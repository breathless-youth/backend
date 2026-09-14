package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StudyDaysResponse(
        @Schema(
                description = "가입 이후 순공시간 1분 이상 세션이 하루라도 있었던 날의 수(KST 기준, 오늘까지). "
                        + "일별 목록 조회와 같은 1분 기준이며, 자정을 걸친 세션은 조각마다 1분 이상일 때 각 날짜가 인정된다",
                example = "12")
        long totalDays) {}
