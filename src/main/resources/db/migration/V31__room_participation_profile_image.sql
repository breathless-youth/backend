-- 방 안 프로필은 입장 시점 값이다 — 닉네임처럼 사진 키도 입장 때 users.profile_image에서 복사한다
ALTER TABLE room_participations ADD COLUMN profile_image varchar;
