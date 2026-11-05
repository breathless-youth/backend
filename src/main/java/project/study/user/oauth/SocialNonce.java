package project.study.user.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import project.study.user.entity.Provider;

/**
 * ID 토큰의 nonce 클레임을 link 요청의 nonce 원문과 맞춰 본다. 앱은 원문을 만들어 그 SHA-256 소문자 hex를 SDK에 넘기고, 원문은 link에
 * 싣는다. 새어 나간 ID 토큰을 다른 비회원이 link에 넣어 남의 계정 토큰을 받는(병합 경로) 탈취를 막는다.
 *
 * <p>애플은 nonce가 필수다. SDK가 nonce를 못 넣는 프로바이더의 토큰은 nonce가 없으니 그대로 받는다. 값(원문·클레임)은 로그에 남기지 않는다.
 */
public final class SocialNonce {

    private SocialNonce() {}

    public static void check(OAuthUserInfo info, String rawNonce) {
        String tokenNonce = info.nonce();
        if (tokenNonce == null) {
            if (info.provider() == Provider.APPLE) {
                throw new InvalidOAuthTokenException("애플 ID 토큰에 nonce가 없습니다");
            }
            return;
        }
        if (rawNonce == null || !constantTimeEquals(sha256Hex(rawNonce), tokenNonce)) {
            throw new InvalidOAuthTokenException("nonce가 일치하지 않습니다");
        }
    }

    /** UTF-8 바이트의 SHA-256을 소문자 hex로. */
    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
