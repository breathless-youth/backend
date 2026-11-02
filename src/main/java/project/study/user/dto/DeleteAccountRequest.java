package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 계정 삭제 요청 본문 — 전체가 선택이다. 애플 계정만 두 값을 보낸다. 인가 코드는 비밀값이라 로그에 남기지 않는다. */
public record DeleteAccountRequest(
        @Schema(description = "애플 계정만: 탈퇴 직전 애플 로그인을 다시 띄워 받은 authorizationCode. 서버가 애플 토큰을 해지하는 데 쓴다")
        @Size(max = 1024, message = "authorizationCode는 1024자 이하여야 합니다")
        String appleAuthorizationCode,

        @Schema(
                description = "애플 계정만: 코드를 받은 앱 종류",
                example = "IOS",
                allowableValues = {"IOS", "ANDROID"})
        AppleClient appleClient) {

    // 인가 코드가 로그·예외 메시지로 새지 않게 record 기본 toString을 막는다
    @Override
    public String toString() {
        return "DeleteAccountRequest[appleClient=" + appleClient + "]";
    }
}
