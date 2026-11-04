-- BY-1126: link에 쓴 소셜 ID 토큰은 한 번만 받는다 (ADR-0042). 새어 나간 토큰을 다른 비회원이 다시 넣어 남의 계정 토큰을 받는 것을 막는다.
-- 원문 대신 SHA-256 hex만 두고, 토큰의 exp가 지나면 지운다(그 뒤로는 프로바이더 검증이 먼저 거부한다). users를 가리키지 않는다
CREATE TABLE used_social_id_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_used_social_id_token_expires_at ON used_social_id_token (expires_at);
