-- 일일 지표 리포트 발송 이력.
-- ECS 무중단 배포 중에는 태스크가 일시적으로 2개가 되어 스케줄러도 2번 뜬다.
-- report_date를 PK로 두고 삽입 성공 여부로 "오늘 아직 안 보냄"을 판정한다 —
-- 애플리케이션 락 대신 DB 제약으로 동시성을 처리하는 이 레포의 방식(UserRepository.insertIfAbsent)과 같다.
CREATE TABLE "daily_report_log" (
    "report_date" date PRIMARY KEY,
    "sent_at"     timestamptz NOT NULL
);
