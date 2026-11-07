package project.study.mission.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

@Component
@RequiredArgsConstructor
public class HomeMissionUserDataHandler implements UserDataHandler {
    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("home_mission_progress.user_id");
    }

    @Override
    public int order() {
        return 520;
    }

    @Override
    public void merge(long source, long target) {
        jdbc.sql("""
                INSERT INTO home_mission_progress (user_id, mission_version, completed_steps)
                SELECT :target, mission_version, completed_steps FROM home_mission_progress WHERE user_id = :source
                ON CONFLICT (user_id, mission_version) DO UPDATE SET completed_steps = GREATEST(home_mission_progress.completed_steps, EXCLUDED.completed_steps)
                """).param("source", source).param("target", target).update();
        erase(source);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM home_mission_progress WHERE user_id = :u")
                .param("u", userId)
                .update();
    }
}
