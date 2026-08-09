package project.study.metrics;

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
import project.study.studysession.service.StudySessionMetricsService;
import project.study.user.service.UserService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class MetricsQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET = LocalDate.of(2020, 1, 10);

    @Autowired
    private UserService userService;

    @Autowired
    private StudySessionMetricsService studySessionMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int sequence = 0;

    /** created_at은 @CreatedDate가 now()로 채우므로, 과거 가입을 만들려면 직접 넣어야 한다. */
    private long insertUserCreatedAt(Instant createdAt) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, ?, ?) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
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

    @Test
    void 총_가입_수는_유저를_넣은_만큼_늘어난다() {
        long before = userService.countTotal();

        insertUserCreatedAt(Instant.parse("2020-01-10T05:00:00Z"));
        insertUserCreatedAt(Instant.parse("2020-01-10T06:00:00Z"));

        assertThat(userService.countTotal()).isEqualTo(before + 2);
    }

    @Test
    void 해당_날짜_KST_범위에_가입한_유저만_센다() {
        long before = userService.countRegisteredOn(TARGET);

        // KST 2020-01-10 00:00:00 = UTC 2020-01-09 15:00:00 (경계 포함)
        insertUserCreatedAt(Instant.parse("2020-01-09T15:00:00Z"));
        // KST 2020-01-10 23:59:59
        insertUserCreatedAt(Instant.parse("2020-01-10T14:59:59Z"));
        // KST 2020-01-09 23:59:59 — 전날이라 제외
        insertUserCreatedAt(Instant.parse("2020-01-09T14:59:59Z"));
        // KST 2020-01-11 00:00:00 — 다음날이라 제외
        insertUserCreatedAt(Instant.parse("2020-01-10T15:00:00Z"));

        assertThat(userService.countRegisteredOn(TARGET)).isEqualTo(before + 2);
    }

    @Test
    void 해당_날짜의_10분_이상_세션만_센다() {
        long before = studySessionMetricsService.countQualifyingSessionsOn(TARGET);
        long userId = insertUserCreatedAt(Instant.parse("2020-01-10T05:00:00Z"));

        insertSession(userId, TARGET, 600);
        insertSession(userId, TARGET, 3600);
        insertSession(userId, TARGET, 599); // 미만이라 제외
        insertSession(userId, TARGET.minusDays(1), 600); // 다른 날이라 제외

        assertThat(studySessionMetricsService.countQualifyingSessionsOn(TARGET)).isEqualTo(before + 2);
    }
}
