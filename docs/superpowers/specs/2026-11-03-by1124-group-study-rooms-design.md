# BY-1124 그룹 공부방 백엔드 설계

- Jira: BY-1124 (BE), 스토리 BY-1123, 짝 티켓 BY-1125 (FE)
- 원본 규칙: 위키 `.ai/product/specs/BY-1123-그룹-공부방.md` (특히 1·6-1·11·12·16·20·24·28절)
- FE 설계: frontend `docs/superpowers/specs/2026-11-02-group-study-rooms-design.md`
- 분기 기준: origin/dev `b49b680`, 다음 마이그레이션 V36, 다음 ADR 0040
- 표기: **[합의]**는 FE와 주고받으며 정한 것, **[BE 판단]**은 사용자가 맡겨 백엔드가 정한 것이다

## 왜

지금 소셜 스터디는 숫자 4자리 초대코드로 만드는 일회성 방이라 마지막 사람이 나가면 사라지고, 아는 사람끼리만 쓴다.
이를 한 번 만들면 사라지지 않는 그룹(공개·비공개, 6명)으로 바꿔 모르는 사람과도 같이 공부하게 한다.
성공 기준은 그룹 멤버의 남는 사용자 비율(주 3일 이상 공부)이다.

백엔드는 그룹·신청·초대·멤버 관리·신고·차단 API, 멤버별 앱 전체 공부 상태, 그룹 공부방 STOMP, 그룹 푸시 8종을 맡는다.
FE는 Swagger가 나와야 타입을 만들 수 있으므로 **계약(REST·STOMP·푸시)을 로직보다 먼저** 낸다.

## 목표

- REST 계약을 Swagger로 dev에 먼저 올리고(로직 전에는 501), 같은 시기에 STOMP 계약을 `websocket.html`에, 푸시 계약을 BY-1124 댓글에 낸다.
- 로직을 FE가 화면을 붙이는 순서(아래 "전달 순서")로 채운다.
- 서버가 개수 제한·만료·내보낸 뒤 3일·연락처 판정의 원본이 되고, 못 하는 이유를 코드로 준다.
- 지금 초대코드 방과 옛 앱을 깨지 않는다.

## 비목표

- 그룹 홈·내 그룹 목록의 실시간 구독 (주기적으로 다시 받는다)
- 채팅, 공개 그룹 이름·소개 신고, 신고 자동 정지, 운영자용 관리 화면·API (운영자는 슬랙 알림과 DB/Metabase로 본다)
- 초대코드 입력 횟수 제한
- 회원 탈퇴 처리 (탈퇴 API가 아직 없다. 그룹장이 앱을 떠난 그룹은 위키 "열린 문제"대로 안고 간다)
- 서버에서 Amplitude 이벤트 보내기
- 지금 초대코드 방 API 삭제 (강제 업데이트 뒤 호출 0을 확인하고 후속 티켓에서 지운다) **[합의]**

## 반목표 (돌아가도 이러면 실패다)

- 초대코드 방이나 옛 앱의 동작이 바뀐다. 초대코드 방에는 새 필드·새 메시지 종류를 보내지 않는다.
- 기존 필드에 새 값을 더한다 (예: `focusState`에 휴식). 지금 웹은 그런 메시지를 SNAPSHOT까지 통째로 버린다 **[합의]**
- 체크포인트(`PUT /api/study-sessions/active`)가 그룹 로직 때문에 느려지거나 실패한다.
- 멤버가 어디서 공부하는지(싱글룸인지 어느 그룹인지)가 드러난다.
- 누가 누구를 차단했는지가 드러난다.
- 트랜잭션이 롤백됐는데 푸시가 나가거나, 서버가 여러 대라 같은 푸시가 두 번 나간다.

## 제약

- CLAUDE.md의 Boot 4 주의사항, Flyway 전용 스키마 변경, `@Profile` 금지, 생성자 주입, record DTO를 따른다.
- 서버는 ECS 여러 태스크가 같은 스케줄러를 모두 돌린다 (ShedLock 없음). 중복은 DB 제약·조건부 UPDATE로 막는다.
- STOMP 브로커는 태스크마다 메모리 안에 있다. 방 상태의 원본은 DB다 (ADR-0018).
- 새 경로는 모두 `API-Version` 1이다 (ADR-0015 BY-721 규칙) **[합의]**

## 정한 것

| 항목 | 결정 | 근거 |
|---|---|---|
| 계약 전달 | REST는 Swagger 태그 `그룹` 하나, dev 반영 시 BY-1124 댓글에 경로·이유 코드·시험·목표 값을 적고, 바뀌면 같은 스레드에 남긴다 | **[합의]** |
| 로직 전 응답 | 컨트롤러·DTO를 먼저 올리고 로직이 없는 동안 `501 {code: NOT_IMPLEMENTED}` | **[합의]** FE는 그동안 고정 데이터로 화면을 붙인다 |
| 그룹 홈·내 그룹 갱신 | 주기적으로 다시 받기 (화면이 보이는 동안 30초, 돌아오면 바로) | **[BE 판단]** 상태가 원래 30초 체크포인트 기준이라 구독해도 빨라지지 않는다 |
| 초대코드 | `23456789ABCDEFGHJKMNPQRSTUVWXYZ` 31자에서 6자리, 대소문자 안 가림(대문자로 바꿔 비교) | **[BE 판단]** 헷갈리는 0·O·1·I·L을 뺐다. 31^6 ≈ 8.9억 |
| 초대 링크 | 서버는 코드만 준다. 링크는 지금 `/social/join?code=` 그대로이고 웹만 바꾼다 | **[합의]** 앱은 code를 형식 검사 없이 웹에 넘긴다 |
| 글자 수 | NFC로 맞추고 앞뒤 공백을 뺀 뒤 이모지 조합을 한 글자로 센다 (`Nicknames.length`와 같은 `BreakIterator`) | **[합의]** FE도 `Intl.Segmenter`로 같게 센다 |
| 휴식 | `focusState`에 값을 더하지 않고 별도 필드 `onBreak` | **[합의]** |
| 앱 전체 공부 상태 | 새 신호 없이 기존 체크포인트(`active_study_session`)로 판단, 기준 창은 랭킹 `FOCUSING_WINDOW`와 같은 60초 | **[BE 판단]** 지연은 대부분 30초 주기에서 오고, 60초 창은 앱이 갑자기 꺼졌을 때만 영향이 있다 |
| 오늘·이번 주 | 오늘은 KST 달력 날짜(`statDate`, 기록 탭 일간과 같음), 이번 주는 월요일 0시 KST부터 | **[BE 판단]** 기록 탭·랭킹 주간의 지금 기준 |
| 무엇을 순공으로 세나 | 랭킹과 같다: 측정 세션(`MEASURED`)만, 1분 미만 조각 제외, 진행 중인 draft 포함 | **[BE 판단]** 공개 그룹 순서와 그룹 순위는 경쟁이라 수동 기록을 빼는 랭킹 기준(ADR-0031)을 따른다 |
| 공개 그룹 평균 | 지금 멤버들의 이번 주 순공 합 ÷ 지금 멤버 수. 주 중간에 들어온 멤버도 들어오기 전을 포함한 이번 주 앱 전체 순공이 모두 들어가고, 나간 멤버는 빠진다. Swagger 설명에 적는다 | **[합의]** 책상·그룹 순위와 같은 값이고, 들어온 뒤부터만 세면 새 멤버를 받은 그룹의 평균이 떨어져 신청을 받는 그룹이 손해를 본다 |
| 페이지 나누기 | 쪽 번호(`page` 0부터, `size` 기본 20, 최대 50), 응답은 `hasNext`만. 다 차지 않은 그룹 → 평균 많은 순 → 그룹 ID 작은 순 | **[BE 판단]** 정렬 값이 계속 바뀌어 커서가 맞지 않는다. FE가 그룹 ID로 중복을 거른다 **[합의]** |
| 시험·목표 | 지금 7개 `PROFESSIONAL, CSAT, JOB, CERTIFICATE, CIVIL_SERVICE, LANGUAGE, ETC`는 그대로, `SCHOOL_EXAM`(내신)·`UNIVERSITY_EXAM`(대학 시험)을 더해 9개. 프로필과 그룹이 같은 값을 쓴다 | **[합의]** |
| 그룹 푸시 기록 | `push_send`(하루 한 통, BY-1029 실험)를 쓰지 않는다. 멤버 공부 알림의 1시간 제한만 별도 테이블로 | **[BE 판단]** FE는 type·push_id를 열림 분석에만 쓴다 **[합의]** |
| 신고 알림 | 지금 슬랙 봇(`SlackBotNotifier`)으로 신고 한 건마다 보낸다. 채널은 새 설정값, 비어 있으면 보내지 않고 경고 로그 | **[BE 판단]** 채널·담당자는 팀이 정해 알려 준다 **[합의]** |
| 그룹 공부방 입구 | REST로 그룹 공부방 자리를 잡아 `roomId`를 받고, 구독·발행은 지금과 같은 `/topic/room/{roomId}`, `/app/room/{roomId}/...` | **[BE 판단]** 예약·리스·정리·재접속을 그대로 다시 쓴다 |
| 차단한 사이의 공부방 | 서버가 SIGNAL을 버리고, 두 사람이 같은 방에 있으면 각자 개인 큐로 `PEER_BLOCKED{userId}` (누가 차단했는지 없음) | **[합의]** |
| 내보내짐 | 당사자에게 개인 큐 `KICKED`, 다른 멤버에게 `MEMBER_LEFT` | **[합의]** |

## 계약

### 못 하는 이유 코드

응답은 지금처럼 `ErrorResponse{code, message}`이고 FE는 `code`로만 문구를 고른다 **[합의]**.
지금은 `NotFoundException`만 코드를 실을 수 있으므로, 400·403·409 예외도 `ErrorCode`를 받게 넓히고 403 예외를 새로 둔다.

| code | 상태 | 뜻 |
|---|---|---|
| `RECENTLY_KICKED` | 409 | 이 그룹에서 내보내진 지 3일이 안 됐다 |
| `GROUP_SLOT_LIMIT` | 409 | 들어간 그룹과 답을 기다리는 신청이 합쳐 5개다 |
| `APPLICATION_LIMIT` | 409 | 답을 기다리는 신청이 3곳이다 |
| `GROUP_FULL` | 409 | 멤버가 6명이다 |
| `OWNED_GROUP_LIMIT` | 409 | 그룹장인 그룹이 2개다 |
| `CONTACT_NOT_ALLOWED` | 400 | 이름·소개·신청 소개 글에 연락처나 링크가 있다 |
| `OWNER_CANNOT_LEAVE` | 409 | 다른 멤버가 있는 그룹장은 나갈 수 없다 |
| `ALREADY_APPLIED` | 409 | 이 그룹에 답을 기다리는 신청이 이미 있다 |
| `ALREADY_MEMBER` | 409 | 이미 멤버다 (신청할 때) |
| `APPLICATION_NOT_PENDING` | 409 | 이미 답했거나 취소·만료된 신청이다 |
| `GROUP_NOT_FOUND` | 404 | 없거나 사라진 그룹, 또는 비공개 그룹을 공개 그룹 보기로 열었거나 비공개 그룹에 신청했다 |
| `GROUP_MEMBER_NOT_FOUND` | 404 | 그 사람은 이 그룹 멤버가 아니다 (넘기기·내보내기·신고 대상) |
| `INVITE_CODE_NOT_FOUND` | 404 | 없거나 새로 만들어져 막힌 초대코드 (기존 코드 재사용) |
| `NOT_GROUP_MEMBER` | 403 | 멤버만 할 수 있다 |
| `NOT_GROUP_OWNER` | 403 | 그룹장만 할 수 있다 |
| `NOT_IMPLEMENTED` | 501 | 계약만 있고 로직이 아직 없다 (다 채워 지웠다 — 아래 "구현하며 정한 것") |

글자 수 위반은 지금처럼 `VALIDATION_FAILED`(400)다.

### REST 경로 (모두 v1, 인증 필요)

이름은 빌더가 다듬어도 되지만, 바꾸면 Swagger를 올리기 전에 바꾼다.

| 무엇 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|
| 그룹 만들기 | `POST /api/groups` | 누구나 | `{name, visibility: PUBLIC\|PRIVATE, category, description?}` → 201 `{groupId}`. 만든 사람은 멤버이자 그룹장이 되므로 5개·2개 제한을 모두 본다 |
| 내 그룹 탭 | `GET /api/groups/me` | 누구나 | `{groups[], pendingApplicationCount, ownedGroupCount, limits{maxGroupsAndApplications:5, maxPendingApplications:3, maxOwnedGroups:2}}`. 그룹은 내가 들어간 순서 |
| 그룹 홈 | `GET /api/groups/{groupId}` | 멤버 | 아래 "그룹 홈" 참고 |
| 그룹 설정 | `PATCH /api/groups/{groupId}` | 그룹장 | `{name, category, description}`. 공개 여부는 받지 않는다 |
| 공개 그룹 목록 | `GET /api/groups/public?query=&category=&page=&size=` | 누구나 | 이름에 `query`가 들어간 것(대소문자 무시), `category` 하나로 거르기 |
| 공개 그룹 보기 | `GET /api/groups/public/{groupId}` | 누구나 | 비공개 그룹이면 `GROUP_NOT_FOUND` |
| 멤버 공부 알림 | `PUT /api/groups/{groupId}/notifications` | 멤버 | `{enabled}`. 처음엔 꺼짐 |
| 멤버 목록 | `GET /api/groups/{groupId}/members` | 멤버 | `{userId, nickname, profileImageUrl, owner, joinedAt, lastStudiedAt}` |
| 그룹장 넘기기 | `PUT /api/groups/{groupId}/owner` | 그룹장 | `{userId}` |
| 내보내기 | `DELETE /api/groups/{groupId}/members/{userId}` | 그룹장 | 자기 자신은 안 된다. 그룹 홈·공부방 모두 이 경로. 비공개 그룹이면 초대코드도 새로 발급된다(응답 형식은 그대로, FE는 초대코드 보기 `GET /api/groups/{groupId}/invite`를 다시 받아 새 코드를 보여 준다) |
| 나가기 | `POST /api/groups/{groupId}/leave` | 멤버 | 혼자 남은 그룹장이면 그룹이 사라진다 |
| 초대코드 보기 | `GET /api/groups/{groupId}/invite` | 멤버 | 비공개 그룹만, `{inviteCode}` |
| 코드 새로 만들기 | `POST /api/groups/{groupId}/invite` | 그룹장 | 이전 코드는 바로 막힌다 |
| 코드·링크로 들어오기 | `POST /api/groups/join` | 누구나 | `{inviteCode, via: LINK\|CODE}` → `{groupId, joined}`. 이미 멤버면 409 대신 `joined:false`로 200 (링크를 두 번 눌러도 그룹 홈으로 간다) |
| 신청 보내기 | `POST /api/groups/{groupId}/applications` | 누구나 | `{introduction}` → 201 `{applicationId, expiresAt}`. 공개 그룹만, 비공개 그룹이면 `GROUP_NOT_FOUND` |
| 그룹장용 신청 목록 | `GET /api/groups/{groupId}/applications` | 그룹장 | `{remainingSeats, applications[{applicationId, userId, nickname, profileImageUrl, introduction, appliedAt, expiresAt}]}`, 신청한 순서 |
| 내 신청 목록 | `GET /api/group-applications/me` | 누구나 | 답을 기다리는 것만 `{applicationId, groupId, groupName, category, appliedAt, expiresAt}` |
| 신청 취소 | `DELETE /api/group-applications/{applicationId}` | 신청한 사람 | |
| 수락 | `POST /api/group-applications/{applicationId}/accept` | 그룹장 | 자리가 없으면 `GROUP_FULL`, 신청은 기한까지 남는다 |
| 거절 | `POST /api/group-applications/{applicationId}/decline` | 그룹장 | |
| 신청 신고 | `POST /api/group-applications/{applicationId}/report` | 그룹장 | `{reason?}` (0~100자). 거절 + 신고 기록 + 슬랙. 신청한 사람에게는 거절 알림만 |
| 공부방 자리 잡기 | `POST /api/groups/{groupId}/room/join` | 멤버 | `{roomId, graceRejoin, cameraOn, micOn, onBreak, iceServers, iceTtlSeconds}`. 지금 `RoomJoinResponse`와 같은 30초 예약 |
| 공부방 나가기 | `POST /api/groups/{groupId}/room/leave` | 멤버 | |
| 공부방 멤버 신고 | `POST /api/groups/{groupId}/members/{userId}/report` | 멤버 | `{reason?}`. 신고 + 차단 + 슬랙, 같은 방이면 `PEER_BLOCKED` |
| 차단 목록 | `GET /api/blocks` | 누구나 | 내가 차단한 사람 `{userId, nickname, profileImageUrl, blockedAt}` |
| 차단 해제 | `DELETE /api/blocks/{userId}` | 누구나 | 다음 공부방 입장부터 영상·소리가 다시 오간다 |
| 프로필 카테고리 | 기존 프로필 API | | 허용 값을 9개로 늘리고 Swagger 설명에 9개를 적는다 |

**멤버 책상 (`GroupMemberDesk`)** — 내 그룹 카드, 그룹 홈, 공개 그룹 보기가 함께 쓴다.

| 필드 | 뜻 |
|---|---|
| `userId`, `nickname`, `profileImageUrl`, `owner` | |
| `status` | `IN_ROOM`(이 그룹 공부방에 있음, 카메라 끔·휴식 포함) / `STUDYING`(다른 곳에서 측정 중) / `PAUSED`(다른 곳에서 일시정지) / `IDLE`(공부 안 함) |
| `accumulating` | 지금 순공이 쌓이는 중이면 참. FE가 1초씩 올린다 |
| `todayFocusSec`, `weekFocusSec` | 앱 전체, 위 "무엇을 순공으로 세나" 기준, 응답 시각까지 |
| `lastStudiedAt` | 앱에서 마지막으로 공부한 때. 기록이 없으면 null |

멤버 순서는 들어간 순서로 주고, 책상 순서·순위 정렬은 FE가 한다 (FE 설계의 순수 모듈).

**그룹 홈**: `groupId, name, visibility, category, description, memberCount, myRole(OWNER|MEMBER), notifyEnabled, newApplicationCount(공개 그룹 그룹장만, 아니면 null), studyingCount, members[GroupMemberDesk]`.
이번 주 순위는 `members[].weekFocusSec`로 FE가 그린다.
`newApplicationCount`는 답을 기다리는(기한 안) 신청 전부다. 그룹장이 목록을 봤는지는 따로 기록하지 않아, 답할 때까지 띠가 남는다 **[BE 판단]** — 신청한 사람이 3일 안에 답을 받게 그룹장을 미는 쪽이 위키 7절의 뜻에 맞고, "봤음" 기록과 그 처리 경로가 필요 없다.

**내 그룹 카드**: `groupId, name, category, visibility, memberCount, studyingCount, owner(내가 그룹장인지), members[GroupMemberDesk]`.
`studyingCount`는 `IN_ROOM` + `STUDYING` 수다 (파랑 + 초록, 위키 12절).

**공개 그룹 카드**: `groupId, name, category, memberCount, weeklyAverageFocusSec, studyingCount, member(내가 멤버인지)`.

**공개 그룹 보기**: `groupId, name, category, description, memberCount, owner{userId, nickname, profileImageUrl}, weeklyAverageFocusSec, members[GroupMemberDesk], applyState, unavailableReason?, application?{applicationId, expiresAt}`.
`applyState`는 `MEMBER` > `APPLIED` > `FULL` > `UNAVAILABLE` > `CAN_APPLY` 순으로 먼저 맞는 것 하나다.
`UNAVAILABLE`일 때 `unavailableReason`은 `RECENTLY_KICKED` > `GROUP_SLOT_LIMIT` > `APPLICATION_LIMIT` 순으로 하나다.

### 공부방 STOMP (그룹 공부방에만)

`websocket.html`에 "그룹 공부방" 절과 변경 이력을 더한다 (CLAUDE.md 규칙 8).
초대코드 방은 아무것도 바뀌지 않는다. 새 필드는 초대코드 방에서 빠진다(직렬화에서 생략).

- **들어오기**: `POST /api/groups/{groupId}/room/join`으로 `roomId`를 받고, 지금처럼 `/topic/room/{roomId}`와 `/user/queue/room`을 구독하고 `/app/room/{roomId}/snapshot|signal|state`로 보낸다.
- **멤버 필드 추가** (`SNAPSHOT.members[]`, `MEMBER_JOINED.member`): `micOn`, `onBreak`, `owner`.
- **`/app/room/{roomId}/state`가 더 받는 것**: `micOn?`, `onBreak?`. 바뀐 값만 알린다.
- **새 메시지 종류**:

| type | 어디로 | 필드 | 언제 |
|---|---|---|---|
| `MIC_CHANGED` | 방 토픽 | `userId, micOn` | 마이크를 켜고 끌 때 |
| `BREAK_CHANGED` | 방 토픽 | `userId, onBreak` | 뽀모도로 휴식이 시작·끝날 때 |
| `OWNER_CHANGED` | 방 토픽 | `userId`(새 그룹장) | 공부방이 열려 있는 동안 그룹장이 바뀔 때 |
| `KICKED` | 당사자 개인 큐 | `groupId` | 공부방에 있던 사람이 내보내질 때. 이어서 방 토픽에 `MEMBER_LEFT` |
| `PEER_BLOCKED` | 두 사람 각자 개인 큐 | `userId`(연결하지 말 상대) | 차단 관계인 두 사람이 같은 방에 있게 될 때(입장 확정 시)와, 공부방에서 신고·차단하는 순간. 구독 순서 때문에 놓치지 않게, SNAPSHOT을 보낼 때마다 그 세션에 해당하는 `PEER_BLOCKED`를 이어서 다시 보낸다. 양쪽 모두 같은 모양이라 누가 차단했는지 알 수 없다 |

- **SIGNAL**: 차단 관계(어느 쪽이든)인 두 사람 사이는 서버가 조용히 버린다. 이미 맺은 연결은 서버가 끊을 수 없으므로 FE가 `PEER_BLOCKED`를 받으면 그 상대와의 기존 연결을 즉시 닫는다(상대 타일은 카메라를 끈 사람처럼 프로필로 보이고, SNAPSHOT·`MEMBER_JOINED`·방 토픽 이벤트에는 그대로 나온다).
- **마이크·휴식 초기값**: 새로 들어오면 `micOn`, `onBreak`는 거짓이다. 30초 안에 다시 들어오는 경우(`graceRejoin`)는 지금 `cameraOn`처럼 이어 간다.
- **그룹을 나가거나 그룹이 사라질 때** 공부방에 있었다면 자리를 지우고 `MEMBER_LEFT`를 보낸다 (`KICKED`는 내보내진 때만).
- 소리는 영상처럼 P2P이고 서버는 녹음·저장하지 않는다.

### 그룹 푸시

데이터는 지금처럼 `type`, `link`, `push_id`(UUID)이고, 제목·본문은 BY-1124 표의 확정 문구를 그대로 쓴다.
`link`는 FE가 라우트를 정하면 바꾼다. 그전까지 아래 값을 제안으로 두고, 링크 만드는 곳을 한 클래스에 모아 한 곳만 고치게 한다.

| type | 받는 사람 | link (제안) |
|---|---|---|
| `group_application_received` | 그룹장 | `/groups/{groupId}/applications` |
| `group_owner_transferred` | 새 그룹장 | `/groups/{groupId}` |
| `group_application_accepted` | 신청한 사람 | `/groups/{groupId}` |
| `group_application_declined` | 신청한 사람 (거절·신고 모두) | `/groups?tab=public&category={그 그룹의 category}` |
| `group_application_expired` | 신청한 사람 | `/groups?tab=public` |
| `group_application_room_closed` | 신청한 사람 | `/groups?tab=public` |
| `group_member_started` | 알림을 켠 다른 멤버 | `/groups/{groupId}` |
| `group_member_kicked` | 내보내진 사람 | `/groups?tab=public` |

- 푸시는 업무 트랜잭션이 커밋된 뒤에 보내고, 보내기가 실패해도 업무는 그대로다.
- **멤버 공부 시작** (`group_member_started`)
  - 언제: 한 사용자의 새 세션 draft가 처음 저장될 때(첫 체크포인트, 시작 약 30초 뒤). 같은 세션의 재개, 수동 기록, 복구는 아니다.
  - 받는 사람: 시작한 사람이 멤버인 그룹마다, 그 그룹 알림을 켠 다른 멤버.
  - 빼는 사람: 그 그룹 공부방에 있는 멤버, 시작한 사람과 어느 쪽이든 차단한 사이.
  - 횟수: 받는 사람·그룹마다 1시간에 한 번. `(receiver_id, group_id)` 행을 조건부 upsert해서(마지막 보낸 때가 1시간 전보다 이를 때만 갱신) 갱신된 행에만 보낸다. 서버가 여러 대여도 한 번만 나간다.
  - 밤 시간 제한은 없다.
  - 체크포인트 요청 안에서 보내지 않는다 (비동기, 실패해도 체크포인트는 성공).
- **3일 만료** (`group_application_expired`): 1분마다 도는 작업이 `UPDATE ... SET status='EXPIRED' WHERE status='PENDING' AND expires_at <= now() RETURNING ...`으로 바꾼 행에만 보낸다. 조회와 개수 제한은 작업이 돌기 전이라도 `expires_at`이 지난 신청을 만료된 것으로 본다.
- **내보내짐**: 공부방에 있었으면 푸시와 `KICKED`를 둘 다 보낸다.

## 데이터 (제안, 이름은 빌더가 다듬는다)

| 테이블 | 핵심 컬럼 | 제약·인덱스 |
|---|---|---|
| `study_group` | `name, description, visibility, category, owner_id, invite_code(비공개만), created_at, updated_at, deleted_at` | `invite_code` 부분 unique(`deleted_at IS NULL`). `group`은 예약어라 피한다 |
| `study_group_member` | `group_id, user_id, joined_at, joined_via(CREATED\|APPLICATION\|INVITE_LINK\|INVITE_CODE), notify_enabled(기본 false), left_at, leave_reason(LEFT\|KICKED\|GROUP_DELETED)` | `(group_id, user_id)` 부분 unique(`left_at IS NULL`), `(user_id)` 살아 있는 멤버 인덱스. 내보낸 뒤 3일은 `leave_reason=KICKED AND left_at > now()-3일`로 본다 |
| `study_group_application` | `group_id, user_id, introduction, status(PENDING\|ACCEPTED\|DECLINED\|CANCELED\|EXPIRED\|GROUP_DELETED), created_at, expires_at, answered_at` | `(group_id, user_id)` 부분 unique(`PENDING`), `expires_at` 부분 인덱스(`PENDING`) |
| `user_block` | `blocker_id, blocked_id, created_at` | PK `(blocker_id, blocked_id)` |
| 신고 | `reporter_id, target_user_id, type(ROOM_MEMBER\|APPLICATION), group_id, application_id, reason, created_at` | V1의 안 쓰는 `report` 테이블에 컬럼을 더할지 새로 만들지는 빌더가 정한다 |
| `group_study_alert` | `receiver_id, group_id, last_sent_at` | PK `(receiver_id, group_id)` |
| `rooms` (변경) | `group_id` 추가, `invite_code` NULL 허용 | `(group_id)` 부분 unique(`closed_at IS NULL`). 초대코드 방은 `group_id IS NULL` |
| `room_participations` (변경) | `mic_on`, `on_break` 추가 (기본 false) | |

- 그룹은 지우지 않고 `deleted_at`을 채운다. 멤버·신청도 행을 남긴다 (`joined_via`, `leave_reason`은 효과 측정 분석에 쓴다).
- 그룹 공부방은 그룹마다 열린 `rooms` 행이 하나다. 처음 자리를 잡을 때 열고, 지금처럼 마지막 사람이 나가면 닫으며, 다음 입장 때 새로 연다.

## 동작 규칙

- **개수 제한과 동시성**: 그룹 행 → 사용자 행 순서로 `SELECT ... FOR UPDATE`를 잡고 센다(교착 방지를 위해 순서 고정).
  - 들어간 그룹 + 답을 기다리는 신청 ≤ 5 (만들기, 신청, 코드 참여에서 검사)
  - 답을 기다리는 신청 ≤ 3
  - 그룹장인 그룹 ≤ 2 (만들기에서만, 넘겨받기는 보지 않는다)
  - 멤버 ≤ 6 (신청, 수락, 코드 참여). 다 찬 그룹에는 신청할 수 없다(위키 5절). 신청한 뒤에 자리가 차면 신청은 기한까지 남고 수락만 막힌다
  - 신청이 이미 5개 안에 들어 있으므로 수락이 5개를 넘게 만들지 않는다.
- **나가기**: 다른 멤버가 있는 그룹장은 `OWNER_CANNOT_LEAVE`. 혼자 남은 그룹장이 나가면 그룹을 지우고, 답을 기다리던 신청을 `GROUP_DELETED`로 바꿔 `group_application_room_closed`를 보낸다.
- **내보내기**: 바로 멤버가 아니게 되고(`KICKED`), 3일 동안 같은 그룹에 신청·코드 참여가 막힌다. 푸시 `group_member_kicked`.
  - 비공개 그룹이면 같은 트랜잭션에서 초대코드를 새로 발급한다(코드 새로 만들기와 같은 동작). 내보내진 사람이 예전 코드를 기억했다가 돌아오는 것을 막는다 **[합의, 2026-11-03 FE 요청]**
  - 예전 코드·링크로 참여하면 `INVITE_CODE_NOT_FOUND`. 새 코드는 다른 사람이 쓸 수 있고, 내보내진 사람은 새 코드를 받아도 3일 안에는 `RECENTLY_KICKED`다.
  - 새 코드 안내는 FE가 그룹장 화면에 토스트로만 띄운다. 푸시·STOMP 메시지는 없다.
  - 3단계 테스트: 내보낸 뒤 예전 코드 참여 실패, 새 코드로 다른 사람 참여 성공, 내보내진 사람의 3일 안 새 코드 참여는 `RECENTLY_KICKED`, 재발급이 실패하면 내보내기도 롤백.
- **그룹장 넘기기**: 대상은 지금 멤버여야 한다. 푸시 `group_owner_transferred`, 공부방이 열려 있으면 `OWNER_CHANGED`.
- **신청 신고**: 신청을 `DECLINED`로 바꾸고 신고를 남긴다. 신청한 사람을 차단하지는 않는다 (위키 8절에 차단이 없다).
- **공부방 멤버 신고**: 신고를 남기고 차단을 만든다 (이미 있으면 그대로). 그룹 멤버 자격·책상·순위는 그대로다.
- **글자 수**: 이름 1~20자(공백만은 안 됨), 소개 0~500자(비우면 null), 신청 소개 글 1~50자(공백만은 안 됨), 신고 사유 0~100자.
- **연락처·링크 판정** (위키 6-1절): 이름·소개·신청 소개 글에 같은 규칙을 쓴다.
  - 막는 것: `http`·`www`·도메인 꼴(`.com`, `.kr` 등), 이메일, 이어진 숫자 9자리 이상(사이의 `-`와 띄어쓰기도 이어진 것으로 본다), `@`로 시작하는 아이디, 연락 수단 말(`카톡`, `오픈채팅`, `인스타`, `텔레그램`, `디엠` 등) 뒤에 오는 아이디처럼 보이는 글.
  - 통과: `9급`, `토익 900`, `D-100`, `2027 수능`.
  - 판정은 순수 클래스 하나에 두고, 통과·차단 예시 표를 테스트로 만든다. 이 예시 표를 BY-1124 댓글로 FE에 넘겨 같은 결과를 내게 한다.
- **앱 전체 공부 상태**: 멤버마다 아래 순서로 정한다.
  1. 이 그룹의 열린 공부방에 확정된 자리가 있으면 `IN_ROOM`
  2. draft가 없거나 `last_seen_at`이 60초보다 오래됐으면 `IDLE`
  3. draft의 열린 이벤트가 `PAUSE`면 `PAUSED`
  4. 그 밖은 `STUDYING`

  `accumulating`은 draft가 60초 안이고 열린 이벤트가 없을 때 참이다 (`ActiveStudySessionService.isFocusing`과 같은 판단).
  다른 그룹 공부방의 카메라 끔은 앱이 `PAUSE`로 기록하므로 `PAUSED`가 된다.
- **이번 주 평균**: 공개 그룹 전체를 한 번에 계산해 짧게(약 60초) 캐시한다 (랭킹의 조회 시 집계 방식, ADR-0028).
- **성능 기준**: 내 그룹 목록은 최대 5그룹 × 6명 = 30명이다. 오늘·이번 주 합계, draft, 공부방 자리, 마지막 공부 시각은 사람 집합 단위로 한 번씩 읽는다 (사람마다 쿼리하지 않는다).

## 전달 순서

0. **계약 PR**: 이 문서, ADR-0040, 이유 코드·예외 넓히기, 모든 REST 컨트롤러·DTO(501), 시험·목표 9개(실제 로직, 프로필 포함), `websocket.html` 그룹 공부방 절. dev 반영 뒤 BY-1124 댓글로 경로·이유 코드·enum·푸시 표·연락처 예시 표를 낸다.
1. 그룹 만들기, 내 그룹 목록, 그룹 홈(책상 정보)
2. 공개 그룹 목록·보기, 신청·수락·거절, 3일 만료 작업 (내보낸 뒤 3일 검사는 여기서 넣고 기록은 3에서 생긴다)
3. 비공개 초대, 멤버 관리(넘기기·내보내기·나가기·그룹 지우기)
4. 공부방 STOMP
5. 그룹 푸시 8종
6. 신고·차단 (4의 SIGNAL 막기·`PEER_BLOCKED`와 5의 알림 제외까지)

1~6은 FE가 화면을 붙이는 순서다 **[합의]**. 단계마다 서비스 단위 테스트와 API 통합 테스트(MockMvcTester, Testcontainers)를 쓰고, `./gradlew check`와 크로스 리뷰를 거친다.
끝나면 위키 28절에 정한 것을 반영한다 (BY-1124 완료 조건).

## 빌더에게 맡기는 것

- 경로·필드·테이블·클래스의 정확한 이름 (Swagger를 올리기 전까지)
- 501 스텁을 어떤 모양으로 둘지
- 신고를 V1 `report`에 넣을지 새 테이블로 할지
- SIGNAL 차단 검사를 매번 DB로 할지 짧은 캐시를 둘지
- 연락처 판정의 정확한 정규식 (예시 표를 통과하는 한)
- 만료 작업 주기(1분 안팎), 평균 캐시 시간
- ADR을 하나로 쓸지 나눌지 (그룹 모델·공부방 재사용, 앱 전체 상태, 그룹 푸시 기록 분리)

## 열린 문제

- 신고 알림 채널: `C0CE18MJFMM`으로 정했다(2026-11-03, 운영 설정 기본값). 봇을 이 채널에 초대했는지는 출시 전에 확인한다. 담당자는 아직 정하지 않았다.
- 푸시 `link`: FE 라우트가 정해지면 바꾼다.
- 다른 사람이 한 일로 생기는 효과 측정 이벤트(신청 수락으로 멤버가 됨, 내보내짐, 그룹 사라짐)는 당사자 기기에서 그 순간 보낼 수 없다. 서버 DB에 `joined_via`, `leave_reason`을 남겨 분석할 수 있게 해 두고, FE와 어떻게 셀지 맞춘다.

## 구현하며 정한 것 (2026-11-03)

1~6단계 로직을 채우며 0단계 계약과 다르게 되었거나 계약에 적지 않았던 것이다. 그룹·차단 API에서 `501`/`NOT_IMPLEMENTED`는 지웠다.
Swagger 설명과 `websocket.html`도 같은 내용으로 맞췄고, 구조에 관한 결정은 ADR-0040(결정 6~14)에 적었다.

- 그룹 공부방 입장 응답(`POST /api/groups/{groupId}/room/join`)의 `micOn`·`onBreak`는 새 자리면 `null`이고, 끊김 유예 중이던 자리로 다시
  들어온 경우(`graceRejoin: true`)에만 이전 값이 든다.
- 공개 그룹 목록에서 `query`가 100자를 넘거나 `page`가 0~10000 밖이면 `400 REQUEST_FAILED`다(`VALIDATION_FAILED`가 아니다).
- 이미 차단한 사람을 다시 신고해도 `204`다.
- 코드 참여가 `409`로 막히는 이유가 여럿이면 `RECENTLY_KICKED` → `GROUP_FULL` → `GROUP_SLOT_LIMIT` 순서로 하나만 준다.
- 그룹 만들기에서 "그룹+신청 5개"와 "그룹장 2개" 한도가 둘 다 걸리면 `GROUP_SLOT_LIMIT`을 먼저 준다.
- 그룹이 사라진 신청의 수락·거절·신고는 `404 NOT_FOUND`다(`GROUP_NOT_FOUND`가 아니다).
- FE는 받은 `PEER_BLOCKED`를 그 방에서 나갈 때까지 기억한다. 차단을 풀어도 알림 없이 다음 입장부터 풀린다.
