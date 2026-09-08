package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomParticipationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", "정처기", "CERTIFICATE");
    private static final ExpiryWindow WINDOW = new ExpiryWindow(NOW.minusSeconds(30), NOW.minusSeconds(30));

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long roomId;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        userId = probe.insertUser();
        roomId = rooms.insertIfCodeFree("1234", userId, NOW, NOW.minusSeconds(600))
                .orElseThrow();
    }

    private long reserve(long user, Instant at) {
        return participations.insertReservation(roomId, user, PROFILE, at);
    }

    @Test
    void 예약_행은_미확정이고_유저의_라이브_방으로_조회된다() {
        long id = reserve(userId, NOW);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.stompConfirmed()).isFalse();
        assertThat(row.joinedAt()).isNull();
        assertThat(participations.findLiveRoomIdOfUser(userId)).contains(roomId);
        assertThat(participations.countLive(roomId)).isEqualTo(1);
        assertThat(participations.existsLive(roomId, userId)).isTrue();
    }

    @Test
    void 확정은_최초_1회만_joined_at을_채우고_세션을_기록한다() {
        reserve(userId, NOW);

        assertThat(participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW))
                .isEqualTo(1);
        Instant joinedAt = participations.lockLive(roomId, userId).orElseThrow().joinedAt();
        assertThat(participations.confirm(roomId, userId, "s2", NOW.plusSeconds(5), "task-A", NOW.plusSeconds(60)))
                .isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.joinedAt()).isEqualTo(joinedAt);
        assertThat(row.stompSessionId()).isEqualTo("s2");
        assertThat(row.taskId()).isEqualTo("task-A");
    }

    @Test
    void 더_늦게_열린_세션이_확정돼_있으면_옛_세션의_확정은_0행이다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "new", NOW.plusSeconds(10), "task-A", NOW);

        assertThat(participations.confirm(roomId, userId, "old", NOW, "task-A", NOW))
                .isZero();
        assertThat(participations.lockLive(roomId, userId).orElseThrow().stompSessionId())
                .isEqualTo("new");
    }

    @Test
    void state_갱신은_현재_세션의_확정_멤버에게만_적용되고_null_필드는_유지된다() {
        reserve(userId, NOW);
        assertThat(participations.updateState(roomId, userId, "s1", true, "DISTRACTED", 100))
                .isZero();

        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);
        assertThat(participations.updateState(roomId, userId, "s1", true, null, 100))
                .isEqualTo(1);
        assertThat(participations.updateState(roomId, userId, "stale", false, "DISTRACTED", 0))
                .isZero();

        RoomMember member = participations.findConfirmedMembers(roomId).getFirst();
        assertThat(member.cameraOn()).isTrue();
        assertThat(member.focusState()).isEqualTo("FOCUS");
        assertThat(member.focusSec()).isEqualTo(100);
        assertThat(member.disconnected()).isFalse();
    }

    @Test
    void 끊김은_세션_ID가_일치할_때만_기록되고_스냅샷에_disconnected로_실린다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);

        assertThat(participations.markDisconnected("other", NOW)).isZero();
        assertThat(participations.markDisconnected("s1", NOW)).isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.disconnectedAt()).isEqualTo(NOW);
        assertThat(row.stompSessionId()).isNull();
        assertThat(row.taskId()).isNull();
        assertThat(participations.findConfirmedMembers(roomId).getFirst().disconnected())
                .isTrue();
        assertThat(participations.isActiveSession(roomId, userId, "s1")).isFalse();
    }

    @Test
    void 만료_조건이_붙은_삭제와_퇴장은_조건에_맞을_때만_바뀌고_두_번째는_0행이다() {
        long unconfirmed = reserve(userId, NOW.minusSeconds(31));
        long other = probe.insertUser();
        long confirmedId = reserve(other, NOW);
        participations.confirm(roomId, other, "s1", NOW, "task-A", NOW);
        participations.markDisconnected("s1", NOW.minusSeconds(31));

        assertThat(participations.deleteUnconfirmed(unconfirmed, new ExpiryWindow(NOW.minusSeconds(60), NOW)))
                .as("예약이 아직 창 안이면 삭제되지 않는다")
                .isZero();
        assertThat(participations.deleteUnconfirmed(unconfirmed, WINDOW)).isEqualTo(1);
        assertThat(participations.markLeft(confirmedId, LeaveReason.DISCONNECT_TIMEOUT, NOW, WINDOW))
                .isEqualTo(1);
        assertThat(participations.markLeft(confirmedId, LeaveReason.EXPLICIT, NOW, null))
                .as("이미 나간 행은 다시 바뀌지 않는다")
                .isZero();

        RoomProbe.Participation left = probe.participation(roomId, other).orElseThrow();
        assertThat(left.leftAt()).isEqualTo(NOW);
        assertThat(left.leaveReason()).isEqualTo("DISCONNECT_TIMEOUT");
        assertThat(probe.participation(roomId, userId)).as("확정 없는 예약은 이력 없이 삭제").isEmpty();
    }

    @Test
    void 만료_후보는_미확정_예약과_유예_만료만_고른다() {
        reserve(userId, NOW.minusSeconds(31));
        long fresh = probe.insertUser();
        reserve(fresh, NOW);
        long graced = probe.insertUser();
        reserve(graced, NOW);
        participations.confirm(roomId, graced, "s1", NOW, "task-A", NOW);
        participations.markDisconnected("s1", NOW.minusSeconds(31));

        List<Candidate> candidates = participations.findExpiryCandidates(WINDOW);

        // 전역 조회라 다른(트랜잭션 없는) 테스트가 커밋한 후보가 섞일 수 있다 — 우리가 만든 두 건의 포함과
        // fresh 제외만 검증한다
        assertThat(candidates)
                .extracting(Candidate::userId)
                .contains(userId, graced)
                .doesNotContain(fresh);
    }

    @Test
    void 죽은_태스크의_참가자는_끊김으로_전환된다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-dead", NOW);

        assertThat(participations.reclaimByTask("task-dead", NOW)).isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.disconnectedAt()).isEqualTo(NOW);
        assertThat(row.taskId()).isNull();
        assertThat(row.stompSessionId()).isNull();
    }

    @Test
    void 리스_행이_없는_task_id의_참가자도_회수된다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "never-registered", NOW);

        assertThat(participations.reclaimOrphansWithoutLease(NOW)).isGreaterThanOrEqualTo(1); // 다른 테스트가 커밋한 고아가 섞일 수 있다
        assertThat(participations.lockLive(roomId, userId).orElseThrow().disconnectedAt())
                .isEqualTo(NOW);
    }

    @Test
    void 활성_세션_스냅샷은_요청자가_현재_세션의_확정_멤버일_때만_목록을_준다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);
        long other = probe.insertUser();
        reserve(other, NOW);

        assertThat(participations.findConfirmedMembersForActiveSession(roomId, userId, "s1"))
                .extracting(RoomMember::userId)
                .containsExactly(userId);
        assertThat(participations.findConfirmedMembersForActiveSession(roomId, userId, "old"))
                .isEmpty();
        assertThat(participations.findConfirmedMembersForActiveSession(roomId, other, "s2"))
                .isEmpty();
        assertThat(participations.findLiveByRoomAndUsers(roomId, Set.of(userId, other)))
                .extracting(Row::userId)
                .containsExactlyInAnyOrder(userId, other);
    }
}
