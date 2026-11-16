-- BY-1299: RevenueCat 사용자 ID로 쓰는 결제용 ID. 회원번호는 추측할 수 있어, 앱의 공개 SDK 키와 남의 회원번호만으로
-- 그 사람의 결제 정보를 보거나 구매를 옮겨 갈 수 있었다. 사용자마다 무작위 값을 주고 본인에게만 내려 준다.
-- 기본값이 행마다 달라 테이블을 다시 쓰지만 users는 작다. 오래 걸리는 쿼리 뒤에 줄 서서 서비스를 막지 않게 락 대기를 끊는다.
SET lock_timeout = '5s';
ALTER TABLE users ADD COLUMN purchase_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uq_users_purchase_id ON users (purchase_id);
RESET lock_timeout;
