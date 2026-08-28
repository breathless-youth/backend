package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.metrics.dto.CohortFirstWeek;
import project.study.studysession.service.StudySessionMetricsService;

/** 코호트 첫주 공부일수 쿼리 통합테스트. 쿼리가 전체 유저를 집계하므로 각 테스트는 자신의 데이터만 넣는다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CohortFirstWeekQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate ANCHOR = LocalDate.of(2020, 3, 10);
    private static final int QUALIFYING = 600;
    private static final int BELOW = 599;

    @Autowired
    private StudySessionMetricsService studySessionMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int sequence = 0;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private void insertSession(long userId, LocalDate statDate, int focusSec) {
        Instant startedAt = statDate.atStartOfDay(KST).toInstant().plusSeconds(sequence++);
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusSeconds(3600)),
                focusSec,
                focusSec);
    }

    private CohortFirstWeek cohort() {
        return studySessionMetricsService.cohortFirstWeek(ANCHOR);
    }

    @Test
    void D0가_기준일_빼기_6이면_코호트에_포함되고_창_안_공부일을_센다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(6), QUALIFYING); // D0 = 경계
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(cohort().cohortSize()).isEqualTo(1);
        assertThat(cohort().avgDays()).isEqualTo(3.0);
    }

    @Test
    void D0가_기준일_빼기_5이면_창이_완결되지_않아_제외된다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(cohort().cohortSize()).isZero();
    }

    @Test
    void 창_밖의_공부일은_세지_않는다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(8), QUALIFYING); // D0, 창 [ANCHOR-8, ANCHOR-2]
        insertSession(userId, ANCHOR.minusDays(7), QUALIFYING); // 창 안
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING); // 창 밖(D0+7)

        assertThat(cohort().cohortSize()).isEqualTo(1);
        assertThat(cohort().avgDays()).isEqualTo(2.0);
    }

    @Test
    void 같은_날_여러_세션은_하루로_세고_10분_미만은_공부일이_아니다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(6), QUALIFYING); // D0
        insertSession(userId, ANCHOR.minusDays(6), QUALIFYING); // 같은 날
        insertSession(userId, ANCHOR.minusDays(5), BELOW); // 10분 미만 → 공부일 아님

        assertThat(cohort().cohortSize()).isEqualTo(1);
        assertThat(cohort().avgDays()).isEqualTo(1.0);
    }

    @Test
    void 코호트_여러_명의_공부일수를_평균낸다() {
        long threeDays = insertUser();
        insertSession(threeDays, ANCHOR.minusDays(6), QUALIFYING);
        insertSession(threeDays, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(threeDays, ANCHOR.minusDays(4), QUALIFYING);

        long oneDay = insertUser();
        insertSession(oneDay, ANCHOR.minusDays(6), QUALIFYING);

        assertThat(cohort().cohortSize()).isEqualTo(2);
        assertThat(cohort().avgDays()).isEqualTo(2.0); // (3 + 1) / 2
    }
}
