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

# Sentry 이슈에 어느 배포에서 발생했는지 표시하기 위해 커밋 SHA를 굽는다.
# CI가 --build-arg GIT_SHA로 주입한다 (.github/workflows/deploy.yml).
# ARG는 사용하는 스테이지마다 선언해야 하므로 빌드 스테이지가 아닌 여기에 둔다.
ARG GIT_SHA=unknown
ENV SENTRY_RELEASE=$GIT_SHA

# root 대신 권한 없는 전용 사용자로 실행한다
RUN useradd -r -u 1001 appuser

COPY --from=builder /app/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080

# 힙 상한 50% + direct 버퍼 상한 명시 (BY-491).
# 부하테스트에서 OOM(exit 137)이 힙 455MB/1GiB 시점에 발생 — 주범이 힙이 아니라
# 웹소켓 커넥션의 native 메모리(direct 버퍼·커널 소켓버퍼·스레드 스택)였다.
# 75%는 native 몫을 25%만 남겨 웹소켓 1000+ 커넥션에서 부족하다. 50%로 낮춰
# native 공간을 보장하고, 힙 부족은 컨테이너 킬 대신 진단 가능한 힙 OOM으로 죽게 한다.
# (실측 힙 사용: 3초 체크포인트 1000명 기준 ~220MB — 50%로도 2배 이상 여유)
# 환경별 오버라이드는 task definition의 JAVA_OPTS env로 가능하다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:MaxDirectMemorySize=256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
