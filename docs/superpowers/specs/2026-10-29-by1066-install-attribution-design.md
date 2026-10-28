# 설치 정보 해석과 사용자별 첫 유입 경로 저장 (BY-1066)

- 작성일: 2026-10-29
- 상위 작업: [BY-1065](https://breathless-youth.atlassian.net/browse/BY-1065) 광고·초대 유입 경로를 사용자별로 기록
- 서브태스크: [BE] BY-1066 (이 문서) · 짝 [FE] BY-1068 · 키 전달 [PROMO] BY-1067
- 상태: 구현 완료(2026-10-29)
- 선행 설계: ADR-0015·0020(API-Version), ADR-0019(게스트 기기 토큰 인증), ADR-0035(키가 없으면 그 기능만 끄고 기동)

## 1. 배경

메타 영상 광고 10개(iOS 5·Android 5)를 집행하기 시작했고, 광고별로 끝까지 남는 사용자를 비교하려고 사용자마다 처음 들어온 경로를 남긴다.
Android 메타 광고 정보는 플레이 설치 정보(Install Referrer) 안에 암호화돼 오고, 복호화 키를 앱에 넣으면 앱을 뜯어 꺼낼 수 있어서 서버가 푼다.
iOS는 설치 정보가 없어서 메타 지연 딥링크의 `fm_ad` 값(`ios_v1`~`ios_v5`)으로만 광고를 안다.

앱(BY-1068)은 설치 정보 원문과 딥링크 값을 읽어 로그인 뒤 한 번 보내고, 실패하면 다음 실행에서 다시 보낸다. 서버가 돌려준 결과로 Amplitude 사용자 속성을 한 번 저장한다.

## 2. 티켓과 달라진 점 (메타 문서로 확인)

1. **메타 광고 판별에 `utm_source`를 쓰지 않는다.** 메타 문서가 "암호화된 캠페인 메타데이터 밖의 매개변수(`utm_source` 등)는 들어 있지 않을 수
   있으니 메타 설치의 지표로 쓰지 말라"고 적고 있다. `utm_content` JSON에 `source.data`와 `source.nonce`가 있으면 메타 광고로 본다.
2. **복호화 결과의 필드 이름이 광고 관리자 계층과 한 단계씩 어긋난다.** 마케팅 API의 옛 이름을 그대로 쓰기 때문이다(Adjust 매핑표로 확인).

   | 복호화 결과 필드 | 광고 관리자 계층 | 저장 컬럼 · 응답 필드 |
   |---|---|---|
   | `campaign_group_id` · `campaign_group_name` | 캠페인 | `meta_campaign_*` · `campaignId` |
   | `campaign_id` · `campaign_name` | 광고 세트 | `meta_adset_*` · `adsetId` |
   | `adgroup_id` · `adgroup_name` | 광고 | `meta_ad_*` · `adId` |

   메타 예시에서 `ad_id`(12345)는 `adgroup_id`(54321)와 값이 다르고, 어느 계층인지 문서에 없다. 그래서 복호화 결과 JSON 전체를
   `meta_payload`에 남기고, 광고 ID는 Adjust 매핑대로 `adgroup_id`를 쓴다. 첫 실데이터가 들어오면 광고 관리자의 광고 ID와 대조한다.

## 3. 복호화

메타 문서의 방식과 예시 값(키·nonce·암호문)을 JDK `javax.crypto`로 직접 풀어 확인했다. 새 의존성은 없다.

- 알고리즘은 AES-256-GCM이고 AAD는 없다. 태그는 128비트다.
- 키는 hex 64자(32바이트), nonce는 hex 24자(12바이트), 암호문은 hex이고 끝에 16바이트 태그가 붙어 있다.
- 이 배치(본문 뒤 태그)는 JDK `AES/GCM/NoPadding`이 받는 입력 형식과 같아서, hex를 바이트로 바꿔 그대로 넣으면 된다.
- 풀린 값은 UTF-8 JSON이다. 예시: `{"ad_id":"12345","ad_objective_name":"APP_INSTALLS","adgroup_id":"54321","adgroup_name":"Adams Test Ad Group","campaign_id":"987654","campaign_name":"Adams Test Campaign","campaign_group_id":"654321","campaign_group_name":"Adams Test Campaign Group","account_id":"444444"}`
- 메타 문서의 예시 키는 공개된 값이라 테스트 벡터로 그대로 쓴다.

## 4. 유입 경로 판별

`installReferrer`는 URL 쿼리 문자열(`a=b&c=d`, 값은 퍼센트 인코딩)이다. 위에서부터 처음 맞는 규칙 하나로 정한다.

| 순서 | 조건 | `source` | 저장하는 값 |
|---|---|---|---|
| 1 | `utm_content`가 JSON이고 `source.data`·`source.nonce`가 있다 | `META_AD` | 복호화한 ID·이름, `meta_payload`, 딥링크 라벨 |
| 2 | `code`가 숫자 4자리다 | `INVITE` | `invite_code` |
| 3 | `deepLinkAd`가 있다 | `META_AD` | 딥링크 라벨만 |
| 4 | `utm_source=google-play`이고 `utm_medium=organic`이다 | `ORGANIC` | — |
| 5 | 설치 정보가 비어 있지 않다 | `OTHER_LINK` | — |
| 6 | 둘 다 없다 | `UNKNOWN` | — |

- `utm_source`·`utm_medium`·`utm_campaign`은 `source`와 상관없이 있으면 항상 저장한다. 분석 때 다시 나눌 수 있게 하려는 것이다.
- 2번의 초대코드 규칙은 앱의 `installReferrerInvite.ts`(`code`가 `^\d{4}$`)와 같다. 앞자리 0이 있어 문자열로 저장한다.
- 친구 초대(2)를 딥링크(3)보다 먼저 본다. 플레이 설치 정보는 스토어로 들어가기 직전에 누른 링크이고, 메타 딥링크는 메타가 자기 기준 기간 안에서
  붙인 값이라서 마지막 클릭인 초대 링크가 더 정확하다.
- 딥링크(3)를 오가닉(4)보다 먼저 본다. 메타 광고를 누른 뒤 플레이에서 직접 검색해 설치하면 설치 정보는 오가닉이고 딥링크만 광고를 가리킨다.
- `utm_source=(not set)`처럼 플레이가 값을 비워 보내는 경우도 5번 `OTHER_LINK`로 간다. 원문이 남아 있어서 나중에 SQL로 다시 나눌 수 있다.
- 딥링크 라벨은 1번에서도 함께 저장한다. Android 메타 광고면 암호문과 딥링크가 둘 다 오기 때문이다.

## 5. 실패 처리

- 메타 암호문이 있는데 풀지 못하면 `META_AD`로 저장하고 ID·이름·`meta_payload`는 비워 둔다. 요청은 성공(200)으로 처리한다. 풀지 못하는 경우는
  키가 없거나, 키 형식이 틀렸거나, 태그 검증이 실패했거나, 풀린 값이 JSON이 아닐 때다.
- 실패는 WARN 로그 한 줄로 남긴다. 예외 메시지에 입력 일부가 섞일 수 있어 예외 종류만 남긴다(`FcmConfig`와 같은 판단). userId는 MDC로 붙는다.
- 키가 비어 있거나 hex 64자가 아니면 기동할 때 WARN 한 줄을 남기고 복호화만 끈 채 뜬다. 키 하나 때문에 API 전체가 뜨지 못하는 쪽이 더
  나쁘다(ADR-0035와 같은 판단).
- 키 없이 저장된 행은 첫 값을 덮어쓰지 않는 규칙 때문에 나중 요청으로 채워지지 않는다. 그래서 **운영 배포 전에 키를 먼저 넣는다.**
  놓친 행은 원문이 남아 있어 일회성 작업으로 다시 풀 수 있고, 그 작업은 이번 범위 밖이다.

## 6. 키 관리

- 프로퍼티 `app.acquisition.meta-install-referrer-key`. prod·dev yaml에는 `${META_INSTALL_REFERRER_KEY:}`(빈 기본값)를 두고,
  `application-local.yaml.example`에는 자리표시자만 둔다.
- prod는 ECS 태스크 정의가 SSM 파라미터를 주입하고, dev는 서버 `.env`에 넣는다. 둘 다 사용자가 반영한다(infra 저장소·dev 서버).
- 키는 응답, 로그, 예외 메시지 어디에도 남기지 않는다. 키를 쥔 객체는 `toString`을 만들지 않는다.
- 요청 바디는 지금도 로그에 남지 않는다(`RequestLoggingFilter`는 액세스 로그만 찍는다). 설치 정보 원문은 암호문이라 DB에 남겨도 된다.

## 7. 저장

새 테이블 `user_acquisition`을 둔다(`V33__user_acquisition.sql`). 사용자당 한 행이고, 행이 있다는 것 자체가 "이미 저장됨"이다.
`users`에 컬럼을 더하지 않는 이유는 분석용 nullable 컬럼 열댓 개가 모든 사용자 조회에 딸려 오지 않게 하려는 것이다.

| 컬럼 | 쓰임 |
|---|---|
| `user_id` | PK이자 `users` FK(삭제 연쇄 없음, `push_token`과 같다) |
| `platform` | `IOS` · `ANDROID` |
| `source` | `META_AD` · `INVITE` · `ORGANIC` · `OTHER_LINK` · `UNKNOWN` |
| `install_referrer` | 설치 정보 원문 (text) |
| `deep_link_ad` | 딥링크 `fm_ad` 원문 |
| `utm_source` · `utm_medium` · `utm_campaign` | 설치 정보에서 꺼낸 값 |
| `invite_code` | 초대코드 4자리 |
| `meta_campaign_id` · `meta_campaign_name` | 캠페인 (`campaign_group_*`) |
| `meta_adset_id` · `meta_adset_name` | 광고 세트 (`campaign_*`) |
| `meta_ad_id` · `meta_ad_name` | 광고 (`adgroup_*`) |
| `meta_payload` | 복호화 결과 JSON 전체 (jsonb) |
| `captured_at` | 앱이 값을 읽은 시각 |
| `created_at` | 서버가 처음 저장한 시각 |

- 메타 ID는 18자리라 JS `number`의 정밀도(2^53)를 넘는다. DB와 응답 모두 문자열로 다룬다.
- 저장은 `INSERT … ON CONFLICT (user_id) DO NOTHING` 한 문장이고, 그 뒤 행을 읽어 돌려준다. 같은 사용자의 요청이 동시에 와도 먼저
  들어간 행이 남고, 두 요청 모두 같은 응답을 받는다. 판별과 복호화는 순수 계산이라 INSERT 전에 한다.
- 두 번째 요청의 값은 버린다. 첫 값과 달라도 로그를 남기지 않는다.
- `docs/erd.dbml`에 테이블을 추가한다.

## 8. API

```
POST /api/users/me/acquisition        API-Version: 1, 토큰 인증 필요
{
  "platform": "ANDROID",
  "installReferrer": "utm_source=apps.facebook.com&utm_campaign=fb4a&utm_content=%7B%22app%22%3A…%7D",
  "deepLinkAd": "aos_v3",
  "capturedAt": "2026-10-29T10:12:00+09:00"
}
→ 200
{
  "source": "META_AD",
  "metaAd": { "campaignId": "654321", "adsetId": "987654", "adId": "54321", "label": "aos_v3" },
  "inviteCode": null,
  "firstTouchAt": "2026-10-29T01:12:00Z"
}
```

- 구 앱 대응이 없는 새 경로라 `version = "1"`을 명시한다(ADR-0015 갱신 규칙).
- 인증은 지금 설정(`anyRequest().authenticated()`)이 그대로 막는다. Security 설정은 바꾸지 않는다.
- 처음이든 다시 보낸 것이든 응답 코드는 200이고, 처음 저장한 값을 돌려준다.
- `metaAd`는 `source`가 `META_AD`일 때만 있고, 그 안의 값도 모르면 null이다(iOS는 `label`만, 복호화 실패면 `label`만 또는 전부 null).
- `firstTouchAt`은 처음 저장한 행의 `captured_at`이다. 설치 직후 앱이 값을 읽은 시각이라 서버 저장 시각보다 설치 시각에 가깝다.
- `firstTouchAt`은 UTC(`Z`)로 내려간다. `timestamptz`가 오프셋을 보관하지 않아서, 요청의 `+09:00`은 같은 순간의 UTC로 바뀐다.
- 400: `platform` 누락이나 `IOS`·`ANDROID` 밖의 값, `capturedAt` 누락, `installReferrer` 4096자 초과, `deepLinkAd` 64자 초과.
- `deepLinkAd` 형식(`ios_v1` 등)은 서버가 검사하지 않는다. 앱이 이미 거르고, 서버가 400으로 막으면 앱이 실행할 때마다 다시 보내게 된다.

## 9. 코드 배치

새 도메인 패키지 `project.study.acquisition`.

- `controller/AcquisitionController` — Swagger 설명 포함.
- `dto/AcquisitionRequest`, `dto/AcquisitionResponse`(+ `MetaAd`) — record.
- `entity/UserAcquisition`, `entity/AcquisitionSource`, `entity/InstallPlatform` — 플랫폼 enum은 `push`의 것을 가져다 쓰지 않고 따로 둔다(도메인 간 직접 참조 최소화).
- `repository/UserAcquisitionRepository` — `JpaRepository`에 `UserAcquisitionInsert` 조각을 합친 인터페이스. `UserAcquisitionInsertImpl`이 `JdbcClient`로 `ON CONFLICT DO NOTHING` 삽입을 하고, 넘기는 값은 `NewAcquisition` record에 담는다.
- `service/AcquisitionService` — 판별·복호화를 묶어 저장하고 응답을 만든다.
- `referrer/InstallReferrerClassifier` — 쿼리 문자열과 `utm_content` JSON을 읽어 판별 순서대로 `Attribution`을 만든다(Jackson 3).
- `referrer/Attribution`, `referrer/Utm`, `referrer/MetaAdDetail` — 판별 결과와 그 안의 값을 담는 record.
- `referrer/MetaReferrerDecryptor` — 키를 쥐고 AES-GCM으로 푼다. 키 검증과 기동 WARN도 여기서 한다.

## 10. 테스트

- 단위: `MetaReferrerDecryptor` — 메타 예시 벡터가 풀린다 · 틀린 키면 실패 · hex가 깨지면 실패 · 키가 없으면 꺼진다.
- 단위: 판별 — 표의 6가지와 순서(초대 > 딥링크, 딥링크 > 오가닉, 메타 암호문 + 딥링크), `(not set)`, 퍼센트 인코딩, `utm_content`가 JSON이 아닐 때.
- 통합(MockMvcTester + Testcontainers): 다섯 `source`가 각각 저장된다 · 두 번째 요청이 첫 값을 바꾸지 않는다 · 복호화 실패도 200이고 원문이
  남는다 · 응답과 로그(`OutputCaptureExtension`)에 키가 없다 · 400 검증 · `API-Version: 1`.

## 11. 범위 밖

- 키 없이 저장된 행을 나중에 다시 푸는 일회성 작업.
- Amplitude 사용자 속성 저장, 리텐션 차트(BY-1068).
- iOS 친구 초대 판정(기존 방 입장 기록으로 분석 때 한다).
- 게시된 개인정보처리방침에 "서비스 유입 경로" 문구가 들어갔는지 확인 — 코드 작업이 아니라 사용자가 확인한다.

## 12. 결과로 생기는 것

- 앱 업데이트가 깔리면 기존 사용자도 한 번씩 보낸다. 이들의 행은 플레이가 기억하는 설치 정보(설치 후 90일)이거나 `UNKNOWN`이다. 광고 분석은
  `users.created_at`이 광고 집행 시작 뒤인 사용자로 거른다.
- 게스트는 앱을 다시 설치하면 새 사용자가 되므로 새 행이 생긴다.
- 배포 순서: 키를 dev `.env`·운영 SSM에 넣고 → 배포 → FE(BY-1068) 앱 업데이트 제출.
