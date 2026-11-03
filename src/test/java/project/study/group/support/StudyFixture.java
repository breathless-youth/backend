package project.study.group.support;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 멤버 책상 테스트가 공부 기록을 세우는 도구 (BY-1124) — 확정 세션 행과 진행 중 draft를 SQL로 바로 만든다. 시각은 {@link GroupDeskTestConfig#NOW}
 * 기준이다.
 */
public class StudyFixture {

    private static final Instant NOW = GroupDeskTestConfig.NOW;

    private final JdbcClient jdbc;

    public StudyFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 확정 측정 세션 한 조각 — 날짜는 시작 시각의 KST 날짜, 끝은 시작 + 순공. */
    public void session(long userId, Instant start, int focusSec) {
        jdbc.sql("""
                        insert into study_session (user_id, stat_date, started_at, submission_started_at, ended_at,
                                                   study_sec, focus_sec, source)
                        values (:userId, :statDate, :start, :start, :end, :focusSec, :focusSec, 'MEASURED')""")
                .param("userId", userId)
                .param("statDate", Date.valueOf(LocalDate.ofInstant(start, GroupDeskTestConfig.KST)))
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(start.plusSeconds(focusSec)))
                .param("focusSec", focusSec)
                .update();
    }

    /** NOW 10초 전에 받은, 진행 중인 이벤트가 없는 draft — 집중 중이라 NOW까지 10초를 늘려 센다. */
    public void studying(long userId, int reportedFocusSec) {
        draft(userId, NOW.minusSeconds(reportedFocusSec + 10), NOW.minusSeconds(10), reportedFocusSec, "[]");
    }

    /** NOW 10초 전에 받은, 일시정지가 진행 중인 draft. */
    public void paused(long userId, int reportedFocusSec) {
        Instant reported = NOW.minusSeconds(10);
        draft(userId, reported.minusSeconds(reportedFocusSec + 60), reported, reportedFocusSec, """
                [{"status":"PAUSE","startedAt":"%s","endedAt":"%s"}]""".formatted(
                        reported.minusSeconds(60), reported));
    }

    /** 마지막 수신 = reportedAt, 총공부는 길이 전체. events는 JSON 배열 문자열 */
    public void draft(long userId, Instant start, Instant reportedAt, int focusSec, String events) {
        jdbc.sql("""
                        insert into active_study_session
                            (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec, events)
                        values (:userId, :start, :reported, :reported, :studySec, :focusSec, cast(:events as jsonb))""")
                .param("userId", userId)
                .param("start", Timestamp.from(start))
                .param("reported", Timestamp.from(reportedAt))
                .param("studySec", (int) Duration.between(start, reportedAt).toSeconds())
                .param("focusSec", focusSec)
                .param("events", events)
                .update();
    }
}
