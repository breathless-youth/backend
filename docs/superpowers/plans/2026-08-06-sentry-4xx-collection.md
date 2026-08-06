# Sentry 4xx 수집 확장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** prod에서 4xx(도메인 예외 + Spring MVC 표준 예외)도 5xx와 동일하게 Sentry 이슈로 수집되게 한다.

**Architecture:** `application-prod.yaml`의 `exception-resolver-order` 설정을 제거해 Sentry의 자동 `SentryExceptionResolver`가 기본 우선순위(최우선)로 복귀하게 하고, `GlobalExceptionHandler`의 수동 `Sentry.captureException` 호출을 제거해 중복 캡처를 막는다. HTTP 응답(상태코드·바디)은 전혀 바꾸지 않는다 — Sentry 캡처는 resolver 체인의 부수효과일 뿐이다.

**Tech Stack:** Spring Boot 4.1, Java 25, Sentry Java SDK 8.51.0(기존 의존성 그대로, 버전 변경 없음), JUnit 5 + AssertJ + MockMvcTester

**설계 문서:** `docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md`

## Global Constraints

- **생성자 주입만 사용** — `@Autowired` 필드 주입 금지 (이 작업은 대상 파일에 필드 주입이 없어 해당 없음, 참고용).
- **Spotless(palantirJavaFormat 2.96.0)** 포맷을 따른다 — 커밋 전 `./gradlew spotlessApply`. `removeUnusedImports()`가 설정돼 있어 미사용 import는 자동 제거되지만, 직접 지워도 무방하다.
- **Checkstyle `maxWarnings=0`** (`config/checkstyle/checkstyle.xml`, toolVersion 10.21.0) — 위반 시 `./gradlew check` 실패.
- **`ddl-auto`는 validate 고정** — 이 작업에 스키마 변경은 없다.
- **실패한 테스트를 지우거나 `@Disabled`로 회피 금지.**
- 작업 완료 선언 전 반드시 **`./gradlew check`** 통과를 확인한다.
- 커밋은 Conventional Commits (`<type>: <설명>`), 한 커밋은 한 가지 목적만.
- **새 자동 테스트를 추가하지 않는다** — `Sentry.captureException`이 static이라 캡처 여부를 코드로 검증할 수 없다는 기존 결정(ADR-0010)을 그대로 따른다. 이 작업의 "테스트"는 기존 회귀 테스트가 계속 통과하는지 확인하는 것뿐이다.

---

### Task 1: Sentry 캡처를 자동 resolver로 일원화

`application-prod.yaml`의 `exception-resolver-order` 설정과 `GlobalExceptionHandler`의 수동 캡처 호출은 서로 짝을 이루는 하나의 메커니즘이다 — 둘 중 하나만 바꾸면 5xx가 아예 안 잡히거나 이슈가 2개로 중복되므로, 반드시 함께 바꾼다.

**Files:**
- Modify: `src/main/resources/application-prod.yaml:63-81`
- Modify: `src/main/java/project/study/common/GlobalExceptionHandler.java`
- Modify: `src/test/java/project/study/common/GlobalExceptionHandlerTest.java:14-18` (주석만, 동작 변경 없음)

**Interfaces:**
- Consumes: 없음 (기존 Sentry Spring Boot 4 자동설정이 이미 클래스패스에 있음, `build.gradle` 변경 없음)
- Produces: 없음 (이 작업이 API의 최종 소비자 — 이후 태스크는 문서만 다룬다)

- [ ] **Step 1: `GlobalExceptionHandler.java`에서 수동 캡처 제거**

파일 전체를 아래 내용으로 교체한다.

```java
package project.study.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(BadRequestException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError == null) {
            return new ErrorResponse("요청 값이 올바르지 않습니다");
        }
        return new ErrorResponse(fieldError.getField() + ": " + fieldError.getDefaultMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse("요청 본문을 읽을 수 없습니다");
    }

    /**
     * 위의 어떤 핸들러도 처리하지 못한 예외를 마지막으로 받는다.
     * Sentry 전송은 여기서 하지 않는다 — SentryExceptionResolver가 이 핸들러보다 먼저 실행되어
     * 이미 캡처했으므로 (설계: docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md),
     * 여기서 또 호출하면 같은 예외가 이슈 2개로 중복된다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 405·415 같은 Spring MVC 표준 예외를 500으로 둔갑시키지 않는다.
        // 이 핸들러는 표준 예외를 담당하는 DefaultHandlerExceptionResolver보다 먼저 실행되므로
        // 여기서 걸러내지 않으면 상태코드가 뭉개진다.
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            HttpStatusCode status = standard.getStatusCode();
            if (status.is5xxServerError()) {
                return ResponseEntity.status(status).body(new ErrorResponse("서버 오류가 발생했습니다"));
            }
            return ResponseEntity.status(status).body(new ErrorResponse("요청을 처리할 수 없습니다"));
        }
        // 내부 예외 메시지는 노출하지 않는다
        return ResponseEntity.internalServerError().body(new ErrorResponse("서버 오류가 발생했습니다"));
    }
}
```

바뀐 점: `import io.sentry.Sentry;` 삭제, 65행·70행의 `Sentry.captureException(e);` 삭제, 관련 주석을 새 동작에 맞게 갱신. 리턴 타입·메서드 시그니처·상태코드 매핑은 전부 동일하다.

- [ ] **Step 2: 이 클래스만 테스트해서 회귀 확인**

Run: `./gradlew test --tests "project.study.common.GlobalExceptionHandlerTest"`
Expected: BUILD SUCCESSFUL, 7개 테스트 전부 PASS (특히 `지원하지_않는_HTTP_메서드는_405를_유지한다`, `표준_예외라도_5xx면_서버_오류_메시지로_응답한다` — 이 둘이 Sentry 캡처와 무관하게 상태코드·메시지만 검증하므로 그대로 통과해야 정상이다).

- [ ] **Step 3: `application-prod.yaml`에서 `exception-resolver-order` 제거**

63~81행 블록을 아래로 교체한다 (주석 문구를 새 동작에 맞게 갱신, `exception-resolver-order` 줄과 그 설명 주석 삭제).

```yaml
# 처리되지 않은 예외(4xx+5xx)를 전부 Sentry로 보낸다.
# 설계: docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md
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
```

`exception-resolver-order`를 지우면 Sentry Spring Boot 통합 기본값(`Integer.MIN_VALUE` = 최우선순위)으로 돌아가, `SentryExceptionResolver`가 `@ExceptionHandler`보다 먼저 실행되어 4xx·5xx 구분 없이 모든 예외를 캡처한다.

- [ ] **Step 4: YAML 문법 확인**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/application-prod.yaml')); print('YAML OK')"`
Expected: `YAML OK` 출력.

- [ ] **Step 5: `GlobalExceptionHandlerTest.java` 상단 주석의 설계 문서 참조 갱신**

14~18행 클래스 Javadoc을 아래로 교체한다 (동작 변경 없음, 참조 문서만 최신화).

```java
/**
 * 전역 예외 핸들러가 내리는 상태코드와 응답 포맷만 검증한다.
 * Sentry 전송 여부는 자동 검증하지 않는다 — Sentry.captureException이 static이라
 * 래퍼 없이는 호출을 관측할 수 없고, 래퍼를 두지 않기로 결정했다
 * (설계: docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md).
 *
 * <p>DB가 필요 없는 검증이라 standalone으로 띄워 Testcontainers 기동 비용을 피한다.
 */
```

- [ ] **Step 6: 전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL. `common` 패키지 테스트뿐 아니라 `StudySessionApiTest`, `StudySessionIdempotencyApiTest`, `StudySessionMinDurationApiTest` 등 기존 4xx 통합테스트도 전부 그린이어야 한다 — 이 작업은 응답 자체를 바꾸지 않으므로 실패하면 회귀다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/application-prod.yaml \
        src/main/java/project/study/common/GlobalExceptionHandler.java \
        src/test/java/project/study/common/GlobalExceptionHandlerTest.java
git commit -m "feat: Sentry가 4xx도 수집하도록 자동 캡처로 전환"
```

---

### Task 2: ADR 문서화

**Files:**
- Create: `docs/adr/0011-sentry-4xx-collection.md`
- Modify: `docs/adr/0010-sentry-error-tracking.md:3`

**Interfaces:**
- Consumes: Task 1에서 확정된 동작 (4xx+5xx 전부 자동 캡처, 알림 정책 변경 없음)
- Produces: 없음 (문서 전용, 이후 태스크 없음)

- [ ] **Step 1: `docs/adr/0011-sentry-4xx-collection.md` 작성**

```markdown
# ADR-0011: Sentry에 4xx도 함께 수집

- 상태: 승인
- 날짜: 2026-08-06

## 맥락

ADR-0010에서는 알림이 노이즈로 죽는 것을 막기 위해 "처리되지 않은 5xx만" Sentry로
보내기로 했다. `NotFoundException`(404)·`ConflictException`(409) 같은 도메인 4xx는
"정상적인 클라이언트 오류"로 분류해 의도적으로 제외했다.

하지만 이 프로젝트는 Sentry의 알림 기능 자체를 쓰지 않는다. 운영 알림은 CloudWatch
경보가 5xx 기준으로 전담하고 있고, Sentry는 이슈를 눈으로 훑어보는 대시보드 용도로만
쓰인다. 즉 ADR-0010이 걱정한 "노이즈가 알림을 죽인다"는 리스크는 Sentry에는 애초에
해당하지 않았다. 반면 4xx 중 일부(특히 `NotFoundException` — 존재하지 않는 리소스
조회)는 잘못된 ID 참조, 삭제된 리소스 재요청 같은 클라이언트 로직 버그의 신호일 수
있는데, 지금까지는 이걸 확인할 방법이 CloudWatch 로그를 직접 뒤지는 것뿐이었다.

## 결정

- **4xx도 5xx와 동일하게 전부 Sentry로 보낸다.** 도메인 예외
  (`BadRequestException`/`InvalidSessionException`, `NotFoundException`,
  `ConflictException`/`DuplicateSessionException`)와 Spring MVC 표준 예외
  (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, 405/415 등)
  를 가리지 않는다
- **`application-prod.yaml`의 `exception-resolver-order` 설정을 제거한다.** 이 설정이
  정확히 ADR-0010이 4xx를 걸러내던 메커니즘이었다. 지우면 Sentry의
  `SentryExceptionResolver`가 기본값(최우선순위)으로 돌아가 `@ExceptionHandler`보다
  먼저 모든 예외를 캡처한다
- **`GlobalExceptionHandler`의 수동 `Sentry.captureException` 호출을 제거한다.** 자동
  resolver가 이미 캡처하므로 남겨두면 5xx가 이슈 2개로 중복된다
- **알림 정책은 바꾸지 않는다.** Sentry 알림은 여전히 쓰지 않고, CloudWatch 경보가
  5xx 알람을 그대로 전담한다
- **4xx/5xx 심각도(level) 구분은 하지 않는다.** 지금은 전부 동일하게 캡처하는 것으로
  충분하다. 이슈 목록이 실제로 훑어보기 불편해지면 그때 `beforeSend` 콜백으로 구분하는
  방법을 별도로 검토한다

## 결과

- HTTP 응답(상태코드·바디)은 전혀 바뀌지 않는다. Sentry 캡처는 resolver 체인의
  부수효과일 뿐 `@ExceptionHandler`의 리턴값에 관여하지 않는다
- Sentry 콘솔의 이슈 발생량이 크게 늘어난다. 알림을 쓰지 않으므로 당장 문제는 없다
- `Sentry.captureException`이 static이라 "실제로 Sentry에 도달하는지"는 여전히 자동
  테스트로 검증하지 않는다 (ADR-0010과 동일한 판단). 배포 후 1회 수동 검증하고, 이후
  4xx 이슈가 전혀 없으면 설정을 의심한다
- 설계: `docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md`
```

- [ ] **Step 2: `docs/adr/0010-sentry-error-tracking.md:3`의 상태 갱신**

```diff
- - 상태: 승인
+ - 상태: 부분 대체됨 (4xx/5xx 수집 범위는 ADR-0011로 대체)
```

전체를 폐기 처리하지 않는 이유: PII 미수집·DSN 처리·prod 전용 활성화·release 태깅 등
나머지 결정은 그대로 유효하기 때문이다. 바뀐 것은 "무엇을 보낼지"뿐이다.

- [ ] **Step 3: 검증**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (문서만 바뀌었으므로 영향은 없어야 한다).

- [ ] **Step 4: 커밋**

```bash
git add docs/adr/0011-sentry-4xx-collection.md docs/adr/0010-sentry-error-tracking.md
git commit -m "docs: Sentry 4xx 수집 확장 ADR 추가"
```

---

## 완료 후 절차 (AGENTS.md 규칙)

1. **`./gradlew check` 최종 확인** — 통과하지 않으면 완료로 보고하지 않는다.
2. **퀴즈 게이트** — 구현 코드·코드 흐름에 대한 퀴즈 5개를 사용자에게 낸다.
   못 맞추면 커밋 완료로 넘어가지 않고 통과할 때까지 다른 퀴즈를 낸다 (학습 목적 프로젝트).
3. **크로스 코드체크** — `/codex review`로 독립 2차 리뷰. P1 발견 시 FAIL 게이트.

## 배포 후 수동 검증 (사용자 작업)

이 계획의 코드 작업으로는 "Sentry에 실제로 4xx가 도달하는지"를 확인할 수 없다.
음수 순서값은 `application-prod.yaml`에만 있어 local 프로파일에서는 검증되지
않으므로(local은 여전히 order 1), 로컬 검증 절차는 두지 않는다.

- **배포 후 검증** — `main` 배포로 새 설정이 반영되면, 존재하지 않는 사용자 조회 같은
  4xx 하나와 5xx 하나를 각각 유발해 둘 다 Sentry에 뜨는지 확인한다.

## 남은 후속 작업 (이 계획 범위 밖)

- 4xx/5xx 심각도(level) 구분 — 이슈 목록이 훑어보기 불편해질 때 재검토
- `docs/superpowers/plans/2026-08-02-sentry-error-tracking.md`의
  "완료 후 절차"에는 `/codex challenge security` 게이트가 없지만, 현재 AGENTS.md의
  "크로스 코드체크" 절에는 이미 반영돼 있다 — 이 계획도 AGENTS.md 최신 규칙을 따른다.
