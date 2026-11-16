-- BY-1241 포인트 충전(인앱결제)과 환불

-- 잔액 가운데 결제로 산 몫. 쓸 때는 무료 몫부터 빠진다
ALTER TABLE point_account
    ADD COLUMN paid_balance integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_point_account_paid CHECK (paid_balance >= 0 AND paid_balance <= balance);

-- 그 줄의 금액 가운데 결제 몫(부호는 amount와 같다)
ALTER TABLE point_ledger ADD COLUMN paid_amount integer NOT NULL DEFAULT 0;

-- 충전 상품. 가격과 통화는 스토어가 갖고, 여기에는 스토어 상품 ID와 주는 포인트만 둔다
CREATE TABLE point_product (
    id bigserial PRIMARY KEY,
    store_product_id varchar(100) NOT NULL UNIQUE,
    points integer NOT NULL CHECK (points > 0),
    bonus_points integer NOT NULL DEFAULT 0 CHECK (bonus_points >= 0),
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true
);

-- 스토어 결제 한 건. (store, transaction_id)가 같은 결제는 한 번만 지급한다.
-- 환불 알림이 지급 알림보다 먼저 오면 REFUNDED 행만 남겨 뒤에 온 지급을 막는다.
-- 탈퇴해도 행은 남기고 user_id만 비운다(같은 결제가 다시 지급되지 않게)
CREATE TABLE point_purchase (
    id bigserial PRIMARY KEY,
    user_id bigint REFERENCES users(id),
    store varchar(24) NOT NULL,
    transaction_id varchar(128) NOT NULL,
    store_product_id varchar(100) NOT NULL,
    points integer NOT NULL CHECK (points >= 0),
    bonus_points integer NOT NULL CHECK (bonus_points >= 0),
    sandbox boolean NOT NULL,
    status varchar(12) NOT NULL CHECK (status IN ('GRANTED', 'REFUNDED')),
    -- 환불 때 잔액에서 되돌린 포인트와, 이미 써서 되돌리지 못한 포인트
    reclaimed_points integer NOT NULL DEFAULT 0 CHECK (reclaimed_points >= 0),
    shortfall_points integer NOT NULL DEFAULT 0 CHECK (shortfall_points >= 0),
    purchased_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    refunded_at timestamptz,
    CONSTRAINT uq_point_purchase_tx UNIQUE (store, transaction_id)
);
CREATE INDEX idx_point_purchase_user ON point_purchase (user_id) WHERE user_id IS NOT NULL;
