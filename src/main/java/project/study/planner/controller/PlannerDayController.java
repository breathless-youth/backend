package project.study.planner.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.planner.dto.PlannerDayResponse;
import project.study.planner.dto.PlannerDaySummary;
import project.study.planner.dto.PlannerTaskRequests.Order;
import project.study.planner.service.PlannerQueryService;
import project.study.planner.service.PlannerSelectionService;

@Tag(
        name = "Planner",
        description =
                "BY-1162/1168. 기기 IANA timeZone(생략 시 저장된 사용자 시간대)의 현지 05:00~다음 날 05:00. DST 날의 실제 길이를 따른다. GET /api/subjects/day는 그날 존재했던 과목, 이 API는 선택·할 일·실제 기록의 과목 합집합이다. 기존 /api/stats의 자정 통계를 바꾸지 않는다.")
@RestController
@RequestMapping(value = "/api/planner", version = "1")
@RequiredArgsConstructor
public class PlannerDayController {
    private final PlannerQueryService query;
    private final PlannerSelectionService selections;

    @Operation(
            summary = "날짜별 계획과 실제 기록",
            description =
                    "서버가 직접 선택한 과목+할 일이 있는 과목+기록에 남은 과목을 합친다. 기록만 있는 삭제 과목도 포함한다. 선택 순서 후 전체 과목 순서/id. 수정·삭제된 개인 기록을 반영하며 랭킹 원본은 유지한다. records의 startedAt/endedAt은 표시 구간, fullStartedAt/fullEndedAt은 편집에 사용할 현재 전체 구간이다.")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            examples = {
                                @ExampleObject(
                                        name = "계획 없는 날",
                                        value =
                                                "{\"date\":\"2026-11-06\",\"timeZone\":\"Asia/Seoul\",\"studySec\":0,\"focusSec\":0,\"goal\":{\"goalSec\":14400,\"individualGoalSec\":null,\"defaultGoalSec\":14400},\"subjects\":[],\"records\":[]}"),
                                @ExampleObject(name = "05시를 걸친 기록의 당일 부분", value = """
                                            {"date":"2026-11-06","timeZone":"Asia/Seoul","studySec":3600,"focusSec":3600,
                                             "goal":{"goalSec":null,"individualGoalSec":null,"defaultGoalSec":null},"subjects":[],
                                             "records":[{"key":"c:2026-11-05T19:30:00Z","source":"MEASURED",
                                               "startedAt":"2026-11-05T20:00:00Z","endedAt":"2026-11-05T21:00:00Z",
                                               "fullStartedAt":"2026-11-05T19:30:00Z","fullEndedAt":"2026-11-05T21:00:00Z",
                                               "studySec":3600,"focusSec":3600,"subjectSegments":[]}]}
                                            """)
                            }))
    @GetMapping("/days/{date}")
    public PlannerDayResponse day(
            @AuthenticationPrincipal Long userId,
            @PathVariable LocalDate date,
            @RequestParam(required = false) String timeZone) {
        return query.day(userId, date, timeZone);
    }

    @Operation(
            summary = "날짜·기간별 상세 조회",
            description = "from/to 포함 최대 366일. 미래 계획과 종료일 없는 반복을 지원한다. 7일을 넘기면 다음 주도 같은 계약으로 조회한다.")
    @GetMapping
    public List<PlannerDayResponse> range(
            @AuthenticationPrincipal Long userId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) String timeZone) {
        return query.range(userId, from, to, timeZone);
    }

    @Operation(
            summary = "주간 요약·달력 데이터",
            description =
                    "from/to 포함 최대 366일. 모든 날짜를 0으로 채운다. studied는 순공이 있는 날, hasTasks는 계획 할 일이 있는 날이다. 기존 자정 기준 /api/stats/period와 날짜 경계가 다르다.")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "[{\"date\":\"2026-11-06\",\"taskCount\":3,\"completedCount\":1,\"studySec\":3600,\"focusSec\":3000,\"studied\":true,\"hasTasks\":true,\"goalSec\":14400}]")))
    @GetMapping("/summary")
    public List<PlannerDaySummary> summary(
            @AuthenticationPrincipal Long userId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) String timeZone) {
        return query.summary(userId, from, to, timeZone);
    }

    @Operation(
            summary = "그날 공부할 과목 선택",
            description = "오늘/미래만 가능. 전체 과목 생성은 POST /api/subjects 후 이 경로를 호출한다. 같은 과목 재선택은 멱등이다.")
    @PutMapping("/days/{date}/subjects/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void select(
            @AuthenticationPrincipal Long userId,
            @PathVariable LocalDate date,
            @PathVariable Long subjectId,
            @RequestParam(required = false) String timeZone) {
        selections.select(userId, date, subjectId, timeZone);
    }

    @Operation(
            summary = "날짜별 과목 순서 저장",
            description = "subjectIds 배열 순서. 없는/남의/지운 과목·중복 ID는 거절한다. 빠진 기존 선택은 뒤에 유지한다.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content = @Content(examples = @ExampleObject(value = "{\"subjectIds\":[3,8,2]}"))))
    @PutMapping("/days/{date}/subjects/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void order(
            @AuthenticationPrincipal Long userId,
            @PathVariable LocalDate date,
            @Valid @RequestBody Order order,
            @RequestParam(required = false) String timeZone) {
        selections.order(userId, date, order.subjectIds(), timeZone);
    }

    @Operation(
            summary = "이 날에서 과목 빼기",
            description = "그날 할 일도 함께 지우며 반복은 그 회차만 지운다. 다른 날짜·전체 과목·실제 기록은 보존한다. 기록이 있는 과목은 409. 과거는 불가.")
    @ApiResponse(
            responseCode = "409",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"CONFLICT\",\"message\":\"공부 기록이 있는 과목은 이 날에서 뺄 수 없습니다\"}")))
    @DeleteMapping("/days/{date}/subjects/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal Long userId,
            @PathVariable LocalDate date,
            @PathVariable Long subjectId,
            @RequestParam(required = false) String timeZone) {
        selections.remove(userId, date, subjectId, timeZone);
    }
}
