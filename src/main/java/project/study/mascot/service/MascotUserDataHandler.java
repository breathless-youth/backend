package project.study.mascot.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.point.service.PointLocks;
import project.study.user.account.UserDataHandler;

/** 비회원은 마스코트를 쓰지 못하므로 병합에서 옮길 것이 없다. 옮기는 쪽 행은 지우고 받는 쪽을 그대로 둔다. */
@Component
@RequiredArgsConstructor
public class MascotUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;
    private final PointLocks locks;

    @Override
    public Set<String> columns() {
        return Set.of("user_mascot.user_id", "user_mascot_seen.user_id");
    }

    @Override
    public int order() {
        return 540;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        // 끝나지 않은 적립·구매·꾸미기 저장이 지운 뒤에 행을 다시 넣지 못하게 같은 락으로 줄 세운다
        locks.lockUser(userId);
        jdbc.sql("DELETE FROM user_mascot_seen WHERE user_id = :u")
                .param("u", userId)
                .update();
        jdbc.sql("DELETE FROM user_mascot WHERE user_id = :u")
                .param("u", userId)
                .update();
    }
}
