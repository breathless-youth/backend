package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import project.study.common.category.StudyCategory;
import project.study.group.dto.GroupApplicationsResponse;
import project.study.group.dto.GroupApplicationsResponse.GroupApplicationItem;
import project.study.group.dto.MyApplicationsResponse;
import project.study.group.dto.MyApplicationsResponse.MyApplicationItem;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.ApplicationRow;
import project.study.push.service.PushSender;

/** 신청 취소와 두 신청 목록 (BY-1124) — 남의 신청은 404, 기한 지난 신청은 목록·취소 모두에서 끝난 신청이다. 시계는 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupApplicationListApiTest {

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
        applicant = probe.insertUser("신청자", "avatars/applicant.png");
        group = probe.insertGroup(owner, "PUBLIC");
    }

    private void cancel(long userId, long applicationId, HttpStatus status, String code) {
        assertErrorCode(v1Delete(mvc, "/api/group-applications/" + applicationId, userId), status, code);
    }

    private MyApplicationsResponse mine(long userId) {
        return assertThat(v1Get(mvc, "/api/group-applications/me", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(MyApplicationsResponse.class)
                .actual();
    }

    private GroupApplicationsResponse ofGroup(long userId, long groupId) {
        return assertThat(v1Get(mvc, "/api/groups/" + groupId + "/applications", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupApplicationsResponse.class)
                .actual();
    }

    // --- 취소 ---

    @Test
    void 내_신청을_취소하면_204이고_CANCELED로_바뀌며_푸시는_없다() {
        long id = probe.insertApplication(group, applicant, "PENDING", FUTURE);

        assertThat(v1Delete(mvc, "/api/group-applications/" + id, applicant)).hasStatus(HttpStatus.NO_CONTENT);

        ApplicationRow row = probe.application(id).orElseThrow();
        assertThat(row.status()).isEqualTo("CANCELED");
        assertThat(row.answeredAt()).isEqualTo(NOW);
        noPush(pushSender);
    }

    @Test
    void 취소하면_한도와_같은_그룹_재신청에서_바로_빠진다() {
        long id = probe.insertApplication(group, applicant, "PENDING", FUTURE);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        probe.insertApplicationToNewGroup(applicant, "PENDING", FUTURE);
        assertErrorCode(
                json(
                        v1Post(
                                mvc,
                                "/api/groups/" + probe.insertGroup(probe.insertUser(), "PUBLIC") + "/applications",
                                applicant),
                        """
                        {"introduction": "넷째예요"}"""),
                HttpStatus.CONFLICT,
                "APPLICATION_LIMIT");

        assertThat(v1Delete(mvc, "/api/group-applications/" + id, applicant)).hasStatus(HttpStatus.NO_CONTENT);

        // 3곳에서 2곳이 됐고, 같은 그룹에도 다시 신청할 수 있다(부분 unique에 막히지 않는다)
        assertThat(json(v1Post(mvc, "/api/groups/" + group + "/applications", applicant), """
                        {"introduction": "다시 신청해요"}"""))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void 남의_신청과_없는_신청은_똑같이_404_NOT_FOUND이고_남의_신청은_그대로다() {
        long id = probe.insertApplication(group, applicant, "PENDING", FUTURE);
        long stranger = probe.insertUser();

        cancel(stranger, id, HttpStatus.NOT_FOUND, "NOT_FOUND");
        cancel(owner, id, HttpStatus.NOT_FOUND, "NOT_FOUND");
        cancel(applicant, 987_654_321L, HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(probe.application(id).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void 이미_답했거나_취소_만료된_신청은_409_APPLICATION_NOT_PENDING이다() {
        for (String status : new String[] {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED", "GROUP_DELETED"}) {
            long id =
                    probe.insertApplication(probe.insertGroup(probe.insertUser(), "PUBLIC"), applicant, status, FUTURE);

            cancel(applicant, id, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

            assertThat(probe.application(id).orElseThrow().status()).as(status).isEqualTo(status);
        }
    }

    @Test
    void 기한이_지난_신청은_만료_작업_전이라도_취소할_수_없다() {
        long id = probe.insertApplication(group, applicant, "PENDING", PAST);

        cancel(applicant, id, HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING");

        assertThat(probe.application(id).orElseThrow().status()).isEqualTo("PENDING");
    }

    // --- 내 신청 목록 ---

    @Test
    void 내_신청_목록은_답을_기다리는_것만_신청한_순서로_준다() {
        long second = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long third = probe.insertGroup(probe.insertUser(), "PUBLIC");
        // ID 순서가 아니라 신청한 시각 순서다
        long lateId = probe.insertApplication(group, applicant, "PENDING", NOW.minusSeconds(60), FUTURE);
        long earlyId =
                probe.insertApplication(second, applicant, "PENDING", NOW.minusSeconds(3600), FUTURE.plusSeconds(5));
        long middleId =
                probe.insertApplication(third, applicant, "PENDING", NOW.minusSeconds(600), FUTURE.plusSeconds(9));
        // 아래는 모두 빠진다: 수락·거절·취소·만료 표시, 기한 지난 PENDING, 남의 신청
        for (String status : new String[] {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED"}) {
            probe.insertApplication(probe.insertGroup(probe.insertUser(), "PUBLIC"), applicant, status, FUTURE);
        }
        probe.insertApplicationToNewGroup(applicant, "PENDING", PAST);
        probe.insertApplicationToNewGroup(probe.insertUser(), "PENDING", FUTURE);

        MyApplicationsResponse response = mine(applicant);

        assertThat(response.applications())
                .extracting(MyApplicationItem::applicationId)
                .containsExactly(earlyId, middleId, lateId);
        MyApplicationItem first = response.applications().getFirst();
        assertThat(first.groupId()).isEqualTo(second);
        assertThat(first.groupName())
                .isEqualTo(probe.group(second).orElseThrow().name());
        assertThat(first.category()).isEqualTo(StudyCategory.ETC);
        assertThat(first.appliedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(first.expiresAt()).isEqualTo(FUTURE.plusSeconds(5));
    }

    @Test
    void 신청이_없으면_내_신청_목록은_빈_배열이다() {
        assertThat(v1Get(mvc, "/api/group-applications/me", owner))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.applications", v -> assertThat(v).asList().isEmpty());
    }

    // --- 그룹장용 신청 목록 ---

    @Test
    void 그룹장용_목록은_답을_기다리는_신청을_신청한_순서로_사람_정보와_함께_준다() {
        long second = probe.insertUser("둘째", null);
        long lateId = probe.insertApplication(group, applicant, "PENDING", NOW.minusSeconds(60), FUTURE);
        long earlyId = probe.insertApplication(group, second, "PENDING", NOW.minusSeconds(3600), FUTURE.plusSeconds(5));
        // 아래는 빠진다: 기한 지난 PENDING, 끝난 신청, 다른 그룹의 신청
        probe.insertApplication(group, probe.insertUser(), "PENDING", PAST);
        probe.insertApplication(group, probe.insertUser(), "ACCEPTED", FUTURE);
        probe.insertApplication(group, probe.insertUser(), "CANCELED", FUTURE);
        probe.insertApplicationToNewGroup(probe.insertUser(), "PENDING", FUTURE);

        GroupApplicationsResponse response = ofGroup(owner, group);

        assertThat(response.remainingSeats()).isEqualTo(5);
        assertThat(response.applications())
                .extracting(GroupApplicationItem::applicationId)
                .containsExactly(earlyId, lateId);
        GroupApplicationItem late = response.applications().get(1);
        assertThat(late.userId()).isEqualTo(applicant);
        assertThat(late.nickname()).isEqualTo(GroupProbe.nickname("신청자", applicant));
        assertThat(late.profileImageUrl()).isEqualTo("/avatars/applicant.png");
        assertThat(late.introduction()).isEqualTo("안녕하세요");
        assertThat(late.appliedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(late.expiresAt()).isEqualTo(FUTURE);
        assertThat(response.applications().getFirst().profileImageUrl()).isNull();
    }

    @Test
    void 남은_자리는_6명에서_지금_멤버_수를_뺀_값이고_다_차면_0이다() {
        probe.insertMember(group, probe.insertUser(), "APPLICATION");
        probe.insertMember(group, probe.insertUser(), "APPLICATION");
        assertThat(ofGroup(owner, group).remainingSeats()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            probe.insertMember(group, probe.insertUser(), "APPLICATION");
        }
        assertThat(ofGroup(owner, group).remainingSeats()).isZero();

        // 나간 멤버는 세지 않는다
        long leaver = probe.insertUser();
        probe.insertLeftMember(group, leaver, "LEFT", PAST);
        assertThat(ofGroup(owner, group).remainingSeats()).isZero();
    }

    @Test
    void 신청이_없으면_그룹장용_목록은_빈_배열이다() {
        assertThat(v1Get(mvc, "/api/groups/" + group + "/applications", owner))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.applications", v -> assertThat(v).asList().isEmpty());
    }

    @Test
    void 그룹장이_아니면_멤버든_아니든_403_NOT_GROUP_OWNER다() {
        long member = probe.insertUser();
        probe.insertMember(group, member, "APPLICATION");
        probe.insertApplication(group, applicant, "PENDING", FUTURE);

        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group + "/applications", member), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group + "/applications", applicant),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_OWNER");
    }

    @Test
    void 없거나_지워진_그룹의_신청_목록은_404_GROUP_NOT_FOUND다() {
        long deleted = probe.insertGroup(owner, "PUBLIC");
        probe.softDeleteGroup(deleted);

        assertErrorCode(
                v1Get(mvc, "/api/groups/987654321/applications", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + deleted + "/applications", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }
}
