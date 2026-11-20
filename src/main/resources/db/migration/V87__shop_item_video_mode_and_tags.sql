-- 영상 공간의 재생 방식과 서버가 정하는 공간 분류
-- 감시(WATCH)는 들어오기·머무르기·나가기 장면과 소리를 따로 재생하는 방식이라, 계속 반복하는 일반 영상(LOOP)과 재생기가 다르다
-- 락 대기 상한: shop_item을 바꾸는 동안 상품 조회가 막히므로 5초 안에 못 잡으면 배포를 실패시킨다(V84와 같다)
SET LOCAL lock_timeout = '5s';

ALTER TABLE shop_item ADD COLUMN video_mode varchar(8) CHECK (video_mode IN ('LOOP', 'WATCH'));
-- V86의 감독관 행이 이미 VIDEO라 제약보다 먼저 채운다
UPDATE shop_item SET video_mode = 'WATCH' WHERE code = 'SPACE_PROCTOR';
-- 기본값을 두지 않아 영상 공간을 넣을 때 방식을 반드시 적게 한다
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_video_mode CHECK (
    (video_mode IS NOT NULL) = (asset_type IS NOT DISTINCT FROM 'VIDEO'));

-- 분류를 늘릴 때 앱을 다시 배포하지 않도록 이름까지 서버가 갖는다
-- shop_item.tag는 배포 중인 옛 서버와 아직 바뀌지 않은 웹이 읽어서 다음 릴리즈에 삭제한다
CREATE TABLE shop_tag (
    id bigserial PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(60) NOT NULL,
    -- 다른 언어 이름이고, 키는 주 언어(en, ja, zh)다
    -- zh에는 번체를 넣는다
    -- 없는 언어는 name(한국어)으로 보여 준다
    names jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    -- false면 응답에서 빼고 연결은 남겨서, 다시 켜면 돌아온다
    active boolean NOT NULL DEFAULT true
);

-- 상품과 분류는 삭제하지 않고 active로 내리므로 CASCADE를 두지 않는다
CREATE TABLE shop_item_tag (
    item_id bigint NOT NULL REFERENCES shop_item (id),
    tag_id bigint NOT NULL REFERENCES shop_tag (id),
    PRIMARY KEY (item_id, tag_id)
);
CREATE INDEX idx_shop_item_tag_tag ON shop_item_tag (tag_id);
