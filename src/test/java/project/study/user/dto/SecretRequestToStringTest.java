package project.study.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.user.entity.Provider;

/** 비밀값(ID 토큰·nonce·인가 코드)이 실린 요청은 로그·예외 메시지로 새지 않게 toString에 값을 싣지 않는다. */
class SecretRequestToStringTest {

    @Test
    void link_요청은_ID_토큰과_nonce를_싣지_않는다() {
        String printed = new LinkSocialRequest(Provider.APPLE, "eyJ.secret-id-token", "secret-nonce").toString();

        assertThat(printed).contains("APPLE").doesNotContain("secret-id-token").doesNotContain("secret-nonce");
    }

    @Test
    void 탈퇴_요청은_인가_코드를_싣지_않는다() {
        String printed = new DeleteAccountRequest("secret-code", AppleClient.IOS).toString();

        assertThat(printed).contains("IOS").doesNotContain("secret-code");
    }
}
