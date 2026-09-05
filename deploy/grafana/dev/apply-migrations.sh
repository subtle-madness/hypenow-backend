#!/usr/bin/env bash
# 레포 Flyway SQL을 버전순으로 로컬 하니스 DB에 적용한다. 사용: ./apply-migrations.sh
#
# 하니스는 1회성이라 Flyway 이력 테이블(flyway_schema_history)은 만들지 않는다 — 리셋은
# `docker compose -f deploy/grafana/dev/compose.dev.yaml down -v && up -d` 후 이 스크립트 재실행.
#
# 운영에서 Flyway 밖(런북·db/init)에 있는 전제 3가지를 여기서 대신 만든다:
#   1) 롤 was_reader / alarm_reader  — monitoring V2가 GRANT 대상으로 참조
#   2) analysis DB의 app 스키마      — was 마이그레이션은 스키마 무접두(Flyway schemas=app 전제)
#   3) app 적용 시 search_path=app   — 위와 같은 이유로 세션 search_path를 app으로 고정
set -euo pipefail
cd "$(dirname "$0")/../../.."   # 레포 루트

COMPOSE="docker compose -f deploy/grafana/dev/compose.dev.yaml"

apply_dir() { # $1=디렉토리 $2=DB $3=search_path(빈값이면 기본) $4=적용 전 SQL(옵션)
  local dir="$1" db="$2" sp="${3:-}" pre="${4:-}"
  local -a opts=(exec -T)
  [ -n "$sp" ] && opts+=(-e "PGOPTIONS=-c search_path=$sp")
  opts+=(postgres psql -v ON_ERROR_STOP=1 -q -U dev -d "$db")

  [ -n "$pre" ] && printf '%s\n' "$pre" | $COMPOSE "${opts[@]}"

  # 버전 숫자순 정렬 — 사전순은 틀린다(`V2__` > `V20260730…`).
  ls "$dir" | awk -F'__' '{v=$1; sub(/^V/,"",v); print v, $0}' | sort -n -s -k1,1 | cut -d' ' -f2- | \
  while read -r f; do echo "  $db${sp:+[$sp]} <- $f"; $COMPOSE "${opts[@]}" < "$dir/$f"; done
}

# 마이그레이션이 GRANT하는 롤을 선생성(운영은 런북 소관 — CREATE ROLE은 Flyway 밖)
ROLES="DO \$\$ BEGIN CREATE ROLE was_reader; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;
DO \$\$ BEGIN CREATE ROLE alarm_reader; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;"

# was 마이그레이션은 스키마 무접두(`CREATE TABLE users`) — Flyway가 schemas=app으로 만들어 주는
# 스키마를 여기서 미리 만들고 search_path로 잡아 준다.
APP_SCHEMA="CREATE SCHEMA IF NOT EXISTS app;"

apply_dir analytics/src/main/resources/db/migration/analysis analysis ""    "$ROLES"
apply_dir was/src/main/resources/db/migration/app            analysis app  "$APP_SCHEMA"
apply_dir monitoring/src/main/resources/db/migration         monitoring "" "$ROLES"
# crawler 마이그레이션은 롤·스키마·확장 전제가 없다(전수 확인 — public 스키마·bigserial·jsonb뿐).
apply_dir crawler/src/main/resources/db/migration            crawler ""    ""
echo "완료"
