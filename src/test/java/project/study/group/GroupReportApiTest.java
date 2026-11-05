package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.group.report.ReportAlert;
import project.study.group.report.ReportAlert.Report;
import project.study.group.support.BlockProbe;
import project.study.group.support.BlockProbe.ReportRow;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/**
 * 신청 신고·공부방 멤버 신고의 REST 동작 (BY-1124) — 권한·검사 순서, 신고 행, 차단, 푸시, 운영 알림. 같은 공부방에 있을 때의 {@code PEER_BLOCKED}는
 * {@link GroupRoomBlockStompTest}가 본다. 시계는 고정이고, 운영 알림(슬랙)은 무엇을 넘겼는지만 본다 — 발송은 {@code ReportAlertTest}가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupReportApiTest {

    private static final Instant FUTURE = NOW.plus(Duration.ofDays(1));

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PushSender pushSender;

    @MockitoBean
    private ReportAlert reportAlert;

    private GroupProbe probe;
    private BlockProbe blocks;
    private long owner;
    private long applicant;
    private long member;
    private long group;
    private long application;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        blocks = new BlockProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        applicant = probe.insertUser("신청자", null);
        member = probe.insertUser("멤버", null);
        group = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(group, member, "APPLICATION");
        application = probe.insertApplication(group, applicant, "PENDING", FUTURE);
    }

    private MockMvcRequestBuilder reportApplication(long userId, long applicationId, String body) {
        return json(v1Post(mvc, "/api/group-applications/" + applicationId + "/report", userId), body);
    }

    private MockMvcRequestBuilder reportMember(long userId, long groupId, long targetUserId, String body) {
        return json(v1Post(mvc, "/api/groups/" + groupId + "/members/" + targetUserId + "/report", userId), body);
    }

    private void nothingReported(long reporterId) {
        assertThat(blocks.reportsBy(reporterId)).isEmpty();
        verify(reportAlert, never()).send(any());
    }

    // --- 신청 신고 ---

    @Test
    void 신청을_신고하면_204이고_거절되며_신고가_남고_운영_알림이_간다() {
        assertThat(reportApplication(owner, application, """
                        {"reason": "  부적절한 소개 글이에요 "}""")).hasStatus(HttpStatus.NO_CONTENT);

        GroupProbe.ApplicationRow row = probe.application(application).orElseThrow();
        assertThat(row.status()).isEqualTo("DECLINED");
        assertThat(row.answeredAt()).isEqualTo(NOW);
        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        assertThat(blocks.reportsBy(owner)).singleElement().satisfies(report -> {
            assertThat(report.type()).isEqualTo("APPLICATION");
            assertThat(report.targetUserId()).isEqualTo(applicant);
            assertThat(report.groupId()).isEqualTo(group);
            assertThat(report.applicationId()).isEqualTo(application);
            assertThat(report.reason()).as("앞뒤 공백을 뺀다").isEqualTo("부적절한 소개 글이에요");
            assertThat(report.createdAt()).isEqualTo(NOW);
        });
        verify(reportAlert).send(new Report("APPLICATION", owner, applicant, group, application, "부적절한 소개 글이에요", NOW));
    }

    @Test
    void 신청_신고는_신청한_사람에게_거절_푸시만_보내고_차단하지_않는다() {
        assertThat(reportApplication(owner, application, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        // 거절과 똑같은 푸시다 — 신고됐다는 사실은 신청한 사람에게 드러나지 않는다
        PushMessage message = pushedOnceTo(pushSender, applicant);
        assertThat(message.type()).isEqualTo("group_application_declined");
        assertThat(message.ko().title()).isEqualTo("비슷한 그룹에 신청해 볼까요?");
        assertThat(message.ko().body())
                .isEqualTo(probe.group(group).orElseThrow().name() + " 신청이 거절됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public&category=ETC");
        notPushedTo(pushSender, owner);
        assertThat(blocks.blockedAt(owner, applicant)).isEmpty();
        assertThat(blocks.blockedAt(applicant, owner)).isEmpty();
        assertThat(blocks.reportsBy(owner)).extracting(ReportRow::reason).containsExactly((String) null);
    }

    @Test
    void 공백뿐인_사유는_사유_없음으로_남는다() {
        assertThat(reportApplication(owner, application, """
                        {"reason": "   "}""")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.reportsBy(owner)).extracting(ReportRow::reason).containsExactly((String) null);
    }

    @Test
    void 신청_신고도_그룹장만_하고_거절과_같은_이유_코드다() {
        long done = probe.insertApplication(group, probe.insertUser(), "DECLINED", FUTURE);
        long stale = probe.insertApplication(group, probe.insertUser(), "PENDING", NOW.minusSeconds(1));
        long gone = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long goneApplication = probe.insertApplication(gone, applicant, "PENDING", FUTURE);
        probe.softDeleteGroup(gone);

        assertErrorCode(reportApplication(member, application, "{}"), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(reportApplication(applicant, application, "{}"), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(reportApplication(owner, 987_654_321L, "{}"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertErrorCode(reportApplication(owner, goneApplication, "{}"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertErrorCode(reportApplication(owner, done, "{}"), HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");
        assertErrorCode(reportApplication(owner, stale, "{}"), HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

        assertThat(probe.application(application).orElseThrow().status()).isEqualTo("PENDING");
        nothingReported(owner);
        nothingReported(member);
        noPush(pushSender);
    }

    @Test
    void 신고한_신청은_다시_신고하거나_거절할_수_없다() {
        assertThat(reportApplication(owner, application, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        assertErrorCode(reportApplication(owner, application, "{}"), HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");
        assertErrorCode(
                v1Post(mvc, "/api/group-applications/" + application + "/decline", owner),
                HttpStatus.CONFLICT,
                "APPLICATION_NOT_PENDING");

        assertThat(blocks.reportsBy(owner)).hasSize(1);
        pushedOnceTo(pushSender, applicant);
    }

    // --- 공부방 멤버 신고 ---

    @Test
    void 멤버를_신고하면_204이고_신고와_차단이_생기며_멤버_자격은_그대로다() {
        assertThat(reportMember(member, group, owner, """
                        {"reason": "카메라를 켜고 불쾌한 행동을 했어요"}""")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.reportsBy(member)).singleElement().satisfies(report -> {
            assertThat(report.type()).isEqualTo("ROOM_MEMBER");
            assertThat(report.targetUserId()).isEqualTo(owner);
            assertThat(report.groupId()).isEqualTo(group);
            assertThat(report.applicationId()).isNull();
            assertThat(report.reason()).isEqualTo("카메라를 켜고 불쾌한 행동을 했어요");
            assertThat(report.createdAt()).isEqualTo(NOW);
        });
        assertThat(blocks.blockedAt(member, owner)).contains(NOW);
        assertThat(blocks.blockedAt(owner, member)).as("신고한 쪽의 차단만 생긴다").isEmpty();
        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        assertThat(probe.group(group).orElseThrow().ownerId()).isEqualTo(owner);
        verify(reportAlert).send(new Report("ROOM_MEMBER", member, owner, group, null, "카메라를 켜고 불쾌한 행동을 했어요", NOW));
        noPush(pushSender);
    }

    @Test
    void 이미_차단한_멤버를_다시_신고해도_204이고_차단_시각은_그대로이며_신고는_하나_더_남는다() {
        Instant firstBlock = NOW.minus(Duration.ofDays(2));
        blocks.insertBlock(member, owner, firstBlock);

        assertThat(reportMember(member, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(reportMember(member, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.blockedAt(member, owner)).contains(firstBlock);
        assertThat(blocks.reportsBy(member)).hasSize(2);
        // 차단이 이미 있던 사이의 신고는 기록만 남고 운영 채널에는 가지 않는다
        verify(reportAlert, never()).send(any());
    }

    @Test
    void 같은_멤버를_다시_신고하면_신고는_남기지만_운영_알림은_처음_한_번만_간다() {
        assertThat(reportMember(member, group, owner, """
                        {"reason": "첫 신고"}""")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(reportMember(member, group, owner, """
                        {"reason": "또 신고"}""")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(reportMember(member, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.reportsBy(member))
                .extracting(ReportRow::reason)
                .containsExactlyInAnyOrder("첫 신고", "또 신고", null);
        verify(reportAlert, times(1)).send(any());
        verify(reportAlert).send(new Report("ROOM_MEMBER", member, owner, group, null, "첫 신고", NOW));
    }

    @Test
    void 다른_사람이_같은_멤버를_신고하면_각자의_첫_신고는_운영_알림이_간다() {
        long other = probe.insertUser("다른 멤버", null);
        probe.insertMember(group, other, "APPLICATION");

        assertThat(reportMember(member, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(reportMember(other, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        verify(reportAlert, times(2)).send(any());
    }

    @Test
    void 상대가_나를_차단한_사이에서_신고해도_내_차단이_따로_생긴다() {
        blocks.insertBlock(owner, member, NOW.minus(Duration.ofDays(1)));

        assertThat(reportMember(member, group, owner, "{}")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.blockedAt(member, owner)).contains(NOW);
        assertThat(blocks.blockedAt(owner, member)).contains(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void 자기_자신은_신고할_수_없다() {
        assertErrorCode(reportMember(member, group, member, "{}"), HttpStatus.BAD_REQUEST, "BAD_REQUEST");

        nothingReported(member);
    }

    @Test
    void 멤버가_아니면_403이고_그룹이_없으면_404다() {
        long stranger = probe.insertUser();
        long gone = probe.insertGroup(member, "PUBLIC");
        probe.insertMember(gone, owner, "APPLICATION");
        probe.softDeleteGroup(gone);

        assertErrorCode(reportMember(stranger, group, owner, "{}"), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        // 멤버 확인이 자기 자신 확인보다 먼저다
        assertErrorCode(reportMember(stranger, group, stranger, "{}"), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        assertErrorCode(reportMember(member, 987_654_321L, owner, "{}"), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(reportMember(member, gone, owner, "{}"), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");

        nothingReported(stranger);
        nothingReported(member);
    }

    @Test
    void 대상이_이_그룹_멤버가_아니면_404_GROUP_MEMBER_NOT_FOUND다() {
        long outsider = probe.insertUser();
        long leaver = probe.insertUser();
        probe.insertMember(group, leaver, "APPLICATION");
        probe.leaveGroup(group, leaver);

        assertErrorCode(reportMember(member, group, outsider, "{}"), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(reportMember(member, group, leaver, "{}"), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
        assertErrorCode(
                reportMember(member, group, 987_654_321L, "{}"), HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");

        nothingReported(member);
        assertThat(blocks.blockedAt(member, outsider)).isEmpty();
    }
}
