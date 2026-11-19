-- 독서실 감독관을 첫 무료 영상 공간으로 카탈로그에 넣는다
-- 파일은 웹의 /spaces/watcher/9c8d9f19/ 에 먼저 올라가 있다. 행이 파일보다 먼저 생기면 목록에는 보이는데 파일이 없다
-- 가격·조건·스토어 상품이 모두 없어 무료이고, 판매 중인 동안 모두가 가진 것으로 본다
INSERT INTO shop_item (code, category, tag, name, names, asset_key, asset_type, sort_order)
VALUES ('SPACE_PROCTOR', 'TIMER_BACKGROUND', 'STUDY_SPACE',
        '독서실 감독관',
        '{"en": "Study hall proctor", "ja": "自習室の監督", "zh": "自習室監督"}',
        'watcher/9c8d9f19', 'VIDEO', 0);
