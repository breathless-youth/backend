package project.study.group.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 멤버 책상을 읽는 그룹 API 테스트 공용 설정 (BY-1124) — 순공·상태가 응답 시각에 달려 있어 시계를 고정하고, 쿼리 수를 센다. 책상을 읽는 테스트
 * 클래스가 모두 이 설정을 그대로 가져와야 컨텍스트(와 컨테이너)를 하나만 띄운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GroupDeskTestConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-10-14(수) 15:00 KST — 이번 주는 10/12(월)부터다. */
    public static final Instant NOW =
            ZonedDateTime.of(2026, 10, 14, 15, 0, 0, 0, KST).toInstant();

    @Bean
    @Primary
    Clock groupDeskClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    static QueryCounter queryCounter() {
        return new QueryCounter();
    }
}
