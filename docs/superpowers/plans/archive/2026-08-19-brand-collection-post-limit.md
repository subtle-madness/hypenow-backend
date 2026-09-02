# 브랜드 태그 수집 개수 상한(collection-post-limit) 구현 계획

> 상태: ✅ 실행 완료 (2026-08-19)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 태그 열거에 전역 수집 개수 상한(기본 2,000)을 추가해, 상한 도달 시 의도된 자연 종료(INFO) + 목표 컷 전체 touch(컷 밖 지표 동결)로 백필·심층 스윕 비용을 좁힌다.

**Architecture:** 변경은 `monitoring` 모듈 `BrandCollectService.doSweepCore` 열거 루프 한 곳 — 기존 안전 밸브(`max-posts-per-sweep` 10,000·ERROR·커버 미처리) **안쪽**에 새 컷(`collection-post-limit` 2,000·INFO·**커버 처리**)을 놓는다. 커버 처리(`coveredCutoff=true` → `touchCrawledDepth`를 목표 컷 전체로)가 컷 밖 due 루프 차단과 지표 동결을 동시에 구현한다. 스펙: [docs/superpowers/specs/2026-08-19-brand-collection-post-limit-design.md](../../specs/2026-08-19-brand-collection-post-limit-design.md).

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5 + AssertJ (fake HikerHttp 스텁 관용구, DB 없음)

## Global Constraints

- 설정 키·기본값: `monitoring.brand.collection-post-limit`, 기본 **2000** (스펙 §4 — 전역 설정 하나, 브랜드별 컬럼 아님)
- 안전 밸브(`max-posts-per-sweep` 10,000·ERROR·`coveredCutoff` 미설정)는 **무변경** — 두 상한의 역할 구분을 코드 주석에 명시(스펙 §4)
- 상한 종료 로그는 **INFO** — 열거 건수·목표 컷·실제 커버 깊이 포함(스펙 §3-2)
- 컷 판정은 `seen.size()`(기지 포함 열거량) 기준, 판정 위치는 커서 소진 판정 **뒤**·안전 밸브 판정 **앞**(스펙 §3-1)
- 티어 정책(`BrandCrawlPolicy`)·저장 스키마·was API 동작·편입 컷 무변경, DB 마이그레이션 없음(스펙 §4)
- marynmay_global 수동 보정 없음 — 배포 후 첫 스윕이 자동 동결(스펙 §5)
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(monitoring):` 등 (CLAUDE.md)
- 테스트는 모듈 단위: `./gradlew :monitoring:test` (전체 `./gradlew test`는 PR 직전에만)

---

### Task 1: BrandCollectService 수집 상한 컷 + 테스트

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (생성자 ~L66-93, `doSweepCore` 루프 ~L205-225)
- Modify: `monitoring/src/main/resources/application.yml` (`monitoring.brand` 블록, `max-posts-per-sweep` 아래 ~L52)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java` (헬퍼 ~L335, 열거 워크 섹션에 신규 테스트)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java:217` (생성자 인자 1개 추가)

**Interfaces:**
- Consumes: 기존 `BrandCollectService` 생성자·`doSweepCore` 루프·`TaggedPostRepository.touchCrawledDepth(long, Instant, Instant)`
- Produces: 생성자 시그니처 변경 — `maxPostsPerSweep` 뒤에 `int collectionPostLimit` 삽입:
  `new BrandCollectService(hiker, callContext, writer, snapshots, comments, taggedPosts, authors, adJudge, enrichWorker, maxPostsPerSweep, collectionPostLimit, commentPages, authorStaleDays, adDisclosureEnabled)`

- [ ] **Step 1: 테스트 헬퍼에 2-인자 오버로드 추가 + 기존 호출부 4곳 보정**

`BrandCollectServiceTest.java` L335의 `service(int)` 헬퍼를 아래로 교체(기존 1-인자 호출부는 수집 상한을 불활성값 10000으로 위임해 **의미 불변**):

```java
private BrandCollectService service(int maxPostsPerSweep) {
	return service(maxPostsPerSweep, 10000);   // 수집 상한 불활성 — 기존 테스트 의미 보존
}

private BrandCollectService service(int maxPostsPerSweep, int collectionPostLimit) {
	return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
			new FakeAdJudge(), Runnable::run, maxPostsPerSweep, collectionPostLimit, 3, 30, true);
}
```

같은 파일의 나머지 직접 생성 2곳도 `maxPostsPerSweep` 뒤에 불활성값 `10000`을 끼운다:
- L1008경(워커 풀 테스트): `..., new FakeAdJudge(), pool, 2000, 10000, 3, 30, true)`
- L1175경(`serviceWithAdJudge`): `..., adJudge, Runnable::run, 10000, 10000, 3, 30, adDisclosureEnabled)`

`BrandDirectCollectServiceTest.java` L217경도 동일:
`..., authors, null, Runnable::run, 2000, 10000, 3, 30, false)`

- [ ] **Step 2: 신규 테스트 3개 작성 (열거 워크 섹션, `안전_상한_중단은_깊이를_갱신하지_않는다` 아래)**

```java
@Test
void 수집_상한_도달은_자연_종료로_목표_깊이_전체를_커버한다() {
	// 수집 개수 상한(2026-08-19 스펙 §3) — 안전 밸브와 달리 의도된 종료라 커버 처리한다.
	// 열거에서 못 만난 컷 밖 깊은 due 링크까지 touch해야 한다 — 안 하면 due가 영구 잔존해
	// 매 스윕이 같은 깊이를 다시 여는 낭비 루프가 된다(§3-2: 루프 차단 = 지표 동결).
	tagged.tracked.add(new TaggedPostRepository.TrackedPost("DeepDue60d",
			Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
	tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

	service(10000, 3).sweep(sweptBrand);   // 수집 상한 3 — 2페이지째 4건 도달, 3페이지는 안 부른다

	assertThat(tagCalls()).isEqualTo(2);
	assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C", "D");
	assertThat(tagged.depthCalls).isEqualTo(1);            // 안전 밸브(depthCalls=0)와의 결정적 차이
	assertThat(tagged.touched).containsKey("DeepDue60d");  // 목표 컷(60일 due)까지 통째로 동결
}

@Test
void 수집_상한_도달도_백필_페이지_콜백은_전부_방출한다() {
	// 등록 백필도 같은 진입점(스펙 §3-4) — 상한 종료가 완결 배치 서빙 계약(페이지마다 1회
	// 콜백)을 깨지 않아야 markServing·backfill_completed_at 경로가 무변경으로 성립한다.
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
	tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));
	List<Integer> sizes = new ArrayList<>();

	service(10000, 3).sweepCore(brand, pageItems -> sizes.add(pageItems.size()));

	assertThat(sizes).containsExactly(2, 2);       // 중단 전 두 페이지 모두 자기 페이지분으로 방출
	assertThat(tagged.depthCalls).isEqualTo(1);    // 백필 목표 컷(수집 창 전체)도 커버 처리
}

@Test
void 마지막_페이지에서_정확히_상한_도달은_자연_종료다() {
	// 상한 판정이 커서 소진 판정 뒤에 있어(기존 안전 밸브와 같은 규칙 — 스펙 §3-1) 마지막
	// 페이지에서 정확히 상한에 닿으면 상한 경로가 아니라 커서 소진 자연 종료로 끝난다.
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	tagPages.add(page(null, reel("C", RECENT, 0, 103, "")));

	service(10000, 3).sweep(brand);   // 상한 3 = 총 열거 3건 — 정확히 상한에서 커서 소진

	assertThat(tagCalls()).isEqualTo(2);
	assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C");
	assertThat(tagged.depthCalls).isEqualTo(1);
}
```

- [ ] **Step 3: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :monitoring:compileTestJava`
Expected: FAIL — `BrandCollectService` 생성자에 14번째 인자가 없어 컴파일 에러 (신규 테스트·보정된 호출부 모두)

- [ ] **Step 4: BrandCollectService 구현**

생성자(L66-93경): 필드 `private final int collectionPostLimit;` 추가, 파라미터를 `maxPostsPerSweep` 바로 뒤에 삽입:

```java
@Value("${monitoring.brand.max-posts-per-sweep:10000}") int maxPostsPerSweep,
@Value("${monitoring.brand.collection-post-limit:2000}") int collectionPostLimit,
```

(대입 `this.collectionPostLimit = collectionPostLimit;`도 같은 위치에.)

`doSweepCore` 루프(L205-225경): 자연 종료 판정(`wholePageBeforeCutoff || page.nextPageId() == null`)과 안전 밸브 판정(`seen.size() >= maxPostsPerSweep`) **사이**에 삽입:

```java
// 수집 개수 상한(2026-08-19 스펙) — 비용 목적의 "의도된 자연 종료"다. 안전 밸브(아래
// maxPostsPerSweep)와 역할이 다르다: 밸브는 닿으면 안 되는 폭주 방어(ERROR·커버 미처리 —
// 다음 스윕이 같은 깊이를 다시 연다)이고, 이 컷은 정상 경로(INFO·커버 처리)다.
// coveredCutoff=true로 목표 컷 "전체"를 touch하는 것이 핵심이다(스펙 §3-2): 컷 밖(더 깊은)
// due의 last_crawled_at이 실크롤 없이 갱신돼 ①매 스윕이 그 깊이를 다시 여는 낭비 루프가
// 차단되고 ②그 게시물들은 마지막 수집 시점 지표로 동결된 채 계속 서빙된다(was 목록
// 상한 2,000과 정합). 판정이 커서 소진 뒤인 이유는 밸브와 동일 — 마지막 페이지에서 정확히
// 상한에 닿는 건 자연 종료다.
if (seen.size() >= collectionPostLimit) {
	log.info("브랜드 태그 수집 개수 상한({}) 도달 — {} 의도된 자연 종료"
					+ " (열거 {}건, 목표 컷 {}, 실제 커버 깊이 {})",
			collectionPostLimit, brand.username(), seen.size(), cutoff,
			oldestTakenAt(collected));
	coveredCutoff = true;
	break;
}
```

클래스 javadoc(L36-50경)에 한 줄 추가: "2026-08-19 수집 개수 상한: 열거는 브랜드당 최신
collection-post-limit(기본 2,000)건에서 의도된 자연 종료 — 목표 컷 전체 touch로 컷 밖 지표 동결."

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest" --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: PASS (신규 3개 포함 전부 — 특히 기존 `안전_상한_도달_시_열거를_중단한다`·`안전_상한_중단은_깊이를_갱신하지_않는다`가 무변경 통과 = 두 상한 독립 증명)

- [ ] **Step 6: application.yml 설정 추가**

`monitoring/src/main/resources/application.yml`의 `max-posts-per-sweep` 항목 바로 아래:

```yaml
    collection-post-limit: 2000     # 수집 개수 상한(2026-08-19 스펙) — 한 실행의 열거를 최신 N건에서
                                    # "의도된 자연 종료"(INFO)로 끊는다. 종료 시 목표 컷 전체를 touch해
                                    # 컷 밖 게시물은 지표 동결·계속 서빙(due 재열거 루프 차단). 위
                                    # 안전 밸브(ERROR·커버 미처리)와 역할이 다르다 — was 목록 상한
                                    # (V1BrandPostsController.POST_LIMIT 2000)과 정합값
```

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
git commit -m "feat(monitoring): 브랜드 태그 수집 개수 상한(collection-post-limit 2000) 추가

열거를 최신 N건에서 의도된 자연 종료로 끊고 목표 컷 전체를 touch —
컷 밖 due 재열거 루프 차단 + 지표 동결(계속 서빙). 안전 밸브(10000·ERROR)와
독립. marynmay_global 백필 1만 건(\$10.6) 운영 실측 대응."
```

### Task 2: was POST_LIMIT 주석의 낡은 참조 교정

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java:55-60` (주석만 — 동작 무변경)

**Interfaces:**
- Consumes: 없음 (독립 주석 수정)
- Produces: 없음 (코드 동작 무변경 — 리뷰어가 Task 1과 독립으로 승인/반려 가능)

- [ ] **Step 1: 주석 교정**

L56의 javadoc이 `monitoring {@code max-posts-per-sweep:2000}`을 참조하는데, 그 값은 08-12에 10,000으로 오르며 낡았다. Task 1로 신설된 `collection-post-limit:2000`이 실제 정합 상대이므로 교정:

```java
	/**
	 * 목록 상한(FE 명세 meta.limit) — 수집 개수 상한(monitoring {@code collection-post-limit:2000},
	 * 2026-08-19 스펙)과 같은 값으로, 수집하는 만큼 보여줄 수 있는 양이다. 구 200은 90일·105건
	 * 시절의 값이라 정책 v1(365일 윈도우·저장소 상한 폐지) 이후 12개월치가 많은 브랜드(실측
	 * 463건)를 실제로 잘랐다 — 잘린 것은 정렬 뒤쪽, 즉 새로 백필된 소급분이었다.
	 */
	private static final int POST_LIMIT = 2000;
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL (주석만 바뀌었으므로)

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java
git commit -m "docs(was): POST_LIMIT 주석의 낡은 max-posts-per-sweep 참조를 collection-post-limit으로 교정"
```

### Task 3: DECISIONS.md 기록 + 모듈 테스트 전체 확인

**Files:**
- Modify: `DECISIONS.md` (맨 위에 새 결정 추가 — CLAUDE.md 규칙)

**Interfaces:**
- Consumes: Task 1·2 완료 상태
- Produces: 없음 (문서·검증)

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 기록**

기존 항목 형식을 따라 맨 위에 추가(파일을 열어 최신 항목의 형식·날짜 표기를 확인하고 맞출 것):

```markdown
## 2026-08-19 브랜드 태그 수집 개수 상한(collection-post-limit 2000) 신설

marynmay_global 등록 백필이 12개월 창 전량을 걷다 1만 건·$10.6에 도달(안전 밸브 ERROR) —
수집량과 was 서빙량(POST_LIMIT 2,000)의 괴리가 곧 낭비 비용이라, 열거를 최신 2,000건에서
"의도된 자연 종료"로 끊는 전역 설정을 신설했다. 종료 시 목표 컷 전체를 touch해 컷 밖 due
재열거 루프를 차단하고 지표를 동결(계속 서빙)한다. 안전 밸브(max-posts-per-sweep 10,000·
ERROR·커버 미처리)는 폭주 방어로 독립 유지. 기간(collection_months)이 아닌 개수인 이유:
유입량이 브랜드마다 달라 기간으로는 개수를 표현할 수 없다(marynmay는 3개월도 ~3,400건).
[스펙](../../specs/2026-08-19-brand-collection-post-limit-design.md)
```

- [ ] **Step 2: monitoring 모듈 테스트 전체 실행**

Run: `./gradlew :monitoring:test`
Expected: PASS 전체 (Testcontainers 통합 테스트 포함 — 로컬 Docker Desktop 기동 필요, `DOCKER_HOST` 미설정이 이 머신의 정답)

- [ ] **Step 3: 커밋**

```bash
git add DECISIONS.md
git commit -m "docs: 브랜드 태그 수집 개수 상한 신설 결정 기록"
```
