package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.user.LinkTestClient.sha256Hex;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import project.study.TestcontainersConfiguration;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** link의 ID 토큰 1회 사용 (ADR-0041) — 새어 나간 토큰을 다른 비회원이 다시 넣어 그 계정 토큰을 받지 못하게 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkTokenReplayApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private SocialTokenVerifiers verifiers;

    private LinkTestClient api;

    @BeforeEach
    void setUp() {
        api = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers);
    }

    @Test
    void 같은_ID_토큰으로_두_번_link하면_두_번째는_400이다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        String idToken = LinkTestClient.freshIdToken(google);
        Device owner = api.register();
        assertThat(api.linkWithIdToken(owner.accessToken(), Provider.GOOGLE, idToken, null))
                .hasStatusOk();
        Device attacker = api.register();

        assertInvalidSocialToken(api.linkWithIdToken(attacker.accessToken(), Provider.GOOGLE, idToken, null));
        assertThat(provider(attacker.userId())).isEqualTo("DEVICE");
        // 원문이 아니라 해시만 남는다
        assertThat(usedRows(sha256Hex(idToken))).isEqualTo(1);
        assertThat(usedRows(idToken)).isZero();
        // 같은 계정이라도 새로 받은 토큰이면 병합된다
        assertThat(api.link(attacker.accessToken(), google, null)).hasStatusOk();
    }

    @Test
    void 같은_ID_토큰이_동시에_두_번_오면_하나만_성공하고_나머지는_400이다() throws Exception {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        String idToken = LinkTestClient.freshIdToken(google);
        Device device = api.register();

        // 나중 쪽은 먼저 쪽의 사용 기록을 기본 키에서 기다렸다가 0행을 받는다
        assertThat(LinkTestClient.concurrently(
                        () -> api.linkWithIdToken(device.accessToken(), Provider.GOOGLE, idToken, null)))
                .containsExactlyInAnyOrder(200, 400);
    }

    @Test
    void 전환_경쟁으로_재시도해도_같은_토큰을_쓸_수_있다() throws Exception {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        String tokenA = LinkTestClient.freshIdToken(google);
        String tokenB = LinkTestClient.freshIdToken(google);
        Device a = api.register();
        Device b = api.register();

        // b의 첫 시도는 unique 인덱스에서 져서 롤백된다 — 사용 기록도 함께 롤백돼, 같은 토큰으로 한 재시도가 병합으로 성공한다
        ConversionRace.Results results = ConversionRace.run(
                transactionManager,
                jdbc,
                a.userId(),
                () -> api.linkWithIdToken(a.accessToken(), Provider.GOOGLE, tokenA, null),
                () -> api.linkWithIdToken(b.accessToken(), Provider.GOOGLE, tokenB, null));

        assertThat(api.okBody(results.first()).isNewUser()).isTrue();
        LinkResponse merged = api.okBody(results.second());
        assertThat(merged.isNewUser()).isFalse();
        assertThat(api.subOf(merged.accessToken())).isEqualTo(a.userId());
        assertThat(usedRows(sha256Hex(tokenA))).isEqualTo(1);
        assertThat(usedRows(sha256Hex(tokenB))).isEqualTo(1);
    }

    @Test
    void 만료된_사용_기록은_지워진다() {
        String expired = "expired-" + UUID.randomUUID();
        String live = "live-" + UUID.randomUUID();
        insertUsed(expired, Instant.now().minusSeconds(60));
        insertUsed(live, Instant.now().plusSeconds(3600));

        assertThat(api.link(api.register().accessToken(), api.stubSocial(Provider.KAKAO, null), null))
                .hasStatusOk();

        assertThat(usedRows(expired)).isZero();
        assertThat(usedRows(live)).isEqualTo(1);
    }

    private void insertUsed(String tokenHash, Instant expiresAt) {
        jdbc.sql("INSERT INTO used_social_id_token (token_hash, expires_at) VALUES (:h, :e)")
                .param("h", tokenHash)
                .param("e", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private long usedRows(String tokenHash) {
        return jdbc.sql("SELECT count(*) FROM used_social_id_token WHERE token_hash = :h")
                .param("h", tokenHash)
                .query(Long.class)
                .single();
    }

    private String provider(long userId) {
        return jdbc.sql("SELECT provider FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private static void assertInvalidSocialToken(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_SOCIAL_TOKEN");
    }
}
