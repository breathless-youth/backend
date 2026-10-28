-- BY-1046: 시간대 구간을 4구간(KST 0·6·12·18시)으로 바꾼다. 옛 5구간 행을 비우면 기동 백필(SessionSlotBackfill)이
-- 지난 주 월요일부터의 측정 세션을 새 경계로 다시 채운다. 배분은 이벤트 비율이라 SQL로 다시 계산하지 않는다(ADR-0033)
DELETE FROM study_session_slot;
