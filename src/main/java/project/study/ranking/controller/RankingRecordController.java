package project.study.ranking.controller;

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
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.service.RankingRecordService;

@Tag(name = "Ranking")
@RestController
@RequestMapping("/api/rankings/records")
@RequiredArgsConstructor
public class RankingRecordController {

    private final RankingRecordService recordService;

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
}
