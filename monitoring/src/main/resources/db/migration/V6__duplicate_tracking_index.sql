-- 같은 유저 이중 추적 배제(계약 §6.25) 조회 인덱스 — 감지(firstDetection)가 매 매칭마다
-- "이 유저가 이미 추적 중인 shortcode" 집합을 user_id로 훑는다. 순수 추가라 expand 단계.
CREATE INDEX idx_target_user_tracked ON target (user_id)
    WHERE status IN ('WATCHING','TRACKING') AND tracked_short_code IS NOT NULL;
