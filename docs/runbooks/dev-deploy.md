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
