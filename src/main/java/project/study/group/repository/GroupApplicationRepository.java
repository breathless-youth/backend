package project.study.group.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.common.category.StudyCategory;

/**
 * study_group_application 테이블 (BY-1124). 상태를 바꾸는 갱신은 바뀐 행 수를 돌려주고, 호출자가 0건을 "그 사이 상황이 바뀜"으로
 * 처리한다.
 *
 * <p>"답을 기다리는 신청"은 {@code status = 'PENDING' AND expires_at > now}다 — 만료 작업이 아직 안 돌았어도 기한이 지난 신청은 세지
 * 않고 목록에도 싣지 않는다. 기한이 지난 {@code PENDING} 행이 남아 있으면 같은 쌍의 부분 unique({@code sga_pending_uidx})가 새 신청을
 * 막으므로, 새 신청 전에 {@link #expireStale}로 그 행을 먼저 닫는다.
 */
@Repository
@RequiredArgsConstructor
public class GroupApplicationRepository {

    /** {@code status} 값 — 오타가 CHECK 제약 위반으로 늦게 드러나지 않게 이름을 붙여 둔다. */
    public static final String PENDING = "PENDING";

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String CANCELED = "CANCELED";
    public static final String EXPIRED = "EXPIRED";
    public static final String GROUP_DELETED = "GROUP_DELETED";
    public static final String JOIN_POLICY_OPEN = "JOIN_POLICY_OPEN";

    public record ApplicationRow(
            long id,
            long groupId,
            long userId,
            String introduction,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant answeredAt) {}

    /** 만료 작업이 방금 EXPIRED로 바꾼 행 — 푸시를 보낼 사람과 그룹. */
    public record ExpiredRow(long id, long groupId, long userId) {}

    /** 그룹장용 목록 한 줄 — 신청한 사람의 닉네임·사진 키를 함께 읽는다(사진은 저장된 키라 응답 경로로 바꿔 써야 한다). */
    public record ApplicantRow(
            long applicationId,
            long userId,
            String nickname,
            String profileImage,
            String introduction,
            Instant createdAt,
            Instant expiresAt) {}

    /** 내 신청 목록 한 줄 — 신청한 그룹의 이름·시험 목표를 함께 읽는다. */
    public record MyApplicationRow(
            long applicationId,
            long groupId,
            String groupName,
            StudyCategory category,
            Instant createdAt,
            Instant expiresAt) {}

    private static final String COLUMNS =
            "id, group_id, user_id, introduction, status, created_at, expires_at, answered_at";

    private static final RowMapper<ApplicationRow> ROW = (rs, n) -> new ApplicationRow(
            rs.getLong("id"),
            rs.getLong("group_id"),
            rs.getLong("user_id"),
            rs.getString("introduction"),
            rs.getString("status"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "expires_at"),
            Rows.instant(rs, "answered_at"));

    private final JdbcClient jdbc;

    /** 답을 기다리는 새 신청. 같은 쌍에 기한 안이든 지났든 {@code PENDING}이 남아 있으면 부분 unique가 막는다. */
    public long insert(long groupId, long userId, String introduction, Instant now, Instant expiresAt) {
        return jdbc.sql("""
            INSERT INTO study_group_application
                (group_id, user_id, introduction, status, created_at, expires_at)
            VALUES (:groupId, :userId, :introduction, 'PENDING', :now, :expiresAt)
            RETURNING id""")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("introduction", introduction)
                .param("now", Rows.ts(now))
                .param("expiresAt", Rows.ts(expiresAt))
                .query(Long.class)
                .single();
    }

    public Optional<ApplicationRow> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group_application WHERE id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** 신청 행을 잠그고 읽는다 — 같은 신청에 동시에 답하거나 취소해도 한쪽만 이긴다. 그룹 행·사용자 락 뒤에 잡는다. */
    public Optional<ApplicationRow> lock(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM study_group_application WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** 이 사람이 이 그룹에 낸, 기한 안에서 답을 기다리는 신청. */
    public Optional<ApplicationRow> findPending(long groupId, long userId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
             FROM study_group_application
            WHERE group_id = :groupId AND user_id = :userId AND status = 'PENDING' AND expires_at > :now""")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("now", Rows.ts(now))
                .query(ROW)
                .optional();
    }

    /** 이 사용자가 답을 기다리는 신청 수 — "그룹+신청 5개"와 "신청 3개" 한도가 쓴다. */
    public int countPendingOfUser(long userId, Instant now) {
        return jdbc.sql("""
            SELECT count(*) FROM study_group_application
             WHERE user_id = :userId AND status = 'PENDING' AND expires_at > :now""")
                .param("userId", userId)
                .param("now", Rows.ts(now))
                .query(Integer.class)
                .single();
    }

    /** 이 그룹에 답을 기다리는 신청 수 — 그룹 홈의 새 신청 띠가 쓴다. */
    public int countPendingOfGroup(long groupId, Instant now) {
        return jdbc.sql("""
            SELECT count(*) FROM study_group_application
             WHERE group_id = :groupId AND status = 'PENDING' AND expires_at > :now""")
                .param("groupId", groupId)
                .param("now", Rows.ts(now))
                .query(Integer.class)
                .single();
    }

    /** 여러 그룹의 답을 기다리는 신청 수 — 내 그룹 카드가 쓴다. 신청이 없는 그룹은 결과에 없다. */
    public Map<Long, Integer> countPendingOfGroups(Collection<Long> groupIds, Instant now) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.sql("""
            SELECT group_id, count(*) AS pending FROM study_group_application
             WHERE group_id IN (:groupIds) AND status = 'PENDING' AND expires_at > :now
             GROUP BY group_id""").param("groupIds", groupIds).param("now", Rows.ts(now)).query(rs -> {
            counts.put(rs.getLong("group_id"), rs.getInt("pending"));
        });
        return counts;
    }

    /** 그룹장용 목록 — 이 그룹에서 기한 안에서 답을 기다리는 신청을 신청한 순서(오래된 것부터)로. */
    public List<ApplicantRow> findPendingOfGroup(long groupId, Instant now) {
        return jdbc.sql("""
            SELECT a.id, a.user_id, u.nickname, u.profile_image, a.introduction, a.created_at, a.expires_at
              FROM study_group_application a
              JOIN users u ON u.id = a.user_id
             WHERE a.group_id = :groupId AND a.status = 'PENDING' AND a.expires_at > :now
             ORDER BY a.created_at, a.id""")
                .param("groupId", groupId)
                .param("now", Rows.ts(now))
                .query((rs, n) -> new ApplicantRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("profile_image"),
                        rs.getString("introduction"),
                        Rows.instant(rs, "created_at"),
                        Rows.instant(rs, "expires_at")))
                .list();
    }

    /** 내 신청 목록 — 기한 안에서 답을 기다리는 것을 신청한 순서(오래된 것부터)로. 사라진 그룹의 신청은 싣지 않는다. */
    public List<MyApplicationRow> findPendingOfUser(long userId, Instant now) {
        return jdbc.sql("""
            SELECT a.id, a.group_id, g.name, g.category, a.created_at, a.expires_at
              FROM study_group_application a
              JOIN study_group g ON g.id = a.group_id AND g.deleted_at IS NULL
             WHERE a.user_id = :userId AND a.status = 'PENDING' AND a.expires_at > :now
             ORDER BY a.created_at, a.id""")
                .param("userId", userId)
                .param("now", Rows.ts(now))
                .query((rs, n) -> new MyApplicationRow(
                        rs.getLong("id"),
                        rs.getLong("group_id"),
                        rs.getString("name"),
                        StudyCategory.valueOf(rs.getString("category")),
                        Rows.instant(rs, "created_at"),
                        Rows.instant(rs, "expires_at")))
                .list();
    }

    /** 신청한 사람의 닉네임 — 그룹장 푸시 본문에 쓴다. */
    public Optional<String> findNickname(long userId) {
        return jdbc.sql("SELECT nickname FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /** 신청이 {@code fromStatus}일 때만 {@code toStatus}로 바꾼다. 이미 답한 신청이면 0건. */
    public int answer(long id, String fromStatus, String toStatus, Instant now) {
        return jdbc.sql("""
            UPDATE study_group_application SET status = :toStatus, answered_at = :now
             WHERE id = :id AND status = :fromStatus""")
                .param("toStatus", toStatus)
                .param("now", Rows.ts(now))
                .param("id", id)
                .param("fromStatus", fromStatus)
                .update();
    }

    /**
     * 기한이 된 신청을 모두 EXPIRED로 바꾸고 바꾼 행을 돌려준다. 푸시는 돌려받은 행에만 보내므로, 작업이 두 번 돌거나 서버 둘이 동시에 돌아도
     * 한 행은 한 번만 알린다(조건부 UPDATE가 이미 바뀐 행을 건너뛴다).
     */
    public List<ExpiredRow> expireDue(Instant now) {
        return jdbc.sql("""
            UPDATE study_group_application SET status = 'EXPIRED', answered_at = :now
             WHERE status = 'PENDING' AND expires_at <= :now
             RETURNING id, group_id, user_id""")
                .param("now", Rows.ts(now))
                .query((rs, n) -> new ExpiredRow(rs.getLong("id"), rs.getLong("group_id"), rs.getLong("user_id")))
                .list();
    }

    /** 이 쌍의 기한 지난 {@code PENDING}만 EXPIRED로 바꾼다 — 새 신청이 부분 unique에 막히지 않게 한다. 바꿨으면 1건. */
    public int expireStale(long groupId, long userId, Instant now) {
        return jdbc.sql("""
            UPDATE study_group_application SET status = 'EXPIRED', answered_at = :now
             WHERE group_id = :groupId AND user_id = :userId
               AND status = 'PENDING' AND expires_at <= :now""")
                .param("now", Rows.ts(now))
                .param("groupId", groupId)
                .param("userId", userId)
                .update();
    }

    /**
     * 그룹이 사라질 때 이 그룹에서 <b>기한 안에서 답을 기다리던</b> 신청만 GROUP_DELETED로 닫고, 바꾼 신청의 신청자 ID를 돌려준다.
     * 기한이 지났지만 만료 작업이 아직 안 닫은 {@code PENDING}은 이미 만료된 신청이라 건드리지도 돌려주지도 않는다 — 그 신청은 만료 작업이
     * EXPIRED로 닫고 신청자는 "답이 없어 취소됐어요" 푸시를 받는다(사라진 그룹의 이름도 읽는다). 만료 작업과 같은 행을 두고 겹치지 않는다.
     */
    public List<Long> closePendingOfGroup(long groupId, Instant now) {
        return jdbc.sql("""
            UPDATE study_group_application SET status = 'GROUP_DELETED', answered_at = :now
             WHERE group_id = :groupId AND status = 'PENDING' AND expires_at > :now
             RETURNING user_id""")
                .param("now", Rows.ts(now))
                .param("groupId", groupId)
                .query(Long.class)
                .list();
    }

    /** 바로 입장으로 바꾸면 기한이 지났어도 아직 PENDING인 신청을 모두 닫는다. 신청자에게는 알리지 않는다. */
    public int closePendingForOpenJoin(long groupId, Instant now) {
        return jdbc.sql("""
            UPDATE study_group_application SET status = :status, answered_at = :now
             WHERE group_id = :groupId AND status = 'PENDING'""")
                .param("status", JOIN_POLICY_OPEN)
                .param("now", Rows.ts(now))
                .param("groupId", groupId)
                .update();
    }
}
