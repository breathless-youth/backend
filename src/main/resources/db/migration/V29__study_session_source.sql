-- 수동 기록(ADR-0031) — 측정한 세션과 사용자가 직접 넣은 세션을 한 테이블에서 가른다.
-- 기존 행은 전부 (자동)측정이라 기본값으로 채운다. 인덱스는 측정 뒤 필요하면 더한다
ALTER TABLE study_session ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MEASURED';
