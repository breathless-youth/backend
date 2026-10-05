package project.study.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.interview.service.InterviewGroupService;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** 인터뷰 대상 그룹 판정 — 쿼리가 핵심이라 실제 PostgreSQL로 검증한다. Clock만 고정해 직접 만든다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class InterviewGroupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant SINCE = NOW.minus(Duration.ofHours(168));
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 1);

    @Autowired
    private StudySessionRepository studySessionRepository;

    @Autowired
    private ActiveStudySessionRepository activeStudySessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private InterviewGroupService service;
    private long userId;

    @BeforeEach
    void setUp() {
        service = new InterviewGroupService(
                studySessionRepository, activeStudySessionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
    }

    /** 자정 분할이 없는 세션 한 건 — 루트 제출 시각은 자기 시작 시각이다. */
    private void session(Instant endedAt, int focusSec, boolean autoFinalized) {
        Instant startedAt = endedAt.minus(Duration.ofHours(1));
        fragment(startedAt, startedAt, endedAt, focusSec, autoFinalized);
    }

    private void fragment(
            Instant submissionStartedAt, Instant startedAt, Instant endedAt, int focusSec, boolean autoFinalized) {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(STAT_DATE),
                Timestamp.from(startedAt),
                Timestamp.from(submissionStartedAt),
                Timestamp.from(endedAt),
                focusSec,
                focusSec,
                autoFinalized);
    }

    private void activeDraft() {
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, 0, 0, '[]'::jsonb)",
                userId,
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private Instant hoursAgo(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }

    @Test
    void 세션이_없으면_1번이다() {
        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G1_NOT_STARTED);
    }

    @Test
    void 세션이_없어도_진행_중_세션이_있으면_1번이_아니다() {
        activeDraft();

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 자동_종료_세션도_시작한_세션이라_1번이_아니다() {
        session(hoursAgo(1), 1200, true);

        assertThat(service.judge(userId)).isNotEqualTo(InterviewGroup.G1_NOT_STARTED);
    }

    @Test
    void 마지막_세션이_정확히_168시간_전에_끝났으면_2번이다() {
        session(SINCE, 1200, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G2_LAPSED);
    }

    @Test
    void 마지막_세션이_168시간에서_1초_모자라면_2번이_아니다() {
        session(SINCE.plusSeconds(1), 1200, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 자동_종료_세션이_마지막이어도_2번_기준이_된다() {
        session(hoursAgo(300), 1200, false);
        session(hoursAgo(200), 1200, true);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G2_LAPSED);
    }

    @Test
    void 오래_쉬었어도_진행_중_세션이_있으면_2번이_아니다() {
        session(hoursAgo(200), 1200, false);
        activeDraft();

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 최근_168시간_완료_세션이_3건이면_3번이다() {
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);
        session(hoursAgo(3), 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G3_ACTIVE);
    }

    @Test
    void 완료_세션이_2건이면_3번이_아니다() {
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 순공_599초_세션은_완료로_세지_않는다() {
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);
        session(hoursAgo(3), 599, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 자동_종료_세션은_완료로_세지_않는다() {
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);
        session(hoursAgo(3), 3600, true);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 정확히_168시간_전에_끝난_세션은_최근_완료에_들지_않는다() {
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);
        session(SINCE, 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 자정_분할_세션은_순공을_합쳐_1건으로_센다() {
        // 두 조각 각각은 10분 미만이지만 합치면 10분이다
        Instant root = hoursAgo(5);
        Instant midnight = hoursAgo(4);
        fragment(root, root, midnight, 300, false);
        fragment(root, midnight, hoursAgo(3), 300, false);
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G3_ACTIVE);
    }

    @Test
    void 자정_분할_조각이_각각_10분이어도_2건이_아니다() {
        Instant root = hoursAgo(5);
        Instant midnight = hoursAgo(4);
        fragment(root, root, midnight, 600, false);
        fragment(root, midnight, hoursAgo(3), 600, false);
        session(hoursAgo(1), 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.NONE);
    }

    @Test
    void 창_경계에_걸친_자정_분할_세션은_끝난_시각으로_판단한다() {
        // 앞 조각은 168시간 전에 끝났지만 뒤 조각이 창 안에서 끝났다 — 묶음 전체가 최근 완료 1건이다
        Instant root = SINCE.minus(Duration.ofHours(2));
        Instant midnight = SINCE.minus(Duration.ofHours(1));
        fragment(root, root, midnight, 300, false);
        fragment(root, midnight, SINCE.plus(Duration.ofHours(1)), 300, false);
        session(hoursAgo(1), 600, false);
        session(hoursAgo(2), 600, false);

        assertThat(service.judge(userId)).isEqualTo(InterviewGroup.G3_ACTIVE);
    }
}
