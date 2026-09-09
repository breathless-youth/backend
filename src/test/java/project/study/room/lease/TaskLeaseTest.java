package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.room.lease.LeaseConnectionFactory.LeaseConnection;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.support.MutableClock;
import project.study.room.websocket.SessionRegistry;

class TaskLeaseTest {

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");
    private static final Heartbeat OK = new Heartbeat(true, null);

    private final TaskLeaseRepository leases = mock(TaskLeaseRepository.class);
    private final SessionRegistry registry = mock(SessionRegistry.class);
    private final MutableClock clock = MutableClock.at(T0);
    private TaskLease lease;

    @BeforeEach
    void setUp() {
        LeaseConnectionFactory factory = mock(LeaseConnectionFactory.class);
        when(factory.open()).thenReturn(new LeaseConnection(leases, () -> {}));
        lease = new TaskLease(new TaskIdentity(""), factory, registry, clock, 30);
        lease.start();
    }

    @AfterEach
    void tearDown() {
        lease.stop();
    }

    // doReturn을 쓴다 — 실패 스텁이 걸린 뒤 when(leases.heartbeat(...))로 다시 스텁하면 스텁하는 호출 자체가 던진다
    private void beatAfter(long seconds, Heartbeat result) {
        clock.advance(Duration.ofSeconds(seconds));
        doReturn(result).when(leases).heartbeat(any(), any());
        lease.beat();
    }

    @Test
    void 기동_시_리스를_등록하고_관찰_기간이_찰_때까지는_회수하지_않는다() {
        verify(leases).register(any(), eq(T0));
        assertThat(lease.canReclaim()).isFalse();

        for (int i = 0; i < 5; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).as("25초는 아직 부족").isFalse();

        beatAfter(5, OK);
        assertThat(lease.canReclaim()).as("30초 연속이면 회수 가능").isTrue();
    }

    @Test
    void heartbeat_간격이_stale을_넘기면_관찰_기간이_다시_시작된다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).isTrue();

        beatAfter(31, OK);

        assertThat(lease.canReclaim()).as("31초 공백 뒤 첫 beat — 관찰 기간 리셋").isFalse();
    }

    @Test
    void 마지막_커밋_beat가_stale을_넘기면_회수할_수_없다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).isTrue();

        // beat 없이 시간만 흐른다 (DB 순단) — 연속 구간은 30초를 채웠지만 최신성이 깨졌다
        clock.advance(Duration.ofSeconds(31));
        assertThat(lease.canReclaim())
                .as("마지막 커밋 beat가 31초 전 — 상대를 죽었다고 판정할 자격이 없다")
                .isFalse();

        // 순단이 풀려 beat가 커밋돼도 공백(36초)이 stale을 넘었으므로 관찰 기간이 처음부터 다시 찬다
        beatAfter(5, OK);
        assertThat(lease.canReclaim()).as("공백 뒤 첫 beat — 관찰 기간 리셋").isFalse();
    }

    @Test
    void 리스가_회수됐으면_소켓을_전부_닫고_리스를_되살린다() {
        beatAfter(5, new Heartbeat(true, T0.plusSeconds(4)));

        verify(registry).fence();
        verify(leases, times(2)).register(any(), any());
        assertThat(lease.canReclaim()).as("되살린 직후는 관찰 기간이 처음부터").isFalse();
    }

    @Test
    void 리스_행이_사라졌어도_펜싱한다() {
        beatAfter(5, new Heartbeat(false, null));

        verify(registry).fence();
    }

    // 실패는 삼키되(스케줄이 죽지 않게) 회수 자격은 그 자리에서 잃는다 — 마지막 성공이 아직 stale 이내라도
    // 회복 직후의 cleanup 틱이 옛 연속 구간을 들고 아직 못 돌아온 상대를 회수하면 안 된다
    @Test
    void heartbeat_실패는_삼키되_회수_자격을_즉시_잃는다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        clock.advance(Duration.ofSeconds(5));
        when(leases.heartbeat(any(), any())).thenThrow(new RuntimeException("db down"));

        lease.beat();

        verify(registry, never()).fence();
        assertThat(lease.canReclaim()).as("실패 직후 — 다음 성공까지 회수 불가").isFalse();
    }

    // 실패한 beat는 5초짜리라 간격만 보면 공백으로 안 잡힌다 — 순단에서 먼저 회복한 쪽이 옛 연속 구간을
    // 그대로 들고 아직 못 돌아온 상대를 회수하지 않도록, 실패 뒤 첫 성공이 관찰 기간을 다시 시작해야 한다
    @Test
    void heartbeat_실패_뒤_첫_성공은_관찰_기간을_다시_시작한다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).isTrue();

        clock.advance(Duration.ofSeconds(5));
        doThrow(new RuntimeException("db down")).when(leases).heartbeat(any(), any());
        lease.beat();
        assertThat(lease.canReclaim()).as("실패 직후 — 회수 불가").isFalse();

        beatAfter(5, OK);

        assertThat(lease.canReclaim()).as("실패 뒤 첫 성공 — 관찰 기간 재시작").isFalse();
    }

    @Test
    void 리스_되살리기가_실패해도_관찰_기간은_리셋된다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).isTrue();

        doThrow(new RuntimeException("db down")).when(leases).register(any(), any());
        beatAfter(5, new Heartbeat(true, T0.plusSeconds(4)));

        assertThat(lease.canReclaim()).isFalse();
        verify(registry).fence();
    }
}
