package project.study.studysession.controller;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.studysession.dto.ManualStudySessionRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.service.ManualStudySessionService;
import project.study.subject.service.StudySubjectService;

@Tag(
        name = "ManualStudySession",
        description = "수동 기록 — 측정하지 못한 공부 시간을 사용자가 직접 기록한다 (ADR-0031). " + "개인 기록 합계에만 더하고 랭킹 등에는 쓰지 않는다")
@RestController
@RequestMapping(path = "/api/study-sessions/manual", version = "1")
@RequiredArgsConstructor
public class ManualStudySessionController {

    private static final String COMMON_RULES = """
            검증 — 시작·종료는 초 이하를 버려 분 단위로 맞춘 뒤 본다.
            - 종료는 시작보다 1분 이상 뒤여야 하고 24시간을 넘을 수 없다
            - 종료는 서버 지금 이전이어야 한다 (측정 제출과 달리 시계 오차 여유가 없다)
            - `subjectId`는 토큰 유저의 과목이어야 하고, 지운 과목은 새로 고를 수 없다

            겹침 — 같은 유저의 다른 기록(측정·수동)과 겹치는 구간은 빼고 남는 조각만 저장한다. \
            끝이 닿는 것은 겹침이 아니다. 남는 조각의 경계는 분 단위로 안쪽에 맞추고 1분 미만 조각은 버린다. \
            남는 조각이 없으면 `409`다.

            묶음 — 겹침으로 잘리거나 한국 시간 자정을 넘겨 나뉜 조각은 모두 첫 조각의 시작 시각을 공유하는 한 묶음이다. \
            응답은 조각 배열이고 `source`는 `MANUAL`, `studySec = focusSec`, `events`는 비어 있다.""";

    private final ManualStudySessionService manualStudySessionService;
    private final StudySubjectService subjectService;

    @Operation(summary = "수동 기록 추가", description = COMMON_RULES)
    @ApiResponse(responseCode = "201", description = "저장한 조각 배열")
    @ApiResponse(
            responseCode = "400",
            description = "검증 실패",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "1분 미만", value = "{\"message\": \"1분 이상이어야 해요\"}"),
                                @ExampleObject(name = "미래", value = "{\"message\": \"지금 이후 시간은 넣을 수 없어요\"}"),
                                @ExampleObject(name = "24시간 초과", value = "{\"message\": \"24시간을 넘길 수 없어요\"}"),
                                @ExampleObject(name = "지운 과목", value = "{\"message\": \"지운 과목은 고를 수 없어요\"}"),
                                @ExampleObject(name = "남의 과목", value = "{\"message\": \"사용자의 과목이 아닙니다\"}")
                            }))
    @ApiResponse(
            responseCode = "409",
            description = "전부 겹침 — 남는 조각이 없다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "전부 겹침", value = "{\"message\": \"이미 기록이 있는 시간이에요\"}")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<StudySessionResponse> create(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ManualStudySessionRequest request) {
        subjectService.assertOwned(userId, request.subjectIds());
        return manualStudySessionService.create(userId, request);
    }

    @Operation(
            summary = "수동 기록 수정",
            description = "id는 묶음의 어느 조각이어도 된다. 묶음 전체를 지우고 새 값으로 다시 만들므로 조각 id가 바뀔 수 있다. "
                    + "겹침 계산에서 자기 묶음은 빠진다. 묶음의 지금 과목이 지운 과목이면 그 id를 그대로 다시 보내는 것은 받는다.\n\n"
                    + COMMON_RULES)
    @ApiResponse(responseCode = "200", description = "다시 만든 조각 배열")
    @ApiResponse(responseCode = "400", description = "검증 실패 — 추가와 같다")
    @ApiResponse(responseCode = "404", description = "없거나 남의 세션")
    @ApiResponse(responseCode = "409", description = "측정 세션 id이거나, 전부 겹침")
    @PatchMapping("/{id}")
    public List<StudySessionResponse> update(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "묶음 안 아무 조각의 세션 ID", example = "10") @PathVariable Long id,
            @Valid @RequestBody ManualStudySessionRequest request) {
        subjectService.assertOwned(userId, request.subjectIds());
        return manualStudySessionService.update(userId, id, request);
    }

    @Operation(summary = "수동 기록 삭제", description = "id가 속한 묶음 전체(자정·겹침으로 나뉜 조각 모두)를 지운다.")
    @ApiResponse(responseCode = "204", description = "삭제함")
    @ApiResponse(responseCode = "404", description = "없거나 남의 세션")
    @ApiResponse(responseCode = "409", description = "측정 세션 id — 측정한 기록은 지울 수 없다")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "묶음 안 아무 조각의 세션 ID", example = "10") @PathVariable Long id) {
        manualStudySessionService.delete(userId, id);
    }
}
