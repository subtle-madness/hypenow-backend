#!/usr/bin/env bash
# test raw 계정·스키마 준비(멱등) — 태스크 K. cd-test가 서버에서 실행한다.
# analytics_dev: crawler 테이블(public) 읽기 전용 + analytics_dev 스키마 소유.
# analytics 스키마엔 USAGE도 주지 않는다 — 치환 누락 시 권한 오류로 즉사(fail-closed).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
: "${DEV_RAW_DB_PASSWORD:?서버 .env에 DEV_RAW_DB_PASSWORD 없음 — deploy/README.md dev 개통 체크리스트 선행}"
PG="${PG_CONTAINER:-deploy-postgres-raw-1}"

docker exec -i "$PG" psql -U crawler -d crawler -v ON_ERROR_STOP=1 \
  -v devpw="$DEV_RAW_DB_PASSWORD" <<'SQL'
DO $do$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analytics_dev') THEN
    CREATE ROLE analytics_dev LOGIN;
  END IF;
END
$do$;
ALTER ROLE analytics_dev PASSWORD :'devpw';
GRANT CONNECT ON DATABASE crawler TO analytics_dev;
GRANT USAGE ON SCHEMA public TO analytics_dev;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO analytics_dev;
ALTER DEFAULT PRIVILEGES FOR ROLE crawler IN SCHEMA public GRANT SELECT ON TABLES TO analytics_dev;
CREATE SCHEMA IF NOT EXISTS analytics_dev AUTHORIZATION analytics_dev;
SQL
echo "analytics_dev 준비 완료"
