-- BY-1319: 마스코트 순서와 여는 시간. 부기와 플래니의 자리를 바꾸고, 공부 시간으로 여는 다섯은 1·10·30·50·100시간이다.
-- 타이미는 처음부터 열려 있고 캠코미는 포인트(1,000P)로 산다(그대로).
UPDATE mascot SET sort_order = 2, unlock_focus_sec = 3600 WHERE code = 'PENCIL';
UPDATE mascot SET sort_order = 3, unlock_focus_sec = 36000 WHERE code = 'BOOK';
UPDATE mascot SET sort_order = 4, unlock_focus_sec = 108000 WHERE code = 'LOCK';
UPDATE mascot SET sort_order = 5, unlock_focus_sec = 180000 WHERE code = 'PLANNER';
UPDATE mascot SET sort_order = 6, unlock_focus_sec = 360000 WHERE code = 'LAMP';
