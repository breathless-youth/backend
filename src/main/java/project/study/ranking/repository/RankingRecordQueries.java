package project.study.ranking.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.ranking.RankingBoardType;
import project.study.ranking.dto.MedalCounts;
import project.study.ranking.dto.RankingBestRow;
import project.study.ranking.dto.RankingRecordRow;
import project.study.ranking.dto.RecordCursor;

/** 마감 기록 읽기 (BY-828) — 모두 본인 기록만 읽는다. timestamptz는 OffsetDateTime(UTC)로 넘긴다. */
@Repository
@RequiredArgsConstructor
public class RankingRecordQueries {

    private static final String COLUMNS = "id, board_key, period_start, closes_at, rank, value";

    private static final RowMapper<RankingRecordRow> ROW = (rs, i) -> new RankingRecordRow(
            rs.getLong("id"),
            rs.getString("board_key"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("closes_at", OffsetDateTime.class).toInstant(),
            rs.getInt("rank"),
            rs.getBigDecimal("value"));

    private final JdbcClient jdbc;

    /** 내 기록 — (마감 시각, id) 내림차순. rank·type은 주면 거르고, after는 그 항목 다음부터. */
    public List<RankingRecordRow> page(
            long userId, Integer rank, RankingBoardType type, RecordCursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ranking_record WHERE user_id = :userId");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("limit", limit);
        if (rank != null) {
            sql.append(" AND rank = :rank");
            params.addValue("rank", rank);
        }
        if (type != null) {
            sql.append(" AND split_part(board_key, ':', 1) = :type");
            params.addValue("type", type.name());
        }
        if (after != null) {
            sql.append(" AND (closes_at, id) < (:afterClosesAt, :afterId)");
            params.addValue("afterClosesAt", after.closesAt().atOffset(ZoneOffset.UTC))
                    .addValue("afterId", after.id());
        }
        sql.append(" ORDER BY closes_at DESC, id DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).paramSource(params).query(ROW).list();
    }

    /** 안 본 기록 중 closedUpTo까지(포함) 마감한 것. 순서는 서비스가 정한다. */
    public List<RankingRecordRow> unseen(long userId, Instant closedUpTo) {
        return jdbc.sql(
                        "SELECT " + COLUMNS
                                + " FROM ranking_record WHERE user_id = :userId AND seen_at IS NULL AND closes_at <= :closedUpTo")
                .param("userId", userId)
                .param("closedUpTo", closedUpTo.atOffset(ZoneOffset.UTC))
                .query(ROW)
                .list();
    }

    public MedalCounts counts(long userId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE rank = 1) AS first_count,
                               count(*) FILTER (WHERE rank = 2) AS second_count,
                               count(*) FILTER (WHERE rank = 3) AS third_count
                        FROM ranking_record
                        WHERE user_id = :userId""")
                .param("userId", userId)
                .query((rs, i) -> new MedalCounts(
                        rs.getLong("first_count"), rs.getLong("second_count"), rs.getLong("third_count")))
                .single();
    }

    public Optional<RankingBestRow> best(long userId) {
        return jdbc.sql("SELECT rank, board_key, period_start FROM ranking_best WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, i) -> new RankingBestRow(
                        rs.getInt("rank"), rs.getString("board_key"), rs.getObject("period_start", LocalDate.class)))
                .optional();
    }

    /** 내 기록만 본 것으로 표시한다 — 남의 id·이미 본 id는 그대로 둔다. */
    public int markSeen(long userId, Collection<Long> ids, Instant seenAt) {
        return jdbc.sql("""
                        UPDATE ranking_record SET seen_at = :seenAt
                        WHERE user_id = :userId AND id IN (:ids) AND seen_at IS NULL""")
                .param("seenAt", seenAt.atOffset(ZoneOffset.UTC))
                .param("userId", userId)
                .param("ids", ids)
                .update();
    }
}
