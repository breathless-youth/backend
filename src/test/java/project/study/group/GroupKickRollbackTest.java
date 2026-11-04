package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.PushAssert.noPush;

import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupRoomProbe;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.push.service.PushSender;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/**
 * 내보내기는 한 트랜잭션이다 (BY-1124, 설계 "3단계 테스트"의 넷째) — 멤버 끝내기·초대코드 재발급·공부방 자리 지우기·푸시 중 어느 단계가 실패해도
 * 전부 롤백되고 푸시·STOMP가 하나도 나가지 않는다. 실패는 리포지토리·푸시 발송기를 spy로 감싸 그 단계에서 일으킨다. 코드 참여가 코드를 찾은 뒤
 * 잠그기 전에 재발급된 경우도 여기서 본다(같은 spy가 필요하다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupKickRollbackTest {

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

    @MockitoSpyBean
    private GroupRepository groupRepository;

    @MockitoSpyBean
    private GroupNotifications notifications;

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

    private String code() {
        return probe.group(group).orElseThrow().inviteCode();
    }

    /** 그룹장과 멤버가 모두 확정된 자리에 앉은 공부방. */
    private long bothSeated() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        sessions.subscribe(member, roomId);
        stomp.clear();
        return roomId;
    }

    private MockMvcRequestBuilder kick() {
        return v1Delete(mvc, "/api/groups/" + group + "/members/" + member, owner);
    }

    /** 내보내기가 아무 흔적도 남기지 않았다 — 멤버 그대로, 코드 그대로, 자리 그대로, 푸시·STOMP 없음. */
    private void assertNothingHappened(String codeBefore, long roomId) {
        assertThat(probe.members(group))
                .filteredOn(row -> row.userId() == member)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.leftAt()).isNull();
                    assertThat(row.leaveReason()).isNull();
                });
        assertThat(code()).isEqualTo(codeBefore);
        assertThat(rooms.liveRoomOf(member)).contains(roomId);
        assertThat(rooms.seat(roomId, member).orElseThrow().leftAt()).isNull();
        noPush(pushSender);
        assertThat(stomp.all()).isEmpty();
    }

    @Test
    void 재발급이_실패하면_내보내기도_롤백돼_아무것도_바뀌지_않고_아무것도_나가지_않는다() {
        long roomId = bothSeated();
        String before = code();
        // 뽑는 코드가 모두 이미 쓰인다 — 재발급이 끝내 빈 코드를 찾지 못한다
        doReturn(true).when(groupRepository).inviteCodeTaken(anyString());

        assertErrorCode(kick(), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        assertNothingHappened(before, roomId);
    }

    @Test
    void 마지막_단계가_실패해도_이미_등록한_공부방_메시지까지_롤백된다() {
        long roomId = bothSeated();
        String before = code();
        // 멤버 끝내기·재발급·자리 지우기(KICKED·MEMBER_LEFT 등록)가 끝난 뒤 실패한다
        doThrow(new IllegalStateException("푸시 등록 실패")).when(notifications).memberKicked(anyLong(), anyString());

        assertErrorCode(kick(), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        assertNothingHappened(before, roomId);
    }

    @Test
    void 코드를_찾은_뒤_잠그기_전에_재발급됐으면_404_INVITE_CODE_NOT_FOUND다() {
        String old = code();
        GroupRow stale = groupRepository.findLiveByInviteCode(old).orElseThrow();
        assertThat(v1Post(mvc, "/api/groups/" + group + "/invite", owner)).hasStatusOk();
        // 재발급 전에 읽은 행을 돌려준다 — 찾기와 잠그기 사이에 재발급이 끼어든 순간
        doReturn(Optional.of(stale)).when(groupRepository).findLiveByInviteCode(old);
        long newcomer = probe.insertUser();

        assertErrorCode(
                json(v1Post(mvc, "/api/groups/join", newcomer), """
                        {"inviteCode": "%s", "via": "CODE"}""".formatted(old)),
                HttpStatus.NOT_FOUND,
                "INVITE_CODE_NOT_FOUND");

        assertThat(probe.members(group)).noneMatch(row -> row.userId() == newcomer);
    }
}
