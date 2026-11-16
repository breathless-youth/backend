-- BY-1298: 같이 공부한 시간을 구하려고 "내가 있던 방의 다른 자리"를 지난 이력까지 찾는다. 기존 room_id 인덱스는 지금 자리
-- (left_at IS NULL)만 담아 이력 조회를 돕지 못한다.
SET lock_timeout = '5s';
CREATE INDEX rp_room_joined_idx ON room_participations (room_id, joined_at);
RESET lock_timeout;
