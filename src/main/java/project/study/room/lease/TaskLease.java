package project.study.room.lease;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import project.study.room.lease.LeaseConnectionFactory.LeaseConnection;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.websocket.SessionRegistry;

/**
 * 태스크 리스 (스펙 §3).
 *
 * <p>phase -1000: STOMP 핸들러(phase 0)보다 먼저 시작해 소켓이 생기기 전에 리스를 등록하고, 핸들러보다 뒤에
 * 멈춰 소켓이 다 닫힐 때까지 heartbeat를 유지한다. heartbeat 스레드는 이 클래스가 직접 소유한다 — Spring의
 * ThreadPoolTaskScheduler는 ContextClosedEvent 시점에 조기 종료해 graceful shutdown 중 heartbeat가 끊긴다.
 * 종료 때 리스 행을 지우지 않는다(낡은 리스는 스윕이 참가자 회수 뒤 치운다).
 *
 * <p>펜싱: heartbeat가 "회수됨"을 돌려주면 다른 태스크가 내 참가자 세션을 비운 것이다. 살아 있는 소켓의 프레임은
 * 전부 거부되고 FE는 재접속하지 않으므로, 소켓을 전부 닫아 5초 뒤 재접속하게 만들고 리스를 되살린다.
 */
@Component
@Slf4j
public class TaskLease implements SmartLifecycle {

    public static final int PHASE = -1000;
    public static final long HEARTBEAT_SECONDS = 5;
    public static final long STALE_SECONDS = 30;

    private final TaskIdentity identity;
    private final LeaseConnectionFactory connectionFactory;
    private final SessionRegistry sessionRegistry;
    private final Clock clock;
    private final long observationSeconds;

    private LeaseConnection connection;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile Instant lastCommittedBeat;
    private volatile Instant continuousSince;

    public TaskLease(
            TaskIdentity identity,
            LeaseConnectionFactory connectionFactory,
            SessionRegistry sessionRegistry,
            Clock clock,
            @Value("${app.room.lease.observation-seconds:30}") long observationSeconds) {
        this.identity = identity;
        this.connectionFactory = connectionFactory;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
        this.observationSeconds = observationSeconds;
    }

    @Override
    public void start() {
        connection = connectionFactory.open();
        Instant now = clock.instant();
        connection.leases().register(identity.id(), now);
        lastCommittedBeat = now;
        continuousSince = now;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "task-lease");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        running = true;
        log.info("태스크 리스 등록: taskId={}", identity.id());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public String taskId() {
        return identity.id();
    }

    /** heartbeat 한 번. 실패는 기록하지 않고 다음 틱에 다시 시도한다 — 커밋된 beat만 연속성에 센다. */
    public void beat() {
        Instant now = clock.instant();
        try {
            Heartbeat heartbeat = connection.leases().heartbeat(identity.id(), now);
            if (heartbeat.fenced()) {
                fence(now);
            } else {
                recordBeat(now);
            }
        } catch (RuntimeException e) {
            log.warn("heartbeat 실패: taskId={}", identity.id(), e);
        }
    }

    /** 남을 죽었다고 판정해도 되는가 — 내 커밋된 heartbeat가 관찰 기간 이상 stale 초과 공백 없이 이어졌을 때만. */
    public boolean canReclaim() {
        Instant since = continuousSince;
        Instant last = lastCommittedBeat;
        return since != null && last != null && Duration.between(since, last).getSeconds() >= observationSeconds;
    }

    private void recordBeat(Instant now) {
        Instant last = lastCommittedBeat;
        if (last == null || Duration.between(last, now).getSeconds() > STALE_SECONDS) {
            continuousSince = now;
        }
        lastCommittedBeat = now;
    }

    private void fence(Instant now) {
        int closed = sessionRegistry.fence();
        connection.leases().register(identity.id(), now);
        continuousSince = now;
        lastCommittedBeat = now;
        log.warn("펜싱: 리스가 회수되어 세션 {}개를 닫고 리스를 되살림 taskId={}", closed, identity.id());
    }
}
