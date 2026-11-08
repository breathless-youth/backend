-- 개인 플래너의 기록 수정본·삭제표식·새 수동 기록. 원본 세션·이벤트·랭킹 구간은 변경하지 않는다.
CREATE TABLE planner_record_edit (
    user_id BIGINT NOT NULL REFERENCES users(id),
    record_key VARCHAR(80) NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('MEASURED', 'MANUAL')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    subject_id BIGINT REFERENCES study_subject(id),
    deleted BOOLEAN NOT NULL DEFAULT false,
    preserve_original_totals BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, record_key),
    CONSTRAINT ck_planner_record_edit_span CHECK (deleted OR (ended_at > started_at AND ended_at - started_at <= interval '24 hours'))
);
CREATE INDEX idx_planner_record_edit_window ON planner_record_edit(user_id, started_at) WHERE NOT deleted;

-- 성공과 업무 오류 모두 고정 응답을 보관한다. 사용자 advisory lock 안에서 확인·저장한다.
CREATE TABLE planner_record_operation (
    user_id BIGINT NOT NULL REFERENCES users(id),
    operation_id VARCHAR(100) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    PRIMARY KEY (user_id, operation_id)
);
