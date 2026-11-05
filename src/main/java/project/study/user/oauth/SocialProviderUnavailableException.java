package project.study.user.oauth;

/**
 * 프로바이더가 응답하지 않았거나(연결·타임아웃) 5xx·429·읽을 수 없는 응답을 줬다 — 토큰이 틀린 게 아니라 잠시 뒤 다시 하면 된다 (503).
 *
 * <p>원인 예외를 붙이지 않는다. RestClient의 I/O 예외 메시지에는 요청 URL이 들어가고, tokeninfo URL에는 ID 토큰이 실려 있다 — 붙이면 로그와
 * Sentry로 토큰이 샌다.
 */
public class SocialProviderUnavailableException extends RuntimeException {

    public SocialProviderUnavailableException(String message) {
        super(message);
    }
}
