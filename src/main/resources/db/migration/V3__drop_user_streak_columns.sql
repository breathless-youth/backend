-- 스트릭은 users에 저장하지 않고 study_session 이력(stat_date)에서 조회 시 계산한다.
-- 사용된 적 없는 사본 컬럼이라 데이터 백업 없이 제거한다.
ALTER TABLE "users" DROP COLUMN "streak";
ALTER TABLE "users" DROP COLUMN "max_streak";
