package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.user.entity.Provider;

class SocialTokenVerifiersTest {

    private static OAuthTokenVerifier stub(Provider provider, String sub) {
        return new OAuthTokenVerifier() {
            @Override
            public Provider provider() {
                return provider;
            }

            @Override
            public OAuthUserInfo verify(String idToken) {
                return new OAuthUserInfo(provider, sub + ":" + idToken, null, null, null);
            }
        };
    }

    private final SocialTokenVerifiers verifiers =
            new SocialTokenVerifiers(List.of(stub(Provider.GOOGLE, "g"), stub(Provider.KAKAO, "k")));

    @Test
    void 프로바이더에_맞는_검증기로_위임한다() {
        OAuthUserInfo info = verifiers.verify(Provider.KAKAO, "t");

        assertThat(info.provider()).isEqualTo(Provider.KAKAO);
        assertThat(info.providerUserId()).isEqualTo("k:t");
    }

    @Test
    void DEVICE는_거부한다() {
        assertThatThrownBy(() -> verifiers.verify(Provider.DEVICE, "x")).isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 등록되지_않은_프로바이더는_거부한다() {
        assertThatThrownBy(() -> verifiers.verify(Provider.APPLE, "x")).isInstanceOf(InvalidOAuthTokenException.class);
    }
}
