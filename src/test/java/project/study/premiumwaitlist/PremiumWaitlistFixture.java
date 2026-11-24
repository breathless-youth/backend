package project.study.premiumwaitlist;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 병합·탈퇴 테스트용 시드 — 계정 데이터 테스트가 premium_waitlist 행도 다루게 한다. */
@Component
@RequiredArgsConstructor
public class PremiumWaitlistFixture {

    private final JdbcClient jdbc;

    public void seed(long userId) {
        jdbc.sql("""
                        INSERT INTO premium_waitlist (user_id, price, placement, age_over14, email, created_at, updated_at)
                        VALUES (:u, 2900, 'HOME', true, 'u@example.com', now(), now())""").param("u", userId).update();
    }
}
