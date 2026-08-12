-- 브랜드 콜 소급 집계(2026-08-12 어드민 크롤링 비용 — 사용자 결정: 기존 호출도 비용에 반영).
-- 콜 원형 raw.fetch_payload(성공 콜 1행 = 1콜, RecordingHikerHttp)에서 브랜드 귀속을 복원해
-- brand_call_count를 실시간 집계(CountingHikerHttp) 개시 이전 구간까지 채운다.
-- 원형 기반 백필 마이그레이션 전례: V20260803064353(post_snapshot views).
--
-- 귀속 규칙(근사 — specs/2026-08-12-admin-crawling-usage-design.md §2에 문서화):
--  ① TAGGED        : subject = 브랜드 ig_user_id → 정확 귀속
--  ② PROFILE       : subject = username 중 brand_account에 있는 것만(등록 검증 + 매일 프로필 갱신).
--                    캠페인 감시 계정과 이름이 겹치면 캠페인 프로필 콜도 브랜드로 계상될 수 있다(수용)
--  ③ PROFILE_BY_ID : 게시자 프로필(브랜드 전용 경로) — 그 게시자를 태그한 브랜드 중 최소 id로
--                    대표 귀속(전역 캐시라 유발 브랜드를 사후 특정할 수 없다 — 이중 계상은 없음)
--  ④ COMMENTS      : subject = media pk → 숏코드 산술 복원(ShortCodes.toMediaId의 역산) →
--                    brand_tagged_post 링크의 최소 brand_id. 캠페인 게시물 댓글은 링크가 없어 자연
--                    제외, 캠페인·브랜드 겹침 게시물은 브랜드로 계상(수용 — 겹침 댓글은 원래 이중 수집)
--  ⑤ 해시태그 recent 열거 콜(kind=OTHER)·캠페인 전용 kind(POSTS·POST·MEDIA_INFO)는 소급하지 않는다
--     — 해시태그는 08-11 개통이라 잔량이 미미하고 kind·subject에 브랜드 단서가 없다(향후분은
--     BrandHashtagCollectService 스코프가 실시간 계상)
--
-- called_on의 KST 달력일 경계는 실시간 쓰기 경로(CountingHikerHttp)와 동일. 이 마이그레이션이
-- 앱 기동(=실시간 집계 개시) 전에 1회 돌므로 이중 계상 창은 없다(교체 배포 중 구 컨테이너가
-- 흘리는 몇 분치 콜만 양쪽 다 놓친다 — 수용).

CREATE FUNCTION pg_temp.shortcode_of(media_id bigint) RETURNS text
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    alphabet constant text := 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    n bigint := media_id;
    code text := '';
BEGIN
    IF n IS NULL OR n <= 0 THEN
        RETURN NULL;
    END IF;
    WHILE n > 0 LOOP
        code := substr(alphabet, (n % 64)::int + 1, 1) || code;
        n := n / 64;
    END LOOP;
    RETURN code;
END $$;

-- 변환 자가 검증 — ShortCodes 주석의 실측 검증값과 불일치하면 백필 전체를 중단한다.
DO $$
BEGIN
    IF pg_temp.shortcode_of(3951324523536622012) <> 'DbV7LgZsKG8' THEN
        RAISE EXCEPTION 'shortcode 역산 검증 실패 — 백필 중단';
    END IF;
END $$;

INSERT INTO brand_call_count (brand_id, called_on, calls)
SELECT brand_id, called_on, count(*)
FROM (
    -- ① 태그 열거
    SELECT ba.id AS brand_id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date AS called_on
    FROM raw.fetch_payload p
    JOIN brand_account ba ON ba.ig_user_id = p.subject
    WHERE p.kind = 'TAGGED'

    UNION ALL

    -- ② 브랜드 프로필(등록 검증·매일 갱신)
    SELECT ba.id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
    FROM raw.fetch_payload p
    JOIN brand_account ba ON ba.username = p.subject
    WHERE p.kind = 'PROFILE'

    UNION ALL

    -- ③ 게시자 프로필 — 태그한 브랜드 중 최소 id 대표 귀속
    SELECT (SELECT min(bt.brand_id) FROM brand_tagged_post bt
             WHERE bt.author_ig_user_id = p.subject),
           (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
    FROM raw.fetch_payload p
    WHERE p.kind = 'PROFILE_BY_ID'

    UNION ALL

    -- ④ 댓글 — media pk → 숏코드 복원 → 태그 링크의 최소 brand_id
    SELECT (SELECT min(bt.brand_id) FROM brand_tagged_post bt
             WHERE bt.short_code = pg_temp.shortcode_of(p.subject::bigint)),
           (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
    FROM raw.fetch_payload p
    WHERE p.kind = 'COMMENTS' AND p.subject ~ '^[0-9]+$'
) attributed
WHERE brand_id IS NOT NULL
GROUP BY brand_id, called_on
ON CONFLICT (brand_id, called_on)
    DO UPDATE SET calls = brand_call_count.calls + EXCLUDED.calls;
