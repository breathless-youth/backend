# 릴리스 런북

GitHub Release를 만들 때 사용한다. `main`의 릴리스 커밋에 annotated 태그를 푸시하면
`.github/workflows/release.yml`이 태그 메시지를 요약으로 맨 위에 넣고, 그 아래에 직전 릴리스
이후 머지된 PR 목록을 자동으로 채워 릴리스를 만든다.

배포와는 무관하다. 운영 배포는 `main`에 push될 때(`deploy.yml`) 이미 끝나 있고, Sentry 릴리스
식별자도 커밋 SHA다. 태그·릴리스는 "그 배포에 무엇이 나갔는지"를 사람이 읽을 수 있게 남기는 기록이다.

## 절차

1. 릴리스 PR(`[chore] vX.Y.Z 릴리즈: dev → main`)을 머지한다. 머지가 곧 운영 배포다.
2. 그 머지 커밋에 annotated 태그를 만든다. 로컬 `main`은 낡아 있을 수 있으니 항상 `origin/main`을 기준으로 한다.
   태그 메시지가 릴리스 노트의 요약이 되므로 아래 작성 규칙대로 쓴다.

```bash
git fetch origin main
git tag -a v1.3.0 origin/main -m "v1.3.0: 비회원 토큰 인증 도입, 누적 공부 일수 API

- 로그인 없이도 기기별 토큰으로 안전하게 API를 쓸 수 있습니다 (BY-526)
- 기록 화면에 누적 공부 일수를 보여줄 수 있는 API가 추가됐습니다 (BY-645)"
git push origin v1.3.0
```

3. 이후 자동으로 진행된다.
   - Actions의 **Release** 워크플로가 태그 커밋이 `main`에 있는지 확인한다.
   - GitHub Releases에 릴리스가 생기고, 본문은 태그 메시지 + 자동 PR 목록("새 기능 / 버그 수정 / 설정·문서·기타")으로 채워진다.
   - 분류는 PR 라벨 기준이고, 라벨은 `pr-label.yml`이 PR 제목 접두어(`[feat]` 등)에서 자동으로 붙인다.

결과는 저장소 **Releases** 페이지에서 확인한다.

## 요약(태그 메시지) 작성 규칙

독자는 비개발 팀원과 회고하는 미래의 나다. PR 제목 목록은 아래에 자동으로 붙으니 요약은 그것을
사람 말로 풀어 주는 역할만 한다.

- 첫 줄: `vX.Y.Z: ` 뒤에 핵심 변경 2~4개를 쉼표로 이은 한 줄 제목
- 빈 줄 하나 뒤, 티켓별로 한 줄씩. "무엇이 가능해졌는지 / 무엇이 고쳐졌는지"를 사용자 관점으로 쓰고 끝에 `(BY-N)`
- 구현 방식(테이블명·클래스명·라이브러리)은 쓰지 않는다. 그건 PR과 ADR에 있다
- 핫픽스처럼 변경이 하나뿐이면 첫 줄만으로 충분하다 (예: `v1.2.1: 닉네임에 이모지·띄어쓰기 허용 (BY-647)`)

## 주의

- 태그는 **한 번에 하나씩, 버전 순서대로** 푸시한다. 한 번에 4개 이상 푸시하면 GitHub이 push 이벤트를
  만들지 않아 릴리스가 조용히 생성되지 않고, 순서를 건너뛰면 릴리스 노트의 "직전 릴리스" 비교 기준이 어긋난다.
- 태그 커밋이 `main`에 없으면 워크플로가 실패한다. `dev`나 기능 브랜치에서 태그하지 않는다.
- 핫픽스도 같은 절차다: `origin/main`에서 분기 → `main` 대상 PR 머지 → 그 머지 커밋에 patch 태그 →
  `dev`에 `git merge --no-ff origin/main`으로 역머지.

## 배포 중 Gradle 다운로드 실패 (BY-1217)

증상: `Deploy to ECS`(또는 dev 배포)의 `Build and push image` 단계가 아래처럼 실패한다.

```
Downloading https://services.gradle.org/distributions/gradle-9.5.1-bin.zip
Attempt 1/1 failed. Reason: Server returned HTTP response code: 504
ERROR: process "/bin/sh -c ./gradlew --no-daemon bootJar -x test" did not complete successfully
```

원인: 이미지 빌드는 캐시 없는 새 러너에서 매번 Gradle 배포판을 받는다. services.gradle.org가 간헐적으로
504를 내는데 래퍼 재시도가 꺼져 있어(`retries=0`) 한 번에 실패했다. v2.14.0 배포가 이 때문에 두 번 실패했다.

해결: `gradle/wrapper/gradle-wrapper.properties`에 `retries=5`, `retryBackOffMs=5000`을 둔다. 대기는 시도마다
2배라 5·10·20·40·80초, 최대 6번 시도한다(재시도 사이 대기만 합쳐 약 2분 35초, 각 다운로드 시간은 별도). `GradleWrapperRetryTest`가 이 값을 지킨다.

- 래퍼를 올릴 때(`./gradlew wrapper --gradle-version …`) 이 두 줄이 기본값으로 돌아가면 테스트가 실패한다. 다시 넣는다.
- 그래도 실패하면 Gradle 서버 장애가 길어진 것이다. [status.gradle.com](https://status.gradle.com)을 확인하고 복구 뒤
  Actions에서 **Re-run failed jobs**로 다시 돌린다. 머지는 이미 끝났으므로 코드를 다시 올릴 필요는 없다.
- 반복되면 후속으로 이미지 빌드에 buildx GitHub Actions 캐시를 붙여 배포판 다운로드 자체를 줄인다.

## 잘못 만든 릴리스 되돌리기

반드시 이 순서대로 (릴리스를 지우기 전에 태그를 다시 푸시하면 남은 릴리스와 충돌한다):

```bash
gh release delete v1.3.0 --yes            # 1. 릴리스 삭제 (Releases 페이지에서 해도 된다)
git push --delete origin v1.3.0           # 2. 원격 태그 삭제
git tag -d v1.3.0                         # 3. 로컬 태그 삭제
git tag -a v1.3.0 <올바른-커밋-SHA> -m "..." && git push origin v1.3.0   # 4. 대상 커밋을 명시해 다시 태그
```

## 버전 규칙

- **태그가 버전의 단일 출처다.** `build.gradle`의 `version`은 태그를 따라간다. `main`에 머지하는 PR(릴리스·핫픽스)에는
  `version`을 그 태그로 올리는 커밋을 포함하고 PR 본문에 `vA.B.C → vX.Y.Z`를 적는다. 기능 브랜치에서는 바꾸지 않는다
  (v2.1.0부터 적용, 그 전에는 `v1.2.0`에 머물러 있었다).
- BE 독립 semver (`vMAJOR.MINOR.PATCH`). 프론트·디자인 버전과 번호를 맞추지 않는다. 호환성은 API 계약(ADR-0015)으로 관리한다.
- 기능·API 추가 = minor, 버그 수정·핫픽스 = patch, 클라이언트가 대응해야 하는 API 계약 변경 = major.
