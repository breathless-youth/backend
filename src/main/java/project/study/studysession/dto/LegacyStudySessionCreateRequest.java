package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 구 앱(v1.2.x)의 세션 제출 요청 — 본문 userId + {@link StudySessionCreateRequest}와 같은 필드. 필드 의미·검증은
 * 토큰 계약 DTO의 Swagger 설명을 따른다. contract 시 삭제 (ADR-0020).
 */
public record LegacyStudySessionCreateRequest(
        @Schema(description = "세션 주인의 유저 ID", example = "1") @NotNull
        Long userId,

        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @NotNull Integer studySec,
        @NotNull Integer focusSec,
        @NotNull @Valid List<StatusEventRequest> events) {

    public StudySessionCreateRequest toRequest() {
        return new StudySessionCreateRequest(startedAt, endedAt, studySec, focusSec, events);
    }
}
