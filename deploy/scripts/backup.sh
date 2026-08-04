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
#   - analysis:   서버 3일 롤링 + B2 7일(기간) 롤링 — 덤프가 작지만(105MB급) 분석 결과는
#                 raw에서 재파생 가능(LLM 재호출 비용만 부담)이라 길게 들 이유가 없다
#   - crawler:    서버는 오프사이트 성패에 따라 1개(성공) / $LOCAL_CRAWLER_KEEP개(실패) 롤링
#                 (offsite_ok 분기 — B2가 막혀도 로컬 사본 + 수동 pull(pull-backup.sh)로 버팀),
#                 B2는 최신 $B2_CRAWLER_KEEP개(개수) 롤링. 덤프 전 선-회전으로 구 사본을
#                 KEEP-1개까지 줄여 "구 사본 + 신규 덤프" 동시 존재 피크를 없앤다
#                 (08-03 hypenow-disk-high: 구 3 + 신규 1 공존으로 루트 디스크 85% 순간 초과).
#   - monitoring: 서버 3일 롤링 + B2 7일(기간) 롤링 — 캠페인·스냅샷뿐이라 덤프가 작다
#
# (07-26: Google Drive 무료 15GB가 crawler 덤프 증가로 초과되어 B2로 전환.
#  07-27~30: 전환한 B2도 무제한이 아니라 캡에 걸림 → 보관 개수 축소로 재대응.
#  08-04: B2 캡 초과가 07-29부터 지속돼 로컬 3개 폴백이 매일 새벽 디스크 알람을 유발
#  → 로컬 2개 + 선-회전으로 재대응. 오프사이트 공백은 수동 pull이 보완.
#  08-04: B2 유료 전환(캡 상향) 준비하며 보관 전반 축소 — crawler 5→3개,
#  analysis·monitoring 서버 7→3일·B2 30→7일. 덤프 세트가 개수×일일증가로 불어나는
#  구조라(월 +90GB급) 복원 창을 실사용 기준 3일/7일로 맞춘 것.
#  08-04: 압축 gzip→zstd 전환 — 2 vCPU에서 gzip이 백업 시간대 CPU를 80% 선까지 밀어
#  (sar 실측 user 58~62%) CPU 85% 알람 문턱 직하에서 매일 돌던 것을 해소. 압축률 동급,
#  압축 CPU 수 배 절감. 전환기엔 구 .sql.gz와 신 .sql.zst가 롤링창 동안 공존하므로
#  로컬 롤링 글롭은 확장자 불문(.sql.*)으로 두 세대를 한 세트로 회전시킨다.)
set -euo pipefail

B2_CRAWLER_KEEP=3     # B2에 유지할 crawler 덤프 개수 — 복원 창 3일, 덤프가 11GiB급이라 개수가 곧 용량(08-04 5→3)
LOCAL_CRAWLER_KEEP=2  # B2 실패 시 서버에 남길 crawler 덤프 개수 — 로컬 pull 사본 전제(08-04 축소)

BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
B2="b2:hypenow-backups"
cd "$DEPLOY_DIR"

docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | zstd -q > "$BACKUP_DIR/analysis-$STAMP.sql.zst"
ls -1t "$BACKUP_DIR"/analysis-*.sql.* | tail -n +4 | xargs -r rm

# crawler 덤프는 GB급이라 신규 덤프와 구 사본이 겹치는 동안 디스크 피크가 생긴다 —
# 덤프 전에 구 사본을 KEEP-1개까지 선-회전해 피크를 정상 상태 수준으로 유지한다.
# 반쪽 파일이 다음 날 선-회전에서 정상본을 밀어내지 않도록 임시 이름(.tmp, 롤링 글롭
# crawler-[0-9]* 비매치)으로 받고 성공 시에만 편입한다(set -euo로 실패 시 즉사 → mv 미도달).
# 선-회전은 비치명 — 사본이 0개인 첫 실행(서버 재구축 §14)에서 ls가 실패해도 덤프는 계속.
rm -f "$BACKUP_DIR"/.crawler-*.sql.*.tmp
{ ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.* 2>/dev/null | tail -n +"$LOCAL_CRAWLER_KEEP" | xargs -r rm; } || true
docker compose exec -T postgres-raw pg_dump -U "$RAW_DB_USER" -d crawler \
  | zstd -q > "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp"
mv "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp" "$BACKUP_DIR/crawler-$STAMP.sql.zst"

# monitoring은 같은 postgres 인스턴스의 별도 DB — 소유자 계정으로 덤프.
# 신규 추가분이라 통째로 비치명 처리한다 — 여기서 set -euo로 죽으면 위 analysis·crawler
# 덤프의 B2 업로드까지 통째로 유실된다(매일 04:10 크론, 기존 백업 경로 보호가 우선).
#   * 변수 미설정: 개통 전(README §13 미실행) — 건너뛴다
#   * 덤프 실패: DB 미생성·자격 불일치 등 — 경고 후 반쪽 파일 제거하고 계속
MONITORING_DUMP=""
if [ -n "${MONITORING_DB_USER:-}" ]; then
  if docker compose exec -T postgres pg_dump -U "$MONITORING_DB_USER" -d monitoring \
      | zstd -q > "$BACKUP_DIR/monitoring-$STAMP.sql.zst"; then
    MONITORING_DUMP="monitoring-$STAMP.sql.zst"
    ls -1t "$BACKUP_DIR"/monitoring-*.sql.* | tail -n +4 | xargs -r rm
  else
    echo "경고: monitoring 덤프 실패 — 기존 백업은 계속(개통은 deploy/README.md §13)" >&2
    rm -f "$BACKUP_DIR/monitoring-$STAMP.sql.zst"   # 반쪽 파일 제거 → 아래 [ -f ] 가드가 자연 정합
  fi
else
  echo "경고: MONITORING_DB_USER 미설정 — monitoring 백업 건너뜀(개통은 deploy/README.md §13)" >&2
fi

# 오프사이트: rclone b2 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
# analysis·crawler 둘 다 성공해야 offsite_ok=true — 아래 crawler 로컬 보관 분기의 기준이 된다.
offsite_ok=false
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^b2:'; then
  if rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.zst" "$B2/analysis/" \
     && rclone copy "$BACKUP_DIR/crawler-$STAMP.sql.zst" "$B2/crawler/"; then
    offsite_ok=true
    # ⚠ 이 아래 뒷정리(기간 롤링·개수 트리밍·monitoring 업로드)는 **전부 비치명이어야 한다**.
    # 여기는 if 본문이라 set -e가 살아 있어서 한 줄이라도 실패하면 스크립트가 즉사하고,
    # 그러면 파일 끝의 **crawler 로컬 보관 정리가 건너뛰어져** 로컬 덤프가 하루 ~8.5GiB씩
    # 무한 누적된다(디스크 만재 → 07-30에 87%까지 찬 그 사고의 재발). 지켜야 할 본질인
    # analysis·crawler 오프사이트 사본은 이미 위 조건절에서 성공했으므로, 뒷정리 실패는
    # 경고만 남기고 다음 실행에서 재시도하면 된다.
    rclone delete --min-age 7d "$B2/analysis/" \
      || echo "경고: B2 analysis 기간 롤링 실패 — 다음 실행에서 재시도" >&2
    # 최신 $B2_CRAWLER_KEEP개만 유지 (파일명 타임스탬프 기준 정렬) — 캡 초과의 직접 원인이라
    # 개수를 상단 상수로 관리한다(용량 여유 생기면 상수만 올릴 것)
    { rclone lsf "$B2/crawler/" | sort | head -n -"$B2_CRAWLER_KEEP" \
        | while read -r f; do rclone deletefile "$B2/crawler/$f"; done; } \
      || echo "경고: B2 crawler 개수 트리밍 실패 — 다음 실행에서 재시도" >&2

    # 덤프가 작아 analysis와 같은 7일 기간 롤링. analysis·crawler 업로드가 이미 성공한
    # 뒤라 캡 여유가 있다고 보고 시도 — monitoring 실패가 위 offsite_ok를 되돌리지 않는다.
    if [ -f "$BACKUP_DIR/monitoring-$STAMP.sql.zst" ]; then
      if rclone copy "$BACKUP_DIR/monitoring-$STAMP.sql.zst" "$B2/monitoring/"; then
        rclone delete --min-age 7d "$B2/monitoring/" \
          || echo "경고: B2 monitoring 기간 롤링 실패 — 다음 실행에서 재시도" >&2
      else
        echo "경고: monitoring B2 업로드 실패 — analysis·crawler 오프사이트는 정상" >&2
      fi
    fi
    echo "B2 업로드 완료: analysis-$STAMP.sql.zst, crawler-$STAMP.sql.zst${MONITORING_DUMP:+, $MONITORING_DUMP}"
  fi
fi

# 롤링 글롭은 crawler-[0-9]* — 수동 스냅샷(crawler-pre-* 등)은 롤링에서 제외
if "$offsite_ok"; then
  ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.* | tail -n +2 | xargs -r rm
else
  echo "경고: B2 업로드 실패(캡 초과 등) 또는 리모트 미설정 — 서버 로컬 ${LOCAL_CRAWLER_KEEP}개 유지로 버팀" >&2
  ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.* | tail -n +"$((LOCAL_CRAWLER_KEEP + 1))" | xargs -r rm
fi
# monitoring은 건너뛰거나 실패하면 목록에서 빠진다 — 크론 로그만으로 성패 판별 가능
echo "백업 완료: analysis-$STAMP.sql.zst, crawler-$STAMP.sql.zst${MONITORING_DUMP:+, $MONITORING_DUMP}"
