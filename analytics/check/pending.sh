#!/usr/bin/env bash
# 미분석 콘텐츠 상태 분해 리포트 — "제때창(3일) 놓쳐서 분석 안 함"과 "분석 대상인데 대기"를 가른다.
# 사용법: ./check/pending.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
#
# 크로스 DB 구도: 후보·자격 판정은 crawler DB(분석 뷰), 기분석·게이트 셋은 analysis DB라
# SQL 조인이 불가 — analysis 셋을 CSV로 뽑아 crawler 세션의 임시 테이블로 주입한 뒤
# pending.sql을 돌린다(PipelineStatsService의 Java 셋 대조와 같은 구도를 psql로 재현).
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
PG="${PG_CONTAINER:-crawler-postgres-1}"
AQ=(docker exec "$PG" psql -U crawler -d analysis -Atc)

{
  echo "CREATE TEMP TABLE _analyzed(short_code text, metric_timeliness text);"
  echo "COPY _analyzed FROM STDIN WITH (FORMAT csv);"
  "${AQ[@]}" "COPY (SELECT short_code, metric_timeliness FROM content_analyses) TO STDOUT WITH (FORMAT csv)"
  printf '%s\n' '\.'

  # 댓글 게이트 — ContentAnalysisJob의 보류 조건과 동일식(댓글 미러됨 ∧ 분류 미완)
  echo "CREATE TEMP TABLE _comment_gate(short_code text);"
  echo "COPY _comment_gate FROM STDIN WITH (FORMAT csv);"
  "${AQ[@]}" "COPY (SELECT DISTINCT m.short_code FROM content_comments m
                    WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k
                                      WHERE k.short_code = m.short_code)) TO STDOUT WITH (FORMAT csv)"
  printf '%s\n' '\.'

  echo "CREATE TEMP TABLE _mirrored(short_code text);"
  echo "COPY _mirrored FROM STDIN WITH (FORMAT csv);"
  "${AQ[@]}" "COPY (SELECT short_code FROM contents) TO STDOUT WITH (FORMAT csv)"
  printf '%s\n' '\.'

  echo "CREATE INDEX ON _analyzed(short_code);"
  echo "CREATE INDEX ON _comment_gate(short_code);"
  echo "CREATE INDEX ON _mirrored(short_code);"
  cat pending.sql
} | docker exec -i "$PG" psql -U crawler -d crawler -v ON_ERROR_STOP=1
