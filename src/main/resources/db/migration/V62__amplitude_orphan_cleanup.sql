-- 탈퇴·병합으로 users에서 사라진 회원의 Amplitude 적재본은 무기한 보관할 수 없어 한 번 지운다(이후 탈퇴는 처리기가, 재적재는 적재 쪽이 막는다).
DELETE FROM amplitude_daily_event e
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = e.user_id);

DELETE FROM amplitude_user_attribute a
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = a.user_id);
