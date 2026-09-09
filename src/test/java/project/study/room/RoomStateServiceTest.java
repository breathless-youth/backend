package project.study.room;

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
import project.study.room.dto.RoomMember;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomRepository;
import project.study.room.service.RoomStateService;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", "정처기", "CERTIFICATE");

    @Autowired
    private RoomStateService state;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long roomId;
    private long me;
    private long peer;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        me = probe.insertUser();
        peer = probe.insertUser();
        roomId = rooms.insertIfCodeFree("1234", me, NOW, NOW.minusSeconds(600)).orElseThrow();
        participations.insertReservation(roomId, me, PROFILE, NOW);
        participations.insertReservation(roomId, peer, PROFILE, NOW);
    }

    @Test
    void 확정_전에는_상태_갱신도_시그널도_거부된다() {
        assertThat(state.updateState(roomId, me, "s1", true, "DISTRACTED", 10)).isFalse();
        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).isFalse();
        assertThat(state.hasParticipant(roomId, me)).as("예약자는 구독 인가는 통과").isTrue();
        assertThat(state.isConfirmedMember(roomId, me)).isFalse();
        assertThat(state.getRoomIdForUser(me)).isEqualTo(roomId);
        assertThat(state.getRoomIdForUser(999_999L)).isNull();
    }

    @Test
    void 현재_세션의_확정_멤버만_상태를_갱신하고_무효_필드는_유지된다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);

        assertThat(state.updateState(roomId, me, "s1", null, null, null))
                .as("전부 null이면 SQL 없이 false")
                .isFalse();
        assertThat(state.updateState(roomId, me, "s1", true, null, 1500)).isTrue();
        assertThat(state.updateState(roomId, me, "old", false, "DISTRACTED", 0)).isFalse();
        assertThat(state.updateState(roomId, me, null, false, "DISTRACTED", 0)).isFalse();

        RoomMember member = state.getMembers(roomId).getFirst();
        assertThat(member.cameraOn()).isTrue();
        assertThat(member.focusState()).isEqualTo("FOCUS");
        assertThat(member.focusSec()).isEqualTo(1500);
    }

    @Test
    void 시그널은_발신자가_현재_세션의_확정_멤버이고_수신자가_확정_멤버일_때만_허용된다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);

        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).as("수신자 미확정").isFalse();
        participations.confirm(roomId, peer, "s2", NOW, "t", NOW);
        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).isTrue();
        assertThat(state.authorizeSignal(roomId, me, "s1", me))
                .as("자기 자신에게도 허용(k6 경로)")
                .isTrue();
        assertThat(state.authorizeSignal(roomId, me, "old", peer)).as("옛 세션").isFalse();
        assertThat(state.authorizeSignal(roomId, me, null, peer)).as("세션 없음").isFalse();
        assertThat(state.authorizeSignal(roomId, me, "s1", null))
                .as("수신자 ID 없음 — 목록 구성 전에 거절")
                .isFalse();
        assertThat(state.authorizeSignal(roomId, me, "s1", 999_999L))
                .as("비멤버 수신자")
                .isFalse();
    }

    @Test
    void 스냅샷_재요청은_인가와_조회가_한_번이다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);
        participations.confirm(roomId, peer, "s2", NOW, "t", NOW);

        assertThat(state.getMembersForActiveSession(roomId, me, "s1"))
                .extracting(RoomMember::userId)
                .containsExactlyInAnyOrder(me, peer);
        assertThat(state.getMembersForActiveSession(roomId, me, "old")).isEmpty();
        assertThat(state.getMembersForActiveSession(roomId, me, null)).isEmpty();
        assertThat(state.getMembersForActiveSession(roomId + 1, me, "s1")).isEmpty();
        assertThat(state.isActiveSession(roomId, me, "s1")).isTrue();
        assertThat(state.isActiveSession(roomId, me, "old")).isFalse();
        assertThat(state.isActiveSession(roomId, me, null)).isFalse();
    }
}
