-- trait 어휘 1개 정식 추가 — '메이크업 리뷰' (B. 리뷰 방식, sort 15).
-- 근거: 07-30 dev 전량 DRY 실측에서 559계정이 보유(빈도 6위)한데 어휘(V41, 172개·13축) 밖이라
-- LLM이 좁은 개념인 '발색 리뷰'(스와치·발색 중심)로 오매핑. 사용자 결정으로 어휘에 정식 추가.
-- 후속 조치: 머지 후 dev에서 이 raw_value에 걸린 trait_canon_log 행을 삭제하고 재-DRY 필요
-- (기존 캐시가 '발색 리뷰' 오매핑을 그대로 재사용하지 않도록).
INSERT INTO trait_taxonomy (name, facet, facet_order, sort) VALUES
  ('메이크업 리뷰','리뷰 방식',2,15)
ON CONFLICT DO NOTHING;
