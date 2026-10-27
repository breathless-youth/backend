# 시간대 랭킹 4구간과 00시 단일 마감 (BY-1046)

- 작성일: 2026-10-28
- 상위: [BY-1020](https://breathless-youth.atlassian.net/browse/BY-1020) 랭킹 탭 UI/UX와 명세 개선
- 서브태스크: [BE] BY-1046 (이 문서) · 짝 [FE] BY-1047 (BY-1046에 막힘)
- 선행 설계: `2026-10-10-by828-ranking-design.md`, ADR-0028(조회 때 집계), ADR-0029(마감 기록)

## 1. 배경

시간대 랭킹은 KST 04·07·12·18·22시 경계의 5구간이고, 심야(22–04)만 시작한 날에 귀속돼 04시에 마감한다. 이 예외 때문에
마감 모달도 04시까지 00시 메달을 보류한다. 랭킹 탭 개편(BY-1020)에서 구간을 심야 0–6, 아침 6–12, 점심 12–18,
저녁 18–24의 4구간으로 바꾸고 모든 일간 판을 00시에 마감하기로 했다.

## 2. 결정 (2026-10-28)

1. **enum 이름은 유지한다.** 0–6 `NIGHT`, 6–12 `MORNING`, 12–18 `AFTERNOON`, 18–24 `EVENING`이다. 배포된 FE가 이 넷으로
   보내는 요청은 그대로 동작하고 구간 시각만 바뀐다. 화면 표기(심야·아침·점심·저녁)는 FE가 맡는다.
2. **`DAWN`은 기록 조회 전용으로 남긴다.** 마감 기록(`ranking_close`·`ranking_record`·`ranking_best`)의 `board_key`에
   `TIME_SLOT:*:DAWN`이 이미 있고 `RankingBoard.fromKey`가 `TimeSlot.valueOf`로 읽는다. 키를 `NIGHT`로 바꾸면 같은 날 두
   판의 메달이 유니크 제약에 걸리고, 지우면 받은 메달이 사라진다. `at()`은 `DAWN`을 돌려주지 않고, 판 생성과 마감 대상에서 뺀다.
3. **저장된 구간 행은 지우고 기동 백필로 다시 채운다.** 행은 이벤트 비율 배분이라 SQL로 다시 만들 수 없다. 마이그레이션이
   `study_session_slot`을 비우면 기존 `SessionSlotBackfill`이 지난 주 월요일부터의 측정 세션을 새 경계로 채운다.
4. **배포 중 겹침은 받아들인다.** 배포 때 옛 태스크와 새 태스크가 잠깐 함께 뜬다(`desired_count = 1`, `maximum_percent = 200`).
   마이그레이션 뒤 옛 태스크가 저장한 세션은 옛 경계로 행이 생기고 백필이 건너뛴다. 영향은 그 몇 분에 끝난 세션의 이번 주
   시간대 판 값뿐이라, 매 기동 전수 재계산(기동마다 2주 치 세션·이벤트 조회)보다 싸다. 배포는 04:01 이후 공부를 끝내는 사람이
   적은 이른 아침에 하고 00:00~04:01은 피한다. 그 사이에 배포하면 옛 태스크가 04:01에 마감하려던 전날 심야판을 새 태스크가
   00:00 마감으로 계산한다. 01시 전이면 새 정의로 마감되고, 01시가 넘으면 마감 1시간 창을 넘겨 메달 없이 건너뛴다.
5. **04시 보류를 없앤다.** 모든 판이 00:01 같은 배치 틱에서 마감되므로 묶어서 보여줄 이유가 없다. 마감 배치가 도는 몇 초 사이에
   앱을 열면 일부 메달만 먼저 보이고 나머지는 다음에 열 때 보인다.

## 3. 변경

### 3.1 구간 (`TimeSlot`)

- 경계 배열은 `{0, 6, 12, 18}`이고 `at()`은 0–6 `NIGHT`, 6–12 `MORNING`, 12–18 `AFTERNOON`, 18–24 `EVENING`을 준다.
- 마감하는 4구간 목록을 상수(`LIVE`)로 두고 `RankingBoard`의 판 생성 검증과 마감 대상이 같은 목록을 쓴다.
- `slotDateOf`는 KST 달력 날짜를 그대로 준다. 호출처 5곳을 그대로 두려고 메서드는 남긴다.
- `nextBoundary`는 경계 배열만 바뀐다.

### 3.2 판과 기간 (`RankingBoard`, `RankingCalendar`)

- `of`는 `slot=DAWN`에 400을 준다. Swagger enum에는 `DAWN`이 남으므로 설명에 기록 조회 전용이라고 적는다.
- 마감 대상 판은 15개에서 13개가 된다(순공 3, 집중률 2, 시간대 일·주 × 4).
- `RankingCalendar`는 심야 분기(`NIGHT_CLOSE`, 귀속일 기준)를 지우고 모든 기간 판을 00시에 마감한다.

### 3.3 마감 모달 (`RankingRecordService`)

- `unseenCutoff`와 심야 키 상수를 지우고 안 본 기록을 모두 준다.

### 3.4 저장된 구간 행

- `V30`: `DELETE FROM study_session_slot;`
- `SessionSlotBackfill.fromDate`는 달력 기준 지난 주 월요일이 된다. 심야 귀속일 설명은 지운다.

### 3.5 문구

- 04시·심야 예외를 설명하는 곳을 고친다: `RankingController`·`RankingRecordController` Swagger 설명, `RankingPeriod`·
  `RecordCursor`·`SlotSplitter`·`TimeSlot`·`RankingCloser` 주석, "시간대 일·주 × 5구간" 표기.
- `StandingsCalculator`의 집중 중 판정과 `canStillAccrue`의 구간 순회는 바뀐 `slotDateOf`·`nextBoundary`를 그대로 쓴다.
  로직은 바꾸지 않고 테스트로 확인한다.

### 3.6 ADR

- ADR-0033을 새로 쓴다: 4구간, 00시 단일 마감, `DAWN` 기록 전용, 지우고 백필로 다시 채우기, 배포 중 겹침이라는 대가.
- ADR-0029 결정 6(04시 보류)에 ADR-0033으로 대체됐다고 표시한다.

## 4. 테스트

- 고치는 테스트: `TimeSlotTest`, `SlotSplitterTest`, `RankingCalendarTest`, `RankingBoardTest`, `CanStillAccrueTest`,
  `RankingUnseenApiTest`, 그 밖에 04시·심야를 기대하는 테스트.
- 새 테스트
  - `slot=DAWN` 판 요청은 400이다.
  - 지난 `DAWN` 기록이 기록 목록·요약 API에서 깨지지 않고 나온다.
  - 마이그레이션 뒤 백필이 새 경계로 행을 채운다.
- 완료 전 `./gradlew check`가 통과한다.

## 5. 배포

- 배포된 FE 웹은 `DAWN` 칸을 포함한 5칸을 그린다. BE만 먼저 나가면 새벽 칸을 누를 때 400이 난다.
- BE와 BY-1047(FE)을 같은 시점에 배포한다. BY-1047은 이 브랜치를 로컬로 띄워 확인한 뒤 푸시한다.
- 배포는 04:01 이후에 한다(§2 결정 4).
- 백필은 실패해도 기동을 막지 않는다. V30이 행을 비운 직후라 실패하면 이번 주 시간대 판이 다음 재기동까지 0이 되므로, 배포 뒤
  `시간대 구간 백필: N개` 로그와 `study_session_slot` 행 수가 0이 아닌지 확인한다.

## 6. 범위 밖

- 배포 중 겹침으로 옛 경계 행을 가진 세션을 찾아 고치는 작업.
- 지난 `DAWN`·`NIGHT`(22–04)·`EVENING`(18–22) 기록의 값과 기간 재계산. 기록은 마감 당시 정의로 남는다.
