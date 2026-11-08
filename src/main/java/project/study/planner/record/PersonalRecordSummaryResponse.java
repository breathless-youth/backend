package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.studysession.dto.CompletedTaskResponse;
import project.study.studysession.dto.StatusEventResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StudySessionSource;

@Schema(description = "개인 수정본의 자정 날짜별 표시 항목. 숫자 세션 ID 대신 displayKey로 구분한다")
public record PersonalRecordSummaryResponse(
        @Schema(description = "날짜별 표시 조각의 안정적인 문자열 키") String displayKey,

        @Schema(description = "기록 배치 편집에 사용하는 원본 묶음/개인 기록 키")
        String recordKey,

        @Schema(
                description = "연결을 확인할 수 있는 실제 원본 세션 ID. 새 개인 직접 입력과 키 변경으로 원본 연결을 잃은 이전 수동 기록은 null이며 표시 키로 쓰지 않는다",
                nullable = true)
        Long originalSessionId,

        @Schema(description = "원본은 false, 개인 수정본과 새 개인 직접 입력은 true. 과목 유무와 독립적이다")
        boolean edited,

        @Schema(description = "자정 기준 통계 귀속 날짜") LocalDate statDate,
        Instant startedAt,
        Instant endedAt,
        @Schema(description = "현재 개인 기록의 조회 창 절삭 전 전체 시작") Instant fullStartedAt,
        @Schema(description = "현재 개인 기록의 조회 창 절삭 전 전체 종료") Instant fullEndedAt,
        long studySec,
        long focusSec,
        double focusRate,

        @Schema(description = "표시 구간에 겹친 이벤트 수. 모든 상태 키를 포함한다")
        Map<EventStatus, Long> eventCounts,

        @Schema(description = "개인 기록 표시 구간에 절삭한 원본 비공부 이벤트") List<StatusEventResponse> events,
        List<PlannerRecordSegment> subjectSegments,

        @Schema(description = "연결을 확인한 원본의 완료한 할 일 참조. 시간 변경 시 첫 표시 조각에 한 번만 제공하며 원본 연결을 잃은 이전 수동 기록은 복원을 추측하지 않는다")
        List<CompletedTaskResponse> completedTasks,

        StudySessionSource source,

        @Schema(description = "원본 제출 묶음 시각. 원본이 없으면 개인 기록 전체 시작")
        Instant submissionStartedAt) {}
