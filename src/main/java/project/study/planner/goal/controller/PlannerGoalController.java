package project.study.planner.goal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import project.study.common.exception.ErrorResponse;
import project.study.planner.goal.dto.PlannerDefaultGoalResponse;
import project.study.planner.goal.dto.PlannerGoalRequest;
import project.study.planner.goal.dto.PlannerGoalResponse;
import project.study.planner.goal.service.PlannerGoalService;

@Tag(name = "Planner goals", description = """
        날짜별 플래너 목표. API-Version: 1, 토큰 인증이 필요하다.
        개별 목표가 없을 때만 기본 목표를 상속하며, 명시적인 0초와 미설정을 구분한다.
        날짜별 목표 변경·해제는 기본적으로 사용자 현지 05:00 기준 오늘보다 미래 날짜만 허용한다. 홈은 날짜별 PUT에 calendar=HOME을 지정하여 사용자 현지 달력상 오늘과 과거 날짜를 저장한다(날짜가 바뀐 뒤 재시도 가능).
        timeZone은 선택 IANA 이름이다. 쓰기에서 읽을 수 있으면 저장하고, 없거나 잘못되면 저장된 시간대(최초 Asia/Seoul)를 쓴다. 조회는 시간대를 변경하지 않는다.
        """)
@ApiResponse(
        responseCode = "401",
        description = "토큰 누락·만료·위조",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = ErrorResponse.class),
                        examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다\"}")))
@RestController
@RequestMapping(value = "/api/planner/goals", version = "1")
@RequiredArgsConstructor
public class PlannerGoalController {

    private final PlannerGoalService service;

    @Operation(summary = "기본 목표 조회", description = "미설정이어도 200이며 goalSec은 null이다.")
    @ApiResponse(
            responseCode = "200",
            description = "기본 목표",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PlannerDefaultGoalResponse.class),
                            examples = {
                                @ExampleObject(name = "설정됨", value = "{\"goalSec\":3600}"),
                                @ExampleObject(name = "미설정", value = "{\"goalSec\":null}")
                            }))
    @GetMapping("/default")
    public PlannerDefaultGoalResponse getDefault(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) String timeZone) {
        return service.getDefault(userId, timeZone);
    }

    @Operation(summary = "기본 목표 설정·수정", description = "0~86400초. 개별 목표가 있는 날짜는 그대로 유지된다.")
    @ApiResponse(
            responseCode = "200",
            description = "저장된 기본 목표",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PlannerDefaultGoalResponse.class),
                            examples = @ExampleObject(value = "{\"goalSec\":3600}")))
    @ApiResponse(
            responseCode = "400",
            description = "목표 초 누락·범위 오류",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "범위",
                                        value =
                                                "{\"code\":\"VALIDATION_FAILED\",\"message\":\"goalSec: 목표 초는 86400 이하여야 합니다\"}"),
                                @ExampleObject(
                                        name = "필수",
                                        value = "{\"code\":\"VALIDATION_FAILED\",\"message\":\"goalSec: 목표 초는 필수입니다\"}")
                            }))
    @PutMapping("/default")
    public PlannerDefaultGoalResponse putDefault(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PlannerGoalRequest request,
            @RequestParam(required = false) String timeZone) {
        return service.saveDefault(userId, request, timeZone);
    }

    @Operation(summary = "기본 목표 해제", description = "없어도 204. 개별 목표는 유지하고, 상속하던 날짜는 미설정 상태로 돌아간다.")
    @ApiResponse(responseCode = "204", description = "해제됨 또는 원래 미설정")
    @DeleteMapping("/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDefault(@AuthenticationPrincipal Long userId, @RequestParam(required = false) String timeZone) {
        service.deleteDefault(userId, timeZone);
    }

    @Operation(summary = "날짜별 목표 조회", description = "과거·오늘·미래를 조회한다. 개별 목표가 없을 때만 기본값이 적용된다.")
    @ApiResponse(
            responseCode = "200",
            description = "적용·개별·기본 목표",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PlannerGoalResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "기본값 상속",
                                        value =
                                                "{\"goalSec\":3600,\"individualGoalSec\":null,\"defaultGoalSec\":3600}"),
                                @ExampleObject(
                                        name = "개별 0",
                                        value = "{\"goalSec\":0,\"individualGoalSec\":0,\"defaultGoalSec\":3600}"),
                                @ExampleObject(
                                        name = "미설정",
                                        value = "{\"goalSec\":null,\"individualGoalSec\":null,\"defaultGoalSec\":null}")
                            }))
    @GetMapping("/{date}")
    public PlannerGoalResponse get(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "플래너 날짜 (YYYY-MM-DD)", example = "2030-03-02")
                    @PathVariable
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(required = false) String timeZone) {
        return service.get(userId, date, timeZone);
    }

    @Operation(
            summary = "날짜별 목표 설정·수정",
            description =
                    "기본은 사용자 현지 05시 기준 미래 날짜만 허용한다. calendar=HOME이면 현지 달력상 오늘과 과거 날짜를 저장한다(자정 후 재시도 가능). 0초도 개별값으로 저장된다.")
    @ApiResponse(
            responseCode = "200",
            description = "저장 후 적용·개별·기본 목표",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PlannerGoalResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"goalSec\":7200,\"individualGoalSec\":7200,\"defaultGoalSec\":3600}")))
    @ApiResponse(
            responseCode = "400",
            description = "목표 초 누락·범위 오류, 잘못된 날짜 또는 오늘·과거 날짜 변경",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "오늘·과거",
                                        value =
                                                "{\"code\":\"BAD_REQUEST\",\"message\":\"개별 목표는 사용자 현지 05시 기준 오늘보다 미래 날짜만 변경할 수 있습니다\"}"),
                                @ExampleObject(
                                        name = "범위",
                                        value =
                                                "{\"code\":\"VALIDATION_FAILED\",\"message\":\"goalSec: 목표 초는 86400 이하여야 합니다\"}")
                            }))
    @PutMapping("/{date}")
    public PlannerGoalResponse put(
            @AuthenticationPrincipal Long userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody PlannerGoalRequest request,
            @RequestParam(required = false) String timeZone,
            @RequestParam(required = false) String calendar) {
        if (calendar != null && !calendar.equals("HOME")) {
            throw new project.study.common.exception.BadRequestException("지원하지 않는 달력 기준입니다");
        }
        return service.save(userId, date, request, timeZone, "HOME".equals(calendar));
    }

    @Operation(summary = "날짜별 목표 해제", description = "사용자 현지 05시 기준 미래 날짜만 허용한다. 개별값을 지워 기본값을 다시 적용한다. 없으면 204.")
    @ApiResponse(responseCode = "204", description = "해제됨 또는 원래 미설정")
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 날짜 또는 오늘·과거 날짜 변경",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"BAD_REQUEST\",\"message\":\"개별 목표는 사용자 현지 05시 기준 오늘보다 미래 날짜만 변경할 수 있습니다\"}")))
    @DeleteMapping("/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String timeZone) {
        service.delete(userId, date, timeZone);
    }
}
