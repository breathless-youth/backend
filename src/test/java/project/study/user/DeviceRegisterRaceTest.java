package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import project.study.user.service.UserService;
import tools.jackson.databind.ObjectMapper;

/**
 * 기기 등록(POST /api/users)과 link가 겹칠 때. 등록과 발급은 트랜잭션이 따로라, 등록이 커밋된 뒤 발급 전에 link가 그 비회원을 소셜 계정으로
 * 바꿀 수 있다. 그때 deviceId만으로 소셜 계정의 토큰이 나가면 안 된다 — 그 틈을 등록 커밋 직후에 멈춰 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeviceRegisterRaceTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private SocialTokenVerifiers verifiers;

    @MockitoSpyBean
    private UserService userService;

    private LinkTestClient api;

    private final CountDownLatch registered = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        api = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers);
    }

    @Test
    void 등록과_발급_사이에_전환되면_새_비회원의_토큰을_받고_소셜_계정의_토큰은_그대로다() throws Exception {
        Device device = api.register();
        Social google = api.stubSocial(Provider.GOOGLE, null);
        pauseNextRegisterAfterCommit();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Device> again = pool.submit(() -> api.register(device.deviceId()));
            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            // 등록은 이 비회원을 찾아 커밋했고 발급은 아직이다 — 이 틈에 전환이 커밋된다
            LinkResponse social = api.linkOk(device, google);
            proceed.countDown();

            Device registeredAgain = again.get(10, TimeUnit.SECONDS);

            assertThat(registeredAgain.userId()).isNotEqualTo(device.userId());
            assertThat(registeredAgain.isNew()).isTrue();
            assertThat(userRow(registeredAgain.userId()))
                    .containsEntry("provider", "DEVICE")
                    .containsEntry("provider_user_id", device.deviceId());
            assertThat(api.refresh(social.refreshToken())).hasStatusOk();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 등록과_발급_사이에_병합되면_500_없이_새_비회원의_토큰을_받는다() throws Exception {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        Device account = api.register();
        api.linkOk(account, google);
        Device device = api.register();
        pauseNextRegisterAfterCommit();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Device> again = pool.submit(() -> api.register(device.deviceId()));
            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            // 이 틈에 병합이 커밋돼 등록이 찾은 행이 사라진다
            LinkResponse merged = api.linkOk(device, google);
            proceed.countDown();

            Device registeredAgain = again.get(10, TimeUnit.SECONDS);

            assertThat(registeredAgain.userId()).isNotIn(device.userId(), account.userId());
            assertThat(registeredAgain.isNew()).isTrue();
            assertThat(api.refresh(merged.refreshToken())).hasStatusOk();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    // 다음 등록 한 번만 커밋 직후에 멈춘다. 그 뒤의 등록(재등록 포함)은 그대로 지나간다
    private void pauseNextRegisterAfterCommit() {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    if (first.getAndSet(false)) {
                        registered.countDown();
                        proceed.await(10, TimeUnit.SECONDS);
                    }
                    return result;
                })
                .when(userService)
                .register(any());
    }

    private Map<String, Object> userRow(long userId) {
        return jdbc.sql("SELECT provider, provider_user_id FROM users WHERE id = :id")
                .param("id", userId)
                .query()
                .singleRow();
    }
}
