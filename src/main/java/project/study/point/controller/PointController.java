package project.study.point.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.LanguageTag;
import project.study.mascot.service.MascotService;
import project.study.point.dto.PointBalanceResponse;
import project.study.point.dto.PointHistoryResponse;
import project.study.point.dto.PointHistoryType;
import project.study.point.dto.StudyReward;
import project.study.point.dto.StudyRewardResponse;
import project.study.point.service.PointService;
import project.study.point.service.StudyPointService;

@Tag(name = "Point", description = "API-Version 1, 토큰 인증 필요. 잔액과 지급 여부는 전부 서버가 판단한다. 공부 포인트는 세션 제출 때 서버가 계산해 준다.")
@ApiResponse(
        responseCode = "403",
        description = "비회원",
        content =
                @Content(
                        examples =
                                @ExampleObject(
                                        value = "{\"code\":\"MEMBER_ONLY\",\"message\":\"로그인한 사용자만 쓸 수 있습니다\"}")))
@RestController
@RequestMapping(value = "/api/points", version = "1")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;
    private final StudyPointService studyPointService;
    private final MascotService mascotService;

    @Operation(summary = "잔액 조회")
    @GetMapping
    public PointBalanceResponse balance(@AuthenticationPrincipal Long userId) {
        return pointService.balance(userId);
    }

    @Operation(
            summary = "포인트 기록 조회",
            description =
                    "최신순. 응답의 nextCursor를 cursor로 넘기면 다음 쪽을 준다. 날짜 묶음과 문구는 앱이 reason·label·createdAt으로 만든다. 구매 기록의 label은 상품 이름이고 Accept-Language의 언어로 준다(번역이 없으면 한국어).")
    @GetMapping("/history")
    public PointHistoryResponse history(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "ALL(전체)·EARNED(받은 것)·SPENT(쓴 것)") @RequestParam(defaultValue = "ALL")
                    PointHistoryType type,
            @Parameter(description = "앞 쪽 응답의 nextCursor. 첫 쪽은 비운다") @RequestParam(required = false) Long cursor,
            @Parameter(description = "한 쪽 개수, 1~100") @RequestParam(defaultValue = "30") int size,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return pointService.history(userId, type, cursor, size, LanguageTag.primary(language));
    }

    @Operation(
            summary = "세션 결과 화면의 포인트 칸",
            description =
                    "POST /api/study-sessions가 끝난 뒤 같은 startedAt으로 부른다. 그 제출로 받은 포인트, 그날 인정된 순공과 상한, 다음 마스코트까지 남은 순공을 준다. "
                            + "제출 응답이 배열이라 필드를 더할 수 없어 따로 뒀다. 포인트는 30분 단위로 주므로 30분에 못 미친 순공은 같은 날 다음 세션과 합쳐져 그때 지급된다.")
    @GetMapping("/study-reward")
    public StudyRewardResponse studyReward(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "제출한 세션의 startedAt (UTC, ISO-8601)", example = "2026-11-10T05:05:00Z")
                    @RequestParam
                    Instant startedAt) {
        StudyReward reward = studyPointService.reward(userId, startedAt);
        return new StudyRewardResponse(
                reward.earnedPoints(),
                reward.statDate(),
                reward.recognizedFocusSec(),
                reward.dailyCapFocusSec(),
                mascotService.next(userId).orElse(null));
    }
}
