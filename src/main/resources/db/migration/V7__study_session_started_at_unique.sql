-- 앱 강제종료 후 재접속 시 같은 세션이 재전송될 수 있다 — 멱등 키는 (user_id, started_at) (프론트 합의).
-- 자정 분할 조각들은 started_at이 서로 다르므로(원본 시작 시각·자정) 조각끼리는 충돌하지 않는다.

-- 과거 데이터는 어떤 것도 자동 삭제·백필하지 않는다. 같은 (user_id, started_at)을 공유하는 과거 행은
-- "같은 세션의 재제출 중복"일 수도, "레거시 자정 분할 조각 + 정확히 자정에 시작한 별개 세션"일 수도
-- 있어 기계적으로 구분할 수 없다 — 일괄 삭제는 실제 공부 이력을 지울 수 있다.
-- 그런 행이나 NULL started_at이 존재하면 아래 문장들이 실패해 배포가 멈추고, 수동 정리 후 재배포한다.
-- 중복 감사 쿼리:
--   SELECT user_id, started_at, array_agg(id ORDER BY id)
--   FROM study_session GROUP BY user_id, started_at HAVING count(*) > 1;
-- NULL 감사 쿼리 (SET NOT NULL 실패 원인 행 식별용):
--   SELECT id, user_id, stat_date FROM study_session WHERE started_at IS NULL;

-- started_at이 멱등 키가 되므로 NULL일 수 없다 — 서버가 항상 요청값으로 채워온 값이라 스키마로 강제한다.
ALTER TABLE "study_session" ALTER COLUMN "started_at" SET NOT NULL;

-- 루트 제출 식별자 — 자정 분할 조각들이 원본 제출의 시작 시각을 공유한다.
-- 재제출 판별과 응답 조회는 이 컬럼으로만 하므로, 분할 조각(started_at=자정)이
-- 별개 제출의 멱등 키와 혼동되지 않는다.
-- V7 이전 행은 NULL로 남긴다(백필하지 않는다): 과거 분할 조각은 정확히 자정에 끝나는
-- 단독 세션과 구분할 수 없어 루트를 신뢰성 있게 복원할 수 없다. NULL 행은 재제출 판별에서
-- 빠지므로, 과거 제출의 재전송이나 시각이 겹치는 제출은 아래 유니크 제약이 409로 거절한다.
ALTER TABLE "study_session" ADD COLUMN "submission_started_at" timestamptz;

CREATE INDEX ON "study_session" ("user_id", "submission_started_at");

ALTER TABLE "study_session"
    ADD CONSTRAINT "uq_study_session_user_started_at" UNIQUE ("user_id", "started_at");
