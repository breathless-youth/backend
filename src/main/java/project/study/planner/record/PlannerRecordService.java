package project.study.planner.record;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.repository.StudySessionRepository;

@Service
@RequiredArgsConstructor
public class PlannerRecordService {
    private final PlannerRecordOriginalRepository originals;
    private final StudySessionRepository sessions;
    private final PlannerRecordRepository repository;

    /** [from,to)로 절삭한 개인 기록. 원본 자정 통계·랭킹에는 이 수정본을 적용하지 않는다. */
    @Transactional(readOnly = true)
    public List<PlannerRecordView> records(Long userId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BadRequestException("조회 종료는 시작 이후여야 합니다");
        }
        Map<String, List<StudySession>> bundles = originals.inWindow(userId, from, to).stream()
                .collect(Collectors.groupingBy(PlannerRecordViews::key, LinkedHashMap::new, Collectors.toList()));
        Map<String, PlannerRecordEdit> edits =
                repository.edits(userId).stream().collect(Collectors.toMap(PlannerRecordEdit::key, e -> e));
        List<PlannerRecordView> views = new ArrayList<>();
        bundles.forEach((key, bundle) -> {
            if (!edits.containsKey(key)) {
                views.add(PlannerRecordViews.original(bundle, from, to));
            }
        });
        for (PlannerRecordEdit edit : edits.values()) {
            if (!edit.deleted() && edit.overlaps(from, to)) {
                editedView(userId, edit, bundles, from, to).ifPresent(views::add);
            }
        }
        return views.stream()
                .sorted(Comparator.comparing(PlannerRecordView::startedAt).thenComparing(PlannerRecordView::key))
                .toList();
    }

    private Optional<PlannerRecordView> editedView(
            Long userId, PlannerRecordEdit edit, Map<String, List<StudySession>> bundles, Instant from, Instant to) {
        try {
            List<StudySession> bundle = edit.source() == StudySessionSource.MANUAL && !edit.preserveOriginalTotals()
                    ? List.of()
                    : bundles.containsKey(edit.key()) ? bundles.get(edit.key()) : bundle(userId, edit.key());
            if (edit.source() == StudySessionSource.MANUAL
                    && edit.preserveOriginalTotals()
                    && bundle.stream()
                            .noneMatch(piece -> piece.getStartedAt().isBefore(to)
                                    && PlannerRecordViews.end(piece).isAfter(from))) {
                return Optional.empty();
            }
            return Optional.of(PlannerRecordViews.edited(edit, bundle, from, to));
        } catch (NotFoundException orphan) {
            // 계정 병합에서 원본 충돌로 버린 측정 수정본도 함께 숨긴다.
            return Optional.empty();
        }
    }

    List<StudySession> bundle(Long userId, String key) {
        if (key == null || !(key.startsWith("c:") || key.startsWith("m:"))) {
            throw new BadRequestException("기록 키가 올바르지 않습니다");
        }
        List<StudySession> found;
        try {
            if (key.substring(2).startsWith("id:")) {
                found = sessions.findByIdAndUserId(Long.valueOf(key.substring(5)), userId)
                        .map(List::of)
                        .orElse(List.of());
            } else {
                StudySessionSource source =
                        key.startsWith("m:") ? StudySessionSource.MANUAL : StudySessionSource.MEASURED;
                found = sessions.findByUserIdAndSubmissionStartedAtAndSourceOrderByStartedAtAsc(
                        userId, Instant.parse(key.substring(2)), source);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BadRequestException("기록 키가 올바르지 않습니다");
        }
        if (found.isEmpty() || !PlannerRecordViews.key(found.getFirst()).equals(key)) {
            throw new NotFoundException("기록을 찾을 수 없습니다");
        }
        return found;
    }

    PlannerRecordEdit current(Long userId, String key) {
        PlannerRecordEdit edit = repository.edits(userId).stream()
                .filter(e -> e.key().equals(key))
                .findFirst()
                .orElse(null);
        if (edit != null) {
            if (edit.deleted()) {
                throw new NotFoundException("기록을 찾을 수 없습니다");
            }
            return edit;
        }
        List<StudySession> bundle = bundle(userId, key);
        Instant end = bundle.stream()
                .map(PlannerRecordViews::end)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Long subject = bundle.stream()
                .flatMap(s -> s.getSubjectSegments().stream())
                .map(s -> s.getSubjectId())
                .findFirst()
                .orElse(null);
        return new PlannerRecordEdit(
                key, bundle.getFirst().getSource(), bundle.getFirst().getStartedAt(), end, subject, false, true);
    }
}
