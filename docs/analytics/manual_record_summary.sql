-- ⑨ 수동기록 리텐션 요약 — 10/17 출시 뒤 활동한 사람을 수동기록을 쓴 사람과 안 쓴 사람으로 나눠 그 뒤 다시 왔는지 비교
--    기준일 = 쓴 사람은 첫 수동기록 날, 안 쓴 사람은 출시 뒤 처음 활동한 날. 다음 날을 볼 수 있게 기준일이 그저께까지인 사람만
--    활동일 = 앱을 연 날(Amplitude) 또는 순공 1분 이상 측정한 날. Amplitude가 어제까지라 어제까지만 본다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today,
           DATE '2026-10-17'                       AS launch
),
manual_first AS (
    SELECT a.user_id, MIN(a.event_date) AS first_day
    FROM amplitude_daily_event a
    CROSS JOIN params p
    WHERE a.event_key IN ('manual_record_add', 'manual_record_edit', 'manual_record_delete', 'manual_record_overlap_blocked')
      AND a.event_date >= p.launch
    GROUP BY a.user_id
),
study_days AS (   -- 순공 1분 이상 측정한 날
    SELECT DISTINCT s.user_id, s.stat_date AS day
    FROM study_session s
    WHERE s.source = 'MEASURED' AND s.focus_sec >= 60
),
active_days AS (   -- 앱을 연 날 또는 측정한 날
    SELECT a.user_id, a.event_date AS day
    FROM amplitude_daily_event a
    WHERE a.event_key IN ('app_launched', 'app_foregrounded')
    UNION
    SELECT user_id, day FROM study_days
),
first_active AS (   -- 출시 뒤 처음 활동한 날
    SELECT ad.user_id, MIN(ad.day) AS first_day
    FROM active_days ad
    CROSS JOIN params p
    WHERE ad.day >= p.launch AND ad.day < p.today
    GROUP BY ad.user_id
),
core AS (   -- 지금 핵심인지 — ①과 같은 기준
    SELECT d.user_id,
           COUNT(*) >= 6 OR COUNT(*) FILTER (WHERE d.stat_date >= p.today - 6) >= 3 AS is_core
    FROM (SELECT DISTINCT s.user_id, s.stat_date
          FROM study_session s CROSS JOIN params p
          WHERE s.source = 'MEASURED' AND s.focus_sec >= 600
            AND s.stat_date BETWEEN p.today - 13 AND p.today) d
    CROSS JOIN params p
    GROUP BY d.user_id, p.today
),
cohort AS (
    SELECT u.id                                    AS user_id,
           mf.user_id IS NOT NULL                  AS used_manual,
           COALESCE(mf.first_day, fa.first_day)    AS anchor
    FROM users u
    LEFT JOIN manual_first mf ON mf.user_id = u.id
    LEFT JOIN first_active fa ON fa.user_id = u.id
    WHERE (mf.user_id IS NOT NULL OR fa.user_id IS NOT NULL)
      AND u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
),
scored AS (
    SELECT c.user_id,
           c.used_manual,
           (p.today - 1) - c.anchor AS observable_days,
           (SELECT COUNT(*) FROM study_days sd
             WHERE sd.user_id = c.user_id AND sd.day BETWEEN c.anchor - 14 AND c.anchor - 1) AS prior_study_days,
           EXISTS (SELECT 1 FROM active_days ad
                    WHERE ad.user_id = c.user_id AND ad.day = c.anchor + 1)                  AS next_day,
           (SELECT COUNT(*) FROM active_days ad
             WHERE ad.user_id = c.user_id AND ad.day BETWEEN c.anchor + 1 AND p.today - 1)   AS active_after,
           (SELECT COUNT(*) FROM study_days sd
             WHERE sd.user_id = c.user_id AND sd.day BETWEEN c.anchor + 1 AND p.today - 1)   AS study_after,
           COALESCE(k.is_core, false) AS is_core
    FROM cohort c
    CROSS JOIN params p
    LEFT JOIN core k ON k.user_id = c.user_id
    WHERE c.anchor <= p.today - 2
),
labeled AS (   -- 한 사람을 "전체" 줄과 "이전 2주 공부 있음/없음" 줄에 한 번씩 넣는다
    SELECT s.*, g.ord, g.grp
    FROM scored s
    CROSS JOIN LATERAL (VALUES
        (CASE WHEN s.used_manual THEN 1 ELSE 4 END,
         CASE WHEN s.used_manual THEN '수동기록 씀' ELSE '안 씀' END),
        (CASE WHEN s.used_manual THEN 2 ELSE 5 END + CASE WHEN s.prior_study_days > 0 THEN 0 ELSE 1 END,
         CASE WHEN s.prior_study_days > 0 THEN '  └ 이전 2주 측정 공부 있음' ELSE '  └ 이전 2주 측정 공부 없음' END)
    ) g(ord, grp)
)
SELECT grp                                                                         AS "그룹",
       COUNT(*)                                                                    AS "인원",
       percentile_cont(0.5) WITHIN GROUP (ORDER BY prior_study_days)               AS "이전 2주 공부일(중앙값)",
       ROUND(100.0 * AVG(next_day::int))                                           AS "다음 날 다시 옴(%)",
       ROUND(100.0 * SUM(active_after) / NULLIF(SUM(observable_days), 0))          AS "이후 활동일 비율(%)",
       ROUND(100.0 * SUM(study_after) / NULLIF(SUM(observable_days), 0))           AS "이후 측정 공부일 비율(%)",
       ROUND(100.0 * AVG(is_core::int))                                            AS "지금 핵심(%)",
       ROUND(AVG(observable_days), 1)                                              AS "평균 지켜본 날 수"
FROM labeled
GROUP BY ord, grp
ORDER BY ord;
