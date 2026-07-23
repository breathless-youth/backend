package project.study.studysession.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.service.StudySessionService;

@Tag(name = "StudySession", description = "공부 세션 기록 API 모음 — 방 퇴장 시 세션 전체를 한 번에 제출받아 검증·계산·저장한다 (ADR-0003)")
@RestController
@RequestMapping("/api/study-sessions")
@RequiredArgsConstructor
public class StudySessionController {

    private final StudySessionService studySessionService;

    @Operation(summary = "공부 세션 제출", description = """
                    공부를 마칠 때(방 퇴장 시) 세션 전체를 한 번에 제출한다. \
                    서버는 세션을 실시간으로 추적하지 않는다 — 앱의 온디바이스 AI가 감지한 \
                    시작/종료 시각과 그 사이의 **비공부 상태 이벤트**(PHONE·DEVICE·AWAY) 목록이 데이터의 전부다.

                    서버가 하는 일은 세 가지다.
                    1. **검증** — 아래 규칙을 하나라도 어기면 `400`으로 거절한다.
                    2. **계산** — 총 시간(`sessionSec`), 순공 시간(`focusSec` = 총 시간 - 이벤트 구간 합), \
                    통계 귀속 날짜(`statDate`, 한국 시간 기준 시작 날짜)를 서버가 직접 계산한다. \
                    조작 방지를 위해 클라이언트가 계산한 값은 받지 않는다.
                    3. **저장** — 세션과 이벤트를 저장하고 계산 결과를 돌려준다.

                    **검증 규칙**
                    - 종료 시각은 시작 시각 이후여야 한다 (세션·이벤트 모두)
                    - 세션은 24시간을 초과할 수 없다
                    - 세션 종료 시각은 미래일 수 없다 (기기 시계 오차 5분까지 허용)
                    - 이벤트는 세션 구간 안에 있어야 하고, 서로 겹칠 수 없다 (끝과 시작이 맞닿는 것은 허용)
                    - 이벤트는 순서가 뒤섞여 와도 된다 — 서버가 시작 시각 기준으로 정렬한다

                    자정을 걸친 세션은 시작 시각의 한국 날짜에 통째로 귀속된다.""")
    @ApiResponse(responseCode = "201", description = "저장 성공 — 서버가 계산한 sessionSec/focusSec/statDate를 포함한 세션이 내려온다")
    @ApiResponse(
            responseCode = "400",
            description = "검증 실패 — 시간 규칙 위반, 이벤트 겹침, 필수 값 누락 등",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "종료가 시작보다 빠름",
                                        value = "{\"message\": \"세션 종료 시각은 시작 시각 이후여야 합니다\"}"),
                                @ExampleObject(name = "24시간 초과", value = "{\"message\": \"세션은 24시간을 초과할 수 없습니다\"}"),
                                @ExampleObject(name = "미래 시각", value = "{\"message\": \"세션 종료 시각이 미래일 수 없습니다\"}"),
                                @ExampleObject(name = "이벤트 겹침", value = "{\"message\": \"이벤트 구간이 서로 겹칠 수 없습니다\"}"),
                                @ExampleObject(name = "이벤트가 세션 밖", value = "{\"message\": \"이벤트는 세션 구간 안에 있어야 합니다\"}"),
                                @ExampleObject(name = "필수 값 누락", value = "{\"message\": \"userId: 널이어서는 안됩니다\"}")
                            }))
    @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 userId — 먼저 POST /api/users 로 유저를 등록해야 한다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(name = "유저 없음", value = "{\"message\": \"존재하지 않는 사용자입니다: 999\"}")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudySessionResponse create(@Valid @RequestBody StudySessionCreateRequest request) {
        return studySessionService.create(request);
    }

    @Operation(summary = "공부 세션 단건 조회", description = """
                    세션 하나를 상태 이벤트 목록까지 포함해 상세 조회한다. \
                    세션 상세 화면(타임라인 표시 등)에서 사용한다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 이벤트 목록을 포함한 세션 상세")
    @ApiResponse(
            responseCode = "404",
            description = "해당 id의 세션이 없음",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "세션 없음", value = "{\"message\": \"세션을 찾을 수 없습니다: 999\"}")))
    @GetMapping("/{id}")
    public StudySessionResponse get(@Parameter(description = "세션 제출 시 발급된 세션 ID") @PathVariable Long id) {
        return studySessionService.get(id);
    }

    @Operation(summary = "기간별 공부 세션 목록 조회", description = """
                    한 유저의 세션을 통계 날짜(`statDate`) 기준 `from`~`to` 기간(양 끝 포함)으로 조회한다. \
                    캘린더·일별 기록 화면에서 사용한다.

                    응답은 세션 목록과 기간 전체 통계를 함께 담은 객체다.
                    - `sessions` — 세션 요약 목록, 시작 시각 내림차순. 이벤트 목록은 미포함(상세는 단건 조회 사용)
                    - `totalSessionSec` / `totalFocusSec` — 기간 전체 총 시간·순공 시간 합계(초)
                    - `focusRate` — 기간 전체 집중률(%). 세션별 집중률의 평균이 아니라 합계 기준으로 계산한다
                    - `eventCounts` — 상태별 이벤트 발생 건수. 없는 상태도 0으로 내려간다

                    존재하지 않는 userId거나 기록 없는 기간이면 sessions는 빈 배열, \
                    합계는 0, focusRate는 0.0, eventCounts는 모든 상태 0인 객체가 내려온다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 세션 목록 + 기간 전체 합계·집중률·상태별 이벤트 건수")
    @GetMapping
    public StudySessionListResponse list(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)") @RequestParam Long userId,
            @Parameter(description = "조회 시작 날짜 (ISO-8601, 예: 2026-07-01) — statDate 기준, 포함")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "조회 끝 날짜 (ISO-8601, 예: 2026-07-31) — statDate 기준, 포함")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return studySessionService.list(userId, from, to);
    }
}
