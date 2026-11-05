package project.study.planner.repository;

import java.time.Instant;
import java.time.LocalDate;
import project.study.planner.dto.PlannerTask;

public record PlannerTaskRow(
        Long id,
        Long subjectId,
        String name,
        LocalDate date,
        Instant doneAt,
        Instant deletedAt,
        Long seriesId,
        LocalDate occurrenceDate,
        boolean customized,
        boolean dismissed) {
    public String key() {
        return seriesId == null ? "t:" + id : "r:" + seriesId + ":" + occurrenceDate;
    }

    public PlannerTask view(String resolvedName) {
        return new PlannerTask(
                key(), id, subjectId, resolvedName, date, doneAt != null, doneAt, seriesId, occurrenceDate, dismissed);
    }
}
