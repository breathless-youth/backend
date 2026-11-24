-- 프리미엄 사전 신청 실험 집계 (BY-1392, BY-1394). 읽기 전용, 이메일은 읽지 않는다.
-- psql 예: psql -v since="'2026-11-24'" -f docs/analytics/premium_waitlist.sql
-- 노출 수는 Amplitude(premium_fake_door_viewed)에 있고, 여기는 신청(서버 저장) 쪽이다.

-- A-1 가격별·위치별 신청 수
SELECT price, placement, count(*) AS applicants
FROM premium_waitlist
WHERE created_at >= :'since'
GROUP BY price, placement
ORDER BY price, placement;

-- A-2 14세 이상/미만 (가격별) — 이메일 보유 수는 파기 전에만 의미가 있다
SELECT price,
       count(*) FILTER (WHERE age_over14) AS over14,
       count(*) FILTER (WHERE NOT age_over14) AS under14,
       count(*) FILTER (WHERE email IS NOT NULL) AS with_email
FROM premium_waitlist
WHERE created_at >= :'since'
GROUP BY price
ORDER BY price;

-- A-3 기대 기능 분포 (고르지 않은 사람은 NONE)
SELECT price, COALESCE(expected_feature, 'NONE') AS expected_feature, count(*) AS applicants
FROM premium_waitlist
WHERE created_at >= :'since'
GROUP BY price, COALESCE(expected_feature, 'NONE')
ORDER BY price, applicants DESC;

-- -------------------------------------------------------------------------------------
-- B. TURN 원가 근거 (rtc_connection_stat)
-- -------------------------------------------------------------------------------------

-- B-1 시간 가중 TURN 중계 비율 — 연결(connection_id)별 첫~마지막 샘플 시각 차를 가중치로 쓴다.
-- 연결의 candidate_type은 마지막 샘플 기준이다. 샘플이 1개뿐이면 길이 0이라 빠진다(connections_all과 비교).
WITH conn AS (
    SELECT connection_id,
           extract(epoch FROM max(created_at) - min(created_at)) AS duration_sec,
           (array_agg(candidate_type ORDER BY created_at DESC))[1] AS candidate_type
    FROM rtc_connection_stat
    WHERE created_at >= :'since'
    GROUP BY connection_id
)
SELECT count(*) AS connections_all,
       count(*) FILTER (WHERE duration_sec > 0) AS connections_weighted,
       round(sum(duration_sec) FILTER (WHERE candidate_type = 'relay') / NULLIF(sum(duration_sec), 0)::numeric, 4)
           AS relay_time_ratio,
       round(count(*) FILTER (WHERE candidate_type = 'relay')::numeric / NULLIF(count(*), 0), 4) AS relay_conn_ratio
FROM conn;

-- B-2 relay 바이트 합 — 연결별로 is_final 행이 있으면 그 값, 없으면 누적 최대값(bytes_*는 누적이다)
WITH conn AS (
    SELECT connection_id,
           bool_or(candidate_type = 'relay') AS is_relay,
           COALESCE(max(bytes_received) FILTER (WHERE is_final), max(bytes_received), 0) AS bytes_received,
           COALESCE(max(bytes_sent) FILTER (WHERE is_final), max(bytes_sent), 0) AS bytes_sent
    FROM rtc_connection_stat
    WHERE created_at >= :'since'
    GROUP BY connection_id
)
SELECT count(*) FILTER (WHERE is_relay) AS relay_connections,
       COALESCE(sum(bytes_received) FILTER (WHERE is_relay), 0) AS relay_bytes_received,
       COALESCE(sum(bytes_sent) FILTER (WHERE is_relay), 0) AS relay_bytes_sent,
       round(COALESCE(sum(bytes_received) FILTER (WHERE is_relay), 0) / 1024.0 / 1024 / 1024, 3) AS relay_received_gib
FROM conn;
