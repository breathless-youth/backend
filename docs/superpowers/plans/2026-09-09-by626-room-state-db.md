# 룸 상태 DB 원본화 (BY-626) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인메모리 `RoomService`(단일 락)를 PostgreSQL 진실 원천으로 옮겨 배포·크래시에도 방과 자리가 유지되게 한다.

**Architecture:** 기존 이력 테이블 `rooms`·`room_participations`가 라이브 상태를 함께 담고(행을 지우지 않는 것이 이력), 전역 락은 DB 제약 + 방 행 `FOR UPDATE` + "락 아래 재조회" + 조건부 갱신으로 대체한다. 쓰기·락 경로는 `JdbcClient` 네이티브 SQL이고 JPA 엔티티는 스키마 검증용이다. 태스크 리스(`live_task`)가 죽은 태스크의 참가자를 회수하고, 오판 시 펜싱(소켓 닫기)으로 복구한다.

**Tech Stack:** Spring Boot 4.1 / Java 25 / PostgreSQL 17 / `JdbcClient`(spring-jdbc 7) / `TransactionTemplate` / HikariCP 7 / Testcontainers 2 / JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-09-08-room-state-db-design.md` (v3). 이 계획은 스펙 §1~§7을 그대로 구현한다. 스펙과 다르게 결정한 것은 아래 "스펙 대비 조정" 표에 있다.

## Global Constraints

- `./gradlew check` 통과 상태에서만 커밋한다(테스트 + spotless + checkstyle + ArchUnit). Docker Desktop이 떠 있어야 Testcontainers 테스트가 돈다(`open -a Docker`).
- checkstyle: 파일 400줄, 메서드 60줄, 순환복잡도 10, 파라미터 7개 이하. `spotlessApply`로 포맷.
- 커밋 컨벤션 `<type>: <설명>`, 한 커밋 한 목적, 본문 끝에 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Jackson 3(`tools.jackson`), Testcontainers 2(`org.testcontainers.postgresql.PostgreSQLContainer`), Boot 4 패키지(`org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails`, `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`).
- 시각은 앱이 파라미터로 넘긴다(DB `now()` 금지). SQL 파라미터는 `Timestamp.from(instant)`, 읽기는 `rs.getTimestamp(col).toInstant()`.
- 상수: 정원 6, 예약 TTL 30초, 끊김 유예 30초, 빈 방 TTL 600초, 초대코드 묘비 600초, heartbeat 5초, 리스 stale 30초, 관찰 기간 30초(테스트 0초).
- 새 의존성 추가 없음. `JdbcClient`·`TransactionTemplate`·`JdbcConnectionDetails`·HikariCP는 이미 classpath에 있다.
- 브랜치 `feat/BY-626-룸-상태-DB-원본화`. 커밋마다 `./gradlew check`.

## 스펙 대비 조정 (구현 편의, 의미 불변)

| 스펙 | 조정 | 이유 |
|---|---|---|
| `invite_code char(4)` | `varchar(4)` | Hibernate `validate`가 `bpchar`를 String 매핑과 다르게 봐서 기동 실패 |
| advisory lock 키 `hashtext(code)` / `userId` | `pg_advisory_xact_lock(:userId)` / `pg_advisory_xact_lock(1099511627776 + hashtext(:code))` | 유저 ID와 코드 해시가 같은 키 공간에서 충돌하지 않게 2^40 오프셋으로 분리 |
| 테스트용 `JpaRepository` 유지 | JPA 저장소 없음. 테스트는 `JdbcClient` 프로브(`RoomProbe`)로 행을 조회 | 프로덕션에 미사용 빈을 두지 않는다 |
| 관찰 기간 30초 상수 | 프로퍼티 `app.room.lease.observation-seconds`(기본 30, 테스트 0) | 통합 테스트에서 30초 대기 없이 회수 경로 검증 |
| 연속성 판정에 `now` 비교 | ~~커밋된 beat 사이 간격만으로 판정~~ → 최종 리뷰에서 되돌림: `since→last` 폭 ≥ 관찰 기간 **그리고** `last`가 주입된 `Clock` 기준 stale 이내 | `now` 항을 빼면 DB 순단 뒤 cleanup 틱이 heartbeat 틱보다 먼저 돌 때 건강한 상대를 회수함. 테스트는 cleanup의 `now` 파라미터가 아니라 주입 Clock을 보므로 영향 없음 |
| `RoomController.create`가 유저 검증 없음 | `userService.getProfile(userId)`로 404 `USER_NOT_FOUND` 선검사 | `rooms.created_by` FK 위반이 500으로 새지 않게. join과 동일 규칙 |

## 파일 구조 (최종)

```
src/main/resources/db/migration/V15__room_live_state.sql           (신규)
src/main/java/project/study/room/
  entity/Room.java                       rooms 엔티티 (RoomHistory 대체, 검증용)
  entity/RoomParticipation.java          확장 (검증용)
  entity/CloseReason.java, LeaveReason.java   event 패키지에서 이동
  repository/RoomRepository.java         JdbcClient: 방 조회·락·생성·종료
  repository/RoomParticipationRepository.java  JdbcClient: 참가자 CRUD·조건부 갱신·후보 조회
  repository/TaskLeaseRepository.java    JdbcClient: 리스 등록·heartbeat·회수
  lease/TaskIdentity.java                태스크 ID (ECS 메타데이터 → UUID 폴백)
  lease/TaskLease.java                   SmartLifecycle(phase -1000), 자체 executor heartbeat, 펜싱, canReclaim
  lease/LeaseConnectionFactory.java      리스 전용 1커넥션 HikariDataSource 생성
  service/AutoLeave.java                 record(roomId, userId)
  service/ParticipantRemover.java        removeParticipant(조건부 DELETE/UPDATE + 방 종료)
  service/RoomService.java               create/join/leave/confirmStomp/handleDisconnect/roomExists
  service/RoomStateService.java          updateState/authorizeSignal/스냅샷/인가 조회
  service/RoomCleanupService.java        진행자: 리스 회수·만료·빈 방 (단계별 TransactionTemplate)
  service/TurnCredentialIssuer.java      (변경 없음)
  websocket/SessionRegistry.java         소켓 핸들·오픈 시각, isOpen/fence
  websocket/SessionTrackingDecorator.java WebSocketHandlerDecorator → registry 등록/해제
  websocket/RoomMessenger.java           세션 스코프 전송·브로드캐스트·ROOM_UNAVAILABLE
  websocket/RoomStompHandler.java        (수정) state 한 문장, signal 인가
  websocket/StompEventListener.java      (수정) confirm 사전/사후 검사, ROOM_UNAVAILABLE
  scheduler/RoomCleanupScheduler.java    (수정) RoomCleanupService + 방별 즉시 브로드캐스트
  controller/RoomController.java         (수정) LeaveResult, create 유저 검증
  dto/RoomMember.java, StateUpdatePayload.java  (수정) focusSec, disconnected
src/main/java/project/study/config/WebSocketConfig.java   (수정) RoomStateService, 데코레이터, 인바운드 executor 8, 거부 시 ROOM_UNAVAILABLE
src/main/java/project/study/studysession/repository/StudySessionMetricsRepository.java (수정) coalesce(left_at, now())
삭제: room/event/*Event.java(4), room/history/RoomHistoryRecorder.java, config/AsyncConfig.java,
      common/logging/MdcTaskDecorator.java, room/service/{ClosedInviteCodes,Room,Participant}.java,
      room/entity/RoomHistory.java, room/repository/{RoomHistoryRepository,RoomParticipationRepository(JPA)}.java
src/test/java/project/study/room/support/RoomProbe.java       테스트 프로브(유저 생성, 행 조회, 시각 조작)
src/test/java/project/study/room/lease/TaskLeaseTest.java      (단위) beat/펜싱/canReclaim
src/test/java/project/study/room/lease/TaskLeaseRepositoryTest.java (통합)
src/test/java/project/study/room/RoomRepositoryTest.java, RoomParticipationRepositoryTest.java (통합)
src/test/java/project/study/room/RoomStateServiceTest.java, RoomCleanupServiceTest.java (통합)
src/test/java/project/study/room/RoomServiceTest.java(이식), RoomServiceConfirmTest.java, RoomExpiryTest.java, RoomInviteCodeErrorTest.java(이식)
src/test/java/project/study/room/RoomConcurrencyTest.java     동시 join·코드 락·두 태스크
src/test/java/project/study/room/websocket/SessionRegistryTest.java, RoomMessengerTest.java
수정: RoomApiTest, RoomStompHandlerTest, StompEventListenerTest, QualifyingSessionQueryIntegrationTest, ArchitectureTest
삭제: RoomServiceEventTest, RoomHistoryRecorderTest, RoomHistoryPersistenceTest, RoomServiceExpiryIndexTest(RoomExpiryTest로 흡수), common/logging/MdcTaskDecoratorTest
docs/adr/0018-room-state-in-postgres.md (신규), docs/erd.dbml (수정), src/test/resources/application.yaml (수정)
```

작업 순서 원칙: 옛 인메모리 `RoomService`는 Task 7에서 한 번에 교체한다. Task 1~6은 옛 서비스와 이름이 겹치지 않는 새 컴포넌트를 쌓고, 각 태스크 끝에 `./gradlew check`가 통과한다.

---

### Task 1: 스키마 V15 + JPA 엔티티 + 이력 기록기·이벤트 제거

**Files:**
- Create: `src/main/resources/db/migration/V15__room_live_state.sql`
- Create: `src/main/java/project/study/room/entity/Room.java`, `entity/CloseReason.java`, `entity/LeaveReason.java`
- Modify: `src/main/java/project/study/room/entity/RoomParticipation.java` (전면 교체)
- Modify: `src/main/java/project/study/room/service/RoomService.java` (이벤트 발행 제거만), `service/Room.java` (uid 제거)
- Modify: `src/test/java/project/study/studysession/QualifyingSessionQueryIntegrationTest.java` (insert 헬퍼 컬럼)
- Modify: `src/test/java/project/study/room/{RoomServiceTest,RoomInviteCodeErrorTest,RoomServiceExpiryIndexTest}.java` (생성자 인자)
- Delete: `room/event/{ParticipantJoinedEvent,ParticipantLeftEvent,RoomClosedEvent,RoomCreatedEvent,CloseReason,LeaveReason}.java`, `room/history/RoomHistoryRecorder.java`, `config/AsyncConfig.java`, `common/logging/MdcTaskDecorator.java`, `room/entity/RoomHistory.java`, `room/repository/RoomHistoryRepository.java`, `room/repository/RoomParticipationRepository.java`
- Delete tests: `room/RoomServiceEventTest.java`, `room/RoomHistoryRecorderTest.java`, `room/RoomHistoryPersistenceTest.java`, `common/logging/MdcTaskDecoratorTest.java`

**Interfaces:**
- Produces: 테이블 `rooms`, `room_participations`, `live_task` (스펙 §1, `invite_code varchar(4)`), enum `project.study.room.entity.CloseReason{LAST_LEFT, EMPTY_EXPIRED}`, `LeaveReason{EXPLICIT, DISCONNECT_TIMEOUT, SWITCHED_ROOM}`.
- 이 태스크가 끝나면 옛 인메모리 `RoomService`는 이벤트 없이 그대로 동작한다(Task 7에서 교체).

- [ ] **Step 1: 마이그레이션 작성**

`src/main/resources/db/migration/V15__room_live_state.sql`:

```sql
-- BY-626: 룸 상태 DB 원본화. 이력 테이블 rooms·room_participations가 라이브 상태를 함께 담는다.
-- 룸 기능 미출시라 두 테이블에는 QA 행만 있어 DROP 후 재생성한다.
DROP TABLE room_participations;
DROP TABLE rooms;

CREATE TABLE live_task (
    task_id      varchar     PRIMARY KEY,     -- ECS 태스크 ID(로그 스트림과 일치), 없으면 UUID
    heartbeat_at timestamptz NOT NULL,
    reclaimed_at timestamptz                  -- NOT NULL = 다른 태스크가 죽었다고 보고 참가자를 회수함(펜싱 신호)
);

CREATE TABLE rooms (
    id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    invite_code  varchar(4)  NOT NULL,
    created_by   bigint      NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL,
    closed_at    timestamptz,                  -- NULL = 열린 방. 닫힌 방은 영구 보존(이력)
    close_reason varchar(20)                   -- LAST_LEFT | EMPTY_EXPIRED
);
CREATE UNIQUE INDEX rooms_open_code_uidx   ON rooms (invite_code) WHERE closed_at IS NULL;
CREATE INDEX        rooms_code_latest_idx  ON rooms (invite_code, created_at DESC);
CREATE INDEX        rooms_open_created_idx ON rooms (created_at) WHERE closed_at IS NULL;

CREATE TABLE room_participations (
    id                 bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    room_id            bigint      NOT NULL REFERENCES rooms (id),
    user_id            bigint      NOT NULL REFERENCES users (id),
    nickname           varchar,
    goal               varchar,
    category           varchar,
    camera_on          boolean     NOT NULL DEFAULT false,
    focus_state        varchar(20) NOT NULL DEFAULT 'FOCUS',
    focus_sec          integer     NOT NULL DEFAULT 0,
    reserved_at        timestamptz NOT NULL,
    stomp_confirmed    boolean     NOT NULL DEFAULT false,
    stomp_session_id   varchar,
    session_opened_at  timestamptz,               -- 마지막으로 확정된 세션의 소켓 오픈 시각
    task_id            varchar,
    disconnected_at    timestamptz,
    joined_at          timestamptz,               -- 최초 STOMP 확정 시각. NULL = 확정된 적 없는 예약
    left_at            timestamptz,               -- NULL = 지금 자리를 가진 참가자
    leave_reason       varchar(20)                -- EXPLICIT | DISCONNECT_TIMEOUT | SWITCHED_ROOM
) WITH (fillfactor = 70);
CREATE UNIQUE INDEX rp_live_user_uidx      ON room_participations (user_id)          WHERE left_at IS NULL;
CREATE UNIQUE INDEX rp_live_room_user_uidx ON room_participations (room_id, user_id) WHERE left_at IS NULL;
CREATE INDEX rp_session_idx      ON room_participations (stomp_session_id) WHERE stomp_session_id IS NOT NULL;
CREATE INDEX rp_reserved_idx     ON room_participations (reserved_at)      WHERE left_at IS NULL AND NOT stomp_confirmed;
CREATE INDEX rp_disconnected_idx ON room_participations (disconnected_at)  WHERE left_at IS NULL AND disconnected_at IS NOT NULL;
CREATE INDEX rp_task_idx         ON room_participations (task_id)          WHERE task_id IS NOT NULL;
CREATE INDEX rp_user_joined_idx  ON room_participations (user_id, joined_at);
```

- [ ] **Step 2: enum 이동 + 엔티티 작성**

`src/main/java/project/study/room/entity/CloseReason.java`:

```java
package project.study.room.entity;

public enum CloseReason {
    LAST_LEFT,
    EMPTY_EXPIRED
}
```

`src/main/java/project/study/room/entity/LeaveReason.java`:

```java
package project.study.room.entity;

public enum LeaveReason {
    EXPLICIT,
    DISCONNECT_TIMEOUT,
    SWITCHED_ROOM
}
```

`src/main/java/project/study/room/entity/Room.java` (기존 `RoomHistory.java`는 삭제):

```java
package project.study.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방 한 개 = 행 하나. 라이브 상태이자 이력이다 (closed_at이 찍히면 닫힌 방, 행은 영구 보존).
 *
 * <p>쓰기·락 경로는 전부 {@code RoomRepository}의 네이티브 SQL이다. 이 엔티티는 {@code ddl-auto: validate}
 * 스키마 검증과 테스트 조회에만 쓴다 — 영속성 컨텍스트가 낡은 값을 돌려주는 문제를 피하기 위해
 * 운영 코드에서 관리 엔티티와 네이티브 갱신을 같은 트랜잭션에서 섞지 않는다 (스펙 §2 원칙 3).
 */
@Table(name = "rooms")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4)
    private String inviteCode;

    @Column(nullable = false, updatable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CloseReason closeReason;
}
```

`src/main/java/project/study/room/entity/RoomParticipation.java` (전면 교체):

```java
package project.study.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방의 자리 하나 = 행 하나. 예약 → STOMP 확정 → (끊김 유예) → 퇴장의 전이가 컬럼으로 표현된다.
 * left_at이 NULL이면 지금 자리를 가진 참가자, 아니면 이력. joined_at이 NULL이면 확정된 적 없는 예약이라
 * 만료 시 이력 없이 삭제된다. 스키마 검증·테스트 조회 전용 (운영 경로는 RoomParticipationRepository의 SQL).
 */
@Table(name = "room_participations")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private Long userId;

    private String nickname;
    private String goal;
    private String category;

    @Column(nullable = false)
    private boolean cameraOn;

    @Column(nullable = false, length = 20)
    private String focusState;

    @Column(nullable = false)
    private int focusSec;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(nullable = false)
    private boolean stompConfirmed;

    private String stompSessionId;
    private Instant sessionOpenedAt;
    private String taskId;
    private Instant disconnectedAt;
    private Instant joinedAt;
    private Instant leftAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LeaveReason leaveReason;
}
```

- [ ] **Step 3: 이벤트·기록기·비동기 실행기 삭제**

```bash
git rm src/main/java/project/study/room/event/ParticipantJoinedEvent.java \
       src/main/java/project/study/room/event/ParticipantLeftEvent.java \
       src/main/java/project/study/room/event/RoomClosedEvent.java \
       src/main/java/project/study/room/event/RoomCreatedEvent.java \
       src/main/java/project/study/room/event/CloseReason.java \
       src/main/java/project/study/room/event/LeaveReason.java \
       src/main/java/project/study/room/history/RoomHistoryRecorder.java \
       src/main/java/project/study/config/AsyncConfig.java \
       src/main/java/project/study/common/logging/MdcTaskDecorator.java \
       src/main/java/project/study/room/entity/RoomHistory.java \
       src/main/java/project/study/room/repository/RoomHistoryRepository.java \
       src/main/java/project/study/room/repository/RoomParticipationRepository.java \
       src/test/java/project/study/room/RoomServiceEventTest.java \
       src/test/java/project/study/room/RoomHistoryRecorderTest.java \
       src/test/java/project/study/room/RoomHistoryPersistenceTest.java \
       src/test/java/project/study/common/logging/MdcTaskDecoratorTest.java
```

- [ ] **Step 4: 옛 인메모리 RoomService에서 이벤트 발행 제거 (Task 7까지의 임시 상태)**

`src/main/java/project/study/room/service/RoomService.java`에서:
- import `org.springframework.context.ApplicationEventPublisher`, `project.study.room.event.*` 6개 삭제.
- 필드 `private final ApplicationEventPublisher eventPublisher;`와 생성자 파라미터 `ApplicationEventPublisher eventPublisher`, 대입 삭제. 생성자 시그니처는 `RoomService(String turnSecret, int turnTtlSeconds, List<String> turnUrls)`가 된다.
- `create()`의 `publish(new RoomCreatedEvent(...))` 줄 삭제.
- `confirmStomp()`의 `publish(new ParticipantJoinedEvent(...))` 줄 삭제 (`firstConfirmedAt` 대입은 남긴다).
- `removeParticipant()`의 `if (removed.firstConfirmedAt != null) { publish(...); }` 블록 삭제. `LeaveReason` 파라미터 타입은 `project.study.room.entity.LeaveReason`으로 import 변경.
- `destroyRoom()`의 `publish(new RoomClosedEvent(...))` 줄 삭제. `CloseReason` import를 `project.study.room.entity.CloseReason`으로 변경.
- `private void publish(Object event)` 메서드 삭제.

`src/main/java/project/study/room/service/Room.java`에서 `final UUID uid = UUID.randomUUID();` 줄과 `java.util.UUID` import 삭제.

- [ ] **Step 5: 테스트 생성자 호출 갱신**

`RoomServiceTest`, `RoomInviteCodeErrorTest`, `RoomServiceExpiryIndexTest`의 `setUp()`:

```java
roomService = new RoomService("test-secret", 86400, List.of());
```

- [ ] **Step 6: 소셜 지표 테스트의 insert 헬퍼를 새 컬럼으로**

`src/test/java/project/study/studysession/QualifyingSessionQueryIntegrationTest.java`에서 `insertRoom`·`insertParticipation`을 교체하고 `java.util.UUID` import는 `insertUser`가 쓰므로 남긴다:

```java
    private long insertRoom(long createdBy) {
        return jdbcTemplate.queryForObject(
                "insert into rooms (invite_code, created_by, created_at) values ('0000', ?, now()) returning id",
                Long.class,
                createdBy);
    }

    private void insertParticipation(long roomId, long userId, Instant joinedAt, Instant leftAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at, left_at) "
                        + "values (?, ?, ?, true, ?, ?)",
                roomId,
                userId,
                Timestamp.from(joinedAt),
                Timestamp.from(joinedAt),
                leftAt == null ? null : Timestamp.from(leftAt));
    }
```

테스트 본문의 `UUID room = insertRoom(...)`를 `long room = insertRoom(...)`로 바꾼다(5곳). 같은 테스트 안에서 `'0000'` 코드로 열린 방이 둘 생기지 않게 `insertRoom`은 유저당 한 번만 호출된다(`rooms_open_code_uidx`). `다른_유저의_참여는_소셜로_치지_않는다`는 방을 하나만 만든다.

- [ ] **Step 7: 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: BUILD SUCCESSFUL. `QualifyingSessionQueryIntegrationTest`의 `종료시각이_없는_stale_참여는_소셜로_치지_않는다`는 아직 옛 쿼리라 그대로 통과한다.

```bash
git add -A src/main/resources/db/migration/V15__room_live_state.sql src/main/java/project/study src/test/java/project/study
git commit -m "refactor: 룸 이력 테이블을 라이브 상태 스키마로 재정의하고 이벤트 기록기를 제거 (BY-626)

rooms·room_participations가 라이브 상태와 이력을 함께 담는다(V15). 이벤트 4종·RoomHistoryRecorder·
AsyncConfig·MdcTaskDecorator를 지우고, 인메모리 RoomService는 Task 7 교체 전까지 이벤트 없이 동작한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: 테스트 프로브 + RoomRepository (JdbcClient)

**Files:**
- Create: `src/test/java/project/study/room/support/RoomProbe.java`
- Create: `src/main/java/project/study/room/repository/RoomRepository.java`
- Test: `src/test/java/project/study/room/RoomRepositoryTest.java`

**Interfaces:**
- Produces: `RoomRepository.RoomRow(Long id, String inviteCode, Long createdBy, Instant createdAt, Instant closedAt, String closeReason)` with `isOpen()`; methods `lockUser(Long)`, `lockInviteCode(String)`, `insertIfCodeFree(String code, Long createdBy, Instant now, Instant tombstoneCutoff) → Optional<Long>`, `findLatestByCode(String) → Optional<RoomRow>`, `lockById(Long) → Optional<RoomRow>`, `lockByIds(Collection<Long>) → List<RoomRow>`, `isOpen(Long) → boolean`, `closeIfEmpty(Long roomId, String inviteCode, CloseReason reason, Instant now) → boolean`, `findEmptyOpenRoomsCreatedBefore(Instant) → List<RoomRow>`.
- Produces (테스트): `RoomProbe` — `insertUser()`, `room(id)`, `participation(roomId, userId)`, `closeRoomAt`, `setReservedAt`, `setDisconnectedAt`, `setHeartbeat`, `setReclaimed`, `heartbeatAt`, `reclaimedAt`, `countLive`.

- [ ] **Step 1: 테스트 프로브 작성**

`src/test/java/project/study/room/support/RoomProbe.java`:

```java
package project.study.room.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 룸 통합 테스트 전용 DB 프로브 — 유저 생성, 행 조회, 시각 조작. 운영 코드가 아니라 SQL을 그대로 쓴다. */
public class RoomProbe {

    public record Participation(
            Long id,
            Long roomId,
            Long userId,
            boolean stompConfirmed,
            String stompSessionId,
            String taskId,
            Instant disconnectedAt,
            Instant joinedAt,
            Instant leftAt,
            String leaveReason,
            int focusSec,
            boolean cameraOn,
            String focusState) {}

    public record RoomRow(Long id, String inviteCode, Instant closedAt, String closeReason) {}

    private static final RowMapper<Participation> PARTICIPATION = (rs, n) -> new Participation(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getLong("user_id"),
            rs.getBoolean("stomp_confirmed"),
            rs.getString("stomp_session_id"),
            rs.getString("task_id"),
            instant(rs, "disconnected_at"),
            instant(rs, "joined_at"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"),
            rs.getInt("focus_sec"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"));

    private static final RowMapper<RoomRow> ROOM = (rs, n) ->
            new RoomRow(rs.getLong("id"), rs.getString("invite_code"), instant(rs, "closed_at"), rs.getString("close_reason"));

    private final JdbcClient jdbc;

    public RoomProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public long insertUser() {
        return jdbc.sql("insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', :device, now(), now()) returning id")
                .param("device", UUID.randomUUID().toString())
                .query(Long.class)
                .single();
    }

    public Optional<RoomRow> room(long roomId) {
        return jdbc.sql("select id, invite_code, closed_at, close_reason from rooms where id = :id")
                .param("id", roomId)
                .query(ROOM)
                .optional();
    }

    /** (방, 유저)의 가장 최근 행 — 이력까지 포함. */
    public Optional<Participation> participation(long roomId, long userId) {
        return jdbc.sql("select * from room_participations where room_id = :roomId and user_id = :userId "
                        + "order by id desc limit 1")
                .param("roomId", roomId)
                .param("userId", userId)
                .query(PARTICIPATION)
                .optional();
    }

    public List<Participation> participationsOfUser(long userId) {
        return jdbc.sql("select * from room_participations where user_id = :userId order by id")
                .param("userId", userId)
                .query(PARTICIPATION)
                .list();
    }

    public int countLive(long roomId) {
        return jdbc.sql("select count(*) from room_participations where room_id = :roomId and left_at is null")
                .param("roomId", roomId)
                .query(Integer.class)
                .single();
    }

    public void closeRoomAt(long roomId, Instant closedAt) {
        jdbc.sql("update rooms set closed_at = :at, close_reason = 'LAST_LEFT' where id = :id")
                .param("at", Timestamp.from(closedAt))
                .param("id", roomId)
                .update();
    }

    public void setReservedAt(long roomId, long userId, Instant at) {
        jdbc.sql("update room_participations set reserved_at = :at "
                        + "where room_id = :roomId and user_id = :userId and left_at is null")
                .param("at", Timestamp.from(at))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    public void setDisconnectedAt(long roomId, long userId, Instant at) {
        jdbc.sql("update room_participations set disconnected_at = :at "
                        + "where room_id = :roomId and user_id = :userId and left_at is null")
                .param("at", Timestamp.from(at))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    public void setHeartbeat(String taskId, Instant at) {
        jdbc.sql("update live_task set heartbeat_at = :at where task_id = :id")
                .param("at", Timestamp.from(at))
                .param("id", taskId)
                .update();
    }

    public void setReclaimed(String taskId, Instant at) {
        jdbc.sql("update live_task set reclaimed_at = :at where task_id = :id")
                .param("at", at == null ? null : Timestamp.from(at))
                .param("id", taskId)
                .update();
    }

    public Optional<Instant> heartbeatAt(String taskId) {
        return jdbc.sql("select heartbeat_at from live_task where task_id = :id")
                .param("id", taskId)
                .query((rs, n) -> instant(rs, "heartbeat_at"))
                .optional();
    }

    public Optional<Instant> reclaimedAt(String taskId) {
        return jdbc.sql("select reclaimed_at from live_task where task_id = :id")
                .param("id", taskId)
                .query((rs, n) -> Optional.ofNullable(instant(rs, "reclaimed_at")))
                .optional()
                .flatMap(o -> o);
    }
}
```

- [ ] **Step 2: RoomRepository 실패 테스트 작성**

`src/test/java/project/study/room/RoomRepositoryTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.entity.CloseReason;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(600);

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long owner;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        owner = probe.insertUser();
    }

    @Test
    void 열린_방이_없는_코드는_발급된다() {
        Optional<Long> id = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF);

        assertThat(id).isPresent();
        assertThat(probe.room(id.get())).get().extracting(RoomProbe.RoomRow::inviteCode).isEqualTo("1234");
    }

    @Test
    void 같은_코드의_열린_방이_있으면_발급되지_않는다() {
        rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF);

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isEmpty();
    }

    @Test
    void 닫힌_지_10분_안인_코드는_묘비라_발급되지_않는다() {
        long closed = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        probe.closeRoomAt(closed, NOW.minusSeconds(599));

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isEmpty();
    }

    @Test
    void 닫힌_지_10분이_지난_코드는_다시_발급된다() {
        long closed = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        probe.closeRoomAt(closed, NOW.minusSeconds(601));

        assertThat(rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF)).isPresent();
    }

    @Test
    void 코드로_찾으면_가장_최근_방이_나온다() {
        long first = rooms.insertIfCodeFree("1234", owner, NOW.minusSeconds(1000), CUTOFF.minusSeconds(1000))
                .orElseThrow();
        probe.closeRoomAt(first, NOW.minusSeconds(900));
        long second = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();

        assertThat(rooms.findLatestByCode("1234")).get().extracting(RoomRow::id).isEqualTo(second);
        assertThat(rooms.findLatestByCode("0000")).isEmpty();
    }

    @Test
    void 라이브_참가자가_없을_때만_닫힌다() {
        long roomId = rooms.insertIfCodeFree("1234", owner, NOW, CUTOFF).orElseThrow();
        jdbc.sql("insert into room_participations (room_id, user_id, reserved_at) values (:r, :u, :t)")
                .param("r", roomId)
                .param("u", owner)
                .param("t", java.sql.Timestamp.from(NOW))
                .update();

        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW)).isFalse();
        assertThat(rooms.isOpen(roomId)).isTrue();

        jdbc.sql("update room_participations set left_at = :t where room_id = :r")
                .param("t", java.sql.Timestamp.from(NOW))
                .param("r", roomId)
                .update();

        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW)).isTrue();
        assertThat(rooms.isOpen(roomId)).isFalse();
        assertThat(rooms.closeIfEmpty(roomId, "1234", CloseReason.LAST_LEFT, NOW))
                .as("이미 닫힌 방은 다시 닫히지 않는다(멱등)")
                .isFalse();
        assertThat(probe.room(roomId)).get().extracting(RoomProbe.RoomRow::closeReason).isEqualTo("LAST_LEFT");
    }

    @Test
    void 여러_방을_id_오름차순으로_잠근다() {
        long a = rooms.insertIfCodeFree("1111", owner, NOW, CUTOFF).orElseThrow();
        long b = rooms.insertIfCodeFree("2222", owner, NOW, CUTOFF).orElseThrow();

        List<RoomRow> locked = rooms.lockByIds(List.of(b, a));

        assertThat(locked).extracting(RoomRow::id).containsExactly(a, b);
        assertThat(rooms.lockById(a)).get().extracting(RoomRow::isOpen).isEqualTo(true);
        assertThat(rooms.lockById(999_999L)).isEmpty();
    }

    @Test
    void 참가자_없이_기한이_지난_열린_방만_빈_방_후보다() {
        long old = rooms.insertIfCodeFree("1111", owner, NOW.minusSeconds(601), CUTOFF).orElseThrow();
        rooms.insertIfCodeFree("2222", owner, NOW.minusSeconds(10), CUTOFF);

        assertThat(rooms.findEmptyOpenRoomsCreatedBefore(NOW.minusSeconds(600)))
                .extracting(RoomRow::id)
                .containsExactly(old);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "project.study.room.RoomRepositoryTest" -q`
Expected: 컴파일 실패 (`RoomRepository`, `RoomProbe` 없음).

- [ ] **Step 4: RoomRepository 구현**

`src/main/java/project/study/room/repository/RoomRepository.java`:

```java
package project.study.room.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.room.entity.CloseReason;

/**
 * rooms 테이블의 쓰기·락 경로. 전부 네이티브 SQL + record — JPA 1차 캐시가 낡은 값을 돌려주는 문제를 피한다.
 *
 * <p>advisory lock 키 공간: 유저 락은 userId 그대로, 초대코드 락은 2^40 + hashtext(code). 두 종류가 같은
 * 키에서 충돌하지 않게 분리한다. 락 순서는 스펙 §2 — 유저 → 방 행 → 참가자 행 → 코드(닫을 때만, 항상 마지막).
 */
@Repository
@RequiredArgsConstructor
public class RoomRepository {

    private static final long CODE_LOCK_OFFSET = 1L << 40;

    public record RoomRow(
            Long id, String inviteCode, Long createdBy, Instant createdAt, Instant closedAt, String closeReason) {
        public boolean isOpen() {
            return closedAt == null;
        }
    }

    private static final String COLUMNS = "id, invite_code, created_by, created_at, closed_at, close_reason";

    private static final RowMapper<RoomRow> ROW = (rs, n) -> new RoomRow(
            rs.getLong("id"),
            rs.getString("invite_code"),
            rs.getLong("created_by"),
            instant(rs, "created_at"),
            instant(rs, "closed_at"),
            rs.getString("close_reason"));

    private final JdbcClient jdbc;

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 같은 유저의 동시 join 직렬화 (트랜잭션 끝까지 유지). */
    public void lockUser(Long userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", userId).query().listOfRows();
    }

    /** 같은 코드의 발급과 닫힘을 직렬화한다 — 묘비 기간 재발급 금지의 실제 보장 주체. */
    public void lockInviteCode(String inviteCode) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:offset + hashtext(:code))")
                .param("offset", CODE_LOCK_OFFSET)
                .param("code", inviteCode)
                .query()
                .listOfRows();
    }

    /**
     * 코드가 비어 있을 때만 방을 만든다. 열린 방 충돌은 부분 유니크 인덱스의 ON CONFLICT가, 묘비(닫힌 지
     * 10분 안) 충돌은 NOT EXISTS가 거른다. 코드 락을 먼저 잡아 같은 순간의 닫힘과 직렬화한다.
     */
    public Optional<Long> insertIfCodeFree(String inviteCode, Long createdBy, Instant now, Instant tombstoneCutoff) {
        lockInviteCode(inviteCode);
        return jdbc.sql(
                        """
                        INSERT INTO rooms (invite_code, created_by, created_at)
                        SELECT :code, :createdBy, :now
                        WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE invite_code = :code AND closed_at > :cutoff)
                        ON CONFLICT (invite_code) WHERE closed_at IS NULL DO NOTHING
                        RETURNING id""")
                .param("code", inviteCode)
                .param("createdBy", createdBy)
                .param("now", ts(now))
                .param("cutoff", ts(tombstoneCutoff))
                .query(Long.class)
                .optional();
    }

    public Optional<RoomRow> findLatestByCode(String inviteCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE invite_code = :code ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("code", inviteCode)
                .query(ROW)
                .optional();
    }

    public Optional<RoomRow> lockById(Long roomId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE id = :id FOR UPDATE")
                .param("id", roomId)
                .query(ROW)
                .optional();
    }

    /** 여러 방을 id 오름차순으로 잠근다 — join의 방 전환에서 데드락을 피하는 고정 순서. */
    public List<RoomRow> lockByIds(Collection<Long> roomIds) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", roomIds)
                .query(ROW)
                .list();
    }

    public boolean isOpen(Long roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :id AND closed_at IS NULL)")
                .param("id", roomId)
                .query(Boolean.class)
                .single();
    }

    /** 라이브 참가자가 없을 때만 닫는다. 호출자가 방 행 락을 쥔 상태여야 하고, 코드 락은 여기서 마지막으로 잡는다. */
    public boolean closeIfEmpty(Long roomId, String inviteCode, CloseReason reason, Instant now) {
        lockInviteCode(inviteCode);
        int updated = jdbc.sql(
                        """
                        UPDATE rooms SET closed_at = :now, close_reason = :reason
                         WHERE id = :id AND closed_at IS NULL
                           AND NOT EXISTS (SELECT 1 FROM room_participations WHERE room_id = :id AND left_at IS NULL)""")
                .param("now", ts(now))
                .param("reason", reason.name())
                .param("id", roomId)
                .update();
        return updated == 1;
    }

    public List<RoomRow> findEmptyOpenRoomsCreatedBefore(Instant deadline) {
        return jdbc.sql(
                        """
                        SELECT r.id, r.invite_code, r.created_by, r.created_at, r.closed_at, r.close_reason
                          FROM rooms r
                         WHERE r.closed_at IS NULL AND r.created_at < :deadline
                           AND NOT EXISTS (SELECT 1 FROM room_participations p WHERE p.room_id = r.id AND p.left_at IS NULL)
                         ORDER BY r.id""")
                .param("deadline", ts(deadline))
                .query(ROW)
                .list();
    }
}
```

- [ ] **Step 5: 통과 확인 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew test --tests "project.study.room.RoomRepositoryTest" -q && ./gradlew check -q`
Expected: 8개 테스트 통과, BUILD SUCCESSFUL.

```bash
git add src/main/java/project/study/room/repository/RoomRepository.java src/test/java/project/study/room/support/RoomProbe.java src/test/java/project/study/room/RoomRepositoryTest.java
git commit -m "feat: rooms 테이블 JdbcClient 저장소 — 코드 락·묘비 검사·조건부 종료 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: DTO 이름 정리(focusSec·disconnected) + RoomParticipationRepository (JdbcClient)

**Files:**
- Modify: `src/main/java/project/study/room/dto/RoomMember.java`, `dto/StateUpdatePayload.java`
- Modify: `src/main/java/project/study/room/service/Room.java`(옛 인메모리, `confirmedMembers()`), `websocket/RoomStompHandler.java`(`focusSec` 키), `src/test/java/project/study/room/RoomStompHandlerTest.java`, `RoomServiceTest.java`
- Create: `src/main/java/project/study/room/repository/RoomParticipationRepository.java`
- Test: `src/test/java/project/study/room/RoomParticipationRepositoryTest.java`

**Interfaces:**
- Produces: `RoomMember(Long userId, String nickname, String goal, String category, boolean cameraOn, String focusState, int focusSec, boolean disconnected)`, `StateUpdatePayload(Boolean cameraOn, String focusState, Integer focusSec)`.
- Produces: `RoomParticipationRepository.Row(...)`, `Profile(nickname, goal, category)`, `ExpiryWindow(reservationDeadline, graceDeadline)`, `Candidate(id, roomId, userId)` 및 메서드(아래 코드의 public 시그니처가 계약이다).

- [ ] **Step 1: DTO 변경과 옛 코드·테스트의 기계적 갱신**

`src/main/java/project/study/room/dto/RoomMember.java`:

```java
package project.study.room.dto;

// disconnected: 소켓이 끊겨 30초 유예 중인 멤버 (FE가 "재접속 중"으로 표시하고 후속 재대조 시점을 잡는 데 쓴다)
public record RoomMember(
        Long userId,
        String nickname,
        String goal,
        String category,
        boolean cameraOn,
        String focusState,
        int focusSec,
        boolean disconnected) {}
```

`src/main/java/project/study/room/dto/StateUpdatePayload.java`:

```java
package project.study.room.dto;

// focusSec: 클라이언트가 주기 보고하는 순공 타이머(초). 옛 이름 studySeconds는 받지 않는다 (룸 출시와 함께 계약 변경)
public record StateUpdatePayload(Boolean cameraOn, String focusState, Integer focusSec) {}
```

옛 인메모리 `service/Room.java`의 `confirmedMembers()` 매핑을 `new RoomMember(p.userId, p.nickname, p.goal, p.category, p.cameraOn, p.focusState, p.studySeconds, p.disconnectedAt != null)`로 바꾼다.

`websocket/RoomStompHandler.java`: `handleState`의 로그·호출에서 `payload.studySeconds()` → `payload.focusSec()`, `broadcastStudyTime`의 파라미터 이름 `focusSec`, 브로드캐스트 맵 키 `"studySeconds"` → `"focusSec"` (`"type", "STUDY_TIME"`은 그대로).

`RoomStompHandlerTest`의 `new RoomMember(1L, "닉네임", "목표", "수능", true, "FOCUS", 120)` 두 곳과 `new RoomMember(1L, "닉네임", null, null, false, "FOCUS", 0)`에 마지막 인자 `false`를 추가한다. `RoomServiceTest.마지막_순공시간이_스냅샷에_실린다`의 `.studySeconds()` 두 곳을 `.focusSec()`로 바꾼다.

- [ ] **Step 2: 실패 테스트 작성**

`src/test/java/project/study/room/RoomParticipationRepositoryTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomParticipationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", "정처기", "CERTIFICATE");
    private static final ExpiryWindow WINDOW = new ExpiryWindow(NOW.minusSeconds(30), NOW.minusSeconds(30));

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long roomId;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        userId = probe.insertUser();
        roomId = rooms.insertIfCodeFree("1234", userId, NOW, NOW.minusSeconds(600)).orElseThrow();
    }

    private long reserve(long user, Instant at) {
        return participations.insertReservation(roomId, user, PROFILE, at);
    }

    @Test
    void 예약_행은_미확정이고_유저의_라이브_방으로_조회된다() {
        long id = reserve(userId, NOW);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.stompConfirmed()).isFalse();
        assertThat(row.joinedAt()).isNull();
        assertThat(participations.findLiveRoomIdOfUser(userId)).contains(roomId);
        assertThat(participations.countLive(roomId)).isEqualTo(1);
        assertThat(participations.existsLive(roomId, userId)).isTrue();
    }

    @Test
    void 확정은_최초_1회만_joined_at을_채우고_세션을_기록한다() {
        reserve(userId, NOW);

        assertThat(participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW)).isEqualTo(1);
        Instant joinedAt = participations.lockLive(roomId, userId).orElseThrow().joinedAt();
        assertThat(participations.confirm(roomId, userId, "s2", NOW.plusSeconds(5), "task-A", NOW.plusSeconds(60)))
                .isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.joinedAt()).isEqualTo(joinedAt);
        assertThat(row.stompSessionId()).isEqualTo("s2");
        assertThat(row.taskId()).isEqualTo("task-A");
    }

    @Test
    void 더_늦게_열린_세션이_확정돼_있으면_옛_세션의_확정은_0행이다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "new", NOW.plusSeconds(10), "task-A", NOW);

        assertThat(participations.confirm(roomId, userId, "old", NOW, "task-A", NOW)).isZero();
        assertThat(participations.lockLive(roomId, userId).orElseThrow().stompSessionId()).isEqualTo("new");
    }

    @Test
    void state_갱신은_현재_세션의_확정_멤버에게만_적용되고_null_필드는_유지된다() {
        reserve(userId, NOW);
        assertThat(participations.updateState(roomId, userId, "s1", true, "DISTRACTED", 100)).isZero();

        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);
        assertThat(participations.updateState(roomId, userId, "s1", true, null, 100)).isEqualTo(1);
        assertThat(participations.updateState(roomId, userId, "stale", false, "DISTRACTED", 0)).isZero();

        RoomMember member = participations.findConfirmedMembers(roomId).getFirst();
        assertThat(member.cameraOn()).isTrue();
        assertThat(member.focusState()).isEqualTo("FOCUS");
        assertThat(member.focusSec()).isEqualTo(100);
        assertThat(member.disconnected()).isFalse();
    }

    @Test
    void 끊김은_세션_ID가_일치할_때만_기록되고_스냅샷에_disconnected로_실린다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);

        assertThat(participations.markDisconnected("other", NOW)).isZero();
        assertThat(participations.markDisconnected("s1", NOW)).isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.disconnectedAt()).isEqualTo(NOW);
        assertThat(row.stompSessionId()).isNull();
        assertThat(row.taskId()).isNull();
        assertThat(participations.findConfirmedMembers(roomId).getFirst().disconnected()).isTrue();
        assertThat(participations.isActiveSession(roomId, userId, "s1")).isFalse();
    }

    @Test
    void 만료_조건이_붙은_삭제와_퇴장은_조건에_맞을_때만_바뀌고_두_번째는_0행이다() {
        long unconfirmed = reserve(userId, NOW.minusSeconds(31));
        long other = probe.insertUser();
        long confirmedId = reserve(other, NOW);
        participations.confirm(roomId, other, "s1", NOW, "task-A", NOW);
        participations.markDisconnected("s1", NOW.minusSeconds(31));

        assertThat(participations.deleteUnconfirmed(unconfirmed, new ExpiryWindow(NOW.minusSeconds(60), NOW)))
                .as("예약이 아직 창 안이면 삭제되지 않는다")
                .isZero();
        assertThat(participations.deleteUnconfirmed(unconfirmed, WINDOW)).isEqualTo(1);
        assertThat(participations.markLeft(confirmedId, LeaveReason.DISCONNECT_TIMEOUT, NOW, WINDOW)).isEqualTo(1);
        assertThat(participations.markLeft(confirmedId, LeaveReason.EXPLICIT, NOW, null))
                .as("이미 나간 행은 다시 바뀌지 않는다")
                .isZero();

        RoomProbe.Participation left = probe.participation(roomId, other).orElseThrow();
        assertThat(left.leftAt()).isEqualTo(NOW);
        assertThat(left.leaveReason()).isEqualTo("DISCONNECT_TIMEOUT");
        assertThat(probe.participation(roomId, userId)).as("확정 없는 예약은 이력 없이 삭제").isEmpty();
    }

    @Test
    void 만료_후보는_미확정_예약과_유예_만료만_고른다() {
        reserve(userId, NOW.minusSeconds(31));
        long fresh = probe.insertUser();
        reserve(fresh, NOW);
        long graced = probe.insertUser();
        reserve(graced, NOW);
        participations.confirm(roomId, graced, "s1", NOW, "task-A", NOW);
        participations.markDisconnected("s1", NOW.minusSeconds(31));

        List<Candidate> candidates = participations.findExpiryCandidates(WINDOW);

        assertThat(candidates).extracting(Candidate::userId).containsExactlyInAnyOrder(userId, graced);
    }

    @Test
    void 죽은_태스크의_참가자는_끊김으로_전환된다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-dead", NOW);

        assertThat(participations.reclaimByTask("task-dead", NOW)).isEqualTo(1);

        Row row = participations.lockLive(roomId, userId).orElseThrow();
        assertThat(row.disconnectedAt()).isEqualTo(NOW);
        assertThat(row.taskId()).isNull();
        assertThat(row.stompSessionId()).isNull();
    }

    @Test
    void 리스_행이_없는_task_id의_참가자도_회수된다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "never-registered", NOW);

        assertThat(participations.reclaimOrphansWithoutLease(NOW)).isGreaterThanOrEqualTo(1); // 다른 테스트가 커밋한 고아가 섞일 수 있다
        assertThat(participations.lockLive(roomId, userId).orElseThrow().disconnectedAt()).isEqualTo(NOW);
    }

    @Test
    void 활성_세션_스냅샷은_요청자가_현재_세션의_확정_멤버일_때만_목록을_준다() {
        reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);
        long other = probe.insertUser();
        reserve(other, NOW);

        assertThat(participations.findConfirmedMembersForActiveSession(roomId, userId, "s1"))
                .extracting(RoomMember::userId)
                .containsExactly(userId);
        assertThat(participations.findConfirmedMembersForActiveSession(roomId, userId, "old")).isEmpty();
        assertThat(participations.findConfirmedMembersForActiveSession(roomId, other, "s2")).isEmpty();
        assertThat(participations.findLiveByRoomAndUsers(roomId, Set.of(userId, other)))
                .extracting(Row::userId)
                .containsExactlyInAnyOrder(userId, other);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "project.study.room.RoomParticipationRepositoryTest" -q`
Expected: 컴파일 실패 (`RoomParticipationRepository` 없음).

- [ ] **Step 4: 구현**

`src/main/java/project/study/room/repository/RoomParticipationRepository.java`:

```java
package project.study.room.repository;

import static project.study.room.repository.RoomRepository.instant;
import static project.study.room.repository.RoomRepository.ts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;

/**
 * room_participations의 쓰기·락·조건부 갱신 경로. 모든 갱신은 갱신 건수를 돌려주고 호출자가 0건을
 * "상황이 바뀜"으로 처리한다 (스펙 §2). 방 인원이 바뀌는 연산(insert/delete/markLeft)은 호출자가 방 행 락을
 * 쥔 채 부르고, 단일 행 조건부 갱신(confirm/markDisconnected/updateState)은 락 없이 부른다.
 */
@Repository
@RequiredArgsConstructor
public class RoomParticipationRepository {

    public record Row(
            Long id,
            Long roomId,
            Long userId,
            String nickname,
            String goal,
            String category,
            boolean cameraOn,
            String focusState,
            int focusSec,
            Instant reservedAt,
            boolean stompConfirmed,
            String stompSessionId,
            Instant sessionOpenedAt,
            String taskId,
            Instant disconnectedAt,
            Instant joinedAt,
            Instant leftAt,
            String leaveReason) {}

    public record Profile(String nickname, String goal, String category) {}

    /** cleanup 경로의 만료 조건. null이면 조건 없음(명시 퇴장·방 전환). */
    public record ExpiryWindow(Instant reservationDeadline, Instant graceDeadline) {}

    public record Candidate(Long id, Long roomId, Long userId) {}

    private static final String COLUMNS = "id, room_id, user_id, nickname, goal, category, camera_on, focus_state, "
            + "focus_sec, reserved_at, stomp_confirmed, stomp_session_id, session_opened_at, task_id, "
            + "disconnected_at, joined_at, left_at, leave_reason";

    private static final String EXPIRED = "((NOT stomp_confirmed AND reserved_at < :reservationDeadline) "
            + "OR disconnected_at < :graceDeadline)";

    private static final String MEMBER_COLUMNS = "user_id, nickname, goal, category, camera_on, focus_state, focus_sec, "
            + "(disconnected_at IS NOT NULL) AS disconnected";

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("goal"),
            rs.getString("category"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"),
            rs.getInt("focus_sec"),
            instant(rs, "reserved_at"),
            rs.getBoolean("stomp_confirmed"),
            rs.getString("stomp_session_id"),
            instant(rs, "session_opened_at"),
            rs.getString("task_id"),
            instant(rs, "disconnected_at"),
            instant(rs, "joined_at"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"));

    private static final RowMapper<RoomMember> MEMBER = (rs, n) -> new RoomMember(
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("goal"),
            rs.getString("category"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"),
            rs.getInt("focus_sec"),
            rs.getBoolean("disconnected"));

    private static final RowMapper<Candidate> CANDIDATE =
            (rs, n) -> new Candidate(rs.getLong("id"), rs.getLong("room_id"), rs.getLong("user_id"));

    private final JdbcClient jdbc;

    public Optional<Long> findLiveRoomIdOfUser(Long userId) {
        return jdbc.sql("SELECT room_id FROM room_participations WHERE user_id = :userId AND left_at IS NULL")
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    public Optional<Row> lockLiveOfUser(Long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM room_participations WHERE user_id = :userId AND left_at IS NULL FOR UPDATE")
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    public Optional<Row> lockLive(Long roomId, Long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL FOR UPDATE")
                .param("roomId", roomId)
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    public int countLive(Long roomId) {
        return jdbc.sql("SELECT count(*) FROM room_participations WHERE room_id = :roomId AND left_at IS NULL")
                .param("roomId", roomId)
                .query(Integer.class)
                .single();
    }

    public long insertReservation(Long roomId, Long userId, Profile profile, Instant now) {
        return jdbc.sql("INSERT INTO room_participations (room_id, user_id, nickname, goal, category, reserved_at) "
                        + "VALUES (:roomId, :userId, :nickname, :goal, :category, :now) RETURNING id")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("nickname", profile.nickname())
                .param("goal", profile.goal())
                .param("category", profile.category())
                .param("now", ts(now))
                .query(Long.class)
                .single();
    }

    /** 유예 복귀 — 끊김 상태일 때만. 예약 상태로 되돌려 30초 안에 다시 구독하게 한다. session_opened_at은 남긴다. */
    public int restoreFromGrace(Long id, Profile profile, Instant now) {
        return withProfile(
                        jdbc.sql("UPDATE room_participations SET disconnected_at = NULL, stomp_confirmed = false, "
                                + "stomp_session_id = NULL, task_id = NULL, reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category "
                                + "WHERE id = :id AND left_at IS NULL AND disconnected_at IS NOT NULL"),
                        profile)
                .param("now", ts(now))
                .param("id", id)
                .update();
    }

    /** 예약 재시도·확정 멤버의 중복 join — 예약 시각과 프로필만 갱신하고 확정·세션은 건드리지 않는다. */
    public int refreshReservation(Long id, Profile profile, Instant now) {
        return withProfile(
                        jdbc.sql("UPDATE room_participations SET reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category WHERE id = :id AND left_at IS NULL"),
                        profile)
                .param("now", ts(now))
                .param("id", id)
                .update();
    }

    private static StatementSpec withProfile(StatementSpec spec, Profile profile) {
        return spec.param("nickname", profile.nickname())
                .param("goal", profile.goal())
                .param("category", profile.category());
    }

    /** 확정된 적 없는 예약은 이력 없이 지운다. window가 있으면 만료 조건까지 한 문장에서 검사한다. */
    public int deleteUnconfirmed(Long id, ExpiryWindow window) {
        String sql = "DELETE FROM room_participations WHERE id = :id AND left_at IS NULL AND joined_at IS NULL"
                + (window == null ? "" : " AND " + EXPIRED);
        return withWindow(jdbc.sql(sql).param("id", id), window).update();
    }

    /** 확정된 참가자는 left_at·사유를 찍어 이력으로 남긴다. 두 번 불려도 두 번째는 0행. */
    public int markLeft(Long id, LeaveReason reason, Instant now, ExpiryWindow window) {
        String sql = "UPDATE room_participations SET left_at = :now, leave_reason = :reason, stomp_session_id = NULL, "
                + "task_id = NULL, disconnected_at = NULL "
                + "WHERE id = :id AND left_at IS NULL AND joined_at IS NOT NULL"
                + (window == null ? "" : " AND " + EXPIRED);
        return withWindow(jdbc.sql(sql).param("id", id).param("now", ts(now)).param("reason", reason.name()), window)
                .update();
    }

    private static StatementSpec withWindow(StatementSpec spec, ExpiryWindow window) {
        if (window == null) return spec;
        return spec.param("reservationDeadline", ts(window.reservationDeadline()))
                .param("graceDeadline", ts(window.graceDeadline()));
    }

    /** STOMP 확정. 더 늦게 열린 세션이 이미 확정돼 있으면 옛 세션의 뒤늦은 확정은 0행이다 (단조 조건). */
    public int confirm(Long roomId, Long userId, String sessionId, Instant sessionOpenedAt, String taskId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE room_participations
                           SET stomp_confirmed = true, stomp_session_id = :sessionId, session_opened_at = :openedAt,
                               task_id = :taskId, disconnected_at = NULL, joined_at = COALESCE(joined_at, :now)
                         WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL
                           AND (session_opened_at IS NULL OR session_opened_at <= :openedAt)""")
                .param("sessionId", sessionId)
                .param("openedAt", ts(sessionOpenedAt))
                .param("taskId", taskId)
                .param("now", ts(now))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    /** 끊김 — 세션 ID로 바로 찾는다. 재접속이 먼저 도착했으면 세션이 이미 새 값이라 0행. */
    public int markDisconnected(String sessionId, Instant now) {
        return jdbc.sql("UPDATE room_participations SET disconnected_at = :now, stomp_session_id = NULL, task_id = NULL "
                        + "WHERE stomp_session_id = :sessionId AND left_at IS NULL")
                .param("now", ts(now))
                .param("sessionId", sessionId)
                .update();
    }

    /** 핫패스: 갱신 1행 = 인가(현재 세션의 확정 멤버) + 저장. null 필드는 COALESCE로 유지. */
    public int updateState(Long roomId, Long userId, String sessionId, Boolean cameraOn, String focusState, Integer focusSec) {
        return jdbc.sql(
                        """
                        UPDATE room_participations
                           SET camera_on = COALESCE(:cameraOn, camera_on),
                               focus_state = COALESCE(:focusState, focus_state),
                               focus_sec = COALESCE(:focusSec, focus_sec)
                         WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL
                           AND stomp_confirmed AND stomp_session_id = :sessionId""")
                .param("cameraOn", cameraOn)
                .param("focusState", focusState)
                .param("focusSec", focusSec)
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .update();
    }

    public List<RoomMember> findConfirmedMembers(Long roomId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND left_at IS NULL AND stomp_confirmed ORDER BY id")
                .param("roomId", roomId)
                .query(MEMBER)
                .list();
    }

    /** 스냅샷 재요청 — 인가(요청자가 현재 세션의 확정 멤버)와 조회를 한 문장으로. */
    public List<RoomMember> findConfirmedMembersForActiveSession(Long roomId, Long userId, String sessionId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND left_at IS NULL AND stomp_confirmed "
                        + "AND EXISTS (SELECT 1 FROM room_participations me WHERE me.room_id = :roomId "
                        + "AND me.user_id = :userId AND me.left_at IS NULL AND me.stomp_confirmed "
                        + "AND me.stomp_session_id = :sessionId) ORDER BY id")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(MEMBER)
                .list();
    }

    public boolean existsLive(Long roomId, Long userId) {
        return exists("room_id = :roomId AND user_id = :userId AND left_at IS NULL", roomId, userId, null);
    }

    public boolean isConfirmed(Long roomId, Long userId) {
        return exists("room_id = :roomId AND user_id = :userId AND left_at IS NULL AND stomp_confirmed", roomId, userId, null);
    }

    public boolean isActiveSession(Long roomId, Long userId, String sessionId) {
        return exists(
                "room_id = :roomId AND user_id = :userId AND left_at IS NULL AND stomp_confirmed "
                        + "AND stomp_session_id = :sessionId",
                roomId,
                userId,
                sessionId);
    }

    private boolean exists(String where, Long roomId, Long userId, String sessionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM room_participations WHERE " + where + ")")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .single();
    }

    /** 시그널 인가용 — 발신자·수신자 라이브 행을 한 번에. */
    public List<Row> findLiveByRoomAndUsers(Long roomId, Collection<Long> userIds) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND user_id IN (:userIds) AND left_at IS NULL")
                .param("roomId", roomId)
                .param("userIds", userIds)
                .query(ROW)
                .list();
    }

    public List<Candidate> findExpiryCandidates(ExpiryWindow window) {
        return withWindow(
                        jdbc.sql("SELECT id, room_id, user_id FROM room_participations WHERE left_at IS NULL AND "
                                + EXPIRED + " ORDER BY room_id, id"),
                        window)
                .query(CANDIDATE)
                .list();
    }

    /** 죽은 태스크의 참가자를 끊김으로 전환한다 — 이후는 유예 만료 경로가 처리. */
    public int reclaimByTask(String taskId, Instant now) {
        return jdbc.sql("UPDATE room_participations SET disconnected_at = COALESCE(disconnected_at, :now), "
                        + "stomp_session_id = NULL, task_id = NULL WHERE task_id = :taskId AND left_at IS NULL")
                .param("now", ts(now))
                .param("taskId", taskId)
                .update();
    }

    /** 리스 행이 아예 없는 task_id의 참가자(등록 전에 죽은 태스크)도 같은 전환을 적용한다. */
    public int reclaimOrphansWithoutLease(Instant now) {
        return jdbc.sql("UPDATE room_participations SET disconnected_at = COALESCE(disconnected_at, :now), "
                        + "stomp_session_id = NULL, task_id = NULL WHERE left_at IS NULL AND task_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM live_task t WHERE t.task_id = room_participations.task_id)")
                .param("now", ts(now))
                .update();
    }
}
```

`sessionId`가 null이면 `param("sessionId", null)`은 `stomp_session_id = NULL` 비교라 항상 거짓이다 — 호출자(서비스)가 null을 먼저 걸러 SQL을 아예 보내지 않는다.

- [ ] **Step 5: 통과 확인 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: `RoomParticipationRepositoryTest` 10건 통과, 기존 `RoomStompHandlerTest`·`RoomServiceTest` 통과, BUILD SUCCESSFUL.

```bash
git add src/main/java/project/study/room src/test/java/project/study/room
git commit -m "feat: room_participations JdbcClient 저장소와 STOMP 페이로드 focusSec·disconnected (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: 태스크 리스 — TaskLeaseRepository · TaskIdentity · SessionRegistry · TaskLease

**Files:**
- Create: `src/main/java/project/study/room/repository/TaskLeaseRepository.java`
- Create: `src/main/java/project/study/room/lease/TaskIdentity.java`, `lease/LeaseConnectionFactory.java`, `lease/TaskLease.java`
- Create: `src/main/java/project/study/room/websocket/SessionRegistry.java`, `websocket/SessionTrackingDecorator.java`
- Modify: `src/main/java/project/study/config/WebSocketConfig.java` (데코레이터 팩토리 등록), `src/test/resources/application.yaml` (관찰 기간 0)
- Create: `src/test/java/project/study/room/support/MutableClock.java`
- Test: `src/test/java/project/study/room/lease/TaskLeaseRepositoryTest.java`, `lease/TaskLeaseTest.java`, `lease/TaskIdentityTest.java`, `src/test/java/project/study/room/websocket/SessionRegistryTest.java`

**Interfaces:**
- Produces: `TaskLeaseRepository` — `register(String taskId, Instant now)`, `heartbeat(taskId, now) → Heartbeat(rowExists, reclaimedAt)` with `fenced()`, `findStaleUnreclaimed(Instant threshold) → List<String>`, `reclaim(taskId, threshold, now) → boolean`, `deleteReclaimedBefore(Instant cutoff) → int`.
- Produces: `TaskIdentity.id()`, `SessionRegistry` — `register(WebSocketSession)`, `remove(String)`, `isOpen(String)`, `openedAt(String) → Optional<Instant>`, `fence() → int`, `size()`.
- Produces: `TaskLease` — `PHASE = -1000`, `HEARTBEAT_SECONDS = 5`, `STALE_SECONDS = 30`, `beat()`, `canReclaim()`.
- Produces (테스트): `MutableClock` — `MutableClock.at(Instant)`, `advance(Duration)`, `set(Instant)`.

- [ ] **Step 1: 실패 테스트 — 저장소 통합**

`src/test/java/project/study/room/lease/TaskLeaseRepositoryTest.java`:

```java
package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TaskLeaseRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minusSeconds(30);

    @Autowired
    private TaskLeaseRepository leases;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
    }

    @Test
    void 등록_후_heartbeat는_정상이고_없는_태스크의_heartbeat는_펜싱이다() {
        leases.register("t1", NOW);

        Heartbeat ok = leases.heartbeat("t1", NOW.plusSeconds(5));
        assertThat(ok.fenced()).isFalse();
        assertThat(probe.heartbeatAt("t1")).contains(NOW.plusSeconds(5));

        assertThat(leases.heartbeat("ghost", NOW).fenced()).isTrue();
    }

    @Test
    void 낡은_리스만_회수되고_회수된_리스의_heartbeat는_펜싱이다() {
        leases.register("stale", NOW.minusSeconds(31));
        leases.register("fresh", NOW.minusSeconds(29));

        assertThat(leases.findStaleUnreclaimed(THRESHOLD)).containsExactly("stale");
        assertThat(leases.reclaim("stale", THRESHOLD, NOW)).isTrue();
        assertThat(leases.reclaim("fresh", THRESHOLD, NOW)).as("신선한 리스는 회수 0행").isFalse();
        assertThat(leases.reclaim("stale", THRESHOLD, NOW)).as("두 번째 회수는 0행").isFalse();
        assertThat(leases.findStaleUnreclaimed(THRESHOLD)).isEmpty();

        Heartbeat fenced = leases.heartbeat("stale", NOW.plusSeconds(1));
        assertThat(fenced.rowExists()).isTrue();
        assertThat(fenced.reclaimedAt()).isEqualTo(NOW);
        assertThat(fenced.fenced()).isTrue();
    }

    @Test
    void heartbeat가_먼저_커밋되면_회수는_0행이다() {
        leases.register("t1", NOW.minusSeconds(31));

        leases.heartbeat("t1", NOW);

        assertThat(leases.reclaim("t1", THRESHOLD, NOW)).isFalse();
    }

    @Test
    void 되살리기는_reclaimed_at을_비우고_10분_지난_회수_리스는_지워진다() {
        leases.register("t1", NOW.minusSeconds(31));
        leases.reclaim("t1", THRESHOLD, NOW);

        leases.register("t1", NOW.plusSeconds(5));
        assertThat(probe.reclaimedAt("t1")).isEmpty();
        assertThat(leases.heartbeat("t1", NOW.plusSeconds(10)).fenced()).isFalse();

        leases.register("old", NOW.minusSeconds(1000));
        leases.reclaim("old", THRESHOLD, NOW.minusSeconds(601));
        assertThat(leases.deleteReclaimedBefore(NOW.minusSeconds(600))).isEqualTo(1);
        assertThat(probe.heartbeatAt("old")).isEmpty();
        assertThat(probe.heartbeatAt("t1")).isPresent();
    }
}
```

- [ ] **Step 2: 실패 테스트 — 레지스트리·식별자·리스 단위**

`src/test/java/project/study/room/support/MutableClock.java`:

```java
package project.study.room.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시각을 밀 수 있는 Clock. */
public class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
        this.now = now;
    }

    public static MutableClock at(Instant now) {
        return new MutableClock(now);
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

`src/test/java/project/study/room/websocket/SessionRegistryTest.java`:

```java
package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import project.study.room.support.MutableClock;

class SessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Test
    void 등록된_세션은_열림이고_오픈_시각을_기억한다() {
        MutableClock clock = MutableClock.at(T0);
        SessionRegistry registry = new SessionRegistry(clock);

        registry.register(session("s1"));
        clock.advance(Duration.ofSeconds(3));
        registry.register(session("s2"));

        assertThat(registry.isOpen("s1")).isTrue();
        assertThat(registry.isOpen("nope")).isFalse();
        assertThat(registry.isOpen(null)).isFalse();
        assertThat(registry.openedAt("s2")).contains(T0.plusSeconds(3));
        registry.remove("s1");
        assertThat(registry.isOpen("s1")).isFalse();
    }

    @Test
    void 펜싱은_모든_세션을_닫힘으로_표시하고_GOING_AWAY로_닫는다() throws Exception {
        SessionRegistry registry = new SessionRegistry(MutableClock.at(T0));
        WebSocketSession s1 = session("s1");
        WebSocketSession s2 = session("s2");
        registry.register(s1);
        registry.register(s2);

        assertThat(registry.fence()).isEqualTo(2);

        verify(s1).close(CloseStatus.GOING_AWAY);
        verify(s2).close(CloseStatus.GOING_AWAY);
        assertThat(registry.isOpen("s1")).isFalse();
        assertThat(registry.fence()).as("이미 닫힌 세션은 다시 세지 않는다").isZero();
    }
}
```

`src/test/java/project/study/room/lease/TaskIdentityTest.java`:

```java
package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TaskIdentityTest {

    @Test
    void ECS_메타데이터의_TaskARN_마지막_조각이_태스크_ID다() {
        String json = "{\"TaskARN\":\"arn:aws:ecs:ap-northeast-2:123:task/focus-makers-prod-cluster/0a1b2c3d\"}";

        assertThat(TaskIdentity.parseTaskId(json)).isEqualTo("0a1b2c3d");
    }

    @Test
    void TaskARN이_없으면_실패한다() {
        assertThatThrownBy(() -> TaskIdentity.parseTaskId("{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 메타데이터_URI가_없으면_local_UUID로_대체한다() {
        String id = TaskIdentity.resolve("");

        assertThat(id).startsWith("local-").hasSize("local-".length() + 36);
        assertThat(TaskIdentity.resolve("")).isNotEqualTo(id);
    }
}
```

`src/test/java/project/study/room/lease/TaskLeaseTest.java`:

```java
package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.study.room.lease.LeaseConnectionFactory.LeaseConnection;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.support.MutableClock;
import project.study.room.websocket.SessionRegistry;

class TaskLeaseTest {

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");
    private static final Heartbeat OK = new Heartbeat(true, null);

    private final TaskLeaseRepository leases = mock(TaskLeaseRepository.class);
    private final SessionRegistry registry = mock(SessionRegistry.class);
    private final MutableClock clock = MutableClock.at(T0);
    private TaskLease lease;

    @BeforeEach
    void setUp() {
        LeaseConnectionFactory factory = mock(LeaseConnectionFactory.class);
        when(factory.open()).thenReturn(new LeaseConnection(leases, () -> {}));
        lease = new TaskLease(new TaskIdentity(""), factory, registry, clock, 30);
        lease.start();
    }

    @AfterEach
    void tearDown() {
        lease.stop();
    }

    private void beatAfter(long seconds, Heartbeat result) {
        clock.advance(Duration.ofSeconds(seconds));
        when(leases.heartbeat(any(), any())).thenReturn(result);
        lease.beat();
    }

    @Test
    void 기동_시_리스를_등록하고_관찰_기간이_찰_때까지는_회수하지_않는다() {
        verify(leases).register(any(), eq(T0));
        assertThat(lease.canReclaim()).isFalse();

        for (int i = 0; i < 5; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).as("25초는 아직 부족").isFalse();

        beatAfter(5, OK);
        assertThat(lease.canReclaim()).as("30초 연속이면 회수 가능").isTrue();
    }

    @Test
    void heartbeat_간격이_stale을_넘기면_관찰_기간이_다시_시작된다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        assertThat(lease.canReclaim()).isTrue();

        beatAfter(31, OK);

        assertThat(lease.canReclaim()).as("31초 공백 뒤 첫 beat — 관찰 기간 리셋").isFalse();
    }

    @Test
    void 리스가_회수됐으면_소켓을_전부_닫고_리스를_되살린다() {
        beatAfter(5, new Heartbeat(true, T0.plusSeconds(4)));

        verify(registry).fence();
        verify(leases, times(2)).register(any(), any());
        assertThat(lease.canReclaim()).as("되살린 직후는 관찰 기간이 처음부터").isFalse();
    }

    @Test
    void 리스_행이_사라졌어도_펜싱한다() {
        beatAfter(5, new Heartbeat(false, null));

        verify(registry).fence();
    }

    @Test
    void heartbeat_실패는_삼키고_상태를_바꾸지_않는다() {
        for (int i = 0; i < 6; i++) {
            beatAfter(5, OK);
        }
        clock.advance(Duration.ofSeconds(5));
        when(leases.heartbeat(any(), any())).thenThrow(new RuntimeException("db down"));

        lease.beat();

        verify(registry, never()).fence();
        assertThat(lease.canReclaim()).isTrue();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "project.study.room.lease.*" --tests "project.study.room.websocket.SessionRegistryTest" -q`
Expected: 컴파일 실패.

- [ ] **Step 4: 구현**

`src/main/java/project/study/room/repository/TaskLeaseRepository.java`:

```java
package project.study.room.repository;

import static project.study.room.repository.RoomRepository.instant;
import static project.study.room.repository.RoomRepository.ts;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * live_task — 태스크 리스. 회수(reclaim)와 heartbeat가 같은 행을 UPDATE로 잠그므로 둘 중 하나만 이기고,
 * 진 쪽은 반드시 상대의 결과를 본다 (스펙 §2.10-1·§3). heartbeat 전용 커넥션에서는 TaskLease가 이 클래스를
 * 직접 생성해 쓰고, cleanup 스윕은 기본 JdbcClient 빈으로 만든 인스턴스를 쓴다.
 */
@Repository
public class TaskLeaseRepository {

    /** rowExists=false(행이 사라짐) 또는 reclaimedAt이 있으면 다른 태스크가 나를 죽었다고 처리한 것 → 펜싱. */
    public record Heartbeat(boolean rowExists, Instant reclaimedAt) {
        public boolean fenced() {
            return !rowExists || reclaimedAt != null;
        }
    }

    private final JdbcClient jdbc;

    public TaskLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기동 등록과 펜싱 뒤 되살리기 — reclaimed_at을 비운다. */
    public void register(String taskId, Instant now) {
        jdbc.sql("INSERT INTO live_task (task_id, heartbeat_at, reclaimed_at) VALUES (:id, :now, NULL) "
                        + "ON CONFLICT (task_id) DO UPDATE SET heartbeat_at = EXCLUDED.heartbeat_at, reclaimed_at = NULL")
                .param("id", taskId)
                .param("now", ts(now))
                .update();
    }

    public Heartbeat heartbeat(String taskId, Instant now) {
        return jdbc.sql("UPDATE live_task SET heartbeat_at = :now WHERE task_id = :id RETURNING reclaimed_at")
                .param("now", ts(now))
                .param("id", taskId)
                .query((rs, n) -> new Heartbeat(true, instant(rs, "reclaimed_at")))
                .optional()
                .orElse(new Heartbeat(false, null));
    }

    public List<String> findStaleUnreclaimed(Instant threshold) {
        return jdbc.sql("SELECT task_id FROM live_task WHERE heartbeat_at < :threshold AND reclaimed_at IS NULL "
                        + "ORDER BY task_id")
                .param("threshold", ts(threshold))
                .query(String.class)
                .list();
    }

    /** 리스 행을 잠그며 회수 표시. heartbeat가 먼저 커밋됐으면 조건이 거짓이라 false. */
    public boolean reclaim(String taskId, Instant threshold, Instant now) {
        int updated = jdbc.sql("UPDATE live_task SET reclaimed_at = :now "
                        + "WHERE task_id = :id AND heartbeat_at < :threshold AND reclaimed_at IS NULL")
                .param("now", ts(now))
                .param("id", taskId)
                .param("threshold", ts(threshold))
                .update();
        return updated == 1;
    }

    public int deleteReclaimedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM live_task WHERE reclaimed_at < :cutoff")
                .param("cutoff", ts(cutoff))
                .update();
    }
}
```

`src/main/java/project/study/room/lease/TaskIdentity.java`:

```java
package project.study.room.lease;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이 JVM의 태스크 ID. Fargate에서는 컨테이너 메타데이터의 TaskARN 마지막 조각(CloudWatch 로그 스트림 이름과
 * 일치)이라 live_task 행에서 로그를 바로 찾아갈 수 있다. 메타데이터가 없으면(로컬·테스트) UUID.
 */
@Component
@Slf4j
public class TaskIdentity {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String id;

    public TaskIdentity(@Value("${ECS_CONTAINER_METADATA_URI_V4:}") String metadataUri) {
        this.id = resolve(metadataUri);
        log.info("태스크 ID: {}", id);
    }

    public String id() {
        return id;
    }

    static String resolve(String metadataUri) {
        if (metadataUri == null || metadataUri.isBlank()) {
            return "local-" + UUID.randomUUID();
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metadataUri + "/task"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            return parseTaskId(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException | RuntimeException e) {
            log.warn("ECS 태스크 메타데이터 조회 실패 — UUID로 대체", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "local-" + UUID.randomUUID();
    }

    static String parseTaskId(String json) {
        JsonNode arn = JSON.readTree(json).path("TaskARN");
        String value = arn.isMissingNode() ? "" : arn.asString();
        if (value.isBlank()) {
            throw new IllegalStateException("TaskARN 없음: " + json);
        }
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
```

`src/main/java/project/study/room/lease/LeaseConnectionFactory.java`:

```java
package project.study.room.lease;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.room.repository.TaskLeaseRepository;

/**
 * heartbeat 전용 1커넥션 풀. HTTP·STOMP·cleanup이 기본 Hikari 풀을 다 점유해도 리스는 기다리지 않는다.
 * DataSource 빈으로 등록하지 않는다 — 두 번째 DataSource 빈이 생기면 Boot의 기본 DataSource 자동 구성이 물러난다.
 */
@Component
@RequiredArgsConstructor
public class LeaseConnectionFactory {

    public record LeaseConnection(TaskLeaseRepository leases, Runnable closer) implements AutoCloseable {
        @Override
        public void close() {
            closer.run();
        }
    }

    private final JdbcConnectionDetails connectionDetails;

    public LeaseConnection open() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("lease");
        config.setJdbcUrl(connectionDetails.getJdbcUrl());
        config.setUsername(connectionDetails.getUsername());
        config.setPassword(connectionDetails.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        HikariDataSource dataSource = new HikariDataSource(config);
        return new LeaseConnection(new TaskLeaseRepository(JdbcClient.create(dataSource)), dataSource::close);
    }
}
```

`src/main/java/project/study/room/lease/TaskLease.java`:

```java
package project.study.room.lease;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import project.study.room.lease.LeaseConnectionFactory.LeaseConnection;
import project.study.room.repository.TaskLeaseRepository.Heartbeat;
import project.study.room.websocket.SessionRegistry;

/**
 * 태스크 리스 (스펙 §3).
 *
 * <p>phase -1000: STOMP 핸들러(phase 0)보다 먼저 시작해 소켓이 생기기 전에 리스를 등록하고, 핸들러보다 뒤에
 * 멈춰 소켓이 다 닫힐 때까지 heartbeat를 유지한다. heartbeat 스레드는 이 클래스가 직접 소유한다 — Spring의
 * ThreadPoolTaskScheduler는 ContextClosedEvent 시점에 조기 종료해 graceful shutdown 중 heartbeat가 끊긴다.
 * 종료 때 리스 행을 지우지 않는다(낡은 리스는 스윕이 참가자 회수 뒤 치운다).
 *
 * <p>펜싱: heartbeat가 "회수됨"을 돌려주면 다른 태스크가 내 참가자 세션을 비운 것이다. 살아 있는 소켓의 프레임은
 * 전부 거부되고 FE는 재접속하지 않으므로, 소켓을 전부 닫아 5초 뒤 재접속하게 만들고 리스를 되살린다.
 */
@Component
@Slf4j
public class TaskLease implements SmartLifecycle {

    public static final int PHASE = -1000;
    public static final long HEARTBEAT_SECONDS = 5;
    public static final long STALE_SECONDS = 30;

    private final TaskIdentity identity;
    private final LeaseConnectionFactory connectionFactory;
    private final SessionRegistry sessionRegistry;
    private final Clock clock;
    private final long observationSeconds;

    private LeaseConnection connection;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile Instant lastCommittedBeat;
    private volatile Instant continuousSince;

    public TaskLease(
            TaskIdentity identity,
            LeaseConnectionFactory connectionFactory,
            SessionRegistry sessionRegistry,
            Clock clock,
            @Value("${app.room.lease.observation-seconds:30}") long observationSeconds) {
        this.identity = identity;
        this.connectionFactory = connectionFactory;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
        this.observationSeconds = observationSeconds;
    }

    @Override
    public void start() {
        connection = connectionFactory.open();
        Instant now = clock.instant();
        connection.leases().register(identity.id(), now);
        lastCommittedBeat = now;
        continuousSince = now;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "task-lease");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        running = true;
        log.info("태스크 리스 등록: taskId={}", identity.id());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public String taskId() {
        return identity.id();
    }

    /** heartbeat 한 번. 실패는 기록하지 않고 다음 틱에 다시 시도한다 — 커밋된 beat만 연속성에 센다. */
    public void beat() {
        Instant now = clock.instant();
        try {
            Heartbeat heartbeat = connection.leases().heartbeat(identity.id(), now);
            if (heartbeat.fenced()) {
                fence(now);
            } else {
                recordBeat(now);
            }
        } catch (RuntimeException e) {
            log.warn("heartbeat 실패: taskId={}", identity.id(), e);
        }
    }

    /** 남을 죽었다고 판정해도 되는가 — 내 커밋된 heartbeat가 관찰 기간 이상 stale 초과 공백 없이 이어졌을 때만. */
    public boolean canReclaim() {
        Instant since = continuousSince;
        Instant last = lastCommittedBeat;
        return since != null && last != null && Duration.between(since, last).getSeconds() >= observationSeconds;
    }

    private void recordBeat(Instant now) {
        Instant last = lastCommittedBeat;
        if (last == null || Duration.between(last, now).getSeconds() > STALE_SECONDS) {
            continuousSince = now;
        }
        lastCommittedBeat = now;
    }

    private void fence(Instant now) {
        int closed = sessionRegistry.fence();
        connection.leases().register(identity.id(), now);
        continuousSince = now;
        lastCommittedBeat = now;
        log.warn("펜싱: 리스가 회수되어 세션 {}개를 닫고 리스를 되살림 taskId={}", closed, identity.id());
    }
}
```

`src/main/java/project/study/room/websocket/SessionRegistry.java`:

```java
package project.study.room.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 이 태스크가 쥔 WebSocket 세션 핸들과 오픈 시각. 룸 상태가 아니라 소켓 핸들이라 태스크 메모리에 산다.
 * confirmStomp의 사전/사후 검사(isOpen)와 펜싱(fence), 옛 세션 confirm 차단용 오픈 시각(openedAt)에 쓴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRegistry {

    private record Entry(WebSocketSession session, Instant openedAt, AtomicBoolean open) {}

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new Entry(session, clock.instant(), new AtomicBoolean(true)));
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public boolean isOpen(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry != null && entry.open().get();
    }

    public Optional<Instant> openedAt(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId)).map(Entry::openedAt);
    }

    public int size() {
        return sessions.size();
    }

    /** 펜싱 — 모든 세션을 먼저 닫힘으로 표시한 뒤 GOING_AWAY로 닫는다. 표시가 먼저라 이후 confirm은 사전 검사에서 거절된다. */
    public int fence() {
        int closed = 0;
        for (Entry entry : sessions.values()) {
            if (!entry.open().compareAndSet(true, false)) {
                continue;
            }
            closed++;
            try {
                entry.session().close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("세션 닫기 실패: sessionId={}", entry.session().getId(), e);
            }
        }
        return closed;
    }
}
```

`src/main/java/project/study/room/websocket/SessionTrackingDecorator.java`:

```java
package project.study.room.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** 소켓이 열리고 닫힐 때 SessionRegistry에 핸들을 등록·해제한다. 세션 ID는 STOMP simpSessionId와 같다. */
public class SessionTrackingDecorator extends WebSocketHandlerDecorator {

    private final SessionRegistry registry;

    public SessionTrackingDecorator(WebSocketHandler delegate, SessionRegistry registry) {
        super(delegate);
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        registry.register(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        registry.remove(session.getId());
        super.afterConnectionClosed(session, closeStatus);
    }
}
```

- [ ] **Step 5: WebSocketConfig에 데코레이터 등록, 테스트 설정**

`WebSocketConfig`: 필드에 `private final SessionRegistry sessionRegistry;`를 추가(import `project.study.room.websocket.SessionRegistry`, `project.study.room.websocket.SessionTrackingDecorator`)하고 `configureWebSocketTransport` 끝에:

```java
        // 소켓 핸들을 레지스트리에 등록한다 — confirm 사전/사후 검사와 펜싱(전체 닫기)에 쓴다 (BY-626)
        registry.addDecoratorFactory(handler -> new SessionTrackingDecorator(handler, sessionRegistry));
```

`src/test/resources/application.yaml` 끝에 추가:

```yaml
# BY-626 태스크 리스 — 테스트는 관찰 기간 없이 바로 회수 판정이 가능하게 한다 (운영 기본 30초)
app:
  room:
    lease:
      observation-seconds: 0
```

- [ ] **Step 6: 통과 확인 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: 새 테스트 14건 통과, 전체 BUILD SUCCESSFUL. `@SpringBootTest` 컨텍스트마다 `TaskLease`가 기동해 Testcontainers DB에 리스를 등록한다(로그 "태스크 리스 등록").

```bash
git add src/main/java/project/study/room src/main/java/project/study/config/WebSocketConfig.java src/test/java/project/study/room src/test/resources/application.yaml
git commit -m "feat: 태스크 리스·펜싱 — live_task heartbeat, 세션 레지스트리, 태스크 식별 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: AutoLeave · ParticipantRemover · RoomStateService

**Files:**
- Create: `src/main/java/project/study/room/service/AutoLeave.java`, `service/ParticipantRemover.java`, `service/RoomStateService.java`
- Test: `src/test/java/project/study/room/ParticipantRemoverTest.java`, `RoomStateServiceTest.java`

**Interfaces:**
- Produces: `record AutoLeave(Long roomId, Long userId)`.
- Produces: `ParticipantRemover.remove(Row row, RoomRow room, LeaveReason reason, Instant now, ExpiryWindow window) → Removed(removed, roomClosed)`; `Removed.NONE`.
- Produces: `RoomStateService` — `updateState(Long roomId, Long userId, String sessionId, Boolean cameraOn, String focusState, Integer focusSec) → boolean`, `authorizeSignal(Long roomId, Long fromUserId, String sessionId, Long toUserId) → boolean`, `getMembersForActiveSession(roomId, userId, sessionId) → List<RoomMember>`, `getMembers(roomId)`, `hasParticipant(roomId, userId)`, `isConfirmedMember(roomId, userId)`, `isActiveSession(roomId, userId, sessionId)`, `getRoomIdForUser(userId) → Long(null이면 없음)`.

- [ ] **Step 1: 실패 테스트**

`src/test/java/project/study/room/ParticipantRemoverTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.service.ParticipantRemover;
import project.study.room.service.ParticipantRemover.Removed;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ParticipantRemoverTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", null, null);

    @Autowired
    private ParticipantRemover remover;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private RoomRow room;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        userId = probe.insertUser();
        long roomId = rooms.insertIfCodeFree("1234", userId, NOW, NOW.minusSeconds(600)).orElseThrow();
        room = rooms.lockById(roomId).orElseThrow();
    }

    private Row live() {
        return participations.lockLive(room.id(), userId).orElseThrow();
    }

    @Test
    void 확정_없는_예약은_삭제되고_마지막이면_방이_닫힌다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);

        Removed removed = remover.remove(live(), room, LeaveReason.EXPLICIT, NOW, null);

        assertThat(removed).isEqualTo(new Removed(true, true));
        assertThat(probe.participation(room.id(), userId)).isEmpty();
        assertThat(probe.room(room.id())).get().extracting(RoomProbe.RoomRow::closeReason).isEqualTo("LAST_LEFT");
    }

    @Test
    void 확정된_참가자는_이력으로_남고_다른_참가자가_있으면_방은_열려_있다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);
        participations.confirm(room.id(), userId, "s1", NOW, "t", NOW);
        long other = probe.insertUser();
        participations.insertReservation(room.id(), other, PROFILE, NOW);

        Removed removed = remover.remove(live(), room, LeaveReason.SWITCHED_ROOM, NOW.plusSeconds(60), null);

        assertThat(removed).isEqualTo(new Removed(true, false));
        RoomProbe.Participation history = probe.participation(room.id(), userId).orElseThrow();
        assertThat(history.leftAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(history.leaveReason()).isEqualTo("SWITCHED_ROOM");
        assertThat(rooms.isOpen(room.id())).isTrue();
    }

    @Test
    void 만료_조건이_안_맞거나_이미_나간_행이면_아무것도_바뀌지_않는다() {
        participations.insertReservation(room.id(), userId, PROFILE, NOW);
        Row row = live();

        ExpiryWindow notYet = new ExpiryWindow(NOW.minusSeconds(30), NOW.minusSeconds(30));
        assertThat(remover.remove(row, room, LeaveReason.DISCONNECT_TIMEOUT, NOW, notYet)).isEqualTo(Removed.NONE);
        assertThat(rooms.isOpen(room.id())).isTrue();

        assertThat(remover.remove(row, room, LeaveReason.EXPLICIT, NOW, null).removed()).isTrue();
        assertThat(remover.remove(row, room, LeaveReason.EXPLICIT, NOW, null)).isEqualTo(Removed.NONE);
    }
}
```

`src/test/java/project/study/room/RoomStateServiceTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.dto.RoomMember;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomRepository;
import project.study.room.service.RoomStateService;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Profile PROFILE = new Profile("포메", "정처기", "CERTIFICATE");

    @Autowired
    private RoomStateService state;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private long roomId;
    private long me;
    private long peer;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        me = probe.insertUser();
        peer = probe.insertUser();
        roomId = rooms.insertIfCodeFree("1234", me, NOW, NOW.minusSeconds(600)).orElseThrow();
        participations.insertReservation(roomId, me, PROFILE, NOW);
        participations.insertReservation(roomId, peer, PROFILE, NOW);
    }

    @Test
    void 확정_전에는_상태_갱신도_시그널도_거부된다() {
        assertThat(state.updateState(roomId, me, "s1", true, "DISTRACTED", 10)).isFalse();
        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).isFalse();
        assertThat(state.hasParticipant(roomId, me)).as("예약자는 구독 인가는 통과").isTrue();
        assertThat(state.isConfirmedMember(roomId, me)).isFalse();
        assertThat(state.getRoomIdForUser(me)).isEqualTo(roomId);
        assertThat(state.getRoomIdForUser(999_999L)).isNull();
    }

    @Test
    void 현재_세션의_확정_멤버만_상태를_갱신하고_무효_필드는_유지된다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);

        assertThat(state.updateState(roomId, me, "s1", null, null, null)).as("전부 null이면 SQL 없이 false").isFalse();
        assertThat(state.updateState(roomId, me, "s1", true, null, 1500)).isTrue();
        assertThat(state.updateState(roomId, me, "old", false, "DISTRACTED", 0)).isFalse();
        assertThat(state.updateState(roomId, me, null, false, "DISTRACTED", 0)).isFalse();

        RoomMember member = state.getMembers(roomId).getFirst();
        assertThat(member.cameraOn()).isTrue();
        assertThat(member.focusState()).isEqualTo("FOCUS");
        assertThat(member.focusSec()).isEqualTo(1500);
    }

    @Test
    void 시그널은_발신자가_현재_세션의_확정_멤버이고_수신자가_확정_멤버일_때만_허용된다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);

        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).as("수신자 미확정").isFalse();
        participations.confirm(roomId, peer, "s2", NOW, "t", NOW);
        assertThat(state.authorizeSignal(roomId, me, "s1", peer)).isTrue();
        assertThat(state.authorizeSignal(roomId, me, "s1", me)).as("자기 자신에게도 허용(k6 경로)").isTrue();
        assertThat(state.authorizeSignal(roomId, me, "old", peer)).as("옛 세션").isFalse();
        assertThat(state.authorizeSignal(roomId, me, "s1", 999_999L)).as("비멤버 수신자").isFalse();
    }

    @Test
    void 스냅샷_재요청은_인가와_조회가_한_번이다() {
        participations.confirm(roomId, me, "s1", NOW, "t", NOW);
        participations.confirm(roomId, peer, "s2", NOW, "t", NOW);

        assertThat(state.getMembersForActiveSession(roomId, me, "s1"))
                .extracting(RoomMember::userId)
                .containsExactlyInAnyOrder(me, peer);
        assertThat(state.getMembersForActiveSession(roomId, me, "old")).isEmpty();
        assertThat(state.getMembersForActiveSession(roomId, me, null)).isEmpty();
        assertThat(state.getMembersForActiveSession(roomId + 1, me, "s1")).isEmpty();
        assertThat(state.isActiveSession(roomId, me, "s1")).isTrue();
        assertThat(state.isActiveSession(roomId, me, "old")).isFalse();
        assertThat(state.isActiveSession(roomId, me, null)).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.room.ParticipantRemoverTest" --tests "project.study.room.RoomStateServiceTest" -q`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`src/main/java/project/study/room/service/AutoLeave.java`:

```java
package project.study.room.service;

/** 서버가 자동으로 내보낸 자리 — 호출자(컨트롤러·스케줄러)가 옛 방 토픽에 MEMBER_LEFT를 브로드캐스트한다. */
public record AutoLeave(Long roomId, Long userId) {}
```

`src/main/java/project/study/room/service/ParticipantRemover.java`:

```java
package project.study.room.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.room.entity.CloseReason;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;

/**
 * 자리 하나를 비운다 (스펙 §2.3). 명시 퇴장·방 전환·만료 공통. 호출자가 방 행 락을 쥔 상태여야 한다.
 * 확정된 적 없는 예약은 이력 없이 삭제, 확정된 참가자는 left_at·사유를 남긴다. 마지막 자리면 방을 닫는다.
 * 최종 문장이 라이브(+만료) 조건을 품고 있어 같은 행에 두 번 불려도 두 번째는 NONE이다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantRemover {

    public record Removed(boolean removed, boolean roomClosed) {
        public static final Removed NONE = new Removed(false, false);
    }

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;

    public Removed remove(Row row, RoomRow room, LeaveReason reason, Instant now, ExpiryWindow window) {
        int changed = row.joinedAt() == null
                ? participations.deleteUnconfirmed(row.id(), window)
                : participations.markLeft(row.id(), reason, now, window);
        if (changed == 0) {
            return Removed.NONE;
        }
        boolean closed = rooms.closeIfEmpty(room.id(), room.inviteCode(), CloseReason.LAST_LEFT, now);
        return new Removed(true, closed);
    }
}
```

`src/main/java/project/study/room/service/RoomStateService.java`:

```java
package project.study.room.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.room.dto.RoomMember;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Row;

/**
 * 핫패스와 인가 조회 (스펙 §2.7~§2.9). 방 락 없이 단일 행 조건부 문장만 쓴다.
 * sessionId가 null이면 SQL을 보내지 않고 거절한다 — NULL 비교는 항상 거짓이라 결과는 같지만 왕복을 아낀다.
 */
@Service
@RequiredArgsConstructor
public class RoomStateService {

    private final RoomParticipationRepository participations;

    /** 갱신 1행 = 인가(현재 세션의 확정 멤버) + 저장. 필드별 검증은 핸들러가 SQL 전에 한다. */
    @Transactional
    public boolean updateState(
            Long roomId, Long userId, String sessionId, Boolean cameraOn, String focusState, Integer focusSec) {
        if (sessionId == null || (cameraOn == null && focusState == null && focusSec == null)) {
            return false;
        }
        return participations.updateState(roomId, userId, sessionId, cameraOn, focusState, focusSec) == 1;
    }

    /** 발신자가 현재 세션의 확정 멤버이고 수신자가 확정 멤버인지 한 번의 조회로 판정한다. */
    @Transactional(readOnly = true)
    public boolean authorizeSignal(Long roomId, Long fromUserId, String sessionId, Long toUserId) {
        if (sessionId == null) {
            return false;
        }
        List<Row> rows = participations.findLiveByRoomAndUsers(roomId, List.of(fromUserId, toUserId));
        boolean fromOk = rows.stream()
                .anyMatch(r -> r.userId().equals(fromUserId) && r.stompConfirmed() && sessionId.equals(r.stompSessionId()));
        boolean toOk = rows.stream().anyMatch(r -> r.userId().equals(toUserId) && r.stompConfirmed());
        return fromOk && toOk;
    }

    /** 스냅샷 재요청 — 인가와 조회가 한 번의 원자 호출. 빈 목록 = 발송 금지. */
    @Transactional(readOnly = true)
    public List<RoomMember> getMembersForActiveSession(Long roomId, Long userId, String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        return participations.findConfirmedMembersForActiveSession(roomId, userId, sessionId);
    }

    @Transactional(readOnly = true)
    public List<RoomMember> getMembers(Long roomId) {
        return participations.findConfirmedMembers(roomId);
    }

    /** SUBSCRIBE 인가 — 자리 예약자(미확정 포함)만 방 토픽을 구독할 수 있다. */
    @Transactional(readOnly = true)
    public boolean hasParticipant(Long roomId, Long userId) {
        return participations.existsLive(roomId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isConfirmedMember(Long roomId, Long userId) {
        return participations.isConfirmed(roomId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isActiveSession(Long roomId, Long userId, String sessionId) {
        return sessionId != null && participations.isActiveSession(roomId, userId, sessionId);
    }

    /** 유저의 현재 방. 없으면 null. */
    @Transactional(readOnly = true)
    public Long getRoomIdForUser(Long userId) {
        return participations.findLiveRoomIdOfUser(userId).orElse(null);
    }
}
```

- [ ] **Step 4: 통과 확인 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: 새 테스트 7건 통과, BUILD SUCCESSFUL.

```bash
git add src/main/java/project/study/room/service src/test/java/project/study/room
git commit -m "feat: 룸 핫패스 서비스와 자리 제거 공통 로직 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: RoomCleanupService — 리스 회수 · 만료 · 빈 방 (단계별 트랜잭션)

**Files:**
- Create: `src/main/java/project/study/room/service/RoomCleanupService.java`
- Test: `src/test/java/project/study/room/RoomCleanupServiceTest.java`, `RoomCleanupIsolationTest.java`

**Interfaces:**
- Consumes: `TaskLease.canReclaim()`, `TaskLeaseRepository`, `RoomRepository`, `RoomParticipationRepository`, `ParticipantRemover`, `TransactionTemplate`(Boot 자동 구성 빈).
- Produces: `RoomCleanupService` — `cleanupExpired(Instant now) → List<AutoLeave>`, `cleanupExpired(Instant now, Consumer<AutoLeave> onRemoved)`, 상수 `RESERVATION_TTL_SECONDS = 30`, `GRACE_PERIOD_SECONDS = 30`, `EMPTY_ROOM_TTL_SECONDS = 600`, `RECLAIMED_LEASE_TTL_SECONDS = 600`.

- [ ] **Step 1: 실패 테스트 — 통합**

`src/test/java/project/study/room/RoomCleanupServiceTest.java` (커밋 경계를 검증하므로 `@Transactional` 없이 실제 커밋):

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.TestcontainersConfiguration;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;
import project.study.room.support.RoomProbe;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomCleanupServiceTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Profile PROFILE = new Profile("포메", null, null);

    @Autowired
    private RoomCleanupService cleanup;

    @Autowired
    private RoomRepository rooms;

    @Autowired
    private RoomParticipationRepository participations;

    @Autowired
    private TaskLeaseRepository leases;

    @Autowired
    private JdbcClient jdbc;

    private RoomProbe probe;
    private Instant now;
    private long userId;

    @BeforeEach
    void setUp() {
        probe = new RoomProbe(jdbc);
        // 기준 시각을 2분 전으로 둔다 — 이 태스크 자신의 리스(실시간 heartbeat)가 낡은 것으로 보이지 않게
        now = Instant.now().minusSeconds(120);
        userId = probe.insertUser();
    }

    private long openRoom(Instant createdAt) {
        for (int i = 0; i < 100; i++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            var id = rooms.insertIfCodeFree(code, userId, createdAt, createdAt.minusSeconds(600));
            if (id.isPresent()) return id.get();
        }
        throw new IllegalStateException("코드 소진");
    }

    @Test
    void 삼십초_넘은_미확정_예약은_지워지고_방도_닫히며_AutoLeave가_나온다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now.minusSeconds(31));

        List<AutoLeave> removed = cleanup.cleanupExpired(now);

        assertThat(removed).contains(new AutoLeave(roomId, userId));
        assertThat(probe.participation(roomId, userId)).isEmpty();
        assertThat(probe.room(roomId)).get().extracting(RoomProbe.RoomRow::closeReason).isEqualTo("LAST_LEFT");
    }

    @Test
    void 유예_삼십초가_지난_참가자는_DISCONNECT_TIMEOUT으로_남고_아직이면_유지된다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now.minusSeconds(60));
        participations.confirm(roomId, userId, "s1", now, "t", now.minusSeconds(60));
        participations.markDisconnected("s1", now.minusSeconds(29));

        assertThat(cleanup.cleanupExpired(now)).doesNotContain(new AutoLeave(roomId, userId));

        assertThat(cleanup.cleanupExpired(now.plusSeconds(2))).contains(new AutoLeave(roomId, userId));
        RoomProbe.Participation history = probe.participation(roomId, userId).orElseThrow();
        assertThat(history.leaveReason()).isEqualTo("DISCONNECT_TIMEOUT");
        assertThat(rooms.isOpen(roomId)).isFalse();
    }

    @Test
    void 입장_이력_없는_빈_방은_10분_뒤_EMPTY_EXPIRED로_닫힌다() {
        long fresh = openRoom(now.minusSeconds(599));
        long old = openRoom(now.minusSeconds(601));
        long occupied = openRoom(now.minusSeconds(601));
        participations.insertReservation(occupied, userId, PROFILE, now);
        participations.confirm(occupied, userId, "s1", now, "t", now);

        cleanup.cleanupExpired(now);

        assertThat(rooms.isOpen(fresh)).isTrue();
        assertThat(rooms.isOpen(occupied)).isTrue();
        assertThat(probe.room(old)).get().extracting(RoomProbe.RoomRow::closeReason).isEqualTo("EMPTY_EXPIRED");
    }

    @Test
    void 죽은_태스크의_참가자는_끊김으로_전환되고_유예_뒤_회수된다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "task-dead", now);
        leases.register("task-dead", now.minusSeconds(31));

        cleanup.cleanupExpired(now);

        RoomProbe.Participation graced = probe.participation(roomId, userId).orElseThrow();
        assertThat(graced.disconnectedAt()).isNotNull();
        assertThat(graced.taskId()).isNull();
        assertThat(probe.reclaimedAt("task-dead")).isPresent();

        assertThat(cleanup.cleanupExpired(now.plusSeconds(31))).contains(new AutoLeave(roomId, userId));
    }

    @Test
    void 리스_행이_없는_task_id의_참가자도_회수되고_회수된_리스는_10분_뒤_지워진다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "never-registered", now);
        leases.register("task-old", now.minusSeconds(2000));
        leases.reclaim("task-old", now.minusSeconds(30), now.minusSeconds(601));

        cleanup.cleanupExpired(now);

        assertThat(probe.participation(roomId, userId).orElseThrow().disconnectedAt()).isNotNull();
        assertThat(probe.heartbeatAt("task-old")).isEmpty();
    }

    @Test
    void 신선한_리스의_참가자는_건드리지_않는다() {
        long roomId = openRoom(now.minusSeconds(100));
        participations.insertReservation(roomId, userId, PROFILE, now);
        participations.confirm(roomId, userId, "s1", now, "task-alive", now);
        leases.register("task-alive", now.minusSeconds(29));

        cleanup.cleanupExpired(now);

        assertThat(probe.participation(roomId, userId).orElseThrow().disconnectedAt()).isNull();
        assertThat(probe.reclaimedAt("task-alive")).isEmpty();
    }
}
```

`src/test/java/project/study/room/RoomCleanupIsolationTest.java` (방별 격리·커밋 직후 브로드캐스트 — Mockito 단위):

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.room.lease.TaskLease;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.repository.TaskLeaseRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.ParticipantRemover;
import project.study.room.service.ParticipantRemover.Removed;
import project.study.room.service.RoomCleanupService;

class RoomCleanupIsolationTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final RoomParticipationRepository participations = mock(RoomParticipationRepository.class);
    private final TaskLeaseRepository leases = mock(TaskLeaseRepository.class);
    private final TaskLease taskLease = mock(TaskLease.class);
    private final ParticipantRemover remover = mock(ParticipantRemover.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private RoomCleanupService service() {
        // TransactionTemplate 흉내 — 콜백을 즉시 실행한다 (커밋 경계 순서만 검증)
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                .doInTransaction(mock(TransactionStatus.class)));
        return new RoomCleanupService(rooms, participations, leases, taskLease, remover, tx);
    }

    private static RoomRow open(long id) {
        return new RoomRow(id, "0000", 1L, NOW, null, null);
    }

    private static Row row(long id, long roomId, long userId) {
        return new Row(id, roomId, userId, null, null, null, false, "FOCUS", 0, NOW, false, null, null, null, null,
                null, null, null);
    }

    @Test
    void 한_방의_예외가_다른_방의_정리와_브로드캐스트를_막지_않는다() {
        when(taskLease.canReclaim()).thenReturn(false);
        when(participations.findExpiryCandidates(any()))
                .thenReturn(List.of(new Candidate(1L, 10L, 100L), new Candidate(2L, 20L, 200L)));
        when(rooms.lockById(10L)).thenReturn(Optional.of(open(10L)));
        when(rooms.lockById(20L)).thenThrow(new RuntimeException("db hiccup"));
        when(participations.lockLive(10L, 100L)).thenReturn(Optional.of(row(1L, 10L, 100L)));
        when(remover.remove(any(), any(), any(), any(), any())).thenReturn(new Removed(true, true));
        when(rooms.findEmptyOpenRoomsCreatedBefore(any())).thenReturn(List.of());

        List<AutoLeave> broadcast = new ArrayList<>();
        service().cleanupExpired(NOW, broadcast::add);

        assertThat(broadcast).containsExactly(new AutoLeave(10L, 100L));
    }

    @Test
    void 관찰_기간이_안_찼으면_리스_회수를_건너뛴다() {
        when(taskLease.canReclaim()).thenReturn(false);
        when(participations.findExpiryCandidates(any())).thenReturn(List.of());
        when(rooms.findEmptyOpenRoomsCreatedBefore(any())).thenReturn(List.of());

        service().cleanupExpired(NOW);

        org.mockito.Mockito.verify(leases, org.mockito.Mockito.never()).findStaleUnreclaimed(any());
        org.mockito.Mockito.verify(participations, org.mockito.Mockito.never()).reclaimOrphansWithoutLease(any());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.room.RoomCleanup*" -q`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`src/main/java/project/study/room/service/RoomCleanupService.java`:

```java
package project.study.room.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.room.entity.CloseReason;
import project.study.room.entity.LeaveReason;
import project.study.room.lease.TaskLease;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Candidate;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;
import project.study.room.repository.TaskLeaseRepository;

/**
 * 5초마다 도는 정리 진행자 (스펙 §2.10). 트랜잭션을 열지 않고 단계·방마다 짧은 트랜잭션을 열어 커밋한다 —
 * 락을 단계 사이로 넘기지 않아 데드락 경로가 없다. 모든 문장이 조건부·멱등이라 두 태스크가 동시에 돌아도
 * 같은 행을 두 번 처리하지 못하므로 스윕을 단일화하지 않는다. 방 하나를 커밋할 때마다 즉시 알리고(onRemoved),
 * 방마다 예외를 격리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomCleanupService {

    public static final int RESERVATION_TTL_SECONDS = 30;
    public static final int GRACE_PERIOD_SECONDS = 30;
    public static final int EMPTY_ROOM_TTL_SECONDS = 600;
    public static final int RECLAIMED_LEASE_TTL_SECONDS = 600;

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;
    private final TaskLeaseRepository leases;
    private final TaskLease taskLease;
    private final ParticipantRemover remover;
    private final TransactionTemplate tx;

    public List<AutoLeave> cleanupExpired(Instant now) {
        List<AutoLeave> removed = new ArrayList<>();
        cleanupExpired(now, removed::add);
        return removed;
    }

    public void cleanupExpired(Instant now, Consumer<AutoLeave> onRemoved) {
        reclaimDeadTasks(now);
        expireParticipants(now, onRemoved);
        closeEmptyRooms(now);
    }

    /** 살아 있는 리스가 없는 태스크의 참가자를 끊김으로 전환한다 — 내 heartbeat가 연속일 때만 (스펙 §3). */
    private void reclaimDeadTasks(Instant now) {
        if (!taskLease.canReclaim()) {
            return;
        }
        Instant threshold = now.minusSeconds(TaskLease.STALE_SECONDS);
        for (String dead : leases.findStaleUnreclaimed(threshold)) {
            if (dead.equals(taskLease.taskId())) {
                continue; // 나 자신은 회수하지 않는다 — 호출자가 미래 시각(now)을 넘겨도 내 리스가 낡아 보이면 안 된다
            }
            quietly("리스 회수 " + dead, () -> tx.execute(status -> {
                if (leases.reclaim(dead, threshold, now)) {
                    int reclaimed = participations.reclaimByTask(dead, now);
                    log.warn("죽은 태스크 회수: taskId={}, 참가자 {}명 끊김 전환", dead, reclaimed);
                }
                return null;
            }));
        }
        quietly("리스 없는 참가자 회수", () -> tx.execute(status -> participations.reclaimOrphansWithoutLease(now)));
        quietly("회수된 리스 정리", () -> tx.execute(status -> leases.deleteReclaimedBefore(
                now.minusSeconds(RECLAIMED_LEASE_TTL_SECONDS))));
    }

    private void expireParticipants(Instant now, Consumer<AutoLeave> onRemoved) {
        ExpiryWindow window = new ExpiryWindow(
                now.minusSeconds(RESERVATION_TTL_SECONDS), now.minusSeconds(GRACE_PERIOD_SECONDS));
        Map<Long, List<Candidate>> byRoom = participations.findExpiryCandidates(window).stream()
                .collect(Collectors.groupingBy(Candidate::roomId));
        byRoom.forEach((roomId, candidates) -> quietly("만료 정리 room=" + roomId, () -> {
            List<AutoLeave> removed = tx.execute(status -> expireRoom(roomId, candidates, window, now));
            if (removed != null) {
                removed.forEach(onRemoved);
            }
        }));
    }

    private List<AutoLeave> expireRoom(Long roomId, List<Candidate> candidates, ExpiryWindow window, Instant now) {
        Optional<RoomRow> room = rooms.lockById(roomId).filter(RoomRow::isOpen);
        if (room.isEmpty()) {
            return List.of();
        }
        List<AutoLeave> removed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Optional<Row> row = participations.lockLive(roomId, candidate.userId());
            if (row.isEmpty()) {
                continue;
            }
            if (remover.remove(row.get(), room.get(), LeaveReason.DISCONNECT_TIMEOUT, now, window).removed()) {
                removed.add(new AutoLeave(roomId, candidate.userId()));
            }
        }
        return removed;
    }

    private void closeEmptyRooms(Instant now) {
        Instant deadline = now.minusSeconds(EMPTY_ROOM_TTL_SECONDS);
        for (RoomRow candidate : rooms.findEmptyOpenRoomsCreatedBefore(deadline)) {
            quietly("빈 방 종료 " + candidate.id(), () -> tx.execute(status -> {
                rooms.lockById(candidate.id())
                        .filter(RoomRow::isOpen)
                        .ifPresent(room -> rooms.closeIfEmpty(room.id(), room.inviteCode(), CloseReason.EMPTY_EXPIRED, now));
                return null;
            }));
        }
    }

    private void quietly(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("cleanup 단계 실패({}) — 다음 틱에 재시도", step, e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: 새 테스트 8건 통과, BUILD SUCCESSFUL. 이 시점의 `RoomCleanupScheduler`는 아직 옛 인메모리 서비스를 부른다(Task 7에서 교체).

```bash
git add src/main/java/project/study/room/service/RoomCleanupService.java src/test/java/project/study/room
git commit -m "feat: 룸 정리 진행자 — 리스 회수·만료·빈 방을 단계별 트랜잭션으로 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: RoomService 교체 + 호출부 전환 + 테스트 이식

옛 인메모리 `RoomService`·`Room`·`Participant`·`ClosedInviteCodes`를 지우고 DB 기반 `RoomService`로 바꾼다. 컨트롤러·STOMP 핸들러·리스너·`WebSocketConfig`·스케줄러가 새 API를 쓰도록 같이 바꾸고, 기존 서비스 테스트 30건을 Testcontainers 통합 테스트로 이식한다. 이 태스크 하나가 "교체"라 크지만, 중간 상태로는 컴파일이 안 되므로 나누지 않는다.

**Files:**
- Modify: `src/main/java/project/study/room/service/TurnCredentialIssuer.java` (빈으로), `service/RoomService.java` (전면 교체)
- Delete: `service/Room.java`, `service/Participant.java`, `service/ClosedInviteCodes.java`
- Modify: `controller/RoomController.java`, `websocket/RoomStompHandler.java`, `websocket/StompEventListener.java`, `scheduler/RoomCleanupScheduler.java`, `config/WebSocketConfig.java`
- Tests (이식/신규): `RoomServiceTest.java`, `RoomServiceConfirmTest.java`, `RoomExpiryTest.java`, `RoomInviteCodeErrorTest.java`; (수정) `RoomApiTest.java`, `RoomStompHandlerTest.java`, `StompEventListenerTest.java`; (삭제) `RoomServiceExpiryIndexTest.java`

**Interfaces:**
- Consumes: Task 2~6의 저장소·`ParticipantRemover`·`RoomStateService`·`RoomCleanupService`·`TaskIdentity`·`SessionRegistry`.
- Produces: `RoomService` — `create(Long userId) → RoomCreateResponse`, `join(Long userId, String inviteCode, String nickname, String goal, String category) → JoinResult(response, autoLeave)`, `leave(Long roomId, Long userId) → LeaveResult(removed, roomStillOpen)`, `confirmStomp(Long roomId, Long userId, String sessionId, Instant sessionOpenedAt, String taskId) → List<RoomMember>`, `handleDisconnect(String sessionId) → boolean`, `roomExists(Long roomId) → boolean`. 상수 `MAX_PARTICIPANTS = 6`, `CLOSED_CODE_TTL_SECONDS = 600`, `EMPTY_ROOM_TTL_SECONDS = 600`.

- [ ] **Step 1: TurnCredentialIssuer를 빈으로**

`TurnCredentialIssuer`에 `@Component`(import `org.springframework.stereotype.Component`)를 붙이고 생성자를 다음으로 바꾼다(`org.springframework.beans.factory.annotation.Value` import):

```java
    TurnCredentialIssuer(
            @Value("${app.room.turn.secret:draft-turn-secret}") String secret,
            @Value("${app.room.turn.ttl-seconds:86400}") int ttlSeconds,
            @Value("${app.room.turn.urls:}") List<String> urls) {
```

- [ ] **Step 2: RoomService 전면 교체**

`src/main/java/project/study/room/service/RoomService.java`:

```java
package project.study.room.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomJoinResponse;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;

/**
 * 룸 멤버십 (스펙 §2.1~§2.6). 진실 원천은 DB다.
 *
 * <p>락 순서: 유저 advisory → 방 행(id 오름차순) → 참가자 행 → 코드 advisory(닫을 때만). 락을 잡기 전에 읽은
 * 값은 판단에 쓰지 않는다 — 방 락을 잡은 뒤 참가자 행을 다시 잠가 읽고 그 값으로 분기한다. 이것이 옛 전역
 * synchronized 락이 주던 "읽기와 쓰기 사이에 아무도 끼어들지 않음"을 대신한다. 모든 갱신은 건수를 확인한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    public static final int MAX_PARTICIPANTS = 6;
    public static final int EMPTY_ROOM_TTL_SECONDS = RoomCleanupService.EMPTY_ROOM_TTL_SECONDS;
    public static final int CLOSED_CODE_TTL_SECONDS = 600;
    private static final int INVITE_CODE_MAX_ATTEMPTS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record JoinResult(RoomJoinResponse response, AutoLeave autoLeave) {}

    public record LeaveResult(boolean removed, boolean roomStillOpen) {
        public static final LeaveResult NONE = new LeaveResult(false, false);
    }

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;
    private final ParticipantRemover remover;
    private final TurnCredentialIssuer turnCredentials;
    private final TransactionTemplate tx;
    private final Clock clock;

    // 생성만으로는 입장 상태가 아니다 — 생성자도 join으로만 입장한다. 시도마다 짧은 트랜잭션(코드 락 + INSERT 한 문장)
    public RoomCreateResponse create(Long userId) {
        Instant now = clock.instant();
        Instant tombstoneCutoff = now.minusSeconds(CLOSED_CODE_TTL_SECONDS);
        for (int attempt = 0; attempt < INVITE_CODE_MAX_ATTEMPTS; attempt++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            Optional<Long> roomId = tx.execute(status -> rooms.insertIfCodeFree(code, userId, now, tombstoneCutoff));
            if (roomId != null && roomId.isPresent()) {
                return new RoomCreateResponse(roomId.get(), code, EMPTY_ROOM_TTL_SECONDS);
            }
        }
        throw new ConflictException("사용 가능한 초대코드가 없습니다");
    }

    // 자리 예약만 하고 30초 안에 STOMP 구독으로 확정한다 (스펙 §2.2)
    @Transactional
    public JoinResult join(Long userId, String inviteCode, String nickname, String goal, String category) {
        if (inviteCode == null || !inviteCode.matches("\\d{4}")) {
            throw new BadRequestException("초대코드는 숫자 4자리여야 합니다");
        }
        Instant now = clock.instant();
        Profile profile = new Profile(nickname, goal, category);

        rooms.lockUser(userId);
        RoomRow target = findJoinableRoom(inviteCode, now);
        Optional<Long> currentRoomId = participations.findLiveRoomIdOfUser(userId);
        Map<Long, RoomRow> locked = lockRooms(target.id(), currentRoomId);
        RoomRow lockedTarget = locked.get(target.id());
        if (lockedTarget == null || !lockedTarget.isOpen()) {
            throw new NotFoundException(ErrorCode.ROOM_CLOSED, "방이 종료되었어요"); // 조회와 락 사이에 닫힘
        }

        // 락 아래에서 다시 읽는다 — 이 값만 판단에 쓴다
        Optional<Row> current = participations.lockLiveOfUser(userId);
        if (current.isPresent() && current.get().roomId().equals(lockedTarget.id())) {
            return rejoin(current.get(), lockedTarget, profile, now);
        }
        current.ifPresent(row -> {
            if (!locked.containsKey(row.roomId())) {
                throw new IllegalStateException("유저 락 아래에서 잠그지 않은 방의 자리가 나타남: userId=" + userId);
            }
        });
        return takeSeat(lockedTarget, current, locked, userId, profile, now);
    }

    private RoomRow findJoinableRoom(String inviteCode, Instant now) {
        RoomRow room = rooms.findLatestByCode(inviteCode)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVITE_CODE_NOT_FOUND, "코드를 다시 확인해 주세요"));
        if (room.isOpen()) {
            return room;
        }
        if (room.closedAt().plusSeconds(CLOSED_CODE_TTL_SECONDS).isAfter(now)) {
            throw new NotFoundException(ErrorCode.ROOM_CLOSED, "방이 종료되었어요");
        }
        throw new NotFoundException(ErrorCode.INVITE_CODE_NOT_FOUND, "코드를 다시 확인해 주세요");
    }

    private Map<Long, RoomRow> lockRooms(Long targetId, Optional<Long> currentRoomId) {
        List<Long> ids = Stream.concat(Stream.of(targetId), currentRoomId.stream()).distinct().toList();
        return rooms.lockByIds(ids).stream().collect(Collectors.toMap(RoomRow::id, Function.identity()));
    }

    // 같은 방에 이미 자리가 있다 — 유예 복귀(끊김 상태) 또는 예약 재시도·확정 멤버의 중복 join
    private JoinResult rejoin(Row current, RoomRow room, Profile profile, Instant now) {
        if (current.disconnectedAt() != null) {
            expectOne(participations.restoreFromGrace(current.id(), profile, now), "유예 복귀");
            log.debug("재입장(유예 중 복원): roomId={}, userId={}", room.id(), current.userId());
            return new JoinResult(response(room.id(), true, current.cameraOn(), current.userId()), null);
        }
        expectOne(participations.refreshReservation(current.id(), profile, now), "예약 갱신");
        return new JoinResult(response(room.id(), false, null, current.userId()), null);
    }

    // 정원 검사를 기존 방 퇴장보다 먼저 한다 — 대상 방이 가득이면 기존 방 자리를 잃지 않아야 한다
    private JoinResult takeSeat(
            RoomRow target, Optional<Row> current, Map<Long, RoomRow> locked, Long userId, Profile profile, Instant now) {
        if (participations.countLive(target.id()) >= MAX_PARTICIPANTS) {
            throw new ConflictException("방이 가득 찼어요");
        }
        AutoLeave autoLeave = null;
        if (current.isPresent()) {
            RoomRow oldRoom = locked.get(current.get().roomId());
            if (remover.remove(current.get(), oldRoom, LeaveReason.SWITCHED_ROOM, now, null).removed()) {
                autoLeave = new AutoLeave(oldRoom.id(), userId);
            }
        }
        participations.insertReservation(target.id(), userId, profile, now);
        log.debug("신규 입장 예약: roomId={}, userId={}, autoLeave={}", target.id(), userId, autoLeave);
        return new JoinResult(response(target.id(), false, null, userId), autoLeave);
    }

    private RoomJoinResponse response(Long roomId, boolean graceRejoin, Boolean cameraOn, Long userId) {
        return new RoomJoinResponse(
                roomId, graceRejoin, cameraOn, turnCredentials.forUser(userId), turnCredentials.ttlSeconds());
    }

    @Transactional
    public LeaveResult leave(Long roomId, Long userId) {
        Optional<RoomRow> room = rooms.lockById(roomId).filter(RoomRow::isOpen);
        if (room.isEmpty()) {
            return LeaveResult.NONE;
        }
        Optional<Row> row = participations.lockLive(roomId, userId);
        if (row.isEmpty()) {
            return LeaveResult.NONE;
        }
        var removed = remover.remove(row.get(), room.get(), LeaveReason.EXPLICIT, clock.instant(), null);
        log.debug("퇴장 요청: roomId={}, userId={}, 처리됨={}", roomId, userId, removed.removed());
        return new LeaveResult(removed.removed(), !removed.roomClosed());
    }

    /**
     * STOMP 구독으로 자리를 확정한다. 0행이면 빈 목록(방·참가자 없음, 또는 더 늦게 열린 세션이 이미 확정됨).
     * 방 락은 잡지 않는다 — 인원이 안 바뀐다. join이 이 행을 잠그고 있으면 그 커밋 뒤에 적용된다.
     */
    @Transactional
    public List<RoomMember> confirmStomp(Long roomId, Long userId, String sessionId, Instant sessionOpenedAt, String taskId) {
        int confirmed = participations.confirm(roomId, userId, sessionId, sessionOpenedAt, taskId, clock.instant());
        if (confirmed == 0) {
            log.debug("STOMP 확정 실패(방/참가자 없음 또는 옛 세션): roomId={}, userId={}", roomId, userId);
            return List.of();
        }
        log.debug("STOMP 확정: roomId={}, userId={}, stompSessionId={}", roomId, userId, sessionId);
        return participations.findConfirmedMembers(roomId);
    }

    /** 끊김 — 세션 ID로 바로 찾는다. 재접속이 먼저 도착했으면 0행(옛 세션의 뒤늦은 끊김)이라 무시된다. */
    @Transactional
    public boolean handleDisconnect(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        boolean started = participations.markDisconnected(sessionId, clock.instant()) == 1;
        log.debug("연결 해제: stompSessionId={}, 유예 시작={}", sessionId, started);
        return started;
    }

    @Transactional(readOnly = true)
    public boolean roomExists(Long roomId) {
        return rooms.isOpen(roomId);
    }

    private static void expectOne(int updated, String what) {
        if (updated != 1) {
            throw new IllegalStateException(what + " 갱신 행 수가 1이 아님: " + updated);
        }
    }
}
```

`git rm src/main/java/project/study/room/service/Room.java src/main/java/project/study/room/service/Participant.java src/main/java/project/study/room/service/ClosedInviteCodes.java`

- [ ] **Step 3: 컨트롤러 — LeaveResult, create 유저 검증**

`RoomController`에서 `import static project.study.room.service.RoomService.*;` 줄을 지우고 `import project.study.room.service.AutoLeave;`, `import project.study.room.service.RoomService.JoinResult;`, `import project.study.room.service.RoomService.LeaveResult;`를 넣는다. 메서드 본문:

```java
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomCreateResponse create(@Valid @RequestBody RoomCreateRequest request) {
        // rooms.created_by FK — 없는 유저는 join과 같은 404(USER_NOT_FOUND)로 답한다
        userService.getProfile(request.userId());
        return roomService.create(request.userId());
    }
```

`leave`:

```java
    public void leave(@PathVariable Long roomId, @RequestParam Long userId) {
        LeaveResult result = roomService.leave(roomId, userId);
        if (result.removed() && result.roomStillOpen()) {
            messagingTemplate.convertAndSend(
                    "/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_LEFT", "userId", userId));
        }
    }
```

`join`의 `AutoLeave al = result.autoLeave();` 부분은 타입 import만 바뀌고 그대로다.

- [ ] **Step 4: STOMP 핸들러 — RoomStateService, state 한 문장, signal 인가**

`RoomStompHandler`의 필드를 `private final RoomStateService roomStateService;`로 바꾸고(import `project.study.room.service.RoomStateService`), 메서드를 교체한다:

```java
    @MessageMapping("/room/{roomId}/snapshot")
    public void handleSnapshotRequest(
            @DestinationVariable Long roomId, Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal == null) return;

        Long userId = Long.valueOf(principal.getName());
        List<RoomMember> members = roomStateService.getMembersForActiveSession(roomId, userId, accessor.getSessionId());
        if (members.isEmpty()) {
            log.debug("snapshot 재요청 무시(비멤버 또는 비활성 세션): roomId={}, userId={}", roomId, userId);
            return;
        }

        log.debug("snapshot 재발송: roomId={}, userId={}, 인원={}", roomId, userId, members.size());
        // 세션 스코프 발송 — 같은 유저의 남은 옛 세션까지 배달되지 않도록 요청 세션에만 보낸다
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(accessor.getSessionId());
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/room",
                Map.of("type", "SNAPSHOT", "members", members),
                headers.getMessageHeaders());
    }

    @MessageMapping("/room/{roomId}/signal")
    public void handleSignal(
            @DestinationVariable Long roomId,
            SignalPayload payload,
            Principal principal,
            SimpMessageHeaderAccessor accessor) {
        if (principal == null
                || payload == null
                || payload.toUserId() == null
                || payload.payload() == null
                || payload.kind() == null
                || !SIGNAL_KINDS.contains(payload.kind())) {
            log.debug("signal 요청 형식 검증 실패: roomId={}, principal={}", roomId, principal);
            return;
        }

        Long fromUserId = Long.valueOf(principal.getName());
        // 발신자의 "현재" 세션 + 수신자가 확정 멤버인지를 한 번의 조회로 — 옛 세션·비멤버의 시그널 주입 차단
        if (!roomStateService.authorizeSignal(roomId, fromUserId, accessor.getSessionId(), payload.toUserId())) {
            log.debug("signal 인가 실패: roomId={}, fromUserId={}, toUserId={}", roomId, fromUserId, payload.toUserId());
            return;
        }

        messagingTemplate.convertAndSendToUser(payload.toUserId().toString(), "/queue/room", (Object) Map.of(
                "type", "SIGNAL", "fromUserId", fromUserId, "kind", payload.kind(), "payload", payload.payload()));
    }

    // 필드별 검증은 SQL 전에 지금처럼 한다 — 무효한 필드만 무시(null)하고 나머지는 반영한다 (스펙 §2.7)
    @MessageMapping("/room/{roomId}/state")
    public void handleState(
            @DestinationVariable Long roomId,
            StateUpdatePayload payload,
            Principal principal,
            SimpMessageHeaderAccessor accessor) {
        if (principal == null || payload == null) return;

        Long userId = Long.valueOf(principal.getName());
        StateChange change = sanitize(payload);
        // 갱신 1행 = 현재 세션의 확정 멤버 인가 + 저장. 저장이 성공했을 때만 브로드캐스트한다
        if (!roomStateService.updateState(
                roomId, userId, accessor.getSessionId(), change.cameraOn(), change.focusState(), change.focusSec())) {
            log.debug("state 인가 실패 또는 갱신 없음: roomId={}, userId={}", roomId, userId);
            return;
        }
        broadcastChanges(roomId, userId, change);
    }

    private record StateChange(Boolean cameraOn, String focusState, Integer focusSec) {}

    private static StateChange sanitize(StateUpdatePayload payload) {
        String focusState = payload.focusState() != null && FOCUS_STATES.contains(payload.focusState())
                ? payload.focusState()
                : null;
        Integer focusSec = payload.focusSec() != null && payload.focusSec() >= 0 ? payload.focusSec() : null;
        return new StateChange(payload.cameraOn(), focusState, focusSec);
    }

    private void broadcastChanges(Long roomId, Long userId, StateChange change) {
        String topic = "/topic/room/" + roomId;
        if (change.cameraOn() != null) {
            messagingTemplate.convertAndSend(topic, (Object)
                    Map.of("type", "CAMERA_CHANGED", "userId", userId, "cameraOn", change.cameraOn()));
        }
        if (change.focusState() != null) {
            messagingTemplate.convertAndSend(topic, (Object)
                    Map.of("type", "FOCUS_CHANGED", "userId", userId, "focusState", change.focusState()));
        }
        if (change.focusSec() != null) {
            messagingTemplate.convertAndSend(topic, (Object)
                    Map.of("type", "STUDY_TIME", "userId", userId, "focusSec", change.focusSec()));
        }
    }
```

기존 `broadcastCameraChange`·`broadcastFocusChange`·`broadcastStudyTime` 메서드는 삭제한다. 순환복잡도 10 제한 때문에 검증(`sanitize`)과 방송(`broadcastChanges`)을 분리했다. `FOCUS_STATES.contains(null)`은 `Set.of`에서 NPE라 null을 먼저 거른다.

- [ ] **Step 5: STOMP 리스너 — 새 confirm 시그니처 (사전/사후 검사·ROOM_UNAVAILABLE은 Task 8)**

`StompEventListener`에 `private final SessionRegistry sessionRegistry;`, `private final TaskIdentity taskIdentity;`, `private final Clock clock;` 필드를 추가하고(import `java.time.Clock`, `java.time.Instant`, `project.study.room.lease.TaskIdentity`, `project.study.room.websocket.SessionRegistry`), `handleSubscribe`의 `confirmStomp` 호출을 바꾼다:

```java
        Long roomId = Long.valueOf(matcher.group(1));
        Long userId = Long.valueOf(principal.getName());
        String sessionId = accessor.getSessionId();
        Instant openedAt = sessionRegistry.openedAt(sessionId).orElseGet(clock::instant);

        List<RoomMember> members = roomService.confirmStomp(roomId, userId, sessionId, openedAt, taskIdentity.id());
```

나머지(SNAPSHOT 개인 큐 발송, MEMBER_JOINED 브로드캐스트)는 그대로. `RoomMember self = ... .orElse(new RoomMember(userId, null, null, null, false, "FOCUS", 0, false));`로 인자를 맞춘다.

- [ ] **Step 6: WebSocketConfig·스케줄러 전환**

`WebSocketConfig`: `RoomService` 의존을 `RoomStateService`로 바꾼다 — 필드 `private final RoomStateService roomStateService;`, `UserIdChannelInterceptor`의 필드·생성자 인자도 `RoomStateService`, `allowSubscribe`의 `roomService.hasParticipant(roomId, userId)` → `roomStateService.hasParticipant(roomId, userId)`. `configureClientInboundChannel`의 `new UserIdChannelInterceptor(roomStateService)`.

`RoomCleanupScheduler` 전면 교체:

```java
package project.study.room.scheduler;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.room.service.RoomCleanupService;

@Component
@RequiredArgsConstructor
public class RoomCleanupScheduler {

    private final RoomCleanupService roomCleanupService;
    private final SimpMessagingTemplate messagingTemplate;

    // 방 하나를 커밋할 때마다 그 방의 MEMBER_LEFT를 즉시 보낸다 — 뒤 방의 실패가 앞 방의 알림을 막지 않는다
    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        roomCleanupService.cleanupExpired(Instant.now(), al -> messagingTemplate.convertAndSend(
                "/topic/room/" + al.roomId(), (Object) Map.of("type", "MEMBER_LEFT", "userId", al.userId())));
    }
}
```

- [ ] **Step 7: 컴파일 확인**

Run: `./gradlew compileJava -q`
Expected: 성공. (테스트 컴파일은 Step 8에서 이식 후 확인)

- [ ] **Step 8: 서비스 테스트 이식 — 공통 베이스**

`src/test/java/project/study/room/support/RoomTestBase.java` (어노테이션 없음, 구체 클래스가 `@SpringBootTest @Import(TestcontainersConfiguration.class) @Transactional`을 단다):

```java
package project.study.room.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.room.dto.RoomMember;
import project.study.room.repository.RoomRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;
import project.study.room.service.RoomService;
import project.study.room.service.RoomService.JoinResult;
import project.study.room.service.RoomStateService;

/** 룸 서비스 통합 테스트 공통 — 실제 유저·방을 만들고 옛 인메모리 테스트와 같은 어휘(join/confirm/cleanupAfter)를 제공한다. */
public abstract class RoomTestBase {

    protected static final String TASK = "task-test";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    protected RoomService roomService;

    @Autowired
    protected RoomStateService roomState;

    @Autowired
    protected RoomCleanupService roomCleanup;

    @Autowired
    protected RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    protected RoomProbe probe;
    protected long owner;

    @BeforeEach
    void setUpBase() {
        probe = new RoomProbe(jdbc);
        owner = probe.insertUser();
    }

    protected long user() {
        return probe.insertUser();
    }

    protected String createRoom() {
        return roomService.create(owner).inviteCode();
    }

    // 프로필 값이 중요하지 않은 테스트용 기본 join — 닉네임·목표는 join 시점에 호출자가 전달한다
    protected JoinResult join(long userId, String code) {
        return roomService.join(userId, code, "포메" + userId, null, null);
    }

    protected List<RoomMember> confirm(long roomId, long userId, String sessionId) {
        return roomService.confirmStomp(roomId, userId, sessionId, Instant.now(), TASK);
    }

    protected List<RoomMember> confirmOpenedAt(long roomId, long userId, String sessionId, Instant openedAt) {
        return roomService.confirmStomp(roomId, userId, sessionId, openedAt, TASK);
    }

    /** 지정한 방들의 AutoLeave만 돌려준다 — 트랜잭션 없는 다른 테스트가 커밋한 방의 만료가 섞이지 않게. 방을 안 주면 전부. */
    protected List<AutoLeave> cleanupAfter(long seconds, long... roomIds) {
        Set<Long> scope = Arrays.stream(roomIds).boxed().collect(Collectors.toSet());
        return roomCleanup.cleanupExpired(Instant.now().plusSeconds(seconds)).stream()
                .filter(al -> scope.isEmpty() || scope.contains(al.roomId()))
                .toList();
    }

    /** 어느 방도 쓰지 않는 코드 — 랜덤 발급이라 "없는 코드"를 고정할 수 없어서 조회로 고른다. */
    protected String unusedCode() {
        for (int i = 0; i < 1000; i++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            if (rooms.findLatestByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("빈 코드를 못 찾음");
    }
}
```

- [ ] **Step 9: RoomServiceTest 이식 (멤버십)**

`src/test/java/project/study/room/RoomServiceTest.java` 전면 교체:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.service.RoomService.JoinResult;
import project.study.room.service.RoomService.LeaveResult;
import project.study.room.support.RoomProbe;
import project.study.room.support.RoomTestBase;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomServiceTest extends RoomTestBase {

    @Test
    void 방을_만들면_숫자_4자리_초대코드가_발급된다() {
        RoomCreateResponse response = roomService.create(owner);

        assertThat(response.roomId()).isNotNull();
        assertThat(response.inviteCode()).matches("\\d{4}");
        assertThat(response.emptyTtlSeconds()).isEqualTo(600);
        assertThat(rooms.isOpen(response.roomId())).isTrue();
    }

    @Test
    void 생성만으로는_입장_상태가_아니다() {
        roomService.create(owner);

        assertThat(roomState.getRoomIdForUser(owner)).isNull();
    }

    @Test
    void 초대코드로_입장하면_참가자가_추가된다() {
        String code = createRoom();
        long userId = user();

        JoinResult result = join(userId, code);

        assertThat(result.response().graceRejoin()).isFalse();
        assertThat(result.autoLeave()).isNull();
        assertThat(roomState.getRoomIdForUser(userId)).isEqualTo(result.response().roomId());
        assertThat(probe.participation(result.response().roomId(), userId)).get()
                .extracting(RoomProbe.Participation::stompConfirmed)
                .isEqualTo(false);
    }

    @Test
    void 형식이_틀린_초대코드는_400이다() {
        long userId = user();
        assertThatThrownBy(() -> join(userId, "12a4")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(userId, "123")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> join(userId, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 없는_초대코드는_404다() {
        long userId = user();
        assertThatThrownBy(() -> join(userId, unusedCode())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 정원_6명_초과_시_ConflictException이_발생한다() {
        String code = createRoom();
        for (int i = 0; i < 6; i++) {
            join(user(), code);
        }

        long seventh = user();
        assertThatThrownBy(() -> join(seventh, code)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 다른_방에_있으면_자동_퇴장_후_새_방에_입장한다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        join(user(), codeA); // roomA가 소멸하지 않도록 다른 참가자 유지

        JoinResult result = join(userId, codeB);

        assertThat(result.autoLeave()).isNotNull();
        assertThat(result.autoLeave().roomId()).isEqualTo(roomA);
        assertThat(roomState.getRoomIdForUser(userId)).isEqualTo(result.response().roomId());
        assertThat(rooms.isOpen(roomA)).isTrue();
    }

    @Test
    void 확정된_뒤_방을_옮기면_옛_방_이력에_SWITCHED_ROOM이_남는다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        confirm(roomA, userId, "s1");

        join(userId, codeB);

        RoomProbe.Participation history = probe.participation(roomA, userId).orElseThrow();
        assertThat(history.leftAt()).isNotNull();
        assertThat(history.leaveReason()).isEqualTo("SWITCHED_ROOM");
        assertThat(rooms.isOpen(roomA)).as("마지막 1명이 옮겨 갔으니 옛 방은 닫힌다").isFalse();
    }

    @Test
    void 마지막_1명이_퇴장하면_방과_코드가_소멸한다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        LeaveResult result = roomService.leave(roomId, userId);

        assertThat(result).isEqualTo(new LeaveResult(true, false));
        assertThat(roomService.roomExists(roomId)).isFalse();
        long next = user();
        assertThatThrownBy(() -> join(next, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 남은_인원이_있으면_방이_유지된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        join(user(), code);

        LeaveResult result = roomService.leave(roomId, userId);

        assertThat(result).isEqualTo(new LeaveResult(true, true));
        assertThat(roomService.roomExists(roomId)).isTrue();
        assertThat(roomService.leave(roomId, userId)).as("두 번째 퇴장은 없음").isEqualTo(LeaveResult.NONE);
    }

    @Test
    void 확정된_참가자의_퇴장은_left_at과_사유와_마지막_순공초를_남긴다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "s1");
        roomState.updateState(roomId, userId, "s1", null, null, 1500);

        roomService.leave(roomId, userId);

        RoomProbe.Participation history = probe.participation(roomId, userId).orElseThrow();
        assertThat(history.joinedAt()).isNotNull();
        assertThat(history.leftAt()).isNotNull();
        assertThat(history.leaveReason()).isEqualTo("EXPLICIT");
        assertThat(history.focusSec()).isEqualTo(1500);
    }

    @Test
    void 확정_없는_예약은_퇴장_시_이력_없이_지워진다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        roomService.leave(roomId, userId);

        assertThat(probe.participation(roomId, userId)).isEmpty();
    }

    @Test
    void 유예_기간_내_재입장하면_graceRejoin이_true다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomState.updateState(roomId, userId, "session-1", true, null, null);
        roomService.handleDisconnect("session-1");

        JoinResult result = join(userId, code);

        assertThat(result.response().graceRejoin()).isTrue();
        assertThat(result.response().cameraOn()).isTrue();
        assertThat(probe.participation(roomId, userId)).get()
                .extracting(RoomProbe.Participation::stompConfirmed)
                .as("복귀는 예약 상태로 되돌려 30초 안에 다시 구독하게 한다")
                .isEqualTo(false);
    }

    @Test
    void 대상_방이_가득_차면_기존_방_자리를_잃지_않는다() {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();
        long roomA = join(userId, codeA).response().roomId();
        for (int i = 0; i < 6; i++) {
            join(user(), codeB);
        }

        assertThatThrownBy(() -> join(userId, codeB)).isInstanceOf(ConflictException.class);
        assertThat(roomState.getRoomIdForUser(userId)).isEqualTo(roomA);
    }
}
```

- [ ] **Step 10: RoomServiceConfirmTest (확정·세션·스냅샷)**

`src/test/java/project/study/room/RoomServiceConfirmTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.room.dto.RoomMember;
import project.study.room.support.RoomProbe;
import project.study.room.support.RoomTestBase;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomServiceConfirmTest extends RoomTestBase {

    @Test
    void STOMP_확정_후_멤버_목록에_포함된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        List<RoomMember> members = confirm(roomId, userId, "session-1");

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(userId);
        assertThat(members.getFirst().focusState()).isEqualTo("FOCUS");
        assertThat(members.getFirst().disconnected()).isFalse();
        assertThat(probe.participation(roomId, userId).orElseThrow().joinedAt()).isNotNull();
    }

    @Test
    void 스냅샷_멤버에_닉네임과_목표가_실린다() {
        String code = createRoom();
        long userId = user();
        long roomId = roomService.join(userId, code, "숨벅찬포메", "정처기 합격", "CERTIFICATE").response().roomId();

        List<RoomMember> members = confirm(roomId, userId, "session-1");

        assertThat(members.getFirst().nickname()).isEqualTo("숨벅찬포메");
        assertThat(members.getFirst().goal()).isEqualTo("정처기 합격");
    }

    @Test
    void 방_멤버가_아니면_확정_멤버가_아니다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.isConfirmedMember(roomId, 999_999L)).isFalse();
        // 예약만 하고 STOMP 확정 전이면 아직 멤버가 아니다
        assertThat(roomState.isConfirmedMember(roomId, userId)).isFalse();
    }

    @Test
    void STOMP_확정_전에는_상태를_갱신할_수_없다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.updateState(roomId, userId, "session-1", true, "DISTRACTED", 300)).isFalse();

        confirm(roomId, userId, "session-1");
        assertThat(roomState.updateState(roomId, userId, "session-1", true, null, null)).isTrue();
    }

    @Test
    void 마지막_순공시간이_스냅샷에_실린다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        // 확정 직후 초기값은 0 — 새 입장자가 빈 값을 보지 않는다
        assertThat(roomState.getMembers(roomId).getFirst().focusSec()).isZero();

        assertThat(roomState.updateState(roomId, userId, "session-1", null, null, 1500)).isTrue();
        assertThat(roomState.getMembers(roomId).getFirst().focusSec()).isEqualTo(1500);
    }

    // 스냅샷 재요청(BY-442) — 인가와 조회가 한 번의 원자 호출이어야 한다
    @Test
    void 활성_세션_멤버는_스냅샷_재요청으로_멤버_목록을_받는다() {
        String code = createRoom();
        long me = user();
        long peer = user();
        long roomId = join(me, code).response().roomId();
        confirm(roomId, me, "session-1");
        join(peer, code);
        confirm(roomId, peer, "session-2");

        List<RoomMember> members = roomState.getMembersForActiveSession(roomId, me, "session-1");

        assertThat(members).extracting(RoomMember::userId).containsExactlyInAnyOrder(me, peer);
    }

    @Test
    void 옛_세션의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomState.getMembersForActiveSession(roomId, userId, "session-old")).isEmpty();
    }

    @Test
    void 확정_전_참가자의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.getMembersForActiveSession(roomId, userId, "session-1")).isEmpty();
    }

    @Test
    void 비멤버와_없는_방의_스냅샷_재요청은_빈_목록을_받는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        assertThat(roomState.getMembersForActiveSession(roomId, 999_999L, "session-x")).isEmpty();
        assertThat(roomState.getMembersForActiveSession(roomId + 1_000_000, userId, "session-1")).isEmpty();
    }

    @Test
    void 현재_세션에서_온_메시지만_활성_세션으로_인정된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomState.isActiveSession(roomId, userId, "session-new")).isTrue();
        assertThat(roomState.isActiveSession(roomId, userId, "session-old")).isFalse();
        assertThat(roomState.isActiveSession(roomId, userId, null)).isFalse();
    }

    @Test
    void 더_늦게_열린_세션이_확정된_뒤_옛_세션의_뒤늦은_confirm은_무시된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        Instant oldOpened = Instant.now().minusSeconds(10);
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(confirmOpenedAt(roomId, userId, "session-old", oldOpened)).isEmpty();
        assertThat(roomState.isActiveSession(roomId, userId, "session-new")).isTrue();
    }

    @Test
    void 자리_예약자만_방_토픽을_구독할_수_있다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        assertThat(roomState.hasParticipant(roomId, userId)).isTrue();
        assertThat(roomState.hasParticipant(roomId, 999_999L)).isFalse();
    }

    @Test
    void 재접속_후_도착한_옛_세션의_끊김_이벤트는_무시된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirmOpenedAt(roomId, userId, "session-old", Instant.now().minusSeconds(10));
        // 재접속으로 새 세션 확정 → 그 뒤에 옛 세션의 disconnect가 늦게 도착
        confirmOpenedAt(roomId, userId, "session-new", Instant.now());

        assertThat(roomService.handleDisconnect("session-old")).isFalse();

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 유예_중_재구독으로_확정되면_유예가_해제되어_쫓겨나지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        assertThat(roomService.handleDisconnect("session-1")).isTrue();
        assertThat(roomState.getMembers(roomId).getFirst().disconnected()).as("유예 중은 disconnected로 실린다").isTrue();

        // join 재호출 없이 STOMP 재구독만으로 복귀하는 경로
        confirm(roomId, userId, "session-2");

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
        assertThat(roomState.getMembers(roomId).getFirst().disconnected()).isFalse();
    }

    @Test
    void 유예_복귀와_confirm의_순서가_어느_쪽이든_최종_상태는_확정이다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");

        // 순서 A: HTTP join(복귀) → STOMP confirm
        assertThat(join(userId, code).response().graceRejoin()).isTrue();
        confirm(roomId, userId, "session-2");
        assertThat(roomState.isActiveSession(roomId, userId, "session-2")).isTrue();

        // 순서 B: STOMP confirm이 먼저, 그 뒤 HTTP join — 이미 멤버라 되돌리지 않는다
        roomService.handleDisconnect("session-2");
        confirm(roomId, userId, "session-3");
        assertThat(join(userId, code).response().graceRejoin()).isFalse();
        assertThat(roomState.isActiveSession(roomId, userId, "session-3")).isTrue();
        assertThat(cleanupAfter(31, roomId)).isEmpty();
    }
}
```

- [ ] **Step 11: RoomExpiryTest (만료·빈 방·후보 정합성) — `RoomServiceExpiryIndexTest.java`는 삭제**

`src/test/java/project/study/room/RoomExpiryTest.java`:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.NotFoundException;
import project.study.room.service.AutoLeave;
import project.study.room.support.RoomTestBase;

/**
 * 만료 정합성. 예약 30초·유예 30초·빈 방 600초. 잘못 만료되면 정상 참가자가 쫓겨나고, 안 만료되면 자리·방이 샌다.
 * (옛 RoomServiceTest의 만료 케이스 + BY-593 RoomServiceExpiryIndexTest를 흡수)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomExpiryTest extends RoomTestBase {

    @Test
    void 만료된_예약이_정리되고_방도_소멸한다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();

        List<AutoLeave> removed = cleanupAfter(31, roomId);

        assertThat(removed).containsExactly(new AutoLeave(roomId, userId));
        assertThat(roomService.roomExists(roomId)).isFalse();
    }

    @Test
    void 끊김_유예_만료_후_참가자가_제거된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");

        List<AutoLeave> removed = cleanupAfter(31, roomId);

        assertThat(removed).containsExactly(new AutoLeave(roomId, userId));
        assertThat(roomState.getRoomIdForUser(userId)).isNull();
        assertThat(probe.participation(roomId, userId).orElseThrow().leaveReason()).isEqualTo("DISCONNECT_TIMEOUT");
    }

    @Test
    void 입장_이력_없는_빈_방은_10분_뒤_소멸한다() {
        String code = createRoom();

        cleanupAfter(601);

        long userId = user();
        assertThatThrownBy(() -> join(userId, code)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 십분이_지나지_않은_빈_방은_유지된다() {
        String code = createRoom();

        cleanupAfter(599);

        assertThat(join(user(), code).response().roomId()).isNotNull();
    }

    @Test
    void 확정된_참가자는_예약_TTL이_지나도_유지된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 유예복원_후_확정하지_않으면_예약_만료로_제거된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");
        // 유예 중 재입장(복원) — 다시 미확정 예약 상태가 되므로 예약 만료 대상이어야 한다
        join(userId, code);

        assertThat(cleanupAfter(31, roomId)).hasSize(1);
        assertThat(roomState.getRoomIdForUser(userId)).isNull();
    }

    @Test
    void 확정된_참가자가_끊긴_뒤_재확정하면_유예_만료되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        roomService.handleDisconnect("session-1");
        join(userId, code);
        confirm(roomId, userId, "session-2");

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 여러_방_중_미확정_예약만_만료되고_확정_방은_유지된다() {
        String codeA = createRoom();
        long a = user();
        long roomA = join(a, codeA).response().roomId();
        confirm(roomA, a, "session-A");
        String codeB = createRoom();
        long b = user();
        long roomB = join(b, codeB).response().roomId();

        assertThat(cleanupAfter(31, roomA, roomB)).extracting(AutoLeave::userId).containsExactly(b);
        assertThat(roomState.isConfirmedMember(roomA, a)).isTrue();
        assertThat(roomService.roomExists(roomB)).isFalse();
    }

    @Test
    void 확정된_멤버가_같은_방에_중복_join해도_만료되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");
        // 연결 유지 중인 멤버의 중복 join(재시도 분기) — 확정 상태가 유지돼야 한다
        join(userId, code);

        assertThat(cleanupAfter(31, roomId)).isEmpty();
        assertThat(roomState.isConfirmedMember(roomId, userId)).isTrue();
    }

    @Test
    void 첫_입장이_생긴_방은_빈_방_TTL_대상에서_빠진다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        confirm(roomId, userId, "session-1");

        cleanupAfter(601);

        assertThat(roomService.roomExists(roomId)).isTrue();
    }
}
```

- [ ] **Step 12: RoomInviteCodeErrorTest 이식**

`src/test/java/project/study/room/RoomInviteCodeErrorTest.java` 전면 교체:

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.room.support.RoomTestBase;

/** BY-436 초대코드 404 에러 코드 구분. 묘비는 닫힌 행(closed_at)이다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoomInviteCodeErrorTest extends RoomTestBase {

    private static void assertNotFoundWithCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    @Test
    void 발급된_적_없는_코드는_INVITE_CODE_NOT_FOUND다() {
        long userId = user();
        assertNotFoundWithCode(() -> join(userId, unusedCode()), ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    void 마지막_1명이_퇴장해_소멸한_방의_코드는_ROOM_CLOSED다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);

        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 입장_없이_만료돼_소멸한_빈_방의_코드도_ROOM_CLOSED다() {
        String code = createRoom();

        cleanupAfter(601);

        long userId = user();
        assertNotFoundWithCode(() -> join(userId, code), ErrorCode.ROOM_CLOSED);
    }

    @Test
    void 소멸_10분이_지난_코드는_다시_INVITE_CODE_NOT_FOUND가_된다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);
        probe.closeRoomAt(roomId, Instant.now().minusSeconds(601)); // 닫힌 시각을 10분 전으로

        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    /**
     * 코드를 1만 개 공간에서 무작위로 뽑으므로 "재발급되지 않았다"를 직접 관측할 수는 없다 —
     * 대신 클라이언트가 실제로 보는 계약을 검증한다. 묘비 기간 안에 방이 여럿 새로 생겨도
     * 소멸한 코드로 들어오면 새 방 입장이 아니라 ROOM_CLOSED여야 한다 (BY-436).
     */
    @Test
    void 소멸한_코드는_묘비_기간_동안_다른_방에_재발급되지_않는다() {
        String code = createRoom();
        long userId = user();
        long roomId = join(userId, code).response().roomId();
        roomService.leave(roomId, userId);
        for (int i = 0; i < 50; i++) {
            roomService.create(owner);
        }

        assertThat(roomService.roomExists(roomId)).isFalse();
        long next = user();
        assertNotFoundWithCode(() -> join(next, code), ErrorCode.ROOM_CLOSED);
    }
}
```

- [ ] **Step 13: RoomApiTest·RoomStompHandlerTest·StompEventListenerTest 갱신**

`RoomApiTest`: `@Autowired private JdbcClient jdbc;`를 추가하고, 방 생성은 등록된 유저로 한다.

```java
    @Test
    void 방을_만들면_201과_초대코드가_내려온다() {
        long userId = registerUser();
        assertThat(mvc.post()
                        .uri("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": " + userId + "}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.roomId", v -> assertThat(v).isNotNull())
                .hasPathSatisfying("$.inviteCode", v -> assertThat(v).asString().matches("\\d{4}"))
                .hasPathSatisfying("$.emptyTtlSeconds", v -> assertThat(v).isEqualTo(600));
    }

    @Test
    void 등록되지_않은_유저는_방을_만들_수_없다() {
        assertThat(mvc.post()
                        .uri("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 999999999}"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("USER_NOT_FOUND"));
    }
```

나머지 테스트의 `createRoomAndGetCode(1L)`를 전부 `createRoomAndGetCode(registerUser())`로 바꾼다. `없는_초대코드는_404다`는 고정 코드 대신 조회로 고른다:

```java
    private String unusedCode() {
        for (int i = 0; i < 1000; i++) {
            String code = String.format("%04d", new java.security.SecureRandom().nextInt(10000));
            boolean used = jdbc.sql("select exists (select 1 from rooms where invite_code = :c)")
                    .param("c", code)
                    .query(Boolean.class)
                    .single();
            if (!used) return code;
        }
        throw new IllegalStateException("빈 코드를 못 찾음");
    }

    @Test
    void 없는_초대코드는_404다() {
        long userId = registerUser();

        assertThat(joinRequest(userId, unusedCode())).hasStatus(HttpStatus.NOT_FOUND);
    }
```

`RoomStompHandlerTest`: `@Mock private RoomService roomService;`를 `@Mock private RoomStateService roomStateService;`로, 기존 4개 테스트의 `roomService.getMembersForActiveSession` 스텁을 `roomStateService`로 바꾸고, 다음을 추가한다(import `project.study.room.dto.StateUpdatePayload`, `static org.mockito.ArgumentMatchers.isNull`):

```java
    @Test
    void state는_무효한_필드만_무시하고_유효한_필드는_저장_후_방송한다() {
        when(roomStateService.updateState(ROOM_ID, 1L, "session-1", true, null, null)).thenReturn(true);

        handler.handleState(ROOM_ID, new StateUpdatePayload(true, "INVALID", -1), USER_1, accessorWithSession("session-1"));

        verify(messagingTemplate)
                .convertAndSend("/topic/room/" + ROOM_ID, (Object) Map.of("type", "CAMERA_CHANGED", "userId", 1L, "cameraOn", true));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void state_저장이_거부되면_아무것도_방송하지_않는다() {
        when(roomStateService.updateState(ROOM_ID, 1L, "session-1", null, "DISTRACTED", 120)).thenReturn(false);

        handler.handleState(ROOM_ID, new StateUpdatePayload(null, "DISTRACTED", 120), USER_1, accessorWithSession("session-1"));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void 순공시간은_focusSec_키로_방송된다() {
        when(roomStateService.updateState(ROOM_ID, 1L, "session-1", null, null, 120)).thenReturn(true);

        handler.handleState(ROOM_ID, new StateUpdatePayload(null, null, 120), USER_1, accessorWithSession("session-1"));

        verify(messagingTemplate)
                .convertAndSend("/topic/room/" + ROOM_ID, (Object) Map.of("type", "STUDY_TIME", "userId", 1L, "focusSec", 120));
    }
```

`StompEventListenerTest`: `@Mock private SessionRegistry sessionRegistry; @Mock private TaskIdentity taskIdentity; @Mock private Clock clock;`를 추가한다(`@InjectMocks`가 생성자 주입으로 다섯 의존성을 채운다). 기존 2개 테스트는 그대로.

- [ ] **Step 14: 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: BUILD SUCCESSFUL. 옛 `RoomServiceExpiryIndexTest.java`는 `git rm`으로 삭제돼 있어야 한다.

```bash
git rm -q src/test/java/project/study/room/RoomServiceExpiryIndexTest.java
git add -A src/main/java/project/study src/test/java/project/study
git commit -m "feat: RoomService를 DB 진실 원천으로 교체 — 방 행 락·락 아래 재조회·조건부 갱신 (BY-626)

인메모리 Room/Participant/ClosedInviteCodes를 지우고 rooms·room_participations를 직접 쓴다.
컨트롤러·STOMP 핸들러·리스너·WebSocketConfig·스케줄러가 새 API(RoomStateService, RoomCleanupService,
LeaveResult, confirmStomp(sessionOpenedAt, taskId))를 쓰도록 바꾸고, 서비스 테스트 30건을 Testcontainers로 이식했다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: WebSocket 계층 — RoomMessenger · ROOM_UNAVAILABLE · confirm 사전/사후 검사 · 인바운드 executor

**Files:**
- Create: `src/main/java/project/study/room/websocket/RoomMessenger.java`
- Modify: `websocket/StompEventListener.java`, `config/WebSocketConfig.java`
- Test: `src/test/java/project/study/room/websocket/RoomMessengerTest.java`, `room/StompEventListenerTest.java`(추가), `src/test/java/project/study/config/UserIdChannelInterceptorTest.java`(신규)

**Interfaces:**
- Produces: `RoomMessenger` — `toSession(String userName, String sessionId, Object payload)`, `broadcast(Long roomId, Object payload)`, `roomUnavailable(String userName, String sessionId, Long roomId)`.
- `StompEventListener` 생성자 의존: `(RoomService, RoomMessenger, SessionRegistry, TaskIdentity, Clock)`.
- `WebSocketConfig.UserIdChannelInterceptor(RoomStateService, ObjectProvider<RoomMessenger>)`.

- [ ] **Step 1: 실패 테스트**

`src/test/java/project/study/room/websocket/RoomMessengerTest.java`:

```java
package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RoomMessengerTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final RoomMessenger messenger = new RoomMessenger(template);

    @Test
    void 세션_스코프_발송은_요청_세션_헤더를_단다() {
        messenger.roomUnavailable("7", "session-1", 3L);

        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(template)
                .convertAndSendToUser(
                        eq("7"), eq("/queue/room"), eq(Map.of("type", "ROOM_UNAVAILABLE", "roomId", 3L)), headers.capture());
        assertThat(headers.getValue()).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, "session-1");
    }

    @Test
    void 브로드캐스트는_방_토픽으로_간다() {
        Map<String, Object> payload = Map.of("type", "MEMBER_LEFT", "userId", 7L);

        messenger.broadcast(3L, payload);

        verify(template).convertAndSend("/topic/room/3", (Object) payload);
    }
}
```

`StompEventListenerTest`에 추가 (기존 `@Mock SimpMessagingTemplate`을 `@Mock RoomMessenger messenger`로 바꾸고, `verifyNoInteractions(roomService, messenger)`로 갱신):

```java
    private static Message<byte[]> subscribe(String sessionId, String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static final RoomMember ME = new RoomMember(7L, "포메", null, null, false, "FOCUS", 0, false);

    @Test
    void 구독_확정은_요청_세션에_SNAPSHOT을_보내고_방에_MEMBER_JOINED를_뿌린다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.of(Instant.parse("2026-09-09T00:00:00Z")));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(3L, 7L, "s1", Instant.parse("2026-09-09T00:00:00Z"), "task-A"))
                .thenReturn(List.of(ME));

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(messenger).toSession("7", "s1", Map.of("type", "SNAPSHOT", "members", List.of(ME)));
        verify(messenger).broadcast(3L, Map.of("type", "MEMBER_JOINED", "member", ME));
    }

    @Test
    void 확정에_실패하면_요청_세션에_ROOM_UNAVAILABLE을_보낸다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(Instant.parse("2026-09-09T00:00:00Z"));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(any(), any(), any(), any(), any())).thenReturn(List.of());

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(messenger).roomUnavailable("7", "s1", 3L);
        verifyNoMoreInteractions(messenger);
    }

    @Test
    void 이미_닫힌_세션의_구독은_확정하지_않는다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(false);

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verifyNoInteractions(roomService, messenger);
    }

    @Test
    void 확정_중에_세션이_닫혔으면_끊김으로_보정한다() {
        when(sessionRegistry.isOpen("s1")).thenReturn(true, false);
        when(sessionRegistry.openedAt("s1")).thenReturn(Optional.of(Instant.parse("2026-09-09T00:00:00Z")));
        when(taskIdentity.id()).thenReturn("task-A");
        when(roomService.confirmStomp(any(), any(), any(), any(), any())).thenReturn(List.of(ME));

        listener.handleSubscribe(new SessionSubscribeEvent(this, subscribe("s1", "/topic/room/3", "7")));

        verify(roomService).handleDisconnect("s1");
        verifyNoInteractions(messenger);
    }
```

(import 추가: `java.time.Instant`, `java.util.List`, `java.util.Map`, `java.util.Optional`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.Mockito.verify`, `static org.mockito.Mockito.verifyNoMoreInteractions`, `static org.mockito.Mockito.when`, `org.springframework.web.socket.messaging.SessionSubscribeEvent`, `project.study.room.dto.RoomMember`, `project.study.room.websocket.RoomMessenger`)

`src/test/java/project/study/config/UserIdChannelInterceptorTest.java`:

```java
package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import project.study.room.service.RoomStateService;
import project.study.room.websocket.RoomMessenger;

class UserIdChannelInterceptorTest {

    private final RoomStateService roomState = mock(RoomStateService.class);
    private final RoomMessenger messenger = mock(RoomMessenger.class);

    @SuppressWarnings("unchecked")
    private WebSocketConfig.UserIdChannelInterceptor interceptor() {
        ObjectProvider<RoomMessenger> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messenger);
        return new WebSocketConfig.UserIdChannelInterceptor(roomState, provider);
    }

    private static Message<byte[]> subscribe(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setDestination(destination);
        if (userId != null) accessor.setUser(() -> userId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 예약자의_방_토픽_구독은_통과한다() {
        when(roomState.hasParticipant(3L, 7L)).thenReturn(true);
        Message<byte[]> message = subscribe("/topic/room/3", "7");

        assertThat(interceptor().preSend(message, mock(MessageChannel.class))).isSameAs(message);
        verifyNoInteractions(messenger);
    }

    @Test
    void 비예약자의_구독은_버리고_요청_세션에_ROOM_UNAVAILABLE을_보낸다() {
        when(roomState.hasParticipant(3L, 7L)).thenReturn(false);

        assertThat(interceptor().preSend(subscribe("/topic/room/3", "7"), mock(MessageChannel.class))).isNull();
        verify(messenger).roomUnavailable("7", "s1", 3L);
    }

    @Test
    void 개인_큐_구독은_항상_통과하고_다른_목적지는_버린다() {
        WebSocketConfig.UserIdChannelInterceptor interceptor = interceptor();
        MessageChannel channel = mock(MessageChannel.class);

        assertThat(interceptor.preSend(subscribe("/user/queue/room", "7"), channel)).isNotNull();
        assertThat(interceptor.preSend(subscribe("/topic/room/**", "7"), channel)).isNull();
        assertThat(interceptor.preSend(subscribe("/topic/room/3", null), channel)).isNull();
        verifyNoInteractions(messenger);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.room.websocket.*" --tests "project.study.room.StompEventListenerTest" --tests "project.study.config.UserIdChannelInterceptorTest" -q`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`src/main/java/project/study/room/websocket/RoomMessenger.java`:

```java
package project.study.room.websocket;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 룸 메시지 발송. 세션 스코프 발송은 같은 유저의 남은 옛 세션에 새지 않도록 요청 세션에만 배달한다. */
@Component
@RequiredArgsConstructor
public class RoomMessenger {

    private final SimpMessagingTemplate messagingTemplate;

    public void toSession(String userName, String sessionId, Object payload) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(userName, "/queue/room", payload, headers.getMessageHeaders());
    }

    public void broadcast(Long roomId, Object payload) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId, payload);
    }

    /** 구독 거부·확정 실패 — FE는 join을 다시 부르거나 종료 안내를 띄운다. 배달은 보장되지 않는다(스펙 §2.9). */
    public void roomUnavailable(String userName, String sessionId, Long roomId) {
        toSession(userName, sessionId, Map.of("type", "ROOM_UNAVAILABLE", "roomId", roomId));
    }
}
```

`StompEventListener` 전면 교체:

```java
package project.study.room.websocket;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import project.study.room.dto.RoomMember;
import project.study.room.lease.TaskIdentity;
import project.study.room.service.RoomService;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompEventListener {

    private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room/(\\d+)$");

    private final RoomService roomService;
    private final RoomMessenger messenger;
    private final SessionRegistry sessionRegistry;
    private final TaskIdentity taskIdentity;
    private final Clock clock;

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug("STOMP CONNECT 수신: sessionId={}", accessor.getSessionId());
    }

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        log.debug("STOMP CONNECTED: sessionId={}, userId={}", accessor.getSessionId(),
                principal == null ? null : principal.getName());
    }

    /**
     * 방 토픽 구독 = 자리 확정 (스펙 §2.5). 옛 세션의 뒤늦은 SUBSCRIBE가 죽은 세션을 다시 등록하지 않도록
     * 레지스트리 사전 검사 → 단조 조건부 확정 → 사후 보정 세 겹으로 막는다.
     */
    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) return;
        Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) return;
        Principal principal = accessor.getUser();
        if (principal == null) return;

        Long roomId = Long.valueOf(matcher.group(1));
        Long userId = Long.valueOf(principal.getName());
        String sessionId = accessor.getSessionId();
        if (!sessionRegistry.isOpen(sessionId)) {
            log.debug("닫힌 세션의 구독 무시: roomId={}, userId={}, sessionId={}", roomId, userId, sessionId);
            return;
        }
        Instant openedAt = sessionRegistry.openedAt(sessionId).orElseGet(clock::instant);

        List<RoomMember> members = roomService.confirmStomp(roomId, userId, sessionId, openedAt, taskIdentity.id());
        if (members.isEmpty()) {
            // 인가는 통과했는데 그 사이 자리가 회수됐거나 옛 세션 — FE가 join을 다시 부르게 알린다
            messenger.roomUnavailable(principal.getName(), sessionId, roomId);
            return;
        }
        if (!sessionRegistry.isOpen(sessionId)) {
            roomService.handleDisconnect(sessionId); // 확정 중에 닫힘(펜싱 등) — 끊김으로 되돌린다
            return;
        }
        log.debug("STOMP 확정: roomId={}, userId={}, 확정 인원={}", roomId, userId, members.size());

        messenger.toSession(principal.getName(), sessionId, Map.of("type", "SNAPSHOT", "members", members));
        RoomMember self = members.stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElse(new RoomMember(userId, null, null, null, false, "FOCUS", 0, false));
        messenger.broadcast(roomId, Map.of("type", "MEMBER_JOINED", "member", self));
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        log.debug("DISCONNECT 수신: sessionId={}, closeStatus={}", sessionId, event.getCloseStatus());
        if (sessionId == null) return;
        roomService.handleDisconnect(sessionId);
    }
}
```

`WebSocketConfig`:
- 필드에 `private final ObjectProvider<RoomMessenger> roomMessenger;` 추가(import `org.springframework.beans.factory.ObjectProvider`, `project.study.room.websocket.RoomMessenger`). `SimpMessagingTemplate`을 configurer에 직접 주입하면 브로커 구성과 순환 참조가 나므로 `ObjectProvider`로 지연 해석한다.
- `configureClientInboundChannel`:

```java
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 인바운드 핸들러가 이제 JDBC로 블로킹된다 — 기본(코어×2, 0.5vCPU에서 2개)은 부족하다 (BY-626, 스펙 §7)
        registration.taskExecutor().corePoolSize(8).maxPoolSize(8);
        // 인가 인터셉터가 CONNECT에서 프린시펄을 세팅하므로 MDC 인터셉터는 그 뒤에 둔다
        registration.interceptors(
                new UserIdChannelInterceptor(roomStateService, roomMessenger), new StompMdcChannelInterceptor());
    }
```

- `UserIdChannelInterceptor`: 생성자 `(RoomStateService roomStateService, ObjectProvider<RoomMessenger> messenger)`(`@RequiredArgsConstructor` 유지, 필드 순서대로), `allowSubscribe`의 마지막 부분:

```java
            Long roomId = Long.valueOf(matcher.group(1));
            Long userId = Long.valueOf(principal.getName());
            boolean allowed = roomStateService.hasParticipant(roomId, userId);
            if (!allowed) {
                // 프레임은 버리되 요청 세션에만 알린다 — 지금까지는 조용히 버려져 FE가 거부를 알 길이 없었다
                messenger.getObject().roomUnavailable(principal.getName(), accessor.getSessionId(), roomId);
            }
            return allowed;
```

- [ ] **Step 4: 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q`
Expected: BUILD SUCCESSFUL (신규 9건 포함).

```bash
git add src/main/java/project/study src/test/java/project/study
git commit -m "feat: 구독 거부·확정 실패 시 ROOM_UNAVAILABLE, confirm 사전/사후 세션 검사, 인바운드 executor 확대 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: 소셜 지표 쿼리 — `left_at IS NULL`은 "지금까지 진행 중"

**Files:**
- Modify: `src/main/java/project/study/studysession/repository/StudySessionMetricsRepository.java`
- Modify: `src/test/java/project/study/studysession/QualifyingSessionQueryIntegrationTest.java`

- [ ] **Step 1: 테스트 뒤집기**

`종료시각이_없는_stale_참여는_소셜로_치지_않는다`를 다음으로 교체:

```java
    @Test
    void 아직_진행_중인_참여도_소셜이다() {
        // BY-626 이후 left_at NULL은 "지금 방에 있음"이다(죽은 태스크의 잔재는 리스 스윕이 30초+30초 안에 닫는다).
        // 어제 저녁부터 밤새 방에 있던 유저가 아침 집계 때 아직 방에 있어도 어제 세션은 소셜이어야 한다.
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        long room = insertRoom(userId);
        insertParticipation(room, userId, at(2), null);

        assertThat(socialOf(userId)).isTrue();
    }
```

Run: `./gradlew test --tests "project.study.studysession.QualifyingSessionQueryIntegrationTest" -q` → 이 테스트만 FAIL.

- [ ] **Step 2: 쿼리 수정**

`findQualifyingSessions`의 EXISTS 절을 다음으로 바꾸고, 위 주석(“left_at NULL은 … 비정상 종료 구간 …”)을 “left_at NULL은 지금 진행 중인 참여라 coalesce(left_at, now())로 지금까지로 계산한다 (BY-626). joined_at NULL(확정 없는 예약)은 비교식이 NULL이라 자동 제외된다”로 갱신한다:

```sql
                        exists (
                            select 1 from room_participations rp
                            where rp.user_id = s.user_id
                              and rp.joined_at < s.ended_at
                              and s.started_at < coalesce(rp.left_at, now())
                        ) as "social"
```

- [ ] **Step 3: 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check -q` → BUILD SUCCESSFUL.

```bash
git add src/main/java/project/study/studysession src/test/java/project/study/studysession
git commit -m "fix: 소셜 지표에서 진행 중인 룸 참여(left_at NULL)를 겹침으로 인정 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: ArchUnit 패키지 규칙 · ADR-0018 · ERD · 스펙 상태

**Files:**
- Modify: `src/test/java/project/study/ArchitectureTest.java`, `docs/erd.dbml`, `docs/superpowers/specs/2026-09-08-room-state-db-design.md`
- Create: `docs/adr/0018-room-state-in-postgres.md`

- [ ] **Step 1: ArchUnit — 이름이 아니라 패키지로**

```java
    @ArchTest
    static final ArchRule controllerShouldNotAccessRepository = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..")
            .because("컨트롤러는 서비스를 거쳐야 한다 (이름이 아니라 repository 패키지 기준)")
            .allowEmptyShould(true);
```

Run: `./gradlew test --tests "project.study.ArchitectureTest" -q` → PASS.

- [ ] **Step 2: ERD 갱신**

`docs/erd.dbml`의 `Table rooms`·`Table room_participations` 블록을 V15 컬럼으로 바꾸고(각 컬럼 note는 V15 SQL 주석과 같게), `Table live_task { task_id varchar [primary key] heartbeat_at timestamptz [not null] reclaimed_at timestamptz [note: 'NOT NULL = 다른 태스크가 회수함(펜싱 신호)'] }`를 추가한다. `room_uid` 관련 ref는 `room_participations.room_id > rooms.id`로 바꾼다.

- [ ] **Step 3: ADR-0018**

`docs/adr/0018-room-state-in-postgres.md` (0017의 형식을 따른다):

```markdown
# ADR-0018: 룸 상태를 PostgreSQL 진실 원천으로

- 상태: 채택 (2026-09-09)
- 날짜: 2026-09-09
- 티켓: BY-626
- 대체: ADR-0017 (배포 시 룸 스냅샷 이어받기, 철회)

## 맥락
`RoomService`는 방·참가자 상태를 인메모리(HashMap + 단일 synchronized 락)로 관리했다. ECS 롤링 배포에서 옛
태스크가 내려가면 방이 전부 사라지고, 프론트는 STOMP 재접속만 할 뿐 join을 다시 부르지 않아 좀비 방에 갇힌다.
ADR-0017의 종료 스냅샷 방식은 땜빵이고 크래시·OOM을 덮지 못해 같은 날 철회했다. 3차 부하테스트에서 RDS는
4~9%로 병목이 아니었고 첫 병목은 Spring CPU였다.

## 결정
방·참가자 상태의 진실 원천을 PostgreSQL로 옮긴다. 상세 설계는 `docs/superpowers/specs/2026-09-08-room-state-db-design.md`.

1. 라이브 상태를 기존 이력 테이블 `rooms`·`room_participations`에 통합한다. 행을 지우지 않는 것이 이력이다.
   이벤트 4종·`RoomHistoryRecorder`·비동기 실행기·`ClosedInviteCodes`를 없앤다.
2. 전역 락은 DB 제약(부분 유니크: 동시 1룸·중복 예약·열린 방 코드 유일) + 방 행 `FOR UPDATE` + "락 아래 재조회"
   + 조건부 갱신으로 대체한다. 락 순서는 유저 advisory → 방 행(id 오름차순) → 참가자 행 → 코드 advisory.
   쓰기·락 경로는 `JdbcClient` 네이티브 SQL이고 JPA 엔티티는 스키마 검증용이다.
3. 태스크 리스(`live_task`): 등록 먼저(phase -1000), 자체 스레드·전용 커넥션 heartbeat, 살아 있는 리스가 없는
   태스크의 참가자를 끊김으로 전환, 회수는 리스 행 UPDATE로 heartbeat와 직렬화, 오판 시 펜싱(소켓 전부 닫기).
4. 배포 겹침 구간의 태스크 간 메시지 전달은 감수한다(A안). 유령 멤버는 FE의 SNAPSHOT 재대조로 보완한다.

## 대안과 기각 이유
- 종료 스냅샷 + 구독 시 복원(ADR-0017): 크래시를 못 덮고 땜빵이다.
- Redis: 태스크 간 브로드캐스트에는 필요하지만 멤버십 진실 원천으로는 과하다. 수평 확장 시 재검토(BY-492).
- 라이브 테이블 별도 + 이력 이벤트 유지: 라이브·이력 이중 구조와 비동기 기록기 충돌. 부분 유니크 인덱스로
  카디널리티 문제가 풀려 통합이 더 단순하다.

## 결과와 한계
- 배포(SIGTERM)·크래시 모두에서 방과 자리가 유지되고 재접속 시 같은 자리로 복귀한다.
- DB 장애가 곧 룸 장애가 된다. STOMP 인바운드 메시지마다 DB 왕복이 생긴다(인바운드 executor 8, HOT 유지를 위한
  fillfactor 70, 다음 부하테스트에서 통과 기준 측정).
- 겹침 구간에 갈린 멤버 사이의 브로드캐스트·시그널은 닿지 않는다. 릴레이(LISTEN/NOTIFY 또는 Redis pub/sub)는 별도 티켓.
```

- [ ] **Step 4: 스펙 상태 갱신**

스펙 헤더의 `- 상태:`를 `v3 확정 — 구현 완료(2026-09-XX), ADR-0018`로, §5의 "테스트용 JpaRepository 두 개는 유지"를 "테스트는 JdbcClient 프로브(RoomProbe)로 조회"로, §1의 `char(4)`를 `varchar(4)`로 바꾼다(계획의 "스펙 대비 조정" 표와 일치).

- [ ] **Step 5: 검증 후 커밋**

Run: `./gradlew check -q` → BUILD SUCCESSFUL.

```bash
git add src/test/java/project/study/ArchitectureTest.java docs/erd.dbml docs/adr/0018-room-state-in-postgres.md docs/superpowers/specs/2026-09-08-room-state-db-design.md
git commit -m "docs: ADR-0018 룸 상태 DB 원본화, ERD 갱신, ArchUnit 컨트롤러→저장소 규칙을 패키지 기준으로 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: 동시성 테스트 — 동시 join · 코드 락 · 동시 1룸

**Files:**
- Test: `src/test/java/project/study/room/RoomConcurrencyTest.java`

- [ ] **Step 1: 테스트 작성** (`@Transactional` 없음 — 스레드마다 실제 커밋)

```java
package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ConflictException;
import project.study.room.support.RoomProbe;
import project.study.room.support.RoomTestBase;

/** 전역 락 없이도 DB 제약·방 행 락·코드 락이 불변식을 지키는지 — 실제 스레드로 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomConcurrencyTest extends RoomTestBase {

    @Autowired
    private TransactionTemplate tx;

    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 스무_명이_동시에_들어와도_정확히_여섯_명만_자리를_얻는다() throws Exception {
        String code = createRoom();
        long roomId = rooms.findLatestByCode(code).orElseThrow().id();
        List<Callable<Boolean>> joins = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long userId = user();
            joins.add(() -> {
                try {
                    join(userId, code);
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            });
        }

        List<Boolean> results = runAll(joins);

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(6);
        assertThat(probe.countLive(roomId)).isEqualTo(6);
    }

    @Test
    void 같은_유저가_두_방에_동시에_들어가도_라이브_자리는_하나다() throws Exception {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();

        runAll(List.<Callable<Boolean>>of(() -> join(userId, codeA) != null, () -> join(userId, codeB) != null));

        List<RoomProbe.Participation> rows = probe.participationsOfUser(userId);
        assertThat(rows.stream().filter(r -> r.leftAt() == null)).hasSize(1);
    }

    @Test
    void 방이_닫히는_순간에도_같은_코드는_묘비_기간_안에_재발급되지_않는다() throws Exception {
        for (int round = 0; round < 20; round++) {
            String code = createRoom();
            long userId = user();
            long roomId = join(userId, code).response().roomId();

            List<Boolean> results = runAll(List.<Callable<Boolean>>of(
                    () -> roomService.leave(roomId, userId).removed(),
                    () -> tx.execute(s -> rooms.insertIfCodeFree(
                            code, owner, Instant.now(), Instant.now().minusSeconds(600)))
                            .isPresent()));

            assertThat(results.getFirst()).as("퇴장은 성공").isTrue();
            assertThat(results.get(1)).as("닫힘과 겹친 발급은 코드 락 덕에 묘비를 보고 실패").isFalse();
            assertThat(rooms.isOpen(roomId)).isFalse();
        }
    }
}
```

세 번째 테스트의 발급 시도는 방이 아직 열려 있으면 ON CONFLICT(열린 코드 유일)로, 닫힌 직후면 NOT EXISTS(묘비)로 막힌다 — 어느 순서로 겹쳐도 발급되면 안 된다.

- [ ] **Step 2: 실행 후 커밋**

Run: `./gradlew test --tests "project.study.room.RoomConcurrencyTest" -q && ./gradlew check -q` → PASS.

```bash
git add src/test/java/project/study/room/RoomConcurrencyTest.java
git commit -m "test: 동시 join 정원·동시 1룸·닫힘 중 코드 재발급 금지 동시성 테스트 (BY-626)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: 최종 게이트 — check · 크로스 리뷰 · 퀴즈 · PR · 티켓

- [ ] **Step 1: 전체 검증** — `./gradlew check` 통과, `git status` 깨끗(`.context/`만 미추적).
- [ ] **Step 2: Codex 리뷰** — `/codex review`(P1 발견 시 FAIL 게이트) 후 `/codex challenge security`(STOMP 인가·advisory lock·펜싱은 보안 민감 변경). 지적은 수정·재검토 후 커밋.
- [ ] **Step 3: 퀴즈 게이트** — 커밋 전 규칙대로 구현 흐름 퀴즈 5개(락 순서, 락 아래 재조회, 리스 회수와 heartbeat 직렬화, 펜싱 조건, 묘비 판정)를 사용자에게 내고 통과 확인.
- [ ] **Step 4: PR** — `gh auth switch --user sangjaekwon` 확인 후 dev 대상 PR. 제목 `[feat] BY-626 무중단 배포·크래시에도 룸 유지 — 룸 상태 DB 원본화`. 본문: 스펙·ADR 링크, 파괴적 마이그레이션(V15 DROP) 명시, FE 계약 변경(focusSec·disconnected·ROOM_UNAVAILABLE), 겹침 구간 한계(A안)와 FE 후속, 부하 재측정 항목. 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- [ ] **Step 5: 머지 후** — BY-626 상태를 완료(전이 ID 41)로 전환하고, 머지 뒤 첫 배포는 옛 태스크가 인메모리라 방이 한 번 사라짐을 티켓 댓글로 남긴다. 다음 부하테스트 티켓에 §7 통과 기준을 옮긴다.
