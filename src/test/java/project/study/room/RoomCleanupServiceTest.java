package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.TestcontainersConfiguration;
import project.study.room.lease.TaskLease;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomCleanupServiceTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Profile PROFILE = new Profile("포메", null, null);

    @Autowired
    private RoomCleanupService cleanup;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private TaskLeaseRepository leases;

    @Autowired
    private TaskLease taskLease;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private Instant now;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        // 기준 시각을 2분 전으로 둔다 — 이 태스크 자신의 리스(실시간 heartbeat)가 낡은 것으로 보이지 않게
        now = Instant.now().minusSeconds(120);
        userId = probe.insertUser();
    }

    private long openRoom(Instant createdAt) {
        for (int i = 0; i < 100; i++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            var id = rooms.insertIfCodeFree(code, userId, createdAt, createdAt.minusSeconds(600));
            if (id.isPresent()) return id.get();
        }
        throw new IllegalStateException("코드 소진");
    }

    @Test
    void 삼십초_넘은_미확정_예약은_지워지고_방도_닫히며_AutoLeave가_나온다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now.minusSeconds(31));

        List<AutoLeave> removed = cleanup.cleanupExpired(now);

        assertThat(removed).contains(new AutoLeave(roomId, userId));
        assertThat(probe.participation(roomId, userId)).isEmpty();
        assertThat(probe.room(roomId))
                .get()
                .extracting(RoomProbe.RoomRow::closeReason)
                .isEqualTo("LAST_LEFT");
    }

    @Test
    void 유예_삼십초가_지난_참가자는_DISCONNECT_TIMEOUT으로_남고_아직이면_유지된다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now.minusSeconds(60));
        participations.confirm(roomId, userId, "s1", now, "t", now.minusSeconds(60));
        participations.markDisconnected("s1", now.minusSeconds(29));

        assertThat(cleanup.cleanupExpired(now)).doesNotContain(new AutoLeave(roomId, userId));

        assertThat(cleanup.cleanupExpired(now.plusSeconds(2))).contains(new AutoLeave(roomId, userId));
        RoomProbe.Participation history = probe.participation(roomId, userId).orElseThrow();
        assertThat(history.leaveReason()).isEqualTo("DISCONNECT_TIMEOUT");
        assertThat(rooms.isOpen(roomId)).isFalse();
    }

    @Test
    void 입장_이력_없는_빈_방은_10분_뒤_EMPTY_EXPIRED로_닫힌다() {
        long fresh = openRoom(now.minusSeconds(599));
        long old = openRoom(now.minusSeconds(601));
        long occupied = openRoom(now.minusSeconds(601));
        participations.insertReservation(occupied, userId, PROFILE, now);
        participations.confirm(occupied, userId, "s1", now, "t", now);

        cleanup.cleanupExpired(now);

        assertThat(rooms.isOpen(fresh)).isTrue();
        assertThat(rooms.isOpen(occupied)).isTrue();
        assertThat(probe.room(old))
                .get()
                .extracting(RoomProbe.RoomRow::closeReason)
                .isEqualTo("EMPTY_EXPIRED");
    }

    @Test
    void 죽은_태스크의_참가자는_끊김으로_전환되고_유예_뒤_회수된다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "task-dead", now);
        leases.register("task-dead", now.minusSeconds(31));

        cleanup.cleanupExpired(now);

        RoomProbe.Participation graced = probe.participation(roomId, userId).orElseThrow();
        assertThat(graced.disconnectedAt()).isNotNull();
        assertThat(graced.taskId()).isNull();
        assertThat(probe.reclaimedAt("task-dead")).isPresent();

        assertThat(cleanup.cleanupExpired(now.plusSeconds(31))).contains(new AutoLeave(roomId, userId));
    }

    @Test
    void 리스_행이_없는_task_id의_참가자도_회수되고_회수된_리스는_10분_뒤_지워진다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "never-registered", now);
        leases.register("task-old", now.minusSeconds(2000));
        leases.reclaim("task-old", now.minusSeconds(30), now.minusSeconds(601));

        cleanup.cleanupExpired(now);

        assertThat(probe.participation(roomId, userId).orElseThrow().disconnectedAt())
                .isNotNull();
        assertThat(probe.heartbeatAt("task-old")).isEmpty();
    }

    // disconnect의 DB 갱신이 실패하면 소켓은 없는데 행은 확정 상태로 남는다 — heartbeat가 살아 있어
    // 리스 회수에도 안 걸리므로, 자기 세션 대조가 유일한 복구 경로다
    @Test
    void 소켓이_없는_자기_태스크의_참가자는_끊김으로_전환된다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "ghost-session", now, taskLease.taskId(), now);

        cleanup.cleanupExpired(now);

        RoomProbe.Participation reconciled = probe.participation(roomId, userId).orElseThrow();
        assertThat(reconciled.disconnectedAt()).isNotNull();
        assertThat(reconciled.taskId()).isNull();
    }

    @Test
    void 신선한_리스의_참가자는_건드리지_않는다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "task-alive", now);
        leases.register("task-alive", now.minusSeconds(29));

        cleanup.cleanupExpired(now);

        assertThat(probe.participation(roomId, userId).orElseThrow().disconnectedAt())
                .isNull();
        assertThat(probe.reclaimedAt("task-alive")).isEmpty();
    }
}
