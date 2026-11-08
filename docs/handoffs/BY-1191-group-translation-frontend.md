# BY-1191 프론트 전달사항: 앱 언어 설정과 그룹 자동 번역

연관 백엔드: [BY-1191](https://breathless-youth.atlassian.net/browse/BY-1191). 프론트 구현은 이 백엔드 PR에 포함하지 않는다.

## 설정 → 앱 언어

선택지는 다음과 같다. 기본값은 **기기 설정 따르기**이며, 사용자의 명시적 선택은 OS 언어와 위치/국가보다 우선한다.

| 선택지 | 저장 값 / API 언어 |
| --- | --- |
| 기기 설정 따르기 | `system` 저장, 요청 시 OS 언어를 지원 locale로 해석 |
| 한국어 | `ko` |
| English | `en` |
| 日本語 | `ja` |
| 简体中文 | `zh-CN` |
| 繁體中文（台灣） | `zh-TW` |
| 繁體中文（香港） | `zh-HK` |

- 선택값은 클라이언트에 저장해 앱 재실행 후에도 유지한다. 언어 저장을 위한 새 백엔드 API는 없다.
- `system`은 UI 설정 값이며 Accept-Language에 그대로 보내지 않는다. OS 언어를 해석한 최종 앱 locale을 보낸다. 미지원 OS 언어는 기존 프론트 국제화의 기본 언어로 처리한다.
- OS가 영어여도 앱에서 한국어를 선택하면 한국어를 유지한다. 한국에 있어도 앱에서 영어를 선택하면 영어를 유지한다. 위치 변경으로 명시적 선택을 덮어쓰지 않는다.
- 언어 변경 즉시 기존 i18n 고정 문구와 그룹 번역 조회에 같은 최종 언어를 적용한다.
- `system`인 경우 앱 복귀 시 OS 언어 변경을 반영한다. 직접 선택한 경우에는 OS 변경의 영향을 받지 않는다.

## 요청

기존 인증·API-Version 헤더를 유지하고, 아래 그룹 GET에 최종 앱 언어를 추가한다.

```http
API-Version: 1
Accept-Language: en
```

| API | 번역 필드 위치 | 대상 |
| --- | --- | --- |
| `GET /api/groups/public` | `groups[].translation` | 이름 |
| `GET /api/groups/me` | `groups[].translation` | 이름 |
| `GET /api/groups/public/{groupId}` | `translation` | 이름·소개 |
| `GET /api/groups/{groupId}` | `translation` | 이름·소개 |

헤더 없음·미지원 언어·잘못된 언어 헤더는 원문이다. 번역 공급자 호출·키·무료량·전환은 백엔드가 관리한다. 프론트에는 공급자 키를 넣지 않는다.

## 표시와 수정

기존 `name`, `description`은 원문이다. 새 `translation` 전체 또는 개별 필드가 null일 수 있으므로 각 필드에서 원문으로 대체한다.

```json
{
  "name": "새벽 공부방",
  "description": "매일 함께 공부해요",
  "translation": {
    "language": "en",
    "name": "Early Morning Study Group",
    "description": "Let's study together every day."
  }
}
```

```javascript
const displayName = group.translation?.name ?? group.name;
const displayDescription = group.translation?.description ?? group.description;
```

- 번역문은 일반 텍스트로 표시한다.
- 그룹 수정 화면은 원문 필드를 사용한다. 번역문으로 원문을 덮어 저장하지 않는다.
- 무료량 소진·시간 초과·공급자 장애·다른 요청의 번역 처리 중에는 원문이 정상 응답이다. 이를 무한 로딩이나 오류로 표시하지 않는다.
- 대만·홍콩은 현재 같은 표준 번체 모델을 사용한다. 홍콩 번체를 광둥어로 표시하지 않는다.

## 캐시와 언어 전환

- 그룹 조회 캐시 키에 최종 앱 locale을 포함한다. 기존 사용자·그룹·검색/필터 구분은 유지한다.
- 언어를 바꾸면 해당 언어로 재조회하고, 이전 언어의 늦은 응답이 현재 화면을 덮지 않게 취소 또는 무시한다.
- 백엔드도 원문·필드·언어별 결과를 공유하므로 조회하는 사용자 수만큼 번역을 다시 호출하지 않는다. 기존 화면 폴링 주기는 유지할 수 있다.

## 확인 목록과 배포 상태

- 여섯 locale의 그룹 목록·상세·내 그룹·그룹 홈 표시.
- OS 영어 + 앱 한국어, OS 한국어 + 앱 영어에서 명시적 선택 유지.
- 앱 재실행 후 선택 유지, system 선택 시 OS 변경 반영.
- 언어 전환 후 이전 언어 캐시/늦은 응답이 남지 않음.
- translation 전체 null 및 이름/소개 중 한 필드만 null일 때 원문 대체.
- 그룹 수정 화면에서 원문 유지.

백엔드 기능과 공급자는 기본 off다. 백엔드 배포·키 주입·무료 잔량 장부 초기화·기능 활성화 후 실번역을 확인할 수 있다. 배포 또는 활성화 전에도 원문 표시는 정상이어야 한다. 실번역 품질 평가는 아직 실행하지 않았다.
