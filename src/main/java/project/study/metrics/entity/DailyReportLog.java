package project.study.metrics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일일 지표 리포트 발송 이력. 삽입은 네이티브 쿼리(on conflict do nothing)로만 하므로
 * 이 엔티티는 스키마 검증(ddl-auto=validate)과 조회 타입 용도다.
 */
@Table(name = "daily_report_log")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReportLog {

    @Id
    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}
