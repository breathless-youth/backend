-- BY-1297: 처리한 구매 복원(RevenueCat TRANSFER) 알림. 알림에는 거래가 없고 옮겨 준 사람과 받은 사람만 있어, 옛 알림이 다시 오면
-- 그 뒤에 되돌아간 구매를 또 옮길 수 있다. 알림 ID를 적어 한 번만 처리한다.
CREATE TABLE purchase_transfer_event (
    event_id varchar(128) PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);
