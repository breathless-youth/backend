package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private void beatAfter(long seconds, Heartbeat result) {
        clock.advance(Duration.ofSeconds(seconds));
        when(leases.heartbeat(any(), any())).thenReturn(result);
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

    @Test
    void heartbeat_실패는_삼키고_상태를_바꾸지_않는다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        clock.advance(Duration.ofSeconds(5));
        when(leases.heartbeat(any(), any())).thenThrow(new RuntimeException("db down"));

        lease.beat();

        verify(registry, never()).fence();
        assertThat(lease.canReclaim()).isTrue();
    }
}
