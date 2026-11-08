package project.study.planner.record;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.dto.StudyRhythmResponse;

@RestController
@RequestMapping(path = "/api/planner/records/stats", version = "1")
@Tag(name = "PersonalRecordStats", description = "원본을 보존한 개인 기록 통계")
@RequiredArgsConstructor
public class PersonalRecordStatsController {
    private final PersonalRecordStatsService service;

    @GetMapping
    @Operation(
            summary = "개인 기록 일간 목록과 통계",
            description = "자정 날짜 기준 개인 수정본을 조회한다. 원본/과목 변경은 기존 statDate, 시간 변경은 원본 시간대, 새 직접 입력은 요청 시간대를 사용한다.")
    public PersonalRecordListResponse day(
            @AuthenticationPrincipal Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String timeZone) {
        return service.day(userId, date, timeZone);
    }

    @GetMapping("/period")
    @Operation(
            summary = "개인 기록 기간 통계",
            description = "양 끝 포함 최대 366일. 비교 기간은 양 끝을 함께 보내며 없는 날은 0이다. 기존 StudyPeriodStatsResponse 구조를 사용한다.")
    public StudyPeriodStatsResponse period(
            @AuthenticationPrincipal Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
            @RequestParam(required = false) String timeZone) {
        return service.period(userId, from, to, compareFrom, compareTo, timeZone);
    }

    @GetMapping("/rhythm")
    @Operation(
            summary = "개인 측정 기록의 공부 리듬",
            description = "개인 수정본과 삭제를 반영한 측정 기록만의 7×24 누적 순공과 공부일이다. 직접 입력은 제외하며 기존 StudyRhythmResponse 구조를 사용한다.")
    public StudyRhythmResponse rhythm(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) String timeZone) {
        return service.rhythm(userId, timeZone);
    }
}
