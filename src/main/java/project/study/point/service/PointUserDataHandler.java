package project.study.point.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 비회원은 포인트를 받지 못하므로 병합에서 옮길 것이 없다. 옮기는 쪽 행은 지우고 받는 쪽을 그대로 둔다. */
@Component
@RequiredArgsConstructor
public class PointUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;
    private final PointLocks locks;

    @Override
    public Set<String> columns() {
        return Set.of("point_account.user_id", "point_ledger.user_id", "point_purchase.user_id");
    }

    @Override
    public int order() {
        return 530;
    }

    /**
     * 적립·충전은 포인트 사용자 락을 쥔 채 users를 가리키는 행을 넣는다(FK가 users 행을 본다). 탈퇴가 users 행을 먼저 잡고 이 락을
     * 기다리면 서로 물리므로, users 행보다 먼저 잡는다.
     */
    @Override
    public void lockBeforeUsers(long userId) {
        locks.lockUser(userId);
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        // 끝나지 않은 적립·구매·꾸미기 저장이 지운 뒤에 행을 다시 넣지 못하게 같은 락으로 줄 세운다
        locks.lockUser(userId);
        jdbc.sql("DELETE FROM point_ledger WHERE user_id = :u")
                .param("u", userId)
                .update();
        // 결제 기록은 남기고 사용자만 비운다 — 지우면 같은 결제의 알림이 다시 올 때 한 번 더 지급된다
        jdbc.sql("UPDATE point_purchase SET user_id = NULL WHERE user_id = :u")
                .param("u", userId)
                .update();
        jdbc.sql("DELETE FROM point_account WHERE user_id = :u")
                .param("u", userId)
                .update();
    }
}
