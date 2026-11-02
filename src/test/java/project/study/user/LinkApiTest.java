package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static project.study.user.LinkTestClient.sha256Hex;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.account.UserDataFixture;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** 소셜 로그인 link — 전환·병합·nonce와 동시 요청. 기기 등록과 토큰은 실제 API로 만들고, 프로바이더 검증만 목으로 바꾼다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDataFixture fixture;

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
    void 처음_쓰는_소셜_계정이면_같은_userId로_전환되고_isNewUser가_true다() {
        Device device = api.register();
        Social google = api.stubSocial(Provider.GOOGLE, null);

        MvcTestResult result = api.link(device.accessToken(), google, null);

        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.isNewUser")
                .isEqualTo(true);
        assertThat(api.subOf(api.read(result, LinkResponse.class).accessToken()))
                .isEqualTo(device.userId());
        assertThat(userRow(device.userId()))
                .containsEntry("provider", "GOOGLE")
                .containsEntry("provider_user_id", google.sub())
                .containsEntry("email", google.email());
    }

    @Test
    void 애플은_nonce_원문의_해시가_토큰_nonce와_같으면_전환된다() {
        Device device = api.register();
        String rawNonce = "nonce-" + UUID.randomUUID();
        Social apple = api.stubSocial(Provider.APPLE, sha256Hex(rawNonce));

        assertThat(api.link(device.accessToken(), apple, rawNonce))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.isNewUser")
                .isEqualTo(true);
        assertThat(userRow(device.userId())).containsEntry("provider", "APPLE");
    }

    @Test
    void 전환_뒤_옛_deviceId로_등록하면_새_비회원이_생긴다() {
        Device device = api.register();
        api.linkOk(device, api.stubSocial(Provider.GOOGLE, null));

        Device again = api.register(device.deviceId());

        assertThat(again.isNew()).isTrue();
        assertThat(again.userId()).isNotEqualTo(device.userId());
        assertThat(userRow(again.userId())).containsEntry("provider", "DEVICE");
    }

    @Test
    void 전환하면_이_기기의_이전_refresh는_쓸_수_없다() {
        Device device = api.register();
        LinkResponse linked = api.linkOk(device, api.stubSocial(Provider.KAKAO, null));

        assertThat(api.refresh(device.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.refresh(linked.refreshToken())).hasStatusOk();
    }

    @Test
    void 이미_있는_소셜_계정이면_기록을_합치고_그_계정의_토큰을_준다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        Device first = api.register();
        LinkResponse account = api.linkOk(first, google); // 남는 쪽의 다른 기기 refresh
        Device second = api.register();
        Instant start = Instant.parse("2030-03-01T01:00:00Z");
        long sessionId = fixture.seedSession(second.userId(), start, start.plusSeconds(1800));

        MvcTestResult result = api.link(second.accessToken(), google, null);

        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.isNewUser")
                .isEqualTo(false);
        LinkResponse merged = api.read(result, LinkResponse.class);
        assertThat(api.subOf(merged.accessToken())).isEqualTo(first.userId());
        assertThat(countUsers(second.userId())).isZero();
        assertThat(jdbc.sql("SELECT user_id FROM study_session WHERE id = :id")
                        .param("id", sessionId)
                        .query(Long.class)
                        .single())
                .isEqualTo(first.userId());
        assertThat(api.refresh(second.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.refresh(account.refreshToken())).hasStatusOk();
        assertThat(api.refresh(merged.refreshToken())).hasStatusOk();
    }

    @Test
    void 이미_소셜_계정이면_409_ALREADY_LINKED() {
        Device device = api.register();
        Social google = api.stubSocial(Provider.GOOGLE, null);
        LinkResponse linked = api.linkOk(device, google);

        assertThat(api.link(linked.accessToken(), api.stubSocial(Provider.GOOGLE, null), null))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("ALREADY_LINKED");
        assertThat(userRow(device.userId())).containsEntry("provider_user_id", google.sub());
    }

    @Test
    void 지워진_비회원의_토큰이면_404_USER_NOT_FOUND다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        api.linkOk(api.register(), google);
        Device merged = api.register();
        api.linkOk(merged, google);

        assertThat(api.link(merged.accessToken(), google, null))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void 검증_실패는_400_INVALID_SOCIAL_TOKEN이다() {
        Device device = api.register();
        Social forged = new Social(Provider.GOOGLE, "forged-" + UUID.randomUUID(), null, null);
        when(verifiers.verify(Provider.GOOGLE, forged.idToken()))
                .thenThrow(new InvalidOAuthTokenException("구글 ID 토큰 검증에 실패했습니다"));

        assertInvalidSocialToken(api.link(device.accessToken(), forged, null));
        assertThat(userRow(device.userId())).containsEntry("provider", "DEVICE");
    }

    @Test
    void 애플_nonce가_없으면_400() {
        Device device = api.register();
        Social apple = api.stubSocial(Provider.APPLE, null);

        assertInvalidSocialToken(api.link(device.accessToken(), apple, "nonce-" + UUID.randomUUID()));
        assertThat(userRow(device.userId())).containsEntry("provider", "DEVICE");
    }

    @Test
    void nonce가_다르면_400() {
        Device device = api.register();
        Social apple = api.stubSocial(Provider.APPLE, sha256Hex("right-nonce"));

        assertInvalidSocialToken(api.link(device.accessToken(), apple, "wrong-nonce"));
        assertInvalidSocialToken(api.link(device.accessToken(), apple, null));
        assertThat(userRow(device.userId())).containsEntry("provider", "DEVICE");
    }

    @Test
    void 요청_형식이_틀리면_400_VALIDATION_FAILED이고_토큰이_없으면_401이다() {
        Device device = api.register();
        List<String> bodies = List.of(
                "{\"idToken\":\"x\"}",
                "{\"provider\":\"GOOGLE\",\"idToken\":\" \"}",
                "{\"provider\":\"GOOGLE\",\"idToken\":\"" + "x".repeat(4097) + "\"}",
                "{\"provider\":\"GOOGLE\",\"idToken\":\"x\",\"nonce\":\"" + "n".repeat(129) + "\"}");
        for (String body : bodies) {
            assertThat(api.linkRequest(device.accessToken()).content(body))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("VALIDATION_FAILED");
        }
        // 모르는 프로바이더 문자열은 본문을 읽지 못한 것이다 — enum에 있는 DEVICE만 INVALID_SOCIAL_TOKEN(SocialTokenVerifiersTest)
        assertThat(api.linkRequest(device.accessToken()).content("{\"provider\":\"NAVER\",\"idToken\":\"x\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("BAD_REQUEST");
        assertThat(mvc.post()
                        .uri("/api/auth/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"idToken\":\"x\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 같은_기기의_link_두_번이_동시에_오면_하나만_성공하고_500은_없다() throws Exception {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        Device converting = api.register();
        // 전환 경로: 나중 쪽은 잠금을 기다린 뒤 이미 소셜 계정이 된 행을 본다
        assertThat(LinkTestClient.concurrently(() -> api.link(converting.accessToken(), google, null)))
                .containsExactlyInAnyOrder(200, 409);

        Device merging = api.register();
        // 병합 경로: 나중 쪽은 잠금을 기다린 뒤 이미 지워진 행을 본다
        assertThat(LinkTestClient.concurrently(() -> api.link(merging.accessToken(), google, null)))
                .containsExactlyInAnyOrder(200, 404);
        assertThat(countUsers(merging.userId())).isZero();
    }

    @Test
    void 두_기기가_같은_소셜_계정으로_동시에_전환해도_둘_다_200이고_한_계정이_된다() throws Exception {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        Device a = api.register();
        Device b = api.register();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // a의 refresh 행을 잡아 a의 전환을 "users 행은 바꿨지만 커밋 전"에 세운다. 그동안 b도 소셜 ID가 비었다고 보고 전환하다
            // unique 인덱스에서 a를 기다린다. 풀면 a가 커밋하고, b는 위반으로 져서 새 트랜잭션의 병합 경로로 다시 한다
            AtomicLong holderPid = new AtomicLong();
            Future<?> holder =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        holderPid.set(jdbc.sql("SELECT pg_backend_pid()")
                                .query(Long.class)
                                .single());
                        jdbc.sql("SELECT id FROM refresh_token WHERE user_id = :u FOR UPDATE")
                                .param("u", a.userId())
                                .query()
                                .listOfRows();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcTestResult> first = pool.submit(() -> api.link(a.accessToken(), google, null));
            long firstPid = awaitBlockedBy(holderPid.get()); // a가 holder를 기다린다
            Future<MvcTestResult> second = pool.submit(() -> api.link(b.accessToken(), google, null));
            awaitBlockedBy(firstPid); // b가 unique 인덱스에서 a를 기다린다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            LinkResponse converted = api.okBody(first.get(10, TimeUnit.SECONDS));
            LinkResponse merged = api.okBody(second.get(10, TimeUnit.SECONDS));
            assertThat(converted.isNewUser()).isTrue();
            assertThat(merged.isNewUser()).isFalse();
            assertThat(api.subOf(converted.accessToken())).isEqualTo(a.userId());
            assertThat(api.subOf(merged.accessToken())).isEqualTo(a.userId());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT id FROM users WHERE provider = 'GOOGLE' AND provider_user_id = :s")
                        .param("s", google.sub())
                        .query(Long.class)
                        .list())
                .containsExactly(a.userId());
        assertThat(countUsers(b.userId())).isZero();
    }

    private static void assertInvalidSocialToken(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_SOCIAL_TOKEN");
    }

    // blocker를 기다리는 백엔드가 생길 때까지 기다려 그 pid를 돌려준다. 이 테스트가 잡은 잠금에서 시작한 사슬만 보므로, 다른 작업의
    // 잠금 대기가 있어도 일찍 풀지 않는다
    private long awaitBlockedBy(long blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            List<Long> blocked = jdbc.sql(
                            "SELECT pid FROM pg_stat_activity WHERE :blocker = ANY(pg_blocking_pids(pid))")
                    .param("blocker", (int) blockerPid)
                    .query(Long.class)
                    .list();
            if (!blocked.isEmpty()) {
                return blocked.get(0);
            }
            assertThat(System.nanoTime()).as("pid %d를 기다리는 백엔드", blockerPid).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> userRow(long userId) {
        return jdbc.sql("SELECT provider, provider_user_id, email FROM users WHERE id = :id")
                .param("id", userId)
                .query()
                .singleRow();
    }

    private long countUsers(long userId) {
        return jdbc.sql("SELECT count(*) FROM users WHERE id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }
}
