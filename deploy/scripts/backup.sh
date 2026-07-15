#!/usr/bin/env bash
# 서버에서 실행(크론): analysis DB 일일 pg_dump — 서버 7일 롤링 + Google Drive 오프사이트(30일 롤링)
set -euo pipefail
BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
cd "$DEPLOY_DIR"
docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | gzip > "$BACKUP_DIR/analysis-$STAMP.sql.gz"
ls -1t "$BACKUP_DIR"/analysis-*.sql.gz | tail -n +8 | xargs -r rm

# 오프사이트: rclone gdrive 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
  rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.gz" gdrive:hypenow-backups/
  rclone delete --min-age 30d gdrive:hypenow-backups/
  echo "Drive 업로드 완료: analysis-$STAMP.sql.gz"
else
  echo "경고: rclone gdrive 리모트 미설정 — 오프사이트 백업 건너뜀" >&2
fi
echo "백업 완료: analysis-$STAMP.sql.gz"
