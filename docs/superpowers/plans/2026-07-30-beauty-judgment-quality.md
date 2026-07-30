# 계정 뷰티 판정 품질 — 실측 캡션 기반 사후 재판정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 구현 미착수
> 설계 스펙: `docs/superpowers/specs/2026-07-30-beauty-judgment-quality-design.md`

**Goal:** 캡션 0건으로 판정된 계정을, 나중에 쌓인 실측 게시물 캡션으로 재판정해서 뷰티 오판(서빙 886개 중 약 85%)을 자동 교정한다.

**Architecture:** `BeautyJob`의 판정 재료를 최신 `raw_profile`에서만 뽑던 것을, 캡션이 비면 `raw_media_page`(릴스 페이지)의 실측 캡션으로 폴백하도록 넓힌다. 판정 근거(캡션 건수·LLM이 밝힌 주근거)를 `influencer`에 기록하고, 이 기록을 조건으로 하는 두 번째 재판정 경로를 추가한다 — 이 경로만 `beauty=true`도 대상으로 삼아 false positive를 교정한다. 프롬프트는 자기신고 `category`의 우선순위를 낮추고 근거를 결론보다 먼저 쓰게 바꾼다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, PostgreSQL 17, JUnit5 + AssertJ + Mockito, Testcontainers 2.x, Jackson 3(`tools.jackson.*`)

---

## 사전 조건

- Docker가 떠 있어야 한다(Testcontainers 통합 테스트). 이 저장소 README는 Docker Desktop을 전제하지만, 이 개발 환경은 colima가 정본이다 — `docker ps`가 응답하면 준비된 것이다.
- 작업 브랜치는 현재 워크트리 브랜치를 그대로 쓴다. `develop`·`staging`·`main`에 직접 커밋하지 않는다.
- 전체 테스트: `./gradlew test` / 단일 클래스: `./gradlew :crawler:test --tests "<FQCN>"`

## 파일 구조

| 파일 | 책임 | 작업 |
|---|---|---|
| `crawler/src/main/resources/db/migration/V22__beauty_judgment_evidence.sql` | 판정 근거 컬럼 2개 추가 + 기존 판정분 백필 | 생성 |
| `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java` | 근거 필드 2개 + `classify()` 시그니처 확장 | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java` | 열거 페이지 payload → 캡션 추출 | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawMediaPageRepository.java` | 계정별 최신 릴스 페이지 조회 | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java` | `Verdict`에 `basis` 추가 | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java` | 프롬프트·파서(3어댑터 단일 원천) | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java` | 응답 스키마 5분류 + `basis` | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` | 캡션 기반 재판정 선정 쿼리 | 수정 |
| `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java` | 캡션 폴백·근거 기록·선정 통합·응답 검증 로그 | 수정 |

---

## Task 0: 백필 규모 사전 측정 (배포 전 1회)

**이 태스크는 코드 변경이 없다.** Task 1의 마이그레이션에 백필 UPDATE가 들어가는데, 그 대상 건수가 곧 1회성 LLM 재판정 비용이다. 로컬에 실데이터가 없어 사전 측정은 운영 DB에서만 가능하다.

- [ ] **Step 1: 사용자에게 운영 카운트 실행 승인을 받는다**

아래 SELECT를 운영 DB에서 돌려도 되는지 **먼저 물어본다.** 읽기 전용이지만 사용자 자원(운영 DB 접속)을 쓰는 실행이므로 승인 없이 돌리지 않는다.

```sql
SELECT count(*) AS backfill_target
FROM influencer i
WHERE i.beauty_class IS NOT NULL
  AND i.beauty_source = 'CLAUDE'
  AND EXISTS (
    SELECT 1 FROM raw_profile rp
    WHERE rp.influencer_id = i.id
      AND rp.source IN ('HIKER_MOBILE', 'DATALIKERS')
      AND rp.captured_at = (SELECT max(rp2.captured_at) FROM raw_profile rp2
                            WHERE rp2.influencer_id = i.id)
  );
```

- [ ] **Step 2: 결과를 사용자에게 보고하고 진행 여부를 확인한다**

건수가 예상(수천 단위)을 크게 넘으면 `app_setting`의 `beauty.batch-limit`으로 회당 상한이 걸리므로 폭주하지는 않지만, 총 비용은 건수에 비례한다. 사용자가 진행을 승인하면 Task 1로 간다.

> **승인 없이 Task 1의 마이그레이션을 운영에 적용하지 않는다.** 로컬·CI에서는 백필 대상이 0건이므로 Task 1~7 구현·테스트는 이 승인과 무관하게 진행해도 된다.

---

## Task 1: 판정 근거 컬럼 (마이그레이션 + 엔티티)

**Files:**
- Create: `crawler/src/main/resources/db/migration/V22__beauty_judgment_evidence.sql`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java`

- [ ] **Step 1: 마이그레이션 번호가 아직 비어 있는지 확인**

```bash
ls crawler/src/main/resources/db/migration/ | sort -V | tail -3
```

기대: `V20__...`, `V21__beauty_foreign_influencer.sql`까지만 존재. **V22가 이미 있으면 다음 빈 번호로 올린다** — 2026-07-21에 두 세션이 같은 V18을 잡아 경합한 전력이 있다. 머지 직전에도 한 번 더 확인한다.

- [ ] **Step 2: 실패하는 통합 테스트를 쓴다**

`BeautySelectionIntegrationTest.java`에 아래 테스트 메서드를 추가한다. 기존 클래스의 `PREFIX` 상수와 `notBeauty(...)` 헬퍼를 그대로 쓴다.

```java
    @Test
    void 판정_근거_필드가_저장되고_읽힌다() {
        Influencer inf = notBeauty(PREFIX + "evidence", Instant.parse("2026-07-01T00:00:00Z"));
        inf.setBeautyCaptionCount((short) 0);
        inf.setBeautyBasis("CATEGORY_ONLY");
        influencers.save(inf);

        Influencer loaded = influencers.findByUsername(PREFIX + "evidence").orElseThrow();
        assertThat(loaded.getBeautyCaptionCount()).isEqualTo((short) 0);
        assertThat(loaded.getBeautyBasis()).isEqualTo("CATEGORY_ONLY");
    }
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"
```

기대: 컴파일 실패 — `cannot find symbol: method setBeautyCaptionCount(short)`

- [ ] **Step 4: 마이그레이션을 작성한다**

`crawler/src/main/resources/db/migration/V22__beauty_judgment_evidence.sql`:

```sql
-- 판정 근거 기록 — 캡션 0건으로 판정된 계정을 나중에 쌓인 실측 캡션으로 재판정하기 위한 대상 표시.
-- (2026-07-30 실측: 서빙 뷰티 인플루언서 7,095 중 게시물 뷰티 비율 0%인 886개를 스팟체크했더니 85%가 오판)
alter table influencer add column beauty_caption_count smallint;
alter table influencer add column beauty_basis text;

-- basis는 LLM이 밝힌 판정 주근거. CATEGORY_ONLY는 인스타그램 자기신고 category만 보고 판단한 저확신 판정.
alter table influencer add constraint influencer_beauty_basis_check
    check (beauty_basis in ('CAPTION', 'BIO', 'CATEGORY_ONLY'));

-- 기록 이전 판정분 백필 — 프로필 응답에 게시물이 아예 없는 소스(HIKER_MOBILE·DATALIKERS)로 판정된
-- 계정은 캡션이 구조적으로 0건이었다. 0으로 표시해야 재판정 선정(findCaptionRejudgeTargets)에 걸린다.
-- NULL로 두면 "기록 이전 판정분"이라 재판정 대상에서 빠진다 — 오판 886개가 여기 포함된다.
update influencer i set beauty_caption_count = 0
where i.beauty_class is not null
  and i.beauty_source = 'CLAUDE'
  and exists (
    select 1 from raw_profile rp
    where rp.influencer_id = i.id
      and rp.source in ('HIKER_MOBILE', 'DATALIKERS')
      and rp.captured_at = (select max(rp2.captured_at) from raw_profile rp2
                            where rp2.influencer_id = i.id)
  );
```

`add column`(nullable)만 있고 `DROP`/`RENAME`/`SET NOT NULL`이 없으므로 expand 단계다 — CI `migration-guard`를 그대로 통과하며 `-- allow-destructive:` 주석이 필요 없다.

- [ ] **Step 5: 엔티티에 필드를 추가한다**

`Influencer.java`의 `beautyJudgedAt` 필드 선언 **바로 아래**에 추가한다(`@Getter @Setter`가 클래스 레벨에 있으므로 접근자는 자동 생성된다):

```java
    /**
     * 판정에 실제로 넣은 캡션 건수. 0이면 실측 근거 없이 판정된 것 — 게시물 캡션이 쌓이면
     * 재판정 대상이 된다(findCaptionRejudgeTargets). NULL은 이 기록 도입 이전 판정분.
     */
    @Column(name = "beauty_caption_count")
    private Short beautyCaptionCount;

    /**
     * LLM이 밝힌 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY 중 하나. CATEGORY_ONLY는 인스타그램
     * 자기신고 category만 보고 판단한 저확신 판정(계정주가 자율 선택하는 미검증 필드).
     */
    @Column(name = "beauty_basis")
    private String beautyBasis;
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"
```

기대: PASS (Flyway가 V22를 적용하지 못하면 컨텍스트 기동 자체가 실패하므로, 통과 = 마이그레이션 유효)

- [ ] **Step 7: 커밋**

```bash
git add crawler/src/main/resources/db/migration/V22__beauty_judgment_evidence.sql crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java
git commit -m "feat(crawler): 뷰티 판정 근거 기록 컬럼 — 캡션 건수·주근거

캡션 0건으로 판정된 계정을 실측 캡션으로 재판정하기 위한 대상 표시.
기존 판정분은 프로필 소스가 HIKER_MOBILE·DATALIKERS면 0으로 백필한다."
```

---

## Task 2: 열거 페이지 캡션 추출기

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/MediaItemExtractorTest.java`

**배경:** 캡션 경로는 `payload -> response -> items[] -> media -> caption -> text`다. 세 근거가 일치한다 — `analytics/views/00_base.sql`의 `v_base_reel_item`이 같은 경로로 캡션을 뽑고, `MediaItemExtractor.items()`/`unwrapMedia()`가 같은 구조를 전제하며, HikerAPI `/v2/user/clips` 실측 픽스처(`monitoring/src/test/resources/hiker/clips.json`)에 실제 텍스트가 있다.

**`HIKER_V1_MEDIAS`(피드 보충)는 제외한다.** payload에 캡션이 있는지 검증한 뷰도 픽스처도 이 저장소에 없다. 근거 없이 경로를 추측해 넣지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MediaItemExtractorTest.java`에 추가한다:

```java
    @Test
    void 릴스_페이지에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of("response", Map.of("items", List.of(
                Map.of("media", Map.of("code", "AAA", "taken_at", 1_700_000_000L,
                        "caption", Map.of("text", "오늘의 스킨케어 루틴"))),
                Map.of("media", Map.of("code", "BBB", "taken_at", 1_700_000_100L,
                        "caption", Map.of("text", "신상 쿠션 발색샷"))))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V2_CLIPS))
                .containsExactly("오늘의 스킨케어 루틴", "신상 쿠션 발색샷");
    }

    @Test
    void 캡션이_없거나_빈_아이템은_건너뛴다() {
        Map<String, Object> payload = Map.of("response", Map.of("items", List.of(
                Map.of("media", Map.of("code", "AAA", "caption", Map.of("text", "  "))),
                Map.of("media", Map.of("code", "BBB")),
                Map.of("media", Map.of("code", "CCC", "caption", Map.of("text", "유효 캡션"))))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V2_CLIPS))
                .containsExactly("유효 캡션");
    }

    @Test
    void 검증되지_않은_소스는_빈_리스트다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "AAA", "caption", Map.of("text", "피드 캡션"))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V1_MEDIAS)).isEmpty();
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"
```

기대: 컴파일 실패 — `cannot find symbol: method captions(...)`

- [ ] **Step 3: 추출기를 구현한다**

`MediaItemExtractor.java`의 `extract()` 메서드 **바로 아래**에 추가한다:

```java
    /**
     * 저장된 열거 페이지에서 게시물 캡션 추출 — 뷰티 재판정의 실측 근거다.
     * HIKER_V2_CLIPS만 지원한다: response.items[].media.caption.text
     * (analytics v_base_reel_item이 캡션을 뽑는 경로와 동일).
     * HIKER_V1_MEDIAS는 payload에 캡션이 있는지 검증한 뷰도 픽스처도 없어 제외한다 —
     * 근거 없는 경로로 빈 문자열을 판정 재료에 섞느니 아예 넣지 않는다.
     */
    public static List<String> captions(Map<String, Object> payload, RawSource source) {
        if (source != RawSource.HIKER_V2_CLIPS) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : items(payload, source)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = unwrapMedia(raw);
            if (m.get("caption") instanceof Map<?, ?> cap
                    && cap.get("text") instanceof String t && !t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"
```

기대: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/MediaItemExtractorTest.java
git commit -m "feat(crawler): 릴스 페이지 payload에서 캡션 추출

경로는 analytics v_base_reel_item과 동일(response.items[].media.caption.text).
캡션 유무가 검증되지 않은 HIKER_V1_MEDIAS는 제외한다."
```

---

## Task 3: 판정 재료 캡션 폴백 + 근거 건수 기록

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawMediaPageRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BeautyJobTest.java`에 추가한다. 기존 `qualified(...)`/`legacyProfile(...)` 헬퍼를 쓰고, 아래 헬퍼를 픽스처 영역에 함께 추가한다:

```java
    static RawMediaPage clipsPage(Long influencerId, String... captions) {
        List<Object> items = new ArrayList<>();
        for (String c : captions) {
            items.add(Map.of("media", Map.of("caption", Map.of("text", c))));
        }
        Map<String, Object> payload = Map.of("response", Map.of("items", items));
        return new RawMediaPage(influencerId, null, RawSource.HIKER_V2_CLIPS, payload, Instant.EPOCH);
    }
```

> `RawMediaPage` 생성자 시그니처는 `RawProfile`과 같은 형태(`influencerId, crawlRunId, source, payload, capturedAt`)다. 실제 시그니처가 다르면 `crawler/src/main/java/com/celfit/crawler/crawling/domain/RawMediaPage.java`를 열어 맞춘다.

```java
    @Test
    void 프로필에_캡션이_없으면_릴스_페이지_캡션을_쓴다() {
        Influencer inf = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(1L, RawSource.HIKER_V2_CLIPS))
                .thenReturn(Optional.of(clipsPage(1L, "스킨케어 루틴", "쿠션 발색")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "뷰티 캡션", "CAPTION")));

        job.run(TriggerType.MANUAL, false);

        ArgumentCaptor<List<BeautyJudge.ProfileCard>> cards = ArgumentCaptor.forClass(List.class);
        verify(judge).judge(cards.capture());
        assertThat(cards.getValue().getFirst().captions())
                .containsExactly("스킨케어 루틴", "쿠션 발색");
        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 2);
    }

    @Test
    void 프로필_캡션이_있으면_릴스_페이지를_조회하지_않는다() {
        Influencer inf = qualified(1L, "acc1");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("biography", "bio");
        payload.put("latestPosts", List.of(Map.of("caption", "프로필 캡션")));
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "CAPTION")));

        job.run(TriggerType.MANUAL, false);

        verifyNoInteractions(rawMediaPages);
        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 1);
    }

    @Test
    void 캡션을_어디서도_못_구하면_0으로_기록한다() {
        Influencer inf = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(1L, RawSource.HIKER_V2_CLIPS))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "CATEGORY_ONLY")));

        job.run(TriggerType.MANUAL, false);

        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 0);
    }
```

테스트 클래스 상단에 mock 필드를 추가하고 `BeautyJob` 생성자 인자에 넣는다:

```java
    private final RawMediaPageRepository rawMediaPages = mock(RawMediaPageRepository.class);
```

그리고 `BeautyJob`을 생성하는 곳(필드 초기화 또는 `@BeforeEach`)의 인자 목록에 `rawProfiles` 다음 자리로 `rawMediaPages`를 끼워 넣는다:

```bash
grep -n "new BeautyJob(" crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java
```

> 이 시점에는 `Verdict`가 아직 3-arg다. 위 테스트는 4-arg(`basis`)로 써 두고 Task 4에서 `Verdict`를 확장할 때 컴파일이 맞아떨어지게 한다 — **Task 3과 Task 4는 한 커밋 흐름으로 이어서 진행한다.** Task 3만 단독으로 초록을 보고 싶으면 위 테스트의 4번째 인자를 잠시 지웠다가 Task 4에서 되돌린다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"
```

기대: 컴파일 실패 — `RawMediaPageRepository`에 `findTopByInfluencerIdAndSourceOrderByCapturedAtDesc` 없음, `BeautyJob` 생성자 인자 수 불일치

- [ ] **Step 3: 레포지토리에 조회 메서드를 추가한다**

`RawMediaPageRepository.java`:

```java
    /** 뷰티 판정 재료: 계정의 최신 릴스 페이지 — 프로필에 캡션이 없는 소스의 폴백 근거. */
    java.util.Optional<com.celfit.crawler.crawling.domain.RawMediaPage>
            findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(
                    Long influencerId, com.celfit.crawler.crawling.domain.RawSource source);
```

- [ ] **Step 4: `BeautyJob`에 폴백과 기록을 구현한다**

(a) 필드·생성자에 `RawMediaPageRepository`를 추가한다. `rawProfiles` 필드 아래에 `private final RawMediaPageRepository rawMediaPages;`를 넣고, 생성자 파라미터를 `RawProfileRepository rawProfiles` 다음에 끼워 넣은 뒤 `this.rawMediaPages = rawMediaPages;`를 대입한다.

(b) `run()`의 카드 구성 루프를 아래로 교체한다(캡션 건수를 username별로 들고 다녀야 `applyVerdicts`에서 기록할 수 있다):

```java
        // 판정 재료 준비 — raw_profile이 아직 없으면 판정 불가(qualify가 언젠가 채우면 재시도)
        List<BeautyJudge.ProfileCard> cards = new ArrayList<>();
        Map<String, Influencer> byUsername = new HashMap<>();
        Map<String, Integer> captionCounts = new HashMap<>();
        int skipped = 0;
        for (Influencer inf : targets) {
            if (byUsername.containsKey(inf.getUsername())) continue;  // 두 선정 쿼리 중복 방어
            var rp = rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(inf.getId());
            if (rp.isEmpty()) { skipped++; continue; }
            RawProfile p = rp.get();
            List<String> captions = trimCaptions(
                    ProfileExtractor.recentCaptions(p.getPayload(), p.getSource()));
            if (captions.isEmpty()) captions = trimCaptions(mediaCaptions(inf.getId()));
            cards.add(new BeautyJudge.ProfileCard(inf.getUsername(),
                    ProfileExtractor.fullName(p.getPayload(), p.getSource()),
                    ProfileExtractor.category(p.getPayload(), p.getSource()),
                    ProfileExtractor.biography(p.getPayload(), p.getSource()),
                    captions));
            byUsername.put(inf.getUsername(), inf);
            captionCounts.put(inf.getUsername(), captions.size());
        }
```

(c) 캡션 폴백 헬퍼를 `trimCaptions` 위에 추가한다:

```java
    /**
     * 프로필 응답에 게시물이 없는 소스(HIKER_MOBILE·DATALIKERS)의 폴백 — 이미 수집된 릴스 페이지의
     * 실측 캡션을 판정 재료로 쓴다. 추가 크롤 없음(raw_media_page는 REELS 잡이 이미 채운 것).
     */
    private List<String> mediaCaptions(Long influencerId) {
        return rawMediaPages
                .findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(influencerId, RawSource.HIKER_V2_CLIPS)
                .map(page -> MediaItemExtractor.captions(page.getPayload(), page.getSource()))
                .orElseGet(List::of);
    }
```

`import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;`와 `import com.celfit.crawler.crawling.domain.RawSource;`를 추가한다.

(d) `applyVerdicts`가 캡션 건수를 받아 기록하게 한다. 시그니처와 호출부, 저장 부분을 바꾼다:

```java
            ChunkResult r = txTemplate.execute(
                    status -> applyVerdicts(verdicts, byUsername, captionCounts, done, cards.size()));
```

```java
    private ChunkResult applyVerdicts(List<BeautyJudge.Verdict> verdicts, Map<String, Influencer> byUsername,
                                      Map<String, Integer> captionCounts, int done, int totalCards) {
```

`inf.setBeautyJudgedAt(clock.instant());` 바로 아래에 추가한다:

```java
            // 판정에 실제로 쓴 캡션 건수 — 0이면 나중에 캡션이 쌓였을 때 재판정 대상이 된다
            inf.setBeautyCaptionCount(captionCounts.getOrDefault(v.username(), 0).shortValue());
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"
```

기대: PASS (Task 4를 아직 안 했다면 `Verdict` 4-arg 때문에 컴파일 실패한다 — Task 4를 먼저 끝내고 돌아온다)

- [ ] **Step 6: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawMediaPageRepository.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java
git commit -m "feat(crawler): 뷰티 판정 캡션 폴백 — 프로필에 없으면 릴스 페이지에서

HIKER_MOBILE·DATALIKERS는 프로필 응답에 게시물이 없어 캡션이 항상 0건이었다.
이미 수집된 raw_media_page의 실측 캡션을 쓴다(추가 크롤 없음).
판정에 쓴 캡션 건수를 beauty_caption_count에 기록한다."
```

---

## Task 4: 판정 근거(basis) — 계약·프롬프트·파서·응답 스키마

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudgeTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudgeTest.java`

- [ ] **Step 1: 실패하는 파서 테스트를 쓴다**

`ClaudeCliBeautyJudgeTest.java`에 추가한다:

```java
    @Test
    void basis를_파싱한다() {
        String output = """
                [{"username":"a","reason":"뷰티 캡션 다수","basis":"CAPTION","class":"INFLUENCER"}]""";

        var verdicts = ClaudeCliBeautyJudge.parse(om, output);

        assertThat(verdicts).singleElement().satisfies(v -> {
            assertThat(v.username()).isEqualTo("a");
            assertThat(v.basis()).isEqualTo("CAPTION");
            assertThat(v.beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        });
    }

    @Test
    void 알_수_없는_basis는_null로_두고_판정은_살린다() {
        String output = """
                [{"username":"a","reason":"이유","basis":"VIBES","class":"INFLUENCER"},
                 {"username":"b","reason":"이유","class":"NOT_BEAUTY"}]""";

        var verdicts = ClaudeCliBeautyJudge.parse(om, output);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).basis()).isNull();
        assertThat(verdicts.get(1).basis()).isNull();
    }

    @Test
    void 프롬프트가_category를_미검증_필드로_명시한다() {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("a", "이름", "Beauty, cosmetic & personal care", "bio", List.of())));

        assertThat(prompt).contains("미검증 자기신고 필드");
        assertThat(prompt).contains("CATEGORY_ONLY");
    }
```

`GeminiBeautyJudgeTest.java`에 추가한다:

```java
    @Test
    void 응답_스키마가_프롬프트의_5분류와_basis를_모두_담는다() {
        assertThat(GeminiBeautyJudge.RESPONSE_SCHEMA)
                .contains("FOREIGN_INFLUENCER")
                .contains("CATEGORY_ONLY");
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudgeTest" --tests "com.celfit.crawler.crawling.adapter.out.gemini.GeminiBeautyJudgeTest"
```

기대: 컴파일 실패 — `cannot find symbol: method basis()`

- [ ] **Step 3: `Verdict`에 `basis`를 추가한다**

`BeautyJudge.java`의 `Verdict` record를 교체한다:

```java
    /**
     * 5분류 판정 결과 — 파생 boolean(beauty/company)은 BeautyClass 규칙을 위임한다.
     * BEAUTY_SERVICE(시술·서비스)는 beauty=false — 리스트업 세그먼트로만 남고 수집·유사발굴 제외.
     * basis는 판정의 주근거(CAPTION·BIO·CATEGORY_ONLY) — 모델이 밝히지 않거나 알 수 없는 값이면 null.
     */
    record Verdict(String username, com.celfit.crawler.crawling.domain.BeautyClass beautyClass, String reason,
                   String basis) {
```

- [ ] **Step 4: `classify()`에 basis를 전달한다**

`Influencer.java`:

```java
    /** 판정 결과 일괄 적용 — 파생 boolean을 beauty_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classify(BeautyClass cls, String source, String reason, String basis) {
        this.beautyClass = cls;
        this.beauty = cls.beauty();
        this.beautyCompany = cls.company();
        this.beautySource = source;
        this.beautyReason = reason;
        this.beautyBasis = basis;
    }
```

호출부를 전부 찾아 고친다:

```bash
grep -rn "\.classify(" crawler/src
```

- `BeautyJob.applyVerdicts`: `inf.classify(v.beautyClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.reason(), v.basis());`
- 수동 판정(MANUAL) 경로가 나오면 마지막 인자에 `null`을 넣는다 — 사람이 판정한 것이라 LLM 근거가 없다.

- [ ] **Step 5: 프롬프트를 고친다**

`ClaudeCliBeautyJudge.buildPrompt()`에서 세 곳을 바꾼다.

(a) "경계 규칙:" 블록의 마지막 줄(`- 캡션이 빈 배열(미수집)이고 bio만으로 모호하면...`) **아래**에 한 줄을 추가한다:

```java
                - category는 계정주가 자율 선택한 미검증 자기신고 필드다 — bio·캡션의 실제 내용과 \
                상충하면 실제 내용을 우선하라.
```

(b) `captions는 최근 게시물 캡션 일부다...` 문단 **아래**에 basis 설명을 추가한다:

```java
                basis는 판정의 주근거다 — 캡션의 콘텐츠 주제를 근거로 했으면 CAPTION, bio·이름을 \
                근거로 했으면 BIO, 캡션도 bio도 근거가 되지 못해 category만 보고 판단했으면 CATEGORY_ONLY.
                reason(근거)을 먼저 쓰고, 그 근거와 일관된 class를 마지막에 쓰라.
```

(c) 출력 형식 줄을 교체한다 — `reason`·`basis`가 `class`보다 **앞에** 온다:

```java
                출력은 JSON 배열만: [{"username":"...","reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY"}]
```

- [ ] **Step 6: 파서를 고친다**

`ClaudeCliBeautyJudge.parse()`의 for 루프 본문을 교체한다. 5분류 switch가 같은 줄을 다섯 번 반복하던 것을 매핑 → 생성 2단계로 줄인다:

```java
        for (JsonNode n : root) {
            String username = n.path("username").asString(null);
            String cls = n.path("class").asString(null);
            if (username == null || username.isBlank() || cls == null) continue;
            // 5분류 외 값(모델 일탈)은 건너뛴다 — 해당 계정은 미판정 유지, 다음 실행 재시도
            BeautyClass parsed = switch (cls) {
                case "INFLUENCER" -> BeautyClass.INFLUENCER;
                case "FOREIGN_INFLUENCER" -> BeautyClass.FOREIGN_INFLUENCER;
                case "COMPANY" -> BeautyClass.COMPANY;
                case "BEAUTY_SERVICE" -> BeautyClass.BEAUTY_SERVICE;
                case "NOT_BEAUTY" -> BeautyClass.NOT_BEAUTY;
                default -> null;
            };
            if (parsed == null) continue;
            out.add(new Verdict(username, parsed, n.path("reason").asString(null),
                    normalizeBasis(n.path("basis").asString(null))));
        }
```

`stripFences()` 위에 헬퍼를 추가한다:

```java
    /**
     * class와 달리 basis는 알 수 없는 값이어도 판정을 버릴 이유가 없다 — 근거 표시만 비우고 판정은 살린다.
     */
    private static String normalizeBasis(String basis) {
        if (basis == null) return null;
        return switch (basis) {
            case "CAPTION", "BIO", "CATEGORY_ONLY" -> basis;
            default -> null;
        };
    }
```

- [ ] **Step 7: Gemini 응답 스키마를 고친다**

`GeminiBeautyJudge.RESPONSE_SCHEMA`를 교체한다. `FOREIGN_INFLUENCER` 누락(V21에서 5분류가 됐는데 스키마는 4분류로 남아 있던 버그)을 함께 고친다:

```java
    static final String RESPONSE_SCHEMA = """
            {"type":"array","items":{"type":"object","properties":{
              "username":{"type":"string"},
              "reason":{"type":"string"},
              "basis":{"type":"string","enum":["CAPTION","BIO","CATEGORY_ONLY"]},
              "class":{"type":"string","enum":["INFLUENCER","FOREIGN_INFLUENCER","COMPANY","BEAUTY_SERVICE","NOT_BEAUTY"]}},
             "required":["username","reason","basis","class"]}}""";
```

- [ ] **Step 8: 나머지 컴파일 오류를 정리한다**

```bash
grep -rn "new Verdict(" crawler/src
```

나오는 모든 생성자 호출에 4번째 인자를 넣는다. 테스트 픽스처는 판정 성격에 맞는 값을 쓴다 — 캡션 근거면 `"CAPTION"`, bio 근거면 `"BIO"`, 근거를 특정하지 않는 테스트면 `null`.

- [ ] **Step 9: 테스트 통과 확인**

```bash
./gradlew :crawler:test
```

기대: 전체 PASS. **`trimSurrogateSafe` 관련 테스트가 그대로 통과하는지 반드시 확인한다** — 2026-07-21에 서로게이트 쌍 절단으로 배치 10개 중 9개가 400 실패한 운영 장애가 있었다.

- [ ] **Step 10: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java crawler/src/test
git commit -m "feat(crawler): 뷰티 판정에 근거(basis) 도입 — category 단독 판정 가시화

인스타그램 category_name은 계정주 자기신고 미검증 필드다. 금지하지 않고
bio·캡션과 상충할 때 실제 내용을 우선하게 하고, category만 본 판정은
basis=CATEGORY_ONLY로 스스로 드러내게 한다.
출력 필드 순서를 reason→class로 뒤집어 근거와 결론의 모순을 줄인다.
Gemini 응답 스키마에 빠져 있던 FOREIGN_INFLUENCER를 함께 채운다."
```

---

## Task 5: 캡션 기반 재판정 선정

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java`

- [ ] **Step 1: 실패하는 통합 테스트를 쓴다**

`BeautySelectionIntegrationTest.java`에 추가한다. 기존 `runId()`/`profile(...)` 헬퍼를 쓰고, 릴스 페이지 헬퍼를 함께 추가한다:

```java
    private Long mediaPage(Influencer inf, Instant capturedAt, Long runId, int itemCount) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(Map.of("media", Map.of("caption", Map.of("text", "캡션 " + i))));
        }
        return jdbcTemplate.queryForObject("""
                insert into raw_media_page (influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V2_CLIPS', ?::jsonb, ?) returning id""",
                Long.class, inf.getId(), runId,
                new ObjectMapper().writeValueAsString(Map.of("response", Map.of("items", items))),
                Timestamp.from(capturedAt));
    }

    private Influencer judged(String username, boolean beauty, Instant judgedAt, Short captionCount) {
        Influencer inf = new Influencer(username);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.classify(beauty ? BeautyClass.INFLUENCER : BeautyClass.NOT_BEAUTY,
                Influencer.BEAUTY_SOURCE_CLAUDE, "이유", "CATEGORY_ONLY");
        inf.setBeautyJudgedAt(judgedAt);
        inf.setBeautyCaptionCount(captionCount);
        return influencers.save(inf);
    }
```

```java
    private static final Instant JUDGED = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void 캡션_재판정은_뷰티_판정분도_대상으로_삼는다() {
        Influencer inf = judged(PREFIX + "fp", true, JUDGED, (short) 0);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).contains(PREFIX + "fp");
    }

    @Test
    void 캡션_재판정은_아이템이_부족한_페이지를_무시한다() {
        Influencer inf = judged(PREFIX + "thin", true, JUDGED, (short) 0);
        mediaPage(inf, AFTER, runId(), 2);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "thin");
    }

    @Test
    void 캡션_재판정은_판정_이전에_쌓인_페이지를_무시한다() {
        Influencer inf = judged(PREFIX + "stale", true, JUDGED, (short) 0);
        mediaPage(inf, BEFORE, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "stale");
    }

    @Test
    void 캡션_재판정은_이미_캡션으로_판정된_계정을_제외한다() {
        Influencer inf = judged(PREFIX + "done", true, JUDGED, (short) 4);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "done");
    }

    @Test
    void 캡션_재판정은_기록_이전_판정분을_제외한다() {
        Influencer inf = judged(PREFIX + "legacy", true, JUDGED, null);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "legacy");
    }

    @Test
    void 캡션_재판정은_수동_판정을_제외한다() {
        Influencer inf = judged(PREFIX + "manual", true, JUDGED, (short) 0);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_MANUAL);
        influencers.save(inf);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "manual");
    }
```

`@AfterEach` 정리에 `raw_media_page` 삭제를 **`raw_profile` 삭제와 같은 위치에**(influencer 삭제보다 먼저) 추가한다 — FK 때문에 순서가 어긋나면 정리가 실패한다:

```java
        jdbcTemplate.update("delete from raw_media_page where influencer_id in "
                + "(select id from influencer where username like ?)", PREFIX + "%");
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"
```

기대: 컴파일 실패 — `cannot find symbol: method findCaptionRejudgeTargets(...)`

- [ ] **Step 3: 선정 쿼리를 추가한다**

`InfluencerRepository.java`의 `findRejudgeTargets` **아래**에 추가한다. jsonb 배열 길이를 세야 해서 네이티브 쿼리다:

```java
    /**
     * BEAUTY 캡션 재판정 대상: 캡션 0건으로 판정됐지만 그 뒤로 릴스 페이지(실측 캡션)가 쌓인 계정.
     * 기존 findRejudgeTargets와 달리 beauty 값을 조건에 걸지 않는다 — 뷰티로 잘못 통과한
     * false positive를 실측 캡션으로 되돌리는 것이 이 경로의 목적이다(2026-07-30 오판 886개).
     * beauty_caption_count가 NULL인 기록 이전 판정분은 대상이 아니다(V22 백필이 0으로 표시한 것만).
     * minItems는 "캡션이 실제로 있을 만한 페이지"의 하한 — 아이템 수가 캡션 수의 근사다.
     * 재판정하면 judged_at이 갱신돼 조건이 닫힌다 — 캡션을 못 뽑아 count가 0으로 남아도
     * captured_at은 고정이라 같은 페이지로 다시 선정되지 않는다(무한 재대상 방지).
     */
    @Query(value = "select i.* from influencer i "
            + "where i.status = :status and i.beauty_source = :beautySource "
            + "and i.beauty_caption_count = 0 "
            + "and exists (select 1 from raw_media_page rmp "
            + "  where rmp.influencer_id = i.id and rmp.source = 'HIKER_V2_CLIPS' "
            + "  and jsonb_typeof(rmp.payload #> '{response,items}') = 'array' "
            + "  and jsonb_array_length(rmp.payload #> '{response,items}') >= :minItems "
            + "  and (i.beauty_judged_at is null or rmp.captured_at > i.beauty_judged_at)) "
            + "order by i.beauty_judged_at asc nulls first, i.id", nativeQuery = true)
    List<Influencer> findCaptionRejudgeTargets(@Param("status") String status,
                                               @Param("beautySource") String beautySource,
                                               @Param("minItems") int minItems,
                                               Pageable pageable);
```

> 네이티브 쿼리라 `status`는 enum이 아니라 문자열이다. 호출부에서 `InfluencerStatus.QUALIFIED.name()`을 넘긴다. 테스트가 enum을 넘기고 있다면 `.name()`으로 고친다.

- [ ] **Step 4: `BeautyJob`이 이 경로를 쓰게 한다**

상수를 `CAPTION_MAX_CHARS` 아래에 추가한다:

```java
    /**
     * 캡션 재판정에 필요한 릴스 페이지 최소 아이템 수 — 아이템 1~2개짜리 페이지로 재판정을 돌리면
     * 근거가 캡션 0건 때와 별로 다르지 않아 LLM 호출만 낭비된다.
     */
    static final int REJUDGE_MIN_ITEMS = 3;
```

`run()`의 기존 rejudge 블록 **아래**에 두 번째 경로를 붙인다:

```java
        if (rejudge && targets.size() < limit) {
            // 캡션 0건으로 판정된 뒤 릴스가 쌓인 계정 — 뷰티 판정분도 포함해 실측으로 되돌린다
            targets.addAll(influencers.findCaptionRejudgeTargets(
                    InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE,
                    REJUDGE_MIN_ITEMS, PageRequest.of(0, limit - targets.size())));
        }
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"
```

기대: PASS (신규 6개 + 기존 재판정 케이스 전부)

- [ ] **Step 6: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java
git commit -m "feat(crawler): 캡션 기반 재판정 — 뷰티 판정분도 실측으로 되돌린다

기존 재판정은 beauty=false만 대상이라 뷰티로 잘못 통과한 계정이 영구 고착됐다.
캡션 0건으로 판정된 뒤 릴스 페이지가 쌓인 계정은 beauty 값과 무관하게 재판정한다.
재판정이 judged_at을 갱신해 조건이 닫히므로 무한 재대상이 되지 않는다."
```

---

## Task 6: 응답 누락·중복 검증 로그

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

**배경:** 지금은 LLM이 요청한 50명 중 일부를 빼먹어도 예외도 로그도 없이 조용히 지나간다. 해당 계정은 미판정으로 남아 다음 실행에 재시도되지만, 이게 일어나고 있다는 사실 자체가 관측되지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BeautyJobTest.java`에 추가한다. 이 저장소에 이미 쓰이는 logback `ListAppender` 패턴을 그대로 쓴다:

```java
    @Test
    void 응답에서_누락된_계정을_경고로_남긴다() {
        Influencer a = qualified(1L, "acc1");
        Influencer b = qualified(2L, "acc2");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(a, b));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "BIO")));

        ListAppender<ILoggingEvent> appender = attachAppender(BeautyJob.class);
        job.run(TriggerType.MANUAL, false);

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("누락").contains("acc2"));
    }

    @Test
    void 응답_중복을_경고로_남긴다() {
        Influencer a = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "BIO"),
                new BeautyJudge.Verdict("acc1", BeautyClass.NOT_BEAUTY, "다른 이유", "BIO")));

        ListAppender<ILoggingEvent> appender = attachAppender(BeautyJob.class);
        job.run(TriggerType.MANUAL, false);

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("중복").contains("acc1"));
    }

    static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
```

> 이 저장소에 이미 `ListAppender`를 붙이는 테스트가 있다면 그쪽 헬퍼를 재사용하고 `attachAppender`를 새로 만들지 않는다: `grep -rn "ListAppender" crawler/src/test`

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"
```

기대: FAIL — WARN 로그가 없어 `anySatisfy` 단언 실패

- [ ] **Step 3: 검증 로그를 구현한다**

`BeautyJob.java`의 `run()`에서 `judge.judge(chunk)` 성공 직후, `applyVerdicts` 호출 **앞**에 한 줄을 넣는다:

```java
            logResponseGaps(chunk, verdicts);
```

`applyVerdicts` 위에 헬퍼를 추가한다:

```java
    /**
     * 응답 누락·중복 관측 — 모델이 요청한 계정 일부를 빼먹어도 예외가 아니라서(나머지 판정을
     * 버릴 이유가 없다) 조용히 지나가던 것을 로그로 드러낸다. 누락분은 미판정으로 남아 다음 실행에
     * 재시도되므로 데이터 유실은 아니지만, 빈도가 높아지면 프롬프트·청크 크기를 의심해야 한다.
     */
    private static void logResponseGaps(List<BeautyJudge.ProfileCard> chunk,
                                        List<BeautyJudge.Verdict> verdicts) {
        java.util.Set<String> returned = new java.util.LinkedHashSet<>();
        List<String> dups = new ArrayList<>();
        for (BeautyJudge.Verdict v : verdicts) {
            if (!returned.add(v.username())) dups.add(v.username());
        }
        List<String> missing = chunk.stream()
                .map(BeautyJudge.ProfileCard::username)
                .filter(u -> !returned.contains(u))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("뷰티 판정 응답 누락 {}건 — 미판정 유지, 다음 실행 재시도: {}", missing.size(), missing);
        }
        if (!dups.isEmpty()) {
            log.warn("뷰티 판정 응답 중복 {}건 — 마지막 값이 적용됨: {}", dups.size(), dups);
        }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"
```

기대: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java
git commit -m "feat(crawler): 뷰티 판정 응답 누락·중복 관측

모델이 요청 계정 일부를 빼먹어도 로그조차 없던 것을 WARN으로 드러낸다.
누락분은 미판정으로 남아 재시도되므로 계약(배치 격리)은 그대로 둔다."
```

---

## Task 7: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: 작업 트랙 표(§5)에 항목을 추가한다**

새 트랙 문자를 부여한다 — 기존 최대 트랙 문자를 확인하고 다음 것을 쓴다:

```bash
grep -n "^| [A-Z] " ARCHITECTURE.md | tail -5
```

트랙 행 내용: "계정 뷰티 판정 품질 — 실측 캡션 기반 사후 재판정", 상태는 구현 완료·PR 대기.

- [ ] **Step 2: 결정 기록(§7)에 항목을 추가한다**

기존 항목 형식에 맞춰 아래 세 결정을 남긴다:

- 뷰티 판정 캡션은 `raw_media_page`(HIKER_V2_CLIPS) 실측을 폴백으로 쓴다 — 프로필 리스냅샷(SELF_GQL 재수집, 2만 콜)은 비용 대비 이득이 맞지 않아 폐기했다.
- 캡션 재판정 경로만 `beauty=true`를 대상으로 삼는다 — false positive 교정이 목적이며, 기존 경로(`beauty=false`)는 그대로 둔다.
- 인스타그램 `category_name`은 금지 근거가 아니라 우선순위가 낮은 근거로 다룬다 — 프롬프트에서 비뷰티로 기울이면 게시물이 쌓이지 않아 되돌릴 수 없다.

- [ ] **Step 3: 커밋**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 뷰티 판정 품질 트랙 — 작업 트랙·결정 기록 반영"
```

---

## Task 8: 최종 검증

- [ ] **Step 1: 전체 테스트**

```bash
./gradlew test
```

기대: 전체 PASS. 실패가 있으면 그 출력을 그대로 보고하고, 통과했다고 말하지 않는다.

- [ ] **Step 2: 마이그레이션 번호 재확인**

```bash
git fetch origin && git log origin/develop --oneline -5 -- crawler/src/main/resources/db/migration/
```

`origin/develop`에 V22가 이미 들어왔으면 파일명을 다음 빈 번호로 바꾼다. 2026-07-21 V18 경합 사례가 있다.

- [ ] **Step 3: expand-contract 가드 자체 점검**

V22에 `DROP`/`RENAME`/타입 변경/`SET NOT NULL`이 없는지 눈으로 확인한다. 없으면 `-- allow-destructive:`/`-- no-backfill:` 주석 없이 CI `migration-guard`를 통과한다.

- [ ] **Step 4: PR 생성 전 확인 사항을 사용자에게 보고한다**

- Task 0의 백필 대상 건수(운영 측정값)
- 배포 순서: 마이그레이션 → 코드. expand 단계라 롤링 중 신구 코드 공존에 안전하다.
- 배포 후 첫 BEAUTY 잡 실행에서 캡션 재판정이 도는지, `beauty=true → false` 전환이 실제로 나오는지 확인이 필요하다는 점.

---

## 범위 밖 (이 계획에서 하지 않는 것)

- **미판정 18,545개(전체 34%)** — `beauty` NULL이라 게시물 수집 대상이 아니고, 프로필 리스냅샷을 폐기한 이상 재료가 생기지 않는다. 별도 트랙.
- **`HIKER_V1_MEDIAS`(피드) 캡션** — payload에 캡션이 있는지 검증한 뷰도 픽스처도 없다. 운영 DB 샘플 조회로 실측한 뒤에 넣는다.
- **`beauty_basis`/`beauty_caption_count`의 analytics·was 노출** — crawler 내부 판정 품질까지가 이 트랙이다.
- **발굴 표면의 게시물 실측 비율 게이트** — PR #204(analytics/was 층)에서 이미 다룬다.
