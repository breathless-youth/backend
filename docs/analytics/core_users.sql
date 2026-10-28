-- 핵심 사용자 — 최근 2주(오늘 포함 14일, KST) 중 순공 10분+ 측정 세션이 있는 날이 6일+ 또는 최근 1주(7일) 중 3일+
-- Metabase 모델 "핵심 사용자"의 원본. 기간 안에 10분+ 세션이 하루라도 있는 사람을 한 줄씩, 핵심 여부와 함께 보여 준다
-- 오늘은 아직 끝나지 않은 날이라 아침에는 숫자가 하루 치 덜 잡힐 수 있다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
days AS (   -- 10분+ 세션이 있는 날 (스트릭·일일 리포트와 같은 기준, 자정 분할 조각은 합치지 않는다)
    SELECT DISTINCT s.user_id, s.stat_date
    FROM study_session s
    CROSS JOIN params p
    WHERE s.source = 'MEASURED'
      AND s.focus_sec >= 600
      AND s.stat_date BETWEEN p.today - 13 AND p.today
),
per_user AS (
    SELECT d.user_id,
           COUNT(*)                                            AS days_2w,
           COUNT(*) FILTER (WHERE d.stat_date >= p.today - 6)  AS days_1w,
           MAX(d.stat_date)                                    AS last_day
    FROM days d
    CROSS JOIN params p
    GROUP BY d.user_id
)
SELECT pu.user_id,
       u.nickname                                                AS "닉네임",
       (u.created_at AT TIME ZONE 'Asia/Seoul')::date            AS "가입일",
       pu.days_2w                                                AS "2주 공부일",
       pu.days_1w                                                AS "1주 공부일",
       pu.last_day                                               AS "마지막 공부일",
       pu.days_2w >= 6 OR pu.days_1w >= 3                        AS "핵심"
FROM per_user pu
JOIN users u ON u.id = pu.user_id
WHERE u.status IS DISTINCT FROM 'DELETE'
  AND u.id NOT IN (14, 324)   -- 관리자 계정
ORDER BY "핵심" DESC, pu.days_2w DESC, pu.user_id;
