package project.study.point.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    public Optional<Long> findOwner(String store, String transactionId) {
        return jdbc.sql("SELECT user_id FROM point_purchase WHERE store = :s AND transaction_id = :t")
                .param("s", store)
                .param("t", transactionId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("user_id", Long.class)))
                .optional()
                .flatMap(owner -> owner);
    }

    /** 결제 한 건을 잠그고 읽는다. 같은 결제의 지급 알림과 환불 알림이 겹쳐도 한 줄로 선다. */
    public Optional<PointPurchase> findForUpdate(String store, String transactionId) {
        return jdbc.sql("""
                        SELECT id, user_id, store_product_id, points, bonus_points, status FROM point_purchase
                        WHERE store = :s AND transaction_id = :t FOR UPDATE""")
                .param("s", store)
                .param("t", transactionId)
                .query((rs, n) -> new PointPurchase(
                        rs.getLong("id"),
                        rs.getObject("user_id", Long.class),
                        rs.getString("store_product_id"),
                        rs.getInt("points"),
                        rs.getInt("bonus_points"),
                        "REFUNDED".equals(rs.getString("status"))))
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
            Instant purchasedAt) {}

    /** 같은 결제가 이미 있으면 넣지 않고 false. refunded면 지급 없이 환불된 결제로 적는다. */
    public boolean insert(NewPurchase purchase, boolean refunded) {
        return jdbc.sql("""
                        INSERT INTO point_purchase (user_id, store, transaction_id, store_product_id, points,
                            bonus_points, sandbox, status, purchased_at, refunded_at)
                        VALUES (:u, :s, :t, :p, :points, :bonus, :sandbox, :status, :purchasedAt,
                            CASE WHEN :status = 'REFUNDED' THEN now() END)
                        ON CONFLICT ON CONSTRAINT uq_point_purchase_tx DO NOTHING""")
                        .param("u", purchase.userId())
                        .param("s", purchase.store())
                        .param("t", purchase.transactionId())
                        .param("p", purchase.storeProductId())
                        .param("points", purchase.points())
                        .param("bonus", purchase.bonusPoints())
                        .param("sandbox", purchase.sandbox())
                        .param("status", refunded ? "REFUNDED" : "GRANTED")
                        .param(
                                "purchasedAt",
                                purchase.purchasedAt() == null ? null : Timestamp.from(purchase.purchasedAt()))
                        .update()
                > 0;
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
