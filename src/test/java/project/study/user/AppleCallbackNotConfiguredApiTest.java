package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** 안드로이드 콜백 주소가 설정되지 않은 환경(운영 기본값) — 엔드포인트가 없는 것처럼 404다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AppleCallbackNotConfiguredApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 설정이_없으면_404다() {
        MvcTestResult result = mvc.post()
                .uri("/api/auth/apple/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("id_token", "a.b.c")
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }
}
