package project.study.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record EarnOverviewResponse(
        @Schema(description = "오늘 날짜(사용자 시간대 자정 기준)", example = "2026-11-12")
        LocalDate date,

        @Schema(description = "오늘 받은 무료 포인트 합계(공부·출석·하루 목표). 메달은 들지 않는다", example = "80")
        int todayEarned,

        @Schema(description = "하루에 받을 수 있는 최대(공부 상한 + 출석 + 오늘 목표의 보상). 메달은 들지 않는다", example = "254")
        int dailyMax,

        Study study,
        Attendance attendance,
        DailyGoal dailyGoal,

        @Schema(description = "포인트를 아직 안 받은 메달. 최근 마감 순, 없으면 []")
        List<MedalReward> medals,

        @Schema(description = "메달 포인트 표. 화면의 설명 줄에 쓴다") MedalPointTable medalPoints) {

    public record Study(
            @Schema(description = "오늘 인정된 순공(초)", example = "7800")
            int recognizedFocusSec,

            @Schema(description = "하루 인정 상한(초)", example = "43200")
            int dailyCapFocusSec,

            @Schema(description = "포인트를 주는 순공 단위(초). 이만큼마다 pointsPerUnit을 준다", example = "1800")
            int unitSec,

            @Schema(description = "단위마다 주는 포인트", example = "10")
            int pointsPerUnit,

            @Schema(description = "오늘 공부로 받은 포인트", example = "40")
            int earnedPoints,

            @Schema(description = "하루에 공부로 받을 수 있는 최대 포인트", example = "240")
            int maxPoints) {}

    public record Attendance(
            @Schema(description = "출석 포인트", example = "10") int points,
            @Schema(description = "오늘 받았는지") boolean claimed) {}

    public record DailyGoal(
            @Schema(description = "오늘 목표를 채우면 받는 포인트. 목표 시간에 비례하고(30분마다 1P) 목표가 없으면 0", example = "4")
            int points,

            @Schema(description = "오늘의 목표(초). 플래너·홈의 목표와 같은 값이고 정한 적이 없으면 null", example = "7200")
            Integer goalSec,

            @Schema(description = "오늘 측정한 순공(초). 직접 입력은 세지 않는다", example = "5400")
            long focusSec,

            @Schema(description = "목표까지 남은 순공(초). 목표가 없으면 null", example = "1800")
            Long remainingSec,

            @Schema(description = "받을 조건을 채웠는지(받을 포인트가 있고 순공이 목표 이상)")
            boolean achieved,

            @Schema(description = "오늘 받았는지") boolean claimed) {}
}
