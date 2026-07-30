# 게시물 캡션 원문 보존 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crawler가 수집한 게시물 캡션 원문을 `content_caption` 테이블에 보존해, jsonb 깊은 경로를 몰라도 조회 가능하게 하고 현재 파싱되지 않는 `HIKER_V1_MEDIAS` 캡션 약 6,240건을 건져낸다.

**Architecture:** 캡션은 이미 raw jsonb(`raw_media_page`·`raw_profile`)에 있으나 `MediaItemExtractor`가 파싱하지 않아 버려진다. 추출기가 세 소스에서 캡션을 뽑도록 고치고(단일 정본 파서), 라이브 수집 경로와 일회성 백필 잡이 **같은 추출기를 재사용**해 파싱 로직이 갈라지지 않게 한다. 저장은 `content_id` PK 단일 테이블(약 96 MB), 쓰기는 `ON CONFLICT` 배치 upsert.

**Tech Stack:** Java 21, Spring Boot 4.1, JPA/Hibernate(`@JdbcTypeCode(SqlTypes.JSON)`), JdbcTemplate 배치, Flyway(crawler = raw DB 소유), JUnit 5 + AssertJ + Mockito, Testcontainers 2.x(`org.testcontainers.postgresql.PostgreSQLContainer`), Gradle 멀티모듈.

**설계 문서:** [docs/superpowers/specs/2026-07-30-caption-preservation-design.md](../specs/2026-07-30-caption-preservation-design.md)

---

## 배경 지식 (이 코드베이스를 모르는 사람을 위해)

**캡션이 담긴 JSON 경로 — 세 소스가 형태가 다르다:**

| RawSource | 저장 테이블 | 배열 위치 | 아이템 언랩 | 캡션 |
|---|---|---|---|---|
| `HIKER_V2_CLIPS` | `raw_media_page` | `payload.response.items` | `item.media` | `caption.text` (중첩 객체) |
| `SELF_GQL` | `raw_profile` | `payload.data.user.edge_owner_to_timeline_media.edges` | `item.node` | `edge_media_to_caption.edges[0].node.text` |
| `HIKER_V1_MEDIAS` | `raw_media_page` | `payload.medias` | (언랩 없음) | `caption_text` (평문) |

**중요한 사실:** `MediaItemExtractor.unwrapMedia()`가 이미 세 형태를 **하나의 맵으로 정규화**한다
(`media` → `node` → 그대로). 따라서 캡션 추출은 정규화된 맵 하나를 받는 **단일 헬퍼**로 끝난다.
소스별 분기가 필요 없다.

**빈 캡션 정책:** 캡션이 없는 게시물도 행을 만들고 `caption=''`로 저장한다. 행 존재 = "확인했음",
`caption=''` = "게시물에 캡션 없음". 이렇게 하면 미백필과 무캡션이 SQL로 구분된다. 따라서
`captionOf()`는 **절대 null을 반환하지 않는다** — 못 찾으면 `""`.

**`RawSource` enum 값 전체:** `LEGACY_ENVELOPE, APIFY_ACTOR, HIKER_MOBILE, HIKER_HASHTAG, SELF_GQL,
HIKER_GQL_MEDIAS, HIKER_V2_CLIPS, HIKER_V1_MEDIAS, DATALIKERS`

**주의 — CI 안전망 없음:** `.github/scripts/check-migration-safety.sh`는 `was`+`analytics`
마이그레이션만 검사한다. crawler 마이그레이션은 파괴 가드가 **자동 차단하지 않는다**. 그러니
`-- allow-destructive:` 주석은 사람 리뷰어용으로 남기고, DDL은 직접 더 조심해서 검토한다.

**테스트 명령:**
- 전체: `./gradlew test`
- crawler만: `./gradlew :crawler:test`
- 클래스 1개: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"`

Testcontainers가 필요하므로 Docker(colima)가 떠 있어야 통합 테스트가 돈다.

---

## File Structure

**생성:**

| 파일 | 책임 |
|---|---|
| `crawler/src/main/resources/db/migration/V23__content_caption.sql` | `content_caption` 테이블 + 백필 워터마크 `app_setting` 시드 |
| `crawler/src/main/resources/db/migration/V23__drop_raw_post_detail.sql` | 죽은 테이블 제거 (contract 단계) |
| `crawler/src/main/java/com/celfit/crawler/content/application/service/ContentCaptionUpserter.java` | 캡션 배치 upsert 1개 책임. 라이브·백필 공용 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJob.java` | 저장된 raw 페이지를 훑어 캡션 소급 적재 |
| `crawler/src/test/java/com/celfit/crawler/content/application/service/ContentCaptionUpserterIntegrationTest.java` | upsert 규칙(신규·최신승리·구버전무시·content 부재 스킵) 검증 |
| `crawler/src/test/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJobIntegrationTest.java` | 백필이 세 소스에서 캡션을 적재하고 워터마크로 재개하는지 검증 |

**수정:**

| 파일 | 변경 |
|---|---|
| `MediaItemExtractor.java` | `MediaItem`에 `caption` 추가(캐노니컬 5-인자 생성자만) + `captionOf()` 헬퍼 |
| `MediaItemExtractorTest.java` | 세 소스 캡션 추출 테스트 추가 |
| `CollectJob.java:184-195` | 피드 소스를 지역 변수로 명시 + 캡션 upsert 호출 |
| `ReelsJob.java:141-142` | 캡션 upsert 호출 |
| `JobName.java` | `CAPTION_BACKFILL` 추가 |
| `JobService.java` | switch에 `case CAPTION_BACKFILL` 분기 (exhaustive switch라 필수) |
| `UiJobController.java` | `/ui/jobs/caption-backfill` 엔드포인트 |
| `crawler/src/main/resources/templates/dashboard.html` | 잡 실행 버튼 |
| `UiController.java` | 진행 바 목록에 잡 추가 |
| `RawMediaPageRepository.java` / `RawProfileRepository.java` | id 커서 배치 조회 |
| `SchemaTest.java:23` | `content_caption` 추가 · `raw_post_detail` 제거 |
| `ARCHITECTURE.md` | §3 캡션 조회 경로 · §5 트랙 · §7 결정 기록 |

**왜 `ContentCaptionUpserter`를 `ContentUpserter`에 합치지 않는가:** `ContentUpserter`는 content 제어
행 1개 책임이고 `source`·`capturedAt`를 모른다. 캡션은 provenance가 필요하므로 별도 단위로 두고,
호출자(각 잡)가 자기가 아는 소스를 명시적으로 넘긴다.

---

## Task 1: 추출기가 캡션을 뽑는다

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java:17,33`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/MediaItemExtractorTest.java`

- [ ] **Step 1: 실패하는 테스트 3개를 작성한다**

`MediaItemExtractorTest.java` 파일 맨 끝 `}` **직전**에 아래를 추가한다:

```java
    // ---- 캡션 원문 추출 — 세 소스가 형태가 다르다(중첩 객체 / edges 배열 / 평문) ----

    @Test
    void v2_clips는_caption_text_중첩객체에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of(
                "response", Map.of("items", List.of(
                        Map.of("media", Map.of("code", "CLIP1", "taken_at", 1783223195L,
                                "product_type", "clips",
                                "caption", Map.of("text", "#광고 여름 메리제인 🤍"))))));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("#광고 여름 메리제인 🤍");
    }

    @Test
    void self_gql은_edge_media_to_caption_첫_노드에서_캡션을_뽑는다() {
        Map<String, Object> payload = profileWithTimeline(List.of(
                Map.of("shortcode", "FEED1", "taken_at_timestamp", 1773630245L,
                        "edge_media_to_caption", Map.of("edges", List.of(
                                Map.of("node", Map.of("text", "매일 쓰는 메이크업 도구")))))));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.SELF_GQL);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("매일 쓰는 메이크업 도구");
    }

    @Test
    void v1_medias는_caption_text_평문필드에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "C_FEED", "taken_at", 1773630245L,
                        "caption_text", "고마어 잘 쓸게~~ 🤍")));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V1_MEDIAS);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("고마어 잘 쓸게~~ 🤍");
    }

    /** 캡션 없는 게시물은 null이 아니라 빈 문자열 — "미확인"과 "캡션 없음"을 DB에서 구분하기 위함. */
    @Test
    void 캡션이_없으면_빈_문자열이다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "NOCAP", "taken_at", 1773630245L)));

        assertThat(MediaItemExtractor.extract(payload, RawSource.HIKER_V1_MEDIAS).get(0).caption())
                .isEqualTo("");
    }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"
```

기대: **컴파일 실패** — `cannot find symbol: method caption()`. (record에 아직 필드가 없다.)

- [ ] **Step 3: `MediaItem`에 `caption`을 추가하고 `captionOf()`를 구현한다**

`MediaItemExtractor.java:17`의 record 선언을 아래로 **교체**한다:

```java
    /**
     * @param caption 캡션 원문 — 없으면 빈 문자열(null 아님). "미확인"과 "캡션 없음"을 DB에서
     *                구분하기 위해 추출 단계에서 이미 정규화한다.
     */
    public record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned,
                            String caption) {}
```

**4-인자 호환 생성자를 추가하지 않는다.** 이 필드의 존재 이유가 "미확인"과 "캡션 없음"의 구분인데,
캡션을 다루지 않는 호출자에게 조용히 `""`(=캡션 없음)를 부여하는 생성자는 그 구분을 프로덕션 API
표면에서 다시 흐린다(코드 품질 리뷰 지적, 07-30). 호출자는 기존 테스트 6곳뿐이므로 각각에
`""`를 명시적으로 넘긴다: `ContentUpserterTest.java:46`의 로컬 팩토리 1곳,
`MediaItemExtractorTest.java`의 기존 `new MediaItemExtractor.MediaItem(...)` 호출 5곳
(각 인자 끝에 `, ""` 추가).

`MediaItemExtractor.java:33`의 `out.add(...)` 한 줄을 아래로 **교체**한다:

```java
            out.add(new MediaItem(code, takenAt, type, pinned, captionOf(m)));
```

`captionOf()`를 `unwrapMedia()` 메서드 **바로 아래**에 추가한다:

```java
    /**
     * 캡션 원문 — unwrapMedia()가 media/node/item을 이미 벗겨냈으므로 정규화된 맵 하나에서
     * 세 형태를 순서대로 시도한다. HIKER_V2_CLIPS는 caption.text(중첩 객체),
     * HIKER_V1_MEDIAS는 caption_text(평문), SELF_GQL은 edge_media_to_caption.edges[0].node.text.
     * 못 찾으면 빈 문자열 — 캡션 없는 게시물도 행을 남겨 "미확인"과 구분한다.
     */
    private static String captionOf(Map<String, Object> m) {
        if (m.get("caption") instanceof Map<?, ?> c && c.get("text") instanceof String s) return s;
        if (m.get("caption_text") instanceof String s) return s;
        if (m.get("edge_media_to_caption") instanceof Map<?, ?> e
                && e.get("edges") instanceof List<?> l && !l.isEmpty()
                && l.get(0) instanceof Map<?, ?> first
                && first.get("node") instanceof Map<?, ?> node
                && node.get("text") instanceof String s) return s;
        return "";
    }
```

클래스 Javadoc(10-14줄)의 첫 문장 `저장된 열거 페이지 원형에서 제어 필드만 추출.` 을 아래로 **교체**한다
(더 이상 제어 필드만 뽑지 않는다):

```java
 * 저장된 열거 페이지 원형에서 제어 필드와 캡션 원문을 추출. 원형은 이미 raw_media_page에 있으므로
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"
```

기대: PASS. **기존 테스트 5개도 함께 통과해야 한다** — 각 호출부에 명시한 `""`와, 기존 픽스처는
캡션이 없어 `captionOf()`도 `""`를 반환하는 것이 맞아떨어져 record equals가 성립한다.

빈 `edges` 경계 케이스도 하나 추가한다(`self_gql_타임라인_내장_여부를_판별한다`가 이미 "빈 edges도
내장 있음"을 검증하는 관례와 짝):

```java
    /** hasEmbeddedTimeline의 빈 edges 검증과 짝 — edges가 비어도 IndexOutOfBounds 없이 빈 문자열. */
    @Test
    void self_gql은_edges가_비어있으면_빈_문자열이다() {
        Map<String, Object> payload = profileWithTimeline(List.of(
                Map.of("shortcode", "FEED1", "taken_at_timestamp", 1773630245L,
                        "edge_media_to_caption", Map.of("edges", List.of()))));

        assertThat(MediaItemExtractor.extract(payload, RawSource.SELF_GQL).get(0).caption())
                .isEqualTo("");
    }
```

- [ ] **Step 5: `ContentUpserterTest`도 함께 통과하는지 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.ContentUpserterTest"
```

기대: PASS. (`ContentUpserterTest.java:46`의 로컬 팩토리가 `""`를 명시한 5-인자 생성자를 쓴다.)

- [ ] **Step 6: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/MediaItemExtractorTest.java
git commit -m "feat(crawler): 추출기가 세 소스에서 캡션 원문을 뽑는다

캡션이 raw jsonb에만 남고 버려지던 유실 지점. HIKER_V2_CLIPS(중첩 객체)·
SELF_GQL(edges 배열)·HIKER_V1_MEDIAS(평문) 셋 다 unwrapMedia()가 정규화한
맵에서 단일 헬퍼로 처리한다.

캡션 없는 게시물은 null이 아니라 빈 문자열 — 미확인과 캡션 없음을 DB에서
구분하기 위해 추출 단계에서 정규화한다."
```

---

## Task 2: `content_caption` 테이블

**Files:**
- Create: `crawler/src/main/resources/db/migration/V23__content_caption.sql`
- Modify: `crawler/src/test/java/com/celfit/crawler/SchemaTest.java:23`

- [ ] **Step 1: 마이그레이션 번호를 확인한다**

```bash
git fetch origin --quiet && git ls-tree -r origin/develop --name-only -- crawler/src/main/resources/db/migration/ | sed 's|.*/||' | sort -t V -k2 -n | tail -3
```

기대: 최대가 `V21__beauty_foreign_influencer.sql`. **V22가 이미 있으면 번호를 하나 올리고 이 계획서의
모든 V22/V23 언급을 그에 맞게 조정한다** (과거 V18 번호 경합 사고 전력).

- [ ] **Step 2: 마이그레이션 파일을 작성한다**

`crawler/src/main/resources/db/migration/V23__content_caption.sql`:

```sql
-- 게시물 캡션 원문 보존 (2026-07-30).
-- 배경: 캡션은 raw_media_page·raw_profile의 jsonb 원형에 실재하지만 MediaItemExtractor가
-- 파싱하지 않아 버려졌고, 도달 경로가 5~7단 jsonb 표현식뿐이어서 "캡션이 DB에 없다"는
-- 오조사가 실제로 발생했다. content 단위 최신 1건만 보존 — 전량 스냅샷은 570MB, 최신만 96MB.
-- content에 컬럼을 붙이지 않는 이유: 148k행 백필 UPDATE가 content를 블로트시키고 TOAST를 만든다.
-- CASCADE를 쓰지 않는다: content를 참조하는 기존 raw_* 4개 테이블(raw_discovery_post·raw_post_detail·
-- raw_comment·raw_media_page)이 전부 CASCADE 없는 RESTRICT 규약이다. content_caption만 CASCADE면
-- content 삭제 경로가 생겼을 때 raw_*는 FK 위반으로 삭제를 막는데 캡션만 조용히 사라지는 비대칭이
-- 생긴다. jsonb 보존기간 정책이 도입되면 이 테이블이 캡션의 마지막 사본이 되므로, 조용한 유실보다
-- 요란한 실패가 낫다.
CREATE TABLE content_caption (
    content_id  bigint      PRIMARY KEY REFERENCES content(id),
    caption     text        NOT NULL,   -- 빈 문자열 = 게시물에 캡션 없음(행 존재 = 확인했음)
    source      text        NOT NULL,   -- 어느 원형에서 건졌는지 — 커버리지 추적·사후 검증용
    captured_at timestamptz NOT NULL,   -- 충돌 시 이 값이 더 최신인 쪽이 이긴다
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 백필 재개 워터마크 — 페이지 id 커서. 기준값은 마이그레이션으로 시드하고(변경 이력 보존),
-- ON CONFLICT DO NOTHING으로 진행 중인 런타임 값을 되돌리지 않는다(V16 관용구).
INSERT INTO app_setting(key, value) VALUES
  ('caption.backfill.media-page-id', '0'),  -- raw_media_page 마지막 처리 id
  ('caption.backfill.profile-id', '0')      -- raw_profile 마지막 처리 id
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 3: `SchemaTest`에 테이블 존재 검증을 추가한다**

`SchemaTest.java:23`의 `assertThat(tables).contains(` 목록에서 마지막 항목 `"raw_run_item");` 을
아래로 **교체**한다:

```java
                "raw_comment", "raw_profile", "raw_media_page", "app_setting", "raw_run_item",
                "content_caption");
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.SchemaTest"
```

기대: PASS (Flyway가 V23을 적용하고 테이블이 생긴다). Docker가 안 떠 있으면 Testcontainers 기동
실패이므로 colima를 먼저 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/resources/db/migration/V23__content_caption.sql crawler/src/test/java/com/celfit/crawler/SchemaTest.java
git commit -m "feat(crawler): content_caption 테이블 + 백필 워터마크 시드 (V23)

content 단위 최신 캡션 1건 보존. 빈 문자열은 '캡션 없음', 행 부재는 '미확인'.
신규 테이블이라 expand-contract 무해(DROP·RENAME·기존 컬럼 NOT NULL 없음)."
```

---

## Task 3: 캡션 배치 upsert

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/content/application/service/ContentCaptionUpserter.java`
- Test: `crawler/src/test/java/com/celfit/crawler/content/application/service/ContentCaptionUpserterIntegrationTest.java`

- [ ] **Step 1: 실패하는 통합 테스트를 작성한다**

`crawler/src/test/java/com/celfit/crawler/content/application/service/ContentCaptionUpserterIntegrationTest.java`:

```java
package com.celfit.crawler.content.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.service.MediaItemExtractor.MediaItem;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 캡션 upsert 규칙 검증 — 트랜잭션 없이 실 DB에 쓰고 @AfterEach로 정리한다
 * (CollectJobIntegrationTest와 같은 이유: managed 엔티티 상태가 프로덕션 상황을 가리지 않게).
 */
class ContentCaptionUpserterIntegrationTest extends IntegrationTest {

    private static final Instant OLD = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant NEW = Instant.parse("2026-07-29T00:00:00Z");

    @Autowired ContentCaptionUpserter upserter;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from content_caption");
        jdbc.update("delete from content");
        jdbc.update("delete from influencer");
    }

    private void seedContent(String shortCode) {
        jdbc.update("insert into influencer(username) values (?) on conflict do nothing", "owner");
        Long influencerId = jdbc.queryForObject(
                "select id from influencer where username='owner'", Long.class);
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                values (?, 'FEED', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION')""",
                shortCode, influencerId);
    }

    private String captionOf(String shortCode) {
        return jdbc.queryForObject("""
                select cc.caption from content_caption cc
                join content c on c.id = cc.content_id where c.short_code = ?""",
                String.class, shortCode);
    }

    private MediaItem item(String shortCode, String caption) {
        return new MediaItem(shortCode, OLD, ContentType.FEED, false, caption);
    }

    @Test
    void 캡션을_신규_적재한다() {
        seedContent("SC1");

        int n = upserter.upsert(List.of(item("SC1", "첫 캡션")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(captionOf("SC1")).isEqualTo("첫 캡션");
    }

    @Test
    void 캡션이_없는_게시물도_빈_문자열로_행을_남긴다() {
        seedContent("SC2");

        upserter.upsert(List.of(item("SC2", "")), RawSource.HIKER_V1_MEDIAS, OLD);

        assertThat(captionOf("SC2")).isEqualTo("");
    }

    @Test
    void 더_최신_captured_at이_기존_캡션을_덮는다() {
        seedContent("SC3");
        upserter.upsert(List.of(item("SC3", "옛 캡션")), RawSource.SELF_GQL, OLD);

        upserter.upsert(List.of(item("SC3", "새 캡션")), RawSource.HIKER_V1_MEDIAS, NEW);

        assertThat(captionOf("SC3")).isEqualTo("새 캡션");
        assertThat(jdbc.queryForObject("select source from content_caption", String.class))
                .isEqualTo("HIKER_V1_MEDIAS");
    }

    /** 백필은 과거 페이지를 훑으므로, 라이브가 이미 최신 캡션을 넣었다면 되돌리지 않아야 한다. */
    @Test
    void 더_오래된_captured_at은_기존_캡션을_덮지_않는다() {
        seedContent("SC4");
        upserter.upsert(List.of(item("SC4", "최신 캡션")), RawSource.SELF_GQL, NEW);

        upserter.upsert(List.of(item("SC4", "옛 캡션")), RawSource.HIKER_V1_MEDIAS, OLD);

        assertThat(captionOf("SC4")).isEqualTo("최신 캡션");
    }

    /** content 행이 없는 short_code는 FK 위반으로 터지지 말고 조용히 건너뛴다. */
    @Test
    void content_행이_없는_short_code는_건너뛴다() {
        seedContent("SC5");

        int n = upserter.upsert(
                List.of(item("SC5", "있음"), item("MISSING", "없음")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from content_caption", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 빈_컬렉션은_아무것도_하지_않는다() {
        assertThat(upserter.upsert(List.of(), RawSource.SELF_GQL, OLD)).isZero();
    }

    @Test
    void 여러_content를_한_번에_적재한다() {
        seedContent("SC6");
        seedContent("SC7");

        int n = upserter.upsert(
                List.of(item("SC6", "A"), item("SC7", "B")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(2);
        assertThat(captionOf("SC6")).isEqualTo("A");
        assertThat(captionOf("SC7")).isEqualTo("B");
    }

    /** Javadoc이 명시하는 계약 — dedup이 사라지면 결과가 드라이버 배치 재작성 설정에 의존하게 된다. */
    @Test
    void 배치_안_중복_short_code는_마지막_것만_반영된다() {
        seedContent("SC8");

        int n = upserter.upsert(
                List.of(item("SC8", "옛"), item("SC8", "새")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(captionOf("SC8")).isEqualTo("새");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.content.application.service.ContentCaptionUpserterIntegrationTest"
```

기대: **컴파일 실패** — `cannot find symbol: class ContentCaptionUpserter`.

- [ ] **Step 3: `ContentCaptionUpserter`를 구현한다**

`crawler/src/main/java/com/celfit/crawler/content/application/service/ContentCaptionUpserter.java`:

```java
package com.celfit.crawler.content.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.crawling.application.service.MediaItemExtractor.MediaItem;
import com.celfit.crawler.crawling.domain.RawSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 캡션 원문 적재 — 라이브 수집(COLLECT·REELS)과 일회성 백필(CAPTION_BACKFILL)이 공유한다.
 * content 단위 최신 1건만 두고, 충돌 시 captured_at이 더 최신인 쪽이 이긴다(백필이 과거 페이지를
 * 훑다가 라이브가 넣은 최신 캡션을 되돌리지 않게 하는 장치).
 *
 * <p>JPA 엔티티를 두지 않고 JdbcTemplate 배치를 쓰는 이유: 읽는 코드가 없어(조회는 SQL 직접)
 * 엔티티의 값이 없고, 백필이 약 15만 행을 넣어야 해 행당 왕복이 비싸다.
 */
@Service
public class ContentCaptionUpserter {

    private static final String UPSERT = """
            INSERT INTO content_caption(content_id, caption, source, captured_at, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (content_id) DO UPDATE
               SET caption = EXCLUDED.caption, source = EXCLUDED.source,
                   captured_at = EXCLUDED.captured_at, updated_at = now()
             WHERE content_caption.captured_at <= EXCLUDED.captured_at
            """;

    private final ContentRepository contents;
    private final JdbcTemplate jdbc;

    public ContentCaptionUpserter(ContentRepository contents, JdbcTemplate jdbc) {
        this.contents = contents;
        this.jdbc = jdbc;
    }

    /**
     * 적재를 시도한 행 수를 반환한다(content 행이 없어 건너뛴 것은 제외).
     * 호출자 트랜잭션에 합류한다(JdbcTemplate이 DataSourceUtils로 스레드 바운드 커넥션을 공유) —
     * 이 메서드 자체는 트랜잭션 경계를 열지 않는다.
     *
     * <p>같은 short_code가 배치에 중복되면 마지막 것만 남긴다 — 결과를 드라이버의 배치 재작성
     * 설정에 의존시키지 않기 위함이다. reWriteBatchedInserts=true면 배치가 하나의 다중행
     * INSERT로 합쳐져 같은 충돌 대상이 두 번 나오는 순간 Postgres가 실패시킨다.
     */
    public int upsert(Collection<MediaItem> items, RawSource source, Instant capturedAt) {
        if (items.isEmpty()) return 0;
        Map<String, MediaItem> byShortCode = new LinkedHashMap<>();
        for (MediaItem it : items) byShortCode.put(it.shortCode(), it);

        Map<String, Content> found = contents.findByShortCodeIn(byShortCode.keySet()).stream()
                .collect(Collectors.toMap(Content::getShortCode, Function.identity()));

        List<Object[]> batch = new ArrayList<>();
        for (MediaItem it : byShortCode.values()) {
            Content c = found.get(it.shortCode());
            if (c == null) continue;   // 열거 창 밖 등으로 content가 없는 경우 — 조용히 건너뛴다
            batch.add(new Object[] {
                    c.getId(), it.caption(), source.name(), Timestamp.from(capturedAt) });
        }
        if (batch.isEmpty()) return 0;
        jdbc.batchUpdate(UPSERT, batch);
        return batch.size();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.content.application.service.ContentCaptionUpserterIntegrationTest"
```

기대: PASS (8개 테스트 전부).

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/content/application/service/ContentCaptionUpserter.java crawler/src/test/java/com/celfit/crawler/content/application/service/ContentCaptionUpserterIntegrationTest.java
git commit -m "feat(crawler): 캡션 배치 upsert — 최신 captured_at이 이긴다

라이브 수집과 백필이 공유하는 단일 적재 경로. 백필이 과거 페이지를 훑다가
라이브가 넣은 최신 캡션을 되돌리지 않도록 ON CONFLICT에 captured_at 가드를 둔다.
content 행이 없는 short_code는 FK 위반 대신 조용히 건너뛴다."
```

---

## Task 4: 라이브 수집 경로 배선

> 상태: ✅ 구현·검증 완료(2026-07-30). 코드 커밋 `3430b4af`, 회귀 가드 테스트 커밋(별도) —
> 아래 Step 3·5·6은 코드 리뷰 반영 후의 최종 형태로 갱신했다(원래 계획의 삼항 비교 방식은
> 리뷰에서 "불리언을 분기에 남기고 소스를 파생시키는 편이 직접적"이라는 지적으로 교체됨).

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/CollectJob.java:184-195`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ReelsJob.java:141-142`
- Modify(회귀 가드): `crawler/src/test/java/com/celfit/crawler/crawling/application/service/CollectJobTest.java`,
  `ReelsJobTest.java`, `CollectJobIntegrationTest.java`

- [x] **Step 1: `ReelsJob`에 `ContentCaptionUpserter`를 주입한다**

`ReelsJob.java` 상단 import에 추가한다:

```java
import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
```

기존 `ContentUpserter contentUpserter` 필드 선언 **바로 아래**에 추가한다:

```java
    private final ContentCaptionUpserter captionUpserter;
```

생성자 파라미터 목록에서 `ContentUpserter contentUpserter` 바로 뒤에
`ContentCaptionUpserter captionUpserter`를 추가하고, 생성자 본문의
`this.contentUpserter = contentUpserter;` 바로 아래에 추가한다:

```java
        this.captionUpserter = captionUpserter;
```

- [x] **Step 2: `ReelsJob.visit()`에서 raw 저장과 캡션이 같은 capturedAt을 쓰게 한다**

`ReelsJob.java:139-142` (`rawMediaPages.save(...)`부터 `contentUpserter.upsert(...)`까지 4줄)을
아래 6줄로 **한 번에 교체**한다. `capturedAt`을 한 번만 선언해 raw 원형과 캡션이 같은 시각을
갖게 하는 것이 요점이다:

```java
        Instant capturedAt = clock.instant();
        rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), RawSource.HIKER_V2_CLIPS,
                payload, capturedAt));
        var items = MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS);
        int upserted = contentUpserter.upsert(items, inf);
        // 캡션 적재는 content 행이 생긴 뒤에 온다 — 이유는 CollectJob과 동일(content_id FK).
        captionUpserter.upsert(items, RawSource.HIKER_V2_CLIPS, capturedAt);
```

`java.time.Instant` import가 없으면 추가한다. (코드 리뷰 반영: 캡션 주석을 `CollectJob`과 중복시키지
않고 상호 참조로 바꿨다 — 이 두 파일의 기존 관례.)

- [x] **Step 3: `CollectJob`에 캡션 적재를 붙인다**

`CollectJob.java`에도 Step 1과 같은 방식으로 `ContentCaptionUpserter` 필드·생성자 파라미터·대입·import를 추가한다.

`CollectJob.java:184-195`를 아래로 **교체**한다(코드 리뷰 반영 최종형 — 원래 계획은
`feedSource == RawSource.SELF_GQL`로 다시 비교했는데, 분기 자체는 불리언에 남기고 소스는
나란히 파생시키는 편이 더 직접적이라는 지적으로 바꿨다):

```java
        // 소스를 지역 변수로 고정한다 — 캡션 provenance로 아래에서 다시 쓴다.
        boolean hasEmbedded = MediaItemExtractor.hasEmbeddedTimeline(payload);
        RawSource feedSource = hasEmbedded ? RawSource.SELF_GQL : RawSource.HIKER_V1_MEDIAS;
        Map<String, MediaItemExtractor.MediaItem> inWindow = new LinkedHashMap<>();
        if (hasEmbedded) {
            for (var it : MediaItemExtractor.extract(payload, RawSource.SELF_GQL)) {
                inWindow.putIfAbsent(it.shortCode(), it);
            }
        } else {
            supplementFeedPage(inf, trigger, inWindow);
        }

        // 3) content upsert — 신규 생성·DISCOVERY 승격은 ContentUpserter(REELS 잡과 공유) 규칙.
        // 릴스 1페이지는 별도 REELS 잡으로 분리됐다(유료 HikerAPI 구간).
        int upserted = contentUpserter.upsert(inWindow.values(), inf);
        // 캡션은 content 행이 생긴 뒤에 적재한다(content_id FK).
        captionUpserter.upsert(inWindow.values(), feedSource, clock.instant());
```

- [x] **Step 4: 컴파일과 기존 테스트를 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.*"
```

기대: PASS. `CollectJobIntegrationTest`·`CollectJobTest`·`ReelsJobTest`가 `CollectJob`/`ReelsJob`을
직접 `new` 하고 있어 생성자 인자 추가를 그 객체 생성부에도 반영해야 했다(단위 테스트는
`mock(ContentCaptionUpserter.class)`, `CollectJobIntegrationTest`는 `@Autowired` 실빈).

- [x] **Step 5: 커밋**

```bash
git commit -m "feat(crawler): 라이브 수집 경로가 캡션을 적재한다 ..."   # 3430b4af
```

- [x] **Step 6(코드 리뷰 후 추가): 소스 provenance·capturedAt 공유 회귀 가드**

Step 4까지의 테스트는 `mock(ContentCaptionUpserter.class)`를 주입만 하고 `verify()`가 없어
`feedSource` 삼항이 뒤바뀌거나 `ReelsJob`의 `capturedAt` 공유가 없어져도(=`clock.instant()`를
캡션 적재에 또 불러도) 전부 통과하는 사각지대가 있었다(코드 리뷰에서 지적). 대응:

- `CollectJobTest`: `captionUpserter`를 필드 mock으로 승격해 두 기존 테스트(내장 타임라인 있음/
  없음 각각)에 `verify(captionUpserter).upsert(any(), eq(RawSource.SELF_GQL/HIKER_V1_MEDIAS), any())`
  추가.
- `ReelsJobTest`: 전용 신규 테스트 `raw_원형과_캡션이_같은_capturedAt을_공유한다()` 추가.
  **주의**: 이 클래스 공용 `CLOCK`은 `Clock.fixed`라 매 호출이 같은 값을 반환하므로
  `clock.instant()`를 한 번 부르든 두 번 부르든 결과가 같아 회귀를 못 잡는다 — 이 테스트만
  호출마다 새 값을 주는 mock `Clock`을 `ReelsJob`에 직접 주입해서 우회했다.
  (`RevisitCutoff.boundary()`가 `run()` 안에서 `clock.instant()`를 한 번 먼저 소비하는 것도
  영향을 준다.)
  **tick 생성기는 유한 목록이 아니라 호출마다 새 값을 뽑는 무한 시퀀스로 구현한다**
  (`AtomicLong` 카운터 + `thenAnswer(inv -> NOW.plusSeconds(seq.incrementAndGet()))`) — 처음엔
  유한 목록(`tick1,tick2,tick3,tick3,tick3` 식, Mockito가 소진 후 마지막 값을 반복)으로 짰다가
  두 가지 거짓 양성을 실측으로 겪었다: ① tick을 2개만 두었을 때 `RevisitCutoff.boundary()`의
  숨은 소비를 놓쳐 raw·caption 호출이 같은 반복 값에 걸림, ② 이후 3개로 늘려 회귀를 잡는 데는
  성공했지만, 유한 목록 자체가 "나중에 capturedAt 대입 이전에 clock 호출이 하나 더 늘면
  회귀 시나리오의 두 호출이 다시 반복 구간에 함께 걸려 조용히 무의미해지는" 구조적 결함을
  안고 있다는 코드 리뷰 지적을 받아 무한 생성기로 교체(별도 커밋). 단정은 특정 tick 번호를
  하드코딩하지 않고 캡처한 두 값끼리 비교하는 형태를 유지한다.
- 두 회귀 모두 **실제로 되돌려서 새 단정이 실패하는 것을 확인**한 뒤 원복함(feedSource 삼항
  반전 → `ArgumentsAreDifferent`; `ReelsJob` capturedAt 공유 제거 → `AssertionFailedError`).
  무한 생성기 교체 후에도 같은 되돌리기로 재확인했고, 추가로 "capturedAt 대입 이전에
  `clock.instant()` 호출이 하나 더 늘어도(로깅 등 미래 변경 모의) 정상 코드는 여전히 PASS"까지
  확인했다 — 이게 무한 생성기로 바꾼 목적 자체를 검증한 것이다.

---

## Task 5: 백필 잡

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJob.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawMediaPageRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawProfileRepository.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJobIntegrationTest.java`

- [ ] **Step 1: 리포지토리에 id 커서 배치 조회를 추가한다**

`RawMediaPageRepository.java` 전체를 아래로 **교체**한다:

```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawMediaPage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawMediaPageRepository extends JpaRepository<RawMediaPage, Long> {

    /** 캡션 백필: id 커서로 결정적으로 소진한다(워터마크 재개 가능). */
    List<RawMediaPage> findByIdGreaterThanOrderById(Long id, Pageable pageable);
}
```

`RawProfileRepository.java` 전체를 아래로 **교체**한다:

```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {

    Optional<RawProfile> findTopByInfluencerIdOrderByCapturedAtDesc(Long influencerId);

    /** 캡션 백필: 내장 타임라인을 담는 SELF_GQL만, id 커서로 결정적으로 소진한다. */
    List<RawProfile> findBySourceAndIdGreaterThanOrderById(RawSource source, Long id, Pageable pageable);
}
```

- [ ] **Step 2: 실패하는 통합 테스트를 작성한다**

`crawler/src/test/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJobIntegrationTest.java`:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 백필이 저장된 raw 원형에서 캡션을 소급 적재하는지 — 특히 어떤 analytics 뷰도 읽지 않는
 * HIKER_V1_MEDIAS가 실제로 건져지는지가 이 잡의 존재 이유다.
 */
class CaptionBackfillJobIntegrationTest extends IntegrationTest {

    @Autowired CaptionBackfillJob job;
    @Autowired JdbcTemplate jdbc;

    private Long influencerId;
    private Long runId;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from content_caption");
        jdbc.update("delete from raw_media_page");
        jdbc.update("delete from raw_profile");
        jdbc.update("delete from content");
        jdbc.update("delete from crawl_run");
        jdbc.update("delete from influencer");
        jdbc.update("update app_setting set value='0' where key like 'caption.backfill.%'");
    }

    private void seed() {
        jdbc.update("insert into influencer(username) values ('owner')");
        influencerId = jdbc.queryForObject(
                "select id from influencer where username='owner'", Long.class);
        jdbc.update("""
                insert into crawl_run(job, trigger_type, actor_id, status, started_at)
                values ('COLLECT', 'MANUAL', 'a', 'RUNNING', now())""");
        runId = jdbc.queryForObject("select max(id) from crawl_run", Long.class);
    }

    private void seedContent(String shortCode) {
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                values (?, 'FEED', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION')""",
                shortCode, influencerId);
    }

    private String captionOf(String shortCode) {
        return jdbc.queryForObject("""
                select cc.caption from content_caption cc
                join content c on c.id = cc.content_id where c.short_code = ?""",
                String.class, shortCode);
    }

    @Test
    void v1_medias_원형에서_캡션을_소급_적재한다() {
        seed();
        seedContent("V1CAP");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"V1CAP","taken_at":1773630245,"caption_text":"뷰티 루틴 공유"}]}""");

        var stats = job.run(TriggerType.MANUAL);

        assertThat(captionOf("V1CAP")).isEqualTo("뷰티 루틴 공유");
        assertThat(stats.captions()).isEqualTo(1);
    }

    @Test
    void self_gql_프로필_내장_타임라인에서도_캡션을_적재한다() {
        seed();
        seedContent("SGCAP");
        jdbc.update("""
                insert into raw_profile(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'SELF_GQL', ?::jsonb, now())""", influencerId, runId,
                """
                {"data":{"user":{"edge_owner_to_timeline_media":{"edges":[{"node":{
                "shortcode":"SGCAP","taken_at_timestamp":1773630245,
                "edge_media_to_caption":{"edges":[{"node":{"text":"오늘의 메이크업"}}]}}}]}}}}""");

        job.run(TriggerType.MANUAL);

        assertThat(captionOf("SGCAP")).isEqualTo("오늘의 메이크업");
    }

    /** 워터마크가 전진하므로 두 번째 실행은 같은 페이지를 다시 처리하지 않는다. */
    @Test
    void 재실행하면_워터마크_이후만_처리한다() {
        seed();
        seedContent("W1");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"W1","taken_at":1773630245,"caption_text":"첫 실행"}]}""");
        job.run(TriggerType.MANUAL);

        var second = job.run(TriggerType.MANUAL);

        assertThat(second.pages()).isZero();
        assertThat(captionOf("W1")).isEqualTo("첫 실행");
    }

    @Test
    void 원형이_없으면_아무것도_하지_않는다() {
        seed();

        var stats = job.run(TriggerType.MANUAL);

        assertThat(stats.pages()).isZero();
        assertThat(stats.captions()).isZero();
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.CaptionBackfillJobIntegrationTest"
```

기대: **컴파일 실패** — `cannot find symbol: class CaptionBackfillJob`.

- [ ] **Step 4: `CaptionBackfillJob`을 구현한다**

> **2026-07-30 수정 이력 — 왜 파싱과 쓰기를 트랜잭션 경계로 분리했는가.**
> 최초 구현은 `upsertPage()`의 `try/catch(RuntimeException)`가 `txTemplate.execute` 콜백 **안**에
> 있었다. 스펙 리뷰가 별도 Postgres에서 프로토콜 레벨로 재현한 결함: SQL 레벨 실패(제약 위반·
> 인코딩 오류 등)가 나면 그 트랜잭션이 aborted 상태가 되는데, catch가 예외를 그 자리에서
> 삼켜버려 `txTemplate.execute`가 예외 없이 반환된다. 그러면 커밋이 시도되고, **Postgres는
> aborted 트랜잭션의 COMMIT을 에러 없이 조용히 ROLLBACK으로 치환한다**(클라이언트에 예외가
> 전달되지 않음 — `BEGIN; INSERT(성공); INSERT(제약 위반→ERROR); COMMIT;` 하면 응답 태그가
> ROLLBACK인데도 호출자는 정상 종료로 본다). 그 결과 그 청크에서 이미 성공했던 캡션까지
> 전부 유실되는데 `saveWatermark()`는 무조건 실행돼 커서가 유실된 페이지 너머로 전진한다 —
> 재실행으로도 영구 복구 불가. 당시엔 `MediaItem.caption`이 절대 null이 아니라 우연히
> 트리거되지 않았을 뿐 코드가 보장한 게 아니었다.
>
> 수정: 파싱(`MediaItemExtractor.extract`, DB 무접촉)을 트랜잭션 **밖**에서 먼저 끝내
> "형태 불일치인 페이지만 건너뛴다"는 원래 의도를 그대로 살리고, 쓰기(`captionUpserter.upsert`)는
> 트랜잭션 **안**에서 예외를 삼키지 않는다. `saveWatermark()`도 트랜잭션 안으로 옮겨 캡션 적재와
> 원자적으로 커밋된다 — 실패하면 캡션도 워터마크도 함께 롤백되고, 재실행이 그 청크를 통째로
> 다시 처리한다. 아래 코드는 이 수정이 반영된 최종본이다.

`crawler/src/main/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJob.java`:

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 캡션 소급 적재 — 이미 저장된 raw 원형(raw_media_page·raw_profile)을 훑어 캡션을 채운다.
 * 인스타 API를 호출하지 않으므로 재크롤 비용이 0이다.
 *
 * <p>페이지 주도로 스캔한다(content 주도 아님) — 페이지 1건이 캡션 약 12건을 내놓으므로,
 * content별로 원형을 역방향 조회(LATERAL 탐색)하는 것보다 훨씬 싸다.
 *
 * <p>파싱은 MediaItemExtractor 하나만 쓴다 — 라이브 수집과 같은 파서라 로직이 갈라지지 않는다.
 *
 * <p>재개는 app_setting 워터마크(페이지 id)로 한다. upsert가 멱등이라 중복 실행도 안전하고,
 * 청크 1개 = 트랜잭션 1개라 중간에 죽어도 처리한 만큼은 커밋돼 있다(BeautyJob과 같은 경계).
 *
 * <p>청크 안에서 파싱(DB 무접촉)과 쓰기(트랜잭션 안)를 분리한다 — 파싱 실패를 트랜잭션 안에서
 * 삼키면, SQL 레벨 실패가 그 트랜잭션을 aborted 상태로 만들어도 예외가 호출자에 전달되지 않고
 * Postgres가 COMMIT을 조용히 ROLLBACK으로 치환한다(응답 태그만 보고는 구분 불가). 그러면 이미
 * 성공한 캡션까지 그 청크 전체가 유실되는데 워터마크는 무조건 전진해 재실행으로도 복구 불가가
 * 된다. 그래서 형태 불일치만 안전하게 건너뛰는 파싱을 트랜잭션 밖에서 먼저 끝내고, 쓰기는
 * 예외를 삼키지 않는 트랜잭션 안에서 워터마크 저장과 함께 원자적으로 수행한다.
 */
@Service
public class CaptionBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CaptionBackfillJob.class);

    /** 페이지 1건이 jsonb 수십~수백 KB다 — 청크를 크게 잡으면 힙이 위험하다. */
    static final int PAGE_CHUNK = 200;

    static final String MEDIA_WATERMARK = "caption.backfill.media-page-id";
    static final String PROFILE_WATERMARK = "caption.backfill.profile-id";

    /** 처리한 페이지 수와 적재 시도한 캡션 수. */
    public record Stats(int pages, int captions) {}

    private final RawMediaPageRepository mediaPages;
    private final RawProfileRepository profiles;
    private final ContentCaptionUpserter captionUpserter;
    private final AppSettingRepository settings;
    private final TransactionTemplate txTemplate;
    private final JobStopFlag stopFlag;

    public CaptionBackfillJob(RawMediaPageRepository mediaPages, RawProfileRepository profiles,
                              ContentCaptionUpserter captionUpserter, AppSettingRepository settings,
                              TransactionTemplate txTemplate, JobStopFlag stopFlag) {
        this.mediaPages = mediaPages;
        this.profiles = profiles;
        this.captionUpserter = captionUpserter;
        this.settings = settings;
        this.txTemplate = txTemplate;
        this.stopFlag = stopFlag;
    }

    public Stats run(TriggerType trigger) {
        log.info("캡션 백필 시작 (trigger={}) — raw 원형에서 소급 적재, 인스타 호출 없음", trigger);
        Stats media = backfillMediaPages();
        Stats profile = backfillProfiles();
        Stats total = new Stats(media.pages() + profile.pages(),
                media.captions() + profile.captions());
        log.info("캡션 백필 완료 — 페이지 {}건 처리, 캡션 {}건 적재", total.pages(), total.captions());
        return total;
    }

    /** raw_media_page: HIKER_V2_CLIPS·HIKER_V1_MEDIAS·HIKER_GQL_MEDIAS 전부 — source는 행에서 읽는다. */
    private Stats backfillMediaPages() {
        int pages = 0, captions = 0;
        long cursor = watermark(MEDIA_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawMediaPage> chunk =
                    mediaPages.findByIdGreaterThanOrderById(cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;

            // 파싱은 트랜잭션 밖에서 — DB를 만지지 않으므로 형태 불일치인 페이지만 안전하게 건너뛴다.
            // (트랜잭션 안에서 삼키면 SQL 실패 시 Postgres가 커밋을 조용히 ROLLBACK으로 치환해
            //  그 청크에서 이미 성공한 캡션까지 유실되고, 워터마크는 전진해 영구 복구 불가가 된다.)
            List<PageItems> parsed = new ArrayList<>();
            for (RawMediaPage page : chunk) {
                parseInto(parsed, page.getPayload(), page.getSource(), page.getCapturedAt());
            }
            long last = chunk.get(chunk.size() - 1).getId();
            // 쓰기는 트랜잭션 안에서 — SQL 실패는 삼키지 않는다(청크 롤백 + 워터마크 미전진 =
            // 요란한 실패로 드러나고 재실행이 그 청크를 다시 처리한다).
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (PageItems p : parsed) {
                    c += captionUpserter.upsert(p.items(), p.source(), p.capturedAt());
                }
                saveWatermark(MEDIA_WATERMARK, last);   // 캡션과 워터마크를 원자적으로
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
            log.info("캡션 백필(media_page) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions);
    }

    /** raw_profile: 내장 타임라인을 담는 SELF_GQL만 — 다른 source엔 게시물 배열이 없다. */
    private Stats backfillProfiles() {
        int pages = 0, captions = 0;
        long cursor = watermark(PROFILE_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawProfile> chunk = profiles.findBySourceAndIdGreaterThanOrderById(
                    RawSource.SELF_GQL, cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;

            List<PageItems> parsed = new ArrayList<>();
            for (RawProfile p : chunk) {
                parseInto(parsed, p.getPayload(), RawSource.SELF_GQL, p.getCapturedAt());
            }
            long last = chunk.get(chunk.size() - 1).getId();
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (PageItems p : parsed) {
                    c += captionUpserter.upsert(p.items(), p.source(), p.capturedAt());
                }
                saveWatermark(PROFILE_WATERMARK, last);   // 캡션과 워터마크를 원자적으로
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
            log.info("캡션 백필(profile) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions);
    }

    /** 트랜잭션 밖에서 파싱한 페이지 1건의 결과. */
    private record PageItems(List<MediaItemExtractor.MediaItem> items, RawSource source,
                             Instant capturedAt) {}

    /**
     * 페이지 1건 파싱 — DB를 만지지 않는다. 형태 불일치·파싱 실패는 그 페이지만 건너뛴다
     * (원형은 raw 테이블에 남아 있으니 유실이 아니다). 빈 결과는 담지 않는다.
     */
    private void parseInto(List<PageItems> out, Map<String, Object> payload, RawSource source,
                           Instant capturedAt) {
        if (payload == null) return;
        try {
            var items = MediaItemExtractor.extract(payload, source);
            if (!items.isEmpty()) out.add(new PageItems(items, source, capturedAt));
        } catch (RuntimeException e) {
            log.warn("캡션 백필 페이지 파싱 실패 — 건너뜀 (source={}): {}", source, e.toString());
        }
    }

    private long watermark(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(Long::parseLong).orElse(0L);
    }

    private void saveWatermark(String key, long value) {
        settings.save(new AppSetting(key, Long.toString(value)));
    }
}
```

- [ ] **Step 4-1: 청크 안 쓰기 실패에 대한 회귀 테스트를 추가한다**

`CaptionBackfillJobIntegrationTest`에 `@MockitoSpyBean ContentCaptionUpserter captionUpserter`
필드를 추가하고, 정상 페이지 하나와 스텁으로 실패시킨 페이지 하나를 같은 청크에 섞어
`job.run()`이 예외를 전파하는지·그 청크의 캡션이 전혀 커밋되지 않는지·워터마크가 전진하지
않는지를 검증한다. NUL 바이트로 진짜 Postgres 인코딩 오류를 유도하는 안은 raw_media_page를
심는 시딩 단계의 jsonb 캐스팅 자체가 거부해 막혔다(psql로 직접 확인 — `unsupported Unicode
escape sequence`). 이 코드베이스의 파싱 계약이 정상 흐름에서 그 상태를 만들지 못하게 해
순수 통합 테스트만으로는 진짜 SQL 레벨 실패를 재현할 수 없었다. 이 회귀 테스트는 옛 버그
(쓰기 트랜잭션 콜백 안에서 예외를 삼키는 코드)를 일시적으로 재도입해 실제로 실패하는 것까지
확인했다.

- [ ] **Step 5: `JobName`에 `CAPTION_BACKFILL`을 추가한다**

`JobName.java`의 `RESNAPSHOT, AGGREGATE;` 줄을 아래로 **교체**한다:

```java
    RESNAPSHOT, AGGREGATE,
    /** 저장된 raw 원형에서 캡션 소급 적재 — 인스타 호출 없음, 수동 전용(스케줄 미등록). */
    CAPTION_BACKFILL(false);
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.CaptionBackfillJobIntegrationTest"
```

기대: PASS (5개 테스트 — Step 2의 4개 + Step 4-1 회귀 테스트 1개). `JobService`가 exhaustive
switch라 여기서 **컴파일 에러**가 날 수 있다 — 그러면 Task 6 Step 1을 먼저 적용한 뒤 이 단계를
다시 실행한다.

- [ ] **Step 7: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJob.java crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawMediaPageRepository.java crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawProfileRepository.java crawler/src/main/java/com/celfit/crawler/crawling/domain/JobName.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/CaptionBackfillJobIntegrationTest.java
git commit -m "feat(crawler): 캡션 소급 적재 백필 잡

저장된 raw 원형을 페이지 주도로 훑어 캡션을 채운다 — 인스타 호출 0회.
파싱은 라이브와 같은 MediaItemExtractor 하나만 써서 로직이 갈라지지 않는다.
청크 1개=트랜잭션 1개(BeautyJob 경계), 재개는 app_setting 워터마크."
```

---

## Task 6: 잡 배선과 어드민 노출

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java`
- Modify: `crawler/src/main/resources/templates/dashboard.html`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiController.java`

이 3+1 파일은 **잡별 하드코딩**이라 새 잡이 자동 편입되지 않는다.

- [ ] **Step 1: `JobService`에 실행 분기를 추가한다**

`JobService.java`에 필드·생성자 파라미터·대입을 추가한다:

```java
    private final CaptionBackfillJob captionBackfillJob;
```

생성자 파라미터에 `CaptionBackfillJob captionBackfillJob`를 추가하고 `this.captionBackfillJob = captionBackfillJob;`를 대입한다.

switch의 `case RESNAPSHOT, AGGREGATE -> log.warn(...)` **바로 위**에 아래를 추가한다:

```java
                    case CAPTION_BACKFILL -> {
                        var s = captionBackfillJob.run(triggerType);
                        log.info("caption-backfill 완료: {}", s);
                    }
```

- [ ] **Step 2: 트리거 엔드포인트를 추가한다**

`UiJobController.java`의 `reels()` 메서드 **바로 아래**에 추가한다:

```java
    /** 저장된 raw 원형에서 캡션 소급 적재 — 인스타 호출 없음. 일회성 운영 작업. */
    @PostMapping("/caption-backfill")
    public String captionBackfill(RedirectAttributes ra) {
        return respond(JobName.CAPTION_BACKFILL,
                jobService.trigger(JobName.CAPTION_BACKFILL, TriggerType.MANUAL), ra);
    }
```

- [ ] **Step 3: 어드민 버튼을 추가한다**

`crawler/src/main/resources/templates/dashboard.html`의 잡 실행 스트립에서 `similar` 폼
**바로 아래**(닫는 `</div>` 앞)에 추가한다:

```html
    <form method="post" th:action="@{/ui/jobs/caption-backfill}">
        <span class="job-strip-name">caption-backfill</span>
        <button type="submit" class="primary"
                title="저장된 raw 원형에서 캡션 소급 적재 (인스타 호출 없음 · 워터마크로 재개)">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/CAPTION_BACKFILL/stop}"
                title="진행 중인 페이지 청크까지 마치고 멈춘다">중지</button>
    </form>
```

- [ ] **Step 4: 진행 상태 목록에 추가한다**

`UiController.java`의 `statusFragment()`에서 `jobStatus(JobName.REELS, "릴스수집")));` 을 아래로 **교체**한다:

```java
                jobStatus(JobName.REELS, "릴스수집"),
                jobStatus(JobName.CAPTION_BACKFILL, "캡션백필")));
```

- [ ] **Step 5: 전체 crawler 테스트를 돌린다**

```bash
./gradlew :crawler:test
```

기대: PASS. 실패하면 대개 `JobService`/`CollectJob`/`ReelsJob`을 직접 `new` 하는 테스트의 생성자
인자 누락이다 — 새 파라미터를 그 테스트에도 넘긴다.

- [ ] **Step 6: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobService.java crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiController.java crawler/src/main/resources/templates/dashboard.html
git commit -m "feat(crawler): 캡션 백필 잡 어드민 배선

JobService switch·트리거 엔드포인트·대시보드 버튼·진행 바 4곳 모두 잡별
하드코딩이라 수동 추가가 필요하다. ScheduleRunner엔 등록하지 않는다 —
일회성 운영 작업이라 수동 전용."
```

---

## Task 7: 죽은 테이블 `raw_post_detail` 제거

`raw_post_detail`은 운영 DB에서 **0행**이고, Java 엔티티·리포지토리가 존재하지 않으며(`find`·`grep`
각 0건) 잔존 참조는 마이그레이션 DDL 이력·`SchemaTest` 존재 확인·문서성 주석뿐이다. 참조 코드가
이미 끊긴 상태라 expand-contract의 contract 단계 조건을 충족한다.

**Files:**
- Create: `crawler/src/main/resources/db/migration/V23__drop_raw_post_detail.sql`
- Modify: `crawler/src/test/java/com/celfit/crawler/SchemaTest.java:23`

- [ ] **Step 1: 운영에서 0행임을 다시 확인한다**

```bash
ssh hypenow 'docker exec deploy-postgres-raw-1 psql -U crawler -d crawler -At -c "select count(*) from raw_post_detail"'
```

기대: `0`. **0이 아니면 이 태스크를 중단하고 사용자에게 보고한다** — 데이터가 있는 테이블을 지우는
것은 이 계획의 범위가 아니다.

- [ ] **Step 2: 마이그레이션을 작성한다**

`crawler/src/main/resources/db/migration/V23__drop_raw_post_detail.sql`:

```sql
-- allow-destructive: 구 파이프라인 상세 payload 테이블. 운영 0행이며 참조 코드가 이미 끊겼다.
-- no-backfill: 0행이라 보정할 데이터가 없다.
--
-- 배경: 신 파이프라인(V15~)은 게시물 상세를 따로 수집하지 않는다 — 릴스는 raw_media_page,
-- 피드는 raw_profile 내장 타임라인이 원형이다. 07-22 열람 화면 제거로 접근 코드까지 삭제되어
-- 엔티티·리포지토리가 존재하지 않고, 남은 참조는 이 마이그레이션 이력과 주석뿐이었다.
-- 캡션 조회 시 이 테이블을 조인해 "캡션이 DB에 없다"는 오조사가 실제로 발생했다(2026-07-30) —
-- 빈 테이블이 살아 있는 것 자체가 오답의 원인이므로 정리한다.
--
-- 주의: crawler 마이그레이션은 CI migration-guard 검사 대상이 아니다(was·analytics만 검사).
-- 위 주석은 자동 통과용이 아니라 사람 리뷰어용이다.
DROP TABLE raw_post_detail;
```

- [ ] **Step 3: `SchemaTest`를 고친다**

`SchemaTest.java`의 `contains(...)` 목록에서 `"raw_post_detail",` 을 **삭제**하고, 아래
`doesNotContain` 단정에 추가한다. `doesNotContain` 줄을 아래로 **교체**한다:

```java
        // V8에서 인플루언서 중심으로 개편되며 카테고리 체계는 완전히 걷어냈다.
        // raw_post_detail은 구 파이프라인 상세 payload — V23에서 제거(운영 0행·참조 코드 부재).
        assertThat(tables).doesNotContain("category", "category_keyword", "collection_rule", "account",
                "raw_post_detail");
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.SchemaTest"
```

기대: PASS.

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/resources/db/migration/V23__drop_raw_post_detail.sql crawler/src/test/java/com/celfit/crawler/SchemaTest.java
git commit -m "chore(crawler): 죽은 테이블 raw_post_detail 제거 (V23)

운영 0행·엔티티/리포지토리 부재로 contract 단계 조건 충족. 빈 테이블이
살아 있어 캡션 조사가 여기를 조인하고 '캡션이 DB에 없다'는 오답에 도달한
전력이 있다(07-30)."
```

---

## Task 8: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§3 raw DB 표 · §5 작업 트랙 표 · §7 결정 기록)

- [ ] **Step 1: §3 raw DB 표를 고친다**

`content` 행의 설명 `— 캡션·지표 없음`을 `— 지표 없음(캡션은 content_caption)`으로 바꾸고,
`raw_post_detail` 행을 **삭제**한 뒤, 표에 아래 행을 추가한다:

```markdown
| `content_caption` | 게시물 캡션 원문 (content_id PK, 최신 1건) — jsonb 원형에서 추출·보존. 빈 문자열=캡션 없음, 행 부재=미확인 |
```

`raw_media_page`·`raw_profile` 행의 설명에 캡션 원천임을 이미 적어두었으므로, 그 뒤에
`→ content_caption으로 추출됨`을 덧붙인다.

- [ ] **Step 2: §3 아래에 캡션 조회 경로를 명시한다**

raw DB 표 바로 아래에 추가한다 (이번 오조사의 재발 방지가 목적이다):

```markdown
**캡션을 찾을 때**: `content_caption`을 본다 — `SELECT caption FROM content_caption WHERE content_id = ?`.
jsonb 원형에서 직접 파는 경로(`raw_media_page.payload#>>'{response,items,N,media,caption,text}'`,
`raw_profile.payload#>>'{data,user,edge_owner_to_timeline_media,edges,N,node,edge_media_to_caption,edges,0,node,text}'`)도
여전히 유효하지만 정본은 `content_caption`이다. **`raw_discovery_post`는 `origin='DISCOVERY'`만
커버하므로 캡션 조회의 정본이 아니다** — 이걸 조인해 "캡션이 DB에 없다"고 오판한 전력이 있다(07-30).
```

- [ ] **Step 3: §5 작업 트랙 표에 트랙 `AA` 행을 추가한다**

**단일 문자 A~Z가 전부 소진됐다**(Z = 하입 스코어 v3). 이 트랙부터 두 글자로 넘어간다 — 표 마지막
행(`Z`) 바로 아래에 추가한다:

```markdown
| AA | 게시물 캡션 원문 보존 | 캡션 원문이 raw jsonb(`raw_media_page`·`raw_profile`)에 실재하는데도 `MediaItemExtractor`가 파싱하지 않아 사장돼 있었고(유실이 아니라 추출 부재), 도달 경로가 5~7단 jsonb 표현식뿐이라 "캡션이 DB에 없다"는 오조사가 실제로 발생(07-30, `raw_post_detail` 0행·`raw_discovery_post` DISCOVERY 전용을 조인). `content_caption` 신설(V23 — content_id PK·최신 1건·96MB, 압축 불필요: pglz 실측 이득 7%) + 추출기가 세 소스에서 캡션 추출(`unwrapMedia()`가 이미 정규화하므로 단일 헬퍼) + 라이브 배선 + `CAPTION_BACKFILL` 원샷 잡(저장된 원형 소급 적재·인스타 호출 0회·라이브와 동일 파서 재사용·app_setting 워터마크 재개). **어떤 analytics 뷰도 읽지 않는 현역 소스 `HIKER_V1_MEDIAS`**(9,691행·1GB·07-18~) 캡션 약 6,240건을 재크롤 0원으로 건짐(표본 40건 중 33건=82.5% 실측) — 단 **저장 측면만** 해소, 서빙 반영(뷰가 `content_caption`을 읽도록 전환)은 analytics 트랙 후속. `raw_post_detail`(0행·참조 코드 부재) DROP 동반(V23) — [specs/2026-07-30-caption-preservation-design.md](docs/superpowers/specs/2026-07-30-caption-preservation-design.md) | — | 🔨 (구현 완료 — PR·배포·백필 수동 실행 대기) |
```

**주의:** 트랙 문자 체계가 두 글자로 확장됐다는 사실을 §5 표 위 설명이나 §7에 한 줄로 남겨,
다음 사람이 어디서 이어야 할지 알 수 있게 한다.

- [ ] **Step 4: §7 결정 기록에 한 줄을 추가한다**

```markdown
- **2026-07-30** — 캡션 원문은 raw jsonb에 있었으나 `MediaItemExtractor`가 파싱하지 않아 사장돼
  있었다. 추출기를 정본 파서로 삼고 `content_caption`(96MB)에 보존한다. `content`에 컬럼을 붙이지
  않은 이유는 148k행 백필 UPDATE의 블로트. 압축·보존기간 제한은 두지 않는다(pglz 실측 이득 7%).
  `HIKER_V1_MEDIAS`(9,691행·현역)를 어떤 analytics 뷰도 읽지 않는 갭은 **저장 측면만** 해소 —
  서빙 반영은 analytics 트랙. `raw_post_detail`(0행) 제거.
```

- [ ] **Step 5: 커밋**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 캡션 보존 반영 — §3 조회 경로·§5 트랙·§7 결정 기록

'캡션을 찾을 때' 항목을 §3에 명시한다 — raw_discovery_post를 조인해
캡션이 없다고 오판한 07-30 전력의 재발 방지."
```

---

## Task 9: 전체 검증과 PR

- [ ] **Step 1: 전체 테스트를 돌린다**

```bash
./gradlew test
```

기대: 전 모듈 PASS. 실패가 있으면 **여기서 멈추고** 원인을 고친다 — 실패한 채로 PR을 올리지 않는다.

- [ ] **Step 2: 마이그레이션 번호 경합을 재확인한다**

```bash
git fetch origin --quiet && git ls-tree -r origin/develop --name-only -- crawler/src/main/resources/db/migration/ | sed 's|.*/||' | sort -t V -k2 -n | tail -3 && ls crawler/src/main/resources/db/migration/ | sort -t V -k2 -n | tail -3
```

기대: `origin/develop`의 최대가 V21이고 내 브랜치가 V22·V23. **develop에 V22 이상이 생겼으면
파일명을 재번호하고 커밋한다** (07-20 V18 경합 사고 재발 방지).

- [ ] **Step 3: 커밋 로그를 확인한다**

```bash
git log --oneline origin/develop..HEAD
```

기대: 스펙 1건 + 구현 7건 = 8커밋.

- [ ] **Step 4: 푸시하고 PR을 만든다**

```bash
git push -u origin feat/content-caption-preservation
```

```bash
gh pr create --base develop --title "feat(crawler): 게시물 캡션 원문 보존 — content_caption 신설" --body "$(cat <<'EOF'
## 배경

계정 뷰티 판정 오판 조사 중 "캡션 원문이 DB에 없다"는 결론이 나왔으나, **실측으로 전제가 틀린 것을 확인**했다. 캡션은 raw jsonb에 보존돼 있고 조인 대상 테이블(`raw_post_detail` 0행 · `raw_discovery_post` DISCOVERY 전용)이 잘못 선택된 것이었다.

그러나 조사가 오답에 도달했다는 사실 자체가 실재하는 결함을 가리킨다 — **캡션에 도달하는 유일한 경로가 5~7단 jsonb 표현식이면, 그것을 모르는 사람에게 캡션은 존재하지 않는 것과 같다.**

## 유실 지점

`MediaItemExtractor.MediaItem`에 캡션 필드가 없어 **세 소스 모두 캡션을 파싱조차 하지 않았다.** 저장 실패가 아니라 추출 부재다.

## 실측 (2026-07-30 운영 raw DB, 읽기 전용)

| 항목 | 수치 |
|---|---|
| ENUMERATION 캡션 커버리지 | REELS 98.9% / FEED 88.7% |
| `HIKER_V1_MEDIAS` (뷰가 읽지 않는 현역 소스) | 9,691행 · 1,062MB · 07-18~현재 |
| V1_MEDIAS로 복원 가능한 FEED 캡션 | 표본 40건 중 33건(82.5%) → **약 6,240건 추정** |
| 캡션 보존 비용 | 148,559건 · **96MB** (pglz 실측 이득 7%뿐 → 압축 불필요) |

## 변경

- `content_caption` 신설 (V23) — `content_id` PK, 최신 1건, `source` provenance 기록
- `MediaItemExtractor`가 세 소스에서 캡션 추출 — `unwrapMedia()`가 이미 정규화하므로 단일 헬퍼
- 라이브 수집(COLLECT·REELS) 배선 — 앞으로 유실 없음
- `CAPTION_BACKFILL` 잡 — 저장된 원형 소급 적재, **인스타 호출 0회**. 라이브와 같은 파서 재사용
- `raw_post_detail` DROP (V23) — 0행·참조 코드 부재로 contract 조건 충족

## 범위 밖 (의도적)

**서빙 커버리지는 이 PR로 바뀌지 않는다.** analytics 뷰는 여전히 jsonb를 직접 읽고 `HIKER_V1_MEDIAS`를 무시하므로, 복원된 ~6,240건은 저장되어도 뷰·랭킹·LLM 판정에 반영되지 않는다. analytics 층 작업이라 제외했다.

후속: ① 뷰가 `content_caption`을 읽도록 전환(V1_MEDIAS 갭 자동 해소) ② `ProfileExtractor.recentCaptions()` 소스 집합 점검(뷰티 오판 연결) ③ `HIKER_V1_MEDIAS`가 왜 현역인지(crawler 트랙) ④ `backup.sh`에 파생 캐시 제외(PR #193 트랙 — `analytics`+`analytics_dev` 캐시 2.05GB가 매일 덤프에 실림)

## 주의

- **crawler 마이그레이션은 CI migration-guard 검사 대상이 아니다**(was·analytics만). 파괴 DDL 자동 차단이 없으니 V23을 사람이 검토해주기 바란다.
- 백필은 jsonb 약 14GB를 1회 읽는다 — 크롤 잡과 겹치지 않는 시각에 `/ui`에서 수동 실행할 것.

설계 문서: `docs/superpowers/specs/2026-07-30-caption-preservation-design.md`
구현 계획: `docs/superpowers/plans/2026-07-30-caption-preservation.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: 계획서를 아카이브로 옮긴다**

```bash
git mv docs/superpowers/plans/2026-07-30-caption-preservation.md docs/superpowers/plans/archive/
git commit -m "docs: 캡션 보존 구현 계획 아카이브 이동 (실행 완료)"
git push
```

---

## 운영 반영 절차 (PR 머지 후 — 실행은 사용자 승인 필요)

이 계획은 **코드까지만** 다룬다. 아래는 머지 후 별도 승인을 받아 수행한다.

1. develop 머지 → CI만 돎(배포 없음)
2. develop → staging 머지 = test 스테이징 배포(dev-api.hypenow.io)
3. staging → main 머지 = 운영 배포. Flyway V22·V23이 이때 적용된다
4. 배포 후 `/ui`에서 `caption-backfill` **수동 실행** — 크롤 잡(KST 06:00 전후)과 겹치지 않는 시각
5. 백필 후 커버리지 검증:

```sql
SELECT c.content_type, count(*) AS total,
       count(cc.content_id) AS with_row,
       count(*) FILTER (WHERE cc.caption <> '') AS with_caption
FROM content c LEFT JOIN content_caption cc ON cc.content_id = c.id
WHERE c.origin = 'ENUMERATION'
GROUP BY c.content_type;
```

기대: FEED의 `with_caption`이 59,618(88.7%)에서 약 65,800(96%대)으로 증가. REELS는 거의 불변.
