#!/bin/sh
# dev 서버 배포 절차 — SSM AWS-RunShellScript가 root·/bin/sh로 실행한다 (BY-670).
# 손으로 하던 "git pull → docker compose up --build"를 그대로 옮기고, 기동 확인을 더했다.
# bash 전용 문법은 쓰지 않는다 (dash 호환). 파이프가 없으므로 pipefail도 필요 없다.
set -eu

APP_DIR=/home/ubuntu/app-dev
HEALTH_URL=http://localhost:8080/actuator/health

cd "$APP_DIR"

# git은 디렉터리 소유자(ubuntu)로 — root로 pull하면 .git 안 파일 소유권이 root로 바뀌어
# 사람이 SSH로 들어가 손으로 작업할 때 막힌다
sudo -u ubuntu -H git checkout dev
# --ff-only: 서버에 로컬 커밋이 생겨 있으면 조용히 병합하지 않고 실패시킨다
sudo -u ubuntu -H git pull --ff-only origin dev

# docker는 데몬 기반이라 root로 실행해도 사람이 손으로 띄운 것과 같은 프로젝트(app-dev)를 갱신한다.
# 빌드가 끝나야 컨테이너를 교체하므로 빌드 실패 시 기존 컨테이너는 그대로 살아 있다
docker compose up -d --build
# 서버 빌드로 쌓이는 dangling 이미지 정리 — 디스크 고갈 예방
docker image prune -f

# 기동 확인 최대 60초 — .env 시크릿 누락 등 기동 실패를 워크플로 실패로 드러낸다
i=0
while [ "$i" -lt 30 ]; do
  if curl -sf "$HEALTH_URL" >/dev/null; then
    echo "healthy: $(git rev-parse --short HEAD)"
    exit 0
  fi
  i=$((i + 1))
  sleep 2
done

echo "health check failed after 60s" >&2
docker compose logs --tail=50
exit 1
