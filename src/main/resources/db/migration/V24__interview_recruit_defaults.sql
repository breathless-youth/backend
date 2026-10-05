-- 인터뷰 모집(BY-821)을 켠 상태로 시작한다 (ADR-0027 갱신). 운영 DB에 손으로 SQL을 넣지 않도록 기본값과
-- 1·2번 공지를 마이그레이션으로 넣는다. 서버가 먼저 배포돼도 새 API는 새 웹만 부르므로 웹 배포 전에는 아무것도 보이지 않는다.
-- 끄고 켜기·문구 수정은 지금처럼 SQL UPDATE로 한다.

-- 구글폼은 닉네임만 미리 채운다. 웹이 NICKNAME 자리에 URL 인코딩한 닉네임을 넣는다
UPDATE "app_config"
SET "config_value" = 'https://docs.google.com/forms/d/e/1FAIpQLSc4sbX8ALpV9qUEYrXqGcXc1T9OD_r1vcXfWUAXw66EGjENrw/viewform?usp=pp_url&entry.1606714956=NICKNAME',
    "updated_at" = now()
WHERE "config_key" IN ('interview.card.url', 'interview.settings.url');

UPDATE "app_config"
SET "config_value" = 'true', "updated_at" = now()
WHERE "config_key" IN ('interview.enabled', 'interview.card.enabled', 'interview.settings.enabled');

-- 1·2번 모달은 같은 내용이다. audience만 달라 웹이 source(g1_revisit·g2_return)를 가른다.
-- 이미지는 웹이 가진 파일의 상대 경로라 dev·운영 도메인과 무관하다
INSERT INTO "notice" ("title", "content", "image_url", "audience", "badge_text", "button_text", "button_url", "starts_at")
SELECT '포메에 의견을 들려주실 분을 찾아요',
       E'아직 타이머를 안 써보셨어도 괜찮아요.\n15분 통화로 솔직한 이야기를 들려주세요.',
       '/images/interview/mascot-phone.png',
       audience,
       '스타벅스 기프티콘 100% 증정',
       '인터뷰 신청하기',
       'https://docs.google.com/forms/d/e/1FAIpQLSc4sbX8ALpV9qUEYrXqGcXc1T9OD_r1vcXfWUAXw66EGjENrw/viewform?usp=pp_url&entry.1606714956=NICKNAME',
       now()
FROM (VALUES ('G1_NOT_STARTED'), ('G2_LAPSED')) AS a(audience);
