-- D-Day 제목 상한 10자 → 15자. 기존 값은 모두 10자 이하라 그대로 들어간다.
ALTER TABLE user_dday ALTER COLUMN title TYPE VARCHAR(15);
