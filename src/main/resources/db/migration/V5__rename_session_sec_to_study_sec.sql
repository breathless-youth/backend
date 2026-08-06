-- session_sec은 서버가 endedAt-startedAt으로 계산해 넣던 값이었으나,
-- 이제 앱이 잰 총 공부 타이머 시간을 그대로 제출·신뢰하는 값(study_sec)으로 의미가 바뀐다 (ADR-0008).
ALTER TABLE "study_session" RENAME COLUMN "session_sec" TO "study_sec";
