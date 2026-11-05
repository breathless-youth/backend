package project.study.planner.dto;

import java.time.Instant;
import java.time.LocalDate;

/** key는 저장되지 않은 반복 회차에도 안정적이다. taskId는 세션 제출용 실체화 전에는 null이다. */
public record PlannerTask(
        String key,
        Long taskId,
        Long subjectId,
        String name,
        LocalDate date,
        boolean done,
        Instant doneAt,
        Long seriesId,
        LocalDate occurrenceDate,
        boolean dismissed) {}
