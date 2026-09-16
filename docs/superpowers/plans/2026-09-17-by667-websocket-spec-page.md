# WebSocket 명세 페이지 (BY-667) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE가 룸 STOMP 계약을 구현·디버깅할 때 코드 대신 보는 한 페이지를 dev 서버 `/docs/websocket.html`로 제공하고, 코드의 메시지 type·목적지가 문서에서 빠지면 `check`가 실패하게 한다.

**Architecture:** 단일 HTML(Mermaid CDN)을 `static/` 밖 클래스패스에 두고, `app.docs.enabled=true`일 때만 `WebMvcConfigurer` 리소스 핸들러가 `/docs/**`로 서빙한다. 서버 메시지 type을 enum으로 모아 리터럴을 교체하고, Spring 없는 단위 테스트가 HTML과 enum·`@MessageMapping`·레코드 필드를 대조한다.

**Tech Stack:** Spring Boot 4.1(WebMvc, Security), JUnit 5 + AssertJ, `MockMvcTester`, Testcontainers, Mermaid 11(jsdelivr).

**Spec:** `docs/superpowers/specs/2026-09-17-by670-by667-dev-cd-and-ws-spec-page-design.md` Part B

## Global Constraints

- 브랜치 `feat/BY-667-websocket-명세-페이지` — **BY-670(PR 1) 머지 후** `dev`에서 분기
- 페이지 URL `/docs/websocket.html`, 파일 `src/main/resources/docs/websocket.html`
- 프로퍼티 `app.docs.enabled` — `havingValue="true"`만 인정, 값 없으면 꺼짐. dev·local example yaml에만 `true`, prod·테스트 yaml에는 넣지 않는다
- `@Profile` 금지(ArchUnit), 생성자 주입만, DTO record, Jackson 3(`tools.jackson`)
- 와이어 포맷 불변: `"type"` 값은 enum `name()` 문자열 그대로. 기존 테스트(`RoomStompHandlerTest`, `StompEventListenerTest`, `RoomMessengerTest`)는 문자열 Map으로 검증하므로 수정 없이 통과해야 한다
- 코드 변경 후 커밋 전 `./gradlew spotlessApply` → `./gradlew check`
- `CLAUDE.md`는 `AGENTS.md`의 심링크 — 편집은 `AGENTS.md`
- 커밋 컨벤션 `<type>: <설명>`; 리팩토링과 기능을 한 커밋에 섞지 않는다

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `src/main/java/project/study/room/websocket/RoomMessageType.java` | 서버→클라 메시지 type 8종의 단일 정의 |
| `src/main/resources/docs/websocket.html` | FE가 보는 계약 문서(내용 전부) |
| `src/main/java/project/study/config/DocsResourceConfig.java` | `/docs/**` → `classpath:/docs/` 매핑, 스위치로만 켜짐 |
| `src/test/java/project/study/room/websocket/WebSocketDocsContractTest.java` | HTML ↔ 코드 대조 |
| `src/test/java/project/study/config/DocsPageEnabledApiTest.java` / `DocsPageDisabledApiTest.java` | 켜짐 200·꺼짐 404 |
| 수정: `SecurityConfig`, `application-dev.yaml`, `application-local.yaml.example`, `RoomController`(@Tag), `AGENTS.md`, `FeatureSwitchTest` | 노출·안내·규칙 |

---

### Task 1: `RoomMessageType` enum으로 type 리터럴 통합 (동작 불변)

**Files:**
- Create: `src/main/java/project/study/room/websocket/RoomMessageType.java`
- Modify: `src/main/java/project/study/room/websocket/RoomMessenger.java:30`
- Modify: `src/main/java/project/study/room/websocket/RoomStompHandler.java:50,77,114,118,122` (+ `SIGNAL_KINDS`·`FOCUS_STATES` 가시성)
- Modify: `src/main/java/project/study/room/websocket/StompEventListener.java:93,98`
- Modify: `src/main/java/project/study/room/controller/RoomController.java:124,140`
- Modify: `src/main/java/project/study/room/scheduler/RoomCleanupScheduler.java:51`

**Interfaces:**
- Produces: `public enum RoomMessageType { SNAPSHOT, MEMBER_JOINED, MEMBER_LEFT, CAMERA_CHANGED, FOCUS_CHANGED, STUDY_TIME, SIGNAL, ROOM_UNAVAILABLE }` — Task 2의 대조 테스트가 `values()`를 순회한다. `RoomStompHandler.SIGNAL_KINDS`·`FOCUS_STATES`는 `static final Set<String>`(패키지 가시성)

- [ ] **Step 1: 기존 룸 테스트가 초록인지 먼저 확인** (리팩토링의 안전망)

Run: `./gradlew test --tests "project.study.room.*" -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: enum 작성**

```java
package project.study.room.websocket;

/**
 * 서버 → 클라이언트 STOMP 메시지의 {@code type} 값. 와이어 포맷은 {@link #name()} 문자열 그대로다.
 * 여기에 값을 더하면 docs/websocket.html에도 실어야 한다 — WebSocketDocsContractTest가 대조한다 (BY-667).
 */
public enum RoomMessageType {
    SNAPSHOT,
    MEMBER_JOINED,
    MEMBER_LEFT,
    CAMERA_CHANGED,
    FOCUS_CHANGED,
    STUDY_TIME,
    SIGNAL,
    ROOM_UNAVAILABLE
}
```

- [ ] **Step 3: 리터럴 교체 — 아래 11곳을 정확히 바꾼다** (`Map.of("type", "X", …)` → `Map.of("type", RoomMessageType.X.name(), …)`)

`RoomMessenger.java`
```java
        toSession(userName, sessionId, Map.of("type", RoomMessageType.ROOM_UNAVAILABLE.name(), "roomId", roomId));
```

`RoomStompHandler.java` — `SIGNAL_KINDS`·`FOCUS_STATES`의 `private`를 지우고(패키지 가시성, 대조 테스트가 읽는다) 다섯 곳:
```java
    // 대조 테스트(WebSocketDocsContractTest)가 문서와 비교하므로 패키지 가시성으로 둔다
    static final Set<String> SIGNAL_KINDS = Set.of("OFFER", "ANSWER", "CANDIDATE");
    static final Set<String> FOCUS_STATES = Set.of("FOCUS", "DISTRACTED");
```
```java
        roomMessenger.toSession(
                principal.getName(),
                accessor.getSessionId(),
                Map.of("type", RoomMessageType.SNAPSHOT.name(), "members", members));
```
```java
        messagingTemplate.convertAndSendToUser(payload.toUserId().toString(), "/queue/room", (Object) Map.of(
                "type", RoomMessageType.SIGNAL.name(),
                "fromUserId", fromUserId,
                "kind", payload.kind(),
                "payload", payload.payload()));
```
```java
        if (change.cameraOn() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.CAMERA_CHANGED.name(), "userId", userId, "cameraOn", change.cameraOn()));
        }
        if (change.focusState() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.FOCUS_CHANGED.name(), "userId", userId, "focusState", change.focusState()));
        }
        if (change.focusSec() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.STUDY_TIME.name(), "userId", userId, "focusSec", change.focusSec()));
        }
```

`StompEventListener.java`
```java
        messenger.toSession(
                principal.getName(), sessionId, Map.of("type", RoomMessageType.SNAPSHOT.name(), "members", members));
        ...
        messenger.broadcast(roomId, Map.of("type", RoomMessageType.MEMBER_JOINED.name(), "member", self));
```

`RoomController.java` (import `project.study.room.websocket.RoomMessageType;` 추가)
```java
            messagingTemplate.convertAndSend("/topic/room/" + al.roomId(), (Object)
                    Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", al.userId()));
        ...
            messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                    Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", userId));
```

`RoomCleanupScheduler.java` (import 추가)
```java
            messagingTemplate.convertAndSend("/topic/room/" + autoLeave.roomId(), (Object)
                    Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", autoLeave.userId()));
```

- [ ] **Step 4: 리터럴이 남지 않았는지 확인**

Run: `grep -rn '"type", "' src/main/java/project/study/room | wc -l`
Expected: `0`

- [ ] **Step 5: 포맷·룸 테스트**

Run: `./gradlew spotlessApply -q && ./gradlew test --tests "project.study.room.*" -q`
Expected: BUILD SUCCESSFUL (기존 테스트는 문자열 Map으로 비교하므로 수정 없이 통과)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/room
git commit -m "refactor: 룸 STOMP 메시지 type을 RoomMessageType enum으로 통합 (BY-667)"
```

---

### Task 2: 명세 HTML + 대조 테스트

**Files:**
- Create: `src/main/resources/docs/websocket.html`
- Test: `src/test/java/project/study/room/websocket/WebSocketDocsContractTest.java`

**Interfaces:**
- Consumes: `RoomMessageType`, `RoomStompHandler.SIGNAL_KINDS`/`FOCUS_STATES`(Task 1), `RoomMember` 레코드, `@MessageMapping` 값
- Produces: 클래스패스 `docs/websocket.html` — Task 3의 API 테스트가 `<title>룸 WebSocket 명세</title>`를 확인한다

- [ ] **Step 1: 대조 테스트 작성 (먼저)**

```java
package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.handler.annotation.MessageMapping;
import project.study.room.dto.RoomMember;

/**
 * docs/websocket.html이 코드의 계약을 빠짐없이 싣고 있는지 대조한다 (BY-667). 메시지 type·발행 목적지·구독 목적지·
 * 시그널 kind·focusState·RoomMember 필드가 기준이다. 필드 타입과 수치까지는 잡지 못한다 — 그건 리뷰 몫이다.
 */
class WebSocketDocsContractTest {

    private static final String DOC = "docs/websocket.html";
    // 구독 목적지는 코드에서 정규식·리터럴(WebSocketConfig.UserIdChannelInterceptor)이라 여기 상수로 둔다
    private static final List<String> SUBSCRIBE_DESTINATIONS = List.of("/topic/room/{roomId}", "/user/queue/room");

    private static String html;

    @BeforeAll
    static void loadHtml() throws IOException {
        try (InputStream in = WebSocketDocsContractTest.class.getClassLoader().getResourceAsStream(DOC)) {
            assertThat(in).as("%s 가 클래스패스에 있어야 한다", DOC).isNotNull();
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 서버_메시지_type이_모두_문서에_있다() {
        for (RoomMessageType type : RoomMessageType.values()) {
            String token = "\"type\": \"" + type.name() + "\"";
            assertThat(html).as(missing(token)).contains(token);
        }
    }

    @Test
    void 클라이언트_발행_목적지가_모두_문서에_있다() {
        List<String> mappings = Arrays.stream(RoomStompHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(MessageMapping.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(MessageMapping.class).value()))
                .toList();
        assertThat(mappings).as("@MessageMapping이 하나도 없다").isNotEmpty();
        for (String mapping : mappings) {
            String destination = "/app" + mapping;
            assertThat(html).as(missing(destination)).contains(destination);
        }
    }

    @Test
    void 구독_목적지가_모두_문서에_있다() {
        for (String destination : SUBSCRIBE_DESTINATIONS) {
            assertThat(html).as(missing(destination)).contains(destination);
        }
    }

    @Test
    void 시그널_kind와_focusState_값이_모두_문서에_있다() {
        for (String kind : RoomStompHandler.SIGNAL_KINDS) {
            assertThat(html).as(missing(kind)).contains(kind);
        }
        for (String state : RoomStompHandler.FOCUS_STATES) {
            assertThat(html).as(missing(state)).contains(state);
        }
    }

    @Test
    void RoomMember_필드가_모두_문서에_있다() {
        for (RecordComponent component : RoomMember.class.getRecordComponents()) {
            String token = "\"" + component.getName() + "\"";
            assertThat(html).as(missing(token)).contains(token);
        }
    }

    private static String missing(String token) {
        return "docs/websocket.html에 %s 가 없다 — 계약을 바꿨으면 문서와 변경 이력을 같은 PR에서 갱신한다".formatted(token);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.room.websocket.WebSocketDocsContractTest" -q`
Expected: FAIL — `docs/websocket.html 가 클래스패스에 있어야 한다`

- [ ] **Step 3: HTML 작성** — 아래 내용 전체를 `src/main/resources/docs/websocket.html`로 저장한다. 값은 코드 기준(`WebSocketConfig`, `RoomStompHandler`, `StompEventListener`, `RoomService`, `RoomCleanupService`, `RoomController`).

````html
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>룸 WebSocket 명세</title>
<style>
  :root { --bg:#ffffff; --fg:#1b1f24; --muted:#57606a; --line:#d0d7de; --code:#f6f8fa; --accent:#0969da; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0d1117; --fg:#e6edf3; --muted:#8b949e; --line:#30363d; --code:#161b22; --accent:#58a6ff; }
  }
  body { margin:0; background:var(--bg); color:var(--fg);
         font:16px/1.65 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; }
  main { max-width:960px; margin:0 auto; padding:24px 16px 96px; }
  h1 { font-size:1.8rem; margin-bottom:.2em; }
  h2 { border-bottom:1px solid var(--line); padding-bottom:.3em; margin-top:2.4em; }
  h3 { margin-top:1.6em; }
  .meta { color:var(--muted); margin-top:0; }
  nav ol { columns:2; padding-left:1.4em; } @media (max-width:640px) { nav ol { columns:1; } }
  table { border-collapse:collapse; width:100%; margin:1em 0; font-size:.95rem; }
  th, td { border:1px solid var(--line); padding:6px 10px; vertical-align:top; text-align:left; }
  th { background:var(--code); }
  code { background:var(--code); padding:.1em .35em; border-radius:4px; font-size:.92em; }
  pre { background:var(--code); padding:12px 14px; border-radius:6px; overflow-x:auto; line-height:1.45; }
  pre code { background:none; padding:0; }
  .note { border-left:4px solid var(--accent); padding:8px 14px; background:var(--code); margin:1em 0; }
  .mermaid { background:var(--code); border-radius:6px; padding:8px; margin:1em 0; overflow-x:auto; }
  a { color:var(--accent); }
</style>
</head>
<body>
<main>

<h1>룸 WebSocket 명세</h1>
<p class="meta">실시간 공부방(룸)의 STOMP 계약. 기준: 2026-09-17 (BY-667). REST API는 <a href="/swagger-ui.html">Swagger</a>, 이 문서는 소켓 계약만 다룬다.
서버 코드가 곧 진실이며, 메시지 type·목적지가 이 문서에서 빠지면 서버 빌드가 실패한다.</p>

<nav><ol>
  <li><a href="#connect">연결과 인증</a></li>
  <li><a href="#join">입장 흐름</a></li>
  <li><a href="#destinations">목적지</a></li>
  <li><a href="#inbound">클라이언트 → 서버</a></li>
  <li><a href="#outbound">서버 → 클라이언트</a></li>
  <li><a href="#signaling">WebRTC 시그널링</a></li>
  <li><a href="#reconnect">끊김·재접속·유예</a></li>
  <li><a href="#leave">퇴장과 방 소멸</a></li>
  <li><a href="#fe">FE가 처리해야 할 것</a></li>
  <li><a href="#limits">수치 한눈에</a></li>
  <li><a href="#changelog">변경 이력</a></li>
</ol></nav>

<h2 id="connect">1. 연결과 인증</h2>
<table>
  <tr><th>항목</th><th>값</th></tr>
  <tr><td>엔드포인트</td><td><code>wss://&lt;API 호스트&gt;/ws</code> — 순수 WebSocket. SockJS는 쓰지 않는다 (<code>@stomp/stompjs</code>의 <code>brokerURL</code>)</td></tr>
  <tr><td>인증</td><td>CONNECT 프레임 헤더 <code>Authorization: Bearer &lt;access 토큰&gt;</code>. 핸드셰이크 URL 쿼리에는 토큰을 싣지 않는다(ALB 액세스 로그에 남는다)</td></tr>
  <tr><td>인증 실패</td><td>헤더가 없거나 토큰이 무효하면 서버가 ERROR 프레임(<code>인증이 필요합니다</code> / <code>유효하지 않은 토큰입니다</code>)을 보내고 소켓을 닫는다</td></tr>
  <tr><td>토큰 만료</td><td>검증은 CONNECT 시점 한 번. 접속 중 access가 만료돼도 세션은 유지된다. 재접속 전에 갱신해서 붙이는 것은 클라이언트 몫</td></tr>
  <tr><td>heartbeat</td><td>서버 제안 <code>10000,10000</code>(ms). 클라이언트도 <code>heartbeatIncoming: 10000, heartbeatOutgoing: 10000</code>으로 맞춘다. 0이면 비활성</td></tr>
</table>
<pre><code>const client = new Client({
  brokerURL: "wss://&lt;API 호스트&gt;/ws",
  connectHeaders: { Authorization: `Bearer ${accessToken}` },
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
});</code></pre>

<h2 id="join">2. 입장 흐름</h2>
<p>입장은 REST와 STOMP 두 단계다. <code>POST /api/rooms/join</code>은 <strong>자리 예약(30초)</strong>이고, <code>/topic/room/{roomId}</code> 구독이 <strong>입장 확정</strong>이다. 30초 안에 구독하지 않으면 예약은 소멸한다.</p>
<div class="mermaid">
sequenceDiagram
    autonumber
    participant FE
    participant API as REST
    participant WS as STOMP
    participant Room as 방 토픽
    FE->>API: POST /api/rooms/join {inviteCode}
    API-->>FE: 200 {roomId, graceRejoin, cameraOn, iceServers, iceTtlSeconds} (자리 예약 30초)
    FE->>WS: CONNECT (Authorization: Bearer)
    WS-->>FE: CONNECTED
    FE->>WS: SUBSCRIBE /user/queue/room
    FE->>WS: SUBSCRIBE /topic/room/{roomId}  (= 입장 확정)
    WS-->>FE: /user/queue/room ← SNAPSHOT {members[]}
    WS-->>Room: /topic/room/{roomId} ← MEMBER_JOINED {member}
</div>
<div class="note">
  <strong>순서가 중요하다.</strong> <code>/user/queue/room</code>을 <code>/topic/room/{roomId}</code>보다 <em>먼저</em> 구독한다.
  SNAPSHOT과 ROOM_UNAVAILABLE은 큐로 오는데, 큐 구독 전에 발사되면 사라진다.
</div>
<ul>
  <li><code>graceRejoin: true</code>는 같은 방에 내 자리가 아직 살아 있어(끊김 유예 중 등) 재입장한 경우다. 이때 <code>cameraOn</code>은 이전 값이다. 새 입장이면 <code>false</code>·<code>null</code>.</li>
  <li>이미 다른 방에 있으면 그 방에서 자동 퇴장된다(그 방 토픽에 MEMBER_LEFT). 동시 1룸.</li>
  <li>join 실패는 REST 응답으로 온다: 400(코드 형식), 404(<code>INVITE_CODE_NOT_FOUND</code> / <code>ROOM_CLOSED</code> / <code>USER_NOT_FOUND</code> — 본문 <code>code</code>로 구분), 409(정원 6명 초과).</li>
  <li>방 생성은 <code>POST /api/rooms</code> → 201 <code>{roomId, inviteCode, emptyTtlSeconds}</code>. 생성자도 join으로만 입장한다.</li>
</ul>

<h2 id="destinations">3. 목적지</h2>
<p>구독과 발행 모두 <strong>허용 목록만</strong> 통과한다. 목록 밖 프레임은 조용히 버려진다(ERROR 프레임 없음).</p>
<table>
  <tr><th>방향</th><th>목적지</th><th>조건</th></tr>
  <tr><td>구독</td><td><code>/user/queue/room</code></td><td>누구나. 내 세션에만 오는 메시지(SNAPSHOT, SIGNAL, ROOM_UNAVAILABLE)</td></tr>
  <tr><td>구독</td><td><code>/topic/room/{roomId}</code></td><td>그 방에 자리(예약 또는 확정)가 있는 유저만. 아니면 프레임을 버리고 요청 세션에 ROOM_UNAVAILABLE을 보낸다. 정확히 일치해야 하며 와일드카드 구독은 거부</td></tr>
  <tr><td>발행</td><td><code>/app/room/{roomId}/state</code></td><td rowspan="3">인증된 세션만. <code>/topic</code>·<code>/queue</code>로 직접 SEND하면 버려진다(브로커에 위조 이벤트를 싣는 우회 차단)</td></tr>
  <tr><td>발행</td><td><code>/app/room/{roomId}/signal</code></td></tr>
  <tr><td>발행</td><td><code>/app/room/{roomId}/snapshot</code></td></tr>
</table>

<h2 id="inbound">4. 클라이언트 → 서버</h2>
<p>세 메시지 모두 <strong>발신자가 그 방의 확정 멤버이고, 지금 세션이 그 유저의 최신 세션</strong>일 때만 처리된다. 아니면 응답 없이 무시된다(옛 탭·옛 기기의 세션은 자동으로 무력화).</p>

<h3>4.1 <code>/app/room/{roomId}/state</code> — 내 상태 보고</h3>
<pre><code>{ "cameraOn": true, "focusState": "FOCUS", "focusSec": 1520 }</code></pre>
<table>
  <tr><th>필드</th><th>타입</th><th>의미</th></tr>
  <tr><td><code>cameraOn</code></td><td>boolean, 선택</td><td>카메라 켜짐 → 방에 CAMERA_CHANGED</td></tr>
  <tr><td><code>focusState</code></td><td><code>"FOCUS"</code> | <code>"DISTRACTED"</code>, 선택</td><td>Vision AI 판정 → 방에 FOCUS_CHANGED</td></tr>
  <tr><td><code>focusSec</code></td><td>int ≥ 0, 선택</td><td>순공 타이머 누적 초(주기 보고) → 방에 STUDY_TIME</td></tr>
</table>
<ul>
  <li>필드는 모두 선택이다. 보낸 필드만 반영되고, <strong>반영된 필드마다 이벤트가 하나씩</strong> 나간다(셋 다 보내면 이벤트 3개).</li>
  <li>무효한 값(모르는 <code>focusState</code>, 음수 <code>focusSec</code>)은 그 필드만 무시하고 나머지는 반영한다.</li>
  <li>옛 이름 <code>studySeconds</code>는 받지 않는다.</li>
</ul>

<h3>4.2 <code>/app/room/{roomId}/signal</code> — WebRTC 시그널 중계</h3>
<pre><code>{ "toUserId": 34, "kind": "OFFER", "payload": { "type": "offer", "sdp": "v=0..." } }</code></pre>
<table>
  <tr><th>필드</th><th>타입</th><th>의미</th></tr>
  <tr><td><code>toUserId</code></td><td>long, 필수</td><td>같은 방의 확정 멤버여야 한다</td></tr>
  <tr><td><code>kind</code></td><td><code>"OFFER"</code> | <code>"ANSWER"</code> | <code>"CANDIDATE"</code>, 필수</td><td>그 외 값은 무시</td></tr>
  <tr><td><code>payload</code></td><td>any, 필수</td><td>서버는 해석·저장하지 않고 그대로 전달한다(SDP, ICE candidate 등)</td></tr>
</table>
<p>대상 유저의 <code>/user/queue/room</code>으로 SIGNAL이 간다(§5). 자기 자신에게도 보낼 수 있으나 의미는 없다.</p>

<h3>4.3 <code>/app/room/{roomId}/snapshot</code> — SNAPSHOT 재요청</h3>
<p>본문 없음(있어도 무시). 요청한 세션에만 SNAPSHOT을 다시 보낸다. 방 상태는 바뀌지 않는다. 확정 멤버가 아니거나 옛 세션이면 아무 응답이 없다 — 재시도가 조용히 소진되게 설계돼 있다.</p>

<h2 id="outbound">5. 서버 → 클라이언트</h2>
<p>모든 메시지는 JSON이고 <code>type</code>으로 구분한다. "방 토픽"은 <code>/topic/room/{roomId}</code>, "내 큐"는 <code>/user/queue/room</code>.</p>
<table>
  <tr><th>type</th><th>어디로</th><th>언제</th><th>필드</th></tr>
  <tr><td><code>SNAPSHOT</code></td><td>내 큐</td><td>입장 확정 직후, 또는 재요청에 응답</td><td><code>members[]</code> — 나를 포함한 전체 멤버</td></tr>
  <tr><td><code>MEMBER_JOINED</code></td><td>방 토픽</td><td>누군가 입장 확정(재입장 포함)</td><td><code>member</code></td></tr>
  <tr><td><code>MEMBER_LEFT</code></td><td>방 토픽</td><td>명시적 퇴장, 유예 만료 자동 퇴장, 다른 방 입장으로 자동 퇴장</td><td><code>userId</code></td></tr>
  <tr><td><code>CAMERA_CHANGED</code></td><td>방 토픽</td><td>누군가의 state 보고에 <code>cameraOn</code>이 있을 때</td><td><code>userId</code>, <code>cameraOn</code></td></tr>
  <tr><td><code>FOCUS_CHANGED</code></td><td>방 토픽</td><td>… <code>focusState</code>가 있을 때</td><td><code>userId</code>, <code>focusState</code></td></tr>
  <tr><td><code>STUDY_TIME</code></td><td>방 토픽</td><td>… <code>focusSec</code>이 있을 때</td><td><code>userId</code>, <code>focusSec</code></td></tr>
  <tr><td><code>SIGNAL</code></td><td>내 큐</td><td>누군가 나에게 signal을 보냈을 때</td><td><code>fromUserId</code>, <code>kind</code>, <code>payload</code></td></tr>
  <tr><td><code>ROOM_UNAVAILABLE</code></td><td>내 큐</td><td>토픽 구독이 거부됐거나 확정에 실패했을 때(자리 회수·옛 세션)</td><td><code>roomId</code></td></tr>
</table>

<h3>예시</h3>
<pre><code>{ "type": "SNAPSHOT", "members": [
  { "userId": 12, "nickname": "상재", "goal": "정보처리기사", "category": "자격증",
    "cameraOn": true, "focusState": "FOCUS", "focusSec": 1520, "disconnected": false },
  { "userId": 34, "nickname": "원일", "goal": "졸업 프로젝트", "category": "학업",
    "cameraOn": false, "focusState": "DISTRACTED", "focusSec": 300, "disconnected": true }
] }
{ "type": "MEMBER_JOINED", "member": { "userId": 56, "nickname": "선규", "goal": null, "category": null,
    "cameraOn": false, "focusState": "FOCUS", "focusSec": 0, "disconnected": false } }
{ "type": "MEMBER_LEFT", "userId": 34 }
{ "type": "CAMERA_CHANGED", "userId": 12, "cameraOn": false }
{ "type": "FOCUS_CHANGED", "userId": 12, "focusState": "DISTRACTED" }
{ "type": "STUDY_TIME", "userId": 12, "focusSec": 1580 }
{ "type": "SIGNAL", "fromUserId": 12, "kind": "CANDIDATE", "payload": { "candidate": "candidate:...", "sdpMid": "0" } }
{ "type": "ROOM_UNAVAILABLE", "roomId": 42 }</code></pre>

<h3>멤버 객체</h3>
<table>
  <tr><th>필드</th><th>타입</th><th>의미</th></tr>
  <tr><td><code>userId</code></td><td>long</td><td></td></tr>
  <tr><td><code>nickname</code></td><td>string</td><td>입장 시점 프로필. 이후 프로필을 바꿔도 방 안에서는 갱신되지 않는다</td></tr>
  <tr><td><code>goal</code>, <code>category</code></td><td>string | null</td><td>공부 목표·카테고리</td></tr>
  <tr><td><code>cameraOn</code></td><td>boolean</td><td>마지막 state 보고 값. 입장 직후 <code>false</code></td></tr>
  <tr><td><code>focusState</code></td><td><code>"FOCUS"</code> | <code>"DISTRACTED"</code></td><td>입장 직후 <code>"FOCUS"</code></td></tr>
  <tr><td><code>focusSec</code></td><td>int</td><td>마지막 보고된 순공 초. 입장 직후 0</td></tr>
  <tr><td><code>disconnected</code></td><td>boolean</td><td>소켓이 끊겨 30초 유예 중. "재접속 중"으로 표시하고 재대조 시점을 잡는 데 쓴다</td></tr>
</table>
<div class="note">
  <strong>MEMBER_JOINED는 재입장에서도 다시 온다.</strong> 이미 목록에 있는 <code>userId</code>면 덮어쓴다(upsert). 끊김 자체는 이벤트로 오지 않는다 — §7.
</div>

<h2 id="signaling">6. WebRTC 시그널링</h2>
<p>서버는 시그널을 <strong>중계만</strong> 한다. 누가 OFFER를 내는지(예: userId가 작은 쪽), 재협상 정책은 FE끼리의 약속이다. ICE 서버 목록과 자격은 join 응답의 <code>iceServers</code>·<code>iceTtlSeconds</code>로 받고, TTL이 지나면 join을 다시 호출해 새 자격을 받는다.</p>
<div class="mermaid">
sequenceDiagram
    autonumber
    participant A as FE(A)
    participant S as 서버
    participant B as FE(B)
    Note over A,B: 둘 다 입장 확정(SNAPSHOT/MEMBER_JOINED로 서로를 안다)
    A->>S: /app/room/{roomId}/signal {toUserId:B, kind:"OFFER", payload:sdp}
    S-->>B: /user/queue/room ← SIGNAL {fromUserId:A, kind:"OFFER", payload:sdp}
    B->>S: /app/room/{roomId}/signal {toUserId:A, kind:"ANSWER", payload:sdp}
    S-->>A: /user/queue/room ← SIGNAL {fromUserId:B, kind:"ANSWER", payload:sdp}
    par ICE
        A->>S: signal {toUserId:B, kind:"CANDIDATE", payload:candidate}
        S-->>B: SIGNAL {fromUserId:A, kind:"CANDIDATE"}
    and
        B->>S: signal {toUserId:A, kind:"CANDIDATE", payload:candidate}
        S-->>A: SIGNAL {fromUserId:B, kind:"CANDIDATE"}
    end
</div>
<ul>
  <li>수신자가 확정 멤버가 아니거나(예약만 한 상태, 이미 퇴장) 발신 세션이 최신이 아니면 조용히 버려진다. 상대가 응답하지 않으면 SNAPSHOT을 재요청해 멤버 여부를 확인한다(§9).</li>
  <li>SIGNAL은 대상 <em>유저</em>의 큐로 간다. 같은 유저가 두 세션을 열었다면 둘 다 받을 수 있으나, 최신 세션만 signal을 보낼 수 있다.</li>
</ul>

<h2 id="reconnect">7. 끊김·재접속·유예</h2>
<p>소켓이 끊기면(앱 백그라운드, 네트워크, 서버 배포) 자리는 <strong>30초 동안 유지</strong>된다. 이때 방에 이벤트는 나가지 않고, SNAPSHOT의 <code>disconnected: true</code>로만 드러난다. 30초 안에 돌아오면 자리가 그대로이고, 넘기면 자동 퇴장(MEMBER_LEFT)이다.</p>
<div class="mermaid">
sequenceDiagram
    autonumber
    participant FE
    participant S as 서버
    participant Room as 방 토픽
    FE--xS: 소켓 끊김 (앱 백그라운드·네트워크·배포)
    Note over S: 자리 유지 30초, members[].disconnected = true
    alt 30초 안에 재접속
        FE->>S: POST /api/rooms/join {inviteCode}
        S-->>FE: 200 {graceRejoin: true, cameraOn: 이전 값, iceServers}
        FE->>S: CONNECT → SUBSCRIBE /user/queue/room → SUBSCRIBE /topic/room/{roomId}
        S-->>FE: SNAPSHOT
        S-->>Room: MEMBER_JOINED {member} (다른 멤버는 upsert)
        Note over FE: WebRTC 피어 연결을 다시 맺는다 (§6)
    else 30초 초과
        S-->>Room: MEMBER_LEFT {userId}
        Note over FE: 이후 join은 새 입장(graceRejoin: false). 방이 이미 닫혔으면 404 ROOM_CLOSED
    end
</div>
<ul>
  <li>재접속 절차는 최초 입장과 같다: <strong>join → CONNECT → 큐 구독 → 토픽 구독</strong>. join은 유예 중이면 자리를 새로 잡지 않고 <code>graceRejoin: true</code>로 답한다.</li>
  <li>다른 기기·탭에서 같은 계정으로 들어오면 새 세션이 최신이 되고, 옛 세션의 발행은 모두 무시된다.</li>
  <li>서버 배포로 소켓이 닫혀도 같은 절차다. 방 상태는 DB에 있어 배포를 넘겨 유지된다.</li>
  <li>다른 멤버 입장에서는: 상대의 피어 연결이 끊긴 뒤 SNAPSHOT을 재요청하면 <code>disconnected</code>를 볼 수 있고, MEMBER_LEFT가 오면 피어를 정리한다.</li>
</ul>

<h2 id="leave">8. 퇴장과 방 소멸</h2>
<div class="mermaid">
sequenceDiagram
    autonumber
    participant FE
    participant API as REST
    participant Room as 방 토픽
    FE->>API: POST /api/rooms/{roomId}/leave
    API-->>FE: 204
    API-->>Room: MEMBER_LEFT {userId} (남은 멤버가 있을 때만)
    Note over API: 마지막 1명이 나가면 방·초대코드 소멸. 그 코드로 join하면 10분간 404 ROOM_CLOSED, 이후 INVITE_CODE_NOT_FOUND
    FE->>FE: 소켓 DISCONNECT
</div>
<ul>
  <li>퇴장 API를 못 부르고 앱이 종료돼도 소켓 끊김 30초 후 자동 퇴장된다.</li>
  <li>생성 후 10분 안에 아무도 입장하지 않은 방도 소멸한다.</li>
  <li>퇴장 순서: leave API 먼저, 소켓은 그다음에 끊는다. 소켓을 먼저 끊으면 30초 동안 <code>disconnected</code>로 남는다.</li>
</ul>

<h2 id="fe">9. FE가 처리해야 할 것</h2>
<table>
  <tr><th>상황</th><th>처리</th></tr>
  <tr><td>토픽 구독 후 SNAPSHOT이 오지 않음</td><td>구독 등록과 SNAPSHOT 발송의 레이스로 드물게 사라진다. 1~2초 뒤 <code>/app/room/{roomId}/snapshot</code>을 재요청하고, 몇 번 재시도해도 안 오면 join부터 다시 한다</td></tr>
  <tr><td>ROOM_UNAVAILABLE 수신</td><td>자리가 회수됐거나(예약 30초 초과, 유예 만료) 옛 세션이다. join을 다시 호출해 자리를 잡거나, 404면 방 종료를 안내한다. 이 메시지는 배달이 보장되지 않으므로 "SNAPSHOT 미도착"과 같은 처리로 수렴시킨다</td></tr>
  <tr><td>피어 연결 실패·끊김</td><td>10초 주기로 SNAPSHOT을 재요청해 멤버 목록을 재대조한다(배포 겹침 구간의 유령 멤버 보완). 목록에 없으면 피어를 정리, <code>disconnected</code>면 대기</td></tr>
  <tr><td>MEMBER_JOINED</td><td><code>userId</code> 기준 upsert. 이미 있는 멤버면 재입장이므로 피어 연결을 다시 맺는다</td></tr>
  <tr><td>access 토큰 만료</td><td>세션은 유지된다. 재접속이 필요해질 때 갱신한 토큰으로 CONNECT한다</td></tr>
  <tr><td>ICE 자격 만료</td><td><code>iceTtlSeconds</code> 경과 전에 join을 다시 호출해 새 <code>iceServers</code>를 받는다(자리는 유지, <code>graceRejoin: true</code>)</td></tr>
</table>

<h2 id="limits">10. 수치 한눈에</h2>
<table>
  <tr><th>항목</th><th>값</th><th>비고</th></tr>
  <tr><td>heartbeat</td><td>10초 / 10초</td><td>서버 제안. 클라이언트도 같은 값</td></tr>
  <tr><td>메시지 최대 크기</td><td>16 KB</td><td>가장 큰 메시지(SNAPSHOT 6명)가 약 2 KB</td></tr>
  <tr><td>세션당 송신 버퍼 / 송신 제한 시간</td><td>64 KB / 5초</td><td>초과하면 서버가 그 세션을 닫는다(느린·죽은 클라이언트 정리)</td></tr>
  <tr><td>정원</td><td>6명</td><td>초과 join은 409. 대기열 없음</td></tr>
  <tr><td>자리 예약</td><td>30초</td><td>join 후 토픽 구독까지</td></tr>
  <tr><td>끊김 유예</td><td>30초</td><td>소켓 끊김 후 자동 퇴장까지</td></tr>
  <tr><td>빈 방 소멸</td><td>600초</td><td>생성 후 무입장</td></tr>
  <tr><td>소멸한 코드의 ROOM_CLOSED 안내</td><td>600초</td><td>이후 INVITE_CODE_NOT_FOUND</td></tr>
  <tr><td>ICE 자격 TTL</td><td>join 응답 <code>iceTtlSeconds</code></td><td>환경마다 다르다</td></tr>
</table>

<h2 id="changelog">11. 변경 이력</h2>
<table>
  <tr><th>날짜</th><th>티켓</th><th>내용</th></tr>
  <tr><td>2026-09-17</td><td>BY-667</td><td>첫 판. BY-626(룸 상태 DB 원본화) 이후 계약 기준: <code>focusSec</code>, <code>members[].disconnected</code>, <code>ROOM_UNAVAILABLE</code>, SNAPSHOT 재요청</td></tr>
</table>

</main>
<script type="module">
  import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs";
  const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  mermaid.initialize({ startOnLoad: true, theme: dark ? "dark" : "default" });
</script>
</body>
</html>
````

- [ ] **Step 4: 대조 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.room.websocket.WebSocketDocsContractTest" -q`
Expected: BUILD SUCCESSFUL (5개 통과)

- [ ] **Step 5: 브라우저로 렌더링 확인** — `open src/main/resources/docs/websocket.html` (파일로 열어도 Mermaid가 CDN에서 로드된다). 다이어그램 4개가 그려지고, 시스템 다크모드에서 배경·글자가 뒤집히는지 본다. 깨진 다이어그램이 있으면 Mermaid 문법(특히 `--x`, `par/and/end`)을 고친다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/docs/websocket.html src/test/java/project/study/room/websocket/WebSocketDocsContractTest.java
git commit -m "feat: 룸 WebSocket 명세 페이지와 코드 대조 테스트 (BY-667)"
```

---

### Task 3: 스위치로 켜지는 서빙 + Security + yaml

**Files:**
- Create: `src/main/java/project/study/config/DocsResourceConfig.java`
- Modify: `src/main/java/project/study/config/SecurityConfig.java:67-69`
- Modify: `src/main/resources/application-dev.yaml` (`app:` 블록), `src/main/resources/application-local.yaml.example` (`app:` 블록)
- Modify: `src/test/java/project/study/config/FeatureSwitchTest.java`
- Test: `src/test/java/project/study/config/DocsPageEnabledApiTest.java`, `src/test/java/project/study/config/DocsPageDisabledApiTest.java`

**Interfaces:**
- Consumes: `docs/websocket.html`(Task 2)
- Produces: 프로퍼티 `app.docs.enabled`, 경로 `/docs/**`

- [ ] **Step 1: API 테스트 두 개 작성 (먼저)**

`DocsPageEnabledApiTest.java`
```java
package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** app.docs.enabled=true면 FE가 토큰 없이 브라우저로 명세 페이지를 연다 (BY-667). */
@SpringBootTest(properties = "app.docs.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocsPageEnabledApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 스위치가_켜지면_토큰_없이_웹소켓_명세_페이지를_연다() throws Exception {
        MvcTestResult result = mvc.get().uri("/docs/websocket.html").exchange();
        assertThat(result).hasStatus(HttpStatus.OK).hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        assertThat(result.getResponse().getContentAsString()).contains("<title>룸 WebSocket 명세</title>");
    }

    @Test
    void 없는_문서는_404() {
        assertThat(mvc.get().uri("/docs/nope.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }
}
```

`DocsPageDisabledApiTest.java`
```java
package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/** 스위치가 없으면(운영) 매핑 자체가 없어 404다 — 401이 아니라 404여야 permitAll이 함께 들어간 것이다 (BY-667). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocsPageDisabledApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 스위치가_없으면_명세_페이지는_404() {
        assertThat(mvc.get().uri("/docs/websocket.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }
}
```

`FeatureSwitchTest.java`에 추가 (기존 runner 필드들 아래, 기존 테스트들 아래):
```java
    private final ApplicationContextRunner docs =
            new ApplicationContextRunner().withUserConfiguration(DocsResourceConfig.class);

    @Test
    void 문서_페이지_서빙은_스위치가_없으면_뜨지_않는다() {
        docs.run(context -> assertThat(context).doesNotHaveBean(DocsResourceConfig.class));
    }

    @Test
    void 문서_페이지_서빙은_스위치를_켜면_뜬다() {
        docs.withPropertyValues("app.docs.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DocsResourceConfig.class));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.config.DocsPage*" --tests "project.study.config.FeatureSwitchTest" -q`
Expected: FAIL — 컴파일 오류(`DocsResourceConfig` 없음). 컴파일이 되도록 아래 Step 3의 클래스를 먼저 만들면 `DocsPageEnabledApiTest`는 401(Security)로, `DocsPageDisabledApiTest`도 401로 실패해야 한다

- [ ] **Step 3: `DocsResourceConfig` 작성**

```java
package project.study.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FE용 계약 문서(classpath:/docs/*.html)를 /docs/** 로 서빙한다. static/ 밖에 두고 스위치로만 켠다 — 값이 없으면
 * 꺼짐이 기본이라 운영 이미지에는 경로 자체가 없다(404). 스위치 규칙은 BY-640, 문서는 BY-667.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true")
public class DocsResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs/**").addResourceLocations("classpath:/docs/");
    }
}
```

- [ ] **Step 4: Security permitAll 한 줄** — `SecurityConfig.java`의 Swagger 매처 바로 아래

```java
                        // API 문서 — prod는 springdoc 자체가 꺼져 있어 404
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        // FE용 계약 문서(웹소켓 명세) — prod는 app.docs.enabled가 없어 매핑 자체가 없다(404) (BY-667)
                        .requestMatchers("/docs/**")
                        .permitAll()
```

- [ ] **Step 5: yaml** — `application-dev.yaml`의 `app:` 블록, `seed:` 바로 아래에

```yaml
  # FE용 계약 문서(/docs/websocket.html) 서빙. 값이 없으면 꺼짐 — prod에는 넣지 않는다 (BY-667)
  docs:
    enabled: true
```

`application-local.yaml.example`의 `app:` 블록, `# seed:` 주석 아래에
```yaml
  # 룸 WebSocket 명세 페이지 http://localhost:8080/docs/websocket.html (BY-667)
  docs:
    enabled: true
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew spotlessApply -q && ./gradlew test --tests "project.study.config.*" -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 로컬 기동으로 눈 확인** — `docker compose up -d && ./gradlew bootRun --args='--spring.profiles.active=local'` 후 브라우저에서 `http://localhost:8080/docs/websocket.html`. 로컬 yaml에도 `app.docs.enabled: true`가 있어야 한다(example에서 복사). 페이지가 뜨고 Swagger `http://localhost:8080/swagger-ui.html`은 그대로인지 본다. 끝나면 bootRun 종료.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/project/study/config/DocsResourceConfig.java src/main/java/project/study/config/SecurityConfig.java \
        src/main/resources/application-dev.yaml src/main/resources/application-local.yaml.example \
        src/test/java/project/study/config/DocsPageEnabledApiTest.java src/test/java/project/study/config/DocsPageDisabledApiTest.java \
        src/test/java/project/study/config/FeatureSwitchTest.java
git commit -m "feat: app.docs.enabled 스위치로 /docs/** 명세 페이지 서빙 (BY-667)"
```

---

### Task 4: Swagger 안내 링크 + 유지보수 규칙

**Files:**
- Modify: `src/main/java/project/study/room/controller/RoomController.java:33` (`@Tag`)
- Modify: `AGENTS.md` "## 작업 규칙" 목록 끝

- [ ] **Step 1: `@Tag` 설명에 링크**

```java
@Tag(
        name = "Room",
        description = "실시간 공부방 API — 초대코드 기반 일회성 방. WebRTC 시그널링은 STOMP WebSocket(/ws)으로 처리한다. "
                + "소켓 메시지 계약(연결·구독·이벤트·재접속)은 /docs/websocket.html 참고")
```

- [ ] **Step 2: `AGENTS.md` 작업 규칙 8번 추가** (7번 퀴즈 게이트 다음 줄)

```markdown
8. 룸 STOMP 계약(메시지 type·필드·목적지·수치)을 바꾸면 `src/main/resources/docs/websocket.html`과 그 변경 이력을
   같은 PR에서 갱신한다. `WebSocketDocsContractTest`가 type·목적지 누락은 잡지만 필드 의미·수치는 못 잡는다 (BY-667)
```

- [ ] **Step 3: 전체 검증**

Run: `./gradlew spotlessApply -q && ./gradlew check`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/project/study/room/controller/RoomController.java AGENTS.md
git commit -m "docs: Swagger Room 태그에 웹소켓 명세 링크, 계약 변경 시 문서 갱신 규칙 (BY-667)"
```

---

### Task 5: 검증 게이트 · PR

- [ ] **Step 1: 크로스 리뷰** — `/codex review` (P1 발견 시 수정 후 재리뷰). Security 변경(permitAll)이 있으므로 `/codex challenge security`도 돌린다
- [ ] **Step 2: 퀴즈 게이트** — 구현 코드·흐름 퀴즈 5개, 통과까지 반복 (CLAUDE.md 작업 규칙 7)
- [ ] **Step 3: push·PR**

```bash
git push -u origin "feat/BY-667-websocket-명세-페이지"
gh pr create --base dev --title "[feat] BY-667 룸 WebSocket 명세 페이지를 dev 서버에서 제공" --body-file <(cat <<'EOF'
## 📌 관련 이슈
- BY-667

## ✨ 작업 내용
- `/docs/websocket.html` — 룸 STOMP 계약 한 페이지(연결·입장·목적지·메시지 8종·시그널링·재접속·퇴장·FE 처리·수치·변경 이력, 시퀀스 4개). `app.docs.enabled=true`일 때만 서빙(dev·local). 값이 없는 운영은 매핑이 없어 404
- Security에 `/docs/**` permitAll — FE가 토큰 없이 브라우저로 열어야 해서. 운영은 매핑 자체가 없고 `DocsPageDisabledApiTest`가 404를 보장한다
- 서버 메시지 type을 `RoomMessageType` enum으로 통합 — 와이어 포맷은 문자열 그대로, 기존 테스트 무수정 통과
- `WebSocketDocsContractTest`가 코드의 type·발행/구독 목적지·kind·focusState·`RoomMember` 필드가 문서에 있는지 대조. 필드 의미·수치는 못 잡으므로 AGENTS.md에 "계약 바꾸면 같은 PR에서 문서 갱신" 규칙 추가
- Swagger Room 태그 설명에 페이지 링크

## 📸 스크린샷 / 테스트 결과
- (페이지 상단·입장 시퀀스 다이어그램 스크린샷 첨부)
- `./gradlew check` 통과

## 🔍 리뷰 포인트
- 문서 §4·§5·§7의 서술이 `RoomStompHandler`·`StompEventListener`·`RoomService` 동작과 맞는지 (대조 테스트가 못 잡는 부분)
- `/docs/**` permitAll의 범위 — `classpath:/docs/` 아래 파일만 노출된다

## ✅ 체크리스트
- [x] 커밋 메시지 컨벤션 준수
- [x] 로컬 빌드/테스트 성공
- [x] 불필요한 주석·console.log 제거
EOF
)
```

- [ ] **Step 4: CI 확인 후 머지** — 체크 런 등록 확인 → `gh pr checks --watch` → merge commit, 브랜치 유지

---

### Task 6: 머지 후 확인 · FE 전달 · 티켓 완료

- [ ] **Step 1: dev 자동 배포 확인** — Actions "Deploy dev" 성공(BY-670). `curl -sI https://<dev API 호스트>/docs/websocket.html | head -1` → `200`
- [ ] **Step 2: FE 전달** — BY-667에 FE 담당자(허원일, accountId `712020:1af168ed-b485-495b-bb0f-56f09b606707`) 멘션 댓글: 페이지 URL, "코드 기준이라 Swagger처럼 항상 최신", 질문은 댓글로. 멘션은 ADF `{"type":"mention","attrs":{"id":"<accountId>","text":"@허원일"}}`로 넣어야 알림이 간다
- [ ] **Step 3: 티켓 완료** — BY-667 상태 완료(전이 ID 41)
