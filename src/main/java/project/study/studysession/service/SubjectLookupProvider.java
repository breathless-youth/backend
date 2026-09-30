package project.study.studysession.service;

import java.util.Collection;
import project.study.studysession.dto.SubjectLookup;

/**
 * 세션 응답에 실을 과목·할 일 이름 조회 (BY-734) — 과목 도메인(StudySubjectService)이 구현한다.
 * 세션 도메인은 이 인터페이스만 알아 과목 엔티티에 의존하지 않고, 단위테스트는 람다로 대신한다 (ADR-0021 §6).
 */
public interface SubjectLookupProvider {

    /** 지운 과목·할 일도 돌려준다. 할 일의 과목이 subjectIds에 없어도 함께 실린다. 둘 다 비면 EMPTY. */
    SubjectLookup lookup(Collection<Long> subjectIds, Collection<Long> taskIds);
}
