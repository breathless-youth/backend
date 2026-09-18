package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * 구 앱 병행 중의 Swagger 문서 (ADR-0020). springdoc은 같은 경로+메서드를 하나로 합치므로 구 앱(v1) 핸들러를
 * 문서에 실으면 토큰 계약(v2) 오퍼레이션이 덮어써진다 — 구 앱 핸들러는 {@code @Hidden}이고 문서는 v2만 싣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiVersionSwaggerDocsTest {

    private static final String API_DOCS = "/v3/api-docs";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 같은_경로의_오퍼레이션은_토큰_계약_v2가_실린다() {
        assertThat(mvc.get().uri(API_DOCS))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/users'].post.summary",
                        v -> assertThat(v).asString().contains("토큰 발급"))
                // 구 앱 핸들러가 실렸다면 summary가 없다(@Hidden이라 @Operation도 없음)
                .hasPathSatisfying(
                        "$.paths['/api/rooms'].post.summary", v -> assertThat(v).isEqualTo("방 생성"))
                .hasPathSatisfying(
                        "$.paths['/api/users/me/profile'].get",
                        v -> assertThat(v).isNotNull());
    }

    @Test
    void 구_앱_전용_경로는_문서에_실리지_않는다() {
        assertThat(mvc.get().uri(API_DOCS))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.paths['/api/users/{userId}/profile']");
    }

    @Test
    void 오퍼레이션의_API_Version_헤더_파라미터는_기본값이_2다() {
        // springdoc은 Spring 기본버전(1 → "1.0.0")을 기본값으로 싣는다 — Swagger UI에서 그대로 보내면 구 앱 핸들러로 가서 400이다
        assertThat(mvc.get().uri(API_DOCS))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/rooms'].post.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("2"))
                .hasPathSatisfying(
                        "$.paths['/api/users/me/profile'].get.parameters[?(@.name=='API-Version')].required",
                        v -> assertThat(v).asList().containsExactly(true));
    }

    @Test
    void 문서_설명에_API_Version_규칙이_있다() {
        assertThat(mvc.get().uri(API_DOCS))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.info.description", v -> assertThat(v).asString().contains("API-Version: 2"));
    }
}
