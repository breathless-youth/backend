package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.RoomSessions.accessor;
import static project.study.group.support.RoomSessions.principal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.service.GroupRoomService;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupRoomProbe;
import project.study.group.support.GroupRoomProbe.SeatRow;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomService;
import project.study.room.websocket.RoomStompHandler;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;
import tools.jackson.databind.JsonNode;

/**
 * 그룹 공부방 STOMP (BY-1124, websocket.html §11) — 멤버 객체의 micOn·onBreak·owner, state의 micOn·onBreak, 유예 복귀. 초대코드 방은
 * 그대로여야 해서, 같은 경로로 초대코드 방을 돌려 직렬화된 JSON에 새 키가 <b>아예 없는지</b> 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupRoomStompTest {

    private static final List<String> GROUP_KEYS = List.of("micOn", "onBreak", "owner");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GroupRoomService groupRooms;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomStompHandler handler;

    @Autowired
    private StompEventListener listener;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

    @Autowired
    @Qualifier("brokerMessageConverter")
    private CompositeMessageConverter converter;

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

    private void state(long userId, long roomId, String sessionId, StateUpdatePayload payload) {
        handler.handleState(roomId, payload, principal(userId), accessor(sessionId));
    }

    private static List<JsonNode> members(JsonNode snapshot) {
        List<JsonNode> members = new ArrayList<>();
        snapshot.path("members").forEach(members::add);
        return members;
    }

    @Test
    void 그룹_방_멤버_객체에는_micOn_onBreak_owner가_있다() {
        long roomId = groupRooms.join(owner, group).roomId();
        groupRooms.join(member, group);
        sessions.subscribe(owner, roomId);
        String memberSession = sessions.subscribe(member, roomId);

        List<JsonNode> snapshot = members(stomp.lastToUser(member, "SNAPSHOT"));
        assertThat(snapshot).extracting(m -> m.path("userId").asLong()).containsExactly(owner, member);
        assertThat(snapshot).extracting(m -> m.path("owner").asBoolean()).containsExactly(true, false);
        assertThat(snapshot).allSatisfy(m -> {
            assertThat(m.path("micOn").isBoolean()).isTrue();
            assertThat(m.path("micOn").asBoolean()).as("입장 직후 마이크는 꺼져 있다").isFalse();
            assertThat(m.path("onBreak").isBoolean()).isTrue();
            assertThat(m.path("onBreak").asBoolean()).isFalse();
        });
        JsonNode joined = stomp.lastOnTopic(roomId, "MEMBER_JOINED").path("member");
        assertThat(joined.path("userId").asLong()).isEqualTo(member);
        assertThat(GROUP_KEYS)
                .allSatisfy(key -> assertThat(joined.has(key)).as(key).isTrue());
        assertThat(joined.path("owner").asBoolean()).isFalse();

        // owner는 입장 때 굳은 값이 아니라 지금 그룹장이다 — 넘긴 뒤 재요청한 SNAPSHOT에 바로 보인다
        jdbc.sql("update study_group set owner_id = :member where id = :id")
                .param("member", member)
                .param("id", group)
                .update();
        handler.handleSnapshotRequest(roomId, principal(member), accessor(memberSession));
        assertThat(members(stomp.lastToUser(member, "SNAPSHOT")))
                .extracting(m -> m.path("owner").asBoolean())
                .containsExactly(false, true);
    }

    @Test
    void 초대코드_방_멤버_객체에는_세_키가_아예_없다() {
        String code = roomService.create(owner).inviteCode();
        long roomId =
                roomService.join(owner, code, "그룹장", null, null).response().roomId();
        roomService.join(member, code, "멤버", null, null);
        sessions.subscribe(owner, roomId);
        String memberSession = sessions.subscribe(member, roomId);
        handler.handleSnapshotRequest(roomId, principal(member), accessor(memberSession));

        List<Sent> snapshots = stomp.queue(member).stream()
                .filter(s -> "SNAPSHOT".equals(s.type()))
                .toList();
        assertThat(snapshots).as("구독 확정 SNAPSHOT + 재요청 SNAPSHOT").hasSize(2);
        List<JsonNode> objects = new ArrayList<>();
        snapshots.forEach(s -> objects.addAll(members(s.body())));
        stomp.topic(roomId).stream()
                .filter(s -> "MEMBER_JOINED".equals(s.type()))
                .forEach(s -> objects.add(s.body().path("member")));
        assertThat(objects).hasSize(2 + 2 + 2);
        assertThat(objects).allSatisfy(m -> {
            assertThat(GROUP_KEYS)
                    .allSatisfy(key -> assertThat(m.has(key)).as(key).isFalse());
            // 생략은 새 필드에만 — 기존 nullable 필드는 null로 그대로 실린다
            assertThat(m.has("goal")).isTrue();
            assertThat(m.has("profileImageUrl")).isTrue();
        });
    }

    @Test
    void 그룹_방_state의_micOn_onBreak는_저장하고_방에_알린다() {
        long roomId = groupRooms.join(owner, group).roomId();
        String session = sessions.subscribe(owner, roomId);
        stomp.clear();

        state(owner, roomId, session, new StateUpdatePayload(null, null, null, true, null));

        assertThat(rooms.seat(roomId, owner).orElseThrow().micOn()).isTrue();
        assertThat(stomp.topic(roomId)).extracting(Sent::type).containsExactly("MIC_CHANGED");
        JsonNode mic = stomp.lastOnTopic(roomId, "MIC_CHANGED");
        assertThat(mic.path("userId").asLong()).isEqualTo(owner);
        assertThat(mic.path("micOn").asBoolean()).isTrue();
        stomp.clear();

        state(owner, roomId, session, new StateUpdatePayload(true, null, 60, null, true));

        SeatRow seat = rooms.seat(roomId, owner).orElseThrow();
        assertThat(seat.onBreak()).isTrue();
        assertThat(seat.micOn()).as("보내지 않은 필드는 그대로").isTrue();
        assertThat(stomp.topic(roomId))
                .extracting(Sent::type)
                .containsExactly("CAMERA_CHANGED", "STUDY_TIME", "BREAK_CHANGED");
        assertThat(stomp.lastOnTopic(roomId, "BREAK_CHANGED").path("onBreak").asBoolean())
                .isTrue();
    }

    @Test
    void 초대코드_방_state의_micOn_onBreak는_저장도_알림도_없다() {
        String code = roomService.create(owner).inviteCode();
        long roomId =
                roomService.join(owner, code, "그룹장", null, null).response().roomId();
        String session = sessions.subscribe(owner, roomId);
        stomp.clear();

        state(owner, roomId, session, new StateUpdatePayload(null, null, null, true, true));

        assertThat(stomp.topic(roomId)).isEmpty();
        SeatRow untouched = rooms.seat(roomId, owner).orElseThrow();
        assertThat(untouched.micOn()).isFalse();
        assertThat(untouched.onBreak()).isFalse();

        state(owner, roomId, session, new StateUpdatePayload(true, null, null, true, null));

        assertThat(stomp.topic(roomId)).extracting(Sent::type).containsExactly("CAMERA_CHANGED");
        SeatRow seat = rooms.seat(roomId, owner).orElseThrow();
        assertThat(seat.cameraOn()).as("같이 온 기존 필드는 그대로 반영").isTrue();
        assertThat(seat.micOn()).isFalse();
    }

    @Test
    void 유예_복귀는_카메라_마이크_휴식을_이어_준다() {
        long roomId = groupRooms.join(member, group).roomId();
        String session = sessions.subscribe(member, roomId);
        state(member, roomId, session, new StateUpdatePayload(true, null, null, true, true));
        roomService.handleDisconnect(session);

        GroupRoomJoinResponse rejoin = groupRooms.join(member, group);

        assertThat(rejoin.roomId()).isEqualTo(roomId);
        assertThat(rejoin.graceRejoin()).isTrue();
        assertThat(rejoin.cameraOn()).isTrue();
        assertThat(rejoin.micOn()).isTrue();
        assertThat(rejoin.onBreak()).isTrue();
        sessions.subscribe(member, roomId);
        JsonNode me = members(stomp.lastToUser(member, "SNAPSHOT")).getFirst();
        assertThat(me.path("micOn").asBoolean()).isTrue();
        assertThat(me.path("onBreak").asBoolean()).isTrue();

        // 확정된 멤버가 ICE 갱신으로 다시 부르면 graceRejoin false·null이지만 자리 값은 그대로다
        GroupRoomJoinResponse refresh = groupRooms.join(member, group);
        assertThat(refresh.graceRejoin()).isFalse();
        assertThat(refresh.micOn()).isNull();
        assertThat(refresh.onBreak()).isNull();
        assertThat(rooms.seat(roomId, member).orElseThrow().micOn()).isTrue();
    }

    @Test
    void state_본문의_micOn_onBreak를_읽고_없으면_null이다() {
        assertThat(read("{\"micOn\": true, \"onBreak\": false}"))
                .isEqualTo(new StateUpdatePayload(null, null, null, true, false));
        assertThat(read("{\"cameraOn\": true, \"focusState\": \"FOCUS\", \"focusSec\": 10}"))
                .isEqualTo(new StateUpdatePayload(true, "FOCUS", 10));
    }

    private StateUpdatePayload read(String json) {
        return (StateUpdatePayload) converter.fromMessage(
                MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                        .build(),
                StateUpdatePayload.class);
    }
}
