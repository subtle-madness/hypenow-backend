#!/usr/bin/env bash
# backup.sh 회귀 테스트 — 격리 샌드박스 HOME + docker/rclone 스텁으로 운영 DB·B2를 전혀 건드리지 않는다.
#
# 사용법: bash deploy/scripts/backup.test.sh deploy/scripts/backup.sh
# ⚠ GNU coreutils 전용(`head -n -N`, `xargs -r`) — macOS에서는 돌지 않는다. 리눅스나 서버의
#   격리 샌드박스에서 실행할 것: scp 두 파일을 /tmp에 올린 뒤 `bash /tmp/backup.test.sh /tmp/backup.sh`.
#   zstd 필요(08-04 gzip→zstd 전환) — 시드는 일부러 구세대 .sql.gz로 깔아 전환기 혼재 롤링을 함께 검증한다.
#
# 검증 목표 두 가지:
#  (1) 오프사이트 **뒷정리**(기간 롤링·개수 트리밍)가 실패해도 **crawler 로컬 보관 정리에
#      반드시 도달**해야 한다. backup.sh의 그 구간은 `if` 본문이라 set -e가 살아 있어서,
#      비치명 처리를 빼먹으면 스크립트가 즉사하고 로컬 보관 정리가 건너뛰어져 덤프가 하루
#      ~8.5GiB씩 무한 누적된다(07-30 디스크 87% 사고의 재발 경로). 케이스 ⑥⑦이 그 회귀를
#      직접 잡는다 — 비치명 처리를 되돌리면 즉시 FAIL한다(실측 확인).
#  (2) 세 계열 모두 **직스트리밍**이고(rclone copy 2패스로 복귀하면 FAIL), 파이프 어느 단계가
#      죽든 **잘린 원격본을 지우며**, 성공한 계열은 **로컬 사본을 0개로 떨어뜨리고**(과거
#      실패일 폴백까지 정리) 실패한 계열만 폴백을 굴린다. 수동 스냅샷(`*-pre-*`)은 셋 다 불가침.
#
# (08-27: 08-25 직스트리밍 전환에 맞춰 정비 — ① 기대값을 "로컬 1개"→"0개"로(성공 시 로컬
#  crawler 사본 0개가 새 의미론), 스트리밍 실패 폴백(②)과 analysis만 실패(③)를 분리.
#  rclone 스텁의 rcat은 stdin을 끝까지 읽는다 — 안 읽으면 실제 rclone과 달리 파이프
#  업스트림(tee)이 SIGPIPE로 죽는 레이스가 생겨 성공 케이스가 간헐 실패한다(실측).
#  ⑧은 CPU 상한 자기 래핑(systemd-run 재실행) 검증 — 08-26 밤 cpu-high 알람 대응.)
# (08-30: analysis·monitoring 직스트리밍 전환에 맞춰 확장 — 실패 모드를 계열별 rcat으로 쪼개고
#  (종전 `upload:copy`/`monitoring:copy`는 rclone copy 자체가 사라져 무의미해졌다),
#  analysis·monitoring 시드 5개씩을 깔아 **로컬 3일 롤링**을 계열마다 검증한다. rclone 호출을
#  전량 로그로 남겨 ⓐ `copy` 미사용(2패스 회귀 방지) ⓑ 실패 시 계열별 `deletefile`(잘린 원격본
#  정리) ⓒ 정상일엔 불필요한 deletefile 없음을 함께 본다. 케이스 ⑤는 pg_dump가 파이프 중간에
#  죽는 경로 — 비치명(monitoring)·치명(analysis) 양쪽에서 잘린 원격본이 지워지는지 본다.
#  이어서 로컬 보관도 crawler와 동형으로 통일 — 성공 계열은 tee 임시본과 **과거 실패일 폴백까지**
#  지워 로컬 0개가 된다(monitoring 3개 5.03GB + analysis 3개 0.45GB = 5.5GB 상주 해소, 08-30 실측).
#  시드 5개가 성공 케이스에서 0개로 떨어지는지, 실패 케이스에서만 LOCAL_FALLBACK_KEEP=3으로
#  굴러가는지, 그리고 세 계열 수동 스냅샷(`*-pre-KEEPME`)이 전부 살아남는지를 본다.)
set -uo pipefail
SCRIPT="$1"

pass=0; fail=0
# 인자: 이름 / 실패모드 / crawler 로컬 기대개수 / analysis "총개수:신규zst" / monitoring 동형
#      / 기대 종료코드 / deletefile 기대("-"=없어야 함, 아니면 공백구분 계열명 목록)
run_case() {
  local name="$1" failmode="$2" expect_crawler="$3" expect_analysis="$4" \
        expect_monitoring="$5" expect_exit="$6" expect_del="$7"
  local SB; SB="$(mktemp -d)"
  mkdir -p "$SB/backups" "$SB/deploy" "$SB/bin"
  cat > "$SB/deploy/.env" <<'EOF'
DB_USER=u
RAW_DB_USER=u
MONITORING_DB_USER=u
EOF
  # docker 스텁 — pg_dump 대신 더미 텍스트를 stdout으로. dumpfail-* 모드에서는 해당 DB만
  # 실패시켜 "파이프 중간(pg_dump)이 죽는" 경로를 만든다(마지막 인자가 `-d <db>`의 db —
  # `${*##* }`는 인자별로 적용돼 통짜 문자열이 그대로 남는다. 반드시 `${*: -1}`).
  cat > "$SB/bin/docker" <<'EOF'
#!/usr/bin/env bash
db="${*: -1}"
case "$FAILMODE:$db" in
  dumpfail-analysis:analysis|dumpfail-monitoring:monitoring) exit 1 ;;
esac
echo "-- dummy dump"
EOF
  # rclone 스텁 — 호출을 전량 로그로 남기고, FAILMODE에 따라 특정 하위명령만 실패시킨다.
  # ⚠ rcat은 stdin을 반드시 소진할 것(cat >/dev/null) — 실제 rclone은 스트림을 끝까지
  # 읽지만, 스텁이 바로 exit하면 tee가 SIGPIPE로 죽어 성공 케이스가 간헐 FAIL한다.
  cat > "$SB/bin/rclone" <<'EOF'
#!/usr/bin/env bash
sub="$1"
printf '%s\n' "$*" >> "$RCLONE_LOG"
case "$FAILMODE:$sub" in
  stream:rcat)      cat >/dev/null; exit 1 ;;                              # B2 쓰기 전면 불능
  crawler:rcat)     [[ "$*" == *crawler* ]]    && { cat >/dev/null; exit 1; } ;;
  analysis:rcat)    [[ "$*" == *analysis* ]]   && { cat >/dev/null; exit 1; } ;;
  monitoring:rcat)  [[ "$*" == *monitoring* ]] && { cat >/dev/null; exit 1; } ;;
  trim:lsf)         exit 1 ;;                       # crawler 개수 트리밍 실패
  rolling:delete)   exit 1 ;;                       # 기간 롤링 실패
esac
case "$sub" in
  rcat)        cat >/dev/null ;;
  listremotes) echo "b2:" ;;
  lsf)         echo "crawler-20260701-000000.sql.gz"; echo "crawler-20260702-000000.sql.gz" ;;
esac
exit 0
EOF
  chmod +x "$SB/bin/docker" "$SB/bin/rclone"

  # 기존 덤프를 미리 깔아 보관 정리가 실제로 도는지 본다
  # (crawler 6개: 선-회전 + 오프사이트 분기 / analysis·monitoring 5개: 3일 롤링)
  for d in 20 21 22 23 24 25; do
    echo x | gzip > "$SB/backups/crawler-202607${d}-000000.sql.gz"; sleep 0.01
  done
  for d in 21 22 23 24 25; do
    echo x | gzip > "$SB/backups/analysis-202607${d}-000000.sql.gz"
    echo x | gzip > "$SB/backups/monitoring-202607${d}-000000.sql.gz"; sleep 0.01
  done
  # 수동 스냅샷 — 세 계열 전부 절대 지워지면 안 된다(로컬 정리 글롭이 <계열>-[0-9]*인 이유)
  for series in crawler analysis monitoring; do
    echo x | gzip > "$SB/backups/$series-pre-KEEPME.sql.gz"
  done

  local out rc
  # BACKUP_CPU_CAPPED=1: CPU 상한 자기 래핑(systemd-run 재실행)을 건너뛰고 본문만 검증 —
  # 래핑 자체는 케이스 ⑧이 sudo 스텁으로 따로 검증한다
  out="$(HOME="$SB" FAILMODE="$failmode" BACKUP_CPU_CAPPED=1 RCLONE_LOG="$SB/rclone.log" \
        PATH="$SB/bin:$PATH" bash "$SCRIPT" 2>&1)"; rc=$?

  local verdict=OK
  count() { find "$SB/backups" -name "$1" | wc -l | tr -d ' '; }
  local n_crawler n_pre
  n_crawler="$(count 'crawler-[0-9]*.sql.*')"
  n_pre="$(count '*-pre-*')"
  [ "$n_crawler" = "$expect_crawler" ] || verdict="FAIL(crawler 로컬 $n_crawler != 기대 $expect_crawler)"
  [ "$n_pre" = "3" ]                   || verdict="$verdict FAIL(수동 스냅샷 유실! $n_pre/3)"
  [ "$rc" = "$expect_exit" ]           || verdict="$verdict FAIL(종료코드 $rc != 기대 $expect_exit)"

  # analysis·monitoring: 총 보관 개수(로컬 3일 롤링) + 오늘자 신규 덤프(.sql.zst) 개수
  local series exp tot zst n_tot n_zst
  for series in analysis monitoring; do
    [ "$series" = analysis ] && exp="$expect_analysis" || exp="$expect_monitoring"
    tot="${exp%%:*}"; zst="${exp##*:}"
    n_tot="$(count "$series-[0-9]*.sql.*")"; n_zst="$(count "$series-[0-9]*.sql.zst")"
    [ "$n_tot" = "$tot" ] || verdict="$verdict FAIL($series 로컬 $n_tot != 기대 $tot)"
    [ "$n_zst" = "$zst" ] || verdict="$verdict FAIL($series 신규덤프 $n_zst != 기대 $zst)"
    # 반쪽 파일이 정식 이름으로 승격되면 안 된다 — 잔재는 .tmp(롤링 글롭 비매치)로만 존재
    [ "$(count ".$series-*.tmp")" -le 1 ] || verdict="$verdict FAIL($series .tmp 누적)"
  done

  # ⓐ 2패스 회귀 방지: 업로드는 전부 rcat이어야 한다(rclone copy 재등장 시 FAIL)
  grep -qE '^copy ' "$SB/rclone.log" && verdict="$verdict FAIL(rclone copy 재등장 — 2패스 회귀)"
  grep -qE 'rcat .*analysis/analysis-' "$SB/rclone.log" \
    || verdict="$verdict FAIL(analysis 직스트리밍 미사용)"
  # monitoring은 analysis·crawler 뒤라 치명 종료(⑨) 케이스에선 도달 자체를 안 한다
  [ "$expect_exit" != 0 ] || grep -qE 'rcat .*monitoring/monitoring-' "$SB/rclone.log" \
    || verdict="$verdict FAIL(monitoring 직스트리밍 미사용)"
  # ⓑⓒ 잘린 원격본 정리 — 실패한 계열만 deletefile, 정상일엔 없어야 한다
  if [ "$expect_del" = "-" ]; then
    ! grep -qE 'deletefile .*/(analysis|monitoring)/' "$SB/rclone.log" \
      || verdict="$verdict FAIL(정상일에 불필요한 원격본 삭제)"
  else
    local p
    for p in $expect_del; do
      grep -qE "deletefile .*/$p/$p-[0-9]" "$SB/rclone.log" \
        || verdict="$verdict FAIL($p 반쪽 원격본 미정리)"
    done
  fi

  if [ "$verdict" = OK ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
  printf '%-46s → %s\n' "$name" "$verdict"
  [ "$verdict" = OK ] || { echo "---- 출력 ----"; echo "$out"; echo "---- rclone ----";
                           cat "$SB/rclone.log"; echo "--------------"; }
  rm -rf "$SB"
}

echo "=== backup.sh 회귀 테스트 ==="
#         이름                                        실패모드          crawler analysis monitoring 종료 deletefile
# 전부 성공: 세 계열 스트리밍 성공 → 로컬 사본 0개(시드 5개도 과거 폴백으로 보고 정리된다)
run_case "① 정상 — 3계열 로컬 0개"                    none               0 0:0 0:0 0 "-"
# crawler rcat만 실패: 로컬 폴백 덤프 생성 → 선-회전 구본 1 + 신규 1 = 2개로 버팀
run_case "② crawler 업로드 실패 — 로컬 폴백 2개"      crawler            2 0:0 0:0 0 "-"
# analysis rcat만 실패: analysis만 폴백 3개 롤링, monitoring은 성공이라 0개. offsite_ok=false
run_case "③ analysis 업로드 실패 — 폴백 3개 롤링"     analysis           1 3:1 0:0 0 "analysis"
# monitoring rcat만 실패: 비치명 — 로컬 폴백만 남기고 offsite_ok는 그대로 true
run_case "④ monitoring 업로드 실패 — 비치명"          monitoring         0 0:0 3:1 0 "monitoring"
# 파이프 중간(pg_dump) 실패 — 비치명 계열: 잘린 원격본 정리 + 오늘자 사본 없음(롤링 미실행)
run_case "⑤ monitoring 덤프 실패 — 원격본 정리"       dumpfail-monitoring 0 0:0 5:0 0 "monitoring"
# ↓ 회귀 방지 핵심: 뒷정리 실패에도 로컬 보관 정리(전량 삭제)에 도달해야 한다
run_case "⑥ 개수 트리밍 실패 — 로컬 정리 도달"        trim               0 0:0 0:0 0 "-"
run_case "⑦ 기간 롤링 실패 — 로컬 정리 도달"          rolling            0 0:0 0:0 0 "-"
# B2 쓰기 전면 불능(캡 초과 등): 세 계열 모두 로컬 폴백 — 장애 기간 안전망이 살아 있어야 한다
run_case "⑧ B2 쓰기 전면 실패 — 3계열 로컬 폴백"      stream             2 3:1 3:1 0 "analysis monitoring"
# analysis 덤프 실패는 치명(종전과 동일) — 죽기 전에 잘린 원격본은 지우고, 반쪽 로컬본을
# 정식 이름으로 남기지 않는다(.tmp로만). 뒤 구간 미도달이라 crawler 시드 6개가 그대로 남는다.
run_case "⑨ analysis 덤프 실패 — 치명·원격본 정리"    dumpfail-analysis   6 5:0 5:0 1 "analysis"

# ⑩ CPU 상한 자기 래핑 — BACKUP_CPU_CAPPED 미설정이면 sudo systemd-run(CPUQuota) 유닛으로
# 재실행해야 한다(08-26 밤 hypenow-api-cpu-high: 스트리밍 전환으로 백업 CPU가 85~89% 지속.
# 실험 실측으로 CPUQuota=35%가 안전선). 파일 첫머리 exec이라 이 래핑이 세 계열 파이프라인을
# 통째로 감싼다. sudo 스텁이 인자를 기록한 뒤 페이로드를 BACKUP_CPU_CAPPED=1로 직접 실행 —
# 래핑→본문 완주까지 한 번에 검증한다.
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
out10="$(HOME="$SB" FAILMODE=none SUDO_LOG="$SB/sudo.log" PATH="$SB/bin:$PATH" bash "$SCRIPT" 2>&1)"; rc10=$?
verdict=OK
grep -q -- 'CPUQuota=35%' "$SB/sudo.log" 2>/dev/null || verdict="FAIL(systemd-run CPUQuota=35% 재실행 없음)"
[ "$rc10" = 0 ] || verdict="$verdict FAIL(종료코드 $rc10 != 기대 0)"
# 성공일엔 로컬 사본이 없는 게 정상 — 완주 여부는 마지막 출력 줄로 본다
# (sudo 스텁은 systemd-run의 StandardOutput=append를 흉내내지 않아 backup.log가 아니라 stdout으로 온다)
printf '%s\n' "$out10" | grep -q '^백업 완료: analysis-.*crawler-.*monitoring-' \
  || verdict="$verdict FAIL(래핑 후 본문 미완주)"
if [ "$verdict" = OK ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
printf '%-46s → %s\n' "⑩ CPU 상한 래핑 — systemd-run 재실행" "$verdict"
[ "$verdict" = OK ] || { echo "---- 출력 ----"; echo "$out10"; cat "$SB/sudo.log" 2>/dev/null; echo "--------------"; }
rm -rf "$SB"

echo "=== 통과 $pass / 실패 $fail ==="
[ "$fail" = 0 ]
