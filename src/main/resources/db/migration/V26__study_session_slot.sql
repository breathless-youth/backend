-- BY-828: 시간대 랭킹용 — 세션 조각의 순공을 시간대 구간(KST 04·07·12·18·22시)별로 나눠 둔다.
-- 세션 저장 트랜잭션에서 함께 쓰는 파생값이고(과목 구간과 같은 방식, ADR-0023 §6) 세션과 함께 대체·삭제된다
CREATE TABLE study_session_slot (
    session_id BIGINT  NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    slot       VARCHAR NOT NULL,   -- DAWN·MORNING·AFTERNOON·EVENING·NIGHT
    slot_date  DATE    NOT NULL,   -- 심야(22–04)는 시작한 날
    focus_sec  INT     NOT NULL,
    PRIMARY KEY (session_id, slot, slot_date)
);
CREATE INDEX idx_study_session_slot_date ON study_session_slot (slot_date, slot) INCLUDE (session_id, focus_sec);

-- 랭킹은 전체 사용자의 기간 합계를 낸다 — 기존 (user_id, stat_date) 인덱스로는 날짜 범위 스캔이 안 된다
CREATE INDEX idx_study_session_stat_date ON study_session (stat_date) INCLUDE (user_id, focus_sec, study_sec, ended_at);
