package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.support.GroupApiTestSupport.status;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
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
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.ApplicationRow;
import project.study.group.support.GroupProbe.MemberRow;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/** 신청 수락·거절 (BY-1124) — 그룹장만, 자리 6명, 신청 상태 검사, 푸시, 동시 수락. 시계는 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupApplicationAnswerApiTest {

    private static final Instant FUTURE = NOW.plus(1, ChronoUnit.DAYS);
    private static final Instant PAST = NOW.minusSeconds(1);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private long owner;
    private long applicant;
    private long group;
    private long application;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        applicant = probe.insertUser("신청자", null);
        group = probe.insertGroup(owner, "PUBLIC");
        application = probe.insertApplication(group, applicant, "PENDING", FUTURE);
    }

    private void accept(long userId, long applicationId, HttpStatus status) {
        assertThat(v1Post(mvc, "/api/group-applications/" + applicationId + "/accept", userId))
                .hasStatus(status);
    }

    private void acceptFails(long userId, long applicationId, HttpStatus status, String code) {
        assertErrorCode(v1Post(mvc, "/api/group-applications/" + applicationId + "/accept", userId), status, code);
    }

    private void declineFails(long userId, long applicationId, HttpStatus status, String code) {
        assertErrorCode(v1Post(mvc, "/api/group-applications/" + applicationId + "/decline", userId), status, code);
    }

    private void fill(long groupId, int size) {
        while (probe.countLiveMembers(groupId) < size) {
            probe.insertMember(groupId, probe.insertUser(), "APPLICATION");
        }
    }

    private String statusOf(long applicationId) {
        return probe.application(applicationId).orElseThrow().status();
    }

    // --- 수락 ---

    @Test
    void 수락하면_204이고_신청한_사람이_멤버가_되며_신청은_ACCEPTED다() {
        accept(owner, application, HttpStatus.NO_CONTENT);

        List<MemberRow> members = probe.members(group);
        assertThat(members).hasSize(2);
        MemberRow joined = members.get(1);
        assertThat(joined.userId()).isEqualTo(applicant);
        assertThat(joined.joinedVia()).isEqualTo("APPLICATION");
        assertThat(joined.notifyEnabled()).isFalse();
        assertThat(joined.leftAt()).isNull();
        ApplicationRow row = probe.application(application).orElseThrow();
        assertThat(row.status()).isEqualTo("ACCEPTED");
        assertThat(row.answeredAt()).isEqualTo(NOW);
    }

    @Test
    void 수락하면_신청한_사람에게만_멤버가_됐다는_푸시가_간다() {
        accept(owner, application, HttpStatus.NO_CONTENT);

        PushMessage message = pushedOnceTo(pushSender, applicant);
        assertThat(message.type()).isEqualTo("group_application_accepted");
        assertThat(message.title()).isEqualTo("새로운 그룹에서 공부를 시작해 볼까요?");
        assertThat(message.body()).isEqualTo(probe.group(group).orElseThrow().name() + "의 멤버가 됐어요");
        assertThat(message.link()).isEqualTo("/groups/" + group);
        notPushedTo(pushSender, owner);
    }

    @Test
    void 멤버가_6명이면_409_GROUP_FULL이고_신청은_그대로_남는다() {
        fill(group, 6);

        acceptFails(owner, application, HttpStatus.CONFLICT, "GROUP_FULL");

        assertThat(statusOf(application)).isEqualTo("PENDING");
        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
        noPush(pushSender);
    }

    @Test
    void 자리가_나면_같은_신청을_다시_수락할_수_있다() {
        fill(group, 6);
        acceptFails(owner, application, HttpStatus.CONFLICT, "GROUP_FULL");
        probe.leaveGroup(group, probe.members(group).get(1).userId());

        accept(owner, application, HttpStatus.NO_CONTENT);

        assertThat(statusOf(application)).isEqualTo("ACCEPTED");
        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
    }

    @Test
    void 신청한_사람의_5개_한도는_수락에서_다시_보지_않는다() {
        // 4개 그룹 + 이 신청 = 5개 — 신청은 이미 5개 안에 들어 있으므로 수락은 6번째가 아니다
        for (int i = 0; i < 4; i++) {
            probe.joinNewGroupOf(applicant);
        }

        accept(owner, application, HttpStatus.NO_CONTENT);

        assertThat(probe.members(group)).extracting(MemberRow::userId).contains(applicant);
    }

    @Test
    void 그룹장이_아니면_403_NOT_GROUP_OWNER이고_아무것도_바뀌지_않는다() {
        long member = probe.insertUser();
        probe.insertMember(group, member, "APPLICATION");
        long stranger = probe.insertUser();

        acceptFails(member, application, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        acceptFails(stranger, application, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        acceptFails(applicant, application, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");

        assertThat(statusOf(application)).isEqualTo("PENDING");
        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        noPush(pushSender);
    }

    @Test
    void 다른_그룹의_그룹장이_수락하면_403이다() {
        long otherOwner = probe.insertUser();
        probe.insertGroup(otherOwner, "PUBLIC");

        acceptFails(otherOwner, application, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
    }

    @Test
    void 없는_신청이거나_그룹이_사라진_신청은_404_NOT_FOUND다() {
        long gone = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long goneApplication = probe.insertApplication(gone, applicant, "PENDING", FUTURE);
        probe.softDeleteGroup(gone);

        acceptFails(owner, 987_654_321L, HttpStatus.NOT_FOUND, "NOT_FOUND");
        acceptFails(owner, goneApplication, HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void 이미_답했거나_취소_만료된_신청은_409_APPLICATION_NOT_PENDING이다() {
        for (String status : new String[] {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED", "GROUP_DELETED"}) {
            long id = probe.insertApplication(group, probe.insertUser(), status, FUTURE);

            acceptFails(owner, id, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

            assertThat(statusOf(id)).as(status).isEqualTo(status);
        }
        noPush(pushSender);
    }

    @Test
    void 기한이_지난_신청은_만료_작업_전이라도_수락할_수_없다() {
        long stale = probe.insertApplication(group, probe.insertUser(), "PENDING", PAST);

        acceptFails(owner, stale, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

        assertThat(statusOf(stale)).isEqualTo("PENDING");
        assertThat(probe.countLiveMembers(group)).isEqualTo(1);
    }

    @Test
    void 검사_순서는_그룹장_신청_상태_자리_순서다() {
        fill(group, 6);
        long done = probe.insertApplication(group, probe.insertUser(), "DECLINED", FUTURE);
        long stranger = probe.insertUser();

        // 그룹장이 아니면 상태·자리보다 먼저 403
        acceptFails(stranger, done, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        // 이미 답한 신청은 자리가 없어도 APPLICATION_NOT_PENDING
        acceptFails(owner, done, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");
        acceptFails(owner, application, HttpStatus.CONFLICT, "GROUP_FULL");
    }

    @Test
    void 수락_뒤_같은_신청을_다시_수락하거나_거절하면_409이고_푸시는_한_번이다() {
        accept(owner, application, HttpStatus.NO_CONTENT);

        acceptFails(owner, application, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");
        declineFails(owner, application, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

        assertThat(probe.countLiveMembers(group)).isEqualTo(2);
        assertThat(pushedOnceTo(pushSender, applicant).type()).isEqualTo("group_application_accepted");
    }

    @Test
    void 자리_하나를_두고_동시에_수락하면_한_명만_멤버가_된다() throws Exception {
        fill(group, 5);
        long other = probe.insertUser();
        long otherApplication = probe.insertApplication(group, other, "PENDING", FUTURE);

        List<Integer> statuses = race(List.<Callable<Integer>>of(
                () -> status(v1Post(mvc, "/api/group-applications/" + application + "/accept", owner)),
                () -> status(v1Post(mvc, "/api/group-applications/" + otherApplication + "/accept", owner))));

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
        List<Long> joined = List.of(applicant, other).stream()
                .filter(id -> probe.members(group).stream().anyMatch(m -> m.userId() == id && m.leftAt() == null))
                .toList();
        assertThat(joined).hasSize(1);
        long winner = joined.getFirst();
        long loser = winner == applicant ? other : applicant;
        assertThat(statusOf(winner == applicant ? application : otherApplication))
                .isEqualTo("ACCEPTED");
        // 못 들어온 사람의 신청은 기한까지 남는다
        assertThat(statusOf(loser == applicant ? application : otherApplication))
                .isEqualTo("PENDING");
        assertThat(pushedOnceTo(pushSender, winner).type()).isEqualTo("group_application_accepted");
        notPushedTo(pushSender, loser);
    }

    @Test
    void 같은_신청을_동시에_수락하거나_취소하면_하나만_이긴다() throws Exception {
        List<Integer> statuses = race(List.<Callable<Integer>>of(
                () -> status(v1Post(mvc, "/api/group-applications/" + application + "/accept", owner)),
                () -> status(v1Delete(mvc, "/api/group-applications/" + application, applicant))));

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        boolean accepted = statusOf(application).equals("ACCEPTED");
        assertThat(statusOf(application)).isIn("ACCEPTED", "CANCELED");
        assertThat(probe.countLiveMembers(group)).isEqualTo(accepted ? 2 : 1);
    }

    // --- 거절 ---

    @Test
    void 거절하면_204이고_DECLINED로_바뀌며_멤버는_늘지_않는다() {
        assertThat(v1Post(mvc, "/api/group-applications/" + application + "/decline", owner))
                .hasStatus(HttpStatus.NO_CONTENT);

        ApplicationRow row = probe.application(application).orElseThrow();
        assertThat(row.status()).isEqualTo("DECLINED");
        assertThat(row.answeredAt()).isEqualTo(NOW);
        assertThat(probe.countLiveMembers(group)).isEqualTo(1);
    }

    @Test
    void 거절하면_신청한_사람에게_그_그룹의_시험_목표로_거른_공개_그룹_링크가_간다() {
        jdbc.sql("update study_group set category = 'CIVIL_SERVICE' where id = :id")
                .param("id", group)
                .update();

        assertThat(v1Post(mvc, "/api/group-applications/" + application + "/decline", owner))
                .hasStatus(HttpStatus.NO_CONTENT);

        PushMessage message = pushedOnceTo(pushSender, applicant);
        assertThat(message.type()).isEqualTo("group_application_declined");
        assertThat(message.title()).isEqualTo("비슷한 그룹에 신청해 볼까요?");
        assertThat(message.body()).isEqualTo(probe.group(group).orElseThrow().name() + " 신청이 거절됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public&category=CIVIL_SERVICE");
        notPushedTo(pushSender, owner);
    }

    @Test
    void 거절은_자리가_없어도_된다() {
        fill(group, 6);

        assertThat(v1Post(mvc, "/api/group-applications/" + application + "/decline", owner))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(statusOf(application)).isEqualTo("DECLINED");
    }

    @Test
    void 거절도_그룹장만_하고_없는_신청은_404이며_끝난_신청은_409다() {
        long member = probe.insertUser();
        probe.insertMember(group, member, "APPLICATION");
        long done = probe.insertApplication(group, probe.insertUser(), "CANCELED", FUTURE);
        long stale = probe.insertApplication(group, probe.insertUser(), "PENDING", PAST);

        declineFails(member, application, HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        declineFails(owner, 987_654_321L, HttpStatus.NOT_FOUND, "NOT_FOUND");
        declineFails(owner, done, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");
        declineFails(owner, stale, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

        assertThat(statusOf(application)).isEqualTo("PENDING");
        assertThat(statusOf(stale)).isEqualTo("PENDING");
        noPush(pushSender);
    }
}
