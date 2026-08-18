-- 같은 유저의 세션 기간은 겹칠 수 없다 — DB 불변식 (BY-383).
-- 제출 시점의 두 기기 동시 사용 충돌과, 계정 병합 로직이 겹침 해소를 누락하는 경우를 모두 DB가 최종 방어한다.
-- 반개구간 '[)'이므로 끝과 시작이 맞닿는 세션(자정 분할 조각 포함)은 겹침이 아니다.

-- 기존 데이터에 겹침·NULL ended_at이 있으면 아래 문장들이 실패해 배포가 멈추고, 수동 정리 후 재배포한다 (V7 패턴).
-- 겹침 감사 쿼리:
--   SELECT a.id, b.id, a.user_id FROM study_session a
--     JOIN study_session b ON a.user_id = b.user_id AND a.id < b.id
--    WHERE tstzrange(a.started_at, a.ended_at, '[)') && tstzrange(b.started_at, b.ended_at, '[)');
-- NULL 감사 쿼리:
--   SELECT id, user_id, stat_date FROM study_session WHERE ended_at IS NULL;

-- ended_at이 NULL이면 tstzrange가 무한 구간이 되어 이후 모든 세션과 겹친다 — 서버가 항상 채워온 값이라 스키마로 강제한다
ALTER TABLE "study_session" ALTER COLUMN "ended_at" SET NOT NULL;

-- gist 인덱스에서 = 연산(user_id)을 쓰기 위한 확장
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE "study_session"
    ADD CONSTRAINT "ex_study_session_user_period"
    EXCLUDE USING gist ("user_id" WITH =, tstzrange("started_at", "ended_at", '[)') WITH &&);
