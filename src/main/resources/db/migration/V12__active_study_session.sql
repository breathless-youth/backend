-- BY-447: 진행중 세션 스냅샷(draft) 테이블 — 30초마다 UPSERT, 확정 시 삭제.
-- reported_at(클라 시계)은 확정 시 ended_at이 되고, last_seen_at(서버 시계)은 무응답 판정 기준이다.
CREATE TABLE active_study_session (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    reported_at  TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    study_sec    INT         NOT NULL,
    focus_sec    INT         NOT NULL,
    events       JSONB       NOT NULL DEFAULT '[]',
    CONSTRAINT uq_active_study_session_user_started UNIQUE (user_id, started_at)
);

ALTER TABLE active_study_session ADD FOREIGN KEY (user_id)
    REFERENCES users (id) DEFERRABLE INITIALLY IMMEDIATE;

-- 자동 확정본 표시 — true인 세션은 잠정 기록이라 늦은 최종 제출·재확정이 대체할 수 있다
ALTER TABLE study_session ADD COLUMN auto_finalized BOOLEAN NOT NULL DEFAULT false;
