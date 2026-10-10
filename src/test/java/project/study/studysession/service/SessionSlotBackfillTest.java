package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;

/** 기동 때 이번 주 세션 중 구간 행이 없는 것을 채운다 — 멱등이고 지난 주 이전은 건드리지 않는다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionSlotBackfillTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private SessionSlotBackfill backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "backfill-" + UUID.randomUUID());
    }

    /** 구간 행 없이 세션 행만 넣는다 — V26 이전에 저장된 세션과 같은 모양 */
    private long legacySession(LocalDate date) {
        Instant start = date.atTime(9, 0).atZone(KST).toInstant();
        Instant end = start.plusSeconds(1800);
        return jdbc.queryForObject(
                """
                INSERT INTO study_session
                    (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, auto_finalized)
                VALUES (?, ?, ?, ?, ?, 1800, 1800, false) RETURNING id""",
                Long.class,
                userId,
                date,
                start.atOffset(ZoneOffset.UTC),
                start.atOffset(ZoneOffset.UTC),
                end.atOffset(ZoneOffset.UTC));
    }

    private int slotCount(long sessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_session_slot WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void 이번_주_세션의_빠진_구간_행만_채우고_다시_돌려도_그대로다() {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        long thisWeek = legacySession(today);
        long old = legacySession(today.minusDays(20));

        backfill.run(new DefaultApplicationArguments());
        backfill.run(new DefaultApplicationArguments());

        assertThat(slotCount(thisWeek)).isEqualTo(1);
        assertThat(slotCount(old)).isZero();
    }
}
