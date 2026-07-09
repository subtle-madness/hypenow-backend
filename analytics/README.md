# analytics — 크롤링 데이터 분석 카탈로그

crawler가 적재한 인스타 raw 데이터로 콘텐츠·크리에이터를 분석하는 읽기전용 SQL 뷰 모음.
운영 스키마(`public`)는 건드리지 않고 `analytics` 스키마에만 뷰를 만든다.

설계: [../docs/superpowers/specs/2026-07-09-analytics-catalog-design.md](../docs/superpowers/specs/2026-07-09-analytics-catalog-design.md)

## 뷰 적용

    for v in analytics/views/*.sql; do
      docker exec -i crawler-postgres-1 psql -U crawler -d crawler -q < "$v"
    done

## 지표 뷰 목록

| 뷰 | 그룹 | 내용 |
|---|---|---|
| `v_latest_profile` / `v_latest_detail` / `v_content_metrics` | base | 계정별 최신 프로필 / 콘텐츠별 최신 상세 / 콘텐츠 팩트 |
| `v_content_performance` | 1 | 콘텐츠별 참여율·조회수 대비 좋아요/댓글율 |
| `v_category_performance` / `v_content_ranking` | 2 | category>main_group>subcategory>keyword 롤업 집계(평균+중앙값 ER) / 참여율 랭킹 |
| `v_follower_tier` / `v_creator_performance` / `v_creator_overperformance` / `v_tier_distribution` | 3 | 팔로워 구간 / 계정별 성과 / 오버퍼폼(협업 후보) / 구간별 분포 |
| `v_content_type_performance` / `v_video_duration_performance` | 4 | 릴스 vs 피드 / 영상 길이 구간별 |
| `v_timing_performance` | 5 | KST 요일·시간대별 성과 |
| `v_hashtag_performance` / `v_mention_performance` / `v_content_comment_stats` | 6 | 해시태그 / 멘션 / 댓글 통계 |
| `v_ad_performance` / `v_ad_ratio` | 7 | 광고 vs 비광고 / 광고 비율 |

## 예시 쿼리

    -- 카테고리 안에서 참여율 상위 콘텐츠 10개
    SELECT short_code, owner_username, main_group, engagement_rate
    FROM analytics.v_content_performance
    ORDER BY engagement_rate DESC NULLS LAST
    LIMIT 10;

    -- 협업 후보(구간 대비 오버퍼폼)
    SELECT * FROM analytics.v_creator_overperformance WHERE overperforms;

## 테스트

    cd analytics && ./test/run.sh          # 전체
    ./test/run.sh test/01_content_performance.test.sql   # 지정

더미데이터(`seed/dummy.sql`)를 트랜잭션에 seed → 뷰 결과를 `ASSERT`로 검증 → `ROLLBACK`.
실데이터는 변경되지 않는다.

## 데이터 한계 (해석 주의)

- 저장·공유·도달·노출 지표 없음 (Apify 응답에 없음).
- 성과는 업로드 +3일 단일 스냅샷 (성장곡선 아님, 콘텐츠 간 비교는 공정).
- 팔로워는 qualify 시점 값.
- 텍스트/감성 분석, BI 대시보드, 파이프라인 운영지표는 범위 밖.
