package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** 프로파일별 yaml 값에 의존하지 않도록 테스트 전용 origin을 주입해 WebConfig의 CORS 동작만 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "app.cors.allowed-origins[0]=https://web.sunqstudio.kr",
            "app.cors.allowed-origins[1]=http://localhost:[*]"
        })
class CorsIntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://web.sunqstudio.kr";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    @Autowired
    private MockMvcTester mvc;

    private MvcTestResult preflight(String origin) {
        return mvc.options()
                .uri("/api/users")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .exchange();
    }

    @Test
    void 허용된_origin의_preflight에는_허용_헤더가_실린다() {
        MvcTestResult result = preflight(ALLOWED_ORIGIN);

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void 허용되지_않은_origin의_preflight는_거부된다() {
        assertThat(preflight("https://evil.example.com")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void localhost는_포트와_무관하게_허용된다() {
        MvcTestResult result = preflight("http://localhost:5173");

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(ALLOW_ORIGIN)).isEqualTo("http://localhost:5173");
    }

    @Test
    void 실제_요청_응답에도_CORS_헤더가_붙는다() {
        MvcTestResult result = mvc.get()
                .uri("/api/stats/streak")
                .with(asUser(1))
                .header("Origin", ALLOWED_ORIGIN)
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void 인증_재도입에_대비해_Authorization_헤더가_preflight에서_허용된다() {
        MvcTestResult result = mvc.options()
                .uri("/api/users")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Headers"))
                .contains("Authorization");
    }

    @Test
    void 브라우저_refresh_쿠키를_위해_credentials를_허용한다() {
        assertThat(preflight(ALLOWED_ORIGIN).getResponse().getHeader(ALLOW_CREDENTIALS))
                .isEqualTo("true");
    }

    @Test
    void Refresh_Transport_헤더를_실은_preflight는_쿠키를_쓰는_POST와_보호된_GET_모두_허용된다() {
        // 웹은 이 헤더를 인증 경로에만 붙이지만, 공통 fetch 도우미가 모든 요청에 붙여도 GET 조회가 preflight에서 막히지 않아야 한다
        Map<String, String> requests = Map.of("POST", "/api/auth/refresh", "GET", "/api/users/me/profile");
        requests.forEach((method, uri) -> {
            MvcTestResult result = mvc.options()
                    .uri(uri)
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", "refresh-transport,content-type,authorization")
                    .exchange();

            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(result.getResponse().getHeader(ALLOW_CREDENTIALS)).isEqualTo("true");
            assertThat(result.getResponse().getHeader("Access-Control-Allow-Headers"))
                    .containsIgnoringCase("refresh-transport");
        });
    }

    @Test
    void 허용되지_않은_origin은_Refresh_Transport_preflight도_거부된다() {
        assertThat(mvc.options()
                        .uri("/api/auth/refresh")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "refresh-transport")
                        .exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
