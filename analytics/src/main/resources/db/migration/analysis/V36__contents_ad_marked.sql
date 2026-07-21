-- contents.ad_marked — 인스타 유료 파트너십 태그(릴스 전용, 피드는 false).
-- 광고 판정의 정본은 캡션 분류(content_analyses.ad_type)지만, 이 태그는 인스타가 보증하는
-- 확정 사실이라 LLM 분석 프롬프트에 사실로 실어야 한다. 안 실으면 태그가 붙은 게시물을
-- LLM이 organic으로 뒤집는다 — 운영 실측 87건(모델 무관, 프롬프트 결손).
-- 일상 잡(ContentAnalysisJob)은 analysis DB의 미러 contents만 읽으므로 여기로 내려야 쓸 수 있다.
-- (V35는 다른 브랜치 선점 — 번호는 머지 직전 재확인할 것.)
ALTER TABLE contents ADD COLUMN ad_marked boolean;
