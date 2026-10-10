package project.study.studysession.repository;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;
import static project.study.studysession.StudySessionThresholds.MIN_STREAK_FOCUS_SEC;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;

/**
 * 랭킹 집계 전용 읽기 쿼리 (BY-828) — 전체 사용자의 기간·구간·누적 값을 사용자별 한 줄로 낸다.
 * userId를 주면 그 사용자 한 줄만 계산한다(내 값은 캐시와 상관없이 매번 새로 읽는다). 탈퇴(DELETE) 사용자는 뺀다.
 * 기준은 다른 화면과 같다 — 순공 1분 미만 조각 제외(ADR-0009), 연속 일수는 세션 10분 기준.
 */
@Repository
@RequiredArgsConstructor
public class StudySessionRankingQueries {

    private static final String NOT_WITHDRAWN = "u.status IS DISTINCT FROM 'DELETE'";

    private static final RowMapper<RankingTotalRow> TOTAL_ROW = (rs, i) -> new RankingTotalRow(
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getLong("focus_sec"),
            rs.getLong("study_sec"),
            instant(rs, "achieved_at"));

    private final JdbcClient jdbc;

    /** 기간(stat_date) 순공·총공부 합. 도달 시각은 그 값을 만든 마지막 조각의 종료 시각이다. */
    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        String sql = """
                SELECT s.user_id, u.nickname,
                       SUM(s.focus_sec) AS focus_sec,
                       COALESCE(SUM(s.study_sec), 0) AS study_sec,
                       COALESCE(MAX(s.ended_at), MAX(s.started_at)) AS achieved_at
                FROM study_session s
                JOIN users u ON u.id = s.user_id
                WHERE s.stat_date BETWEEN :from AND :to
                  AND s.focus_sec >= :minFocusSec
                  AND %s%s
                GROUP BY s.user_id, u.nickname""".formatted(NOT_WITHDRAWN, userFilter(userId));
        return jdbc.sql(sql)
                .paramSource(range(userId, from, to))
                .query(TOTAL_ROW)
                .list();
    }

    /** 시간대 구간 순공 합 — 구간 행은 조각 단위라 조각의 1분 기준을 그대로 건다. */
    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        String sql = """
                SELECT s.user_id, u.nickname,
                       SUM(sl.focus_sec) AS focus_sec,
                       0 AS study_sec,
                       COALESCE(MAX(s.ended_at), MAX(s.started_at)) AS achieved_at
                FROM study_session_slot sl
                JOIN study_session s ON s.id = sl.session_id
                JOIN users u ON u.id = s.user_id
                WHERE sl.slot = :slot
                  AND sl.slot_date BETWEEN :from AND :to
                  AND s.focus_sec >= :minFocusSec
                  AND %s%s
                GROUP BY s.user_id, u.nickname""".formatted(NOT_WITHDRAWN, userFilter(userId));
        return jdbc.sql(sql)
                .paramSource(range(userId, from, to).addValue("slot", slot.name()))
                .query(TOTAL_ROW)
                .list();
    }

    /** 누적 공부일 — 순공 1분 이상 조각이 있는 날 수(오늘까지). 누적 공부일 API와 같은 숫자다. */
    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        String sql = """
                WITH days AS (
                    SELECT s.user_id, s.stat_date, COALESCE(MIN(s.ended_at), MIN(s.started_at)) AS first_done
                    FROM study_session s
                    WHERE s.focus_sec >= :minFocusSec AND s.stat_date <= :today%s
                    GROUP BY s.user_id, s.stat_date
                )
                SELECT d.user_id, u.nickname, COUNT(*) AS days,
                       (ARRAY_AGG(d.first_done ORDER BY d.stat_date DESC))[1] AS achieved_at
                FROM days d
                JOIN users u ON u.id = d.user_id
                WHERE %s
                GROUP BY d.user_id, u.nickname""".formatted(userFilter(userId), NOT_WITHDRAWN);
        MapSqlParameterSource params =
                params(userId).addValue("today", today).addValue("minFocusSec", MIN_LIST_FOCUS_SEC);
        return jdbc.sql(sql)
                .paramSource(params)
                .query((rs, i) -> new RankingDaysRow(
                        rs.getLong("user_id"), rs.getString("nickname"), rs.getInt("days"), instant(rs, "achieved_at")))
                .list();
    }

    /**
     * 최장 연속 공부일 — 세션 10분 기준 날짜를 연속 구간으로 묶어(gaps-and-islands) 가장 긴 구간을, 같으면 먼저 끝난 구간을
     * 고른다. 스트릭 API의 maxStreak와 같은 숫자다.
     */
    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId) {
        String sql = """
                WITH days AS (
                    SELECT s.user_id, s.stat_date, COALESCE(MIN(s.ended_at), MIN(s.started_at)) AS first_done
                    FROM study_session s
                    WHERE s.focus_sec >= :minFocusSec AND s.stat_date <= :today%s
                    GROUP BY s.user_id, s.stat_date
                ), runs AS (
                    SELECT user_id, stat_date, first_done,
                           stat_date - CAST(ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY stat_date) AS INT) AS grp
                    FROM days
                ), islands AS (
                    SELECT user_id, grp, COUNT(*) AS len, MIN(stat_date) AS start_date, MAX(stat_date) AS end_date
                    FROM runs
                    GROUP BY user_id, grp
                ), best AS (
                    SELECT DISTINCT ON (user_id) user_id, len, start_date, end_date
                    FROM islands
                    ORDER BY user_id, len DESC, end_date ASC
                )
                SELECT b.user_id, u.nickname, b.len AS days, b.start_date, b.end_date, r.first_done AS achieved_at
                FROM best b
                JOIN runs r ON r.user_id = b.user_id AND r.stat_date = b.end_date
                JOIN users u ON u.id = b.user_id
                WHERE %s""".formatted(userFilter(userId), NOT_WITHDRAWN);
        MapSqlParameterSource params =
                params(userId).addValue("today", today).addValue("minFocusSec", MIN_STREAK_FOCUS_SEC);
        return jdbc.sql(sql)
                .paramSource(params)
                .query((rs, i) -> new RankingStreakRow(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getInt("days"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        instant(rs, "achieved_at")))
                .list();
    }

    /** 진행 중 조각만 있는 사용자의 닉네임 — 탈퇴자와 닉네임을 아직 안 정한 사용자는 빠진다. */
    public Map<Long, String> activeNicknames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT u.id, u.nickname FROM users u WHERE u.id IN (:ids) AND u.nickname IS NOT NULL AND "
                + NOT_WITHDRAWN;
        return jdbc
                .sql(sql)
                .paramSource(new MapSqlParameterSource("ids", userIds))
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("nickname")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String userFilter(Long userId) {
        return userId == null ? "" : " AND s.user_id = :userId";
    }

    private static MapSqlParameterSource params(Long userId) {
        return new MapSqlParameterSource("userId", userId);
    }

    private static MapSqlParameterSource range(Long userId, LocalDate from, LocalDate to) {
        return params(userId).addValue("from", from).addValue("to", to).addValue("minFocusSec", MIN_LIST_FOCUS_SEC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
