-- BY-455: 세션 복구 판별·확인 API — 자동 확정본을 홈 진입 시 한 번 보여준 뒤 재노출하지 않도록
-- 확인 시각을 남긴다. NULL이면 아직 사용자에게 보여주지 않은(미확인) 자동 확정본.
ALTER TABLE study_session ADD COLUMN recovery_acknowledged_at TIMESTAMPTZ;
