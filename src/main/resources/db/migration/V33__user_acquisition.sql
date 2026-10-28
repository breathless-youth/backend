-- 사용자마다 처음 들어온 경로 한 행 (BY-1066, ADR-0036). 행이 있으면 이미 저장된 것이고 덮어쓰지 않는다
CREATE TABLE user_acquisition (
    user_id             bigint PRIMARY KEY REFERENCES users (id),
    platform            varchar(16)  NOT NULL,
    source              varchar(16)  NOT NULL,
    install_referrer    text,
    deep_link_ad        varchar(64),
    utm_source          varchar,
    utm_medium          varchar,
    utm_campaign        varchar,
    invite_code         varchar(4),
    -- 메타 필드명은 광고 계층과 한 단계 어긋나 있다(campaign_group = 캠페인, campaign = 광고 세트, adgroup = 광고).
    -- 아래 컬럼은 메타 광고 관리자 기준 이름이다
    meta_campaign_id    varchar,
    meta_campaign_name  varchar,
    meta_adset_id       varchar,
    meta_adset_name     varchar,
    meta_ad_id          varchar,
    meta_ad_name        varchar,
    meta_payload        jsonb,
    captured_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL
);
