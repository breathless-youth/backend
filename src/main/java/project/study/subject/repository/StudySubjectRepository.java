package project.study.subject.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.subject.entity.StudySubject;

public interface StudySubjectRepository extends JpaRepository<StudySubject, Long> {

    // 목록·상한·순서·색 계산은 살아있는 과목만 본다 — 저장된 순서, 같으면 id (ADR-0022)
    List<StudySubject> findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long userId);

    Optional<StudySubject> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 세션 제출의 소유 검증용 — 세션 중 지운 과목의 시간도 받아야 하므로 삭제 여부를 보지 않는다
    List<StudySubject> findByIdInAndUserId(Collection<Long> ids, Long userId);
}
