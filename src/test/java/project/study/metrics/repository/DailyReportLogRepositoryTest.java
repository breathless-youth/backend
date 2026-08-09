package project.study.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

// @Transactional: 테스트가 넣은 행을 롤백해 다른 테스트에 새어나가지 않게 한다.
// 같은 트랜잭션 안에서도 on conflict는 직전 삽입행을 보므로 두 번째 claim은 0을 반환한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DailyReportLogRepositoryTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2020, 1, 10);

    @Autowired
    private DailyReportLogRepository dailyReportLogRepository;

    @Test
    void 처음_선점하면_1을_반환한다() {
        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isEqualTo(1);
    }

    @Test
    void 같은_날짜를_다시_선점하면_0을_반환한다() {
        dailyReportLogRepository.claim(REPORT_DATE);

        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isZero();
    }

    @Test
    void 다른_날짜는_각각_선점된다() {
        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isEqualTo(1);
        assertThat(dailyReportLogRepository.claim(REPORT_DATE.plusDays(1))).isEqualTo(1);
    }
}
