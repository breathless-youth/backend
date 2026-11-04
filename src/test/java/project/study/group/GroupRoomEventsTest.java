package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupRoomProbe;
import project.study.group.support.GroupRoomProbe.SeatRow;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.room.service.RoomService;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/**
 * 그룹 쪽 변화가 공부방에 닿는 길 (BY-1124) — 내보내기·나가기·그룹 사라짐({@code memberRemoved})과 그룹장 넘기기({@code ownerChanged}).
 * 둘 다 호출자(멤버 관리)의 그룹 트랜잭션 안에서 불리므로, 테스트도 트랜잭션 안에서 불러 커밋이면 나가고 롤백이면 아무것도 안 나가는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupRoomEventsTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private GroupRoomService groupRooms;

    @Autowired
    private RoomService roomService;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private StompEventListener listener;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

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

    /** 그룹장과 멤버가 모두 확정된 자리에 앉은 공부방. */
    private long bothSeated() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        sessions.subscribe(member, roomId);
        stomp.clear();
        return roomId;
    }

    @Test
    void 내보내면_커밋_뒤_개인_큐로_KICKED_다음_방_토픽으로_MEMBER_LEFT가_나간다() {
        long roomId = bothSeated();

        tx.executeWithoutResult(status -> {
            groupRooms.memberRemoved(group, member, true);
            assertThat(stomp.all()).as("커밋 전에는 아무것도 나가지 않는다").isEmpty();
        });

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("KICKED", "MEMBER_LEFT");
        Sent kicked = stomp.queue(member).getFirst();
        assertThat(kicked.sessionId()).as("세션을 가리지 않고 그 사람의 모든 세션으로").isNull();
        assertThat(kicked.body().path("groupId").asLong()).isEqualTo(group);
        assertThat(stomp.lastOnTopic(roomId, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(member);
        SeatRow seat = rooms.seat(roomId, member).orElseThrow();
        assertThat(seat.leftAt()).isNotNull();
        assertThat(seat.leaveReason()).isEqualTo("KICKED");
        assertThat(rooms.room(roomId).closedAt()).as("그룹장이 남아 있다").isNull();
    }

    @Test
    void 롤백되면_아무것도_나가지_않고_자리도_그대로다() {
        long roomId = bothSeated();

        tx.executeWithoutResult(status -> {
            groupRooms.memberRemoved(group, member, true);
            status.setRollbackOnly();
        });

        assertThat(stomp.all()).isEmpty();
        assertThat(rooms.liveRoomOf(member)).contains(roomId);
    }

    @Test
    void 그룹을_나가거나_그룹이_사라지면_KICKED_없이_MEMBER_LEFT만_나간다() {
        long roomId = bothSeated();

        tx.executeWithoutResult(status -> groupRooms.memberRemoved(group, member, false));

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("MEMBER_LEFT");
        assertThat(rooms.seat(roomId, member).orElseThrow().leaveReason()).isEqualTo("GROUP_LEFT");
    }

    @Test
    void 마지막_자리였으면_방이_닫힌다() {
        long roomId = groupRooms.join(member, group).roomId();
        sessions.subscribe(member, roomId);

        tx.executeWithoutResult(status -> groupRooms.memberRemoved(group, member, false));

        assertThat(rooms.room(roomId).closedAt()).isNotNull();
        assertThat(rooms.openRoomOf(group)).isEmpty();
    }

    @Test
    void 확정_전_예약도_지우고_알린다() {
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        stomp.clear();

        tx.executeWithoutResult(status -> groupRooms.memberRemoved(group, member, true));

        assertThat(rooms.liveRoomOf(member)).isEmpty();
        assertThat(stomp.all()).extracting(Sent::type).containsExactly("KICKED", "MEMBER_LEFT");
    }

    @Test
    void 이_그룹_공부방에_자리가_없으면_아무것도_보내지_않는다() {
        // 열린 방이 없음
        tx.executeWithoutResult(status -> groupRooms.memberRemoved(group, member, true));
        assertThat(stomp.all()).isEmpty();

        // 열린 방은 있지만 내 자리는 다른 방(초대코드 방)에 있음 — 그 자리는 건드리지 않는다
        groupRooms.join(owner, group);
        String code = roomService.create(owner).inviteCode();
        long otherRoom =
                roomService.join(member, code, "멤버", null, null).response().roomId();
        stomp.clear();

        tx.executeWithoutResult(status -> groupRooms.memberRemoved(group, member, true));

        assertThat(stomp.all()).isEmpty();
        assertThat(rooms.liveRoomOf(member)).contains(otherRoom);
    }

    @Test
    void 그룹장이_바뀌면_열린_공부방에만_커밋_뒤_OWNER_CHANGED가_나간다() {
        tx.executeWithoutResult(status -> groupRooms.ownerChanged(group, member));
        assertThat(stomp.all()).as("열린 방이 없으면 알릴 곳이 없다").isEmpty();

        long roomId = bothSeated();
        tx.executeWithoutResult(status -> {
            groupRooms.ownerChanged(group, member);
            status.setRollbackOnly();
        });
        assertThat(stomp.all()).as("롤백되면 안 나간다").isEmpty();

        tx.executeWithoutResult(status -> {
            groupRooms.ownerChanged(group, member);
            assertThat(stomp.all()).isEmpty();
        });

        assertThat(stomp.all()).extracting(Sent::destination).containsExactly("/topic/room/" + roomId);
        assertThat(stomp.lastOnTopic(roomId, "OWNER_CHANGED").path("userId").asLong())
                .isEqualTo(member);
    }

    @Test
    void 호출자의_트랜잭션_밖에서는_부를_수_없다() {
        // 커밋 뒤 발송을 보장하려면 호출자의 그룹 트랜잭션에 들어가야 한다 — 밖에서 부르면 바로 보내 버리는 실수를 막는다
        assertThatThrownBy(() -> groupRooms.memberRemoved(group, member, true))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> groupRooms.ownerChanged(group, member))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
