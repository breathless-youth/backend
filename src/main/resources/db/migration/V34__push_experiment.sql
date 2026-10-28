-- 푸시 실험 배정과 발송 기록 (BY-1070, ADR-0037).

-- 실험 배정. control도 남겨야 비교할 수 있다. PK가 여러 태스크의 중복 배정을 막는다.
-- first_*는 첫 측정 세션 묶음(submission_started_at)이 생기면, day7_returned는 가입 7일 뒤가 집계되면 한 번 채우고 바꾸지 않는다
-- — 리포트가 읽는 Amplitude·가입자 창(61·68일)보다 실험이 길어져도 결과가 남는다
CREATE TABLE push_experiment_assignment (
    experiment       VARCHAR(50) NOT NULL,
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    arm              VARCHAR(20) NOT NULL,
    assigned_at      TIMESTAMPTZ NOT NULL,
    first_session_id BIGINT,
    first_session_at TIMESTAMPTZ,
    first_focus_sec  INT,
    day7_returned    BOOLEAN,
    PRIMARY KEY (experiment, user_id)
);

-- 발송 한 건. 보내기 전에 행을 넣어 자리를 잡고, 넣지 못하면 보내지 않는다. 유니크가 사람당 하루(KST) 1회 상한이다 —
-- 여러 태스크가 같은 틱에 돌아도 한 번만 나가고, FCM 호출이 실패해도 그날 다시 보내지 않는다
CREATE TABLE push_send (
    push_id      UUID        PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    type         VARCHAR(50) NOT NULL,
    send_date    DATE        NOT NULL,
    sent_at      TIMESTAMPTZ NOT NULL,
    fcm_accepted INT,
    CONSTRAINT uq_push_send_user_date UNIQUE (user_id, send_date)
);
