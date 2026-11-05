package project.study.planner.goal.repository;

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PlannerGoalRepository {

    private final JdbcClient jdbc;

    public Optional<Integer> findDefault(Long userId) {
        return jdbc.sql("SELECT goal_sec FROM planner_default_goal WHERE user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .optional();
    }

    public Optional<Integer> findIndividual(Long userId, LocalDate date) {
        return jdbc.sql("SELECT goal_sec FROM planner_daily_goal WHERE user_id = :u AND goal_date = :d")
                .param("u", userId)
                .param("d", date)
                .query(Integer.class)
                .optional();
    }

    public void upsertDefault(Long userId, int goalSec) {
        jdbc.sql("""
                        INSERT INTO planner_default_goal (user_id, goal_sec) VALUES (:u, :g)
                        ON CONFLICT (user_id) DO UPDATE SET goal_sec = EXCLUDED.goal_sec
                        """).param("u", userId).param("g", goalSec).update();
    }

    public void upsertIndividual(Long userId, LocalDate date, int goalSec) {
        jdbc.sql("""
                        INSERT INTO planner_daily_goal (user_id, goal_date, goal_sec) VALUES (:u, :d, :g)
                        ON CONFLICT (user_id, goal_date) DO UPDATE SET goal_sec = EXCLUDED.goal_sec
                        """).param("u", userId).param("d", date).param("g", goalSec).update();
    }

    public void deleteDefault(Long userId) {
        jdbc.sql("DELETE FROM planner_default_goal WHERE user_id = :u")
                .param("u", userId)
                .update();
    }

    public void deleteIndividual(Long userId, LocalDate date) {
        jdbc.sql("DELETE FROM planner_daily_goal WHERE user_id = :u AND goal_date = :d")
                .param("u", userId)
                .param("d", date)
                .update();
    }
}
