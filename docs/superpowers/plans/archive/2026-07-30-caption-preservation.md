# 게시물 캡션 원문 보존 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crawler가 수집한 게시물 캡션 원문을 `content_caption` 테이블에 보존해, jsonb 깊은 경로를 몰라도 조회 가능하게 하고 현재 파싱되지 않는 `HIKER_V1_MEDIAS` 캡션 약 6,240건을 건져낸다.

**Architecture:** 캡션은 이미 raw jsonb(`raw_media_page`·`raw_profile`)에 있으나 `MediaItemExtractor`가 파싱하지 않아 버려진다. 추출기가 세 소스에서 캡션을 뽑도록 고치고(단일 정본 파서), 라이브 수집 경로와 일회성 백필 잡이 **같은 추출기를 재사용**해 파싱 로직이 갈라지지 않게 한다. 저장은 `content_id` PK 단일 테이블(약 96 MB), 쓰기는 `ON CONFLICT` 배치 upsert.

**Tech Stack:** Java 21, Spring Boot 4.1, JPA/Hibernate(`@JdbcTypeCode(SqlTypes.JSON)`), JdbcTemplate 배치, Flyway(crawler = raw DB 소유), JUnit 5 + AssertJ + Mockito, Testcontainers 2.x(`org.testcontainers.postgresql.PostgreSQLContainer`), Gradle 멀티모듈.

**설계 문서:** [docs/superpowers/specs/2026-07-30-caption-preservation-design.md](../../specs/2026-07-30-caption-preservation-design.md)

> **재채번 이력(07-30):** 이 계획서 곳곳에 등장하는 `V23__content_caption.sql`·
> `V24__drop_raw_post_detail.sql`은 실행 당시 번호다. develop이 이후 신규 Flyway 마이그레이션의
> UTC 타임스탬프 채번을 규약화하면서(PR #237), 머지 전이라 안전한 이 두 파일을
> `V20260730122500__content_caption.sql`·`V20260730122600__drop_raw_post_detail.sql`로
> 재번호했다(상대 순서 보존). 아래 본문의 V23/V24 언급은 당시 기록 그대로 남긴다.

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

**빈 캡션 정책 (2026-07-30 최종 종합 리뷰로 개정 — 아래 "Task 10" 참고):** 최초 구현은 `captionOf()`가
절대 null을 반환하지 않고 못 찾으면 `""`를 반환했다. 그런데 이 `""`는 "캡션이 없다"와 "이 payload
형태에서 캡션을 읽는 방법을 모른다"를 구분하지 못해, 같은 content가 다른 소스 페이지에 다시
등장할 때(타임라인은 릴스도 담고 V1_MEDIAS는 피드·릴스를 함께 담는다) 더 최신 페이지의 `""`가
실제 캡션을 덮어쓰는 결함이 있었다(머지 차단급). 최종 형태는 3-상태다: `captionOf()`가
**미확인(형태를 못 알아봄)은 `null`**, **확인된 무캡션(형태는 인식했고 값이 비어 있음)은 `""`**를
반환하고, `ContentCaptionUpserter.upsert()`가 `caption == null`인 아이템을 배치에서 제외한다.
행 존재 = "확인했음"이 이 개정으로 비로소 참이 된다.

**`RawSource` enum 값 전체:** `LEGACY_ENVELOPE, APIFY_ACTOR, HIKER_MOBILE, HIKER_HASHTAG, SELF_GQL,
HIKER_GQL_MEDIAS, HIKER_V2_CLIPS, HIKER_V1_MEDIAS, DATALIKERS`

**주의 — CI 안전망 범위:** `.github/scripts/check-migration-safety.sh`의 **파괴적 DDL 검사**는
`was`+`analytics` 마이그레이션만 본다(롤링 배포 중 신구 코드 공존 근거가 그 두 DB에만 있다) —
crawler 마이그레이션은 이 검사가 **자동 차단하지 않는다**. 그러니 `-- allow-destructive:` 주석은
사람 리뷰어용으로 남기고, DDL은 직접 더 조심해서 검토한다. 단, 같은 스크립트의 **버전 중복
검사(v3)는 crawler·monitoring까지 4개 디렉토리 전부를 대상**으로 한다 — 실패 모드가 달라서다
(어느 Flyway 인스턴스든 중복 버전이면 그 인스턴스 자체가 기동을 거부하므로 신구 공존 여부와
무관). Task 7의 V22 번호 경합(open PR #216 선점)은 이 검사로도 기계적으로 잡혔을 사안이다.

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
| `crawler/src/main/resources/db/migration/V24__drop_raw_post_detail.sql` | 죽은 테이블 제거 (contract 단계) |
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
>
> **2026-07-30 수정 이력 2 — 커버리지 공백 보강.** 코드 품질 리뷰가 승인하며 두 가지를 짚었다:
> (1) 기존 테스트가 전부 raw 행 1~2건이라 `PAGE_CHUNK`(200) 경계를 넘는 커서 전진이 한 번도
> 실행되지 않았다 — "DB 재조회 커서 루프"는 이 잡이 이 코드베이스에 처음 도입한 패턴이라 기존
> 테스트가 전혀 커버하지 못했고, 운영에서는 raw_media_page 46,450행 기준 약 233회 청크
> 루프를 돈다. (2) `stopFlag` 중지 경로("중지해도 재개 가능"이라는 클래스 Javadoc의 주장)도
> 테스트가 없었다. 여기에 `Stats.skippedParse`도 추가했다 — 형태 불일치로 건너뛴 페이지가
> `log.warn`에만 남고 반환값에 없어, 백필을 운영에서 돌린 뒤 "건너뛴 페이지가 0인지"를
> 판단할 방법이 없었다(`BeautyJob.Summary`가 `skippedNoProfile`·`failedBatches`를 명시
> 집계하는 것과 같은 결). Step 4-2에 상세.

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

    /**
     * 페이지 1건이 jsonb 수십~수백 KB다(2026-07-30 최종 종합 리뷰로 200→50 하향 — Task 10 참고,
     * 최대 소스 HIKER_V2_CLIPS 실측 평균이 그 범위 상단인 약 196KB/행이라 청크를 크게 잡으면
     * 힙이 위험하다는 게 실측으로 확인됨).
     */
    static final int PAGE_CHUNK = 50;

    static final String MEDIA_WATERMARK = "caption.backfill.media-page-id";
    static final String PROFILE_WATERMARK = "caption.backfill.profile-id";

    /** 처리한 페이지 수, 적재 시도한 캡션 수, 파싱 실패로 건너뛴 페이지 수. */
    public record Stats(int pages, int captions, int skippedParse) {}

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
                media.captions() + profile.captions(),
                media.skippedParse() + profile.skippedParse());
        // 건너뛴 페이지가 0인지가 백필이 제대로 됐는지의 핵심 지표라 warn으로 승격한다
        // (BeautyJob이 부분 실패를 warn으로 승격하는 관용구와 같은 결).
        if (total.skippedParse() > 0) {
            log.warn("캡션 백필 완료 — 페이지 {}건 / 캡션 {}건 / 파싱 실패 건너뜀 {}건",
                    total.pages(), total.captions(), total.skippedParse());
        } else {
            log.info("캡션 백필 완료 — 페이지 {}건 처리, 캡션 {}건 적재", total.pages(), total.captions());
        }
        return total;
    }

    /** raw_media_page: HIKER_V2_CLIPS·HIKER_V1_MEDIAS·HIKER_GQL_MEDIAS 전부 — source는 행에서 읽는다. */
    private Stats backfillMediaPages() {
        int pages = 0, captions = 0, skippedParse = 0;
        long cursor = watermark(MEDIA_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawMediaPage> chunk =
                    mediaPages.findByIdGreaterThanOrderById(cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;

            // 파싱은 트랜잭션 밖에서 — DB를 만지지 않으므로 형태 불일치인 페이지만 안전하게 건너뛴다.
            // (트랜잭션 안에서 삼키면 SQL 실패 시 Postgres가 커밋을 조용히 ROLLBACK으로 치환해
            //  그 청크에서 이미 성공한 캡션까지 유실되고, 워터마크는 전진해 영구 복구 불가가 된다.)
            List<PageItems> parsed = new ArrayList<>();
            int skippedInChunk = 0;
            for (RawMediaPage page : chunk) {
                if (parseInto(parsed, page.getPayload(), page.getSource(), page.getCapturedAt())) {
                    skippedInChunk++;
                }
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
            skippedParse += skippedInChunk;
            cursor = last;
            log.info("캡션 백필(media_page) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions, skippedParse);
    }

    /** raw_profile: 내장 타임라인을 담는 SELF_GQL만 — 다른 source엔 게시물 배열이 없다. */
    private Stats backfillProfiles() {
        int pages = 0, captions = 0, skippedParse = 0;
        long cursor = watermark(PROFILE_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawProfile> chunk = profiles.findBySourceAndIdGreaterThanOrderById(
                    RawSource.SELF_GQL, cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;

            List<PageItems> parsed = new ArrayList<>();
            int skippedInChunk = 0;
            for (RawProfile p : chunk) {
                if (parseInto(parsed, p.getPayload(), RawSource.SELF_GQL, p.getCapturedAt())) {
                    skippedInChunk++;
                }
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
            skippedParse += skippedInChunk;
            cursor = last;
            log.info("캡션 백필(profile) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions, skippedParse);
    }

    /** 트랜잭션 밖에서 파싱한 페이지 1건의 결과. */
    private record PageItems(List<MediaItemExtractor.MediaItem> items, RawSource source,
                             Instant capturedAt) {}

    /**
     * 페이지 1건 파싱 — DB를 만지지 않는다. 형태 불일치·파싱 실패는 그 페이지만 건너뛰고 true를
     * 반환한다(원형은 raw 테이블에 남아 있으니 유실이 아니다 — 다만 백필이 제대로 됐는지를
     * 판단하는 운영 지표로 집계한다). 정상적으로 아이템이 0개인 페이지는 담지 않되 실패로
     * 세지는 않는다(형태 불일치와 구분).
     */
    private boolean parseInto(List<PageItems> out, Map<String, Object> payload, RawSource source,
                              Instant capturedAt) {
        if (payload == null) return false;
        try {
            var items = MediaItemExtractor.extract(payload, source);
            if (!items.isEmpty()) out.add(new PageItems(items, source, capturedAt));
            return false;
        } catch (RuntimeException e) {
            log.warn("캡션 백필 페이지 파싱 실패 — 건너뜀 (source={}): {}", source, e.toString());
            return true;
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

- [ ] **Step 4-2: 멀티청크 커서 전진·중지 경로 회귀 테스트를 추가하고 `Stats`에 `skippedParse`를 더한다**

두 가지 커버리지 공백을 메운다:

1. **청크 경계 테스트** — `PAGE_CHUNK + 5`(205)건을 `insert ... select ... from generate_series`로
   대량 시딩해 청크가 최소 2번 돈다. content도 같은 방식으로 205건 시딩해 전건 캡션 적재까지
   검증하고, 워터마크가 정확히 마지막으로 심은 행의 id까지 전진했는지 단정한다. **주의**:
   Postgres `format()` 함수의 `%s`는 이 SQL이 JdbcTemplate을 통해 그대로 전달되는 리터럴
   문자열이라(Java `String.format()`을 거치지 않는다) `%%s`로 이스케이프하면 안 된다 — psql로
   직접 확인한 결과 `%%s`는 모든 행에 리터럴 "%s" 문자열을 남겨 short_code가 전부 같아진다.
   단일 `%s`가 맞다.

   검증: `long last = chunk.get(chunk.size() - 1).getId();`를 `chunk.get(0).getId();`로 바꿔
   커서 전진을 일부러 깨뜨린 뒤 이 테스트만 단독 실행 → `expected: 205 but was: 21100`으로
   FAILED 확인(커서가 1씩만 전진해 겹치는 청크를 반복 재처리) → 원복.

2. **중지 재개 테스트** — 실제 `JobStopFlag`는 다른 스레드가 비동기로 호출하는 구조라, 단일
   스레드 테스트에서 "첫 청크 처리 후 중지"라는 타이밍을 결정적으로 재현하려면 스텁이
   필요하다. 첫 번째 확인(첫 청크 진입 전)은 통과시키고 그다음부터는 중지가 걸린 것처럼 구는
   `JobStopFlag` 서브클래스로 `CaptionBackfillJob`을 수동 조립한다(다른 협력자는 `@Autowired`로
   가져온 실제 Spring 빈을 그대로 쓴다). 이 인스턴스는 그 테스트 안에서만 쓰고 버리므로
   공유되는 실제 `JobStopFlag` 빈 상태는 건드리지 않는다. 검증 대상: 첫 청크(200건)만
   처리되고 워터마크도 거기까지만 전진하며, 이후 실제 `job` 빈으로 재실행하면 나머지
   5건을 이어서 처리한다.

   검증: `while (!stopFlag.isRequested(...))`를 `while (true)`로 바꿔 중지 체크 자체를
   무력화한 뒤 이 테스트만 단독 실행 → `expected: 200 but was: 205`로 FAILED 확인(중지 요청을
   무시하고 전건을 처리) → 원복.

3. **`Stats.skippedParse`** — `record Stats(int pages, int captions)`를
   `record Stats(int pages, int captions, int skippedParse)`로 바꾸고, `parseInto()`가
   `void` 대신 `boolean`(건너뛰었으면 true)을 반환하도록 고쳐 두 `backfillXxx()` 메서드가
   청크별로 집계하게 한다. `run()`의 완료 로그는 `skippedParse > 0`이면 `log.warn`, 아니면
   `log.info`로 승격한다(`BeautyJob`이 부분 실패를 warn으로 승격하는 관용구와 같은 결).
   `JobService`의 `case CAPTION_BACKFILL` 로그도 같은 분기를 넣는다.

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

기대: PASS (7개 테스트 — Step 2의 4개 + Step 4-1 회귀 테스트 1개 + Step 4-2 회귀 테스트 2개).
`JobService`가 exhaustive switch라 여기서 **컴파일 에러**가 날 수 있다 — 그러면 Task 6 Step 1을
먼저 적용한 뒤 이 단계를 다시 실행한다.

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
                title="저장된 raw 원형에서 캡션 소급 적재 — jsonb 약 14GB 1회 읽기, 크롤 잡과 겹치지 않는 시각에 실행 (일회성 · 인스타 호출 없음 · 워터마크로 재개)">실행</button>
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
- Create: `crawler/src/main/resources/db/migration/V24__drop_raw_post_detail.sql`
- Modify: `crawler/src/test/java/com/celfit/crawler/SchemaTest.java:23`
- Modify: `crawler/src/main/resources/db/migration/V23__content_caption.sql` (CASCADE 주석 stale 해소)

> **번호 노트**: 이 브랜치는 도중에 `V22__beauty_judgment_evidence.sql`을 열려던 open PR #216에
> 선점당해 캡션 마이그레이션이 V22→V23으로 재번호됐다. 그래서 이 Task의 DROP은 계획 초안의
> V23이 아니라 **V24**다.

- [x] **Step 1: 운영에서 0행임을 다시 확인한다**

```bash
ssh hypenow 'docker exec deploy-postgres-raw-1 psql -U crawler -d crawler -At -c "select count(*) from raw_post_detail"'
```

기대: `0`. **0이 아니면 이 태스크를 중단하고 사용자에게 보고한다** — 데이터가 있는 테이블을 지우는
것은 이 계획의 범위가 아니다. (실행 결과: `0` — 진행.)

- [x] **Step 2: 마이그레이션을 작성한다**

`crawler/src/main/resources/db/migration/V24__drop_raw_post_detail.sql`:

```sql
-- allow-destructive: 구 파이프라인 상세 payload 테이블. 운영 0행이며 참조 코드가 이미 끊겼다.
--
-- 운영 0행이라 보정할 데이터가 없다(DROP COLUMN 짝 검사의 no-backfill 태그는 컬럼 제거에만
-- 적용되는 규칙이라 여기서는 쓰지 않는다).
--
-- 배경: 신 파이프라인(V15~)은 게시물 상세를 따로 수집하지 않는다 — 릴스는 raw_media_page,
-- 피드는 raw_profile 내장 타임라인이 원형이다. 07-22 열람 화면 제거로 접근 코드까지 삭제되어
-- 엔티티·리포지토리가 존재하지 않고, 남은 참조는 마이그레이션 이력과 문서성 주석뿐이었다.
-- 캡션 조회 시 이 테이블을 조인해 "캡션이 DB에 없다"는 오조사가 실제로 발생했다(2026-07-30) —
-- 빈 테이블이 살아 있는 것 자체가 오답의 원인이므로 정리한다.
--
-- 주의: check-migration-safety.sh의 파괴적 DDL 검사(DROP TABLE 포함)는 was·analytics만
-- 대상이다(was 롤링 배포 중 신구 코드 공존 근거가 그 두 DB에만 있다) — 이 DROP은 자동
-- 차단되지 않으므로 위 주석은 사람 리뷰어용이다. 단, 같은 스크립트의 버전 중복 검사는
-- crawler·monitoring까지 4개 디렉토리 전부를 본다(check-migration-safety.sh v3, 실패 모드가
-- 달라서다 — 어느 Flyway 인스턴스든 중복 버전이면 그 인스턴스 자체가 기동을 거부한다).
-- 이번 V22 번호 경합(open PR #216 선점)은 그 버전 중복 검사로도 기계적으로 잡혔을 사안이다.
DROP TABLE raw_post_detail;
```

(최초 초안엔 `-- no-backfill:` 태그가 있었으나, 코드 리뷰에서 그 태그가 `DROP COLUMN` 짝 검사
전용 컨벤션(가드 정규식이 `drop column`만 매칭)이라 `DROP TABLE`에는 적용되지 않는다는 지적을
받아 평문 문장으로 흡수했다 — 태그로 남기면 다음 사람에게 잘못된 컨벤션을 가르치게 된다.)

- [x] **Step 2b: V23의 CASCADE 주석을 고친다 (코드 리뷰 지적)**

V23이 "content를 참조하는 raw_* 4개 테이블"을 열거하며 `raw_post_detail`을 포함했는데, 같은 PR
안에서 V24가 그 테이블을 지운다 — 머지되는 순간 개수·목록이 틀린 진술이 된다(결론인 RESTRICT
일관성 자체는 유효). `crawler/src/main/resources/db/migration/V23__content_caption.sql`의 해당
문장을 3개 테이블(raw_discovery_post·raw_comment·raw_media_page, raw_post_detail은 V24에서
제거되었다고 괄호로 명시)로 고쳤다.

- [x] **Step 3: `SchemaTest`를 고친다**

`SchemaTest.java`의 `contains(...)` 목록에서 `"raw_post_detail",` 을 **삭제**하고, `doesNotContain`
단정을 사건별로 분리한다(코드 리뷰 지적 — V8 카테고리 개편분과 V24 제거분이 한 배열에 섞이면
빠르게 훑을 때 전부 "V8에서"로 오독된다):

```java
        // V8에서 인플루언서 중심으로 개편되며 카테고리 체계는 완전히 걷어냈다.
        assertThat(tables).doesNotContain("category", "category_keyword", "collection_rule", "account");
        // raw_post_detail은 구 파이프라인 상세 payload — 07-22 접근 코드 삭제 후 V24에서 제거했다.
        assertThat(tables).doesNotContain("raw_post_detail");
```

- [x] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
./gradlew :crawler:test --rerun
```

기대: 380개 전부 PASS. (실행 결과: 380 tests, 0 failures, 0 errors.)

- [x] **Step 5: 커밋**

원 커밋(`chore(crawler): 죽은 테이블 raw_post_detail 제거 (V24)`, sha `b8995625`)과, 코드 리뷰
지적 3건을 반영한 후속 커밋(`fix(crawler): V23 주석 stale 해소 + SchemaTest 단언 분리`)으로
나뉘어 있다 — amend 대신 별도 커밋 원칙을 따랐다.

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
| AA | 게시물 캡션 원문 보존 | 캡션 원문이 raw jsonb(`raw_media_page`·`raw_profile`)에 실재하는데도 `MediaItemExtractor`가 파싱하지 않아 사장돼 있었고(유실이 아니라 추출 부재), 도달 경로가 5~7단 jsonb 표현식뿐이라 "캡션이 DB에 없다"는 오조사가 실제로 발생(07-30, `raw_post_detail` 0행·`raw_discovery_post` DISCOVERY 전용을 조인). `content_caption` 신설(V23 — content_id PK·최신 1건·96MB, 압축 불필요: pglz 실측 이득 7%) + 추출기가 세 소스에서 캡션 추출(`unwrapMedia()`가 이미 정규화하므로 단일 헬퍼) + 라이브 배선 + `CAPTION_BACKFILL` 원샷 잡(저장된 원형 소급 적재·인스타 호출 0회·라이브와 동일 파서 재사용·app_setting 워터마크 재개). **어떤 analytics 뷰도 읽지 않는 현역 소스 `HIKER_V1_MEDIAS`**(9,691행·1GB·07-18~) 캡션 약 6,240건을 재크롤 0원으로 건짐(표본 40건 중 33건=82.5% 실측) — 단 **저장 측면만** 해소, 서빙 반영(뷰가 `content_caption`을 읽도록 전환)은 analytics 트랙 후속. `raw_post_detail`(0행·참조 코드 부재) DROP 동반(V24) — [specs/2026-07-30-caption-preservation-design.md](docs/superpowers/specs/2026-07-30-caption-preservation-design.md) | — | 🔨 (구현 완료 — PR·배포·백필 수동 실행 대기) |
```

> **실행 노트(Task 8 사후)**: 위 예시는 트랙 문자를 `AA`로 적었으나, 실제 문서화 시점(07-30)에
> 같은 날 다른 세션이 트랙 `AA`(발굴 표면 뷰티 비율 게이트)를 먼저 커밋해 선점한 상태였다 —
> 이 트랙은 실제로 **`AB`**로 배정됐다(ARCHITECTURE.md §5·§7 확인).

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

기대: `origin/develop`의 최대가 V21이고 내 브랜치가 V23·V24(V22는 open PR #216이 먼저 점유해
재번호됨 — Task 7 번호 노트 참고). **develop에 V22 이상이 새로 생겼으면 파일명을 재번호하고
커밋한다** (07-20 V18 경합 사고 재발 방지).

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
- `raw_post_detail` DROP (V24) — 0행·참조 코드 부재로 contract 조건 충족

## 범위 밖 (의도적)

**서빙 커버리지는 이 PR로 바뀌지 않는다.** analytics 뷰는 여전히 jsonb를 직접 읽고 `HIKER_V1_MEDIAS`를 무시하므로, 복원된 ~6,240건은 저장되어도 뷰·랭킹·LLM 판정에 반영되지 않는다. analytics 층 작업이라 제외했다.

후속: ① 뷰가 `content_caption`을 읽도록 전환(V1_MEDIAS 갭 자동 해소) ② `ProfileExtractor.recentCaptions()` 소스 집합 점검(뷰티 오판 연결) ③ `HIKER_V1_MEDIAS`가 왜 현역인지(crawler 트랙) ④ `backup.sh`에 파생 캐시 제외(PR #193 트랙 — `analytics`+`analytics_dev` 캐시 2.05GB가 매일 덤프에 실림)

## 주의

- **migration-guard의 파괴적 DDL 검사는 was·analytics만 대상이다** — crawler 마이그레이션은 이 검사가 자동 차단하지 않으니 V24(DROP)를 사람이 검토해주기 바란다. (버전 중복 검사는 별개로 crawler도 포함한다.)
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

## Task 10: 최종 종합 리뷰 반영 (2026-07-30)

Task 9 완료 후 PR #236에 대한 최종 종합 리뷰가 머지 차단급 결함 1건과 후속 3건을 찾아 전부
반영했다.

- [x] **수정 1 (Critical, 머지 차단) — 빈 문자열이 실제 캡션을 조용히 덮는 결함.**
  `captionOf()`가 "캡션이 없다"와 "이 payload 형태에서 캡션을 읽는 방법을 모른다"를 똑같이
  `""`로 만들었다. 같은 content가 여러 소스 페이지에 등장할 때(타임라인은 릴스도 담고
  V1_MEDIAS는 피드·릴스를 함께 담는다) 더 최신 페이지의 `""`가 실제 캡션을 덮었고, 라이브는
  `clock.instant()`(현재)를 쓰므로 항상 백필을 이겨 백필로 건진 캡션(~6,240건, 이 PR의
  헤드라인 가치)이 영구 소실될 수 있었다. 위 "빈 캡션 정책" 절이 이 결함이 있던 최초 계약을
  그대로 남기고 있었다 — 최종 형태는 그 절의 개정 내용대로 `captionOf()`가 미확인은 `null`,
  확인된 무캡션은 `""`를 반환하고 `ContentCaptionUpserter.upsert()`가 `null` 아이템을 배치에서
  제외한다. 회귀 확인: `captionOf()`를 옛 동작(못 찾으면 `""`)으로 되돌리자
  `MediaItemExtractorTest` 7건이 실패했고, upserter의 null 제외 가드를 제거하자
  `ContentCaptionUpserterIntegrationTest` 1건이 `caption NOT NULL` 제약 위반
  (`DataIntegrityViolationException`)으로 실패하는 것을 확인한 뒤 원복했다. 별도 커밋.

- [x] **수정 2 (후속) — `PAGE_CHUNK`를 200→50으로 낮춘다.** 운영 크롤러 힙이 `-Xmx1g`(컨테이너
  메모리 제한 없음)인데, 최대 소스 HIKER_V2_CLIPS 실측 평균이 약 196KB/행(36,759행/7,040MB)이라
  밀집 청크 최악이 200×196KB ≈ 38MB 원시 JSON — Jackson Map/String 그래프로 파싱되면 수 배
  팽창한 채 `chunk` 지역변수로 트랜잭션 끝까지 강참조된다. 원샷 잡이라 런타임이 무의미하므로
  50으로 낮췄다(media 루프 233회 → 약 930회). 위 "Task 5" 코드 블록·주석과
  `CaptionBackfillJobIntegrationTest`의 하드코딩된 200/205 언급도 함께 정합화했다(테스트 자체는
  `CaptionBackfillJob.PAGE_CHUNK` 상수를 참조해 값을 자동으로 따라간다).

- [x] **수정 3 (후속) — ARCHITECTURE.md §3 "캡션을 찾을 때" 정확도 수정.** `00_base.sql`을
  직접 대조한 결과 세 가지가 부정확했다: ① "피드는 raw_profile"이라는 배타적 라벨이
  틀렸다 — `00_base.sql:99-100`이 명시하듯 SELF_GQL 내장 타임라인은 릴스(`product_type='clips'`)도
  담아 릴스 스냅샷 폴백 소스로 쓰인다. ② jsonb 경로 예시에 `source=` 조건이 빠져 있었다 —
  `raw_media_page`·`raw_profile` 둘 다 여러 source가 섞여 있으므로 `source='HIKER_V2_CLIPS'`·
  `source='SELF_GQL'`을 명시했다. ③ `content_caption.source`가 "이 게시물의 정본 소스"가 아니라
  "마지막에 이긴 파싱 경로"라는 점을 추가했다 — 타임라인이 릴스도 담으므로 같은 REELS
  content가 시점에 따라 `SELF_GQL`·`HIKER_V2_CLIPS`로 갈릴 수 있어 `(content_type, source)`
  집계는 오해를 낳는다.

- [x] **수정 4 (정정) — migration-guard 스코프 서술 정정.** `.github/scripts/check-migration-safety.sh`를
  직접 읽어 확인한 결과, "crawler 마이그레이션은 CI migration-guard 검사 대상이 아니다"는
  **파괴적 DDL 검사에만** 참이었다(was·analytics만 대상 — 롤링 공존 근거가 그 두 DB에만 있다).
  **버전 중복 검사(v3)는 crawler·monitoring까지 4개 디렉토리 전부를 본다** — 어느 Flyway
  인스턴스든 중복 버전이면 그 인스턴스 자체가 기동을 거부하는 별개의 실패 모드라서다. 이번
  V22 번호 경합(Task 7의 "번호 노트" — open PR #216 선점)은 그 버전 중복 검사로도 기계적으로
  잡혔을 사안이었다. `V24__drop_raw_post_detail.sql`과 이 계획서 곳곳의 관련 주석을 정정했다.

- [x] **검증**: `./gradlew test --rerun` 전 모듈 PASS. 병합 후 기준치는 위 "테스트 명령" 절
  참고 — 이 리뷰로 crawler 테스트 수가 늘었다(캡션 3-상태 회귀 테스트 추가).

---

## Task 11: 운영 실측 반증 — V2_CLIPS의 명시적 `caption: null` (2026-07-30)

Task 10에서 도입한 3-상태 계약("행 부재=미확인, 빈 문자열=확인된 무캡션")이 세운 가정 하나를
운영 raw DB 실측으로 반증했다.

- [x] **실측**: 캡션 없는 게시물의 표현을 세 소스 전부 측정했다. `HIKER_V2_CLIPS`(표본 2,905건)는
  키 부재 0건, **명시적 `"caption": null` 24건(0.83%)**. `HIKER_V1_MEDIAS`(표본 2,393건)는
  `caption_text=""` 23건(0.96%), 명시적 null 0건. `SELF_GQL`(표본 3,542건)은 `edges=[]` 23건
  (0.65%), 명시적 null 0건. 세 소스 모두 **키 부재는 0건** — "미확인"은 이론상 계약이지 실측
  발생 사례가 아니었다.
- [x] **결함**: `captionOf()`가 `m.get("caption") instanceof Map`으로 판정했는데, `Map.get()`은
  "키 부재"와 "값이 JSON null"을 구분하지 못한다(Jackson이 둘 다 Java `null`로 매핑). V2_CLIPS의
  `"caption": null`(캡션 없음이 **명시된** 상태)이 "미확인"으로 오판돼 행이 생성되지 않았다 —
  실측 0.83%가 커버리지 집계에서 누락되고 그만큼 "행 부재=미확인" 계약이 거짓이 됐다.
- [x] **수정**: `captionOf()`의 V2_CLIPS 분기만 `containsKey`로 판정하도록 변경 — 키가 있으면
  값이 null이거나 캡션 객체가 아니어도 "확인된 무캡션"(`""`)으로 다룬다. `caption_text`
  (V1_MEDIAS)·`edge_media_to_caption`(SELF_GQL) 분기는 실측상 이미 올바르고(명시적 null
  0건) 손대지 않았다.
- [x] **회귀 확인**: 수정 전 코드로 `v2_clips는_caption이_명시적_null이면_확인된_무캡션이다`
  테스트가 `expected: "" but was: null`로 실패하는 것을 확인한 뒤 수정을 재적용했다.
- [x] **검증**: `./gradlew test --rerun` 전 모듈 PASS.

---

## 운영 반영 절차 (PR 머지 후 — 실행은 사용자 승인 필요)

이 계획은 **코드까지만** 다룬다. 아래는 머지 후 별도 승인을 받아 수행한다.

1. develop 머지 → CI만 돎(배포 없음)
2. develop → staging 머지 = test 스테이징 배포(dev-api.hypenow.io)
3. staging → main 머지 = 운영 배포. Flyway V23·V24가 이때 적용된다
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
