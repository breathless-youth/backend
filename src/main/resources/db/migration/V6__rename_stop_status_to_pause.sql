-- EventStatus.STOP을 PAUSE로 리네임 (일시정지라는 의미를 더 명확히 드러냄).
-- 컬럼은 varchar라 제약 변경은 필요 없고, 기존에 저장된 값만 맞춰준다.
UPDATE "status_event" SET "status" = 'PAUSE' WHERE "status" = 'STOP';
