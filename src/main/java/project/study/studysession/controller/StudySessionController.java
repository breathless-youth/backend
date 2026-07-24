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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
import project.study.studysession.dto.StudySessionStreakResponse;
import project.study.studysession.service.StudySessionService;

@Tag(name = "StudySession", description = "공부 세션 기록 API 모음 — 방 퇴장 시 세션 전체를 한 번에 제출받아 검증·계산·저장한다 (ADR-0003)")
@RestController
@RequestMapping("/api/study-sessions")
@RequiredArgsConstructor
public class StudySessionController {

    private final StudySessionService studySessionService;

    @Operation(summary = "공부 세션 제출", description = """
                    공부를 마칠 때(방 퇴장 시) 세션 전체를 한 번에 제출한다. \
                    서버는 세션을 실시간으로 추적하지 않는다 — 앱에서 제출한 \
                    시작/종료 시각, 앱이 계산한 순공 시간(`focusSec`), \
                    온디바이스에서 제공한 **비공부 상태 이벤트**(PHONE·DEVICE·AWAY·STOP) 목록이 데이터의 전부다.

                    서버가 하는 일은 세 가지다.
                    1. **검증** — 아래 규칙을 하나라도 어기면 `400`으로 거절한다.
                    2. **계산** — 총 시간(`sessionSec` = 종료 - 시작)과 통계 귀속 날짜(`statDate`, \
                    한국 시간 기준 시작 날짜)를 서버가 계산한다. \
                    순공 시간(`focusSec`)은 앱이 제출한 값을 그대로 저장한다 — 자정 분할 시에는 조각 길이에 비례해 배분한다(합계 보존).
                    3. **저장** — 세션과 이벤트를 저장하고 결과를 돌려준다.

                    **검증 규칙**
                    - 종료 시각은 시작 시각 이후여야 한다 (세션·이벤트 모두)
                    - 세션은 24시간을 초과할 수 없다
                    - 세션 종료 시각은 미래일 수 없다 (기기 시계 오차 5분까지 허용)
                    - 순공 시간(`focusSec`)은 0 이상, 세션 총 시간 이하여야 한다
                    - 이벤트는 세션 구간 안에 있어야 하고, 서로 겹칠 수 없다 (끝과 시작이 맞닿는 것은 허용)
                    - 이벤트는 순서가 뒤섞여 와도 된다 — 서버가 시작 시각 기준으로 정렬한다
                    - 검증은 자정 분할 전의 원본 제출 기준이다 (예: 자정을 걸친 25시간 세션은 거절)

                    **자정 분할 — 응답은 항상 배열이다.** 세션이 한국 시간 자정(00:00)을 넘으면 \
                    날짜별 세션으로 나뉘어 저장된다. 예: 24일 23시~25일 01시 제출 → 24일 세션(23~00시)과 \
                    25일 세션(00~01시) 2개가 만들어지고 응답 배열에 둘 다 담긴다. 요청의 `focusSec`도 \
                    조각 길이에 비례해 나뉘어 저장된다(조각 합 = 요청값). 자정에 걸친 이벤트도 \
                    시각 기준으로 나뉘어 각 세션에 귀속된다 (각 날짜 조회의 `eventCounts`에 1건씩 잡힌다). \
                    자정을 넘지 않으면 요소가 1개인 배열이 내려온다. \
                    정확히 자정에 시작하거나 끝나는 세션은 분할되지 않는다.""")
    @ApiResponse(
            responseCode = "201",
            description = "저장 성공 — sessionSec/focusSec/focusRate/statDate를 포함한 세션 배열 (자정 분할 시 2개)")
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
                                        name = "순공 시간 범위 위반",
                                        value = "{\"message\": \"순공 시간은 0 이상, 세션 총 시간 이하여야 합니다\"}"),
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

    @Operation(summary = "공부 세션 목록 조회 (하루)", description = """
                    한 유저의 세션을 통계 날짜(`statDate`) 기준으로 하루 단위로 조회한다 (일별 기록 화면). \
                    기간(from~to) 조회는 추후 별도 API로 제공한다.

                    응답은 세션 목록과 그날 전체 통계를 함께 담은 객체다.
                    - `sessions` — 세션 요약 목록, 시작 시각 내림차순. 이벤트 목록은 미포함
                    - `sessionCount` — 조회된 세션 개수 (자정 분할 세션은 각각 1개로 센다)
                    - `totalSessionSec` / `totalFocusSec` — 그날 총 시간·순공 시간 합계(초)
                    - `focusRate` — 그날 전체 집중률(%). 세션별 집중률의 평균이 아니라 합계 기준으로 계산한다
                    - `eventCounts` — 상태별 이벤트 발생 건수. 없는 상태도 0으로 내려간다

                    존재하지 않는 userId거나 기록 없는 날짜면 sessions는 빈 배열, sessionCount는 0, \
                    합계는 0, focusRate는 0.0, eventCounts는 모든 상태 0인 객체가 내려온다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 세션 목록 + 세션 개수 + 그날 합계·집중률·상태별 이벤트 건수")
    @GetMapping
    public StudySessionListResponse list(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)") @RequestParam Long userId,
            @Parameter(description = "조회할 날짜 (ISO-8601, 예: 2026-07-24) — statDate 기준")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return studySessionService.list(userId, date);
    }

    @Operation(summary = "연속 공부일(스트릭) 조회", description = """
                    통계 날짜(`statDate`) 기준으로 세션이 하루라도 있으면 그날은 공부한 날로 친다. \
                    유저에 저장된 값이 아니라 세션 이력에서 매번 계산한다.

                    - `streak` — 오늘(오늘 기록이 아직 없으면 어제)부터 거꾸로 이어진 연속 공부일. \
                    오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다 — 오늘이 지나기 전엔 끊긴 게 아니다. \
                    어제도 오늘도 기록이 없으면 0
                    - `maxStreak` — 전체 이력에서 가장 길었던 연속 공부일

                    기록이 없거나 존재하지 않는 userId면 둘 다 0이다 (목록 조회와 같은 계약).""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 현재 스트릭과 역대 최장 스트릭")
    @GetMapping("/streak")
    public StudySessionStreakResponse streak(
            @Parameter(description = "조회할 유저 ID (POST /api/users 로 발급받은 값)") @RequestParam Long userId) {
        return studySessionService.streak(userId);
    }
}
