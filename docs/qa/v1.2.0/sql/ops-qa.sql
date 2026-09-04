-- =====================================================================================
-- 출시 전 백엔드 운영 QA 검증 쿼리 (BY-610)
-- 짝 문서: docs/qa/v1.2.0/2026-09-04-출시전-백엔드-운영-QA.md
--
-- 사용법 (dev EC2, docker compose):
--   docker compose exec postgres psql -U study -d study
--   \set uid  12          -- QA 기기 1의 userId (POST /api/users 응답)
--   \set uid2 13          -- QA 기기 2의 userId
--   \set since '2026-09-05 10:00+09'   -- 이번 QA 시작 시각 (그 이전 행은 무시)
--   \timing on
--   \i docs/qa/sql/ops-qa.sql  는 하지 말 것 — 섹션별로 복사해 실행한다.
--
-- 시각은 전부 KST로 보여준다 (컬럼은 timestamptz라 서버 시계와 무관).
-- =====================================================================================
SET TIME ZONE 'Asia/Seoul';

-- -------------------------------------------------------------------------------------
-- A. 준비·기준선
-- -------------------------------------------------------------------------------------

-- A-1 QA 유저 확인 (provider=DEVICE, provider_user_id=기기 UUID)
SELECT id, provider, provider_user_id, nickname, created_at
FROM users WHERE id IN (:uid, :uid2);

-- A-2 기준선 스냅샷 — 시나리오 전후로 실행해 증가분만 본다
SELECT
  (SELECT count(*) FROM study_session        WHERE user_id IN (:uid, :uid2)) AS sessions,
  (SELECT count(*) FROM status_event e JOIN study_session s ON s.id = e.session_id
                                       WHERE s.user_id IN (:uid, :uid2))     AS events,
  (SELECT count(*) FROM active_study_session WHERE user_id IN (:uid, :uid2)) AS drafts,
  (SELECT count(*) FROM rooms                WHERE created_by IN (:uid, :uid2)) AS rooms,
  (SELECT count(*) FROM room_participations  WHERE user_id IN (:uid, :uid2)) AS participations,
  (SELECT count(*) FROM rtc_connection_stat  WHERE user_id IN (:uid, :uid2)) AS rtc_samples;

-- -------------------------------------------------------------------------------------
-- B. 공부 세션
-- -------------------------------------------------------------------------------------

-- B-1 진행중 draft (스냅샷 30초마다 PUT /api/study-sessions/active)
--   기대: 유저당 최대 1행. reported_at·last_seen_at이 30초 주기로 전진, study_sec 단조 증가,
--         events JSONB가 누적 (PAUSE 구간 포함). 두 번 실행해 값이 바뀌는지 본다.
SELECT id, user_id, started_at, reported_at, last_seen_at,
       now() - last_seen_at AS since_last_seen,
       study_sec, focus_sec, jsonb_array_length(events) AS event_cnt
FROM active_study_session
WHERE user_id = :uid;

-- B-1b draft의 이벤트 목록 (앱에서 누른 일시정지·카메라 이탈과 대조)
SELECT e->>'status' AS status, e->>'startedAt' AS started_at, e->>'endedAt' AS ended_at
FROM active_study_session, jsonb_array_elements(events) e
WHERE user_id = :uid;

-- B-2 확정 세션 (정상 종료 POST /api/study-sessions, 또는 자동확정)
--   기대: 종료 직후 행 1개 추가, 같은 (user_id, started_at) draft는 삭제.
--         auto_finalized=false(앱 제출) / true(스케줄러). stat_date = started_at의 KST 날짜.
--         focus_sec <= study_sec, ended_at - started_at >= study_sec(일시정지 만큼 더 김).
SELECT id, stat_date, started_at, ended_at,
       extract(epoch FROM ended_at - started_at)::int AS wall_sec,
       study_sec, focus_sec, auto_finalized, submission_started_at, recovery_acknowledged_at
FROM study_session
WHERE user_id = :uid AND started_at >= :'since'
ORDER BY started_at DESC;

-- B-2b 확정 세션의 상태 이벤트 (앱 결과 화면의 일시정지 횟수와 대조)
--   기대: 이벤트 구간이 세션 [started_at, ended_at] 안, 서로 겹치지 않음, PAUSE 합이 wall_sec - study_sec에 근접
SELECT s.id AS session_id, e.status, e.started_at, e.ended_at,
       extract(epoch FROM e.ended_at - e.started_at)::int AS dur_sec
FROM status_event e JOIN study_session s ON s.id = e.session_id
WHERE s.user_id = :uid AND s.started_at >= :'since'
ORDER BY s.started_at DESC, e.started_at;

-- B-3 자동확정 시간 압축 — 앱 강제종료 후 6분 기다리는 대신 last_seen_at을 뒤로 돌린다.
--   다음 스케줄러 틱(최대 60초) 안에 B-2에서 auto_finalized=true 행이 생기고 draft가 사라져야 한다.
--   ★ dev 전용. 앱이 아직 살아 있으면 다음 스냅샷이 last_seen_at을 다시 앞당기므로 반드시 앱을 죽인 뒤 실행.
UPDATE active_study_session
SET last_seen_at = now() - interval '6 minutes'
WHERE user_id = :uid;

-- B-4 이어하기·재시작 후 중복 여부
--   기대: 같은 started_at 세션이 정확히 1행 (UNIQUE 제약이 막지만, 서로 다른 started_at으로 2행 생기는 버그를 잡기 위해 본다)
SELECT started_at, count(*) AS rows, bool_or(auto_finalized) AS any_auto, bool_and(auto_finalized) AS all_auto
FROM study_session
WHERE user_id = :uid AND started_at >= :'since'
GROUP BY started_at HAVING count(*) > 1;

-- B-5 통계 API 대조 — 앱/HTTP 응답과 같은 값이어야 한다
--   GET /api/stats?userId=&date=YYYY-MM-DD  → 목록은 focus_sec >= 60 인 세션만, 합계도 그 세션들 기준
SELECT stat_date, count(*) FILTER (WHERE focus_sec >= 60) AS listed_sessions,
       sum(focus_sec) FILTER (WHERE focus_sec >= 60) AS total_focus_sec,
       sum(study_sec) FILTER (WHERE focus_sec >= 60) AS total_study_sec
FROM study_session
WHERE user_id = :uid
GROUP BY stat_date ORDER BY stat_date DESC LIMIT 7;

--   GET /api/stats/streak?userId= → 연속일은 하루 순공 합 >= 600초인 날만 센다
SELECT stat_date, sum(focus_sec) AS day_focus_sec, sum(focus_sec) >= 600 AS counts_for_streak
FROM study_session
WHERE user_id = :uid
GROUP BY stat_date ORDER BY stat_date DESC LIMIT 14;

-- -------------------------------------------------------------------------------------
-- C. 소셜룸 이력 · RTC
-- -------------------------------------------------------------------------------------

-- C-1 방 (POST /api/rooms → rooms 1행, 비동기 기록이라 응답 후 1~2초 뒤 확인)
--   기대: closed_at은 마지막 사람이 나가면 LAST_LEFT, 아무도 안 들어와 10분 지나면 EMPTY_EXPIRED
SELECT id, room_uid, created_by, created_at, closed_at, close_reason,
       (SELECT count(*) FROM room_participations p WHERE p.room_uid = r.room_uid) AS participants
FROM rooms r
WHERE created_at >= :'since'
ORDER BY created_at DESC;

-- C-2 참여 이력
--   기대: STOMP 구독까지 마친 참가자만 행 생성 (예약만 하고 30초 내 구독 없으면 행 없음).
--         퇴장 시 left_at·leave_reason(EXPLICIT / DISCONNECT_TIMEOUT / SWITCHED_ROOM)·focus_sec(마지막 보고값)
SELECT p.id, r.id AS room_id, p.user_id, p.joined_at, p.left_at,
       extract(epoch FROM p.left_at - p.joined_at)::int AS stay_sec,
       p.leave_reason, p.focus_sec
FROM room_participations p JOIN rooms r ON r.room_uid = p.room_uid
WHERE p.joined_at >= :'since'
ORDER BY p.joined_at DESC;

-- C-3 빈 방 만료 시간 압축 — 10분 기다리는 대신 인메모리 TTL을 앞당길 수는 없다(서버 메모리 상태).
--   → 이 항목만은 실제로 10분 대기. 그동안 다른 항목을 진행한다.

-- C-4 RTC 통계 (앱이 PeerConnection마다 주기 샘플 + 종료 시 is_final=true 1건)
--   기대: connection_id별 is_final 최대 1건, candidate_type ∈ host|srflx|prflx|relay,
--         relay면 relay_protocol 있음, peer_user_id는 상대 QA 유저
SELECT connection_id, user_id, peer_user_id, room_id,
       count(*) AS samples,
       count(*) FILTER (WHERE is_final) AS finals,
       max(candidate_type) AS candidate_type, max(relay_protocol) AS relay_protocol,
       max(bytes_received) AS bytes_received, max(bytes_sent) AS bytes_sent,
       min(created_at) AS first_at, max(created_at) AS last_at
FROM rtc_connection_stat
WHERE created_at >= :'since'
GROUP BY connection_id, user_id, peer_user_id, room_id
ORDER BY first_at DESC;

-- C-4b 릴레이 비율 (coturn 경유 여부 — Wi-Fi끼리는 host/srflx, LTE↔Wi-Fi는 relay가 섞여야 정상)
SELECT candidate_type, count(DISTINCT connection_id) AS connections
FROM rtc_connection_stat
WHERE created_at >= :'since' AND is_final
GROUP BY candidate_type;

-- -------------------------------------------------------------------------------------
-- D. 불변식 스윕 — 시나리오와 무관하게 QA 마지막에 항상 실행. 모든 쿼리가 0행이어야 한다.
-- -------------------------------------------------------------------------------------

-- D-1 순공이 공부시간을 넘거나, 공부시간이 벽시계를 넘는 세션
SELECT 'focus>study' AS violation, id, user_id, study_sec, focus_sec FROM study_session WHERE focus_sec > study_sec
UNION ALL
SELECT 'study>wall', id, user_id, study_sec, extract(epoch FROM ended_at - started_at)::int
FROM study_session WHERE study_sec > extract(epoch FROM ended_at - started_at) + 5;

-- D-2 stat_date가 KST 시작일과 다른 세션 (자정 분할이 깨졌거나 UTC로 저장됨)
SELECT id, user_id, started_at, stat_date, (started_at AT TIME ZONE 'Asia/Seoul')::date AS expected
FROM study_session
WHERE stat_date <> (started_at AT TIME ZONE 'Asia/Seoul')::date;

-- D-3 자정을 넘긴 세션 (분할됐어야 함) — 있으면 자정 분할 실패
SELECT id, user_id, started_at, ended_at
FROM study_session
WHERE (started_at AT TIME ZONE 'Asia/Seoul')::date <> ((ended_at - interval '1 second') AT TIME ZONE 'Asia/Seoul')::date;

-- D-4 세션 구간 밖으로 나가거나 서로 겹치는 상태 이벤트
SELECT e.id, e.session_id, e.status, e.started_at, e.ended_at
FROM status_event e JOIN study_session s ON s.id = e.session_id
WHERE e.started_at < s.started_at OR e.ended_at > s.ended_at OR e.ended_at < e.started_at
UNION ALL
SELECT a.id, a.session_id, a.status, a.started_at, a.ended_at
FROM status_event a JOIN status_event b ON a.session_id = b.session_id AND a.id < b.id
WHERE a.started_at < b.ended_at AND b.started_at < a.ended_at;

-- D-5 확정됐어야 할 오래된 draft (스케줄러가 죽었거나 영구 실패가 반복됨)
SELECT id, user_id, started_at, last_seen_at, now() - last_seen_at AS stale_for
FROM active_study_session
WHERE last_seen_at < now() - interval '7 minutes';

-- D-6 draft와 확정본이 같은 (user_id, started_at)으로 동시에 존재 (확정 후 draft 삭제 누락)
SELECT d.id AS draft_id, s.id AS session_id, d.user_id, d.started_at
FROM active_study_session d JOIN study_session s ON s.user_id = d.user_id AND s.started_at = d.started_at;

-- D-7 참여 이력: 퇴장이 입장보다 앞서거나, 닫힌 방에 아직 안 나간 참가자
SELECT 'left<joined' AS violation, p.id, p.user_id, p.joined_at, p.left_at
FROM room_participations p WHERE p.left_at < p.joined_at
UNION ALL
SELECT 'open participant in closed room', p.id, p.user_id, p.joined_at, r.closed_at
FROM room_participations p JOIN rooms r ON r.room_uid = p.room_uid
WHERE r.closed_at IS NOT NULL AND p.left_at IS NULL;

-- D-8 퇴장했는데 사유가 없거나, 사유가 있는데 안 나간 참여
SELECT id, user_id, left_at, leave_reason FROM room_participations
WHERE (left_at IS NULL) <> (leave_reason IS NULL);

-- D-9 한 유저가 동시에 두 방에 열려 있는 참여 (SWITCHED_ROOM 처리 누락)
SELECT user_id, count(*) AS open_participations
FROM room_participations WHERE left_at IS NULL
GROUP BY user_id HAVING count(*) > 1;

-- D-10 RTC: connection당 final이 2건 이상, relay인데 프로토콜 없음, 알 수 없는 candidate_type
SELECT 'multi-final' AS violation, connection_id, count(*)::text AS detail
FROM rtc_connection_stat WHERE is_final GROUP BY connection_id HAVING count(*) > 1
UNION ALL
SELECT 'relay w/o protocol', connection_id, id::text
FROM rtc_connection_stat WHERE candidate_type = 'relay' AND relay_protocol IS NULL
UNION ALL
SELECT 'bad candidate_type', connection_id, candidate_type
FROM rtc_connection_stat WHERE candidate_type NOT IN ('host','srflx','prflx','relay');

-- D-11 고아 행 (FK가 DEFERRABLE이라 트랜잭션 안에서만 보호됨 — 실제로 없는지 확인)
SELECT 'session w/o user' AS violation, s.id FROM study_session s LEFT JOIN users u ON u.id = s.user_id WHERE u.id IS NULL
UNION ALL
SELECT 'participation w/o room', p.id FROM room_participations p LEFT JOIN rooms r ON r.room_uid = p.room_uid WHERE r.id IS NULL;

-- -------------------------------------------------------------------------------------
-- E. QA 데이터 정리 (dev라 필수는 아님. 다음 QA 라운드의 기준선을 깨끗하게 하고 싶을 때)
-- -------------------------------------------------------------------------------------
-- BEGIN;
-- DELETE FROM status_event WHERE session_id IN (SELECT id FROM study_session WHERE user_id IN (:uid, :uid2));
-- DELETE FROM study_session        WHERE user_id IN (:uid, :uid2);
-- DELETE FROM active_study_session WHERE user_id IN (:uid, :uid2);
-- DELETE FROM rtc_connection_stat  WHERE user_id IN (:uid, :uid2);
-- DELETE FROM room_participations  WHERE user_id IN (:uid, :uid2);
-- DELETE FROM rooms WHERE created_by IN (:uid, :uid2)
--   AND NOT EXISTS (SELECT 1 FROM room_participations p WHERE p.room_uid = rooms.room_uid);
-- COMMIT;
