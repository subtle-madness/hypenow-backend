# 브랜드 direct 게시물 삭제 감지 → hidden 노출 구현 계획

> 상태: 🟢 활성
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 야간 스윕이 direct 게시물 404를 만나면 `brand_tagged_post.unavailable_at`에 영속화하고, was가 그 행을 `trackingStatus: "hidden"`으로 내려 FE의 기존 "삭제·비공개" 칩이 작동하게 한다.

**Architecture:** monitoring이 감지(404 → 마킹)·해제(재관측 → NULL)를 쓰고, was는 읽어서 조립 시 상태만 바꾼다. 계약(FE) 변경 없음 — `TrackingStatus` 어휘에 `hidden`이 이미 있다. 스펙: [2026-08-25-brand-post-deletion-hidden-design.md](../../specs/2026-08-25-brand-post-deletion-hidden-design.md)

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway(monitoring DB), JdbcTemplate(monitoring)/JdbcClient(was), JUnit + AssertJ(DB 없는 스텁 관용구)

## Global Constraints

- 신규 마이그레이션은 UTC 타임스탬프 채번, monitoring 공간은 `20260812170000` 초과 필수(현재 최대 `V20260824015923`).
- expand-contract: 이번 변경은 ADD COLUMN만(expand). DROP·RENAME 금지.
- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(모듈):`.
- 테스트는 모듈 단위: `./gradlew :monitoring:test`, `./gradlew :was:test`. 이 머신은 Docker Desktop 정본 — `DOCKER_HOST` 설정하지 말 것(08-09 확인, CLAUDE.md의 colima 항목은 이 머신엔 해당 없음).
- 기존 스펙 문서(2026-08-18 direct 통합 등)는 내용 불변 — "상태 전이 없음" 서술의 변경은 코드 주석과 DECISIONS.md로만 기록.

---

### Task 1: monitoring — 마이그레이션 + TaggedPostRepository

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260825044536__brand_tagged_post_unavailable_at.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (`touchCrawled` 252행 부근, 신규 메서드 추가)

**Interfaces:**
- Produces: `TaggedPostRepository.markUnavailable(long brandId, String shortCode, Instant at)` — Task 2가 호출. `touchCrawled(long brandId, Collection<String> codes, Instant at)`는 시그니처 불변, SET 절에 해제 추가.

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 브랜드 direct 게시물 삭제·비공개 관측(2026-08-25 설계) — 야간 스윕 단건 콜의 404를 영속화한다.
-- NULL = 정상, 값 = 마지막 단건 조회가 404를 받은 시각. 재관측(touchCrawled)이 NULL로 되돌린다.
-- tagged-only 행은 단건 콜 자체가 없어 항상 NULL이다(감지 대상 외 — 설계 §범위).
ALTER TABLE brand_tagged_post ADD COLUMN unavailable_at timestamptz;
```

- [ ] **Step 2: TaggedPostRepository에 markUnavailable 추가**

`touchCrawled` 메서드 아래에:

```java
/**
 * 삭제·비공개 관측 마킹(2026-08-25 설계) — 야간 스윕 단건 콜이 404(SubjectNotFound)를 받은
 * 게시물에 찍는다. 행·스냅샷은 보존(was가 hidden으로 노출), 재관측({@link #touchCrawled})이
 * 해제하는 자가 치유 짝이다. 재마킹은 무해하다(시각만 갱신).
 */
public void markUnavailable(long brandId, String shortCode, Instant at) {
	db.update("UPDATE brand_tagged_post SET unavailable_at = ? WHERE brand_id = ? AND short_code = ?",
			Timestamp.from(at), brandId, shortCode);
}
```

- [ ] **Step 3: touchCrawled에 해제 추가**

기존:

```java
db.update("UPDATE brand_tagged_post SET last_crawled_at = ? WHERE brand_id = ? AND short_code IN ("
		+ placeholders + ")", args);
```

변경(주석도 함께 — 기존 javadoc `/** 이번 열거에서 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정 입력. */`을 아래로 교체):

```java
/**
 * 이번 열거·실수집에서 실제로 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정
 * 입력. 직접 관측 = 존재 확인이므로 삭제·비공개 마킹({@link #markUnavailable})도 여기서 해제한다
 * (IG 보관 후 재공개 자가 치유). 깊이 touch({@link #touchCrawledDepth})는 개별 관측이 아니라
 * 해제하지 않는다.
 */
```

```java
db.update("UPDATE brand_tagged_post SET last_crawled_at = ?, unavailable_at = NULL"
		+ " WHERE brand_id = ? AND short_code IN (" + placeholders + ")", args);
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :monitoring:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/resources/db/migration/V20260825044536__brand_tagged_post_unavailable_at.sql monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java
git commit -m "feat(monitoring): brand_tagged_post.unavailable_at 컬럼·마킹/해제 리포지토리 추가"
```

---

### Task 2: monitoring — 스윕 404 시 마킹 (TDD)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java` (`collectOne` 125행 부근)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`

**Interfaces:**
- Consumes: `TaggedPostRepository.markUnavailable(long, String, Instant)` (Task 1)
- Produces: 스윕 2단계에서 404 게시물이 `unavailable_at` 마킹됨 — Task 3의 was 읽기가 의존하는 데이터 계약.

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandDirectCollectServiceTest`의 `InMemoryTagged` 스텁(140행 부근)에 기록 필드·오버라이드 추가:

```java
final List<String> unavailable = new ArrayList<>();
```

```java
@Override
public void markUnavailable(long brandId, String shortCode, Instant at) {
	unavailable.add(shortCode);
}
```

테스트 추가(기존 `_14일_이내_direct_행은_매일_due다` 관용구 승계):

```java
@Test
void 스윕_404_게시물은_unavailable_마킹되고_나머지는_계속_수집된다() {
	tagged.due.add(new TaggedPostRepository.TrackedPost("Gone", Instant.ofEpochSecond(RECENT),
			Instant.now().minusSeconds(86400)));
	tagged.due.add(new TaggedPostRepository.TrackedPost("Alive", Instant.ofEpochSecond(RECENT),
			Instant.now().minusSeconds(86400)));
	notFoundCodes.add("Gone");
	postResponses.put("Alive", postJson("Alive", RECENT, 105));

	service().sweepDirect(brand);

	// 404 게시물: 마킹만, 저장·touch 없음(마지막 수집값 보존)
	assertThat(tagged.unavailable).containsExactly("Gone");
	assertThat(tagged.touched).doesNotContainKey("Gone");
	// 격리 유지: 나머지 게시물은 정상 수집되고 touch(관측=해제 경로)를 지난다
	assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("Alive");
	assertThat(tagged.touched).containsKey("Alive");
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: FAIL — `tagged.unavailable`이 비어 있음(마킹 미구현)

- [ ] **Step 3: collectOne 구현**

`BrandDirectCollectService.collectOne`의 catch(136행 부근) 기존:

```java
} catch (SubjectNotFoundException e) {
	log.info("direct 게시물 부재/비공개 전환(격리, 상태 전이 없음) — {}: {}", shortCode, e.toString());
}
```

변경:

```java
} catch (SubjectNotFoundException e) {
	log.info("direct 게시물 부재/비공개 — unavailable 마킹: {} ({})", shortCode, e.toString());
	taggedPosts.markUnavailable(brand.id(), shortCode, now);
}
```

`collectOne` javadoc(119행 부근 `삭제·비공개 전환(...)에도 행을 지우지 않는다: 브랜드 파이프라인은 상태 전이를 하지 않는다` 문단)을 아래로 교체:

```java
/**
 * 게시물 1건 격리 수집 — 삭제·비공개 전환({@link SubjectNotFoundException})에도 행을 지우지
 * 않는다. 대신 unavailable_at을 마킹해 was가 hidden으로 노출한다(2026-08-25 설계 — 스펙 §8의
 * "상태 전이 없음"에 대한 유일한 예외이며, 성공 재관측이 해제하는 가역 마킹이라 CLOSED 같은
 * 종결 전이가 아니다). 카드는 마지막 스냅샷으로 남는다. 그 외 실패(타임아웃·5xx·셰이프 이상)는
 * 이 게시물만 건너뛰고 나머지는 계속 — 한 건의 실패가 배치 전체를 죽이면 안 된다.
 */
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: PASS (기존 테스트 포함 전부)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
git commit -m "feat(monitoring): 야간 스윕 direct 게시물 404를 unavailable_at으로 영속화"
```

---

### Task 3: was — hidden 노출 (TDD)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (`findBrandPostsInWindow` 81행 부근 SELECT, `BrandTaggedPostRow` record 350행 부근)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` (상수 79행, 조립 403행 부근)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`

**Interfaces:**
- Consumes: `brand_tagged_post.unavailable_at` 컬럼(Task 1)
- Produces: `BrandTaggedPostRow`에 마지막 컴포넌트 `OffsetDateTime unavailableAt` 추가(기존 9개 → 10개). `BrandPostResponse.trackingStatus()`가 `"hidden"` 가능 — Task 4가 의존.

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandPostAssemblerTest`에 추가. 주의: `BrandTaggedPostRow` 생성자가 10개 인자가 되므로 이 테스트는 컴파일을 위해 Step 3과 함께 진행하되, **먼저 테스트를 작성해 두고** 구현 전 실행으로 실패(컴파일 에러)를 확인한다.

```java
@Test
void unavailable_마킹된_행은_hidden으로_내려간다() {
	var row = new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
			OffsetDateTime.parse("2026-08-06T00:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
			7L, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"),
			OffsetDateTime.parse("2026-08-20T18:00:00Z"));

	var post = assembleSingle(row);

	assertThat(post.trackingStatus()).isEqualTo("hidden");
}

@Test
void unavailable_null이면_기존대로_tracking이다() {
	var post = assembleSingle(row("ABC", "2026-08-06T01:00:00Z", "2026-08-06T00:00:00Z", null));

	assertThat(post.trackingStatus()).isEqualTo("tracking");
}
```

`assembleSingle`은 이 테스트 파일의 기존 조립 헬퍼를 쓴다(파일 내 기존 테스트가 row를 응답으로 만드는 경로를 그대로 따를 것 — 헬퍼 이름이 다르면 그 이름을 사용). `row(...)` 헬퍼(909행)는 Step 3에서 10번째 인자 `null`을 받도록 고친다.

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :was:compileTestJava`
Expected: FAIL — `BrandTaggedPostRow` 생성자 인자 수 불일치

- [ ] **Step 3: 구현**

3-1. `BrandReadRepository.findBrandPostsInWindow` SELECT에 컬럼 추가:

```sql
SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at,
       unavailable_at
```

3-2. `BrandTaggedPostRow` record 마지막에 컴포넌트 추가 + javadoc에 한 줄 추가:

```java
public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
		OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount,
		OffsetDateTime lastCrawledAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt,
		OffsetDateTime unavailableAt) {
}
```

javadoc 추가 문장: `{@code unavailableAt}(야간 스윕 단건 콜이 404를 받은 시각, null이면 정상 — 값이 있으면 trackingStatus가 hidden으로 내려간다, 2026-08-25 설계).`

3-3. `BrandPostAssembler` 79행 상수 옆에 추가:

```java
private static final String HIDDEN = "hidden";
```

403행 `TRACKING,` → 아래로 교체(직전 주석 포함):

```java
// 야간 스윕이 삭제·비공개(404)를 관측한 행은 hidden(2026-08-25 설계) — FE 칩("삭제·비공개")의
// 유일한 트리거. tagged-only 행은 단건 콜이 없어 항상 null이라 기존대로 tracking이다.
post.unavailableAt() != null ? HIDDEN : TRACKING,
```

3-4. `BrandTaggedPostRow` 생성자를 쓰는 모든 위치에 10번째 인자 보정 — 다음으로 전수 확인:

```bash
grep -rn "BrandTaggedPostRow(" --include='*.java' was/src crawler/src analytics/src monitoring/src | grep -v "record BrandTaggedPostRow"
```

테스트 헬퍼 `row()`(909행)는 마지막 인자에 `null` 추가, 그 외 명시 생성 위치(BrandPostAssemblerTest 361·366·593·612·646행 등 grep 결과 전부)도 `null` 추가.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"`
Expected: PASS (신규 2건 포함 전부)

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java
git commit -m "feat(was): unavailable_at 마킹된 브랜드 게시물을 trackingStatus hidden으로 노출"
```

---

### Task 4: was — 성과 대시보드 상태 전파 (TDD)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` (`fromBrandPost` 255행 부근, `STATUS_TRACKING` 상수 80행)
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` (STATUSES javadoc 44행 부근)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Consumes: `BrandPostResponse.trackingStatus()`가 `"hidden"` 가능(Task 3)
- Produces: 성과 대시보드 합성 아이템의 `status`가 브랜드 풀 관측을 따름. `statusCounts`는 기존 `STATUSES`에 `hidden`이 이미 있어 추가 변경 없음.

- [ ] **Step 1: 실패하는 테스트 작성**

`PerformanceContentAssemblerTest`의 `brandPost` 헬퍼(620행 부근)가 `trackingStatus`를 리터럴로 넣고 있다면, status를 받는 오버로드를 추가한다(기존 7-인자 헬퍼는 `"tracking"`을 넘겨 위임 — 기존 테스트 무수정):

```java
private static BrandPostResponse directPostHidden(String shortcode) {
	return brandPost(shortcode, "direct", List.of(), "브랜드 태그 캡션", false, BRAND_ID, List.of(), "hidden");
}
```

(기존 `brandPost(...)`에 `String trackingStatus` 마지막 파라미터를 추가하고, `BrandPostResponse` 생성부의 `trackingStatus` 자리 리터럴을 그 파라미터로 교체. 기존 호출 7곳은 `"tracking"`을 덧붙인다.)

테스트 추가(기존 `브랜드_풀_전용은_bt_접두_합성_아이템이다` 관용구 승계):

```java
@Test
void 브랜드_풀_hidden_게시물은_합성_아이템도_hidden이다() {
	givenLegacy();
	givenBrand(directPostHidden("ABC"));

	var item = assembler().assemble(USER_ID).contents().get(0).item();

	assertThat(item.status()).isEqualTo("hidden");
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"`
Expected: FAIL — `item.status()`가 `"tracking"`(하드코딩)

- [ ] **Step 3: 구현**

`fromBrandPost` 255행 `STATUS_TRACKING` → `post.trackingStatus()`:

```java
new PerformanceItemResponse(SYNTHETIC_ID_PREFIX + post.shortcode(), MODE_URL, post.trackingStatus(),
```

`STATUS_TRACKING` 상수(80행)는 다른 사용처가 없으면 삭제(grep으로 확인). `fromBrandPost` javadoc에 한 줄 추가: `상태는 {@link BrandPostResponse#trackingStatus()}를 그대로 승계한다 — 삭제·비공개 감지(hidden, 2026-08-25 설계)가 대시보드에도 반영된다.`

`V1PerformanceDashboardController` 44행 javadoc의 `tagged-only 합성 아이템은 항상 {@code tracking}이라 값 공간이 늘지 않는다.` 문장을 `브랜드 풀 합성 아이템도 hidden이 가능하다(2026-08-25 삭제 감지) — 어휘는 여전히 이 목록 안이다.`로 교체.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"`
Expected: PASS (기존 포함 전부)

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java
git commit -m "feat(was): 성과 대시보드 합성 아이템 status를 브랜드 풀 관측(hidden 포함)으로 승계"
```

---

### Task 5: 모듈 테스트·문서·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/plans/2026-08-25-brand-post-deletion-hidden.md` → `docs/superpowers/plans/archive/`로 이동(PR에 포함)
- 확인만: `docs/contracts/monitoring-was-contract.md` — brand_tagged_post 컬럼 목록이 있으면 `unavailable_at` 추가, 없으면 무수정.

- [ ] **Step 1: 모듈 테스트 전체 실행**

Run: `./gradlew :monitoring:test :was:test`
Expected: BUILD SUCCESSFUL (Docker Desktop 기동 필요 — Testcontainers. `DOCKER_HOST`는 설정하지 않는다)

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 기록**

```markdown
## 2026-08-25 브랜드 direct 게시물 삭제 감지 — unavailable_at 가역 마킹으로 hidden 노출

야간 스윕 단건 콜의 404를 `brand_tagged_post.unavailable_at`에 영속화하고 was가 그 행을
`trackingStatus: "hidden"`으로 노출한다(FE 칩은 이미 구현돼 있었음 — 계약 6.25). 스펙 §8
"브랜드 파이프라인은 상태 전이를 하지 않는다"의 유일한 예외지만, 재관측(touchCrawled)이
해제하는 가역 마킹이라 종결 전이가 아니다. tagged-only 행은 단건 콜이 없고(열거 부재는 태그
해제와 구분 불가·검증 콜은 404도 과금) 감지 대상 외 — 항상 tracking.
[설계](../../specs/2026-08-25-brand-post-deletion-hidden-design.md)
```

- [ ] **Step 3: plan 아카이브 + 커밋**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-08-25-brand-post-deletion-hidden.md docs/superpowers/plans/archive/
git add DECISIONS.md
git commit -m "docs: 브랜드 삭제 감지 결정 기록·plan 아카이브"
```

- [ ] **Step 4: PR 생성 (develop 대상)**

```bash
git push -u origin feature/brand-post-deletion-detection-7b1d19
gh pr create --base develop --title "feat: 브랜드 direct 게시물 삭제 감지 → hidden 노출" --body "..."
```

PR 본문에 포함: 배경(FE 칩 대기 상태), 범위 결정(direct만·근거), 데이터 흐름(404 → unavailable_at → hidden), 롤링 안전성(expand-only ADD COLUMN), 테스트 결과. 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
