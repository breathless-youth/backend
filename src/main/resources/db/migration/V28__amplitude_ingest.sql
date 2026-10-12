-- Amplitude Export 일 단위 적재 (BY-904, ADR-0030). 원본 이벤트는 저장하지 않는다.

-- 처리한 수신 시각 창(KST 날짜 하나). PK 선점으로 재실행·태스크 중복에도 한 번만 센다
CREATE TABLE amplitude_export_window (
    window_date  DATE        PRIMARY KEY,
    event_count  INT         NOT NULL,
    skipped      INT         NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 유저 × 발생일(KST) × 이벤트 키 횟수. 키는 event_type 또는 "event_type:값"(속성값 파생 키).
-- user_id에 외래키를 걸지 않는다 — Amplitude에는 탈퇴·타 환경 id가 섞일 수 있어 리포트가 users와 맞춰 거른다
CREATE TABLE amplitude_daily_event (
    user_id    BIGINT       NOT NULL,
    event_date DATE         NOT NULL,
    event_key  VARCHAR(120) NOT NULL,
    count      INT          NOT NULL,
    PRIMARY KEY (user_id, event_date, event_key)
);
CREATE INDEX idx_amplitude_daily_event_date ON amplitude_daily_event (event_date);

-- 유저 속성 최신값. 값마다 그 값을 본 시각을 따로 둬 더 늦게 본 non-null이 이긴다.
-- 광고 클릭 유입은 한 번이라도 보이면 true로 남는다
CREATE TABLE amplitude_user_attribute (
    user_id       BIGINT       PRIMARY KEY,
    utm_source    VARCHAR(200),
    utm_seen_at   TIMESTAMPTZ,
    ad_click      BOOLEAN      NOT NULL DEFAULT false,
    os_name       VARCHAR(50),
    os_seen_at    TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
