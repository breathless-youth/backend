package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;

import java.time.Instant;
import java.util.UUID;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupRoomProbe;
import project.study.group.support.GroupRoomProbe.SeatRow;
import project.study.group.support.StompCapture;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;
import project.study.room.service.RoomService;

/**
 * 그룹 공부방 자리 잡기·나가기 REST (BY-1124) — 멤버만, 그룹마다 열린 방 하나, 다른 방 자동 퇴장, 마지막 사람이 나가면 닫힘, 그룹 홈 책상의
 * {@code IN_ROOM}. 응답의 cameraOn·micOn·onBreak는 유예 복귀일 때만 값이 있다(Swagger·websocket.html §11.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupRoomApiTest {

    private static final String TASK = "task-group-room-api";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomCleanupService cleanup;

    @Autowired
    private GroupRoomService groupRoomService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

    private GroupProbe probe;
    private GroupRoomProbe rooms;
    private StompCapture stomp;
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
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    private GroupRoomJoinResponse join(long userId, long groupId) {
        return assertThat(v1Post(mvc, "/api/groups/" + groupId + "/room/join", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupRoomJoinResponse.class)
                .actual();
    }

    private void leave(long userId, long groupId) {
        assertThat(v1Post(mvc, "/api/groups/" + groupId + "/room/leave", userId))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private void confirm(long roomId, long userId) {
        assertThat(roomService.confirmStomp(roomId, userId, "s-" + UUID.randomUUID(), Instant.now(), TASK))
                .isNotEmpty();
    }

    @Test
    void 바깥_트랜잭션_안에서_자리_잡기를_부르면_거절하고_아무것도_만들지_않는다() {
        // 닫힘 경합 재시도는 "트랜잭션을 통째로 새로 열어"야 성립한다 — 바깥 트랜잭션에 합류하면 그 보장이 깨진다
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> groupRoomService.join(member, group)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rooms.openRoomOf(group)).isEmpty();
    }

    @Test
    void 멤버가_자리를_잡으면_그룹_공부방이_열리고_30초_예약이_생긴다() {
        GroupRoomJoinResponse response = join(member, group);

        assertThat(response.roomId()).isEqualTo(rooms.openRoomOf(group).orElseThrow());
        assertThat(response.graceRejoin()).isFalse();
        // 새 자리면 셋 다 null이다 — 이전 값을 이어 줄 것이 없다
        assertThat(response.cameraOn()).isNull();
        assertThat(response.micOn()).isNull();
        assertThat(response.onBreak()).isNull();
        assertThat(response.iceServers()).isNotNull();
        GroupRoomProbe.RoomRow room = rooms.room(response.roomId());
        assertThat(room.groupId()).isEqualTo(group);
        assertThat(room.inviteCode()).as("그룹 공부방에는 초대코드가 없다").isNull();
        SeatRow seat = rooms.seat(response.roomId(), member).orElseThrow();
        assertThat(seat.stompConfirmed()).as("구독 전까지는 예약이다").isFalse();
        assertThat(seat.micOn()).isFalse();
        assertThat(seat.onBreak()).isFalse();
    }

    @Test
    void 그룹마다_열린_공부방은_하나다() {
        long first = join(owner, group).roomId();
        long second = join(member, group).roomId();

        assertThat(second).isEqualTo(first);
        long otherGroup = probe.insertGroup(owner, "PRIVATE");
        assertThat(join(owner, otherGroup).roomId()).as("다른 그룹은 다른 방").isNotEqualTo(first);
    }

    @Test
    void 멤버가_아니면_403_NOT_GROUP_MEMBER다() {
        long stranger = probe.insertUser();
        long left = probe.insertUser();
        probe.insertMember(group, left, "APPLICATION");
        probe.leaveGroup(group, left);

        for (long userId : new long[] {stranger, left}) {
            assertErrorCode(
                    v1Post(mvc, "/api/groups/" + group + "/room/join", userId),
                    HttpStatus.FORBIDDEN,
                    "NOT_GROUP_MEMBER");
            assertErrorCode(
                    v1Post(mvc, "/api/groups/" + group + "/room/leave", userId),
                    HttpStatus.FORBIDDEN,
                    "NOT_GROUP_MEMBER");
        }
        assertThat(rooms.openRoomOf(group)).as("거절된 요청은 방을 열지 않는다").isEmpty();
    }

    @Test
    void 없거나_지워진_그룹은_404_GROUP_NOT_FOUND다() {
        long deleted = probe.insertGroup(owner, "PUBLIC");
        probe.softDeleteGroup(deleted);

        for (long groupId : new long[] {987654321L, deleted}) {
            assertErrorCode(
                    v1Post(mvc, "/api/groups/" + groupId + "/room/join", owner),
                    HttpStatus.NOT_FOUND,
                    "GROUP_NOT_FOUND");
            assertErrorCode(
                    v1Post(mvc, "/api/groups/" + groupId + "/room/leave", owner),
                    HttpStatus.NOT_FOUND,
                    "GROUP_NOT_FOUND");
        }
    }

    @Test
    void 다른_방에_있으면_그_방에서_나오고_옛_방에_MEMBER_LEFT가_나간다() {
        String code = roomService.create(owner).inviteCode();
        long oldRoom =
                roomService.join(member, code, "멤버", null, null).response().roomId();
        confirm(oldRoom, member);

        long groupRoom = join(member, group).roomId();

        assertThat(rooms.liveRoomOf(member)).contains(groupRoom);
        SeatRow old = rooms.seat(oldRoom, member).orElseThrow();
        assertThat(old.leftAt()).isNotNull();
        assertThat(old.leaveReason()).isEqualTo("SWITCHED_ROOM");
        assertThat(stomp.lastOnTopic(oldRoom, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(member);
    }

    @Test
    void 자리가_없어도_나가기는_204다() {
        leave(member, group);

        join(owner, group);
        leave(member, group);

        assertThat(rooms.openRoomOf(group)).as("내 자리가 없으면 방은 그대로").isPresent();
        assertThat(stomp.to("/topic/room/" + rooms.openRoomOf(group).orElseThrow()))
                .isEmpty();
    }

    @Test
    void 나가면_남은_사람에게_MEMBER_LEFT가_나가고_마지막_사람이_나가면_방이_닫힌다() {
        long roomId = join(owner, group).roomId();
        confirm(roomId, owner);
        join(member, group);
        confirm(roomId, member);

        leave(member, group);

        assertThat(rooms.seat(roomId, member).orElseThrow().leaveReason()).isEqualTo("EXPLICIT");
        assertThat(stomp.lastOnTopic(roomId, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(member);
        stomp.clear();

        leave(owner, group);

        assertThat(rooms.room(roomId).closedAt()).isNotNull();
        assertThat(rooms.openRoomOf(group)).isEmpty();
        assertThat(stomp.topic(roomId)).as("남은 사람이 없으면 알릴 곳도 없다").isEmpty();
        assertThat(join(member, group).roomId()).as("다음 입장 때 새로 연다").isNotEqualTo(roomId);
    }

    @Test
    void 구독하지_않은_예약은_정리_작업이_지우고_빈_그룹_공부방을_닫는다() {
        long roomId = join(member, group).roomId();

        // 초대코드가 없는 방도 같은 정리 경로를 탄다 — 닫을 때 코드 락을 건너뛴다
        Instant sweepAt = Instant.now().plusSeconds(RoomCleanupService.RESERVATION_TTL_SECONDS + 1);
        assertThat(cleanup.cleanupExpired(sweepAt)).contains(new AutoLeave(roomId, member));

        assertThat(rooms.liveRoomOf(member)).isEmpty();
        assertThat(rooms.room(roomId).closedAt()).isNotNull();
        assertThat(rooms.openRoomOf(group)).isEmpty();
    }

    @Test
    void 그룹_홈_책상은_공부방에_확정된_자리가_있는_멤버만_IN_ROOM이다() {
        long roomId = join(member, group).roomId();
        join(owner, group);

        assertThat(statusOf(member)).as("예약만 한 자리는 아직 IN_ROOM이 아니다").isNotEqualTo(DeskStatus.IN_ROOM);

        confirm(roomId, member);

        GroupHomeResponse home = home();
        assertThat(home.members())
                .extracting(GroupMemberDesk::userId, GroupMemberDesk::status)
                .contains(tuple(member, DeskStatus.IN_ROOM), tuple(owner, DeskStatus.IDLE));
        assertThat(home.studyingCount()).isEqualTo(1);

        leave(member, group);
        assertThat(statusOf(member)).as("나가면 IN_ROOM이 아니다").isEqualTo(DeskStatus.IDLE);
    }

    private GroupHomeResponse home() {
        return assertThat(v1Get(mvc, "/api/groups/" + group, owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupHomeResponse.class)
                .actual();
    }

    private DeskStatus statusOf(long userId) {
        return home().members().stream()
                .filter(desk -> desk.userId() == userId)
                .findFirst()
                .orElseThrow()
                .status();
    }
}
