-- 퇴장 시점에 서버가 마지막으로 본 순공 타이머 값(초).
-- 클라이언트가 state로 주기 보고하는 값이라 실제보다 최대 1분가량 뒤처질 수 있는 운영/백업 값이다.
-- 유저 공부 기록(study_session)과는 무관하며, NULL = 이 컬럼 도입 전 기록이거나 서버 재시작으로 유실.
ALTER TABLE "room_participations" ADD COLUMN "focus_sec" integer;
