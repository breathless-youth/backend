package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
        // 프론트 번들러마다 포트가 달라 패턴으로 열어둔 것이 실제로 동작하는지 확인한다
        MvcTestResult result = preflight("http://localhost:5173");

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(ALLOW_ORIGIN)).isEqualTo("http://localhost:5173");
    }

    @Test
    void 실제_요청_응답에도_CORS_헤더가_붙는다() {
        var auth = new UsernamePasswordAuthenticationToken(1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        MvcTestResult result = mvc.get()
                .uri("/api/stats/streak")
                .header("Origin", ALLOWED_ORIGIN)
                .with(authentication(auth))
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

    // 보장 범위는 "응답에 credentials 허용 헤더가 없다"까지다. 서버가 쿠키 헤더를 거부한다는 뜻이 아니라,
    // 브라우저가 쿠키를 실은 교차 출처 요청의 응답을 읽지 못하게 된다는 의미다.
    @Test
    void credentials_허용_헤더는_응답에_실리지_않는다() {
        assertThat(preflight(ALLOWED_ORIGIN).getResponse().getHeader("Access-Control-Allow-Credentials"))
                .isNull();
    }
}
