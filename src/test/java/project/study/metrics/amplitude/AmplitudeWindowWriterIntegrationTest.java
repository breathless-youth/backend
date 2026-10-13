package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

/**
 * 테스트 바깥 트랜잭션 없이 돌린다 — 바깥에 {@code @Transactional}을 두면 writer의 트랜잭션 선언이 사라져도 통과해, 창 하나 =
 * 트랜잭션 하나라는 보장의 회귀를 잡지 못한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmplitudeWindowWriterIntegrationTest {

    private static final LocalDate OCT_11 = LocalDate.of(2026, 10, 11);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);
    private static final Instant T1 = Instant.parse("2026-10-12T01:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-12T03:00:00Z");
    private static final Instant T5 = Instant.parse("2026-10-12T05:00:00Z");

    @Autowired
    private AmplitudeWindowWriter writer;

    @Autowired
    private AmplitudeIngestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE amplitude_export_window, amplitude_daily_event, amplitude_user_attribute");
    }

    private static WindowAggregate aggregate(long userId, LocalDate date, String key, int times, Instant seenAt) {
        WindowAggregate aggregate = WindowAggregate.empty();
        for (int i = 0; i < times; i++) {
            aggregate.addEvent(
                    userId, date, List.of(key), UserAttributeObservation.observed(seenAt, null, false, "ios"));
        }
        return aggregate;
    }

    private static WindowAggregate utm(long userId, String... valuesAtTimes) {
        WindowAggregate aggregate = WindowAggregate.empty();
        for (int i = 0; i < valuesAtTimes.length; i += 2) {
            Instant seenAt = Instant.parse(valuesAtTimes[i + 1]);
            aggregate.addEvent(
                    userId,
                    OCT_12,
                    List.of("app_launched"),
                    UserAttributeObservation.observed(seenAt, valuesAtTimes[i], false, null));
        }
        return aggregate;
    }

    private Integer count(long userId, LocalDate date, String key) {
        return jdbcTemplate.queryForObject(
                "select count from amplitude_daily_event where user_id = ? and event_date = ? and event_key = ?",
                Integer.class,
                userId,
                date,
                key);
    }

    private String utmSource(long userId) {
        return jdbcTemplate.queryForObject(
                "select utm_source from amplitude_user_attribute where user_id = ?", String.class, userId);
    }

    @Test
    void 창을_쓰면_처리_기록과_횟수와_속성이_남는다() {
        boolean written = writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        assertThat(written).isTrue();
        assertThat(count(7, OCT_12, "app_launched")).isEqualTo(2);
        assertThat(repository.findProcessedDates(OCT_11, OCT_12)).containsExactly(OCT_12);
        assertThat(jdbcTemplate.queryForObject(
                        "select event_count from amplitude_export_window where window_date = ?", Integer.class, OCT_12))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "select os_name from amplitude_user_attribute where user_id = 7", String.class))
                .isEqualTo("ios");
    }

    @Test
    void 같은_창을_두_번_쓰면_두_번째는_아무것도_쓰지_않는다() {
        writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        boolean second = writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        assertThat(second).isFalse();
        assertThat(count(7, OCT_12, "app_launched")).isEqualTo(2);
    }

    @Test
    void 두_태스크가_같은_창을_동시에_써도_한_번만_센다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> write = () -> {
            start.await();
            return writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(write);
            Future<Boolean> second = pool.submit(write);
            start.countDown();

            List<Boolean> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(count(7, OCT_12, "app_launched")).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 쓰다가_실패하면_선점과_횟수가_모두_롤백되고_다시_쓸_수_있다() {
        WindowAggregate broken = WindowAggregate.empty();
        // 파서를 거치지 않은 200자 초과 UTM — 선점·횟수 가산 뒤 마지막 단계(속성 upsert)에서 DB가 거부한다
        broken.addEvent(
                7,
                OCT_12,
                List.of("app_launched"),
                UserAttributeObservation.observed(T1, "u".repeat(250), false, null));

        assertThatThrownBy(() -> writer.write(new ExportWindow(OCT_12), broken))
                .isInstanceOf(DataAccessException.class);

        assertThat(repository.findProcessedDates(OCT_12, OCT_12)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from amplitude_daily_event", Integer.class))
                .isZero();
        assertThat(writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 1, T1)))
                .isTrue();
    }

    @Test
    void 늦게_수신된_이벤트는_다른_창에서_발생일_횟수에_더해진다() {
        writer.write(new ExportWindow(OCT_11), aggregate(7, OCT_11, "app_launched", 1, T1));

        writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_11, "app_launched", 1, T1));

        assertThat(count(7, OCT_11, "app_launched")).isEqualTo(2);
    }

    @Test
    void 창이_달라도_속성은_값마다_가장_늦게_본_non_null이_이긴다() {
        // 앞 창: 01시 A, 05시 값 없음(마지막 확인 05시) / 뒤 창: 03시 B — 값으로는 B가 A보다 늦다
        WindowAggregate first = utm(7, "A", "2026-10-12T01:00:00Z");
        first.addEvent(7, OCT_12, List.of("app_launched"), UserAttributeObservation.observed(T5, null, false, null));
        writer.write(new ExportWindow(OCT_11), first);

        writer.write(new ExportWindow(OCT_12), utm(7, "B", "2026-10-12T03:00:00Z"));

        assertThat(utmSource(7)).isEqualTo("B");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select first_seen_at, last_seen_at from amplitude_user_attribute where user_id = 7");
        assertThat(((Timestamp) row.get("first_seen_at")).toInstant()).isEqualTo(T1);
        assertThat(((Timestamp) row.get("last_seen_at")).toInstant()).isEqualTo(T5);
    }

    @Test
    void 창이_달라도_같은_시각의_속성은_먼저_쓴_값을_둔다() {
        writer.write(new ExportWindow(OCT_11), utm(7, "A", "2026-10-12T03:00:00Z"));

        writer.write(new ExportWindow(OCT_12), utm(7, "B", "2026-10-12T03:00:00Z"));

        assertThat(utmSource(7)).isEqualTo("A");
    }

    @Test
    void 광고_클릭은_한_번이라도_있으면_남는다() {
        WindowAggregate clicked = WindowAggregate.empty();
        clicked.addEvent(7, OCT_12, List.of("app_launched"), UserAttributeObservation.observed(T1, null, true, null));
        writer.write(new ExportWindow(OCT_11), clicked);

        writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 1, T3));

        assertThat(jdbcTemplate.queryForObject(
                        "select ad_click from amplitude_user_attribute where user_id = 7", Boolean.class))
                .isTrue();
    }

    @Test
    void 이벤트가_없는_창도_처리_기록은_남긴다() {
        writer.write(new ExportWindow(OCT_12), WindowAggregate.empty());

        assertThat(repository.findProcessedDates(OCT_12, OCT_12)).containsExactly(OCT_12);
    }
}
