-- BY-1302: 마스코트의 순서와 여는 조건 확정값. 첫 마스코트(고른 적 없는 사용자의 기본값)가 BOOK에서 STOPWATCH로 바뀐다.
-- CAMCORDER는 공부 시간이 아니라 포인트(1,000P)로 산다. 출시 전이라 이미 고른 사용자는 따로 옮기지 않는다 —
-- 고른 것이 CAMCORDER면(사지 않았으므로) 조회할 때 첫 마스코트로 보이고, 공부 시간으로 여는 것은 고른 대로 남는다.
UPDATE mascot SET sort_order = 1, unlock_focus_sec = 0, price = NULL WHERE code = 'STOPWATCH';
UPDATE mascot SET sort_order = 2, unlock_focus_sec = 7200, price = NULL WHERE code = 'PENCIL';
UPDATE mascot SET sort_order = 3, unlock_focus_sec = 36000, price = NULL WHERE code = 'PLANNER';
UPDATE mascot SET sort_order = 4, unlock_focus_sec = 72000, price = NULL WHERE code = 'LOCK';
UPDATE mascot SET sort_order = 5, unlock_focus_sec = 108000, price = NULL WHERE code = 'BOOK';
UPDATE mascot SET sort_order = 6, unlock_focus_sec = 180000, price = NULL WHERE code = 'LAMP';
UPDATE mascot SET sort_order = 7, price = 1000 WHERE code = 'CAMCORDER';
