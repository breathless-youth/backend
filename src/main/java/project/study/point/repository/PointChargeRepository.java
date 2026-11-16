package project.study.point.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.point.dto.PointProduct;
import project.study.point.dto.PointPurchase;

@Repository
@RequiredArgsConstructor
public class PointChargeRepository {

    private final JdbcClient jdbc;

    public List<PointProduct> activeProducts() {
        return jdbc.sql("""
                        SELECT id, store_product_id, points, bonus_points FROM point_product
                        WHERE active ORDER BY sort_order, id""").query(PointChargeRepository::product).list();
    }

    /** 내린 상품도 찾는다 — 내리기 직전에 한 결제의 알림이 늦게 올 수 있다. */
    public Optional<PointProduct> findProduct(String storeProductId) {
        return jdbc.sql(
                        "SELECT id, store_product_id, points, bonus_points FROM point_product WHERE store_product_id = :p")
                .param("p", storeProductId)
                .query(PointChargeRepository::product)
                .optional();
    }

    private static PointProduct product(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int points = rs.getInt("points");
        int bonus = rs.getInt("bonus_points");
        return new PointProduct(rs.getLong("id"), rs.getString("store_product_id"), points, bonus, points + bonus);
    }

    /** 그 결제를 지급받은 사용자. 락을 잡기 전에 누구의 락을 잡을지 알려고 잠그지 않고 읽는다. */
    /** 그 사용자의 결제용 ID. */
    public String purchaseIdOf(long userId) {
        return jdbc.sql("SELECT purchase_id FROM users WHERE id = :u")
                .param("u", userId)
                .query(String.class)
                .single();
    }

    /** 결제용 ID의 주인. 없으면 비어 있다. */
    public Optional<Long> findUserByPurchaseId(UUID purchaseId) {
        return jdbc.sql("SELECT id FROM users WHERE purchase_id = :p")
                .param("p", purchaseId)
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findOwner(String store, String transactionId) {
        return jdbc.sql("SELECT user_id FROM point_purchase WHERE store = :s AND transaction_id = :t")
                .param("s", store)
                .param("t", transactionId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("user_id", Long.class)))
                .optional()
                .flatMap(owner -> owner);
    }

    /** 그 결제가 상점 상품을 산 것이면 그 상품. 환불 알림을 어느 길로 처리할지 정하려고 잠그지 않고 읽는다. */
    public Optional<Long> findItemId(String store, String transactionId) {
        return jdbc.sql("SELECT item_id FROM point_purchase WHERE store = :s AND transaction_id = :t")
                .param("s", store)
                .param("t", transactionId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("item_id", Long.class)))
                .optional()
                .flatMap(itemId -> itemId);
    }

    /** 결제 한 건을 잠그고 읽는다. 같은 결제의 지급 알림과 환불 알림이 겹쳐도 한 줄로 선다. */
    public Optional<PointPurchase> findForUpdate(String store, String transactionId) {
        return jdbc.sql("""
                        SELECT id, user_id, store_product_id, points, bonus_points, status, item_id FROM point_purchase
                        WHERE store = :s AND transaction_id = :t FOR UPDATE""")
                .param("s", store)
                .param("t", transactionId)
                .query((rs, n) -> new PointPurchase(
                        rs.getLong("id"),
                        rs.getObject("user_id", Long.class),
                        rs.getString("store_product_id"),
                        rs.getInt("points"),
                        rs.getInt("bonus_points"),
                        "REFUNDED".equals(rs.getString("status")),
                        rs.getObject("item_id", Long.class)))
                .optional();
    }

    /** 넣을 결제 한 건. userId는 사용자를 찾지 못한 환불 표시면 null이다. */
    public record NewPurchase(
            Long userId,
            String store,
            String transactionId,
            String storeProductId,
            int points,
            int bonusPoints,
            boolean sandbox,
            Instant purchasedAt,
            Long itemId) {}

    /** 같은 결제가 이미 있으면 넣지 않고 false. refunded면 지급 없이 환불된 결제로 적는다. */
    public boolean insert(NewPurchase purchase, boolean refunded) {
        return jdbc.sql("""
                        INSERT INTO point_purchase (user_id, store, transaction_id, store_product_id, points,
                            bonus_points, sandbox, status, purchased_at, refunded_at, item_id)
                        VALUES (:u, :s, :t, :p, :points, :bonus, :sandbox, :status, :purchasedAt,
                            CASE WHEN :status = 'REFUNDED' THEN now() END, :item)
                        ON CONFLICT ON CONSTRAINT uq_point_purchase_tx DO NOTHING""")
                        .param("u", purchase.userId())
                        .param("s", purchase.store())
                        .param("t", purchase.transactionId())
                        .param("p", purchase.storeProductId())
                        .param("points", purchase.points())
                        .param("bonus", purchase.bonusPoints())
                        .param("sandbox", purchase.sandbox())
                        .param("status", refunded ? "REFUNDED" : "GRANTED")
                        .param("item", purchase.itemId())
                        .param(
                                "purchasedAt",
                                purchase.purchasedAt() == null ? null : Timestamp.from(purchase.purchasedAt()))
                        .update()
                > 0;
    }

    /** 그 사용자가 같은 상품을 산, 환불되지 않은 다른 결제가 있는지. */
    public boolean hasOtherGrantedItem(long userId, long itemId, long exceptPurchaseId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM point_purchase
                        WHERE user_id = :u AND item_id = :i AND status = 'GRANTED' AND id <> :id)""")
                .param("u", userId)
                .param("i", itemId)
                .param("id", exceptPurchaseId)
                .query(Boolean.class)
                .single();
    }

    /**
     * 구매 복원: 한 사용자가 결제로 산(환불되지 않은) 상점 상품 결제를 다른 사용자에게 옮기고, 옮긴 상품들을 돌려준다. 그 스토어의, 같은 환경(샌드박스·운영)의
     * 결제만 옮긴다.
     */
    public List<Long> moveItemPurchases(long fromUserId, long toUserId, String store, boolean sandbox) {
        return jdbc.sql("""
                        WITH moved AS (
                            UPDATE point_purchase SET user_id = :to
                            WHERE user_id = :from AND item_id IS NOT NULL AND status = 'GRANTED'
                              AND sandbox = :sandbox
                              AND store = :store
                            RETURNING item_id)
                        SELECT DISTINCT item_id FROM moved ORDER BY item_id""")
                .param("from", fromUserId)
                .param("to", toUserId)
                .param("store", store)
                .param("sandbox", sandbox)
                .query(Long.class)
                .list();
    }

    /** 구매 복원 알림을 처음 받았으면 적고 true. 이미 처리한 알림이면 false. */
    public boolean insertTransferEvent(String eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO purchase_transfer_event (event_id) VALUES (:e)
                        ON CONFLICT (event_id) DO NOTHING""").param("e", eventId).update();
        return inserted == 1;
    }

    public void markRefunded(long purchaseId, int reclaimed, int shortfall) {
        jdbc.sql("""
                UPDATE point_purchase SET status = 'REFUNDED', reclaimed_points = :r, shortfall_points = :s,
                    refunded_at = now()
                WHERE id = :id""")
                .param("r", reclaimed)
                .param("s", shortfall)
                .param("id", purchaseId)
                .update();
    }
}
