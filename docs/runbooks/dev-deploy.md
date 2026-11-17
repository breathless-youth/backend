# dev 서버 배포 런북

`dev` 브랜치에 push되면 `.github/workflows/deploy-dev.yml`이 dev 앱 서버에 자동 배포한다(BY-670 → BY-1317에서 Oracle로 이전).
dev AWS 계정 크레딧 소진으로 서버를 **Oracle Always Free**(E2.1.Micro 2대, 오사카)로 옮겼다. 비용은 0원이다.

운영(`main` → AWS ECS)과는 계정도 워크플로도 다르다. 운영은 `deploy.yml`, 릴리스 기록은 `release.md`.

## 구성

| 서버 | 역할 | 주소 | 비고 |
|---|---|---|---|
| `dev-app` | Spring 앱 컨테이너 (호스트 80 → 8080) | 공인 `161.33.29.77`, 사설 `10.0.0.213` | 앞단 Cloudflare `api-dev.focusmakers.app` |
| `dev-db` | Postgres 17 컨테이너 | 공인 `129.225.144.196`, 사설 `10.0.0.252` | 5432는 `dev-app-nsg`에서만 허용 |

- 둘 다 VM.Standard.E2.1.Micro(1/8 OCPU, 1GB, Always Free), Ubuntu 24.04, 스왑 2GB. 같은 VCN·서브넷에 coturn-a1이 있다.
- 접속: `ssh -i <키> ubuntu@<공인 IP>`. 배포 키는 GitHub 시크릿 `DEV_SSH_KEY`, 사용자 키는 `~/.ssh/id_ed25519`.
- 앱 서버 `/home/ubuntu/app-dev` — `docker-compose.yml`(배포가 매번 덮어씀), `.env`(수동, 추적 안 함), `.image`(마지막 배포 이미지).
- DB 서버 `/home/ubuntu/app-dev-db` — `.env`(`SPRING_DATASOURCE_PASSWORD`, `COMPOSE_FILE`, `COMPOSE_PROJECT_NAME`)와 compose 2개(`docker-compose.yml`, `docker-compose.dev-db.yml`).

## 배포 흐름

1. dev push → Actions가 amd64로 이미지를 빌드해 `ghcr.io/breathless-youth/backend-dev:<sha>`에 올린다. 서버는 1GB라 직접 빌드하지 않는다.
2. SSH로 `docker-compose.dev-app.yml`을 서버에 복사하고 `.github/scripts/deploy-dev.sh`를 stdin으로 실행한다.
3. 스크립트가 GHCR 로그인(일회성 `GITHUB_TOKEN`) → pull → `compose up -d` → `/actuator/health` 최대 300초 확인(1/8 OCPU라 콜드 스타트가 느리다).

다시 배포: Actions → Deploy dev → **Run workflow** (`dev`). 배포가 겹치면 큐에서 기다리고, 서버 잠금(`/tmp/app-dev-deploy.lock`)이 한 번 더 막는다.

필요한 GitHub 설정: 시크릿 `DEV_SSH_KEY`, 변수 `DEV_APP_HOST`, `DEV_SSH_KNOWN_HOSTS`(앱 서버 ed25519 호스트 키).
서버를 새로 만들면 `ssh-keyscan -t ed25519 <IP>` 결과로 `DEV_SSH_KNOWN_HOSTS`를 갱신한다.

## 실패했을 때

| 로그 | 원인 | 대응 |
|---|---|---|
| `Host key verification failed` | 서버 재생성 등으로 호스트 키가 바뀜 | `DEV_SSH_KNOWN_HOSTS` 갱신 |
| `Connection timed out` | 인스턴스 정지·공인 IP 변경·NSG | OCI 콘솔에서 상태·IP 확인, `DEV_APP_HOST` 갱신 |
| `.env missing` | 서버 `.env` 없음 | 아래 "서버 준비" |
| `pull access denied` / `unauthorized` | 패키지 권한 | 저장소 Actions 권한(packages)과 패키지 가시성 확인 |
| `health check failed after 300s` + 로그 | 새 컨테이너 기동 실패(대개 `.env` 누락·DB 연결) | `.image`·`docker images`로 직전 이미지 확인, `.env`·DB 점검 후 재배포. 이 상태에서 dev는 새 이미지로 떠 있다 |
| 응답이 느리거나 OOM | 1GB 메모리 | `free -m`, `docker stats`, 스왑 사용량 확인 |

## 서버 준비 (새로 만들 때)

1. OCI에서 E2.1.Micro 2대를 만든다. NSG: 앱은 22·80·443, DB는 22와 앱 NSG에서 오는 5432.
   cloud-init으로 `docker.io docker-compose-v2`, 스왑 2GB, iptables(22·80 / 22·5432) 개방.
2. DB 서버: compose 2개를 복사하고 `.env`를 만든 뒤 `docker compose up -d`.
3. 앱 서버 `.env`에는 `SPRING_DATASOURCE_URL=jdbc:postgresql://<DB 사설 IP>:5432/study`, `SPRING_DATASOURCE_USERNAME=study`, `SPRING_DATASOURCE_PASSWORD`(DB와 같은 값), `JWT_SECRET`, `APP_ROOM_TURN_SECRET`(운영 SSM `/focus-makers/prod/room/turn-secret`과 같은 값)을 넣는다. 나머지는 `application-dev.yaml` 기본값.
4. 첫 배포 전에 DB가 비어 있어도 Flyway가 스키마를 만든다.

## 주의

- Cloudflare는 원본을 HTTP 80으로 받는 구성(SSL 모드 Flexible)이어야 한다. Full(strict)이면 원본에 인증서가 필요하다.
- DB 볼륨은 서버 로컬 디스크 하나뿐이라 백업이 없다. dev 데이터는 잃어도 되는 전제다.
- Always Free E2.1.Micro는 2대가 한도다. 늘리면 과금될 수 있다.
