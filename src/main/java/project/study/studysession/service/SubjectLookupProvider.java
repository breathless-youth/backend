package project.study.studysession.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import project.study.studysession.dto.DaySubjectResponse;
import project.study.studysession.dto.SubjectLookup;

/**
 * 세션 응답에 실을 과목·할 일 이름 조회 (BY-734) — 과목 도메인(StudySubjectService)이 구현한다.
 * 세션 도메인은 이 인터페이스만 알아 과목 엔티티에 의존하지 않고, 단위테스트는 람다로 대신한다 (ADR-0021 §6).
 */
public interface SubjectLookupProvider {

    /** 지운 과목·할 일도 돌려준다. 할 일의 과목이 subjectIds에 없어도 함께 실린다. 둘 다 비면 EMPTY. */
    SubjectLookup lookup(Collection<Long> subjectIds, Collection<Long> taskIds);

    /**
     * 일간 조회의 subjects — 살아있는 과목 전부(저장된 순서)와 그 뒤에 그날과 관련 있는 지운 과목(id 오름차순),
     * 과목마다 그날(KST 자정 기준)의 할 일 (ADR-0026). referencedSubjectIds는 그날 세션이 참조한 과목이다 —
     * 지운 과목이어도 반드시 실린다. 기본 구현은 빈 목록이다 — 람다로 대신하는 단위테스트가 lookup만 구현해도 되게 한다.
     */
    default List<DaySubjectResponse> daySubjects(Long userId, LocalDate date, Collection<Long> referencedSubjectIds) {
        return List.of();
    }
}
