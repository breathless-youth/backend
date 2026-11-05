package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.support.GroupApiTestSupport.status;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Duration;
import java.util.List;
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
import project.study.config.ApiVersionConfig;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.push.service.PushSender;

/** 공개 그룹 바로 입장의 검사 순서·한도·동시 요청 (BY-1124). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupOpenJoinApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private long group;
    private long user;
    private long owner;

    @BeforeEach
    void 준비한다() {
        probe = new GroupProbe(jdbc);
        probe.softDeleteAllPublicGroups();
        owner = probe.insertUser();
        user = probe.insertUser();
        group = probe.insertGroup(owner, "PUBLIC");
        jdbc.sql("update study_group set join_policy = 'OPEN' where id = :id")
                .param("id", group)
                .update();
    }

    @Test
    void 바로_입장하면_멤버가_되고_그룹_홈을_볼_수_있다() {
        GroupJoinResponse response = join();
        assertThat(response.groupId()).isEqualTo(group);
        assertThat(response.joined()).isTrue();
        assertThat(probe.members(group))
                .filteredOn(row -> row.userId() == user)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.joinedVia()).isEqualTo("OPEN_JOIN");
                    assertThat(row.leftAt()).isNull();
                    assertThat(row.notifyEnabled()).isFalse();
                });
        assertThat(v1Get(mvc, "/api/groups/" + group, user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.myRole", v -> assertThat(v).isEqualTo("MEMBER"));
        org.mockito.Mockito.verifyNoInteractions(pushSender);
    }

    @Test
    void 이미_멤버면_다른_제한보다_먼저_성공하고_다시_넣지_않는다() {
        probe.insertMember(group, user, "OPEN_JOIN");
        probe.insertLeftMember(group, user, "KICKED", NOW.minusSeconds(1));
        fill();
        joinOthers(4);
        assertThat(join().joined()).isFalse();
        assertThat(probe.members(group))
                .filteredOn(row -> row.userId() == user && row.leftAt() == null)
                .hasSize(1);
    }

    @Test
    void 내보낸_지_3일이_안_되면_정원과_개수_제한보다_먼저_막는다() {
        probe.insertLeftMember(
                group, user, "KICKED", NOW.minus(Duration.ofDays(3)).plusSeconds(1));
        fill();
        joinOthers(5);
        fails("RECENTLY_KICKED");
    }

    @Test
    void 내보낸_지_정확히_3일이면_다시_들어올_수_있다() {
        probe.insertLeftMember(group, user, "KICKED", NOW.minus(Duration.ofDays(3)));
        assertThat(join().joined()).isTrue();
    }

    @Test
    void 정원이_6명이면_개수_제한보다_먼저_막는다() {
        fill();
        joinOthers(5);
        fails("GROUP_FULL");
    }

    @Test
    void 그룹과_대기_신청이_합쳐_5개면_막는다() {
        joinOthers(3);
        for (int i = 0; i < 2; i++) {
            probe.insertApplicationToNewGroup(user, "PENDING", NOW.plusSeconds(60));
        }
        fails("GROUP_SLOT_LIMIT");
        assertThat(probe.countLiveMembers(group)).isEqualTo(1);
    }

    @Test
    void 신청이_3곳이어도_합쳐_5개_미만이면_들어온다() {
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(user, "PENDING", NOW.plusSeconds(60));
        }
        joinOthers(1);
        assertThat(join().joined()).isTrue();
    }

    @Test
    void 신청_후_입장과_비공개_그룹은_이미_멤버여도_입장_방식_오류다() {
        for (String visibility : List.of("PUBLIC", "PRIVATE")) {
            long id = probe.insertGroup(owner, visibility);
            assertErrorCode(v1Post(mvc, uri(id), owner), HttpStatus.CONFLICT, "GROUP_JOIN_APPROVAL_REQUIRED");
            assertErrorCode(v1Post(mvc, uri(id), user), HttpStatus.CONFLICT, "GROUP_JOIN_APPROVAL_REQUIRED");
        }
    }

    @Test
    void 없거나_삭제된_그룹은_404다() {
        assertErrorCode(v1Post(mvc, uri(987654321), user), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        probe.softDeleteGroup(group);
        assertErrorCode(v1Post(mvc, uri(group), user), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    @Test
    void 인증이_없으면_401이다() {
        assertErrorCode(
                mvc.post().uri(uri(group)).header(ApiVersionConfig.HEADER, "1"),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED");
    }

    @Test
    void 같은_사람이_동시에_두_번_들어와도_소속은_하나다() throws Exception {
        List<GroupJoinResponse> responses = race(List.of(this::join, this::join));
        assertThat(responses).extracting(GroupJoinResponse::joined).containsExactlyInAnyOrder(true, false);
        assertThat(probe.members(group)).filteredOn(row -> row.userId() == user).hasSize(1);
    }

    @Test
    void 마지막_한_자리에_동시에_들어와도_6명을_넘지_않는다() throws Exception {
        while (probe.countLiveMembers(group) < 5) {
            probe.insertMember(group, probe.insertUser(), "OPEN_JOIN");
        }
        long other = probe.insertUser();
        List<Integer> statuses = race(
                List.of(() -> status(v1Post(mvc, uri(group), user)), () -> status(v1Post(mvc, uri(group), other))));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
    }

    @Test
    void 한_사람이_서로_다른_그룹에_동시에_들어와도_5칸을_넘지_않는다() throws Exception {
        joinOthers(4);
        long other = probe.insertGroup(probe.insertUser(), "PUBLIC");
        jdbc.sql("update study_group set join_policy = 'OPEN' where id = :id")
                .param("id", other)
                .update();

        List<Integer> statuses =
                race(List.of(() -> status(v1Post(mvc, uri(group), user)), () -> status(v1Post(mvc, uri(other), user))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(probe.members(group).stream()
                                .filter(row -> row.userId() == user)
                                .count()
                        + probe.members(other).stream()
                                .filter(row -> row.userId() == user)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void 바로_입장_그룹에_신청하면_멤버_검사보다_먼저_입장_방식_오류다() {
        for (long id : List.of(user, owner)) {
            assertErrorCode(
                    json(v1Post(mvc, "/api/groups/" + group + "/applications", id), "{\"introduction\":\"함께 공부해요\"}"),
                    HttpStatus.CONFLICT,
                    "GROUP_JOIN_OPEN");
        }
    }

    private GroupJoinResponse join() {
        return assertThat(v1Post(mvc, uri(group), user))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupJoinResponse.class)
                .actual();
    }

    private void fails(String code) {
        assertErrorCode(v1Post(mvc, uri(group), user), HttpStatus.CONFLICT, code);
    }

    private static String uri(long id) {
        return "/api/groups/" + id + "/members";
    }

    private void fill() {
        while (probe.countLiveMembers(group) < 6) {
            probe.insertMember(group, probe.insertUser(), "OPEN_JOIN");
        }
    }

    private void joinOthers(int count) {
        for (int i = 0; i < count; i++) {
            probe.joinNewGroupOf(user);
        }
    }
}
