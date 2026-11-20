package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopTagResponse(
        @Schema(description = "분류 코드", example = "NATURE") String code,

        @Schema(description = "분류 이름. Accept-Language의 언어, 번역이 없으면 한국어", example = "자연")
        String name) {}
