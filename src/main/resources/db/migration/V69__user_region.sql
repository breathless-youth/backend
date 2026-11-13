-- 사용자 기기의 지역(ISO 3166-1 두 글자, 예: KR·TW·SG). 광고 나라별 분석에 쓴다. 앱이 보내기 전에는 비어 있다
ALTER TABLE users ADD COLUMN region VARCHAR(2);
