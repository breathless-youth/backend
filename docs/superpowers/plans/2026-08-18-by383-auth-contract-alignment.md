# BY-383 인증 계약 정렬 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `.ai` 리포의 BY-383 소셜 로그인 인증·인가 명세에 맞춰 백엔드 계약을 정렬한다 — link 전환 `isNewUser`, 세션 무겹침 불변식, link 병합 경로, `/api/auth/login` 제거, 소프트 삭제 잔재 제거, 탈퇴 API.

**Architecture:** 인증은 `project.study.user` 도메인(AuthService/UserService), 세션은 `project.study.studysession` 도메인. 병합·탈퇴는 user 도메인 서비스가 `StudySessionRepository`를 직접 사용한다(ArchUnit은 컨트롤러→리포지토리만 금지 — metrics 도메인에 선례 있음). 세션 겹침은 PostgreSQL exclusion constraint를 DB 불변식으로 두고, 병합 로직이 겹침을 사전 해소한다.

**Tech Stack:** Spring Boot 4.1 / Java 25 / PostgreSQL(Flyway) / Testcontainers 2.0 / MockMvcTester / jjwt(HS256)

**Spec:** GitHub `breathless-youth/.ai` 리포 `product/specs/BY-383-소셜로그인-인증인가.md` (PR #6, merged 2026-08-18). 로컬 사본 없음 — `gh api repos/breathless-youth/.ai/contents/product/specs/BY-383-소셜로그인-인증인가.md --jq '.content' | base64 -d`로 조회.

## Global Constraints

- 커밋 전 반드시 `./gradlew check` 통과 (테스트+Spotless+ArchUnit). 포맷 실패 시 `./gradlew spotlessApply` 후 재실행
- Spring Boot **4.x** — Jackson 3(`tools.jackson`), Testcontainers 2.0(`org.testcontainers.postgresql.PostgreSQLContainer`), MockMvc 대신 `MockMvcTester`
- DB 변경은 Flyway 마이그레이션으로만 (`src/main/resources/db/migration/`), `ddl-auto: validate` 고정. 다음 번호는 **V9**
- DTO는 record, 생성자 주입만, 엔티티에 `@Data` 금지
- 커밋 컨벤션 `<type>: <설명>`, 한 커밋 한 목적
- SecurityConfig 변경(Task 4의 permitAll 제거)은 사용자가 스펙+인터뷰로 사전 승인함 (2026-08-18)
- 스펙 확정 정책 (전 태스크 공통 전제):
  - 병합 충돌: **기존 계정 기록 우선 — 겹치는 익명 세션 폐기** (부분 겹침도 통째로)
  - 탈퇴: **하드 삭제** (유저 행·세션·refresh 즉시 DELETE), 소프트 삭제 잔재 제거
  - 겹침 판정: 반개구간 `[started_at, ended_at)` — 맞닿음(끝=시작)은 겹침 아님

---

### Task 1: link 전환 시 `isNewUser: true`

스펙: 전환(=해당 소셜 계정 최초 가입)이면 `isNewUser: true` — 프론트가 프로필 설정 화면으로 보낸다. 현재 link는 항상 `false`.

**Files:**
- Modify: `src/main/java/project/study/user/service/AuthService.java:122` (`new LoginResponse(..., false)` → `true`)
- Test: `src/test/java/project/study/user/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: 기존 `AuthService.linkSocialAccount(Long userId, LinkSocialRequest request)` → `LoginResponse(accessToken, refreshToken, isNewUser)`
- Produces: 전환 경로의 `isNewUser == true`. Task 3이 이 테스트에 stub을 추가하며 재사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`AuthServiceTest`에 추가 (기존 import에 `project.study.user.dto.LinkSocialRequest` 추가):

```java
@Test
void 익명_유저의_link_전환은_isNewUser가_true다() {
    when(tokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    when(tokenVerifier.verify(ID_TOKEN)).thenReturn(USER_INFO);
    User device = new User(Provider.DEVICE, "device-uuid");
    ReflectionTestUtils.setField(device, "id", 5L);
    when(userRepository.findById(5L)).thenReturn(Optional.of(device));

    LoginResponse response = authService.linkSocialAccount(5L, new LinkSocialRequest(Provider.GOOGLE, ID_TOKEN));

    assertThat(response.isNewUser()).isTrue();
    assertThat(jwtUtil.getUserId(response.accessToken())).isEqualTo("5");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.user.service.AuthServiceTest"`
Expected: FAIL — `isNewUser` expected true but was false

- [ ] **Step 3: 최소 구현**

`AuthService.linkSocialAccount` 내부의 `return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), false);`를 `true`로 변경. 주석도 갱신:

```java
// 전환 = 이 소셜 계정의 최초 가입 → isNewUser true (프론트가 프로필 설정 화면으로 보낸다)
return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), true);
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.user.service.AuthServiceTest"`
Expected: PASS

- [ ] **Step 5: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "feat: link 전환 시 isNewUser true 반환 (BY-383 계약)"
```

---

### Task 2: 세션 무겹침 불변식 (V9 exclusion constraint) + 제출 시점 겹침 409

같은 유저의 세션 기간이 겹칠 수 없다는 것을 DB 불변식으로 강제한다. 제출 시점의 동시 기기 충돌(예: 폰 10:00~11:00 저장 후 태블릿 10:05~10:50 제출)이 409로 거절되고, Task 3의 병합이 겹침 해소를 빼먹으면 DB가 막아준다.

**Files:**
- Create: `src/main/resources/db/migration/V9__study_session_no_overlap.sql`
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java` (제약 이름 매핑 추가)
- Modify: `src/main/java/project/study/studysession/controller/StudySessionController.java` (409 스웨거 설명 갱신)
- Test: `src/test/java/project/study/studysession/StudySessionOverlapApiTest.java` (신규)

**Interfaces:**
- Produces: DB 제약 `ex_study_session_user_period` (EXCLUDE gist), `ended_at NOT NULL`. Task 3의 병합은 이 불변식 아래에서 동작한다.
- 위반 시 `DuplicateSessionException`("이미 같은 시간대에 저장된 세션이 있습니다") → 기존 핸들러가 409로 변환.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/project/study/studysession/StudySessionOverlapApiTest.java` 생성. 기존 `StudySessionIdempotencyApiTest`의 인증 패턴(`SecurityMockMvcRequestPostProcessors.authentication()` + `UsernamePasswordAuthenticationToken(userId, null, authorities)`)을 그대로 따른다 — 파일 상단 구조(어노테이션·유저 준비)는 그 파일을 열어 동일하게 복사하고 테스트 본문만 아래로 교체:

```java
@Test
void 기간이_겹치는_별개_제출은_409로_거절된다() {
    Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
    // 첫 제출: base ~ base+60분
    assertThat(submit(base, base.plus(60, ChronoUnit.MINUTES))).hasStatus(HttpStatus.CREATED);
    // 겹치는 제출: base+5분 ~ base+50분 (시작 시각이 달라 유니크는 통과, 기간은 겹침)
    assertThat(submit(base.plus(5, ChronoUnit.MINUTES), base.plus(50, ChronoUnit.MINUTES)))
            .hasStatus(HttpStatus.CONFLICT);
}

@Test
void 끝과_시작이_맞닿는_세션은_겹침이_아니다() {
    Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
    assertThat(submit(base, base.plus(30, ChronoUnit.MINUTES))).hasStatus(HttpStatus.CREATED);
    // 앞 세션의 끝 == 뒷 세션의 시작 → 반개구간이라 허용
    assertThat(submit(base.plus(30, ChronoUnit.MINUTES), base.plus(60, ChronoUnit.MINUTES)))
            .hasStatus(HttpStatus.CREATED);
}
```

`submit(Instant startedAt, Instant endedAt)` 헬퍼: `POST /api/study-sessions`에 `{"startedAt": "...", "endedAt": "...", "studySec": <구간초>, "focusSec": <구간초>, "events": []}` 본문. 세션은 10분 이상·과거 시각이어야 검증을 통과한다 (base를 6시간 전으로 잡는 이유).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionOverlapApiTest"`
Expected: FAIL — 겹치는 제출이 409가 아니라 201로 저장됨

- [ ] **Step 3: V9 마이그레이션 작성**

`V9__study_session_no_overlap.sql`:

```sql
-- 같은 유저의 세션 기간은 겹칠 수 없다 — DB 불변식 (BY-383).
-- 제출 시점의 두 기기 동시 사용 충돌과, 계정 병합 로직이 겹침 해소를 누락하는 경우를 모두 DB가 최종 방어한다.
-- 반개구간 '[)'이므로 끝과 시작이 맞닿는 세션(자정 분할 조각 포함)은 겹침이 아니다.

-- 기존 데이터에 겹침·NULL ended_at이 있으면 아래 문장들이 실패해 배포가 멈추고, 수동 정리 후 재배포한다 (V7 패턴).
-- 겹침 감사 쿼리:
--   SELECT a.id, b.id, a.user_id FROM study_session a
--     JOIN study_session b ON a.user_id = b.user_id AND a.id < b.id
--    WHERE tstzrange(a.started_at, a.ended_at, '[)') && tstzrange(b.started_at, b.ended_at, '[)');
-- NULL 감사 쿼리:
--   SELECT id, user_id, stat_date FROM study_session WHERE ended_at IS NULL;

-- ended_at이 NULL이면 tstzrange가 무한 구간이 되어 이후 모든 세션과 겹친다 — 서버가 항상 채워온 값이라 스키마로 강제한다
ALTER TABLE "study_session" ALTER COLUMN "ended_at" SET NOT NULL;

-- gist 인덱스에서 = 연산(user_id)을 쓰기 위한 확장
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE "study_session"
    ADD CONSTRAINT "ex_study_session_user_period"
    EXCLUDE USING gist ("user_id" WITH =, tstzrange("started_at", "ended_at", '[)') WITH &&);
```

- [ ] **Step 4: 서비스에 제약 이름 매핑 추가**

`StudySessionService.java` — 상수 추가(40행 근처):

```java
private static final String PERIOD_OVERLAP_CONSTRAINT = "ex_study_session_user_period";
```

`create()`의 catch 블록(64행 근처)에 분기 추가 — `STARTED_AT_UNIQUE_CONSTRAINT` 분기 바로 아래:

```java
if (PERIOD_OVERLAP_CONSTRAINT.equalsIgnoreCase(constraint)) {
    throw new DuplicateSessionException("이미 같은 시간대에 저장된 세션이 있습니다");
}
```

컨트롤러는 수정 불필요 — `DuplicateSessionException` catch 후 `findExistingSubmission` 재조회가 빈 목록(겹침은 submissionStartedAt이 다름)이면 그대로 409를 던지는 기존 흐름이 맞는 동작이다.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionOverlapApiTest"`
Expected: PASS

⚠️ 겹침 제출이 409가 아니라 500이면: Hibernate가 exclusion 위반(SQLState 23P01)에서 제약 이름을 못 뽑은 것. 이 경우 `violatedConstraint`에 fallback 추가 — cause 체인의 `org.postgresql.util.PSQLException` `getServerErrorMessage().getConstraint()`를 읽는다.

- [ ] **Step 6: 스웨거 409 설명 갱신**

`StudySessionController.java:106-116`의 409 `@ApiResponse` description을 다음으로 교체하고 예시 추가:

```
description = "시작 시각 또는 기간 충돌 — 같은 시각에 시작했거나 기간이 겹치는 세션이 이미 저장돼 있다 (두 기기 동시 사용 등). 같은 키(재제출)면 저장된 결과를 받지만, 기간만 겹치는 별개 제출은 저장되지 않는다"
```

examples 배열에 추가: `@ExampleObject(name = "기간 겹침", value = "{\"message\": \"이미 같은 시간대에 저장된 세션이 있습니다\"}")`

- [ ] **Step 7: 전체 세션 테스트 회귀 확인** (자정 분할·멱등 재제출이 새 제약과 충돌하지 않는지)

Run: `./gradlew test --tests "project.study.studysession.*"`
Expected: PASS (분할 조각은 맞닿기만 하고, 재제출은 insert 전에 걸러지므로 영향 없음)

- [ ] **Step 8: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "feat: 유저별 세션 기간 무겹침 제약 추가 — 겹치는 제출은 409"
```

---

### Task 3: link 병합 경로 — 익명 기록을 기존 소셜 계정으로 이관

스펙: link 대상 소셜 계정이 **이미 존재하면** 409가 아니라 **병합** — 익명 유저의 세션을 기존 계정으로 이관하되 겹치는 세션은 폐기, 익명 유저는 소멸, 기존 계정 토큰 발급, `isNewUser: false`. 이미 소셜인 유저의 link 호출만 409로 남는다.

**Files:**
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java` (겹침 조회·벌크 이관 추가)
- Modify: `src/main/java/project/study/user/service/AuthService.java` (linkSocialAccount 재구성 + StudySessionRepository 의존 추가)
- Modify: `src/main/java/project/study/user/controller/AuthController.java` (link 스웨거 설명 갱신)
- Modify: `src/test/java/project/study/user/service/AuthServiceTest.java` (Task 1 테스트에 stub 추가)
- Test: `src/test/java/project/study/user/LinkMergeIntegrationTest.java` (신규)

**Interfaces:**
- Consumes: Task 2의 무겹침 불변식 (이관 UPDATE가 제약 위반 없이 통과해야 함 → 겹침 사전 삭제 필수)
- Produces:
  - `StudySessionRepository.findIdsOverlapping(Long sourceUserId, Long targetUserId): List<Long>`
  - `StudySessionRepository.reassignUserId(Long sourceUserId, Long targetUserId): int`
  - `AuthService.linkSocialAccount` — 전환/병합 분기, 동시 전환 경쟁 시 병합 재시도

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/project/study/user/LinkMergeIntegrationTest.java` 생성 (`AuthApiIntegrationTest`와 같은 어노테이션 구조: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)` + `@MockitoBean GoogleTokenVerifier`):

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkMergeIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudySessionRepository studySessionRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        when(googleTokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    }

    @Test
    void 기존_소셜_계정으로_link하면_익명_기록이_병합되고_겹치는_세션은_폐기된다() {
        // 기존 소셜 계정: 기기 A 익명 등록 → 전환 → 세션 base~base+60분 저장
        stubVerifier("sub-merge");
        UserRegisterResponse deviceA = registerDevice();
        LoginResponse social = link(deviceA.accessToken(), "sub-merge");
        Long socialUserId = Long.valueOf(jwtUserId(social.accessToken()));
        Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
        submitSession(social.accessToken(), base, base.plus(60, ChronoUnit.MINUTES));

        // 기기 B 익명: 겹치는 세션(base+30분~base+90분) + 안 겹치는 세션(base+2시간~base+2시간30분)
        UserRegisterResponse deviceB = registerDevice();
        Long anonUserId = deviceB.userId();
        submitSession(deviceB.accessToken(), base.plus(30, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES));
        submitSession(deviceB.accessToken(), base.plus(2, ChronoUnit.HOURS),
                base.plus(2, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES));

        // 기기 B가 같은 소셜 계정으로 link → 병합
        LoginResponse merged = link(deviceB.accessToken(), "sub-merge");

        assertThat(merged.isNewUser()).isFalse();
        assertThat(jwtUserId(merged.accessToken())).isEqualTo(String.valueOf(socialUserId));
        // 겹치는 익명 세션(base+30분 시작)은 폐기 — 어느 유저 소유로도 남지 않는다
        // (자정 분할로 조각 수가 변할 수 있어 세션 개수 대신 시작 시각으로 단언한다)
        assertThat(studySessionRepository.findAll())
                .noneMatch(s -> s.getStartedAt().equals(base.plus(30, ChronoUnit.MINUTES)));
        // 안 겹치는 익명 세션(base+2시간 시작)은 소셜 계정으로 이관
        assertThat(studySessionRepository.findAll())
                .filteredOn(s -> s.getStartedAt().equals(base.plus(2, ChronoUnit.HOURS)))
                .allMatch(s -> s.getUserId().equals(socialUserId));
        // 기존 계정 세션(base 시작)은 유지
        assertThat(studySessionRepository.findAll())
                .anyMatch(s -> s.getStartedAt().equals(base) && s.getUserId().equals(socialUserId));
        // 익명 유저는 소멸
        assertThat(userRepository.findById(anonUserId)).isEmpty();
        // 익명 유저의 refresh 토큰도 폐기 → 재발급 불가
        assertThat(refreshRequest(deviceB.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 기록이_없는_새_기기의_link는_빈_병합으로_기존_계정_토큰을_받는다() {
        stubVerifier("sub-second-device");
        UserRegisterResponse deviceA = registerDevice();
        LoginResponse social = link(deviceA.accessToken(), "sub-second-device");

        UserRegisterResponse deviceB = registerDevice();
        LoginResponse merged = link(deviceB.accessToken(), "sub-second-device");

        assertThat(merged.isNewUser()).isFalse();
        assertThat(jwtUserId(merged.accessToken())).isEqualTo(jwtUserId(social.accessToken()));
    }

    @Test
    void 이미_소셜인_유저가_다시_link하면_409다() {
        stubVerifier("sub-already");
        stubVerifier("sub-other");
        UserRegisterResponse device = registerDevice();
        LoginResponse social = link(device.accessToken(), "sub-already");

        assertThat(linkRequest(social.accessToken(), "sub-other")).hasStatus(HttpStatus.CONFLICT);
    }
}
```

헬퍼 (같은 파일 하단):

```java
private void stubVerifier(String sub) {
    when(googleTokenVerifier.verify("id-token-" + sub))
            .thenReturn(new OAuthUserInfo(Provider.GOOGLE, sub, sub + "@gmail.com"));
}

private UserRegisterResponse registerDevice() {
    MvcTestResult result = mvc.post()
            .uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
            .exchange();
    assertThat(result).hasStatus2xxSuccessful();
    return readBody(result, UserRegisterResponse.class);
}

private MockMvcTester.MockMvcRequestBuilder linkRequest(String accessToken, String sub) {
    return mvc.post()
            .uri("/api/auth/link")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"provider\":\"GOOGLE\",\"idToken\":\"id-token-" + sub + "\"}");
}

private LoginResponse link(String accessToken, String sub) {
    MvcTestResult result = linkRequest(accessToken, sub).exchange();
    assertThat(result).hasStatusOk();
    return readBody(result, LoginResponse.class);
}

private void submitSession(String accessToken, Instant startedAt, Instant endedAt) {
    long sec = Duration.between(startedAt, endedAt).toSeconds();
    MvcTestResult result = mvc.post()
            .uri("/api/study-sessions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"startedAt\":\"" + startedAt + "\",\"endedAt\":\"" + endedAt
                    + "\",\"studySec\":" + sec + ",\"focusSec\":" + sec + ",\"events\":[]}")
            .exchange();
    assertThat(result).hasStatus(HttpStatus.CREATED);
}

private MockMvcTester.MockMvcRequestBuilder refreshRequest(String refreshToken) {
    return mvc.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}");
}

// 응답 토큰의 sub(userId) 파싱용 — 클래스에 @Autowired JwtUtil jwtUtil; 필드를 추가한다
private String jwtUserId(String accessToken) {
    return jwtUtil.getUserId(accessToken);
}

// getContentAsString과 달리 getContentAsByteArray는 checked 예외가 없어 테스트에 throws가 안 번진다
private <T> T readBody(MvcTestResult result, Class<T> type) {
    return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
}
```

주의: 자정(KST) 근처에 테스트가 돌면 `base = now-6h` 세션이 자정 분할될 수 있으나, 위 단언들은 세션 개수가 아니라 시작 시각 기준이라 분할 여부와 무관하게 성립한다 (분할 조각의 첫 조각은 원본 시작 시각을 유지한다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.user.LinkMergeIntegrationTest"`
Expected: FAIL — 두 번째 link가 200이 아니라 409 (현재는 병합 없이 거절)

- [ ] **Step 3: 리포지토리 메서드 추가**

`StudySessionRepository.java`에 추가:

```java
// 병합 전 겹침 판정 — 대상 유저의 세션과 기간(반개구간)이 겹치는 원본 유저 세션 id.
// 겹치는 세션은 이관하지 않고 폐기한다 (기존 계정 기록 우선 — BY-383)
@Query(value = """
        select s.id from study_session s
        where s.user_id = :sourceUserId
          and exists (
            select 1 from study_session t
            where t.user_id = :targetUserId
              and tstzrange(t.started_at, t.ended_at, '[)') && tstzrange(s.started_at, s.ended_at, '[)')
          )
        """, nativeQuery = true)
List<Long> findIdsOverlapping(@Param("sourceUserId") Long sourceUserId, @Param("targetUserId") Long targetUserId);

// 병합 이관 — 겹침 삭제 후에만 호출해야 무겹침 제약(ex_study_session_user_period)을 통과한다
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("update StudySession s set s.userId = :targetUserId where s.userId = :sourceUserId")
int reassignUserId(@Param("sourceUserId") Long sourceUserId, @Param("targetUserId") Long targetUserId);
```

- [ ] **Step 4: AuthService 재구성**

`AuthService`에 의존 추가 — 필드 `private final StudySessionRepository studySessionRepository;`와 생성자 파라미터(`RefreshTokenRepository` 다음 위치), import `project.study.studysession.repository.StudySessionRepository`.

`linkSocialAccount`를 다음으로 교체:

```java
public LoginResponse linkSocialAccount(Long userId, LinkSocialRequest request) {
    // 외부 HTTP 호출(소셜 검증)은 DB 트랜잭션 밖에서 수행한다
    OAuthUserInfo socialInfo = verifierFor(request.provider()).verify(request.idToken());
    try {
        return transactionTemplate.execute(status -> linkTransaction(userId, socialInfo));
    } catch (DataIntegrityViolationException e) {
        // 동시 전환 경쟁에서 패배 → 상대가 만든 소셜 유저가 이제 존재하므로 병합 경로로 재시도
        // (트랜잭션 안에서 catch하면 rollback-only 때문에 실패하므로 반드시 실행 단위를 분리)
        return transactionTemplate.execute(status -> linkTransaction(userId, socialInfo));
    }
}

private LoginResponse linkTransaction(Long userId, OAuthUserInfo socialInfo) {
    User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("존재하지 않는 사용자입니다"));
    if (user.getProvider() != Provider.DEVICE) {
        throw new ConflictException("이미 소셜 계정이 연동된 사용자입니다");
    }
    Optional<User> socialUser =
            userRepository.findByProviderAndProviderUserId(socialInfo.provider(), socialInfo.providerUserId());
    if (socialUser.isEmpty()) {
        // 전환: 식별자 쌍 교체 — userId 유지로 기록이 그대로 이어지고, 옛 deviceId 연결은 자동 해제된다
        user.linkSocialAccount(socialInfo.provider(), socialInfo.providerUserId(), socialInfo.email());
        userRepository.flush();
        TokenPair tokens = issueTokens(user.getId());
        // 전환 = 이 소셜 계정의 최초 가입 → isNewUser true (프론트가 프로필 설정 화면으로 보낸다)
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), true);
    }
    Long targetUserId = mergeInto(user, socialUser.get());
    TokenPair tokens = issueTokens(targetUserId);
    return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), false);
}

/**
 * 익명 유저의 기록을 기존 소셜 계정으로 병합하고 익명 유저를 소멸시킨다.
 * 기존 계정 세션과 기간이 겹치는 익명 세션은 폐기한다(기존 계정 기록 우선) —
 * 겹침을 남기면 이관 UPDATE가 무겹침 제약(ex_study_session_user_period)에 걸린다.
 */
private Long mergeInto(User source, User target) {
    List<Long> overlapping = studySessionRepository.findIdsOverlapping(source.getId(), target.getId());
    if (!overlapping.isEmpty()) {
        // 엔티티 단위 삭제여야 status_event가 cascade로 함께 지워진다
        studySessionRepository.deleteAll(studySessionRepository.findAllById(overlapping));
        studySessionRepository.flush();
    }
    int moved = studySessionRepository.reassignUserId(source.getId(), target.getId());
    refreshTokenRepository.deleteByUserId(source.getId());
    userRepository.delete(source);
    log.info("계정 병합: 익명 {} → 소셜 {} (이관 {}건, 겹침 폐기 {}건)",
            source.getId(), target.getId(), moved, overlapping.size());
    return target.getId();
}
```

클래스에 로거 추가: `@Slf4j` (lombok) 어노테이션을 `@Service` 위에 추가하고 `import lombok.extern.slf4j.Slf4j;`. import 추가: `project.study.common.ConflictException`(이미 있음), `java.util.Optional`(이미 있음).

- [ ] **Step 5: Task 1 단위 테스트에 stub 추가**

`AuthServiceTest.익명_유저의_link_전환은_isNewUser가_true다`에 전환 분기 도달용 stub 추가 (`when(userRepository.findById(5L))...` 다음 줄):

```java
when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-123"))
        .thenReturn(Optional.empty());
```

또한 `AuthService` 생성자 시그니처가 바뀌므로 `AuthServiceTest.setUp`의 생성자 호출에 `@Mock private StudySessionRepository studySessionRepository;`를 추가하고 인자로 전달한다 (`refreshTokenRepository` 다음 위치).

- [ ] **Step 6: 통과 확인**

Run: `./gradlew test --tests "project.study.user.*"`
Expected: PASS (LinkMergeIntegrationTest 3건 + 기존 테스트 회귀 없음)

- [ ] **Step 7: link 스웨거 설명 갱신**

`AuthController.linkSocial`의 `@Operation` description을 스펙 언어로 교체:

```
기존 DEVICE(익명) 유저가 소셜 계정으로 로그인한다. 어느 경우든 이 기기의 기록이 소셜 계정으로 이어진다.

- 해당 소셜 계정이 처음이면(전환): 익명 유저의 식별자만 소셜로 교체된다 — 기존 데이터 전부 유지, isNewUser true.
- 해당 소셜 계정이 이미 있으면(병합): 이 기기 익명 유저의 기록이 기존 계정으로 이관되고 익명 유저는 소멸한다.
  기존 계정의 세션과 시간이 겹치는 익명 세션은 폐기된다(기존 계정 기록 우선). isNewUser false.

병합은 되돌릴 수 없다 — 앱은 진행 전 고지 문구를 노출한다.
```

409 `@ApiResponse` description을 `"이미 소셜 계정이 연동된 사용자가 link를 호출함 (비정상 흐름) — 현재 토큰을 유지하고 로그인 화면을 닫는다"`로 교체.

- [ ] **Step 8: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "feat: link 병합 경로 추가 — 익명 기록을 기존 소셜 계정으로 이관"
```

---

### Task 4: `POST /api/auth/login` 제거 — 로그인 진입을 link로 단일화

스펙: 모든 기기는 로그인 시점에 익명 유저이므로 로그인 API는 link 하나다. `/api/auth/login`은 계약 제외·코드 제거 대상. SecurityConfig permitAll에서도 뺀다 (사용자 승인 완료).

**Files:**
- Modify: `src/main/java/project/study/user/controller/AuthController.java` (login 메서드 삭제)
- Modify: `src/main/java/project/study/user/service/AuthService.java` (login/loginTransaction 삭제)
- Delete: `src/main/java/project/study/user/dto/LoginRequest.java`
- Modify: `src/main/java/project/study/config/SecurityConfig.java:44`
- Modify: `src/test/java/project/study/user/AuthApiIntegrationTest.java` (login 기반 헬퍼를 register+link로 재편)
- Modify: `src/test/java/project/study/user/service/AuthServiceTest.java` (login 전용 테스트 삭제·이전)

**Interfaces:**
- Consumes: Task 3의 link 전환·병합 (테스트 헬퍼가 register+link 조합으로 토큰을 얻는다)
- Produces: `/api/auth/login` 부재 (미인증 401 / 인증 시 404). `LoginResponse`는 link 응답으로 유지.

- [ ] **Step 1: 실패하는 테스트 작성**

`AuthApiIntegrationTest`에 추가:

```java
@Test
void 제거된_login_엔드포인트는_인증_없이_접근하면_401이다() {
    assertThat(mvc.post()
                    .uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"provider\":\"GOOGLE\",\"idToken\":\"any\"}"))
            .hasStatus(HttpStatus.UNAUTHORIZED);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.user.AuthApiIntegrationTest"`
Expected: FAIL — 아직 permitAll이라 401이 아님

- [ ] **Step 3: 프로덕션 코드 제거**

1. `AuthController`: `login` 메서드와 그 `@Operation`/`@ApiResponse` 블록(36-64행) 삭제, `LoginRequest` import 삭제. 클래스 `@Tag` description을 `"소셜 로그인(link)·토큰 재발급·로그아웃 API 모음 — 로그인 진입은 link 하나다 (BY-383)"`로 갱신.
2. `AuthService`: `login()`, `loginTransaction()` 메서드와 `LoginRequest` import 삭제.
3. `LoginRequest.java` 파일 삭제.
4. `SecurityConfig.java:44`: `.requestMatchers("/api/auth/login", "/api/auth/refresh")` → `.requestMatchers("/api/auth/refresh")`.

- [ ] **Step 4: 테스트 재편**

`AuthApiIntegrationTest`:
- `loginRequest`/`login` 헬퍼를 register+link 조합으로 교체:

```java
private UserRegisterResponse registerDevice() {
    MvcTestResult result = mvc.post()
            .uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
            .exchange();
    assertThat(result).hasStatus2xxSuccessful();
    return readBody(result, UserRegisterResponse.class);
}

private LoginResponse login(String sub) {
    stubVerifier(sub);
    UserRegisterResponse device = registerDevice();
    MvcTestResult result = mvc.post()
            .uri("/api/auth/link")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"provider\":\"GOOGLE\",\"idToken\":\"id-token-" + sub + "\"}")
            .exchange();
    assertThat(result).hasStatusOk();
    return readBody(result, LoginResponse.class);
}
```

import 추가: `java.util.UUID`, `project.study.user.dto.UserRegisterResponse`.
- `첫_로그인은_유저를_생성하고_isNewUser가_true_재로그인은_false다` → link 의미로 재작성 (첫 link=전환 `isNewUser true`, 새 익명 기기의 재link=병합 `false`):

```java
@Test
void 첫_link는_전환으로_isNewUser가_true_같은_계정_재link는_병합으로_false다() {
    assertThat(login("sub-new").isNewUser()).isTrue();
    assertThat(login("sub-new").isNewUser()).isFalse();
}
```

- `구글이_거부한_ID_토큰이면_401이다`: uri를 `/api/auth/link`로 바꾸고 익명 access 토큰 헤더 추가 (`registerDevice()` 사용).

`AuthServiceTest`:
- login 전용 테스트 3건 삭제: `신규_유저_로그인은...`, `기존_유저_로그인은...`, `동시_첫_로그인_경쟁에서...` — 동시 가입 경쟁 처리는 link의 병합 재시도(catch 후 재실행)로 옮겨졌으므로, 같은 패턴의 단위 테스트를 link 기준으로 원하면 `linkSocialAccount`가 `DataIntegrityViolationException` 후 두 번째 실행에서 병합 경로를 타는지 검증하는 테스트로 대체한다 (선택).
- `refresh_토큰은_원문이_아닌_SHA256_해시로_저장된다`는 login 대신 `linkSocialAccount` 호출로 재작성 (DEVICE 유저 `findById` stub + `findByProviderAndProviderUserId → empty` stub, Task 1 테스트와 동일 패턴).
- `LoginRequest` import 삭제.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "project.study.user.*"`
Expected: PASS

- [ ] **Step 6: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "feat: 로그인 진입을 link로 단일화 — /api/auth/login 제거"
```

---

### Task 5: 소프트 삭제 잔재 제거 (V10)

스펙 정책은 "즉시 전부 삭제"(하드 삭제)이므로 `UserStatus.DELETE`/`deleteAt`/`rejectIfDeleted` 소프트 삭제 잔재를 제거한다. 잔재를 남기면 탈퇴 계정 재로그인 = 신규 가입 계약과 충돌한다.

**Files:**
- Delete: `src/main/java/project/study/user/entity/UserStatus.java`
- Modify: `src/main/java/project/study/user/entity/User.java` (status·deleteAt 필드 제거)
- Modify: `src/main/java/project/study/user/service/AuthService.java` (rejectIfDeleted 제거)
- Create: `src/main/resources/db/migration/V10__drop_user_soft_delete_columns.sql`

**Interfaces:**
- Produces: `User` 엔티티에서 status/deleteAt 부재. Task 6의 하드 삭제가 이 상태를 전제한다.

- [ ] **Step 1: 프로덕션 코드 제거**

1. `User.java`: `status`·`deleteAt` 필드, `UserStatus` import, 생성자의 `this.status = UserStatus.ACTIVE;` 줄 삭제.
2. `UserStatus.java` 파일 삭제.
3. `AuthService.java`: `rejectIfDeleted` 메서드와 호출 2곳(refresh·linkTransaction), `UserStatus` import 삭제. (`InvalidOAuthTokenException` import는 `verifierFor`가 계속 쓰므로 유지.)

- [ ] **Step 2: V10 마이그레이션 작성**

```sql
-- 탈퇴는 하드 삭제("즉시 전부 삭제" 정책 — BY-383)로 확정되어 소프트 삭제 잔재를 제거한다.
-- status는 ACTIVE 단일값만 존재했고 delete_at은 어디서도 기록된 적 없다 — 데이터 손실 없음.
ALTER TABLE "users" DROP COLUMN "status";
ALTER TABLE "users" DROP COLUMN "delete_at";
```

- [ ] **Step 3: 전체 테스트로 회귀 확인** (ddl-auto validate가 컬럼 불일치를 잡는지 포함)

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 4: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "refactor: 소프트 삭제 잔재 제거 — 탈퇴는 하드 삭제로 확정"
```

---

### Task 6: `DELETE /api/users/me` — 탈퇴 (하드 삭제)

스펙: 인증 필요, 204, refresh 전량 즉시 폐기(모든 기기 로그아웃), 유저 귀속 데이터 즉시 삭제, 익명 유저도 사용 가능. Apple 5.1.1(v) 요건으로 V1.2 출시 필수.

**Files:**
- Modify: `src/main/java/project/study/user/controller/UserController.java`
- Modify: `src/main/java/project/study/user/service/UserService.java`
- Test: `src/test/java/project/study/user/AccountDeletionApiTest.java` (신규)

**Interfaces:**
- Consumes: `StudySessionRepository.deleteByUserId(Long)` (기존 — 엔티티 단위 삭제라 status_event cascade), `RefreshTokenRepository.deleteByUserId(Long)` (기존)
- Produces: `UserService.deleteAccount(Long userId): void`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/project/study/user/AccountDeletionApiTest.java` 생성 — LinkMergeIntegrationTest와 같은 구조(어노테이션·`registerDevice`/`link`/`submitSession`/`refreshRequest`/`stubVerifier` 헬퍼 복사):

```java
@Test
void 탈퇴하면_유저와_세션이_삭제되고_refresh가_전량_폐기된다() {
    UserRegisterResponse device = registerDevice();
    Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
    submitSession(device.accessToken(), base, base.plus(30, ChronoUnit.MINUTES));

    assertThat(mvc.delete()
                    .uri("/api/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken()))
            .hasStatus(HttpStatus.NO_CONTENT);

    assertThat(userRepository.findById(device.userId())).isEmpty();
    assertThat(studySessionRepository.findAll())
            .noneMatch(s -> s.getUserId().equals(device.userId()));
    assertThat(refreshRequest(device.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
}

@Test
void 탈퇴한_소셜_계정으로_재로그인하면_신규_가입이다() {
    stubVerifier("sub-rejoin");
    UserRegisterResponse deviceA = registerDevice();
    LoginResponse social = link(deviceA.accessToken(), "sub-rejoin");

    assertThat(mvc.delete()
                    .uri("/api/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + social.accessToken()))
            .hasStatus(HttpStatus.NO_CONTENT);

    // 같은 소셜 ID로 재로그인 → 전환 경로(신규 가입), 이전 데이터 미복구
    UserRegisterResponse deviceB = registerDevice();
    assertThat(link(deviceB.accessToken(), "sub-rejoin").isNewUser()).isTrue();
}

@Test
void 인증_없이_탈퇴를_호출하면_401이다() {
    assertThat(mvc.delete().uri("/api/users/me")).hasStatus(HttpStatus.UNAUTHORIZED);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.user.AccountDeletionApiTest"`
Expected: FAIL — DELETE /api/users/me가 404/405

- [ ] **Step 3: 구현**

`UserService`에 의존 추가 (`@RequiredArgsConstructor`이므로 필드만): `private final StudySessionRepository studySessionRepository;`, `private final RefreshTokenRepository refreshTokenRepository;` + import. 메서드 추가:

```java
/**
 * 탈퇴 — "즉시 전부 삭제" 정책(BY-383). refresh 전량 폐기로 모든 기기가 로그아웃되고,
 * 세션 기록은 status_event까지 함께 삭제된다(엔티티 단위 삭제의 cascade).
 * 이미 삭제된 유저면 아무것도 안 한다(멱등) — 삭제 직후 중복 호출이 실패하지 않게 한다.
 */
@Transactional
public void deleteAccount(Long userId) {
    studySessionRepository.deleteByUserId(userId);
    refreshTokenRepository.deleteByUserId(userId);
    userRepository.deleteById(userId);
}
```

`UserController`에 추가 (import: `org.springframework.web.bind.annotation.DeleteMapping`, `org.springframework.security.core.annotation.AuthenticationPrincipal`, `io.swagger.v3.oas.annotations.responses.ApiResponse` 등 기존 스타일):

```java
@Operation(summary = "계정 삭제 (탈퇴)", description = """
                계정과 모든 데이터를 즉시 삭제한다 — 세션 기록, refresh 토큰 전량(모든 기기 로그아웃).
                삭제는 되돌릴 수 없으며, 같은 소셜 계정으로 다시 로그인하면 신규 가입으로 처리된다.
                익명(DEVICE) 유저도 "모든 데이터 삭제" 용도로 사용할 수 있다.""")
@ApiResponse(responseCode = "204", description = "삭제 완료 — 응답 본문 없음")
@ApiResponse(
        responseCode = "401",
        description = "access 토큰 누락 또는 무효 — 응답 본문 없음",
        content = @Content(schema = @Schema(hidden = true)))
@DeleteMapping("/me")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void deleteMe(@AuthenticationPrincipal Long userId) {
    userService.deleteAccount(userId);
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.user.*"`
Expected: PASS

- [ ] **Step 5: check 후 커밋**

```bash
./gradlew check
git add -A && git commit -m "feat: 계정 삭제(탈퇴) API 추가 — 유저 데이터 즉시 전부 삭제"
```

---

### Task 7: ADR 0014 작성

**Files:**
- Create: `docs/adr/0014-align-auth-contract-with-by383.md`

- [ ] **Step 1: ADR 작성** (기존 ADR 형식 — `docs/adr/0013-reintroduce-social-login.md` 헤더 구조를 따른다)

내용에 반드시 포함:
- **결정 4건**: ① 로그인 진입을 link로 단일화(`/api/auth/login` 제거 — 첫 화면이 항상 비로그인이라 로그인 시점에 익명 토큰 상시 존재) ② link 병합 경로(기존 소셜 계정 존재 시 익명 기록 이관, **겹치는 세션은 기존 계정 우선으로 폐기** — 순공시간·스트릭 왜곡 방지) ③ 유저별 세션 무겹침을 exclusion constraint(`ex_study_session_user_period`, btree_gist, 반개구간)로 DB 불변식화 — 제출 시점 충돌과 병합 누락을 모두 방어 ④ 탈퇴는 하드 삭제(즉시 전부 삭제, 재로그인=신규 가입) — 소프트 삭제 잔재 제거
- 근거 문서: `.ai` 리포 `product/specs/BY-383-소셜로그인-인증인가.md`
- 겹침 스킵 vs 클립(잘라내기) 트레이드오프: 부분 겹침의 꼬리 손실을 감수하고 단순한 규칙 선택, 병합 로그로 폐기 건수를 관측하다 유의미하면 클립으로 업그레이드

- [ ] **Step 2: check 후 커밋**

```bash
./gradlew check
git add docs/adr/0014-align-auth-contract-with-by383.md
git commit -m "docs: BY-383 인증 계약 정렬 ADR 추가"
```

---

## 마무리 게이트 (플랜 실행 후, 커밋 컨벤션과 별개로 반드시)

1. `./gradlew check` 전체 통과 재확인
2. **퀴즈 게이트**: 구현 코드·흐름 퀴즈 5개를 사용자에게 출제, 통과 전 추가 커밋/PR 금지 (CLAUDE.md 작업 규칙 7)
3. **크로스 코드체크**: `/codex review` 독립 2차 리뷰 (P1 발견 시 FAIL 게이트) + 인증·보안 변경이므로 `/codex challenge security` 적대적 점검
4. PR: base `dev`, 제목 `[feat] BY-383 소셜 로그인 계약 정렬 — link 단일화·병합·탈퇴`
