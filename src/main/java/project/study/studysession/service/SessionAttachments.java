package project.study.studysession.service;

import java.util.List;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.dto.ObservationRequest;
import project.study.studysession.dto.SubjectSegmentRequest;

/**
 * 세션에 함께 붙는 부가 기록 — 과목 구간은 조각에 맞춰 잘라 다시 계산되고(ADR-0023), 완료 할 일은 완료 시각이 속한 조각에
 * 붙는다(ADR-0022). 관찰 기록은 시작 시각이 속한 조각에 붙는다. 검증·분할 진입점의 파라미터 수를 묶어 두려고 한 값으로 넘긴다.
 */
record SessionAttachments(
        List<SubjectSegmentRequest> subjectSegments,
        List<CompletedTask> completedTasks,
        List<ObservationRequest> observations) {
    static final SessionAttachments NONE = new SessionAttachments(List.of(), List.of());

    SessionAttachments(List<SubjectSegmentRequest> subjectSegments, List<CompletedTask> completedTasks) {
        this(subjectSegments, completedTasks, List.of());
    }

    /** 정렬한 과목 구간으로 바꾼 사본 — 나머지 기록은 그대로다. */
    SessionAttachments withSubjectSegments(List<SubjectSegmentRequest> sorted) {
        return new SessionAttachments(sorted, completedTasks, observations);
    }
}
