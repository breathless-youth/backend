package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StudyRhythmResponse(
        @Schema(description = "가입 이후 순공시간 1분 이상 세션이 하루라도 있었던 날의 수(KST, 오늘까지) — study-days와 같은 값", example = "22")
        long studyDays,

        @Schema(
                description = "요일(월=0 … 일=6) × 시각(KST 0~23시)별 누적 순공 초. 세션 순공을 시작~종료 사이의 시각 경계로 "
                        + "잘라 배분한 값이라 전체 합은 1분 이상 측정 세션의 순공 합과 같다. 기록이 없으면 모두 0")
        int[][] focusSecByWeekdayHour) {}
