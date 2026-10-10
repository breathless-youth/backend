package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import project.study.config.ApiVersionConfig;

/** 메달 버튼·시트 요약 (BY-828). 기준 시각은 2026-10-10(토) 15:00 KST — 지금 구간은 오후다. */
class RankingRecordSummaryApiTest extends RankingRecordTestBase {

    private static final String SUMMARY = "/api/rankings/records/summary";

    @Test
    void 기록이_있으면_순위별_개수와_최근_6개만_주고_best_closest는_null이다() {
        long me = user("me");
        int[] ranks = {1, 1, 2, 2, 2, 3, 1};
        for (int i = 0; i < ranks.length; i++) {
            record(me, "FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 1 + i), kst(10, 2 + i, 0, 0), ranks[i], "3000.0");
        }
        record(user("other"), "FOCUS_TIME:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 1, "40000.0");

        assertThat(get(SUMMARY, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(7))
                .hasPathSatisfying("$.firstCount", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.secondCount", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.thirdCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.recent.length()", v -> assertThat(v).isEqualTo(6))
                .hasPathSatisfying("$.recent[0].periodStart", v -> assertThat(v).isEqualTo("2026-10-07"))
                .hasPathSatisfying("$.recent[5].periodStart", v -> assertThat(v).isEqualTo("2026-10-02"))
                .hasPathSatisfying("$.best", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest", v -> assertThat(v).isNull());
    }

    @Test
    void 기록이_없으면_역대_최고와_메달에_가장_가까운_판을_준다() {
        long me = user("me");
        session(me, kst(10, 10, 9, 0), 40, 2000); // 오늘 오전 — 오전 일간판엔 나 혼자라 남은 양 0
        session(user("a"), kst(10, 10, 12, 0), 120, 5000); // 오늘 오후 — 순공 판에서 나는 4위(3위까지 1000초)
        session(user("b"), kst(10, 10, 12, 0), 90, 4000);
        session(user("c"), kst(10, 10, 13, 0), 60, 3000);
        best(me, 2, "TIME_SLOT:WEEKLY:EVENING", LocalDate.of(2026, 9, 28));

        assertThat(get(SUMMARY, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.best.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.best.type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.best.period", v -> assertThat(v).isEqualTo("WEEKLY"))
                .hasPathSatisfying("$.best.slot", v -> assertThat(v).isEqualTo("EVENING"))
                .hasPathSatisfying("$.best.periodStart", v -> assertThat(v).isEqualTo("2026-09-28"))
                .hasPathSatisfying("$.closest.type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.closest.period", v -> assertThat(v).isEqualTo("DAILY"))
                .hasPathSatisfying("$.closest.slot", v -> assertThat(v).isEqualTo("MORNING"))
                .hasPathSatisfying("$.closest.gap", v -> assertThat(v).isEqualTo(0))
                .extractingPath("$.recent")
                .asArray()
                .isEmpty();
    }

    @Test
    void 아무것도_없으면_best는_null이고_closest는_30분_남은_순공_일간판이다() {
        assertThat(get(SUMMARY, user("me")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.best", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest.type", v -> assertThat(v).isEqualTo("FOCUS_TIME"))
                .hasPathSatisfying("$.closest.period", v -> assertThat(v).isEqualTo("DAILY"))
                .hasPathSatisfying("$.closest.slot", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest.gap", v -> assertThat(v).isEqualTo(1800));
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get().uri(SUMMARY).header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
