-- 일별 가입·공부한 사람 수 — 최근 8주(오늘 포함 56일, KST). Metabase에서는 날짜를 가로축으로 한 선 그래프로 본다
-- 공부 = 측정 세션 기준. 1분+ = 화면에 기록이 보인 사람, 5분+ = 그날이 공부한 날(스트릭)로 잡힌 사람
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
days AS (
    SELECT generate_series(p.today - 55, p.today, interval '1 day')::date AS day
    FROM params p
),
members AS (   -- 분석 대상: 탈퇴·관리자 계정 제외
    SELECT u.id, (u.created_at AT TIME ZONE 'Asia/Seoul')::date AS signup_day
    FROM users u
    WHERE u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
),
study AS (
    SELECT s.stat_date                                                AS day,
           COUNT(DISTINCT s.user_id) FILTER (WHERE s.focus_sec >= 60)  AS over_1min,
           COUNT(DISTINCT s.user_id) FILTER (WHERE s.focus_sec >= 300) AS over_5min
    FROM study_session s
    JOIN members m ON m.id = s.user_id
    CROSS JOIN params p
    WHERE s.source = 'MEASURED'
      AND s.stat_date BETWEEN p.today - 55 AND p.today
    GROUP BY s.stat_date
)
SELECT d.day                                                      AS "날짜",
       (SELECT COUNT(*) FROM members m WHERE m.signup_day = d.day) AS "가입",
       COALESCE(st.over_1min, 0)                                  AS "1분+ 공부",
       COALESCE(st.over_5min, 0)                                 AS "5분+ 공부"
FROM days d
LEFT JOIN study st ON st.day = d.day
ORDER BY d.day;
