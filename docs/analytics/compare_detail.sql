-- ① 사람별 상세 — 최근 2주(KST, 오늘 포함) 한 번이라도 공부한 사람 한 줄씩
-- 핵심 = 2주 중 10분+ 세션 있는 날 6일 이상 또는 1주 중 3일 이상, 비교 = 나머지
-- 기능 횟수 중 Amplitude 기반(앱 연 날·기록·랭킹·배경음·타임랩스·수동기록·간단 모드)은 어제까지만 적재돼 있다
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
SELECT grp                   AS "그룹",
       user_id,
       nickname              AS "닉네임",
       signup_date           AS "가입일",
       days_10min_2w         AS "2주 10분+ 일수",
       days_10min_1w         AS "1주 10분+ 일수",
       study_days_2w         AS "2주 공부일",
       app_open_days         AS "2주 앱 연 날",
       focus_hours_2w        AS "2주 순공(시간)",
       sessions              AS "세션 수",
       avg_session_min       AS "평균 세션 순공(분)",
       pauses_per_session    AS "세션당 일시정지",
       distractions_per_hour AS "순공 1시간당 딴짓",
       simple_mode_on        AS "간단 모드",
       nickname_changed      AS "닉네임 변경",
       category              AS "카테고리",
       dday                  AS "디데이",
       records               AS "기록 조작",
       ranking_views         AS "랭킹 조회",
       group_joins           AS "그룹 참여",
       group_focus_hours     AS "그룹 순공(시간)",
       ambient_changes       AS "배경음 변경",
       timelapse_created     AS "타임랩스 생성",
       timelapse_shared      AS "타임랩스 공유",
       manual_adds           AS "수동기록 추가"
FROM detail
ORDER BY grp = '핵심' DESC, days_10min_2w DESC, focus_hours_2w DESC;
