package project.study.studysession.dto;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;

/**
 * 세션 응답에 붙일 과목·할 일 이름 조회 결과 (BY-734) — 과목 도메인이 만들어 세션 도메인에 넘기는 값이라
 * {@link CompletedTask}처럼 세션 dto에 둔다(의존 방향은 subject → studysession.dto 하나로 유지, ADR-0021 §6).
 */
public record SubjectLookup(Map<Long, SubjectRef> subjects, Map<Long, CompletedTaskResponse> tasks) {

    public static final SubjectLookup EMPTY = new SubjectLookup(Map.of(), Map.of());

    /** id 오름차순. 모르는 id는 건너뛴다(FK가 있어 실제로는 없다). */
    public List<SubjectRef> subjectsFor(Collection<Long> ids) {
        return ids.stream()
                .distinct()
                .sorted()
                .map(subjects::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** id 오름차순 — 기존 completedTaskIds 계약(오름차순)을 잇는다. */
    public List<CompletedTaskResponse> tasksFor(Collection<Long> ids) {
        return ids.stream()
                .distinct()
                .sorted()
                .map(tasks::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 세션이 참조한 과목 — 구간의 과목과 완료 할 일의 과목을 합쳐 id 오름차순. */
    public List<SubjectRef> subjectsReferencedBy(StudySession session) {
        Set<Long> ids = new HashSet<>();
        session.getSubjectSegments().stream()
                .map(StudySessionSubjectSegment::getSubjectId)
                .forEach(ids::add);
        tasksFor(session.getCompletedTaskIds()).stream()
                .map(CompletedTaskResponse::subjectId)
                .forEach(ids::add);
        return subjectsFor(ids);
    }

    /** 조회된 과목 전부, id 오름차순 — 일간 목록의 응답 최상위 subjects[]. */
    public List<SubjectRef> allSubjects() {
        return subjectsFor(subjects.keySet());
    }
}
