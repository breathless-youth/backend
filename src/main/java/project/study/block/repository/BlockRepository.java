package project.study.block.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * user_block 테이블 (BY-1124). 한 행은 방향이 있는 차단(blocker → blocked)이지만, 막는 쪽은 언제나 "어느 쪽이든 차단한 사이"다 — 양쪽 방향을
 * 함께 찾는 조회는 기본 키(blocker 쪽)와 {@code user_block_blocked_idx}(blocked 쪽)를 하나씩 탄다.
 */
@Repository
@RequiredArgsConstructor
public class BlockRepository {

    /** 내가 건 차단 한 줄 — 차단한 사람의 닉네임·사진 키를 함께 읽는다(사진은 저장된 키라 응답 경로로 바꿔 써야 한다). */
    public record BlockedRow(long userId, String nickname, String profileImage, Instant blockedAt) {}

    private final JdbcClient jdbc;

    /** 차단을 건다. 이미 있으면 그대로 두고(처음 차단 시각 유지) 0을 돌려준다. */
    public int insert(long blockerId, long blockedId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO user_block (blocker_id, blocked_id, created_at)
                        VALUES (:blockerId, :blockedId, :now)
                        ON CONFLICT (blocker_id, blocked_id) DO NOTHING""")
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** 내가 건 차단을 지운다. 상대가 건 차단은 건드리지 않는다. */
    public int delete(long blockerId, long blockedId) {
        return jdbc.sql("DELETE FROM user_block WHERE blocker_id = :blockerId AND blocked_id = :blockedId")
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .update();
    }

    /** 내가 건 차단, 차단한 순서(오래된 것부터). 같은 시각이면 userId 순이다. */
    public List<BlockedRow> findByBlocker(long blockerId) {
        return jdbc.sql("""
                        SELECT b.blocked_id, u.nickname, u.profile_image, b.created_at
                          FROM user_block b
                          JOIN users u ON u.id = b.blocked_id
                         WHERE b.blocker_id = :blockerId
                         ORDER BY b.created_at, b.blocked_id""")
                .param("blockerId", blockerId)
                .query((rs, n) -> new BlockedRow(
                        rs.getLong("blocked_id"),
                        rs.getString("nickname"),
                        rs.getString("profile_image"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public boolean existsEitherWay(long a, long b) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_block
                                        WHERE (blocker_id = :a AND blocked_id = :b)
                                           OR (blocker_id = :b AND blocked_id = :a))""").param("a", a).param("b", b).query(Boolean.class).single();
    }

    /** {@code others} 중 {@code userId}와 어느 쪽이든 차단한 사이인 사람. 호출자가 빈 목록을 거른다. */
    public Set<Long> findPeers(long userId, Collection<Long> others) {
        return new HashSet<>(jdbc.sql("""
                        SELECT blocked_id FROM user_block WHERE blocker_id = :userId AND blocked_id IN (:others)
                        UNION
                        SELECT blocker_id FROM user_block WHERE blocked_id = :userId AND blocker_id IN (:others)""")
                .param("userId", userId)
                .param("others", others)
                .query(Long.class)
                .list());
    }
}
