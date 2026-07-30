# V — 발굴 목록 계정 하입 스코어

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: C1, P4
- **상태**: ✅ (구현 완료 — 배포 대기)

## 내용

`v_account_summaries.avg_hype_score` 신설(B안 — 미러 3곳 동시 변경) — 계정별 최근 12창 콘텐츠 `hype_score()`(신선도 감쇠 포함, 콘텐츠 함수 재사용) 단순 평균. 계약 record 확장 + analysis Flyway V42(`account_summaries.avg_hype_score` 컬럼 — V41은 trait_taxonomy가 선점, V18 경합 전례로 재번호) + was 발굴 목록(`GET /v1/influencers`, 6.21) `sort=hype` 정렬 옵션과 카드 `hypeScore` 노출 배선. 유사 카드(6.23, R)는 발굴 카드와 SELECT·DTO를 공유해 자동 포함(의도된 부수효과) — [specs/2026-07-29-influencer-avg-hype-score-design.md](../superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md) [plans/2026-07-29-influencer-avg-hype-score.md](../superpowers/plans/archive/2026-07-29-influencer-avg-hype-score.md)
