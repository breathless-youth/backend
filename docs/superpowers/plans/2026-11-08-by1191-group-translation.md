# BY-1191 Group Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 그룹 원문을 보존하면서 무료 한도 안에서 여섯 앱 언어의 번역을 재사용하고, 실패하면 원문을 제공한다.

**Architecture:** 기존 그룹 읽기 서비스 밖의 파사드가 응답에 번역을 붙인다. PostgreSQL이 필드별 캐시·작업 임차권·공급자별 사용량을 공유하고, 짧은 트랜잭션으로 예약한 뒤 외부 HTTP를 실행한다. Google NMT → Azure F0 → DeepL 무료 잔량 순서를 사용한다.

**Tech Stack:** Java 25, Spring Boot 4.1, Jackson 3, JdbcClient, RestClient/JDK HTTP client, PostgreSQL/Flyway, JUnit/AssertJ, MockMvcTester/Testcontainers. 새 의존성 없음.

**Spec:** `docs/superpowers/specs/2026-11-07-by1191-group-translation-design.md`

## Global Constraints

- 티켓 BY-1191, 브랜치 `feat/BY-1191-group-translation`. 실제 원문·키·토큰을 문서/로그/테스트 fixture에 기록하지 않는다.
- Google 일반 NMT 동기 텍스트만 사용. 백엔드 13,000자/일 AND 400,000자/월, `America/Los_Angeles` 경계. 콘솔 15,000자/일 적용은 백엔드 검증 후다.
- Azure는 F0만, 서버 보조 한도 2,000,000자/월 UTC. 실제 F0 무료 잔량이 먼저 소진되면 중단한다. DeepL Developer 총 1,000,000자 장부는 월초 초기화하지 않는다.
- 원문 코드 포인트를 대상 언어별·공급자 시도별 사전 예약. 실패·시간 초과 예약은 반환하지 않는다. 계정의 기사용량을 활성화 전에 반영한다.
- 성공 캐시에는 시간 만료 없음. 임차권 10초, 필드 재시도 대기 60초. HTTP 총 대기 예산 3초, 시도 최대 2초, 연결 최대 300ms, 자동 재시도 없음.
- 전역/공급자별 활성화 기본 false. 원문 검색·권한·STOMP·Security 설정·의존성은 변경하지 않는다.
- DTO는 record, 생성자 주입, Flyway만 스키마 변경. `./gradlew check` 및 독립 리뷰 후 커밋 전 퀴즈 5개를 통과해야 한다. 아래 커밋 단계는 이 게이트가 충족되기 전 실행하지 않는다.

## Review Focus

1. 이모지·분해 한글·공백의 코드 포인트와 대상 언어 곱셈이 장부와 일치해야 한다 → Task 1, 3.
2. 원문 수정/삭제 중 완료된 번역이 새 캐시를 덮거나 권한 없는 요청의 외부 호출을 유발하면 안 된다 → Task 2, 5.
3. 재시작·동시 서버·DST·월 경계에도 무료 장부가 줄거나 DeepL이 초기화되면 안 된다 → Task 3.
4. `q=0`, 잘못된 헤더, 홍콩/대만 번체 매핑은 의도한 원문 대체/언어 선택을 해야 한다 → Task 1, 4.
5. timeout·429·403001·456·잘못된 응답 후에도 조회가 성공하고 과금 가능 재시도가 숨어 있으면 안 된다 → Task 4, 5.

---

## 파일 경계

신규 도메인 코드는 `src/main/java/project/study/group/translation/`에 모은다. 기존 그룹 저장·권한 서비스는 그대로 사용하며 범용 번역 플랫폼으로 확장하지 않는다. 아래 파일은 각 Task의 생성/수정 목록이 전부다. 내부 보조 record는 사용하는 클래스 안에 둔다.

| 단위 | 책임 |
| --- | --- |
| `TranslationLanguage`, `TranslationField`, `TranslationProvider`, `TranslationText` | 언어·필드·공급자·전송 원문 계약 |
| `TranslationCacheRepository` | 캐시 일괄 조회, 임차권, 현재 원문 검증과 조건부 저장 |
| `TranslationBudget` | 기간별 사용량의 원자적 예약, 공급자 중지/대기 |
| `TextTranslator`, 세 공급자 구현, `TranslationConfig` | HTTP·인증·응답 검증·무료용 설정 |
| `GroupTranslationService` | 캐시/예산/공급자 전환과 요청 대기 예산 |
| `LocalizedGroupReader` | 기존 권한 있는 읽기 응답과 번역의 조합 |
| `GroupTranslation` DTO | 원문과 별도로 제공하는 선택 번역 필드 |

### Task 1: 언어·문자 수·응답의 공통 계약

**Files:**
- Create: `src/main/java/project/study/group/translation/TranslationLanguage.java`
- Create: `src/main/java/project/study/group/translation/TranslationField.java`
- Create: `src/main/java/project/study/group/translation/TranslationProvider.java`
- Create: `src/main/java/project/study/group/translation/TranslationText.java`
- Create: `src/main/java/project/study/group/dto/GroupTranslation.java`
- Test: `src/test/java/project/study/group/translation/TranslationLanguageTest.java`
- Test: `src/test/java/project/study/group/translation/TranslationTextTest.java`

**Interfaces:**
- `TranslationLanguage`: enum `KO, EN, JA, ZH_CN, ZH_TW, ZH_HK`; `static Optional<TranslationLanguage> resolve(String acceptLanguage)`; `String locale()`; `String providerCode(TranslationProvider provider)`.
- `TranslationField`: enum `NAME, DESCRIPTION`; `TranslationProvider`: enum `GOOGLE, AZURE, DEEPL`.
- `TranslationText(long groupId, TranslationField field, String source)` record; `String sourceHash()`; `long characters()`. Hash exact transmitted UTF-8 with JDK SHA-256; do not normalize again after source service normalization.
- `GroupTranslation(String language, String name, String description)` record. This task defines the type; adding it to existing responses is Task 5.

- [ ] Write tests: `resolve("ja;q=0.5,en;q=0.9") == EN`, `resolve("en;q=0,ko;q=1") == KO`; null, malformed quality and unsupported header return empty. Assert all specified Chinese aliases, unsupported Chinese variant, and Hong Kong provider mapping.
- [ ] Write tests: `new TranslationText(1, NAME, "A😀 가").characters() == 4`; NFC and decomposed strings have different hashes; same exact string hashes identically. Null/blank fields are filtered before constructing dispatch batches in Task 5.
- [ ] Run `./gradlew test --tests 'project.study.group.translation.TranslationLanguageTest' --tests 'project.study.group.translation.TranslationTextTest'` and confirm the new assertions fail because the types/behavior are absent.
- [ ] Implement the exact interfaces, `Locale.LanguageRange.parse` with validation/fallback and explicit alias table, code-point count and SHA-256. Preserve language-range order on equal quality; ignore wildcards and q=0.
- [ ] Run the same targeted tests and confirm PASS. Do not turn a compile error unrelated to these types into the accepted red result.
- [ ] Commit checkpoint after project gates: `feat: BY-1191 번역 언어와 입력 계약 정의`.

### Task 2: 영속 캐시와 수정·동시 요청 처리

**Files:**
- Create: `src/main/resources/db/migration/V64__group_translation.sql` (recheck maximum migration number first)
- Create: `src/main/java/project/study/group/translation/TranslationCacheRepository.java`
- Test: `src/test/java/project/study/group/translation/TranslationCacheRepositoryTest.java`

**Interfaces:**
- Consumes Task 1 types and existing `JdbcClient`, `Clock`, `PlatformTransactionManager`.
- Nested records: `CacheKey(long groupId, TranslationField field, TranslationLanguage language)`; `CachedText(String sourceHash, String text, TranslationProvider provider)`; `Lease(TranslationText source, TranslationLanguage language, UUID token)`.
- `Map<CacheKey, CachedText> find(List<TranslationText> texts, TranslationLanguage language)`.
- `List<Lease> claim(List<TranslationText> texts, TranslationLanguage language)` — short write transaction, deterministic order, only current live source; returns owned leases only.
- `boolean complete(Lease lease, String translated, TranslationProvider provider)`; `void fail(List<Lease> leases)` — conditional on token/current source; failure cooldown 60 seconds.
- Migration also creates provider-state/usage tables and seeds exactly GOOGLE/AZURE/DEEPL lock rows; constraints enforce nonnegative counts and valid field/locale/period types. Task 3 implements usage behavior.

- [ ] Write PostgreSQL tests: two instances race for the same text/language and only one claims; separate languages can claim; another user's identical read shares the same cache; name and description have separate hashes.
- [ ] Write PostgreSQL tests: change description only and retain name cache; old lease completion after source update/delete returns false; expired/reclaimed lease cannot be completed by its old token; full failure retries only after 60 seconds. A lease expires after 10 seconds using an injected clock.
- [ ] Run `./gradlew test --tests 'project.study.group.translation.TranslationCacheRepositoryTest'` and confirm the behavior is absent.
- [ ] Implement migration and repository with existing JDBC patterns. Never hold a transaction across an outbound HTTP call. Cache reads must compare requested hashes; claim/completion must additionally verify current live group field against the source parameter. Do not add pgcrypto or a new dependency.
- [ ] Repeat the targeted test against Testcontainers and confirm PASS, including physical group deletion FK cleanup. Confirm no existing lock order is reversed.
- [ ] Commit checkpoint after project gates: `feat: BY-1191 그룹 번역 캐시 영속화`.

### Task 3: 사전 예약으로 무료 한도 보호

**Files:**
- Create: `src/main/java/project/study/group/translation/TranslationBudget.java`
- Test: `src/test/java/project/study/group/translation/TranslationBudgetTest.java`

**Interfaces:**
- Consumes Task 1 and Task 2 provider-state/usage tables, injected `Clock` and transaction manager.
- Nested record `Limits(long googleDaily, long googleMonthly, long azureMonthly, long deepLLifetime)` with positive values capped at 13,000 / 400,000 / 2,000,000 / 1,000,000 respectively. Task 3 tests construct this directly; Task 4 wires it from server settings.
- `boolean tryReserve(TranslationProvider provider, long characters)` — atomically checks all required buckets and increments all or none before dispatch; false if disabled/blocked/unknown usage state.
- `long available(TranslationProvider provider)` — advisory remaining capacity; `tryReserve` remains authoritative under concurrency.
- `void blockUntil(TranslationProvider provider, Instant until)` and `void disable(TranslationProvider provider)` — no public HTTP management endpoint. Usage synchronization may only increase observed reservation, never decrease it.

- [ ] Write boundary tests: Google 12,999 day/399,999 month +1 succeeds, next +1 fails; month 399,999 with a fresh day +2 fails without partially increasing either bucket. Verify each fallback provider has its own charge.
- [ ] Write concurrent PostgreSQL test: 100 requests for 200 characters cannot reserve over 13,000/day. Instantiate another budget service with the same DB and confirm remaining usage survives process-style reconstruction.
- [ ] Write clock tests across Los Angeles midnight, DST transitions and month end; UTC Azure month; DeepL 999,999 lifetime +1 succeeds, another +1 fails even after month/year changes. Unknown initial usage, DB failure and negative/overflow input must never allow a provider call.
- [ ] Run `./gradlew test --tests 'project.study.group.translation.TranslationBudgetTest'` and confirm expected failures.
- [ ] Implement per-provider row lock and atomic multi-bucket check/update in one short transaction. Persist reservations even if later HTTP fails. Require verified initial usage before activation; no automatic counter reset or usage refunds. Derive limit values only from validated server settings capped at the spec maxima.
- [ ] Repeat the targeted test and confirm PASS; inspect actual stored counts after denied and concurrent reservations.
- [ ] Commit checkpoint after project gates: `feat: BY-1191 무료 번역 사용량 사전 예약`.

### Task 4: 무료용 HTTP 어댑터와 장애 분류

**Files:**
- Create: `src/main/java/project/study/group/translation/TextTranslator.java`
- Create: `src/main/java/project/study/group/translation/TranslationHttp.java` (공유 JDK HTTP 경계)
- Create: `src/main/java/project/study/group/translation/GoogleTextTranslator.java`
- Create: `src/main/java/project/study/group/translation/AzureTextTranslator.java`
- Create: `src/main/java/project/study/group/translation/DeepLTextTranslator.java`
- Create: `src/main/java/project/study/group/translation/TranslationConfig.java`
- Modify: `src/main/resources/application-local.yaml.example`
- Test: `src/test/java/project/study/group/translation/TextTranslatorTest.java`
- Test: `src/test/java/project/study/group/translation/TranslationConfigTest.java`

**Interfaces:**
- `TextTranslator.provider(): TranslationProvider`.
- `TextTranslator.translate(List<String> texts, TranslationLanguage language, Duration timeout): TranslationReply`.
- Nested `TranslationReply(List<String> texts, Failure failure, Instant retryAt)` and enum `Failure { NONE, TEMPORARY, RATE_LIMIT, QUOTA, AUTH, UNSUPPORTED, INVALID_RESPONSE }`. Success requires the same order/count and nonblank text for every input; unsuccessful replies contain no usable text.
- DeepL adapter checks `FreeUsage(long usedCharacters, long limitCharacters)` via `Optional<FreeUsage> freeUsage(Duration timeout)` and targets via `Set<String> supportedTargets(Duration timeout)` within the remaining call deadline. Targets use current `/v3/languages?resource=translate_text`, `usable_as_target` and BCP 47 codes (English target `EN-US`). Failure prevents translation until verified; it does not manufacture a fresh lifetime balance.
- `TranslationConfig` wires Task 3's `TranslationBudget.Limits` and produces validated `Settings` consumed by Task 5: all enabled flags false by default, exact maxima/timeouts from Global Constraints, keys and Azure region from server configuration. No property may raise maxima above the approved values.

- [ ] Write JDK `HttpServer` tests for Google `POST /language/translate/v2` with q-array, `model=nmt`, `format=text`, automatic source detection; Azure `POST /translate?api-version=3.0&to=...` with Text-array and key+region headers; DeepL free-host `/v2/translate` with text-array, auth header and provider target code. Confirm no separate paid detection request.
- [ ] Write stub responses for ordered success, escaped text, same-as-original result, array count mismatch, blank translations, 403001, 456, auth failure, 429 Retry-After, timeout. Assert one network request per attempt and no hidden retry. Test the six target mappings and DeepL unsupported target bypass.
- [ ] Run `./gradlew test --tests 'project.study.group.translation.TextTranslatorTest'` and confirm expected failures.
- [ ] Implement with existing RestClient/JDK APIs and Jackson 3. Apply each invocation's timeout without mutating a shared client concurrently. Google authorization belongs in a header, never a key-bearing logged URL. Discard malformed whole-batch responses. Production endpoints are fixed provider hosts; only test configuration can point to loopback stubs.
- [ ] Repeat tests and confirm PASS; verify default feature-off context boots without any key. Ensure no provider exception/log output contains fake test key markers or submitted source text.
- [ ] Commit checkpoint after project gates: `feat: BY-1191 무료 번역 공급자 연동`.

### Task 5: 조회 통합·배치 전환·최종 검증

**Files:**
- Create: `src/main/java/project/study/group/translation/GroupTranslationService.java`
- Create: `src/main/java/project/study/group/translation/LocalizedGroupReader.java`
- Modify: `src/main/java/project/study/group/controller/GroupController.java`
- Modify: `src/main/java/project/study/group/dto/GroupHomeResponse.java`
- Modify: `src/main/java/project/study/group/dto/MyGroupsResponse.java`
- Modify: `src/main/java/project/study/group/dto/PublicGroupPageResponse.java`
- Modify: `src/main/java/project/study/group/dto/PublicGroupDetailResponse.java`
- Update constructor sites in the existing `GroupService`, `PublicGroupService` and tests only as required for the appended nullable field; keep their transaction/access behavior.
- Create: `docs/adr/0046-group-content-translation.md` (recheck next ADR number)
- Create: `docs/runbooks/group-translation.md`
- Test: `src/test/java/project/study/group/translation/GroupTranslationServiceTest.java`
- Test: `src/test/java/project/study/group/translation/GroupTranslationApiTest.java`

**Interfaces:**
- `GroupTranslationService.translate(List<TranslationText> texts, TranslationLanguage language): Map<TranslationCacheRepository.CacheKey, String>` returns only successful translations matching the requested source. Empty results mean original fallback.
- `LocalizedGroupReader.myGroups(Long userId, String acceptLanguage): MyGroupsResponse`.
- `LocalizedGroupReader.home(Long userId, long groupId, String acceptLanguage): GroupHomeResponse`.
- `LocalizedGroupReader.publicGroups(Long userId, String query, StudyCategory category, int page, int size, String acceptLanguage): PublicGroupPageResponse`.
- `LocalizedGroupReader.publicGroup(Long userId, long groupId, String acceptLanguage): PublicGroupDetailResponse`.
- Append `GroupTranslation translation` to `GroupHomeResponse`, `PublicGroupDetailResponse`, `MyGroupCard` and `PublicGroupCard`. Preserve original fields/accessors and source-only service behavior. No external provider/lease/usage details in API DTOs.

- [ ] Write service tests: hit → zero reservation/HTTP; one language requested → only that language; list → only names; distinct cold fields → one provider array up to 50/5,000; repeated polling/another user → cache reuse; blank fields skipped; equal output cached; partial budget dispatches fitting fields and sends remainder to next provider.
- [ ] Write service tests: Google unavailable/over daily or month → Azure; Azure quota → DeepL; all unavailable → original; failed earlier calls remain charged; HTTP deadline prevents starting work after the 3-second budget; owned lease conflicts return original without a duplicate call. Verify each fallback request is reserved before HTTP starts and no DB transaction is active inside the adapter.
- [ ] Write MockMvcTester/PostgreSQL tests on all four GETs with `API-Version: 1` and `asUser`: original fields unchanged, per-field translation fallback, header absent/unsupported/feature-off → no HTTP; nonmember/private/deleted group errors unchanged and zero provider work. Public filtering/pagination/source-name search stay unchanged.
- [ ] Run `./gradlew test --tests 'project.study.group.translation.GroupTranslationServiceTest' --tests 'project.study.group.translation.GroupTranslationApiTest'` and confirm expected failures.
- [ ] Implement facade after the existing service transaction returns, enrichment service, DTO additions and optional Accept-Language header. Batch/cache work once per response, fixed provider order, no pretranslation on create/update/startup. Add low-cardinality existing Micrometer counters for cache result, reservation characters, provider outcome and fallback reason.
- [ ] Repeat targeted tests and existing group API regression tests, then run `./gradlew spotlessApply` and `./gradlew check`; fix failures without disabling/removing tests. Record actual output before claiming success.
- [ ] Write ADR and runbook: free usage baseline, key injection, endpoint/plan/language check, rollback flag, console unchanged until guard verified, Google free credit shared across other projects, Azure provider cycle may differ, DeepL lifetime never reset, original fallback and frontend integration examples.
- [ ] Prepare a live quality matrix for six locales: short titles, 500-character intro, emoji, numbers, mixed script and study terms. Compute/reserve sample character budget before sending anything. Run only after actual keys/free balances are configured; mark unrun pairs explicitly and obtain human language-quality assessment. Do not let the harness bypass the same budget/cache path.
- [ ] Self-review diff and obtain the repository-required independent cross review. Present five implementation/data-flow quiz questions and wait for the user to pass before committing. Do not claim the quiz or independent review has already happened.
- [ ] Final commit checkpoint after all gates: `feat: BY-1191 그룹 조회에 자동 번역 제공`. Push/PR/merge only within subsequent authorization; after merge transition BY-1191 to done.

## 설계 자체 점검

- 범위와 소스 코드의 네 조회 경로를 대조했다. 필드별 캐시가 목록/상세 사이에서 이름을 재사용하고 소개만 수정되는 경우를 포함한다.
- 공통 타입/중첩 타입/메서드는 소비 작업 전에 정의했다. 어댑터 실패와 API 원문 대체는 서로 다른 계약이다.
- Review Focus 다섯 항목 각각 테스트 소유 작업을 지정했다. 실제 번역 품질과 모의 네트워크 테스트를 구분했다.
- 2026-11-08 최종 검증: `./gradlew check` 통과. 전체 2,520개, 번역 46개, 실패/오류/건너뜀 0개. 독립 리뷰의 캐시 경합·잘못된 헤더 수용을 RED→GREEN으로 수정했고 최신 dev 통합 후 전체 검증을 다시 통과했다. 이번 작업은 사용자 추가 승인으로 퀴즈를 생략한다. 실제 키 주입·실번역 품질 평가·콘솔 한도 변경은 미실행이다.

## 실행 중 결정과 한계

- 2026-11-08 추가 승인: 사용자가 “퀴즈없이 가자”로 이번 작업의 퀴즈를 생략하고 커밋·PR 진행을 승인했다. 저장소의 일반 퀴즈 규칙은 변경하지 않는다.
- 승인된 기능 브랜치/체크아웃에서 직접 진행했다. 별도 체크아웃을 만들지 않아 이 폴더에서 변경이 바로 보인다.
- 프로젝트 퀴즈 규칙이 계획의 개별 커밋 단계보다 우선한다. 모든 변경은 퀴즈 통과 전까지 미커밋 상태로 유지한다.
- 사용량 장부의 마지막 날짜/월 키를 저장한다. 현재/직전 장부가 사라지면 새 무료량을 만들지 않고 호출을 차단한다. 장부 유실 시 수동 복구가 필요하다.
- HTTP 공통부는 기존 JDK 클라이언트로 구현했다. 새 SDK 없이 호출별 남은 제한 시간을 적용하며, 외부 요청이 처리됐는지 모르는 경우에도 예약량을 환급하지 않는다.
- 처음에는 V55를 사용했으나 최신 dev의 V55 플래너와 충돌하여 V64로 재배정했다. ADR 번호도 최신 dev 뒤인 0046으로 옮겼다.
- DeepL 현재 capability API에 맞춰 영어 대상은 EN-US를 쓴다. 마지막 공급자에서 미국식 영어가 기본이 된다. 홍콩/대만은 표준 번체를 사용한다.
- 비정상 언어 헤더도 외부 호출을 유발할 수 있어 리뷰의 해당 경미 지적을 수정 대상으로 올렸다. HTTP 문법을 따르지 않는 클라이언트는 번역 대신 원문을 받는다.
- 실계정 잔량과 사람에 의한 번역 품질 평가는 별도 활성화 단계다. 검증 전 기능은 기본 off이며, 모의 HTTP 성공을 실제 번역 품질 보장으로 설명하지 않는다.
- 보류한 리뷰 경미 항목은 없다. 초기 검증 두 개를 겹쳐 실행한 결과 파일 충돌은 순차 검증으로 바로잡았고, 최종 수치는 순차 실행 결과다.
