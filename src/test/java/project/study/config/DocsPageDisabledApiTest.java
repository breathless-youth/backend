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

/** 스위치가 없으면(운영) 매핑 자체가 없어 404다 — 401이 아니라 404여야 permitAll이 함께 들어간 것이다 (BY-667). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocsPageDisabledApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 스위치가_없으면_명세_페이지는_404() {
        assertThat(mvc.get().uri("/docs/websocket.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }
}
