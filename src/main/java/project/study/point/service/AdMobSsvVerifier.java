package project.study.point.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AdMob 광고 보상 콜백(서버 확인)의 서명을 본다. 쿼리의 마지막 두 값은 늘 signature, key_id 순서이고, 서명한 내용은 그 앞의 쿼리 문자열
 * 그대로다(순서·인코딩을 건드리지 않는다). 서명은 SHA-256 ECDSA(DER)를 base64url로 적은 것이다.
 *
 * <p>공개 키는 바뀌므로 받은 지 24시간이 넘은 것은 쓰지 않는다. 모르는 키 ID가 오거나 키가 오래됐으면 다시 받되, 가짜 요청이 키 서버를
 * 두드리지 못하게 성공·실패와 상관없이 몇 초에 한 번만 시도한다. 구글은 실패한 콜백을 1초 간격으로 다섯 번 다시 보내므로 그 안에 한 번은
 * 다시 받는다. 쓸 수 있는 키가 없으면 예외(500)로 답해 구글이 다시 보내게 한다. 키를 받는 동안 다른 요청을 세워 두지 않는다.
 */
@Slf4j
@Component
public class AdMobSsvVerifier {

    private static final String SIGNATURE_PARAM = "&signature=";
    private static final String KEY_ID_PARAM = "&key_id=";
    private static final Duration KEY_TTL = Duration.ofHours(24);
    private static final Duration ATTEMPT_GAP = Duration.ofSeconds(3);

    private final AdMobKeySource keySource;
    private final Clock clock;

    private volatile Loaded loaded = new Loaded(Map.of(), Instant.EPOCH);
    private Instant attemptedAt = Instant.EPOCH;

    public AdMobSsvVerifier(AdMobKeySource keySource, Clock clock) {
        this.keySource = keySource;
        this.clock = clock;
    }

    /** 서명이 맞으면 서명된 값들을, 아니면 빈 값을 준다. */
    public Optional<Map<String, String>> verify(String rawQuery) {
        if (rawQuery == null) {
            return Optional.empty();
        }
        int signatureAt = rawQuery.lastIndexOf(SIGNATURE_PARAM);
        int keyIdAt = rawQuery.lastIndexOf(KEY_ID_PARAM);
        if (signatureAt < 0 || keyIdAt < signatureAt) {
            return Optional.empty();
        }
        String content = rawQuery.substring(0, signatureAt);
        try {
            byte[] signature =
                    Base64.getUrlDecoder().decode(rawQuery.substring(signatureAt + SIGNATURE_PARAM.length(), keyIdAt));
            long keyId = Long.parseLong(rawQuery.substring(keyIdAt + KEY_ID_PARAM.length()));
            PublicKey key = keyFor(keyId);
            if (key == null) {
                return Optional.empty();
            }
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initVerify(key);
            ecdsa.update(content.getBytes(StandardCharsets.UTF_8));
            return ecdsa.verify(signature) ? Optional.of(params(content)) : Optional.empty();
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // 잘못된 base64·숫자·서명 형식. 서명이 틀린 요청과 같이 다룬다
            return Optional.empty();
        }
    }

    private PublicKey keyFor(long keyId) {
        Instant now = clock.instant();
        Loaded current = loaded;
        if (current.freshAt(now) && current.keys().containsKey(keyId)) {
            return current.keys().get(keyId);
        }
        if (claimAttempt(now)) {
            try {
                loaded = new Loaded(Map.copyOf(keySource.fetch()), now);
            } catch (RuntimeException e) {
                // 실패는 들고 있는 키의 수명을 늘리지 않는다
                log.warn("AdMob 공개 키를 받지 못했습니다: {}", e.getClass().getSimpleName());
            }
        }
        current = loaded;
        if (!current.freshAt(now)) {
            throw new IllegalStateException("쓸 수 있는 AdMob 공개 키가 없습니다");
        }
        return current.keys().get(keyId);
    }

    private synchronized boolean claimAttempt(Instant now) {
        if (Duration.between(attemptedAt, now).compareTo(ATTEMPT_GAP) < 0) {
            return false;
        }
        attemptedAt = now;
        return true;
    }

    private record Loaded(Map<Long, PublicKey> keys, Instant at) {
        boolean freshAt(Instant now) {
            return Duration.between(at, now).compareTo(KEY_TTL) <= 0;
        }
    }

    private static Map<String, String> params(String content) {
        Map<String, String> params = new HashMap<>();
        for (String pair : content.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
