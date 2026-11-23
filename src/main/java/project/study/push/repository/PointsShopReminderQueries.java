package project.study.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.common.time.DefaultTimeZone;

@Repository
@RequiredArgsConstructor
public class PointsShopReminderQueries {

    private static final String CAMPAIGN = "points_shop_reminder";
    private final JdbcClient jdbc;

    /** 캠페인이 켜진 뒤 알림 허용 상태로 접속한 첫 시각만 보존한다. */
    public void enroll(long userId, Instant at) {
        jdbc.sql("""
                INSERT INTO push_experiment_assignment (experiment, user_id, arm, assigned_at)
                VALUES (:campaign, :userId, 'reminder', :at)
                ON CONFLICT (experiment, user_id) DO NOTHING
                """)
                .param("campaign", CAMPAIGN)
                .param("userId", userId)
                .param("at", Timestamp.from(at))
                .update();
    }

    public List<Long> findDue(LocalDate today) {
        return jdbc.sql("""
                SELECT u.id FROM push_experiment_assignment a
                JOIN users u ON u.id = a.user_id
                WHERE a.experiment = :campaign
                  AND a.assigned_at < :todayStart
                  AND u.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM push_token t WHERE t.user_id = u.id AND t.notifications_enabled)
                  AND NOT EXISTS (SELECT 1 FROM active_study_session s WHERE s.user_id = u.id)
                  AND NOT EXISTS (
                    SELECT 1 FROM push_send p
                    WHERE p.user_id = u.id AND p.send_date > :intervalStart
                      AND (p.send_date = :today OR (starts_with(p.type, :prefix) AND p.fcm_accepted IS DISTINCT FROM 0))
                  )
                ORDER BY u.id
                """)
                .param("campaign", CAMPAIGN)
                .param("prefix", CAMPAIGN + "_")
                .param(
                        "todayStart",
                        Timestamp.from(today.atStartOfDay(DefaultTimeZone.ZONE).toInstant()))
                .param("intervalStart", today.minusDays(3))
                .param("today", today)
                .query(Long.class)
                .list();
    }

    /** 잔액으로 살 수 있고 이미 보유하지 않은 활성 소품을 찾는다. */
    public Optional<Long> affordableAccessory(long userId, long balance) {
        return jdbc.sql("""
                    SELECT i.id FROM shop_item i JOIN mascot m ON m.code = i.mascot_code
                    WHERE i.category = 'MASCOT_ACCESSORY' AND i.active AND i.price <= :balance
                      AND NOT EXISTS (SELECT 1 FROM user_item o WHERE o.user_id = :userId AND o.item_id = i.id)
                      AND (EXISTS (SELECT 1 FROM user_mascot_owned o WHERE o.user_id = :userId AND o.mascot_code = m.code)
                           OR (m.price IS NULL AND (m.unlock_focus_sec = 0 OR m.unlock_focus_sec <= (
                               SELECT COALESCE(SUM(s.focus_sec), 0) FROM study_session s
                               WHERE s.user_id = :userId AND s.source = 'MEASURED'
                           ))))
                    ORDER BY i.price, i.sort_order, i.id
                    LIMIT 1
                """)
                .param("userId", userId)
                .param("balance", balance)
                .query(Long.class)
                .optional();
    }
}
