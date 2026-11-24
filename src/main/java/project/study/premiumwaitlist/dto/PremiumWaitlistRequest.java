package project.study.premiumwaitlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.premiumwaitlist.entity.ExpectedFeature;
import project.study.premiumwaitlist.entity.PremiumPlacement;

/** 프리미엄 사전 신청 본문 (BY-1394). 신청 완료와 기대 기능 선택이 같은 본문 모양으로 두 번 온다. */
public record PremiumWaitlistRequest(
        @Schema(description = "보여준 가격(원). 2900 또는 5900만 허용", example = "2900") @NotNull
        Integer price,

        @Schema(description = "카드가 노출된 위치") @NotNull PremiumPlacement placement,
        @Schema(description = "만 14세 이상 여부") @NotNull Boolean ageOver14,

        @Schema(description = "ageOver14=true면 필수. false면 보내도 저장하지 않는다", example = "user@example.com", nullable = true)
        @Email
        @Size(max = 254)
        String email,

        @Schema(description = "기대 기능. null이면 이미 저장된 값을 유지한다", nullable = true)
        ExpectedFeature expectedFeature) {

    @Schema(hidden = true)
    @AssertTrue(message = "price는 2900 또는 5900이어야 합니다")
    public boolean isPriceAllowed() {
        return price == null || price == 2900 || price == 5900;
    }

    @Schema(hidden = true)
    @AssertTrue(message = "ageOver14가 true면 email이 필요합니다")
    public boolean isEmailPresentWhenOver14() {
        return !Boolean.TRUE.equals(ageOver14) || (email != null && !email.isBlank());
    }

    /** 저장할 이메일. 14세 미만이면 보내도 버린다. */
    public String emailToStore() {
        return Boolean.TRUE.equals(ageOver14) ? email.strip() : null;
    }
}
