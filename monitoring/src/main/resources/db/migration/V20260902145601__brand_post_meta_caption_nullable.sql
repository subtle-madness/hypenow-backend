-- post_meta 캡션 nullable 전환(V20260902130736)과 동형 결함이 brand_post_meta에도 있었다 —
-- 캡션 3-상태 계약(트랙 HH: null=미수집 / ''=확인된 무캡션 / 값=원문)을 저장 계층까지 통과시키려면
-- NOT NULL 제약이 걸림돌이다. DROP NOT NULL은 제약을 느슨히 하는 expand 단계라 항상 안전하다.
ALTER TABLE brand_post_meta ALTER COLUMN caption DROP NOT NULL;
