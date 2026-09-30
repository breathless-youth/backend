package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/**
 * 누적 공부일의 "오늘" 경계 (BY-645). 시계를 KST 23:59에 고정해, 오늘 세션은 세고 시계 오차 허용(5분)으로 저장된
 * 내일 날짜 조각은 세지 않는지 확인한다 — {@code statDate <= today} 조건이 빠지거나 {@code <}로 바뀌면 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyDaysBoundaryApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 1, 23, 59, 0, 0, KST);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "boundary-" + UUID.randomUUID());
    }

    private void submit(Instant startedAt, int durationSec) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(startedAt, startedAt.plusSeconds(durationSec), durationSec, durationSec);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED);
    }

    private void assertTotalDays(int expected) {
        // 구 앱 대응이 없는 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
        assertThat(mvc.get()
                        .uri("/api/stats/study-days")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.totalDays", v -> assertThat(v).isEqualTo(expected));
    }

    @Test
    void 오늘_세션도_센다() {
        submit(NOW.withHour(12).withMinute(0).toInstant(), 600);

        assertTotalDays(1);
    }

    @Test
    void 시계_오차로_저장된_내일_날짜_조각은_세지_않는다() {
        // 23:58 ~ 00:02 (240초, 종료가 now+3분이라 5분 허용 안에서 저장됨) → 오늘 조각 120초 + 내일 조각 120초.
        // 둘 다 1분 이상이지만 내일 조각은 today 상한에 걸려 오늘 하루만 센다
        submit(NOW.withMinute(58).toInstant(), 240);

        assertTotalDays(1);
    }
}
