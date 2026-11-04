package project.study.studysession.repository;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    /** 그룹은 오늘·이번 주를 KST 날짜로 센다(ADR-0041 결정 11). */
    private static final ZoneId GROUP_DAY_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;

    /**
     * 시작 시각이 KST monday 0시~today 다음 날 0시인 조각만 읽는다 — (user_id, started_at) 인덱스 범위다.
     *
     * <p>stat_date는 세션 시간대의 날짜라 해외에서 한 공부는 KST 날짜와 다를 수 있어 쓰지 않는다. 조각은 시작 시각의 KST 날짜에 넣는다.
     */
    public Map<Long, FocusTotals> focusTotals(Collection<Long> userIds, LocalDate monday, LocalDate today) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FocusTotals> totals = new HashMap<>();
        jdbc.sql("""
                        SELECT s.user_id,
                               COALESCE(SUM(s.focus_sec) FILTER (WHERE s.started_at >= :todayStart), 0) AS today_sec,
                               SUM(s.focus_sec) AS week_sec
                        FROM study_session s
                        WHERE s.user_id IN (:userIds)
                          AND s.started_at >= :weekStart
                          AND s.started_at < :tomorrowStart
                          AND s.source = 'MEASURED'
                          AND s.focus_sec >= :minFocusSec
                        GROUP BY s.user_id""")
                .param("userIds", userIds)
                .param("weekStart", startOf(monday))
                .param("todayStart", startOf(today))
                .param("tomorrowStart", startOf(today.plusDays(1)))
                .param("minFocusSec", MIN_LIST_FOCUS_SEC)
                .query(rs -> {
                    totals.put(rs.getLong("user_id"), new FocusTotals(rs.getLong("today_sec"), rs.getLong("week_sec")));
                });
        return totals;
    }

    /**
     * 1분 이상 측정 세션이 마지막으로 끝난 때. 기록 탭에 보이는 기준(1분)과 맞추고, 수동 기록은 앱에서 공부한 때가 아니라 뺀다. 끝 시각이 비어
     * 있는 옛 행은 시작 시각으로 본다.
     *
     * <p>FE가 홈·내 그룹을 30초마다 부르므로 사람마다 모든 측정 행을 훑지 않는다. {@code (user_id, started_at)} 인덱스로 시작이 가장 늦은 세션을
     * 찾고, 그보다 24시간 앞까지 시작한 세션 중 끝이 가장 늦은 것을 고른다. 두 기기로 겹쳐 공부하면 시작 순서와 끝 순서가 다를 수 있고, 세션은
     * 24시간을 넘지 않아 그보다 먼저 시작한 세션이 더 늦게 끝날 수는 없다 — {@code MAX(끝 시각)}과 결과가 같다.
     * {@code stat_date}는 세션마다 시간대가 달라 시각 순서와 어긋날 수 있어 쓰지 않는다.
     */
    public Map<Long, Instant> lastMeasuredEndedAt(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Instant> lastEnded = new HashMap<>();
        jdbc.sql("""
                        SELECT u.id AS user_id, last.last_at
                        FROM unnest(CAST(:userIds AS bigint[])) AS u(id)
                        CROSS JOIN LATERAL (
                            SELECT MAX(COALESCE(s.ended_at, s.started_at)) AS last_at
                            FROM study_session s
                            WHERE s.user_id = u.id
                              AND s.source = 'MEASURED'
                              AND s.focus_sec >= :minFocusSec
                              AND s.started_at >= (
                                  SELECT latest.started_at - INTERVAL '24 hours'
                                  FROM study_session latest
                                  WHERE latest.user_id = u.id
                                    AND latest.source = 'MEASURED'
                                    AND latest.focus_sec >= :minFocusSec
                                  ORDER BY latest.started_at DESC
                                  LIMIT 1)) last""")
                .param("userIds", userIds.toArray(Long[]::new))
                .param("minFocusSec", MIN_LIST_FOCUS_SEC)
                .query(rs -> {
                    OffsetDateTime lastAt = rs.getObject("last_at", OffsetDateTime.class);
                    if (lastAt != null) {
                        lastEnded.put(rs.getLong("user_id"), lastAt.toInstant());
                    }
                });
        return lastEnded;
    }

    private static OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(GROUP_DAY_ZONE).toInstant().atOffset(ZoneOffset.UTC);
    }
}
