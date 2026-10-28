# 설치 정보 해석과 첫 유입 경로 저장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앱이 보낸 플레이 설치 정보·메타 딥링크 값을 서버가 해석(메타 암호문 복호화 포함)해 사용자당 한 번 저장하고, 정리한 유입 경로를 돌려주는 `POST /api/users/me/acquisition`을 만든다.

**Architecture:** 새 도메인 패키지 `project.study.acquisition`. 순수 계산(`referrer/` — 복호화·판별)과 저장(`user_acquisition` 테이블, `INSERT … ON CONFLICT (user_id) DO NOTHING` 후 JPA로 읽기)을 나눈다. 키는 프로퍼티로 받고, 없으면 복호화만 끈다.

**Tech Stack:** Spring Boot 4.1, Java 25, JPA + `JdbcClient`, Flyway, Jackson 3(`tools.jackson`), JDK `javax.crypto`(AES/GCM/NoPadding), Testcontainers + `MockMvcTester`.

**Spec:** `docs/superpowers/specs/2026-10-29-by1066-install-attribution-design.md`

## Global Constraints

- 새 의존성 없음. Security 설정(`SecurityConfig`) 변경 없음 — 새 경로는 `anyRequest().authenticated()`가 막는다.
- 컨트롤러 매핑 `version = "1"` 명시. 테스트는 `.header(ApiVersionConfig.HEADER, "1")`을 `.with(asUser(..))` **앞**에 둔다(asUser는 헤더가 없으면 2를 싣는다).
- checkstyle: 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하(생성자 포함). 판별 로직은 작은 메서드로 나눈다.
- 프로퍼티 `app.acquisition.meta-install-referrer-key`, 환경변수 `META_INSTALL_REFERRER_KEY`, prod·dev yaml 값은 `${META_INSTALL_REFERRER_KEY:}`.
- 키·예외 메시지를 로그·응답에 남기지 않는다. 실패 로그는 예외 종류(`getClass().getSimpleName()`)만.
- 메타 ID는 DB(`varchar`)·응답(JSON string) 모두 문자열.
- 마이그레이션 번호 V33, ADR 번호 0036 (분기 기준 `origin/dev` 3cbaec8에서 확인).
- 주석·Swagger·ADR 문체는 기존 코드(`PushTokenController`, `FcmConfig`, ADR-0035)를 따른다: 한국어, "왜"를 쓴다.
- 커밋은 `./gradlew check` 통과 뒤에만, Conventional Commits + `(BY-1066)` 접미, `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 퍼센트 인코딩이 깨진 설치 정보(`utm_source=a%2&x=%E0%A4%A`) — 예외 없이 `OTHER_LINK`, 원문 저장. → Task 2 `깨진_퍼센트_인코딩도_예외없이_그밖의링크`
2. `utm_content`가 JSON이 아니거나 `source.data`가 hex가 아닌 경우 — 500 없이 각각 `OTHER_LINK` / `META_AD`(ID 없음). → Task 2 `utm_content가_JSON이_아니면_메타가_아니다`, Task 1 `hex가_아니면_빈값`
3. 복호화 결과 ID가 JSON 숫자로 오는 경우 — 문자열로 저장. → Task 2 `숫자_ID도_문자열로_꺼낸다`
4. 같은 사용자의 첫 요청 두 개가 동시에 — 둘 다 200, 같은 응답, 행 하나. → Task 3 `동시_첫요청도_한행이고_같은응답`
5. `installReferrer`·`deepLinkAd`가 빈 문자열·공백 — 없음과 같게 `UNKNOWN`, 컬럼은 null. → Task 3 `빈_문자열은_없음과_같다`

---

## File Structure

```
src/main/java/project/study/acquisition/
  controller/AcquisitionController.java     POST /api/users/me/acquisition (Swagger)
  dto/AcquisitionRequest.java               요청 record + 검증
  dto/AcquisitionResponse.java              응답 record (+ 중첩 MetaAd), from(UserAcquisition)
  entity/AcquisitionSource.java             META_AD, INVITE, ORGANIC, OTHER_LINK, UNKNOWN
  entity/InstallPlatform.java               IOS, ANDROID
  entity/UserAcquisition.java               user_acquisition 읽기용 엔티티(생성자 없음)
  referrer/MetaReferrerDecryptor.java       키 보관·검증, AES-GCM 복호화
  referrer/InstallReferrerClassifier.java   쿼리 파싱 + 판별 → Attribution
  referrer/Attribution.java                 판별 결과 record
  referrer/Utm.java                         utm_source/medium/campaign record
  referrer/MetaAdDetail.java                복호화해 꺼낸 값 record
  repository/UserAcquisitionRepository.java JpaRepository + UserAcquisitionInsert
  repository/UserAcquisitionInsert.java     insertIfAbsent 프래그먼트 인터페이스
  repository/UserAcquisitionInsertImpl.java JdbcClient 구현
  repository/NewAcquisition.java            INSERT 인자 record
  service/AcquisitionService.java           판별 → 저장 → 응답
src/main/resources/db/migration/V33__user_acquisition.sql
src/main/resources/application-{dev,prod}.yaml, application-local.yaml.example
docs/erd.dbml, docs/adr/0036-install-referrer-first-touch-acquisition.md
src/test/java/project/study/acquisition/
  MetaReferrerFixture.java                  메타 문서 예시 키·nonce·암호문, 참조 생성 도우미
  referrer/MetaReferrerDecryptorTest.java
  referrer/InstallReferrerClassifierTest.java
  AcquisitionApiTest.java
```

---

### Task 1: 메타 설치 정보 복호화기

**Files:**
- Create: `src/main/java/project/study/acquisition/referrer/MetaReferrerDecryptor.java`
- Create: `src/test/java/project/study/acquisition/MetaReferrerFixture.java`
- Test: `src/test/java/project/study/acquisition/referrer/MetaReferrerDecryptorTest.java`

**Interfaces:**
- Produces:
  - `@Component public class MetaReferrerDecryptor` — 생성자 `MetaReferrerDecryptor(@Value("${app.acquisition.meta-install-referrer-key:}") String keyHex)`
  - `public boolean enabled()` — 키가 hex 64자로 읽혔는지
  - `public Optional<String> decrypt(String dataHex, String nonceHex)` — UTF-8 평문. 꺼져 있거나 어떤 실패든 `Optional.empty()`
  - 테스트 픽스처 `MetaReferrerFixture` (public final, static):
    - `KEY = "2575590594a9cd809e5bfacf397f8c1ac730dbc38a3e137ecd1ab66591c8c3c9"`
    - `NONCE = "b7203c6a6fb633d16e9cf5c1"`
    - `CIPHER = "afe56cf6228c6ea8c79da49186e718e92a579824596ae1d0d4d20d7793dca797bd4034ccf467bfae5c79a3981e7a2968c41949237e2b2db678c1c3d39c9ae564c5cafd52f2b77a3dc77bf1bae063114d0283b97417487207735da31ddc1531d5645a9c3e602c195a0ebf69c272aa5fda3a2d781cb47e117310164715a54c7a5a032740584e2789a7b4e596034c16425139a77e507c492b629c848573c714a03a2e7d25b9459b95842332b460f3682d19c35dbc7d53e3a51e0497ff6a6cbb367e760debc4194ae097498108df7b95eac2fa9bac4320077b510be3b7b823248bfe02ae501d9fe4ba179c7de6733c92bf89d523df9e31238ef497b9db719484cbab7531dbf6c5ea5a8087f95d59f5e4f89050e0f1dc03e464168ad76a64cca64b79"`
    - `PLAIN = "{\"ad_id\":\"12345\",\"ad_objective_name\":\"APP_INSTALLS\",\"adgroup_id\":\"54321\",\"adgroup_name\":\"Adams Test Ad Group\",\"campaign_id\":\"987654\",\"campaign_name\":\"Adams Test Campaign\",\"campaign_group_id\":\"654321\",\"campaign_group_name\":\"Adams Test Campaign Group\",\"account_id\":\"444444\"}"`
    - `static String encrypt(String plainJson)` — 같은 KEY·NONCE로 AES/GCM 암호화해 hex(본문+태그)를 돌려준다(숫자 ID 등 임의 페이로드 테스트용)
    - `static String metaReferrer(String cipherHex, String nonceHex)` — `"utm_source=apps.facebook.com&utm_campaign=fb4a&utm_content=" + URLEncoder.encode("{\"app\":1234567890,\"t\":1595425811,\"source\":{\"data\":\"<cipher>\",\"nonce\":\"<nonce>\"}}", UTF_8)`

- [ ] **Step 1: 픽스처와 실패하는 테스트 작성** — `MetaReferrerDecryptorTest` (Spring 없이 `new MetaReferrerDecryptor(..)`, `@ExtendWith(OutputCaptureExtension.class)`)

```java
@Test void 메타_문서_예시가_풀린다() {
    assertThat(new MetaReferrerDecryptor(KEY).decrypt(CIPHER, NONCE)).contains(PLAIN);
}
@Test void 다른_키면_빈값이고_키는_로그에_없다(CapturedOutput output) {
    String otherKey = "00".repeat(32);
    assertThat(new MetaReferrerDecryptor(otherKey).decrypt(CIPHER, NONCE)).isEmpty();
    assertThat(output).contains("AEADBadTagException").doesNotContain(otherKey);
}
@Test void hex가_아니면_빈값() {
    MetaReferrerDecryptor decryptor = new MetaReferrerDecryptor(KEY);
    assertThat(decryptor.decrypt("zz" + CIPHER, NONCE)).isEmpty();
    assertThat(decryptor.decrypt(CIPHER.substring(1), NONCE)).isEmpty(); // 홀수 길이
    assertThat(decryptor.decrypt(CIPHER, "abcd")).isEmpty();           // nonce 12바이트 아님 → 빈값(예외 아님)
}
@ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  ", "abc", "2575590594a9cd80"})
void 키가_없거나_64자_hex가_아니면_꺼진다(String key, CapturedOutput output) {
    MetaReferrerDecryptor decryptor = new MetaReferrerDecryptor(key);
    assertThat(decryptor.enabled()).isFalse();
    assertThat(decryptor.decrypt(CIPHER, NONCE)).isEmpty();
    assertThat(output).contains("meta-install-referrer-key");
}
@Test void 키가_맞으면_켜진다() { assertThat(new MetaReferrerDecryptor(KEY).enabled()).isTrue(); }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.acquisition.referrer.MetaReferrerDecryptorTest"` → 컴파일 실패(클래스 없음)

- [ ] **Step 3: `MetaReferrerDecryptor` 구현** — `HexFormat.of().parseHex`, `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, nonce)`, `SecretKeySpec(key, "AES")`. 생성자에서 키를 검증해 `byte[]`(또는 `SecretKeySpec`)로만 보관하고 문자열 원본은 필드로 두지 않는다. 키가 비었거나 `^[0-9a-fA-F]{64}$`가 아니면 WARN `"app.acquisition.meta-install-referrer-key가 비어 있거나 hex 64자가 아니어서 메타 설치 정보를 풀지 않는다"`. `decrypt`는 `GeneralSecurityException | IllegalArgumentException`을 잡아 WARN `"메타 설치 정보를 풀지 못했다: {}"`(예외 단순 이름) 후 빈값. `toString` 정의하지 않음. 클래스 Javadoc에 "본문 뒤 16바이트 태그 = JDK GCM 입력 형식이라 그대로 넣는다"와 "키 하나 때문에 API가 뜨지 못하는 쪽이 더 나쁘다(FcmConfig와 같은 판단)"를 적는다.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS

- [ ] **Step 5: 커밋** — `./gradlew spotlessApply check` 통과 후
  `git add src/main/java/project/study/acquisition/referrer/MetaReferrerDecryptor.java src/test/java/project/study/acquisition/`
  `git commit -m "feat: 메타 설치 정보 암호문을 AES-GCM으로 푸는 복호화기를 둔다 (BY-1066)"`

---

### Task 2: 설치 정보 판별기

**Files:**
- Create: `src/main/java/project/study/acquisition/entity/AcquisitionSource.java`
- Create: `src/main/java/project/study/acquisition/referrer/{Attribution,Utm,MetaAdDetail,InstallReferrerClassifier}.java`
- Test: `src/test/java/project/study/acquisition/referrer/InstallReferrerClassifierTest.java`

**Interfaces:**
- Consumes: `MetaReferrerDecryptor.decrypt(String, String)` (Task 1), `MetaReferrerFixture` (Task 1)
- Produces:
  - `public enum AcquisitionSource { META_AD, INVITE, ORGANIC, OTHER_LINK, UNKNOWN }`
  - `public record Utm(String source, String medium, String campaign)` — 없으면 각 필드 null (`Utm` 자체는 항상 non-null)
  - `public record MetaAdDetail(String campaignId, String campaignName, String adsetId, String adsetName, String adId, String adName, String payload)` — `payload`는 복호화 평문 JSON 원문
  - `public record Attribution(AcquisitionSource source, Utm utm, String inviteCode, MetaAdDetail metaAd)` — `inviteCode`는 INVITE일 때만, `metaAd`는 복호화에 성공했을 때만 non-null
  - `@Component @RequiredArgsConstructor public class InstallReferrerClassifier` — `public Attribution classify(String installReferrer, String deepLinkAd)`. 인자는 이미 공백→null 정리된 값(Task 3 서비스가 정리)

판별 순서는 스펙 4절 표 그대로(1 메타 암호문 → 2 `code` 숫자 4자리 → 3 딥링크 → 4 `google-play`+`organic` → 5 그 밖의 비어 있지 않은 설치 정보 → 6 UNKNOWN). 복호화 평문 필드 매핑은 스펙 2절 표(`campaign_group_*`→campaign, `campaign_*`→adset, `adgroup_*`→ad). 값은 `JsonNode`가 문자열이든 숫자든 `asString()`으로, 없거나 null이면 null. 평문이 JSON 객체가 아니면 `metaAd = null`(META_AD는 유지).

- [ ] **Step 1: 실패하는 테스트 작성** — 실제 `new MetaReferrerDecryptor(KEY)`를 넣은 `new InstallReferrerClassifier(..)`

```java
@Test void 메타_암호문이면_풀어서_계층을_맞춰_꺼낸다() {
    Attribution a = classifier.classify(metaReferrer(CIPHER, NONCE), "aos_v3");
    assertThat(a.source()).isEqualTo(META_AD);
    assertThat(a.metaAd()).isEqualTo(new MetaAdDetail("654321", "Adams Test Campaign Group",
            "987654", "Adams Test Campaign", "54321", "Adams Test Ad Group", PLAIN));
    assertThat(a.utm()).isEqualTo(new Utm("apps.facebook.com", null, "fb4a"));
}
@Test void utm_source가_없어도_암호문이_있으면_메타다() {  // 메타 문서: utm_source는 보장되지 않는다
    String referrer = metaReferrer(CIPHER, NONCE).replace("utm_source=apps.facebook.com&", "");
    assertThat(classifier.classify(referrer, null).source()).isEqualTo(META_AD);
}
@Test void 암호문을_못_풀어도_메타이고_상세는_없다() {
    Attribution a = new InstallReferrerClassifier(new MetaReferrerDecryptor("")).classify(metaReferrer(CIPHER, NONCE), null);
    assertThat(a.source()).isEqualTo(META_AD);
    assertThat(a.metaAd()).isNull();
}
@Test void 숫자_ID도_문자열로_꺼낸다() {
    String cipher = encrypt("{\"campaign_group_id\":120200000000000001,\"campaign_id\":120200000000000002,\"adgroup_id\":120200000000000003}");
    MetaAdDetail m = classifier.classify(metaReferrer(cipher, NONCE), null).metaAd();
    assertThat(m.campaignId()).isEqualTo("120200000000000001");
    assertThat(m.adsetId()).isEqualTo("120200000000000002");
    assertThat(m.adId()).isEqualTo("120200000000000003");
    assertThat(m.campaignName()).isNull();
}
@Test void 초대코드_4자리면_친구초대() {
    Attribution a = classifier.classify("code=0123", null);
    assertThat(a.source()).isEqualTo(INVITE);
    assertThat(a.inviteCode()).isEqualTo("0123");
}
@ParameterizedTest @ValueSource(strings = {"code=123", "code=12345", "code=abcd"})
void 초대코드_형식이_아니면_그밖의링크(String referrer) {
    assertThat(classifier.classify(referrer, null).source()).isEqualTo(OTHER_LINK);
}
@Test void 초대가_딥링크보다_먼저다() { assertThat(classifier.classify("code=1234", "aos_v1").source()).isEqualTo(INVITE); }
@Test void 딥링크만_있으면_메타() {
    Attribution a = classifier.classify(null, "ios_v2");
    assertThat(a.source()).isEqualTo(META_AD);
    assertThat(a.metaAd()).isNull();
}
@Test void 딥링크가_오가닉보다_먼저다() {
    assertThat(classifier.classify("utm_source=google-play&utm_medium=organic", "aos_v1").source()).isEqualTo(META_AD);
}
@Test void 플레이_오가닉() {
    Attribution a = classifier.classify("utm_source=google-play&utm_medium=organic", null);
    assertThat(a.source()).isEqualTo(ORGANIC);
    assertThat(a.utm()).isEqualTo(new Utm("google-play", "organic", null));
}
@Test void 그밖의_UTM은_그밖의링크이고_값을_남긴다() {
    Attribution a = classifier.classify("utm_source=insta_bio&utm_medium=social&utm_campaign=launch%20week", null);
    assertThat(a.source()).isEqualTo(OTHER_LINK);
    assertThat(a.utm()).isEqualTo(new Utm("insta_bio", "social", "launch week"));
}
@Test void not_set도_그밖의링크() {
    assertThat(classifier.classify("utm_source=(not%20set)&utm_medium=(not%20set)", null).source()).isEqualTo(OTHER_LINK);
}
@Test void 깨진_퍼센트_인코딩도_예외없이_그밖의링크() {
    assertThat(classifier.classify("utm_source=a%2&x=%E0%A4%A", null).source()).isEqualTo(OTHER_LINK);
}
@ParameterizedTest @ValueSource(strings = {"utm_content=hello", "utm_content=%5B1%2C2%5D", "utm_content=%7B%22source%22%3A%7B%22data%22%3A%22ab%22%7D%7D"})
void utm_content가_JSON이_아니거나_data_nonce가_없으면_메타가_아니다(String referrer) {
    assertThat(classifier.classify(referrer, null).source()).isEqualTo(OTHER_LINK);
}
@Test void 아무것도_없으면_알수없음() {
    Attribution a = classifier.classify(null, null);
    assertThat(a.source()).isEqualTo(UNKNOWN);
    assertThat(a.utm()).isEqualTo(new Utm(null, null, null));
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.acquisition.referrer.InstallReferrerClassifierTest"` → 컴파일 실패

- [ ] **Step 3: 구현** — 쿼리 파싱은 `&`로 자르고 첫 `=`에서 키·값을 나눠 `URLDecoder.decode(.., UTF_8)`. `IllegalArgumentException`이면 그 값은 원문 그대로 둔다. 같은 키가 여러 번이면 첫 값. `utm_content` JSON은 클래스 상수 `JsonMapper.builder().build()`(`AmplitudeEventParser`와 같은 방식)로 읽고 `JacksonException`이면 메타 아님. `source.data`·`source.nonce`가 문자열이고 비어 있지 않을 때만 메타. 규칙마다 private 메서드로 나눠 복잡도 10 이하.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS

- [ ] **Step 5: 커밋** — `./gradlew spotlessApply check` 통과 후
  `git commit -m "feat: 설치 정보와 딥링크 값으로 유입 경로를 판별한다 (BY-1066)"`

---

### Task 3: 저장과 API

**Files:**
- Create: `src/main/resources/db/migration/V33__user_acquisition.sql`
- Create: `acquisition/entity/{InstallPlatform,UserAcquisition}.java`, `acquisition/repository/*` (4개), `acquisition/service/AcquisitionService.java`, `acquisition/dto/{AcquisitionRequest,AcquisitionResponse}.java`, `acquisition/controller/AcquisitionController.java`
- Modify: `src/main/resources/application-dev.yaml`, `application-prod.yaml` (`app:` 아래 `push:` 블록 뒤), `application-local.yaml.example`, `docs/erd.dbml` (`push_token` 뒤)
- Test: `src/test/java/project/study/acquisition/AcquisitionApiTest.java`

**Interfaces:**
- Consumes: `InstallReferrerClassifier.classify(String, String)`, `Attribution`/`Utm`/`MetaAdDetail`, `AcquisitionSource` (Task 2), `MetaReferrerFixture` (Task 1)
- Produces:
  - `public enum InstallPlatform { IOS, ANDROID }`
  - `UserAcquisition` 엔티티: `@Id userId`(Long, `user_id`), `@Enumerated(STRING)` `platform`·`source`, `installReferrer`, `deepLinkAd`, `utmSource`, `utmMedium`, `utmCampaign`, `inviteCode`, `metaCampaignId/Name`, `metaAdsetId/Name`, `metaAdId/Name`, `@JdbcTypeCode(SqlTypes.JSON) String metaPayload`, `Instant capturedAt`, `Instant createdAt`. `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, 생성자 없음(쓰기는 insertIfAbsent 한 곳 — `PushToken`과 같은 설명 Javadoc). `BaseTimeEntity`는 쓰지 않는다(updated_at 없음).
  - `public record NewAcquisition(long userId, InstallPlatform platform, String installReferrer, String deepLinkAd, Instant capturedAt, Attribution attribution)`
  - `public interface UserAcquisitionInsert { void insertIfAbsent(NewAcquisition row); }` / `class UserAcquisitionInsertImpl` (`JdbcClient`, `meta_payload`는 `cast(:metaPayload as jsonb)`, `created_at`은 `now()`)
  - `public interface UserAcquisitionRepository extends JpaRepository<UserAcquisition, Long>, UserAcquisitionInsert {}`
  - `public record AcquisitionRequest(@NotNull InstallPlatform platform, @Size(max = 4096) String installReferrer, @Size(max = 64) String deepLinkAd, @NotNull OffsetDateTime capturedAt)` — 각 필드 `@Schema` 설명·예시(스펙 8절 예시값)
  - `public record AcquisitionResponse(AcquisitionSource source, MetaAd metaAd, String inviteCode, Instant firstTouchAt)` + `public record MetaAd(String campaignId, String adsetId, String adId, String label)` + `static AcquisitionResponse from(UserAcquisition a)` — `metaAd`는 `source == META_AD`일 때만 non-null, `label = deepLinkAd`, `firstTouchAt = capturedAt`
  - `AcquisitionService.record(Long userId, AcquisitionRequest request): AcquisitionResponse` — `@Transactional`. 공백 문자열을 null로 바꾼 뒤 classify → insertIfAbsent → `findById(userId).orElseThrow()` → `from`
  - `AcquisitionController` — `@RequestMapping(value = "/api/users/me/acquisition", version = "1")`, `@PostMapping`, 200. `@Tag(name = "Acquisition")` 설명에 ADR-0036·버전 1·인증 필요, `@Operation` 설명에 "처음 저장한 값만 남고 다시 보내도 처음 값을 돌려준다", `@ApiResponse` 200/400(스펙 8절 400 목록)/401

V33 스키마 (스펙 7절 표 그대로):

```sql
-- 사용자마다 처음 들어온 경로 한 행 (BY-1066, ADR-0036). 행이 있으면 이미 저장된 것이고 덮어쓰지 않는다
CREATE TABLE user_acquisition (
    user_id           bigint PRIMARY KEY REFERENCES users (id),
    platform          varchar(16)  NOT NULL,
    source            varchar(16)  NOT NULL,
    install_referrer  text,
    deep_link_ad      varchar(64),
    utm_source        varchar, utm_medium varchar, utm_campaign varchar,
    invite_code       varchar(4),
    meta_campaign_id  varchar, meta_campaign_name varchar,
    meta_adset_id     varchar, meta_adset_name    varchar,
    meta_ad_id        varchar, meta_ad_name       varchar,
    meta_payload      jsonb,
    captured_at       timestamptz NOT NULL,
    created_at        timestamptz NOT NULL
);
```
(컬럼별 한 줄로 펼치고, 메타 계층이 이름과 한 단계 어긋난다는 주석을 meta_* 위에 단다.)

yaml (dev·prod 같은 값, 주석 문구만 환경에 맞게):
```yaml
  # 메타 설치 정보 복호화 키 (BY-1066, ADR-0036). 값이 없으면 복호화만 꺼진 채 기동한다 — 운영은 ECS가 SSM meta/install-referrer-key를 주입
  acquisition:
    meta-install-referrer-key: ${META_INSTALL_REFERRER_KEY:}
```

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `@SpringBootTest(properties = "app.acquisition.meta-install-referrer-key=" + MetaReferrerFixture.KEY)`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration.class)`, `@ExtendWith(OutputCaptureExtension.class)`. 사용자는 `PushTokenApiTest.insertUser()`와 같은 SQL로 만든다. `post(userId, body)` 도우미는 헤더 1 → `.with(asUser(..))` 순서.

```java
@Test void 메타_광고를_풀어서_저장하고_돌려준다(CapturedOutput output) {
    assertThat(post(userId, body("ANDROID", metaReferrer(CIPHER, NONCE), "aos_v3"))).hasStatusOk()
        .bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.source").isEqualTo("META_AD");
            assertThat(json).extractingPath("$.metaAd.campaignId").isEqualTo("654321");
            assertThat(json).extractingPath("$.metaAd.adsetId").isEqualTo("987654");
            assertThat(json).extractingPath("$.metaAd.adId").isEqualTo("54321");
            assertThat(json).extractingPath("$.metaAd.label").isEqualTo("aos_v3");
            assertThat(json).extractingPath("$.firstTouchAt").isEqualTo("2026-10-29T01:12:00Z");
        });
    assertThat(row(userId)).containsEntry("meta_ad_name", "Adams Test Ad Group").containsEntry("utm_campaign", "fb4a");
    assertThat(output).doesNotContain(KEY);
}
@Test void 친구초대를_저장한다()       // "code=0123" → source INVITE, inviteCode "0123", metaAd 없음($.metaAd null)
@Test void 플레이_오가닉을_저장한다()  // ORGANIC, utm_source "google-play"
@Test void 그밖의_링크를_저장한다()    // OTHER_LINK, utm_* 컬럼
@Test void iOS_딥링크만_있으면_라벨만() // IOS, null, "ios_v2" → META_AD, $.metaAd.label "ios_v2", $.metaAd.adId null
@Test void 빈_문자열은_없음과_같다()   // installReferrer "", deepLinkAd "  " → UNKNOWN, install_referrer·deep_link_ad 컬럼 null
@Test void 두번째_요청은_첫값을_바꾸지_않는다() {
    post(userId, body("ANDROID", "code=1234", null));
    assertThat(post(userId, body("ANDROID", "utm_source=google-play&utm_medium=organic", null))).hasStatusOk()
        .bodyJson().extractingPath("$.source").isEqualTo("INVITE");
    assertThat(row(userId)).containsEntry("install_referrer", "code=1234");
}
@Test void 복호화에_실패해도_200이고_원문이_남는다(CapturedOutput output) {
    String referrer = metaReferrer("00" + CIPHER.substring(2), NONCE);   // 태그 검증 실패
    assertThat(post(userId, body("ANDROID", referrer, null))).hasStatusOk()
        .bodyJson().extractingPath("$.source").isEqualTo("META_AD");
    assertThat(row(userId)).containsEntry("install_referrer", referrer).containsEntry("meta_ad_id", null);
    assertThat(output).contains("AEADBadTagException").doesNotContain(KEY);
}
@Test void 동시_첫요청도_한행이고_같은응답() throws Exception {
    // 2개 스레드가 CountDownLatch로 동시에 서로 다른 값(code=1111 / code=2222)을 보낸다 → 둘 다 200, 두 응답의 inviteCode가 같고,
    // SELECT count(*) FROM user_acquisition WHERE user_id = ? 가 1
}
@Test void platform이_없거나_틀리면_400()   // {"capturedAt": ...} / "platform":"WEB"
@Test void capturedAt이_없으면_400()
@Test void installReferrer가_4096자를_넘으면_400()  // "a".repeat(4097)
@Test void 토큰이_없으면_401()               // 헤더 1, asUser 없이
@Test void 스웨거에_경로가_버전1로_실린다()  // GET /v3/api-docs (ApiVersionSwaggerDocsTest 방식) →
    // "$.paths['/api/users/me/acquisition'].post.parameters[?(@.name=='API-Version')].schema.default" 에 "1",
    // "$.paths['/api/users/me/acquisition'].post.requestBody" 존재
```
`$.metaAd.adId` 같은 null 단언은 `.isNull()`로 쓴다.
`body(platform, installReferrer, deepLinkAd)`는 `capturedAt`을 `"2026-10-29T10:12:00+09:00"`로 고정하고, null 인자는 JSON `null`로 쓴다(Jackson 3 `JsonMapper`로 `Map`을 직렬화해 이스케이프를 맡긴다).

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.acquisition.AcquisitionApiTest"` → 컴파일 실패

- [ ] **Step 3: 마이그레이션·엔티티·저장소·서비스·DTO·컨트롤러·yaml·ERD 작성** — 위 Interfaces와 스키마대로. `application-local.yaml.example`에는 기존 주석 블록 형식으로 `# acquisition:\n#   meta-install-referrer-key: <메타 앱 대시보드 > 기본 설정 > Android의 Install Referrer Decryption Key>` 자리표시자. ERD에는 `user_acquisition` 테이블을 컬럼 주석과 함께 추가.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.acquisition.*"` → PASS (Swagger 명세는 `스웨거에_경로가_버전1로_실린다`가 확인한다).

- [ ] **Step 5: 커밋** — `./gradlew spotlessApply check` 통과 후
  `git commit -m "feat: 사용자별 첫 유입 경로 저장 API를 추가한다 (BY-1066)"`

---

### Task 4: ADR과 설계 문서 정리

**Files:**
- Create: `docs/adr/0036-install-referrer-first-touch-acquisition.md`
- Modify: `docs/superpowers/specs/2026-10-29-by1066-install-attribution-design.md` (상태 → `구현 완료(2026-10-29)`)
- Add: `docs/superpowers/plans/2026-10-29-by1066-install-attribution.md` (이 문서)

- [ ] **Step 1: ADR-0036 작성** — ADR-0035 형식(상태·날짜·티켓·설계·관련 / 맥락 / 결정 / 결과). 결정 항목: ① 판별은 암호문 유무로, `utm_source`는 보지 않는다(메타 문서 인용) ② 판별 순서와 그 이유(초대 > 딥링크 > 오가닉) ③ 메타 필드 계층 매핑과 `meta_payload` 보관, `ad_id`는 첫 실데이터로 대조 ④ 사용자당 한 행·덮어쓰지 않음·`ON CONFLICT DO NOTHING` ⑤ 키가 없거나 복호화 실패면 원문만 남기고 200, 기동은 막지 않음, 로그는 예외 종류만 ⑥ ID는 문자열. 결과: 운영 배포 전 키 주입 필요, 키 없이 저장된 행은 원문으로 일회성 재복호화(범위 밖), 기존 사용자도 앱 업데이트 뒤 한 번 저장 → 분석은 가입일로 거른다.

- [ ] **Step 2: 확인** — Run: `./gradlew check` → BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**
  `git add docs/adr/0036-*.md docs/superpowers/specs/2026-10-29-by1066-*.md docs/superpowers/plans/2026-10-29-by1066-*.md`
  `git commit -m "docs: 설치 정보로 첫 유입 경로를 남기는 결정을 ADR-0036으로 남긴다 (BY-1066)"`
