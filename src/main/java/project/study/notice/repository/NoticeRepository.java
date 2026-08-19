package project.study.notice.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.notice.entity.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /**
     * 노출 기간 안의 공지만, 최신 시작순으로. ends_at이 없는 공지는 무기한 노출이다.
     * 클라이언트가 목록 첫 항목만 표시하므로 같은 starts_at의 순서가 흔들리면 안 된다 — id로 동률을 깬다.
     */
    @Query("""
            select n from Notice n
            where n.startsAt <= :now and (n.endsAt is null or :now < n.endsAt)
            order by n.startsAt desc, n.id desc
            """)
    List<Notice> findActive(@Param("now") Instant now);
}
