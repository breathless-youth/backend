package project.study.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 계정 삭제 요청 본문 — 전체가 선택이다. 애플 계정만 두 값을 보낸다. 인가 코드를 보내면 앱 종류도 함께 보내야 한다(코드를 만든 클라이언트로만
 * 바꿀 수 있어 짐작하지 않는다). 인가 코드는 비밀값이라 로그에 남기지 않는다.
 */
public record DeleteAccountRequest(
        @Schema(description = "애플 계정만: 탈퇴 직전 애플 로그인을 다시 띄워 받은 authorizationCode. 서버가 애플 토큰을 해지하는 데 쓴다")
        @Size(max = 1024, message = "authorizationCode는 1024자 이하여야 합니다")
        String appleAuthorizationCode,

        @Schema(
                description = "애플 계정만: 코드를 받은 앱 종류. appleAuthorizationCode를 보내면 필수",
                example = "IOS",
                allowableValues = {"IOS", "ANDROID"})
        AppleClient appleClient) {

    /** 검증 전용 — 인가 코드가 있으면 앱 종류도 있어야 한다. 위반은 지우기 전에 400 {@code VALIDATION_FAILED}다. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "appleAuthorizationCode를 보낼 때는 appleClient(IOS·ANDROID)도 보내야 합니다")
    public boolean isAppleClientGiven() {
        return appleAuthorizationCode == null || appleAuthorizationCode.isBlank() || appleClient != null;
    }

    // 인가 코드가 로그·예외 메시지로 새지 않게 record 기본 toString을 막는다
    @Override
    public String toString() {
        return "DeleteAccountRequest[appleClient=" + appleClient + "]";
    }
}
