package project.study.shop.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.shop.dto.AccessoryRow;
import project.study.shop.dto.AccessorySlot;
import project.study.shop.dto.DecorationRow;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItem;
import project.study.shop.dto.UnlockType;
import project.study.studysession.StudySessionThresholds;

@Repository
@RequiredArgsConstructor
public class ShopRepository {

    private static final String ITEM_COLUMNS =
            "id, code, category, mascot_code, slot, tag, name, image_url, price, unlock_type, unlock_value";

    private final JdbcClient jdbc;

    /** 파는 중인 상품. 종류, 진열 순서 순. 이름은 language 번역이 있으면 그것, 없으면 한국어다. */
    public List<ShopItem> findActive(String language) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS.replace(" name,", " COALESCE(names ->> :lang, name) AS name,")
                        + " FROM shop_item WHERE active ORDER BY category, sort_order, id")
                .param("lang", language)
                .query(ShopRepository::item)
                .list();
    }

    /** 내린 상품도 찾는다. 이미 가진 상품은 내린 뒤에도 쓸 수 있어야 한다. */
    public Optional<ShopItem> findById(long itemId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM shop_item WHERE id = :id")
                .param("id", itemId)
                .query(ShopRepository::item)
                .optional();
    }

    public boolean isActive(long itemId) {
        return jdbc.sql("SELECT active FROM shop_item WHERE id = :id")
                .param("id", itemId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public Set<Long> ownedItemIds(long userId) {
        return jdbc.sql("SELECT item_id FROM user_item WHERE user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .set();
    }

    public void insertOwned(long userId, long itemId) {
        jdbc.sql("INSERT INTO user_item (user_id, item_id) VALUES (:u, :i)")
                .param("u", userId)
                .param("i", itemId)
                .update();
    }

    public List<AccessoryRow> accessories(long userId) {
        return jdbc.sql("""
                        SELECT mascot_code, slot, item_id FROM user_mascot_accessory
                        WHERE user_id = :u ORDER BY mascot_code, slot""")
                .param("u", userId)
                .query((rs, n) -> new AccessoryRow(
                        rs.getString("mascot_code"),
                        AccessorySlot.valueOf(rs.getString("slot")),
                        rs.getLong("item_id")))
                .list();
    }

    /** 여러 사람이 낀 소품과 그 상품. 사용자 ID → (낀 자리, 상품) 목록. */
    public Map<Long, List<Map.Entry<AccessoryRow, ShopItem>>> accessoriesOf(Collection<Long> userIds) {
        Map<Long, List<Map.Entry<AccessoryRow, ShopItem>>> byUser = new HashMap<>();
        if (userIds.isEmpty()) {
            return byUser;
        }
        jdbc.sql("""
                        SELECT a.user_id, a.mascot_code AS worn_mascot, a.slot AS worn_slot, i.* FROM user_mascot_accessory a
                        JOIN shop_item i ON i.id = a.item_id
                        WHERE a.user_id IN (:ids) ORDER BY a.user_id, a.mascot_code, a.slot""")
                .param("ids", userIds)
                .query((rs, n) -> byUser.computeIfAbsent(rs.getLong("user_id"), id -> new ArrayList<>())
                        .add(Map.entry(
                                new AccessoryRow(
                                        rs.getString("worn_mascot"),
                                        AccessorySlot.valueOf(rs.getString("worn_slot")),
                                        rs.getLong("id")),
                                item(rs, n))))
                .list();
        return byUser;
    }

    public void saveAccessory(long userId, String mascotCode, AccessorySlot slot, long itemId) {
        jdbc.sql("""
                INSERT INTO user_mascot_accessory (user_id, mascot_code, slot, item_id) VALUES (:u, :m, :s, :i)
                ON CONFLICT (user_id, mascot_code, slot) DO UPDATE SET item_id = EXCLUDED.item_id""")
                .param("u", userId)
                .param("m", mascotCode)
                .param("s", slot.name())
                .param("i", itemId)
                .update();
    }

    public void deleteAccessory(long userId, String mascotCode, AccessorySlot slot) {
        jdbc.sql("DELETE FROM user_mascot_accessory WHERE user_id = :u AND mascot_code = :m AND slot = :s")
                .param("u", userId)
                .param("m", mascotCode)
                .param("s", slot.name())
                .update();
    }

    public DecorationRow decoration(long userId) {
        return jdbc.sql("SELECT timer_background_item_id FROM user_decoration WHERE user_id = :u")
                .param("u", userId)
                .query((rs, n) -> new DecorationRow(rs.getObject("timer_background_item_id", Long.class)))
                .optional()
                .orElse(new DecorationRow(null));
    }

    public void saveDecoration(long userId, DecorationRow row) {
        jdbc.sql("""
                INSERT INTO user_decoration (user_id, timer_background_item_id) VALUES (:u, :bg)
                ON CONFLICT (user_id) DO UPDATE SET timer_background_item_id = EXCLUDED.timer_background_item_id""")
                .param("u", userId)
                .param("bg", row.timerBackgroundItemId())
                .update();
    }

    /** 가장 길었던 연속 공부 일수. 스트릭과 같이 측정 세션 하나의 순공이 기준 이상인 날만 센다. 날짜에서 순번을 빼면 이어진 날들이 같은 값이 되는 것으로 묶는다. */
    public int maxStreakDays(long userId) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(run), 0) FROM (
                            SELECT COUNT(*) AS run FROM (
                                SELECT day, day - CAST(ROW_NUMBER() OVER (ORDER BY day) AS integer) AS island FROM (
                                    SELECT DISTINCT stat_date AS day FROM study_session
                                    WHERE user_id = :u AND source = 'MEASURED' AND focus_sec >= :min
                                ) days
                            ) numbered GROUP BY island
                        ) runs""")
                .param("u", userId)
                .param("min", StudySessionThresholds.MIN_STREAK_FOCUS_SEC)
                .query(Integer.class)
                .single();
    }

    private static ShopItem item(ResultSet rs, int rowNum) throws SQLException {
        String slot = rs.getString("slot");
        String unlockType = rs.getString("unlock_type");
        return new ShopItem(
                rs.getLong("id"),
                rs.getString("code"),
                ShopCategory.valueOf(rs.getString("category")),
                rs.getString("mascot_code"),
                slot == null ? null : AccessorySlot.valueOf(slot),
                rs.getString("tag"),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getObject("price", Integer.class),
                unlockType == null ? null : UnlockType.valueOf(unlockType),
                rs.getObject("unlock_value", Integer.class));
    }
}
