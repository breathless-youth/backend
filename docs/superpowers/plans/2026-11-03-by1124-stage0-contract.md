# BY-1124 0단계: 그룹 공부방 계약 PR 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE가 타입을 만들 수 있게 그룹 공부방의 REST 계약(Swagger, 로직 전 501)과 공부방 STOMP 계약(`websocket.html`)을 dev에 올리고, 시험·목표 9개·글자 수 규칙·연락처 판정·이유 코드처럼 계약에 딸린 순수 규칙은 실제로 동작하게 만든다.

**Architecture:** 새 도메인 패키지 `project.study.group`(그룹·멤버·신청)과 `project.study.block`(차단)에 컨트롤러·DTO record·서비스 스텁을 둔다. 서비스 메서드는 모두 `NotImplementedException`(501)을 던지고, 1~6단계가 이 메서드 몸통을 채운다. 공통 규칙(이유 코드 예외, 글자 단위 길이 검증, 시험·목표 enum)은 `common/`에 둔다.

**Tech Stack:** Spring Boot 4.1, Java 25, springdoc(OpenAPI), Bean Validation, JUnit 5 + AssertJ + MockMvcTester, Testcontainers PostgreSQL

**Spec:** `docs/superpowers/specs/2026-11-03-by1124-group-study-rooms-design.md` (이 계획은 그 문서의 "계약" 절을 코드로 옮긴다. 둘 다 읽는다)

**범위:** 설계의 "전달 순서" 0단계만. 1~6단계는 앞 단계가 머지된 뒤 단계마다 계획을 따로 쓴다. 이번 단계에는 마이그레이션이 없다.

## Global Constraints

- 브랜치 `feat/BY-1124-group-rooms` (origin/dev `b49b680`에서 분기, 설계 문서 커밋 `fcc0768` 위)
- 새 매핑은 모두 `version = "1"`을 명시한다 (ADR-0015 BY-721: 새 경로는 1)
- Swagger 태그 이름은 정확히 `그룹` — 그룹·멤버·신청·차단 컨트롤러 모두 이 태그 하나
- 에러 본문은 `ErrorResponse{code, message}`. FE는 `code`로만 분기한다
- 수치: 그룹+신청 5, 신청 3, 그룹장 2, 멤버 6, 내보낸 뒤 3일, 신청 만료 3일
- 글자 수 (NFC + 앞뒤 공백 제거 뒤 grapheme): 그룹 이름 1~20, 그룹 소개 0~500, 신청 소개 글 1~50, 신고 사유 0~100
- 시험·목표 9개, 이 순서: `CSAT, SCHOOL_EXAM, UNIVERSITY_EXAM, CIVIL_SERVICE, CERTIFICATE, LANGUAGE, JOB, PROFESSIONAL, ETC` / 라벨 `수능, 내신, 대학 시험, 공무원, 자격증, 어학, 취업, 전문직, 기타`
- 초대코드 알파벳 `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, 6자리, 대소문자 무시 (Swagger 설명에만 쓴다. 생성은 3단계)
- 새 의존성 없음, `SecurityConfig` 변경 없음 (새 경로는 기존 `anyRequest().authenticated()`에 걸린다)
- Jackson 3(`tools.jackson`), `@Profile` 금지, 생성자 주입(`@RequiredArgsConstructor`), DTO는 record, 엔티티 없음
- 커밋마다 `./gradlew spotlessApply check` 통과, Conventional Commits 한국어 제목, 본문 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- PR 올리기 전 한 번: CLAUDE.md 퀴즈 게이트(사용자가 생략을 명시하면 생략), `codex exec -s read-only`로 2차 리뷰 (`/codex` 스킬 없음)
- 서브에이전트는 브랜치를 바꾸지 않는다 (`git checkout` 금지). 커밋 전에 `git rev-parse --abbrev-ref HEAD`가 `feat/BY-1124-group-rooms`인지 확인한다

## Review Focus

1. 고정 경로와 변수 경로가 겹친다: `GET /api/groups/me`, `GET /api/groups/public`, `POST /api/groups/join`이 `/{groupId}`의 Long 변환 실패(400)로 새면 안 된다 → Task 5 테스트 `고정_경로가_groupId_경로보다_먼저_잡힌다`
2. 인증 없이 부르면 501이 아니라 401이어야 한다 → Task 5 테스트 `인증_없으면_401이다`
3. 분해형(NFD) 한글과 이모지 ZWJ 조합이 글자 수를 부풀리면 안 된다 → Task 2 테스트 `NFD_한글과_ZWJ_이모지는_한_글자다`
4. 공부 글이 연락처로 오판되면 안 된다(`9급`, `토익 900`, `D-100`, `2027 수능`, `node.js`) → Task 4 통과 예시 표
5. 프로필 허용 값·enum·리포트 라벨이 서로 어긋나면 안 된다 → Task 3 테스트 `프로필_허용값과_리포트_라벨이_enum과_같다`

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `common/exception/ErrorCode.java` (수정) | 이유 코드 15개 + `NOT_IMPLEMENTED` 추가 |
| `common/exception/BadRequestException.java`, `ConflictException.java` (수정) | `ErrorCode`를 받는 생성자 추가 (기존 생성자는 `BAD_REQUEST`/`CONFLICT`) |
| `common/exception/ForbiddenException.java` (생성) | 403, 코드 필수 |
| `common/exception/NotImplementedException.java` (생성) | 501, 코드 `NOT_IMPLEMENTED` |
| `common/exception/GlobalExceptionHandler.java` (수정) | 위 예외들의 코드를 그대로 내림 |
| `common/text/Graphemes.java` (생성) | NFC+strip 정규화, grapheme 나누기·세기·첫 글자 |
| `common/text/TextLength.java`, `TextLengthValidator.java` (생성) | 글자 단위 길이 제약 |
| `user/nickname/Nicknames.java` (수정) | 정규화·grapheme을 `Graphemes`에 맡긴다 (동작 변화 없음) |
| `common/category/StudyCategory.java` (생성) | 시험·목표 9개와 라벨 |
| `user/dto/ProfileUpdateRequest.java`, `metrics/report/Categories.java` (수정) | 9개로 늘림 |
| `group/ContactDetector.java` (생성) | 연락처·링크 판정 (위키 6-1) |
| `group/GroupVisibility.java`, `GroupRole.java`, `DeskStatus.java`, `ApplyState.java`, `JoinVia.java` (생성) | 계약 enum |
| `group/dto/*.java` (생성) | 요청·응답 record |
| `group/controller/GroupController.java`, `GroupMemberController.java`, `GroupApplicationController.java` (생성) | Swagger 계약 |
| `group/service/GroupService.java`, `GroupMemberService.java`, `GroupApplicationService.java` (생성) | 501 스텁 (1~6단계가 채움) |
| `block/controller/BlockController.java`, `block/service/BlockService.java`, `block/dto/*.java` (생성) | 차단 목록·해제 계약 |
| `resources/docs/websocket.html` (수정) | 그룹 공부방 절 + 변경 이력 |
| `docs/adr/0040-group-study-rooms.md` (생성) | 설계 결정 기록 |

경로 앞의 `src/main/java/project/study/`는 생략했다. 테스트는 `src/test/java/project/study/` 아래 같은 패키지에 둔다.

---

### Task 1: 이유 코드와 예외

**Files:**
- Modify: `src/main/java/project/study/common/exception/ErrorCode.java`, `BadRequestException.java`, `ConflictException.java`, `GlobalExceptionHandler.java`
- Create: `src/main/java/project/study/common/exception/ForbiddenException.java`, `NotImplementedException.java`
- Test: `src/test/java/project/study/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `ErrorCode`에 추가 (각각 Javadoc 한 줄, 뜻은 설계의 "못 하는 이유 코드" 표): `RECENTLY_KICKED, GROUP_SLOT_LIMIT, APPLICATION_LIMIT, GROUP_FULL, OWNED_GROUP_LIMIT, CONTACT_NOT_ALLOWED, OWNER_CANNOT_LEAVE, ALREADY_APPLIED, ALREADY_MEMBER, APPLICATION_NOT_PENDING, GROUP_NOT_FOUND, GROUP_MEMBER_NOT_FOUND, NOT_GROUP_MEMBER, NOT_GROUP_OWNER, NOT_IMPLEMENTED` (`INVITE_CODE_NOT_FOUND`는 기존 것을 쓴다)
  - `new BadRequestException(ErrorCode code, String message)`, `new ConflictException(ErrorCode code, String message)`, 둘 다 `ErrorCode getCode()`
  - `new ForbiddenException(ErrorCode code, String message)` → 403
  - `new NotImplementedException()` → 501 `{code: NOT_IMPLEMENTED, message: "아직 준비 중인 기능입니다"}`

- [ ] **Step 1: 실패하는 테스트 쓰기** — `ThrowingController`에 경로 4개(`/test/conflict-coded`가 `new ConflictException(ErrorCode.GROUP_FULL, "자리가 없어요")`, `/test/bad-request-coded`가 `CONTACT_NOT_ALLOWED`, `/test/forbidden`이 `NOT_GROUP_OWNER`, `/test/not-implemented`)를 더하고 테스트 4개:
  - `ConflictException의_코드를_그대로_내려준다` — 409, `$.code == "GROUP_FULL"`, `$.message == "자리가 없어요"`
  - `BadRequestException의_코드를_그대로_내려준다` — 400, `$.code == "CONTACT_NOT_ALLOWED"`
  - `ForbiddenException은_403과_코드로_응답한다` — 403, `$.code == "NOT_GROUP_OWNER"`
  - `NotImplementedException은_501로_응답한다` — 501, `$.code == "NOT_IMPLEMENTED"`, `$.message == "아직 준비 중인 기능입니다"`
  - 기존 `ConflictException은_409로_응답한다`에 `$.code == "CONFLICT"` 단언을 더한다 (기존 생성자의 기본 코드 회귀 방지)
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.common.GlobalExceptionHandlerTest"` → 새 테스트 컴파일 실패
- [ ] **Step 3: 구현** — 예외 클래스와 `ErrorCode`를 만들고, 핸들러의 400·409 처리를 `e.getCode()`로 바꾸고 403·501 핸들러를 더한다 (`NotFoundException` 패턴과 같다)
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `./gradlew spotlessApply check` 통과 뒤 `feat: 이유 코드를 400·403·409·501 응답에 싣는다 (BY-1124)`

### Task 2: 글자 단위 길이 검증

**Files:**
- Create: `src/main/java/project/study/common/text/Graphemes.java`, `TextLength.java`, `TextLengthValidator.java`
- Modify: `src/main/java/project/study/user/nickname/Nicknames.java` (`normalize`·`length`·`initialOf`·`graphemes`가 `Graphemes`를 부른다. 공개 메서드 시그니처는 그대로)
- Test: `src/test/java/project/study/common/text/GraphemesTest.java`, `TextLengthValidatorTest.java`

**Interfaces:**
- Produces:
  - `Graphemes.normalize(String raw): String` — null은 null, NFC + `strip()`
  - `Graphemes.split(String text): List<String>`, `Graphemes.count(String text): int`, `Graphemes.first(String text): String` (빈 문자열이면 `""`)
  - `@TextLength(min = 0, max)` — 필드·파라미터에 붙인다. null은 통과(필수는 `@NotNull`을 같이 단다). 정규화한 뒤 grapheme 수가 [min, max]여야 하고, 원문 UTF-16 길이가 `max * 16`을 넘으면 실패한다 (거대 입력 방어). 기본 메시지 `"{min}~{max}자여야 합니다"`

- [ ] **Step 1: 실패하는 테스트 쓰기**
  - `GraphemesTest`
    - `NFD_한글과_ZWJ_이모지는_한_글자다` — `count(normalize("한"))`(NFD "한") == 1, `count("👨‍👩‍👧")` == 1, `count("🇰🇷")` == 1
    - `앞뒤_공백을_빼고_NFC로_맞춘다` — `normalize("  공부 ")` == `"공부"`, `normalize(null)` == null
    - `첫_글자는_이모지를_자르지_않는다` — `first("👨‍👩‍👧공부")` == `"👨‍👩‍👧"`
  - `TextLengthValidatorTest` (Bean Validation `Validator`로 record `Sample(@TextLength(min = 1, max = 20) String name)` 검증)
    - `이모지_조합_20개는_통과한다` — `"👨‍👩‍👧".repeat(20)` 위반 0개
    - `21자는_실패한다` — `"가".repeat(21)` 위반 1개
    - `공백만은_min_1에서_실패한다` — `"   "` 위반 1개
    - `null은_통과한다` — 위반 0개
    - `원문이_너무_길면_실패한다` — `"a".repeat(20 * 16 + 1)` 위반 1개
  - 기존 `user/nickname` 테스트는 손대지 않고 그대로 통과해야 한다 (위임 회귀 확인)
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.common.text.*"` → 컴파일 실패
- [ ] **Step 3: 구현** — `Nicknames`의 `BreakIterator.getCharacterInstance(Locale.ROOT)` 코드를 `Graphemes`로 옮기고 `Nicknames`가 위임한다
- [ ] **Step 4: 통과 확인** — `./gradlew test --tests "project.study.common.text.*" --tests "project.study.user.*"` → PASS
- [ ] **Step 5: 커밋** — `feat: 글자 단위 길이 검증을 공통으로 뺀다 (BY-1124)`

### Task 3: 시험·목표 9개

**Files:**
- Create: `src/main/java/project/study/common/category/StudyCategory.java`
- Modify: `src/main/java/project/study/user/dto/ProfileUpdateRequest.java:21-25`, `src/main/java/project/study/metrics/report/Categories.java`
- Test: `src/test/java/project/study/common/category/StudyCategoryTest.java`, `src/test/java/project/study/user/ProfileApiTest.java`

**Interfaces:**
- Produces: `enum StudyCategory { CSAT, SCHOOL_EXAM, UNIVERSITY_EXAM, CIVIL_SERVICE, CERTIFICATE, LANGUAGE, JOB, PROFESSIONAL, ETC }`, `String label()` (Global Constraints의 라벨). `users.category`는 지금처럼 String이다 (엔티티·컬럼 변경 없음)

- [ ] **Step 1: 실패하는 테스트 쓰기**
  - `StudyCategoryTest.프로필_허용값과_리포트_라벨이_enum과_같다`
    - `ProfileUpdateRequest.class.getDeclaredField("category").getAnnotation(Pattern.class).regexp()`를 `|`로 나눈 집합이 `StudyCategory` 이름 집합과 같다 (`@Pattern`은 `RECORD_COMPONENT` 대상이 아니라 `RecordComponent`로는 읽히지 않는다)
    - 각 값의 `Categories.label(name)`이 `StudyCategory.label()`과 같다
  - `ProfileApiTest.카테고리를_내신과_대학_시험으로_바꿀_수_있다` — `{"category": "SCHOOL_EXAM"}` PATCH → 200, 다시 GET하면 `$.category == "SCHOOL_EXAM"`. `UNIVERSITY_EXAM`도 같다
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.common.category.*" --tests "project.study.user.ProfileApiTest"` → FAIL
- [ ] **Step 3: 구현**
  - `ProfileUpdateRequest`의 `@Pattern` 정규식과 `@Schema.allowableValues`에 두 값을 더한다 (애노테이션 값은 상수여야 해서 enum에서 만들 수 없다. 위 테스트가 어긋남을 잡는다)
  - `Categories`의 라벨 표를 `StudyCategory.values()`에서 채운다 (리포트 순서도 enum 순서)
- [ ] **Step 4: 통과 확인** — 같은 명령, `--tests "project.study.metrics.*"` 추가 → PASS
- [ ] **Step 5: 커밋** — `feat: 시험·목표에 내신과 대학 시험을 더한다 (BY-1124)`

### Task 4: 연락처·링크 판정

**Files:**
- Create: `src/main/java/project/study/group/ContactDetector.java`
- Test: `src/test/java/project/study/group/ContactDetectorTest.java`

**Interfaces:**
- Produces: `ContactDetector.containsContact(String text): boolean` (static, null·빈 문자열은 false). 1단계(그룹 만들기·설정)와 2단계(신청)의 서비스가 쓴다

- [ ] **Step 1: 실패하는 테스트 쓰기** — `@ParameterizedTest` 두 개. 이 표는 FE에 그대로 넘기므로 값을 바꾸지 않는다

  `막는다` (true):
  | 입력 | 규칙 |
  |---|---|
  | `https://open.kakao.com/o/abc` | 링크 |
  | `www.studywith.me` | www |
  | `studygroup.kr 으로 와요` | 도메인 |
  | `naver.com` | 도메인 |
  | `kim1234@gmail.com` | 이메일 |
  | `010-1234-5678` | 숫자 9자리 이상 (`-` 포함) |
  | `010 1234 5678` | 숫자 9자리 이상 (띄어쓰기 포함) |
  | `123456789` | 숫자 9자리 |
  | `@study_kim` | `@` 아이디 |
  | `카톡 kim1234` | 연락 수단 말 + 아이디 |
  | `인스타: study_kim` | 연락 수단 말 + 아이디 |
  | `오픈채팅 abc123` | 연락 수단 말 + 아이디 |
  | `텔레그램 kimstudy` | 연락 수단 말 + 아이디 |
  | `디엠 kim_99` | 연락 수단 말 + 아이디 |

  `통과시킨다` (false):
  | 입력 |
  |---|
  | `9급` |
  | `토익 900` |
  | `D-100` |
  | `2027 수능` |
  | `2026-11-03부터 시작` (숫자 8자리) |
  | `하루 10시간 공부해요` |
  | `node.js 공부 모임` |
  | `카톡 말고 여기서 공부해요` |
  | `인스타 안 해요` |
  | `3.5시간` |

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.group.ContactDetectorTest"` → 컴파일 실패
- [ ] **Step 3: 구현** — 판정은 소문자로 바꾼 뒤 아래 순서로 본다. 정확한 정규식은 표를 통과하는 한 구현자가 정한다
  - 링크: `https?://` 또는 `www.`
  - 도메인: `[a-z0-9-]+\.(com|net|org|kr|co|io|me|ly|gg|so|app|dev|link|site|xyz|info|biz|shop)\b` (최상위 도메인 허용 목록이라 `node.js`는 통과)
  - 이메일: `[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}`
  - 숫자: 숫자 사이에 `-`·띄어쓰기만 끼운 숫자열에서 숫자가 9개 이상
  - `@` 아이디: `@[a-z0-9._]{2,}`
  - 연락 수단 말(`카톡, 카카오톡, 오픈채팅, 오픈톡, 인스타, 인스타그램, 텔레그램, 텔레, 디엠, dm, 라인, 디코, 디스코드`) 바로 뒤(구분자 `\s:：-` 0~3자)에 `[a-z0-9._]{3,}`이 오면 막는다
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `feat: 연락처·링크 판정 규칙을 추가한다 (BY-1124)`

### Task 5: 그룹 계약 — 그룹·공개 그룹·알림

**Files:**
- Create: `src/main/java/project/study/group/{GroupVisibility,GroupRole,DeskStatus,ApplyState}.java`
- Create: `src/main/java/project/study/group/dto/` — `GroupCreateRequest`, `GroupCreateResponse`, `GroupUpdateRequest`, `GroupNotificationRequest`, `GroupMemberDesk`, `MyGroupsResponse`(중첩 `MyGroupCard`, `GroupLimits`), `GroupHomeResponse`, `PublicGroupPageResponse`(중첩 `PublicGroupCard`), `PublicGroupDetailResponse`(중첩 `GroupOwnerProfile`, `PendingApplicationRef`)
- Create: `src/main/java/project/study/group/controller/GroupController.java`, `src/main/java/project/study/group/service/GroupService.java`
- Test: `src/test/java/project/study/group/GroupContractApiTest.java`

**Interfaces:**
- Produces (필드는 설계의 "계약" 절 그대로. 시각은 `Instant`, 초는 `long`, 수는 `int`):
  - `enum GroupVisibility { PUBLIC, PRIVATE }`, `enum GroupRole { OWNER, MEMBER }`, `enum DeskStatus { IN_ROOM, STUDYING, PAUSED, IDLE }`, `enum ApplyState { CAN_APPLY, APPLIED, MEMBER, FULL, UNAVAILABLE }`
  - `GroupCreateRequest(@NotNull @TextLength(min = 1, max = 20) String name, @NotNull GroupVisibility visibility, @NotNull StudyCategory category, @TextLength(max = 500) String description)`
  - `GroupUpdateRequest(@NotNull @TextLength(min = 1, max = 20) String name, @NotNull StudyCategory category, @TextLength(max = 500) String description)` — 공개 여부 필드 없음
  - `GroupNotificationRequest(@NotNull Boolean enabled)`
  - `GroupMemberDesk(long userId, String nickname, String profileImageUrl, boolean owner, DeskStatus status, boolean accumulating, long todayFocusSec, long weekFocusSec, Instant lastStudiedAt)`
  - `PublicGroupDetailResponse.unavailableReason`의 타입은 `ErrorCode` (`@Schema(allowableValues = {"RECENTLY_KICKED", "GROUP_SLOT_LIMIT", "APPLICATION_LIMIT"})`)
  - `GroupService` 메서드 (모두 `throw new NotImplementedException()`): `long create(Long userId, GroupCreateRequest)`, `MyGroupsResponse myGroups(Long userId)`, `GroupHomeResponse home(Long userId, long groupId)`, `void update(Long userId, long groupId, GroupUpdateRequest)`, `PublicGroupPageResponse publicGroups(Long userId, String query, StudyCategory category, int page, int size)`, `PublicGroupDetailResponse publicGroup(Long userId, long groupId)`, `void setNotification(Long userId, long groupId, boolean enabled)`
- 매핑 (클래스 `@RequestMapping("/api/groups")`, `@Tag(name = "그룹", description = ...)`, 모두 `version = "1"`): `POST ""`(201) · `GET "/me"` · `GET "/{groupId}"` · `PATCH "/{groupId}"`(204) · `GET "/public"`(`query`, `category`, `page` 기본 0 `@Min(0)`, `size` 기본 20 `@Min(1) @Max(50)`) · `GET "/public/{groupId}"` · `PUT "/{groupId}/notifications"`(204)
- Swagger: 오퍼레이션마다 `@ApiResponse`로 가능한 상태와 `code`를 적는다 (설계의 REST 표와 이유 코드 표). `weeklyAverageFocusSec` 설명에 "지금 멤버들의 이번 주(월요일 0시 KST부터) 앱 전체 순공 합 ÷ 지금 멤버 수. 주 중간에 들어온 멤버도 이번 주 순공이 모두 들어가고 나간 멤버는 빠진다. 측정 세션만, 1분 미만 조각 제외, 진행 중 포함, 약 1분마다 갱신"을, 목록 정렬 설명에 "다 차지 않은 그룹 → 평균 많은 순 → 그룹 ID 작은 순, 쪽마다 순서가 바뀔 수 있어 FE가 groupId로 중복을 거른다"를, `newApplicationCount`에 "답을 기다리는 신청 전부. 공개 그룹 그룹장이 아니면 null"을 적는다

- [ ] **Step 1: 실패하는 테스트 쓰기** — `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`, 요청마다 `.header(ApiVersionConfig.HEADER, "1").with(asUser(1L))`
  - `로직_전에는_501과_NOT_IMPLEMENTED다` — 위 7개 매핑 각각 → 501, `$.code == "NOT_IMPLEMENTED"` (`POST`·`PATCH`·`PUT`은 유효한 본문을 보낸다)
  - `고정_경로가_groupId_경로보다_먼저_잡힌다` — `GET /api/groups/me`, `GET /api/groups/public` → 501 (400이 아니다)
  - `인증_없으면_401이다` — `asUser` 없이 `GET /api/groups/me` → 401
  - `이름이_21자면_400_VALIDATION_FAILED다` — `POST /api/groups` `name = "가".repeat(21)` → 400, `$.code == "VALIDATION_FAILED"`
  - `없는_시험_목표는_400이다` — `category = "UNKNOWN"` → 400
  - `size가_50을_넘으면_400이다` — `GET /api/groups/public?size=51` → 400
  - `Swagger에_그룹_태그와_버전_1이_실린다` — `GET /v3/api-docs`에서 `$.paths['/api/groups/me'].get.tags[0] == "그룹"`, `$.paths['/api/groups/me'].get.parameters[?(@.name=='API-Version')].schema.default`에 `"1"`이 있다
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.group.GroupContractApiTest"` → 컴파일 실패
- [ ] **Step 3: 구현** — DTO·enum·컨트롤러·스텁 서비스. 컨트롤러는 `@Valid` 본문과 `@AuthenticationPrincipal Long userId`를 받아 서비스에 넘기기만 한다. 쿼리 파라미터의 `@Min`·`@Max`는 Spring MVC 내장 메서드 검증(`HandlerMethodValidationException` → 기존 핸들러가 400)에 맡기고 클래스에 `@Validated`를 달지 않는다 (AOP 검증의 `ConstraintViolationException`은 지금 핸들러에서 500이 된다)
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. `/v3/api-docs`의 `paths` 키 목록에서 7개 매핑을 눈으로 확인
- [ ] **Step 5: 커밋** — `feat: 그룹 API 계약을 Swagger에 올린다 (BY-1124)`

### Task 6: 그룹 계약 — 멤버·초대·공부방·멤버 신고

**Files:**
- Create: `src/main/java/project/study/group/JoinVia.java`
- Create: `src/main/java/project/study/group/dto/` — `GroupMembersResponse`(중첩 `GroupMemberItem`), `GroupOwnerTransferRequest`, `GroupInviteResponse`, `GroupJoinRequest`, `GroupJoinResponse`, `GroupRoomJoinResponse`, `ReportRequest`
- Create: `src/main/java/project/study/group/controller/GroupMemberController.java`, `src/main/java/project/study/group/service/GroupMemberService.java`
- Test: `src/test/java/project/study/group/GroupContractApiTest.java` (Task 5 파일에 이어서)

**Interfaces:**
- Consumes: Task 5의 enum·태그, Task 1의 `NotImplementedException`, Task 2의 `@TextLength`
- Produces:
  - `enum JoinVia { LINK, CODE }`
  - `GroupMemberItem(long userId, String nickname, String profileImageUrl, boolean owner, Instant joinedAt, Instant lastStudiedAt)`
  - `GroupOwnerTransferRequest(@NotNull Long userId)`, `GroupInviteResponse(String inviteCode)`
  - `GroupJoinRequest(@NotBlank String inviteCode, @NotNull JoinVia via)` — 형식 검사는 하지 않는다 (틀린 형식도 3단계에서 `INVITE_CODE_NOT_FOUND`)
  - `GroupJoinResponse(long groupId, boolean joined)`
  - `GroupRoomJoinResponse(long roomId, boolean graceRejoin, Boolean cameraOn, Boolean micOn, Boolean onBreak, List<RoomJoinResponse.IceServer> iceServers, int iceTtlSeconds)` — `IceServer`는 `project.study.room.dto.RoomJoinResponse.IceServer`를 그대로 쓴다
  - `ReportRequest(@TextLength(max = 100) String reason)` — 신청 신고(Task 7)도 이 record를 쓴다
  - `GroupMemberService` 메서드 (모두 501): `GroupMembersResponse members(Long userId, long groupId)`, `void transferOwner(Long userId, long groupId, long targetUserId)`, `void kick(Long userId, long groupId, long targetUserId)`, `void leave(Long userId, long groupId)`, `GroupInviteResponse invite(Long userId, long groupId)`, `GroupInviteResponse regenerateInvite(Long userId, long groupId)`, `GroupJoinResponse join(Long userId, GroupJoinRequest)`, `GroupRoomJoinResponse joinRoom(Long userId, long groupId)`, `void leaveRoom(Long userId, long groupId)`, `void reportMember(Long userId, long groupId, long targetUserId, ReportRequest)`
- 매핑 (`/api/groups`, 태그 `그룹`, `version = "1"`): `GET "/{groupId}/members"` · `PUT "/{groupId}/owner"`(204) · `DELETE "/{groupId}/members/{userId}"`(204) · `POST "/{groupId}/leave"`(204) · `GET "/{groupId}/invite"` · `POST "/{groupId}/invite"` · `POST "/join"` · `POST "/{groupId}/room/join"` · `POST "/{groupId}/room/leave"`(204) · `POST "/{groupId}/members/{userId}/report"`(204)
- Swagger: `inviteCode` 설명에 "영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시", `join`에 "이미 멤버면 409 대신 joined=false로 200", `room/join`에 "응답의 roomId로 /topic/room/{roomId}를 구독한다 — 공부방 메시지는 /docs/websocket.html", `reportMember`에 "신고하면 차단도 된다"를 적는다

- [ ] **Step 1: 실패하는 테스트 쓰기**
  - `멤버_초대_공부방_경로도_로직_전에는_501이다` — 위 10개 매핑 각각 → 501 `NOT_IMPLEMENTED`
  - `join은_고정_경로로_잡힌다` — `POST /api/groups/join` 유효 본문 → 501 (405·400이 아니다)
  - `신고_사유가_101자면_400이다` — `POST /api/groups/1/members/2/report` `reason = "가".repeat(101)` → 400 `VALIDATION_FAILED`
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.group.GroupContractApiTest"` → FAIL
- [ ] **Step 3: 구현** — Task 5와 같은 방식
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `feat: 그룹 멤버·초대·공부방 API 계약을 올린다 (BY-1124)`

### Task 7: 신청·차단 계약

**Files:**
- Create: `src/main/java/project/study/group/dto/` — `GroupApplicationCreateRequest`, `GroupApplicationCreateResponse`, `GroupApplicationsResponse`(중첩 `GroupApplicationItem`), `MyApplicationsResponse`(중첩 `MyApplicationItem`)
- Create: `src/main/java/project/study/group/controller/GroupApplicationController.java`, `src/main/java/project/study/group/service/GroupApplicationService.java`
- Create: `src/main/java/project/study/block/controller/BlockController.java`, `src/main/java/project/study/block/service/BlockService.java`, `src/main/java/project/study/block/dto/BlockListResponse.java`(중첩 `BlockedUser`)
- Test: `src/test/java/project/study/group/GroupContractApiTest.java`

**Interfaces:**
- Consumes: Task 6의 `ReportRequest`
- Produces:
  - `GroupApplicationCreateRequest(@NotNull @TextLength(min = 1, max = 50) String introduction)`, `GroupApplicationCreateResponse(long applicationId, Instant expiresAt)`
  - `GroupApplicationItem(long applicationId, long userId, String nickname, String profileImageUrl, String introduction, Instant appliedAt, Instant expiresAt)`, `GroupApplicationsResponse(int remainingSeats, List<GroupApplicationItem> applications)`
  - `MyApplicationItem(long applicationId, long groupId, String groupName, StudyCategory category, Instant appliedAt, Instant expiresAt)`
  - `BlockedUser(long userId, String nickname, String profileImageUrl, Instant blockedAt)`
  - `GroupApplicationService` (모두 501): `GroupApplicationCreateResponse apply(Long userId, long groupId, GroupApplicationCreateRequest)`, `GroupApplicationsResponse applications(Long userId, long groupId)`, `MyApplicationsResponse myApplications(Long userId)`, `void cancel(Long userId, long applicationId)`, `void accept(Long userId, long applicationId)`, `void decline(Long userId, long applicationId)`, `void report(Long userId, long applicationId, ReportRequest)`
  - `BlockService` (모두 501): `BlockListResponse blocks(Long userId)`, `void unblock(Long userId, long targetUserId)`
- 매핑 (태그 `그룹`, `version = "1"`): `GroupApplicationController`는 클래스 경로 없이 `POST /api/groups/{groupId}/applications`(201) · `GET /api/groups/{groupId}/applications` · `GET /api/group-applications/me` · `DELETE /api/group-applications/{applicationId}`(204) · `POST /api/group-applications/{applicationId}/accept`(204) · `/decline`(204) · `/report`(204). `BlockController`는 `GET /api/blocks` · `DELETE /api/blocks/{userId}`(204)
- Swagger: `apply`에 "공개 그룹만. 답이 없으면 3일 뒤 취소", `accept`에 "자리가 없으면 GROUP_FULL, 신청은 기한까지 남는다", `report`에 "신고하면 바로 거절되고 신청한 사람에게는 거절 알림만 간다. 신청한 사람을 차단하지 않는다", `unblock`에 "다음 공부방 입장부터 다시 보인다"를 적는다

- [ ] **Step 1: 실패하는 테스트 쓰기**
  - `신청과_차단_경로도_로직_전에는_501이다` — 위 9개 매핑 각각 → 501 `NOT_IMPLEMENTED`
  - `신청_소개_글이_비었거나_51자면_400이다` — `introduction = "  "` → 400, `"가".repeat(51)` → 400, 둘 다 `VALIDATION_FAILED`
  - `그룹_API는_모두_그룹_태그_하나다` — `/v3/api-docs`의 `/api/groups`, `/api/group-applications`, `/api/blocks`로 시작하는 모든 오퍼레이션의 `tags`가 `["그룹"]`
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "project.study.group.GroupContractApiTest"` → FAIL
- [ ] **Step 3: 구현** — Task 5와 같은 방식
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `feat: 그룹 신청·차단 API 계약을 올린다 (BY-1124)`

### Task 8: 공부방 STOMP 계약 문서와 ADR

**Files:**
- Modify: `src/main/resources/docs/websocket.html` (목차, 새 `<h2 id="group-room">12. 그룹 공부방</h2>`, 변경 이력)
- Create: `docs/adr/0040-group-study-rooms.md` (`docs/adr/0039-*.md` 형식)

**Interfaces:**
- Produces: 4단계 구현이 따를 STOMP 계약. 이 단계에서는 서버 코드(`RoomMessageType`, `RoomMember`, `RoomStompHandler`)를 바꾸지 않는다

- [ ] **Step 1: `websocket.html`에 12절을 쓴다** — 설계의 "공부방 STOMP" 절을 옮긴다
  - 맨 위 안내: "그룹 공부방 계약. 서버는 BY-1124 4단계에서 보내기 시작한다. 초대코드 방에는 아무것도 바뀌지 않는다"
  - 들어오기 흐름 (REST `POST /api/groups/{groupId}/room/join` → 기존 구독·발행 목적지)
  - 멤버 필드 추가 표: `micOn`(boolean, 입장 직후 false), `onBreak`(boolean, 입장 직후 false), `owner`(boolean)
  - `/app/room/{roomId}/state`가 더 받는 `micOn?`, `onBreak?`
  - 새 type 표 5개 (`MIC_CHANGED`, `BREAK_CHANGED`, `OWNER_CHANGED`, `KICKED`, `PEER_BLOCKED`: 어디로·필드·언제, `PEER_BLOCKED`는 SNAPSHOT마다 다시 보낸다는 것까지)
  - 예시 JSON. 기존 예시처럼 `"type": "MIC_CHANGED"` 형식(콜론 뒤 공백 하나)으로 써서 4단계의 `WebSocketDocsContractTest`가 그대로 통과하게 한다
  - SIGNAL 차단, `graceRejoin` 때 `micOn`·`onBreak`를 이어 가는 것, 그룹을 나가거나 그룹이 사라질 때 `MEMBER_LEFT`, 소리는 P2P이고 서버가 저장하지 않는다는 것
  - 변경 이력 행: `2026-11-03 | BY-1124 | 그룹 공부방 계약(§12) 추가. 서버 송신은 4단계부터. 초대코드 방은 변화 없음`
- [ ] **Step 2: ADR-0040을 쓴다** — 결정 5개를 기록한다: ① 그룹은 사라지지 않고 공부방은 기존 `rooms`를 `group_id`로 다시 쓴다 ② 앱 전체 공부 상태는 체크포인트로 판단하고 60초 창 ③ 그룹 푸시는 `push_send` 밖, 멤버 공부 알림만 `(receiver, group)` 1시간 제한 ④ 이유 코드를 400·403·409에 싣는다 ⑤ 계약을 먼저 내고 로직 전에는 501. 각각 근거는 설계 문서의 "정한 것" 표에서 가져온다
- [ ] **Step 3: 확인** — `./gradlew test --tests "project.study.room.websocket.WebSocketDocsContractTest"` → PASS (코드는 그대로라 통과해야 한다). 브라우저나 `open`으로 html이 깨지지 않는지 본다
- [ ] **Step 4: 커밋** — `docs: 그룹 공부방 STOMP 계약과 ADR-0040을 추가한다 (BY-1124)`

### Task 9: 검증, PR, 계약 전달

- [ ] **Step 1: 전체 검증** — `./gradlew spotlessApply check` → BUILD SUCCESSFUL
- [ ] **Step 2: 로컬 Swagger 확인** — `./gradlew bootRun --args='--spring.profiles.active=local'` 뒤 `curl -s localhost:8080/v3/api-docs | jq '[.paths | to_entries[] | select(.key | test("^/api/(groups|group-applications|blocks)")) | .key] | length'` → `23`(경로 키 수. 매핑 26개 중 `/{groupId}`, `/{groupId}/invite`, `/{groupId}/applications` 세 경로를 두 메서드가 같이 쓴다)
- [ ] **Step 3: 리뷰 게이트** — 자체 리뷰, `codex exec -s read-only`로 2차 리뷰(P1이면 고치고 다시), 퀴즈 게이트(사용자가 생략을 명시하지 않았으면 5문제)
- [ ] **Step 4: PR** — `git push -u origin feat/BY-1124-group-rooms`, 제목 `[feat] BY-1124 그룹 공부방 API 계약(Swagger)과 공부방 메시지 명세`, 본문은 `.github/pull_request_template.md` 절 구조 그대로 짧게. CI 런이 등록된 걸 확인한 뒤 `gh pr checks --watch`, 머지는 사용자 확인 뒤 merge commit으로
- [ ] **Step 5: dev 반영 확인** — dev 자동 배포 뒤 `https://<api-dev>/v3/api-docs`에 `/api/groups/me`가 있는지, `/docs/websocket.html`에 12절이 있는지 확인
- [ ] **Step 6: BY-1124 댓글** — FE 담당자 멘션(ADF)으로 남긴다: Swagger 링크, 경로 목록, 이유 코드 표, 시험·목표 9개 값과 라벨, 푸시 8종 type·link(제안) 표, 연락처 판정 예시 표(Task 4), "로직은 1~6단계 순서로 채우고 그전에는 501", "응답 모양이 바뀌면 이 스레드에 남긴다"
