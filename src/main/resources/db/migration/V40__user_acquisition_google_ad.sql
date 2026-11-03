-- BY-1138: 구글 광고 유입 (ADR-0036 7번). 앱이 읽은 구글 광고 그룹 딥링크 값(fm_ad=g_소재) 원문을 남긴다.
-- source에는 GOOGLE_AD가 더해진다(varchar라 스키마 변경 없음). gclid는 install_referrer 원문 안에만 둔다.
-- 락 대기 상한: nullable 컬럼 추가는 메타데이터만 바꾸지만 ACCESS EXCLUSIVE 잠금을 잡는다. 분석 쿼리 뒤에 줄을 서서 그 뒤 요청이
--   쌓이지 않게 5초 안에 못 잡으면 배포를 실패시키고 다시 시도한다. SET LOCAL은 이 마이그레이션 트랜잭션에서만 적용된다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE user_acquisition ADD COLUMN google_deep_link_ad varchar(64);
