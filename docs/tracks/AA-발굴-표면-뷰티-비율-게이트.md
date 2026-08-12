# AA — 발굴 표면 뷰티 비율 게이트

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: P4, R
- **상태**: 🔨 (구현 완료 — PR 대기)

## 내용

계정 단위 뷰티 판정(crawler `influencer.beauty_class`)의 오판(0% 구간 스팟체크 20개 중 17개)을 계정 판정 로직 변경 없이 발굴 표면에서 게시물 실측 비율로 필터. `account_beauty_ratio` 뷰(analysis Flyway V45 — 창 내 분석 완료·뷰티 판정 원시 카운트만, 정책 없음) + was 게이트(분석 8건 미만은 보류·통과, 뷰티 비율 20% 미만이면 제외, 기존 카테고리 게이트와 동일 임계값 재사용). 적용은 발굴 목록 `GET /v1/influencers`(`V1InfluencerDiscoveryRepository`)·유사 인플루언서 후보 단계(`V2InfluencerReportRepository.findSimilarHandles`)뿐 — 랭킹 `/v1/contents`·상세 직접 조회·저장 목록은 `account_summaries`를 조인하지 않아 무영향(의도적 미적용). 0으로 나누기 방어(`NULLIF` 관용구, 카테고리 게이트와 동일 패턴)를 후속 커밋으로 동봉 — [V45__account_beauty_ratio_view.sql](../../analytics/src/main/resources/db/migration/analysis/V45__account_beauty_ratio_view.sql)
