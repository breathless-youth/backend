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
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.service.ParticipantRemover;
import project.study.room.service.ParticipantRemover.Removed;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ParticipantRemoverTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", null, null);

    @Autowired
    private ParticipantRemover remover;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private RoomRow room;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        userId = probe.insertUser();
        long roomId = rooms.insertIfCodeFree("1234", userId, NOW, NOW.minusSeconds(600))
                .orElseThrow();
        room = rooms.lockById(roomId).orElseThrow();
    }

    private Row live() {
        return participations.lockLive(room.id(), userId).orElseThrow();
    }

    @Test
    void 확정_없는_예약은_삭제되고_마지막이면_방이_닫힌다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);

        Removed removed = remover.remove(live(), room, LeaveReason.EXPLICIT, NOW, null);

        assertThat(removed).isEqualTo(new Removed(true, true));
        assertThat(probe.participation(room.id(), userId)).isEmpty();
        assertThat(probe.room(room.id()))
                .get()
                .extracting(RoomProbe.RoomRow::closeReason)
                .isEqualTo("LAST_LEFT");
    }

    @Test
    void 확정된_참가자는_이력으로_남고_다른_참가자가_있으면_방은_열려_있다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);
        participations.confirm(room.id(), userId, "s1", NOW, "t", NOW);
        long other = probe.insertUser();
        participations.insertReservation(room.id(), other, PROFILE, NOW);

        Removed removed = remover.remove(live(), room, LeaveReason.SWITCHED_ROOM, NOW.plusSeconds(60), null);

        assertThat(removed).isEqualTo(new Removed(true, false));
        RoomProbe.Participation history = probe.participation(room.id(), userId).orElseThrow();
        assertThat(history.leftAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(history.leaveReason()).isEqualTo("SWITCHED_ROOM");
        assertThat(rooms.isOpen(room.id())).isTrue();
    }

    @Test
    void 만료_조건이_안_맞거나_이미_나간_행이면_아무것도_바뀌지_않는다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);
        Row row = live();

        ExpiryWindow notYet = new ExpiryWindow(NOW.minusSeconds(30), NOW.minusSeconds(30));
        assertThat(remover.remove(row, room, LeaveReason.DISCONNECT_TIMEOUT, NOW, notYet))
                .isEqualTo(Removed.NONE);
        assertThat(rooms.isOpen(room.id())).isTrue();

        assertThat(remover.remove(row, room, LeaveReason.EXPLICIT, NOW, null).removed())
                .isTrue();
        assertThat(remover.remove(row, room, LeaveReason.EXPLICIT, NOW, null)).isEqualTo(Removed.NONE);
    }
}
