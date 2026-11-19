package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.MemberStudy;
import project.study.studysession.dto.MemberStudy.Presence;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.MemberStudySource;
import project.study.studysession.service.MemberStudySource.WeekSec;

/**
 * 그룹 멤버 책상은 기본 모드 세션도 순공에 넣고, 공개 그룹 정렬용으로 관리 모드 순공을 따로 센다 (ADR-0049).
 * 파일 400줄 제한으로 MemberStudySourceTest에서 분리했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberStudySourceTimerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-10-14(수) 15:00 KST. 이번 주는 10/12(월)부터다. */
    private static final Instant AS_OF = kst(14, 15, 0, 0);

    @Autowired
    private MemberStudySource source;

    @Autowired
    private ActiveStudySessionRepository drafts;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();

    // 전 유저 draft를 읽는 다른 테스트가 흔들리지 않게 만든 draft를 지운다
    @AfterEach
    void cleanUpDrafts() {
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM active_study_session WHERE user_id = ?", id));
        createdUserIds.clear();
    }

    private long user() {
        long id = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "desk-" + UUID.randomUUID().toString().substring(0, 8));
        createdUserIds.add(id);
        return id;
    }

    private static Instant kst(int day, int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, KST).toInstant();
    }

    /** 확정 세션 한 조각. 날짜는 시작 시각의 KST 날짜, 끝은 시작 + 순공이다. */
    private void session(long userId, Instant start, int focusSec, String sessionSource) {
        jdbc.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId,
                Date.valueOf(LocalDate.ofInstant(start, KST)),
                Timestamp.from(start),
                Timestamp.from(start),
                Timestamp.from(start.plusSeconds(focusSec)),
                focusSec,
                focusSec,
                sessionSource);
    }

    /** 진행 중 스냅샷. 마지막 수신은 reportedAt, 총공부는 길이 전체다. */
    private void draft(long userId, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        drafts.upsertSnapshot(userId, start, reportedAt, reportedAt, length, focusSec, events);
    }

    private MemberStudy of(long userId) {
        return source.of(List.of(userId), AS_OF).get(userId);
    }

    @Test
    void 기본_모드_확정_세션도_오늘과_이번_주와_마지막_공부에_들어간다() {
        long me = user();
        session(me, kst(14, 10, 0, 0), 1800, "TIMER"); // 오늘 30분, 10:30에 끝남
        session(me, kst(12, 0, 30, 0), 1200, "TIMER"); // 이번 주 월요일
        session(me, kst(14, 8, 0, 0), 600, "MEASURED");

        MemberStudy study = of(me);

        assertThat(study.todayFocusSec()).isEqualTo(1800 + 600);
        assertThat(study.weekFocusSec()).isEqualTo(1800 + 1200 + 600);
        assertThat(study.lastStudiedAt()).isEqualTo(kst(14, 10, 30, 0));
    }

    @Test
    void 기본_모드_draft도_STUDYING이고_순공에_들어간다() {
        long me = user();
        draft(me, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");
        jdbc.update("UPDATE active_study_session SET source = 'TIMER' WHERE user_id = ?", me);

        MemberStudy study = of(me);

        assertThat(study.presence()).isEqualTo(Presence.STUDYING);
        assertThat(study.accumulating()).isTrue();
        assertThat(study.todayFocusSec()).isEqualTo(600);
        assertThat(study.weekFocusSec()).isEqualTo(600);
    }

    @Test
    void weekFocus는_관리_모드_순공을_따로_센다() {
        long measuredDraft = user();
        long timerDraft = user();
        session(measuredDraft, kst(12, 0, 30, 0), 1200, "MEASURED");
        session(measuredDraft, kst(13, 9, 0, 0), 1800, "TIMER");
        draft(measuredDraft, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");
        session(timerDraft, kst(13, 9, 0, 0), 900, "MEASURED");
        draft(timerDraft, AS_OF.minusSeconds(600), AS_OF.minusSeconds(10), 590, "[]");
        jdbc.update("UPDATE active_study_session SET source = 'TIMER' WHERE user_id = ?", timerDraft);

        Map<Long, WeekSec> week = source.weekFocus(List.of(measuredDraft, timerDraft), AS_OF);

        assertThat(week.get(measuredDraft)).isEqualTo(new WeekSec(1200 + 1800 + 600, 1200 + 600));
        assertThat(week.get(timerDraft)).isEqualTo(new WeekSec(900 + 600, 900));
    }
}
