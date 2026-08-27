package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.service.DuplicateSessionException;
import project.study.studysession.service.StudySessionService;

@Tag(
        name = "StudySession",
        description = "공부 세션 기록 API 모음 — 방 퇴장 시 세션 전체를 한 번에 제출받아 검증·계산·저장한다 (ADR-0003). 통계 조회는 StudySessionStats 참고")
@RestController
@RequestMapping("/api/study-sessions")
@RequiredArgsConstructor
public class StudySessionController {

    private final StudySessionService studySessionService;

    @Operation(summary = "공부 세션 제출", description = """
                    공부를 마칠 때(방 퇴장 시) 세션 전체를 한 번에 제출한다. \
                    서버는 세션을 실시간으로 추적하지 않는다 — 앱에서 제출한 \
                    시작/종료 시각, 앱이 잰 총 공부 시간(`studySec`)과 순공 시간(`focusSec`), \
                    온디바이스에서 제공한 **비공부 상태 이벤트**(PHONE·DEVICE·AWAY·PAUSE) 목록이 데이터의 전부다. \
                    이벤트 1건은 `status`/`startedAt`/`endedAt` 3개 필드만 있으면 된다.

                    세션(방 입장~퇴장) 안에 총 공부시간 타이머가 있고, 그 안에 다시 순공시간 타이머가 있는 구조다 — \
                    `PAUSE`(일시정지, 앱에서 직접 멈추는 상태)는 총공부·순공 타이머를 모두 멈추고, \
                    나머지(PHONE/DEVICE/AWAY)는 순공 타이머만 멈춘다.

                    서버가 하는 일은 세 가지다.
                    1. **검증** — 아래 규칙을 하나라도 어기면 `400`으로 거절한다.
                    2. **계산** — 통계 귀속 날짜(`statDate`, 한국 시간 기준 시작 날짜)만 서버가 계산한다. \
                    총 공부 시간(`studySec`)과 순공 시간(`focusSec`)은 앱이 제출한 값을 그대로 저장한다 — \
                    자정 분할 시에는 조각 길이에 비례해 배분한다(합계 보존).
                    3. **저장** — 세션과 이벤트를 저장하고 결과를 돌려준다.

                    **검증 규칙**
                    - 종료 시각은 시작 시각 이후여야 한다 (세션·이벤트 모두)
                    - 세션은 24시간을 초과할 수 없다
                    - 세션 종료 시각은 미래일 수 없다 (기기 시계 오차 5분까지 허용)
                    - 총 공부 시간(`studySec`)은 0 이상, (세션 총 시간 − `PAUSE` 이벤트 시간 합) 이하여야 한다
                    - 순공 시간(`focusSec`)은 0 이상, 총 공부 시간(`studySec`) 이하여야 한다
                    - 이벤트는 세션 구간 안에 있어야 하고, 서로 겹칠 수 없다 (끝과 시작이 맞닿는 것은 허용)
                    - 이벤트는 순서가 뒤섞여 와도 된다 — 서버가 시작 시각 기준으로 정렬한다
                    - 검증은 자정 분할 전의 원본 제출 기준이다 (예: 자정을 걸친 25시간 세션은 거절)

                    **자정 분할 — 응답은 항상 배열이다.** 세션이 한국 시간 자정(00:00)을 넘으면 \
                    날짜별 세션으로 나뉘어 저장된다. 예: 24일 23시~25일 01시 제출 → 24일 세션(23~00시)과 \
                    25일 세션(00~01시) 2개가 만들어지고 응답 배열에 둘 다 담긴다. 요청의 `studySec`은 PAUSE를 제외한 \
                    조각 길이 비율로, `focusSec`은 전체 이벤트를 제외한 조각 길이 비율로 나뉘어 저장된다(각각 조각 합 = 요청값). \
                    자정에 걸친 이벤트도 시각 기준으로 나뉘어 각 세션에 귀속된다 (각 날짜 조회 응답의 세션별 `eventCounts`와 \
                    합계 `totalEventCounts`에 각각 1건씩 잡힌다). \
                    자정을 넘지 않으면 요소가 1개인 배열이 내려온다. \
                    정확히 자정에 시작하거나 끝나는 세션은 분할되지 않는다.

                    **멱등 재제출 — 중복 저장 방지.** `userId`+`startedAt`이 멱등 키다. 같은 키로 다시 제출하면 \
                    (앱 강제종료 후 재접속해 로컬 보관분을 재전송하는 경우 등) 새로 저장하지 않고 이미 저장된 \
                    세션 배열을 그대로 `201`로 돌려준다 — 재제출 본문의 다른 필드는 무시된다. \
                    시작 시각이 기존 세션(자정 분할 조각 포함)과 겹치는 별개 제출이 동시에 들어오면 `409`로 거절된다. \
                    자동 확정본(잠정 기록)이 이미 저장돼 있으면 재제출이 아니라 대체가 일어난다 — 진행중 세션 스냅샷 API 참고 (ADR-0014).""")
    @ApiResponse(
            responseCode = "201",
            description = "저장 성공 — studySec/focusSec/focusRate/statDate를 포함한 세션 배열 (자정 분할 시 2개)")
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
                                @ExampleObject(
                                        name = "총 공부 시간 범위 위반",
                                        value = "{\"message\": \"총 공부 시간은 0 이상, 일시정지를 제외한 세션 시간 이하여야 합니다\"}"),
                                @ExampleObject(
                                        name = "순공 시간 범위 위반",
                                        value = "{\"message\": \"순공 시간은 0 이상, 총 공부 시간 이하여야 합니다\"}"),
                                @ExampleObject(name = "이벤트 겹침", value = "{\"message\": \"이벤트 구간이 서로 겹칠 수 없습니다\"}"),
                                @ExampleObject(name = "이벤트가 세션 밖", value = "{\"message\": \"이벤트는 세션 구간 안에 있어야 합니다\"}"),
                                @ExampleObject(name = "필수 값 누락", value = "{\"message\": \"userId: 널이어서는 안됩니다\"}")
                            }))
    @ApiResponse(
            responseCode = "409",
            description = "시작 시각 충돌 — 같은 시각에 시작한 세션이 이미 저장돼 있다 (동시 재전송 레이스 등). 같은 키로 다시 제출하면 저장된 결과를 받는다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            name = "시작 시각 충돌",
                                            value = "{\"message\": \"이미 같은 시각에 시작한 세션이 저장되어 있습니다\"}")))
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
    public List<StudySessionResponse> create(@Valid @RequestBody StudySessionCreateRequest request) {
        try {
            return studySessionService.create(request.userId(), request);
        } catch (DuplicateSessionException | ObjectOptimisticLockingFailureException e) {
            // 자동 확정 스케줄러와의 유니크 레이스에서 진 경우 — 방금 확정된 auto_finalized(잠정) 행을
            // 그대로 돌려주면 최종 제출이 영구 유실되므로, create를 1회 재시도해 대체 로직을 태운다
            // (기존 클라본이면 멱등 반환, 전부 auto_finalized면 대체, 재충돌이면 아래 폴백) (ADR-0014).
            try {
                return studySessionService.create(request.userId(), request);
            } catch (DuplicateSessionException retryEx) {
                List<StudySessionResponse> concurrent =
                        studySessionService.findExistingSubmission(request.userId(), request.startedAt());
                if (!concurrent.isEmpty()) {
                    return concurrent;
                }
                throw retryEx;
            }
        }
    }
}
