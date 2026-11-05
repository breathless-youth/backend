package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** 안드로이드 애플 웹 로그인 콜백 — 애플이 form_post로 보낸 값을 정해 둔 딥링크로 넘기기만 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "oauth.apple.android-callback-redirect=https://app.example/apple-callback")
class AppleCallbackApiTest {

    private static final String REDIRECT = "https://app.example/apple-callback";

    @Autowired
    private MockMvcTester mvc;

    private MvcTestResult post(String... nameValues) {
        var request = mvc.post().uri("/api/auth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < nameValues.length; i += 2) {
            request = request.param(nameValues[i], nameValues[i + 1]);
        }
        return request.exchange();
    }

    @Test
    void 애플_결과를_정해_둔_딥링크로_303_넘긴다() {
        MvcTestResult result = post("code", "c0de+/=", "id_token", "a.b.c", "state", "st ate&x");

        assertThat(result).hasStatus(303);
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(REDIRECT + "?");
        assertThat(location)
                .contains("code=c0de%2B%2F%3D")
                .contains("id_token=a.b.c")
                .contains("state=st%20ate%26x");
    }

    @Test
    void 값이_있는_파라미터만_붙인다() {
        MvcTestResult result = post("id_token", "a.b.c", "state", "");

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isEqualTo(REDIRECT + "?id_token=a.b.c");
    }

    @Test
    void 인증_없이_부를_수_있다() {
        MvcTestResult result = post("id_token", "a.b.c");

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
    }

    @Test
    void 받은_주소로는_넘기지_않는다() {
        MvcTestResult result = post("id_token", "a.b.c", "redirect_uri", "https://evil.example/steal");

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(REDIRECT + "?");
        assertThat(location).doesNotContain("evil.example").doesNotContain("redirect_uri");
    }
}
