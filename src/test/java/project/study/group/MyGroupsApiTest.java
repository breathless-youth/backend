package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.MyGroupsResponse.GroupLimits;
import project.study.group.dto.MyGroupsResponse.MyGroupCard;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.GroupRow;
import project.study.group.support.QueryCounter;
import project.study.group.support.StudyFixture;

/** 내 그룹 탭 — 들어간 순서의 그룹 카드(멤버 책상 포함), 답을 기다리는 신청 수, 그룹장 수, 한도 (BY-1124). 시계는 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class MyGroupsApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private StudyFixture study;
    private long me;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
        me = probe.insertUser("나", null);
    }

    private MyGroupsResponse myGroups(long userId) {
        return assertThat(v1Get(mvc, "/api/groups/me", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(MyGroupsResponse.class)
                .actual();
    }

    @Test
    void 그룹이_없으면_빈_목록과_0_그리고_한도를_준다() {
        MyGroupsResponse response = myGroups(me);

        assertThat(response.groups()).isEmpty();
        assertThat(response.pendingApplicationCount()).isZero();
        assertThat(response.ownedGroupCount()).isZero();
        assertThat(response.limits()).isEqualTo(new GroupLimits(5, 3, 2));
    }

    @Test
    void 그룹은_그룹_ID가_아니라_내가_들어간_순서다() {
        long first = probe.insertGroup(probe.insertUser(), "PUBLIC"); // ID가 작다
        long second = probe.insertGroup(probe.insertUser(), "PRIVATE");
        probe.insertMemberAt(second, me, "INVITE_CODE", NOW.minus(Duration.ofDays(2)));
        probe.insertMemberAt(first, me, "APPLICATION", NOW.minus(Duration.ofDays(1)));
        long mine = probe.insertGroup(me, "PUBLIC"); // 가장 늦게 들어감(만듦)

        assertThat(myGroups(me).groups()).extracting(MyGroupCard::groupId).containsExactly(second, first, mine);
    }

    @Test
    void 카드는_그룹_정보와_내가_그룹장인지_멤버_책상을_준다() {
        long otherOwner = probe.insertUser("그룹장", "avatars/a.jpg");
        long joined = probe.insertGroup(otherOwner, "PRIVATE");
        probe.insertMember(joined, me, "INVITE_LINK");
        long owned = probe.insertGroup(me, "PUBLIC");
        study.studying(me, 590);
        GroupRow joinedRow = probe.group(joined).orElseThrow();

        MyGroupsResponse response = myGroups(me);

        MyGroupCard joinedCard = response.groups().get(0);
        assertThat(joinedCard.groupId()).isEqualTo(joined);
        assertThat(joinedCard.name()).isEqualTo(joinedRow.name());
        assertThat(joinedCard.category().name()).isEqualTo(joinedRow.category());
        assertThat(joinedCard.visibility()).isEqualTo(GroupVisibility.PRIVATE);
        assertThat(joinedCard.memberCount()).isEqualTo(2);
        assertThat(joinedCard.owner()).isFalse();
        assertThat(joinedCard.members()).extracting(GroupMemberDesk::userId).containsExactly(otherOwner, me);
        assertThat(joinedCard.members()).extracting(GroupMemberDesk::owner).containsExactly(true, false);
        assertThat(joinedCard.members().get(0).profileImageUrl()).isEqualTo("/avatars/a.jpg");
        assertThat(joinedCard.members().get(1).status()).isEqualTo(DeskStatus.STUDYING);
        assertThat(joinedCard.members().get(1).todayFocusSec()).isEqualTo(600);
        assertThat(joinedCard.studyingCount()).isEqualTo(1);

        MyGroupCard ownedCard = response.groups().get(1);
        assertThat(ownedCard.groupId()).isEqualTo(owned);
        assertThat(ownedCard.owner()).isTrue();
        assertThat(ownedCard.memberCount()).isEqualTo(1);
        // 같은 사람의 책상은 그룹이 달라도 같은 값이다(앱 전체 기준)
        assertThat(ownedCard.members().getFirst().owner()).isTrue();
        assertThat(ownedCard.members().getFirst())
                .usingRecursiveComparison()
                .ignoringFields("owner")
                .isEqualTo(joinedCard.members().get(1));
    }

    @Test
    void 공부_중인_멤버_수는_멤버_중_STUDYING만_센다() {
        long groupId = probe.insertGroup(me, "PUBLIC");
        long studying = probe.insertUser("공부중", null);
        long paused = probe.insertUser("쉬는중", null);
        long idle = probe.insertUser("쉬는사람", null);
        probe.insertMember(groupId, studying, "APPLICATION");
        probe.insertMember(groupId, paused, "APPLICATION");
        probe.insertMember(groupId, idle, "APPLICATION");
        study.studying(studying, 590);
        study.paused(paused, 300);
        study.session(idle, NOW.minus(Duration.ofHours(3)), 1800);

        MyGroupCard card = myGroups(me).groups().getFirst();

        assertThat(card.members())
                .extracting(GroupMemberDesk::status)
                .containsExactly(DeskStatus.IDLE, DeskStatus.STUDYING, DeskStatus.PAUSED, DeskStatus.IDLE);
        assertThat(card.studyingCount()).isEqualTo(1);
    }

    @Test
    void 나간_그룹과_지워진_그룹은_나오지_않는다() {
        long left = probe.joinNewGroupOf(me);
        probe.leaveGroup(left, me);
        long deleted = probe.joinNewGroupOf(me);
        probe.softDeleteGroup(deleted);
        long stays = probe.joinNewGroupOf(me);

        assertThat(myGroups(me).groups()).extracting(MyGroupCard::groupId).containsExactly(stays);
    }

    @Test
    void 답을_기다리는_신청_수는_기한_안_PENDING만_센다() {
        probe.insertApplicationToNewGroup(me, "PENDING", NOW.plusSeconds(3600));
        probe.insertApplicationToNewGroup(me, "PENDING", NOW.plusSeconds(60));
        probe.insertApplicationToNewGroup(me, "PENDING", NOW.minusSeconds(1)); // 기한 지남 — 만료 작업 전
        probe.insertApplicationToNewGroup(me, "DECLINED", NOW.plusSeconds(3600));
        probe.insertApplicationToNewGroup(me, "CANCELED", NOW.plusSeconds(3600));

        assertThat(myGroups(me).pendingApplicationCount()).isEqualTo(2);
    }

    @Test
    void 새_신청_수는_공개_그룹_그룹장의_카드에만_기한_안_PENDING을_센다() {
        Instant future = NOW.plusSeconds(3600);
        Instant past = NOW.minusSeconds(3600);
        long withRequests = probe.insertGroup(me, "PUBLIC");
        long quiet = probe.insertGroup(me, "PUBLIC");
        long privateGroup = probe.insertGroup(me, "PRIVATE");
        long memberOnly = probe.joinNewGroupOf(me);
        probe.insertApplication(withRequests, probe.insertUser(), "PENDING", future);
        probe.insertApplication(withRequests, probe.insertUser(), "PENDING", future);
        probe.insertApplication(withRequests, probe.insertUser(), "PENDING", past);
        probe.insertApplication(withRequests, probe.insertUser(), "DECLINED", future);
        probe.insertApplication(memberOnly, probe.insertUser(), "PENDING", future);

        Map<Long, Integer> counts = new HashMap<>();
        myGroups(me).groups().forEach(card -> counts.put(card.groupId(), card.newApplicationCount()));

        assertThat(counts.get(withRequests)).isEqualTo(2);
        assertThat(counts.get(quiet)).isZero();
        assertThat(counts.get(privateGroup)).isNull();
        assertThat(counts.get(memberOnly)).isNull();
    }

    @Test
    void 그룹장인_그룹_수는_살아_있는_그룹만_센다() {
        probe.insertGroup(me, "PUBLIC");
        probe.insertGroup(me, "PRIVATE");
        long deleted = probe.insertGroup(me, "PUBLIC");
        probe.softDeleteGroup(deleted);
        probe.joinNewGroupOf(me); // 멤버일 뿐인 그룹

        MyGroupsResponse response = myGroups(me);

        assertThat(response.ownedGroupCount()).isEqualTo(2);
        assertThat(response.groups()).hasSize(3);
    }

    @Test
    void 그룹과_멤버가_늘어도_쿼리_수는_그대로다() {
        long first = probe.joinNewGroupOf(me);
        study.studying(me, 590);
        int oneGroup = QueryCounter.count(() -> myGroups(me));

        // 5그룹 × 6명 = 30명 (설계의 성능 기준 최대치)
        fillWithStudyingMembers(first, 4);
        for (int g = 0; g < 4; g++) {
            fillWithStudyingMembers(probe.joinNewGroupOf(me), 4);
        }
        MyGroupsResponse full = myGroups(me);
        int fiveGroups = QueryCounter.count(() -> myGroups(me));

        assertThat(full.groups())
                .hasSize(5)
                .allSatisfy(card -> assertThat(card.memberCount()).isEqualTo(6));
        assertThat(oneGroup).isPositive();
        assertThat(fiveGroups).isEqualTo(oneGroup);
    }

    private void fillWithStudyingMembers(long groupId, int count) {
        for (int i = 0; i < count; i++) {
            long member = probe.insertUser("멤버" + i, "avatars/" + i + ".jpg");
            probe.insertMember(groupId, member, "APPLICATION");
            study.session(member, NOW.minus(Duration.ofHours(2)), 600);
            study.studying(member, 120);
        }
    }
}
