-- BY-1304: 약관(2026-11-23 시행 제13·14조)의 환불 규칙에 필요한 결제 기록 (ADR-0047 30번).
-- paid_remaining: 그 충전 건에서 아직 쓰지 않은 유료 포인트. 유료 포인트는 먼저 충전한 것부터 쓴다. 한 사용자의 GRANTED 건 합이
--   point_account.paid_balance와 같다.
-- owner_deleted_at: 주인이 계정을 삭제한 때. 삭제해도 결제 행과 paid_remaining은 남아(user_id만 비운다) 환불 요청에 답한다.
-- item_first_worn_at: 결제로 산 상품을 처음 착용(또는 배경으로 설정)한 때. 입기 전인지 판단한다.
-- manual_refunded_at, manual_refund_points: 스토어 밖에서(계좌 이체 등) 직접 환불한 때와 그 유료 포인트 수. 스토어 환불과 달리 알림이
--   오지 않아 사람이 적는다. 같은 요청에 두 번 환불하지 않으려는 기록이다 (docs/runbooks/point-refund.md)
SET lock_timeout = '5s';
ALTER TABLE point_purchase
    ADD COLUMN paid_remaining integer NOT NULL DEFAULT 0,
    ADD COLUMN owner_deleted_at timestamptz,
    ADD COLUMN item_first_worn_at timestamptz,
    ADD COLUMN manual_refunded_at timestamptz,
    ADD COLUMN manual_refund_points integer CHECK (manual_refund_points > 0);
ALTER TABLE point_purchase
    ADD CONSTRAINT ck_point_purchase_paid_remaining CHECK (paid_remaining >= 0 AND paid_remaining <= points);

-- 지금 결제 몫(paid_balance)을 최근 충전 건부터 채운다. 먼저 충전한 것부터 썼다고 보면 남은 것은 최근 건이다
WITH ranked AS (
    SELECT p.id, p.points, a.paid_balance,
           SUM(p.points) OVER (PARTITION BY p.user_id
                               ORDER BY COALESCE(p.purchased_at, p.created_at) DESC, p.id DESC) AS newer_points
    FROM point_purchase p
    JOIN point_account a ON a.user_id = p.user_id
    WHERE p.status = 'GRANTED' AND p.item_id IS NULL
)
UPDATE point_purchase p
SET paid_remaining = GREATEST(0, LEAST(r.points, r.paid_balance - (r.newer_points - r.points)))
FROM ranked r
WHERE p.id = r.id;

-- 지금 끼고 있거나 배경으로 쓰는 결제 상품은 이미 입은 것이다. 처음 입은 때를 모르니 결제 시각으로 적는다
UPDATE point_purchase p
SET item_first_worn_at = COALESCE(p.purchased_at, p.created_at)
WHERE p.status = 'GRANTED' AND p.item_id IS NOT NULL AND p.user_id IS NOT NULL
  AND (EXISTS (SELECT 1 FROM user_mascot_accessory a WHERE a.user_id = p.user_id AND a.item_id = p.item_id)
       OR EXISTS (SELECT 1 FROM user_decoration d WHERE d.user_id = p.user_id AND d.timer_background_item_id = p.item_id));

CREATE INDEX idx_point_purchase_paid_lot ON point_purchase (user_id) WHERE paid_remaining > 0;
RESET lock_timeout;
