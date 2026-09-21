package project.study.studysession.service;

import java.util.List;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.entity.StudySessionSubjectTime;

/**
 * 세션에 함께 붙는 부가 기록 — 과목별 시간은 조각에 비례 배분되고(ADR-0021), 완료 할 일은 완료 시각이 속한 조각에
 * 붙는다(ADR-0022). 검증·분할 진입점의 파라미터 수를 묶어 두려고 한 값으로 넘긴다.
 */
record SessionAttachments(List<StudySessionSubjectTime> subjectTimes, List<CompletedTask> completedTasks) {

    static final SessionAttachments NONE = new SessionAttachments(List.of(), List.of());
}
