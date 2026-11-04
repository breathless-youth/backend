package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.RoomSessions.accessor;
import static project.study.group.support.RoomSessions.principal;

import java.util.List;
import java.util.Map;
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
import project.study.TestcontainersConfiguration;
import project.study.group.report.ReportAlert;
import project.study.group.service.GroupRoomService;
import project.study.group.support.BlockProbe;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.RoomSessions;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.push.service.PushSender;
import project.study.room.dto.SignalPayload;
import project.study.room.service.RoomService;
import project.study.room.websocket.RoomStompHandler;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;
import tools.jackson.databind.JsonNode;

/**
 * 차단한 사이의 그룹 공부방 (BY-1124, websocket.html §11.4·§11.5) — 신고하는 순간과 입장 확정·스냅샷 재요청 때의 {@code PEER_BLOCKED}, 차단
 * 사이 SIGNAL 버리기. {@code PEER_BLOCKED}는 양쪽이 같은 모양이라 누가 차단했는지 드러나지 않아야 한다. 초대코드 방은 차단과 상관없이 지금
 * 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupRoomBlockStompTest {

    private static final String PEER_BLOCKED = "PEER_BLOCKED";

    @Autowired
    private MockMvcTester mvc;

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

    @MockitoBean
    private PushSender pushSender;

    @MockitoBean
    private ReportAlert reportAlert;

    private GroupProbe probe;
    private BlockProbe blocks;
    private StompCapture stomp;
    private RoomSessions sessions;
    private long owner;
    private long member;
    private long third;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        blocks = new BlockProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        member = probe.insertUser("멤버", null);
        third = probe.insertUser("셋째", null);
        group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, member, "APPLICATION");
        probe.insertMember(group, third, "APPLICATION");
        stomp = StompCapture.on(brokerChannel);
        sessions = new RoomSessions(registry, listener);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        sessions.close();
    }

    /** 그룹 공부방에 자리를 잡고 구독까지 마친다. 그 세션 ID를 돌려준다. */
    private String enter(long userId) {
        long roomId = groupRooms.join(userId, group).roomId();
        return sessions.subscribe(userId, roomId);
    }

    private long roomId() {
        return roomService.openGroupRoomId(group).orElseThrow();
    }

    private void reportMember(long userId, long targetUserId) {
        assertThat(json(v1Post(mvc, "/api/groups/" + group + "/members/" + targetUserId + "/report", userId), "{}"))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private void signal(long roomId, long from, String session, long to) {
        handler.handleSignal(
                roomId, new SignalPayload(to, "OFFER", Map.of("sdp", "v=0")), principal(from), accessor(session));
    }

    private List<Sent> peerBlocked(long userId) {
        return stomp.queue(userId).stream()
                .filter(s -> PEER_BLOCKED.equals(s.type()))
                .toList();
    }

    private List<Sent> signals(long userId) {
        return stomp.queue(userId).stream()
                .filter(s -> "SIGNAL".equals(s.type()))
                .toList();
    }

    private static List<String> keys(JsonNode body) {
        return body.propertyNames().stream().sorted().toList();
    }

    // --- 신고하는 순간 ---

    @Test
    void 같은_공부방에서_신고하면_두_사람_모두_같은_모양의_PEER_BLOCKED를_모든_세션으로_받는다() {
        enter(owner);
        enter(member);
        enter(third);
        stomp.clear();

        reportMember(member, owner);

        Sent toReporter = peerBlocked(member).getFirst();
        Sent toTarget = peerBlocked(owner).getFirst();
        assertThat(peerBlocked(member)).hasSize(1);
        assertThat(peerBlocked(owner)).hasSize(1);
        assertThat(toReporter.body().path("userId").asLong()).isEqualTo(owner);
        assertThat(toTarget.body().path("userId").asLong()).isEqualTo(member);
        // 양쪽 모양이 같다 — 누가 신고·차단했는지 알 수 없다
        assertThat(keys(toReporter.body())).isEqualTo(keys(toTarget.body())).containsExactly("type", "userId");
        assertThat(toReporter.sessionId()).as("방 밖에서 일어난 일이라 모든 세션으로").isNull();
        assertThat(toTarget.sessionId()).isNull();
        assertThat(stomp.queue(third)).isEmpty();
        assertThat(stomp.topic(roomId())).as("방의 다른 사람에게는 알리지 않는다").isEmpty();
    }

    @Test
    void 예약만_한_자리도_같은_방이면_PEER_BLOCKED를_보낸다() {
        enter(member);
        groupRooms.join(owner, group);
        stomp.clear();

        reportMember(member, owner);

        assertThat(peerBlocked(member)).hasSize(1);
        assertThat(peerBlocked(owner)).hasSize(1);
    }

    @Test
    void 한_사람만_공부방에_있으면_PEER_BLOCKED가_없다() {
        enter(member);
        stomp.clear();

        reportMember(member, owner);

        assertThat(stomp.all()).isEmpty();
        assertThat(blocks.blockedAt(member, owner)).contains(NOW);
    }

    // --- SIGNAL ---

    @Test
    void 그룹_방에서_차단_사이_SIGNAL은_어느_쪽으로든_조용히_버린다() {
        String ownerSession = enter(owner);
        String memberSession = enter(member);
        String thirdSession = enter(third);
        blocks.insertBlock(owner, member, NOW);
        stomp.clear();

        signal(roomId(), member, memberSession, owner);
        signal(roomId(), owner, ownerSession, member);
        signal(roomId(), third, thirdSession, owner);
        signal(roomId(), owner, ownerSession, third);

        assertThat(signals(owner))
                .extracting(s -> s.body().path("fromUserId").asLong())
                .containsExactly(third);
        assertThat(signals(member)).isEmpty();
        assertThat(signals(third))
                .extracting(s -> s.body().path("fromUserId").asLong())
                .containsExactly(owner);
    }

    @Test
    void 초대코드_방은_차단_사이여도_SIGNAL이_오가고_PEER_BLOCKED가_없다() {
        blocks.insertBlock(owner, member, NOW);
        String code = roomService.create(owner).inviteCode();
        long roomId =
                roomService.join(owner, code, "그룹장", null, null).response().roomId();
        roomService.join(member, code, "멤버", null, null);
        String ownerSession = sessions.subscribe(owner, roomId);
        String memberSession = sessions.subscribe(member, roomId);
        handler.handleSnapshotRequest(roomId, principal(member), accessor(memberSession));

        signal(roomId, member, memberSession, owner);
        signal(roomId, owner, ownerSession, member);

        assertThat(signals(owner)).hasSize(1);
        assertThat(signals(member)).hasSize(1);
        assertThat(stomp.all()).extracting(Sent::type).doesNotContain(PEER_BLOCKED);
    }

    // --- 입장 확정·스냅샷 재요청 ---

    @Test
    void 입장_확정_때_SNAPSHOT_뒤에_그_세션과_상대에게_같은_모양의_PEER_BLOCKED가_간다() {
        blocks.insertBlock(member, owner, NOW);
        enter(owner);
        enter(third);
        stomp.clear();

        String memberSession = enter(member);

        List<Sent> mine = stomp.queue(member);
        assertThat(mine).extracting(Sent::type).containsExactly("SNAPSHOT", PEER_BLOCKED);
        assertThat(mine).extracting(Sent::sessionId).containsOnly(memberSession);
        JsonNode toNewcomer = mine.get(1).body();
        assertThat(toNewcomer.path("userId").asLong()).isEqualTo(owner);
        // 먼저 와 있던 상대에게는 그 사람의 모든 세션으로, 새로 들어온 사람을 알린다 — MEMBER_JOINED보다 먼저
        List<Sent> theirs = peerBlocked(owner);
        assertThat(theirs).singleElement().satisfies(s -> {
            assertThat(s.body().path("userId").asLong()).isEqualTo(member);
            assertThat(s.sessionId()).isNull();
        });
        assertThat(keys(theirs.getFirst().body())).isEqualTo(keys(toNewcomer));
        assertThat(stomp.all())
                .extracting(Sent::type)
                .containsExactly("SNAPSHOT", PEER_BLOCKED, PEER_BLOCKED, "MEMBER_JOINED");
        assertThat(stomp.queue(third)).isEmpty();
    }

    @Test
    void 차단한_사람이_나중에_들어와도_양쪽이_같은_모양으로_받는다() {
        // 위 테스트와 차단 방향만 반대다 — 받는 메시지로는 어느 쪽이 차단했는지 가릴 수 없어야 한다
        blocks.insertBlock(owner, member, NOW);
        enter(owner);
        stomp.clear();

        enter(member);

        assertThat(peerBlocked(member))
                .singleElement()
                .satisfies(s -> assertThat(s.body().path("userId").asLong()).isEqualTo(owner));
        assertThat(peerBlocked(owner))
                .singleElement()
                .satisfies(s -> assertThat(s.body().path("userId").asLong()).isEqualTo(member));
    }

    @Test
    void 스냅샷을_다시_요청하면_그_세션에만_PEER_BLOCKED가_다시_온다() {
        blocks.insertBlock(member, owner, NOW);
        blocks.insertBlock(third, owner, NOW);
        enter(owner);
        String memberSession = enter(member);
        enter(third);
        stomp.clear();

        handler.handleSnapshotRequest(roomId(), principal(member), accessor(memberSession));

        assertThat(stomp.all()).extracting(Sent::type).containsExactly("SNAPSHOT", PEER_BLOCKED);
        assertThat(stomp.queue(member)).extracting(Sent::sessionId).containsOnly(memberSession);
        assertThat(peerBlocked(member).getFirst().body().path("userId").asLong())
                .isEqualTo(owner);
        assertThat(stomp.queue(owner)).as("재요청은 상대에게 다시 보내지 않는다").isEmpty();
    }

    @Test
    void 차단을_풀면_다음_입장부터_PEER_BLOCKED가_없고_SIGNAL이_오간다() {
        blocks.insertBlock(member, owner, NOW);
        String ownerSession = enter(owner);
        assertThat(v1Delete(mvc, "/api/blocks/" + owner, member)).hasStatus(HttpStatus.NO_CONTENT);
        stomp.clear();

        String memberSession = enter(member);
        signal(roomId(), member, memberSession, owner);
        signal(roomId(), owner, ownerSession, member);

        assertThat(stomp.all()).extracting(Sent::type).doesNotContain(PEER_BLOCKED);
        assertThat(signals(owner)).hasSize(1);
        assertThat(signals(member)).hasSize(1);
    }
}
