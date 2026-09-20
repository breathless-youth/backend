package project.study.subject.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.subject.entity.StudySubject;

public interface StudySubjectRepository extends JpaRepository<StudySubject, Long> {

    // 목록·상한 계산은 살아있는 과목만 본다
    List<StudySubject> findByUserIdAndDeletedAtIsNullOrderByIdAsc(Long userId);

    Optional<StudySubject> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    long countByUserIdAndDeletedAtIsNull(Long userId);

    // 세션 제출의 소유 검증용 — 세션 중 지운 과목의 시간도 받아야 하므로 삭제 여부를 보지 않는다
    List<StudySubject> findByIdInAndUserId(Collection<Long> ids, Long userId);
}
