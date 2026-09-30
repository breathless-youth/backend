-- BY-698: 과목 > 할 일과 세션 과목 시간 (ADR-0021)
-- 과목·할 일은 soft delete — 지워도 study_session_subject_time에 쌓인 시간 기록은 남는다.
CREATE TABLE study_subject (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) DEFERRABLE INITIALLY IMMEDIATE,
    name       VARCHAR(50) NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_study_subject_user_live ON study_subject (user_id) WHERE deleted_at IS NULL;

-- 할 일은 마감일이 없다. done_at이 오늘(KST)이면 체크된 채 보이고 다음 날부터 목록에서 숨는다.
CREATE TABLE study_task (
    id         BIGSERIAL    PRIMARY KEY,
    subject_id BIGINT       NOT NULL REFERENCES study_subject (id) DEFERRABLE INITIALLY IMMEDIATE,
    name       VARCHAR(100) NOT NULL,
    done_at    TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_study_task_subject_live ON study_task (subject_id) WHERE deleted_at IS NULL;

-- 세션 × 과목 × 할 일(없을 수 있음)의 시간. 자정 분할 조각마다 비례 배분된 행이 따로 생긴다.
-- 과목 누적 = subject_id 합, 할 일 누적 = task_id 합.
CREATE TABLE study_session_subject_time (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT    NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    subject_id BIGINT    NOT NULL REFERENCES study_subject (id),
    task_id    BIGINT    REFERENCES study_task (id),
    study_sec  INT       NOT NULL,
    focus_sec  INT       NOT NULL
);
CREATE INDEX idx_study_session_subject_time_session ON study_session_subject_time (session_id);
CREATE INDEX idx_study_session_subject_time_subject ON study_session_subject_time (subject_id);
CREATE INDEX idx_study_session_subject_time_task ON study_session_subject_time (task_id) WHERE task_id IS NOT NULL;

-- 진행중 스냅샷에도 항목별 시간을 함께 보관해 재접속 복구·자동 확정 때 되살린다.
ALTER TABLE active_study_session ADD COLUMN subject_times JSONB NOT NULL DEFAULT '[]';
