-- BY-733: 과목 시간을 합계가 아니라 구간으로 받는다 (ADR-0023)
-- 앱은 과목 전환 시각(subjectSegments)만 보내고, 과목별 총공부·순공은 서버가 구간과 비공부 이벤트로 계산해 행에 같이 둔다
-- (과목 누적이 subject_id 합산 한 문장이 되도록). 과목 시트 앱이 배포 전이라 합계 행은 이관 없이 버린다.
DROP TABLE study_session_subject_time;

CREATE TABLE study_session_subject_segment (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    subject_id BIGINT      NOT NULL REFERENCES study_subject (id),
    started_at TIMESTAMPTZ NOT NULL,   -- 자정 분할 조각에는 잘린 구간이 담긴다
    ended_at   TIMESTAMPTZ NOT NULL,
    study_sec  INT         NOT NULL,   -- 서버 파생값: 길이 − PAUSE 겹침
    focus_sec  INT         NOT NULL    -- 서버 파생값: 길이 − 모든 이벤트 겹침
);
CREATE INDEX idx_study_session_subject_segment_session ON study_session_subject_segment (session_id);
CREATE INDEX idx_study_session_subject_segment_subject ON study_session_subject_segment (subject_id);

-- 진행중 스냅샷도 구간을 보관한다 — 옛 합계 JSON은 모양이 달라 버린다(배포 전)
ALTER TABLE active_study_session DROP COLUMN subject_times;
ALTER TABLE active_study_session ADD COLUMN subject_segments JSONB NOT NULL DEFAULT '[]';
