# Gemini 3.1 Flash-Lite LLM 스택 구현 계획

> 상태: ✅ 구현/실행/반영됨 (2026-07-18 — Task 1~9 완료. 백필 런타임 실행만 신 스키마 뷰 머지 + GEMINI_API_KEY_PAID 등록 후)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** analytics·crawler의 LLM 분석 전 축(①뷰티 판정 / ②속성+③종합 통합 1콜 / ④계정 카피)을 Gemini 3.1 Flash-Lite로 전환하고, 초기 백필 2만 건을 Batch API로 처리할 one-shot 러너를 만든다.

**Architecture:** 기존 포트/어댑터 구조 유지 — Gemini 어댑터를 같은 포트 뒤에 추가하고 `app_setting` 키(`analytics.llm-provider`)로 선택한다. ②+③은 신설 통합 포트(`ContentInsightPort`) 1콜로 합치고 Anthropic 경로는 기존 어댑터 2종을 감싼 컴포지트로 보존한다. 호출은 검증된 REST 형태(골드셋 스파이크 `run_gemini.py`) 그대로 — JDK HttpClient + Jackson, `responseSchema` 구조화 출력, 429 백오프 + RPM 페이싱, 일 한도 소진 시 예외로 배치 중단(이월). 크롤러 판정은 팀 프롬프트·파서(`ClaudeCliBeautyJudge.buildPrompt/parse`)를 재사용하는 전송층만 추가.

**Tech Stack:** Java 21 · Spring Boot 4.1 · JDK `java.net.http.HttpClient` · Jackson 3(`tools.jackson`) · Gemini API v1beta(REST, google-genai SDK 미사용 — 스파이크로 검증된 REST 형태 유지) · Testcontainers + `com.sun.net.httpserver` 스텁

**확정 결정 (재논의 금지):** 모델 `gemini-3.1-flash-lite` 전 축 통일 / ②③ 통합 1콜 / 문구 프롬프트에 절제 규칙(GUARD) 필수 / 일상 무료 키(`GEMINI_API_KEY`) 동기+페이싱, 백필은 유료 키(`GEMINI_API_KEY_PAID`) Batch API 일회 / 429·한도 소진은 에러 아닌 이월.

**선행 의존:** 신 스키마 뷰 재구축(`feat/analytics-views-new-schema`, 별도 세션 진행 중 — 07-18 기준 00·01 뷰 완료, `04_analysis_candidates`·`03_analysis_baseline` 미작성). 이 계획의 Task 1~7은 뷰와 무관(포트 계층). Task 8(백필)은 그쪽 계획 문서에 확정된 `v_analysis_candidates` 컬럼 계약(short_code, content_type, account_handle, uploaded_at, caption, thumbnail_url, followers, views, likes, comments, metric_captured_at)에 맞춰 코딩하고, **런타임 실행은 그 브랜치 머지 + 뷰 적용 후**.

**실행 전 운영 준비물(코드 밖):**
- `GEMINI_API_KEY` — ~/.zshenv에 있음(무료 프로젝트). 커밋 금지, `.env`는 JVM에 자동 로드 안 됨.
- `GEMINI_API_KEY_PAID` — **아직 없음.** 같은 계정의 유료(결제 연결) 프로젝트 키를 만들어 ~/.zshenv에 export해야 Task 8 실행 가능.
- 일 예산 반영 SQL(raw DB `app_setting`, 배포 아님·운영자 실행):
  ```sql
  INSERT INTO app_setting(key, value) VALUES
    ('analytics.llm-provider', 'gemini'),
    ('analytics.analyze-batch-limit', '450'),
    ('analytics.account-analyze-batch-limit', '150')
  ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
  ```
  (판정 예산 ~100콜/일은 crawler `crawler.beauty.batch-limit`(500계정 = 10콜)로 이미 하회 — 변경 불요)

---

### Task 1: 브랜치 준비

**Files:** 없음 (git만)

- [ ] **Step 1: develop 기준 feat 브랜치 생성**

```bash
git checkout -b feat/gemini-llm-stack
git log --oneline -1   # 28d442e (develop HEAD) 확인
```

---

### Task 2: Gemini 공통 클라이언트 (`GeminiApi` / `GeminiHttpApi` / 쿼터 예외)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/LlmQuotaExhaustedException.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/GeminiApi.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/GeminiHttpApi.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiHttpApiTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — 로컬 `HttpServer` 스텁으로 요청 본문 구조·응답 파싱·429 재시도·429 소진 시 쿼터 예외를 고정.

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Gemini REST 호출 계약: 본문 구조(responseSchema 포함)·응답 텍스트 추출·429 백오프·소진 시 쿼터 예외. */
class GeminiHttpApiTest {

	static final String OK_RESPONSE = """
			{"candidates":[{"content":{"parts":[{"text":"{\\"a\\":1}"}]}}],
			 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}""";

	HttpServer server;
	List<String> bodies;
	AtomicInteger status429Count;

	@BeforeEach
	void setUp() throws Exception {
		bodies = new CopyOnWriteArrayList<>();
		status429Count = new AtomicInteger(0);
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			bodies.add(buf.toString(StandardCharsets.UTF_8));
			int remaining = status429Count.getAndDecrement();
			byte[] out = (remaining > 0 ? "{}" : OK_RESPONSE).getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(remaining > 0 ? 429 : 200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	GeminiHttpApi api() {
		// rpm 매우 크게(페이싱 대기 0)·재시도 기본 대기 1ms — 테스트 속도용
		return new GeminiHttpApi("test-key", "http://localhost:" + server.getAddress().getPort(),
				600_000, 1);
	}

	@Test
	void 요청_본문에_시스템_스키마_생성설정이_실린다() {
		api().generateJson("gemini-3.1-flash-lite", "시스템 지침", "유저 입력", null,
				"{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}", 4096);
		JsonNode body = new ObjectMapper().readTree(bodies.get(0));
		assertEquals("시스템 지침", body.path("systemInstruction").path("parts").get(0).path("text").asString());
		assertEquals("유저 입력", body.path("contents").get(0).path("parts").get(0).path("text").asString());
		JsonNode gen = body.path("generationConfig");
		assertEquals("application/json", gen.path("responseMimeType").asString());
		assertEquals("object", gen.path("responseSchema").path("type").asString());
		assertEquals(4096, gen.path("maxOutputTokens").asInt());
		assertEquals(0, gen.path("temperature").asInt());
	}

	@Test
	void 이미지가_있으면_inlineData_파트가_텍스트_앞에_실린다() {
		api().generateJson("m", "sys", "user", new GeminiApi.InlineImage("image/jpeg", new byte[] {1, 2}),
				"{\"type\":\"object\"}", 1024);
		JsonNode parts = new ObjectMapper().readTree(bodies.get(0)).path("contents").get(0).path("parts");
		assertEquals("image/jpeg", parts.get(0).path("inlineData").path("mimeType").asString());
		assertTrue(parts.get(1).path("text").asString().contains("user"));
	}

	@Test
	void 응답_텍스트를_돌려준다() {
		String out = api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024);
		assertEquals("{\"a\":1}", out);
	}

	@Test
	void 일시_429는_재시도로_넘긴다() {
		status429Count.set(2);
		String out = api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024);
		assertEquals("{\"a\":1}", out);
		assertEquals(3, bodies.size());
	}

	@Test
	void 재시도_소진까지_429면_쿼터_예외() {
		status429Count.set(100);
		assertThrows(LlmQuotaExhaustedException.class,
				() -> api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024));
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests GeminiHttpApiTest`
Expected: 컴파일 실패 (GeminiApi/GeminiHttpApi/LlmQuotaExhaustedException 없음)

- [ ] **Step 3: 구현**

`LlmQuotaExhaustedException.java`:

```java
package com.celfit.analytics.llm;

/**
 * LLM 일 한도(429 재시도 소진) — 잡은 이를 에러가 아닌 "잔여 이월" 신호로 받아 배치를 중단한다
 * (기존 "다음 실행 재대상" 컨벤션의 배치 단위 판).
 */
public class LlmQuotaExhaustedException extends RuntimeException {
	public LlmQuotaExhaustedException(String message) {
		super(message);
	}
}
```

`GeminiApi.java`:

```java
package com.celfit.analytics.llm;

/** Gemini generateContent 1콜 추상화 — 어댑터는 이 인터페이스만 보고, 테스트는 fake로 대체한다. */
public interface GeminiApi {

	record InlineImage(String mimeType, byte[] data) {}

	/**
	 * 구조화 JSON 출력 1콜. schemaJson은 Gemini responseSchema(OpenAPI 스타일 — additionalProperties
	 * 불가, nullable 사용) JSON 텍스트. 반환은 응답 본문 텍스트(JSON).
	 * 일 한도 소진(429 재시도 소진)은 {@link LlmQuotaExhaustedException}.
	 */
	String generateJson(String model, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens);
}
```

`GeminiHttpApi.java`:

```java
package com.celfit.analytics.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini REST 구현 — 검증된 호출 형태(골드셋 스파이크 run_gemini.py) 그대로:
 * systemInstruction + responseSchema 구조화 출력, temperature 0.
 * 무료 티어 대응: RPM 페이싱(분당 rpm콜 균등) + 429/5xx 지수 백오프, 재시도 소진 429는 일 한도로 간주.
 */
public final class GeminiHttpApi implements GeminiApi {

	private static final Logger log = LoggerFactory.getLogger(GeminiHttpApi.class);
	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
	private static final int MAX_ATTEMPTS = 6;

	private final String apiKey;
	private final String baseUrl;
	private final long paceIntervalMillis;
	private final long retryBaseMillis;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper om = new ObjectMapper();
	private long nextAllowedAt; // 페이싱 — 마지막 허용 시각 (synchronized 접근)

	public GeminiHttpApi(String apiKey, String baseUrl, int rpm, long retryBaseMillis) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.paceIntervalMillis = Math.max(0, 60_000L / Math.max(1, rpm));
		this.retryBaseMillis = retryBaseMillis;
	}

	/** 무료 프로젝트 키(GEMINI_API_KEY) — 일상 파이프라인용. */
	public static GeminiHttpApi fromEnv(int rpm) {
		return new GeminiHttpApi(requireEnv("GEMINI_API_KEY"), DEFAULT_BASE_URL, rpm, 15_000);
	}

	/** 유료 프로젝트 키(GEMINI_API_KEY_PAID) — 백필 Batch 전용. 일상 파이프라인에서 쓰지 않는다. */
	public static GeminiHttpApi fromEnvPaid() {
		return new GeminiHttpApi(requireEnv("GEMINI_API_KEY_PAID"), DEFAULT_BASE_URL, 60, 15_000);
	}

	static String requireEnv(String name) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) {
			throw new IllegalStateException(name + " 미설정 — 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
		}
		return v;
	}

	@Override
	public String generateJson(String model, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens) {
		String body = requestBody(om, systemInstruction, userText, image, schemaJson, maxOutputTokens);
		String responseBody = send("/v1beta/models/" + model + ":generateContent", body);
		JsonNode root = om.readTree(responseBody);
		JsonNode usage = root.path("usageMetadata");
		log.info("gemini usage: model={} input={} output={}", model,
				usage.path("promptTokenCount").asInt(), usage.path("candidatesTokenCount").asInt());
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		return text.asString();
	}

	/** POST 공통 — 페이싱 + 429/5xx 백오프. 백필 러너의 배치 엔드포인트도 이 경로를 쓴다. */
	String send(String path, String jsonBody) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			pace();
			int status;
			String responseBody;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.header("x-goog-api-key", apiKey)
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				status = res.statusCode();
				responseBody = res.body();
			} catch (java.io.IOException | InterruptedException e) {
				throw new IllegalStateException("Gemini 호출 실패: " + path, e);
			}
			if (status >= 200 && status < 300) {
				return responseBody;
			}
			boolean retryable = status == 429 || status == 500 || status == 503;
			if (retryable && attempt < MAX_ATTEMPTS) {
				long wait = retryBaseMillis * attempt;
				log.warn("gemini HTTP {} — {}ms 후 재시도 ({}/{})", status, wait, attempt, MAX_ATTEMPTS);
				sleep(wait);
				continue;
			}
			if (status == 429) {
				throw new LlmQuotaExhaustedException("Gemini 429 재시도 소진 — 일 한도로 간주, 잔여 이월");
			}
			throw new IllegalStateException("Gemini HTTP " + status + ": " + abbreviate(responseBody));
		}
		throw new IllegalStateException("도달 불가");
	}

	static String requestBody(ObjectMapper om, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
		ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
		if (image != null) {
			ObjectNode inline = parts.addObject().putObject("inlineData");
			inline.put("mimeType", image.mimeType());
			inline.put("data", Base64.getEncoder().encodeToString(image.data()));
		}
		parts.addObject().put("text", userText);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(schemaJson));
		gen.put("maxOutputTokens", maxOutputTokens);
		return om.writeValueAsString(root);
	}

	private synchronized void pace() {
		long now = System.currentTimeMillis();
		long wait = nextAllowedAt - now;
		if (wait > 0) {
			sleep(wait);
		}
		nextAllowedAt = Math.max(now, nextAllowedAt) + paceIntervalMillis;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("대기 중 인터럽트", e);
		}
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests GeminiHttpApiTest`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/GeminiApi.java \
        analytics/src/main/java/com/celfit/analytics/llm/GeminiHttpApi.java \
        analytics/src/main/java/com/celfit/analytics/llm/LlmQuotaExhaustedException.java \
        analytics/src/test/java/com/celfit/analytics/llm/GeminiHttpApiTest.java
git commit -m "feat(analytics): Gemini REST 클라이언트 — responseSchema 구조화 출력 + RPM 페이싱·429 백오프"
```

---

### Task 3: 통합 콘텐츠 포트 (`ContentInsightPort`) + Anthropic 컴포지트 + 잡 리팩토링

②속성+③종합을 포트 수준에서 1콜로 합친다. 잡은 통합 포트만 본다. Anthropic 경로는 기존 어댑터 2종을 감싼 컴포지트(2콜)로 보존 — 롤백 스위치용. Anthropic 문구 프롬프트에도 절제 규칙(GUARD)을 추가한다(결정 4 — 프롬프트 필수 규칙은 프로바이더 공통).

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/ContentInsightPort.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicContentInsight.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/LlmGuard.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicSynthesizer.java` (INSTRUCTIONS에 GUARD 추가)
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/AnthropicAccountSynthesizer.java` (동일)
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (포트 2개 → 1개)
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (배선)
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java` (contentInsightPort 빈 — 이 태스크에서는 Anthropic 컴포지트로만, Gemini 분기는 Task 6)
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (fake를 통합 포트로 교체)

- [ ] **Step 1: `LlmGuard` — 절제 규칙 단일 원천** (골드셋 검증 문구 verbatim — `run_copy_quality2.py` GUARD 블록)

```java
package com.celfit.analytics.llm;

/**
 * 문구 생성(③종합·④계정 카피) 절제 규칙 — 골드셋(07-18)에서 조언 2→0건·얇은 표본 헤지 7/7 검증된
 * 문구 그대로. 프로바이더 공통이라 프롬프트 조립부가 공유한다. 문구 수정 시 골드셋 재검 권장.
 */
public final class LlmGuard {

	public static final String RULES = """
			[절제 규칙 — 반드시 지켜라]
			- 분석 표본이 3건 미만이면 성과·패턴·추이를 단정하지 말고 "표본이 부족해 판단하기 어렵다"를 명시하라.
			- 입력에 없는 사실·패턴·경향을 추론해 단정하지 마라. 평균과 값이 같은 이유가 표본 1건 때문이면 "안정적"이라 표현하지 마라.
			- 조언·제안·전략 제시는 금지다. 관찰과 해석만 쓴다 ("~가 필요합니다", "~하는 전략" 금지).
			- 핵심 주장에는 근거 수치를 함께 인용하라.""";

	private LlmGuard() {}
}
```

- [ ] **Step 2: `ContentInsightPort`**

```java
package com.celfit.analytics.llm;

/**
 * ②속성 추출 + ③콘텐츠 종합 통합 포트 (2026-07-18 확정 — 캡션·시스템 프롬프트 중복 제거, 호출 수 절반).
 * Gemini 구현은 1콜, Anthropic 구현은 기존 어댑터 2콜 컴포지트(롤백 경로).
 */
public interface ContentInsightPort {

	record ContentInsight(ContentAttributes attributes, Synthesis synthesis) {}

	/** @param thumbnailUrl null이면 캡션만으로 속성 분석. 캡션·썸네일 모두 없는 판단은 호출자(잡) 몫. */
	ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl);
}
```

- [ ] **Step 3: Anthropic 컴포지트**

```java
package com.celfit.analytics.llm;

/**
 * 통합 포트의 Anthropic 경로 — 기존 어댑터 2종(속성·종합)을 그대로 2콜로 감싼다.
 * app_setting(analytics.llm-provider=anthropic) 롤백 스위치용.
 */
public final class AnthropicContentInsight implements ContentInsightPort {

	private final ContentAttributePort attributes;
	private final SynthesisPort synthesis;

	public AnthropicContentInsight(ContentAttributePort attributes, SynthesisPort synthesis) {
		this.attributes = attributes;
		this.synthesis = synthesis;
	}

	@Override
	public ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl) {
		boolean hasCaption = content.caption() != null && !content.caption().isBlank();
		ContentAttributes attrs = hasCaption || thumbnailUrl != null
				? attributes.analyze(content.caption(), thumbnailUrl)
				: null;
		return new ContentInsight(attrs, synthesis.synthesize(content));
	}
}
```

- [ ] **Step 4: Anthropic 문구 프롬프트에 GUARD 추가**

`AnthropicSynthesizer` — `INSTRUCTIONS` 상수를 다음으로 교체(항목 설명은 기존 그대로, 끝에 GUARD):

```java
	private static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 주어진 수치만 근거로 삼고
			수치를 지어내지 마라. 한국어로, 각 항목 2~3문장 이내.

			- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
			  협찬 수용도를 종합한 요약
			- contentsPattern: 이 계정의 어떤 콘텐츠 패턴에서 성과가 나는지 한 줄 해석
			- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
			- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
			- commentAuthenticityNote: 판정 근거 한 줄

			%s
			""".formatted(LlmGuard.RULES);
```

`AnthropicAccountSynthesizer` — 동일 방식으로 `INSTRUCTIONS` 끝에 `%s`+`.formatted(LlmGuard.RULES)` 추가(항목 설명 7종은 기존 그대로).

- [ ] **Step 5: `ContentAnalysisJob` 리팩토링** — 필드 `SynthesisPort synthesis; ContentAttributePort attributes;` → `ContentInsightPort insight;` 하나로. 생성자 시그니처 변경:

```java
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive) {
```

`analyzeOne` 중 포트 호출부(기존 `ContentAttributes attrs = …`와 `Synthesis s = synthesis.synthesize(…)` 두 곳)를 다음으로 교체 — 입력 전무 시 속성 폐기 규칙(기존 "속성 컬럼만 NULL" 시맨틱)은 잡에 유지:

```java
		ContentInsightPort.ContentInsight result = insight.analyze(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption,
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts), attachThumbnail ? thumbnailUrl : null);
		// 캡션도 썸네일도 없으면 속성 근거 입력이 없다 — 통합 콜이 돌려줘도 폐기하고 속성 컬럼 NULL 유지.
		ContentAttributes attrs = hasCaption || attachThumbnail ? result.attributes() : null;
		Synthesis s = result.synthesis();
```

(주의: `baselineForPrompt` 조립 블록을 포트 호출보다 위로 이동해야 한다 — 기존 코드는 속성 콜이 먼저였음. INSERT 문과 빈 종합 가드는 무변경.)

- [ ] **Step 6: `JobConfig.contentAnalysisJob` 배선 교체**

```java
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off — 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck());
	}
```

- [ ] **Step 7: `LlmConfig`에 통합 포트 빈 추가** (이 태스크에서는 Anthropic 고정 — Task 6에서 프로바이더 분기로 교체)

```java
	@Bean
	@Lazy
	public ContentInsightPort contentInsightPort(AnthropicClient client, AnalyticsSettings settings,
			BeautyTaxonomyLoader taxonomyLoader) {
		return new AnthropicContentInsight(
				new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader),
				new AnthropicSynthesizer(client, settings));
	}
```

기존 `synthesisPort`/`contentAttributePort` 빈 메서드는 삭제(소비자가 사라짐 — 클래스는 컴포지트가 직접 생성). `commentClassificationPort`·`accountSynthesisPort`·`beautyTaxonomyLoader` 빈은 유지.

- [ ] **Step 8: `ContentAnalysisJobTest` fake 교체** — `fakeSynthesisPort()`/`fakeAttributePort()`/`rewireJob` 를 통합 포트 기반으로:

```java
	List<ContentToAnalyze> insightCalls;
	List<String> thumbnailArgs; // 통합 콜에 전달된 thumbnailUrl (null = 캡션만)

	ContentInsightPort fakeInsightPort() {
		return (content, thumbnailUrl) -> {
			insightCalls.add(content);
			thumbnailArgs.add(thumbnailUrl);
			return new ContentInsightPort.ContentInsight(
					new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), "high",
							List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
							List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
							List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
							List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored"),
					new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "high", "판정 근거"));
		};
	}

	void rewireJob(ContentInsightPort port, boolean thumbnailEnabled) {
		rewireJob(port, thumbnailEnabled, url -> true);
	}

	void rewireJob(ContentInsightPort port, boolean thumbnailEnabled,
			java.util.function.Predicate<String> thumbnailAlive) {
		job = new ContentAnalysisJob(db, ds, port, new AnalyticsSettings(db), thumbnailEnabled, thumbnailAlive);
	}
```

기존 테스트 케이스의 의미는 유지하며 어서션만 이행: `synthesisCalls` → `insightCalls`, `attributeCalls` → `thumbnailArgs`. "입력 전무면 속성 생략" 케이스는 "통합 콜은 1회 나가되 속성 컬럼은 NULL 저장"으로 어서션 변경(포트 호출 수 + DB 컬럼 NULL 확인). 실패 격리 케이스의 예외 던지는 fake도 통합 포트 람다로 교체.

- [ ] **Step 9: 테스트 실행**

Run: `./gradlew :analytics:test --tests ContentAnalysisJobTest --tests AnthropicContentAttributeAnalyzerTest`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add -A analytics/src
git commit -m "feat(analytics): 속성+종합 통합 포트 ContentInsightPort — 잡 1콜화, Anthropic은 컴포지트 보존 + 문구 절제 규칙"
```

---

### Task 4: `GeminiContentAnalyzer` — 통합 1콜 어댑터

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java`

- [ ] **Step 1: 실패하는 테스트** — fake `GeminiApi`로 (a) 요청 인자(모델·스키마·프롬프트 구성) (b) 응답 JSON → 레코드 매핑+sanitize (c) 어휘 밖 값 방어 (d) 등급 방어를 고정. `BeautyTaxonomy`는 기존 `BeautyTaxonomyTest`가 쓰는 생성 방식(엔트리 리스트)으로 만든다.

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 통합(속성+종합) Gemini 어댑터 계약: 프롬프트 조립·스키마·응답 매핑·어휘/등급 방어. */
class GeminiContentAnalyzerTest {

	static final String RESPONSE = """
			{"detectedBrands":[{"name":"브랜드A","evidence":"캡션 언급"}],
			 "sponsoredSignalLevel":"엉뚱값","sponsoredSignalReasons":["#협찬"],
			 "adDisclosure":"표기 있음","detectedProductCategories":["클렌징폼","없는라벨"],
			 "detectedProducts":[{"name":"딥클렌징폼","brand":null}],
			 "vlmAttributes":[],"mainCategory":"cleansing","subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영","쿠팡"],"adType":"sponsored",
			 "aiContentSummary":"평균 대비 1.2배","contentsPattern":"클렌징 루틴형",
			 "aiCommentInsight":"표본 부족","commentAuthenticityGrade":"이상값","commentAuthenticityNote":"근거"}""";

	BeautyTaxonomy taxonomy = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼")),
			List.of("올리브영", "다이소"));

	record Call(String model, String system, String user, GeminiApi.InlineImage image, String schema) {}

	java.util.List<Call> calls = new java.util.ArrayList<>();

	GeminiApi fakeApi(String response) {
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, image, schema));
			return response;
		};
	}

	ContentToAnalyze content() {
		return new ContentToAnalyze("post_a", "acct1", "캡션A", "reels", 11000L, 520L, 52L,
				Map.of("recent_contents_count", 3), Map.of());
	}

	@Test
	void 통합_1콜로_속성과_종합을_함께_돌려준다() {
		GeminiContentAnalyzer analyzer = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "gemini-3.1-flash-lite", () -> taxonomy);
		ContentInsightPort.ContentInsight r = analyzer.analyze(content(), null);
		assertEquals(1, calls.size());
		assertEquals("gemini-3.1-flash-lite", calls.get(0).model());
		assertEquals("브랜드A", r.attributes().detectedBrands().get(0).name());
		assertEquals("평균 대비 1.2배", r.synthesis().aiContentSummary());
	}

	@Test
	void 프롬프트에_속성_종합_절제규칙_분류표가_모두_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String system = calls.get(0).system();
		assertTrue(system.contains("detectedBrands"));
		assertTrue(system.contains("aiContentSummary"));
		assertTrue(system.contains("[절제 규칙 — 반드시 지켜라]"));
		assertTrue(system.contains("클렌징폼/젤"));
		String user = calls.get(0).user();
		assertTrue(user.contains("캡션A"));
		assertTrue(user.contains("views=11000"));
	}

	@Test
	void 어휘_밖_값은_sanitize로_걸러진다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertNull(r.attributes().sponsoredSignalLevel()); // "엉뚱값" 제거
		assertEquals(List.of("클렌징폼"), r.attributes().detectedProductCategories()); // "없는라벨" 제거
		assertEquals(List.of("올리브영"), r.attributes().detectedDistributors()); // "쿠팡" 제거
	}

	@Test
	void 등급_밖_값은_normal로_강제된다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals("normal", r.synthesis().commentAuthenticityGrade());
	}
}
```

(참고: `BeautyTaxonomy` 생성자 시그니처는 실제 코드에 맞출 것 — 엔트리 리스트+유통사 리스트가 아니면 기존 `BeautyTaxonomyTest`가 쓰는 팩토리를 그대로 따른다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests GeminiContentAnalyzerTest`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.celfit.analytics.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * ②속성+③종합 통합 Gemini 어댑터 — 1콜로 ContentAttributes 11필드 + Synthesis 5필드 합본을 받는다
 * (2026-07-18 확정). 스키마·호출 형태는 골드셋 스파이크(run_gemini.py) 검증본, 어휘 sanitize는
 * Anthropic 속성 어댑터와 동일 로직 공유. 썸네일은 직접 다운로드 후 inlineData(base64).
 */
public final class GeminiContentAnalyzer implements ContentInsightPort {

	private static final Set<String> GRADES = Set.of("high", "normal", "suspect");
	static final int MAX_OUTPUT_TOKENS = 4096;

	/** 통합 산출 스키마 — 속성 11필드(nullable) + 종합 5필드. Gemini responseSchema(OpenAPI 스타일). */
	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "detectedBrands":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"evidence":{"type":"string","nullable":true}},
			    "required":["name","evidence"]}},
			  "sponsoredSignalLevel":{"type":"string","nullable":true},
			  "sponsoredSignalReasons":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adDisclosure":{"type":"string","nullable":true},
			  "detectedProductCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedProducts":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"brand":{"type":"string","nullable":true}},
			    "required":["name","brand"]}},
			  "vlmAttributes":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"label":{"type":"string"},"value":{"type":"string"}},
			    "required":["label","value"]}},
			  "mainCategory":{"type":"string","nullable":true},
			  "subCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedDistributors":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adType":{"type":"string","nullable":true},
			  "aiContentSummary":{"type":"string"},
			  "contentsPattern":{"type":"string"},
			  "aiCommentInsight":{"type":"string"},
			  "commentAuthenticityGrade":{"type":"string"},
			  "commentAuthenticityNote":{"type":"string"}},
			 "required":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","mainCategory","subCategories",
			  "detectedDistributors","adType","aiContentSummary","contentsPattern","aiCommentInsight",
			  "commentAuthenticityGrade","commentAuthenticityNote"]}""";

	private final GeminiApi api;
	private final Supplier<String> model;
	private final Supplier<BeautyTaxonomy> taxonomy;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final ObjectMapper om = new ObjectMapper();

	public GeminiContentAnalyzer(GeminiApi api, Supplier<String> model, Supplier<BeautyTaxonomy> taxonomy) {
		this.api = api;
		this.model = model;
		this.taxonomy = taxonomy;
	}

	/** 통합 시스템 프롬프트 — 속성 지침(골드셋 검증) + 종합 지침 + 절제 규칙 + 분류표. */
	static String instructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 캡션(과 썸네일이 주어지면 썸네일)과
				지표를 보고 [A] 콘텐츠 속성과 [B] 종합 해석을 한 번에 산출하라. 한국어로.

				[A. 속성 — 캡션·썸네일 근거. 확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라]
				- detectedBrands: 캡션·화면에서 확인되는 브랜드 {name, evidence(근거)} —
				  브랜드를 특정할 수 없는 제품은 목록에서 제외하라 ("미상"/"불명확" 같은 표기 금지)
				- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
				- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
				- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
				- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로
				  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"])
				- detectedProductCategories: 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
				- detectedProducts: 확인되는 제품명 {name(상품명), brand(그 제품의 브랜드, 미상이면 null)}
				- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외
				- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
				  콘텐츠 유형 / 무드 / 편집 스타일 순 (썸네일 없이 판단 불가한 항목은 제외)
				- adType: organic|sponsored (캡션 표기+화면 종합 판정)

				[B. 종합 — 주어진 수치만 근거로 삼고 수치를 지어내지 마라. 각 항목 2~3문장 이내]
				- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
				  협찬 수용도를 종합한 요약
				- contentsPattern: 이 계정의 어떤 콘텐츠 패턴에서 성과가 나는지 한 줄 해석
				- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
				- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
				- commentAuthenticityNote: 판정 근거 한 줄

				%s

				[분류표 — 대분류(한글): 중분류[소분류, …]]
				%s""".formatted(taxonomy.distributorsPrompt(), LlmGuard.RULES, taxonomy.promptTable());
	}

	/** 유저 입력 — 기존 AnthropicSynthesizer 입력 포맷 그대로 (골드셋 문구 검증도 이 포맷). */
	static String userText(ContentToAnalyze c, boolean withThumbnail) {
		return """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s

				위 %s과 지표를 분석하라.""".formatted(c.shortCode(), c.accountHandle(), c.contentType(),
				c.caption() == null ? "(없음)" : c.caption(), c.views(), c.likes(), c.comments(),
				c.baseline(), c.commentCategoryCounts(), withThumbnail ? "썸네일·캡션" : "캡션");
	}

	@Override
	public ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl) {
		BeautyTaxonomy tx = taxonomy.get();
		GeminiApi.InlineImage image = thumbnailUrl == null ? null : download(thumbnailUrl);
		String out = api.generateJson(model.get(), instructions(tx),
				userText(content, image != null), image, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return parse(om, out, tx);
	}

	/** 응답 JSON → 속성(sanitize)+종합(등급 방어). 백필 러너(Batch 결과 파싱)도 이 진입점을 쓴다. */
	static ContentInsight parse(ObjectMapper om, String json, BeautyTaxonomy taxonomy) {
		Output o = om.readValue(json, Output.class);
		ContentAttributes attrs = AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType()), taxonomy);
		String grade = GRADES.contains(o.commentAuthenticityGrade()) ? o.commentAuthenticityGrade() : "normal";
		return new ContentInsight(attrs, new Synthesis(o.aiContentSummary(), o.contentsPattern(),
				o.aiCommentInsight(), grade, o.commentAuthenticityNote()));
	}

	/** 통합 산출 합본 — RESPONSE_SCHEMA와 1:1. */
	record Output(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			String commentAuthenticityGrade, String commentAuthenticityNote) {}

	/** 썸네일 직접 다운로드 — Anthropic 어댑터와 같은 사유(CDN 직링크 불가 대비 아닌, 만료 프리체크 후 첨부). */
	private GeminiApi.InlineImage download(String thumbnailUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(thumbnailUrl))
					.timeout(Duration.ofSeconds(15)).build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("썸네일 다운로드 실패 HTTP " + res.statusCode());
			}
			String mime = res.headers().firstValue("content-type").orElse("image/jpeg");
			return new GeminiApi.InlineImage(mime.split(";")[0].trim(), res.body());
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("썸네일 다운로드 실패: " + thumbnailUrl, e);
		}
	}
}
```

주의: `AnthropicContentAttributeAnalyzer.sanitize`는 같은 패키지의 package-private static — 접근 가능. `GeminiContentAnalyzer` 생성자의 `Supplier<String> model`/`Supplier<BeautyTaxonomy>`는 배선 시 `settings::geminiModel`(Task 6)·`taxonomyLoader::get`을 넘긴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests GeminiContentAnalyzerTest`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/GeminiContentAnalyzer.java \
        analytics/src/test/java/com/celfit/analytics/llm/GeminiContentAnalyzerTest.java
git commit -m "feat(analytics): Gemini 통합 콘텐츠 분석 어댑터 — 속성+종합 1콜, 스파이크 검증 스키마·절제 규칙"
```

---

### Task 5: `GeminiAccountSynthesizer` — ④계정 카피

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java`
- Test: `analytics/src/test/java/com/celfit/analytics/llm/GeminiAccountSynthesizerTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 계정 카피 Gemini 어댑터 계약: 입력 포맷·GUARD 포함 프롬프트·레코드 매핑. */
class GeminiAccountSynthesizerTest {

	static final String RESPONSE = """
			{"tagline":"저자극 스킨케어 리뷰 톤","summary":"요약 문장","trendNote":"상승 12%",
			 "chartNote":"상위 3개가 견인","traits":["정보형","스킨케어"],"adHeadline":"","paceNote":"주 2회"}""";

	record Call(String model, String system, String user, String schema) {}

	java.util.List<Call> calls = new java.util.ArrayList<>();

	GeminiApi fakeApi() {
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, schema));
			return RESPONSE;
		};
	}

	@Test
	void 카피_7종을_레코드로_돌려준다() {
		AccountCopy copy = new GeminiAccountSynthesizer(fakeApi(), () -> "gemini-3.1-flash-lite")
				.synthesize(new AccountToAnalyze("acct1", Map.of("avg_views", 1000),
						List.of(Map.of("main_group", "cleansing")), List.of(), false));
		assertEquals("저자극 스킨케어 리뷰 톤", copy.tagline());
		assertEquals(List.of("정보형", "스킨케어"), copy.traits());
	}

	@Test
	void 프롬프트에_절제규칙과_광고비교_유무가_실린다() {
		new GeminiAccountSynthesizer(fakeApi(), () -> "m")
				.synthesize(new AccountToAnalyze("acct1", Map.of(), List.of(), List.of(), false));
		assertTrue(calls.get(0).system().contains("[절제 규칙 — 반드시 지켜라]"));
		assertTrue(calls.get(0).user().contains("광고 비교 데이터: 없음"));
		assertTrue(calls.get(0).schema().contains("adHeadline"));
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests GeminiAccountSynthesizerTest`
Expected: 컴파일 실패

- [ ] **Step 3: 구현** — 프롬프트는 골드셋 검증본(`run_copy_quality2.py` COPY_SYS) verbatim, 입력 포맷은 Anthropic 어댑터와 동일.

```java
package com.celfit.analytics.llm;

import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** 계정 카피 Gemini 어댑터 — 카피 7종 1콜. 프롬프트·GUARD는 골드셋(07-18) 문구 검증 통과본. */
public final class GeminiAccountSynthesizer implements AccountSynthesisPort {

	static final int MAX_OUTPUT_TOKENS = 4096;

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "tagline":{"type":"string"},"summary":{"type":"string"},"trendNote":{"type":"string"},
			  "chartNote":{"type":"string"},"traits":{"type":"array","items":{"type":"string"}},
			  "adHeadline":{"type":"string"},"paceNote":{"type":"string"}},
			 "required":["tagline","summary","trendNote","chartNote","traits","adHeadline","paceNote"]}""";

	static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개 — 콘텐츠 성격·톤 (예: "저자극 스킨케어 중심 · 성분과 사용감을 짚는 정보형 리뷰 톤"). 40자 이내
			- summary: 마케터 관점의 계정 분석 요약, 3~4문장
			- trendNote: 최근 흐름 한 문장 (trend_direction·trend_change_pct 근거)
			- chartNote: 게시물별 성과 분포의 특징 한 문장 (예: "잘 터진 3개가 평균을 끌어올림")
			- traits: 콘텐츠 성향 태그 3~5개, 각 2~6자 명사구
			- adHeadline: 광고 비교 수치(organic_avg·ad_avg·ad_drop_pct) 근거 헤드라인 한 문장.
			  입력에 "광고 비교 데이터: 없음"이면 빈 문자열
			- paceNote: 업로드 페이스 한 문장 (avg_interval_days 근거, 예: "주 2~3회 올리는 페이스")

			%s""".formatted(LlmGuard.RULES);

	private final GeminiApi api;
	private final Supplier<String> model;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiAccountSynthesizer(GeminiApi api, Supplier<String> model) {
		this.api = api;
		this.model = model;
	}

	@Override
	public AccountCopy synthesize(AccountToAnalyze account) {
		String input = """
				계정: @%s (광고 비교 데이터: %s)
				계정 지표: %s
				카테고리 믹스: %s
				게시물(올린 순, 캡션은 앞부분만): %s
				""".formatted(account.handle(), account.hasAdComparison() ? "있음" : "없음",
				account.summary(), account.categoryStats(), account.posts());
		String out = api.generateJson(model.get(), INSTRUCTIONS, input, null,
				RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return om.readValue(out, AccountCopy.class);
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :analytics:test --tests GeminiAccountSynthesizerTest`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java \
        analytics/src/test/java/com/celfit/analytics/llm/GeminiAccountSynthesizerTest.java
git commit -m "feat(analytics): Gemini 계정 카피 어댑터 — 골드셋 검증 프롬프트·절제 규칙"
```

---

### Task 6: 프로바이더 선택·설정 키·쿼터 이월 배선

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (모델 키·쿼터 이월)
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java` (동일)
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/JobCostEstimator.java` (비용 카드 문구)
- Test: `analytics/src/test/java/com/celfit/analytics/config/AnalyticsSettingsTest.java` (있으면 갱신, 없으면 신설)
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (쿼터 이월 케이스 추가)

- [ ] **Step 1: 설정 키 테스트** (기존 AnalyticsSettings 테스트 위치·패턴 확인 후 — 없으면 Testcontainers 대신 `ContentAnalysisJobTest`처럼 앱세팅 테이블 가진 컨테이너 재사용은 과함: `AnalyticsSettings`는 JdbcTemplate만 필요하므로 기존 잡 테스트 컨테이너에 얹거나 간단 신설)

핵심 어서션:

```java
	// app_setting 미설정 시 기본값
	assertEquals("gemini", settings.llmProvider());
	assertEquals("gemini-3.1-flash-lite", settings.geminiModel());
	assertEquals(15, settings.geminiRpm());
	assertEquals("gemini-3.1-flash-lite", settings.activeLlmModel());
	// anthropic 전환 시
	db.update("INSERT INTO app_setting VALUES ('analytics.llm-provider','anthropic')");
	assertEquals("claude-opus-4-8", settings.activeLlmModel());
```

- [ ] **Step 2: `AnalyticsSettings` 구현** — 키 3종 추가:

```java
	/** LLM 프로바이더 선택 — gemini(기본) | anthropic (롤백 경로). 전환은 재기동 필요(빈 생성 시 결정). */
	public static final String KEY_LLM_PROVIDER = "analytics.llm-provider";
	/** Gemini 모델 — 2026-07-18 골드셋 확정. 구모델(2.5 등)은 신규 키에서 404. */
	public static final String KEY_GEMINI_MODEL = "analytics.gemini-model";
	/** Gemini 분당 호출 상한 — 무료 티어 15 RPM. crawler 판정과 동시 실행 시 합산 초과 주의. */
	public static final String KEY_GEMINI_RPM = "analytics.gemini-rpm";

	static final String DEFAULT_LLM_PROVIDER = "gemini";
	static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite";
	static final int DEFAULT_GEMINI_RPM = 15;

	public String llmProvider() {
		return read(KEY_LLM_PROVIDER).orElse(DEFAULT_LLM_PROVIDER);
	}

	public String geminiModel() {
		return read(KEY_GEMINI_MODEL).orElse(DEFAULT_GEMINI_MODEL);
	}

	public int geminiRpm() {
		return read(KEY_GEMINI_RPM).map(Integer::parseInt).orElse(DEFAULT_GEMINI_RPM);
	}

	/** content_analyses.model 등 기록·호출에 쓰는 활성 모델명 — 프로바이더 따라 결정. */
	public String activeLlmModel() {
		return "anthropic".equals(llmProvider()) ? llmModel() : geminiModel();
	}
```

- [ ] **Step 3: `LlmConfig` 프로바이더 분기** — `contentInsightPort`(Task 3에서 만든 것)와 `accountSynthesisPort`를 분기로 교체. Anthropic 클라이언트는 `ObjectProvider`로 지연(gemini 경로에서 ANTHROPIC 키 불요):

```java
	@Bean
	@Lazy
	public GeminiApi geminiApi(AnalyticsSettings settings) {
		return GeminiHttpApi.fromEnv(settings.geminiRpm()); // GEMINI_API_KEY (무료 프로젝트)
	}

	@Bean
	@Lazy
	public ContentInsightPort contentInsightPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini,
			BeautyTaxonomyLoader taxonomyLoader) {
		if ("anthropic".equals(settings.llmProvider())) {
			AnthropicClient client = anthropic.getObject();
			return new AnthropicContentInsight(
					new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader),
					new AnthropicSynthesizer(client, settings));
		}
		return new GeminiContentAnalyzer(gemini.getObject(), settings::geminiModel, taxonomyLoader::get);
	}

	@Bean
	@Lazy
	public AccountSynthesisPort accountSynthesisPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini) {
		if ("anthropic".equals(settings.llmProvider())) {
			return new AnthropicAccountSynthesizer(anthropic.getObject(), settings);
		}
		return new GeminiAccountSynthesizer(gemini.getObject(), settings::geminiModel);
	}
```

(import `org.springframework.beans.factory.ObjectProvider` 추가. `commentClassificationPort`는 Anthropic 유지 — 댓글 분류는 MVP 휴면이라 전환 제외, 07-18 결정.)

- [ ] **Step 4: 잡 2종 — 모델 키 교체 + 쿼터 이월**

`ContentAnalysisJob.run()`:

```java
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model);
				processed++;
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18 결정)
				log.warn("LLM 일 한도 소진 — 배치 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				break;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
		}
```

`AccountAnalysisJob.run()` — 동일 패턴 (`String model = settings.activeLlmModel();` + 쿼터 catch·break).

- [ ] **Step 5: 쿼터 이월 테스트** — `ContentAnalysisJobTest`에 케이스 추가:

```java
	@Test
	void 일_한도_소진이면_배치를_중단하고_잔여를_이월한다() {
		java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
		ContentInsightPort quotaPort = (content, thumbnailUrl) -> {
			if (n.incrementAndGet() >= 2) {
				throw new com.celfit.analytics.llm.LlmQuotaExhaustedException("일 한도");
			}
			return fakeInsightPort().analyze(content, thumbnailUrl);
		};
		rewireJob(quotaPort, false);
		int processed = job.run();
		assertEquals(1, processed);                       // 첫 건만 저장
		assertEquals(2, n.get());                          // 두 번째 콜에서 중단 — 이후 대상 콜 없음
		Long saved = db.queryForObject("SELECT count(*) FROM content_analyses", Long.class);
		assertEquals(1L, saved);
	}
```

(어서션 세부는 시드 데이터의 대상 수에 맞춰 조정 — 대상 2건 이상인 시드에서 두 번째 콜이 쿼터 예외를 던지게.)

- [ ] **Step 6: `JobCostEstimator` 문구 갱신** — ANALYZE·ACCOUNT_ANALYZE 카드 note를 프로바이더 반영 문구로:

```java
				CostCard.of(JobName.ANALYZE, analyzeTargets(), ANALYZE_UNIT_MIN, ANALYZE_UNIT_MAX,
						"기본 Gemini 무료 티어 $0 — 표시 단가는 anthropic 전환 시 참고치($0.03~0.05, 07-14 실측)"),
				CostCard.of(JobName.ACCOUNT_ANALYZE, accountAnalyzeTargets(), null, null,
						"기본 Gemini 무료 티어 $0 — 건수만 표시 (계정당 1콜)"));
```

- [ ] **Step 7: 테스트 실행**

Run: `./gradlew :analytics:test`
Expected: PASS (전체 — LlmConfig 배선 변화가 다른 테스트를 깨지 않는지 확인)

- [ ] **Step 8: 커밋**

```bash
git add -A analytics/src
git commit -m "feat(analytics): LLM 프로바이더 선택(app_setting) — 기본 Gemini, 일 한도 소진 시 배치 이월"
```

---### Task 7: crawler `GeminiBeautyJudge` — ①뷰티 판정

팀 프롬프트·파서(`ClaudeCliBeautyJudge.buildPrompt/parse`)를 재사용하고 전송만 Gemini REST로. 크롤러는 analytics 코드를 공유할 수 없으므로(모듈 공유 금지 §4-4) 작은 전용 호출부를 갖는다. 선택은 `crawler.beauty.judge` 프로퍼티(기본 gemini — 07-18 확정, claude-cli 롤백 가능).

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java` (`buildPrompt`/`parse` → `public static`, `@ConditionalOnProperty` 추가)
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java`
- Modify: `crawler/src/main/resources/application.yml` (`crawler.beauty.judge: gemini` 주석 포함)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudgeTest.java`

- [ ] **Step 1: 실패하는 테스트** — 요청 본문 구조와 응답 텍스트 추출(순수 static) + 파서 연동:

```java
package com.celfit.crawler.crawling.adapter.out.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudge;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.ProfileCard;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Gemini 판정 전송층: 팀 프롬프트 재사용 + responseSchema 배열 출력 + 팀 파서로 매핑. */
class GeminiBeautyJudgeTest {

	ObjectMapper om = new ObjectMapper();

	@Test
	void 요청_본문에_팀_프롬프트와_배열_스키마가_실린다() {
		String prompt = ClaudeCliBeautyJudge.buildPrompt(om,
				List.of(new ProfileCard("user1", "이름", "카테고리", "바이오", List.of("캡션1"))));
		String body = GeminiBeautyJudge.requestBody(om, prompt);
		JsonNode root = om.readTree(body);
		String text = root.path("contents").get(0).path("parts").get(0).path("text").asString();
		assertTrue(text.contains("INFLUENCER"));
		assertTrue(text.contains("user1"));
		assertEquals("array", root.path("generationConfig").path("responseSchema").path("type").asString());
		assertEquals("application/json",
				root.path("generationConfig").path("responseMimeType").asString());
	}

	@Test
	void 응답_텍스트를_팀_파서로_판정에_매핑한다() {
		String response = """
				{"candidates":[{"content":{"parts":[{"text":
				"[{\\"username\\":\\"user1\\",\\"class\\":\\"COMPANY\\",\\"reason\\":\\"쇼핑몰\\"}]"}]}}]}""";
		String text = GeminiBeautyJudge.extractText(om, response);
		List<Verdict> verdicts = ClaudeCliBeautyJudge.parse(om, text);
		assertEquals(1, verdicts.size());
		assertTrue(verdicts.get(0).beauty());
		assertTrue(verdicts.get(0).company());
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests GeminiBeautyJudgeTest`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`ClaudeCliBeautyJudge` 수정 — 두 static 메서드 가시성만 `public static`으로 넓히고(시그니처·본문 무변경), 클래스에 선택 조건 추가:

```java
@Component
@ConditionalOnProperty(name = "crawler.beauty.judge", havingValue = "claude-cli")
public class ClaudeCliBeautyJudge implements BeautyJudge {
```

`GeminiBeautyJudge.java`:

```java
package com.celfit.crawler.crawling.adapter.out.gemini;

import com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudge;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 뷰티 판정 Gemini 구현 (2026-07-18 확정 — 전 분석 축 gemini-3.1-flash-lite 통일).
 * 프롬프트·파서는 팀 것(ClaudeCliBeautyJudge) 재사용 — 이 클래스는 전송(REST + responseSchema)만.
 * 실패 계약은 포트 그대로: CLI 오류·타임아웃·파싱 불가 → ApifyException (호출자가 배치 단위 격리).
 */
@Component
@ConditionalOnProperty(name = "crawler.beauty.judge", havingValue = "gemini", matchIfMissing = true)
public class GeminiBeautyJudge implements BeautyJudge {

	private static final Logger log = LoggerFactory.getLogger(GeminiBeautyJudge.class);
	private static final int MAX_ATTEMPTS = 6;
	private static final long RETRY_BASE_MILLIS = 15_000;
	private static final long PACE_MILLIS = 4_000; // 무료 티어 15 RPM — 청크 간 최소 간격

	static final String RESPONSE_SCHEMA = """
			{"type":"array","items":{"type":"object","properties":{
			  "username":{"type":"string"},"class":{"type":"string"},"reason":{"type":"string"}},
			 "required":["username","class","reason"]}}""";

	private final ObjectMapper om;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private long nextAllowedAt;

	public GeminiBeautyJudge(ObjectMapper om,
			@Value("${crawler.beauty.gemini-model:gemini-3.1-flash-lite}") String model) {
		this.om = om;
		this.model = model;
	}

	@Override
	public List<Verdict> judge(List<ProfileCard> cards) {
		String prompt = ClaudeCliBeautyJudge.buildPrompt(om, cards);
		String responseBody = call(requestBody(om, prompt));
		return ClaudeCliBeautyJudge.parse(om, extractText(om, responseBody));
	}

	static String requestBody(ObjectMapper om, String prompt) {
		ObjectNode root = om.createObjectNode();
		root.putArray("contents").addObject().put("role", "user")
				.putArray("parts").addObject().put("text", prompt);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", 8192);
		return om.writeValueAsString(root);
	}

	static String extractText(ObjectMapper om, String responseBody) {
		JsonNode text = om.readTree(responseBody)
				.path("candidates").path(0).path("content").path(0).path("text");
		// parts 경로 폴백 — 표준 형태는 content.parts[0].text
		if (text.isMissingNode()) {
			text = om.readTree(responseBody)
					.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		}
		if (text.isMissingNode()) {
			throw new ApifyException("Gemini 판정 응답에 본문 없음");
		}
		return text.asString();
	}

	private String call(String body) {
		String key = System.getenv("GEMINI_API_KEY");
		if (key == null || key.isBlank()) {
			throw new ApifyException("GEMINI_API_KEY 미설정 — 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
		}
		String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			pace();
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.header("x-goog-api-key", key)
						.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() >= 200 && res.statusCode() < 300) {
					return res.body();
				}
				if ((res.statusCode() == 429 || res.statusCode() == 500 || res.statusCode() == 503)
						&& attempt < MAX_ATTEMPTS) {
					long wait = RETRY_BASE_MILLIS * attempt;
					log.warn("gemini 판정 HTTP {} — {}ms 후 재시도 ({}/{})", res.statusCode(), wait,
							attempt, MAX_ATTEMPTS);
					Thread.sleep(wait);
					continue;
				}
				throw new ApifyException("Gemini 판정 HTTP " + res.statusCode());
			} catch (java.io.IOException e) {
				throw new ApifyException("Gemini 판정 호출 실패", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ApifyException("Gemini 판정 대기 중 인터럽트", e);
			}
		}
		throw new ApifyException("Gemini 판정 재시도 소진");
	}

	private synchronized void pace() {
		long now = System.currentTimeMillis();
		long wait = nextAllowedAt - now;
		if (wait > 0) {
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ApifyException("페이싱 대기 중 인터럽트", e);
			}
		}
		nextAllowedAt = Math.max(now, nextAllowedAt) + PACE_MILLIS;
	}
}
```

(주의: `ApifyException` 생성자 시그니처는 실제 코드 확인 후 맞출 것 — `(String)`/`(String, Throwable)`이 없으면 있는 형태로. `extractText`의 표준 경로는 `content.parts[0].text` — 구현 시 표준 경로를 먼저 시도.)

`application.yml` — `crawler.beauty` 아래 추가:

```yaml
  beauty:
    batch-limit: 500  # (기존 주석 유지)
    judge: gemini      # 판정 구현 선택 — gemini(기본, GEMINI_API_KEY 필요) | claude-cli(로컬 Claude CLI 롤백)
```

- [ ] **Step 4: 테스트 실행** — crawler 전체(기존 ClaudeCliBeautyJudgeTest 포함 — 가시성 변경 무해 확인, 컴포넌트 스캔 테스트가 있으면 조건부 빈 영향 확인)

Run: `./gradlew :crawler:test`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A crawler/src
git commit -m "feat(crawler): 뷰티 판정 Gemini 어댑터 — 팀 프롬프트·파서 재사용, crawler.beauty.judge로 선택(기본 gemini)"
```

---

### Task 8: 초기 백필 — Gemini Batch API one-shot 러너

2만 건(뷰티 모수 × 최근 윈도우)을 유료 키 Batch로 일회 처리(~$9). 흐름: **submit**(대상 조회 → JSONL 생성·업로드 → 배치 생성 → 사이드카 저장) / **collect**(상태 확인 → 결과 다운로드 → 파싱·sanitize → INSERT). Batch 처리(≤24h) 동안 프로세스를 붙잡지 않도록 2모드 분리.

**의존:** `analytics.v_analysis_candidates`·`v_analysis_baseline` (신 스키마 브랜치) — 코드는 문서화된 컬럼 계약으로 작성, 테스트는 픽스처 뷰 생성. **런타임 실행은 그 브랜치 머지 후 + `GEMINI_API_KEY_PAID` 등록 후.**

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisWriter.java` (INSERT 추출 — 잡·백필 공유)
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (INSERT를 writer 위임)
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/GeminiBackfillRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiHttpApi.java` (배치 엔드포인트 4종: upload/create/get/download)
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (러너 빈 — `analytics.backfill-*` 게이트)
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/GeminiBackfillRunnerTest.java`

- [ ] **Step 1: `ContentAnalysisWriter` 추출** — `ContentAnalysisJob.analyzeOne`의 INSERT 문(28컬럼)+`toJson`을 그대로 옮긴 static 유틸:

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.Synthesis;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** content_analyses INSERT 단일 원천 — 일상 잡과 백필 러너가 공유한다. 컬럼 변경 시 이 한 곳만. */
final class ContentAnalysisWriter {

	private ContentAnalysisWriter() {}

	/** conflictIgnore=true면 이미 분석된 행은 건너뛴다(백필 재실행 멱등). */
	static void insert(JdbcTemplate analysis, ObjectMapper json, String shortCode, String model,
			Baseline b, ContentAttributes attrs, Synthesis s, boolean conflictIgnore) {
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  ai_content_summary, contents_pattern, ai_comment_insight,
				  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
				  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
				  category_top_percentile, category_avg_views, category_sample_size,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, detected_products, vlm_attributes, main_category, sub_categories,
				  detected_distributors, ad_type,
				  comment_authenticity_grade, comment_authenticity_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?)"""
				+ (conflictIgnore ? " ON CONFLICT (short_code) DO NOTHING" : ""),
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				toJson(json, attrs == null ? null : attrs.detectedBrands()),
				attrs == null ? null : attrs.sponsoredSignalLevel(),
				toJson(json, attrs == null ? null : attrs.sponsoredSignalReasons()),
				attrs == null ? null : attrs.adDisclosure(),
				toJson(json, attrs == null ? null : attrs.detectedProductCategories()),
				toJson(json, attrs == null ? null : attrs.detectedProducts()),
				toJson(json, attrs == null ? null : attrs.vlmAttributes()),
				attrs == null ? null : attrs.mainCategory(),
				toJson(json, attrs == null ? null : attrs.subCategories()),
				toJson(json, attrs == null ? null : attrs.detectedDistributors()),
				attrs == null ? null : attrs.adType(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote());
	}

	private static String toJson(ObjectMapper json, Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}
}
```

`ContentAnalysisJob.analyzeOne`의 INSERT 블록을 `ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false);`로 교체. 기존 `ContentAnalysisJobTest` 통과 확인(리팩토링 무손상).

- [ ] **Step 2: `GeminiHttpApi` 배치 메서드 4종 추가**

```java
	/** File API 업로드(단순 2단계 resumable) — JSONL 바이트를 올리고 files/NNN 이름을 돌려준다. */
	public String uploadFile(byte[] jsonl, String displayName) {
		try {
			HttpRequest start = HttpRequest.newBuilder(URI.create(baseUrl + "/upload/v1beta/files"))
					.header("x-goog-api-key", apiKey)
					.header("X-Goog-Upload-Protocol", "resumable")
					.header("X-Goog-Upload-Command", "start")
					.header("X-Goog-Upload-Header-Content-Length", String.valueOf(jsonl.length))
					.header("X-Goog-Upload-Header-Content-Type", "application/jsonl")
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"file\":{\"display_name\":\"" + displayName + "\"}}"))
					.build();
			HttpResponse<String> started = http.send(start, HttpResponse.BodyHandlers.ofString());
			String uploadUrl = started.headers().firstValue("x-goog-upload-url")
					.orElseThrow(() -> new IllegalStateException("업로드 URL 헤더 없음: " + started.statusCode()));
			HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl))
					.header("X-Goog-Upload-Command", "upload, finalize")
					.header("X-Goog-Upload-Offset", "0")
					.POST(HttpRequest.BodyPublishers.ofByteArray(jsonl))
					.build();
			HttpResponse<String> done = http.send(put, HttpResponse.BodyHandlers.ofString());
			if (done.statusCode() < 200 || done.statusCode() >= 300) {
				throw new IllegalStateException("파일 업로드 실패 HTTP " + done.statusCode());
			}
			return om.readTree(done.body()).path("file").path("name").asString();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("파일 업로드 실패", e);
		}
	}

	/** 배치 잡 생성 — batches/NNN 이름을 돌려준다. */
	public String createBatch(String model, String inputFileName, String displayName) {
		String body = """
				{"batch":{"display_name":"%s","input_config":{"file_name":"%s"}}}"""
				.formatted(displayName, inputFileName);
		String res = send("/v1beta/models/" + model + ":batchGenerateContent", body);
		return om.readTree(res).path("name").asString();
	}

	/** 배치 잡 조회 — 응답 JSON 전체를 돌려준다(state·결과 파일 탐색은 호출자). */
	public String getBatch(String batchName) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/" + batchName))
					.header("x-goog-api-key", apiKey).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("배치 조회 실패 HTTP " + res.statusCode());
			}
			return res.body();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("배치 조회 실패", e);
		}
	}

	/** 결과 파일 다운로드(JSONL). */
	public String downloadFile(String fileName) {
		try {
			HttpRequest req = HttpRequest.newBuilder(
					URI.create(baseUrl + "/download/v1beta/" + fileName + ":download?alt=media"))
					.header("x-goog-api-key", apiKey).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("결과 다운로드 실패 HTTP " + res.statusCode());
			}
			return res.body();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("결과 다운로드 실패", e);
		}
	}
```

(필드 `om`은 이미 있음. `send`는 Task 2에서 만든 공통 POST.)

- [ ] **Step 3: `GeminiBackfillRunner`**

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentInsightPort.ContentInsight;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import com.celfit.analytics.llm.GeminiHttpApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 초기 백필 one-shot — 유료 키(GEMINI_API_KEY_PAID) Batch API 일회 실행 (2026-07-18 확정, ~$9).
 * submit: v_analysis_candidates ∩ v_analysis_baseline 중 미분석 전량 → JSONL 업로드 → 배치 생성 →
 *         사이드카(기준선 스냅샷) 저장. 캡션 단독(썸네일 미첨부 — 백필 시점 서명 URL 대부분 만료).
 * collect: 상태 확인 → 결과 다운로드 → 파싱·sanitize → ON CONFLICT DO NOTHING INSERT(재실행 멱등).
 * 실행: --spring.main.web-application-type=none --analytics.backfill-submit=true
 *   또는 --analytics.backfill-collect=batches/NNN
 */
public class GeminiBackfillRunner {

	private static final Logger log = LoggerFactory.getLogger(GeminiBackfillRunner.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final GeminiBatchApi api; // Step 5의 인터페이스 — 테스트 fake 용이
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final Path workDir;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiBackfillRunner(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			GeminiBatchApi api, AnalyticsSettings settings, BeautyTaxonomyLoader taxonomyLoader,
			Path workDir) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.api = api;
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
		this.workDir = workDir;
	}

	/** @return 배치 잡 이름 (collect 실행 시 그대로 넘긴다) */
	public String submit() {
		Set<String> analyzed = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses", String.class));
		List<Map<String, Object>> rows = raw.queryForList("""
				SELECT c.short_code, c.account_handle, c.content_type, c.caption,
				       c.views, c.likes, c.comments,
				       b.recent_reels_avg_views, b.rank_in_recent_reels, b.recent_reels_count,
				       b.recent_contents_count, b.recent12_avg_engagement_rate,
				       b.recent12_avg_like_count, b.recent12_avg_comment_count,
				       b.category_top_percentile, b.category_avg_views, b.category_sample_size
				FROM analytics.v_analysis_candidates c
				JOIN analytics.v_analysis_baseline b USING (short_code)
				ORDER BY c.short_code""");
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		String system = GeminiContentAnalyzer.instructions(taxonomyLoader.get());
		int count = 0;
		for (Map<String, Object> r : rows) {
			String shortCode = (String) r.get("short_code");
			if (analyzed.contains(shortCode)) {
				continue;
			}
			jsonl.append(om.writeValueAsString(requestLine(shortCode, r, system))).append('\n');
			sidecar.append(om.writeValueAsString(sidecarLine(shortCode, r))).append('\n');
			count++;
		}
		if (count == 0) {
			log.info("백필 대상 없음");
			return null;
		}
		Files.createDirectories(workDir);
		Files.writeString(workDir.resolve("backfill-input.jsonl"), jsonl.toString());
		Files.writeString(workDir.resolve("backfill-sidecar.jsonl"), sidecar.toString());
		String fileName = api.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8),
				"hypenow-backfill");
		String batchName = api.createBatch(settings.geminiModel(), fileName, "hypenow-backfill");
		log.info("백필 배치 제출 완료 — {}건, 배치: {} (collect는 --analytics.backfill-collect={})",
				count, batchName, batchName);
		return batchName;
	}

	/** JSONL 요청 라인 — key=short_code, request=GenerateContentRequest(snake_case, 문서 형식). */
	ObjectNode requestLine(String shortCode, Map<String, Object> r, String system) {
		Map<String, Object> baseline = new LinkedHashMap<>();
		baseline.put("recent_reels_avg_views", r.get("recent_reels_avg_views"));
		baseline.put("rank_in_recent_reels", r.get("rank_in_recent_reels"));
		baseline.put("recent_contents_count", r.get("recent_contents_count"));
		baseline.put("recent12_avg_engagement_rate", r.get("recent12_avg_engagement_rate"));
		baseline.put("recent12_avg_like_count", r.get("recent12_avg_like_count"));
		baseline.put("recent12_avg_comment_count", r.get("recent12_avg_comment_count"));
		baseline.put("category_top_percentile", r.get("category_top_percentile"));
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				baseline, Map.of());
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("system_instruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentAnalyzer.userText(content, false));
		ObjectNode gen = request.putObject("generation_config");
		gen.put("temperature", 0);
		gen.put("response_mime_type", "application/json");
		gen.set("response_schema", om.readTree(GeminiContentAnalyzer.RESPONSE_SCHEMA));
		gen.put("max_output_tokens", GeminiContentAnalyzer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/** 사이드카 라인 — 저장 시점에 프롬프트에 실었던 기준선 스냅샷을 그대로 복원하기 위한 기록. */
	ObjectNode sidecarLine(String shortCode, Map<String, Object> r) {
		ObjectNode line = om.createObjectNode();
		line.put("short_code", shortCode);
		for (String k : List.of("recent_reels_avg_views", "rank_in_recent_reels", "recent_reels_count",
				"recent_contents_count", "recent12_avg_engagement_rate", "recent12_avg_like_count",
				"recent12_avg_comment_count", "category_top_percentile", "category_avg_views",
				"category_sample_size", "caption")) {
			Object v = r.get(k);
			if (v == null) {
				line.putNull(k);
			} else {
				line.put(k, v.toString());
			}
		}
		return line;
	}

	/** @return 저장 건수. 배치 미완료면 -1 (상태 로그만). */
	public int collect(String batchName) {
		JsonNode batch = om.readTree(api.getBatch(batchName));
		String state = firstText(batch, "metadata", "state");
		if (state == null) {
			state = batch.path("state").asString();
		}
		if (!"JOB_STATE_SUCCEEDED".equals(state) && !"BATCH_STATE_SUCCEEDED".equals(state)) {
			log.info("배치 미완료 — state={} (전체: {})", state, batch.toString());
			return -1;
		}
		String resultFile = firstNonNull(
				firstText(batch, "metadata", "output", "responsesFile"),
				firstText(batch, "response", "responsesFile"),
				firstText(batch, "dest", "fileName"),
				firstText(batch, "metadata", "dest", "fileName"));
		if (resultFile == null) {
			throw new IllegalStateException("결과 파일 이름을 찾지 못함 — 배치 응답: " + batch);
		}
		Map<String, Map<String, String>> sidecar = readSidecar();
		String model = settings.geminiModel();
		var taxonomy = taxonomyLoader.get();
		int saved = 0;
		int failed = 0;
		for (String line : api.downloadFile(resultFile).split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			try {
				JsonNode node = om.readTree(line);
				String shortCode = node.path("key").asString();
				JsonNode text = node.path("response").path("candidates").path(0)
						.path("content").path("parts").path(0).path("text");
				if (shortCode.isEmpty() || text.isMissingNode()) {
					failed++;
					log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
					continue;
				}
				ContentInsight insight = GeminiContentAnalyzer.parse(om, text.asString(), taxonomy);
				if (insight.synthesis().aiContentSummary() == null
						|| insight.synthesis().aiContentSummary().isBlank()) {
					failed++;
					continue;
				}
				Map<String, String> base = sidecar.get(shortCode);
				if (base == null) {
					failed++;
					log.warn("사이드카에 없는 key: {}", shortCode);
					continue;
				}
				boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
				ContentAnalysisWriter.insert(analysis, om, shortCode, model, baselineOf(base),
						hasCaption ? insight.attributes() : null, insight.synthesis(), true);
				saved++;
			} catch (Exception e) {
				failed++;
				log.warn("결과 라인 저장 실패: {}", abbreviate(line), e);
			}
		}
		log.info("백필 저장 완료 — {}건 저장, {}건 실패(잔여는 일상 파이프라인이 흡수)", saved, failed);
		return saved;
	}

	private Map<String, Map<String, String>> readSidecar() {
		Path path = workDir.resolve("backfill-sidecar.jsonl");
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (String line : Files.readString(path).split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = om.readTree(line);
			Map<String, String> vals = new LinkedHashMap<>();
			node.properties().forEach(e -> vals.put(e.getKey(),
					e.getValue().isNull() ? null : e.getValue().asString()));
			out.put(node.path("short_code").asString(), vals);
		}
		return out;
	}

	private static Baseline baselineOf(Map<String, String> b) {
		return new Baseline(longOrNull(b.get("recent_reels_avg_views")),
				intOrNull(b.get("rank_in_recent_reels")), intOrNull(b.get("recent_reels_count")),
				intOrNull(b.get("recent_contents_count")), decimalOrNull(b.get("recent12_avg_engagement_rate")),
				longOrNull(b.get("recent12_avg_like_count")), longOrNull(b.get("recent12_avg_comment_count")),
				intOrNull(b.get("category_top_percentile")), longOrNull(b.get("category_avg_views")),
				longOrNull(b.get("category_sample_size")));
	}

	private static Long longOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v).longValue();
	}

	private static Integer intOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v).intValue();
	}

	private static java.math.BigDecimal decimalOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v);
	}

	private static Long numberOf(Object v) {
		return v == null ? null : ((Number) v).longValue();
	}

	private static String firstText(JsonNode root, String... path) {
		JsonNode n = root;
		for (String p : path) {
			n = n.path(p);
		}
		return n.isMissingNode() || n.isNull() ? null : n.asString();
	}

	private static String firstNonNull(String... vals) {
		for (String v : vals) {
			if (v != null && !v.isEmpty()) {
				return v;
			}
		}
		return null;
	}

	private static String abbreviate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
```

- [ ] **Step 4: `JobConfig` 러너 배선** (one-shot CLI 컨벤션 — 미러 `mirror-on-startup`과 동형):

```java
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.backfill-submit:false} or '${analytics.backfill-collect:}' != ''")
	public org.springframework.boot.ApplicationRunner geminiBackfillRunner(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings, com.celfit.analytics.llm.BeautyTaxonomyLoader taxonomyLoader,
			@Value("${analytics.backfill-submit:false}") boolean submit,
			@Value("${analytics.backfill-collect:}") String collectBatch,
			@Value("${analytics.backfill-dir:./backfill}") String dir) {
		return args -> {
			GeminiBackfillRunner runner = new GeminiBackfillRunner(rawJdbcTemplate, analysisDataSource,
					com.celfit.analytics.llm.GeminiHttpApi.fromEnvPaid(), settings, taxonomyLoader,
					java.nio.file.Path.of(dir));
			if (submit) {
				runner.submit();
			} else {
				runner.collect(collectBatch);
			}
		};
	}
```

- [ ] **Step 5: 테스트** — Testcontainers로 collect 경로(결과 JSONL → INSERT 멱등)와 requestLine 구조를 고정. `GeminiHttpApi`를 상속/스텁할 수 없으므로(final) — **테스트 편의를 위해 `GeminiHttpApi`의 final 제거** 후 서브클래스 스텁, 또는 배치 4메서드를 담은 소형 인터페이스 추출 중 후자 권장:

`GeminiBatchApi` 인터페이스(구현은 `GeminiHttpApi`):

```java
package com.celfit.analytics.llm;

/** Batch API 표면 — 백필 러너가 보는 최소 계약 (테스트 fake 용이). */
public interface GeminiBatchApi {
	String uploadFile(byte[] jsonl, String displayName);
	String createBatch(String model, String inputFileName, String displayName);
	String getBatch(String batchName);
	String downloadFile(String fileName);
}
```

`GeminiHttpApi implements GeminiApi, GeminiBatchApi`. 러너 필드 타입을 `GeminiBatchApi`로.

테스트 골자 (`GeminiBackfillRunnerTest`, `ContentAnalysisJobTest`의 컨테이너·`TestDb.resetAndMigrate` 패턴 재사용):

```java
	// 픽스처: analytics 스키마에 v_analysis_candidates·v_analysis_baseline 뷰(문서화된 컬럼 계약대로) 생성
	// fake GeminiBatchApi: uploadFile→"files/f1", createBatch→"batches/b1",
	//   getBatch→ {"metadata":{"state":"JOB_STATE_SUCCEEDED","output":{"responsesFile":"files/r1"}}},
	//   downloadFile→ 유효 응답 2줄(그중 1줄은 이미 분석된 short_code — DO NOTHING 확인)
	@Test void submit은_미분석_후보로_JSONL과_사이드카를_만들고_배치를_생성한다() { ... }
	@Test void collect는_결과를_sanitize해_저장하고_재실행에_멱등이다() { ... }
	@Test void 캡션_없는_행은_속성을_폐기하고_종합만_저장한다() { ... }
```

각 케이스에서 JSONL 라인의 `key`/`request.generation_config.response_schema` 존재, `content_analyses` 행 수·컬럼 값, ON CONFLICT 멱등(2회 collect에도 행 수 동일)을 어서션.

- [ ] **Step 6: 테스트 실행**

Run: `./gradlew :analytics:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A analytics/src
git commit -m "feat(analytics): 초기 백필 one-shot — 유료 키 Gemini Batch(submit/collect), INSERT 단일 원천 추출"
```

---

### Task 9: 문서 갱신 + 전체 검증 + PR

**Files:**
- Modify: `ARCHITECTURE.md` (§5 트랙 표 행 추가, §6 비용 행 추가, §7 결정 기록, §8 "LLM 모델" 미결 해소)
- Test: 전체

- [ ] **Step 1: ARCHITECTURE.md 갱신**

§5 표에 행 추가:

```markdown
| L | LLM Gemini 전환 | 전 분석 축(판정·속성+종합 통합 1콜·카피)을 gemini-3.1-flash-lite로 — 프로바이더 선택 `analytics.llm-provider`(기본 gemini), 무료 키 페이싱(15RPM·일 예산은 batch-limit)+한도 이월, 백필은 유료 키 Batch one-shot(submit/collect). 크롤러 판정은 `crawler.beauty.judge`(기본 gemini) | F, B3, C2 | ✅ (백필 실행은 신 스키마 뷰 머지 대기) |
```

§6에 비용 행 추가:

```markdown
- LLM 실측 비용(07-18 골드셋): 전 축 gemini-3.1-flash-lite 무료 티어 $0 운영(분당 15콜·일 1,500콜 예산),
  초기 백필 2만 건은 유료 프로젝트 Batch API ~$9. Anthropic 단가는 롤백 참고치로 유지.
```

§7 맨 위에 결정 행 추가:

```markdown
| 2026-07-18 | **LLM 스택 Gemini 3.1 Flash-Lite 전환** — 골드셋 40건 실측(Opus 기준 mainCategory 90%·adType 98%·subCat Jaccard 0.62·브랜드 88%, Haiku 4.5보다 우수·5.5배 저렴)으로 전 분석 축 통일. ②속성+③종합은 통합 1콜(ContentInsightPort — Anthropic은 2콜 컴포지트 롤백 경로), 문구 프롬프트에 절제 규칙(LlmGuard — 표본 3건 미만 단정 금지·조언 금지·수치 인용) 필수. 이원 운영: 일상=무료 키 동기+페이싱(RPM 15, 일 예산은 batch-limit, 429/한도 소진은 이월) / 백필 2만 건=유료 키 Batch one-shot(~$9). 판정은 크롤러 BeautyJudge 포트 뒤 Gemini 어댑터(팀 프롬프트·파서 재사용). 댓글 분류는 MVP 휴면이라 Anthropic 유지. 구모델(2.5)은 신규 키 404 — 3.1이 유일 | [plans/2026-07-18-gemini-llm-stack.md](docs/superpowers/plans/2026-07-18-gemini-llm-stack.md) |
```

§8 "LLM 모델" 행을 해소로:

```markdown
| ~~LLM 모델~~ | 해소(07-18) — 골드셋 실측으로 전 축 gemini-3.1-flash-lite 확정(§7), Anthropic은 app_setting 롤백 경로 |
```

- [ ] **Step 2: 전체 테스트**

Run: `./gradlew test`
Expected: PASS (crawler·analytics·was·contract 전 모듈)

- [ ] **Step 3: 커밋 + PR**

```bash
git add ARCHITECTURE.md docs/superpowers/plans/2026-07-18-gemini-llm-stack.md
git commit -m "docs: LLM Gemini 전환 결정·트랙 기록 (ARCHITECTURE §5·§6·§7·§8)"
git push -u origin feat/gemini-llm-stack
gh pr create --base develop --title "feat: LLM 분석 스택 Gemini 3.1 Flash-Lite 전환" --body "..."
```

PR 본문에 명시: 백필 러너 실행은 `feat/analytics-views-new-schema` 머지 + `GEMINI_API_KEY_PAID` 등록 후 / 운영 app_setting SQL(계획 서두) 적용 필요.

---

## 남는 것 (이 계획 밖)

- **백필 실제 실행** — 신 스키마 뷰 머지 후: `./gradlew :analytics:bootRun --args='--spring.main.web-application-type=none --analytics.backfill-submit=true'` → 배치 완료 후 `--analytics.backfill-collect=batches/NNN`. 실행 전 `GEMINI_API_KEY_PAID` 등록.
- **일상 파이프라인 hype 상위 우선 정렬** — `v_analysis_candidates` 소비 전환과 함께(현 잡은 기존 `v_analysis_baseline` 최신순 유지 — 뷰 인터페이스가 보존되므로 동작 무변경). 신 스키마 머지 후 후속 세션에서 대상 선정 쿼리를 후보 뷰 + `analytics.hype_score()` 정렬로 전환.
- **성장 시 유료 전환** — 일 1,500콜 초과하면 `GEMINI_API_KEY`를 유료 프로젝트 키 값으로 교체(코드 무변경 — 07-18 결정 7).
