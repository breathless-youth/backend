package project.study.subject.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.subject.dto.SubjectCreateRequest;
import project.study.subject.dto.SubjectOrderRequest;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.SubjectUpdateRequest;
import project.study.subject.dto.TaskCreateRequest;
import project.study.subject.dto.TaskResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.service.StudySubjectService;

@Tag(name = "StudySubject", description = """
                과목 > 할 일 관리 API 모음 (BY-698). 세션 화면의 과목 시트가 쓴다 — 과목은 시간이 쌓이는 카테고리, \
                할 일은 과목 하위의 체크 가능한 항목이다. 과목별 시간은 세션 제출·스냅샷의 과목 구간(`subjectSegments`)으로 들어오고 \
                서버가 계산한 값의 누적 합계만 여기서 내려준다.

                ⚠️ **이 API만 `API-Version: 1`이다.** 다른 토큰 계약 API는 2를 쓰지만(ADR-0020) 과목 API는 \
                구 앱이 호출하지 않아 버전을 가를 이유가 없어 기본버전 하나로 통일했다. 인증은 그대로 \
                토큰이 필요하다 — 버전은 라우팅만 가르고 인가는 SecurityConfig가 정한다.""")
@RestController
@RequestMapping(value = "/api/subjects", version = "1")
@RequiredArgsConstructor
public class StudySubjectController {

    private final StudySubjectService subjectService;

    @Operation(summary = "내 과목 목록", description = """
            살아있는 과목을 저장된 순서(`PUT /order`, 같으면 id 오름차순)로, 각 과목의 보이는 할 일과 누적 시간을 붙여 내려준다. \
            새로 만든 과목은 맨 뒤다. 응답에 순서 번호는 없다 — 배열 순서가 곧 순서다.

            - **색** = `colorIndex`(0..19). 만들 때 서버가 덜 쓴 색을 배정하고 이후 바뀌지 않는다 — 앱은 팔레트에 매핑만 한다.
            - **보이는 할 일** = 미완료 전부 + 완료 시각이 오늘(KST)인 것. 어제 완료한 할 일은 숨지만 삭제되지 않는다.
            - **누적 시간** = 저장된 모든 세션의 과목 구간(`subjectSegments`)에서 서버가 계산한 총공부·순공의 합. 과목 시간은 할 일을 골랐든 과목만 골랐든 그 과목 전부의 합이다.
            - 과목이 하나도 없으면 빈 배열 — 앱은 이때 추천 칩을 보여준다.""")
    @ApiResponse(responseCode = "200", description = "과목 배열 — 각 원소에 tasks·studySec·focusSec")
    @GetMapping
    public List<SubjectResponse> list(@AuthenticationPrincipal Long userId) {
        return subjectService.list(userId);
    }

    @Operation(
            summary = "과목 추가",
            description = "이름만 받는다. 순서는 맨 뒤, 색은 살아있는 과목이 가장 적게 쓴 인덱스를 서버가 정한다. 살아있는 과목이 20개면 400.")
    @ApiResponse(responseCode = "201", description = "만들어진 과목 — 누적 0, 할 일 없음")
    @ApiResponse(
            responseCode = "400",
            description = "이름 누락·공백·50자 초과, 또는 과목 상한(20) 초과",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            name = "상한 초과",
                                            value = "{\"message\": \"과목은 최대 20개까지 만들 수 있습니다\"}")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubjectResponse create(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody SubjectCreateRequest request) {
        return subjectService.create(userId, request.name());
    }

    @Operation(summary = "과목 이름 변경")
    @ApiResponse(responseCode = "200", description = "바뀐 과목 — 할 일·누적 포함")
    @ApiResponse(responseCode = "404", description = "없는 과목이거나 다른 유저의 과목, 또는 이미 지운 과목")
    @PatchMapping("/{subjectId}")
    public SubjectResponse rename(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Valid @RequestBody SubjectUpdateRequest request) {
        return subjectService.rename(userId, subjectId, request.name());
    }

    @Operation(summary = "과목 삭제", description = "soft delete — 하위 할 일도 함께 숨는다. 세션에 쌓인 시간 기록은 남는다.")
    @ApiResponse(responseCode = "204", description = "삭제됨")
    @ApiResponse(responseCode = "404", description = "없는 과목이거나 다른 유저의 과목")
    @DeleteMapping("/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId) {
        subjectService.delete(userId, subjectId);
    }

    @Operation(summary = "과목 순서 저장", description = """
            과목 시트에서 끌어 바꾼 순서를 통째로 저장한다 — 드래그가 끝날 때 한 번 부른다. 보낸 순서대로 0부터 번호가 매겨지고 \
            목록 조회는 이 번호 오름차순(같으면 id)으로 내려온다.

            - `subjectIds`는 살아있는 내 과목 id여야 한다 — 남의 과목·지운 과목·없는 id·중복이 섞이면 400이고 아무것도 바뀌지 않는다.
            - 목록에서 빠진 살아있는 과목은 기존 상대 순서 그대로 보낸 것들 뒤에 붙는다 — 일부만 보내도 된다.
            - 새로 만든 과목은 항상 맨 뒤다. 응답에 순서 번호는 없다 — 배열 순서가 곧 순서다.""")
    @ApiResponse(responseCode = "200", description = "저장된 순서의 전체 과목 배열 — GET /api/subjects와 같은 모양")
    @ApiResponse(
            responseCode = "400",
            description = "내 과목이 아닌 id, 중복 id, 또는 subjectIds 누락",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "남의 과목", value = "{\"message\": \"사용자의 과목이 아닙니다\"}"),
                                @ExampleObject(name = "중복", value = "{\"message\": \"subjectIds에 중복이 있습니다\"}")
                            }))
    @PutMapping("/order")
    public List<SubjectResponse> reorder(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody SubjectOrderRequest request) {
        return subjectService.reorder(userId, request.subjectIds());
    }

    @Operation(summary = "할 일 추가", description = "과목 아래 할 일을 만든다. 마감일은 없다. 그 과목의 살아있는 할 일이 30개면 400.")
    @ApiResponse(responseCode = "201", description = "만들어진 할 일 — 미완료, 누적 0")
    @ApiResponse(responseCode = "400", description = "이름 누락·공백·100자 초과, 또는 할 일 상한(30) 초과")
    @ApiResponse(responseCode = "404", description = "없는 과목이거나 다른 유저의 과목")
    @PostMapping("/{subjectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse addTask(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Valid @RequestBody TaskCreateRequest request) {
        return subjectService.addTask(userId, subjectId, request.name());
    }

    @Operation(summary = "할 일 이름 변경·완료 토글", description = """
            `name`과 `done` 중 보낸 것만 바뀐다. `done: true`면 완료 시각이 서버 현재 시각으로 찍히고, \
            `false`면 지워진다(완료 해제). 완료한 할 일은 그날(KST)까지 목록에 보이고 다음 날부터 숨는다.""")
    @ApiResponse(responseCode = "200", description = "바뀐 할 일 — 누적 포함")
    @ApiResponse(responseCode = "400", description = "name·done 둘 다 없음, 또는 name이 공백")
    @ApiResponse(responseCode = "404", description = "없는 과목·할 일이거나 다른 유저의 것")
    @PatchMapping("/{subjectId}/tasks/{taskId}")
    public TaskResponse updateTask(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Parameter(description = "할 일 ID", example = "12") @PathVariable Long taskId,
            @Valid @RequestBody TaskUpdateRequest request) {
        return subjectService.updateTask(userId, subjectId, taskId, request);
    }

    @Operation(summary = "할 일 삭제", description = "soft delete — 세션에 쌓인 시간 기록은 남는다.")
    @ApiResponse(responseCode = "204", description = "삭제됨")
    @ApiResponse(responseCode = "404", description = "없는 과목·할 일이거나 다른 유저의 것")
    @DeleteMapping("/{subjectId}/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Parameter(description = "할 일 ID", example = "12") @PathVariable Long taskId) {
        subjectService.deleteTask(userId, subjectId, taskId);
    }
}
