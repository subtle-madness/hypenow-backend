# 성과 대시보드 ETag 조건부 요청 (PR ④) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대시보드 4표면(`/contents`·`/comparison`·`/influencers`·`/growth`)에 약한 ETag + 조기 304를 얹어 무변경 재요청의 조립·직렬화·전송을 전부 생략한다.

**Architecture:** [08-13 설계](../specs/2026-08-13-performance-dashboard-etag-design.md)를 그대로 구현한다 — 버전키는 데이터 유래 지문(md5: cacheEpoch·userId·KST 날짜·레거시/브랜드 스윕 워터마크·유저 소유 행 지문), **조립 전** 계산해 If-None-Match 일치 시 304 조기 반환. 캐시 헤더는 4표면만 `private, no-cache`로 전환. 단건 라우트는 제외(원 설계).

**Tech Stack:** Java 21 · Spring Boot 4.1 · JdbcClient(app DataSource) · MessageDigest md5.

**스펙:** 08-13 설계 전체 + [2026-08-27 설계 §6](../specs/2026-08-27-perf-dashboard-list-api-optimization-design.md)(표면 4종 확장·지문 재점검)

## Global Constraints

- 주석·커밋 한국어, prefix `feat(was):`/`docs:`. Docker Desktop — `DOCKER_HOST` 설정 금지.
- 테스트: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"`, 마지막에 `:was:test` 전체.
- **정확성 원칙(08-13 §2)**: 지문이 놓친 입력 = 낡은 데이터의 조용한 서빙. 과잉 무효화(불필요 재계산)는 무해, 과소 무효화는 사고 — 응답에 영향 주는 유저 쓰기 컬럼을 빠짐없이 지문에 넣는다.
- 304 조기 반환은 컨트롤러 안(인증·CORS 필터 뒤 — 08-13 §5-①). 304에서 `index()`/조립이 돌면 안 된다(테스트로 고정).
- 약한 검증자 `W/"<md5 앞 16자>"`. If-None-Match 비교는 W/ 접두 무시·복수 값·`*` 처리.
- 캐시 헤더 전환은 **4표면 한정**: `Cache-Control: private, no-cache`(+`Pragma` 제거) — Spring Security 기본(no-store)을 이 응답들에서만 덮는다. 전역 변경 금지.
- 기존 응답 본문 계약 불변(헤더만 추가). 스키마 변경 없음.
- **스테이징 수동 검증 8종**(08-13 §5: CORS 헤더·Vary·gzip·304 본문·세션 슬라이딩·지문 누락·자정 경계·지표 해석)은 이 PR 범위 밖 — develop→staging 배포 후, 운영 승격 전 필수 게이트로 PR 본문·회신에 명시한다.

## 구현 결정 (08-13 설계의 미확정 지점 확정)

1. **아카이브 워터마크(③) 생략** — 08-13이 "비용 재고 후 생략 허용"으로 남긴 항목. 08-14 GCS 컷오버로 이미지 경로가 안정화됐고, 스윕 워터마크가 매일 갱신돼 지연 상한이 하루라 원 설계의 수용 조건 그대로 성립. 설계 문서 상태 갱신 시 기록.
2. **지문 대상 확장(08-13 §2-2 이후 스키마 변화 반영)**: 08-13의 3테이블(`monitoring_items`·`brand_monitorings`·`brand_direct_posts`)에 더해 —
   - `app.campaigns`(id·name, 유저 스코프): `campaignName`이 응답에 실린다(이름 변경 감지).
   - `app.brand_post_campaigns`(유저 스코프 부착 행): direct 통합 이후 캠페인 부착 정본 — `campaignId`·campaignIds 유래.
   - `brand_monitorings`에 `collection_months` 포함(대시보드 응답 직접 영향은 없으나 과잉 무효화는 무해 — 판단 비용보다 포함이 싸다).
3. **브랜드 워터마크는 행 지문으로**: 연결 브랜드들의 `(brand_id, last_swept_at, covered_until)` — `covered_until`은 커버리지 클램프 입력이라 max(last_swept_at)만으로는 부족할 수 있다(백필 진행 중 갱신).

## File Structure

- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardVersion.java` — 버전키 계산 컴포넌트(+ETag 문자열·If-None-Match 매칭 정적 헬퍼).
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardVersionRepository.java` — app DataSource 유저 지문 쿼리(JdbcClient).
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` — 4라우트 조건부 반환 배선.
- Test: Create `DashboardVersionTest.java` + `V1PerformanceDashboardControllerTest.java` 확장.

---

### Task 1: 버전키 계산기

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardVersionRepository.java`
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardVersion.java`
- Test: Create `was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardVersionTest.java`

**Interfaces:**
- Produces:

```java
/** 유저 소유 가변 행 지문(08-13 §2-3) — app DataSource. 각 쿼리는 md5 문자열 1개를 돌려준다. */
@Repository
public class DashboardVersionRepository {
	public String monitoringItemsFingerprint(long userId)   // id:tracking_days:campaign_id:target_id:canceled_at
	public String brandLinksFingerprint(long userId)        // brand_id:account_type:collection_months:deleted_at
	public String directPostsFingerprint(long userId)       // 행 식별 + 가변 컬럼 전부(실 스키마 확인 후 확정)
	public String campaignsFingerprint(long userId)         // id:name(:deleted 계열 있으면 포함)
	public String postCampaignLinksFingerprint(long userId) // short_code:campaign_id
}
```

각 쿼리는 08-13 §2-3의 관용구를 따른다(예 — 나머지도 동형):

```sql
SELECT md5(coalesce(string_agg(
         i.id || ':' || i.tracking_days || ':' || coalesce(i.campaign_id::text,'-')
              || ':' || coalesce(i.target_id::text,'-') || ':' || coalesce(i.canceled_at::text,'-'),
         ',' ORDER BY i.id), ''))
FROM app.monitoring_items i WHERE i.user_id = :userId
```

**구현 전 실 스키마 확인 필수**: 각 테이블의 실제 컬럼을 레포의 Flyway 마이그레이션(`was/src/main/resources/db/migration/app`)에서 확인하고, 응답에 영향 주는 가변 컬럼이 지문에 다 들어갔는지 대응표를 javadoc에 남긴다(08-13 §2-3 "해싱 컬럼 ↔ 응답 영향 컬럼 1:1" 규율).

```java
/**
 * 대시보드 버전키(08-13 설계) — 조립 전에 계산해 If-None-Match 일치 시 304 조기 반환의 근거가 된다.
 * 입력 다섯 종(§2-1): 레거시 스윕·브랜드 스윕(행 지문)·유저 쓰기 지문·KST 날짜·배포 세대.
 */
@Component
public class DashboardVersion {
	public DashboardVersion(DashboardVersionRepository repository,
			Optional<MonitoringReadRepository> monitoringReadRepository,
			BrandLinkRepository linkRepository, Optional<BrandReadRepository> brandReadRepository,
			ObjectProvider<BuildProperties> buildProperties, Clock clock)

	/** 버전키 — md5 hex 32자. 같은 입력이면 항상 같다(순서 고정 join 후 md5). */
	public String compute(long userId)

	/** ETag 헤더 값 — {@code W/"<version 앞 16자>"}. */
	public static String etagOf(String version)

	/** If-None-Match 매칭 — W/ 접두·따옴표 무시, 쉼표 복수 값, {@code *}는 항상 일치. */
	public static boolean matches(String ifNoneMatchHeader, String etag)
}
```

- `cacheEpoch`은 `CacheConfig`와 같은 관용구: `BuildProperties.getTime()` 없으면 `"dev"` (08-13 §2-6 — 배포마다 전 ETag 무효화).
- KST 날짜는 주입된 `Clock`으로(`LocalDate.ofInstant(clock.instant(), KstTimestamps.KST)`) — 자정 경계 테스트 가능.
- 레거시 워터마크: `MonitoringReadRepository.lastSuccessfulSweepAt()`(Optional — monitoring 비활성이면 `"-"`).
- 브랜드 워터마크: `linkRepository.findAllActiveByUser(userId)` 순회로 `findAccount(brandId)`의 `(id, lastSweptAt, coveredUntil)`을 brand_id 오름차순 정렬 join(비활성·빈 링크면 `"-"`).
- 최종: `md5(join("|", cacheEpoch, userId, kstToday, legacySweepAt, brandRows, fp1..fp5))` — Java `MessageDigest`.

- [ ] **Step 1: 실패하는 테스트** — `DashboardVersionTest`(mock repo — 테이블 주도):

```java
	@Test
	void 입력이_하나라도_바뀌면_키가_바뀐다() {
		// 기준 스텁으로 키 계산 후, 입력 8종(레거시 스윕·브랜드 last_swept·covered_until·아이템 지문·
		// 링크 지문·direct 지문·캠페인 지문·부착 지문)을 하나씩 바꿔 각각 키가 달라짐을 단정
	}

	@Test
	void 같은_입력이면_키가_같다() { }

	@Test
	void KST_자정을_넘기면_키가_바뀐다() { /* Clock 주입 — 23:59 vs 00:00 KST */ }

	@Test
	void monitoring_비활성이면_레거시_브랜드_입력이_상수로_접히고_키는_안정적이다() { }

	@Test
	void etagOf는_약한_검증자_형식이다() { /* W/"16자" */ }

	@Test
	void matches는_W_접두와_복수_값과_별표를_처리한다() {
		// "W/\"abc\"" vs etag W/"abc" → true; "\"abc\", \"def\"" 중 일치 → true; "*" → true; 불일치 → false
	}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.DashboardVersionTest"` / Expected: 컴파일 실패
- [ ] **Step 3: 구현** — 위 명세. 지문 SQL은 스키마 확인 후 컬럼 대응표 javadoc과 함께.
- [ ] **Step 4: 통과 확인** — Run 동일 + `--tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 버전키 — 데이터 유래 지문 계산기(08-13 설계)`

---

### Task 2: 컨트롤러 조건부 반환 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `DashboardVersion`.
- Produces: 4라우트(`contents`·`comparison`·`influencers`·`growth`)의 반환이 `ResponseEntity<...>`로 바뀌되 **본문 JSON은 기존과 동일**(ApiResponse 래핑 유지). 단건 라우트는 무변경.

배선 관용구(4라우트 공통 — private 헬퍼로):

```java
	/**
	 * 조건부 반환(08-13 설계) — 버전키는 조립 전에 계산한다: 304면 index()·조립·직렬화 전부 생략이
	 * 이 설계의 이득이다. 파라미터 검증(400)은 버전 계산보다 앞 — 잘못된 요청이 304로 가려지면 안 된다.
	 */
	private <T> ResponseEntity<T> conditional(long userId, String ifNoneMatch, Supplier<T> body) {
		String etag = DashboardVersion.etagOf(dashboardVersion.compute(userId));
		if (DashboardVersion.matches(ifNoneMatch, etag)) {
			return withCacheHeaders(ResponseEntity.status(HttpStatus.NOT_MODIFIED), etag).build();
		}
		return withCacheHeaders(ResponseEntity.ok(), etag).body(body.get());
	}

	private static ResponseEntity.HeadersBuilder<?>/*BodyBuilder*/ withCacheHeaders(..., String etag) {
		// ETag: etag / Cache-Control: "private, no-cache" — Spring Security 기본(no-store)을 이 응답에서만 덮는다.
		// Pragma는 덮어쓰기로 제거 불가하면 빈 값 대신 그대로 두되 테스트로 실제 헤더를 관찰해 결정(08-13 §4).
	}
```

각 라우트: `@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch` 추가 → 파라미터 검증(400) 후 `return conditional(userId, ifNoneMatch, () -> <기존 본문 조립>)`.

- [ ] **Step 1: 실패하는 테스트**:

```java
	@Test
	void 같은_버전이면_304이고_조립이_돌지_않는다() throws Exception {
		given(dashboardVersion.compute(7L)).willReturn("v".repeat(32));
		mockMvc.perform(get(CONTENTS).header("If-None-Match", "W/\"" + "v".repeat(16) + "\"").with(user(principal())))
				.andExpect(status().isNotModified())
				.andExpect(header().string("ETag", "W/\"" + "v".repeat(16) + "\""))
				.andExpect(content().string(""));
		then(assembler).should(never()).index(anyLong());
	}

	@Test
	void 다른_버전이면_200과_ETag_캐시_헤더가_실린다() throws Exception {
		// 200 + ETag + Cache-Control "private, no-cache" (no-store 부재 단정) + 본문 기존 그대로
	}

	@Test
	void 파라미터_400은_조건부보다_앞이다() throws Exception {
		// sort=bogus + If-None-Match 일치 → 400 (304 아님), compute 미호출
	}

	@Test
	void 네_표면_모두_조건부가_걸리고_단건은_아니다() throws Exception {
		// contents·comparison·influencers·growth 각 304 확인; /contents/{id}는 If-None-Match 보내도 200·ETag 없음
	}
```

기존 테스트: 반환 타입 변경으로 인한 컴파일·기대 헤더 차이만 조정(본문 jsonPath 단정은 무수정이어야 한다 — 본문 계약 불변의 증거). `dashboardVersion`은 `@MockitoBean` 추가, 기본 스텁은 항상 새 버전(조건부 미스) — 기존 테스트가 304로 새지 않게.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"` / Expected: FAIL
- [ ] **Step 3: 구현** — 위 관용구. Cache-Control 덮어쓰기가 실제로 관철되는지(@WebMvcTest + SecurityConfig import 환경에서 최종 헤더 관찰)를 테스트가 보증. 안 되면 `response.setHeader` 직접 설정 등 대안을 찾고 report에 기록.
- [ ] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` / Expected: PASS
- [ ] **Step 5: Commit** — `feat(was): 대시보드 4표면 조건부 요청 — 조립 전 304·캐시 헤더 전환`

---

### Task 3: 문서·전체 검증

**Files:**
- Modify: `docs/superpowers/specs/2026-08-13-performance-dashboard-etag-design.md` (상태 헤더만: `미구현` → `✅ 구현/반영됨(PR ④ — 표면 4종 확장·아카이브 워터마크 생략, 2026-08-28)`)
- Modify: `docs/superpowers/specs/2026-08-27-perf-dashboard-list-api-optimization-design.md` (상태 헤더: `§1~§5(PR ①~③) 구현됨 · §6 미구현` → `§1~§6 전부 구현됨(PR ①~④)`)
- Modify: `DECISIONS.md` (`(PR ①~③ 구현됨 · ④ 미구현)` → `(PR ①~④ 전부 구현됨)`)
- Modify: `docs/superpowers/specs/2026-08-27-perf-dashboard-list-reply.md` — §7 표의 ④ 행 `구현됨(스테이징 검증 후 운영)`으로 갱신 + 말미에 `## 10. PR ④ ETag (2026-08-28 추가)` 절: FE 변경 불필요(브라우저 자동 If-None-Match — fetch에 cache 옵션 없어야 함), 적용 표면 4종·단건 제외, **운영 승격 전 스테이징 수동 검증 8종(08-13 §5)이 게이트**임을 명시.

- [ ] **Step 1: 문서 4건 갱신** — 위 지정 문구.
- [ ] **Step 2: 모듈 전체** — Run: `./gradlew :was:test` / Expected: PASS(실제 카운트).
- [ ] **Step 3: Commit** — `docs: PR ④ 상태 갱신 — ETag 구현·스테이징 검증 게이트 명시`

---

## 완료 판정

- 08-13 §6 검증 계획 중 단위·mock 계층 전부(입력별 키 변화·304/200·헤더·400 우선) 코드로 고정. Testcontainers 통합·스테이징 8종은 배포 단계 게이트(PR 본문 명시).
- 기존 응답 본문 계약 불변(기존 jsonPath 단정 무수정 그린).
- PR 생성 시 이 plan을 `plans/archive/`로 이동.
