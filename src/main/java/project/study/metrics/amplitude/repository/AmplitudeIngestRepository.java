package project.study.metrics.amplitude.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.amplitude.DailyEventKey;
import project.study.metrics.amplitude.UserAttributeObservation;

/** Amplitude 적재 테이블 쓰기·조회 (V28). */
@Repository
@RequiredArgsConstructor
public class AmplitudeIngestRepository {

    private static final String ADD_COUNT = """
            INSERT INTO amplitude_daily_event (user_id, event_date, event_key, count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id, event_date, event_key)
            DO UPDATE SET count = amplitude_daily_event.count + excluded.count""";

    // 값마다 더 늦게 본 non-null이 이기고, 같은 시각이면 먼저 있던 값을 둔다(UserAttributeObservation.merge와 같은 규칙).
    // 광고 클릭은 한 번이라도 있으면 남는다
    private static final String UPSERT_ATTRIBUTE = """
            INSERT INTO amplitude_user_attribute AS a
                (user_id, utm_source, utm_seen_at, ad_click, os_name, os_seen_at, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                utm_source = CASE WHEN %1$s THEN excluded.utm_source ELSE a.utm_source END,
                utm_seen_at = CASE WHEN %1$s THEN excluded.utm_seen_at ELSE a.utm_seen_at END,
                ad_click = a.ad_click OR excluded.ad_click,
                os_name = CASE WHEN %2$s THEN excluded.os_name ELSE a.os_name END,
                os_seen_at = CASE WHEN %2$s THEN excluded.os_seen_at ELSE a.os_seen_at END,
                first_seen_at = least(a.first_seen_at, excluded.first_seen_at),
                last_seen_at = greatest(a.last_seen_at, excluded.last_seen_at)""".formatted(newer("utm_seen_at"), newer("os_seen_at"));

    private static final Comparator<DailyEventKey> ROW_ORDER = Comparator.comparingLong(DailyEventKey::userId)
            .thenComparing(DailyEventKey::eventDate)
            .thenComparing(DailyEventKey::eventKey);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    /** 창 처리 기록을 선점한다. 이미 있으면(재실행·다른 태스크) false — 호출자는 아무것도 쓰지 않는다. */
    public boolean claimWindow(LocalDate date, int eventCount, int skipped) {
        int inserted = jdbc.sql("""
                        INSERT INTO amplitude_export_window (window_date, event_count, skipped)
                        VALUES (:date, :eventCount, :skipped)
                        ON CONFLICT (window_date) DO NOTHING""")
                .param("date", date)
                .param("eventCount", eventCount)
                .param("skipped", skipped)
                .update();
        return inserted == 1;
    }

    public void addCounts(Map<DailyEventKey, Integer> counts) {
        // 같은 행을 동시에 갱신할 수 있는 다른 트랜잭션과 행 잠금 순서를 맞춘다
        List<Object[]> rows = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ROW_ORDER))
                .map(entry -> new Object[] {
                    entry.getKey().userId(),
                    entry.getKey().eventDate(),
                    entry.getKey().eventKey(),
                    entry.getValue()
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(ADD_COUNT, rows);
        }
    }

    public void upsertAttributes(Map<Long, UserAttributeObservation> attributes) {
        List<Object[]> rows = attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> attributeRow(entry.getKey(), entry.getValue()))
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(UPSERT_ATTRIBUTE, rows);
        }
    }

    public Set<LocalDate> findProcessedDates(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                        SELECT window_date FROM amplitude_export_window
                        WHERE window_date BETWEEN :from AND :to""")
                .param("from", from)
                .param("to", to)
                .query(LocalDate.class)
                .list());
    }

    private static Object[] attributeRow(long userId, UserAttributeObservation value) {
        return new Object[] {
            userId,
            value.utmSource(),
            utc(value.utmSeenAt()),
            value.adClick(),
            value.osName(),
            utc(value.osSeenAt()),
            utc(value.firstSeenAt()),
            utc(value.lastSeenAt())
        };
    }

    // 들어온 값이 있고, 저장된 값이 없거나 더 이른 시각일 때만 바꾼다 — null > x는 NULL(거짓)이라 들어온 값이 없으면 둔다
    private static String newer(String seenAtColumn) {
        return "(excluded.%1$s > a.%1$s OR (a.%1$s IS NULL AND excluded.%1$s IS NOT NULL))".formatted(seenAtColumn);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
