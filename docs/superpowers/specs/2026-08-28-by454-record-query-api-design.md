# 기록창 조회 API 재설계 (BY-454)

- 작성일: 2026-08-28
- 티켓: [BY-454](https://breathless-youth.atlassian.net/browse/BY-454)
- 상태: 설계 확정 대기 → 승인 시 구현 계획(writing-plans)으로 이관

## 1. 배경

기록창 UI가 아래 구조로 개편됐다(프로토타입 `기록창-standalone.html` 기준).

- **주간/월간 토글** — 상단에서 주간·월간 뷰 전환
- **주간 뷰** — 주 총 순공시간 + 지난주 대비 증감, 요일(월~일) 막대(일별 순공시간), 날짜별 발자국
- **월간 뷰** — 월 총 순공시간 + 지난달 대비 증감, 달력에 일별 발자국(공부량 강도)
- **날짜 상세** — 선택 날짜의 순공/총공부/평균집중 + 세션 목록
- **세션 바텀시트** — 세션 선택 시 타임라인(공부↔비공부 구간) + 총공부·비집중횟수·집중률

기존 조회 API는 하루 단위(`GET /api/stats?date=`)와 스트릭(`GET /api/stats/streak`)뿐이라, 기간(주/월) 집계와 세션 타임라인을 커버하지 못한다.

## 2. 설계 원칙

1. **"주/월"의 의미는 클라이언트가 소유한다.** 서버는 "이 날짜 구간의 원시 집계"만 책임진다. 그래서 기간 API 하나로 주간·월간을 모두 그리고, 향후 연간 뷰가 생겨도 추가 비용 없이 커버된다.
2. **표현(render) 로직은 클라이언트가 소유한다.** 발자국 강도 매핑, 타임라인 세그먼트 계산, 상태별 색 매핑, "비집중 N회" 정의는 모두 클라 몫이다. 서버는 원시 데이터(일별 순공시간, 이벤트 구간)만 내려준다.
3. **기존 계약을 재사용·비파괴 확장한다.** 날짜 상세는 기존 `GET /api/stats?date=`가 이미 충족하므로 신규 개발하지 않는다.

## 3. 화면이 사용하는 API (총 3개)

| # | 엔드포인트 | 상태 | 화면 용도 |
|---|---|---|---|
| 1 | `GET /api/stats/period` | **신규** | 주간 막대 / 월간 달력 + 기간 총합 + 직전 기간 비교 |
| 2 | `GET /api/study-sessions/{id}` | **신규** | 세션 바텀시트 (타임라인 구간) |
| 3 | `GET /api/stats?userId&date` | 기존 재사용 | 날짜 상세 세션 목록 (id 포함) |

- `GET /api/stats/streak`(기존)는 이번 기록창에서 **사용하지 않는다.** 엔드포인트 자체는 삭제하지 않고 그대로 둔다(비파괴).

## 4. 신규 API 상세

### 4.1 `GET /api/stats/period` — 기간 집계 조회

주어진 날짜 구간 `[from, to]`에 대해 일별 순공시간 버킷과 기간 총합을 반환한다. `compareFrom`/`compareTo`를 주면 그 구간의 순공 총합도 함께 반환한다(증감 비교용).

**요청 파라미터**

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `userId` | Long | Y | 조회 유저 |
| `from` | LocalDate | Y | 구간 시작일(포함) — statDate 기준 |
| `to` | LocalDate | Y | 구간 종료일(포함) — statDate 기준 |
| `compareFrom` | LocalDate | N | 비교 구간 시작일. `compareTo`와 함께 주거나 둘 다 생략 |
| `compareTo` | LocalDate | N | 비교 구간 종료일 |

- 주간 뷰: `from`=주 시작(월), `to`=주 끝(일), `compareFrom/To`=지난주 월~일.
- 월간 뷰: `from`=그 달 1일, `to`=그 달 말일, `compareFrom/To`=지난달 1일~말일.
- 비교 구간을 클라가 명시적으로 넘기므로, 월마다 길이가 달라도(28~31일) 서버가 "주냐 월이냐"를 알 필요 없이 정확히 계산된다.

**응답** — `StudyPeriodStatsResponse`

```jsonc
{
  "from": "2026-08-24",
  "to": "2026-08-30",
  "totalStudySec": 47700,           // 기간 총공부 합
  "totalFocusSec": 41040,           // 기간 순공 합 (11시간 24분)
  "previousTotalFocusSec": 33840,   // compare 구간 순공 합 (지난주). compare 미지정 시 null
  "dailyFocusSec": [                // from~to 모든 날짜, 공부 없는 날도 0, statDate 오름차순
    { "date": "2026-08-24", "studySec": 13800, "focusSec": 12240 },
    { "date": "2026-08-25", "studySec": 8700,  "focusSec": 7560  },
    { "date": "2026-08-26", "studySec": 17640, "focusSec": 16200 },
    { "date": "2026-08-27", "studySec": 7560,  "focusSec": 5040  },
    { "date": "2026-08-28", "studySec": 0, "focusSec": 0 },
    { "date": "2026-08-29", "studySec": 0, "focusSec": 0 },
    { "date": "2026-08-30", "studySec": 0, "focusSec": 0 }
  ]
  // 합계 검증: focusSec 합 = 41040(= totalFocusSec), studySec 합 = 47700(= totalStudySec)
}
```

**응답 필드 규칙**

- `dailyFocusSec`는 `from`~`to`의 **모든 날짜**를 빠짐없이 담는다(공부 없는 날은 `focusSec=0`, `studySec=0`). 클라가 요일/날짜 인덱스로 바로 매핑할 수 있게 하기 위함이다.
- 발자국 유무 = `focusSec > 0`, 발자국 강도 = 클라가 `focusSec` 값으로 자체 임계값 매핑.
- `previousTotalFocusSec`는 `compareFrom/To` 둘 다 있을 때만 계산, 아니면 `null`.
- 존재하지 않는 userId·기록 없는 구간이면 총합 0, `dailyFocusSec`는 모든 날짜 0으로 채워진 배열.

**입력 검증**

- `from` > `to` → 400.
- `compareFrom`/`compareTo` 중 하나만 있으면 400, `compareFrom` > `compareTo` → 400 (기존 streak API의 from/to 계약과 동일한 규칙).
- 과도한 범위 방어: `to - from`이 최대 허용치(예: 366일)를 넘으면 400.

### 4.2 `GET /api/study-sessions/{id}` — 세션 단건 상세 조회

세션 바텀시트용. 기존 `StudySessionResponse`를 그대로 반환한다(신규 DTO 없음).

**요청**

| 이름 | 위치 | 필수 | 설명 |
|---|---|---|---|
| `id` | path | Y | 세션 ID |
| `userId` | query | Y | 소유권 검증용 |

- 세션 `id`는 날짜 상세(`GET /api/stats?date=`)의 `sessions[].id`에서 클라가 이미 확보한다. 따라서 요청에 날짜는 불필요하다(id가 날짜를 유일하게 특정한다).
- `id`가 존재하지 않거나 `userId`의 세션이 아니면 → **404** (IDOR 방지: 소유자 불일치도 존재 여부를 노출하지 않도록 404로 통일).

**응답** — 기존 `StudySessionResponse` 재사용

```jsonc
{
  "id": 10,
  "userId": 1,
  "statDate": "2026-08-27",
  "startedAt": "2026-08-27T00:12:00Z",
  "endedAt":   "2026-08-27T01:36:00Z",
  "studySec": 5040,     // 총공부 1시간 24분
  "focusSec": 4080,     // 순공 1시간 8분
  "focusRate": 82.0,    // 집중률
  "events": [           // 비공부 상태 이벤트 구간, startedAt 오름차순
    { "status": "PHONE", "startedAt": "2026-08-27T00:34:00Z", "endedAt": "2026-08-27T00:41:00Z" },
    { "status": "AWAY",  "startedAt": "2026-08-27T01:05:00Z", "endedAt": "2026-08-27T01:12:00Z" }
  ]
}
```

- 타임라인은 클라가 그린다: 세션 범위(`startedAt`~`endedAt`)에서 `events` 구간을 빼면 공부(집중) 구간.
- 4개 상태(PHONE/DEVICE/AWAY/PAUSE)를 **모두** 원시로 내려준다. 색 매핑(PAUSE=회색 등)과 "비집중 N회" 정의는 클라가 결정.
- 자정 분할 세션은 조각마다 별도 `id`·`statDate`를 가지므로, 한 조각(=한 세션 row)만 정확히 반환된다.

## 5. 화면 ↔ API 매핑

**주간 뷰 진입**
1. `GET /api/stats/period?userId&from=<주월>&to=<주일>&compareFrom=<지난주월>&compareTo=<지난주일>` → 막대(요일별)·주 총합·증감
2. 날짜 탭 → `GET /api/stats?userId&date=<선택일>` → 하단 순공/총공부/평균집중 + 세션 목록
3. 세션 탭 → `GET /api/study-sessions/{id}?userId` → 바텀시트

**월간 뷰 진입**
1. `GET /api/stats/period?userId&from=<1일>&to=<말일>&compareFrom=<지난달1일>&compareTo=<지난달말일>` → 달력 발자국·월 총합·증감
2. 날짜 탭 → 위와 동일(`stats?date=`)
3. 세션 탭 → 위와 동일(`study-sessions/{id}`)

## 6. 계산 규칙 및 가정

- **일별/기간 집계의 세션 필터**: 기존 `list` API와 동일하게 순공시간(`focusSec`)이 1분 미만인 세션은 집계에서 제외한다. 그런 세션만 있는 날은 `focusSec=0`(발자국 없음)이 된다.
- **인증/스코핑**: 기존 조회 API와 동일하게 `userId`를 쿼리 파라미터로 받는다(소셜 로그인+JWT는 현재 파킹 상태).
- **버킷 단위**: 항상 일별(day). 주간=7버킷, 월간=28~31버킷. 별도 granularity 파라미터는 두지 않는다(YAGNI).
- **집중률**: 기존 계약과 동일 — 세션별 평균이 아니라 합계 기준(`totalFocusSec ÷ totalStudySec`).
- **잉여 필드**: 기존 `StudySessionListResponse.studiedDatesInMonth`는 달력이 period로 대체되어 이 화면에선 불필요해지지만, 하위호환을 위해 그대로 둔다.

## 7. 범위 제외 (Out of Scope)

- 스트릭("발자국 N일째")은 이번 기록창에서 제외한다.
- 세그먼트/색 매핑, 발자국 강도 임계값, "비집중 N회" 정의는 클라이언트 책임이라 서버 설계에 포함하지 않는다.
- 기간 API의 주/월 이외 단위(연간 등)는 지금 만들지 않는다(기존 계약으로 확장 가능).

## 8. 테스트 관점 (구현 시)

- **`period` 서비스 단위테스트**: 빈 구간(모든 날 0), 일부 날만 기록, `from>to` 400, compare 한쪽만 지정 400, 1분 미만 세션 제외 확인, 직전 기간 합 정확성.
- **`period` 통합테스트(MockMvcTester)**: `dailyFocusSec`가 모든 날짜를 채우는지, `previousTotalFocusSec` null/값 케이스.
- **`study-sessions/{id}` 통합테스트**: 정상 조회, 남의 세션 404, 없는 id 404, events 구간 포함 확인, 자정 분할 조각 단건 반환.
- 통합테스트는 Testcontainers(실제 PostgreSQL) + `SecurityMockMvcRequestPostProcessors.authentication()` 패턴 사용.
