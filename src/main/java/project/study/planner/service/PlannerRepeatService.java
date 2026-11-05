package project.study.planner.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.planner.dto.PlannerRepeatResponse;
import project.study.planner.dto.PlannerTask;
import project.study.planner.dto.PlannerTaskRequests.Repeat;
import project.study.planner.repository.PlannerRule;
import project.study.planner.repository.PlannerStore;
import project.study.planner.repository.PlannerTaskRow;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
public class PlannerRepeatService {
    private final PlannerStore store;
    private final PlannerTaskResolver resolver;
    private final PlannerCapacity capacity;
    private final StudySubjectLockRepository locks;
    private final UserTimeZones timeZones;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PlannerRepeatResponse get(Long userId, String key) {
        PlannerTask task = resolver.get(userId, key);
        PlannerRule rule = task.seriesId() == null ? null : ruleAtOrBefore(userId, task);
        if (rule == null) {
            return new PlannerRepeatResponse(task.seriesId(), null, List.of(), null);
        }
        List<DayOfWeek> weekdays = Arrays.stream(DayOfWeek.values())
                .filter(day -> (rule.weekdays() & (1 << (day.getValue() - 1))) != 0)
                .toList();
        return new PlannerRepeatResponse(task.seriesId(), rule.startDate(), weekdays, rule.endDate());
    }

    @Transactional
    public PlannerTask change(Long userId, String key, Repeat request, String timeZone) {
        locks.lockUser(userId);
        PlannerTask task = resolver.get(userId, key);
        PlannerCalendar.editable(task.date(), PlannerCalendar.today(clock, timeZones.resolve(userId, timeZone)));
        return change(userId, task, request);
    }

    public PlannerTask change(Long userId, PlannerTask task, Repeat request) {
        validate(request, task.date());
        int mask = mask(request);
        if (mask == 0) return stop(userId, task);
        long id = task.taskId() == null ? store.materialize(task) : task.taskId();
        long series = task.seriesId() == null ? store.createSeries(userId, task.subjectId()) : task.seriesId();
        boolean custom = store.row(userId, id).orElseThrow().customized()
                || (mask & (1 << (task.date().getDayOfWeek().getValue() - 1))) == 0;
        store.attach(id, series, task.occurrenceDate() == null ? task.date() : task.occurrenceDate(), custom);
        store.endRules(series, task.date());
        store.addRule(series, task.name(), task.date(), request.endDate(), mask);
        capacity.validate(userId, task.subjectId());
        return resolver.get(
                userId, "r:" + series + ":" + (task.occurrenceDate() == null ? task.date() : task.occurrenceDate()));
    }

    public void renameForward(Long userId, PlannerTask task, String name) {
        PlannerRule current = ruleAtOrBefore(userId, task);
        store.endRules(task.seriesId(), task.date());
        if (current != null) store.addRule(task.seriesId(), name, task.date(), current.endDate(), current.weekdays());
        if (task.taskId() != null) {
            PlannerTaskRow row = store.row(userId, task.taskId()).orElseThrow();
            store.update(row.id(), name, row.doneAt(), row.customized());
        }
    }

    public void deleteForward(Long userId, PlannerTask task) {
        store.endRules(task.seriesId(), task.date());
        for (PlannerTaskRow row : store.rows(userId, PlannerTaskResolver.FIRST_DATE, PlannerTaskResolver.LAST_DATE)) {
            if (task.seriesId().equals(row.seriesId()) && !row.date().isBefore(task.date())) store.remove(row.id());
        }
    }

    private PlannerTask stop(Long userId, PlannerTask task) {
        if (task.seriesId() == null) return task;
        long id = task.taskId() == null ? store.materialize(task) : task.taskId();
        store.endRules(task.seriesId(), task.date());
        store.detach(id);
        // 이동한 회차를 일반화해도 원래 날짜의 가상 회차가 되살아나면 안 된다.
        if (!task.date().equals(task.occurrenceDate())) store.remove(store.materialize(task));
        return resolver.get(userId, "t:" + id);
    }

    private PlannerRule ruleAtOrBefore(Long userId, PlannerTask task) {
        List<PlannerRule> rules = store.rules(userId);
        return rules.stream()
                .filter(r -> r.seriesId().equals(task.seriesId())
                        && !r.startDate().isAfter(task.date())
                        && (r.endDate() == null || !r.endDate().isBefore(task.date())))
                .findFirst()
                .orElse(null);
    }

    private static int mask(Repeat request) {
        return request.weekdays().stream()
                .mapToInt(d -> 1 << (d.getValue() - 1))
                .reduce(0, (a, b) -> a | b);
    }

    private static void validate(Repeat request, LocalDate start) {
        if (request.weekdays() == null || request.weekdays().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BadRequestException("반복 요일이 필요합니다");
        }
        if (request.weekdays().size() != new java.util.HashSet<>(request.weekdays()).size())
            throw new BadRequestException("반복 요일에 중복이 있습니다");
        if (request.endDate() != null) {
            PlannerCalendar.checkDate(request.endDate());
            if (request.endDate().isBefore(start)) throw new BadRequestException("반복 종료일은 시작일 이전일 수 없습니다");
        }
    }
}
