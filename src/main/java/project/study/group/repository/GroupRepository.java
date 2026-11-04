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
import project.study.group.GroupVisibility;

/**
 * study_group 테이블 (BY-1124). 네이티브 SQL + record — JPA 1차 캐시가 낡은 값을 돌려주지 않게 한다. 상태를 바꾸는 갱신은 바뀐 행
 * 수를 돌려주고, 호출자가 0건을 "그 사이 상황이 바뀜"으로 처리한다.
 *
 * <p>읽기는 모두 살아 있는(삭제 안 된) 그룹만 본다. 삭제된 그룹은 서비스에서 "없는 그룹"과 같다.
 */
@Repository
@RequiredArgsConstructor
public class GroupRepository {

    public record GroupRow(
            long id,
            String name,
            String description,
            GroupVisibility visibility,
            StudyCategory category,
            long ownerId,
            String inviteCode,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    /** 공개 목록 한 줄 — 이름·시험 목표와 지금 멤버 수만 읽는다. */
    public record PublicGroupRow(long id, String name, StudyCategory category, int memberCount) {}

    private static final String COLUMNS =
            "id, name, description, visibility, category, owner_id, invite_code, created_at, updated_at, deleted_at";

    private static final RowMapper<GroupRow> ROW = (rs, n) -> new GroupRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            GroupVisibility.valueOf(rs.getString("visibility")),
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

    /** 그룹 행을 잠그고 읽는다 — 락 순서 ①. 한 작업에서 그룹 행은 하나만 잠근다. */
    public Optional<GroupRow> lockLive(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(ROW)
                .optional();
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
                        SELECT g.id, g.name, g.category, count(m.id) AS member_count
                          FROM study_group g
                          LEFT JOIN study_group_member m ON m.group_id = g.id AND m.left_at IS NULL
                         WHERE g.visibility = 'PUBLIC' AND g.deleted_at IS NULL
                         GROUP BY g.id, g.name, g.category""")
                .query((rs, n) -> new PublicGroupRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        StudyCategory.valueOf(rs.getString("category")),
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

    /** 이름·시험·목표·소개 전체 교체. 공개 여부는 바꾸지 않는다. */
    public int update(long id, String name, StudyCategory category, String description, Instant now) {
        return jdbc.sql("""
                        UPDATE study_group
                           SET name = :name, category = :category, description = :description, updated_at = :now
                         WHERE id = :id AND deleted_at IS NULL""")
                .param("name", name)
                .param("category", category.name())
                .param("description", description)
                .param("now", Rows.ts(now))
                .param("id", id)
                .update();
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
