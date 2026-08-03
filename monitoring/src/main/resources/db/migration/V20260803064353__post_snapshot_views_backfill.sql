-- 조회수 세션 일관성 백필(findings §2 결론 4) — fb_plays 도입 이전에 적재된 스냅샷은
-- play_count 우선 파싱이라 콜 세션에 따라 IG 전용/합산이 뒤섞여 있다(같은 릴스가 221→305→222로
-- 역행). 원형 적재(raw.fetch_payload)에 콜별 ig/fb가 그대로 남아 있으므로, (code, KST 날짜)별
-- 마지막 관측을 뽑아 새 코드와 같은 규칙(views = IG 몫 + fb, fb는 캐리포워드 + 첫 관측 역전파)으로
-- 전 행을 재계산한다. 운영 DB 드라이런으로 수치 검증 완료(2026-08-03 — DX0U76Xy1D2 221→304 정렬,
-- DUrj0iGEn6G 유도 fb 58,254로 diff 0 등). raw가 없는 (code, 날짜) 행은 건드리지 않는다.
-- 신규/테스트 DB는 raw가 비어 있어 no-op.
WITH calls AS (
  -- 단건(/v2/media/by/code): items[0]. 열거(clips/medias): response.items[] (clips는 media 한 겹 래핑)
  SELECT fetched_at, COALESCE(payload->'items'->0, payload->'response'->'items'->0) AS item
  FROM raw.fetch_payload WHERE kind = 'POST'
  UNION ALL
  SELECT p.fetched_at, COALESCE(it->'media', it)
  FROM raw.fetch_payload p
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(p.payload->'response'->'items') = 'array'
         THEN p.payload->'response'->'items' ELSE '[]'::jsonb END) it
  WHERE p.kind = 'POSTS'
), metrics AS (
  -- captured_on은 KST 날짜(SnapshotWriter)라 콜 시각도 KST로 접는다
  SELECT item->>'code' AS code,
         (fetched_at AT TIME ZONE 'Asia/Seoul')::date AS day, fetched_at,
         COALESCE((item->>'ig_play_count')::bigint,
                  CASE WHEN item ? 'play_count' AND item ? 'fb_play_count'
                       THEN (item->>'play_count')::bigint - (item->>'fb_play_count')::bigint
                       ELSE (item->>'play_count')::bigint END) AS ig,
         (item->>'fb_play_count')::bigint AS fb_key,
         (item->>'play_count')::bigint AS play,
         (item ? 'fb_play_count') AS fb_key_seen
  FROM calls
  WHERE item ? 'code'
), derived AS (
  -- FB 몫은 play > ig면 play - ig 유도를 fb 키보다 우선(HikerClient.playCounts와 동일 규칙)
  SELECT code, day, fetched_at, ig,
         CASE WHEN ig IS NOT NULL AND play IS NOT NULL AND play > ig
              THEN play - ig ELSE fb_key END AS fb,
         (fb_key_seen OR (ig IS NOT NULL AND play IS NOT NULL AND play > ig)) AS fb_seen
  FROM metrics
), daily AS (
  SELECT code, day,
         (array_agg(ig ORDER BY fetched_at DESC) FILTER (WHERE ig IS NOT NULL))[1] AS ig,
         (array_agg(fb ORDER BY fetched_at DESC) FILTER (WHERE fb_seen))[1] AS fb,
         bool_or(fb_seen) AS fb_seen
  FROM derived
  GROUP BY code, day
), carried AS (
  -- fb: 캐리포워드(직전 관측) 우선, 없으면 첫 관측 역전파(이후 관측) — 첫 관측일 점프 제거
  SELECT d.code, d.day, d.ig,
         COALESCE(
           (SELECT d2.fb FROM daily d2
            WHERE d2.code = d.code AND d2.day <= d.day AND d2.fb_seen
            ORDER BY d2.day DESC LIMIT 1),
           (SELECT d2.fb FROM daily d2
            WHERE d2.code = d.code AND d2.day > d.day AND d2.fb_seen
            ORDER BY d2.day ASC LIMIT 1)) AS fb
  FROM daily d
  WHERE d.ig IS NOT NULL
)
UPDATE post_snapshot s
SET views = c.ig + COALESCE(c.fb, 0), fb_plays = c.fb
FROM carried c
WHERE s.short_code = c.code AND s.captured_on = c.day AND s.views IS NOT NULL;
