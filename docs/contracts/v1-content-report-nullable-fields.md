# `GET /v1/contents/{shortCode}` (스펙 6.3) - null 가능 필드 계약

> 상태: 🟢 활성 · 2026-09-03

## 무엇이 바뀌었나

콘텐츠 AI 분석이 2단계로 갈렸다([설계](../superpowers/specs/2026-09-03-content-analysis-two-phase-split-design.md)).

- **파트 A(사실)**: 광고 판정·카테고리·브랜드·제품·유통사·협찬 신호. 캡션만 보고 만들며
  **업로드 다음 날(D+1)** 채워진다.
- **파트 B(해석)**: AI 요약·패턴 해설·댓글 인사이트·댓글 신뢰도 + 계정 기준선 스냅샷.
  3일 고정 지표와 계정 기준선을 인용하므로 **업로드 나흘 뒤(D+4)** 채워진다.

그래서 D+1 ~ D+3 사이 이 API는 **404가 아니라 200**을 돌려주고, 파트 B 산출물만 null이다.
이전에는 분석 행이 아예 없어 404였다.

## D+1 ~ D+3 응답에서 null인 필드

| 경로 | 의미 | 언제 채워지나 |
|---|---|---|
| `aiContentSummary` | AI 요약 | 파트 B(D+4) |
| `comparison.narrative` | 패턴 해설 | 파트 B(D+4) |
| `comparison.engagementRate.baseline` | 계정 최근 12개 평균 참여율 | 파트 B(D+4) |
| `comparison.engagementQuality.likes.baseline` | 최근 12개 평균 좋아요 | 파트 B(D+4) |
| `comparison.engagementQuality.comments.baseline` | 최근 12개 평균 댓글 | 파트 B(D+4) |
| `commentAnalysis.insight` | 댓글 인사이트 | 파트 B(D+4) |
| `commentAnalysis.signals.authenticity.grade` / `.note` | 댓글 신뢰도 판정 | 파트 B(D+4) |
| `categoryContext.percentile` | 카테고리 상위 백분위 | 파트 B(D+4) |

## D+1부터 이미 채워지는 필드

`vlmAnalysis`(브랜드·협찬 신호·광고 고지·제품 카테고리·속성), `categoryContext.categoryLabel`,
`categoryContext.categoryAvgViews`, `categoryContext.sampleSize`,
`comparison.views`(조회수·라이브 재계산 기준선·순위·최근 릴스 차트),
`comparison.engagementRate.value`, `comparison.engagementQuality.*.value`.

인플루언서 상세(6.4 / v2)의 최근 콘텐츠 `adType`·카테고리도 D+1부터 값이 있다.

## 화면 요청

D+1 ~ D+3 구간은 "분석 실패"가 아니라 "해석 준비 중"이다. 위 null 필드는 빈 문자열이나 0이
아니라 **자리표시(예: 준비 중)** 로 그려 주기 바란다. 파트 A 값(광고 배지·카테고리·브랜드)은
그대로 노출하면 된다.

## 랭킹(6.1)은 무엇이 바뀌나

바뀌지 않는다. 랭킹 노출 시점은 현행과 같은 D+4다 - 파트 B가 채워지기 전에는 지표 시점이
미확정(`pending`)이라 랭킹 쿼리가 제외한다.

## 롤백

백엔드 `app_setting`의 `analytics.analyze-mode`를 `unified`로 되돌리면 이 계약도 이전 상태
(D+1 ~ D+3은 404)로 돌아간다. 되돌릴 때는 FE에 별도로 알린다.
