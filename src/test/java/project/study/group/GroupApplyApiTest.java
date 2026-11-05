package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.support.GroupApiTestSupport.status;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.notPushedTo;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.group.dto.GroupApplicationCreateResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.ApplicationRow;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/** 그룹 신청 보내기 (BY-1124) — 검사 순서와 이유 코드, 기한 지난 신청, 그룹장 푸시, 동시 요청. 시계는 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupApplyApiTest {

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

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        applicant = probe.insertUser("신청자", null);
        group = probe.insertGroup(owner, "PUBLIC");
    }

    private MockMvcRequestBuilder apply(long userId, long groupId, String introduction) {
        return json(v1Post(mvc, "/api/groups/" + groupId + "/applications", userId), """
                {"introduction": "%s"}""".formatted(introduction));
    }

    private void assertConflict(MockMvcRequestBuilder request, String code) {
        assertErrorCode(request, HttpStatus.CONFLICT, code);
    }

    @Test
    void 신청하면_201과_신청_ID_만료_시각을_주고_답을_기다리는_행이_생긴다() {
        GroupApplicationCreateResponse response = assertThat(apply(applicant, group, "새벽 5시에 일어나요"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .convertTo(GroupApplicationCreateResponse.class)
                .actual();

        assertThat(response.expiresAt()).isEqualTo(NOW.plus(3, ChronoUnit.DAYS));
        ApplicationRow row = probe.application(response.applicationId()).orElseThrow();
        assertThat(row.groupId()).isEqualTo(group);
        assertThat(row.userId()).isEqualTo(applicant);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.introduction()).isEqualTo("새벽 5시에 일어나요");
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.expiresAt()).isEqualTo(response.expiresAt());
        assertThat(row.answeredAt()).isNull();
    }

    @Test
    void 소개_글은_앞뒤_공백을_떼고_NFD는_NFC로_저장한다() {
        String nfd = Normalizer.normalize("한글 공부", Normalizer.Form.NFD);

        assertThat(apply(applicant, group, "  " + nfd + "  ")).hasStatus(HttpStatus.CREATED);

        assertThat(probe.applications(group, applicant).getFirst().introduction())
                .isEqualTo("한글 공부");
    }

    @Test
    void 신청하면_그룹장에게만_새_신청_푸시가_간다() {
        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);

        PushMessage message = pushedOnceTo(pushSender, owner);
        assertThat(message.type()).isEqualTo("group_application_received");
        assertThat(message.ko().title()).isEqualTo("새 신청을 확인해 볼까요?");
        assertThat(message.ko().body())
                .isEqualTo(GroupProbe.nickname("신청자", applicant) + "님이 "
                        + probe.group(group).orElseThrow().name() + "에 신청했어요");
        assertThat(message.link()).isEqualTo("/groups/" + group + "/applications");
        notPushedTo(pushSender, applicant);
    }

    @Test
    void 닉네임이_없는_신청자여도_푸시는_나간다() {
        long nameless = probe.insertUser();

        assertThat(apply(nameless, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);

        assertThat(pushedOnceTo(pushSender, owner).ko().body()).startsWith("새 사용자님이 ");
    }

    @Test
    void 연락처가_있으면_400_CONTACT_NOT_ALLOWED이고_신청은_안_생긴다() {
        assertErrorCode(apply(applicant, group, "www.open.com 에서 봐요"), HttpStatus.BAD_REQUEST, "CONTACT_NOT_ALLOWED");

        assertThat(probe.applications(group, applicant)).isEmpty();
        noPush(pushSender);
    }

    @Test
    void 연락처_검사가_그룹_확인보다_먼저다() {
        assertErrorCode(
                apply(applicant, 987_654_321L, "010-1234-5678로 연락줘"), HttpStatus.BAD_REQUEST, "CONTACT_NOT_ALLOWED");
    }

    @Test
    void 없는_그룹과_삭제된_그룹과_비공개_그룹은_똑같이_404_GROUP_NOT_FOUND다() {
        long deleted = probe.insertGroup(probe.insertUser(), "PUBLIC");
        probe.softDeleteGroup(deleted);
        long hidden = probe.insertGroup(probe.insertUser(), "PRIVATE");

        assertErrorCode(apply(applicant, 987_654_321L, "안녕하세요"), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(apply(applicant, deleted, "안녕하세요"), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(apply(applicant, hidden, "안녕하세요"), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertThat(probe.applications(hidden, applicant)).isEmpty();
    }

    @Test
    void 이미_멤버이면_409_ALREADY_MEMBER다_그룹장도_마찬가지다() {
        probe.insertMember(group, applicant, "APPLICATION");

        assertConflict(apply(applicant, group, "안녕하세요"), "ALREADY_MEMBER");
        assertConflict(apply(owner, group, "안녕하세요"), "ALREADY_MEMBER");
    }

    @Test
    void 답을_기다리는_신청이_있으면_409_ALREADY_APPLIED이고_옛_신청은_그대로다() {
        long existing = probe.insertApplication(group, applicant, "PENDING", FUTURE);

        assertConflict(apply(applicant, group, "또 신청해요"), "ALREADY_APPLIED");

        assertThat(probe.applications(group, applicant)).hasSize(1);
        assertThat(probe.application(existing).orElseThrow().status()).isEqualTo("PENDING");
        noPush(pushSender);
    }

    @Test
    void 이미_답을_받은_신청은_ALREADY_APPLIED가_아니다() {
        probe.insertApplication(group, applicant, "DECLINED", FUTURE);
        probe.insertApplication(group, applicant, "CANCELED", FUTURE);

        assertThat(apply(applicant, group, "다시 신청해요")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 내보내진_지_3일이_안_됐으면_409_RECENTLY_KICKED다() {
        probe.insertLeftMember(group, applicant, "KICKED", NOW.minus(2, ChronoUnit.DAYS));

        assertConflict(apply(applicant, group, "안녕하세요"), "RECENTLY_KICKED");
    }

    @Test
    void 내보내진_지_3일이_지났거나_스스로_나갔으면_신청할_수_있다() {
        probe.insertLeftMember(
                group, applicant, "KICKED", NOW.minus(3, ChronoUnit.DAYS).minusSeconds(1));
        long leaver = probe.insertUser();
        probe.insertLeftMember(group, leaver, "LEFT", NOW.minus(1, ChronoUnit.HOURS));

        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);
        assertThat(apply(leaver, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 다른_그룹에서_내보내진_것은_이_그룹_신청을_막지_않는다() {
        long other = probe.insertGroup(probe.insertUser(), "PUBLIC");
        probe.insertLeftMember(other, applicant, "KICKED", NOW.minus(1, ChronoUnit.HOURS));

        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 멤버가_6명이면_409_GROUP_FULL이다() {
        fill(group, 6);

        assertConflict(apply(applicant, group, "안녕하세요"), "GROUP_FULL");

        assertThat(probe.applications(group, applicant)).isEmpty();
        noPush(pushSender);
    }

    @Test
    void 멤버가_5명이면_신청할_수_있다() {
        fill(group, 5);

        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 들어간_그룹과_답을_기다리는_신청이_합쳐_5개면_409_GROUP_SLOT_LIMIT이다() {
        probe.joinNewGroupOf(applicant);
        probe.joinNewGroupOf(applicant);
        probe.joinNewGroupOf(applicant);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);

        assertConflict(apply(applicant, group, "안녕하세요"), "GROUP_SLOT_LIMIT");
    }

    @Test
    void 들어간_그룹이_4개이면_다섯_번째로_신청할_수_있고_그_뒤는_막힌다() {
        for (int i = 0; i < 4; i++) {
            probe.joinNewGroupOf(applicant);
        }

        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);

        long next = probe.insertGroup(probe.insertUser(), "PUBLIC");
        assertConflict(apply(applicant, next, "안녕하세요"), "GROUP_SLOT_LIMIT");
    }

    @Test
    void 답을_기다리는_신청이_3곳이면_409_APPLICATION_LIMIT이다() {
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        }

        assertConflict(apply(applicant, group, "안녕하세요"), "APPLICATION_LIMIT");
    }

    @Test
    void 두_한도가_함께_걸리면_GROUP_SLOT_LIMIT이_먼저다() {
        probe.joinNewGroupOf(applicant);
        probe.joinNewGroupOf(applicant);
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        }

        assertConflict(apply(applicant, group, "안녕하세요"), "GROUP_SLOT_LIMIT");
    }

    @Test
    void 검사_순서는_멤버_신청_내보냄_자리_다음에_한도다() {
        // 6명인 그룹에 이미 답을 기다리는 신청이 있고 내보내진 기록도 있으면 ALREADY_APPLIED가 먼저다
        fill(group, 6);
        long kicked = probe.insertUser();
        probe.insertLeftMember(group, kicked, "KICKED", NOW.minus(1, ChronoUnit.HOURS));
        probe.insertApplication(group, kicked, "PENDING", FUTURE);
        assertConflict(apply(kicked, group, "안녕하세요"), "ALREADY_APPLIED");

        // 내보냄이 자리보다 먼저다
        long kicked2 = probe.insertUser();
        probe.insertLeftMember(group, kicked2, "KICKED", NOW.minus(1, ChronoUnit.HOURS));
        assertConflict(apply(kicked2, group, "안녕하세요"), "RECENTLY_KICKED");

        // 자리가 한도보다 먼저다
        for (int i = 0; i < 5; i++) {
            probe.joinNewGroupOf(applicant);
        }
        assertConflict(apply(applicant, group, "안녕하세요"), "GROUP_FULL");
    }

    @Test
    void 기한이_지난_신청은_개수_제한에_세지_않는다() {
        probe.joinNewGroupOf(applicant);
        probe.joinNewGroupOf(applicant);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        // 만료 작업이 아직 안 돈 기한 지난 신청 셋 — 세면 5개·3곳을 넘어 막힌다
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(applicant, "PENDING", PAST);
        }

        assertThat(apply(applicant, group, "안녕하세요")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 기한이_지난_신청이_남은_같은_그룹에_다시_신청하면_옛_신청을_EXPIRED로_바꾸고_만료_푸시를_한_번_보낸다() {
        long stale = probe.insertApplication(group, applicant, "PENDING", PAST);

        GroupApplicationCreateResponse response = assertThat(apply(applicant, group, "다시 신청해요"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .convertTo(GroupApplicationCreateResponse.class)
                .actual();

        ApplicationRow old = probe.application(stale).orElseThrow();
        assertThat(old.status()).isEqualTo("EXPIRED");
        assertThat(old.answeredAt()).isEqualTo(NOW);
        ApplicationRow fresh = probe.application(response.applicationId()).orElseThrow();
        assertThat(fresh.status()).isEqualTo("PENDING");
        assertThat(fresh.introduction()).isEqualTo("다시 신청해요");

        PushMessage expired = pushedOnceTo(pushSender, applicant);
        assertThat(expired.type()).isEqualTo("group_application_expired");
        assertThat(expired.ko().body())
                .isEqualTo(probe.group(group).orElseThrow().name() + " 신청은 답이 없어 취소됐어요");
        assertThat(pushedOnceTo(pushSender, owner).type()).isEqualTo("group_application_received");
    }

    @Test
    void 자리가_없어_막히면_기한_지난_옛_신청도_그대로_남고_만료_푸시도_없다() {
        long stale = probe.insertApplication(group, applicant, "PENDING", PAST);
        fill(group, 6);

        assertConflict(apply(applicant, group, "안녕하세요"), "GROUP_FULL");

        // 만료 처리는 신청이 받아들여질 때만 한다 — 거절된 요청이 상태를 바꾸지 않는다
        assertThat(probe.application(stale).orElseThrow().status()).isEqualTo("PENDING");
        noPush(pushSender);
    }

    @Test
    void 같은_사용자가_동시에_신청_4개를_보내면_3개만_받아들여진다() throws Exception {
        List<Long> groups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            groups.add(probe.insertGroup(probe.insertUser(), "PUBLIC"));
        }

        List<Integer> statuses = race(groups.stream()
                .<Callable<Integer>>map(id -> () -> status(apply(applicant, id, "동시에 신청해요")))
                .toList());

        assertThat(statuses).containsExactlyInAnyOrder(201, 201, 201, 409);
        long pending = groups.stream()
                .filter(id -> !probe.applications(id, applicant).isEmpty())
                .count();
        assertThat(pending).isEqualTo(3);
    }

    @Test
    void 같은_사용자가_같은_그룹에_동시에_두_번_신청하면_하나만_받아들여진다() throws Exception {
        List<Integer> statuses = race(List.<Callable<Integer>>of(
                () -> status(apply(applicant, group, "동시에 신청해요")), () -> status(apply(applicant, group, "동시에 신청해요"))));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(probe.applications(group, applicant)).hasSize(1);
        assertThat(pushedOnceTo(pushSender, owner).type()).isEqualTo("group_application_received");
    }

    @Test
    void 인증_없으면_401이다() {
        assertThat(mvc.post()
                        .uri("/api/groups/" + group + "/applications")
                        .header(ApiVersionConfig.HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"introduction\": \"안녕하세요\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** 그룹을 멤버 수 {@code size}명이 되도록 채운다(그룹장 포함). */
    private void fill(long groupId, int size) {
        while (probe.countLiveMembers(groupId) < size) {
            probe.insertMember(groupId, probe.insertUser(), "APPLICATION");
        }
    }
}
