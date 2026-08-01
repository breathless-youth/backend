# ---------- 1단계: 빌드 ----------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# 의존성 캐싱: 빌드 스크립트를 소스보다 먼저 복사하면
# 소스만 바뀐 경우 의존성 다운로드 레이어를 재사용한다
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

# checkstyle 설정 파일 경로가 build.gradle에서 참조된다
COPY config ./config
COPY src ./src

# 테스트는 CI(./gradlew check)에서 수행한다 — Testcontainers가 도커를 요구하므로 이미지 빌드에서는 제외
RUN ./gradlew --no-daemon bootJar -x test

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# root 대신 권한 없는 전용 사용자로 실행한다
RUN useradd -r -u 1001 appuser

COPY --from=builder /app/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080

# JVM 기본값은 컨테이너 메모리의 25%만 힙으로 잡아 1GB 태스크에서 메모리를 놀린다.
# 힙 외 영역(메타스페이스·스레드 스택) 여유를 남기고 75%까지 사용한다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
