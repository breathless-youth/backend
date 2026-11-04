package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;

import java.time.Instant;
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
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.service.GroupRoomService;
import project.study.group.support.BlockProbe;
import project.study.group.support.BlockProbe.ReportRow;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.ApplicationRow;
import project.study.group.support.GroupProbe.MemberRow;
import project.study.group.support.RoomSessions;
import project.study.group.support.StartAlertFixture;
import project.study.group.support.StompCapture;
import project.study.group.support.StompCapture.Sent;
import project.study.push.service.PushSender;
import project.study.room.websocket.SessionRegistry;
import project.study.room.websocket.StompEventListener;
import project.study.user.account.AccountData;

/**
 * 계정 병합(옮기는 쪽 → 남는 쪽) 때 그룹 (BY-1126) — 옮기는 쪽 행을 남는 쪽으로 바꾸고, 부딪히면 남는 쪽이 이긴다. 그룹 개수 한도는 보지 않는다.
 * 병합 트랜잭션({@code AccountLinker.merge})과 같은 순서로 잠그고 옮긴 뒤 옮기는 쪽 users 행까지 지운다. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupAccountMergeTest {

    private static final Instant EARLIER = NOW.minusSeconds(7200);
    private static final Instant LATER = NOW.minusSeconds(3600);

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
    private long source;
    private long target;
    private long owner;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        blocks = new BlockProbe(jdbc);
        alerts = new StartAlertFixture(jdbc, probe);
        source = probe.insertUser("옮기는쪽", null);
        target = probe.insertUser("남는쪽", null);
        owner = probe.insertUser("그룹장", null);
        stomp = StompCapture.on(brokerChannel);
        sessions = new RoomSessions(registry, listener);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        sessions.close();
    }

    /** {@code AccountLinker.merge}와 같은 순서: 옮기는 쪽의 다른 도메인 잠금 → 두 사용자 행 잠금(ID 순) → 처리기 → 옮기는 쪽 users 행 삭제. */
    private void mergeAccounts() {
        tx.executeWithoutResult(status -> {
            accountData.lockBeforeUsers(source);
            jdbc.sql("SELECT id FROM users WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                    .param("ids", List.of(source, target))
                    .query(Long.class)
                    .list();
            accountData.merge(source, target);
            jdbc.sql("DELETE FROM users WHERE id = :id").param("id", source).update();
        });
    }

    private List<MemberRow> rowsOf(long groupId, long userId) {
        return probe.members(groupId).stream()
                .filter(row -> row.userId() == userId)
                .toList();
    }

    private ApplicationRow application(long applicationId) {
        return probe.application(applicationId).orElseThrow();
    }

    @Test
    void 옮기는_쪽이_그룹장이면_남는_쪽이_그룹장이_되고_공부방에_OWNER_CHANGED가_나가며_푸시는_없다() {
        long group = probe.insertGroup(source, "PUBLIC");
        probe.insertMember(group, owner, "APPLICATION");
        long deleted = probe.insertGroup(source, "PRIVATE");
        probe.softDeleteGroup(deleted);
        long roomId = groupRooms.join(owner, group).roomId();
        sessions.subscribe(owner, roomId);
        stomp.clear();

        mergeAccounts();

        assertThat(probe.group(group).orElseThrow().ownerId()).isEqualTo(target);
        assertThat(probe.group(deleted).orElseThrow().ownerId()).isEqualTo(target);
        assertThat(rowsOf(group, target))
                .singleElement()
                .satisfies(row -> assertThat(row.leftAt()).isNull());
        assertThat(stomp.all()).extracting(Sent::type).containsExactly("OWNER_CHANGED");
        assertThat(stomp.lastOnTopic(roomId, "OWNER_CHANGED").path("userId").asLong())
                .isEqualTo(target);
        noPush(pushSender);
    }

    @Test
    void 같은_그룹에_둘_다_살아_있는_멤버면_살아_있는_행이_하나만_남고_나간_이력은_옮긴다() {
        long group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, source, "APPLICATION");
        probe.insertMember(group, target, "INVITE_CODE");
        long kickedFrom = probe.insertGroup(owner, "PUBLIC");
        probe.insertLeftMember(kickedFrom, source, "KICKED", NOW.minusSeconds(60));

        mergeAccounts();

        assertThat(rowsOf(group, source)).isEmpty();
        assertThat(rowsOf(group, target)).singleElement().satisfies(row -> {
            assertThat(row.leftAt()).isNull();
            assertThat(row.joinedVia()).as("남는 쪽 행이 이긴다").isEqualTo("INVITE_CODE");
        });
        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        assertThat(rowsOf(kickedFrom, target))
                .singleElement()
                .satisfies(row -> assertThat(row.leaveReason()).isEqualTo("KICKED"));
    }

    @Test
    void 같은_그룹에_둘_다_답을_기다리면_남는_쪽_신청만_남고_옮기는_쪽은_취소된다() {
        long group = probe.insertGroup(owner, "PUBLIC");
        long sourceApplication = probe.insertApplication(group, source, "PENDING", NOW.plusSeconds(3600));
        long targetApplication = probe.insertApplication(group, target, "PENDING", NOW.plusSeconds(7200));

        mergeAccounts();

        assertThat(application(targetApplication).status()).isEqualTo("PENDING");
        assertThat(application(targetApplication).answeredAt()).isNull();
        ApplicationRow canceled = application(sourceApplication);
        assertThat(canceled.userId()).as("신고가 가리킬 수 있어 지우지 않고 옮긴다").isEqualTo(target);
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.answeredAt()).isEqualTo(NOW);
    }

    @Test
    void 남는_쪽이_멤버가_된_그룹에_기다리던_신청은_양쪽_모두_취소되고_답한_신청은_그대로_옮긴다() {
        long targetIsMember = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(targetIsMember, target, "APPLICATION");
        long sourceApplication = probe.insertApplication(targetIsMember, source, "PENDING", NOW.plusSeconds(3600));
        long sourceIsMember = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(sourceIsMember, source, "APPLICATION");
        long targetApplication = probe.insertApplication(sourceIsMember, target, "PENDING", NOW.plusSeconds(3600));
        long declined =
                probe.insertApplication(probe.insertGroup(owner, "PUBLIC"), source, "DECLINED", NOW.plusSeconds(3600));

        mergeAccounts();

        assertThat(application(sourceApplication).userId()).isEqualTo(target);
        assertThat(application(sourceApplication).status()).isEqualTo("CANCELED");
        assertThat(rowsOf(sourceIsMember, target))
                .singleElement()
                .satisfies(row -> assertThat(row.leftAt()).isNull());
        assertThat(application(targetApplication).status()).isEqualTo("CANCELED");
        assertThat(application(targetApplication).answeredAt()).isEqualTo(NOW);
        assertThat(application(declined).userId()).isEqualTo(target);
        assertThat(application(declined).status()).isEqualTo("DECLINED");
    }

    @Test
    void 서로_신고한_기록은_지우고_다른_신고는_남는_쪽으로_옮긴다() {
        long group = probe.insertGroup(owner, "PUBLIC");
        blocks.insertReport(source, target, "ROOM_MEMBER", group, null);
        blocks.insertReport(target, source, "ROOM_MEMBER", group, null);
        long bySource = blocks.insertReport(source, owner, "ROOM_MEMBER", group, null);
        long aboutSource = blocks.insertReport(owner, source, "ROOM_MEMBER", group, null);

        mergeAccounts();

        assertThat(blocks.reportsBy(target)).extracting(ReportRow::id).containsExactly(bySource);
        assertThat(blocks.reportsAbout(target)).extracting(ReportRow::id).containsExactly(aboutSource);
        assertThat(blocks.reportsBy(owner))
                .singleElement()
                .satisfies(row -> assertThat(row.targetUserId()).isEqualTo(target));
    }

    @Test
    void 멤버_공부_알림_기록이_겹치면_남는_쪽_기록을_두고_나머지는_옮긴다() {
        long both = probe.insertGroup(owner, "PUBLIC");
        long onlySource = probe.insertGroup(owner, "PUBLIC");
        alerts.insertAlert(target, both, EARLIER);
        alerts.insertAlert(source, both, LATER);
        alerts.insertAlert(source, onlySource, LATER);

        mergeAccounts();

        assertThat(alerts.lastSentAt(target, both)).isEqualTo(EARLIER);
        assertThat(alerts.lastSentAt(target, onlySource)).isEqualTo(LATER);
    }
}
