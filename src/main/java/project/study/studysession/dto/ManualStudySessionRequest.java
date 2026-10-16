package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record ManualStudySessionRequest(
        @Schema(description = "시작 시각 (UTC, ISO-8601) — 초 이하는 버리고 분 단위로 저장한다", example = "2026-10-15T05:00:00Z") @NotNull
        Instant startedAt,

        @Schema(
                description = "종료 시각 (UTC, ISO-8601) — 분 단위로 버린 뒤 시작보다 1분 이상 뒤, 24시간 이내, 서버 지금 이전이어야 한다",
                example = "2026-10-15T06:30:00Z")
        @NotNull
        Instant endedAt,

        @Schema(description = "과목 ID(선택) — 토큰 유저의 과목. 지운 과목은 수정에서 묶음의 지금 과목을 다시 보낼 때만 받는다", example = "3")
        Long subjectId) {

    /** 소유 검증(StudySubjectService.assertOwned)에 넘길 목록. */
    public List<Long> subjectIds() {
        return subjectId == null ? List.of() : List.of(subjectId);
    }
}
