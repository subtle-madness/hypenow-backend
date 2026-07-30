#!/usr/bin/env bash
# hype_score 앵커 재적합 후보값을 산출한다(읽기 전용).
# 사용법: ./check/hype-anchor-refit.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
# 대상은 crawler DB(분석 뷰가 사는 곳) — coverage.sh(analysis DB)와 대상이 다르다.
#
# 콘텐츠 출력 앵커 섹션(2026-07-30, hype-anchor-refit.sql 참조)만 크로스 DB다 — 랭킹 경로 모수
# (is_beauty ∧ metric_timeliness)는 content_analyses(analysis DB)에만 있다. pending.sh와 같은
# 방식으로 analysis DB에서 CSV로 뽑아 crawler 세션의 임시 테이블 _ranking_hype_scores로 주입한
# 뒤 hype-anchor-refit.sql을 돌린다.
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
PG="${PG_CONTAINER:-crawler-postgres-1}"
AQ=(docker exec "$PG" psql -U crawler -d analysis -Atc)

{
  echo "CREATE TEMP TABLE _ranking_hype_scores(hype_score bigint);"
  echo "COPY _ranking_hype_scores FROM STDIN WITH (FORMAT csv);"
  "${AQ[@]}" "COPY (SELECT c.hype_score FROM contents c
                    JOIN content_analyses an ON an.short_code = c.short_code
                    WHERE an.is_beauty = true
                      AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)) TO STDOUT WITH (FORMAT csv)"
  printf '%s\n' '\.'
  cat hype-anchor-refit.sql
} | docker exec -i "$PG" psql -U crawler -d crawler -v ON_ERROR_STOP=1
echo "REFIT OK (산출값 반영 절차는 hype-anchor-refit.sql 헤더 주석 참조)"
