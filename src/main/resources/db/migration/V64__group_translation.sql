-- BY-1191: 원문은 study_group에 두고 번역/임차권과 무료 사용량만 영속화한다.
CREATE TABLE group_translation_cache (
    group_id BIGINT NOT NULL REFERENCES study_group(id) ON DELETE CASCADE,
    field VARCHAR(16) NOT NULL CHECK (field IN ('NAME', 'DESCRIPTION')),
    target_locale VARCHAR(8) NOT NULL CHECK (target_locale IN ('ko','en','ja','zh-TW')),
    source_hash VARCHAR(64) NOT NULL,
    translated_text TEXT,
    provider VARCHAR(8) CHECK (provider IN ('GOOGLE','AZURE','DEEPL')),
    lease_token UUID,
    lease_until TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, field, target_locale),
    CHECK ((translated_text IS NULL) = (provider IS NULL))
);
CREATE TABLE group_translation_provider_state (
    provider VARCHAR(8) PRIMARY KEY CHECK (provider IN ('GOOGLE','AZURE','DEEPL')),
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    suspended BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_until TIMESTAMPTZ,
    day_key VARCHAR(10),
    month_key VARCHAR(7)
);
INSERT INTO group_translation_provider_state(provider) VALUES ('GOOGLE'), ('AZURE'), ('DEEPL');
CREATE TABLE group_translation_usage (
    provider VARCHAR(8) NOT NULL REFERENCES group_translation_provider_state(provider),
    period_type VARCHAR(8) NOT NULL CHECK (period_type IN ('DAY','MONTH','LIFETIME')),
    period_key VARCHAR(10) NOT NULL,
    reserved_characters BIGINT NOT NULL CHECK (reserved_characters >= 0),
    PRIMARY KEY (provider, period_type, period_key)
);
