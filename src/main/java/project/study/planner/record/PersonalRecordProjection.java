package project.study.planner.record;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.studysession.entity.StudySession;

@Component
@RequiredArgsConstructor
class PersonalRecordProjection {
    private final PlannerRecordOriginalRepository originals;
    private final PlannerRecordRepository edits;

    List<PersonalRecordPiece> read(Long userId, LocalDate from, LocalDate to, ZoneId zone) {
        return project(userId, originals.inStatDates(userId, from, to), zone).stream()
                .filter(piece -> !piece.date().isBefore(from) && !piece.date().isAfter(to))
                .toList();
    }

    List<PersonalRecordPiece> all(Long userId, ZoneId zone) {
        return project(userId, originals.findByUserIdOrderByStartedAtAsc(userId), zone);
    }

    private List<PersonalRecordPiece> project(Long userId, List<StudySession> selected, ZoneId zone) {
        List<PlannerRecordEdit> overrides = edits.edits(userId);
        Map<Long, StudySession> sessions = new LinkedHashMap<>();
        selected.forEach(session -> sessions.put(session.getId(), session));
        Set<String> present = selected.stream().map(PlannerRecordViews::key).collect(Collectors.toSet());
        loadOriginals(
                        userId,
                        overrides.stream()
                                .filter(edit -> !present.contains(edit.key()))
                                .toList())
                .forEach(session -> sessions.put(session.getId(), session));
        Map<String, List<StudySession>> bundles = new LinkedHashMap<>();
        for (StudySession session : sessions.values().stream()
                .sorted(java.util.Comparator.comparing(StudySession::getStartedAt))
                .toList()) {
            bundles.computeIfAbsent(PlannerRecordViews.key(session), key -> new ArrayList<>())
                    .add(session);
        }
        Map<String, PlannerRecordEdit> byKey = new HashMap<>();
        overrides.forEach(edit -> byKey.put(edit.key(), edit));
        List<PersonalRecordPiece> result = new ArrayList<>();
        bundles.forEach((key, bundle) -> {
            if (!byKey.containsKey(key)) result.addAll(PersonalRecordPieces.original(bundle, null));
        });
        overrides.stream().filter(edit -> !edit.deleted()).forEach(edit -> {
            List<StudySession> bundle = bundles.getOrDefault(edit.key(), List.of());
            if (edit.source() == project.study.studysession.entity.StudySessionSource.MEASURED && bundle.isEmpty())
                return;
            result.addAll(
                    edit.preserveOriginalTotals()
                            ? PersonalRecordPieces.original(bundle, edit)
                            : PersonalRecordPieces.changed(edit, bundle, zone));
        });
        return result.stream()
                .filter(piece -> piece.view().focusSec() >= MIN_LIST_FOCUS_SEC)
                .toList();
    }

    private List<StudySession> loadOriginals(Long userId, List<PlannerRecordEdit> overrides) {
        if (overrides.isEmpty()) return List.of();
        List<Instant> roots = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (PlannerRecordEdit edit : overrides) {
            String root = edit.key().substring(2);
            if (root.startsWith("id:")) ids.add(Long.valueOf(root.substring(3)));
            else roots.add(Instant.parse(root));
        }
        return originals.forRecordKeys(userId, roots, ids);
    }
}
