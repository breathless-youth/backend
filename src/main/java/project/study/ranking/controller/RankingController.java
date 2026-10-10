package project.study.ranking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingHomeResponse;
import project.study.ranking.dto.RankingSessionGainsResponse;
import project.study.ranking.service.RankingBoardService;
import project.study.ranking.service.RankingHomeService;
import project.study.ranking.service.RankingSessionGainsService;
import project.study.studysession.entity.TimeSlot;

@Tag(name = "Ranking", description = "랭킹 API — 랭킹판(순공·집중률·시간대)과 명예의 전당을 조회한다 (BY-828)")
@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

    private final RankingBoardService rankingBoardService;
    private final RankingHomeService rankingHomeService;
    private final RankingSessionGainsService rankingSessionGainsService;

    @Operation(summary = "랭킹판 조회", description = """
                    랭킹판 하나의 시상대(1~3위), 내 순위, 내 주변(앞 2 · 나 · 뒤 2), 바로 위·아래와의 차이를 준다. \
                    참가 인원 수 필드는 없다(`startNowRank`·`topPercent`로 가늠할 수 있다). 다른 사용자의 userId는 내려주지 않는다 — 행 키는 고유한 nickname이다.

                    - 종목·기간: 순공(FOCUS_TIME) 일·주·월, 집중률(FOCUS_RATE) 주·월(참가 조건 주 10시간·월 30시간), \
                    시간대(TIME_SLOT) 일·주(slot 생략 시 지금 구간), 명예의 전당(TOTAL_TIME·TOTAL_DAYS·MAX_STREAK, period 없음)
                    - 기간은 KST — 일간 00시, 주간 월요일 00시, 월간 1일 00시 마감. 심야(NIGHT)판만 04시 마감이고 시작한 날 기준이다
                    - 값·차이 단위: 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리)
                    - 순위: 값이 같으면 먼저 달성한 사람이 앞(공동 순위 없음). 상위 % = max(1, ceil(순위 × 100 ÷ 참가자 수))
                    - 실시간: 순공·집중률·시간대는 진행 중 세션을 포함한다(집중률 판은 focusing이 항상 false). \
                    focusing=true인 값은 asOf부터 1초씩 올려 보간하고, 마감된 지난 기간엔 focusing이 없다. 15초 폴링 권장
                    - 판별 추가 필드: 집중률 eligibility·above.catchUpFocusSec, 누적 nextTier, 연속 일수 streak·aroundGroups \
                    (around 대신), 순공 주간에 기록이 없으면 goalExamples. 해당하지 않으면 null
                    - offset=-1은 직전 기간의 최종 결과(기간 판만). 없는 조합은 400""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/board", version = "1")
    public RankingBoardResponse board(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "종목", example = "FOCUS_TIME") @RequestParam RankingBoardType type,
            @Parameter(description = "기간 — 기간 판만", example = "WEEKLY") @RequestParam(required = false)
                    RankingPeriod period,
            @Parameter(description = "시간대 구간 — 시간대 판만, 생략하면 지금 구간") @RequestParam(required = false) TimeSlot slot,
            @Parameter(description = "0=지금 기간, -1=직전 기간(기간 판만)", example = "0") @RequestParam(defaultValue = "0")
                    int offset) {
        return rankingBoardService.board(userId, type, period, slot, offset);
    }

    @Operation(summary = "홈 랭킹 (한 줄 카드·첫 접속 추월)", description = """
                    이번 주 순공 판의 내 순위·값·바로 위와의 차이(card)와, since 뒤로 나를 추월한 사람(overtaken)을 준다.

                    - card: 이번 주 기록이 없으면 null
                    - overtaken: since를 줬을 때만. since가 이번 주 월요일 00:00(KST) 이전이거나 미래이거나, since에 내 순위가 없었거나, \
                    순위가 내려가지 않았으면 null
                    - since 시점 값은 그때까지 끝난 조각과, 걸쳐 있던 조각(진행 중 세션 포함)의 시간 비율로 되돌린다
                    - nearest: 나를 추월한 사람 중 지금 가장 가까운 2명 — since 뒤에 처음 공부한 시각과 그 뒤로 쌓은 순공
                    - FE 몫: 마지막 포그라운드 시각 보관, 그날 첫 접속 판단, 하루 1회, 마감 모달이 뜬 날 생략""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/home", version = "1")
    public RankingHomeResponse home(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "마지막으로 앱을 본 시각(UTC ISO-8601)", example = "2026-10-07T12:00:00Z")
                    @RequestParam(required = false)
                    Instant since) {
        return rankingHomeService.home(userId, since);
    }

    @Operation(summary = "세션 뒤 오른 랭킹", description = """
                    방금 제출한 세션으로 순위가 오른 판을 준다(공부 결과 화면 "랭킹이 올랐어요").

                    - before: 이번 제출의 조각을 뺀 내 값, after: 지금 내 값 — 둘 다 지금의 같은 다른 사람들 사이에서 매긴 순위
                    - 대상: 지금 기간의 순공 일·주·월, 집중률 주·월, 이 세션이 지난 시간대 일·주, 누적 시간·누적 일수·연속 일수
                    - 이번 세션 전에 순위가 없던 판(처음 순위가 생긴 판)과 오르지 않은 판은 빠진다
                    - startedAt은 제출한 세션의 시작 시각(자정 분할 조각 전부를 묶는 제출 시작). 없거나 내 세션이 아니면 404""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "그 시각에 시작한 내 세션이 없음")
    @GetMapping(value = "/session-gains", version = "1")
    public RankingSessionGainsResponse sessionGains(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "제출한 세션의 시작 시각(UTC ISO-8601)", example = "2026-10-10T00:00:00Z") @RequestParam
                    Instant startedAt) {
        return rankingSessionGainsService.gains(userId, startedAt);
    }
}
