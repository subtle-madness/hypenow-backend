# 태스크 D: 게시물 상세 API (`GET /api/posts/{shortCode}`) Implementation Plan

> 상태: ✅ 구현/실행/반영됨 (2026-07-12)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 콘텐츠 상세 모달의 비LLM 데이터 전부(계정·게시물·수집 댓글 전체)를 반환하는 `GET /api/posts/{shortCode}`를 만든다 — B1이 개통한 analysis DB 서빙 테이블 3종(`accounts`·`contents`·`content_comments`)만 읽는다.

**Architecture:** was는 계약 record(`Content`·`Account`·`ContentComment`)로 미러 테이블을 SELECT하고(ARCHITECTURE §4-3), 행 단위 파생값(참여율·경과일)만 Java에서 계산해(§4-2 표현 조립) 블록 구조 응답으로 조립한다. LLM 산출 블록(AI 요약·성과 비교·카테고리 맥락·콘텐츠 분석·댓글 분석 탭, 댓글 ai_category)은 **응답에 필드 자체가 없고** B2·B3 완료 후 additive로 추가된다. 집합 연산(작성자 평균 등)은 하지 않는다.

**Tech Stack:** Java 21 / Spring Boot 4.1 (JdbcClient, record DTO), Testcontainers 2.x (`org.testcontainers.postgresql.PostgreSQLContainer`), MockMvc (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`).

**전제:** 브랜치 `feat/task-a-analytics-foundation`(A+B1 산출물)에서 분기. 실DB 검증에는 로컬 `crawler-postgres-1` 필요(`docker start crawler-postgres-1`).

**응답 계약 (was → front REST JSON, §4-4 표의 was 소유 계약):**

```json
{
  "post": {
    "shortCode": "mari01", "thumbnailUrl": "https://…", "caption": "…",
    "postedAt": "2026-06-28T00:00:00Z", "daysSincePosted": 10,
    "contentType": "reels", "videoDuration": 18.0, "originalUrl": "https://…",
    "views": 1911943, "likes": 32969, "comments": 488,
    "engagementRate": 0.0175, "hypeScore": 1911943
  },
  "account": { "handle": "marimood", "displayName": "마리 MARI",
               "profileImageUrl": "https://…", "followers": 16586 },
  "comments": { "collectedCount": 18, "items": [
    { "id": 1, "authorMasked": "hye***", "body": "…", "likeCount": 342 } ] }
}
```

- `engagementRate` = (likes+comments)/views, 소수 4자리 HALF_UP. **views가 NULL(피드)이거나 0이면 null** (피드 조회수 NULL 규칙).
- `comments.items`는 수집분 **전체**, `like_count DESC, id ASC` 정렬. `collectedCount` = items 크기 (프론트 카피 "댓글 N (작성자 답글 제외)" — 답글은 수집 자체를 안 하므로 별도 처리 없음).
- 게시물 원 지표 `post.comments`(전체 댓글 수)와 `comments.collectedCount`(수집분)는 다른 값 — 둘 다 제공.
- `contents`에 short_code 없으면 **404**. 미러 테이블 자체가 없어도 500 대신 404 (AnalysisRepository 우아한 저하 컨벤션).
- account 행이 없으면(미러 정합 깨짐) `"account": null` — 500을 내지 않는다.

## File Structure

```
was/build.gradle                                              [수정] contract-analysis + Testcontainers 의존성
was/src/test/java/com/celfit/was/IntegrationTest.java          [신규] Testcontainers 공통 베이스 (crawler 패턴 복제)
was/src/main/java/com/celfit/was/postdetail/
  PostDetailRepository.java                                    [신규] 미러 3종 조회 (계약 record 매핑)
  PostDetailResponse.java                                      [신규] 응답 record (post/account/comments 블록)
  PostDetailAssembler.java                                     [신규] 행 → 블록 조립 + 참여율·경과일 계산
  PostDetailController.java                                    [신규] GET /api/posts/{shortCode} + 404
was/src/main/java/com/celfit/was/config/
  ClockConfig.java                                             [신규] Clock 빈 (경과일 테스트 고정용)
  WebConfig.java                                               [신규] /api/** CORS
was/src/main/resources/application.yml                         [수정] was.cors.allowed-origins
was/src/test/java/com/celfit/was/postdetail/
  PostDetailRepositoryTest.java                                [신규] Testcontainers
  PostDetailAssemblerTest.java                                 [신규] 순수 단위 (고정 Clock)
  PostDetailControllerTest.java                                [신규] MockMvc 200/404/CORS
ARCHITECTURE.md                                                [수정] §5 D 상태 ✅ + §7 결정 기록
```

**테스트 기대값 근거** (marimood 실데이터 모사):
- ER: (32,969 + 488) / 1,911,943 = 0.017499… → **0.0175**
- 경과일: 게시 2026-06-28T00:00Z, Clock 고정 2026-07-08T00:00Z → **10**
- 피드(views NULL): ER **null**, hype_score = likes+comments

---

### Task 1: 브랜치 분기 + was 모듈 배선

**Files:**
- Modify: `was/build.gradle`
- Create: `was/src/test/java/com/celfit/was/IntegrationTest.java`

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout -b feat/task-d-post-detail-api
```

- [ ] **Step 2: was/build.gradle 의존성 추가**

dependencies 블록의 `implementation 'org.springframework.boot:spring-boot-starter-webmvc'` 위에 계약 모듈, 테스트 블록에 Testcontainers(crawler와 동일 좌표):

```groovy
dependencies {
	implementation project(':contract-analysis')
	implementation 'org.springframework.boot:spring-boot-starter-webmvc'
	implementation 'org.springframework.boot:spring-boot-starter-jdbc'
	implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
	compileOnly 'org.projectlombok:lombok'
	annotationProcessor 'org.projectlombok:lombok'
	runtimeOnly 'org.postgresql:postgresql'
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testImplementation 'org.springframework.boot:spring-boot-testcontainers'
	testImplementation 'org.testcontainers:testcontainers-postgresql'
	testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

- [ ] **Step 3: IntegrationTest 베이스 작성** (crawler의 `IntegrationTest` 패턴 복제)

`was/src/test/java/com/celfit/was/IntegrationTest.java`:

```java
package com.celfit.was;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 통합 테스트 공통 베이스. Postgres 컨테이너 1개를 JVM 전체에서 공유(싱글턴 패턴). */
@SpringBootTest
public abstract class IntegrationTest {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :was:compileJava :was:compileTestJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add was/build.gradle was/src/test/java/com/celfit/was/IntegrationTest.java
git commit -m "chore(was): 계약 모듈·Testcontainers 배선 — 태스크 D 기반

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: PostDetailRepository — 미러 3종 조회 (TDD)

**Files:**
- Test: `was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java`:

```java
package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostDetailRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PostDetailRepository repository;

	@BeforeEach
	void setUpTables() {
		// analytics의 V1__serving_tables.sql과 동일 형상 (컬럼 계약: 뷰 = DDL = record)
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_comments");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    thumbnail_url  text,
				    caption        text,
				    posted_at      timestamptz,
				    content_type   text,
				    video_duration numeric,
				    original_url   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint,
				    hype_score     bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_comments (
				    id            bigint PRIMARY KEY,
				    short_code    text NOT NULL,
				    author_masked text,
				    body          text,
				    like_count    bigint
				)""");
		jdbcTemplate.update("""
				INSERT INTO accounts VALUES ('marimood', '마리 MARI', 'https://pic/mari.jpg', 16586)
				""");
		jdbcTemplate.update("""
				INSERT INTO contents VALUES
				 ('mari01', 'marimood', 'https://thumb/mari01.jpg', '쿨톤 여름 침착 조합',
				  '2026-06-28T00:00:00Z', 'reels', 18.0, 'https://www.instagram.com/p/mari01/',
				  1911943, 32969, 488, 1911943),
				 ('mari02', 'marimood', 'https://thumb/mari02.jpg', '피드 게시물',
				  '2026-07-01T00:00:00Z', 'feed', NULL, 'https://www.instagram.com/p/mari02/',
				  NULL, 2000, 100, 2100)
				""");
		jdbcTemplate.update("""
				INSERT INTO content_comments VALUES
				 (1, 'mari01', 'hye***', '이거 어디서 살 수 있어요??', 342),
				 (2, 'mari01', 'min***', '건성인데 자극 없을까요??', 214),
				 (3, 'mari01', 'seo***', '언니 피부 미쳤다', 289)
				""");
	}

	@Test
	void shortCode로_콘텐츠_1건을_계약_record로_읽는다() {
		Optional<Content> found = repository.findContent("mari01");

		assertThat(found).isPresent();
		Content content = found.get();
		assertThat(content.accountHandle()).isEqualTo("marimood");
		assertThat(content.views()).isEqualTo(1911943L);
		assertThat(content.postedAt()).isNotNull();
		assertThat(content.hypeScore()).isEqualTo(1911943L);
	}

	@Test
	void 없는_shortCode면_empty를_반환한다() {
		assertThat(repository.findContent("nope")).isEmpty();
	}

	@Test
	void handle로_계정을_읽는다() {
		Optional<Account> found = repository.findAccount("marimood");

		assertThat(found).isPresent();
		assertThat(found.get().displayName()).isEqualTo("마리 MARI");
		assertThat(found.get().followers()).isEqualTo(16586L);
	}

	@Test
	void 댓글은_좋아요_내림차순으로_전부_읽는다() {
		List<ContentComment> comments = repository.findComments("mari01");

		assertThat(comments).hasSize(3);
		assertThat(comments).extracting(ContentComment::likeCount)
				.containsExactly(342L, 289L, 214L);
		assertThat(comments.getFirst().authorMasked()).isEqualTo("hye***");
	}

	@Test
	void 미러_테이블이_없으면_빈_값으로_저하한다() {
		jdbcTemplate.execute("DROP TABLE contents");
		jdbcTemplate.execute("DROP TABLE accounts");
		jdbcTemplate.execute("DROP TABLE content_comments");

		assertThat(repository.findContent("mari01")).isEmpty();
		assertThat(repository.findAccount("marimood")).isEmpty();
		assertThat(repository.findComments("mari01")).isEmpty();
	}
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'`
Expected: FAIL — `PostDetailRepository` 심볼 없음 (컴파일 에러)

- [ ] **Step 3: Repository 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java`:

```java
package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 서빙 미러 3종 조회. 계약 record로 매핑하고(§4-3), 미러 부재 시 빈 값으로 저하한다(대시보드 컨벤션). */
@Repository
public class PostDetailRepository {

	private static final Logger log = LoggerFactory.getLogger(PostDetailRepository.class);

	private final JdbcClient jdbcClient;

	public PostDetailRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<Content> findContent(String shortCode) {
		return safeQuery("contents", Optional::empty, () -> jdbcClient.sql("""
				SELECT short_code, account_handle, thumbnail_url, caption, posted_at,
				       content_type, video_duration, original_url,
				       views, likes, comments, hype_score
				FROM contents
				WHERE short_code = :shortCode
				""")
				.param("shortCode", shortCode)
				.query(Content.class)
				.optional());
	}

	public Optional<Account> findAccount(String handle) {
		return safeQuery("accounts", Optional::empty, () -> jdbcClient.sql("""
				SELECT handle, display_name, profile_image_url, followers
				FROM accounts
				WHERE handle = :handle
				""")
				.param("handle", handle)
				.query(Account.class)
				.optional());
	}

	public List<ContentComment> findComments(String shortCode) {
		return safeQuery("content_comments", List::of, () -> jdbcClient.sql("""
				SELECT id, short_code, author_masked, body, like_count
				FROM content_comments
				WHERE short_code = :shortCode
				ORDER BY like_count DESC NULLS LAST, id
				""")
				.param("shortCode", shortCode)
				.query(ContentComment.class)
				.list());
	}

	private <T> T safeQuery(String table, Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("{} 조회 실패, 빈 값으로 대체합니다: {}", table, e.getMessage());
			return fallback.get();
		}
	}
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailRepositoryTest*'`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailRepositoryTest.java
git commit -m "feat(was): 서빙 미러 3종 조회 리포지토리 — 계약 record 매핑 + 우아한 저하

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 응답 DTO + 어셈블러 (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/config/ClockConfig.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`
- Test: `was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`

- [ ] **Step 1: 실패하는 어셈블러 단위 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`:

```java
package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostDetailAssemblerTest {

	// 게시일(2026-06-28T00:00Z)로부터 10일 경과 시점으로 고정
	private final Clock fixedClock =
			Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

	private final PostDetailAssembler assembler = new PostDetailAssembler(fixedClock);

	private Content reels() {
		return new Content("mari01", "marimood", "https://thumb/mari01.jpg", "쿨톤 여름 침착 조합",
				OffsetDateTime.parse("2026-06-28T00:00:00Z"), "reels", new BigDecimal("18.0"),
				"https://www.instagram.com/p/mari01/", 1911943L, 32969L, 488L, 1911943L);
	}

	private Account account() {
		return new Account("marimood", "마리 MARI", "https://pic/mari.jpg", 16586L);
	}

	@Test
	void 행을_모달_블록_구조로_조립한다() {
		List<ContentComment> comments = List.of(
				new ContentComment(1L, "mari01", "hye***", "이거 어디서 살 수 있어요??", 342L),
				new ContentComment(3L, "mari01", "seo***", "언니 피부 미쳤다", 289L));

		PostDetailResponse response = assembler.toResponse(reels(), account(), comments);

		assertThat(response.post().shortCode()).isEqualTo("mari01");
		assertThat(response.post().daysSincePosted()).isEqualTo(10L);
		// (32969+488)/1911943 = 0.0175 (4자리 HALF_UP)
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0175"));
		assertThat(response.post().hypeScore()).isEqualTo(1911943L);
		assertThat(response.account().displayName()).isEqualTo("마리 MARI");
		assertThat(response.comments().collectedCount()).isEqualTo(2);
		assertThat(response.comments().items().getFirst().authorMasked()).isEqualTo("hye***");
		assertThat(response.comments().items().getFirst().likeCount()).isEqualTo(342L);
	}

	@Test
	void 피드는_조회수가_없어_참여율이_null이다() {
		Content feed = new Content("mari02", "marimood", null, "피드 게시물",
				OffsetDateTime.parse("2026-07-01T00:00:00Z"), "feed", null,
				"https://www.instagram.com/p/mari02/", null, 2000L, 100L, 2100L);

		PostDetailResponse response = assembler.toResponse(feed, account(), List.of());

		assertThat(response.post().engagementRate()).isNull();
		assertThat(response.post().daysSincePosted()).isEqualTo(7L);
		assertThat(response.comments().collectedCount()).isZero();
		assertThat(response.comments().items()).isEmpty();
	}

	@Test
	void 계정이_없으면_account_블록이_null이다() {
		PostDetailResponse response = assembler.toResponse(reels(), null, List.of());

		assertThat(response.account()).isNull();
		assertThat(response.post().shortCode()).isEqualTo("mari01");
	}

	@Test
	void 게시일이_null이면_경과일도_null이다() {
		Content undated = new Content("mari03", "marimood", null, null,
				null, "reels", null, null, 1000L, 10L, 1L, 1000L);

		PostDetailResponse response = assembler.toResponse(undated, account(), List.of());

		assertThat(response.post().daysSincePosted()).isNull();
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0110"));
	}
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: FAIL — `PostDetailResponse`/`PostDetailAssembler` 심볼 없음

- [ ] **Step 3: Clock 빈 등록**

`was/src/main/java/com/celfit/was/config/ClockConfig.java`:

```java
package com.celfit.was.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 경과일 계산용 Clock — 테스트에서 고정 주입할 수 있게 빈으로 분리. */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
```

- [ ] **Step 4: 응답 record 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 콘텐츠 상세 모달 응답. 블록이 소스 테이블과 1:1 대응한다(post=contents, account=accounts,
 * comments=content_comments). LLM 산출 블록(AI 요약·성과 비교·카테고리 맥락·콘텐츠 분석·댓글 분석,
 * 댓글 ai_category)은 필드 자체가 없고 B2·B3 완료 후 additive로 추가된다.
 */
public record PostDetailResponse(Post post, Account account, Comments comments) {

	/** engagementRate = (likes+comments)/views — views가 NULL(피드)이거나 0이면 null. */
	public record Post(
			String shortCode,
			String thumbnailUrl,
			String caption,
			OffsetDateTime postedAt,
			Long daysSincePosted,
			String contentType,
			BigDecimal videoDuration,
			String originalUrl,
			Long views,
			Long likes,
			Long comments,
			BigDecimal engagementRate,
			Long hypeScore) {
	}

	public record Account(
			String handle,
			String displayName,
			String profileImageUrl,
			Long followers) {
	}

	/** collectedCount = 수집 댓글 수(작성자 답글은 수집 안 함) — post.comments(원 지표)와 다른 값. */
	public record Comments(int collectedCount, List<Item> items) {

		public record Item(Long id, String authorMasked, String body, Long likeCount) {
		}
	}
}
```

- [ ] **Step 5: 어셈블러 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`:

```java
package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/** 계약 record → 모달 블록 응답 조립. 행 단위 파생값(참여율·경과일)만 계산한다(§4-2 표현 조립). */
@Component
public class PostDetailAssembler {

	private final Clock clock;

	public PostDetailAssembler(Clock clock) {
		this.clock = clock;
	}

	public PostDetailResponse toResponse(Content content, Account account, List<ContentComment> comments) {
		return new PostDetailResponse(
				new PostDetailResponse.Post(
						content.shortCode(), content.thumbnailUrl(), content.caption(),
						content.postedAt(), daysSincePosted(content.postedAt()),
						content.contentType(), content.videoDuration(), content.originalUrl(),
						content.views(), content.likes(), content.comments(),
						engagementRate(content), content.hypeScore()),
				account == null ? null
						: new PostDetailResponse.Account(
								account.handle(), account.displayName(),
								account.profileImageUrl(), account.followers()),
				new PostDetailResponse.Comments(
						comments.size(),
						comments.stream()
								.map(c -> new PostDetailResponse.Comments.Item(
										c.id(), c.authorMasked(), c.body(), c.likeCount()))
								.toList()));
	}

	private Long daysSincePosted(OffsetDateTime postedAt) {
		if (postedAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(postedAt, OffsetDateTime.now(clock));
	}

	/** (좋아요+댓글)/조회수 — 피드는 조회수가 항상 NULL이라 참여율도 null (조회수 NULL 규칙). */
	private BigDecimal engagementRate(Content content) {
		if (content.views() == null || content.views() == 0) {
			return null;
		}
		long engagements = nullToZero(content.likes()) + nullToZero(content.comments());
		return BigDecimal.valueOf(engagements)
				.divide(BigDecimal.valueOf(content.views()), 4, RoundingMode.HALF_UP);
	}

	private long nullToZero(Long value) {
		return value == null ? 0 : value;
	}
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add was/src/main/java/com/celfit/was/config/ClockConfig.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java
git commit -m "feat(was): 상세 모달 응답 DTO + 어셈블러 — 참여율·경과일은 행 단위 표현 조립

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 컨트롤러 + CORS (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`
- Create: `was/src/main/java/com/celfit/was/config/WebConfig.java`
- Modify: `was/src/main/resources/application.yml`
- Test: `was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`

- [ ] **Step 1: 실패하는 MockMvc 테스트 작성**

`was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`:

```java
package com.celfit.was.postdetail;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.WebConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PostDetailController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000,https://celfit-front.vercel.app")
@Import({PostDetailAssembler.class, ClockConfig.class, WebConfig.class})
class PostDetailControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PostDetailRepository repository;

	private void givenMari01() {
		given(repository.findContent("mari01")).willReturn(Optional.of(
				new Content("mari01", "marimood", "https://thumb/mari01.jpg", "쿨톤 여름 침착 조합",
						OffsetDateTime.parse("2026-06-28T00:00:00Z"), "reels", new BigDecimal("18.0"),
						"https://www.instagram.com/p/mari01/", 1911943L, 32969L, 488L, 1911943L)));
		given(repository.findAccount("marimood")).willReturn(Optional.of(
				new Account("marimood", "마리 MARI", "https://pic/mari.jpg", 16586L)));
		given(repository.findComments("mari01")).willReturn(List.of(
				new ContentComment(1L, "mari01", "hye***", "이거 어디서 살 수 있어요??", 342L),
				new ContentComment(3L, "mari01", "seo***", "언니 피부 미쳤다", 289L)));
	}

	@Test
	void 게시물_상세를_블록_JSON으로_반환한다() throws Exception {
		givenMari01();

		mockMvc.perform(get("/api/posts/mari01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.post.shortCode").value("mari01"))
				.andExpect(jsonPath("$.post.engagementRate").value(0.0175))
				.andExpect(jsonPath("$.post.views").value(1911943))
				.andExpect(jsonPath("$.account.handle").value("marimood"))
				.andExpect(jsonPath("$.account.followers").value(16586))
				.andExpect(jsonPath("$.comments.collectedCount").value(2))
				.andExpect(jsonPath("$.comments.items[0].authorMasked").value("hye***"))
				.andExpect(jsonPath("$.comments.items[0].likeCount").value(342));
	}

	@Test
	void 없는_게시물이면_404() throws Exception {
		given(repository.findContent("nope")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/posts/nope"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		givenMari01();

		mockMvc.perform(get("/api/posts/mari01")
						.header("Origin", "https://celfit-front.vercel.app"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://celfit-front.vercel.app"));
	}
}
```

> Spring Boot 4에서 `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지(CLAUDE.md 함정 항목). `@MockitoBean`은 `org.springframework.test.context.bean.override.mockito`.

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: FAIL — `PostDetailController`/`WebConfig` 심볼 없음

- [ ] **Step 3: 컨트롤러 작성**

`was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`:

```java
package com.celfit.was.postdetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PostDetailController {

	private final PostDetailRepository repository;
	private final PostDetailAssembler assembler;

	public PostDetailController(PostDetailRepository repository, PostDetailAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/api/posts/{shortCode}")
	public PostDetailResponse postDetail(@PathVariable String shortCode) {
		return repository.findContent(shortCode)
				.map(content -> assembler.toResponse(
						content,
						repository.findAccount(content.accountHandle()).orElse(null),
						repository.findComments(shortCode)))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode));
	}
}
```

- [ ] **Step 4: CORS 설정 작성** (프론트가 별도 오리진: celfit-front.vercel.app)

`was/src/main/java/com/celfit/was/config/WebConfig.java`:

```java
package com.celfit.was.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final String[] allowedOrigins;

	public WebConfig(@Value("${was.cors.allowed-origins}") String[] allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET");
	}
}
```

`was/src/main/resources/application.yml` 끝에 추가:

```yaml
was:
  cors:
    allowed-origins: http://localhost:3000,https://celfit-front.vercel.app
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: 3 tests PASS

- [ ] **Step 6: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL — Repository·Assembler·Controller 테스트 전부 PASS

- [ ] **Step 7: Commit**

```bash
git add was/src/main/java/com/celfit/was/postdetail/PostDetailController.java \
  was/src/main/java/com/celfit/was/config/WebConfig.java \
  was/src/main/resources/application.yml \
  was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java
git commit -m "feat(was): GET /api/posts/{shortCode} 상세 모달 API + CORS

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: E2E 검증 (실 analysis DB)

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — crawler·analytics·was 전 모듈 그린

- [ ] **Step 2: 실데이터 관통 검증**

```bash
docker start crawler-postgres-1
./gradlew :was:bootRun &                       # port 8081
sleep 15
# 실데이터에서 댓글 있는 short_code 하나 선택
SHORT_CODE=$(docker exec -i crawler-postgres-1 psql -U crawler -d analysis -tAc \
  "SELECT c.short_code FROM contents c JOIN content_comments m ON m.short_code = c.short_code GROUP BY c.short_code ORDER BY count(*) DESC LIMIT 1")
curl -s "http://localhost:8081/api/posts/${SHORT_CODE}" | head -c 2000; echo
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/posts/__none__"
curl -s -o /dev/null -D - -H "Origin: https://celfit-front.vercel.app" \
  "http://localhost:8081/api/posts/${SHORT_CODE}" | grep -i "access-control-allow-origin"
```

Expected:
- 첫 curl: `post`(shortCode·engagementRate·views…), `account`(handle·followers…), `comments`(collectedCount·items 배열) 채워진 JSON
- 둘째 curl: `404`
- 셋째 curl: `Access-Control-Allow-Origin: https://celfit-front.vercel.app`

- [ ] **Step 3: was 종료**

```bash
kill %1 2>/dev/null
git status   # 잔여 변경 없음 확인
```

---

### Task 6: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 D 행 상태, §7 결정 기록)
- Modify: `docs/superpowers/plans/2026-07-12-task-d-post-detail-api.md` (상태 헤더)

- [ ] **Step 1: ARCHITECTURE.md §5의 D 행 상태 ⬜→✅** (내용은 유지, 상태만)

- [ ] **Step 2: §7 결정 기록 맨 위에 한 줄 추가**

```markdown
| 2026-07-12 | 태스크 D: 상세 API 계약 확정 — 응답 블록=소스 테이블 1:1(post/account/comments), 참여율=(좋아요+댓글)/조회수(피드 null)·경과일은 was 표현 조립, 댓글은 수집분 전체 서빙(좋아요순). LLM 블록은 필드 부재→B2·B3 additive. as-of 규칙은 스냅샷 미러 도입 시로 보류 유지 | [plans/2026-07-12-task-d-post-detail-api.md](docs/superpowers/plans/2026-07-12-task-d-post-detail-api.md) |
```

- [ ] **Step 3: 이 계획 문서 상태 헤더 갱신** — `> 상태: 🟢 활성` → `> 상태: ✅ 구현/실행/반영됨 (2026-07-12)`

- [ ] **Step 4: Commit**

```bash
git add ARCHITECTURE.md docs/superpowers/plans/2026-07-12-task-d-post-detail-api.md
git commit -m "docs: D 완료 반영 (상세 API 계약 결정 기록)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

> 계획 문서의 `plans/archive/` 이동은 브랜치 머지 시점에(B1 전례).

---

## 완료 기준 (DoD)

- was 테스트 3벌(리포지토리 5·어셈블러 4·컨트롤러 3) + 전 모듈 `./gradlew test` 그린
- 실 analysis DB에서 `GET /api/posts/{shortCode}` 200 JSON / 없는 코드 404 / CORS 헤더 확인
- 계약이 명확: 응답 블록 = 미러 테이블 1:1, LLM 블록은 필드 부재(additive 확장 지점 명시)

## 다루지 않는 것

- 랭킹 목록 API (별도 태스크 — 프론트 리스트는 아직 목데이터)
- 우측 탭 5종·댓글 ai_category (B2·B3 산출물 — additive)
- as-of 스냅샷 규칙 (`content_metric_snapshots` 미러 부재 — 도입 시 결정)
- 후보 저장·인플루언서 상세 (G·E)

## Self-Review 체크 결과 (작성 시 수행)

- **스펙 커버리지**: 승인된 설계 7항목 — 계약 record SELECT ✅(Task 2) · 행 단위 파생값 ✅(Task 3) · 댓글 전체+정렬 ✅(Task 2·3) · 404/우아한 저하 ✅(Task 2·4) · CORS ✅(Task 4) · 검증 3벌 ✅(Task 2~4) · 범위 밖 명시 ✅
- **플레이스홀더 없음**: 모든 코드 블록 완성본. SB4 패키지 함정(WebMvcTest·MockitoBean)은 단서 명시.
- **타입 일관성**: 계약 record 3종의 컴포넌트 순서 = SELECT 컬럼 순서 = V1 DDL(현물 대조 완료). `PostDetailAssembler(Clock)` 시그니처가 Task 3 테스트·Task 4 Import와 일치. `Comments.Item` 중첩명 일관.
