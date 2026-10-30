-- BY-1053: 날짜별 완료 기록 (ADR-0038)
-- "무엇(target_type·target_id)을 어느 날짜(completed_date)에 완료했다" 한 행. 지금은 과목(SUBJECT)만 쓰고, 할 일 반복이 생기면
-- TASK를 같은 표에 둔다 — 그래서 target_id에 FK를 걸지 않는다(대상 표가 종류마다 다르다). 완료 취소는 행 삭제다.
-- completed_date는 홈 날짜 줄의 달력 날짜이고 하루는 05시~다음 날 05시(KST)다 — 날짜 계산은 서비스가 한다.
-- 유니크가 같은 날 같은 대상의 중복 완료를 막고, (user_id, completed_date) 선두라 "그날 완료한 것" 조회가 이 인덱스를 탄다.
CREATE TABLE daily_completion (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    target_type    VARCHAR(16) NOT NULL,
    target_id      BIGINT      NOT NULL,
    completed_date DATE        NOT NULL,
    completed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_daily_completion UNIQUE (user_id, completed_date, target_type, target_id)
);
