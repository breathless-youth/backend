package project.study.studysession.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.StudySessionSubjectTime;

/** 과목 누적 시간 집계 — 저장은 StudySession의 cascade가 하고, 여기서는 합산만 한다. */
public interface StudySessionSubjectTimeRepository extends JpaRepository<StudySessionSubjectTime, Long> {

    // 과목 누적 — 그 과목에서 잰 시간 전부의 합
    @Query("""
            select new project.study.studysession.dto.SubjectTimeSum(t.subjectId, sum(t.studySec), sum(t.focusSec))
            from StudySessionSubjectTime t
            where t.subjectId in :subjectIds
            group by t.subjectId""")
    List<SubjectTimeSum> sumBySubjectIds(@Param("subjectIds") Collection<Long> subjectIds);
}
