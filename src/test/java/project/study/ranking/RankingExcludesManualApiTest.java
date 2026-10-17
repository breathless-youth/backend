package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.config.ApiVersionConfig;
import project.study.studysession.dto.ManualStudySessionRequest;
import project.study.studysession.service.ManualStudySessionService;

/** 수동 기록은 랭킹 어느 판에도 들어가지 않는다 — 랭킹은 측정한 순공만 센다 (ADR-0031). */
class RankingExcludesManualApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ManualStudySessionService manualStudySessionService;

    private void manual(long userId, Instant start, Instant end) {
        manualStudySessionService.create(userId, new ManualStudySessionRequest(start, end, null));
    }

    /** 10/6~10/10 닷새를 수동으로 채운다 — 세면 주간·월간 집중률 참가 조건(10·30시간)과 연속 5일을 넘긴다. */
    private void fillWeekManually(long userId) {
        for (int day = 6; day <= 9; day++) {
            manual(userId, kst(10, day, 0, 0), kst(10, day, 23, 0));
        }
        manual(userId, kst(10, 10, 0, 0), kst(10, 10, 14, 0));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "type=FOCUS_TIME&period=DAILY",
                "type=FOCUS_TIME&period=WEEKLY",
                "type=FOCUS_TIME&period=MONTHLY",
                "type=FOCUS_RATE&period=WEEKLY",
                "type=FOCUS_RATE&period=MONTHLY",
                "type=TIME_SLOT&period=DAILY&slot=MORNING",
                "type=TIME_SLOT&period=DAILY&slot=AFTERNOON",
                "type=TIME_SLOT&period=WEEKLY&slot=MORNING",
                "type=TIME_SLOT&period=WEEKLY&slot=AFTERNOON",
                "type=TOTAL_TIME",
                "type=TOTAL_DAYS",
                "type=MAX_STREAK"
            })
    void 수동_기록만_있는_사용자는_어느_판에도_오르지_않는다(String query) {
        long me = user("me");
        fillWeekManually(me);
        session(user("other"), kst(10, 10, 9, 0), 60, 3000);

        assertThat(mvc.get()
                        .uri("/api/rankings/board?" + query)
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(me)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.me", v -> assertThat(v).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"type=FOCUS_TIME&period=DAILY", "type=FOCUS_TIME&period=WEEKLY", "type=TOTAL_TIME"})
    void 측정과_수동이_섞이면_측정한_순공만_값이_된다(String query) {
        long me = user("me");
        session(me, kst(10, 10, 14, 0), 60, 3000);
        manual(me, kst(10, 10, 9, 0), kst(10, 10, 12, 0));

        assertThat(mvc.get()
                        .uri("/api/rankings/board?" + query)
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(me)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.me.value", v -> assertThat(v).isEqualTo(3000));
    }
}
