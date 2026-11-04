-- BY-1126: 탈퇴한 사람이 만든 방은 지우지 않고 created_by를 비운다 (ADR-0042). 다른 사람의 참가 기록이 그 방을 가리킨다.
-- 락 대기 상한: ALTER가 rooms에 ACCESS EXCLUSIVE를 요구하는데, 오래 걸리는 분석 쿼리가 먼저 잡고 있으면 그 뒤의 방 문장이 모두
--   ALTER 뒤에 줄을 서게 된다. 5초 안에 못 잡으면 배포가 실패하고 다시 시도하면 된다.
--   SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다(Flyway가 파일마다 트랜잭션으로 실행).
SET LOCAL lock_timeout = '5s';
ALTER TABLE rooms ALTER COLUMN created_by DROP NOT NULL;
