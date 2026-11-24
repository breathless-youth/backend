-- 공부 세션 화면의 한 줄 배너에서 연 신청도 받는다. V90의 placement CHECK는 이름 없이 만들어져
-- PostgreSQL이 premium_waitlist_placement_check로 붙였으므로 지우고 SESSION을 더해 다시 만든다.
ALTER TABLE premium_waitlist DROP CONSTRAINT premium_waitlist_placement_check;
ALTER TABLE premium_waitlist
    ADD CONSTRAINT premium_waitlist_placement_check CHECK (placement IN ('RESULT', 'HOME', 'SESSION'));
