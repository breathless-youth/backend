package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * BY-541 API 버전닝 — API-Version 헤더 해석과 기본버전(1) 동작을 다룬다 (ADR-0015).
 *
 * <p>기존 엔드포인트 정상 동작은 404(핸들러 도달)로 검증한다 — 미지원 버전이면 매핑 단계에서
 * 거부되어 400이 나므로, 404는 버전 해석을 통과해 라우팅까지 됐다는 증거다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiVersionApiTest {

    private static final String NO_SUCH_USER_PROFILE = "/api/users/999999999/profile";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 버전_헤더가_없으면_기본버전_1로_해석되어_기존_엔드포인트에_라우팅된다() {
        assertThat(mvc.get().uri(NO_SUCH_USER_PROFILE).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 버전_1을_명시해도_헤더_없는_요청과_동일하게_라우팅된다() {
        assertThat(mvc.get()
                        .uri(NO_SUCH_USER_PROFILE)
                        .header("API-Version", "1")
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 지원하지_않는_버전이면_400이다() {
        assertThat(mvc.get()
                        .uri(NO_SUCH_USER_PROFILE)
                        .header("API-Version", "99")
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 파싱할_수_없는_버전이면_400이다() {
        assertThat(mvc.get()
                        .uri(NO_SUCH_USER_PROFILE)
                        .header("API-Version", "abc")
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
