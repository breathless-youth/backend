-- BY-698: 세션 시간 기록에서 할 일 축을 걷어낸다 (ADR-0021 갱신)
-- 측정 단위가 과목으로 확정돼 task_id는 채워지지 않는 컬럼이 됐다. 할 일은 체크리스트로 남고
-- study_task 테이블은 그대로다 — 지우는 것은 "할 일별 시간"뿐이다.
DROP INDEX IF EXISTS idx_study_session_subject_time_task;
ALTER TABLE study_session_subject_time DROP COLUMN task_id;
