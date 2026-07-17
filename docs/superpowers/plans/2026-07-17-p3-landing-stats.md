# P3: 랜딩 통계 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 근거 스펙: [specs/2026-07-15-hypenow-api-spec-alignment-design.md §6](../specs/2026-07-15-hypenow-api-spec-alignment-design.md)

**Goal:** 프론트 랜딩의 "데이터 투명성" 섹션(현재 프론트 상수)을 실데이터 API `GET /v1/stats`(스펙 6.20)로 교체한다.

**Architecture:** 집계는 분석 층(§4-2 "집합 연산은 SQL 뷰") — 1행짜리 서빙 뷰 → 미러 테이블(§4-3 타입 기반) → was가 읽어 envelope+HTTP 캐시로 서빙. 비율의 정수 보정·배열 조립만 was 표현 계층 몫.

**Tech Stack:** SQL 뷰(raw DB `analytics` 스키마) / Flyway(analysis DB, analytics 소유 — 다음 번호 **V32**) / contract-analysis record / SQL 하니스 / was JdbcClient·MockMvc

**작업 위치:** worktree `.worktrees/api-spec`, 브랜치 `feat/p3-stats`(origin/develop 기준 — 이미 생성됨).

**확정된 결정 (사용자 승인 2026-07-17):**
- **모수 = 마이크로 구간 계정(followers 3,000 이상 50,000 미만)만.** 랜딩 카피("뷰티 마이크로 인플루언서 3천~5만")·제품 타깃·스펙의 `followerDistribution` pct 합계 100과 일관. 실데이터 기준 수집 계정 114개 중 55개가 이 구간(나머지 59개는 5만 이상, 3천 미만은 0개).
- `contentsCount`도 **그 마이크로 계정들의 콘텐츠만** 집계(모수 일관).
- `totalViews`/`avgViews`는 **릴스만**(회신표 #16 "조회수 측정 가능 콘텐츠(릴스) 기준"). 실데이터에 피드인데 views가 있는 예외 1건(`Cr8TkbLrIZU`)이 존재하나 릴스 기준이면 자동 제외된다.
- `updatedAt` = 미러 실행 시각(뷰의 `now()`가 미러 시점에 박힘). 구 `materialization_meta`는 07-12에 삭제된 제네릭 미러의 잔재라 쓰지 않는다.
- **유사 콘텐츠(스펙 6.2)는 범위에서 제외** — 사용자 확정(고려 대상 아님). 스펙 6.2·회신표 #3은 "미구현"으로 남긴다.

**전역 규칙:** 한국어 주석·커밋(`feat(analytics):`/`feat(was):`). 미러 규율 — record 컴포넌트(camelCase)→snake_case 이름·**순서**가 뷰 컬럼·DDL과 일치(불일치 시 미러가 시작 시점에 실패). 검증: `cd analytics/test && ./run.sh`(DB 필요: `docker start crawler-postgres-1`), `./gradlew :was:test`.

---

### Task 1: 분석 층 — 랜딩 통계 뷰 + 미러

**Files:**
- Create: `analytics/views/20_landing_stats.sql`
- Create: `analytics/test/20_landing_stats.test.sql`
- Create: `analytics/src/main/resources/db/migration/analysis/V32__landing_stats.sql`
- Create: `contract-analysis/src/main/java/com/celfit/contract/analysis/LandingStats.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/mirror/MirrorConfig.java` (spec 등록)

- [ ] **Step 1: 뷰 작성** — `analytics/views/20_landing_stats.sql`. 항상 정확히 1행(계정이 0명이어도 0으로 채운 1행)을 내야 미러·서빙이 단순해진다:

```sql
-- 랜딩 데이터 투명성 통계 (스펙 6.20) — 항상 1행.
-- 모수 = 마이크로 구간 계정(팔로워 3,000 이상 50,000 미만)과 그 계정들의 콘텐츠 (2026-07-17 사용자 확정) —
-- 랜딩 카피 "뷰티 마이크로 인플루언서(3천~5만)"·제품 타깃·스펙의 분포 합계 100과 모수를 일치시킨다.
-- 조회수 집계는 릴스만 (회신표 #16 — 피드는 조회수 미공개. 구 크롤 잔재로 피드에 값이 있어도 제외).
-- 분포는 구간별 '계정 수'까지만 내고 %·합계 100 보정은 was 표현 계층 몫 (§4-2).
-- updated_at: 뷰 실행 = 미러 실행 시각 → "매주 갱신 중" 표기의 근거.
CREATE OR REPLACE VIEW analytics.v_landing_stats AS
WITH micro AS (
  SELECT username, followers
  FROM analytics.v_base_profile
  WHERE followers >= 3000 AND followers < 50000
),
content AS (
  SELECT c.content_id, lower(c.content_type) AS content_type, d.views
  FROM analytics.v_base_content c
  JOIN analytics.v_base_detail d USING (content_id)
  JOIN micro m ON m.username = c.owner_username
)
SELECT
  (SELECT count(*) FROM content)                                        AS contents_count,
  (SELECT count(*) FROM micro)                                          AS influencers_count,
  COALESCE((SELECT sum(views) FROM content WHERE content_type = 'reels'), 0) AS total_views,
  COALESCE((SELECT round(avg(views)) FROM content WHERE content_type = 'reels'), 0) AS avg_views,
  (SELECT count(*) FROM micro WHERE followers < 10000)                  AS followers_3k_10k,
  (SELECT count(*) FROM micro WHERE followers >= 10000 AND followers < 30000) AS followers_10k_30k,
  (SELECT count(*) FROM micro WHERE followers >= 30000)                 AS followers_30k_50k,
  now()                                                                 AS updated_at;
```

주의: `sum(views)`·`round(avg(views))`는 numeric/bigint 타입이 섞인다 — record가 Long이면 뷰에서 `::bigint` 캐스트가 필요한지 하니스의 `pg_typeof`로 확인하고 맞춘다(P1의 hype_score에서 같은 함정이 있었다).

- [ ] **Step 2: 하니스 테스트** — `analytics/test/20_landing_stats.test.sql`. 기존 하니스 관례(더미 시드 + BEGIN/ROLLBACK, `analytics/test/02_serving.test.sql` 참고)를 따르되, **모수 경계를 픽스처로 고정**한다. 기존 시드의 dummy 계정 팔로워 값을 먼저 확인하고(예: `dummy_a` 5500), 경계 검증용 계정을 추가:

```sql
-- 모수 경계 픽스처: 2,999(제외) / 3,000(포함, 하한 경계) / 49,999(포함) / 50,000(제외, 상한 경계)
INSERT INTO account(id, username, status, first_seen_at) VALUES
 (9301,'dummy_under','QUALIFIED', now()), (9302,'dummy_lower','QUALIFIED', now()),
 (9303,'dummy_upper','QUALIFIED', now()), (9304,'dummy_over','QUALIFIED', now());
INSERT INTO raw_profile(account_id, crawl_run_id, username, followers, payload, captured_at) VALUES
 (9301,9990,'dummy_under', 2999,'{"fullName":"경계 아래"}'::jsonb, now()),
 (9302,9990,'dummy_lower', 3000,'{"fullName":"하한"}'::jsonb, now()),
 (9303,9990,'dummy_upper',49999,'{"fullName":"상한"}'::jsonb, now()),
 (9304,9990,'dummy_over', 50000,'{"fullName":"경계 위"}'::jsonb, now());

DO $$
DECLARE s record;
BEGIN
  SELECT * INTO s FROM analytics.v_landing_stats;
  -- 경계: 3,000 포함 / 2,999·50,000 제외 (dummy_a 5500 + 하한 + 상한 = 시드 계정 중 마이크로 3명)
  ASSERT s.influencers_count = 3,
    'influencers_count != 3 (마이크로 경계 위반): ' || s.influencers_count;
  ASSERT s.followers_3k_10k = 2, 'followers_3k_10k != 2: ' || s.followers_3k_10k;   -- dummy_a(5500) + 하한(3000)
  ASSERT s.followers_10k_30k = 0, 'followers_10k_30k != 0';
  ASSERT s.followers_30k_50k = 1, 'followers_30k_50k != 1';                          -- 상한(49999)
  -- 구간 합 = 전체 마이크로 수 (분포 모수 일관 — was의 합계 100 보정 전제)
  ASSERT s.followers_3k_10k + s.followers_10k_30k + s.followers_30k_50k = s.influencers_count,
    '구간 합 != influencers_count';
  -- 조회수는 릴스만 (피드 views가 있어도 제외)
  ASSERT s.total_views = (SELECT COALESCE(sum(d.views), 0) FROM analytics.v_base_content c
                          JOIN analytics.v_base_detail d USING (content_id)
                          JOIN analytics.v_base_profile p ON p.username = c.owner_username
                          WHERE lower(c.content_type) = 'reels'
                            AND p.followers >= 3000 AND p.followers < 50000),
    'total_views가 릴스·마이크로 모수와 불일치';
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, '뷰가 1행이 아님';
  ASSERT pg_typeof(s.total_views) = 'bigint'::regtype, 'total_views 타입 != bigint';
  ASSERT pg_typeof(s.avg_views) = 'bigint'::regtype, 'avg_views 타입 != bigint';
END $$;
```

시드 계정의 실제 컬럼 구성(`account`/`raw_profile` 스키마)은 기존 `analytics/seed/dummy.sql`을 읽고 그대로 맞춰라 — 위 INSERT의 컬럼 목록은 참고값이며 실제 스키마가 정본이다. 기대값(3/2/0/1)도 시드의 기존 계정 팔로워를 확인해 실제에 맞게 재계산할 것.

- [ ] **Step 3: 하니스 실행** — `cd analytics/test && ./run.sh`. Expected: 전 파일 PASS. 타입 불일치가 나면 뷰에 `::bigint` 캐스트 추가.

- [ ] **Step 4: V32 마이그레이션**

```sql
-- 랜딩 통계 미러 (스펙 6.20) — 1행 테이블. 컬럼 순서 = analytics.v_landing_stats = LandingStats record.
CREATE TABLE landing_stats (
    contents_count    bigint,
    influencers_count bigint,
    total_views       bigint,
    avg_views         bigint,
    followers_3k_10k  bigint,
    followers_10k_30k bigint,
    followers_30k_50k bigint,
    updated_at        timestamptz
);
```

- [ ] **Step 5: 계약 record**

```java
/**
 * 랜딩 통계 1행 (미러: analytics.v_landing_stats → landing_stats).
 * 모수는 마이크로 구간 계정(팔로워 3,000~49,999)과 그 콘텐츠 — 랜딩 카피·스펙 분포와 모수 일치(2026-07-17 확정).
 * totalViews·avgViews는 릴스만(피드는 조회수 미공개). followers* 는 구간별 계정 수 —
 * %·합계 100 보정은 소비자(was) 표현 계층 몫. updatedAt = 미러 실행 시각.
 */
public record LandingStats(Long contentsCount, Long influencersCount, Long totalViews, Long avgViews,
		Long followers3k10k, Long followers10k30k, Long followers30k50k, OffsetDateTime updatedAt) {
}
```

주의: 미러의 snake_case 변환 규칙은 **숫자-문자 경계에 언더스코어를 넣지 않는다**(`recent12AvgLikeCount` → `recent12_avg_like_count` 선례). `followers3k10k` → `followers3k10k`?? — 이 규칙으로 뷰 컬럼명이 정확히 무엇이 되는지 **MirrorSpec의 toSnakeCase 구현을 읽고 확인**한 뒤, 변환 결과와 뷰·DDL 컬럼명이 일치하도록 셋을 맞춰라(불일치 시 미러가 시작 시점에 실패하므로 Step 7에서 즉시 드러난다). 필요하면 record 필드명을 `followersBand3k10k` 등으로 바꾸지 말고 **뷰·DDL 쪽을 변환 결과에 맞추는 것**이 규율(§4-3)이다.

- [ ] **Step 6: 미러 등록** — `MirrorConfig.mirrorRegistry()`에 `new MirrorSpec<>("analytics.v_landing_stats", "landing_stats", LandingStats.class)` 추가(기존 spec 목록의 관례·순서 따라).

- [ ] **Step 7: 미러 실행 검증** — `./gradlew :analytics:bootRun`. Expected: V32 적용 + 미러 성공(landing_stats 1행), 컬럼 대조 실패 없음. 이후 확인:

```bash
docker exec crawler-postgres-1 psql -U crawler -d analysis -c "SELECT * FROM landing_stats"
```
실데이터 기대: influencers_count=55, 분포 22/25/8(합 55), total_views는 마이크로 계정 릴스 합, updated_at은 방금 시각.

- [ ] **Step 8: Commit** — `feat(analytics): 랜딩 통계 뷰·미러 — 마이크로 모수 집계 (스펙 6.20)`

---

### Task 2: was — `GET /v1/stats` + 마무리(문서·PR)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/stats/V1StatsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/stats/V1StatsRepository.java`
- Create: `was/src/main/java/com/celfit/was/v1/stats/StatsResponse.java`
- Test: `was/src/test/java/com/celfit/was/v1/stats/V1StatsControllerTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/stats/StatsResponseTest.java` (분포 보정 단위)

**계약(스펙 6.20):** 인증 Public. envelope data = `{contentsCount, influencersCount, totalViews, avgViews, followerDistribution: [{range, pct}], updatedAt(ISO Z)}`. `Cache-Control: public, max-age=3600`(주간 배치 갱신 전제).

- [ ] **Step 1: 응답 record + 분포 보정 테스트(TDD)**

```java
/** 스펙 6.20 랜딩 통계. followerDistribution의 range는 6.1 follower enum과 동일 문자열, pct 합계는 항상 100. */
public record StatsResponse(Long contentsCount, Long influencersCount, Long totalViews, Long avgViews,
		List<Band> followerDistribution, String updatedAt) {

	public record Band(String range, int pct) {
	}
}
```

분포 보정은 **최대 잔여(largest remainder) 방식** — 단순 반올림은 합이 99/101이 될 수 있어 스펙("합계 100")을 깬다. 정적 헬퍼로 두고 단위 테스트:

```java
class StatsResponseTest {

	@Test
	void 분포_비율은_합계가_항상_100이다() {
		// 22/25/8 (합 55) → 40.0/45.45/14.55 → 최대 잔여 보정 → 40/45/15
		List<StatsResponse.Band> bands = StatsResponse.distribution(22, 25, 8);
		assertThat(bands).extracting("range").containsExactly("3k-10k", "10k-30k", "30k-50k");
		assertThat(bands).extracting("pct").containsExactly(40, 45, 15);
		assertThat(bands.stream().mapToInt(StatsResponse.Band::pct).sum()).isEqualTo(100);
	}

	@Test
	void 반올림이_어긋나는_분포도_합계_100을_지킨다() {
		// 1/1/1 → 33.33 셋 → 단순 반올림이면 99 → 보정으로 34/33/33
		List<StatsResponse.Band> bands = StatsResponse.distribution(1, 1, 1);
		assertThat(bands.stream().mapToInt(StatsResponse.Band::pct).sum()).isEqualTo(100);
	}

	@Test
	void 계정이_없으면_전_구간_0이다() {
		// 분모 0 방어 — 합계 100 규칙보다 "데이터 없음"이 우선(0/0/0)
		List<StatsResponse.Band> bands = StatsResponse.distribution(0, 0, 0);
		assertThat(bands).extracting("pct").containsExactly(0, 0, 0);
	}
}
```

- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :was:test --tests '*StatsResponseTest'`. Expected: 컴파일 실패.

- [ ] **Step 3: 구현** — `distribution(long b3k, long b10k, long b30k)`: 합이 0이면 전부 0. 아니면 각 구간의 정확 비율을 내림한 정수 + 잔여가 큰 구간부터 1씩 분배해 합 100.

리포지토리는 1행 조회(미러가 항상 1행이지만 **미러 미실행 상태면 0행**일 수 있으므로 Optional):

```java
@Repository
public class V1StatsRepository {

	private final JdbcClient jdbcClient;

	public V1StatsRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 랜딩 통계 1행(미러). 미러 미실행이면 empty → 컨트롤러가 404. */
	public Optional<LandingStats> find() {
		return jdbcClient.sql("""
				SELECT contents_count, influencers_count, total_views, avg_views,
				       followers_3k_10k, followers_10k_30k, followers_30k_50k, updated_at
				FROM landing_stats
				LIMIT 1
				""").query(LandingStats.class).optional();
	}
}
```

(SELECT 컬럼명은 Task 1에서 확정된 실제 DDL 컬럼명에 맞출 것 — record 필드명 변환 규칙 확인 결과 반영.)

컨트롤러:

```java
/** 6.20 랜딩 통계 — 인증 Public. 주간 배치 갱신이라 강한 HTTP 캐시를 허용한다(스펙 6.20). */
@RestController
public class V1StatsController {

	private final V1StatsRepository repository;

	public V1StatsController(V1StatsRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/stats")
	public ResponseEntity<ApiResponse<StatsResponse>> stats() {
		LandingStats s = repository.find()
				.orElseThrow(() -> V1ApiException.notFound("통계를 찾을 수 없습니다."));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
				.body(ApiResponse.ok(StatsResponse.from(s)));
	}
}
```

`StatsResponse.from(LandingStats)`: updatedAt은 ISO Z(`DateTimeFormatter.ISO_INSTANT`, 기존 v1 관례와 동일), followerDistribution은 `distribution(...)`.

- [ ] **Step 4: 컨트롤러 테스트** — `@WebMvcTest` + `@MockitoBean V1StatsRepository`(기존 v1 컨트롤러 테스트의 @Import 구성 그대로): 200 envelope 구조(`$.data.followerDistribution[0].range`=`3k-10k`, `$.data.updatedAt`), **`Cache-Control` 헤더**(`public, max-age=3600`) 단언, **비로그인 접근 가능**(Public — permitAll 확인), 미러 미실행(empty) → 404 NOT_FOUND envelope.

- [ ] **Step 5: 전체 테스트** — `./gradlew test`. Expected: 전 모듈 PASS.

- [ ] **Step 6: 실 DB 스모크** — was 기동 후:

```bash
curl -si 'localhost:8081/v1/stats' | head -20
```
검증: 200, `Cache-Control: max-age=3600, public`, data의 6필드, `followerDistribution` pct 합 100, `influencersCount`=55(실데이터), `updatedAt` ISO Z. 확인 후 was 종료.

- [ ] **Step 7: 문서** —
  - ARCHITECTURE.md §5 "API 스펙 정렬 트랙" 표: **P3 행을 수정** — 내용에서 유사 콘텐츠를 빼고 `/v1/stats`만 남기며 상태 ✅("07-17 개통"). 유사 콘텐츠 제외 사실을 행 내용에 명시.
  - §7 결정 기록 맨 위에 1행: `| 2026-07-17 | **P3 랜딩 통계 개통 + 유사 콘텐츠 제외 확정** — GET /v1/stats(스펙 6.20)를 분석 층 1행 뷰·미러(landing_stats V32)로 서빙, 강한 HTTP 캐시(1시간). **모수는 마이크로 구간 계정(팔로워 3천~5만)과 그 콘텐츠로 통일** — 랜딩 카피·스펙 분포 합계 100과 일치(수집 114 중 55). 조회수는 릴스만(회신표 #16). 분포 %·합계 100 보정은 was 표현 계층(최대 잔여). updatedAt은 미러 실행 시각. **유사 콘텐츠(스펙 6.2)는 제품 고려 대상이 아니라 구현하지 않기로 확정** — 스펙 6.2·회신표 #3은 미구현으로 남김 | [plans/archive/2026-07-17-p3-landing-stats.md](docs/superpowers/plans/archive/2026-07-17-p3-landing-stats.md) |`
  - 이 계획 문서 상태 헤더 `✅ 구현/실행/반영됨` + `git mv`로 `plans/archive/`로 이동(상대링크 `../../specs/` 보정).
- [ ] **Step 8: 커밋 & PR** — 코드 커밋 `feat(was): GET /v1/stats 랜딩 통계 (스펙 6.20)`, 문서 커밋 `docs: P3 개통 반영 — 유사 콘텐츠 제외 확정·ARCHITECTURE §5/§7 갱신`(Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>). push 후 `gh pr create --base develop --title "feat: P3 랜딩 통계 — GET /v1/stats (유사 콘텐츠 제외 확정)"`. 본문: 요약(모수 결정 근거 포함)·스모크 결과·유사 콘텐츠 제외 명시·프론트 액션(랜딩 상수 → API 교체). 끝에 "🤖 Generated with [Claude Code](https://claude.com/claude-code)".

---

## Self-Review 노트

- 스펙 커버: 6.20 전 필드(contentsCount·influencersCount·totalViews·avgViews·followerDistribution·updatedAt) + 캐시. 6.2(유사)는 **의도적 제외**(사용자 확정).
- 회신표 갱신: #16(모수)은 이 계획으로 확정, #3(유사도 테이블)은 "미구현 확정"으로 §7에 기록.
- 프론트 액션: 랜딩 상수(`src/components/landing/stats.ts`) → `/v1/stats` 교체. "3천~5만" 카피는 이제 모수와 정확히 일치.
