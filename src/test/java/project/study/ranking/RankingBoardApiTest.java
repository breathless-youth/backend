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

class RankingBoardApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    private MockMvcRequestBuilder request(String query, long userId) {
        // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
        return mvc.get()
                .uri("/api/rankings/board?" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 랭킹판을_조회하고_다른_사람의_userId는_싣지_않는다() {
        long me = user("me");
        session(me, kst(10, 6, 9, 0), 60, 3000);
        session(user("other"), kst(10, 7, 9, 0), 60, 3300);

        assertThat(request("type=FOCUS_TIME&period=WEEKLY", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.periodStart", v -> assertThat(v).isEqualTo("2026-10-05"))
                .hasPathSatisfying("$.me.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.above.gap", v -> assertThat(v).isEqualTo(300))
                .hasPathSatisfying("$.podium[0].nickname", v -> assertThat(v).isEqualTo("other"))
                .doesNotHavePath("$.podium[0].userId");
    }

    @Test
    void 참가자가_없으면_시상대가_비고_지금_시작하면_1위다() {
        long me = user("me");

        assertThat(request("type=FOCUS_TIME&period=DAILY", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.startNowRank", v -> assertThat(v).isEqualTo(1))
                .extractingPath("$.podium")
                .asArray()
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "type=FOCUS_RATE&period=DAILY",
                "type=TIME_SLOT&period=MONTHLY",
                "type=FOCUS_TIME",
                "type=TOTAL_TIME&period=WEEKLY",
                "type=FOCUS_TIME&period=WEEKLY&slot=MORNING",
                "type=FOCUS_TIME&period=WEEKLY&offset=-2",
                "type=TOTAL_TIME&offset=-1",
                "type=NOPE",
                "period=WEEKLY"
            })
    void 없는_조합은_400이다(String query) {
        assertThat(request(query, user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get()
                        .uri("/api/rankings/board?type=TOTAL_TIME")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
