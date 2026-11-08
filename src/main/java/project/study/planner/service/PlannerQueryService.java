package project.study.planner.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.planner.dto.PlannerDayResponse;
import project.study.planner.dto.PlannerDaySummary;
import project.study.planner.dto.PlannerTask;
import project.study.planner.goal.service.PlannerGoalService;
import project.study.planner.record.PlannerRecordService;
import project.study.planner.record.PlannerRecordView;
import project.study.planner.repository.PlannerSelectionStore;
import project.study.subject.entity.StudySubject;
import project.study.subject.repository.StudySubjectRepository;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
public class PlannerQueryService {
    private final PlannerTaskResolver tasks;
    private final PlannerSelectionStore selections;
    private final StudySubjectRepository subjects;
    private final PlannerRecordService records;
    private final PlannerGoalService goals;
    private final UserTimeZones timeZones;

    @Transactional(readOnly = true)
    public PlannerDayResponse day(Long userId, LocalDate date, String timeZone) {
        PlannerCalendar.range(date, date);
        ZoneId zone = timeZones.parse(timeZone).orElseGet(() -> timeZones.zoneOf(userId));
        List<PlannerTask> plan = tasks.tasks(userId, date, date);
        Map<Long, Integer> selected = selections.selected(userId, date);
        List<PlannerRecordView> actual = records.records(
                userId, PlannerCalendar.start(date, zone), PlannerCalendar.start(date.plusDays(1), zone));
        Set<Long> ids = new HashSet<>(selected.keySet());
        plan.forEach(t -> ids.add(t.subjectId()));
        actual.forEach(r -> r.subjectSegments().forEach(s -> ids.add(s.subjectId())));
        List<StudySubject> shown = ids.isEmpty() ? List.of() : subjects.findByIdInAndUserId(ids, userId);
        List<PlannerDayResponse.Subject> response = shown.stream()
                .sorted(Comparator.comparingInt((StudySubject s) -> selected.getOrDefault(s.getId(), Integer.MAX_VALUE))
                        .thenComparingInt(StudySubject::getSortOrder)
                        .thenComparing(StudySubject::getId))
                .map(s -> subject(s, plan, selected, actual))
                .toList();
        return new PlannerDayResponse(
                date,
                zone.getId(),
                actual.stream().mapToLong(PlannerRecordView::studySec).sum(),
                actual.stream().mapToLong(PlannerRecordView::focusSec).sum(),
                goals.get(userId, date),
                response,
                actual);
    }

    @Transactional(readOnly = true)
    public List<PlannerDayResponse> range(Long userId, LocalDate from, LocalDate to, String timeZone) {
        PlannerCalendar.range(from, to);
        List<PlannerDayResponse> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) result.add(day(userId, date, timeZone));
        return result;
    }

    @Transactional(readOnly = true)
    public List<PlannerDaySummary> summary(Long userId, LocalDate from, LocalDate to, String timeZone) {
        return range(userId, from, to, timeZone).stream()
                .map(d -> {
                    List<PlannerTask> plan = d.subjects().stream()
                            .flatMap(s -> s.tasks().stream())
                            .toList();
                    return new PlannerDaySummary(
                            d.date(),
                            plan.size(),
                            plan.stream().filter(PlannerTask::done).count(),
                            d.studySec(),
                            d.focusSec(),
                            d.focusSec() > 0,
                            !plan.isEmpty(),
                            d.goal().goalSec());
                })
                .toList();
    }

    private static PlannerDayResponse.Subject subject(
            StudySubject s, List<PlannerTask> plan, Map<Long, Integer> selected, List<PlannerRecordView> actual) {
        long study = 0;
        long focus = 0;
        for (var record : actual)
            for (var segment : record.subjectSegments()) {
                if (segment.subjectId().equals(s.getId())) {
                    study += segment.studySec();
                    focus += segment.focusSec();
                }
            }
        return new PlannerDayResponse.Subject(
                s.getId(),
                s.getName(),
                s.getColorIndex(),
                s.getDeletedAt() != null,
                selected.containsKey(s.getId()),
                study,
                focus,
                plan.stream().filter(t -> t.subjectId().equals(s.getId())).toList());
    }
}
