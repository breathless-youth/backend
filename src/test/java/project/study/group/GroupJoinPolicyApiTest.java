package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.assertValidationFailed;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Patch;
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
import project.study.group.dto.GroupCreateResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.push.service.PushSender;

/** 그룹 입장 방식 만들기·변경과 신청 닫기 (BY-1124). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupJoinPolicyApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private long owner;
    private long group;

    @BeforeEach
    void 준비한다() {
        probe = new GroupProbe(jdbc);
        probe.softDeleteAllPublicGroups();
        owner = probe.insertUser();
        group = probe.insertGroup(owner, "PUBLIC", "내 그룹", "ETC");
    }

    @Test
    void 입장_방식을_생략하거나_null이면_신청_후_입장으로_만든다() {
        for (String policy : List.of("", ", \"joinPolicy\": null")) {
            long id = create("PUBLIC", policy);
            assertThat(policyOf(id)).isEqualTo("APPROVAL");
            probe.softDeleteGroup(id);
        }
    }

    @Test
    void 공개_그룹은_바로_입장으로_만들_수_있다() {
        long id = create("PUBLIC", ", \"joinPolicy\": \"OPEN\"");
        assertThat(policyOf(id)).isEqualTo("OPEN");
    }

    @Test
    void 비공개_그룹을_바로_입장으로_만들면_검증_실패다() {
        assertValidationFailed(json(v1Post(mvc, "/api/groups", owner), """
            {"name":"비공개", "category":"ETC", "visibility":"PRIVATE", "joinPolicy":"OPEN"}
            """));
    }

    @Test
    void 바로_입장으로_바꾸면_이_그룹의_대기_신청만_모두_닫고_알리지_않는다() {
        long applicant = probe.insertUser();
        long waiting = probe.insertApplication(group, applicant, "PENDING", NOW.plusSeconds(60));
        long stale = probe.insertApplication(group, probe.insertUser(), "PENDING", NOW.minusSeconds(1));
        long accepted = probe.insertApplication(group, probe.insertUser(), "ACCEPTED", NOW.plusSeconds(60));
        long other = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long untouched = probe.insertApplication(other, applicant, "PENDING", NOW.plusSeconds(60));

        patch(group, "내 그룹", "OPEN");

        assertThat(policyOf(group)).isEqualTo("OPEN");
        for (long id : List.of(waiting, stale)) {
            var row = probe.application(id).orElseThrow();
            assertThat(row.status()).isEqualTo("JOIN_POLICY_OPEN");
            assertThat(row.answeredAt()).isEqualTo(NOW);
        }
        assertThat(probe.application(accepted).orElseThrow().status()).isEqualTo("ACCEPTED");
        assertThat(probe.application(untouched).orElseThrow().status()).isEqualTo("PENDING");
        org.mockito.Mockito.verifyNoInteractions(pushSender);
    }

    @Test
    void 신청_후_입장으로_바꿔도_기존_멤버와_끝난_신청은_그대로다() {
        patch(group, "내 그룹", "OPEN");
        probe.insertMember(group, probe.insertUser(), "OPEN_JOIN");
        var before = probe.members(group);
        long closed = probe.insertApplication(group, probe.insertUser(), "JOIN_POLICY_OPEN", NOW.plusSeconds(60));

        patch(group, "내 그룹", "APPROVAL");

        assertThat(policyOf(group)).isEqualTo("APPROVAL");
        assertThat(probe.members(group)).isEqualTo(before);
        assertThat(probe.application(closed).orElseThrow().status()).isEqualTo("JOIN_POLICY_OPEN");
    }

    @Test
    void 입장_방식을_생략하거나_null이면_기존_값을_유지한다() {
        patch(group, "내 그룹", "OPEN");
        for (String policy : List.of("", ", \"joinPolicy\": null")) {
            assertThat(json(
                            v1Patch(mvc, "/api/groups/" + group, owner),
                            "{\"name\":\"내 그룹\",\"category\":\"ETC\"" + policy + "}"))
                    .hasStatus(HttpStatus.NO_CONTENT);
            assertThat(policyOf(group)).isEqualTo("OPEN");
        }
    }

    @Test
    void 비공개_그룹은_바로_입장으로_바꿀_수_없다() {
        long privateGroup = probe.insertGroup(owner, "PRIVATE");
        assertValidationFailed(json(v1Patch(mvc, "/api/groups/" + privateGroup, owner), update("비공개", "OPEN")));
        assertThat(policyOf(privateGroup)).isEqualTo("APPROVAL");
    }

    @Test
    void 이름이_겹치면_입장_방식과_신청도_그대로고_다른_이름이면_함께_바꾼다() {
        probe.insertGroup(probe.insertUser(), "PUBLIC", "다른 그룹", "ETC");
        long waiting = probe.insertApplication(group, probe.insertUser(), "PENDING", NOW.plus(Duration.ofDays(1)));

        assertErrorCode(
                json(v1Patch(mvc, "/api/groups/" + group, owner), update("다른그룹", "OPEN")),
                HttpStatus.CONFLICT,
                "GROUP_NAME_TAKEN");
        assertThat(policyOf(group)).isEqualTo("APPROVAL");
        assertThat(probe.application(waiting).orElseThrow().status()).isEqualTo("PENDING");

        patch(group, "새 이름", "OPEN");
        assertThat(probe.group(group).orElseThrow().name()).isEqualTo("새 이름");
        assertThat(policyOf(group)).isEqualTo("OPEN");
        assertThat(probe.application(waiting).orElseThrow().status()).isEqualTo("JOIN_POLICY_OPEN");
    }

    private long create(String visibility, String policy) {
        return assertThat(json(
                        v1Post(mvc, "/api/groups", owner),
                        "{\"name\":\"새 그룹\",\"category\":\"ETC\",\"visibility\":\"" + visibility + "\"" + policy + "}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .convertTo(GroupCreateResponse.class)
                .actual()
                .groupId();
    }

    private String policyOf(long id) {
        return jdbc.sql("select join_policy from study_group where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void patch(long id, String name, String policy) {
        assertThat(json(v1Patch(mvc, "/api/groups/" + id, owner), update(name, policy)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private static String update(String name, String policy) {
        return """
            {"name":"%s", "category":"ETC", "joinPolicy":"%s"}
            """.formatted(name, policy);
    }
}
