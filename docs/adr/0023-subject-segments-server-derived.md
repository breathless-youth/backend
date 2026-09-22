# ADR-0023: 과목 시간을 구간으로 받고 서버가 과목별 시간을 계산한다

- 상태: 승인
- 날짜: 2026-09-22
- 티켓: BY-733 (스토리 BY-556, 후행 [BE] BY-734 · [FE] BY-735)
- 선행: ADR-0021 (과목 > 할 일과 세션 과목 시간) — §3·§4를 이 ADR이 갱신한다
- 설계: `docs/superpowers/specs/2026-09-22-by556-record-tab-v2-design.md`

## 맥락

기록 탭 v2의 24시간 타임테이블은 2분 칸마다 "그때 공부한 과목 색"을 칠한다. ADR-0021은 세션당 과목별
**합계**(`study_session_subject_time`)만 저장해 "언제 어느 과목을 했는지"를 모른다. 프로토타입은 합계 비율로
세션을 앞뒤로 쪼개 칠한 목업이었고, 그대로 가면 화면이 실제와 다른 순서를 보여준다.

과목 시트(BY-696)가 들어간 앱은 배포 전이라 `subjectTimes` 계약을 바꿀 비용이 지금이 가장 싸다.

## 결정

1. **앱은 과목 전환 구간만 보낸다.** 세션 제출·스냅샷·복구의 `subjectTimes[]`(과목별 순공·총공부 합계)를 없애고
   `subjectSegments[]`(subjectId, startedAt, endedAt)로 **교체**한다. 비공부 이벤트가 시각만 보내고 길이는 서버가
   계산하는 것과 같은 원칙이다. 같은 사실을 합계와 구간 두 형태로 받는 안은 기각 — 앱 버그로 둘이 어긋나면 화면이
   자기모순을 보여준다.
2. **과목별 총공부·순공은 서버가 계산한다.** 구간마다 `studySec = 길이 − PAUSE 겹침`, `focusSec = 길이 − 모든 이벤트
   겹침`. 앱 타이머가 멈추는 규칙과 같은 계산이라 앱이 보내던 값과 같은 결과가 나온다. 이벤트끼리는 겹치지 않으므로
   겹침 합이 정확하다. 길이는 이벤트와 같이 초 절삭이며 `floor(a)+floor(b) ≤ floor(a+b)`라 음수가 나오지 않는다.
   절삭 뒤 0초가 된 조각은 행을 만들지 않는다.
3. **세션 단위 값은 여전히 앱을 믿는다.** `studySec`·`focusSec`는 ADR-0006·0008대로 제출값 그대로이고, 구간 합을 세션
   값에 맞추는 검증은 두지 않는다(ADR-0021 §4의 태도 유지). 구간 계산은 세션 값의 *배분*이 아니라 독립된 파생이다.
4. **검증은 이벤트와 같다.** 종료 > 시작, 세션(스냅샷은 reportedAt) 구간 안, 서로 겹치지 않음(맞닿음 허용), 순서 무관,
   `subjectId`는 토큰 유저의 과목(지운 과목 허용, ADR-0021 §1). 위반 400. 개수 상한은 이벤트처럼 두지 않는다.
   소유 검증은 ADR-0021 §6대로 컨트롤러가 `StudySubjectService.assertOwned`를 먼저 부른다.
   - 요청의 시각은 마이크로초로 절삭해 받는다. 스냅샷은 구간을 JSON으로, 세션 시작·보고 시각은 timestamptz(마이크로초)
     컬럼으로 두므로, 나노초가 JSON에만 남으면 자동 확정 때 "구간이 세션 밖"으로 오판돼 draft가 폐기된다.
5. **자정 분할은 구간을 자정에서 자르고 조각마다 그 조각의 이벤트로 다시 계산한다.** 시간처럼 비례 배분하지 않는다 —
   구간은 시각을 가진 사실이라 자를 수 있다. 정확히 자정에 끝나는 구간은 첫 조각에만 남는다.
6. **저장은 구간 행에 파생값을 함께 둔 테이블 하나다.** `study_session_subject_segment(session_id, subject_id,
   started_at, ended_at, study_sec, focus_sec)`가 `study_session_subject_time`을 대체한다(V20). 과목 누적
   (`GET /api/subjects`)이 지금처럼 `subject_id` 합산 한 문장이어야 하기 때문이다(ADR-0021 §3의 근거 유지). 같은
   트랜잭션에서 같은 입력으로 쓰고 이후 바뀌지 않으므로 ADR-0007이 걱정한 파생값 동기화 버그 부류가 아니다.
   - 읽을 때 구간∩이벤트로 계산하는 안은 기각: 과목 누적이 이벤트까지 조인하는 집계가 된다.
7. **스냅샷·복구도 같은 필드다.** `active_study_session.subject_segments`(jsonb)에 통째로 덮어쓰고, 복구 응답이 요청과
   같은 모양을 **시작 오름차순**으로 돌려줘 앱이 마지막 원소의 과목으로 선택 상태를 복원한다. 자동 확정은 `create`
   재사용이라 구간도 그대로 확정된다. 진행 중인 구간은 앱이 reportedAt에서 닫아 보낸다.
8. **병행 없이 교체한다.** 배포된 앱은 `subjectTimes`를 보내지 않고 운영에 합계 행이 없다. 구 앱(API-Version 1)은 이
   필드를 애초에 모른다. V20은 합계 테이블과 스냅샷 컬럼을 이관 없이 버린다.

## 결과

- 계약: `subjectTimes[]` → `subjectSegments[]`(제출·스냅샷·복구), `StudySessionResponse.subjectSegments[]`(subjectId,
  startedAt, endedAt, studySec, focusSec, 시작 오름차순). 구간 사이 빈 시간은 "과목 미선택"이고 표현은 앱이 정한다.
- 스키마 V20: `study_session_subject_segment` 생성, `study_session_subject_time` 삭제, `active_study_session.subject_times`
  → `subject_segments`.
- 코드: `SubjectSegmentSplitter`(절단·계산 순수 로직), `StudySessionValidator.validateSubjectSegments`,
  `StudySubjectService.assertOwned(userId, subjectIds)`. 비례 배분(`splitSubjectTimes`)·합계 검증은 사라진다.
- dev 시더가 데모 과목 3개와 큐레이션 세션 3건의 구간을 심는다.
- 후속: BY-734가 일간 목록·상세 응답에 이벤트·구간·완료 할 일과 과목 이름·색을 싣는다(ADR-0022 §9의 읽기 후속).
  이벤트 요청(`StatusEventRequest`)의 나노초는 같은 부류의 잠재 문제가 남아 있다 — 앱(웹뷰)은 밀리초만 보내 실제로는
  닿지 않으므로 이번엔 건드리지 않았다.
