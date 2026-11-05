package project.study.planner.repository;

import java.time.LocalDate;

public record PlannerRule(
        Long id, Long seriesId, Long subjectId, String name, LocalDate startDate, LocalDate endDate, int weekdays) {
    public boolean includes(LocalDate date) {
        return !date.isBefore(startDate)
                && (endDate == null || !date.isAfter(endDate))
                && (weekdays & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
    }
}
