package project.study.shop.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.point.service.PointLocks;
import project.study.user.account.UserDataHandler;

/** 비회원은 상점을 쓰지 못하므로 병합에서 옮길 것이 없다. 옮기는 쪽 행은 지우고 받는 쪽을 그대로 둔다. */
@Component
@RequiredArgsConstructor
public class ShopUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;
    private final PointLocks locks;

    @Override
    public Set<String> columns() {
        return Set.of("user_item.user_id", "user_mascot_accessory.user_id", "user_decoration.user_id");
    }

    @Override
    public int order() {
        return 550;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        // 끝나지 않은 적립·구매·꾸미기 저장이 지운 뒤에 행을 다시 넣지 못하게 같은 락으로 줄 세운다
        locks.lockUser(userId);
        for (String table : new String[] {"user_mascot_accessory", "user_decoration", "user_item"}) {
            jdbc.sql("DELETE FROM " + table + " WHERE user_id = :u")
                    .param("u", userId)
                    .update();
        }
    }
}
