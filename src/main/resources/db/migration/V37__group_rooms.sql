-- BY-1124: 그룹 공부방 — 초대코드 방과 같은 rooms·room_participations를 쓴다 (예약·리스·정리·재접속을 그대로 다시 쓴다)
-- rooms: 초대코드 방은 invite_code만, 그룹 공부방은 group_id만 가진다(둘 중 정확히 하나). 그룹마다 열린 방은 하나다.
--   열린 방 unique(rooms_open_group_uidx)가 동시에 처음 들어오는 두 멤버의 방 열기를 하나로 모은다(INSERT ... ON CONFLICT DO NOTHING).
-- room_participations: 마이크·휴식 표시값. 그룹 공부방에서만 읽고 쓰고, 초대코드 방에서는 기본값 그대로 둔다.
--   leave_reason은 varchar(20)에 CHECK가 없어 새 사유(KICKED, GROUP_LEFT)는 스키마를 바꾸지 않는다.
ALTER TABLE rooms ALTER COLUMN invite_code DROP NOT NULL;
ALTER TABLE rooms ADD COLUMN group_id BIGINT REFERENCES study_group (id);
ALTER TABLE rooms ADD CONSTRAINT rooms_code_xor_group_chk CHECK ((invite_code IS NULL) <> (group_id IS NULL));
CREATE UNIQUE INDEX rooms_open_group_uidx ON rooms (group_id) WHERE closed_at IS NULL AND group_id IS NOT NULL;
ALTER TABLE room_participations ADD COLUMN mic_on BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE room_participations ADD COLUMN on_break BOOLEAN NOT NULL DEFAULT false;
