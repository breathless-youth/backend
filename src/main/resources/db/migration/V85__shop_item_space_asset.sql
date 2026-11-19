-- BY-1333 세션 공간을 상점 상품으로 내보낸다

-- 공간 파일 위치. 끝은 파일 내용 해시라 파일을 바꾸면 값도 바뀐다(예: watcher/9c8d9f19). 앱이 이 값과 종류로 기기별 파일 주소를 만든다
ALTER TABLE shop_item ADD COLUMN asset_key varchar(100);
ALTER TABLE shop_item ADD COLUMN asset_type varchar(8) CHECK (asset_type IN ('IMAGE', 'VIDEO'));
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_asset CHECK (
    (asset_key IS NULL) = (asset_type IS NULL)
    AND (asset_key IS NULL OR category = 'TIMER_BACKGROUND'));

-- 타이머 배경은 얻는 방법이 없는 무료 상품도 된다. 무료 상품은 판매 중인 동안 모두가 가진 것으로 본다
ALTER TABLE shop_item DROP CONSTRAINT ck_shop_item_acquire;
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_acquire CHECK (
    (price IS NOT NULL)::int + (unlock_type IS NOT NULL)::int + (store_product_id IS NOT NULL)::int
        IN (CASE WHEN category = 'TIMER_BACKGROUND' THEN 0 ELSE 1 END, 1)
    AND (unlock_type IS NULL) = (unlock_value IS NULL));
