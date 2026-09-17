package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** app.docs.enabled=true면 FE가 토큰 없이 브라우저로 명세 페이지를 연다 (BY-667). */
@SpringBootTest(properties = "app.docs.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocsPageEnabledApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 스위치가_켜지면_토큰_없이_웹소켓_명세_페이지를_연다() {
        MvcTestResult result = mvc.get().uri("/docs/websocket.html").exchange();
        assertThat(result).hasStatus(HttpStatus.OK).hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        // MockHttpServletResponse는 charset이 없는 응답을 ISO-8859-1로 읽는다 — 페이지는 <meta charset="utf-8">로
        // 선언하므로 바이트를 UTF-8로 본다
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("<title>룸 WebSocket 명세</title>");
    }

    @Test
    void 없는_문서는_404() {
        assertThat(mvc.get().uri("/docs/nope.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }
}
