package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.StartAlertFixture;
import project.study.group.support.StudyFixture;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/**
 * 멤버 공부 시작 알림 (BY-1124) — 새 draft를 한 번만 꺼내, 시작한 사람이 든 그룹마다 알림을 켠 다른 멤버에게 보낸다. 서비스 메서드를 직접 부른다(스케줄러
 * 빈은 테스트에서 꺼져 있다). 시계는 2026-10-14(수) 15:00 KST로 고정이다.
 */
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

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private StudyFixture study;
    private StartAlertFixture fixture;
    private final List<Long> users = new ArrayList<>();
    private long starter;
    private long owner;
    private long group;

    @BeforeEach
    void setUp() {
        // 같은 컨텍스트의 다른 테스트가 남긴 새 draft를 알리지 않고 치운다 — process는 DB 전체의 새 draft를 꺼내므로 남아 있으면 건수가 어긋난다
        jdbc.sql("update active_study_session set start_notified = true where not start_notified")
                .update();
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
        fixture = new StartAlertFixture(jdbc, probe);
        starter = user("공부왕");
        owner = user("그룹장");
        group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, starter, "APPLICATION");
    }

    @AfterEach
    void tearDown() {
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

    @Test
    void 첫_draft는_알림을_켠_다른_멤버에게_한_번_간다() {
        long receiver = member(group);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isEqualTo(1);

        PushMessage message = pushedOnceTo(pushSender, receiver);
        assertThat(message.type()).isEqualTo("group_member_started");
        assertThat(message.title()).isEqualTo(GroupProbe.nickname("공부왕", starter) + "님이 공부를 시작했어요");
        assertThat(message.body()).isEqualTo(probe.group(group).orElseThrow().name() + "에 같이 공부하러 가 볼까요?");
        assertThat(message.link()).isEqualTo("/groups/" + group);
        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(NOW);
    }

    @Test
    void 알림을_끈_멤버와_시작한_본인은_받지_않는다() {
        long receiver = member(group);
        long off = user("꺼둔멤버");
        probe.insertMember(group, off, "APPLICATION");
        probe.setNotify(group, starter, true);
        firstCheckpoint(starter, SESSION_START);

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
        firstCheckpoint(starter, SESSION_START);

        // 자리 있음(확정·예약만·유예 중)은 모두 뺀다. 다른 그룹 방에 앉은 사람과 자리를 비운 사람은 이 그룹 방에 자리가 없어 받는다
        assertThat(alerts.process(NOW)).isEqualTo(3);

        // 받는 사람은 한 그룹에 한 번에 묶여 나간다 — 자리 있는 세 사람은 그 묶음에 없다
        assertThat(sent(1)).extracting(Sent::receivers).containsExactly(List.of(outside, inOtherGroupRoom, left));
    }

    @Test
    void 같은_draft는_두_번_처리해도_한_번만_간다() {
        long receiver = member(group);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isEqualTo(1);
        assertThat(alerts.process(NOW.plusSeconds(10))).isZero();

        pushedOnceTo(pushSender, receiver);
    }

    @Test
    void 한_시간_안의_두_번째_세션_시작은_보내지_않고_한_시간이_지나면_보낸다() {
        long receiver = member(group);
        firstCheckpoint(starter, SESSION_START);
        alerts.process(NOW);

        Instant secondStart = NOW.plus(Duration.ofMinutes(30));
        firstCheckpoint(starter, secondStart);
        assertThat(alerts.process(secondStart.plusSeconds(30))).isZero();
        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(NOW);

        // 1시간이 꼭 지난 때부터 다시 보낸다
        Instant thirdStart = NOW.plus(Duration.ofHours(1)).minusSeconds(30);
        firstCheckpoint(starter, thirdStart);
        Instant exactlyAnHour = NOW.plus(Duration.ofHours(1));
        assertThat(alerts.process(exactlyAnHour)).isEqualTo(1);

        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(exactlyAnHour);
        verify(pushSender, timeout(3000).times(2)).send(eq(List.of(receiver)), any());
    }

    @Test
    void 한_시간에_1초_모자라면_보내지_않는다() {
        long receiver = member(group);
        firstCheckpoint(starter, SESSION_START);
        alerts.process(NOW);

        Instant secondStart = NOW.plus(Duration.ofHours(1)).minusSeconds(31);
        firstCheckpoint(starter, secondStart);
        assertThat(alerts.process(NOW.plus(Duration.ofHours(1)).minusSeconds(1)))
                .isZero();

        assertThat(fixture.lastSentAt(receiver, group)).isEqualTo(NOW);
        verify(pushSender, timeout(3000).times(1)).send(eq(List.of(receiver)), any());
    }

    @Test
    void 시작한_사람이_그룹_둘에_들어_있으면_그룹마다_보낸다() {
        long secondGroup = probe.insertGroup(user("둘째그룹장"), "PUBLIC");
        probe.insertMember(secondGroup, starter, "APPLICATION");
        long both = member(group);
        probe.insertMember(secondGroup, both, "APPLICATION");
        probe.setNotify(secondGroup, both, true);
        long onlyFirst = member(group);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isEqualTo(3);

        // 그룹마다 한 번씩 — 첫 그룹은 둘에게, 둘째 그룹은 둘 다 든 사람에게만
        List<Sent> sent = sent(2);
        assertThat(sent)
                .extracting(entry -> entry.message().link(), Sent::receivers)
                .containsExactlyInAnyOrder(
                        tuple("/groups/" + group, List.of(both, onlyFirst)),
                        tuple("/groups/" + secondGroup, List.of(both)));
    }

    @Test
    void 받을_사람이_여럿이면_한_그룹에_한_번에_보낸다() {
        long first = member(group);
        long second = member(group);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isEqualTo(2);

        assertThat(sent(1)).extracting(Sent::receivers).containsExactly(List.of(first, second));
    }

    @Test
    void 시작한_지_10분이_넘은_draft는_알리지_않고_정확히_10분이면_알린다() {
        long receiver = member(group);
        long lateStarter = user("늦은사람");
        probe.insertMember(group, lateStarter, "APPLICATION");
        long exactStarter = user("딱10분");
        probe.insertMember(group, exactStarter, "APPLICATION");
        firstCheckpoint(lateStarter, NOW.minus(Duration.ofMinutes(10)).minusSeconds(1));
        firstCheckpoint(exactStarter, NOW.minus(Duration.ofMinutes(10)));

        assertThat(alerts.process(NOW)).isEqualTo(1);

        PushMessage message = pushedOnceTo(pushSender, receiver);
        assertThat(message.title()).startsWith(GroupProbe.nickname("딱10분", exactStarter));
        // 늦게 올라온 draft는 알리지 않을 뿐 꺼낸 것으로 표시돼 다음 번에 다시 보이지 않는다
        assertThat(alerts.process(NOW.plusSeconds(10))).isZero();
    }

    @Test
    void 이미_확정된_세션의_draft는_알리지_않는다() {
        long receiver = member(group);
        study.session(starter, SESSION_START, 600);
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isZero();

        noPush(pushSender);
        assertThat(fixture.lastSentAt(receiver, group)).isNull();
    }

    @Test
    void 그룹을_나간_멤버와_그룹_멤버가_아닌_사람의_draft는_조용히_넘어간다() {
        long leaver = member(group);
        probe.leaveGroup(group, leaver);
        long lonely = user("그룹없는사람");
        firstCheckpoint(lonely, SESSION_START);

        assertThat(alerts.process(NOW)).isZero();
        firstCheckpoint(starter, SESSION_START);
        assertThat(alerts.process(NOW)).isZero();

        noPush(pushSender);
    }

    @Test
    void 푸시_발송이_실패해도_처리는_끝나고_다른_그룹_알림은_나간다() {
        long secondGroup = probe.insertGroup(user("둘째그룹장"), "PUBLIC");
        probe.insertMember(secondGroup, starter, "APPLICATION");
        long failing = member(group);
        long fine = member(secondGroup);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(eq(List.of(failing)), any());
        firstCheckpoint(starter, SESSION_START);

        assertThat(alerts.process(NOW)).isEqualTo(2);

        pushedOnceTo(pushSender, fine);
        assertThat(fixture.lastSentAt(failing, group)).isEqualTo(NOW);
    }

    @Test
    void 테스트에서는_스케줄러_빈이_꺼져_있다() {
        assertThat(context.getBeansOfType(GroupStartAlertScheduler.class)).isEmpty();
    }
}
