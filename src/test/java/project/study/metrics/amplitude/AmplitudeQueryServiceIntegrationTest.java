package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

/** 리포트가 읽는 Amplitude 적재 데이터 (BY-904). 적재 테스트가 테이블을 비우므로 여기선 트랜잭션 롤백으로 정리한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AmplitudeQueryServiceIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2020, 4, 1);
    private static final long USER = 9_000_001L;
    private static final long OTHER = 9_000_002L;

    @Autowired
    private AmplitudeQueryService amplitudeQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertCount(long userId, LocalDate date, String key, int count) {
        jdbcTemplate.update(
                "insert into amplitude_daily_event (user_id, event_date, event_key, count) values (?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(date),
                key,
                count);
    }

    @Test
    void 기간과_키로_거른_일별_횟수를_돌려준다() {
        insertCount(USER, DAY, "app_launched", 3);
        insertCount(USER, DAY, "Page Viewed", 9);
        insertCount(USER, DAY.plusDays(5), "app_launched", 1);

        List<AmplitudeDailyCount> counts =
                amplitudeQueryService.findCounts(DAY, DAY.plusDays(1), Set.of("app_launched"));

        assertThat(counts)
                .filteredOn(count -> count.userId() == USER)
                .containsExactly(new AmplitudeDailyCount(USER, DAY, "app_launched", 3));
        assertThat(amplitudeQueryService.findCounts(DAY, DAY, Set.of())).isEmpty();
    }

    @Test
    void 요청한_유저의_속성만_돌려준다() {
        jdbcTemplate.update(
                "insert into amplitude_user_attribute (user_id, utm_source, ad_click, os_name, first_seen_at, last_seen_at) "
                        + "values (?, 'instagram', true, 'ios', now(), now()), (?, null, false, null, now(), now())",
                USER,
                OTHER);

        assertThat(amplitudeQueryService.findAttributes(List.of(USER)))
                .containsExactlyEntriesOf(java.util.Map.of(USER, new AmplitudeAttribute("instagram", true, "ios")));
        assertThat(amplitudeQueryService.findAttributes(List.of())).isEmpty();
    }

    @Test
    void 처리_기록이_있는_날만_수집일이다() {
        jdbcTemplate.update(
                "insert into amplitude_export_window (window_date, event_count, skipped) values (?, 1, 0)",
                java.sql.Date.valueOf(DAY));

        assertThat(amplitudeQueryService.collectedDates(DAY.minusDays(1), DAY.plusDays(1)))
                .containsExactly(DAY);
    }
}
