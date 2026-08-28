-- 구 해시태그 감지 데이터 이관(2026-08-27 해시태그 직접 수집 설계 §5).
--
-- brand_hashtag_post(감지 전용 테이블)의 게시물을 통합 풀 brand_tagged_post로 승격한다.
-- 구 LLM 관련성 판정(verdict)은 폐기됐으므로 IRRELEVANT·UNCERTAIN도 전량 이관한다. 단
-- SELF(브랜드 본인 게시물)만 제외한다 — 새 수집 규칙의 "본인 게시물 제외"와 정합해야 한다.
--
-- 브랜드당 최신순 1,000건 상한(설계 §0의 편입 상한과 같은 수) — 순위는 taken_at DESC이고,
-- 동시각 타이브레이크로 short_code를 둬 재실행 결과가 흔들리지 않게 한다(멱등의 전제).
--
-- author_ig_user_id는 구 테이블에 없다 → NULL. 야간 스윕 2단계의 단건 수집이 채운다.
-- enriched_at·last_crawled_at은 채우지 않는다: 이관분은 게시자·댓글·스냅샷이 비어 있으므로
-- was 표시 게이트(enriched_at IS NOT NULL)를 통과하면 안 되고, last_crawled_at NULL이 곧
-- "즉시 due"라 2단계 미보강 우선 배치가 상한 안에서 점진 충전한다.
--
-- 겹침(이미 tagged·direct로 있던 행)은 hashtag_detected_at만 얹는다 — COALESCE라 재실행해도
-- 최초 시각이 밀리지 않는다.
INSERT INTO brand_tagged_post
    (brand_id, short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
     tag_detected_at, hashtag_detected_at)
SELECT r.brand_id, r.short_code, r.author_username, NULL, r.taken_at, r.first_seen_at,
       NULL, r.first_seen_at
FROM (
    SELECT hp.brand_id, hp.short_code, hp.author_username, hp.taken_at, hp.first_seen_at,
           row_number() OVER (PARTITION BY hp.brand_id
                              ORDER BY hp.taken_at DESC, hp.short_code) AS rn
    FROM brand_hashtag_post hp
    WHERE hp.verdict <> 'SELF'
) r
WHERE r.rn <= 1000
ON CONFLICT (brand_id, short_code) DO UPDATE SET
    hashtag_detected_at = COALESCE(brand_tagged_post.hashtag_detected_at, EXCLUDED.hashtag_detected_at);

-- 매칭 태그 이관 — 새 테이블의 FK가 brand_tagged_post를 향하므로, 위에서 실제로 이관된
-- (또는 원래 풀에 있던) 행에만 붙인다. 상한·SELF로 빠진 행의 태그를 옮기면 FK가 터진다.
-- 조인 조건에 hashtag_detected_at IS NOT NULL을 둬 "구 감지 유래 행"으로 좁힌다.
INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
SELECT m.brand_id, m.short_code, m.tag
FROM brand_hashtag_post_matched_tags m
JOIN brand_tagged_post t ON t.brand_id = m.brand_id AND t.short_code = m.short_code
WHERE t.hashtag_detected_at IS NOT NULL
ON CONFLICT DO NOTHING;

-- 발견 경로 태그(brand_hashtag_post.matched_tag, NOT NULL 단일 컬럼)도 함께 옮긴다. V20260819054457이
-- 이 값을 matched_tags로 백필했고 이후 스윕도 계속 기록했으므로 대개 위 문장에 이미 포함되지만,
-- 두 벌 사이에 구멍이 있으면 그 게시물은 매칭 태그가 0건이 돼 was 격리 필터(fail-open 폐기)에서
-- 영영 숨는다. 멱등이라 중복은 무해하니 안전 쪽으로 한 번 더 긁는다.
INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
SELECT hp.brand_id, hp.short_code, hp.matched_tag
FROM brand_hashtag_post hp
JOIN brand_tagged_post t ON t.brand_id = hp.brand_id AND t.short_code = hp.short_code
WHERE t.hashtag_detected_at IS NOT NULL
ON CONFLICT DO NOTHING;
