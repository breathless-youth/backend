package project.study.subject.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.subject.dto.SubjectCreateRequest;
import project.study.subject.dto.SubjectDayResponse;
import project.study.subject.dto.SubjectOrderRequest;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.SubjectUpdateRequest;
import project.study.subject.dto.TaskCreateRequest;
import project.study.subject.dto.TaskResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.service.StudySubjectService;
import project.study.subject.service.SubjectDayService;

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
    private final SubjectDayService subjectDayService;

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

    @Operation(summary = "날짜 기준 과목 목록", description = """
                    홈 날짜 줄이 고른 날짜에 **그날 있던 과목**을, 그날의 과목별 시간과 완료 여부를 붙여 내려준다 (BY-1053). \
                    `GET /api/subjects`와 달리 지난 날짜에는 그날 뒤에 지운 과목도 실리고, 그날 뒤에 만든 과목은 빠진다.

                    - **하루** = 그 달력 날짜의 05:00부터 다음 날 05:00(KST)까지 — 플래너와 같다. 새벽 0~5시 공부는 전날에 든다.
                    - **그날 있던 과목** = 그 하루가 끝나기 전에 만들었고, 그 하루가 시작한 뒤에 지웠거나 아직 안 지운 과목. \
                    서버가 이 한 곳에서 기간으로 판단한다 — 시작일·종료일이 생기면 이 판단만 바뀐다.
                    - **순서** = 살아있는 과목은 `GET /api/subjects`와 같은 순서, 그 뒤에 지운 과목(`deleted: true`)이 id 오름차순.
                    - **시간** = 그 하루에 걸치는 세션의 과목 구간을 05시 창으로 잘라 합산한 총공부·순공(초). 길이가 1분 미만인 \
                    세션도 포함하고, 수동 기록도 더한다 — `GET /api/subjects`의 누적과 같은 기준이다. 공부하지 않았으면 0.
                    - **완료** = 그 날짜에 `PUT /{subjectId}/completions/{date}`로 기록한 것. 날짜별이라 어제 완료한 과목도 오늘은 false다.
                    - 할 일은 싣지 않는다 — 일간 조회 `GET /api/stats`의 `subjects[].tasks`를 그대로 쓴다.
                    - 미래 날짜도 조회는 된다(살아있는 과목이 시간 0으로 실린다). 과목이 하나도 없던 날이면 빈 배열.""")
    @ApiResponse(responseCode = "200", description = "그날 있던 과목 배열 — 각 원소에 studySec·focusSec·completed·completedAt")
    @ApiResponse(responseCode = "400", description = "date 누락 또는 형식 오류")
    @GetMapping("/day")
    public List<SubjectDayResponse> subjectsOn(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "홈 날짜 줄의 달력 날짜 (ISO-8601) — 이 날 05시부터 다음 날 05시까지", example = "2026-10-29")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return subjectDayService.subjectsOn(userId, date);
    }

    @Operation(summary = "과목 완료 표시", description = """
                    과목을 그 날짜에 완료한 것으로 기록한다 — 홈 과목 카드의 `완료 표시`. 날짜별 기록이라 다음 날에는 완료 전으로 돌아간다.

                    - 같은 날 같은 과목을 다시 보내면 그대로 204다(멱등). 기록은 한 건만 남는다.
                    - 지난 날짜도 된다. 그날에 있던 과목이면 지금 지운 과목도 된다.
                    - 미래 날짜(오늘 KST 달력 날짜보다 뒤)와 그날 없던 과목은 400, 내 과목이 아니거나 없는 과목은 404.""")
    @ApiResponse(responseCode = "204", description = "기록됨(이미 기록돼 있던 경우 포함)")
    @ApiResponse(
            responseCode = "400",
            description = "미래 날짜, 그날 없던 과목, 또는 date 형식 오류",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "미래 날짜", value = "{\"message\": \"미래 날짜에는 완료를 기록할 수 없습니다\"}"),
                                @ExampleObject(name = "그날 없던 과목", value = "{\"message\": \"그날에 없던 과목입니다\"}")
                            }))
    @ApiResponse(responseCode = "404", description = "없는 과목이거나 다른 유저의 과목")
    @PutMapping("/{subjectId}/completions/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Parameter(description = "완료한 날짜 (ISO-8601) — 홈 날짜 줄의 달력 날짜", example = "2026-10-29")
                    @PathVariable
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        subjectDayService.complete(userId, subjectId, date);
    }

    @Operation(summary = "과목 완료 취소", description = "그 날짜의 완료 기록을 지운다 — 완료한 카드를 오른쪽으로 밀어 `완료 취소`. 기록이 없어도 204다(멱등).")
    @ApiResponse(responseCode = "204", description = "지워짐(기록이 없던 경우 포함)")
    @ApiResponse(responseCode = "400", description = "date 형식 오류")
    @DeleteMapping("/{subjectId}/completions/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uncomplete(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "과목 ID", example = "3") @PathVariable Long subjectId,
            @Parameter(description = "완료를 취소할 날짜 (ISO-8601)", example = "2026-10-29")
                    @PathVariable
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        subjectDayService.uncomplete(userId, subjectId, date);
    }

    @Operation(summary = "과목 추가", description = """
                    이름만 받는다. 순서는 맨 뒤, 색은 살아있는 과목이 가장 적게 쓴 인덱스(동률이면 지운 과목이 덜 쓴 색)를 서버가 정한다. 살아있는 과목이 20개면 400.

                    - **이름은 사용자 안에서 하나다.** 살아있는 과목과 같은 이름(앞뒤 공백을 뗀 뒤 그대로 비교)이면 409.
                    - **지운 과목과 같은 이름이면 그 과목을 되살린다.** 새 id를 만들지 않고 예전 id로 목록 맨 뒤에 돌아오며, \
                    누적 시간도 이어진다. 색은 예전 색을 쓰되 다른 과목(지운 과목 포함)이 그 색을 쓰고 있으면 덜 쓴 색으로 바뀐다. 함께 지워진 할 일은 돌아오지 않는다.""")
    @ApiResponse(responseCode = "201", description = "만들어진 과목 — 누적 0, 할 일 없음. 되살린 과목이면 예전 id와 누적 시간")
    @ApiResponse(responseCode = "409", description = "살아있는 과목이 이미 쓰는 이름")
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

    @Operation(summary = "과목 이름 변경", description = "살아있는 다른 과목이 이미 쓰는 이름으로는 바꿀 수 없다(409). 지금 이름 그대로 보내는 것은 된다.")
    @ApiResponse(responseCode = "200", description = "바뀐 과목 — 할 일·누적 포함")
    @ApiResponse(responseCode = "409", description = "살아있는 다른 과목이 이미 쓰는 이름")
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
