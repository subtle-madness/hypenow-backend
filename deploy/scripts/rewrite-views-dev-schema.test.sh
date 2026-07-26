#!/usr/bin/env bash
# rewrite-views-dev-schema.sh 픽스처 테스트 — 실제 뷰 SQL의 4가지 패턴을 커버:
# ①스키마 생성(무점) ②뷰 정의·참조 ③따옴표 설정 키(치환 금지) ④달러 인용 함수 본문·DROP
set -euo pipefail
cd "$(dirname "$0")"

actual=$(./rewrite-views-dev-schema.sh <<'IN'
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE OR REPLACE VIEW analytics.v_base_influencer AS
SELECT id FROM influencer;
analytics.v_line_start_case AS x
SELECT COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12),
       analytics.hype_score(t, a, b) FROM analytics.v_contents
CREATE OR REPLACE FUNCTION analytics.refresh_snapshot_cache() RETURNS bigint LANGUAGE plpgsql AS $$
BEGIN
  TRUNCATE analytics.content_snapshot_cache;
END $$;
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint);
IN
)

expected=$(cat <<'OUT'
CREATE SCHEMA IF NOT EXISTS analytics_dev;
CREATE OR REPLACE VIEW analytics_dev.v_base_influencer AS
SELECT id FROM influencer;
analytics_dev.v_line_start_case AS x
SELECT COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12),
       analytics_dev.hype_score(t, a, b) FROM analytics_dev.v_contents
CREATE OR REPLACE FUNCTION analytics_dev.refresh_snapshot_cache() RETURNS bigint LANGUAGE plpgsql AS $$
BEGIN
  TRUNCATE analytics_dev.content_snapshot_cache;
END $$;
DROP FUNCTION IF EXISTS analytics_dev.hype_score(text, bigint);
OUT
)

if [ "$actual" != "$expected" ]; then
  echo "치환 결과 불일치:"
  diff <(echo "$expected") <(echo "$actual") || true
  exit 1
fi
echo "OK"
