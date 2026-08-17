#!/usr/bin/env bash
# hype_score 앵커 재적합 후보값을 산출한다(읽기 전용).
# 사용법: ./check/hype-anchor-refit.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
# 대상은 crawler DB(분석 뷰가 사는 곳) — coverage.sh(analysis DB)와 대상이 다르다.
#
# analysis DB에서는 랭킹 경로(is_beauty ∧ metric_timeliness — content_analyses에만 있는 필터) 소속
# short_code만 뽑아 주입하고, 점수는 crawler DB에서 신 가중·신 Q 앵커 기준으로 재계산한다
# (v2, 2026-08-17). pending.sh와 같은 방식으로 CSV로 뽑아 crawler 세션의 임시 테이블
# _ranking_codes로 주입한 뒤 hype-anchor-refit.sql을 돌린다.
# 추출은 먼저 임시 파일로 받는다(파이프에 바로 물리지 않음) — AQ가 실패하면 set -e가 이 지점에서
# 즉시 죽어 원인이 분명하고, 성공 시엔 건수를 stderr로 먼저 보여준다(파이프 안에 넣으면 AQ 실패가
# psql 쪽 set -e 범위 밖이라 원인 불명한 채로 죽거나 빈 주입으로 조용히 넘어갈 수 있었다).
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
PG="${PG_CONTAINER:-crawler-postgres-1}"
AQ=(docker exec "$PG" psql -U crawler -d analysis -Atc)

CODES_CSV="$(mktemp)"
trap 'rm -f "$CODES_CSV"' EXIT
"${AQ[@]}" "COPY (SELECT c.short_code FROM contents c
                  JOIN content_analyses an ON an.short_code = c.short_code
                  WHERE an.is_beauty = true
                    AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)) TO STDOUT WITH (FORMAT csv)" > "$CODES_CSV"
echo "랭킹 경로 short_code 추출: $(wc -l < "$CODES_CSV" | tr -d ' ')건" >&2

{
  echo "CREATE TEMP TABLE _ranking_codes(short_code text);"
  echo "COPY _ranking_codes FROM STDIN WITH (FORMAT csv);"
  cat "$CODES_CSV"
  printf '%s\n' '\.'
  cat hype-anchor-refit.sql
} | docker exec -i "$PG" psql -U crawler -d crawler -v ON_ERROR_STOP=1
echo "REFIT OK (산출값 반영 절차는 hype-anchor-refit.sql 헤더 주석 참조)"
