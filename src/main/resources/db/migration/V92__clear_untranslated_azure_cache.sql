-- BY-1409: Azure가 원문 언어를 못 알아봐(감지 확신도 0) 원문을 거의 그대로 돌려준 결과가 번역으로 저장돼 있다(문언문 등).
-- 대상 언어의 글자가 하나도 없는 Azure 결과만 지운다. 지운 행은 다음 조회 때 다시 번역된다(새 코드는 Azure를 건너뛴다).
-- zh-TW 대상은 한자 원문과 번역을 글자로 구별할 수 없어 건드리지 않는다.
DELETE FROM group_translation_cache
WHERE provider = 'AZURE'
  AND ((target_locale = 'ko' AND translated_text !~ '[가-힣]')
    OR (target_locale = 'ja' AND translated_text !~ '[ぁ-んァ-ヶ]')
    OR (target_locale = 'en' AND translated_text !~ '[A-Za-z]'));

DELETE FROM group_application_translation_cache
WHERE provider = 'AZURE'
  AND ((target_locale = 'ko' AND translated_text !~ '[가-힣]')
    OR (target_locale = 'ja' AND translated_text !~ '[ぁ-んァ-ヶ]')
    OR (target_locale = 'en' AND translated_text !~ '[A-Za-z]'));
