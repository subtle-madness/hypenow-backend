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
#   - crawler:    B2가 살아 있으면 덤프를 B2로 직스트리밍(pg_dump|zstd|tee|rclone rcat) —
#                 성공 시 서버 로컬 0개(B2 사본 확인됨), 실패 시 로컬 전용 덤프로 폴백해
#                 $LOCAL_CRAWLER_KEEP개 롤링(offsite_ok 분기 — B2가 막혀도 로컬 사본 +
#                 수동 pull(pull-backup.sh)로 버팀). B2는 최신 $B2_CRAWLER_KEEP개(개수) 롤링.
#                 덤프 전 선-회전으로 과거 실패일 폴백 사본을 KEEP-1개까지 줄여 "구 사본 +
#                 신규 덤프" 동시 존재 피크를 없앤다
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
# (08-20: B2 업로드 대역 제한 도입 — 업로드가 실효 ~17MiB/s(08-19 실측: 11.4GiB/11.4분)로
#  디스크를 읽어 2 vCPU 서버 iowait가 60~79%까지 올라 외부 health 프로브가 2~3분 실패
#  (08-20 04:41 KST hypenow-api-unreachable 알람). ionice는 대안이 못 된다 — 서버 sda의
#  IO 스케줄러가 `none`이라 IO 우선순위가 무시된다(실측 확인). rclone --bwlimit이 읽기
#  속도 자체를 깎는 유일한 지렛대. 같은 사고의 나머지 절반은 백업 크론을 야간 분석
#  파이프라인(19:25 refresh→19:30 미러→20:00 분석) 밖 15:00 UTC로 옮겨 해소 — setup-server.sh 참조.)
# (08-25: crawler B2 직스트리밍 전환 + B2 가드 레이스 수정 —
#  ① 종전 `rclone listremotes | grep -q '^b2:'` 가드는 b2:가 출력 첫 줄이라 grep -q가
#  즉시 종료하며 파이프를 닫고, 나머지 줄을 쓰던 rclone이 SIGPIPE(141)로 죽어 pipefail
#  아래서 가드 전체가 실패 판정됐다 — 서버 실측 100회 중 5회(5%), 08-23 무음 실패의 원인.
#  rclone 에러 출력 전에 죽는 데다 2>/dev/null이 stderr마저 버려 로그에 아무 흔적이 없었다.
#  파이프 없는 명령 치환 + 문자열 매치로 교체, stderr는 더 이상 버리지 않는다.
#  ② 덤프→업로드 2단계 직렬(덤프 19분 + 업로드 19분, 로컬 11GiB 상주, 업로드 구간 재읽기
#  iowait 24% — 08-24 sar 실측)을 단일 스트리밍 파이프라인(~20분)으로 합침. 성공 시 로컬
#  crawler 사본 0개(-11GiB — 08-24 disk-high 87%의 백업 몫 해소), 실패 시 로컬 덤프 폴백.
#  ③ 동시화로 압축·업로드 CPU가 같은 시간대에 겹치므로(덤프 구간 idle 13% 실측) zstd·rclone을
#  nice -n 19로 실행 — WAS·크롤러가 CPU 우선, 부족분은 파이프 역압으로 백업만 감속한다.)
# (08-27: CPU 상한(cgroup CPUQuota) 자기 래핑 도입 — ③의 동시화 첫 실행(08-26 KST 00:12)이
#  hypenow-api-cpu-high를 발화시켰다: 직렬이 겹치며 CPU 85~89%가 21분 지속. nice는 서비스
#  지연은 지키지만(iowait 1.5~2.5%, api-unreachable 미발화) OCI CpuUtilization은 nice 사용분도
#  집계해 알람은 그대로 운다. 실행 중 set-property로 스텝 실측한 결과(60%→전체 86%,
#  45%→80%, 35%→57~60%) 상한 밖 postgres-raw 백엔드가 ~0.5코어를 더 쓰고 크롤 잡이 1분
#  단위 +20~30%p 출렁여, 여유까지 반영한 안전선이 35%다. 소요 +30%(28→36분)로 15:00 크론
#  슬롯 안에 수용. 파이프라인 유닛만 상한해도 pg_dump 클라이언트가 감속하면 postgres 쪽도
#  배압으로 따라 줄어 전체가 내려간다.)
# (09-02: 오프사이트 암호화 도입 — B2로 나가는 스트림에만 age 공개키 암호화(`.sql.zst.age`).
#  덤프에 사용자 이메일 등 개인정보가 담기는데 B2 계정 유출 시 그대로 노출되는 구멍을 막는다.
#  서버 로컬 사본은 평문 유지 — 서버가 침해되면 어차피 라이브 DB가 읽혀 암호화 이득이 없고,
#  로컬 즉시 복원 경로(zstdcat|psql)를 지키는 쪽이 이득. 복호화 비밀키는 서버에 없다(그게
#  목적) — OCI Vault 시크릿 `hypenow-backup-age-key` + 로컬 맥 `~/.config/age/`(README §6-2).
#  age 미설치면 B2 업로드 자체를 생략한다(기밀성 fail-closed) — 가용성은 기존 로컬 폴백이
#  담당하고, 크론 로그 경고로 드러난다. 전환기 B2엔 구 평문 `.zst`와 신 `.zst.age`가 롤링창
#  동안 공존하며 자연 소거된다 — 기간 롤링·개수 트리밍 글롭은 확장자 불문이라 무수정.)
set -euo pipefail

B2_CRAWLER_KEEP=3     # B2에 유지할 crawler 덤프 개수 — 복원 창 3일, 덤프가 11GiB급이라 개수가 곧 용량(08-04 5→3)
LOCAL_CRAWLER_KEEP=2  # B2 실패 시 서버에 남길 crawler 덤프 개수 — 로컬 pull 사본 전제(08-04 축소)
B2_BWLIMIT=10M        # rclone 업로드 대역 상한 — 무제한 시 실효 17MiB/s가 iowait를 포화시킴(08-20 알람).
                      # 10M이면 crawler 11GiB급 업로드가 ~20분 — 크론 슬롯(15:00, 다음 배치는 16:00 크롤)에 여유
BACKUP_CPUQUOTA=35%   # 백업 유닛 CPU 상한(2코어 중 0.35코어) — 08-27 실험 실측 안전선(상단 주석).
                      # 조정 시 알람 문턱 85% 대비 동거 부하(+20~30%p) 여유를 남길 것
# 오프사이트 암호화 공개키(09-02) — 공개키라 커밋 무해. 복호화 비밀키는 OCI Vault
# `hypenow-backup-age-key` + 로컬 맥에만(서버에 두면 암호화가 무의미해진다 — README §6-2).
AGE_RECIPIENT=age1ygrprm2yc83jeymm3xf75e7hnvpep043egjq423arewqmrct0s5qujuuzh

# CPU 상한 자기 래핑 — 크론이 그냥 실행해도 systemd transient 유닛(CPUQuota) 안에서 돌게
# 재실행한다(크론탭 무수정 — 상한값·로직이 이 파일 하나에 남아 버전 관리된다).
#   * 유닛명 고정(backup-capped): 전일 실행이 아직 살아 있으면 systemd-run이 이름 충돌로
#     실패한다 — 이중 백업 방지 가드를 겸한다. --collect가 실패 유닛 잔재를 자동 회수.
#   * StandardOutput=append: 유닛의 stdout은 크론의 `>> backup.log` 리다이렉트에 닿지 않아
#     직접 backup.log에 붙인다(수동 실행도 동일하게 로그로 감 — 화면 출력 없음에 주의).
#   * systemd-run/sudo 불가 환경이면 상한 없이 진행 — 래퍼 때문에 백업 자체가 죽는 것이
#     상한 없는 백업(최악: WARNING 알람 1회)보다 나쁘다.
if [ -z "${BACKUP_CPU_CAPPED:-}" ]; then
  if command -v systemd-run >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    exec sudo -n systemd-run --quiet --wait --collect --unit backup-capped \
      --uid "$(id -un)" --gid "$(id -gn)" \
      -p CPUQuota="$BACKUP_CPUQUOTA" \
      -p Environment="HOME=$HOME" -p Environment=BACKUP_CPU_CAPPED=1 \
      -p WorkingDirectory="$HOME" \
      -p StandardOutput="append:$HOME/backups/backup.log" \
      -p StandardError="append:$HOME/backups/backup.log" \
      /bin/bash "$0" "$@"
  fi
  echo "경고: systemd-run/sudo 사용 불가 — CPU 상한 없이 백업을 계속한다" >&2
fi

BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
B2="b2:hypenow-backups"
cd "$DEPLOY_DIR"

docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | zstd -q > "$BACKUP_DIR/analysis-$STAMP.sql.zst"
ls -1t "$BACKUP_DIR"/analysis-*.sql.* | tail -n +4 | xargs -r rm

# B2 사용 가능 판정 — crawler 스트리밍과 아래 업로드 블록이 공유한다.
# ⚠ 파이프(`listremotes | grep -q`) 금지: grep -q의 조기 종료가 rclone을 SIGPIPE로 죽여
# pipefail 아래서 ~5% 확률로 오판한다(08-23 무음 실패 — 상단 08-25 주석). 명령 치환은
# 출력을 전량 읽어 조기 종료가 원천 불가. stderr는 버리지 않는다(무음 실패 증거 보존).
b2_ready=false
if command -v rclone >/dev/null 2>&1; then
  if ! command -v age >/dev/null 2>&1; then
    # 암호화 없이는 올리지 않는다(09-02 fail-closed) — 로컬 폴백이 백업 공백을 막는다
    echo "경고: age 미설치 — 오프사이트 암호화 불가라 B2 업로드 생략(sudo apt-get install -y age)" >&2
  else
    remotes="$(rclone listremotes || true)"
    if [[ $'\n'"$remotes" == *$'\n'"b2:"* ]]; then b2_ready=true; fi
  fi
fi

# crawler 덤프는 GB급이라 "로컬에 쓰고 다시 읽어 올리는" 2단계가 시간·디스크 I/O를 배로
# 먹는다 — B2가 살아 있으면 덤프·압축·로컬 기록(tee)·업로드를 한 파이프라인으로 스트리밍
# 한다(08-25, 상단 주석 ②③). 선-회전은 종전과 동일: 과거 실패일 폴백 사본과 신규 덤프의
# 동시 존재 피크를 KEEP-1개 수준으로 유지하며, 비치명(사본 0개면 ls 실패해도 계속).
# 반쪽 파일 규약도 종전과 동일 — 임시 이름(.tmp, 롤링 글롭 crawler-[0-9]* 비매치)으로만
# 쓰고, 성공이 확인된 사본만 정식 이름을 갖는다.
rm -f "$BACKUP_DIR"/.crawler-*.sql.*.tmp
{ ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.* 2>/dev/null | tail -n +"$LOCAL_CRAWLER_KEEP" | xargs -r rm; } || true

crawler_offsite=false
if "$b2_ready"; then
  # tee 뒤에서 암호화(09-02) — 로컬 임시본(폴백용)은 평문 .zst, B2로 나가는 쪽만 .zst.age
  if docker compose exec -T postgres-raw pg_dump -U "$RAW_DB_USER" -d crawler \
      | nice -n 19 zstd -q \
      | tee "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp" \
      | nice -n 19 age -r "$AGE_RECIPIENT" \
      | nice -n 19 rclone rcat --bwlimit "$B2_BWLIMIT" "$B2/crawler/crawler-$STAMP.sql.zst.age"; then
    crawler_offsite=true
    rm -f "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp"   # B2 사본 확인 — 로컬 임시본 폐기
  else
    # 파이프 어느 단계가 죽었든 통째 실패로 처리한다. pg_dump가 중간에 죽어도 rcat은 잘린
    # 스트림을 정상 종료로 닫고 원격 파일을 만들 수 있다 — 반쪽 원격본이 정식 이름으로
    # 남으면 복원 사고가 되므로 즉시 제거를 시도한다(없으면 에러 한 줄, 무해).
    echo "경고: crawler 스트리밍 백업 실패 — 반쪽 원격본 정리 후 로컬 전용 덤프로 폴백" >&2
    rclone deletefile "$B2/crawler/crawler-$STAMP.sql.zst.age" || true
    rclone cleanup "$B2" || true   # 중단된 청크 업로드의 미완성 large-file 파트 회수
    rm -f "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp"
  fi
fi
if ! "$crawler_offsite"; then
  # 폴백: 종전 방식의 로컬 전용 덤프 — B2 장애 기간(07-27~08-13 전력)에도 시점 스냅샷을
  # 로컬에 남긴다. set -euo라 여기가 실패하면 즉사 → mv 미도달(반쪽 파일은 .tmp로만 존재).
  docker compose exec -T postgres-raw pg_dump -U "$RAW_DB_USER" -d crawler \
    | nice -n 19 zstd -q > "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp"
  mv "$BACKUP_DIR/.crawler-$STAMP.sql.zst.tmp" "$BACKUP_DIR/crawler-$STAMP.sql.zst"
fi

# monitoring은 같은 postgres 인스턴스의 별도 DB — 소유자 계정으로 덤프.
# 신규 추가분이라 통째로 비치명 처리한다 — 여기서 set -euo로 죽으면 아래 analysis 업로드와
# 로컬 보관 정리까지 통째로 유실된다(매일 15:00 UTC 크론, 기존 백업 경로 보호가 우선).
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

# 오프사이트: crawler는 위 스트리밍으로 이미 올라갔다(crawler_offsite) — 여기서는 analysis를
# 올리고, 둘 다 성공해야 offsite_ok=true(아래 crawler 로컬 보관 분기의 기준). crawler 스트리밍이
# 실패했어도 analysis 업로드는 시도한다(작고 독립적 — 덤프 계열별 오프사이트는 서로 비의존).
offsite_ok=false
if "$b2_ready"; then
  analysis_offsite=false
  # 로컬 평문 파일을 스트리밍 암호화해 올린다(09-02) — 로컬에 암호문 사본을 따로 만들지 않는다.
  # rcat 실패 시 반쪽 원격본이 정식 이름으로 남을 수 있어 crawler와 같은 즉시 제거를 시도.
  if age -r "$AGE_RECIPIENT" < "$BACKUP_DIR/analysis-$STAMP.sql.zst" \
      | rclone rcat --bwlimit "$B2_BWLIMIT" "$B2/analysis/analysis-$STAMP.sql.zst.age"; then
    analysis_offsite=true
  else
    rclone deletefile "$B2/analysis/analysis-$STAMP.sql.zst.age" || true
  fi
  if "$analysis_offsite" && "$crawler_offsite"; then
    offsite_ok=true
    # ⚠ 이 아래 뒷정리(기간 롤링·개수 트리밍·monitoring 업로드)는 **전부 비치명이어야 한다**.
    # 여기는 if 본문이라 set -e가 살아 있어서 한 줄이라도 실패하면 스크립트가 즉사하고,
    # 그러면 파일 끝의 **crawler 로컬 보관 정리가 건너뛰어져** 로컬 덤프가 하루 ~8.5GiB씩
    # 무한 누적된다(디스크 만재 → 07-30에 87%까지 찬 그 사고의 재발). 지켜야 할 본질인
    # analysis(위 조건절)·crawler(스트리밍) 오프사이트 사본은 이미 성공했으므로, 뒷정리 실패는
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
      if age -r "$AGE_RECIPIENT" < "$BACKUP_DIR/monitoring-$STAMP.sql.zst" \
          | rclone rcat --bwlimit "$B2_BWLIMIT" "$B2/monitoring/monitoring-$STAMP.sql.zst.age"; then
        rclone delete --min-age 7d "$B2/monitoring/" \
          || echo "경고: B2 monitoring 기간 롤링 실패 — 다음 실행에서 재시도" >&2
      else
        echo "경고: monitoring B2 업로드 실패 — analysis·crawler 오프사이트는 정상" >&2
        rclone deletefile "$B2/monitoring/monitoring-$STAMP.sql.zst.age" || true
      fi
    fi
    echo "B2 업로드 완료(age 암호화): analysis-$STAMP.sql.zst.age, crawler-$STAMP.sql.zst.age${MONITORING_DUMP:+, $MONITORING_DUMP.age}"
  fi
fi

# 롤링 글롭은 crawler-[0-9]* — 수동 스냅샷(crawler-pre-* 등)은 롤링에서 제외
if "$offsite_ok"; then
  # 스트리밍 성공일은 오늘자 로컬 사본이 애초에 없다 — 남아 있는 건 과거 실패일의 폴백뿐이라
  # B2 사본이 확인된 지금 전부 정리한다(평상시 로컬 crawler 0개 — 08-25 스트리밍 전환).
  rm -f "$BACKUP_DIR"/crawler-[0-9]*.sql.*
else
  echo "경고: B2 업로드 실패(캡 초과 등) 또는 리모트 미설정 — 서버 로컬 최대 ${LOCAL_CRAWLER_KEEP}개 유지로 버팀" >&2
  # 스트리밍만 성공하고 analysis가 실패한 날은 로컬 crawler가 0개일 수 있다 — ls 실패 비치명.
  { ls -1t "$BACKUP_DIR"/crawler-[0-9]*.sql.* 2>/dev/null | tail -n +"$((LOCAL_CRAWLER_KEEP + 1))" | xargs -r rm; } || true
fi
# monitoring은 건너뛰거나 실패하면 목록에서 빠진다 — 크론 로그만으로 성패 판별 가능
echo "백업 완료: analysis-$STAMP.sql.zst, crawler-$STAMP.sql.zst${MONITORING_DUMP:+, $MONITORING_DUMP}"
