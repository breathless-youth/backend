package project.study.mascot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record MascotSelectRequest(
        @Schema(description = "선택할 마스코트 코드", example = "STOPWATCH") @NotBlank
        String code) {}
