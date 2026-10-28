-- ④ 첫 세션 — 처음 측정한 세션이 최근 4주(오늘−27 ~ 오늘−8, KST)인 사람: 첫 세션의 딴짓 감지와 7일 안 재방문
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
SELECT user_id,
       nickname                  AS "닉네임",
       stat_date                 AS "첫 세션 날",
       to_char(started_at AT TIME ZONE 'Asia/Seoul', 'HH24:MI') AS "시작",
       social                    AS "그룹 방",
       session_min               AS "세션 길이(분)",
       focus_min                 AS "순공(분)",
       early_distractions        AS "첫 5분 딴짓",
       sec_to_first_distraction  AS "첫 딴짓까지(초)",
       phone                     AS "휴대폰",
       away                      AS "자리 비움",
       sleep                     AS "졸음",
       device                    AS "기기",
       pause                     AS "일시정지",
       return_days               AS "7일 안 재방문 일수"
FROM fs
ORDER BY return_days DESC, focus_min DESC;
