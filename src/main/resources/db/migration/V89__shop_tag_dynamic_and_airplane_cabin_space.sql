-- 딴짓하면 화면이 바뀌는 공간을 모으는 첫 서버 분류 '다이나믹'을 만든다
-- 감독관이 들어오는 감독 공간과 비집중 장면이 있는 반복 공간을 함께 묶는다. 앱이 재생 방식으로 따로 묶던 감독 섹션을 대신한다(BY-1391)
INSERT INTO shop_tag (code, name, names, sort_order)
VALUES ('DYNAMIC', '다이나믹', '{"en": "Dynamic", "ja": "ダイナミック", "zh": "動態"}', 0);

-- 해 질 녘 기내 창가를 무료 반복 영상 공간으로 넣는다. 비집중 장면은 공간 폴더의 매니페스트 2판에 있어 재생 방식은 LOOP다
-- 파일은 웹의 /spaces/airplane-cabin/1ff8ae4d/ 에 먼저 올라가 있어야 한다. 행이 파일보다 먼저 생기면 목록에는 보이는데 파일이 없다
INSERT INTO shop_item (code, category, name, names, asset_key, asset_type, video_mode, sort_order)
VALUES ('SPACE_AIRPLANE_CABIN', 'TIMER_BACKGROUND', '해 질 녘의 기내 창가',
        '{"en": "Window Seat at Dusk", "ja": "夕暮れの窓側席", "zh": "黃昏的靠窗座位"}',
        'airplane-cabin/1ff8ae4d', 'VIDEO', 'LOOP', 1);

INSERT INTO shop_item_tag (item_id, tag_id)
SELECT i.id, t.id
FROM shop_item i, shop_tag t
WHERE i.code IN ('SPACE_PROCTOR', 'SPACE_AIRPLANE_CABIN') AND t.code = 'DYNAMIC';
