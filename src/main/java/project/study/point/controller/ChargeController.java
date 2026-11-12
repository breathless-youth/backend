package project.study.point.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.point.dto.PointProductsResponse;
import project.study.point.service.ChargeService;

@Tag(name = "Point charge", description = "API-Version 1. 포인트 충전(인앱결제). 결제는 앱의 RevenueCat SDK가 하고 지급은 서버가 한다.")
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class ChargeController {

    private final ChargeService service;

    @Operation(summary = "충전 상품 목록", description = """
                    토큰 인증 필요. 스토어 상품 ID와 받는 포인트만 준다. 가격과 통화는 앱이 RevenueCat SDK(스토어)에서 읽는다. \
                    결제 전에 RevenueCat에 사용자 ID(토큰의 sub)로 로그인해야 한다. 결제가 끝나면 서버가 RevenueCat의 알림을 받아 \
                    포인트를 주므로, 앱은 `GET /api/points`의 잔액을 다시 읽는다(알림은 보통 몇 초, 길면 1분쯤 걸린다). 비회원은 403 `MEMBER_ONLY`.""")
    @GetMapping(value = "/products", version = "1")
    public PointProductsResponse products(@AuthenticationPrincipal Long userId) {
        return service.products(userId);
    }

    @Operation(summary = "RevenueCat 결제 알림", description = """
                    앱이 부르는 API가 아니다. RevenueCat이 결제(`NON_RENEWING_PURCHASE`)와 환불(`CANCELLATION`, \
                    `cancel_reason=CUSTOMER_SUPPORT`)을 알리면 포인트를 주거나 되돌린다. 같은 결제는 한 번만 지급한다. \
                    Authorization 값이 서버 설정과 다르면 401, 충전이 설정되지 않은 서버면 404.""")
    @SecurityRequirements // RevenueCat이 대시보드에 넣은 Authorization 값으로 부른다
    @PostMapping(value = "/purchases/revenuecat/webhook", version = "1")
    public ResponseEntity<Void> revenueCatWebhook(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        if (!service.authorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body.get("event") instanceof Map<?, ?> event) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) event;
            service.handle(typed);
        }
        return ResponseEntity.ok().build();
    }
}
