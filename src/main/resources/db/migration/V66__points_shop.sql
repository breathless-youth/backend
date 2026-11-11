-- BY-1241 포인트·꾸미기 상점 1단계

CREATE TABLE point_account (
    user_id bigint PRIMARY KEY REFERENCES users(id),
    balance integer NOT NULL DEFAULT 0 CHECK (balance >= 0)
);

-- 잔액을 바꾸는 모든 일은 여기 한 줄을 남긴다. (user_id, reason, ref_key)가 같은 지급·차감은 한 번만 들어간다
CREATE TABLE point_ledger (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    amount integer NOT NULL CHECK (amount <> 0),
    fund varchar(8) NOT NULL DEFAULT 'FREE' CHECK (fund IN ('FREE', 'PAID')),
    reason varchar(32) NOT NULL,
    ref_key varchar(128) NOT NULL,
    label varchar(100),
    stat_date date,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_point_ledger_ref UNIQUE (user_id, reason, ref_key)
);
CREATE INDEX idx_point_ledger_user_id ON point_ledger (user_id, id DESC);
CREATE INDEX idx_point_ledger_day ON point_ledger (user_id, stat_date) WHERE stat_date IS NOT NULL;

CREATE TABLE mascot (
    code varchar(32) PRIMARY KEY,
    unlock_focus_sec integer NOT NULL CHECK (unlock_focus_sec >= 0),
    sort_order integer NOT NULL
);

-- 이름과 그림은 앱이 갖고 있다. 해금 기준은 예시 값이고 기획이 정하면 UPDATE로 바꾼다
INSERT INTO mascot (code, unlock_focus_sec, sort_order) VALUES
    ('BOOK', 0, 1),
    ('PLANNER', 18000, 2),
    ('STOPWATCH', 54000, 3),
    ('LAMP', 108000, 4),
    ('PENCIL', 216000, 5),
    ('LOCK', 360000, 6),
    ('CAMCORDER', 540000, 7);

CREATE TABLE user_mascot (
    user_id bigint PRIMARY KEY REFERENCES users(id),
    selected_mascot_code varchar(32) NOT NULL REFERENCES mascot(code)
);

-- 축하 화면을 이미 본 마스코트
CREATE TABLE user_mascot_seen (
    user_id bigint NOT NULL REFERENCES users(id),
    mascot_code varchar(32) NOT NULL REFERENCES mascot(code),
    PRIMARY KEY (user_id, mascot_code)
);

CREATE TABLE shop_item (
    id bigserial PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    -- 종류를 더하면(플래너 꾸미기 등) 이 CHECK를 넓힌다
    category varchar(24) NOT NULL CHECK (category IN ('MASCOT_ACCESSORY', 'TIMER_BACKGROUND')),
    mascot_code varchar(32) REFERENCES mascot(code),
    slot varchar(8) CHECK (slot IN ('HEAD', 'FACE', 'BODY')),
    tag varchar(32),
    name varchar(60) NOT NULL,
    -- 다른 언어 이름. 키는 주 언어(en, ja, zh)이고 없는 언어는 name(한국어)으로 보여 준다
    names jsonb NOT NULL DEFAULT '{}'::jsonb,
    image_url varchar(500),
    price integer CHECK (price > 0),
    unlock_type varchar(24) CHECK (unlock_type IN ('STREAK_DAYS', 'TOTAL_FOCUS_HOURS')),
    unlock_value integer CHECK (unlock_value > 0),
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    -- 포인트로 사는 상품이거나 조건으로 받는 보상 상품이거나 둘 중 하나다
    CONSTRAINT ck_shop_item_price_or_unlock
        CHECK ((price IS NOT NULL AND unlock_type IS NULL AND unlock_value IS NULL)
            OR (price IS NULL AND unlock_type IS NOT NULL AND unlock_value IS NOT NULL)),
    CONSTRAINT ck_shop_item_accessory
        CHECK ((category = 'MASCOT_ACCESSORY') = (mascot_code IS NOT NULL AND slot IS NOT NULL))
);

CREATE TABLE user_item (
    user_id bigint NOT NULL REFERENCES users(id),
    item_id bigint NOT NULL REFERENCES shop_item(id),
    acquired_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);

CREATE TABLE user_mascot_accessory (
    user_id bigint NOT NULL REFERENCES users(id),
    mascot_code varchar(32) NOT NULL REFERENCES mascot(code),
    slot varchar(8) NOT NULL CHECK (slot IN ('HEAD', 'FACE', 'BODY')),
    item_id bigint NOT NULL REFERENCES shop_item(id),
    PRIMARY KEY (user_id, mascot_code, slot)
);

CREATE TABLE user_decoration (
    user_id bigint PRIMARY KEY REFERENCES users(id),
    timer_background_item_id bigint REFERENCES shop_item(id)
);
