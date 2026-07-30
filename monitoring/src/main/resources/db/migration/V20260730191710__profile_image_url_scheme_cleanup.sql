-- 결함 ② 기존 오염행 정정: Hiker 업스트림이 리터럴 "exception://" 같은 무효 스킴을 profile_pic_url로
-- 보낸 사례가 실측 확인됐다(cherish__sy). 검증 없이 저장돼 was가 그대로 서빙 중인 값을 NULL로 되돌린다.
-- 이후 재스윕에서 ProfileMetaRepository 정규화(http/https 아니면 null, 기존 유효값 COALESCE 보존)를
-- 통해 정상 URL로 복구되거나 계속 null로 남는다.
UPDATE profile_meta
   SET profile_image_url = NULL
 WHERE profile_image_url IS NOT NULL
   AND profile_image_url !~* '^https?://';
