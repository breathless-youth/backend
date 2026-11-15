-- BY-1290 캐릭터를 포인트로 사기

-- price가 있으면 공부 시간이 아니라 포인트로 사서 여는 캐릭터다. 그때 unlock_focus_sec은 쓰지 않는다.
-- 어느 캐릭터를 얼마에 팔지는 기획이 정하면 UPDATE로 넣는다
ALTER TABLE mascot ADD COLUMN price integer CHECK (price > 0);

-- 포인트로 산 캐릭터
CREATE TABLE user_mascot_owned (
    user_id bigint NOT NULL REFERENCES users(id),
    mascot_code varchar(32) NOT NULL REFERENCES mascot(code),
    acquired_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, mascot_code)
);
