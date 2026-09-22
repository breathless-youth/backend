package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 세션 제출·스냅샷·복구에 공통으로 실리는 과목 구간 1건 — 과목을 선택한 채 공부한 [startedAt, endedAt) (ADR-0023).
 * 길이와 과목별 총공부·순공은 서버가 비공부 이벤트와 겹쳐 계산하므로 보내지 않는다 — 이벤트가 시각만 보내는 것과 같은 원칙이다.
 */
public record SubjectSegmentRequest(
        @Schema(description = "과목 ID — 토큰 유저의 과목이어야 한다. 세션 중 지운 과목도 허용된다(기록은 남긴다)", example = "3") @NotNull
        Long subjectId,

        @Schema(description = "구간 시작 시각 (UTC, ISO-8601) — 세션 구간 안이어야 한다", example = "2026-09-22T00:12:00Z") @NotNull
        Instant startedAt,

        @Schema(
                description = "구간 종료 시각 (UTC, ISO-8601) — 시작 이후여야 하고, 다른 구간과 겹칠 수 없다(끝과 시작이 맞닿는 것은 허용). "
                        + "진행 중인 구간은 스냅샷의 reportedAt(최종 제출은 endedAt)에서 닫아서 보낸다",
                example = "2026-09-22T00:41:00Z")
        @NotNull
        Instant endedAt) {

    /**
     * 시각을 마이크로초로 절삭한다 — DB timestamptz 정밀도와 맞추기 위해서다. 스냅샷은 구간을 JSON으로 두고 세션 시작·보고 시각은
     * 컬럼에 두므로, 나노초가 JSON에만 남으면 자동 확정 때 "구간이 세션 밖"으로 오판돼 draft가 폐기된다(Codex 리뷰 P2).
     */
    public SubjectSegmentRequest {
        startedAt = truncate(startedAt);
        endedAt = truncate(endedAt);
    }

    private static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
