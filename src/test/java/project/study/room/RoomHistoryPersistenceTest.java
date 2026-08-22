package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.room.entity.RoomHistory;
import project.study.room.entity.RoomParticipation;
import project.study.room.event.CloseReason;
import project.study.room.event.LeaveReason;
import project.study.room.repository.RoomHistoryRepository;
import project.study.room.repository.RoomParticipationRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoomHistoryPersistenceTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomHistoryRepository roomHistoryRepository;

    @Autowired
    private RoomParticipationRepository roomParticipationRepository;

    private long registerUser() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\": \"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    @Test
    void 방_이력과_참여_이력을_저장하고_조회한다() {
        long userId = registerUser();
        UUID roomUid = UUID.randomUUID();
        Instant now = Instant.now();

        roomHistoryRepository.save(new RoomHistory(roomUid, userId, now));
        roomParticipationRepository.save(new RoomParticipation(roomUid, userId, now));

        RoomHistory room = roomHistoryRepository.findByRoomUid(roomUid).orElseThrow();
        assertThat(room.getCreatedBy()).isEqualTo(userId);
        assertThat(room.getClosedAt()).isNull();

        RoomParticipation participation = roomParticipationRepository
                .findByRoomUidAndUserIdAndLeftAtIsNull(roomUid, userId)
                .orElseThrow();
        assertThat(participation.getJoinedAt()).isEqualTo(now);
    }

    @Test
    void 닫힌_참여는_open_조회에서_제외된다() {
        long userId = registerUser();
        UUID roomUid = UUID.randomUUID();
        Instant now = Instant.now();

        RoomHistory room = new RoomHistory(roomUid, userId, now);
        room.close(now, CloseReason.LAST_LEFT);
        roomHistoryRepository.save(room);

        RoomParticipation participation = new RoomParticipation(roomUid, userId, now);
        participation.close(now, LeaveReason.EXPLICIT);
        roomParticipationRepository.save(participation);

        assertThat(roomParticipationRepository.findByRoomUidAndUserIdAndLeftAtIsNull(roomUid, userId))
                .isEmpty();
        assertThat(roomHistoryRepository.findByRoomUid(roomUid).orElseThrow().getCloseReason())
                .isEqualTo(CloseReason.LAST_LEFT);
    }
}
