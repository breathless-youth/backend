package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.account.UserDataFixture;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.image.ProfileImageStorage;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/** 계정 삭제(탈퇴) — 즉시 하드 삭제. 사용자 데이터 전부와 users 행이 사라지고, 모든 기기의 refresh가 무효가 된다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountDeletionApiTest {

    private static final Instant T0 = Instant.parse("2030-03-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2030-03-01T11:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2030, 3, 1);

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

    @MockitoBean
    private SocialTokenVerifiers verifiers;

    @MockitoBean
    private ProfileImageStorage storage;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private LinkTestClient api;

    @BeforeEach
    void setUp() {
        api = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers);
    }

    private MvcTestResult deleteMe(String accessToken) {
        return mvc.delete()
                .uri("/api/users/me")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private long countUser(long id) {
        return jdbc.sql("SELECT count(*) FROM users WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private void seedEverything(long user, long other) {
        fixture.seedDday(user, "d");
        fixture.seedAcquisition(user, "s");
        fixture.seedPushToken(user, "tok-" + user);
        fixture.seedPushSend(user, DAY);
        fixture.seedPushAssignment(user, "exp-" + user);
        fixture.seedStats("study_stats_daily", user, DAY, 10);
        fixture.seedStats("study_stats_weekly", user, DAY, 10);
        fixture.seedStats("study_stats_monthly", user, DAY, 10);
        fixture.seedRefreshToken(user);
        fixture.seedReport(user, other);
        fixture.seedReport(other, user);
        fixture.seedInquiry(user);
        fixture.seedUserNotification(user);
        long session = fixture.seedSession(user, T0, T1);
        fixture.seedStatusEvent(session);
        fixture.seedActiveSession(user, T1.plusSeconds(3600));
        long subject = fixture.seedSubject(user, "과목" + user, 0, false);
        fixture.seedTask(subject, "할일");
        fixture.seedSegment(session, subject, T0, T1);
        fixture.seedDailyCompletion(user, subject, DAY);
        long room = fixture.seedRoom(user);
        fixture.seedParticipation(room, user, false);
        fixture.seedRtcStat(room, user, other);
        fixture.seedRankingRecord(user, "board-" + user, DAY, 1);
        fixture.seedRankingBest(user, 1, "b", DAY);
    }

    @Test
    void 탈퇴하면_사용자와_모든_사용자_데이터가_사라진다() {
        Device me = api.register();
        Device other = api.register();
        seedEverything(me.userId(), other.userId());
        seedEverything(other.userId(), me.userId());
        assertThat(fixture.countRowsReferencing(me.userId())).isPositive();

        assertThat(deleteMe(me.accessToken())).hasStatus(204);

        assertThat(fixture.countRowsReferencing(me.userId())).isZero();
        assertThat(countUser(me.userId())).isZero();
    }

    @Test
    void 탈퇴하면_모든_기기의_refresh가_무효다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        LinkResponse first = api.linkOk(api.register(), google);
        LinkResponse second = api.linkOk(api.register(), google);
        assertThat(api.refresh(first.refreshToken())).hasStatusOk();

        assertThat(deleteMe(second.accessToken())).hasStatus(204);

        assertThat(api.refresh(first.refreshToken())).hasStatus(401);
        assertThat(api.refresh(second.refreshToken())).hasStatus(401);
    }

    @Test
    void 두_번_탈퇴해도_204다() {
        Device me = api.register();

        assertThat(deleteMe(me.accessToken())).hasStatus(204);
        assertThat(deleteMe(me.accessToken())).hasStatus(204);
    }

    @Test
    void 탈퇴한_소셜_ID로_다시_link하면_isNewUser가_true다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        LinkResponse linked = api.linkOk(api.register(), google);
        long oldUserId = api.subOf(linked.accessToken());

        assertThat(deleteMe(linked.accessToken())).hasStatus(204);
        LinkResponse again = api.linkOk(api.register(), google);

        assertThat(again.isNewUser()).isTrue();
        assertThat(api.subOf(again.accessToken())).isNotEqualTo(oldUserId);
    }

    @Test
    void 프로필_사진이_있으면_파일도_지운다() {
        Device me = api.register();
        String key = "profile/" + me.userId() + "/photo.jpg";
        jdbc.sql("UPDATE users SET profile_image = :k WHERE id = :id")
                .param("k", key)
                .param("id", me.userId())
                .update();

        assertThat(deleteMe(me.accessToken())).hasStatus(204);

        verify(storage).delete(key);
    }

    @Test
    void 프로필_사진이_없으면_파일을_지우지_않는다() {
        Device me = api.register();

        assertThat(deleteMe(me.accessToken())).hasStatus(204);

        verifyNoInteractions(storage);
    }

    @Test
    void 방_안에서_탈퇴하면_방에서_빠진다() {
        Device me = api.register();
        Device other = api.register();
        long roomId = fixture.seedRoom(other.userId());
        fixture.seedParticipation(roomId, other.userId(), true);
        fixture.seedParticipation(roomId, me.userId(), true);

        assertThat(deleteMe(me.accessToken())).hasStatus(204);

        verify(messagingTemplate)
                .convertAndSend("/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_LEFT", "userId", me.userId()));
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT user_id, left_at IS NOT NULL AS left_room FROM room_participations WHERE room_id = :r")
                .param("r", roomId)
                .query()
                .listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("user_id", other.userId()).containsEntry("left_room", false);
        assertThat(countUser(me.userId())).isZero();
    }

    @Test
    void 비회원도_탈퇴할_수_있다() {
        Device device = api.register();
        assertThat(jdbc.sql("SELECT provider FROM users WHERE id = :id")
                        .param("id", device.userId())
                        .query(String.class)
                        .single())
                .isEqualTo("DEVICE");

        assertThat(deleteMe(device.accessToken())).hasStatus(204);

        assertThat(countUser(device.userId())).isZero();
    }

    @Test
    void 다른_사람의_데이터는_그대로다() {
        Device me = api.register();
        Device other = api.register();
        seedEverything(me.userId(), other.userId());
        seedEverything(other.userId(), me.userId());

        assertThat(deleteMe(me.accessToken())).hasStatus(204);

        assertThat(countUser(other.userId())).isEqualTo(1);
        assertThat(fixture.count("study_session", "user_id", other.userId())).isPositive();
        assertThat(fixture.count("study_subject", "user_id", other.userId())).isPositive();
        assertThat(fixture.count("refresh_token", "user_id", other.userId())).isPositive();
        assertThat(fixture.count("rtc_connection_stat", "user_id", other.userId()))
                .isPositive();
    }

    @Test
    void 인증_없이는_탈퇴할_수_없다() {
        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .exchange())
                .hasStatus(401);
    }
}
