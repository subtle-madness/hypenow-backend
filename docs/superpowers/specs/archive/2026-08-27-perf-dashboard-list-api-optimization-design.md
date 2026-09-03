# 성과 대시보드 목록 API 최적화 — 2단 조립·페이지네이션·탭별 전용 엔드포인트 설계

> 상태: ✅ 구현됨(2026-08-27 작성 · §1~§6 전부 구현, PR ①~④)
>
> 원 요청: FE 개선요청 "성과 대시보드 목록 API 페이로드 + 탭별 전용 엔드포인트" (2026-08-27)
> 선행: [2026-08-12 고정 지연 회신](2026-08-12-perf-dashboard-fixed-latency-reply.md)(슬림 조립) ·
> [2026-08-13 ETag 설계](2026-08-13-performance-dashboard-etag-design.md)(PR ④로 구현됨) ·
> [2026-08-27 브랜드 게시물 페이지네이션](2026-08-27-brand-posts-pagination-design.md)(2단 조립 원형, PR #602)

## 배경

성과 대시보드가 탭 3개(개요/인기 콘텐츠/인기 인플루언서) 구조로 개편됐는데 세 탭 모두
`GET /v1/performance-dashboard/contents?accountType=all` 전량(20.8MB/약 6,000건)을 기다린다.
FE 실측(PostHog 최근 14일): 평균 7.4초, p90 14.3초, 최대 22.6초, 08-27 타임아웃 전면 실패 1건.

**요청서와 현재 코드의 갭 — 착수 전 확정한 사실관계:**

1. **요청 1(목록 댓글 제외)은 이미 구현·운영 배포됨**(08-12, PR #439 슬림 조립). 목록·비교의
   `recentComments`는 항상 빈 배열이다. FE 응답 크기 분석의 "recentComments 7.9MB"는 그 이전
   캡처로 추정 — 회신에서 재측정을 요청한다(§8).
2. 응답이 다시 7초대인 것은 **08-19 크롤링 정책 v1**(365일 백필·수집 상한 2000) 이후 데이터
   증가 때문이다: 슬림 조립 후에도 남는 비용(브랜드 풀 전량 풀 조립 — 스냅샷 일 시계열
   게시물당 최대 365행)이 데이터에 비례해 되돌아왔다.
3. FE가 브랜드 목록에서 잰 "요청당 고정비 1.9초"는 **PR #602(08-27 2단 조립) 배포 전 측정**이다.
   같은 구조 수리를 이 표면에 복제하는 것이 본 설계다.
4. FE가 언급한 `GET /v1/brand-monitoring/influencers`(별도 요청)는 미구현·미착수. 스코프
   (태그 게시물만 vs 대시보드 3계열 전체)와 참여율 분모(followers×postCount vs ratedFollowers)가
   달라 **통합하지 않고 대시보드 전용으로 신설한다**(사용자 결정 08-27).

## 결정 요약

| # | 항목 | 결정 |
|---|---|---|
| P0 | 요청당 고정비 | 브랜드 목록(PR #602)의 2단 조립을 복제 — 확장 ref 인덱스 위에서 목록·집계 전부 계산(§1). FE 제안 사전 집계 테이블은 비채택(§1-4) |
| P1 | 스냅샷 축소 | `snapshotMode=latest` 옵트인 + `previousDayValues` additive 필드(§3) |
| P1 | 정렬·페이지네이션 | 브랜드 목록 규약 복제: sort/order/offset/limit + `meta.page`, 생략 시 전량(§2) |
| P1 | 인플루언서 집계 | `GET /v1/performance-dashboard/influencers` 신설 — ref 위 handle 그룹핑(§4) |
| P2 | 시계열 집계 | `GET /v1/performance-dashboard/growth` 신설 — ref 위 업로드일 버킷팅(§5) |
| P3 | ETag | 08-13 기존 설계 재활용·갱신, 적용 표면에 신설 2종 추가(§6) |
| — | PR 분할 | ① 목록(P0+§2·§3) → ② influencers → ③ growth → ④ ETag 순차(사용자 결정) |

## §1. 공통 기반 — 대시보드 인덱스 패스 (P0, PR ①)

### 1-1. 현재 구조와 비용

`PerformanceContentAssembler.assembleSlim`은 매 요청: ① 레거시 전량 조립(08-12 실측 6ms,
유저당 최대 33행 — 무혐의) + ② 연결 브랜드마다 `BrandPostAssembler.assembleBrandPosts`
**전량 풀 조립**(scope=ALL·커버리지 클램프 on — 스냅샷 일 시계열 전량이 지배 비용) + ③
전 콘텐츠 응답 객체 생성·직렬화. 필터·정렬·statusCounts는 그 위 메모리 계산이다.
"첫 50건"을 위해서도 ②③ 전량이 돈다 — 이것이 고정비의 본체다.

### 1-2. 인덱스 패스(경량)

`PerformanceContentAssembler`에 `index(userId)`를 신설한다. 산출은 `DashboardRef` 목록:

```
DashboardRef(
  contentKey,        // canonicalPostId(shortcode) 또는 합성/레거시 item id — 목록 정렬 타이브레이크
  shortcode, source, sponsorship, status,
  uploadedOn,        // KST 달력일 (정렬·기간 필터·growth 버킷 키)
  brandAccountId, campaignId,
  handle, displayName, profileImageUrl, followers,   // 인플루언서 집계·engagement 정렬용
  latestViews, latestLikes, latestLikesHidden, latestComments,  // 최신 스냅샷 1행 유래
  latestSnapshotOn,  // 최신 스냅샷 날짜 — influencers latestPostAt이 아니라 스냅샷 유무 판정용
  hasSnapshots
)
```

구성은 세 갈래다. **판정·병합 의미론을 현행과 바이트 단위로 보존하는 분해**가 핵심이다:

- **레거시 계열은 현행 전량 조립 유지** — 유저당 소량이라 비용이 무시할 수준이고, 조립된
  `PerformanceContentResponse` 카드에서 ref를 유도한다. 카드는 하이드레이션(§1-3)에 재사용.
- **레거시와 겹치는 브랜드 풀 코드만 풀 하이드레이트** — 겹침 코드는 레거시 건수(≤33)로
  유계다. `BrandPostAssembler.hydrate`로 그 코드들만 풀 카드를 받아 현행 스냅샷 병합
  (`mergeSnapshots`)·협찬 승격·additionalSources·귀속을 그대로 수행한다. 병합 결과 카드에서
  ref를 유도하므로 "최신 스냅샷의 지표별 병합" 의미론이 재구현 없이 보존된다.
- **나머지 브랜드 풀 전량(지배 비용)은 경량 프로젝션만** — 신설 리포지토리 쿼리
  `findDashboardIndex`(브랜드당 1회): 기존 `findBrandPostIndex`의 판정 컬럼(short_code·
  taken_at·tag_detected_at·direct_registered_at·is_paid_partnership·caption) + 작성자
  (username·full_name·profile_pic·followers) + **게시물별 최신 스냅샷 1행**(views·likes·
  likes_hidden·comments·날짜, `findLatestViewsForBrand` 관용구 확장) + hidden 판정 입력을
  단일(또는 2개) 조인 쿼리로 읽는다. 캠페인 매핑은 기존 `campaignIdsByCode` 재사용.

대시보드 표면 계약이 브랜드 목록 표면과 **다른 세 가지**를 신설 쿼리·조립이 그대로 승계한다:

1. **scope=ALL**(정산 전 포함 — 빼면 지표 과소 계상, 기존 loadBrandPool 주석) — 기존
   `indexForBrand`는 ENRICHED_ONLY 전용이라 재사용하지 않고 쿼리를 신설하는 이유다.
2. **커버리지 클램프 on**(수집 상한 v2 §7-1 — coveredUntil보다 깊은 tagged 행 제외, direct 면제).
3. **노출 필터**(direct-only는 등록자에게만) + own-first 다계정 병합(`ownFirst`·putIfAbsent) —
   현행 `loadBrandPool` 순회 구조를 ref 구성에도 동일 적용.

`source`·`sponsorship`·`status`·업로드일 판정은 풀 조립과 같은 함수(`resolveSource`·
`BrandSponsorshipClassifier.classify`·KST 달력일)를 쓴다 — **ref 기반 statusCounts·집계가
전량 조립 값과 정의상 일치**한다(형제 설계와 같은 논거).

### 1-3. 하이드레이트 패스(무거움)

목록 응답에 실을 페이지 코드만 풀 카드로 조립한다:

- 레거시 계열 콘텐츠 → §1-2에서 이미 조립한 카드 재사용(추가 비용 0).
- 브랜드 풀 전용 콘텐츠 → 소속 브랜드별로 `BrandPostAssembler.hydrate`(댓글 없음) 호출 후
  현행 `fromBrandPost` 변환. 페이지 상한(≤100)이라 스냅샷 시계열 비용이 페이지 크기에만
  비례한다.

전량 모드(offset/limit 생략)에서는 전 코드를 하이드레이트한다 — 기존과 동일 결과·유사 비용
(하위 호환이 목적이고, FE가 페이지 파라미터로 전환하는 만큼 자연히 사라지는 경로다).

### 1-4. `/comparison`도 같은 인덱스로

`PerformanceComparisonAssembler`는 콘텐츠별 **최신 스냅샷만** 소비함을 확인했다
(`latestSnapshot` = 마지막 원소). 입력을 `PerformanceContentResponse` 목록에서
`DashboardRef` 목록으로 바꿔 같은 인덱스를 타게 한다 — `/comparison`(현행 평균 ~800ms대의
순수 조립 비용)의 고정비도 함께 사라진다. 5구간 산출·합산 순수 함수는 불변.

**FE 제안 사전 집계 테이블은 비채택** — 형제 설계(08-27 브랜드 페이지네이션)의 기각 사유가
그대로 적용된다: 협찬 판정은 조회 시 소급 계산이 정본, source·가시성·창이 조회자 종속,
취소·재수집 등 드리프트 원천 다수. 같은 목표(응답 시간이 요청 건수에 비례)를 읽기 경로
재구성으로 달성한다. 풀은 브랜드당 수집 상한 2,000으로 유계라 인덱스 패스는 O(상한×브랜드 수).

## §2. `/contents` 정렬·페이지네이션·신규 필터 (요청 3, PR ①)

브랜드 목록(PR #602)·리더보드(6.1) 규약 복제:

- `sort=views|likes|comments|engagement|uploaded`(기본 uploaded) · `order=desc|asc`(기본 desc).
  값 공간 밖 400. `engagement`=(최신 likes+comments)÷followers — 팔로워 미상·좋아요 숨김 등
  분자·분모 미상은 순위에서 제외(정렬 키 null 취급). 모든 정렬 키에서 null은 order와 무관하게
  **항상 마지막**, 타이브레이크는 업로드 최신순 → `item.id`(전순서 — 페이지 간 중복·누락 없음).
- `offset`(≥0)·`limit`(1..100, 기본 100) — **둘 다 생략 시 기존 전량 응답 그대로**(하위 호환).
  하나라도 있으면 페이지 모드. 범위 밖 400.
- 신규 필터:
  - `accountIds` — 쉼표 구분 brandAccountId 목록(FE의 "여러 계정만 보기"). 기존 단수
    `brandAccountId`는 유지하되 **둘 다 오면 accountIds가 이긴다**(신규약 우선, 문서화).
    `accountIds` 명시 시 `brandAccountId`와 동일하게 accountType=all 함의(08-12 규칙 승계).
  - `authorUsername` — handle 소문자 일치. 인플루언서 상세 뷰 전용(페이지네이션 도입 시
    "현재 페이지에 실린 것만 보이는" 문제의 해법 — FE 요청).
- meta: 기존 `total`(필터 적용 후 전체 건수)·`limit`·`statusCounts`·`lastCollectedAt` 유지 +
  additive `page={offset,limit}`(전량이면 `{0,null}` — 형제 표면과 동일). statusCounts 모수
  규칙(분류 필터만, status·기간 미적용)은 불변이며 ref 위에서 센다.

## §3. 스냅샷 축소 — `snapshotMode=latest` (요청 2, PR ①)

- **옵트인 파라미터**(기본은 현행 전체 이력) — FE가 상세 화면을 단건 GET으로 전환하기 전에도
  배포 순서 무관(FE 요청서의 배포 순서 주의사항 해소). 값 공간: `full`(기본)|`latest`, 밖 400.
- `latest`면 각 콘텐츠의 `snapshots`를 **최신 1개**만 싣는다.
- `previousDayValues={views,likes,comments}`(직전 스냅샷의 값, 직전이 없으면 null·필드 키는
  유지)를 `post`에 **additive 필드로 항상** 싣는다 — 목록 카드의 "▲오늘" 표기 재료.
  full 모드에서도 값을 채운다(비용이 0이고, 모드에 따라 필드 유무가 갈리는 계약을 피한다).
- FE 예시의 스냅샷 내 `followers`는 싣지 않는다 — 스냅샷 행에 팔로워 관측이 없고(현 스키마),
  작성자 팔로워는 `item.followers`로 이미 내려간다. 회신에 명시(§8).
- 단건 `/contents/{contentId}`는 무변경(전체 이력·댓글 포함).

## §4. `GET /v1/performance-dashboard/influencers` 신설 (요청 4, PR ②)

목록과 같은 필터 축(`uploadedFrom/To`·`sponsorship`·`accountIds`·`campaignId`·`accountType`) +
`sort`·`order`·`offset`·`limit`. §1 인덱스의 ref를 handle로 그룹핑한다(DB 신규 쿼리 없음).

응답 1행(FE 제안 셰이프 그대로):

```
handle, displayName, profileImageUrl, followers,
postCount, sponsoredCount, likesKnownCount, latestPostAt,
views, likes, comments,          // 아는 값만 합산, 하나도 모르면 null (0 아님)
ratedFollowers, ratedEngaged,    // 참여율 분모·분자
brandAccountIds
meta: { total, page: {offset, limit} }
```

집계 규칙(FE 명세 준수, ref의 최신 스냅샷 지표 기준):

- 지표 합산은 **스냅샷 있는 게시물만** 대상. `views`·`likes`·`comments`는 값을 아는 행만
  더하고 하나도 모르면 null. 좋아요 숨김 게시물은 likes 합계에서 빼고 `likesKnownCount`로
  개수만 노출.
- `ratedFollowers`·`ratedEngaged`: 팔로워를 알고 좋아요·댓글을 **모두** 아는 게시물만 대상,
  게시물마다 작성자 팔로워를 1회씩 더한 값과 (likes+comments) 합.
- `followers`: 작성자의 현재 팔로워(ref의 followers — 산지별 최신 관측). `displayName`·
  `profileImageUrl`도 동일 산지.
- `brandAccountIds`: 그 인플루언서의 필터 통과 게시물이 귀속된 브랜드 id 집합(미귀속 제외).
- `latestPostAt`: 필터 통과 게시물의 최신 업로드일.
- handle이 비어 있는 콘텐츠(작성자 미상)는 집계에서 제외한다.

**FE 확인 항목(회신에 명시, §8):**

- `postCount`는 **필터 통과 게시물 전체 수**(스냅샷 유무 무관)로 정의한다 — "스냅샷 없는
  게시물 제외"는 지표 합산 규칙으로 해석했다. 다르면 회신 회수.
- `sort` 값 공간 제안: `views|likes|comments|engagement|posts|latest`(기본 views desc).
  engagement=ratedEngaged÷ratedFollowers, 분모 미상·0은 순위 제외. 타이브레이크 handle
  (전순서).

## §5. `GET /v1/performance-dashboard/growth` 신설 (요청 5, PR ③)

- 파라미터: `from`/`to`(YYYY-MM-DD) · `granularity=day|week|month`(기본 month) + **목록과 같은
  분류 필터**(`sponsorship`·`accountIds`·`campaignId`·`accountType`) — FE 표에는 앞 4개만
  있으나 축을 목록과 통일한다(authorUsername 부재가 페이지네이션을 무의미하게 만들 뻔한
  전례의 재발 방지 — 캠페인·경쟁사 필터가 걸린 개요 탭이 growth만 못 거르면 전량 폴백하게 된다).
- §1 인덱스 ref를 **업로드일(KST)**로 버킷팅한다. 최신 스냅샷 지표 기준 합산.
  - `granularity=week`는 ISO 월요일 시작(KST 달력일), `month`는 달력월. 버킷 경계도 KST.
  - `from`/`to` 지정 시 빈 버킷 포함 **연속 생성**(contentCount 0·합계 null) — 차트 축 연속성.
    생략 시 데이터가 있는 범위(최소~최대 업로드일).
  - point: `start`·`end`·`contentCount`·`views`·`likes`·`comments`·`followersSum`·
    `viewsMissingCount`·`likesHiddenCount`·`followersMissingCount`. 결측 규칙은 기존 계약
    (08-06 v1): 조회수 미제공(피드)·좋아요 숨김·팔로워 미확인은 합계에서 빼고 카운트로 노출.
    `contentCount`는 버킷 내 게시물 수(차트 지표 중 하나 — FE 강조), `followersSum`은
    게시물별 작성자 팔로워 합(참여율 분모).
  - `accounts[]`(brandAccountId별 시리즈)는 **항상** 내려준다 — 계정 비교 차트·CSV 재료.
    individual(브랜드 미귀속) 콘텐츠는 최상위 `points`에는 포함되고 `accounts[]`에는 시리즈가
    없다(귀속 브랜드가 없으므로 — 회신에 명시).
  - 업로드일 미상 콘텐츠(collecting 등 post 없는 아이템)는 버킷 판정 불가로 제외.

## §6. ETag 조건부 요청 (요청 6, PR ④)

[08-13 기존 설계](2026-08-13-performance-dashboard-etag-design.md)를 재활용한다 — 버전키
(데이터 유래 지문 + cacheEpoch + KST 날짜), 조립 전 조기 반환, `private, no-cache` 전환,
스테이징 수동 검증 8종 체크리스트 전부 유효. 갱신 사항:

- **적용 표면 확장**: `/contents`·`/comparison` + 신설 `/influencers`·`/growth`. 버전키는
  데이터 지문이라 URL(파라미터 조합)과 무관하게 동일 — 브라우저가 URL별로 캐시하므로
  파라미터 공간이 커져도 충돌 없음. 같은 지문 계산을 4개 표면이 공유한다.
- **지문 컬럼 재점검**: 08-13 이후 스키마 변화(direct 이관 완료 여부·`brand_direct_posts`
  상태·해시태그 태그 장부·campaignId 부착 경로)를 반영해 §2-1 입력 목록을 구현 시 재검증한다.
  "해싱 컬럼 ↔ 응답 영향 컬럼 1:1" 결합 테스트로 고정(기존 설계 §6).
- 08-13 설계의 "페이지네이션 기각"(§7)은 본 설계로 **대체된다** — 당시 전제(FE가 전량을 받아
  클라이언트 필터)가 UI 개편으로 사라졌다. 해당 문서에 대체 주석을 단다.

## §7. 검증

- 테스트(기존 `V1PerformanceDashboardControllerTest` 스타일 — 실 어셈블러 + repo mock):
  - ref-counts·statusCounts가 전량 조립 값과 일치(같은 필터 조합 대조).
  - 페이지 두 쪽 합 = 전량·중복 없음, 정렬별(5종×asc/desc) null-마지막·타이브레이크 안정성.
  - snapshotMode=latest의 스냅샷 1개·previousDayValues, full 모드 불변, 단건 전체 이력 불변.
  - accountIds 복수 필터·brandAccountId 공존 우선순위·authorUsername 일치.
  - influencers: null 규칙(하나도 모르면 null)·likesKnownCount·ratedFollowers 대상 선정·
    brandAccountIds·미상 handle 제외.
  - growth: 버킷 경계(KST 자정·ISO 주 시작·달력월), 빈 버킷 연속 생성, 결측 3카운트,
    accounts[] 시리즈와 총계 정합(individual 포함 차).
  - 파라미터 검증 400 전 조합, 생략 시 전량 하위 호환.
- 실측: `PerfDiagnosisHarnessTest`(운영 덤프 복원, `PERF_DIAG=1` 게이트)로 인덱스 패스 전/후
  조립 시간 비교 — 목표는 첫 페이지(limit=50) 2초 내(FE의 브랜드 목록 완료 판정과 동형).
- ETag는 기존 설계 §5·§6의 스테이징 체크리스트 전부(운영 반영 전 필수).

## §8. FE 회신 (스펙 승인 후 별도 문서)

1. 요청 1(댓글 제외)은 08-12 반영 완료 — 응답 크기 재측정 요청(recentComments 7.9MB는 이전
   캡처로 추정).
2. 브랜드 목록 고정비 1.9초는 PR #602(08-27) 배포 전 측정 — 배포 후 재측정 요청.
3. §3(스냅샷 내 followers 미포함)·§4(postCount 정의·sort 값 공간)·§5(individual의 accounts[]
   부재·필터 축 통일) 확인 요청.
4. 인플루언서 엔드포인트는 대시보드 전용 신설(브랜드 모니터링 쪽 요청과 비통합) — 사유 공유.
