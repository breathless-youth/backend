package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 다른 사람에게 보이는 모습. 선택한 마스코트와 그 마스코트가 낀 소품이다. */
public record MascotAppearance(
        @Schema(description = "선택한 마스코트 코드. 고른 적이 없으면 첫 마스코트", example = "STOPWATCH")
        String mascotCode,

        @Schema(description = "그 마스코트가 낀 소품. 없으면 []") List<EquippedAccessory> accessories) {}
