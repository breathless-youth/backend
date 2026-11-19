-- BY-1322: 기본 모드(TIMER) 세션 출처 (docs/adr/0049-timer-study-session-source.md)
-- active_study_session.source: draft의 출처. 확정한 세션이 이 값을 이어받는다. 기존 draft는 모두 관리 모드다.
-- planner_record_edit.source: 기본 모드 기록을 플래너에서 고치면 TIMER가 들어온다.
--   V54가 이름 없이 만든 열 CHECK의 PostgreSQL 자동 이름은 planner_record_edit_source_check다(V56·V62·V70과 같은 방식).
-- 락 대기 상한: active_study_session에 열을 더하는 동안 체크포인트 UPSERT가 막히므로 5초 안에 못 잡으면 배포를 실패시킨다(V38과 같다).
SET LOCAL lock_timeout = '5s';
ALTER TABLE active_study_session ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MEASURED';
-- 새 CHECK를 NOT VALID로 먼저 걸고 VALIDATE로 기존 행을 따로 검사하는 순서다.
-- VALIDATE 자체는 ACCESS EXCLUSIVE 대신 SHARE UPDATE EXCLUSIVE만 잡아 검사 중에도 읽기·쓰기를 막지 않는다.
-- 다만 Flyway는 이 파일을 한 트랜잭션으로 돌리므로, 앞의 ADD CONSTRAINT가 잡은 ACCESS EXCLUSIVE가 커밋까지 남아 검사도 그 락 아래에서 돈다.
-- 그래서 이 파일 안에서는 DROP과 ADD를 한 문장으로 쓸 때와 락 크기가 같고, 검사 실패 시 옛 CHECK가 남은 채 전부 되돌아가는 점도 같다.
-- 락까지 줄이려면 VALIDATE를 다음 버전 마이그레이션으로 분리해 별도 트랜잭션에서 돌려야 한다.
-- planner_record_edit는 사용자가 고친 기록만 담아 작으므로, 이번에는 한 파일로 두고 최종 제약 이름만 V54와 맞춘다.
ALTER TABLE planner_record_edit
    ADD CONSTRAINT planner_record_edit_source_check_v84 CHECK (source IN ('MEASURED', 'MANUAL', 'TIMER')) NOT VALID;
ALTER TABLE planner_record_edit VALIDATE CONSTRAINT planner_record_edit_source_check_v84;
ALTER TABLE planner_record_edit DROP CONSTRAINT planner_record_edit_source_check;
ALTER TABLE planner_record_edit
    RENAME CONSTRAINT planner_record_edit_source_check_v84 TO planner_record_edit_source_check;
