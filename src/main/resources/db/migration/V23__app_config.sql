-- 재배포 없이 SQL로 바꾸는 운영 설정 (ADR-0027). 값은 문자열이고, on/off 키는 'true'일 때만 켜진 것으로 본다.
-- 인터뷰 모집(BY-833) 키는 꺼진 채로 넣어 두고 운영에서 UPDATE로 켠다.
CREATE TABLE "app_config" (
    "config_key" varchar(100) PRIMARY KEY,
    "config_value" text NOT NULL,
    "description" text,
    "updated_at" timestamptz NOT NULL DEFAULT now()
);

INSERT INTO "app_config" ("config_key", "config_value", "description") VALUES
    ('interview.enabled', 'false', '인터뷰 모집 전체 — 끄면 1·2번 공지, 완료 화면 카드, 설정 입구가 모두 빠진다'),
    ('interview.card.enabled', 'false', '완료 화면 카드(3번 그룹)'),
    ('interview.card.url', '', '카드의 구글폼 미리 채운 링크 (source=g3_complete)'),
    ('interview.settings.enabled', 'false', '설정의 인터뷰 신청하기 행'),
    ('interview.settings.url', '', '설정 행의 구글폼 미리 채운 링크 (source=settings)');
