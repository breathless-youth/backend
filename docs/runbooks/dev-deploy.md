# dev 서버 배포 런북

`dev` 브랜치에 push되면 `.github/workflows/deploy-dev.yml`이 dev EC2에 자동 배포한다(BY-670).
서버 절차는 손으로 하던 것과 같다: `/home/ubuntu/app-dev`에서 `git pull` → `docker compose up -d --build`.
다른 점은 GitHub Actions가 SSM Run Command로 그 절차를 실행하고, `/actuator/health`가 60초 안에
응답하지 않으면 실패로 표시한다는 것뿐이다. 배포에 SSH는 필요 없다. 워크플로에 보이는 서버 출력은 실패 시 빌드 로그의 마지막 120줄뿐이므로(SSM 인라인 출력 한도 stdout 24,000자·stderr 8,000자), 그 앞부분이 필요할 때만 서버의 `/home/ubuntu/deploy-dev.log`를 SSH로 본다.
보안 모델은 단순하다: `dev`에 push할 수 있는 사람은 dev EC2에서 root로 임의 명령을 실행할 수 있다(배포 스크립트가 push된 커밋에서 읽혀 root로 돈다). 롤은 인스턴스 1대·문서 1개로 좁혀 폭발 반경을 dev 서버(와 그 `.env`)로 한정한다.

운영(`main` → ECS)과는 계정도 워크플로도 다르다. 운영은 `deploy.yml`, 릴리스 기록은 `release.md`.

## 평소

- dev 머지 → Actions **Deploy dev** 실행이 초록이면 끝. 서버 stdout에 `healthy: <sha>`가 찍힌다.
- 다시 배포하고 싶으면 Actions → Deploy dev → **Run workflow** (`dev` 브랜치).
- 배포가 겹치면 뒤 실행은 큐에서 기다린다. 취소하지 않는다.
- 배포 겹침 방지는 두 겹이다: Actions concurrency 큐 + 서버 잠금(`/var/lock/app-dev-deploy.lock`). 잠금을 못 잡으면 `another deploy is still running` 으로 즉시 실패하니 앞 실행이 끝난 뒤 Run workflow.

## 실패했을 때

워크플로 로그의 "server stdout / server stderr" 그룹을 먼저 본다. 세 갈래다.
빌드 출력 전체는 서버 `/home/ubuntu/deploy-dev.log`(마지막 실행분만)에 있고, 워크플로에는 실패했을 때 그 끝 120줄이 stdout으로 실린다.

| 로그 | 원인 | 대응 |
|---|---|---|
| `fatal: Not possible to fast-forward` | 서버 `dev`가 origin과 갈라졌다(로컬 커밋 + 원격 진행) | SSH로 들어가 `git log --oneline -3`, 정리 후 재실행. 기존 컨테이너는 살아 있다 |
| `error: Your local changes to the following files would be overwritten by merge` | 서버에 추적 파일의 로컬 수정이 있다 | `git status --short`로 확인. compose 설정은 override 파일(아래 "서버 전제")로 빼고, 나머지는 stash/버림 |
| `server dev is ahead of origin/dev` | 서버에서 직접 커밋했다 | 그 커밋을 PR로 올리거나 `git reset --hard origin/dev`. 배포는 하지 않았다 |
| `compose project defines no app service` | 서버 override 파일이 없거나 이름이 바뀌었다 | 아래 "서버 전제" 확인. 배포는 하지 않았다 |
| `another deploy is still running` | 앞 배포가 아직 서버에서 실행 중 | 앞 실행이 끝난 뒤 Run workflow |
| `Permission denied (publickey)` 또는 `could not read Username for 'https://github.com'` | `ubuntu`에 비대화형 git 자격 증명이 없다 | 아래 "서버 전제" 3번 |
| `docker compose up --build failed` + 로그 끝 120줄 | 코드 빌드 실패 | 로그 끝에 원인이 없으면 서버 `deploy-dev.log`를 본다. 코드 수정 후 재푸시. 기존 컨테이너는 살아 있다 |
| `health check failed after 60s` + compose 로그 | 새 컨테이너가 기동 실패(대개 `.env` 시크릿 누락, BY-642) | **이 상태에서 dev는 새(깨진) 코드로 떠 있다.** 직전 이미지는 prune 전이라 `docker images`에 남아 있으니 급하면 그 이미지로 되돌리고, 아니면 `.env`/코드를 고쳐 Run workflow |

워크플로가 서버 로그 없이 실패하면 AWS 쪽 문제다.

| 메시지 | 원인 | 대응 |
|---|---|---|
| `DEV_INSTANCE_ID 저장소 변수가 비어 있다` | GitHub 변수 미설정 | 계정 준비 4번 |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 롤 신뢰 조건 불일치(브랜치가 `dev`가 아니거나 OIDC 공급자 없음) | 계정 준비 2·3번 확인 |
| `InvalidInstanceId` | 인스턴스가 SSM에 **한 번도** 등록된 적 없다 | 계정 준비 1번. 서버에서 `sudo snap restart amazon-ssm-agent` |
| `Pending`/`Delayed`에 오래 머물다 `TimedOut` (약 10분) | 등록은 됐지만 에이전트가 오프라인(중지·인스턴스 정지) | `aws ssm describe-instance-information --profile focusdev`의 `PingStatus` 확인, 에이전트 재시작 |
| 폴링 중 `AccessDenied`·`Throttling` 즉시 실패 | 롤 정책 누락 또는 API 한도 | 계정 준비 3번 정책 확인 후 재실행 |
| 약 40분 후 워크플로 실패 | 빌드가 실행 한도(30분)를 넘김 | 서버 상태 확인. t3.small 빌드는 보통 5~10분 |

## 서버 전제 (첫 배포 전 확인)

스크립트는 서버의 다음 상태를 전제한다. 첫 배포 전에 SSH로 들어가 한 번 확인한다.

1. **app 서비스는 리포 밖 override 파일이 정의한다.** 리포의 `docker-compose.yml`에는 postgres만 있다.
   `/home/ubuntu/app-dev`에서 `docker compose config --services`에 postgres 외 서비스가 보여야 하고, 그 서비스가 호스트
   8080을 publish해야 한다(`HEALTH_URL`). `git status --short docker-compose.yml`이 비어 있어야 한다 — 추적 파일을 고쳐 쓰고
   있다면 `docker-compose.override.yml`(untracked)로 옮긴다.
2. **`.env`** — `/home/ubuntu/app-dev/.env` (compose `env_file`). 시크릿 목록은 BY-642.
3. **비대화형 git 자격 증명** — SSM 실행에는 SSH agent forwarding이 없다. root 셸에서
   `sudo -u ubuntu -H git -C /home/ubuntu/app-dev ls-remote origin dev` 가 프롬프트 없이 성공해야 한다.
   실패하면 deploy key(`~ubuntu/.ssh`)나 `credential.helper store`를 심는다.
4. `flock`·`curl`·`jq 불필요` — Ubuntu 기본 이미지에 있는 `flock`(util-linux)과 `curl`만 쓴다.

## 계정 준비 (1회)

dev는 운영과 다른 AWS 계정(`325574368445`)이다. 로컬 프로파일 `focusdev`를 쓴다 — 현재는 루트 액세스 키다(임시. IAM 사용자나 Identity Center로 옮기고 루트 키는 삭제하는 것이 후속 과제, 아래 "범위 밖").
BY-670 머지 전에 한 번 실행한다. 멱등이라 다시 돌려도 안전하다. 파일로 저장해서 `bash setup-dev-aws.sh`로 실행한다 — 셸에 붙여넣으면 `set -e`와 `export AWS_PROFILE`이 현재 셸을 오염시킨다.

```bash
#!/usr/bin/env bash
# 1. EC2 SSM 롤 + 인스턴스 프로파일 → 인스턴스 연결  2. GitHub OIDC 공급자  3. github-deploy-dev 롤  4. GitHub 시크릿·변수
# 멱등: 있으면 건너뛴다. 오류는 삼키지 않는다(set -e) — 실패한 줄에서 멈춘다
set -euo pipefail
export AWS_PROFILE=focusdev AWS_PAGER=""
R=ap-northeast-2; ACCT=325574368445; INST=i-09590430695b1e5bd
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

cat > "$T/ec2-trust.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
cat > "$T/gh-trust.json" <<EOF
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"arn:aws:iam::$ACCT:oidc-provider/token.actions.githubusercontent.com"},"Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com","token.actions.githubusercontent.com:sub":"repo:breathless-youth/backend:ref:refs/heads/dev"}}}]}
EOF
cat > "$T/gh-policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Sid":"RunShellOnDevInstance","Effect":"Allow","Action":"ssm:SendCommand","Resource":["arn:aws:ec2:$R:$ACCT:instance/$INST","arn:aws:ssm:$R::document/AWS-RunShellScript"]},
 {"Sid":"ReadInvocation","Effect":"Allow","Action":"ssm:GetCommandInvocation","Resource":"*"}]}
EOF

echo "### 1. EC2 SSM 롤 + 인스턴스 프로파일"
# IAM description은 ASCII/Latin-1만 허용한다 — 한글을 넣으면 CreateRole이 거부한다
aws iam get-role --role-name focus-dev-ec2-ssm >/dev/null 2>&1 \
  || aws iam create-role --role-name focus-dev-ec2-ssm \
       --description "SSM registration for dev EC2 (BY-670)" \
       --assume-role-policy-document "file://$T/ec2-trust.json" --query 'Role.Arn' --output text
aws iam attach-role-policy --role-name focus-dev-ec2-ssm \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam get-instance-profile --instance-profile-name focus-dev-ec2-ssm >/dev/null 2>&1 \
  || aws iam create-instance-profile --instance-profile-name focus-dev-ec2-ssm --query 'InstanceProfile.Arn' --output text
if ! aws iam get-instance-profile --instance-profile-name focus-dev-ec2-ssm \
       --query 'InstanceProfile.Roles[].RoleName' --output text | grep -qw focus-dev-ec2-ssm; then
  aws iam add-role-to-instance-profile --instance-profile-name focus-dev-ec2-ssm --role-name focus-dev-ec2-ssm
fi
associated=0
for i in 1 2 3 4 5 6; do
  if aws ec2 associate-iam-instance-profile --region "$R" --instance-id "$INST" \
       --iam-instance-profile Name=focus-dev-ec2-ssm --query 'IamInstanceProfileAssociation.State' --output text 2>"$T/assoc.err"; then
    associated=1; break
  fi
  if grep -q IncorrectState "$T/assoc.err"; then echo "already associated"; associated=1; break; fi
  echo "IAM 전파 대기 ($i/6)…"; sleep 10
done
[ "$associated" = 1 ] || { cat "$T/assoc.err"; exit 1; }

echo "### 2. GitHub OIDC 공급자"
if ! aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text \
       | grep -q token.actions.githubusercontent.com; then
  aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
    --client-id-list sts.amazonaws.com \
    --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd \
    --query 'OpenIDConnectProviderArn' --output text
else
  echo "provider already exists"
fi

echo "### 3. github-deploy-dev 롤"
aws iam get-role --role-name github-deploy-dev >/dev/null 2>&1 \
  || aws iam create-role --role-name github-deploy-dev \
       --description "GitHub Actions dev-branch deploy via SSM (BY-670)" \
       --assume-role-policy-document "file://$T/gh-trust.json" --query 'Role.Arn' --output text
aws iam put-role-policy --role-name github-deploy-dev --policy-name ssm-run-dev-deploy \
  --policy-document "file://$T/gh-policy.json"

echo "### 4. GitHub 시크릿·변수 (sangjaekwon 계정)"
gh secret set AWS_DEV_DEPLOY_ROLE_ARN --repo breathless-youth/backend --body "arn:aws:iam::$ACCT:role/github-deploy-dev"
gh variable set DEV_INSTANCE_ID --repo breathless-youth/backend --body "$INST"
echo "done: $(aws iam get-role --role-name github-deploy-dev --query 'Role.Arn' --output text)"
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
- 루트 액세스 키 → IAM 사용자 또는 Identity Center로 전환하고 루트 키 삭제
- 서버 compose override 파일을 리포로 가져오기(`docker-compose.dev.yml`) — 지금은 서버 상태에 의존한다
