package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
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
import project.study.room.service.RoomService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RoomHistoryRecorderTest.SyncExecutorConfig.class})
class RoomHistoryRecorderTest {

    @TestConfiguration
    static class SyncExecutorConfig {
        // @Async("roomHistoryExecutor")가 이름으로 찾으므로 같은 이름의 동기 실행기로 교체한다
        @Bean(name = "roomHistoryExecutor")
        TaskExecutor roomHistoryExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomService roomService;

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

    private RoomHistory findRoomOfCreator(long creatorId) {
        return roomHistoryRepository.findAll().stream()
                .filter(room -> room.getCreatedBy().equals(creatorId))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    void 생성부터_퇴장까지_이력이_기록된다() {
        long creator = registerUser();

        String code = roomService.create(creator).inviteCode();
        RoomHistory room = findRoomOfCreator(creator);
        assertThat(room.getClosedAt()).isNull();

        long roomId =
                roomService.join(creator, code, "포메테스트", null, null).response().roomId();
        roomService.confirmStomp(roomId, creator, "session-" + creator);

        RoomParticipation participation = roomParticipationRepository
                .findByRoomUidAndUserIdAndLeftAtIsNull(room.getRoomUid(), creator)
                .orElseThrow();
        assertThat(participation.getJoinedAt()).isNotNull();

        roomService.leave(roomId, creator);

        RoomParticipation closed = roomParticipationRepository.findAll().stream()
                .filter(p -> p.getRoomUid().equals(room.getRoomUid())
                        && p.getUserId().equals(creator))
                .findFirst()
                .orElseThrow();
        assertThat(closed.getLeftAt()).isNotNull();
        assertThat(closed.getLeaveReason()).isEqualTo(LeaveReason.EXPLICIT);

        RoomHistory closedRoom =
                roomHistoryRepository.findByRoomUid(room.getRoomUid()).orElseThrow();
        assertThat(closedRoom.getClosedAt()).isNotNull();
        assertThat(closedRoom.getCloseReason()).isEqualTo(CloseReason.LAST_LEFT);
    }

    @Test
    void 예약만_하고_확정하지_않으면_참여_기록이_없다() {
        long creator = registerUser();

        String code = roomService.create(creator).inviteCode();
        RoomHistory room = findRoomOfCreator(creator);

        roomService.join(creator, code, "포메테스트", null, null);
        roomService.cleanupExpired(Instant.now().plusSeconds(31));

        boolean hasParticipation = roomParticipationRepository.findAll().stream()
                .anyMatch(p -> p.getRoomUid().equals(room.getRoomUid()));
        assertThat(hasParticipation).isFalse();

        // 마지막 예약 만료로 방도 소멸 — LAST_LEFT로 닫힌다
        RoomHistory closedRoom =
                roomHistoryRepository.findByRoomUid(room.getRoomUid()).orElseThrow();
        assertThat(closedRoom.getCloseReason()).isEqualTo(CloseReason.LAST_LEFT);
    }
}
