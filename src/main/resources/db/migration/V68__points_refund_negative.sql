-- BY-1241 환불은 결제로 준 포인트를 전부 되돌리고, 이미 써서 모자라면 잔액을 음수로 둔다

ALTER TABLE point_account
    DROP CONSTRAINT point_account_balance_check,
    DROP CONSTRAINT ck_point_account_paid,
    -- 잔액이 음수인 동안 결제 몫은 0이다
    ADD CONSTRAINT ck_point_account_paid CHECK (paid_balance >= 0 AND paid_balance <= GREATEST(balance, 0));

COMMENT ON COLUMN point_purchase.reclaimed_points IS '환불 때 되돌린 포인트(결제로 준 전부)';
COMMENT ON COLUMN point_purchase.shortfall_points IS '그 가운데 이미 써서 잔액을 음수로 만든 포인트';
