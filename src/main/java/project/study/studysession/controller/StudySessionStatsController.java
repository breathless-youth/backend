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
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.service.StudySessionService;

@Tag(name = "StudySessionStats", description = "공부 세션 통계 조회 API 모음 — 하루 목록·합계와 연속 공부일(스트릭)을 조회한다")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StudySessionStatsController {

    private final StudySessionService studySessionService;

    @Operation(summary = "공부 세션 목록 조회 (하루)", description = """
                    한 유저의 세션 을 통계 날짜(`statDate`) 기준으로 하루 단위로 조회한다 (일별 기록 화면). \
                    기간(from~to) 조회는 추후 별도 API로 제공한다.

                    응답은 세션 목록과 그날 전체 통계를 함께 담은 객체다.
                    - `sessions` — 세션 요약 목록, 시작 시각 내림차순. 원본 이벤트 목록(시각)은 미포함이고, \
                    각 세션 항목 안에 그 세션의 상태별 이벤트 건수(`sessions[].eventCounts`)만 담긴다
                    - `sessionCount` — 조회된 세션 개수 (자정 분할 세션은 각각 1개로 센다)
                    - `totalStudySec` / `totalFocusSec` — 그날 총 공부 시간·순공 시간 합계(초) — sessions[].studySec/focusSec의 합
                    - `focusRate` — 그날 전체 집중률(%). 세션별 집중률의 평균이 아니라 합계(totalFocusSec ÷ totalStudySec) 기준으로 계산한다
                    - `totalEventCounts` — 그날 전체 상태별 이벤트 발생 건수 합계 — sessions[].eventCounts를 모두 더한 값. 없는 상태도 0으로 내려간다
                    - `studiedDatesInMonth` — `date`가 속한 달 동안 공부 기록이 있는 날짜 목록. \
                    캘린더에 공부일을 표시하는 용도(중복 없음, 오름차순)

                    존재하지 않는 userId거나 기록 없는 날짜면 sessions는 빈 배열, sessionCount는 0, \
                    합계는 0, focusRate는 0.0, totalEventCounts는 모든 상태 0인 객체가 내려온다. \
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
                    통계 날짜(`statDate`) 기준으로 세션이 하루라도 있으면 그날은 공부한 날로 친다. \
                    유저에 저장된 값이 아니라 세션 이력에서 매번 계산한다.

                    - `streak` — 오늘(오늘 기록이 아직 없으면 어제)부터 거꾸로 이어진 연속 공부일. \
                    오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다 — 오늘이 지나기 전엔 끊긴 게 아니다. \
                    어제도 오늘도 기록이 없으면 0
                    - `maxStreak` — 전체 이력에서 가장 길었던 연속 공부일

                    기록이 없거나 존재하지 않는 userId면 둘 다 0이다 (목록 조회와 같은 계약).""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 현재 스트릭과 역대 최장 스트릭")
    @GetMapping("/streak")
    public StudySessionStreakResponse streak(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @RequestParam Long userId) {
        return studySessionService.streak(userId);
    }
}
