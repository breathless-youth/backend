-- BY-1124: 그룹 공부방 — 그룹·멤버·신청 (docs/superpowers/specs/2026-11-03-by1124-group-study-rooms-design.md)
-- study_group: 이름이 group이면 예약어라 피했다. 삭제는 deleted_at 표시(soft delete)이고, 초대코드는 비공개 그룹만 가진다.
--   초대코드 unique는 살아 있는 그룹끼리만이라, 삭제된 그룹의 코드는 다시 쓸 수 있다.
-- study_group_member: 멤버 한 번의 소속이 한 행이다. 나가거나 내보내지면 left_at·leave_reason을 채우고, 다시 들어오면 새 행이다.
--   살아 있는 소속은 (group_id, user_id)당 하나만(sgm_live_uidx). 내보낸 뒤 3일 제한은 KICKED 행의 left_at으로 본다.
-- study_group_application: 공개 그룹 신청. 답을 기다리는 신청(PENDING)은 같은 그룹에 한 사람당 하나만 둔다(sga_pending_uidx).
--   expires_at이 지난 PENDING은 만료 작업이 EXPIRED로 바꾸기 전에도 서비스가 "기다리는 신청"으로 세지 않는다.
CREATE TABLE study_group (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    description TEXT,
    visibility  VARCHAR(10) NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    category    VARCHAR(30) NOT NULL,
    owner_id    BIGINT      NOT NULL REFERENCES users (id),
    invite_code VARCHAR(6),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT study_group_public_no_code_chk CHECK (visibility = 'PRIVATE' OR invite_code IS NULL)
);
CREATE UNIQUE INDEX study_group_invite_code_uidx ON study_group (invite_code) WHERE deleted_at IS NULL;
CREATE INDEX study_group_owner_idx ON study_group (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX study_group_public_idx ON study_group (id) WHERE deleted_at IS NULL AND visibility = 'PUBLIC';

CREATE TABLE study_group_member (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT      NOT NULL REFERENCES study_group (id),
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    joined_at      TIMESTAMPTZ NOT NULL,
    joined_via     VARCHAR(20) NOT NULL CHECK (joined_via IN ('CREATED', 'APPLICATION', 'INVITE_LINK', 'INVITE_CODE')),
    notify_enabled BOOLEAN     NOT NULL DEFAULT false,
    left_at        TIMESTAMPTZ,
    leave_reason   VARCHAR(20) CHECK (leave_reason IN ('LEFT', 'KICKED', 'GROUP_DELETED')),
    CONSTRAINT sgm_left_reason_chk CHECK ((left_at IS NULL) = (leave_reason IS NULL))
);
CREATE UNIQUE INDEX sgm_live_uidx ON study_group_member (group_id, user_id) WHERE left_at IS NULL;
CREATE INDEX sgm_live_user_idx ON study_group_member (user_id) WHERE left_at IS NULL;
CREATE INDEX sgm_kicked_idx ON study_group_member (group_id, user_id, left_at) WHERE leave_reason = 'KICKED';

CREATE TABLE study_group_application (
    id           BIGSERIAL PRIMARY KEY,
    group_id     BIGINT      NOT NULL REFERENCES study_group (id),
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    introduction TEXT        NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELED', 'EXPIRED', 'GROUP_DELETED')),
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    answered_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX sga_pending_uidx ON study_group_application (group_id, user_id) WHERE status = 'PENDING';
CREATE INDEX sga_pending_expiry_idx ON study_group_application (expires_at) WHERE status = 'PENDING';
CREATE INDEX sga_pending_user_idx ON study_group_application (user_id) WHERE status = 'PENDING';
