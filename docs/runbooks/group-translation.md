# 그룹 자동 번역 운영 (BY-1191)

기본 상태는 off다. 키를 넣는 것만으로 켜지지 않으며, 공급자 장부의 initialized도 별도로 확인한다. 사용자 조건은 번역 비용 0원이다. 무료 잔량을 확인할 수 없는 공급자는 켜지 않는다.

## 설정

실제 키는 환경 변수나 gitignore된 application-local.yaml에만 둔다. 채팅·티켓·로그에 키를 붙이지 않는다. 아래 설정은 application-local.yaml.example에도 있다. 전역과 각 공급자 enabled가 모두 true일 때만 해당 어댑터를 만든다.

- 전역 `app.group-translation.enabled`: 기본 false.
- `google.enabled`, `azure.enabled`, `deepl.enabled`: 모두 기본 false (같은 prefix 아래).
- `GOOGLE_TRANSLATION_API_KEY`, `AZURE_TRANSLATION_API_KEY`, `AZURE_TRANSLATION_REGION` (기본 koreacentral), `DEEPL_TRANSLATION_API_KEY`.
- Google daily-limit=13000, monthly-limit=400000, Azure monthly-limit=2000000, DeepL lifetime-limit=1000000. 더 작게 설정할 수 있으며 승인된 최대값보다 높이면 기동을 거부한다.
- Azure plan=F0, DeepL plan=DEVELOPER만 허용한다. 실제 콘솔 요금제도 반드시 확인한다. 이 문자열 자체가 외부 계정 상태의 증거는 아니다.
- Google은 고정 NMT plain-text 경로, Azure는 고정 표준 텍스트 경로, DeepL은 api-free.deepl.com만 사용한다. 프론트가 endpoint/model/키를 지정할 수 없다.
- 환경변수 스위치는 `APP_GROUPTRANSLATION_ENABLED`, `APP_GROUPTRANSLATION_DEEPL_ENABLED`처럼 쓴다. 제한 값은 `APP_GROUPTRANSLATION_GOOGLE_DAILYLIMIT`, `APP_GROUPTRANSLATION_GOOGLE_MONTHLYLIMIT`, `APP_GROUPTRANSLATION_DEEPL_LIFETIMELIMIT`다. API 키 변수와 구분하며 실제 배포 바인딩도 테스트로 검증한다.
- DeepL을 켜면 기동 중 지원 언어만 최대 5초간 미리 조회한다. 이 준비 단계는 번역 요청을 보내지 않으며 실패해도 서버 기동을 막지 않는다. 실제 그룹 조회에서는 잔량 검증·선예약과 기존 공급자 2초·전체 3초 제한을 유지한다.

## 첫 활성화

1. 전역 off 상태에서 마이그레이션과 `./gradlew check`를 검증한다.
2. 공급자 전용 키를 서버에 주입한다. Google API 제한과 일일 콘솔 한도, Azure 실제 F0, DeepL 실제 Developer 무료 요금제를 확인한다. DeepL 100만 자는 평생 총량이며 매월 무료량이 아니다.
3. Google은 결제 계정의 다른 프로젝트 사용량도 함께 확인한다. 일반 NMT만 사용하는지, 다른 호출 경로가 같은 무료분을 소비하지 않는지 확인한다. Google 콘솔은 백엔드 검증 후에만 목표 15,000자/일로 변경한다. 이 문서는 콘솔 변경을 실행하지 않는다.
4. Google 오늘/이번 달, Azure 실제 무료 주기, DeepL 영구 누적 기사용량을 확인한다. DeepL은 애플리케이션의 영구 장부를 월간 usage 응답으로 감소시키지 않는다. 월간 수치만 확인 가능하고 기존 총 사용량을 모르면 DeepL은 off를 유지한다.
5. 아래 SQL로 확인한 기사용량을 증가 방향으로만 반영한다. 공급자들을 켠 채 실행하지 않는다. 각 공급자의 확인이 완료되지 않았다면 해당 공급자 초기화/활성화를 생략한다.
6. 확인된 공급자만 enabled=true로 설정한 뒤 전역을 켠다. Google → Azure → DeepL 순서는 고정이다. 첫 소량 요청에서 reservation·cache hit·fallback을 확인한다.

### 기사용량 반영 예시 (psql)

아래는 세 공급자 모두 확인했을 때의 초기화 예시다. 기사용량을 0으로 가정하지 않는다. psql 프롬프트에 확인한 실제 비음수 정수를 입력한다. 기존 장부보다 작은 값은 반영되지 않는다. 운영 중 장부가 유실됐다면 무료량을 새로 부여하지 말고 off 상태에서 증거를 복구한다.

```sql
\prompt 'Google 태평양 시간 오늘 기사용량: ' google_day_used
\prompt 'Google 무료분 공유 사용처 포함 이번 달 기사용량: ' google_month_used
\prompt 'Azure F0 현재 무료 주기 기사용량: ' azure_month_used
\prompt 'DeepL Developer 영구 누적 기사용량: ' deepl_lifetime_used
BEGIN;
SELECT provider FROM group_translation_provider_state ORDER BY provider FOR UPDATE;
INSERT INTO group_translation_usage(provider, period_type, period_key, reserved_characters)
VALUES
 ('GOOGLE','DAY',to_char(now() AT TIME ZONE 'America/Los_Angeles','YYYY-MM-DD'), :'google_day_used'::bigint),
 ('GOOGLE','MONTH',to_char(now() AT TIME ZONE 'America/Los_Angeles','YYYY-MM'), :'google_month_used'::bigint),
 ('AZURE','MONTH',to_char(now() AT TIME ZONE 'UTC','YYYY-MM'), :'azure_month_used'::bigint),
 ('DEEPL','LIFETIME','ALL', :'deepl_lifetime_used'::bigint)
ON CONFLICT (provider,period_type,period_key) DO UPDATE
SET reserved_characters = GREATEST(group_translation_usage.reserved_characters, EXCLUDED.reserved_characters);
UPDATE group_translation_provider_state SET initialized=true, suspended=false, blocked_until=NULL,
 day_key=to_char(now() AT TIME ZONE CASE WHEN provider='GOOGLE' THEN 'America/Los_Angeles' ELSE 'UTC' END,'YYYY-MM-DD'),
 month_key=to_char(now() AT TIME ZONE CASE WHEN provider='GOOGLE' THEN 'America/Los_Angeles' ELSE 'UTC' END,'YYYY-MM');
COMMIT;
```

Azure의 실제 무료 주기가 서버 UTC 달력과 다르면 F0 자체 한도가 먼저 차단할 수 있다. `403001` 이후 잔량 확인 전 자동으로 재개하지 않는다. DeepL `456`·인증 오류는 공급자 중지 상태로 저장한다. 원인을 확인한 뒤에만 해당 provider 행의 suspended/blocked_until을 해제한다. 이를 위해 무료 장부를 삭제하거나 감소시키지 않는다.

## 프론트 연결

기존 네 GET(`/api/groups/public`, `/api/groups/public/{id}`, `/api/groups/me`, `/api/groups/{id}`)에 선택한 앱 언어를 Accept-Language로 보낸다. 지원 API locale은 ko/en/ja/zh-TW다. FE는 기존 기기 언어로 표시 언어를 정하며, 미지원/간체 기기는 en을 보낸다. 이번에는 언어 선택 UI를 추가하지 않는다. 헤더가 없거나 지원하지 않으면 원문이다. 언어 q값이 잘못된 경우에도 원문이다.

기존 name/description은 원문이다. `translation`이 있을 때 필드별로 아래 순서를 적용한다.

```javascript
const title = group.translation?.name ?? group.name;
const description = group.translation?.description ?? group.description;
```

목록은 이름만 요청하며 소개는 상세에서 요청한다. 모든 공급자가 소진되거나 실패해도 GET은 원문으로 성공한다. 같은 원문/필드/언어의 성공 번역은 사용자끼리 공유하고 서버 재시작에도 유지된다. 수정 전 번역은 새 원문에 붙지 않는다. 대만·홍콩·마카오와 zh-Hant 별칭은 모두 zh-TW로 정규화해 캐시를 공유하며, 같은 표준 번체 모델을 사용하므로 광둥어 또는 홍콩 고유 표현을 보장하지 않는다. DeepL 영어는 현재 capability API에서 사용 가능한 EN-US를 대상으로 한다.

Swagger는 translation 객체와 name/description 각각의 null을 명시한다. 네 그룹 GET 응답에는 Vary: Accept-Language를 포함한다. dev Swagger 반영은 PR #159의 dev 머지 후 자동 배포 성공 시점이다.

## 관측과 롤백

- `group.translation.cache{result=hit}`: 성공 캐시 재사용 필드 수.
- `group.translation.reserved.characters{provider}`: 시도 전에 예약한 코드 포인트 수. 실패/timeout도 포함하고 반환하지 않는다.
- `group.translation.provider{provider,outcome}`: 실제 어댑터 시도 결과.
- `group.translation.fallback{language}`: 원문을 쓴 필드 수.
- 현재 사용량은 group_translation_usage, 중지·대기는 group_translation_provider_state에서 조회한다. 레코드에 키·사용자 ID·원문을 넣지 않는다.
- 즉시 롤백은 전역 enabled=false다. 캐시도 사용하지 않고 원문만 제공한다. 그룹 원문·권한·검색은 기존 동작을 유지한다.

## 실번역 품질 점검표

자동 테스트는 네트워크/한도/응답 계약 검증이며 실제 번역 품질 검증이 아니다. 아래 언어별 품질 점검은 아직 완료하지 않았다. 각 언어의 자연스러움은 해당 언어에 능숙한 사람이 검토한다.

### 2026-11-09 실연동 사전 점검 (BY-1203)

- Azure 실제 F0와 DeepL 실제 무료 API Developer를 확인했다. 기존 키는 gitignore 로컬 설정에만 저장했으며 운영에는 주입하지 않았다. Azure 문자 메트릭은 `--`로 표시되어 기사용량을 0자로 가정하지 않았다.
- Google 콘솔의 일반 NMT 일일 한도는 10,000자, 일괄 번역·LLM·맞춤 모델의 주요 할당량은 0으로 확인했다. 목표 15,000자/일은 아직 적용하지 않았다. dev·운영이 같은 프로젝트를 사용하면 Google의 일일 한도를 공유한다. 월 기사용량과 다른 무료분 사용처 확인은 남아 있다.
- 별도 로컬 DB에서 DeepL 사용량 API의 `0 / 1,000,000`을 확인하고 소량 그룹 GET을 실행했다. 기존 300ms 연결 제한의 TLS 시간 초과를 재현해 1.5초로 보정했다. 조회 전체 3초·공급자 시도 2초는 유지한다.
- 보정 뒤에도 실제 DeepL 응답은 2초 제한에 걸려 HTTP 200 원문으로 복귀했다. 01:20 KST 사용량 조회는 실제 소비 59자, 로컬 장부는 예약 269자다. 시간 초과로 예약한 문자를 환불하지 않았다. 성공 번역·성공 캐시·언어별 품질 검증은 미완료다.
- 점검 뒤 로컬·운영 번역은 off다. 무료 소진 시 원문 복귀 자동 테스트와 실제 연결 실패의 원문 복귀를 확인했으며, 이 결과만으로 운영 활성화 완료나 Google 청구 0원을 단정하지 않는다.
- 후속 진단에서 첫 잔량 조회 약 1.1초와 지원 언어 조회 약 0.7초가 같은 2초 예산을 소모함을 확인했다. 준비 조회를 먼저 마친 같은 어댑터로 실제 그룹 GET을 실행하자 ko/en/ja/zh-TW의 제목·소개가 모두 0.9~1.5초에 번역됐다. 숫자 6·50과 이모지, Python·시험 문맥 및 원문 보존을 확인했다. 이 결과는 백엔드 응답 확인이며 프론트 화면 확인과 별개다.

| 입력 종류 | 예시/기대 | ko | en | ja | zh-TW |
| --- | --- | --- | --- | --- | --- |
| 짧은 제목 | 새벽 공부방 — 공부 모임의 의미 | 미실행 | 미실행 | 미실행 | 미실행 |
| 공부 용어 | 수능·공시·순공, 문맥 의미 보존 | 미실행 | 미실행 | 미실행 | 미실행 |
| 숫자·이모지 | 매일 6시, 50분 공부 📚 — 숫자 보존 | 미실행 | 미실행 | 미실행 | 미실행 |
| 혼합 언어 | Python 알고리즘 함께 풀어요 | 미실행 | 미실행 | 미실행 | 미실행 |
| 긴 소개 | 500 grapheme 경계, 내용 누락/과장 없음 | 미실행 | 미실행 | 미실행 | 미실행 |

실행 전 예제 원문 코드 포인트 합 × 대상 언어 수 × 실제 시도 공급자 수를 상한으로 계산한다. 실제 앱의 인증된 그룹 GET으로 같은 장부/캐시 경로를 사용한다. 공급자 비교는 로컬 전용 테스트 그룹에서 검증된 공급자를 하나씩 설정해 수행하고, 매번 장부를 유지한다. 평가를 위한 직접 번역 API 호출로 장부를 우회하지 않는다. 평가 뒤 테스트 그룹/원문은 정리하되 소비된 장부는 남긴다.

## 공식 근거 (2026-11-08 확인)

- [Google NMT 요금](https://cloud.google.com/products/translate/pricing), [할당량](https://docs.cloud.google.com/translate/quotas), [Basic v2 번역 계약](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate)
- [Azure F0 요금](https://azure.microsoft.com/en-us/pricing/details/translator/), [표준 Translate 계약](https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/translate)
- [DeepL Developer 무료 총량](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans), [현재 언어 capability API](https://developers.deepl.com/docs/languages/using-the-languages-api)
