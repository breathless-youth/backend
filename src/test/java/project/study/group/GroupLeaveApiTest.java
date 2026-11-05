package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.MyGroupsResponse.MyGroupCard;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.dto.PublicGroupPageResponse.PublicGroupCard;
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
 * 그룹 나가기 (BY-1124) — 멤버만, 다른 멤버가 있는 그룹장은 409, 혼자 남은 그룹장이 나가면 그룹이 사라지고 답을 기다리던 신청이 닫힌다. 사라진
 * 그룹은 어디서 열어도 없는 그룹이다. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupLeaveApiTest {

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
        group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, member, "APPLICATION");
        stomp = StompCapture.on(brokerChannel);
        sessions = new RoomSessions(registry, listener);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        sessions.close();
    }

    private MockMvcRequestBuilder leave(long userId, long groupId) {
        return v1Post(mvc, "/api/groups/" + groupId + "/leave", userId);
    }

    private MemberRow lastRowOf(long groupId, long userId) {
        return probe.members(groupId).stream()
                .filter(row -> row.userId() == userId)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private String statusOf(long applicationId) {
        return probe.application(applicationId).orElseThrow().status();
    }

    @Test
    void 일반_멤버가_나가면_204이고_LEFT로_끝나며_그룹은_그대로다() {
        assertThat(leave(member, group)).hasStatus(HttpStatus.NO_CONTENT);

        MemberRow row = lastRowOf(group, member);
        assertThat(row.leftAt()).isEqualTo(NOW);
        assertThat(row.leaveReason()).isEqualTo("LEFT");
        assertThat(probe.group(group).orElseThrow().deletedAt()).isNull();
        assertThat(probe.countLiveMembers(group)).isEqualTo(1);
        assertErrorCode(v1Get(mvc, "/api/groups/" + group, member), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        noPush(pushSender);
    }

    @Test
    void 다른_멤버가_있는_그룹장은_409_OWNER_CANNOT_LEAVE다() {
        assertErrorCode(leave(owner, group), HttpStatus.CONFLICT, "OWNER_CANNOT_LEAVE");

        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        assertThat(probe.group(group).orElseThrow().deletedAt()).isNull();
    }

    @Test
    void 멤버가_아니면_403_없는_그룹은_404다() {
        assertErrorCode(leave(probe.insertUser(), group), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        assertErrorCode(leave(member, 987654321L), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    @Test
    void 혼자_남은_그룹장이_나가면_그룹이_사라지고_답을_기다리던_신청만_닫혀_푸시가_간다() {
        assertThat(leave(member, group)).hasStatus(HttpStatus.NO_CONTENT);
        long waiting = probe.insertUser();
        long lapsed = probe.insertUser();
        long declined = probe.insertUser();
        long waitingId = probe.insertApplication(group, waiting, "PENDING", NOW.plusSeconds(3600));
        // 기한만 지난 신청은 만료 작업이 닫고 만료 푸시를 보낸다
        long lapsedId = probe.insertApplication(group, lapsed, "PENDING", NOW.minusSeconds(1));
        long declinedId = probe.insertApplication(group, declined, "DECLINED", NOW.plusSeconds(3600));

        assertThat(leave(owner, group)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.group(group).orElseThrow().deletedAt()).isEqualTo(NOW);
        assertThat(lastRowOf(group, owner).leaveReason()).isEqualTo("LEFT");
        assertThat(statusOf(waitingId)).isEqualTo("GROUP_DELETED");
        assertThat(probe.application(waitingId).orElseThrow().answeredAt()).isEqualTo(NOW);
        assertThat(statusOf(lapsedId)).isEqualTo("PENDING");
        assertThat(statusOf(declinedId)).isEqualTo("DECLINED");
        PushMessage message = pushedOnceTo(pushSender, waiting);
        assertThat(message.type()).isEqualTo("group_application_room_closed");
        assertThat(message.ko().title()).isEqualTo("다른 그룹을 찾아볼까요?");
        assertThat(message.ko().body())
                .isEqualTo(probe.group(group).orElseThrow().name() + " 그룹이 사라져 신청이 취소됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
        notPushedTo(pushSender, lapsed);
        notPushedTo(pushSender, declined);
        notPushedTo(pushSender, owner);
    }

    @Test
    void 사라진_비공개_그룹은_홈_멤버_목록_초대코드_내_그룹_코드_참여에서_모두_없다() {
        long alone = probe.insertGroup(owner, "PRIVATE");
        String code = probe.group(alone).orElseThrow().inviteCode();

        assertThat(leave(owner, alone)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.group(alone).orElseThrow().inviteCode())
                .as("코드는 다른 그룹이 다시 쓸 수 있게 비운다")
                .isNull();
        assertErrorCode(v1Get(mvc, "/api/groups/" + alone, owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + alone + "/members", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(v1Get(mvc, "/api/groups/" + alone + "/invite", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(
                json(v1Post(mvc, "/api/groups/join", probe.insertUser()), """
                        {"inviteCode": "%s", "via": "LINK"}""".formatted(code)),
                HttpStatus.NOT_FOUND,
                "INVITE_CODE_NOT_FOUND");
        MyGroupsResponse mine = assertThat(v1Get(mvc, "/api/groups/me", owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(MyGroupsResponse.class)
                .actual();
        assertThat(mine.groups()).extracting(MyGroupCard::groupId).containsExactly(group);
    }

    @Test
    void 사라진_공개_그룹은_공개_목록과_보기에서_없다() {
        assertThat(leave(member, group)).hasStatus(HttpStatus.NO_CONTENT);
        String name = probe.group(group).orElseThrow().name();

        assertThat(leave(owner, group)).hasStatus(HttpStatus.NO_CONTENT);

        assertErrorCode(v1Get(mvc, "/api/groups/public/" + group, member), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        PublicGroupPageResponse page = assertThat(v1Get(
                        mvc, "/api/groups/public?query=" + URLEncoder.encode(name, StandardCharsets.UTF_8), member))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupPageResponse.class)
                .actual();
        assertThat(page.groups()).extracting(PublicGroupCard::groupId).doesNotContain(group);
    }

    @Test
    void 공부방에_자리가_있던_멤버가_나가면_방_토픽에_MEMBER_LEFT만_나간다() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        sessions.subscribe(member, roomId);
        stomp.clear();

        assertThat(leave(member, group)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("MEMBER_LEFT");
        assertThat(stomp.lastOnTopic(roomId, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(member);
        assertThat(rooms.seat(roomId, member).orElseThrow().leaveReason()).isEqualTo("GROUP_LEFT");
    }

    @Test
    void 혼자_남은_그룹장이_공부방에_있다가_나가면_방도_닫힌다() {
        assertThat(leave(member, group)).hasStatus(HttpStatus.NO_CONTENT);
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);

        assertThat(leave(owner, group)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rooms.room(roomId).closedAt()).isNotNull();
        assertThat(rooms.openRoomOf(group)).isEmpty();
    }
}
