package project.study.point.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 충전 건별 남은 유료 포인트 (BY-1304). 유료 포인트는 먼저 충전한 것부터 쓰고, 한 사용자의 남은 양 합이 결제 몫(paid_balance)과 같다.
 * 호출자는 포인트 사용자 락을 쥐고 부른다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PaidLotRepository {

    private final JdbcClient jdbc;

    private record Lot(long id, int remaining) {}

    public void setRemaining(long purchaseId, int remaining) {
        jdbc.sql("UPDATE point_purchase SET paid_remaining = :r WHERE id = :id")
                .param("r", remaining)
                .param("id", purchaseId)
                .update();
    }

    public int remainingOf(long purchaseId) {
        return jdbc.sql("SELECT paid_remaining FROM point_purchase WHERE id = :id")
                .param("id", purchaseId)
                .query(Integer.class)
                .single();
    }

    /** 먼저 충전한 건부터 amount만큼 줄인다. 남은 양이 모자라면 있는 만큼만 줄인다. */
    public void consume(long userId, int amount) {
        if (amount <= 0) {
            return;
        }
        List<Lot> lots = jdbc.sql("""
                        SELECT id, paid_remaining FROM point_purchase
                        WHERE user_id = :u AND status = 'GRANTED' AND paid_remaining > 0
                        ORDER BY COALESCE(purchased_at, created_at), id
                        FOR UPDATE""")
                .param("u", userId)
                .query((rs, n) -> new Lot(rs.getLong("id"), rs.getInt("paid_remaining")))
                .list();
        int left = amount;
        for (Lot lot : lots) {
            if (left == 0) {
                break;
            }
            int used = Math.min(left, lot.remaining());
            setRemaining(lot.id(), lot.remaining() - used);
            left -= used;
        }
        if (left > 0) {
            // 남은 양의 합이 결제 몫보다 작다. 기록이 어긋났다는 뜻이라 사람이 보게 남긴다
            log.error("충전 건별 남은 유료 포인트가 결제 몫보다 모자랍니다: user={} 모자란 양={}", userId, left);
        }
    }

    public int totalRemaining(long userId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(paid_remaining), 0) FROM point_purchase
                        WHERE user_id = :u AND status = 'GRANTED'""").param("u", userId).query(Integer.class).single();
    }
}
