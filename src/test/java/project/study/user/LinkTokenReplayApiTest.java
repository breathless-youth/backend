package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static project.study.user.LinkTestClient.sha256Hex;
import static project.study.user.LinkTestClient.usedKey;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** link의 ID 토큰 1회 사용 (ADR-0043) — 새어 나간 토큰을 다른 비회원이 다시 넣어 그 계정 토큰을 받지 못하게 한다. */
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
        assertThat(usedRows(usedKey(idToken))).isEqualTo(1);
        assertThat(usedRows(idToken)).isZero();
        // 같은 계정이라도 새로 받은 토큰이면 병합된다
        assertThat(api.link(attacker.accessToken(), google, null)).hasStatusOk();
    }

    @Test
    void 서명_부분만_다르게_쓴_같은_토큰도_두_번째는_400이다() {
        Social apple = api.stubSocial(Provider.APPLE, sha256Hex("raw-nonce"));
        String idToken = LinkTestClient.freshIdToken(apple);
        Device owner = api.register();
        assertThat(api.linkWithIdToken(owner.accessToken(), Provider.APPLE, idToken, "raw-nonce"))
                .hasStatusOk();
        Device attacker = api.register();

        // base64url의 남는 비트나 패딩으로 같은 서명을 다른 문자열로 쓴 토큰 — 서명 검증은 통과하므로 서명 부분은 키에 넣지 않는다
        String reEncoded = idToken + "=";
        assertInvalidSocialToken(api.linkWithIdToken(attacker.accessToken(), Provider.APPLE, reEncoded, "raw-nonce"));
        assertThat(provider(attacker.userId())).isEqualTo("DEVICE");
    }

    @Test
    void 사용_기록은_토큰_만료_5분_뒤까지_둔다() {
        String sub = "kakao-" + UUID.randomUUID();
        String idToken = "id-token-" + sub + ".1.signature";
        Instant exp = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        when(verifiers.verify(eq(Provider.KAKAO), eq(idToken)))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, sub, null, null, exp));

        assertThat(api.linkWithIdToken(api.register().accessToken(), Provider.KAKAO, idToken, null))
                .hasStatusOk();

        // 검증하는 쪽(프로바이더 서버) 시계가 DB보다 늦으면 exp가 조금 지나도 토큰이 받힌다 — 그 사이에 기록이 지워지면 다시 쓸 수 있다
        assertThat(usedExpiresAt(usedKey(idToken))).isEqualTo(exp.plus(Duration.ofMinutes(5)));
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
        assertThat(usedRows(usedKey(tokenA))).isEqualTo(1);
        assertThat(usedRows(usedKey(tokenB))).isEqualTo(1);
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

    @Test
    void link가_404로_끝나면_ID_토큰을_쓰지_않아_다시_등록한_뒤_같은_토큰으로_로그인된다() {
        // 앱은 404면 다시 등록한다 — 그때 SDK 로그인을 다시 하지 않고 받아 둔 토큰을 그대로 보내도 된다
        Social google = api.stubSocial(Provider.GOOGLE, null);
        api.linkOk(api.register(), google);
        Device merged = api.register();
        api.linkOk(merged, google);
        String idToken = LinkTestClient.freshIdToken(google);

        assertThat(api.linkWithIdToken(merged.accessToken(), Provider.GOOGLE, idToken, null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(usedRows(usedKey(idToken))).isZero();

        Device again = api.register();
        assertThat(api.linkWithIdToken(again.accessToken(), Provider.GOOGLE, idToken, null))
                .hasStatusOk();
    }

    @Test
    void link가_409로_끝나도_ID_토큰을_쓰지_않는다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        LinkResponse linked = api.linkOk(api.register(), google);
        String idToken = LinkTestClient.freshIdToken(google);

        assertThat(api.linkWithIdToken(linked.accessToken(), Provider.GOOGLE, idToken, null))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(usedRows(usedKey(idToken))).isZero();
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

    private Instant usedExpiresAt(String tokenHash) {
        return jdbc.sql("SELECT expires_at FROM used_social_id_token WHERE token_hash = :h")
                .param("h", tokenHash)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
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
