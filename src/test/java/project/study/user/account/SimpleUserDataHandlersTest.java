package project.study.user.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SimpleUserDataHandlersTest {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 1);

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    @Test
    void ddayMovesWhenTargetHasNone() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedDday(source, "src");

        accountData.merge(source, target);

        assertThat(fixture.count("user_dday", "user_id", source)).isZero();
        assertThat(jdbc.sql("SELECT title FROM user_dday WHERE user_id = :u")
                        .param("u", target)
                        .query(String.class)
                        .single())
                .isEqualTo("src");
    }

    @Test
    void ddayOfTargetWinsWhenBothHave() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedDday(source, "src");
        fixture.seedDday(target, "tgt");

        accountData.merge(source, target);

        assertThat(fixture.count("user_dday", "user_id", source)).isZero();
        assertThat(jdbc.sql("SELECT title FROM user_dday WHERE user_id = :u")
                        .param("u", target)
                        .query(String.class)
                        .single())
                .isEqualTo("tgt");
    }

    @Test
    void acquisitionMovesWhenTargetHasNone() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedAcquisition(source, "src");

        accountData.merge(source, target);

        assertThat(fixture.count("user_acquisition", "user_id", source)).isZero();
        assertThat(acquisitionSource(target)).isEqualTo("src");
    }

    @Test
    void acquisitionOfTargetWinsWhenBothHave() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedAcquisition(source, "src");
        fixture.seedAcquisition(target, "tgt");

        accountData.merge(source, target);

        assertThat(fixture.count("user_acquisition", "user_id", source)).isZero();
        assertThat(acquisitionSource(target)).isEqualTo("tgt");
    }

    @Test
    void pushTokensMoveToTarget() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedPushToken(source, "tok-" + source);
        fixture.seedPushToken(target, "tok-" + target);

        accountData.merge(source, target);

        assertThat(fixture.count("push_token", "user_id", source)).isZero();
        assertThat(fixture.count("push_token", "user_id", target)).isEqualTo(2);
    }

    @Test
    void pushSendAndAssignmentOfSourceAreDropped() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedPushSend(source, DAY);
        fixture.seedPushAssignment(source, "exp");
        fixture.seedPushAssignment(target, "exp");

        accountData.merge(source, target);

        assertThat(fixture.count("push_send", "user_id", source)).isZero();
        assertThat(fixture.count("push_send", "user_id", target)).isZero();
        assertThat(fixture.count("push_experiment_assignment", "user_id", source))
                .isZero();
        assertThat(fixture.count("push_experiment_assignment", "user_id", target))
                .isEqualTo(1);
    }

    @Test
    void statsCollidingDateKeepsTargetAndOthersMove() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        for (String table : new String[] {"study_stats_daily", "study_stats_weekly", "study_stats_monthly"}) {
            fixture.seedStats(table, source, DAY, 100);
            fixture.seedStats(table, source, DAY.plusDays(1), 200);
            fixture.seedStats(table, target, DAY, 999);
        }

        accountData.merge(source, target);

        for (String table : new String[] {"study_stats_daily", "study_stats_weekly", "study_stats_monthly"}) {
            assertThat(fixture.count(table, "user_id", source)).isZero();
            assertThat(fixture.count(table, "user_id", target)).isEqualTo(2);
            assertThat(jdbc.sql("SELECT total_session_sec FROM " + table + " WHERE user_id = :u ORDER BY 1")
                            .param("u", target)
                            .query(Integer.class)
                            .list())
                    .containsExactly(200, 999);
        }
    }

    @Test
    void reportInquiryAndNotificationMoveToTarget() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long other = fixture.seedUser();
        fixture.seedReport(source, other);
        fixture.seedReport(other, source);
        fixture.seedInquiry(source);
        fixture.seedUserNotification(source);

        accountData.merge(source, target);

        assertThat(fixture.count("report", "report_user_id", target)).isEqualTo(1);
        assertThat(fixture.count("report", "target_user_id", target)).isEqualTo(1);
        assertThat(fixture.count("inquiry", "user_id", target)).isEqualTo(1);
        assertThat(fixture.count("user_notification", "user_id", target)).isEqualTo(1);
        assertThat(fixture.countRowsReferencing(source)).isZero();
    }

    @Test
    void refreshTokensOfSourceAreAllDeleted() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedRefreshToken(source);
        fixture.seedRefreshToken(source);
        fixture.seedRefreshToken(target);

        accountData.merge(source, target);

        assertThat(fixture.count("refresh_token", "user_id", source)).isZero();
        assertThat(fixture.count("refresh_token", "user_id", target)).isEqualTo(1);
    }

    @Test
    void eraseRemovesEverythingTheSimpleHandlersOwn() {
        long user = fixture.seedUser();
        long other = fixture.seedUser();
        fixture.seedDday(user, "d");
        fixture.seedAcquisition(user, "s");
        fixture.seedPushToken(user, "tok-" + user);
        fixture.seedPushSend(user, DAY);
        fixture.seedPushAssignment(user, "exp");
        fixture.seedStats("study_stats_daily", user, DAY, 1);
        fixture.seedStats("study_stats_weekly", user, DAY, 1);
        fixture.seedStats("study_stats_monthly", user, DAY, 1);
        fixture.seedRefreshToken(user);
        fixture.seedReport(user, other);
        fixture.seedReport(other, user);
        fixture.seedInquiry(user);
        fixture.seedUserNotification(user);
        assertThat(fixture.countRowsReferencing(user)).isPositive();

        accountData.erase(user);

        assertThat(fixture.countRowsReferencing(user)).isZero();
    }

    private String acquisitionSource(long userId) {
        return jdbc.sql("SELECT source FROM user_acquisition WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .single();
    }
}
