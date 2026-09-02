# JJ — v1 인플루언서 AI 리포트 구 카피 5필드 NULL 수렴

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: Q
- **상태**: ⬜ 대기(미착수, 07-30 신규 발견)

## 내용

`V1InfluencerReportRepository.findLatestCopy`(`was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java:32-42`)가 `account_analyses`에서 `ORDER BY analyzed_at DESC LIMIT 1`로 계정별 최신 1행만 읽고, v2(`V2InfluencerReportRepository`)와 달리 `perf_summary IS NOT NULL` 필터가 없다. Q(인플루언서 리포트 개편, 07-27)에서 계정 카피가 7종→5종으로 개편되며 구 카피 5필드(tagline 제외 — `ad_headline`·`summary`·`trend_note`·`chart_note`·`pace_note`)가 `AccountAnalysisWriter.insert`(`analytics/src/main/java/.../analyze/AccountAnalysisWriter.java:71`)의 INSERT 컬럼 목록에서 빠졌다(`:50` 주석: "구 카피 5컬럼은 07-27 개편 후 미기록"). 결과적으로 07-27 이후 재분석된 계정은 `findLatestCopy`가 최신 행(5컬럼 전부 NULL)을 집어 `GET /v1/influencers/{id}/ai-report`(`V1InfluencerReportController.java:18-20`)가 이 5필드를 점차 NULL로 서빙하게 된다 — 재분석이 쌓일수록 확산되는 시한부 결함.

**전례**: 구 `GET /api/influencers/{handle}`을 07-27에 제거한 사유가 정확히 이 문제였다(`docs/tracks/archive/E-인플루언서-api.md:9` — "카피 7종→5종 개편(V40)으로 구 카피 컬럼이 NULL로 쌓여 점차 빈 카피를 서빙하게 되는 표면이었음"). v1은 그때 제거 대상에서 제외되고 남아, 같은 결함을 다시 안고 있다.

v1은 프론트 기존 패널이 아직 소비 중이라 v2와 병존한다(`V2InfluencerReportController.java:19-20` 주석: "6.5(v1)는 기존 패널이 소비 중이라 병존 — 프론트 v2 전환 후 별도 PR로 폐기"). **프론트가 v1의 null 5필드를 실제로 어떻게 렌더링하는지는 이 저장소(hypenow-backend)에서 확인 불가 — celfit-front 확인이 필요하다.**

해소 선택지 3가지(택 1, 프론트 확인 후 결정):
1. 프론트 v2 전환을 앞당기고 v1(6.5) 자체를 E와 같은 방식으로 폐기
2. `V1InfluencerReportAssembler`의 `ad_headline` 등 구 카피 조립부(`toAds`, `V1InfluencerReportAssembler.java:97`)를 v2 결정론 템플릿 `V2InfluencerReportAssembler.headline()`(`was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportAssembler.java:178`) 재사용으로 치환 — 나머지 4필드(summary·trend_note·chart_note·pace_note)도 상응하는 v2 산출 로직이 있으면 같은 방식 검토
3. 프론트가 null을 이미 허용 렌더링하면 방치(단, 이 저장소만으로는 확인 불가 — 위 전제 필요)
