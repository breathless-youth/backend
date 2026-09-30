-- BY-724: 과목 정렬 순서·색과 세션 제출 시 완료한 할 일 (ADR-0022)
-- DEFAULT는 원시 INSERT(테스트·수동)용이다. 실제 값은 서비스가 계산해 넣는다 — 순서는 살아있는 과목의 max+1,
-- 색은 그 사용자의 살아있는 과목이 가장 적게 쓴 인덱스.
ALTER TABLE study_subject ADD COLUMN sort_order  INT NOT NULL DEFAULT 0;
ALTER TABLE study_subject ADD COLUMN color_index INT NOT NULL DEFAULT 0;

-- 기존 행 백필 ① 순서: 사용자별 id 순으로 0..n-1. 삭제된 행도 번호를 받지만 목록엔 안 보이고 빈자리는 허용된다.
UPDATE study_subject s
SET sort_order = r.rn
FROM (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY id) - 1 AS rn
    FROM study_subject
) r
WHERE s.id = r.id;

-- 기존 행 백필 ② 색: 살아있는 행만 사용자별 id 순으로 세어 20(팔레트 크기)으로 나눈 나머지 — 하나씩 만들었을 때
-- "덜 쓴 색 배정"이 냈을 결과와 같다. 삭제된 행까지 세면 살아있는 과목끼리 색이 겹칠 수 있어(1번째·21번째만 남은 경우)
-- 제외한다. 삭제된 행은 DEFAULT 0 그대로다.
UPDATE study_subject s
SET color_index = r.rn % 20
FROM (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY id) - 1 AS rn
    FROM study_subject
    WHERE deleted_at IS NULL
) r
WHERE s.id = r.id;

-- 세션 제출 시 완료한 할 일 — 세션의 자식이라 세션이 지워지면 함께 사라진다.
-- 자정 분할이면 각 할 일은 done_at이 속한 조각 하나에만 붙는다. 할 일은 soft delete라 task FK는 CASCADE하지 않고
-- 지운 할 일의 기록도 남긴다(ADR-0021과 같은 정책).
CREATE TABLE study_session_task_done (
    session_id BIGINT NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    task_id    BIGINT NOT NULL REFERENCES study_task (id),
    PRIMARY KEY (session_id, task_id)
);
-- PK가 session_id 선두라 세션 기준 조회는 PK를 탄다. 할 일 기준(기록 탭 후속)만 별도 인덱스.
CREATE INDEX idx_study_session_task_done_task ON study_session_task_done (task_id);
