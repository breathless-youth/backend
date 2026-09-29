# ADR-0024: Sentry에서 봇 스캐닝 404를 버린다

- 상태: 승인
- 날짜: 2026-09-29
- ADR-0011을 부분 대체한다 (4xx 전부 수집 → `/api/` 밖 경로의 `NoResourceFoundException`만 제외)

## 맥락

Sentry 무료 플랜의 월 에러 한도(5,000건)를 9/16~10/15 사용 기간 도중에 다 썼다. 초기화 전까지는
실제 장애가 나도 Sentry에 남지 않는다.

최근 30일 `focusmakers-api` 이벤트의 대부분은 외부 스캐너가 보낸 없는 경로 요청이었다.
`NoResourceFoundException: No static resource livewire/update` 약 4,200건,
`wp-content/.../eval-stdin.php` 24건 등이다. ADR-0011이 4xx를 가리지 않고 수집하기로 했기 때문에
이 노이즈가 그대로 쿼터를 먹었다.

## 결정

- **`BeforeSendCallback` 빈(`BotScanEventFilter`)에서 `/api/` 밖 경로의 `NoResourceFoundException`을
  버린다.** 판정은 예외의 `getResourcePath()`(선행 `/` 없음, 예: `livewire/update`)로 한다. Spring이 끝 슬래시를
  떼므로 `/api/` 요청은 `api`로 들어오고, 이것도 `/api/` 아래로 본다
- **`/api/` 아래의 없는 경로는 계속 보낸다.** 우리 앱은 `/api/` 밖을 부르지 않으므로 `/api/` 아래의 404는
  앱이 잘못된 URL을 부르는 버그의 신호일 수 있다. 봇이 `/api/`까지 두드리기 시작하면 그때 다시 본다
- **도메인 예외는 계속 보낸다.** 요청에는 "복구할 세션이 없습니다"·"진행중인 세션이 없습니다"·닉네임 중복·
  초대 코드 불일치도 빼 달라고 했지만, ADR-0011의 취지(클라이언트 로직 버그 신호)대로 남긴다. 쿼터 소진의
  주원인은 봇 404이고, 이 넷은 합쳐도 소수다

### 검토한 대안

- **`sentry.ignored-exceptions-for-type`(yaml)**: 코드 없이 되지만 예외 타입 단위라 경로로 가를 수 없어
  `/api/` 아래의 404까지 사라진다. 클래스명 문자열이라 오타도 조용히 무시된다
- **`NoResourceFoundException` 전부 제외**: 더 단순하지만 앱의 잘못된 경로 호출도 안 보이게 된다
- **도메인 4xx까지 제외(= ADR-0010으로 복귀)**: 위의 이유로 채택하지 않았다

## 결과

- HTTP 응답은 바뀌지 않는다. 캡처 직전에 이벤트를 버릴 뿐이다
- 이벤트 레벨이 `FATAL`/`handled=false`로 보이는 문제(ADR-0011 결과)는 그대로다. 필요해지면 같은
  `beforeSend`에서 레벨을 조정한다
- 빈 등록과 경로 형식은 `BotScanEventFilterWiringTest`로 못 박았다 — 자동 설정이 필터를 `beforeSend`로
  거는지, 실제 404 요청의 리소스 경로가 필터가 가정한 형태로 나오는지 확인한다
- 쿼터가 초기화되는 10/15 이후 Sentry에 봇 404가 새로 들어오지 않는지 확인한다 (BY-789)
