package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.entity.CloseReason;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(600);

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long owner;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        owner = probe.insertUser();
    }

    @Test
    void 열린_방이_없는_코드는_발급된다() {
        Optional<Long> id = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF);

        assertThat(id).isPresent();
        assertThat(probe.room(id.get()))
                .get()
                .extracting(RoomProbe.RoomRow::inviteCode)
                .isEqualTo("1234");
    }

    @Test
    void 같은_코드의_열린_방이_있으면_발급되지_않는다() {
        rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF);

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isEmpty();
    }

    @Test
    void 닫힌_지_10분_안인_코드는_묘비라_발급되지_않는다() {
        long closed = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        probe.closeRoomAt(closed, NOW.minusSeconds(599));

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isEmpty();
    }

    @Test
    void 닫힌_지_10분이_지난_코드는_다시_발급된다() {
        long closed = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        probe.closeRoomAt(closed, NOW.minusSeconds(601));

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isPresent();
    }

    @Test
    void 코드로_찾으면_가장_최근_방이_나온다() {
        long first = rooms.insertIfCodeFree("1234", owner, NOW.minusSeconds(1000), CUTOFF.minusSeconds(1000))
                .orElseThrow();
        probe.closeRoomAt(first, NOW.minusSeconds(900));
        long second = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();

        assertThat(rooms.findLatestByCode("1234")).get().extracting(RoomRow::id).isEqualTo(second);
        assertThat(rooms.findLatestByCode("0000")).isEmpty();
    }

    @Test
    void 라이브_참가자가_없을_때만_닫힌다() {
        long roomId = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        jdbc.sql("insert into room_participations (room_id, user_id, reserved_at) values (:r, :u, :t)")
                .param("r", roomId)
                .param("u", owner)
                .param("t", java.sql.Timestamp.from(NOW))
                .update();

        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW))
                .isFalse();
        assertThat(rooms.isOpen(roomId)).isTrue();

        jdbc.sql("update room_participations set left_at = :t where room_id = :r")
                .param("t", java.sql.Timestamp.from(NOW))
                .param("r", roomId)
                .update();

        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW))
                .isTrue();
        assertThat(rooms.isOpen(roomId)).isFalse();
        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW))
                .as("이미 닫힌 방은 다시 닫히지 않는다(멱등)")
                .isFalse();
        assertThat(probe.room(roomId))
                .get()
                .extracting(RoomProbe.RoomRow::closeReason)
                .isEqualTo("LAST_LEFT");
    }

    @Test
    void 여러_방을_id_오름차순으로_잠근다() {
        long a = rooms.insertIfCodeFree("1111", owner, NOW, CUTOFF).orElseThrow();
        long b = rooms.insertIfCodeFree("2222", owner, NOW, CUTOFF).orElseThrow();

        List<RoomRow> locked = rooms.lockByIds(List.of(b, a));

        assertThat(locked).extracting(RoomRow::id).containsExactly(a, b);
        assertThat(rooms.lockById(a)).get().extracting(RoomRow::isOpen).isEqualTo(true);
        assertThat(rooms.lockById(999_999L)).isEmpty();
    }

    @Test
    void 참가자_없이_기한이_지난_열린_방만_빈_방_후보다() {
        long old = rooms.insertIfCodeFree("1111", owner, NOW.minusSeconds(601), CUTOFF)
                .orElseThrow();
        rooms.insertIfCodeFree("2222", owner, NOW.minusSeconds(10), CUTOFF);

        assertThat(rooms.findEmptyOpenRoomsCreatedBefore(NOW.minusSeconds(600)))
                .extracting(RoomRow::id)
                .containsExactly(old);
    }
}
