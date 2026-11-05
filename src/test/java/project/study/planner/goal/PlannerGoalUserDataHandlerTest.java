package project.study.planner.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.user.account.AccountData;
import project.study.user.account.UserDataHandler;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PlannerGoalUserDataHandlerTest {

    @Autowired
    private AccountData accountData;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private List<UserDataHandler> handlers;

    @Test
    void 목표의_사용자_컬럼은_병합_탈퇴_처리기_한_개가_책임진다() {
        for (String column : List.of("planner_default_goal.user_id", "planner_daily_goal.user_id")) {
            assertThat(handlers.stream().filter(handler -> handler.columns().contains(column)))
                    .hasSize(1);
        }
    }

    @Test
    void 병합_충돌은_대상의_0도_우선하고_없는_날짜만_옮긴다() {
        long source = seedUser();
        long target = seedUser();
        seedDefault(source, 7200);
        seedDefault(target, 0);
        seedDaily(source, "2030-03-01", 3600);
        seedDaily(source, "2030-03-02", 1800);
        seedDaily(target, "2030-03-01", 0);

        accountData.merge(source, target);

        assertThat(count("planner_default_goal", source)).isZero();
        assertThat(count("planner_daily_goal", source)).isZero();
        assertThat(defaultGoal(target)).isZero();
        assertThat(dailyGoal(target, "2030-03-01")).isZero();
        assertThat(dailyGoal(target, "2030-03-02")).isEqualTo(1800);
        jdbc.sql("DELETE FROM users WHERE id = :u").param("u", source).update();
    }

    @Test
    void 대상에_기본목표가_없으면_원본의_0을_옮긴다() {
        long source = seedUser();
        long target = seedUser();
        seedDefault(source, 0);

        accountData.merge(source, target);

        assertThat(defaultGoal(target)).isZero();
        assertThat(count("planner_default_goal", source)).isZero();
    }

    @Test
    void 탈퇴하면_두_목표를_지우고_다른_사용자는_보존한다() {
        long user = seedUser();
        long other = seedUser();
        seedDefault(user, 3600);
        seedDaily(user, "2030-03-01", 1800);
        seedDefault(other, 7200);
        seedDaily(other, "2030-03-01", 0);

        accountData.erase(user);

        assertThat(count("planner_default_goal", user)).isZero();
        assertThat(count("planner_daily_goal", user)).isZero();
        assertThat(defaultGoal(other)).isEqualTo(7200);
        assertThat(dailyGoal(other, "2030-03-01")).isZero();
        jdbc.sql("DELETE FROM users WHERE id = :u").param("u", user).update();
    }

    private long seedUser() {
        return jdbc.sql("INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', :p, :n) RETURNING id")
                .param("p", UUID.randomUUID().toString())
                .param("n", "goal-handler-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private void seedDefault(long user, int value) {
        jdbc.sql("INSERT INTO planner_default_goal (user_id, goal_sec) VALUES (:u, :g)")
                .param("u", user)
                .param("g", value)
                .update();
    }

    private void seedDaily(long user, String date, int value) {
        jdbc.sql("INSERT INTO planner_daily_goal (user_id, goal_date, goal_sec) VALUES (:u, :d, :g)")
                .param("u", user)
                .param("d", LocalDate.parse(date))
                .param("g", value)
                .update();
    }

    private int defaultGoal(long user) {
        return jdbc.sql("SELECT goal_sec FROM planner_default_goal WHERE user_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    private int dailyGoal(long user, String date) {
        return jdbc.sql("SELECT goal_sec FROM planner_daily_goal WHERE user_id = :u AND goal_date = :d")
                .param("u", user)
                .param("d", LocalDate.parse(date))
                .query(Integer.class)
                .single();
    }

    private long count(String table, long user) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", user)
                .query(Long.class)
                .single();
    }
}
