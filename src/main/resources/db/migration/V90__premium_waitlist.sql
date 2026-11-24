-- BY-1394: 프리미엄 사전 신청(페이크도어 실험, BY-1392) 저장 테이블.
-- 사용자당 한 행이다. 신청 완료와 기대 기능 선택이 같은 행을 덮어쓴다.
-- email은 14세 이상이고 수신에 동의한 사람만 평문으로 저장한다(users.email과 같은 관례, ADR-0051).
-- 파기는 email만 NULL로 만들고 집계용 행은 남긴다(docs/runbooks/premium-waitlist.md).
CREATE TABLE premium_waitlist (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL UNIQUE REFERENCES users (id),
    price            INT         NOT NULL CHECK (price IN (2900, 5900)),
    placement        VARCHAR     NOT NULL CHECK (placement IN ('RESULT', 'HOME')),
    age_over14       BOOLEAN     NOT NULL,
    email            VARCHAR(254),                -- 14세 미만이거나 파기했으면 NULL
    expected_feature VARCHAR,                     -- AI_PLANNER 등, 고르기 전엔 NULL
    consented_at     TIMESTAMPTZ,                 -- 이메일을 저장한 시각, 14세 미만이면 NULL
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
