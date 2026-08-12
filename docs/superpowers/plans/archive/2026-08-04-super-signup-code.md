# super 초대코드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료 · 2026-08-04 · 스펙: [2026-08-04-super-signup-code-design.md](../../specs/archive/2026-08-04-super-signup-code-design.md)

**Goal:** `is_super` 플래그가 켜진 초대코드는 인원 제한 없이 여러 명이 가입할 수 있게 한다.

**Architecture:** `app.signup_codes`에 `is_super boolean` 컬럼 하나 추가. 가입 claim은 기존 원자 UPDATE에 `AND NOT is_super` 가드를 붙이고, 0행이면 super 여부를 조회해 상태 변경 없이 통과(무제한). 어드민은 적재 시 플래그·PATCH 부분 갱신(승격/강등)·조회 노출 3면.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcClient / Flyway / Testcontainers(PostgreSQL). 작업 위치는 worktree `.worktrees/super-signup-code`(브랜치 `feat/super-signup-code`).

**공통 주의:**
- 테스트 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수 (미설정 시 Testcontainers 대량 실패).
- 테스트는 모듈 단위: `./gradlew :was:test --tests "..."`. 전체 `./gradlew test`는 PR 직전에만.
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`.

---

### Task 1: 마이그레이션 + claim/isUsable super 분기

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260804043829__signup_codes_is_super.sql`
- Modify: `was/src/main/java/com/celfit/was/v1/account/SignupCodeRepository.java`
- Test: `was/src/test/java/com/celfit/was/SignupCodeIntegrationTest.java`

- [ ] **Step 1: 마이그레이션 작성** (테스트가 컴파일·기동되려면 컬럼이 먼저 필요)

```sql
-- super 초대코드(설계 2026-08-04) — is_super=true인 코드는 인원 제한 없이 가입 가능.
-- 소진 스탬프(used_at)를 영원히 찍지 않는 방식이라 기존 "used_at IS NULL = 미소진" 정본과 공존한다.
-- 컬럼 추가만이라 expand-contract 안전(구 코드는 컬럼 무시, 기존 1회용 동작 유지).
ALTER TABLE app.signup_codes ADD COLUMN is_super boolean NOT NULL DEFAULT false;
```

- [ ] **Step 2: 실패하는 테스트 작성** — `SignupCodeIntegrationTest.java`에 아래 헬퍼와 테스트 4개 추가. 헬퍼는 기존 `seedCode` 아래에:

```java
	private void seedSuperCode(String code) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, is_super) VALUES (:code, 'TEST', true)
				ON CONFLICT (code) DO UPDATE SET used_by = NULL, used_at = NULL, is_super = true""")
				.param("code", code)
				.update();
	}
```

테스트 4개 (클래스 말미에 추가 — 기존 관용구대로 메서드마다 고유 이메일, `testIp`는 `@BeforeEach`가 이미 고유화):

```java
	@Test
	void super_코드는_여러_명이_가입할_수_있고_used_at이_찍히지_않는다() throws Exception {
		seedSuperCode("SUPER-MULTI");
		signup("SUPER-MULTI", "super-multi-1@example.com").andExpect(status().isCreated());
		signup("SUPER-MULTI", "super-multi-2@example.com").andExpect(status().isCreated());

		Map<String, Object> row = jdbcClient.sql(
				"SELECT used_by, used_at FROM app.signup_codes WHERE code = 'SUPER-MULTI'")
				.query().singleRow();
		assertThat(row.get("used_by")).isNull();
		assertThat(row.get("used_at")).isNull();

		// 두 명 가입 후에도 사전 검증은 계속 valid
		verify("SUPER-MULTI")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.valid").value(true));
	}

	@Test
	void 소진된_일반_코드를_super로_승격하면_다시_가입할_수_있다() throws Exception {
		seedCode("THREADS-PROMO");
		signup("THREADS-PROMO", "promo-first@example.com").andExpect(status().isCreated());
		// 소진 확인 후 승격 — 기존 used_at 스탬프는 보존된 채 무제한이 된다(설계 §동작 규칙).
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = true WHERE code = 'THREADS-PROMO'").update();

		signup("THREADS-PROMO", "promo-second@example.com").andExpect(status().isCreated());
		verify("THREADS-PROMO").andExpect(status().isOk());
	}

	@Test
	void super를_강등하면_일반_1회용_규칙으로_복귀한다() throws Exception {
		seedSuperCode("SUPER-DEMOTE");
		signup("SUPER-DEMOTE", "demote-first@example.com").andExpect(status().isCreated());
		// 강등 — super 가입은 used_at을 안 찍었으므로 미소진 일반 코드가 된다.
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = false WHERE code = 'SUPER-DEMOTE'").update();

		signup("SUPER-DEMOTE", "demote-second@example.com").andExpect(status().isCreated());
		// 두 번째 가입이 스탬프를 찍었으니 이제 소진 — 세 번째는 403.
		signup("SUPER-DEMOTE", "demote-third@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}

	@Test
	void 일반_코드_소진은_super_도입_후에도_그대로다() throws Exception {
		seedCode("THREADS-STILL1");
		signup("THREADS-STILL1", "still-first@example.com").andExpect(status().isCreated());
		signup("THREADS-STILL1", "still-second@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}
```

- [ ] **Step 3: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.SignupCodeIntegrationTest"
```
Expected: 신규 4개 중 `super_코드는_...`, `소진된_일반_코드를_...` FAIL(두 번째 가입 403). 기존 테스트는 PASS 유지.

- [ ] **Step 4: SignupCodeRepository 수정** — `isUsable`·`claim` 두 메서드 교체:

```java
	/** 존재하며 미사용(또는 super)이면 true — 사전 검증(/signup-code/verify)과 가입 초입의 빠른 실패용. 소진 판정은 used_at 기준. */
	public boolean isUsable(String code) {
		String normalized = normalize(code);
		if (normalized.isEmpty()) {
			return false;
		}
		return jdbcClient.sql("""
				SELECT EXISTS (SELECT 1 FROM app.signup_codes
				WHERE code = :code AND (used_at IS NULL OR is_super))""")
				.param("code", normalized)
				.query(Boolean.class)
				.single();
	}

	/**
	 * 원자 선점(used_at 스탬프) — 이미 소진됐거나 없는 코드면 false. 가입 트랜잭션 안에서 호출할 것.
	 * super 코드(설계 2026-08-04)는 스탬프 없이 통과해 무제한 — UPDATE의 NOT is_super 가드가 없으면
	 * 첫 가입자가 used_at을 찍어 강등 시 소진 상태로 굳는다.
	 */
	public boolean claim(String code, long userId) {
		String normalized = normalize(code);
		if (normalized.isEmpty()) {
			return false;
		}
		int updated = jdbcClient.sql("""
				UPDATE app.signup_codes SET used_by = :userId, used_at = now()
				WHERE code = :code AND used_at IS NULL AND NOT is_super""")
				.param("userId", userId)
				.param("code", normalized)
				.update();
		if (updated == 1) {
			return true;
		}
		return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM app.signup_codes WHERE code = :code AND is_super)")
				.param("code", normalized)
				.query(Boolean.class)
				.single();
	}
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.SignupCodeIntegrationTest"
```
Expected: 전부 PASS (기존 6개 + 신규 4개).

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V20260804043829__signup_codes_is_super.sql \
  was/src/main/java/com/celfit/was/v1/account/SignupCodeRepository.java \
  was/src/test/java/com/celfit/was/SignupCodeIntegrationTest.java
git commit -m "feat(was): super 초대코드 — is_super 코드는 인원 제한 없이 가입 허용

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 적재 API에 isSuper 플래그

**Files:**
- Modify: `was/src/main/java/com/celfit/was/admin/SignupCodeCreateRequest.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupCodeService.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupCodeRepository.java`
- Test: `was/src/test/java/com/celfit/was/AdminSignupCodeIngestIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `AdminSignupCodeIngestIntegrationTest.java` 말미에 추가 (기존 `submit`/`uniqueCode` 헬퍼 재사용):

```java
	@Test
	void isSuper_true로_적재하면_is_super가_켜진다() throws Exception {
		String code = uniqueCode("PARTNER");
		submit(TOKEN, "{\"codes\":[\"" + code + "\"],\"isSuper\":true}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(1));
		Boolean isSuper = jdbcClient.sql("SELECT is_super FROM app.signup_codes WHERE code = :c")
				.param("c", code).query(Boolean.class).single();
		assertThat(isSuper).isTrue();
	}

	@Test
	void isSuper_생략하면_일반_코드로_적재된다() throws Exception {
		String code = uniqueCode("THREADS");
		submit(TOKEN, "{\"codes\":[\"" + code + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(1));
		Boolean isSuper = jdbcClient.sql("SELECT is_super FROM app.signup_codes WHERE code = :c")
				.param("c", code).query(Boolean.class).single();
		assertThat(isSuper).isFalse();
	}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.AdminSignupCodeIngestIntegrationTest"
```
Expected: `isSuper_true로_적재하면...` FAIL (is_super가 false — 요청 필드가 아직 무시됨). 나머지 PASS.

- [ ] **Step 3: 구현** — 3개 파일 수정.

`SignupCodeCreateRequest.java` 교체:

```java
package com.celfit.was.admin;

import java.util.List;

/**
 * 가입 코드 일괄 적재 요청(설계 2026-07-20) — codes는 PREFIX-XXXX 형식, 배치 ≤500.
 * isSuper(설계 2026-08-04)는 배치 전체에 적용 — 생략·null이면 일반(1회용) 코드.
 * 'super'는 Java 예약어라 컴포넌트명으로 못 쓴다.
 */
public record SignupCodeCreateRequest(List<String> codes, Boolean isSuper) {
}
```

`AdminSignupCodeRepository.insert` 교체:

```java
	public int insert(String code, String channel, boolean isSuper) {
		return jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, is_super) VALUES (:code, :channel, :isSuper)
				ON CONFLICT (code) DO NOTHING""")
				.param("code", code)
				.param("channel", channel)
				.param("isSuper", isSuper)
				.update();
	}
```

`AdminSignupCodeService.create`의 삽입 루프 교체 (검증부 불변):

```java
		boolean isSuper = Boolean.TRUE.equals(request.isSuper());
		int inserted = 0;
		for (CodeChannel cc : parsed) {
			inserted += repository.insert(cc.code(), cc.channel(), isSuper);
		}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.AdminSignupCodeIngestIntegrationTest"
```
Expected: 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/admin/SignupCodeCreateRequest.java \
  was/src/main/java/com/celfit/was/admin/AdminSignupCodeRepository.java \
  was/src/main/java/com/celfit/was/admin/AdminSignupCodeService.java \
  was/src/test/java/com/celfit/was/AdminSignupCodeIngestIntegrationTest.java
git commit -m "feat(was): 가입 코드 적재 시 isSuper 플래그로 super 코드 발급

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: PATCH 부분 갱신(승격·강등) + 조회 노출

**Files:**
- Create: `was/src/main/java/com/celfit/was/admin/SignupCodePatchRequest.java`
- Create: `was/src/main/java/com/celfit/was/admin/SignupCodePatchResponse.java`
- Delete: `was/src/main/java/com/celfit/was/admin/SignupCodeSentRequest.java`
- Delete: `was/src/main/java/com/celfit/was/admin/SignupCodeSentResponse.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupController.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java`
- Modify: `was/src/main/java/com/celfit/was/admin/SignupUsageRow.java`
- Test: `was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `AdminSignupIntegrationTest.java`에 추가. 기존 `isSent_누락_PATCH는_400` 테스트의 이름·주석을 부분 갱신 계약에 맞게 교체하고, 신규 테스트를 더한다.

기존 테스트 교체 (`isSent_누락_PATCH는_400` → 아래로):

```java
	@Test
	void isSent_isSuper_둘_다_누락한_PATCH는_400() throws Exception {
		String code = uniqueCode("DM-400");
		seedUnusedCode(code, "DM");

		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists());
	}
```

신규 테스트 3개:

```java
	@Test
	void PATCH_isSuper로_승격하고_강등할_수_있다() throws Exception {
		String code = uniqueCode("DM-SUPER");
		seedUnusedCode(code, "DM");

		// 승격 — isSent는 안 보냈으므로 기존 값(false) 유지(부분 갱신).
		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSuper\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.isSuper").value(true))
				.andExpect(jsonPath("$.isSent").value(false));

		// 강등.
		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSuper\": false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuper").value(false));
	}

	@Test
	void PATCH_isSent만_보내면_isSuper는_유지된다() throws Exception {
		String code = uniqueCode("DM-KEEP");
		seedUnusedCode(code, "DM");
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = true WHERE code = :c")
				.param("c", code).update();

		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSent\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSent").value(true))
				.andExpect(jsonPath("$.isSuper").value(true));
	}

	@Test
	void 조회_응답에_isSuper가_노출된다() throws Exception {
		String code = uniqueCode("DM-SHOW");
		seedUnusedCode(code, "DM");
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = true WHERE code = :c")
				.param("c", code).update();

		mockMvc.perform(get("/admin/signups").header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='" + code + "')].isSuper")
						.value(org.hamcrest.Matchers.contains(true)));

		SignupUsageRow row = repository.findAll().stream()
				.filter(r -> r.code().equals(code)).findFirst().orElseThrow();
		assertThat(row.isSuper()).isTrue();
	}
```

- [ ] **Step 2: 실패 확인** (컴파일 에러 예상 — `row.isSuper()`가 아직 없음)

```bash
./gradlew :was:test --tests "com.celfit.was.AdminSignupIntegrationTest"
```
Expected: 컴파일 실패 (`SignupUsageRow.isSuper()` 부재).

- [ ] **Step 3: 구현**

`SignupCodePatchRequest.java` 신규 (기존 `SignupCodeSentRequest.java` 삭제):

```java
package com.celfit.was.admin;

/**
 * 가입 코드 부분 갱신 요청(설계 2026-07-22 발송 표시 → 2026-08-04 super 승격·강등 확장).
 * 두 필드 모두 옵션(Boolean 래퍼) — 온 필드만 갱신하고, 둘 다 null이면 400.
 */
public record SignupCodePatchRequest(Boolean isSent, Boolean isSuper) {
}
```

`SignupCodePatchResponse.java` 신규 (기존 `SignupCodeSentResponse.java` 삭제):

```java
package com.celfit.was.admin;

/**
 * 가입 코드 부분 갱신 결과 — 반영된 최종 상태를 그대로 돌려준다(멱등 PATCH).
 * JdbcClient 매핑: is_sent → isSent, is_super → isSuper.
 */
public record SignupCodePatchResponse(String code, boolean isSent, boolean isSuper) {
}
```

`AdminSignupRepository` — `findAll`의 SELECT에 `sc.is_super` 추가, `updateIsSent`를 `updatePartial`로 교체:

```java
	public List<SignupUsageRow> findAll() {
		return jdbcClient.sql("""
				SELECT sc.code, sc.channel, u.email, sc.used_by AS user_id, sc.used_at, sc.is_sent, sc.is_super
				FROM app.signup_codes sc
				LEFT JOIN app.users u ON u.id = sc.used_by
				ORDER BY sc.used_at DESC NULLS LAST, sc.code""")
				.query(SignupUsageRow.class)
				.list();
	}

	/**
	 * 부분 갱신(설계 2026-08-04) — null인 필드는 기존 값 유지(COALESCE), 빈 결과면 코드 없음(호출부가 404 판정).
	 * CAST 명시는 untyped null 파라미터의 타입 추론 실패(could not determine data type) 방지.
	 */
	public Optional<SignupCodePatchResponse> updatePartial(String code, Boolean isSent, Boolean isSuper) {
		return jdbcClient.sql("""
				UPDATE app.signup_codes
				SET is_sent = COALESCE(CAST(:isSent AS boolean), is_sent),
				    is_super = COALESCE(CAST(:isSuper AS boolean), is_super)
				WHERE code = :code
				RETURNING code, is_sent, is_super""")
				.param("isSent", isSent)
				.param("isSuper", isSuper)
				.param("code", code)
				.query(SignupCodePatchResponse.class)
				.optional();
	}
```

(`import java.util.Optional;` 추가 필요.)

`AdminSignupController.updateSent` 교체 (클래스 주석의 "발송 표시 변경" 문구도 "발송 표시·super 승격 변경"으로 갱신):

```java
	@PatchMapping("/admin/signup-codes/{code}")
	public SignupCodePatchResponse patch(@PathVariable String code,
			@RequestBody SignupCodePatchRequest request) {
		if (request.isSent() == null && request.isSuper() == null) {
			throw new AdminApiException(400, "isSent 또는 isSuper 중 하나는 필요합니다.");
		}
		return repository.updatePartial(code, request.isSent(), request.isSuper())
				.orElseThrow(() -> new AdminApiException(404, "존재하지 않는 코드입니다: " + code));
	}
```

`SignupUsageRow` — 컴포넌트 끝에 `boolean isSuper` 추가, 주석에 한 줄 추가:

```java
public record SignupUsageRow(String code, String channel, String email, Long userId,
		OffsetDateTime usedAt, boolean isSent, boolean isSuper) {
}
```

- [ ] **Step 4: 통과 확인** — PATCH·조회·가입 코드 테스트 전부:

```bash
./gradlew :was:test --tests "com.celfit.was.AdminSignupIntegrationTest" \
  --tests "com.celfit.was.SignupCodeIntegrationTest" \
  --tests "com.celfit.was.AdminSignupCodeIngestIntegrationTest"
```
Expected: 전부 PASS. (기존 `PATCH로_발송_표시를_켜고_끌_수_있다`가 새 응답에서도 통과해야 함 — isSent 필드는 그대로.)

- [ ] **Step 5: 커밋**

```bash
git add -A was/src/main/java/com/celfit/was/admin/ was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java
git commit -m "feat(was): 가입 코드 PATCH 부분 갱신으로 super 승격·강등, 조회에 isSuper 노출

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 전체 검증 + 문서 + PR

- [ ] **Step 1: was 모듈 전체 테스트** (다른 테스트로의 파급 확인 — 특히 OpenAPI 문서 스냅샷류가 DTO 개명에 반응하는지)

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test
```
Expected: 전부 PASS. 실패 시 실패 클래스만 열어 원인 수정(예: OpenAPI 스키마명 단언이 있으면 새 DTO명으로 갱신).

- [ ] **Step 2: 스펙 상태 헤더를 `✅ 구현됨`으로 갱신 + DECISIONS.md 맨 위에 결정 추가**

DECISIONS.md 항목(맨 위에):

```markdown
## 2026-08-04 super 초대코드 — is_super 플래그, 소진 스탬프 미기록으로 무제한
- 코드 하나로 여러 명 가입 허용은 `signup_codes.is_super` 컬럼 하나로(uses 테이블 기각 — 가입자 추적은 signup_events가 이미 정본).
- claim의 원자 UPDATE에 `AND NOT is_super` 가드 — super 코드에 used_at이 찍히면 강등 시 소진으로 굳기 때문.
- PATCH /admin/signup-codes/{code}는 isSent·isSuper 부분 갱신으로 확장(둘 다 없으면 400).
- 스펙: docs/superpowers/specs/2026-08-04-super-signup-code-design.md
```

```bash
git add docs/superpowers/specs/2026-08-04-super-signup-code-design.md DECISIONS.md docs/superpowers/plans/2026-08-04-super-signup-code.md
git commit -m "docs: super 초대코드 결정 기록·스펙 상태 갱신

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 3: push + develop 대상 PR 생성**

```bash
git push -u origin feat/super-signup-code
gh pr create --base develop --title "feat(was): super 초대코드 — 코드 하나로 여러 명 가입" --body "$(cat <<'EOF'
## 요약
- `app.signup_codes.is_super` 추가 — super 코드는 소진 스탬프 없이 통과해 인원 무제한
- 적재 API `POST /admin/signup-codes`에 `isSuper` 플래그(배치 전체 적용)
- `PATCH /admin/signup-codes/{code}` 부분 갱신으로 확장(isSent·isSuper, 둘 다 없으면 400) — 기존 코드 승격·강등
- `GET /admin/signups` 응답에 `isSuper` 노출

## 설계
docs/superpowers/specs/2026-08-04-super-signup-code-design.md

## 테스트
- super 다중 가입·미스탬프, 소진 코드 승격 후 재가입, 강등 후 1회용 복귀, 기존 1회용 불변
- 적재 isSuper on/off, PATCH 부분 갱신·400·404, 조회 노출

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review 결과

- 스펙 커버리지: 스키마(Task 1 Step 1), claim/isUsable(Task 1), 적재(Task 2), 승격·강등 PATCH(Task 3), 조회 노출(Task 3), 엣지(승격 후 재가입·강등 복귀 — Task 1 테스트) 전부 태스크에 매핑됨.
- 429 플레이키: SignupCodeIntegrationTest는 rate-limit 프로퍼티 대신 테스트별 고유 IP(`IP_SEQUENCE`) 관용구를 이미 쓰고 있어 신규 테스트도 그대로 커버된다(신규 프로퍼티 불필요).
- 타입 일관성: `SignupCodePatchRequest/Response`·`updatePartial`·`insert(code, channel, isSuper)` 시그니처가 태스크 간 일치함을 확인.
