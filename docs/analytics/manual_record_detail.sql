-- ⑧ 수동기록 쓴 사람 — 10/17 출시 뒤 Amplitude 수동기록 이벤트(추가·수정·삭제·겹침 막힘)를 남긴 사람 한 줄씩
--    활동일 = 앱을 연 날(Amplitude) 또는 순공 1분 이상 측정한 날. Amplitude가 어제까지라 "이후"는 어제까지만 본다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today,
           DATE '2026-10-17'                       AS launch
),
manual_ev AS (
    SELECT a.user_id,
           MIN(a.event_date)                                                                 AS first_day,
           COUNT(DISTINCT a.event_date)                                                     AS manual_days,
           COALESCE(SUM(a.count) FILTER (WHERE a.event_key = 'manual_record_add'), 0)       AS adds,
           COALESCE(SUM(a.count) FILTER (WHERE a.event_key = 'manual_record_edit'), 0)      AS edits,
           COALESCE(SUM(a.count) FILTER (WHERE a.event_key = 'manual_record_delete'), 0)    AS deletes,
           COALESCE(SUM(a.count) FILTER (WHERE a.event_key = 'manual_record_overlap_blocked'), 0) AS blocked
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
db_manual AS (   -- DB에 남아 있는 수동 기록(지운 것은 빠짐)
    SELECT s.user_id, COUNT(*) AS pieces, SUM(s.focus_sec) AS focus_sec
    FROM study_session s
    WHERE s.source = 'MANUAL'
    GROUP BY s.user_id
),
core AS (   -- 지금 핵심인지 — ①과 같은 기준(최근 2주 중 10분 세션 있는 날 6일+ 또는 최근 1주 3일+)
    SELECT d.user_id,
           COUNT(*) >= 6 OR COUNT(*) FILTER (WHERE d.stat_date >= p.today - 6) >= 3 AS is_core
    FROM (SELECT DISTINCT s.user_id, s.stat_date
          FROM study_session s CROSS JOIN params p
          WHERE s.source = 'MEASURED' AND s.focus_sec >= 600
            AND s.stat_date BETWEEN p.today - 13 AND p.today) d
    CROSS JOIN params p
    GROUP BY d.user_id, p.today
)
SELECT m.user_id,
       u.nickname                                                          AS "닉네임",
       to_char(u.created_at AT TIME ZONE 'Asia/Seoul', 'MM-DD')            AS "가입",
       m.first_day                                                         AS "첫 수동기록 날",
       m.manual_days                                                       AS "수동기록 한 날 수",
       m.adds                                                              AS "추가",
       m.edits                                                             AS "수정",
       m.deletes                                                           AS "삭제",
       m.blocked                                                           AS "겹쳐서 막힘",
       COALESCE(dm.pieces, 0)                                              AS "남은 수동 기록 수",
       ROUND(COALESCE(dm.focus_sec, 0) / 3600.0, 1)                        AS "수동 기록 시간(h)",
       (SELECT COUNT(*) FROM study_days sd
         WHERE sd.user_id = m.user_id AND sd.day BETWEEN m.first_day - 14 AND m.first_day - 1) AS "이전 2주 측정 공부일",
       (p.today - 1) - m.first_day                                         AS "이후 지켜본 날 수",
       CASE WHEN m.first_day >= p.today - 1 THEN NULL
            ELSE EXISTS (SELECT 1 FROM active_days ad
                          WHERE ad.user_id = m.user_id AND ad.day = m.first_day + 1)
       END                                                                 AS "다음 날 다시 옴",
       (SELECT COUNT(*) FROM active_days ad
         WHERE ad.user_id = m.user_id AND ad.day BETWEEN m.first_day + 1 AND p.today - 1) AS "이후 활동일",
       (SELECT COUNT(*) FROM study_days sd
         WHERE sd.user_id = m.user_id AND sd.day BETWEEN m.first_day + 1 AND p.today - 1) AS "이후 측정 공부일",
       COALESCE(k.is_core, false)                                          AS "지금 핵심"
FROM manual_ev m
JOIN users u ON u.id = m.user_id
CROSS JOIN params p
LEFT JOIN db_manual dm ON dm.user_id = m.user_id
LEFT JOIN core k       ON k.user_id = m.user_id
WHERE u.status IS DISTINCT FROM 'DELETE'
  AND u.id NOT IN (14, 324)   -- 관리자 계정
ORDER BY m.first_day, m.user_id;
