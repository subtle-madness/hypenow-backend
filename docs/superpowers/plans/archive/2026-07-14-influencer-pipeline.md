# 인플루언서 중심 파이프라인 전환 구현 계획

> 상태: ✅ 구현됨 — 단 CollectJob 세부(6개월 백필·피드/릴스 2스트림 커서 페이지네이션·taken_at 컷오프)는 07-15 collect 분리 설계가 재정의. 스펙: [specs/2026-07-14-influencer-pipeline-design.md](../../specs/archive/2026-07-14-influencer-pipeline-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 게시물 중심 파이프라인(discover→qualify→aggregate)을 인플루언서 중심(discover→qualify→collect)으로 전환한다 — 판정은 인플루언서 단위(전역 팔로워 범위), 수집은 인플루언서별 6개월 백필(피드+릴스 열거) + 추적 방문.

**Architecture:** raw는 응답 원형 그대로 저장(`source` 태그), 제어 필드는 저장 후 추출해 실컬럼에 기록. 열거는 HikerAPI 두 스트림(`/gql/user/medias?flat=true` + `/v2/user/clips`)을 커서 페이지네이션으로 페이지 원형째 `raw_media_page`에 저장하고 shortCode dedup. 열거=상세이므로 게시물별 추가 방문은 댓글(self GraphQL)뿐.

**Tech Stack:** Java 21, Spring Boot(JPA/Flyway/Thymeleaf), PostgreSQL 17, Testcontainers, HikerAPI, 기존 hexagonal 구조(`crawling`/`content`/`settings`/`dashboard`).

**스펙:** `docs/superpowers/specs/2026-07-14-influencer-pipeline-design.md` (승인됨)

## Global Constraints

- 브랜치: `feat/influencer-pipeline` (이미 생성됨, feat/direct-comment-crawler에서 분기)
- raw payload는 **변형 금지** — 응답 파싱 결과(Map)를 그대로 jsonb로. envelope(`_raw*` 병합) 생성 금지
- 이력 테이블(`influencer_discovery`, raw 전부)은 살아있는 설정을 id 참조하지 않는다 — 키워드는 **텍스트 스냅샷**
- Hiker gql flat 응답의 숫자 필드는 `1l`/`1f` 접두사(`1ltaken_at`) — 추출기는 3형(`key`, `1l`+key, `1f`+key)을 순서대로 조회
- 고정 게시물(`timeline_pinned_user_ids`/`clips_tab_pinned_user_ids` 비어있지 않음)은 컷오프 중단 판단에서 제외
- 판정 팔로워 범위는 전역 하나: `qualify.min-followers`(기본 3000) ~ `qualify.max-followers`(기본 50000), app_setting으로 무중단 변경
- 첫 방문 백필 `collect.backfill-months`(기본 6), 추적 방문 `collect.track-window-days`(기본 30)
- 기존 데이터 보존: account 4,176행 → influencer로, 발굴 raw 전부 유지, 기존 raw payload는 `source='LEGACY_ENVELOPE'`
- 커밋 메시지는 기존 컨벤션(한국어, `feat:`/`fix:`/`docs:` 접두사), 각 태스크 끝마다 커밋
- 테스트: `./gradlew test` (Testcontainers — Docker Desktop 필요). 단위 테스트는 plain JUnit

---

### Task 1: Flyway V8 마이그레이션 (스키마 전환 + 데이터 이관)

**Files:**
- Create: `src/main/resources/db/migration/V8__influencer_pipeline.sql`
- Test: `src/test/java/com/celfit/crawler/InfluencerMigrationTest.java`

**Interfaces:**
- Produces: 테이블 `search_keyword`, `influencer`(account 개명), `influencer_discovery`, `raw_media_page`; `content`·`crawl_run`·raw 4테이블 개편. 이후 모든 태스크의 엔티티가 이 스키마에 매핑됨
- 컬럼 확정: `influencer(id, username, status, followers, last_profiled_at, first_collected_at, last_collected_at)`, `content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, collected_at, collect_attempts)`, `crawl_run(id, job, trigger_type, keyword, target_username, actor_id, apify_run_id, status, item_count, request_count, error_message, started_at, finished_at)`

- [ ] **Step 1: 마이그레이션 검증 테스트 작성 (실패 확인용)**

Flyway를 V7까지만 돌리고 구스키마 픽스처를 넣은 뒤 V8을 적용해 이관 결과를 검증한다.

```java
package com.celfit.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class InfluencerMigrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateWithFixture() {
        DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds);

        // 1) V7까지 적용 (구스키마)
        Flyway.configure().dataSource(ds).target("7").load().migrate();

        // 2) 구스키마 픽스처 — 카테고리/키워드/계정/콘텐츠/프로필
        jdbc.update("INSERT INTO category(id, name, enabled) VALUES (2, '뷰티', true)");
        jdbc.update("""
            INSERT INTO category_keyword(category_id, keyword, enabled, subcategory, main_group)
            VALUES (2, '쿠션', true, '베이스메이크업', '메이크업'),
                   (2, '립밤', false, '립메이크업', '메이크업')""");
        jdbc.update("INSERT INTO collection_rule(category_id, min_followers, max_followers, content_types) VALUES (2, 3000, 50000, 'ALL')");
        jdbc.update("INSERT INTO account(id, username, last_profiled_at) VALUES (10, 'alice', now()), (11, 'bob', NULL)");
        jdbc.update("""
            INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id,
                                discovery_keyword, subcategory, main_group, status, first_seen_at,
                                aggregated_at, aggregate_attempts, ad_marked)
            VALUES (100, 'SC_A', 'REELS', 'alice', now(), 2, '쿠션', '베이스메이크업', '메이크업',
                    'AGGREGATED', now(), now(), 1, true),
                   (101, 'SC_B', 'FEED', 'bob', now(), 2, '립밤', '립메이크업', '메이크업',
                    'EXCLUDED', now(), NULL, 0, false)""");
        jdbc.update("""
            INSERT INTO crawl_run(id, job, trigger_type, category_id, keyword, actor_id, status, started_at)
            VALUES (500, 'QUALIFY', 'MANUAL', 2, NULL, 'test-actor', 'SUCCEEDED', now())""");
        jdbc.update("""
            INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at)
            VALUES (10, 500, '{"username":"alice","followersCount":12345}'::jsonb, now())""");

        // 3) V8 적용
        Flyway.configure().dataSource(ds).load().migrate();
    }

    @Test
    void 키워드가_평탄화되어_이관된다() {
        assertThat(jdbc.queryForList("SELECT keyword FROM search_keyword ORDER BY keyword", String.class))
                .containsExactly("립밤", "쿠션");
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM search_keyword WHERE keyword='립밤'", Boolean.class)).isFalse();
    }

    @Test
    void account가_influencer로_개명되고_팔로워가_복사된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM influencer", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM influencer WHERE username='alice'", String.class)).isEqualTo("DISCOVERED");
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM influencer WHERE username='alice'", Long.class)).isEqualTo(12345L);
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM influencer WHERE username='bob'", Long.class)).isNull();
    }

    @Test
    void 발굴_출처가_content에서_역산된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM influencer_discovery", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT keyword FROM influencer_discovery d
                JOIN influencer i ON i.id = d.influencer_id WHERE i.username='alice'""", String.class))
                .isEqualTo("쿠션");
    }

    @Test
    void content가_인플루언서_소유로_재편되고_상태가_재매핑된다() {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM content WHERE short_code='SC_A'", String.class)).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM content WHERE short_code='SC_B'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT i.username FROM content c JOIN influencer i ON i.id = c.influencer_id
                WHERE c.short_code='SC_A'""", String.class)).isEqualTo("alice");
        // 분류 컬럼·ad_marked 제거 확인
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='content' AND column_name IN
                ('category_id','discovery_keyword','subcategory','main_group','ad_marked','qualified_at')""",
                Long.class)).isZero();
    }

    @Test
    void raw_테이블에_source가_붙고_generated가_실컬럼이_된다() {
        assertThat(jdbc.queryForObject(
                "SELECT source FROM raw_profile LIMIT 1", String.class)).isEqualTo("LEGACY_ENVELOPE");
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM raw_profile LIMIT 1", Long.class)).isEqualTo(12345L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='raw_profile' AND column_name='followers' AND is_generated='ALWAYS'""",
                Long.class)).isZero();
    }

    @Test
    void 구_테이블은_삭제되고_raw_media_page가_생긴다() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name IN ('category','category_keyword','collection_rule')""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables WHERE table_name='raw_media_page'""",
                Long.class)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests InfluencerMigrationTest`
Expected: FAIL — `Migration ... V8 not found` 혹은 테이블 미존재 오류

- [ ] **Step 3: V8 마이그레이션 작성**

```sql
-- V8__influencer_pipeline.sql
-- 인플루언서 중심 전환: 스키마 + 같은 DB 안 데이터 이관 (스펙 2026-07-14)

-- ===== 1) search_keyword (분류 평탄화) =====
CREATE TABLE search_keyword (
    id         bigserial   PRIMARY KEY,
    keyword    text        NOT NULL UNIQUE,
    enabled    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO search_keyword (keyword, enabled)
SELECT keyword, bool_or(enabled) FROM category_keyword GROUP BY keyword;

-- ===== 2) account → influencer =====
ALTER TABLE account RENAME TO influencer;
ALTER SEQUENCE account_id_seq RENAME TO influencer_id_seq;
ALTER TABLE influencer RENAME CONSTRAINT account_username_key TO influencer_username_key;
ALTER TABLE influencer
    ADD COLUMN status             text NOT NULL DEFAULT 'DISCOVERED',
    ADD COLUMN followers          bigint,
    ADD COLUMN first_collected_at timestamptz,
    ADD COLUMN last_collected_at  timestamptz;
CREATE INDEX idx_influencer_status ON influencer(status);

-- 프로필 있는 계정은 최신 raw_profile 팔로워 복사 (판정은 새 qualify가 재수행)
UPDATE influencer i SET followers = rp.followers
FROM (SELECT DISTINCT ON (account_id) account_id, (payload->>'followersCount')::bigint AS followers
      FROM raw_profile ORDER BY account_id, captured_at DESC) rp
WHERE rp.account_id = i.id;

ALTER TABLE raw_profile RENAME COLUMN account_id TO influencer_id;
ALTER INDEX idx_raw_profile_account RENAME TO idx_raw_profile_influencer;

-- ===== 3) influencer_discovery (발굴 출처, 키워드는 텍스트 스냅샷) =====
CREATE TABLE influencer_discovery (
    id                         bigserial   PRIMARY KEY,
    influencer_id              bigint      NOT NULL REFERENCES influencer(id),
    keyword                    text        NOT NULL,
    discovered_post_short_code text,
    discovered_at              timestamptz NOT NULL
);
CREATE INDEX idx_influencer_discovery_influencer ON influencer_discovery(influencer_id);
INSERT INTO influencer_discovery (influencer_id, keyword, discovered_post_short_code, discovered_at)
SELECT i.id, c.discovery_keyword, c.short_code, c.first_seen_at
FROM content c JOIN influencer i ON i.username = c.owner_username;

-- ===== 4) content 재편 =====
ALTER TABLE content
    ADD COLUMN influencer_id    bigint REFERENCES influencer(id),
    ADD COLUMN collected_at     timestamptz,
    ADD COLUMN collect_attempts integer NOT NULL DEFAULT 0;
UPDATE content c SET influencer_id = i.id FROM influencer i WHERE i.username = c.owner_username;
ALTER TABLE content ALTER COLUMN influencer_id SET NOT NULL;
UPDATE content SET collected_at = aggregated_at, collect_attempts = aggregate_attempts;
-- 상태 재매핑: 판정 상태는 인플루언서로 이동, 게시물은 수집 여부만
UPDATE content SET status = CASE
    WHEN status = 'AGGREGATED' THEN 'COLLECTED'
    WHEN status IN ('GONE', 'FAILED') THEN 'FAILED'
    ELSE 'PENDING' END;
ALTER TABLE content
    DROP COLUMN category_id,
    DROP COLUMN discovery_keyword,
    DROP COLUMN subcategory,
    DROP COLUMN main_group,
    DROP COLUMN ad_marked,
    DROP COLUMN qualified_at,
    DROP COLUMN aggregated_at,
    DROP COLUMN aggregate_attempts;
CREATE INDEX idx_content_influencer ON content(influencer_id);

-- ===== 5) crawl_run: 카테고리 제거, 대상 인플루언서 기록 =====
ALTER TABLE crawl_run
    DROP COLUMN category_id,
    ADD COLUMN target_username text;

-- ===== 6) raw 원형화: source 태그 + generated → 실컬럼 =====
-- 기존 payload는 정규화 envelope이므로 LEGACY_ENVELOPE로 표시. 새 코드는 source를 명시 저장.
ALTER TABLE raw_discovery_post ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_post_detail    ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_comment        ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_profile        ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_discovery_post ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_post_detail    ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_comment        ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_profile        ALTER COLUMN source DROP DEFAULT;

-- generated column을 실컬럼으로 (drop → add → 기존값 backfill). 추출 실패 시 NULL 허용이 목적.
ALTER TABLE raw_discovery_post DROP COLUMN short_code, DROP COLUMN caption;
ALTER TABLE raw_discovery_post ADD COLUMN short_code text, ADD COLUMN caption text;
UPDATE raw_discovery_post SET short_code = payload->>'shortCode', caption = payload->>'caption';

ALTER TABLE raw_post_detail DROP COLUMN short_code, DROP COLUMN caption, DROP COLUMN likes,
                            DROP COLUMN comments_count, DROP COLUMN video_play_count;
ALTER TABLE raw_post_detail ADD COLUMN short_code text, ADD COLUMN caption text, ADD COLUMN likes bigint,
                            ADD COLUMN comments_count bigint, ADD COLUMN video_play_count bigint;
UPDATE raw_post_detail SET short_code = payload->>'shortCode', caption = payload->>'caption',
    likes = (payload->>'likesCount')::bigint, comments_count = (payload->>'commentsCount')::bigint,
    video_play_count = (payload->>'videoPlayCount')::bigint;

ALTER TABLE raw_comment DROP COLUMN writer, DROP COLUMN text, DROP COLUMN written_at;
ALTER TABLE raw_comment ADD COLUMN writer text, ADD COLUMN text text, ADD COLUMN written_at text;
UPDATE raw_comment SET writer = payload->>'ownerUsername', text = payload->>'text',
    written_at = payload->>'timestamp';

ALTER TABLE raw_profile DROP COLUMN username, DROP COLUMN followers;
ALTER TABLE raw_profile ADD COLUMN username text, ADD COLUMN followers bigint;
UPDATE raw_profile SET username = payload->>'username', followers = (payload->>'followersCount')::bigint;

-- ===== 7) raw_media_page (열거 페이지 원형) =====
CREATE TABLE raw_media_page (
    id            bigserial   PRIMARY KEY,
    influencer_id bigint      NOT NULL REFERENCES influencer(id),
    crawl_run_id  bigint      NOT NULL REFERENCES crawl_run(id),
    source        text        NOT NULL,
    payload       jsonb       NOT NULL,
    captured_at   timestamptz NOT NULL
);
CREATE INDEX idx_raw_media_page_influencer ON raw_media_page(influencer_id);

-- ===== 8) 구 테이블 정리 =====
DROP TABLE collection_rule;
DROP TABLE category_keyword;
DROP TABLE category;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests InfluencerMigrationTest`
Expected: PASS (6개 테스트)

주의: 이 시점에 기존 엔티티(Category 등)가 스키마와 어긋나 **다른 통합 테스트는 깨진다** — 정상. Task 2~10이 코드를 따라잡는다. 이 태스크에서는 InfluencerMigrationTest만 돌린다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration/V8__influencer_pipeline.sql src/test/java/com/celfit/crawler/InfluencerMigrationTest.java
git commit -m "feat: V8 인플루언서 중심 스키마 전환 + 데이터 이관 마이그레이션"
```

---

### Task 2: 도메인·리포지토리 재편

**Files:**
- Create: `crawling/domain/Influencer.java`, `crawling/domain/InfluencerStatus.java`, `crawling/domain/InfluencerDiscovery.java`, `crawling/domain/RawMediaPage.java`, `crawling/domain/RawSource.java`, `content/domain/SearchKeyword.java`
- Create(port): `crawling/application/port/out/InfluencerRepository.java`, `crawling/application/port/out/InfluencerDiscoveryRepository.java`, `crawling/application/port/out/RawMediaPageRepository.java`, `content/application/port/out/SearchKeywordRepository.java`
- Delete: `crawling/domain/Account.java`, `crawling/application/port/out/AccountRepository.java`, `content/domain/Category.java`, `content/domain/CategoryKeyword.java`, `content/domain/CollectionRule.java`, `content/domain/ContentTypeFilter.java`, 해당 리포지토리 3개
- Modify: `content/domain/Content.java`, `content/domain/ContentStatus.java`, `crawling/domain/CrawlRun.java`, `crawling/domain/RawProfile.java`, `crawling/domain/RawComment.java`, `crawling/domain/RawDiscoveryPost.java`, `crawling/domain/RawPostDetail.java`, `crawling/domain/JobName.java`
- Test: `src/test/java/com/celfit/crawler/crawling/domain/RawEntityTest.java` (수정)

(모든 경로는 `src/main/java/com/celfit/crawler/` 아래)

**Interfaces:**
- Produces (이후 태스크가 의존하는 시그니처):
  - `Influencer(String username)` 생성자, getter/setter: `getId(), getUsername(), getStatus(), setStatus(InfluencerStatus), getFollowers(), setFollowers(Long), getLastProfiledAt(), setLastProfiledAt(Instant), getFirstCollectedAt(), setFirstCollectedAt(Instant), getLastCollectedAt(), setLastCollectedAt(Instant)`
  - `InfluencerStatus { DISCOVERED, QUALIFIED, EXCLUDED }`
  - `ContentStatus { PENDING, COLLECTED, FAILED }`
  - `Content(String shortCode, ContentType contentType, String ownerUsername, Long influencerId, Instant uploadedAt, Instant firstSeenAt)` + `getCollectAttempts()/setCollectAttempts(int)/setCollectedAt(Instant)`
  - `RawSource { LEGACY_ENVELOPE, APIFY_ACTOR, HIKER_MOBILE, HIKER_HASHTAG, SELF_GQL, HIKER_GQL_MEDIAS, HIKER_V2_CLIPS }`
  - `InfluencerDiscovery(Long influencerId, String keyword, String discoveredPostShortCode, Instant discoveredAt)`
  - `RawMediaPage(Long influencerId, Long crawlRunId, RawSource source, Map<String,Object> payload, Instant capturedAt)`
  - raw 엔티티 생성자에 `RawSource source` 파라미터 추가: `RawProfile(Long influencerId, Long crawlRunId, RawSource source, Map<String,Object> payload, Instant capturedAt)` (comment/discovery/detail 동일 패턴). **실컬럼(short_code·followers 등)은 이 태스크에서 생성자가 payload로부터 채우지 않는다** — Task 4의 추출기가 세터로 채움: `RawProfile.setUsername(String)/setFollowers(Long)`, `RawDiscoveryPost.setShortCode(String)/setCaption(String)` 등
  - `InfluencerRepository`: `Optional<Influencer> findByUsername(String)`, `List<Influencer> findByStatus(InfluencerStatus, Pageable)`, `long countByStatus(InfluencerStatus)`, `List<Influencer> findCollectTargets(Pageable)` — `@Query("select i from Influencer i where i.status = 'QUALIFIED' order by case when i.firstCollectedAt is null then 0 else 1 end, i.lastCollectedAt asc nulls first")`
  - `SearchKeywordRepository`: `List<SearchKeyword> findByEnabledTrue()`, `Optional<SearchKeyword> findByKeyword(String)`, `List<SearchKeyword> findAllByOrderByKeywordAsc()`
  - `ContentRepository` 추가 메서드: `List<Content> findByInfluencerIdAndStatus(Long, ContentStatus)`, 기존 `findByShortCode` 유지, `findDue`/`findByStatus(카테고리 관련)` 제거
  - `CrawlRun(JobName job, TriggerType trigger, String keyword, String targetUsername, String actorId, Instant startedAt)` — categoryId 파라미터 제거
  - `JobName { DISCOVER, QUALIFY, COLLECT }` (AGGREGATE 제거)

- [ ] **Step 1: 엔티티 작성** — Influencer는 Account를 개명·확장(파일 이동), 나머지 신규. 대표 코드:

```java
// crawling/domain/Influencer.java
package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "influencer")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Influencer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InfluencerStatus status = InfluencerStatus.DISCOVERED;

    /** 최신 팔로워 수 — qualify 판정 근거. raw_profile 원형에서 추출해 복사. */
    private Long followers;

    @Column(name = "last_profiled_at")
    private Instant lastProfiledAt;

    /** 첫 6개월 백필 완료 시각. NULL이면 백필 대상. */
    @Column(name = "first_collected_at")
    private Instant firstCollectedAt;

    @Column(name = "last_collected_at")
    private Instant lastCollectedAt;

    public Influencer(String username) {
        this.username = username;
    }
}
```

```java
// crawling/domain/InfluencerDiscovery.java
package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발굴 출처 이력(append-only). keyword는 텍스트 스냅샷 — search_keyword id 참조 금지. */
@Entity
@Table(name = "influencer_discovery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerDiscovery {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(nullable = false)
    private String keyword;

    @Column(name = "discovered_post_short_code")
    private String discoveredPostShortCode;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    public InfluencerDiscovery(Long influencerId, String keyword,
                               String discoveredPostShortCode, Instant discoveredAt) {
        this.influencerId = influencerId;
        this.keyword = keyword;
        this.discoveredPostShortCode = discoveredPostShortCode;
        this.discoveredAt = discoveredAt;
    }
}
```

```java
// crawling/domain/RawMediaPage.java — RawProfile과 같은 JdbcTypeCode(SqlTypes.JSON) 패턴 사용
package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 게시물 열거 응답의 페이지 단위 원형. 게시물별 필드는 content로 추출된다. */
@Entity
@Table(name = "raw_media_page")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawMediaPage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RawSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public RawMediaPage(Long influencerId, Long crawlRunId, RawSource source,
                        Map<String, Object> payload, Instant capturedAt) {
        this.influencerId = influencerId;
        this.crawlRunId = crawlRunId;
        this.source = source;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
```

```java
// content/domain/SearchKeyword.java
package com.celfit.crawler.content.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 발굴 입력 키워드 — 분류 계층 없음. 이름 수정 없음(수정 = 삭제 + 추가). */
@Entity
@Table(name = "search_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchKeyword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String keyword;

    @Setter
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SearchKeyword(String keyword, Instant createdAt) {
        this.keyword = keyword;
        this.createdAt = createdAt;
    }
}
```

기존 raw 엔티티 4종에 공통 패턴 적용 (RawProfile 예 — 나머지 동일):
`@Enumerated(EnumType.STRING) @Column(nullable = false) private RawSource source;` 필드 추가,
생성자 3번째 파라미터로 `RawSource source` 삽입, 실컬럼은 `@Setter` 붙은 nullable 필드
(`private String username; private Long followers;` — DB generated가 아니므로 insertable).
Content·ContentStatus·CrawlRun·JobName은 Interfaces 블록의 시그니처대로 수정
(Content에서 categoryId/discoveryKeyword/subcategory/mainGroup/adMarked/qualifiedAt/aggregatedAt 필드 제거,
influencerId/collectedAt/collectAttempts 추가).

- [ ] **Step 2: 리포지토리 작성** — Interfaces 블록의 시그니처대로. Account/Category 계열 삭제로 깨지는 참조는 컴파일 에러 목록을 따라 다음 태스크 대상 파일에서 임시 주석이 아니라 **이 태스크에서 함께 삭제/수정**한다 (이 태스크가 끝나면 컴파일이 되어야 함). 깨지는 파일 중 잡·컨트롤러는 Task 6~10에서 재작성되므로, 여기서는 최소 수정: `DiscoverJob`·`QualifyJob`·`AggregateJob`·`CategoryService`·`CategoryAdminController`·`UiCategoryController`·`StatusService`의 **깨진 부분은 이 태스크에서 함께 삭제**(AggregateJob·CategoryService·컨트롤러 2종은 파일째 삭제, DiscoverJob·QualifyJob·StatusService는 빈 구현으로 재작성해 Task 6~7·10에서 채움). ScheduleRunner·JobService의 AGGREGATE 분기도 제거

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava -x test`
Expected: BUILD SUCCESSFUL (깨진 테스트 파일은 함께 삭제: AggregateJob·Category·CollectionRule·DiscoveryItemParser 관련 — Task 6~8에서 새 테스트로 대체)

- [ ] **Step 4: RawEntityTest 수정·실행** — raw 엔티티 생성자에 source가 들어갔는지, RawMediaPage가 저장되는지 기존 패턴대로 검증

Run: `./gradlew test --tests RawEntityTest --tests InfluencerMigrationTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat: 도메인 재편 — Influencer·SearchKeyword·InfluencerDiscovery·RawMediaPage, 분류 계열 제거"
```

---

### Task 3: 설정 — collect/qualify 파라미터

**Files:**
- Create: `common/config/CollectProperties.java`
- Modify: `common/config/QualifyProperties.java`, `settings/application/service/SettingsService.java`, `src/main/resources/application.yml`, `common/config/CrawlerConfig.java`(프로퍼티 등록 확인)
- Delete: `common/config/AggregateProperties.java`
- Test: `src/test/java/com/celfit/crawler/settings/adapter/in/web/SettingsApiTest.java` (수정)

**Interfaces:**
- Produces: `SettingsService`에 `int qualifyMinFollowers()`, `int qualifyMaxFollowers()`, `int backfillMonths()`, `int trackWindowDays()`, `int collectBatchLimit()`, `int commentsPerPost()`, `int maxAttempts()` (기존 `delayDays()`·`batchLimit()`·`chunkSize()`와 aggregate 키 제거)
- 키: `qualify.min-followers`, `qualify.max-followers`, `qualify.batch-limit`(유지), `collect.backfill-months`, `collect.track-window-days`, `collect.batch-limit`, `collect.comments-per-post`, `collect.max-attempts`, `discover.results-limit`(유지)

- [ ] **Step 1: SettingsApiTest에 새 키 왕복 테스트 추가** (기존 테스트 패턴 그대로 — PUT 후 GET으로 effective 확인, aggregate 키는 404/400 확인으로 변경)
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests SettingsApiTest` Expected: FAIL
- [ ] **Step 3: 구현**

```java
// common/config/CollectProperties.java
package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler.collect")
public record CollectProperties(int backfillMonths, int trackWindowDays, int batchLimit,
                                int commentsPerPost, int maxAttempts) {}
```

```java
// common/config/QualifyProperties.java — 필드 추가
@ConfigurationProperties(prefix = "crawler.qualify")
public record QualifyProperties(int batchLimit, int minFollowers, int maxFollowers) {}
```

application.yml (`crawler:` 아래 aggregate 블록 대체):

```yaml
  qualify:
    batch-limit: 500
    min-followers: 3000     # 전역 판정 범위 — collection_rule 대체
    max-followers: 50000
  collect:
    backfill-months: 6      # 첫 방문 수집 범위
    track-window-days: 30   # 추적 방문 수집 범위 (데이터 보고 조정)
    batch-limit: 10         # 실행 1회당 방문 인플루언서 수
    comments-per-post: 30
    max-attempts: 3
```

SettingsService: KEYS 목록·switch를 새 키로 교체(패턴은 기존과 동일 — 키 상수, `effective()`, `defaultValue()`). `validate()`에 `qualify.max-followers >= qualify.min-followers`는 넣지 않는다(개별 키 단위 업데이트라 순서 역전이 일시적으로 가능 — 판정 시점에 min>max면 아무도 통과 못할 뿐 안전).

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests SettingsApiTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: qualify 전역 팔로워 범위·collect 설정 키 추가, aggregate 설정 제거"`

---

### Task 4: 원형 추출기 — MediaItemExtractor·ProfileExtractor

**Files:**
- Create: `crawling/application/service/MediaItemExtractor.java`, `crawling/application/service/ProfileExtractor.java`
- Test: `crawling/application/service/MediaItemExtractorTest.java`, `crawling/application/service/ProfileExtractorTest.java`

**Interfaces:**
- Produces:
  - `record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned)`
  - `MediaItemExtractor.extract(Map<String,Object> payload, RawSource source)` → `List<MediaItem>` — `HIKER_GQL_MEDIAS`는 `payload.items[]`, `HIKER_V2_CLIPS`는 `payload.response.items[].media` 언랩. 필수(code) 결손 아이템은 건너뜀(원형은 이미 저장돼 있으므로 유실 아님)
  - `MediaItemExtractor.nextCursor(Map<String,Object> payload, RawSource source)` → `String` (null=끝): GQL_MEDIAS는 `more_available`이 true일 때 `profile_grid_items_cursor`, V2_CLIPS는 `next_page_id`
  - `ProfileExtractor.followers(Map payload, RawSource source)` → `Long`, `ProfileExtractor.userId(...)` → `String`, `ProfileExtractor.username(...)` → `String` — SELF_GQL은 `data.user.edge_followed_by.count`/`data.user.id`, HIKER_MOBILE은 `user.follower_count`/`user.pk`, LEGACY_ENVELOPE·APIFY_ACTOR는 `followersCount`/`userId` 평면 키
  - 숫자 키 조회 헬퍼: `key` → `1l`+key → `1f`+key 순서 (Global Constraints)

- [ ] **Step 1: 실패 테스트 작성** — 실측 응답 축약 픽스처로:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaItemExtractorTest {

    @Test
    void gql_flat_페이지에서_1l접두사_시각과_고정여부를_추출한다() {
        Map<String, Object> payload = Map.of(
                "items", List.of(
                        Map.of("code", "PIN1", "1ltaken_at", 1745000000L, "product_type", "clips",
                                "timeline_pinned_user_ids", List.of("74969123775")),
                        Map.of("code", "NEW1", "1ltaken_at", 1783474981L, "product_type", "clips",
                                "timeline_pinned_user_ids", List.of()),
                        Map.of("code", "CAR1", "1ltaken_at", 1783474000L, "product_type", "carousel_container")),
                "more_available", true,
                "profile_grid_items_cursor", "CURSOR_X");

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_GQL_MEDIAS);

        assertThat(items).hasSize(3);
        assertThat(items.get(0).pinned()).isTrue();
        assertThat(items.get(1)).isEqualTo(new MediaItemExtractor.MediaItem(
                "NEW1", Instant.ofEpochSecond(1783474981L), ContentType.REELS, false));
        assertThat(items.get(2).type()).isEqualTo(ContentType.FEED);
        assertThat(MediaItemExtractor.nextCursor(payload, RawSource.HIKER_GQL_MEDIAS)).isEqualTo("CURSOR_X");
    }

    @Test
    void v2_clips_페이지는_response_items_media를_언랩한다() {
        Map<String, Object> payload = Map.of(
                "response", Map.of("items", List.of(
                        Map.of("media", Map.of("code", "CLIP1", "taken_at", 1783223195L,
                                "product_type", "clips")))),
                "next_page_id", "PAGE2");

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS);

        assertThat(items).containsExactly(new MediaItemExtractor.MediaItem(
                "CLIP1", Instant.ofEpochSecond(1783223195L), ContentType.REELS, false));
        assertThat(MediaItemExtractor.nextCursor(payload, RawSource.HIKER_V2_CLIPS)).isEqualTo("PAGE2");
    }

    @Test
    void 커서가_없거나_more_available_false면_null() {
        assertThat(MediaItemExtractor.nextCursor(
                Map.of("more_available", false, "profile_grid_items_cursor", "X"),
                RawSource.HIKER_GQL_MEDIAS)).isNull();
        assertThat(MediaItemExtractor.nextCursor(Map.of(), RawSource.HIKER_V2_CLIPS)).isNull();
    }

    @Test
    void code_없는_아이템은_건너뛴다() {
        Map<String, Object> payload = Map.of("items", List.of(Map.of("1ltaken_at", 1L)));
        assertThat(MediaItemExtractor.extract(payload, RawSource.HIKER_GQL_MEDIAS)).isEmpty();
    }
}
```

ProfileExtractorTest도 같은 요령: SELF_GQL(`data.user.edge_followed_by.count`), HIKER_MOBILE(`user.follower_count`·`user.pk`), LEGACY_ENVELOPE(`followersCount` 평면) 3케이스 + 결손 시 null.

- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests MediaItemExtractorTest --tests ProfileExtractorTest` Expected: FAIL (클래스 없음)
- [ ] **Step 3: 구현**

```java
// crawling/application/service/MediaItemExtractor.java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 저장된 열거 페이지 원형에서 제어 필드만 추출. 원형은 이미 raw_media_page에 있으므로
 * 여기서의 결손·실패는 데이터 유실이 아니다(해당 아이템만 건너뜀).
 * Hiker gql flat은 숫자 필드에 1l/1f 접두사를 붙인다 — get()이 3형을 순서대로 조회.
 */
public final class MediaItemExtractor {

    public record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned) {}

    public static List<MediaItem> extract(Map<String, Object> payload, RawSource source) {
        List<MediaItem> out = new ArrayList<>();
        for (Object o : items(payload, source)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = unwrapMedia(raw);
            String code = m.get("code") instanceof String s && !s.isBlank() ? s : null;
            Long takenAt = asLong(get(m, "taken_at"));
            if (code == null || takenAt == null) continue;
            ContentType type = "clips".equals(m.get("product_type"))
                    ? ContentType.REELS : ContentType.FEED;
            boolean pinned = nonEmptyList(m.get("timeline_pinned_user_ids"))
                    || nonEmptyList(m.get("clips_tab_pinned_user_ids"));
            out.add(new MediaItem(code, Instant.ofEpochSecond(takenAt), type, pinned));
        }
        return out;
    }

    /** 다음 페이지 커서. null이면 끝. */
    public static String nextCursor(Map<String, Object> payload, RawSource source) {
        return switch (source) {
            case HIKER_GQL_MEDIAS -> Boolean.TRUE.equals(payload.get("more_available"))
                    && payload.get("profile_grid_items_cursor") instanceof String s && !s.isBlank()
                    ? s : null;
            case HIKER_V2_CLIPS -> payload.get("next_page_id") instanceof String s && !s.isBlank()
                    ? s : null;
            default -> null;
        };
    }

    private static List<?> items(Map<String, Object> payload, RawSource source) {
        Object items = switch (source) {
            case HIKER_GQL_MEDIAS -> payload.get("items");
            case HIKER_V2_CLIPS -> payload.get("response") instanceof Map<?, ?> r
                    ? r.get("items") : null;
            default -> null;
        };
        return items instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapMedia(Map<?, ?> item) {
        return item.get("media") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : (Map<String, Object>) item;
    }

    /** hiker flat 접두사 대응: key → 1l+key → 1f+key. */
    static Object get(Map<String, Object> m, String key) {
        if (m.containsKey(key)) return m.get(key);
        if (m.containsKey("1l" + key)) return m.get("1l" + key);
        return m.get("1f" + key);
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static boolean nonEmptyList(Object v) {
        return v instanceof List<?> l && !l.isEmpty();
    }

    private MediaItemExtractor() {}
}
```

```java
// crawling/application/service/ProfileExtractor.java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.Map;

/** 프로필 응답 원형에서 제어 필드(followers·userId·username) 추출. 소스별 경로. */
public final class ProfileExtractor {

    public static Long followers(Map<String, Object> payload, RawSource source) {
        return switch (source) {
            case SELF_GQL -> asLong(dig(payload, "data", "user", "edge_followed_by", "count"));
            case HIKER_MOBILE -> asLong(dig(user(payload), "follower_count"));
            default -> asLong(payload.get("followersCount"));  // LEGACY_ENVELOPE·APIFY_ACTOR
        };
    }

    public static String userId(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "id");
            case HIKER_MOBILE -> {
                Object pk = dig(user(payload), "pk");
                yield pk != null ? pk : dig(user(payload), "id");
            }
            default -> payload.get("userId");
        };
        return v == null ? null : String.valueOf(v);
    }

    public static String username(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "username");
            case HIKER_MOBILE -> dig(user(payload), "username");
            default -> payload.get("username");
        };
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, Object> user(Map<String, Object> payload) {
        return payload.get("user") instanceof Map<?, ?> u
                ? castMap(u) : payload;
    }

    private static Object dig(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> mm)) return null;
            cur = mm.get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        return null;
    }

    private ProfileExtractor() {}
}
```

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests MediaItemExtractorTest --tests ProfileExtractorTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: 원형 페이지·프로필 추출기 — 1l/1f 접두사·pinned·소스별 경로"`

---

### Task 5: 열거 fetcher — Hiker medias·clips 페이지

**Files:**
- Create: `crawling/application/port/out/UserMediaPageFetcher.java`, `crawling/application/service/HikerGqlMediasFetcher.java`, `crawling/application/service/HikerV2ClipsFetcher.java`
- Test: `crawling/application/service/HikerMediaPageFetchersTest.java`

**Interfaces:**
- Consumes: `HikerHttp.get(String path)` (기존), `tools.jackson.databind.ObjectMapper` 빈
- Produces:

```java
// crawling/application/port/out/UserMediaPageFetcher.java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.Map;

/** 인플루언서 게시물 열거 — 페이지 1회 조회. 응답 원형(Map)을 그대로 반환한다. */
public interface UserMediaPageFetcher {
    RawSource source();
    /** cursor null이면 첫 페이지. 반환 payload는 응답 JSON 원형. */
    Map<String, Object> fetchPage(String userId, String cursor);
}
```

- [ ] **Step 1: 실패 테스트 작성** — fake HikerHttp로 URL·커서 인코딩·원형 반환 검증:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerMediaPageFetchersTest {

    static class FakeHttp implements HikerHttp {
        String lastPath;
        String response = "{\"items\":[{\"code\":\"A\"}],\"more_available\":true,\"profile_grid_items_cursor\":\"C1\"}";
        @Override public String get(String path) { lastPath = path; return response; }
    }

    @Test
    void gql_medias는_flat과_커서를_붙여_호출하고_원형을_반환한다() {
        FakeHttp http = new FakeHttp();
        HikerGqlMediasFetcher f = new HikerGqlMediasFetcher(http, new ObjectMapper());

        Map<String, Object> first = f.fetchPage("74969123775", null);
        assertThat(http.lastPath).isEqualTo("/gql/user/medias?user_id=74969123775&flat=true");
        assertThat(first).containsKey("items").containsEntry("profile_grid_items_cursor", "C1");

        f.fetchPage("74969123775", "QVF+D/x=");
        assertThat(http.lastPath)
                .isEqualTo("/gql/user/medias?user_id=74969123775&flat=true&profile_grid_items_cursor=QVF%2BD%2Fx%3D");
    }

    @Test
    void v2_clips는_page_id_커서로_호출한다() {
        FakeHttp http = new FakeHttp();
        http.response = "{\"response\":{\"items\":[]},\"next_page_id\":\"P2\"}";
        HikerV2ClipsFetcher f = new HikerV2ClipsFetcher(http, new ObjectMapper());

        f.fetchPage("8558856783", null);
        assertThat(http.lastPath).isEqualTo("/v2/user/clips?user_id=8558856783");
        f.fetchPage("8558856783", "P2");
        assertThat(http.lastPath).isEqualTo("/v2/user/clips?user_id=8558856783&page_id=P2");
    }
}
```

- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests HikerMediaPageFetchersTest` Expected: FAIL
- [ ] **Step 3: 구현**

```java
// crawling/application/service/HikerGqlMediasFetcher.java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.RawSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 피드 게시물 열거 — HikerAPI /gql/user/medias (flat). 응답 원형을 그대로 반환. */
@Component
public class HikerGqlMediasFetcher implements UserMediaPageFetcher {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerGqlMediasFetcher(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override
    public RawSource source() {
        return RawSource.HIKER_GQL_MEDIAS;
    }

    @Override
    public Map<String, Object> fetchPage(String userId, String cursor) {
        String path = "/gql/user/medias?user_id=" + userId + "&flat=true";
        if (cursor != null) {
            path += "&profile_grid_items_cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        }
        return parse(http.get(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("medias 페이지 파싱 실패: " + e.getMessage(), e);
        }
    }
}
```

`HikerV2ClipsFetcher`도 동형 — `source()` = `HIKER_V2_CLIPS`, path `/v2/user/clips?user_id=` + (`&page_id=` URL인코딩 커서).

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests HikerMediaPageFetchersTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: 인플루언서 게시물 열거 fetcher — Hiker gql medias·v2 clips 페이지 원형"`

---

### Task 6: DiscoverJob 재편 — 키워드 평탄화 + 인플루언서 발굴

**Files:**
- Modify: `crawling/application/service/DiscoverJob.java`(Task 2에서 비운 것 재작성), `crawling/application/service/DiscoveryItemParser.java`(유지 — 시그니처 그대로), `crawling/application/port/out/DiscoverFetcher.java`(categoryId 제거), `crawling/application/service/HikerDiscoverFetcher.java`·`ActorDiscoverFetcher.java`·`DiscoverSourceSelector.java`(시그니처 추종), `crawling/application/service/CrawlExecutor.java`(categoryId→targetUsername)
- Test: `crawling/application/service/DiscoverJobTest.java` (재작성)

**Interfaces:**
- Consumes: `InfluencerRepository`, `InfluencerDiscoveryRepository`, `SearchKeywordRepository`, `ContentRepository`, `RawDiscoveryPostRepository`, `DiscoverSourceSelector.fetch(String keyword, TriggerType)` → `CrawlExecutor.Execution`
- Produces: `DiscoverJob.run(TriggerType trigger)` → `record Summary(int newInfluencers, int knownInfluencers, int skippedItems, int failedKeywords)` — 카테고리 파라미터 없음. `CrawlExecutor.execute(JobName, TriggerType, String keyword, String targetUsername, String actorId, Supplier<ApifyResult>)`

- [ ] **Step 1: 실패 테스트 작성** — 기존 DiscoverJobTest 패턴(fake 리포지토리·fake selector)으로 재작성:
  - 활성 키워드만 순회한다
  - 새 작성자면 influencer 생성(DISCOVERED), 알려진 작성자면 재사용 — 두 경우 모두 `influencer_discovery` 행이 추가된다(키워드 텍스트·shortCode·시각)
  - 발굴 게시물은 content upsert(short_code dedup, influencer_id 연결)
  - raw_discovery_post는 항상 저장되며 `source`가 발굴 fetcher의 소스로 찍힌다
  - 키워드 하나 실패 시 다음 키워드 계속(failedKeywords 증가)
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests DiscoverJobTest` Expected: FAIL
- [ ] **Step 3: 구현**

```java
// crawling/application/service/DiscoverJob.java (재작성 핵심)
@Service
public class DiscoverJob {

    public record Summary(int newInfluencers, int knownInfluencers, int skippedItems, int failedKeywords) {}

    private final SearchKeywordRepository keywords;
    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final DiscoverSourceSelector discoverSourceSelector;
    private final Clock clock;

    // 생성자 생략 없이 필드 주입 순서대로 작성

    @Transactional
    public Summary run(TriggerType trigger) {
        int newInf = 0, known = 0, skipped = 0, failedKeywords = 0;
        for (SearchKeyword kw : keywords.findByEnabledTrue()) {
            CrawlExecutor.Execution ex;
            try {
                ex = discoverSourceSelector.fetch(kw.getKeyword(), trigger);
            } catch (ApifyException e) {
                failedKeywords++;
                continue;
            }
            for (Map<String, Object> item : ex.items()) {
                var parsed = DiscoveryItemParser.parse(item);
                if (parsed.isEmpty()) { skipped++; continue; }
                var d = parsed.get();
                var existing = influencers.findByUsername(d.ownerUsername());
                Influencer inf = existing.orElseGet(() -> influencers.save(new Influencer(d.ownerUsername())));
                if (existing.isPresent()) known++; else newInf++;
                discoveries.save(new InfluencerDiscovery(
                        inf.getId(), kw.getKeyword(), d.shortCode(), clock.instant()));
                Content content = contents.findByShortCode(d.shortCode()).orElseGet(() ->
                        contents.save(new Content(d.shortCode(), d.type(), d.ownerUsername(),
                                inf.getId(), d.uploadedAt(), clock.instant())));
                // 중복 발굴이어도 raw는 항상 저장 — 원형 그대로 + 소스 태그
                rawDiscovery.save(new RawDiscoveryPost(content.getId(), ex.runId(),
                        discoverSourceSelector.currentSource(), d.payload(), clock.instant()));
            }
        }
        return new Summary(newInf, known, skipped, failedKeywords);
    }
}
```

`DiscoverFetcher`에 `RawSource rawSource()` 메서드 추가 — `ActorDiscoverFetcher`는 `APIFY_ACTOR`, `HikerDiscoverFetcher`는 `HIKER_HASHTAG`를 선언. `DiscoverSourceSelector.currentSource()`는 현재 선택된 fetcher의 `rawSource()`를 그대로 반환. 발굴 raw의 저장 후 실컬럼(short_code·caption)은 `RawDiscoveryPost` 세터로 파서 결과를 채운다.

주의: 기존 `DiscoveryItemParser`는 Apify 계약 키(shortCode/timestamp/ownerUsername) 기준 — Hiker 발굴 경로는 기존 `HikerDiscoveryMapper`가 그 키로 맞춰주고 있었다. **발굴 경로의 원형화는 이번 스코프에서 하지 않는다**(스펙 "이번 작업에서 하지 않는 것" — 발굴 소스 다변화 제외). 발굴 payload는 기존 형태 유지, source는 실제 페처 기준으로 기록만 한다.

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests DiscoverJobTest --tests DiscoverSourceSelectorTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: discover 재편 — search_keyword 순회, 인플루언서 upsert + 발굴 출처 기록"`

---

### Task 7: QualifyJob 재작성 — 인플루언서 단위 판정

**Files:**
- Modify: `crawling/application/service/QualifyJob.java`, `crawling/application/service/ProfileSourceSelector.java`(원형 반환 경로), 프로필 fetcher 3종(`SelfProfileFetcher`·`HikerMobileProfileFetcher`·`ActorProfileFetcher`) — **정규화 제거, 원형 반환 + `RawSource` 노출**, `ProfileMapper` 삭제, `ProfileSupplementer`에서 posts 보충 제거(`HikerMediasSupplement` 삭제, `HikerSuggestedSupplement`·related 보충 유지)
- Test: `crawling/application/service/QualifyJobTest.java` (재작성), `SelfProfileFetcherTest`·`HikerProfileFetchersTest` (원형 반환으로 수정), `ProfileMapperTest`·`ProfileSupplementerTest`(posts 부분) 삭제/수정

**Interfaces:**
- Consumes: `InfluencerRepository`, `RawProfileRepository`, `ProfileSourceSelector`, `SettingsService.qualifyMinFollowers()/qualifyMaxFollowers()/qualifyBatchLimit()`, `ProfileExtractor`
- Produces: `QualifyJob.run(TriggerType, boolean requalify)` → `record Summary(int profiled, int qualified, int excluded, int deferred)`. `ProfileFetcher`에 `RawSource rawSource()` 추가, fetch 결과 아이템은 **응답 원형**(SELF는 web_profile_info JSON 루트, HIKER_MOBILE은 v2/user/by/username 루트)

- [ ] **Step 1: 실패 테스트 작성** — fake selector가 원형 프로필(예: `{"user":{"username":"alice","follower_count":12345,"pk":"111"}}`)을 반환:
  - DISCOVERED 인플루언서가 배치 상한만큼 선정된다
  - 프로필 원형이 raw_profile에 source와 함께 저장되고, followers·username 실컬럼이 추출로 채워진다
  - influencer.followers 갱신 + 3000~50000 안이면 QUALIFIED, 밖이면 EXCLUDED
  - 프로필 실패(응답에 없음)면 DISCOVERED 유지(deferred)
  - requalify=true면 QUALIFIED·EXCLUDED도 기존 followers로 재판정(재호출 없음)
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests QualifyJobTest` Expected: FAIL
- [ ] **Step 3: 구현** — 핵심 로직:

```java
@Transactional
public Summary run(TriggerType trigger, boolean requalify) {
    List<Influencer> targets = new ArrayList<>(influencers.findByStatus(
            InfluencerStatus.DISCOVERED, PageRequest.of(0, settings.qualifyBatchLimit())));

    int profiled = profileMissing(targets, trigger);

    if (requalify) {
        targets.addAll(influencers.findByStatus(InfluencerStatus.QUALIFIED, Pageable.unpaged()));
        targets.addAll(influencers.findByStatus(InfluencerStatus.EXCLUDED, Pageable.unpaged()));
    }

    long min = settings.qualifyMinFollowers(), max = settings.qualifyMaxFollowers();
    int qualified = 0, excluded = 0, deferred = 0;
    for (Influencer inf : targets) {
        Long followers = inf.getFollowers();
        if (followers == null) { deferred++; continue; }   // 프로필 미확보 → 다음 실행 재시도
        boolean pass = followers >= min && followers <= max;
        inf.setStatus(pass ? InfluencerStatus.QUALIFIED : InfluencerStatus.EXCLUDED);
        if (pass) qualified++; else excluded++;
    }
    return new Summary(profiled, qualified, excluded, deferred);
}

private int profileMissing(List<Influencer> targets, TriggerType trigger) {
    List<Influencer> toProfile = targets.stream()
            .filter(i -> i.getLastProfiledAt() == null).toList();
    int profiled = 0;
    for (List<Influencer> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
        List<String> names = chunk.stream().map(Influencer::getUsername).toList();
        CrawlExecutor.Execution ex;
        RawSource source = profileSourceSelector.currentSource();
        try {
            ex = profileSourceSelector.fetchAndSupplement(names, trigger);
        } catch (ApifyException e) {
            continue;
        }
        Map<String, Influencer> byName = chunk.stream()
                .collect(Collectors.toMap(Influencer::getUsername, i -> i));
        for (Map<String, Object> item : ex.items()) {
            String username = ProfileExtractor.username(item, source);
            Influencer inf = username != null ? byName.get(username) : null;
            if (inf == null) continue;
            RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
            rp.setUsername(username);
            rp.setFollowers(ProfileExtractor.followers(item, source));
            rawProfiles.save(rp);
            inf.setFollowers(rp.getFollowers());
            inf.setLastProfiledAt(clock.instant());
            profiled++;
        }
    }
    return profiled;
}
```

프로필 fetcher 원형화: `SelfProfileFetcher`는 `ProfileMapper.fromSelf(json)` 대신 `om.readValue(json, Map.class)` 원형을 아이템으로. `ActorProfileFetcher`는 액터 아이템 그대로(이미 원형). `ProfileSourceSelector.currentSource()`는 설정된 소스의 `RawSource` 반환.

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests QualifyJobTest --tests SelfProfileFetcherTest --tests HikerProfileFetchersTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: qualify 인플루언서 단위 재작성 — 전역 팔로워 범위, 프로필 원형 저장"`

---

### Task 8: CollectJob — 6개월 백필·추적 방문 + 댓글 원형화

**Files:**
- Create: `crawling/application/service/CollectJob.java`
- Modify: `crawling/application/port/out/CommentFetcher.java`(페이지 원형 반환), `crawling/application/service/DirectCommentFetcher.java`, `crawling/application/service/ActorCommentFetcher.java`, `crawling/application/service/CommentSourceSelector.java`(`currentSource()` 추가)
- Test: `crawling/application/service/CollectJobTest.java`, `crawling/application/service/DirectCommentFetcherTest.java`(수정)

**Interfaces:**
- Consumes: `InfluencerRepository.findCollectTargets(Pageable)`, `UserMediaPageFetcher` 2빈(List 주입), `MediaItemExtractor`, `ProfileExtractor`, `ProfileSourceSelector`, `CommentSourceSelector`, `RawMediaPageRepository`, `ContentRepository`, `RawCommentRepository`, `CrawlExecutor`, `SettingsService`, `JobProgress`
- Produces:
  - `CollectJob.run(TriggerType)` → `record Summary(int visited, int postsUpserted, int postsCollected, int failedVisits)`
  - `CommentFetcher.fetch(List<String> shortCodes, int commentsPerPost, TriggerType)` → `record CommentResult(Long runId, Map<String, List<Map<String,Object>>> pagesByCode)` — SELF는 페이지 원형 목록, ACTOR는 댓글 아이템 목록(소스가 해석 구분)

- [ ] **Step 1: 실패 테스트 작성** — fake 열거 fetcher 2개(페이지 시퀀스 반환)·fake 댓글·fake 프로필로:
  - 백필 대상(first_collected_at null)이 추적 대상보다 먼저 선정된다
  - 방문 시 프로필 원형 저장 + followers 갱신 (userId 추출해 열거에 사용)
  - 두 스트림 페이지가 각각 raw_media_page에 source와 함께 저장된다
  - 컷오프(백필 6개월) 넘긴 페이지에서 중단하되, **고정 게시물은 중단 판단에서 제외**
  - 두 스트림 shortCode 중복은 content 1건 (윈도우 안 게시물만 upsert, 고정이라도 윈도우 안이면 포함)
  - 댓글 페이지가 content별 raw_comment에 원형 저장되고 content가 COLLECTED로 전이
  - 댓글 실패 시 collect_attempts 증가, 상한 도달 시 FAILED
  - 방문 완료 시 first_collected_at(첫 방문만)·last_collected_at 갱신
  - 추적 방문(첫 방문 완료된 인플루언서)은 track-window-days 컷오프를 쓴다
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests CollectJobTest` Expected: FAIL
- [ ] **Step 3: 구현**

```java
// crawling/application/service/CollectJob.java (핵심 — 전체 구조)
@Service
public class CollectJob {

    private static final int MAX_PAGES_PER_STREAM = 40;  // 폭주 방지 안전 상한

    public record Summary(int visited, int postsUpserted, int postsCollected, int failedVisits) {}

    // 필드: influencers, rawMediaPages, contents, rawComments, mediaFetchers(List<UserMediaPageFetcher>),
    //       profileSourceSelector, commentSource, executor, settings, clock, progress

    @Transactional
    public Summary run(TriggerType trigger) {
        List<Influencer> targets = influencers.findCollectTargets(
                PageRequest.of(0, settings.collectBatchLimit()));
        int visited = 0, upserted = 0, collected = 0, failed = 0;
        progress.start(JobName.COLLECT, targets.size());
        try {
            for (Influencer inf : targets) {
                try {
                    VisitResult r = visit(inf, trigger);
                    upserted += r.upserted();
                    collected += r.collected();
                    visited++;
                } catch (ApifyException e) {
                    failed++;   // 인플루언서 단위 실패 — 방문 시각 미갱신, 다음 실행 재시도
                } finally {
                    progress.advance(JobName.COLLECT, 1);
                }
            }
        } finally {
            progress.finish(JobName.COLLECT);
        }
        return new Summary(visited, upserted, collected, failed);
    }

    private record VisitResult(int upserted, int collected) {}

    private VisitResult visit(Influencer inf, TriggerType trigger) {
        // 1) 프로필 갱신 (원형 저장 + followers·userId 추출) — Task 7의 저장 로직과 동일 패턴 재사용
        String userId = refreshProfile(inf, trigger);

        // 2) 컷오프: 첫 방문=백필 개월, 이후=추적 윈도우
        boolean backfill = inf.getFirstCollectedAt() == null;
        Instant cutoff = backfill
                ? clock.instant().atZone(ZoneOffset.UTC).minusMonths(settings.backfillMonths()).toInstant()
                : clock.instant().minus(Duration.ofDays(settings.trackWindowDays()));

        // 3) 두 스트림 열거 → 페이지 원형 저장 → 추출 → 윈도우 내 아이템 수집 (shortCode dedup)
        Map<String, MediaItemExtractor.MediaItem> inWindow = new LinkedHashMap<>();
        for (UserMediaPageFetcher fetcher : mediaFetchers) {
            enumerateStream(inf, fetcher, userId, cutoff, trigger, inWindow);
        }

        // 4) content upsert
        int upserted = 0;
        List<Content> pending = new ArrayList<>();
        for (var item : inWindow.values()) {
            Content c = contents.findByShortCode(item.shortCode()).orElseGet(() -> {
                return contents.save(new Content(item.shortCode(), item.type(),
                        inf.getUsername(), inf.getId(), item.takenAt(), clock.instant()));
            });
            if (c.getStatus() == ContentStatus.PENDING) pending.add(c);
            upserted++;
        }

        // 5) 게시물별 댓글 수집 (열거=상세이므로 추가 방문은 댓글뿐)
        int collected = collectComments(pending, trigger);

        if (backfill) inf.setFirstCollectedAt(clock.instant());
        inf.setLastCollectedAt(clock.instant());
        return new VisitResult(upserted, collected);
    }

    /** 커서 페이지네이션. "고정 제외 전부가 컷오프보다 오래됨"이면 중단. 윈도우 내 아이템만 수집. */
    private void enumerateStream(Influencer inf, UserMediaPageFetcher fetcher, String userId,
                                 Instant cutoff, TriggerType trigger,
                                 Map<String, MediaItemExtractor.MediaItem> sink) {
        String cursor = null;
        for (int page = 0; page < MAX_PAGES_PER_STREAM; page++) {
            final String cur = cursor;
            CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger,
                    null, inf.getUsername(), fetcher.source().name(),
                    () -> new ApifyResult(null, 1, List.of(fetcher.fetchPage(userId, cur))));
            Map<String, Object> payload = ex.items().get(0);
            rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), fetcher.source(),
                    payload, clock.instant()));

            List<MediaItemExtractor.MediaItem> items =
                    MediaItemExtractor.extract(payload, fetcher.source());
            if (items.isEmpty()) return;
            for (var it : items) {
                if (!it.takenAt().isBefore(cutoff)) sink.putIfAbsent(it.shortCode(), it);
            }
            List<MediaItemExtractor.MediaItem> fresh = items.stream().filter(i -> !i.pinned()).toList();
            if (!fresh.isEmpty() && fresh.stream().allMatch(i -> i.takenAt().isBefore(cutoff))) return;
            cursor = MediaItemExtractor.nextCursor(payload, fetcher.source());
            if (cursor == null) return;
        }
    }

    private int collectComments(List<Content> pending, TriggerType trigger) {
        if (pending.isEmpty()) return 0;
        List<String> codes = pending.stream().map(Content::getShortCode).toList();
        CommentFetcher.CommentResult r;
        RawSource source = commentSource.currentSource();
        try {
            r = commentSource.current().fetch(codes, settings.commentsPerPost(), trigger);
        } catch (ApifyException e) {
            bumpAttempts(pending);
            return 0;
        }
        int collected = 0;
        for (Content c : pending) {
            List<Map<String, Object>> pages = r.pagesByCode().get(c.getShortCode());
            if (pages == null) {
                bumpAttempts(List.of(c));
                continue;
            }
            for (Map<String, Object> pagePayload : pages) {
                rawComments.save(new RawComment(c.getId(), r.runId(), source, pagePayload, clock.instant()));
            }
            c.setStatus(ContentStatus.COLLECTED);
            c.setCollectedAt(clock.instant());
            collected++;
        }
        return collected;
    }

    private void bumpAttempts(List<Content> chunk) {
        for (Content c : chunk) {
            c.setCollectAttempts(c.getCollectAttempts() + 1);
            if (c.getCollectAttempts() >= settings.maxAttempts()) c.setStatus(ContentStatus.FAILED);
        }
    }
}
```

`DirectCommentFetcher` 수정: 기존 per-code 페이지 루프 유지하되, `CommentMapper.parse()`는 **커서·hasNext 판단에만** 사용하고 저장용으로는 페이지 JSON 원형(`om.readValue(json, Map.class)`)을 `pagesByCode`에 누적. `ActorCommentFetcher`: 기존 아이템을 postUrl→shortCode로 그룹해 `pagesByCode`로 반환(아이템=원형 그대로).

- [ ] **Step 4: 실행 — 통과 확인** — Run: `./gradlew test --tests CollectJobTest --tests DirectCommentFetcherTest` Expected: PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat: collect 잡 — 두 스트림 열거·컷오프·dedup·댓글 페이지 원형 수집"`

---

### Task 9: 배선·정리 — JobService/컨트롤러/스케줄러 + 구코드 삭제

**Files:**
- Modify: `crawling/application/service/JobService.java`, `crawling/application/port/in/TriggerJobUseCase.java`(categoryId 파라미터 제거), `crawling/adapter/in/web/JobController.java`(`POST /admin/jobs/collect`, category 파라미터 제거), `crawling/adapter/in/web/UiJobController.java`, `crawling/adapter/in/scheduler/ScheduleRunner.java`(aggregate→collect), `src/main/resources/application.yml`(스케줄 cron 키 이름)
- Delete: `crawling/application/service/AggregateJob.java`(Task 2)·`HikerMediasSupplement.java`(Task 7)는 이미 삭제됨 — 잔여 참조만 확인. 이 태스크에서 삭제: `AdSignals.java`, `DetailMapper.java`, `ActorDetailFetcher.java`, `HikerReelDetailFetcher.java`, `SelfFeedDetailFetcher.java`, `DetailSourceSelector.java`, `DetailFetcher.java`(port), `DetailSourceUiController.java`, `settings/.../DetailSourceSetting.java`, `settings/domain/DetailSource.java`, 관련 테스트 전부
- Test: `crawling/adapter/in/web/JobApiTest.java`(수정 — collect 트리거·category 파라미터 제거 검증)

**Interfaces:**
- Consumes: Task 6~8의 `DiscoverJob.run(TriggerType)`, `QualifyJob.run(TriggerType, boolean)`, `CollectJob.run(TriggerType)`
- Produces: `TriggerJobUseCase.trigger(JobName job, TriggerType triggerType, boolean requalify)` — REST `POST /admin/jobs/{discover|qualify|collect}`

- [ ] **Step 1: JobApiTest 수정 (실패 확인)** — collect 트리거가 ACCEPTED를 반환하고 aggregate 엔드포인트는 404가 되는지
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests JobApiTest` Expected: FAIL
- [ ] **Step 3: 구현** — JobService switch를 `DISCOVER -> discoverJob.run(trigger)` / `QUALIFY` / `COLLECT -> collectJob.run(trigger)`로. 삭제 목록 일괄 제거 후 컴파일 확인. ScheduleRunner의 aggregate cron을 collect cron으로 (yml 키 `crawler.schedule.collect-cron`)
- [ ] **Step 4: 전체 테스트** — Run: `./gradlew test` Expected: PASS (UI 테스트는 Task 10 대상이라 이 시점 실패분은 Task 10 파일로 확인해 남겨두지 말고 **UI 컨트롤러가 컴파일만 되는 상태면 통과하도록 최소 수정**)
- [ ] **Step 5: 커밋** — `git commit -m "feat: collect 배선 + aggregate·상세 fetcher·AdSignals·posts 보충 제거"`

---

### Task 10: UI·대시보드·열람 재편 + README

**Files:**
- Create: `content/adapter/in/web/KeywordAdminController.java`(REST `/admin/keywords` — GET 목록/POST 추가/PUT enabled 토글/DELETE), `content/adapter/in/web/UiKeywordController.java`(`/ui/keywords`), `src/main/resources/templates/keywords.html`
- Delete: `templates/categories.html`, `CategoryAdminController`·`UiCategoryController` 잔재
- Modify: `dashboard/application/StatusService.java`(인플루언서 상태 카운트 + 게시물 수집 카운트), `dashboard/adapter/in/web/UiController.java`, `templates/dashboard.html`·`fragments/status-tiles.html`(카드: DISCOVERED/QUALIFIED/EXCLUDED/백필 대기/게시물 PENDING·COLLECTED·FAILED), `templates/jobs.html`(aggregate 버튼→collect), `templates/contents.html`(인플루언서 목록 → 게시물 드릴다운), `templates/fragments/nav.html`(메뉴명), `dashboard/adapter/in/web/AdminQueryController.java`(runs·status 응답 필드), `README.md`(파이프라인 설명·운영 절차·스모크 절차를 새 흐름으로)
- Test: `content/adapter/in/web/KeywordApiTest.java`(신규 — CategoryApiTest 대체), `dashboard/adapter/in/web/UiSmokeTest.java`(수정)

**Interfaces:**
- Consumes: `SearchKeywordRepository`, `InfluencerRepository.countByStatus`, `ContentRepository`의 상태 카운트
- Produces: REST `/admin/keywords`(GET/POST/PUT/DELETE — PUT은 enabled만, **keyword 텍스트 수정 없음**), UI `/ui/keywords`·대시보드 카드

- [ ] **Step 1: KeywordApiTest 작성 (실패 확인)** — 추가/목록/토글/삭제 왕복, keyword 수정 시도는 405/400. UiSmokeTest에 `/ui/keywords` 200 추가
- [ ] **Step 2: 실행 — 실패 확인** — Run: `./gradlew test --tests KeywordApiTest --tests UiSmokeTest` Expected: FAIL
- [ ] **Step 3: 구현** — 기존 CategoryAdminController/categories.html 패턴을 평탄화 버전으로 이식. StatusService는 `influencer` 상태 3종 + `first_collected_at IS NULL AND status='QUALIFIED'`(백필 대기) + content 상태 3종 카운트 반환. README의 파이프라인·운영 절차·스모크 섹션을 discover→qualify→collect로 갱신
- [ ] **Step 4: 전체 테스트 + 수동 확인** — Run: `./gradlew test` Expected: 전체 PASS. 이어서 `./gradlew bootRun`으로 `/ui` 대시보드·키워드·잡 화면 렌더 확인
- [ ] **Step 5: 커밋** — `git commit -m "feat: 검색 키워드 UI·인플루언서 대시보드·열람 재편 + README 갱신"`

---

## 마무리 체크 (전 태스크 완료 후)

- [ ] `./gradlew test` 전체 PASS
- [ ] 실 DB(로컬 Docker)에 부팅해 V8 마이그레이션이 4,176명 실데이터에서 성공하는지 확인 — 백업(`crawler-20260713.dump`)이 있으므로 실패 시 복원 가능
- [ ] 대시보드에서 이관 결과 확인: influencer 4,176 / search_keyword 54 / influencer_discovery ≈ 기존 content 행 수
- [ ] 스모크(실과금 최소): `collect.batch-limit=1`로 소형 계정 1명 collect 실행 → raw_media_page·content·raw_comment 적재 확인
- [ ] superpowers:verification-before-completion 후 superpowers:finishing-a-development-branch

**의도적 후속 이관 (이번 스코프 아님):** 스펙의 "재추출 배치"(추출 실패로 제어 컬럼이 NULL인 raw 행 복구)는
별도 잡을 만들지 않는다 — 원형이 raw에 있으므로 필요해지는 시점(첫 추출 실패 관측)에 추가해도 데이터 유실이 없다.
분석 소스 계층·AdSignals 이관·추적 스케줄 자동화도 스펙의 "하지 않는 것" 그대로.
