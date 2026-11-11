package project.study.point.service;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 구글의 키 서버에서 받는다. 공유 빌더에는 타임아웃이 없어 clone()해 얹는다(SocialProviderHttpConfig와 같은 이유). */
@Component
public class HttpAdMobKeySource implements AdMobKeySource {

    private static final String KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public HttpAdMobKeySource(RestClient.Builder restClientBuilder) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient =
                restClientBuilder.clone().requestFactory(requestFactory).build();
    }

    @Override
    public Map<Long, PublicKey> fetch() {
        Keys body = restClient.get().uri(KEYS_URL).retrieve().body(Keys.class);
        Map<Long, PublicKey> keys = new HashMap<>();
        if (body == null || body.keys() == null) {
            return keys;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            for (Key key : body.keys()) {
                keys.put(
                        key.keyId(),
                        factory.generatePublic(
                                new X509EncodedKeySpec(Base64.getDecoder().decode(key.base64()))));
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("AdMob 공개 키를 읽지 못했습니다", e);
        }
        return keys;
    }

    record Keys(List<Key> keys) {}

    record Key(long keyId, String base64) {}
}
