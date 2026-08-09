package project.study.metrics.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.entity.DailyReportLog;

public interface DailyReportLogRepository extends JpaRepository<DailyReportLog, LocalDate> {

    /**
     * 오늘 리포트를 선점한다. 삽입에 성공하면(1) 이 인스턴스가 발송 주체이고,
     * 0이면 다른 인스턴스가 이미 보냈다는 뜻이다.
     *
     * <p>@Modifying 쿼리는 트랜잭션이 필요한데, 호출자(DailyReportService)가 자기 메서드를
     * 호출하는 방식으로는 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. 그렇다고 발송
     * 전체를 트랜잭션으로 감싸면 Slack HTTP 호출 동안 DB 커넥션을 점유하게 되므로,
     * 선점만 독립 트랜잭션으로 처리한다.
     */
    @Modifying
    @Transactional
    @Query(value = """
        insert into daily_report_log (report_date, sent_at)
        values (:reportDate, now())
        on conflict (report_date) do nothing
        """, nativeQuery = true)
    int claim(@Param("reportDate") LocalDate reportDate);
}
