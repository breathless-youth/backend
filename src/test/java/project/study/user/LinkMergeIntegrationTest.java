package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.repository.StudySessionRepository;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.GoogleTokenVerifier;
import project.study.user.oauth.OAuthUserInfo;
import project.study.user.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkMergeIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudySessionRepository studySessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        when(googleTokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    }

    @Test
    void 기존_소셜_계정으로_link하면_익명_기록이_병합되고_겹치는_세션은_폐기된다() {
        // 기존 소셜 계정: 기기 A 익명 등록 → 전환 → 세션 base~base+60분 저장
        stubVerifier("sub-merge");
        UserRegisterResponse deviceA = registerDevice();
        LoginResponse social = link(deviceA.accessToken(), "sub-merge");
        Long socialUserId = Long.valueOf(jwtUserId(social.accessToken()));
        Instant base = Instant.now().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        submitSession(social.accessToken(), base, base.plus(60, ChronoUnit.MINUTES));

        // 기기 B 익명: 겹치는 세션(base+30분~base+90분, 내부에 PAUSE 이벤트 포함) + 안 겹치는 세션(base+2시간~base+2시간30분)
        UserRegisterResponse deviceB = registerDevice();
        Long anonUserId = deviceB.userId();
        Instant overlapStart = base.plus(30, ChronoUnit.MINUTES);
        Instant overlapEnd = base.plus(90, ChronoUnit.MINUTES);
        Instant pauseStart = overlapStart.plus(10, ChronoUnit.MINUTES);
        Instant pauseEnd = pauseStart.plus(5, ChronoUnit.MINUTES);
        submitSessionWithPauseEvent(deviceB.accessToken(), overlapStart, overlapEnd, pauseStart, pauseEnd);
        submitSession(
                deviceB.accessToken(),
                base.plus(2, ChronoUnit.HOURS),
                base.plus(2, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES));

        // 겹침 폐기 대상 세션에 status_event가 실제로 붙어 있는지 병합 전에 확인해둔다 —
        // 그래야 병합 후 이 이벤트가 cascade로 함께 지워졌는지 검증할 수 있다.
        // (events는 지연 로딩 컬렉션이라 트랜잭션 밖에서 엔티티로 접근하면 LazyInitializationException이
        // 나므로 JdbcTemplate으로 직접 조회한다 — 기존 세션 테스트들의 패턴과 동일)
        Long pauseEventId = jdbcTemplate.queryForObject(
                "SELECT e.id FROM status_event e JOIN study_session s ON e.session_id = s.id "
                        + "WHERE s.user_id = ? AND s.started_at = ?",
                Long.class,
                anonUserId,
                Timestamp.from(overlapStart));
        assertThat(pauseEventId).isNotNull();

        // 기기 B가 같은 소셜 계정으로 link → 병합
        LoginResponse merged = link(deviceB.accessToken(), "sub-merge");

        assertThat(merged.isNewUser()).isFalse();
        assertThat(jwtUserId(merged.accessToken())).isEqualTo(String.valueOf(socialUserId));
        // 겹치는 익명 세션(base+30분 시작)은 폐기 — 어느 유저 소유로도 남지 않는다
        // (자정 분할로 조각 수가 변할 수 있어 세션 개수 대신 시작 시각으로 단언한다)
        assertThat(studySessionRepository.findAll())
                .noneMatch(s -> s.getStartedAt().equals(base.plus(30, ChronoUnit.MINUTES)));
        // 폐기된 세션의 status_event도 cascade로 함께 지워졌다 — 엔티티 단위 삭제(deleteAll)가
        // 벌크 delete로 바뀌면 FK(session_id) 위반 없이도 이 이벤트가 고아 행으로 남는다
        Integer remainingPauseEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM status_event WHERE id = ?", Integer.class, pauseEventId);
        assertThat(remainingPauseEvents).isZero();
        // 안 겹치는 익명 세션(base+2시간 시작)은 소셜 계정으로 이관
        assertThat(studySessionRepository.findAll())
                .filteredOn(s -> s.getStartedAt().equals(base.plus(2, ChronoUnit.HOURS)))
                .allMatch(s -> s.getUserId().equals(socialUserId));
        // 기존 계정 세션(base 시작)은 유지
        assertThat(studySessionRepository.findAll())
                .anyMatch(s -> s.getStartedAt().equals(base) && s.getUserId().equals(socialUserId));
        // 익명 유저는 소멸
        assertThat(userRepository.findById(anonUserId)).isEmpty();
        // 익명 유저의 refresh 토큰도 폐기 → 재발급 불가
        assertThat(refreshRequest(deviceB.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 전환하면_전환_전에_발급된_익명_refresh_토큰은_무효가_된다() {
        // 전환은 userId를 유지하므로 익명 시절 토큰이 그대로 유효해질 수 있다 —
        // 전환 시점의 익명 유저는 단일 기기이므로 전환 경로에서 전량 폐기해야 한다
        stubVerifier("sub-rotate");
        UserRegisterResponse device = registerDevice();
        LoginResponse social = link(device.accessToken(), "sub-rotate");

        assertThat(refreshRequest(device.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        // 전환이 새로 발급한 토큰은 정상 동작한다
        assertThat(refreshRequest(social.refreshToken())).hasStatusOk();
    }

    @Test
    void 기록이_없는_새_기기의_link는_빈_병합으로_기존_계정_토큰을_받는다() {
        stubVerifier("sub-second-device");
        UserRegisterResponse deviceA = registerDevice();
        LoginResponse social = link(deviceA.accessToken(), "sub-second-device");

        UserRegisterResponse deviceB = registerDevice();
        LoginResponse merged = link(deviceB.accessToken(), "sub-second-device");

        assertThat(merged.isNewUser()).isFalse();
        assertThat(jwtUserId(merged.accessToken())).isEqualTo(jwtUserId(social.accessToken()));
    }

    @Test
    void 이미_소셜인_유저가_다시_link하면_409다() {
        stubVerifier("sub-already");
        stubVerifier("sub-other");
        UserRegisterResponse device = registerDevice();
        LoginResponse social = link(device.accessToken(), "sub-already");

        assertThat(linkRequest(social.accessToken(), "sub-other")).hasStatus(HttpStatus.CONFLICT);
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

    // PAUSE 이벤트가 붙은 세션을 제출한다 — PAUSE는 총 공부 타이머도 멈추므로 studySec은
    // 전체 구간에서 PAUSE 길이를 뺀 값이어야 검증(StudySessionValidator.validateStudySec)을 통과한다
    private void submitSessionWithPauseEvent(
            String accessToken, Instant startedAt, Instant endedAt, Instant pauseStart, Instant pauseEnd) {
        long sec = Duration.between(startedAt, endedAt).toSeconds();
        long pauseSec = Duration.between(pauseStart, pauseEnd).toSeconds();
        long studySec = sec - pauseSec;
        String eventsJson =
                "[{\"status\":\"PAUSE\",\"startedAt\":\"" + pauseStart + "\",\"endedAt\":\"" + pauseEnd + "\"}]";
        MvcTestResult result = mvc.post()
                .uri("/api/study-sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startedAt\":\"" + startedAt + "\",\"endedAt\":\"" + endedAt + "\",\"studySec\":" + studySec
                        + ",\"focusSec\":" + studySec + ",\"events\":" + eventsJson + "}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private MockMvcTester.MockMvcRequestBuilder refreshRequest(String refreshToken) {
        return mvc.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    // 응답 토큰의 sub(userId) 파싱용
    private String jwtUserId(String accessToken) {
        return jwtUtil.getUserId(accessToken);
    }

    // getContentAsString과 달리 getContentAsByteArray는 checked 예외가 없어 테스트에 throws가 안 번진다
    private <T> T readBody(MvcTestResult result, Class<T> type) {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }
}
