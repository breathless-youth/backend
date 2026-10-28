package project.study.acquisition.referrer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 메타 광고로 설치된 앱의 설치 리퍼러에 든 암호문(AES-256-GCM)을 푼다. 메타가 주는 data는 "본문 + 16바이트 태그"를 hex로 쓴
 * 것이라 JDK GCM 입력 형식 그대로 넣으면 된다.
 *
 * <p>키가 없거나 형식이 틀리면 복호화만 끄고 기동한다 — 키 하나 때문에 API가 뜨지 못하는 쪽이 더 나쁘다(FcmConfig와 같은 판단).
 * 키 문자열은 생성자에서 검증한 뒤 바이트로만 들고 있고, 실패 로그에는 예외 종류만 남긴다(예외 메시지에 입력이 섞일 수 있다).
 */
@Slf4j
@Component
public class MetaReferrerDecryptor {

    private static final Pattern KEY_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public MetaReferrerDecryptor(@Value("${app.acquisition.meta-install-referrer-key:}") String keyHex) {
        if (keyHex == null || !KEY_HEX.matcher(keyHex).matches()) {
            log.warn("app.acquisition.meta-install-referrer-key가 비어 있거나 hex 64자가 아니어서 메타 설치 정보를 풀지 않는다");
            this.key = null;
            return;
        }
        this.key = new SecretKeySpec(HexFormat.of().parseHex(keyHex), "AES");
    }

    public boolean enabled() {
        return key != null;
    }

    /** UTF-8 평문. 꺼져 있거나 어떤 이유로든 풀지 못하면 빈값. */
    public Optional<String> decrypt(String dataHex, String nonceHex) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            byte[] data = HexFormat.of().parseHex(dataHex);
            byte[] nonce = HexFormat.of().parseHex(nonceHex);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return Optional.of(new String(cipher.doFinal(data), UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            log.warn("메타 설치 정보를 풀지 못했다: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
