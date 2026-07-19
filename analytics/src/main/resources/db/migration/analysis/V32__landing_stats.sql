-- 랜딩 통계 미러 (스펙 6.20) — 1행 테이블. 컬럼 순서 = analytics.v_landing_stats = LandingStats record.
-- followers3k10k 등 언더스코어 없는 이름이 정본: MirrorJob.toSnakeCase가 대문자 앞에만 '_'를 넣어
-- record 컴포넌트 followers3k10k를 그대로 컬럼명으로 쓴다 (§4-3: 뷰·DDL을 record 변환 결과에 맞춘다).
CREATE TABLE landing_stats (
    contents_count    bigint,
    influencers_count bigint,
    total_views       bigint,
    avg_views         bigint,
    followers3k10k    bigint,
    followers10k30k   bigint,
    followers30k50k   bigint,
    updated_at        timestamptz
);
