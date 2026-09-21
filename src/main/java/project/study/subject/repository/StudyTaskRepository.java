package project.study.subject.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.subject.entity.StudyTask;

public interface StudyTaskRepository extends JpaRepository<StudyTask, Long> {

    // 목록에 보이는 할 일 — 미완료 전부 + 완료 시각이 오늘(KST 자정 이후)인 것. 어제 완료한 것은 숨는다(삭제 아님)
    @Query("""
            select t
            from StudyTask t
            where t.subjectId in :subjectIds and t.deletedAt is null
              and (t.doneAt is null or t.doneAt >= :todayStart)
            order by t.id asc""")
    List<StudyTask> findVisible(
            @Param("subjectIds") Collection<Long> subjectIds, @Param("todayStart") Instant todayStart);

    Optional<StudyTask> findByIdAndSubjectIdAndDeletedAtIsNull(Long id, Long subjectId);

    long countBySubjectIdAndDeletedAtIsNull(Long subjectId);

    // 세션 제출의 소유 검증용 — 할 일은 과목을 거쳐 유저에 닿는다. 삭제 여부는 보지 않는다(과목과 같은 이유, ADR-0021)
    @Query("""
            select t
            from StudyTask t
            where t.id in :ids
              and t.subjectId in (select s.id from StudySubject s where s.userId = :userId)""")
    List<StudyTask> findByIdInAndOwner(@Param("ids") Collection<Long> ids, @Param("userId") Long userId);

    // 과목 삭제 시 하위 할 일도 함께 soft delete
    @Modifying
    @Query("update StudyTask t set t.deletedAt = :at where t.subjectId = :subjectId and t.deletedAt is null")
    int softDeleteBySubjectId(@Param("subjectId") Long subjectId, @Param("at") Instant at);
}
