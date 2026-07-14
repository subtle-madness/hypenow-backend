# 태스크 D3: 상세 API as-of 지표 (`?endDate=`) Implementation Plan

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/posts/{shortCode}?endDate=2026-07-03` — 집계 기간 끝(end_date) 시점의 스냅샷으로 지표를 재구성한다. 파라미터 없으면 기존 최신 경로 그대로.

**Architecture:** ARCHITECTURE §7 2026-07-14 결정("as-of 선택 규칙") 이행. 프론트 실측 근거 — 랭킹 집계 기간은 게시일 필터고 "그 기간 화면"의 지표 시점은 end_date다. 선택 규칙: `captured_at < endDate 다음날 0시(KST)` 중 최신 스냅샷 1행(`content_metric_snapshots`, 계약 record `ContentMetricSnapshot`으로 SELECT — §4-3). **그 시점 스냅샷이 없으면 404** — 그 기간 화면에 존재하지 않는 게시물(목록도 같은 규칙으로 필터링 예정, 태스크 H). as-of가 바꾸는 것은 지표뿐: views·likes·comments·engagementRate·hypeScore(스냅샷 값), daysSincePosted(endDate 기준), `post.metricsCapturedAt` 신설(사용된 스냅샷 시각 — 최신 경로 null). account·캡션 등 메타·comments·analysis는 불변.

**Tech Stack:** 기존 D 스택 그대로. 날짜 해석은 KST(`Asia/Seoul`) 고정.

**작업 위치:** worktree `.worktrees/task-d2` (브랜치 `feat/task-d-post-detail-api` 이어서). 메인 체크아웃 접근 금지.

**소스 계약 (실물 확인 완료):**

- `content_metric_snapshots`(V4): id(PK, raw id), short_code, captured_at timestamptz, views, likes, comments, hype_score / 인덱스 (short_code, captured_at)
- `ContentMetricSnapshot` record: id, shortCode, capturedAt, views, likes, comments, hypeScore

**응답 계약 변경 (additive):**

```jsonc
"post": {
  ..., // 기존 필드 그대로
  "metricsCapturedAt": "2026-07-08T06:05:18Z"   // asOf 경로: 사용된 스냅샷 시각 / 최신 경로: null
}
```

- `?endDate=` 있음: 지표 5종 = 스냅샷 값, engagementRate = (스냅샷 likes+comments)/스냅샷 views(피드 null 규칙 동일), daysSincePosted = postedAt→endDate 다음날 0시 KST 기준 24h 단위(기존 의미 유지 — "게시 N일차" = 값+1이 프론트 표기와 일치).
- 스냅샷 없음 → **404**. endDate 형식 오류(`yyyy-MM-dd` 아님) → **400**(Spring 기본 타입 미스매치).

## File Structure

```
was/src/main/java/com/celfit/was/postdetail/
  AsOfMetrics.java                [신규] as-of 조립 입력 (스냅샷 + 기준 시각) — was 로컬 record
  PostDetailRepository.java       [수정] findSnapshotAsOf 추가 (계약 record 매핑)
  PostDetailResponse.java         [수정] Post.metricsCapturedAt 추가
  PostDetailAssembler.java        [수정] toResponse 5번째 인자 Optional<AsOfMetrics> — 지표 원천 분기
  PostDetailController.java       [수정] endDate 파라미터 + KST cutoff 계산 + 404
was/src/test/java/com/celfit/was/postdetail/
  PostDetailRepositoryTest.java   [수정] 스냅샷 시드 + as-of 선택 3케이스
  PostDetailAssemblerTest.java    [수정] 기존 호출 5인자화 + as-of 조립 2테스트
  PostDetailControllerTest.java   [수정] endDate 200/404/400 3테스트
```

**테스트 기대값 근거 (스냅샷 시드 — mari01):**

| captured_at (UTC) | KST 시각 | views | likes | comments | hype |
|---|---|---|---|---|---|
| 2026-06-30T15:00:00Z | 07-01 00:00 | 100000 | 3000 | 100 | 100000 |
| 2026-07-04T14:59:59Z | 07-03 23:59:59 | 500000 | 20000 | 400 | 500000 |
| 2026-07-07T03:00:00Z | 07-07 12:00 | 1911943 | 32969 | 488 | 1911943 |

- endDate=2026-07-03 → cutoff = 07-04 00:00 KST = **07-03T15:00:00Z** → 2행 중 최신 = **07-04T14:59:59Z? 아니다** — 07-04T14:59:59Z(UTC)는 KST 07-04 23:59라 cutoff 초과. 선택되는 것은 **06-30T15:00Z 스냅샷(views 100000)**. (경계 검증 의도: UTC 표기에 속지 말 것)
- endDate=2026-07-04 → cutoff 07-05 00:00 KST = 07-04T15:00:00Z → **07-04T14:59:59Z 스냅샷(views 500000)** — cutoff 1초 전 경계.
- endDate=2026-06-29 → cutoff 06-30 00:00 KST = 06-29T15:00:00Z → 스냅샷 없음 → empty.
- as-of ER: 500000 스냅샷 → (20000+400)/500000 = **0.0408**.
- daysSincePosted: postedAt 2026-06-28T00:00Z(=KST 06-28 09:00), endDate=07-03 → between(06-28T00:00Z, 07-03T15:00Z) = 5일 15시간 → **5**.

---

### Task 1: 리포지토리 — as-of 스냅샷 선택 (TDD)

**Files:**
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`

- [ ] **Step 1: 실패하는 테스트 추가**

`setUpTables()`에 스냅샷 테이블 DDL·시드 추가 (기존 시드 뒤, V4와 동일 형상):

```java
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_metric_snapshots");
		jdbcTemplate.execute("""
				CREATE TABLE content_metric_snapshots (
				    id          bigint PRIMARY KEY,
				    short_code  text NOT NULL,
				    captured_at timestamptz NOT NULL,
				    views       bigint,
				    likes       bigint,
				    comments    bigint,
				    hype_score  bigint
				)""");
		jdbcTemplate.update("""
				INSERT INTO content_metric_snapshots VALUES
				 (11, 'mari01', '2026-06-30T15:00:00Z', 100000, 3000, 100, 100000),
				 (12, 'mari01', '2026-07-04T14:59:59Z', 500000, 20000, 400, 500000),
				 (13, 'mari01', '2026-07-07T03:00:00Z', 1911943, 32969, 488, 1911943)
				""");
```

테스트 3개 추가 (임포트: `com.celfit.contract.analysis.ContentMetricSnapshot`, `java.time.OffsetDateTime`):

```java
	@Test
	void cutoff_이전_스냅샷_중_최신을_선택한다() {
		// endDate=2026-07-03 → cutoff = 2026-07-03T15:00:00Z (KST 07-04 00:00)
		Optional<ContentMetricSnapshot> found = repository.findSnapshotAsOf(
				"mari01", OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		assertThat(found).isPresent();
		assertThat(found.get().views()).isEqualTo(100000L);   // 07-04T14:59Z는 KST 07-04라 제외
		assertThat(found.get().capturedAt()).isEqualTo(OffsetDateTime.parse("2026-06-30T15:00:00Z"));
	}

	@Test
	void cutoff_직전_1초의_스냅샷도_포함한다() {
		// endDate=2026-07-04 → cutoff = 2026-07-04T15:00:00Z
		Optional<ContentMetricSnapshot> found = repository.findSnapshotAsOf(
				"mari01", OffsetDateTime.parse("2026-07-04T15:00:00Z"));

		assertThat(found).isPresent();
		assertThat(found.get().views()).isEqualTo(500000L);
		assertThat(found.get().hypeScore()).isEqualTo(500000L);
	}

	@Test
	void cutoff_이전_스냅샷이_없으면_empty다() {
		assertThat(repository.findSnapshotAsOf(
				"mari01", OffsetDateTime.parse("2026-06-29T15:00:00Z"))).isEmpty();
	}
```

기존 `미러_테이블이_없으면_빈_값으로_저하한다`에 DROP·assert 추가:

```java
		jdbcTemplate.execute("DROP TABLE content_metric_snapshots");
		// (기존 assert들 뒤에)
		assertThat(repository.findSnapshotAsOf("mari01",
				OffsetDateTime.parse("2026-07-10T00:00:00Z"))).isEmpty();
```

- [ ] **Step 2: 실행 — 컴파일 실패 확인** (`./gradlew :was:test --tests '*PostDetailRepositoryTest*'` → `findSnapshotAsOf` 심볼 없음)

- [ ] **Step 3: Repository에 메서드 추가**

```java
	/** cutoff(집계 기간 끝의 KST 다음날 0시) 이전 스냅샷 중 최신 1행 — as-of 선택 규칙(§7 2026-07-14). */
	public Optional<ContentMetricSnapshot> findSnapshotAsOf(String shortCode, OffsetDateTime cutoff) {
		return safeQuery("content_metric_snapshots", Optional::empty, () -> jdbcClient.sql("""
				SELECT id, short_code, captured_at, views, likes, comments, hype_score
				FROM content_metric_snapshots
				WHERE short_code = :shortCode AND captured_at < :cutoff
				ORDER BY captured_at DESC
				LIMIT 1
				""")
				.param("shortCode", shortCode)
				.param("cutoff", cutoff)
				.query(ContentMetricSnapshot.class)
				.optional());
	}
```

(임포트 추가: `com.celfit.contract.analysis.ContentMetricSnapshot`, `java.time.OffsetDateTime`. 클래스 Javadoc의 "미러 3종"을 "미러 4종"으로 갱신)

- [ ] **Step 4: 실행 — 통과 확인** (리포지토리 12 tests PASS)

- [ ] **Step 5: Commit** — `feat(was): as-of 스냅샷 선택 조회 — cutoff 이전 최신 1행 (계약 record 매핑)`

---

### Task 2: 어셈블러 — 지표 원천 분기 + metricsCapturedAt (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/postdetail/AsOfMetrics.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`

- [ ] **Step 1: 실패하는 테스트로 수정**

기존 테스트의 `toResponse(...)` 호출 전부에 5번째 인자 `Optional.empty()` 추가. 첫 테스트에 `assertThat(response.post().metricsCapturedAt()).isNull();` 추가. 신규 2개 (임포트: `ContentMetricSnapshot`):

```java
	@Test
	void asOf가_있으면_지표를_스냅샷_값으로_재구성한다() {
		ContentMetricSnapshot snapshot = new ContentMetricSnapshot(12L, "mari01",
				OffsetDateTime.parse("2026-07-04T14:59:59Z"), 500000L, 20000L, 400L, 500000L);
		// endDate=2026-07-03 가정 → 기준 시각 = 2026-07-03T15:00:00Z (KST 07-04 00:00)
		AsOfMetrics asOf = new AsOfMetrics(snapshot, OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.empty(), Optional.of(asOf));

		assertThat(response.post().views()).isEqualTo(500000L);
		assertThat(response.post().likes()).isEqualTo(20000L);
		assertThat(response.post().comments()).isEqualTo(400L);
		assertThat(response.post().hypeScore()).isEqualTo(500000L);
		// (20000+400)/500000 = 0.0408
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0408"));
		// postedAt 06-28T00:00Z → 기준 07-03T15:00Z = 5일 15시간 → 5
		assertThat(response.post().daysSincePosted()).isEqualTo(5L);
		assertThat(response.post().metricsCapturedAt())
				.isEqualTo(OffsetDateTime.parse("2026-07-04T14:59:59Z"));
		// 메타는 최신 미러 값 그대로
		assertThat(response.post().caption()).isEqualTo("쿨톤 여름 침착 조합");
	}

	@Test
	void asOf_스냅샷의_조회수가_null이면_참여율도_null이다() {
		ContentMetricSnapshot feedSnapshot = new ContentMetricSnapshot(21L, "mari02",
				OffsetDateTime.parse("2026-07-02T00:00:00Z"), null, 1500L, 80L, 1580L);
		AsOfMetrics asOf = new AsOfMetrics(feedSnapshot, OffsetDateTime.parse("2026-07-02T15:00:00Z"));

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.empty(), Optional.of(asOf));

		assertThat(response.post().views()).isNull();
		assertThat(response.post().engagementRate()).isNull();
		assertThat(response.post().hypeScore()).isEqualTo(1580L);
	}
```

- [ ] **Step 2: 실행 — 컴파일 실패 확인**

- [ ] **Step 3: 구현**

`AsOfMetrics.java`:

```java
package com.celfit.was.postdetail;

import com.celfit.contract.analysis.ContentMetricSnapshot;
import java.time.OffsetDateTime;

/**
 * as-of 조립 입력 — 선택된 스냅샷과 기준 시각(집계 기간 끝의 KST 다음날 0시).
 * reference는 경과일 계산의 "지금" 역할을 한다.
 */
public record AsOfMetrics(ContentMetricSnapshot snapshot, OffsetDateTime reference) {
}
```

`PostDetailResponse.Post`에 마지막 컴포넌트 추가:

```java
			Long hypeScore,
			OffsetDateTime metricsCapturedAt) {
```

(Javadoc에 한 줄: `metricsCapturedAt = 지표가 어느 스냅샷에서 왔는지 — asOf 경로에서만 채워지고 최신 경로는 null.`)

`PostDetailAssembler` — 시그니처 확장 및 지표 분기 (기존 4인자 호출처는 없으므로 교체):

```java
	public PostDetailResponse toResponse(Content content, Account account, List<CommentRow> comments,
			Optional<ContentAnalysisRow> analysis, Optional<AsOfMetrics> asOf) {
		return new PostDetailResponse(
				asOf.map(a -> asOfPost(content, a)).orElseGet(() -> latestPost(content)),
				/* account·comments·analysis 조립 기존 그대로 */);
	}

	/** 최신 경로 — contents 미러 값 + 현재 시각 기준 경과일. */
	private PostDetailResponse.Post latestPost(Content content) {
		return post(content, content.views(), content.likes(), content.comments(), content.hypeScore(),
				OffsetDateTime.now(clock), null);
	}

	/** as-of 경로 — 스냅샷 지표 + 기간 끝 기준 경과일. 메타(캡션·썸네일 등)는 최신 미러 값. */
	private PostDetailResponse.Post asOfPost(Content content, AsOfMetrics asOf) {
		ContentMetricSnapshot s = asOf.snapshot();
		return post(content, s.views(), s.likes(), s.comments(), s.hypeScore(),
				asOf.reference(), s.capturedAt());
	}

	private PostDetailResponse.Post post(Content content, Long views, Long likes, Long comments,
			Long hypeScore, OffsetDateTime reference, OffsetDateTime metricsCapturedAt) {
		return new PostDetailResponse.Post(
				content.shortCode(), content.thumbnailUrl(), content.caption(),
				content.postedAt(), daysSincePosted(content.postedAt(), reference),
				content.contentType(), content.videoDuration(), content.originalUrl(),
				views, likes, comments,
				engagementRate(views, likes, comments), hypeScore, metricsCapturedAt);
	}
```

`daysSincePosted`·`engagementRate`를 값 인자 기반으로 리팩토링:

```java
	private Long daysSincePosted(OffsetDateTime postedAt, OffsetDateTime reference) {
		if (postedAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(postedAt, reference);
	}

	private BigDecimal engagementRate(Long views, Long likes, Long comments) {
		if (views == null || views == 0) {
			return null;
		}
		long engagements = nullToZero(likes) + nullToZero(comments);
		return BigDecimal.valueOf(engagements)
				.divide(BigDecimal.valueOf(views), 4, RoundingMode.HALF_UP);
	}
```

(기존 Content 인자 버전 제거. 주석의 "경과일 = 24시간 단위…" 의미 유지)

- [ ] **Step 4: 실행 — 통과 확인** (어셈블러 9 tests PASS. 컨트롤러 컴파일 실패 시 Task 3에서 처리 — Task 3까지 이어서 한 뒤 커밋)

- [ ] **Step 5: Commit은 Task 3과 함께** (시그니처 변경으로 컴파일 단위가 얽힘)

---

### Task 3: 컨트롤러 — endDate 파라미터 (TDD)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`

- [ ] **Step 1: 실패하는 테스트로 수정**

`givenMari01()`은 그대로. 신규 3개 (임포트: `ContentMetricSnapshot`, `java.time.LocalDate` 불필요 — 문자열 파라미터):

```java
	@Test
	void endDate가_있으면_그_시점_스냅샷으로_지표를_내린다() throws Exception {
		givenMari01();
		given(repository.findSnapshotAsOf("mari01", OffsetDateTime.parse("2026-07-03T15:00:00Z")))
				.willReturn(Optional.of(new ContentMetricSnapshot(12L, "mari01",
						OffsetDateTime.parse("2026-07-02T03:00:00Z"), 500000L, 20000L, 400L, 500000L)));

		mockMvc.perform(get("/api/posts/mari01").param("endDate", "2026-07-03"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.post.views").value(500000))
				.andExpect(jsonPath("$.post.engagementRate").value(0.0408))
				.andExpect(jsonPath("$.post.metricsCapturedAt").value("2026-07-02T03:00:00Z"))
				.andExpect(jsonPath("$.post.caption").value("쿨톤 여름 침착 조합"));
	}

	@Test
	void endDate_시점_스냅샷이_없으면_404() throws Exception {
		givenMari01();
		given(repository.findSnapshotAsOf("mari01", OffsetDateTime.parse("2026-06-29T15:00:00Z")))
				.willReturn(Optional.empty());

		mockMvc.perform(get("/api/posts/mari01").param("endDate", "2026-06-29"))
				.andExpect(status().isNotFound());
	}

	@Test
	void endDate_형식이_틀리면_400() throws Exception {
		mockMvc.perform(get("/api/posts/mari01").param("endDate", "07/03/2026"))
				.andExpect(status().isBadRequest());
	}
```

> `metricsCapturedAt` 직렬화 문자열이 `2026-07-02T03:00:00Z`와 오프셋 표기(`+00:00` 등)로 어긋나면 기대 문자열을 실제 Jackson 출력에 맞춰 고정하되(의미 동일), 임의 포맷 변경 금지.

- [ ] **Step 2: 실행 — 실패 확인**

- [ ] **Step 3: 컨트롤러 수정**

```java
	/** 집계 기간 끝 날짜의 KST 하루가 끝나는 시각(다음날 0시) — as-of 선택 규칙(§7 2026-07-14). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@GetMapping("/api/posts/{shortCode}")
	public PostDetailResponse postDetail(@PathVariable String shortCode,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
		Content content = repository.findContent(shortCode)
				.orElseThrow(() -> notFound(shortCode));
		Optional<AsOfMetrics> asOf = Optional.ofNullable(endDate).map(d -> {
			OffsetDateTime cutoff = d.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
			ContentMetricSnapshot snapshot = repository.findSnapshotAsOf(shortCode, cutoff)
					// 그 시점 스냅샷이 없으면 그 기간 화면에 존재하지 않는 게시물 (목록 필터링과 동일 규칙)
					.orElseThrow(() -> notFound(shortCode));
			return new AsOfMetrics(snapshot, cutoff);
		});
		return assembler.toResponse(
				content,
				repository.findAccount(content.accountHandle()).orElse(null),
				repository.findComments(shortCode),
				repository.findAnalysis(shortCode),
				asOf);
	}

	private ResponseStatusException notFound(String shortCode) {
		// reason은 서버 로그용 — 프론트 계약은 상태코드 404
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode);
	}
```

(임포트: `org.springframework.format.annotation.DateTimeFormat`, `org.springframework.web.bind.annotation.RequestParam`, `java.time.LocalDate`, `java.time.ZoneId`, `java.time.OffsetDateTime`, `com.celfit.contract.analysis.ContentMetricSnapshot`, `java.util.Optional`)

- [ ] **Step 4: was 전체 테스트** — `./gradlew :was:test` → 리포지토리 12 · 어셈블러 9 · 컨트롤러 8 전부 PASS

- [ ] **Step 5: Commit (Task 2+3 묶음)**

```bash
git add was/src/main/java/com/celfit/was/postdetail/ was/src/test/java/com/celfit/was/postdetail/
git commit -m "feat(was): 상세 API as-of 지표 — endDate 시점 스냅샷 재구성, 부재 시 404

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: E2E 검증 (실 analysis DB)

**Files:** 없음 (검증만. 필요 시 scratchpad 테스터 페이지에 endDate 입력 추가 — 저장소 밖)

- [ ] **Step 1: 전체 테스트** — `./gradlew test` → BUILD SUCCESSFUL

- [ ] **Step 2: 실DB 검증** (실 스냅샷: 두 게시물 모두 2026-07-08 06:05Z = KST 07-08 15:05 수집 1건씩)

```bash
docker start crawler-postgres-1
(cd /Users/woomin/Project/hypenow-backend/.worktrees/task-d2 && ./gradlew :was:bootRun) &
sleep 20
curl -s "http://localhost:8081/api/posts/DYE2SisT-jE?endDate=2026-07-08" | head -c 400; echo   # KST 07-08 포함 → 200 + metricsCapturedAt
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/posts/DYE2SisT-jE?endDate=2026-07-07"   # 스냅샷 이전 → 404
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/posts/DYE2SisT-jE?endDate=bad"          # 400
curl -s "http://localhost:8081/api/posts/DYE2SisT-jE" | python3 -c "import json,sys; d=json.load(sys.stdin); print('latest metricsCapturedAt:', d['post']['metricsCapturedAt'])"  # null
```

- [ ] **Step 3: 서버 종료, working tree clean 확인**

---

### Task 5: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 D3 행 ⬜→✅)
- Modify: `docs/superpowers/plans/2026-07-14-task-d3-asof-metrics.md` (상태 헤더 ✅)

- [ ] **Step 1: §5 D3 행 상태 ⬜→✅** (결정 기록은 2026-07-14 as-of 규칙 행이 이미 있음 — 추가 불필요)
- [ ] **Step 2: 이 계획 상태 헤더 갱신**
- [ ] **Step 3: Commit** — `docs: D3 완료 반영 (as-of 지표 서빙)`

---

## 완료 기준 (DoD)

- was 테스트 29개(리포지토리 12·어셈블러 9·컨트롤러 8) + 전 모듈 그린
- 실DB: endDate 포함/이전/형식오류 = 200(스냅샷 지표+metricsCapturedAt)/404/400, 파라미터 없으면 기존 응답에 metricsCapturedAt null만 추가
- KST 경계가 테스트로 고정 (UTC 함정 케이스 포함)

## 다루지 않는 것

- 목록(랭킹) API — 태스크 H (다음 착수 예정, 같은 as-of 규칙 재사용)
- 스냅샷 이력 전체 서빙(추이 그래프) — 확정안에서 UI 제외 상태 유지
