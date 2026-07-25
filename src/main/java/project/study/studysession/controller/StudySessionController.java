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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionResponse;
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
                    온디바이스에서 제공한 **비공부 상태 이벤트**(PHONE·DEVICE·AWAY·STOP) 목록이 데이터의 전부다. \
                    세션(방 입장~퇴장) 안에 총 공부시간 타이머가 있고, 그 안에 다시 순공시간 타이머가 있는 구조다 — \
                    `STOP`(일시정지)은 총공부·순공 타이머를 모두 멈추고, 나머지(PHONE/DEVICE/AWAY)는 순공 타이머만 멈춘다.

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
                    - 총 공부 시간(`studySec`)은 0 이상, (세션 총 시간 − `STOP` 이벤트 시간 합) 이하여야 한다
                    - 순공 시간(`focusSec`)은 0 이상, 총 공부 시간(`studySec`) 이하여야 한다
                    - 이벤트는 세션 구간 안에 있어야 하고, 서로 겹칠 수 없다 (끝과 시작이 맞닿는 것은 허용)
                    - 이벤트는 순서가 뒤섞여 와도 된다 — 서버가 시작 시각 기준으로 정렬한다
                    - 검증은 자정 분할 전의 원본 제출 기준이다 (예: 자정을 걸친 25시간 세션은 거절)

                    **자정 분할 — 응답은 항상 배열이다.** 세션이 한국 시간 자정(00:00)을 넘으면 \
                    날짜별 세션으로 나뉘어 저장된다. 예: 24일 23시~25일 01시 제출 → 24일 세션(23~00시)과 \
                    25일 세션(00~01시) 2개가 만들어지고 응답 배열에 둘 다 담긴다. 요청의 `studySec`은 STOP을 제외한 \
                    조각 길이 비율로, `focusSec`은 전체 이벤트를 제외한 조각 길이 비율로 나뉘어 저장된다(각각 조각 합 = 요청값). \
                    자정에 걸친 이벤트도 시각 기준으로 나뉘어 각 세션에 귀속된다 (각 날짜 조회의 `eventCounts`에 1건씩 잡힌다). \
                    자정을 넘지 않으면 요소가 1개인 배열이 내려온다. \
                    정확히 자정에 시작하거나 끝나는 세션은 분할되지 않는다.""")
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
        return studySessionService.create(request);
    }
}
