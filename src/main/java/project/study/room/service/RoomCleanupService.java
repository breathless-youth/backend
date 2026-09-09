package project.study.room.service;

import io.sentry.Sentry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.room.entity.CloseReason;
import project.study.room.entity.LeaveReason;
import project.study.room.lease.TaskLease;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.repository.TaskLeaseRepository;

/**
 * 5초마다 도는 정리 진행자 (스펙 §2.10). 트랜잭션을 열지 않고 단계·방마다 짧은 트랜잭션을 열어 커밋한다 —
 * 락을 단계 사이로 넘기지 않아 데드락 경로가 없다. 모든 문장이 조건부·멱등이라 두 태스크가 동시에 돌아도
 * 같은 행을 두 번 처리하지 못하므로 스윕을 단일화하지 않는다. 방 하나를 커밋할 때마다 즉시 알리고(onRemoved),
 * 방마다 예외를 격리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomCleanupService {

    public static final int RESERVATION_TTL_SECONDS = 30;
    public static final int GRACE_PERIOD_SECONDS = 30;
    public static final int EMPTY_ROOM_TTL_SECONDS = 600;
    public static final int RECLAIMED_LEASE_TTL_SECONDS = 600;

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;
    private final TaskLeaseRepository leases;
    private final TaskLease taskLease;
    private final ParticipantRemover remover;
    private final TransactionTemplate tx;

    public List<AutoLeave> cleanupExpired(Instant now) {
        List<AutoLeave> removed = new ArrayList<>();
        cleanupExpired(now, removed::add);
        return removed;
    }

    public void cleanupExpired(Instant now, Consumer<AutoLeave> onRemoved) {
        reclaimDeadTasks(now);
        expireParticipants(now, onRemoved);
        closeEmptyRooms(now);
    }

    /** 살아 있는 리스가 없는 태스크의 참가자를 끊김으로 전환한다 — 내 heartbeat가 연속일 때만 (스펙 §3). */
    private void reclaimDeadTasks(Instant now) {
        if (!taskLease.canReclaim()) {
            return;
        }
        Instant threshold = now.minusSeconds(TaskLease.STALE_SECONDS);
        quietly("죽은 태스크 조회", () -> reclaimEach(leases.findStaleUnreclaimed(threshold), threshold, now));
        quietly("리스 없는 참가자 회수", () -> tx.execute(status -> participations.reclaimOrphansWithoutLease(now)));
        quietly(
                "회수된 리스 정리",
                () -> tx.execute(
                        status -> leases.deleteReclaimedBefore(now.minusSeconds(RECLAIMED_LEASE_TTL_SECONDS))));
    }

    private void reclaimEach(List<String> dead, Instant threshold, Instant now) {
        for (String taskId : dead) {
            if (taskId.equals(taskLease.taskId())) {
                continue; // 나 자신은 회수하지 않는다 — 호출자가 미래 시각(now)을 넘겨도 내 리스가 낡아 보이면 안 된다
            }
            quietly(
                    "리스 회수 " + taskId,
                    () -> tx.execute(status -> {
                        if (leases.reclaim(taskId, threshold, now)) {
                            int reclaimed = participations.reclaimByTask(taskId, now);
                            log.warn("죽은 태스크 회수: taskId={}, 참가자 {}명 끊김 전환", taskId, reclaimed);
                        }
                        return null;
                    }));
        }
    }

    private void expireParticipants(Instant now, Consumer<AutoLeave> onRemoved) {
        ExpiryWindow window =
                new ExpiryWindow(now.minusSeconds(RESERVATION_TTL_SECONDS), now.minusSeconds(GRACE_PERIOD_SECONDS));
        quietly("만료 후보 조회", () -> {
            Map<Long, List<Candidate>> byRoom = participations.findExpiryCandidates(window).stream()
                    .collect(Collectors.groupingBy(Candidate::roomId));
            byRoom.forEach((roomId, candidates) -> quietly("만료 정리 room=" + roomId, () -> {
                List<AutoLeave> removed = tx.execute(status -> expireRoom(roomId, candidates, window, now));
                if (removed != null) {
                    removed.forEach(onRemoved);
                }
            }));
        });
    }

    private List<AutoLeave> expireRoom(Long roomId, List<Candidate> candidates, ExpiryWindow window, Instant now) {
        Optional<RoomRow> room = rooms.lockById(roomId).filter(RoomRow::isOpen);
        if (room.isEmpty()) {
            return List.of();
        }
        List<AutoLeave> removed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Optional<Row> row = participations.lockLive(roomId, candidate.userId());
            if (row.isEmpty()) {
                continue;
            }
            if (remover.remove(row.get(), room.get(), LeaveReason.DISCONNECT_TIMEOUT, now, window)
                    .removed()) {
                removed.add(new AutoLeave(roomId, candidate.userId()));
            }
        }
        return removed;
    }

    private void closeEmptyRooms(Instant now) {
        Instant deadline = now.minusSeconds(EMPTY_ROOM_TTL_SECONDS);
        quietly("빈 방 조회", () -> {
            for (RoomRow candidate : rooms.findEmptyOpenRoomsCreatedBefore(deadline)) {
                quietly(
                        "빈 방 종료 " + candidate.id(),
                        () -> tx.execute(status -> {
                            rooms.lockById(candidate.id())
                                    .filter(RoomRow::isOpen)
                                    .ifPresent(room -> rooms.closeIfEmpty(
                                            room.id(), room.inviteCode(), CloseReason.EMPTY_EXPIRED, now));
                            return null;
                        }));
            }
        });
    }

    // 단계의 드라이버 쿼리까지 이 안에서 실행한다 — 조회 하나가 터졌다고 뒤 단계가 통째로 건너뛰면
    // (예: 만료 후보 조회 실패 → 빈 방이 영영 안 닫힘) 스윕이 한 틱 이상 멈춘 것과 같다
    private void quietly(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("cleanup 단계 실패({}) — 다음 틱에 재시도", step, e);
            // 삼킨 예외도 Sentry에는 올린다 — 로그만 남으면 스윕이 조용히 실패한 채로 남는다
            Sentry.captureException(e);
        }
    }
}
