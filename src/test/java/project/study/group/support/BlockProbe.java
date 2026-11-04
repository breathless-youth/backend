package project.study.group.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 신고·차단 테스트 전용 DB 프로브 (BY-1124) — user_block·user_report 행을 SQL로 바로 만들고 읽는다. 서비스가 남긴 값과 비교한다. */
public class BlockProbe {

    public record ReportRow(
            long id,
            long reporterId,
            long targetUserId,
            String type,
            long groupId,
            Long applicationId,
            String reason,
            Instant createdAt) {}

    private static final RowMapper<ReportRow> REPORT = (rs, n) -> new ReportRow(
            rs.getLong("id"),
            rs.getLong("reporter_id"),
            rs.getLong("target_user_id"),
            rs.getString("type"),
            rs.getLong("group_id"),
            rs.getObject("application_id", Long.class),
            rs.getString("reason"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public BlockProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public void insertBlock(long blockerId, long blockedId, Instant at) {
        jdbc.sql("insert into user_block (blocker_id, blocked_id, created_at) values (:blocker, :blocked, :at)")
                .param("blocker", blockerId)
                .param("blocked", blockedId)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** 이 쌍(방향 있음)의 차단 시각 — 없으면 비어 있다. */
    public Optional<Instant> blockedAt(long blockerId, long blockedId) {
        return jdbc.sql("select created_at from user_block where blocker_id = :blocker and blocked_id = :blocked")
                .param("blocker", blockerId)
                .param("blocked", blockedId)
                .query((rs, n) -> rs.getTimestamp("created_at").toInstant())
                .optional();
    }

    /** 신고 행을 바로 만든다. 신청 ID는 신청 신고(APPLICATION)에만 넣는다. 신고 ID를 돌려준다. */
    public long insertReport(long reporterId, long targetUserId, String type, long groupId, Long applicationId) {
        return jdbc.sql("""
                        insert into user_report (reporter_id, target_user_id, type, group_id, application_id, created_at)
                        values (:reporter, :target, :type, :groupId, :applicationId, now()) returning id""")
                .param("reporter", reporterId)
                .param("target", targetUserId)
                .param("type", type)
                .param("groupId", groupId)
                .param("applicationId", applicationId, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    /** 이 사람이 받은 신고 전부, id 순. */
    public List<ReportRow> reportsAbout(long targetUserId) {
        return jdbc.sql("select * from user_report where target_user_id = :target order by id")
                .param("target", targetUserId)
                .query(REPORT)
                .list();
    }

    /** 이 사람이 낸 신고 전부, id 순. */
    public List<ReportRow> reportsBy(long reporterId) {
        return jdbc.sql("select * from user_report where reporter_id = :reporter order by id")
                .param("reporter", reporterId)
                .query(REPORT)
                .list();
    }
}
