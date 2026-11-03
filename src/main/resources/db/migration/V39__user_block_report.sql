-- BY-1124: 신고·차단 (docs/superpowers/specs/2026-11-03-by1124-group-study-rooms-design.md)
-- user_block: 사용자 사이의 차단 — 그룹과 상관없이 걸려 어느 그룹 공부방에서든 영상·소리가 오가지 않는다. 같은 쌍은 한 행이고(다시 차단해도
--   그대로), 해제는 행을 지운다. "어느 쪽이든 차단한 사이"를 양쪽 방향으로 찾으려고 차단당한 사람 쪽 인덱스를 따로 둔다.
-- user_report: 공부방 멤버 신고(ROOM_MEMBER)와 그룹 신청 신고(APPLICATION). 운영자는 슬랙 알림과 DB/Metabase로 본다.
--   V1의 report 테이블은 쓰는 코드가 없고 컬럼(그룹·신청·종류)이 맞지 않아 새로 만든다.
-- 락 대기 상한: 새 테이블 FK가 users·study_group에 잠금을 걸 때 오래 걸리는 분석 쿼리 뒤에 줄을 서면 그 뒤 문장이 모두 쌓인다.
--   5초 안에 못 잡으면 배포가 실패하고 다시 시도하면 된다. SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다.
SET LOCAL lock_timeout = '5s';
CREATE TABLE user_block (
    blocker_id BIGINT      NOT NULL REFERENCES users (id),
    blocked_id BIGINT      NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT user_block_self_chk CHECK (blocker_id <> blocked_id)
);
CREATE INDEX user_block_blocked_idx ON user_block (blocked_id);

CREATE TABLE user_report (
    id             BIGSERIAL PRIMARY KEY,
    reporter_id    BIGINT      NOT NULL REFERENCES users (id),
    target_user_id BIGINT      NOT NULL REFERENCES users (id),
    type           VARCHAR(20) NOT NULL CHECK (type IN ('ROOM_MEMBER', 'APPLICATION')),
    group_id       BIGINT      NOT NULL REFERENCES study_group (id),
    application_id BIGINT REFERENCES study_group_application (id),
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX user_report_created_idx ON user_report (created_at);
