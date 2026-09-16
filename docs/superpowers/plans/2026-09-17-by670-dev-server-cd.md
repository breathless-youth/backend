# dev 서버 CD (BY-670) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `dev` 브랜치 push 한 번으로 dev EC2에 최신 코드가 뜨고, 안 뜨면 워크플로가 실패로 알린다.

**Architecture:** GitHub Actions가 OIDC로 dev 계정 롤을 assume하고, SSM Run Command(`AWS-RunShellScript`)로 인스턴스 1대에서 `git pull --ff-only` → `docker compose up -d --build` → health 확인을 실행한다. 서버 측 절차는 지금 손으로 치는 것과 같고, 이미지 레지스트리·SSH는 쓰지 않는다.

**Tech Stack:** GitHub Actions, `aws-actions/configure-aws-credentials`(OIDC), AWS SSM, docker compose, bash(POSIX sh 호환).

**Spec:** `docs/superpowers/specs/2026-09-17-by670-by667-dev-cd-and-ws-spec-page-design.md` Part A

## Global Constraints

- dev AWS 계정 `325574368445`, 리전 `ap-northeast-2`, 인스턴스 `i-09590430695b1e5bd`, 로컬 프로파일 `focusdev`
- 서버 경로 `/home/ubuntu/app-dev`, 브랜치 `dev`, `.env`는 같은 디렉터리(추적 대상 아님)
- 액션은 커밋 SHA 고정 — 운영 `deploy.yml`과 같은 SHA: `actions/checkout@11d5960a326750d5838078e36cf38b85af677262`, `aws-actions/configure-aws-credentials@7474bc4690e29a8392af63c5b98e7449536d5c3a`
- `concurrency: group: deploy-dev, cancel-in-progress: false`
- GitHub 시크릿 `AWS_DEV_DEPLOY_ROLE_ARN` = `arn:aws:iam::325574368445:role/github-deploy-dev`, 변수 `DEV_INSTANCE_ID` = `i-09590430695b1e5bd`
- 서버 스크립트는 SSM이 `/bin/sh`(dash)로 실행하므로 bash 전용 문법(`pipefail`, `[[ ]]`)을 쓰지 않는다
- git은 `sudo -u ubuntu`로, docker는 root로 실행
- 커밋 컨벤션 `<type>: <설명>`, 브랜치 `chore/BY-670-dev-서버-CD`(이미 생성, 스펙 커밋 1개 있음)
- 이 PR에는 Java 변경이 없지만 커밋 전 `./gradlew check`는 규칙대로 실행한다

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `.github/scripts/deploy-dev.sh` | 서버에서 도는 배포 절차 하나. 워크플로가 읽어 SSM 파라미터로 보낸다 |
| `.github/workflows/deploy-dev.yml` | 트리거·OIDC·SSM 전송·완료 폴링·실패 판정 |
| `docs/runbooks/dev-deploy.md` | 평소 사용법, 실패 시 진단, dev 계정 1회 준비 CLI 전문 |

---

### Task 1: 서버 배포 스크립트

**Files:**
- Create: `.github/scripts/deploy-dev.sh`

**Interfaces:**
- Produces: 파일 경로 `.github/scripts/deploy-dev.sh` — Task 2의 워크플로가 `jq --rawfile`로 읽는다. 종료 코드 0 = 배포 성공(health 통과), 그 외 = 실패

- [ ] **Step 1: 스크립트 작성**

```sh
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
```

- [ ] **Step 2: 문법 검사 (dash 호환까지)**

Run: `sh -n .github/scripts/deploy-dev.sh && bash -n .github/scripts/deploy-dev.sh && echo OK`
Expected: `OK`

- [ ] **Step 3: 실행 권한 없이 두는 것 확인**

파일은 SSM이 내용을 읽어 실행하므로 실행 비트가 필요 없다. `git add` 후 `git diff --cached --summary`에 `mode 100644`로 보이면 된다.

- [ ] **Step 4: 커밋**

```bash
git add .github/scripts/deploy-dev.sh
git commit -m "chore: dev 서버 배포 스크립트 — pull·compose up·health 확인 (BY-670)"
```

---

### Task 2: 배포 워크플로

**Files:**
- Create: `.github/workflows/deploy-dev.yml`
- 참고: `.github/workflows/deploy.yml` (운영, 같은 골격)

**Interfaces:**
- Consumes: `.github/scripts/deploy-dev.sh` (Task 1), 시크릿 `AWS_DEV_DEPLOY_ROLE_ARN`, 변수 `DEV_INSTANCE_ID`
- Produces: Actions 워크플로 "Deploy dev" — dev push·수동 실행

- [ ] **Step 1: 워크플로 작성**

```yaml
name: Deploy dev

on:
  push:
    branches: [dev]
  workflow_dispatch: # 재배포·검증용 수동 실행

env:
  AWS_REGION: ap-northeast-2

# OIDC로 AWS 자격 증명을 받기 위해 필요하다 (액세스 키를 저장하지 않는다)
permissions:
  id-token: write
  contents: read

# dev에 연속으로 push되면 늦게 시작한 배포가 먼저 끝나 이전 코드로 덮어쓸 수 있다.
# 새 실행은 큐에 세워 순서대로만 배포한다 (진행 중 취소는 compose 교체 도중 끊길 수 있어 쓰지 않는다)
concurrency:
  group: deploy-dev
  cancel-in-progress: false

jobs:
  deploy:
    # 빌드는 서버에서 하므로 러너 아키텍처는 무관하다
    runs-on: ubuntu-latest
    steps:
      # 액션은 id-token: write로 AWS 자격을 얻는 워크플로라 커밋 SHA로 고정한다 (운영 deploy.yml과 같은 SHA)
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4

      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@7474bc4690e29a8392af63c5b98e7449536d5c3a # v4
        with:
          role-to-assume: ${{ secrets.AWS_DEV_DEPLOY_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Send deploy command (SSM)
        id: send
        env:
          INSTANCE_ID: ${{ vars.DEV_INSTANCE_ID }}
        run: |
          # 스크립트 파일 전체를 commands 배열의 한 원소로 넘긴다 — jq가 줄바꿈·따옴표를 이스케이프한다
          PARAMS=$(jq -n --rawfile script .github/scripts/deploy-dev.sh \
            '{commands: [$script], executionTimeout: ["1800"]}')
          COMMAND_ID=$(aws ssm send-command \
            --instance-ids "$INSTANCE_ID" \
            --document-name AWS-RunShellScript \
            --comment "deploy-dev ${GITHUB_SHA::7}" \
            --parameters "$PARAMS" \
            --query 'Command.CommandId' --output text)
          echo "command_id=$COMMAND_ID" >> "$GITHUB_OUTPUT"

      - name: Wait for completion
        env:
          INSTANCE_ID: ${{ vars.DEV_INSTANCE_ID }}
          COMMAND_ID: ${{ steps.send.outputs.command_id }}
        run: |
          # 전송 직후 몇 초는 InvocationDoesNotExist가 나므로 실패를 Pending으로 본다. 10초 × 180 = 30분 상한
          for _ in $(seq 1 180); do
            STATUS=$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
              --query Status --output text 2>/dev/null || echo Pending)
            case "$STATUS" in
              Pending|InProgress|Delayed) sleep 10 ;;
              *) break ;;
            esac
          done
          aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
            --query '{Status: Status, Out: StandardOutputContent, Err: StandardErrorContent}' \
            --output json > result.json
          echo "::group::server stdout"; jq -r '.Out' result.json; echo "::endgroup::"
          echo "::group::server stderr"; jq -r '.Err' result.json; echo "::endgroup::"
          FINAL=$(jq -r '.Status' result.json)
          echo "SSM status: $FINAL"
          [ "$FINAL" = "Success" ] || exit 1
```

- [ ] **Step 2: YAML 파싱 검사**

Run: `ruby -ryaml -e 'YAML.load_file(".github/workflows/deploy-dev.yml"); puts "YAML OK"'`
Expected: `YAML OK`

- [ ] **Step 3: 인라인 셸 검사** — 두 `run:` 블록을 파일로 뽑아 `bash -n`

Run:
```bash
ruby -ryaml -e 'y=YAML.load_file(".github/workflows/deploy-dev.yml"); y["jobs"]["deploy"]["steps"].each{|s| next unless s["run"]; File.write("/tmp/step.sh", s["run"]); system("bash -n /tmp/step.sh") or abort("bad: #{s["name"]}")}; puts "shell OK"'
```
Expected: `shell OK`

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/deploy-dev.yml
git commit -m "chore: dev push 시 SSM으로 dev 서버 자동 배포 워크플로 (BY-670)"
```

---

### Task 3: 런북

**Files:**
- Create: `docs/runbooks/dev-deploy.md`
- 참고: `docs/runbooks/release.md` (문체·구조)

**Interfaces:**
- Consumes: Task 1·2의 파일명, 스펙 A6의 리소스 이름

- [ ] **Step 1: 런북 작성**

````markdown
# dev 서버 배포 런북

`dev` 브랜치에 push되면 `.github/workflows/deploy-dev.yml`이 dev EC2에 자동 배포한다(BY-670).
서버 절차는 손으로 하던 것과 같다: `/home/ubuntu/app-dev`에서 `git pull` → `docker compose up -d --build`.
다른 점은 GitHub Actions가 SSM Run Command로 그 절차를 실행하고, `/actuator/health`가 60초 안에
응답하지 않으면 실패로 표시한다는 것뿐이다. SSH 키·22번 포트는 쓰지 않는다.

운영(`main` → ECS)과는 계정도 워크플로도 다르다. 운영은 `deploy.yml`, 릴리스 기록은 `release.md`.

## 평소

- dev 머지 → Actions **Deploy dev** 실행이 초록이면 끝. 서버 stdout에 `healthy: <sha>`가 찍힌다.
- 다시 배포하고 싶으면 Actions → Deploy dev → **Run workflow** (`dev` 브랜치).
- 배포가 겹치면 뒤 실행은 큐에서 기다린다. 취소하지 않는다.

## 실패했을 때

워크플로 로그의 "server stdout / server stderr" 그룹을 먼저 본다. 세 갈래다.

| 로그 | 원인 | 대응 |
|---|---|---|
| `fatal: Not possible to fast-forward` | 서버에 로컬 커밋·변경이 있다 | SSH로 들어가 `git status`, 정리 후 재실행. 기존 컨테이너는 살아 있다 |
| Gradle/`docker build` 오류 | 코드 빌드 실패 | 코드 수정 후 재푸시. 기존 컨테이너는 살아 있다 |
| `health check failed after 60s` + compose 로그 | 새 컨테이너가 기동 실패(대개 `.env` 시크릿 누락, BY-642) | `.env` 반영 후 Run workflow |

워크플로가 서버 로그 없이 실패하면 AWS 쪽 문제다.

| 메시지 | 원인 | 대응 |
|---|---|---|
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 롤 신뢰 조건 불일치(브랜치가 `dev`가 아니거나 OIDC 공급자 없음) | 아래 "계정 준비" 3번 확인 |
| `InvalidInstanceId` | 인스턴스가 SSM에 등록돼 있지 않다 | `aws ssm describe-instance-information --profile focusdev`에 안 보이면 아래 "계정 준비" 1번. 서버에서 `sudo snap restart amazon-ssm-agent` |
| 30분 폴링 후 `TimedOut` | 빌드가 너무 오래 걸림 | 서버 상태 확인. t3.small 빌드는 보통 5~10분 |

## 계정 준비 (1회)

dev는 운영과 다른 AWS 계정(`325574368445`)이다. 로컬 프로파일 `focusdev`(루트 액세스 키)를 쓴다.
아래 스크립트는 멱등이라 다시 돌려도 안전하다. 2026-09-17에 처음 실행했다.

```bash
#!/usr/bin/env bash
# 1. EC2 SSM 롤 + 인스턴스 프로파일 → 인스턴스 연결  2. GitHub OIDC 공급자  3. github-deploy-dev 롤  4. GitHub 시크릿·변수
set -uo pipefail
export AWS_PROFILE=focusdev AWS_PAGER=""
R=ap-northeast-2; ACCT=325574368445; INST=i-09590430695b1e5bd
T=$(mktemp -d)

cat > "$T/ec2-trust.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
cat > "$T/gh-trust.json" <<EOF
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"arn:aws:iam::$ACCT:oidc-provider/token.actions.githubusercontent.com"},"Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com","token.actions.githubusercontent.com:sub":"repo:breathless-youth/backend:ref:refs/heads/dev"}}}]}
EOF
cat > "$T/gh-policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Sid":"RunShellOnDevInstance","Effect":"Allow","Action":"ssm:SendCommand","Resource":["arn:aws:ec2:$R:$ACCT:instance/$INST","arn:aws:ssm:$R::document/AWS-RunShellScript"]},
 {"Sid":"ReadInvocation","Effect":"Allow","Action":["ssm:GetCommandInvocation","ssm:ListCommandInvocations"],"Resource":"*"}]}
EOF

echo "### 1. EC2 SSM 롤 + 인스턴스 프로파일"
aws iam get-role --role-name focus-dev-ec2-ssm >/dev/null 2>&1 \
  || aws iam create-role --role-name focus-dev-ec2-ssm \
       --description "dev EC2가 SSM에 등록되기 위한 롤 (BY-670)" \
       --assume-role-policy-document "file://$T/ec2-trust.json" --query 'Role.Arn' --output text
aws iam attach-role-policy --role-name focus-dev-ec2-ssm \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam get-instance-profile --instance-profile-name focus-dev-ec2-ssm >/dev/null 2>&1 \
  || aws iam create-instance-profile --instance-profile-name focus-dev-ec2-ssm --query 'InstanceProfile.Arn' --output text
aws iam add-role-to-instance-profile --instance-profile-name focus-dev-ec2-ssm --role-name focus-dev-ec2-ssm 2>/dev/null || true
for i in 1 2 3 4 5 6; do
  if aws ec2 associate-iam-instance-profile --region "$R" --instance-id "$INST" \
       --iam-instance-profile Name=focus-dev-ec2-ssm --query 'IamInstanceProfileAssociation.State' --output text 2>"$T/assoc.err"; then
    break
  fi
  if grep -q IncorrectState "$T/assoc.err"; then echo "already associated"; break; fi
  echo "IAM 전파 대기 ($i/6)…"; sleep 10
done

echo "### 2. GitHub OIDC 공급자"
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd \
  --query 'OpenIDConnectProviderArn' --output text 2>&1 | grep -v EntityAlreadyExists || echo "provider already exists"

echo "### 3. github-deploy-dev 롤"
aws iam get-role --role-name github-deploy-dev >/dev/null 2>&1 \
  || aws iam create-role --role-name github-deploy-dev \
       --description "GitHub Actions(dev 브랜치)가 SSM으로 dev 서버에 배포 (BY-670)" \
       --assume-role-policy-document "file://$T/gh-trust.json" --query 'Role.Arn' --output text
aws iam put-role-policy --role-name github-deploy-dev --policy-name ssm-run-dev-deploy \
  --policy-document "file://$T/gh-policy.json"

echo "### 4. GitHub 시크릿·변수 (sangjaekwon 계정)"
gh secret set AWS_DEV_DEPLOY_ROLE_ARN --repo breathless-youth/backend --body "arn:aws:iam::$ACCT:role/github-deploy-dev"
gh variable set DEV_INSTANCE_ID --repo breathless-youth/backend --body "$INST"
rm -rf "$T"
```

확인:

```bash
aws ssm describe-instance-information --profile focusdev --region ap-northeast-2 \
  --query 'InstanceInformationList[].{id:InstanceId,ping:PingStatus}' --output table   # Online이어야 한다 (연결 후 수 분)
aws ssm send-command --profile focusdev --region ap-northeast-2 --instance-ids i-09590430695b1e5bd \
  --document-name AWS-RunShellScript --parameters 'commands=["echo hello from $(hostname)"]' \
  --query 'Command.CommandId' --output text                                            # 이어서 get-command-invocation로 stdout 확인
```

`focusdev` 프로파일이 없으면 `aws configure --profile focusdev`로 만든다(콘솔 → 보안 자격 증명 → 액세스 키).

## 범위 밖 (후속 후보)

- 이미지 기반 배포(ECR)로 전환 — 서버 빌드 부하가 문제될 때
- 보안그룹 22번 전체 개방 정리 — SSM이 붙은 뒤에는 SSH 없이 배포되므로 닫아도 된다
````

- [ ] **Step 2: 커밋**

```bash
git add docs/runbooks/dev-deploy.md
git commit -m "docs: dev 서버 배포 런북 — 사용법·실패 진단·계정 준비 CLI (BY-670)"
```

---

### Task 4: 검증 게이트 · PR

**Files:**
- 없음 (검증·PR)

- [ ] **Step 1: 전체 검증**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL` (Java 변경이 없어도 규칙대로 돌린다)

- [ ] **Step 2: 크로스 리뷰** — `/codex review` (P1 발견 시 수정 후 재리뷰)

- [ ] **Step 3: 퀴즈 게이트** — 워크플로·스크립트 흐름에 대한 퀴즈 5개를 사용자에게 내고 통과 확인 (CLAUDE.md 작업 규칙 7)

- [ ] **Step 4: push·PR**

```bash
git push -u origin "chore/BY-670-dev-서버-CD"
gh pr create --base dev --title "[chore] BY-670 dev 서버 CD — dev push 시 SSM으로 자동 배포" --body-file <(cat <<'EOF'
## 📌 관련 이슈
- BY-670

## ✨ 작업 내용
- `dev` push 시 GitHub Actions가 OIDC로 dev 계정 롤을 받아 SSM Run Command로 서버에서 `git pull --ff-only` → `docker compose up -d --build` → `/actuator/health` 확인을 실행한다. 서버 절차는 손으로 하던 것과 같다
- pull·빌드 실패 시 기존 컨테이너는 그대로 남고, health 실패(예: `.env` 시크릿 누락)는 워크플로 실패로 드러난다
- 런북 `docs/runbooks/dev-deploy.md`: 사용법·실패 진단·dev 계정 준비 CLI(멱등)
- 설계 스펙 `docs/superpowers/specs/2026-09-17-by670-by667-dev-cd-and-ws-spec-page-design.md` Part A

## 📸 스크린샷 / 테스트 결과
- 롤 신뢰 조건이 `dev` 브랜치라 머지 전에는 실행할 수 없다. 머지 후 workflow_dispatch로 첫 배포를 돌려 `healthy: <sha>`를 확인한다
- `./gradlew check` 통과 (Java 변경 없음)

## 🔍 리뷰 포인트
- `deploy-dev.sh`는 SSM이 `/bin/sh`(dash)로 실행한다 — bash 전용 문법이 없는지
- 롤 권한이 인스턴스 1대·문서 `AWS-RunShellScript` 하나로 한정된 점(런북 "계정 준비" 3번)

## ✅ 체크리스트
- [x] 커밋 메시지 컨벤션 준수
- [x] 로컬 빌드/테스트 성공
- [x] 불필요한 주석·console.log 제거
EOF
)
```

- [ ] **Step 5: CI 확인 후 머지** — 체크 런이 등록된 것을 본 뒤 `gh pr checks --watch`, 머지는 별도 단계(merge commit, 브랜치 유지)

---

### Task 5: 머지 후 실배포 검증 · 티켓 완료

**Files:**
- 없음

- [ ] **Step 1: 계정 준비 완료 확인**

Run:
```bash
aws ssm describe-instance-information --profile focusdev --region ap-northeast-2 --query 'InstanceInformationList[].PingStatus' --output text
gh variable list --repo breathless-youth/backend
```
Expected: `Online`, `DEV_INSTANCE_ID`

- [ ] **Step 2: 첫 배포** — Actions → Deploy dev → Run workflow(`dev`). 또는 `gh workflow run "Deploy dev" --ref dev`

Run: `gh run watch $(gh run list --workflow "Deploy dev" --limit 1 --json databaseId --jq '.[0].databaseId')`
Expected: 성공, server stdout에 `healthy: <sha>`

- [ ] **Step 3: 실패 경로 1회 확인 (스펙 A8)** — 서버 `.env`의 한 시크릿을 임시로 비우고 Run workflow → `health check failed`로 실패하는지 확인 → 복구 후 다시 Run workflow → 성공. dev 서버에 접속 중인 FE가 없을 때 한다.

- [ ] **Step 4: 티켓 완료** — BY-670 상태를 완료(전이 ID 41)로 전환. PR 링크를 댓글로 남긴다.
