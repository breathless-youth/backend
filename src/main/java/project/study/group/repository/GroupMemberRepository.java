package project.study.group.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * study_group_member 테이블 (BY-1124). 멤버 한 번의 소속이 한 행이고, 나가면 {@code left_at}을 채운다. 읽기는 기본적으로 살아 있는
 * 소속({@code left_at IS NULL})만 본다.
 */
@Repository
@RequiredArgsConstructor
public class GroupMemberRepository {

    /** {@code joined_via} 값 — 오타가 CHECK 제약 위반으로 늦게 드러나지 않게 이름을 붙여 둔다. */
    public static final String VIA_CREATED = "CREATED";

    public static final String VIA_APPLICATION = "APPLICATION";
    public static final String VIA_INVITE_LINK = "INVITE_LINK";
    public static final String VIA_INVITE_CODE = "INVITE_CODE";

    /** {@code leave_reason} 값 */
    public static final String LEAVE_LEFT = "LEFT";

    public static final String LEAVE_KICKED = "KICKED";
    public static final String LEAVE_GROUP_DELETED = "GROUP_DELETED";

    public record MemberRow(
            long id, long groupId, long userId, Instant joinedAt, String joinedVia, boolean notifyEnabled) {}

    private static final String COLUMNS = "id, group_id, user_id, joined_at, joined_via, notify_enabled";

    private static final RowMapper<MemberRow> ROW = (rs, n) -> new MemberRow(
            rs.getLong("id"),
            rs.getLong("group_id"),
            rs.getLong("user_id"),
            Rows.instant(rs, "joined_at"),
            rs.getString("joined_via"),
            rs.getBoolean("notify_enabled"));

    private final JdbcClient jdbc;

    /** 새 소속 행. 알림은 꺼진 채로 시작한다. */
    public void insert(long groupId, long userId, String joinedVia, Instant now) {
        jdbc.sql("""
                        INSERT INTO study_group_member (group_id, user_id, joined_at, joined_via)
                        VALUES (:groupId, :userId, :now, :joinedVia)""")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("now", Rows.ts(now))
                .param("joinedVia", joinedVia)
                .update();
    }

    public Optional<MemberRow> findLive(long groupId, long userId) {
        return jdbc.sql(
                        "SELECT " + COLUMNS
                                + " FROM study_group_member WHERE group_id = :groupId AND user_id = :userId AND left_at IS NULL")
                .param("groupId", groupId)
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    /** 여러 그룹의 살아 있는 멤버를 한 번에 — 그룹 안에서는 들어온 순({@code joined_at, id})이다. */
    public List<MemberRow> findLiveByGroups(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM study_group_member WHERE group_id IN (:groupIds) AND left_at IS NULL"
                        + " ORDER BY group_id, joined_at, id")
                .param("groupIds", groupIds)
                .query(ROW)
                .list();
    }

    public int countLive(long groupId) {
        return jdbc.sql("SELECT count(*) FROM study_group_member WHERE group_id = :groupId AND left_at IS NULL")
                .param("groupId", groupId)
                .query(Integer.class)
                .single();
    }

    /** 이 사용자가 지금 멤버로 있는 그룹 수 — "그룹+신청 5개" 한도의 앞쪽 절반이다. */
    public int countLiveGroupsOfUser(long userId) {
        return jdbc.sql("SELECT count(*) FROM study_group_member WHERE user_id = :userId AND left_at IS NULL")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    /** 이 사용자가 들어간 그룹 ID, 들어간 순서대로. */
    public List<Long> findLiveGroupIdsOfUser(long userId) {
        return jdbc.sql("""
                        SELECT group_id FROM study_group_member
                         WHERE user_id = :userId AND left_at IS NULL
                         ORDER BY joined_at, id""").param("userId", userId).query(Long.class).list();
    }

    /** 소속을 끝낸다. 이미 끝났으면 0건. */
    public int markLeft(long groupId, long userId, String leaveReason, Instant now) {
        return jdbc.sql("""
                        UPDATE study_group_member SET left_at = :now, leave_reason = :reason
                         WHERE group_id = :groupId AND user_id = :userId AND left_at IS NULL""")
                .param("now", Rows.ts(now))
                .param("reason", leaveReason)
                .param("groupId", groupId)
                .param("userId", userId)
                .update();
    }

    /** 멤버 공부 알림을 켜고 끈다. 같은 값이어도 행은 매칭되므로 1건이고, 멤버가 아니면 0건. */
    public int setNotify(long groupId, long userId, boolean enabled) {
        return jdbc.sql("""
                        UPDATE study_group_member SET notify_enabled = :enabled
                         WHERE group_id = :groupId AND user_id = :userId AND left_at IS NULL""")
                .param("enabled", enabled)
                .param("groupId", groupId)
                .param("userId", userId)
                .update();
    }

    /** {@code since} 이후에 이 그룹에서 내보내진 적이 있는가 (내보낸 뒤 3일 제한). */
    public boolean recentlyKicked(long groupId, long userId, Instant since) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM study_group_member
                             WHERE group_id = :groupId AND user_id = :userId
                               AND leave_reason = 'KICKED' AND left_at > :since)""")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("since", Rows.ts(since))
                .query(Boolean.class)
                .single();
    }
}
