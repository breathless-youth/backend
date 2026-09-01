package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.dto.ActiveSessionSnapshotResponse;
import project.study.studysession.dto.SessionRecoveryResponse;
import project.study.studysession.service.ActiveStudySessionService;
import project.study.studysession.service.SessionRecoveryService;

@Tag(
        name = "StudySession",
        description = "공부 세션 기록 API 모음 — 방 퇴장 시 세션 전체를 한 번에 제출받아 검증·계산·저장한다 (ADR-0003). 통계 조회는 StudySessionStats 참고")
@RestController
@RequestMapping("/api/study-sessions")
@RequiredArgsConstructor
public class ActiveStudySessionController {

    private final ActiveStudySessionService activeStudySessionService;
    private final SessionRecoveryService sessionRecoveryService;

    @Operation(summary = "진행중 세션 스냅샷 보고", description = """
				공부 중 30초마다 진행중 세션의 누적 스냅샷을 보고한다 (BY-447, ADR-0014). \
				앱이 최종 제출 없이 죽어도 서버가 마지막 스냅샷 기준으로 세션을 자동 확정해 유실을 막는다 — \
				최악 유실은 마지막 스냅샷 이후 30초.

				**스냅샷은 누적값이다.** 매 보고가 "지금까지의 studySec/focusSec + 비공부 이벤트 전체"를 담고 \
				서버 draft를 통째로 덮어쓴다(멱등 PUT). 진행 중인 이벤트는 reportedAt에서 닫아서 보낸다 — \
				다음 스냅샷이 덮어쓰므로 자연히 갱신된다. 저장된 스냅샷보다 reportedAt이 과거인 보고는 조용히 무시된다.

				**검증은 최종 제출과 같은 규칙이다** — endedAt 자리에 reportedAt을 두고 시각 순서·24시간 한도·\
				미래 금지(5분 허용)·studySec/focusSec 범위·이벤트 겹침/구간 검증을 모두 적용한다.

				**자동 확정** — 마지막 보고가 서버 기준 5분 넘게 끊기면 스케줄러가 reportedAt을 종료 시각으로 \
				세션을 확정한다(자정 분할 포함, 길이 무관). 자동 확정본은 잠정 기록이라, 이후 같은 startedAt으로 \
				최종 제출이 도착하면 그것으로 대체된다. 정상 최종 제출 시 draft는 함께 삭제되므로 \
				앱은 확정 여부를 신경 쓸 필요 없이 늘 하던 대로 제출하면 된다.""")
    @ApiResponse(responseCode = "204", description = "스냅샷 반영 완료 (역순 도착으로 무시된 경우 포함)")
    @ApiResponse(
            responseCode = "400",
            description = "검증 실패 — 최종 제출과 같은 시간·범위·이벤트 규칙",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            name = "기준 시점이 시작보다 빠름",
                                            value = "{\"message\": \"세션 종료 시각은 시작 시각 이후여야 합니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 userId",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(name = "유저 없음", value = "{\"message\": \"존재하지 않는 사용자입니다: 999\"}")))
    @PutMapping("/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody ActiveSessionSnapshotRequest request) {
        activeStudySessionService.reportSnapshot(request);
    }

    @Operation(summary = "진행중 세션 복구 조회", description = """
					백그라운드에서 돌아왔는데 로컬 기록이 없을 때 호출한다 (BY-448). 서버가 보관 중인 진행 스냅샷 중 \
					마지막 보고가 가장 최근인 1건을 돌려준다 — 스냅샷 보고 요청과 같은 모양이라 그대로 상태를 \
					복원하면 된다.

					받은 뒤 선택지는 둘 다 유효하다. **이어서 공부**: 받은 값으로 타이머를 복원하고 같은 startedAt으로 \
					스냅샷 보고를 재개한다(앱이 죽어있던 공백은 studySec에 누적되지 않았으므로 기록은 정확하다). \
					**즉시 마감**: endedAt = reportedAt으로 최종 제출하고 세션을 끝낸다.

					조회 직후 서버가 그 세션을 자동 확정하는 극단적 타이밍이 겹쳐도 받은 데이터로 이어서 \
					보고·제출하면 대체 정책이 더 완전한 기록으로 수렴시키므로 클라이언트가 따로 처리할 것은 없다.""")
    @ApiResponse(responseCode = "200", description = "진행중 스냅샷 — startedAt/reportedAt/studySec/focusSec/events")
    @ApiResponse(
            responseCode = "404",
            description = "진행중 세션 없음 — 이미 자동 확정됐거나 애초에 없던 경우. 확정본은 통계 조회에 이미 반영돼 있으므로 새로 시작하면 된다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "진행중 세션 없음", value = "{\"message\": \"진행중인 세션이 없습니다\"}")))
    @GetMapping("/active")
    public ActiveSessionSnapshotResponse restore(
            @Parameter(description = "세션 주인의 유저 ID", example = "1") @RequestParam Long userId) {
        return activeStudySessionService.findLatestSnapshot(userId);
    }

    @Operation(summary = "세션 복구 판별·확인", description = """
					비정상 종료(크래시/강제종료) 후 홈에 재진입했을 때 호출한다 (BY-455). 이어하기용 복구 조회\
					(`GET /active`)와 목적이 다르다 — 이건 **이어하지 않고, 자동 저장된 마지막 세션을 확인만** 하는 경로다.

					동작:
					1. **아직 정리되지 않은 진행중 세션(draft)이 있으면** 지금 확정하고 그 요약을 반환한다.
					2. draft가 없고 **가장 최근 세션이 자동 확정본이며 아직 확인되지 않았으면** 그 요약을 반환한다.
					3. 둘 다 아니면(마지막이 정상 종료 세션이거나, 이미 확인했거나, 기록이 없으면) `404`.

					**한 번 반환한 세션은 확인 처리되어 다음 호출부터 재노출되지 않는다.** 자정을 걸친 세션은\
					조각들을 한 건으로 집계해 내려준다(시작=최소, 종료=최대, 시간=합).

					**주의:** 사용자가 '이어하기 아님'으로 판단했을 때만 호출해야 한다 — 무조건 호출하면 이어하려던\
					draft까지 확정된다.""")
    @ApiResponse(responseCode = "200", description = "복구 대상 요약 — statDate/startedAt/endedAt/studySec/focusSec")
    @ApiResponse(
            responseCode = "404",
            description = "복구할 세션 없음 — 마지막이 정상 종료됐거나, 이미 확인했거나, 기록이 없다",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "복구 대상 없음", value = "{\"message\": \"복구할 세션이 없습니다\"}")))
    @PostMapping("/recovery")
    public SessionRecoveryResponse recover(
            @Parameter(description = "세션 주인의 유저 ID", example = "1") @RequestParam Long userId) {
        return sessionRecoveryService.recover(userId);
    }
}
