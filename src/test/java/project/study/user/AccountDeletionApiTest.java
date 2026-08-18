package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.repository.StudySessionRepository;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.oauth.GoogleTokenVerifier;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountDeletionApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudySessionRepository studySessionRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        when(googleTokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    }

    @Test
    void 탈퇴하면_유저와_세션이_삭제되고_refresh가_전량_폐기된다() {
        // 유저 한 명에게 refresh 토큰이 여러 개(다기기) 있어도 탈퇴 시 전부 폐기됨을 검증한다.
        // 기기 A: 익명 등록 후 소셜 전환 — 전환은 userId를 유지하고 옛 device 토큰을 지우지 않으므로
        // deviceA.refreshToken()이 전환 후에도 그대로 유효하게 남는다.
        // 기기 B: 같은 소셜 계정으로 link(병합) — 병합이 새로 발급한 토큰을 받는다.
        // 결과적으로 같은 최종 유저(deviceA.userId())에 서로 다른 두 refresh 토큰이 걸린다.
        stubVerifier("sub-multi-device");
        UserRegisterResponse deviceA = registerDevice();
        link(deviceA.accessToken(), "sub-multi-device");
        UserRegisterResponse deviceB = registerDevice();
        LoginResponse merged = link(deviceB.accessToken(), "sub-multi-device");

        Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
        submitSession(merged.accessToken(), base, base.plus(30, ChronoUnit.MINUTES));

        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + merged.accessToken()))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(userRepository.findById(deviceA.userId())).isEmpty();
        assertThat(studySessionRepository.findAll())
                .noneMatch(s -> s.getUserId().equals(deviceA.userId()));
        // 다기기 refresh 토큰 둘 다 폐기됐는지 확인 — deleteByUserId가 deleteByTokenHash로
        // 회귀하면(탈퇴 호출에 쓴 토큰 하나만 지움) deviceA.refreshToken() 쪽이 여전히 살아있어 실패한다
        assertThat(refreshRequest(deviceA.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refreshRequest(merged.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 탈퇴한_소셜_계정으로_재로그인하면_신규_가입이다() {
        stubVerifier("sub-rejoin");
        UserRegisterResponse deviceA = registerDevice();
        LoginResponse social = link(deviceA.accessToken(), "sub-rejoin");

        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + social.accessToken()))
                .hasStatus(HttpStatus.NO_CONTENT);

        // 같은 소셜 ID로 재로그인 → 전환 경로(신규 가입), 이전 데이터 미복구
        UserRegisterResponse deviceB = registerDevice();
        assertThat(link(deviceB.accessToken(), "sub-rejoin").isNewUser()).isTrue();
    }

    @Test
    void 인증_없이_탈퇴를_호출하면_401이다() {
        assertThat(mvc.delete().uri("/api/users/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private void stubVerifier(String sub) {
        when(googleTokenVerifier.verify("id-token-" + sub))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, sub, sub + "@gmail.com"));
    }

    private UserRegisterResponse registerDevice() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus2xxSuccessful();
        return readBody(result, UserRegisterResponse.class);
    }

    private MockMvcTester.MockMvcRequestBuilder linkRequest(String accessToken, String sub) {
        return mvc.post()
                .uri("/api/auth/link")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"GOOGLE\",\"idToken\":\"id-token-" + sub + "\"}");
    }

    private LoginResponse link(String accessToken, String sub) {
        MvcTestResult result = linkRequest(accessToken, sub).exchange();
        assertThat(result).hasStatusOk();
        return readBody(result, LoginResponse.class);
    }

    private void submitSession(String accessToken, Instant startedAt, Instant endedAt) {
        long sec = Duration.between(startedAt, endedAt).toSeconds();
        MvcTestResult result = mvc.post()
                .uri("/api/study-sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startedAt\":\"" + startedAt + "\",\"endedAt\":\"" + endedAt + "\",\"studySec\":" + sec
                        + ",\"focusSec\":" + sec + ",\"events\":[]}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private MockMvcTester.MockMvcRequestBuilder refreshRequest(String refreshToken) {
        return mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    // getContentAsString과 달리 getContentAsByteArray는 checked 예외가 없어 테스트에 throws가 안 번진다
    private <T> T readBody(MvcTestResult result, Class<T> type) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }
}
