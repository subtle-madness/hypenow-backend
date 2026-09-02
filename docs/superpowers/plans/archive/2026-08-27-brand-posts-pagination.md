# 브랜드 게시물 목록 타임아웃 해소 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/brand-monitoring/accounts/{id}/posts`를 "경량 인덱스 패스(counts·정렬·슬라이스) + 페이지 코드만 풀 조립" 구조로 재편 — 목록 댓글 제외(P0), offset/limit 페이지네이션(P1), counts·total의 전량 풀 조립 제거.

**Architecture:** [설계 스펙](../../specs/2026-08-27-brand-posts-pagination-design.md) 참조. `BrandPostAssembler`에 `indexForBrand`(경량 PostRef 목록)와 `hydrate`(지정 shortcode만 풀 카드 조립)를 신설, 컨트롤러는 ref 위에서 counts·필터·정렬·페이지를 계산한 뒤 페이지 코드만 하이드레이트한다. 상세도 같은 기계를 탄다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, `@WebMvcTest`(실 어셈블러 + repo mock), Testcontainers 통합 테스트(`BrandReadRepositoryTest`).

## Global Constraints

- 파라미터(`limit`·`offset`) 둘 다 생략 시 응답은 기존과 동일해야 한다(단, `recentComments: []`·`commentsCollectedCount: 0`은 의도된 P0 변경).
- `meta.limit`(수집 상한 2000)·`meta.counts`(필터 전 전량)·`meta.total`(필터 후 전체) 의미 유지. 신설 `meta.page = {offset, limit}`는 항상 포함(전량이면 `{0, null}`).
- `limit` 1..100·`offset` ≥0, 위반 시 400 (`V1ApiException.validation`).
- 정렬 comparator·창 판정·협찬 판정·source 파생은 기존 코드와 같은 함수를 재사용해 counts가 전량 계산과 정의상 일치해야 한다.
- 상세(`GET /posts/{postId}`)는 `recentComments` 포함 유지.
- 커밋 prefix `feat(was):`/`test(was):`, 주석·커밋 한국어.
- 테스트는 모듈 단위 `./gradlew :was:test --tests "..."`. DOCKER_HOST는 이 머신에선 미설정이 정답(Docker Desktop).

---

### Task 1: BrandReadRepository 경량 프로젝션 2종

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java`

**Interfaces (Produces):**
```java
public record SponsorshipMetaRow(String shortCode, Boolean isPaidPartnership, String caption) {}
public List<SponsorshipMetaRow> findSponsorshipMeta(Collection<String> shortCodes)
public record LatestViewsRow(String shortCode, String contentType, Long views) {}
public List<LatestViewsRow> findLatestViews(Collection<String> shortCodes)
```

- [x] **Step 1: 실패하는 통합 테스트 작성** — `BrandReadRepositoryTest`에 추가(기존 시드 헬퍼 재사용):

```java
@Test
void 협찬_판정_프로젝션은_캡션과_유료협찬만_읽는다() {
    long brandId = seedBrand("brand");
    seedTaggedPost(brandId, "SC1", "2026-08-01T12:00:00+09:00");
    // 기존 메타 시드 관용구로 SC1 메타(caption='#협찬 후기', is_paid_partnership=null) 삽입
    List<BrandReadRepository.SponsorshipMetaRow> rows = repository.findSponsorshipMeta(Set.of("SC1", "NOPE"));
    assertThat(rows).extracting(BrandReadRepository.SponsorshipMetaRow::shortCode).containsExactly("SC1");
    assertThat(rows.get(0).caption()).isEqualTo("#협찬 후기");
    assertThat(repository.findSponsorshipMeta(Set.of())).isEmpty();
}

@Test
void 최신_스냅샷_프로젝션은_게시물당_마지막_1행이다() {
    // SC1: 08-01 views=10, 08-02 views=20(REELS) / SC2: 08-01 views=null(FEED)
    List<BrandReadRepository.LatestViewsRow> rows = repository.findLatestViews(Set.of("SC1", "SC2"));
    // SC1은 08-02 행(views=20), SC2는 1행 — DISTINCT ON (short_code) ... captured_on DESC
}
```

- [x] **Step 2: 실행해 실패 확인** — `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"` → 컴파일 에러(메서드 부재) 확인.
- [x] **Step 3: 구현** — `findSponsorshipMeta`: `SELECT short_code, is_paid_partnership, caption FROM brand_post_meta WHERE short_code IN (:shortCodes)`. `findLatestViews`: `SELECT DISTINCT ON (short_code) short_code, content_type, views FROM brand_post_snapshot WHERE short_code IN (:shortCodes) ORDER BY short_code, captured_on DESC`. 빈 컬렉션 선처리(`IN ()` SQL 오류) 관용구 동일 적용.
- [x] **Step 4: 테스트 그린 확인** — 같은 명령 PASS.
- [x] **Step 5: 커밋** — `feat(was): 브랜드 게시물 협찬·최신뷰 경량 프로젝션 조회 추가`

### Task 2: 어셈블러 index/hydrate 분리 + BrandPostResponse 댓글 스트립

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`

**Interfaces (Produces):**
```java
// BrandPostResponse
public BrandPostResponse withoutRecentComments()   // recentComments=List.of(), commentsCollectedCount=0, 나머지 불변

// BrandPostAssembler
public record PostRef(String shortcode, String source, String sponsorship, LocalDate uploadedOn, Long latestViews) {}
public record BrandPostIndex(List<PostRef> refs, Map<String, BrandTaggedPostRow> poolRowsByCode,
        Map<String, BrandPostResponse> legacyByCode, Set<String> ownedShortCodes) {}
public BrandPostIndex indexForBrand(long userId, BrandAccountRow account, String viewerAccountType, boolean withViews)
public List<BrandPostResponse> hydrate(long userId, BrandAccountRow account, String viewerAccountType,
        BrandPostIndex index, List<String> codes, boolean withComments)
```

- [x] **Step 1: 실패하는 단위 테스트** — 정적/순수 부분(`withoutRecentComments`, ref 파생 규칙)과 mock 기반 index/hydrate 동작(`BrandPostAssemblerTest` 기존 관용구):
  - `withoutRecentComments는_댓글만_비운다`: 풀 카드 → 호출 → recentComments 빈 목록·collectedCount 0·다른 필드 동일.
  - `index는_스냅샷_댓글_게시자_조회_없이_ref를_만든다`: findBrandPostsInWindow·findSponsorshipMeta만 stub, `then(brandReadRepository).should(never()).findSnapshots(any)` / `findComments` / `findAuthors` 검증. ref의 source(겹침 행 + 등록자/타인)·sponsorship(classify 동일 입력)·uploadedOn(KST 날짜) 검증.
  - `index는_performance용_최신뷰를_피드면_null로_접는다`: findLatestViews stub(REELS views=20, FEED views=5) → ref.latestViews가 REELS 20·FEED null.
  - `hydrate는_지정_코드만_조립하고_입력_순서를_지킨다`: 3건 중 2건 지정 → findPostMeta 등 배치 조회 인자가 그 2건 코드만, 결과 순서 = 입력 codes 순서.
  - `hydrate는_withComments_false면_댓글을_비운다`(레거시 폴백 카드 포함).
- [x] **Step 2: 실행해 실패 확인**.
- [x] **Step 3: 구현** —
  - `indexForBrand`: `findBrandPostsInWindow(ENRICHED_ONLY)` → ownedShortCodes(direct 행 있을 때만) → `filterVisibleToUser` → `findSponsorshipMeta` → (withViews면) `findLatestViews`(REELS 아니면 views null — `snapshotOf` 피드 규칙 동형) → `assembleLegacyPending`(기존) 카드로 legacy ref(source·sponsorship·uploadedOn·latestSnapshot views는 카드 필드 그대로) — 풀 코드와 겹치면 제외(풀 우선 병합 불변). ref 순서는 정의하지 않는다(정렬은 호출부).
  - `hydrate`: codes를 풀/레거시로 나눠 풀 코드만 meta·스냅샷·(withComments면) 댓글·게시자·캠페인·시딩 배치 조회 후 `brandPost` 조립, 레거시는 `legacyByCode` 카드 재사용. `!withComments`면 양쪽 다 `withoutRecentComments()`. 반환은 codes 순서.
  - 기존 `assembleForBrand`는 삭제하지 않고 index+hydrate 조합으로 재구현(전 코드 하이드레이트)하거나, 호출부가 사라지면 제거 — Task 3·4에서 결정.
- [x] **Step 4: 테스트 그린 확인** — `--tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"`.
- [x] **Step 5: 커밋** — `feat(was): 브랜드 게시물 조립을 경량 인덱스/하이드레이트로 분리`

### Task 3: 목록 컨트롤러 — P0 댓글 제외 + 페이지네이션 + ref 기반 counts

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

**Interfaces (Consumes):** Task 2의 `indexForBrand`/`hydrate`/`PostRef`.

- [x] **Step 1: 실패하는 테스트** — 기존 스타일(mock repo + 실 어셈블러)로 추가·수정:
  - `목록은_댓글을_내려주지_않는다`: 댓글 시드 있어도 `recentComments`가 `[]`·`commentsCollectedCount` 0, `commentsTotal`(스냅샷 유래)은 유지, `findComments`는 호출되지 않는다. (기존 `목록은_tagged_게시물의_지표와_댓글을_함께_내려준다`를 이 계약으로 개정)
  - `limit_offset은_정렬된_전량의_슬라이스다`: 3건 시드, `limit=2` → 앞 2건, `offset=2&limit=2` → 마지막 1건, 두 페이지 합집합 = 전량·중복 없음.
  - `페이지네이션은_meta_page를_노출하고_total_counts는_전체_기준이다`: `limit=1`이어도 `meta.total`=필터 후 전체, `meta.counts.all`=전량, `meta.page.offset/limit` 반영, `meta.limit`은 여전히 2000.
  - `파라미터_생략_시_meta_page는_전량_표식이다`: `meta.page.offset` 0, `meta.page.limit` null, data 전량.
  - `limit_범위_밖은_400이다`: `limit=0`·`limit=101`·`offset=-1` 각각 400.
  - `offset만_주면_limit은_기본_100이다`.
  - 기존 협찬/counts 테스트의 meta stub에 `findSponsorshipMeta` 스텁 추가(캡션·유료협찬 동일 값) — counts 산지가 ref로 바뀌므로.
- [x] **Step 2: 실행해 실패 확인**.
- [x] **Step 3: 구현** — `list`에 `limit`·`offset` 파라미터 추가, 검증(1..100·≥0), `indexForBrand`(withViews = performance 정렬일 때) → 링크 창 필터(ref 기반 `withinLinkWindow` — source·uploadedOn으로 판정, 함수 시그니처를 ref로 일반화) → counts(ref) → 필터·정렬(ref comparator: uploadedOn nullsLast 역순 + shortcode / latestViews nullsLast 역순 + 위) → `POST_LIMIT` 캡 → total → 슬라이스 → `hydrate(withComments=false)`. `meta()`에 `page` 추가. 클래스 javadoc의 "필터·정렬은 전부 메모리·전량 조립" 서술 갱신.
- [x] **Step 4: 테스트 그린** — `--tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"`.
- [x] **Step 5: 커밋** — `feat(was): 브랜드 게시물 목록 페이지네이션·댓글 제외·경량 counts`

### Task 4: 상세 엔드포인트를 index+hydrate로 전환

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java`(get)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`(assembleForBrand 제거 여부)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

- [x] **Step 1: 실패하는 테스트** — `상세는_댓글을_포함하고_전량_조립을_하지_않는다`: 상세 조회 시 `recentComments` 포함 + `findPostMeta`/`findSnapshots` 인자가 해당 shortcode 1건만(전량 코드 아님) 검증. 기존 상세 테스트(`링크_창_밖_게시물_상세는_404다` 등)는 그대로 그린이어야 한다.
- [x] **Step 2: 실행해 실패 확인**.
- [x] **Step 3: 구현** — `get`: 브랜드 링크 순회마다 `indexForBrand(withViews=false)` → ref에서 shortcode 일치 + 링크 창 판정 → 있으면 `hydrate(그 1건, withComments=true)`. `assembleForBrand` 호출부가 사라지면 메서드 제거(주석의 진입점 서술 갱신).
- [x] **Step 4: 테스트 그린**.
- [x] **Step 5: 커밋** — `feat(was): 브랜드 게시물 상세도 인덱스 경로로 — 단건만 조립`

### Task 5: 모듈 전체 검증·문서·PR

- [x] **Step 1:** `./gradlew :was:test` 전체 그린 확인(회귀 — perfdashboard·campaign 등 assembleBrandPosts 소비자 포함).
- [x] **Step 2:** `DECISIONS.md` 맨 위에 결정 1행 추가(버킷 테이블 비채택 사유 요약 + 스펙 링크). 이 plan 문서를 `plans/archive/`로 이동.
- [x] **Step 3:** 커밋 후 develop 대상 PR 생성(`gh pr create`) — 본문에 FE 협의 포인트 명시: `meta.page` additive 선택 이유, `recentComments: []`(키 유지) 의미, `commentsCollectedCount` 0 됨, 요청 3을 읽기 경로 재구성으로 충족한 사유.
