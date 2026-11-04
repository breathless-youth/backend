package project.study.studysession.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.Kst;
import project.study.metrics.report.fact.SessionFact;

/** 일일 리포트 v2가 읽는 세션 사실 (BY-904). 쓰기 흐름 쿼리와 섞이지 않게 StudySessionRepository와 나눈다. */
@Repository
@RequiredArgsConstructor
public class SessionFactQueries {

    private final JdbcClient jdbc;

    // 리포트 날짜는 KST다. stat_date는 사용자 시간대 날짜라 시작 시각의 KST 날짜 범위로 읽는다(ADR-0041)
    // 소셜 = 세션 [started_at, ended_at]이 그 유저의 방 참여 [joined_at, left_at]와 조금이라도 겹침. left_at NULL은 진행 중
    // 참여라 now()까지로 본다(BY-626). joined_at NULL(확정 없는 예약)은 비교식이 NULL이라 빠진다. room 엔티티를 참조하지
    // 않도록 테이블명으로만 조인한다. status_event(session_id)에 인덱스가 있어 세션별 일시정지 수는 인덱스로 센다
    public List<SessionFact> findBetween(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT s.user_id, s.started_at, coalesce(s.focus_sec, 0) AS focus_sec,
                            (SELECT count(*) FROM status_event e
                             WHERE e.session_id = s.id AND e.status = 'PAUSE') AS pause_count,
                            EXISTS (
                                SELECT 1 FROM room_participations rp
                                WHERE rp.user_id = s.user_id
                                  AND rp.joined_at < s.ended_at
                                  AND s.started_at < coalesce(rp.left_at, now())
                            ) AS social
                        FROM study_session s
                        WHERE s.started_at >= :from AND s.started_at < :to AND s.source = 'MEASURED'
                        ORDER BY s.user_id, s.started_at""")
                .param("from", Timestamp.from(Kst.startOf(from)))
                .param("to", Timestamp.from(Kst.startOf(to.plusDays(1))))
                .query((rs, rowNum) -> new SessionFact(
                        rs.getLong("user_id"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("focus_sec"),
                        rs.getInt("pause_count"),
                        rs.getBoolean("social")))
                .list();
    }
}
