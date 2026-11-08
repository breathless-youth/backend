package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.studysession.dto.DaySubjectResponse;
import project.study.studysession.entity.EventStatus;

@Schema(description = "개인 기록의 자정 기준 일간 목록과 합계. 원본 stats와 랭킹은 변경하지 않는다")
public record PersonalRecordListResponse(
        List<PersonalRecordSummaryResponse> sessions,
        int sessionCount,
        long totalStudySec,
        long totalFocusSec,
        @Schema(description = "측정 개인 기록만의 최장 연속 집중 초") long longestFocusSec,
        @Schema(description = "측정 개인 기록의 순공/총공부 비율, 소수 1자리") double focusRate,
        Map<EventStatus, Long> totalEventCounts,
        List<LocalDate> studiedDatesInMonth,
        List<DaySubjectResponse> subjects) {}
