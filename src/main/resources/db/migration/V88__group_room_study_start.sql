-- BY-1382: 공부방 자리마다 실제 공부 시작 알림을 한 번만 처리한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE room_participations ADD COLUMN group_start_notified BOOLEAN NOT NULL DEFAULT false;

-- 배포 시점에 이미 공부 중인 자리는 새 시작으로 알리지 않는다.
UPDATE room_participations p
   SET group_start_notified = true
  FROM rooms r
 WHERE r.id = p.room_id AND r.group_id IS NOT NULL
   AND r.closed_at IS NULL AND p.left_at IS NULL AND p.stomp_confirmed
   AND p.stomp_session_id IS NOT NULL AND p.camera_on AND NOT p.on_break;

CREATE INDEX room_participations_group_start_pending_idx ON room_participations (id)
    WHERE NOT group_start_notified AND left_at IS NULL AND stomp_confirmed AND stomp_session_id IS NOT NULL
      AND camera_on AND NOT on_break;

-- 롤링 배포 중 구버전 서버도 개인 공부 초안으로 알림을 보내지 않게 한다.
ALTER TABLE active_study_session ALTER COLUMN start_notified SET DEFAULT true;
UPDATE active_study_session SET start_notified = true WHERE NOT start_notified;
