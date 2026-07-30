#!/usr/bin/env bash
# 서버에서 실행(크론): 일일 pg_dump — 오프사이트는 Backblaze B2(rclone 리모트 `b2:`).
#
# ⚠ B2에는 용량 캡이 있다(종량제가 아니다). 07-27부터 며칠간 캡 초과로 업로드가 전량
# `403 storage_cap_exceeded`로 실패했다 — 실측 당시 버킷 4개 객체 10.266GiB. 원인은 crawler
# 덤프가 하루 ~1GiB씩 불어나는 것(07-25 4.85GiB → 07-26 5.27 → 07-27 6.41 → 07-28 7.54 →
# 07-29 8.48GiB)이라 옛 "B2 최신 30개" 정책이 요구하는 용량(~240GB)이 캡을 애초에 초과했다.
# crawler는 덤프 하나가 곧 GB급이라 "보관 개수"가 그대로 "필요 용량"이 된다 — 그래서 개수를
# 파일 상단 상수로 뽑아 용량 사고를 예방한다(B2_CRAWLER_KEEP).
#
# 보관 정책:
#   - analysis:   서버 7일 롤링 + B2 30일(기간) 롤링 — 덤프가 작아(105MB급) 캡 영향 없음
#   - crawler:    서버는 오프사이트 성패에 따라 1개(성공) / 3개(실패) 롤링(offsite_ok 분기 —
#                 B2가 막혀도 로컬 3개로 버틴다), B2는 최신 $B2_CRAWLER_KEEP개(개수) 롤링
#   - monitoring: 서버 7일 롤링 + B2 30일(기간) 롤링 — 캠페인·스냅샷뿐이라 덤프가 작다
#
# (07-26: Google Drive 무료 15GB가 crawler 덤프 증가로 초과되어 B2로 전환.
#  07-27~30: 전환한 B2도 무제한이 아니라 캡에 걸림 → 보관 개수 축소로 재대응)
set -euo pipefail

B2_CRAWLER_KEEP=5   # B2에 유지할 crawler 덤프 개수 — 하루 ~1GiB 증가 기준, 캡 재발 방지의 핵심 상수

BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
B2="b2:hypenow-backups"
cd "$DEPLOY_DIR"

docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | gzip > "$BACKUP_DIR/analysis-$STAMP.sql.gz"
ls -1t "$BACKUP_DIR"/analysis-*.sql.gz | tail -n +8 | xargs -r rm

docker compose exec -T postgres-raw pg_dump -U "$RAW_DB_USER" -d crawler \
  | gzip > "$BACKUP_DIR/crawler-$STAMP.sql.gz"

# monitoring은 같은 postgres 인스턴스의 별도 DB — 소유자 계정으로 덤프.
# 신규 추가분이라 통째로 비치명 처리한다 — 여기서 set -euo로 죽으면 위 analysis·crawler
# 덤프의 B2 업로드까지 통째로 유실된다(매일 04:10 크론, 기존 백업 경로 보호가 우선).
#   * 변수 미설정: 개통 전(README §13 미실행) — 건너뛴다
#   * 덤프 실패: DB 미생성·자격 불일치 등 — 경고 후 반쪽 파일 제거하고 계속
MONITORING_DUMP=""
if [ -n "${MONITORING_DB_USER:-}" ]; then
  if docker compose exec -T postgres pg_dump -U "$MONITORING_DB_USER" -d monitoring \
      | gzip > "$BACKUP_DIR/monitoring-$STAMP.sql.gz"; then
    MONITORING_DUMP="monitoring-$STAMP.sql.gz"
    ls -1t "$BACKUP_DIR"/monitoring-*.sql.gz | tail -n +8 | xargs -r rm
  else
    echo "경고: monitoring 덤프 실패 — 기존 백업은 계속(개통은 deploy/README.md §13)" >&2
    rm -f "$BACKUP_DIR/monitoring-$STAMP.sql.gz"   # 반쪽 파일 제거 → 아래 [ -f ] 가드가 자연 정합
  fi
else
  echo "경고: MONITORING_DB_USER 미설정 — monitoring 백업 건너뜀(개통은 deploy/README.md §13)" >&2
fi

# 오프사이트: rclone b2 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
# analysis·crawler 둘 다 성공해야 offsite_ok=true — 아래 crawler 로컬 보관 분기의 기준이 된다.
offsite_ok=false
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^b2:'; then
  if rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.gz" "$B2/analysis/" \
     && rclone copy "$BACKUP_DIR/crawler-$STAMP.sql.gz" "$B2/crawler/"; then
    offsite_ok=true
    rclone delete --min-age 30d "$B2/analysis/"
    # 최신 $B2_CRAWLER_KEEP개만 유지 (파일명 타임스탬프 기준 정렬) — 캡 초과의 직접 원인이라
    # 개수를 상단 상수로 관리한다(용량 여유 생기면 상수만 올릴 것)
    rclone lsf "$B2/crawler/" | sort | head -n -"$B2_CRAWLER_KEEP" \
      | while read -r f; do rclone deletefile "$B2/crawler/$f"; done

    # 덤프가 작아 analysis와 같은 30일 기간 롤링. analysis·crawler 업로드가 이미 성공한
    # 뒤라 캡 여유가 있다고 보고 시도 — monitoring 실패가 위 offsite_ok를 되돌리지는 않는다.
    if [ -f "$BACKUP_DIR/monitoring-$STAMP.sql.gz" ]; then
      rclone copy "$BACKUP_DIR/monitoring-$STAMP.sql.gz" "$B2/monitoring/"
      rclone delete --min-age 30d "$B2/monitoring/"
    fi
    echo "B2 업로드 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz${MONITORING_DUMP:+, $MONITORING_DUMP}"
  fi
fi

# 롤링 글롭은 crawler-[0-9]* — 수동 스냅샷(crawler-pre-*.sql.gz 등)은 롤링에서 제외
if "$offsite_ok"; then
  ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.gz | tail -n +2 | xargs -r rm
else
  echo "경고: B2 업로드 실패(캡 초과 등) 또는 리모트 미설정 — 서버 로컬 3개 유지로 버팀" >&2
  ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.gz | tail -n +4 | xargs -r rm
fi
# monitoring은 건너뛰거나 실패하면 목록에서 빠진다 — 크론 로그만으로 성패 판별 가능
echo "백업 완료: analysis-$STAMP.sql.gz, crawler-$STAMP.sql.gz${MONITORING_DUMP:+, $MONITORING_DUMP}"
