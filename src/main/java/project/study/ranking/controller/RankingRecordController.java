package project.study.ranking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.ranking.RankingBoardType;
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.dto.RankingRecordSeenRequest;
import project.study.ranking.dto.RankingRecordSummaryResponse;
import project.study.ranking.dto.RankingUnseenResponse;
import project.study.ranking.service.RankingRecordService;
import project.study.ranking.service.RankingRecordSummaryService;

@Tag(name = "Ranking")
@RestController
@RequestMapping("/api/rankings/records")
@RequiredArgsConstructor
public class RankingRecordController {

    private final RankingRecordService recordService;
    private final RankingRecordSummaryService summaryService;

    @Operation(summary = "마감 기록 요약 (메달 버튼·시트)", description = """
                    누적 메달 수, 순위별 개수, 최근 기록 6개를 준다.

                    - 기록이 없을 때만(total 0) best(역대 마감 최고 순위, 없으면 null)와 closest(메달까지 남은 양이 가장 작은 진행 중 시간 판)를 준다
                    - closest.gap = max(0, 3위 값 − 내 값, 1800 − 내 값) 초. 대상은 순공 일·주·월과 시간대 일·주 × 5구간이다""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/summary", version = "1")
    public RankingRecordSummaryResponse summary(@AuthenticationPrincipal Long userId) {
        return summaryService.summary(userId);
    }

    @Operation(summary = "마감 기록 모두 보기", description = """
                    내가 1·2·3위로 마감한 판을 마감 시각 최신순(같으면 id 내림차순)으로 준다. 달별 묶음은 FE가 한다.

                    - rank(1·2·3)·type(FOCUS_TIME·FOCUS_RATE·TIME_SLOT)으로 거를 수 있다. 범위 밖이면 400
                    - size 기본 20·최대 50. 다음 페이지는 nextCursor를 cursor로 넘긴다(마지막이면 null)
                    - value: 시간 판 초, 집중률 %(소수 1자리)""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(version = "1")
    public RankingRecordPageResponse list(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "순위 1·2·3") @RequestParam(required = false) Integer rank,
            @Parameter(description = "종목 — FOCUS_TIME·FOCUS_RATE·TIME_SLOT") @RequestParam(required = false)
                    RankingBoardType type,
            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 1~50", example = "20") @RequestParam(defaultValue = "20") int size) {
        return recordService.list(userId, rank, type, cursor, size);
    }

    @Operation(summary = "안 본 마감 기록 (마감 모달)", description = """
                    아직 보지 않은 메달을 순위 → 일·주·월 → 순공·집중률·시간대 순으로 준다. total은 누적 메달 수(본 것 포함)다.

                    - 04시 보류: 그날 04:00 심야 마감(월요일이면 심야 주간까지)이 확정되기 전(00:00~04:01)에는 그날 00시 마감분을 주지 \
                    않는다 — 04시 이후 처음 앱을 열 때 그날 메달을 한 번에 보여준다
                    - records는 최대 100개다 — seen으로 표시한 뒤 다시 부르면 나머지를 준다
                    - 모달을 띄운 뒤 records/seen으로 본 것으로 표시한다""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/unseen", version = "1")
    public RankingUnseenResponse unseen(@AuthenticationPrincipal Long userId) {
        return recordService.unseen(userId);
    }

    @Operation(summary = "마감 기록 본 것으로 표시", description = "내 기록만 바뀐다 — 남의 id·이미 본 id는 무시한다. ids는 최대 100개")
    @ApiResponse(responseCode = "204", description = "표시 완료")
    @PostMapping(value = "/seen", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AuthenticationPrincipal Long userId, @Valid @RequestBody RankingRecordSeenRequest request) {
        recordService.markSeen(userId, request.ids());
    }
}
