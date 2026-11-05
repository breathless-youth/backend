package project.study.group.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.common.category.StudyCategory;
import project.study.group.GroupJoinPolicy;
import project.study.group.GroupVisibility;
import project.study.group.service.GroupNames;

/**
 * study_group 테이블 (BY-1124). 네이티브 SQL + record — JPA 1차 캐시가 낡은 값을 돌려주지 않게 한다. 상태를 바꾸는 갱신은 바뀐 행
 * 수를 돌려주고, 호출자가 0건을 "그 사이 상황이 바뀜"으로 처리한다.
 *
 * <p>읽기는 모두 살아 있는(삭제 안 된) 그룹만 본다. 삭제된 그룹은 서비스에서 "없는 그룹"과 같다.
 */
@Repository
@RequiredArgsConstructor
public class GroupRepository {

    /**
     * 그룹 한 줄. {@code ownerId}는 살아 있는 그룹이면 늘 있다(V43 CHECK). 그룹장이 탈퇴한 지운 그룹은 {@code owner_id}가 NULL이라 0이다 — 지운
     * 그룹까지 읽는 {@link #findIncludingDeletedByIds}만 그런 행을 돌려준다(BY-1126).
     */
    public record GroupRow(
            long id,
            String name,
            String description,
            GroupVisibility visibility,
            GroupJoinPolicy joinPolicy,
            StudyCategory category,
            long ownerId,
            String inviteCode,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    /** 공개 목록 한 줄 — 이름·시험 목표와 지금 멤버 수만 읽는다. */
    public record PublicGroupRow(
            long id, String name, StudyCategory category, GroupJoinPolicy joinPolicy, int memberCount) {}

    private static final String COLUMNS =
            "id, name, description, visibility, join_policy, category, owner_id, invite_code, created_at, updated_at,"
                    + " deleted_at";

    private static final RowMapper<GroupRow> ROW = (rs, n) -> new GroupRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            GroupVisibility.valueOf(rs.getString("visibility")),
            GroupJoinPolicy.valueOf(rs.getString("join_policy")),
            StudyCategory.valueOf(rs.getString("category")),
            rs.getLong("owner_id"),
            rs.getString("invite_code"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"),
            Rows.instant(rs, "deleted_at"));

    private final JdbcClient jdbc;

    public long insert(
            String name,
            String description,
            GroupVisibility visibility,
            StudyCategory category,
            long ownerId,
            String inviteCode,
            Instant now) {
        return jdbc.sql("""
            INSERT INTO study_group
                (name, description, visibility, category, owner_id, invite_code, created_at, updated_at)
            VALUES (:name, :description, :visibility, :category, :ownerId, :inviteCode, :now, :now)
            RETURNING id""")
                .param("name", name)
                .param("description", description)
                .param("visibility", visibility.name())
                .param("category", category.name())
                .param("ownerId", ownerId)
                .param("inviteCode", inviteCode)
                .param("now", Rows.ts(now))
                .query(Long.class)
                .single();
    }

    public Optional<GroupRow> findLive(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /**
     * 그룹 행을 잠그고 읽는다 — 락 순서 ①. 한 작업에서 그룹 행은 하나만 잠근다. 계정 병합·탈퇴만 여러 그룹을 ID 순으로 잡는다({@link
     * #lockLiveInvolving}).
     */
    public Optional<GroupRow> lockLive(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /**
     * 이 사람과 얽힌 살아 있는 그룹(살아 있는 멤버·{@code PENDING} 신청·그룹장)을 ID 순으로 잠그고 그 ID를 돌려준다 — 계정 병합·탈퇴가 users 행보다
     * 먼저 잡는 락 순서 ①이다(BY-1126). 여러 그룹을 잡으므로 늘 ID 순으로 잡는다. 고르는 조건(멤버·신청)은 잠그지 않고 이 문장의 스냅샷으로 읽는다.
     * 대상은 사용자별 부분 인덱스({@code sgm_live_user_idx}·{@code sga_pending_user_idx}·{@code study_group_owner_idx})로 찾는다.
     *
     * <p>{@code FOR UPDATE}가 아니라 {@code FOR NO KEY UPDATE}다 — {@link #lockLive}({@code FOR UPDATE})·{@link #shareLive}({@code FOR
     * SHARE})와는 그대로 막히지만, 그룹을 가리키는 행(신고·알림 기록)을 넣는 FK 검사({@code FOR KEY SHARE})는 막지 않아 그 트랜잭션이 users 행
     * 잠금을 쥔 채 이 그룹 행을 기다리는 교착이 생기지 않는다.
     */
    public List<Long> lockLiveInvolving(long userId) {
        return jdbc.sql("""
            SELECT g.id FROM study_group g
             WHERE g.id IN (SELECT m.group_id FROM study_group_member m
                             WHERE m.user_id = :userId AND m.left_at IS NULL
                            UNION
                            SELECT a.group_id FROM study_group_application a
                             WHERE a.user_id = :userId AND a.status = 'PENDING'
                            UNION
                            SELECT o.id FROM study_group o
                             WHERE o.owner_id = :userId AND o.deleted_at IS NULL)
               AND g.deleted_at IS NULL
             ORDER BY g.id
               FOR NO KEY UPDATE OF g""").param("userId", userId).query(Long.class).list();
    }

    /**
     * 이 사람이 살아 있는 멤버이거나 그룹장인 살아 있는 그룹을 ID 순으로 잠그고 읽는다 — 탈퇴가 그룹마다 다음 그룹장을 정한다(BY-1126). 잠금 모드와
     * 그 이유는 {@link #lockLiveInvolving}과 같다({@code FOR NO KEY UPDATE}).
     */
    public List<GroupRow> lockLiveOfMemberOrOwner(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
             FROM study_group g
            WHERE g.id IN (SELECT m.group_id FROM study_group_member m
                            WHERE m.user_id = :userId AND m.left_at IS NULL
                           UNION
                           SELECT o.id FROM study_group o
                            WHERE o.owner_id = :userId AND o.deleted_at IS NULL)
              AND g.deleted_at IS NULL
            ORDER BY g.id
              FOR NO KEY UPDATE OF g""")
                .param("userId", userId)
                .query(ROW)
                .list();
    }

    /**
     * 그룹 행을 공유 락으로 읽는다 — 락 순서 ①의 읽기 쪽. 같은 그룹의 공부방 자리 잡기끼리는 서로 막지 않고, 내보내기·나가기·삭제({@link
     * #lockLive})와는 직렬화된다. 기다리는 사이 삭제됐으면 다시 확인한 조건에서 빠져 비어 있다.
     */
    public Optional<GroupRow> shareLive(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id = :id AND deleted_at IS NULL FOR SHARE")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /**
     * 살아 있는 공개 그룹 전부와 지금 멤버 수. 이름 검색·정렬은 호출자가 메모리에서 한다 — 평균이 캐시에 있어 SQL로 정렬할 수 없고, 이름의
     * {@code LIKE}는 {@code %}·{@code _} 이스케이프와 대소문자 규칙이 얽힌다.
     */
    public List<PublicGroupRow> findPublicLive() {
        return jdbc.sql("""
            SELECT g.id, g.name, g.category, g.join_policy, count(m.id) AS member_count
              FROM study_group g
              LEFT JOIN study_group_member m ON m.group_id = g.id AND m.left_at IS NULL
             WHERE g.visibility = 'PUBLIC' AND g.deleted_at IS NULL
             GROUP BY g.id, g.name, g.category""")
                .query((rs, n) -> new PublicGroupRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        StudyCategory.valueOf(rs.getString("category")),
                        GroupJoinPolicy.valueOf(rs.getString("join_policy")),
                        rs.getInt("member_count")))
                .list();
    }

    public List<GroupRow> findLiveByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id IN (:ids) AND deleted_at IS NULL ORDER BY id")
                .param("ids", ids)
                .query(ROW)
                .list();
    }

    /**
     * 삭제된 그룹까지 읽는다 — 이미 사라진 그룹에 남은 기한 지난 신청의 만료 푸시처럼 그룹 이름만 필요한 곳이 쓴다. 다른 읽기는 살아 있는
     * 그룹만 본다.
     */
    public List<GroupRow> findIncludingDeletedByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query(ROW)
                .list();
    }

    /** 이름·시험·목표·소개와 입장 방식을 바꾼다. 공개 여부는 바꾸지 않는다. */
    public int update(
            long id, String name, StudyCategory category, String description, GroupJoinPolicy joinPolicy, Instant now) {
        return jdbc.sql("""
            UPDATE study_group
               SET name = :name, category = :category, description = :description,
                   join_policy = :joinPolicy, updated_at = :now
             WHERE id = :id AND deleted_at IS NULL""")
                .param("name", name)
                .param("category", category.name())
                .param("description", description)
                .param("joinPolicy", joinPolicy.name())
                .param("now", Rows.ts(now))
                .param("id", id)
                .update();
    }

    /** 생성 트랜잭션에서 기본값 APPROVAL 대신 선택한 입장 방식을 저장한다. */
    public int updateJoinPolicy(long id, GroupJoinPolicy joinPolicy) {
        return jdbc.sql("UPDATE study_group SET join_policy = :joinPolicy WHERE id = :id AND deleted_at IS NULL")
                .param("joinPolicy", joinPolicy.name())
                .param("id", id)
                .update();
    }

    /**
     * 다른 살아 있는 공개 그룹이 이 이름을 쓰는지. 공백과 대소문자는 무시한다({@link GroupNames}). 이름을 바꾸는 그룹 자신은
     * {@code exceptGroupId}로 뺀다(새로 만들 때는 0).
     */
    public boolean existsLivePublicNamed(String name, long exceptGroupId) {
        return jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM study_group
                 WHERE visibility = 'PUBLIC' AND deleted_at IS NULL AND id <> :exceptId
                   AND %s = %s)""".formatted(GroupNames.keySql("name"), GroupNames.keySql(":name")))
                .param("name", name)
                .param("exceptId", exceptGroupId)
                .query(Boolean.class)
                .single();
    }

    /** 이 그룹의 지금 이름과 {@code name}이 같은 이름인지(공백·대소문자 무시). 이름을 안 바꾸는 수정을 가려내는 데 쓴다. */
    public boolean hasSameNameKey(long groupId, String name) {
        return jdbc.sql("SELECT %s = %s FROM study_group WHERE id = :id"
                        .formatted(GroupNames.keySql("name"), GroupNames.keySql(":name")))
                .param("name", name)
                .param("id", groupId)
                .query(Boolean.class)
                .single();
    }

    /** 코드는 {@link project.study.group.InviteCodes#canonical}로 맞춘 값을 준다. */
    public Optional<GroupRow> findLiveByInviteCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE invite_code = :code AND deleted_at IS NULL")
                .param("code", code)
                .query(ROW)
                .optional();
    }

    /** 살아 있는 그룹이 이 코드를 쓰고 있는가 (삭제된 그룹의 코드는 다시 쓸 수 있다). */
    public boolean inviteCodeTaken(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM study_group WHERE invite_code = :code AND deleted_at IS NULL)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    /** 비공개 그룹의 코드를 바꾼다. 공개 그룹은 CHECK 제약이 코드를 막는다. */
    public int updateInviteCode(long id, String code, Instant now) {
        return jdbc.sql("""
            UPDATE study_group SET invite_code = :code, updated_at = :now
             WHERE id = :id AND deleted_at IS NULL""")
                .param("code", code)
                .param("now", Rows.ts(now))
                .param("id", id)
                .update();
    }

    public int updateOwner(long id, long ownerId, Instant now) {
        return jdbc.sql("""
            UPDATE study_group SET owner_id = :ownerId, updated_at = :now
             WHERE id = :id AND deleted_at IS NULL""")
                .param("ownerId", ownerId)
                .param("now", Rows.ts(now))
                .param("id", id)
                .update();
    }

    /** 삭제 표시. 초대코드는 비워 다른 그룹이 다시 쓸 수 있게 한다. */
    public int softDelete(long id, Instant now) {
        return jdbc.sql("""
            UPDATE study_group SET deleted_at = :now, updated_at = :now, invite_code = NULL
             WHERE id = :id AND deleted_at IS NULL""").param("now", Rows.ts(now)).param("id", id).update();
    }

    /** 이 사용자가 그룹장인 살아 있는 그룹 수. */
    public int countOwnedLive(long userId) {
        return jdbc.sql("SELECT count(*) FROM study_group WHERE owner_id = :userId AND deleted_at IS NULL")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }
}
