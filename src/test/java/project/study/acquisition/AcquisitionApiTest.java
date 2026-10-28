package project.study.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.acquisition.MetaReferrerFixture.CIPHER;
import static project.study.acquisition.MetaReferrerFixture.KEY;
import static project.study.acquisition.MetaReferrerFixture.NONCE;
import static project.study.acquisition.MetaReferrerFixture.metaReferrer;
import static project.study.support.AuthTestSupport.asUser;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import tools.jackson.databind.json.JsonMapper;

/** 첫 유입 경로 저장 API — 판별 결과 저장, 첫 값 유지, 동시 첫 요청, 검증. 트랜잭션을 묶지 않아 실제 커밋으로 확인한다. */
@SpringBootTest(properties = "app.acquisition.meta-install-referrer-key=" + MetaReferrerFixture.KEY)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AcquisitionApiTest {

    private static final String URI = "/api/users/me/acquisition";
    private static final String API_VERSION = "1";
    private static final String CAPTURED_AT = "2026-10-29T10:12:00+09:00";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult post(Long asUserId, String body) {
        return mvc.post()
                .uri(URI)
                .header(ApiVersionConfig.HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(asUserId))
                .exchange();
    }

    private static String body(String platform, String installReferrer, String deepLinkAd) {
        Map<String, Object> map = new HashMap<>();
        map.put("platform", platform);
        map.put("installReferrer", installReferrer);
        map.put("deepLinkAd", deepLinkAd);
        map.put("capturedAt", CAPTURED_AT);
        return JSON.writeValueAsString(map);
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM user_acquisition WHERE user_id = ?", id);
    }

    @Test
    void 메타_광고를_풀어서_저장하고_돌려준다(CapturedOutput output) {
        assertThat(post(userId, body("ANDROID", metaReferrer(CIPHER, NONCE), "aos_v3")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.source").isEqualTo("META_AD");
                    assertThat(json).extractingPath("$.metaAd.campaignId").isEqualTo("654321");
                    assertThat(json).extractingPath("$.metaAd.adsetId").isEqualTo("987654");
                    assertThat(json).extractingPath("$.metaAd.adId").isEqualTo("54321");
                    assertThat(json).extractingPath("$.metaAd.label").isEqualTo("aos_v3");
                    assertThat(json).extractingPath("$.firstTouchAt").isEqualTo("2026-10-29T01:12:00Z");
                });
        assertThat(row(userId))
                .containsEntry("meta_ad_name", "Adams Test Ad Group")
                .containsEntry("utm_campaign", "fb4a");
        assertThat(output).doesNotContain(KEY);
    }

    @Test
    void 친구초대를_저장한다() {
        assertThat(post(userId, body("ANDROID", "code=0123", null)))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.source").isEqualTo("INVITE");
                    assertThat(json).extractingPath("$.inviteCode").isEqualTo("0123");
                    assertThat(json).extractingPath("$.metaAd").isNull();
                });
        assertThat(row(userId)).containsEntry("source", "INVITE").containsEntry("invite_code", "0123");
    }

    @Test
    void 플레이_오가닉을_저장한다() {
        assertThat(post(userId, body("ANDROID", "utm_source=google-play&utm_medium=organic", null)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.source")
                .isEqualTo("ORGANIC");
        assertThat(row(userId)).containsEntry("utm_source", "google-play");
    }

    @Test
    void 그밖의_링크를_저장한다() {
        assertThat(post(userId, body("ANDROID", "utm_source=blog&utm_medium=post&utm_campaign=spring", null)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.source")
                .isEqualTo("OTHER_LINK");
        assertThat(row(userId))
                .containsEntry("utm_source", "blog")
                .containsEntry("utm_medium", "post")
                .containsEntry("utm_campaign", "spring");
    }

    @Test
    void iOS_딥링크만_있으면_라벨만() {
        assertThat(post(userId, body("IOS", null, "ios_v2")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.source").isEqualTo("META_AD");
                    assertThat(json).extractingPath("$.metaAd.label").isEqualTo("ios_v2");
                    assertThat(json).extractingPath("$.metaAd.adId").isNull();
                });
    }

    @Test
    void 빈_문자열은_없음과_같다() {
        assertThat(post(userId, body("ANDROID", "", "  ")))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.source")
                .isEqualTo("UNKNOWN");
        assertThat(row(userId)).containsEntry("install_referrer", null).containsEntry("deep_link_ad", null);
    }

    @Test
    void 두번째_요청은_첫값을_바꾸지_않는다() {
        post(userId, body("ANDROID", "code=1234", null));

        assertThat(post(userId, body("ANDROID", "utm_source=google-play&utm_medium=organic", null)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.source")
                .isEqualTo("INVITE");
        assertThat(row(userId)).containsEntry("install_referrer", "code=1234");
    }

    @Test
    void 복호화에_실패해도_200이고_원문이_남는다(CapturedOutput output) {
        String referrer = metaReferrer("00" + CIPHER.substring(2), NONCE); // 태그 검증 실패

        assertThat(post(userId, body("ANDROID", referrer, null)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.source")
                .isEqualTo("META_AD");
        assertThat(row(userId)).containsEntry("install_referrer", referrer).containsEntry("meta_ad_id", null);
        assertThat(output).contains("AEADBadTagException").doesNotContain(KEY);
    }

    @Test
    void 동시_첫요청도_한행이고_같은응답() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<MvcTestResult> a = pool.submit(() -> {
                start.await();
                return post(userId, body("ANDROID", "code=1111", null));
            });
            Future<MvcTestResult> b = pool.submit(() -> {
                start.await();
                return post(userId, body("ANDROID", "code=2222", null));
            });
            start.countDown();

            MvcTestResult ra = a.get();
            MvcTestResult rb = b.get();
            assertThat(ra).hasStatusOk();
            assertThat(rb).hasStatusOk();
            Object codeA = JSON.readTree(ra.getResponse().getContentAsString())
                    .get("inviteCode")
                    .asString();
            Object codeB = JSON.readTree(rb.getResponse().getContentAsString())
                    .get("inviteCode")
                    .asString();
            assertThat(codeA).isEqualTo(codeB);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM user_acquisition WHERE user_id = ?", Long.class, userId))
                .isEqualTo(1L);
    }

    @Test
    void platform이_없거나_틀리면_400() {
        assertThat(post(userId, "{\"capturedAt\": \"" + CAPTURED_AT + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(userId, body("WEB", "code=1234", null))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void capturedAt이_없으면_400() {
        assertThat(post(userId, "{\"platform\": \"ANDROID\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void installReferrer가_4096자를_넘으면_400() {
        assertThat(post(userId, body("ANDROID", "a".repeat(4097), null))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401() {
        assertThat(mvc.post()
                        .uri(URI)
                        .header(ApiVersionConfig.HEADER, API_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "code=1234", null)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 스웨거에_경로가_버전1로_실린다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/users/me/acquisition'].post.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                .hasPathSatisfying(
                        "$.paths['/api/users/me/acquisition'].post.requestBody",
                        v -> assertThat(v).isNotNull());
    }
}
