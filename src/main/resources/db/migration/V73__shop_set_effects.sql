-- BY-1293 세트 칸, 효과 그림과 움직임 정보, 코드로 그리는 빛 효과

-- 세트는 캐릭터의 한 벌 옷이다. 7칸 소품과 함께 끼지 않는다(서비스가 지킨다)
ALTER TABLE shop_item DROP CONSTRAINT ck_shop_item_slot;
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_slot
    CHECK (slot IN ('HEAD', 'FACE', 'BODY', 'WINGS', 'HELD', 'UNDERFOOT', 'EFFECT', 'SET'));
ALTER TABLE user_mascot_accessory DROP CONSTRAINT ck_user_mascot_accessory_slot;
ALTER TABLE user_mascot_accessory ADD CONSTRAINT ck_user_mascot_accessory_slot
    CHECK (slot IN ('HEAD', 'FACE', 'BODY', 'WINGS', 'HELD', 'UNDERFOOT', 'EFFECT', 'SET'));

-- 세트는 스토어 결제로만 판다
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_set_paid
    CHECK (slot IS DISTINCT FROM 'SET' OR store_product_id IS NOT NULL);

-- 세트의 움직이는 효과 그림. 옷 그림(image_url)은 멈춰 있다. 없으면 NULL
ALTER TABLE shop_item ADD COLUMN effect_image_url varchar(500);

-- 효과 그림의 조각별 위치와 움직임: [{"x":0.1,"y":0.2,"width":0.3,"height":0.3,"motion":"float"}, ...]
-- 값은 판 크기에 대한 비율(0~1)이다. 효과 칸 소품은 image_url에, 세트는 effect_image_url에 적용한다. 없으면 NULL
ALTER TABLE shop_item ADD COLUMN effect_pieces jsonb
    CONSTRAINT ck_shop_item_effect_pieces CHECK (jsonb_typeof(effect_pieces) = 'array');

-- 그림 없이 앱이 코드로 그리는 빛 효과의 종류(aurora, glow, rainbow 등). 이때 image_url은 NULL이다. 아니면 NULL
ALTER TABLE shop_item ADD COLUMN effect_type varchar(32);
