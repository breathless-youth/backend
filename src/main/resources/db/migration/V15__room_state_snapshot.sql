-- BY-626: 무중단 배포 시 룸 이어받기
-- room_id_seq: 방 ID를 메모리 카운터 대신 DB에서 발급해 태스크 재시작 후 /topic/room/{id} 충돌을 막는다.
-- 첫 롤아웃의 배포 겹침 구간에는 레거시 태스크(메모리 카운터, 1부터)가 아직 살아 있으므로 그 범위와 겹치지 않게 띄운다
CREATE SEQUENCE room_id_seq START WITH 1000000;

-- 종료 시점의 RoomService 메모리 스냅샷(한 행). 새 태스크가 첫 구독/입장에서 신선한 것만 읽어 복원한다
CREATE TABLE room_state_snapshot (
    id       smallint PRIMARY KEY CHECK (id = 1),
    payload  jsonb NOT NULL,
    saved_at timestamptz NOT NULL
);
