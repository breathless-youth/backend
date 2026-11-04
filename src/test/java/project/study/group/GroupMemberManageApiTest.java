package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupApiTestSupport.v1Put;
import static project.study.group.support.GroupDeskTestConfig.KST;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.GroupMembersResponse;
import project.study.group.dto.GroupMembersResponse.GroupMemberItem;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.group.support.StudyFixture;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/** 멤버 목록과 그룹장 넘기기 (BY-1124). 시계는 2026-10-14(수) 15:00 KST로 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupMemberManageApiTest {

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
    private StudyFixture study;
    private StompCapture stomp;
    private RoomSessions sessions;
    private long owner;
    private long member;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
        owner = probe.insertUser("그룹장", "avatars/owner.jpg");
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
        study.clear(List.of(owner, member));
    }

    private static Instant kst(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    private GroupMembersResponse members(long userId, long groupId) {
        return assertThat(v1Get(mvc, "/api/groups/" + groupId + "/members", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupMembersResponse.class)
                .actual();
    }

    private Instant joinedAt(long groupId, long userId) {
        return jdbc.sql("select joined_at from study_group_member "
                        + "where group_id = :groupId and user_id = :userId and left_at is null")
                .param("groupId", groupId)
                .param("userId", userId)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    private MockMvcRequestBuilder transfer(long userId, long groupId, long targetUserId) {
        return json(v1Put(mvc, "/api/groups/" + groupId + "/owner", userId), """
                {"userId": %d}""".formatted(targetUserId));
    }

    private long ownerOf(long groupId) {
        return probe.group(groupId).orElseThrow().ownerId();
    }

    // --- 멤버 목록 ---

    @Test
    void 멤버_목록은_들어간_순서로_사람_정보를_준다() {
        long earliest = probe.insertUser("먼저온", null);
        // 그룹장보다 먼저 들어온 것으로 — 순서가 ID가 아니라 들어온 때인지 본다
        Instant earliestJoinedAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        probe.insertMemberAt(group, earliest, "INVITE_LINK", earliestJoinedAt);

        List<GroupMemberItem> items = members(member, group).members();

        assertThat(items).extracting(GroupMemberItem::userId).containsExactly(earliest, owner, member);
        assertThat(items).extracting(GroupMemberItem::owner).containsExactly(false, true, false);
        assertThat(items)
                .extracting(GroupMemberItem::nickname)
                .containsExactly(
                        GroupProbe.nickname("먼저온", earliest),
                        GroupProbe.nickname("그룹장", owner),
                        GroupProbe.nickname("멤버", member));
        assertThat(items)
                .extracting(GroupMemberItem::profileImageUrl)
                .containsExactly(null, "/avatars/owner.jpg", null);
        assertThat(items)
                .extracting(GroupMemberItem::joinedAt)
                .containsExactly(earliestJoinedAt, joinedAt(group, owner), joinedAt(group, member));
    }

    @Test
    void 마지막_공부_시각은_책상과_같은_값이고_기록이_없으면_null이다() {
        long idle = probe.insertUser("기록없음", null);
        probe.insertMember(group, idle, "INVITE_CODE");
        study.session(owner, kst(14, 10, 0), 1800); // 10:30에 끝
        study.studying(member, 590); // NOW 10초 전에 받은 draft

        List<GroupMemberItem> items = members(owner, group).members();
        GroupHomeResponse home = assertThat(v1Get(mvc, "/api/groups/" + group, owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupHomeResponse.class)
                .actual();

        assertThat(items)
                .extracting(GroupMemberItem::lastStudiedAt)
                .containsExactly(kst(14, 10, 30), NOW.minusSeconds(10), null);
        assertThat(items)
                .extracting(GroupMemberItem::lastStudiedAt)
                .containsExactlyElementsOf(home.members().stream()
                        .map(GroupMemberDesk::lastStudiedAt)
                        .toList());
    }

    @Test
    void 멤버_목록은_멤버만_보고_사라진_그룹은_404다() {
        long left = probe.insertUser();
        probe.insertMember(group, left, "INVITE_CODE");
        probe.leaveGroup(group, left);
        long deleted = probe.insertGroup(owner, "PUBLIC");
        probe.softDeleteGroup(deleted);

        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group + "/members", probe.insertUser()),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_MEMBER");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group + "/members", left), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + deleted + "/members", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    // --- 그룹장 넘기기 ---

    @Test
    void 그룹장을_넘기면_204이고_새_그룹장에게만_푸시가_한_번_간다() {
        assertThat(transfer(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(ownerOf(group)).isEqualTo(member);
        assertThat(probe.group(group).orElseThrow().updatedAt()).isEqualTo(NOW);
        PushMessage message = pushedOnceTo(pushSender, member);
        assertThat(message.type()).isEqualTo("group_owner_transferred");
        assertThat(message.title()).isEqualTo("그룹장이 됐어요");
        assertThat(message.body()).isEqualTo(probe.group(group).orElseThrow().name() + "의 그룹장을 넘겨받았어요");
        assertThat(message.link()).isEqualTo("/groups/" + group);
        notPushedTo(pushSender, owner);
        // 넘긴 사람은 일반 멤버로 남는다
        assertThat(members(owner, group).members())
                .extracting(GroupMemberItem::userId, GroupMemberItem::owner)
                .contains(tuple(owner, false));
    }

    @Test
    void 넘긴_뒤에는_새_그룹장만_그룹장_일을_한다() {
        assertThat(transfer(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertErrorCode(
                v1Post(mvc, "/api/groups/" + group + "/invite", owner), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertThat(v1Post(mvc, "/api/groups/" + group + "/invite", member)).hasStatusOk();
    }

    @Test
    void 새_그룹장은_그룹장인_그룹이_이미_2개여도_넘겨받는다() {
        probe.insertGroup(member, "PUBLIC");
        probe.insertGroup(member, "PRIVATE");

        assertThat(transfer(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.groupsOwnedBy(member)).hasSize(3);
    }

    @Test
    void 넘기기는_그룹장만_하고_자기_자신은_400_멤버가_아닌_대상은_404다() {
        long stranger = probe.insertUser();
        long left = probe.insertUser();
        probe.insertMember(group, left, "INVITE_CODE");
        probe.leaveGroup(group, left);

        assertErrorCode(transfer(member, group, member), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(transfer(owner, group, owner), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertErrorCode(transfer(owner, group, stranger), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(transfer(owner, group, left), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(transfer(owner, 987654321L, member), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");

        assertThat(ownerOf(group)).isEqualTo(owner);
        noPush(pushSender);
    }

    @Test
    void 공부방이_열려_있으면_넘긴_뒤_방_토픽에_OWNER_CHANGED가_나간다() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        stomp.clear();

        assertThat(transfer(owner, group, member)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("OWNER_CHANGED");
        assertThat(stomp.lastOnTopic(roomId, "OWNER_CHANGED").path("userId").asLong())
                .isEqualTo(member);
    }
}
