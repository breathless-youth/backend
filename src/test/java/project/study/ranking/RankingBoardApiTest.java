package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

class RankingBoardApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    private MockMvcRequestBuilder request(String query, long userId) {
        return request(query, userId, "2");
    }

    // 일수 판이 개인 단위가 되며 2가 생겼다. 1은 묶음·공동 순위 계약이다
    private MockMvcRequestBuilder request(String query, long userId, String version) {
        return mvc.get()
                .uri("/api/rankings/board?" + query)
                .header(ApiVersionConfig.HEADER, version)
                .with(asUser(userId));
    }

    private void studyDays(long userId, int... days) {
        for (int day : days) {
            session(userId, kst(10, day, 9, 0), 15, 900);
        }
    }

    @Test
    void 버전_2의_일수_판은_개인_줄로_준다() {
        studyDays(user("a"), 1, 2, 3);
        studyDays(user("b"), 4, 5, 6);
        long me = user("me");
        studyDays(me, 7);

        assertThat(request("type=TOTAL_DAYS", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.podium[*].rank", v -> assertThat(v).asArray().containsExactly(1, 2, 3))
                .hasPathSatisfying("$.around.length()", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.me.rank", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.podiumGroups", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.aroundGroups", v -> assertThat(v).isNull());
    }

    @Test
    void 버전_1의_일수_판은_같은_일수_묶음과_공동_순위로_준다() {
        studyDays(user("a"), 1, 2, 3);
        studyDays(user("b"), 4, 5, 6);
        long me = user("me");
        studyDays(me, 7);

        assertThat(request("type=TOTAL_DAYS", me, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.podiumGroups[*].rank", v -> assertThat(v).asArray().containsExactly(1, 3))
                .hasPathSatisfying("$.me.rank", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.podium", v -> assertThat(v).asArray().isEmpty())
                .hasPathSatisfying("$.around", v -> assertThat(v).asArray().isEmpty());
    }

    @Test
    void 버전_헤더가_없으면_버전_1의_묶음으로_준다() {
        studyDays(user("a"), 1, 2, 3);
        long me = user("me");
        studyDays(me, 7);
        // asUser는 헤더가 없으면 2를 싣는다 — 헤더 없는 요청은 인증만 직접 건다
        var auth = authentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(mvc.get().uri("/api/rankings/board?type=TOTAL_DAYS").with(auth))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.podiumGroups[*].rank", v -> assertThat(v).asArray().containsExactly(1, 2))
                .hasPathSatisfying("$.podium", v -> assertThat(v).asArray().isEmpty());
    }

    @Test
    void 지원하지_않는_버전은_400이다() {
        assertThat(request("type=TOTAL_DAYS", user("me"), "3")).hasStatus(HttpStatus.BAD_REQUEST);
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
        assertThat(mvc.get().uri("/api/rankings/board?type=TOTAL_TIME").header(ApiVersionConfig.HEADER, "2"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
