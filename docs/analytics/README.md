# 운영 데이터 탐색 SQL

리텐션·방향 탐색에 쓴 SQL의 원본이다. Metabase(infra 저장소 `metabase/README.md`)의 같은 이름 질문으로 등록돼 있고,
Metabase에서 질문을 고치면 이 파일도 같이 고친다. 결정 배경은 [ADR-0032](../adr/0032-local-metabase-for-data-exploration.md).

공식 지표(일일 Slack 리포트)는 여기가 아니라 `project.study.metrics` 코드와 테스트에 있다. 여기 SQL은 테스트가 없는 탐색용이다.

## 공통 정의

- **날짜**: KST 기준 `stat_date`. 오늘 = `(now() AT TIME ZONE 'Asia/Seoul')::date`. 오늘은 진행 중이라 덜 잡힌다
- **측정만**: `source = 'MEASURED'`. 수동 기록(`MANUAL`, ADR-0031)은 빼고, 수동 기록 자체를 볼 때만 넣는다
- **화면에 보이는 조각**: `focus_sec >= 60`. 1분 미만 조각은 기록 화면에 나오지 않는다
- **공부한 날**: 그날 세션 하나라도 `focus_sec >= 300`. 스트릭·일일 리포트와 같은 기준이고 자정 분할 조각은 합치지 않는다 (ADR-0009, ADR-0012, ADR-0039)
- **핵심 사용자**: 최근 2주 중 공부한 날 6일+ 또는 최근 1주 중 3일+ — `core_users.sql`
- **제외**: 탈퇴(`status = 'DELETE'`), 관리자 계정 14·324
- **Amplitude** (`amplitude_daily_event`): 사용자 × 날짜 × 이벤트의 하루 횟수만 있다. 속성과 시각은 없다.
  매일 09시(KST)에 어제 것까지 적재하고, 적재 안 된 날은 0이 아니라 "없음"이다 (ADR-0030)
  - `session_end`는 다음 세션 때 찍혀 재방문의 대리값이 되므로 쓰지 않는다
  - `study_result_confirmed`·`session_notice_confirmed`는 솔로 세션 웹뷰가 닫히며 빠질 수 있다
  - `vision_detector_ready`는 10/2 무렵 이전 앱에는 없다
- **방 참여** (`room_participations`): `joined_at`이 비어 있으면 예약만 하고 들어오지 않은 것이다

## 파일

| 파일 | 보는 것 |
|---|---|
| `daily_learners.sql` | 최근 8주 일별 가입·1분+·5분+ 공부한 사람 수 (선 그래프) |
| `person_timeline.sql` | 한 사람의 최근 4주 날짜별 공부·방·Amplitude 이벤트 (`{{user_id}}` 입력 칸) |
| `core_users.sql` | 핵심 사용자 명단과 2주·1주 공부일 (Metabase 모델 "핵심 사용자") |
| `core_study_time.sql` | 핵심 사용자의 2주 공부 시간, 평균·중앙값 |
| `compare_detail.sql` ① | 최근 2주 공부한 사람별 기능 사용 (핵심 vs 비교) |
| `compare_summary.sql` ② | ①의 그룹별 사용 비율·중앙값 |
| `group_pairs.sql` ③ | 같은 방에서 10분+ 같이 공부한 짝 (지인 무리 찾기) |
| `first_session_detail.sql` ④ | 첫 측정 세션의 딴짓 감지와 7일 안 재방문 |
| `first_session_summary.sql` ⑤ | ④를 첫 5분 딴짓·첫 세션 순공·장소별로 묶은 재방문율 |
| `first_day_events_summary.sql` ⑥ | 첫날 Amplitude 이벤트를 남긴 비율 (첫 세션 결과별 깔때기) |
| `first_day_events_detail.sql` ⑦ | ⑥의 사람별 이벤트 횟수 |
| `manual_record_detail.sql` ⑧ | 수동기록을 쓴 사람과 그 뒤 활동 |
| `manual_record_summary.sql` ⑨ | 수동기록을 쓴 사람과 안 쓴 사람의 재방문 비교 |
