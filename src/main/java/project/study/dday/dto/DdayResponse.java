package project.study.dday.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record DdayResponse(
        @Schema(description = "제목", example = "2027 수능") String title,

        @Schema(description = "목표 날짜(YYYY-MM-DD) — D-N은 클라이언트가 기기 날짜로 계산한다", example = "2027-11-18")
        LocalDate targetDate) {}
