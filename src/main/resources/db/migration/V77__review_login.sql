-- BY-1300: 스토어 심사용 로그인 (ADR-0048). 심사 계정 하나만 아이디·비밀번호로 들어온다.
-- 행이 없거나 released_version이 NULL이면 꺼져 있다. 값은 배포 없이 SQL로 바꾼다.
CREATE TABLE review_login (
    id smallint PRIMARY KEY CHECK (id = 1),
    login_id varchar(64) NOT NULL,
    -- BCrypt 해시. 평문은 어디에도 두지 않는다
    password_hash varchar(100) NOT NULL,
    -- 스토어에 출시된 앱 버전(예: 26.46.0). 앱 버전이 이보다 높을 때만 켜진다
    released_version varchar(32),
    initial_points integer NOT NULL DEFAULT 0 CHECK (initial_points >= 0),
    -- 처음 상태로 줄 상점 상품 코드(쉼표로 구분). 없는 코드는 건너뛴다
    initial_item_codes text NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 틀린 시도. client는 요청이 온 IP다. 사용자 번호를 담지 않는다
CREATE TABLE review_login_failure (
    id bigserial PRIMARY KEY,
    client varchar(64) NOT NULL,
    failed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_login_failure_at ON review_login_failure (failed_at);
