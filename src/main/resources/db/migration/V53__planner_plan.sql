-- BY-1162/BY-1169: 기존 id·완료 시각·세션 참조를 유지한다. 사용자 현지 05시 날짜다.
CREATE TABLE planner_series (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    subject_id BIGINT NOT NULL REFERENCES study_subject(id)
);
CREATE INDEX ix_planner_series_user ON planner_series(user_id);

CREATE TABLE planner_repeat_rule (
    id BIGSERIAL PRIMARY KEY,
    series_id BIGINT NOT NULL REFERENCES planner_series(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    weekdays INTEGER NOT NULL CHECK (weekdays BETWEEN 1 AND 127),
    CHECK (end_date IS NULL OR end_date >= start_date),
    UNIQUE(series_id, start_date)
);

CREATE TABLE planner_day_subject (
    user_id BIGINT NOT NULL REFERENCES users(id),
    plan_date DATE NOT NULL,
    subject_id BIGINT NOT NULL REFERENCES study_subject(id),
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id, plan_date, subject_id)
);

ALTER TABLE study_task ADD COLUMN planned_date DATE;
ALTER TABLE study_task ADD COLUMN series_id BIGINT REFERENCES planner_series(id) ON DELETE SET NULL;
ALTER TABLE study_task ADD COLUMN occurrence_date DATE;
ALTER TABLE study_task ADD COLUMN customized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE study_task ADD COLUMN carryover_dismissed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_planner_occurrence ON study_task(series_id, occurrence_date) WHERE series_id IS NOT NULL;
CREATE INDEX ix_planner_task_date ON study_task(subject_id, planned_date);

UPDATE study_task t SET planned_date =
    ((COALESCE(t.done_at, CURRENT_TIMESTAMP) AT TIME ZONE u.time_zone) - INTERVAL '5 hours')::date
FROM study_subject s JOIN users u ON u.id = s.user_id
WHERE t.subject_id = s.id AND t.planned_date IS NULL;

INSERT INTO planner_day_subject(user_id, plan_date, subject_id, sort_order)
SELECT DISTINCT s.user_id, t.planned_date, s.id, s.sort_order
FROM study_task t JOIN study_subject s ON s.id = t.subject_id
WHERE t.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- 구 앱/시더도 날짜 없는 새 행을 만들지 못하게 한다. 명시한 계획 날짜는 바꾸지 않는다.
CREATE FUNCTION planner_fill_task_date() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.planned_date IS NULL THEN
        SELECT ((COALESCE(NEW.done_at, CURRENT_TIMESTAMP) AT TIME ZONE u.time_zone) - INTERVAL '5 hours')::date
          INTO NEW.planned_date
          FROM study_subject s JOIN users u ON u.id = s.user_id WHERE s.id = NEW.subject_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER planner_task_date BEFORE INSERT ON study_task
FOR EACH ROW EXECUTE FUNCTION planner_fill_task_date();
ALTER TABLE study_task ALTER COLUMN planned_date SET NOT NULL;
