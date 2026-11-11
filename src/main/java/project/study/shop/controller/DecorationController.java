package project.study.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.shop.dto.AccessoryEquipRequest;
import project.study.shop.dto.DecorationItemRequest;
import project.study.shop.dto.DecorationResponse;
import project.study.shop.service.DecorationService;

@Tag(name = "Decoration", description = "API-Version 1, 토큰 인증 필요. 가진 상품만 착용·적용할 수 있다. 저장 요청은 모두 바뀐 꾸미기 전체를 돌려준다.")
@ApiResponse(
        responseCode = "403",
        description = "비회원",
        content =
                @Content(
                        examples =
                                @ExampleObject(
                                        value = "{\"code\":\"MEMBER_ONLY\",\"message\":\"로그인한 사용자만 쓸 수 있습니다\"}")))
@ApiResponse(
        responseCode = "409",
        description = "가지지 않은 상품",
        content =
                @Content(
                        examples =
                                @ExampleObject(value = "{\"code\":\"ITEM_NOT_OWNED\",\"message\":\"가지지 않은 상품입니다\"}")))
@RestController
@RequestMapping(value = "/api/decorations", version = "1")
@RequiredArgsConstructor
public class DecorationController {

    private final DecorationService service;

    @Operation(summary = "내 꾸미기 조회", description = "선택한 마스코트, 마스코트별로 낀 소품, 적용한 타이머 배경.")
    @GetMapping
    public DecorationResponse get(@AuthenticationPrincipal Long userId) {
        return service.get(userId);
    }

    @Operation(
            summary = "마스코트 소품 착용·벗기",
            description = "부위마다 하나만 낀다. itemId가 null이면 그 부위를 벗는다. 다른 마스코트나 다른 부위의 소품이면 400.")
    @PutMapping("/mascots/{mascotCode}/accessories")
    public DecorationResponse equipAccessory(
            @AuthenticationPrincipal Long userId,
            @PathVariable String mascotCode,
            @Valid @RequestBody AccessoryEquipRequest request) {
        return service.equipAccessory(userId, mascotCode, request.slot(), request.itemId());
    }

    @Operation(summary = "타이머 배경 적용", description = "itemId가 null이면 기본 배경으로 되돌린다.")
    @PutMapping("/timer-background")
    public DecorationResponse setTimerBackground(
            @AuthenticationPrincipal Long userId, @RequestBody DecorationItemRequest request) {
        return service.setTimerBackground(userId, request.itemId());
    }
}
