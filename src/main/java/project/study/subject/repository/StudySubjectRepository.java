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

    // 과목을 만들 때 지운 과목을 본다 — 같은 이름이면 되살리고, 기록에 남아 있는 색은 피해서 고른다
    // 지운 과목용 인덱스는 두지 않았다 — 과목 테이블이 커져 생성이 느려지면 user_id 부분 인덱스를 추가한다
    List<StudySubject> findByUserIdAndDeletedAtIsNotNull(Long userId);

    // 세션 제출의 소유 검증용 — 세션 중 지운 과목의 시간도 받아야 하므로 삭제 여부를 보지 않는다
    List<StudySubject> findByIdInAndUserId(Collection<Long> ids, Long userId);
}
