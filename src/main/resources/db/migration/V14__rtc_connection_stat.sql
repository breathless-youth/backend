-- BY-490: WebRTC 연결 통계 수집 테이블 — 릴레이 비율·coturn egress 측정용.
-- 프론트 getStats() 샘플(연결별 candidate 타입·bytes)을 적재한다.
-- 고빈도 텔레메트리라 FK 없이 비정규화한다 (peer_user_id는 상대 유저, 조인 불필요).
CREATE TABLE rtc_connection_stat (
    id             BIGSERIAL PRIMARY KEY,
    connection_id  VARCHAR     NOT NULL, -- 프론트가 PeerConnection당 발급하는 UUID (연결 단위 dedup 키)
    room_id        BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    peer_user_id   BIGINT,
    candidate_type VARCHAR     NOT NULL, -- host | srflx | prflx | relay
    relay_protocol VARCHAR,              -- relay일 때 udp | tcp | tls
    bytes_received BIGINT,               -- candidate pair 누적값 (relay면 coturn egress 추정 기준)
    bytes_sent     BIGINT,
    rtt_ms         INT,
    is_final       BOOLEAN     NOT NULL, -- 연결 종료 시 최종 샘플 여부
    client_at      TIMESTAMPTZ,          -- 클라 시계(epoch millis 유래)
    created_at     TIMESTAMPTZ NOT NULL  -- 서버 수신 시각
);

-- 집계용: 릴레이 비율·egress 산출(candidate_type 필터 + 기간)
CREATE INDEX idx_rtc_stat_type_created ON rtc_connection_stat (candidate_type, created_at);
-- connection 단위 dedup(최신 final 선택)용
CREATE INDEX idx_rtc_stat_connection ON rtc_connection_stat (connection_id, created_at);
