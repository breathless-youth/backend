package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API-Version 헤더 해석 (ADR-0015) — 병행 기간에는 헤더 없음/1이 구 앱 계약, 2가 토큰 계약이다 (ADR-0020).
 *
 * <p>같은 경로에 버전이 다른 핸들러가 있으면 요청 버전과 정확히 일치하는 쪽만 선택되고,
 * 미지원·파싱 불가·불일치 버전은 매핑 단계에서 400이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiVersionApiTest {

    private static final String API_VERSION = "API-Version";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 토큰 계약(API-Version: 2)으로 등록해 userId와 access 토큰을 얻는다. */
    private JsonNode registerV2() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .header(API_VERSION, "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void 버전_헤더가_없으면_기본버전_1로_해석되어_구_앱_핸들러에_라우팅된다() {
        long userId = registerV2().get("userId").asLong();

        assertThat(mvc.get().uri("/api/users/" + userId + "/profile")).hasStatusOk();
    }

    @Test
    void 버전_1을_명시해도_헤더_없는_요청과_동일하게_라우팅된다() {
        long userId = registerV2().get("userId").asLong();

        assertThat(mvc.get().uri("/api/users/" + userId + "/profile").header(API_VERSION, "1"))
                .hasStatusOk();
    }

    @Test
    void 버전_2는_토큰_계약_핸들러에_라우팅된다() {
        long userId = registerV2().get("userId").asLong();

        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId))).hasStatusOk();
    }

    @Test
    void 토큰이_있어도_버전_헤더가_없으면_토큰_계약_핸들러에_닿지_않는다() {
        // 새 앱이 헤더를 빠뜨린 경우 — 구 앱 핸들러(/{userId}/profile)에 "me"가 들어가 400으로 드러난다
        String accessToken = registerV2().get("accessToken").asString();

        assertThat(mvc.get().uri("/api/users/me/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 지원하지_않는_버전이면_400이다() {
        long userId = registerV2().get("userId").asLong();

        assertThat(mvc.get()
                        .uri("/api/users/me/profile")
                        .header(API_VERSION, "99")
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 파싱할_수_없는_버전이면_400이다() {
        long userId = registerV2().get("userId").asLong();

        assertThat(mvc.get()
                        .uri("/api/users/me/profile")
                        .header(API_VERSION, "abc")
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
