> 상태: ✅ 실행 완료 (2026-07-21, PR #98)

# 서빙 이미지 아카이브(태스크 J) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CDN 만료(~4일) 전에 프로필·릴스 썸네일·게시글 썸네일을 OCI `hypenow-images` 버킷에 적재하고, was가 `/img/` 상대경로로 서빙하게 한다.

**Architecture:** analytics에 데일리 아카이브 잡 신설(raw 뷰에서 URL 읽기 → HTTP 다운로드 → 쓰기 PAR로 OCI PUT → analysis DB `image_assets` 기록). was는 조회 SQL에 `image_assets` LEFT JOIN + `COALESCE('/img/'||object_path, 원본 URL)`만 추가(읽기 전용 유지). 프론트 Vercel rewrite(`/img/:path*` → OCI)는 이미 배포됨. 설계 근거: [specs/2026-07-21-image-archive-design.md](../../specs/2026-07-21-image-archive-design.md).

**Tech Stack:** Java 21 · Spring Boot 4.1 · `java.net.http.HttpClient`(SDK 무추가 — 업로드는 OCI 쓰기 PAR에 PUT) · Flyway(analysis DB) · Testcontainers 2.x(`org.testcontainers.postgresql.PostgreSQLContainer`)

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(analytics):`/`feat(was):`/`docs:` 식.
- 작업 브랜치 `feat/image-archive`(develop에서 분기), develop 대상 PR. develop·main 직접 push 금지.
- was는 분석 결과 **읽기만** — 이 계획에서 was는 SELECT 변경 외 어떤 쓰기·업로드도 하지 않는다.
- `image_assets`는 **MirrorConfig에 절대 등록하지 않는다**(미러 TRUNCATE 대상 아님 — content_analyses 전례).
- 객체 키 결정적: `thumb/{shortCode}.jpg` · `profile/{handle}.jpg`. Cache-Control: 썸네일 `public, max-age=31536000, immutable` / 프로필 `public, max-age=86400`.
- 테스트: `./gradlew :analytics:test` / `./gradlew :was:test` (Docker 필요 — Testcontainers).
- 버킷: 네임스페이스 `nr4nxrxoojw8`, 버킷 `hypenow-images`, 리전 `ap-tokyo-1`. 배포는 CD(develop→main 머지)로만 — deploy.sh 수동 실행 금지.

---

### Task 0: 브랜치 준비

- [ ] **Step 1: develop 기준 feat 브랜치 생성**

```bash
git fetch origin develop
git checkout -b feat/image-archive origin/develop
```

Expected: `Switched to a new branch 'feat/image-archive'`

---

### Task 1: ImageStore 포트 + ParImageStore 어댑터 (analytics)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/archive/ImageStore.java`
- Create: `analytics/src/main/java/com/celfit/analytics/archive/ParImageStore.java`
- Test: `analytics/src/test/java/com/celfit/analytics/archive/ParImageStoreTest.java`

**Interfaces:**
- Consumes: 없음 (독립)
- Produces: `interface ImageStore { void put(String objectPath, byte[] bytes, String contentType, String cacheControl); }` — Task 2의 잡이 사용. `new ParImageStore(String parBaseUrl)` — Task 3의 JobConfig가 생성. 빈 parBaseUrl이면 생성자에서 `IllegalStateException`.

- [ ] **Step 1: 실패하는 테스트 작성**

JDK 내장 `com.sun.net.httpserver.HttpServer`로 PUT 요청을 캡처한다.

```java
package com.celfit.analytics.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PAR PUT 계약: 경로 결합·Content-Type/Cache-Control 헤더 전달·비2xx 실패. */
class ParImageStoreTest {

	HttpServer server;
	Map<String, String> captured = new ConcurrentHashMap<>();
	int status = 200;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/p/tok/n/ns/b/bk/o/", exchange -> {
			captured.put("method", exchange.getRequestMethod());
			captured.put("path", exchange.getRequestURI().getPath());
			captured.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
			captured.put("cacheControl", exchange.getRequestHeaders().getFirst("Cache-Control"));
			captured.put("body", new String(exchange.getRequestBody().readAllBytes()));
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort() + "/p/tok/n/ns/b/bk/o/";
	}

	@Test
	void PUT_경로와_헤더를_전달한다() {
		new ParImageStore(baseUrl()).put("thumb/abc123.jpg", "img".getBytes(),
				"image/jpeg", "public, max-age=31536000, immutable");
		assertThat(captured.get("method")).isEqualTo("PUT");
		assertThat(captured.get("path")).isEqualTo("/p/tok/n/ns/b/bk/o/thumb/abc123.jpg");
		assertThat(captured.get("contentType")).isEqualTo("image/jpeg");
		assertThat(captured.get("cacheControl")).isEqualTo("public, max-age=31536000, immutable");
		assertThat(captured.get("body")).isEqualTo("img");
	}

	@Test
	void 비2xx면_예외() {
		status = 500;
		assertThatThrownBy(() -> new ParImageStore(baseUrl()).put("a.jpg", new byte[0], "image/jpeg", "no-cache"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("500");
	}

	@Test
	void PAR_URL_미설정이면_생성자에서_실패() {
		assertThatThrownBy(() -> new ParImageStore(""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("image-par-url");
	}

	@Test
	void 슬래시_없는_baseUrl도_정규화() {
		new ParImageStore(baseUrl().substring(0, baseUrl().length() - 1))
				.put("thumb/x.jpg", new byte[0], "image/jpeg", "no-cache");
		assertThat(captured.get("path")).isEqualTo("/p/tok/n/ns/b/bk/o/thumb/x.jpg");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests ParImageStoreTest`
Expected: 컴파일 실패 (`ImageStore`/`ParImageStore` 미존재)

- [ ] **Step 3: 구현**

`analytics/src/main/java/com/celfit/analytics/archive/ImageStore.java`:

```java
package com.celfit.analytics.archive;

/** 이미지 바이트를 오브젝트 스토리지에 넣는 포트 — 테스트는 fake로 대체. */
public interface ImageStore {

	void put(String objectPath, byte[] bytes, String contentType, String cacheControl);
}
```

`analytics/src/main/java/com/celfit/analytics/archive/ParImageStore.java`:

```java
package com.celfit.analytics.archive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OCI 쓰기 PAR(사전 인증 요청) 어댑터 — SDK 없이 HTTP PUT 하나로 업로드.
 * PAR URL은 `.../o/`로 끝나는 쓰기 전용(AnyObjectWrite) URL. Cache-Control은
 * PUT 헤더로 객체 메타데이터에 저장돼 공개 읽기·Vercel 엣지가 그대로 따른다.
 */
public class ParImageStore implements ImageStore {

	private final String parBaseUrl;
	private final HttpClient http;

	public ParImageStore(String parBaseUrl) {
		this(parBaseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	ParImageStore(String parBaseUrl, HttpClient http) {
		if (parBaseUrl == null || parBaseUrl.isBlank()) {
			throw new IllegalStateException("analytics.image-par-url 미설정 — 쓰기 PAR URL이 필요하다");
		}
		this.parBaseUrl = parBaseUrl.endsWith("/") ? parBaseUrl : parBaseUrl + "/";
		this.http = http;
	}

	@Override
	public void put(String objectPath, byte[] bytes, String contentType, String cacheControl) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(parBaseUrl + objectPath))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", contentType)
				.header("Cache-Control", cacheControl)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
				.build();
		try {
			HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
			if (res.statusCode() / 100 != 2) {
				throw new IllegalStateException("업로드 실패 HTTP " + res.statusCode() + ": " + objectPath);
			}
		} catch (IOException e) {
			throw new IllegalStateException("업로드 실패: " + objectPath, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("업로드 중단: " + objectPath, e);
		}
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests ParImageStoreTest`
Expected: 4 tests PASS

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/archive/ analytics/src/test/java/com/celfit/analytics/archive/
git commit -m "feat(analytics): 오브젝트 스토리지 쓰기 PAR 어댑터 (ImageStore 포트)"
```

---

### Task 2: image_assets DDL + ImageArchiveJob 코어 (analytics)

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V35__image_assets.sql`
- Create: `analytics/src/main/java/com/celfit/analytics/archive/ImageDownloader.java`
- Create: `analytics/src/main/java/com/celfit/analytics/archive/ImageArchiveJob.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java` (키 1개 추가)
- Test: `analytics/src/test/java/com/celfit/analytics/archive/ImageArchiveJobTest.java`

**Interfaces:**
- Consumes: Task 1의 `ImageStore`. 기존 `AnalyticsSettings`, `JobResult(int processed, int failed, boolean carriedOver)`(`com.celfit.analytics.analyze`), `ProgressReporter`(NOOP 상수 보유).
- Produces: `ImageArchiveJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource, ImageStore store, ImageDownloader downloader, AnalyticsSettings settings, ProgressReporter reporter)` + `JobResult run()`. `ImageDownloader.http()` 정적 팩토리. `AnalyticsSettings.archiveBatchLimit()`(기본 1000, 키 `analytics.archive-batch-limit`). Task 3이 이 시그니처로 배선.

- [ ] **Step 1: Flyway DDL 작성**

`analytics/src/main/resources/db/migration/analysis/V35__image_assets.sql`:

```sql
-- 서빙 이미지 아카이브 매핑 (태스크 J, specs/2026-07-21-image-archive-design.md).
-- 잡 소유 누적 테이블 — 미러(MirrorConfig) 대상 아님 (content_analyses 전례).
-- key: thumbnail=short_code / profile=handle. source_name: 원본 URL 파일명(호스트·서명 제외)
-- — 프로필 실제 교체 감지용(같으면 재다운로드 생략).
CREATE TABLE image_assets (
    kind        text NOT NULL CHECK (kind IN ('thumbnail', 'profile')),
    key         text NOT NULL,
    object_path text NOT NULL,
    source_name text NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, key)
);
```

- [ ] **Step 2: 실패하는 잡 테스트 작성**

기존 `ContentAnalysisJobTest` 패턴: Testcontainers PG 1개를 raw·analysis 겸용으로 쓰고, raw 뷰는 같은 이름의 테이블로 대체, analysis 쪽은 `TestDb.resetAndMigrate`로 V35까지 적용.

```java
package com.celfit.analytics.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 아카이브 잡 계약:
 * ① 신규 썸네일·프로필 업로드+기록 ② 기록된 썸네일은 다운로드 자체 생략(12개 윈도우 중복 무해)
 * ③ 프로필은 파일명 동일하면 생략·바뀌면 같은 키 덮어쓰기 ④ 배치 상한 초과분 이월(carriedOver)
 * ⑤ 한 건 실패 격리(계속 진행) ⑥ Cache-Control 종류별 차등.
 */
@Testcontainers
class ImageArchiveJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	List<String> downloads = new ArrayList<>();       // fetch된 URL 기록
	List<Map<String, String>> puts = new ArrayList<>(); // put(objectPath, cacheControl) 기록
	List<String> failUrls = new ArrayList<>();          // 다운로드를 실패시킬 URL

	ImageDownloader fakeDownloader() {
		return url -> {
			downloads.add(url);
			if (failUrls.contains(url)) throw new IllegalStateException("다운로드 실패 HTTP 403: " + url);
			return new ImageDownloader.Downloaded("bytes".getBytes(), "image/jpeg");
		};
	}

	ImageStore fakeStore() {
		return (objectPath, bytes, contentType, cacheControl) ->
				puts.add(Map.of("path", objectPath, "cacheControl", cacheControl));
	}

	ImageArchiveJob job(int batchLimit) {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.archive-batch-limit', ?) "
				+ "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", String.valueOf(batchLimit));
		return new ImageArchiveJob(db, ds, fakeStore(), fakeDownloader(),
				new AnalyticsSettings(db), ProgressReporter.NOOP);
	}

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		// raw 쪽 대체물 — 잡이 읽는 뷰와 같은 이름의 테이블 + app_setting
		db.execute("CREATE SCHEMA analytics");
		db.execute("CREATE TABLE analytics.v_contents (short_code text, thumbnail_url text)");
		db.execute("CREATE TABLE analytics.v_accounts (handle text, profile_image_url text)");
		db.execute("CREATE TABLE IF NOT EXISTS app_setting (key text PRIMARY KEY, value text)");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedContent(String shortCode, String url) {
		db.update("INSERT INTO analytics.v_contents VALUES (?, ?)", shortCode, url);
	}

	void seedAccount(String handle, String url) {
		db.update("INSERT INTO analytics.v_accounts VALUES (?, ?)", handle, url);
	}

	@Test
	void 신규_썸네일과_프로필을_업로드하고_기록한다() {
		seedContent("abc123", "https://cdn.example/v/t51/463_111_n.jpg?sig=1");
		seedAccount("celfit", "https://cdn.example/v/t51/999_222_n.jpg?sig=2");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.failed()).isZero();
		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("thumb/abc123.jpg", "profile/celfit.jpg");
		Integer rows = db.queryForObject("SELECT count(*) FROM image_assets", Integer.class);
		assertThat(rows).isEqualTo(2);
		String sourceName = db.queryForObject(
				"SELECT source_name FROM image_assets WHERE kind='profile' AND key='celfit'", String.class);
		assertThat(sourceName).isEqualTo("999_222_n.jpg");
	}

	@Test
	void 기록된_썸네일은_다운로드_자체를_생략한다() {
		seedContent("abc123", "https://cdn.example/v/463_111_n.jpg?sig=1");
		job(1000).run();
		downloads.clear();
		puts.clear();

		JobResult second = job(1000).run();

		assertThat(second.processed()).isZero();
		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 프로필_파일명_같으면_생략_바뀌면_같은_키_덮어쓰기() {
		seedAccount("celfit", "https://cdn-a.example/v/999_222_n.jpg?sig=1");
		job(1000).run();
		downloads.clear();
		puts.clear();

		// 호스트·서명만 바뀐 같은 파일명 → 생략
		db.update("UPDATE analytics.v_accounts SET profile_image_url = ?",
				"https://cdn-b.example/v/999_222_n.jpg?sig=99");
		assertThat(job(1000).run().processed()).isZero();
		assertThat(downloads).isEmpty();

		// 파일명 변경(실제 교체) → 같은 키 재업로드 + source_name 갱신
		db.update("UPDATE analytics.v_accounts SET profile_image_url = ?",
				"https://cdn-b.example/v/1000_333_n.jpg?sig=5");
		JobResult changed = job(1000).run();
		assertThat(changed.processed()).isEqualTo(1);
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("profile/celfit.jpg");
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE kind='profile'", Integer.class);
		assertThat(rows).isEqualTo(1);
		String sourceName = db.queryForObject(
				"SELECT source_name FROM image_assets WHERE kind='profile' AND key='celfit'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 배치_상한_초과분은_이월된다() {
		seedContent("c1", "https://cdn.example/1_n.jpg");
		seedContent("c2", "https://cdn.example/2_n.jpg");
		seedContent("c3", "https://cdn.example/3_n.jpg");

		JobResult result = job(2).run();

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.carriedOver()).isTrue();
	}

	@Test
	void 한_건_실패해도_나머지는_계속() {
		seedContent("bad", "https://cdn.example/expired_n.jpg");
		seedContent("good", "https://cdn.example/ok_n.jpg");
		failUrls.add("https://cdn.example/expired_n.jpg");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(1);
		// 실패분은 기록되지 않아 다음 실행에서 재대상
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE key='bad'", Integer.class);
		assertThat(rows).isZero();
	}

	@Test
	void CacheControl은_종류별_차등() {
		seedContent("abc123", "https://cdn.example/1_n.jpg");
		seedAccount("celfit", "https://cdn.example/2_n.jpg");

		job(1000).run();

		assertThat(puts).anySatisfy(m -> {
			assertThat(m.get("path")).startsWith("thumb/");
			assertThat(m.get("cacheControl")).isEqualTo("public, max-age=31536000, immutable");
		});
		assertThat(puts).anySatisfy(m -> {
			assertThat(m.get("path")).startsWith("profile/");
			assertThat(m.get("cacheControl")).isEqualTo("public, max-age=86400");
		});
	}
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :analytics:test --tests ImageArchiveJobTest`
Expected: 컴파일 실패 (`ImageArchiveJob`/`ImageDownloader` 미존재)

- [ ] **Step 4: 구현**

`analytics/src/main/java/com/celfit/analytics/archive/ImageDownloader.java`:

```java
package com.celfit.analytics.archive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** CDN 이미지 다운로드 포트 — 테스트는 fake, 기본 구현은 http(). */
public interface ImageDownloader {

	Downloaded fetch(String url) throws Exception;

	record Downloaded(byte[] bytes, String contentType) {
	}

	/** 기본 구현 — 인스타 CDN GET (AnthropicContentAttributeAnalyzer.download 관용구). */
	static ImageDownloader http() {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		return url -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() / 100 != 2) {
				throw new IllegalStateException("다운로드 실패 HTTP " + res.statusCode() + ": " + url);
			}
			// 인스타 CDN은 jpeg/webp 혼재 — 미상은 jpeg로 간주 (기존 분석기 관용구)
			return new Downloaded(res.body(),
					res.headers().firstValue("Content-Type").orElse("image/jpeg"));
		};
	}
}
```

`analytics/src/main/java/com/celfit/analytics/archive/ImageArchiveJob.java`:

```java
package com.celfit.analytics.archive;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 서빙 이미지 아카이브 잡 (태스크 J) — CDN 만료(~4일) 전에 썸네일·프로필을 오브젝트 스토리지로.
 * 대상 선정: 썸네일=image_assets 미기록 shortCode만(1회 불변 — 중복 수집분 다운로드 생략),
 * 프로필=원본 URL 파일명(source_name)이 바뀐 계정만(같은 키 덮어쓰기 — 축적 없음).
 * 실패는 건 단위 격리(미기록 → 다음 실행 재대상), 배치 상한 초과분은 이월(carriedOver).
 */
public class ImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(ImageArchiveJob.class);

	static final String KIND_THUMBNAIL = "thumbnail";
	static final String KIND_PROFILE = "profile";
	static final String THUMB_CACHE_CONTROL = "public, max-age=31536000, immutable";
	static final String PROFILE_CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;

	public ImageArchiveJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ImageStore store, ImageDownloader downloader, AnalyticsSettings settings,
			ProgressReporter reporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.store = store;
		this.downloader = downloader;
		this.settings = settings;
		this.reporter = reporter;
	}

	public JobResult run() {
		List<Target> targets = new ArrayList<>(profileTargets());
		targets.addAll(thumbnailTargets());
		int limit = settings.archiveBatchLimit();
		boolean carriedOver = targets.size() > limit;
		List<Target> batch = targets.subList(0, Math.min(limit, targets.size()));

		int done = 0;
		int failed = 0;
		reporter.report(0, 0, batch.size());
		for (Target t : batch) {
			try {
				ImageDownloader.Downloaded img = downloader.fetch(t.url());
				store.put(t.objectPath(), img.bytes(), img.contentType(),
						KIND_PROFILE.equals(t.kind()) ? PROFILE_CACHE_CONTROL : THUMB_CACHE_CONTROL);
				analysis.update("""
						INSERT INTO image_assets (kind, key, object_path, source_name)
						VALUES (?, ?, ?, ?)
						ON CONFLICT (kind, key) DO UPDATE
						  SET object_path = EXCLUDED.object_path,
						      source_name = EXCLUDED.source_name,
						      archived_at = now()
						""", t.kind(), t.key(), t.objectPath(), sourceName(t.url()));
				done++;
			} catch (Exception e) {
				failed++;
				log.warn("이미지 아카이브 실패: {} {}", t.kind(), t.key(), e);
			}
			reporter.report(done, failed, batch.size());
		}
		log.info("이미지 아카이브 완료 — {}건 저장, {}건 실패{}", done, failed,
				carriedOver ? ", 잔여 " + (targets.size() - batch.size()) + "건 이월" : "");
		return new JobResult(done, failed, carriedOver);
	}

	record Target(String kind, String key, String url) {

		String objectPath() {
			return (KIND_PROFILE.equals(kind) ? "profile/" : "thumb/") + key + ".jpg";
		}
	}

	private List<Target> thumbnailTargets() {
		Set<String> archived = new HashSet<>(analysis.queryForList(
				"SELECT key FROM image_assets WHERE kind = 'thumbnail'", String.class));
		return raw.query("""
				SELECT short_code, thumbnail_url FROM analytics.v_contents
				WHERE thumbnail_url IS NOT NULL
				""", (rs, i) -> new Target(KIND_THUMBNAIL, rs.getString(1), rs.getString(2)))
				.stream().filter(t -> !archived.contains(t.key())).toList();
	}

	private List<Target> profileTargets() {
		Map<String, String> archived = new HashMap<>();
		analysis.query("SELECT key, source_name FROM image_assets WHERE kind = 'profile'",
				rs -> {
					archived.put(rs.getString(1), rs.getString(2));
				});
		return raw.query("""
				SELECT handle, profile_image_url FROM analytics.v_accounts
				WHERE profile_image_url IS NOT NULL
				""", (rs, i) -> new Target(KIND_PROFILE, rs.getString(1), rs.getString(2)))
				.stream().filter(t -> !sourceName(t.url()).equals(archived.get(t.key()))).toList();
	}

	/** URL 경로의 마지막 세그먼트(인스타 미디어 ID 파일명) — 호스트·서명 쿼리는 크롤마다 바뀌므로 제외. */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}
}
```

`AnalyticsSettings.java`에 추가 (기존 키 상수 블록·기본값 블록·메서드 블록에 각각):

```java
	/** 1회 실행당 이미지 아카이브(다운로드+업로드) 상한 — 초과분은 이월. */
	public static final String KEY_ARCHIVE_BATCH_LIMIT = "analytics.archive-batch-limit";
```

```java
	static final int DEFAULT_ARCHIVE_BATCH_LIMIT = 1000;
```

```java
	public int archiveBatchLimit() {
		return read(KEY_ARCHIVE_BATCH_LIMIT).map(Integer::parseInt)
				.orElse(DEFAULT_ARCHIVE_BATCH_LIMIT);
	}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :analytics:test --tests ImageArchiveJobTest`
Expected: 6 tests PASS

- [ ] **Step 6: 커밋**

```bash
git add analytics/src/main/resources/db/migration/analysis/V35__image_assets.sql \
        analytics/src/main/java/com/celfit/analytics/archive/ \
        analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java \
        analytics/src/test/java/com/celfit/analytics/archive/
git commit -m "feat(analytics): 이미지 아카이브 잡 — image_assets(V35)·썸네일 1회·프로필 파일명 변경 감지"
```

---

### Task 3: 잡 배선 — JobConfig·one-shot·어드민·스케줄·compose (analytics)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (빈 추가)
- Create: `analytics/src/main/java/com/celfit/analytics/archive/ArchiveRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/JobName.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/ScheduleInfo.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java:32-33` (DASHBOARD_JOBS)
- Modify: `deploy/compose.yaml` (analytics env)
- Test: 기존 `ScheduleInfoTest`·`AnalyticsJobServiceTest` 생성자 호출부 갱신 + ARCHIVE 케이스 추가

**Interfaces:**
- Consumes: Task 1 `ParImageStore(parUrl)`, Task 2 `ImageArchiveJob(...)`·`ImageDownloader.http()`.
- Produces: `JobName.ARCHIVE`(slug `archive`), 프로퍼티 `analytics.archive-on-startup`·`analytics.image-par-url`·`analytics.schedule.archive-cron`. `AnalyticsJobService` 생성자에 `ObjectProvider<ImageArchiveJob> archiveJob` 파라미터가 **accountAnalyzeJob 다음, progress 앞**에 추가됨. `ScheduleInfo` 생성자에 `String archiveCron`이 마지막 파라미터로 추가됨.

- [ ] **Step 1: 실패하는 테스트 — 기존 테스트에 ARCHIVE 반영**

`ScheduleInfoTest.java`의 생성자 호출 2곳(13행·27행)을 5-크론으로 갱신:

```java
		ScheduleInfo info = new ScheduleInfo(true, "0 30 19 * * *", "-", "-", "-", "-");
```

```java
		ScheduleInfo info = new ScheduleInfo(false, "0 30 19 * * *", "-", "-", "-", "-");
```

`AnalyticsJobServiceTest.java`의 `new AnalyticsJobService(...)` 2곳(38행·106행)에 `accountAnalyzeJob` 인자 **다음**에 아카이브 provider 인자를 추가한다. 기존 provider 인자들이 어떤 형태(`ObjectProvider` fake/null)로 넘겨지는지 그 파일의 기존 스타일을 그대로 따라 같은 형태로 하나 더 넘긴다. 그리고 ARCHIVE 트리거 케이스를 추가:

```java
	@Test
	void archive_잡을_트리거하면_run이_호출된다() {
		// 파일 상단의 기존 fake/provider 헬퍼 스타일을 재사용해 ImageArchiveJob 목을 주입.
		// run()이 JobResult(3, 1, false)를 반환하도록 하고:
		// - trigger(JobName.ARCHIVE, TriggerType.MANUAL) == ACCEPTED
		// - history에 processed=3, failed=1로 기록됨을 단언한다 (기존 테스트 단언 스타일).
	}
```

(위 케이스 본문은 파일의 기존 mirror/analyze 케이스를 그대로 본떠 작성 — 같은 파일 안에 완전한 선례가 있다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests ScheduleInfoTest --tests AnalyticsJobServiceTest`
Expected: 컴파일 실패 (생성자 시그니처 불일치·`JobName.ARCHIVE` 미존재)

- [ ] **Step 3: 구현**

`JobName.java` — enum에 추가:

```java
	ARCHIVE("이미지 아카이브 — CDN→오브젝트 스토리지");
```

(shortLabel은 " — " 앞에서 자르는 기존 규칙이라 추가 코드 불필요.)

`AnalyticsJobService.java` — import·필드·생성자 파라미터(accountAnalyzeJob 다음)·switch 케이스 추가:

```java
import com.celfit.analytics.archive.ImageArchiveJob;
```

```java
	private final ObjectProvider<ImageArchiveJob> archiveJob;
```

생성자 파라미터 목록의 `ObjectProvider<AccountAnalysisJob> accountAnalyzeJob,` 뒤에:

```java
			ObjectProvider<ImageArchiveJob> archiveJob,
```

(본문에 `this.archiveJob = archiveJob;` 추가.) `run(JobName)` switch에:

```java
			case ARCHIVE -> archiveJob.getObject().run();
```

`ScheduleRunner.java` — 메서드 추가:

```java
	@Scheduled(cron = "${analytics.schedule.archive-cron:-}")
	void archive() {
		log.info("스케줄 archive: {}", jobService.trigger(JobName.ARCHIVE, TriggerType.SCHEDULED));
	}
```

`ScheduleInfo.java` — 생성자 마지막 파라미터·put 추가:

```java
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron,
			@Value("${analytics.schedule.archive-cron:-}") String archiveCron) {
```

```java
		put(JobName.ARCHIVE, archiveCron);
```

`AdminConfig.java` — ① `analyticsJobService` 빈: `ObjectProvider<ImageArchiveJob> archiveJob` 파라미터 추가 후 생성자 인자 순서대로 전달. ② `scheduleInfo` 빈:

```java
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron,
			@Value("${analytics.schedule.archive-cron:-}") String archiveCron) {
		return new ScheduleInfo(enabled, mirrorCron, classifyCron, analyzeCron, accountCron, archiveCron);
```

`AdminUiController.java:32-33`:

```java
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.ANALYZE, JobName.ACCOUNT_ANALYZE, JobName.ARCHIVE);
```

`JobConfig.java` — 빈 추가 (기존 contentAnalysisJob 패턴):

```java
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.archive-on-startup:false} or ${analytics.admin-enabled:false}")
	public com.celfit.analytics.archive.ImageArchiveJob imageArchiveJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings,
			@Value("${analytics.image-par-url:}") String imageParUrl,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.ARCHIVE) : ProgressReporter.NOOP;
		// @Lazy — PAR 미설정이면 첫 트리거 때 이 잡만 실패(로그 패널 노출), 서버 기동은 영향 없음
		return new com.celfit.analytics.archive.ImageArchiveJob(rawJdbcTemplate, analysisDataSource,
				new com.celfit.analytics.archive.ParImageStore(imageParUrl),
				com.celfit.analytics.archive.ImageDownloader.http(), settings, reporter);
	}
```

(파일 상단 import 스타일에 맞춰 FQCN 대신 import를 써도 된다 — 기존 파일 관례 따름.)

`analytics/src/main/java/com/celfit/analytics/archive/ArchiveRunner.java` (AnalyzeRunner 동형):

```java
package com.celfit.analytics.archive;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이미지 아카이브 기동 트리거 — analytics.archive-on-startup=true일 때만. 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.archive-on-startup", havingValue = "true")
public class ArchiveRunner {

	@Bean
	public CommandLineRunner archiveOnStartup(ImageArchiveJob job) {
		return args -> job.run();
	}
}
```

`deploy/compose.yaml` — analytics 서비스 environment에 추가 (스케줄 주석도 갱신):

```yaml
      # 스케줄(KST=UTC+9): 미러 04:30 → 아카이브 04:50 → 분석 05:00 → 계정 카피 07:00 (백업 04:10 이후)
      ANALYTICS_SCHEDULE_ARCHIVE_CRON: "0 50 19 * * *"
      ANALYTICS_IMAGE_PAR_URL: ${ANALYTICS_IMAGE_PAR_URL}
```

(기존 `# 스케줄(KST=UTC+9): 미러 04:30 → 분석 05:00 → 계정 카피 07:00` 주석 줄을 위 주석으로 교체하고, 두 env는 기존 `ANALYTICS_SCHEDULE_*` 블록에 나란히 둔다.)

- [ ] **Step 4: 통과 확인 + 전체 회귀**

Run: `./gradlew :analytics:test`
Expected: 전체 PASS (AdminUiControllerTest 등이 카드 수를 단언하면 4개 기준으로 갱신)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/ deploy/compose.yaml analytics/src/test/java/com/celfit/analytics/admin/
git commit -m "feat(analytics): 아카이브 잡 배선 — 어드민 카드·스케줄(04:50 KST)·one-shot·PAR 설정"
```

---

### Task 4: was /v1 서빙 치환 — COALESCE + image_assets 조인

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/ContentCardRow.java:36-46` (SELECT 상수 + IMAGE_JOINS 상수 신설)
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentRepository.java:49-71` (buildWhere FROM 2곳)
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerRepository.java` (findProfile·findRecentCards)
- Modify: `was/src/main/java/com/celfit/was/v1/saved/V1SavedRepository.java` (카드 2곳·프로필 2곳)
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentRepositoryTest.java` 등 영향 픽스처 전부

**Interfaces:**
- Consumes: `image_assets(kind, key, object_path, ...)` (Task 2 DDL — was 테스트는 사본 DDL 사용).
- Produces: 카드 FROM 절에 붙일 `ContentCardRow.IMAGE_JOINS` 상수. 응답 필드명·record 계약은 **무변경**(같은 별칭 `thumbnail_url`·`profile_image_url`로 SELECT).

- [ ] **Step 1: 실패하는 테스트 작성**

`V1ContentRepositoryTest.setUpTables()`에 image_assets 사본 DDL 추가:

```java
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
```

(DROP 블록에 추가 — 순서는 content_analyses 앞이면 어디든 무방)

```java
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text NOT NULL,
				    source_name text NOT NULL,
				    archived_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (kind, key)
				)""");
```

테스트 케이스 추가 (기존 시드 헬퍼 스타일 재사용 — 계정·콘텐츠·분석 행을 넣는 기존 메서드를 그대로 사용):

```java
	@Test
	void 아카이브된_이미지는_img_경로로_미아카이브는_원본_URL로_서빙() {
		// 기존 픽스처 헬퍼로 계정 handle=h1(profile_image_url='https://cdn/p.jpg'),
		// 콘텐츠 c1·c2(thumbnail_url='https://cdn/t1.jpg'/'https://cdn/t2.jpg') + 분석 행 시드.
		jdbcTemplate.update("INSERT INTO image_assets(kind, key, object_path, source_name) "
				+ "VALUES ('thumbnail', 'c1', 'thumb/c1.jpg', 't1.jpg')");
		jdbcTemplate.update("INSERT INTO image_assets(kind, key, object_path, source_name) "
				+ "VALUES ('profile', 'h1', 'profile/h1.jpg', 'p.jpg')");

		List<ContentCardRow> rows = repository.findCards(/* 기존 테스트의 기본 쿼리 객체 */);

		ContentCardRow archived = rows.stream().filter(r -> r.shortCode().equals("c1")).findFirst().orElseThrow();
		assertThat(archived.thumbnailUrl()).isEqualTo("/img/thumb/c1.jpg");
		assertThat(archived.profileImageUrl()).isEqualTo("/img/profile/h1.jpg");
		ContentCardRow fallback = rows.stream().filter(r -> r.shortCode().equals("c2")).findFirst().orElseThrow();
		assertThat(fallback.thumbnailUrl()).isEqualTo("https://cdn/t2.jpg");
	}
```

(시드 헬퍼·쿼리 객체 생성은 그 파일의 기존 테스트 메서드에 완전한 선례가 있다 — 그대로 본뜬다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests V1ContentRepositoryTest`
Expected: 신규 케이스 FAIL (`/img/...`가 아닌 원본 URL 반환)

- [ ] **Step 3: 구현**

`ContentCardRow.java` — SELECT 상수 교체 + 조인 상수 신설:

```java
	/** 카드 SELECT 절 공통 상수 — 목록(6.1)·recentContents(6.4) 리포지토리가 같이 쓴다.
	 *  아카이브된 이미지는 /img/ 상대경로(Vercel rewrite→오브젝트 스토리지), 미아카이브는 원본 CDN 폴백. */
	public static final String SELECT = """
			SELECT c.short_code,
			       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
			       c.caption, c.posted_at, c.content_type,
			       c.video_duration, c.original_url, c.views, c.likes, c.comments,
			       c.hype_score, c.metric_captured_at,
			       an.main_category, an.sub_categories::text AS sub_categories_json, an.ad_type,
			       an.detected_brands::text AS brands_json,
			       an.detected_products::text AS products_json,
			       an.detected_distributors::text AS distributors_json,
			       a.handle, a.display_name,
			       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
			       a.followers
			""";

	/** SELECT의 it·ip 별칭 공급 — 카드 FROM 절에 반드시 함께 붙인다. */
	public static final String IMAGE_JOINS = """
			LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
			LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
			""";
```

`V1ContentRepository.buildWhere` — 두 FROM 변형 모두, 마지막 JOIN 뒤·WHERE 앞에 조인 삽입. 첫 변형(49-57행):

```java
		StringBuilder sb = new StringBuilder("""

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE c.posted_at >= :start AND c.posted_at < :end
				  AND c.content_type = :contentType
				  AND an.is_beauty = true
				""");
```

둘째 변형(63행 시작, LATERAL 스냅샷 조인 포함)은 `) s ON true` 줄 **다음**에 같은 두 LEFT JOIN을 삽입한다. (`SELECT count(*)` + fromWhere 재사용 경로는 LEFT JOIN이 1:1 이하라 카운트 불변.)

`V1InfluencerRepository.findRecentCards` — `JOIN accounts a ON a.handle = c.account_handle` 다음 줄에 `ContentCardRow.IMAGE_JOINS`를 문자열 결합으로 삽입하거나 같은 두 LEFT JOIN 줄을 텍스트로 추가 (파일 내 다른 SQL이 text block이면 텍스트 추가가 자연스럽다).

`V1InfluencerRepository.findProfile` — 교체:

```java
		return jdbcClient.sql("""
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers, a.external_link,
				       s.posts_count, s.follows_count, s.biography
				FROM accounts a
				LEFT JOIN account_summaries s ON s.handle = a.handle
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE a.handle = :h
				""").param("h", handle).query(ProfileRow.class).optional();
```

`V1SavedRepository` — 카드 2곳(152·166행): `JOIN accounts a ON a.handle = c.account_handle` 다음에 IMAGE_JOINS 두 줄 삽입. 프로필 2곳(findInfluencer·findInfluencers, 179·193행 부근): 별칭 추가 형태로 교체:

```java
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers
				FROM accounts a
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE a.handle = :h
```

(IN (:handles) 변형도 동일하게 `WHERE a.handle IN (:handles)`.)

- [ ] **Step 4: 통과 + was 전체 회귀 (픽스처 정비)**

Run: `./gradlew :was:test`
Expected: `relation "image_assets" does not exist`로 깨지는 테스트가 나온다 — **accounts/contents DDL을 만드는 모든 was 테스트 픽스처**(V1InfluencerRepositoryTest, V1SavedRepositoryTest, 관련 컨트롤러 통합 테스트 등 실패 목록 전부)에 Step 1의 image_assets 사본 DDL 블록을 추가한다. 전체 PASS까지 반복.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/ was/src/test/java/com/celfit/was/
git commit -m "feat(was): /v1 이미지 서빙을 image_assets COALESCE로 — /img/ 상대경로, 미아카이브 CDN 폴백"
```

---

### Task 5: was 레거시 /api 서빙 치환

**Files:**
- Modify: `was/src/main/java/com/celfit/was/contentlist/ContentListRepository.java` (SELECT + buildWhere FROM)
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailRepository.java` (findContent·findAccount)
- Modify: `was/src/main/java/com/celfit/was/influencer/InfluencerDetailRepository.java` (findAccount)
- Test: `ContentListRepositoryTest`·`PostDetailRepositoryTest`·`InfluencerDetailRepositoryTest` (같은 패턴)

**Interfaces:**
- Consumes: Task 4와 동일한 `image_assets` 사본 DDL·COALESCE 패턴.
- Produces: 없음 (레거시 응답 계약 무변경).

- [ ] **Step 1: 실패하는 테스트** — Task 4 Step 1과 동일 패턴: 각 테스트 픽스처에 image_assets DDL 추가 + "아카이브면 `/img/...`, 아니면 원본" 케이스를 각 리포지토리 테스트에 1개씩 추가 (시드는 각 파일의 기존 헬퍼 재사용).

- [ ] **Step 2: 실패 확인** — `./gradlew :was:test --tests ContentListRepositoryTest --tests PostDetailRepositoryTest --tests InfluencerDetailRepositoryTest` → 신규 케이스 FAIL

- [ ] **Step 3: 구현**

`ContentListRepository.findContents` SELECT 교체(첫 두 이미지 컬럼만 변경):

```java
					SELECT c.short_code,
					       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
					       c.caption, c.posted_at, c.content_type,
					       a.handle, a.display_name,
					       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
					       a.followers,
					       s.views, s.likes, s.comments, s.hype_score, s.captured_at,
					       an.ad_type,
					       an.detected_product_categories::text AS product_categories_json,
					       jsonb_array_length(an.detected_brands) AS brand_count
```

`ContentListRepository.buildWhere`의 FROM(contents c … accounts a 조인부) 마지막 JOIN 뒤에 Task 4와 같은 두 LEFT JOIN 삽입.

`PostDetailRepository.findContent` 교체:

```java
				SELECT c.short_code, c.account_handle,
				       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
				       c.caption, c.posted_at,
				       c.content_type, c.video_duration, c.original_url,
				       c.views, c.likes, c.comments, c.hype_score, c.metric_captured_at
				FROM contents c
				LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
				WHERE c.short_code = :shortCode
```

`PostDetailRepository.findAccount`·`InfluencerDetailRepository.findAccount` 교체(동일 형태):

```java
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers, a.external_link
				FROM accounts a
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE a.handle = :handle
```

- [ ] **Step 4: 통과 + 전 모듈 회귀**

Run: `./gradlew test`
Expected: 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/ was/src/test/java/com/celfit/was/
git commit -m "feat(was): 레거시 /api 이미지 서빙도 image_assets COALESCE 적용"
```

---

### Task 6: 운영 개통 — 버킷 공개·쓰기 PAR·서버 env (수동 ops, 사용자 확인 후)

> ⚠️ 클라우드 리소스 변경 — 실행 전 사용자에게 명령을 보여주고 확인받는다. 배포 자체는 CD(develop→main 머지)로만.

- [ ] **Step 1: 버킷 읽기 공개(목록 차단)**

```bash
oci --profile HYPENOW os bucket update --namespace-name nr4nxrxoojw8 \
    --bucket-name hypenow-images --public-access-type ObjectReadWithoutList
```

Expected: JSON에 `"public-access-type": "ObjectReadWithoutList"`

- [ ] **Step 2: 쓰기 전용 PAR 생성**

```bash
oci --profile HYPENOW os preauth-request create --namespace-name nr4nxrxoojw8 \
    --bucket-name hypenow-images --name image-archive-writer \
    --access-type AnyObjectWrite --time-expires "2030-01-01T00:00:00+00:00"
```

반환 `access-uri`로 PAR URL 조립: `https://objectstorage.ap-tokyo-1.oraclecloud.com<access-uri>` (…`/o/`로 끝남). **PAR URL은 시크릿** — 채팅·커밋에 남기지 않는다.

- [ ] **Step 3: 업로드·공개 읽기·캐시 헤더 검증**

```bash
printf 'test' > /tmp/t.txt
curl -sf -X PUT -H "Content-Type: text/plain" -H "Cache-Control: public, max-age=60" \
     --data-binary @/tmp/t.txt "<PAR URL>test/hello.txt"
curl -sI "https://objectstorage.ap-tokyo-1.oraclecloud.com/n/nr4nxrxoojw8/b/hypenow-images/o/test/hello.txt" \
     | grep -iE 'HTTP|cache-control|content-type'
```

Expected: `200` + `Cache-Control: public, max-age=60` + `Content-Type: text/plain`. (Cache-Control이 안 붙어 나오면 PAR가 헤더를 통과시키지 않는 것 — 이 경우 사용자와 상의해 Vercel rewrite에 headers 설정을 추가하는 대안으로 전환.) 프론트 경유도 확인: `curl -sI https://<프론트 도메인>/img/test/hello.txt`. 검증 후 정리:

```bash
oci --profile HYPENOW os object delete --namespace-name nr4nxrxoojw8 \
    --bucket-name hypenow-images --object-name test/hello.txt --force
```

- [ ] **Step 4: 서버 .env에 PAR 등록**

서버 SSH로 compose `.env`에 `ANALYTICS_IMAGE_PAR_URL=<PAR URL>` 추가. (CD 배포 시 compose가 읽는다 — compose.yaml 변경분은 이 브랜치에 이미 포함.)

- [ ] **Step 5: 배포 후 첫 실행 확인**

develop→main 머지(CD 배포) 후: 어드민 `/ui`(SSH 터널 8082)에서 ARCHIVE 잡 카드 확인·수동 트리거 → 로그에 "이미지 아카이브 완료 — N건 저장" 확인 → `image_assets` 행 수와 실제 프론트 카드 이미지가 `/img/...`로 뜨는지 확인.

---

### Task 7: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` §5 태스크 J 상태(📋 설계 확정 → ✅) + §7 구현 결정 한 줄 추가
- Modify: `docs/superpowers/specs/2026-07-21-image-archive-design.md` 상태 헤더 → `🟢 활성 · ✅ 구현됨`
- Move: 본 계획 → `docs/superpowers/plans/archive/2026-07-21-image-archive.md` (상태 헤더 `✅ 실행 완료`)

- [ ] **Step 1: 문서 3종 갱신** — §7 추가 줄은 구현 요약(V35·잡 04:50 KST·was COALESCE 적용 범위 /v1+/api·운영 개통 여부)과 PR 링크 포함.

- [ ] **Step 2: 커밋 + PR**

```bash
git add ARCHITECTURE.md docs/
git commit -m "docs: 태스크 J 서빙 이미지 아카이브 구현 반영"
git push -u origin feat/image-archive
```

develop 대상 PR 생성 (superpowers:finishing-a-development-branch 절차).

---

## Self-Review 결과 (작성 시 수행)

- 스펙 커버리지: 결정 ①(Task 2·3) ②(Task 4·5) ③(Task 2) ④(Task 6) + 데일리 사이클(compose cron) 모두 태스크에 대응. 스코프 제외(만료 과거분·VLM 하베스트)는 코드 불요 확인.
- 타입 일관성: `ImageStore.put(4-args)`·`ImageArchiveJob` 생성자·`JobResult`·`ScheduleInfo(6-args)` 태스크 간 시그니처 일치 확인.
- 미리 알 수 없는 부분(기존 테스트 픽스처 내부·AnalyticsJobServiceTest의 fake 스타일)은 "같은 파일의 선례를 본뜬다"로 한정 — 파일 밖 참조 없음.
