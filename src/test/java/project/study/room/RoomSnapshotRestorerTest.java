package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.room.service.RoomService;
import project.study.room.snapshot.RoomSnapshotRestorer;
import project.study.room.snapshot.RoomStateSnapshotRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class RoomSnapshotRestorerTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");
    private static final long ROOM_ID = 1_000_777L;

    @Mock
    private RoomStateSnapshotRepository repository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RoomService roomService;
    private RoomSnapshotRestorer restorer;

    @BeforeEach
    void setUp() {
        roomService = new RoomService("test-secret", 86400, List.of(), event -> {});
        restorer = new RoomSnapshotRestorer(roomService, repository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 옛 태스크가 남겼을 스냅샷 JSON — 실제 export를 거쳐 만든다(직렬화 왕복 검증 겸함). */
    private String snapshotJson() {
        RoomService old = new RoomService("test-secret", 86400, List.of(), event -> {});
        String code = old.create(1L, ROOM_ID).inviteCode();
        old.join(100L, code, "포메100", null, null);
        old.confirmStomp(ROOM_ID, 100L, "old-session");
        return objectMapper.writeValueAsString(old.exportSnapshot(NOW.minusSeconds(5)));
    }

    @Test
    void 신선한_스냅샷이_있으면_복원하고_지운다() {
        when(repository.loadIfSavedAfter(NOW.minusSeconds(RoomSnapshotRestorer.MAX_AGE_SECONDS)))
                .thenReturn(Optional.of(snapshotJson()));

        assertThat(restorer.restoreIfAvailable()).isTrue();

        assertThat(roomService.roomExists(ROOM_ID)).isTrue();
        assertThat(roomService.hasParticipant(ROOM_ID, 100L)).isTrue();
        verify(repository).clear();
    }

    @Test
    void 스냅샷이_없으면_복원하지_않고_다음_호출에서_다시_본다() {
        when(repository.loadIfSavedAfter(any())).thenReturn(Optional.empty());

        assertThat(restorer.restoreIfAvailable()).isFalse();
        assertThat(restorer.restoreIfAvailable()).isFalse();

        // "없음"은 기억하지 않는다 — 겹침 구간에 옛 태스크가 아직 안 썼을 수 있다
        verify(repository, org.mockito.Mockito.times(2)).loadIfSavedAfter(any());
        verify(repository, never()).clear();
    }

    @Test
    void 한_번_복원한_뒤에는_다시_읽지_않는다() {
        when(repository.loadIfSavedAfter(any())).thenReturn(Optional.of(snapshotJson()));

        assertThat(restorer.restoreIfAvailable()).isTrue();
        assertThat(restorer.restoreIfAvailable()).isFalse();

        verify(repository, org.mockito.Mockito.times(1)).loadIfSavedAfter(any());
    }

    @Test
    void 깨진_스냅샷은_폐기하고_복원_없이_진행한다() {
        when(repository.loadIfSavedAfter(any())).thenReturn(Optional.of("{not json"));

        assertThat(restorer.restoreIfAvailable()).isFalse();

        assertThat(roomService.roomExists(ROOM_ID)).isFalse();
        verify(repository).clear();
    }

    @Test
    void 저장소_조회가_실패해도_예외를_밖으로_내지_않는다() {
        when(repository.loadIfSavedAfter(any())).thenThrow(new RuntimeException("db down"));

        assertThat(restorer.restoreIfAvailable()).isFalse();
    }

    @Test
    void 복원_뒤_스냅샷_삭제가_실패해도_예외가_새지_않고_복원은_유지된다() {
        when(repository.loadIfSavedAfter(any())).thenReturn(Optional.of(snapshotJson()));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(repository)
                .clear();

        assertThat(restorer.restoreIfAvailable()).isTrue();

        assertThat(roomService.roomExists(ROOM_ID)).isTrue();
        assertThat(restorer.restoreIfAvailable()).isFalse(); // 이미 복원됨 — 다시 읽지 않는다
    }
}
