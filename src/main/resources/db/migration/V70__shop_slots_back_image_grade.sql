-- BY-1289 소품 칸 7칸, 뒤 조각 그림, 상품 등급

-- UNDERFOOT이 9자라 열도 넓힌다
ALTER TABLE shop_item ALTER COLUMN slot TYPE varchar(16);
ALTER TABLE shop_item DROP CONSTRAINT shop_item_slot_check;
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_slot
    CHECK (slot IN ('HEAD', 'FACE', 'BODY', 'WINGS', 'HELD', 'UNDERFOOT', 'EFFECT'));

ALTER TABLE user_mascot_accessory ALTER COLUMN slot TYPE varchar(16);
ALTER TABLE user_mascot_accessory DROP CONSTRAINT user_mascot_accessory_slot_check;
ALTER TABLE user_mascot_accessory ADD CONSTRAINT ck_user_mascot_accessory_slot
    CHECK (slot IN ('HEAD', 'FACE', 'BODY', 'WINGS', 'HELD', 'UNDERFOOT', 'EFFECT'));

-- 마스코트 뒤에 그리는 조각(날개의 뒷면 등). 없으면 NULL
ALTER TABLE shop_item ADD COLUMN back_image_url varchar(500);

-- 등급 1·2·3. 화면에 보여 주는 값이고 사는 방법은 price·unlock_type이 정한다
ALTER TABLE shop_item ADD COLUMN grade smallint NOT NULL DEFAULT 1 CHECK (grade IN (1, 2, 3));
