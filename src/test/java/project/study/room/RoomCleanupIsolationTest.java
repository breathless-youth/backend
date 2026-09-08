package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.room.lease.TaskLease;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.ParticipantRemover;
import project.study.room.service.ParticipantRemover.Removed;
import project.study.room.service.RoomCleanupService;

class RoomCleanupIsolationTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final RoomParticipationRepository participations = mock(RoomParticipationRepository.class);
    private final TaskLeaseRepository leases = mock(TaskLeaseRepository.class);
    private final TaskLease taskLease = mock(TaskLease.class);
    private final ParticipantRemover remover = mock(ParticipantRemover.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private RoomCleanupService service() {
        // TransactionTemplate 흉내 — 콜백을 즉시 실행한다 (커밋 경계 순서만 검증)
        when(tx.execute(any()))
                .thenAnswer(inv ->
                        ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return new RoomCleanupService(rooms, participations, leases, taskLease, remover, tx);
    }

    private static RoomRow open(long id) {
        return new RoomRow(id, "0000", 1L, NOW, null, null);
    }

    private static Row row(long id, long roomId, long userId) {
        return new Row(
                id, roomId, userId, null, null, null, false, "FOCUS", 0, NOW, false, null, null, null, null, null, null,
                null);
    }

    @Test
    void 한_방의_예외가_다른_방의_정리와_브로드캐스트를_막지_않는다() {
        when(taskLease.canReclaim()).thenReturn(false);
        when(participations.findExpiryCandidates(any()))
                .thenReturn(List.of(new Candidate(1L, 10L, 100L), new Candidate(2L, 20L, 200L)));
        when(rooms.lockById(10L)).thenReturn(Optional.of(open(10L)));
        when(rooms.lockById(20L)).thenThrow(new RuntimeException("db hiccup"));
        when(participations.lockLive(10L, 100L)).thenReturn(Optional.of(row(1L, 10L, 100L)));
        when(remover.remove(any(), any(), any(), any(), any())).thenReturn(new Removed(true, true));
        when(rooms.findEmptyOpenRoomsCreatedBefore(any())).thenReturn(List.of());

        List<AutoLeave> broadcast = new ArrayList<>();
        service().cleanupExpired(NOW, broadcast::add);

        assertThat(broadcast).containsExactly(new AutoLeave(10L, 100L));
    }

    @Test
    void 관찰_기간이_안_찼으면_리스_회수를_건너뛴다() {
        when(taskLease.canReclaim()).thenReturn(false);
        when(participations.findExpiryCandidates(any())).thenReturn(List.of());
        when(rooms.findEmptyOpenRoomsCreatedBefore(any())).thenReturn(List.of());

        service().cleanupExpired(NOW);

        org.mockito.Mockito.verify(leases, org.mockito.Mockito.never()).findStaleUnreclaimed(any());
        org.mockito.Mockito.verify(participations, org.mockito.Mockito.never()).reclaimOrphansWithoutLease(any());
    }
}
