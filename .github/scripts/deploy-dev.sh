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
