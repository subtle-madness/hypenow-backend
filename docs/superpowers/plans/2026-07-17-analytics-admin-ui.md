# analytics 어드민 UI + 스케줄러 골격 구현 계획

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** analytics를 상주 웹 서버(8082)로 전환하고 크롤러 어드민 패턴의 `/ui`(잡 트리거 4종·LLM 예상 비용 카드·로그 패널)와 스케줄러 골격(기본 off)을 붙인다.

**Architecture:** 크롤러의 JobService·JobLock·LogBuffer·htmx 폴링 패턴을 analytics에 복제. 잡 빈(LLM 3종)은 `@Lazy` + `analytics.admin-enabled` 조건으로 재배선해 서버 기동 시 Anthropic 키 없이도 뜨고, LLM 잡 첫 트리거 때만 클라이언트가 생성된다. cloud push는 one-shot CLI 보존(cloud 프로파일만 `web-application-type: none` + `mirror-on-startup: true`).

**Tech Stack:** Spring Boot 4.1 (starter-web·thymeleaf 추가), htmx(CDN), logback appender, `@Scheduled`(게이트 off).

**스펙:** [specs/2026-07-17-analytics-admin-ui-design.md](../specs/2026-07-17-analytics-admin-ui-design.md)

**스펙과의 차이 1건:** 스펙 §3은 "기존 CommandLineRunner 무변경"이라 했지만, 잡 빈이 러너 설정
클래스(`*-on-startup` 조건) 안에 있어 서버 모드에선 빈 자체가 없다는 걸 계획 단계에서 발견했다.
잡 `@Bean` 정의를 `JobConfig`로 옮기고 러너 설정에는 CommandLineRunner만 남긴다(Task 4) —
스펙의 의도(CLI one-shot 경로 보존)는 그대로 지켜진다.

**작업 위치:** 워크트리 `.worktrees/analytics-admin`, 브랜치 `feat/analytics-admin-ui`. 커밋 prefix `feat(analytics):`.

---

## 파일 구조

```
analytics/
  build.gradle                                   [수정] starter-web·thymeleaf 추가
  src/main/resources/
    application.yml                              [수정] servlet·8082·admin-enabled·mirror-on-startup false·schedule off
    application-cloud.yml                        [수정] none·mirror-on-startup true·admin-enabled false
    templates/admin.html                         [신설] /ui 단일 페이지
    templates/fragments/logs.html                [신설] 로그 패널 프래그먼트
    templates/fragments/status.html              [신설] 잡 상태 배지 프래그먼트
    static/css/admin.css                         [신설] crawler에서 복사
  src/main/java/com/celfit/analytics/
    admin/JobName.java                           [신설] enum(라벨·slug)
    admin/TriggerType.java                       [신설] enum MANUAL·SCHEDULED
    admin/JobLock.java                           [신설] crawler 복제
    admin/LogBuffer.java                         [신설] crawler 복제(로거만 변경)
    admin/AnalyticsJobService.java               [신설] trigger→락→비동기 실행
    admin/JobCostEstimator.java                  [신설] LLM 대상 건수·예상 비용
    admin/AdminUiController.java                 [신설] /ui·트리거·프래그먼트
    admin/AdminConfig.java                       [신설] jobTaskExecutor 빈
    admin/ScheduleRunner.java                    [신설] 스케줄 골격(기본 off)
    config/JobConfig.java                        [신설] LLM 잡 빈 3종(@Lazy·조건)
    llm/LlmConfig.java                           [수정] 조건에 admin-enabled 추가 + @Lazy
    classify/ClassifyRunner.java                 [수정] 잡 @Bean 제거(러너만 유지)
    analyze/AnalyzeRunner.java                   [수정] 잡 @Bean·headPrecheck 이동(러너만 유지)
    analyze/AccountAnalyzeRunner.java            [수정] 잡 @Bean 제거(러너만 유지)
```

잡 클래스 4종(MirrorJob·CommentClassificationJob·ContentAnalysisJob·AccountAnalysisJob)과
MirrorConfig는 **무변경**.

---

### Task 1: 빌드·프로파일 전환 (상주 서버 + one-shot 병존)

**Files:**
- Modify: `analytics/build.gradle`
- Modify: `analytics/src/main/resources/application.yml`
- Modify: `analytics/src/main/resources/application-cloud.yml`

- [ ] **Step 1: build.gradle에 web·thymeleaf 추가**

`dependencies` 블록의 `spring-boot-starter-jdbc` 줄 다음에 추가:

```groovy
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
```

- [ ] **Step 2: application.yml 전환**

`spring.main.web-application-type: none` 블록을 삭제하고 `server.port` 추가,
`analytics` 블록에 admin·schedule 키 추가, `mirror-on-startup`을 false로. 전체 결과:

```yaml
spring:
  application:
    name: analytics
  # 기본 DataSource 자동설정 비활성 (수동 2개 정의)
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration

server:
  port: 8082   # crawler 8080 · was 8081 다음

analytics:
  admin-enabled: true          # 어드민 UI·잡 트리거 층. cloud one-shot에서는 false
  mirror-on-startup: false     # 서버 모드에선 UI 트리거가 정본. one-shot 미러: --analytics.mirror-on-startup=true --spring.main.web-application-type=none
  classify-on-startup: false   # 댓글 분류 배치 — 실 API 비용이 들어 기본 off. 실행: --analytics.classify-on-startup=true
  analyze-on-startup: false    # 콘텐츠 분석 배치 — 실 API 비용. 실행: --analytics.analyze-on-startup=true
  account-analyze-on-startup: false  # 계정 카피 배치 — 실 API 비용. 실행: --analytics.account-analyze-on-startup=true
  vlm-enabled: false           # F-2 스파이크 검증 전까지 VLM 스킵 (컬럼 NULL)
  schedule:
    enabled: false             # 스케줄 트리거 골격 — 켜려면 true + 잡별 *-cron 지정 (admin-enabled 필요)
    # mirror-cron: "0 0 6 * * *"   # 켜는 날 예시. 미지정 잡은 "-"(비활성)

app:
  datasource:
    raw:
      jdbc-url: jdbc:postgresql://localhost:5433/crawler
      username: crawler
      password: crawler
      driver-class-name: org.postgresql.Driver
    analysis:
      jdbc-url: jdbc:postgresql://localhost:5433/analysis
      username: crawler
      password: crawler
      driver-class-name: org.postgresql.Driver
```

- [ ] **Step 3: application-cloud.yml에 one-shot 보존 명시**

기존 내용 위에 `spring.main`·`analytics` 키 추가. 전체 결과:

```yaml
# 클라우드 타깃 — 로컬 raw를 읽어 오라클 analysis DB(SSH 터널 localhost:15432)에 Flyway+미러 push.
# 사용법: deploy/scripts/tunnel.sh <ssh-host> 로 터널을 연 뒤
#   CLOUD_DB_PASSWORD=... ./gradlew :analytics:bootRun --args='--spring.profiles.active=cloud'
spring:
  main:
    web-application-type: none   # one-shot 배치 보존 — 미러 후 즉시 종료
analytics:
  admin-enabled: false           # 어드민 층 미기동 (베이스 servlet 전환과 무관하게 one-shot)
  mirror-on-startup: true        # 베이스 기본값이 false로 바뀌어 여기서 명시
  flyway-ignore-missing: false   # 클라우드 DB엔 이 repo 마이그레이션만 존재 — 엄격 검증
app:
  datasource:
    analysis:
      jdbc-url: jdbc:postgresql://localhost:15432/analysis
      username: ${CLOUD_DB_USER:celfit}
      password: ${CLOUD_DB_PASSWORD}
```

- [ ] **Step 4: 기존 테스트 회귀 확인**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL (기존 테스트 전부 통과 — 이 태스크는 빈 배선을 안 바꿈)

- [ ] **Step 5: Commit**

```bash
git add analytics/build.gradle analytics/src/main/resources/application.yml analytics/src/main/resources/application-cloud.yml
git commit -m "feat(analytics): 상주 서버 전환(8082) — cloud one-shot 보존, mirror-on-startup 기본 off"
```

---

### Task 2: JobName·TriggerType·JobLock

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/JobName.java`
- Create: `analytics/src/main/java/com/celfit/analytics/admin/TriggerType.java`
- Create: `analytics/src/main/java/com/celfit/analytics/admin/JobLock.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/JobNameTest.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/JobLockTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`JobNameTest.java`:

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobNameTest {

	@Test
	void slug은_소문자_하이픈() {
		assertThat(JobName.MIRROR.slug()).isEqualTo("mirror");
		assertThat(JobName.ACCOUNT_ANALYZE.slug()).isEqualTo("account-analyze");
	}

	@Test
	void fromSlug은_역변환() {
		assertThat(JobName.fromSlug("mirror")).isEqualTo(JobName.MIRROR);
		assertThat(JobName.fromSlug("account-analyze")).isEqualTo(JobName.ACCOUNT_ANALYZE);
	}

	@Test
	void fromSlug은_모르는_값에_예외() {
		assertThatThrownBy(() -> JobName.fromSlug("nope")).isInstanceOf(IllegalArgumentException.class);
	}
}
```

`JobLockTest.java`:

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobLockTest {

	@Test
	void 같은_잡은_해제_전까지_재획득_불가() {
		JobLock lock = new JobLock();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isFalse();
		assertThat(lock.isRunning(JobName.MIRROR)).isTrue();
		lock.release(JobName.MIRROR);
		assertThat(lock.isRunning(JobName.MIRROR)).isFalse();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
	}

	@Test
	void 잡별_락은_독립() {
		JobLock lock = new JobLock();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
		assertThat(lock.tryAcquire(JobName.ANALYZE)).isTrue();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.*"`
Expected: COMPILE FAILURE (JobName·JobLock 미존재)

- [ ] **Step 3: 구현**

`JobName.java`:

```java
package com.celfit.analytics.admin;

/** 어드민이 트리거하는 analytics 잡. 라벨은 UI 표기 원본. */
public enum JobName {
	MIRROR("미러 — 분석 뷰 → analysis DB"),
	CLASSIFY("댓글 분류 (LLM)"),
	ANALYZE("콘텐츠 분석 (LLM)"),
	ACCOUNT_ANALYZE("계정 카피 (LLM)");

	private final String label;

	JobName(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** URL 경로 조각 — account-analyze 식 소문자 하이픈. */
	public String slug() {
		return name().toLowerCase().replace('_', '-');
	}

	public static JobName fromSlug(String slug) {
		return valueOf(slug.toUpperCase().replace('-', '_'));
	}
}
```

`TriggerType.java`:

```java
package com.celfit.analytics.admin;

public enum TriggerType { MANUAL, SCHEDULED }
```

`JobLock.java` (crawler `JobLock` 복제 — §4-4가 모듈 간 공유를 금지해 의도된 중복):

```java
package com.celfit.analytics.admin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 같은 잡 동시 실행 방지 (단일 인스턴스 전제 — 인프로세스 락). crawler JobLock과 의도된 중복. */
public class JobLock {

	private final ConcurrentHashMap<JobName, AtomicBoolean> locks = new ConcurrentHashMap<>();

	public boolean tryAcquire(JobName job) {
		return locks.computeIfAbsent(job, k -> new AtomicBoolean(false)).compareAndSet(false, true);
	}

	public void release(JobName job) {
		AtomicBoolean l = locks.get(job);
		if (l != null) l.set(false);
	}

	public boolean isRunning(JobName job) {
		AtomicBoolean l = locks.get(job);
		return l != null && l.get();
	}
}
```

(빈 등록은 Task 5의 `AdminConfig`에서 — 여기서는 순수 클래스만.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.*"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin analytics/src/test/java/com/celfit/analytics/admin
git commit -m "feat(analytics): 어드민 잡 어휘(JobName·TriggerType)와 잡별 락"
```

---

### Task 3: LogBuffer — UI 로그 패널의 인메모리 소스

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/LogBuffer.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/LogBufferTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LogBufferTest {

	private final LogBuffer buffer = new LogBuffer();

	@AfterEach
	void tearDown() {
		buffer.unregister();
	}

	@Test
	void analytics_로거의_로그를_최신순으로_보관() {
		buffer.register();
		Logger log = LoggerFactory.getLogger("com.celfit.analytics.admin.LogBufferTest");
		log.info("첫 줄");
		log.warn("둘째 줄");
		assertThat(buffer.lines()).hasSize(2);
		assertThat(buffer.lines().get(0).message()).isEqualTo("둘째 줄");
		assertThat(buffer.lines().get(0).level()).isEqualTo("WARN");
		assertThat(buffer.lines().get(0).logger()).isEqualTo("LogBufferTest");
	}

	@Test
	void 최대_200줄_초과분은_버림() {
		buffer.register();
		Logger log = LoggerFactory.getLogger("com.celfit.analytics.admin.LogBufferTest");
		for (int i = 0; i < 205; i++) {
			log.info("line {}", i);
		}
		assertThat(buffer.lines()).hasSize(200);
		assertThat(buffer.lines().get(0).message()).isEqualTo("line 204");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.LogBufferTest"`
Expected: COMPILE FAILURE (LogBuffer 미존재)

- [ ] **Step 3: 구현 — crawler LogBuffer 복제, 로거·라이프사이클 어노테이션만 변경**

crawler 원본과의 차이: 대상 로거 `com.celfit.analytics`, `@Component` 대신 Task 5의
`AdminConfig`에서 빈 등록(조건부), `@PostConstruct/@PreDestroy` 대신 public 메서드
(테스트·빈 라이프사이클 양쪽에서 명시 호출).

```java
package com.celfit.analytics.admin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.LoggerFactory;

/**
 * com.celfit.analytics 로거의 최근 로그를 메모리에 보관해 UI 실행 로그 패널에 노출.
 * 프로세스 재시작 시 사라지는 휘발성 뷰 — 영속 이력 테이블은 두지 않기로 결정(스펙 §2).
 * crawler LogBuffer와 의도된 중복 (§4-4 모듈 공유 금지).
 */
public class LogBuffer extends AppenderBase<ILoggingEvent> {

	public record Line(String time, String level, String logger, String message) {}

	static final int MAX_LINES = 200;
	private static final DateTimeFormatter TIME =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private final Deque<Line> lines = new ConcurrentLinkedDeque<>();

	public void register() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
		setContext(ctx);
		start();
		ctx.getLogger("com.celfit.analytics").addAppender(this);
	}

	public void unregister() {
		if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
			ctx.getLogger("com.celfit.analytics").detachAppender(this);
		}
		stop();
	}

	@Override
	protected void append(ILoggingEvent event) {
		String message = event.getFormattedMessage();
		if (event.getThrowableProxy() != null) {
			message += " — " + event.getThrowableProxy().getClassName()
					+ ": " + event.getThrowableProxy().getMessage();
		}
		lines.addFirst(new Line(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())),
				event.getLevel().toString(), shortLogger(event.getLoggerName()), message));
		while (lines.size() > MAX_LINES) lines.pollLast();
	}

	/** 최신순. */
	public List<Line> lines() {
		return List.copyOf(lines);
	}

	private static String shortLogger(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.LogBufferTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/LogBuffer.java analytics/src/test/java/com/celfit/analytics/admin/LogBufferTest.java
git commit -m "feat(analytics): LogBuffer — UI 로그 패널용 인메모리 로그 버퍼 (crawler 패턴 복제)"
```

---

### Task 4: 잡 빈 재배선 — @Lazy + admin-enabled 조건

서버 모드(게이트 전부 off)에서도 UI가 LLM 잡을 부를 수 있어야 한다. 현재 잡 빈은
러너 설정 클래스(`*-on-startup` 조건) 안에 있어 게이트 off면 빈 자체가 없다.
잡 빈을 `JobConfig`로 옮기고 `@Lazy`로 만들어 **서버 기동 시 Anthropic 키가 없어도 뜨고**,
LLM 잡 첫 트리거 때만 클라이언트가 생성되게 한다.

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/classify/ClassifyRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AnalyzeRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalyzeRunner.java`
- Test: `analytics/src/test/java/com/celfit/analytics/config/JobConfigTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 — 조건별 빈 정의 존재 여부**

`@Lazy` 빈은 정의만 등록되고 인스턴스화되지 않으므로 `containsBeanDefinition`으로
검사한다(DataSource 없이 컨텍스트가 뜬다).

```java
package com.celfit.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(JobConfig.class);

	@Test
	void admin_on이면_게이트_off여도_잡_빈_정의가_있다() {
		runner.withPropertyValues("analytics.admin-enabled=true")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("accountAnalysisJob")).isTrue();
				});
	}

	@Test
	void admin_off_게이트_off면_잡_빈이_없다() {
		runner.withPropertyValues("analytics.admin-enabled=false")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isFalse();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isFalse();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("accountAnalysisJob")).isFalse();
				});
	}

	@Test
	void admin_off여도_개별_게이트_on이면_해당_잡_빈_정의가_있다() {
		runner.withPropertyValues("analytics.admin-enabled=false", "analytics.analyze-on-startup=true")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isFalse();
				});
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.config.JobConfigTest"`
Expected: COMPILE FAILURE (JobConfig 미존재)

- [ ] **Step 3: JobConfig 신설 — 잡 빈 3종을 러너에서 이동**

`headPrecheck()`도 `AnalyzeRunner`에서 여기로 옮긴다(잡 배선의 일부).

```java
package com.celfit.analytics.config;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.ContentAttributePort;
import com.celfit.analytics.llm.SynthesisPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLM 잡 빈 배선 — one-shot 러너(*-on-startup)와 어드민(admin-enabled) 양쪽이 쓴다.
 * 전부 @Lazy: 어드민 모드에서 서버 기동 시 Anthropic 키가 없어도 뜨고,
 * 첫 트리거 때 포트→클라이언트 체인이 생성된다(키 없으면 그 잡만 실패 — 로그 패널에 노출).
 */
@Configuration
public class JobConfig {

	/**
	 * 썸네일 서명 URL 생존 확인 (인스타 CDN은 수집 후 ~4일이면 403 — 2026-07-14 실측).
	 * 만료 썸네일을 VLM에 넘기면 Anthropic 쪽 fetch가 실패하므로 호출 전에 거른다.
	 */
	public static Predicate<String> headPrecheck() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return url -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.method("HEAD", HttpRequest.BodyPublishers.noBody())
						.timeout(Duration.ofSeconds(10))
						.build();
				int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
				return status >= 200 && status < 300;
			} catch (Exception e) {
				return false;
			}
		};
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.admin-enabled:false}")
	public CommentClassificationJob commentClassificationJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		return new CommentClassificationJob(rawJdbcTemplate, analysisDataSource, port, settings);
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			SynthesisPort synthesis, ContentAttributePort attributes, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off — 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, synthesis,
				attributes, settings, thumbnailEnabled, headPrecheck());
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings) {
		return new AccountAnalysisJob(analysisDataSource, port, settings);
	}
}
```

- [ ] **Step 4: LlmConfig 조건 확장 + @Lazy**

클래스 조건에 `admin-enabled`를 추가하고 모든 `@Bean`에 `@Lazy`를 단다.
클래스 Javadoc 마지막에 한 줄 추가, import에 `org.springframework.context.annotation.Lazy` 추가:

```java
/**
 * (기존 Javadoc 유지)
 * 어드민 모드(analytics.admin-enabled)에서는 UI 트리거가 소비자 — 전 빈 @Lazy라
 * 기동 시 키가 없어도 뜨고, LLM 잡 첫 실행 때 생성된다.
 */
@Configuration
@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}"
		+ " or ${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
public class LlmConfig {
```

기존 5개 `@Bean` 메서드(anthropicClient·commentClassificationPort·synthesisPort·
beautyTaxonomyLoader·contentAttributePort·accountSynthesisPort) 각각에 `@Lazy` 추가.
메서드 본문은 무변경.

- [ ] **Step 5: 러너 3종 축소 — 잡 @Bean 제거, CommandLineRunner만 유지**

`ClassifyRunner.java` 전체 교체:

```java
package com.celfit.analytics.classify;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 분류 배치 기동 트리거 — analytics.classify-on-startup=true일 때만 (실 API 비용). 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class ClassifyRunner {

	@Bean
	public CommandLineRunner classifyOnStartup(CommentClassificationJob job) {
		return args -> job.run();
	}
}
```

`AnalyzeRunner.java` 전체 교체 (headPrecheck는 JobConfig로 이동):

```java
package com.celfit.analytics.analyze;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 분석 배치 기동 트리거 — analytics.analyze-on-startup=true일 때만 (실 API 비용). 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.analyze-on-startup", havingValue = "true")
public class AnalyzeRunner {

	@Bean
	public CommandLineRunner analyzeOnStartup(ContentAnalysisJob job) {
		return args -> job.run();
	}
}
```

`AccountAnalyzeRunner.java` 전체 교체:

```java
package com.celfit.analytics.analyze;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 계정 카피 배치 기동 트리거 — analytics.account-analyze-on-startup=true일 때만 (실 API 비용). 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.account-analyze-on-startup", havingValue = "true")
public class AccountAnalyzeRunner {

	@Bean
	public CommandLineRunner accountAnalyzeOnStartup(AccountAnalysisJob job) {
		return args -> job.run();
	}
}
```

※ `JobConfig`가 `AnalyticsSettings`를 참조하므로 import는
`com.celfit.analytics.config.AnalyticsSettings` — JobConfig가 같은 패키지라 import 불필요.
`AnalyzeRunner.headPrecheck()`를 참조하던 곳이 없는지 확인:
`grep -rn "headPrecheck" analytics/src` → JobConfig만 나와야 한다.

- [ ] **Step 6: 전체 테스트 확인**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL — JobConfigTest 3건 포함 전부 통과.
(기존 `ContentAnalysisJobTest` 등은 잡 클래스를 직접 생성하므로 영향 없음)

- [ ] **Step 7: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/config/JobConfig.java analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java analytics/src/main/java/com/celfit/analytics/classify/ClassifyRunner.java analytics/src/main/java/com/celfit/analytics/analyze/AnalyzeRunner.java analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalyzeRunner.java analytics/src/test/java/com/celfit/analytics/config/JobConfigTest.java
git commit -m "feat(analytics): 잡 빈 @Lazy 재배선 — admin-enabled에서도 잡 빈 정의, 키는 첫 트리거 때만 필요"
```

---

### Task 5: AnalyticsJobService — 트리거·락·비동기 실행

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java`
- Create: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

동기 실행(SyncTaskExecutor)으로 결정적으로 만든다 — crawler 테스트 컨벤션.
`ObjectProvider`는 Spring 5.1부터 `getObject()` 외 전부 default 메서드라 익명 구현이 가능하다.

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;

class AnalyticsJobServiceTest {

	private static <T> ObjectProvider<T> provider(T instance) {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				return instance;
			}
		};
	}

	private final JobLock lock = new JobLock();
	private final MirrorJob mirrorJob = mock(MirrorJob.class);
	private final MirrorRegistry registry = new MirrorRegistry(List.of());
	private final ContentAnalysisJob analyzeJob = mock(ContentAnalysisJob.class);

	private AnalyticsJobService service() {
		return new AnalyticsJobService(lock, new SyncTaskExecutor(), mirrorJob, registry,
				provider(mock(CommentClassificationJob.class)), provider(analyzeJob),
				provider(mock(AccountAnalysisJob.class)));
	}

	@Test
	void 트리거는_잡을_실행하고_ACCEPTED를_반환() {
		when(analyzeJob.run()).thenReturn(3);
		var result = service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse(); // 동기 실행 후 해제
	}

	@Test
	void 실행_중이면_BUSY() {
		lock.tryAcquire(JobName.MIRROR);
		var result = service().trigger(JobName.MIRROR, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.BUSY);
	}

	@Test
	void 잡이_예외를_던져도_락은_해제() {
		when(analyzeJob.run()).thenThrow(new IllegalStateException("boom"));
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse();
	}

	@Test
	void 지연_잡_공급자는_실행_시점에만_조회() {
		AtomicInteger resolved = new AtomicInteger();
		ObjectProvider<ContentAnalysisJob> lazyProvider = new ObjectProvider<>() {
			@Override
			public ContentAnalysisJob getObject() {
				resolved.incrementAndGet();
				return analyzeJob;
			}
		};
		AnalyticsJobService service = new AnalyticsJobService(lock, new SyncTaskExecutor(),
				mirrorJob, registry, provider(mock(CommentClassificationJob.class)),
				lazyProvider, provider(mock(AccountAnalysisJob.class)));
		assertThat(resolved.get()).isZero(); // 생성만으로는 미조회
		service.trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(resolved.get()).isEqualTo(1);
	}
}
```

※ `MirrorJob`은 final 클래스다 — `mock(MirrorJob.class)`은 mockito의 인라인 mock maker가
필요하다. Boot 4의 `spring-boot-starter-test`는 mockito-core 5.x(인라인 기본)라 동작한다.
컴파일·실행이 안 되면 `MirrorJob` mock 대신 실제 인스턴스 + 빈 레지스트리(`List.of()`)로
대체해도 무방(빈 레지스트리면 mirror 호출이 없다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: COMPILE FAILURE (AnalyticsJobService 미존재)

- [ ] **Step 3: 구현**

`AnalyticsJobService.java`:

```java
package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import com.celfit.analytics.mirror.MirrorSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

/**
 * 어드민·스케줄 공용 잡 트리거 — 잡별 락으로 중복 실행 차단, 비동기 실행.
 * LLM 잡은 ObjectProvider로 실행 시점에 조회(@Lazy 빈 — 키 없으면 그 잡만 실패).
 */
public class AnalyticsJobService {

	public enum TriggerResult { ACCEPTED, BUSY }

	private static final Logger log = LoggerFactory.getLogger(AnalyticsJobService.class);

	private final JobLock lock;
	private final TaskExecutor executor;
	private final MirrorJob mirrorJob;
	private final MirrorRegistry registry;
	private final ObjectProvider<CommentClassificationJob> classifyJob;
	private final ObjectProvider<ContentAnalysisJob> analyzeJob;
	private final ObjectProvider<AccountAnalysisJob> accountAnalyzeJob;

	public AnalyticsJobService(JobLock lock, TaskExecutor executor,
			MirrorJob mirrorJob, MirrorRegistry registry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob) {
		this.lock = lock;
		this.executor = executor;
		this.mirrorJob = mirrorJob;
		this.registry = registry;
		this.classifyJob = classifyJob;
		this.analyzeJob = analyzeJob;
		this.accountAnalyzeJob = accountAnalyzeJob;
	}

	public TriggerResult trigger(JobName job, TriggerType triggerType) {
		if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
		executor.execute(() -> {
			try {
				log.info("{} 시작 (trigger={})", job, triggerType);
				run(job);
			} catch (Exception e) {
				log.error("{} 잡 실패", job, e);
			} finally {
				lock.release(job);
			}
		});
		return TriggerResult.ACCEPTED;
	}

	public boolean isRunning(JobName job) {
		return lock.isRunning(job);
	}

	private void run(JobName job) {
		switch (job) {
			case MIRROR -> {
				int total = 0;
				for (MirrorSpec<?> spec : registry.specs()) {
					int rows = mirrorJob.mirror(spec);
					log.info("mirrored {} rows: {} -> {}", rows, spec.viewName(), spec.tableName());
					total += rows;
				}
				log.info("mirror complete ({} targets, {} rows)", registry.specs().size(), total);
			}
			case CLASSIFY -> classifyJob.getObject().run();
			case ANALYZE -> analyzeJob.getObject().run();
			case ACCOUNT_ANALYZE -> accountAnalyzeJob.getObject().run();
		}
	}
}
```

(LLM 잡 3종은 자체적으로 "complete" 로그를 남기므로 여기서 중복 로그를 만들지 않는다.
미러는 잡 클래스에 완료 로그가 없어 서비스가 남긴다 — MirrorRunner와 같은 포맷.)

`AdminConfig.java` — 어드민 층 빈 일괄 등록(조건 한 곳):

```java
package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** 어드민 층 배선 — analytics.admin-enabled=true일 때만 (cloud one-shot은 false). */
@Configuration
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminConfig {

	@Bean
	public JobLock jobLock() {
		return new JobLock();
	}

	/** 잡 비동기 실행용 — 테스트는 SyncTaskExecutor로 대체해 결정적으로 만든다. */
	@Bean
	public TaskExecutor jobTaskExecutor() {
		return new SimpleAsyncTaskExecutor("job-");
	}

	@Bean
	public AnalyticsJobService analyticsJobService(JobLock jobLock, TaskExecutor jobTaskExecutor,
			MirrorJob mirrorJob, MirrorRegistry mirrorRegistry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob) {
		return new AnalyticsJobService(jobLock, jobTaskExecutor, mirrorJob, mirrorRegistry,
				classifyJob, analyzeJob, accountAnalyzeJob);
	}

	@Bean(initMethod = "register", destroyMethod = "unregister")
	public LogBuffer logBuffer() {
		return new LogBuffer();
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin analytics/src/test/java/com/celfit/analytics/admin
git commit -m "feat(analytics): AnalyticsJobService — 잡 트리거·락·비동기 실행 + 어드민 배선"
```

---

### Task 6: JobCostEstimator — LLM 대상 건수·예상 비용

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/JobCostEstimator.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` (빈 추가)
- Test: `analytics/src/test/java/com/celfit/analytics/admin/JobCostEstimatorTest.java`

단가는 ARCHITECTURE §6 실측값(추정치임을 카드에 명시):
- CLASSIFY: 게시물당 $0.0122(haiku)~$0.061(opus) — 1,000건당 $12.2/$61
- ANALYZE: 건당 $0.03~0.05 (VLM 실측 — 캡션 종합 포함 추정)
- ACCOUNT_ANALYZE: 단가 미실측 → 비용 없이 건수만

- [ ] **Step 1: 실패하는 테스트 작성 — 순수 계산부**

DB 카운트 조회는 통합 환경이 필요하므로 순수 계산(`CostCard.of`)만 단위 테스트하고,
카운트 쿼리는 Task 9의 수동 E2E에서 실데이터로 확인한다.

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobCostEstimatorTest {

	@Test
	void 단가_범위와_건수로_비용_범위를_계산() {
		var card = JobCostEstimator.CostCard.of(JobName.CLASSIFY, 100, "0.0122", "0.061", "노트");
		assertThat(card.targets()).isEqualTo(100);
		assertThat(card.minUsd()).isEqualByComparingTo("1.22");
		assertThat(card.maxUsd()).isEqualByComparingTo("6.10");
		assertThat(card.label()).isEqualTo(JobName.CLASSIFY.label());
	}

	@Test
	void 단가_미실측이면_비용은_null() {
		var card = JobCostEstimator.CostCard.of(JobName.ACCOUNT_ANALYZE, 7, null, null, "단가 미실측");
		assertThat(card.minUsd()).isNull();
		assertThat(card.maxUsd()).isNull();
		assertThat(card.targets()).isEqualTo(7);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.JobCostEstimatorTest"`
Expected: COMPILE FAILURE

- [ ] **Step 3: 구현**

대상 선정 로직은 각 잡의 것을 그대로 복제한다(대상 수가 잡 실행과 일치해야 카드가 정직).

```java
package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLM 잡 예상 비용 카드 — 실행 전 "대상 몇 건, 대략 얼마"를 보여준다 (crawler 잡 비용 카드 UX).
 * 대상 선정 쿼리는 각 잡의 로직 복제 — 잡과 어긋나면 카드가 거짓말을 하므로 잡 수정 시 함께 고칠 것.
 * 단가는 ARCHITECTURE §6 실측값 기반 추정치.
 */
public class JobCostEstimator {

	public record CostCard(JobName job, String label, int targets,
			BigDecimal minUsd, BigDecimal maxUsd, String note) {

		public static CostCard of(JobName job, int targets, String unitMin, String unitMax, String note) {
			BigDecimal count = BigDecimal.valueOf(targets);
			return new CostCard(job, job.label(), targets,
					unitMin == null ? null : new BigDecimal(unitMin).multiply(count),
					unitMax == null ? null : new BigDecimal(unitMax).multiply(count),
					note);
		}
	}

	// §6 실측: 댓글 분류 1,000건당 haiku $12.2 / opus $61 → 게시물당
	static final String CLASSIFY_UNIT_MIN = "0.0122";
	static final String CLASSIFY_UNIT_MAX = "0.061";
	// §6 실측: VLM 건당 $0.03~0.05 (캡션 종합 포함 추정)
	static final String ANALYZE_UNIT_MIN = "0.03";
	static final String ANALYZE_UNIT_MAX = "0.05";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	public JobCostEstimator(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.settings = settings;
	}

	public List<CostCard> costCards() {
		return List.of(
				CostCard.of(JobName.CLASSIFY, classifyTargets(), CLASSIFY_UNIT_MIN, CLASSIFY_UNIT_MAX,
						"게시물당 $0.0122(haiku)~$0.061(opus) — 07-12 실측. 댓글 수집 MVP 제외로 대개 0건"),
				CostCard.of(JobName.ANALYZE, analyzeTargets(), ANALYZE_UNIT_MIN, ANALYZE_UNIT_MAX,
						"건당 $0.03~0.05 — VLM 07-14 실측 기반 추정"),
				CostCard.of(JobName.ACCOUNT_ANALYZE, accountAnalyzeTargets(), null, null,
						"단가 미실측 — 건수만 표시 (계정당 1콜)"));
	}

	/** CommentClassificationJob.run()의 대상 선정 복제. */
	int classifyTargets() {
		Set<String> classified = new HashSet<>(analysis.queryForList(
				"SELECT DISTINCT short_code FROM comment_classifications", String.class));
		long count = raw.queryForList(
				"SELECT DISTINCT short_code FROM analytics.v_content_comments", String.class)
				.stream().filter(sc -> !classified.contains(sc)).count();
		return (int) Math.min(count, settings.analyzeBatchLimit());
	}

	/** ContentAnalysisJob.run()의 대상 선정 복제 (양쪽 DB 교집합 + 숙성 가드). */
	int analyzeTargets() {
		Set<String> withBaseline = new HashSet<>(raw.queryForList(
				"SELECT short_code FROM analytics.v_analysis_baseline", String.class));
		long count = analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				  AND c.posted_at <= now() - make_interval(days => ?)""",
				String.class, settings.analyzeMaturityDays())
				.stream().filter(withBaseline::contains).count();
		return (int) Math.min(count, settings.analyzeBatchLimit());
	}

	/** AccountAnalysisJob.run()의 대상 선정 쿼리를 count로 감쌈. */
	int accountAnalyzeTargets() {
		Integer count = analysis.queryForObject("""
				SELECT count(*) FROM (
				  SELECT s.handle
				  FROM account_summaries s
				  LEFT JOIN LATERAL (
				    SELECT a.input_last_posted_at, a.analyzed_at
				    FROM account_analyses a WHERE a.handle = s.handle
				    ORDER BY a.analyzed_at DESC LIMIT 1
				  ) latest ON true
				  WHERE latest.analyzed_at IS NULL
				     OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				         AND latest.analyzed_at < now() - make_interval(days => ?))
				  LIMIT ?
				) t""", Integer.class,
				settings.accountAnalyzeCooldownDays(), settings.accountAnalyzeBatchLimit());
		return count == null ? 0 : count;
	}
}
```

`AdminConfig`에 빈 추가 (기존 import에 `AnalyticsSettings`·`DataSource`·`JdbcTemplate`·`Qualifier` 추가):

```java
	@Bean
	public JobCostEstimator jobCostEstimator(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource, AnalyticsSettings settings) {
		return new JobCostEstimator(rawJdbcTemplate, analysisDataSource, settings);
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.JobCostEstimatorTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin analytics/src/test/java/com/celfit/analytics/admin/JobCostEstimatorTest.java
git commit -m "feat(analytics): LLM 잡 예상 비용 카드 — 대상 건수 × §6 실측 단가"
```

---

### Task 7: AdminUiController + 템플릿·정적 자원

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java`
- Create: `analytics/src/main/resources/templates/admin.html`
- Create: `analytics/src/main/resources/templates/fragments/logs.html`
- Create: `analytics/src/main/resources/templates/fragments/status.html`
- Create: `analytics/src/main/resources/static/css/admin.css` (crawler에서 복사)
- Test: `analytics/src/test/java/com/celfit/analytics/admin/AdminUiControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

Spring Boot 4 주의: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지.

```java
package com.celfit.analytics.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminUiController.class)
@TestPropertySource(properties = "analytics.admin-enabled=true") // 컨트롤러의 @ConditionalOnProperty 게이트
class AdminUiControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AnalyticsJobService jobService;

	@MockitoBean
	JobCostEstimator costEstimator;

	@MockitoBean
	LogBuffer logBuffer;

	@Test
	void ui_페이지는_잡_버튼과_비용_카드를_렌더() throws Exception {
		when(costEstimator.costCards()).thenReturn(List.of(
				JobCostEstimator.CostCard.of(JobName.ANALYZE, 5, "0.03", "0.05", "노트")));
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("미러")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("콘텐츠 분석")));
	}

	@Test
	void 트리거는_서비스를_부르고_리다이렉트() throws Exception {
		when(jobService.trigger(eq(JobName.MIRROR), eq(TriggerType.MANUAL)))
				.thenReturn(AnalyticsJobService.TriggerResult.ACCEPTED);
		mvc.perform(post("/ui/jobs/mirror"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/ui"))
				.andExpect(flash().attributeExists("message"));
		verify(jobService).trigger(JobName.MIRROR, TriggerType.MANUAL);
	}

	@Test
	void account_analyze_슬러그도_매핑() throws Exception {
		when(jobService.trigger(eq(JobName.ACCOUNT_ANALYZE), eq(TriggerType.MANUAL)))
				.thenReturn(AnalyticsJobService.TriggerResult.BUSY);
		mvc.perform(post("/ui/jobs/account-analyze"))
				.andExpect(status().is3xxRedirection());
	}

	@Test
	void 모르는_잡은_404() throws Exception {
		mvc.perform(post("/ui/jobs/nope")).andExpect(status().isNotFound());
	}

	@Test
	void 로그_프래그먼트() throws Exception {
		when(logBuffer.lines()).thenReturn(List.of(
				new LogBuffer.Line("12:00:00", "INFO", "MirrorJob", "mirror complete")));
		mvc.perform(get("/ui/fragments/logs"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("mirror complete")));
	}

	@Test
	void 상태_프래그먼트는_실행_중_배지() throws Exception {
		when(jobService.isRunning(JobName.MIRROR)).thenReturn(true);
		mvc.perform(get("/ui/fragments/status"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("실행 중")));
	}
}
```

※ 컨트롤러에는 반드시 `@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")`를
단다 — `@Controller`는 컴포넌트 스캔 대상이라 게이트가 없으면 cloud one-shot(admin 빈 없음)에서
`AnalyticsJobService` 주입 실패로 기동이 깨진다. 테스트의 `@TestPropertySource`가 이 게이트를 연다.

**주의**: `LogBuffer`는 logback `AppenderBase` 상속이라 `@MockitoBean` mock 생성은 문제없으나,
혹시 mockito가 거부하면 `@MockitoBean` 대신 실제 `LogBuffer`를 `@TestConfiguration` 빈으로
등록하고(register 호출 없이) 로그 프래그먼트 테스트는 빈 목록 200 OK만 검증으로 완화한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AdminUiControllerTest"`
Expected: COMPILE FAILURE

- [ ] **Step 3: 컨트롤러 구현**

```java
package com.celfit.analytics.admin;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 어드민 화면 — 잡 트리거·비용 카드·상태·로그 (crawler /ui 패턴). cloud one-shot에선 게이트 off. */
@Controller
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminUiController {

	public record JobStatus(String label, boolean running) {}

	private final AnalyticsJobService jobService;
	private final JobCostEstimator costEstimator;
	private final LogBuffer logBuffer;

	public AdminUiController(AnalyticsJobService jobService, JobCostEstimator costEstimator,
			LogBuffer logBuffer) {
		this.jobService = jobService;
		this.costEstimator = costEstimator;
		this.logBuffer = logBuffer;
	}

	@GetMapping("/")
	public String root() {
		return "redirect:/ui";
	}

	@GetMapping("/ui")
	public String ui(Model model) {
		model.addAttribute("jobs", JobName.values());
		model.addAttribute("costs", costEstimator.costCards());
		return "admin";
	}

	@PostMapping("/ui/jobs/{slug}")
	public String trigger(@PathVariable String slug, RedirectAttributes ra) {
		JobName job;
		try {
			job = JobName.fromSlug(slug);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "모르는 잡: " + slug);
		}
		String message = switch (jobService.trigger(job, TriggerType.MANUAL)) {
			case ACCEPTED -> job.label() + " 실행 시작";
			case BUSY -> job.label() + " — 이미 실행 중입니다";
		};
		ra.addFlashAttribute("message", message);
		return "redirect:/ui";
	}

	@GetMapping("/ui/fragments/logs")
	public String logs(Model model) {
		model.addAttribute("lines", logBuffer.lines());
		return "fragments/logs :: panel";
	}

	@GetMapping("/ui/fragments/status")
	public String status(Model model) {
		List<JobStatus> statuses = Arrays.stream(JobName.values())
				.map(j -> new JobStatus(j.label(), jobService.isRunning(j)))
				.toList();
		model.addAttribute("statuses", statuses);
		return "fragments/status :: badges";
	}
}
```

- [ ] **Step 4: 템플릿 작성**

`templates/admin.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>hypenow analytics — 잡 실행</title>
    <link rel="stylesheet" href="/css/admin.css"/>
    <script src="https://unpkg.com/htmx.org@1.9.12"></script>
</head>
<body>
<main>
<h1>analytics 잡 실행</h1>
<div class="flash" th:if="${message}" th:text="${message}"></div>

<div class="job-forms">
    <form method="post" th:each="j : ${jobs}" th:action="@{'/ui/jobs/' + ${j.slug()}}">
        <button type="submit" class="primary" th:text="${j.label()}"></button>
    </form>
</div>

<h2>현재 상태</h2>
<div hx-get="/ui/fragments/status" hx-trigger="load, every 3s"></div>

<h2>예상 비용 (LLM 잡 — 추정치)</h2>
<div class="job-costs">
    <div class="card job-cost" th:each="c : ${costs}">
        <h3 th:text="${c.label()}"></h3>
        <p class="job-cost-targets">대상 <b th:text="${c.targets()}"></b>건</p>
        <p class="job-cost-usd" th:if="${c.minUsd() != null}">
            예상 비용
            <b th:text="'$' + ${#numbers.formatDecimal(c.minUsd(), 1, 3)} + '~$' + ${#numbers.formatDecimal(c.maxUsd(), 1, 3)}"></b>
        </p>
        <p class="job-cost-note" th:text="${c.note()}"></p>
    </div>
</div>

<h2>실행 로그</h2>
<div hx-get="/ui/fragments/logs" hx-trigger="load, every 3s"></div>
</main>
</body>
</html>
```

`templates/fragments/logs.html` (crawler와 동일 구조):

```html
<div th:fragment="panel" xmlns:th="http://www.thymeleaf.org">
    <div class="log-panel" th:if="${!lines.isEmpty()}">
        <div class="log-line" th:each="l : ${lines}" th:classappend="${l.level()}">
            <span class="log-time" th:text="${l.time()}"></span>
            <span class="log-level" th:text="${l.level()}"></span>
            <span class="log-logger" th:text="${l.logger()}"></span>
            <span class="log-msg" th:text="${l.message()}"></span>
        </div>
    </div>
    <p class="hint" th:if="${lines.isEmpty()}">아직 로그 없음 — 잡을 실행하면 여기에 표시됩니다.</p>
</div>
```

`templates/fragments/status.html`:

```html
<div th:fragment="badges" xmlns:th="http://www.thymeleaf.org">
    <div class="job-statuses">
        <span class="job-status" th:each="s : ${statuses}">
            <span th:text="${s.label()}"></span>
            <span class="badge RUNNING" th:if="${s.running()}">실행 중</span>
            <span class="badge idle" th:unless="${s.running()}">유휴</span>
        </span>
    </div>
</div>
```

- [ ] **Step 5: 정적 자원 복사**

```bash
mkdir -p analytics/src/main/resources/static/css
cp crawler/src/main/resources/static/css/admin.css analytics/src/main/resources/static/css/admin.css
```

`admin.css` 끝에 상태 배지용 최소 스타일 추가:

```css
/* analytics 어드민 — 잡 상태 배지 */
.job-statuses { display: flex; gap: 16px; flex-wrap: wrap; }
.job-status { display: inline-flex; gap: 6px; align-items: center; }
.badge.idle { background: #e5e7eb; color: #374151; }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AdminUiControllerTest"`
Expected: PASS (6 tests)

- [ ] **Step 7: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java analytics/src/main/resources/templates analytics/src/main/resources/static analytics/src/test/java/com/celfit/analytics/admin/AdminUiControllerTest.java
git commit -m "feat(analytics): 어드민 /ui — 잡 트리거 4종·비용 카드·상태 배지·로그 패널"
```

---

### Task 8: ScheduleRunner — 스케줄 골격 (기본 off)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/ScheduleRunnerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 — 게이트 검증**

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ScheduleRunnerTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(AnalyticsJobService.class, () -> mock(AnalyticsJobService.class))
			.withUserConfiguration(ScheduleRunner.class);

	@Test
	void 기본은_비활성() {
		runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ScheduleRunner.class));
	}

	@Test
	void enabled면_활성() {
		runner.withPropertyValues("analytics.schedule.enabled=true")
				.run(ctx -> assertThat(ctx).hasSingleBean(ScheduleRunner.class));
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.ScheduleRunnerTest"`
Expected: COMPILE FAILURE

- [ ] **Step 3: 구현**

크론 기본값 `-`는 Spring의 `Scheduled.CRON_DISABLED` — 프로퍼티를 지정한 잡만 돈다.

```java
package com.celfit.analytics.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스케줄 트리거 골격 — analytics.schedule.enabled=true일 때만 활성 (기본 off, 크롤러 패턴).
 * 잡별 크론 미지정("-")이면 그 잡은 안 돈다. admin-enabled=true 전제(AnalyticsJobService 필요).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "analytics.schedule", name = "enabled", havingValue = "true")
public class ScheduleRunner {

	private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

	private final AnalyticsJobService jobService;

	public ScheduleRunner(AnalyticsJobService jobService) {
		this.jobService = jobService;
	}

	@Scheduled(cron = "${analytics.schedule.mirror-cron:-}")
	void mirror() {
		log.info("스케줄 mirror: {}", jobService.trigger(JobName.MIRROR, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.classify-cron:-}")
	void classify() {
		log.info("스케줄 classify: {}", jobService.trigger(JobName.CLASSIFY, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.analyze-cron:-}")
	void analyze() {
		log.info("스케줄 analyze: {}", jobService.trigger(JobName.ANALYZE, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.account-analyze-cron:-}")
	void accountAnalyze() {
		log.info("스케줄 account-analyze: {}",
				jobService.trigger(JobName.ACCOUNT_ANALYZE, TriggerType.SCHEDULED));
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.ScheduleRunnerTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java analytics/src/test/java/com/celfit/analytics/admin/ScheduleRunnerTest.java
git commit -m "feat(analytics): 스케줄 트리거 골격 — analytics.schedule.enabled 게이트, 기본 off"
```

---

### Task 9: 전체 검증 · 수동 E2E · 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§2 표·§5 태스크 I 상태)

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (crawler·was 포함 전 모듈 회귀 없음)

- [ ] **Step 2: 수동 E2E — 서버 기동·미러 트리거**

사전: `docker start crawler-postgres-1` (5433에 실데이터).

```bash
./gradlew :analytics:bootRun
```

브라우저(또는 curl)로 확인:
1. `GET http://localhost:8082/ui` → 200, 잡 버튼 4개·비용 카드 3장(실데이터 대상 건수) 렌더
2. 미러 실행: `curl -s -X POST http://localhost:8082/ui/jobs/mirror -o /dev/null -w '%{http_code}'` → 302
3. 3초 내 `GET http://localhost:8082/ui/fragments/logs`에 `mirror complete (7 targets, ...)` 노출
4. 실행 직후 연타: `POST /ui/jobs/mirror` 두 번 → 두 번째가 "이미 실행 중" 플래시(빠르면 통과 가능 — 로그로 중복 실행 없음만 확인)
5. LLM 잡은 **실행하지 않는다**(비용) — 버튼·카드 렌더만 확인

one-shot 회귀:
```bash
./gradlew :analytics:bootRun --args='--analytics.mirror-on-startup=true --spring.main.web-application-type=none'
```
→ 미러 후 프로세스 종료(기존 CLI 경로 보존) 확인.

- [ ] **Step 3: ARCHITECTURE.md 갱신**

- §2 표의 analytics 행: 기술 칸 `헤드리스 배치, JdbcTemplate ×2` →
  `상주 서버(8082, 어드민 /ui) + one-shot(cloud), JdbcTemplate ×2`
- §5 태스크 I 상태: `⬜` → `✅`
- §8 "미러 갱신 주기" 행: `현재 수동 1회. 자동화 여부·주기` →
  `어드민 UI 수동 트리거(8082 /ui). 스케줄 골격 있음(기본 off) — 크론 켜는 시점·주기만 미결`

- [ ] **Step 4: Commit**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 태스크 I 완료 반영 — analytics 상주 서버·어드민 UI·스케줄 골격"
```

- [ ] **Step 5: 마무리**

superpowers:finishing-a-development-branch 스킬로 PR 생성(develop 대상).
머지 후 이 plan을 `docs/superpowers/plans/archive/`로 이동.
