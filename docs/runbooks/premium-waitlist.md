# 프리미엄 사전 신청 이메일 파기 (BY-1394, ADR-0051)

페이크도어 실험(BY-1392)에서 받은 이메일을 지울 때 쓴다. 서버에는 파기 스케줄러가 없다. 사람이 아래 SQL을 실행한다.

## 언제

- 출시 알림을 보낸 직후, 또는
- 늦어도 **2027-05-31**(실험 종료 2026-11-30 뒤 6개월)까지.

둘 중 먼저 오는 날이다. 알림 발송 체크리스트에 이 문서 링크를 넣어 둔다.

## 하기 전에

먼저 집계(`docs/analytics/premium_waitlist.sql`)를 뽑아 결과를 저장한다. 파기 뒤에도 가격·위치·나이 구분·기대 기능은 남지만, 이메일 기반 확인은 못 한다.

```sql
-- 지울 대상 수 (읽기 전용)
SELECT count(*) FILTER (WHERE email IS NOT NULL) AS emails, count(*) AS rows FROM premium_waitlist;
```

## 파기 (권장: 이메일만 지우고 집계용 행은 남긴다)

```sql
BEGIN;
UPDATE premium_waitlist SET email = NULL, consented_at = NULL, updated_at = now() WHERE email IS NOT NULL;
-- 건수를 확인한다. 위 count의 emails와 같아야 한다
SELECT count(*) AS remaining_emails FROM premium_waitlist WHERE email IS NOT NULL;  -- 0이어야 한다
COMMIT;
```

## 행까지 모두 지우기 (집계도 필요 없을 때만)

```sql
DELETE FROM premium_waitlist;
```

## 사용자 한 명이 지워 달라고 할 때

탈퇴하면 `UserDataHandler`가 행을 지운다. 탈퇴 없이 요청하면 아래를 실행한다.

```sql
UPDATE premium_waitlist SET email = NULL, consented_at = NULL, updated_at = now() WHERE user_id = :user_id;
```

## 확인

`SELECT count(*) FROM premium_waitlist WHERE email IS NOT NULL;`이 0이면 끝이다. 백업 사본에는 남아 있을 수 있으니 DB 백업 보관 주기(보존 기간이 끝나면 사라진다)를 같이 확인한다.
