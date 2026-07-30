# celfit crawler 구현 계획

> 상태: ✅ 실행됨
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카테고리 키워드로 인스타그램 콘텐츠를 발굴하고 업로드+3일 후 게시물 상세·댓글·프로필을 Apify 응답 원형(raw)으로 적재하는 Spring Boot 앱 + 관리 UI.

**Architecture:** 단일 Spring Boot 앱. 잡 3개(discover→qualify→aggregate)가 Apify 액터를 비동기(run 시작→폴링→dataset)로 실행하고, raw는 액터별 jsonb 테이블에 verbatim 적재(조회 편의는 generated column). 카테고리·키워드·규칙은 DB(UI 편집). 수동 트리거(REST/UI) 우선, 스케줄은 설정 off로 내장.

**Tech Stack:** Java 21 · Spring Boot **4.1.0** (주의: SB4는 `spring-boot-starter-webmvc` 네이밍) · Gradle 9.5.1 (wrapper는 `../backend`에서 복사) · PostgreSQL 17 (docker-compose 자동 기동) · Flyway · Thymeleaf+htmx · Testcontainers · Lombok.

**Spec:** `docs/superpowers/specs/2026-07-07-crawler-design.md`

**전제:** 작업 디렉터리 `C:\Users\woomin\Desktop\Project\celfit\crawler` (git repo 존재, docs만 커밋됨). Docker Desktop 실행 중이어야 통합 테스트·bootRun 가능. 모든 명령은 repo 루트에서 실행.

---

## 파일 구조 (전체 지도)

```
crawler/
├─ build.gradle / settings.gradle / gradlew(.bat) / gradle/wrapper/
├─ compose.yaml                      # Postgres (포트 5433 — 구 backend의 5432와 충돌 회피)
├─ .gitignore
├─ src/main/resources/
│  ├─ application.yml
│  ├─ db/migration/V1__init.sql     # 전체 스키마 (generated column 포함)
│  └─ templates/  layout.html, dashboard.html, jobs.html, categories.html,
│                 contents.html, content-detail.html, fragments/runs.html
└─ src/main/java/com/celfit/crawler/
   ├─ CrawlerApplication.java
   ├─ config/  CrawlerConfig.java, ApifyProperties.java, DiscoverProperties.java,
   │           AggregateProperties.java, ScheduleProperties.java
   ├─ domain/  (enum) ContentStatus, ContentType, ContentTypeFilter, JobName, TriggerType, RunStatus
   │           (entity) Category, CategoryKeyword, CollectionRule, Account, Content, CrawlRun,
   │                    RawDiscoveryPost, RawPostDetail, RawComment, RawProfile
   │           (repo)   *Repository (Spring Data JPA)
   ├─ apify/   Actors.java, ActorInputs.java, ShortCodes.java, ApifyHttp.java, JdkApifyHttp.java,
   │           Sleeper.java, ApifyException.java, ApifyResult.java, ApifyRunner.java, ApifyClient.java
   ├─ job/     DiscoveryItemParser.java, CrawlExecutor.java, DiscoverJob.java, QualifyJob.java,
   │           AggregateJob.java, JobLock.java, JobService.java, ScheduleRunner.java
   ├─ admin/   JobController.java, StatusService.java, AdminQueryController.java,
   │           CategoryService.java, CategoryAdminController.java
   └─ ui/      UiController.java, UiJobController.java, UiCategoryController.java
```

책임: `apify`=Apify HTTP·입력 생성(순수), `job`=파이프라인 로직(Apify는 `ApifyRunner` 인터페이스 뒤로 격리 — 테스트는 fake), `domain`=엔티티+리포지토리, `admin`=REST·공용 서비스, `ui`=Thymeleaf 컨트롤러(로직은 admin 서비스 재사용).

---

### Task 1: 프로젝트 스캐폴딩

**Files:**
- Create: `settings.gradle`, `build.gradle`, `.gitignore`, `compose.yaml`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/celfit/crawler/CrawlerApplication.java`
- Create: `src/test/java/com/celfit/crawler/SanityTest.java`
- Copy: `../backend/gradlew`, `../backend/gradlew.bat`, `../backend/gradle/` (wrapper)

- [ ] **Step 1: Gradle wrapper 복사**

```bash
cp ../backend/gradlew ../backend/gradlew.bat .
cp -r ../backend/gradle .
```

- [ ] **Step 2: 빌드 파일 작성**

`settings.gradle`:
```gradle
rootProject.name = 'celfit-crawler'
```

`build.gradle`:
```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.0'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.celfit'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
	implementation 'org.springframework.boot:spring-boot-starter-webmvc'
	implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
	implementation 'org.springframework.boot:spring-boot-starter-validation'
	implementation 'org.flywaydb:flyway-core'
	implementation 'org.flywaydb:flyway-database-postgresql'
	compileOnly 'org.projectlombok:lombok'
	annotationProcessor 'org.projectlombok:lombok'
	runtimeOnly 'org.postgresql:postgresql'
	developmentOnly 'org.springframework.boot:spring-boot-docker-compose'
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
	testImplementation 'org.springframework.boot:spring-boot-testcontainers'
	testImplementation 'org.testcontainers:postgresql'
	testImplementation 'org.testcontainers:junit-jupiter'
	testCompileOnly 'org.projectlombok:lombok'
	testAnnotationProcessor 'org.projectlombok:lombok'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

`.gitignore`:
```
build/
.gradle/
.env
.idea/
*.iml
.claude/
.worktrees/
```

`compose.yaml` (구 backend Postgres와 충돌하지 않게 호스트 포트 5433):
```yaml
services:
  postgres:
    image: 'postgres:17-alpine'
    environment:
      POSTGRES_DB: crawler
      POSTGRES_USER: crawler
      POSTGRES_PASSWORD: crawler
    ports:
      - '5433:5432'
    volumes:
      - celfit-crawler-pg:/var/lib/postgresql/data

volumes:
  celfit-crawler-pg:
```

- [ ] **Step 3: 앱 엔트리 + 설정 파일**

`src/main/java/com/celfit/crawler/CrawlerApplication.java`:
```java
package com.celfit.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrawlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrawlerApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: celfit-crawler
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  threads:
    virtual:
      enabled: true

crawler:
  apify:
    token: ${APIFY_TOKEN:}
    base-url: https://api.apify.com
    poll-interval: 5s
    run-timeout: 10m
  discover:
    results-limit: 100
  aggregate:
    delay-days: 3
    batch-limit: 200
    chunk-size: 50
    comments-per-post: 50
    max-attempts: 3
  schedule:
    enabled: false
    discover-cron: "0 0 6 * * *"
    qualify-cron: "0 30 6 * * *"
    aggregate-cron: "0 0 7 * * *"
```

- [ ] **Step 4: sanity 테스트 작성 후 빌드**

`src/test/java/com/celfit/crawler/SanityTest.java`:
```java
package com.celfit.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SanityTest {
    @Test
    void 빌드_동작() {
        assertEquals(2, 1 + 1);
    }
}
```

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (컴파일+테스트 통과. 이 시점엔 DB 불필요 — Spring 컨텍스트 테스트가 아직 없음)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "chore: Spring Boot 4.1 프로젝트 스캐폴딩 (gradle, compose, 설정)"
```

---

### Task 2: Flyway V1 스키마 + 통합 테스트 베이스

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/test/resources/application.yml`
- Create: `src/test/java/com/celfit/crawler/IntegrationTest.java`
- Test: `src/test/java/com/celfit/crawler/SchemaTest.java`

- [ ] **Step 1: 통합 테스트 베이스 작성 (Testcontainers 싱글턴)**

`src/test/java/com/celfit/crawler/IntegrationTest.java`:
```java
package com.celfit.crawler;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 통합 테스트 공통 베이스. Postgres 컨테이너 1개를 JVM 전체에서 공유(싱글턴 패턴). */
@SpringBootTest
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

`src/test/resources/application.yml` (주의 — 테스트 classpath의 application.yml은 main 것을 **가리므로** 필요한 설정 전부 포함):
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false

crawler:
  apify:
    token: test-token
    base-url: http://localhost:9999
    poll-interval: 1ms
    run-timeout: 1s
  discover:
    results-limit: 100
  aggregate:
    delay-days: 3
    batch-limit: 200
    chunk-size: 50
    comments-per-post: 50
    max-attempts: 3
  schedule:
    enabled: false
    discover-cron: "0 0 6 * * *"
    qualify-cron: "0 30 6 * * *"
    aggregate-cron: "0 0 7 * * *"
```

- [ ] **Step 2: 실패하는 스키마 테스트 작성**

`src/test/java/com/celfit/crawler/SchemaTest.java`:
```java
package com.celfit.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional  // 직접 insert가 롤백되도록 — 다른 테스트 클래스와 DB 공유(싱글턴 컨테이너)
class SchemaTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void 전체_테이블이_생성된다() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).contains(
                "category", "category_keyword", "collection_rule",
                "account", "content", "crawl_run",
                "raw_discovery_post", "raw_post_detail", "raw_comment", "raw_profile");
    }

    @Test
    void generated_column이_동작한다() {
        jdbc.update("insert into category(name) values ('테스트')");
        Long catId = jdbc.queryForObject("select id from category where name='테스트'", Long.class);
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, uploaded_at,
                                    category_id, discovery_keyword, status, first_seen_at)
                values ('abc123', 'REELS', 'tester', now(), ?, '테스트', 'PENDING', now())""", catId);
        Long contentId = jdbc.queryForObject("select id from content where short_code='abc123'", Long.class);
        jdbc.update("""
                insert into crawl_run(job, trigger_type, actor_id, status, started_at)
                values ('DISCOVER', 'MANUAL', 'a', 'RUNNING', now())""");
        Long runId = jdbc.queryForObject("select max(id) from crawl_run", Long.class);
        jdbc.update("""
                insert into raw_comment(content_id, crawl_run_id, payload, captured_at)
                values (?, ?, '{"ownerUsername":"kim","text":"좋아요!"}'::jsonb, now())""",
                contentId, runId);
        String writer = jdbc.queryForObject(
                "select writer from raw_comment where content_id = ?", String.class, contentId);
        assertThat(writer).isEqualTo("kim");
    }
}
```

Run: `./gradlew test --tests "com.celfit.crawler.SchemaTest"`
Expected: FAIL — Flyway 마이그레이션이 없어 테이블 부재 (`relation ... does not exist` 또는 컨텍스트 기동 실패)

- [ ] **Step 3: V1 마이그레이션 작성**

`src/main/resources/db/migration/V1__init.sql`:
```sql
-- ===== 규칙 (UI 편집) =====
CREATE TABLE category (
    id      bigserial PRIMARY KEY,
    name    text      NOT NULL UNIQUE,
    enabled boolean   NOT NULL DEFAULT true
);

CREATE TABLE category_keyword (
    id          bigserial PRIMARY KEY,
    category_id bigint    NOT NULL REFERENCES category(id),
    keyword     text      NOT NULL,
    enabled     boolean   NOT NULL DEFAULT true,
    UNIQUE (category_id, keyword)
);

CREATE TABLE collection_rule (
    id            bigserial PRIMARY KEY,
    category_id   bigint    NOT NULL UNIQUE REFERENCES category(id),
    min_followers integer,
    max_followers integer,
    content_types text      NOT NULL DEFAULT 'ALL'
);

-- ===== 제어 인덱스 =====
CREATE TABLE account (
    id               bigserial   PRIMARY KEY,
    username         text        NOT NULL UNIQUE,
    last_profiled_at timestamptz
);

CREATE TABLE content (
    id                 bigserial   PRIMARY KEY,
    short_code         text        NOT NULL UNIQUE,
    content_type       text        NOT NULL,
    owner_username     text        NOT NULL,
    uploaded_at        timestamptz NOT NULL,
    category_id        bigint      NOT NULL REFERENCES category(id),
    discovery_keyword  text        NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING',
    first_seen_at      timestamptz NOT NULL,
    qualified_at       timestamptz,
    aggregated_at      timestamptz,
    aggregate_attempts integer     NOT NULL DEFAULT 0
);
CREATE INDEX idx_content_status ON content(status);
CREATE INDEX idx_content_uploaded_at ON content(uploaded_at);

CREATE TABLE crawl_run (
    id            bigserial   PRIMARY KEY,
    job           text        NOT NULL,
    trigger_type  text        NOT NULL,
    category_id   bigint      REFERENCES category(id),
    keyword       text,
    actor_id      text        NOT NULL,
    apify_run_id  text,
    status        text        NOT NULL,
    item_count    integer,
    error_message text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz
);

-- ===== raw (Apify 응답 verbatim, 액터별 테이블) =====
-- generated column은 조회 편의용 파생값. Apify 필드명이 바뀌면 여기만 마이그레이션.
-- 주의: ::bigint 캐스트는 값이 숫자 문자열이 아니면 insert가 실패한다 —
--       스모크 테스트에서 실제 응답 필드명·형식 확인 후 필요 시 V2로 조정.
CREATE TABLE raw_discovery_post (
    id           bigserial   PRIMARY KEY,
    content_id   bigint      NOT NULL REFERENCES content(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    short_code   text GENERATED ALWAYS AS (payload->>'shortCode') STORED,
    caption      text GENERATED ALWAYS AS (payload->>'caption') STORED
);
CREATE INDEX idx_raw_discovery_post_content ON raw_discovery_post(content_id);

CREATE TABLE raw_post_detail (
    id               bigserial   PRIMARY KEY,
    content_id       bigint      NOT NULL REFERENCES content(id),
    crawl_run_id     bigint      NOT NULL REFERENCES crawl_run(id),
    payload          jsonb       NOT NULL,
    captured_at      timestamptz NOT NULL,
    short_code       text   GENERATED ALWAYS AS (payload->>'shortCode') STORED,
    caption          text   GENERATED ALWAYS AS (payload->>'caption') STORED,
    likes            bigint GENERATED ALWAYS AS ((payload->>'likesCount')::bigint) STORED,
    comments_count   bigint GENERATED ALWAYS AS ((payload->>'commentsCount')::bigint) STORED,
    video_play_count bigint GENERATED ALWAYS AS ((payload->>'videoPlayCount')::bigint) STORED
);
CREATE INDEX idx_raw_post_detail_content ON raw_post_detail(content_id);

CREATE TABLE raw_comment (
    id           bigserial   PRIMARY KEY,
    content_id   bigint      NOT NULL REFERENCES content(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    writer       text GENERATED ALWAYS AS (payload->>'ownerUsername') STORED,
    text         text GENERATED ALWAYS AS (payload->>'text') STORED,
    written_at   text GENERATED ALWAYS AS (payload->>'timestamp') STORED
);
CREATE INDEX idx_raw_comment_content ON raw_comment(content_id);

CREATE TABLE raw_profile (
    id           bigserial   PRIMARY KEY,
    account_id   bigint      NOT NULL REFERENCES account(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    username     text   GENERATED ALWAYS AS (payload->>'username') STORED,
    followers    bigint GENERATED ALWAYS AS ((payload->>'followersCount')::bigint) STORED
);
CREATE INDEX idx_raw_profile_account ON raw_profile(account_id);
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.celfit.crawler.SchemaTest"`
Expected: PASS (2 tests) — Docker Desktop이 꺼져 있으면 컨테이너 기동 실패하니 먼저 켤 것

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: V1 스키마 (제어·규칙·raw 테이블, generated column) + Testcontainers 베이스"
```

---

### Task 3: 제어·규칙 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/celfit/crawler/domain/` — `ContentStatus.java`, `ContentType.java`, `ContentTypeFilter.java`, `JobName.java`, `TriggerType.java`, `RunStatus.java`, `Category.java`, `CategoryKeyword.java`, `CollectionRule.java`, `Account.java`, `Content.java`, `CrawlRun.java`, `CategoryRepository.java`, `CategoryKeywordRepository.java`, `CollectionRuleRepository.java`, `AccountRepository.java`, `ContentRepository.java`, `CrawlRunRepository.java`
- Test: `src/test/java/com/celfit/crawler/domain/ContentRepositoryTest.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

`src/test/java/com/celfit/crawler/domain/ContentRepositoryTest.java`:
```java
package com.celfit.crawler.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ContentRepositoryTest extends IntegrationTest {

    @Autowired ContentRepository contents;
    @Autowired CategoryRepository categories;

    static final Instant CUTOFF = Instant.parse("2026-07-04T00:00:00Z");

    Long catId;

    Content save(String shortCode, ContentStatus status, Instant uploadedAt) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        Content c = new Content(shortCode, ContentType.REELS, "user_" + shortCode,
                uploadedAt, catId, "메이크업", Instant.parse("2026-07-01T00:00:00Z"));
        c.setStatus(status);
        return contents.save(c);
    }

    @Test
    void shortCode로_조회된다() {
        save("sc1", ContentStatus.PENDING, CUTOFF);
        assertThat(contents.findByShortCode("sc1")).isPresent();
        assertThat(contents.findByShortCode("없음")).isEmpty();
    }

    @Test
    void findDue는_QUALIFIED이고_미집계이고_컷오프_이전_업로드만_고른다() {
        save("due1", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(3600));  // 대상
        save("due2", ContentStatus.QUALIFIED, CUTOFF);                      // 경계 = 대상 (<=)
        save("fresh", ContentStatus.QUALIFIED, CUTOFF.plusSeconds(3600));   // 아직 3일 안 됨
        save("pend", ContentStatus.PENDING, CUTOFF.minusSeconds(3600));     // 미판정
        Content done = save("done", ContentStatus.AGGREGATED, CUTOFF.minusSeconds(3600));
        done.setAggregatedAt(Instant.now());

        var due = contents.findDue(ContentStatus.QUALIFIED, CUTOFF, PageRequest.of(0, 10));
        assertThat(due).extracting(Content::getShortCode).containsExactly("due1", "due2");
    }

    @Test
    void findDue는_배치_상한을_지킨다() {
        save("a", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(30));
        save("b", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(20));
        save("c", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(10));
        var due = contents.findDue(ContentStatus.QUALIFIED, CUTOFF, PageRequest.of(0, 2));
        assertThat(due).hasSize(2);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.domain.ContentRepositoryTest"`
Expected: FAIL — 컴파일 에러 (엔티티·리포지토리 미존재)

- [ ] **Step 3: enum 6종 작성**

`domain/ContentStatus.java`:
```java
package com.celfit.crawler.domain;

public enum ContentStatus { PENDING, QUALIFIED, EXCLUDED, AGGREGATED, GONE, FAILED }
```

`domain/ContentType.java`:
```java
package com.celfit.crawler.domain;

public enum ContentType { REELS, FEED }
```

`domain/ContentTypeFilter.java`:
```java
package com.celfit.crawler.domain;

public enum ContentTypeFilter {
    ALL, REELS, FEED;

    public boolean allows(ContentType type) {
        return this == ALL || name().equals(type.name());
    }
}
```

`domain/JobName.java`:
```java
package com.celfit.crawler.domain;

public enum JobName { DISCOVER, QUALIFY, AGGREGATE }
```

`domain/TriggerType.java`:
```java
package com.celfit.crawler.domain;

public enum TriggerType { MANUAL, SCHEDULED }
```

`domain/RunStatus.java`:
```java
package com.celfit.crawler.domain;

public enum RunStatus { RUNNING, SUCCEEDED, FAILED }
```

- [ ] **Step 4: 엔티티 작성**

`domain/Category.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "category")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    public Category(String name) {
        this.name = name;
    }
}
```

`domain/CategoryKeyword.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "category_keyword")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryKeyword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private String keyword;

    @Column(nullable = false)
    private boolean enabled = true;

    public CategoryKeyword(Long categoryId, String keyword) {
        this.categoryId = categoryId;
        this.keyword = keyword;
    }
}
```

`domain/CollectionRule.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "collection_rule")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false, unique = true)
    private Long categoryId;

    @Column(name = "min_followers")
    private Integer minFollowers;

    @Column(name = "max_followers")
    private Integer maxFollowers;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_types", nullable = false)
    private ContentTypeFilter contentTypes = ContentTypeFilter.ALL;

    public CollectionRule(Long categoryId) {
        this.categoryId = categoryId;
    }

    /** 팔로워 조건이 하나라도 있으면 프로필 데이터 없이는 판정 불가. */
    public boolean needsFollowers() {
        return minFollowers != null || maxFollowers != null;
    }

    public boolean followersPass(long followers) {
        return (minFollowers == null || followers >= minFollowers)
                && (maxFollowers == null || followers <= maxFollowers);
    }
}
```

`domain/Account.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "account")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "last_profiled_at")
    private Instant lastProfiledAt;

    public Account(String username) {
        this.username = username;
    }
}
```

`domain/Content.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "content")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Content {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "discovery_keyword", nullable = false)
    private String discoveryKeyword;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status = ContentStatus.PENDING;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "qualified_at")
    private Instant qualifiedAt;

    @Column(name = "aggregated_at")
    private Instant aggregatedAt;

    @Column(name = "aggregate_attempts", nullable = false)
    private int aggregateAttempts;

    public Content(String shortCode, ContentType contentType, String ownerUsername,
                   Instant uploadedAt, Long categoryId, String discoveryKeyword, Instant firstSeenAt) {
        this.shortCode = shortCode;
        this.contentType = contentType;
        this.ownerUsername = ownerUsername;
        this.uploadedAt = uploadedAt;
        this.categoryId = categoryId;
        this.discoveryKeyword = discoveryKeyword;
        this.firstSeenAt = firstSeenAt;
    }
}
```

`domain/CrawlRun.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "crawl_run")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrawlRun {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobName job;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Column(name = "category_id")
    private Long categoryId;

    private String keyword;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "apify_run_id")
    private String apifyRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.RUNNING;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public CrawlRun(JobName job, TriggerType triggerType, Long categoryId,
                    String keyword, String actorId, Instant startedAt) {
        this.job = job;
        this.triggerType = triggerType;
        this.categoryId = categoryId;
        this.keyword = keyword;
        this.actorId = actorId;
        this.startedAt = startedAt;
    }

    public void finishOk(String apifyRunId, int itemCount, Instant at) {
        this.apifyRunId = apifyRunId;
        this.status = RunStatus.SUCCEEDED;
        this.itemCount = itemCount;
        this.finishedAt = at;
    }

    public void finishFailed(String error, Instant at) {
        this.status = RunStatus.FAILED;
        this.errorMessage = error;
        this.finishedAt = at;
    }
}
```

- [ ] **Step 5: 리포지토리 작성**

`domain/CategoryRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
    List<Category> findByEnabledTrue();
}
```

`domain/CategoryKeywordRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryKeywordRepository extends JpaRepository<CategoryKeyword, Long> {
    List<CategoryKeyword> findByCategoryIdAndEnabledTrue(Long categoryId);
    List<CategoryKeyword> findByCategoryId(Long categoryId);
    boolean existsByCategoryIdAndKeyword(Long categoryId, String keyword);
}
```

`domain/CollectionRuleRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRuleRepository extends JpaRepository<CollectionRule, Long> {
    Optional<CollectionRule> findByCategoryId(Long categoryId);
}
```

`domain/AccountRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByUsername(String username);
    List<Account> findByUsernameInAndLastProfiledAtIsNull(Collection<String> usernames);
}
```

`domain/ContentRepository.java`:
```java
package com.celfit.crawler.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByShortCode(String shortCode);

    List<Content> findByStatus(ContentStatus status);

    Page<Content> findByStatus(ContentStatus status, Pageable pageable);

    /** aggregate 대상: 판정 통과 + 미집계 + 업로드가 컷오프(now-3일) 이전(경계 포함). */
    @Query("""
            select c from Content c
            where c.status = :status and c.aggregatedAt is null and c.uploadedAt <= :cutoff
            order by c.uploadedAt asc""")
    List<Content> findDue(@Param("status") ContentStatus status,
                          @Param("cutoff") Instant cutoff,
                          Pageable pageable);

    long countByStatus(ContentStatus status);

    long countByStatusAndAggregatedAtIsNullAndUploadedAtLessThanEqual(ContentStatus status, Instant cutoff);
}
```

`domain/CrawlRunRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {
    List<CrawlRun> findTop50ByOrderByIdDesc();
}
```

- [ ] **Step 6: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.domain.ContentRepositoryTest"`
Expected: PASS (3 tests)

```bash
git add -A && git commit -m "feat: 제어·규칙 도메인 엔티티 + 리포지토리 (findDue 포함)"
```

---

### Task 4: raw 엔티티 (jsonb + generated column 읽기)

**Files:**
- Create: `src/main/java/com/celfit/crawler/domain/` — `RawDiscoveryPost.java`, `RawPostDetail.java`, `RawComment.java`, `RawProfile.java`, `RawDiscoveryPostRepository.java`, `RawPostDetailRepository.java`, `RawCommentRepository.java`, `RawProfileRepository.java`
- Test: `src/test/java/com/celfit/crawler/domain/RawEntityTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/domain/RawEntityTest.java`:
```java
package com.celfit.crawler.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RawEntityTest extends IntegrationTest {

    @Autowired RawCommentRepository rawComments;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired CrawlRunRepository runs;
    @Autowired EntityManager em;

    @Test
    void payload가_jsonb로_왕복되고_generated_column이_읽힌다() {
        Long catId = categories.save(new Category("메이크업")).getId();
        Content content = contents.save(new Content("sc1", ContentType.REELS, "kim",
                Instant.parse("2026-07-01T00:00:00Z"), catId, "메이크업", Instant.now()));
        CrawlRun run = runs.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));

        Map<String, Object> payload = Map.of(
                "ownerUsername", "kim",
                "text", "너무 예뻐요",
                "timestamp", "2026-07-02T10:00:00.000Z",
                "likesCount", 7,
                "replies", List.of(Map.of("text", "감사합니다")));
        RawComment saved = rawComments.save(
                new RawComment(content.getId(), run.getId(), payload, Instant.now()));
        em.flush();
        em.refresh(saved);  // DB가 계산한 generated column 읽기

        assertThat(saved.getPayload().get("text")).isEqualTo("너무 예뻐요");
        assertThat(saved.getPayload().get("replies")).isEqualTo(List.of(Map.of("text", "감사합니다")));
        assertThat(saved.getWriter()).isEqualTo("kim");
        assertThat(saved.getText()).isEqualTo("너무 예뻐요");
    }

    @Test
    void raw_profile의_followers_generated_column() {
        Account acct = accounts.save(new Account("kim"));
        CrawlRun run = runs.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));
        RawProfile saved = rawProfiles.save(new RawProfile(acct.getId(), run.getId(),
                Map.of("username", "kim", "followersCount", 123456), Instant.now()));
        em.flush();
        em.refresh(saved);

        assertThat(saved.getFollowers()).isEqualTo(123456L);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.domain.RawEntityTest"`
Expected: FAIL — 컴파일 에러 (raw 엔티티 미존재)

- [ ] **Step 3: raw 엔티티 4종 + 리포지토리 작성**

`domain/RawComment.java` (다른 3개도 동일 골격):
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawComment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    // generated column — 읽기 전용
    @Column(insertable = false, updatable = false)
    private String writer;

    @Column(insertable = false, updatable = false)
    private String text;

    @Column(name = "written_at", insertable = false, updatable = false)
    private String writtenAt;

    public RawComment(Long contentId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
```

`domain/RawDiscoveryPost.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_discovery_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawDiscoveryPost {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "short_code", insertable = false, updatable = false)
    private String shortCode;

    @Column(insertable = false, updatable = false)
    private String caption;

    public RawDiscoveryPost(Long contentId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
```

`domain/RawPostDetail.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_post_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawPostDetail {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "short_code", insertable = false, updatable = false)
    private String shortCode;

    @Column(insertable = false, updatable = false)
    private String caption;

    @Column(insertable = false, updatable = false)
    private Long likes;

    @Column(name = "comments_count", insertable = false, updatable = false)
    private Long commentsCount;

    @Column(name = "video_play_count", insertable = false, updatable = false)
    private Long videoPlayCount;

    public RawPostDetail(Long contentId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
```

`domain/RawProfile.java`:
```java
package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(insertable = false, updatable = false)
    private String username;

    @Column(insertable = false, updatable = false)
    private Long followers;

    public RawProfile(Long accountId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.accountId = accountId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
```

리포지토리 4개:

`domain/RawDiscoveryPostRepository.java`:
```java
package com.celfit.crawler.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RawDiscoveryPostRepository extends JpaRepository<RawDiscoveryPost, Long> {
}
```

`domain/RawPostDetailRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawPostDetailRepository extends JpaRepository<RawPostDetail, Long> {
    Optional<RawPostDetail> findTopByContentIdOrderByCapturedAtDesc(Long contentId);
}
```

`domain/RawCommentRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawCommentRepository extends JpaRepository<RawComment, Long> {
    List<RawComment> findTop100ByContentIdOrderByIdDesc(Long contentId);
}
```

`domain/RawProfileRepository.java`:
```java
package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {
    Optional<RawProfile> findTopByAccountIdOrderByCapturedAtDesc(Long accountId);
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.domain.RawEntityTest"`
Expected: PASS (2 tests)

```bash
git add -A && git commit -m "feat: 액터별 raw 엔티티 (jsonb payload + generated column 읽기)"
```

---

### Task 5: ActorInputs · ShortCodes · DiscoveryItemParser (순수 로직)

**Files:**
- Create: `src/main/java/com/celfit/crawler/apify/Actors.java`, `.../ActorInputs.java`, `.../ShortCodes.java`
- Create: `src/main/java/com/celfit/crawler/job/DiscoveryItemParser.java`
- Test: `src/test/java/com/celfit/crawler/apify/ActorInputsTest.java`, `src/test/java/com/celfit/crawler/job/DiscoveryItemParserTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/apify/ActorInputsTest.java`:
```java
package com.celfit.crawler.apify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorInputsTest {

    @Test
    void 한글_키워드는_keywordSearch가_켜진다() {
        assertThat(ActorInputs.needsKeywordSearch("메이크업")).isTrue();
        assertThat(ActorInputs.needsKeywordSearch("makeup")).isFalse();
    }

    @Test
    void discovery_입력() {
        Map<String, Object> input = ActorInputs.discovery("메이크업", 100);
        assertThat(input)
                .containsEntry("hashtags", List.of("메이크업"))
                .containsEntry("resultsType", "posts")
                .containsEntry("resultsLimit", 100)
                .containsEntry("keywordSearch", true);
    }

    @Test
    void postDetail_comments_profiles_입력() {
        List<String> urls = List.of("https://www.instagram.com/p/abc/");
        assertThat(ActorInputs.postDetail(urls))
                .containsEntry("directUrls", urls)
                .containsEntry("resultsType", "details");
        assertThat(ActorInputs.comments(urls, 50))
                .containsEntry("directUrls", urls)
                .containsEntry("resultsLimit", 50);
        assertThat(ActorInputs.profiles(List.of("kim")))
                .containsEntry("usernames", List.of("kim"));
    }

    @Test
    void chunk는_n개_단위로_쪼갠다() {
        assertThat(ActorInputs.chunk(List.of(1, 2, 3, 4, 5), 2))
                .containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
        assertThat(ActorInputs.chunk(List.of(), 2)).isEmpty();
    }

    @Test
    void shortCode_url_왕복() {
        assertThat(ShortCodes.postUrl("abc_-1")).isEqualTo("https://www.instagram.com/p/abc_-1/");
        assertThat(ShortCodes.fromUrl("https://www.instagram.com/p/abc_-1/")).contains("abc_-1");
        assertThat(ShortCodes.fromUrl("https://www.instagram.com/reel/xyz9/?hl=ko")).contains("xyz9");
        assertThat(ShortCodes.fromUrl("https://example.com/nope")).isEmpty();
    }
}
```

`src/test/java/com/celfit/crawler/job/DiscoveryItemParserTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.domain.ContentType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryItemParserTest {

    @Test
    void 정상_아이템_파싱_clips는_REELS() {
        Map<String, Object> item = Map.of(
                "shortCode", "abc123",
                "productType", "clips",
                "timestamp", "2026-07-01T12:00:00.000Z",
                "ownerUsername", "kim");
        var parsed = DiscoveryItemParser.parse(item).orElseThrow();
        assertThat(parsed.shortCode()).isEqualTo("abc123");
        assertThat(parsed.type()).isEqualTo(ContentType.REELS);
        assertThat(parsed.uploadedAt()).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));
        assertThat(parsed.ownerUsername()).isEqualTo("kim");
    }

    @Test
    void productType_clips가_아니면_FEED() {
        Map<String, Object> item = Map.of(
                "shortCode", "abc", "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", "kim");
        assertThat(DiscoveryItemParser.parse(item).orElseThrow().type()).isEqualTo(ContentType.FEED);
    }

    @Test
    void 필수_필드가_없거나_timestamp가_깨지면_empty() {
        assertThat(DiscoveryItemParser.parse(Map.of("shortCode", "a", "ownerUsername", "k"))).isEmpty();
        assertThat(DiscoveryItemParser.parse(Map.of("timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", "k"))).isEmpty();
        assertThat(DiscoveryItemParser.parse(Map.of("shortCode", "a", "timestamp", "언제더라", "ownerUsername", "k"))).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.apify.ActorInputsTest" --tests "com.celfit.crawler.job.DiscoveryItemParserTest"`
Expected: FAIL — 컴파일 에러

- [ ] **Step 3: 구현**

`apify/Actors.java` (액터 id·입력 형식은 스모크 테스트 때 실계정으로 검증 — 틀리면 여기만 수정):
```java
package com.celfit.crawler.apify;

/** 사용하는 Apify 액터 id. */
public final class Actors {
    public static final String DISCOVERY = "apify~instagram-hashtag-scraper";
    public static final String POST_DETAIL = "apify~instagram-scraper";
    public static final String COMMENT = "apify~instagram-comment-scraper";
    public static final String PROFILE = "apify~instagram-profile-scraper";

    private Actors() {}
}
```

`apify/ActorInputs.java`:
```java
package com.celfit.crawler.apify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 액터 입력 생성 순수 헬퍼. */
public final class ActorInputs {

    /**
     * 인스타 비로그인 해시태그 페이지는 한글(비ASCII) 태그를 차단해 hashtag 모드가
     * 빈 결과를 반환한다 → 액터의 키워드 검색 모드로 우회.
     */
    public static boolean needsKeywordSearch(String keyword) {
        return keyword != null && keyword.chars().anyMatch(c -> c > 0x7f);
    }

    public static Map<String, Object> discovery(String keyword, int resultsLimit) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hashtags", List.of(keyword));
        input.put("resultsType", "posts");
        input.put("resultsLimit", resultsLimit);
        input.put("keywordSearch", needsKeywordSearch(keyword));
        return input;
    }

    public static Map<String, Object> postDetail(List<String> postUrls) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("directUrls", postUrls);
        input.put("resultsType", "details");
        return input;
    }

    public static Map<String, Object> comments(List<String> postUrls, int perPost) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("directUrls", postUrls);
        input.put("resultsLimit", perPost);
        return input;
    }

    public static Map<String, Object> profiles(List<String> usernames) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("usernames", usernames);
        return input;
    }

    public static <T> List<List<T>> chunk(List<T> list, int n) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += n) {
            out.add(List.copyOf(list.subList(i, Math.min(i + n, list.size()))));
        }
        return out;
    }

    private ActorInputs() {}
}
```

`apify/ShortCodes.java`:
```java
package com.celfit.crawler.apify;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShortCodes {

    private static final Pattern URL = Pattern.compile("instagram\\.com/(?:p|reel)/([A-Za-z0-9_-]+)");

    public static String postUrl(String shortCode) {
        return "https://www.instagram.com/p/" + shortCode + "/";
    }

    public static Optional<String> fromUrl(String url) {
        if (url == null) return Optional.empty();
        Matcher m = URL.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private ShortCodes() {}
}
```

`job/DiscoveryItemParser.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.domain.ContentType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/** 발굴 액터 아이템에서 제어 인덱스 필드만 추출. raw payload는 손대지 않는다. */
public final class DiscoveryItemParser {

    public record DiscoveredItem(String shortCode, ContentType type, Instant uploadedAt,
                                 String ownerUsername, Map<String, Object> payload) {}

    public static Optional<DiscoveredItem> parse(Map<String, Object> item) {
        String shortCode = str(item, "shortCode");
        String timestamp = str(item, "timestamp");
        String owner = str(item, "ownerUsername");
        if (shortCode == null || timestamp == null || owner == null) return Optional.empty();
        Instant uploadedAt;
        try {
            uploadedAt = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        ContentType type = "clips".equals(str(item, "productType")) ? ContentType.REELS : ContentType.FEED;
        return Optional.of(new DiscoveredItem(shortCode, type, uploadedAt, owner, item));
    }

    private static String str(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private DiscoveryItemParser() {}
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.apify.ActorInputsTest" --tests "com.celfit.crawler.job.DiscoveryItemParserTest"`
Expected: PASS (8 tests)

```bash
git add -A && git commit -m "feat: 액터 입력 생성·shortcode 헬퍼·발굴 아이템 파서"
```

---

### Task 6: ApifyClient (비동기 run→폴링→dataset, abort)

**Files:**
- Create: `src/main/java/com/celfit/crawler/config/ApifyProperties.java`
- Create: `src/main/java/com/celfit/crawler/apify/` — `ApifyException.java`, `ApifyHttp.java`, `Sleeper.java`, `ApifyResult.java`, `ApifyRunner.java`, `ApifyClient.java`, `JdkApifyHttp.java`
- Create: `src/main/java/com/celfit/crawler/config/CrawlerConfig.java`
- Test: `src/test/java/com/celfit/crawler/apify/ApifyClientTest.java`

- [ ] **Step 1: 인터페이스·예외·프로퍼티 작성** (테스트가 이 타입들에 의존하므로 먼저)

`config/ApifyProperties.java`:
```java
package com.celfit.crawler.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.apify")
public record ApifyProperties(String token, String baseUrl, Duration pollInterval, Duration runTimeout) {}
```

`apify/ApifyException.java`:
```java
package com.celfit.crawler.apify;

public class ApifyException extends RuntimeException {
    public ApifyException(String message) {
        super(message);
    }

    public ApifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`apify/ApifyHttp.java`:
```java
package com.celfit.crawler.apify;

/** Apify HTTP 전송 격리 — 테스트에서 fake로 대체. url은 토큰 포함 완성형. */
public interface ApifyHttp {
    String get(String url);
    String post(String url, String jsonBody);
}
```

`apify/Sleeper.java`:
```java
package com.celfit.crawler.apify;

import java.time.Duration;

public interface Sleeper {
    void sleep(Duration duration);
}
```

`apify/ApifyResult.java`:
```java
package com.celfit.crawler.apify;

import java.util.List;
import java.util.Map;

public record ApifyResult(String runId, List<Map<String, Object>> items) {}
```

`apify/ApifyRunner.java`:
```java
package com.celfit.crawler.apify;

import java.util.Map;

/** 액터 실행 추상화 — 잡은 이 인터페이스만 의존한다(테스트는 fake). */
public interface ApifyRunner {
    ApifyResult run(String actorId, Map<String, Object> input);
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/apify/ApifyClientTest.java`:
```java
package com.celfit.crawler.apify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.config.ApifyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApifyClientTest {

    /** 스크립트된 응답을 순서대로 돌려주고 호출 url을 기록하는 fake. */
    static class FakeHttp implements ApifyHttp {
        final Deque<String> responses = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        @Override public String get(String url) {
            calls.add("GET " + url);
            return responses.pop();
        }

        @Override public String post(String url, String body) {
            calls.add("POST " + url);
            return responses.pop();
        }
    }

    /** sleep할 때마다 가짜 시계를 전진시킨다. */
    static class TickingClock extends Clock {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    static final String STARTED = """
            {"data": {"id": "run-1", "defaultDatasetId": "ds-1", "status": "READY"}}""";

    ApifyClient client(FakeHttp http, TickingClock clock, Duration timeout) {
        ApifyProperties props = new ApifyProperties(
                "tok", "https://api.test", Duration.ofSeconds(5), timeout);
        Sleeper advancing = d -> clock.now = clock.now.plus(d);
        return new ApifyClient(http, props, new ObjectMapper(), advancing, clock);
    }

    @Test
    void 폴링_후_SUCCEEDED면_dataset_아이템을_반환한다() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "SUCCEEDED"}}""");
        http.responses.add("""
                [{"shortCode": "abc"}, {"shortCode": "def"}]""");

        ApifyResult result = client(http, new TickingClock(), Duration.ofMinutes(10))
                .run("apify~instagram-hashtag-scraper", java.util.Map.of("k", "v"));

        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).containsEntry("shortCode", "abc");
        assertThat(http.calls.get(0)).startsWith("POST https://api.test/v2/acts/apify~instagram-hashtag-scraper/runs");
        assertThat(http.calls.get(3)).contains("/v2/datasets/ds-1/items");
    }

    @Test
    void run이_FAILED면_예외() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "FAILED"}}""");

        assertThatThrownBy(() -> client(http, new TickingClock(), Duration.ofMinutes(10))
                .run("actor", java.util.Map.of()))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void 타임아웃되면_abort_호출_후_예외() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "ABORTING"}}""");  // abort 응답
        // 타임라인: t=0 poll(RUNNING)→sleep→t=5 poll(RUNNING)→deadline(4s) 초과→abort
        assertThatThrownBy(() -> client(http, new TickingClock(), Duration.ofSeconds(4))
                .run("actor", java.util.Map.of()))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("타임아웃");
        assertThat(http.calls).anyMatch(c -> c.startsWith("POST") && c.contains("/abort"));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.apify.ApifyClientTest"`
Expected: FAIL — 컴파일 에러 (ApifyClient 미존재)

- [ ] **Step 4: ApifyClient 구현**

`apify/ApifyClient.java`:
```java
package com.celfit.crawler.apify;

import com.celfit.crawler.config.ApifyProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Apify 비동기 실행: run 시작 → 상태 폴링 → SUCCEEDED면 dataset 수신.
 * run-sync 엔드포인트는 장시간 실행에서 게이트웨이가 먼저 끊겨 과금+결과 유실 → 금지.
 */
@Component
public class ApifyClient implements ApifyRunner {

    private static final Set<String> TERMINAL_FAILURES = Set.of("FAILED", "ABORTED", "TIMED-OUT");

    private final ApifyHttp http;
    private final ApifyProperties props;
    private final ObjectMapper om;
    private final Sleeper sleeper;
    private final Clock clock;

    public ApifyClient(ApifyHttp http, ApifyProperties props, ObjectMapper om,
                       Sleeper sleeper, Clock clock) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    @Override
    public ApifyResult run(String actorId, Map<String, Object> input) {
        JsonNode started = postJson(url("/v2/acts/" + actorId + "/runs"), input);
        String runId = started.path("data").path("id").asText();
        String datasetId = started.path("data").path("defaultDatasetId").asText();
        if (runId.isEmpty() || datasetId.isEmpty()) {
            throw new ApifyException("run 시작 응답에 id/datasetId 없음: " + started);
        }

        Instant deadline = clock.instant().plus(props.runTimeout());
        while (true) {
            String status = getJson(url("/v2/actor-runs/" + runId))
                    .path("data").path("status").asText();
            if ("SUCCEEDED".equals(status)) break;
            if (TERMINAL_FAILURES.contains(status)) {
                throw new ApifyException("run " + runId + " 종료 상태 " + status);
            }
            if (clock.instant().isAfter(deadline)) {
                postJson(url("/v2/actor-runs/" + runId + "/abort"), Map.of());
                throw new ApifyException("run " + runId + " 타임아웃(" + props.runTimeout() + ") — abort 요청함");
            }
            sleeper.sleep(props.pollInterval());
        }

        String body = http.get(url("/v2/datasets/" + datasetId + "/items") + "&clean=true&format=json");
        try {
            List<Map<String, Object>> items = om.readValue(body, new TypeReference<>() {});
            return new ApifyResult(runId, items);
        } catch (Exception e) {
            throw new ApifyException("dataset 파싱 실패: " + e.getMessage(), e);
        }
    }

    private String url(String path) {
        return props.baseUrl() + path + "?token=" + props.token();
    }

    private JsonNode getJson(String url) {
        return parse(http.get(url));
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        try {
            return parse(http.post(url, om.writeValueAsString(body)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApifyException("입력 직렬화 실패", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new ApifyException("응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
```

`apify/JdkApifyHttp.java`:
```java
package com.celfit.crawler.apify;

import com.celfit.crawler.config.ApifyProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class JdkApifyHttp implements ApifyHttp {

    private final HttpClient client = HttpClient.newHttpClient();

    public JdkApifyHttp(ApifyProperties props) {
        if (props.token() == null || props.token().isBlank()) {
            throw new IllegalStateException("APIFY_TOKEN이 설정되지 않았습니다 (환경변수 필요)");
        }
    }

    @Override
    public String get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
    }

    @Override
    public String post(String url, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new ApifyException("Apify HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new ApifyException("Apify 요청 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Apify 요청 중단", e);
        }
    }
}
```

`config/CrawlerConfig.java`:
```java
package com.celfit.crawler.config;

import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
@EnableConfigurationProperties({ApifyProperties.class, DiscoverProperties.class,
        AggregateProperties.class, ScheduleProperties.class})
public class CrawlerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Sleeper sleeper() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApifyException("폴링 대기 중단", e);
            }
        };
    }

    /** 잡 비동기 실행용 — 테스트는 SyncTaskExecutor로 대체해 결정적으로 만든다. */
    @Bean
    AsyncTaskExecutor jobTaskExecutor() {
        return new SimpleAsyncTaskExecutor("job-");
    }
}
```

`config/DiscoverProperties.java`, `config/AggregateProperties.java`, `config/ScheduleProperties.java` (Task 8·10·12에서 쓰지만 CrawlerConfig가 참조하므로 지금 생성):
```java
package com.celfit.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.discover")
public record DiscoverProperties(int resultsLimit) {}
```

```java
package com.celfit.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.aggregate")
public record AggregateProperties(int delayDays, int batchLimit, int chunkSize,
                                  int commentsPerPost, int maxAttempts) {}
```

```java
package com.celfit.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.schedule")
public record ScheduleProperties(boolean enabled, String discoverCron,
                                 String qualifyCron, String aggregateCron) {}
```

- [ ] **Step 5: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.apify.ApifyClientTest"`
Expected: PASS (3 tests)

Run: `./gradlew test` (전체 회귀 — JdkApifyHttp가 test-token으로 기동되는지 확인)
Expected: PASS

```bash
git add -A && git commit -m "feat: ApifyClient 비동기 실행 (run→폴링→dataset, 타임아웃 abort)"
```

---

### Task 7: CrawlExecutor (crawl_run 기록) + FakeApifyRunner

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/CrawlExecutor.java`
- Create: `src/test/java/com/celfit/crawler/FakeApifyRunner.java` (이후 잡 테스트 전부가 공유)
- Test: `src/test/java/com/celfit/crawler/job/CrawlExecutorTest.java`

- [ ] **Step 1: FakeApifyRunner 작성**

`src/test/java/com/celfit/crawler/FakeApifyRunner.java`:
```java
package com.celfit.crawler;

import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ApifyResult;
import com.celfit.crawler.apify.ApifyRunner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 잡 테스트용 fake. 스크립트된 결과를 순서대로 반환하고 (actorId, input)을 기록. */
public class FakeApifyRunner implements ApifyRunner {

    public record Call(String actorId, Map<String, Object> input) {}

    private final Deque<Object> script = new ArrayDeque<>();  // ApifyResult 또는 ApifyException
    public final List<Call> calls = new ArrayList<>();

    public void enqueue(List<Map<String, Object>> items) {
        script.add(new ApifyResult("fake-run-" + (script.size() + 1), items));
    }

    public void enqueueFailure(String message) {
        script.add(new ApifyException(message));
    }

    /** 테스트 간 상태 격리 — fake는 컨텍스트 싱글턴이라 각 테스트 @BeforeEach에서 호출할 것. */
    public void reset() {
        script.clear();
        calls.clear();
    }

    @Override
    public ApifyResult run(String actorId, Map<String, Object> input) {
        calls.add(new Call(actorId, input));
        if (script.isEmpty()) {
            throw new IllegalStateException("스크립트되지 않은 액터 호출: " + actorId);
        }
        Object next = script.pop();
        if (next instanceof ApifyException e) throw e;
        return (ApifyResult) next;
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/job/CrawlExecutorTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ApifyRunner;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.RunStatus;
import com.celfit.crawler.domain.TriggerType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Import(CrawlExecutorTest.Config.class)
class CrawlExecutorTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired CrawlExecutor executor;
    @Autowired CrawlRunRepository runs;

    @Test
    void 성공하면_crawl_run이_SUCCEEDED로_기록된다() {
        fake.enqueue(List.of(Map.of("shortCode", "a"), Map.of("shortCode", "b")));

        var execution = executor.execute(JobName.DISCOVER, TriggerType.MANUAL,
                null, "메이크업", "actor-x", Map.of("k", "v"));

        assertThat(execution.items()).hasSize(2);
        var run = runs.findById(execution.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getItemCount()).isEqualTo(2);
        assertThat(run.getApifyRunId()).isEqualTo("fake-run-1");
        assertThat(run.getKeyword()).isEqualTo("메이크업");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void 실패하면_FAILED로_기록되고_예외가_전파된다() {
        fake.enqueueFailure("보이지 않는 손");

        assertThatThrownBy(() -> executor.execute(JobName.QUALIFY, TriggerType.MANUAL,
                null, null, "actor-x", Map.of()))
                .isInstanceOf(ApifyException.class);

        var run = runs.findTop50ByOrderByIdDesc().get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("보이지 않는 손");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.job.CrawlExecutorTest"`
Expected: FAIL — 컴파일 에러 (CrawlExecutor 미존재)

- [ ] **Step 4: CrawlExecutor 구현**

`job/CrawlExecutor.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ApifyResult;
import com.celfit.crawler.apify.ApifyRunner;
import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 액터 실행 1회를 crawl_run으로 감싼다: RUNNING 기록 → 실행 → SUCCEEDED/FAILED 마감.
 * crawl_run 저장은 REQUIRES_NEW가 아니라 호출자 트랜잭션에 합류한다 — 잡 단위 원자성 우선.
 */
@Component
public class CrawlExecutor {

    public record Execution(Long runId, List<Map<String, Object>> items) {}

    private final ApifyRunner runner;
    private final CrawlRunRepository runs;
    private final Clock clock;

    public CrawlExecutor(ApifyRunner runner, CrawlRunRepository runs, Clock clock) {
        this.runner = runner;
        this.runs = runs;
        this.clock = clock;
    }

    public Execution execute(JobName job, TriggerType trigger, Long categoryId,
                             String keyword, String actorId, Map<String, Object> input) {
        CrawlRun run = runs.save(new CrawlRun(job, trigger, categoryId, keyword, actorId, clock.instant()));
        try {
            ApifyResult result = runner.run(actorId, input);
            run.finishOk(result.runId(), result.items().size(), clock.instant());
            runs.save(run);
            return new Execution(run.getId(), result.items());
        } catch (ApifyException e) {
            run.finishFailed(e.getMessage(), clock.instant());
            runs.save(run);
            throw e;
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.job.CrawlExecutorTest"`
Expected: PASS (2 tests)

```bash
git add -A && git commit -m "feat: CrawlExecutor — 액터 실행을 crawl_run으로 기록"
```

### Task 8: DiscoverJob

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/DiscoverJob.java`
- Test: `src/test/java/com/celfit/crawler/job/DiscoverJobTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/job/DiscoverJobTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(DiscoverJobTest.Config.class)
@Transactional
class DiscoverJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired DiscoverJob job;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Autowired CategoryRepository categories;
    @Autowired CategoryKeywordRepository keywords;
    @Autowired CollectionRuleRepository rules;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired RawDiscoveryPostRepository rawDiscovery;

    static Map<String, Object> item(String shortCode, String productType, String owner) {
        return productType == null
                ? Map.of("shortCode", shortCode, "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", owner)
                : Map.of("shortCode", shortCode, "productType", productType,
                         "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", owner);
    }

    Long seedCategory(String... kws) {
        Long catId = categories.save(new Category("메이크업")).getId();
        for (String kw : kws) keywords.save(new CategoryKeyword(catId, kw));
        return catId;
    }

    @Test
    void 발굴_아이템이_content와_raw로_등록된다() {
        Long catId = seedCategory("메이크업");
        fake.enqueue(List.of(item("sc1", "clips", "kim"), item("sc2", null, "lee")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(2);
        Content c1 = contents.findByShortCode("sc1").orElseThrow();
        assertThat(c1.getContentType()).isEqualTo(ContentType.REELS);
        assertThat(c1.getStatus()).isEqualTo(ContentStatus.PENDING);
        assertThat(c1.getDiscoveryKeyword()).isEqualTo("메이크업");
        assertThat(accounts.findByUsername("kim")).isPresent();
        assertThat(rawDiscovery.count()).isEqualTo(2);
        // 한글 키워드 → keywordSearch 자동 전환 확인
        assertThat(fake.calls.get(0).input()).containsEntry("keywordSearch", true);
    }

    @Test
    void 재발굴은_content를_안_늘리고_raw_이력만_쌓는다() {
        Long catId = seedCategory("메이크업");
        fake.enqueue(List.of(item("sc1", "clips", "kim")));
        fake.enqueue(List.of(item("sc1", "clips", "kim")));

        job.run(catId, TriggerType.MANUAL);
        var second = job.run(catId, TriggerType.MANUAL);

        assertThat(second.newContents()).isZero();
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(contents.count()).isEqualTo(1);
        assertThat(rawDiscovery.count()).isEqualTo(2);
    }

    @Test
    void content_types_규칙에_안_맞으면_완전히_skip() {
        Long catId = seedCategory("메이크업");
        CollectionRule rule = new CollectionRule(catId);
        rule.setContentTypes(ContentTypeFilter.REELS);
        rules.save(rule);
        fake.enqueue(List.of(item("reel1", "clips", "kim"), item("feed1", null, "lee")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(contents.findByShortCode("feed1")).isEmpty();
        assertThat(rawDiscovery.count()).isEqualTo(1);
    }

    @Test
    void 한_키워드가_실패해도_다음_키워드는_진행된다() {
        Long catId = seedCategory("메이크업", "화장품추천");
        fake.enqueueFailure("액터 폭발");
        fake.enqueue(List.of(item("sc9", "clips", "park")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.failedKeywords()).isEqualTo(1);
        assertThat(summary.newContents()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.job.DiscoverJobTest"`
Expected: FAIL — 컴파일 에러 (DiscoverJob 미존재)

- [ ] **Step 3: DiscoverJob 구현**

`job/DiscoverJob.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.apify.Actors;
import com.celfit.crawler.apify.ActorInputs;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.config.DiscoverProperties;
import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoverJob {

    public record Summary(int newContents, int duplicates, int skipped, int failedKeywords) {}

    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;
    private final AccountRepository accounts;
    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final CrawlExecutor executor;
    private final DiscoverProperties props;
    private final Clock clock;

    public DiscoverJob(CategoryRepository categories, CategoryKeywordRepository keywords,
                       CollectionRuleRepository rules, AccountRepository accounts,
                       ContentRepository contents, RawDiscoveryPostRepository rawDiscovery,
                       CrawlExecutor executor, DiscoverProperties props, Clock clock) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
        this.accounts = accounts;
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.executor = executor;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Summary run(long categoryId, TriggerType trigger) {
        categories.findById(categoryId)
                .filter(Category::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 비활성 카테고리: " + categoryId));
        ContentTypeFilter filter = rules.findByCategoryId(categoryId)
                .map(CollectionRule::getContentTypes)
                .orElse(ContentTypeFilter.ALL);

        int newContents = 0, duplicates = 0, skipped = 0, failedKeywords = 0;
        for (CategoryKeyword kw : keywords.findByCategoryIdAndEnabledTrue(categoryId)) {
            CrawlExecutor.Execution ex;
            try {
                ex = executor.execute(JobName.DISCOVER, trigger, categoryId, kw.getKeyword(),
                        Actors.DISCOVERY, ActorInputs.discovery(kw.getKeyword(), props.resultsLimit()));
            } catch (ApifyException e) {
                failedKeywords++;  // crawl_run에 FAILED 기록됨 — 다음 키워드 계속
                continue;
            }
            for (Map<String, Object> item : ex.items()) {
                var parsed = DiscoveryItemParser.parse(item);
                if (parsed.isEmpty() || !filter.allows(parsed.get().type())) {
                    skipped++;  // content_types 불일치·필수 필드 결손 → 등록·raw 모두 skip
                    continue;
                }
                var d = parsed.get();
                accounts.findByUsername(d.ownerUsername())
                        .orElseGet(() -> accounts.save(new Account(d.ownerUsername())));
                var existing = contents.findByShortCode(d.shortCode());
                Content content = existing.orElseGet(() -> contents.save(new Content(
                        d.shortCode(), d.type(), d.ownerUsername(), d.uploadedAt(),
                        categoryId, kw.getKeyword(), clock.instant())));
                if (existing.isPresent()) duplicates++; else newContents++;
                // 중복 발굴이어도 raw는 항상 저장 — "언제 어떤 키워드에서 발견됐나" 이력
                rawDiscovery.save(new RawDiscoveryPost(content.getId(), ex.runId(), d.payload(), clock.instant()));
            }
        }
        return new Summary(newContents, duplicates, skipped, failedKeywords);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.job.DiscoverJobTest"`
Expected: PASS (4 tests)

```bash
git add -A && git commit -m "feat: DiscoverJob — 키워드 발굴, content/raw 등록, 멱등"
```

---

### Task 9: QualifyJob

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/QualifyJob.java`
- Test: `src/test/java/com/celfit/crawler/job/QualifyJobTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/job/QualifyJobTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(QualifyJobTest.Config.class)
@Transactional
class QualifyJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired QualifyJob job;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Autowired CategoryRepository categories;
    @Autowired CollectionRuleRepository rules;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired RawProfileRepository rawProfiles;

    Long catId;

    Content seedContent(String shortCode, String owner) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        accounts.findByUsername(owner).orElseGet(() -> accounts.save(new Account(owner)));
        return contents.save(new Content(shortCode, ContentType.REELS, owner,
                Instant.parse("2026-07-01T00:00:00Z"), catId, "메이크업", Instant.now()));
    }

    void seedRule(Integer min, Integer max) {
        CollectionRule rule = new CollectionRule(catId);
        rule.setMinFollowers(min);
        rule.setMaxFollowers(max);
        rules.save(rule);
    }

    static Map<String, Object> profile(String username, int followers) {
        return Map.of("username", username, "followersCount", followers);
    }

    @Test
    void 프로필을_수집하고_팔로워_규칙으로_판정한다() {
        seedContent("sc1", "big");    // 팔로워 충분 → QUALIFIED
        seedContent("sc2", "small");  // 부족 → EXCLUDED
        seedRule(10_000, null);
        fake.enqueue(List.of(profile("big", 50_000), profile("small", 300)));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.profiled()).isEqualTo(2);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
        assertThat(contents.findByShortCode("sc2").orElseThrow().getStatus()).isEqualTo(ContentStatus.EXCLUDED);
        assertThat(rawProfiles.count()).isEqualTo(2);
        assertThat(accounts.findByUsername("big").orElseThrow().getLastProfiledAt()).isNotNull();
    }

    @Test
    void 규칙이_없으면_전부_QUALIFIED_프로필은_그래도_수집() {
        seedContent("sc1", "kim");
        fake.enqueue(List.of(profile("kim", 5)));

        job.run(TriggerType.MANUAL);

        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
    }

    @Test
    void 이미_프로필된_계정은_재수집하지_않는다() {
        Content c = seedContent("sc1", "kim");
        Account kim = accounts.findByUsername("kim").orElseThrow();
        kim.setLastProfiledAt(Instant.now());
        accounts.save(kim);
        // 프로필 액터 호출이 없어야 하므로 스크립트 안 넣음 — 호출되면 fake가 예외

        job.run(TriggerType.MANUAL);

        assertThat(fake.calls).isEmpty();
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
    }

    @Test
    void 팔로워_규칙이_있는데_프로필_미확보면_PENDING_유지() {
        seedContent("sc1", "ghost");
        seedRule(1000, null);
        fake.enqueue(List.of());  // 프로필 응답에 ghost 없음 (비공개 등)

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.PENDING);
    }

    @Test
    void requalify는_EXCLUDED를_Apify_재호출_없이_재판정한다() {
        seedContent("sc1", "kim");
        seedRule(10_000, null);
        fake.enqueue(List.of(profile("kim", 500)));
        job.run(TriggerType.MANUAL);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.EXCLUDED);

        // 규칙 완화 후 재판정 — raw_profile 재사용, 액터 추가 호출 없음
        CollectionRule rule = rules.findByCategoryId(catId).orElseThrow();
        rule.setMinFollowers(100);
        rules.save(rule);

        job.run(TriggerType.MANUAL, true);

        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
        assertThat(fake.calls).hasSize(1);  // 처음 1회뿐
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.job.QualifyJobTest"`
Expected: FAIL — 컴파일 에러

- [ ] **Step 3: QualifyJob 구현**

`job/QualifyJob.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.apify.Actors;
import com.celfit.crawler.apify.ActorInputs;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualifyJob {

    static final int PROFILE_CHUNK = 50;

    public record Summary(int profiled, int qualified, int excluded, int deferred) {}

    private final ContentRepository contents;
    private final AccountRepository accounts;
    private final CollectionRuleRepository rules;
    private final RawProfileRepository rawProfiles;
    private final CrawlExecutor executor;
    private final Clock clock;

    public QualifyJob(ContentRepository contents, AccountRepository accounts,
                      CollectionRuleRepository rules, RawProfileRepository rawProfiles,
                      CrawlExecutor executor, Clock clock) {
        this.contents = contents;
        this.accounts = accounts;
        this.rules = rules;
        this.rawProfiles = rawProfiles;
        this.executor = executor;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        return run(trigger, false);
    }

    /** requalify=true면 EXCLUDED도 재판정 (규칙 변경 후, raw_profile 재사용 — Apify 재호출 없음). */
    @Transactional
    public Summary run(TriggerType trigger, boolean requalify) {
        List<Content> targets = new ArrayList<>(contents.findByStatus(ContentStatus.PENDING));
        if (requalify) targets.addAll(contents.findByStatus(ContentStatus.EXCLUDED));

        int profiled = profileMissingAccounts(targets, trigger);

        int qualified = 0, excluded = 0, deferred = 0;
        Map<Long, Optional<CollectionRule>> ruleCache = new HashMap<>();
        for (Content c : targets) {
            CollectionRule rule = ruleCache
                    .computeIfAbsent(c.getCategoryId(), rules::findByCategoryId)
                    .orElse(null);
            if (rule == null || !rule.needsFollowers()) {
                c.setStatus(ContentStatus.QUALIFIED);
                c.setQualifiedAt(clock.instant());
                qualified++;
                continue;
            }
            Long followers = latestFollowers(c.getOwnerUsername());
            if (followers == null) {
                deferred++;  // 프로필 미확보 → PENDING 유지, 다음 실행 때 재시도
                continue;
            }
            boolean pass = rule.followersPass(followers);
            c.setStatus(pass ? ContentStatus.QUALIFIED : ContentStatus.EXCLUDED);
            c.setQualifiedAt(clock.instant());
            if (pass) qualified++; else excluded++;
        }
        return new Summary(profiled, qualified, excluded, deferred);
    }

    private int profileMissingAccounts(List<Content> targets, TriggerType trigger) {
        Set<String> usernames = targets.stream()
                .map(Content::getOwnerUsername)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (usernames.isEmpty()) return 0;
        List<Account> toProfile = accounts.findByUsernameInAndLastProfiledAtIsNull(usernames);

        int profiled = 0;
        for (List<Account> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
            List<String> names = chunk.stream().map(Account::getUsername).toList();
            CrawlExecutor.Execution ex;
            try {
                ex = executor.execute(JobName.QUALIFY, trigger, null, null,
                        Actors.PROFILE, ActorInputs.profiles(names));
            } catch (ApifyException e) {
                continue;  // FAILED 기록됨 — 해당 청크 계정은 다음 실행 때 재시도
            }
            Map<String, Account> byName = chunk.stream()
                    .collect(Collectors.toMap(Account::getUsername, a -> a));
            for (Map<String, Object> item : ex.items()) {
                Account acct = item.get("username") instanceof String s ? byName.get(s) : null;
                if (acct == null) continue;
                rawProfiles.save(new RawProfile(acct.getId(), ex.runId(), item, clock.instant()));
                acct.setLastProfiledAt(clock.instant());
                profiled++;
            }
        }
        return profiled;
    }

    private Long latestFollowers(String username) {
        return accounts.findByUsername(username)
                .flatMap(a -> rawProfiles.findTopByAccountIdOrderByCapturedAtDesc(a.getId()))
                .map(rp -> rp.getPayload().get("followersCount"))
                .filter(Number.class::isInstance)
                .map(n -> ((Number) n).longValue())
                .orElse(null);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.job.QualifyJobTest"`
Expected: PASS (5 tests)

```bash
git add -A && git commit -m "feat: QualifyJob — 프로필 수집 + 팔로워 규칙 판정, requalify 지원"
```

---

### Task 10: AggregateJob

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/AggregateJob.java`
- Test: `src/test/java/com/celfit/crawler/job/AggregateJobTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/job/AggregateJobTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(AggregateJobTest.Config.class)
@Transactional
class AggregateJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired AggregateJob job;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired RawPostDetailRepository rawDetails;
    @Autowired RawCommentRepository rawComments;

    Long catId;

    Content seedQualified(String shortCode, int daysAgo) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        Content c = new Content(shortCode, ContentType.REELS, "kim",
                Instant.now().minus(daysAgo, ChronoUnit.DAYS), catId, "메이크업", Instant.now());
        c.setStatus(ContentStatus.QUALIFIED);
        return contents.save(c);
    }

    static Map<String, Object> detail(String shortCode) {
        return Map.of("shortCode", shortCode, "likesCount", 10, "commentsCount", 2);
    }

    static Map<String, Object> comment(String shortCode, String text) {
        return Map.of("postUrl", "https://www.instagram.com/p/" + shortCode + "/",
                "ownerUsername", "fan", "text", text, "timestamp", "2026-07-05T00:00:00.000Z");
    }

    @Test
    void 도래분은_상세와_댓글을_적재하고_AGGREGATED가_된다() {
        seedQualified("sc1", 4);
        seedQualified("fresh", 1);  // 아직 3일 안 됨 — 대상 아님
        fake.enqueue(List.of(detail("sc1")));
        fake.enqueue(List.of(comment("sc1", "굿"), comment("sc1", "최고")));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.aggregated()).isEqualTo(1);
        Content c = contents.findByShortCode("sc1").orElseThrow();
        assertThat(c.getStatus()).isEqualTo(ContentStatus.AGGREGATED);
        assertThat(c.getAggregatedAt()).isNotNull();
        assertThat(rawDetails.count()).isEqualTo(1);
        assertThat(rawComments.count()).isEqualTo(2);
        assertThat(contents.findByShortCode("fresh").orElseThrow().getStatus())
                .isEqualTo(ContentStatus.QUALIFIED);
        // 댓글 액터 입력에 게시물당 상한이 들어간다
        assertThat(fake.calls.get(1).input()).containsEntry("resultsLimit", 50);
    }

    @Test
    void 응답에_없는_shortcode는_GONE() {
        seedQualified("살아있음", 4);
        seedQualified("삭제됨", 4);
        fake.enqueue(List.of(detail("살아있음")));
        fake.enqueue(List.of());  // 댓글 없음

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.aggregated()).isEqualTo(1);
        assertThat(summary.gone()).isEqualTo(1);
        assertThat(contents.findByShortCode("삭제됨").orElseThrow().getStatus())
                .isEqualTo(ContentStatus.GONE);
    }

    @Test
    void 액터_실패시_attempts_증가하고_상한_도달하면_FAILED() {
        Content c = seedQualified("sc1", 4);
        fake.enqueueFailure("일시 장애");

        var first = job.run(TriggerType.MANUAL);
        assertThat(first.retried()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getAggregateAttempts()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);

        // max-attempts=3 (테스트 yml) — 두 번 더 실패하면 FAILED
        fake.enqueueFailure("또 장애");
        job.run(TriggerType.MANUAL);
        fake.enqueueFailure("계속 장애");
        var third = job.run(TriggerType.MANUAL);

        assertThat(third.failed()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.FAILED);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.job.AggregateJobTest"`
Expected: FAIL — 컴파일 에러

- [ ] **Step 3: AggregateJob 구현**

`job/AggregateJob.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.apify.Actors;
import com.celfit.crawler.apify.ActorInputs;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ShortCodes;
import com.celfit.crawler.config.AggregateProperties;
import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AggregateJob {

    public record Summary(int aggregated, int gone, int retried, int failed) {}

    private final ContentRepository contents;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final CrawlExecutor executor;
    private final AggregateProperties props;
    private final Clock clock;

    public AggregateJob(ContentRepository contents, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, CrawlExecutor executor,
                        AggregateProperties props, Clock clock) {
        this.contents = contents;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.executor = executor;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.delayDays()));
        List<Content> due = contents.findDue(ContentStatus.QUALIFIED, cutoff,
                PageRequest.of(0, props.batchLimit()));

        int aggregated = 0, gone = 0, retried = 0, failed = 0;
        for (List<Content> chunk : ActorInputs.chunk(due, props.chunkSize())) {
            List<String> urls = chunk.stream()
                    .map(c -> ShortCodes.postUrl(c.getShortCode()))
                    .toList();

            Map<String, Map<String, Object>> detailByCode;
            Map<String, List<Map<String, Object>>> commentsByCode;
            Long detailRunId;
            Long commentRunId;
            try {
                var dx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                        Actors.POST_DETAIL, ActorInputs.postDetail(urls));
                detailRunId = dx.runId();
                detailByCode = indexDetails(dx.items());
                var cx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                        Actors.COMMENT, ActorInputs.comments(urls, props.commentsPerPost()));
                commentRunId = cx.runId();
                commentsByCode = groupComments(cx.items());
            } catch (ApifyException e) {
                // 청크 전체 재시도 대상 — attempts 상한 도달분은 FAILED로 밀어냄
                for (Content c : chunk) {
                    c.setAggregateAttempts(c.getAggregateAttempts() + 1);
                    if (c.getAggregateAttempts() >= props.maxAttempts()) {
                        c.setStatus(ContentStatus.FAILED);
                        failed++;
                    } else {
                        retried++;
                    }
                }
                continue;
            }

            for (Content c : chunk) {
                Map<String, Object> detail = detailByCode.get(c.getShortCode());
                if (detail == null) {
                    c.setStatus(ContentStatus.GONE);  // 응답에 없음 = 삭제·비공개 간주
                    gone++;
                    continue;
                }
                rawDetails.save(new RawPostDetail(c.getId(), detailRunId, detail, clock.instant()));
                for (Map<String, Object> comment : commentsByCode.getOrDefault(c.getShortCode(), List.of())) {
                    rawComments.save(new RawComment(c.getId(), commentRunId, comment, clock.instant()));
                }
                c.setStatus(ContentStatus.AGGREGATED);
                c.setAggregatedAt(clock.instant());
                aggregated++;
            }
        }
        return new Summary(aggregated, gone, retried, failed);
    }

    private Map<String, Map<String, Object>> indexDetails(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> item : items) {
            String sc = item.get("shortCode") instanceof String s ? s
                    : ShortCodes.fromUrl(item.get("url") instanceof String u ? u : null).orElse(null);
            if (sc != null) byCode.put(sc, item);
        }
        return byCode;
    }

    private Map<String, List<Map<String, Object>>> groupComments(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> byCode = new HashMap<>();
        for (Map<String, Object> item : items) {
            ShortCodes.fromUrl(item.get("postUrl") instanceof String u ? u : null)
                    .ifPresent(sc -> byCode.computeIfAbsent(sc, k -> new ArrayList<>()).add(item));
        }
        return byCode;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.job.AggregateJobTest"`
Expected: PASS (3 tests)

```bash
git add -A && git commit -m "feat: AggregateJob — +3일 도래분 상세·댓글 적재, GONE·재시도·FAILED"
```

---

### Task 11: JobLock · JobService · 관리 API (트리거/이력/상태)

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/JobLock.java`, `.../JobService.java`
- Create: `src/main/java/com/celfit/crawler/admin/StatusService.java`, `.../JobController.java`, `.../AdminQueryController.java`
- Modify: `src/main/java/com/celfit/crawler/config/CrawlerConfig.java` (jobTaskExecutor 타입을 TaskExecutor로)
- Modify: `src/test/resources/application.yml` (bean overriding 허용 1줄)
- Test: `src/test/java/com/celfit/crawler/admin/JobApiTest.java`

- [ ] **Step 1: CrawlerConfig 수정 + 테스트 yml 수정**

`config/CrawlerConfig.java`의 jobTaskExecutor 빈을 다음으로 교체 (import도 `AsyncTaskExecutor`→`TaskExecutor`):
```java
    /** 잡 비동기 실행용 — 테스트는 SyncTaskExecutor로 대체해 결정적으로 만든다. */
    @Bean
    TaskExecutor jobTaskExecutor() {
        return new SimpleAsyncTaskExecutor("job-");
    }
```
(import: `org.springframework.core.task.TaskExecutor`)

`src/test/resources/application.yml` 맨 위 `spring:` 아래에 추가:
```yaml
  main:
    allow-bean-definition-overriding: true
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/admin/JobApiTest.java`:
```java
package com.celfit.crawler.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryKeyword;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.CategoryKeywordRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.job.JobLock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(JobApiTest.Config.class)
@Transactional  // SyncTaskExecutor라 잡이 같은 스레드에서 돌아 테스트 tx에 합류 → 롤백으로 DB 오염 방지
class JobApiTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }

        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() {
            return new SyncTaskExecutor();  // 트리거를 동기 실행으로 만들어 테스트 결정적
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeApifyRunner fake;
    @Autowired CategoryRepository categories;
    @Autowired CategoryKeywordRepository keywords;
    @Autowired JobLock lock;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @AfterEach
    void unlock() {
        for (JobName j : JobName.values()) lock.release(j);
    }

    @Test
    void discover_트리거는_202이고_잡이_실행된다() throws Exception {
        Long catId = categories.save(new Category("메이크업")).getId();
        keywords.save(new CategoryKeyword(catId, "메이크업"));
        fake.enqueue(List.of());

        mvc.perform(post("/admin/jobs/discover").param("category", String.valueOf(catId)))
                .andExpect(status().isAccepted());

        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void 실행_중인_잡은_409() throws Exception {
        lock.tryAcquire(JobName.QUALIFY);
        mvc.perform(post("/admin/jobs/qualify")).andExpect(status().isConflict());
    }

    @Test
    void discover에_category_없으면_400_모르는_잡도_400() throws Exception {
        mvc.perform(post("/admin/jobs/discover")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/jobs/terraform")).andExpect(status().isBadRequest());
    }

    @Test
    void runs와_status_조회() throws Exception {
        mvc.perform(get("/admin/runs")).andExpect(status().isOk());
        mvc.perform(get("/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentByStatus.PENDING").exists())
                .andExpect(jsonPath("$.dueForAggregate").exists());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.admin.JobApiTest"`
Expected: FAIL — 컴파일 에러

- [ ] **Step 4: 구현**

`job/JobLock.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.domain.JobName;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 같은 잡 동시 실행 방지 (단일 인스턴스 전제 — 인프로세스 락). */
@Component
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

`job/JobService.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    public enum TriggerResult { ACCEPTED, BUSY }

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobLock lock;
    private final DiscoverJob discoverJob;
    private final QualifyJob qualifyJob;
    private final AggregateJob aggregateJob;
    private final TaskExecutor taskExecutor;

    public JobService(JobLock lock, DiscoverJob discoverJob, QualifyJob qualifyJob,
                      AggregateJob aggregateJob,
                      @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.lock = lock;
        this.discoverJob = discoverJob;
        this.qualifyJob = qualifyJob;
        this.aggregateJob = aggregateJob;
        this.taskExecutor = taskExecutor;
    }

    public TriggerResult trigger(JobName job, Long categoryId, TriggerType triggerType) {
        if (job == JobName.DISCOVER && categoryId == null) {
            throw new IllegalArgumentException("discover는 category 파라미터가 필요합니다");
        }
        if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
        taskExecutor.execute(() -> {
            try {
                switch (job) {
                    case DISCOVER -> log.info("discover 완료: {}", discoverJob.run(categoryId, triggerType));
                    case QUALIFY -> log.info("qualify 완료: {}", qualifyJob.run(triggerType));
                    case AGGREGATE -> log.info("aggregate 완료: {}", aggregateJob.run(triggerType));
                }
            } catch (Exception e) {
                log.error("{} 잡 실패", job, e);
            } finally {
                lock.release(job);
            }
        });
        return TriggerResult.ACCEPTED;
    }
}
```

`admin/StatusService.java`:
```java
package com.celfit.crawler.admin;

import com.celfit.crawler.config.AggregateProperties;
import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StatusService {

    public record StatusSummary(Map<ContentStatus, Long> contentByStatus,
                                long rawDiscoveryPosts, long rawPostDetails,
                                long rawComments, long rawProfiles, long dueForAggregate) {}

    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final AggregateProperties aggregateProps;
    private final Clock clock;

    public StatusService(ContentRepository contents, RawDiscoveryPostRepository rawDiscovery,
                         RawPostDetailRepository rawDetails, RawCommentRepository rawComments,
                         RawProfileRepository rawProfiles, AggregateProperties aggregateProps,
                         Clock clock) {
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.rawProfiles = rawProfiles;
        this.aggregateProps = aggregateProps;
        this.clock = clock;
    }

    public StatusSummary summary() {
        Map<ContentStatus, Long> byStatus = new EnumMap<>(ContentStatus.class);
        for (ContentStatus s : ContentStatus.values()) {
            byStatus.put(s, contents.countByStatus(s));
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(aggregateProps.delayDays()));
        long due = contents.countByStatusAndAggregatedAtIsNullAndUploadedAtLessThanEqual(
                ContentStatus.QUALIFIED, cutoff);
        return new StatusSummary(byStatus, rawDiscovery.count(), rawDetails.count(),
                rawComments.count(), rawProfiles.count(), due);
    }
}
```

`admin/JobController.java`:
```java
package com.celfit.crawler.admin;

import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import com.celfit.crawler.job.JobService;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/{job}")
    public ResponseEntity<Map<String, String>> trigger(@PathVariable String job,
                                                       @RequestParam(required = false) Long category) {
        JobName name;
        try {
            name = JobName.valueOf(job.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "알 수 없는 잡: " + job));
        }
        try {
            return switch (jobService.trigger(name, category, TriggerType.MANUAL)) {
                case ACCEPTED -> ResponseEntity.accepted()
                        .body(Map.of("job", name.name(), "result", "accepted"));
                case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("job", name.name(), "result", "busy"));
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
```

`admin/AdminQueryController.java`:
```java
package com.celfit.crawler.admin;

import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminQueryController {

    public record RunView(Long id, String job, String trigger, Long categoryId, String keyword,
                          String actorId, String apifyRunId, String status, Integer itemCount,
                          String errorMessage, Instant startedAt, Instant finishedAt) {
        static RunView from(CrawlRun r) {
            return new RunView(r.getId(), r.getJob().name(), r.getTriggerType().name(),
                    r.getCategoryId(), r.getKeyword(), r.getActorId(), r.getApifyRunId(),
                    r.getStatus().name(), r.getItemCount(), r.getErrorMessage(),
                    r.getStartedAt(), r.getFinishedAt());
        }
    }

    private final CrawlRunRepository runs;
    private final StatusService statusService;

    public AdminQueryController(CrawlRunRepository runs, StatusService statusService) {
        this.runs = runs;
        this.statusService = statusService;
    }

    @GetMapping("/runs")
    public List<RunView> runs() {
        return runs.findTop50ByOrderByIdDesc().stream().map(RunView::from).toList();
    }

    @GetMapping("/status")
    public StatusService.StatusSummary status() {
        return statusService.summary();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.admin.JobApiTest"`
Expected: PASS (4 tests)

Run: `./gradlew test` (전체 회귀)
Expected: PASS

```bash
git add -A && git commit -m "feat: 잡 트리거 API (락·202/409), 실행 이력·상태 조회"
```

---

### Task 12: ScheduleRunner

**Files:**
- Create: `src/main/java/com/celfit/crawler/job/ScheduleRunner.java`
- Test: `src/test/java/com/celfit/crawler/job/ScheduleRunnerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/job/ScheduleRunnerTest.java`:
```java
package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

class ScheduleRunnerTest {

    /** 기본(enabled=false)에서는 스케줄 빈이 아예 없다. */
    static class DisabledTest extends IntegrationTest {
        @Autowired ApplicationContext ctx;

        @Test
        void 스케줄러_빈이_없다() {
            assertThat(ctx.getBeansOfType(ScheduleRunner.class)).isEmpty();
        }
    }

    @TestPropertySource(properties = "crawler.schedule.enabled=true")
    static class EnabledTest extends IntegrationTest {
        @Autowired ApplicationContext ctx;

        @Test
        void 스케줄러_빈이_뜬다() {
            assertThat(ctx.getBeansOfType(ScheduleRunner.class)).hasSize(1);
        }
    }
}
```

주의: JUnit이 중첩 static 클래스를 실행하도록 `@Nested`가 아니라 별도 top-level로 인식돼야 함 — 위 구조가 실행되지 않으면 두 클래스를 `ScheduleRunnerDisabledTest.java` / `ScheduleRunnerEnabledTest.java` 파일로 분리한다 (동작은 동일).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.job.ScheduleRunnerTest*"`
Expected: FAIL — 컴파일 에러 (ScheduleRunner 미존재)

- [ ] **Step 3: 구현**

`job/ScheduleRunner.java`:
```java
package com.celfit.crawler.job;

import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 스케줄 트리거 — crawler.schedule.enabled=true일 때만 활성. 초기 운영은 수동 트리거. */
@Component
@ConditionalOnProperty(prefix = "crawler.schedule", name = "enabled", havingValue = "true")
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final JobService jobService;
    private final CategoryRepository categories;

    public ScheduleRunner(JobService jobService, CategoryRepository categories) {
        this.jobService = jobService;
        this.categories = categories;
    }

    @Scheduled(cron = "${crawler.schedule.discover-cron}")
    void discover() {
        for (Category c : categories.findByEnabledTrue()) {
            log.info("스케줄 discover 카테고리={}: {}", c.getName(),
                    jobService.trigger(JobName.DISCOVER, c.getId(), TriggerType.SCHEDULED));
        }
    }

    @Scheduled(cron = "${crawler.schedule.qualify-cron}")
    void qualify() {
        log.info("스케줄 qualify: {}", jobService.trigger(JobName.QUALIFY, null, TriggerType.SCHEDULED));
    }

    @Scheduled(cron = "${crawler.schedule.aggregate-cron}")
    void aggregate() {
        log.info("스케줄 aggregate: {}", jobService.trigger(JobName.AGGREGATE, null, TriggerType.SCHEDULED));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.job.ScheduleRunnerTest*"`
Expected: PASS (2 tests)

```bash
git add -A && git commit -m "feat: ScheduleRunner — 설정 게이트된 일일 스케줄 (기본 off)"
```

---

### Task 13: 카테고리·키워드·규칙 CRUD (서비스 + REST)

**Files:**
- Create: `src/main/java/com/celfit/crawler/admin/CategoryService.java`, `.../CategoryAdminController.java`
- Test: `src/test/java/com/celfit/crawler/admin/CategoryApiTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/celfit/crawler/admin/CategoryApiTest.java`:
```java
package com.celfit.crawler.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Transactional
class CategoryApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    long createCategory(String name) throws Exception {
        String body = mvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
    }

    @Test
    void 카테고리_키워드_규칙_CRUD_왕복() throws Exception {
        long catId = createCategory("메이크업");

        // 중복 생성 → 409
        mvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"메이크업\"}"))
                .andExpect(status().isConflict());

        // 키워드 추가
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"화장품추천\"}"))
                .andExpect(status().isCreated());

        // 규칙 업서트
        mvc.perform(put("/admin/categories/" + catId + "/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minFollowers\": 5000, \"maxFollowers\": null, \"contentTypes\": \"REELS\"}"))
                .andExpect(status().isOk());

        // 목록에 반영 확인
        mvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("메이크업"))
                .andExpect(jsonPath("$[0].keywords[0].keyword").value("화장품추천"))
                .andExpect(jsonPath("$[0].rule.minFollowers").value(5000))
                .andExpect(jsonPath("$[0].rule.contentTypes").value("REELS"));

        // 카테고리 비활성화
        mvc.perform(patch("/admin/categories/" + catId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    void 키워드_토글() throws Exception {
        long catId = createCategory("스킨케어");
        String body = mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"피부관리\"}"))
                .andReturn().getResponse().getContentAsString();
        long kwId = new ObjectMapper().readTree(body).get("id").asLong();

        mvc.perform(patch("/admin/keywords/" + kwId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords[0].enabled").value(false));
    }
}
```

주의: SB4의 Jackson 패키지가 `tools.jackson`이 아니라 기존 `com.fasterxml.jackson`이면 import를 `com.fasterxml.jackson.databind.ObjectMapper`로 바꾼다 (컴파일 에러로 즉시 드러남).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.admin.CategoryApiTest"`
Expected: FAIL — 404 또는 컴파일 에러

- [ ] **Step 3: 구현**

`admin/CategoryService.java`:
```java
package com.celfit.crawler.admin;

import com.celfit.crawler.domain.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {

    public record KeywordView(Long id, String keyword, boolean enabled) {}
    public record RuleView(Integer minFollowers, Integer maxFollowers, ContentTypeFilter contentTypes) {}
    public record CategoryView(Long id, String name, boolean enabled,
                               List<KeywordView> keywords, RuleView rule) {}

    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;

    public CategoryService(CategoryRepository categories, CategoryKeywordRepository keywords,
                           CollectionRuleRepository rules) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        return categories.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public CategoryView create(String name) {
        if (categories.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 카테고리: " + name);
        }
        return toView(categories.save(new Category(name)));
    }

    @Transactional
    public void setCategoryEnabled(Long id, boolean enabled) {
        Category c = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + id));
        c.setEnabled(enabled);
    }

    @Transactional
    public KeywordView addKeyword(Long categoryId, String keyword) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        if (keywords.existsByCategoryIdAndKeyword(categoryId, keyword)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 키워드: " + keyword);
        }
        CategoryKeyword saved = keywords.save(new CategoryKeyword(categoryId, keyword));
        return new KeywordView(saved.getId(), saved.getKeyword(), saved.isEnabled());
    }

    @Transactional
    public void setKeywordEnabled(Long keywordId, boolean enabled) {
        CategoryKeyword kw = keywords.findById(keywordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음: " + keywordId));
        kw.setEnabled(enabled);
    }

    @Transactional
    public RuleView upsertRule(Long categoryId, RuleView req) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        CollectionRule rule = rules.findByCategoryId(categoryId)
                .orElseGet(() -> new CollectionRule(categoryId));
        rule.setMinFollowers(req.minFollowers());
        rule.setMaxFollowers(req.maxFollowers());
        rule.setContentTypes(req.contentTypes() == null ? ContentTypeFilter.ALL : req.contentTypes());
        rule = rules.save(rule);
        return new RuleView(rule.getMinFollowers(), rule.getMaxFollowers(), rule.getContentTypes());
    }

    private CategoryView toView(Category c) {
        List<KeywordView> kws = keywords.findByCategoryId(c.getId()).stream()
                .map(k -> new KeywordView(k.getId(), k.getKeyword(), k.isEnabled()))
                .toList();
        RuleView rule = rules.findByCategoryId(c.getId())
                .map(r -> new RuleView(r.getMinFollowers(), r.getMaxFollowers(), r.getContentTypes()))
                .orElse(null);
        return new CategoryView(c.getId(), c.getName(), c.isEnabled(), kws, rule);
    }
}
```

`admin/CategoryAdminController.java`:
```java
package com.celfit.crawler.admin;

import com.celfit.crawler.admin.CategoryService.CategoryView;
import com.celfit.crawler.admin.CategoryService.KeywordView;
import com.celfit.crawler.admin.CategoryService.RuleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class CategoryAdminController {

    public record CategoryReq(@NotBlank String name) {}
    public record KeywordReq(@NotBlank String keyword) {}
    public record EnabledReq(boolean enabled) {}

    private final CategoryService service;

    public CategoryAdminController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<CategoryView> list() {
        return service.list();
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryView> create(@Valid @RequestBody CategoryReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req.name()));
    }

    @PatchMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        service.setCategoryEnabled(id, req.enabled());
    }

    @PostMapping("/categories/{id}/keywords")
    public ResponseEntity<KeywordView> addKeyword(@PathVariable Long id,
                                                  @Valid @RequestBody KeywordReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addKeyword(id, req.keyword()));
    }

    @PatchMapping("/keywords/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setKeywordEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        service.setKeywordEnabled(id, req.enabled());
    }

    @PutMapping("/categories/{id}/rule")
    public RuleView upsertRule(@PathVariable Long id, @RequestBody RuleView req) {
        return service.upsertRule(id, req);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.admin.CategoryApiTest"`
Expected: PASS (2 tests)

```bash
git add -A && git commit -m "feat: 카테고리·키워드·수집 규칙 CRUD API"
```

---

### Task 14: UI ① 레이아웃·대시보드·잡 실행

**Files:**
- Create: `src/main/java/com/celfit/crawler/ui/UiController.java`, `.../UiJobController.java`
- Create: `src/main/resources/templates/fragments/nav.html`, `.../fragments/runs.html`, `.../dashboard.html`, `.../jobs.html`
- Test: `src/test/java/com/celfit/crawler/ui/UiSmokeTest.java`

- [ ] **Step 1: 실패하는 스모크 테스트 작성**

`src/test/java/com/celfit/crawler/ui/UiSmokeTest.java`:
```java
package com.celfit.crawler.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(UiSmokeTest.Config.class)
@Transactional  // 카테고리 저장이 롤백되도록 — 다른 테스트 클래스와 DB 공유
class UiSmokeTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }

        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;

    @Test
    void 루트는_ui로_리다이렉트() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui"));
    }

    @Test
    void 대시보드와_잡_화면이_뜬다() throws Exception {
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대시보드")));
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk());
        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk());
    }

    @Test
    void 잡_실행_폼은_플래시_메시지와_함께_리다이렉트() throws Exception {
        categories.save(new Category("메이크업"));
        mvc.perform(post("/ui/jobs/qualify"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/jobs"))
                .andExpect(flash().attributeExists("message"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.celfit.crawler.ui.UiSmokeTest"`
Expected: FAIL — 404

- [ ] **Step 3: 컨트롤러 구현**

`ui/UiController.java`:
```java
package com.celfit.crawler.ui;

import com.celfit.crawler.admin.StatusService;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.CrawlRunRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {

    private final StatusService statusService;
    private final CrawlRunRepository runs;
    private final CategoryRepository categories;

    public UiController(StatusService statusService, CrawlRunRepository runs,
                        CategoryRepository categories) {
        this.statusService = statusService;
        this.runs = runs;
        this.categories = categories;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/ui";
    }

    @GetMapping("/ui")
    public String dashboard(Model model) {
        model.addAttribute("summary", statusService.summary());
        return "dashboard";
    }

    @GetMapping("/ui/fragments/runs")
    public String runsFragment(Model model) {
        model.addAttribute("runs", runs.findTop50ByOrderByIdDesc());
        return "fragments/runs :: table";
    }

    @GetMapping("/ui/jobs")
    public String jobs(Model model) {
        model.addAttribute("categories", categories.findByEnabledTrue());
        return "jobs";
    }
}
```

`ui/UiJobController.java`:
```java
package com.celfit.crawler.ui;

import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import com.celfit.crawler.job.JobService;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui/jobs")
public class UiJobController {

    private final JobService jobService;

    public UiJobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/{job}")
    public String trigger(@PathVariable String job,
                          @RequestParam(required = false) Long category,
                          RedirectAttributes ra) {
        try {
            JobName name = JobName.valueOf(job.toUpperCase(Locale.ROOT));
            String message = switch (jobService.trigger(name, category, TriggerType.MANUAL)) {
                case ACCEPTED -> name + " 실행 시작";
                case BUSY -> name + "이(가) 이미 실행 중입니다";
            };
            ra.addFlashAttribute("message", message);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("message", "실행 불가: " + e.getMessage());
        }
        return "redirect:/ui/jobs";
    }
}
```

- [ ] **Step 4: 템플릿 작성**

`templates/fragments/nav.html`:
```html
<nav th:fragment="nav" xmlns:th="http://www.thymeleaf.org">
    <a href="/ui">대시보드</a> |
    <a href="/ui/jobs">잡 실행</a> |
    <a href="/ui/categories">카테고리·규칙</a> |
    <a href="/ui/contents">수집 데이터</a>
    <hr/>
</nav>
```

`templates/fragments/runs.html`:
```html
<div th:fragment="table" xmlns:th="http://www.thymeleaf.org">
    <table border="1" cellpadding="4">
        <tr><th>id</th><th>잡</th><th>트리거</th><th>키워드</th><th>액터</th>
            <th>상태</th><th>건수</th><th>에러</th><th>시작</th><th>종료</th></tr>
        <tr th:each="r : ${runs}">
            <td th:text="${r.id}"></td>
            <td th:text="${r.job}"></td>
            <td th:text="${r.triggerType}"></td>
            <td th:text="${r.keyword}"></td>
            <td th:text="${r.actorId}"></td>
            <td th:text="${r.status}"></td>
            <td th:text="${r.itemCount}"></td>
            <td th:text="${r.errorMessage}"></td>
            <td th:text="${r.startedAt}"></td>
            <td th:text="${r.finishedAt}"></td>
        </tr>
    </table>
</div>
```

`templates/dashboard.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <title>celfit crawler — 대시보드</title>
    <script src="https://unpkg.com/htmx.org@1.9.12"></script>
    <style>
        body { font-family: sans-serif; margin: 2rem; }
        .tiles { display: flex; gap: 1rem; flex-wrap: wrap; }
        .tile { border: 1px solid #ccc; padding: 1rem; min-width: 8rem; text-align: center; }
        .num { font-size: 1.6rem; font-weight: bold; }
    </style>
</head>
<body>
<nav th:replace="~{fragments/nav :: nav}"></nav>
<h1>대시보드</h1>
<div class="tiles">
    <div class="tile" th:each="e : ${summary.contentByStatus()}">
        <div class="num" th:text="${e.value}">0</div>
        <div th:text="${e.key}">STATUS</div>
    </div>
    <div class="tile">
        <div class="num" th:text="${summary.dueForAggregate()}">0</div>
        <div>집계 대기 (+3일 도래)</div>
    </div>
</div>
<h2>raw 적재량</h2>
<ul>
    <li>발굴 게시물: <span th:text="${summary.rawDiscoveryPosts()}"></span></li>
    <li>게시물 상세: <span th:text="${summary.rawPostDetails()}"></span></li>
    <li>댓글: <span th:text="${summary.rawComments()}"></span></li>
    <li>프로필: <span th:text="${summary.rawProfiles()}"></span></li>
</ul>
<h2>최근 실행</h2>
<div hx-get="/ui/fragments/runs" hx-trigger="load, every 5s"></div>
</body>
</html>
```

`templates/jobs.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <title>celfit crawler — 잡 실행</title>
    <script src="https://unpkg.com/htmx.org@1.9.12"></script>
    <style>
        body { font-family: sans-serif; margin: 2rem; }
        form { display: inline-block; margin-right: 1rem; }
        .flash { background: #e8f5e9; padding: 0.5rem 1rem; margin: 1rem 0; }
    </style>
</head>
<body>
<nav th:replace="~{fragments/nav :: nav}"></nav>
<h1>잡 실행</h1>
<div class="flash" th:if="${message}" th:text="${message}"></div>

<form method="post" th:action="@{/ui/jobs/discover}">
    <select name="category">
        <option th:each="c : ${categories}" th:value="${c.id}" th:text="${c.name}"></option>
    </select>
    <button type="submit">① discover — 발굴</button>
</form>
<form method="post" th:action="@{/ui/jobs/qualify}">
    <button type="submit">② qualify — 프로필·규칙 판정</button>
</form>
<form method="post" th:action="@{/ui/jobs/aggregate}">
    <button type="submit">③ aggregate — +3일 상세·댓글</button>
</form>

<h2>최근 실행</h2>
<div hx-get="/ui/fragments/runs" hx-trigger="load, every 5s"></div>
</body>
</html>
```

- [ ] **Step 5: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.ui.UiSmokeTest"`
Expected: PASS (3 tests)

```bash
git add -A && git commit -m "feat: UI — 대시보드·잡 실행 화면 (htmx 실행 이력 5초 갱신)"
```

---

### Task 15: UI ② 카테고리·규칙 관리 + 수집 데이터 열람

**Files:**
- Create: `src/main/java/com/celfit/crawler/ui/UiCategoryController.java`
- Modify: `src/main/java/com/celfit/crawler/ui/UiController.java` (contents 화면 2개 추가)
- Create: `src/main/resources/templates/categories.html`, `.../contents.html`, `.../content-detail.html`
- Test: Modify `src/test/java/com/celfit/crawler/ui/UiSmokeTest.java` (테스트 추가)

- [ ] **Step 1: 실패하는 테스트 추가**

`UiSmokeTest.java`에 추가:
```java
    @Test
    void 카테고리_화면과_폼() throws Exception {
        mvc.perform(get("/ui/categories")).andExpect(status().isOk());
        mvc.perform(post("/ui/categories").param("name", "메이크업"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/categories"));
    }

    @Test
    void 수집_데이터_화면() throws Exception {
        mvc.perform(get("/ui/contents")).andExpect(status().isOk());
        mvc.perform(get("/ui/contents").param("status", "PENDING")).andExpect(status().isOk());
    }
```

Run: `./gradlew test --tests "com.celfit.crawler.ui.UiSmokeTest"`
Expected: FAIL — 404 (새 화면 2개)

- [ ] **Step 2: UiCategoryController 구현**

`ui/UiCategoryController.java`:
```java
package com.celfit.crawler.ui;

import com.celfit.crawler.admin.CategoryService;
import com.celfit.crawler.admin.CategoryService.RuleView;
import com.celfit.crawler.domain.ContentTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui")
public class UiCategoryController {

    private final CategoryService service;

    public UiCategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public String page(Model model) {
        model.addAttribute("categories", service.list());
        model.addAttribute("filters", ContentTypeFilter.values());
        return "categories";
    }

    @PostMapping("/categories")
    public String addCategory(@RequestParam String name, RedirectAttributes ra) {
        return handle(ra, () -> service.create(name.trim()));
    }

    @PostMapping("/categories/{id}/toggle")
    public String toggleCategory(@PathVariable Long id, @RequestParam boolean enabled,
                                 RedirectAttributes ra) {
        return handle(ra, () -> service.setCategoryEnabled(id, enabled));
    }

    @PostMapping("/categories/{id}/keywords")
    public String addKeyword(@PathVariable Long id, @RequestParam String keyword,
                             RedirectAttributes ra) {
        return handle(ra, () -> service.addKeyword(id, keyword.trim()));
    }

    @PostMapping("/keywords/{id}/toggle")
    public String toggleKeyword(@PathVariable Long id, @RequestParam boolean enabled,
                                RedirectAttributes ra) {
        return handle(ra, () -> service.setKeywordEnabled(id, enabled));
    }

    @PostMapping("/categories/{id}/rule")
    public String saveRule(@PathVariable Long id,
                           @RequestParam(required = false) Integer minFollowers,
                           @RequestParam(required = false) Integer maxFollowers,
                           @RequestParam ContentTypeFilter contentTypes,
                           RedirectAttributes ra) {
        return handle(ra, () -> service.upsertRule(id, new RuleView(minFollowers, maxFollowers, contentTypes)));
    }

    private String handle(RedirectAttributes ra, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("message", e.getReason());
        }
        return "redirect:/ui/categories";
    }
}
```

(`handle`의 `Runnable` 인자에 값을 반환하는 람다를 쓰기 위해 컴파일 에러가 나면 `() -> { service.create(name.trim()); }`처럼 블록 람다로 감싼다.)

- [ ] **Step 3: UiController에 데이터 열람 추가**

`ui/UiController.java`에 필드·생성자 파라미터 추가:
```java
    // 추가 필드 (생성자 주입에도 추가)
    private final ContentRepository contents;
    private final AccountRepository accounts;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final ObjectMapper objectMapper;
```

메서드 추가:
```java
    @GetMapping("/ui/contents")
    public String contents(@RequestParam(required = false) ContentStatus status,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        var pageable = PageRequest.of(page, 50, Sort.by(Sort.Direction.DESC, "id"));
        var result = status == null ? contents.findAll(pageable)
                                    : contents.findByStatus(status, pageable);
        model.addAttribute("page", result);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ContentStatus.values());
        return "contents";
    }

    @GetMapping("/ui/contents/{id}")
    public String contentDetail(@PathVariable Long id, Model model) {
        Content content = contents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "콘텐츠 없음"));
        model.addAttribute("content", content);
        model.addAttribute("detailJson", rawDetails.findTopByContentIdOrderByCapturedAtDesc(id)
                .map(d -> pretty(d.getPayload())).orElse(null));
        model.addAttribute("comments", rawComments.findTop100ByContentIdOrderByIdDesc(id));
        model.addAttribute("profileJson", accounts.findByUsername(content.getOwnerUsername())
                .flatMap(a -> rawProfiles.findTopByAccountIdOrderByCapturedAtDesc(a.getId()))
                .map(p -> pretty(p.getPayload())).orElse(null));
        return "content-detail";
    }

    private String pretty(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
```

필요 import: `com.celfit.crawler.domain.*`, `com.fasterxml.jackson.databind.ObjectMapper`, `org.springframework.data.domain.PageRequest`, `org.springframework.data.domain.Sort`, `org.springframework.http.HttpStatus`, `org.springframework.web.bind.annotation.PathVariable`, `org.springframework.web.bind.annotation.RequestParam`, `org.springframework.web.server.ResponseStatusException`.

- [ ] **Step 4: 템플릿 작성**

`templates/categories.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <title>celfit crawler — 카테고리·규칙</title>
    <style>
        body { font-family: sans-serif; margin: 2rem; }
        .card { border: 1px solid #ccc; padding: 1rem; margin-bottom: 1rem; }
        .off { color: #999; }
        .flash { background: #fff3e0; padding: 0.5rem 1rem; margin: 1rem 0; }
        form.inline { display: inline; }
    </style>
</head>
<body>
<nav th:replace="~{fragments/nav :: nav}"></nav>
<h1>카테고리 · 수집 규칙</h1>
<div class="flash" th:if="${message}" th:text="${message}"></div>

<form method="post" th:action="@{/ui/categories}">
    <input name="name" placeholder="새 카테고리" required/>
    <button type="submit">추가</button>
</form>

<div class="card" th:each="c : ${categories}">
    <h2>
        <span th:text="${c.name()}" th:classappend="${c.enabled()} ? '' : 'off'"></span>
        <form class="inline" method="post" th:action="@{'/ui/categories/' + ${c.id()} + '/toggle'}">
            <input type="hidden" name="enabled" th:value="${!c.enabled()}"/>
            <button type="submit" th:text="${c.enabled()} ? '비활성화' : '활성화'"></button>
        </form>
    </h2>

    <h3>키워드</h3>
    <ul>
        <li th:each="k : ${c.keywords()}">
            <span th:text="${k.keyword()}" th:classappend="${k.enabled()} ? '' : 'off'"></span>
            <form class="inline" method="post" th:action="@{'/ui/keywords/' + ${k.id()} + '/toggle'}">
                <input type="hidden" name="enabled" th:value="${!k.enabled()}"/>
                <button type="submit" th:text="${k.enabled()} ? '중지' : '재개'"></button>
            </form>
        </li>
    </ul>
    <form method="post" th:action="@{'/ui/categories/' + ${c.id()} + '/keywords'}">
        <input name="keyword" placeholder="새 키워드" required/>
        <button type="submit">키워드 추가</button>
    </form>

    <h3>규칙</h3>
    <form method="post" th:action="@{'/ui/categories/' + ${c.id()} + '/rule'}">
        <label>최소 팔로워 <input type="number" name="minFollowers"
                th:value="${c.rule() != null ? c.rule().minFollowers() : null}"/></label>
        <label>최대 팔로워 <input type="number" name="maxFollowers"
                th:value="${c.rule() != null ? c.rule().maxFollowers() : null}"/></label>
        <label>콘텐츠 유형
            <select name="contentTypes">
                <option th:each="f : ${filters}" th:value="${f}" th:text="${f}"
                        th:selected="${c.rule() != null && c.rule().contentTypes() == f}"></option>
            </select>
        </label>
        <button type="submit">규칙 저장</button>
    </form>
</div>
</body>
</html>
```

`templates/contents.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <title>celfit crawler — 수집 데이터</title>
    <style> body { font-family: sans-serif; margin: 2rem; } </style>
</head>
<body>
<nav th:replace="~{fragments/nav :: nav}"></nav>
<h1>수집 데이터</h1>

<form method="get" th:action="@{/ui/contents}">
    <select name="status">
        <option value="">전체</option>
        <option th:each="s : ${statuses}" th:value="${s}" th:text="${s}"
                th:selected="${status == s}"></option>
    </select>
    <button type="submit">필터</button>
</form>

<table border="1" cellpadding="4">
    <tr><th>id</th><th>short_code</th><th>유형</th><th>계정</th><th>업로드</th>
        <th>카테고리 키워드</th><th>상태</th><th>집계 시각</th></tr>
    <tr th:each="c : ${page.content}">
        <td><a th:href="@{'/ui/contents/' + ${c.id}}" th:text="${c.id}"></a></td>
        <td th:text="${c.shortCode}"></td>
        <td th:text="${c.contentType}"></td>
        <td th:text="${c.ownerUsername}"></td>
        <td th:text="${c.uploadedAt}"></td>
        <td th:text="${c.discoveryKeyword}"></td>
        <td th:text="${c.status}"></td>
        <td th:text="${c.aggregatedAt}"></td>
    </tr>
</table>
<p>
    <a th:if="${page.hasPrevious()}"
       th:href="@{/ui/contents(status=${status}, page=${page.number - 1})}">이전</a>
    <span th:text="${page.number + 1} + ' / ' + ${page.totalPages}"></span>
    <a th:if="${page.hasNext()}"
       th:href="@{/ui/contents(status=${status}, page=${page.number + 1})}">다음</a>
</p>
</body>
</html>
```

`templates/content-detail.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="utf-8"/>
    <title>celfit crawler — 콘텐츠 상세</title>
    <style>
        body { font-family: sans-serif; margin: 2rem; }
        pre { background: #f5f5f5; padding: 1rem; overflow-x: auto; max-height: 24rem; }
    </style>
</head>
<body>
<nav th:replace="~{fragments/nav :: nav}"></nav>
<h1 th:text="'콘텐츠 ' + ${content.shortCode}"></h1>
<table border="1" cellpadding="4">
    <tr><th>유형</th><td th:text="${content.contentType}"></td></tr>
    <tr><th>계정</th><td th:text="${content.ownerUsername}"></td></tr>
    <tr><th>업로드</th><td th:text="${content.uploadedAt}"></td></tr>
    <tr><th>상태</th><td th:text="${content.status}"></td></tr>
    <tr><th>발굴 키워드</th><td th:text="${content.discoveryKeyword}"></td></tr>
    <tr><th>링크</th><td><a th:href="'https://www.instagram.com/p/' + ${content.shortCode} + '/'"
                            th:text="'instagram.com/p/' + ${content.shortCode}" target="_blank"></a></td></tr>
</table>

<h2>게시물 상세 raw (+3일 확정)</h2>
<pre th:if="${detailJson}" th:text="${detailJson}"></pre>
<p th:unless="${detailJson}">아직 미수집</p>

<h2>댓글 (최근 100)</h2>
<table border="1" cellpadding="4" th:if="${!comments.isEmpty()}">
    <tr><th>작성자</th><th>내용</th><th>작성 시각</th></tr>
    <tr th:each="cm : ${comments}">
        <td th:text="${cm.writer}"></td>
        <td th:text="${cm.text}"></td>
        <td th:text="${cm.writtenAt}"></td>
    </tr>
</table>
<p th:if="${comments.isEmpty()}">댓글 없음</p>

<h2>작성자 프로필 raw</h2>
<pre th:if="${profileJson}" th:text="${profileJson}"></pre>
<p th:unless="${profileJson}">아직 미수집</p>
</body>
</html>
```

- [ ] **Step 5: 테스트 통과 확인 후 커밋**

Run: `./gradlew test --tests "com.celfit.crawler.ui.UiSmokeTest"`
Expected: PASS (5 tests)

Run: `./gradlew test` (전체 회귀)
Expected: PASS

```bash
git add -A && git commit -m "feat: UI — 카테고리·규칙 관리, 수집 데이터 열람 (raw JSON·댓글·프로필)"
```

---

### Task 16: README + 스모크 절차

**Files:**
- Create: `README.md`

- [ ] **Step 1: README 작성**

`README.md`:
```markdown
# celfit crawler

카테고리 키워드로 인스타그램 콘텐츠(릴스/피드)를 발굴하고, 업로드 3일 후 게시물
상세·댓글·작성자 프로필을 **Apify 응답 원형(raw)** 그대로 적재하는 수집 시스템.

- 설계: [docs/superpowers/specs/2026-07-07-crawler-design.md](../../specs/2026-07-07-crawler-design.md)
- 파이프라인: **discover**(발굴) → **qualify**(프로필+규칙 판정) → **aggregate**(+3일 상세·댓글)

## 실행

필요: Java 21, Docker Desktop(Postgres 자동 기동), Apify 계정 토큰.

```powershell
$env:APIFY_TOKEN = 'apify_api_...'
./gradlew bootRun
```

- UI: http://localhost:8080/ui (대시보드 · 잡 실행 · 카테고리/규칙 · 데이터 열람)
- DB: localhost:5433 / crawler / crawler (raw는 psql·DBeaver로 직접 조회 —
  generated column 덕에 `select writer, text from raw_comment` 식으로 일반 테이블처럼 보임)

## 테스트

```bash
./gradlew test          # 통합 테스트는 Testcontainers — Docker Desktop 필요
```

## 운영 절차 (초기 = 전부 수동)

1. UI → 카테고리·규칙: 카테고리 생성(예: 메이크업), 키워드 추가, 규칙(팔로워 범위 등) 설정
2. UI → 잡 실행: **discover** 실행 → 대시보드에서 PENDING 확인
3. **qualify** 실행 → QUALIFIED/EXCLUDED 분포 확인
4. 3일 후(백필이면 즉시 — 과거 게시물은 이미 3일 경과) **aggregate** 실행
5. 검증 끝나면 `application.yml`의 `crawler.schedule.enabled: true`로 자동화

REST로도 가능: `POST /admin/jobs/{discover|qualify|aggregate}?category=<id>`,
`GET /admin/runs`, `GET /admin/status`.

## 스모크 테스트 (실 Apify 과금 주의 — CI 금지)

첫 실 실행 전 **액터 id·입출력 필드 검증**이 목적. 최소 비용으로:

1. `application.yml`에서 `crawler.discover.results-limit: 5`, `crawler.aggregate.comments-per-post: 5`로 임시 축소
2. 키워드 1개짜리 카테고리로 discover → `crawl_run`에 SUCCEEDED + item_count 확인,
   Apify 콘솔에서 같은 run id 확인
3. `raw_discovery_post.payload`에 `shortCode`/`timestamp`/`ownerUsername`/`productType` 존재 확인
   (없으면 `DiscoveryItemParser`·generated column 필드명을 실제 응답에 맞게 수정)
4. qualify → `raw_profile.followers` 채워지는지 확인 (`followersCount` 필드명 검증)
5. aggregate(과거 게시물이라 즉시 도래) → `raw_post_detail`·`raw_comment` 적재 확인,
   댓글 아이템의 `postUrl`로 shortcode 매칭이 되는지 확인
6. limit 원복

## 주의

- **run-sync 금지** — 비동기 시작→폴링→dataset 수신만 사용 (장시간 실행 시 과금+유실 방지)
- 한글 키워드는 자동으로 `keywordSearch: true` 우회 (인스타 비로그인 해시태그 차단)
- 액터 id·필드명은 `apify/Actors.java`·`ActorInputs.java`·V1 마이그레이션에 모여 있음 —
  Apify 쪽 변경 시 이 세 곳만 수정
```

- [ ] **Step 2: 전체 테스트 + 기동 확인**

Run: `./gradlew test`
Expected: PASS (전체)

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

(선택 — Docker Desktop 켜져 있으면) Run: `APIFY_TOKEN=dummy-smoke ./gradlew bootRun` 백그라운드 기동 후 `curl -s http://localhost:8080/ui` → HTML 응답 확인 후 종료. dummy 토큰으로도 기동·UI·DB는 동작(실 Apify 호출만 실패).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: README — 실행·운영·스모크 절차"
```

---

## 계획 셀프리뷰 노트 (실행자 참고)

- **스펙 커버리지**: 실행 모델(§3)=Task 11·12·14, 데이터 모델(§4)=Task 2·3·4·13, 수집 흐름(§5)=Task 5–10, UI(§6)=Task 14·15, 구조·설정(§7)=Task 1, 테스트 전략(§8)=각 태스크 + 스모크는 Task 16 README 절차.
- **의도된 스펙 보강**: qualify의 `requalify` 파라미터(Task 9)는 스펙 §5 "재판정은 Apify 재호출 없음" 능력을 실제 진입점으로 구현한 것.
- **알려진 리스크 (막히면 여기부터)**:
  1. Apify 액터의 실제 입출력 필드명 — 계획은 hashtag-scraper의 `shortCode`/`productType`/`timestamp`/`ownerUsername`, comment-scraper의 `postUrl`, profile-scraper의 `followersCount`를 가정. 스모크 테스트(Task 16)에서 검증하고 어긋나면 `DiscoveryItemParser`·`AggregateJob`의 매칭 키·generated column을 수정.
  2. SB4 Jackson 패키지(`tools.jackson` vs `com.fasterxml.jackson`) — 컴파일 에러가 나는 쪽으로 교체. ObjectMapper를 쓰는 곳 전부 해당: Task 6(ApifyClient·ApifyClientTest), Task 13(CategoryApiTest), Task 15(UiController pretty JSON). 세 곳의 import를 같은 패키지로 통일할 것.
  3. `@TestConfiguration`의 동명 빈 오버라이드는 `allow-bean-definition-overriding: true`(Task 11 Step 1)가 선행되어야 함.

