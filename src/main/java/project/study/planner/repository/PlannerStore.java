package project.study.planner.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.planner.dto.PlannerTask;

@Repository
@RequiredArgsConstructor
public class PlannerStore {
    private final JdbcClient jdbc;

    public List<PlannerTaskRow> rows(Long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT t.* FROM study_task t JOIN study_subject s ON s.id=t.subject_id
                WHERE s.user_id=:u AND (t.planned_date BETWEEN :f AND :t OR t.occurrence_date BETWEEN :f AND :t)
                ORDER BY t.id""")
                .param("u", userId)
                .param("f", from)
                .param("t", to)
                .query(PlannerStore::task)
                .list();
    }

    public List<PlannerRule> rules(Long userId) {
        return jdbc.sql("""
                SELECT r.*, s.subject_id FROM planner_repeat_rule r JOIN planner_series s ON s.id=r.series_id
                JOIN study_subject sub ON sub.id=s.subject_id
                WHERE s.user_id=:u AND sub.deleted_at IS NULL ORDER BY r.start_date, r.id""")
                .param("u", userId)
                .query((rs, n) -> new PlannerRule(
                        rs.getLong("id"),
                        rs.getLong("series_id"),
                        rs.getLong("subject_id"),
                        rs.getString("name"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("weekdays")))
                .list();
    }

    public Optional<PlannerTaskRow> row(Long userId, long id) {
        return jdbc.sql(
                        "SELECT t.* FROM study_task t JOIN study_subject s ON s.id=t.subject_id WHERE t.id=:id AND s.user_id=:u")
                .param("u", userId)
                .param("id", id)
                .query(PlannerStore::task)
                .optional();
    }

    public Optional<PlannerTaskRow> occurrence(Long userId, long series, LocalDate date) {
        return jdbc.sql(
                        "SELECT t.* FROM study_task t JOIN study_subject s ON s.id=t.subject_id WHERE t.series_id=:s AND t.occurrence_date=:d AND s.user_id=:u")
                .param("s", series)
                .param("d", date)
                .param("u", userId)
                .query(PlannerStore::task)
                .optional();
    }

    public long insert(Long subjectId, LocalDate date, String name) {
        return jdbc.sql("INSERT INTO study_task(subject_id,name,planned_date) VALUES (:s,:n,:d) RETURNING id")
                .param("s", subjectId)
                .param("n", name)
                .param("d", date)
                .query(Long.class)
                .single();
    }

    public long materialize(PlannerTask task) {
        return jdbc.sql("""
                INSERT INTO study_task(subject_id,name,planned_date,series_id,occurrence_date)
                VALUES (:s,:n,:d,:r,:o) RETURNING id""")
                .param("s", task.subjectId())
                .param("n", task.name())
                .param("d", task.date())
                .param("r", task.seriesId())
                .param("o", task.occurrenceDate())
                .query(Long.class)
                .single();
    }

    public long createSeries(Long userId, Long subjectId) {
        return jdbc.sql("INSERT INTO planner_series(user_id,subject_id) VALUES (:u,:s) RETURNING id")
                .param("u", userId)
                .param("s", subjectId)
                .query(Long.class)
                .single();
    }

    public void addRule(long series, String name, LocalDate from, LocalDate to, int mask) {
        jdbc.sql("INSERT INTO planner_repeat_rule(series_id,name,start_date,end_date,weekdays) VALUES (:s,:n,:f,:t,:w)")
                .param("s", series)
                .param("n", name)
                .param("f", from)
                .param("t", to)
                .param("w", mask)
                .update();
    }

    public void attach(long id, long series, LocalDate origin, boolean customized) {
        jdbc.sql("UPDATE study_task SET series_id=:s,occurrence_date=:o,customized=:c WHERE id=:id")
                .param("s", series)
                .param("o", origin)
                .param("c", customized)
                .param("id", id)
                .update();
    }

    public void update(long id, String name, Instant doneAt, boolean customized) {
        jdbc.sql("UPDATE study_task SET name=:n,done_at=:a,customized=:c,updated_at=now() WHERE id=:id")
                .param("n", name)
                .param("a", doneAt == null ? null : doneAt.atOffset(java.time.ZoneOffset.UTC))
                .param("c", customized)
                .param("id", id)
                .update();
    }

    public void remove(long id) {
        jdbc.sql("UPDATE study_task SET deleted_at=now(),updated_at=now() WHERE id=:id")
                .param("id", id)
                .update();
    }

    public void move(long id, LocalDate date) {
        jdbc.sql("UPDATE study_task SET planned_date=:d,customized=true,updated_at=now() WHERE id=:id")
                .param("d", date)
                .param("id", id)
                .update();
    }

    public void dismiss(long id) {
        jdbc.sql("UPDATE study_task SET carryover_dismissed=true WHERE id=:id")
                .param("id", id)
                .update();
    }

    public void endRules(long series, LocalDate from) {
        jdbc.sql("DELETE FROM planner_repeat_rule WHERE series_id=:s AND start_date>=:d")
                .param("s", series)
                .param("d", from)
                .update();
        jdbc.sql(
                        "UPDATE planner_repeat_rule SET end_date=:end WHERE series_id=:s AND start_date<:d AND (end_date IS NULL OR end_date>=:d)")
                .param("s", series)
                .param("d", from)
                .param("end", from.minusDays(1))
                .update();
    }

    public void detach(long id) {
        jdbc.sql("UPDATE study_task SET series_id=NULL,occurrence_date=NULL,customized=false WHERE id=:id")
                .param("id", id)
                .update();
    }

    public void clearSubjectSelections(Long userId, Long subjectId) {
        jdbc.sql("DELETE FROM planner_day_subject WHERE user_id=:u AND subject_id=:s")
                .param("u", userId)
                .param("s", subjectId)
                .update();
    }

    private static PlannerTaskRow task(ResultSet rs, int n) throws SQLException {
        return new PlannerTaskRow(
                rs.getLong("id"),
                rs.getLong("subject_id"),
                rs.getString("name"),
                rs.getObject("planned_date", LocalDate.class),
                instant(rs, "done_at"),
                instant(rs, "deleted_at"),
                rs.getObject("series_id", Long.class),
                rs.getObject("occurrence_date", LocalDate.class),
                rs.getBoolean("customized"),
                rs.getBoolean("carryover_dismissed"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime value = rs.getObject(col, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
