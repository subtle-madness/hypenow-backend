-- 스펙 6.7: 저장 콘텐츠에 메모 보존. 구 /api/saved(콘텐츠)는 메모 미지원이라 컬럼이 없었다.
-- saved_influencers는 이미 memo 컬럼 보유(V1) — 콘텐츠 쪽만 맞춘다.
ALTER TABLE app.saved_contents ADD COLUMN memo text;
