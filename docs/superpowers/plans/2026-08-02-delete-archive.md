# 삭제 데이터 아카이브 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** was에서 hard delete되는 행을 삭제 직전 `archive.archived_rows`로 원본 그대로 이관한다.

**Architecture:** 라이브 삭제 로직과 조회 경로는 그대로 두고, 삭제 직전에 같은 트랜잭션으로 `INSERT … SELECT`를 하나 끼워 넣는다. 아카이브 대상 테이블의 메타데이터(PK 컬럼, user_id 표현식, 가명화 제외 컬럼, 유저 스코프 WHERE)는 `ArchiveTables` 카탈로그 한 곳에 모으고, `ArchiveWriter`가 그것으로 SQL을 조립한다. 누락은 `information_schema`/`pg_constraint`를 읽는 가드 테스트 2종이 CI에서 차단한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, PostgreSQL 17, Flyway(수동 `@Configuration`), JUnit 5 + AssertJ + Testcontainers

**설계 문서:** [2026-08-02-delete-archive-design.md](../specs/2026-08-02-delete-archive-design.md)

---

## 사전 준비

작업 전 셸에서 반드시 실행한다. 없으면 Testcontainers 통합 테스트가 대량 실패하고, 코드 결함으로 오진하기 쉽다.

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```

작업 디렉토리는 워크트리 `.worktrees/delete-archive` (브랜치 `feat/delete-archive`)다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `was/src/main/resources/db/migration/app/V20260802090000__delete_archive.sql` | `archive` 스키마 + `archived_rows` 테이블 생성 |
| `was/src/main/java/com/celfit/was/archive/ArchiveReason.java` | 이관 사유 enum |
| `was/src/main/java/com/celfit/was/archive/ArchiveTable.java` | 테이블 1개의 아카이브 메타데이터 record |
| `was/src/main/java/com/celfit/was/archive/ArchiveTables.java` | 아카이브 대상 카탈로그(단일 정본) |
| `was/src/main/java/com/celfit/was/archive/ArchiveWriter.java` | SQL 조립·실행 |
| `was/src/test/java/com/celfit/was/archive/ArchiveWriterTest.java` | Writer 단위 검증 |
| `was/src/test/java/com/celfit/was/archive/ArchiveInventoryTest.java` | 가드 — app 스키마 전 테이블 분류 강제 |
| `was/src/test/java/com/celfit/was/archive/ArchiveCascadeReachabilityTest.java` | 가드 — CASCADE 재귀 도달성 |

수정 대상: `UserRepository`, `SavedRepository`, `V1SavedRepository`, `CampaignRepository`, `MonitoringItemRepository`와 각각의 기존 테스트.

---

### Task 1: 아카이브 스키마·테이블 마이그레이션

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260802090000__delete_archive.sql`
- Test: `was/src/test/java/com/celfit/was/archive/ArchiveSchemaTest.java`

`AppFlywayConfig`는 `.schemas("app").defaultSchema("app")`이지만 이는 Flyway가 관리·생성하는 스키마 목록일 뿐, 스크립트가 실행할 DDL 범위를 제한하지 않는다. `CREATE SCHEMA archive`는 그대로 실행된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/archive/ArchiveSchemaTest.java`:

```java
package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** archive 스키마가 Flyway로 실제 생성되는지 검증 — DDL 하드코딩 없음. */
class ArchiveSchemaTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void archived_rows_테이블이_archive_스키마에_생성된다() {
		List<String> columns = jdbcClient.sql("""
						SELECT column_name FROM information_schema.columns
						 WHERE table_schema = 'archive' AND table_name = 'archived_rows'
						 ORDER BY ordinal_position
						""")
				.query(String.class)
				.list();

		assertThat(columns).containsExactly(
				"id", "table_name", "row_pk", "user_id", "payload", "archived_at", "archived_reason");
	}

	@Test
	void payload와_row_pk는_jsonb다() {
		List<String> types = jdbcClient.sql("""
						SELECT data_type FROM information_schema.columns
						 WHERE table_schema = 'archive' AND table_name = 'archived_rows'
						   AND column_name IN ('row_pk', 'payload')
						""")
				.query(String.class)
				.list();

		assertThat(types).containsExactly("jsonb", "jsonb");
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveSchemaTest"
```

Expected: FAIL — `containsExactly`가 빈 리스트를 받아 실패한다(`archive` 스키마가 없으므로 조회 결과 0건).

- [ ] **Step 3: 마이그레이션 작성**

`was/src/main/resources/db/migration/app/V20260802090000__delete_archive.sql`:

```sql
-- 삭제되는 행의 원본 보존(트랙 NN). 라이브 삭제 로직은 그대로 두고 삭제 직전 이관만 한다.
-- archive는 Flyway schemas 목록 밖이라 clean 대상에서 빠진다 — 운영에서 clean을 쓰지 않으므로 무해.
CREATE SCHEMA IF NOT EXISTS archive;

CREATE TABLE archive.archived_rows (
    id              bigserial PRIMARY KEY,
    -- 원본 테이블(스키마 한정). 예: 'app.saved_contents'
    table_name      text        NOT NULL,
    -- 복합 PK가 많아 단일 컬럼으로 못 담는다. 예: {"user_id":12,"short_code":"ABC"}
    row_pk          jsonb       NOT NULL,
    -- 조회 편의용 승격. entries처럼 user_id 컬럼이 없는 테이블은 NULL
    user_id         bigint,
    -- to_jsonb(원본 행) 전체. users만 직접 식별 컬럼을 뺀 가명화 형태로 넣는다
    payload         jsonb       NOT NULL,
    archived_at     timestamptz NOT NULL DEFAULT now(),
    -- ACCOUNT_DELETION / SAVED_REMOVED / CAMPAIGN_DELETED / REGISTRATION_ROLLBACK
    archived_reason text        NOT NULL
);

CREATE INDEX idx_archived_rows_table_time ON archive.archived_rows (table_name, archived_at);
CREATE INDEX idx_archived_rows_user ON archive.archived_rows (user_id) WHERE user_id IS NOT NULL;
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveSchemaTest"
```

Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V20260802090000__delete_archive.sql was/src/test/java/com/celfit/was/archive/ArchiveSchemaTest.java
git commit -m "feat(was): 삭제 아카이브 스키마·테이블 추가"
```

---

### Task 2: 아카이브 카탈로그와 Writer

**Files:**
- Create: `was/src/main/java/com/celfit/was/archive/ArchiveReason.java`
- Create: `was/src/main/java/com/celfit/was/archive/ArchiveTable.java`
- Create: `was/src/main/java/com/celfit/was/archive/ArchiveTables.java`
- Create: `was/src/main/java/com/celfit/was/archive/ArchiveWriter.java`
- Test: `was/src/test/java/com/celfit/was/archive/ArchiveWriterTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/archive/ArchiveWriterTest.java`:

```java
package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ArchiveWriterTest extends IntegrationTest {

	@Autowired
	ArchiveWriter archiveWriter;

	@Autowired
	JdbcClient jdbcClient;

	private long insertUser(String email) {
		return jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash, name, nickname, phone_number, company_name)
						VALUES (:email, 'hash', '홍길동', '길동', '01012345678', '하입나우')
						RETURNING id
						""")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	@Test
	void 복합PK_테이블은_row_pk에_두_컬럼을_모두_담는다() {
		long userId = insertUser("writer-1@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'ABC123')")
				.param("id", userId)
				.update();

		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.short_code = :shortCode",
				Map.of("userId", userId, "shortCode", "ABC123"));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT table_name, row_pk::text AS row_pk, user_id, archived_reason
						  FROM archive.archived_rows WHERE user_id = :id
						""")
				.param("id", userId)
				.query()
				.singleRow();

		assertThat(row.get("table_name")).isEqualTo("app.saved_contents");
		assertThat(row.get("row_pk").toString()).contains("\"user_id\": " + userId).contains("\"short_code\": \"ABC123\"");
		assertThat(row.get("user_id")).isEqualTo(userId);
		assertThat(row.get("archived_reason")).isEqualTo("SAVED_REMOVED");
	}

	@Test
	void payload는_원본_행_전체를_담는다() {
		long userId = insertUser("writer-2@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code, memo) VALUES (:id, 'XYZ789', '메모다')")
				.param("id", userId)
				.update();

		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId", Map.of("userId", userId));

		String memo = jdbcClient.sql("SELECT payload ->> 'memo' FROM archive.archived_rows WHERE user_id = :id")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(memo).isEqualTo("메모다");
	}

	@Test
	void users는_직접_식별_컬럼을_제거하고_id는_남긴다() {
		long userId = insertUser("writer-3@example.com");

		archiveWriter.archive(ArchiveTables.USERS, ArchiveReason.ACCOUNT_DELETION,
				"t.id = :userId", Map.of("userId", userId));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT payload::text AS payload, user_id FROM archive.archived_rows
						 WHERE table_name = 'app.users' AND user_id = :id
						""")
				.param("id", userId)
				.query()
				.singleRow();

		String payload = row.get("payload").toString();
		assertThat(payload)
				.doesNotContain("writer-3@example.com")
				.doesNotContain("password_hash")
				.doesNotContain("홍길동")
				.doesNotContain("01012345678");
		assertThat(payload).contains("하입나우");   // company_name은 자산이라 보존
		assertThat(row.get("user_id")).isEqualTo(userId);
	}

	@Test
	void user_id_컬럼이_없는_테이블은_user_id를_NULL로_남긴다() {
		long userId = insertUser("writer-4@example.com");
		long registrationId = jdbcClient.sql("""
						INSERT INTO app.monitoring_registrations (user_id, kind, requested_count)
						VALUES (:id, 'account', 1) RETURNING id
						""")
				.param("id", userId)
				.query(Long.class)
				.single();
		jdbcClient.sql("""
						INSERT INTO app.monitoring_registration_entries (registration_id, seq, input, result)
						VALUES (:rid, 1, 'someaccount', 'pending')
						""")
				.param("rid", registrationId)
				.update();

		archiveWriter.archive(ArchiveTables.MONITORING_REGISTRATION_ENTRIES, ArchiveReason.ACCOUNT_DELETION,
				"t.registration_id = :registrationId", Map.of("registrationId", registrationId));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT user_id, row_pk::text AS row_pk FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_registration_entries'
						""")
				.query()
				.singleRow();

		assertThat(row.get("user_id")).isNull();
		assertThat(row.get("row_pk").toString()).contains("\"registration_id\": " + registrationId).contains("\"seq\": 1");
	}

	@Test
	void 대상_행이_없으면_아무것도_안_남기고_예외도_없다() {
		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId", Map.of("userId", -1L));

		Long count = jdbcClient.sql("SELECT count(*) FROM archive.archived_rows WHERE user_id = -1")
				.query(Long.class)
				.single();

		assertThat(count).isZero();
	}
}
```

**주의**: `monitoring_registrations`/`monitoring_registration_entries`의 INSERT 컬럼은 V16 기준이다. 테스트 작성 시 `V16__monitoring_v3.sql`을 열어 NOT NULL 컬럼을 확인하고, 위 INSERT에 빠진 NOT NULL 컬럼이 있으면 값을 채워 넣어라(스키마가 계획 작성 이후 바뀌었을 수 있다).

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveWriterTest"
```

Expected: 컴파일 실패 — `ArchiveWriter`, `ArchiveTables`, `ArchiveReason` 심볼을 찾을 수 없음

- [ ] **Step 3: 구현 작성**

`ArchiveReason.java`:

```java
package com.celfit.was.archive;

/** 아카이브 이관 사유 — archive.archived_rows.archived_reason에 name()이 그대로 들어간다. */
public enum ArchiveReason {
	/** 회원 탈퇴 */
	ACCOUNT_DELETION,
	/** 저장 개별 해제 */
	SAVED_REMOVED,
	/** 캠페인 삭제 */
	CAMPAIGN_DELETED,
	/** 모니터링 등록 실패 롤백 */
	REGISTRATION_ROLLBACK
}
```

`ArchiveTable.java`:

```java
package com.celfit.was.archive;

import java.util.List;

/**
 * 아카이브 대상 테이블 1개의 메타데이터. 모든 값은 코드 상수라 SQL 조립에 그대로 써도 안전하다
 * (외부 입력이 섞이는 자리는 whereClause의 named parameter뿐).
 *
 * @param qualifiedName 스키마 한정 테이블명. 예: "app.saved_contents"
 * @param pkColumns     PK 컬럼 목록. 복합 PK면 2개 이상 — row_pk jsonb의 키가 된다
 * @param userIdExpr    archived_rows.user_id로 승격할 표현식("t.user_id" 등). 해당 컬럼이 없으면 null
 * @param omitColumns   payload에서 제외할 컬럼(가명화). 없으면 빈 리스트
 * @param userScopeWhere 특정 유저의 행 전체를 고르는 WHERE 절. named parameter는 :userId 하나만 쓴다
 */
public record ArchiveTable(
		String qualifiedName,
		List<String> pkColumns,
		String userIdExpr,
		List<String> omitColumns,
		String userScopeWhere) {
}
```

`ArchiveTables.java`:

```java
package com.celfit.was.archive;

import java.util.List;

/**
 * 아카이브 대상 카탈로그 — 단일 정본. 여기 없는 테이블은 ArchiveInventoryTest가 EXCLUDED에
 * 사유와 함께 등재돼 있는지 검사한다.
 *
 * <p>ACCOUNT_DELETION_ORDER는 탈퇴 시 이관 순서다. 이관(INSERT)은 전부 삭제(DELETE)보다
 * 먼저 일어나므로 순서 자체가 정확성에 영향을 주진 않지만, 자식 → 부모 순으로 읽히게 둔다.
 */
public final class ArchiveTables {

	/** 직접 식별 컬럼 7종 — 자연인을 특정한다. company_name 등 속성 컬럼은 자산이라 보존한다. */
	private static final List<String> USER_PII = List.of(
			"email", "password_hash", "name", "nickname",
			"phone_country_code", "phone_number", "profile_image_url");

	public static final ArchiveTable SAVED_CONTENTS = new ArchiveTable(
			"app.saved_contents", List.of("user_id", "short_code"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable SAVED_INFLUENCERS = new ArchiveTable(
			"app.saved_influencers", List.of("user_id", "handle"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_CAMPAIGNS = new ArchiveTable(
			"app.monitoring_campaigns", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_EMAIL_OPT_OUTS = new ArchiveTable(
			"app.monitoring_email_opt_outs", List.of("user_id", "event_type"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_ITEMS = new ArchiveTable(
			"app.monitoring_items", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_REGISTRATIONS = new ArchiveTable(
			"app.monitoring_registrations", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_DIGESTS = new ArchiveTable(
			"app.monitoring_digests", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/** user_id 컬럼이 없다 — registration을 거쳐야 유저에 닿는다(간접 CASCADE). */
	public static final ArchiveTable MONITORING_REGISTRATION_ENTRIES = new ArchiveTable(
			"app.monitoring_registration_entries", List.of("registration_id", "seq"), null,
			List.of(),
			"t.registration_id IN (SELECT id FROM app.monitoring_registrations WHERE user_id = :userId)");

	public static final ArchiveTable USERS = new ArchiveTable(
			"app.users", List.of("id"), "t.id",
			USER_PII, "t.id = :userId");

	/** 탈퇴 시 이관 대상 전체 — 자식 8개 + users. */
	public static final List<ArchiveTable> ACCOUNT_DELETION_ORDER = List.of(
			SAVED_CONTENTS,
			SAVED_INFLUENCERS,
			MONITORING_REGISTRATION_ENTRIES,
			MONITORING_REGISTRATIONS,
			MONITORING_ITEMS,
			MONITORING_DIGESTS,
			MONITORING_EMAIL_OPT_OUTS,
			MONITORING_CAMPAIGNS,
			USERS);

	private ArchiveTables() {
	}
}
```

`ArchiveWriter.java`:

```java
package com.celfit.was.archive;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 삭제 직전 원본 행을 archive.archived_rows로 이관한다. 행을 애플리케이션으로 끌어올리지 않고
 * INSERT … SELECT로 DB 안에서 끝낸다.
 *
 * <p>fail-closed — 이관이 실패하면 예외가 그대로 전파돼 트랜잭션이 롤백되고 삭제도 일어나지
 * 않는다. 자산 보존이 목적인데 조용히 유실되면 의미가 없기 때문이다. 따라서 호출부에는
 * 반드시 트랜잭션 경계가 있어야 한다.
 */
@Component
public class ArchiveWriter {

	/** whereClause의 named parameter와 충돌하면 안 되는 예약 이름. */
	private static final String REASON_PARAM = "archiveReason";

	private final JdbcClient jdbcClient;

	public ArchiveWriter(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * @param whereClause 원본 테이블 별칭 t를 쓰는 WHERE 절. 코드 상수여야 한다(외부 입력 금지)
	 * @param params      whereClause의 named parameter 값. "archiveReason" 키는 쓸 수 없다
	 */
	public void archive(ArchiveTable table, ArchiveReason reason, String whereClause, Map<String, Object> params) {
		if (params.containsKey(REASON_PARAM)) {
			throw new IllegalArgumentException("예약된 파라미터명이다: " + REASON_PARAM);
		}
		JdbcClient.StatementSpec spec = jdbcClient.sql(buildSql(table, whereClause))
				.param(REASON_PARAM, reason.name());
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		spec.update();
	}

	private static String buildSql(ArchiveTable table, String whereClause) {
		String pkJson = table.pkColumns().stream()
				.map(column -> "'" + column + "', t." + column)
				.collect(Collectors.joining(", "));
		String userIdExpr = table.userIdExpr() == null ? "NULL::bigint" : table.userIdExpr();
		String payload = table.omitColumns().stream()
				.map(column -> " - '" + column + "'")
				.collect(Collectors.joining("", "to_jsonb(t)", ""));

		return """
				INSERT INTO archive.archived_rows (table_name, row_pk, user_id, payload, archived_reason)
				SELECT '%s', jsonb_build_object(%s), %s, %s, :%s
				  FROM %s t
				 WHERE %s
				""".formatted(table.qualifiedName(), pkJson, userIdExpr, payload, REASON_PARAM,
				table.qualifiedName(), whereClause);
	}
}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveWriterTest"
```

Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/archive was/src/test/java/com/celfit/was/archive/ArchiveWriterTest.java
git commit -m "feat(was): 아카이브 카탈로그·Writer 추가"
```

---

### Task 3: 탈퇴 경로 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java` (`deleteAccount`)
- Test: `was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java` (테스트 추가)

`deleteAccount`에는 이미 `@Transactional`이 있다. 이관을 앞에 붙이기만 하면 원자성이 확보된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`UserRepositoryTest.java`에 아래 테스트를 추가한다(기존 import에 `com.celfit.was.archive` 관련은 필요 없다 — SQL로만 검증).

```java
@Test
void 탈퇴하면_유저와_자식_행이_모두_아카이브된다() {
	AppUser user = repository.insert("archive-me@example.com", "hashed");
	jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'SC1')")
			.param("id", user.id())
			.update();
	jdbcClient.sql("INSERT INTO app.saved_influencers (user_id, handle) VALUES (:id, 'someone')")
			.param("id", user.id())
			.update();

	repository.deleteAccount(user.id());

	List<String> archived = jdbcClient.sql("""
					SELECT table_name FROM archive.archived_rows
					 WHERE user_id = :id ORDER BY table_name
					""")
			.param("id", user.id())
			.query(String.class)
			.list();

	assertThat(archived).contains("app.users", "app.saved_contents", "app.saved_influencers");
	assertThat(jdbcClient.sql("SELECT count(*) FROM app.users WHERE id = :id")
			.param("id", user.id())
			.query(Long.class)
			.single()).isZero();
}

@Test
void 탈퇴_아카이브의_users_payload에는_이메일이_없다() {
	AppUser user = repository.insert("secret@example.com", "hashed");

	repository.deleteAccount(user.id());

	String payload = jdbcClient.sql("""
					SELECT payload::text FROM archive.archived_rows
					 WHERE table_name = 'app.users' AND user_id = :id
					""")
			.param("id", user.id())
			.query(String.class)
			.single();

	assertThat(payload).doesNotContain("secret@example.com").doesNotContain("hashed");
}
```

`java.util.List` import가 없으면 추가한다.

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.auth.UserRepositoryTest"
```

Expected: FAIL — `archived`가 비어 있어 `contains` 실패

- [ ] **Step 3: 구현 작성**

`UserRepository.java`를 아래와 같이 수정한다. 생성자에 `ArchiveWriter`를 주입하고, `deleteAccount` 본문 앞에 이관 루프를 넣는다.

```java
import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTable;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.util.Map;
```

```java
	private final JdbcClient jdbcClient;
	private final ArchiveWriter archiveWriter;

	public UserRepository(JdbcClient jdbcClient, ArchiveWriter archiveWriter) {
		this.jdbcClient = jdbcClient;
		this.archiveWriter = archiveWriter;
	}
```

```java
	/**
	 * 탈퇴(스펙 6.13) — saved 2종은 users FK가 CASCADE가 아니라 자식부터 순서 삭제, 한 트랜잭션.
	 * 세션 무효화·이미지 파일 정리는 DB 밖 자원이라 호출부가 커밋 후에 수행한다.
	 *
	 * <p>삭제 전 원본 행을 전부 아카이브한다(트랙 NN). CASCADE(V16)로 사라지는 자식과
	 * registrations를 거치는 간접 CASCADE(entries)까지 ArchiveTables.ACCOUNT_DELETION_ORDER가
	 * 전부 담고 있다 — 새 자식 테이블이 생기면 ArchiveCascadeReachabilityTest가 CI에서 막는다.
	 */
	@Transactional
	public void deleteAccount(long id) {
		for (ArchiveTable table : ArchiveTables.ACCOUNT_DELETION_ORDER) {
			archiveWriter.archive(table, ArchiveReason.ACCOUNT_DELETION, table.userScopeWhere(), Map.of("userId", id));
		}
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :id").param("id", id).update();
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :id").param("id", id).update();
		jdbcClient.sql("DELETE FROM app.users WHERE id = :id").param("id", id).update();
	}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.auth.UserRepositoryTest"
```

Expected: PASS (기존 테스트 전부 + 신규 2개)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/auth/UserRepository.java was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java
git commit -m "feat(was): 탈퇴 시 유저·자식 행 9종 아카이브"
```

---

### Task 4: 저장 해제 경로 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/saved/SavedRepository.java` (`deleteContent`, `deleteInfluencer`)
- Modify: `was/src/main/java/com/celfit/was/v1/saved/V1SavedRepository.java` (`deleteContent`, `deleteInfluencer`)
- Test: `was/src/test/java/com/celfit/was/v1/saved/V1SavedRepositoryTest.java` (없으면 생성)

두 리포지토리는 같은 SQL을 별 bean으로 중복 구현하고 있다(주석에 이유 명시). 둘 다 배선해야 한다. **두 곳 모두 `@Transactional`이 없으므로 함께 추가한다** — 없으면 아카이브와 삭제가 각각 auto-commit으로 나뉘어 fail-closed가 깨진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/v1/saved/V1SavedRepositoryTest.java`(이미 있으면 테스트 메서드만 추가):

```java
package com.celfit.was.v1.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class V1SavedRepositoryTest extends IntegrationTest {

	@Autowired
	V1SavedRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	private long insertUser(String email) {
		return jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash) VALUES (:email, 'hash') RETURNING id
						""")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	@Test
	void 콘텐츠_저장해제하면_아카이브에_남는다() {
		long userId = insertUser("unsave-content@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'SC9')")
				.param("id", userId)
				.update();

		repository.deleteContent(userId, "SC9");

		String reason = jdbcClient.sql("""
						SELECT archived_reason FROM archive.archived_rows
						 WHERE table_name = 'app.saved_contents' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(reason).isEqualTo("SAVED_REMOVED");
	}

	@Test
	void 인플루언서_저장해제하면_아카이브에_남는다() {
		long userId = insertUser("unsave-influencer@example.com");
		jdbcClient.sql("INSERT INTO app.saved_influencers (user_id, handle) VALUES (:id, 'someone9')")
				.param("id", userId)
				.update();

		repository.deleteInfluencer(userId, "someone9");

		String handle = jdbcClient.sql("""
						SELECT payload ->> 'handle' FROM archive.archived_rows
						 WHERE table_name = 'app.saved_influencers' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(handle).isEqualTo("someone9");
	}

	@Test
	void 없는_행을_해제해도_아카이브가_생기지_않는다() {
		long userId = insertUser("unsave-noop@example.com");

		repository.deleteContent(userId, "NOTHING");

		Long count = jdbcClient.sql("SELECT count(*) FROM archive.archived_rows WHERE user_id = :id")
				.param("id", userId)
				.query(Long.class)
				.single();

		assertThat(count).isZero();
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.saved.V1SavedRepositoryTest"
```

Expected: FAIL — `single()`이 결과 0건에서 `EmptyResultDataAccessException`

- [ ] **Step 3: 구현 작성**

`V1SavedRepository.java` — 생성자에 `ArchiveWriter` 주입 후 두 메서드를 아래로 교체한다.

```java
import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
```

```java
	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다(스펙 6.8). 삭제 전 아카이브(트랙 NN). */
	@Transactional
	public void deleteContent(long userId, String shortCode) {
		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.short_code = :shortCode",
				Map.of("userId", userId, "shortCode", shortCode));
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :userId AND short_code = :shortCode")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}

	/** 멱등 — 없는 행을 지워도 0건 삭제(스펙 6.11). 구 saved.SavedRepository와 동일 SQL(별 bean이라 재구현). */
	@Transactional
	public void deleteInfluencer(long userId, String handle) {
		archiveWriter.archive(ArchiveTables.SAVED_INFLUENCERS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.handle = :handle",
				Map.of("userId", userId, "handle", handle));
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :userId AND handle = :handle")
				.param("userId", userId)
				.param("handle", handle)
				.update();
	}
```

`SavedRepository.java`(구 표면) — 같은 import를 추가하고 생성자에 `ArchiveWriter`를 주입한 뒤, 두 메서드를 아래로 교체한다. 메서드 순서가 V1SavedRepository와 반대(인플루언서가 먼저)이니 주의한다.

```java
	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다. 삭제 전 아카이브(트랙 NN). */
	@Transactional
	public void deleteInfluencer(long userId, String handle) {
		archiveWriter.archive(ArchiveTables.SAVED_INFLUENCERS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.handle = :handle",
				Map.of("userId", userId, "handle", handle));
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :userId AND handle = :handle")
				.param("userId", userId)
				.param("handle", handle)
				.update();
	}

	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다. 삭제 전 아카이브(트랙 NN). */
	@Transactional
	public void deleteContent(long userId, String shortCode) {
		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.short_code = :shortCode",
				Map.of("userId", userId, "shortCode", shortCode));
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :userId AND short_code = :shortCode")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}
```

생성자는 다음과 같이 바뀐다.

```java
	private final JdbcClient jdbcClient;
	private final ArchiveWriter archiveWriter;

	public SavedRepository(JdbcClient jdbcClient, ArchiveWriter archiveWriter) {
		this.jdbcClient = jdbcClient;
		this.archiveWriter = archiveWriter;
	}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.saved.V1SavedRepositoryTest" --tests "com.celfit.was.saved.*"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/saved/SavedRepository.java was/src/main/java/com/celfit/was/v1/saved/V1SavedRepository.java was/src/test/java/com/celfit/was/v1/saved/V1SavedRepositoryTest.java
git commit -m "feat(was): 저장 해제 시 아카이브 이관"
```

---

### Task 5: 캠페인 삭제 경로 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/CampaignRepository.java` (`delete`)
- Test: `was/src/test/java/com/celfit/was/monitoring/CampaignRepositoryTest.java` (없으면 생성)

캠페인 삭제 시 `monitoring_items.campaign_id`는 `SET NULL`이라 item은 남는다 — 아카이브 대상은 캠페인 행 하나뿐이다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CampaignRepositoryTest extends IntegrationTest {

	@Autowired
	CampaignRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 캠페인을_삭제하면_아카이브에_남는다() {
		long userId = jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash) VALUES ('campaign@example.com', 'hash') RETURNING id
						""")
				.query(Long.class)
				.single();
		long campaignId = jdbcClient.sql("""
						INSERT INTO app.monitoring_campaigns (user_id, name) VALUES (:id, '여름 캠페인') RETURNING id
						""")
				.param("id", userId)
				.query(Long.class)
				.single();

		repository.delete(campaignId);

		String name = jdbcClient.sql("""
						SELECT payload ->> 'name' FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_campaigns' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(name).isEqualTo("여름 캠페인");
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", campaignId)
				.query(Long.class)
				.single()).isZero();
	}
}
```

**주의**: `monitoring_campaigns`의 NOT NULL 컬럼을 `V16__monitoring_v3.sql`에서 확인하고, `name` 외에 필수 컬럼이 있으면 INSERT에 채워 넣어라.

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.CampaignRepositoryTest"
```

Expected: FAIL — `single()`이 결과 0건에서 `EmptyResultDataAccessException`

- [ ] **Step 3: 구현 작성**

`CampaignRepository.java` — 생성자에 `ArchiveWriter` 주입 후:

```java
import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
```

```java
	/** 삭제 전 아카이브(트랙 NN). items는 campaign_id가 SET NULL로 풀릴 뿐이라 대상 아님. */
	@Transactional
	public void delete(long id) {
		archiveWriter.archive(ArchiveTables.MONITORING_CAMPAIGNS, ArchiveReason.CAMPAIGN_DELETED,
				"t.id = :campaignId", Map.of("campaignId", id));
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", id)
				.update();
	}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.CampaignRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/CampaignRepository.java was/src/test/java/com/celfit/was/monitoring/CampaignRepositoryTest.java
git commit -m "feat(was): 캠페인 삭제 시 아카이브 이관"
```

---

### Task 6: 등록 실패 롤백 경로 배선

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java` (`delete`)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java` (없으면 생성)

호출부(`MonitoringRegistrationExecutor` 3곳)는 수정하지 않는다 — 리포지토리 메서드 안에서 이관과 삭제가 한 트랜잭션으로 끝난다. `entries.item_id`가 `SET NULL`이라 delete 이후 item 역조회가 불가능해지는 기존 제약은 그대로다(호출부가 이미 id를 먼저 확보한다).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringItemRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringItemRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 등록_롤백으로_item을_지우면_아카이브에_남는다() {
		long userId = jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash) VALUES ('rollback@example.com', 'hash') RETURNING id
						""")
				.query(Long.class)
				.single();
		long itemId = jdbcClient.sql("""
						INSERT INTO app.monitoring_items (user_id, kind, input) VALUES (:id, 'account', 'someacct')
						RETURNING id
						""")
				.param("id", userId)
				.query(Long.class)
				.single();

		repository.delete(itemId);

		String reason = jdbcClient.sql("""
						SELECT archived_reason FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_items' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(reason).isEqualTo("REGISTRATION_ROLLBACK");
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.monitoring_items WHERE id = :id")
				.param("id", itemId)
				.query(Long.class)
				.single()).isZero();
	}
}
```

**주의**: `monitoring_items`의 NOT NULL 컬럼을 `V16__monitoring_v3.sql`에서 확인하고 INSERT를 맞춰라.

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringItemRepositoryTest"
```

Expected: FAIL — `single()`이 결과 0건에서 `EmptyResultDataAccessException`

- [ ] **Step 3: 구현 작성**

`MonitoringItemRepository.java` — 생성자에 `ArchiveWriter` 주입 후:

```java
import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
```

```java
	/** 등록 실패 롤백 — 삭제 전 아카이브(트랙 NN). 실패한 등록 시도의 원인 이력이 남는다. */
	@Transactional
	public void delete(long itemId) {
		archiveWriter.archive(ArchiveTables.MONITORING_ITEMS, ArchiveReason.REGISTRATION_ROLLBACK,
				"t.id = :targetItemId", Map.of("targetItemId", itemId));
		jdbcClient.sql("DELETE FROM app.monitoring_items WHERE id = :itemId")
				.param("itemId", itemId)
				.update();
	}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringItemRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java
git commit -m "feat(was): 등록 롤백 시 item 아카이브 이관"
```

---

### Task 7: 가드 — app 스키마 분류 완전성 테스트

**Files:**
- Create: `was/src/test/java/com/celfit/was/archive/ArchiveInventoryTest.java`

새 테이블이 생기면 이 테스트가 CI에서 깨진다. 사람이 기억할 필요가 없게 만드는 것이 이 태스크의 목적이다.

- [ ] **Step 1: 테스트 작성 (이번엔 처음부터 통과해야 정상)**

```java
package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app 스키마의 모든 테이블은 아카이브 대상이거나, 사유가 적힌 제외 대상이거나 둘 중 하나여야 한다.
 * 새 테이블을 만들면 이 테스트가 깨진다 — 의도적이다(트랙 NN).
 */
class ArchiveInventoryTest extends IntegrationTest {

	/** 아카이브하지 않는 테이블과 그 사유. 여기 추가할 때는 반드시 사유를 적을 것. */
	private static final Map<String, String> EXCLUDED = Map.ofEntries(
			Map.entry("flyway_schema_history", "Flyway 이력 테이블 — 우리 데이터가 아니다"),
			Map.entry("spring_session", "세션 토큰. 자산 가치 없음"),
			Map.entry("spring_session_attributes", "세션 속성. 자산 가치 없음"),
			Map.entry("gate_events", "삭제 경로 없음. FK도 없어 탈퇴에도 보존된다(V5 주석의 의도)"),
			Map.entry("app_setting", "was 런타임 설정값"),
			Map.entry("email_verifications", "만료성 인증 코드"),
			Map.entry("signup_codes", "삭제 경로 없음(used_by가 SET NULL로 끊길 뿐)"),
			Map.entry("signup_events", "삭제 경로 없음"),
			Map.entry("inquiries", "삭제 경로 없음"));

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void app_스키마의_모든_테이블은_분류돼_있어야_한다() {
		List<String> actual = jdbcClient.sql("""
						SELECT table_name FROM information_schema.tables
						 WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
						""")
				.query(String.class)
				.list();

		Set<String> archived = ArchiveTables.ACCOUNT_DELETION_ORDER.stream()
				.map(table -> table.qualifiedName().replace("app.", ""))
				.collect(Collectors.toSet());

		List<String> unclassified = actual.stream()
				.filter(name -> !archived.contains(name) && !EXCLUDED.containsKey(name))
				.toList();

		assertThat(unclassified)
				.as("""
						분류되지 않은 app 테이블이 있다: %s
						탈퇴 시 아카이브할 테이블이면 ArchiveTables에 ArchiveTable을 추가하고
						ACCOUNT_DELETION_ORDER에 넣어라. 아카이브하지 않을 테이블이면
						ArchiveInventoryTest.EXCLUDED에 사유와 함께 등재하라.
						""".formatted(unclassified))
				.isEmpty();
	}

	@Test
	void 제외_목록에_죽은_항목이_없어야_한다() {
		List<String> actual = jdbcClient.sql("""
						SELECT table_name FROM information_schema.tables
						 WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
						""")
				.query(String.class)
				.list();

		List<String> stale = EXCLUDED.keySet().stream().filter(name -> !actual.contains(name)).toList();

		assertThat(stale)
				.as("EXCLUDED에 이미 없어진 테이블이 남아 있다: %s — 목록에서 지워라".formatted(stale))
				.isEmpty();
	}
}
```

- [ ] **Step 2: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveInventoryTest"
```

Expected: PASS (2 tests). **FAIL이면 그것이 발견이다** — 계획 작성 시점(17개) 이후 테이블이 늘었다는 뜻이니, 실패 메시지가 지목한 테이블을 `ArchiveTables` 또는 `EXCLUDED` 중 맞는 쪽에 사유와 함께 등재하고 다시 돌려라.

- [ ] **Step 3: 가드가 실제로 작동하는지 확인**

`EXCLUDED`에서 `Map.entry("inquiries", ...)` 한 줄을 임시로 주석 처리하고 테스트를 다시 돌린다.

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveInventoryTest"
```

Expected: FAIL — 메시지에 `[inquiries]`와 안내문이 보여야 한다. 확인 후 주석을 되돌리고 다시 PASS를 확인한다. **이 단계를 건너뛰지 마라** — 항상 통과하는 가드는 가드가 아니다.

- [ ] **Step 4: 커밋**

```bash
git add was/src/test/java/com/celfit/was/archive/ArchiveInventoryTest.java
git commit -m "test(was): app 스키마 아카이브 분류 완전성 가드"
```

---

### Task 8: 가드 — CASCADE 재귀 도달성 테스트

**Files:**
- Create: `was/src/test/java/com/celfit/was/archive/ArchiveCascadeReachabilityTest.java`

설계 단계에서 `monitoring_registration_entries`를 놓쳤던 이유가 직접 FK만 봤기 때문이다. 이 테스트는 재귀로 훑는다.

- [ ] **Step 1: 테스트 작성**

```java
package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app.users를 지우면 ON DELETE CASCADE를 타고 함께 사라지는 테이블 전부가 탈퇴 아카이브
 * 대상이어야 한다. 직접 FK만 보면 monitoring_registration_entries 같은 간접 연쇄를 놓친다 —
 * 실제로 설계 단계에서 놓쳤다(트랙 NN).
 */
class ArchiveCascadeReachabilityTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void users에서_CASCADE로_도달하는_테이블은_전부_아카이브_대상이다() {
		List<String> reachable = jdbcClient.sql("""
						WITH RECURSIVE cascaded AS (
						    SELECT c.conrelid AS rel
						      FROM pg_constraint c
						     WHERE c.contype = 'f'
						       AND c.confdeltype = 'c'
						       AND c.confrelid = 'app.users'::regclass
						    UNION
						    SELECT c.conrelid
						      FROM pg_constraint c
						      JOIN cascaded p ON c.confrelid = p.rel
						     WHERE c.contype = 'f'
						       AND c.confdeltype = 'c'
						)
						SELECT DISTINCT rel::regclass::text FROM cascaded
						""")
				.query(String.class)
				.list();

		Set<String> archived = ArchiveTables.ACCOUNT_DELETION_ORDER.stream()
				.map(ArchiveTable::qualifiedName)
				.collect(Collectors.toSet());

		// regclass는 search_path에 따라 스키마를 생략할 수 있어 app. 접두사를 보정한다
		List<String> missing = reachable.stream()
				.map(name -> name.contains(".") ? name : "app." + name)
				.filter(name -> !archived.contains(name))
				.toList();

		assertThat(missing)
				.as("""
						탈퇴 시 CASCADE로 사라지는데 아카이브되지 않는 테이블이 있다: %s
						ArchiveTables에 ArchiveTable을 추가하고 ACCOUNT_DELETION_ORDER에 넣어라.
						""".formatted(missing))
				.isEmpty();
	}

	@Test
	void 간접_CASCADE인_entries가_실제로_탐지된다() {
		List<String> reachable = jdbcClient.sql("""
						WITH RECURSIVE cascaded AS (
						    SELECT c.conrelid AS rel
						      FROM pg_constraint c
						     WHERE c.contype = 'f' AND c.confdeltype = 'c' AND c.confrelid = 'app.users'::regclass
						    UNION
						    SELECT c.conrelid
						      FROM pg_constraint c
						      JOIN cascaded p ON c.confrelid = p.rel
						     WHERE c.contype = 'f' AND c.confdeltype = 'c'
						)
						SELECT DISTINCT rel::regclass::text FROM cascaded
						""")
				.query(String.class)
				.list();

		assertThat(reachable.stream().map(name -> name.contains(".") ? name : "app." + name))
				.contains("app.monitoring_registration_entries");
	}
}
```

- [ ] **Step 2: 테스트를 돌려 통과를 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.archive.ArchiveCascadeReachabilityTest"
```

Expected: PASS (2 tests). 두 번째 테스트가 실패하면 재귀 CTE가 간접 연쇄를 못 잡고 있다는 뜻이니 쿼리를 먼저 고쳐라 — 첫 번째 테스트만으로는 "가드가 아무것도 안 잡는 상태"를 구분할 수 없다.

- [ ] **Step 3: 커밋**

```bash
git add was/src/test/java/com/celfit/was/archive/ArchiveCascadeReachabilityTest.java
git commit -m "test(was): CASCADE 재귀 도달성 가드"
```

---

### Task 9: 전체 검증과 PR

- [ ] **Step 1: was 모듈 전체 테스트**

```bash
./gradlew :was:test
```

Expected: PASS. 대량 실패가 보이면 `DOCKER_HOST`부터 확인하라(사전 준비 참고) — 코드 결함으로 오진하기 쉽다.

- [ ] **Step 2: 전체 테스트 (PR 직전에만)**

```bash
./gradlew test
```

Expected: PASS. 로컬에서 느리면 colima 자원(`--cpu 8 --memory 12`)을 먼저 확인한다.

- [ ] **Step 3: 마이그레이션 가드 확인**

```bash
./deploy/scripts/check-migration-safety.sh
```

Expected: PASS — 이번 마이그레이션은 순수 추가(CREATE)라 파괴적 변경이 없다.

- [ ] **Step 4: DECISIONS.md 갱신**

파일 맨 위(가장 최신 항목 자리)에 아래를 추가한다.

```markdown
## 2026-08-02 — 삭제 데이터는 soft delete 대신 아카이브 이관 (트랙 NN)

was의 삭제는 거의 전부 hard delete였고, 사용자 행동 시그널이 복구 불가로 사라지고 있었다.
전면 soft delete는 기각했다 — was 조회가 JdbcClient 생 SQL이라 `deleted_at IS NULL` 누락을
컴파일러가 못 잡고, 누락 시 삭제한 데이터가 사용자에게 다시 보인다. `users.email` UNIQUE
충돌과 CASCADE 무력화도 따라온다.

대신 삭제 직전 같은 트랜잭션으로 `archive.archived_rows`(단일 범용 jsonb 테이블)에 원본 행을
이관하고, 라이브 삭제·조회 경로는 그대로 뒀다. **was가 `app` 외 스키마에 쓰는 첫 사례**다 —
같은 DB여야 트랜잭션이 묶이고, 스키마를 분리해두면 축적량이 커졌을 때 옮기기 쉽다.

`users`는 직접 식별 컬럼 7종을 뺀 가명화 형태로 이관한다(`id` 유지 — 자식 조인이 살아야
자산 가치가 있다). 누락 방지는 가드 테스트 2종(app 스키마 분류 완전성, CASCADE 재귀 도달성)이
CI에서 담당한다.
```

- [ ] **Step 5: 트랙 문서 생성**

`docs/tracks/NN-삭제-데이터-아카이브.md`:

```markdown
# 트랙 NN — 삭제 데이터 아카이브

> 상태: 🟢 활성 · 시작 2026-08-02

## 목표
hard delete로 사라지던 행을 삭제 직전 `archive.archived_rows`로 원본 그대로 이관한다.

## 범위 (이번 트랙)
- `archive` 스키마 + `archived_rows` 테이블
- 이관 경로 4종 — 탈퇴(테이블 9개), 저장 해제, 캠페인 삭제, 등록 실패 롤백
- 가드 테스트 2종 — 분류 완전성, CASCADE 재귀 도달성

## 범위 밖 (후속)
- 불필요 데이터 정리 배치 잡
- 아카이브 → 라이브 복원 도구
- crawler·analytics 삭제 지점
- 아카이브 데이터 분석 표면

## 설계
[docs/superpowers/specs/2026-08-02-delete-archive-design.md](../superpowers/specs/2026-08-02-delete-archive-design.md)

## 진행
- 2026-08-02 설계·계획 작성, 구현 착수
```

- [ ] **Step 6: 커밋하고 PR을 연다**

```bash
git add DECISIONS.md docs/tracks/NN-삭제-데이터-아카이브.md
git commit -m "docs: 삭제 데이터 아카이브 트랙 NN 등재"
git push -u origin feat/delete-archive
```

```bash
gh pr create --base develop --title "feat(was): 삭제 데이터 아카이브" --body "$(cat <<'EOF'
## 무엇

hard delete되던 행을 삭제 직전 `archive.archived_rows`로 원본 그대로 이관한다.
라이브 삭제 로직과 조회 경로는 바뀌지 않는다.

## 왜 soft delete가 아닌가

was 조회는 JdbcClient 생 SQL이라 `deleted_at IS NULL` 누락을 컴파일러가 못 잡고,
하나만 빠지면 삭제한 데이터가 사용자에게 다시 보인다. `users.email` UNIQUE 충돌과
CASCADE 무력화도 따라온다. 아카이브 이관은 조회 경로를 건드리지 않아 이 결함이
원리적으로 발생하지 않는다.

## 이관 경로 4종

- 탈퇴 — 테이블 9개(saved 2종 + CASCADE 5종 + 간접 CASCADE `monitoring_registration_entries` + users)
- 저장 개별 해제 / 캠페인 삭제 / 등록 실패 롤백

`users`는 직접 식별 컬럼 7종을 뺀 가명화 형태로 이관한다(`id`는 유지).

## 누락 방지

- `ArchiveInventoryTest` — app 스키마 전 테이블이 아카이브 대상이거나 사유가 적힌 제외 대상이어야 한다. 새 테이블이 생기면 CI가 깨진다
- `ArchiveCascadeReachabilityTest` — users에서 CASCADE로 재귀 도달하는 테이블이 전부 아카이브 대상인지 검사. 설계 단계에서 놓쳤던 간접 연쇄를 잡는다

## 배포 주의

마이그레이션과 코드가 한 PR에 있어 롤링 창에서 구 인스턴스가 처리한 삭제분은
아카이브되지 않는다(설계 §10 수용 리스크). 창을 없애려면 두 번에 나눠 배포한다.

설계: docs/superpowers/specs/2026-08-02-delete-archive-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**배포는 2단계로 나눈다** — 마이그레이션(Task 1)이 먼저 운영에 반영된 뒤 코드가 올라가야 한다. 이번 PR은 둘을 함께 담고 있으므로, 롤링 창에서 구 인스턴스가 처리한 삭제분은 아카이브되지 않는다(설계 §10에서 수용하기로 한 리스크). 창을 없애려면 PR을 마이그레이션과 코드로 쪼개 두 번 배포한다.
