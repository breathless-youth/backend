# 포인트·세트 환불 요청 처리 (BY-1304, ADR-0047 30번)

약관(2026-11-23 시행) 제13·14조의 환불 요청을 판단할 때 쓴다. 서버는 기록만 남기고, 환불은 사람이 실행한다.
조회 SQL은 모두 읽기 전용이다. `:요청시각`에는 사용자가 환불을 요청한 때를 넣는다(처리한 날이 아니다).

## 기록이 남는 곳

`point_purchase` 한 행이 스토어 결제 한 건이다.

| 칸 | 뜻 |
|---|---|
| `transaction_id`, `store` | 스토어 거래 ID(RevenueCat이 준 값) |
| `points`, `bonus_points`, `paid_remaining` | 그 건으로 산 유료 포인트, 덤(무료), 아직 쓰지 않은 유료 포인트 |
| `item_id`, `item_first_worn_at` | 결제로 산 상품(세트)과 처음 입은 때. NULL이면 입기 전 |
| `purchased_at`, `status` | 결제 시각, `GRANTED`·`REFUNDED` |
| `user_id`, `owner_deleted_at` | 주인. 계정을 삭제하면 `user_id`가 비고 삭제 시각이 남는다 |
| `manual_refunded_at`, `manual_refund_points` | 스토어 밖에서 직접 환불한 기록(사람이 적는다) |

알아 둘 것:

- 유료 포인트는 무료 포인트를 다 쓴 뒤에, 먼저 충전한 건부터 쓴다.
- 다른 건이 스토어에서 환불되며 생긴 빚을 갚느라 쓰지 않은 건의 `paid_remaining`이 줄 수 있다. 빚은 무료 포인트부터 갚는다.
  이렇게 줄어든 건도 "일부라도 쓴 건"으로 보고 7일 환불을 하지 않는다.
- 가격과 통화는 DB에 없다. 실제 결제 금액은 스토어·RevenueCat에서 본다. 덤은 환불하지 않는다.
- `purchased_at`이 비어 있는 옛 행은 `created_at`(알림을 받은 때)을 쓴다.

## 거래 ID 찾기

사용자가 보내는 영수증 번호가 `transaction_id`와 다를 수 있다.

- App Store: 영수증의 Order ID는 거래 ID가 아니다. App Store Server API의 주문 번호 조회(Look Up Order ID)나 RevenueCat
  대시보드의 고객 화면에서 거래 ID를 찾는다.
- Google Play: 주문 번호(`GPA.`로 시작)가 RevenueCat의 거래 ID와 같은지 처음 처리할 때 확인하고 이 문서를 고친다.
- 계정이 있으면 회원번호로 찾는 편이 빠르다.

## 쓰지 않은 유료 포인트 (결제 뒤 7일 안)

```sql
SELECT store, transaction_id, store_product_id, points, paid_remaining,
       COALESCE(purchased_at, created_at) AS purchased_at, paid_remaining = points AS refundable
FROM point_purchase
WHERE user_id = :회원번호 AND item_id IS NULL AND status = 'GRANTED' AND manual_refunded_at IS NULL
  AND COALESCE(purchased_at, created_at) > CAST(:요청시각 AS timestamptz) - interval '7 days'
ORDER BY 6;
```

**하나도 쓰지 않은 건(`paid_remaining = points`)만 환불한다**(2026-11-16 결정). 일부라도 쓴 건(`paid_remaining < points`)은
환불하지 않는다 — 스토어 환불은 건 전체를 되돌리므로, 일부 쓴 건을 환불하면 이미 쓴 포인트까지 거두게 된다. 위 결과의 `refundable`이 true인 건만 환불한다.

## 입기 전인 세트 (결제 뒤 7일 안)

```sql
SELECT p.transaction_id, s.code, COALESCE(p.purchased_at, p.created_at) AS purchased_at, p.item_first_worn_at
FROM point_purchase p JOIN shop_item s ON s.id = p.item_id
WHERE p.user_id = :회원번호 AND p.status = 'GRANTED'
  AND COALESCE(p.purchased_at, p.created_at) > CAST(:요청시각 AS timestamptz) - interval '7 days';
```

## 계정을 삭제한 사람의 남은 유료 포인트 (10%를 빼고 환불)

```sql
SELECT store, transaction_id, store_product_id, points, paid_remaining,
       COALESCE(purchased_at, created_at) AS purchased_at, owner_deleted_at
FROM point_purchase
WHERE transaction_id = :거래ID AND owner_deleted_at IS NOT NULL
  AND status = 'GRANTED' AND manual_refunded_at IS NULL;
```

환불 금액 = 그 건의 결제 금액 × `paid_remaining` / `points` × 0.9.

## 환불을 실행하면

- **스토어에서 환불**: RevenueCat이 `CANCELLATION` 알림을 보내고, 서버가 그 건의 유료 포인트와 덤을 모두 거두거나(잔액이 음수가 될
  수 있다) 세트를 벗기고 거둔다. 결과는 `status = 'REFUNDED'`로 확인한다. 스토어가 일부만 환불했을 때 어떤 알림이 오는지는 처음
  처리할 때 확인한다.
- **스토어 밖에서 직접 환불**(계좌 이체 등): 알림이 오지 않는다. 계정을 삭제한 사람의 건만 이렇게 처리하고, 환불한 뒤 바로 적는다.

```sql
UPDATE point_purchase
SET manual_refunded_at = now(), manual_refund_points = paid_remaining, paid_remaining = 0
WHERE transaction_id = :거래ID AND owner_deleted_at IS NOT NULL AND user_id IS NULL
  AND manual_refunded_at IS NULL AND paid_remaining > 0;
```

계정이 있는 사람은 잔액과 기록을 맞춰야 해서 이 UPDATE를 쓰지 않는다. 스토어 환불로 처리한다.
