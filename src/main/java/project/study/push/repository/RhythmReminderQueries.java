package project.study.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 정기 맞춤 알림의 대상과 발송 직전 확인. 사용자·세션 테이블은 PushExperimentQueries처럼 테이블명으로만 읽는다.
 *
 * <p>공부일과 세션은 기록 탭 공부 리듬과 같이 순공 minFocusSec 이상인 측정 세션만 센다.
 */
@Repository
@RequiredArgsConstructor
public class RhythmReminderQueries {

    private final JdbcClient jdbc;

    public record Candidate(long userId, long studyDays, ZoneId zone) {}

    /** 세션 하나의 시작 시각과 그 세션의 시간대. 자정 분할 조각은 하나로 묶는다. */
    public record RecentStart(long userId, Instant startedAt, ZoneId zone) {}

    /** 알림 권한이 켜진 토큰이 있고 공부일이 하루 이상인 활성 사용자. */
    public List<Candidate> findCandidates(int minFocusSec) {
        return jdbc.sql("""
                        SELECT u.id, d.study_days, u.time_zone
                        FROM users u
                        JOIN (SELECT s.user_id, count(DISTINCT s.stat_date) AS study_days
                              FROM study_session s
                              WHERE s.source = 'MEASURED' AND s.focus_sec >= :minFocusSec
                              GROUP BY s.user_id) d ON d.user_id = u.id
                        WHERE u.status = 'ACTIVE'
                          AND EXISTS (SELECT 1 FROM push_token t WHERE t.user_id = u.id AND t.notifications_enabled)
                        ORDER BY u.id""")
                .param("minFocusSec", minFocusSec)
                .query((rs, rowNum) ->
                        new Candidate(rs.getLong("id"), rs.getLong("study_days"), ZoneId.of(rs.getString("time_zone"))))
                .list();
    }

    /**
     * from 이후에 시작한 세션들. 현지 시각은 호출자가 세션 시간대로 계산한다.
     * 저장된 시간대에는 PostgreSQL이 부호를 반대로 읽는 오프셋 표기(+09:00 등)도 들어올 수 있어 SQL에서 변환하지 않는다.
     */
    public List<RecentStart> findRecentStarts(Instant from, int minFocusSec) {
        // 자정 분할 조각은 submission_started_at을 공유한다(옛 행은 started_at)
        return jdbc.sql("""
                        SELECT DISTINCT s.user_id, coalesce(s.submission_started_at, s.started_at) AS started, s.time_zone
                        FROM study_session s
                        WHERE s.source = 'MEASURED' AND s.focus_sec >= :minFocusSec
                          AND coalesce(s.submission_started_at, s.started_at) >= :from""")
                .param("from", Timestamp.from(from))
                .param("minFocusSec", minFocusSec)
                .query((rs, rowNum) -> new RecentStart(
                        rs.getLong("user_id"),
                        rs.getObject("started", OffsetDateTime.class).toInstant(),
                        ZoneId.of(rs.getString("time_zone"))))
                .list();
    }

    /** 오늘 기록이 없고 지금 공부 중도 아닌지. 수동 기록도 오늘 공부한 것으로 본다. */
    public boolean canSend(long userId, LocalDate today) {
        return jdbc.sql("""
                        SELECT NOT EXISTS (SELECT 1 FROM study_session s WHERE s.user_id = :userId AND s.stat_date = :today)
                           AND NOT EXISTS (SELECT 1 FROM active_study_session a WHERE a.user_id = :userId)""")
                .param("userId", userId)
                .param("today", today)
                .query(Boolean.class)
                .single();
    }

    public Optional<LocalDate> lastSendDate(long userId, String type) {
        return jdbc.sql("""
                        SELECT send_date FROM push_send WHERE user_id = :userId AND type = :type
                        ORDER BY send_date DESC LIMIT 1""")
                .param("userId", userId)
                .param("type", type)
                .query(LocalDate.class)
                .optional();
    }
}
