-- 캠페인·콘텐츠 모니터링 콜 소급 집계(2026-08-12 어드민 크롤링 비용 범위 확장 — 사용자 결정:
-- 브랜드와 동일하게 기존 호출도 비용에 반영). 콜 원형 raw.fetch_payload(성공 콜 1행 = 1콜,
-- RecordingHikerHttp)에서 유저 귀속을 복원해 target_call_count를 실시간 집계(CountingHikerHttp +
-- TargetCallContext) 개시 이전 구간까지 채운다. 브랜드 소급 전례: V20260812153000.
--
-- 귀속 규칙(근사 — 전부 "그 시점에 그 대상을 감시하던 캠페인의 유저" 복원):
--  ① PROFILE  : subject = username → 같은 계정을 감시하던 target의 유저.
--               브랜드 계정과 이름이 겹치면 같은 콜이 브랜드(V20260812153000)와 양쪽에 계상될 수
--               있다(수용 — 실시간도 파이프라인별 별도 콜이라 각자 계상된다)
--  ② POSTS    : subject = ig user_id(열거·clips) → PROFILE 원형의 user.pk로 username을 복원해 ①과 동일 귀속
--  ③ POST     : subject = short_code(단건 정본) → 그 게시물을 등록·추적하던 target의 유저
--  ④ COMMENTS : subject = media pk → 숏코드 산술 복원(ShortCodes.toMediaId의 역산) →
--               tracked_short_code 매칭. 브랜드 태그와 겹친 게시물은 양쪽 계상(수용 — ①과 동일 근거)
--  ⑤ MEDIA_INFO(share 해소)는 소급하지 않는다 — subject가 단축 URL이라 유저 단서가 원형에 없다
--     (등록 요청 기록은 app 스키마 소속 — 크로스 DB 접근 금지). 향후분은 ShareResolveService의
--     userId 스코프가 실시간 계상. 건수도 등록당 최대 1콜이라 잔량이 미미하다
--
-- 유저 귀속의 기간 컷: 콜의 KST 달력일이 target의 [등록일, 종결일(closed_at, 없으면 만료일)] 안에
-- 들 때만 그 유저 몫이다(양끝 포함) — 등록 전 이력(같은 계정을 먼저 감시하던 다른 유저의 콜)을
-- 물려받지 않고, 해지 후 콜(다른 유저 몫으로 계속 도는 수집)을 떠안지 않는다. 같은 유저가 같은
-- 대상에 캠페인을 여러 개 걸어도 payload×유저 DISTINCT라 1콜 1계상(실시간의 유저 '집합' 계상과
-- 동일 규칙). user_id가 null인 행(V3 이전 등록)은 귀속 불가라 자연 제외된다.
--
-- called_on의 KST 달력일 경계는 실시간 쓰기 경로와 동일. 이 마이그레이션이 앱 기동(=실시간 집계
-- 개시) 전에 1회 돌므로 이중 계상 창은 없다(교체 배포 중 구 컨테이너가 흘리는 몇 분치 콜만
-- 양쪽 다 놓친다 — 수용, 브랜드 소급과 동일).

-- OR REPLACE인 이유: Flyway는 마이그레이션들을 한 세션으로 돌리므로, 프레시 DB(테스트·신규 환경)에선
-- 브랜드 백필(V20260812153000)이 같은 세션에 만든 pg_temp.shortcode_of가 아직 살아 있다.
CREATE OR REPLACE FUNCTION pg_temp.shortcode_of(media_id bigint) RETURNS text
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

-- 기간 컷 술어는 4개 분기 공통이라 함수로 묶는다(psql 변수 없이 마이그레이션 단독 실행 가능해야 함).
CREATE OR REPLACE FUNCTION pg_temp.within_period(fetched timestamptz, registered timestamptz,
        closed timestamptz, expires timestamptz) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT (fetched AT TIME ZONE 'Asia/Seoul')::date
           BETWEEN (registered AT TIME ZONE 'Asia/Seoul')::date
               AND (COALESCE(closed, expires) AT TIME ZONE 'Asia/Seoul')::date
$$;

INSERT INTO target_call_count (user_id, called_on, calls)
SELECT user_id, called_on, count(*)
FROM (
    SELECT DISTINCT payload_id, user_id, called_on
    FROM (
        -- ① 프로필(등록 검증·스윕 갱신·팔로워 1회 수집)
        SELECT p.id AS payload_id, t.user_id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date AS called_on
        FROM raw.fetch_payload p
        JOIN target t ON t.username = p.subject
        WHERE p.kind = 'PROFILE' AND t.user_id IS NOT NULL
          AND pg_temp.within_period(p.fetched_at, t.registered_at, t.closed_at, t.expires_at)

        UNION ALL

        -- ② 열거·clips(스윕 열거, 저장·리포스트 세션 복권 재시도) — PROFILE 원형의 user.pk로
        -- username을 복원한다(HikerClient.fetchProfile이 읽는 그 키). 개명으로 한 pk가 여러
        -- username에 걸리면 양쪽 다 매칭된다(잔존 시 이중 계상 — 수용)
        SELECT p.id, t.user_id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
        FROM raw.fetch_payload p
        JOIN (SELECT DISTINCT payload -> 'user' ->> 'pk' AS ig_user_id, subject AS username
              FROM raw.fetch_payload
              WHERE kind = 'PROFILE' AND payload -> 'user' ->> 'pk' IS NOT NULL) m
             ON m.ig_user_id = p.subject
        JOIN target t ON t.username = m.username
        WHERE p.kind = 'POSTS' AND t.user_id IS NOT NULL
          AND pg_temp.within_period(p.fetched_at, t.registered_at, t.closed_at, t.expires_at)

        UNION ALL

        -- ③ 게시물 단건 정본(등록 첫 수집·추적 스윕)
        SELECT p.id, t.user_id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
        FROM raw.fetch_payload p
        JOIN target t ON p.subject IN (t.short_code, t.tracked_short_code)
        WHERE p.kind = 'POST' AND t.user_id IS NOT NULL
          AND pg_temp.within_period(p.fetched_at, t.registered_at, t.closed_at, t.expires_at)

        UNION ALL

        -- ④ 댓글 — media pk → 숏코드 복원 → 추적 게시물 매칭. 캐스트는 CASE 안에서만 —
        -- 조인 조건은 WHERE보다 먼저 평가될 수 있어 비숫자 subject가 캐스트 오류를 낼 수 있다.
        SELECT p.id, t.user_id, (p.fetched_at AT TIME ZONE 'Asia/Seoul')::date
        FROM raw.fetch_payload p
        JOIN target t ON t.tracked_short_code = CASE
                WHEN p.subject ~ '^[0-9]+$' THEN pg_temp.shortcode_of(p.subject::bigint) END
        WHERE p.kind = 'COMMENTS' AND p.subject ~ '^[0-9]+$' AND t.user_id IS NOT NULL
          AND pg_temp.within_period(p.fetched_at, t.registered_at, t.closed_at, t.expires_at)
    ) candidate
) deduped
GROUP BY user_id, called_on
ON CONFLICT (user_id, called_on)
    DO UPDATE SET calls = target_call_count.calls + EXCLUDED.calls;
