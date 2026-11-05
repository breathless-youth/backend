package project.study.planner.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.planner.record.PlannerRecordService;
import project.study.planner.repository.PlannerSelectionStore;
import project.study.planner.repository.PlannerStore;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
public class PlannerSelectionService {
    private final PlannerSelectionStore selections;
    private final StudySubjectRepository subjects;
    private final StudySubjectLockRepository locks;
    private final PlannerTaskResolver resolver;
    private final PlannerStore store;
    private final PlannerRecordService records;
    private final UserTimeZones timeZones;
    private final Clock clock;

    @Transactional
    public void select(Long userId, LocalDate date, Long subjectId, String timeZone) {
        editable(userId, date, timeZone);
        owned(userId, subjectId);
        int next = selections.selected(userId, date).values().stream()
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(-1)
                + 1;
        selections.select(userId, date, subjectId, next);
    }

    @Transactional
    public void order(Long userId, LocalDate date, List<Long> ids, String timeZone) {
        editable(userId, date, timeZone);
        if (ids.size() != new HashSet<>(ids).size()) throw new BadRequestException("과목 ID에 중복이 있습니다");
        ids.forEach(id -> owned(userId, id));
        List<Long> ordered = new ArrayList<>(ids);
        selections.selected(userId, date).keySet().stream()
                .filter(id -> !ids.contains(id))
                .forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) selections.order(userId, date, ordered.get(i), i);
    }

    @Transactional
    public void remove(Long userId, LocalDate date, Long subjectId, String timeZone) {
        ZoneId zone = editable(userId, date, timeZone);
        owned(userId, subjectId);
        boolean recorded = records
                .records(userId, PlannerCalendar.start(date, zone), PlannerCalendar.start(date.plusDays(1), zone))
                .stream()
                .flatMap(r -> r.subjectSegments().stream())
                .anyMatch(s -> s.subjectId().equals(subjectId));
        if (recorded) throw new ConflictException("공부 기록이 있는 과목은 이 날에서 뺄 수 없습니다");
        for (var task : resolver.tasks(userId, date, date)) {
            if (task.subjectId().equals(subjectId))
                store.remove(task.taskId() == null ? store.materialize(task) : task.taskId());
        }
        selections.remove(userId, date, subjectId);
    }

    private ZoneId editable(Long userId, LocalDate date, String timeZone) {
        locks.lockUser(userId);
        ZoneId zone = timeZones.resolve(userId, timeZone);
        PlannerCalendar.editable(date, PlannerCalendar.today(clock, zone));
        return zone;
    }

    private void owned(Long userId, Long id) {
        subjects.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다"));
    }
}
