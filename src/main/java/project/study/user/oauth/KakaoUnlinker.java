package project.study.user.oauth;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 탈퇴 시 카카오 연결 끊기. 카카오 로그인 정책이 서비스 탈퇴 때 연결 끊기를 요구한다. 앱의 사용자 토큰 없이 서버가 어드민 키와 회원번호로
 * 끊는다. 어떤 실패도 밖으로 던지지 않는다. 카카오 때문에 탈퇴가 실패하면 안 된다. 대신 실패는 Sentry에 남긴다(sentry-logback이 없어
 * 로그만으로는 아무도 모른다). 어드민 키는 모든 사용자를 끊을 수 있는 비밀값이라 로그·Sentry에 싣지 않는다.
 */
@Slf4j
@Component
public class KakaoUnlinker {

    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";

    private final RestClient restClient;
    private final OAuthProperties.Kakao kakao;

    public KakaoUnlinker(
            @Qualifier(SocialProviderHttpConfig.REST_CLIENT) RestClient restClient, OAuthProperties properties) {
        this.restClient = restClient;
        this.kakao = properties.kakao();
    }

    /** kakaoUserId는 카카오 회원번호(ID 토큰의 sub)다. */
    public void unlink(String kakaoUserId) {
        // 키가 없는 환경(로컬 등)은 정상이다. 빠진 운영 설정은 OAuthConfigCheck가 기동 뒤 한 번 알린다
        if (!kakao.unlinkConfigured()) {
            log.debug("카카오 연결 끊기를 건너뜀: 어드민 키가 없다");
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);
        try {
            restClient
                    .post()
                    .uri(UNLINK_URL)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakao.adminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 예외 메시지는 싣지 않는다. 클래스와 HTTP 상태면 원인을 가를 수 있다
            String status = e instanceof RestClientResponseException response
                    ? ", status=" + response.getStatusCode().value()
                    : "";
            String message = "카카오 연결 끊기 실패 (error=" + e.getClass().getName() + status + ")";
            log.error(message);
            Sentry.captureMessage(message, SentryLevel.ERROR);
        }
    }
}
