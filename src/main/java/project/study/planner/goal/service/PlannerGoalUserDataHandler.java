package project.study.planner.goal.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.account.UserDataHandler;

/** 병합 시 대상에 이미 있는 날짜·기본값이 우선한다. 0도 설정된 목표로 취급한다. */
@Component
@RequiredArgsConstructor
public class PlannerGoalUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;
    private final StudySubjectLockRepository locks;

    @Override
    public Set<String> columns() {
        return Set.of("planner_default_goal.user_id", "planner_daily_goal.user_id");
    }

    @Override
    public int order() {
        return 510;
    }

    @Override
    public void lockBeforeUsers(long userId) {
        locks.lockUser(userId);
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("""
                        INSERT INTO planner_default_goal (user_id, goal_sec)
                        SELECT :target, goal_sec FROM planner_default_goal WHERE user_id = :source
                        ON CONFLICT (user_id) DO NOTHING
                        """)
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("""
                        INSERT INTO planner_daily_goal (user_id, goal_date, goal_sec)
                        SELECT :target, goal_date, goal_sec FROM planner_daily_goal WHERE user_id = :source
                        ON CONFLICT (user_id, goal_date) DO NOTHING
                        """)
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM planner_daily_goal WHERE user_id = :u")
                .param("u", userId)
                .update();
        jdbc.sql("DELETE FROM planner_default_goal WHERE user_id = :u")
                .param("u", userId)
                .update();
    }
}
