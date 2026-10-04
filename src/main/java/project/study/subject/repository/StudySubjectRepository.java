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

    // 같은 이름으로 다시 만들 때 되살릴 과목 — 여러 번 지웠으면 가장 최근에 지운 것
    // 지운 과목용 인덱스는 두지 않았다 — 과목 테이블이 커져 생성이 느려지면 (user_id, name) 부분 인덱스를 추가한다
    Optional<StudySubject> findFirstByUserIdAndNameAndDeletedAtIsNotNullOrderByDeletedAtDescIdDesc(
            Long userId, String name);

    // 세션 제출의 소유 검증용 — 세션 중 지운 과목의 시간도 받아야 하므로 삭제 여부를 보지 않는다
    List<StudySubject> findByIdInAndUserId(Collection<Long> ids, Long userId);
}
