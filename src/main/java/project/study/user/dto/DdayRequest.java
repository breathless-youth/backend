package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record DdayRequest(
        @Schema(description = "제목 — 앞뒤 공백은 잘라 저장한다", example = "2027 수능")
        @NotBlank
        @Size(max = 10, message = "제목은 10자 이하여야 합니다")
        String title,

        @Schema(description = "목표 날짜(YYYY-MM-DD) — 오늘(Asia/Seoul) 이후만 받는다", example = "2027-11-18") @NotNull
        LocalDate targetDate) {}
