package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record PointHistoryItem(
        @Schema(description = "기록 ID. 다음 쪽을 부를 때 cursor로 쓴다", example = "120")
        long id,

        @Schema(description = "금액. 받은 것은 양수, 쓴 것은 음수", example = "-600")
        int amount,

        @Schema(
                description = "사유. STUDY(공부)·PURCHASE(상점 구매)·ATTENDANCE(출석)·DAILY_GOAL(하루 목표)·MEDAL(랭킹 메달)",
                example = "PURCHASE")
        PointReason reason,

        @Schema(description = "무료로 받은 포인트(FREE)인지 결제로 산 포인트(PAID)인지", example = "FREE")
        PointFund fund,

        @Schema(
                description =
                        "사유에 붙는 이름. PURCHASE면 상품 이름(Accept-Language의 언어), MEDAL이면 \"판 키/순위\"(예: FOCUS_TIME:DAILY/1), 나머지는 null",
                example = "왕관")
        String label,

        @Schema(description = "STUDY·ATTENDANCE·DAILY_GOAL일 때 그 날짜, 아니면 null", example = "2026-11-10")
        LocalDate statDate,

        @Schema(description = "기록 시각 (UTC)", example = "2026-11-10T05:00:00Z")
        Instant createdAt) {}
