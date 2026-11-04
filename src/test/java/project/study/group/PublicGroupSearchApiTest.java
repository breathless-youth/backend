package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.dto.PublicGroupPageResponse.PublicGroupCard;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.PublicGroupKit;
import project.study.group.support.StudyFixture;
import project.study.ranking.engine.StandingsCache;

/** 공개 그룹 목록의 이름 검색·시험 목표 거르기·쪽 나누기·입력 한도 (BY-1124). 시계는 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class PublicGroupSearchApiTest {

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

    // --- 검색·거르기 ---

    @Test
    void 이름_검색은_대소문자를_무시하고_이름_가운데_글자도_찾는다() {
        long hit = kit.group("-Algo 스터디");
        long other = kit.group("-다른 모임");

        assertThat(kit.search((kit.token + "-ALGO").toLowerCase())).containsExactly(hit);
        assertThat(kit.search((kit.token + "-algo").toUpperCase())).containsExactly(hit);
        // 이름 앞이 아니라 가운데에 든 글자도 찾는다 — 표지에서 앞의 두 글자("Zq")를 뗀다
        assertThat(kit.search(kit.token.substring(2).toUpperCase())).containsExactlyInAnyOrder(hit, other);
    }

    @Test
    void 퍼센트와_밑줄은_와일드카드가_아니라_글자다() {
        long percent = kit.group("a%b");
        long underscore = kit.group("a_b");
        long plain = kit.group("axb");

        assertThat(kit.search(kit.token + "a%")).containsExactly(percent);
        assertThat(kit.search(kit.token + "a_")).containsExactly(underscore);
        assertThat(kit.search(kit.token + "a")).containsExactlyInAnyOrder(percent, underscore, plain);
    }

    @Test
    void 검색어는_앞뒤_공백을_떼고_NFC로_맞춘다() {
        long hit = kit.group("-한글 모임");
        String nfd = Normalizer.normalize("한글", Normalizer.Form.NFD);

        assertThat(kit.search("  " + kit.token + "-" + nfd + "  ")).containsExactly(hit);
    }

    @Test
    void 공백뿐인_검색어는_검색어_없음이라_모든_공개_그룹이_나온다() {
        long first = kit.group("-전체1");
        long second = kit.group("-전체2");
        List<Long> seen = new ArrayList<>();
        for (int page = 0; ; page++) {
            PublicGroupPageResponse response = kit.list("query", "   ", "size", "50", "page", String.valueOf(page));
            seen.addAll(PublicGroupKit.ids(response.groups()));
            if (!response.hasNext()) {
                break;
            }
        }

        assertThat(seen).contains(first, second);
    }

    @Test
    void 시험_목표_하나로_거른다() {
        long civil = kit.group("-공무원", "CIVIL_SERVICE");
        long csat = kit.group("-수능", "CSAT");
        kit.group("-기타", "ETC");

        List<PublicGroupCard> civilCards = kit.list("query", kit.token, "category", "CIVIL_SERVICE", "size", "50")
                .groups();

        assertThat(PublicGroupKit.ids(civilCards)).containsExactly(civil);
        assertThat(civilCards.getFirst().category().name()).isEqualTo("CIVIL_SERVICE");
        assertThat(PublicGroupKit.ids(kit.list("query", kit.token, "category", "CSAT", "size", "50")
                        .groups()))
                .containsExactly(csat);
        assertThat(kit.mine()).hasSize(3);
    }

    @Test
    void 없는_시험_목표는_400이다() {
        assertThat(kit.request("category", "NOPE")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 비공개와_지워진_그룹은_목록에_없다() {
        long visible = kit.group("-공개");
        probe.insertGroup(kit.user(), "PRIVATE", kit.token + "-비공개", "ETC");
        long deleted = kit.group("-삭제");
        probe.softDeleteGroup(deleted);

        assertThat(PublicGroupKit.ids(kit.mine())).containsExactly(visible);
    }

    // --- 쪽 나누기 ---

    @Test
    void 쪽으로_자르고_다음_쪽이_있는지만_준다() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(kit.group("-p" + i)); // 모두 평균 0 — ID 순
        }

        PublicGroupPageResponse first = kit.list("query", kit.token, "size", "2", "page", "0");
        PublicGroupPageResponse second = kit.list("query", kit.token, "size", "2", "page", "1");
        PublicGroupPageResponse third = kit.list("query", kit.token, "size", "2", "page", "2");
        PublicGroupPageResponse beyond = kit.list("query", kit.token, "size", "2", "page", "3");

        assertThat(PublicGroupKit.ids(first.groups())).containsExactly(created.get(0), created.get(1));
        assertThat(first.hasNext()).isTrue();
        assertThat(PublicGroupKit.ids(second.groups())).containsExactly(created.get(2), created.get(3));
        assertThat(second.hasNext()).isTrue();
        assertThat(PublicGroupKit.ids(third.groups())).containsExactly(created.get(4));
        assertThat(third.hasNext()).isFalse();
        assertThat(beyond.groups()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void 정확히_쪽_크기만큼이면_다음_쪽이_없다() {
        kit.group("-q0");
        kit.group("-q1");

        PublicGroupPageResponse page = kit.list("query", kit.token, "size", "2");

        assertThat(page.groups()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void 쪽_크기를_생략하면_20이다() {
        for (int i = 0; i < 21; i++) {
            kit.group("-d" + i);
        }

        PublicGroupPageResponse page = kit.list("query", kit.token);

        assertThat(page.groups()).hasSize(20);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void 결과가_없으면_빈_배열이다() {
        PublicGroupPageResponse page = kit.list("query", kit.token + "-없음");

        assertThat(page.groups()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    // --- 입력 한도 ---

    @Test
    void 검색어는_100자까지_받고_101자부터는_400이다() {
        assertThat(kit.request("query", "가".repeat(100))).hasStatusOk();
        assertErrorCode(kit.request("query", "가".repeat(101)), HttpStatus.BAD_REQUEST, "REQUEST_FAILED");
    }

    @Test
    void 쪽_번호는_10000까지_받고_10001부터는_400이다() {
        PublicGroupPageResponse far = kit.list("page", "10000", "size", "50");
        assertThat(far.groups()).isEmpty();
        assertThat(far.hasNext()).isFalse();
        assertErrorCode(kit.request("page", "10001"), HttpStatus.BAD_REQUEST, "REQUEST_FAILED");
    }

    @Test
    void 쪽_번호가_음수이거나_쪽_크기가_범위_밖이면_400이다() {
        assertThat(kit.request("page", "-1")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(kit.request("size", "0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(kit.request("size", "51")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
