# 브랜드 AI 어시스턴트 툴·한계 재설계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료(2026-08-31) · 스펙: docs/superpowers/specs/archive/2026-08-31-brand-ai-tool-limits-redesign-design.md

**Goal:** 브랜드 AI 어시스턴트의 집합 연산을 서버로 옮기고(조합형 aggregate_posts·댓글 배치·파생 지표 서버 계산), 가드레일 한계를 뿌리(시간 90초·토큰 10만)에서 재도출한다.

**Architecture:** 모든 변경은 `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/` 패키지 안이다. BrandAiToolbox의 aggregatePosts를 GroupAcc 누산기 기반으로 재작성해 groupBy 축을 얻고(스칼라 경로는 단일 그룹으로 통합), 파생 지표(도달 배수·참여율)를 서버 계산으로 고정한다. BrandAiAgent는 상수 재도출 + limitReached 원인 노출 + 브랜드 컨텍스트 선주입. 컨트롤러는 90초 계약·분당 10회·limitReached 응답 필드.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3(`tools.jackson.*`), Testcontainers(PostgreSQL), JdbcClient.

**전제 (Task 0에서 확인):**
- 구현 베이스는 `origin/feature/brand-monitoring-ai-assistant-poc` (277de176, SSE 스트리밍 포함, 클린).
- 새 워크트리 `.worktrees/brand-ai-tool-redesign`, 새 브랜치 `feat/brand-ai-tool-limits-redesign`.
- 테스트 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수(CLAUDE.md 함정).
- 테스트는 `./gradlew :was:test --tests "..."` 단위로. 전체 `:was:test`는 마지막에 1회.
- PR은 열지 않는다(사용자 지시). push까지만.

---

### Task 0: 작업 공간 준비

**Files:** 없음 (git 작업만)

- [ ] **Step 0-1: 워크트리·브랜치 생성**

```bash
git -C /Users/woomin/Project/hypenow-backend fetch origin feature/brand-monitoring-ai-assistant-poc
git -C /Users/woomin/Project/hypenow-backend worktree add .worktrees/brand-ai-tool-redesign -b feat/brand-ai-tool-limits-redesign origin/feature/brand-monitoring-ai-assistant-poc
```

Expected: 워크트리 생성, HEAD=277de176.

- [ ] **Step 0-2: 스펙·계획 문서 커밋 이식**

스펙 커밋(99e8397b)과 이 계획 문서 커밋을 `claude/optimistic-bassi-877f4d` 브랜치에서 cherry-pick한다:

```bash
git -C /Users/woomin/Project/hypenow-backend/.worktrees/brand-ai-tool-redesign cherry-pick 99e8397b <계획문서커밋SHA>
```

Expected: docs/superpowers/specs/archive/2026-08-31-brand-ai-tool-limits-redesign-design.md와 이 계획 파일이 구현 브랜치에 존재.

- [ ] **Step 0-3: 빌드 정상 확인**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/brand-ai-tool-redesign && ./gradlew :was:compileJava -q
```

Expected: BUILD SUCCESSFUL.

---

### Task 1: 한계 상수 재도출 + limitReached 원인 노출 (BrandAiAgent)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgent.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgentTest.java`

**배경(스펙 §4·§5):** 1차 제약을 진짜 자원(시간·토큰)으로, 회수는 안전망으로 강등. 강제 답변이 일어난 원인(`"time"`/`"budget"`)을 AgentOutcome으로 노출해 컨트롤러(Task 7)가 구조 고지 필드를 만든다.

- [ ] **Step 1-1: 실패하는 테스트 작성**

BrandAiAgentTest에 추가(기존 테스트의 fake client·fixed clock 관용구를 그대로 따른다 — 파일 상단 기존 헬퍼 확인):

```java
@Test
void 툴_상한_도달_강제_답변이면_limitReached가_budget이다() {
	// 기존 "툴 상한 도달" 테스트와 같은 픽스처를 쓰되 상한이 24로 올랐으므로 25회 툴 호출을 유도한다.
	// (기존 테스트가 8회 기준이면 해당 테스트도 24 기준으로 갱신)
	BrandAiAgent.AgentOutcome outcome = /* 상한 도달 시나리오 실행 */;
	assertThat(outcome.limitReached()).isEqualTo("budget");
}

@Test
void 벽시계_예산_소진_강제_답변이면_limitReached가_time이다() {
	// mutable clock을 TIME_BUDGET_MILLIS 넘게 전진시킨 뒤 다음 턴 진입
	assertThat(outcome.limitReached()).isEqualTo("time");
}

@Test
void 정상_완료면_limitReached가_null이다() {
	assertThat(outcome.limitReached()).isNull();
}
```

- [ ] **Step 1-2: 테스트 실패 확인**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiAgentTest" 2>&1 | tail -20
```

Expected: 컴파일 에러(limitReached 심볼 없음).

- [ ] **Step 1-3: 구현**

BrandAiAgent.java 변경:

```java
/** 툴 호출 안전망(스펙 §4, 2026-08-31 재도출) - 1차 제약이 아니라 폭주 방지선이다. 정상 질문은
 * 시간·토큰 예산이 먼저 끊는다. */
static final int MAX_TOOL_CALLS = 24;
/** 누적 프롬프트 토큰 예산(스펙 §4) - 비용 뿌리(질문당 천장 ~50원) 직접 환산. */
static final int PROMPT_TOKEN_BUDGET = 100_000;
/** 벽시계 예산(스펙 §4) - 시간 뿌리 90초(컨트롤러 응답 계약)에서 마무리 여유를 뺀 값. */
static final long TIME_BUDGET_MILLIS = 85_000L;

/** AgentOutcome.limitReached 값 - 구조 고지(스펙 §5)용. */
public static final String LIMIT_BUDGET = "budget";
public static final String LIMIT_TIME = "time";
```

두 run() 오버로드의 cap 판정을 원인 보존형으로 바꾼다:

```java
boolean toolCapped = toolCalls.size() >= MAX_TOOL_CALLS;
String limitCause = toolCapped || promptTokens >= PROMPT_TOKEN_BUDGET ? LIMIT_BUDGET
		: clock.millis() >= deadline ? LIMIT_TIME : null;
boolean capped = limitCause != null;
```

AgentOutcome record 마지막에 `String limitReached` 컴포넌트 추가:

```java
public record AgentOutcome(String answer, List<String> referencedShortCodes, List<ReferenceInfo> references,
		List<AiChatLogEntry.ToolCallLog> toolCalls, int promptTokens, int outputTokens,
		Long brandId, String outcome, boolean answered, String limitReached) {
}
```

생성 지점별 limitReached 값:
- 정상 텍스트 반환(`capped ? OUTCOME_TOOL_CAP : OUTCOME_OK` 분기): `capped ? limitCause : null`
- 강제 답변 턴 병리(OUTCOME_LLM_CALL_CAP, capped 블록): `limitCause`
- 루프 끝 안전망(OUTCOME_LLM_CALL_CAP): `LIMIT_BUDGET`
- BLOCKED·ABORTED·FALLBACK(비cap): `null`

**클래스 상단 javadoc의 55초·8회 언급, TIME_BUDGET_MILLIS 주석의 "55초"도 재도출 값·근거(스펙 §4)로 갱신한다.** BrandAiConfig의 "에이전트 벽시계 예산(55초)" 주석도 85초로 갱신.

- [ ] **Step 1-4: 기존 테스트 컴파일 복구**

AgentOutcome 생성자를 직접 부르는 테스트·프로덕션 호출부 전부에 limitReached 인자 추가(대부분 null). 기존 "툴 8회 상한" 계열 테스트는 24 기준으로 갱신(테스트 상수 참조 `BrandAiAgent.MAX_TOOL_CALLS`를 쓰고 있으면 자동 추종 — 확인 후 하드코딩만 수정).

- [ ] **Step 1-5: 테스트 통과 확인**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiAgentTest" 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 1-6: 커밋**

```bash
git add -A && git commit -m "feat(was): AI 챗 한계 재도출 - 시간·토큰이 1차 제약, 회수는 안전망(24회) + limitReached 원인 노출"
```

---

### Task 2: 컨트롤러 시간 계약 90초 + 분당 10회

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiMessagesController.java`
- Create: `was/src/main/resources/db/migration/app/V<UTC타임스탬프>__ai_chat_per_minute_limit_10.sql`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiMessagesControllerTest.java`

- [ ] **Step 2-1: 상수 변경**

```java
/** 응답 상한(스펙 §4, 2026-08-31 재도출) - 시간 뿌리 90초. SSE emitter 타임아웃·완결 경로
 * future.get·followUps 잔여 예산이 전부 이 값에서 파생된다. */
private static final int RESPONSE_TIMEOUT_SECONDS = 90;
...
static final int DEFAULT_PER_MINUTE_LIMIT = 10;
```

- [ ] **Step 2-2: 분당 한도 마이그레이션 작성**

파일명은 실행 시점에 `date -u +%Y%m%d%H%M%S`로 채번(**반드시 UTC** — CLAUDE.md):

```sql
-- 분당 질문 상한 기준값 상향 5 → 10 (2026-08-31 한계 재도출, 스펙 §4 - 사용자 지시)
-- 운영자가 런타임에 손댄 값은 존중한다 - 시드 기본값(5) 그대로인 행만 올린다.
UPDATE app.app_setting SET value = '10'
WHERE key = 'ai.chat.per-minute-limit' AND value = '5';
```

- [ ] **Step 2-3: 테스트 갱신·확인**

V1BrandAiMessagesControllerTest에서 분당 5회·60초를 참조하는 하드코딩을 10회·90초 기준으로 갱신(상수 참조면 자동 추종). 실행:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.V1BrandAiMessagesControllerTest" 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2-4: 커밋**

```bash
git add -A && git commit -m "feat(was): AI 챗 응답 계약 90초·분당 10회 상향 - 한계 뿌리 재도출(스펙 §4)"
```

---

### Task 3: aggregate_posts 일반화 - keyword·groupBy·orderBy·limit + 파생 지표 서버 계산

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolbox.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecs.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolboxIntegrationTest.java`

**핵심 설계(스펙 §3-1·§3-2):** 스칼라 집계와 그룹 집계를 하나의 누산기(GroupAcc) 루프로 통합한다 — groupBy 생략 = 단일 그룹 "all"이 기존 스칼라 페이로드로 나가는 구조라 하위호환이 자연 성립한다. 캡션 매칭 로직은 searchPosts와 공유하도록 헬퍼로 추출한다(DRY).

- [ ] **Step 3-1: 실패하는 테스트 작성**

BrandAiToolboxIntegrationTest에 추가(기존 insertTaggedPost·스냅샷 시드 헬퍼 사용, author_profile 시드는 파일 내 기존 관용구 참조 — 없으면 `INSERT INTO author_profile (username, full_name, followers, is_verified) VALUES ...` 직접):

```java
@Test
void aggregate_posts_groupBy_author는_작성자별_집계와_파생지표를_서버가_계산해_정렬한다() {
	// author A: 릴스 2건(조회수 1000, 3000), 팔로워 100 → avgViews 2000, reachMultiple 20.0
	// author B: 릴스 1건(조회수 9000), 팔로워 9000 → avgViews 9000, reachMultiple 1.0
	// orderBy=reachMultiple이면 A가 B보다 앞이어야 한다(모델 암산이 아니라 서버 정렬)
	ObjectNode args = objectMapper.createObjectNode();
	args.put("brandId", myBrandId);
	args.put("groupBy", "author");
	args.put("orderBy", "reachMultiple");
	AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS, args);
	JsonNode payload = objectMapper.readTree(result.payloadJson());
	assertThat(payload.path("totalGroups").asInt()).isEqualTo(2);
	assertThat(payload.path("groups").get(0).path("key").asString()).isEqualTo("author_a");
	assertThat(payload.path("groups").get(0).path("reachMultiple").asDouble()).isEqualTo(20.0);
	assertThat(payload.path("groups").get(1).path("reachMultiple").asDouble()).isEqualTo(1.0);
}

@Test
void aggregate_posts_groupBy_author는_팔로워_미상이면_reachMultiple이_null이고_뒤로_정렬된다() { ... }

@Test
void aggregate_posts_limit은_그룹을_자르되_totalGroups는_전체를_보고한다() {
	// 작성자 3명 시드, limit=2 → groups 2행, totalGroups 3
}

@Test
void aggregate_posts_keyword는_캡션_매칭_게시물만_모수로_삼는다() { ... }

@Test
void aggregate_posts_groupBy_month는_KST_달력_월로_버킷한다() {
	// 8/31 23:00 KST(=8월)와 9/1 01:00 KST(=9월) 게시물이 다른 버킷
}

@Test
void aggregate_posts_groupBy_없으면_기존_스칼라_페이로드_그대로다() {
	// 기존 필드(postCount·totalViews·avgViews·viewsNote...) 존재 확인 - 하위호환 고정
}
```

- [ ] **Step 3-2: 테스트 실패 확인**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolboxIntegrationTest" 2>&1 | tail -20
```

Expected: 신규 테스트 FAIL (groupBy 미지원 - payload에 groups 없음).

- [ ] **Step 3-3: 캡션 매칭 헬퍼 추출**

searchPosts 본문의 매칭 로직(풀 SQL + 레거시 인메모리 두 갈래)을 추출:

```java
/** 창 안 refs 중 캡션이 normalizedQuery에 매칭되는 것만(스펙 §3-1 keyword 필터) - searchPosts와
 * aggregatePosts가 공유한다. 풀 게시물은 SQL ILIKE, 과도기 레거시 카드는 인메모리 비교(기존 두 갈래
 * 로직을 그대로 옮긴 것). */
private List<PostRef> captionMatchedRefs(BrandWindow window, String normalizedQuery) {
	List<PostRef> poolRefs = new ArrayList<>();
	List<PostRef> legacyRefs = new ArrayList<>();
	for (PostRef ref : window.inWindow()) {
		(window.index().poolCodes().contains(ref.shortcode()) ? poolRefs : legacyRefs).add(ref);
	}
	Set<String> poolCodes = poolRefs.stream().map(PostRef::shortcode)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	Set<String> matchedPoolCodes = brandReadRepository.findCaptionMatches(poolCodes, normalizedQuery);
	String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);
	List<PostRef> matched = new ArrayList<>();
	for (PostRef ref : poolRefs) {
		if (matchedPoolCodes.contains(ref.shortcode())) {
			matched.add(ref);
		}
	}
	for (PostRef ref : legacyRefs) {
		BrandPostResponse legacy = window.index().legacyByCode().get(ref.shortcode());
		String caption = legacy == null ? null : legacy.caption();
		if (caption != null && caption.replace(" ", "").toLowerCase(Locale.ROOT).contains(lowerQuery)) {
			matched.add(ref);
		}
	}
	return matched;
}
```

searchPosts 본문을 이 헬퍼 호출로 교체(동작 무변화 - 기존 search 테스트가 회귀 가드).

- [ ] **Step 3-4: GroupAcc 누산기·그룹 키 함수 구현**

```java
/** 집계 상수(스펙 §3-1) - 그룹 행은 캡션 없는 순수 숫자 행이라 상한을 목록 툴(30)보다 높게 잡는다. */
private static final int DEFAULT_GROUP_LIMIT = 10;
private static final int MAX_GROUP_LIMIT = 50;
private static final Set<String> GROUP_BY_VALUES = Set.of("author", "month", "week", "sponsorship", "mediaType");

/** 그룹 1개의 누산기(스펙 §3-1·§3-2) - 스칼라 경로도 단일 그룹("all")로 이 누산기를 쓴다. */
private static final class GroupAcc {
	final String key;
	long postCount;
	long reelsCount;
	long feedCount;
	long totalViews;               // 릴스만(피드는 조회수 항상 null)
	long viewsSampleCount;
	long totalLikes;
	long likesSampleCount;
	long totalComments;
	long commentsSampleCount;
	long reelsComments;            // engagementRate 분자 - 릴스 게시물의 댓글 합(분모와 모수 일치)
	long reelsCommentsSampleCount;
	String topShortCode;
	Long topViews;
	Long followers;                // author 축 전용(배치 조회로 나중에 채움)

	GroupAcc(String key) {
		this.key = key;
	}

	void add(String shortCode, String contentType, Long views, Long likes, Long comments) {
		postCount++;
		boolean isReels = BrandPostAssembler.CONTENT_TYPE_REELS.equalsIgnoreCase(contentType);
		if (isReels) {
			reelsCount++;
		} else {
			feedCount++;
		}
		if (isReels && views != null) {
			totalViews += views;
			viewsSampleCount++;
			if (topViews == null || views > topViews) {
				topViews = views;
				topShortCode = shortCode;
			}
		}
		if (isReels && comments != null) {
			reelsComments += comments;
			reelsCommentsSampleCount++;
		}
		if (likes != null) {
			totalLikes += likes;
			likesSampleCount++;
		}
		if (comments != null) {
			totalComments += comments;
			commentsSampleCount++;
		}
	}

	Double avgViews() {
		return viewsSampleCount == 0 ? null : (double) totalViews / viewsSampleCount;
	}

	Double avgLikes() {
		return likesSampleCount == 0 ? null : (double) totalLikes / likesSampleCount;
	}

	Double avgComments() {
		return commentsSampleCount == 0 ? null : (double) totalComments / commentsSampleCount;
	}

	/** 도달 배수(스펙 §3-2) = 릴스 평균 조회수 ÷ 팔로워. 팔로워 null·0이면 null(계산 불가 - 제외가
	 * 아니라 유지, 정렬 시 nullsLast). */
	Double reachMultiple() {
		Double avg = avgViews();
		return followers == null || followers <= 0 || avg == null ? null : avg / followers;
	}

	/** 참여율(스펙 §3-2) = 릴스 댓글 합 ÷ 릴스 조회수 합. 분모 0·표본 없음이면 null. */
	Double engagementRate() {
		return totalViews <= 0 || reelsCommentsSampleCount == 0 ? null : (double) reelsComments / totalViews;
	}
}

/** groupBy 축별 그룹 키(스펙 §3-1) - null 반환은 "키를 정할 수 없는 게시물"(작성자 미상·업로드일
 * 미상)로, 집계에서 빼고 skippedNoKey로 센다. 기간 버킷은 KST 달력 기준(월은 1일~말일, 주는 월요일
 * 시작) - "지난달"의 자연스러운 의미와 일치시키고 롤링 30일 해석을 배제한다(스펙 §3-1). */
private static Function<PostRef, String> groupKeyFunction(String groupBy) {
	if (groupBy == null) {
		return ref -> "all";
	}
	return switch (groupBy) {
		case "author" -> PostRef::authorUsername;
		case "month" -> ref -> ref.uploadedOn() == null ? null
				: String.format(Locale.ROOT, "%04d-%02d", ref.uploadedOn().getYear(),
						ref.uploadedOn().getMonthValue());
		case "week" -> ref -> ref.uploadedOn() == null ? null
				: ref.uploadedOn().with(java.time.DayOfWeek.MONDAY).toString();
		case "sponsorship" -> ref -> ref.sponsorship() == null ? "unknown" : ref.sponsorship();
		case "mediaType" -> ref -> ref.contentType() == null ? "unknown"
				: ref.contentType().toLowerCase(Locale.ROOT);
		default -> null; // 호출부가 검증 실패로 처리
	};
}
```

주의: PostRef.uploadedOn()은 이미 KST 달력일 LocalDate다(기존 창 판정이 그 전제) - 별도 타임존 변환 불필요.

- [ ] **Step 3-5: aggregatePosts 재작성**

기존 스칼라 루프를 GroupAcc 기반으로 교체:

```java
private AiToolResult aggregatePosts(ToolSession session, long userId, JsonNode args) {
	// (brandId 검증·scopeMismatch·ownedBrand·findAccount·resolveWindow까지 기존 그대로)

	String groupBy = args.path("groupBy").isMissingNode() || args.path("groupBy").isNull() ? null
			: args.path("groupBy").asString();
	if (groupBy != null && !GROUP_BY_VALUES.contains(groupBy)) {
		return error("groupBy는 author·month·week·sponsorship·mediaType 중 하나여야 합니다.");
	}
	String orderBy = args.path("orderBy").asString("postCount");
	int groupLimit = Math.clamp(args.path("limit").asInt(DEFAULT_GROUP_LIMIT), 1, MAX_GROUP_LIMIT);

	// keyword 필터(스펙 §3-1) - search_posts와 같은 정규화(공백 흡수)·같은 매칭 헬퍼
	String keyword = args.path("keyword").asString("").replace(" ", "");
	List<PostRef> universe = keyword.isEmpty() ? window.inWindow()
			: captionMatchedRefs(window, keyword);

	// 지표 배치 조회(기존 로직 유지 - universe 기준으로 좁힘)
	Set<String> poolCodes = universe.stream().map(PostRef::shortcode)
			.filter(window.index().poolCodes()::contains)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	Map<String, BrandReadRepository.LatestMetricsRow> metricsByCode = brandReadRepository
			.findLatestMetricsByShortCodes(poolCodes).stream()
			.collect(Collectors.toMap(BrandReadRepository.LatestMetricsRow::shortCode, Function.identity(),
					(a, b) -> a));

	Function<PostRef, String> keyFn = groupKeyFunction(groupBy);
	Map<String, GroupAcc> groups = new LinkedHashMap<>();
	long skippedNoKey = 0;
	for (PostRef ref : universe) {
		String key = keyFn.apply(ref);
		if (key == null) {
			skippedNoKey++;
			continue;
		}
		String code = ref.shortcode();
		String contentType;
		Long views;
		Long likes;
		Long comments;
		if (window.index().poolCodes().contains(code)) {
			BrandReadRepository.LatestMetricsRow row = metricsByCode.get(code);
			contentType = row == null ? null : row.contentType();
			views = row == null ? null : row.views();
			likes = row == null ? null : row.likes();
			comments = row == null ? null : row.comments();
		} else {
			BrandPostResponse legacy = window.index().legacyByCode().get(code);
			TrackingItemResponse.SnapshotResponse latest = legacy == null ? null : legacy.latestSnapshot();
			contentType = legacy == null ? null : legacy.contentType();
			views = latest == null ? null : latest.views();
			likes = latest == null ? null : latest.likes();
			comments = latest == null ? null : latest.comments();
		}
		groups.computeIfAbsent(key, GroupAcc::new).add(code, contentType, views, likes, comments);
	}

	// author 축 팔로워 join(스펙 §3-2) - 기존 배치 조회 재사용
	if ("author".equals(groupBy) && !groups.isEmpty()) {
		Map<String, AuthorRow> byUsername = findAuthorsByUsernameBatched(
				new LinkedHashSet<>(groups.keySet())).stream()
				.collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
		for (GroupAcc acc : groups.values()) {
			AuthorRow author = byUsername.get(acc.key);
			acc.followers = author == null ? null : author.followers();
		}
	}

	if (groupBy == null) {
		return scalarAggregatePayload(link, window, keyword, groups, universe.size());
	}
	return groupedAggregatePayload(link, window, groupBy, orderBy, groupLimit, keyword, groups,
			universe.size(), skippedNoKey);
}
```

스칼라 페이로드(기존 필드명·모양 유지 - Step 3-1의 하위호환 테스트가 가드):

```java
/** groupBy 없는 기존 스칼라 페이로드 - 단일 GroupAcc("all")를 기존 필드명 그대로 편다. keyword가
 * 있으면 명시해 모델이 "키워드 매칭 게시물 기준"임을 답변에 밝힐 수 있게 한다. */
private AiToolResult scalarAggregatePayload(BrandLinkRow link, BrandWindow window, String keyword,
		Map<String, GroupAcc> groups, int universeSize) {
	GroupAcc acc = groups.getOrDefault("all", new GroupAcc("all"));
	ObjectNode payload = objectMapper.createObjectNode();
	payload.put("brandId", link.brandId());
	payload.put("since", window.since().toString());
	payload.put("window", window.windowKind());
	if (!keyword.isEmpty()) {
		payload.put("keyword", keyword);
	}
	payload.put("postCount", universeSize);
	payload.put("reelsCount", acc.reelsCount);
	payload.put("feedCount", acc.feedCount);
	payload.put("viewsNote", "피드 게시물은 조회수가 항상 null이라 조회수 집계·평균은 릴스만 대상입니다.");
	payload.put("totalViews", acc.totalViews);
	payload.put("avgViews", acc.avgViews());
	payload.put("viewsSampleCount", acc.viewsSampleCount);
	payload.put("totalLikes", acc.totalLikes);
	payload.put("avgLikes", acc.avgLikes());
	payload.put("likesSampleCount", acc.likesSampleCount);
	payload.put("totalComments", acc.totalComments);
	payload.put("avgComments", acc.avgComments());
	payload.put("commentsSampleCount", acc.commentsSampleCount);
	List<String> codes;
	if (acc.topShortCode != null) {
		ObjectNode topPost = payload.putObject("topPost");
		topPost.put("shortCode", acc.topShortCode);
		topPost.put("views", acc.topViews);
		codes = List.of(acc.topShortCode);
	} else {
		codes = List.of();
	}
	return AiToolResult.ok(payload.toString(), universeSize, codes);
}
```

그룹 페이로드(정렬은 서버 - 스펙 §3-2, nullsLast·내림차순):

```java
private AiToolResult groupedAggregatePayload(BrandLinkRow link, BrandWindow window, String groupBy,
		String orderBy, int groupLimit, String keyword, Map<String, GroupAcc> groups, int universeSize,
		long skippedNoKey) {
	Function<GroupAcc, Double> sortKey = switch (orderBy) {
		case "totalViews" -> acc -> (double) acc.totalViews;
		case "avgViews" -> GroupAcc::avgViews;
		case "avgLikes" -> GroupAcc::avgLikes;
		case "avgComments" -> GroupAcc::avgComments;
		case "reachMultiple" -> GroupAcc::reachMultiple;
		case "engagementRate" -> GroupAcc::engagementRate;
		default -> acc -> (double) acc.postCount; // postCount 및 알 수 없는 값 폴백
	};
	List<GroupAcc> ordered = groups.values().stream()
			.sorted(Comparator.comparing(sortKey, Comparator.nullsLast(Comparator.reverseOrder())))
			.toList();
	List<GroupAcc> page = ordered.stream().limit(groupLimit).toList();

	ObjectNode payload = objectMapper.createObjectNode();
	payload.put("brandId", link.brandId());
	payload.put("since", window.since().toString());
	payload.put("window", window.windowKind());
	payload.put("groupBy", groupBy);
	payload.put("orderBy", orderBy);
	if (!keyword.isEmpty()) {
		payload.put("keyword", keyword);
	}
	payload.put("postCount", universeSize);
	payload.put("totalGroups", groups.size());
	payload.put("returnedGroups", page.size());
	if (skippedNoKey > 0) {
		payload.put("skippedNoKey", skippedNoKey);
	}
	payload.put("viewsNote", "피드 게시물은 조회수가 항상 null이라 조회수·도달배수·참여율은 릴스만 대상입니다. "
			+ "reachMultiple·engagementRate는 서버가 계산한 값이니 그대로 인용하세요.");
	ArrayNode groupsNode = payload.putArray("groups");
	List<String> codes = new ArrayList<>();
	for (GroupAcc acc : page) {
		ObjectNode node = groupsNode.addObject();
		node.put("key", acc.key);
		node.put("postCount", acc.postCount);
		node.put("reelsCount", acc.reelsCount);
		node.put("feedCount", acc.feedCount);
		node.put("totalViews", acc.totalViews);
		node.put("avgViews", acc.avgViews());
		node.put("viewsSampleCount", acc.viewsSampleCount);
		node.put("totalLikes", acc.totalLikes);
		node.put("avgLikes", acc.avgLikes());
		node.put("totalComments", acc.totalComments);
		node.put("avgComments", acc.avgComments());
		if ("author".equals(groupBy)) {
			node.put("followers", acc.followers);
			node.put("reachMultiple", acc.reachMultiple());
			node.put("engagementRate", acc.engagementRate());
		}
		if (acc.topShortCode != null) {
			node.put("topPostShortCode", acc.topShortCode);
			if (codes.size() < MAX_GROUP_LIMIT) {
				codes.add(acc.topShortCode);
			}
		}
	}
	// rowCount는 전체 그룹 수 - search_posts의 totalMatches 관용구와 동일하게 "정확한 총 수"를 로그에 남긴다.
	return AiToolResult.ok(payload.toString(), groups.size(), codes);
}
```

주의: `ObjectNode.put(String, Double)`은 null 허용 오버로드다(기존 avgViews 코드가 같은 관용구) - null이면 JSON null.

- [ ] **Step 3-6: BrandAiToolSpecs.AGGREGATE_POSTS 선언 갱신**

```java
new AiToolSpec(AGGREGATE_POSTS,
		"브랜드 게시물의 수·좋아요/댓글/조회수 합계·평균을 지정한 기간(창) 안 전체를 대상으로 집계한다. "
				+ "groupBy를 주면 작성자별·월별·주별·협찬여부별·미디어타입별로 묶어 그룹별 집계와 서버가 계산한 "
				+ "파생 지표(author 축: followers·reachMultiple=릴스 평균 조회수÷팔로워·engagementRate=릴스 댓글 합÷조회수 합)를 "
				+ "orderBy 기준 내림차순 정렬로 돌려준다. 작성자 랭킹·기간 비교·협찬 vs 오가닉 비교는 반드시 이 툴 "
				+ "1회로 해결한다 - list_posts로 모아 get_author를 반복 호출하며 직접 계산하지 마라. "
				+ "reachMultiple·engagementRate·totalGroups 등 숫자는 직접 재계산하지 말고 그대로 인용한다. "
				+ "keyword를 주면 캡션에 그 키워드가 있는 게시물만 모수로 삼는다. "
				+ "조회수·도달배수·참여율은 릴스만 집계한다(피드는 조회수가 항상 없다). "
				+ "limit 초과분은 잘리고 totalGroups로 전체 수를 알려주니 '전체 N개 중 상위 M개 기준'을 답변에 명시하라. "
				+ "days 생략 시 수집 기간 전체를 대상으로 한다. 사용자가 기간을 명시했을 때만 days를 넘겨라.",
		"""
		{"type":"object","properties":{
		  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
		  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 수집된 기간 전체, 최대 365일"},
		  "keyword":{"type":"string","description":"캡션 필터 - 이 키워드가 캡션에 있는 게시물만 집계. 공백 유무는 흡수"},
		  "groupBy":{"type":"string","enum":["author","month","week","sponsorship","mediaType"],
		             "description":"묶는 축. author=작성자별, month/week=KST 달력 월/주별(기간 비교용), sponsorship=협찬여부별, mediaType=릴스/피드별. 생략하면 전체 하나로 집계"},
		  "orderBy":{"type":"string","enum":["postCount","totalViews","avgViews","avgLikes","avgComments","reachMultiple","engagementRate"],
		             "description":"그룹 정렬 기준(내림차순, 서버 정렬). 생략하면 postCount"},
		  "limit":{"type":"integer","description":"돌려줄 그룹 상위 N. 생략하면 10, 최대 50. 사용자가 N명/N개를 명시하면 그 값을 그대로 넘겨라"}
		},"required":["brandId"]}
		""")
```

- [ ] **Step 3-7: 테스트 통과 확인**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolboxIntegrationTest" --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolSpecsTest" 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL (신규 + 기존 aggregate·search 테스트 전부).

- [ ] **Step 3-8: 커밋**

```bash
git add -A && git commit -m "feat(was): aggregate_posts 일반화 - groupBy·orderBy·keyword·limit + 도달배수·참여율 서버 계산(스펙 §3-1·§3-2)"
```

---

### Task 4: get_comments 배치화

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolbox.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecs.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolboxIntegrationTest.java`

**계약(스펙 §3-3):** `shortCodes` 배열(최대 5개) 신규, 기존 `shortCode` 단건도 하위호환 수용(모델 이력 호환). 단건이면 기존 동작 그대로(기본 20·최대 50), 복수면 게시물당 기본 10·최대 20에 전체 총 상한 50.

- [ ] **Step 4-1: 실패하는 테스트 작성**

```java
@Test
void get_comments는_shortCodes_배열로_여러_게시물을_한_번에_돌려준다() {
	// 게시물 2건에 댓글 각 3건 시드 → posts 배열 2원소, 각각 comments 3건
}

@Test
void get_comments_배열은_게시물당_상한과_총_상한을_지킨다() {
	// 5개 게시물 × 댓글 15건씩, perPostLimit 미지정 → 게시물당 10건, 총 50건
}

@Test
void get_comments는_기존_shortCode_단건_호출과_하위호환된다() {
	// shortCode 문자열만 넘긴 기존 관용구 → 기존 페이로드 모양 유지
}

@Test
void get_comments_배열에_소유하지_않은_게시물이_섞이면_그_게시물만_빠진다() {
	// 남의 게시물 shortCode 포함 → posts에서 제외 + notFound 배열로 보고, 전체 실패 아님
}
```

- [ ] **Step 4-2: 테스트 실패 확인** (Task 3과 동일 명령) Expected: 신규 FAIL.

- [ ] **Step 4-3: 구현**

```java
private static final int MAX_COMMENT_POSTS = 5;
private static final int PER_POST_DEFAULT_COMMENTS = 10;
private static final int PER_POST_MAX_COMMENTS = 20;
/** 배치 호출 전체 총 상한 - 기존 MAX_COMMENTS(50)와 같은 값이라 토큰 예산이 불변이다(스펙 §3-3). */
private static final int TOTAL_BATCH_COMMENTS = 50;

private AiToolResult getComments(ToolSession session, long userId, JsonNode args) {
	// 하위호환(스펙 §3-3) - shortCodes 배열이 없으면 기존 단건 경로 그대로.
	JsonNode codesNode = args.path("shortCodes");
	if (!codesNode.isArray() || codesNode.isEmpty()) {
		return getCommentsSingle(session, userId, args);
	}
	List<String> shortCodes = new ArrayList<>();
	for (JsonNode codeNode : codesNode) {
		String code = codeNode.asString("");
		if (!code.isBlank() && !shortCodes.contains(code)) {
			shortCodes.add(code);
		}
		if (shortCodes.size() >= MAX_COMMENT_POSTS) {
			break;
		}
	}
	if (shortCodes.isEmpty()) {
		return error("shortCodes가 비어 있습니다.");
	}
	int perPost = Math.clamp(args.path("limit").asInt(PER_POST_DEFAULT_COMMENTS), 1, PER_POST_MAX_COMMENTS);

	ObjectNode payload = objectMapper.createObjectNode();
	ArrayNode postsNode = payload.putArray("posts");
	ArrayNode notFound = objectMapper.createArrayNode();
	List<String> okCodes = new ArrayList<>();
	int total = 0;
	for (String shortCode : shortCodes) {
		if (total >= TOTAL_BATCH_COMMENTS) {
			break;
		}
		Optional<BrandPostResponse> found = hydrateOwnedPost(session, userId, shortCode, true);
		if (found.isEmpty()) {
			notFound.add(shortCode);
			continue;
		}
		List<TrackingItemResponse.PostCommentResponse> rows = found.get().recentComments().stream()
				.limit(Math.min(perPost, TOTAL_BATCH_COMMENTS - total)).toList();
		total += rows.size();
		okCodes.add(shortCode);
		ObjectNode postNode = postsNode.addObject();
		postNode.put("shortCode", shortCode);
		postNode.put("returned", rows.size());
		ArrayNode comments = postNode.putArray("comments");
		for (TrackingItemResponse.PostCommentResponse row : rows) {
			ObjectNode node = comments.addObject();
			node.put("author", row.author());
			node.put("body", truncate(row.text(), COMMENT_BODY_LENGTH));
			node.put("likeCount", row.likes());
			node.put("commentedAt", row.createdAt());
			node.put("ownerReplyText", row.reply() == null ? null : row.reply().text());
		}
	}
	payload.put("totalReturned", total);
	if (!notFound.isEmpty()) {
		payload.set("notFound", notFound);
	}
	if (okCodes.isEmpty()) {
		return error("어느 게시물도 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
	}
	return AiToolResult.ok(payload.toString(), total, okCodes);
}

/** 기존 단건 경로(하위호환) - 기존 getComments 본문을 이름만 바꿔 그대로 유지한다. */
private AiToolResult getCommentsSingle(ToolSession session, long userId, JsonNode args) {
	// (기존 getComments 본문 그대로)
}
```

- [ ] **Step 4-4: GET_COMMENTS 선언 갱신**

```java
new AiToolSpec(GET_COMMENTS,
		"게시물의 댓글을 최신순으로 돌려준다. shortCodes 배열로 최대 5개 게시물을 한 번에 조회할 수 있다 - "
				+ "여러 게시물의 댓글 여론을 종합할 때 게시물마다 따로 호출하지 말고 반드시 배열로 묶어 1회 호출한다. "
				+ "배열 호출은 게시물당 기본 10건(최대 20건), 전체 최대 50건. 단일 게시물만 볼 때는 shortCode 하나로 "
				+ "호출하면 기본 20건(최대 50건)이다.",
		"""
		{"type":"object","properties":{
		  "shortCodes":{"type":"array","items":{"type":"string"},
		                "description":"인스타그램 게시물 shortCode 목록(최대 5개) - 여러 게시물 댓글 종합 시 사용"},
		  "shortCode":{"type":"string","description":"단일 게시물 shortCode(shortCodes 대신 사용 가능)"},
		  "limit":{"type":"integer","description":"게시물당 가져올 댓글 수. 배열이면 기본 10·최대 20, 단건이면 기본 20·최대 50"}
		},"required":[]}
		""")
```

주의: required가 비면 안 되는 스키마 검증이 있는지 BrandAiToolSpecsTest 확인 - 문제되면 `"required":[]` 대신 required 키 생략.

- [ ] **Step 4-5: 테스트 통과 확인** (Task 3과 동일 명령) Expected: BUILD SUCCESSFUL.

- [ ] **Step 4-6: 커밋**

```bash
git add -A && git commit -m "feat(was): get_comments 배치화 - shortCodes 최대 5건·총 50건, 단건 하위호환(스펙 §3-3)"
```

---

### Task 5: 브랜드 컨텍스트 선주입 + 프롬프트 개정(병렬 호출·산수 금지)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolbox.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgent.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiPrompt.java`
- Test: `BrandAiAgentTest.java`, `BrandAiToolboxIntegrationTest.java`

- [ ] **Step 5-1: 실패하는 테스트 작성**

```java
// BrandAiToolboxIntegrationTest
@Test
void brandContextLine은_브랜드_메타를_한_줄로_요약한다() {
	String line = toolbox.brandContextLine(userId, myBrandId);
	assertThat(line).contains("brandId=" + myBrandId).contains("mybrand").contains("1000");
}

@Test
void brandContextLine은_링크가_없으면_빈_문자열이다() {
	assertThat(toolbox.brandContextLine(otherUserId, myBrandId)).isEmpty();
}

// BrandAiAgentTest - fake client가 받은 systemPrompt를 캡처하는 기존 관용구 사용
@Test
void 세션_brandId가_있으면_시스템_프롬프트에_브랜드_컨텍스트가_선주입된다() {
	// fake toolbox의 brandContextLine이 "[컨텍스트]..." 반환하게 스텁
	// run(userId, messages, brandId, null, "") 후 캡처된 systemPrompt에 그 문구 포함 확인
}
```

주의: BrandAiAgentTest가 BrandAiToolbox를 어떻게 대역화하는지 먼저 확인(mock/서브클래스) - brandContextLine을 스텁 가능한 형태로 맞춘다.

- [ ] **Step 5-2: 테스트 실패 확인** Expected: 컴파일 에러(brandContextLine 없음).

- [ ] **Step 5-3: BrandAiToolbox에 컨텍스트 메서드 추가**

```java
/**
 * 브랜드 컨텍스트 선주입용 한 줄 요약(스펙 §6) - 컨트롤러가 이미 소유 검증한 brandId의 메타를
 * 시스템 프롬프트에 미리 실어, 질문 100%에서 발생하던 list_brands 첫 왕복을 없앤다. 툴이 아니라
 * 서버 내부 호출이라 툴 회수를 소모하지 않는다. 링크·계정이 없으면 빈 문자열(선주입 생략 -
 * 모델은 기존처럼 list_brands로 폴백한다).
 */
public String brandContextLine(long userId, long brandId) {
	Optional<BrandLinkRow> linkOpt = linkRepository.findActiveByUserAndBrand(userId, brandId);
	if (linkOpt.isEmpty()) {
		return "";
	}
	BrandLinkRow link = linkOpt.get();
	StringBuilder sb = new StringBuilder("\n\n[브랜드 컨텍스트] 이 대화의 브랜드: brandId=")
			.append(link.brandId()).append(", username=@").append(link.username())
			.append(", 구분=").append(link.accountType())
			.append(", 수집 기간=").append(link.collectionMonths()).append("개월");
	brandReadRepository.findAccount(link.brandId()).ifPresent(account -> sb.append(", 팔로워=")
			.append(account.followers()).append(", 게시물 수=").append(account.mediaCount()));
	sb.append(". 이 brandId로 바로 다른 툴을 호출하세요. list_brands 호출은 불필요합니다.");
	return sb.toString();
}
```

- [ ] **Step 5-4: BrandAiAgent 두 run() 오버로드에 선주입**

두 오버로드 모두 basePrompt 조립을 다음으로 교체(중복 방지를 위해 private 헬퍼로 추출):

```java
/** 시스템 프롬프트 조립(스펙 §6) - 세션 brandId가 있으면 브랜드 컨텍스트를 선주입한다. */
private String buildBasePrompt(long userId, Long sessionBrandId, String extraSystemPrompt) {
	String context = sessionBrandId == null ? "" : toolbox.brandContextLine(userId, sessionBrandId);
	return BrandAiPrompt.SYSTEM + context + (extraSystemPrompt == null ? "" : extraSystemPrompt);
}
```

- [ ] **Step 5-5: BrandAiPrompt 규칙 개정**

규칙 9를 확장하고 규칙 13·14를 서버 값 인용으로 교체:

```
9. 같은 툴을 같은 인자로 반복 호출하지 않습니다. 서로 의존하지 않는 여러 조회가 필요하면 한 번의 턴에 묶어 함께 호출합니다. 필요한 정보가 모이면 바로 답합니다.
...
13. 좋아요를 순위·평균의 기준으로 쓰지 않습니다. 참여율은 aggregate_posts가 돌려주는 engagementRate(릴스 댓글 합÷조회수 합, 서버 계산)를 그대로 인용하고 직접 계산하지 않습니다. 서버 값이 없는 맥락에서 부득이 계산할 때만 댓글수/조회수로 계산하되 산식을 답변에 명시합니다.
14. 도달 배수는 aggregate_posts(groupBy=author)가 돌려주는 reachMultiple(릴스 평균 조회수÷팔로워, 서버 계산)를 그대로 인용하고 직접 계산하지 않습니다. reachMultiple이 null이면 팔로워 수가 없거나 0인 경우이니 계산 불가하다고 밝히세요. 순위·정렬도 서버가 orderBy로 정렬해 준 순서를 그대로 따릅니다.
```

- [ ] **Step 5-6: 테스트 통과 확인** (BrandAiAgentTest + BrandAiToolboxIntegrationTest) Expected: BUILD SUCCESSFUL.

- [ ] **Step 5-7: 커밋**

```bash
git add -A && git commit -m "feat(was): 브랜드 컨텍스트 선주입·병렬 호출 유도·산수 서버 위임 프롬프트(스펙 §6·§3-2)"
```

---

### Task 6: 프리셋 지시문 재작성

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiPresets.java`

- [ ] **Step 6-1: 5개 지시문 교체**

```java
private static final Map<String, String> INSTRUCTIONS = Map.of(
		"efficient_influencers", """

				[프리셋] 사용자가 "효율 좋은 인플루언서" 프리셋을 선택했습니다.
				aggregate_posts(groupBy=author, orderBy=reachMultiple, limit=10) 한 번으로 작성자별 집계를 받아,
				서버가 정렬해 준 순서 그대로 상위 게시자를 표(5열 이하)로 안내하세요. reachMultiple이 null인
				게시자는 팔로워 정보가 없어 계산 불가라고 밝히세요. get_author를 게시자마다 반복 호출하지 마세요.
				""",
		"top_posts", """

				[프리셋] 사용자가 "인기 게시물" 프리셋을 선택했습니다. list_posts(sort=performance_desc)로
				조회수 기준 상위 게시물을 찾아 5열 이하 표로 정리해 답하세요.
				""",
		"sponsored_vs_organic", """

				[프리셋] 사용자가 "협찬 vs 오가닉 비교" 프리셋을 선택했습니다.
				aggregate_posts(groupBy=sponsorship) 한 번으로 협찬 여부별 집계를 받아 비교하세요.
				참여율은 서버가 준 engagementRate를 그대로 인용합니다.
				""",
		"tagged_posts_analysis", """

				[프리셋] 사용자가 "태그된 게시물 분석" 프리셋을 선택했습니다. aggregate_posts로 규모·추이를
				잡고 list_posts·search_posts로 브랜드에 태그된 게시물의 최근 흐름과 특징(주제·언급 빈도 등)을
				정리해 답하세요.
				""",
		"paid_amplify", """

				[프리셋] 사용자가 "유료 증폭 후보" 프리셋을 선택했습니다. list_posts(sort=performance_desc)나
				aggregate_posts로 이미 성과가 좋은 오가닉 게시물을 찾아 유료 증폭(부스팅) 후보로 제안하세요. 실제 광고
				집행 여부는 데이터에 없으니 추천 근거는 실측 지표(조회수·참여율 등)로만 듭니다.
				""");
```

- [ ] **Step 6-2: 컴파일·기존 테스트 확인**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.*" 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6-3: 커밋**

```bash
git add -A && git commit -m "feat(was): 프리셋 지시문을 조합형 집계 경로로 재작성 - 프리셋 1번 상한 실패 해소(스펙 §3-4)"
```

---

### Task 7: limitReached 구조 고지 (FE additive 필드)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiMessagesResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiMessagesController.java`
- Test: `V1BrandAiMessagesControllerTest.java`

- [ ] **Step 7-1: 실패하는 테스트 작성**

```java
@Test
void 강제_답변이면_응답에_limitReached가_실린다() {
	// agent 대역이 limitReached="budget"인 AgentOutcome 반환하게 스텁
	// POST /messages 응답 JSON의 limitReached == "budget" 확인
}

@Test
void 정상_완료면_limitReached가_null이다() { ... }
```

- [ ] **Step 7-2: 테스트 실패 확인** Expected: 컴파일 에러 또는 필드 부재 FAIL.

- [ ] **Step 7-3: 구현**

AiMessagesResponse 마지막에 필드 추가(FE에 additive - 무시 가능):

```java
/** @param limitReached 예산 도달로 부분 답변이 된 경우 그 원인("time"=시간, "budget"=조회 예산),
 *                      정상 완료면 null(스펙 §5 구조 고지 - FE 협의 전이라 additive로만 싣는다). */
public record AiMessagesResponse(String conversationId, String messageId, String content,
		List<FollowUp> followUps, List<Reference> references, String limitReached) {
```

컨트롤러 완결 경로: `new AiMessagesResponse(..., references, outcome.limitReached())`.
SSE done 이벤트: `doneNode.put("limitReached", outcome.limitReached());`

- [ ] **Step 7-4: 테스트 통과 확인** Expected: BUILD SUCCESSFUL.

- [ ] **Step 7-5: 커밋**

```bash
git add -A && git commit -m "feat(was): AI 챗 응답에 limitReached 구조 고지 추가 - FE additive(스펙 §5)"
```

---

### Task 8: 전체 검증·문서·마무리

**Files:**
- Modify: `DECISIONS.md` (구현 브랜치)
- Modify: `docs/superpowers/specs/archive/2026-08-31-brand-ai-tool-limits-redesign-design.md` (상태 헤더)
- Move: 이 계획 파일 → `docs/superpowers/plans/archive/`

- [ ] **Step 8-1: was 전체 테스트**

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. 실패 시 대량 실패면 DOCKER_HOST부터 의심(CLAUDE.md 함정).

- [ ] **Step 8-2: 마이그레이션 안전 검사**

```bash
./deploy/scripts/check-migration-safety.sh 2>/dev/null || true
```

(스크립트 경로·존재 확인 후 실행 - 없으면 신규 마이그레이션이 UPDATE 1건뿐이라 expand-contract 위반 없음을 눈으로 확인.)

- [ ] **Step 8-3: 문서 갱신**

- DECISIONS.md 맨 위에 결정 추가: 2026-08-31 툴·한계 재설계(조합형 aggregate·한계 뿌리 재도출·B 기각·A→eval→모델 순서), 스펙 링크.
- 스펙 상태 헤더를 `🟢 활성 · ✅ 구현됨(2026-08-31)`으로.
- 이 계획 파일을 `docs/superpowers/plans/archive/`로 이동(상태 헤더 `✅ 실행 완료`).

- [ ] **Step 8-4: 커밋·push (PR 금지)**

```bash
git add -A && git commit -m "docs: AI 챗 툴·한계 재설계 결정 기록·스펙 상태 갱신·계획 아카이브"
git push -u origin feat/brand-ai-tool-limits-redesign
```

**PR은 열지 않는다(사용자 지시 2026-08-31).**

---

## 계획 자체 검토 결과

- 스펙 §3-1(aggregate 일반화)→Task 3, §3-2(파생 지표)→Task 3·5, §3-3(댓글 배치)→Task 4, §3-4(프리셋)→Task 6, §4(한계)→Task 1·2, §5(구조 고지)→Task 1·7, §6(왕복 절감)→Task 5, §7·§8(eval·모델)→구현 범위 밖(후속). 커버리지 갭 없음.
- limitReached 타입: AgentOutcome(Task 1)과 AiMessagesResponse(Task 7) 모두 String("time"/"budget"/null) - 일치.
- GroupAcc·groupKeyFunction·captionMatchedRefs는 Task 3에서 정의되고 다른 태스크가 참조하지 않음 - 순서 의존은 Task 1(AgentOutcome)→Task 7뿐. Task 순서대로 실행하면 안전.
