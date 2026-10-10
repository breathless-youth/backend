package project.study.ranking.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 마감 쓰기 (BY-828, ADR-0029) — 판·기간마다 한 번만 확정하는 마감 표시, TOP 3 메달, 참가자의 역대 최고 순위.
 * timestamptz는 OffsetDateTime(UTC)로 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class RankingCloseRepository {

    private static final String UPSERT_BEST = """
            INSERT INTO ranking_best (user_id, rank, board_key, period_start)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE
            SET rank = excluded.rank,
                board_key = excluded.board_key,
                period_start = excluded.period_start
            WHERE excluded.rank < ranking_best.rank""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    /** 마감을 표시한다. 같은 판·기간이 이미 있으면(다른 태스크가 먼저 확정) 아무것도 하지 않고 false. */
    public boolean insertClose(
            String boardKey, LocalDate periodStart, Instant closesAt, Instant closedAt, boolean skipped) {
        int inserted = jdbc.sql("""
                        INSERT INTO ranking_close (board_key, period_start, closes_at, closed_at, skipped)
                        VALUES (:boardKey, :periodStart, :closesAt, :closedAt, :skipped)
                        ON CONFLICT (board_key, period_start) DO NOTHING""")
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .param("closesAt", utc(closesAt))
                .param("closedAt", utc(closedAt))
                .param("skipped", skipped)
                .update();
        return inserted == 1;
    }

    public boolean isClosed(String boardKey, LocalDate periodStart) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM ranking_close WHERE board_key = :boardKey AND period_start = :periodStart)""")
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .query(Boolean.class)
                .single();
    }

    /** closesAt에 마감한 판 중 boardKeys에 든 것의 수 — 건너뛴 마감도 센다. */
    public int countClosedAt(Collection<String> boardKeys, Instant closesAt) {
        return jdbc.sql("SELECT count(*) FROM ranking_close WHERE board_key IN (:boardKeys) AND closes_at = :closesAt")
                .param("boardKeys", boardKeys)
                .param("closesAt", utc(closesAt))
                .query(Integer.class)
                .single();
    }

    public void insertRecord(
            long userId, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value) {
        jdbc.sql("""
                        INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                        VALUES (:userId, :boardKey, :periodStart, :closesAt, :rank, :value)""")
                .param("userId", userId)
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .param("closesAt", utc(closesAt))
                .param("rank", rank)
                .param("value", value)
                .update();
    }

    /** 참가자 전원의 개인 최고 순위를 갱신한다 — 더 높은 순위일 때만 바꾸고 같으면 먼저 것을 둔다. userIds는 순위 순이다. */
    public void upsertBest(String boardKey, LocalDate periodStart, List<Long> userIdsInRankOrder) {
        if (userIdsInRankOrder.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(userIdsInRankOrder.size());
        for (int i = 0; i < userIdsInRankOrder.size(); i++) {
            rows.add(new Object[] {userIdsInRankOrder.get(i), i + 1, boardKey, periodStart});
        }
        jdbcTemplate.batchUpdate(UPSERT_BEST, rows);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
