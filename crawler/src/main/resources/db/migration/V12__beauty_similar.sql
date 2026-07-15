-- 뷰티 판정(BEAUTY 잡·수동 오버라이드) + 유사 계정 발굴(SIMILAR 잡) 지원
ALTER TABLE influencer
    ADD COLUMN beauty               boolean,
    ADD COLUMN beauty_source        text,
    ADD COLUMN beauty_reason        text,
    ADD COLUMN similar_processed_at timestamptz;
