-- 핵심 사용자의 최근 2주(오늘 포함 14일, KST) 공부 시간 — 측정 세션, 순공 1분 이상 조각만(화면과 같은 기준)
-- 핵심 = core_users.sql과 같은 정의. 사람별 줄 아래에 핵심 평균·중앙값 줄이 붙는다
-- 오늘은 아직 진행 중이라 "14일로 나눈 하루 순공"은 조금 낮게 나온다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
core AS (
    SELECT d.user_id
    FROM (SELECT DISTINCT s.user_id, s.stat_date
          FROM study_session s
          CROSS JOIN params p
          WHERE s.source = 'MEASURED'
            AND s.focus_sec >= 600
            AND s.stat_date BETWEEN p.today - 13 AND p.today) d
    CROSS JOIN params p
    JOIN users u ON u.id = d.user_id
    WHERE u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
    GROUP BY d.user_id
    HAVING COUNT(*) >= 6 OR COUNT(*) FILTER (WHERE d.stat_date >= p.today - 6) >= 3
),
per_user AS (
    SELECT c.user_id,
           COUNT(DISTINCT s.stat_date)        AS study_days,
           COUNT(s.id)                        AS sessions,
           COALESCE(SUM(s.focus_sec), 0)      AS focus_sec,
           COALESCE(SUM(s.study_sec), 0)      AS study_sec
    FROM core c
    CROSS JOIN params p
    LEFT JOIN study_session s ON s.user_id = c.user_id
                             AND s.source = 'MEASURED'
                             AND s.focus_sec >= 60
                             AND s.stat_date BETWEEN p.today - 13 AND p.today
    GROUP BY c.user_id
),
metrics AS (
    SELECT user_id,
           study_days,
           sessions,
           focus_sec / 60.0                                   AS focus_min,
           focus_sec / NULLIF(study_days, 0) / 60.0           AS focus_min_per_study_day,
           focus_sec / 14 / 60.0                              AS focus_min_per_day,
           focus_sec / NULLIF(sessions, 0) / 60.0             AS focus_min_per_session,
           study_sec / NULLIF(study_days, 0) / 60.0           AS study_min_per_study_day
    FROM per_user
),
rows AS (
    SELECT 1 AS ord, user_id::text AS who, study_days::numeric AS study_days, focus_min,
           focus_min_per_study_day, focus_min_per_day, focus_min_per_session, study_min_per_study_day
    FROM metrics
    UNION ALL
    SELECT 2, format('핵심 %s명 평균', COUNT(*)), AVG(study_days), AVG(focus_min),
           AVG(focus_min_per_study_day), AVG(focus_min_per_day), AVG(focus_min_per_session), AVG(study_min_per_study_day)
    FROM metrics
    UNION ALL
    SELECT 3, format('핵심 %s명 중앙값', COUNT(*)),
           percentile_cont(0.5) WITHIN GROUP (ORDER BY study_days)::numeric,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY focus_min)::numeric,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY focus_min_per_study_day)::numeric,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY focus_min_per_day)::numeric,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY focus_min_per_session)::numeric,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY study_min_per_study_day)::numeric
    FROM metrics
)
SELECT who                                                                    AS "사람",
       ROUND(study_days, 1)                                                   AS "공부한 날",
       format('%s:%s', floor(focus_min / 60)::int, lpad((floor(focus_min)::int % 60)::text, 2, '0'))
                                                                              AS "2주 순공 합(시:분)",
       ROUND(focus_min_per_study_day)                                         AS "공부한 날 하루 순공(분)",
       ROUND(focus_min_per_day)                                               AS "14일로 나눈 하루 순공(분)",
       ROUND(focus_min_per_session)                                           AS "세션당 순공(분)",
       ROUND(study_min_per_study_day)                                         AS "공부한 날 하루 총공부(분)"
FROM rows
ORDER BY ord, focus_min DESC;
