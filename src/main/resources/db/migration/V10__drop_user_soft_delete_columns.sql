-- 탈퇴는 하드 삭제("즉시 전부 삭제" 정책 — BY-383)로 확정되어 소프트 삭제 잔재를 제거한다.
-- status는 ACTIVE 단일값만 존재했고 delete_at은 어디서도 기록된 적 없다 — 데이터 손실 없음.

-- 소프트 삭제된 행이 있으면 컬럼 drop이 그 계정을 되살린다 — 존재 시 실패시키고 수동 정리 후 재배포한다 (V7 패턴).
-- 감사 쿼리: SELECT id FROM users WHERE status = 'DELETE';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE status = 'DELETE') THEN
        RAISE EXCEPTION 'users.status=DELETE 행이 존재합니다 — 수동 정리 후 재배포하세요';
    END IF;
END $$;

ALTER TABLE "users" DROP COLUMN "status";
ALTER TABLE "users" DROP COLUMN "delete_at";
