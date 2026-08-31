package project.study.studysession.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import project.study.studysession.buffer.SnapshotBufferStore.PendingSnapshot;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * BY-492 Redis 버퍼 저장소 — 코얼레싱·역순 무시(나노 정밀도)·at-least-once drain/ack·재기동 내구성을 실제 Redis로
 * 검증한다.
 *
 * <p>스프링 컨텍스트 없이 컨테이너에 직접 붙는다 — 저장소 계층만 빠르게 검증.
 */
class RedisSnapshotBufferStoreTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static RedisSnapshotBufferStore store;

    private final Instant startedAt = Instant.parse("2026-08-20T03:00:00Z");

    @BeforeAll
    static void setUp() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        store = new RedisSnapshotBufferStore(template, new ObjectMapper());
    }

    @AfterAll
    static void tearDown() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void clean() {
        template.delete(List.of(RedisSnapshotBufferStore.BUFFER_KEY, RedisSnapshotBufferStore.DRAIN_KEY));
    }

    private ActiveSessionSnapshotRequest request(long userId, int studySec, Instant reportedAt) {
        return new ActiveSessionSnapshotRequest(userId, startedAt, reportedAt, studySec, studySec, List.of());
    }

    @Test
    void 같은_세션은_최신본_1건으로_코얼레싱된다() {
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now());
        store.offer(request(1L, 120, startedAt.plusSeconds(120)), Instant.now());

        assertThat(store.pendingCount()).isEqualTo(1);

        List<PendingSnapshot> drained = store.drainAll();
        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().request().studySec()).isEqualTo(120);
    }

    @Test
    void reportedAt_역순_도착은_무시된다() {
        store.offer(request(1L, 120, startedAt.plusSeconds(120)), Instant.now());
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now()); // 역순

        List<PendingSnapshot> drained = store.drainAll();
        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().request().studySec()).isEqualTo(120);
    }

    @Test
    void 나노초_차이도_정확히_판정한다() {
        Instant base = startedAt.plusSeconds(60);
        store.offer(request(1L, 60, base), Instant.now());
        store.offer(request(1L, 61, base.plusNanos(1)), Instant.now()); // 같은 밀리초, 1ns 뒤 — 최신
        store.offer(request(1L, 59, base.minusNanos(1)), Instant.now()); // 1ns 앞 — 역순, 무시

        List<PendingSnapshot> drained = store.drainAll();
        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().request().studySec()).isEqualTo(61);
    }

    @Test
    void ack하면_비워지고_drain_후_offer는_다음_사이클로_간다() {
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now());
        assertThat(store.drainAll()).hasSize(1);
        store.offer(request(2L, 30, startedAt.plusSeconds(30)), Instant.now()); // drain 뒤 도착 — 새 버퍼로

        store.ackDrained();

        assertThat(store.pendingCount()).isEqualTo(1); // userId=2만 남음
        List<PendingSnapshot> next = store.drainAll();
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().request().userId()).isEqualTo(2L);
    }

    @Test
    void ack_전에_크래시하면_다음_drain이_같은_배치를_재전달한다() {
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now());
        List<PendingSnapshot> first = store.drainAll();
        assertThat(first).hasSize(1);
        // ack 없이 죽었다고 가정 — 재전달돼야 한다 (UPSERT 멱등이라 중복 무해)
        List<PendingSnapshot> redelivered = store.drainAll();
        assertThat(redelivered).hasSize(1);
        assertThat(redelivered.getFirst().request().userId()).isEqualTo(1L);
        assertThat(store.pendingCount()).isEqualTo(1); // draining 분도 대기분으로 집계
    }

    @Test
    void 잔여_draining이_있으면_그것부터_재전달하고_새_버퍼는_다음_사이클이다() {
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now());
        template.rename(RedisSnapshotBufferStore.BUFFER_KEY, RedisSnapshotBufferStore.DRAIN_KEY); // 크래시 재현
        store.offer(request(2L, 30, startedAt.plusSeconds(30)), Instant.now()); // 새 버퍼에 쌓임

        List<PendingSnapshot> leftover = store.drainAll();
        assertThat(leftover).hasSize(1);
        assertThat(leftover.getFirst().request().userId()).isEqualTo(1L); // 잔여분 우선

        store.ackDrained();
        List<PendingSnapshot> next = store.drainAll();
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().request().userId()).isEqualTo(2L); // 다음 사이클에 새 버퍼
    }

    @Test
    void 재기동해도_대기분이_남는다() {
        store.offer(request(1L, 60, startedAt.plusSeconds(60)), Instant.now());

        // 재기동 시뮬레이션 — 새 커넥션·새 저장소 인스턴스로 같은 Redis를 본다
        LettuceConnectionFactory cf2 = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        cf2.afterPropertiesSet();
        StringRedisTemplate t2 = new StringRedisTemplate(cf2);
        t2.afterPropertiesSet();
        RedisSnapshotBufferStore store2 = new RedisSnapshotBufferStore(t2, new ObjectMapper());

        assertThat(store2.pendingCount()).isEqualTo(1);
        List<PendingSnapshot> drained = store2.drainAll();
        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().request().userId()).isEqualTo(1L);
        cf2.destroy();
    }
}
