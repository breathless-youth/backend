package project.study.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.dto.PushText;
import project.study.push.repository.PushTokenRepository;

/**
 * 발송과 결과 처리 — 실제 firebase-admin이 만든 HTTP 요청을 가짜 전송 계층(MockHttpTransport)으로 받아 검사한다.
 * 토큰 문자열에 "dead"가 들어가면 UNREGISTERED, "bad"가 들어가면 INVALID_ARGUMENT로 답한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PushSenderTest {

    private static final PushMessage MESSAGE =
            new PushMessage(new PushText("제목", "본문"), new PushText("Title", "Body"), "/records", "test");

    @Autowired
    private PushTokenRepository pushTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 요청 본문 → 그 요청에 실린 토큰. 발송은 SDK 안에서 병렬로 나가서 동시성 맵을 쓴다. */
    private final Map<String, String> requests = new ConcurrentHashMap<>();

    private FirebaseApp app;
    private PushSender sender;

    @BeforeEach
    void setUp() {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.create(new AccessToken(
                        "test-access-token", Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))))
                .setProjectId("test-project")
                .setHttpTransport(new FakeFcmTransport())
                .build();
        app = FirebaseApp.initializeApp(options, "push-test-" + UUID.randomUUID());
        sender = new PushSender(pushTokenRepository, FirebaseMessaging.getInstance(app));
    }

    @AfterEach
    void tearDown() {
        app.delete();
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private String insertToken(Long userId, String prefix) {
        return insertToken(userId, prefix, "ko");
    }

    private String insertToken(Long userId, String prefix, String locale) {
        String token = prefix + "-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO push_token (user_id, token, platform, notifications_enabled, locale, created_at, updated_at)"
                        + " VALUES (?, ?, 'IOS', true, ?, now(), now())",
                userId,
                token,
                locale);
        return token;
    }

    /** 이 토큰으로 나간 요청 본문. */
    private String requestTo(String token) {
        return requests.entrySet().stream()
                .filter(e -> e.getValue().equals(token))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private boolean exists(String token) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM push_token WHERE token = ?", Long.class, token) == 1;
    }

    @Test
    void 대상_사용자들의_토큰_전부에_보내고_다른_사용자에게는_안_보낸다() {
        Long a = insertUser();
        Long b = insertUser();
        Long other = insertUser();
        String a1 = insertToken(a, "ok");
        String a2 = insertToken(a, "ok");
        String b1 = insertToken(b, "ok");
        String o1 = insertToken(other, "ok");

        PushResult result = sender.send(List.of(a, b), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(3, 0, 0));
        assertThat(requests.values()).containsExactlyInAnyOrder(a1, a2, b1).doesNotContain(o1);
    }

    @Test
    void 토큰이_501개면_FCM_호출_상한_500을_넘지_않게_나눠_전부_보낸다() {
        Long user = insertUser();
        IntStream.range(0, 501).forEach(i -> insertToken(user, "ok"));

        PushResult result = sender.send(List.of(user), MESSAGE);

        assertThat(result.sent()).isEqualTo(501);
        assertThat(requests).hasSize(501);
    }

    @Test
    void UNREGISTERED_토큰만_지우고_removed로_센다() {
        Long user = insertUser();
        String alive = insertToken(user, "ok");
        String dead = insertToken(user, "dead");

        PushResult result = sender.send(List.of(user), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(1, 0, 1));
        assertThat(exists(dead)).isFalse();
        assertThat(exists(alive)).isTrue();
    }

    @Test
    void INVALID_ARGUMENT는_우리_쪽_문제일_수_있어_지우지_않고_failed로_센다() {
        Long user = insertUser();
        String bad = insertToken(user, "bad");

        PushResult result = sender.send(List.of(user), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(0, 1, 0));
        assertThat(exists(bad)).isTrue();
    }

    @Test
    void 메시지에_제목_본문_link_type_analytics_label_iOS_소리가_실린다() {
        Long user = insertUser();
        insertToken(user, "ok");

        sender.send(List.of(user), MESSAGE);

        String body = requests.keySet().iterator().next();
        assertThat(body)
                .contains("\"title\":\"제목\"")
                .contains("\"body\":\"본문\"")
                .contains("\"link\":\"/records\"")
                .contains("\"type\":\"test\"")
                .contains("\"analytics_label\":\"test\"")
                .contains("\"sound\":\"default\"");
    }

    @Test
    void 한국어_기기는_한국어_문구를_받고_ko_KR도_한국어로_본다() {
        Long user = insertUser();
        String ko = insertToken(user, "ok", "ko");
        String koKr = insertToken(user, "ok", "ko-KR");

        sender.send(List.of(user), MESSAGE);

        assertThat(requestTo(ko)).contains("\"title\":\"제목\"").contains("\"body\":\"본문\"");
        assertThat(requestTo(koKr)).contains("\"title\":\"제목\"");
    }

    @Test
    void 한국어_판별은_대소문자를_가리지_않는다() {
        Long user = insertUser();
        String upper = insertToken(user, "ok", "KO");
        String mixed = insertToken(user, "ok", "Ko-KR");

        sender.send(List.of(user), MESSAGE);

        assertThat(requestTo(upper)).contains("\"title\":\"제목\"");
        assertThat(requestTo(mixed)).contains("\"title\":\"제목\"");
    }

    @Test
    void 한국어가_아닌_기기는_모두_영어_문구를_받는다() {
        Long user = insertUser();
        List<String> others =
                List.of(insertToken(user, "ok", "en"), insertToken(user, "ok", "ja"), insertToken(user, "ok", "fr"));

        sender.send(List.of(user), MESSAGE);

        others.forEach(token ->
                assertThat(requestTo(token)).contains("\"title\":\"Title\"").contains("\"body\":\"Body\""));
    }

    @Test
    void 한_사람의_기기가_두_언어면_언어마다_따로_보낸다() throws Exception {
        Long user = insertUser();
        insertToken(user, "ok", "ko");
        insertToken(user, "ok", "en");
        FirebaseMessaging messaging = spy(FirebaseMessaging.getInstance(app));

        PushResult result = new PushSender(pushTokenRepository, messaging).send(List.of(user), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(2, 0, 0));
        verify(messaging, times(2)).sendEachForMulticast(any());
    }

    @Test
    void 언어마다_500개씩_나눠_보낸다() {
        Long user = insertUser();
        IntStream.range(0, 501).forEach(i -> insertToken(user, "ok", "en"));
        String ko = insertToken(user, "ok", "ko");

        PushResult result = sender.send(List.of(user), MESSAGE);

        assertThat(result.sent()).isEqualTo(502);
        assertThat(requestTo(ko)).contains("\"title\":\"제목\"");
        assertThat(requests.keySet())
                .filteredOn(body -> body.contains("\"title\":\"Title\""))
                .hasSize(501);
    }

    @Test
    void 언어가_달라도_UNREGISTERED만_지우고_결과를_합친다() {
        Long user = insertUser();
        String koDead = insertToken(user, "dead", "ko");
        insertToken(user, "ok", "ko");
        String enDead = insertToken(user, "dead", "en");
        String enBad = insertToken(user, "bad", "en");
        insertToken(user, "ok", "en");

        PushResult result = sender.send(List.of(user), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(2, 1, 2));
        assertThat(exists(koDead)).isFalse();
        assertThat(exists(enDead)).isFalse();
        assertThat(exists(enBad)).isTrue();
    }

    @Test
    void 자격증명이_없으면_아무것도_보내지_않고_0을_돌려준다() {
        Long user = insertUser();
        insertToken(user, "ok");

        PushResult result = new PushSender(pushTokenRepository, null).send(List.of(user), MESSAGE);

        assertThat(result).isEqualTo(new PushResult(0, 0, 0));
        assertThat(requests).isEmpty();
    }

    /** FCM HTTP v1 messages:send를 흉내 낸다 — 오류 본문은 FCM이 실제로 주는 모양(details[].errorCode)이다. */
    private final class FakeFcmTransport extends MockHttpTransport {
        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest(url) {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    String body = getContentAsString();
                    String token = body.replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
                    requests.put(body, token);
                    if (token.startsWith("dead")) {
                        return error(404, "NOT_FOUND", "UNREGISTERED");
                    }
                    if (token.startsWith("bad")) {
                        return error(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT");
                    }
                    return new MockLowLevelHttpResponse()
                            .setContentType("application/json")
                            .setContent("{\"name\": \"projects/test-project/messages/" + UUID.randomUUID() + "\"}");
                }
            };
        }

        private static LowLevelHttpResponse error(int status, String grpcStatus, String fcmCode) {
            return new MockLowLevelHttpResponse()
                    .setStatusCode(status)
                    .setContentType("application/json")
                    .setContent("""
                            {"error": {"code": %d, "message": "test", "status": "%s",
                              "details": [{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                                           "errorCode": "%s"}]}}""".formatted(status, grpcStatus, fcmCode));
        }
    }

    @Test
    void 자격증명이_있을_때만_보낼_수_있다고_답한다() {
        assertThat(sender.isEnabled()).isTrue();
        assertThat(new PushSender(pushTokenRepository, null).isEnabled()).isFalse();
    }
}
