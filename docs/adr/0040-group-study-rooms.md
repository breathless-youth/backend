# ADR-0040: 그룹은 사라지지 않고 공부방은 기존 방을 다시 쓰며, 계약을 로직보다 먼저 낸다

- 상태: 승인
- 날짜: 2026-11-03
- 티켓: BY-1124 (스토리 BY-1123, FE 짝 BY-1125)
- 설계: `docs/superpowers/specs/2026-11-03-by1124-group-study-rooms-design.md`
- 관련: ADR-0015(API 버전닝, 새 경로는 1), ADR-0018(룸 상태 DB 원본), ADR-0028(랭킹 조회 시 집계), ADR-0031(수동 기록 제외),
  ADR-0037(푸시 실험 발송 기록)

## 맥락

지금 소셜 스터디는 숫자 4자리 초대코드로 만드는 일회성 방이라 마지막 사람이 나가면 방과 코드가 사라지고, 아는 사람끼리만
쓴다. 이를 한 번 만들면 사라지지 않는 그룹(공개·비공개, 6명)으로 바꿔 모르는 사람과도 같이 공부하게 한다. 성공 기준은 그룹
멤버의 남는 사용자 비율(주 3일 이상 공부)이다.

FE는 Swagger가 나와야 타입을 만들 수 있어서 계약(REST·STOMP·푸시)이 로직보다 먼저 필요하다. 서버 쪽 제약은 이렇다.

- ECS 여러 태스크가 같은 스케줄러를 모두 돌린다(ShedLock 없음). 중복은 DB 제약·조건부 UPDATE로 막는다.
- STOMP 브로커는 태스크마다 메모리 안에 있고 방 상태의 원본은 DB다(ADR-0018).
- 초대코드 방과 옛 앱의 동작, 체크포인트(`PUT /api/study-sessions/active`)의 속도·성공은 바뀌면 안 된다.

## 결정

1. **그룹은 지우지 않고, 그룹 공부방은 기존 `rooms`를 `group_id`로 다시 쓴다.**
   - 그룹은 `deleted_at`만 채우고 멤버·신청도 행을 남긴다. `joined_via`·`leave_reason`은 효과 측정 분석에 쓴다.
   - `rooms`에 `group_id`를 더하고 `invite_code`를 NULL 허용으로 바꾼다. `(group_id)` 부분 unique(`closed_at IS NULL`)로 그룹마다
     열린 방이 하나다. 초대코드 방은 `group_id IS NULL`이다. `room_participations`에는 `mic_on`·`on_break`(기본 false)를 더한다.
   - 입구만 REST(`POST /api/groups/{groupId}/room/join`)로 `roomId`를 받고, 구독·발행은 지금과 같은
     `/topic/room/{roomId}`, `/app/room/{roomId}/...`다. 예약·리스·정리·재접속을 그대로 다시 쓴다.
     - 그룹용 방 표와 목적지를 따로 두는 안은 기각: 같은 기능을 두 벌 유지하게 된다.
   - 초대코드 방에는 새 필드·새 메시지 종류를 보내지 않는다(직렬화에서 생략). 휴식은 `focusState`에 값을 더하지 않고 별도
     필드 `onBreak`로 둔다 — 지금 웹은 모르는 값이 든 메시지를 SNAPSHOT까지 통째로 버린다.
   - 차단한 사이는 서버가 SIGNAL을 버리고 두 사람 각자의 개인 큐로 `PEER_BLOCKED{userId}`를 보낸다(누가 차단했는지는 담지
     않는다). 서버가 SIGNAL을 버려도 이미 맺은 연결은 끊기지 않으므로 FE는 `PEER_BLOCKED`를 받으면 그 상대와의 기존 연결을 즉시
     닫는다. 상대는 목록에는 그대로 나오고 영상·소리만 오가지 않는다. 내보내지면 당사자에게 개인 큐 `KICKED`, 다른 멤버에게 `MEMBER_LEFT`다.
2. **앱 전체 공부 상태는 새 신호 없이 기존 체크포인트(`active_study_session`)로 판단하고, 기준 창은 60초다.**
   - 멤버마다 순서대로: 이 그룹 공부방에 확정된 자리가 있으면 `IN_ROOM` → draft가 없거나 `last_seen_at`이 60초보다 오래됐으면
     `IDLE` → 열린 이벤트가 `PAUSE`면 `PAUSED` → 그 밖은 `STUDYING`. 60초는 `ActiveStudySessionService.FOCUSING_WINDOW`와 같다.
   - 지연은 대부분 30초 체크포인트 주기에서 오고 60초 창은 앱이 갑자기 꺼졌을 때만 영향을 준다. 그래서 새 신호(소켓 구독·별도
     보고)를 만들지 않고, 그룹 홈·내 그룹 갱신은 구독 대신 화면이 보이는 동안 30초마다 다시 받는다.
   - 순공은 랭킹과 같은 기준이다: 측정 세션(`MEASURED`)만, 1분 미만 조각 제외, 진행 중인 draft 포함. 공개 그룹 순서와
     그룹 순위는 경쟁이라 수동 기록을 뺀다(ADR-0031). 오늘은 KST 달력 날짜, 이번 주는 월요일 0시 KST부터다.
   - 공개 그룹 평균은 지금 멤버들의 이번 주 앱 전체 순공 합 ÷ 지금 멤버 수다. 들어온 뒤부터만 세면 새 멤버를 받은 그룹의
     평균이 떨어져 신청을 받는 그룹이 손해를 본다. 공개 그룹 전체를 한 번에 계산해 약 60초 캐시한다(ADR-0028).
3. **그룹 푸시는 `push_send` 밖에 두고, 멤버 공부 알림만 `(receiver, group)` 1시간 제한을 별도 표로 둔다.**
   - `push_send`는 하루 한 통 실험(BY-1029, ADR-0037)의 기록이라 그룹 푸시를 섞지 않는다. FE는 `type`·`push_id`를 열림 분석에만
     쓴다.
   - 1시간 제한은 `group_study_alert(receiver_id, group_id, last_sent_at)`의 조건부 upsert(마지막 보낸 때가 1시간 전보다
     이를 때만 갱신)로 걸고, 갱신된 행에만 보낸다. 서버가 여러 대여도 한 번만 나간다.
   - 푸시는 업무 트랜잭션이 커밋된 뒤에 보내고 실패해도 업무는 그대로다. 멤버 공부 시작 푸시(`group_member_started`)만
     체크포인트 요청 안에서 보내지 않고 비동기로 보내, 실패해도 체크포인트는 성공한다.
   - 신청 3일 만료도 `UPDATE ... SET status='EXPIRED' WHERE status='PENDING' AND expires_at <= now() RETURNING ...`으로 바꾼
     행에만 보내, 여러 태스크가 같은 작업을 돌려도 알림이 한 번이다.
4. **못 하는 이유 코드를 400·403·409 응답에 싣는다.**
   - 지금은 `NotFoundException`만 `ErrorCode`를 실을 수 있다. 400·409 예외도 `ErrorCode`를 받게 넓히고 403 예외를 새로 둔다.
     본문은 지금처럼 `ErrorResponse{code, message}`이고 FE는 `code`로만 문구를 고른다. 글자 수 위반은 그대로
     `VALIDATION_FAILED`(400)다.
   - 서버가 개수 제한(그룹+신청 5, 신청 3, 그룹장 2, 멤버 6)·신청 만료·내보낸 뒤 3일·연락처 판정의 원본이 되고, 신청 화면이
     "왜 못 하는지"를 이유별로 보여 줄 수 있게 한다(`RECENTLY_KICKED`, `GROUP_SLOT_LIMIT`, `APPLICATION_LIMIT` 등).
5. **계약을 먼저 내고, 로직이 채워지기 전에는 501을 준다.**
   - 컨트롤러·DTO·Swagger(태그 `그룹` 하나)를 먼저 올리고 로직이 없는 동안 `501 {code: NOT_IMPLEMENTED}`를 준다. FE는 그동안
     고정 데이터로 화면을 붙인다. 새 경로는 모두 `API-Version` 1이다(ADR-0015).
   - STOMP 계약은 같은 시기에 `websocket.html` §11에, 푸시 계약은 BY-1124 댓글에 낸다. 서버가 STOMP를 실제로 보내는 것은 4단계부터이고,
     그때까지 `RoomMessageType`·`RoomMember`·`RoomStompHandler`는 그대로다. 로직은 FE가 화면을 붙이는 순서(그룹 만들기·홈 →
     공개 목록·신청 → 초대·멤버 관리 → 공부방 STOMP → 푸시 → 신고·차단)로 채우고, 다 채우면 `NOT_IMPLEMENTED`를 지운다.

## 결과

- 초대코드 방과 옛 앱의 동작은 바뀌지 않는다. 4단계 구현은 `websocket.html` §11을 따르고, 계약(type·필드·목적지·수치)을 바꾸면
  §11과 변경 이력을 같은 PR에서 고친다(CLAUDE.md 작업 규칙 8). `WebSocketDocsContractTest`는 코드에 있는 type·필드가 문서에
  있는지만 보므로 문서가 먼저 나가 있는 동안에도 통과한다.
- 그룹 홈의 공부 상태는 체크포인트 주기만큼 늦다. 구독으로 바꿔도 빨라지지 않으므로 다시 받기로 둔다.
- 그룹 푸시는 `push_send` 기반 실험 통계에 잡히지 않는다. 열림 분석은 FE가 보내는 `type`·`push_id`로 한다.
- 지워진 그룹·나간 멤버·끝난 신청의 행이 남아, 쿼리와 부분 unique·인덱스는 `deleted_at IS NULL`·`left_at IS NULL`·`PENDING` 같은
  조건을 함께 건다.
- 지금 초대코드 방 API는 지우지 않는다. 강제 업데이트 뒤 호출이 0인 것을 확인하고 후속 티켓에서 지운다.
- 501 단계에서는 FE가 고정 데이터로 화면을 붙이므로, 단계마다 응답 모양이 바뀌면 같은 BY-1124 스레드에 남긴다.
