# was ↔ monitoring 통신 계층 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현됨 (2026-07-29, feat/monitoring-was-seam)
> 스펙: [specs/2026-07-28-monitoring-was-seam-design.md](../../specs/2026-07-28-monitoring-was-seam-design.md)
> 계약 스냅샷: [docs/contracts/monitoring-was-contract.md](../../../contracts/monitoring-was-contract.md)
> 작업 위치: 워크트리 `.worktrees/monitoring-seam`, 브랜치 `feat/monitoring-was-seam`

**Goal:** was가 monitoring 컨테이너와 통신하는 계층 전부 — 명령 클라이언트(5개), 읽기 전용 조회 계층, app 매핑 테이블, 오케스트레이션 서비스. 프론트 `/v1` 컨트롤러·이메일 크론은 범위 밖.

**Architecture:** `monitoring.enabled=false` 기본의 조건부 활성화. monitoring용 DataSource·JdbcClient는 **빈으로 노출 금지**(Spring Boot 자동구성 back-off 회피 — DataSource·JdbcClient 자동구성 모두 `@ConditionalOnMissingBean`이라 빈을 노출하면 기존 analysis DB 배선이 깨진다). 등록은 멱등키 선저장 2단계(INSERT pending → 호출 → UPDATE 확정).

**Tech Stack:** Spring Boot 4.1 (Java 21), RestClient + JdkClientHttpRequestFactory(PATCH 지원), JdbcClient, Flyway(app 스키마), Jackson 3(`tools.jackson.*`, 애노테이션은 `com.fasterxml.jackson.annotation` 유지), Testcontainers(공유 싱글턴 `IntegrationTest`), MockRestServiceServer, Mockito, AssertJ.

**컨벤션 주의:** 주석·테스트 메서드명·커밋 메시지는 한국어. 들여쓰기는 탭. record DTO. 테스트는 `./gradlew :was:test --tests "..."`(Docker Desktop/colima 필요 — Testcontainers).

---

## 파일 구조 (전체 조감)

```
was/src/main/resources/db/migration/app/V13__monitoring_campaigns.sql   ← Task 1
was/src/main/resources/application.yml                                  ← Task 2 (monitoring.enabled 기본값 추가)
was/src/main/java/com/celfit/was/monitoring/
  MonitoringCampaignMapping.java            ← Task 1  app 매핑 1행 record
  MonitoringCampaignMappingRepository.java  ← Task 1  app.monitoring_campaigns CRUD (@Repository, 항상 활성)
  MonitoringConfig.java                     ← Task 2  조건부 빈 조립 (도메인 빈 3개만 노출)
  MonitoringException.java                  ← Task 3  공통 베이스 (abstract)
  MonitoringApiException.java               ← Task 3  monitoring이 준 에러 code 승격
  MonitoringUnavailableException.java       ← Task 3  전송 실패 — 같은 키 재시도 가능 신호
  CampaignNotFoundException.java            ← Task 5  (user, target) 매핑 없음 — was 소유 검증 실패
  KeywordRule.java                          ← Task 3  {and, any, exclude}
  RegisterRequest.java                      ← Task 3  등록 요청 (ACCOUNT/POST 공용)
  RegisterResult.java                       ← Task 3  등록 응답 (firstSnapshot은 불투명 JsonNode)
  ApproveResult.java / RejectResult.java / ExtendResult.java / CancelResult.java  ← Task 3
  MonitoringCommandClient.java              ← Task 3  명령 5개 HTTP + 에러 승격
  TargetRow.java / CandidateRow.java / PendingCandidate.java
  ProfileSnapshotRow.java / PostSnapshotRow.java                        ← Task 4  조회 record (계약 §3 컬럼 그대로)
  MonitoringReadRepository.java             ← Task 4  베이스 테이블 4개 SELECT
  MonitoringCampaignService.java            ← Task 5  2단계 등록·소유 검증·삭제 순서
was/src/test/resources/monitoring-schema.sql                            ← Task 4  계약 §3 유도 DDL 픽스처
was/src/test/java/com/celfit/was/monitoring/
  MonitoringCampaignMappingRepositoryTest.java  ← Task 1
  MonitoringCommandClientTest.java              ← Task 3
  MonitoringReadRepositoryTest.java             ← Task 4
  MonitoringCampaignServiceTest.java            ← Task 5
was/src/test/java/com/celfit/was/
  MonitoringDisabledTest.java               ← Task 2  (비활성 기본 — 모니터링 빈 부재)
  MonitoringEnabledConfigTest.java          ← Task 2  (com.celfit.was 패키지 — IntegrationTest.POSTGRES 접근 필요)
```

---

### Task 1: app 매핑 테이블 (V13) + MonitoringCampaignMappingRepository

**Files:**
- Create: `was/src/main/resources/db/migration/app/V13__monitoring_campaigns.sql`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignMapping.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignMappingRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringCampaignMappingRepositoryTest.java`

- [ ] **Step 1: 마이그레이션 작성** (Flyway는 테스트 부팅 시 자동 적용되므로 코드보다 먼저)

```sql
-- 마케팅 모니터링 캠페인 매핑 — was가 보관하는 (user, target, 멱등키) + 등록 2단계의 pending 상태.
-- target_id는 monitoring DB target.id의 논리 참조 (크로스 DB — FK 금지, saved_influencers.handle 관용구).
-- target_id NULL = 등록 1단계(pending): monitoring 호출 전 선저장, 호출 성공 시 UPDATE로 확정.
CREATE TABLE app.monitoring_campaigns (
    id               bigserial PRIMARY KEY,
    user_id          bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    registration_key uuid   NOT NULL UNIQUE,   -- was가 생성하는 멱등 키 (계약 §2-1)
    target_id        bigint,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX monitoring_campaigns_user_idx ON app.monitoring_campaigns (user_id);
```

- [ ] **Step 2: record 작성**

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * app.monitoring_campaigns 1행 — targetId는 monitoring DB target.id의 논리 참조(FK 아님).
 * targetId가 null이면 등록 2단계가 끝나지 않은 pending 행.
 */
public record MonitoringCampaignMapping(long id, long userId, UUID registrationKey,
		Long targetId, OffsetDateTime createdAt) {
}
```

- [ ] **Step 3: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringCampaignMappingRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringCampaignMappingRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		// email unique — 테스트 간 충돌 방지로 매번 랜덤
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "mon-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 등록_2단계_선저장과_확정() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);

		// 1단계 직후: pending (target_id NULL) — 소유 조회엔 아직 안 잡힘
		assertThat(repository.findByUserAndTarget(userId, 17L)).isEmpty();

		repository.confirmTarget(key, 17L);

		MonitoringCampaignMapping mapping = repository.findByUserAndTarget(userId, 17L).orElseThrow();
		assertThat(mapping.registrationKey()).isEqualTo(key);
		assertThat(mapping.targetId()).isEqualTo(17L);
	}

	@Test
	void 소유_검증은_다른_유저의_target을_거른다() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);
		repository.confirmTarget(key, 42L);

		assertThat(repository.findByUserAndTarget(userId + 999, 42L)).isEmpty();
	}

	@Test
	void 키_삭제는_pending_행을_지운다() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);
		repository.deleteByKey(key);

		assertThat(repository.findByUser(userId)).isEmpty();
	}

	@Test
	void 유저_target_삭제와_목록_조회() {
		UUID key1 = UUID.randomUUID();
		UUID key2 = UUID.randomUUID();
		repository.insertPending(userId, key1);
		repository.confirmTarget(key1, 1L);
		repository.insertPending(userId, key2);
		repository.confirmTarget(key2, 2L);

		assertThat(repository.findByUser(userId)).hasSize(2);

		repository.deleteByUserAndTarget(userId, 1L);

		assertThat(repository.findByUser(userId)).hasSize(1);
		assertThat(repository.findByUserAndTarget(userId, 1L)).isEmpty();
	}
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCampaignMappingRepositoryTest"`
Expected: 컴파일 실패 (`MonitoringCampaignMappingRepository` 없음)

- [ ] **Step 5: 리포지토리 구현**

```java
package com.celfit.was.monitoring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_campaigns CRUD — 모니터링 비활성이어도 무해한 app 테이블 접근이라 항상 활성.
 * 주입되는 JdbcClient는 기본(analysis DB) 것 — monitoring DB 접근은 MonitoringReadRepository 몫.
 */
@Repository
public class MonitoringCampaignMappingRepository {

	private final JdbcClient jdbcClient;

	public MonitoringCampaignMappingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 등록 1단계 — target_id NULL의 pending 행 선저장. 크래시 후에도 멱등키가 남아 replay 가능. */
	public long insertPending(long userId, UUID registrationKey) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_campaigns (user_id, registration_key)
				VALUES (:userId, :key)
				RETURNING id
				""")
				.param("userId", userId)
				.param("key", registrationKey)
				.query(Long.class)
				.single();
	}

	/** 등록 2단계 — monitoring 호출 성공 후 target_id 확정. */
	public void confirmTarget(UUID registrationKey, long targetId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_campaigns SET target_id = :targetId
				WHERE registration_key = :key
				""")
				.param("targetId", targetId)
				.param("key", registrationKey)
				.update();
	}

	/** 등록 확정 실패(monitoring이 target을 안 만든 경우) 시 pending 행 정리. */
	public void deleteByKey(UUID registrationKey) {
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns WHERE registration_key = :key")
				.param("key", registrationKey)
				.update();
	}

	/** 소유 검증 — (user, target) 매핑이 있어야 명령을 위임한다. pending(target NULL)은 안 잡힌다. */
	public Optional<MonitoringCampaignMapping> findByUserAndTarget(long userId, long targetId) {
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_campaigns
				WHERE user_id = :userId AND target_id = :targetId
				""")
				.param("userId", userId)
				.param("targetId", targetId)
				.query(MonitoringCampaignMapping.class)
				.optional();
	}

	public List<MonitoringCampaignMapping> findByUser(long userId) {
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_campaigns
				WHERE user_id = :userId
				ORDER BY created_at DESC
				""")
				.param("userId", userId)
				.query(MonitoringCampaignMapping.class)
				.list();
	}

	/** 유저의 "캠페인 삭제" — cancel 명령 성공 후 호출 (순서는 서비스가 보장). */
	public void deleteByUserAndTarget(long userId, long targetId) {
		jdbcClient.sql("""
				DELETE FROM app.monitoring_campaigns
				WHERE user_id = :userId AND target_id = :targetId
				""")
				.param("userId", userId)
				.param("targetId", targetId)
				.update();
	}
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCampaignMappingRepositoryTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V13__monitoring_campaigns.sql was/src/main/java/com/celfit/was/monitoring/ was/src/test/java/com/celfit/was/monitoring/
git commit -m "feat(was): 모니터링 캠페인 매핑 테이블(V13)·리포지토리 — 멱등키 선저장 2단계 등록의 저장소"
```

---

### Task 2: MonitoringConfig — 조건부 활성화, 인프라 빈 비노출

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java`
- Modify: `was/src/main/resources/application.yml` (마지막에 monitoring 블록 추가)
- Test: `was/src/test/java/com/celfit/was/MonitoringDisabledTest.java`
- Test: `was/src/test/java/com/celfit/was/MonitoringEnabledConfigTest.java`

주의: 이 Task의 Config는 Task 3~5에서 만들 `MonitoringCommandClient`·`MonitoringReadRepository`·`MonitoringCampaignService`를 참조한다. **컴파일을 위해 이 Task에서는 Config에 monitoring 내부 JdbcClient·RestClient 조립까지만 넣고, 도메인 빈 3개의 @Bean 메서드는 각 Task에서 추가한다.** 여기서는 검증 가능한 최소 형태(JdbcClient 내부 생성 + 커넥션 확인)로 시작한다.

- [ ] **Step 1: 실패하는 테스트 2개 작성**

`was/src/test/java/com/celfit/was/MonitoringDisabledTest.java`:

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** monitoring.enabled 미설정(기본 false) — 모니터링 빈이 아예 안 뜨고 기존 배선 무손상. */
class MonitoringDisabledTest extends IntegrationTest {

	@Autowired
	ApplicationContext context;

	@Test
	void 비활성_기본값이면_모니터링_구성이_없다() {
		assertThat(context.getBeanNamesForType(MonitoringConfig.class)).isEmpty();
	}

	@Test
	void 기본_JdbcClient는_하나뿐이다() {
		assertThat(context.getBeansOfType(JdbcClient.class)).hasSize(1);
	}
}
```

`was/src/test/java/com/celfit/was/MonitoringEnabledConfigTest.java` (POSTGRES가 패키지 프라이빗이라 이 패키지에 둔다 — monitoring DB는 별도 컨테이너 대신 공유 컨테이너의 같은 DB로 대신한다. 접속 문자열만 다르면 되므로 검증 목적엔 충분):

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * monitoring.enabled=true — 모니터링 구성이 뜨되, 기본 DataSource·JdbcClient 자동구성이
 * back-off 하지 않는다(인프라 빈 비노출 설계 검증 — 스펙 §3).
 */
@TestPropertySource(properties = "monitoring.enabled=true")
class MonitoringEnabledConfigTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	ApplicationContext context;

	@Test
	void 활성이면_모니터링_구성이_뜬다() {
		assertThat(context.getBeanNamesForType(MonitoringConfig.class)).hasSize(1);
	}

	@Test
	void 기본_JdbcClient는_여전히_하나뿐이다() {
		// monitoring 내부 JdbcClient가 빈으로 새어 나오면 여기가 2가 되며 기존 리포지토리 주입이 전부 깨진다
		assertThat(context.getBeansOfType(JdbcClient.class)).hasSize(1);
	}

	@Test
	void 모니터링_DB_조회가_동작한다() {
		MonitoringConfig config = context.getBean(MonitoringConfig.class);
		Integer one = config.monitoringJdbc().sql("SELECT 1").query(Integer.class).single();
		assertThat(one).isEqualTo(1);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.MonitoringDisabledTest" --tests "com.celfit.was.MonitoringEnabledConfigTest"`
Expected: 컴파일 실패 (`MonitoringConfig` 없음)

- [ ] **Step 3: MonitoringConfig 구현**

```java
package com.celfit.was.monitoring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * 모니터링 통신 계층 조립 — monitoring.enabled=true일 때만 뜬다(기본 비활성: 컨테이너 미배포
 * 환경에서 was 부팅 무영향). 스펙 §3: monitoring용 HikariDataSource·JdbcClient는 빈으로
 * 노출하지 않는다 — DataSource·JdbcClient 자동구성이 모두 @ConditionalOnMissingBean이라
 * 빈으로 두면 기존 analysis DB 배선(세션 JDBC·app Flyway·전 리포지토리 주입)이 깨진다.
 */
@Configuration
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class MonitoringConfig {

	private final HikariDataSource monitoringDataSource;
	private final JdbcClient monitoringJdbc;
	private final RestClient monitoringRestClient;

	public MonitoringConfig(
			@Value("${monitoring.api.base-url:http://monitoring:8083}") String baseUrl,
			@Value("${monitoring.datasource.url}") String dbUrl,
			@Value("${monitoring.datasource.username}") String dbUsername,
			@Value("${monitoring.datasource.password}") String dbPassword) {
		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(dbUrl);
		hikari.setUsername(dbUsername);
		hikari.setPassword(dbPassword);
		hikari.setMaximumPoolSize(3);          // 조회 전용·저트래픽 (스펙 §3)
		hikari.setPoolName("monitoring-ro");
		this.monitoringDataSource = new HikariDataSource(hikari);
		this.monitoringJdbc = JdbcClient.create(monitoringDataSource);

		// PATCH(기간 연장) 때문에 JDK HttpClient 팩토리 — 타임아웃은 계약 §1 권고 최대치로 단일화(스펙 §4)
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		this.monitoringRestClient = RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(baseUrl)
				.build();
	}

	/** 내부 접근자 — 빈이 아니다. 도메인 빈 조립과 테스트에서만 쓴다. */
	JdbcClient monitoringJdbc() {
		return monitoringJdbc;
	}

	RestClient monitoringRestClient() {
		return monitoringRestClient;
	}

	@PreDestroy
	void close() {
		monitoringDataSource.close();
	}
}
```

주의: `monitoringJdbc()`·`monitoringRestClient()`는 테스트가 같은 패키지가 아니므로 — `MonitoringEnabledConfigTest`는 `com.celfit.was` — **`monitoringJdbc()`는 public으로** 열어야 한다. 접근자 두 개를 `public`으로 선언할 것 (위 코드에서 수정: `public JdbcClient monitoringJdbc()`, `public RestClient monitoringRestClient()`).

- [ ] **Step 4: application.yml에 기본값 추가** (파일 끝에)

```yaml
monitoring:
  enabled: false   # true면 monitoring.api.base-url + monitoring.datasource.*(읽기 전용 계정) 필요 — 계약: docs/contracts/monitoring-was-contract.md
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.MonitoringDisabledTest" --tests "com.celfit.was.MonitoringEnabledConfigTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java was/src/main/resources/application.yml was/src/test/java/com/celfit/was/MonitoringDisabledTest.java was/src/test/java/com/celfit/was/MonitoringEnabledConfigTest.java
git commit -m "feat(was): MonitoringConfig 조건부 활성화 — 인프라 빈 비노출로 자동구성 back-off 회피"
```

---

### Task 3: 예외 2계열 + 명령 DTO + MonitoringCommandClient

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringException.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringApiException.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringUnavailableException.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/KeywordRule.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/RegisterRequest.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/RegisterResult.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/ApproveResult.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/RejectResult.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/ExtendResult.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/CancelResult.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java` (@Bean 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java`

- [ ] **Step 1: 예외 3개 작성**

`MonitoringException.java`:

```java
package com.celfit.was.monitoring;

/** 모니터링 통신 예외 공통 베이스 — 하위 2계열의 구분 축은 "같은 멱등키 재시도 가능성"(스펙 §4). */
public abstract class MonitoringException extends RuntimeException {

	protected MonitoringException(String message) {
		super(message);
	}

	protected MonitoringException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

`MonitoringApiException.java`:

```java
package com.celfit.was.monitoring;

/**
 * monitoring이 에러 응답 {code, message}를 준 경우 — 어휘는 계약 §2가 정본이고 was는
 * 해석·분기 없이 그대로 담아 올린다(프론트 어휘 변환은 나중 컨트롤러 몫). 재시도 무의미.
 */
public class MonitoringApiException extends MonitoringException {

	private final String code;
	private final int httpStatus;

	public MonitoringApiException(String code, String message, int httpStatus) {
		super("[" + code + "] " + message);
		this.code = code;
		this.httpStatus = httpStatus;
	}

	public String code() {
		return code;
	}

	public int httpStatus() {
		return httpStatus;
	}
}
```

`MonitoringUnavailableException.java`:

```java
package com.celfit.was.monitoring;

/** 전송 실패(연결 불가·타임아웃·해석 불가 응답) — 같은 registrationKey로 재시도 가능(멱등 replay). */
public class MonitoringUnavailableException extends MonitoringException {

	public MonitoringUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

- [ ] **Step 2: DTO record 작성** (계약 §2 JSON 그대로)

`KeywordRule.java`:

```java
package com.celfit.was.monitoring;

import java.util.List;

/** 키워드 규칙 — 매칭 의미(and 전부 ∧ any 하나 이상 ∧ exclude 전무)는 monitoring 소유(계약 §3). */
public record KeywordRule(List<String> and, List<String> any, List<String> exclude) {
}
```

`RegisterRequest.java`:

```java
package com.celfit.was.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 등록 요청(계약 §2-1) — ACCOUNT/POST 공용이라 타입별 미사용 필드는 null이며 직렬화에서 뺀다.
 * (Jackson 3도 애노테이션 패키지는 com.fasterxml.jackson.annotation 유지)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterRequest(String registrationKey, String type, String username,
		String shortCode, KeywordRule keywordRule, OffsetDateTime expiresAt) {

	public static RegisterRequest account(UUID key, String username, KeywordRule keywordRule,
			OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), "ACCOUNT", username, null, keywordRule, expiresAt);
	}

	public static RegisterRequest post(UUID key, String shortCode, OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), "POST", null, shortCode, null, expiresAt);
	}
}
```

`RegisterResult.java`:

```java
package com.celfit.was.monitoring;

import tools.jackson.databind.JsonNode;

/**
 * 등록 응답(계약 §2-1). firstSnapshot은 타입별(profile/post 지표) 형태가 계약 v0.1에서
 * 미확정이라 불투명 JsonNode로 전달만 한다 — 성형은 프론트 API 작업 때.
 */
public record RegisterResult(long targetId, String status, JsonNode firstSnapshot) {
}
```

`ApproveResult.java`:

```java
package com.celfit.was.monitoring;

/** 승인 응답(계약 §2-2) — WATCHING → TRACKING 전환 결과. */
public record ApproveResult(long targetId, String status, String trackedShortCode) {
}
```

`RejectResult.java`:

```java
package com.celfit.was.monitoring;

/** 기각 응답(계약 §2-3) — 후보만 닫히고 캠페인은 WATCHING 지속. */
public record RejectResult(long candidateId, String status) {
}
```

`ExtendResult.java`:

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** 기간 연장 응답(계약 §2-4). */
public record ExtendResult(long targetId, OffsetDateTime expiresAt) {
}
```

`CancelResult.java`:

```java
package com.celfit.was.monitoring;

/** 해지 응답(계약 §2-5) — 멱등: 이미 종결이면 현재 상태 그대로 온다. */
public record CancelResult(long targetId, String status) {
}
```

- [ ] **Step 3: 실패하는 테스트 작성** (ResendMailSenderTest의 MockRestServiceServer 관용구)

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MonitoringCommandClientTest {

	static final String BASE = "http://monitoring:8083";

	MockRestServiceServer server;
	MonitoringCommandClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new MonitoringCommandClient(builder.build());
	}

	@Test
	void 계정_등록_요청과_응답_파싱() {
		UUID key = UUID.randomUUID();
		server.expect(requestTo(BASE + "/api/targets"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.registrationKey").value(key.toString()))
				.andExpect(jsonPath("$.type").value("ACCOUNT"))
				.andExpect(jsonPath("$.keywordRule.and[0]").value("샤넬"))
				.andExpect(jsonPath("$.shortCode").doesNotExist())   // NON_NULL — POST 전용 필드 미직렬화
				.andRespond(withSuccess("""
						{ "targetId": 17, "status": "WATCHING",
						  "firstSnapshot": { "profile": { "followers": 12345 }, "recentPostCount": 12 } }
						""", MediaType.APPLICATION_JSON));

		RegisterResult result = client.register(RegisterRequest.account(key, "some_influencer",
				new KeywordRule(List.of("샤넬"), List.of(), List.of("이벤트")),
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00")));

		assertThat(result.targetId()).isEqualTo(17L);
		assertThat(result.status()).isEqualTo("WATCHING");
		assertThat(result.firstSnapshot().path("profile").path("followers").asLong()).isEqualTo(12345L);
		server.verify();
	}

	@Test
	void 에러_바디의_code가_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SUBJECT_NOT_FOUND\", \"message\": \"계정을 찾을 수 없음: @foo\" }"));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("SUBJECT_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	@Test
	void 바디_없는_5xx는_Unavailable() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 승인_기각_연장_해지_경로() {
		server.expect(requestTo(BASE + "/api/targets/17/candidates/3/approve"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(
						"{ \"targetId\": 17, \"status\": \"TRACKING\", \"trackedShortCode\": \"DAbC\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17/candidates/4/reject"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("{ \"candidateId\": 4, \"status\": \"REJECTED\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17"))
				.andExpect(method(HttpMethod.PATCH))
				.andExpect(jsonPath("$.expiresAt").exists())
				.andRespond(withSuccess(
						"{ \"targetId\": 17, \"expiresAt\": \"2026-09-30T23:59:59+09:00\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": 17, \"status\": \"CANCELED\" }",
						MediaType.APPLICATION_JSON));

		assertThat(client.approve(17, 3).trackedShortCode()).isEqualTo("DAbC");
		assertThat(client.reject(17, 4).status()).isEqualTo("REJECTED");
		assertThat(client.extend(17, OffsetDateTime.parse("2026-09-30T23:59:59+09:00")).targetId()).isEqualTo(17L);
		assertThat(client.cancel(17).status()).isEqualTo("CANCELED");
		server.verify();
	}
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCommandClientTest"`
Expected: 컴파일 실패 (`MonitoringCommandClient` 없음)

- [ ] **Step 5: 클라이언트 구현**

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * monitoring 내부 명령 API 5개(계약 §2). 인증 없음 — 도커 내부망 전용(07-28 토큰 제거 결정).
 * 에러는 2계열로 승격: 에러 바디 {code, message} → MonitoringApiException(code 그대로),
 * 전송 실패·해석 불가 → MonitoringUnavailableException(같은 멱등키 재시도 가능 신호).
 */
public class MonitoringCommandClient {

	private final RestClient restClient;

	public MonitoringCommandClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public RegisterResult register(RegisterRequest request) {
		return exchange(() -> restClient.post().uri("/api/targets")
				.body(request).retrieve().body(RegisterResult.class));
	}

	public ApproveResult approve(long targetId, long candidateId) {
		return exchange(() -> restClient.post()
				.uri("/api/targets/{id}/candidates/{cid}/approve", targetId, candidateId)
				.retrieve().body(ApproveResult.class));
	}

	public RejectResult reject(long targetId, long candidateId) {
		return exchange(() -> restClient.post()
				.uri("/api/targets/{id}/candidates/{cid}/reject", targetId, candidateId)
				.retrieve().body(RejectResult.class));
	}

	public ExtendResult extend(long targetId, OffsetDateTime expiresAt) {
		return exchange(() -> restClient.patch().uri("/api/targets/{id}", targetId)
				.body(Map.of("expiresAt", expiresAt)).retrieve().body(ExtendResult.class));
	}

	public CancelResult cancel(long targetId) {
		return exchange(() -> restClient.delete().uri("/api/targets/{id}", targetId)
				.retrieve().body(CancelResult.class));
	}

	private <T> T exchange(Supplier<T> call) {
		try {
			return call.get();
		} catch (RestClientResponseException e) {
			ErrorBody body = parseErrorBody(e);
			if (body == null || body.code() == null) {
				throw new MonitoringUnavailableException(
						"monitoring 응답 해석 불가 HTTP " + e.getStatusCode().value(), e);
			}
			throw new MonitoringApiException(body.code(), body.message(), e.getStatusCode().value());
		} catch (ResourceAccessException e) {
			throw new MonitoringUnavailableException("monitoring 접속 실패: " + e.getMessage(), e);
		}
	}

	private ErrorBody parseErrorBody(RestClientResponseException e) {
		try {
			return e.getResponseBodyAs(ErrorBody.class);
		} catch (RuntimeException parseFailure) {
			return null;   // JSON 아님·빈 바디 — 전송 계열로 처리
		}
	}

	record ErrorBody(String code, String message) {
	}
}
```

- [ ] **Step 6: MonitoringConfig에 빈 추가**

`MonitoringConfig.java`에 메서드 추가:

```java
	@Bean
	MonitoringCommandClient monitoringCommandClient() {
		return new MonitoringCommandClient(monitoringRestClient);
	}
```

(import `org.springframework.context.annotation.Bean` 추가)

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCommandClientTest"`
Expected: PASS (4 tests)

- [ ] **Step 8: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/ was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java
git commit -m "feat(was): 모니터링 명령 클라이언트 — 5개 명령 + 에러 2계열(code 승격/재시도 가능) 승격"
```

---

### Task 4: 조회 계층 — DDL 픽스처 + MonitoringReadRepository

**Files:**
- Create: `was/src/test/resources/monitoring-schema.sql`
- Create: `was/src/main/java/com/celfit/was/monitoring/TargetRow.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/CandidateRow.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/PendingCandidate.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/ProfileSnapshotRow.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/PostSnapshotRow.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringReadRepository.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java` (@Bean 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringReadRepositoryTest.java`

- [ ] **Step 1: DDL 픽스처 작성** (`was/src/test/resources/monitoring-schema.sql`)

```sql
-- 계약 §3(v0.1)에서 유도한 monitoring DB 픽스처 — 테스트 전용.
-- ⚠️ monitoring 구현 확정 시 실제 스키마와 대조 필요(스펙 §7 잔여 작업).
CREATE TABLE IF NOT EXISTS target (
    id                 bigserial PRIMARY KEY,
    type               text NOT NULL,             -- ACCOUNT / POST
    username           text NOT NULL,
    short_code         text,
    keyword_rule       jsonb,
    status             text NOT NULL,             -- WATCHING/TRACKING/EXPIRED/CANCELED/FAILED
    tracked_short_code text,
    tracked_since      timestamptz,
    registration_key   text NOT NULL UNIQUE,
    expires_at         timestamptz NOT NULL,
    registered_at      timestamptz NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    last_fetched_at    timestamptz,
    fail_reason        text
);

CREATE TABLE IF NOT EXISTS detected_candidate (
    id              bigserial PRIMARY KEY,
    target_id       bigint NOT NULL,
    short_code      text NOT NULL,
    detected_at     timestamptz NOT NULL,
    caption_excerpt text,
    status          text NOT NULL,                -- PENDING/APPROVED/REJECTED
    UNIQUE (target_id, short_code)
);

CREATE TABLE IF NOT EXISTS profile_snapshot (
    username    text NOT NULL,
    captured_on date NOT NULL,
    followers   bigint,
    following   bigint,
    media_count bigint,
    PRIMARY KEY (username, captured_on)
);

CREATE TABLE IF NOT EXISTS post_snapshot (
    username     text NOT NULL,
    short_code   text NOT NULL,
    captured_on  date NOT NULL,
    content_type text,                            -- REELS / FEED
    likes        bigint,
    comments     bigint,
    views        bigint,                          -- 피드는 항상 NULL (계약 §3 null 규칙)
    saves        bigint,
    shares       bigint,
    reposts      bigint,
    PRIMARY KEY (short_code, captured_on)
);
```

- [ ] **Step 2: 조회 record 작성** (계약 §3 컬럼 그대로 — DataClassRowMapper가 snake_case→camelCase 매핑)

`TargetRow.java`:

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** monitoring DB target 1행(계약 §3) — status·fail_reason 어휘는 monitoring이 확정, 해석 없이 전달. */
public record TargetRow(long id, String type, String username, String shortCode,
		String keywordRule, String status, String trackedShortCode, OffsetDateTime trackedSince,
		String registrationKey, OffsetDateTime expiresAt, OffsetDateTime registeredAt,
		OffsetDateTime closedAt, OffsetDateTime lastFetchedAt, String failReason) {
}
```

`CandidateRow.java`:

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** detected_candidate 1행(계약 §3). */
public record CandidateRow(long id, long targetId, String shortCode,
		OffsetDateTime detectedAt, String captionExcerpt, String status) {
}
```

`PendingCandidate.java`:

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** 워터마크 이후 신규 PENDING 후보 + 소속 캠페인 계정(계약 §3 알람 쿼리) — 이메일 크론 대비. */
public record PendingCandidate(long id, long targetId, String shortCode,
		String captionExcerpt, OffsetDateTime detectedAt, String username) {
}
```

`ProfileSnapshotRow.java`:

```java
package com.celfit.was.monitoring;

import java.time.LocalDate;

/** profile_snapshot 1행 — captured_on 일 1회 upsert(KST), 등록 당일은 1행뿐(계약 §5). */
public record ProfileSnapshotRow(LocalDate capturedOn, Long followers, Long following,
		Long mediaCount) {
}
```

`PostSnapshotRow.java`:

```java
package com.celfit.was.monitoring;

import java.time.LocalDate;

/** post_snapshot 1행 — 취득 불가 지표는 null(피드 조회수 등, 계약 §3 null 규칙). */
public record PostSnapshotRow(LocalDate capturedOn, String contentType, Long likes,
		Long comments, Long views, Long saves, Long shares, Long reposts) {
}
```

- [ ] **Step 3: 실패하는 테스트 작성**

공유 컨테이너의 public 스키마에 픽스처를 적용하고, 리포지토리는 기본 DataSource 위의 JdbcClient로 직접 생성한다(조회 SQL 검증이 목적 — MonitoringConfig 활성화와 무관).

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MonitoringReadRepositoryTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	JdbcClient jdbc;
	MonitoringReadRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();
		repository = new MonitoringReadRepository(jdbc);
	}

	long seedTarget(String username, String trackedShortCode, String status) {
		return jdbc.sql("""
				INSERT INTO target (type, username, keyword_rule, status, tracked_short_code,
				                    registration_key, expires_at)
				VALUES ('ACCOUNT', :username, '{"and":["샤넬"],"any":[],"exclude":[]}'::jsonb,
				        :status, :tracked, gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("status", status).param("tracked", trackedShortCode)
				.query(Long.class).single();
	}

	@Test
	void 타겟_조회는_계약_컬럼을_그대로_돌려준다() {
		long id = seedTarget("some_influencer", null, "WATCHING");

		List<TargetRow> rows = repository.findTargets(List.of(id));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).username()).isEqualTo("some_influencer");
		assertThat(rows.get(0).status()).isEqualTo("WATCHING");
		assertThat(rows.get(0).keywordRule()).contains("샤넬");
		assertThat(rows.get(0).closedAt()).isNull();
	}

	@Test
	void 빈_id_목록은_빈_결과() {
		assertThat(repository.findTargets(List.of())).isEmpty();
	}

	@Test
	void 후보_목록과_워터마크_이후_신규_PENDING() {
		long id = seedTarget("acc1", null, "WATCHING");
		jdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:t, 'OLD1', now() - interval '2 days', '…샤넬…', 'PENDING'),
				       (:t, 'NEW1', now(), '…샤넬 립스틱…', 'PENDING'),
				       (:t, 'REJ1', now(), '…', 'REJECTED')
				""").param("t", id).update();

		assertThat(repository.findCandidates(id)).hasSize(3);

		List<PendingCandidate> fresh = repository.findPendingCandidatesSince(
				Instant.now().minusSeconds(3600));
		assertThat(fresh).hasSize(1);
		assertThat(fresh.get(0).shortCode()).isEqualTo("NEW1");
		assertThat(fresh.get(0).username()).isEqualTo("acc1");
	}

	@Test
	void 프로필_추이는_날짜순() {
		jdbc.sql("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES ('acc1', '2026-07-27', 100, 10, 5), ('acc1', '2026-07-28', 110, 10, 6)
				""").update();

		List<ProfileSnapshotRow> rows = repository.profileTimeseries("acc1");

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).followers()).isEqualTo(100L);
		assertThat(rows.get(1).followers()).isEqualTo(110L);
	}

	@Test
	void 게시물_추이는_추적_short_code_기준이고_null_지표가_보존된다() {
		long id = seedTarget("acc1", "TRACK1", "TRACKING");
		jdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acc1', 'TRACK1', '2026-07-28', 'FEED', 50, 3, NULL, 7, 1, 0),
				       ('acc1', 'OTHER', '2026-07-28', 'REELS', 999, 9, 1000, 9, 9, 9)
				""").update();

		List<PostSnapshotRow> rows = repository.postTimeseries(id);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).likes()).isEqualTo(50L);
		assertThat(rows.get(0).views()).isNull();   // 피드 조회수 NULL 규칙
	}
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringReadRepositoryTest"`
Expected: 컴파일 실패 (`MonitoringReadRepository` 없음)

- [ ] **Step 5: 리포지토리 구현**

```java
package com.celfit.was.monitoring;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * monitoring DB 조회(계약 §3) — 베이스 테이블 4개만, 초안 뷰는 monitoring 확정 후 반영(스펙 §5).
 * 주입되는 JdbcClient는 MonitoringConfig가 내부 생성한 읽기 전용 커넥션 — 쓰기 시도는
 * DB 권한 오류로 fail-closed. app 스키마·분석 결과와의 크로스 DB 조인 금지(조합은 was 코드).
 */
public class MonitoringReadRepository {

	private final JdbcClient jdbc;

	public MonitoringReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<TargetRow> findTargets(Collection<Long> targetIds) {
		if (targetIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT id, type, username, short_code, keyword_rule::text AS keyword_rule, status,
				       tracked_short_code, tracked_since, registration_key, expires_at,
				       registered_at, closed_at, last_fetched_at, fail_reason
				FROM target
				WHERE id IN (:ids)
				ORDER BY registered_at DESC
				""")
				.param("ids", targetIds)
				.query(TargetRow.class)
				.list();
	}

	public List<CandidateRow> findCandidates(long targetId) {
		return jdbc.sql("""
				SELECT id, target_id, short_code, detected_at, caption_excerpt, status
				FROM detected_candidate
				WHERE target_id = :targetId
				ORDER BY detected_at DESC
				""")
				.param("targetId", targetId)
				.query(CandidateRow.class)
				.list();
	}

	/** 워터마크 이후 신규 PENDING(계약 §3 알람 쿼리 그대로) — 이메일 크론 대비. */
	public List<PendingCandidate> findPendingCandidatesSince(Instant since) {
		return jdbc.sql("""
				SELECT c.id, c.target_id, c.short_code, c.caption_excerpt, c.detected_at, t.username
				FROM detected_candidate c JOIN target t ON t.id = c.target_id
				WHERE c.status = 'PENDING' AND c.detected_at > :since
				ORDER BY c.detected_at
				""")
				.param("since", java.sql.Timestamp.from(since))
				.query(PendingCandidate.class)
				.list();
	}

	public List<ProfileSnapshotRow> profileTimeseries(String username) {
		return jdbc.sql("""
				SELECT captured_on, followers, following, media_count
				FROM profile_snapshot
				WHERE username = :username
				ORDER BY captured_on
				""")
				.param("username", username)
				.query(ProfileSnapshotRow.class)
				.list();
	}

	/** 추적 게시물 추이(계약 §3 예시의 tracked_short_code 서브쿼리 그대로). */
	public List<PostSnapshotRow> postTimeseries(long targetId) {
		return jdbc.sql("""
				SELECT captured_on, content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = (SELECT tracked_short_code FROM target WHERE id = :targetId)
				ORDER BY captured_on
				""")
				.param("targetId", targetId)
				.query(PostSnapshotRow.class)
				.list();
	}
}
```

- [ ] **Step 6: MonitoringConfig에 빈 추가**

```java
	@Bean
	MonitoringReadRepository monitoringReadRepository() {
		return new MonitoringReadRepository(monitoringJdbc);
	}
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringReadRepositoryTest"`
Expected: PASS (5 tests)

- [ ] **Step 8: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/ was/src/test/resources/monitoring-schema.sql was/src/test/java/com/celfit/was/monitoring/MonitoringReadRepositoryTest.java
git commit -m "feat(was): 모니터링 조회 계층 — 계약 베이스 테이블 4개 SELECT + DDL 픽스처"
```

---

### Task 5: MonitoringCampaignService — 2단계 등록·재시도·소유 검증·삭제 순서

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/CampaignNotFoundException.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignService.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java` (@Bean 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringCampaignServiceTest.java`

- [ ] **Step 1: 예외 작성**

```java
package com.celfit.was.monitoring;

/** (user, target) 매핑 없음 — 남의 캠페인이거나 존재하지 않는 target. was 소유 검증 실패. */
public class CampaignNotFoundException extends MonitoringException {

	public CampaignNotFoundException(long targetId) {
		super("캠페인 매핑 없음: targetId=" + targetId);
	}
}
```

- [ ] **Step 2: 실패하는 테스트 작성** (실제 매핑 리포지토리 + Mockito mock 클라이언트)

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringCampaignServiceTest extends IntegrationTest {

	static final OffsetDateTime EXPIRES = OffsetDateTime.parse("2026-08-28T23:59:59+09:00");
	static final KeywordRule RULE = new KeywordRule(List.of("샤넬"), List.of(), List.of());

	@Autowired
	MonitoringCampaignMappingRepository mappings;
	@Autowired
	JdbcClient jdbcClient;

	MonitoringCommandClient client;
	MonitoringCampaignService service;
	long userId;

	@BeforeEach
	void setUp() {
		client = mock(MonitoringCommandClient.class);
		service = new MonitoringCampaignService(client, mappings);
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 등록_성공은_선저장_후_target을_확정한다() {
		given(client.register(any())).willReturn(new RegisterResult(17L, "WATCHING", null));

		RegisterResult result = service.registerAccount(userId, "some_influencer", RULE, EXPIRES);

		assertThat(result.targetId()).isEqualTo(17L);
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isPresent();
	}

	@Test
	void 전송_실패는_같은_키로_1회_재시도한다() {
		given(client.register(any()))
				.willThrow(new MonitoringUnavailableException("접속 실패", null))
				.willReturn(new RegisterResult(18L, "WATCHING", null));

		service.registerAccount(userId, "some_influencer", RULE, EXPIRES);

		// 두 호출 모두 같은 registrationKey — 멱등 replay 전제
		org.mockito.ArgumentCaptor<RegisterRequest> captor =
				org.mockito.ArgumentCaptor.forClass(RegisterRequest.class);
		verify(client, times(2)).register(captor.capture());
		assertThat(captor.getAllValues().get(0).registrationKey())
				.isEqualTo(captor.getAllValues().get(1).registrationKey());
		assertThat(mappings.findByUserAndTarget(userId, 18L)).isPresent();
	}

	@Test
	void 재시도까지_실패하면_pending_행이_남는다() {
		given(client.register(any()))
				.willThrow(new MonitoringUnavailableException("접속 실패", null));

		assertThatThrownBy(() -> service.registerAccount(userId, "some_influencer", RULE, EXPIRES))
				.isInstanceOf(MonitoringUnavailableException.class);

		// pending(target NULL) 행 유지 — 프론트 API 작업 때 키 재사용으로 마저 닫는다(스펙 §6)
		assertThat(mappings.findByUser(userId)).hasSize(1);
		assertThat(mappings.findByUser(userId).get(0).targetId()).isNull();
	}

	@Test
	void API_에러는_pending_행을_지우고_그대로_전파한다() {
		given(client.register(any()))
				.willThrow(new MonitoringApiException("SUBJECT_NOT_FOUND", "계정 없음", 404));

		assertThatThrownBy(() -> service.registerAccount(userId, "ghost", RULE, EXPIRES))
				.isInstanceOfSatisfying(MonitoringApiException.class,
						e -> assertThat(e.code()).isEqualTo("SUBJECT_NOT_FOUND"));

		// 확정 실패 = monitoring에 target 미생성 — pending 잔재 없음
		assertThat(mappings.findByUser(userId)).isEmpty();
	}

	@Test
	void 소유하지_않은_target_명령은_클라이언트_호출_전에_거부된다() {
		assertThatThrownBy(() -> service.approve(userId, 999L, 1L))
				.isInstanceOf(CampaignNotFoundException.class);
		verify(client, never()).approve(anyLong(), anyLong());
	}

	@Test
	void 소유한_target은_승인_기각_연장이_위임된다() {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, 17L);
		given(client.approve(17L, 3L)).willReturn(new ApproveResult(17L, "TRACKING", "DAbC"));
		given(client.reject(17L, 4L)).willReturn(new RejectResult(4L, "REJECTED"));
		given(client.extend(17L, EXPIRES)).willReturn(new ExtendResult(17L, EXPIRES));

		assertThat(service.approve(userId, 17L, 3L).status()).isEqualTo("TRACKING");
		assertThat(service.reject(userId, 17L, 4L).status()).isEqualTo("REJECTED");
		assertThat(service.extend(userId, 17L, EXPIRES).targetId()).isEqualTo(17L);
	}

	@Test
	void 삭제는_해지_성공_후에만_매핑을_지운다() {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, 17L);
		given(client.cancel(17L))
				.willThrow(new MonitoringUnavailableException("접속 실패", null));

		assertThatThrownBy(() -> service.cancelAndDelete(userId, 17L))
				.isInstanceOf(MonitoringUnavailableException.class);
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isPresent();   // 매핑 유지

		given(client.cancel(17L)).willReturn(new CancelResult(17L, "CANCELED"));

		assertThat(service.cancelAndDelete(userId, 17L).status()).isEqualTo("CANCELED");
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isEmpty();     // 성공 후 삭제
	}
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCampaignServiceTest"`
Expected: 컴파일 실패 (`MonitoringCampaignService` 없음)

- [ ] **Step 4: 서비스 구현**

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * user_id 기준 모니터링 오케스트레이션(스펙 §6).
 * 등록은 멱등키 선저장 2단계 — was가 호출 직후 죽어도 키가 남아 같은 키 replay가 가능하다.
 * 명령은 (user, target) 매핑 소유 검증 후 위임. 삭제는 해지 성공 후에만 매핑을 지운다.
 */
public class MonitoringCampaignService {

	private final MonitoringCommandClient client;
	private final MonitoringCampaignMappingRepository mappings;

	public MonitoringCampaignService(MonitoringCommandClient client,
			MonitoringCampaignMappingRepository mappings) {
		this.client = client;
		this.mappings = mappings;
	}

	public RegisterResult registerAccount(long userId, String username, KeywordRule keywordRule,
			OffsetDateTime expiresAt) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		return completeRegistration(key, RegisterRequest.account(key, username, keywordRule, expiresAt));
	}

	public RegisterResult registerPost(long userId, String shortCode, OffsetDateTime expiresAt) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		return completeRegistration(key, RegisterRequest.post(key, shortCode, expiresAt));
	}

	private RegisterResult completeRegistration(UUID key, RegisterRequest request) {
		RegisterResult result;
		try {
			result = registerWithOneRetry(request);
		} catch (MonitoringApiException e) {
			// 확정 실패(계정 없음·비공개 등) — monitoring에 target 미생성이므로 pending 정리
			mappings.deleteByKey(key);
			throw e;
		}
		// 전송 계열(Unavailable)로 여기 못 오면 pending 행이 남는다 — 의도된 보류(스펙 §6 알려진 한계)
		mappings.confirmTarget(key, result.targetId());
		return result;
	}

	private RegisterResult registerWithOneRetry(RegisterRequest request) {
		try {
			return client.register(request);
		} catch (MonitoringUnavailableException first) {
			// 같은 registrationKey 멱등 replay — 첫 호출로 target이 만들어졌어도 같은 행이 200으로 온다
			return client.register(request);
		}
	}

	public ApproveResult approve(long userId, long targetId, long candidateId) {
		requireOwned(userId, targetId);
		return client.approve(targetId, candidateId);
	}

	public RejectResult reject(long userId, long targetId, long candidateId) {
		requireOwned(userId, targetId);
		return client.reject(targetId, candidateId);
	}

	public ExtendResult extend(long userId, long targetId, OffsetDateTime expiresAt) {
		requireOwned(userId, targetId);
		return client.extend(targetId, expiresAt);
	}

	/**
	 * 유저의 "캠페인 삭제"(계약 §5) — monitoring엔 해지(상태 전이)만 보내고, 성공했을 때만
	 * was 매핑을 지운다. 순서 고정으로 "monitoring엔 살아있는데 매핑만 없는" 상태를 막는다.
	 */
	public CancelResult cancelAndDelete(long userId, long targetId) {
		requireOwned(userId, targetId);
		CancelResult result = client.cancel(targetId);
		mappings.deleteByUserAndTarget(userId, targetId);
		return result;
	}

	private void requireOwned(long userId, long targetId) {
		mappings.findByUserAndTarget(userId, targetId)
				.orElseThrow(() -> new CampaignNotFoundException(targetId));
	}
}
```

- [ ] **Step 5: MonitoringConfig에 빈 추가**

```java
	@Bean
	MonitoringCampaignService monitoringCampaignService(MonitoringCommandClient client,
			MonitoringCampaignMappingRepository mappings) {
		return new MonitoringCampaignService(client, mappings);
	}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCampaignServiceTest"`
Expected: PASS (7 tests)

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/ was/src/test/java/com/celfit/was/monitoring/MonitoringCampaignServiceTest.java
git commit -m "feat(was): MonitoringCampaignService — 멱등키 2단계 등록·1회 재시도·소유 검증·삭제 순서"
```

---

### Task 6: 전체 검증 + 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표에 행 추가, §7 결정 기록에 한 줄 추가)
- Modify: `docs/superpowers/plans/2026-07-28-monitoring-was-seam.md` (상태 헤더 → ✅)

- [ ] **Step 1: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL — 기존 테스트 전부 통과(비활성 기본값 무영향 회귀 검증 겸용)

- [ ] **Step 2: ARCHITECTURE.md 갱신**

§5 작업 트랙 표에 행 추가 (기존 행 형식 그대로):

```
| 모니터링 was seam | was ↔ monitoring 통신 계층 (명령 클라이언트·읽기 전용 조회·매핑 V13) — 프론트 /v1·이메일 크론은 후속 | 🚧 | specs/2026-07-28-monitoring-was-seam-design.md |
```

(표 컬럼 구성은 실제 §5를 열어 확인 후 맞출 것 — 위는 의미 기준.)

§7 결정 기록에 추가:

```
- 2026-07-28 모니터링 seam: monitoring용 DataSource·JdbcClient는 빈 비노출(자동구성 back-off 회피),
  등록은 멱등키 선저장 2단계, 조회는 계약 베이스 테이블만. 내부망 전용이라 명령 API 토큰 인증 제거.
```

- [ ] **Step 3: 플랜 상태 헤더를 `✅ 구현됨`으로 갱신 후 커밋**

```bash
git add ARCHITECTURE.md docs/superpowers/plans/2026-07-28-monitoring-was-seam.md
git commit -m "docs: ARCHITECTURE §5·§7 모니터링 seam 반영 + 플랜 상태 갱신"
```

- [ ] **Step 4: PR 생성은 superpowers:finishing-a-development-branch 스킬로** (develop 대상, 직접 push 금지)

---

## Self-Review 결과 (작성 시 반영 완료)

- 스펙 커버리지: §2 구성요소 7종 → Task 1~5(실구현에서 MonitoringProperties는 MonitoringConfig의 @Value 4개로 대체 — 기능 동등, 코드베이스 @Value 관례 준수), §3 back-off 회피 → Task 2 테스트가 직접 검증, §4 에러 2계열 → Task 3, §5 베이스 테이블·null 규칙 → Task 4, §6 2단계·재시도·소유·삭제 순서 → Task 5, §7 테스트 전략 전 항목 → 각 Task + Task 6 전체 실행. 컨트롤러·크론은 스펙에서 명시 제외.
- 타입 일관성: `MonitoringCommandClient` 시그니처(Task 3)와 서비스 사용처(Task 5), `MonitoringConfig` 접근자(Task 2)와 빈 추가(Task 3~5) 대조 완료.
- 유의: Jackson 3에서 `JsonNode`는 `tools.jackson.databind.JsonNode`, 애노테이션은 `com.fasterxml.jackson.annotation` — 혼동 금지. `MonitoringEnabledConfigTest`는 `POSTGRES` 접근 때문에 반드시 `com.celfit.was` 패키지.
