-- 사용자 기기의 IANA 시간대 (ADR-0041). 스트릭·그날 할 일·05시 하루·D-Day의 오늘을 이 시간대로 정한다.
-- 기존 사용자는 기본값이 지금 동작(KST)과 같아 따로 채우지 않는다
ALTER TABLE users ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul';
