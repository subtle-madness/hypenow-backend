# 랭킹 시점(timeliness) 노출 정책 — 설계

> 상태: ✅ 구현/반영됨 (운영 배포는 사용자 승인 후 was 재배포)

2026-07-21. PO 결정: 랭킹 `/v1/contents`는 시점 편향 없는 분석분만 노출하고,
늦크롤 백필분(`late_backfill`)은 인플루언서 상세에서만 보이게 한다.

## 배경

07-19 `metric_timeliness`(V33) 도입 시 컬럼은 timely/late_backfill/immature 구분만
담당하고 **서빙(랭킹) 노출 정책은 미결**로 남겼다(ARCHITECTURE §7 2026-07-19 행).

07-20 트랙 M(Vertex 전환 + 최근 12개 백필 재도입, 스펙
[2026-07-20-vertex-migration-recent12-backfill-design.md](2026-07-20-vertex-migration-recent12-backfill-design.md))이
"백필 MVP 제외"를 번복해 계정별 최근 N개(=12) 윈도우 안이면 제때 크롤 실패분도 분석 대상에
포함하도록 04 뷰·`ContentAnalysisJob` 자격을 확장했다. 초기 백필 ~1.4만 건이 유입 예정.

문제: `late_backfill` 행은 고정 지표를 업로드 +pin(3)일이 아니라 **더 늦은 시점에 캡처**해
누적 좋아요·조회수·(릴스) 조회수가 timely 행(+3일 고정) 대비 **상향 편향**된다. 랭킹은
현재 "분석 완료분 전부"를 노출하므로 백필분이 대량 유입되면 편향된 행이 상위를 차지한다.

## 결정

| 항목 | 결정 |
|---|---|
| 랭킹 `/v1/contents` | **timely + 미분류 레거시(NULL)만 노출** — `late_backfill`·`immature` 제외 |
| 인플루언서 상세 `recentContents` | **무변경** — 최신 12개 전부(미분석·late_backfill 포함) 노출 유지 |
| 저장 목록·per-content 리포트 | **무변경** — 사용자가 특정 콘텐츠를 직접 여는 맥락이라 노출 유지 |
| 레거시 `/api/contents` | **범위 밖** — 구 최신 스냅샷 패러다임(핀 미적용)·정리 대기 |

### NULL(미분류 레거시) 처리

V33은 "기존 행은 NULL로 두고 운영 1회성 UPDATE로 채운다". 신규 행은 잡·백필 러너가 항상
timely/late_backfill을 쓴다. 필터를 `= 'timely'`로 하면 미분류 레거시(V33 이전 기분석분 —
백필 편향 개념과 무관)를 랭킹에서 떨궈 **비회귀 위반**. 따라서
`metric_timeliness = 'timely' OR metric_timeliness IS NULL`로 구현 — late_backfill·immature만
제외하고 timely·레거시NULL은 유지. `immature`(미성숙 하향 편향, 가드 도입 전 극소수 레거시)는
편향 제거 취지상 함께 제외한다.

## 구현

was 서빙 계층 한 곳 (분석 결과 읽기 — 시스템 경계 준수, raw 무접촉·analysis 무쓰기):

- `V1ContentRepository.buildWhere` (`findCards`·`countCards` 공용 WHERE)에
  `AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)` 추가.
- 인플루언서 상세 `V1InfluencerRepository.findRecentCards`(LEFT JOIN)는 무변경 —
  백필분이 그대로 최신 12에 노출된다.

## 테스트

- `V1ContentRepositoryTest`: 별도 기간 창([07-11, 07-20))에 tl1(timely)·lb1(late_backfill)·
  lg1(시점 NULL 레거시) 대조군 추가. `lb1`은 hype 999로 필터 없으면 1위인데 랭킹에서 제외,
  `tl1`·`lg1`은 노출. `countCards`도 2로 정합. 기존 케이스는 전 행 timely 마킹으로 무영향.
- `./gradlew :was:test` GREEN.

## 범위 밖 / 후속

- 운영 반영: was 재배포만 필요(뷰·마이그레이션·백필 무관) — 사용자 승인 후.
- 백필 실행(~1.4만, Vertex 배치)은 트랙 M 런북 소관.
- `immature` 레거시 재분류·`/api/contents` 정리는 별도 트랙.
