-- stat_date는 서버가 항상 계산해 넣는 값이라 NULL일 수 없다.
-- 스트릭 계산이 이 불변식에 의존하므로 스키마로 강제한다.
-- V1이 NULL을 허용했으므로, 혹시 있을 레거시 행은 시작 시각의 KST 날짜로 백필한 뒤 제약을 건다.
UPDATE "study_session"
SET "stat_date" = ("started_at" AT TIME ZONE 'Asia/Seoul')::date
WHERE "stat_date" IS NULL;

ALTER TABLE "study_session" ALTER COLUMN "stat_date" SET NOT NULL;
