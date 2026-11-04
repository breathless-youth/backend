-- 세션을 저장할 때 쓴 IANA 시간대 (ADR-0042). 자정 분할·stat_date·구간 slot_date가 이 시간대 기준이다.
-- 기존 세션은 모두 KST로 계산됐으므로 기본값이 맞다
ALTER TABLE study_session ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul';
