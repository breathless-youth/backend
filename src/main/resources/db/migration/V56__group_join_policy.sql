-- BY-1124: 공개 그룹은 신청 후 입장과 바로 입장을 고를 수 있다. 기존 그룹과 비공개 그룹은 신청 후 입장이다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE study_group
    ADD COLUMN join_policy TEXT NOT NULL DEFAULT 'APPROVAL',
    ADD CONSTRAINT study_group_join_policy_chk CHECK (join_policy IN ('APPROVAL', 'OPEN')),
    ADD CONSTRAINT study_group_private_approval_chk CHECK (visibility = 'PUBLIC' OR join_policy = 'APPROVAL');

ALTER TABLE study_group_member
    DROP CONSTRAINT study_group_member_joined_via_check,
    ADD CONSTRAINT study_group_member_joined_via_check
        CHECK (joined_via IN ('CREATED', 'APPLICATION', 'INVITE_LINK', 'INVITE_CODE', 'OPEN_JOIN'));

ALTER TABLE study_group_application
    DROP CONSTRAINT study_group_application_status_check,
    ADD CONSTRAINT study_group_application_status_check
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELED', 'EXPIRED', 'GROUP_DELETED', 'JOIN_POLICY_OPEN'));
