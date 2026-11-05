package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ErrorCode;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;

/** 그룹 응답의 입장 방식과 바로 입장 가능 여부 (BY-1124). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupJoinPolicyReadApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private long owner;
    private long user;
    private long open;
    private long approval;

    @BeforeEach
    void 준비한다() {
        probe = new GroupProbe(jdbc);
        probe.softDeleteAllPublicGroups();
        owner = probe.insertUser();
        user = probe.insertUser();
        open = probe.insertGroup(owner, "PUBLIC");
        approval = probe.insertGroup(owner, "PUBLIC");
        jdbc.sql("update study_group set join_policy = 'OPEN' where id = :id")
                .param("id", open)
                .update();
    }

    @Test
    void 공개_목록과_보기에_입장_방식이_나온다() {
        PublicGroupPageResponse page = assertThat(v1Get(mvc, "/api/groups/public", user))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupPageResponse.class)
                .actual();
        assertThat(page.groups())
                .filteredOn(card -> card.groupId() == open)
                .singleElement()
                .satisfies(card -> assertThat(card.joinPolicy().name()).isEqualTo("OPEN"));
        assertThat(page.groups())
                .filteredOn(card -> card.groupId() == approval)
                .singleElement()
                .satisfies(card -> assertThat(card.joinPolicy().name()).isEqualTo("APPROVAL"));
        assertThat(detail(open).joinPolicy().name()).isEqualTo("OPEN");
        assertThat(detail(approval).joinPolicy().name()).isEqualTo("APPROVAL");
    }

    @Test
    void 홈과_내_그룹_카드는_바로_입장이면_그룹장에게도_새_신청_수를_주지_않는다() {
        probe.insertApplication(approval, user, "PENDING", NOW.plusSeconds(60));
        GroupHomeResponse home = home(open);
        assertThat(home.joinPolicy().name()).isEqualTo("OPEN");
        assertThat(home.newApplicationCount()).isNull();
        assertThat(home(approval).joinPolicy().name()).isEqualTo("APPROVAL");
        assertThat(home(approval).newApplicationCount()).isEqualTo(1);

        MyGroupsResponse mine = assertThat(v1Get(mvc, "/api/groups/me", owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(MyGroupsResponse.class)
                .actual();
        assertThat(mine.groups())
                .filteredOn(card -> card.groupId() == open)
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.joinPolicy().name()).isEqualTo("OPEN");
                    assertThat(card.newApplicationCount()).isNull();
                });
        assertThat(mine.groups())
                .filteredOn(card -> card.groupId() == approval)
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.joinPolicy().name()).isEqualTo("APPROVAL");
                    assertThat(card.newApplicationCount()).isEqualTo(1);
                });
    }

    @Test
    void 대기_신청이_3곳이어도_바로_입장_보기에는_신청_제한이_없다() {
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(user, "PENDING", NOW.plusSeconds(60));
        }
        assertThat(detail(open).applyState()).isEqualTo(ApplyState.CAN_APPLY);
        assertThat(detail(open).unavailableReason()).isNull();
        assertThat(detail(approval).unavailableReason()).isEqualTo(ErrorCode.APPLICATION_LIMIT);
    }

    @Test
    void 바로_입장도_그룹_개수와_내보낸_기록은_보기에서_안내한다() {
        for (int i = 0; i < 5; i++) {
            probe.joinNewGroupOf(user);
        }
        assertThat(detail(open).unavailableReason()).isEqualTo(ErrorCode.GROUP_SLOT_LIMIT);
        probe.insertLeftMember(open, user, "KICKED", NOW.minusSeconds(1));
        assertThat(detail(open).unavailableReason()).isEqualTo(ErrorCode.RECENTLY_KICKED);
    }

    private PublicGroupDetailResponse detail(long id) {
        return assertThat(v1Get(mvc, "/api/groups/public/" + id, user))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupDetailResponse.class)
                .actual();
    }

    private GroupHomeResponse home(long id) {
        return assertThat(v1Get(mvc, "/api/groups/" + id, owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupHomeResponse.class)
                .actual();
    }
}
