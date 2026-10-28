package project.study.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.ExperimentFact;

/**
 * 푸시 실험의 배정·발송 기록 (BY-1070, ADR-0037). 대상 판정은 사용자·세션 테이블을 테이블명으로만 읽는다 — 엔티티를 참조하지
 * 않는 지표 쿼리(SessionFactQueries)와 같은 방식이다.
 */
@Repository
@RequiredArgsConstructor
public class PushExperimentQueries {

    private final JdbcClient jdbc;

    /**
     * 가입 시각이 {@code [from, to)}이고, 측정 세션도 진행 중인 세션도 없으며, 알림 권한이 켜진 토큰이 있는 활성 사용자.
     * 수동 기록은 첫 세션으로 치지 않는다.
     */
    public List<Long> findFirstSessionCandidates(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT u.id FROM users u
                        WHERE u.created_at >= :from AND u.created_at < :to
                          AND u.status = 'ACTIVE'
                          AND NOT EXISTS (SELECT 1 FROM study_session s
                                          WHERE s.user_id = u.id AND s.source = 'MEASURED')
                          AND NOT EXISTS (SELECT 1 FROM active_study_session a WHERE a.user_id = u.id)
                          AND EXISTS (SELECT 1 FROM push_token t
                                      WHERE t.user_id = u.id AND t.notifications_enabled)
                        ORDER BY u.id""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .list();
    }

    /** 처음이면 {@code arm}으로 배정하고, 이미 배정됐으면 그 집단을 돌려준다. 집단은 바뀌지 않는다. */
    public String assign(String experiment, long userId, String arm, Instant at) {
        jdbc.sql("""
                        INSERT INTO push_experiment_assignment (experiment, user_id, arm, assigned_at)
                        VALUES (:experiment, :userId, :arm, :at)
                        ON CONFLICT (experiment, user_id) DO NOTHING""")
                .param("experiment", experiment)
                .param("userId", userId)
                .param("arm", arm)
                .param("at", Timestamp.from(at))
                .update();
        return jdbc.sql(
                        "SELECT arm FROM push_experiment_assignment WHERE experiment = :experiment AND user_id = :userId")
                .param("experiment", experiment)
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    /**
     * 첫 세션 칸이 빈 배정자에게 첫 측정 세션 묶음을 채운다. 묶음은 자정 분할 조각들이 공유하는 submission_started_at(옛 행은
     * started_at)이고, 순공은 조각 합이다 — 분할이 마지막 조각에 나머지를 몰아 합이 제출값과 같다. 한 번 채우면 바꾸지 않는다.
     */
    public int fillFirstSessions(String experiment) {
        return jdbc.sql("""
                        WITH pieces AS (
                            SELECT s.user_id, s.id, s.started_at,
                                   coalesce(s.submission_started_at, s.started_at) AS group_key,
                                   coalesce(s.focus_sec, 0) AS focus_sec
                            FROM study_session s
                            JOIN push_experiment_assignment a
                              ON a.user_id = s.user_id AND a.experiment = :experiment AND a.first_session_id IS NULL
                            WHERE s.source = 'MEASURED'
                        ),
                        first_group AS (
                            SELECT p.user_id, p.group_key, sum(p.focus_sec) AS focus_sec,
                                   (array_agg(p.id ORDER BY p.started_at))[1] AS first_id
                            FROM pieces p
                            WHERE p.group_key = (SELECT min(q.group_key) FROM pieces q WHERE q.user_id = p.user_id)
                            GROUP BY p.user_id, p.group_key
                        )
                        UPDATE push_experiment_assignment a
                        SET first_session_id = g.first_id, first_session_at = g.group_key, first_focus_sec = g.focus_sec
                        FROM first_group g
                        WHERE a.experiment = :experiment AND a.user_id = g.user_id AND a.first_session_id IS NULL""").param("experiment", experiment).update();
    }

    /** 그날(KST) 발송 자리를 잡는다. 이미 그날 보낸 기록이 있으면(다른 태스크 포함) false — 보내지 않는다. */
    public boolean reserveSend(UUID pushId, long userId, String type, LocalDate sendDate, Instant at) {
        return jdbc.sql("""
                                INSERT INTO push_send (push_id, user_id, type, send_date, sent_at)
                                VALUES (:pushId, :userId, :type, :sendDate, :at)
                                ON CONFLICT (user_id, send_date) DO NOTHING""")
                        .param("pushId", pushId)
                        .param("userId", userId)
                        .param("type", type)
                        .param("sendDate", sendDate)
                        .param("at", Timestamp.from(at))
                        .update()
                == 1;
    }

    public void recordAccepted(UUID pushId, int accepted) {
        jdbc.sql("UPDATE push_send SET fcm_accepted = :accepted WHERE push_id = :pushId")
                .param("accepted", accepted)
                .param("pushId", pushId)
                .update();
    }

    /**
     * 가입 7일 뒤 그날(KST) 세션을 시작했는지(Bounded D7)를 한 번 채운다. 근거는 일일 적재된 Amplitude
     * `study_session_started`다. 늦게 도착한 이벤트까지 들어오도록 그날과 다음 날의 수신 창이 모두 처리된 뒤에 채운다. 적재 테이블은
     * 테이블명으로만 읽는다.
     */
    public int fillDaySeven(String experiment) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT a.user_id, (u.created_at AT TIME ZONE 'Asia/Seoul')::date + 7 AS day7
                            FROM push_experiment_assignment a
                            JOIN users u ON u.id = a.user_id
                            WHERE a.experiment = :experiment AND a.day7_returned IS NULL
                        )
                        UPDATE push_experiment_assignment a
                        SET day7_returned = EXISTS (
                            SELECT 1 FROM amplitude_daily_event e
                            WHERE e.user_id = d.user_id AND e.event_date = d.day7
                              AND e.event_key = 'study_session_started' AND e.count > 0)
                        FROM due d
                        WHERE a.experiment = :experiment AND a.user_id = d.user_id
                          AND EXISTS (SELECT 1 FROM amplitude_export_window w WHERE w.window_date = d.day7)
                          AND EXISTS (SELECT 1 FROM amplitude_export_window w WHERE w.window_date = d.day7 + 1)""").param("experiment", experiment).update();
    }

    /** 일일 리포트용 배정 사실 — 지금 토큰이 남아 있는지, 그중 알림 권한이 켜진 것이 있는지도 함께. */
    public List<ExperimentFact> findFacts(String experiment) {
        return jdbc.sql("""
                        SELECT a.user_id, a.arm, a.assigned_at, a.first_session_at, a.first_focus_sec, a.day7_returned,
                               EXISTS (SELECT 1 FROM push_token t WHERE t.user_id = a.user_id) AS has_token,
                               EXISTS (SELECT 1 FROM push_token t
                                       WHERE t.user_id = a.user_id AND t.notifications_enabled) AS notifications_enabled
                        FROM push_experiment_assignment a
                        WHERE a.experiment = :experiment
                        ORDER BY a.user_id""")
                .param("experiment", experiment)
                .query((rs, rowNum) -> new ExperimentFact(
                        rs.getLong("user_id"),
                        rs.getString("arm"),
                        rs.getObject("assigned_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("first_session_at", OffsetDateTime.class)),
                        rs.getObject("first_focus_sec", Integer.class),
                        rs.getObject("day7_returned", Boolean.class),
                        rs.getBoolean("has_token"),
                        rs.getBoolean("notifications_enabled")))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
