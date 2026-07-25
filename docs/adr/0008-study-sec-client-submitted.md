# ADR-0008: session_sec를 study_sec으로 바꾸고 총 공부 시간도 앱 제출값을 신뢰

- 상태: 승인
- 날짜: 2026-07-25

## 맥락

세션 제출 API는 지금까지 `session_sec`(세션 총 시간)을 서버가 `endedAt - startedAt`으로
계산해 저장해왔다. 그런데 실제 앱 UX는 3단 중첩 타이머다: **세션(방 입장~퇴장)** 안에
**총 공부시간 타이머**가 있고, 그 안에 다시 **순공시간 타이머**(`focusSec`, ADR-0006)가
있다. 총 공부시간은 방에 머문 시간(`endedAt - startedAt`)과 다르다 — 사용자가 `PAUSE`
(일시정지, 앱에서 직접 멈춤) 상태일 때는 방을 나가지 않고도 총 공부시간 타이머가 멈춘다. 서버가
`endedAt - startedAt`만으로 이 값을 계산하면 일시정지 구간까지 공부 시간으로 셀 수밖에
없어 실제 UX와 어긋난다.

또한 순공 시간을 앱 제출값 그대로 신뢰하기로 한 ADR-0006과 같은 이유로, 총 공부 시간도
서버가 재계산할 근거가 없다 — 타이머의 시작/정지를 판단하는 주체는 앱이고, 서버는 그
판정을 재현할 수 없다.

## 결정

- `session_sec` 컬럼/필드를 **`study_sec`으로 이름을 바꾸고**, 의미를 "서버 계산값"에서
  "앱이 제출한 총 공부 타이머 시간"으로 바꾼다. ADR-0006의 신뢰 모델을 총 공부 시간까지
  확장하는 것이다
- 이벤트 상태별로 멈추는 타이머가 다르다: `PAUSE`(일시정지)는 총공부시간·순공시간 타이머를
  둘 다 멈추고, 나머지(`PHONE`/`DEVICE`/`AWAY`)는 순공시간 타이머만 멈춘다
- 검증 체인: `0 ≤ focusSec ≤ studySec ≤ (endedAt - startedAt) − PAUSE 이벤트 시간 합`.
  `focusSec`의 상한은 `studySec`까지만 본다 — 이벤트(PHONE/DEVICE/AWAY 포함) 총합으로
  더 빡빡하게 제한하지 않는다. 이벤트로 `focusSec`을 역산·제한하지 않는다는 ADR-0006의
  원칙을 유지하되, `PAUSE`만 예외적으로 `studySec` 쪽 검증에 반영한다 — `PAUSE`는 타이머
  자체를 멈추는 이벤트라 물리적으로 그 시간만큼은 총 공부 시간이 될 수 없기 때문이다
- 자정 분할(ADR-0005) 시 배분 가중치도 이 구분을 따른다: 조각별로 `PAUSE` 시간만 뺀
  `studyActiveSec`을 `studySec` 배분 가중치로, 전체 이벤트 시간을 뺀 `focusActiveSec`을
  `focusSec` 배분 가중치로 각각 쓴다. 배분 가중치 합이 0인 예외 케이스(전 구간이 이벤트로
  덮인 경우)는 0으로 나누기를 피하기 위해 `studySec` 비율로 대체 배분한다
- 집중률(`focusRate`)의 분모를 `sessionSec`에서 `studySec`으로 바꾼다:
  `focusRate = focusSec ÷ studySec × 100`. 방에 있었지만 공부 타이머를 켜지 않은 시간은
  집중률 계산에서 제외한다
- 방 체류시간(`endedAt - startedAt`) 자체는 별도 필드로 저장하지 않는다 — 필요하면
  응답의 `startedAt`/`endedAt`에서 클라이언트가 계산할 수 있다

## 결과

- ADR-0003의 "총 시간은 서버가 계산한다"는 전제를 `studySec`에 대해서는 대체한다
  (`focusSec`은 이미 ADR-0006에서 대체됨)
- 응답 DTO(`StudySessionResponse`, `StudySessionSummaryResponse`, `StudySessionListResponse`)와
  DB 컬럼(`study_session.session_sec` → `study_sec`)이 함께 바뀐다 (`V5` 마이그레이션)
- 순공 시간과 마찬가지로 총 공부 시간도 클라이언트 신뢰 범위에 들어간다 — 랭킹 등 경쟁
  기능 도입 시 조작 방지 관점에서 재검토 필요 (ADR-0003, ADR-0006과 동일한 유보)
- 자정 분할 배분값은 여전히 비례 추정치다 — `PAUSE`/이벤트가 자정 앞뒤 어느 쪽에
  가까웠는지는 조각 내에서의 위치까지는 반영하지만, 조각을 넘어서는 실제 흐름은 알 수 없다
