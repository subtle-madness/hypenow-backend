-- collect 열거(gql/user/medias·v2/user/clips)는 인스타 내부 user id가 필수인데, 지금까지는
-- 방문 시점 프로필 응답에서만 추출해 프로필 요청 1회 실패(프록시 간헐 401)가 방문 전체를
-- 실패시켰다. 판정·수집 시 추출해 저장해두고, 프로필 갱신 실패 시 폴백으로 쓴다.
ALTER TABLE influencer ADD COLUMN ig_user_id text;

-- 기존 raw_profile 원형에서 백필 — 소스별 추출 경로는 ProfileExtractor.userId와 동일.
-- 인플루언서별로 "추출값이 있는 가장 최근 원형"을 쓴다.
UPDATE influencer i
SET ig_user_id = sub.uid
FROM (
    SELECT DISTINCT ON (influencer_id) influencer_id, uid
    FROM (
        SELECT influencer_id, captured_at,
               CASE source
                   WHEN 'SELF_GQL'     THEN payload->'data'->'user'->>'id'
                   WHEN 'HIKER_MOBILE' THEN COALESCE(payload->'user'->>'pk', payload->'user'->>'id')
                   ELSE payload->>'userId'
               END AS uid
        FROM raw_profile
    ) t
    WHERE uid IS NOT NULL
    ORDER BY influencer_id, captured_at DESC
) sub
WHERE i.id = sub.influencer_id;
