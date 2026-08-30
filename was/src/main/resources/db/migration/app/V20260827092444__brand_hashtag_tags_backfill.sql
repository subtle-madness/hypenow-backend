-- 사용자 태그 장부 백필(2026-08-27 해시태그 직접 수집 설계 §4) — 기존 활성 링크 전원분.
--
-- 08-27 진단된 갭: 브랜드 등록 시 monitoring이 brand_hashtag에 자동으로 심는 계정명 유도 태그가
-- was의 사용자 태그 원장(app.brand_hashtag_tags)에는 기록되지 않는다. 해시태그 게시물의 사용자
-- 격리 필터는 "내 장부 태그 ∩ 게시물 매칭 태그"라, 장부가 비면 이 사용자에게 아무것도 보이지
-- 않는다(구 fail-open이 이걸 가리고 있었고, 그 fail-open은 이제 폐기된다).
--
-- 유도 규칙은 was BrandHashtagTags.derive / monitoring BrandHashtagTags.derive와 같다:
-- 소문자화 후 "선행 유효 해시태그 문자 구간"만 취한다(IG 해시태그는 점(.)에서 끊긴다).
-- 정규식을 [a-z0-9_]로 쓴 것은 축약이 아니라 정확한 대응이다 — username은 등록 시
-- BrandUsername.validate가 ^[a-z0-9._]{1,30}$로 이미 강제한 값이라 비ASCII가 들어올 수 없다.
-- substring(...)은 매치가 없으면 NULL을 돌려주므로 그 행은 WHERE에서 자연히 빠진다.
--
-- 해제된 연결(deleted_at IS NOT NULL)은 제외한다 — 사용자가 끊은 브랜드의 장부를 되살리면 안 된다.
-- ON CONFLICT DO NOTHING이라 재실행 안전하고, 사용자가 직접 관리 중인 기존 태그도 건드리지 않는다.
INSERT INTO app.brand_hashtag_tags (user_id, brand_id, tag)
SELECT bm.user_id,
       bm.brand_id,
       substring(lower(bm.username) from '^[a-z0-9_]+')
FROM app.brand_monitorings bm
WHERE bm.deleted_at IS NULL
  AND substring(lower(bm.username) from '^[a-z0-9_]+') IS NOT NULL
ON CONFLICT (user_id, brand_id, tag) DO NOTHING;
