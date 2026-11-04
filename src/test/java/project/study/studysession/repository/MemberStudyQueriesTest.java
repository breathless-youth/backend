package project.study.studysession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;

/**
 * 그룹 멤버의 마지막 측정 세션 시각
 *
 * 세션마다 시간대가 달라 stat_date 순서와 시각 순서가 어긋날 수 있어서, 시각이 가장 늦은 세션을 고르는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberStudyQueriesTest {

    @Autowired
    private MemberStudyQueries memberStudyQueries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private void insertSession(LocalDate statDate, Instant startedAt, Instant endedAt, String timeZone) {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, time_zone) VALUES (?, ?, ?, ?, ?, 3600, 3000, ?)",
                userId,
                Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                Timestamp.from(endedAt),
                timeZone);
    }

    @Test
    void 시간대를_바꾼_뒤의_세션이_통계_날짜는_더_이르더라도_시각이_가장_늦으면_그_세션을_고른다() {
        // 서울 11월 5일 00:30에 공부하고, 뉴욕으로 가서 뉴욕 11월 4일 20:00(서울 11월 5일 10:00)에 다시 공부했다
        insertSession(
                LocalDate.of(2026, 11, 5),
                Instant.parse("2026-11-04T15:30:00Z"),
                Instant.parse("2026-11-04T16:30:00Z"),
                "Asia/Seoul");
        insertSession(
                LocalDate.of(2026, 11, 4),
                Instant.parse("2026-11-05T01:00:00Z"),
                Instant.parse("2026-11-05T02:00:00Z"),
                "America/New_York");

        assertThat(memberStudyQueries.lastMeasuredEndedAt(List.of(userId)))
                .containsEntry(userId, Instant.parse("2026-11-05T02:00:00Z"));
    }

    @Test
    void 두_기기로_겹쳐_공부했으면_나중에_시작한_세션이_아니라_나중에_끝난_세션의_시각이다() {
        insertSession(
                LocalDate.of(2026, 11, 4),
                Instant.parse("2026-11-04T01:00:00Z"),
                Instant.parse("2026-11-04T05:00:00Z"),
                "Asia/Seoul");
        insertSession(
                LocalDate.of(2026, 11, 4),
                Instant.parse("2026-11-04T02:00:00Z"),
                Instant.parse("2026-11-04T03:00:00Z"),
                "Asia/Seoul");

        assertThat(memberStudyQueries.lastMeasuredEndedAt(List.of(userId)))
                .containsEntry(userId, Instant.parse("2026-11-04T05:00:00Z"));
    }

    @Test
    void 측정_세션이_없으면_결과에_넣지_않는다() {
        assertThat(memberStudyQueries.lastMeasuredEndedAt(List.of(userId))).doesNotContainKey(userId);
    }
}
