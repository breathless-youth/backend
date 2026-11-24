package project.study.premiumwaitlist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.premiumwaitlist.dto.PremiumWaitlistRequest;
import project.study.premiumwaitlist.service.PremiumWaitlistService;

@Tag(name = "Premium Waitlist", description = "프리미엄 사전 신청 페이크도어 실험 (BY-1392)")
@RestController
@RequestMapping(value = "/api/premium-waitlist", version = "2")
@RequiredArgsConstructor
public class PremiumWaitlistController {

    private final PremiumWaitlistService service;

    @Operation(
            summary = "프리미엄 사전 신청 저장",
            description = "사용자당 한 행으로 덮어쓴다. 신청 완료와 기대 기능 선택이 같은 본문으로 두 번 온다."
                    + " expectedFeature가 null이면 이미 저장된 값을 유지한다. 14세 미만이면 email을 저장하지 않는다.")
    @ApiResponse(responseCode = "204", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "허용되지 않은 price·placement·expectedFeature, 이메일 누락·형식 오류")
    @ApiResponse(responseCode = "401", description = "토큰 없음")
    @PutMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@AuthenticationPrincipal Long userId, @Valid @RequestBody PremiumWaitlistRequest request) {
        service.apply(userId, request);
    }
}
