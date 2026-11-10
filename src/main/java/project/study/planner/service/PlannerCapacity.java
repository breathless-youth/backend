package project.study.planner.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.planner.repository.PlannerRule;
import project.study.planner.repository.PlannerStore;
import project.study.planner.repository.PlannerTaskRow;

/** 반복 상태가 바뀌는 경계마다 7요일, 그리고 예외가 있는 날짜를 검사한다. 끝없는 미래를 펼치지 않는다. */
@Component
@RequiredArgsConstructor
public class PlannerCapacity {
    private final PlannerStore store;

    public void validate(Long userId, Long subjectId) {
        List<PlannerRule> rules = store.rules(userId).stream()
                .filter(r -> r.subjectId().equals(subjectId))
                .toList();
        List<PlannerTaskRow> rows =
                store.rows(userId, PlannerTaskResolver.FIRST_DATE, PlannerTaskResolver.LAST_DATE).stream()
                        .filter(r -> r.subjectId().equals(subjectId))
                        .toList();
        Set<LocalDate> dates = new HashSet<>();
        for (PlannerRule rule : rules) {
            addWeek(dates, rule.startDate());
            if (rule.endDate() != null && rule.endDate().isBefore(PlannerTaskResolver.LAST_DATE))
                addWeek(dates, rule.endDate().plusDays(1));
        }
        for (PlannerTaskRow row : rows) {
            addExceptionBoundary(dates, row.date());
            if (row.occurrenceDate() != null) addExceptionBoundary(dates, row.occurrenceDate());
        }
        for (LocalDate date : dates) {
            if (PlannerTaskResolver.on(rows, rules, date).size() > 30) {
                throw new ConflictException(
                        ErrorCode.PLANNER_TASK_LIMIT, "과목 하나에 할 일은 날짜마다 30개까지 넣을 수 있습니다 (" + date + ")");
            }
        }
    }

    private static void addExceptionBoundary(Set<LocalDate> dates, LocalDate date) {
        addWeek(dates, date);
        if (date.isBefore(PlannerTaskResolver.LAST_DATE)) addWeek(dates, date.plusDays(1));
    }

    private static void addWeek(Set<LocalDate> dates, LocalDate start) {
        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            if (!date.isAfter(PlannerTaskResolver.LAST_DATE)) dates.add(date);
        }
    }
}
