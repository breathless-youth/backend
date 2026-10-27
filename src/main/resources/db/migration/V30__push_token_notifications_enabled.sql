-- 마지막 등록 때 OS 알림 권한이 켜져 있었는지 (BY-1029). 클릭률의 분모("표시될 수 있었던 발송")를 iOS까지 근사한다.
-- V1부터 테이블은 있었지만 쓰는 코드가 없어 행이 없다 — 기본값 없이 NOT NULL로 둔다.
ALTER TABLE push_token ADD COLUMN notifications_enabled boolean NOT NULL;
