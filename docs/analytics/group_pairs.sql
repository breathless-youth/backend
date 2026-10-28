-- ③ 같이 공부한 짝 — 최근 2주(KST) 같은 방에 같은 시간대에 있었던 두 사람과 함께한 날·시간
-- 지인끼리 들어와 같이 공부하는 무리를 찾는다. 겹친 시간 10분 이상인 짝만
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
rp AS (
    SELECT r.room_id, r.user_id, r.joined_at,
           LEAST(COALESCE(r.left_at, ro.closed_at, now()), now()) AS left_at   -- left_at NULL = 아직 자리에 있음
    FROM room_participations r
    JOIN rooms ro ON ro.id = r.room_id
    CROSS JOIN params p
    WHERE r.joined_at >= (p.today - 13)::timestamp AT TIME ZONE 'Asia/Seoul'
      AND r.user_id NOT IN (14, 324)   -- 관리자 계정
),
pairs AS (
    SELECT a.user_id AS user_a,
           b.user_id AS user_b,
           (GREATEST(a.joined_at, b.joined_at) AT TIME ZONE 'Asia/Seoul')::date AS day,
           EXTRACT(EPOCH FROM LEAST(a.left_at, b.left_at) - GREATEST(a.joined_at, b.joined_at)) AS overlap_sec
    FROM rp a
    JOIN rp b ON b.room_id = a.room_id
             AND a.user_id < b.user_id
             AND a.joined_at < b.left_at
             AND b.joined_at < a.left_at
)
SELECT p.user_a                                  AS "A",
       ua.nickname                               AS "A 닉네임",
       p.user_b                                  AS "B",
       ub.nickname                               AS "B 닉네임",
       COUNT(DISTINCT p.day)                     AS "함께한 날",
       ROUND(SUM(p.overlap_sec) / 3600.0, 1)     AS "함께한 시간",
       MIN(p.day)                                AS "처음",
       MAX(p.day)                                AS "마지막"
FROM pairs p
JOIN users ua ON ua.id = p.user_a
JOIN users ub ON ub.id = p.user_b
GROUP BY p.user_a, ua.nickname, p.user_b, ub.nickname
HAVING SUM(p.overlap_sec) >= 600
ORDER BY "함께한 시간" DESC;
