package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record DecorationResponse(
        @Schema(description = "선택한 마스코트 코드", example = "STOPWATCH")
        String selectedMascotCode,

        @Schema(description = "마스코트별로 낀 소품. 선택하지 않은 마스코트의 소품도 담긴다")
        List<EquippedAccessory> accessories,

        @Schema(description = "적용한 타이머 배경. 기본이면 null") EquippedItem timerBackground) {}
