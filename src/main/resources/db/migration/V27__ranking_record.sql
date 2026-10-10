-- BY-828: 랭킹 마감 기록 (ADR-0029) — 판·기간마다 마감 1분 뒤 한 번 확정하고 이후 바꾸지 않는다.
-- 마감 표시: 판·기간당 1행. PK가 태스크 둘의 중복 확정을 막는다. skipped = 마감 뒤 1시간이 넘어 확정하지 않고 건너뜀
CREATE TABLE ranking_close (
    board_key    VARCHAR     NOT NULL,   -- 예: FOCUS_TIME:WEEKLY, TIME_SLOT:DAILY:NIGHT
    period_start DATE        NOT NULL,
    closes_at    TIMESTAMPTZ NOT NULL,   -- 기간 마감 시각
    closed_at    TIMESTAMPTZ NOT NULL,   -- 확정한 시각
    skipped      BOOLEAN     NOT NULL,
    PRIMARY KEY (board_key, period_start)
);

-- 메달: 1·2·3위 중 메달 조건(시간 판 30분)을 넘은 사람만. 탈퇴해도 남지만 본인만 조회한다
CREATE TABLE ranking_record (
    id           BIGSERIAL      PRIMARY KEY,
    user_id      BIGINT         NOT NULL,
    board_key    VARCHAR        NOT NULL,
    period_start DATE           NOT NULL,
    closes_at    TIMESTAMPTZ    NOT NULL,
    rank         SMALLINT       NOT NULL,   -- 1·2·3
    value        NUMERIC(12, 1) NOT NULL,   -- 초 또는 집중률(%)
    seen_at      TIMESTAMPTZ,               -- 마감 모달을 본 시각
    UNIQUE (board_key, period_start, rank)
);
CREATE INDEX idx_ranking_record_user ON ranking_record (user_id, closes_at DESC);

-- 개인 최고: 역대 마감 최종 순위 중 최고, 사용자당 1행. 같은 순위면 먼저 것을 둔다
CREATE TABLE ranking_best (
    user_id      BIGINT  PRIMARY KEY,
    rank         INT     NOT NULL,
    board_key    VARCHAR NOT NULL,
    period_start DATE    NOT NULL
);
