package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/** 공부 결과 화면 "랭킹이 올랐어요" (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST. */
class RankingSessionGainsApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder gains(String query, long userId) {
        return mvc.get()
                .uri("/api/rankings/session-gains" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 오른_판만_칩_순서로_주고_처음_순위가_생긴_판과_안_오른_판은_뺀다() {
        session(user("a"), kst(10, 6, 9, 0), 120, 5000); // 화 오전 — 이번 세션 뒤 나와 같은 값이지만 먼저 도달
        session(user("b"), kst(10, 7, 9, 0), 90, 3500); // 수 오전
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 2000); // 월 오전 — 이번 세션 전엔 3위
        session(me, kst(10, 10, 9, 0), 60, 3000); // 토 오전 — 이번 제출, 이후 5000으로 2위

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.weekly.before", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.weekly.after", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.weekly.delta", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.others.length()", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.others[0].type", v -> assertThat(v).isEqualTo("FOCUS_TIME"))
                .hasPathSatisfying("$.others[0].period", v -> assertThat(v).isEqualTo("MONTHLY"))
                .hasPathSatisfying("$.others[1].type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.others[1].period", v -> assertThat(v).isEqualTo("WEEKLY"))
                .hasPathSatisfying("$.others[1].slot", v -> assertThat(v).isEqualTo("MORNING"))
                .hasPathSatisfying("$.others[1].before", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.others[1].after", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.others[2].type", v -> assertThat(v).isEqualTo("TOTAL_TIME"))
                .hasPathSatisfying("$.others[2].period", v -> assertThat(v).isNull())
                // 누적 일수도 순차 순위라 같은 1일 중 순공이 가장 적던 3위에서 2일로 1위가 된다
                .hasPathSatisfying("$.others[3].type", v -> assertThat(v).isEqualTo("TOTAL_DAYS"))
                .hasPathSatisfying("$.others[3].before", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.others[3].after", v -> assertThat(v).isEqualTo(1));
    }

    @Test
    void 이번_세션으로_처음_순위가_생긴_판뿐이면_weekly는_null이고_others는_비어_있다() {
        session(user("a"), kst(10, 6, 9, 0), 60, 3000);
        long me = user("me");
        session(me, kst(10, 10, 9, 0), 60, 2000);

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.weekly", v -> assertThat(v).isNull())
                .extractingPath("$.others")
                .asArray()
                .isEmpty();
    }

    @Test
    void 누적_일수는_순차_순위로_비교하고_같은_일수면_순공이_많은_쪽이_앞이다() {
        for (String name : List.of("a", "b", "c")) {
            long id = user(name);
            for (int day = 1; day <= 3; day++) {
                session(id, kst(10, day, 9, 0), 15, 900);
            }
        }
        long d = user("d");
        session(d, kst(10, 1, 9, 0), 15, 900);
        session(d, kst(10, 2, 9, 0), 15, 900);
        long me = user("me");
        session(me, kst(10, 4, 9, 0), 15, 900); // 이번 세션 전 2일·1800초 — d(2일·1800초)보다 늦게 달성해 5위
        session(me, kst(10, 5, 9, 0), 15, 900);
        session(me, kst(10, 10, 9, 0), 15, 900); // 이번 제출 — 3일·2700초로 a·b·c와 같지만 가장 늦게 달성해 4위

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.others[?(@.type == 'TOTAL_DAYS')].before",
                        v -> assertThat(v).asArray().containsExactly(5))
                .hasPathSatisfying(
                        "$.others[?(@.type == 'TOTAL_DAYS')].after",
                        v -> assertThat(v).asArray().containsExactly(4));
    }

    @Test
    void 연속_일수가_같아도_누적_일수가_늘어_앞서면_오른_것이다() {
        session(user("z"), kst(10, 1, 9, 0), 15, 900); // 연속 1일·누적 1일, 먼저 도달
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 15, 900); // 이번 세션 전 연속 1일·누적 1일 — z보다 늦어 2위
        session(me, kst(10, 8, 9, 0), 15, 900); // 이번 제출 — 연속은 그대로 1일, 누적 2일로 1위

        assertThat(gains("?startedAt=2026-10-08T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.others[?(@.type == 'MAX_STREAK')].before",
                        v -> assertThat(v).asArray().containsExactly(2))
                .hasPathSatisfying(
                        "$.others[?(@.type == 'MAX_STREAK')].after",
                        v -> assertThat(v).asArray().containsExactly(1));
    }

    @Test
    void 없는_제출이나_남의_제출이면_404다() {
        long me = user("me");
        long other = user("other");
        session(other, kst(10, 10, 9, 0), 60, 2000);

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(gains("?startedAt=2026-10-10T00:00:01Z", other)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void startedAt이_없거나_형식이_틀리면_400이고_토큰이_없으면_401이다() {
        long me = user("me");

        assertThat(gains("", me)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(gains("?startedAt=nope", me)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get()
                        .uri("/api/rankings/session-gains?startedAt=2026-10-10T00:00:00Z")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
