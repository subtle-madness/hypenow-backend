#!/usr/bin/env bash
# backup.sh 회귀 테스트 — 격리 샌드박스 HOME + docker/rclone 스텁으로 운영 DB·B2를 전혀 건드리지 않는다.
#
# 사용법: bash deploy/scripts/backup.test.sh deploy/scripts/backup.sh
# ⚠ GNU coreutils 전용(`head -n -N`, `xargs -r`) — macOS에서는 돌지 않는다. 리눅스나 서버의
#   격리 샌드박스에서 실행할 것: scp 두 파일을 /tmp에 올린 뒤 `bash /tmp/backup.test.sh /tmp/backup.sh`.
#   zstd 필요(08-04 gzip→zstd 전환) — 시드는 일부러 구세대 .sql.gz로 깔아 전환기 혼재 롤링을 함께 검증한다.
#
# 검증 목표: 오프사이트 **뒷정리**(기간 롤링·개수 트리밍·monitoring 업로드)가 실패해도
# **crawler 로컬 보관 정리에 반드시 도달**해야 한다. backup.sh의 그 구간은 `if` 본문이라
# set -e가 살아 있어서, 비치명 처리를 빼먹으면 스크립트가 즉사하고 로컬 보관 정리가
# 건너뛰어져 덤프가 하루 ~8.5GiB씩 무한 누적된다(07-30 디스크 87% 사고의 재발 경로).
# 케이스 ③④⑤가 그 회귀를 직접 잡는다 — 비치명 처리를 되돌리면 즉시 FAIL한다(실측 확인).
set -uo pipefail
SCRIPT="$1"

pass=0; fail=0
run_case() {
  local name="$1" failmode="$2" expect_local="$3" expect_exit="$4"
  local SB; SB="$(mktemp -d)"
  mkdir -p "$SB/backups" "$SB/deploy" "$SB/bin"
  cat > "$SB/deploy/.env" <<'EOF'
DB_USER=u
RAW_DB_USER=u
MONITORING_DB_USER=u
EOF
  # docker 스텁 — pg_dump 대신 더미 텍스트를 stdout으로
  cat > "$SB/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "-- dummy dump"
EOF
  # rclone 스텁 — FAILMODE에 따라 특정 하위명령만 실패시킨다
  cat > "$SB/bin/rclone" <<'EOF'
#!/usr/bin/env bash
sub="$1"
case "$FAILMODE:$sub" in
  upload:copy)      exit 1 ;;                       # analysis·crawler 업로드 실패(캡 초과)
  monitoring:copy)  [[ "$*" == *monitoring* ]] && exit 1 ;;   # monitoring만 실패
  trim:lsf)         exit 1 ;;                       # crawler 개수 트리밍 실패
  rolling:delete)   exit 1 ;;                       # 기간 롤링 실패
esac
case "$sub" in
  listremotes) echo "b2:" ;;
  lsf)         echo "crawler-20260701-000000.sql.gz"; echo "crawler-20260702-000000.sql.gz" ;;
esac
exit 0
EOF
  chmod +x "$SB/bin/docker" "$SB/bin/rclone"

  # 기존 crawler 덤프 6개를 미리 깔아 보관 정리가 실제로 도는지 본다
  for d in 20 21 22 23 24 25; do
    echo x | gzip > "$SB/backups/crawler-202607${d}-000000.sql.gz"; sleep 0.01
  done
  echo x | gzip > "$SB/backups/crawler-pre-KEEPME.sql.gz"   # 수동 스냅샷 — 절대 지워지면 안 됨

  local out rc
  out="$(HOME="$SB" FAILMODE="$failmode" PATH="$SB/bin:$PATH" bash "$SCRIPT" 2>&1)"; rc=$?
  local n_local n_pre
  n_local="$(find "$SB/backups" -name 'crawler-[0-9]*.sql.*' | wc -l | tr -d ' ')"
  n_pre="$(find "$SB/backups" -name 'crawler-pre-*' | wc -l | tr -d ' ')"

  local verdict=OK
  [ "$n_local" = "$expect_local" ] || verdict="FAIL(로컬 $n_local != 기대 $expect_local)"
  [ "$rc" = "$expect_exit" ]       || verdict="$verdict FAIL(종료코드 $rc != 기대 $expect_exit)"
  [ "$n_pre" = "1" ]               || verdict="$verdict FAIL(수동 스냅샷 유실!)"
  if [ "$verdict" = OK ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
  printf '%-46s → %s\n' "$name" "$verdict"
  [ "$verdict" = OK ] || { echo "---- 출력 ----"; echo "$out"; echo "--------------"; }
  rm -rf "$SB"
}

echo "=== backup.sh 회귀 테스트 ==="
# 전부 성공: 오프사이트 OK → 로컬 crawler 1개만 남김
run_case "① 정상 — 로컬 1개로 정리"                  none       1 0
# 업로드 실패(캡 초과): offsite_ok=false → 로컬 $LOCAL_CRAWLER_KEEP(=2)개로 버팀
# (08-04 로컬 보존 3→2 반영 — 선-회전이 구 1개 + 신규 1개로 유지한다)
run_case "② 업로드 실패(캡) — 로컬 2개로 버팀"        upload     2 0
# ↓ 회귀 방지 핵심 3건: 뒷정리 실패에도 로컬 보관 정리에 도달해야 한다
run_case "③ monitoring만 실패 — 로컬 정리 도달"       monitoring 1 0
run_case "④ 개수 트리밍 실패 — 로컬 정리 도달"        trim       1 0
run_case "⑤ 기간 롤링 실패 — 로컬 정리 도달"          rolling    1 0
echo "=== 통과 $pass / 실패 $fail ==="
[ "$fail" = 0 ]
