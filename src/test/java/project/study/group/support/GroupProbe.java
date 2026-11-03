package project.study.group.support;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.group.InviteCodes;

/**
 * 그룹 통합 테스트 전용 DB 프로브 — 사용자·그룹·멤버·신청 행을 SQL로 바로 만들고 읽는다 (BY-1124). 운영 코드를 거치지 않고 상황을 세우는
 * 도구라 서비스가 만드는 값과 비교하는 단언에도 쓴다.
 */
public class GroupProbe {

    public record GroupRow(
            long id,
            String name,
            String description,
            String visibility,
            String category,
            long ownerId,
            String inviteCode,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    public record MemberRow(
            long id,
            long groupId,
            long userId,
            String joinedVia,
            boolean notifyEnabled,
            Instant leftAt,
            String leaveReason) {}

    private static final RowMapper<GroupRow> GROUP = (rs, n) -> new GroupRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("visibility"),
            rs.getString("category"),
            rs.getLong("owner_id"),
            rs.getString("invite_code"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "deleted_at"));

    private static final RowMapper<MemberRow> MEMBER = (rs, n) -> new MemberRow(
            rs.getLong("id"),
            rs.getLong("group_id"),
            rs.getLong("user_id"),
            rs.getString("joined_via"),
            rs.getBoolean("notify_enabled"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public GroupProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public long insertUser() {
        return jdbc.sql("insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', :device, now(), now()) returning id")
                .param("device", UUID.randomUUID().toString())
                .query(Long.class)
                .single();
    }

    /**
     * 닉네임·사진 키가 있는 사용자. 닉네임은 유니크라 {@code 접두어-userId}로 만든다({@link #nickname}). 사진 키는 null이어도 된다.
     */
    public long insertUser(String nicknamePrefix, String profileImage) {
        long userId = insertUser();
        jdbc.sql("update users set nickname = :nickname, profile_image = :profileImage where id = :id")
                .param("nickname", nickname(nicknamePrefix, userId))
                .param("profileImage", profileImage)
                .param("id", userId)
                .update();
        return userId;
    }

    /** {@link #insertUser(String, String)}가 만든 닉네임. */
    public static String nickname(String prefix, long userId) {
        return prefix + "-" + userId;
    }

    public Optional<GroupRow> group(long groupId) {
        return jdbc.sql("select * from study_group where id = :id")
                .param("id", groupId)
                .query(GROUP)
                .optional();
    }

    /** 이 사용자가 그룹장인 그룹 전부(삭제된 것 포함), id 순. */
    public List<GroupRow> groupsOwnedBy(long userId) {
        return jdbc.sql("select * from study_group where owner_id = :userId order by id")
                .param("userId", userId)
                .query(GROUP)
                .list();
    }

    /** 이 그룹의 멤버 행 전부(나간 소속 포함), id 순. */
    public List<MemberRow> members(long groupId) {
        return jdbc.sql("select * from study_group_member where group_id = :groupId order by id")
                .param("groupId", groupId)
                .query(MEMBER)
                .list();
    }

    public int countLiveMembers(long groupId) {
        return jdbc.sql("select count(*) from study_group_member where group_id = :groupId and left_at is null")
                .param("groupId", groupId)
                .query(Integer.class)
                .single();
    }

    /** 그룹과 그룹장 멤버 행(CREATED)을 SQL로 만든다. PRIVATE이면 초대코드도 붙는다. */
    public long insertGroup(long ownerId, String visibility) {
        String code = "PRIVATE".equals(visibility) ? InviteCodes.generate(RANDOM) : null;
        Timestamp now = Timestamp.from(Instant.now());
        long groupId = jdbc.sql("""
                        insert into study_group (name, description, visibility, category, owner_id, invite_code,
                                                 created_at, updated_at)
                        values (:name, null, :visibility, 'ETC', :ownerId, :code, :now, :now) returning id""")
                .param("name", "그룹" + UUID.randomUUID().toString().substring(0, 8))
                .param("visibility", visibility)
                .param("ownerId", ownerId)
                .param("code", code)
                .param("now", now)
                .query(Long.class)
                .single();
        insertMember(groupId, ownerId, "CREATED");
        return groupId;
    }

    /** 다른 사람이 그룹장인 새 공개 그룹을 만들고 {@code userId}를 멤버로 넣는다. 그 그룹 ID를 돌려준다. */
    public long joinNewGroupOf(long userId) {
        long groupId = insertGroup(insertUser(), "PUBLIC");
        insertMember(groupId, userId, "APPLICATION");
        return groupId;
    }

    public void insertMember(long groupId, long userId, String joinedVia) {
        jdbc.sql("insert into study_group_member (group_id, user_id, joined_at, joined_via) "
                        + "values (:groupId, :userId, now(), :via)")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("via", joinedVia)
                .update();
    }

    /** 들어온 시각을 정한 소속 행 — "들어간 순서"가 그룹 ID 순서와 다르게 하려고 쓴다. */
    public void insertMemberAt(long groupId, long userId, String joinedVia, Instant joinedAt) {
        jdbc.sql("insert into study_group_member (group_id, user_id, joined_at, joined_via) "
                        + "values (:groupId, :userId, :joinedAt, :via)")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("joinedAt", Timestamp.from(joinedAt))
                .param("via", joinedVia)
                .update();
    }

    public void setNotify(long groupId, long userId, boolean enabled) {
        jdbc.sql("update study_group_member set notify_enabled = :enabled "
                        + "where group_id = :groupId and user_id = :userId and left_at is null")
                .param("enabled", enabled)
                .param("groupId", groupId)
                .param("userId", userId)
                .update();
    }

    /** 이 그룹에 {@code userId}가 낸 신청 행. 만든 시각은 기한 3일 전이다. */
    public void insertApplication(long groupId, long userId, String status, Instant expiresAt) {
        jdbc.sql("insert into study_group_application "
                        + "(group_id, user_id, introduction, status, created_at, expires_at) "
                        + "values (:groupId, :userId, '안녕하세요', :status, :createdAt, :expiresAt)")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("status", status)
                .param("createdAt", Timestamp.from(expiresAt.minusSeconds(3 * 24 * 3600)))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    /** 이미 나간(또는 내보내진) 소속 행. */
    public void insertLeftMember(long groupId, long userId, String leaveReason, Instant leftAt) {
        jdbc.sql("insert into study_group_member (group_id, user_id, joined_at, joined_via, left_at, leave_reason) "
                        + "values (:groupId, :userId, :joinedAt, 'APPLICATION', :leftAt, :reason)")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("joinedAt", Timestamp.from(leftAt.minusSeconds(3600)))
                .param("leftAt", Timestamp.from(leftAt))
                .param("reason", leaveReason)
                .update();
    }

    /** 다른 사람이 그룹장인 새 공개 그룹에 {@code userId}가 낸 신청 행. */
    public void insertApplicationToNewGroup(long userId, String status, Instant expiresAt) {
        long groupId = insertGroup(insertUser(), "PUBLIC");
        jdbc.sql("insert into study_group_application "
                        + "(group_id, user_id, introduction, status, created_at, expires_at) "
                        + "values (:groupId, :userId, '안녕하세요', :status, :createdAt, :expiresAt)")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("status", status)
                .param("createdAt", Timestamp.from(expiresAt.minusSeconds(3 * 24 * 3600)))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    /** 그룹을 지운 것으로 표시하고 살아 있는 멤버를 모두 내보낸 것으로(GROUP_DELETED) 닫는다. */
    public void softDeleteGroup(long groupId) {
        jdbc.sql("update study_group set deleted_at = now(), invite_code = null where id = :id")
                .param("id", groupId)
                .update();
        jdbc.sql("update study_group_member set left_at = now(), leave_reason = 'GROUP_DELETED' "
                        + "where group_id = :id and left_at is null")
                .param("id", groupId)
                .update();
    }

    public void leaveGroup(long groupId, long userId) {
        jdbc.sql("update study_group_member set left_at = now(), leave_reason = 'LEFT' "
                        + "where group_id = :groupId and user_id = :userId and left_at is null")
                .param("groupId", groupId)
                .param("userId", userId)
                .update();
    }
}
