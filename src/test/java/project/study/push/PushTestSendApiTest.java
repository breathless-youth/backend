package project.study.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.service.PushSender;

/** dev 전용 테스트 발송 — 본인 토큰으로만, type=test. 꺼져 있을 때 경로가 없는지는 PushTokenApiTest가 본다. */
@SpringBootTest(properties = "app.push.test-send.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PushTestSendApiTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PushSender pushSender;

    @Test
    void 본인_토큰으로만_type_test로_보내고_결과를_돌려준다() {
        when(pushSender.send(any(), any())).thenReturn(new PushResult(1, 0, 0));

        assertThat(mvc.post()
                        .uri("/api/push-tokens/test")
                        .header(ApiVersionConfig.HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"안녕\", \"link\": \"/records\"}")
                        .with(asUser(42L))
                        .exchange())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.sent", v -> assertThat(v).isEqualTo(1));

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(org.mockito.ArgumentMatchers.eq(List.of(42L)), message.capture());
        assertThat(message.getValue().type()).isEqualTo("test");
        assertThat(message.getValue().title()).isEqualTo("안녕");
        assertThat(message.getValue().link()).isEqualTo("/records");
        assertThat(message.getValue().body()).isNotBlank();
    }
}
