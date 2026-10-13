package project.study.metrics.amplitude.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;

/** 리포트용 Amplitude 적재 데이터 읽기 (V28). 쓰기는 AmplitudeIngestRepository. */
@Repository
@RequiredArgsConstructor
public class AmplitudeQueryRepository {

    private final JdbcClient jdbc;

    public List<AmplitudeDailyCount> findCounts(LocalDate from, LocalDate to, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT user_id, event_date, event_key, count FROM amplitude_daily_event
                        WHERE event_date BETWEEN :from AND :to AND event_key IN (:keys)""")
                .param("from", from)
                .param("to", to)
                .param("keys", keys)
                .query((rs, rowNum) -> new AmplitudeDailyCount(
                        rs.getLong("user_id"),
                        rs.getObject("event_date", LocalDate.class),
                        rs.getString("event_key"),
                        rs.getInt("count")))
                .list();
    }

    public Map<Long, AmplitudeAttribute> findAttributes(Collection<Long> userIds) {
        Map<Long, AmplitudeAttribute> attributes = new HashMap<>();
        if (userIds.isEmpty()) {
            return attributes;
        }
        jdbc.sql("SELECT user_id, utm_source, ad_click, os_name FROM amplitude_user_attribute WHERE user_id IN (:ids)")
                .param("ids", userIds)
                .query(rs -> {
                    attributes.put(
                            rs.getLong("user_id"),
                            new AmplitudeAttribute(
                                    rs.getString("utm_source"), rs.getBoolean("ad_click"), rs.getString("os_name")));
                });
        return attributes;
    }
}
