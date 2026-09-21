package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.studysession.entity.StudySession;

public record StudySessionResponse(
        @Schema(description = "세션 ID", example = "10") Long id,
        @Schema(description = "세션 주인의 유저 ID", example = "1") Long userId,

        @Schema(description = "통계 귀속 날짜 — 한국 시간 기준 (자정을 넘는 제출은 날짜별 세션으로 분할되어 각각 귀속)", example = "2026-07-24")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 공부 시간(초) — 앱이 제출한 값 그대로 (자정 분할 시 조각 길이에 비례해 배분)", example = "6600")
        Integer studySec,

        @Schema(description = "순공 시간(초) — 앱이 제출한 값 그대로 (자정 분할 시 조각 길이에 비례해 배분)", example = "6000")
        Integer focusSec,

        @Schema(description = "집중률(%) — focusSec ÷ studySec × 100, 소수 1자리 반올림", example = "90.9")
        Double focusRate,

        @Schema(description = "비공부 상태 이벤트 목록 — 시작 시각 오름차순, 제출한 이벤트가 자정 분할로 나뉘면 각 세션에 조각으로 귀속된다")
        List<StatusEventResponse> events,

        @Schema(description = "과목·할 일별 시간 — 제출한 subjectTimes가 자정 분할로 나뉘면 각 세션에 조각 몫으로 귀속된다. 없으면 []")
        List<SubjectTimeResponse> subjectTimes,

        @Schema(description = "이 세션(조각)에서 완료한 할 일 ID — 오름차순. 자정 분할이면 완료 시각이 속한 조각에만 실린다. 없으면 []", example = "[12, 15]")
        List<Long> completedTaskIds) {

    // focusRate 계산은 서비스가 담당한다 — DTO는 값을 옮겨 담기만 한다
    public static StudySessionResponse from(StudySession session, double focusRate) {
        return new StudySessionResponse(
                session.getId(),
                session.getUserId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getStudySec(),
                session.getFocusSec(),
                focusRate,
                session.getEvents().stream().map(StatusEventResponse::from).toList(),
                session.getSubjectTimes().stream()
                        .map(SubjectTimeResponse::from)
                        .toList(),
                // Set은 순서가 없으니 정렬해 계약을 안정시킨다
                session.getCompletedTaskIds().stream().sorted().toList());
    }
}
