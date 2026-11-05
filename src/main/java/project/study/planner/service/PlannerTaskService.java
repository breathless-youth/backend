package project.study.planner.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.planner.dto.PlannerTask;
import project.study.planner.dto.PlannerTaskRequests.Create;
import project.study.planner.dto.PlannerTaskRequests.Scope;
import project.study.planner.dto.PlannerTaskRequests.Update;
import project.study.planner.repository.PlannerStore;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
public class PlannerTaskService {
    private final PlannerStore store;
    private final PlannerTaskResolver resolver;
    private final PlannerCapacity capacity;
    private final PlannerRepeatService repeats;
    private final StudySubjectRepository subjects;
    private final StudySubjectLockRepository locks;
    private final UserTimeZones timeZones;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PlannerTask> tasks(Long userId, LocalDate from, LocalDate to) {
        return resolver.tasks(userId, from, to);
    }

    @Transactional
    public PlannerTask create(Long userId, Create request, String timeZone) {
        locks.lockUser(userId);
        ZoneId zone = timeZones.resolve(userId, timeZone);
        PlannerCalendar.editable(request.date(), PlannerCalendar.today(clock, zone));
        ownedSubject(userId, request.subjectId());
        String name = name(request.name());
        long id = store.insert(request.subjectId(), request.date(), name);
        PlannerTask task = resolver.get(userId, "t:" + id);
        if (request.repeat() != null && !request.repeat().weekdays().isEmpty())
            task = repeats.change(userId, task, request.repeat());
        capacity.validate(userId, request.subjectId());
        return task;
    }

    @Transactional
    public PlannerTask update(Long userId, String key, Update request, String timeZone) {
        locks.lockUser(userId);
        LocalDate today = PlannerCalendar.today(clock, timeZones.resolve(userId, timeZone));
        PlannerTask task = resolver.get(userId, key);
        validateUpdate(task, request, today);
        if (request.name() != null && request.effectiveScope() == Scope.FUTURE && task.seriesId() != null) {
            repeats.renameForward(userId, task, name(request.name()));
            task = resolver.get(userId, key);
        }
        long id = materialize(task);
        boolean custom = request.name() != null && request.effectiveScope() == Scope.THIS && task.seriesId() != null;
        var old = store.row(userId, id).orElseThrow();
        store.update(
                id,
                request.name() == null ? task.name() : name(request.name()),
                doneAt(task, request),
                custom || old.customized());
        return resolver.get(userId, task.key());
    }

    private static void validateUpdate(PlannerTask task, Update request, LocalDate today) {
        if (request.name() == null && request.done() == null) throw new BadRequestException("바꿀 값이 없습니다");
        if (request.name() != null) PlannerCalendar.editable(task.date(), today);
        if (request.done() != null && task.date().isAfter(today)) throw new BadRequestException("아직 할 일을 완료할 수 없습니다");
        if (request.done() != null && request.effectiveScope() == Scope.FUTURE)
            throw new BadRequestException("완료는 이 날만 적용합니다");
    }

    private java.time.Instant doneAt(PlannerTask task, Update request) {
        if (request.done() == null) return task.doneAt();
        if (!request.done()) return null;
        return task.doneAt() == null ? clock.instant() : task.doneAt();
    }

    @Transactional
    public void delete(Long userId, String key, Scope scope, String timeZone) {
        locks.lockUser(userId);
        PlannerTask task = resolver.get(userId, key);
        PlannerCalendar.editable(task.date(), PlannerCalendar.today(clock, timeZones.resolve(userId, timeZone)));
        if (scope == Scope.FUTURE && task.seriesId() != null) repeats.deleteForward(userId, task);
        else store.remove(materialize(task));
    }

    @Transactional
    public PlannerTask move(Long userId, String key, LocalDate date, String timeZone) {
        locks.lockUser(userId);
        PlannerCalendar.editable(date, PlannerCalendar.today(clock, timeZones.resolve(userId, timeZone)));
        PlannerTask task = resolver.get(userId, key);
        if (task.date().equals(date)) return task;
        assertNoDuplicate(userId, task, date);
        store.move(materialize(task), date);
        capacity.validate(userId, task.subjectId());
        return resolver.get(userId, task.key());
    }

    @Transactional
    public void deleteSubjectPlan(Long userId, Long subjectId) {
        locks.lockUser(userId);
        LocalDate today = PlannerCalendar.today(clock, timeZones.zoneOf(userId));
        store.rules(userId).stream()
                .filter(r -> r.subjectId().equals(subjectId))
                .map(project.study.planner.repository.PlannerRule::seriesId)
                .distinct()
                .forEach(series -> store.endRules(series, today));
        store.clearSubjectSelections(userId, subjectId);
    }

    @Transactional
    public PlannerTask materialize(Long userId, String key) {
        locks.lockUser(userId);
        PlannerTask task = resolver.get(userId, key);
        return resolver.get(userId, "t:" + materialize(task));
    }

    long materialize(PlannerTask task) {
        return task.taskId() == null ? store.materialize(task) : task.taskId();
    }

    void assertNoDuplicate(Long userId, PlannerTask task, LocalDate date) {
        if (task.seriesId() != null
                && resolver.tasks(userId, date, date).stream()
                        .anyMatch(t ->
                                task.seriesId().equals(t.seriesId()) && !t.key().equals(task.key()))) {
            throw new ConflictException("같은 반복 할 일이 이미 있는 날짜입니다");
        }
    }

    void ownedSubject(Long userId, Long id) {
        subjects.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다"));
    }

    static String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 25)
            throw new BadRequestException("할 일 이름은 1~25자입니다");
        return value.strip();
    }
}
