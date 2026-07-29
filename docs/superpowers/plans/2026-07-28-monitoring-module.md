# monitoring 모듈 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 실행 전
> 스펙: [specs/2026-07-28-monitoring-module-design.md](../specs/2026-07-28-monitoring-module-design.md) ·
> was 계약: [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md)

**Goal:** 시딩 캠페인 모니터링을 담당하는 4번째 Gradle 모듈 `monitoring` 신설 — 등록(동기 첫 수집)·키워드 감지·승인 게이트·일일 스윕·조회 표면까지.

**Architecture:** 명령은 내부 HTTP API(전용 도커 네트워크 `monitoring-net`이 접근 통제 — 토큰 없음), 조회는 was가 monitoring DB `public` 스키마를 읽기 전용 SELECT. 수집은 HikerAPI만. 사설 monitoring DB(기존 `postgres` 인스턴스에 신설, raw/public 2스키마). target=캠페인 단위, 스냅샷=관측 대상(계정·게시물) 단위. dev 스택에도 dev-monitoring 편입.

**Tech Stack:** Java 21, Spring Boot 4.1(starter-web·jdbc), Flyway(수동 빈), Postgres 17, Jackson 3(`tools.jackson.*`), Testcontainers 2.x.

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(monitoring):` / `docs:` / `chore:`.
- 모듈 간 Java 공유 금지 — crawler·analytics·was 코드를 import 하지 않는다(Hiker 클라이언트는 자체 보유).
- monitoring은 crawler DB·analysis DB에 접근하지 않는다. 자기 DB(`monitoring`)만.
- DTO는 record. 배열·구조 저장은 `jsonb`(`?::jsonb` 캐스팅).
- Spring Boot 4 주의: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure`, Testcontainers는 `org.testcontainers.postgresql.PostgreSQLContainer`. Flyway 자동설정 모듈 없음 — 수동 빈이 유일 Flyway.
- 포트 8083. 서버 크론은 UTC 표기(KST 02:00 = `0 0 17 * * *`).
- 상태 어휘: target `WATCHING/TRACKING/EXPIRED/CANCELED/FAILED`, 후보 `PENDING/APPROVED/REJECTED`, 에러 코드 어휘는 계약 문서 §2 표.
- 명령 API 인증은 **토큰 없음** — 전용 도커 네트워크 `monitoring-net` 소속(was뿐)이 곧 인증(스펙 결정 11). 호스트 포트 매핑 금지.
- 이 계획의 범위 밖: was 쪽 구현(`/v1/monitoring`·이메일 크론·app 매핑).

---

### Task 1: Hiker 엔드포인트 실측 + 픽스처 확보

> ✅ 완료(2026-07-29). 결과: `docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md`.
> **실측으로 엔드포인트 3종이 전부 v2 계열로 바뀌었다** — v1 계열은 6지표 중 좋아요·댓글만 준다.
> 열거는 `/v2/user/medias`(+릴스 조회수 보강용 `/v2/user/clips`), 단건은 `/v2/media/by/code`.
> 아래 Step 1~2의 v1 curl 예시는 실행 당시의 초안이며, 정본은 findings 문서다.

**Files:**
- Create: `monitoring/src/test/resources/hiker/profile.json`
- Create: `monitoring/src/test/resources/hiker/medias.json`
- Create: `monitoring/src/test/resources/hiker/clips.json` (실측 중 추가 — 릴스 재생수)
- Create: `monitoring/src/test/resources/hiker/media-by-code.json`
- Create: `monitoring/src/test/resources/hiker/media-by-code-feed.json` (실측 중 추가 — 널 규칙)
- Create: `docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md`

**Interfaces:**
- Produces: 픽스처 5종(이후 Task 4 파서 테스트의 입력)과 6지표 필드 매핑 확정 메모.

- [x] **Step 1: 실측 호출 3종** — `HIKER_API_KEY`는 셸 export 필요(.env 자동 로드 안 됨). 공개 계정 하나(예: 최근 릴스·피드가 섞인 뷰티 계정)로:

```bash
mkdir -p monitoring/src/test/resources/hiker
# ① 프로필
curl -s -H "x-access-key: $HIKER_API_KEY" \
  "https://api.hikerapi.com/v2/user/by/username?username=<공개계정>" \
  | tee monitoring/src/test/resources/hiker/profile.json | head -c 400
# ② 게시물 열거 (릴스+피드, 1페이지 12건). user_id는 ①의 pk 값
curl -s -H "x-access-key: $HIKER_API_KEY" \
  "https://api.hikerapi.com/v2/user/medias?user_id=<pk>" \
  | tee monitoring/src/test/resources/hiker/medias.json | head -c 400
# ②' 릴스 재생수 보강 — ②는 play_count를 안 준다
curl -s -H "x-access-key: $HIKER_API_KEY" \
  "https://api.hikerapi.com/v2/user/clips?user_id=<pk>" \
  | tee monitoring/src/test/resources/hiker/clips.json | head -c 400
# ③ 게시물 단건 (릴스 하나 + 피드 하나)
curl -s -H "x-access-key: $HIKER_API_KEY" \
  "https://api.hikerapi.com/v2/media/by/code?code=<릴스shortCode>" \
  | tee monitoring/src/test/resources/hiker/media-by-code.json | head -c 400
curl -s -H "x-access-key: $HIKER_API_KEY" \
  "https://api.hikerapi.com/v2/media/by/code?code=<피드shortCode>" \
  | tee monitoring/src/test/resources/hiker/media-by-code-feed.json | head -c 400
```

- [x] **Step 2: 6지표 필드 존재 확인** — 각 픽스처에서 다음 후보 필드를 grep으로 확인하고 결과를 기록:

```bash
for f in monitoring/src/test/resources/hiker/*.json; do echo "== $f"; \
  grep -o '"like_count"\|"comment_count"\|"play_count"\|"ig_play_count"\|"view_count"\|"save_count"\|"reshare_count"\|"media_repost_count"' $f | sort | uniq -c; done
```

- [x] **Step 3: findings 문서 작성** — `docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md`에 기록: 사용 엔드포인트 3종 확정, 지표별 소스 필드(없으면 "미제공 → null"), 열거 응답만으로 저장·공유·리포스트가 나오는지(안 나오면 "TRACKING 게시물만 단건 콜로 보강" 결정 명시), 열거 1페이지 게시물 수. **Task 4의 파서 필드 후보 목록과 다르면 Task 4 코드의 `firstLong(...)` 후보 나열을 이 findings 기준으로 수정한다.**

- [x] **Step 4: Commit**

```bash
git add monitoring/src/test/resources/hiker docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md
git commit -m "chore(monitoring): Hiker 실측 픽스처·필드 매핑 findings"
```

---

### Task 2: 모듈 골격 + DB 마이그레이션 V1 + 테스트 인프라

**Files:**
- Modify: `settings.gradle`
- Create: `monitoring/build.gradle`
- Create: `monitoring/src/main/java/com/celfit/monitoring/MonitoringApplication.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/FlywayConfig.java`
- Create: `monitoring/src/main/resources/application.yml`
- Create: `monitoring/src/main/resources/db/migration/V1__core_tables.sql`
- Create: `db/init/02-create-monitoring-db.sql`
- Create: `monitoring/src/test/java/com/celfit/monitoring/testsupport/TestDb.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java`

**Interfaces:**
- Produces: `TestDb.container()`·`TestDb.resetAndMigrate(JdbcTemplate, DataSource)`·`TestDb.dataSource(container)` — 이후 모든 Testcontainers 테스트가 사용. 테이블 `raw.fetch_payload`·`target`·`detected_candidate`·`profile_snapshot`·`post_snapshot`.

- [ ] **Step 1: Gradle 배선** — `settings.gradle`의 `include 'was'` 아래에 `include 'monitoring'` 추가. `monitoring/build.gradle`:

```groovy
plugins {
	id 'org.springframework.boot'
	id 'io.spring.dependency-management'
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-jdbc'
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.flywaydb:flyway-core'
	runtimeOnly 'org.postgresql:postgresql'
	runtimeOnly 'org.flywaydb:flyway-database-postgresql'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testImplementation 'org.testcontainers:testcontainers-postgresql'
	testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 2: 앱 골격** — `MonitoringApplication.java`:

```java
package com.celfit.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonitoringApplication {
	public static void main(String[] args) {
		SpringApplication.run(MonitoringApplication.class, args);
	}
}
```

`application.yml`:

```yaml
spring:
  application:
    name: monitoring
  datasource:
    url: jdbc:postgresql://localhost:5433/monitoring
    username: monitoring
    password: monitoring

server:
  port: 8083   # crawler 8080 · was 8081 · analytics 8082 다음

monitoring:
  hiker:
    api-key: ${HIKER_API_KEY:}
    base-url: https://api.hikerapi.com
    request-timeout: 15s
  schedule:
    sweep-cron: "-"     # "-"=비활성. 운영은 UTC 17:00(KST 02:00)을 env로 주입
  enumerate-pages: 1    # 게시물 열거 페이지 수(최근 N개 범위) — Task 1 findings로 조정
```

`config/FlywayConfig.java` (Boot 4는 Flyway 자동설정 모듈이 없어 수동 빈이 유일 Flyway — analytics 전례):

```java
package com.celfit.monitoring.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** monitoring DB 전용 Flyway. crawler·analysis DB에는 절대 걸지 않는다. */
@Configuration
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway monitoringFlyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.load();
	}
}
```

- [ ] **Step 3: V1 마이그레이션** — `db/migration/V1__core_tables.sql`:

```sql
-- 원형 스키마: was 무권한(GRANT 자체를 안 줌). 롤링 삭제 대상.
CREATE SCHEMA IF NOT EXISTS raw;

CREATE TABLE raw.fetch_payload (
    id          bigserial PRIMARY KEY,
    kind        text        NOT NULL,  -- PROFILE / POSTS / POST
    subject     text        NOT NULL,  -- username 또는 short_code
    fetched_at  timestamptz NOT NULL DEFAULT now(),
    http_status int         NOT NULL,
    payload     jsonb       NOT NULL
);
CREATE INDEX idx_fetch_payload_subject ON raw.fetch_payload (subject, fetched_at);

-- 캠페인 단위 (등록마다 1행 — 같은 계정도 캠페인별 별도. 스펙 결정 10)
CREATE TABLE target (
    id                 bigserial   PRIMARY KEY,
    type               text        NOT NULL,          -- ACCOUNT / POST
    username           text        NOT NULL,
    short_code         text,                          -- POST 등록 시의 게시물
    keyword_rule       jsonb,                         -- {"and":[],"any":[],"exclude":[]} (ACCOUNT 전용)
    status             text        NOT NULL,          -- WATCHING/TRACKING/EXPIRED/CANCELED/FAILED
    tracked_short_code text,
    tracked_since      timestamptz,
    registration_key   text        NOT NULL UNIQUE,   -- was 생성 멱등 키
    expires_at         timestamptz NOT NULL,
    registered_at      timestamptz NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    last_fetched_at    timestamptz,
    fail_reason        text
);
CREATE INDEX idx_target_active ON target (username) WHERE status IN ('WATCHING', 'TRACKING');

CREATE TABLE detected_candidate (
    id              bigserial   PRIMARY KEY,
    target_id       bigint      NOT NULL REFERENCES target (id),
    short_code      text        NOT NULL,
    detected_at     timestamptz NOT NULL DEFAULT now(),
    caption_excerpt text,
    status          text        NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
    UNIQUE (target_id, short_code)   -- 재감지 중복 생성 방지 (거절돼도 되살아나지 않음)
);

-- 관측 대상 단위 (캠페인 수와 무관 — 계정·게시물당 1행/일)
CREATE TABLE profile_snapshot (
    username    text   NOT NULL,
    captured_on date   NOT NULL,
    followers   bigint,
    following   bigint,
    media_count bigint,
    PRIMARY KEY (username, captured_on)
);

CREATE TABLE post_snapshot (
    username     text   NOT NULL,
    short_code   text   NOT NULL,
    captured_on  date   NOT NULL,
    content_type text,             -- REELS / FEED
    likes        bigint,
    comments     bigint,
    views        bigint,
    saves        bigint,
    shares       bigint,
    reposts      bigint,
    PRIMARY KEY (short_code, captured_on)
);
CREATE INDEX idx_post_snapshot_username ON post_snapshot (username, captured_on);
```

- [ ] **Step 4: 로컬 init 스크립트** — `db/init/02-create-monitoring-db.sql` (01과 같은 제약: 새 볼륨에만 자동 적용, 기존 환경은 수동 실행):

```sql
-- monitoring 모듈 사설 DB + 계정 2종.
-- was_reader에는 접속 권한만 — 객체 GRANT는 monitoring Flyway(V2)가 소유자로서 부여한다.
CREATE ROLE monitoring LOGIN PASSWORD 'monitoring';
CREATE ROLE was_reader LOGIN PASSWORD 'was_reader';
CREATE DATABASE monitoring OWNER monitoring;
```

- [ ] **Step 5: 테스트 인프라** — `testsupport/TestDb.java` (analytics TestDb 전례 — 스키마 통째 재생성이라 테이블이 늘어도 갱신 불필요):

```java
package com.celfit.monitoring.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Testcontainers 공용 초기화 — raw·public 스키마 재생성 + Flyway 재적용. */
public final class TestDb {

	private static PostgreSQLContainer container;

	private TestDb() {
	}

	public static synchronized PostgreSQLContainer container() {
		if (container == null) {
			container = new PostgreSQLContainer("postgres:17-alpine");
			container.start();
		}
		return container;
	}

	public static DriverManagerDataSource dataSource(PostgreSQLContainer pg) {
		return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
	}

	/** was 읽기 전용 계정 시점의 DataSource — 권한 검증 테스트용. */
	public static DriverManagerDataSource wasReaderDataSource(PostgreSQLContainer pg) {
		return new DriverManagerDataSource(pg.getJdbcUrl(), "was_reader", "was_reader");
	}

	public static void resetAndMigrate(JdbcTemplate db, DataSource ds) {
		db.update("DROP SCHEMA IF EXISTS raw CASCADE");
		db.update("DROP SCHEMA public CASCADE");
		db.update("CREATE SCHEMA public");
		db.update("""
				DO $$ BEGIN
				  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'was_reader')
				  THEN CREATE ROLE was_reader LOGIN PASSWORD 'was_reader'; END IF;
				END $$""");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
	}
}
```

- [ ] **Step 6: 실패하는 테스트 작성** — `MigrationTest.java`:

```java
package com.celfit.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest {

	@Test
	void 마이그레이션이_핵심_테이블을_만든다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long tables = db.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE (table_schema, table_name) IN
				  (('raw','fetch_payload'), ('public','target'), ('public','detected_candidate'),
				   ('public','profile_snapshot'), ('public','post_snapshot'))""", Long.class);
		assertThat(tables).isEqualTo(5);
	}
}
```

- [ ] **Step 7: 실행 확인** — `./gradlew :monitoring:test` → PASS (V1이 없으면 FAIL부터 확인). 전체 회귀 `./gradlew build -x test` 컴파일 통과.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle monitoring db/init/02-create-monitoring-db.sql
git commit -m "feat(monitoring): 모듈 골격 + monitoring DB V1(2스키마 핵심 테이블) + 테스트 인프라"
```

---

### Task 3: 키워드 규칙 도메인

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/domain/KeywordRule.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/domain/TargetStatus.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/domain/TargetType.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/domain/CandidateStatus.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/domain/KeywordRuleTest.java`

**Interfaces:**
- Produces: `KeywordRule(List<String> and, List<String> any, List<String> exclude)` — `boolean matches(String caption)`, `boolean isValid()`. enum `TargetStatus{WATCHING,TRACKING,EXPIRED,CANCELED,FAILED}`(+`boolean active()`), `TargetType{ACCOUNT,POST}`, `CandidateStatus{PENDING,APPROVED,REJECTED}`.

- [ ] **Step 1: 실패하는 테스트**

```java
package com.celfit.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRuleTest {

	@Test
	void and는_전부_있어야_매칭() {
		var rule = new KeywordRule(List.of("샤넬", "립"), List.of(), List.of());
		assertThat(rule.matches("샤넬 신상 립 발색")).isTrue();
		assertThat(rule.matches("샤넬 신상 파운데이션")).isFalse();
	}

	@Test
	void any는_하나만_있어도_매칭() {
		var rule = new KeywordRule(List.of(), List.of("샤넬", "chanel"), List.of());
		assertThat(rule.matches("New CHANEL lipstick")).isTrue();  // 대소문자 무시
		assertThat(rule.matches("디올 신상")).isFalse();
	}

	@Test
	void exclude가_있으면_배제() {
		var rule = new KeywordRule(List.of(), List.of("샤넬"), List.of("이벤트"));
		assertThat(rule.matches("샤넬 이벤트 공지")).isFalse();
	}

	@Test
	void and와_any_조합() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("립", "쿠션"), List.of());
		assertThat(rule.matches("샤넬 쿠션 리뷰")).isTrue();
		assertThat(rule.matches("샤넬 향수 리뷰")).isFalse();
	}

	@Test
	void and_any_모두_비면_무효_그리고_무매칭() {
		var rule = new KeywordRule(List.of(), List.of(), List.of("이벤트"));
		assertThat(rule.isValid()).isFalse();
		assertThat(rule.matches("아무 캡션")).isFalse();
	}

	@Test
	void null_캡션은_무매칭() {
		assertThat(new KeywordRule(List.of("샤넬"), List.of(), List.of()).matches(null)).isFalse();
	}
}
```

- [ ] **Step 2: 실행 → FAIL 확인** — `./gradlew :monitoring:test --tests '*KeywordRuleTest'` → 컴파일 실패(클래스 없음).

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.domain;

import java.util.List;
import java.util.Locale;

/**
 * 키워드 규칙 3종 목록 — 매칭 = (and 전부) ∧ (any 비었거나 하나 이상) ∧ (exclude 전무).
 * 부분 문자열·대소문자 무시·캡션 전문 대상(해시태그도 캡션의 일부라 자연 포함).
 */
public record KeywordRule(List<String> and, List<String> any, List<String> exclude) {

	public KeywordRule {
		and = and == null ? List.of() : List.copyOf(and);
		any = any == null ? List.of() : List.copyOf(any);
		exclude = exclude == null ? List.of() : List.copyOf(exclude);
	}

	/** and·any 중 최소 한 목록은 비어 있지 않아야 등록 가능. */
	public boolean isValid() {
		return !and.isEmpty() || !any.isEmpty();
	}

	public boolean matches(String caption) {
		if (caption == null || !isValid()) {
			return false;
		}
		String c = caption.toLowerCase(Locale.ROOT);
		if (!and.stream().allMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)))) {
			return false;
		}
		if (!any.isEmpty() && any.stream().noneMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)))) {
			return false;
		}
		return exclude.stream().noneMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)));
	}
}
```

enum 3종:

```java
package com.celfit.monitoring.domain;

public enum TargetStatus {
	WATCHING, TRACKING, EXPIRED, CANCELED, FAILED;

	public boolean active() {
		return this == WATCHING || this == TRACKING;
	}
}
```

```java
package com.celfit.monitoring.domain;

public enum TargetType { ACCOUNT, POST }
```

```java
package com.celfit.monitoring.domain;

public enum CandidateStatus { PENDING, APPROVED, REJECTED }
```

- [ ] **Step 4: 실행 → PASS** — `./gradlew :monitoring:test --tests '*KeywordRuleTest'`

- [ ] **Step 5: Commit** — `git add monitoring/src && git commit -m "feat(monitoring): 키워드 규칙(and/any/exclude) 도메인 + 상태 어휘"`

---

### Task 4: Hiker 클라이언트 + 파서

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerHttp.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/JdkHikerHttp.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerProperties.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/ProfileInfo.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/PostInfo.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/SubjectNotFoundException.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/PrivateAccountException.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerFetchException.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/PropertiesConfig.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java`

**Interfaces:**
- Consumes: Task 1 픽스처 5종(`profile.json`·`medias.json`·`clips.json`·`media-by-code.json`·`media-by-code-feed.json`).
  필드 매핑·엔드포인트의 정본은 `docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md`
  (아래 코드는 그 findings를 이미 반영한 상태).
- Produces:
  - `HikerHttp { String get(String path); }` — 테스트 fake 지점.
  - `HikerClient.fetchProfile(String username) → ProfileInfo`
  - `HikerClient.fetchRecentPosts(String username, String userId, int pages) → List<PostInfo>`
  - `HikerClient.fetchPost(String shortCode) → PostInfo`
  - `ProfileInfo(String username, String userId, Long followers, Long following, Long mediaCount, String rawJson)`
  - `PostInfo(String shortCode, String username, String contentType, String caption, Long takenAt, Long likes, Long comments, Long views, Long saves, Long shares, Long reposts, String rawJson)` — 취득 불가 지표는 null. `takenAt`은 `taken_at` epoch초 — 핀 고정 게시물 함정(findings §3) 재정렬용, `fetchRecentPosts`는 takenAt 내림차순 정렬해 반환.
  - 예외: `SubjectNotFoundException`(404) / `PrivateAccountException` / `HikerFetchException`(그 외).

- [ ] **Step 1: 실패하는 테스트** — 픽스처 기반. Task 1에서 확보한 실제 응답을 fake `HikerHttp`로 돌려준다:

```java
package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HikerClientTest {

	private static String fixture(String name) {
		try (var in = HikerClientTest.class.getResourceAsStream("/hiker/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 경로별로 픽스처를 돌려주는 fake — 열거는 medias·clips 두 콜을 쏜다. */
	private static HikerHttp fakeHttp() {
		return path -> {
			if (path.startsWith("/v2/user/by/username")) return fixture("profile.json");
			if (path.startsWith("/v2/user/medias")) return fixture("medias.json");
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			return fixture("media-by-code.json");
		};
	}

	@Test
	void 프로필_파싱() {
		HikerClient client = new HikerClient(fakeHttp());
		ProfileInfo p = client.fetchProfile("rarebeauty");
		assertThat(p.userId()).isNotBlank();
		assertThat(p.followers()).isPositive();
		assertThat(p.rawJson()).isNotBlank();
	}

	@Test
	void 열거_파싱_릴스는_조회수_머지_피드는_저장공유_null() {
		HikerClient client = new HikerClient(fakeHttp());
		var posts = client.fetchRecentPosts("rarebeauty", "3109786630", 1);
		assertThat(posts).hasSize(12);                       // 1페이지 12건(findings §3)
		assertThat(posts).allSatisfy(p -> {
			assertThat(p.shortCode()).isNotBlank();
			assertThat(p.likes()).isNotNull();
			assertThat(p.comments()).isNotNull();
			assertThat(p.reposts()).isNotNull();             // media_repost_count는 전 타입 제공
		});
		// 핀 고정(2023년) 게시물이 맨 앞에 오지 않게 taken_at 내림차순 재정렬됨(findings §3)
		assertThat(posts.getFirst().takenAt()).isGreaterThanOrEqualTo(posts.getLast().takenAt());
		var reel = posts.stream().filter(p -> p.contentType().equals("REELS")).findFirst().orElseThrow();
		assertThat(reel.saves()).isNotNull();
		assertThat(reel.shares()).isNotNull();
		assertThat(reel.views()).isPositive();               // clips 열거에서 머지된 play_count
		var feed = posts.stream().filter(p -> p.contentType().equals("FEED")).findFirst().orElseThrow();
		assertThat(feed.views()).isNull();                   // 피드는 조회수 영구 null
		assertThat(feed.saves()).isNull();
		assertThat(feed.shares()).isNull();
	}

	@Test
	void 단건_파싱_릴스는_6지표_전량() {
		HikerClient client = new HikerClient(path -> fixture("media-by-code.json"));
		PostInfo p = client.fetchPost("DbV7LgZsKG8");
		assertThat(p.contentType()).isEqualTo("REELS");
		assertThat(p.caption()).isNotNull();
		assertThat(p.likes()).isPositive();
		assertThat(p.comments()).isPositive();
		assertThat(p.views()).isPositive();
		assertThat(p.saves()).isPositive();
		assertThat(p.shares()).isPositive();
		assertThat(p.reposts()).isPositive();
	}

	@Test
	void 단건_파싱_피드는_조회_저장_공유가_null() {
		HikerClient client = new HikerClient(path -> fixture("media-by-code-feed.json"));
		PostInfo p = client.fetchPost("DbOMP1_CY18");
		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.likes()).isPositive();
		assertThat(p.views()).isNull();
		assertThat(p.saves()).isNull();
		assertThat(p.shares()).isNull();
	}

	@Test
	void _404는_SubjectNotFound로() {
		HikerClient client = new HikerClient(path -> { throw new SubjectNotFoundException("404"); });
		assertThatThrownBy(() -> client.fetchProfile("ghost"))
				.isInstanceOf(SubjectNotFoundException.class);
	}
}
```

- [ ] **Step 2: 실행 → FAIL 확인** — `./gradlew :monitoring:test --tests '*HikerClientTest'`

- [ ] **Step 3: 구현** — 예외 3종은 `RuntimeException` 상속 단순 클래스(메시지 생성자만). `HikerProperties`:

```java
package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("monitoring.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout) {}
```

`config/PropertiesConfig.java`:

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.HikerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HikerProperties.class)
public class PropertiesConfig {}
```

`HikerHttp` / `JdkHikerHttp` — crawler 관용구를 monitoring 소유로 재작성(공유 금지):

```java
package com.celfit.monitoring.hiker;

/** HikerAPI HTTP 전송 격리 — 테스트에서 fake로 대체. path는 base-url 이후(쿼리 포함). */
public interface HikerHttp {
	String get(String path);
}
```

```java
package com.celfit.monitoring.hiker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class JdkHikerHttp implements HikerHttp {

	private final HttpClient client = HttpClient.newHttpClient();
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;

	public JdkHikerHttp(HikerProperties props) {
		this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
		this.apiKey = props.apiKey();
		this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
	}

	@Override
	public String get(String path) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new HikerFetchException("HIKER_API_KEY 미설정");
		}
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("x-access-key", apiKey)
				.header("accept", "application/json")
				.GET().build();
		try {
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 404) {
				throw new SubjectNotFoundException("Hiker 404: " + res.body());
			}
			if (res.statusCode() >= 300) {
				throw new HikerFetchException("Hiker HTTP " + res.statusCode() + ": " + res.body());
			}
			return res.body();
		} catch (IOException e) {
			throw new HikerFetchException("Hiker 요청 실패: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 요청 중단", e);
		}
	}
}
```

`HikerClient` — Jackson 3(`tools.jackson`)로 파싱. 필드 후보는 Task 1 findings 기준으로 유지·수정:

```java
package com.celfit.monitoring.hiker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HikerClient {

	private static final Logger log = LoggerFactory.getLogger(HikerClient.class);
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final HikerHttp http;

	public HikerClient(HikerHttp http) {
		this.http = http;
	}

	public ProfileInfo fetchProfile(String username) {
		String body = http.get("/v2/user/by/username?username=" + username);
		JsonNode user = root(body).path("user");
		if (user.isMissingNode() || user.isNull()) {
			throw new HikerFetchException("프로필 응답에 user 없음: " + username);
		}
		if (user.path("is_private").asBoolean(false)) {
			throw new PrivateAccountException("비공개 계정: " + username);
		}
		return new ProfileInfo(username, user.path("pk").asString(),
				firstLong(user, "follower_count"), firstLong(user, "following_count"),
				firstLong(user, "media_count"), body);
	}

	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		// v2 user/medias — 릴스+피드 전체 미디어(1페이지 12건). pages>1 확장은 next_page_id(YAGNI: 1페이지).
		// 단, 이 엔드포인트는 릴스여도 play_count를 안 준다(Task 1 findings §2) → clips 열거로 조회수 머지.
		Map<String, Long> plays = fetchClipPlays(userId);
		String body = http.get("/v2/user/medias?user_id=" + userId);
		List<PostInfo> out = new ArrayList<>();
		for (JsonNode item : items(root(body))) {
			out.add(toPost(item, username, body, plays));
		}
		// 핀 고정 게시물이 배열 맨 앞에 옴(taken_at 2023년 사례 — findings §3) → 게시 시각 내림차순 재정렬
		out.sort(java.util.Comparator.comparing(PostInfo::takenAt,
				java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
		return out;
	}

	/** 릴스 재생수 보강 — /v2/user/clips는 items[].media로 한 겹 더 감싼다. 실패해도 스윕은 계속(조회수만 null). */
	private Map<String, Long> fetchClipPlays(String userId) {
		Map<String, Long> plays = new HashMap<>();
		try {
			JsonNode root = root(http.get("/v2/user/clips?user_id=" + userId));
			for (JsonNode item : root.path("response").path("items")) {
				JsonNode m = item.path("media");
				Long play = firstLong(m, "play_count", "ig_play_count");
				if (play != null) {
					plays.put(m.path("code").asString(), play);
				}
			}
		} catch (RuntimeException e) {
			log.warn("클립 재생수 보강 실패 — user_id {}: {}", userId, e.getMessage());
		}
		return plays;
	}

	public PostInfo fetchPost(String shortCode) {
		String body = http.get("/v2/media/by/code?code=" + shortCode);
		return toPost(items(root(body)).getFirst(), null, body, Map.of());
	}

	private static JsonNode root(String body) {
		return MAPPER.readTree(body);
	}

	/** {response:{items:[...]}} / {items:[...]} / 배열 / 단일 객체 — 셰이프 유연 대응. */
	private static List<JsonNode> items(JsonNode root) {
		JsonNode node = root.has("response") ? root.path("response") : root;
		JsonNode arr = node.isArray() ? node
				: node.has("items") ? node.path("items")
				: node.has("medias") ? node.path("medias") : node;
		List<JsonNode> out = new ArrayList<>();
		if (arr.isArray()) {
			arr.forEach(out::add);
		} else {
			out.add(arr);
		}
		return out;
	}

	private static PostInfo toPost(JsonNode node, String usernameHint, String rawJson,
			Map<String, Long> clipPlays) {
		JsonNode m = node.has("media") ? node.path("media") : node;   // clips 열거는 한 겹 더 감쌈
		String code = m.path("code").asString();
		String username = usernameHint != null ? usernameHint : m.path("user").path("username").asString();
		// media_type==2는 일반 비디오 피드도 포함 → 릴스 판별은 product_type
		String contentType = "clips".equals(m.path("product_type").asString("")) ? "REELS" : "FEED";
		String caption = m.path("caption_text").isMissingNode()
				? m.path("caption").path("text").asString(null) : m.path("caption_text").asString(null);
		// view_count 키는 v2 응답에 부재 → 후보에서 제외. 열거 응답엔 play_count가 없어 clips 머지로 보강.
		Long views = firstLong(m, "play_count", "ig_play_count");
		return new PostInfo(code, username, contentType, caption,
				firstLong(m, "taken_at"),
				firstLong(m, "like_count"), firstLong(m, "comment_count"),
				views != null ? views : clipPlays.get(code),
				firstLong(m, "save_count"),          // 릴스 전용 — 피드·캐러셀은 키 부재 → null
				firstLong(m, "reshare_count"),       // 공유. 릴스 전용
				firstLong(m, "media_repost_count"),  // 리포스트. 전 타입 제공
				rawJson);
	}

	/** 후보 필드 중 처음 존재하는 값. 전부 없으면 null(취득 불가 지표 규칙). */
	private static Long firstLong(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.path(f);
			if (v.isNumber()) {
				return v.asLong();
			}
		}
		return null;
	}
}
```

record 2종:

```java
package com.celfit.monitoring.hiker;

public record ProfileInfo(String username, String userId, Long followers, Long following,
                          Long mediaCount, String rawJson) {}
```

```java
package com.celfit.monitoring.hiker;

public record PostInfo(String shortCode, String username, String contentType, String caption,
                       Long takenAt, Long likes, Long comments, Long views, Long saves,
                       Long shares, Long reposts, String rawJson) {}
```

- [ ] **Step 4: 실행 → PASS** — 픽스처 실제 셰이프에 맞춰 파서(경로·필드 후보)를 조정하며 GREEN까지. **조정 내용은 findings 문서에 추기.**

- [ ] **Step 5: Commit** — `git commit -m "feat(monitoring): Hiker 클라이언트·파서 — 프로필/열거/단건, 6지표 null 규칙"`

---

### Task 5: 저장 계층 (JdbcTemplate 리포지토리)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/TargetRow.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/TargetRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/CandidateRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/SnapshotRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/RawPayloadRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/StoreTest.java`

**Interfaces:**
- Consumes: Task 2 스키마, Task 3 enum·`KeywordRule`, Task 4 `ProfileInfo`/`PostInfo`.
- Produces:
  - `TargetRow(long id, TargetType type, String username, String shortCode, KeywordRule keywordRule, TargetStatus status, String trackedShortCode, String registrationKey, Instant expiresAt, String failReason)`
  - `TargetRepository`: `Optional<TargetRow> findByRegistrationKey(String)`, `Optional<TargetRow> findById(long)`, `long insert(TargetType, String username, String shortCode, KeywordRule, TargetStatus, String trackedShortCode, String registrationKey, Instant expiresAt)`, `List<TargetRow> findActive()`, `void markTracking(long id, String shortCode)`, `void close(long id, TargetStatus terminal, String failReason)`, `void updateExpiresAt(long id, Instant)`, `void touchFetched(long id)`, `int expireOverdue()`
  - `CandidateRepository`: `void insertPending(long targetId, String shortCode, String captionExcerpt)` (ON CONFLICT DO NOTHING), `Optional<CandidateRow> find(long id)` — `CandidateRow(long id, long targetId, String shortCode, CandidateStatus status)`, `void setStatus(long id, CandidateStatus)`
  - `SnapshotRepository`: `void upsertProfile(String username, LocalDate on, ProfileInfo p)`, `void upsertPost(LocalDate on, PostInfo p)`
  - `RawPayloadRepository`: `void save(String kind, String subject, int httpStatus, String payloadJson)`

- [ ] **Step 1: 실패하는 테스트** — 핵심 시맨틱만 고정:

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StoreTest {

	JdbcTemplate db;
	TargetRepository targets;
	CandidateRepository candidates;
	SnapshotRepository snapshots;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		candidates = new CandidateRepository(db);
		snapshots = new SnapshotRepository(db);
	}

	@Test
	void 등록키_멱등_조회와_keyword_rule_왕복() {
		var rule = new KeywordRule(List.of("샤넬"), List.of(), List.of("이벤트"));
		long id = targets.insert(TargetType.ACCOUNT, "acct_a", null, rule,
				TargetStatus.WATCHING, null, "key-1", Instant.parse("2026-08-28T00:00:00Z"));
		var found = targets.findByRegistrationKey("key-1").orElseThrow();
		assertThat(found.id()).isEqualTo(id);
		assertThat(found.keywordRule().and()).containsExactly("샤넬");
	}

	@Test
	void 같은_후보는_한_번만_생성() {
		long id = targets.insert(TargetType.ACCOUNT, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-2", Instant.now().plusSeconds(3600));
		candidates.insertPending(id, "SC1", "…샤넬…");
		candidates.insertPending(id, "SC1", "…샤넬…");
		assertThat(db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class)).isEqualTo(1);
	}

	@Test
	void 스냅샷은_일_1회_upsert() {
		var post = new PostInfo("SC1", "acct_a", "REELS", "캡션", 1753670000L, 10L, 2L, 100L, null, null, null, "{}");
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post);
		var post2 = new PostInfo("SC1", "acct_a", "REELS", "캡션", 1753670000L, 12L, 3L, 110L, null, null, null, "{}");
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post2);
		assertThat(db.queryForObject(
				"SELECT likes FROM post_snapshot WHERE short_code='SC1'", Long.class)).isEqualTo(12);
	}

	@Test
	void 만료_스윕은_활성만_EXPIRED로() {
		targets.insert(TargetType.POST, "acct_a", "SC1", null,
				TargetStatus.TRACKING, "SC1", "key-3", Instant.now().minusSeconds(60));
		int expired = targets.expireOverdue();
		assertThat(expired).isEqualTo(1);
		assertThat(targets.findActive()).isEmpty();
	}
}
```

- [ ] **Step 2: 실행 → FAIL 확인**

- [ ] **Step 3: 구현** — 대표 코드(`TargetRepository`; Candidate/Snapshot/RawPayload는 같은 스타일의 단순 SQL):

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TargetRepository {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final JdbcTemplate db;

	public TargetRepository(JdbcTemplate db) {
		this.db = db;
	}

	private static final RowMapper<TargetRow> ROW = (rs, i) -> new TargetRow(
			rs.getLong("id"), TargetType.valueOf(rs.getString("type")),
			rs.getString("username"), rs.getString("short_code"),
			rs.getString("keyword_rule") == null ? null
					: JsonMapper.builder().build().readValue(rs.getString("keyword_rule"), KeywordRule.class),
			TargetStatus.valueOf(rs.getString("status")), rs.getString("tracked_short_code"),
			rs.getString("registration_key"),
			rs.getTimestamp("expires_at").toInstant(), rs.getString("fail_reason"));

	public long insert(TargetType type, String username, String shortCode, KeywordRule rule,
			TargetStatus status, String trackedShortCode, String registrationKey, Instant expiresAt) {
		return db.queryForObject("""
				INSERT INTO target (type, username, short_code, keyword_rule, status,
				                    tracked_short_code, tracked_since, registration_key, expires_at)
				VALUES (?, ?, ?, ?::jsonb, ?, ?, CASE WHEN ? IS NOT NULL THEN now() END, ?, ?)
				RETURNING id""",
				Long.class, type.name(), username, shortCode,
				rule == null ? null : JSON.writeValueAsString(rule), status.name(),
				trackedShortCode, trackedShortCode, registrationKey,
				java.sql.Timestamp.from(expiresAt));
	}

	public Optional<TargetRow> findByRegistrationKey(String key) {
		return db.query("SELECT * FROM target WHERE registration_key = ?", ROW, key).stream().findFirst();
	}

	public Optional<TargetRow> findById(long id) {
		return db.query("SELECT * FROM target WHERE id = ?", ROW, id).stream().findFirst();
	}

	public List<TargetRow> findActive() {
		return db.query("SELECT * FROM target WHERE status IN ('WATCHING','TRACKING') ORDER BY id", ROW);
	}

	public void markTracking(long id, String shortCode) {
		db.update("UPDATE target SET status='TRACKING', tracked_short_code=?, tracked_since=now() WHERE id=?",
				shortCode, id);
	}

	public void close(long id, TargetStatus terminal, String failReason) {
		db.update("UPDATE target SET status=?, closed_at=now(), fail_reason=? WHERE id=?",
				terminal.name(), failReason, id);
	}

	public void updateExpiresAt(long id, Instant expiresAt) {
		db.update("UPDATE target SET expires_at=? WHERE id=?", java.sql.Timestamp.from(expiresAt), id);
	}

	public void touchFetched(long id) {
		db.update("UPDATE target SET last_fetched_at=now() WHERE id=?", id);
	}

	/** 만료 스윕 — 활성 상태만 EXPIRED로 종결. */
	public int expireOverdue() {
		return db.update("""
				UPDATE target SET status='EXPIRED', closed_at=now()
				WHERE status IN ('WATCHING','TRACKING') AND expires_at < now()""");
	}
}
```

`SnapshotRepository` upsert 관용구:

```java
public void upsertPost(LocalDate on, PostInfo p) {
	db.update("""
			INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
			                           likes, comments, views, saves, shares, reposts)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (short_code, captured_on) DO UPDATE SET
			  likes=EXCLUDED.likes, comments=EXCLUDED.comments, views=EXCLUDED.views,
			  saves=EXCLUDED.saves, shares=EXCLUDED.shares, reposts=EXCLUDED.reposts""",
			p.username(), p.shortCode(), on, p.contentType(),
			p.likes(), p.comments(), p.views(), p.saves(), p.shares(), p.reposts());
}
```

`CandidateRepository.insertPending`은 `ON CONFLICT (target_id, short_code) DO NOTHING`. `RawPayloadRepository.save`는 `INSERT INTO raw.fetch_payload (kind, subject, http_status, payload) VALUES (?, ?, ?, ?::jsonb)`.

- [ ] **Step 4: 실행 → PASS** — `./gradlew :monitoring:test --tests '*StoreTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(monitoring): 저장 계층 — target/candidate/snapshot/raw 리포지토리"`

---

### Task 6: 등록 API (멱등 + 동기 첫 수집)

인증 없음 — 접근 통제는 전용 도커 네트워크(`monitoring-net`, Task 11)가 강제한다. 토큰 필터를 만들지 않는다.

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/web/ApiExceptionHandler.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/web/TargetController.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/RegistrationService.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/CollectService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/RegistrationApiTest.java`

**Interfaces:**
- Consumes: Task 3~5 전부.
- Produces:
  - `POST /api/targets` — 계약 문서 §2-1의 JSON 그대로. 응답 `{targetId, status, firstSnapshot}` (201, 멱등 replay 200).
  - `CollectService.collectAccount(String username) → AccountCollectResult(ProfileInfo profile, List<PostInfo> posts)` — 원형 저장+스냅샷 upsert까지 수행. `CollectService.collectPost(String shortCode) → PostInfo` — 동일. (Task 8 스윕이 재사용)
  - `ApiExceptionHandler` — 계약 §2 에러 어휘(JSON `{code, message}`): `VALIDATION` 400 / `TARGET_NOT_FOUND`·`CANDIDATE_NOT_FOUND`·`SUBJECT_NOT_FOUND` 404 / `PRIVATE_ACCOUNT` 422 / `INVALID_STATE` 409 / `FETCH_FAILED` 502. (인증 에러 없음 — 네트워크 격리)

- [ ] **Step 1: 실패하는 테스트** — `@SpringBootTest` + Testcontainers + fake `HikerHttp` 빈(픽스처 반환). 케이스: ①ACCOUNT 등록 201 + target·스냅샷·원형 적재 ②같은 registrationKey 재호출 200·행 1개 ③keywordRule and·any 모두 빈 배열 → 400 VALIDATION ④Hiker 404 → 404 SUBJECT_NOT_FOUND·target 미생성:

```java
package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.hiker.HikerHttp;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.testsupport.TestDb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class RegistrationApiTest {

	static class SwitchableHiker implements HikerHttp {
		volatile boolean notFound = false;

		private static String fixture(String name) {
			try (var in = RegistrationApiTest.class.getResourceAsStream("/hiker/" + name)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public String get(String path) {
			if (notFound) {
				throw new SubjectNotFoundException("404");
			}
			if (path.startsWith("/v2/user/by/username")) {
				return fixture("profile.json");
			}
			if (path.startsWith("/v2/user/medias")) {
				return fixture("medias.json");
			}
			if (path.startsWith("/v2/user/clips")) {
				return fixture("clips.json");
			}
			return fixture("media-by-code.json");
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		SwitchableHiker fakeHiker() {
			return new SwitchableHiker();
		}
	}

	@DynamicPropertySource
	static void dbProps(DynamicPropertyRegistry r) {
		var pg = TestDb.container();
		r.add("spring.datasource.url", pg::getJdbcUrl);
		r.add("spring.datasource.username", pg::getUsername);
		r.add("spring.datasource.password", pg::getPassword);
	}

	@Autowired WebApplicationContext ctx;
	@Autowired JdbcTemplate db;
	@Autowired SwitchableHiker hiker;
	MockMvc mvc;

	private static final String ACCOUNT_BODY = """
			{"registrationKey":"rk-1","type":"ACCOUNT","username":"someuser",
			 "keywordRule":{"and":[],"any":["샤넬"],"exclude":[]},
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM detected_candidate");
		db.update("DELETE FROM target");
		hiker.notFound = false;
	}

	@Test
	void 계정_등록은_동기_첫_수집까지_하고_201() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WATCHING"))
				.andExpect(jsonPath("$.firstSnapshot.profile.followers").isNumber());
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class)).isGreaterThan(0);
	}

	@Test
	void 같은_registrationKey는_replay_200_행_1개() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isOk());
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isEqualTo(1);
	}

	@Test
	void 키워드_and_any_모두_비면_400_VALIDATION() throws Exception {
		String bad = ACCOUNT_BODY.replace("\"any\":[\"샤넬\"]", "\"any\":[]");
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@Test
	void 계정_없음은_404_SUBJECT_NOT_FOUND_target_미생성() throws Exception {
		hiker.notFound = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}
}
```

- [ ] **Step 2: 실행 → FAIL 확인**

- [ ] **Step 3: 구현** — `CollectService` — 원형 저장 + 스냅샷 upsert를 한 곳에(등록·스윕 공용):

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectService {

	public record AccountCollectResult(ProfileInfo profile, List<PostInfo> posts) {}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final RawPayloadRepository rawPayloads;
	private final SnapshotRepository snapshots;
	private final int enumeratePages;

	public CollectService(HikerClient hiker, RawPayloadRepository rawPayloads,
			SnapshotRepository snapshots, @Value("${monitoring.enumerate-pages:1}") int enumeratePages) {
		this.hiker = hiker;
		this.rawPayloads = rawPayloads;
		this.snapshots = snapshots;
		this.enumeratePages = enumeratePages;
	}

	/** 계정 1회 수집 — 원형 적재 + 프로필·게시물 스냅샷 upsert. */
	@Transactional
	public AccountCollectResult collectAccount(String username) {
		LocalDate today = LocalDate.now(KST);
		ProfileInfo profile = hiker.fetchProfile(username);
		rawPayloads.save("PROFILE", username, 200, profile.rawJson());
		snapshots.upsertProfile(username, today, profile);
		List<PostInfo> posts = hiker.fetchRecentPosts(username, profile.userId(), enumeratePages);
		if (!posts.isEmpty()) {
			rawPayloads.save("POSTS", username, 200, posts.getFirst().rawJson());
			posts.forEach(p -> snapshots.upsertPost(today, p));
		}
		return new AccountCollectResult(profile, posts);
	}

	/** 게시물 1회 수집 — 원형 적재 + 스냅샷 upsert. */
	@Transactional
	public PostInfo collectPost(String shortCode) {
		PostInfo post = hiker.fetchPost(shortCode);
		rawPayloads.save("POST", shortCode, 200, post.rawJson());
		snapshots.upsertPost(LocalDate.now(KST), post);
		return post;
	}
}
```

`RegistrationService` — 검증(타입별 필수 필드, `KeywordRule.isValid()`, `expiresAt` 미래) → 멱등 replay → 수집 → target INSERT. `ValidationException`(직접 정의, `VALIDATION` 매핑) 사용. `TargetController`는 계약 §2대로 매핑하고 `RegisterResponse(long targetId, String status, Object firstSnapshot, boolean replayed)`로 201/200 분기. `ApiExceptionHandler`는 `@RestControllerAdvice`로 예외→`{code,message}` 매핑(§2 표 그대로).

- [ ] **Step 4: 실행 → PASS** — `./gradlew :monitoring:test --tests '*RegistrationApiTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(monitoring): 등록 API — registration_key 멱등·동기 첫 수집·에러 어휘"`

---

### Task 7: 후보 승인/거절 + 연장/해지 API

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/TargetController.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/TargetCommandService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/CommandApiTest.java`

**Interfaces:**
- Consumes: Task 5 리포지토리, Task 6 `CollectService`·에러 어휘.
- Produces: 계약 §2-2~2-5의 4개 엔드포인트. `TargetCommandService`: `approve(long targetId, long candidateId)`, `reject(long targetId, long candidateId)`, `extend(long targetId, Instant expiresAt)`, `cancel(long targetId)`.

- [ ] **Step 1: 실패하는 테스트** — `CommandApiTest`(RegistrationApiTest와 같은 픽스처·컨테이너 배선). 케이스: ①WATCHING target에 후보 심고 approve → 200·status TRACKING·tracked_short_code 확정·후보 APPROVED·post_snapshot 생성 ②reject → 후보 REJECTED·target WATCHING 유지 ③이미 TRACKING인 target에 approve → 409 INVALID_STATE ④없는 후보 → 404 CANDIDATE_NOT_FOUND ⑤PATCH expiresAt → 200·연장 반영 ⑥DELETE → 200 CANCELED·재DELETE도 200(멱등) ⑦종결 target에 PATCH → 409 INVALID_STATE. (후보는 테스트에서 `CandidateRepository.insertPending`으로 직접 심는다.)

- [ ] **Step 2: 실행 → FAIL 확인**

- [ ] **Step 3: 구현** — `TargetCommandService` 핵심:

```java
@Transactional
public TargetRow approve(long targetId, long candidateId) {
	TargetRow target = targets.findById(targetId)
			.orElseThrow(() -> new TargetNotFoundException(targetId));
	if (target.status() != TargetStatus.WATCHING) {
		throw new InvalidStateException("WATCHING 상태에서만 승인 가능: " + target.status());
	}
	CandidateRow candidate = candidates.find(candidateId)
			.filter(c -> c.targetId() == targetId)
			.orElseThrow(() -> new CandidateNotFoundException(candidateId));
	if (candidate.status() != CandidateStatus.PENDING) {
		throw new InvalidStateException("PENDING 후보만 승인 가능: " + candidate.status());
	}
	candidates.setStatus(candidateId, CandidateStatus.APPROVED);
	targets.markTracking(targetId, candidate.shortCode());
	collectService.collectPost(candidate.shortCode());   // 승인 즉시 1회 수집
	targets.touchFetched(targetId);
	return targets.findById(targetId).orElseThrow();
}
```

`reject`는 PENDING 검사 후 `setStatus(REJECTED)`. `cancel`은 활성이면 `close(id, CANCELED, null)`, 이미 종결이면 그대로 반환(멱등). `extend`는 활성 검사 후 `updateExpiresAt`. 예외 3종(`TargetNotFoundException`·`CandidateNotFoundException`·`InvalidStateException`)은 `web` 패키지가 아닌 `service`에 두고 `ApiExceptionHandler`에 매핑 추가.

- [ ] **Step 4: 실행 → PASS**

- [ ] **Step 5: Commit** — `git commit -m "feat(monitoring): 승인/거절·연장/해지 명령 API — 상태 전이 규칙 포함"`

---

### Task 8: 일일 스윕 배치

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/DailySweepJobTest.java`

**Interfaces:**
- Consumes: Task 5 리포지토리, Task 6 `CollectService`, Task 3 `KeywordRule`.
- Produces: `DailySweepJob.run()` — Task 9 스케줄러·수동 트리거가 호출.

- [ ] **Step 1: 실패하는 테스트** — fake `HikerHttp`(픽스처 + 계정별 분기·404 스위치)로:

케이스: ①만료 지난 활성 target이 EXPIRED로 종결 ②같은 username의 WATCHING 캠페인 2개 — Hiker 프로필 호출이 **계정당 1회**(fake 호출 카운트 단언), 키워드가 다른 두 캠페인이 각자 자기 규칙으로만 후보 생성 ③매칭 게시물이 `detected_candidate(PENDING)`로 쌓이고 재실행해도 중복 생성 없음 ④TRACKING target의 tracked_short_code가 열거에 없으면 단건 콜로 스냅샷 보강 ⑤한 계정 Hiker 오류가 다른 계정 처리를 막지 않음(실패 격리) ⑥404 계정은 그 계정의 활성 target 전부 FAILED(fail_reason `SUBJECT_NOT_FOUND`).

```java
// 대표 케이스 ② 골격 — 나머지는 같은 배선으로 상태·카운트 단언
@Test
void 같은_계정_두_캠페인은_수집_1회_감지는_각자() {
	long a = targets.insert(TargetType.ACCOUNT, "someuser", null,
			new KeywordRule(List.of(), List.of("샤넬"), List.of()),
			TargetStatus.WATCHING, null, "rk-a", Instant.now().plusSeconds(86400));
	long b = targets.insert(TargetType.ACCOUNT, "someuser", null,
			new KeywordRule(List.of(), List.of("절대없는키워드zz"), List.of()),
			TargetStatus.WATCHING, null, "rk-b", Instant.now().plusSeconds(86400));

	job.run();

	assertThat(fakeHiker.profileCalls).isEqualTo(1);   // 계정당 1회
	assertThat(candidateCount(a)).isGreaterThan(0);    // 픽스처 캡션에 '샤넬' 포함 전제 — 없으면 any 키워드를 픽스처 캡션 단어로 교체
	assertThat(candidateCount(b)).isZero();
}
```

- [ ] **Step 2: 실행 → FAIL 확인**

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕(KST 02:00) — 만료 처리 → 계정별 1회 수집(캠페인 수와 무관) →
 * WATCHING 키워드 감지 → TRACKING 게시물 보강. 실패는 계정 단위 격리.
 */
@Service
public class DailySweepJob {

	private static final Logger log = LoggerFactory.getLogger(DailySweepJob.class);
	private static final int EXCERPT_LEN = 120;

	private final TargetRepository targets;
	private final CandidateRepository candidates;
	private final CollectService collect;

	public DailySweepJob(TargetRepository targets, CandidateRepository candidates, CollectService collect) {
		this.targets = targets;
		this.candidates = candidates;
		this.collect = collect;
	}

	public void run() {
		int expired = targets.expireOverdue();
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.collect(Collectors.groupingBy(TargetRow::username));
		int failedAccounts = 0;
		for (var entry : byUsername.entrySet()) {
			try {
				sweepAccount(entry.getKey(), entry.getValue());
			} catch (SubjectNotFoundException e) {
				entry.getValue().forEach(t ->
						targets.close(t.id(), TargetStatus.FAILED, "SUBJECT_NOT_FOUND"));
				failedAccounts++;
			} catch (Exception e) {
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.getMessage());
				failedAccounts++;
			}
		}
		log.info("스윕 완료 — 계정 {}건(실패 {}), 만료 {}건", byUsername.size(), failedAccounts, expired);
	}

	private void sweepAccount(String username, List<TargetRow> accountTargets) {
		boolean needsEnumeration = accountTargets.stream()
				.anyMatch(t -> t.status() == TargetStatus.WATCHING
						|| (t.status() == TargetStatus.TRACKING && t.keywordRule() != null));
		List<PostInfo> posts = List.of();
		if (needsEnumeration || hasAccountType(accountTargets)) {
			posts = collect.collectAccount(username).posts();
		}
		var enumerated = posts.stream().map(PostInfo::shortCode).collect(Collectors.toSet());
		for (TargetRow t : accountTargets) {
			if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
				for (PostInfo p : posts) {
					// 등록 시각 이후 게시물만 감지(07-29 확정) — 등록 전 옛 키워드 게시물 노이즈 차단.
					// taken_at 미상은 보수적으로 제외. TargetRow에 registeredAt 필드 필요(11번째).
					boolean postedAfterRegistration = p.takenAt() != null
							&& !Instant.ofEpochSecond(p.takenAt()).isBefore(t.registeredAt());
					if (postedAfterRegistration && t.keywordRule().matches(p.caption())) {
						candidates.insertPending(t.id(), p.shortCode(), excerpt(p.caption()));
					}
				}
			}
			String tracked = t.status() == TargetStatus.TRACKING ? t.trackedShortCode() : null;
			if (tracked != null && !enumerated.contains(tracked)) {
				collect.collectPost(tracked);   // 열거 범위 밖으로 밀려난 추적 게시물 보강
			}
			targets.touchFetched(t.id());
		}
	}

	private static boolean hasAccountType(List<TargetRow> ts) {
		return ts.stream().anyMatch(t -> t.type() == com.celfit.monitoring.domain.TargetType.ACCOUNT);
	}

	private static String excerpt(String caption) {
		if (caption == null) {
			return null;
		}
		return caption.length() <= EXCERPT_LEN ? caption : caption.substring(0, EXCERPT_LEN) + "…";
	}
}
```

주의: `type=POST` 단독 계정(열거 불필요)은 `collectAccount`를 건너뛰고 `collectPost(t.shortCode())`만 — 위 골격에 분기 추가(테스트 ④·게시물 등록 케이스로 고정).

- [ ] **Step 4: 실행 → PASS** — `./gradlew :monitoring:test --tests '*DailySweepJobTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(monitoring): 일일 스윕 — 만료·계정당 1회 수집·키워드 감지·추적 보강·실패 격리"`

---

### Task 9: 스케줄 배선

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/SweepScheduler.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/SweepSchedulerTest.java`

**Interfaces:**
- Consumes: Task 8 `DailySweepJob.run()`.
- Produces: `monitoring.schedule.sweep-cron` 프로퍼티로 켜지는 `@Scheduled` 러너.

- [ ] **Step 1: 테스트** — cron 표현식 검증 수준(스케줄 실동작은 스프링 몫): `CronExpression.parse("0 0 17 * * *")`가 유효하고, 기본값 `-`일 때 스케줄러 빈이 등록돼도 예외 없이 뜨는지 `@SpringBootTest`(Task 6 배선 재사용) 컨텍스트 로드로 확인.

- [ ] **Step 2: 구현**

```java
package com.celfit.monitoring.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 일일 스윕 스케줄 — 기본 "-"(비활성). 운영은 env로 UTC 크론 주입(KST 02:00 = 0 0 17 * * *). */
@Component
public class SweepScheduler {

	private final DailySweepJob job;

	public SweepScheduler(DailySweepJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${monitoring.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		job.run();
	}
}
```

- [ ] **Step 3: 실행 → PASS + 전체 회귀** — `./gradlew :monitoring:test`

- [ ] **Step 4: Commit** — `git commit -m "feat(monitoring): 스윕 크론 배선 — 기본 비활성, env 주입"`

---

### Task 10: 조회 표면 V2 — 뷰 + was_reader 권한

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V2__read_surface.sql`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/ReadSurfaceTest.java`

**Interfaces:**
- Produces: `v_target_overview`·`v_target_timeseries` 뷰, was_reader 권한 체계. (계약 문서 §3의 조회 표면 — 뷰 확정 시 계약 문서 갱신은 Task 12)

- [ ] **Step 1: V2 마이그레이션**

```sql
-- 조회 뷰 — was 캠페인 화면이 내부 테이블 구조에 직접 의존하지 않게 하는 계약 표면.

-- 캠페인 목록: target + 최신 프로필 스냅샷 + 승인 대기 후보 수
CREATE VIEW v_target_overview AS
SELECT t.id AS target_id, t.type, t.username, t.short_code, t.keyword_rule, t.status,
       t.tracked_short_code, t.tracked_since, t.expires_at, t.registered_at, t.closed_at,
       t.last_fetched_at, t.fail_reason,
       ps.captured_on AS profile_captured_on, ps.followers, ps.media_count,
       (SELECT count(*) FROM detected_candidate c
         WHERE c.target_id = t.id AND c.status = 'PENDING') AS pending_candidates
FROM target t
LEFT JOIN LATERAL (
    SELECT * FROM profile_snapshot p
    WHERE p.username = t.username ORDER BY p.captured_on DESC LIMIT 1
) ps ON true;

-- 추적 게시물 일별 추이 + 전일 대비 증감(파생 집계)
CREATE VIEW v_target_timeseries AS
SELECT t.id AS target_id, s.captured_on, s.content_type,
       s.likes, s.comments, s.views, s.saves, s.shares, s.reposts,
       s.likes    - lag(s.likes)    OVER w AS likes_delta,
       s.comments - lag(s.comments) OVER w AS comments_delta,
       s.views    - lag(s.views)    OVER w AS views_delta,
       s.saves    - lag(s.saves)    OVER w AS saves_delta,
       s.shares   - lag(s.shares)   OVER w AS shares_delta,
       s.reposts  - lag(s.reposts)  OVER w AS reposts_delta
FROM target t
JOIN post_snapshot s ON s.short_code = t.tracked_short_code
WINDOW w AS (PARTITION BY t.id ORDER BY s.captured_on);

-- was 읽기 전용 권한 — public 조회 표면만. raw 스키마는 GRANT 자체가 없어 접근 불가(fail-closed).
GRANT USAGE ON SCHEMA public TO was_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO was_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO was_reader;
```

- [ ] **Step 2: 실패하는 테스트 → 구현 확인** — `ReadSurfaceTest`: ①시드(WATCHING target + 후보 + 스냅샷 이틀치) 후 `v_target_overview.pending_candidates`=1, `v_target_timeseries`의 둘째 날 `likes_delta` 정확 ②`TestDb.wasReaderDataSource()`로 접속해 `SELECT count(*) FROM v_target_overview` 성공 ③같은 접속으로 `INSERT INTO target …` 시도 → `permission denied` 예외 단언 ④`SELECT * FROM raw.fetch_payload` 시도 → `permission denied` 예외 단언.

- [ ] **Step 3: 실행 → PASS** — `./gradlew :monitoring:test --tests '*ReadSurfaceTest'`

- [ ] **Step 4: Commit** — `git commit -m "feat(monitoring): 조회 표면 V2 — 뷰 2종 + was_reader 읽기 전용·raw 무권한 강제"`

---

### Task 11: 운영 배선 (compose·CD·백업)

**Files:**
- Modify: `deploy/compose.yaml` (monitoring 서비스 추가)
- Modify: `.github/workflows/cd.yml` (matrix에 monitoring)
- Modify: `deploy/scripts/backup.sh` (monitoring DB pg_dump)
- Modify: `deploy/README.md` (개통 체크리스트 절 추가)

**Interfaces:**
- Consumes: Task 2~10 완성된 모듈.
- Produces: main 머지 시 monitoring 컨테이너가 배포되는 CD 경로. **서버 postgres에 DB·계정 생성은 수동 1회 ops**(README 체크리스트) — CD보다 먼저 실행돼야 한다.

- [ ] **Step 1: compose 서비스 추가 + 전용 네트워크** — `deploy/compose.yaml`:

파일 하단 `volumes:` 옆에 최상위 네트워크 정의 추가:

```yaml
networks:
  # 명령 API 접근 통제 — was↔monitoring 전용(스펙 결정 11). 토큰 없음:
  # 이 네트워크 미소속 컨테이너(dev 스택 포함)는 monitoring 호스트명 해석부터 실패.
  monitoring-net: {}
```

기존 `was:` 서비스에 두 줄 추가 (다른 서비스는 무접촉 — networks를 선언하지 않은 서비스는 default에 그대로 남는다):

```yaml
  was:
    # …기존 정의 유지…
    networks: [default, monitoring-net]
```

`was:` 블록 뒤에 monitoring 서비스 (analytics 블록 관용구 준수 — **ports 매핑 없음**: 어드민 UI가 없고 유일한 클라이언트 was는 네트워크로 붙는다. 호스트 8083은 dev-analytics 터널이 이미 점유 중이라 충돌 회피이기도 함):

```yaml
  # 시딩 캠페인 모니터링 — 사설 monitoring DB(postgres 인스턴스 내). 스윕 KST 02:00(UTC 17:00).
  # 호스트 포트 미노출 — 디버깅은 docker exec deploy-was-1 curl http://monitoring:8083/… 경유.
  monitoring:
    image: ghcr.io/subtle-madness/hypenow-monitoring:latest
    restart: unless-stopped
    logging: *logging
    networks: [default, monitoring-net]   # default는 postgres 접속용
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/monitoring
      SPRING_DATASOURCE_USERNAME: ${MONITORING_DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${MONITORING_DB_PASSWORD}
      HIKER_API_KEY: ${HIKER_API_KEY}
      MONITORING_SCHEDULE_SWEEP_CRON: "0 0 17 * * *"
      JAVA_OPTS: "-Xms128m -Xmx384m"
    healthcheck:
      test: ["CMD-SHELL", "bash -c '</dev/tcp/127.0.0.1/8083' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 60s
    depends_on:
      postgres:
        condition: service_healthy
```

- [ ] **Step 2: CD 매트릭스** — `.github/workflows/cd.yml`: `service: [was, analytics, crawler]` → `service: [was, analytics, crawler, monitoring]`, 서버 배포 스텝의 `docker compose pull was analytics crawler`에 `monitoring` 추가. 배포 후 확인은 호스트 포트가 없으므로 `/health` curl 대신 `docker compose ps monitoring` 출력의 `(healthy)` 문자열 grep으로 판정.

- [ ] **Step 3: 백업** — `deploy/scripts/backup.sh`의 analysis pg_dump 옆에 같은 관용구로 monitoring DB 덤프 1줄 + Drive 롤링(기존 crawler/analysis 경로 준수, `hypenow-backups/monitoring/`).

- [ ] **Step 4: 개통 체크리스트** — `deploy/README.md`에 §13 추가:

```markdown
## 13. monitoring 모듈 개통 (1회 ops — 첫 CD 배포 전에)

1. 서버 postgres 컨테이너에 DB·계정 생성 (db/init/02와 동일, 비밀번호는 실값):
   docker exec -it deploy-postgres-1 psql -U $DB_USER -d postgres -c \
     "CREATE ROLE monitoring LOGIN PASSWORD '<실값>'; CREATE ROLE was_reader LOGIN PASSWORD '<실값>'; CREATE DATABASE monitoring OWNER monitoring;"
2. ~/deploy/.env에 추가: MONITORING_DB_USER, MONITORING_DB_PASSWORD, (기존 확인) HIKER_API_KEY
3. develop→main 머지로 배포 → docker compose ps에서 monitoring healthy 확인
   (호스트 포트 없음 — 수동 확인은 docker exec deploy-was-1 curl http://monitoring:8083/… 경유)
4. 컨테이너 다운 알람 대상에 monitoring 추가 (post-container-metrics.py의 감시 목록)
```

`post-container-metrics.py`의 컨테이너 목록에 monitoring을 실제로 추가한다(파일 열어 기존 목록 관용구 확인 후 1줄).

- [ ] **Step 5: 검증** — `./gradlew :monitoring:bootJar` 성공, `docker compose -f deploy/compose.yaml config -q` 통과(env 미설정 경고는 무시), backup.sh는 `bash -n` 문법 체크.

- [ ] **Step 6: Commit** — `git commit -m "chore(monitoring): 운영 배선 — compose·CD 매트릭스·백업·개통 체크리스트"`

---

### Task 12: dev 스택 편입 (dev-monitoring)

**Files:**
- Modify: `deploy/compose.dev.yaml` (dev-monitoring 서비스 + dev-monitoring-net)
- Modify: `.github/workflows/cd-dev.yml` (빌드 매트릭스·pull·up 대상)
- Modify: `deploy/README.md` (§12 dev 개통 체크리스트에 monitoring DB 생성 항목)

**Interfaces:**
- Consumes: Task 11의 운영 배선 패턴(같은 구조의 dev 판).
- Produces: develop 푸시마다 `dev-monitoring`이 자동 배포되고, dev-was가 `http://dev-monitoring:8083`으로 호출 가능한 환경.

- [ ] **Step 1: compose.dev.yaml** — 파일 하단에 네트워크 정의, dev-was에 networks 추가, dev-monitoring 서비스 추가 (K 원칙 "dev 스케줄 전부 off" — 스윕 크론 미설정, 등록 동기 수집만 동작. Hiker 키는 운영 공유 — 등록 테스트 소량 전제):

```yaml
networks:
  dev-monitoring-net: {}   # dev-was ↔ dev-monitoring 전용 (운영 monitoring-net과 상호 불가침)
```

```yaml
  dev-was:
    # …기존 정의 유지…
    networks: [default, dev-monitoring-net]
```

```yaml
  # dev 모니터링 — dev-postgres의 monitoring DB. 스윕 크론 off(K 원칙), 등록 동기 수집만.
  dev-monitoring:
    image: ghcr.io/subtle-madness/hypenow-monitoring:develop
    profiles: ["dev"]
    restart: unless-stopped
    logging: *logging
    mem_limit: 512m
    cpus: 0.5
    networks: [default, dev-monitoring-net]   # default는 dev-postgres 접속용
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://dev-postgres:5432/monitoring
      SPRING_DATASOURCE_USERNAME: monitoring
      SPRING_DATASOURCE_PASSWORD: ${DEV_MONITORING_DB_PASSWORD:-}
      HIKER_API_KEY: ${HIKER_API_KEY:-}
      JAVA_OPTS: "-Xms128m -Xmx256m"
    healthcheck:
      test: ["CMD-SHELL", "bash -c '</dev/tcp/127.0.0.1/8083' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 60s
    depends_on:
      dev-postgres:
        condition: service_healthy
```

- [ ] **Step 2: cd-dev.yml** — 빌드 매트릭스 `service: [was, analytics]` → `[was, analytics, monitoring]`, 재기동 스텝의 pull 대상에 `dev-monitoring`, up 대상에 `dev-monitoring` 추가.

- [ ] **Step 3: dev DB 생성 항목** — `deploy/README.md` §12(dev 개통 체크리스트)에 추가:

```markdown
- dev-postgres에 monitoring DB·계정 생성 (1회):
  docker exec -it deploy-dev-postgres-1 psql -U $DEV_DB_USER -d analysis -c \
    "CREATE ROLE monitoring LOGIN PASSWORD '<실값>'; CREATE ROLE was_reader LOGIN PASSWORD '<실값>'; CREATE DATABASE monitoring OWNER monitoring;"
- ~/deploy/.env에 DEV_MONITORING_DB_PASSWORD 추가
```

- [ ] **Step 4: 검증** — `docker compose -f deploy/compose.yaml -f deploy/compose.dev.yaml --profile dev config -q` 통과.

- [ ] **Step 5: Commit** — `git commit -m "chore(monitoring): dev 스택 편입 — dev-monitoring·전용 네트워크·dev CD 배선"`

---

### Task 13: 문서 정리

**Files:**
- Modify: `ARCHITECTURE.md` (§2 모듈 표 "신설 예정" 해제, §5 MON 트랙 상태 ⬜→✅, §7은 구현 완료 한 줄)
- Modify: `docs/contracts/monitoring-was-contract.md` (구현 확정 반영 — 뷰 컬럼 실명, 에러 어휘 동결, 상태 헤더 v0.1→v1.0)
- Modify: `CLAUDE.md` (빌드·검증 절에 `:monitoring:bootRun`(8083) 추가)
- Modify: `docs/superpowers/plans/2026-07-28-monitoring-module.md` (상태 헤더 → ✅ 실행 완료, `plans/archive/`로 이동)

- [ ] **Step 1: 각 문서 갱신** — Task 1 findings(엔드포인트·필드 매핑·열거 범위)와 Task 10 뷰 확정 내용을 계약 문서 §3에 반영. spec의 "열린 항목" 중 해소된 것(Hiker 매핑·열거 페이지 수·에러 어휘)을 결정 내용과 함께 각주 처리.
- [ ] **Step 2: 전체 회귀** — `./gradlew test` GREEN 확인 후 결과를 커밋 메시지에 명기.
- [ ] **Step 3: Commit** — `git commit -m "docs: monitoring 구현 완료 반영 — ARCHITECTURE 트랙 ✅·계약 v1.0·플랜 아카이브"`

---

## Self-Review 결과

- **스펙 커버리지**: 결정 1~12 전부 태스크에 대응(1·2→Task 2·4, 3→Task 6·7·10, 4·5→Task 2·10, 6→Task 8·9, 7→Task 7·8, 8→Task 7, 10→Task 5·8, 11→Task 6·11, 12→Task 12). 결정 9(이메일)는 was 몫 — 범위 밖 명시. 원형 롤링 삭제·종결 데이터 청소는 스펙에서도 "나중 결정"이라 미포함(YAGNI).
- **타입 일관성**: `TargetRow`/`CandidateRow`/`ProfileInfo`/`PostInfo`/`KeywordRule` 시그니처를 Interfaces 블록에 고정, 태스크 간 참조 일치 확인.
- **주의점**: Task 4·6·8의 픽스처 의존 단언은 Task 1 실측 결과에 따라 조정 여지가 있음을 해당 스텝에 명시(플레이스홀더 아님 — 조정 규칙 포함).
