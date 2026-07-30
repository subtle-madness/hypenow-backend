# 계정 하입 스코어 (발굴 목록) Implementation Plan

> 상태: ✅ 실행됨 (PR #164 머지, 2026-07-29)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인플루언서 발굴 목록(`GET /v1/influencers`) 카드에 계정 하입 스코어(`hypeScore`, 최근 12창 콘텐츠 hype_score 단순 평균)를 노출하고 `sort=hype` 정렬을 추가한다.

**Architecture:** 스펙 `docs/superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md`의 B안 — 산식은 analytics `v_account_summaries`에 두고(기존 `analytics.hype_score()` 함수 재사용, 신선도 `now()` 기준 = `v_contents`와 동일), 미러 3곳 동시 변경(뷰 SQL + analysis Flyway DDL + `AccountSummary` record) 후 was는 `su.avg_hype_score` 읽기와 정렬만 추가한다.

**Tech Stack:** PostgreSQL 뷰/SQL 하니스, Flyway(analysis DB), Java 21 record, Spring JdbcClient, Testcontainers.

**작업 위치:** 공유 체크아웃이므로 git worktree(`superpowers:using-git-worktrees`)에서 `feat/influencer-avg-hype-score` 브랜치로 작업, develop 대상 PR로 마무리. 통합 테스트는 Docker 필요(colima 환경이면 `DOCKER_HOST`·`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 설정 — 과거 세션 관례).

**전제 확인(실행 시작 시):**
- analysis 마이그레이션 최신 번호가 V40인지 재확인(`ls analytics/src/main/resources/db/migration/analysis/ | sort -V | tail -1`). V41이 선점됐으면 이 계획의 V41을 다음 번호로 치환(V18 경합 전례).
- SQL 하니스는 실데이터 postgres 컨테이너 필요: `docker start crawler-postgres-1` (이름 다르면 `PG_CONTAINER`로 오버라이드).

---

### Task 1: analytics 뷰 — `v_account_summaries.avg_hype_score` (SQL 하니스 TDD)

**Files:**
- Modify: `analytics/views/10_account_detail.sql` (win CTE·base CTE·최종 SELECT)
- Test: `analytics/test/10_account_detail.test.sql` (끝에 추가)

- [ ] **Step 1: 하니스 테스트 먼저 추가 (실패 확인용)**

`analytics/test/10_account_detail.test.sql` 파일 **맨 끝**에 추가:

```sql

-- avg_hype_score (스펙 2026-07-29-influencer-avg-hype-score): 최근창 콘텐츠 hype_score 단순 평균.
-- 기대값을 고정하지 않고 v_contents(랭킹)와의 항등식으로 검증한다 — 같은 함수·같은 핀·같은 now()(트랜잭션 고정)라
-- 두 경로가 반드시 일치해야 하고, 시간이 지나 신선도 감쇠로 절대값이 변해도 테스트가 안 깨진다.
DO $$
BEGIN
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
         BETWEEN 0 AND 100,
    'summaries a avg_hype_score not in 0..100';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
       = (SELECT round(avg(c.hype_score))::bigint
          FROM analytics.v_contents c
          JOIN analytics.v_account_content_series s ON s.short_code = c.short_code
          WHERE s.account_handle = 'dummy_a'),
    'summaries a avg_hype_score != v_contents 창 평균 (같은 함수·핀·기준시각이어야 함)';
END $$;

-- 점수 불가 창 계정: 릴스인데 조회수 없는 스냅샷만 → hype NULL → 계정 avg_hype_score NULL.
-- 다른 테스트 파일과 시드(dummy.sql)를 공유하므로 시드는 건드리지 않고 이 파일 안에서만 추가한다
-- (위 DO 블록의 기존 카운트 단언들은 이 INSERT보다 먼저 실행돼 영향 없음).
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990006, 'dummy_h', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts)
VALUES (99990109, 'dummy_h1', 'REELS', 'dummy_h', 99990006, timestamptz '2026-06-01 09:00:00+09',
        'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990006, 99990000, 'HIKER_MOBILE', 'dummy_h', 3000,
  '{"status":"ok","user":{"username":"dummy_h","full_name":"더미 에이치","follower_count":3000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');
-- play_count·ig_play_count 둘 다 없음 → views NULL → 릴스 hype NULL (핀 우선순위 ④ 최신으로 여전히 핀됨)
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
VALUES (99990006, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_h1","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"caption":{"text":"cap h1"}}}]}}'::jsonb,
  timestamptz '2026-06-07 12:00:00+09');
SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_summaries WHERE handle = 'dummy_h') = 1,
    'summaries h row missing (점수 불가 콘텐츠도 창에는 있어야 함)';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_h') IS NULL,
    'summaries h avg_hype_score not null (창 전체 점수 불가면 NULL)';
  ASSERT (SELECT avg_likes FROM analytics.v_account_summaries WHERE handle = 'dummy_h') = 100,
    'summaries h avg_likes != 100 (다른 집계는 살아야 함)';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/10_account_detail.test.sql
```

Expected: FAIL — `column "avg_hype_score" does not exist`.

- [ ] **Step 3: 뷰 구현**

`analytics/views/10_account_detail.sql` 세 군데 수정.

win CTE에 `content_type` 통과 (기존 22~28행):

```sql
win AS (
  SELECT owner_username, content_id, uploaded_at, content_type, likes, comments_count, views, ad_marked,
         profile_followers AS followers,
         row_number() OVER (PARTITION BY owner_username ORDER BY uploaded_at ASC, content_id ASC) AS seq,
         count(*)     OVER (PARTITION BY owner_username)                                          AS n
  FROM analytics.v_account_recent
),
```

base CTE의 `avg_comments` 집계 다음 줄에 추가:

```sql
         round(avg(analytics.hype_score(lower(content_type), views, likes, comments_count,
                                        followers,
                                        extract(epoch FROM (now() - uploaded_at)) / 86400.0)))::bigint
                                                            AS avg_hype_score,
```

최종 SELECT의 **맨 끝**(`avg_interval_days` 뒤)에 추가 — 미러 1:1 컬럼 순서 규칙(뷰=DDL=record, ALTER는 끝에 붙음):

```sql
  END AS avg_interval_days,
  b.avg_hype_score
```

뷰 상단 주석(14~16행 부근, `-- 계정 1행 요약.` 블록)에 한 줄 추가:

```sql
-- avg_hype_score: 창 콘텐츠 hype_score(02_serving 함수, now() 신선도 — v_contents와 동일) 단순 평균.
-- 점수 불가(hype NULL) 콘텐츠는 avg가 자연 제외, 전무하면 NULL (스펙 2026-07-29-influencer-avg-hype-score).
```

- [ ] **Step 4: 하니스 통과 확인 (전체 — 다른 테스트 회귀 포함)**

```bash
analytics/test/run.sh
```

Expected: `ALL GREEN` (10 테스트 신규 단언 포함 전체 통과).

- [ ] **Step 5: Commit**

```bash
git add analytics/views/10_account_detail.sql analytics/test/10_account_detail.test.sql
git commit -m "feat(analytics): 계정 요약에 avg_hype_score 추가 — 최근창 hype 단순 평균, v_contents 항등 검증"
```

---

### Task 2: 계약 record + analysis Flyway (FlywaySchemaTest TDD)

**Files:**
- Modify: `contract-analysis/src/main/java/com/celfit/contract/analysis/AccountSummary.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V42__account_summaries_avg_hype_score.sql` (번호는 전제 확인 결과 기준)
- Test: 기존 `analytics/src/test/java/com/celfit/analytics/mirror/FlywaySchemaTest.java` (수정 없음 — DDL=record 대조 가드)

- [ ] **Step 1: record에 필드 먼저 추가 (실패 확인용)**

`AccountSummary.java` — 마지막 컴포넌트 `avgIntervalDays` 뒤에 `Long avgHypeScore` 추가, javadoc 한 줄 추가:

```java
/**
 * 인플루언서 상세 계정 요약 1행 (미러: analytics.v_account_summaries → account_summaries).
 * celfit-front AccountReport의 결정(비LLM) 지표 — 산식은 스펙 2026-07-13-c1-account-detail-design.md §3.
 * metric: 'views'|'likes' — 조회수 데이터 부족 계정의 기준 지표 폴백. 트렌드·광고 비교가 이 축을 따른다.
 * avgErPct: 계정 평균 ER(팔로워 분모, %) — 게시물 ER(조회수 분모)과 정의가 다르다.
 * trendDirection 'flat'은 "변화 ±threshold 이내"와 "비교 불가(표본 부족)"를 겸한다 —
 * 후자는 trendOlderAvg가 NULL이고 trendChangePct가 0인 것으로 구분.
 * avgHypeScore: 최근창 콘텐츠 hype_score 단순 평균(0~100), 점수 가능 콘텐츠 없으면 NULL
 * (스펙 2026-07-29-influencer-avg-hype-score).
 */
public record AccountSummary(String handle, Long followers, Long followsCount, Long postsCount,
		String biography, Long analyzedCount, Long viewsCount, String metric, Long avgViews,
		BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
		String trendDirection, Integer trendChangePct, Long trendOlderAvg, Long trendNewerAvg,
		Long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
		Long comparisonOrganicCount, Long comparisonAdCount, OffsetDateTime lastAdPostedAt,
		OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays, Long avgHypeScore) {
}
```

- [ ] **Step 2: 가드 테스트 실패 확인 (Docker 필요)**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.mirror.FlywaySchemaTest"
```

Expected: FAIL — `account_summaries 컬럼이 record와 다름` (record에만 avg_hype_score 존재).

- [ ] **Step 3: 마이그레이션 작성**

Create `analytics/src/main/resources/db/migration/analysis/V42__account_summaries_avg_hype_score.sql`:

```sql
-- 계정 하입 스코어(스펙 2026-07-29-influencer-avg-hype-score): 최근창 콘텐츠 hype_score 단순 평균(0~100).
-- 산식 정본은 analytics 뷰(10_account_detail.sql v_account_summaries) — 여기는 미러 저장 형상만.
ALTER TABLE account_summaries ADD COLUMN avg_hype_score bigint;
```

- [ ] **Step 4: 가드 테스트 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.mirror.FlywaySchemaTest"
```

Expected: PASS. (뷰=record 경계는 MirrorJob 런타임 가드 — Task 1에서 뷰 끝에 같은 이름·순서로 추가했으므로 정합.)

- [ ] **Step 5: Commit**

```bash
git add contract-analysis/src/main/java/com/celfit/contract/analysis/AccountSummary.java analytics/src/main/resources/db/migration/analysis/V42__account_summaries_avg_hype_score.sql
git commit -m "feat(analytics): account_summaries.avg_hype_score 미러 형상 — V42 + 계약 record"
```

---

### Task 3: was — `sort=hype` + `CardRow.avgHypeScore` 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryQuery.java:16`
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepository.java` (SELECT 2곳·CardRow·orderBy)
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryQueryTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepositoryTest.java`
- Test(컴파일 수정): `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryAssemblerTest.java`, `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryControllerTest.java`, `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java:94-95`

주의: 기존 테스트 두 곳이 `sort=hype`를 **"잘못된 enum" 예시**로 쓰고 있다(QueryTest 74행, DiscoveryControllerTest 70행) — 유효값으로 승격되므로 무효 예시를 `zzz`로 교체해야 한다.

- [ ] **Step 1: 테스트 먼저 수정 (실패 확인용)**

`V1InfluencerDiscoveryQueryTest.java` — `잘못된_enum과_음수_경계는_400`의 sort 케이스(74~75행)를 교체하고 유효 케이스 추가:

```java
	// 교체: "hype"는 이제 유효 — 무효 예시는 zzz
	assertThatThrownBy(() -> of(null, null, null, null, null, null, null, null, "zzz", null, null))
			.isInstanceOf(V1ApiException.class);
```

새 테스트 메서드 추가:

```java
	@Test
	void sort_hype는_허용된다() {
		assertThat(of(null, null, null, null, null, null, null, null, "hype", null, null)
				.sort()).isEqualTo("hype");
	}
```

`V1InfluencerDiscoveryRepositoryTest.java` — ① `setUpTables`의 `account_summaries` DDL 사본에 컬럼 추가(`avg_comments` 뒤):

```java
				CREATE TABLE account_summaries (
				    handle             text PRIMARY KEY,
				    followers          bigint,
				    follows_count      bigint,
				    posts_count        bigint,
				    biography          text,
				    avg_views          bigint,
				    views_per_follower numeric,
				    avg_er_pct         numeric,
				    avg_likes          bigint,
				    avg_comments       bigint,
				    avg_hype_score     bigint,
				    last_posted_at     timestamptz
				)
```

② 시드 INSERT에 값 추가 (glow 72 / calm 80 / mute NULL / tiny 45 — hype 정렬이 reach 정렬과 다른 순서가 되도록 설계):

```java
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, follows_count, posts_count,
				  biography, avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  avg_hype_score, last_posted_at) VALUES
				  ('glow', 20000, 380, 214, E'수분크림 기록\\n문의는 DM', 50000, 12.42, 4.0, 3000, 150,
				   72, now() - interval '1 day'),
				  ('calm', 30000, 100, 90, '차분한 후기', 30000, 5.0, 2.0, 500, 30,
				   80, now() - interval '10 days'),
				  ('mute', 40000, 50, 40, NULL, NULL, NULL, 1.0, 300, 10,
				   NULL, now() - interval '40 days'),
				  ('tiny', 1000, 10, 20, '새싹', 2000, 2.0, 3.0, 25, 3,
				   45, now() - interval '5 days')""");
```

③ `기본_reach_정렬과_카드_필드`에 필드 단언 추가:

```java
		assertThat(glow.avgHypeScore()).isEqualTo(72);
		assertThat(mute.avgHypeScore()).isNull(); // 점수 가능 콘텐츠 없는 계정
```

④ 새 정렬 테스트 추가:

```java
	@Test
	void 정렬_hype는_avg_hype_score_내림차순_NULL_마지막() {
		var hype = query(null, null, null, null, null, null, null, null, "hype", null, null);
		assertThat(repository.findCards(hype)).extracting(CardRow::handle)
				.containsExactly("calm", "glow", "tiny", "mute");
	}
```

- [ ] **Step 2: CardRow 생성자 호출처 3곳 컴파일 수정 (avgComments 다음 자리에 인자 삽입)**

`V1InfluencerDiscoveryAssemblerTest.java` 24~26행:

```java
		return new CardRow(handle, "이름", "/img/p.jpg", followers, 214L, 380L,
				"소개\n둘째줄", "태그라인", new BigDecimal("12.42"), new BigDecimal("3.84"),
				413200L, 10370L, 152L, 72L, 3L);
```

같은 파일 44~45행:

```java
		var bare = new CardRow("mute", "이름", null, 40000L, 40L, 50L, null, null,
				null, null, null, 300L, 10L, null, 0L);
```

`V1InfluencerDiscoveryControllerTest.java` 36~38행:

```java
		return new CardRow(handle, "글로우", "/img/p/glow.jpg", 20000L, 214L, 380L,
				"소개", "저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
				413200L, 10370L, 152L, 72L, 3L);
```

같은 파일 `잘못된_enum은_400_VALIDATION_FAILED`(70행)의 `sort=hype` → `sort=zzz`:

```java
		mockMvc.perform(get("/v1/influencers?sort=zzz"))
```

`V2InfluencerReportControllerTest.java` 94~95행 (avgComments값 다음에 null 삽입):

```java
				new CardRow("a", "A", null, 1000L, 10L, 5L, "bio-a", null, null, null, null, null, null, null, 0L),
				new CardRow("b", "B", null, 2000L, 20L, 6L, "bio-b", null, null, null, null, null, null, null, 0L)));
```

- [ ] **Step 3: 실패 확인 (Docker 필요 — RepositoryTest는 Testcontainers)**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryQueryTest" --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepositoryTest"
```

Expected: FAIL — 컴파일 에러(`CardRow` 인자 수·`avgHypeScore()` 미정의) 또는 sort=hype 검증 실패.

- [ ] **Step 4: 구현**

`V1InfluencerDiscoveryQuery.java` 16행:

```java
	private static final Set<String> SORTS = Set.of("reach", "views", "followers", "hype");
```

`V1InfluencerDiscoveryRepository.java` — ① `findCards` SELECT(50~53행)에 `su.avg_hype_score` 추가:

```java
						SELECT a.handle, a.display_name,
						       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
						       a.followers,
						       su.posts_count, su.follows_count, su.biography, cp.tagline,
						       su.views_per_follower, su.avg_er_pct AS avg_er_pct,
						       su.avg_views, su.avg_likes, su.avg_comments, su.avg_hype_score,
						       COALESCE(sp.cnt, 0) AS sponsored_count
```

② `findCardsByHandles` SELECT(72~79행)도 같은 줄 동일 수정 (6.23 유사 카드가 이 쿼리를 공유 — 스펙 §5의 "자동 포함" 지점).

③ `orderBy`에 케이스 추가:

```java
	/** 전부 내림차순, 동점 2차 정렬은 id(=handle) 오름차순 (스펙 6.21 안정 정렬). */
	private String orderBy(String sort) {
		return switch (sort) {
			case "views" -> "\nORDER BY su.avg_views DESC NULLS LAST, a.handle";
			case "followers" -> "\nORDER BY a.followers DESC NULLS LAST, a.handle";
			case "hype" -> "\nORDER BY su.avg_hype_score DESC NULLS LAST, a.handle";
			default -> "\nORDER BY su.views_per_follower DESC NULLS LAST, a.handle";
		};
	}
```

④ `CardRow` record — `avgComments` 다음에 필드 추가:

```java
	public record CardRow(String handle, String displayName, String profileImageUrl, Long followers,
			Long postsCount, Long followsCount, String biography,
			String tagline, BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgViews,
			Long avgLikes, Long avgComments, Long avgHypeScore, Long sponsoredCount) {
	}
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.*" --tests "com.celfit.was.v2.influencer.*"
```

Expected: PASS (assembler·controller 테스트는 아직 hypeScore DTO 미노출 상태로 기존 단언만 통과).

- [ ] **Step 6: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java
git commit -m "feat(was): 발굴 목록 sort=hype + avg_hype_score 조회 배선"
```

---

### Task 4: was — `InfluencerCard.hypeScore` 노출

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/InfluencerCard.java`
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryAssembler.java:55`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryAssemblerTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryControllerTest.java`

- [ ] **Step 1: 테스트 먼저 (실패 확인용)**

`V1InfluencerDiscoveryAssemblerTest.java` — `카드_변환_스케일과_id_규칙`에 추가:

```java
		assertThat(card.hypeScore()).isEqualTo(72);
```

`bio_tagline_부재는_빈문자열_배열은_빈배열`에 추가:

```java
		assertThat(card.hypeScore()).isNull(); // 점수 가능 콘텐츠 없는 계정
```

`V1InfluencerDiscoveryControllerTest.java` — `익명_200_카드와_meta`의 jsonPath 단언에 추가:

```java
				.andExpect(jsonPath("$.data[0].hypeScore").value(72))
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssemblerTest" --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryControllerTest"
```

Expected: FAIL — 컴파일 에러(`hypeScore()` 미정의).

- [ ] **Step 3: 구현**

`InfluencerCard.java` — `avgComments` 다음에 `Integer hypeScore` 추가 + javadoc 한 줄:

```java
/**
 * 스펙 6.21 발굴 목록 카드. 모든 파생 지표의 근거는 최근 12개 게시물 창(account_content_series).
 * id는 handle 그대로(6.4 확정 준용). email은 크롤러 미수집(V31)이라 현재 항상 null.
 * effectiveFollowers·avgViews·er는 산출 불가(ER·릴스 없음)면 null, bio·tagline 부재는 빈 문자열.
 * hypeScore: 계정 하입 스코어(최근창 콘텐츠 hype_score 단순 평균, 0~100) — 점수 가능 콘텐츠 없으면 null
 * (스펙 2026-07-29-influencer-avg-hype-score).
 */
public record InfluencerCard(String id, String handle, String displayName, String profileImageUrl,
		Long followers, Long effectiveFollowers, Long postsCount, Long followingCount, String bio,
		String email, String tagline, BigDecimal reachMultiplier, BigDecimal er, Long avgViews,
		Long avgLikes, Long avgComments, Integer hypeScore, Long sponsoredCount,
		List<String> collaboratedBrands, List<CategoryShare> categoryShares,
		List<RecentThumb> recentThumbs) {
```

`V1InfluencerDiscoveryAssembler.java` `toCard` — 55행 부근을 다음으로 교체:

```java
				r.avgViews(), r.avgLikes(), r.avgComments(),
				r.avgHypeScore() == null ? null : r.avgHypeScore().intValue(),
				r.sponsoredCount(),
```

- [ ] **Step 4: 통과 확인 (was 전체 — v2 유사 카드 회귀 포함)**

```bash
./gradlew :was:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): 발굴 카드에 hypeScore 노출 — 유사 카드(6.23)도 공유 SELECT로 자동 포함"
```

---

### Task 5: 전체 검증 · 문서 · PR

**Files:**
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표·§7 결정 기록)
- Modify: `docs/superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md` (상태 헤더만)

- [ ] **Step 1: 전체 테스트 (SQL 하니스 + Gradle 전 모듈)**

```bash
analytics/test/run.sh
./gradlew test
```

Expected: `ALL GREEN` + `BUILD SUCCESSFUL`.

- [ ] **Step 2: ARCHITECTURE.md 갱신**

§5 작업 트랙 표에 트랙 추가(기존 행 서식에 맞춰): 트랙명 "발굴 목록 계정 하입 스코어", 상태 ✅ 구현(배포 대기), 정본 스펙 `2026-07-29-influencer-avg-hype-score-design.md`.
§7 결정 기록에 추가: "07-29 계정 하입 스코어 = 최근창 콘텐츠 hype_score 단순 평균(감쇠 포함, 함수 재사용) — 산식 위치는 v_account_summaries(B안), 유사 카드 자동 포함 의도".

- [ ] **Step 3: 스펙 상태 헤더 갱신**

`docs/superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md` 첫머리 상태를 `> 상태: 🟢 활성 · ✅ 구현됨(배포 대기)`으로 변경(본문 불변).

- [ ] **Step 4: Commit + PR**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md
git commit -m "docs: 계정 하입 스코어 트랙 반영 — ARCHITECTURE §5·§7, 스펙 상태 갱신"
```

`superpowers:finishing-a-development-branch` 스킬로 마무리 — develop 대상 PR. PR 본문에 반드시 포함:
- **배포 순서**: 뷰 수동 적용(origin/develop 기준, lock_timeout 재시도 런북) → analytics 배포(Flyway V42) → 미러 → was 배포. was가 먼저 뜨면 `su.avg_hype_score` 미존재로 발굴 목록 500.
- **머지 직전 V41 번호 재확인** (V18 경합 전례).
- **프론트 통지**: 카드 `hypeScore`(0~100, null 가능 — null 표시 정책은 프론트 몫) + `sort=hype` 추가, 유사 카드에도 동일 필드 포함.

- [ ] **Step 5: 계획 아카이브**

머지 후(또는 PR 생성 시점에 사용자 지시에 따라) 본 계획을 `docs/superpowers/plans/archive/`로 이동하고 상태 헤더를 `✅ 실행됨`으로 갱신.

---

## Self-Review 결과 (작성 시 수행)

- 스펙 커버리지: §3 산식(Task 1), §4 3곳 동시 변경(Task 1·2)+was(Task 3·4), §5 API·유사 카드(Task 3·4), §6 배포 순서(Task 5 PR 본문), §7 테스트 3종(Task 1 하니스 항등식·NULL, Task 3·4 was) — 전부 매핑됨. 하니스 "동일 입력 동일 점수"(§7-3)는 고정 기대값 대신 v_contents 항등식으로 구현(신선도 감쇠로 절대값이 시간에 따라 변해 고정값 단언은 시한폭탄이기 때문).
- 플레이스홀더: 없음. V41은 실행 시작 시 전제 확인으로 치환 가능하게 명시.
- 타입 일관성: 뷰 `bigint` ↔ record `Long avgHypeScore` ↔ CardRow `Long` ↔ DTO `Integer hypeScore`(어셈블러 intValue 변환) — Task 3 Step 4·Task 4 Step 3 코드 상호 일치 확인.
