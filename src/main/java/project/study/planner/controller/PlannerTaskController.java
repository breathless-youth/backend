package project.study.planner.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.planner.dto.PlannerRepeatResponse;
import project.study.planner.dto.PlannerTask;
import project.study.planner.dto.PlannerTaskRequests.Create;
import project.study.planner.dto.PlannerTaskRequests.Move;
import project.study.planner.dto.PlannerTaskRequests.Repeat;
import project.study.planner.dto.PlannerTaskRequests.Scope;
import project.study.planner.dto.PlannerTaskRequests.Update;
import project.study.planner.service.PlannerRepeatService;
import project.study.planner.service.PlannerTaskService;

@Tag(
        name = "PlannerTask",
        description =
                "BY-1163/1164. 날짜별 계획. API-Version 1, 토큰 필요. key는 t:할일ID 또는 r:반복계열ID:원래날짜다. 실제 완료 시각(doneAt)과 계획 날짜(date)를 구분한다.")
@RestController
@RequestMapping(value = "/api/planner/tasks", version = "1")
@RequiredArgsConstructor
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "과거 변경=PLANNER_PAST_DAY, 미래 완료·이름/날짜 오류=BAD_REQUEST",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value = "{\"code\":\"BAD_REQUEST\",\"message\":\"아직 할 일을 완료할 수 없습니다\"}"))),
    @ApiResponse(
            responseCode = "404",
            description = "없는 회차 또는 다른 사용자",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(value = "{\"code\":\"NOT_FOUND\",\"message\":\"할 일을 찾을 수 없습니다\"}"))),
    @ApiResponse(
            responseCode = "409",
            description = "변경 전체 원복. 과목별 날짜당 30개 초과=PLANNER_TASK_LIMIT, 옮길 날짜에 같은 반복 회차=PLANNER_DUPLICATE_OCCURRENCE",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"PLANNER_DUPLICATE_OCCURRENCE\",\"message\":\"같은 반복 할 일이 이미 있는 날짜입니다\"}")))
})
public class PlannerTaskController {
    private final PlannerTaskService tasks;
    private final PlannerRepeatService repeats;

    @Operation(
            summary = "기간별 할 일",
            description = "from/to 포함, 최대 366일. 종료일 없는 반복도 필요한 날짜에 계산한다. 삭제 회차 제외. 조회 때 한도만큼 잘라내지 않는다.")
    @GetMapping
    public List<PlannerTask> list(
            @AuthenticationPrincipal Long userId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return tasks.tasks(userId, from, to);
    }

    @Operation(
            summary = "날짜별 할 일 추가",
            description = "오늘/미래만 가능. 반복 요일은 MONDAY~SUNDAY. 종료일 포함, null이면 끝없음. 시작일이 선택 요일이 아니어도 원래 할 일은 남는다.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"subjectId\":3,\"date\":\"2026-11-06\",\"name\":\"수학 3단원\",\"repeat\":{\"weekdays\":[\"MONDAY\",\"FRIDAY\"],\"endDate\":null}}"))))
    @ApiResponse(
            responseCode = "201",
            description = "저장한 할 일",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"key\":\"r:4:2026-11-06\",\"taskId\":12,\"subjectId\":3,\"name\":\"수학 3단원\",\"date\":\"2026-11-06\",\"done\":false,\"doneAt\":null,\"seriesId\":4,\"occurrenceDate\":\"2026-11-06\",\"dismissed\":false}")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlannerTask create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody Create request,
            @RequestParam(required = false) String timeZone) {
        return tasks.create(userId, request, timeZone);
    }

    @Operation(
            summary = "이름 변경·완료·완료 취소",
            description =
                    "name/done 중 보낸 값만 바꾼다. scope THIS(기본) 또는 FUTURE. 완료는 THIS만 가능. 과거 완료도 원래 계획 날짜의 완료 개수에 반영한다.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @Content(
                                            examples = {
                                                @ExampleObject(name = "완료", value = "{\"done\":true}"),
                                                @ExampleObject(
                                                        name = "이 날부터 이름 변경",
                                                        value = "{\"name\":\"다음 단원\",\"scope\":\"FUTURE\"}")
                                            })))
    @PatchMapping("/{key}")
    public PlannerTask update(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key,
            @Valid @RequestBody Update request,
            @RequestParam(required = false) String timeZone) {
        return tasks.update(userId, key, request, timeZone);
    }

    @Operation(
            summary = "할 일 삭제",
            description = "scope=THIS(기본)는 그 회차만, FUTURE는 그 날부터 모두. 과거 삭제 불가. 실제 공부 기록은 삭제하지 않는다.")
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key,
            @RequestParam(defaultValue = "THIS") Scope scope,
            @RequestParam(required = false) String timeZone) {
        tasks.delete(userId, key, scope, timeZone);
    }

    @Operation(
            summary = "할 일을 다른 날짜로 이동",
            description =
                    "목적지는 오늘 이후. 반복은 이 회차만 이동하며 반복 요일은 유지한다. 같은 계열의 다른 회차가 있으면 409. 실패하면 원본 날짜/상태를 유지한다. 같은 목적지 재시도는 멱등.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content = @Content(examples = @ExampleObject(value = "{\"date\":\"2026-11-07\"}"))))
    @PutMapping("/{key}/date")
    public PlannerTask move(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key,
            @Valid @RequestBody Move request,
            @RequestParam(required = false) String timeZone) {
        return tasks.move(userId, key, request.date(), timeZone);
    }

    @Operation(
            summary = "반복 편집 초기값 조회",
            description =
                    "현재 계획 날짜에 유효한 규칙 버전을 읽는다. 이동한 회차는 이동 후 날짜 기준이며 요일 밖에서도 읽을 수 있다. 일반 할 일/종료된 계열의 예외는 weekdays=[]다. 가상 회차를 저장하지 않는다.")
    @ApiResponse(
            responseCode = "200",
            description = "반복 설정",
            content =
                    @Content(
                            schema = @Schema(implementation = PlannerRepeatResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "월수금",
                                        value =
                                                "{\"seriesId\":4,\"startDate\":\"2026-11-06\",\"weekdays\":[\"MONDAY\",\"WEDNESDAY\",\"FRIDAY\"],\"endDate\":\"2026-12-31\"}"),
                                @ExampleObject(
                                        name = "종료일 없음",
                                        value =
                                                "{\"seriesId\":4,\"startDate\":\"2026-11-06\",\"weekdays\":[\"MONDAY\"],\"endDate\":null}"),
                                @ExampleObject(
                                        name = "안 함",
                                        value =
                                                "{\"seriesId\":null,\"startDate\":null,\"weekdays\":[],\"endDate\":null}")
                            }))
    @GetMapping("/{key}/repeat")
    public PlannerRepeatResponse repeatSettings(@AuthenticationPrincipal Long userId, @PathVariable String key) {
        return repeats.get(userId, key);
    }

    @Operation(
            summary = "이 회차부터 반복 변경",
            description =
                    "요일/종료일 변경은 과거 회차를 보존하고 해당 날짜부터 적용한다. 개별 이동/이름 변경된 미래 예외는 유지한다. weekdays=[]는 현재를 일반 할 일로 남기고 이후 반복을 끝낸다.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @Content(
                                            examples = {
                                                @ExampleObject(
                                                        name = "평일",
                                                        value =
                                                                "{\"weekdays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"],\"endDate\":null}"),
                                                @ExampleObject(name = "안 함", value = "{\"weekdays\":[]}")
                                            })))
    @PutMapping("/{key}/repeat")
    public PlannerTask repeat(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key,
            @Valid @RequestBody Repeat request,
            @RequestParam(required = false) String timeZone) {
        return repeats.change(userId, key, request, timeZone);
    }

    @Operation(
            summary = "세션 제출용 할 일 ID 준비",
            description = "가상 반복 회차를 한 번만 저장한다. 응답 taskId를 기존 completedTaskIds 계약에 사용한다. 같은 key 재호출은 같은 ID다.")
    @PostMapping("/{key}/materialize")
    public PlannerTask materialize(@AuthenticationPrincipal Long userId, @PathVariable String key) {
        return tasks.materialize(userId, key);
    }
}
