# CLAUDE.md

## 프로젝트 개요
<!-- TODO: 서비스 한 줄 설명으로 교체 -->
Spring Boot 4.1 + Java 25(LTS) + PostgreSQL. Spring Security(OAuth2 클라이언트 로그인) 사용.

## 자주 쓰는 명령어
```bash
docker compose up -d                                        # PostgreSQL 기동
./gradlew check                                             # 전체 검증 (테스트+포맷+린트+아키텍처)
./gradlew test --tests "project.study.<도메인>.*"           # 특정 패키지 테스트만
./gradlew bootRun --args='--spring.profiles.active=local'   # 로컬 실행
curl -s localhost:8080/actuator/health                      # 기동 확인
./gradlew spotlessApply                                     # 포맷 자동 수정
```

## 아키텍처
- 패키지는 도메인 기준: `project.study.<도메인>` (예: `order/`, `member/`)
- 각 도메인 안: `XxxController`, `XxxService`, `XxxRepository`, 엔티티, DTO(record)
- 도메인 간 직접 참조 최소화. 공통은 `common/`, 보안 설정은 `config/`
- 규칙은 ArchUnit 테스트(`ArchitectureTest`)로 강제됨 — 어기면 check 실패

## 기술 스택 주의사항 (중요)
- **Spring Boot 4.x다.** 학습 데이터의 3.x 지식과 다른 부분:
  - `spring-boot-starter-web` → `spring-boot-starter-webmvc` (이미 적용됨)
  - 테스트 스타터도 모듈화됨 (`spring-boot-starter-webmvc-test` 등, 이미 적용됨)
  - Jackson 3 사용 (`tools.jackson` 패키지, `com.fasterxml.jackson` 아님)
  - Testcontainers 2.0 사용: 의존성은 `org.testcontainers:testcontainers-postgresql`,
    클래스는 `org.testcontainers.postgresql.PostgreSQLContainer` (구 `org.testcontainers.containers` 아님)
  - Boot 4 BOM에서 빠진 의존성(rest-assured 등)은 버전 명시 필요 (6.0.1+이 Spring 7 대응)
  - Flyway는 `flyway-core`만으론 자동설정 안 됨 → `spring-boot-starter-flyway` 필수 (없으면 조용히 무시됨)
  - ArchUnit은 1.4.2+ 사용 (구버전은 Java 25+ 클래스 파일을 못 읽음)
  - Spotless의 palantir-java-format은 버전 명시 필수 (기본 내장 버전은 JDK 25+의 javac 내부 API 변경으로 깨짐)
  - 확실치 않으면 공식 문서/마이그레이션 가이드 확인 후 작성
- DB 스키마는 Flyway 마이그레이션(`src/main/resources/db/migration/`)으로만 변경. `ddl-auto`는 validate 고정
- DTO는 Java record 사용. 엔티티에는 Lombok 사용 가능하되 `@Data` 금지 (`@Getter` + 명시적 생성자 권장)
- 생성자 주입만 사용 (`@Autowired` 필드 주입 금지, Lombok `@RequiredArgsConstructor` 권장)

## 작업 규칙
1. 기능 구현 시 테스트를 함께 작성 (서비스 로직은 단위테스트, API는 통합테스트)
2. 작업 완료 선언 전 반드시 `./gradlew check` 실행하고 통과 확인
3. 실패한 테스트를 지우거나 @Disabled 처리로 회피 금지
4. 중요한 설계 결정은 `docs/adr/`에 ADR 추가 (기존 파일 형식 참고)
5. 새 의존성 추가는 먼저 이유를 설명하고 승인받기
6. Security 설정 변경(permitAll 추가 등)은 반드시 변경 이유를 설명하고 승인받기

## 테스트
- 통합테스트는 Testcontainers로 실제 PostgreSQL 사용 (`TestcontainersConfiguration` import)
- API 테스트에서 인증이 필요하면 `spring-security-test`의 `@WithMockUser` / `SecurityMockMvcRequestPostProcessors.oauth2Login()` 사용

## 커밋 컨벤션
[Conventional Commits](https://www.conventionalcommits.org/) 형식 사용: `<type>: <설명>`

- `feat`: 새 기능
- `fix`: 버그 수정
- `refactor`: 동작 변화 없는 코드 개선
- `test`: 테스트 추가/수정
- `docs`: 문서(CLAUDE.md, ADR, README 등)
- `chore`: 빌드 설정, 의존성 등 잡무
- `style`: 포맷팅(Spotless 자동 정리 등, 로직 변경 없음)

예: `feat: 회원 가입 API 추가`, `fix: study_session 조회 시 N+1 발생 수정`

규칙:
- 한 커밋은 한 가지 목적만 (기능 추가와 리팩토링을 한 커밋에 섞지 않기)
- 제목은 명령형/간결하게, 50자 내외
- `./gradlew check` 통과한 상태에서만 커밋 (커밋을 검증 단위로 취급)
- 커밋 메시지 본문에 "왜"가 필요하면 본문에 추가 (ADR 갈 정도가 아닌 자잘한 결정)
