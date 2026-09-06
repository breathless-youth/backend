package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.room.service.RoomService;
import project.study.room.service.RoomStateSnapshot;
import project.study.room.snapshot.RoomSnapshotLifecycle;
import project.study.room.snapshot.RoomStateSnapshotRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class RoomSnapshotLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private RoomStateSnapshotRepository repository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RoomService roomService;
    private RoomSnapshotLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of(), event -> {});
        lifecycle = new RoomSnapshotLifecycle(roomService, repository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 정지_시_방이_있으면_스냅샷을_저장한다() {
        String code = roomService.create(1L, 1_000_001L).inviteCode();
        roomService.join(100L, code, "포메100", null, null);

        lifecycle.start();
        lifecycle.stop();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).save(payload.capture(), eq(NOW));
        RoomStateSnapshot saved = objectMapper.readValue(payload.getValue(), RoomStateSnapshot.class);
        assertThat(saved.rooms()).hasSize(1);
        assertThat(saved.rooms().get(0).id()).isEqualTo(1_000_001L);
        assertThat(saved.rooms().get(0).participants())
                .extracting(p -> p.userId())
                .containsExactly(100L);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void 정지_시_방도_묘비도_없으면_저장하지_않고_기존_스냅샷을_지운다() {
        lifecycle.stop();

        verify(repository).clear();
        verify(repository, never()).save(any(), any());
    }

    @Test
    void 저장_실패는_종료를_막지_않는다() {
        roomService.create(1L, 1_000_002L);
        doThrow(new RuntimeException("db down")).when(repository).save(any(), any());

        assertThatCode(lifecycle::stop).doesNotThrowAnyException();
    }

    @Test
    void 웹서버보다_먼저_멈추도록_가장_큰_phase를_가진다() {
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void 방은_없어도_초대코드_묘비가_남아_있으면_저장한다() {
        String code = roomService.create(1L, 1_000_003L).inviteCode();
        roomService.join(100L, code, "포메100", null, null);
        roomService.leave(1_000_003L, 100L); // 마지막 퇴장 → 방 소멸, 묘비 기록

        lifecycle.stop();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).save(payload.capture(), eq(NOW));
        RoomStateSnapshot saved = objectMapper.readValue(payload.getValue(), RoomStateSnapshot.class);
        assertThat(saved.rooms()).isEmpty();
        assertThat(saved.closedCodes()).containsKey(code);
        verify(repository, never()).clear();
    }
}
