package project.study.studysession.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 과목 누적 시간 집계 — 저장은 StudySession의 cascade가 하고, 여기서는 구간 행의 파생값을 합산만 한다 (ADR-0023). */
public interface StudySessionSubjectSegmentRepository extends JpaRepository<StudySessionSubjectSegment, Long> {

    // 과목 누적 — 그 과목 구간 전부의 총공부·순공 합
    @Query("""
            select new project.study.studysession.dto.SubjectTimeSum(s.subjectId, sum(s.studySec), sum(s.focusSec))
            from StudySessionSubjectSegment s
            where s.subjectId in :subjectIds
            group by s.subjectId""")
    List<SubjectTimeSum> sumBySubjectIds(@Param("subjectIds") Collection<Long> subjectIds);
}
