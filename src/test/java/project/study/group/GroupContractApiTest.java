package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/**
 * 그룹 REST 계약 (BY-1124). 로직 전에는 컨트롤러가 501 {@code NOT_IMPLEMENTED}를 주고, FE는 Swagger의 경로·DTO로 타입을 쓴다.
 * 서비스가 DB를 건드리지 않으므로 {@code asUser}가 가리키는 사용자 행은 필요 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupContractApiTest {

    private static final String CREATE_BODY = """
            {"name": "새벽 공시반", "visibility": "PUBLIC", "category": "CIVIL_SERVICE", "description": "같이 해요"}""";

    private static final String UPDATE_BODY = """
            {"name": "새벽 공시반", "category": "CIVIL_SERVICE", "description": "같이 해요"}""";

    @Autowired
    private MockMvcTester mvc;

    // 새 경로라 버전 1이다 (ADR-0015 갱신) — asUser의 기본 헤더(2)를 덮는다
    private MockMvcRequestBuilder get(String uri) {
        return mvc.get().uri(uri).header(ApiVersionConfig.HEADER, "1").with(asUser(1L));
    }

    private MockMvcRequestBuilder withBody(MockMvcRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertNotImplemented(MockMvcRequestBuilder request) {
        assertThat(request)
                .hasStatus(HttpStatus.NOT_IMPLEMENTED)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("NOT_IMPLEMENTED"));
    }

    @Test
    void 로직_전에는_501과_NOT_IMPLEMENTED다() {
        assertNotImplemented(withBody(
                mvc.post()
                        .uri("/api/groups")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(1L)),
                CREATE_BODY));
        assertNotImplemented(get("/api/groups/me"));
        assertNotImplemented(get("/api/groups/10"));
        assertNotImplemented(withBody(
                mvc.patch()
                        .uri("/api/groups/10")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(1L)),
                UPDATE_BODY));
        assertNotImplemented(get("/api/groups/public"));
        assertNotImplemented(get("/api/groups/public/10"));
        assertNotImplemented(withBody(
                mvc.put()
                        .uri("/api/groups/10/notifications")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(1L)),
                """
                {"enabled": true}"""));
    }

    @Test
    void 고정_경로가_groupId_경로보다_먼저_잡힌다() {
        // /{groupId}가 먼저 잡혔다면 "me"·"public"을 Long으로 바꾸다 400이 난다
        assertNotImplemented(get("/api/groups/me"));
        assertNotImplemented(get("/api/groups/public"));
    }

    @Test
    void 인증_없으면_401이다() {
        assertThat(mvc.get().uri("/api/groups/me").header(ApiVersionConfig.HEADER, "1"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 이름이_21자면_400_VALIDATION_FAILED다() {
        String body = """
                {"name": "%s", "visibility": "PUBLIC", "category": "CIVIL_SERVICE"}""".formatted("가".repeat(21));

        assertThat(withBody(
                        mvc.post()
                                .uri("/api/groups")
                                .header(ApiVersionConfig.HEADER, "1")
                                .with(asUser(1L)),
                        body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void 없는_시험_목표는_400이다() {
        String body = """
                {"name": "새벽 공시반", "visibility": "PUBLIC", "category": "UNKNOWN"}""";

        assertThat(withBody(
                        mvc.post()
                                .uri("/api/groups")
                                .header(ApiVersionConfig.HEADER, "1")
                                .with(asUser(1L)),
                        body))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void size가_50을_넘으면_400이다() {
        assertThat(get("/api/groups/public?size=51")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void Swagger에_그룹_태그와_버전_1이_실린다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/groups/me'].get.tags[0]",
                        v -> assertThat(v).isEqualTo("그룹"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/me'].get.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                // 7개 오퍼레이션이 모두 문서에 실린다
                .hasPathSatisfying(
                        "$.paths['/api/groups'].post", v -> assertThat(v).isNotNull())
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}'].get",
                        v -> assertThat(v).isNotNull())
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}'].patch",
                        v -> assertThat(v).isNotNull())
                .hasPathSatisfying(
                        "$.paths['/api/groups/public'].get", v -> assertThat(v).isNotNull())
                .hasPathSatisfying(
                        "$.paths['/api/groups/public/{groupId}'].get",
                        v -> assertThat(v).isNotNull())
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/notifications'].put",
                        v -> assertThat(v).isNotNull());
    }
}
