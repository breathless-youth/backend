package project.study.planner.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.repository.ManualStudySessionLockRepository;
import project.study.studysession.service.StudySessionUserDataHandler;
import project.study.user.account.UserDataHandler;

/** 원본 세션 병합(order 100) 전에 출처를 판별한다. 남는 계정의 개인 기록이 충돌에서 우선한다. */
@Component
@RequiredArgsConstructor
public class PlannerRecordUserDataHandler implements UserDataHandler {
    private final JdbcClient jdbc;
    private final PlannerRecordRepository repository;
    private final PlannerRecordOriginalRepository originals;
    private final PlannerRecordService records;
    private final ManualStudySessionLockRepository locks;
    private final StudySessionUserDataHandler sessionMerge;

    @Override
    public Set<String> columns() {
        return Set.of("planner_record_edit.user_id", "planner_record_operation.user_id");
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public void lockBeforeUsers(long userId) {
        locks.lockUser(userId);
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        List<StudySession> sourceOriginals = originals.findByUserIdOrderByStartedAtAsc(sourceUserId);
        List<PlannerRecordEdit> sourceEdits = repository.edits(sourceUserId);
        Set<String> targetKeys = originals.findByUserIdOrderByStartedAtAsc(targetUserId).stream()
                .map(PlannerRecordViews::key)
                .collect(Collectors.toSet());
        repository.edits(targetUserId).forEach(edit -> targetKeys.add(edit.key()));
        Set<String> originalKeys =
                sourceOriginals.stream().map(PlannerRecordViews::key).collect(Collectors.toSet());
        // 세션 병합은 원래 시각으로 충돌을 본다. 수정본이 그 시간을 지웠어도 원본이 버려지면 남은 조각도 근거를 잃는다.
        Set<Long> droppedSessions = sessionMerge.conflictingSessionIds(sourceUserId, targetUserId);
        Set<String> droppedKeys = sourceOriginals.stream()
                .filter(session -> droppedSessions.contains(session.getId()))
                .map(PlannerRecordViews::key)
                .collect(Collectors.toSet());
        Map<String, PlannerRecordEdit> moves = new HashMap<>();
        sourceEdits.stream()
                .filter(edit -> !targetKeys.contains(edit.key()))
                .filter(edit -> edit.source() == StudySessionSource.MANUAL || originalKeys.contains(edit.key()))
                .forEach(edit -> moves.put(edit.key(), edit));
        // 옮기기 전에 전체 충돌을 검사해야 후속 판단이 아직 옮기지 않은 원본을 찾으려 하지 않는다.
        for (PlannerRecordView view : sourceViews(sourceUserId, sourceOriginals, sourceEdits)) {
            if (targetKeys.contains(view.key())) {
                moves.remove(view.key());
            } else if (droppedKeys.contains(view.key()) || conflicts(targetUserId, view)) {
                moves.remove(view.key());
                if (originalKeys.contains(view.key())) {
                    moves.put(
                            view.key(),
                            new PlannerRecordEdit(
                                    view.key(), view.source(), view.startedAt(), view.endedAt(), null, true, false));
                }
            }
        }
        moves.values().forEach(edit -> repository.saveEdit(targetUserId, edit));
        jdbc.sql("DELETE FROM planner_record_edit WHERE user_id=:source")
                .param("source", sourceUserId)
                .update();
        mergeOperations(sourceUserId, targetUserId);
    }

    /** 분 범위로 지운 빈칸은 기록이 아니므로 실제 남은 조각끼리만 충돌을 본다. */
    private boolean conflicts(long targetUserId, PlannerRecordView view) {
        if (view.pieces().isEmpty()) {
            return !records.records(targetUserId, view.startedAt(), view.endedAt())
                    .isEmpty();
        }
        return view.pieces().stream()
                .anyMatch(piece -> !records.records(targetUserId, piece.startedAt(), piece.endedAt())
                        .isEmpty());
    }

    private List<PlannerRecordView> sourceViews(
            long userId, List<StudySession> sessions, List<PlannerRecordEdit> edits) {
        List<Instant> bounds = new ArrayList<>();
        sessions.forEach(session -> {
            bounds.add(session.getStartedAt());
            bounds.add(PlannerRecordViews.end(session));
        });
        edits.forEach(edit -> {
            bounds.add(edit.startedAt());
            bounds.add(edit.endedAt());
        });
        if (bounds.isEmpty()) {
            return List.of();
        }
        Instant from = bounds.stream().min(Comparator.naturalOrder()).orElseThrow();
        Instant to = bounds.stream().max(Comparator.naturalOrder()).orElseThrow();
        return from.isBefore(to) ? records.records(userId, from, to) : List.of();
    }

    private void mergeOperations(long sourceUserId, long targetUserId) {
        jdbc.sql("""
                        DELETE FROM planner_record_operation s WHERE s.user_id=:source AND EXISTS (
                            SELECT 1 FROM planner_record_operation t WHERE t.user_id=:target AND t.operation_id=s.operation_id)
                        """)
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("UPDATE planner_record_operation SET user_id=:target WHERE user_id=:source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM planner_record_edit WHERE user_id=:u")
                .param("u", userId)
                .update();
        jdbc.sql("DELETE FROM planner_record_operation WHERE user_id=:u")
                .param("u", userId)
                .update();
    }
}
