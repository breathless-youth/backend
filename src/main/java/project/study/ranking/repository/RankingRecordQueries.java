package project.study.ranking.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.ranking.RankingBoardType;
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
}
