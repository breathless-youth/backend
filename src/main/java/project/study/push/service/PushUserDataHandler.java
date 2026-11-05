package project.study.push.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 푸시 처리. 토큰은 기기에 붙어 있어 옮기고, 실험 배정·발송 기록은 옮기는 쪽 것을 지운다. */
@Component
@RequiredArgsConstructor
public class PushUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("push_token.user_id", "push_send.user_id", "push_experiment_assignment.user_id");
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("UPDATE push_token SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        deleteFrom("push_send", sourceUserId);
        deleteFrom("push_experiment_assignment", sourceUserId);
    }

    @Override
    public void erase(long userId) {
        deleteFrom("push_token", userId);
        deleteFrom("push_send", userId);
        deleteFrom("push_experiment_assignment", userId);
    }

    private void deleteFrom(String table, long userId) {
        jdbc.sql("DELETE FROM " + table + " WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
