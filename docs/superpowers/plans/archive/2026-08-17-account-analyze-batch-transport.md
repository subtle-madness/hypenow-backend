# 계정 카피(ACCOUNT_ANALYZE) Vertex 배치 전송 전환 구현 계획

> 상태: ✅ 구현됨
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) 구문으로 추적한다.

**Goal:** 계정 카피 잡(ACCOUNT_ANALYZE, KST 07:00)의 Vertex 온라인 호출을 콘텐츠 분석과 동일한 배치 제출→수거 패턴으로 전환해 LLM 비용을 반감한다(08-17 규명: 8일 주기 ~2,700건 펄스 ~$4.3 + 일상 트리클, 온라인 유지 시 월 ~$19 → 배치 전환 시 절감 ~월 $9~10).

**Architecture:** 2026-08-11 콘텐츠 배치 전환(`ContentAnalysisJob.submitBatch` → `content_batch_jobs` → `ContentBatchCollectJob`)을 계정 카피에 그대로 미러한다. 새 테이블 `account_batch_jobs`(콘텐츠와 분리 — `timely` 없음), 새 토글 `analytics.account-analyze-transport`(기본 `online`, 콘텐츠 토글과 독립), JSONL 조립·결과 해석 헬퍼 `AccountBatchLines`(콘텐츠의 `GeminiBatchLines` 동형), 수거 잡 `AccountBatchCollectJob`. 수거 스케줄은 기존 `BATCH_COLLECT` 크론(KST 05:10~11:40 30분 간격)에 얹는다 — 새 크론·compose 변경 없음.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcTemplate, Flyway(UTC 타임스탬프 채번), Testcontainers(PostgreSQL), Jackson 3(`tools.jackson.*`), Vertex AI Batch API(`GeminiBatchApi`).

---

## 배경 (실행자가 알아야 할 사실)

- **왜**: 08-11에 야간 콘텐츠 분석(ANALYZE·LATE_BACKFILL)만 배치로 전환됐고, 계정 카피는 "배치용 JSONL 빌더·파서 부재"로 의도적으로 제외됐다([AnalyticsSettings.java:50-52](../../../analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java) 주석). 08-17 조사로 계정 카피가 8일 주기 대량 온라인 펄스(회당 ~2,700건, ~$4.3)의 범인으로 확정됐다 — 쿨다운 7일 + 잡이 07:00 정각에 돌아 D일 코호트가 D+7 판정(`analyzed_at < now()-7d` 엄격 미만)을 근소하게 놓치고 D+8에 통째로 재도래한다. 운영 `analytics.account-analyze-batch-limit=30000`(07-28 상향·**원복 금지** — 사용자 확정)이라 펄스가 한 아침에 전량 처리된다.
- **미러 원본**: 제출은 [ContentAnalysisJob.java:197-264](../../../analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java) `submitBatch()`, 수거는 [ContentBatchCollectJob.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/ContentBatchCollectJob.java), 라인 조립·해석은 [GeminiBatchLines.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/GeminiBatchLines.java).
- **전환 대상**: [AccountAnalysisJob.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java)(대상 자격 `ELIGIBLE_WHERE`·`analyzeOne`), LLM 호출은 [GeminiAccountSynthesizer.java](../../../analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java).
- **콘텐츠보다 단순한 점**: 계정 카피는 이미지 입력이 없어(`image=null` 고정) VLM 폴백 가드가 불필요하고, 기준선(baseline) 9종 스냅샷 대신 사이드카에 3개 필드만 실으면 된다.
- **콘텐츠와 다른 점(함정)**:
  1. 시스템 프롬프트가 **계정마다 다르다** — `instructions(vocab, confidence)`의 `PerfConfidence`가 계정별 판정이라, 콘텐츠처럼 시스템 문자열 하나를 전 라인에 공유할 수 없다. 라인마다 각자 조립한다.
  2. 응답 파싱이 static으로 분리돼 있지 않다 — `synthesize()`에 `om.readValue(out, AccountCopy.class)` 인라인. 먼저 `parse()`로 추출해야 배치가 재사용한다(Task 3).
  3. `RESPONSE_SCHEMA`·`MAX_OUTPUT_TOKENS`가 package-private인데 배치 헬퍼는 `analyze` 패키지다 — public 승격 필요(Task 3). (콘텐츠의 `GeminiContentAnalyzer` 상수들이 public인 것과 동형.)
  4. `account_analyses`는 이력 테이블이라 **유니크 제약이 없다** — 콘텐츠의 `ON CONFLICT DO NOTHING` 같은 DB 멱등이 없다. 중복 제출 방어는 "제출 전 pending 수거"(콘텐츠와 동일)와 `ELIGIBLE_WHERE` 자연 재대상에 의존한다. 수거 잡이 같은 배치를 두 번 처리하지 않는 것(pending→collected 단방향 전이)이 유일한 수거측 멱등이므로, **전체 스트리밍 성공 후에만 상태 전이**하는 순서를 지킨다.
- **재대상 안전망**: 배치가 실패하거나 일부 라인이 파싱 불가면 `account_analyses`에 행이 안 쌓이고, `ELIGIBLE_WHERE`(분석 이력 없음 OR stale)가 다음 실행에서 자동 재대상한다 — 별도 재시도 로직을 만들지 않는다(콘텐츠와 동일 관용, [ContentBatchCollectJob.java:84-85](../../../analytics/src/main/java/com/celfit/analytics/analyze/ContentBatchCollectJob.java) 주석).
- **사이드카는 DB 컬럼**: analytics 컨테이너에 쓰기 볼륨이 없어 로컬 파일은 배포 시 유실 좀비를 만든다(08-11 리뷰 지적) — `account_batch_jobs.sidecar_jsonl` text 컬럼에 저장.
- **스코프 제외**: 8일 펄스 주기 자체의 보정(쿨다운 경계 완화)은 하지 않는다 — 배치 전환이면 펄스 비용이 반감되고 서빙 영향이 없다. 콘텐츠 배치 경로·`content_batch_jobs`는 일절 건드리지 않는다.
- **작업 브랜치**: develop에서 `feat/account-analyze-batch-transport` 분기. **PR은 사용자 명시 승인 후에만 연다**(전역 규칙 — push·보고까지만).

## 파일 구조

| 구분 | 경로 | 책임 |
|---|---|---|
| Create | `analytics/src/main/resources/db/migration/analysis/V<UTC>__account_batch_jobs.sql` | 배치 상태 테이블 |
| Create | `crawler/src/main/resources/db/migration/V<UTC>__account_analyze_transport_setting.sql` | 토글 기준값 시드 |
| Modify | `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java` | 토글 읽기 메서드 |
| Modify | `analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java` | `parse()` static 추출, 상수 public |
| Create | `analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchLines.java` | JSONL 요청/사이드카 조립, 결과 라인 해석·저장 |
| Create | `analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchCollectJob.java` | pending 순회·수거·상태 전이 |
| Modify | `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java` | `prepare()` 분리, 배치 제출 분기 |
| Modify | `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` | batchApi 배선, 수거 잡 빈 |
| Modify | `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java` | BATCH_COLLECT가 계정 수거도 실행 |
| Test | `analytics/src/test/java/com/celfit/analytics/analyze/AccountBatchLinesTest.java` | 순수 함수 단위 테스트 |
| Test | `analytics/src/test/java/com/celfit/analytics/analyze/AccountBatchCollectJobTest.java` | 수거 통합 테스트(Testcontainers) |
| Test(Modify) | `analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java` | 배치 제출 분기 테스트 추가 |
| Modify | `DECISIONS.md` | 결정 기록 1항목 |

테스트 실행 전제(로컬): `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (미설정 시 Testcontainers 대량 실패 — CLAUDE.md 함정 참조).

---

### Task 1: Flyway 마이그레이션 2개 (account_batch_jobs + 토글 시드)

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V<UTC>__account_batch_jobs.sql`
- Create: `crawler/src/main/resources/db/migration/V<UTC>__account_analyze_transport_setting.sql`

- [ ] **Step 1: UTC 타임스탬프 채번**

```bash
date -u +%Y%m%d%H%M%S
```

출력값(14자리)을 두 파일 모두의 `<UTC>` 자리에 사용한다(같은 값이어도 무방 — 버전 공간이 모듈별로 독립). **KST로 채번 금지**(미래 번호 선점 → out-of-order 크래시루프 전력, CLAUDE.md).

- [ ] **Step 2: analysis 마이그레이션 작성**

`analytics/src/main/resources/db/migration/analysis/V<UTC>__account_batch_jobs.sql`:

```sql
-- 계정 카피 배치 전송(2026-08-17 계획) 상태 테이블 — content_batch_jobs(V20260811021726) 동형.
-- timely 컬럼은 계정 카피에 없음(콘텐츠 전용 개념). 사이드카는 DB 컬럼 보관(컨테이너에 쓰기 볼륨 없음).
CREATE TABLE account_batch_jobs (
    id              bigserial PRIMARY KEY,
    batch_name      text NOT NULL,
    submitted_count int NOT NULL,
    status          text NOT NULL DEFAULT 'pending',
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    collected_at    timestamptz,
    note            text,
    sidecar_jsonl   text,
    CONSTRAINT account_batch_jobs_status_check CHECK (status IN ('pending', 'collected', 'failed'))
);
CREATE INDEX account_batch_jobs_status_idx ON account_batch_jobs (status);
```

- [ ] **Step 3: crawler 토글 시드 마이그레이션 작성**

`crawler/src/main/resources/db/migration/V<UTC>__account_analyze_transport_setting.sql` (V16 패턴 — 기준값은 마이그레이션 시드, 런타임 토글만 수동 UPDATE):

```sql
-- 계정 카피 전송 방식 기준값(2026-08-17) — online(기본)|batch. 콘텐츠 토글(analytics.analyze-transport)과
-- 독립: 08-11 콘텐츠 전환 때 계정 카피는 의도적으로 제외됐던 후속 트랙. 전환·롤백은 수동 UPDATE.
INSERT INTO app_setting (key, value)
VALUES ('analytics.account-analyze-transport', 'online')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 4: 채번 경합·가드 확인**

```bash
./deploy/scripts/check-migration-safety.sh
```

Expected: 통과(신규 CREATE TABLE·INSERT는 expand 단계 — destructive 아님). 스크립트가 없거나 인자가 다르면 `deploy/README.md §5-1`을 따른다. 추가로 `git log origin/develop --oneline -5 -- analytics/src/main/resources/db/migration/analysis/`로 develop에 더 큰 번호가 없는지 확인.

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/resources/db/migration/analysis/ crawler/src/main/resources/db/migration/
git commit -m "feat(analytics): 계정 카피 배치 전송 상태 테이블·토글 시드 마이그레이션"
```

---

### Task 2: AnalyticsSettings 토글 메서드

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`

- [ ] **Step 1: 키 상수·기본값·읽기 메서드 추가**

기존 `KEY_ANALYZE_TRANSPORT`(line 53 부근)·`analyzeTransport()`/`batchTransportEnabled()`(line 152-160) 바로 옆에, 같은 관용으로:

```java
/**
 * 계정 카피 전송 방식 — online(기본)|batch. 콘텐츠 토글(analytics.analyze-transport)과 독립
 * (08-11 콘텐츠 전환 때 계정 카피는 의도적으로 제외 — 2026-08-17 후속 전환).
 */
static final String KEY_ACCOUNT_ANALYZE_TRANSPORT = "analytics.account-analyze-transport";
static final String DEFAULT_ACCOUNT_ANALYZE_TRANSPORT = "online";

/** 잡 실행 시점마다 매번 읽는다(캐시 없음) — 재기동 없이 online↔batch 전환. */
public String accountAnalyzeTransport() {
	return read(KEY_ACCOUNT_ANALYZE_TRANSPORT).orElse(DEFAULT_ACCOUNT_ANALYZE_TRANSPORT);
}

/** true면 계정 카피(ACCOUNT_ANALYZE)가 Vertex 배치 제출 경로로 전환된다. */
public boolean accountBatchTransportEnabled() {
	return "batch".equals(accountAnalyzeTransport());
}
```

기존 `batchTransportEnabled()`의 javadoc "콘텐츠 분석에만 적용, 계정 카피는 대상 아님" 주석에는 후속 포인터 한 줄을 덧붙인다: `계정 카피는 accountBatchTransportEnabled() 별도 토글(2026-08-17).`

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :analytics:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java
git commit -m "feat(analytics): 계정 카피 전송 방식 토글 accountBatchTransportEnabled 추가"
```

---

### Task 3: GeminiAccountSynthesizer — parse() static 추출·상수 public 승격

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java`

- [ ] **Step 1: 상수 public 승격 + parse() 추출**

`GeminiAccountSynthesizer.java`에서 (a) `MAX_OUTPUT_TOKENS`·`RESPONSE_SCHEMA`를 `public static final`로 승격(배치 헬퍼가 `analyze` 패키지에서 참조 — `GeminiContentAnalyzer` 상수들과 동형), (b) 파싱을 static으로 분리:

```java
public static final int MAX_OUTPUT_TOKENS = 4096;

public static final String RESPONSE_SCHEMA = """
		...(기존 내용 그대로)...""";

/** 응답 JSON → AccountCopy — 온라인(synthesize)·배치 수거(AccountBatchLines)가 공유하는 단일 파서. */
public static AccountCopy parse(ObjectMapper om, String json) {
	return om.readValue(json, AccountCopy.class);
}

@Override
public AccountCopy synthesize(AccountToAnalyze account) {
	String out = api.generateJson(model.get(), instructions(vocab.get(), account.confidence()),
			userText(account), null, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
	return parse(om, out);
}
```

- [ ] **Step 2: 기존 테스트 회귀 확인**

```bash
./gradlew :analytics:test --tests "*GeminiAccountSynthesizer*" --tests "*AccountAnalysisJob*"
```

Expected: 전부 PASS (동작 불변 리팩터). 해당 패턴의 테스트가 없으면 `./gradlew :analytics:compileJava :analytics:compileTestJava`로 대체.

- [ ] **Step 3: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java
git commit -m "refactor(analytics): 계정 카피 응답 파서 static 분리 — 배치 수거 재사용 준비"
```

---

### Task 4: AccountBatchLines — 라인 조립·해석 헬퍼 (순수 함수부)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchLines.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/AccountBatchLinesTest.java`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`AccountBatchLinesTest.java` (Testcontainers 불필요 — 순수 함수만):

```java
package com.celfit.analytics.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AccountBatchLinesTest {

	final ObjectMapper om = new ObjectMapper();

	@Test
	void 요청_라인은_key와_계정별_시스템·유저_텍스트·스키마를_담는다() {
		ObjectNode line = AccountBatchLines.requestLine(om, "beauty_kim", "시스템 지시문", "유저 텍스트");
		assertThat(line.path("key").asString()).isEqualTo("beauty_kim");
		assertThat(line.path("request").path("systemInstruction").path("parts").path(0)
				.path("text").asString()).isEqualTo("시스템 지시문");
		assertThat(line.path("request").path("contents").path(0).path("parts").path(0)
				.path("text").asString()).isEqualTo("유저 텍스트");
		JsonNode gen = line.path("request").path("generationConfig");
		assertThat(gen.path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(gen.path("maxOutputTokens").asInt())
				.isEqualTo(com.celfit.analytics.llm.GeminiAccountSynthesizer.MAX_OUTPUT_TOKENS);
	}

	@Test
	void 사이드카는_라운드트립되고_null_필드를_보존한다() {
		OffsetDateTime posted = OffsetDateTime.parse("2026-08-16T07:00:00+09:00");
		String jsonl = om.writeValueAsString(
				AccountBatchLines.sidecarLine(om, "a_handle", posted, 34L, "COMPARABLE")) + "\n"
				+ om.writeValueAsString(
				AccountBatchLines.sidecarLine(om, "b_handle", null, null, "NO_SPONSORED")) + "\n";
		Map<String, Map<String, String>> parsed = AccountBatchLines.parseSidecar(om, jsonl);
		assertThat(parsed.get("a_handle").get("last_posted_at")).isEqualTo(posted.toString());
		assertThat(parsed.get("a_handle").get("analyzed_count")).isEqualTo("34");
		assertThat(parsed.get("a_handle").get("ad_situation")).isEqualTo("COMPARABLE");
		assertThat(parsed.get("b_handle").get("last_posted_at")).isNull();
		assertThat(parsed.get("b_handle").get("analyzed_count")).isNull();
	}

	@Test
	void Vertex_출력에_key가_없으면_에코된_유저_텍스트_첫_줄에서_핸들을_복원한다() {
		ObjectNode node = om.createObjectNode();
		node.putObject("request").putArray("contents").addObject().putArray("parts")
				.addObject().put("text", "계정: @beauty_kim (광고 활동: 비교 가능)\n계정 지표: {...}");
		assertThat(AccountBatchLines.handleFromEcho(node)).isEqualTo("beauty_kim");
	}
}
```

주의: `ad_situation` 테스트 값(`"COMPARABLE"` 등)은 실제 `com.celfit.analytics.llm.AdSituation` enum 상수명과 일치해야 한다 — 파일을 열어 실명으로 교체할 것(이 계획 작성 시점엔 미확인).

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountBatchLinesTest"
```

Expected: 컴파일 실패("AccountBatchLines를 찾을 수 없음").

- [ ] **Step 3: 순수 함수부 구현**

`AccountBatchLines.java` — `GeminiBatchLines`(같은 패키지)의 동형 구조. `state()`·`resultFileOf()`·`abbreviate()`는 새로 만들지 말고 `GeminiBatchLines`의 package-private static을 그대로 호출한다(DRY):

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.llm.GeminiAccountSynthesizer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 계정 카피 배치 JSONL 조립·결과 해석 헬퍼 — 콘텐츠의 {@link GeminiBatchLines} 동형(2026-08-17).
 * 콘텐츠와 달리 시스템 지시문이 계정별(PerfConfidence 판정 포함)이라 라인마다 받고, 사이드카는
 * 기준선 대신 저장 시점 복원용 3필드(last_posted_at·analyzed_count·ad_situation)만 싣는다.
 */
final class AccountBatchLines {

	private static final Logger log = LoggerFactory.getLogger(AccountBatchLines.class);

	/** 사이드카 키 — AccountAnalysisWriter.insert가 LLM 응답 외에 요구하는 제출 시점 스냅샷. */
	static final List<String> SIDECAR_KEYS = List.of("last_posted_at", "analyzed_count", "ad_situation");

	/** GeminiAccountSynthesizer.userText 첫 줄("계정: @{handle} (…")에서 핸들 복원. */
	private static final java.util.regex.Pattern ECHO_HANDLE =
			java.util.regex.Pattern.compile("^계정: @(\\S+) \\(");

	private AccountBatchLines() {
	}

	/** JSONL 요청 라인 — key=handle. 시스템 지시문은 계정별(confidence 포함)이라 호출자가 조립해 넘긴다. */
	static ObjectNode requestLine(ObjectMapper om, String handle, String system, String userText) {
		ObjectNode line = om.createObjectNode();
		line.put("key", handle);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", userText);
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiAccountSynthesizer.RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", GeminiAccountSynthesizer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/** 사이드카 라인 — 수거 시점에 AccountAnalysisWriter.insert 인자를 복원하기 위한 기록. */
	static ObjectNode sidecarLine(ObjectMapper om, String handle, OffsetDateTime lastPostedAt,
			Long analyzedCount, String adSituationName) {
		ObjectNode line = om.createObjectNode();
		line.put("handle", handle);
		if (lastPostedAt == null) {
			line.putNull("last_posted_at");
		} else {
			line.put("last_posted_at", lastPostedAt.toString());
		}
		if (analyzedCount == null) {
			line.putNull("analyzed_count");
		} else {
			line.put("analyzed_count", analyzedCount.toString());
		}
		line.put("ad_situation", adSituationName);
		return line;
	}

	/** 사이드카 JSONL 파싱 — handle → 필드맵. GeminiBatchLines.parseSidecar와 동형. */
	static Map<String, Map<String, String>> parseSidecar(ObjectMapper om, String contents) {
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (String line : contents.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = om.readTree(line);
			Map<String, String> vals = new LinkedHashMap<>();
			for (String k : SIDECAR_KEYS) {
				JsonNode v = node.path(k);
				vals.put(k, v.isNull() || v.isMissingNode() ? null : v.asString());
			}
			out.put(node.path("handle").asString(), vals);
		}
		return out;
	}

	/** Vertex 출력엔 key가 없다 — 에코된 request 유저 텍스트 첫 줄에서 복원(콘텐츠의 shortCodeFromEcho 동형). */
	static String handleFromEcho(JsonNode node) {
		JsonNode parts = node.path("request").path("contents").path(0).path("parts");
		for (JsonNode part : parts) {
			String text = part.path("text").asString("");
			java.util.regex.Matcher m = ECHO_HANDLE.matcher(text);
			if (m.find()) {
				return m.group(1);
			}
		}
		return "";
	}
}
```

(`processResultLine`은 Task 5에서 이 클래스에 추가한다 — DB가 필요해 통합 테스트로 다룬다.)

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountBatchLinesTest"
```

Expected: 3개 PASS.

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchLines.java \
    analytics/src/test/java/com/celfit/analytics/analyze/AccountBatchLinesTest.java
git commit -m "feat(analytics): 계정 카피 배치 JSONL 조립 헬퍼 AccountBatchLines"
```

---

### Task 5: processResultLine + AccountBatchCollectJob (수거)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchLines.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/AccountBatchCollectJob.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/AccountBatchCollectJobTest.java`

- [ ] **Step 1: 기존 콘텐츠 수거 테스트를 정독**

`analytics/src/test/java/com/celfit/analytics/analyze/ContentBatchCollectJobTest.java`를 읽는다 — 컨테이너·스키마 셋업, fake `GeminiBatchApi` 구성(`succeededApi(resultFile, resultJsonl)`, `stateOnlyApi(state)`)을 그대로 미러할 것이므로 헬퍼 시그니처를 확인한다.

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`AccountBatchCollectJobTest.java` — Testcontainers, `ContentBatchCollectJobTest`의 셋업 관용(단일 PostgreSQLContainer, Flyway로 analysis 마이그레이션 적용 또는 수동 DDL — 기존 파일이 하는 방식 그대로)을 미러. 케이스 4개:

```java
@Test
void SUCCEEDED_배치는_결과를_저장하고_status를_collected로_전이하고_사이드카를_비운다() {
	// given: account_batch_jobs에 pending 1행(사이드카에 handle=beauty_kim, last_posted_at,
	// analyzed_count=34, ad_situation 실명), fake api는 SUCCEEDED + 결과 JSONL 1라인
	// (key=beauty_kim, response.candidates[0].content.parts[0].text = 유효한 AccountCopy JSON)
	// when: job.run()
	// then: account_analyses에 1행 INSERT(handle=beauty_kim, input_analyzed_count=34,
	//       input_last_posted_at=사이드카 값, copy_version=CopyRules.VERSION),
	//       account_batch_jobs.status='collected', sidecar_jsonl IS NULL
}

@Test
void 실행_중_배치는_pending을_유지하고_다운로드를_호출하지_않는다() {
	// stateOnlyApi("JOB_STATE_RUNNING") — downloadResults 호출되면 예외 나는 fake로 검증(콘텐츠 동형)
	// then: status='pending' 유지, processed=0
}

@Test
void 실패_상태_배치는_failed로_전이하고_note에_상태를_남긴다() {
	// stateOnlyApi("JOB_STATE_FAILED") → status='failed', note LIKE '%JOB_STATE_FAILED%', sidecar_jsonl NULL
}

@Test
void 사이드카_유실_pending은_failed로_접히고_좀비로_남지_않는다() {
	// sidecar_jsonl=NULL인 pending + SUCCEEDED api → status='failed'(다운로드 진입 전),
	// account_analyses 0행 — ELIGIBLE_WHERE 자연 재대상이 복구 경로
}
```

각 케이스의 given/when/then 주석을 실제 코드로 채운다(fake·assert는 콘텐츠 테스트에서 복사·치환). 유효한 AccountCopy JSON 예: `{"tagline":"저자극 스킨케어 리뷰","traits":["성분 분석"],"perfSummary":"성과 요약","contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}`.

- [ ] **Step 3: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountBatchCollectJobTest"
```

Expected: 컴파일 실패("AccountBatchCollectJob를 찾을 수 없음").

- [ ] **Step 4: processResultLine을 AccountBatchLines에 추가**

```java
/**
 * 결과 한 줄 처리: 파싱 → isValid 가드 → account_analyses INSERT. 콘텐츠와 달리 DB 유니크가
 * 없으므로(이력 테이블) 멱등은 수거 잡의 pending→collected 단방향 전이에 의존한다.
 *
 * @return true=저장 성공, false=실패(파싱 불가·사이드카 부재·빈 카피 — 다음 실행이 재대상 흡수)
 */
static boolean processResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
		Map<String, Map<String, String>> sidecar, String model, Set<String> vocabulary) {
	try {
		JsonNode node = om.readTree(line);
		String vertexStatus = node.path("status").asString("");
		if (!vertexStatus.isEmpty()) {
			log.warn("배치 실패 라인 (status={}): {}", vertexStatus, GeminiBatchLines.abbreviate(line));
			return false;
		}
		String handle = node.path("key").asString("");
		if (handle.isEmpty()) {
			handle = handleFromEcho(node);
		}
		JsonNode text = node.path("response").path("candidates").path(0)
				.path("content").path("parts").path(0).path("text");
		if (handle.isEmpty() || text.isMissingNode()) {
			log.warn("결과 라인 해석 불가/오류 응답: {}", GeminiBatchLines.abbreviate(line));
			return false;
		}
		AccountCopy copy = GeminiAccountSynthesizer.parse(om, text.asString());
		if (!AccountAnalysisWriter.isValid(copy)) {
			log.warn("빈 카피 — 저장 생략: {}", handle);
			return false;
		}
		Map<String, String> side = sidecar.get(handle);
		if (side == null) {
			log.warn("사이드카에 없는 key: {}", handle);
			return false;
		}
		OffsetDateTime lastPostedAt = side.get("last_posted_at") == null
				? null : OffsetDateTime.parse(side.get("last_posted_at"));
		Long analyzedCount = side.get("analyzed_count") == null
				? null : Long.valueOf(side.get("analyzed_count"));
		AdSituation adSituation = AdSituation.valueOf(side.get("ad_situation"));
		AccountAnalysisWriter.insert(analysis, om, handle, OffsetDateTime.now(), model,
				lastPostedAt, analyzedCount, copy, adSituation, vocabulary);
		return true;
	} catch (Exception e) {
		log.warn("결과 라인 저장 실패: {}", GeminiBatchLines.abbreviate(line), e);
		return false;
	}
}
```

주의: `AccountAnalysisWriter.insert`의 실제 파라미터 순서·타입은 [AccountAnalysisWriter.java:56-78](../../../analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisWriter.java)를 열어 그대로 맞춘다(위 코드는 조사 시점 시그니처 기준). `analyzedAt`은 **수거 시점** `OffsetDateTime.now()` — 온라인 경로(analyzeOne line 163)와 동일 관용이고, `ELIGIBLE_WHERE` 쿨다운 기준 시각이 된다.

- [ ] **Step 5: AccountBatchCollectJob 구현**

`ContentBatchCollectJob` 동형 — 테이블명·헬퍼·의존만 치환:

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 계정 카피 배치 수거 잡(2026-08-17) — 콘텐츠의 {@link ContentBatchCollectJob} 동형.
 * account_batch_jobs의 pending 행을 순회해 상태를 확인하고, 완료분은
 * {@link AccountBatchLines#processResultLine}으로 파싱·저장 후 상태를 전이시킨다.
 * 멱등: pending 행만 대상 — collected/failed로 전이된 배치는 다시 건드리지 않는다.
 */
public class AccountBatchCollectJob {

	private static final Logger log = LoggerFactory.getLogger(AccountBatchCollectJob.class);

	private final JdbcTemplate analysis;
	private final GeminiBatchApi batchApi; // null이면 배치 미지원 프로바이더 — run()이 no-op
	private final TraitTaxonomyLoader traitLoader;
	private final AnalyticsSettings settings;
	private final ObjectMapper om = new ObjectMapper();

	public AccountBatchCollectJob(DataSource analysisDataSource, GeminiBatchApi batchApi,
			TraitTaxonomyLoader traitLoader, AnalyticsSettings settings) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.batchApi = batchApi;
		this.traitLoader = traitLoader;
		this.settings = settings;
	}

	/** @return processed=이번 실행에서 새로 저장한 건수, failed=결과 판독 실패 건수. 실행 중 배치는 no-op. */
	public JobResult run() {
		if (batchApi == null) {
			return new JobResult(0, 0, false);
		}
		List<Map<String, Object>> pending = analysis.queryForList("""
				SELECT id, batch_name, sidecar_jsonl FROM account_batch_jobs
				WHERE status = 'pending' ORDER BY submitted_at""");
		int collected = 0;
		int failed = 0;
		for (Map<String, Object> row : pending) {
			long id = ((Number) row.get("id")).longValue();
			try {
				int[] r = collectOne(id, (String) row.get("batch_name"), (String) row.get("sidecar_jsonl"));
				collected += r[0];
				failed += r[1];
			} catch (Exception e) {
				failed++;
				log.error("계정 배치 수거 실패 — id={} 다음 사이클에서 재시도", id, e);
			}
		}
		return new JobResult(collected, failed, false);
	}

	private int[] collectOne(long id, String batchName, String sidecarJsonl) {
		JsonNode batch = om.readTree(batchApi.getBatch(batchName));
		String state = GeminiBatchLines.state(batch);
		if (state == null || state.endsWith("_RUNNING") || state.endsWith("_PENDING")
				|| state.endsWith("_QUEUED") || "JOB_STATE_UNSPECIFIED".equals(state)) {
			return new int[] {0, 0}; // 아직 실행 중 — pending 유지, 다음 사이클에서 재확인
		}
		if (!state.endsWith("_SUCCEEDED")) {
			markFailed(id, "배치 실패 상태: " + state);
			return new int[] {0, 1};
		}
		String resultFile;
		try {
			resultFile = GeminiBatchLines.resultFileOf(batch);
		} catch (IllegalStateException e) {
			markFailed(id, "결과 파일 이름을 찾지 못함");
			return new int[] {0, 1};
		}
		if (sidecarJsonl == null || sidecarJsonl.isBlank()) {
			markFailed(id, "사이드카 유실 — 재분석은 ELIGIBLE_WHERE 자연 재대상");
			return new int[] {0, 1};
		}
		Map<String, Map<String, String>> sidecar;
		try {
			sidecar = AccountBatchLines.parseSidecar(om, sidecarJsonl);
		} catch (Exception e) {
			markFailed(id, "사이드카 파싱 실패");
			return new int[] {0, 1};
		}
		String model = settings.activeLlmModel();
		java.util.Set<String> vocabulary = traitLoader.get().names();
		java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
		batchApi.downloadResults(resultFile, line -> {
			if (line.isBlank()) {
				return;
			}
			if (AccountBatchLines.processResultLine(analysis, om, line, sidecar, model, vocabulary)) {
				ok.incrementAndGet();
			} else {
				bad.incrementAndGet();
			}
		});
		// 전체 스트리밍 성공 후에만 전이 — 부분 처리 후 상태만 잘못 전이되는 레이스 방지(콘텐츠 동형)
		analysis.update("UPDATE account_batch_jobs SET status = 'collected', collected_at = now(),"
				+ " sidecar_jsonl = NULL WHERE id = ?", id);
		log.info("계정 배치 수거 완료 — id={}, 저장 {}건, 실패 {}건", id, ok.get(), bad.get());
		return new int[] {ok.get(), bad.get()};
	}

	private void markFailed(long id, String note) {
		analysis.update("UPDATE account_batch_jobs SET status = 'failed', collected_at = now(),"
				+ " note = ?, sidecar_jsonl = NULL WHERE id = ?", note, id);
		log.warn("계정 배치 failed 전이 — id={}: {}", id, note);
	}
}
```

주의: 상태 판별 분기·`markFailed` 문구는 구현 전에 `ContentBatchCollectJob.collectOne()`(line 75-138)을 열어 실제 분기 순서·상태 문자열과 정확히 일치시킨다(위 코드는 조사 보고 기준 재구성). `GeminiBatchLines.state`/`resultFileOf`가 package-private인지 확인하고, private이면 package-private으로 완화한다(같은 패키지).

- [ ] **Step 6: 테스트 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountBatchCollectJobTest" \
    --tests "com.celfit.analytics.analyze.AccountBatchLinesTest"
```

Expected: 전부 PASS.

- [ ] **Step 7: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ analytics/src/test/java/com/celfit/analytics/analyze/
git commit -m "feat(analytics): 계정 카피 배치 수거 잡 AccountBatchCollectJob"
```

---

### Task 6: AccountAnalysisJob — prepare() 분리 (동작 불변 리팩터)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java`

- [ ] **Step 1: analyzeOne을 prepare + 호출·영속화로 분리**

`analyzeOne(handle, model)`의 1~5단계(컨텍스트 로드·신뢰도 판정·dataIncomplete 가드)를 `prepare()`로 추출한다. LLM 호출·영속화(6~8단계)만 `analyzeOne`에 남긴다:

```java
/** 제출·온라인 공용 준비물 — LLM 입력과, 저장 시점에 필요한 스냅샷(사이드카행). */
record Prepared(com.celfit.analytics.llm.AccountToAnalyze account, OffsetDateTime lastPostedAt,
		Long analyzedCount, com.celfit.analytics.llm.AdSituation adSituation) {
}

/** @return null이면 데이터 미비 스킵(SKIPPED_DATA_INCOMPLETE — 기존 배포 과도기 가드 주석 참조) */
private Prepared prepare(String handle) {
	Map<String, Object> summary = analysis.queryForMap(
			"SELECT * FROM account_summaries WHERE handle = ?", handle);
	OffsetDateTime lastPostedAt = analysis.queryForObject(
			"SELECT last_posted_at FROM account_summaries WHERE handle = ?", OffsetDateTime.class, handle);
	Long analyzedCount = (Long) summary.get("analyzed_count");
	List<Map<String, Object>> categories = analysis.queryForList("""
			SELECT main_group, content_count FROM account_category_stats
			WHERE account_handle = ? ORDER BY content_count DESC, main_group ASC""", handle);
	List<Map<String, Object>> posts = AccountAdCanon.loadPosts(analysis, handle);
	AccountAdCanon.AdMetrics ad = AccountAdCanon.load(analysis, handle, (String) summary.get("metric"));
	AccountAdCanon.SummaryWithConfidence sc = AccountAdCanon.withConfidence(summary, ad);
	if (sc.confidence().dataIncomplete()) {
		return null;
	}
	List<Map<String, Object>> promptPosts = AccountAdCanon.withPostConfidence(posts, sc.confidence());
	return new Prepared(new com.celfit.analytics.llm.AccountToAnalyze(handle, sc.promptSummary(),
			categories, promptPosts, ad.situation(), sc.confidence()),
			lastPostedAt, analyzedCount, ad.situation());
}

private Outcome analyzeOne(String handle, String model) {
	Prepared p = prepare(handle);
	if (p == null) {
		return Outcome.SKIPPED_DATA_INCOMPLETE;
	}
	AccountCopy copy = port.synthesize(p.account());
	if (!AccountAnalysisWriter.isValid(copy)) {
		throw new IllegalStateException("계정 카피가 비어 있음: " + handle);
	}
	AccountAnalysisWriter.insert(analysis, json, handle, OffsetDateTime.now(), model,
			p.lastPostedAt(), p.analyzedCount(), copy, p.adSituation(), traitLoader.get().names());
	return Outcome.PROCESSED;
}
```

기존 analyzeOne의 설명 주석들(광고 판정 정본, 배포 과도기 가드 등, line 126-149)은 각 코드가 옮겨간 자리에 **그대로 보존**한다 — 삭제 금지.

- [ ] **Step 2: 기존 테스트 green 확인 (동작 불변 증명)**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisJobTest"
```

Expected: 기존 테스트 전부 PASS, 테스트 수정 없이.

- [ ] **Step 3: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java
git commit -m "refactor(analytics): AccountAnalysisJob 컨텍스트 준비를 prepare()로 분리 — 배치 제출 공용화"
```

---

### Task 7: AccountAnalysisJob 배치 제출 분기 + JobConfig 배선

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/AccountAnalysisJobTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`AccountAnalysisJobTest`에 추가 — 기존 셋업 헬퍼(`fakePort()`, `rewireJob(...)` 등)를 재사용하되, 생성자 변경에 맞춰 헬퍼에 `GeminiBatchApi` 인자를 추가한다(기존 테스트는 `null` 전달로 무변경 통과):

```java
@Test
void 배치_전송이면_제출만_하고_온라인_포트를_호출하지_않는다() {
	// given: app_setting에 ('analytics.account-analyze-transport','batch') INSERT,
	//        자격 대상 계정 1개 시드, 업로드·생성 호출을 캡처하는 fake GeminiBatchApi
	// when: job.run()
	// then: calls(온라인 포트 호출 기록)가 비어 있고, fake가 캡처한 JSONL에
	//       "key":"<handle>"과 "계정: @<handle>" 유저 텍스트가 있고,
	//       account_batch_jobs에 status='pending'·submitted_count=1·sidecar_jsonl에
	//       last_posted_at/analyzed_count/ad_situation이 담긴 1행이 생기고,
	//       account_analyses에는 아무 행도 안 생긴다(저장은 수거 시점)
}

@Test
void 배치_전송이라도_batchApi가_없으면_온라인으로_폴백한다() {
	// given: transport='batch', batchApi=null → when: run() → then: 온라인 포트 호출됨(기존 경로)
}

@Test
void 배치_제출_전에_pending_잔여를_먼저_수거한다() {
	// given: transport='batch', account_batch_jobs에 SUCCEEDED 상태 pending 1행(유효 사이드카·결과 fake)
	// when: run() → then: 그 행이 collected로 전이된 후 새 제출이 이뤄진다(중복 제출 완화 — 콘텐츠 동형)
}
```

given/when/then 주석을 실제 코드로 채운다. fake `GeminiBatchApi`는 `uploadFile`이 받은 바이트를 필드에 보관하고 `"files/fake"`를, `createBatch`가 `"batches/fake"`를 반환하는 익명 클래스.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisJobTest"
```

Expected: 새 테스트 3개 FAIL(컴파일 실패 또는 배치 분기 부재로 온라인 경로 실행).

- [ ] **Step 3: AccountAnalysisJob에 배치 분기 구현**

생성자에 `GeminiBatchApi batchApi`(nullable)를 추가하고, 수거 잡을 내부 생성한다(콘텐츠의 [ContentAnalysisJob.java:103](../../../analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java) 동형):

```java
private final com.celfit.analytics.llm.GeminiBatchApi batchApi;
private final AccountBatchCollectJob collectJob;

// 생성자 말미에:
this.batchApi = batchApi;
this.collectJob = new AccountBatchCollectJob(analysisDataSource, batchApi, traitLoader, settings);
```

`run()`의 대상 조회 직후, for 루프 전에 분기(기존 온라인 루프는 `runOnline(targets, model)` 프라이빗 메서드로 추출):

```java
if (settings.accountBatchTransportEnabled()) {
	if (batchApi != null) {
		return submitBatch(targets);
	}
	log.warn("account-analyze-transport=batch이나 활성 프로바이더가 배치 미지원 — 온라인 경로로 폴백");
}
```

제출 메서드(콘텐츠 `submitBatch` 동형 — 사이드카는 DB 컬럼, 제출 전 pending 수거):

```java
/** 배치 전송 제출 — 제출 전 pending 잔여를 먼저 수거해 중복 제출을 완화한다(콘텐츠 동형). */
private JobResult submitBatch(List<String> targets) {
	JobResult swept = collectJob.run();
	if (swept.processed() > 0 || swept.failed() > 0) {
		log.info("계정 배치 제출 전 pending 수거 — {}건 저장, {}건 실패", swept.processed(), swept.failed());
	}
	if (targets.isEmpty()) {
		log.info("계정 배치 제출 대상 없음 — 제출 생략");
		return new JobResult(0, 0, false);
	}
	String model = settings.activeLlmModel();
	com.celfit.analytics.llm.TraitTaxonomy vocab = traitLoader.get();
	StringBuilder jsonl = new StringBuilder();
	StringBuilder sidecar = new StringBuilder();
	int skippedIncomplete = 0;
	int submitted = 0;
	for (String handle : targets) {
		Prepared p = prepare(handle);
		if (p == null) {
			skippedIncomplete++; // 온라인 경로의 SKIPPED_DATA_INCOMPLETE와 동일 — 제출에서 제외
			continue;
		}
		String system = com.celfit.analytics.llm.GeminiAccountSynthesizer
				.instructions(vocab, p.account().confidence());
		jsonl.append(json.writeValueAsString(AccountBatchLines.requestLine(json, handle, system,
				com.celfit.analytics.llm.GeminiAccountSynthesizer.userText(p.account())))).append('\n');
		sidecar.append(json.writeValueAsString(AccountBatchLines.sidecarLine(json, handle,
				p.lastPostedAt(), p.analyzedCount(), p.adSituation().name()))).append('\n');
		submitted++;
	}
	if (skippedIncomplete > 0) {
		log.warn("계정 {}건 스킵 — 데이터 미비(뷰 미적용/미러 실패 의심, 온라인 경로와 동일 판정)",
				skippedIncomplete);
	}
	if (submitted == 0) {
		log.info("계정 배치 제출 대상 전량 스킵 — 제출 생략");
		return new JobResult(0, 0, false);
	}
	String fileName = batchApi.uploadFile(
			jsonl.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "hypenow-account");
	String batchName = batchApi.createBatch(model, fileName, "hypenow-account");
	// 사이드카는 DB 컬럼 보관 — 컨테이너에 쓰기 볼륨이 없어 로컬 파일은 배포 교체 시 유실 좀비(08-11 리뷰)
	analysis.update("""
			INSERT INTO account_batch_jobs (batch_name, submitted_count, status, sidecar_jsonl)
			VALUES (?, ?, 'pending', ?)""", batchName, submitted, sidecar.toString());
	log.info("계정 배치 제출 완료 — batch={}, {}건", batchName, submitted);
	return new JobResult(submitted, 0, false);
}
```

- [ ] **Step 4: JobConfig 배선**

`accountAnalysisJob` 빈(line 126-138)에 `ObjectProvider<GeminiApi> gemini` 파라미터를 추가하고 `batchApiOrNull(settings, gemini)`를 넘긴다(콘텐츠 빈 line 88-90 동형):

```java
return new AccountAnalysisJob(analysisDataSource, port, settings, reporter, traitLoader,
		batchApiOrNull(settings, gemini));
```

수거 잡 독립 빈 추가(`contentBatchCollectJob` 빈 line 97-106 동형 — BATCH_COLLECT 트리거용):

```java
/** 계정 카피 배치 수거 잡(2026-08-17) — 제출 전 스윕과 별개로 BATCH_COLLECT가 독립 트리거한다. */
@Bean
@Lazy
@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
public com.celfit.analytics.analyze.AccountBatchCollectJob accountBatchCollectJob(
		@Qualifier("analysisDataSource") DataSource analysisDataSource,
		AnalyticsSettings settings, ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini,
		com.celfit.analytics.llm.TraitTaxonomyLoader traitLoader) {
	return new com.celfit.analytics.analyze.AccountBatchCollectJob(analysisDataSource,
			batchApiOrNull(settings, gemini), traitLoader, settings);
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :analytics:test --tests "com.celfit.analytics.analyze.AccountAnalysisJobTest"
```

Expected: 기존 + 신규 전부 PASS.

- [ ] **Step 6: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/ analytics/src/test/java/com/celfit/analytics/
git commit -m "feat(analytics): 계정 카피 배치 제출 경로 — account-analyze-transport 토글 분기"
```

---

### Task 8: BATCH_COLLECT가 계정 배치도 수거

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java`

- [ ] **Step 1: 필드·케이스 수정**

`AnalyticsJobService`에 `batchCollectJob`(ObjectProvider — 기존 필드·생성자 파라미터 관용 그대로)과 나란히 `ObjectProvider<AccountBatchCollectJob> accountBatchCollectJob`을 추가하고, switch의 BATCH_COLLECT 케이스(line 134)를 교체:

```java
// 수거는 종류 불문 한 트리거로 — 각 잡은 자기 pending이 없으면 no-op라 겹쳐 돌아도 무해.
case BATCH_COLLECT -> {
	JobResult content = batchCollectJob.getObject().run();
	JobResult account = accountBatchCollectJob.getObject().run();
	yield new JobResult(content.processed() + account.processed(),
			content.failed() + account.failed(), false);
}
```

크론은 기존 `ANALYTICS_SCHEDULE_BATCH_COLLECT_CRON`(KST 05:10~11:40 30분 간격) 그대로 — 07:00 제출분을 07:10부터 최대 9회 폴링하고, 그날 못 끝나면 다음날 05:10 창과 제출 전 스윕이 회수한다. **compose.yaml 변경 없음.**

- [ ] **Step 2: 전 모듈 컴파일·analytics 테스트**

```bash
./gradlew :analytics:test
```

Expected: BUILD SUCCESSFUL(전체 green). 실패 시 원인 파악 먼저 — `DOCKER_HOST` 미설정이면 Testcontainers 대량 실패 함정부터 의심.

- [ ] **Step 3: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java
git commit -m "feat(analytics): BATCH_COLLECT 트리거가 계정 카피 배치도 함께 수거"
```

---

### Task 9: 문서·마무리

**Files:**
- Modify: `DECISIONS.md`
- Modify: `docs/superpowers/plans/2026-08-17-account-analyze-batch-transport.md` (상태 헤더)

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 추가**

```markdown
- **2026-08-17 계정 카피 배치 전송 전환**: ACCOUNT_ANALYZE(KST 07:00)를 콘텐츠와 동형의 Vertex 배치
  제출→수거로 전환. 새 토글 `analytics.account-analyze-transport`(기본 online — 콘텐츠 토글과 독립),
  상태는 `account_batch_jobs`, 수거는 기존 BATCH_COLLECT 크론에 합승. 배경: 08-17 규명 — 쿨다운 7일
  +07시 정각 실행이 만드는 8일 주기 온라인 펄스(회당 ~2,700건·~$4.3, batch-limit 30000 운영 확정값),
  전환 시 절감 ~월 $9~10. 8일 펄스 주기 자체의 보정은 스코프 제외(배치면 비용 반감·서빙 무영향).
```

(기존 항목 형식과 다르면 파일 맨 위 최근 항목의 형식을 따른다.)

- [ ] **Step 2: 전체 테스트 (PR 직전 관용)**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. (전체 테스트는 PR 직전에만 — colima 8 CPU/12 GiB 확인.)

- [ ] **Step 3: 이 계획 문서 상태 갱신·아카이브 준비**

이 파일의 상태 헤더를 `> 상태: ✅ 구현됨`으로 바꾸고 `docs/superpowers/plans/archive/`로 `git mv`(PR을 열게 되면 같은 PR에 포함 — 완료 문서 잔류가 후속 세션 컨텍스트 비용을 만든다). 옛 경로 참조 링크를 grep으로 확인:

```bash
grep -rn "2026-08-17-account-analyze-batch-transport" --exclude-dir=.git . | grep -v plans/archive
```

- [ ] **Step 4: Commit + push (PR은 열지 않는다)**

```bash
git add DECISIONS.md docs/superpowers/plans/
git commit -m "docs: 계정 카피 배치 전환 결정 기록·계획 아카이브"
git push -u origin feat/account-analyze-batch-transport
```

**push·결과 보고까지만 — PR 개설은 사용자 명시 승인 후에만**(전역 규칙, 프로젝트 "즉시 열기" 컨벤션보다 우선).

---

## 배포·전환 런북 (머지 후 — 코드 작업 아님)

1. **승격**: develop → staging(dev-api 배포·검증) → main(운영 배포). 마이그레이션 2개는 expand 단계라 롤링 안전. 승격·역머지 PR은 merge commit으로.
2. **staging 검증(선택)**: test 환경에서 `UPDATE app_setting SET value='batch' WHERE key='analytics.account-analyze-transport'` 후 어드민 `/ui`에서 ACCOUNT_ANALYZE 수동 트리거 → `account_batch_jobs`에 pending 생성 → BATCH_COLLECT 트리거로 수거 완주 확인.
3. **운영 전환**: 운영 raw DB에서 같은 UPDATE 한 줄(런타임 토글 — 재기동 불필요, 잡 실행 시점마다 읽음).
4. **첫 펄스 실증**: 다음 대량 펄스는 **~08-24(직전 펄스 08-16 + 8일) KST 07:00** 예상. 당일 `account_batch_jobs`의 submitted_count·collected 전이와 GCP 콘솔(Vertex Model Garden Monitoring)에서 온라인 토큰이 안 튀는 것을 확인. 콘텐츠 배치 전환(08-11)도 첫 완주 실증이 미확인 과제였던 전력이 있다 — 이번엔 확인을 빼먹지 말 것. 펄스 제출 시 analytics 힙 사용량도 관측(2,700건 JSONL 조립 — 스트리밍화 반영됐지만 첫 실측 필요).
5. **롤백**: `UPDATE app_setting SET value='online' WHERE key='analytics.account-analyze-transport'` 한 줄. pending 잔여는 BATCH_COLLECT가 계속 수거하므로 유실 없음.
6. **`analytics.llm-provider` 런타임 전환 시 analytics 재기동 필요** — batchApi는 빈 생성 시점에 provider로 고정된다(콘텐츠와 동일 구조).

## Self-Review 결과 (계획 작성 시 수행)

- 실행자가 열어 확인해야 하는 지점 3곳을 본문에 명시했다: `AdSituation` enum 실명(Task 4), `AccountAnalysisWriter.insert` 실시그니처(Task 5), `ContentBatchCollectJob.collectOne` 실분기(Task 5). 조사 시점 코드 기준으로 작성했으므로 실행 시점에 원본과 대조가 필수다.
- 타입 일관성: `Prepared`(Task 6)를 Task 7의 `submitBatch`가 그대로 사용, `AccountBatchLines.sidecarLine(om, handle, lastPostedAt, analyzedCount, adSituationName)` 시그니처가 Task 4 테스트·Task 7 호출부와 일치.
- 스펙 커버리지: 제출(Task 7)·수거(Task 5)·토글(Task 1·2)·스케줄(Task 8)·문서(Task 9)·검증(런북). 콘텐츠 경로 무변경 확인: `content_batch_jobs`·`GeminiBatchLines`는 읽기 재사용만.
