package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.user.LinkTestClient.Device;
import project.study.user.LinkTestClient.Social;
import project.study.user.account.UserDataFixture;
import project.study.user.dto.LinkResponse;
import project.study.user.entity.Provider;
import project.study.user.jwt.JwtUtil;
import project.study.user.oauth.SocialTokenVerifiers;
import tools.jackson.databind.ObjectMapper;

/**
 * link와 공부방. 방 나가기는 병합 규칙이다 — 옮기는 쪽이 사라지기 전에 방에서 먼저 뺀다. 전환은 userId가 그대로라 참가가 그대로 맞고,
 * 409·404는 부작용이 없어야 한다. 참가자는 STOMP로 확정된 행으로 심는다(확정 전 예약은 나가기에서 이력 없이 지워진다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkRoomApiTest {

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

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private LinkTestClient api;

    @BeforeEach
    void setUp() {
        api = new LinkTestClient(mvc, objectMapper, jwtUtil, verifiers);
    }

    @Test
    void link_순간_방_안에_있으면_방에서_빠지고_병합된다() {
        Social google = api.stubSocial(Provider.GOOGLE, null);
        Device account = api.register();
        api.linkOk(account, google);
        Device source = api.register();
        Device other = api.register();
        long roomId = roomWith(source, other);

        assertThat(api.link(source.accessToken(), google, null)).hasStatusOk();

        // 남은 사람에게 옮기는 쪽의 퇴장이 나간다 — 병합 처리기의 경쟁 방지 닫기(SWITCHED_ROOM)가 아니라 방 나가기로 끝났다
        verify(messagingTemplate).convertAndSend("/topic/room/" + roomId, (Object)
                Map.of("type", "MEMBER_LEFT", "userId", source.userId()));
        List<Map<String, Object>> rows = participations(roomId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("user_id", account.userId())
                .containsEntry("left_room", true)
                .containsEntry("leave_reason", "EXPLICIT");
        assertThat(rows.get(1)).containsEntry("user_id", other.userId()).containsEntry("left_room", false);
        assertThat(jdbc.sql("SELECT created_by FROM rooms WHERE id = :r")
                        .param("r", roomId)
                        .query(Long.class)
                        .single())
                .isEqualTo(account.userId());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :id")
                        .param("id", source.userId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void 전환은_방에서_빼지_않는다() {
        Device device = api.register();
        Device other = api.register();
        long roomId = roomWith(device, other);

        LinkResponse linked = api.linkOk(device, api.stubSocial(Provider.GOOGLE, null));

        assertThat(linked.isNewUser()).isTrue();
        assertStillInRoom(roomId, device);
    }

    @Test
    void 이미_소셜_계정이면_방에서_빼지_않는다() {
        Device device = api.register();
        LinkResponse linked = api.linkOk(device, api.stubSocial(Provider.GOOGLE, null));
        Device other = api.register();
        long roomId = roomWith(device, other);
        // 다른 소셜 ID는 이미 있는 계정이다 — 미리 보기만으로 병합이라 여겨 방에서 빼면 안 된다
        Social existing = api.stubSocial(Provider.KAKAO, null);
        api.linkOk(api.register(), existing);

        assertThat(api.link(linked.accessToken(), existing, null))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("ALREADY_LINKED");
        assertStillInRoom(roomId, device);
    }

    // 둘 다 확정된 참가자인 열린 방
    private long roomWith(Device first, Device second) {
        long roomId = fixture.seedRoom(first.userId());
        fixture.seedParticipation(roomId, first.userId(), true);
        fixture.seedParticipation(roomId, second.userId(), true);
        return roomId;
    }

    private void assertStillInRoom(long roomId, Device device) {
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/room/" + roomId), any(Object.class));
        assertThat(participations(roomId).get(0))
                .containsEntry("user_id", device.userId())
                .containsEntry("left_room", false);
    }

    private List<Map<String, Object>> participations(long roomId) {
        return jdbc.sql("""
                        SELECT user_id, left_at IS NOT NULL AS left_room, leave_reason
                          FROM room_participations WHERE room_id = :r ORDER BY id""").param("r", roomId).query().listOfRows();
    }
}
