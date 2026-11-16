package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record MedalReward(
        @Schema(description = "메달 기록 ID. 받기 요청의 경로에 넣는다", example = "301")
        long recordId,

        @Schema(description = "랭킹판 키. 종목:기간(:시간대)", example = "FOCUS_TIME:DAILY")
        String boardKey,

        @Schema(description = "기간. DAILY·WEEKLY·MONTHLY", example = "DAILY")
        String period,

        @Schema(description = "기간 시작 날짜(KST)", example = "2026-11-09")
        LocalDate periodStart,

        @Schema(description = "순위 1~3", example = "1") int rank,
        @Schema(description = "받을 포인트", example = "100") int points) {}
