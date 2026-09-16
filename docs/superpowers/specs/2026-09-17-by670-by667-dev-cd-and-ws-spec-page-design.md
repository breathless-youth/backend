# dev 서버 CD(BY-670) · WebSocket 명세 페이지(BY-667) 설계

- 상태: 사용자 리뷰 대기
- 날짜: 2026-09-17
- 티켓: BY-670(dev CD, 먼저), BY-667(명세 페이지, 다음)
- PR: 두 티켓은 독립이라 PR을 나눈다. BY-670이 먼저 머지되어야 BY-667 머지 즉시 dev에서 페이지가 보인다.

## 0. 인터뷰로 확정한 결정

| 질문 | 결정 |
|---|---|
| 독자 | FE가 룸 기능을 구현·디버깅할 때 보는 계약서. 코드가 하는 그대로를 옮긴다 |
| 위치 | Spring이 서빙하는 정적 HTML. dev 서버에서 Swagger 옆 주소로 연다 |
| dev 배포 | 지금 절차(`git pull` → `docker compose up --build`)를 그대로 자동화. 이미지 기반 전환은 범위 밖 |
| 서버 접근 | SSM Run Command. SSH 키·22번 포트 의존 없음 |
| 내용 범위 | 계약 + 운영 수치·예외 처리 + 흐름 시퀀스 다이어그램 |
| 형식 | 단일 HTML 손으로 작성, Mermaid(CDN). 빌드 단계·의존성 없음 |
| 드리프트 방지 | 코드의 메시지 type·목적지가 HTML에 모두 있는지 테스트로 대조 |
| 노출 제어 | `app.docs.enabled` 프로퍼티. 값이 없으면 꺼짐(운영 404). Security에 `/docs/**` permitAll |

---

## Part A. dev 서버 CD (BY-670)

### A1. 목표와 비목표

목표: `dev` 브랜치 push(또는 수동 실행) 한 번으로 dev EC2에 최신 코드가 뜨고, 안 뜨면 워크플로가 실패로 알린다.

비목표: ECR 이미지 배포, `.env` 관리(BY-642), 보안그룹 정리, DB 롤백, prod 워크플로 변경.

### A2. 사실 (2026-09-17 확인)

| 항목 | 값 |
|---|---|
| dev AWS 계정 | 325574368445 (운영 821365066487과 다름). 로컬 프로파일 `focusdev` |
| 인스턴스 | `i-09590430695b1e5bd`, Ubuntu 26.04, t3.small, ap-northeast-2c |
| 서버 경로·브랜치 | `/home/ubuntu/app-dev`, `dev`. `.env`는 같은 디렉터리(compose `env_file`) |
| 현재 수동 절차 | SSH → `git pull` → `docker compose up --build` |
| 준비 전 상태 | 인스턴스 프로파일 없음(SSM 미등록), OIDC 공급자·IAM 롤 없음 |

### A3. 흐름

```
dev push ─▶ deploy-dev.yml ─▶ OIDC로 github-deploy-dev assume
        ─▶ ssm send-command (AWS-RunShellScript, 인스턴스 1대)
        ─▶ get-command-invocation 폴링 (최대 30분)
        ─▶ Success면 성공, 그 외는 stdout/stderr 출력 후 실패
```

### A4. 워크플로 `.github/workflows/deploy-dev.yml`

- 트리거: `push: branches: [dev]`, `workflow_dispatch`
- `permissions: id-token: write, contents: read`
- `concurrency: group: deploy-dev, cancel-in-progress: false` — 운영과 같은 이유(늦게 시작한 배포가 먼저 끝나 덮어쓰는 문제 방지, 진행 중 취소 금지)
- 액션은 커밋 SHA 고정(`actions/checkout`, `aws-actions/configure-aws-credentials` — 운영 `deploy.yml`과 같은 SHA 재사용)
- 러너: `ubuntu-latest` (러너에서 빌드하지 않으므로 ARM 불필요)
- 입력값: 시크릿 `AWS_DEV_DEPLOY_ROLE_ARN`, 변수 `DEV_INSTANCE_ID`, `env.AWS_REGION: ap-northeast-2`
- 스텝
  1. checkout (스크립트 파일을 리포에서 읽기 위해)
  2. configure-aws-credentials (OIDC)
  3. `aws ssm send-command` — `--document-name AWS-RunShellScript`, `--instance-ids $DEV_INSTANCE_ID`, `--parameters` `commands`=아래 A5 스크립트, `executionTimeout=1800`, `--timeout-seconds 600`; `--comment "deploy-dev <sha>"`; 출력에서 `CommandId` 추출
  4. 폴링: `aws ssm get-command-invocation`을 10초 간격으로, 처음 6회만 조회 오류를 Pending으로 간주, 상한 2,500초(배달 600 + 실행 1,800보다 길게). 종료 시 `StandardOutputContent`·`StandardErrorContent`를 로그에 출력하고 `Status != Success`면 `exit 1`
  - 잡 `timeout-minutes: 45`, `INSTANCE_ID`는 잡 레벨 env, 비어 있으면 즉시 실패
- CI 게이트: PR은 이미 CI를 통과해야 dev에 머지되므로 운영과 같이 push 즉시 배포한다

### A5. 서버 스크립트 (`.github/scripts/deploy-dev.sh`, 워크플로가 읽어 SSM 파라미터로 전달)

```sh
#!/bin/sh
# dev 서버 배포 절차 — SSM AWS-RunShellScript가 root·/bin/sh로 실행한다 (BY-670).
# 손으로 하던 "git pull → docker compose up --build"를 그대로 옮기고, 기동 확인을 더했다.
# bash 전용 문법은 쓰지 않는다 (dash 호환). 파이프는 진단 출력과 if 조건에만 있어 pipefail이 필요 없다(조건은 마지막 명령의 상태만 본다).
set -eu

APP_DIR=/home/ubuntu/app-dev
HEALTH_URL=http://localhost:8080/actuator/health
# 빌드 전체 로그는 리포 밖 서버 파일에 남긴다(워킹트리 안에 두면 git status에 계속 뜬다). SSM이 워크플로로 돌려주는
# 인라인 출력은 stdout 24,000자·stderr 8,000자까지라 빌드 출력을 그대로 흘리면 끝부분(실패 원인)이 잘린다 —
# 실패 시 파일의 끝 120줄(최대 20,000바이트)만 stdout으로 보여준다
LOG=/home/ubuntu/deploy-dev.log
TAIL_LINES=120
TAIL_BYTES=20000
LOCK=/var/lock/app-dev-deploy.lock
# HTTPS 자격 증명이 없으면 git이 프롬프트를 띄우고 SSM 아래에서 영원히 멈춘다 — 즉시 실패시킨다
export GIT_TERMINAL_PROMPT=0

# 서버 측 직렬화 — 워크플로 폴링이 끝난 뒤에도 원격 명령이 살아 있으면 다음 배포와 겹칠 수 있다.
# 잠금을 못 잡으면 기다리지 않고 바로 실패한다(재실행은 사람이 결정)
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "another deploy is still running (lock $LOCK) — retry later"
  exit 1
fi

cd "$APP_DIR"

# 추적 파일의 로컬 수정(스테이징 포함)이 있으면 그 수정본이 빌드되면서 origin SHA로 보고된다 — 거부한다.
# 추적 대상이 아닌 .env·compose override 파일은 영향 없다
if ! sudo -u ubuntu -H git diff --quiet HEAD; then
  echo "server has uncommitted changes to tracked files — refusing to deploy"
  exit 1
fi

# git은 디렉터리 소유자(ubuntu)로 — root로 실행하면 .git 안 파일 소유권이 root로 바뀌거나
# git이 "dubious ownership"으로 거부한다
sudo -u ubuntu -H git fetch origin dev
sudo -u ubuntu -H git checkout dev
# --ff-only: 서버에 로컬 커밋이 생겨 있으면 조용히 병합하지 않고 실패시킨다
sudo -u ubuntu -H git merge --ff-only origin/dev
# --ff-only는 갈라진 경우만 막는다. 서버가 origin/dev보다 앞서 있으면 "Already up to date"로 통과하므로 따로 확인한다
if [ "$(sudo -u ubuntu -H git rev-parse HEAD)" != "$(sudo -u ubuntu -H git rev-parse origin/dev)" ]; then
  echo "server dev is ahead of origin/dev — refusing to deploy unpushed commits"
  exit 1
fi

# 리포의 docker-compose.yml에는 postgres만 있다. app 서비스는 서버의 override 파일이 정의하므로,
# 그 파일이 없으면 compose up이 "빌드할 것 없음"으로 조용히 성공해 옛 코드가 초록으로 보고된다 — 미리 막는다
if ! services=$(docker compose config --services); then
  echo "docker compose config failed — the server's compose files (override included) are invalid"
  exit 1
fi
if ! printf '%s\n' "$services" | grep -qv '^postgres$'; then
  echo "compose project defines no app service (only postgres) — check the server's compose override file"
  exit 1
fi

# docker는 데몬 기반이라 root로 실행해도 사람이 손으로 띄운 것과 같은 프로젝트(app-dev)를 갱신한다.
# 빌드가 끝나야 컨테이너를 교체하므로 빌드 실패 시 기존 컨테이너는 그대로 살아 있다
echo "building — full log on the server: $LOG"
if ! docker compose up -d --build >"$LOG" 2>&1; then
  echo "docker compose up --build failed — last $TAIL_LINES lines of $LOG:"
  tail -n "$TAIL_LINES" "$LOG" | tail -c "$TAIL_BYTES"
  exit 1
fi

# 기동 확인 최대 60초 — .env 시크릿 누락 등 기동 실패를 워크플로 실패로 드러낸다.
# 여기서 실패하면 dev는 이미 새(깨진) 코드로 떠 있다 — 직전 이미지는 아래 prune 전이라 아직 남아 있다
i=0
while [ "$i" -lt 30 ]; do
  if curl -sf --max-time 2 "$HEALTH_URL" >/dev/null; then
    echo "healthy: $(sudo -u ubuntu -H git rev-parse --short HEAD)"
    # 서버 빌드로 쌓이는 dangling 이미지 정리 — 기동 확인 뒤에 해야 실패 시 직전 이미지로 되돌릴 수 있다.
    # 정리 실패로 배포를 실패 처리하지 않는다
    docker image prune -f >>"$LOG" 2>&1 || true
    exit 0
  fi
  i=$((i + 1))
  sleep 2
done

echo "health check failed after 60s — dev is now running the NEW build; previous image is still present for rollback"
docker compose logs --tail=50 | tail -c "$TAIL_BYTES"
exit 1
```

- `--ff-only`: 서버에 로컬 커밋이 있으면 병합하지 않고 실패한다. `.env`는 추적 대상이 아니라 영향 없다
- `compose up --build`는 빌드가 끝나야 컨테이너를 교체한다. 빌드 실패 시 기존 컨테이너는 유지된다
- 빌드 출력은 서버 `/home/ubuntu/deploy-dev.log`(리포 밖)로, 실패 시 끝 120줄만 stdout으로 — SSM 인라인 출력 한도(stdout 24,000자·stderr 8,000자) 때문에 실패 원인이 잘리지 않게 한다
- 추적 파일의 로컬 수정이 있으면 배포 거부(`git diff --quiet HEAD`) — 수정본이 origin SHA로 보고되는 것을 막는다
- compose 설정 자체의 오류와 "app 서비스 없음"을 구분해 보고한다
- health 60초 대기: `.env` 시크릿 누락 등 기동 실패가 워크플로 실패로 드러난다. 앱이 호스트 8080에 노출된다고 가정하며, 다르면 런북에서 포트만 바꾼다
- `docker image prune -f`: 서버 빌드로 쌓이는 dangling 이미지 정리(디스크 고갈 예방)
- git 커맨드는 `sudo -u ubuntu -H`로 — root 실행 시 `.git` 소유권 문제 또는 "dubious ownership" 오류 방지
- `curl --max-time 2`: 연결 대기 시간 한계 설정(반복 간격 정확성)
- 서버 잠금(`flock`)으로 배포 직렬화 — 폴링 종료 후에도 원격 실행이 남는 경우 대비
- fetch 후 HEAD == origin/dev 확인 — `--ff-only`는 서버가 앞서 있는 경우를 막지 못한다
- compose 프로젝트에 postgres 외 서비스가 있는지 확인 — 리포 compose는 postgres뿐이라 override 부재 시 "배포 안 하고 초록" 방지
- prune은 health 성공 뒤, 실패해도 배포 실패로 치지 않는다
- `GIT_TERMINAL_PROMPT=0` — 자격 증명 없으면 멈추지 않고 실패

### A6. dev 계정 리소스 (1회, 스크립트 `setup-dev-aws.sh`로 생성, 런북에 수록)

| 리소스 | 이름 | 내용 |
|---|---|---|
| IAM 롤 + 인스턴스 프로파일 | `focus-dev-ec2-ssm` | 신뢰 `ec2.amazonaws.com`, 정책 `AmazonSSMManagedInstanceCore`. 인스턴스에 연결(재부팅 없음) |
| OIDC 공급자 | `token.actions.githubusercontent.com` | audience `sts.amazonaws.com` |
| IAM 롤 | `github-deploy-dev` | 신뢰 조건 `sub = repo:breathless-youth/backend:ref:refs/heads/dev`, `aud = sts.amazonaws.com`. 인라인 정책: `ssm:SendCommand`는 인스턴스 1대 + 문서 `AWS-RunShellScript`에만, `ssm:GetCommandInvocation`는 `*` |
| GitHub | 시크릿 `AWS_DEV_DEPLOY_ROLE_ARN`, 변수 `DEV_INSTANCE_ID` | `arn:aws:iam::325574368445:role/github-deploy-dev`, `i-09590430695b1e5bd` |

SSM 에이전트는 Ubuntu AMI에 내장. 프로파일 연결 후 수 분 내 `aws ssm describe-instance-information`에 `Online`으로 보인다. 안 보이면 서버에서 `sudo snap restart amazon-ssm-agent`.
IAM `--description`은 ASCII/Latin-1만 허용한다 — 한글 설명은 CreateRole이 거부한다.

### A7. 런북 `docs/runbooks/dev-deploy.md`

1. 평소: dev 머지 → Actions "Deploy dev" 확인. 수동 재배포는 workflow_dispatch
2. 실패 시: 워크플로 로그의 stdout/stderr → `git pull` 실패(로컬 변경)·빌드 실패·health 실패 세 갈래와 각 대응
3. 계정 준비(위 A6)를 처음부터 다시 하는 CLI 전문. `focusdev` 프로파일 만드는 법
4. SSM 미등록·롤 신뢰 실패(`Not authorized to perform sts:AssumeRoleWithWebIdentity`) 진단

### A8. 검증

- 브랜치 push 전: `act`는 쓰지 않는다. 대신 워크플로 머지 후 `workflow_dispatch`로 첫 배포를 돌려 성공을 확인한다
- 실패 경로 확인: 서버에서 임시로 `.env`의 한 시크릿을 비운 뒤 재배포 → health 실패로 워크플로 실패하는지 확인하고 복구
- 스크립트 자체는 `bash -n`으로 문법 검사(CI `check`에는 넣지 않는다 — Gradle 프로젝트 범위 밖)

### A9. 실패 모드

| 상황 | 결과 | 대응 |
|---|---|---|
| 서버에 로컬 커밋/변경 | `--ff-only` 실패, 기존 컨테이너 유지 | 서버에서 정리 후 재실행 |
| Gradle 빌드 실패 | 기존 컨테이너 유지, 워크플로 실패 | 코드 수정 |
| `.env` 누락 | 새 컨테이너 기동 실패 → health 실패 (새 코드로 떠 있음) | BY-642 절차로 `.env` 반영 |
| 동시 push | 큐잉 후 순서대로 | 없음 |
| SSM에 등록된 적 없음 | send-command가 `InvalidInstanceId` | 런북 "계정 준비" 1 |
| 에이전트 오프라인(등록 후) | `Pending`/`Delayed` → 600초 뒤 `TimedOut` | 런북 AWS 표 |
| 서버 dev가 origin보다 앞섬 | `server dev is ahead of origin/dev`, 배포 안 함 | PR로 올리거나 reset |
| 서버 override 파일 없음 | `compose project defines no app service`, 배포 안 함 | 런북 "서버 전제" |
| 앞 배포가 아직 실행 중 | `another deploy is still running` | 끝난 뒤 재실행 |
| 서버 추적 파일에 로컬 수정 | `server has uncommitted changes to tracked files`, 배포 안 함 | stash/버림 또는 override로 이동 |
| 서버 compose 파일 문법 오류 | `docker compose config failed`, 배포 안 함 | `docker compose config`로 확인 |
| health 실패 | 새(깨진) 코드로 떠 있음, 직전 이미지 보존 | 이미지 되돌리기 또는 수정 후 재배포 |

---

## Part B. WebSocket 명세 페이지 (BY-667)

### B1. 목표와 비목표

목표: FE가 룸 STOMP 계약을 구현·디버깅할 때 코드 대신 보는 한 페이지. BE가 계약을 바꾸면 같은 PR에서 이 페이지를 고치고, 빠뜨리면 `check`가 실패한다.

비목표: 다른 도메인 문서, AsyncAPI 생성, 문서 버전 자동화, REST API 문서(Swagger가 담당).

### B2. 서빙 구조

| 항목 | 설계 |
|---|---|
| 파일 | `src/main/resources/docs/websocket.html` (`static/` 밖 → 기본 서빙 안 됨) |
| 매핑 | `project.study.config.DocsResourceConfig` — `WebMvcConfigurer`, `@ConditionalOnProperty(name="app.docs.enabled", havingValue="true")`, `addResourceHandler("/docs/**")` → `classpath:/docs/`. 컨트롤러 없음, ETag·Content-Type은 Spring이 처리 |
| URL | `/docs/websocket.html` |
| Security | `SecurityConfig`에 `.requestMatchers("/docs/**").permitAll()` — Swagger 줄 옆, 주석 "prod는 app.docs.enabled가 없어 매핑 자체가 없다(404)" |
| 프로퍼티 | `application-dev.yaml`·`application-local.yaml.example`에 `app.docs.enabled: true`. prod·테스트 yaml에는 없음(기본 꺼짐) |
| Swagger 링크 | `RoomController`의 `@Tag` description 끝에 "메시지 계약은 `/docs/websocket.html`" 한 줄 |
| CLAUDE.md | 규칙 한 줄: 룸 STOMP 계약(메시지 type·필드·목적지·수치)을 바꾸면 `docs/websocket.html`과 변경 이력을 같은 PR에서 갱신 |

### B3. 페이지 내용

한국어. 단일 HTML, CSS 인라인, 시스템 폰트, `prefers-color-scheme`로 다크모드. Mermaid는 `https://cdn.jsdelivr.net/npm/mermaid@11`을 ES 모듈로 로드(다이어그램 4개). 상단에 "기준 커밋/날짜"와 목차.

| 절 | 필수 내용 (값은 코드 기준) |
|---|---|
| 1. 연결 | `/ws` 순수 WebSocket(SockJS 없음, `@stomp/stompjs`). CONNECT 프레임 헤더 `Authorization: Bearer <access>` — URL 쿼리 금지(ALB 로그에 남음). 인증 실패 → ERROR 프레임 후 소켓 종료. 검증은 CONNECT 1회, 접속 중 만료돼도 세션 유지, 재접속 전 갱신은 클라 몫. heartbeat 서버 제안 10000/10000 |
| 2. 입장 시퀀스 | `POST /api/rooms/join` → 자리 예약 30초 + ICE 서버 → `/user/queue/room` 구독 → `/topic/room/{roomId}` 구독 = 확정 → 요청 세션에 SNAPSHOT, 방에 MEMBER_JOINED. 순서 규칙: 큐 구독을 토픽 구독보다 먼저(ROOM_UNAVAILABLE·SNAPSHOT을 받기 위해). `graceRejoin=true`는 같은 방 자리가 살아 있어 재입장한 경우이며 `cameraOn`은 이전 값 |
| 3. 목적지 | 구독 허용: `/topic/room/{roomId}`(멤버만), `/user/queue/room`. 그 외 구독은 조용히 거부(멤버 아님이면 ROOM_UNAVAILABLE). 발행 허용: `/app/**`만. 클라가 `/topic`·`/queue`로 직접 SEND하면 드랍 |
| 4. 클라→서버 | `/app/room/{roomId}/state` `{cameraOn?: boolean, focusState?: "FOCUS"\|"DISTRACTED", focusSec?: int≥0}` — 무효 필드만 무시, 나머지 반영, 반영된 필드만 이벤트 발생. `/app/room/{roomId}/signal` `{toUserId, kind: "OFFER"\|"ANSWER"\|"CANDIDATE", payload}` — 발신자 현재 세션·수신자 확정 멤버 아니면 조용히 무시. `/app/room/{roomId}/snapshot` 본문 없음 — 요청 세션에만 SNAPSHOT 재발송, 비멤버·옛 세션은 무응답 |
| 5. 서버→클라 | 8종 표: SNAPSHOT(세션 큐, `members[]`), MEMBER_JOINED(토픽, `member`), MEMBER_LEFT(토픽, `userId`), CAMERA_CHANGED(토픽, `userId, cameraOn`), FOCUS_CHANGED(토픽, `userId, focusState`), STUDY_TIME(토픽, `userId, focusSec`), SIGNAL(세션 큐 → 대상 유저, `fromUserId, kind, payload`), ROOM_UNAVAILABLE(세션 큐, `roomId`). 각각 JSON 예시. `RoomMember` 필드 8개(`userId, nickname, goal, category, cameraOn, focusState, focusSec, disconnected`) |
| 6. 시그널링 시퀀스 | A→서버 OFFER→B, B→서버 ANSWER→A, 양방향 CANDIDATE. 서버는 payload를 해석·저장하지 않는다. ICE 서버 목록·TTL은 join 응답(`iceServers`, `iceTtlSeconds`) |
| 7. 끊김·재접속 시퀀스 | 소켓 끊김 → 30초 유예, 다른 멤버는 SNAPSHOT 재요청 시 `disconnected: true`로 봄 → 유예 내 join 재호출 + 재구독이면 자리 유지(`graceRejoin`), 초과면 자동 퇴장 MEMBER_LEFT. 다른 방 join 시 기존 방에서 자동 퇴장(MEMBER_LEFT). 배포 중 소켓이 닫히면 같은 절차 |
| 8. 퇴장·소멸 | `POST /api/rooms/{roomId}/leave` → 방에 MEMBER_LEFT. 마지막 1명 퇴장 시 방·코드 소멸. 소멸 코드는 10분간 `ROOM_CLOSED`, 이후 `INVITE_CODE_NOT_FOUND`. 생성 후 10분 무입장이면 소멸 |
| 9. FE가 처리할 것 | 토픽 구독 후 SNAPSHOT이 안 오면 `/app/room/{id}/snapshot` 재요청(재시도 소진 시 join 재호출). 피어 실패 시 10초 주기 SNAPSHOT 재대조. ROOM_UNAVAILABLE 수신 시 join 재호출 또는 종료 안내(배달 보장 없음, 미도착도 같은 처리). BY-668과 동일 |
| 10. 수치 | heartbeat 10초 / 메시지 16KB / 세션 송신버퍼 64KB·5초(초과 시 세션 종료) / 정원 6 / 예약 30초 / 유예 30초 / 빈 방 600초 / 소멸 코드 600초 |
| 11. 변경 이력 | 날짜·티켓·요약. 첫 항목은 BY-667 |

### B4. `RoomMessageType` 상수화 (동작 불변 리팩토링)

- `project.study.room.websocket.RoomMessageType` enum: `SNAPSHOT, MEMBER_JOINED, MEMBER_LEFT, CAMERA_CHANGED, FOCUS_CHANGED, STUDY_TIME, SIGNAL, ROOM_UNAVAILABLE`
- 5개 파일의 `"type", "X"` 리터럴을 `"type", RoomMessageType.X.name()`으로 교체: `RoomStompHandler`, `StompEventListener`, `RoomMessenger`, `RoomController`, `RoomCleanupScheduler`. 와이어 포맷은 문자열 그대로
- `RoomStompHandler.SIGNAL_KINDS`·`FOCUS_STATES`는 패키지 가시성으로 바꿔 테스트가 참조

### B5. 대조 테스트 `project.study.room.websocket.WebSocketDocsContractTest` (Spring 없음)

HTML을 클래스패스에서 읽어 다음이 전부 본문에 있는지 확인한다.
1. `RoomMessageType.values()` 각각 — `"type": "X"` 형태
2. `RoomStompHandler`의 모든 `@MessageMapping` 값(리플렉션) — `/app` 접두어를 붙인 문자열
3. 구독 목적지 `/topic/room/{roomId}`, `/user/queue/room` (코드에선 정규식·리터럴이라 테스트 상수로 둔다)
4. `SIGNAL_KINDS`·`FOCUS_STATES` 원소 각각
5. `RoomMember` 레코드 컴포넌트 이름 각각(리플렉션)

실패 메시지는 "docs/websocket.html에 X가 없다 — 계약을 바꿨으면 문서와 변경 이력을 같이 갱신" 형태.

### B6. 통합 테스트 `project.study.config.DocsResourceConfigTest`

- `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration)`, `MockMvcTester`
- 프로퍼티 `app.docs.enabled=true`: 인증 없이 `GET /docs/websocket.html` → 200, `text/html`, 본문에 `<title>`
- 별도 컨텍스트(프로퍼티 없음): 같은 요청 → 404

### B7. 범위 밖으로 남기는 것

- 필드 타입·의미까지 코드에서 자동 검증(AsyncAPI 급) — 상수·목적지 수준 대조로 시작
- 페이지 다국어, 검색

---

## PR 분할

| PR | 브랜치 | 내용 |
|---|---|---|
| 1 (BY-670) | `chore/BY-670-dev-서버-CD` | 이 스펙, `deploy-dev.yml`, `.github/scripts/deploy-dev.sh`, `docs/runbooks/dev-deploy.md` |
| 2 (BY-667) | `feat/BY-667-websocket-명세-페이지` (PR 1 머지 후 dev 기준) | `RoomMessageType` 리팩토링, HTML, `DocsResourceConfig`, Security 한 줄, yaml, Swagger 링크, CLAUDE.md 한 줄, 테스트 2개 |
