package project.study.premiumwaitlist.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 프리미엄 사전 신청 처리. 병합은 target이 이기고, 탈퇴는 행(이메일 포함)을 지운다. */
@Component
@RequiredArgsConstructor
public class PremiumWaitlistUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("premium_waitlist.user_id");
    }

    @Override
    public int order() {
        return 460;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("""
                        UPDATE premium_waitlist SET user_id = :target
                        WHERE user_id = :source
                          AND NOT EXISTS (SELECT 1 FROM premium_waitlist WHERE user_id = :target)""")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("DELETE FROM premium_waitlist WHERE user_id = :source")
                .param("source", sourceUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM premium_waitlist WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
