-- 날짜별 완료 할 일 조회 (ADR-0026): 과목별로 완료 시각 범위를 읽는다. 지운 할 일도 읽으므로
-- idx_study_task_subject_live(WHERE deleted_at IS NULL)는 쓸 수 없다. 미완료 행은 조회 대상이 아니라 뺀다.
CREATE INDEX idx_study_task_subject_done ON study_task (subject_id, done_at) WHERE done_at IS NOT NULL;
