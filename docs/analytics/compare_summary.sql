-- ② 그룹별 요약 — ①과 같은 사람·같은 기준
-- 기능: "한 번이라도 쓴 사람 비율"과 "쓴 사람들의 횟수 중앙값" / 사용 방식: 그룹 전체 중앙값
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today,
           DATE '2026-10-16' AS ranking_from,     -- 랭킹 출시
           DATE '2026-10-16' AS timelapse_from,   -- 타임랩스 출시
           DATE '2026-10-17' AS manual_from       -- 수동기록 출시 (기록·배경음은 10/7이라 기간 전체)
),
sess AS (   -- 최근 2주 측정 세션, 화면과 같은 기준(순공 1분 이상 조각)
    SELECT s.id, s.user_id, s.stat_date, s.focus_sec
    FROM study_session s
    CROSS JOIN params p
    WHERE s.stat_date BETWEEN p.today - 13 AND p.today
      AND s.source = 'MEASURED'
      AND s.focus_sec >= 60
),
days AS (
    SELECT user_id, stat_date, MAX(focus_sec) >= 600 AS has_10min   -- 스트릭 기준
    FROM sess
    GROUP BY user_id, stat_date
),
study AS (
    SELECT d.user_id,
           COUNT(*) FILTER (WHERE d.has_10min)                                AS days_10min_2w,
           COUNT(*) FILTER (WHERE d.has_10min AND d.stat_date >= p.today - 6) AS days_10min_1w,
           COUNT(*)                                                           AS study_days_2w
    FROM days d
    CROSS JOIN params p
    GROUP BY d.user_id
),
habit AS (   -- AI 타이머 사용 방식
    SELECT s.user_id,
           COUNT(*)                   AS sessions,
           SUM(s.focus_sec)           AS focus_sec,
           AVG(s.focus_sec) / 60.0    AS avg_session_min,
           AVG(e.pauses)              AS pauses_per_session,
           SUM(e.distractions)        AS distractions
    FROM sess s
    LEFT JOIN LATERAL (
        SELECT COUNT(*) FILTER (WHERE ev.status = 'PAUSE')                             AS pauses,
               COUNT(*) FILTER (WHERE ev.status IN ('PHONE', 'DEVICE', 'AWAY', 'SLEEP')) AS distractions
        FROM status_event ev
        WHERE ev.session_id = s.id
    ) e ON true
    GROUP BY s.user_id
),
amp AS (   -- Amplitude 일별 횟수 (어제까지 적재됨)
    SELECT a.user_id,
           COUNT(DISTINCT a.event_date) FILTER (WHERE a.event_key IN ('app_launched', 'app_foregrounded')) AS app_open_days,
           SUM(a.count) FILTER (WHERE a.event_key LIKE 'records\_%')                                    AS records,
           SUM(a.count) FILTER (WHERE a.event_key = 'ranking_board_viewed' AND a.event_date >= p.ranking_from) AS ranking_views,
           SUM(a.count) FILTER (WHERE a.event_key = 'ambient_sound_changed')                             AS ambient_changes,
           SUM(a.count) FILTER (WHERE a.event_key = 'timelapse_video_created' AND a.event_date >= p.timelapse_from) AS timelapse_created,
           SUM(a.count) FILTER (WHERE a.event_key = 'timelapse_share_tapped' AND a.event_date >= p.timelapse_from)  AS timelapse_shared,
           SUM(a.count) FILTER (WHERE a.event_key = 'manual_record_add' AND a.event_date >= p.manual_from)  AS manual_adds,
           SUM(a.count) FILTER (WHERE a.event_key = 'session_simple_mode_toggled:on')                    AS simple_mode_on
    FROM amplitude_daily_event a
    CROSS JOIN params p
    WHERE a.event_date BETWEEN p.today - 13 AND p.today
    GROUP BY a.user_id
),
room AS (   -- 그룹(소셜 방) 참여
    SELECT rp.user_id,
           COUNT(*)                     AS group_joins,
           COALESCE(SUM(rp.focus_sec), 0) AS group_focus_sec
    FROM room_participations rp
    CROSS JOIN params p
    WHERE rp.joined_at >= (p.today - 13)::timestamp AT TIME ZONE 'Asia/Seoul'
    GROUP BY rp.user_id
),
detail AS (
    SELECT CASE WHEN st.days_10min_2w >= 6 OR st.days_10min_1w >= 3 THEN '핵심' ELSE '비교' END AS grp,
           u.id                                          AS user_id,
           u.nickname,
           (u.created_at AT TIME ZONE 'Asia/Seoul')::date AS signup_date,
           st.days_10min_2w,
           st.days_10min_1w,
           st.study_days_2w,
           COALESCE(am.app_open_days, 0)                 AS app_open_days,
           ROUND(h.focus_sec / 3600.0, 1)                AS focus_hours_2w,
           h.sessions,
           ROUND(h.avg_session_min)                      AS avg_session_min,
           ROUND(h.pauses_per_session, 1)                AS pauses_per_session,
           ROUND(h.distractions / (h.focus_sec / 3600.0), 1) AS distractions_per_hour,
           COALESCE(am.simple_mode_on, 0)                AS simple_mode_on,
           u.nickname !~ '^포메[0-9]+$'                    AS nickname_changed,   -- 기본값은 '포메'+숫자
           CASE u.category
               WHEN 'CSAT' THEN '수능'
               WHEN 'CIVIL_SERVICE' THEN '공무원'
               WHEN 'CERTIFICATE' THEN '자격증'
               WHEN 'JOB' THEN '취업'
               WHEN 'LANGUAGE' THEN '어학'
               WHEN 'PROFESSIONAL' THEN '전문직'
               WHEN 'ETC' THEN '기타'
           END                                           AS category,
           dd.title                                      AS dday,
           COALESCE(am.records, 0)                       AS records,
           COALESCE(am.ranking_views, 0)                 AS ranking_views,
           COALESCE(r.group_joins, 0)                    AS group_joins,
           ROUND(COALESCE(r.group_focus_sec, 0) / 3600.0, 1) AS group_focus_hours,
           COALESCE(am.ambient_changes, 0)               AS ambient_changes,
           COALESCE(am.timelapse_created, 0)             AS timelapse_created,
           COALESCE(am.timelapse_shared, 0)              AS timelapse_shared,
           COALESCE(am.manual_adds, 0)                   AS manual_adds
    FROM study st
    JOIN users u     ON u.id = st.user_id
    JOIN habit h     ON h.user_id = st.user_id
    LEFT JOIN amp am ON am.user_id = st.user_id
    LEFT JOIN room r ON r.user_id = st.user_id
    LEFT JOIN user_dday dd ON dd.user_id = st.user_id
    WHERE u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
)
, metric AS (   -- 기능은 "한 번이라도 쓴 비율 + 쓴 사람 중앙값", 사용 방식은 "전체 중앙값"
    SELECT d.grp, m.ord, m.label, m.kind, m.val
    FROM detail d
    CROSS JOIN LATERAL (VALUES
        (1,  '닉네임 변경',        'use',   d.nickname_changed::int::numeric),
        (2,  '카테고리 설정',      'use',   (d.category IS NOT NULL)::int::numeric),
        (3,  '디데이 설정',        'use',   (d.dday IS NOT NULL)::int::numeric),
        (4,  '기록 조작',          'use',   d.records::numeric),
        (5,  '랭킹 조회',          'use',   d.ranking_views::numeric),
        (6,  '그룹 참여',          'use',   d.group_joins::numeric),
        (7,  '배경음 변경',        'use',   d.ambient_changes::numeric),
        (8,  '타임랩스 생성',      'use',   d.timelapse_created::numeric),
        (9,  '타임랩스 공유',      'use',   d.timelapse_shared::numeric),
        (10, '수동기록 추가',      'use',   d.manual_adds::numeric),
        (11, '간단 모드',          'use',   d.simple_mode_on::numeric),
        (21, '2주 앱 연 날',       'level', d.app_open_days::numeric),
        (22, '2주 순공(시간)',     'level', d.focus_hours_2w),
        (23, '평균 세션 순공(분)', 'level', d.avg_session_min),
        (24, '세션당 일시정지',    'level', d.pauses_per_session),
        (25, '순공 1시간당 딴짓',  'level', d.distractions_per_hour)
    ) AS m(ord, label, kind, val)
)
SELECT label     AS "항목",
       core_rate AS "핵심 사용률%",
       comp_rate AS "비교 사용률%",
       core_med  AS "핵심 중앙값",
       comp_med  AS "비교 중앙값"
FROM (
    SELECT 0 AS ord, '인원(명)' AS label,
           COUNT(*) FILTER (WHERE grp = '핵심')::numeric AS core_rate,
           COUNT(*) FILTER (WHERE grp = '비교')::numeric AS comp_rate,
           NULL::numeric AS core_med,
           NULL::numeric AS comp_med
    FROM detail
    UNION ALL
    SELECT ord, label,
           CASE WHEN kind = 'use' THEN ROUND(100.0 * AVG((val > 0)::int) FILTER (WHERE grp = '핵심')) END,
           CASE WHEN kind = 'use' THEN ROUND(100.0 * AVG((val > 0)::int) FILTER (WHERE grp = '비교')) END,
           ROUND((percentile_cont(0.5) WITHIN GROUP (ORDER BY val)
               FILTER (WHERE grp = '핵심' AND (kind = 'level' OR val > 0)))::numeric, 1),
           ROUND((percentile_cont(0.5) WITHIN GROUP (ORDER BY val)
               FILTER (WHERE grp = '비교' AND (kind = 'level' OR val > 0)))::numeric, 1)
    FROM metric
    GROUP BY ord, label, kind
) t
ORDER BY ord;
