package project.study.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import project.study.user.entity.Provider;

class SocialNonceTest {

    private static final String RAW = "raw-nonce-1234";
    // RAW의 SHA-256 hex (소문자). 앱은 이 값을 SDK에 넘기고, 원문 RAW를 link 요청에 싣는다
    private static final String HASHED = "510fd09ae00f9ca1f67236a381368ce412cc78091d177618a39ac580716ad922";

    @Test
    void sha256Hex는_UTF8_바이트의_SHA256을_소문자_hex로_돌려준다() {
        assertThat(SocialNonce.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        // 한글처럼 ASCII 밖의 문자는 UTF-8 바이트(eb85bc ec8aa4)로 해시한다
        assertThat(SocialNonce.sha256Hex("논스"))
                .isEqualTo("63bc4372647dcd3e0322a4b74497a12a4b23b75fdf4560664bb7d45ab22ba229");
    }

    @Test
    void 애플_토큰에_nonce가_없으면_실패한다() {
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.APPLE, null), RAW))
                .isInstanceOf(InvalidOAuthTokenException.class);
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.APPLE, null), null))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 토큰에_nonce가_있는데_원문이_없으면_실패한다() {
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.GOOGLE, HASHED), null))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 원문의_해시가_토큰_nonce와_다르면_실패한다() {
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.APPLE, HASHED), "other-nonce"))
                .isInstanceOf(InvalidOAuthTokenException.class);
        // 원문 그대로를 SDK에 넘긴 경우(해시하지 않음)도 다르다
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.KAKAO, RAW), RAW))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 원문의_해시가_토큰_nonce와_같으면_통과한다() {
        assertThatCode(() -> SocialNonce.check(info(Provider.APPLE, HASHED), RAW))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialNonce.check(info(Provider.GOOGLE, HASHED), RAW))
                .doesNotThrowAnyException();
    }

    @Test
    void 대문자_hex는_다른_값으로_본다() {
        // 계약은 소문자 hex다 — 앱이 대문자로 넘기면 토큰 nonce가 달라져 거부된다
        assertThatThrownBy(() -> SocialNonce.check(info(Provider.APPLE, HASHED.toUpperCase(Locale.ROOT)), RAW))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    void 구글_카카오_토큰에_nonce가_없으면_원문과_상관없이_통과한다() {
        // SDK가 nonce를 못 넣는 프로바이더의 토큰은 nonce가 없으니 그대로 받는다
        assertThatCode(() -> SocialNonce.check(info(Provider.GOOGLE, null), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialNonce.check(info(Provider.KAKAO, null), RAW)).doesNotThrowAnyException();
    }

    private static OAuthUserInfo info(Provider provider, String nonce) {
        return new OAuthUserInfo(provider, "sub-1", "a@example.com", nonce, null);
    }
}
