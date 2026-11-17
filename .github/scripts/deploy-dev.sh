#!/bin/sh
# dev 앱 서버 배포 — 워크플로가 SSH로 이 스크립트를 stdin에 흘려 `sh -s <이미지>`로 실행한다 (BY-1317).
# 서버에는 git·소스가 없다. 이미지는 GHCR에서 받고, compose 파일은 워크플로가 미리 복사해 둔다.
set -eu

IMAGE="${1:?usage: deploy-dev.sh <image-ref>}"
APP_DIR=/home/ubuntu/app-dev
HEALTH_URL=http://localhost/actuator/health
LOCK=/tmp/app-dev-deploy.lock

exec 9>"$LOCK"
if ! flock -n 9; then
  echo "another deploy is still running (lock $LOCK) — retry later"
  exit 1
fi

cd "$APP_DIR"
if [ ! -f .env ]; then
  echo ".env missing in $APP_DIR — see docs/runbooks/dev-deploy.md (server prerequisites)"
  exit 1
fi

# 직전 이미지 기록 — 기동 실패 시 되돌릴 때 쓴다
PREV=$(docker inspect --format '{{.Config.Image}}' app-dev-app-1 2>/dev/null || true)

echo "pull $IMAGE"
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin >/dev/null
docker pull "$IMAGE"
docker logout ghcr.io >/dev/null 2>&1 || true

echo "$IMAGE" > .image
APP_IMAGE="$IMAGE" docker compose -p app-dev up -d --no-build

# E2.1.Micro(1/8 OCPU)는 콜드 스타트가 90초를 넘는다 — 최대 300초 기다린다
i=0
while [ "$i" -lt 150 ]; do
  if curl -sf --max-time 3 "$HEALTH_URL" >/dev/null; then
    echo "healthy: $IMAGE (previous: ${PREV:-none})"
    # 직전 이미지는 남기고 그 이전 것만 정리한다
    docker image prune -f >/dev/null 2>&1 || true
    exit 0
  fi
  i=$((i + 1))
  sleep 2
done

echo "health check failed after 300s — dev is running the NEW image; previous: ${PREV:-none}"
docker compose -p app-dev logs --tail=60 app | tail -c 20000
exit 1
