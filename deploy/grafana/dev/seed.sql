-- 그라파나 하니스 목 시드 — 2026-08-18 운영 실측 밀도 복제(정상/초록 상태)
--
-- 적용(DB 2개라 구간을 나눠 두 번 실행한다 — psql 세션은 DB 하나만 본다):
--   C="docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev"
--   sed -n '/^-- BEGIN analysis/,/^-- END analysis/p'     deploy/grafana/dev/seed.sql | $C -d analysis
--   sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed.sql | $C -d monitoring
--
-- (`^-- ` 앵커가 필요하다 — 앵커 없이 쓰면 이 안내 주석 줄 자체가 구간 시작으로 잡혀 두 구간이
--  한 덩어리로 뽑힌다.)
--
-- 선행: deploy/grafana/dev/apply-migrations.sh로 스키마가 적용돼 있어야 한다.
-- 재적용 가능 — 각 구간 첫머리에서 시드 대상 테이블을 TRUNCATE ... RESTART IDENTITY 한다
-- (하니스 DB는 1회성이므로 CASCADE 절단이 안전하다. 운영 DB에 절대 돌리지 말 것).
--
-- 실측 근거(설계 §9, 2026-08-18 운영 조회):
--   app: users 53 · campaigns 12 · monitoring_items 76 · registrations 20 · digests 20 ·
--        brand_monitorings 130 · saved_influencers 9 · saved_contents 4 · inquiries 6(마지막 07-29)
--   monitoring: brand_tagged_post 28,255 · brand_hashtag_post 2,543 · brand_post_meta 8,000 · target 73 ·
--        sweep_run 7일 7/7 성공 · Hiker 콜 30일 49,756(브랜드) + 3,536(타깃) · alarm_event 97
--
-- 빨간불 상태가 필요하면 이 시드 위에 seed-red.sql을 덧입힌다(복원은 리셋 후 이 파일 재적용).
--
-- 시간대 규약: `called_on`·`last_swept_on` 등 date 컬럼은 **KST 달력일이 정본**이다
-- (V20260812100000·V20260812160000 주석 — was의 월초·자정 경계와 같은 시간대). 컨테이너
-- postgres는 UTC로 돌기 때문에 `current_date`가 아니라 `(now() AT TIME ZONE 'Asia/Seoul')::date`를
-- 쓴다. 패널 쿼리도 같은 식이어야 UTC 15~24시(=KST 00~09시)에 초록이 빨강으로 오진되지 않는다.
--
-- 시드하지 않은 것(패널을 만들 때 "데이터가 왜 없지"로 헤매지 않도록 명시):
--   · app.signup_events    — 가입 이벤트 로그. 계정 유입 대시보드는 app.users.created_at을 쓴다
--   · app.spring_session / spring_session_attributes — 세션 저장소. 대시보드 표면이 아니다
--   · app.gate_events / admin_audit_logs / notices / password_resets / brand_direct_posts /
--     monitoring_email_opt_outs / notice_items / notice_seen / app_setting — 패널 근거 없음
--   · monitoring.post_meta / post_snapshot / post_comment / profile_meta / profile_snapshot /
--     author_profile / brand_post_* / brand_profile_snapshot — 성과 패널이 설계 §4에서 기각됨
--   · monitoring.detected_candidate — 실측 0건이 정상(설계 §3), 의도적 공백
--   · analysis public.* 분석 산출물(account_analyses·content_analyses·image_assets 등) — 이번
--     개편 패널이 읽지 않는다(탐색 대시보드는 accounts·contents·landing_stats만 본다)

-- ============================================================================
-- BEGIN analysis  (psql -d analysis)
-- ============================================================================
SELECT setseed(0.4242);   -- 재현 가능한 난수(같은 시드 = 같은 분포)

TRUNCATE app.users RESTART IDENTITY CASCADE;              -- 참조 테이블 전부 함께 비움
TRUNCATE app.inquiries, app.signup_codes RESTART IDENTITY CASCADE;
TRUNCATE public.landing_stats;
TRUNCATE public.contents, public.accounts RESTART IDENTITY CASCADE;

-- 유저 53명, 최근 90일 분산(하루 0~2명꼴 — "빈 판 시계열" 재현이 목적)
INSERT INTO app.users (id, email, password_hash, created_at, name, user_type, company_name,
                       signup_route, industry, company_size, role, last_active_at)
SELECT g, 'user' || g || '@mock.test', 'x', now() - (random() * 90 || ' days')::interval,
       '목유저' || g,
       (ARRAY['brand','agency','distributor','influencer'])[1 + (g % 4)],
       '목회사' || g,
       (ARRAY['portal_search','blog_community','pr_article','social_media','offline_event','referral','other'])[1 + (g % 7)],
       (ARRAY['fashion','beauty','fnb','home_living','baby_kids'])[1 + (g % 5)],
       (ARRAY['2-10','11-50','51-200','201-500','501-1000','1001+'])[1 + (g % 6)],
       CASE WHEN g = 1 THEN 'ADMIN' ELSE 'USER' END,
       now() - (random() * 14 || ' days')::interval
FROM generate_series(1, 53) g;
SELECT setval('app.users_id_seq', 53);

-- 가입 코드 40장 중 25장 소진(계정 유입 대시보드 "코드 소진" stat용)
INSERT INTO app.signup_codes (code, channel, used_by, used_at, is_sent, created_at)
SELECT 'CODE' || lpad(g::text, 4, '0'),
       (ARRAY['direct','partner','event'])[1 + (g % 3)],
       CASE WHEN g <= 25 THEN g END,
       CASE WHEN g <= 25 THEN now() - (random() * 60 || ' days')::interval END,
       g <= 32,
       now() - interval '90 days' + (g || ' days')::interval
FROM generate_series(1, 40) g;

-- 탐색 사용량: 저장 인플루언서 9 · 저장 콘텐츠 4 (실측 그대로 — "거의 안 쓴다"가 사실)
INSERT INTO app.saved_influencers (user_id, handle, status, created_at, updated_at)
SELECT (g % 53) + 1, 'mockacct' || g,
       (ARRAY['reviewing','contact_planned','collaborating'])[1 + (g % 3)],
       now() - (random() * 30 || ' days')::interval, now()
FROM generate_series(1, 9) g;

INSERT INTO app.saved_contents (user_id, short_code, created_at)
SELECT (g % 53) + 1, 'MC' || g, now() - (random() * 30 || ' days')::interval
FROM generate_series(1, 4) g;

-- 문의 6건, 가장 최근이 20일 전(실측 마지막 07-29 — "오래 비어 있음"이 재현 대상)
INSERT INTO app.inquiries (user_type, name, email, organization, message, created_at)
SELECT (ARRAY['brand','agency','distributor','influencer'])[1 + (g % 4)],
       '문의자' || g, 'inq' || g || '@mock.test', '목조직' || g, '목 문의 내용 ' || g,
       now() - interval '20 days' - ((g - 1) * 7 || ' days')::interval
FROM generate_series(1, 6) g;

-- 캠페인 12
INSERT INTO app.monitoring_campaigns (id, user_id, name, description, brand, start_date, end_date,
                                      budget, seeding_count, created_at)
SELECT g, (g % 10) + 1, '목캠페인' || g, '하니스 목 캠페인 ' || g, '목브랜드' || g,
       current_date - 60 + g, current_date + g, 1000000 * g, 5 * g,
       now() - (random() * 60 || ' days')::interval
FROM generate_series(1, 12) g;
SELECT setval('app.monitoring_campaigns_id_seq', 12);

-- 등록 20건 — 초록 상태이므로 전부 완료(completed_at NOT NULL). 멈춘 등록은 seed-red.sql이 만든다.
INSERT INTO app.monitoring_registrations (id, user_id, requested_at, completed_at, tracking_days,
                                          campaign_id, acknowledged_at)
SELECT g, (g % 10) + 1,
       now() - (g || ' days')::interval,
       now() - (g || ' days')::interval + interval '3 minutes',
       (ARRAY[7, 14, 30])[1 + (g % 3)],
       CASE WHEN g % 3 = 0 THEN (g % 12) + 1 END,
       now() - (g || ' days')::interval + interval '2 hours'
FROM generate_series(1, 20) g;
SELECT setval('app.monitoring_registrations_id_seq', 20);

-- 모니터링 아이템 76
INSERT INTO app.monitoring_items (id, user_id, mode, registration_key, target_id, campaign_id,
                                  input_value, tracking_days, registered_on, created_at)
SELECT g, (g % 10) + 1,
       CASE WHEN g % 3 = 0 THEN 'url' ELSE 'account' END,
       gen_random_uuid(),
       CASE WHEN g <= 73 THEN g END,
       CASE WHEN g % 5 = 0 THEN (g % 12) + 1 END,
       'acct' || g, (ARRAY[7, 14, 30])[1 + (g % 3)],
       current_date - (g % 45), now() - ((g % 45) || ' days')::interval
FROM generate_series(1, 76) g;
SELECT setval('app.monitoring_items_id_seq', 76);

-- 등록 엔트리 — 초록 상태: pending 0건(=결과 미확정 0), 실패는 소수만
INSERT INTO app.monitoring_registration_entries (registration_id, seq, input, kind, result, reason_code, reason)
SELECT r, s, 'acct' || (r * 10 + s),
       CASE WHEN s = 3 THEN 'post' ELSE 'account' END,
       CASE WHEN (r + s) % 11 = 0 THEN 'failed' ELSE 'success' END,
       CASE WHEN (r + s) % 11 = 0 THEN 'not_found' END,
       CASE WHEN (r + s) % 11 = 0 THEN '계정을 찾을 수 없음' END
FROM generate_series(1, 20) r, generate_series(1, 3) s;

-- 다이제스트 20 (user_id, digest_date 유니크)
INSERT INTO app.monitoring_digests (user_id, digest_date, items, created_at, read_at)
SELECT (g % 10) + 1, current_date - (g / 10),
       jsonb_build_array(jsonb_build_object('shortCode', 'TSC' || g, 'views', (random() * 10000)::int)),
       now() - ((g / 10) || ' days')::interval,
       CASE WHEN g % 3 <> 0 THEN now() - ((g / 10) || ' days')::interval + interval '5 hours' END
FROM generate_series(1, 20) g;

-- 브랜드 모니터링 130 (brand_id 1..130 = monitoring DB brand_account.id와 1:1)
INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type, collection_months, created_at)
SELECT (g % 53) + 1, g, 'brand' || g,
       CASE WHEN g % 4 = 0 THEN 'competitor' ELSE 'own' END,
       (ARRAY[1, 3, 6, 12])[1 + (g % 4)],
       now() - (random() * 45 || ' days')::interval
FROM generate_series(1, 130) g;

-- 미러 신선도 대용 지표(설계 §3 타일7): 2시간 전 갱신 = 초록
INSERT INTO public.landing_stats (contents_count, influencers_count, total_views, avg_views,
                                  followers500to3k, followers3k10k, followers10k30k, followers30k50k, updated_at)
VALUES (2400, 200, 184000000, 76000, 41, 78, 52, 29, now() - interval '2 hours');

-- 탐색 미러 규모: 계정 200 · 콘텐츠 2,400 (랭킹 산출 건수 stat용)
INSERT INTO public.accounts (handle, display_name, followers, profile_image_url, external_link)
SELECT 'mockacct' || g, '목계정' || g, 500 + (random() * 49500)::bigint,
       'https://mock.test/p/' || g || '.jpg',
       CASE WHEN g % 5 = 0 THEN 'https://mock.test/link/' || g END
FROM generate_series(1, 200) g;

-- 피드 게시물은 조회수가 항상 NULL(CLAUDE.md 함정 — 미러도 같은 규칙)
INSERT INTO public.contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
                             video_duration, views, likes, comments, hype_score, hype_score_precise,
                             metric_captured_at, ad_marked)
SELECT 'MC' || g, 'mockacct' || ((g % 200) + 1), 'https://mock.test/t/' || g || '.jpg',
       '목 캡션 ' || g || ' #뷰티 #목데이터',
       now() - (random() * 120 || ' days')::interval,
       CASE WHEN g % 3 = 0 THEN 'FEED' ELSE 'REELS' END,
       CASE WHEN g % 3 = 0 THEN NULL ELSE round((10 + random() * 50)::numeric, 1) END,
       CASE WHEN g % 3 = 0 THEN NULL ELSE (random() * 300000)::bigint END,
       (random() * 12000)::bigint, (random() * 400)::bigint,
       (random() * 100)::bigint, round((random() * 100)::numeric, 4),
       now() - (random() * 3 || ' days')::interval,
       g % 7 = 0
FROM generate_series(1, 2400) g;
-- ============================================================================
-- END analysis
-- ============================================================================


-- ============================================================================
-- BEGIN monitoring  (psql -d monitoring)
-- ============================================================================
SELECT setseed(0.4242);

TRUNCATE brand_account RESTART IDENTITY CASCADE;   -- brand_hashtag(_post/_exclusion)·brand_tagged_post 동반
TRUNCATE target RESTART IDENTITY CASCADE;          -- detected_candidate 동반
TRUNCATE sweep_run RESTART IDENTITY;
TRUNCATE alarm_event RESTART IDENTITY;
TRUNCATE brand_call_count, target_call_count;
TRUNCATE brand_post_meta;                          -- 게시물 전역 테이블 — brand_account CASCADE가 못 지움

-- 브랜드 계정 130(app.brand_monitorings.brand_id와 1:1). 스윕은 10시간 전 완료 = 초록.
-- CLOSED 4건(127~130)은 closed_at을 채우고 스윕 흔적을 종결 이전으로 되돌린다 — 종결된 계정이
-- "10시간 전에 스윕됨"으로 남아 있으면 스윕 이력 패널이 거짓말을 한다.
-- last_swept_on도 KST 달력일(called_on과 같은 시간대 규약).
INSERT INTO brand_account (id, username, ig_user_id, followers, following, media_count, full_name,
                           biography, status, registered_at, closed_at, last_swept_on, last_swept_at,
                           collection_months, collection_started_at, backfill_completed_at, is_verified)
-- registered_at을 서브쿼리에서 먼저 뽑는다 — collection_started_at(등록 직후)·backfill_completed_at
-- (등록 + 5~180분)이 등록 시각에서 파생돼야 하기 때문. 독립 난수로 뽑으면 완료가 등록보다 앞서는
-- 행이 절반쯤 생겨 운영 건강 '백필 소요' 패널이 음수를 그린다(2026-08-22 실측).
SELECT g, 'brand' || g, '17841400' || lpad(g::text, 6, '0'),
       (random() * 500000)::bigint, (random() * 2000)::bigint, (random() * 3000)::bigint,
       '목브랜드' || g, '하니스 목 브랜드 계정 ' || g,
       CASE WHEN g > 126 THEN 'CLOSED' ELSE 'ACTIVE' END,
       reg,
       CASE WHEN g > 126 THEN now() - ((g - 122) || ' days')::interval END,
       CASE WHEN g > 126 THEN ((now() - ((g - 122) || ' days')::interval) AT TIME ZONE 'Asia/Seoul')::date
            ELSE (now() AT TIME ZONE 'Asia/Seoul')::date END,
       CASE WHEN g > 126 THEN now() - ((g - 122) || ' days')::interval - interval '3 hours'
            ELSE now() - interval '10 hours' END,
       (ARRAY[1, 3, 6, 12])[1 + (g % 4)],
       reg + (random() * 60 || ' seconds')::interval,
       reg + ((5 + random() * 175) || ' minutes')::interval,
       g % 9 = 0
FROM (SELECT g, CASE WHEN g > 126 THEN now() - interval '150 days'     -- 종결 계정은 등록이 확실히 앞서게
                     ELSE now() - (random() * 120 || ' days')::interval END AS reg
      FROM generate_series(1, 130) g) s;
SELECT setval('brand_account_id_seq', 130);

-- 브랜드별 해시태그 3개(수집 규칙)
INSERT INTO brand_hashtag (brand_id, tag, created_at)
SELECT b, '태그' || b || '_' || s, now() - (random() * 100 || ' days')::interval
FROM generate_series(1, 130) b, generate_series(1, 3) s;

-- 타깃 73 — TRACKING 44 / WATCHING 26 / EXPIRED 3 (status 어휘는 V1__core_tables.sql 주석이 정본)
-- fetch_failing 2건은 모니터링 대시보드 "fetch 실패 타깃" 패널용(홈 신호등 대상 아님 — 초록 유지).
-- EXPIRED 3건은 last_fetched_at을 closed_at 직전으로 — 종결된 타깃이 "10시간 전에 조회됨"으로
-- 남으면 추적 활성도 패널이 종결분까지 살아 있는 것으로 센다.
INSERT INTO target (id, type, username, short_code, status, registration_key, registered_at, expires_at,
                    user_id, tracked_short_code, tracked_since, last_fetched_at, closed_at,
                    fetch_failing, fail_reason, matched_keywords)
SELECT g,
       CASE WHEN g % 3 = 0 THEN 'POST' ELSE 'ACCOUNT' END,
       'acct' || g,
       CASE WHEN g % 3 = 0 THEN 'SRC' || g END,
       CASE WHEN g <= 44 THEN 'TRACKING' WHEN g <= 70 THEN 'WATCHING' ELSE 'EXPIRED' END,
       gen_random_uuid()::text,
       now() - (random() * 60 || ' days')::interval,
       CASE WHEN g > 70 THEN now() - interval '2 days' ELSE now() + interval '30 days' END,
       (g % 10) + 1,
       CASE WHEN g <= 44 THEN 'TSC' || g END,
       CASE WHEN g <= 44 THEN now() - (random() * 30 || ' days')::interval END,
       CASE WHEN g > 70 THEN now() - interval '2 days' - interval '30 minutes'
            ELSE now() - interval '10 hours' END,
       CASE WHEN g > 70 THEN now() - interval '2 days' END,
       g IN (12, 37),
       CASE WHEN g IN (12, 37) THEN 'HIKER_404' END,
       CASE WHEN g IN (7, 19) THEN jsonb_build_array('목키워드') END
FROM generate_series(1, 73) g;
SELECT setval('target_id_seq', 73);

-- 스윕 30일 전부 성공, 마지막이 10시간 전 = 신선도 초록(임계 26h)
INSERT INTO sweep_run (started_at, completed_at, ok)
SELECT s, s + interval '40 minutes', true
FROM (SELECT now() - interval '10 hours' - (d || ' days')::interval AS s
      FROM generate_series(0, 29) d) t;

-- called_on은 **KST 달력일이 정본**이다(V20260812100000·V20260812160000 주석 — was의 월초·자정
-- 경계 계산과 같은 시간대). 컨테이너 postgres는 UTC로 도니 `current_date`를 그대로 쓰면 UTC
-- 15~24시(=KST 익일 00~09시) 구간에서 "KST 오늘 행"이 없는 상태가 되고, 타일 6(오늘 Hiker 콜)이
-- 초록 시드인데 빨강으로 오진된다. 따라서 KST 달력일로 채운다 — 패널 쿼리도 같은 식이어야 한다.
-- Hiker 콜 30일: 브랜드 130 × 일 10~15콜 ≈ 48,750(실측 49,756)
INSERT INTO brand_call_count (brand_id, called_on, calls)
SELECT b, (now() AT TIME ZONE 'Asia/Seoul')::date - d, 10 + (random() * 5)::int
FROM generate_series(1, 130) b, generate_series(0, 29) d;

-- 타깃 콜 30일: 유저 10 × 일 9~15콜 ≈ 3,600(실측 3,536)
INSERT INTO target_call_count (user_id, called_on, calls)
SELECT u, (now() AT TIME ZONE 'Asia/Seoul')::date - d, 9 + (random() * 6)::int
FROM generate_series(1, 10) u, generate_series(0, 29) d;

-- 알림 대장 97건, 전부 발송 성공 = 초록.
-- 앞 4건은 최근 24h 안(seed-red가 "최근 3건 실패"로 뒤집을 때 24h 창에 확실히 걸리도록 고정).
-- event_type 어휘는 V3__user_id_and_alarm_event.sql CHECK가 정본(COLLECTION_STARTED/ENDED/
-- METRICS_HIDDEN/CONTENT_UNAVAILABLE — 'TRACKING_STARTED'는 없다).
INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after,
                         email_status, email_attempts, email_sent_at)
SELECT t.target_id, t.user_id, t.event_type, t.payload,
       t.o, t.o + interval '5 minutes', 'SENT', 1, t.o + interval '6 minutes'
FROM (SELECT (g % 44) + 1                                                       AS target_id,
             (g % 10) + 1                                                       AS user_id,
             (ARRAY['COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE'])[1 + (g % 4)] AS event_type,
             jsonb_build_object('mock', true, 'seq', g, 'shortCode', 'TSC' || ((g % 44) + 1)) AS payload,
             CASE WHEN g <= 4 THEN now() - (g || ' hours')::interval
                  ELSE now() - (random() * 30 || ' days')::interval END         AS o
      FROM generate_series(1, 97) g) t;

-- 해시태그 게시물 2,543 (verdict/verdict_source 어휘는 V20260811085943 CHECK가 정본)
INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, author_full_name,
                                taken_at, caption, content_type, thumbnail_url, likes, comments,
                                verdict, verdict_source, first_seen_at)
SELECT (g % 130) + 1, 'HP' || g,
       '태그' || ((g % 130) + 1) || '_' || (1 + g % 3),
       'author' || (g % 400), '목작성자' || (g % 400),
       now() - (random() * 90 || ' days')::interval,
       '목 해시태그 게시물 ' || g,
       CASE WHEN g % 4 = 0 THEN 'FEED' ELSE 'REELS' END,
       'https://mock.test/h/' || g || '.jpg',
       (random() * 5000)::bigint, (random() * 200)::bigint,
       CASE WHEN g % 10 = 0 THEN 'IRRELEVANT' WHEN g % 7 = 0 THEN 'UNCERTAIN' ELSE 'RELEVANT' END,
       CASE WHEN g % 5 = 0 THEN 'LLM' WHEN g % 3 = 0 THEN 'MENTION' ELSE 'RULE' END,
       now() - (random() * 30 || ' days')::interval
FROM generate_series(1, 2543) g;

-- 태그드 게시물 28,255
-- last_crawled_at은 taken_at 나이 티어(BrandCrawlPolicy: 14일 매일 / 30일 3일 / 90일 7일 /
-- 180일 30일)와 정합으로 생성한다 — 매일 티어는 오늘 새벽 일일 수집(10시간 전)에 갱신됐고,
-- 나머지 티어는 주기 이내. 독립 난수로 뽑으면 운영 건강 '갱신 미처리' 타일이 초록 시드에서
-- 빨강이 된다(2026-08-22).
INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at,
                               first_seen_at, comments_collected_count, last_crawled_at, enriched_at)
SELECT (g % 130) + 1, 'TP' || g, 'author' || (g % 2000), '17841500' || lpad((g % 2000)::text, 6, '0'),
       tk,
       now() - (random() * 40 || ' days')::interval,
       (random() * 30)::bigint,
       CASE WHEN tk >= now() - interval '14 days' THEN now() - interval '10 hours'
            WHEN tk >= now() - interval '30 days' THEN now() - (random() * 2.5 || ' days')::interval
            WHEN tk >= now() - interval '90 days' THEN now() - (random() * 6.5 || ' days')::interval
            ELSE now() - (random() * 29 || ' days')::interval END,
       CASE WHEN g % 4 <> 0 THEN now() - (random() * 10 || ' days')::interval END
FROM (SELECT g, now() - (random() * 180 || ' days')::interval AS tk
      FROM generate_series(1, 28255) g) s;

-- detected_candidate는 의도적으로 비워 둔다 — 실측 0건(설계 §3: 첫 감지는 후보 단계 없이
-- target.matched_keywords만 남기고 바로 자동 추적 전환).

-- 광고 표기 판정 시드(brand_post_meta 8,000 — 실측 밀도 없음: 08-17 신설·백필 진행 중 가정).
-- 판정 60%(g%5<3): verdict는 DISCLOSED 위주 4값,
-- source RULE 83%/LLM 17%(판정행의 g%10이 {0,1,2,5,6,7}만 나옴),
-- ad_judged_at은 최근 30일 + 오늘 확정분(g<=120은 오늘 새벽 — '오늘 판정' stat이 0이 안 되게).
-- 미판정 40%: judged_caption_hash NULL(잔여 스톡 — '미판정 잔여' stat).
INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
                             thumbnail_url, first_seen_at,
                             ad_verdict, ad_verdict_source, ad_violations, ad_evidence,
                             ad_judged_at, judged_caption_hash)
SELECT 'TP' || g, 'author' || (g % 2000),
       CASE WHEN g % 4 = 0 THEN 'FEED' ELSE 'REELS' END,
       (now() - (random() * 180 || ' days')::interval)::date,
       '목 캡션 ' || g,
       'https://mock.test/m/' || g || '.jpg',
       now() - (random() * 40 || ' days')::interval,
       CASE WHEN g % 5 >= 3 THEN NULL
            WHEN g % 20 = 0 THEN 'UNCERTAIN'
            WHEN g % 10 = 1 THEN 'INSUFFICIENT'
            WHEN g % 7  = 0 THEN 'NOT_DISCLOSED'
            ELSE 'DISCLOSED' END,
       CASE WHEN g % 5 >= 3 THEN NULL WHEN g % 10 < 7 THEN 'RULE' ELSE 'LLM' END,
       CASE WHEN g % 5 < 3 AND g % 7 = 0 THEN '["HIDDEN_PLACEMENT"]'::jsonb END,
       NULL,
       CASE WHEN g % 5 >= 3 THEN NULL
            WHEN g <= 120 THEN ((now() AT TIME ZONE 'Asia/Seoul')::date::timestamp AT TIME ZONE 'Asia/Seoul')
                               + interval '3 hours' + (g || ' seconds')::interval
            ELSE now() - (random() * 30 || ' days')::interval END,
       CASE WHEN g % 5 < 3 THEN md5('목 캡션 ' || g) END
FROM generate_series(1, 8000) g;

-- enrich 분포 조정(수집 현황 'enrich 잔여' stat용): 기존 시드는 25%가 무기한 NULL이라
-- 잔여 스탯이 상시 수천으로 뜬다 — 하루 넘게 미처리는 전부 메워 초록 시드의 잔여를 0으로.
-- 24h 이내 유입분의 NULL(자연 처리 대기)은 그대로 둔다 — '오늘' 타일들과 마찬가지로
-- 하니스 시드는 24시간 내 재적용 전제(시간이 지나면 이 대기분이 창을 넘어 잔여로 늙는다).
UPDATE brand_tagged_post SET enriched_at = first_seen_at + interval '2 hours'
 WHERE enriched_at IS NULL AND first_seen_at < now() - interval '24 hours';

-- 브랜드 스윕 시각 분포(Task 8 보강): 순차 처리 근사 — 오늘 02:00 KST부터 브랜드당 ~17초 간격.
-- 균일 시각이면 '오늘 스윕 소요'·'브랜드별 처리 간격' 패널이 0으로 뭉개진다.
UPDATE brand_account
SET last_swept_at = ((now() AT TIME ZONE 'Asia/Seoul')::date::timestamp AT TIME ZONE 'Asia/Seoul')
                    + interval '2 hours' + (id * interval '17 seconds') + (random() * interval '12 seconds')
WHERE closed_at IS NULL;

-- ============================================================================
-- END monitoring
-- ============================================================================
