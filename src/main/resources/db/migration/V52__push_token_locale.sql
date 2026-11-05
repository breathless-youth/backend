-- 기기의 앱 언어. 알림 문구를 고를 때 쓴다(ADR-0035). 언어를 보내지 않는 구 앱과 기존 행은 지금처럼 한국어를 받도록 ko로 채운다
-- 락 대기 상한: ALTER가 push_token에 ACCESS EXCLUSIVE를 요구하는데, 오래 걸리는 분석 쿼리가 먼저 잡고 있으면 그 뒤의 토큰 문장이
--   모두 ALTER 뒤에 줄을 서게 된다. 5초 안에 못 잡으면 배포가 실패하고 다시 시도하면 된다.
--   SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다(Flyway가 파일마다 트랜잭션으로 실행).
SET LOCAL lock_timeout = '5s';
ALTER TABLE push_token ADD COLUMN locale varchar(35) NOT NULL DEFAULT 'ko';
