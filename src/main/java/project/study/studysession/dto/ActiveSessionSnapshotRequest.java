package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.StudySessionSubjectTime;

/** 진행중 세션의 누적 스냅샷 — 30초마다 통째로 보내 서버 draft를 덮어쓴다 (BY-447). */
public record ActiveSessionSnapshotRequest(
        @Schema(
                description =
                        "세션 시작 시각 (UTC, ISO-8601) — 최종 제출의 startedAt과 같은 값이어야 한다. " + "토큰의 userId와 함께 draft의 멱등 키로 쓰인다",
                example = "2026-08-27T01:00:00Z")
        @NotNull
        Instant startedAt,

        @Schema(
                description = "이 스냅샷의 기준 시점(지금 시각, 클라이언트 시계). 세션이 자동 확정되면 이 값이 endedAt이 된다. "
                        + "startedAt 이후·미래 아님(5분 허용) 규칙은 최종 제출의 endedAt과 동일하다. "
                        + "저장된 스냅샷보다 과거면 조용히 무시된다(역순 도착)",
                example = "2026-08-27T01:10:30Z")
        @NotNull
        Instant reportedAt,

        @Schema(description = "지금까지의 누적 총 공부 시간(초) — 최종 제출 studySec과 같은 검증 규칙", example = "600") @NotNull
        Integer studySec,

        @Schema(description = "지금까지의 누적 순공 시간(초) — 0 이상 studySec 이하", example = "540") @NotNull
        Integer focusSec,

        @Schema(description = "지금까지의 비공부 이벤트 전체 — 진행 중인 이벤트는 reportedAt에서 닫아서 보낸다. 없으면 []") @NotNull @Valid
        List<StatusEventRequest> events,

        @Schema(
                description = "지금까지의 과목·할 일별 시간 전체 — 최종 제출의 subjectTimes와 같은 규칙. 진행 중인 항목은 "
                        + "reportedAt 기준 누적값으로 보낸다. 선택 필드라 없거나 []이면 기존과 동일")
        @Valid
        List<SubjectTimeRequest> subjectTimes) {

    /** 선택 필드라 null이면 빈 목록으로 다룬다. */
    public List<SubjectTimeRequest> subjectTimesOrEmpty() {
        return subjectTimes == null ? List.of() : subjectTimes;
    }

    public List<StudySessionSubjectTime> getSubjectTimeList() {
        return subjectTimesOrEmpty().stream().map(SubjectTimeRequest::toEntity).toList();
    }
}
