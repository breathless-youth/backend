package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record MascotListResponse(
        @Schema(description = "내 누적 순공(초). 측정한 기록만 세고 직접 입력은 뺀다", example = "92880")
        long totalFocusSec,

        @Schema(description = "선택한 마스코트 코드. 고른 적이 없으면 첫 마스코트", example = "BOOK")
        String selectedMascotCode,

        @Schema(description = "전체 마스코트, 해금 기준 오름차순") List<MascotResponse> mascots) {}
