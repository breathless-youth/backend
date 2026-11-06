package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 로그에도 어드민 키·회원번호가 남지 않는지 보려고 출력을 잡는다
@ExtendWith(OutputCaptureExtension.class)
class KakaoUnlinkerTest {

    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";
    private static final String ADMIN_KEY = "the-admin-key";
    private static final String KAKAO_ID = "1234567890";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    // 연결 끊기 실패는 로그만으로는 아무도 모른다(sentry-logback 없음). Sentry에 직접 남는지 본다
    private final MockedStatic<Sentry> sentry = mockStatic(Sentry.class);

    @AfterEach
    void closeSentry() {
        sentry.close();
    }

    @Test
    void 어드민_키와_회원번호로_연결을_끊는다(CapturedOutput output) {
        server.expect(requestTo(UNLINK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK " + ADMIN_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("target_id_type=user_id&target_id=1234567890"))
                .andRespond(withSuccess("{\"id\":1234567890}", MediaType.APPLICATION_JSON));

        unlinker(ADMIN_KEY).unlink(KAKAO_ID);

        server.verify();
        sentry.verifyNoInteractions();
        assertNoSecrets(output);
    }

    @Test
    void 카카오가_오류로_답하면_예외_없이_Sentry에_남긴다(CapturedOutput output) {
        server.expect(requestTo(UNLINK_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));

        assertThatCode(() -> unlinker(ADMIN_KEY).unlink(KAKAO_ID)).doesNotThrowAnyException();

        server.verify();
        assertSentry("status=400");
        assertThat(output).contains("카카오 연결 끊기 실패");
        assertNoSecrets(output);
    }

    @Test
    void 연결이_끊기거나_시간이_지나도_예외_없이_Sentry에_남긴다(CapturedOutput output) {
        server.expect(requestTo(UNLINK_URL)).andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatCode(() -> unlinker(ADMIN_KEY).unlink(KAKAO_ID)).doesNotThrowAnyException();

        server.verify();
        assertSentry("error=");
        assertThat(output).contains("카카오 연결 끊기 실패");
        assertNoSecrets(output);
    }

    @Test
    void 어드민_키가_없으면_부르지_않고_Sentry에도_남기지_않는다(CapturedOutput output) {
        server.expect(never(), requestTo(UNLINK_URL)).andRespond(withSuccess());

        unlinker(null).unlink(KAKAO_ID);
        unlinker(" ").unlink(KAKAO_ID);

        server.verify();
        sentry.verifyNoInteractions();
        assertNoSecrets(output);
    }

    // 고정 문구와 예외 정보만 싣는다. 어드민 키와 회원번호는 싣지 않는다
    private void assertSentry(String detail) {
        sentry.verify(() -> Sentry.captureMessage(
                argThat(message -> message.startsWith("카카오 연결 끊기 실패")
                        && message.contains(detail)
                        && !message.contains(ADMIN_KEY)
                        && !message.contains(KAKAO_ID)),
                eq(SentryLevel.ERROR)));
    }

    private static void assertNoSecrets(CapturedOutput output) {
        assertThat(output).doesNotContain(ADMIN_KEY).doesNotContain(KAKAO_ID);
    }

    private KakaoUnlinker unlinker(String adminKey) {
        OAuthProperties properties = new OAuthProperties(null, new OAuthProperties.Kakao(List.of(), adminKey), null);
        return new KakaoUnlinker(builder.build(), properties);
    }
}
