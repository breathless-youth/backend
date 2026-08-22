# BY-415 룸 사용 지표 DB 저장 — 설계

- 티켓: [BY-415](https://breathless-youth.atlassian.net/browse/BY-415)
- 날짜: 2026-08-23
- 상태: 승인됨

## 배경과 목표

소셜(룸) 기능이 실제로 얼마나·어떻게 쓰이는지 팀이 판단할 데이터가 없다. 룸 상태는
`RoomService`의 인메모리 맵에만 있어서 방이 사라지면 아무 기록도 남지 않는다.

이 작업은 **방 생성·참여 이력을 DB에 남겨** 아래 세 가지 분석 질문에 SQL로 답할 수
있게 한다. 소비는 SQL 직접 조회까지만이 이번 범위다 (Slack 일일 리포트 추가는 별도
티켓).

| 분석 질문 | 계산 방법 |
|---|---|
| 채택률 — 얼마나 쓰나 | 일별 방 생성 수, 일별 참여 유저 수, 룸 경험 유저 비율(distinct 참여자 ÷ 활성 유저) |
| 몰입 효과 — 같이 하면 더 오래 하나 | 룸 참여 구간(joined_at~left_at)과 시간대가 겹치는 StudySession vs 안 겹치는 세션의 순공시간 비교 |
| 리텐션 기여 — 쓰면 더 돌아오나 | 룸 사용 유저 vs 비사용 유저의 주간 재방문율 (기존 세션 데이터와 조인) |

## 핵심 결정: 운영 상태는 인메모리 유지, 이력만 DB

세 지표 모두 "무슨 일이 있었나"(cold record)만 필요하고 "지금 누가 방에 있나"(hot
state)는 필요 없다. 룸 운영 상태는 일회성·고빈도 갱신·WebSocket 세션 수명에 강결합된
전형적 hot state라 인메모리가 맞다.

검토한 대안과 기각 사유:

- **DB를 상태 원본으로**: 재시작 생존의 실익이 작다 — WebSocket 연결은 어차피 DB에
  저장할 수 없어 재시작 시 전원 끊김·재접속은 동일하다. 반면 글로벌 락 기반 현재
  구조를 크게 뒤집어야 한다.
- **Redis**: 장점(상태 공유, TTL 네이티브)은 전부 멀티 인스턴스가 전제일 때 빛난다.
  단일 인스턴스인 지금은 복합 연산 원자성 재구현(Lua), 인프라 비용, 핫패스 네트워크
  홉이라는 단점만 즉시 지불한다. 멀티 인스턴스로 가려면 어차피 STOMP simple broker도
  외부 브로커로 바꿔야 해서(인스턴스 로컬) 세트로 별도 아키텍처 작업이다.

**전환 트리거**: 인스턴스를 2대 이상으로 늘려야 하는 시점. 그때 Redis + 외부 STOMP
브로커를 세트로 도입한다.

**배포 중단 대응**: 배포 시 진행 중인 방이 소멸하는 문제는 이번에 쌓는 지표(시간대별
사용량)로 저활성 배포 윈도우를 정하는 것으로 시작한다. 배포 중단이 데이터로 아프다고
확인되면 SIGTERM 상태 스냅숏 저장 + 부팅 복원(기존 유예 30초 메커니즘 재활용)을 별도
티켓으로 올린다.

## 스키마 — Flyway `V10__room_history.sql`

```sql
CREATE TABLE rooms (
    id           BIGSERIAL PRIMARY KEY,
    room_uid     UUID NOT NULL UNIQUE,     -- 인메모리 방과의 연결고리 (재시작 간에도 유일)
    created_by   BIGINT NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL,
    closed_at    TIMESTAMPTZ,              -- NULL = 운영 중이거나 서버 재시작으로 유실
    close_reason VARCHAR(20)               -- LAST_LEFT | EMPTY_EXPIRED
);

CREATE TABLE room_participations (
    id           BIGSERIAL PRIMARY KEY,
    room_uid     UUID NOT NULL REFERENCES rooms (room_uid),
    user_id      BIGINT NOT NULL REFERENCES users (id),
    joined_at    TIMESTAMPTZ NOT NULL,     -- STOMP 확정 시각 (자리 예약 아님)
    left_at      TIMESTAMPTZ,
    leave_reason VARCHAR(20)               -- EXPLICIT | DISCONNECT_TIMEOUT | SWITCHED_ROOM
);
CREATE INDEX idx_room_participations_user ON room_participations (user_id, joined_at);
```

결정 사항:

- **인메모리 방 ID는 DB 키로 쓰지 않는다.** `roomIdSequence`는 재시작마다 0부터
  시작해 ID가 재사용된다. 대신 `Room` 생성 시 `UUID.randomUUID()`로 `room_uid`를
  부여한다 — DB 호출 없이 재시작 간 유일성 확보.
- **`joined_at`은 STOMP 확정(confirmStomp) 시점.** 자리 예약(join API)만 하고 30초
  내 확정하지 않은 경우는 기록하지 않는다 — 몰입 효과 계산에 "실제로 방에 있지 않은
  구간"이 섞이면 안 된다.
- **유예 30초 내 재접속(grace rejoin)은 새 row를 만들지 않는다.** 기존 참여가
  이어진다.

## 컴포넌트 — 도메인 이벤트 발행 → 비동기 기록

글로벌 락(`synchronized`) 안에서 DB I/O를 하면 모든 방의 모든 작업이 DB 지연에
직렬로 묶이므로(락 안 I/O 금지 원칙), 이벤트로 분리한다.

- **도메인 이벤트** (`room/event/`): `RoomCreatedEvent`, `ParticipantJoinedEvent`,
  `ParticipantLeftEvent(reason)`, `RoomClosedEvent(reason)` — 전부 record,
  `roomUid`·`userId`·발생 시각 포함
- **`RoomHistoryRecorder`** (`room/history/`): `@Async` + `@EventListener`로 받아
  JPA 저장. 예외는 로그만 남긴다 — 기록 실패가 룸 동작에 영향을 주지 않는다
- **전용 1스레드 executor**: "방 생성 기록보다 입장 기록이 먼저 처리되는" 순서
  역전을 막기 위해 이 리스너 전용으로 스레드 1개짜리 executor를 쓴다. 큐에 쌓인
  순서대로 처리되므로 발행 순서가 보장된다
- **엔티티/리포지토리** (`room/entity/`, `room/repository/`): `RoomHistory`,
  `RoomParticipation` — Lombok `@Getter` + 명시적 생성자, Spring Data JPA

`RoomService`는 `ApplicationEventPublisher`를 주입받아 상태 변경 지점에서 발행한다.
`@Async` 리스너라 publish는 큐 제출(마이크로초)로 끝나 락 안에서 호출해도 안전하지만,
**동기 리스너를 추가하면 락 안에서 실행되는 함정**이 있으므로 코드에 경고 주석을
남긴다.

## 이벤트 발행 지점 (퇴장 3경로 전부 커버)

| RoomService 경로 | 이벤트 |
|---|---|
| `create` | RoomCreated |
| `confirmStomp` (최초 확정) | ParticipantJoined |
| `leave` | ParticipantLeft(EXPLICIT) |
| `join` 중 기존 방 자동퇴장 | ParticipantLeft(SWITCHED_ROOM) |
| `cleanupExpired` 유예 만료 | ParticipantLeft(DISCONNECT_TIMEOUT) |
| 마지막 인원 퇴장 / 빈 방 10분 만료 | RoomClosed(LAST_LEFT / EMPTY_EXPIRED) |

## 에러 처리와 알려진 한계

- 리스너 예외는 로그만 — 지표는 best-effort, 룸 동작이 항상 우선
- 서버 재시작 시 진행 중이던 row는 `left_at`/`closed_at`이 NULL로 남는다 — 버그가
  아니라 "비정상 종료 구간" 식별 정보로 쓴다 (분석 시 NULL 구간은 제외하거나 별도
  집계)
- 앱 강제종료 등으로 이벤트가 유실될 수 있다 — 분석용 데이터라 허용

## 테스트

- **단위**: `RoomService`가 각 경로에서 올바른 이벤트를 발행하는지 — publisher를
  기록용 스텁으로 주입해 검증
- **통합**: 테스트 프로파일에서 executor를 동기(`SyncTaskExecutor`)로 교체해 async
  대기 없이 join→confirm→leave 흐름 후 테이블 row 검증 (Testcontainers). 새 의존성
  (Awaitility 등) 불필요
