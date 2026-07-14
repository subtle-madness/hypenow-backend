# 태스크 E: 인플루언서 상세 API (`GET /api/influencers/{handle}`) Implementation Plan

> 상태: ✅ 구현/실행/반영됨 (2026-07-14)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development 또는 superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** celfit-front 인플루언서 패널(`AccountReport`)의 결정(비LLM) 데이터 전부를 1회 호출로 서빙한다 — C1 미러 3종 + `accounts` 조합.

**Architecture:** C1 스펙(specs/2026-07-13-c1-account-detail-design.md)이 E에 넘긴 계약을 그대로 소비 — `account_summaries`(주 리소스, 부재 시 404)·`account_category_stats`·`account_content_series`를 **계약 record로 SELECT**(§4-3), 프로필 표시 필드는 `accounts` 조합(부재 시 null 허용 — D의 account 규약과 동일). was 몫의 표현 조립(C1 스펙 §3 말미): `lastUploadDaysAgo`·`isActive`(14일)·`lastAdNote` 문구·광고 `strip`(올린 순 bool 배열). 집합 연산 없음 — 전 지표는 C1이 저장한 값 전달. LLM 카피 7종(summary·trend.note·traits·ads.headline·brands·paceNote·tagline)은 **필드 부재** → C2 additive.

**Tech Stack:** 기존 was 스택 (JdbcClient·record·MockMvc·Testcontainers). D·H 관용구 재사용.

**작업 위치:** worktree `.worktrees/task-d2`, 브랜치 `feat/task-e-influencer-api` (origin/develop 기준 신규).

**응답 계약 (프론트 렌더러 실측 2026-07-14 — 필드명은 AccountReport 소비 경로와 일치):**

```jsonc
{
  "profile": {                       // accounts ⊕ account_summaries — accounts 부재 시 표시 필드 null
    "handle": "…", "displayName": "…", "profileImageUrl": "…",
    "followers": 42555, "followsCount": 312, "postsCount": 486, "biography": "…"
  },
  "report": {                        // account_summaries 1행 — 이 행이 없으면 404
    "totalPosts": 486,               // = posts_count ("전체 N개 중 최근 M개" 카피)
    "analyzedCount": 12,
    "stats": { "metric": "views", "avgViews": 30600, "viewsPerFollower": 1.3,
               "avgErPct": 4.3, "avgLikes": 5515, "avgComments": 90 },
    "trend": { "direction": "up", "changePct": 18, "olderAvg": 25000, "newerAvg": 29500 },
    "chart": { "metric": "views", "bars": [                    // 올린 순(posted_at ASC, short_code ASC)
      { "shortCode": "…", "postedAt": "…", "contentType": "reels",
        "views": 1000, "likes": 100, "comments": 10, "sponsored": false } ] },
    "contentMix": { "categories": [ { "label": "스킨케어", "count": 7 } ] },  // main_group 어휘, count DESC·label ASC
    "ads": { "sponsoredCount": 2,
             "strip": [false, true, …],                        // bars와 같은 순서의 sponsored 배열
             "lastAdPostedAt": "…", "lastAdNote": "마지막 광고 3일 전",   // 없으면 둘 다 null
             "comparison": {                                    // organic_avg·ad_avg 둘 다 있어야, 아니면 null
               "metric": "views", "organicCount": 6, "adCount": 2,
               "organicAvg": 30600, "adAvg": 26900, "adDropPct": 12 } },
    "activity": { "lastPostedAt": "…", "lastUploadDaysAgo": 2,
                  "isActive": true,                             // lastUploadDaysAgo < 14 (프론트 상수, was 몫 — C1 스펙)
                  "avgIntervalDays": 2.4 }
  }
}
```

- 표현 조립 규칙: `lastUploadDaysAgo`/`lastAdDaysAgo` = D·H와 동일한 24h 단위(Clock 기준, postedAt null → null). `lastAdNote` = lastAdPostedAt 없으면 null, 경과 0일 → `"마지막 광고 오늘"`, N일 → `"마지막 광고 N일 전"`. `isActive` = lastUploadDaysAgo != null && < 14.
- `strip` = chart.bars와 동일 정렬의 `sponsored` 값 배열 (프론트가 "올린 순서대로 (왼쪽이 과거)" 렌더).
- 원값 제공 원칙(§4-6): trend·comparison의 원값 전부 노출. C1이 NULL로 둔 값은 null 그대로(해석 없음).
- 404: `account_summaries`에 handle 없음. 미러 테이블 부재도 404 (D 관례).

## File Structure

```
was/src/main/java/com/celfit/was/influencer/
  InfluencerDetailRepository.java   [신규] 4테이블 조회 (계약 record 3종 + Account)
  InfluencerDetailResponse.java     [신규] profile + report 블록
  InfluencerDetailAssembler.java    [신규] 조립 + 경과일·isActive·lastAdNote·strip (Clock 주입)
  InfluencerDetailController.java   [신규] GET /api/influencers/{handle} + 404
was/src/test/java/com/celfit/was/influencer/
  InfluencerDetailRepositoryTest.java  [신규] Testcontainers
  InfluencerDetailAssemblerTest.java   [신규] 고정 Clock 단위
  InfluencerDetailControllerTest.java  [신규] MockMvc
```

**테스트 기대값 근거 (시드 — 계정 `glow`):**

- accounts: (glow, 글로우, https://pic/glow.jpg, 42555)
- account_summaries: handle=glow, followers=42555, follows_count=312, posts_count=486, biography='건성 8년차',
  analyzed_count=12, views_count=10, metric='views', avg_views=30600, views_per_follower=1.3, avg_er_pct=4.3,
  avg_likes=5515, avg_comments=90, trend_direction='up', trend_change_pct=18, trend_older_avg=25000,
  trend_newer_avg=29500, sponsored_count=2, organic_avg=30600, ad_avg=26900, ad_drop_pct=12,
  comparison_organic_count=6, comparison_ad_count=2, last_ad_posted_at=2026-07-11T00:00Z,
  last_posted_at=2026-07-12T00:00Z, avg_interval_days=2.4
- account_category_stats: (glow, 스킨케어, 7), (glow, 메이크업, 3), (glow, 클렌징, 3) → count DESC·label ASC 정렬
- account_content_series: g1(07-01, reels, 1000/100/10, sponsored=false), g2(07-05, feed, NULL/300/30, true),
  g3(07-08, reels, 2000/150/15, false) → 올린 순 g1·g2·g3, strip=[false,true,false]
- Clock 고정 2026-07-14T00:00Z → lastUploadDaysAgo=2(07-12), isActive=true, lastAdNote="마지막 광고 3일 전"(07-11)

---

### Task 1: 리포지토리 (TDD)

**Files:** Test + `InfluencerDetailRepository.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `IntegrationTest` 상속. `@BeforeEach`: accounts(기존 DDL 재사용) + V10 3테이블 DDL(analytics/src/main/resources/db/migration/analysis/V10__account_detail_tables.sql과 동일 형상 — 파일에서 복사) DROP/CREATE + 위 시드. 테스트 5개:

```java
	@Test
	void handle로_요약_1행을_계약_record로_읽는다() {
		Optional<AccountSummary> found = repository.findSummary("glow");
		assertThat(found).isPresent();
		assertThat(found.get().metric()).isEqualTo("views");
		assertThat(found.get().avgErPct()).isEqualByComparingTo(new BigDecimal("4.3"));
		assertThat(found.get().adDropPct()).isEqualTo(12);
		assertThat(found.get().avgIntervalDays()).isEqualByComparingTo(new BigDecimal("2.4"));
	}

	@Test
	void 없는_handle이면_empty다() { assertThat(repository.findSummary("nope")).isEmpty(); }

	@Test
	void 카테고리_믹스는_개수_내림차순_라벨_오름차순이다() {
		List<AccountCategoryStat> stats = repository.findCategoryStats("glow");
		assertThat(stats).extracting(AccountCategoryStat::mainGroup)
				.containsExactly("스킨케어", "메이크업", "클렌징");   // 7 · 3 · 3 (동률은 라벨순)
	}

	@Test
	void 시계열은_올린_순이다() {
		List<AccountContentPoint> series = repository.findSeries("glow");
		assertThat(series).extracting(AccountContentPoint::shortCode).containsExactly("g1", "g2", "g3");
		assertThat(series.get(1).views()).isNull();   // 피드 NULL 보존
		assertThat(series.get(1).sponsored()).isTrue();
	}

	@Test
	void 미러_테이블이_없으면_빈_값으로_저하한다() { /* 4테이블 DROP → 전 메서드 empty/빈 목록 */ }
```

- [ ] **Step 2: 실패 확인** → **Step 3: 구현** — D·H와 동일 관용구(safeQuery, 계약 record 매핑):

```java
	public Optional<AccountSummary> findSummary(String handle)      // SELECT 26컬럼 = record 순서 = V10
	public Optional<Account> findAccount(String handle)             // 기존 accounts 4컬럼 (PostDetailRepository와 동일 쿼리 — 패키지 분리로 의도적 중복)
	public List<AccountCategoryStat> findCategoryStats(String h)    // ORDER BY content_count DESC, main_group
	public List<AccountContentPoint> findSeries(String h)           // ORDER BY posted_at, short_code
```

- [ ] **Step 4: 5개 PASS + was 전체 그린** → **Step 5: Commit** — `feat(was): 인플루언서 상세 조회 — C1 미러 3종+accounts (계약 record 매핑)`

---

### Task 2: 응답 record + 어셈블러 (TDD)

**Files:** Test + `InfluencerDetailResponse.java` + `InfluencerDetailAssembler.java`

- [ ] **Step 1: 실패하는 테스트 작성** — 고정 Clock(2026-07-14T00:00Z), `new InfluencerDetailAssembler(fixedClock)` (jsonb 없음 — ObjectMapper 불필요). 테스트 5개:
  1. 전체 조립: profile 7필드, totalPosts=486, stats·trend 원값, chart.metric='views'·bars 3개 올린 순, categories 라벨·count, ads(sponsoredCount=2, strip=[false,true,false], lastAdNote="마지막 광고 3일 전", comparison 원값+adDropPct=12), activity(lastUploadDaysAgo=2, isActive=true, avgIntervalDays=2.4)
  2. accounts 부재 → profile의 displayName·profileImageUrl null, handle·followers 등 summaries 값은 유지
  3. last_ad_posted_at null → ads.lastAdPostedAt·lastAdNote null, comparison은 organic_avg 또는 ad_avg null이면 null
  4. last_posted_at이 오늘 → lastUploadDaysAgo=0·lastAdNote 조립 규칙 "마지막 광고 오늘"(lastAd도 오늘로), isActive=true
  5. lastUploadDaysAgo 14 경계 → 14일 전이면 isActive=false (13일 전 true)

- [ ] **Step 2: 실패 확인** → **Step 3: 구현** — 시그니처:

```java
	public InfluencerDetailResponse toResponse(AccountSummary summary, Account account,
			List<AccountCategoryStat> categoryStats, List<AccountContentPoint> series)
```

  - Response 중첩 record: `Profile` / `Report(totalPosts, analyzedCount, Stats, Trend, Chart(metric, List<Bar>), ContentMix(List<Category>), Ads(sponsoredCount, List<Boolean> strip, lastAdPostedAt, lastAdNote, Comparison), Activity)`
  - 경과일 헬퍼는 D·H와 동일 시맨틱의 로컬 중복(Javadoc 명시). isActive = days != null && days < 14 (프론트 상수 — C1 스펙 §5).
  - comparison: `organicAvg`·`adAvg` 둘 중 하나라도 null이면 블록 전체 null (프론트 `o.comparison?` 분기 대응).
  - LLM 필드(summary·note·traits·headline·brands·paceNote)는 **record에 없음** — C2 additive.

- [ ] **Step 4: 5개 PASS** → **Step 5: Commit** — `feat(was): 인플루언서 리포트 조립 — 경과일·isActive·lastAdNote·광고 스트립 (표현 조립)`

---

### Task 3: 컨트롤러 (TDD)

**Files:** Test + `InfluencerDetailController.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `@WebMvcTest` + `@Import({InfluencerDetailAssembler.class, ClockConfig.class, WebConfig.class})`(CORS properties 포함 — D 관용구), `@MockitoBean` 리포지토리. 테스트 3개: ①200 + jsonPath(profile.displayName, report.stats.metric, report.chart.bars[0].shortCode, report.ads.strip[1]=true, report.activity.isActive) ②summaries 없음 → 404 ③CORS 허용 오리진 헤더(D 테스트 관용구)

- [ ] **Step 2: 실패 확인** → **Step 3: 구현**:

```java
	@GetMapping("/api/influencers/{handle}")
	public InfluencerDetailResponse influencerDetail(@PathVariable String handle) {
		return repository.findSummary(handle)
				.map(summary -> assembler.toResponse(
						summary,
						repository.findAccount(handle).orElse(null),
						repository.findCategoryStats(handle),
						repository.findSeries(handle)))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "인플루언서를 찾을 수 없습니다: " + handle));
	}
```

- [ ] **Step 4: was 전체 그린 (기존 48 + 신규 13 = 61)** → **Step 5: Commit** — `feat(was): GET /api/influencers/{handle} 인플루언서 상세 API`

---

### Task 4: E2E (실 analysis DB)

- [ ] `./gradlew test` 전 모듈 그린
- [ ] worktree `:was:bootRun` 후: 실 DB의 handle로 200(전 블록 채움 — C1 미러가 돌았는지 먼저 `SELECT count(*) FROM account_summaries` 확인, 0이면 additive null 경로 대신 404 확인으로 대체하고 보고), 없는 handle 404, CORS 헤더
- [ ] 서버 종료, working tree clean

### Task 5: 문서 갱신

- [ ] ARCHITECTURE §5 E 행: 내용 갱신(`GET /api/influencers/{handle}` — profile+report(AccountReport 결정 지표), LLM 카피는 C2 additive) + ⬜→✅
- [ ] §7 결정 기록 추가: E 계약 확정(프론트 AccountReport 렌더러 실측 — 표현 조립 3종은 was 몫 이행, comparison null 규칙, LLM 필드 부재→C2 additive)
- [ ] 계획 상태 헤더 ✅ → Commit — `docs: E 완료 반영 (인플루언서 상세 API 계약)`

## 완료 기준 (DoD)

- 신규 테스트 13개(리포지토리 5·어셈블러 5·컨트롤러 3) + was 전체·전 모듈 그린
- 실 DB E2E 200/404/CORS
- LLM 필드 부재(additive 지점)와 표현 조립 규칙(24h·14일·문구)이 계약 문서·테스트로 고정

## 다루지 않는 것

- C2 산출물(tagline·summary·trendNote·chartNote·traits·adHeadline·paceNote·brands) — additive 확장 (C2 세션 진행 중)
- recentContents 탭 데이터 — 랭킹 목록 API(H)를 프론트가 handle 필터로 재사용할지는 프론트 몫 (필요 시 H에 account 파라미터 추가는 후속)
- G(서비스 데이터) — 별도 계획
