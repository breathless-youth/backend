package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TaskLeaseRepositoryTest {

    // 과거로 고정 — 이 컨텍스트의 실제 TaskLease(SmartLifecycle)가 기동 시 실제 시각으로 자기 리스를
    // 등록하므로, NOW가 "오늘"이면 실행 시각에 따라 그 실제 행이 findStaleUnreclaimed에 우연히 걸릴 수 있다.
    // 과거 고정 시각이면 실제 heartbeat_at(항상 "현재")이 threshold보다 항상 뒤라 절대 걸리지 않는다.
    private static final Instant NOW = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minusSeconds(30);

    @Autowired
    private TaskLeaseRepository leases;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
    }

    @Test
    void 등록_후_heartbeat는_정상이고_없는_태스크의_heartbeat는_펜싱이다() {
        leases.register("t1", NOW);

        Heartbeat ok = leases.heartbeat("t1", NOW.plusSeconds(5));
        assertThat(ok.fenced()).isFalse();
        assertThat(probe.heartbeatAt("t1")).contains(NOW.plusSeconds(5));

        assertThat(leases.heartbeat("ghost", NOW).fenced()).isTrue();
    }

    @Test
    void 낡은_리스만_회수되고_회수된_리스의_heartbeat는_펜싱이다() {
        leases.register("stale", NOW.minusSeconds(31));
        leases.register("fresh", NOW.minusSeconds(29));

        assertThat(leases.findStaleUnreclaimed(THRESHOLD)).containsExactly("stale");
        assertThat(leases.reclaim("stale", THRESHOLD, NOW)).isTrue();
        assertThat(leases.reclaim("fresh", THRESHOLD, NOW)).as("신선한 리스는 회수 0행").isFalse();
        assertThat(leases.reclaim("stale", THRESHOLD, NOW)).as("두 번째 회수는 0행").isFalse();
        assertThat(leases.findStaleUnreclaimed(THRESHOLD)).isEmpty();

        Heartbeat fenced = leases.heartbeat("stale", NOW.plusSeconds(1));
        assertThat(fenced.rowExists()).isTrue();
        assertThat(fenced.reclaimedAt()).isEqualTo(NOW);
        assertThat(fenced.fenced()).isTrue();
    }

    @Test
    void heartbeat가_먼저_커밋되면_회수는_0행이다() {
        leases.register("t1", NOW.minusSeconds(31));

        leases.heartbeat("t1", NOW);

        assertThat(leases.reclaim("t1", THRESHOLD, NOW)).isFalse();
    }

    @Test
    void 되살리기는_reclaimed_at을_비우고_10분_지난_회수_리스는_지워진다() {
        leases.register("t1", NOW.minusSeconds(31));
        leases.reclaim("t1", THRESHOLD, NOW);

        leases.register("t1", NOW.plusSeconds(5));
        assertThat(probe.reclaimedAt("t1")).isEmpty();
        assertThat(leases.heartbeat("t1", NOW.plusSeconds(10)).fenced()).isFalse();

        leases.register("old", NOW.minusSeconds(1000));
        leases.reclaim("old", THRESHOLD, NOW.minusSeconds(601));
        assertThat(leases.deleteReclaimedBefore(NOW.minusSeconds(600))).isEqualTo(1);
        assertThat(probe.heartbeatAt("old")).isEmpty();
        assertThat(probe.heartbeatAt("t1")).isPresent();
    }
}
