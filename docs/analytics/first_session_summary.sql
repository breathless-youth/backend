-- ⑤ 첫 세션 요약 — ④와 같은 사람을 첫 5분 딴짓·첫 세션 순공·장소별로 묶어 재방문율 비교
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
first_sess AS (   -- 사람마다 처음 측정한 세션(조각)
    SELECT DISTINCT ON (s.user_id) s.id, s.user_id, s.stat_date, s.started_at, s.ended_at, s.focus_sec
    FROM study_session s
    WHERE s.source = 'MEASURED'
    ORDER BY s.user_id, s.started_at
),
target AS (   -- 첫 세션이 최근 4주 안이고, 그 뒤 7일을 다 지켜볼 수 있는 사람
    SELECT f.*, u.nickname
    FROM first_sess f
    JOIN users u ON u.id = f.user_id
    CROSS JOIN params p
    WHERE f.stat_date BETWEEN p.today - 27 AND p.today - 8
      AND u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
),
ev AS (
    SELECT t.user_id,
           COUNT(*) FILTER (WHERE e.status = 'PHONE')  AS phone,
           COUNT(*) FILTER (WHERE e.status = 'AWAY')   AS away,
           COUNT(*) FILTER (WHERE e.status = 'SLEEP')  AS sleep,
           COUNT(*) FILTER (WHERE e.status = 'DEVICE') AS device,
           COUNT(*) FILTER (WHERE e.status = 'PAUSE')  AS pause,
           COUNT(*) FILTER (WHERE e.status IN ('PHONE', 'AWAY', 'SLEEP', 'DEVICE')
                              AND e.started_at < t.started_at + interval '5 minutes') AS early_distractions,
           MIN(e.started_at) FILTER (WHERE e.status IN ('PHONE', 'AWAY', 'SLEEP', 'DEVICE'))  AS first_distraction_at
    FROM target t
    LEFT JOIN status_event e ON e.session_id = t.id
    GROUP BY t.user_id
),
back AS (   -- 첫 세션 다음 날부터 7일 안에 다시 공부한 날 수
    SELECT t.user_id, COUNT(DISTINCT s.stat_date) AS return_days
    FROM target t
    LEFT JOIN study_session s ON s.user_id = t.user_id
                             AND s.source = 'MEASURED'
                             AND s.focus_sec >= 60
                             AND s.stat_date BETWEEN t.stat_date + 1 AND t.stat_date + 7
    GROUP BY t.user_id
),
fs AS (
    SELECT t.user_id,
           t.nickname,
           t.stat_date,
           t.started_at,
           ROUND(EXTRACT(EPOCH FROM t.ended_at - t.started_at) / 60.0, 1) AS session_min,
           ROUND(t.focus_sec / 60.0, 1)                                   AS focus_min,
           EXISTS (SELECT 1 FROM room_participations rp
                   WHERE rp.user_id = t.user_id
                     AND rp.joined_at < t.ended_at
                     AND t.started_at < COALESCE(rp.left_at, now()))       AS social,
           ev.phone, ev.away, ev.sleep, ev.device, ev.pause,
           ev.early_distractions,
           EXTRACT(EPOCH FROM ev.first_distraction_at - t.started_at)::int AS sec_to_first_distraction,
           b.return_days
    FROM target t
    JOIN ev   ON ev.user_id = t.user_id
    JOIN back b ON b.user_id = t.user_id
)
, bucketed AS (
    SELECT fs.*,
           CASE WHEN early_distractions = 0 THEN 1
                WHEN early_distractions <= 2 THEN 2
                WHEN early_distractions <= 5 THEN 3
                ELSE 4 END AS early_bucket,
           CASE WHEN focus_min < 3 THEN 1
                WHEN focus_min < 10 THEN 2
                WHEN focus_min < 30 THEN 3
                ELSE 4 END AS focus_bucket
    FROM fs
),
rows AS (
    SELECT 1 AS ord, '첫 5분 딴짓 횟수' AS axis, early_bucket AS k,
           (ARRAY['0회', '1~2회', '3~5회', '6회 이상'])[early_bucket] AS label, b.*
    FROM bucketed b
    UNION ALL
    SELECT 2, '첫 세션 순공', focus_bucket,
           (ARRAY['3분 미만', '3~10분', '10~30분', '30분 이상'])[focus_bucket], b.*
    FROM bucketed b
    UNION ALL
    SELECT 3, '첫 세션 장소', CASE WHEN social THEN 2 ELSE 1 END,
           CASE WHEN social THEN '그룹 방' ELSE '혼자' END, b.*
    FROM bucketed b
)
SELECT axis                                                   AS "기준",
       label                                                  AS "구간",
       COUNT(*)                                               AS "인원",
       ROUND(100.0 * AVG((return_days > 0)::int))             AS "7일 안 재방문%",
       ROUND(100.0 * AVG((return_days >= 3)::int))            AS "7일 안 3일+%",
       percentile_cont(0.5) WITHIN GROUP (ORDER BY focus_min) AS "첫 세션 순공 중앙값(분)",
       percentile_cont(0.5) WITHIN GROUP (ORDER BY phone + away + sleep + device) AS "첫 세션 딴짓 중앙값"
FROM rows
GROUP BY ord, axis, k, label
ORDER BY ord, k;
