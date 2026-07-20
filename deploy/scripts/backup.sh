#!/usr/bin/env bash
# 서버에서 실행(크론): 일일 pg_dump — analysis(서버 7일 + Drive 30일 롤링),
# crawler(서버 3일 + Drive 최신 3개 롤링 — 덤프가 GB급이라 Drive 무료 용량에 맞춰 개수 제한)
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

# 오프사이트: rclone gdrive 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
  rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.gz" gdrive:hypenow-backups/
  rclone delete --min-age 30d --max-depth 1 gdrive:hypenow-backups/   # crawler/ 하위는 개수 롤링만 적용

  rclone copy "$BACKUP_DIR/crawler-$STAMP.sql.gz" gdrive:hypenow-backups/crawler/
  # 최신 3개만 유지 (파일명 타임스탬프 기준 정렬)
  rclone lsf gdrive:hypenow-backups/crawler/ | sort | head -n -3 \
    | while read -r f; do rclone deletefile "gdrive:hypenow-backups/crawler/$f"; done
  echo "Drive 업로드 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz"
else
  echo "경고: rclone gdrive 리모트 미설정 — 오프사이트 백업 건너뜀" >&2
fi
echo "백업 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz"
