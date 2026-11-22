package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;
import static project.study.group.support.RoomSessions.accessor;
import static project.study.group.support.RoomSessions.principal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.group.scheduler.GroupStartAlertScheduler;
import project.study.group.service.GroupStartAlerts;
import project.study.group.support.BlockProbe;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.RoomSessions;
import project.study.group.support.StartAlertFixture;
import project.study.group.support.StudyFixture;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomService;
import project.study.room.websocket.RoomStompHandler;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;

/** 그룹 공부방의 실제 공부 시작부터 푸시 발송까지 검증한다 (BY-1382). */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupStartAlertsTest {

    private static final Instant SESSION_START = NOW.minusSeconds(30);

    @Autowired
    private GroupStartAlerts alerts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RoomService rooms;

    @Autowired
    private RoomStompHandler handler;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private StompEventListener listener;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private StudyFixture study;
    private StartAlertFixture fixture;
    private RoomSessions sessions;
    private final List<Long> users = new ArrayList<>();
    private long starter;
    private long owner;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
        fixture = new StartAlertFixture(jdbc, probe);
        sessions = new RoomSessions(registry, listener);
        starter = user("공부왕");
        owner = user("그룹장");
        group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, starter, "APPLICATION");
    }

    @AfterEach
    void tearDown() {
        sessions.close();
        fixture.closeRooms();
        fixture.clearAlerts(users);
        study.clear(users);
    }

    private long user(String nickname) {
        long id = probe.insertUser(nickname, null);
        users.add(id);
        return id;
    }

    /** 알림을 켠 멤버. */
    private long member(long groupId) {
        long id = user("멤버");
        probe.insertMember(groupId, id, "APPLICATION");
        probe.setNotify(groupId, id, true);
        return id;
    }

    private record Sent(List<Long> receivers, PushMessage message) {}

    /** 푸시 발송이 정확히 {@code calls}번 나갈 때까지 기다린 뒤, 더 나가지 않는지 지켜보고 발송마다 받는 사람과 내용을 돌려준다. */
    private List<Sent> sent(int calls) {
        ArgumentCaptor<Collection<Long>> receivers = ArgumentCaptor.captor();
        ArgumentCaptor<PushMessage> messages = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender, timeout(3000).times(calls)).send(receivers.capture(), messages.capture());
        verify(pushSender, after(300).times(calls)).send(any(), any());
        List<Sent> result = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            result.add(new Sent(
                    List.copyOf(receivers.getAllValues().get(i)),
                    messages.getAllValues().get(i)));
        }
        return result;
    }

    private void firstCheckpoint(long userId, Instant startedAt) {
        study.draft(userId, startedAt, startedAt.plusSeconds(30), 25, "[]");
    }

    private record ConnectionInRoom(long roomId, long userId, String sessionId) {}

    private ConnectionInRoom joinRoom(long groupId, long userId) {
        long roomId = fixture.seatInGroupRoom(groupId, userId, false, false);
        return new ConnectionInRoom(roomId, userId, sessions.subscribe(userId, roomId));
    }

    private void camera(ConnectionInRoom connection, boolean on) {
        handler.handleState(
                connection.roomId(),
                new StateUpdatePayload(on, null, null),
                principal(connection.userId()),
                accessor(connection.sessionId()));
    }

    private void startStudy(long groupId, long userId) {
        camera(joinRoom(groupId, userId), true);
    }

    @Test
    void 개인_공부를_시작한_그룹_멤버는_그룹_알림을_보내지_않는다() {
        long receiver = member(group);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isZero();

        noPush(pushSender);
        assertThat(fixture.lastSentAt(receiver, group)).isNull();
    }

    @Test
    void 그룹_공부방에서_공부하면_알림을_켠_다른_멤버에게_한_번_간다() {
        long receiver = member(group);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isEqualTo(1);

        PushMessage message = pushedOnceTo(pushSender, receiver);
        assertThat(message.type()).isEqualTo("group_member_started");
        assertThat(message.ko().title()).isEqualTo(GroupProbe.nickname("공부왕", starter) + "님이 공부를 시작했어요");
        assertThat(message.ko().body())
                .isEqualTo(probe.group(group).orElseThrow().name() + "에 같이 공부하러 가 볼까요?");
        assertThat(message.link()).isEqualTo("/social/groups/" + group);
        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(NOW);
    }

    @Test
    void 알림을_끈_멤버와_시작한_본인은_받지_않는다() {
        long receiver = member(group);
        long off = user("꺼둔멤버");
        probe.insertMember(group, off, "APPLICATION");
        probe.setNotify(group, starter, true);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isEqualTo(1);

        pushedOnceTo(pushSender, receiver);
        notPushedTo(pushSender, off);
        notPushedTo(pushSender, starter);
        notPushedTo(pushSender, owner);
        assertThat(fixture.lastSentAt(off, group)).isNull();
        assertThat(fixture.lastSentAt(starter, group)).isNull();
    }

    @Test
    void 그_그룹_공부방에_자리가_있는_멤버는_받지_않는다() {
        long confirmed = member(group);
        long reserved = member(group);
        long grace = member(group);
        long outside = member(group);
        long inOtherGroupRoom = member(group);
        long left = member(group);
        fixture.seatInGroupRoom(group, confirmed, true, false);
        fixture.seatInGroupRoom(group, reserved, false, false);
        fixture.seatInGroupRoom(group, grace, true, true);
        long otherGroup = probe.insertGroup(user("다른그룹장"), "PUBLIC");
        fixture.seatInGroupRoom(otherGroup, inOtherGroupRoom, true, false);
        // 자리를 이미 비운 사람은 자리가 없는 사람이다
        fixture.seatInGroupRoom(group, left, true, false);
        fixture.leaveSeat(left);
        startStudy(group, starter);

        // 자리 있음(확정·예약만·유예 중)은 모두 뺀다. 다른 그룹 방에 앉은 사람과 자리를 비운 사람은 이 그룹 방에 자리가 없어 받는다
        assertThat(alerts.process(NOW)).isEqualTo(3);

        // 받는 사람은 한 그룹에 한 번에 묶여 나간다 — 자리 있는 세 사람은 그 묶음에 없다
        assertThat(sent(1)).extracting(Sent::receivers).containsExactly(List.of(outside, inOtherGroupRoom, left));
    }

    @Test
    void 시작한_사람과_어느_쪽이든_차단한_사이는_받지_않는다() {
        long blockedByStarter = member(group);
        long blockerOfStarter = member(group);
        long receiver = member(group);
        BlockProbe blocks = new BlockProbe(jdbc);
        blocks.insertBlock(starter, blockedByStarter, NOW);
        blocks.insertBlock(blockerOfStarter, starter, NOW);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isEqualTo(1);

        assertThat(sent(1)).extracting(Sent::receivers).containsExactly(List.of(receiver));
        // 걸러진 사람은 "1시간에 한 번" 기록도 쓰지 않는다 — 기록을 잡기 전에 거른다
        assertThat(fixture.lastSentAt(blockedByStarter, group)).isNull();
        assertThat(fixture.lastSentAt(blockerOfStarter, group)).isNull();
    }

    @Test
    void 카메라를_끄고_켜거나_같은_자리에_다시_연결해도_한_번만_간다() {
        long receiver = member(group);
        ConnectionInRoom room = joinRoom(group, starter);
        camera(room, true);

        assertThat(alerts.process(NOW)).isEqualTo(1);
        camera(room, false);
        camera(room, true);
        String reconnected = sessions.subscribe(starter, room.roomId());
        camera(new ConnectionInRoom(room.roomId(), starter, reconnected), true);

        assertThat(alerts.process(NOW.plus(Duration.ofHours(2)))).isZero();
        pushedOnceTo(pushSender, receiver);
    }

    @Test
    void 한_시간_안의_새_공부_시작은_보내지_않고_한_시간이_지나면_보낸다() {
        long receiver = member(group);
        startStudy(group, starter);
        assertThat(alerts.process(NOW)).isEqualTo(1);

        fixture.leaveSeat(starter);
        startStudy(group, starter);
        assertThat(alerts.process(NOW.plus(Duration.ofHours(1)).minusSeconds(1)))
                .isZero();
        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(NOW);

        fixture.leaveSeat(starter);
        startStudy(group, starter);
        Instant exactlyAnHour = NOW.plus(Duration.ofHours(1));
        assertThat(alerts.process(exactlyAnHour)).isEqualTo(1);

        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(exactlyAnHour);
        verify(pushSender, timeout(3000).times(2)).send(eq(List.of(receiver)), any());
    }

    @Test
    void 시작한_사람이_그룹_둘에_들어_있어도_공부를_시작한_그룹에만_보낸다() {
        long secondGroup = probe.insertGroup(user("둘째그룹장"), "PUBLIC");
        probe.insertMember(secondGroup, starter, "APPLICATION");
        long both = member(group);
        probe.insertMember(secondGroup, both, "APPLICATION");
        probe.setNotify(secondGroup, both, true);
        long onlyFirst = member(group);
        long onlySecond = member(secondGroup);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isEqualTo(2);

        assertThat(sent(1)).singleElement().satisfies(entry -> {
            assertThat(entry.message().link()).isEqualTo("/social/groups/" + group);
            assertThat(entry.receivers()).containsExactly(both, onlyFirst);
        });
        assertThat(fixture.lastSentAt(both, secondGroup)).isNull();
        assertThat(fixture.lastSentAt(onlySecond, secondGroup)).isNull();
    }

    @Test
    void 받을_사람이_여럿이면_한_그룹에_한_번에_보낸다() {
        long first = member(group);
        long second = member(group);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isEqualTo(2);

        assertThat(sent(1)).extracting(Sent::receivers).containsExactly(List.of(first, second));
    }

    @Test
    void 방에_입장만_했거나_옛_소켓이_카메라를_켜면_보내지_않는다() {
        long receiver = member(group);
        ConnectionInRoom room = joinRoom(group, starter);
        camera(room, false);
        camera(new ConnectionInRoom(room.roomId(), starter, "old-session"), true);

        assertThat(alerts.process(NOW)).isZero();

        noPush(pushSender);
        assertThat(fixture.lastSentAt(receiver, group)).isNull();

        camera(room, true);
        assertThat(alerts.process(NOW.plusSeconds(10))).isEqualTo(1);
        pushedOnceTo(pushSender, receiver);
    }

    @Test
    void 그룹_멤버가_초대코드_방에서_공부하면_보내지_않는다() {
        long receiver = member(group);
        String code = rooms.create(starter).inviteCode();
        long roomId = rooms.join(starter, code, "공부왕", null, null).response().roomId();
        try {
            String session = sessions.subscribe(starter, roomId);
            camera(new ConnectionInRoom(roomId, starter, session), true);
            firstCheckpoint(starter, SESSION_START);

            assertThat(alerts.process(NOW)).isZero();

            noPush(pushSender);
            assertThat(fixture.lastSentAt(receiver, group)).isNull();
        } finally {
            rooms.leave(roomId, starter);
        }
    }

    @Test
    void 그룹을_나간_멤버에게는_보내지_않는다() {
        long leaver = member(group);
        probe.leaveGroup(group, leaver);
        startStudy(group, starter);

        assertThat(alerts.process(NOW)).isZero();

        noPush(pushSender);
    }

    @Test
    void 푸시_발송이_실패해도_처리는_끝나고_다른_그룹_알림은_나간다() {
        long secondStarter = user("둘째그룹장");
        long secondGroup = probe.insertGroup(secondStarter, "PUBLIC");
        long failing = member(group);
        long fine = member(secondGroup);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(eq(List.of(failing)), any());
        startStudy(group, starter);
        startStudy(secondGroup, secondStarter);

        assertThat(alerts.process(NOW)).isEqualTo(2);

        pushedOnceTo(pushSender, fine);
        assertThat(fixture.lastSentAt(failing, group)).isEqualTo(NOW);
    }

    @Test
    void 테스트에서는_스케줄러_빈이_꺼져_있다() {
        assertThat(context.getBeansOfType(GroupStartAlertScheduler.class)).isEmpty();
    }
}
