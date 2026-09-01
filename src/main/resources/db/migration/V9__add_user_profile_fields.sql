-- 프로필 필드 추가 (BY-404-룸-참여): 한줄 목표, 아바타 이니셜, 아바타 색상 인덱스
-- 닉네임 유니크 제약은 유지한다 (중복 시 409 NICKNAME_TAKEN)
ALTER TABLE "users" ADD COLUMN "goal" varchar;
ALTER TABLE "users" ADD COLUMN "initial" varchar;
ALTER TABLE "users" ADD COLUMN "color_index" integer;

-- 기존 유저 백필 — 닉네임 없는 유저는 id 그대로 발급해 유니크 충돌을 원천 차단한다
-- (랜덤·모듈로 값은 대량 UPDATE에서 충돌하면 마이그레이션이 실패한다)
UPDATE "users"
SET "nickname" = '포메' || "id"
WHERE "nickname" IS NULL;

UPDATE "users" SET "initial" = LEFT("nickname", 1) WHERE "initial" IS NULL;
UPDATE "users" SET "color_index" = floor(random() * 8)::integer WHERE "color_index" IS NULL;
