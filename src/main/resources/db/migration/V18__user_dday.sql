-- 홈 D-Day — 유저당 1개(제목 + 목표일). 여러 개로 늘릴 때는 UNIQUE(user_id)를 풀고 대표 플래그를 더한다.
CREATE TABLE user_dday (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL UNIQUE REFERENCES users (id) DEFERRABLE INITIALLY IMMEDIATE,
    title       VARCHAR(10) NOT NULL,
    target_date DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
