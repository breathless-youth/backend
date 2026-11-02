package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static project.study.support.AuthTestSupport.TOKEN_API_VERSION;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.config.ApiVersionConfig;
import project.study.user.dto.LinkResponse;
import project.study.user.dto.LinkSocialRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/**
 * link 테스트용 HTTP 도우미 — 기기 등록·토큰은 실제 API로 만들고, 소셜 검증은 목으로 둔 {@link SocialTokenVerifiers}에 심는다.
 * MockMvcTester가 있는 컨텍스트에서만 쓰므로 빈이 아니라 테스트가 직접 만든다.
 */
final class LinkTestClient {

    record Device(long userId, String deviceId, boolean isNew, String accessToken, String refreshToken) {}

    record Social(Provider provider, String idToken, String sub, String email) {}

    private final MockMvcTester mvc;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final SocialTokenVerifiers verifiers;

    LinkTestClient(MockMvcTester mvc, ObjectMapper objectMapper, JwtUtil jwtUtil, SocialTokenVerifiers verifiers) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
        this.verifiers = verifiers;
    }

    Device register() {
        return register(UUID.randomUUID().toString());
    }

    Device register(String deviceId) {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .header(ApiVersionConfig.HEADER, TOKEN_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}")
                .exchange();
        assertThat(result).hasStatus2xxSuccessful();
        UserRegisterResponse body = read(result, UserRegisterResponse.class);
        return new Device(subOf(body.accessToken()), deviceId, body.isNew(), body.accessToken(), body.refreshToken());
    }

    /** 처음 보는 소셜 ID를 하나 만들어 그 ID 토큰의 검증 결과를 심는다. nonceClaim은 ID 토큰의 nonce 클레임(없으면 null). */
    Social stubSocial(Provider provider, String nonceClaim) {
        String sub = provider.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
        Social social = new Social(provider, "id-token-" + sub, sub, sub + "@example.com");
        when(verifiers.verify(provider, social.idToken()))
                .thenReturn(new OAuthUserInfo(provider, sub, social.email(), nonceClaim));
        return social;
    }

    /** 새 경로라 기본버전 1이다 — 실제 Bearer 토큰을 싣는다. */
    MockMvcTester.MockMvcRequestBuilder linkRequest(String accessToken) {
        return mvc.post()
                .uri("/api/auth/link")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MvcTestResult link(String accessToken, Social social, String nonce) {
        String body =
                objectMapper.writeValueAsString(new LinkSocialRequest(social.provider(), social.idToken(), nonce));
        return linkRequest(accessToken).content(body).exchange();
    }

    LinkResponse linkOk(Device device, Social social) {
        return okBody(link(device.accessToken(), social, null));
    }

    LinkResponse okBody(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return read(result, LinkResponse.class);
    }

    MvcTestResult refresh(String refreshToken) {
        return mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")
                .exchange();
    }

    long subOf(String accessToken) {
        return Long.parseLong(jwtUtil.getUserId(accessToken));
    }

    <T> T read(MvcTestResult result, Class<T> type) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }

    /** 같은 요청 둘을 한꺼번에 출발시켜 상태 코드를 모은다. */
    static List<Integer> concurrently(Callable<MvcTestResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS).getResponse().getStatus());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 앱이 SDK에 넘기는 값 — nonce 원문의 SHA-256 소문자 hex. 제품 코드와 따로 계산해 맞춰 본다. */
    static String sha256Hex(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
