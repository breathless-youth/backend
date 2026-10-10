package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/** 홈 한 줄 카드와 첫 접속 추월 (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST — 이번 주는 10/5(월)부터다. */
class RankingHomeApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder home(String query, long userId) {
        return mvc.get()
                .uri("/api/rankings/home" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 카드는_이번_주_순위와_바로_위_차이고_추월은_그때_뒤였거나_없던_사람이다() {
        long c = user("c");
        session(c, kst(10, 5, 13, 0), 120, 6000); // 그때도 지금도 내 앞
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 3000);
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 2000); // 그때는 내 뒤
        session(a, kst(10, 7, 9, 0), 60, 3500); // since 뒤 — 지금 5500
        long b = user("b");
        session(b, kst(10, 8, 9, 0), 120, 4000); // 그때는 없었다

        assertThat(home("?since=2026-10-06T15:00:00Z", me)) // 수 00:00 KST
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.card.value", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$.card.above.nickname", v -> assertThat(v).isEqualTo("b"))
                .hasPathSatisfying("$.card.above.gap", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying("$.overtaken.fromRank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.toRank", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.overtaken.count", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].nickname", v -> assertThat(v).isEqualTo("b"))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].gap", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-08T00:00:00Z"))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].studiedFocusSec",
                        v -> assertThat(v).isEqualTo(4000))
                .hasPathSatisfying(
                        "$.overtaken.nearest[1].nickname", v -> assertThat(v).isEqualTo("a"))
                .hasPathSatisfying(
                        "$.overtaken.nearest[1].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-07T00:00:00Z"))
                .hasPathSatisfying(
                        "$.overtaken.nearest[1].studiedFocusSec",
                        v -> assertThat(v).isEqualTo(3500))
                .doesNotHavePath("$.overtaken.nearest[0].userId");
    }

    @Test
    void since에_걸쳐_공부_중이던_진행_중_세션은_시간_비율로_되돌리고_since부터_공부했다고_준다() {
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 80, 4000);
        long y = user("y");
        draft(y, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]"); // 14:00엔 3600 — 내 뒤, 지금 7080 — 내 앞

        assertThat(home("?since=2026-10-10T05:00:00Z", me)) // 토 14:00 KST
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.fromRank", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.overtaken.toRank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.count", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].gap", v -> assertThat(v).isEqualTo(3080))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-10T05:00:00Z"))
                .hasPathSatisfying(
                        "$.overtaken.nearest[0].studiedFocusSec",
                        v -> assertThat(v).isEqualTo(3480));
    }

    // 이번 주 월요일 00:00 KST 직전, 미래(15:00:01 KST)
    @ParameterizedTest
    @ValueSource(strings = {"?since=2026-10-04T14:59:59Z", "?since=2026-10-10T06:00:01Z", ""})
    void since가_이번_주_밖이거나_미래거나_없으면_추월은_없고_카드는_준다(String query) {
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 3000);
        session(user("a"), kst(10, 8, 9, 0), 60, 3500);

        assertThat(home(query, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void since에_내_순위가_없었거나_순위가_안_내려갔으면_추월은_없다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 60, 3000); // since(수 00:00)엔 기록 없음
        session(user("a"), kst(10, 8, 9, 0), 60, 3500);
        long solo = user("solo");
        session(solo, kst(10, 5, 9, 0), 60, 3000);

        assertThat(home("?since=2026-10-06T15:00:00Z", me))
                .bodyJson()
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
        assertThat(home("?since=2026-10-09T15:00:00Z", solo)) // 그때도 지금도 2위 — 안 내려감
                .bodyJson()
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void 지금_1위면_since가_이번_주_안이어도_추월은_없다() {
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 120, 5000);
        session(user("a"), kst(10, 6, 9, 0), 60, 3000);

        assertThat(home("?since=2026-10-06T15:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.card.above", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void 이번_주_기록이_없으면_카드도_추월도_없다() {
        assertThat(home("?since=2026-10-06T15:00:00Z", user("me")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void since_형식이_틀리면_400이고_토큰이_없으면_401이다() {
        assertThat(home("?since=nope", user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get()
                        .uri("/api/rankings/home")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
