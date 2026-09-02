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
# 케이스 ④⑤⑥이 그 회귀를 직접 잡는다 — 비치명 처리를 되돌리면 즉시 FAIL한다(실측 확인).
#
# (08-27: 08-25 직스트리밍 전환에 맞춰 정비 — ① 기대값을 "로컬 1개"→"0개"로(성공 시 로컬
#  crawler 사본 0개가 새 의미론), 스트리밍 실패 폴백(②)과 analysis만 실패(③)를 분리.
#  rclone 스텁의 rcat은 stdin을 끝까지 읽는다 — 안 읽으면 실제 rclone과 달리 파이프
#  업스트림(tee)이 SIGPIPE로 죽는 레이스가 생겨 성공 케이스가 간헐 실패한다(실측).
#  ⑦은 CPU 상한 자기 래핑(systemd-run 재실행) 검증 — 08-26 밤 cpu-high 알람 대응.)
# (09-02: 오프사이트 age 암호화 도입에 맞춰 정비 — analysis·monitoring 업로드가 copy→rcat으로
#  바뀌어 실패 주입 지점을 rcat+대상명 매치로 재편. rcat 스텁이 대상 경로를 기록해 성공
#  케이스에서 세 계열 모두 `.sql.zst.age`로 올라가는지 검증한다. age 실물 필요 — zstd처럼
#  하니스 전제 조건.)
set -uo pipefail
SCRIPT="$1"
command -v age >/dev/null 2>&1 || { echo "age 필요(오프사이트 암호화) — sudo apt-get install -y age"; exit 1; }

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
  # rclone 스텁 — FAILMODE에 따라 특정 하위명령만 실패시킨다.
  # ⚠ rcat은 stdin을 반드시 소진할 것(cat >/dev/null) — 실제 rclone은 스트림을 끝까지
  # 읽지만, 스텁이 바로 exit하면 tee가 SIGPIPE로 죽어 성공 케이스가 간헐 FAIL한다.
  cat > "$SB/bin/rclone" <<'EOF'
#!/usr/bin/env bash
sub="$1"
# rcat은 분기 무관 stdin을 먼저 소진(실제 rclone 동작) — 안 읽으면 tee가 SIGPIPE로 죽는 레이스.
# 대상 경로를 기록해 하니스가 암호화 업로드명(.sql.zst.age)을 검증한다(09-02).
if [ "$sub" = rcat ]; then cat >/dev/null; printf '%s\n' "$*" >> "${RCLONE_LOG:-/dev/null}"; fi
case "$FAILMODE:$sub" in
  stream:rcat)      [[ "$*" == *crawler* ]] && exit 1 ;;      # crawler 직스트리밍 업로드 실패
  upload:rcat)      [[ "$*" == *analysis* ]] && exit 1 ;;     # analysis 업로드 실패(캡 초과)
  monitoring:rcat)  [[ "$*" == *monitoring* ]] && exit 1 ;;   # monitoring만 실패
  trim:lsf)         exit 1 ;;                                 # crawler 개수 트리밍 실패
  rolling:delete)   exit 1 ;;                                 # 기간 롤링 실패
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
  # BACKUP_CPU_CAPPED=1: CPU 상한 자기 래핑(systemd-run 재실행)을 건너뛰고 본문만 검증 —
  # 래핑 자체는 케이스 ⑦이 sudo 스텁으로 따로 검증한다
  out="$(HOME="$SB" FAILMODE="$failmode" RCLONE_LOG="$SB/rclone.log" BACKUP_CPU_CAPPED=1 PATH="$SB/bin:$PATH" bash "$SCRIPT" 2>&1)"; rc=$?
  local n_local n_pre
  n_local="$(find "$SB/backups" -name 'crawler-[0-9]*.sql.*' | wc -l | tr -d ' ')"
  n_pre="$(find "$SB/backups" -name 'crawler-pre-*' | wc -l | tr -d ' ')"

  local verdict=OK
  [ "$n_local" = "$expect_local" ] || verdict="FAIL(로컬 $n_local != 기대 $expect_local)"
  [ "$rc" = "$expect_exit" ]       || verdict="$verdict FAIL(종료코드 $rc != 기대 $expect_exit)"
  [ "$n_pre" = "1" ]               || verdict="$verdict FAIL(수동 스냅샷 유실!)"
  if [ "$failmode" = none ]; then
    # 전부 성공 케이스: 세 계열(crawler·analysis·monitoring) 모두 암호화 이름으로 올라가야 한다(09-02)
    local n_age; n_age="$(grep -c '\.sql\.zst\.age' "$SB/rclone.log" 2>/dev/null || true)"
    [ "${n_age:-0}" = "3" ] || verdict="$verdict FAIL(암호화 업로드 ${n_age:-0} != 3)"
  fi
  if [ "$verdict" = OK ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
  printf '%-46s → %s\n' "$name" "$verdict"
  [ "$verdict" = OK ] || { echo "---- 출력 ----"; echo "$out"; echo "--------------"; }
  rm -rf "$SB"
}

echo "=== backup.sh 회귀 테스트 ==="
# 전부 성공: 스트리밍 성공 + 오프사이트 OK → 로컬 crawler 0개(08-25 새 의미론)
run_case "① 정상 — 스트리밍 성공, 로컬 0개"          none       0 0
# 스트리밍(rcat) 실패: 로컬 폴백 덤프 생성 → 선-회전 구본 1 + 신규 1 = 2개로 버팀
run_case "② 스트리밍 실패 — 로컬 폴백 2개로 버팀"     stream     2 0
# analysis 업로드만 실패: 스트리밍은 성공(로컬 신규 없음) → 선-회전 구본 1개만 잔존
run_case "③ analysis 업로드 실패 — 로컬 구본 1개"     upload     1 0
# ↓ 회귀 방지 핵심 3건: 뒷정리 실패에도 로컬 보관 정리(전량 삭제)에 도달해야 한다
run_case "④ monitoring만 실패 — 로컬 정리 도달"       monitoring 0 0
run_case "⑤ 개수 트리밍 실패 — 로컬 정리 도달"        trim       0 0
run_case "⑥ 기간 롤링 실패 — 로컬 정리 도달"          rolling    0 0

# ⑦ CPU 상한 자기 래핑 — BACKUP_CPU_CAPPED 미설정이면 sudo systemd-run(CPUQuota) 유닛으로
# 재실행해야 한다(08-26 밤 hypenow-api-cpu-high: 스트리밍 전환으로 백업 CPU가 85~89% 지속.
# 실험 실측으로 CPUQuota=35%가 안전선). sudo 스텁이 인자를 기록한 뒤 페이로드를
# BACKUP_CPU_CAPPED=1로 직접 실행 — 래핑→본문 완주까지 한 번에 검증한다.
SB="$(mktemp -d)"
mkdir -p "$SB/backups" "$SB/deploy" "$SB/bin"
printf 'DB_USER=u\nRAW_DB_USER=u\nMONITORING_DB_USER=u\n' > "$SB/deploy/.env"
printf '#!/usr/bin/env bash\necho "-- dummy dump"\n' > "$SB/bin/docker"
cat > "$SB/bin/rclone" <<'EOF'
#!/usr/bin/env bash
case "$1" in rcat) cat >/dev/null ;; listremotes) echo "b2:" ;; lsf) : ;; esac
exit 0
EOF
cat > "$SB/bin/sudo" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SUDO_LOG"
[[ "$*" == *systemd-run* ]] || exit 0   # `sudo -n true` 가드 호출은 성공만 흉내
while [ "$#" -gt 0 ] && [ "$1" != "/bin/bash" ]; do shift; done
BACKUP_CPU_CAPPED=1 exec "$@"
EOF
chmod +x "$SB/bin/docker" "$SB/bin/rclone" "$SB/bin/sudo"
out6="$(HOME="$SB" FAILMODE=none SUDO_LOG="$SB/sudo.log" PATH="$SB/bin:$PATH" bash "$SCRIPT" 2>&1)"; rc6=$?
verdict=OK
grep -q -- 'CPUQuota=35%' "$SB/sudo.log" 2>/dev/null || verdict="FAIL(systemd-run CPUQuota=35% 재실행 없음)"
[ "$rc6" = 0 ] || verdict="$verdict FAIL(종료코드 $rc6 != 기대 0)"
ls "$SB/backups"/analysis-*.sql.zst >/dev/null 2>&1 || verdict="$verdict FAIL(래핑 후 본문 미완주)"
if [ "$verdict" = OK ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
printf '%-46s → %s\n' "⑦ CPU 상한 래핑 — systemd-run 재실행" "$verdict"
[ "$verdict" = OK ] || { echo "---- 출력 ----"; echo "$out6"; cat "$SB/sudo.log" 2>/dev/null; echo "--------------"; }
rm -rf "$SB"

echo "=== 통과 $pass / 실패 $fail ==="
[ "$fail" = 0 ]
