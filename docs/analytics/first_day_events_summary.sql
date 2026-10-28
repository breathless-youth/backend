-- ⑥ 첫날 이벤트 요약 — 첫날(첫 측정 세션 날, 세션이 없으면 가입한 날)이 최근 4주(오늘−27 ~ 오늘−8, KST)인 사람을
--    첫 세션 결과로 나눠, 그날 Amplitude 이벤트를 한 번이라도 남긴 사람의 비율(%)을 비교한다
--    분모는 그날 Amplitude 기록이 하나라도 있는 사람이다. 하루 단위라 첫 세션 뒤의 행동도 섞인다
WITH params AS (
    SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today
),
first_sess AS (   -- 사람마다 처음 측정한 세션(조각)
    SELECT DISTINCT ON (s.user_id) s.user_id, s.stat_date, s.focus_sec
    FROM study_session s
    WHERE s.source = 'MEASURED'
    ORDER BY s.user_id, s.started_at
),
cohort AS (
    SELECT u.id AS user_id,
           COALESCE(f.stat_date, (u.created_at AT TIME ZONE 'Asia/Seoul')::date) AS day0,
           f.focus_sec AS first_focus_sec
    FROM users u
    LEFT JOIN first_sess f ON f.user_id = u.id
    CROSS JOIN params p
    WHERE COALESCE(f.stat_date, (u.created_at AT TIME ZONE 'Asia/Seoul')::date) BETWEEN p.today - 27 AND p.today - 8
      AND u.status IS DISTINCT FROM 'DELETE'
      AND u.id NOT IN (14, 324)   -- 관리자 계정
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
grouped AS (
    SELECT c.user_id, c.day0,
           CASE WHEN c.first_focus_sec IS NULL      THEN 'g0'   -- 세션 없음
                WHEN c.first_focus_sec < 180
                 AND b.return_days = 0              THEN 'g1'   -- 3분 미만·안 옴
                WHEN c.first_focus_sec < 180        THEN 'g2'   -- 3분 미만·다시 옴
                WHEN c.first_focus_sec < 600        THEN 'g3'   -- 3~10분
                ELSE                                     'g4'   -- 10분 이상
           END AS grp
    FROM cohort c
    JOIN back b ON b.user_id = c.user_id
),
day0_ev AS (   -- 첫날 이벤트(사람·이벤트마다 한 줄)
    SELECT g.grp, g.user_id, a.event_key, a.count
    FROM grouped g
    JOIN amplitude_daily_event a ON a.user_id = g.user_id AND a.event_date = g.day0
),
sizes AS (
    SELECT g.grp,
           COUNT(*)                                                              AS members,
           COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM day0_ev e WHERE e.user_id = g.user_id)) AS tracked
    FROM grouped g
    GROUP BY g.grp
),
steps(ord, event_key, label) AS (VALUES   -- 첫 실행부터 세션 끝까지 흐름 순서
    ( 1, 'app_launched',                    '앱 실행'),
    ( 2, 'invite_deep_link_opened',         '초대 링크로 들어옴'),
    ( 3, 'focus_start_tapped',              '"집중 시작" 탭'),
    ( 4, 'guide_entered',                   '온보딩 가이드 진입'),
    ( 5, 'guide_step_viewed',               '가이드 단계 봄'),
    ( 6, 'guide_finished',                  '가이드 끝(완료·건너뛰기 합침)'),
    ( 7, 'camera_permission_gate_resolved', '카메라 권한 확인(허용·거부 합침)'),
    ( 8, 'permission_denied_viewed',        '카메라 권한 거부 안내'),
    ( 9, 'study_session_started',           '세션 화면 진입'),
    (10, 'vision_detector_ready',           'AI 준비 완료'),
    (11, 'vision_runtime_fallback',         'AI 메인 스레드로 전환'),
    (12, 'study_session_distracted',        '딴짓 감지'),
    (13, 'study_session_paused',            '일시정지'),
    (14, 'app_backgrounded',                '앱을 벗어남(백그라운드)'),
    (15, 'study_session_exit_requested',    '종료 버튼 누름'),
    (16, 'study_session_exit_cancelled',    '종료 취소'),
    (17, 'study_session_ended',             '세션 종료'),
    (18, 'session_notice_confirmed',        '1분 미만·자동 종료 안내 확인'),
    (19, 'study_result_confirmed',          '결과 화면 확인'),
    (20, 'social_room_entered',             '그룹 방 입장'),
    (21, 'error_retry_pressed',             '오류 화면 다시 시도'),
    (22, 'error_fallback_reloaded',         '오류 화면 새로고침'),
    (23, 'webview_load_failed',             '화면 로딩 실패')
),
rates AS (
    SELECT e.event_key, e.grp, COUNT(*) AS users,
           ROUND(100.0 * COUNT(*) / NULLIF(s.tracked, 0)) AS pct
    FROM day0_ev e
    JOIN sizes s ON s.grp = e.grp
    GROUP BY e.event_key, e.grp, s.tracked
),
keys AS (   -- 흐름 이벤트는 아무도 안 남겼어도 0으로 보인다
    SELECT event_key FROM steps
    UNION
    SELECT event_key FROM day0_ev
),
pivot AS (
    SELECT k.event_key,
           st.ord,
           st.label,
           COALESCE(SUM(r.users), 0)                     AS users_all,
           MAX(r.pct) FILTER (WHERE r.grp = 'g0')        AS g0,
           MAX(r.pct) FILTER (WHERE r.grp = 'g1')        AS g1,
           MAX(r.pct) FILTER (WHERE r.grp = 'g2')        AS g2,
           MAX(r.pct) FILTER (WHERE r.grp = 'g3')        AS g3,
           MAX(r.pct) FILTER (WHERE r.grp = 'g4')        AS g4
    FROM keys k
    LEFT JOIN steps st ON st.event_key = k.event_key
    LEFT JOIN rates r  ON r.event_key = k.event_key
    GROUP BY k.event_key, st.ord, st.label
)
SELECT ord AS "순서", "이벤트", "뜻", "세션 없음", "3분 미만·안 옴", "3분 미만·다시 옴", "3~10분", "10분 이상"
FROM (
    SELECT -2 AS ord, '인원' AS "이벤트", '첫날이 기간 안인 사람' AS "뜻", NULL::bigint AS users_all,
           MAX(members) FILTER (WHERE grp = 'g0') AS "세션 없음",
           MAX(members) FILTER (WHERE grp = 'g1') AS "3분 미만·안 옴",
           MAX(members) FILTER (WHERE grp = 'g2') AS "3분 미만·다시 옴",
           MAX(members) FILTER (WHERE grp = 'g3') AS "3~10분",
           MAX(members) FILTER (WHERE grp = 'g4') AS "10분 이상"
    FROM sizes
    UNION ALL
    SELECT -1, 'Amplitude 기록 있음', '아래 비율(%)의 분모(명)', NULL,
           MAX(tracked) FILTER (WHERE grp = 'g0'),
           MAX(tracked) FILTER (WHERE grp = 'g1'),
           MAX(tracked) FILTER (WHERE grp = 'g2'),
           MAX(tracked) FILTER (WHERE grp = 'g3'),
           MAX(tracked) FILTER (WHERE grp = 'g4')
    FROM sizes
    UNION ALL
    SELECT COALESCE(ord, 100), event_key, label, users_all,
           COALESCE(g0, 0), COALESCE(g1, 0), COALESCE(g2, 0), COALESCE(g3, 0), COALESCE(g4, 0)
    FROM pivot
    WHERE ord IS NOT NULL OR users_all >= 3   -- 흐름 밖 이벤트는 3명 이상 남긴 것만
) t
ORDER BY ord, users_all DESC NULLS FIRST, "이벤트";
