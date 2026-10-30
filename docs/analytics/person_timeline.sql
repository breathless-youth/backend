-- 한 사람의 날짜별 공부와 앱 사용 — 최근 4주(오늘 포함 28일, KST)
-- {{user_id}}는 Metabase 질문의 입력 칸이다 (psql로 돌릴 때는 번호로 바꾼다). Amplitude 이벤트는 어제까지만 있다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
days AS (
    SELECT generate_series(p.today - 27, p.today, interval '1 day')::date AS day
    FROM params p
),
study AS (
    SELECT s.stat_date                                                           AS day,
           COUNT(*) FILTER (WHERE s.source = 'MEASURED' AND s.focus_sec >= 60)     AS pieces,
           SUM(s.focus_sec) FILTER (WHERE s.source = 'MEASURED' AND s.focus_sec >= 60) AS focus_sec,
           MAX(s.focus_sec) FILTER (WHERE s.source = 'MEASURED')                   AS max_focus_sec,
           SUM(s.focus_sec) FILTER (WHERE s.source = 'MANUAL')                     AS manual_sec
    FROM study_session s
    WHERE s.user_id = {{user_id}}
    GROUP BY s.stat_date
),
rooms AS (   -- 실제로 들어간 방만 (joined_at 없는 것은 예약만)
    SELECT (rp.joined_at AT TIME ZONE 'Asia/Seoul')::date AS day, COUNT(DISTINCT rp.room_id) AS rooms
    FROM room_participations rp
    WHERE rp.user_id = {{user_id}}
      AND rp.joined_at IS NOT NULL
    GROUP BY 1
),
events AS (
    SELECT a.event_date                                                     AS day,
           SUM(a.count)                                                     AS total,
           string_agg(a.event_key || ' ' || a.count, ', ' ORDER BY a.count DESC, a.event_key) AS keys
    FROM amplitude_daily_event a
    WHERE a.user_id = {{user_id}}
    GROUP BY a.event_date
)
SELECT d.day                                                        AS "날짜",
       to_char(d.day, 'Dy')                                         AS "요일",
       COALESCE(st.pieces, 0)                                       AS "측정 조각(1분+)",
       ROUND(COALESCE(st.focus_sec, 0) / 60.0)                      AS "순공(분)",
       ROUND(COALESCE(st.max_focus_sec, 0) / 60.0)                  AS "가장 긴 세션(분)",
       COALESCE(st.max_focus_sec, 0) >= 300                         AS "공부한 날",
       ROUND(COALESCE(st.manual_sec, 0) / 60.0)                     AS "수동 기록(분)",
       COALESCE(r.rooms, 0)                                         AS "들어간 방",
       COALESCE(e.total, 0)                                         AS "Amplitude 이벤트",
       e.keys                                                       AS "이벤트별 횟수"
FROM days d
LEFT JOIN study st ON st.day = d.day
LEFT JOIN rooms r  ON r.day = d.day
LEFT JOIN events e ON e.day = d.day
ORDER BY d.day DESC;
