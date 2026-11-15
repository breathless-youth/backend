-- BY-1292 스토어 결제로 사는 상점 단품

-- 값이 있으면 포인트가 아니라 스토어 결제(RevenueCat)로 사는 상품이다. 충전 상품(point_product)과 같은 ID를 쓰지 않는다
ALTER TABLE shop_item ADD COLUMN store_product_id varchar(100) UNIQUE;

-- 포인트로 사거나, 조건으로 받거나, 결제로 사거나 셋 중 하나다
ALTER TABLE shop_item DROP CONSTRAINT ck_shop_item_price_or_unlock;
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_acquire CHECK (
    (price IS NOT NULL)::int + (unlock_type IS NOT NULL)::int + (store_product_id IS NOT NULL)::int = 1
    AND (unlock_type IS NULL) = (unlock_value IS NULL));

-- 상품을 산 결제면 그 상품. 포인트 충전이면 NULL이다. 상품 결제는 points·bonus_points가 0이다
ALTER TABLE point_purchase ADD COLUMN item_id bigint REFERENCES shop_item(id);
CREATE INDEX idx_point_purchase_item ON point_purchase (user_id, item_id) WHERE item_id IS NOT NULL;
