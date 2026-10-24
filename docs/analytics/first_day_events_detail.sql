-- ⑦ 첫날 이벤트 상세 — ⑥과 같은 사람을 한 줄씩. 칸의 숫자는 그날 그 이벤트를 남긴 횟수(빈칸 = 0)
--    첫날 = 첫 측정 세션 날, 세션이 없으면 가입한 날 (최근 4주: 오늘−27 ~ 오늘−8, KST)
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
first_sess AS (   -- 사람마다 처음 측정한 세션(조각)
    SELECT DISTINCT ON (s.user_id) s.user_id, s.stat_date, s.started_at, s.focus_sec
    FROM study_session s
    WHERE s.source = 'MEASURED'
    ORDER BY s.user_id, s.started_at
),
cohort AS (
    SELECT u.id AS user_id,
           u.nickname,
           COALESCE(f.stat_date, (u.created_at AT TIME ZONE 'Asia/Seoul')::date) AS day0,
           u.created_at,
           f.started_at AS first_started_at,
           f.focus_sec  AS first_focus_sec
    FROM users u
    LEFT JOIN first_sess f ON f.user_id = u.id
    CROSS JOIN params p
    WHERE COALESCE(f.stat_date, (u.created_at AT TIME ZONE 'Asia/Seoul')::date) BETWEEN p.today - 27 AND p.today - 8
      AND u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
),
day0_sess AS (   -- 첫날 측정 세션 수(조각 기준)
    SELECT c.user_id, COUNT(s.id) AS sessions
    FROM cohort c
    LEFT JOIN study_session s ON s.user_id = c.user_id AND s.source = 'MEASURED' AND s.stat_date = c.day0
    GROUP BY c.user_id
),
back AS (   -- 첫날 다음 날부터 7일 안에 다시 공부한 날 수
    SELECT c.user_id, COUNT(DISTINCT s.stat_date) AS return_days
    FROM cohort c
    LEFT JOIN study_session s ON s.user_id = c.user_id
                             AND s.source = 'MEASURED'
                             AND s.focus_sec >= 60
                             AND s.stat_date BETWEEN c.day0 + 1 AND c.day0 + 7
    GROUP BY c.user_id
),
ev AS (
    SELECT c.user_id,
           COUNT(a.event_key)                                                             AS event_kinds,
           MAX(a.count) FILTER (WHERE a.event_key = 'app_launched')                       AS app_launched,
           MAX(a.count) FILTER (WHERE a.event_key = 'invite_deep_link_opened')            AS invite_opened,
           MAX(a.count) FILTER (WHERE a.event_key = 'focus_start_tapped')                 AS focus_start,
           MAX(a.count) FILTER (WHERE a.event_key = 'guide_entered')                      AS guide_entered,
           MAX(a.count) FILTER (WHERE a.event_key = 'guide_step_viewed')                  AS guide_steps,
           MAX(a.count) FILTER (WHERE a.event_key = 'guide_finished')                     AS guide_finished,
           MAX(a.count) FILTER (WHERE a.event_key = 'camera_permission_gate_resolved')    AS camera_gate,
           MAX(a.count) FILTER (WHERE a.event_key = 'permission_denied_viewed')           AS permission_denied,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_started')              AS session_started,
           MAX(a.count) FILTER (WHERE a.event_key = 'vision_detector_ready')              AS vision_ready,
           MAX(a.count) FILTER (WHERE a.event_key = 'vision_runtime_fallback')            AS vision_fallback,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_distracted')           AS distracted,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_paused')               AS paused,
           MAX(a.count) FILTER (WHERE a.event_key = 'app_backgrounded')                   AS backgrounded,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_exit_requested')       AS exit_requested,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_exit_cancelled')       AS exit_cancelled,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_session_ended')                AS session_ended,
           MAX(a.count) FILTER (WHERE a.event_key = 'session_notice_confirmed')           AS notice_confirmed,
           MAX(a.count) FILTER (WHERE a.event_key = 'study_result_confirmed')             AS result_confirmed,
           MAX(a.count) FILTER (WHERE a.event_key = 'social_room_entered')                AS social_entered,
           SUM(a.count) FILTER (WHERE a.event_key IN ('error_retry_pressed', 'error_fallback_reloaded',
                                                      'webview_load_failed'))             AS errors
    FROM cohort c
    LEFT JOIN amplitude_daily_event a ON a.user_id = c.user_id AND a.event_date = c.day0
    GROUP BY c.user_id
)
SELECT c.user_id,
       c.nickname                                                         AS "닉네임",
       CASE WHEN c.first_focus_sec IS NULL      THEN '0. 세션 없음'
            WHEN c.first_focus_sec < 180
             AND b.return_days = 0              THEN '1. 3분 미만·안 옴'
            WHEN c.first_focus_sec < 180        THEN '2. 3분 미만·다시 옴'
            WHEN c.first_focus_sec < 600        THEN '3. 3~10분'
            ELSE                                     '4. 10분 이상'
       END                                                                AS "그룹",
       c.day0                                                             AS "첫날",
       to_char(c.created_at AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')   AS "가입",
       to_char(c.first_started_at AT TIME ZONE 'Asia/Seoul', 'HH24:MI')   AS "첫 세션 시작",
       c.first_focus_sec                                                  AS "첫 세션 순공(초)",
       d.sessions                                                         AS "그날 세션 수",
       b.return_days                                                      AS "7일 안 재방문 일수",
       ev.event_kinds                                                     AS "이벤트 종류 수",
       ev.app_launched      AS "앱 실행",
       ev.invite_opened     AS "초대 링크",
       ev.focus_start       AS "집중 시작 탭",
       ev.guide_entered     AS "가이드 진입",
       ev.guide_steps       AS "가이드 단계",
       ev.guide_finished    AS "가이드 끝",
       ev.camera_gate       AS "카메라 권한 확인",
       ev.permission_denied AS "권한 거부 안내",
       ev.session_started   AS "세션 진입",
       ev.vision_ready      AS "AI 준비",
       ev.vision_fallback   AS "AI 전환",
       ev.distracted        AS "딴짓 감지",
       ev.paused            AS "일시정지",
       ev.backgrounded      AS "앱 벗어남",
       ev.exit_requested    AS "종료 버튼",
       ev.exit_cancelled    AS "종료 취소",
       ev.session_ended     AS "세션 종료",
       ev.notice_confirmed  AS "1분 미만·자동 종료 안내",
       ev.result_confirmed  AS "결과 확인",
       ev.social_entered    AS "그룹 방 입장",
       ev.errors            AS "오류"
FROM cohort c
JOIN day0_sess d ON d.user_id = c.user_id
JOIN back b      ON b.user_id = c.user_id
JOIN ev          ON ev.user_id = c.user_id
ORDER BY "그룹", b.return_days DESC, c.first_focus_sec DESC NULLS LAST, c.user_id;
