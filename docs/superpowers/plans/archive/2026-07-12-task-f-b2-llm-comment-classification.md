# 태스크 F+B2: LLM 공통 골격 + 댓글 분류 배치 Implementation Plan

> 상태: ✅ 구현/실행/반영됨
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LLM 호출 골격(포트/어댑터·설정·비용 가드)을 세우고, 첫 소비자인 **댓글 6분류 배치**를 개통한다 — 미분류 콘텐츠의 댓글을 Claude로 분류해 `comment_classifications`에 저장. 골드셋 스파이크 러너(F-1 도구)도 만들되 **실 API 실행은 사용자 결정으로 남긴다**.

**Architecture:** [스펙](../../specs/2026-07-12-analytics-data-layer-design.md) §6·§7, ARCHITECTURE.md §4-2(절차·외부 연동은 Java). **LLM 코드의 모듈 소속 = analytics로 확정** (미결 §8 해소 — raw를 읽어 분석 결과를 쓰는 일의 한 종류이므로). 포트/어댑터: 테스트는 실 API를 절대 안 때린다(포트 fake) — Anthropic 어댑터는 클라이언트 빈이 게이트 뒤에 있어 API 키 없이도 미러 실행에 영향 없음.

**Tech Stack:** Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`), structured outputs(record 자동 스키마·타입 파싱), 기본 모델 `claude-opus-4-8`(설정으로 교체 가능 — 스파이크 결과 반영 지점). Flyway V2, Testcontainers.

**전제:** 브랜치 `feat/task-a-analytics-foundation` 이어서. `ANTHROPIC_API_KEY`는 분류 배치·스파이크 실행 시에만 필요(기본 게이트 off).

---

## File Structure

```
analytics/build.gradle                                   [수정] anthropic-java 의존성
analytics/src/main/resources/db/migration/analysis/
  V2__comment_classifications.sql                        [신규] 댓글 분류 테이블
analytics/src/main/resources/application.yml             [수정] analytics.classify-on-startup=false 추가
analytics/src/main/java/com/celfit/analytics/
  config/AnalyticsSettings.java                          [신규] app_setting 리더 (모델명·배치 상한)
  llm/CommentClassificationPort.java                     [신규] 분류 포트 (인터페이스)
  llm/CommentToClassify.java                             [신규] 입력 record
  llm/ClassifiedComment.java                             [신규] 출력 record
  llm/AnthropicCommentClassifier.java                    [신규] Anthropic 어댑터 (structured outputs)
  llm/LlmConfig.java                                     [신규] 클라이언트·어댑터 빈 (게이트 뒤)
  classify/CommentClassificationJob.java                 [신규] 배치: 대상 선정→분류→저장 (멱등·상한)
  classify/ClassifyRunner.java                           [신규] 기동 러너 (게이트)
  spike/GoldsetSpikeRunner.java                          [신규] F-1 골드셋 정확도/비용 비교 CLI
analytics/src/test/java/com/celfit/analytics/classify/
  CommentClassificationJobTest.java                      [신규] fake 포트 + Testcontainers
analytics/README.md                                      [수정] 실행법·설정 키
ARCHITECTURE.md                                          [수정] §5 F·B2 상태, §8 미결(모듈 소속 확정), §7 결정 기록
```

책임: 포트(`CommentClassificationPort`)가 유일한 LLM 경계 — 잡은 포트만 알고, Anthropic SDK는 어댑터에만 산다. `AnalyticsSettings`는 raw DB `app_setting`을 읽는 유일한 Java 창구.

---

### Task 1: 의존성 + 설정 리더 + 분류 테이블 (TDD는 Task 2부터 — 이 태스크는 기반 배선)

**Files:**
- Modify: `analytics/build.gradle` (dependencies에 한 줄 추가)
- Create: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V2__comment_classifications.sql`
- Modify: `analytics/src/main/resources/application.yml`

- [ ] **Step 1: build.gradle 의존성 추가** (implementation 블록에)

```gradle
	implementation 'com.anthropic:anthropic-java:2.34.0'
```

- [ ] **Step 2: AnalyticsSettings.java**

```java
package com.celfit.analytics.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 런타임 설정 리더 — raw DB의 app_setting(key,value)을 읽는 유일한 Java 창구.
 * 키가 없으면 기본값 (뷰의 COALESCE 컨벤션과 동일). 값 갱신은 admin SQL로.
 */
@Component
public class AnalyticsSettings {

	/** 댓글 분류 등 LLM 호출 모델. 스파이크(F-1) 결과로 교체 가능. */
	public static final String KEY_LLM_MODEL = "analytics.llm-model";
	/** 1회 실행당 분석(LLM 호출) 콘텐츠 수 상한 — 비용 가드. */
	public static final String KEY_ANALYZE_BATCH_LIMIT = "analytics.analyze-batch-limit";

	static final String DEFAULT_LLM_MODEL = "claude-opus-4-8";
	static final int DEFAULT_ANALYZE_BATCH_LIMIT = 10;

	private final JdbcTemplate raw;

	public AnalyticsSettings(JdbcTemplate rawJdbcTemplate) {
		this.raw = rawJdbcTemplate;
	}

	public String llmModel() {
		return read(KEY_LLM_MODEL).orElse(DEFAULT_LLM_MODEL);
	}

	public int analyzeBatchLimit() {
		return read(KEY_ANALYZE_BATCH_LIMIT).map(Integer::parseInt).orElse(DEFAULT_ANALYZE_BATCH_LIMIT);
	}

	private java.util.Optional<String> read(String key) {
		return raw.query("SELECT value FROM app_setting WHERE key = ?",
				rs -> rs.next() ? java.util.Optional.of(rs.getString(1)) : java.util.Optional.empty(), key);
	}
}
```

- [ ] **Step 3: V2__comment_classifications.sql**

```sql
-- 댓글 1:1 AI 분류 (분석 층 소유 — 미러 아님, Java가 직접 쓴다).
-- id = 미러 테이블 content_comments.id와 같은 raw 댓글 id (논리 참조, FK 없음).
-- ai_category 어휘는 생산자(분석 층)가 확정하고 was는 전달만 한다 (ARCHITECTURE §4-4).
CREATE TABLE comment_classifications (
    id           bigint PRIMARY KEY,
    short_code   text   NOT NULL,
    ai_category  text   NOT NULL CHECK (ai_category IN
        ('purchase', 'question', 'positive', 'adAware', 'friendTag', 'etc')),
    model        text   NOT NULL,
    classified_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_comment_classifications_short_code ON comment_classifications (short_code);
```

- [ ] **Step 4: application.yml의 `analytics:` 블록에 게이트 추가**

```yaml
analytics:
  mirror-on-startup: true
  classify-on-startup: false   # 댓글 분류 배치 — 실 API 비용이 들어 기본 off. 실행: --analytics.classify-on-startup=true
```

- [ ] **Step 5: 빌드 + Flyway 테스트 확인** — `./gradlew :analytics:test` (기존 FlywaySchemaTest가 V2 추가에도 통과해야 — V2는 대조 대상 아님)

- [ ] **Step 6: Commit** — `feat(analytics): LLM 기반 배선 — Anthropic SDK·설정 리더·comment_classifications DDL`

---

### Task 2: 분류 포트 + Anthropic 어댑터 (TDD — 어댑터의 순수 로직만 테스트)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/CommentToClassify.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/ClassifiedComment.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/CommentClassificationPort.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicCommentClassifier.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`

- [ ] **Step 1: 포트와 record**

```java
package com.celfit.analytics.llm;

/** 분류 입력 — 댓글 1건. id는 raw 댓글 id. */
public record CommentToClassify(long id, String text) {
}
```

```java
package com.celfit.analytics.llm;

/** 분류 출력 — 6분류 중 하나. 어휘: purchase·question·positive·adAware·friendTag·etc */
public record ClassifiedComment(long id, String category) {
}
```

```java
package com.celfit.analytics.llm;

import java.util.List;

/**
 * 댓글 6분류 포트 — 유일한 LLM 경계. 테스트는 이 포트를 fake로 대체한다
 * (실 API 호출 금지 — ARCHITECTURE §4-7). 구현: AnthropicCommentClassifier.
 */
public interface CommentClassificationPort {

	List<ClassifiedComment> classify(List<CommentToClassify> comments);
}
```

- [ ] **Step 2: Anthropic 어댑터** — structured outputs로 record에 타입 파싱 (수동 JSON 파싱 없음)

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.List;
import java.util.Set;

/**
 * 댓글 6분류의 Anthropic 구현. structured outputs가 스키마 준수를 보장하고,
 * 어휘 밖 카테고리는 방어적으로 etc로 강제한다 (CHECK 제약과 이중 안전망).
 */
public final class AnthropicCommentClassifier implements CommentClassificationPort {

	private static final Set<String> CATEGORIES =
			Set.of("purchase", "question", "positive", "adAware", "friendTag", "etc");

	private static final String INSTRUCTIONS = """
			당신은 인스타그램 뷰티 콘텐츠의 댓글 분류기다. 각 댓글을 아래 6분류 중 정확히 하나로 분류하라.

			- purchase: 구매 의사·구매처/가격/재입고 질문 ("어디서 사요", "링크 주세요", "가격이요")
			- question: 제품·사용법에 대한 질문 (구매 의도 없이 궁금증 — "건성인데 써도 돼요?")
			- positive: 호감·응원·칭찬 ("피부 미쳤다", "잘 보고 있어요")
			- adAware: 광고임을 인식/언급 ("광고지만", "협찬이구나")
			- friendTag: 친구 태그·같이 보자는 멘션 ("@아무개 이거 봐")
			- etc: 위 어디에도 속하지 않음 (이모지만, 무의미)

			입력의 모든 댓글에 대해 (id, category) 쌍을 빠짐없이 반환하라.
			""";

	/** structured outputs 스키마용 내부 record — 응답 전체 그릇. */
	record Result(List<ClassifiedComment> items) {
	}

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicCommentClassifier(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public List<ClassifiedComment> classify(List<CommentToClassify> comments) {
		StringBuilder input = new StringBuilder("댓글 목록:\n");
		for (CommentToClassify c : comments) {
			input.append("- id=").append(c.id()).append(": ").append(c.text()).append('\n');
		}
		StructuredMessageCreateParams<Result> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(8192L)
				.system(INSTRUCTIONS)
				.outputConfig(Result.class)
				.addUserMessage(input.toString())
				.build();
		Result result = client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("분류 응답에 본문 없음"))
				.text();
		return result.items().stream()
				.map(c -> CATEGORIES.contains(c.category()) ? c : new ClassifiedComment(c.id(), "etc"))
				.toList();
	}
}
```

- [ ] **Step 3: LlmConfig — 클라이언트·어댑터 빈은 게이트 뒤** (API 키 없이 미러가 돌아야 하므로)

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.celfit.analytics.config.AnalyticsSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** LLM 빈 배선. 분류 게이트가 꺼져 있으면 Anthropic 클라이언트를 아예 만들지 않는다 (API 키 불필요). */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class LlmConfig {

	@Bean
	public AnthropicClient anthropicClient() {
		return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY 필요
	}

	@Bean
	public CommentClassificationPort commentClassificationPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicCommentClassifier(client, settings);
	}
}
```

- [ ] **Step 4: 컴파일 확인** — `./gradlew :analytics:compileJava -q` (structured outputs 오버로드가 컴파일되는지가 이 태스크의 검증점. 시그니처가 다르면 SDK 문서 기준으로 최소 수정하되 "record 타입 파싱" 방식은 유지, 보고에 명시)

- [ ] **Step 5: Commit** — `feat(analytics): 댓글 6분류 포트 + Anthropic 어댑터 (structured outputs)`

---

### Task 3: 분류 배치 잡 (TDD — fake 포트 + Testcontainers)

**Files:**
- Create: `analytics/src/test/java/com/celfit/analytics/classify/CommentClassificationJobTest.java`
- Create: `analytics/src/main/java/com/celfit/analytics/classify/CommentClassificationJob.java`
- Create: `analytics/src/main/java/com/celfit/analytics/classify/ClassifyRunner.java`

잡의 계약:
- **대상 선정**: raw의 서빙 뷰(`analytics.v_content_comments`)에서 댓글이 있는 short_code 중, analysis DB `comment_classifications`에 아직 없는 것 → `analyzeBatchLimit()`개까지 (비용 가드)
- **멱등**: 콘텐츠 단위 delete→insert 한 트랜잭션. 부분 실패 시 그 콘텐츠만 롤백, 다음 실행에서 재대상
- 테스트는 raw/analysis 모두 같은 Testcontainers DB로 (미러 테스트와 동일 패턴), 포트는 fake

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.analytics.classify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.CommentToClassify;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CommentClassificationJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	CommentClassificationJob job;
	List<List<CommentToClassify>> portCalls;

	/** fake 포트: 전부 positive로 분류, 호출 내역 기록. */
	CommentClassificationPort fakePort() {
		return comments -> {
			portCalls.add(comments);
			return comments.stream().map(c -> new ClassifiedComment(c.id(), "positive")).toList();
		};
	}

	@BeforeEach
	void setUp() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		portCalls = new ArrayList<>();
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		// 테스트 간 완전 초기화: Flyway 이력과 V1·V2 산출물, raw 대역을 전부 지우고 다시 만든다
		db.update("DROP TABLE IF EXISTS comment_classifications, accounts, contents, content_comments");
		db.update("DROP TABLE IF EXISTS app_setting, src_comments, flyway_schema_history");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true).baselineVersion("0").load().migrate();
		// 테스트용 raw 대역: 서빙 뷰와 같은 모양의 뷰 + app_setting
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("""
				CREATE TABLE src_comments (id bigint, short_code text, author_masked text, body text, like_count bigint)""");
		db.update("""
				CREATE VIEW analytics.v_content_comments AS
				SELECT id, short_code, author_masked, body, like_count FROM src_comments""");
		db.update("INSERT INTO src_comments VALUES (1,'post_a','aaa***','어디서 사요?',3),(2,'post_a','bbb***','예뻐요',1),(3,'post_b','ccc***','좋아요',0)");
		JdbcTemplate raw = db;
		job = new CommentClassificationJob(raw, ds, fakePort(), new AnalyticsSettings(raw));
	}

	@Test
	void 미분류_콘텐츠의_댓글을_분류해_저장한다() {
		int processed = job.run();

		assertEquals(2, processed); // post_a, post_b
		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
		assertEquals("positive", db.queryForObject(
				"SELECT ai_category FROM comment_classifications WHERE id = 1", String.class));
	}

	@Test
	void 이미_분류된_콘텐츠는_건너뛴다() {
		job.run();
		portCalls.clear();

		int processed = job.run();

		assertEquals(0, processed);
		assertTrue(portCalls.isEmpty());
	}

	@Test
	void 배치_상한이_대상_수를_제한한다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-batch-limit', '1')");

		int processed = job.run();

		assertEquals(1, processed);
		assertEquals(1L, db.queryForObject(
				"SELECT count(DISTINCT short_code) FROM comment_classifications", Long.class));
	}

	@Test
	void 재분류_시_콘텐츠_단위로_교체된다_중복_없음() {
		job.run();
		db.update("DELETE FROM comment_classifications WHERE short_code = 'post_b'"); // post_b만 재대상화

		job.run();

		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
	}
}
```

- [ ] **Step 2: 실행 — 실패 확인** (`./gradlew :analytics:test --tests '*CommentClassificationJobTest*'` → 컴파일 에러: CommentClassificationJob 없음)

- [ ] **Step 3: CommentClassificationJob 구현**

```java
package com.celfit.analytics.classify;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.CommentToClassify;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 댓글 분류 배치 (스펙 §6-1). 미분류 콘텐츠를 상한(비용 가드)까지 골라
 * 콘텐츠 단위로 분류→저장한다. 콘텐츠 단위 delete→insert 한 트랜잭션 = 멱등,
 * 부분 실패 시 해당 콘텐츠만 롤백돼 다음 실행에서 자동 재대상.
 */
public class CommentClassificationJob {

	private static final Logger log = LoggerFactory.getLogger(CommentClassificationJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final TransactionTemplate analysisTx;
	private final CommentClassificationPort port;
	private final AnalyticsSettings settings;

	public CommentClassificationJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.analysisTx = new TransactionTemplate(new DataSourceTransactionManager(analysisDataSource));
		this.port = port;
		this.settings = settings;
	}

	/** @return 처리한 콘텐츠 수 */
	public int run() {
		List<String> classified = analysis.queryForList(
				"SELECT DISTINCT short_code FROM comment_classifications", String.class);
		List<String> targets = raw.queryForList("""
				SELECT DISTINCT short_code FROM analytics.v_content_comments
				ORDER BY short_code""", String.class).stream()
				.filter(sc -> !classified.contains(sc))
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.llmModel();
		int processed = 0;
		for (String shortCode : targets) {
			List<CommentToClassify> comments = raw.query("""
					SELECT id, body FROM analytics.v_content_comments WHERE short_code = ?""",
					(rs, i) -> new CommentToClassify(rs.getLong(1), rs.getString(2)), shortCode);
			List<ClassifiedComment> results = port.classify(comments);
			analysisTx.executeWithoutResult(tx -> {
				analysis.update("DELETE FROM comment_classifications WHERE short_code = ?", shortCode);
				analysis.batchUpdate(
						"INSERT INTO comment_classifications (id, short_code, ai_category, model) VALUES (?, ?, ?, ?)",
						results, 500, (ps, r) -> {
							ps.setLong(1, r.id());
							ps.setString(2, shortCode);
							ps.setString(3, r.category());
							ps.setString(4, model);
						});
			});
			processed++;
			log.info("classified {} comments for {}", results.size(), shortCode);
		}
		log.info("classification complete ({} contents)", processed);
		return processed;
	}
}
```

- [ ] **Step 4: 테스트 통과 확인** (4개)

- [ ] **Step 5: ClassifyRunner — 게이트 뒤 기동 러너 + 잡 빈**

```java
package com.celfit.analytics.classify;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.CommentClassificationPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 분류 배치 배선 — analytics.classify-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class ClassifyRunner {

	@Bean
	public CommentClassificationJob commentClassificationJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		return new CommentClassificationJob(rawJdbcTemplate, analysisDataSource, port, settings);
	}

	@Bean
	public CommandLineRunner classifyOnStartup(CommentClassificationJob job) {
		return args -> job.run();
	}
}
```

- [ ] **Step 6: 전체 테스트 + 기본 게이트 off 상태 부트 스모크** (`./gradlew :analytics:build` → 기존 미러 경로 무영향, bootRun 시 분류 빈 미생성 확인)

- [ ] **Step 7: Commit** — `feat(analytics): 댓글 분류 배치 — 멱등·비용 가드 (기본 게이트 off)`

---

### Task 4: F-1 골드셋 스파이크 러너 (도구만 — 실행은 사용자)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/spike/GoldsetSpikeRunner.java`

- [ ] **Step 1: GoldsetSpikeRunner 작성**

```java
package com.celfit.analytics.spike;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AnthropicCommentClassifier;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentToClassify;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-1 스파이크: 수동 라벨 골드셋(CSV: id,label,text — 헤더 없음, text에 콤마 가능)을
 * 모델별로 분류해 정확도를 비교한다. 실 API를 때리므로 수동 실행 전용:
 *   ANTHROPIC_API_KEY=... ./gradlew :analytics:bootRun --args='--analytics.goldset-path=/path/goldset.csv'
 * 비용·정확도 결과로 analytics.llm-model 설정을 확정한다 (ARCHITECTURE §8 미결).
 */
@Configuration
@ConditionalOnProperty(name = "analytics.goldset-path")
public class GoldsetSpikeRunner {

	private static final List<String> MODELS = List.of("claude-opus-4-8", "claude-haiku-4-5");

	record GoldRow(long id, String label, String text) {
	}

	@Bean
	public CommandLineRunner goldsetSpike(JdbcTemplate rawJdbcTemplate,
			org.springframework.core.env.Environment env) {
		return args -> {
			Path path = Path.of(env.getRequiredProperty("analytics.goldset-path"));
			List<GoldRow> gold = new ArrayList<>();
			for (String line : Files.readAllLines(path)) {
				if (line.isBlank()) continue;
				String[] parts = line.split(",", 3);
				gold.add(new GoldRow(Long.parseLong(parts[0].trim()), parts[1].trim(), parts[2]));
			}
			List<CommentToClassify> input = gold.stream()
					.map(g -> new CommentToClassify(g.id(), g.text())).toList();
			AnthropicClient client = AnthropicOkHttpClient.fromEnv();
			for (String model : MODELS) {
				JdbcTemplate raw = rawJdbcTemplate;
				var settings = new AnalyticsSettings(raw) {
					@Override
					public String llmModel() {
						return model;
					}
				};
				long start = System.currentTimeMillis();
				List<ClassifiedComment> results =
						new AnthropicCommentClassifier(client, settings).classify(input);
				long ms = System.currentTimeMillis() - start;
				Map<Long, String> byId = results.stream()
						.collect(Collectors.toMap(ClassifiedComment::id, ClassifiedComment::category));
				long correct = gold.stream().filter(g -> g.label().equals(byId.get(g.id()))).count();
				System.out.printf("%n=== %s ===%n정확도: %d/%d (%.1f%%), 소요: %dms%n",
						model, correct, gold.size(), 100.0 * correct / gold.size(), ms);
				gold.stream().filter(g -> !g.label().equals(byId.get(g.id())))
						.forEach(g -> System.out.printf("  오분류 id=%d: 정답=%s 예측=%s | %s%n",
								g.id(), g.label(), byId.get(g.id()), g.text()));
			}
		};
	}
}
```

주의: `AnalyticsSettings`의 `llmModel()`이 override 가능해야 하므로 Task 1의 클래스가 final이 아니어야 한다(계획 코드 그대로면 문제 없음).

- [ ] **Step 2: 컴파일 확인** — `./gradlew :analytics:compileJava -q`

- [ ] **Step 3: Commit** — `feat(analytics): F-1 골드셋 스파이크 러너 (수동 실행 전용)`

---

### Task 5: 문서 갱신

**Files:**
- Modify: `analytics/README.md`
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: README** — 실행 절에 분류·스파이크 명령 추가, 설정 키 표에 두 키 추가:

```markdown
    ANTHROPIC_API_KEY=... ../gradlew :analytics:bootRun --args='--analytics.classify-on-startup=true'   # 댓글 분류 배치
    ANTHROPIC_API_KEY=... ../gradlew :analytics:bootRun --args='--analytics.goldset-path=/path/goldset.csv'  # F-1 스파이크
```

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.llm-model` | claude-opus-4-8 | LLM 호출 모델 (스파이크 결과로 확정) |
| `analytics.analyze-batch-limit` | 10 | 1회 실행당 LLM 분석 콘텐츠 수 상한 (비용 가드) |

- [ ] **Step 2: ARCHITECTURE.md** — 3곳:
  1. §5 표: F 행 상태 ⬜→✅ (내용 뒤에 `— F-2(VLM)는 B3에서 실험` 추가), B2 행 상태 ⬜→✅
  2. §8 미결: "LLM 코드 모듈 소속" 행 삭제 (확정됨), "LLM 모델" 행은 유지(스파이크 실행 대기)
  3. §7 결정 기록 맨 위에 행 추가:
     `| 2026-07-12 | LLM 코드 모듈 소속 = analytics 확정 (포트/어댑터, 테스트는 fake). 댓글 분류 배치 개통 — 기본 게이트 off, 비용 가드 app_setting | [plans/2026-07-12-task-f-b2-llm-comment-classification.md](2026-07-12-task-f-b2-llm-comment-classification.md) |`

- [ ] **Step 3: 계획 아카이브** — 이 파일 상태 헤더 ✅로 바꾸고 `git mv`로 plans/archive/로 이동

- [ ] **Step 4: 최종 검증** — `cd analytics && ./test/run.sh && cd .. && ./gradlew :analytics:build -q`

- [ ] **Step 5: Commit** — `docs: F·B2 완료 반영 — LLM 모듈 소속 확정, 분류 배치 개통`

---

## 완료 기준 (DoD)

- `./gradlew :analytics:build` 통과 (CommentClassificationJobTest 4케이스 포함, 실 API 호출 0)
- 기본 설정으로 bootRun 시 미러만 돌고 LLM 빈은 생성 안 됨 (API 키 불필요)
- 사용자 결정 대기 항목이 명확: F-1 스파이크 실행(골드셋 라벨링 + API 비용), 모델 확정

## 다루지 않는 것

- 기준선 스냅샷·VLM·종합 텍스트·content_analyses — **B3** (별도 계획)
- Batches API 50% 절감 — 볼륨 생기면 도입 검토 (§8)
