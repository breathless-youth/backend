-- BY-1301: 그룹장용 신청 목록의 신청 소개 글 번역 캐시. group_translation_cache와 같은 모양이고 신청 행을 기준으로 한다.
-- 신청 행이 지워지면(탈퇴) 번역도 같이 지운다.
CREATE TABLE group_application_translation_cache (
    application_id BIGINT NOT NULL REFERENCES study_group_application(id) ON DELETE CASCADE,
    field VARCHAR(16) NOT NULL CHECK (field IN ('INTRODUCTION')),
    target_locale VARCHAR(8) NOT NULL CHECK (target_locale IN ('ko','en','ja','zh-TW')),
    source_hash VARCHAR(64) NOT NULL,
    translated_text TEXT,
    provider VARCHAR(8) CHECK (provider IN ('GOOGLE','AZURE','DEEPL')),
    lease_token UUID,
    lease_until TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (application_id, field, target_locale),
    CHECK ((translated_text IS NULL) = (provider IS NULL))
);
