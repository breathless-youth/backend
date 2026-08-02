# Sentry 에러 추적 도입 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** prod에서 처리되지 않은 서버 에러(5xx)가 발생하면 Sentry에 이슈로 쌓이게 한다.

**Architecture:** Sentry Spring Boot 4 자동설정을 붙이되, 자동 등록되는 `SentryExceptionResolver`는 실행 순서를 최하위로 미뤄 무력화한다. 대신 `GlobalExceptionHandler`의 최종 핸들러에서 `Sentry.captureException`을 명시적으로 호출해, 4xx로 변환되는 도메인 예외는 제외하고 처리되지 않은 예외만 전송한다.

**Tech Stack:** Spring Boot 4.1, Java 25, Sentry Java SDK 8.51.0, JUnit 5 + AssertJ + MockMvcTester

**설계 문서:** `docs/superpowers/specs/2026-08-02-sentry-error-tracking-design.md`

## Global Constraints

- **Spring Boot 4 전용 모듈을 쓴다** — `io.sentry:sentry-spring-boot-4`. `sentry-spring-boot-starter-jakarta`는 Boot 3용이라 사용 금지.
- **버전은 반드시 명시한다** — `8.51.0`. Boot 4 BOM이 Sentry 버전을 관리하지 않는다.
- **DTO는 Java record**, 엔티티에 `@Data` 금지, **생성자 주입만** 사용 (`@Autowired` 필드 주입 금지).
- **Spotless(palantir-java-format)** 포맷을 따른다 — 커밋 전 `./gradlew spotlessApply`.
- **Checkstyle `maxWarnings=0`** — 미사용 import 금지, 파일 400줄·메서드 60줄·순환복잡도 10 이내.
- **`ddl-auto`는 validate 고정** — 이 작업에 스키마 변경은 없다.
- 작업 완료 선언 전 반드시 **`./gradlew check`** 통과를 확인한다.
- 커밋은 Conventional Commits (`<type>: <설명>`), 한 커밋은 한 가지 목적만.

## 이미 완료된 것 (다시 하지 말 것)

AWS 인프라는 설계 승인 시점에 이미 반영되었다.

| 항목 | 상태 |
|---|---|
| SSM 파라미터 `/focus-makers/prod/sentry/dsn` | SecureString, Version 1 — 생성 완료 |
| ECS 태스크 정의 `focus-makers-prod-api` | rev 3 등록 완료 (`SENTRY_DSN` secret 포함) |
| execution role SSM 권한 | 기존 `/focus-makers/prod/*` 와일드카드로 커버 — 변경 불필요 |

---

### Task 1: Sentry 의존성과 prod 설정 추가

애플리케이션이 Sentry SDK를 로드하고, prod 프로파일에서만 활성화되도록 설정한다.
이 태스크만으로는 아직 아무 에러도 전송되지 않는다 (캡처 호출은 Task 2).

**Files:**
- Modify: `build.gradle` (dependencies 블록)
- Modify: `src/main/resources/application-prod.yaml` (파일 끝에 추가)

**Interfaces:**
- Consumes: 없음
- Produces: `io.sentry.Sentry` 클래스를 컴파일 클래스패스에서 사용 가능하게 한다 (Task 2가 의존)

- [ ] **Step 1: `build.gradle`에 의존성 추가**

`// --- 데이터 / DB ---` 블록 **위**, `// --- Security / 인증 ---` 주석 블록 아래에 새 블록으로 추가한다.

```gradle
	// --- 모니터링 ---
	// Boot 4 전용 모듈이다. sentry-spring-boot-starter-jakarta는 Boot 3용이라 여기선 동작하지 않는다.
	// Boot 4 BOM이 Sentry 버전을 관리하지 않아 버전 명시가 필요하다.
	implementation 'io.sentry:sentry-spring-boot-4:8.51.0'
```

- [ ] **Step 2: 의존성이 해석되고 Jackson 충돌이 없는지 확인**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -iE "sentry|com.fasterxml.jackson"`

Expected: `io.sentry:sentry-spring-boot-4:8.51.0`과 함께 딸려오는 `io.sentry:*` 모듈들이 보이고,
**`com.fasterxml.jackson`(Jackson 2) 항목이 새로 나타나지 않는다.**
이 프로젝트는 Jackson 3(`tools.jackson`)을 쓰므로 Jackson 2가 끌려오면 jjwt와 같은 충돌 문제가 생긴다.
Jackson 2가 보이면 즉시 중단하고 보고할 것.

- [ ] **Step 3: `application-prod.yaml`에 Sentry 설정 추가**

파일 맨 끝(`management:` 블록 아래)에 추가한다.

```yaml

# 처리되지 않은 5xx만 Sentry로 보낸다.
# 설계: docs/superpowers/specs/2026-08-02-sentry-error-tracking-design.md
sentry:
  # DSN이 없으면 SDK가 no-op으로 동작한다. DB 설정과 달리 기본값을 두는 이유는,
  # 모니터링 설정 하나가 없다고 API 전체가 기동 실패하는 쪽이 더 나쁘기 때문이다.
  # DSN이 비면 SDK가 기동 로그에 비활성 메시지를 남기므로 누락은 발견할 수 있다.
  dsn: ${SENTRY_DSN:}
  environment: prod
  # 이미지 빌드 시 커밋 SHA가 주입된다 (Dockerfile의 ARG GIT_SHA)
  release: ${SENTRY_RELEASE:unknown}
  # 요청 헤더·IP를 수집하지 않는다. 로그인이 MVP 제외(ADR-0004)라 식별할 주체도 없다
  send-default-pii: false
  # 성능 트레이싱은 이번 범위가 아니다
  traces-sample-rate: 0
  # 자동 등록되는 SentryExceptionResolver를 맨 뒤로 미룬다.
  # 기본값(Integer.MIN_VALUE = 최우선순위)이면 @ExceptionHandler보다 먼저 모든 예외를 캡처해
  # 404·409 같은 정상적인 클라이언트 오류까지 이슈로 쌓이고,
  # GlobalExceptionHandler의 명시적 캡처(Task 2)와도 중복된다.
  exception-resolver-order: 2147483647
```

`dev`/`local` 프로파일에는 넣지 않는다 — DSN이 없으면 자동으로 비활성되므로 별도 플래그가 필요 없다.

- [ ] **Step 4: 전체 검증**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 기존 테스트가 모두 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add build.gradle src/main/resources/application-prod.yaml
git commit -m "chore: Sentry SDK 의존성과 prod 설정 추가"
```

---

### Task 2: 처리되지 않은 예외를 Sentry로 전송

`GlobalExceptionHandler`에 최종 핸들러를 추가한다. 이 태스크가 이번 작업의 핵심이며,
가장 중요한 산출물은 **405가 500으로 둔갑하는 회귀를 막는 테스트**다.

**Files:**
- Create: `src/test/java/project/study/common/GlobalExceptionHandlerTest.java`
- Modify: `src/main/java/project/study/common/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: Task 1이 추가한 `io.sentry.Sentry`
- Produces: `GlobalExceptionHandler.handleUnexpected(Exception)` → `ResponseEntity<ErrorResponse>`

**배경 (구현 전 반드시 읽을 것):**

`@ExceptionHandler(Exception.class)`는 `ExceptionHandlerExceptionResolver`에서 처리되는데,
이것은 Spring MVC 표준 예외(405·415 등)를 담당하는 `DefaultHandlerExceptionResolver`보다
**우선순위가 높다.** 따라서 분기 없이 모든 예외를 500으로 처리하면
`HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotSupportedException`(415)이
전부 500으로 바뀐다.

`org.springframework.web.ErrorResponse`는 이런 표준 예외들이 구현하는 인터페이스이고
`getStatusCode()`를 제공한다 (Spring 7.0.3에서 확인함). `instanceof`로 걸러 원래 상태코드를 유지한다.

이 인터페이스는 이 프로젝트의 `project.study.common.ErrorResponse` record와 이름이 겹치므로
**import하지 말고 FQCN으로 쓴다.**

- [ ] **Step 1: 실패하는 테스트 작성**

Create: `src/test/java/project/study/common/GlobalExceptionHandlerTest.java`

```java
package project.study.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전역 예외 핸들러가 내리는 상태코드와 응답 포맷만 검증한다.
 * Sentry 전송 여부는 자동 검증하지 않는다 — Sentry.captureException이 static이라
 * 래퍼 없이는 호출을 관측할 수 없고, 래퍼를 두지 않기로 결정했다
 * (설계: docs/superpowers/specs/2026-08-02-sentry-error-tracking-design.md).
 *
 * DB가 필요 없는 검증이라 standalone으로 띄워 Testcontainers 기동 비용을 피한다.
 */
class GlobalExceptionHandlerTest {

    private final MockMvcTester mvc = MockMvcTester.of(
            List.of(new ThrowingController()),
            builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

    @Test
    void 예상하지_못한_예외는_500과_공통_포맷으로_응답한다() {
        assertThat(mvc.get().uri("/test/unexpected").exchange())
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).isEqualTo("서버 오류가 발생했습니다"));
    }

    @Test
    void 내부_예외_메시지는_응답에_노출하지_않는다() {
        assertThat(mvc.get().uri("/test/unexpected").exchange())
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message)
                        .asString()
                        .doesNotContain("db connection refused"));
    }

    @Test
    void NotFoundException은_404로_응답한다() {
        assertThat(mvc.get().uri("/test/not-found").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BadRequestException은_400으로_응답한다() {
        assertThat(mvc.get().uri("/test/bad-request").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ConflictException은_409로_응답한다() {
        assertThat(mvc.get().uri("/test/conflict").exchange()).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * 회귀 방지 테스트. @ExceptionHandler(Exception.class)는 Spring MVC 표준 예외를 담당하는
     * DefaultHandlerExceptionResolver보다 먼저 실행되므로, ErrorResponse 분기가 없으면
     * 405가 500으로 바뀐다.
     */
    @Test
    void 지원하지_않는_HTTP_메서드는_405를_유지한다() {
        assertThat(mvc.get().uri("/test/post-only").exchange()).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("db connection refused");
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("없는 사용자입니다");
        }

        @GetMapping("/test/bad-request")
        void badRequest() {
            throw new BadRequestException("잘못된 요청입니다");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("이미 존재합니다");
        }

        @PostMapping("/test/post-only")
        void postOnly() {}
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인**

Run: `./gradlew test --tests "project.study.common.GlobalExceptionHandlerTest"`

Expected: FAIL.
`예상하지_못한_예외는_500과_공통_포맷으로_응답한다`가 실패한다 — 아직 `Exception` 핸들러가 없어
`IllegalStateException`이 처리되지 않고 서블릿 밖으로 전파되기 때문이다.
`지원하지_않는_HTTP_메서드는_405를_유지한다`와 도메인 예외 테스트 3개는 이 시점에 이미 통과할 수 있다
(기존 핸들러가 처리하므로) — **정상이다.** 405 테스트는 Step 3 구현이 회귀를 일으키지 않는지
검증하는 것이 목적이므로, 구현 전후 모두 통과해야 한다.

- [ ] **Step 3: `GlobalExceptionHandler`에 최종 핸들러 구현**

Modify: `src/main/java/project/study/common/GlobalExceptionHandler.java`

import 두 개를 추가한다 (기존 import 블록의 알파벳 순서에 맞춰 배치, Spotless가 정렬한다).

```java
import io.sentry.Sentry;
import org.springframework.http.ResponseEntity;
```

클래스의 **맨 마지막 메서드로** 추가한다 (`handleUnreadable` 아래).

```java
    /**
     * 위의 어떤 핸들러도 처리하지 못한 예외를 마지막으로 받는다.
     * 여기 도달했다는 것은 우리가 예상하지 못한 상황이므로 Sentry로 전송한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 405·415 같은 Spring MVC 표준 예외를 500으로 둔갑시키지 않는다.
        // 이 핸들러는 표준 예외를 담당하는 DefaultHandlerExceptionResolver보다 먼저 실행되므로
        // 여기서 걸러내지 않으면 상태코드가 뭉개진다.
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            return ResponseEntity.status(standard.getStatusCode()).body(new ErrorResponse("요청을 처리할 수 없습니다"));
        }
        Sentry.captureException(e);
        // 내부 예외 메시지는 노출하지 않는다
        return ResponseEntity.internalServerError().body(new ErrorResponse("서버 오류가 발생했습니다"));
    }
```

- [ ] **Step 4: 테스트를 실행해 통과를 확인**

Run: `./gradlew test --tests "project.study.common.GlobalExceptionHandlerTest"`
Expected: PASS — 6개 테스트 모두 통과.

특히 `지원하지_않는_HTTP_메서드는_405를_유지한다`가 여전히 통과해야 한다.
여기서 500이 나온다면 `instanceof` 분기가 빠졌거나 잘못된 것이다.

- [ ] **Step 5: 포맷 정리 후 전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL. 기존 테스트가 모두 통과해야 한다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/common/GlobalExceptionHandler.java \
        src/test/java/project/study/common/GlobalExceptionHandlerTest.java
git commit -m "feat: 처리되지 않은 예외를 Sentry로 전송"
```

---

### Task 3: 릴리스(커밋 SHA) 추적

Sentry 이슈에 "어느 배포에서 터졌는지"가 표시되도록 커밋 SHA를 이미지에 굽는다.
ECS 태스크 정의는 건드리지 않는다.

**Files:**
- Modify: `Dockerfile` (2단계: 실행 스테이지)
- Modify: `.github/workflows/deploy.yml` (Build and push image 스텝)

**Interfaces:**
- Consumes: Task 1이 추가한 `sentry.release: ${SENTRY_RELEASE:unknown}` 설정
- Produces: 컨테이너 환경변수 `SENTRY_RELEASE` = 커밋 SHA

- [ ] **Step 1: `Dockerfile` 실행 스테이지에 ARG/ENV 추가**

`FROM eclipse-temurin:25-jre` 아래 `WORKDIR /app` 다음에 추가한다.
`ARG`는 사용하는 빌드 스테이지마다 선언해야 하므로 빌드 스테이지가 아닌 **실행 스테이지**에 둔다.

```dockerfile
# Sentry 이슈에 어느 배포에서 발생했는지 표시하기 위해 커밋 SHA를 굽는다.
# CI가 --build-arg GIT_SHA로 주입한다 (.github/workflows/deploy.yml).
ARG GIT_SHA=unknown
ENV SENTRY_RELEASE=$GIT_SHA
```

- [ ] **Step 2: `deploy.yml`의 docker build에 build-arg 추가**

`Build and push image` 스텝의 `docker build` 명령에 한 줄 추가한다.

```yaml
          docker build --provenance=false --sbom=false \
            --build-arg GIT_SHA=${{ github.sha }} \
            -t $IMAGE:${{ github.sha }} -t $IMAGE:latest .
```

- [ ] **Step 3: 이미지에 값이 실제로 구워지는지 확인**

Run:
```bash
docker build --build-arg GIT_SHA=testsha123 -t sentry-release-check . && \
docker run --rm --entrypoint sh sentry-release-check -c 'echo "SENTRY_RELEASE=$SENTRY_RELEASE"'
```

Expected: `SENTRY_RELEASE=testsha123`

빌드에 수 분이 걸린다. 도커가 없거나 시간이 부족하면 이 스텝을 건너뛰되,
**건너뛰었다는 사실을 보고에 명시할 것** (조용히 통과시키지 말 것).

확인 후 정리: `docker rmi sentry-release-check`

- [ ] **Step 4: 커밋**

```bash
git add Dockerfile .github/workflows/deploy.yml
git commit -m "chore: Sentry 릴리스에 커밋 SHA 주입"
```

---

### Task 4: ADR과 기술 스택 주의사항 문서화

**Files:**
- Create: `docs/adr/0010-sentry-error-tracking.md`
- Modify: `AGENTS.md` (기술 스택 주의사항 섹션)

**Interfaces:**
- Consumes: Task 1~3의 결정 사항
- Produces: 없음 (문서)

**주의:** `CLAUDE.md`는 `AGENTS.md`의 심링크다. **`AGENTS.md`를 수정할 것.**

- [ ] **Step 1: ADR 작성**

Create: `docs/adr/0010-sentry-error-tracking.md`

```markdown
# ADR-0010: 에러 추적에 Sentry를 도입하고 5xx만 수집

- 상태: 승인
- 날짜: 2026-08-02

## 맥락

운영 중 발생한 서버 에러를 확인하려면 ECS 태스크의 CloudWatch 로그를 직접 뒤져야 했다.
로그는 ECS(Elastic Common Schema) JSON으로 구조화되어 있지만 수집·알림·집계 계층이 없어,
에러가 났다는 사실 자체를 사용자 제보 전에는 알 수 없었다.

에러 추적 도구를 붙일 때 결정해야 할 것이 두 가지 있었다. 무엇을 보낼 것인가, 그리고 어떻게 보낼 것인가다.

Sentry의 Spring 통합은 `SentryExceptionResolver`를 최우선순위로 자동 등록해 `@ExceptionHandler`보다
**먼저** 모든 예외를 캡처한다. 그대로 두면 `NotFoundException`(404)·`ConflictException`(409)처럼
정상적인 클라이언트 오류까지 전부 이슈로 쌓인다. 알림이 노이즈로 가득 차면 알림을 보지 않게 되고,
그러면 도구를 도입한 의미가 없어진다.

## 결정

- **에러 추적만 도입한다.** 성능 트레이싱(`traces-sample-rate: 0`)과 로그 수집(`sentry-logback`)은 제외한다.
  로그 수집은 현재 `log.error` 사용처가 없어 얻는 것이 없고, 붙이면 같은 예외가 예외 경로와 로그 경로
  양쪽으로 들어와 중복 이슈가 생긴다
- **prod 프로파일에서만 활성화한다.** dev/local에는 `sentry` 설정 블록을 두지 않는다 —
  DSN이 없으면 SDK가 no-op으로 동작하므로 별도 on/off 플래그가 필요 없다
- **처리되지 않은 예외(5xx)만 보낸다.** `sentry.exception-resolver-order`를 최하위로 미뤄
  자동 resolver를 무력화하고, `GlobalExceptionHandler`의 최종 핸들러에서
  `Sentry.captureException`을 명시적으로 호출한다
- **DSN이 없어도 기동은 성공한다** (`${SENTRY_DSN:}`). DB 설정은 값이 없으면 기동에 실패하도록
  기본값을 두지 않았지만 Sentry는 성격이 다르다 — 모니터링 설정 하나 때문에 API 전체가
  기동 실패하는 쪽이 더 나쁘다
- **사용자 식별 정보를 수집하지 않는다** (`send-default-pii: false`). 로그인이 MVP 제외(ADR-0004)라
  식별할 주체가 없고, 요청 헤더·IP도 보내지 않는다

## 결과

- `GlobalExceptionHandler`에 `@ExceptionHandler(Exception.class)`가 추가되면서, 지금까지 Spring 기본
  포맷(`timestamp`/`status`/`error`/`path`)으로 나가던 500 응답이 4xx와 같은 `ErrorResponse` 포맷으로
  통일된다. 내부 예외 메시지는 노출하지 않는다
- 이 핸들러는 Spring MVC 표준 예외를 담당하는 `DefaultHandlerExceptionResolver`보다 먼저 실행되므로,
  `org.springframework.web.ErrorResponse` 구현체를 `instanceof`로 걸러 원래 상태코드를 유지한다.
  이 분기가 없으면 405·415가 모두 500으로 바뀐다 — 회귀 테스트로 고정했다
- Sentry로 실제 전송되는지는 자동 테스트로 검증하지 않는다. `Sentry.captureException`이 static이라
  래퍼 인터페이스 없이는 관측할 수 없는데, 구현체가 하나뿐인 인터페이스를 유지하는 비용 대비 얻는 것이
  적다고 판단했다. 배포 후 1회 수동 검증하고, 이후 장기간 이슈가 0건이면 설정을 의심한다
- 이미지 빌드 시 커밋 SHA가 `SENTRY_RELEASE`로 구워져 이슈마다 배포 버전이 표시된다
- 서블릿 필터나 `@Scheduled`/`@Async`에서 발생하는 예외는 이 경로를 타지 않아 잡히지 않는다.
  현재 해당 사용처가 없으며(인증 필터는 AUTH-DISABLED), 도입 시 재검토한다
```

- [ ] **Step 2: `AGENTS.md`에 Boot 4 함정 추가**

`## 기술 스택 주의사항 (중요)` 섹션의 Boot 4 목록에서, QueryDSL 항목 바로 아래·
"확실치 않으면 공식 문서..." 항목 바로 위에 추가한다.

```markdown
  - Sentry는 Boot 4 전용 모듈 `io.sentry:sentry-spring-boot-4` 사용
    (`sentry-spring-boot-starter-jakarta`는 Boot 3용). Boot 4 BOM이 버전을 관리하지 않아 버전 명시 필요
```

- [ ] **Step 3: 검증**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (문서만 바뀌었으므로 영향은 없어야 한다).

- [ ] **Step 4: 커밋**

```bash
git add docs/adr/0010-sentry-error-tracking.md AGENTS.md
git commit -m "docs: Sentry 도입 ADR 추가"
```

---

## 완료 후 절차 (AGENTS.md 규칙)

1. **`./gradlew check` 최종 확인** — 통과하지 않으면 완료로 보고하지 않는다.
2. **퀴즈 게이트** — 구현 코드·코드 흐름에 대한 퀴즈 5개를 사용자에게 낸다.
   못 맞추면 커밋 완료로 넘어가지 않고 통과할 때까지 다른 퀴즈를 낸다 (학습 목적 프로젝트).
3. **크로스 코드체크** — `/codex review`로 독립 2차 리뷰. P1 발견 시 FAIL 게이트.

## 배포 후 수동 검증 (사용자 작업)

이 계획의 코드 작업으로는 "Sentry에 실제로 도달하는지"를 확인할 수 없다. 다음 중 하나로 검증한다.

- **로컬 검증(배포 전 권장)** — `application-local.yaml`에 `sentry.dsn`을 임시로 넣고 앱을 띄운 뒤
  예외를 발생시켜 Sentry 콘솔에 이슈가 뜨는지 확인한다. 확인 후 설정을 되돌린다.
  운영 코드에 예외를 유발하는 테스트용 엔드포인트를 남기지 않는다.
- **배포 후 검증** — `main` 배포 시 태스크 정의 rev 3이 적용되면서 `SENTRY_DSN`이 주입된다.
  첫 실제 에러가 Sentry에 뜨는지 확인한다.

## 남은 후속 작업 (이 계획 범위 밖)

- 노출된 Sentry auth token 회전 (릴리스 스코프, 대화 중 평문 노출됨)
- Sentry 콘솔에서 알림 채널 연동 (배포 알림용 Slack 워크스페이스 재사용 가능)
