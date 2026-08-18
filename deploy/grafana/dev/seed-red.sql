-- 그라파나 하니스 "빨간불" 시드 — seed.sql(초록) 위에 덧입혀 홈 신호등의 DB 타일을 전부 켠다.
--
-- 적용(seed.sql을 먼저 적용한 상태에서):
--   C="docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev"
--   sed -n '/^-- BEGIN analysis/,/^-- END analysis/p'     deploy/grafana/dev/seed-red.sql | $C -d analysis
--   sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed-red.sql | $C -d monitoring
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

-- 타일 5(스윕 신선도, 임계 26h) 빨강 — 최근 3일치 스윕 삭제 → 마지막 성공이 3일 10시간 전
DELETE FROM sweep_run WHERE started_at > now() - interval '3 days';

-- 타일 6(오늘 Hiker 콜 0건 = 스윕 불발) 빨강
DELETE FROM brand_call_count  WHERE called_on = current_date;
DELETE FROM target_call_count WHERE called_on = current_date;

-- 타일 9(알림 발송 실패 24h > 0) 빨강 — 가장 최근 3건을 FAILED로.
-- seed.sql이 앞 4건을 now-1~4h로 고정해 두므로 최근 3건은 항상 24h 창 안에 있다.
UPDATE alarm_event
   SET email_status = 'FAILED', email_sent_at = NULL, email_attempts = 3
 WHERE id IN (SELECT id FROM alarm_event ORDER BY occurred_at DESC LIMIT 3);

-- 모니터링 대시보드 「fetch 실패 타깃」도 함께 부풀린다(홈 타일은 아님)
UPDATE target
   SET fetch_failing = true, fail_reason = 'HIKER_401'
 WHERE id IN (SELECT id FROM target WHERE status = 'TRACKING' ORDER BY id LIMIT 6);
-- ============================================================================
-- END monitoring
-- ============================================================================
