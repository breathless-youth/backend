package project.study.user.account;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 계정 병합·탈퇴 테스트용 시드. 테이블마다 seedXxx를 두고, 처리기가 책임지는 컬럼 전부의 행 수를 센다. */
@Component
@RequiredArgsConstructor
public class UserDataFixture {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private final JdbcClient jdbc;
    private final List<UserDataHandler> handlers;

    public long seedUser() {
        long n = SEQ.incrementAndGet();
        return jdbc.sql("""
                        INSERT INTO users (provider, provider_user_id, nickname)
                        VALUES ('ANONYMOUS', :pid, :nick) RETURNING id""")
                .param("pid", "fx-" + n + "-" + UUID.randomUUID())
                .param("nick", "fx" + n)
                .query(Long.class)
                .single();
    }

    public void seedDday(long userId, String title) {
        jdbc.sql("INSERT INTO user_dday (user_id, title, target_date) VALUES (:u, :t, DATE '2030-01-01')")
                .param("u", userId)
                .param("t", title)
                .update();
    }

    public void seedAcquisition(long userId, String source) {
        jdbc.sql("""
                        INSERT INTO user_acquisition (user_id, platform, source, captured_at, created_at)
                        VALUES (:u, 'ios', :s, now(), now())""").param("u", userId).param("s", source).update();
    }

    public void seedPushToken(long userId, String token) {
        jdbc.sql("""
                        INSERT INTO push_token (user_id, token, platform, notifications_enabled, created_at, updated_at)
                        VALUES (:u, :t, 'ios', true, now(), now())""").param("u", userId).param("t", token).update();
    }

    public void seedPushSend(long userId, LocalDate date) {
        jdbc.sql("""
                        INSERT INTO push_send (push_id, user_id, type, send_date, sent_at)
                        VALUES (:id, :u, 'test', :d, now())""")
                .param("id", UUID.randomUUID())
                .param("u", userId)
                .param("d", date)
                .update();
    }

    public void seedPushAssignment(long userId, String experiment) {
        jdbc.sql("""
                        INSERT INTO push_experiment_assignment (experiment, user_id, arm, assigned_at)
                        VALUES (:e, :u, 'control', now())""").param("e", experiment).param("u", userId).update();
    }

    /** table은 study_stats_daily | study_stats_weekly | study_stats_monthly. */
    public void seedStats(String table, long userId, LocalDate date, int sessionSec) {
        String dateColumn = "study_stats_daily".equals(table) ? "stat_date" : "period_start";
        jdbc.sql("INSERT INTO " + table + " (user_id, " + dateColumn + ", total_session_sec) VALUES (:u, :d, :s)")
                .param("u", userId)
                .param("d", date)
                .param("s", sessionSec)
                .update();
    }

    public void seedRefreshToken(long userId) {
        jdbc.sql("""
                        INSERT INTO refresh_token (user_id, token_hash, expires_at, created_at)
                        VALUES (:u, :h, now() + interval '1 day', now())""")
                .param("u", userId)
                .param("h", UUID.randomUUID().toString())
                .update();
    }

    public void seedReport(long reporterId, long targetId) {
        jdbc.sql("""
                        INSERT INTO report (report_user_id, target_user_id, reason, created_at)
                        VALUES (:r, :t, 'x', now())""").param("r", reporterId).param("t", targetId).update();
    }

    public void seedInquiry(long userId) {
        jdbc.sql("INSERT INTO inquiry (user_id, content, created_at) VALUES (:u, 'q', now())")
                .param("u", userId)
                .update();
    }

    public void seedUserNotification(long userId) {
        long notificationId = jdbc.sql("""
                        INSERT INTO notification (title, content, created_at, updated_at)
                        VALUES ('t', 'c', now(), now()) RETURNING id""").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO user_notification (user_id, notification_id, is_read, created_at)
                        VALUES (:u, :n, false, now())""").param("u", userId).param("n", notificationId).update();
    }

    /** endedAt이 null이면 옛 세션처럼 ended_at을 비워 둔다. */
    public long seedSession(long userId, Instant startedAt, Instant endedAt) {
        return seedSession(userId, startedAt, endedAt, 60);
    }

    public long seedSession(long userId, Instant startedAt, Instant endedAt, int studySec) {
        return jdbc.sql("""
                        INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec)
                        VALUES (:u, DATE '2030-03-01', :s, :e, :sec, 60) RETURNING id""")
                .param("u", userId)
                .param("sec", studySec)
                .param("s", Timestamp.from(startedAt))
                .param("e", endedAt == null ? null : Timestamp.from(endedAt), Types.TIMESTAMP)
                .query(Long.class)
                .single();
    }

    public void seedStatusEvent(long sessionId) {
        jdbc.sql("INSERT INTO status_event (session_id, status) VALUES (:s, 'PAUSE')")
                .param("s", sessionId)
                .update();
    }

    public void seedActiveSession(long userId, Instant startedAt) {
        seedActiveSession(userId, startedAt, Instant.now());
    }

    public void seedActiveSession(long userId, Instant startedAt, Instant lastSeenAt) {
        jdbc.sql("""
                        INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec)
                        VALUES (:u, :s, :l, :l, 0, 0)""")
                .param("u", userId)
                .param("s", Timestamp.from(startedAt))
                .param("l", Timestamp.from(lastSeenAt))
                .update();
    }

    /** 과목 구간 하나가 [startedAt, reportedAt) 전체인 진행 중 기록 — 자동 확정할 수 있는 모양이다. */
    public long seedDraftWithSegment(long userId, Instant startedAt, Instant reportedAt, long subjectId) {
        int sec = (int) Duration.between(startedAt, reportedAt).toSeconds();
        String segments = "[{\"subjectId\":%d,\"startedAt\":\"%s\",\"endedAt\":\"%s\"}]"
                .formatted(subjectId, startedAt, reportedAt);
        return jdbc.sql("""
                        INSERT INTO active_study_session
                            (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec, subject_segments)
                        VALUES (:u, :s, :r, :r, :sec, :sec, CAST(:seg AS jsonb)) RETURNING id""")
                .param("u", userId)
                .param("s", Timestamp.from(startedAt))
                .param("r", Timestamp.from(reportedAt))
                .param("sec", sec)
                .param("seg", segments)
                .query(Long.class)
                .single();
    }

    public long seedSubject(long userId, String name, int sortOrder, boolean deleted) {
        return jdbc.sql("""
                        INSERT INTO study_subject (user_id, name, sort_order, deleted_at)
                        VALUES (:u, :n, :o, CASE WHEN :d THEN now() END) RETURNING id""")
                .param("u", userId)
                .param("n", name)
                .param("o", sortOrder)
                .param("d", deleted)
                .query(Long.class)
                .single();
    }

    public long seedTask(long subjectId, String name) {
        return jdbc.sql("INSERT INTO study_task (subject_id, name) VALUES (:s, :n) RETURNING id")
                .param("s", subjectId)
                .param("n", name)
                .query(Long.class)
                .single();
    }

    public void seedSegment(long sessionId, long subjectId, Instant startedAt, Instant endedAt) {
        jdbc.sql("""
                        INSERT INTO study_session_subject_segment (session_id, subject_id, started_at, ended_at, study_sec, focus_sec)
                        VALUES (:sess, :sub, :s, :e, 60, 60)""")
                .param("sess", sessionId)
                .param("sub", subjectId)
                .param("s", Timestamp.from(startedAt))
                .param("e", Timestamp.from(endedAt))
                .update();
    }

    public void seedDailyCompletion(long userId, long subjectId, LocalDate date) {
        jdbc.sql("""
                        INSERT INTO daily_completion (user_id, target_type, target_id, completed_date, completed_at)
                        VALUES (:u, 'SUBJECT', :t, :d, now())""").param("u", userId).param("t", subjectId).param("d", date).update();
    }

    public long seedRoom(long creatorId) {
        return jdbc.sql("""
                        INSERT INTO rooms (invite_code, created_by, created_at)
                        VALUES (:code, :u, now()) RETURNING id""")
                .param("code", String.valueOf(1000 + SEQ.incrementAndGet() % 9000))
                .param("u", creatorId)
                .query(Long.class)
                .single();
    }

    /** live가 false면 이미 나간 이력 행이다. */
    public void seedParticipation(long roomId, long userId, boolean live) {
        jdbc.sql("""
                        INSERT INTO room_participations (room_id, user_id, reserved_at, joined_at, left_at, leave_reason)
                        VALUES (:r, :u, now(), now(), CASE WHEN :live THEN NULL ELSE now() END,
                                CASE WHEN :live THEN NULL ELSE 'EXPLICIT' END)""").param("r", roomId).param("u", userId).param("live", live).update();
    }

    public void seedRtcStat(long roomId, long userId, Long peerUserId) {
        jdbc.sql("""
                        INSERT INTO rtc_connection_stat (connection_id, room_id, user_id, peer_user_id, candidate_type, is_final, created_at)
                        VALUES (:c, :r, :u, :p, 'host', true, now())""")
                .param("c", UUID.randomUUID().toString())
                .param("r", roomId)
                .param("u", userId)
                .param("p", peerUserId, Types.BIGINT)
                .update();
    }

    public void seedRankingRecord(long userId, String boardKey, LocalDate periodStart, int rank) {
        jdbc.sql("""
                        INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                        VALUES (:u, :b, :p, now(), :rank, 100)""")
                .param("u", userId)
                .param("b", boardKey)
                .param("p", periodStart)
                .param("rank", rank)
                .update();
    }

    public void seedRankingBest(long userId, int rank, String boardKey, LocalDate periodStart) {
        jdbc.sql("INSERT INTO ranking_best (user_id, rank, board_key, period_start) VALUES (:u, :rank, :b, :p)")
                .param("u", userId)
                .param("rank", rank)
                .param("b", boardKey)
                .param("p", periodStart)
                .update();
    }

    /** 모든 처리기가 책임지는 컬럼에서 userId를 가리키는 행의 합. */
    public long countRowsReferencing(long userId) {
        return countRowsReferencing(userId, Set.of());
    }

    /** excludedColumns("테이블.컬럼")는 세지 않는다. 명세상 예외를 테스트에서 드러내 쓸 때만 쓴다. */
    public long countRowsReferencing(long userId, Set<String> excludedColumns) {
        long total = 0;
        for (UserDataHandler handler : handlers) {
            for (String column : handler.columns()) {
                if (excludedColumns.contains(column)) {
                    continue;
                }
                int dot = column.indexOf('.');
                String table = column.substring(0, dot);
                String col = column.substring(dot + 1);
                total += jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + col + " = :id")
                        .param("id", userId)
                        .query(Long.class)
                        .single();
            }
        }
        return total;
    }

    public long count(String table, String column, long userId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    /**
     * users를 가리키는 모든 테이블에 user의 행을 심는다. other는 신고·RTC 통계처럼 상대가 필요한 행의 상대다. users를 참조하는 테이블이
     * 늘면 여기에 한 줄을 더한다 — 병합·탈퇴 테스트가 모두 이 시드를 쓴다.
     */
    public void seedEverything(long user, long other) {
        Instant t0 = Instant.parse("2030-03-01T10:00:00Z");
        Instant t1 = Instant.parse("2030-03-01T11:00:00Z");
        LocalDate day = LocalDate.of(2030, 3, 1);
        seedDday(user, "d");
        seedAcquisition(user, "s");
        seedPushToken(user, "tok-" + user);
        seedPushSend(user, day);
        seedPushAssignment(user, "exp-" + user);
        seedStats("study_stats_daily", user, day, 10);
        seedStats("study_stats_weekly", user, day, 10);
        seedStats("study_stats_monthly", user, day, 10);
        seedRefreshToken(user);
        seedReport(user, other);
        seedReport(other, user);
        seedInquiry(user);
        seedUserNotification(user);
        long session = seedSession(user, t0, t1);
        seedStatusEvent(session);
        seedActiveSession(user, t1.plusSeconds(3600));
        long subject = seedSubject(user, "과목" + user, 0, false);
        seedTask(subject, "할일");
        seedSegment(session, subject, t0, t1);
        seedDailyCompletion(user, subject, day);
        long room = seedRoom(user);
        seedParticipation(room, user, false);
        seedRtcStat(room, user, other);
        seedRankingRecord(user, "board-" + user, day, 1);
        seedRankingBest(user, 1, "b", day);
    }
}
