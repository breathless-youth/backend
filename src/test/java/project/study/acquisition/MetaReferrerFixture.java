package project.study.acquisition;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 메타 문서의 설치 정보 암호화 예시 값과, 같은 키로 임의 페이로드를 암호화하는 도우미. */
public final class MetaReferrerFixture {

    public static final String KEY = "2575590594a9cd809e5bfacf397f8c1ac730dbc38a3e137ecd1ab66591c8c3c9";
    public static final String NONCE = "b7203c6a6fb633d16e9cf5c1";
    public static final String CIPHER =
            "afe56cf6228c6ea8c79da49186e718e92a579824596ae1d0d4d20d7793dca797bd4034ccf467bfae5c79a3981e7a2968c41949237e2b2db678c1c3d39c9ae564c5cafd52f2b77a3dc77bf1bae063114d0283b97417487207735da31ddc1531d5645a9c3e602c195a0ebf69c272aa5fda3a2d781cb47e117310164715a54c7a5a032740584e2789a7b4e596034c16425139a77e507c492b629c848573c714a03a2e7d25b9459b95842332b460f3682d19c35dbc7d53e3a51e0497ff6a6cbb367e760debc4194ae097498108df7b95eac2fa9bac4320077b510be3b7b823248bfe02ae501d9fe4ba179c7de6733c92bf89d523df9e31238ef497b9db719484cbab7531dbf6c5ea5a8087f95d59f5e4f89050e0f1dc03e464168ad76a64cca64b79";
    public static final String PLAIN =
            "{\"ad_id\":\"12345\",\"ad_objective_name\":\"APP_INSTALLS\",\"adgroup_id\":\"54321\",\"adgroup_name\":\"Adams Test Ad Group\",\"campaign_id\":\"987654\",\"campaign_name\":\"Adams Test Campaign\",\"campaign_group_id\":\"654321\",\"campaign_group_name\":\"Adams Test Campaign Group\",\"account_id\":\"444444\"}";

    private MetaReferrerFixture() {}

    /** KEY·NONCE로 AES/GCM 암호화해 hex(본문 + 16바이트 태그)를 돌려준다. */
    public static String encrypt(String plainJson) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(HexFormat.of().parseHex(KEY), "AES"),
                    new GCMParameterSpec(128, HexFormat.of().parseHex(NONCE)));
            return HexFormat.of().formatHex(cipher.doFinal(plainJson.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Play 설치 리퍼러 문자열 형태의 메타 값을 만든다. */
    public static String metaReferrer(String cipherHex, String nonceHex) {
        String json = "{\"app\":1234567890,\"t\":1595425811,\"source\":{\"data\":\"" + cipherHex + "\",\"nonce\":\""
                + nonceHex + "\"}}";
        return "utm_source=apps.facebook.com&utm_campaign=fb4a&utm_content=" + URLEncoder.encode(json, UTF_8);
    }
}
