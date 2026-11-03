-- BY-1124: 멤버 공부 시작 알림 (docs/superpowers/specs/2026-11-03-by1124-group-study-rooms-design.md)
-- group_study_alert: 받는 사람·그룹마다 마지막으로 보낸 때. 조건부 upsert(마지막 보낸 때가 1시간 전보다 이를 때만 갱신)로 서버가 여러 대여도
--   한 번만 나간다.
-- active_study_session.start_notified: 새 draft를 알림 대상으로 한 번만 꺼내려는 표시. 하트비트 UPSERT는 이 열을 쓰지 않아 새 행은 기본값
--   false로 들어오고, 꺼낸 행은 true가 된다.
-- 락 대기 상한: active_study_session에 열을 더하고 인덱스를 만드는 동안 하트비트 UPSERT가 막히므로, 오래 걸리는 분석 쿼리 뒤에서
--   기다리지 않고 5초 안에 못 잡으면 배포를 실패시킨다(다시 시도하면 된다). SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다.
SET LOCAL lock_timeout = '5s';
CREATE TABLE group_study_alert (
    receiver_id  BIGINT      NOT NULL REFERENCES users (id),
    group_id     BIGINT      NOT NULL REFERENCES study_group (id),
    last_sent_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (receiver_id, group_id)
);
-- 배포 시점에 이미 있던 draft는 알리지 않는다: 기존 행은 true로 채우고 새 행 기본값만 false로 바꾼다
ALTER TABLE active_study_session ADD COLUMN start_notified BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE active_study_session ALTER COLUMN start_notified SET DEFAULT false;
CREATE INDEX active_study_session_start_pending_idx ON active_study_session (id) WHERE NOT start_notified;
