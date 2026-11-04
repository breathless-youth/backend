-- BY-1126: 탈퇴한 그룹장의 지운 그룹은 그룹장을 비운다 (ADR-0041). 탈퇴는 먼저 그 사람이 그룹장인 살아 있는 그룹을 가장 먼저 들어온 다른
--   멤버에게 넘기고(혼자면 그룹을 지운다), 그 뒤 남는 것은 지운 그룹뿐이다. 지운 그룹도 신고(user_report.group_id)·공부방(rooms.group_id)이
--   가리켜 행을 지우지 않고 owner_id만 NULL로 둔다. 살아 있는 그룹은 CHECK로 늘 그룹장이 있다.
-- 락 대기 상한: ALTER가 study_group에 ACCESS EXCLUSIVE를 요구하는데, 오래 걸리는 분석 쿼리가 먼저 잡고 있으면 그 뒤의 그룹 문장이 모두
--   ALTER 뒤에 줄을 서게 된다. 5초 안에 못 잡으면 배포가 실패하고 다시 시도하면 된다.
--   SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다(Flyway가 파일마다 트랜잭션으로 실행).
SET LOCAL lock_timeout = '5s';
ALTER TABLE study_group ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE study_group ADD CONSTRAINT study_group_live_owner_chk CHECK (deleted_at IS NOT NULL OR owner_id IS NOT NULL);
