package project.study.dday.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 D-Day 처리. 유저당 1개라 남는 쪽에 있으면 옮기는 쪽 것을 지운다. */
@Component
@RequiredArgsConstructor
public class DdayUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("user_dday.user_id");
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("DELETE FROM user_dday WHERE user_id = :source "
                        + "AND EXISTS (SELECT 1 FROM user_dday WHERE user_id = :target)")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("UPDATE user_dday SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM user_dday WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
