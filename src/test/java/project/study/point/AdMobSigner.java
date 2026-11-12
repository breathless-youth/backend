package project.study.point;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** 테스트에서 AdMob처럼 콜백 쿼리에 서명한다. */
public final class AdMobSigner {

    public static final long KEY_ID = 3335741209L;

    private final KeyPair keyPair;

    public AdMobSigner() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public String content(String adUnit, String userId, String transactionId) {
        return "ad_network=5450213213286189855&ad_unit=" + adUnit
                + "&custom_data=&reward_amount=1&reward_item=point&timestamp=1762900000000&transaction_id="
                + transactionId + "&user_id=" + userId;
    }

    /** 서명과 키 ID를 붙인 쿼리 문자열. */
    public String signed(String content) {
        try {
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(keyPair.getPrivate());
            ecdsa.update(content.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(ecdsa.sign());
            return content + "&signature=" + signature + "&key_id=" + KEY_ID;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
