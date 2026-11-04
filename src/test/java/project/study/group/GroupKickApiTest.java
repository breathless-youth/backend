package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.MemberRow;
import project.study.group.support.GroupRoomProbe;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/**
 * 멤버 내보내기 (BY-1124) — 그룹장만, 3일 동안 다시 못 들어옴, 비공개 그룹은 같은 처리에서 초대코드 재발급(설계 "3단계 테스트"), 푸시와 공부방
 * 메시지. 재발급이 실패하면 내보내기도 롤백되는 것은 {@link GroupKickRollbackTest}가 본다. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupKickApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GroupRoomService groupRooms;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private StompEventListener listener;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private GroupRoomProbe rooms;
    private StompCapture stomp;
    private RoomSessions sessions;
    private long owner;
    private long member;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        rooms = new GroupRoomProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        member = probe.insertUser("멤버", null);
        group = probe.insertGroup(owner, "PRIVATE");
        probe.insertMember(group, member, "INVITE_CODE");
        stomp = StompCapture.on(brokerChannel);
        sessions = new RoomSessions(registry, listener);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        sessions.close();
    }

    private MockMvcRequestBuilder kick(long userId, long groupId, long targetUserId) {
        return v1Delete(mvc, "/api/groups/" + groupId + "/members/" + targetUserId, userId);
    }

    private MockMvcRequestBuilder join(long userId, String inviteCode) {
        return json(v1Post(mvc, "/api/groups/join", userId), """
                {"inviteCode": "%s", "via": "LINK"}""".formatted(inviteCode));
    }

    private String code(long groupId) {
        return probe.group(groupId).orElseThrow().inviteCode();
    }

    private MemberRow lastRowOf(long groupId, long userId) {
        return probe.members(groupId).stream()
                .filter(row -> row.userId() == userId)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    void 내보내면_204이고_KICKED로_끝나며_내보내진_사람에게만_푸시가_간다() {
        assertThat(kick(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        MemberRow row = lastRowOf(group, member);
        assertThat(row.leftAt()).isEqualTo(NOW);
        assertThat(row.leaveReason()).isEqualTo("KICKED");
        PushMessage message = pushedOnceTo(pushSender, member);
        assertThat(message.type()).isEqualTo("group_member_kicked");
        assertThat(message.ko().title()).isEqualTo("다른 그룹과 다시 시작해 볼까요?");
        assertThat(message.ko().body())
                .isEqualTo(probe.group(group).orElseThrow().name() + "에서 내보내졌어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
        notPushedTo(pushSender, owner);
        assertErrorCode(v1Get(mvc, "/api/groups/" + group, member), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
    }

    @Test
    void 비공개_그룹에서_내보내면_예전_코드는_404이고_새_코드로는_다른_사람이_들어온다() {
        String old = code(group);

        assertThat(kick(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        String renewed = code(group);
        assertThat(renewed).isNotEqualTo(old).matches("[" + InviteCodes.ALPHABET + "]{6}");
        assertErrorCode(join(probe.insertUser(), old), HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND");
        long newcomer = probe.insertUser();
        assertThat(join(newcomer, renewed))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.joined", v -> assertThat(v).isEqualTo(true));
        // FE는 초대코드 보기를 다시 받아 새 코드를 보여 준다
        assertThat(v1Get(mvc, "/api/groups/" + group + "/invite", owner))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.inviteCode", v -> assertThat(v).isEqualTo(renewed));
    }

    @Test
    void 내보내진_사람은_새_코드를_받아도_3일_동안_RECENTLY_KICKED다() {
        assertThat(kick(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertErrorCode(join(member, code(group)), HttpStatus.CONFLICT, "RECENTLY_KICKED");

        assertThat(probe.countLiveMembers(group)).isEqualTo(1);
    }

    @Test
    void 공개_그룹에서_내보내면_초대코드는_없는_그대로다() {
        long publicGroup = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(publicGroup, member, "APPLICATION");

        assertThat(kick(owner, publicGroup, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.group(publicGroup).orElseThrow().inviteCode()).isNull();
        assertThat(lastRowOf(publicGroup, member).leaveReason()).isEqualTo("KICKED");
    }

    @Test
    void 내보내기는_그룹장만_하고_자기_자신은_400_멤버가_아닌_대상은_404다() {
        long third = probe.insertUser();
        probe.insertMember(group, third, "INVITE_CODE");
        long left = probe.insertUser();
        probe.insertMember(group, left, "INVITE_CODE");
        probe.leaveGroup(group, left);
        String old = code(group);

        assertErrorCode(kick(member, group, third), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(kick(owner, group, owner), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertErrorCode(kick(owner, group, probe.insertUser()), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(kick(owner, group, left), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(kick(owner, 987654321L, member), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");

        assertThat(probe.countLiveMembers(group)).isEqualTo(3);
        assertThat(code(group)).as("실패한 내보내기는 코드를 바꾸지 않는다").isEqualTo(old);
        noPush(pushSender);
    }

    @Test
    void 공부방에_있던_멤버를_내보내면_개인_큐로_KICKED_방_토픽으로_MEMBER_LEFT가_나간다() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        sessions.subscribe(member, roomId);
        stomp.clear();

        assertThat(kick(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("KICKED", "MEMBER_LEFT");
        assertThat(stomp.lastToUser(member, "KICKED").path("groupId").asLong()).isEqualTo(group);
        assertThat(stomp.lastOnTopic(roomId, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(member);
        assertThat(rooms.seat(roomId, member).orElseThrow().leaveReason()).isEqualTo("KICKED");
        assertThat(rooms.liveRoomOf(owner)).contains(roomId);
    }
}
