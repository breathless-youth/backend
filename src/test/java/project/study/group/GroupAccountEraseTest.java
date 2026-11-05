package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.service.GroupRoomService;
import project.study.group.support.BlockProbe;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.GroupRow;
import project.study.group.support.GroupProbe.MemberRow;
import project.study.group.support.RoomSessions;
import project.study.group.support.StartAlertFixture;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;
import project.study.user.account.AccountData;

/**
 * 탈퇴할 때 그룹 (BY-1126) — 그룹장이면 가장 먼저 들어온 다른 멤버가 그룹장이 되고, 혼자면 나가기처럼 그룹이 사라진다. 그 사람의 그룹 행은 모두
 * 지운다. 탈퇴 트랜잭션({@code AccountEraser})과 같은 순서로 잠그고 지운 뒤 users 행까지 지운다. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupAccountEraseTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountData accountData;

    @Autowired
    private TransactionTemplate tx;

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
    private BlockProbe blocks;
    private StartAlertFixture alerts;
    private StompCapture stomp;
    private RoomSessions sessions;
    private long owner;
    private long member;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        blocks = new BlockProbe(jdbc);
        alerts = new StartAlertFixture(jdbc, probe);
        owner = probe.insertUser("그룹장", null);
        member = probe.insertUser("멤버", null);
        group = probe.insertGroup(owner, "PUBLIC");
        stomp = StompCapture.on(brokerChannel);
        sessions = new RoomSessions(registry, listener);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        sessions.close();
    }

    /** {@code AccountEraser}와 같은 순서: 다른 도메인 잠금 → 사용자 행 잠금 → 처리기 → users 행 삭제. 커밋 뒤 푸시·STOMP가 나간다. */
    private void eraseAccount(long userId) {
        tx.executeWithoutResult(status -> {
            accountData.lockBeforeUsers(userId);
            jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                    .param("id", userId)
                    .query(Long.class)
                    .single();
            accountData.erase(userId);
            jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
        });
    }

    private boolean hasNoOwner(long groupId) {
        return jdbc.sql("SELECT owner_id IS NULL FROM study_group WHERE id = :id")
                .param("id", groupId)
                .query(Boolean.class)
                .single();
    }

    private String statusOf(long applicationId) {
        return probe.application(applicationId).orElseThrow().status();
    }

    @Test
    void 그룹장이_탈퇴하면_가장_먼저_들어온_멤버가_그룹장이_되고_그_사람에게만_푸시가_간다() {
        long later = probe.insertUser("나중", null);
        // 멤버 행이 먼저 생긴 사람보다 먼저 들어온 사람이 이긴다
        probe.insertMemberAt(group, later, "APPLICATION", NOW.minusSeconds(3600));
        probe.insertMemberAt(group, member, "APPLICATION", NOW.minusSeconds(7200));

        eraseAccount(owner);

        GroupRow row = probe.group(group).orElseThrow();
        assertThat(row.ownerId()).isEqualTo(member);
        assertThat(row.deletedAt()).isNull();
        assertThat(probe.members(group)).extracting(MemberRow::userId).containsExactlyInAnyOrder(later, member);
        PushMessage message = pushedOnceTo(pushSender, member);
        assertThat(message.type()).isEqualTo("group_owner_transferred");
        assertThat(message.ko().title()).isEqualTo("그룹장이 됐어요");
        assertThat(message.ko().body()).isEqualTo(row.name() + "의 그룹장을 넘겨받았어요");
        notPushedTo(pushSender, later);
    }

    @Test
    void 들어온_시각이_같으면_멤버_행이_먼저_생긴_사람이_그룹장이_된다() {
        long biggerId = probe.insertUser("뒤", null);
        Instant same = NOW.minusSeconds(3600);
        // 사용자 ID는 member가 작지만 멤버 행은 biggerId가 먼저다
        probe.insertMemberAt(group, biggerId, "APPLICATION", same);
        probe.insertMemberAt(group, member, "APPLICATION", same);

        eraseAccount(owner);

        assertThat(probe.group(group).orElseThrow().ownerId()).isEqualTo(biggerId);
    }

    @Test
    void 혼자_남은_그룹장이_탈퇴하면_그룹이_사라지고_답을_기다리던_신청이_닫혀_푸시가_간다() {
        long waiting = probe.insertUser();
        long lapsed = probe.insertUser();
        long waitingId = probe.insertApplication(group, waiting, "PENDING", NOW.plusSeconds(3600));
        // 기한만 지난 신청은 나가기와 같이 만료 작업이 닫는다
        long lapsedId = probe.insertApplication(group, lapsed, "PENDING", NOW.minusSeconds(1));

        eraseAccount(owner);

        GroupRow row = probe.group(group).orElseThrow();
        assertThat(row.deletedAt()).isEqualTo(NOW);
        assertThat(row.inviteCode()).isNull();
        assertThat(hasNoOwner(group)).as("지운 그룹은 그룹장을 비운다").isTrue();
        assertThat(probe.members(group)).isEmpty();
        assertThat(statusOf(waitingId)).isEqualTo("GROUP_DELETED");
        assertThat(probe.application(waitingId).orElseThrow().answeredAt()).isEqualTo(NOW);
        assertThat(statusOf(lapsedId)).isEqualTo("PENDING");
        PushMessage message = pushedOnceTo(pushSender, waiting);
        assertThat(message.type()).isEqualTo("group_application_room_closed");
        assertThat(message.ko().body()).isEqualTo(row.name() + " 그룹이 사라져 신청이 취소됐어요");
        notPushedTo(pushSender, lapsed);
    }

    @Test
    void 공부방이_열려_있으면_새_그룹장으로_OWNER_CHANGED가_나가고_남아_있던_자리는_MEMBER_LEFT로_지운다() {
        probe.insertMember(group, member, "APPLICATION");
        long roomId = groupRooms.join(member, group).roomId();
        sessions.subscribe(member, roomId);
        // 보통은 탈퇴 전에 방을 나가지만(CurrentRoomLeaver), 그 사이 다시 앉았어도 자리를 지운다
        groupRooms.join(owner, group);
        sessions.subscribe(owner, roomId);
        stomp.clear();

        eraseAccount(owner);

        assertThat(stomp.topic(roomId)).extracting(Sent::type).containsExactly("OWNER_CHANGED", "MEMBER_LEFT");
        assertThat(stomp.lastOnTopic(roomId, "OWNER_CHANGED").path("userId").asLong())
                .isEqualTo(member);
        assertThat(stomp.lastOnTopic(roomId, "MEMBER_LEFT").path("userId").asLong())
                .isEqualTo(owner);
    }

    @Test
    void 일반_멤버가_탈퇴하면_그룹과_그룹장은_그대로이고_공부방_자리만_지운다() {
        probe.insertMember(group, member, "APPLICATION");
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        groupRooms.join(member, group);
        sessions.subscribe(member, roomId);
        stomp.clear();

        eraseAccount(member);

        GroupRow row = probe.group(group).orElseThrow();
        assertThat(row.ownerId()).isEqualTo(owner);
        assertThat(row.deletedAt()).isNull();
        assertThat(probe.members(group)).extracting(MemberRow::userId).containsExactly(owner);
        assertThat(stomp.all()).extracting(Sent::type).containsExactly("MEMBER_LEFT");
        noPush(pushSender);
    }

    @Test
    void 이미_지운_그룹의_그룹장이었던_사람도_탈퇴할_수_있다() {
        long deleted = probe.insertGroup(owner, "PRIVATE");
        probe.softDeleteGroup(deleted);
        probe.insertMember(group, member, "APPLICATION");

        eraseAccount(owner);

        assertThat(hasNoOwner(deleted)).isTrue();
        assertThat(probe.group(deleted).orElseThrow().deletedAt()).isNotNull();
        assertThat(probe.members(deleted)).isEmpty();
        assertThat(probe.groupsOwnedBy(member)).extracting(GroupRow::id).containsExactly(group);
    }

    @Test
    void 살아_있는_그룹은_그룹장을_비울_수_없다() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE study_group SET owner_id = NULL WHERE id = :id")
                        .param("id", group)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("study_group_live_owner_chk");
    }

    @Test
    void 탈퇴하면_신청_신고와_알림_기록을_지우고_다른_사람끼리의_신고는_남긴다() {
        probe.insertMember(group, member, "APPLICATION");
        long applicant = probe.insertUser();
        long application = probe.insertApplication(group, applicant, "DECLINED", NOW.plusSeconds(3600));
        blocks.insertReport(owner, applicant, "APPLICATION", group, application);
        blocks.insertReport(applicant, member, "ROOM_MEMBER", group, null);
        long kept = blocks.insertReport(member, owner, "ROOM_MEMBER", group, null);
        alerts.insertAlert(applicant, group, NOW);

        eraseAccount(applicant);

        assertThat(probe.application(application)).isEmpty();
        assertThat(blocks.reportsAbout(applicant)).isEmpty();
        assertThat(blocks.reportsBy(applicant)).isEmpty();
        assertThat(blocks.reportsBy(member))
                .extracting(BlockProbe.ReportRow::id)
                .containsExactly(kept);
        assertThat(alerts.lastSentAt(applicant, group)).isNull();
    }
}
