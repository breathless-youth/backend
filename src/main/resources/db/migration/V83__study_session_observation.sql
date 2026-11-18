-- BY-1347: 세션 관찰 기록 — 하품처럼 순공과 무관한 관찰을 남긴다. 상태 이벤트(status_event)는 모두 순공을 멈추는 구간이라 따로 둔다.
-- 세션의 자식이라 세션이 지워지면 함께 사라진다. 자정 분할이면 시작 시각이 속한 조각 하나에 붙고, 기록끼리 겹쳐도 된다.
CREATE TABLE study_session_observation (
    session_id BIGINT      NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    type       VARCHAR     NOT NULL,   -- YAWN
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ             -- 길이가 없는 순간 기록이면 NULL
);
CREATE INDEX idx_study_session_observation_session ON study_session_observation (session_id);

-- 진행 중 세션 스냅샷 — 확정 시 study_session_observation 행이 된다
ALTER TABLE active_study_session ADD COLUMN observations JSONB NOT NULL DEFAULT '[]';
