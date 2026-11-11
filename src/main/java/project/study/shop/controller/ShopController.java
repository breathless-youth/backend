package project.study.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.LanguageTag;
import project.study.shop.dto.PurchaseResponse;
import project.study.shop.dto.ShopItemListResponse;
import project.study.shop.service.ShopService;

@Tag(name = "Shop", description = "API-Version 1, 토큰 인증 필요. 상품과 가격은 서버 값이라 앱 배포 없이 추가되거나 바뀐다.")
@ApiResponse(
        responseCode = "403",
        description = "비회원",
        content =
                @Content(
                        examples =
                                @ExampleObject(
                                        value = "{\"code\":\"MEMBER_ONLY\",\"message\":\"로그인한 사용자만 쓸 수 있습니다\"}")))
@RestController
@RequestMapping(value = "/api/shop/items", version = "1")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService service;

    @Operation(
            summary = "상품 목록",
            description = "마스코트 소품과 타이머 배경을 한 번에 준다. price가 null인 상품은 포인트로 살 수 없는 보상 상품이고 unlock에 조건과 진행이 담긴다. "
                    + "보상 상품은 조건을 채우면 따로 받는 요청 없이 owned가 true가 된다. "
                    + "name은 Accept-Language의 언어로 주고 번역이 없는 상품은 한국어로 준다. tag는 화면에서 거르는 분류 코드이고 보여 줄 이름은 앱이 정한다.")
    @GetMapping
    public ShopItemListResponse list(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return service.list(userId, LanguageTag.primary(language));
    }

    @Operation(summary = "구매", description = "가격은 서버가 정한다. 같은 요청이 두 번 와도 포인트는 한 번만 빠지고 두 번째는 409 ITEM_ALREADY_OWNED다.")
    @ApiResponse(responseCode = "200", description = "구매 성공, 구매 후 잔액")
    @ApiResponse(
            responseCode = "409",
            description =
                    "INSUFFICIENT_POINTS(잔액 부족)·ITEM_ALREADY_OWNED(이미 가짐)·MASCOT_LOCKED(잠긴 마스코트의 소품)·ITEM_NOT_PURCHASABLE(보상 상품)",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트가 모자랍니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "없거나 내린 상품",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"SHOP_ITEM_NOT_FOUND\",\"message\":\"없는 상품입니다: 999\"}")))
    @PostMapping("/{itemId}/purchase")
    public PurchaseResponse purchase(@AuthenticationPrincipal Long userId, @PathVariable long itemId) {
        return service.purchase(userId, itemId);
    }
}
