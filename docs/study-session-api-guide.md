# 공부 세션 API 가이드 (프론트엔드용)

이 문서는 `study-session` 도메인 API 3종(세션 제출·하루 목록 조회·스트릭 조회)을 프론트엔드가 스웨거 없이도 바로 연동할 수 있도록 정리한 것이다. 필드명, 예시 값, 검증 규칙, 에러 케이스를 모두 담았다.

- 인증 없음 (MVP 제외, ADR-0004)
- 모든 시각은 UTC ISO-8601 (`2026-07-24T01:00:00Z`)
- 모든 시간(초) 필드는 정수(초 단위)

## 목차

1. [핵심 개념 — 세션 안의 두 타이머](#핵심-개념--세션-안의-두-타이머)
2. [상태(EventStatus) 4종](#상태eventstatus-4종)
3. [POST /api/study-sessions — 세션 제출](#post-apistudy-sessions--세션-제출)
4. [GET /api/stats — 하루 목록 조회](#get-apistats--하루-목록-조회)
5. [GET /api/stats/streak — 연속 공부일(스트릭) 조회](#get-apistatsstreak--연속-공부일스트릭-조회)
6. [에러 응답 공통 포맷](#에러-응답-공통-포맷)

---

## 핵심 개념 — 세션 안의 두 타이머

하나의 공부 세션(방 입장~퇴장)은 안에 타이머 2개가 중첩된 구조다.

```
세션 (방 입장 ~ 퇴장, startedAt ~ endedAt)
└─ 총 공부 타이머 (studySec) — PAUSE 상태에서 멈춘다
   └─ 순공 타이머 (focusSec) — PHONE/DEVICE/AWAY/PAUSE 상태에서 모두 멈춘다
```

- **studySec (총 공부 시간)**: 앱이 잰 "총 공부 타이머" 값. `PAUSE`(일시정지) 상태일 때만 멈춘다.
- **focusSec (순공 시간)**: 온디바이스 AI가 판정한 실제 집중 시간. PHONE/DEVICE/AWAY/PAUSE 등 어떤 비공부 상태든 발생하면 멈춘다.
- 항상 `focusSec ≤ studySec ≤ (endedAt − startedAt)` 관계를 만족해야 한다.
- **서버는 이 두 값을 재계산하지 않는다.** 앱이 보낸 값을 그대로 저장한다(순공 시간은 ADR-0006, 총 공부 시간은 ADR-0008). 서버는 범위만 검증한다.

## 상태(EventStatus) 4종

| 값 | 의미 | studySec(총공부) 타이머 | focusSec(순공) 타이머 |
|---|---|---|---|
| `PHONE` | 휴대폰 사용 | 안 멈춤 | 멈춤 |
| `DEVICE` | 다른 기기 사용 | 안 멈춤 | 멈춤 |
| `AWAY` | 자리 비움 | 안 멈춤 | 멈춤 |
| `PAUSE` | 일시정지 (앱에서 직접 멈춤) | **멈춤** | 멈춤 |

> 과거 버전에서 `PAUSE`는 `STOP`이라는 이름이었다. 지금은 `PAUSE`로 통일되어 있다.

---

## POST /api/study-sessions — 세션 제출

공부를 마칠 때(방 퇴장 시) 세션 전체를 **한 번에** 제출한다. 서버는 세션 진행 중 실시간으로 아무것도 추적하지 않는다 — 이 API 호출이 유일한 데이터 소스다.

### Request

```
POST /api/study-sessions
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `userId` | Long | ✅ | 세션 주인의 유저 ID. `POST /api/users`로 발급받은 값 |
| `startedAt` | Instant | ✅ | 세션 시작 시각(방 입장). UTC ISO-8601 |
| `endedAt` | Instant | ✅ | 세션 종료 시각(방 퇴장). `startedAt` 이후, 24시간 이내, 미래 불가(기기 시계 오차 5분까지 허용) |
| `studySec` | Integer | ✅ | 총 공부 시간(초). 앱의 "총 공부 타이머" 값 그대로 |
| `focusSec` | Integer | ✅ | 순공 시간(초). 앱의 "순공 타이머" 값 그대로 |
| `events` | Array | ✅ | 비공부 상태 이벤트 목록. 없으면 빈 배열 `[]` |

**`events[]` 원소 — 딱 3개 필드만 보내면 된다. 길이(duration)는 서버가 `startedAt`/`endedAt`으로 직접 계산하므로 별도 필드로 보내지 않는다.**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | String | ✅ | `PHONE` \| `DEVICE` \| `AWAY` \| `PAUSE` 중 하나 |
| `startedAt` | Instant | ✅ | 이벤트 시작 시각. 세션 구간 안이어야 한다 |
| `endedAt` | Instant | ✅ | 이벤트 종료 시각. 시작 이후여야 하고, 다른 이벤트와 겹칠 수 없다(끝과 시작이 맞닿는 건 허용) |

이벤트는 순서가 뒤섞여 와도 된다 — 서버가 시작 시각 기준으로 정렬한다.

#### 요청 예시

```json
{
  "userId": 1,
  "startedAt": "2026-07-24T01:00:00Z",
  "endedAt": "2026-07-24T03:00:00Z",
  "studySec": 6600,
  "focusSec": 6000,
  "events": [
    { "status": "PHONE", "startedAt": "2026-07-24T01:10:00Z", "endedAt": "2026-07-24T01:20:00Z" },
    { "status": "PAUSE", "startedAt": "2026-07-24T02:00:00Z", "endedAt": "2026-07-24T02:10:00Z" }
  ]
}
```

### 검증 규칙 (하나라도 어기면 `400`)

1. `endedAt`은 `startedAt`보다 이후여야 한다
2. 세션은 24시간을 초과할 수 없다
3. `endedAt`은 미래일 수 없다(기기 시계 오차 5분까지 허용)
4. **`studySec`은 0 이상, `(endedAt − startedAt) − PAUSE 이벤트 시간 합` 이하**여야 한다
5. **`focusSec`은 0 이상, `studySec` 이하**여야 한다 (이벤트 총합과는 무관 — PHONE/DEVICE/AWAY가 아무리 많아도 focusSec 상한에 영향 없음)
6. 이벤트는 세션 구간 안에 있어야 하고, 서로 겹칠 수 없다
7. 검증은 **자정 분할 전 원본 제출 기준**이다 (예: 자정을 걸친 25시간짜리 세션은 무조건 거절)

### 자정 분할 — 응답은 항상 배열

세션이 한국 시간(KST) 자정(00:00)을 넘으면 **날짜별로 2개 세션으로 쪼개져 저장**된다.

- 예: `07-24 23:00` ~ `07-25 01:00` 제출 → `07-24` 세션(23~00시)과 `07-25` 세션(00~01시) 2개 생성, 응답 배열에 둘 다 담김
- `studySec`은 PAUSE를 제외한 조각 길이 비율로, `focusSec`은 전체 이벤트를 제외한 조각 길이 비율로 나뉘어 저장된다 (조각 합 = 원래 제출값)
- 자정에 걸친 이벤트도 시각 기준으로 나뉘어 각 세션에 귀속된다
- 자정을 넘지 않으면 배열 길이는 항상 1
- **정확히 자정에 시작하거나 끝나는 세션은 분할되지 않는다**

### Response `201 Created`

배열로 내려온다 (자정 분할 시 2개, 아니면 1개).

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | Long | 세션 ID |
| `userId` | Long | 세션 주인의 유저 ID |
| `statDate` | LocalDate | 통계 귀속 날짜(KST 기준 시작 날짜) |
| `startedAt` | Instant | 세션(조각) 시작 시각 |
| `endedAt` | Instant | 세션(조각) 종료 시각 |
| `studySec` | Integer | 총 공부 시간(초) — 제출값 그대로(분할 시 조각 비례 배분) |
| `focusSec` | Integer | 순공 시간(초) — 제출값 그대로(분할 시 조각 비례 배분) |
| `focusRate` | Double | 집중률(%) = `focusSec ÷ studySec × 100`, 소수 1자리 반올림 |
| `events` | Array | 이 세션(조각)에 귀속된 이벤트 목록, 시작 시각 오름차순. 각 원소는 `status`/`startedAt`/`endedAt` |

#### 응답 예시 (자정을 넘지 않는 경우 — 배열 길이 1)

```json
[
  {
    "id": 10,
    "userId": 1,
    "statDate": "2026-07-24",
    "startedAt": "2026-07-24T01:00:00Z",
    "endedAt": "2026-07-24T03:00:00Z",
    "studySec": 6600,
    "focusSec": 6000,
    "focusRate": 90.9,
    "events": [
      { "status": "PHONE", "startedAt": "2026-07-24T01:10:00Z", "endedAt": "2026-07-24T01:20:00Z" },
      { "status": "PAUSE", "startedAt": "2026-07-24T02:00:00Z", "endedAt": "2026-07-24T02:10:00Z" }
    ]
  }
]
```

### 에러 응답

| 코드 | 상황 | 예시 |
|---|---|---|
| `400` | 검증 규칙 위반 | `{"message": "총 공부 시간은 0 이상, 일시정지를 제외한 세션 시간 이하여야 합니다"}` |
| `400` | 순공 시간 범위 위반 | `{"message": "순공 시간은 0 이상, 총 공부 시간 이하여야 합니다"}` |
| `400` | 이벤트 겹침 | `{"message": "이벤트 구간이 서로 겹칠 수 없습니다"}` |
| `400` | 필수 값 누락 | `{"message": "userId: 널이어서는 안됩니다"}` |
| `404` | 존재하지 않는 `userId` | `{"message": "존재하지 않는 사용자입니다: 999"}` — 먼저 `POST /api/users`로 유저 등록 필요 |

---

## GET /api/stats — 하루 목록 조회

한 유저의 세션을 `statDate` 기준 **하루 단위**로 조회한다 (일별 기록 화면용). 기간(from~to) 조회는 아직 없음.

### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `userId` | Long | ✅ | 조회할 유저 ID |
| `date` | LocalDate | ✅ | 조회할 날짜, `statDate` 기준. 예: `2026-07-24` |

`date` 없이 호출하면 `400`.

### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `sessions` | Array | 세션 요약 목록, 시작 시각 내림차순. 아래 표 참고 |
| `sessionCount` | Integer | 조회된 세션 개수 (자정 분할 세션은 각각 1개로 센다) |
| `totalStudySec` | Long | 그날 총 공부 시간 합계(초) — `sessions[].studySec`의 합 |
| `totalFocusSec` | Long | 그날 순공 시간 합계(초) — `sessions[].focusSec`의 합 |
| `focusRate` | Double | 그날 전체 집중률(%) = `totalFocusSec ÷ totalStudySec × 100`. **세션별 집중률의 평균이 아니라 합계 기준** |
| `totalEventCounts` | Map | 그날 전체 상태별 이벤트 발생 건수 합계. `sessions[].eventCounts`를 모두 더한 값 |
| `studiedDatesInMonth` | Array&lt;LocalDate&gt; | `date`가 속한 달 동안 공부 기록이 있는 날짜 목록. 캘린더 표시용(중복 없음, 오름차순) |

`sessions[]` 원소(세션 요약) — 원본 이벤트 시각 목록은 안 담기고, **그 세션의 상태별 이벤트 건수만** 담긴다:

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | Long | 세션 ID |
| `statDate` | LocalDate | 통계 귀속 날짜 |
| `startedAt` / `endedAt` | Instant | 세션(조각) 시작/종료 시각 |
| `studySec` / `focusSec` | Integer | 총 공부 시간 / 순공 시간(초) |
| `focusRate` | Double | 이 세션의 집중률(%) |
| `eventCounts` | Map | **이 세션 하나**의 상태별 이벤트 건수. 발생하지 않은 상태도 키는 존재하고 값 0 |

`eventCounts` / `totalEventCounts`는 항상 `PHONE`/`DEVICE`/`AWAY`/`PAUSE` 4개 키가 전부 존재한다(발생 0건이어도 키 누락 없음) — 프론트에서 `undefined` 체크 없이 바로 써도 된다.

#### 응답 예시

```json
{
  "sessions": [
    {
      "id": 10,
      "statDate": "2026-07-24",
      "startedAt": "2026-07-24T01:00:00Z",
      "endedAt": "2026-07-24T03:00:00Z",
      "studySec": 6600,
      "focusSec": 6000,
      "focusRate": 90.9,
      "eventCounts": { "PHONE": 1, "DEVICE": 0, "AWAY": 0, "PAUSE": 1 }
    }
  ],
  "sessionCount": 1,
  "totalStudySec": 6600,
  "totalFocusSec": 6000,
  "focusRate": 90.9,
  "totalEventCounts": { "PHONE": 1, "DEVICE": 0, "AWAY": 0, "PAUSE": 1 },
  "studiedDatesInMonth": ["2026-07-03", "2026-07-10", "2026-07-24"]
}
```

#### 기록이 없는 경우 (빈 래퍼)

존재하지 않는 `userId`거나 기록 없는 날짜를 조회해도 `404`가 아니라 `200`으로 빈 값이 내려온다.

```json
{
  "sessions": [],
  "sessionCount": 0,
  "totalStudySec": 0,
  "totalFocusSec": 0,
  "focusRate": 0.0,
  "totalEventCounts": { "PHONE": 0, "DEVICE": 0, "AWAY": 0, "PAUSE": 0 },
  "studiedDatesInMonth": []
}
```

`studiedDatesInMonth`만은 예외 — 그 달에 다른 날짜 기록이 있으면 채워진다(오늘 조회한 `date`에 기록이 없어도).

---

## GET /api/stats/streak — 연속 공부일(스트릭) 조회

`statDate` 기준으로 세션이 하루라도 있으면 그날은 "공부한 날"로 친다. 유저 테이블에 저장된 값이 아니라 **세션 이력에서 매번 계산**한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `userId` | Long | ✅ | 조회할 유저 ID |

### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `streak` | int | 오늘(오늘 기록이 아직 없으면 어제)부터 거꾸로 이어진 연속 공부일. 오늘 기록이 없어도 어제까지 이어졌으면 유지 중으로 본다 — 오늘이 지나기 전엔 끊긴 게 아니다. 어제도 오늘도 기록이 없으면 0 |
| `maxStreak` | int | 전체 이력에서 가장 길었던 연속 공부일 |

기록이 없거나 존재하지 않는 `userId`면 `streak`/`maxStreak` 둘 다 0 (하루 목록 조회와 같은 "빈 값이어도 200" 계약).

#### 응답 예시

```json
{ "streak": 5, "maxStreak": 12 }
```

---

## 에러 응답 공통 포맷

`400`/`404` 에러는 전부 다음 포맷 하나다.

```json
{ "message": "사람이 읽을 수 있는 한국어 에러 메시지" }
```

메시지 문자열로 어떤 에러인지 분기하고 싶다면 `POST /api/study-sessions` 절의 표를 참고 — 메시지 앞부분("총 공부 시간은", "순공 시간은" 등)으로 케이스를 구분할 수 있다.
