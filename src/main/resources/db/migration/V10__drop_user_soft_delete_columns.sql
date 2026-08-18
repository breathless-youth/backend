-- 탈퇴는 하드 삭제("즉시 전부 삭제" 정책 — BY-383)로 확정되어 소프트 삭제 잔재를 제거한다.
-- status는 ACTIVE 단일값만 존재했고 delete_at은 어디서도 기록된 적 없다 — 데이터 손실 없음.
ALTER TABLE "users" DROP COLUMN "status";
ALTER TABLE "users" DROP COLUMN "delete_at";
