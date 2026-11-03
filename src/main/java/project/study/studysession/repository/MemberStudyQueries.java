package project.study.studysession.repository;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 그룹 멤버 책상(BY-1124)이 읽는 확정 세션 집계 — 사람 집합 단위로 한 번씩 읽고, 사람마다 쿼리하지 않는다. 기준은 랭킹과 같다: 측정한 세션만
 * (ADR-0031), 순공 1분 미만 조각 제외(ADR-0009). 기록이 없는 사람은 결과 맵에 없다.
 */
@Repository
@RequiredArgsConstructor
public class MemberStudyQueries {

    /** 확정 세션 순공 합 — 오늘(today 하루)과 이번 주(monday~today). */
    public record FocusTotals(long todaySec, long weekSec) {}

    private final JdbcClient jdbc;

    /** stat_date가 monday~today인 조각만 읽는다 — (user_id, stat_date) 인덱스 범위다. */
    public Map<Long, FocusTotals> focusTotals(Collection<Long> userIds, LocalDate monday, LocalDate today) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FocusTotals> totals = new HashMap<>();
        jdbc.sql("""
                        SELECT s.user_id,
                               COALESCE(SUM(s.focus_sec) FILTER (WHERE s.stat_date = :today), 0) AS today_sec,
                               SUM(s.focus_sec) AS week_sec
                        FROM study_session s
                        WHERE s.user_id IN (:userIds)
                          AND s.stat_date BETWEEN :monday AND :today
                          AND s.source = 'MEASURED'
                          AND s.focus_sec >= :minFocusSec
                        GROUP BY s.user_id""")
                .param("userIds", userIds)
                .param("monday", monday)
                .param("today", today)
                .param("minFocusSec", MIN_LIST_FOCUS_SEC)
                .query(rs -> {
                    totals.put(rs.getLong("user_id"), new FocusTotals(rs.getLong("today_sec"), rs.getLong("week_sec")));
                });
        return totals;
    }

    /**
     * 1분 이상 측정 세션이 마지막으로 끝난 때. 기록 탭에 보이는 기준(1분)과 맞추고, 수동 기록은 앱에서 공부한 때가 아니라 뺀다. 끝 시각이 비어
     * 있는 옛 행은 시작 시각으로 본다.
     */
    public Map<Long, Instant> lastMeasuredEndedAt(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Instant> lastEnded = new HashMap<>();
        jdbc.sql("""
                        SELECT s.user_id, MAX(COALESCE(s.ended_at, s.started_at)) AS last_at
                        FROM study_session s
                        WHERE s.user_id IN (:userIds)
                          AND s.source = 'MEASURED'
                          AND s.focus_sec >= :minFocusSec
                        GROUP BY s.user_id""")
                .param("userIds", userIds)
                .param("minFocusSec", MIN_LIST_FOCUS_SEC)
                .query(rs -> {
                    OffsetDateTime lastAt = rs.getObject("last_at", OffsetDateTime.class);
                    if (lastAt != null) {
                        lastEnded.put(rs.getLong("user_id"), lastAt.toInstant());
                    }
                });
        return lastEnded;
    }
}
