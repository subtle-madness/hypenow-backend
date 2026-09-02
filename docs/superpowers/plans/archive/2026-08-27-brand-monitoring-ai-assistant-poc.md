# 브랜드 모니터링 AI 어시스턴트 PoC 구현 계획

> 상태: ✅ 구현됨
> 작성: 2026-08-27
> 설계 정본: [docs/superpowers/specs/2026-08-27-brand-monitoring-ai-assistant-poc-design.md](../specs/2026-08-27-brand-monitoring-ai-assistant-poc-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** was 모듈에 읽기 전용 툴 6종을 가진 툴 콜링 에이전트를 붙여, 사용자가 자기 브랜드 모니터링 데이터에 자유 질의하고 답을 받는 `POST /v1/brand-monitoring/ai/chat`을 열되 모든 질문·답변·툴 시퀀스를 `app.ai_chat_logs`에 남긴다.

**Architecture:** 신규 패키지 `com.celfit.was.v1.brandmonitoring.ai` 안에 전송 포트(`ChatTransport`) → 요청 조립·응답 파싱(`GeminiChatClient`) → 툴 실행기(`BrandAiToolbox`) → 에이전트 루프(`BrandAiAgent`) → 컨트롤러(`V1BrandAiChatController`) 5층을 쌓는다. 데이터 접근은 기존 `BrandReadRepository`(monitoring 읽기 전용 풀)와 `BrandLinkRepository`(app.brand_monitorings) 재사용이고 새 조회 SQL은 로그 테이블 것뿐이라 시스템 경계(was는 monitoring 읽기 전용, 쓰기는 app 스키마만)는 무변경이다. LLM 전송은 `common-llm`의 `VertexHttpTransport.post(path, jsonBody)`를 그대로 쓴다 - 이 메서드가 이미 임의 JSON 본문을 통과시키므로 function calling을 위해 common-llm을 고칠 필요가 없다(설계 §3의 "소폭 확장" 조건은 발생하지 않음).

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Jackson 3(`tools.jackson.*`), Gradle 멀티모듈(`:was` + `:common-llm`), Vertex AI Gemini(`generateContent` + `functionDeclarations`), JUnit 5 + AssertJ + Mockito, Testcontainers PostgreSQL.

---

## 사전 확인 (구현 시작 전 1회)

- [ ] 셸 환경 고정: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (미설정 시 `:was:test`가 대량 실패하며 코드 결함으로 오진하기 쉽다 - CLAUDE.md 함정 항목).
- [ ] colima 자원 확인: `colima status` 로 8 CPU / 12 GiB 이상인지 확인. 미달이면 `colima stop && colima start --cpu 8 --memory 12`.
- [ ] 기준선 확인: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 통과하는지. 기대: BUILD SUCCESSFUL. 실패하면 이 계획을 시작하기 전에 원인을 먼저 해소한다.

---

## Task 1: 질문 로그 테이블과 리포지토리

설계 §6. 이 테이블이 PoC의 진짜 산출물이고, 일일 상한 판정(Task 7)도 이 테이블을 센다.

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260827110158__ai_chat_logs.sql`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogEntry.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogRepositoryIntegrationTest.java`

### 스텝

- [ ] **1-1. 마이그레이션 번호를 확인한다.** `ls was/src/main/resources/db/migration/app/ | sort | tail -3` 을 실행해 현재 최대 버전이 `V20260819050953__brand_hashtag_tags.sql` 인지 확인한다. 이 계획이 쓰는 `V20260827110158`은 2026-08-27 11:01:58 UTC에 `date -u +%Y%m%d%H%M%S`로 채번한 값이라 그보다 크다. **더 큰 app 마이그레이션이 이미 들어와 있으면** `date -u +%Y%m%d%H%M%S`로 새로 채번해 파일명을 바꾸고, 이 계획의 뒤 스텝에 나오는 파일명도 함께 바꾼다.

- [ ] **1-2. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogRepositoryIntegrationTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * app.ai_chat_logs 적재·집계 통합 검증(설계 §6) - tool_calls jsonb 왕복과 일일 상한 판정용
 * countSince가 유저 스코프로 갈리는지가 핵심이다.
 */
class AiChatLogRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	AiChatLogRepository repository;
	long userId;
	long otherUserId;

	@BeforeEach
	void setUp() {
		repository = new AiChatLogRepository(jdbcClient, objectMapper);
		jdbcClient.sql("TRUNCATE app.ai_chat_logs RESTART IDENTITY CASCADE").update();
		userId = insertUser();
		otherUserId = insertUser();
	}

	private long insertUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""")
				.param("email", UUID.randomUUID() + "@example.com")
				.query(Long.class)
				.single();
	}

	@Test
	void 툴_시퀀스와_토큰이_담긴_로그가_그대로_적재된다() {
		AiChatLogEntry entry = new AiChatLogEntry(userId, 100L, "지난주 반응 좋은 게시물 알려줘", "3건이 있어요.",
				List.of(new AiChatLogEntry.ToolCallLog("list_posts",
						objectMapper.createObjectNode().put("brandId", 100).put("days", 7), 3)),
				1200, 340, 4200L, AiChatLogEntry.OUTCOME_OK);

		repository.insert(entry);

		String toolCalls = jdbcClient.sql("SELECT tool_calls::text FROM app.ai_chat_logs WHERE user_id = :id")
				.param("id", userId).query(String.class).single();
		assertThat(toolCalls).contains("list_posts").contains("\"rows\": 3");
		assertThat(jdbcClient.sql("SELECT prompt_tokens FROM app.ai_chat_logs WHERE user_id = :id")
				.param("id", userId).query(Integer.class).single()).isEqualTo(1200);
	}

	@Test
	void countSince는_해당_유저의_기간_내_행만_센다() {
		OffsetDateTime since = OffsetDateTime.now().minusHours(1);
		repository.insert(logOf(userId));
		repository.insert(logOf(userId));
		repository.insert(logOf(otherUserId));

		assertThat(repository.countSince(userId, since)).isEqualTo(2);
		assertThat(repository.countSince(otherUserId, since)).isEqualTo(1);
		assertThat(repository.countSince(userId, OffsetDateTime.now().plusMinutes(1))).isZero();
	}

	@Test
	void 마이그레이션이_일일_상한_기준값_30을_시드한다() {
		assertThat(jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = 'ai.chat.daily-limit'")
				.query(String.class).optional()).contains("30");
	}

	private AiChatLogEntry logOf(long id) {
		return new AiChatLogEntry(id, null, "질문", "답변", List.of(), 10, 5, 100L,
				AiChatLogEntry.OUTCOME_OK);
	}
}
```

> 이 클래스는 `app.app_setting`을 수정하지 않으므로 시드 검증이 여기 있어야 참이다. `AiChatQuotaIntegrationTest`는 상한 값을 계속 덮어쓰기 때문에 같은 검증을 그쪽에 두면 자기 setUp을 확인하는 무의미한 테스트가 된다.

- [ ] **1-3. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.AiChatLogRepositoryIntegrationTest"` - 기대: 컴파일 실패(`AiChatLogRepository`·`AiChatLogEntry` 없음).

- [ ] **1-4. 마이그레이션을 작성한다.** `was/src/main/resources/db/migration/app/V20260827110158__ai_chat_logs.sql`

```sql
-- 브랜드 모니터링 AI 어시스턴트 PoC 질문 로그(2026-08-27 설계 §6) - append-only.
--
-- PoC가 검증하려는 "사용자가 무엇을 묻는가"의 정본이다. 질문만이 아니라 답변과 툴 시퀀스까지
-- 남기는 이유: "이 질문에 모델이 어떤 툴을 골랐고 몇 건을 받았나"가 다음 툴 백로그의 근거라서다.
-- 일일 질문 상한(설계 §7)도 별도 카운터 테이블 없이 이 테이블을 센다 - 로그가 곧 원장이다.
--
-- tool_calls는 [{"name": "list_posts", "args": {...}, "rows": 3}] 형태 jsonb 배열
-- (배열 저장은 text[] 대신 jsonb - CLAUDE.md 컨벤션).
CREATE TABLE app.ai_chat_logs (
    id            bigserial   PRIMARY KEY,
    user_id       bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    -- 모델이 실제로 조회한 브랜드(툴 인자에서 관측). 브랜드가 특정되지 않은 질문은 NULL.
    -- monitoring brand_account.id 논리 참조 - 크로스 DB FK 금지(app.brand_monitorings와 동일 규칙).
    brand_id      bigint,
    question      text        NOT NULL,
    -- LLM 실패로 답을 못 만든 경우 NULL. 실패한 질문도 수요 신호라 행 자체는 남긴다.
    answer        text,
    tool_calls    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    prompt_tokens integer     NOT NULL DEFAULT 0,
    output_tokens integer     NOT NULL DEFAULT 0,
    elapsed_ms    integer     NOT NULL DEFAULT 0,
    -- ok | tool_cap | llm_failed. 값 공간은 AiChatLogEntry의 OUTCOME_* 상수가 정본.
    outcome       text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 일일 상한 판정(user_id + created_at 범위 count)이 유일한 뜨거운 조회 경로다.
CREATE INDEX ai_chat_logs_user_created_idx ON app.ai_chat_logs (user_id, created_at DESC);

-- 유저당 일일 질문 상한 기준값(설계 §7). 기준값은 마이그레이션으로 시드하고 런타임 조정만
-- app_setting UPDATE로 한다(07-20 수동 등록분 유실 사고 후 확립된 규칙).
INSERT INTO app.app_setting (key, value) VALUES ('ai.chat.daily-limit', '30')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **1-5. `AiChatLogEntry`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogEntry.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * app.ai_chat_logs 1행(설계 §6) - 질문 1건당 1행. 에이전트 루프가 끝난 뒤 컨트롤러가 조립한다.
 *
 * @param brandId 모델이 툴 인자로 실제 조회한 브랜드. 특정되지 않았으면 null.
 * @param answer  LLM 실패 시 null - 실패한 질문도 수요 신호라 행은 남긴다.
 */
public record AiChatLogEntry(long userId, Long brandId, String question, String answer,
		List<ToolCallLog> toolCalls, int promptTokens, int outputTokens, long elapsedMillis,
		String outcome) {

	/** 정상 답변 완료. */
	public static final String OUTCOME_OK = "ok";
	/** 툴 호출 상한(8회)에 걸려 그때까지의 정보로 답변을 강제한 경우. */
	public static final String OUTCOME_TOOL_CAP = "tool_cap";
	/** LLM 전송 실패(타임아웃·쿼터·5xx) - answer는 null. */
	public static final String OUTCOME_LLM_FAILED = "llm_failed";

	/**
	 * 툴 호출 1건 기록 - args는 모델이 넘긴 원본 인자 노드, rows는 툴이 돌려준 행 수.
	 * JsonNode를 그대로 담아 Jackson이 jsonb 문자열로 직렬화하게 한다(문자열로 들고 있으면
	 * 이중 인코딩된다).
	 */
	public record ToolCallLog(String name, JsonNode args, int rows) {
	}
}
```

- [ ] **1-6. `AiChatLogRepository`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatLogRepository.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 어시스턴트 질문 로그 적재·집계(설계 §6). append-only - UPDATE·DELETE 경로를 두지 않는다.
 *
 * <p>적재는 fire-and-forget이다(SignupEventRecorder 선례): 이미 좋은 답을 만든 요청을 로그 실패로
 * 500으로 만들 이유가 없다. 대신 이 테이블이 일일 상한의 원장이기도 해서, 적재가 실패하면 그 요청은
 * 상한 계산에서 빠진다 - PoC 규모에서 감수하는 트레이드오프이며 warn 로그로 관측 가능하게 둔다.
 */
@Repository
public class AiChatLogRepository {

	private static final Logger log = LoggerFactory.getLogger(AiChatLogRepository.class);

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public AiChatLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	public void insert(AiChatLogEntry entry) {
		try {
			jdbcClient.sql("""
					INSERT INTO app.ai_chat_logs (user_id, brand_id, question, answer, tool_calls,
					                              prompt_tokens, output_tokens, elapsed_ms, outcome)
					VALUES (:userId, :brandId, :question, :answer, CAST(:toolCalls AS jsonb),
					        :promptTokens, :outputTokens, :elapsedMs, :outcome)
					""")
					.param("userId", entry.userId())
					.param("brandId", entry.brandId())
					.param("question", entry.question())
					.param("answer", entry.answer())
					.param("toolCalls", objectMapper.writeValueAsString(entry.toolCalls()))
					.param("promptTokens", entry.promptTokens())
					.param("outputTokens", entry.outputTokens())
					.param("elapsedMs", (int) Math.min(entry.elapsedMillis(), Integer.MAX_VALUE))
					.param("outcome", entry.outcome())
					.update();
		} catch (RuntimeException e) {
			log.warn("AI 질문 로그 적재 실패(무시) - userId={}, outcome={}", entry.userId(), entry.outcome(), e);
		}
	}

	/** since 이후 이 유저가 던진 질문 수 - 일일 상한 판정 전용(설계 §7). */
	public int countSince(long userId, OffsetDateTime since) {
		return jdbcClient.sql("""
				SELECT count(*) FROM app.ai_chat_logs
				WHERE user_id = :userId AND created_at >= :since
				""")
				.param("userId", userId)
				.param("since", since)
				.query(Integer.class)
				.single();
	}
}
```

- [ ] **1-7. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.AiChatLogRepositoryIntegrationTest"` - 기대: BUILD SUCCESSFUL, 테스트 3건 통과.

- [ ] **1-8. 커밋한다.** `git add -A && git commit -m "feat(was): AI 어시스턴트 질문 로그 테이블·리포지토리 추가"`

---

## Task 2: common-llm 의존과 Vertex 전송 포트

설계 §3. `VertexHttpTransport.post(path, jsonBody)`는 임의 JSON 본문을 그대로 통과시키므로 function calling을 위해 common-llm을 고칠 필요가 없다. was 쪽에는 "본문만 넘기면 generateContent를 때려 준다"는 좁은 seam만 만든다.

**Files:**
- Modify: `was/build.gradle`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/ChatTransport.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/VertexChatTransport.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/VertexChatTransportTest.java`

### 스텝

- [ ] **2-1. `was/build.gradle`에 common-llm 의존을 추가한다.** `dependencies { ... }` 블록 첫 줄 `implementation project(':contract-analysis')` 바로 아래에 다음을 넣는다.

```groovy
	// AI 어시스턴트 LLM 전송(2026-08-27 설계 §3) - Vertex HTTP·SA 토큰·재시도만 재사용한다.
	// 프롬프트·툴 정의·에이전트 루프는 was 소관이라 common-llm에 반입하지 않는다(monitoring과 동일 원칙).
	implementation project(':common-llm')
```

- [ ] **2-2. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/VertexChatTransportTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.common.llm.VertexHttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Vertex generateContent 경로 조립 검증 - 실 소켓(HttpServer)을 띄워 common-llm 전송까지 통과시킨다
 * (common-llm VertexHttpTransportTest·monitoring GeminiHttpTransportTest와 같은 관용구:
 * Spring 컨텍스트 없이 생성자 직접 주입).
 */
class VertexChatTransportTest {

	private HttpServer server;
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private int port;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().getPath());
			try (InputStream in = exchange.getRequestBody()) {
				bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			byte[] out = "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void 프로젝트_로케이션_모델로_generateContent_경로를_만든다() {
		VertexHttpTransport http = new VertexHttpTransport(
				() -> "test-token", "http://localhost:" + port, 10, 1, 200);
		VertexChatTransport transport =
				new VertexChatTransport(http, "hypenow-prod", "global", "gemini-2.5-flash");

		String response = transport.post("{\"contents\":[]}");

		assertThat(response).isEqualTo("{\"candidates\":[]}");
		assertThat(paths).containsExactly(
				"/v1/projects/hypenow-prod/locations/global/publishers/google/models/gemini-2.5-flash:generateContent");
		assertThat(bodies).containsExactly("{\"contents\":[]}");
	}
}
```

- [ ] **2-3. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.VertexChatTransportTest"` - 기대: 컴파일 실패(`ChatTransport`·`VertexChatTransport` 없음).

- [ ] **2-4. `ChatTransport`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/ChatTransport.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 채팅 LLM 전송 seam(설계 §9) - 테스트에서 스크립트된 응답 fake로 갈아끼우는 지점이다.
 * 경로가 인자에 없는 이유: 이 표면이 쓰는 액션은 generateContent 하나뿐이라 경로 조립은 구현체 몫이다
 * (monitoring GeminiHttp가 path를 받는 것과 의도적으로 다르다 - 거긴 AI Studio 경로 호환이 목적이었다).
 */
@FunctionalInterface
public interface ChatTransport {

	/** 요청 본문 JSON을 보내고 응답 본문 JSON을 그대로 돌려준다. 실패는 예외로 전파된다. */
	String post(String jsonBody);
}
```

- [ ] **2-5. `VertexChatTransport`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/VertexChatTransport.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.VertexHttpTransport;

/**
 * {@link ChatTransport}의 Vertex 구현 - generateContent 경로를 조립해 common-llm 전송에 위임한다
 * (monitoring {@code VertexGeminiHttp}와 같은 역할이되, 이쪽은 AI Studio 경로 변환이 필요 없어
 * 처음부터 Vertex 경로를 만든다).
 *
 * <p>common-llm은 프롬프트·툴 정의를 모르는 순수 전송 계층이고, function calling 페이로드는
 * 그냥 JSON 본문의 일부라 이 경로에 common-llm 확장이 필요 없다(설계 §3 확인 완료, 08-27).
 */
public final class VertexChatTransport implements ChatTransport {

	private final VertexHttpTransport transport;
	private final String path;

	public VertexChatTransport(VertexHttpTransport transport, String project, String location, String model) {
		this.transport = transport;
		this.path = "/v1/projects/" + project + "/locations/" + location
				+ "/publishers/google/models/" + model + ":generateContent";
	}

	@Override
	public String post(String jsonBody) {
		return transport.post(path, jsonBody);
	}
}
```

- [ ] **2-6. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.VertexChatTransportTest"` - 기대: BUILD SUCCESSFUL, 테스트 1건 통과.

- [ ] **2-7. 커밋한다.** `git add -A && git commit -m "feat(was): common-llm 의존 추가·Vertex 채팅 전송 포트 도입"`

---

## Task 3: Gemini 요청 조립과 응답 파싱

설계 §3의 에이전트 루프가 딛고 설 계층. 여기까지가 "LLM과 말하는 법"이고, 다음 태스크부터가 "무엇을 말할 것인가"다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiToolSpec.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/LlmTurn.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/GeminiChatClient.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/GeminiChatClientTest.java`

### 스텝

- [ ] **3-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/GeminiChatClientTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Vertex generateContent 요청 조립·응답 파싱 검증 - 전송은 스크립트 fake로 대체한다(설계 §9). */
class GeminiChatClientTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void 시스템프롬프트와_툴선언을_요청_본문에_싣는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"안녕하세요\"}]}}]}";
		}, om);

		client.generate("너는 분석 어시스턴트다", List.of(client.userContent("안녕")),
				List.of(new AiToolSpec("list_brands", "브랜드 목록", null),
						new AiToolSpec("get_post", "게시물 상세",
								"{\"type\":\"object\",\"properties\":{\"shortCode\":{\"type\":\"string\"}}}")));

		JsonNode body = om.readTree(sent.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.isEqualTo("너는 분석 어시스턴트다");
		assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
		JsonNode declarations = body.path("tools").path(0).path("functionDeclarations");
		assertThat(declarations.size()).isEqualTo(2);
		assertThat(declarations.path(0).has("parameters")).isFalse();
		assertThat(declarations.path(1).path("parameters").path("properties").has("shortCode")).isTrue();
	}

	@Test
	void 툴이_비면_tools_필드를_아예_싣지_않는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}";
		}, om);

		client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(om.readTree(sent.get(0)).has("tools")).isFalse();
	}

	@Test
	void 함수호출_응답을_ToolCall로_파싱한다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[{"content":{"role":"model","parts":[
				  {"functionCall":{"name":"list_posts","args":{"brandId":7,"days":30}}}]}}],
				 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":15}}
				""", om);

		LlmTurn turn = client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(turn.text()).isEmpty();
		assertThat(turn.toolCalls()).hasSize(1);
		assertThat(turn.toolCalls().get(0).name()).isEqualTo("list_posts");
		assertThat(turn.toolCalls().get(0).args().path("brandId").asInt()).isEqualTo(7);
		assertThat(turn.promptTokens()).isEqualTo(120);
		assertThat(turn.outputTokens()).isEqualTo(15);
	}

	@Test
	void 텍스트_응답은_파트를_이어붙인다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[{"content":{"parts":[{"text":"앞"},{"text":"뒤"}]}}]}
				""", om);

		assertThat(client.generate("시스템", List.of(client.userContent("질문")), List.of()).text())
				.isEqualTo("앞뒤");
	}

	@Test
	void 툴_결과_컨텐츠는_functionResponse_파트로_조립된다() {
		GeminiChatClient client = new GeminiChatClient(
				body -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}", om);

		JsonNode content = client.toolResultContent(
				List.of(new GeminiChatClient.ToolResponse("get_post", "{\"shortCode\":\"ABC\"}")));

		assertThat(content.path("role").asString()).isEqualTo("user");
		JsonNode fr = content.path("parts").path(0).path("functionResponse");
		assertThat(fr.path("name").asString()).isEqualTo("get_post");
		assertThat(fr.path("response").path("result").path("shortCode").asString()).isEqualTo("ABC");
	}
}
```

- [ ] **3-2. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.GeminiChatClientTest"` - 기대: 컴파일 실패.

- [ ] **3-3. `AiToolSpec`을 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiToolSpec.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

/**
 * function calling 툴 선언 1건(설계 §4).
 *
 * @param parametersJson OpenAPI 스키마 부분집합 JSON 문자열. 인자가 없는 툴은 null - Gemini는
 *                       properties가 빈 object 스키마를 거부하는 버전이 있어 아예 필드를 생략한다.
 */
public record AiToolSpec(String name, String description, String parametersJson) {
}
```

- [ ] **3-4. `LlmTurn`을 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/LlmTurn.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * LLM 1턴의 응답 - 텍스트와 툴 호출은 배타적이지 않다(Gemini가 둘을 같은 파트 배열에 섞어 보낼 수
 * 있다). 에이전트 루프는 toolCalls가 비었을 때만 종료한다.
 */
public record LlmTurn(String text, List<ToolCall> toolCalls, int promptTokens, int outputTokens) {

	/** 모델이 요청한 툴 호출 1건. args는 항상 object 노드(빈 object 포함). */
	public record ToolCall(String name, JsonNode args) {
	}
}
```

- [ ] **3-5. `GeminiChatClient`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/GeminiChatClient.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Vertex Gemini generateContent 요청 조립·응답 파싱(설계 §3). 프롬프트 내용·툴 목록은 호출자
 * ({@link BrandAiAgent})가 정하고, 이 클래스는 "Gemini가 알아듣는 JSON 형태"만 안다.
 *
 * <p>contents 조립 헬퍼를 여기 둔 이유: 모델 턴(functionCall)과 툴 결과 턴(functionResponse)의
 * role 규약이 Vertex 계약의 일부라 루프 쪽에 흩어지면 안 된다. functionResponse는 role="user"로
 * 보낸다(Vertex REST 계약).
 */
public class GeminiChatClient {

	private static final double TEMPERATURE = 0.2;
	private static final int MAX_OUTPUT_TOKENS = 2048;

	private final ChatTransport transport;
	private final ObjectMapper objectMapper;

	public GeminiChatClient(ChatTransport transport, ObjectMapper objectMapper) {
		this.transport = transport;
		this.objectMapper = objectMapper;
	}

	/** tools가 비면 tools 필드를 싣지 않는다 - 툴 호출 상한 도달 시 "답변만 하라"를 강제하는 수단이다. */
	public LlmTurn generate(String systemPrompt, List<JsonNode> contents, List<AiToolSpec> tools) {
		ObjectNode body = objectMapper.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
		ArrayNode contentsNode = body.putArray("contents");
		contents.forEach(contentsNode::add);
		if (!tools.isEmpty()) {
			ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
			for (AiToolSpec spec : tools) {
				ObjectNode declaration = declarations.addObject();
				declaration.put("name", spec.name());
				declaration.put("description", spec.description());
				if (spec.parametersJson() != null) {
					declaration.set("parameters", objectMapper.readTree(spec.parametersJson()));
				}
			}
		}
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("temperature", TEMPERATURE);
		generation.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
		return parse(transport.post(body.toString()));
	}

	public JsonNode userContent(String text) {
		return textContent("user", text);
	}

	public JsonNode modelContent(String text) {
		return textContent("model", text);
	}

	/** 모델이 요청한 툴 호출을 대화 이력에 그대로 되돌려 넣는다 - 없으면 다음 턴에서 문맥이 끊긴다. */
	public JsonNode modelToolCallContent(List<LlmTurn.ToolCall> calls) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", "model");
		ArrayNode parts = content.putArray("parts");
		for (LlmTurn.ToolCall call : calls) {
			ObjectNode functionCall = parts.addObject().putObject("functionCall");
			functionCall.put("name", call.name());
			functionCall.set("args", call.args());
		}
		return content;
	}

	/** 툴 실행 결과 되먹임. payloadJson은 임의 JSON이며 {"result": ...}로 감싸 보낸다(Vertex 계약). */
	public JsonNode toolResultContent(List<ToolResponse> responses) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", "user");
		ArrayNode parts = content.putArray("parts");
		for (ToolResponse response : responses) {
			ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
			functionResponse.put("name", response.name());
			functionResponse.putObject("response").set("result", objectMapper.readTree(response.payloadJson()));
		}
		return content;
	}

	private JsonNode textContent(String role, String text) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", role);
		content.putArray("parts").addObject().put("text", text);
		return content;
	}

	private LlmTurn parse(String raw) {
		JsonNode root = objectMapper.readTree(raw);
		JsonNode usage = root.path("usageMetadata");
		JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
		StringBuilder text = new StringBuilder();
		List<LlmTurn.ToolCall> calls = new ArrayList<>();
		for (JsonNode part : parts) {
			JsonNode functionCall = part.path("functionCall");
			if (functionCall.isObject()) {
				JsonNode args = functionCall.path("args");
				calls.add(new LlmTurn.ToolCall(functionCall.path("name").asString(),
						args.isObject() ? args : objectMapper.createObjectNode()));
			} else if (part.hasNonNull("text")) {
				text.append(part.path("text").asString());
			}
		}
		return new LlmTurn(text.toString(), List.copyOf(calls),
				usage.path("promptTokenCount").asInt(), usage.path("candidatesTokenCount").asInt());
	}

	/** 툴 결과 1건 - payloadJson은 {@link AiToolResult#payloadJson()} 그대로다. */
	public record ToolResponse(String name, String payloadJson) {
	}
}
```

- [ ] **3-6. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.GeminiChatClientTest"` - 기대: BUILD SUCCESSFUL, 테스트 5건 통과.

- [ ] **3-7. 커밋한다.** `git add -A && git commit -m "feat(was): Gemini function calling 요청 조립·응답 파싱 클라이언트 추가"`

---

## Task 4: 툴 선언 6종과 시스템 프롬프트

설계 §4·§7. 프롬프트와 툴 정의는 was 소관이라는 원칙에 따라 was 안 상수로 둔다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiPrompt.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecs.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecsTest.java`

### 스텝

- [ ] **4-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecsTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 툴 선언 6종의 형태 검증(설계 §4) - 이름 오타·깨진 스키마는 런타임까지 안 가고 여기서 잡는다. */
class BrandAiToolSpecsTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void 설계가_정한_툴_6종이_그대로_선언된다() {
		assertThat(BrandAiToolSpecs.ALL).extracting(AiToolSpec::name)
				.containsExactly("list_brands", "list_posts", "get_post", "get_comments",
						"list_hashtag_posts", "get_author");
	}

	@Test
	void 모든_툴_스키마가_파싱_가능한_object_타입이다() {
		for (AiToolSpec spec : BrandAiToolSpecs.ALL) {
			assertThat(spec.description()).isNotBlank();
			if (spec.parametersJson() != null) {
				assertThat(om.readTree(spec.parametersJson()).path("type").asString()).isEqualTo("object");
			}
		}
	}

	@Test
	void 인자가_없는_list_brands만_스키마가_null이다() {
		List<String> withoutSchema = BrandAiToolSpecs.ALL.stream()
				.filter(spec -> spec.parametersJson() == null).map(AiToolSpec::name).toList();
		assertThat(withoutSchema).containsExactly("list_brands");
	}

	@Test
	void 시스템_프롬프트는_도메인_밖_질문_거절을_지시한다() {
		assertThat(BrandAiPrompt.SYSTEM).contains("브랜드 모니터링").contains("답할 수 없어요");
		assertThat(BrandAiPrompt.TOOL_CAP_NOTE).contains("지금까지");
	}
}
```

- [ ] **4-2. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolSpecsTest"` - 기대: 컴파일 실패.

- [ ] **4-3. `BrandAiPrompt`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiPrompt.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 어시스턴트 시스템 프롬프트(설계 §7) - 도메인 밖 질문 거절과 "툴로 조회한 사실만" 규칙이 핵심이다.
 * 프롬프트는 was 소관이라 common-llm에 반입하지 않는다(설계 §3).
 *
 * <p>사용자에게 그대로 노출될 수 있는 문안이므로 엠대시를 쓰지 않는다(전역 카피 규칙).
 */
public final class BrandAiPrompt {

	public static final String SYSTEM = """
			당신은 하입나우 브랜드 모니터링 데이터를 읽어 답하는 분석 어시스턴트입니다.

			규칙:
			1. 답변에 쓰는 사실은 반드시 제공된 툴로 조회한 데이터에서만 가져옵니다. 추측하거나 일반 상식으로 채우지 않습니다.
			2. 브랜드 모니터링(등록한 브랜드 계정, 태그된 게시물, 해시태그로 발견한 게시물, 댓글, 게시자 프로필) 밖의 질문에는 답하지 않습니다. 그럴 때는 "이 어시스턴트는 브랜드 모니터링 데이터에 대해서만 답할 수 있어요."라고 안내하고 끝냅니다.
			3. 어떤 브랜드에 대한 질문인지 분명하지 않으면 먼저 list_brands를 호출해 확인합니다.
			4. 툴이 빈 결과나 오류를 돌려주면 그 사실을 그대로 알립니다. 없는 데이터를 지어내지 않습니다.
			5. 특정 게시물을 언급할 때는 shortCode를 함께 적습니다.
			6. 답변은 한국어 평문으로 간결하게 씁니다. 표는 비교가 꼭 필요할 때만 씁니다.
			7. 같은 툴을 같은 인자로 반복 호출하지 않습니다. 필요한 정보가 모이면 바로 답합니다.
			""";

	/** 툴 호출 상한(설계 §7, 8회) 도달 시 마지막 턴에 덧붙여 답변을 강제하는 지시. */
	public static final String TOOL_CAP_NOTE = """

			[중요] 조회 가능 횟수를 모두 썼습니다. 추가 조회 없이 지금까지 확인한 정보만으로 답하세요.
			확인하지 못한 부분은 확인하지 못했다고 솔직히 적으세요.
			""";

	private BrandAiPrompt() {
	}
}
```

- [ ] **4-4. `BrandAiToolSpecs`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolSpecs.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 툴 선언 6종(설계 §4) - 전부 읽기 전용이다. 이름 문자열이 {@link BrandAiToolbox}의 dispatch
 * switch와 1:1로 맞아야 하므로 상수로 뽑아 양쪽이 같은 심볼을 쓴다.
 *
 * <p>description은 모델이 읽는 유일한 사용 설명서다 - 상한(게시물 30건·댓글 50건)과 데이터의
 * 한계(해시태그 발견 게시물은 지표·댓글이 없다)를 여기 적어야 모델이 헛도는 호출을 하지 않는다.
 */
public final class BrandAiToolSpecs {

	public static final String LIST_BRANDS = "list_brands";
	public static final String LIST_POSTS = "list_posts";
	public static final String GET_POST = "get_post";
	public static final String GET_COMMENTS = "get_comments";
	public static final String LIST_HASHTAG_POSTS = "list_hashtag_posts";
	public static final String GET_AUTHOR = "get_author";

	public static final List<AiToolSpec> ALL = List.of(
			new AiToolSpec(LIST_BRANDS,
					"이 사용자가 모니터링 중인 브랜드 계정 목록과 계정 메타(팔로워·게시물 수·소개글·내 브랜드/경쟁사 구분)를 돌려준다. "
							+ "brandId가 필요한 다른 툴을 쓰기 전에 먼저 호출한다.",
					null),
			new AiToolSpec(LIST_POSTS,
					"브랜드에 태그된 게시물 목록을 최근 순 또는 성과 순으로 최대 30건 돌려준다. "
							+ "각 항목은 shortCode·업로드일·유료협찬 표기 여부·캡션 앞부분·좋아요/댓글수/조회수를 담는다. "
							+ "피드 게시물의 조회수는 항상 null이다.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 30일, 최대 365일"},
					  "sort":{"type":"string","enum":["uploaded_desc","performance_desc"],
					          "description":"uploaded_desc는 최신순, performance_desc는 조회수 높은 순. 생략하면 최신순"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(GET_POST,
					"게시물 1건의 상세를 돌려준다: 전체 캡션, 게시자, 유료협찬 표기 여부, 광고 표기 판정, 일별 지표 시계열(최근 14일). "
							+ "shortCode는 list_posts가 돌려준 값이어야 한다. 해시태그로 발견한 게시물(list_hashtag_posts)은 "
							+ "브랜드 게시물 풀에 없어 이 툴로 조회되지 않는다.",
					"""
					{"type":"object","properties":{
					  "shortCode":{"type":"string","description":"인스타그램 게시물 shortCode"}
					},"required":["shortCode"]}
					"""),
			new AiToolSpec(GET_COMMENTS,
					"게시물 1건의 댓글을 최신순으로 돌려준다. 최대 50건이며 그보다 큰 limit을 넘겨도 50건으로 자른다. "
							+ "댓글 반응·여론을 물었을 때 쓴다.",
					"""
					{"type":"object","properties":{
					  "shortCode":{"type":"string","description":"인스타그램 게시물 shortCode"},
					  "limit":{"type":"integer","description":"가져올 댓글 수. 생략하면 20건, 최대 50건"}
					},"required":["shortCode"]}
					"""),
			new AiToolSpec(LIST_HASHTAG_POSTS,
					"브랜드 해시태그로 발견한 게시물을 최근 순으로 최대 30건 돌려준다. 태그 없이 브랜드를 언급한 게시물을 찾는 경로다. "
							+ "이 게시물들은 지표 시계열과 댓글이 수집되지 않아 get_post·get_comments로 더 파고들 수 없다.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 30일, 최대 365일"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(GET_AUTHOR,
					"게시자(인플루언서) 인스타그램 계정의 공개 프로필을 돌려준다: 이름·팔로워 수·인증 배지 여부. "
							+ "list_posts가 돌려준 authorUsername으로 호출한다.",
					"""
					{"type":"object","properties":{
					  "username":{"type":"string","description":"인스타그램 계정 아이디(@ 없이)"}
					},"required":["username"]}
					"""));

	private BrandAiToolSpecs() {
	}
}
```

- [ ] **4-5. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolSpecsTest"` - 기대: BUILD SUCCESSFUL, 테스트 4건 통과.

- [ ] **4-6. 커밋한다.** `git add -A && git commit -m "feat(was): AI 어시스턴트 툴 선언 6종·시스템 프롬프트 정의"`

---

## Task 5: 툴 실행기 - 소유 검증과 건수 상한

설계 §4·§7. **이 태스크가 보안 경계다.** brandId·shortCode 소유 검증이 여기 없으면 LLM이 임의 id로 남의 브랜드를 읽는다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiToolResult.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolbox.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolboxIntegrationTest.java`

### 스텝

- [ ] **5-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolboxIntegrationTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 툴 레이어 소유 검증 통합 검증(설계 §4·§9) - 실 DB 위에서 "남의 brandId·shortCode를 넘기면 막히는가"가
 * 필수 케이스다. monitoring 테이블은 was 테스트 픽스처(monitoring-brand-schema.sql)를 같은 컨테이너에
 * 얹어 재현한다(V1BrandDirectPostCancelIntegrationTest와 같은 관용구).
 */
class BrandAiToolboxIntegrationTest extends IntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	BrandAiToolbox toolbox;
	long userId;
	long otherUserId;
	long myBrandId;
	long otherBrandId;

	@BeforeEach
	void setUp() throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		JdbcClient monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post
				         RESTART IDENTITY CASCADE
				""").update();
		jdbcClient.sql("TRUNCATE app.brand_monitorings RESTART IDENTITY CASCADE").update();

		toolbox = new BrandAiToolbox(linkRepository, new BrandReadRepository(monitoringJdbc),
				objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), true);

		userId = insertUser();
		otherUserId = insertUser();
		myBrandId = insertBrand(monitoringJdbc, "mybrand");
		otherBrandId = insertBrand(monitoringJdbc, "otherbrand");
		linkRepository.insertLink(userId, myBrandId, "mybrand", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, otherBrandId, "otherbrand", BrandAccountType.OWN, 12);

		insertPost(monitoringJdbc, myBrandId, "MINE1", "mine_author");
		insertPost(monitoringJdbc, otherBrandId, "THEIRS1", "their_author");
	}

	private long insertUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""").param("email", UUID.randomUUID() + "@example.com").query(Long.class).single();
	}

	private long insertBrand(JdbcClient monitoringJdbc, String username) {
		return monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography, media_count)
				VALUES (:username, :igId, 1000, '브랜드 소개', 42)
				RETURNING id
				""").param("username", username).param("igId", username + "-ig")
				.query(Long.class).single();
	}

	private void insertPost(JdbcClient monitoringJdbc, long brandId, String shortCode, String author) {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at)
				VALUES (:brandId, :shortCode, :author, :author, :takenAt)
				""").param("brandId", brandId).param("shortCode", shortCode).param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(NOW.minusSeconds(86400), ZoneOffset.UTC))
				.update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
				                             is_paid_partnership, ad_verdict)
				VALUES (:shortCode, :author, 'reel', DATE '2026-08-26', :caption, true, 'DISCLOSED')
				""").param("shortCode", shortCode).param("author", author)
				.param("caption", shortCode + " 캡션 본문").update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes, comments, views)
				VALUES (:author, :shortCode, DATE '2026-08-26', 'reel', 100, 7, 5000)
				""").param("shortCode", shortCode).param("author", author).update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES (:shortCode, :shortCode || '-c1', 'fan1', '너무 예뻐요', 3, :at)
				""").param("shortCode", shortCode)
				.param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
	}

	private ObjectNode args() {
		return objectMapper.createObjectNode();
	}

	@Test
	void list_brands는_내_브랜드만_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_BRANDS, args());

		assertThat(result.failed()).isFalse();
		assertThat(result.rowCount()).isEqualTo(1);
		assertThat(result.payloadJson()).contains("mybrand").doesNotContain("otherbrand");
	}

	@Test
	void 남의_brandId로_list_posts하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", otherBrandId));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).contains("접근 권한");
		assertThat(result.rowCount()).isZero();
	}

	@Test
	void 남의_shortCode로_get_post하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "THEIRS1"));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).doesNotContain("THEIRS1 캡션 본문");
	}

	@Test
	void 남의_shortCode로_get_comments하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "THEIRS1"));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).doesNotContain("너무 예뻐요");
	}

	@Test
	void 내_게시물은_상세와_댓글이_모두_조회된다() {
		AiToolResult post = toolbox.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "MINE1"));
		AiToolResult comments = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "MINE1"));

		assertThat(post.failed()).isFalse();
		assertThat(post.payloadJson()).contains("MINE1 캡션 본문").contains("DISCLOSED");
		assertThat(post.shortCodes()).containsExactly("MINE1");
		assertThat(comments.failed()).isFalse();
		assertThat(comments.payloadJson()).contains("너무 예뻐요");
	}

	@Test
	void 댓글_상한은_모델_요청값과_무관하게_50건으로_잘린다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "MINE1").put("limit", 9999));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"limit\":50");
	}

	@Test
	void 광고_판정_노출_토글이_꺼지면_adDisclosure가_실리지_않는다() {
		BrandAiToolbox hidden = new BrandAiToolbox(linkRepository,
				new BrandReadRepository(JdbcClient.create(dataSource)), objectMapper,
				Clock.fixed(NOW, ZoneOffset.UTC), false);

		AiToolResult result = hidden.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "MINE1"));

		assertThat(result.payloadJson()).doesNotContain("DISCLOSED");
	}

	@Test
	void 모르는_툴_이름은_실패_결과다() {
		AiToolResult result = toolbox.execute(userId, "drop_table", args());

		assertThat(result.failed()).isTrue();
	}
}
```

- [ ] **5-2. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolboxIntegrationTest"` - 기대: 컴파일 실패.

- [ ] **5-3. `AiToolResult`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiToolResult.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 툴 1회 실행 결과(설계 §4·§8).
 *
 * <p>소유 검증 실패·잘못된 인자는 예외가 아니라 {@code failed} 결과다 - 모델이 자가 수정할 기회를
 * 줘야 하고(설계 §8), 무엇보다 LLM이 넘긴 값 하나 때문에 사용자 요청 전체를 500으로 만들 이유가 없다.
 *
 * @param payloadJson 모델에 되먹일 JSON. 실패면 {"error": "..."} 형태다.
 * @param rowCount    로그(app.ai_chat_logs.tool_calls[].rows)에 남길 결과 행 수.
 * @param shortCodes  이 호출이 언급한 게시물 shortCode - 응답의 참조 목록으로 모인다(설계 §5).
 */
public record AiToolResult(String payloadJson, int rowCount, List<String> shortCodes, boolean failed) {

	public static AiToolResult ok(String payloadJson, int rowCount, List<String> shortCodes) {
		return new AiToolResult(payloadJson, rowCount, List.copyOf(shortCodes), false);
	}

	/** 실패 메시지는 모델이 읽는다 - 무엇을 고쳐야 하는지 알 수 있게 쓴다. */
	public static AiToolResult failure(String payloadJson) {
		return new AiToolResult(payloadJson, 0, List.of(), true);
	}
}
```

- [ ] **5-4. `BrandAiToolbox`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiToolbox.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostIndexRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.LatestViewsRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 툴 6종 실행기(설계 §4) - 전부 읽기 전용이고, <b>brandId·shortCode 소유 검증이 이 클래스 안에서
 * 강제된다</b>. LLM이 임의 id를 넘길 수 있다는 전제로 짜여 있으며, 검증에 걸리면 예외가 아니라
 * failed 결과를 돌려 모델이 스스로 물러나게 한다.
 *
 * <p>{@link BrandReadRepository}는 brandId를 검증 없이 조회한다(그 클래스 javadoc 명시) - 소유
 * 스코프는 호출자 책임이고, 이 클래스가 그 호출자다. 반드시 {@link BrandLinkRepository}에서 얻은
 * brandId만 넘긴다.
 *
 * <p>건수 상한(게시물 30·댓글 50·시계열 14)은 모델 요청값과 무관하게 여기서 자른다(설계 §7) -
 * 토큰 폭발 방지가 목적이라 프롬프트 지시로는 보장할 수 없다.
 */
public class BrandAiToolbox {

	/** 게시물 목록 상한 - 30건이면 "최근 흐름"을 판단하기 충분하고 캡션 발췌 포함 토큰이 통제된다. */
	private static final int MAX_POSTS = 30;
	/** 댓글 상한(설계 §7). */
	private static final int MAX_COMMENTS = 50;
	private static final int DEFAULT_COMMENTS = 20;
	private static final int MAX_HASHTAG_POSTS = 30;
	/** 지표 시계열 상한 - 최근 14일이면 상승/정체 판단에 충분하다. */
	private static final int MAX_SNAPSHOTS = 14;
	private static final int DEFAULT_DAYS = 30;
	private static final int MAX_DAYS = 365;
	private static final int CAPTION_EXCERPT_LENGTH = 120;
	private static final int CAPTION_FULL_LENGTH = 1500;

	private static final String SORT_PERFORMANCE_DESC = "performance_desc";

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final boolean exposeAdDisclosure;

	public BrandAiToolbox(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			ObjectMapper objectMapper, Clock clock, boolean exposeAdDisclosure) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}

	public AiToolResult execute(long userId, String toolName, JsonNode args) {
		return switch (toolName) {
			case BrandAiToolSpecs.LIST_BRANDS -> listBrands(userId);
			case BrandAiToolSpecs.LIST_POSTS -> listPosts(userId, args);
			case BrandAiToolSpecs.GET_POST -> getPost(userId, args);
			case BrandAiToolSpecs.GET_COMMENTS -> getComments(userId, args);
			case BrandAiToolSpecs.LIST_HASHTAG_POSTS -> listHashtagPosts(userId, args);
			case BrandAiToolSpecs.GET_AUTHOR -> getAuthor(args);
			default -> error("알 수 없는 툴입니다: " + toolName);
		};
	}

	private AiToolResult listBrands(long userId) {
		ArrayNode brands = objectMapper.createArrayNode();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			ObjectNode node = brands.addObject();
			node.put("brandId", link.brandId());
			node.put("username", link.username());
			node.put("accountType", link.accountType());
			node.put("collectionMonths", link.collectionMonths());
			brandReadRepository.findAccount(link.brandId()).ifPresent(account -> {
				node.put("followers", account.followers());
				node.put("mediaCount", account.mediaCount());
				node.put("fullName", account.fullName());
				node.put("biography", account.biography());
			});
		}
		return AiToolResult.ok(brands.toString(), brands.size(), List.of());
	}

	private AiToolResult listPosts(long userId, JsonNode args) {
		Optional<BrandLinkRow> link = ownedBrand(userId, args.path("brandId").asLong(0));
		if (link.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		OffsetDateTime cutoff = cutoffFor(link.get(), args);
		List<BrandPostIndexRow> index = brandReadRepository.findBrandPostIndex(link.get().brandId(), cutoff, false);
		Map<String, Long> viewsByCode = new HashMap<>();
		for (LatestViewsRow row : brandReadRepository.findLatestViewsForBrand(link.get().brandId(), cutoff, false)) {
			viewsByCode.put(row.shortCode(), row.views());
		}
		Comparator<BrandPostIndexRow> order = SORT_PERFORMANCE_DESC.equals(args.path("sort").asString())
				// 조회수 없는 게시물(피드는 views가 항상 null)이 성과순 앞자리를 차지하지 않도록 0으로 접는다
				? Comparator.comparingLong((BrandPostIndexRow row) -> viewsOf(viewsByCode, row.shortCode()))
						.reversed()
				: Comparator.comparing(BrandPostIndexRow::takenAt,
						Comparator.nullsLast(Comparator.reverseOrder()));
		List<BrandPostIndexRow> page = index.stream().sorted(order).limit(MAX_POSTS).toList();

		List<String> codes = page.stream().map(BrandPostIndexRow::shortCode).toList();
		Map<String, BrandSnapshotRow> latest = latestSnapshotByCode(codes);
		Map<String, BrandPostMetaRow> metaByCode = new HashMap<>();
		for (BrandPostMetaRow meta : brandReadRepository.findPostMeta(codes)) {
			metaByCode.put(meta.shortCode(), meta);
		}

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.get().brandId());
		payload.put("since", cutoff.toString());
		payload.put("returned", page.size());
		payload.put("totalInWindow", index.size());
		ArrayNode posts = payload.putArray("posts");
		for (BrandPostIndexRow row : page) {
			ObjectNode node = posts.addObject();
			node.put("shortCode", row.shortCode());
			node.put("takenAt", row.takenAt() == null ? null : row.takenAt().toString());
			node.put("isPaidPartnership", row.isPaidPartnership());
			node.put("caption", truncate(row.caption(), CAPTION_EXCERPT_LENGTH));
			BrandPostMetaRow meta = metaByCode.get(row.shortCode());
			node.put("authorUsername", meta == null ? null : meta.username());
			BrandSnapshotRow snapshot = latest.get(row.shortCode());
			node.put("likes", snapshot == null ? null : snapshot.likes());
			node.put("comments", snapshot == null ? null : snapshot.comments());
			node.put("views", snapshot == null ? null : snapshot.views());
		}
		return AiToolResult.ok(payload.toString(), page.size(), codes);
	}

	private AiToolResult getPost(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		if (ownerBrandOf(userId, shortCode).isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		List<BrandPostMetaRow> metas = brandReadRepository.findPostMeta(List.of(shortCode));
		if (metas.isEmpty()) {
			return error("그 게시물의 상세 정보가 아직 수집되지 않았습니다.");
		}
		BrandPostMetaRow meta = metas.get(0);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", meta.shortCode());
		payload.put("authorUsername", meta.username());
		payload.put("contentType", meta.contentType());
		payload.put("uploadedAt", meta.uploadedAt() == null ? null : meta.uploadedAt().toString());
		payload.put("caption", truncate(meta.caption(), CAPTION_FULL_LENGTH));
		payload.put("isPaidPartnership", meta.isPaidPartnership());
		if (exposeAdDisclosure) {
			payload.put("adDisclosure", meta.adVerdict());
		}
		ArrayNode metrics = payload.putArray("dailyMetrics");
		List<BrandSnapshotRow> snapshots = brandReadRepository.findSnapshots(List.of(shortCode));
		List<BrandSnapshotRow> tail = snapshots.size() <= MAX_SNAPSHOTS
				? snapshots : snapshots.subList(snapshots.size() - MAX_SNAPSHOTS, snapshots.size());
		for (BrandSnapshotRow snapshot : tail) {
			ObjectNode node = metrics.addObject();
			node.put("capturedOn", snapshot.capturedOn().toString());
			node.put("likes", snapshot.likes());
			node.put("comments", snapshot.comments());
			node.put("views", snapshot.views());
		}
		return AiToolResult.ok(payload.toString(), 1, List.of(shortCode));
	}

	private AiToolResult getComments(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		if (ownerBrandOf(userId, shortCode).isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		int requested = args.path("limit").asInt(DEFAULT_COMMENTS);
		int limit = Math.clamp(requested, 1, MAX_COMMENTS);
		List<BrandCommentRow> rows = brandReadRepository.findComments(List.of(shortCode), limit);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", shortCode);
		payload.put("limit", limit);
		payload.put("returned", rows.size());
		ArrayNode comments = payload.putArray("comments");
		for (BrandCommentRow row : rows) {
			ObjectNode node = comments.addObject();
			node.put("author", row.author());
			node.put("body", row.body());
			node.put("likeCount", row.likeCount());
			node.put("commentedAt", row.commentedAt() == null ? null : row.commentedAt().toString());
			node.put("ownerReplyText", row.ownerReplyText());
		}
		return AiToolResult.ok(payload.toString(), rows.size(), List.of(shortCode));
	}

	private AiToolResult listHashtagPosts(long userId, JsonNode args) {
		Optional<BrandLinkRow> link = ownedBrand(userId, args.path("brandId").asLong(0));
		if (link.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		List<BrandHashtagPostRow> rows = brandReadRepository.findHashtagPosts(
				link.get().brandId(), cutoffFor(link.get(), args), MAX_HASHTAG_POSTS);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.get().brandId());
		payload.put("returned", rows.size());
		ArrayNode posts = payload.putArray("posts");
		List<String> codes = new ArrayList<>();
		for (BrandHashtagPostRow row : rows) {
			codes.add(row.shortCode());
			ObjectNode node = posts.addObject();
			node.put("shortCode", row.shortCode());
			node.put("matchedTag", row.matchedTag());
			node.put("authorUsername", row.authorUsername());
			node.put("takenAt", row.takenAt() == null ? null : row.takenAt().toString());
			node.put("caption", truncate(row.caption(), CAPTION_EXCERPT_LENGTH));
			node.put("likes", row.likes());
			node.put("comments", row.comments());
		}
		return AiToolResult.ok(payload.toString(), rows.size(), codes);
	}

	/**
	 * 게시자 프로필은 브랜드 스코프로 좁히지 않는다 - author_profile은 공개 인스타그램 프로필
	 * (이름·팔로워·인증 배지)만 담고 사용자별 비공개 데이터가 없으며, 설계 §4의 소유 검증 대상도
	 * brandId·shortCode 둘로 명시돼 있다. 열람하려면 username을 이미 알아야 해서 열거 경로도 아니다.
	 */
	private AiToolResult getAuthor(JsonNode args) {
		String username = args.path("username").asString();
		if (username.isBlank()) {
			return error("username이 필요합니다.");
		}
		List<AuthorRow> rows = brandReadRepository.findAuthorsByUsername(List.of(username));
		if (rows.isEmpty()) {
			return error("그 계정의 프로필이 수집되지 않았습니다: " + username);
		}
		AuthorRow row = rows.get(0);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("username", row.username());
		payload.put("fullName", row.fullName());
		payload.put("followers", row.followers());
		payload.put("isVerified", row.isVerified());
		return AiToolResult.ok(payload.toString(), 1, List.of());
	}

	/** 유저의 활성 링크에 있는 brandId만 통과시킨다 - 여기가 브랜드 소유 검증 지점이다. */
	private Optional<BrandLinkRow> ownedBrand(long userId, long brandId) {
		if (brandId <= 0) {
			return Optional.empty();
		}
		return linkRepository.findActiveByUserAndBrand(userId, brandId);
	}

	/**
	 * shortCode가 이 유저의 어느 브랜드 게시물 풀(tagged ∪ direct)에 속하는지 - 속하지 않으면 empty다.
	 * 브랜드 수는 유저당 최대 9개(own 6 + competitor 3)라 순회 비용이 상한선 안에 있다.
	 */
	private Optional<Long> ownerBrandOf(long userId, String shortCode) {
		if (shortCode == null || shortCode.isBlank()) {
			return Optional.empty();
		}
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			if (!brandReadRepository.findBrandPostsByShortCodes(link.brandId(), List.of(shortCode)).isEmpty()) {
				return Optional.of(link.brandId());
			}
		}
		return Optional.empty();
	}

	/** 모델이 요청한 days와 유저의 표시 기간(collectionMonths) 중 짧은 쪽 - 창 밖 데이터는 보이면 안 된다. */
	private OffsetDateTime cutoffFor(BrandLinkRow link, JsonNode args) {
		int days = Math.clamp(args.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
		OffsetDateTime now = OffsetDateTime.now(clock);
		OffsetDateTime requested = now.minusDays(days);
		OffsetDateTime windowStart = now.minusMonths(link.collectionMonths());
		return requested.isAfter(windowStart) ? requested : windowStart;
	}

	/** 성과순 정렬 키 - 조회수 미수집(피드는 항상 null)은 0으로 접어 뒤로 보낸다. */
	private static long viewsOf(Map<String, Long> viewsByCode, String shortCode) {
		Long views = viewsByCode.get(shortCode);
		return views == null ? 0L : views;
	}

	private Map<String, BrandSnapshotRow> latestSnapshotByCode(List<String> shortCodes) {
		Map<String, BrandSnapshotRow> latest = new HashMap<>();
		if (shortCodes.isEmpty()) {
			return latest;
		}
		// findSnapshots는 capturedOn 오름차순이라 뒤에 오는 행이 항상 더 최신이다
		for (BrandSnapshotRow row : brandReadRepository.findSnapshots(shortCodes)) {
			latest.put(row.shortCode(), row);
		}
		return latest;
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return null;
		}
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}

	private AiToolResult error(String message) {
		return AiToolResult.failure(objectMapper.createObjectNode().put("error", message).toString());
	}
}
```

- [ ] **5-5. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiToolboxIntegrationTest"` - 기대: BUILD SUCCESSFUL, 테스트 8건 통과. 특히 `남의_brandId로_list_posts하면_실패_결과를_돌려준다`·`남의_shortCode로_get_post하면_실패_결과를_돌려준다`·`남의_shortCode로_get_comments하면_실패_결과를_돌려준다` 세 건이 이 태스크의 합격 조건이다.

- [ ] **5-6. 커밋한다.** `git add -A && git commit -m "feat(was): 소유 검증·건수 상한을 강제하는 AI 툴 실행기 6종 추가"`

---

## Task 6: 에이전트 루프

설계 §3·§7·§8. 툴 호출 상한 8회, 툴 실패 1회 되먹임이 여기 산다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatMessage.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgent.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgentTest.java`

### 스텝

- [ ] **6-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgentTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 루프 검증(설계 §9) - 전송을 스크립트 fake로 갈아끼워 "툴 호출 → 되먹임 → 종료"·툴 실패
 * 되먹임·툴 호출 상한 동작을 결정론으로 고정한다. 실 LLM은 때리지 않는다.
 */
class BrandAiAgentTest {

	private final ObjectMapper om = new ObjectMapper();

	private static String functionCall(String name, String argsJson) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{"
				+ "\"name\":\"" + name + "\",\"args\":" + argsJson + "}}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	private static String textAnswer(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	/** 스크립트를 순서대로 돌려주고, 다 떨어지면 마지막 응답을 반복한다. */
	private ChatTransport scripted(List<String> script, List<String> captured) {
		AtomicInteger index = new AtomicInteger();
		return body -> {
			captured.add(body);
			int i = Math.min(index.getAndIncrement(), script.size() - 1);
			return script.get(i);
		};
	}

	private BrandAiAgent agentWith(List<String> script, List<String> captured, BrandAiToolbox toolbox) {
		return new BrandAiAgent(new GeminiChatClient(scripted(script, captured), om), toolbox, om);
	}

	@Test
	void 툴을_호출하고_결과를_되먹인_뒤_텍스트로_답한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_posts", "{\"brandId\":7}"), textAnswer("3건 있어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("3건 있어요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(outcome.referencedShortCodes()).containsExactly("ABC");
		assertThat(outcome.toolCalls()).hasSize(1);
		assertThat(outcome.toolCalls().get(0).name()).isEqualTo("list_posts");
		assertThat(outcome.toolCalls().get(0).rows()).isEqualTo(3);
		assertThat(outcome.brandId()).isEqualTo(7L);
		assertThat(outcome.promptTokens()).isEqualTo(20);
		// 2번째 요청 본문에 functionResponse 되먹임이 실려 있어야 한다
		assertThat(captured.get(1)).contains("functionResponse").contains("list_posts");
	}

	@Test
	void 툴이_없으면_한_번의_LLM_호출로_끝난다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("모니터링 데이터만 답할 수 있어요")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "오늘 날씨?")));

		assertThat(outcome.answer()).isEqualTo("모니터링 데이터만 답할 수 있어요");
		assertThat(captured).hasSize(1);
		assertThat(outcome.toolCalls()).isEmpty();
	}

	@Test
	void 툴_실패는_첫_회만_재시도_지시로_되먹인다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.failure("{\"error\":\"권한 없음\"}"));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("get_post", "{\"shortCode\":\"X\"}"),
						functionCall("get_post", "{\"shortCode\":\"Y\"}"),
						textAnswer("확인하지 못했어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("확인하지 못했어요");
		// 요청 본문은 Jackson 컴팩트 직렬화라 콜론 뒤에 공백이 없다
		assertThat(captured.get(1)).contains("\"retry\":true");
		assertThat(captured.get(2)).contains("\"retry\":false");
	}

	@Test
	void 툴_호출이_8회를_넘으면_툴_없이_답변을_강제한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		// 스크립트가 끝없이 툴만 요청한다 - 상한이 없으면 무한 루프다
		BrandAiAgent agent = agentWith(List.of(functionCall("list_brands", "{}")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.toolCalls()).hasSize(8);
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_TOOL_CAP);
		// 마지막 요청은 tools 필드 없이(= 툴 호출 불가) 상한 안내를 달고 나간다
		String last = captured.get(captured.size() - 1);
		assertThat(om.readTree(last).has("tools")).isFalse();
		assertThat(last).contains("조회 가능 횟수를 모두 썼습니다");
	}
}
```

- [ ] **6-2. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiAgentTest"` - 기대: 컴파일 실패.

- [ ] **6-3. `AiChatMessage`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatMessage.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 무상태 대화 이력 1건(설계 §5) - 서버 세션이 없으므로 프론트가 매 요청에 전체를 실어 보낸다.
 *
 * @param role "user" 또는 "assistant". 그 외 값은 컨트롤러가 거른다.
 */
public record AiChatMessage(String role, String content) {

	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";
}
```

- [ ] **6-4. `BrandAiAgent`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiAgent.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 루프(설계 §3) - 시스템 프롬프트 + 대화 이력을 LLM에 보내고, 툴 호출이 오면 실행해
 * 되먹이기를 반복하다 텍스트 답변이 나오면 끝낸다. SSE·서버 세션은 스코프 밖이다(설계 §10).
 *
 * <p>정지 조건이 두 겹이다: 툴 호출 {@value #MAX_TOOL_CALLS}회(설계 §7)에 걸리면 다음 턴을 툴 없이
 * 보내 답변을 강제하고, 그래도 안 끝나는 병리적 경우를 위해 LLM 호출 자체를 {@value #MAX_LLM_CALLS}회로
 * 막는다. 후자는 도달하면 안 되는 안전망이라 도달 시 warn을 남긴다.
 *
 * <p>툴 실패는 같은 툴 기준 1회만 재시도 지시를 붙여 되먹인다(설계 §8) - 두 번째부터는
 * {@code retry: false}로 "이 정보 없이 답하라"고 못 박는다. 그러지 않으면 모델이 같은 실패를
 * 상한까지 반복한다.
 */
public class BrandAiAgent {

	/** 턴당 툴 호출 상한(설계 §7). */
	static final int MAX_TOOL_CALLS = 8;
	/** LLM 호출 안전망 - 툴 상한 도달 후 강제 답변 턴까지 감안한 여유값. */
	static final int MAX_LLM_CALLS = 12;
	/** 대화 이력에서 되살리지 못한 답변을 대신할 문구. */
	private static final String FALLBACK_ANSWER =
			"확인한 내용을 정리하지 못했어요. 질문을 조금 더 좁혀서 다시 물어봐 주세요.";

	private static final Logger log = LoggerFactory.getLogger(BrandAiAgent.class);

	private final GeminiChatClient client;
	private final BrandAiToolbox toolbox;
	private final ObjectMapper objectMapper;

	public BrandAiAgent(GeminiChatClient client, BrandAiToolbox toolbox, ObjectMapper objectMapper) {
		this.client = client;
		this.toolbox = toolbox;
		this.objectMapper = objectMapper;
	}

	public AgentOutcome run(long userId, List<AiChatMessage> messages) {
		List<JsonNode> contents = new ArrayList<>();
		for (AiChatMessage message : messages) {
			contents.add(AiChatMessage.ROLE_ASSISTANT.equals(message.role())
					? client.modelContent(message.content())
					: client.userContent(message.content()));
		}

		List<AiChatLogEntry.ToolCallLog> toolCalls = new ArrayList<>();
		LinkedHashSet<String> shortCodes = new LinkedHashSet<>();
		Map<String, Integer> failuresByTool = new HashMap<>();
		Long brandId = null;
		int promptTokens = 0;
		int outputTokens = 0;

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			boolean capped = toolCalls.size() >= MAX_TOOL_CALLS;
			LlmTurn turn = client.generate(
					capped ? BrandAiPrompt.SYSTEM + BrandAiPrompt.TOOL_CAP_NOTE : BrandAiPrompt.SYSTEM,
					contents,
					capped ? List.of() : BrandAiToolSpecs.ALL);
			promptTokens += turn.promptTokens();
			outputTokens += turn.outputTokens();

			if (turn.toolCalls().isEmpty()) {
				String answer = turn.text().isBlank() ? FALLBACK_ANSWER : turn.text();
				return new AgentOutcome(answer, List.copyOf(shortCodes), List.copyOf(toolCalls),
						promptTokens, outputTokens, brandId,
						capped ? AiChatLogEntry.OUTCOME_TOOL_CAP : AiChatLogEntry.OUTCOME_OK);
			}

			contents.add(client.modelToolCallContent(turn.toolCalls()));
			List<GeminiChatClient.ToolResponse> responses = new ArrayList<>();
			for (LlmTurn.ToolCall call : turn.toolCalls()) {
				if (toolCalls.size() >= MAX_TOOL_CALLS) {
					responses.add(new GeminiChatClient.ToolResponse(call.name(),
							objectMapper.createObjectNode().put("error", "조회 가능 횟수를 모두 썼습니다.")
									.put("retry", false).toString()));
					continue;
				}
				AiToolResult result = toolbox.execute(userId, call.name(), call.args());
				toolCalls.add(new AiChatLogEntry.ToolCallLog(call.name(), call.args(), result.rowCount()));
				shortCodes.addAll(result.shortCodes());
				if (brandId == null && call.args().hasNonNull("brandId")) {
					brandId = call.args().path("brandId").asLong();
				}
				responses.add(new GeminiChatClient.ToolResponse(call.name(),
						result.failed() ? withRetryHint(call.name(), result, failuresByTool)
								: result.payloadJson()));
			}
			contents.add(client.toolResultContent(responses));
		}

		log.warn("AI 에이전트 LLM 호출 안전망 도달 - userId={}, 툴 호출 {}회", userId, toolCalls.size());
		return new AgentOutcome(FALLBACK_ANSWER, List.copyOf(shortCodes), List.copyOf(toolCalls),
				promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_TOOL_CAP);
	}

	/** 같은 툴의 첫 실패에만 retry=true를 붙인다 - 두 번째부터는 물러나라고 지시한다(설계 §8). */
	private String withRetryHint(String toolName, AiToolResult result, Map<String, Integer> failuresByTool) {
		int failures = failuresByTool.merge(toolName, 1, Integer::sum);
		ObjectNode payload = (ObjectNode) objectMapper.readTree(result.payloadJson());
		payload.put("retry", failures == 1);
		if (failures > 1) {
			payload.put("hint", "이 정보 없이 지금까지 확인한 내용으로 답하세요.");
		}
		return payload.toString();
	}

	/**
	 * 루프 1회의 산출물.
	 *
	 * @param brandId 모델이 처음 넘긴 brandId 인자 - 로그 분석에서 "어느 브랜드 질문인가"를 가른다.
	 */
	public record AgentOutcome(String answer, List<String> referencedShortCodes,
			List<AiChatLogEntry.ToolCallLog> toolCalls, int promptTokens, int outputTokens,
			Long brandId, String outcome) {
	}
}
```

- [ ] **6-5. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.BrandAiAgentTest"` - 기대: BUILD SUCCESSFUL, 테스트 4건 통과.

- [ ] **6-6. 커밋한다.** `git add -A && git commit -m "feat(was): 툴 호출 상한·실패 되먹임을 갖춘 AI 에이전트 루프 추가"`

---

## Task 7: 일일 질문 상한

설계 §7·§8. app_setting으로 조정 가능한 유저당 일일 상한, 초과 시 429.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatQuota.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/AiChatQuotaIntegrationTest.java`

### 스텝

- [ ] **7-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/AiChatQuotaIntegrationTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** 일일 질문 상한 통합 검증(설계 §7·§8) - 기준값 시드·app_setting 오버라이드·초과 429를 모두 본다. */
class AiChatQuotaIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	AppSettingRepository settingRepository;

	AiChatLogRepository logRepository;
	AiChatQuota quota;
	long userId;

	@BeforeEach
	void setUp() {
		logRepository = new AiChatLogRepository(jdbcClient, objectMapper);
		// was에는 Clock 빈이 없다(생성자 직접 주입 관용구) - 실시계로 충분하다:
		// 지금 insert한 행은 항상 오늘 KST 자정 이후라 경계 결정론이 필요 없다
		quota = new AiChatQuota(logRepository, settingRepository, Clock.systemUTC());
		jdbcClient.sql("TRUNCATE app.ai_chat_logs RESTART IDENTITY CASCADE").update();
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "30");
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""").param("email", UUID.randomUUID() + "@example.com").query(Long.class).single();
	}

	@Test
	void 상한_미만이면_통과한다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "2");
		logRepository.insert(logOf());

		assertThatCode(() -> quota.requireWithinDailyLimit(userId)).doesNotThrowAnyException();
	}

	@Test
	void 상한에_도달하면_429를_던진다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "2");
		logRepository.insert(logOf());
		logRepository.insert(logOf());

		assertThatThrownBy(() -> quota.requireWithinDailyLimit(userId))
				.isInstanceOfSatisfying(V1ApiException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					assertThat(e.code()).isEqualTo("AI_DAILY_LIMIT_REACHED");
					assertThat(e.getMessage()).contains("2");
				});
	}

	@Test
	void 값이_숫자가_아니면_기본값_30으로_폴백한다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "없음");

		assertThat(quota.dailyLimit()).isEqualTo(AiChatQuota.DEFAULT_DAILY_LIMIT);
	}

	private AiChatLogEntry logOf() {
		return new AiChatLogEntry(userId, null, "질문", "답변", List.of(), 1, 1, 1L,
				AiChatLogEntry.OUTCOME_OK);
	}
}
```

- [ ] **7-2. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.AiChatQuotaIntegrationTest"` - 기대: 컴파일 실패.

- [ ] **7-3. `AiChatQuota`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatQuota.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 유저당 일일 질문 상한(설계 §7) - 기준값은 마이그레이션이 시드한 app_setting
 * {@value #DAILY_LIMIT_KEY}, 런타임 조정은 그 행 UPDATE로 한다.
 *
 * <p>하루 경계는 KST 자정이다 - 사용자가 체감하는 "오늘"과 맞아야 안내 문구("내일 다시")가 참이 된다.
 * 카운트 원장은 app.ai_chat_logs다(별도 카운터 테이블 없음, 설계 §6).
 *
 * <p>분당 버스트는 이 상한이 아니라 컨트롤러의 {@code RateLimiter}가 막는다 - 역할이 다르다.
 *
 * <p>컴포넌트 스캔 대상이 아니라 {@code BrandAiConfig}가 배선한다 - was에는 Clock 빈이 없어
 * {@code @Component}로 두면 킬 스위치와 무관하게 컨텍스트 기동이 깨진다(Clock은 생성자 직접 주입이
 * was 관용구다).
 */
public class AiChatQuota {

	public static final String DAILY_LIMIT_KEY = "ai.chat.daily-limit";
	public static final int DEFAULT_DAILY_LIMIT = 30;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Logger log = LoggerFactory.getLogger(AiChatQuota.class);

	private final AiChatLogRepository logRepository;
	private final AppSettingRepository settingRepository;
	private final Clock clock;

	public AiChatQuota(AiChatLogRepository logRepository, AppSettingRepository settingRepository, Clock clock) {
		this.logRepository = logRepository;
		this.settingRepository = settingRepository;
		this.clock = clock;
	}

	/** 상한에 도달했으면 429를 던진다. 도달 전이면 조용히 통과. */
	public void requireWithinDailyLimit(long userId) {
		int limit = dailyLimit();
		int used = logRepository.countSince(userId, startOfTodayKst());
		if (used >= limit) {
			throw new V1ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_DAILY_LIMIT_REACHED",
					"오늘 질문 가능 횟수(" + limit + "회)를 모두 사용했어요. 내일 다시 시도해 주세요.");
		}
	}

	int dailyLimit() {
		Optional<String> stored = settingRepository.findValue(DAILY_LIMIT_KEY);
		if (stored.isEmpty()) {
			return DEFAULT_DAILY_LIMIT;
		}
		try {
			return Integer.parseInt(stored.get().trim());
		} catch (NumberFormatException e) {
			log.warn("{} 값이 숫자가 아님({}) - 기본값 {}로 폴백", DAILY_LIMIT_KEY, stored.get(), DEFAULT_DAILY_LIMIT);
			return DEFAULT_DAILY_LIMIT;
		}
	}

	private OffsetDateTime startOfTodayKst() {
		return OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate()
				.atStartOfDay(KST).toOffsetDateTime();
	}
}
```

- [ ] **7-4. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.AiChatQuotaIntegrationTest"` - 기대: BUILD SUCCESSFUL, 테스트 3건 통과.

- [ ] **7-5. 커밋한다.** `git add -A && git commit -m "feat(was): AI 어시스턴트 일일 질문 상한(app_setting 기반) 추가"`

---

## Task 8: 챗 API 컨트롤러와 배선

설계 §5·§7·§8. 킬 스위치는 `@ConditionalOnProperty`로 빈 자체를 등록하지 않아 404가 된다(기존 `monitoring.enabled` 게이트와 동일 관용구).

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatRequest.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiConfig.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiChatController.java`
- Modify: `was/src/main/resources/application.yml`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiChatControllerTest.java`

### 스텝

- [ ] **8-1. 실패 테스트를 작성한다.** `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiChatControllerTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 챗 API 계약 슬라이스 검증(설계 §5·§8) - 에이전트는 mock이고, 여기서 보는 것은 요청 검증·상한
 * 429·로그 적재·응답 형태다. 킬 스위치 404는 컨텍스트 자체가 달라 별도 클래스로 뺀다.
 *
 * <p>실행기는 동기 실행기(Runnable::run)로 갈아끼운다 - 슬라이스 테스트에서 별도 스레드로 넘기면
 * 타이밍 의존 플레이키가 생기고, 여기서 검증하려는 것은 비동기 배선이 아니라 계약이다.
 */
@WebMvcTest(controllers = V1BrandAiChatController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class,
		V1BrandAiChatControllerTest.SyncExecutorConfig.class})
class V1BrandAiChatControllerTest {

	@TestConfiguration
	static class SyncExecutorConfig {

		@Bean("brandAiChatExecutor")
		Executor brandAiChatExecutor() {
			return Runnable::run;
		}
	}

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	BrandAiAgent agent;
	@MockitoBean
	AiChatQuota quota;
	@MockitoBean
	AiChatLogRepository logRepository;
	@MockitoBean
	RateLimiter rateLimiter;

	@BeforeEach
	void allowRateLimit() {
		// Mockito boolean 기본값이 false라 명시적으로 열어 주지 않으면 모든 테스트가 429가 된다
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
	}

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static String body(String content) {
		return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}";
	}

	@Test
	void 답변과_참조_shortCode를_돌려주고_로그를_남긴다() throws Exception {
		given(agent.run(anyLong(), any())).willReturn(new BrandAiAgent.AgentOutcome(
				"3건이에요", List.of("ABC", "DEF"), List.of(), 100, 20, 7L,
				AiChatLogEntry.OUTCOME_OK));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.answer").value("3건이에요"))
				.andExpect(jsonPath("$.data.referencedShortCodes[0]").value("ABC"));

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().question()).isEqualTo("알려줘");
		assertThat(captor.getValue().userId()).isEqualTo(7L);
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
	}

	@Test
	void 일일_상한_초과는_429다() throws Exception {
		willThrow(new V1ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
				"AI_DAILY_LIMIT_REACHED", "오늘 질문 가능 횟수(30회)를 모두 사용했어요. 내일 다시 시도해 주세요."))
				.given(quota).requireWithinDailyLimit(anyLong());

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("AI_DAILY_LIMIT_REACHED"));
	}

	@Test
	void 메시지가_비면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 마지막_메시지가_사용자_발화가_아니면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"messages\":[{\"role\":\"assistant\",\"content\":\"안녕\"}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void LLM_실패는_502_재시도_안내이고_실패도_로그로_남는다() throws Exception {
		given(agent.run(anyLong(), any())).willThrow(new IllegalStateException("Vertex HTTP 500"));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_LLM_FAILED);
		assertThat(captor.getValue().answer()).isNull();
	}
}
```

- [ ] **8-2. 킬 스위치 테스트를 같은 파일 옆에 추가한다.** 같은 파일 하단(클래스 밖)이 아니라 **별도 파일**로 만든다: `was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiChatDisabledTest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 킬 스위치 검증(설계 §7) - monitoring.brand.ai.enabled=false면 컨트롤러 빈이 등록되지 않아
 * 표면 자체가 없다(404). AD_DISCLOSURE_EXPOSE와 달리 "중립값"이 아니라 "표면 부재"인 이유:
 * 어시스턴트는 값 하나가 아니라 기능 전체라 끄면 사라지는 게 맞다.
 */
@WebMvcTest(controllers = V1BrandAiChatController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=false"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAiChatDisabledTest {

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	BrandAiAgent agent;
	@MockitoBean
	AiChatQuota quota;
	@MockitoBean
	AiChatLogRepository logRepository;

	@Test
	void 킬_스위치가_꺼져_있으면_표면이_없다() throws Exception {
		AppUserDetails principal = new AppUserDetails(new AppUser(7L, "user@example.com", "hash",
				"USER", OffsetDateTime.parse("2026-06-01T00:00:00Z")));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal)).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"messages\":[{\"role\":\"user\",\"content\":\"안녕\"}]}"))
				.andExpect(status().isNotFound());
	}
}
```

- [ ] **8-3. 실패를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.V1BrandAiChat*"` - 기대: 컴파일 실패. (`AppUser`·`SecurityConfig`의 실제 경로가 다르면 `V1BrandPostsControllerTest`의 import를 그대로 따라 고친다.)

- [ ] **8-4. 요청·응답 record를 작성한다.**

`was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatRequest.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 챗 요청(설계 §5) - 무상태라 대화 이력 전체를 매 요청에 싣는다. 서버 세션은 스코프 밖(설계 §10).
 * 검증(빈 목록·역할·길이·건수)은 컨트롤러가 수동으로 한다(브랜드 표면의 기존 관용구).
 */
public record AiChatRequest(List<AiChatMessage> messages) {
}
```

`was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/AiChatResponse.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 챗 응답(설계 §5) - 답변 텍스트와 참조한 게시물 shortCode 목록. 프론트가 shortCode로 링크를
 * 걸 수 있게 별도 필드로 뺀다(본문 파싱을 시키지 않는다).
 */
public record AiChatResponse(String answer, List<String> referencedShortCodes) {
}
```

- [ ] **8-5. `BrandAiConfig`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/BrandAiConfig.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.VertexHttpTransport;
import com.celfit.common.llm.VertexTokenProvider;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 어시스턴트 배선(설계 §3·§7). monitoring.enabled와 킬 스위치가 <b>둘 다</b> true일 때만 뜬다 -
 * {@link BrandReadRepository} 자체가 monitoring.enabled 조건부 빈이라 하나만 켜면 배선이 깨진다.
 *
 * <p>재시도를 common-llm 기본값(6회·15초 기저)보다 크게 줄인다(2회·2초): 야간 배치와 달리 이 경로는
 * 사람이 기다리는 동기 요청이라 오래 매달리는 것보다 빨리 실패해 재시도를 안내하는 편이 낫다.
 */
@Configuration
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class BrandAiConfig {

	/** 동기 챗의 재시도 횟수 - 60초 응답 계약(설계 §5) 안에 들어오도록 짧게 잡는다. */
	private static final int CHAT_MAX_ATTEMPTS = 2;
	private static final long CHAT_RETRY_BASE_MILLIS = 2_000L;
	private static final int ERROR_BODY_LOG_LIMIT = 2_000;

	@Bean
	public ChatTransport brandAiChatTransport(
			@Value("${monitoring.brand.ai.vertex-project}") String project,
			@Value("${monitoring.brand.ai.vertex-location:global}") String location,
			@Value("${monitoring.brand.ai.model:gemini-2.5-flash}") String model) {
		VertexHttpTransport http = new VertexHttpTransport(VertexTokenProvider.fromEnv(),
				VertexHttpTransport.DEFAULT_BASE_URL, CHAT_RETRY_BASE_MILLIS,
				CHAT_MAX_ATTEMPTS, ERROR_BODY_LOG_LIMIT);
		return new VertexChatTransport(http, project, location, model);
	}

	@Bean
	public GeminiChatClient brandAiChatClient(ChatTransport brandAiChatTransport, ObjectMapper objectMapper) {
		return new GeminiChatClient(brandAiChatTransport, objectMapper);
	}

	@Bean
	public BrandAiToolbox brandAiToolbox(BrandLinkRepository linkRepository,
			BrandReadRepository brandReadRepository, ObjectMapper objectMapper,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		// 광고 판정 노출은 FE와 같은 토글을 쓴다 - 화면에서 가린 값을 어시스턴트가 말하면 킬 스위치가 무의미해진다.
		// Clock은 빈이 아니라 직접 만든다 - was에 Clock 빈이 없고(생성자 직접 주입 관용구), 전역 빈을
		// 새로 등록하면 자기 fixed Clock을 띄우는 기존 통합 테스트들과 충돌한다.
		return new BrandAiToolbox(linkRepository, brandReadRepository, objectMapper,
				Clock.systemUTC(), exposeAdDisclosure);
	}

	@Bean
	public AiChatQuota aiChatQuota(AiChatLogRepository logRepository,
			AppSettingRepository settingRepository) {
		return new AiChatQuota(logRepository, settingRepository, Clock.systemUTC());
	}

	@Bean
	public BrandAiAgent brandAiAgent(GeminiChatClient brandAiChatClient, BrandAiToolbox brandAiToolbox,
			ObjectMapper objectMapper) {
		return new BrandAiAgent(brandAiChatClient, brandAiToolbox, objectMapper);
	}

	/**
	 * 챗 전용 실행 풀 - 60초 응답 계약(설계 §5)을 실제로 지키려면 요청 스레드가 아닌 곳에서 돌리고
	 * 시간 초과를 끊어야 한다. 공용 {@code ConcurrencyLimiter}(permits 4)를 쓰지 않는 이유: 60초짜리
	 * 작업이 그 벌크헤드를 물면 무관한 무거운 엔드포인트까지 함께 굶는다.
	 * 큐 없이 2 스레드 - 넘치면 즉시 거절해 429로 돌려보낸다(대기줄이 길어지면 60초 계약이 먼저 깨진다).
	 */
	@Bean("brandAiChatExecutor")
	public ThreadPoolTaskExecutor brandAiChatExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("brand-ai-chat-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
```

- [ ] **8-6. `V1BrandAiChatController`를 작성한다.** `was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiChatController.java`

```java
package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 모니터링 AI 어시스턴트 챗 표면(설계 §5) - 무상태 동기 API 하나뿐이다.
 *
 * <p>킬 스위치(설계 §7): monitoring.enabled와 monitoring.brand.ai.enabled가 둘 다 true여야 빈이
 * 등록된다. 꺼져 있으면 경로 자체가 없어 404다(브랜드 표면의 기존 게이트 관용구와 동일).
 *
 * <p>보호 장치가 세 겹이다 - 분당 버스트는 {@link RateLimiter}, 하루 총량은 {@link AiChatQuota},
 * 동시 실행은 전용 풀(brandAiChatExecutor)의 거절이다. 셋 다 429로 수렴하되 code로 구분한다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiChatController {

	/** 동기 응답 상한(설계 §5). */
	private static final int RESPONSE_TIMEOUT_SECONDS = 60;
	/** 분당 질문 수 - 하루 상한과 별개로 연타·자동화를 막는다. */
	private static final int PER_MINUTE_LIMIT = 5;
	/** 대화 이력 상한 - 무상태라 프론트가 무한히 키울 수 있어 서버가 자른다. */
	private static final int MAX_MESSAGES = 20;
	private static final int MAX_CONTENT_LENGTH = 2_000;
	private static final int BUSY_RETRY_AFTER_SECONDS = 10;

	private static final Logger log = LoggerFactory.getLogger(V1BrandAiChatController.class);

	private final BrandAiAgent agent;
	private final AiChatQuota quota;
	private final AiChatLogRepository logRepository;
	private final RateLimiter rateLimiter;
	// 타입을 Executor로 잡는다 - 테스트에서 동기 실행기(Runnable::run)로 갈아끼워 결정론을 얻는다
	private final Executor executor;

	public V1BrandAiChatController(BrandAiAgent agent, AiChatQuota quota,
			AiChatLogRepository logRepository, RateLimiter rateLimiter,
			@Qualifier("brandAiChatExecutor") Executor executor) {
		this.agent = agent;
		this.quota = quota;
		this.logRepository = logRepository;
		this.rateLimiter = rateLimiter;
		this.executor = executor;
	}

	@PostMapping("/chat")
	public ApiResponse<AiChatResponse> chat(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) AiChatRequest request) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
		long userId = principal.getUserId();
		List<AiChatMessage> messages = validate(request);
		String question = messages.get(messages.size() - 1).content();

		if (!rateLimiter.tryAcquire("ai-chat:" + userId, PER_MINUTE_LIMIT)) {
			throw V1ApiException.rateLimited();
		}
		quota.requireWithinDailyLimit(userId);

		long startedAt = System.nanoTime();
		BrandAiAgent.AgentOutcome outcome;
		try {
			outcome = runWithTimeout(userId, messages);
		} catch (RuntimeException e) {
			logRepository.insert(new AiChatLogEntry(userId, null, question, null, List.of(), 0, 0,
					elapsedMillis(startedAt), AiChatLogEntry.OUTCOME_LLM_FAILED));
			throw e;
		}

		logRepository.insert(new AiChatLogEntry(userId, outcome.brandId(), question, outcome.answer(),
				outcome.toolCalls(), outcome.promptTokens(), outcome.outputTokens(),
				elapsedMillis(startedAt), outcome.outcome()));
		return ApiResponse.ok(new AiChatResponse(outcome.answer(), outcome.referencedShortCodes()));
	}

	/**
	 * 전용 풀에서 돌리고 60초에 끊는다. 끊긴 작업 스레드는 인터럽트를 걸어도 진행 중인 HTTP 응답
	 * 수신까지는 마칠 수 있다(common-llm 전송이 인터럽트를 즉시 반영하지 않는다) - 전송 재시도를
	 * 2회로 줄여(BrandAiConfig) 그 잔류 시간을 짧게 유지한다.
	 */
	private BrandAiAgent.AgentOutcome runWithTimeout(long userId, List<AiChatMessage> messages) {
		CompletableFuture<BrandAiAgent.AgentOutcome> future;
		try {
			future = CompletableFuture.supplyAsync(() -> agent.run(userId, messages), executor);
		} catch (RejectedExecutionException e) {
			throw V1ApiException.rateLimited(BUSY_RETRY_AFTER_SECONDS);
		}
		try {
			return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("AI 챗 응답 시간 초과({}초) - userId={}", RESPONSE_TIMEOUT_SECONDS, userId);
			throw V1ApiException.badGateway("AI_TIMEOUT", "답변 생성이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요.");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof V1ApiException v1) {
				throw v1;
			}
			if (cause instanceof LlmQuotaExhaustedException) {
				log.warn("AI 챗 Vertex 쿼터 소진 - userId={}", userId);
			} else {
				log.error("AI 챗 처리 실패 - userId={}", userId, cause);
			}
			throw V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요.");
		}
	}

	private static List<AiChatMessage> validate(AiChatRequest request) {
		if (request == null || request.messages() == null || request.messages().isEmpty()) {
			throw V1ApiException.validation("질문을 입력해 주세요.");
		}
		List<AiChatMessage> messages = request.messages();
		if (messages.size() > MAX_MESSAGES) {
			throw V1ApiException.validation("대화가 너무 길어요. 새 대화로 다시 시작해 주세요.");
		}
		for (AiChatMessage message : messages) {
			if (message == null || message.content() == null || message.content().isBlank()) {
				throw V1ApiException.validation("빈 메시지는 보낼 수 없어요.");
			}
			if (message.content().length() > MAX_CONTENT_LENGTH) {
				throw V1ApiException.validation("메시지가 너무 길어요. 더 짧게 나눠서 물어봐 주세요.");
			}
			if (!AiChatMessage.ROLE_USER.equals(message.role())
					&& !AiChatMessage.ROLE_ASSISTANT.equals(message.role())) {
				throw V1ApiException.validation("메시지 역할이 올바르지 않아요.");
			}
		}
		if (!AiChatMessage.ROLE_USER.equals(messages.get(messages.size() - 1).role())) {
			throw V1ApiException.validation("마지막 메시지는 사용자 질문이어야 해요.");
		}
		return messages;
	}

	private static long elapsedMillis(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
```

- [ ] **8-7. `application.yml`에 설정을 추가한다.** `was/src/main/resources/application.yml`의 `monitoring.brand.ad-disclosure` 블록 **아래**(`brand:` 하위, 같은 들여쓰기)에 다음을 넣는다.

```yaml
    ai:
      enabled: ${BRAND_AI_ENABLED:false}   # AI 어시스턴트 킬 스위치(설계 §7) - false면 컨트롤러 빈이
                       # 등록되지 않아 /v1/brand-monitoring/ai/chat가 404다. monitoring.enabled와
                       # 둘 다 true여야 배선이 뜬다(BrandReadRepository가 monitoring 조건부 빈이라).
      vertex-project: ${BRAND_AI_VERTEX_PROJECT:}   # 켤 때 필수. SA 키는 GOOGLE_APPLICATION_CREDENTIALS
      vertex-location: ${BRAND_AI_VERTEX_LOCATION:global}
      model: ${BRAND_AI_MODEL:gemini-2.5-flash}     # function calling 지원 모델이어야 한다
```

- [ ] **8-8. 통과를 확인한다.** `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.ai.V1BrandAiChat*"` - 기대: BUILD SUCCESSFUL, 테스트 6건 통과(챗 5 + 킬 스위치 1).

- [ ] **8-9. 커밋한다.** `git add -A && git commit -m "feat(was): 브랜드 모니터링 AI 챗 API·킬 스위치·배선 추가"`

---

## Task 9: 마무리 - 문서 갱신과 전체 검증

**Files:**
- Modify: `DECISIONS.md`
- Modify: `ARCHITECTURE.md`
- Test: 전체

### 스텝

- [ ] **9-1. `DECISIONS.md` 맨 위에 결정 기록을 추가한다.** 기존 항목의 날짜·서식(맨 위가 최신)을 그대로 따라 다음 내용을 담는다: (1) 브랜드 모니터링 AI 어시스턴트 PoC를 was에 툴 콜링 에이전트로 구현, (2) common-llm은 무수정 - `VertexHttpTransport.post`가 임의 JSON 본문을 통과시켜 function calling에 확장이 불필요했음, (3) 킬 스위치는 `BRAND_AI_ENABLED` env(기본 false)로 표면 자체를 없애는 방식, (4) 일일 상한 기준값은 app_setting `ai.chat.daily-limit`=30, (5) 툴 소유 검증은 `BrandAiToolbox` 안에서 강제하며 `BrandReadRepository`는 여전히 brandId를 검증하지 않는다는 전제 유지.

- [ ] **9-2. `ARCHITECTURE.md`의 was 모듈 설명에 한 줄을 더한다.** was가 `common-llm`을 의존하게 된 것이 구조 변경이다(기존에는 analytics·monitoring만 의존했다). 모듈 의존 관계를 적은 절에 was → common-llm 간선을 추가하고, "프롬프트·툴 정의·에이전트 루프는 was 소관, common-llm은 전송만"이라는 원칙이 유지됨을 명시한다.

- [ ] **9-3. was 모듈 전체 테스트를 돌린다.** `./gradlew :was:test` - 기대: BUILD SUCCESSFUL. 실패가 대량이면 코드 결함으로 오진하기 전에 `echo $DOCKER_HOST`부터 확인한다(CLAUDE.md 함정).

- [ ] **9-4. PR 직전 전체 테스트를 돌린다.** `./gradlew test` - 기대: BUILD SUCCESSFUL. (모듈 4개가 각자 Testcontainers를 띄우므로 colima 자원이 8 CPU / 12 GiB 이상인지 먼저 확인한다.)

- [ ] **9-5. 마이그레이션 가드를 자가 점검한다.** `ls was/src/main/resources/db/migration/app/ | sort | tail -3`으로 신규 파일이 최대 버전인지, 파일에 `DROP`·`RENAME`·`SET NOT NULL`이 없는지(순수 expand 단계라 있어선 안 된다) 확인한다. 기대: `V20260827110158__ai_chat_logs.sql`이 마지막이고 파괴적 DDL 없음.

- [ ] **9-6. 이 계획 문서를 아카이브로 옮긴다.** `git mv docs/superpowers/plans/2026-08-27-brand-monitoring-ai-assistant-poc.md docs/superpowers/plans/archive/` 후 문서 첫머리 상태 헤더를 `> 상태: ✅ 구현됨`으로 고친다. 설계 스펙(`specs/2026-08-27-...`)은 트랙이 완결될 때까지 활성 위치에 남긴다.

- [ ] **9-7. 커밋한다.** `git add -A && git commit -m "docs: AI 어시스턴트 PoC 결정 기록·구조 갱신 및 계획 문서 아카이브"`

- [ ] **9-8. 브랜치를 push하고 보고한다.** `git push -u origin HEAD`. **PR은 열지 않는다** - 사용자의 명시 승인 후에만 연다.

---

## 배포 시 확인 사항 (구현 범위 밖, 인수인계용)

- 스테이징·운영 compose에 `BRAND_AI_ENABLED`·`BRAND_AI_VERTEX_PROJECT` env를 추가해야 기능이 켜진다. 기본값이 false라 **env를 넣지 않으면 배포해도 표면이 없다** - 마이그레이션만 먼저 적용되고 API는 404다(의도된 안전 기본값).
- was 컨테이너에 `GOOGLE_APPLICATION_CREDENTIALS`(SA 키 경로)가 마운트돼 있어야 한다. monitoring 컨테이너에는 이미 있으나 was에는 없을 수 있다 - `deploy/compose.yaml`의 was 서비스에서 확인할 것. 없는데 `BRAND_AI_ENABLED=true`로 켜면 `VertexTokenProvider.fromEnv()`가 기동 시 예외를 던져 **was가 뜨지 않는다**(monitoring의 그레이스풀 폴백과 달리 여기는 폴백 경로를 두지 않았다 - AI Studio 무료 키 쿼터 공유가 08-18 429 폭주의 원인이었으므로 되살리지 않는다).
- Vertex 비용은 Monitoring 토큰 메트릭이 아니라 이 PoC의 `app.ai_chat_logs.prompt_tokens`·`output_tokens` 합으로 별도 관측 가능하다. 첫 주 일별 합계를 확인해 상한(30회)이 적정한지 재판단한다.
