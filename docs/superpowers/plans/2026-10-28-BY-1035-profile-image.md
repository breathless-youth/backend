# 프로필 사진 업로드 URL과 응답 필드 (BY-1035) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 프로필 사진을 올리고 바꾸고 되돌릴 수 있게 하고, 프로필·소셜룸·랭킹 응답에 사진 경로(`profileImageUrl`)를 싣는다.

**Architecture:** 서버는 바이트를 받지 않는다. `POST upload-url`이 5분짜리 presigned PUT URL과 서버가 만든 키(`avatars/{userId}/{UUID}.jpg`)를
주고, 브라우저가 S3에 바로 올린 뒤 `PUT profile-image {key}`로 등록한다. 등록 때 `HeadObject`로 존재·크기·형식을 확인하고, `users.profile_image`를
바꾼 트랜잭션이 커밋된 뒤 이전 파일을 지운다. S3 호출은 `ProfileImageStorage` 한 클래스, 키→경로 변환은 `ProfileImageUrls` 한 클래스가 맡고,
룸은 입장 때 사진 키를 `room_participations`에 복사하며, 랭킹은 닉네임 조인 쿼리에서 사진 키를 같이 읽는다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL 17, Flyway, `JdbcClient`, Jackson 3(`tools.jackson`), AWS SDK for Java v2 `s3`
(BOM `2.56.0`), JUnit 5 + AssertJ + Mockito, `MockMvcTester`, Testcontainers 2.

**Spec:** `docs/superpowers/specs/2026-10-28-BY-1035-profile-image-design.md`

## Global Constraints

- 새 의존성은 `software.amazon.awssdk:bom`(platform) + `software.amazon.awssdk:s3` 둘만이다(사용자 승인됨). 다른 AWS 모듈을 직접 추가하지 않는다.
- 세 경로 모두 `API-Version: 2`, 인증 필요: `POST /api/users/me/profile-image/upload-url`, `PUT /api/users/me/profile-image`, `DELETE /api/users/me/profile-image`. `SecurityConfig` 변경 없음(`anyRequest().authenticated()`에 걸린다).
- `contentLength`가 1 미만이거나 1,048,576(1MB)을 넘으면 400 `VALIDATION_FAILED`.
- 키는 `avatars/{userId}/{UUID}.jpg`로 서버가 만든다. 서명에 `Content-Type: image/jpeg`와 요청한 `Content-Length`를 넣는다. URL 유효 시간 5분.
- `PUT`의 400 `BAD_REQUEST`: 키 형식이 아니거나 남의 키 / S3에 파일 없음 / 크기 1MB 초과 또는 `Content-Type`이 `image/jpeg`가 아님(이때는 그 파일을 지우고 400).
- 같은 키를 다시 `PUT`하면 아무것도 지우지 않고 200. `DELETE`는 사진이 없어도 200.
- 이전 파일 삭제는 DB 커밋 뒤. 삭제 실패는 경고 로그만 남기고 요청은 성공.
- 응답 주소는 `base-url + "/" + key`. `base-url` 기본값은 빈 문자열(지금은 상대 경로 `/avatars/...`).
- 설정 키: `app.profile-image.bucket`, `app.profile-image.region`, `app.profile-image.base-url`. 운영 버킷 `focus-makers-prod-avatars-821365066487`, 개발 서버 `focus-makers-staging-avatars-821365066487`, 리전 `ap-northeast-2`.
- 자격 증명은 SDK 기본 체인(운영 ECS 태스크 역할, 개발 서버 EC2 인스턴스 역할, 로컬 `AWS_PROFILE`). 자격 증명이 없는 환경(테스트·로컬)에서도 앱이 떠야 한다.
- `ProfileResponse`·`RoomMember`·랭킹 `BoardEntry`·`BoardNeighbor`·`DayGroupRow`에 nullable `profileImageUrl`을 추가한다.
- V30: `room_participations.profile_image varchar`. 다음 ADR 번호는 0033.
- 룸 STOMP 계약이 바뀌므로 `src/main/resources/docs/websocket.html`과 그 변경 이력을 같은 작업에서 고친다(CLAUDE.md 8번).
- CLAUDE.md: DTO는 record, 생성자 주입만(`@Autowired` 금지 — ArchUnit), `@Profile` 금지, `@Data` 금지. API 통합 테스트는 `MockMvcTester` + `AuthTestSupport.asUser`(기본으로 `API-Version: 2`를 싣는다).
- 테스트에서 S3는 `@TestConfiguration`의 `@Bean @Primary` Mockito 목으로 바꾼다(`@MockitoBean`은 이 저장소에서 쓰지 않는다).
- checkstyle(테스트 포함): 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하, 안 쓰는 import 금지. 포맷은 `./gradlew spotlessApply`.
- 코드 주석은 최소로, 한국어로, "왜"만 쓴다. 코드 주석에 티켓 번호를 넣지 않는다(마이그레이션 SQL 주석 포함).
- **태스크 안에서 커밋하지 않는다.** 구현·리뷰·퀴즈 5문항 게이트를 지난 뒤 사용자가 따로 승인한다. 각 태스크의 마지막 단계는 "변경 파일 목록을 보고서에 적는다"이고, 커밋 분할안은 맨 끝 절에 있다.

## 설계 보충 (spec에 없어 이 계획이 정한 것)

| 항목 | 정한 것 | 이유 |
|---|---|---|
| 설정 읽기 | `@ConfigurationProperties` 대신 `@Value`(기본값 있음). 운영 yaml만 `${APP_PROFILE_IMAGE_BUCKET}`(기본값 없음) | Boot 바인더는 못 푼 `${...}`를 문자열 그대로 넣어 조용히 뜬다. `@Value`는 중첩 placeholder를 못 풀면 기동이 실패한다(dev yaml 머리 주석과 같은 동작). 기본값이 있어 팀원의 기존 `application-local.yaml`도 그대로 뜬다 |
| 키→경로 변환 | `ProfileImageUrls`(컴포넌트, `of(key)`) | `ProfileImageStorage`는 통합 테스트에서 목이라 거기 두면 경로가 null이 된다 |
| 커밋 뒤 삭제 | `ProfileImageService`는 트랜잭션 없이 `UserService.replaceProfileImage`(`@Transactional`)를 부르고, 그 호출이 돌아온 뒤 지운다 | 프록시 호출이 돌아온 시점이 곧 커밋 뒤라 `TransactionSynchronization` 등록 없이 순서가 보장되고, 단위 테스트에서 목 `InOrder`로 확인된다 |
| 룸 사진 키 출처 | `room_participations`의 세 입장 SQL이 같은 문장 안에서 `users.profile_image`를 서브쿼리로 복사한다 | 컨트롤러가 받는 `ProfileResponse`에는 키가 아니라 경로만 있다. `RoomService.join` 시그니처·`Profile` 레코드·테스트 호출부를 건드리지 않는다 |
| 필드 위치 | `ProfileResponse`는 맨 뒤, `RoomMember`는 `category` 뒤, 랭킹 세 레코드는 `nickname` 바로 뒤 | 같은 사람을 가리키는 필드끼리 붙인다 |
| `PUT`의 `key` 누락·빈 값 | 400 `BAD_REQUEST`(형식 오류와 같은 경로) | spec은 키 오류를 모두 `BAD_REQUEST`로 묶었다 |
| 거부한 파일 삭제 실패 | 경고 로그만 남기고 400 | 이전 파일 삭제와 같은 규칙. 남은 파일은 어디서도 참조하지 않는다 |
| 랭킹 진행 중 줄 | `activeNicknames` → `activeProfiles`(`Map<Long, RankingProfile>`) | 진행 중 조각만 있는 사람도 사진 키가 필요하다 |
| `RankingEntry.of` | 4인자(사진 null)는 남기고 5인자를 더한다 | 가정한 줄·테스트 10여 곳이 4인자를 쓴다 |
| 마감된 판 | 따로 할 일 없음 | 지난 기간 판도 `StandingsCalculator`가 같은 쿼리로 다시 계산한다. 마감 테이블(`ranking_record` 등)에는 닉네임이 없고 본인 기록만 준다 |

---

## File Structure

| 파일 | 역할 |
|---|---|
| `build.gradle` | AWS SDK BOM + `s3` |
| `src/main/java/project/study/user/image/ProfileImageConfig.java` (생성) | `S3Client`·`S3Presigner` 빈 |
| `src/main/java/project/study/user/image/ProfileImageStorage.java` (생성) | presign PUT·`HeadObject`·`DeleteObject` |
| `src/main/java/project/study/user/image/ProfileImageUrls.java` (생성) | 키 → 응답 경로 |
| `src/main/java/project/study/user/service/ProfileImageService.java` (생성) | 키 발급·소유 확인·등록·되돌리기·이전 파일 삭제 |
| `src/main/java/project/study/user/dto/ProfileImageUploadUrlRequest.java`, `ProfileImageUploadUrlResponse.java`, `ProfileImageUpdateRequest.java` (생성) | 요청·응답 |
| `src/main/java/project/study/user/controller/UserController.java` | 세 엔드포인트 |
| `src/main/java/project/study/user/dto/ProfileResponse.java`, `user/entity/User.java`, `user/service/UserService.java` | `profileImageUrl`, 키 교체 |
| `src/main/resources/db/migration/V30__room_participation_profile_image.sql` (생성) | 룸 스냅샷 열 |
| `src/main/java/project/study/room/dto/RoomMember.java`, `room/repository/RoomParticipationRepository.java`, `room/websocket/StompEventListener.java` | 룸 멤버 사진 경로 |
| `src/main/resources/docs/websocket.html` | STOMP 계약 문서·변경 이력 |
| `src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java`, `studysession/service/RankingSource.java`, `studysession/dto/Ranking{Total,Days,Streak}Row.java`, `studysession/dto/RankingProfile.java` (생성) | 랭킹 쿼리 사진 키 |
| `src/main/java/project/study/ranking/engine/RankingEntry.java`, `ranking/engine/StandingsCalculator.java`, `ranking/dto/RankingBoardResponse.java`, `ranking/service/RankingBoardService.java`, `ranking/service/BoardExtras.java` | 랭킹 응답 사진 경로 |
| `docs/adr/0033-profile-image-presigned-upload.md`, `docs/runbooks/profile-image-removal.md` (생성) | 결정 기록·운영자 삭제 절차 |
| `src/main/resources/application-{dev,prod}.yaml`, `application-local.yaml.example`, `src/test/resources/application.yaml` | 설정 |

---

### Task 1: AWS SDK 의존성과 `ProfileImageStorage`

**Files:**
- Modify: `build.gradle` (dependencies 블록, `// --- 데이터 / DB ---` 묶음 앞)
- Create: `src/main/java/project/study/user/image/ProfileImageConfig.java`
- Create: `src/main/java/project/study/user/image/ProfileImageStorage.java`
- Test: `src/test/java/project/study/user/image/ProfileImageStorageTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ProfileImageStorage` (`@Component`), 생성자 `ProfileImageStorage(S3Presigner presigner, S3Client s3, @Value("${app.profile-image.bucket:}") String bucket)`
  - `public static final String CONTENT_TYPE = "image/jpeg";`, `static final Duration URL_TTL = Duration.ofMinutes(5);`
  - `public PresignedUpload presignPut(String key, long contentLength)` — `record PresignedUpload(String url, Instant expiresAt)` (중첩, public)
  - `public Optional<StoredObject> head(String key)` — 404면 empty, 그 밖의 S3 오류는 그대로 던진다. `record StoredObject(long size, String contentType)` (중첩, public)
  - `public void delete(String key)`
  - 빈 `S3Client profileImageS3Client`, `S3Presigner profileImageS3Presigner` (`ProfileImageConfig`)

- [ ] **Step 1: 의존성 추가**

`build.gradle`의 `// --- 데이터 / DB ---` 바로 앞에 넣는다.

```gradle
	// --- 파일 저장 (S3) ---
	// 프로필 사진 업로드 URL 발급·확인·삭제. Boot BOM이 AWS SDK를 관리하지 않아 SDK BOM으로 버전을 맞춘다
	implementation platform('software.amazon.awssdk:bom:2.56.0')
	implementation 'software.amazon.awssdk:s3'
```

버전이 실제로 풀리는지 확인한다.

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -E "software.amazon.awssdk:(s3|apache-client|auth):"`
Expected: `software.amazon.awssdk:s3 -> 2.56.0`와 `apache-client`·`auth`가 같은 버전으로 나온다. 풀리지 않으면 Maven Central에서 2주 이상 된 최신 2.x로 바꾸고 바꾼 버전을 보고서에 적는다.

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/project/study/user/image/ProfileImageStorageTest.java`

```java
package project.study.user.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.study.user.image.ProfileImageStorage.PresignedUpload;
import project.study.user.image.ProfileImageStorage.StoredObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ProfileImageStorageTest {

    private static final String KEY = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg";

    // 고정 자격 증명이면 서명은 네트워크 없이 로컬에서 계산된다
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
            .build();

    private final S3Client s3 = mock(S3Client.class);

    private final ProfileImageStorage storage = new ProfileImageStorage(presigner, s3, "test-avatars");

    @Test
    void 업로드_URL은_크기와_형식을_서명하고_5분_뒤_만료된다() {
        Instant before = Instant.now();

        PresignedUpload upload = storage.presignPut(KEY, 123_456);

        Map<String, String> query = query(upload.url());
        assertThat(upload.url()).startsWith("https://test-avatars.s3.ap-northeast-2.amazonaws.com/" + KEY + "?");
        assertThat(query.get("X-Amz-SignedHeaders").split(";")).contains("content-length", "content-type", "host");
        assertThat(query).containsEntry("X-Amz-Expires", "300");
        // 브라우저 PUT은 체크섬 헤더를 보내지 않는다 — 서명에 체크섬이 끼면 업로드가 거부된다
        assertThat(query.keySet())
                .noneMatch(k -> k.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum")
                        || k.toLowerCase(Locale.ROOT).startsWith("x-amz-sdk-checksum"));
        assertThat(upload.expiresAt()).isBetween(before.plusSeconds(299), Instant.now().plusSeconds(301));
    }

    @Test
    void 파일이_있으면_크기와_형식을_준다() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(1000L)
                        .contentType("image/jpeg")
                        .build());

        assertThat(storage.head(KEY)).contains(new StoredObject(1000, "image/jpeg"));
    }

    @Test
    void 파일이_없으면_empty다() {
        doThrow(S3Exception.builder().statusCode(404).build()).when(s3).headObject(any(HeadObjectRequest.class));

        assertThat(storage.head(KEY)).isEmpty();
    }

    @Test
    void 권한_오류_같은_다른_S3_오류는_그대로_던진다() {
        doThrow(S3Exception.builder().statusCode(403).build()).when(s3).headObject(any(HeadObjectRequest.class));

        assertThatThrownBy(() -> storage.head(KEY)).isInstanceOf(S3Exception.class);
    }

    private static Map<String, String> query(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        p -> p[0], p -> URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.user.image.ProfileImageStorageTest"`
Expected: FAIL — `ProfileImageStorage`가 없어 컴파일 오류.

- [ ] **Step 4: 빈 설정 작성**

`src/main/java/project/study/user/image/ProfileImageConfig.java`

```java
package project.study.user.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// 자격 증명은 첫 호출 때 기본 체인에서 읽는다 — 자격 증명이 없는 테스트·로컬에서도 기동은 된다
@Configuration(proxyBeanMethods = false)
public class ProfileImageConfig {

    @Bean
    S3Client profileImageS3Client(@Value("${app.profile-image.region:ap-northeast-2}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    S3Presigner profileImageS3Presigner(@Value("${app.profile-image.region:ap-northeast-2}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
```

- [ ] **Step 5: 저장소 작성**

`src/main/java/project/study/user/image/ProfileImageStorage.java`

```java
package project.study.user.image;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** 프로필 사진 버킷의 S3 호출을 모두 맡는다. */
@Component
public class ProfileImageStorage {

    public static final String CONTENT_TYPE = "image/jpeg";
    static final Duration URL_TTL = Duration.ofMinutes(5);

    private final S3Presigner presigner;
    private final S3Client s3;
    private final String bucket;

    public ProfileImageStorage(
            S3Presigner presigner, S3Client s3, @Value("${app.profile-image.bucket:}") String bucket) {
        this.presigner = presigner;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public record PresignedUpload(String url, Instant expiresAt) {}

    public record StoredObject(long size, String contentType) {}

    // 크기·형식을 서명에 넣어 다른 크기나 형식의 본문은 S3가 거부하게 한다
    public PresignedUpload presignPut(String key, long contentLength) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(CONTENT_TYPE)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(URL_TTL)
                .putObjectRequest(put)
                .build());
        return new PresignedUpload(presigned.url().toString(), presigned.expiration());
    }

    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
```

- [ ] **Step 6: 테스트 통과 확인 + 서명 결과 기록**

Run: `./gradlew test --tests "project.study.user.image.ProfileImageStorageTest"`
Expected: PASS.

`업로드_URL은_크기와_형식을_서명하고_5분_뒤_만료된다`의 결과로 아래 둘 중 어느 경우인지 **보고서에 적는다**.

- **A. 통과** — SDK가 `content-length`를 서명한다. 크기는 서명(S3가 다른 크기의 본문을 거부)과 `PUT` 때의 `HeadObject` 두 번 확인된다. 그대로 둔다.
- **B. `content-length`가 없어서 실패** — SDK가 presigned PUT에 `Content-Length`를 서명하지 않는다. 단언을 사실대로 고친다:
  `.contains("content-type", "host")`와 `.doesNotContain("content-length")`로 바꾸고, 단언 위에 주석 한 줄
  `// SDK는 presigned PUT에 Content-Length를 서명하지 않는다 — 크기는 PUT 등록 때 HeadObject가 막는다`를 단다. spec의 `HeadObject` 확인이 크기를 강제한다. Task 6의 ADR 결정 3을 B에 맞춰 쓴다.
- **체크섬 단언이 실패하면** 멈추고 URL 전체(서명 값은 지워도 된다)를 보고서에 적는다. 설정을 추측해 고치지 않는다.

- [ ] **Step 7: 자격 증명 없이 앱이 뜨는지 확인**

AWS 환경 변수·설정 파일을 모두 가린 채 컨텍스트를 띄우는 기존 통합 테스트를 돌린다(데몬이 셸 환경을 물려받지 않게 `--no-daemon`).

Run: `env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true ./gradlew --no-daemon test --tests "project.study.user.ProfileApiTest"`
Expected: PASS. `S3Client`·`S3Presigner` 빌드는 자격 증명을 읽지 않는다(SDK는 첫 요청·첫 서명 때 체인을 푼다). 실패하면 스택트레이스 첫 줄을 보고서에 적는다.

- [ ] **Step 8: 변경 파일 목록을 보고서에 적는다**

`build.gradle`, `ProfileImageConfig.java`, `ProfileImageStorage.java`, `ProfileImageStorageTest.java`, 그리고 Step 6의 A/B 결과.

---

### Task 2: 업로드 URL 발급 API

**Files:**
- Create: `src/main/java/project/study/user/dto/ProfileImageUploadUrlRequest.java`
- Create: `src/main/java/project/study/user/dto/ProfileImageUploadUrlResponse.java`
- Create: `src/main/java/project/study/user/service/ProfileImageService.java`
- Modify: `src/main/java/project/study/user/controller/UserController.java` (필드 :37-38, 클래스 끝 :136 뒤에 메서드)
- Test: `src/test/java/project/study/user/service/ProfileImageServiceTest.java` (생성)
- Test: `src/test/java/project/study/user/ProfileImageApiTest.java` (생성)

**Interfaces:**
- Consumes: `ProfileImageStorage.presignPut(String, long)` → `PresignedUpload(String url, Instant expiresAt)` (Task 1)
- Produces:
  - `record ProfileImageUploadUrlRequest(Long contentLength)`, `public static final long MAX_BYTES = 1_048_576;`
  - `record ProfileImageUploadUrlResponse(String uploadUrl, String key, Instant expiresAt)`
  - `ProfileImageService.issueUploadUrl(Long userId, long contentLength)` → `ProfileImageUploadUrlResponse`
  - `ProfileImageApiTest`의 `@TestConfiguration` 목 빈 `profileImageStorageMock` (Task 3이 같은 파일에 테스트를 더한다)

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`src/test/java/project/study/user/service/ProfileImageServiceTest.java`

```java
package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

@ExtendWith(MockitoExtension.class)
class ProfileImageServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-28T10:05:00Z");

    @Mock
    private UserService userService;

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private ProfileImageService service;

    @Test
    void 업로드_URL은_본인_폴더의_UUID_jpg_키로_발급한다() {
        when(storage.presignPut(anyString(), eq(123_456L)))
                .thenReturn(new PresignedUpload("https://s3.example/upload", EXPIRES));

        ProfileImageUploadUrlResponse response = service.issueUploadUrl(12L, 123_456);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).presignPut(key.capture(), eq(123_456L));
        assertThat(key.getValue())
                .matches("avatars/12/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");
        assertThat(response).isEqualTo(new ProfileImageUploadUrlResponse("https://s3.example/upload", key.getValue(), EXPIRES));
    }
}
```

- [ ] **Step 2: 실패하는 API 테스트 작성**

`src/test/java/project/study/user/ProfileImageApiTest.java`

```java
package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileImageApiTest {

    @TestConfiguration
    static class StorageMockConfig {
        @Bean
        @Primary
        ProfileImageStorage profileImageStorageMock() {
            return mock(ProfileImageStorage.class);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProfileImageStorage storage;

    @BeforeEach
    void resetStorage() {
        reset(storage);
    }

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private static String keyOf(long userId) {
        return "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
    }

    private MockMvcTester.MockMvcRequestBuilder uploadUrl(long userId, String body) {
        return mvc.post()
                .uri("/api/users/me/profile-image/upload-url")
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void 업로드_URL과_키와_만료_시각을_준다() {
        long userId = user();
        when(storage.presignPut(anyString(), anyLong()))
                .thenReturn(new PresignedUpload("https://s3.example/upload", Instant.parse("2026-10-28T10:05:00Z")));

        assertThat(uploadUrl(userId, "{\"contentLength\": 123456}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.uploadUrl", v -> assertThat(v).isEqualTo("https://s3.example/upload"))
                .hasPathSatisfying("$.key", v -> assertThat(v).asString().matches("avatars/" + userId + "/[0-9a-f-]{36}\\.jpg"))
                .hasPathSatisfying("$.expiresAt", v -> assertThat(v).isEqualTo("2026-10-28T10:05:00Z"));
        verify(storage).presignPut(startsWith("avatars/" + userId + "/"), eq(123_456L));
    }

    @Test
    void 크기가_1_미만이거나_1MB를_넘거나_없으면_400_VALIDATION_FAILED다() {
        long userId = user();
        for (String body : new String[] {"{\"contentLength\": 0}", "{\"contentLength\": 1048577}", "{}"}) {
            assertThat(uploadUrl(userId, body))
                    .as(body)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("VALIDATION_FAILED"));
        }
    }

    @Test
    void 정확히_1MB는_발급한다() {
        long userId = user();
        when(storage.presignPut(anyString(), anyLong()))
                .thenReturn(new PresignedUpload("https://s3.example/upload", Instant.parse("2026-10-28T10:05:00Z")));

        assertThat(uploadUrl(userId, "{\"contentLength\": 1048576}")).hasStatusOk();
    }
}
```

`keyOf`는 Task 3이 쓴다. 이 태스크에서 안 쓰여 checkstyle이 막지는 않는다(안 쓰는 import만 막는다).

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.user.service.ProfileImageServiceTest" --tests "project.study.user.ProfileImageApiTest"`
Expected: FAIL — `ProfileImageService`·`ProfileImageUploadUrlResponse`가 없어 컴파일 오류.

- [ ] **Step 4: DTO 작성**

`src/main/java/project/study/user/dto/ProfileImageUploadUrlRequest.java`

```java
package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProfileImageUploadUrlRequest(
        @Schema(description = "올릴 JPEG의 바이트 수 — 1 이상 1,048,576(1MB) 이하", example = "123456")
        @NotNull @Min(1) @Max(MAX_BYTES)
        Long contentLength) {

    public static final long MAX_BYTES = 1_048_576;
}
```

`src/main/java/project/study/user/dto/ProfileImageUploadUrlResponse.java`

```java
package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ProfileImageUploadUrlResponse(
        @Schema(description = "S3 presigned PUT URL — 요청한 크기의 JPEG를 Content-Type: image/jpeg로 PUT한다")
        String uploadUrl,

        @Schema(description = "업로드 뒤 PUT /api/users/me/profile-image에 보낼 키", example = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String key,

        @Schema(description = "uploadUrl 만료 시각 (발급 5분 뒤)", example = "2026-10-28T10:05:00Z")
        Instant expiresAt) {}
```

- [ ] **Step 5: 서비스 작성 (발급만)**

`src/main/java/project/study/user/service/ProfileImageService.java`

```java
package project.study.user.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

@Slf4j
@Service
@RequiredArgsConstructor
// 트랜잭션을 걸지 않는다 — S3 호출 동안 DB 연결을 잡지 않고, UserService 호출이 돌아온 시점을 커밋 뒤로 쓰기 위해서다
public class ProfileImageService {

    private final UserService userService;
    private final ProfileImageStorage storage;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId, long contentLength) {
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        PresignedUpload upload = storage.presignPut(key, contentLength);
        return new ProfileImageUploadUrlResponse(upload.url(), key, upload.expiresAt());
    }
}
```

`userService`·`log`는 Task 3에서 쓴다. 이 태스크에서 안 쓰는 필드가 경고를 내면 그대로 둔다(checkstyle 대상 아님).

- [ ] **Step 6: 컨트롤러 엔드포인트 추가**

`UserController`에 필드와 메서드를 더한다. import: `project.study.user.dto.ProfileImageUploadUrlRequest`, `project.study.user.dto.ProfileImageUploadUrlResponse`, `project.study.user.service.ProfileImageService`.

```java
    private final ProfileImageService profileImageService;
```

```java
    @Operation(summary = "프로필 사진 업로드 URL 발급", description = """
                    S3에 바로 올릴 5분짜리 presigned PUT URL과 키를 준다. 브라우저는 요청한 크기와 같은 JPEG를 \
                    `Content-Type: image/jpeg`로 그 URL에 PUT한 뒤, 받은 `key`로 PUT /api/users/me/profile-image를 부른다.""")
    @ApiResponse(responseCode = "200", description = "발급 성공")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED — contentLength가 없거나 1 미만이거나 1,048,576(1MB) 초과",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/me/profile-image/upload-url", version = "2")
    public ProfileImageUploadUrlResponse issueProfileImageUploadUrl(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ProfileImageUploadUrlRequest request) {
        return profileImageService.issueUploadUrl(userId, request.contentLength());
    }
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.user.service.ProfileImageServiceTest" --tests "project.study.user.ProfileImageApiTest" --tests "project.study.user.*"`
Expected: PASS (기존 `ProfileApiTest`·`LegacyUserApiTest` 포함).

- [ ] **Step 8: 변경 파일 목록을 보고서에 적는다**

`ProfileImageUploadUrlRequest.java`, `ProfileImageUploadUrlResponse.java`, `ProfileImageService.java`, `UserController.java`, `ProfileImageServiceTest.java`, `ProfileImageApiTest.java`.

---

### Task 3: 사진 등록·되돌리기 API와 `ProfileResponse.profileImageUrl`

**Files:**
- Create: `src/main/java/project/study/user/image/ProfileImageUrls.java`
- Create: `src/main/java/project/study/user/dto/ProfileImageUpdateRequest.java`
- Modify: `src/main/java/project/study/user/dto/ProfileResponse.java:18-19` (`colorIndex` 뒤에 필드)
- Modify: `src/main/java/project/study/user/entity/User.java:84` 뒤 (메서드 추가)
- Modify: `src/main/java/project/study/user/service/UserService.java:38` (필드), `:95-104` (메서드 추가·`toProfileResponse` 인스턴스화)
- Modify: `src/main/java/project/study/user/service/ProfileImageService.java`
- Modify: `src/main/java/project/study/user/controller/UserController.java`
- Test: `src/test/java/project/study/user/service/ProfileImageServiceTest.java`, `src/test/java/project/study/user/service/UserServiceTest.java`, `src/test/java/project/study/user/ProfileImageApiTest.java`

**Interfaces:**
- Consumes: `ProfileImageStorage.head(String)` → `Optional<StoredObject(long size, String contentType)>`, `ProfileImageStorage.delete(String)`, `ProfileImageStorage.CONTENT_TYPE` (Task 1), `ProfileImageUploadUrlRequest.MAX_BYTES` (Task 2)
- Produces:
  - `ProfileImageUrls` (`@Component`), 생성자 `ProfileImageUrls(@Value("${app.profile-image.base-url:}") String baseUrl)`, `public String of(String key)` — null이면 null, 아니면 `baseUrl + "/" + key` (Task 4·5가 쓴다)
  - `ProfileResponse(String nickname, String goal, String category, String initial, Integer colorIndex, String profileImageUrl)`
  - `User.replaceProfileImage(String key)` → 이전 키(`String`)
  - `UserService.replaceProfileImage(Long userId, String key)` → `UserService.ProfileImageChange(ProfileResponse profile, String previousKey)` (`@Transactional`)
  - `ProfileImageService.updateProfileImage(Long userId, String key)` → `ProfileResponse`, `ProfileImageService.deleteProfileImage(Long userId)` → `ProfileResponse`
  - `record ProfileImageUpdateRequest(String key)`

- [ ] **Step 1: 실패하는 단위 테스트 작성 — `ProfileImageServiceTest`에 추가**

import 추가: `static org.assertj.core.api.Assertions.assertThatThrownBy`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.Mockito.doThrow`, `static org.mockito.Mockito.inOrder`, `static org.mockito.Mockito.never`, `static org.mockito.Mockito.verifyNoInteractions`, `java.util.Optional`, `org.mockito.InOrder`, `project.study.common.exception.BadRequestException`, `project.study.user.dto.ProfileResponse`, `project.study.user.image.ProfileImageStorage.StoredObject`.

```java
    private static final String MINE = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg";
    private static final String OLD = "avatars/12/0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d.jpg";
    private static final ProfileResponse PROFILE = new ProfileResponse("포메", null, null, "포", 3, "/" + MINE);
    private static final StoredObject JPEG = new StoredObject(1_048_576, "image/jpeg");

    @Test
    void 내_키를_등록하면_커밋_뒤에_이전_파일을_지운다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, OLD));

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);

        InOrder order = inOrder(userService, storage);
        order.verify(userService).replaceProfileImage(12L, MINE);
        order.verify(storage).delete(OLD);
    }

    @Test
    void 키_형식이_아니거나_남의_키면_S3를_보지_않고_400이다() {
        String[] bad = {
            null,
            "",
            "avatars/13/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "avatars/012/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "/avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.png",
            "avatars/12/3F2A9C1E-7B4D-4E8A-9C2F-1A2B3C4D5E6F.jpg",
            "avatars/12/x.jpg",
            "avatars/12/../13/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg"
        };
        for (String key : bad) {
            assertThatThrownBy(() -> service.updateProfileImage(12L, key))
                    .as("key=%s", key)
                    .isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(storage, userService);
    }

    @Test
    void S3에_파일이_없으면_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userService);
    }

    @Test
    void 정확히_1MB는_받고_1바이트라도_넘으면_그_파일을_지우고_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.of(new StoredObject(1_048_577, "image/jpeg")));

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verify(storage).delete(MINE);
        verifyNoInteractions(userService);
    }

    @Test
    void JPEG가_아니면_그_파일을_지우고_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.of(new StoredObject(1000, "image/png")));

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verify(storage).delete(MINE);
        verifyNoInteractions(userService);
    }

    @Test
    void 같은_키를_다시_보내면_아무것도_지우지_않는다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, MINE));

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);
        verify(storage, never()).delete(any());
    }

    @Test
    void 이전_파일_삭제가_실패해도_성공한다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, OLD));
        doThrow(new RuntimeException("S3 down")).when(storage).delete(OLD);

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);
    }

    @Test
    void 되돌리면_이전_파일을_지우고_사진이_없었으면_아무것도_지우지_않는다() {
        ProfileResponse none = new ProfileResponse("포메", null, null, "포", 3, null);
        when(userService.replaceProfileImage(12L, null))
                .thenReturn(new UserService.ProfileImageChange(none, OLD))
                .thenReturn(new UserService.ProfileImageChange(none, null));

        assertThat(service.deleteProfileImage(12L)).isEqualTo(none);
        verify(storage).delete(OLD);

        service.deleteProfileImage(12L);
        verify(storage).delete(any()); // 두 번째 호출은 지우지 않아 여전히 한 번
    }
```

`정확히_1MB` 쪽 통과 경계(1,048,576)는 `내_키를_등록하면…`의 `JPEG` 상수가 맡는다.

- [ ] **Step 2: 실패하는 단위 테스트 작성 — `UserServiceTest`**

필드 추가(`@InjectMocks` 생성자 주입이 이 스파이를 넘긴다). import: `org.mockito.Spy`, `project.study.user.image.ProfileImageUrls`.

```java
    @Spy
    private ProfileImageUrls profileImageUrls = new ProfileImageUrls("");
```

테스트 추가:

```java
    @Test
    void 사진_키를_바꾸면_이전_키와_새_사진_경로를_돌려준다() {
        User user = userWithId(1L, DEVICE_ID);
        user.replaceProfileImage("avatars/1/old.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserService.ProfileImageChange change = userService.replaceProfileImage(1L, "avatars/1/new.jpg");

        assertThat(change.previousKey()).isEqualTo("avatars/1/old.jpg");
        assertThat(change.profile().profileImageUrl()).isEqualTo("/avatars/1/new.jpg");
        assertThat(userService.replaceProfileImage(1L, null).profile().profileImageUrl()).isNull();
    }
```

- [ ] **Step 3: 실패하는 API 테스트 작성 — `ProfileImageApiTest`에 추가**

import 추가: `static org.mockito.ArgumentMatchers.any`, `static org.mockito.Mockito.never`, `java.util.Optional`, `project.study.user.image.ProfileImageStorage.StoredObject`.

```java
    private void jpegExists() {
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(1000, "image/jpeg")));
    }

    private MockMvcTester.MockMvcRequestBuilder putImage(long userId, String key) {
        return mvc.put()
                .uri("/api/users/me/profile-image")
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\": \"" + key + "\"}");
    }

    private String storedKey(long userId) {
        return jdbc.queryForObject("SELECT profile_image FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void 내_키를_등록하면_프로필에_사진_경로가_실린다() {
        long userId = user();
        String key = keyOf(userId);
        jpegExists();

        assertThat(putImage(userId, key))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isEqualTo("/" + key));
        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isEqualTo("/" + key));
        verify(storage, never()).delete(any());
    }

    @Test
    void 사진을_바꾸면_새_키를_저장하고_이전_파일을_지운다() {
        long userId = user();
        String first = keyOf(userId);
        String second = keyOf(userId);
        jpegExists();

        assertThat(putImage(userId, first)).hasStatusOk();
        assertThat(putImage(userId, second)).hasStatusOk();

        assertThat(storedKey(userId)).isEqualTo(second);
        verify(storage).delete(first);
    }

    @Test
    void 남의_키는_400_BAD_REQUEST이고_S3를_보지_않는다() {
        long userId = user();

        assertThat(putImage(userId, keyOf(userId + 1)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("BAD_REQUEST"));
        verify(storage, never()).head(any());
    }

    @Test
    void S3에_파일이_없으면_400이다() {
        long userId = user();
        when(storage.head(anyString())).thenReturn(Optional.empty());

        assertThat(putImage(userId, keyOf(userId))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 1MB를_넘는_파일은_지우고_400이다() {
        long userId = user();
        String key = keyOf(userId);
        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(1_048_577, "image/jpeg")));

        assertThat(putImage(userId, key)).hasStatus(HttpStatus.BAD_REQUEST);
        verify(storage).delete(key);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 되돌리면_사진_경로가_null이고_이전_파일을_지운다() {
        long userId = user();
        String key = keyOf(userId);
        jpegExists();
        assertThat(putImage(userId, key)).hasStatusOk();

        assertThat(mvc.delete().uri("/api/users/me/profile-image").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isNull());
        verify(storage).delete(key);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 사진이_없어도_되돌리기는_200이다() {
        long userId = user();

        assertThat(mvc.delete().uri("/api/users/me/profile-image").with(asUser(userId)))
                .hasStatusOk();
        verify(storage, never()).delete(any());
    }
```

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.user.*"`
Expected: FAIL — `ProfileImageUrls`, `UserService.ProfileImageChange`, `replaceProfileImage`, 6인자 `ProfileResponse`가 없어 컴파일 오류.

- [ ] **Step 5: `ProfileImageUrls` 작성**

`src/main/java/project/study/user/image/ProfileImageUrls.java`

```java
package project.study.user.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 저장된 사진 키를 응답 경로로 바꾼다. base-url이 비어 있으면 웹과 같은 출처의 상대 경로가 된다. */
@Component
public class ProfileImageUrls {

    private final String baseUrl;

    public ProfileImageUrls(@Value("${app.profile-image.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String of(String key) {
        return key == null ? null : baseUrl + "/" + key;
    }
}
```

- [ ] **Step 6: `ProfileResponse`·`User`·`UserService` 수정**

`ProfileResponse` — `colorIndex` 뒤에 추가:

```java
        @Schema(description = "아바타 색상 인덱스 (0~7) — 최초 배정 후 불변", example = "3")
        Integer colorIndex,

        @Schema(description = "프로필 사진 경로 — 없으면 null. 지금은 웹과 같은 출처의 상대 경로", example = "/avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String profileImageUrl) {}
```

`User` — `updateProfile` 뒤에 추가:

```java
    // 이전 키를 돌려준다 — 호출자가 커밋 뒤 그 파일을 지운다
    public String replaceProfileImage(String key) {
        String previous = this.profileImage;
        this.profileImage = key;
        return previous;
    }
```

`UserService` — 필드 추가(`import project.study.user.image.ProfileImageUrls;`):

```java
    private final UserRepository userRepository;
    private final ProfileImageUrls profileImageUrls;
```

`findUser` 앞에 추가:

```java
    /** 사진 키를 바꾼 뒤의 프로필과 바뀌기 전 키. */
    public record ProfileImageChange(ProfileResponse profile, String previousKey) {}

    @Transactional
    public ProfileImageChange replaceProfileImage(Long userId, String key) {
        User user = findUser(userId);
        String previous = user.replaceProfileImage(key);
        return new ProfileImageChange(toProfileResponse(user), previous);
    }
```

`toProfileResponse`를 인스턴스 메서드로 바꾼다:

```java
    private ProfileResponse toProfileResponse(User user) {
        return new ProfileResponse(
                user.getNickname(),
                user.getGoal(),
                user.getCategory(),
                user.getInitial(),
                user.getColorIndex(),
                profileImageUrls.of(user.getProfileImage()));
    }
```

- [ ] **Step 7: `ProfileImageService`에 등록·되돌리기 추가**

import 추가: `java.util.regex.Matcher`, `java.util.regex.Pattern`, `project.study.common.exception.BadRequestException`, `project.study.user.dto.ProfileImageUploadUrlRequest`, `project.study.user.dto.ProfileResponse`, `project.study.user.image.ProfileImageStorage.StoredObject`.

```java
    private static final Pattern KEY =
            Pattern.compile("avatars/(\\d+)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");

    public ProfileResponse updateProfileImage(Long userId, String key) {
        requireOwnKey(userId, key);
        StoredObject stored =
                storage.head(key).orElseThrow(() -> new BadRequestException("업로드된 사진이 없습니다"));
        if (stored.size() > ProfileImageUploadUrlRequest.MAX_BYTES
                || !ProfileImageStorage.CONTENT_TYPE.equals(stored.contentType())) {
            deleteQuietly(key);
            throw new BadRequestException("사진은 1MB 이하 JPEG여야 합니다");
        }
        return replace(userId, key);
    }

    public ProfileResponse deleteProfileImage(Long userId) {
        return replace(userId, null);
    }

    private ProfileResponse replace(Long userId, String key) {
        UserService.ProfileImageChange change = userService.replaceProfileImage(userId, key);
        String previous = change.previousKey();
        if (previous != null && !previous.equals(key)) {
            deleteQuietly(previous);
        }
        return change.profile();
    }

    private static void requireOwnKey(Long userId, String key) {
        Matcher matcher = key == null ? null : KEY.matcher(key);
        if (matcher == null || !matcher.matches() || !matcher.group(1).equals(String.valueOf(userId))) {
            throw new BadRequestException("사진 키가 올바르지 않습니다");
        }
    }

    // 남은 파일은 어디서도 참조하지 않으므로 지우지 못해도 요청은 성공으로 둔다
    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("프로필 사진 삭제 실패: key={}", key, e);
        }
    }
```

- [ ] **Step 8: 컨트롤러에 `PUT`·`DELETE` 추가**

`src/main/java/project/study/user/dto/ProfileImageUpdateRequest.java`

```java
package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileImageUpdateRequest(
        @Schema(description = "업로드 URL 발급 때 받은 키", example = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String key) {}
```

`UserController` import 추가: `org.springframework.web.bind.annotation.DeleteMapping`, `org.springframework.web.bind.annotation.PutMapping`, `project.study.user.dto.ProfileImageUpdateRequest`.

```java
    @Operation(summary = "프로필 사진 등록", description = """
                    업로드를 마친 키를 프로필 사진으로 등록하고 바뀐 프로필을 준다. 서버가 S3에서 파일의 존재·크기(1MB 이하)·형식\
                    (`image/jpeg`)을 확인한다. 이전 사진 파일은 지운다. 같은 키를 다시 보내면 아무것도 지우지 않고 200이다.""")
    @ApiResponse(responseCode = "200", description = "등록 성공 — 바뀐 전체 프로필 반환")
    @ApiResponse(
            responseCode = "400",
            description = "BAD_REQUEST — 내 키 형식이 아님, S3에 파일 없음, 1MB 초과 또는 JPEG가 아님(이때 파일은 지워진다)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping(value = "/me/profile-image", version = "2")
    public ProfileResponse updateProfileImage(
            @AuthenticationPrincipal Long userId, @RequestBody ProfileImageUpdateRequest request) {
        return profileImageService.updateProfileImage(userId, request.key());
    }

    @Operation(summary = "프로필 사진 되돌리기", description = "사진을 지우고 이니셜 아바타로 되돌린다. 사진이 없어도 200이다.")
    @ApiResponse(responseCode = "200", description = "성공 — 바뀐 전체 프로필 반환(profileImageUrl은 null)")
    @DeleteMapping(value = "/me/profile-image", version = "2")
    public ProfileResponse deleteProfileImage(@AuthenticationPrincipal Long userId) {
        return profileImageService.deleteProfileImage(userId);
    }
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.user.*" --tests "project.study.room.*"`
Expected: PASS. 룸 패키지는 `RoomController.join`이 `ProfileResponse`를 쓰므로 함께 돌린다.

- [ ] **Step 10: 변경 파일 목록을 보고서에 적는다**

`ProfileImageUrls.java`, `ProfileImageUpdateRequest.java`, `ProfileResponse.java`, `User.java`, `UserService.java`, `ProfileImageService.java`, `UserController.java`, `ProfileImageServiceTest.java`, `UserServiceTest.java`, `ProfileImageApiTest.java`.

---

### Task 4: 소셜룸 멤버 사진 경로 (V30·스냅샷·`RoomMember`·`websocket.html`)

**Files:**
- Create: `src/main/resources/db/migration/V30__room_participation_profile_image.sql`
- Modify: `src/main/java/project/study/room/dto/RoomMember.java`
- Modify: `src/main/java/project/study/room/repository/RoomParticipationRepository.java` (:64-66 `MEMBER_COLUMNS`, :88-96 `MEMBER`, :137-172 입장 SQL 세 곳, :254·:268 `.query(MEMBER)`, :104 필드)
- Modify: `src/main/java/project/study/room/websocket/StompEventListener.java:98`
- Modify: `src/main/resources/docs/websocket.html` (§5 예시 :155-162, 멤버 객체 표 :170-178, 변경 이력 :284-290)
- Test: `src/test/java/project/study/room/RoomParticipationRepositoryTest.java`, `src/test/java/project/study/room/RoomStompHandlerTest.java:52-53,69`, `src/test/java/project/study/room/StompEventListenerTest.java:65`

**Interfaces:**
- Consumes: `ProfileImageUrls.of(String key)` (Task 3)
- Produces: `RoomMember(Long userId, String nickname, String goal, String category, String profileImageUrl, boolean cameraOn, String focusState, int focusSec, boolean disconnected)`

- [ ] **Step 1: 실패하는 테스트 작성**

`RoomParticipationRepositoryTest`에 추가(import `java.sql.Types`).

```java
    private void setProfileImage(long user, String key) {
        jdbc.sql("UPDATE users SET profile_image = :key WHERE id = :id")
                .param("key", key, Types.VARCHAR)
                .param("id", user)
                .update();
    }

    @Test
    void 입장_경로마다_유저의_사진_키를_복사하고_멤버에는_사진_경로로_싣는다() {
        setProfileImage(userId, "avatars/1/a.jpg");
        long id = reserve(userId, NOW);
        participations.confirm(roomId, userId, "s1", NOW, "task-A", NOW);
        assertThat(participations.findConfirmedMembers(roomId).getFirst().profileImageUrl())
                .isEqualTo("/avatars/1/a.jpg");

        setProfileImage(userId, "avatars/1/b.jpg");
        assertThat(participations.refreshReservation(id, PROFILE, NOW)).isEqualTo(1);
        assertThat(participations.findConfirmedMembers(roomId).getFirst().profileImageUrl())
                .isEqualTo("/avatars/1/b.jpg");

        participations.markDisconnected("s1", NOW);
        setProfileImage(userId, null);
        assertThat(participations.restoreFromGrace(id, PROFILE, NOW)).isEqualTo(1);
        participations.confirm(roomId, userId, "s2", NOW.plusSeconds(1), "task-A", NOW);
        assertThat(participations.findConfirmedMembers(roomId).getFirst().profileImageUrl())
                .isNull();
    }
```

기존 생성자 호출을 9인자로 고친다(`category` 뒤에 사진 경로).

- `RoomStompHandlerTest:52` → `new RoomMember(1L, "닉네임", "목표", "수능", "/avatars/1/a.jpg", true, "FOCUS", 120, false),`
- `RoomStompHandlerTest:53` → `new RoomMember(2L, "친구", "목표2", "수능", null, false, "DISTRACTED", 45, false));`
- `RoomStompHandlerTest:69` → `.thenReturn(List.of(new RoomMember(1L, "닉네임", null, null, null, false, "FOCUS", 0, false)));`
- `StompEventListenerTest:65` → `private static final RoomMember ME = new RoomMember(7L, "포메", null, null, null, false, "FOCUS", 0, false);`

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.room.*"`
Expected: FAIL — `RoomMember`에 9인자 생성자와 `profileImageUrl()`이 없어 컴파일 오류.

- [ ] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V30__room_participation_profile_image.sql`

```sql
-- 방 안 프로필은 입장 시점 값이다 — 닉네임처럼 사진 키도 입장 때 users.profile_image에서 복사한다
ALTER TABLE room_participations ADD COLUMN profile_image varchar;
```

- [ ] **Step 4: `RoomMember`·리포지토리·리스너 수정**

`RoomMember`:

```java
public record RoomMember(
        Long userId,
        String nickname,
        String goal,
        String category,
        String profileImageUrl,
        boolean cameraOn,
        String focusState,
        int focusSec,
        boolean disconnected) {}
```

`RoomParticipationRepository` — import `java.sql.ResultSet`, `java.sql.SQLException`, `project.study.user.image.ProfileImageUrls`.

```java
    private static final String MEMBER_COLUMNS =
            "user_id, nickname, goal, category, profile_image, camera_on, focus_state, focus_sec, "
                    + "(disconnected_at IS NOT NULL) AS disconnected";

    // 사진 키는 프로필 응답에 경로로만 실리므로 입장하는 같은 문장에서 users에서 복사한다
    private static final String CURRENT_PROFILE_IMAGE =
            "(SELECT u.profile_image FROM users u WHERE u.id = room_participations.user_id)";
```

정적 `MEMBER` 매퍼를 지우고 인스턴스 메서드로 바꾼다(경로 변환에 주입받은 빈이 필요하다).

```java
    private final JdbcClient jdbc;
    private final ProfileImageUrls profileImageUrls;

    private RoomMember toMember(ResultSet rs, int rowNum) throws SQLException {
        return new RoomMember(
                rs.getLong("user_id"),
                rs.getString("nickname"),
                rs.getString("goal"),
                rs.getString("category"),
                profileImageUrls.of(rs.getString("profile_image")),
                rs.getBoolean("camera_on"),
                rs.getString("focus_state"),
                rs.getInt("focus_sec"),
                rs.getBoolean("disconnected"));
    }
```

`findConfirmedMembers`·`findConfirmedMembersForActiveSession`의 `.query(MEMBER)`를 `.query(this::toMember)`로 바꾼다.

입장 SQL 세 곳:

```java
    public long insertReservation(Long roomId, Long userId, Profile profile, Instant now) {
        return jdbc.sql("INSERT INTO room_participations "
                        + "(room_id, user_id, nickname, goal, category, profile_image, reserved_at) "
                        + "VALUES (:roomId, :userId, :nickname, :goal, :category, "
                        + "(SELECT u.profile_image FROM users u WHERE u.id = :userId), :now) RETURNING id")
```
(나머지 `.param`·`.query`는 그대로)

```java
                        jdbc.sql("UPDATE room_participations SET disconnected_at = NULL, stomp_confirmed = false, "
                                + "stomp_session_id = NULL, task_id = NULL, reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category, profile_image = " + CURRENT_PROFILE_IMAGE + " "
                                + "WHERE id = :id AND left_at IS NULL AND disconnected_at IS NOT NULL"),
```

```java
                        jdbc.sql("UPDATE room_participations SET reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category, profile_image = " + CURRENT_PROFILE_IMAGE + " "
                                + "WHERE id = :id AND left_at IS NULL"),
```

`StompEventListener:98`:

```java
                .orElse(new RoomMember(userId, null, null, null, null, false, "FOCUS", 0, false));
```

- [ ] **Step 5: `websocket.html` 갱신**

§5 예시의 멤버 객체 세 곳에 `"profileImageUrl"`을 넣는다.

```html
<pre><code>{ "type": "SNAPSHOT", "members": [
  { "userId": 12, "nickname": "상재", "goal": "정보처리기사", "category": "자격증",
    "profileImageUrl": "/avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
    "cameraOn": true, "focusState": "FOCUS", "focusSec": 1520, "disconnected": false },
  { "userId": 34, "nickname": "원일", "goal": "졸업 프로젝트", "category": "학업", "profileImageUrl": null,
    "cameraOn": false, "focusState": "DISTRACTED", "focusSec": 300, "disconnected": true }
] }
{ "type": "MEMBER_JOINED", "member": { "userId": 56, "nickname": "선규", "goal": null, "category": null,
    "profileImageUrl": null, "cameraOn": false, "focusState": "FOCUS", "focusSec": 0, "disconnected": false } }
```
(그 아래 `MEMBER_LEFT`부터는 그대로)

멤버 객체 표의 `goal`, `category` 줄 바로 아래에 추가:

```html
  <tr><td><code>profileImageUrl</code></td><td>string | null</td><td>입장 시점 프로필 사진 경로(지금은 웹과 같은 출처의 상대 경로 <code>/avatars/...</code>). 없으면 null — 이니셜로 표시. 방에 있는 동안 사진을 바꾸면 다음 입장부터 반영된다. 바꾸면 이전 파일을 바로 지우므로 엣지 캐시가 끝난 뒤(최대 1시간) 다음 입장 전까지는 이미지를 못 불러온다 — 로드 실패도 이니셜로 표시한다</td></tr>
```

변경 이력 표 마지막 줄 뒤에 추가:

```html
  <tr><td>2026-10-28</td><td>BY-1035</td><td>멤버 객체에 <code>profileImageUrl</code>(string | null) 추가(§5). 입장 시점 값이라 방 안에서 사진을 바꿔도 다음 입장부터 반영</td></tr>
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.room.*"`
Expected: PASS(`WebSocketDocsContractTest.RoomMember_필드가_모두_문서에_있다` 포함 — 문서에 `"profileImageUrl"`이 있어야 통과한다).

- [ ] **Step 7: 변경 파일 목록을 보고서에 적는다**

`V30__room_participation_profile_image.sql`, `RoomMember.java`, `RoomParticipationRepository.java`, `StompEventListener.java`, `websocket.html`, `RoomParticipationRepositoryTest.java`, `RoomStompHandlerTest.java`, `StompEventListenerTest.java`.

---

### Task 5: 랭킹 응답 사진 경로

**Files:**
- Create: `src/main/java/project/study/studysession/dto/RankingProfile.java`
- Modify: `src/main/java/project/study/studysession/dto/RankingTotalRow.java`, `RankingDaysRow.java`, `RankingStreakRow.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java` (:38-43, :55·:65, :80·:92, :112·:117, :122-123, :155, :164-170, :174-188)
- Modify: `src/main/java/project/study/studysession/service/RankingSource.java:55-57`
- Modify: `src/main/java/project/study/ranking/engine/RankingEntry.java`
- Modify: `src/main/java/project/study/ranking/engine/StandingsCalculator.java` (:86, :90, :94, :134, :140, :144, :196-213, :222-252)
- Modify: `src/main/java/project/study/ranking/dto/RankingBoardResponse.java:65,69,93-100`
- Modify: `src/main/java/project/study/ranking/service/RankingBoardService.java` (:31-33 필드, :88-129)
- Modify: `src/main/java/project/study/ranking/service/BoardExtras.java` (:40-43 필드, :144, :171-192)
- Test: `src/test/java/project/study/studysession/StudySessionRankingQueriesTest.java`, `src/test/java/project/study/ranking/engine/StandingsCalculatorTest.java`, `src/test/java/project/study/ranking/service/RankingBoardServiceTest.java`, `src/test/java/project/study/ranking/service/RankingBoardExtrasTest.java`, `src/test/java/project/study/ranking/engine/RankingEntryTest.java:13,31`, `src/test/java/project/study/ranking/engine/StandingsTest.java:58,69,82`

**Interfaces:**
- Consumes: `ProfileImageUrls.of(String key)` (Task 3)
- Produces:
  - `RankingTotalRow(long userId, String nickname, String profileImage, long focusSec, long studySec, Instant achievedAt)`
  - `RankingDaysRow(long userId, String nickname, String profileImage, int days, Instant achievedAt)`
  - `RankingStreakRow(long userId, String nickname, String profileImage, int days, LocalDate startDate, LocalDate endDate, Instant achievedAt)`
  - `RankingProfile(String nickname, String profileImage)`; `RankingSource.activeProfiles(Collection<Long>)` / `StudySessionRankingQueries.activeProfiles(Collection<Long>)` → `Map<Long, RankingProfile>` (`activeNicknames` 대체)
  - `RankingEntry(long userId, String nickname, String profileImage, double value, Instant achievedAt, boolean focusing, long focusSec, long studySec)`, `RankingEntry.of(long, String, String, double, Instant)` 추가, 기존 `of(long, String, double, Instant)`은 사진 null로 유지
  - `BoardEntry(int rank, String nickname, String profileImageUrl, Number value, boolean focusing, boolean me)`
  - `BoardNeighbor(String nickname, String profileImageUrl, Number gap, boolean focusing, Long catchUpFocusSec)`
  - `DayGroupRow(int days, int rank, String nickname, String profileImageUrl, int othersCount, LocalDate achievedDate, boolean me, Integer myOrder)`

- [ ] **Step 1: 실패하는 테스트 작성**

`StudySessionRankingQueriesTest` — 기존 두 테스트의 `source.activeNicknames(`를 `source.activeProfiles(`로 바꾸고(:165, :176), import `project.study.studysession.dto.RankingProfile`을 더한 뒤 추가:

```java
    @Test
    void 집계_줄과_진행_중_프로필에_사진_키가_실린다() {
        long a = user();
        String key = "avatars/" + a + "/p.jpg";
        jdbc.update("UPDATE users SET profile_image = ? WHERE id = ?", key, a);
        session(a, FROM.plusDays(1), 9, 60, 3000);

        assertThat(source.periodTotals(FROM, TO, a)).extracting(RankingTotalRow::profileImage).containsExactly(key);
        assertThat(source.studyDays(TODAY, a)).extracting(RankingDaysRow::profileImage).containsExactly(key);
        assertThat(source.maxStreaks(TODAY, a)).extracting(RankingStreakRow::profileImage).containsExactly(key);
        assertThat(source.activeProfiles(List.of(a))).containsEntry(a, new RankingProfile(nickname(a), key));
    }
```

`StandingsCalculatorTest`에 추가:

```java
    @Test
    void 진행_중_조각만_있는_사람도_사진_키를_가진다() {
        long live = user("live");
        jdbc.update("UPDATE users SET profile_image = ? WHERE id = ?", "avatars/" + live + "/l.jpg", live);
        draft(live, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), null))
                .extracting(RankingEntry::nickname, RankingEntry::profileImage)
                .containsExactly(tuple("live", "avatars/" + live + "/l.jpg"));
    }
```

`RankingBoardServiceTest` — 기존 :56·:57·:73의 `new BoardNeighbor("u3", 600L, false, null)` 꼴을 `new BoardNeighbor("u3", null, 600L, false, null)` 꼴로(닉네임 뒤에 `null`) 고치고 추가:

```java
    @Test
    void 시상대와_앞뒤_줄에_사진_경로가_실린다() {
        long u1 = weekly("u1", 70);
        long u2 = weekly("u2", 60);
        weekly("u3", 50);
        jdbc.update("UPDATE users SET profile_image = ? WHERE id = ?", "avatars/" + u1 + "/a.jpg", u1);

        RankingBoardResponse r = weeklyBoard(u2);

        assertThat(r.podium())
                .extracting(BoardEntry::profileImageUrl)
                .containsExactly("/avatars/" + u1 + "/a.jpg", null, null);
        assertThat(r.above().profileImageUrl()).isEqualTo("/avatars/" + u1 + "/a.jpg");
        assertThat(r.below().profileImageUrl()).isNull();
    }
```

`RankingBoardExtrasTest` — `new DayGroupRow(` 9곳(:137-139, :184-187, :208-209)의 닉네임 뒤에 `null`을 넣고(예: `new DayGroupRow(5, 1, "a", null, 1, LocalDate.of(2026, 9, 5), false, null)`), import `static org.assertj.core.api.Assertions.tuple`을 더한 뒤 추가:

```java
    @Test
    void 일수_판_묶음_대표에_사진_경로가_실린다() {
        long a = user("a");
        streak(a, LocalDate.of(2026, 9, 1), 3);
        jdbc.update("UPDATE users SET profile_image = ? WHERE id = ?", "avatars/" + a + "/a.jpg", a);
        long me = user("me");
        streak(me, LocalDate.of(2026, 9, 10), 1);

        RankingBoardResponse r = service.board(me, TOTAL_DAYS, null, null, 0);

        assertThat(r.podiumGroups())
                .extracting(DayGroupRow::nickname, DayGroupRow::profileImageUrl)
                .containsExactly(tuple("a", "/avatars/" + a + "/a.jpg"), tuple("me", null));
    }
```

엔진 단위 테스트의 정식 생성자 호출을 8인자로 고친다(닉네임 뒤 `null`).

- `RankingEntryTest:13` → `return new RankingEntry(1, "u1", null, value, T0, true, 600, 700);`
- `RankingEntryTest:31` → `assertThat(settled).isEqualTo(new RankingEntry(1, "u1", null, 100, T0, false, 600, 700));`
- `StandingsTest:58`, `:69`, `:82` → `new RankingEntry(1, "u1", null, 100, T0, true, 0, 0)`

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.*" --tests "project.study.studysession.StudySessionRankingQueriesTest"`
Expected: FAIL — `profileImage()`·`profileImageUrl()`·`activeProfiles`·`RankingProfile`이 없어 컴파일 오류.

- [ ] **Step 3: 행 DTO와 쿼리 수정**

`RankingTotalRow`:

```java
public record RankingTotalRow(
        long userId, String nickname, String profileImage, long focusSec, long studySec, Instant achievedAt) {}
```

`RankingDaysRow`:

```java
public record RankingDaysRow(long userId, String nickname, String profileImage, int days, Instant achievedAt) {}
```

`RankingStreakRow`:

```java
public record RankingStreakRow(
        long userId,
        String nickname,
        String profileImage,
        int days,
        LocalDate startDate,
        LocalDate endDate,
        Instant achievedAt) {}
```

`src/main/java/project/study/studysession/dto/RankingProfile.java`:

```java
package project.study.studysession.dto;

/** 진행 중 조각만 있는 사용자의 표시 정보 — 확정 집계 줄이 없어 따로 읽는다. */
public record RankingProfile(String nickname, String profileImage) {}
```

`StudySessionRankingQueries`:

- `TOTAL_ROW` — `rs.getString("nickname"),` 뒤에 `rs.getString("profile_image"),`
- `periodTotals` — `SELECT s.user_id, u.nickname, u.profile_image,` / `GROUP BY s.user_id, u.nickname, u.profile_image"""`
- `slotTotals` — 위와 같은 두 줄
- `studyDays` — `SELECT d.user_id, u.nickname, u.profile_image, COUNT(*) AS days,` / `GROUP BY d.user_id, u.nickname, u.profile_image"""`, 매퍼:

```java
                .query((rs, i) -> new RankingDaysRow(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("profile_image"),
                        rs.getInt("days"),
                        instant(rs, "achieved_at")))
```

- `maxStreaks` — `SELECT b.user_id, u.nickname, u.profile_image, b.len AS days, b.start_date, b.end_date, r.first_done AS achieved_at`, 매퍼 `rs.getString("nickname"),` 뒤에 `rs.getString("profile_image"),`
- `activeNicknames`를 아래로 바꾼다(import `project.study.studysession.dto.RankingProfile`):

```java
    /** 진행 중 조각만 있는 사용자의 닉네임·사진 키 — 탈퇴자와 닉네임을 아직 안 정한 사용자는 빠진다. */
    public Map<Long, RankingProfile> activeProfiles(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT u.id, u.nickname, u.profile_image FROM users u WHERE u.id IN (:ids) "
                + "AND u.nickname IS NOT NULL AND " + NOT_WITHDRAWN;
        return jdbc
                .sql(sql)
                .paramSource(new MapSqlParameterSource("ids", userIds))
                .query((rs, i) -> Map.entry(
                        rs.getLong("id"), new RankingProfile(rs.getString("nickname"), rs.getString("profile_image"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
```

`RankingSource`:

```java
    public Map<Long, RankingProfile> activeProfiles(Collection<Long> userIds) {
        return queries.activeProfiles(userIds);
    }
```

- [ ] **Step 4: 엔진 수정**

`RankingEntry` — 문서 주석 끝 문장을 `nickname·profileImage(사진 키)는 가정한 줄(예상 순위 계산)이면 null일 수 있다.`로 바꾸고:

```java
public record RankingEntry(
        long userId,
        String nickname,
        String profileImage,
        double value,
        Instant achievedAt,
        boolean focusing,
        long focusSec,
        long studySec) {
```

```java
    public static RankingEntry of(long userId, String nickname, double value, Instant achievedAt) {
        return of(userId, nickname, null, value, achievedAt);
    }

    public static RankingEntry of(long userId, String nickname, String profileImage, double value, Instant achievedAt) {
        return new RankingEntry(userId, nickname, profileImage, value, achievedAt, false, 0, 0);
    }
```

`advancedTo`·`settledAt`의 `new RankingEntry(userId, nickname, …)`를 `new RankingEntry(userId, nickname, profileImage, …)`로.

`StandingsCalculator` (import `project.study.studysession.dto.RankingProfile`):

- :86 → `.map(r -> RankingEntry.of(r.userId(), r.nickname(), r.profileImage(), r.focusSec(), r.achievedAt()))`
- :90, :94 → `.map(r -> RankingEntry.of(r.userId(), r.nickname(), r.profileImage(), r.days(), r.achievedAt()))`
- :134 → `totals.put(row.userId(), new Totals(row.nickname(), row.profileImage(), row.focusSec(), row.studySec(), row.achievedAt()));`
- :140 → `totals.computeIfAbsent(piece.userId(), id -> new Totals(null, null, 0, 0, Instant.EPOCH))`
- :144 → `fillLiveOnlyProfiles(totals);`
- :196-214를 바꾼다:

```java
    /** 진행 중 조각만 있는 사용자는 확정 집계 줄이 없어 닉네임·사진 키를 따로 읽는다 — 탈퇴자는 여기서 빠진다. */
    private void fillLiveOnlyProfiles(Map<Long, Totals> totals) {
        List<Long> missing = totals.entrySet().stream()
                .filter(e -> e.getValue().nickname == null)
                .map(Map.Entry::getKey)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, RankingProfile> profiles = source.activeProfiles(missing);
        for (Long userId : missing) {
            RankingProfile profile = profiles.get(userId);
            if (profile == null) {
                totals.remove(userId);
            } else {
                totals.get(userId).nickname = profile.nickname();
                totals.get(userId).profileImage = profile.profileImage();
            }
        }
    }
```

- `Totals` — 필드 `private String profileImage;`를 `nickname` 아래에 더하고, 생성자를 `Totals(String nickname, String profileImage, long focusSec, long studySec, Instant achievedAt)`로(본문에 `this.profileImage = profileImage;`), `toEntry`를 `new RankingEntry(userId, nickname, profileImage, value, achievedAt, focusing, focusSec, studySec)`로.

`BoardExtras:144` → `new RankingEntry(userId, null, null, totals.rate(), now, false, totals.focusSec(), totals.studySec()));`

- [ ] **Step 5: 응답 수정**

`RankingBoardResponse`:

```java
    public record BoardEntry(
            int rank, String nickname, String profileImageUrl, Number value, boolean focusing, boolean me) {}
```

```java
    public record BoardNeighbor(
            String nickname, String profileImageUrl, Number gap, boolean focusing, Long catchUpFocusSec) {}
```

```java
    public record DayGroupRow(
            int days,
            int rank,
            String nickname,
            String profileImageUrl,
            int othersCount,
            LocalDate achievedDate,
            boolean me,
            Integer myOrder) {}
```

`DayGroupRow` 문서 주석의 "nickname은 대표(…)" 뒤에 `profileImageUrl은 그 대표의 사진 경로,`를 넣는다.

`RankingBoardService` — import `project.study.user.image.ProfileImageUrls`, 필드 `private final ProfileImageUrls profileImageUrls;`. `around`·`entries`·`neighbor`에서 `static`을 빼고(`myRank`·`startNowRank`·`catchUpFocusSec`는 그대로 static):

```java
    private List<BoardEntry> entries(List<RankedEntry> ranked, long userId, RankingBoardType type) {
        return ranked.stream()
                .map(row -> new BoardEntry(
                        row.rank(),
                        row.entry().nickname(),
                        profileImageUrls.of(row.entry().profileImage()),
                        BoardValues.value(type, row.entry().value()),
                        row.entry().focusing(),
                        row.entry().userId() == userId))
                .toList();
    }

    private BoardNeighbor neighbor(RankingEntry other, RankingEntry me, RankingBoardType type, boolean above) {
        if (other == null || me == null) {
            return null;
        }
        Long catchUp = above && type == RankingBoardType.FOCUS_RATE ? catchUpFocusSec(other, me) : null;
        return new BoardNeighbor(
                other.nickname(),
                profileImageUrls.of(other.profileImage()),
                BoardValues.gap(type, other.value(), me.value()),
                other.focusing(),
                catchUp);
    }
```

`BoardExtras` — import `project.study.user.image.ProfileImageUrls`, 필드 `private final ProfileImageUrls profileImageUrls;`. `toRow`에서 `static`을 빼고 두 `new DayGroupRow(` 호출에 닉네임 뒤로 `profileImageUrls.of(head.profileImage()),` / `profileImageUrls.of(me.profileImage()),`를 넣는다.

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*" --tests "project.study.studysession.*"`
Expected: PASS. 지난 기간 판(`RankingClosedBoardTest`)도 같은 계산 경로라 함께 통과해야 한다.

- [ ] **Step 7: 변경 파일 목록을 보고서에 적는다**

`RankingProfile.java`, `RankingTotalRow.java`, `RankingDaysRow.java`, `RankingStreakRow.java`, `StudySessionRankingQueries.java`, `RankingSource.java`, `RankingEntry.java`, `StandingsCalculator.java`, `RankingBoardResponse.java`, `RankingBoardService.java`, `BoardExtras.java`, 테스트 6개(`StudySessionRankingQueriesTest`, `StandingsCalculatorTest`, `RankingBoardServiceTest`, `RankingBoardExtrasTest`, `RankingEntryTest`, `StandingsTest`).

---

### Task 6: ADR-0033, 운영자 삭제 런북, 환경별 설정, 전체 검증

**Files:**
- Create: `docs/adr/0033-profile-image-presigned-upload.md`
- Create: `docs/runbooks/profile-image-removal.md`
- Modify: `src/main/resources/application-prod.yaml` (`app:` 블록, `room:` 앞)
- Modify: `src/main/resources/application-dev.yaml` (`app:` 블록, `room:` 앞)
- Modify: `src/main/resources/application-local.yaml.example` (`app:` 블록, `room:` 앞)
- Modify: `src/test/resources/application.yaml` (`app:` 블록 끝)

**Interfaces:**
- Consumes: Task 1~5 전부. 설정 키 `app.profile-image.bucket`·`region`·`base-url`(Task 1·3의 `@Value`)
- Produces: 없음

- [ ] **Step 1: 설정 추가**

모든 파일에서 **기존 `app:` 블록 안에** 넣는다. 최상위 `app:`를 새로 만들면 yaml 중복 키로 기동이 깨진다.

`application-prod.yaml` (`app:` 아래 `room:` 앞):

```yaml
  # 프로필 사진 버킷. ECS 태스크 정의가 환경 변수로 넣는다(infra 저장소). 버킷에는 기본값을 두지 않는다 —
  # @Value로 읽어 누락 시 기동이 실패한다. 자격 증명은 태스크 역할(s3:PutObject·GetObject·DeleteObject)
  profile-image:
    bucket: ${APP_PROFILE_IMAGE_BUCKET}
    region: ap-northeast-2
    # 비우면 웹과 같은 출처의 /avatars/... 상대 경로로 응답한다(CloudFront /avatars/*)
    base-url: ${APP_PROFILE_IMAGE_BASE_URL:}
```

`application-dev.yaml` (`app:` 아래 `room:` 앞):

```yaml
  # 프로필 사진 버킷(스테이징). 자격 증명은 EC2 인스턴스 역할 — s3:GetObject까지 있어야 등록 확인이 된다
  profile-image:
    bucket: ${APP_PROFILE_IMAGE_BUCKET:focus-makers-staging-avatars-821365066487}
    region: ap-northeast-2
    base-url: ${APP_PROFILE_IMAGE_BASE_URL:}
```

`application-local.yaml.example` (`app:` 아래 `room:` 앞):

```yaml
  # 프로필 사진. 자격 증명은 AWS_PROFILE로 읽는다. 실제 업로드를 시험할 때만 스테이징 버킷 이름을 넣는다
  profile-image:
    bucket: local-avatars
    region: ap-northeast-2
    base-url: ""
```

`src/test/resources/application.yaml` (`app:` 블록의 `ranking:` 묶음 뒤, 같은 들여쓰기):

```yaml
  # 테스트는 S3를 부르지 않는다 — 서명 테스트는 고정 자격 증명, API 테스트는 ProfileImageStorage 목
  profile-image:
    bucket: test-avatars
    region: ap-northeast-2
    base-url: ""
```

- [ ] **Step 2: ADR-0033 작성**

`docs/adr/0033-profile-image-presigned-upload.md`. 결정 3은 Task 1 Step 6 결과에 맞춘다. A면 `(B: …)` 줄만 지운다. B면 결정 3의 첫 두 문장을 `(B: …)` 줄의 괄호 안 문장으로 바꾸고 그 줄을 지운다.

```markdown
# ADR-0033: 프로필 사진은 presigned PUT으로 S3에 바로 올리고, 웹과 같은 출처의 `/avatars/*` 경로로 서빙한다

- 상태: 승인
- 날짜: 2026-10-28
- 티켓: BY-1035 (스토리 BY-1031)
- 명세: `docs/superpowers/specs/2026-10-28-BY-1035-profile-image-design.md`
- 관련: BY-1034(사진 버킷·CloudFront `/avatars/*`), BY-1032(위키 정책), ADR-0015(API 버전), ADR-0018(룸 상태는 Postgres), ADR-0028(랭킹은 조회 때 집계)

## 맥락

프로필 아바타는 이니셜과 색상뿐이었다. 사용자가 사진을 올리고 바꾸고 되돌릴 수 있게 하고, 프로필·소셜룸·랭킹에 사진을 보여 준다.
웹이 512px JPEG로 줄여 올리므로 파일은 1MB를 넘지 않는다(BY-1037). 사진 버킷과 CloudFront 경로는 BY-1034가 만들었다.

## 결정

1. **서버는 바이트를 받지 않는다.** `POST /api/users/me/profile-image/upload-url`이 5분짜리 presigned PUT URL과 서버가 만든 키
   `avatars/{userId}/{UUID}.jpg`를 주고, 브라우저가 S3에 바로 올린다. API 태스크가 이미지 본문을 메모리에 올리거나 대역폭을 쓰지 않는다.
   키를 서버가 만들어 사용자는 자기 폴더 밖에 쓸 수 없다.
2. **등록은 사진 전용 경로로 나눈다.** `PUT /api/users/me/profile-image {key}`로 등록하고 `DELETE`로 되돌린다. 프로필 `PATCH`에 섞으면
   S3 확인과 파일 삭제가 닉네임 수정과 한 요청에 엉킨다.
3. **크기는 두 번 확인한다.** 서명에 `Content-Type: image/jpeg`와 요청한 `Content-Length`를 넣어 다른 크기·형식의 본문은 S3가 거부하고,
   등록 때 `HeadObject`로 실제 파일의 존재·크기(1MB 이하)·형식을 다시 본다. 서명은 발급 때 받은 숫자만 묶고, DB가 참조하기 전에 실제
   객체를 보는 것은 `HeadObject`뿐이다. 기준을 넘으면 그 파일을 지우고 400이다.
   (B: SDK v2는 presigned PUT에 `Content-Length`를 서명하지 않는다. 서명은 형식만 묶고, 크기는 등록 때 `HeadObject`가 막는다.)
   업로드 주체에 `s3:GetObject`가 필요하다.
4. **웹과 같은 출처의 경로로 서빙한다.** 응답은 `base-url + "/" + key`이고 `base-url` 기본값은 빈 문자열이라 `/avatars/...` 상대 경로다.
   웹 응답이 COEP `require-corp`라 다른 출처 이미지는 CORP 헤더가 없으면 브라우저가 막는다. CloudFront가 웹과 같은 도메인에서
   `/avatars/*`를 버킷으로 보내므로 같은 출처가 되어 헤더가 필요 없다. PC용 웹처럼 다른 도메인에서 사진을 써야 하면
   `app.profile-image.base-url`로 절대 URL로 바꾸고, 그때 `/avatars/*` 응답에 CORP 헤더를 함께 넣는다.
5. **이전 파일은 DB 커밋 뒤 바로 지운다.** 삭제가 실패하면 경고 로그만 남기고 요청은 성공이다. 남은 파일은 어디서도 참조하지 않는다.
   같은 키를 다시 보내면 아무것도 지우지 않는다.
6. **운영자 삭제는 관리 API 없이 런북으로 한다**(`docs/runbooks/profile-image-removal.md`). 관리자 인증 체계가 없고, 새 엔드포인트는
   공격 지점을 늘린다. 신고가 잦아지면 BY-1039에서 다시 본다.
7. **소셜룸은 입장 시점 사진, 랭킹은 조회 때 사진을 싣는다.** 룸은 닉네임처럼 입장 때 사진 키를 `room_participations.profile_image`(V30)에
   복사한다. 랭킹은 닉네임 조인 쿼리에서 `users.profile_image`를 같이 읽는다.

## 결과

- `ProfileResponse`·`RoomMember`·랭킹 `BoardEntry`·`BoardNeighbor`·`DayGroupRow`에 nullable `profileImageUrl`이 실린다. 필드 추가라 기존 클라이언트는 영향이 없다.
- 방에 있는 동안 사진을 바꾸면 다음 입장부터 반영된다. 이전 파일을 바로 지우므로 이미 방에 있는 사람들 화면에서는 엣지 캐시가 끝난 뒤(최대 1시간) 다음 입장 전까지 이니셜로 보인다.
- 랭킹 캐시 때문에 사진 변경은 최대 60초 늦게 반영된다.
- 운영 배포 전에 infra 저장소에서 ECS 태스크 정의에 `APP_PROFILE_IMAGE_BUCKET`을 넣어야 한다. 없으면 기동이 실패한다.
- 사진 버킷 정책의 업로드 주체와 개발 서버 역할 정책에 `s3:GetObject`가 필요하다.
- 탈퇴 시 사진 삭제는 탈퇴 기능이 생길 때 다룬다.

## 고려한 대안

- **서버가 multipart로 받아 S3에 올린다**: 크기·형식 검사를 서버가 직접 하지만, API 태스크가 이미지 트래픽을 그대로 받는다.
- **이미지 전용 도메인**: 캐시 정책을 따로 둘 수 있지만 도메인·인증서·CORS가 늘고 지금 얻는 것이 없다. `base-url`로 나중에 옮길 수 있다.
- **이전 파일을 수명 주기 규칙으로 늦게 지운다**: 방 안 화면이 더 오래 보이지만, 참조하지 않는 파일을 남기는 기간과 규칙이 생긴다.
- **관리자 삭제 API**: 관리자 인증부터 만들어야 한다. 신고 빈도가 낮은 지금은 런북이 싸다.
```

- [ ] **Step 3: 런북 작성**

`docs/runbooks/profile-image-removal.md` (SQL은 spec 그대로)

````markdown
# 프로필 사진 운영자 삭제 런북

신고된 프로필 사진을 운영자가 지울 때 쓴다. 관리 API는 없다(ADR-0033 결정 6).

| 환경 | 버킷 |
|---|---|
| 운영 | `focus-makers-prod-avatars-821365066487` |
| 개발 서버 | `focus-makers-staging-avatars-821365066487` |

## 절차

1. db-shell로 운영 DB에 접속한다.
2. 아래 SQL을 트랜잭션 안에서 실행하고, 한 줄만 바뀌었는지와 삭제할 키를 확인한 뒤 커밋한다. `RETURNING`은 바뀐 뒤 값을 돌려주므로 지우기 전 키는 서브쿼리로 받는다.

   ```sql
   BEGIN;
   UPDATE users u SET profile_image = NULL
   FROM (SELECT id, profile_image FROM users WHERE id = :userId FOR UPDATE) old
   WHERE u.id = old.id
   RETURNING u.id, old.profile_image;
   COMMIT;
   ```
3. 나온 키 하나만 `aws s3 rm s3://<버킷>/<키>`로 삭제한다. `--recursive`는 쓰지 않는다.
4. 처리한 사용자 id와 시각을 신고 메일 스레드에 남긴다.

## 참고

- 지운 뒤에도 엣지 캐시가 끝날 때까지(최대 1시간) 같은 경로가 열릴 수 있다.
- 소셜룸에 이미 들어와 있는 사람에게는 입장 때 복사한 사진 경로가 그대로 실린다. 파일을 지웠으므로 캐시가 끝나면 이니셜로 보인다.
````

- [ ] **Step 4: 전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL(테스트·Spotless·checkstyle·ArchUnit 전부). 실패를 지우거나 `@Disabled`로 피하지 않는다.

- [ ] **Step 5: 변경 파일 목록을 보고서에 적는다**

`0033-profile-image-presigned-upload.md`, `profile-image-removal.md`, `application-prod.yaml`, `application-dev.yaml`, `application-local.yaml.example`, `src/test/resources/application.yaml`, 그리고 `spotlessApply`가 고친 파일.

보고서에 함께 적을 운영 확인 사항:
- 팀원 각자의 `application-local.yaml`(gitignore)에는 `app.profile-image`가 없다. 기본값 덕에 기동은 되지만 업로드를 시험하려면 example의 블록을 복사해야 한다.
- 개발 서버는 docker 컨테이너에서 EC2 인스턴스 역할을 읽는다. 인스턴스 메타데이터 hop limit이 1이면 컨테이너가 IMDSv2 토큰을 못 받아 자격 증명을 못 읽는다. 첫 업로드 URL 발급이 자격 증명 오류로 500이면 BE 팀원에게 hop limit 2를 요청한다.

---

## 커밋 분할안 (구현·리뷰·퀴즈 뒤 사용자 승인용)

각 커밋은 앞 커밋까지 포함해 `./gradlew check`가 통과한다(설정은 `@Value` 기본값이 있어 Task 6 전에도 뜬다). 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

| 순서 | 태스크 | 메시지 |
|---|---|---|
| 1 | Task 1 | `chore: S3 SDK 추가와 프로필 사진 저장소 구현 (BY-1035)` |
| 2 | Task 2 | `feat: 프로필 사진 업로드 URL 발급 API 추가 (BY-1035)` |
| 3 | Task 3 | `feat: 프로필 사진 등록·되돌리기 API와 프로필 사진 경로 추가 (BY-1035)` |
| 4 | Task 4 | `feat: 소셜룸 멤버에 입장 시점 사진 경로 추가 (BY-1035)` |
| 5 | Task 5 | `feat: 랭킹 응답에 사진 경로 추가 (BY-1035)` |
| 6 | Task 6 설정 | `chore: 환경별 프로필 사진 버킷 설정 추가 (BY-1035)` |
| 7 | Task 6 문서 | `docs: 프로필 사진 ADR-0033과 운영자 삭제 런북 추가 (BY-1035)` |
