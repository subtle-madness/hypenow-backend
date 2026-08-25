# 브랜드 direct 게시물 삭제 감지 → hidden 노출 설계

> 상태: 🟢 활성

## 배경

- FE(celfit-front main)는 `BrandPostResponse.trackingStatus`가 `"hidden"`이면 카드에 "삭제·비공개"
  칩을 띄우는 UI가 이미 구현돼 있다(`BrandTaggedPostCard.tsx:100`, 계약 6.25). 카드를 숨기는 게
  아니라 마지막 수집값을 보존한 채 상태를 밝히는 방식이다.
- 그런데 BE 브랜드 파이프라인은 `trackingStatus`를 항상 `"tracking"`으로 하드코딩하고
  (`BrandPostAssembler` tagged/direct 조립), 야간 스윕이 게시물 404(`SubjectNotFoundException`)를
  만나도 로그만 남기고 삼킨다(스펙 §8 "브랜드 파이프라인은 상태 전이를 하지 않는다"). 감지 재료는
  있는데 영속화가 없어 FE에 전달할 방법이 없는 상태다.

## 범위 결정 (2026-08-25 사용자 확정)

**direct 등록 게시물만** 감지한다. 근거:

- 삭제를 결정적으로 확인할 수 있는 신호는 단건 조회(`fetchPost`)의 404뿐이고, 이 콜은 야간 스윕
  2단계(`BrandDirectCollectService.sweepDirect`)에서 direct 행(+태그 열거가 못 만난 겹침 행)에
  이미 나가고 있다 — 추가 Hiker 비용 0, 오탐 0.
- tagged-only 게시물은 태그 열거로만 관측된다. 삭제되면 열거에서 사라질 뿐 404가 없고, 부재는
  태그 해제·수집 상한 컷과 구분 불가라 휴리스틱은 오탐, 검증 콜은 과금(404도 과금 — Hiker 실측)이
  따른다. 대상 외로 두고 기존과 동일하게 `tracking`으로 노출한다.

## 설계

### 1. 스키마 (monitoring DB, expand-only)

`brand_tagged_post`에 `unavailable_at timestamptz NULL` 추가. NULL = 정상, 값 = 마지막 단건
조회가 404를 받은 시각. UTC 타임스탬프 채번(모니터링 공간, 20260812170000 초과), DROP 없음.

### 2. 감지·해제 (monitoring)

- **세팅**: `BrandDirectCollectService.collectOne`의 `SubjectNotFoundException` catch(현재 로그만
  남기는 자리)에서 `unavailable_at = now()` 기록. 행 삭제·스냅샷 변경 없음 — "카드는 마지막
  수집값 보존" 원칙 유지. 등록 경로(`collectAndEnrich`)의 404는 기존대로 호출자 전파(등록 실패
  응답) — 행이 아직 없으므로 마킹 대상이 아니다.
- **해제(자가 치유)**: `TaggedPostRepository.touchCrawled`에 `unavailable_at = NULL`을 추가한다.
  touchCrawled는 "이번 열거·실수집에서 실제로 만난 게시물"에만 찍히는 시맨틱이라(직접 관측 =
  존재 확인) 해제 지점으로 정확하다 — 단건 수집 성공(collectOne)·태그 열거 재관측(겹침 행)·등록
  경로 성공이 전부 이 메서드를 지난다. `touchCrawledDepth`(깊이 커버 — 개별 관측 아님)는 해제하지
  않는다.
- **due 선정은 불변**: 마킹된 게시물도 기존처럼 게시 180일까지 매일 1콜 나간다. IG는 보관(archive)
  후 재공개가 흔해 복구가 실제로 일어나고, 404 과금은 건당 $0.00069로 무시 가능하다.

### 3. 노출 (was)

- `BrandReadRepository`의 brand_tagged_post 조회 SQL과 `BrandTaggedPostRow` record에
  `unavailable_at` 추가.
- `BrandPostAssembler`의 하드코딩 `TRACKING`을 `unavailableAt != null ? "hidden" : "tracking"`으로.
  목록(`GET /brand-monitoring/accounts/{id}/posts`)·단건 상세(`GET /brand-monitoring/posts/{id}`)가
  같은 조립기를 타므로 둘 다 반영된다. 계약 필드 추가 없음 — FE 수정 0.
- 레거시 pending direct 조립(`legacyPendingPost`)은 `item.status()`를 그대로 실어 이미 hidden이
  가능하므로 손대지 않는다.

### 4. 구현 중 검증 지점

- 브랜드 목록 `meta.counts`가 상태 축을 세는지 확인 — 조립 결과에서 파생하면 자동 반영, 별도
  SQL 카운트면 거기도 반영.
- 성과 대시보드(`PerformanceContentAssembler`)가 같은 행으로 direct 게시물 상태를 합성한다면
  같은 조건을 적용(1줄이면 포함, 구조가 다르면 후속으로 분리).

### 5. 테스트

- monitoring: collectOne이 404에서 `unavailable_at`을 세팅하는지 / 성공 수집(touchCrawled)이
  해제하는지.
- was: `unavailable_at` 있는 행이 `trackingStatus: "hidden"`으로 내려가는지, NULL이면 기존대로
  `"tracking"`인지.
