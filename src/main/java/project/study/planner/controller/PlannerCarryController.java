package project.study.planner.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.planner.dto.PlannerTask;
import project.study.planner.dto.PlannerTaskRequests.Carry;
import project.study.planner.service.PlannerCarryService;

@Tag(
        name = "PlannerCarry",
        description = "BY-1166. 오늘 이전의 미완료 후보를 날짜 오름차순으로 제공한다. 반복은 오늘 반복 요일/회차가 없을 때 계열별 최근 미완료 한 건만 제시한다.")
@RestController
@RequestMapping(value = "/api/planner/carryover", version = "1")
@RequiredArgsConstructor
public class PlannerCarryController {
    private final PlannerCarryService carry;

    @Operation(
            summary = "못 끝낸 할 일 후보",
            description = "기기 현지 05시 오늘 기준. 다시 묻지 않기로 처리한 항목 제외. 배열을 date로 묶어 표시한다. GET은 상태를 바꾸지 않는다.")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "[{\"key\":\"t:12\",\"taskId\":12,\"subjectId\":3,\"name\":\"수학 3단원\",\"date\":\"2026-11-04\",\"done\":false,\"doneAt\":null,\"seriesId\":null,\"occurrenceDate\":null,\"dismissed\":false}]")))
    @GetMapping
    public List<PlannerTask> candidates(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) String timeZone) {
        return carry.candidates(userId, timeZone);
    }

    @Operation(
            summary = "선택 항목 가져오기·미선택 항목 다시 묻지 않기",
            description =
                    "offeredKeys는 실제 제시한 후보, selectedKeys는 그 안에서 고른 항목. selectedKeys=[]는 그냥 둘게요. 선택 항목은 오늘로 이동하고 미선택은 원래 날짜에 둔 채 다시 묻지 않는다. 전체 원자적: 한 건이라도 실패하면 이동·무시 모두 원복. 이미 오늘로 옮긴 같은 key 재시도는 중복 없음.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"offeredKeys\":[\"t:12\",\"r:4:2026-11-04\"],\"selectedKeys\":[\"t:12\"]}"))))
    @ApiResponse(responseCode = "200", description = "오늘로 가져온 항목 배열(그냥 둘게요면 [])")
    @ApiResponse(
            responseCode = "409",
            description =
                    "전부 원복. 후보가 바뀌었거나 이미 완료=PLANNER_CARRYOVER_CHANGED(후보를 다시 불러온다), " + "과목별 30개 초과=PLANNER_TASK_LIMIT",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"PLANNER_CARRYOVER_CHANGED\",\"message\":\"후보가 바뀌었습니다. 다시 불러오세요\"}")))
    @PostMapping
    public List<PlannerTask> decide(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody Carry request,
            @RequestParam(required = false) String timeZone) {
        return carry.decide(userId, request, timeZone);
    }
}
