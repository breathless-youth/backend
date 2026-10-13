# 일일 지표 리포트 v2 — 세그먼트·리텐션 중심 재구성 (BY-904)

- 작성일: 2026-10-13
- 티켓: [BY-904](https://breathless-youth.atlassian.net/browse/BY-904)
- 선행: BY-461(현재 리포트), BY-373(Amplitude 트래킹), ADR-0009(스트릭 기준), ADR-0012(헤비유저·지표를 백엔드에)
- 결정 기록: ADR-0030
- 상태: 설계 확정(2026-10-13 인터뷰) · PR ① 구현 계획 `docs/superpowers/plans/2026-10-13-daily-report-v2-pr1-amplitude-ingest.md`
  · PR ② 계획은 ① 구현 뒤 별도 문서

## 1. 배경

매일 10시(KST) Slack으로 오는 일일 리포트(BY-461)를 팀이 제대로 보지 않는다. 인터뷰에서 확인한 섹션별 사용 실태:

| 지금 섹션 | 실태 | v2 |
|---|---|---|
| 가입 수·신규 목록(id·시각) | 수는 매일 봄, 시각 나열은 안 봄 | 수 + **가입 시간대 분포** |
| 헤비유저 수·id | 의미 없음 | 제거 → 고정 세그먼트 S1·"정착" 단계로 흡수 |
| 10분+ 세션 건수 | 의미 없음 | 제거 |
| 소셜 vs 싱글 순공 | 숫자만 있어 판단 불가 | 제거 → 세그먼트의 "주로 한 행동"으로 |
| 코호트 첫주 공부일수 | 안 봄 | 제거 → 리텐션 섹션으로 |
| 세션 상세(상위 20명) | "많이 했네"에서 끝남 | **어제 공부한 유저를 단계별 한 줄 프로필로** |

리포트로 하려는 결정은 **기능 우선순위**(어떤 기능이 남는 유저와 연결되나)와 **타겟 유저 정의**(어떤 사람이 남나)다.
규모는 하루 가입 수십 명·DAU 수십 명이라, 모든 비율은 분모와 함께 쓰고 하루가 아니라 28일 코호트로 계산한다.

## 2. 결정 (2026-10-13 인터뷰)

1. **세그먼트는 명시적 행동 규칙으로 정의하고, 규칙 문장을 메시지에 그대로 적는다.** 업계의 행동 코호트·아하 모먼트
   분석과 같은 틀이다.
2. **고정 규칙 1개 + 자동 탐색 40개.** 고정 규칙은 "가입 후 첫 7일 중 공부한 날 3일 이상"(S1). 앱 스트릭·헤비유저와 같은 잣대다.
3. **리텐션은 두 층이다.** 전체 흐름은 D1/D7/D30, 세그먼트 비교는 "첫 7일 행동 → 8~14일째(D7~D13)에 공부했나"(2주차).
   행동 기간과 결과 기간이 겹치지 않게 한다.
4. **공부한 날 = 순공 10분 이상 세션이 하나라도 있는 날**(ADR-0009). 자정 분할 조각은 병합하지 않는다(ADR-0012 결정 3).
5. **노출 조건과 순위는 Amplitude Compass 기준을 따른다**(§6). 상관(phi) 0.2 이상만, 상관 순 정렬. %p 차이·배수 정렬은 기각 —
   작고 극단적인 그룹이 1위로 올라온다.
6. **Amplitude 이벤트도 쓴다.** 매일 Export API로 받아 유저×날짜×이벤트 횟수로 적재하고, 리포트는 DB만 읽는다(§7).
   리포트 때마다 다운로드하는 안(Amplitude 장애 = 섹션 누락, 매일 6주치 재다운로드)과 Amplitude 차트 API 안(서버 기준
   "공부한 날"을 못 쓰고 유저별 프로필 불가)은 기각.
7. **별도 배치 인프라는 두지 않는다.** prod 하루 4~6천 건(2026-10-13 실측)으로 서버 안 `@Scheduled`면 충분하다. 장애 범위는
   줄 단위 스트리밍·전용 스레드·타임아웃·크기 상한으로 묶는다. 재검토 신호: 리포트 생성이 수 분을 넘음, 리포트 쿼리가 API 응답에
   영향, ECS 태스크 2개 이상, 크기 상한 초과 알림.
8. **Slack은 요약 1개 + 스레드 댓글 5개.** Incoming Webhook은 스레드를 못 달아 봇 토큰(`chat.postMessage`)으로 바꾼다.
9. **서버에 기록이 없는 행동은 Amplitude로만 본다.** 새 화면 이벤트 수집, FE 자기 신고 유입 채널 연결은 범위 밖이다.

## 3. 공통 정의

- **기준일 A**: 발송일 KST 어제. 오늘은 부분 집계라 쓰지 않는다.
- **가입일 D0**: `users.created_at`의 KST 날짜. Dn = D0 + n일.
- **공부한 날**: 그 `stat_date`에 `focus_sec >= 600`인 세션이 하나 이상. 상수는 `StudySessionThresholds`를 공유한다.
- **하루 순공**: 그 `stat_date` 세션들의 `focus_sec` 합.
- **제외 유저**: 설정값 `metrics.report.excluded-user-ids`(팀 내부 계정 등, 기본 빈 목록). 데모·테스트 유저를 빼는 기존 장치는
  없다(랭킹도 탈퇴 유저만 뺀다, 데모 유저는 dev 전용). 탈퇴(`status = DELETE`)는 아직 경로가 없지만, 생기면 코호트에 남겨 이탈로
  센다(빼면 리텐션이 부푼다).
- **비율 표기**: `64% (9/14)`. 분모가 0이면 `—`.
- **조회 범위**: 원본 사실은 `[A−61, A]` 62일. D30 코호트(가입 `[A−57, A−30]`)와 "연속 Top" 판정(A−2까지 재계산)을 덮는다.

## 4. 섹션 정의

### 4.1 🕐 가입
- 어제 가입 수와 전주 같은 요일(A−7) 대비 증감.
- 최근 7일(`[A−6, A]`) 가입을 3시간 구간 8개(00–03 … 21–24)로 나눈 텍스트 막대와 피크 구간.
- 공부 분야(`category`: 수능·공시·자격증 등, 미입력 포함) 비율. 로그인 방식은 지금 전원 게스트(`DEVICE`)라 쓰지 않는다.
- 유입: Amplitude 유저 속성의 광고 클릭 유입 여부(`initial_fbclid`·`initial_gclid` 등 클릭 ID 존재)와 `initial_utm_source` 상위 값.
  Amplitude 속성이 없는 유저는 "미수집"으로 센다.
- 어제 가입자 중 어제 공부한 날이 된 비율(가입 당일 활성화).

### 4.2 📈 리텐션
- **Dn (n = 1, 7, 30)**: 가입일 `[A−n−27, A−n]`(Dn이 이미 지난 최근 28일치 가입자) 중 Dn이 공부한 날인 비율.
- 옆에 지난주 값(같은 계산을 기준일 A−7로).
- **첫 주 미사용**: 가입일 `[A−6−27, A−6]` 중 D0~D6 공부한 날 0일인 비율.

### 4.3 👥 고정 세그먼트
- **코호트 C**: 가입일 `[A−40, A−13]`(2주차까지 끝난 최근 28일치 가입자).
- **S1**: D0~D6 중 공부한 날 ≥ 3일. **2주차 리텐션**: D7~D13 중 공부한 날 ≥ 1일.
- 표시: S1 인원, S1 2주차 리텐션 vs 나머지, %p 차이, 상관.
- **주로 한 행동**: 규칙 카탈로그(§5)의 행동형 규칙(묶음 D~I)을 행동으로 쓰고, **S1의 30% 이상이 한 행동** 중 S1 사용률 − 나머지
  사용률이 큰 순 5개. S1이 5명 미만이면 "표본 부족".
- **어떤 사람**: S1과 나머지의 공부 분야, 프로필 입력 비율(분야·목표 중 하나라도), 가입 시간대(3시간 구간), OS, 광고 유입 비율을 나란히.

### 4.4 🔍 자동 탐색
- 코호트 C에 규칙 카탈로그 40개를 모두 적용한다(행동 = D0~D6, 결과 = 2주차 리텐션).
- §6 노출 조건을 통과한 규칙을 상관 내림차순(동률이면 해당 인원 많은 순) Top 3.
- 규칙마다: 인원(코호트 대비 %), 상관과 강도 라벨, 2주차 리텐션 vs 안 한 사람, "남은 사람 R명 중 k명이 했음".
- 같은 계산을 A−1, A−2 기준으로도 돌려 세 번 모두 Top 3이면 "3일 연속 Top" 표시.
- 꼬리: "규칙 40개 중 통과 n개 · 제외: 사람 부족 a · 너무 흔하거나 드묾 b · 상관 약함 c · Amplitude 미수집 d"와
  "상관관계이지 인과는 아님. 실험으로 확인할 후보" 주의 문구. 통과가 0개면 "오늘은 조건을 통과한 규칙 없음(표본 부족)".

### 4.5 🧑‍🎓 어제 공부한 유저
- 대상: A가 공부한 날인 유저.
- 단계(위에서 먼저 맞는 하나):

| 단계 | 조건 |
|---|---|
| 신규 | A − D0 ≤ 6 |
| 복귀 | `[A−14, A−1]` 공부한 날 0일 |
| 정착 | `[A−6, A]` 공부한 날 ≥ 3일 |
| 가벼운 사용 | 나머지 |

- 한 줄 프로필: `닉네임(#id) · 가입 N일차 · 연속 K일 · 어제 순공 h시간 m분 · 소셜/싱글 위주 · 과목 n개 · 분야(목표) · 최근 7일 앱 실행 n일`.
  목표는 자유 입력이라 입력한 경우만 괄호로 붙인다.
  연속 K일은 A부터 거꾸로 센 연속 공부한 날(62일 상한, 넘으면 "60일+"). 앱 실행 일수는 Amplitude 미수집이면 생략.
- 단계 안에서 어제 순공 내림차순. 메시지 전체 100줄 상한, 초과분은 "외 N명".
- 마지막 줄: Amplitude 기준 A에 `app_launched`·`app_foregrounded`가 있지만 공부한 날이 아닌 유저 수("앱만 열고 공부 안 함").

### 4.6 📊 본 메시지(요약)
매일 훑어보는 줄이라 **용어 없이 풀어 쓴 문장**으로 쓴다(2026-10-13 사용자 피드백 — D1·세그먼트·상관 같은 용어는 설명 없이
읽히지 않았다). 정확한 정의·분모·코호트 기간은 스레드에만 둔다.
```
📊 10/12(월) 일일 지표
• 가입 38명 (지난주 같은 요일보다 5명 많음) · 가장 많이 가입한 시간 21~24시 · 어제 공부한 사람 34명
• 다시 와서 공부한 비율: 가입 다음 날 31% · 7일째 18% · 30일째 9%  (지난주 33% · 16% · 9%)
• 첫 주에 3일 이상 공부한 사람은 둘째 주에도 64%가 공부 (못 채운 사람은 12%)
• 남는 사람과 가장 관련 깊은 행동: 첫 주에 소셜 방 3번 이상 (연관 강함, 3일째 1위)
• 어제 공부한 34명: 새로 온 사람 12 · 꾸준한 사람 15 · 돌아온 사람 3 · 가끔 오는 사람 4
```
- 단계 이름은 요약에서 풀어 쓴다: 신규 → 새로 온 사람, 정착 → 꾸준한 사람, 복귀 → 돌아온 사람, 가벼운 사용 → 가끔 오는 사람.
- 상관 강도는 "연관 강함/보통/약함"으로, "N일 연속 Top"은 "N일째 1위"(1위일 때) 또는 "N일째 상위권"으로 쓴다.
- 섹션 계산이 실패하면 해당 줄은 "계산 실패"로 쓴다.
- 자동 탐색에는 고정 규칙(공부한 날 3일+)도 후보로 남기고, Top 3에 들면 "(고정 세그먼트)"를 붙인다 — 다른 규칙 대비 순위
  자체가 정보다.

## 5. 규칙 카탈로그 (40개, 판정 기간 D0~D6)

규칙 이름은 코드 파라미터에서 생성한다(예: `consecutive(2, 60분)` → "첫 7일 중 2일 연속 하루 순공 1시간+"). 메시지 설명과
판정이 어긋날 수 없게 하기 위해서다.

| 묶음 | 규칙 | 출처 |
|---|---|---|
| A 연속 공부 (12) | "공부한 날이면서 하루 순공 ≥ M"인 날이 N일 연속. N ∈ {2,3,4} × M ∈ {10분, 30분, 1시간, 2시간} | 서버 |
| B 공부한 날 수 (4) | 공부한 날 ≥ K일, K ∈ {2,3,4,5} (K=3은 S1과 같음) | 서버 |
| C 첫날 (2) | D0 하루 순공 ≥ 30분 / ≥ 1시간 | 서버 |
| D 서버 기능 (5) | 소셜 방 참여 ≥ 1회 / ≥ 3회, 과목 등록 ≥ 3개, 할 일 완료 ≥ 3개, D-day 설정 | 서버 |
| E Amplitude 행동 (6) | `app_launched` 있는 날 ≥ 3일, `ambient_sound_changed` ≥ 1, `planner_opened` ≥ 1, `invite_shared` ≥ 1, `guide_finished` ≥ 1, `ranking_board_viewed` ≥ 1 | Amplitude |
| F 공부 습관 (5) | 세션당 일시정지 수 ≤ 코호트 중앙값, 순공 1시간당 `study_session_distracted` ≤ 코호트 중앙값, 평균 세션 순공 ≥ 30분, 순공의 절반 이상이 21~03시 시작 세션, 절반 이상이 05~09시 시작 세션 | 서버(딴짓만 Amplitude) |
| G 화면 설정 (3) | `session_simple_mode_toggled:on` ≥ 1, `session_orientation_changed:landscape` ≥ 1, `social_room_camera_toggled:on` ≥ 1 | Amplitude |
| H 초대 유입 (1) | D0~D1에 `invite_deep_link_opened` ≥ 1 | Amplitude |
| I 기록·알림 (2) | `records_date_selected` ≥ 1, `push_notification_opened` ≥ 1 | Amplitude |

- 중앙값 규칙(F의 앞 두 개)은 해당 지표를 계산할 수 있는 유저(세션 1개 이상)만 대상이고, 나머지는 "안 함"으로 본다.
- Amplitude 규칙은 판정 기간의 Amplitude 창이 하나라도 미수집이면 그날 평가에서 빠진다("Amplitude 미수집"으로 집계).

## 6. 노출 조건과 순위

2×2 표: a = 함·남음, b = 함·안 남음, c = 안 함·남음, d = 안 함·안 남음, N = a+b+c+d.

- **사람 수**: a+b ≥ 5 그리고 c+d ≥ 5.
- **흔함·드묾**: 0.10 ≤ (a+b)/N ≤ 0.90. 거의 모두가 하는 행동은 남는 사람을 못 가르고(Compass의 "세션 시작" 예), 소수만 하는 행동은
  믿을 수 없다.
- **상관**: phi = (ad − bc) / √((a+b)(c+d)(a+c)(b+d)) ≥ 0.2. 분모가 0이면 탈락(상관 약함으로 집계).
- **강도 라벨**: ≥ 0.4 강함, 0.3~0.4 보통, 0.2~0.3 약함(Amplitude Compass 구간).
- **순위**: phi 내림차순, 동률이면 a+b 내림차순, 그다음 카탈로그 순서.
- 음의 상관(하면 덜 남음)은 이번 범위에서 보여주지 않는다.

출처: Amplitude Compass 문서(aha moment, interpret your chart), Reforge "상관 0.2 이상부터 실험 가치".

## 7. Amplitude 적재

### 7.1 실측 사실 (2026-10-13 브라우저 확인)
- 조직 `long-mode-988791`, US 데이터센터, Free 플랜(월 2M). 프로젝트 prod/dev 분리 → prod 키 사용.
- prod 10/1~13 33.3k건, 최근 하루 4~6천 건. user_id = 우리 DB userId, 이벤트의 약 93%에 붙어 있다.
- `acquisition_channel` 속성은 prod에 없다. `initial_utm_*`·`initial_fbclid`(10/7~)는 있으나 값 채움 여부 미확인(스파이크에서 확인).
- Export API: `GET https://amplitude.com/api/2/export?start=YYYYMMDDTHH&end=YYYYMMDDTHH`, Basic(api_key:secret_key).
  **기간은 server_upload_time 기준**, 수신 후 최대 2시간 지연, 응답은 zip(시간별 gzip JSON 줄 파일), 404 = 데이터 없음, 4GB·365일 상한.

### 7.2 창(window)
- 창 = KST 하루에 해당하는 수신 시각 24시간. KST 날짜 W의 창은 `start = (W−1)T15`, `end = WT14`(UTC 시 단위, 양끝 포함).
- 처리 대상 = `[오늘 − backfillDays, 어제]` 중 처리 기록이 없는 W, 그리고 창 끝 + 3시간 < 지금. 오래된 것부터.
  첫 배포는 이 규칙으로 과거를 채우고, 실패한 창은 다음 실행이 다시 집는다.
- 창 하나 = 다운로드 하나 + 트랜잭션 하나(처리 기록 insert + 횟수 upsert 가산). 처리 기록은 W를 PK로 `ON CONFLICT DO NOTHING`
  선점 후 0행이면 롤백 — 배포 중 태스크 둘이 같은 창을 처리해도 한 번만 센다.

### 7.3 파싱
- 응답을 임시 파일로 받아 `ZipFile`로 연다(중앙 디렉터리까지 검증 — 순차 읽기로는 항목 경계에서 잘린 zip이 정상 종료처럼
  보인다). `.gz` 항목을 하나씩 `GZIPInputStream` → 줄 단위로 읽고 파일은 지운다. 원본은 저장하지 않는다.
- `user_id`가 없으면 익명(가입 전 웹 방문, 평소 약 7%)으로 건너뛴다. 깨진 JSON·`event_type`/`event_time` 없음·형식 다름·
  숫자가 아닌 `user_id`는 깨진 줄로 건너뛴다. **깨진 줄이 적재한 줄보다 많으면** Export 형식이 바뀐 것으로 보고 창을 실패시킨다.
- `.gz` 항목이 하나도 없는 zip도 실패다(데이터 없음은 404로 온다).
- 문자열의 널 문자는 지우고, `event_key` 120자·`utm_source` 200자·`os_name` 50자로 자른다(넘으면 창 전체가 롤백되고 재시도마다 실패).
- 날짜 = `event_time`(UTC) → KST 날짜. 늦게 수신된 이벤트도 발생일로 들어간다.
- 키 = `event_type`. 아래 3개는 속성값 키를 추가로 센다.

| 이벤트 | 조건 | 추가 키 |
|---|---|---|
| `session_simple_mode_toggled` | `event_properties.on == true` | `session_simple_mode_toggled:on` |
| `session_orientation_changed` | `event_properties.orientation == "landscape"` | `session_orientation_changed:landscape` |
| `social_room_camera_toggled` | `event_properties.on == true` | `social_room_camera_toggled:on` |

- 유저 속성: `user_properties.initial_utm_source`, 광고 클릭 ID(`initial_fbclid`·`initial_gclid`·`initial_ttclid` 등 값이
  비어 있지 않고 `EMPTY`가 아님), `os_name`. **값마다 그 값을 본 시각을 따로 두고 더 늦게 본 non-null이 이긴다**(같은 시각이면
  먼저 있던 값). 창 안(Java 병합)과 창 사이(SQL upsert)가 같은 규칙이다. 광고 클릭은 한 번이라도 보이면 남는다.
- 다운로드 크기 상한 200MB(설정값). 넘으면 그 창을 중단하고 예외 → Sentry.

### 7.4 테이블 (V28)
```sql
CREATE TABLE amplitude_export_window (
    window_date  DATE        PRIMARY KEY,           -- KST 날짜 W
    event_count  INT         NOT NULL,              -- 적재한 이벤트 수(user_id 있는 것)
    skipped      INT         NOT NULL,              -- user_id 없거나 숫자 아님
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE amplitude_daily_event (
    user_id    BIGINT       NOT NULL,
    event_date DATE         NOT NULL,
    event_key  VARCHAR(120) NOT NULL,
    count      INT          NOT NULL,
    PRIMARY KEY (user_id, event_date, event_key)
);
CREATE INDEX idx_amplitude_daily_event_date ON amplitude_daily_event (event_date);
CREATE TABLE amplitude_user_attribute (
    user_id       BIGINT       PRIMARY KEY,
    utm_source    VARCHAR(200),
    utm_seen_at   TIMESTAMPTZ,
    ad_click      BOOLEAN      NOT NULL DEFAULT false,
    os_name       VARCHAR(50),
    os_seen_at    TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
```
user_id에 외래키를 걸지 않는다 — Amplitude에는 탈퇴·타 환경 id가 섞일 수 있고, 리포트가 users와 맞춰 거른다.

### 7.5 "수집됨" 판정
리포트는 날짜 W의 창 처리 기록이 있으면 W를 Amplitude 수집일로 본다. 규칙 판정 기간·프로필 기간에 미수집일이 있으면 그
Amplitude 항목은 "미수집"으로 표시하고 0으로 세지 않는다.

## 8. 코드 구조

```
metrics/
  amplitude/  AmplitudeExportClient   다운로드·zip/gzip 스트리밍·줄 파싱 → WindowAggregate
              AmplitudeIngestService  처리 대상 창 계산, 창별 트랜잭션 적재
              AmplitudeIngestJob      09:00 스케줄 → MetricsJobRunner에 제출
              엔티티·리포지토리 (export_window, daily_event, user_attribute)
              AmplitudeQueryService   기간 내 횟수·속성·수집일 조회 (리포트용)
  report/     ReportDataLoader        도메인 서비스·Amplitude 조회 → UserTimeline 목록
              UserTimeline            유저 한 명의 62일 사실(일별 순공·공부한 날·세션·행동 횟수·속성)
              analyzer/ SignupAnalyzer · RetentionAnalyzer · SegmentAnalyzer · RuleExplorer · ActiveUserClassifier
              rule/     SegmentRule(이름 + 판정), RuleCatalog(40개), Correlation(2×2·phi)
              ReportRenderer          결과 → 요약 1 + 스레드 5 문자열
              DailyReportService      조립·섹션별 실패 격리·발송
              DailyReportJob          10:00 스케줄 → MetricsJobRunner
  job/        MetricsJobRunner        단일 스레드 실행기(빈 아님), 예외 → Sentry, run-once 플래그
  slack/      SlackNotifier(post → ts, reply(ts, text)), SlackBotNotifier(chat.postMessage)
```

- **도메인 조회**: metrics는 다른 도메인 테이블을 읽지 않는다. user·studysession·room·subject·dday 서비스에 "기간 내
  사실"을 돌려주는 메서드를 하나씩 추가한다(계획 참조). 세션의 소셜 여부는 기존 `findQualifyingSessions`와 같은 방식(같은
  시간대에 확정된 방 참여가 있었나)으로 studysession 쿼리가 판정한다.
- **실행기**: `TaskScheduler`·`Executor` 타입 빈을 새로 만들지 않는다. 만들면 Boot의 기본 스케줄러·실행기 자동 구성이 물러나
  다른 스케줄러까지 그 빈으로 돈다. `MetricsJobRunner`가 내부에 단일 스레드 `ExecutorService`를 갖고, 스케줄 메서드는 제출만
  하고 바로 반환한다(prod 스케줄러 풀 2개를 붙잡지 않음).
- **삭제**: `DailyReport`, `HeavyUser`, `CohortFirstWeek`, `RoomVsSingleFocus`, `FocusStat`, `UserSessionDetail`, `QualifyingSession`,
  `NewUser`, `SlackWebhookNotifier`와 이들만 쓰는 조회 메서드·테스트. ADR-0012가 가리키는 `HeavyUserQueryIntegrationTest`는
  헤비유저 조회가 사라지므로 함께 지우고, 자정 분할 비병합 규칙은 `UserTimeline` 단위 테스트가 이어받는다.

## 9. Slack

- `POST https://slack.com/api/chat.postMessage`, `Authorization: Bearer <SLACK_BOT_TOKEN>`, 본문 `{channel, text, thread_ts?,
  unfurl_links:false, unfurl_media:false}`. 응답 `ok:false`면 `error` 코드로 `SlackNotificationException`. HTTP 429는
  `Retry-After`초 대기 후 재시도.
- 본 메시지 실패: 기존처럼 `@Retryable` 2회 → Sentry, 스레드는 안 단다.
- 스레드 댓글 실패: 재시도 후 그 댓글만 건너뛰고 다음으로. 댓글 사이 1초 간격(설정값, 테스트 0).
- 토큰·채널이 비면 발송기 비활성(WARN), 리포트 계산도 건너뛴다.
- 봇 앱 권한: `chat:write`, 채널 초대 필요.

## 10. 실패 처리

| 상황 | 처리 |
|---|---|
| Export 실패(타임아웃·5xx·깨진 zip) | 창 롤백, 예외 → Sentry. 다음 실행이 재시도 |
| Export 404 | 0건으로 처리 기록 + WARN·Sentry 경고(prod에서 404는 설정 오류일 수 있다) |
| 200인데 zip이 아님·잘린 zip·`.gz` 없음·깨진 줄 과반 | 창 실패(처리 기록 없음), 다음 실행 재시도 |
| 다운로드 마감(`download-timeout`, 기본 10분) 초과 | 창 실패. 마감은 요청 시작부터의 전체 시간이다(Spring 7 동작) |
| 크기 상한 초과 | 창 중단, Sentry("배치 분리 검토 신호") |
| 어제 창 미수집 | Amplitude 항목 "미수집", 탐색에서 Amplitude 규칙 제외 |
| 분석기 예외 | 그 섹션만 "계산 실패", Sentry. 나머지 발송 |
| 메시지 길이 | 스레드당 40,000자 상한 failsafe(기존 방식), 어제 유저 100줄 상한 |
| 중복 발송 | 태스크 2개면 두 번 온다(기존과 같음, 무해). 적재는 창 선점으로 중복 없음 |

## 11. 설정

```yaml
metrics:
  slack:
    bot-token: ${SLACK_BOT_TOKEN:}
    channel-id: ${SLACK_CHANNEL_ID:}
    thread-delay-ms: 1000
  report:
    excluded-user-ids: ${REPORT_EXCLUDED_USER_IDS:}
  amplitude:
    api-key: ${AMPLITUDE_API_KEY:}
    secret-key: ${AMPLITUDE_SECRET_KEY:}
    base-url: https://amplitude.com
    backfill-days: 60
    max-download-bytes: 209715200
    download-timeout: PT10M   # 첫 바이트 + 전송 + 파싱 전체 마감
app:
  report:
    enabled: true        # prod만. 스케줄 잡 두 개를 함께 켠다
    run-once: false      # 로컬에서만 true — 기동 시 적재→리포트 한 번
```
키가 비면 각각 비활성(WARN). `run-once`는 값이 없으면 꺼진다(BY-640).

## 12. 테스트

- 단위: `UserTimeline`(자정 분할 비병합, 공부한 날·연속 일수), 분석기 5개(코호트 경계, 노출 조건 경계 5명·10%·90%·0.2,
  phi 값, 중앙값 규칙, 단계 우선순위, Amplitude 미수집 분기), 규칙 이름 생성, `ReportRenderer` 출력 문자열.
- Amplitude: 테스트에서 만든 zip 고정 데이터로 파서(user_id 없음 건너뛰기, 속성값 키, KST 날짜, 광고 클릭 ID), 404,
  크기 상한. Testcontainers로 적재 서비스(재실행 무중복, 누락 창 복구, 늦은 이벤트, 창 선점 경합).
- 외부 호출: `MockRestServiceServer`로 Amplitude·Slack(본 메시지 ts로 스레드, `ok:false`, 429).
- 도메인 조회 메서드: Testcontainers 통합 테스트.
- `./gradlew check`(ArchUnit 포함).

## 13. 배포 순서

0. **스파이크**: 로컬에서 prod Export 하루치를 받아 크기·시간·UTM 값 채움을 확인한다. 코드는 버리고 결과를 이 문서 §7.1에 적는다.
1. **PR ① 적재**: V28, amplitude 패키지, 적재 잡, 실행기. 리포트는 그대로. 배포 전 prod에 `AMPLITUDE_API_KEY`·`AMPLITUDE_SECRET_KEY`.
   배포 후 60일 백필·매일 적재를 며칠 확인.
2. **PR ② 리포트**: 도메인 조회, report 패키지, Slack 봇, 기존 지표 삭제. 배포 전 Slack 앱(`chat:write`)·채널 초대,
   `SLACK_BOT_TOKEN`·`SLACK_CHANNEL_ID`. dev는 dev Amplitude 키 + 테스트 채널로 먼저 확인.
3. 웹훅 설정(`SLACK_WEBHOOK_URL`)은 PR ② 배포 확인 뒤 제거.

## 14. 알려진 한계

- 상관은 인과가 아니고 표본이 작다. 40개 규칙 중 우연히 높은 규칙이 섞일 수 있어 "규칙 40개 중"과 "연속 Top"을 같이 보인다.
- 웹에서 연 초대 링크·스토어 이동 같은 익명 이벤트(약 7%)는 유저와 연결되지 않는다.
- 유입 채널은 광고 클릭 ID·UTM 대용이다. 자기 신고 채널은 FE 연결 후 추가한다.
- 랭킹·타임랩스 이벤트는 prod 출시 후부터 쌓인다. 그전엔 해당 규칙이 "사람 부족"으로 빠진다.
