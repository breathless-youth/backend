# BY-1191 FE 연동 계약: 기기 언어에 따른 그룹 자동 번역

연관 백엔드: [BY-1191](https://breathless-youth.atlassian.net/browse/BY-1191), [PR #159](https://github.com/breathless-youth/backend/pull/159).

## 이번 FE 범위

이번에는 설정 화면 언어 선택을 넣지 않는다. FE가 공유한 현재 정책대로 기기 언어에서 화면 언어를 정하고, 화면에 보이는 언어를 그룹 조회 요청에 보낸다. 모바일 탭바·다른 탭의 언어 전환과 앱 업데이트는 이번 범위 밖이다.

| 현재 표시 언어 | Accept-Language |
| --- | --- |
| 한국어 | `ko` |
| 영어 (미지원 기기 언어 포함) | `en` |
| 일본어 | `ja` |
| 번체 중국어 (대만·홍콩·마카오 및 번체 지정) | `zh-TW` |

FE의 번체 리소스 코드 `zh-Hant`는 요청할 때 `zh-TW`로 매핑한다. 간체 중국어 기기는 현재 화면이 영어이므로 `en`을 보낸다. `zh-CN`, `system`은 보내지 않는다. 기존 Authorization과 API-Version 헤더는 유지한다.

백엔드는 `zh-Hant`, `zh-HK`, `zh-MO`와 각 번체 script/region 별칭을 모두 `zh-TW`로 정규화한다. 이들은 같은 번역 캐시를 공유하며 응답 `translation.language`도 `zh-TW`다. 광둥어를 별도 제공하는 계약은 아니다.

## 요청과 표시

```http
API-Version: 1
Accept-Language: zh-TW
```

| API | 번역 필드 위치 | 대상 |
| --- | --- | --- |
| `GET /api/groups/public` | `groups[].translation` | 이름 |
| `GET /api/groups/me` | `groups[].translation` | 이름 |
| `GET /api/groups/public/{groupId}` | `translation` | 이름·소개 |
| `GET /api/groups/{groupId}` | `translation` | 이름·소개 |

기존 name/description은 원문이며 그룹 수정 화면은 이 원문을 사용한다.

```javascript
const displayName = group.translation?.name ?? group.name;
const displayDescription = group.translation?.description ?? group.description;
```

`translation` 객체, 그 안의 `name`, `description`은 **각각 null일 수 있다**. 객체가 있을 때 `language`는 ko/en/ja/zh-TW 중 하나다. 예를 들어 이름만 번역되거나 소개만 번역될 수 있으므로 필드별 원문 대체가 필요하다. 두 번역이 모두 없으면 객체 전체가 null이다. 목록은 소개를 번역하지 않는다.

무료량 소진·시간 초과·공급자 장애·다른 요청의 처리 중에도 원문으로 정상 응답한다. translation이 비어 있다는 이유로 오류나 로딩을 표시하지 않는다. 번역문은 일반 텍스트로 표시한다.

## 캐시

- FE 조회 캐시 키에 요청 언어를 포함하고 기존 사용자·그룹·필터 구분을 유지한다. 다른 언어의 응답을 섞지 않는다.
- 네 그룹 GET 응답은 `Vary: Accept-Language`를 포함한다. 기존 인증·캐시 제어를 유지하며, 이 헤더가 공용 HTTP 캐시 허용을 의미하지는 않는다.
- 번체 지역 별칭은 백엔드의 같은 캐시/예약량을 재사용한다. FE에서 공급자 API를 직접 호출하거나 전 언어를 미리 조회하지 않는다.

## Swagger와 활성화 상태

Swagger의 네 응답 DTO에 nullable translation을 싣고, GroupTranslation.name/description의 null 및 language의 네 값을 명시한다. 생성된 `/v3/api-docs` 응답을 테스트해 타입 계약을 검증한다.

이 문서를 작성할 때 PR #159는 dev 미머지 상태다. **PR을 dev에 머지한 뒤 자동 dev 배포가 성공해야 dev Swagger에 반영된다.** 실제 배포 확인 없이 반영되었다고 안내하지 않는다.

번역 기능·공급자는 기본 off다. 키 주입·무료 잔량 확인·장부 초기화·활성화 전에는 원문이 정상 응답이다. 실계정 번역 및 사람에 의한 품질 평가는 아직 실행하지 않았다. 무료 확인 전에는 켜지 않는다.

## 확인 목록

- 네 표시 언어와 네 조회 경로의 번역/원문 표시.
- translation 전체 null, name만 null, description만 null일 때 필드별 원문 대체.
- 대만·홍콩·마카오가 같은 zh-TW 결과를 재사용.
- 간체/미지원 기기 언어는 FE의 영어 화면에 맞춰 en 전송.
- 언어별 FE 캐시 분리, 그룹 수정 화면의 원문 유지.
- 생성된 Swagger의 nullable 타입과 네 GET의 Vary 헤더 확인.
