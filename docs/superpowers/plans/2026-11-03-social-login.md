# 소셜 로그인 백엔드 (BY-1126) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비회원이 구글·카카오·애플로 link하면 기록이 그 계정으로 이어지고(전환·병합), 기기별 로그아웃과 즉시 하드 삭제 탈퇴(애플 해지 포함), 안드로이드 애플 웹 로그인 콜백을 제공한다.

**Architecture:** 프로바이더 검증은 `user/oauth`(트랜잭션 밖), 토큰 발급은 `AuthService`(유저 행 잠금 아래)가 맡는다. users를 참조하는 테이블은 각 도메인이 `UserDataHandler`를 구현해 자기 테이블의 병합·삭제 규칙을 갖고, `user/account/AccountData`가 순서대로 부른다. DB에서 users 참조 컬럼을 읽어 처리기에 빠진 컬럼이 있으면 실패하는 테스트가 빠뜨림을 막는다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL, Flyway, jjwt 0.13(gson), `JdbcClient`, `RestClient`, MockMvcTester, Testcontainers 2.0, `MockRestServiceServer`, `@MockitoBean`

**Spec:** `docs/superpowers/specs/2026-11-03-social-login-design.md`

**범위:** 설계의 전달 순서 1·2단계. 3단계(그룹 테이블의 병합·탈퇴, 그룹장 자동 승계)는 그룹 테이블이 dev에 들어온 뒤 별도 계획으로 한다. 그룹 PR이 먼저 들어오면 Task 4의 빠뜨림 방지 테스트가 그 테이블을 잡아낸다.

## Global Constraints

- CLAUDE.md의 Boot 4 주의사항(Jackson 3 `tools.jackson`, Testcontainers 2.0, `@Profile` 금지, 생성자 주입, record DTO, `@Data` 금지)을 따른다
- 새 경로는 모두 `version = "1"`. 테스트는 `.header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)`을 `.with(asUser(..))` 앞에 둔다 (`asUser`는 헤더가 없으면 2를 싣는다)
- 새 의존성 없음. 애플 client secret(ES256)은 jjwt로 서명한다
- 발급·회전·전량 폐기·병합·탈퇴는 `UserRepository.findByIdForUpdate` 잠금 아래. 두 사용자를 잠글 때는 ID 작은 쪽부터
- 프로바이더 HTTP 호출(검증·애플 토큰 교환·해지)은 DB 트랜잭션 밖
- `permitAll` 추가는 `POST /api/auth/apple/callback` 하나뿐 (2026-11-03 사용자 승인)
- 에러는 `ErrorResponse{code, message}`. 새 code: `INVALID_SOCIAL_TOKEN`(400), `ALREADY_LINKED`(409). 기존 `USER_NOT_FOUND`(404)를 쓴다
- 병합 원칙: 부딪히면 남는 쪽(기존 소셜 계정)이 이긴다. 합친 결과가 개수 제한을 넘어도 그대로 둔다
- `amplitude_daily_event`, `amplitude_user_attribute`는 병합·탈퇴에서 건드리지 않는다 (사용자 결정)
- 마이그레이션은 `V36`, ADR은 `0041`로 쓰되, BY-1124가 먼저 dev에 들어가면 머지 직전에 다음 번호로 바꾼다
- 커밋은 태스크마다 `./gradlew check` 통과 뒤, Conventional Commits 한국어 제목 + `(BY-1126)`. 퀴즈 게이트(CLAUDE.md 7)는 전체 구현이 끝난 뒤 PR 전에 한 번
- 비밀값(ID 토큰, nonce, authorizationCode, 애플 개인 키)은 로그에 남기지 않는다

## Review Focus

- 경계가 맞닿은 세션(남는 쪽 끝 = 옮기는 쪽 시작)은 겹침이 아니어서 옮겨져야 하고, `ended_at`이 NULL인 옛 세션도 500 없이 처리돼야 한다 → Task 3 테스트
- 이름이 같아 보이지만 앞뒤 공백·NFC 정규화가 다른 과목("수학 " vs "수학", 자모 분리형 한글)은 하나로 합쳐져야 한다 → Task 3 테스트
- link 순간 옮기는 쪽이 공부방 안에 있으면 방에서 빠지고(다른 사람에게 `MEMBER_LEFT`) 병합이 500 없이 끝나야 한다 → Task 5 테스트
- 같은 기기가 link를 연달아 두 번 보내면 한쪽은 200, 다른 쪽은 409 또는 404여야 하고 500이면 안 된다 → Task 5 테스트
- 모든 사용자 테이블에 행이 있는 사용자의 병합·탈퇴가 FK 위반 없이 끝나고 옮기는 쪽/지운 사람을 가리키는 행이 하나도 남지 않아야 한다 → Task 4·7 테스트

---

### Task 1: 프로바이더 ID 토큰 검증기 되살리기 (허용 aud 목록, nonce 클레임)

**Files:**
- Modify (주석 해제 후 수정): `src/main/java/project/study/user/oauth/{OAuthTokenVerifier,OAuthUserInfo,InvalidOAuthTokenException,GoogleTokenVerifier,KakaoTokenVerifier,AppleTokenVerifier}.java`
- Create: `src/main/java/project/study/user/oauth/OAuthProperties.java`, `src/main/java/project/study/user/oauth/SocialTokenVerifiers.java`
- Modify: `src/main/resources/application-dev.yaml`, `src/main/resources/application-prod.yaml`, `src/main/resources/application-local.yaml.example`, `src/test/resources/application.yaml` (`oauth:` 블록)
- Test (주석 해제 후 수정): `src/test/java/project/study/user/oauth/{Google,Kakao,Apple}TokenVerifierTest.java`, Create `SocialTokenVerifiersTest.java`

**Interfaces:**
- Produces:
  - `record OAuthUserInfo(Provider provider, String providerUserId, String email, String nonce)` — `nonce`는 토큰의 nonce 클레임(없으면 null)
  - `interface OAuthTokenVerifier { Provider provider(); OAuthUserInfo verify(String idToken); }` — 실패는 `InvalidOAuthTokenException`
  - `@ConfigurationProperties("oauth") record OAuthProperties(Google google, Kakao kakao, Apple apple)`, `record Google(List<String> clientIds)`, `record Kakao(List<String> appKeys)`, `record Apple(String bundleId, String servicesId, String teamId, String keyId, String privateKey, String webRedirectUri, String androidCallbackRedirect)` + `List<String> Apple.audiences()`(비지 않은 bundleId·servicesId) + `boolean Apple.revokeConfigured()`(teamId·keyId·privateKey 모두 있음)
  - `@Component SocialTokenVerifiers { OAuthUserInfo verify(Provider provider, String idToken) }` — `DEVICE`나 등록되지 않은 프로바이더는 `InvalidOAuthTokenException`. 통합 테스트는 이 빈을 `@MockitoBean`으로 바꾼다

설정 키(환경변수): `oauth.google.client-ids`(`OAUTH_GOOGLE_CLIENT_IDS`, 쉼표 목록), `oauth.kakao.app-keys`(`OAUTH_KAKAO_APP_KEYS`), `oauth.apple.bundle-id`(`OAUTH_APPLE_BUNDLE_ID`), `oauth.apple.services-id`·`team-id`·`key-id`·`private-key`·`web-redirect-uri`·`android-callback-redirect`(`OAUTH_APPLE_*`, 기본값 빈 문자열). prod yaml은 google·kakao·bundle-id에 기본값을 두지 않는다. dev yaml의 지금 기본값(구글 웹 클라이언트 ID, 카카오 앱 키, `com.breathlessyouth.mobile`)은 목록의 첫 값으로 옮긴다.

- [ ] **Step 1: 주석 처리된 검증기 테스트 세 개를 되살리고 목록·nonce 케이스를 더한다**
  - 각 테스트: 목록의 두 번째 aud로 발급된 토큰도 통과, 목록에 없는 aud는 `InvalidOAuthTokenException`
  - 각 테스트: 토큰에 `nonce`가 있으면 `OAuthUserInfo.nonce()`에 그대로 담긴다, 없으면 null
  - Apple: `iss`가 `https://appleid.apple.com`이 아니면 실패, servicesId로 발급된 토큰 통과
  - `SocialTokenVerifiersTest`: `verify(Provider.DEVICE, "x")`는 `InvalidOAuthTokenException`, `verify(Provider.KAKAO, t)`는 카카오 검증기로 위임
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.oauth.*"` / Expected: 컴파일 실패(검증기 주석 상태)
- [ ] **Step 3: 검증기·`OAuthProperties`·`SocialTokenVerifiers`를 구현한다** — 구글은 tokeninfo 응답의 `aud`·`nonce`, 카카오는 tokeninfo 응답의 `aud`·`nonce`, 애플은 `requireIssuer` 뒤 `aud` 클레임이 `audiences()` 안에 있는지 직접 비교하고 `nonce` 클레임을 읽는다. `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(OAuthProperties.class)`로 등록
- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.user.oauth.*"` / Expected: PASS
- [ ] **Step 5: 전체 확인 후 커밋** — Run: `./gradlew check` / Expected: BUILD SUCCESSFUL
```bash
git add src/main/java/project/study/user/oauth src/main/resources src/test/java/project/study/user/oauth src/test/resources/application.yaml
git commit -m "feat: 소셜 ID 토큰 검증기를 되살리고 허용 aud를 목록으로 받는다 (BY-1126)"
```

### Task 2: 사용자 데이터 처리기 구조와 단순 테이블 처리기

**Files:**
- Create: `src/main/java/project/study/user/account/UserDataHandler.java`, `AccountData.java`, `UserDataColumns.java`
- Create: `src/main/java/project/study/user/account/UserAccountDataHandler.java` (user 패키지 소유 테이블)
- Create: `src/main/java/project/study/dday/service/DdayUserDataHandler.java`, `src/main/java/project/study/acquisition/service/AcquisitionUserDataHandler.java`, `src/main/java/project/study/push/service/PushUserDataHandler.java`
- Test: `src/test/java/project/study/user/account/UserDataCoverageTest.java`, `src/test/java/project/study/user/account/UserDataFixture.java`, `src/test/java/project/study/user/account/SimpleUserDataHandlersTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface UserDataHandler {
      Set<String> columns();          // 책임지는 "테이블.컬럼", 예: "user_dday.user_id"
      int order();                    // 작을수록 먼저 (병합·삭제 공통)
      void merge(long sourceUserId, long targetUserId);
      void erase(long userId);
  }
  ```
  - `@Component AccountData { void merge(long sourceUserId, long targetUserId); void erase(long userId); }` — 처리기를 `order()` 순으로 부른다. `@Transactional(propagation = MANDATORY)`. users 행 자체는 건드리지 않는다
  - `UserDataColumns.IGNORED = Set.of("amplitude_daily_event.user_id", "amplitude_user_attribute.user_id")`
  - `UserDataColumns.PENDING` — 아직 처리기가 없는 컬럼. Task 2에서 study_session·active_study_session·study_subject·daily_completion·rooms.created_by·room_participations·rtc_connection_stat(두 컬럼)·ranking_record·ranking_best를 넣고, Task 3·4가 지워 Task 4 끝에 클래스에서 없앤다
  - 테스트용 `UserDataFixture` (`@Component`, `JdbcClient` 사용): 테이블별 `seedXxx(long userId, ...)`와 `long countRowsReferencing(long userId)`(처리기 `columns()` 전부를 `WHERE col = :id`로 센 합)
- 순서 상수: 세션 100, 과목 200, 방 300, 랭킹 400, 디데이·유입·푸시 500, user 패키지 900

처리기 규칙 (병합 / 삭제):

| 처리기 | 컬럼 | 병합 | 삭제 |
|---|---|---|---|
| `UserAccountDataHandler` | `refresh_token.user_id`, `report.report_user_id`, `report.target_user_id`, `inquiry.user_id`, `user_notification.user_id`, `study_stats_daily/weekly/monthly.user_id` | refresh는 옮기는 쪽 전량 삭제. 나머지는 옮기고, PK가 `(user_id, 날짜)`인 stats는 남는 쪽에 같은 키가 있으면 옮기는 쪽 행을 지운다 | 전부 삭제 |
| `DdayUserDataHandler` | `user_dday.user_id` | 남는 쪽에 있으면 옮기는 쪽 삭제, 없으면 옮김 | 삭제 |
| `AcquisitionUserDataHandler` | `user_acquisition.user_id` | 같은 규칙 | 삭제 |
| `PushUserDataHandler` | `push_token.user_id`, `push_send.user_id`, `push_experiment_assignment.user_id` | `push_token` 전부 옮김, 나머지 둘은 옮기는 쪽 삭제 | 전부 삭제 |

- [ ] **Step 1: 빠뜨림 방지 테스트를 쓴다** (`UserDataCoverageTest`, `@SpringBootTest` + `TestcontainersConfiguration`)
  - `pg_constraint`에서 `confrelid = 'users'::regclass`인 FK의 "테이블.컬럼"과, `information_schema.columns`에서 `users`가 아닌 테이블의 `user_id`·`%_user_id` 컬럼을 모은다 (flyway 테이블 제외)
  - `assertThat(db).isEqualTo(union(모든 UserDataHandler 빈의 columns(), IGNORED, PENDING))`
  - 처리기끼리 같은 컬럼을 두 번 갖지 않는다
- [ ] **Step 2: 단순 처리기 테스트를 쓴다** (`SimpleUserDataHandlersTest`) — 표의 각 규칙마다 하나: 디데이 남는 쪽에 있음/없음, 유입 같은 두 경우, 푸시 토큰이 남는 쪽으로 옮겨짐, push_send·assignment는 옮기는 쪽 것이 사라짐, stats 같은 날짜 충돌, refresh 옮기는 쪽 전량 삭제, 각 처리기 `erase` 뒤 `countRowsReferencing == 0`
- [ ] **Step 3: 실패 확인** — Run: `./gradlew test --tests "project.study.user.account.*"` / Expected: 컴파일 실패
- [ ] **Step 4: 인터페이스·`AccountData`·`UserDataColumns`·처리기 넷을 구현한다** — `JdbcClient` 네이티브 SQL. 병합의 "남는 쪽에 있으면 지운다"는 `DELETE ... WHERE user_id = :source AND EXISTS (남는 쪽 같은 키)` 뒤 `UPDATE ... SET user_id = :target WHERE user_id = :source` 순서
- [ ] **Step 5: 통과 확인** — Run: `./gradlew test --tests "project.study.user.account.*"` / Expected: PASS
- [ ] **Step 6: 전체 확인 후 커밋** — `./gradlew check` 후
```bash
git commit -m "feat: 사용자 데이터 병합·삭제 처리기 구조와 빠뜨림 방지 테스트를 추가한다 (BY-1126)"
```

### Task 3: 공부 기록·과목 처리기 (겹침 폐기, 같은 이름 과목 합치기)

**Files:**
- Create: `src/main/java/project/study/studysession/service/StudySessionUserDataHandler.java`, `src/main/java/project/study/subject/service/SubjectUserDataHandler.java`
- Modify: `src/main/java/project/study/user/account/UserDataColumns.java` (PENDING에서 네 컬럼 제거)
- Test: `src/test/java/project/study/studysession/StudySessionUserDataHandlerTest.java`, `src/test/java/project/study/subject/SubjectUserDataHandlerTest.java`, `UserDataFixture`에 seed 추가

**Interfaces:**
- Consumes: `UserDataHandler`, `UserDataFixture` (Task 2)
- Produces: 처리기 두 개 (`columns()` = `study_session.user_id`, `active_study_session.user_id` / `study_subject.user_id`, `daily_completion.user_id`)

규칙:
- 세션 병합: 옮기는 쪽 세션 중 남는 쪽 세션과 `tstzrange(started_at, COALESCE(ended_at, started_at), '[)')`가 겹치거나 `started_at`이 같은 것을 지운다(`status_event`를 먼저 지운다, 나머지 하위 테이블은 ON DELETE CASCADE). 남은 세션을 옮긴다. `active_study_session`은 같은 `started_at`이 남는 쪽에 있으면 지우고 나머지를 옮긴다. 지운·옮긴 건수를 `log.info`
- 세션 삭제: `status_event` → `study_session`(CASCADE) → `active_study_session`
- 과목 병합: 살아 있는(`deleted_at IS NULL`) 과목끼리 `normalize(name) = NFC + strip()`이 같으면 옮기는 쪽 과목을 남는 쪽 과목에 합친다 — `study_session_subject_segment.subject_id`와 `study_task.subject_id`를 남는 쪽 과목으로, `daily_completion`(target_type `SUBJECT`)의 `target_id`를 바꾸고 unique 충돌은 옮기는 쪽 행 삭제, 옮기는 쪽 과목 행 삭제. 이름이 다른 과목은 `user_id`를 바꾸고 `sort_order`를 남는 쪽 최댓값 뒤로 원래 순서대로 다시 매긴다. 지운(`deleted_at`) 과목은 그대로 옮긴다. 남은 `daily_completion`은 옮기고 충돌은 지운다. 이름 비교는 Java에서 `java.text.Normalizer`로 한다(옮기는 쪽·남는 쪽 과목은 사람당 수십 개라 읽어서 비교해도 된다)
- 과목 삭제: `daily_completion` → `study_task` → `study_subject` (세션이 먼저 지워져 있어야 하므로 order 200)

- [ ] **Step 1: 실패 테스트를 쓴다**
  - `겹치는_세션은_지우고_겹치지_않는_세션은_옮긴다` (남는 쪽 10:00–11:00, 옮기는 쪽 10:30–11:30은 삭제, 12:00–13:00은 이동)
  - `끝과_시작이_맞닿은_세션은_옮긴다` (남는 쪽 10:00–11:00, 옮기는 쪽 11:00–12:00 이동)
  - `ended_at이_없는_세션도_처리한다` (옮기는 쪽 `ended_at` NULL 세션: 남는 쪽과 `started_at`이 겹치지 않으면 이동)
  - `겹쳐서_지운_세션의_status_event도_사라진다`
  - `같은_started_at의_draft는_지우고_나머지는_옮긴다`
  - `이름이_같은_과목은_하나로_합치고_구간과_할_일이_남는_쪽_과목을_가리킨다` ("수학 " vs "수학", NFD "수학" vs NFC "수학" 각각)
  - `이름이_다른_과목은_남는_쪽_뒤_순서로_옮긴다` (남는 쪽 sort_order 0·1, 옮기는 쪽 과목 둘 → 2·3)
  - `합친_과목의_같은_날_완료_기록은_하나만_남는다`
  - 두 처리기 `erase` 뒤 `countRowsReferencing == 0`, 다른 사용자 행은 그대로
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.studysession.StudySessionUserDataHandlerTest" --tests "project.study.subject.SubjectUserDataHandlerTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 두 처리기를 구현하고 PENDING에서 네 컬럼을 지운다**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `UserDataCoverageTest` / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 공부 기록·과목 병합 규칙을 추가한다 — 겹침 폐기·같은 이름 과목 합치기 (BY-1126)"
```

### Task 4: 방·RTC·랭킹 처리기와 현재 방 나가기

**Files:**
- Create: `src/main/resources/db/migration/V36__rooms_created_by_nullable.sql` (`ALTER TABLE rooms ALTER COLUMN created_by DROP NOT NULL;`)
- Create: `src/main/java/project/study/room/service/RoomUserDataHandler.java`, `src/main/java/project/study/room/service/CurrentRoomLeaver.java`, `src/main/java/project/study/rtcstats/service/RtcStatsUserDataHandler.java`, `src/main/java/project/study/ranking/service/RankingUserDataHandler.java`
- Modify: `src/main/java/project/study/room/entity/*` 중 `created_by` 매핑(있으면 nullable), `UserDataColumns.java` (PENDING 삭제)
- Test: `src/test/java/project/study/room/RoomUserDataHandlerTest.java`, `CurrentRoomLeaverTest.java`, `src/test/java/project/study/ranking/RankingUserDataHandlerTest.java`, `src/test/java/project/study/user/account/AccountDataCompletenessTest.java`

**Interfaces:**
- Consumes: `UserDataHandler`, `UserDataFixture`, `RoomParticipationRepository.findLiveRoomIdOfUser(Long)`, `RoomService.leave(Long roomId, Long userId): LeaveResult`
- Produces: `@Component CurrentRoomLeaver { void leave(long userId) }` — 지금 있는 방에서 `RoomService.leave`로 나가고, 방이 아직 열려 있으면 `RoomController.leave`와 같은 `MEMBER_LEFT` 메시지를 `/topic/room/{roomId}`로 보낸다. 방에 없으면 아무것도 안 한다. 트랜잭션 밖에서 부른다

규칙:
- 방: 병합은 `rooms.created_by`와 `room_participations.user_id`를 옮긴다. 이 시점에 옮기는 쪽의 살아 있는 참가 행(`left_at IS NULL`)이 남아 있으면(경쟁) 옮기기 전에 `left_at = now()`로 닫는다. 삭제는 참가 행 삭제, `rooms.created_by = NULL`
- RTC: 병합은 그대로 둔다(no-op). 삭제는 `user_id` 행 삭제, `peer_user_id = NULL`
- 랭킹: 병합은 남는 쪽에 같은 `(board_key, period_start)` 기록이 있으면 옮기는 쪽 기록 삭제, 나머지 이동. `ranking_best`는 옮기는 쪽 값을 `RankingCloseRepository`의 UPSERT와 같은 조건(`excluded.rank < ranking_best.rank`)으로 남는 쪽에 반영한 뒤 옮기는 쪽 행 삭제. 삭제는 두 테이블 행 삭제

- [ ] **Step 1: 실패 테스트를 쓴다**
  - 방·RTC·랭킹 규칙마다 하나 (`만든_방은_탈퇴해도_남고_created_by만_비운다`, `같은_순위판_기간의_기록은_남는_쪽_것만_남는다`, `더_좋은_최고_순위는_남는_쪽으로_옮겨진다` 등)
  - `CurrentRoomLeaverTest`: 방에 있는 사용자 → 참가 행 `left_at` 채워짐 + `MEMBER_LEFT` 전송(`SimpMessagingTemplate` 목), 방에 없는 사용자 → 아무 일 없음
  - `AccountDataCompletenessTest` (Review Focus 5): `UserDataFixture`로 모든 처리기 테이블에 행을 만든 두 사용자 → `merge` 뒤 옮기는 쪽 `countRowsReferencing == 0`이고 `users` 행 삭제가 FK 위반 없이 된다 / 한 사용자 `erase` 뒤 0이고 `users` 행 삭제가 된다
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.room.*UserData*" --tests "project.study.room.CurrentRoomLeaverTest" --tests "project.study.ranking.RankingUserDataHandlerTest" --tests "project.study.user.account.*"` / Expected: 컴파일 실패
- [ ] **Step 3: 마이그레이션·처리기 셋·`CurrentRoomLeaver`를 구현하고 `UserDataColumns.PENDING`을 없앤다** (`UserDataCoverageTest`에서 PENDING 참조도 지운다)
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 방·RTC·랭킹 병합·삭제 규칙과 현재 방 나가기를 추가한다 (BY-1126)"
```

### Task 5: `POST /api/auth/link` (전환·병합·nonce)

**Files:**
- Modify: `src/main/java/project/study/user/service/AuthService.java`, `src/main/java/project/study/user/controller/AuthController.java`, `src/main/java/project/study/user/controller/AuthExceptionHandler.java`, `src/main/java/project/study/common/exception/ErrorCode.java`, `src/main/java/project/study/user/entity/User.java`
- Modify (주석 해제 후 수정): `src/main/java/project/study/user/dto/LinkSocialRequest.java`
- Create: `src/main/java/project/study/user/dto/LinkResponse.java`, `src/main/java/project/study/user/service/AlreadyLinkedException.java`, `src/main/java/project/study/user/oauth/SocialNonce.java`
- Delete: `src/main/java/project/study/user/dto/LoginRequest.java`, `LoginResponse.java`
- Test: `src/test/java/project/study/user/LinkApiTest.java`, `src/test/java/project/study/user/oauth/SocialNonceTest.java`, `src/test/java/project/study/user/service/AuthServiceTest.java`(필요한 단위 케이스)

**Interfaces:**
- Consumes: `SocialTokenVerifiers.verify` (Task 1), `AccountData.merge` (Task 2), `CurrentRoomLeaver.leave` (Task 4), `ProfileImageStorage.delete(String)`
- Produces:
  - `record LinkSocialRequest(@NotNull Provider provider, @NotBlank @Size(max = 4096) String idToken, @Size(max = 128) String nonce)`
  - `record LinkResponse(String accessToken, String refreshToken, boolean isNewUser)`
  - `LinkResponse AuthService.link(Long userId, LinkSocialRequest request)`
  - `ErrorCode.INVALID_SOCIAL_TOKEN`, `ErrorCode.ALREADY_LINKED`
  - `final class SocialNonce { static void check(OAuthUserInfo info, String rawNonce) }` — 애플인데 토큰 nonce가 null이면 실패, 토큰 nonce가 있으면 `rawNonce`가 있고 `sha256Hex(rawNonce)`와 같아야 함, 실패는 `InvalidOAuthTokenException`

흐름 (`link`, 트랜잭션 아님):
1. `verify` → `SocialNonce.check` (트랜잭션 밖)
2. `currentRoomLeaver.leave(userId)`
3. `TransactionTemplate`으로 `linkTransaction`: 옮기는 쪽 잠금(없으면 `NotFoundException(USER_NOT_FOUND)`), DEVICE가 아니면 `AlreadyLinkedException`, 소셜 ID 사용자 조회
   - 없음(전환): `user.linkSocialAccount(..)` → `flush` → refresh 전량 삭제 → 발급, `isNewUser = true`
   - 있음(병합): 두 사용자를 ID 작은 쪽부터 잠그고 → `accountData.merge(source, target)` → 옮기는 쪽 프로필 사진 키를 기억 → `userRepository.delete(source)` → 남는 쪽 발급, `isNewUser = false`
4. 전환 중 `DataIntegrityViolationException`(같은 소셜 ID 동시 전환)이면 새 트랜잭션으로 3을 한 번 더 (병합 경로가 된다)
5. 커밋 뒤 옮기는 쪽 프로필 사진 파일을 조용히 지운다 (`ProfileImageService.deleteQuietly`와 같은 방식)

- `User.linkSocialAccount`는 `ConflictException` 대신 `AlreadyLinkedException`을 던진다
- `AuthExceptionHandler`: `InvalidOAuthTokenException` → 400 `INVALID_SOCIAL_TOKEN`, `AlreadyLinkedException` → 409 `ALREADY_LINKED`
- 컨트롤러: `@PostMapping(value = "/link", version = "1")`, `@AuthenticationPrincipal Long userId`, Swagger에 200·400(`INVALID_SOCIAL_TOKEN`, `VALIDATION_FAILED`)·401·404·409 예시와 nonce 규칙(SDK에는 원문의 SHA-256 hex를 넘긴다, 애플 필수). `@Tag` 설명에서 "소셜 로그인 파킹" 문구를 지운다

- [ ] **Step 1: 실패 테스트를 쓴다**
  - `SocialNonceTest`: 애플 nonce 없음 실패 / 토큰 nonce 있는데 원문 없음 실패 / 해시 불일치 실패 / 일치 통과 / 구글·카카오 토큰 nonce 없음 통과
  - `LinkApiTest` (`@MockitoBean SocialTokenVerifiers`, 기기 등록은 실제 `POST /api/users`):
    - `처음_쓰는_소셜_계정이면_같은_userId로_전환되고_isNewUser가_true다` (응답 access의 sub = 원래 userId, provider·이메일 저장)
    - `전환_뒤_옛_deviceId로_등록하면_새_비회원이_생긴다`
    - `전환하면_이_기기의_이전_refresh는_쓸_수_없다`
    - `이미_있는_소셜_계정이면_기록을_합치고_그_계정의_토큰을_준다` (sub = 남는 쪽, 옮기는 쪽 users 행 없음, 세션 이동, isNewUser false, 남는 쪽 다른 기기 refresh는 여전히 유효)
    - `이미_소셜_계정이면_409_ALREADY_LINKED`
    - `검증_실패는_400_INVALID_SOCIAL_TOKEN이다` / `애플_nonce가_없으면_400` / `nonce가_다르면_400`
    - `link_순간_방_안에_있으면_방에서_빠지고_병합된다` (Review Focus 3)
    - `같은_기기의_link_두_번이_동시에_오면_하나만_성공하고_500은_없다` (Review Focus 4, `CountDownLatch` 두 스레드, 결과 상태 집합이 {200, 409} 또는 {200, 404})
    - `두_기기가_같은_소셜_계정으로_동시에_전환해도_둘_다_200이고_한_계정이_된다`
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.LinkApiTest" --tests "project.study.user.oauth.SocialNonceTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 위 흐름대로 구현한다**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `./gradlew test --tests "project.study.user.*"` / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 소셜 로그인 link API를 추가한다 — 전환·병합·nonce 검증 (BY-1126)"
```

### Task 6: `POST /api/auth/logout`

**Files:**
- Modify: `AuthService.java`, `AuthController.java`, `src/main/java/project/study/user/repository/RefreshTokenRepository.java`
- Test: `src/test/java/project/study/user/LogoutApiTest.java`

**Interfaces:**
- Produces: `void AuthService.logout(Long userId, String refreshToken)`, `int RefreshTokenRepository.deleteByTokenHashAndUserIdIfUnused(String tokenHash, Long userId)` (`delete ... where tokenHash = :h and userId = :u and usedAt is null`)

규칙 (PR #8 `AuthService.logout` 그대로): 한 문장 삭제가 1이면 끝. 0이면 토큰을 조회해 호출자 소유면 전량 폐기(이미 회전된 내 토큰), 남의 토큰이면 아무것도 안 함. 유저 행 잠금 아래. 요청은 `RefreshRequest`, 응답 204, `version = "1"`.

- [ ] **Step 1: 실패 테스트** — `로그아웃하면_이_기기_refresh만_무효가_된다`(다른 기기 refresh는 재발급 성공), `남의_refresh로_로그아웃해도_그_사람은_로그아웃되지_않는다`, `이미_회전된_내_refresh로_로그아웃하면_전량_폐기된다`, `없는_토큰도_204다`
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.LogoutApiTest"` / Expected: FAIL (404 경로 없음)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 기기별 로그아웃 API를 추가한다 (BY-1126)"
```

### Task 7: `DELETE /api/users/me` (즉시 하드 삭제)

**Files:**
- Create: `src/main/java/project/study/user/service/AccountDeletionService.java`, `src/main/java/project/study/user/service/AccountEraser.java`, `src/main/java/project/study/user/dto/DeleteAccountRequest.java`, `src/main/java/project/study/user/dto/AppleClient.java`
- Modify: `src/main/java/project/study/user/controller/UserController.java`
- Test: `src/test/java/project/study/user/AccountDeletionApiTest.java`

**Interfaces:**
- Consumes: `AccountData.erase` (Task 2), `CurrentRoomLeaver.leave` (Task 4), `ProfileImageStorage.delete`
- Produces:
  - `enum AppleClient { IOS, ANDROID }`
  - `record DeleteAccountRequest(@Size(max = 1024) String appleAuthorizationCode, AppleClient appleClient)` — 본문 전체 선택(`@RequestBody(required = false)`)
  - `@Component AccountEraser { Optional<ErasedAccount> erase(long userId) }` (`@Transactional`) — 잠금, 없으면 빈 값, `accountData.erase` → users 행 삭제. `record ErasedAccount(Provider provider, String profileImageKey)`
  - `@Service AccountDeletionService { void delete(Long userId, DeleteAccountRequest request) }` — 트랜잭션 아님: `currentRoomLeaver.leave` → `accountEraser.erase` → 사진 파일 조용히 삭제 (Task 8이 애플 해지를 이 뒤에 더한다)
- 컨트롤러: `@DeleteMapping(value = "/me", version = "1")`, 204. Swagger에 "모든 기기 로그아웃, 되돌릴 수 없음, 같은 소셜 계정으로 다시 로그인하면 새 가입, 애플 계정은 앱이 애플 로그인을 다시 띄워 받은 코드를 함께 보낸다"

- [ ] **Step 1: 실패 테스트** (`@MockitoBean ProfileImageStorage`)
  - `탈퇴하면_사용자와_모든_사용자_데이터가_사라진다` (`UserDataFixture`로 모든 테이블 seed → 204 → `countRowsReferencing == 0`, users 행 없음; Review Focus 5)
  - `탈퇴하면_모든_기기의_refresh가_무효다`
  - `두_번_탈퇴해도_204다`
  - `탈퇴한_소셜_ID로_다시_link하면_isNewUser가_true다` (`SocialTokenVerifiers` 목)
  - `프로필_사진이_있으면_파일도_지운다` (`verify(storage).delete(key)`)
  - `방_안에서_탈퇴하면_방에서_빠진다`
  - `비회원도_탈퇴할_수_있다`
  - `다른_사람의_데이터는_그대로다`
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.AccountDeletionApiTest"` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 계정 삭제(탈퇴) API를 추가한다 — 즉시 하드 삭제 (BY-1126)"
```

### Task 8: 애플 토큰 해지

**Files:**
- Create: `src/main/java/project/study/user/oauth/AppleClientSecret.java`, `src/main/java/project/study/user/oauth/AppleTokenRevoker.java`
- Modify: `AccountDeletionService.java`
- Test: `src/test/java/project/study/user/oauth/AppleClientSecretTest.java`, `AppleTokenRevokerTest.java`, `AccountDeletionApiTest`에 케이스 추가

**Interfaces:**
- Consumes: `OAuthProperties.Apple` (Task 1), `AppleClient`, `ErasedAccount` (Task 7)
- Produces:
  - `AppleClientSecret { String create(String clientId) }` — ES256, 헤더 `kid = keyId`, 클레임 `iss = teamId`, `iat = now`, `exp = now + 5분`, `aud = "https://appleid.apple.com"`, `sub = clientId`. `privateKey`는 PEM(.p8 내용, `\n` 이스케이프 허용)
  - `@Component AppleTokenRevoker { void revoke(String authorizationCode, AppleClient client) }` — `revokeConfigured()`가 아니면 경고 로그 후 끝. `client_id`는 IOS면 `bundleId`, ANDROID면 `servicesId`. `POST https://appleid.apple.com/auth/token` (form: `client_id`, `client_secret`, `code`, `grant_type=authorization_code`, ANDROID면 `redirect_uri = webRedirectUri`) → 응답 `refresh_token` → `POST https://appleid.apple.com/auth/revoke` (form: `client_id`, `client_secret`, `token`, `token_type_hint=refresh_token`). 어떤 실패도 예외를 밖으로 내지 않고 `log.error`(Sentry로 간다), 코드·토큰 값은 로그에 넣지 않는다
- `AccountDeletionService`: 지운 계정이 APPLE이면 코드가 있을 때 `revoke(code, client == null ? IOS : client)`, 없으면 `log.warn`. 사용자 데이터 삭제가 끝난 뒤(트랜잭션 밖)

- [ ] **Step 1: 실패 테스트**
  - `AppleClientSecretTest`: 테스트용 EC 키쌍으로 만든 secret을 공개키로 파싱해 헤더·클레임 값 확인
  - `AppleTokenRevokerTest` (`MockRestServiceServer`): IOS는 bundleId로 교환하고 `redirect_uri` 없음 → revoke 호출 / ANDROID는 servicesId와 `redirect_uri` / 설정이 없으면 HTTP 호출 0 / 토큰 교환 400이면 revoke 없이 예외 없이 끝 / revoke 500이어도 예외 없음
  - `AccountDeletionApiTest`: `애플_계정은_코드와_함께_탈퇴하면_해지를_부른다`(`@MockitoBean AppleTokenRevoker`), `코드가_없어도_탈퇴는_된다`, `애플이_아닌_계정은_해지를_부르지_않는다`
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.oauth.Apple*" --tests "project.study.user.AccountDeletionApiTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 구현** (jjwt `Jwts.builder().header().keyId(..).and()...signWith(privateKey, Jwts.SIG.ES256)`, 키는 `KeyFactory.getInstance("EC")` + `PKCS8EncodedKeySpec`)
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 탈퇴 시 애플 토큰을 해지한다 (BY-1126)"
```

### Task 9: 안드로이드 애플 웹 로그인 콜백

**Files:**
- Create: `src/main/java/project/study/user/controller/AppleCallbackController.java`
- Modify: `src/main/java/project/study/config/SecurityConfig.java` (`requestMatchers(HttpMethod.POST, "/api/auth/apple/callback").permitAll()`, 이유 주석: 애플이 결과를 form_post로 보내므로 인증 헤더가 없다, 넘기기만 하고 로그인 처리 없음)
- Test: `src/test/java/project/study/user/AppleCallbackApiTest.java`

**Interfaces:**
- Consumes: `OAuthProperties.Apple.androidCallbackRedirect()` (Task 1)
- Produces: `POST /api/auth/apple/callback` (`consumes = application/x-www-form-urlencoded`, `version = "1"`, `@SecurityRequirements`), 파라미터 `code`, `id_token`, `state`, `user` 모두 선택 → 303 `Location: {androidCallbackRedirect}?` + 값이 있는 파라미터만 URL 인코딩해 붙인다(주소에 이미 `?`가 있으면 `&`). 설정이 비면 404 `NOT_FOUND`

- [ ] **Step 1: 실패 테스트** (`@TestPropertySource(properties = "oauth.apple.android-callback-redirect=https://app.example/apple-callback")`와 설정 없는 클래스 하나)
  - `애플_결과를_정해_둔_딥링크로_303_넘긴다` (Location에 `code=`·`id_token=`·`state=`가 인코딩돼 있음)
  - `인증_없이_부를_수_있다` (`asUser` 없이 401이 아님)
  - `받은_주소로는_넘기지_않는다` (`redirect_uri` 같은 추가 파라미터를 보내도 Location의 앞부분은 설정값)
  - `설정이_없으면_404다`
- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.user.AppleCallbackApiTest"` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `./gradlew test --tests "project.study.config.*"` / Expected: PASS
- [ ] **Step 5: `./gradlew check` 후 커밋**
```bash
git commit -m "feat: 안드로이드 애플 웹 로그인 콜백을 추가한다 (BY-1126)"
```

### Task 10: ADR·정리·리뷰

**Files:**
- Create: `docs/adr/0042-social-login-link-merge-and-account-deletion.md` (기존 ADR 형식)
- Modify: `src/main/java/project/study/user/service/AuthService.java` 클래스 주석의 "파킹" 문구, `// AUTH-DISABLED` 잔재가 남은 곳

ADR에 적을 것: link 단일화와 전환·병합, 병합 규칙 표 요약(겹침 폐기·같은 이름 과목·푸시 토큰 이전·실험 기록 폐기·랭킹 충돌), `UserDataHandler`와 빠뜨림 방지 테스트, 탈퇴 하드 삭제와 `rooms.created_by` NULL, 애플 해지(재인증 코드, 실패해도 탈퇴), 안드로이드 애플 콜백 permitAll의 이유, 잘못된 ID 토큰 400(8월 명세 401에서 바꾼 이유), nonce, 서버에서 비회원 그룹 차단을 안 하는 이유(강제 업데이트), 옛 앱 무인증 경로의 남은 위험과 후속, 병합·탈퇴 뒤 최대 30분 남는 옛 access 토큰은 받아들인다(앱이 바로 토큰을 바꾼다)

- [ ] **Step 1: ADR을 쓰고 잔재 주석을 정리한다**
- [ ] **Step 2: 전체 검증** — Run: `./gradlew check` / Expected: BUILD SUCCESSFUL
- [ ] **Step 3: 커밋**
```bash
git commit -m "docs: 소셜 로그인 link·병합·탈퇴 ADR을 추가한다 (BY-1126)"
```
- [ ] **Step 4: 크로스 리뷰** — `codex exec -s read-only`로 브랜치 전체 리뷰(P1이면 고친 뒤 다시), 인증 변경이라 보안 적대 점검 프롬프트도 한 번. 결과를 사용자에게 보고
- [ ] **Step 5: 퀴즈 게이트** (CLAUDE.md 7) — 구현 코드·흐름 퀴즈 5개, 통과 뒤 PR
