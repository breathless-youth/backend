package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record StudySessionCreateRequest(
        @Schema(description = "세션 주인의 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @NotNull
        Long userId,

        @Schema(
                description = "세션 시작 시각 — 방 입장 시각 (UTC, ISO-8601). userId와 함께 멱등 키로 쓰인다 — "
                        + "같은 유저가 같은 startedAt으로 다시 제출하면(강제종료 후 재전송 등) 중복 저장 없이 "
                        + "이미 저장된 결과를 그대로 돌려받는다",
                example = "2026-07-24T01:00:00Z")
        @NotNull
        Instant startedAt,

        @Schema(
                description = "세션 종료 시각 — 방 퇴장 시각 (UTC, ISO-8601). 시작 이후 10분~24시간 이내여야 하고 "
                        + "미래일 수 없다(기기 시계 오차 5분까지 허용). 10분 미만 세션은 저장되지 않는다",
                example = "2026-07-24T03:00:00Z")
        @NotNull
        Instant endedAt,

        @Schema(
                description = "총 공부 시간(초) — 앱의 '총 공부 타이머'가 잰 값을 그대로 보낸다(서버는 재계산하지 않고 그대로 저장). "
                        + "PAUSE(일시정지) 상태에서는 이 타이머도 함께 멈추므로, PAUSE 구간은 절대 포함하면 안 된다. "
                        + "0 이상, (endedAt − startedAt) − PAUSE 이벤트 시간 합 이하여야 하며 벗어나면 400. "
                        + "자정을 넘는 세션은 두 개로 나뉘어 저장되며 이 값도 조각 길이에 비례해 배분된다",
                example = "6600")
        @NotNull
        Integer studySec,

        @Schema(
                description = "순공 시간(초) — 앱의 '순공 타이머'(온디바이스 AI가 판정한 실제 집중 시간)가 잰 값을 그대로 보낸다. "
                        + "0 이상, studySec 이하여야 하며 벗어나면 400(이벤트 목록으로 재검증하지 않는다). "
                        + "자정을 넘는 세션은 조각 길이에 비례해 배분된다",
                example = "6000")
        @NotNull
        Integer focusSec,

        @Schema(
                description = "세션 중 발생한 비공부 상태 이벤트 목록 — 없으면 빈 배열 []. 각 원소는 status/startedAt/endedAt 3개 필드만 "
                        + "있으면 되고, 구간 길이(duration)는 서버가 startedAt/endedAt으로 직접 계산하므로 별도로 보낼 필요가 없다. "
                        + "순서는 뒤섞여 와도 된다(서버가 시작 시각 기준으로 정렬한다)")
        @NotNull
        @Valid
        List<StatusEventRequest> events) {}
