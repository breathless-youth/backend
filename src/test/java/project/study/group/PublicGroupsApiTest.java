package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PublicGroupKit.LAST_SUNDAY;
import static project.study.group.support.PublicGroupKit.MONDAY_NOON;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.PublicGroupPageResponse.PublicGroupCard;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.PublicGroupKit;
import project.study.group.support.QueryCounter;
import project.study.group.support.StudyFixture;
import project.study.ranking.engine.StandingsCache;

/**
 * 공개 그룹 목록 (BY-1124) — 이번 주 평균, 정렬, 카드의 멤버 여부·공부 중 수. 시계는 고정이다. 평균 캐시는 시계가 고정이라 저절로 만료되지 않아 매
 * 테스트 전에 비운다. 같은 컨텍스트의 다른 테스트가 만든 공개 그룹이 목록에 섞이므로 그룹 이름의 표지({@link PublicGroupKit#token})로 자기 그룹만 고른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class PublicGroupsApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StandingsCache standingsCache;

    private PublicGroupKit kit;
    private GroupProbe probe;
    private StudyFixture study;

    @BeforeEach
    void setUp() {
        standingsCache.clear();
        kit = new PublicGroupKit(mvc, jdbc);
        probe = kit.probe;
        study = kit.study;
    }

    @AfterEach
    void tearDown() {
        kit.clear();
        standingsCache.clear();
    }

    // --- 이번 주 평균 ---

    @Test
    void 평균은_지금_멤버들의_이번_주_순공_합을_지금_멤버_수로_나눈_값이고_소수는_버린다() {
        long group = kit.group("-평균");
        long a = kit.member(group);
        long b = kit.member(group);
        // 그룹장(기록 없음) + a + b = 3명. 합 1000 / 3 = 333.33
        study.session(a, MONDAY_NOON, 700);
        study.session(b, MONDAY_NOON, 300);

        PublicGroupCard card = kit.mine().getFirst();

        assertThat(card.memberCount()).isEqualTo(3);
        assertThat(card.weeklyAverageFocusSec()).isEqualTo(333);
    }

    @Test
    void 들어오기_전_순공도_들어가고_나간_멤버와_지난_주_기록과_순공_0초_조각은_들어가지_않는다() {
        long group = kit.group("-범위");
        long owner = probe.group(group).orElseThrow().ownerId();
        long joinedLate = kit.user();
        // 월요일 정오에 공부하고 수요일에 들어왔다 — 들어오기 전 순공도 이번 주 앱 전체 순공이다
        probe.insertMemberAt(group, joinedLate, "APPLICATION", NOW.minusSeconds(3600));
        study.session(joinedLate, MONDAY_NOON, 600);
        // 나간 멤버의 순공은 빠진다
        long left = kit.user();
        probe.insertLeftMember(group, left, "LEFT", NOW.minusSeconds(60));
        study.session(left, MONDAY_NOON, 9000);
        // 지난 주 기록과 순공 0초 조각은 이번 주 순공이 아니다
        study.session(owner, LAST_SUNDAY, 5000);
        study.session(owner, MONDAY_NOON, 0);

        PublicGroupCard card = kit.mine().getFirst();

        // 멤버는 그룹장과 joinedLate 둘이고 합은 600이다
        assertThat(card.memberCount()).isEqualTo(2);
        assertThat(card.weeklyAverageFocusSec()).isEqualTo(300);
    }

    @Test
    void 진행_중인_draft의_순공도_평균에_들어간다() {
        long group = kit.group("-진행중");
        long member = kit.member(group);
        // 마지막 수신 10초 전, 보고된 순공 590초 + 지금까지 늘린 10초
        study.studying(member, 590);

        assertThat(kit.mine().getFirst().weeklyAverageFocusSec()).isEqualTo(300);
    }

    @Test
    void 기록이_없는_그룹의_평균은_0이다() {
        kit.group("-빈");

        assertThat(kit.mine().getFirst().weeklyAverageFocusSec()).isZero();
    }

    @Test
    void 평균은_약_1분_캐시라_그_안의_변화는_보이지_않고_캐시를_비우면_보인다() {
        long group = kit.group("-캐시");
        long owner = probe.group(group).orElseThrow().ownerId();
        assertThat(kit.mine().getFirst().weeklyAverageFocusSec()).isZero();

        study.session(owner, MONDAY_NOON, 600);

        assertThat(kit.mine().getFirst().weeklyAverageFocusSec()).isZero();
        standingsCache.clear();
        assertThat(kit.mine().getFirst().weeklyAverageFocusSec()).isEqualTo(600);
    }

    // --- 정렬 ---

    @Test
    void 정렬은_다_차지_않은_그룹이_먼저이고_평균_큰_순_그다음_그룹_ID_순이다() {
        // 만든 순서와 기대 순서가 다르도록 섞어 만든다. 다 찬 그룹은 ID가 가장 작고 평균이 가장 크다
        long full = kit.group("-full");
        List<Long> fullMembers = kit.join(full, 5);
        study.session(fullMembers.getFirst(), MONDAY_NOON, 6000);
        long low = kit.group("-low");
        long tieFirst = kit.group("-tie1");
        long tieSecond = kit.group("-tie2");
        long high = kit.group("-high");
        study.session(probe.group(tieFirst).orElseThrow().ownerId(), MONDAY_NOON, 500);
        study.session(probe.group(tieSecond).orElseThrow().ownerId(), MONDAY_NOON, 500);
        study.session(probe.group(high).orElseThrow().ownerId(), MONDAY_NOON, 1000);

        List<PublicGroupCard> cards = kit.mine();

        assertThat(PublicGroupKit.ids(cards)).containsExactly(high, tieFirst, tieSecond, low, full);
        assertThat(cards.getLast().memberCount()).isEqualTo(6);
        // 다 찬 그룹의 평균 6000/6 = 1000은 high와 같지만 뒤로 간다
        assertThat(cards.getLast().weeklyAverageFocusSec()).isEqualTo(1000);
    }

    // --- 카드 ---

    @Test
    void 카드는_내가_멤버인지와_공부_중인_멤버_수를_준다() {
        long joined = kit.group("-내그룹");
        probe.insertMember(joined, kit.me, "APPLICATION");
        long other = kit.group("-남의그룹");
        long studying = kit.member(other);
        long paused = kit.member(other);
        long idle = kit.member(other);
        study.studying(studying, 590);
        study.paused(paused, 300);
        study.session(idle, NOW.minusSeconds(3 * 3600), 1800);

        List<PublicGroupCard> cards = kit.mine();

        PublicGroupCard joinedCard = PublicGroupKit.card(cards, joined);
        PublicGroupCard otherCard = PublicGroupKit.card(cards, other);
        assertThat(joinedCard.member()).isTrue();
        assertThat(joinedCard.studyingCount()).isZero();
        assertThat(joinedCard.memberCount()).isEqualTo(2);
        assertThat(otherCard.member()).isFalse();
        assertThat(otherCard.memberCount()).isEqualTo(4);
        // 측정 중(STUDYING)인 한 명만 센다 — 일시정지·쉬는 중·기록 없는 그룹장은 세지 않는다
        assertThat(otherCard.studyingCount()).isEqualTo(1);
    }

    @Test
    void 나간_그룹은_내가_멤버가_아니다() {
        long group = kit.group("-나감");
        probe.insertMember(group, kit.me, "APPLICATION");
        probe.leaveGroup(group, kit.me);

        assertThat(kit.mine().getFirst().member()).isFalse();
    }

    @Test
    void 카드의_공부_중_수는_그_쪽_그룹만_책상을_한_번에_읽어_세므로_쿼리_수가_그룹_수와_상관없다() {
        for (int i = 0; i < 6; i++) {
            kit.join(kit.group("-s" + i), 2);
        }
        kit.list("query", kit.token); // 평균 캐시를 채워 두고 센다

        int one = QueryCounter.count(() -> kit.list("query", kit.token, "size", "1"));
        int six = QueryCounter.count(() -> kit.list("query", kit.token, "size", "6"));

        // 쿼리 수를 세고 있어야(0 == 0이 아니어야) 같다는 단언이 뜻을 가진다
        assertThat(one).isPositive();
        assertThat(six).isEqualTo(one);
    }
}
