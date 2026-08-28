package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.service.StudySessionService;

@Tag(name = "StudySessionStats", description = "공부 세션 통계 조회 API 모음 — 하루 목록·합계, 연속 공부일(스트릭), 기간(주간/월간) 집계를 조회한다")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StudySessionStatsController {

    private final StudySessionService studySessionService;

    @Operation(summary = "공부 세션 목록 조회 (하루)", description = """
                    한 유저의 세션 을 통계 날짜(`statDate`) 기준으로 하루 단위로 조회한다 (일별 기록 화면). \
                    기간(from~to) 조회는 추후 별도 API로 제공한다.

                    세션은 길이와 무관하게 저장되지만, 순공시간(`focusSec`)이 1분 미만인 세션은 이 조회에는 \
                    보이지 않는다 (`studiedDatesInMonth`도 동일 기준 적용).

                    응답은 세션 목록과 그날 전체 통계를 함께 담은 객체다.
                    - `sessions` — 세션 요약 목록, 시작 시각 내림차순. 원본 이벤트 목록(시각)은 미포함이고, \
                    각 세션 항목 안에 그 세션의 상태별 이벤트 건수(`sessions[].eventCounts`)만 담긴다
                    - `sessionCount` — 조회된 세션 개수 (자정 분할 세션은 각각 1개로 센다)
                    - `totalStudySec` / `totalFocusSec` — 그날 총 공부 시간·순공 시간 합계(초) — sessions[].studySec/focusSec의 합
                    - `longestFocusSec` — 그날 최장집중시간(초). 세션 하나 안에서 이벤트(PHONE/DEVICE/AWAY/PAUSE)로 끊기지 \
                    않고 이어진 가장 긴 구간을 세션마다 구해, 그중 최댓값을 취한다
                    - `focusRate` — 그날 전체 집중률(%). 세션별 집중률의 평균이 아니라 합계(totalFocusSec ÷ totalStudySec) 기준으로 계산한다
                    - `totalEventCounts` — 그날 전체 상태별 이벤트 발생 건수 합계 — sessions[].eventCounts를 모두 더한 값. 없는 상태도 0으로 내려간다
                    - `studiedDatesInMonth` — `date`가 속한 달 동안 공부 기록이 있는 날짜 목록. \
                    캘린더에 공부일을 표시하는 용도(중복 없음, 오름차순)

                    존재하지 않는 userId거나 기록 없는 날짜면 sessions는 빈 배열, sessionCount는 0, \
                    합계는 0, longestFocusSec은 0, focusRate는 0.0, totalEventCounts는 모든 상태 0인 객체가 내려온다. \
                    studiedDatesInMonth는 해당 달의 기록 여부와 무관하게 항상 계산된다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 세션 목록 + 세션 개수 + 그날 합계·집중률·상태별 이벤트 건수")
    @GetMapping
    public StudySessionListResponse list(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @RequestParam Long userId,
            @Parameter(description = "조회할 날짜 (ISO-8601, 예: 2026-07-24) — statDate 기준", example = "2026-07-24")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return studySessionService.list(userId, date);
    }

    @Operation(summary = "연속 공부일(스트릭) 조회", description = """
                    통계 날짜(`statDate`) 기준으로 그 날 세션 중 하나라도 순공시간(`focusSec`)이 10분 이상이면 \
                    그날은 공부한 날로 친다 (하루 합계가 아니라 세션 단위 기준). 유저에 저장된 값이 아니라 세션 \
                    이력에서 매번 계산한다.

                    - `streak` — 오늘(오늘 기록이 아직 없으면 어제)부터 거꾸로 이어진 연속 공부일. \
                    오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다 — 오늘이 지나기 전엔 끊긴 게 아니다. \
                    어제도 오늘도 기록이 없으면 0
                    - `maxStreak` — 전체 이력에서 가장 길었던 연속 공부일
                    - `studiedDatesInRange` — `from`~`to` 기간 중 위 스트릭 인정 기준을 만족한 날짜 목록. \
                    `from`/`to`는 선택 파라미터로 둘 다 주거나 둘 다 생략해야 하며(하나만 주면 400, `from`이 \
                    `to`보다 이후면 400), 생략하면 빈 배열이 내려온다

                    기록이 없거나 존재하지 않는 userId면 streak/maxStreak 둘 다 0이다 (목록 조회와 같은 계약).""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 현재 스트릭, 역대 최장 스트릭, (선택) 기간 내 공부일 목록")
    @GetMapping("/streak")
    public StudySessionStreakResponse streak(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @RequestParam Long userId,
            @Parameter(description = "기간 조회 시작일 (ISO-8601) — to와 함께 지정해야 한다", example = "2026-07-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "기간 조회 종료일 (ISO-8601) — from과 함께 지정해야 한다", example = "2026-07-28")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return studySessionService.streak(userId, from, to);
    }

    @Operation(summary = "기간 집계 조회 (주간/월간)", description = """
                    from~to 구간의 일별 순공/총공부 집계를 dailyList로 반환한다 (주간 막대·월간 달력용). \
                    dailyList는 from~to 모든 날짜를 담으며 공부 없는 날은 0이다 (순공 1분 미만 세션은 집계 제외). \
                    compareFrom/compareTo를 함께 주면 그 구간도 같은 방식의 일별 배열 compareDailyList로 함께 준다(증감 비교용) — \
                    미지정 시 compareFrom/compareTo는 null, compareDailyList는 빈 배열. 총합·비교는 클라가 배열을 합산해 계산한다. \
                    from>to, compare 한쪽만 지정, 366일 초과 범위(메인·비교 공통)는 400.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — from~to 일별 배열 + (선택) compare 구간 일별 배열")
    @GetMapping("/period")
    public StudyPeriodStatsResponse period(
            @Parameter(description = "조회할 유저 ID", example = "1") @RequestParam Long userId,
            @Parameter(description = "구간 시작일(ISO-8601, 포함)", example = "2026-08-24")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "구간 종료일(ISO-8601, 포함)", example = "2026-08-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "비교 구간 시작일 — compareTo와 함께 지정", example = "2026-08-17")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate compareFrom,
            @Parameter(description = "비교 구간 종료일 — compareFrom과 함께 지정", example = "2026-08-23")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate compareTo) {
        return studySessionService.periodStats(userId, from, to, compareFrom, compareTo);
    }
}
