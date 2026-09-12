package project.study.studysession.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행중 세션 스냅샷 벌크 UPSERT (BY-470) — 버퍼가 코얼레싱한 최신본들을 한 배치(한 왕복)로 쓴다.
 *
 * <p>SQL은 {@link ActiveStudySessionRepository#upsertSnapshot}와 동일하다 — 동시 첫 스냅샷
 * 레이스(ON CONFLICT)와 역순 도착 가드(WHERE reported_at &lt; excluded)를 그대로 유지한다.
 * 버퍼가 이미 세션당 최신본만 남기지만, 여러 앱 인스턴스가 동시에 flush해도 DB에서 순서가 보장된다.
 */
@Repository
public class ActiveStudySessionBatchRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO active_study_session
                (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec, events)
            VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            ON CONFLICT (user_id, started_at) DO UPDATE
            SET reported_at = excluded.reported_at,
                last_seen_at = excluded.last_seen_at,
                study_sec = excluded.study_sec,
                focus_sec = excluded.focus_sec,
                events = excluded.events
            WHERE active_study_session.reported_at < excluded.reported_at""";

    private final JdbcTemplate jdbcTemplate;

    public ActiveStudySessionBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 여러 스냅샷을 한 배치로 UPSERT. 빈 리스트면 아무것도 안 한다. */
    @Transactional
    public void bulkUpsert(List<SnapshotRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SnapshotRow r = rows.get(i);
                ps.setLong(1, r.userId());
                // TIMESTAMPTZ 컬럼 — OffsetDateTime(UTC)로 넘겨 PG 드라이버가 정확히 매핑하게 한다
                ps.setObject(2, r.startedAt().atOffset(ZoneOffset.UTC));
                ps.setObject(3, r.reportedAt().atOffset(ZoneOffset.UTC));
                ps.setObject(4, r.lastSeenAt().atOffset(ZoneOffset.UTC));
                ps.setInt(5, r.studySec());
                ps.setInt(6, r.focusSec());
                ps.setString(7, r.eventsJson());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /** 벌크 UPSERT 한 행. events는 이미 JSON 문자열로 직렬화된 상태다. */
    public record SnapshotRow(
            Long userId,
            Instant startedAt,
            Instant reportedAt,
            Instant lastSeenAt,
            int studySec,
            int focusSec,
            String eventsJson) {}
}
