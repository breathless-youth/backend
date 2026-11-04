package project.study.block.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/**
 * 계정 병합·탈퇴 시 차단 처리 (BY-1126). 병합은 옮기는 쪽이 건 차단과 받은 차단을 모두 남는 쪽으로 합친다 — 두 계정 사이의 차단은 자기 자신 차단이
 * 되어 버리고({@code user_block_self_chk}), 남는 쪽에 이미 있는 쌍은 남는 쪽 행(처음 차단 시각)을 둔다. 탈퇴는 양쪽 방향을 모두 지운다.
 */
@Component
@RequiredArgsConstructor
public class BlockUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("user_block.blocker_id", "user_block.blocked_id");
    }

    @Override
    public int order() {
        return 260;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        // 기본 키(blocker, blocked)를 바꾸는 UPDATE는 남는 쪽 행과 부딪히므로, 남는 쪽 행으로 복사한 뒤 옮기는 쪽 행을 지운다
        jdbc.sql("""
                        INSERT INTO user_block (blocker_id, blocked_id, created_at)
                        SELECT :target, blocked_id, created_at FROM user_block
                         WHERE blocker_id = :source AND blocked_id <> :target
                        ON CONFLICT (blocker_id, blocked_id) DO NOTHING""")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("""
                        INSERT INTO user_block (blocker_id, blocked_id, created_at)
                        SELECT blocker_id, :target, created_at FROM user_block
                         WHERE blocked_id = :source AND blocker_id <> :target
                        ON CONFLICT (blocker_id, blocked_id) DO NOTHING""")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM user_block WHERE blocker_id = :id OR blocked_id = :id")
                .param("id", userId)
                .update();
    }
}
