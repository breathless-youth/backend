package project.study.common.tx;

import io.sentry.Sentry;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 업무 트랜잭션이 커밋된 뒤에만 부수 효과(푸시·STOMP 발송)를 실행한다 (BY-1124). 롤백되면 아무것도 나가지 않아, 사용자가 받은 알림과
 * DB 상태가 어긋나지 않는다.
 *
 * <p>트랜잭션 동기화가 꺼져 있으면(트랜잭션 밖 호출) 바로 실행한다. 실행 중 예외는 잡아서 로그와 Sentry에만 남기고 던지지 않는다 — 커밋은
 * 이미 끝나 호출자가 되돌릴 수 없고, 한 작업의 실패가 뒤에 등록된 작업을 막으면 안 된다.
 *
 * <p>커밋 뒤 작업 안에서 다시 {@code run}을 부르면(예: 푸시 발송기를 부르는 작업 안의 또 다른 발송) 그 자리에서 바로 실행한다. Spring은
 * {@code afterCommit}을 도는 동안에도 동기화가 켜져 있다고 답하지만, 이미 순회를 시작한 뒤에 등록한 콜백은 호출하지 않고 지워 버린다 —
 * 새로 등록하면 아무 로그도 없이 사라진다. 이미 커밋된 트랜잭션이라 기다릴 것이 없다.
 *
 * <p>트랜잭션마다 하나의 {@link Collector}가 작업을 모아 두고 그 트랜잭션의 {@code afterCommit}에서 등록 순서대로 실행한다. 커밋 뒤 작업
 * 안에서 {@code REQUIRES_NEW}로 새 트랜잭션을 열면 그쪽에는 자기 {@code Collector}가 따로 있어, 그 트랜잭션이 커밋될 때 실행된다.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }
        Collector collector = currentCollector();
        if (collector == null) {
            collector = new Collector();
            TransactionSynchronizationManager.registerSynchronization(collector);
        }
        collector.add(action);
    }

    private static Collector currentCollector() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Collector collector) {
                return collector;
            }
        }
        return null;
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("커밋 뒤 작업 실패", e);
            Sentry.captureException(e);
        }
    }

    /** 한 트랜잭션의 커밋 뒤 작업 모음. 롤백되면 {@code afterCommit}이 불리지 않아 모은 작업이 그대로 버려진다. */
    private static final class Collector implements TransactionSynchronization {

        private final List<Runnable> pending = new ArrayList<>();
        private boolean committed;

        void add(Runnable action) {
            if (committed) {
                runSafely(action);
            } else {
                pending.add(action);
            }
        }

        @Override
        public void afterCommit() {
            // 먼저 표시해야 실행 중 다시 부른 run이 모음에 쌓이지 않고 바로 실행된다
            committed = true;
            for (Runnable action : pending) {
                runSafely(action);
            }
            pending.clear();
        }
    }
}
