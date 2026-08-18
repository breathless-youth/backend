package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import project.study.TestcontainersConfiguration;

/** 세션 기간 무겹침 제약(V9) — 제출 시점의 두 기기 동시 사용 충돌이 409로 거절되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionOverlapApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = insertUser();
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private static RequestPostProcessor authenticatedUser(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private MvcTestResult submit(Instant startedAt, Instant endedAt) {
        int sec = (int) Duration.between(startedAt, endedAt).toSeconds();
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(startedAt, endedAt, sec, sec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(authenticatedUser(userId))
                .exchange();
    }

    @Test
    void 기간이_겹치는_별개_제출은_409로_거절된다() {
        Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
        // 첫 제출: base ~ base+60분
        assertThat(submit(base, base.plus(60, ChronoUnit.MINUTES))).hasStatus(HttpStatus.CREATED);
        // 겹치는 제출: base+5분 ~ base+50분 (시작 시각이 달라 유니크는 통과, 기간은 겹침)
        assertThat(submit(base.plus(5, ChronoUnit.MINUTES), base.plus(50, ChronoUnit.MINUTES)))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void 끝과_시작이_맞닿는_세션은_겹침이_아니다() {
        Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
        assertThat(submit(base, base.plus(30, ChronoUnit.MINUTES))).hasStatus(HttpStatus.CREATED);
        // 앞 세션의 끝 == 뒷 세션의 시작 → 반개구간이라 허용
        assertThat(submit(base.plus(30, ChronoUnit.MINUTES), base.plus(60, ChronoUnit.MINUTES)))
                .hasStatus(HttpStatus.CREATED);
    }
}
