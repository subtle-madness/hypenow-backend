-- 그라파나 하니스 "빨간불" 시드 — seed.sql(초록) 위에 덧입혀 홈 신호등의 DB 타일을 전부 켠다.
--
-- 적용(seed.sql을 먼저 적용한 상태에서):
--   C="docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev"
--   sed -n '/^-- BEGIN analysis/,/^-- END analysis/p'     deploy/grafana/dev/seed-red.sql | $C -d analysis
--   sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed-red.sql | $C -d monitoring
--   sed -n '/^-- BEGIN crawler/,/^-- END crawler/p'       deploy/grafana/dev/seed-red.sql | $C -d crawler
--
-- (`^-- ` 앵커 필요 — 앵커 없이 쓰면 이 안내 주석 줄이 구간 시작으로 잡힌다.)
--
-- 복원: 이 파일은 파괴적(DELETE 포함)이라 되돌리는 SQL이 없다. seed.sql을 재적용하면 된다
--   (seed.sql은 시드 대상 테이블을 TRUNCATE 후 다시 채운다). 스키마까지 의심스러우면
--   `down -v && up -d` → 20초 대기 → apply-migrations.sh → seed.sql.
--
-- 커버 범위: 홈 13타일 중 **DB로 판정하는 5개**(5 스윕 신선도 · 6 오늘 Hiker 콜 · 7 미러 신선도 ·
-- 8 멈춘 등록 · 9 알림 발송 실패). 1·2·3·4(Prometheus/Loki)와 10~13(node-exporter/JVM)은
-- DB가 아니라 여기서 못 만든다 — 그쪽은 실제 지표를 흘려 넣거나 임계값을 낮춰서 확인한다.
--
-- 09-04 수집 회귀 감시 트랙 추가분: crawler·monitoring에 "어제(KST) 수집이 사실상 멈췄다"
-- 시나리오를 덧입힌다(09-02~09-04 인스타 로그아웃 프로필 401 사고 재현 — raw_profile 정상
-- 2,600~3,850건/일이 며칠간 두 자릿수로 무너졌던 그 양상). hypenow-collection 알람 그룹의
-- 회귀 알람이 firing 하는지 로컬에서 확인하는 용도.

-- ============================================================================
-- BEGIN analysis  (psql -d analysis)
-- ============================================================================

-- 타일 7(미러 신선도, 임계 26h) 빨강 — 30시간 전 갱신으로 되돌린다
UPDATE landing_stats SET updated_at = now() - interval '30 hours';

-- 타일 8(멈춘 등록) 빨강 — completed_at NULL인 채 2시간 묵은 등록 1건
INSERT INTO app.monitoring_registrations (user_id, requested_at, completed_at, tracking_days)
VALUES (1, now() - interval '2 hours', NULL, 14);

-- 위 등록의 엔트리를 pending으로 — 「결과 미확정 상세」 패널도 함께 켠다
INSERT INTO app.monitoring_registration_entries (registration_id, seq, input, kind, result)
SELECT max(id), 1, 'acct_stuck', 'account', 'pending' FROM app.monitoring_registrations;
-- ============================================================================
-- END analysis
-- ============================================================================


-- ============================================================================
-- BEGIN monitoring  (psql -d monitoring)
-- ============================================================================

-- 타일 5(스윕 신선도, 임계 26h) 빨강 — 최근 3일치 스윕 삭제 → 마지막 성공이 3일 10시간 전.
-- (09-04 수집 회귀 감시 트랙이 요구하는 "sweep_run 최근 행 3일 전" 시나리오도 이 문장이 그대로 충족)
DELETE FROM sweep_run WHERE started_at > now() - interval '3 days';

-- 타일 6(오늘 Hiker 콜 0건 = 스윕 불발) 빨강.
-- called_on은 KST 달력일이 정본이다(V20260812100000 주석) — 컨테이너 postgres가 UTC라
-- `current_date`로 지우면 UTC 15~24시(=KST 익일)에 엉뚱한 날을 지운다. seed.sql과 같은 식을 쓴다.
DELETE FROM brand_call_count  WHERE called_on = (now() AT TIME ZONE 'Asia/Seoul')::date;
DELETE FROM target_call_count WHERE called_on = (now() AT TIME ZONE 'Asia/Seoul')::date;

-- 타일 9(알림 발송 실패 24h > 0) 빨강 — 가장 최근 3건을 FAILED로.
-- seed.sql이 앞 4건을 now-1~4h로 고정해 두므로 최근 3건은 항상 24h 창 안에 있다.
UPDATE alarm_event
   SET email_status = 'FAILED', email_sent_at = NULL, email_attempts = 3
 WHERE id IN (SELECT id FROM alarm_event ORDER BY occurred_at DESC LIMIT 3);

-- 모니터링 대시보드 「fetch 실패 타깃」도 함께 부풀린다(홈 타일은 아님)
UPDATE target
   SET fetch_failing = true, fail_reason = 'HIKER_401'
 WHERE id IN (SELECT id FROM target WHERE status = 'TRACKING' ORDER BY id LIMIT 6);

-- [브랜드] 운영 '적재 결과' row 빨강 — 오늘 신규 태그 게시물 0(스윕 불발 양상) + 백필 미완 브랜드 4
--                          + enrich 잔여 600(빨강 임계 500 초과)
DELETE FROM brand_tagged_post
 WHERE (first_seen_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date;
UPDATE brand_account SET last_swept_on = NULL WHERE id IN (1, 2, 3, 4);
UPDATE brand_tagged_post SET enriched_at = NULL
 WHERE short_code IN (SELECT short_code FROM brand_tagged_post
                       WHERE first_seen_at < now() - interval '24 hours'
                       ORDER BY short_code LIMIT 600);

-- [브랜드] 광고 표기 빨강 — 오늘 판정 0건(판정 잡 정지 양상)
UPDATE brand_post_meta SET ad_judged_at = ad_judged_at - interval '2 days'
 WHERE (ad_judged_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date;

-- ---- 09-04 수집 회귀 감시 트랙 추가분 ----------------------------------------

-- raw.fetch_payload 어제(KST) 300건으로 축소(정상 5,000~15,000/일 대비 붕괴) — id 오름차순 300건만
-- 남기고 나머지 삭제. `OFFSET n`은 앞 n건을 건너뛰고 그 뒤를 전부 돌려주므로 "n건만 남기고 삭제"에
-- 그대로 쓸 수 있다(랜덤 샘플링 없이 결정적).
DELETE FROM raw.fetch_payload WHERE id IN (
    SELECT id FROM raw.fetch_payload
     WHERE (fetched_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
     ORDER BY id OFFSET 300
);

-- brand_post_snapshot 어제(KST) likes null ~60%로 악화(정상 시드는 ~16%) — 수집 자체는 도는데
-- 좋아요 값만 못 받아오는 부분 장애 양상. likes_hidden은 false로 둔다 — 알람 룰
-- quality-brand-post-likes-null-daily가 "숨김 아닌데 null"만 세므로(숨김 좋아요는 정상), hidden=true로
-- 시드하면 룰이 안 울린다(09-05 dev 실측: 62% null인데 룰 A=0.08).
UPDATE brand_post_snapshot
   SET likes = NULL, likes_hidden = false
 WHERE captured_on = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
   AND random() < 0.6;
-- ============================================================================
-- END monitoring
-- ============================================================================


-- ============================================================================
-- BEGIN crawler  (psql -d crawler)
-- ============================================================================

-- raw_profile 어제(KST) 30건으로 축소(정상 ~3,000/일 대비 09-02~09-04 사고 재현 —
-- 실측 09-02 31·09-03 76건과 같은 자릿수). raw_profile은 아무 테이블도 참조하지 않아 그냥 지운다.
DELETE FROM raw_profile WHERE id IN (
    SELECT id FROM raw_profile
     WHERE (captured_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
     ORDER BY id OFFSET 30
);

-- crawl_run REELS 어제(KST) 40건으로 축소(정상 ~3,000/일). raw_media_page가 REELS 풀만 참조하므로
-- (seed.sql 주석 참조) crawl_run을 지우기 전에 그 crawl_run_id를 참조하는 raw_media_page부터
-- 먼저 지워야 FK 위반이 안 난다 — 삭제 대상 id 집합을 CTE로 한 번만 계산해 두 DELETE에 재사용.
WITH doomed AS (
    SELECT id FROM crawl_run
     WHERE job = 'REELS'
       AND (started_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
     ORDER BY id OFFSET 40
)
DELETE FROM raw_media_page WHERE crawl_run_id IN (SELECT id FROM doomed);

WITH doomed AS (
    SELECT id FROM crawl_run
     WHERE job = 'REELS'
       AND (started_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
     ORDER BY id OFFSET 40
)
DELETE FROM crawl_run WHERE id IN (SELECT id FROM doomed);
-- ============================================================================
-- END crawler
-- ============================================================================
