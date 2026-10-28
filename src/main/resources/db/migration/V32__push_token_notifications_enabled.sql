-- 마지막 등록 때 OS 알림 권한이 켜져 있었는지 (BY-1029). 클릭률의 분모("표시될 수 있었던 발송")를 iOS까지 근사한다.
-- V1부터 테이블은 있었지만 쓰는 코드가 없어 행이 없을 것이다. 그래도 손으로 넣은 행이 있으면 NOT NULL 추가가 실패해
-- 서버가 뜨지 않으므로 false로 채운 뒤 기본값을 지운다 — 새 행은 지금처럼 값을 반드시 받는다.
ALTER TABLE push_token ADD COLUMN notifications_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE push_token ALTER COLUMN notifications_enabled DROP DEFAULT;
