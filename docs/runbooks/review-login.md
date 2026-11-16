# 스토어 심사용 로그인 운영 (BY-1300, ADR-0048)

설정은 DB의 `review_login` 한 행이다. 값을 바꾸면 바로 반영되고 배포는 필요 없다.

## 처음 넣기

비밀번호의 BCrypt 해시를 만든다(평문은 스토어 심사 정보에만 적는다). 비밀번호는 명령 인수로 넘기지 않고 물어볼 때 입력한다 — 인수로 넘기면 셸 이력에 남는다.

```bash
htpasswd -nBC 10 "" | tr -d ':\n' | sed 's/^\$2y/$2a/'
```

```sql
INSERT INTO review_login (id, login_id, password_hash, released_version, initial_points, initial_item_codes)
VALUES (1, 'review', '<해시>', '<지금 스토어에 출시된 버전, 예: 26.46.0>', 5000, '<상품 코드,쉼표로>');
```

## 심사에 올릴 때

비밀번호를 새로 정해 해시를 바꾼다.

```sql
UPDATE review_login SET password_hash = '<새 해시>', updated_at = now() WHERE id = 1;
```

## 출시한 뒤 (자동)

서버가 매시 정각에 App Store의 출시 버전을 읽어 더 높으면 올린다(BY-1312). 보통은 할 일이 없다. 조회 API가 캐시를 써서 몇 시간 늦을 수 있다. 반나절이 지나도 그대로면
(조회 실패 로그 `App Store 출시 버전을 읽지 못했습니다`) 아래처럼 직접 올린다.

## 직접 올리기

출시된 버전으로 올린다. 그 버전의 앱에서는 입구가 사라진다.

```sql
UPDATE review_login SET released_version = '26.47.0', updated_at = now() WHERE id = 1;
```

## 끄기

```sql
UPDATE review_login SET released_version = NULL WHERE id = 1;
```

## 처음 상태

포인트(`initial_points`), 상품(`initial_item_codes`), 그리고 마스코트 7종 전부의 보유다. 마스코트는 설정 없이 늘 전부 열린다.

## 처음 상태를 바꿨을 때

이미 만들어진 심사 계정에는 반영되지 않는다. 앱에서 심사 계정으로 로그인해 계정을 삭제한 뒤 다시 로그인하면 새 값으로 만들어진다.

## 막혔을 때

같은 IP에서 10분에 5번 틀리면 그 IP가 429를 받는다. 풀려면 그 줄을 지운다.

```sql
DELETE FROM review_login_failure;
```
