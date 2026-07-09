#!/usr/bin/env bash
# analysis DB에 적재된 분석 결과를 사람이 보기 좋게 출력한다.
# 먼저 materialization 잡을 돌려야 채워짐: ./gradlew :analytics:bootRun
set -euo pipefail
PSQL=(docker exec -i crawler-postgres-1 psql -U crawler -d analysis -P pager=off)

show() { echo; echo "━━━ $1 ━━━"; shift; "${PSQL[@]}" -c "$1"; }

echo "════════ hypenow 분석 결과 (analysis DB) ════════"
"${PSQL[@]}" -c "select table_name, row_count, materialized_at from materialization_meta order by table_name;"

show "콘텐츠 참여율 랭킹 (상위 20)" \
  "select rank_overall, short_code, owner_username, main_group, engagement_rate, likes, views from content_ranking order by rank_overall nulls last limit 20;"

show "분류별 성과 (main_group 레벨)" \
  "select main_group, content_count, avg_engagement_rate, median_engagement_rate, avg_likes from category_performance where subcategory='(all)' and keyword='(all)' and main_group is not null order by avg_engagement_rate desc;"

show "크리에이터별 성과" \
  "select owner_username, content_count, avg_engagement_rate, followers from creator_performance order by avg_engagement_rate desc;"

show "협업 후보 (구간 대비 오버퍼폼)" \
  "select owner_username, tier, avg_engagement_rate, tier_median_er from creator_overperformance where overperforms order by avg_engagement_rate desc;"

show "팔로워 구간 분포" \
  "select tier, creator_count, content_count, avg_engagement_rate from tier_distribution order by 1;"

show "릴스 vs 피드" \
  "select content_format, content_count, avg_engagement_rate, avg_likes, avg_views from content_type_performance order by avg_engagement_rate desc;"

show "해시태그 성과 (상위 10)" \
  "select tag, content_count, avg_engagement_rate from hashtag_performance order by content_count desc, avg_engagement_rate desc limit 10;"
