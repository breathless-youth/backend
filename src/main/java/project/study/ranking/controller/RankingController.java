package project.study.ranking.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.service.RankingBoardService;
import project.study.studysession.entity.TimeSlot;

@Tag(name = "Ranking", description = "랭킹 API — 랭킹판(순공·집중률·시간대)과 명예의 전당을 조회한다 (BY-828)")
@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

    private final RankingBoardService rankingBoardService;

    @Operation(summary = "랭킹판 조회", description = """
                    랭킹판 하나의 시상대(1~3위), 내 순위, 내 주변(앞 2 · 나 · 뒤 2), 바로 위·아래와의 차이를 준다. \
                    참가 인원 수 필드는 없다(`startNowRank`·`topPercent`·`eligibility.expectedTopPercent`로 가늠할 수 있다). 다른 사용자의 userId는 내려주지 않는다 — 행 키는 고유한 nickname이다.

                    - 종목·기간: 순공(FOCUS_TIME) 일·주·월, 집중률(FOCUS_RATE) 주·월(참가 조건 주 10시간·월 30시간), \
                    시간대(TIME_SLOT) 일·주(slot 생략 시 지금 구간), 명예의 전당(TOTAL_TIME·TOTAL_DAYS·MAX_STREAK, period 없음)
                    - 기간은 KST — 일간 00시, 주간 월요일 00시, 월간 1일 00시 마감
                    - 값·차이 단위: 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리)
                    - 순위: 자리대로(1, 2, 3). 값이 같으면 먼저 달성한 사람이 앞. 일수 판은 같은 일수면 누적 일수 판은 누적 순공, \
                    연속 일수 판은 누적 일수가 많은 사람이 앞이고, 그것도 같으면 먼저 달성한 사람이 앞이다. 일수 판의 above·below는 \
                    일수가 다른 가장 가까운 사람이고 상위 %는 같은 일수의 첫 순위로 센다(같은 일수끼리 %가 같다). \
                    상위 % = max(1, ceil(순위 × 100 ÷ 참가자 수))
                    - 실시간: 순공·집중률·시간대는 진행 중 세션을 포함한다(집중률 판은 focusing이 항상 false). \
                    focusing=true인 값은 asOf부터 1초씩 올려 보간하고, 마감된 지난 기간엔 focusing이 없다. 15초 폴링 권장
                    - 판별 추가 필드: 집중률 eligibility·above.catchUpFocusSec, 누적 nextTier, 연속 일수 streak, \
                    순공 주간에 기록이 없으면 goalExamples. 해당하지 않으면 null. podiumGroups·aroundGroups는 버전 1 전용이라 늘 null
                    - offset=-1은 직전 기간의 최종 결과(기간 판만). 없는 조합은 400
                    - 버전 1(헤더 없음·1)은 일수 판을 같은 일수 묶음(podiumGroups·aroundGroups)과 공동 순위(1, 1, 3)로 준다. 문서에는 \
                    싣지 않는다""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/board", version = "2")
    public RankingBoardResponse board(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "종목", example = "FOCUS_TIME") @RequestParam RankingBoardType type,
            @Parameter(description = "기간 — 기간 판만", example = "WEEKLY") @RequestParam(required = false)
                    RankingPeriod period,
            @Parameter(
                            description =
                                    "시간대 구간 — 시간대 판만, 생략하면 지금 구간. KST NIGHT 0–6·MORNING 6–12·AFTERNOON 12–18·EVENING 18–24시. "
                                            + "DAWN은 지난 마감 기록에만 있어 400")
                    @RequestParam(required = false)
                    TimeSlot slot,
            @Parameter(description = "0=지금 기간, -1=직전 기간(기간 판만)", example = "0") @RequestParam(defaultValue = "0")
                    int offset) {
        return rankingBoardService.board(userId, type, period, slot, offset);
    }

    /** 버전 1 — 일수 판을 같은 일수 묶음·공동 순위로 준다. 일수 판 개인 단위 전환 전 앱용이라 문서에 싣지 않는다. */
    @Hidden
    @GetMapping(value = "/board", version = "1")
    public RankingBoardResponse boardV1(
            @AuthenticationPrincipal Long userId,
            @RequestParam RankingBoardType type,
            @RequestParam(required = false) RankingPeriod period,
            @RequestParam(required = false) TimeSlot slot,
            @RequestParam(defaultValue = "0") int offset) {
        return rankingBoardService.board(userId, type, period, slot, offset, true);
    }
}
