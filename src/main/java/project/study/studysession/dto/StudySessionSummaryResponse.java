package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StudySession;

public record StudySessionSummaryResponse(
        @Schema(description = "세션 ID", example = "10") Long id,

        @Schema(
                description = "통계 귀속 날짜 — 한국 시간 기준 시작 날짜 (자정을 넘는 세션은 날짜별로 분할 저장되어 각각의 statDate를 가진다)",
                example = "2026-07-24")
        LocalDate statDate,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z")
        Instant startedAt,

        @Schema(description = "세션 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T03:00:00Z")
        Instant endedAt,

        @Schema(description = "총 공부 시간(초) — 앱이 잰 '총 공부 타이머' 값", example = "6600")
        Integer studySec,

        @Schema(description = "순공 시간(초) — 앱이 잰 '순공 타이머'(실제 집중 시간) 값", example = "6000")
        Integer focusSec,

        @Schema(description = "집중률(%) — focusSec ÷ studySec × 100, 소수 1자리 반올림", example = "90.9")
        Double focusRate,

        @Schema(
                description = "이 세션에서 발생한 상태별 이벤트 건수 — 이벤트가 없던 상태는 0으로 내려간다(키 누락 없음)",
                example = "{\"PHONE\": 2, \"DEVICE\": 0, \"AWAY\": 1, \"SLEEP\": 1, \"PAUSE\": 0}")
        Map<EventStatus, Long> eventCounts,

        @Schema(description = "비공부 상태 이벤트 원본(시각) — 시작 시각 오름차순. 타임테이블의 휴식 칸을 그린다 (BY-734)")
        List<StatusEventResponse> events,

        @Schema(description = "과목 구간 — 시작 시각 오름차순, 서버가 계산한 studySec·focusSec 포함. 타임테이블의 과목 칸을 그린다. 없으면 []")
        List<SubjectSegmentResponse> subjectSegments,

        @Schema(description = "이 세션에서 완료한 할 일 — 이름 포함, id 오름차순, 지운 할 일도 남는다. 없으면 []")
        List<CompletedTaskResponse> completedTasks) {
    // focusRate/eventCounts 계산은 서비스가 담당한다 — DTO는 값을 옮겨 담기만 한다. 과목 이름·색은 목록 응답의 subjects[]에 한 번 실린다
    public static StudySessionSummaryResponse from(
            StudySession session, double focusRate, Map<EventStatus, Long> eventCounts, SubjectLookup lookup) {
        return new StudySessionSummaryResponse(
                session.getId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getStudySec(),
                session.getFocusSec(),
                focusRate,
                eventCounts,
                session.getEvents().stream().map(StatusEventResponse::from).toList(),
                session.getSubjectSegments().stream()
                        .map(SubjectSegmentResponse::from)
                        .toList(),
                lookup.tasksFor(session.getCompletedTaskIds()));
    }
}
