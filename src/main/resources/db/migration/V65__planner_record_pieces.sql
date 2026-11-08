-- 분 범위 과목 변경·삭제(BY-1204). pieces가 NULL이면 기존 단일 구간 수정본 규칙을 그대로 쓴다.
-- pieces: 현재 개인 기록에 실제로 남은 조각 [{"startedAt","endedAt","subjectId"}], 시작 순·서로 겹치지 않음.
-- revision: 조회 뒤 다른 기기에서 바뀐 기록을 덮어쓰지 않도록 하는 기록별 버전. 수정본이 없는 원본 기록은 0이다.
ALTER TABLE planner_record_edit
    ADD COLUMN pieces JSONB,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
