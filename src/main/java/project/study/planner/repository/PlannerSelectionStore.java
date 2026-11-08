package project.study.planner.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PlannerSelectionStore {
    private final JdbcClient jdbc;

    public Map<Long, Integer> selected(Long userId, LocalDate date) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT subject_id,sort_order FROM planner_day_subject WHERE user_id=:u AND plan_date=:d ORDER BY sort_order,subject_id")
                .param("u", userId)
                .param("d", date)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public void select(Long userId, LocalDate date, Long subjectId, int order) {
        jdbc.sql(
                        "INSERT INTO planner_day_subject(user_id,plan_date,subject_id,sort_order) VALUES (:u,:d,:s,:o) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("d", date)
                .param("s", subjectId)
                .param("o", order)
                .update();
    }

    public void order(Long userId, LocalDate date, Long subjectId, int order) {
        jdbc.sql(
                        "INSERT INTO planner_day_subject(user_id,plan_date,subject_id,sort_order) VALUES (:u,:d,:s,:o) ON CONFLICT(user_id,plan_date,subject_id) DO UPDATE SET sort_order=:o")
                .param("u", userId)
                .param("d", date)
                .param("s", subjectId)
                .param("o", order)
                .update();
    }

    public void remove(Long userId, LocalDate date, Long subjectId) {
        jdbc.sql("DELETE FROM planner_day_subject WHERE user_id=:u AND plan_date=:d AND subject_id=:s")
                .param("u", userId)
                .param("d", date)
                .param("s", subjectId)
                .update();
    }
}
