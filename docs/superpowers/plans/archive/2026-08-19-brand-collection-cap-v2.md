# 브랜드 수집 상한 v2(확장 스킵·커버리지 영속화·direct 면제·컷 클램프) 구현 계획

> 상태: ✅ 실행 완료 (2026-08-20)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수집 상한 v1의 정합 구멍(창 확장 no-op·direct 겹침 동결·티어 재장전 무익 딥 스윕)을 해소하고, "요청 창을 다 모았는지 / 상한에서 끊겼는지"를 영속화해 was API로 노출한다.

**Architecture:** [스펙 §7](../../specs/2026-08-19-brand-collection-post-limit-design.md) 참조. `brand_account`에 `collection_capped`/`covered_until` 2컬럼 추가(백필 시점 기록), 확장 요청은 저장 행 수 사전 체크로 스킵, direct 행은 3개 SQL(touchCrawledDepth·trackedPosts·directDuePosts)에서 상한 체계 밖으로 빼고, capped 브랜드의 일일 열거 목표 컷을 `covered_until`로 클램프한다. 전부 같은 브랜치(미머지 PR) 위 증분.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway(UTC 채번), JUnit 5 + AssertJ(fake 스텁 관용구)

## Global Constraints

- 마이그레이션은 **additive만**(ADD COLUMN, expand-contract 준수), 버전은 **UTC 타임스탬프 채번** `V$(date -u +%Y%m%d%H%M%S)__brand_account_coverage.sql` — monitoring 모듈은 `20260812170000` 초과 필수(08-12 사고), 기존 최신은 `V20260819054457`
- 커버리지 의미(스펙 §7-1): 컷 도달 = `collection_capped=true` + `covered_until=실수집 깊이(최고령 편입 taken_at)`, 완주 = `false`+`NULL`. **일일 스윕은 이 값을 쓰지(read) 않고는 갱신하지 않는다** — 기록은 백필 실행(`last_swept_on` null)일 때만
- 확장 스킵(스펙 §7-2): `count >= collection-post-limit`이면 백필 미제출, `collection_months`만 상향 + capped 마킹, `last_swept_on`/`backfill_completed_at` **리셋 안 함**
- direct 면제(스펙 §7-3): `touchCrawledDepth`·`trackedPosts`에 `AND direct_registered_at IS NULL` 추가, `directDuePosts`에서 `tag_detected_at IS NULL` 필터 제거
- 클램프(스펙 §7-4): capped 브랜드의 `enumerationCutoff` 결과를 `covered_until`보다 깊지 않게 — **백필 실행은 클램프 제외**
- 주석·커밋 한국어, prefix `feat(monitoring):`/`feat(was):`/`docs:`. 테스트는 모듈 단위(`:monitoring:test`, `:was:test`), 전체 `./gradlew test`는 마지막에만

---

### Task 1: 마이그레이션 + 저장소 메서드(coverage·count)

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_account_coverage.sql` (채번은 `date -u +%Y%m%d%H%M%S`)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (countByBrand만 — direct 면제 SQL은 Task 4)

**Interfaces:**
- Produces (Task 2·3·5가 사용):
  - `record Coverage(boolean capped, Instant coveredUntil)` (BrandRepository 내부 record)
  - `BrandRepository.coverage(long brandId)` → `Coverage`
  - `BrandRepository.updateCoverage(long brandId, boolean capped, Instant coveredUntil)`
  - `BrandRepository.raiseWindowCapped(long brandId, int months, Instant coveredUntilFallback)` → `boolean`(창이 실제로 커졌으면 true — `expandWindow`와 같은 rowcount 판정)
  - `TaggedPostRepository.countByBrand(long brandId)` → `long`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 수집 상한 v2(스펙 §7-1) — 백필 시점 창 커버리지 판정의 영속화.
-- covered_until NULL = 요청 창 전체 커버, capped=true면 covered_until이 실수집 깊이.
ALTER TABLE brand_account
    ADD COLUMN collection_capped boolean NOT NULL DEFAULT false,
    ADD COLUMN covered_until timestamptz;
```

- [ ] **Step 2: BrandRepository 메서드 3개 추가** — 기존 메서드들의 JdbcClient 관용구·javadoc 톤을 따른다.

```java
/** 창 커버리지 단면(스펙 §7-1) — 일일 스윕의 컷 클램프 입력 + was 노출 원천. */
public record Coverage(boolean capped, Instant coveredUntil) {}

public Coverage coverage(long brandId) { /* SELECT collection_capped, covered_until ... WHERE id=? */ }

/** 백필 종료 시 커버리지 기록(스펙 §7-1) — 컷=true+실수집 깊이, 완주=false+NULL. */
public void updateCoverage(long brandId, boolean capped, Instant coveredUntil) { /* UPDATE ... */ }

/**
 * 확장 스킵 경로(스펙 §7-2) — 이미 상한 도달인 브랜드의 창 상향: 수집 상태(last_swept_on·
 * backfill_completed_at)는 건드리지 않고 창·커버리지 마킹만. covered_until은 기존값 우선
 * (COALESCE) — 기존 백필이 컷 없이 완주했던 브랜드는 폴백(기존 창 컷)이 실수집 깊이 근사다.
 */
public boolean raiseWindowCapped(long brandId, int months, Instant coveredUntilFallback) {
	return db.update("""
			UPDATE brand_account
			SET collection_months = GREATEST(collection_months, ?), collection_capped = true,
			    covered_until = COALESCE(covered_until, ?)
			WHERE id = ? AND collection_months < ?""",
			months, Timestamp.from(coveredUntilFallback), brandId, months) > 0;
}
```

- [ ] **Step 3: TaggedPostRepository.countByBrand 추가**

```java
/** 브랜드 저장 행 수 — 확장 스킵 판정 입력(스펙 §7-2). */
public long countByBrand(long brandId) { /* SELECT count(*) FROM brand_tagged_post WHERE brand_id=? */ }
```

- [ ] **Step 4: 검증** — 저장소 통합 테스트 클래스가 있으면(`monitoring/src/test`에서 `BrandRepository` 테스트 grep) 거기에 케이스 추가, 없으면 컴파일 + 기존 통합 테스트가 Flyway를 재생하므로 `./gradlew :monitoring:test --tests "*Flyway*" --tests "*Migration*"` 류가 없다면 `:monitoring:compileJava` 후 Task 6의 전체 테스트에 위임. 마이그레이션 채번이 UTC 지금 시각인지, `20260819054457`보다 큰지 확인.

- [ ] **Step 5: 커밋** — `feat(monitoring): 브랜드 창 커버리지 컬럼·저장소 메서드(수집 상한 v2)`

### Task 2: BrandCollectService — 커버리지 기록 + 컷 클램프

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`
- Test: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectServiceTest.java` 아님 → `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java` (+ 생성자 변경 여파: `BrandDirectCollectServiceTest`, `BrandRegistrationServiceTest`·`BrandSweepJobTest`의 스텁 `super(...)` — v1과 동일한 6-7곳)

**Interfaces:**
- Consumes: Task 1의 `BrandRepository.coverage/updateCoverage`
- Produces: `BrandCollectService` 생성자에 `BrandRepository brands` 파라미터 추가(`authors` 뒤, `adJudge` 앞 위치). 테스트 스텁: `RecordingBrands extends BrandRepository`(super(null), coverage 반환값 설정 가능 + updateCoverage 호출 기록)

- [ ] **Step 1: 실패 테스트 3개 작성** (TDD — 생성자 변경으로 우선 컴파일 에러가 RED)

```java
@Test
void 백필이_컷으로_끝나면_커버리지를_capped로_기록한다() {
	// 3페이지·상한 3 — 2페이지째 컷. 백필(last_swept_on null) 실행이므로 종료부가 기록해야 한다.
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	tagPages.add(page("p3", reel("C", OLD_20D, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
	tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

	service(10000, 3).sweep(brand);

	assertThat(brands.coverageWrites).containsExactly("1:capped:" + Instant.ofEpochSecond(OLD_20D));
	// 실수집 깊이 = 편입분 최고령 taken_at(OLD_20D) — 목표 컷(365일)이 아니다.
}

@Test
void 백필이_완주하면_커버리지를_전체_커버로_기록한다() {
	tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

	service(10000, 2000).sweep(brand);

	assertThat(brands.coverageWrites).containsExactly("1:full");
}

@Test
void capped_브랜드의_일일_열거는_covered_until보다_깊게_열지_않는다() {
	// 60일령 due 링크가 컷을 60일로 벌리려 하지만, 커버리지가 20일이면 클램프돼 20일 컷.
	brands.coverage = new BrandRepository.Coverage(true, Instant.ofEpochSecond(OLD_20D));
	tagged.tracked.add(new TaggedPostRepository.TrackedPost("Due60d",
			Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
	tagPages.add(page("p3", reel("Old25d", NOW - 25L * 86400, 0, 102, "")));   // 20일 컷 이전 — 중단
	tagPages.add(page(null, reel("Deep", RETRO_IN_WINDOW, 0, 103, "")));

	service(10000, 2000).sweep(sweptBrand);

	assertThat(tagCalls()).isEqualTo(2);   // 클램프 없었으면 60일 컷이라 3콜
}
```

(일일 스윕은 커버리지를 **기록하지 않음**도 세 번째 테스트에서 `coverageWrites` 비어 있음으로 함께 단언. `RecordingBrands` 스텁은 `coverage` 필드 기본값 `new Coverage(false, null)`.)

- [ ] **Step 2: 구현**
  - 생성자에 `BrandRepository brands` 추가 + 필드. 전 호출부(테스트 6-7곳)에 스텁/`null` 삽입 — `BrandRegistrationServiceTest`·`BrandSweepJobTest`의 `StubCollect extends BrandCollectService`는 `super(...)`에 함께.
  - `doSweepCore`: 컷 분기에서 지역 플래그 `cappedThisRun = true`. 루프 종료 후:
    ```java
    if (brand.lastSweptOn() == null) {
    	// 백필 커버리지 기록(스펙 §7-1) — 일일 스윕은 기록하지 않는다(창 커버리지는 백필 속성).
    	brands.updateCoverage(brand.id(), cappedThisRun,
    			cappedThisRun ? oldestTakenAt(collected) : null);
    }
    ```
  - `enumerationCutoff`의 티어 경로(lastSweptOn 비-null) 끝에 클램프:
    ```java
    // 컷 클램프(스펙 §7-4) — capped 브랜드는 covered_until보다 깊게 열지 않는다: 컷 밖 행의
    // due 재장전이 열거를 상한까지 벌려도 구조적으로 도달 불가라 전부 무익 콜이었다.
    BrandRepository.Coverage cov = brands.coverage(brand.id());
    if (cov.capped() && cov.coveredUntil() != null && cov.coveredUntil().isAfter(cutoff)) {
    	cutoff = cov.coveredUntil();
    }
    ```
  - 클램프 도입으로 "컷 밖 due의 touch 반복"이 대부분 사라지므로, `sweepCore` javadoc ⑤ 문단에 한 줄 보강(동결 = 범위 제외로 수렴).

- [ ] **Step 3: 테스트** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest" --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest" --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest" --tests "com.celfit.monitoring.service.BrandSweepJobTest"` 전부 PASS.

- [ ] **Step 4: 커밋** — `feat(monitoring): 백필 커버리지 기록·capped 컷 클램프(수집 상한 v2)`

### Task 3: BrandRegistrationService — 확장 스킵

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (`expandIfRequested`, ~L163)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `TaggedPostRepository.countByBrand`, `BrandRepository.raiseWindowCapped`, `@Value("${monitoring.brand.collection-post-limit:2000}") int collectionPostLimit`(생성자 주입 추가 — BrandCollectService와 같은 키)

- [ ] **Step 1: 실패 테스트 2개** (기존 확장 테스트 관용구를 파일에서 확인 후 그 스타일로)
  - `이미_상한_도달인_브랜드의_확장은_백필_없이_창만_올린다`: 저장 행 수 스텁 ≥ limit → 확장 요청 시 backfill 미제출(기존 테스트의 백필 제출 관측 수단 재사용), `raiseWindowCapped` 호출 기록(months, 폴백 = now−기존 창), `expandWindow` 미호출.
  - `상한_미달_브랜드의_확장은_기존_경로다`: count < limit → 기존 `expandWindow` + 백필 제출 그대로(기존 테스트 회귀 확인 수준).

- [ ] **Step 2: 구현**

```java
private void expandIfRequested(BrandRow existing, int months) {
	if (months <= existing.collectionMonths()) {
		return;
	}
	// 확장 스킵(스펙 §7-2) — 이미 상한 도달이면 재백필이 기지 게시물만 세다 컷될 것이 확정이라
	// 열거를 시작하지 않는다(~96콜 절약). 창·커버리지 마킹만 하고 수집 상태는 불변 — UI가
	// capped·covered_until로 "확장 신청·상한 도달"을 표시한다.
	if (collectionPostLimit > 0 && taggedPosts.countByBrand(existing.id()) >= collectionPostLimit) {
		Instant fallback = ZonedDateTime.now(KST).minusMonths(existing.collectionMonths()).toInstant();
		brands.raiseWindowCapped(existing.id(), months, fallback);
		return;
	}
	if (!brands.expandWindow(existing.id(), months)) {
		return;
	}
	BrandRow row = brands.findByUsername(existing.username()).orElseThrow();
	backfill.execute(() -> runBackfillSafely(row));
}
```

(`taggedPosts`가 이 서비스에 이미 주입돼 있는지 확인 — 없으면 주입 추가. `KST` 상수는 클래스에 이미 있는지 확인 후 재사용/추가.)

- [ ] **Step 3: 테스트** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"` PASS.
- [ ] **Step 4: 커밋** — `feat(monitoring): 상한 도달 브랜드의 창 확장은 백필 없이 마킹만(수집 상한 v2)`

### Task 4: direct 상한 면제 (SQL 3곳 + javadoc 정합)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (`touchCrawledDepth` ~L189, `trackedPosts` ~L147, `directDuePosts` ~L164 + 각 javadoc)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`·`BrandCollectServiceTest.java` (fake가 SQL 필터를 미러하면 fake도 갱신)

**Interfaces:**
- Consumes: 없음 (독립 SQL 변경)

- [ ] **Step 1: SQL 변경**
  - `touchCrawledDepth`: `WHERE ... AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL`
  - `trackedPosts`: `WHERE ... AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL`
  - `directDuePosts`: `WHERE brand_id = ? AND direct_registered_at IS NOT NULL AND taken_at >= ?` (`tag_detected_at IS NULL` 제거)

- [ ] **Step 2: javadoc 3곳 재작성** — 스펙 §7-3의 논리로: 겹침 행 중복 콜 방지는 "1단계가 실제로 만나면 touchCrawled → due 아님"으로 구조 유지, 컷 밖 겹침 행만 2단계 단건 수집. `touchCrawledDepth` javadoc의 "호출은 자연 종료했을 때만" 문장을 v1 이후 현실(수집 상한 컷도 커버 처리로 호출 — 의도된 동결)에 맞게 정정(직전 리뷰 지적 사항).

- [ ] **Step 3: 서비스 레벨 테스트** — fake `InMemoryTagged`가 위 필터를 미러하도록 갱신하고(TrackedPost에 direct 여부가 없으면 fake 시드 구조 확장), 다음을 단언:
  - `BrandCollectServiceTest`: 컷 도달 시 depth-touch가 direct 행을 건드리지 않는다(추적 direct 행의 touched 미포함).
  - `BrandDirectCollectServiceTest`: 겹침 행(tag_detected_at·direct 둘 다)이 due면 2단계 모수에 들어간다 / 1단계가 방금 touchCrawled한 겹침 행은 안 들어간다. 기존 테스트 관용구(스텁·due 판정)를 먼저 읽고 맞출 것.

- [ ] **Step 4: 테스트** — `./gradlew :monitoring:test --tests "*BrandDirectCollect*" --tests "*BrandCollectService*"` PASS.
- [ ] **Step 5: 커밋** — `feat(monitoring): direct 등록 게시물을 수집 상한 밖으로(겹침 동결 해소)`

### Task 5: was API 커버리지 노출

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java` (+`collectionCapped`, `coveredUntil` 필드)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java` (+ 그 데이터를 읽는 리포지토리 쿼리 — 파일에서 brand_account SELECT 지점을 찾아 두 컬럼 추가)
- Test: was의 기존 브랜드 계정 테스트 파일(grep으로 확인, `V1BrandAccountsController`/assembler 테스트)

**Interfaces:**
- Consumes: Task 1의 컬럼 2개 (was_reader 테이블 그랜트에 자동 포함)
- Produces: API 응답 신규 필드 `collectionCapped: boolean`, `coveredUntil: string|null(ISO)` — FE가 "N개월 신청 · YYYY-MM-DD까지 수집(상한 도달)" 표시용

- [ ] **Step 1: 기존 응답 record·쿼리·테스트 관용구 확인 후 실패 테스트 추가**(응답 필드 매핑 단언)
- [ ] **Step 2: 쿼리·record·어셈블러에 두 필드 추가** (record는 정적 `from()` 관용구 유지)
- [ ] **Step 3: 테스트** — `./gradlew :was:test --tests "*BrandAccount*"` PASS. (was 테스트는 Testcontainers monitoring DB에 Flyway를 재생하는지 확인 — 마이그레이션이 was 테스트 픽스처에 별도 DDL로 미러돼 있으면 그곳에도 컬럼 추가)
- [ ] **Step 4: 커밋** — `feat(was): 브랜드 수집 커버리지(capped·covered_until) 응답 노출`

### Task 6: 문서 정리 + 전체 테스트

**Files:**
- Modify: `DECISIONS.md` (맨 위 v2 결정 1행 + 기존 08-19 행의 "안전 밸브 독립 유지" 문구를 "기본 설정 도달 불가(vestigial)"로 정정 — 리뷰 지적)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (08-19 문단에 v2 반영: 알려진 여파 ②(direct 동결) 해소 표기, 커버리지 컬럼·확장 스킵·클램프 추가, 미결 항목 정리)
- Modify: `docs/superpowers/plans/archive/2026-08-19-brand-collection-post-limit.md` (상태 헤더 `> 상태: ✅ 실행 완료 (2026-08-19)` 1행 추가 — 리뷰 지적)
- Move: 이 plan 문서도 완료 시 `plans/archive/`로 + 상태 헤더

- [ ] **Step 1: 문서 3개 수정 + plan 아카이브**
- [ ] **Step 2: 전체 테스트** — `./gradlew :monitoring:test :was:test` PASS (Docker Desktop 기동 확인, `DOCKER_HOST` 미설정 유지)
- [ ] **Step 3: 커밋** — `docs: 수집 상한 v2 결정·트랙 기록 및 plan 아카이브`
