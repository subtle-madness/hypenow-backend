#!/usr/bin/env bash
# 서버에서 실행(크론): 일일 pg_dump — analysis(서버 7일 + Drive 30일 롤링),
# crawler(서버 3일 + Drive 최신 3개 롤링 — 덤프가 GB급이라 Drive 무료 용량에 맞춰 개수 제한),
# monitoring(서버 7일 + Drive 30일 롤링 — 캠페인·스냅샷뿐이라 덤프가 작다)
set -euo pipefail
BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
cd "$DEPLOY_DIR"

docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | gzip > "$BACKUP_DIR/analysis-$STAMP.sql.gz"
ls -1t "$BACKUP_DIR"/analysis-*.sql.gz | tail -n +8 | xargs -r rm

docker compose exec -T postgres-raw pg_dump -U "$RAW_DB_USER" -d crawler \
  | gzip > "$BACKUP_DIR/crawler-$STAMP.sql.gz"
# 롤링 글롭은 crawler-[0-9]* — 수동 스냅샷(crawler-pre-*.sql.gz 등)은 롤링에서 제외
ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.gz | tail -n +4 | xargs -r rm

# monitoring은 같은 postgres 인스턴스의 별도 DB — 소유자 계정으로 덤프.
# 개통 전(README §13 미실행)에는 .env에 계정이 없다 — set -u로 스크립트가 죽어
# 위 analysis·crawler 백업까지 못 쓰게 되는 걸 막으려고 건너뛰고 경고만 남긴다.
if [ -n "${MONITORING_DB_USER:-}" ]; then
  docker compose exec -T postgres pg_dump -U "$MONITORING_DB_USER" -d monitoring \
    | gzip > "$BACKUP_DIR/monitoring-$STAMP.sql.gz"
  ls -1t "$BACKUP_DIR"/monitoring-*.sql.gz | tail -n +8 | xargs -r rm
else
  echo "경고: MONITORING_DB_USER 미설정 — monitoring 백업 건너뜀(개통은 deploy/README.md §13)" >&2
fi

# 오프사이트: rclone gdrive 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
  rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.gz" gdrive:hypenow-backups/
  rclone delete --min-age 30d --max-depth 1 gdrive:hypenow-backups/   # crawler/ 하위는 개수 롤링만 적용

  rclone copy "$BACKUP_DIR/crawler-$STAMP.sql.gz" gdrive:hypenow-backups/crawler/
  # 최신 3개만 유지 (파일명 타임스탬프 기준 정렬)
  rclone lsf gdrive:hypenow-backups/crawler/ | sort | head -n -3 \
    | while read -r f; do rclone deletefile "gdrive:hypenow-backups/crawler/$f"; done

  # 덤프가 작아 analysis와 같은 30일 기간 롤링 — 위 루트 delete는 --max-depth 1이라 이 하위는 안 건드린다
  if [ -f "$BACKUP_DIR/monitoring-$STAMP.sql.gz" ]; then
    rclone copy "$BACKUP_DIR/monitoring-$STAMP.sql.gz" gdrive:hypenow-backups/monitoring/
    rclone delete --min-age 30d --max-depth 1 gdrive:hypenow-backups/monitoring/
  fi
  echo "Drive 업로드 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz"
else
  echo "경고: rclone gdrive 리모트 미설정 — 오프사이트 백업 건너뜀" >&2
fi
echo "백업 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz"
