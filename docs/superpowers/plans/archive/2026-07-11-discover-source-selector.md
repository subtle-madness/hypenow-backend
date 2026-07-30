# 발굴 소스 셀렉터 (ACTOR/HIKER) Implementation Plan

> 상태: ✅ 구현됨 — `DiscoverSourceSelector` 현재도 사용 중(변경 없음). 스펙: [specs/2026-07-11-discover-source-selector-design.md](../../specs/2026-07-11-discover-source-selector-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 발굴(Discover) 단계를 런타임 토글(ACTOR=Apify 해시태그 액터 / HIKER=HikerAPI `/v2/hashtag/medias/top`, 기본 HIKER)로 전환한다.

**Architecture:** 댓글(`CommentSource`)·프로필(`ProfileSource`)과 동일한 토글 4종 세트(enum + Setting + Fetcher port + Selector)의 세 번째 복제. `DiscoverJob`은 executor 직접 호출 대신 `DiscoverSourceSelector`에 위임하고, `DiscoveryItemParser`·필터·저장 로직은 불변. HikerAPI 응답은 `HikerDiscoveryMapper`가 파서 계약 4필드로 정규화하고 원본을 `_rawMedia`에 통째 보존한다.

**Tech Stack:** Java 21, Spring Boot 4, tools.jackson(Jackson 3), JUnit 5 + AssertJ + Mockito, Gradle.

## Global Constraints

- 패키지 루트 `com.celfit.crawler`. spec: `docs/superpowers/specs/2026-07-11-discover-source-selector-design.md`.
- `ApifyException`은 `com.celfit.crawler.crawling.application.port.out.ApifyException` (adapter 패키지 아님).
- ObjectMapper는 `tools.jackson.databind.ObjectMapper` (com.fasterxml 금지). JsonNode 문자열 추출은 `.asString(null)`.
- 설정 키 `discover.source`, 기본값·미인식 폴백 모두 `HIKER`.
- HikerAPI 호출: 기존 `HikerHttp.get(path)` 재사용 (base URL·x-access-key는 `JdkHikerHttp`가 처리). 새 HTTP 어댑터 금지.
- 파서 계약(불변): payload에 `shortCode`(String)·`timestamp`(ISO-8601 String, `Instant.parse` 호환)·`ownerUsername`(String) 필수, `productType`=="clips"→REELS.
- 원본 보존: HIKER 아이템은 media 원본 전체를 `_rawMedia` 키에 저장 (프로필 `_rawProfile` 컨벤션).
- crawl_run 라벨: HIKER는 `hiker-hashtag-top` (actorId 컬럼).
- HikerAPI 페이지네이션: 최상위 `next_page_id` → `&page_id=`, `response.more_available`, 페이지당 32개 내외. `settings.resultsLimit()` 이상 모이면 중단(페이지 단위 과수집 허용).
- 기존 통합테스트(`DiscoverJobTest`, `SettingsApiTest`)는 FakeApifyRunner 경유 → 배선 교체 후 `discover.source=ACTOR` 강제 설정 필요 (Task 5에 포함).
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

### Task 1: DiscoverSource enum + DiscoverSourceSetting (기본 HIKER)

**Files:**
- Create: `src/main/java/com/celfit/crawler/settings/domain/DiscoverSource.java`
- Create: `src/main/java/com/celfit/crawler/settings/application/service/DiscoverSourceSetting.java`
- Test: `src/test/java/com/celfit/crawler/settings/application/service/DiscoverSourceSettingTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository`·`AppSetting`(기존), `ProfileSourceSettingTest.fakeRepo(Map)`(기존 public static 테스트 헬퍼)
- Produces: `enum DiscoverSource { ACTOR, HIKER }`, `DiscoverSourceSetting.current(): DiscoverSource`, `update(DiscoverSource)`, `updateRaw(String)` — Task 5·6이 사용

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class DiscoverSourceSettingTest {

    @Test void 기본값은_HIKER() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(DiscoverSource.HIKER);
    }

    @Test void 저장한_값을_읽는다() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(DiscoverSource.ACTOR);
        assertThat(setting.current()).isEqualTo(DiscoverSource.ACTOR);
    }

    @Test void 이상한_값이면_HIKER_폴백() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.updateRaw("GARBAGE");
        assertThat(setting.current()).isEqualTo(DiscoverSource.HIKER);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*DiscoverSourceSettingTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE (`DiscoverSource`, `DiscoverSourceSetting` 미존재)

- [ ] **Step 3: 최소 구현**

`src/main/java/com/celfit/crawler/settings/domain/DiscoverSource.java`:
```java
package com.celfit.crawler.settings.domain;

/** 발굴(해시태그) 수집 소스. */
public enum DiscoverSource {
    /** Apify instagram-hashtag-scraper 액터. */
    ACTOR,
    /** HikerAPI /v2/hashtag/medias/top (해시태그 인기, 기본). */
    HIKER
}
```

`src/main/java/com/celfit/crawler/settings/application/service/DiscoverSourceSetting.java`:
```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 발굴 소스 토글. app_setting 키 discover.source, 없거나 이상하면 HIKER. */
@Service
public class DiscoverSourceSetting {

    static final String KEY = "discover.source";

    private final AppSettingRepository settings;

    public DiscoverSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DiscoverSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(DiscoverSource.HIKER);
    }

    @Transactional
    public void update(DiscoverSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    /** 테스트·마이그레이션용: 검증 없이 원문 저장. current()가 파싱 실패 시 HIKER 폴백. */
    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private DiscoverSource parse(String value) {
        try {
            return DiscoverSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return DiscoverSource.HIKER;
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*DiscoverSourceSettingTest*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/settings/domain/DiscoverSource.java \
        src/main/java/com/celfit/crawler/settings/application/service/DiscoverSourceSetting.java \
        src/test/java/com/celfit/crawler/settings/application/service/DiscoverSourceSettingTest.java
git commit -m "feat: discover.source 토글 설정 (기본 HIKER)"
```

---

### Task 2: DiscoverFetcher 포트 + ActorDiscoverFetcher

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/DiscoverFetcher.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ActorDiscoverFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ActorDiscoverFetcherTest.java`

**Interfaces:**
- Consumes: `CrawlExecutor.execute(JobName, TriggerType, Long, String, String, Map<String,Object>)` Map 오버로드(기존), `Actors.DISCOVERY`, `ActorInputs.discovery(String, int)`, `SettingsService.resultsLimit()`
- Produces: `interface DiscoverFetcher { CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger); DiscoverSource source(); }` — Task 4·5가 구현/사용

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActorDiscoverFetcherTest {

    @Test void 기존_액터_경로로_위임한다() {
        CrawlExecutor executor = mock(CrawlExecutor.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(7);
        var expected = new CrawlExecutor.Execution(1L, List.of(Map.of("shortCode", "sc1")));
        when(executor.execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq(5L), eq("립"),
                eq(Actors.DISCOVERY), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(expected);

        var fetcher = new ActorDiscoverFetcher(executor, settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex).isSameAs(expected);
        assertThat(fetcher.source()).isEqualTo(DiscoverSource.ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(executor).execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq(5L), eq("립"),
                eq(Actors.DISCOVERY), input.capture());
        assertThat(input.getValue()).containsEntry("resultsLimit", 7).containsEntry("hashtags", List.of("립"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*ActorDiscoverFetcherTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE (`DiscoverFetcher`, `ActorDiscoverFetcher` 미존재)

- [ ] **Step 3: 최소 구현**

`src/main/java/com/celfit/crawler/crawling/application/port/out/DiscoverFetcher.java`:
```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DiscoverSource;

/** 발굴(키워드→게시물 목록) 소스 추상화. 구현체는 crawl_run 기록까지 책임진다. */
public interface DiscoverFetcher {

    CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger);

    DiscoverSource source();
}
```

`src/main/java/com/celfit/crawler/crawling/application/service/ActorDiscoverFetcher.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.springframework.stereotype.Component;

/** 기존 Apify 해시태그 액터 경로. */
@Component
public class ActorDiscoverFetcher implements DiscoverFetcher {

    private final CrawlExecutor executor;
    private final SettingsService settings;

    public ActorDiscoverFetcher(CrawlExecutor executor, SettingsService settings) {
        this.executor = executor;
        this.settings = settings;
    }

    @Override
    public CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger) {
        return executor.execute(JobName.DISCOVER, trigger, categoryId, keyword,
                Actors.DISCOVERY, ActorInputs.discovery(keyword, settings.resultsLimit()));
    }

    @Override
    public DiscoverSource source() {
        return DiscoverSource.ACTOR;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*ActorDiscoverFetcherTest*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/DiscoverFetcher.java \
        src/main/java/com/celfit/crawler/crawling/application/service/ActorDiscoverFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ActorDiscoverFetcherTest.java
git commit -m "feat: DiscoverFetcher 포트 + ActorDiscoverFetcher (기존 액터 경로 이동)"
```

---

### Task 3: HikerDiscoveryMapper (sections 순회 + 정규화 + _rawMedia)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoveryMapper.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerDiscoveryMapperTest.java`

**Interfaces:**
- Consumes: `tools.jackson.databind.ObjectMapper`(Boot 빈), `ApifyException`
- Produces: `record Page(List<Map<String,Object>> items, String nextPageId, boolean moreAvailable)` + `Page parse(String json)` — Task 4가 사용

**응답 구조(실측, `~/Desktop/hiker_vs_self/hashtag_TOP_HIKERAPI.json`):**
```
{ "response": { "sections": [ { "layout_content": {
      "medias":      [ {"media": {…}} ],
      "fill_items":  [ {"media": {…}} ],          // 캐러셀 조각 — code 없음 → 스킵됨
      "one_by_two_item": { "clips": { "items": [ {"media": {…}} ] } }
  }}], "more_available": true, "next_max_id": "…" },
  "next_page_id": "Wy…" }
```
media 필수: `code`, `taken_at`(epoch초), `user.username`. 부가: `product_type`, `like_count`, `comment_count`, `play_count`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerDiscoveryMapperTest {

    HikerDiscoveryMapper mapper = new HikerDiscoveryMapper(new ObjectMapper());

    // 실측 축약: medias 1개(릴스) + fill_items 1개(code 없는 캐러셀 조각) + one_by_two_item 1개(피드)
    static final String JSON = """
        {"response":{"sections":[
          {"layout_content":{"medias":[{"media":{
            "pk":"1","code":"DZr1AvEMT0M","taken_at":1781694665,"product_type":"clips",
            "like_count":1469,"comment_count":32,"play_count":108290,
            "user":{"pk":"76739063345","username":"owysim"}}}]}},
          {"layout_content":{"fill_items":[{"media":{
            "pk":"2","taken_at":1781000000,"product_type":"carousel_item"}}]}},
          {"layout_content":{"one_by_two_item":{"clips":{"items":[{"media":{
            "pk":"3","code":"DUpnobjj653","taken_at":1780000000,"product_type":"feed",
            "like_count":50,"comment_count":3,
            "user":{"pk":"9","username":"pink._.soodal"}}}]}}}}
        ],"more_available":true,"next_max_id":"abc"},"next_page_id":"PAGE2"}""";

    @Test void 정규화_4필드와_부가카운트() {
        var page = mapper.parse(JSON);
        assertThat(page.items()).hasSize(2);  // 캐러셀 조각(code 없음)은 스킵
        Map<String, Object> first = page.items().get(0);
        assertThat(first)
            .containsEntry("shortCode", "DZr1AvEMT0M")
            .containsEntry("timestamp", "2026-06-17T11:11:05Z")   // 1781694665 epoch → ISO
            .containsEntry("ownerUsername", "owysim")
            .containsEntry("productType", "clips")
            .containsEntry("likesCount", 1469L)
            .containsEntry("commentsCount", 32L)
            .containsEntry("videoPlayCount", 108290L);
        assertThat(first).containsKey("_rawMedia");  // 원본 통째 보존
        assertThat(page.items().get(1)).containsEntry("ownerUsername", "pink._.soodal");
    }

    @Test void 페이지네이션_커서() {
        var page = mapper.parse(JSON);
        assertThat(page.nextPageId()).isEqualTo("PAGE2");
        assertThat(page.moreAvailable()).isTrue();
    }

    @Test void username_없는_미디어는_스킵() {
        String json = """
            {"response":{"sections":[{"layout_content":{"medias":[{"media":{
              "pk":"1","code":"X","taken_at":1781694665}}]}}],"more_available":false}}""";
        var page = mapper.parse(json);
        assertThat(page.items()).isEmpty();
        assertThat(page.moreAvailable()).isFalse();
        assertThat(page.nextPageId()).isNull();
    }

    @Test void 깨진_JSON이면_ApifyException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.parse("{broken"))
            .isInstanceOf(com.celfit.crawler.crawling.application.port.out.ApifyException.class);
    }
}
```

**epoch 검증값 계산:** `1781694665` = `Instant.ofEpochSecond(1781694665).toString()` = `"2026-06-17T11:11:05Z"` (Apify 원본 payload의 timestamp `2026-06-17T11:11:05.000Z`와 동일 시각 — 밀리초 표기만 다르고 `Instant.parse`는 둘 다 수용).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*HikerDiscoveryMapperTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE (`HikerDiscoveryMapper` 미존재)

- [ ] **Step 3: 최소 구현**

`src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoveryMapper.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HikerAPI /v2/hashtag/medias/top 응답을 DiscoveryItemParser 계약(payload)으로 정규화.
 * sections[].layout_content의 medias/fill_items/one_by_two_item.clips.items 3종을 순회하고
 * 필수(code·taken_at·user.username) 결손 노드(캐러셀 조각 등)는 스킵. 원본 media는 _rawMedia로 보존.
 */
@Component
public class HikerDiscoveryMapper {

    /** 한 페이지 결과: 정규화 아이템 + 다음 페이지 커서. */
    public record Page(List<Map<String, Object>> items, String nextPageId, boolean moreAvailable) {}

    private final ObjectMapper om;

    public HikerDiscoveryMapper(ObjectMapper om) {
        this.om = om;
    }

    public Page parse(String json) {
        JsonNode root = read(json);
        JsonNode response = root.path("response");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode section : response.path("sections")) {
            JsonNode lc = section.path("layout_content");
            collect(lc.path("medias"), items);
            collect(lc.path("fill_items"), items);
            collect(lc.path("one_by_two_item").path("clips").path("items"), items);
        }
        return new Page(items,
                root.path("next_page_id").asString(null),
                response.path("more_available").asBoolean(false));
    }

    private void collect(JsonNode arr, List<Map<String, Object>> out) {
        for (JsonNode node : arr) {
            JsonNode m = node.has("media") ? node.path("media") : node;
            Map<String, Object> item = toItem(m);
            if (item != null) out.add(item);
        }
    }

    /** 파서 필수 3필드 결손이면 null (캐러셀 조각·비정상 노드). */
    private Map<String, Object> toItem(JsonNode m) {
        String code = m.path("code").asString(null);
        long takenAt = m.path("taken_at").asLong();
        String username = m.path("user").path("username").asString(null);
        if (code == null || takenAt <= 0 || username == null) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shortCode", code);
        item.put("timestamp", Instant.ofEpochSecond(takenAt).toString());
        item.put("ownerUsername", username);
        item.put("productType", m.path("product_type").asString(null));
        item.put("likesCount", m.path("like_count").asLong());
        item.put("commentsCount", m.path("comment_count").asLong());
        item.put("videoPlayCount", m.path("play_count").asLong());
        item.put("_rawMedia", om.convertValue(m, Object.class));
        return item;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("해시태그 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*HikerDiscoveryMapperTest*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. `timestamp` 검증 실패 시 epoch 계산을 다시 확인:
`jshell> java.time.Instant.ofEpochSecond(1781694665L)` → `2026-06-17T11:11:05Z`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoveryMapper.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerDiscoveryMapperTest.java
git commit -m "feat: HikerDiscoveryMapper — sections 순회·epoch→ISO·_rawMedia 원본보존"
```

---

### Task 4: HikerDiscoverFetcher (페이지네이션 + Supplier 실행)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoverFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerDiscoverFetcherTest.java`

**Interfaces:**
- Consumes: Task 2 `DiscoverFetcher`, Task 3 `HikerDiscoveryMapper.parse(String): Page`, `HikerHttp.get(String)`(기존), `CrawlExecutor.execute(JobName, TriggerType, Long, String, String, Supplier<ApifyResult>)` Supplier 오버로드(기존), `SettingsService.resultsLimit()`
- Produces: `HikerDiscoverFetcher`(source=HIKER, 라벨 `hiker-hashtag-top`) — Task 5가 사용

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerDiscoverFetcherTest {

    static String media(String code, String user) {
        return """
            {"media":{"code":"%s","taken_at":1781694665,"product_type":"clips",
             "like_count":1,"comment_count":1,"play_count":1,
             "user":{"username":"%s"}}}""".formatted(code, user);
    }

    static String page(String medias, String nextPageId, boolean more) {
        return """
            {"response":{"sections":[{"layout_content":{"medias":[%s]}}],"more_available":%b},
             "next_page_id":%s}""".formatted(medias, more,
                nextPageId == null ? "null" : "\"" + nextPageId + "\"");
    }

    /** executor 목: Supplier를 즉시 실행해 Execution으로 감싼다 (CrawlExecutor 실동작 모사). */
    static CrawlExecutor passthroughExecutor(List<String> capturedLabels) {
        CrawlExecutor executor = mock(CrawlExecutor.class);
        when(executor.execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq(5L), eq("립"),
                any(String.class), any(Supplier.class)))
            .thenAnswer(inv -> {
                capturedLabels.add(inv.getArgument(4));
                Supplier<ApifyResult> work = inv.getArgument(5);
                ApifyResult r = work.get();
                return new CrawlExecutor.Execution(1L, r.items());
            });
        return executor;
    }

    @Test void resultsLimit_채울때까지_페이지_반복() {
        HikerHttp http = path -> path.contains("page_id=P2")
            ? page(media("C3", "u3"), null, false)
            : page(media("C1", "u1") + "," + media("C2", "u2"), "P2", true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(3);
        List<String> labels = new ArrayList<>();

        var fetcher = new HikerDiscoverFetcher(http, passthroughExecutor(labels),
                new HikerDiscoveryMapper(new ObjectMapper()), settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(3);
        assertThat(ex.items().get(2).get("shortCode")).isEqualTo("C3");
        assertThat(labels).containsExactly("hiker-hashtag-top");
        assertThat(fetcher.source()).isEqualTo(DiscoverSource.HIKER);
    }

    @Test void limit_도달하면_다음_페이지_안_부름() {
        List<String> calls = new ArrayList<>();
        HikerHttp http = path -> {
            calls.add(path);
            return page(media("C1", "u1") + "," + media("C2", "u2"), "P2", true);
        };
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(2);  // 첫 페이지로 충족

        var fetcher = new HikerDiscoverFetcher(http, passthroughExecutor(new ArrayList<>()),
                new HikerDiscoveryMapper(new ObjectMapper()), settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(2);
        assertThat(calls).hasSize(1);  // page_id 요청 없음
    }

    @Test void 키워드는_URL인코딩된다() {
        List<String> calls = new ArrayList<>();
        HikerHttp http = path -> { calls.add(path); return page(media("C1", "u1"), null, false); };
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(10);

        new HikerDiscoverFetcher(http, passthroughExecutor(new ArrayList<>()),
                new HikerDiscoveryMapper(new ObjectMapper()), settings)
            .fetch(5L, "립", TriggerType.MANUAL);

        assertThat(calls.get(0)).startsWith("/v2/hashtag/medias/top?name=%EB%A6%BD");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*HikerDiscoverFetcherTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE (`HikerDiscoverFetcher` 미존재)

- [ ] **Step 3: 최소 구현**

`src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoverFetcher.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * HikerAPI 해시태그 인기(/v2/hashtag/medias/top) 발굴.
 * resultsLimit 이상 모일 때까지 next_page_id로 반복(페이지 단위 과수집 허용).
 * 페이지 중간 실패는 예외 전파 — 부분 발굴을 성공으로 기록하지 않는다(키워드 단위 재시도).
 */
@Component
public class HikerDiscoverFetcher implements DiscoverFetcher {

    static final String LABEL = "hiker-hashtag-top";

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final HikerDiscoveryMapper mapper;
    private final SettingsService settings;

    public HikerDiscoverFetcher(HikerHttp http, CrawlExecutor executor,
                                HikerDiscoveryMapper mapper, SettingsService settings) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger) {
        return executor.execute(JobName.DISCOVER, trigger, categoryId, keyword, LABEL,
                () -> new ApifyResult(null, collect(keyword)));
    }

    private List<Map<String, Object>> collect(String keyword) {
        int limit = settings.resultsLimit();
        String enc = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        List<Map<String, Object>> out = new ArrayList<>();
        String pageId = null;
        while (true) {
            String path = "/v2/hashtag/medias/top?name=" + enc
                    + (pageId == null ? "" : "&page_id=" + URLEncoder.encode(pageId, StandardCharsets.UTF_8));
            HikerDiscoveryMapper.Page page = mapper.parse(http.get(path));
            out.addAll(page.items());
            pageId = page.nextPageId();
            if (out.size() >= limit || !page.moreAvailable() || pageId == null || pageId.isBlank()) break;
        }
        return out;
    }

    @Override
    public DiscoverSource source() {
        return DiscoverSource.HIKER;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*HikerDiscoverFetcherTest*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/HikerDiscoverFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerDiscoverFetcherTest.java
git commit -m "feat: HikerDiscoverFetcher — 해시태그 top 페이지네이션 발굴"
```

---

### Task 5: DiscoverSourceSelector + DiscoverJob 배선 교체

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/DiscoverSourceSelector.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/DiscoverJob.java` (executor·settings 의존 제거 → selector)
- Modify: `src/test/java/com/celfit/crawler/crawling/application/service/DiscoverJobTest.java` (`discover.source=ACTOR` 강제)
- Modify: `src/test/java/com/celfit/crawler/settings/adapter/in/web/SettingsApiTest.java` (동일)
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/DiscoverSourceSelectorTest.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/DiscoverJobRoutingTest.java`

**Interfaces:**
- Consumes: Task 1 `DiscoverSourceSetting.current()`, Task 2 `DiscoverFetcher`
- Produces: `DiscoverSourceSelector.fetch(long categoryId, String keyword, TriggerType trigger): CrawlExecutor.Execution` — DiscoverJob이 사용

- [ ] **Step 1: 셀렉터 실패 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoverSourceSelectorTest {

    static DiscoverFetcher fetcher(DiscoverSource src, String marker) {
        return new DiscoverFetcher() {
            @Override public CrawlExecutor.Execution fetch(long c, String k, TriggerType t) {
                return new CrawlExecutor.Execution(1L, List.of(Map.of("shortCode", marker)));
            }
            @Override public DiscoverSource source() { return src; }
        };
    }

    @Test void 설정된_소스의_페처를_고른다() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(DiscoverSource.ACTOR);
        var sel = new DiscoverSourceSelector(
            List.of(fetcher(DiscoverSource.ACTOR, "actor"), fetcher(DiscoverSource.HIKER, "hiker")), setting);
        var ex = sel.fetch(1L, "립", TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("shortCode")).isEqualTo("actor");
    }

    @Test void 미등록_소스면_HIKER_폴백() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(DiscoverSource.ACTOR);  // ACTOR 페처 미등록
        var sel = new DiscoverSourceSelector(List.of(fetcher(DiscoverSource.HIKER, "hiker")), setting);
        var ex = sel.fetch(1L, "립", TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("shortCode")).isEqualTo("hiker");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*DiscoverSourceSelectorTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE

- [ ] **Step 3: 셀렉터 구현**

`src/main/java/com/celfit/crawler/crawling/application/service/DiscoverSourceSelector.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** discover.source 설정으로 발굴 페처 선택(미존재 시 HIKER 폴백). */
@Service
public class DiscoverSourceSelector {

    private final Map<DiscoverSource, DiscoverFetcher> bySource;
    private final DiscoverSourceSetting setting;

    public DiscoverSourceSelector(List<DiscoverFetcher> fetchers, DiscoverSourceSetting setting) {
        this.bySource = fetchers.stream().collect(Collectors.toMap(DiscoverFetcher::source, Function.identity()));
        this.setting = setting;
    }

    public CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger) {
        DiscoverFetcher f = bySource.get(setting.current());
        if (f == null) f = bySource.get(DiscoverSource.HIKER);
        return f.fetch(categoryId, keyword, trigger);
    }
}
```

Run: `./gradlew test --tests "*DiscoverSourceSelectorTest*" 2>&1 | tail -5` → BUILD SUCCESSFUL

- [ ] **Step 4: DiscoverJob 라우팅 실패 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.CollectionRuleRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.application.port.out.RawDiscoveryPostRepository;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.CategoryKeyword;
import com.celfit.crawler.crawling.application.port.out.AccountRepository;
import com.celfit.crawler.settings.application.port.out.CategoryKeywordRepository;
import com.celfit.crawler.settings.application.port.out.CategoryRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * DiscoverJob이 (구) executor.execute(...Actors.DISCOVERY...) 직접 호출이 아니라
 * DiscoverSourceSelector.fetch(...)에 위임하는지 검증하는 순수 mockito 배선 테스트.
 */
class DiscoverJobRoutingTest {

    @Test
    void run은_DiscoverSourceSelector_경유로_발굴한다() {
        CategoryRepository categories = mock(CategoryRepository.class);
        CategoryKeywordRepository keywords = mock(CategoryKeywordRepository.class);
        CollectionRuleRepository rules = mock(CollectionRuleRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        ContentRepository contents = mock(ContentRepository.class);
        RawDiscoveryPostRepository rawDiscovery = mock(RawDiscoveryPostRepository.class);
        DiscoverSourceSelector selector = mock(DiscoverSourceSelector.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);

        Category cat = mock(Category.class);
        when(cat.isEnabled()).thenReturn(true);
        when(categories.findById(1L)).thenReturn(Optional.of(cat));
        CategoryKeyword kw = mock(CategoryKeyword.class);
        when(kw.getKeyword()).thenReturn("립");
        when(kw.getSubcategory()).thenReturn("");
        when(kw.getMainGroup()).thenReturn("");
        when(keywords.findByCategoryIdAndEnabledTrue(1L)).thenReturn(List.of(kw));
        when(rules.findByCategoryId(1L)).thenReturn(Optional.empty());
        when(accounts.findByUsername("owysim")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contents.findByShortCode("DZr1AvEMT0M")).thenReturn(Optional.empty());
        when(contents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> item = Map.of("shortCode", "DZr1AvEMT0M", "productType", "clips",
                "timestamp", "2026-06-17T11:11:05Z", "ownerUsername", "owysim");
        when(selector.fetch(1L, "립", TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(9L, List.of(item)));

        DiscoverJob job = new DiscoverJob(categories, keywords, rules, accounts,
                contents, rawDiscovery, selector, clock);
        var summary = job.run(1L, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(1);
        verify(selector).fetch(1L, "립", TriggerType.MANUAL);
        verifyNoMoreInteractions(selector);
        verify(rawDiscovery).save(any());
    }
}
```

**주의:** 리포지토리들의 실제 패키지는 구현 시점에 import 오류가 알려준다 — `CategoryRepository`/`CategoryKeywordRepository`/`RawDiscoveryPostRepository`의 위치는 `DiscoverJob.java`의 기존 wildcard import 기준으로 `git grep "interface CategoryRepository" src/main`으로 확인해 맞출 것. 위 테스트의 import 패키지가 다르면 실제 위치로 고치면 된다(테스트 의도는 불변).

- [ ] **Step 5: DiscoverJob 배선 교체**

`DiscoverJob.java` 수정 — 필드·생성자에서 `CrawlExecutor executor`와 `SettingsService settings`를 제거하고 `DiscoverSourceSelector discoverSourceSelector`로 교체:

```java
    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;
    private final AccountRepository accounts;
    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final DiscoverSourceSelector discoverSourceSelector;
    private final Clock clock;

    public DiscoverJob(CategoryRepository categories, CategoryKeywordRepository keywords,
                       CollectionRuleRepository rules, AccountRepository accounts,
                       ContentRepository contents, RawDiscoveryPostRepository rawDiscovery,
                       DiscoverSourceSelector discoverSourceSelector, Clock clock) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
        this.accounts = accounts;
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.discoverSourceSelector = discoverSourceSelector;
        this.clock = clock;
    }
```

키워드 루프의 실행 부분 교체 — 기존:
```java
            try {
                ex = executor.execute(JobName.DISCOVER, trigger, categoryId, kw.getKeyword(),
                        Actors.DISCOVERY, ActorInputs.discovery(kw.getKeyword(), settings.resultsLimit()));
            } catch (ApifyException e) {
```
신규:
```java
            try {
                ex = discoverSourceSelector.fetch(categoryId, kw.getKeyword(), trigger);
            } catch (ApifyException e) {
```

미사용이 된 import 제거: `Actors`, `ActorInputs`, `JobName`, `SettingsService` — resultsLimit이 ActorDiscoverFetcher/HikerDiscoverFetcher로 이동했으므로 DiscoverJob에서 SettingsService는 완전히 제거된다(8-인자 생성자). DiscoverJob은 wildcard import(`settings.application.port.out.*` 등)를 쓰므로 실제 남는 import는 컴파일러 기준으로 정리.

- [ ] **Step 6: 기존 통합테스트에 ACTOR 강제**

`DiscoverJobTest.java` — FakeApifyRunner는 액터 경로만 모사하므로 소스를 ACTOR로 고정:
```java
    @Autowired com.celfit.crawler.settings.application.service.DiscoverSourceSetting discoverSourceSetting;

    @org.junit.jupiter.api.BeforeEach
    void forceActorSource() {
        discoverSourceSetting.update(com.celfit.crawler.settings.domain.DiscoverSource.ACTOR);
    }
```
(기존 `resetFake()` @BeforeEach와 별개 메서드로 추가해도 되고 합쳐도 된다.)

`SettingsApiTest.java` — `discoverJob.run(...)`을 호출하는 테스트가 있으므로 동일하게:
```java
    @Autowired com.celfit.crawler.settings.application.service.DiscoverSourceSetting discoverSourceSetting;
```
`resetFake()` @BeforeEach 안에 `discoverSourceSetting.update(com.celfit.crawler.settings.domain.DiscoverSource.ACTOR);` 한 줄 추가.

- [ ] **Step 7: 전체 테스트**

Run: `./gradlew test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (라우팅·셀렉터 신규 + 기존 DiscoverJobTest·SettingsApiTest 그린)

- [ ] **Step 8: 커밋**

```bash
git add -u src/
git add src/main/java/com/celfit/crawler/crawling/application/service/DiscoverSourceSelector.java \
        src/test/java/com/celfit/crawler/crawling/application/service/DiscoverSourceSelectorTest.java \
        src/test/java/com/celfit/crawler/crawling/application/service/DiscoverJobRoutingTest.java
git commit -m "feat: DiscoverJob 발굴을 DiscoverSourceSelector로 배선 (기본 HIKER)"
```

---

### Task 6: UI — 발굴 소스 카드 + 컨트롤러

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/DiscoverSourceUiController.java`
- Modify: `src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java` (`discoverSource` 모델 속성)
- Modify: `src/main/resources/templates/settings.html` (댓글 카드 앞에 발굴 카드)
- Test: `src/test/java/com/celfit/crawler/crawling/adapter/in/web/DiscoverSourceUiControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `DiscoverSourceSetting`
- Produces: `POST /ui/discover-source` (param `source`=ACTOR|HIKER) → redirect `/ui/settings`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class DiscoverSourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired DiscoverSourceSetting setting;

    @Test
    void 소스_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/discover-source").param("source", "ACTOR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(setting.current()).isEqualTo(DiscoverSource.ACTOR);
    }

    @Test
    void 설정_페이지가_발굴_소스를_노출한다() throws Exception {
        mvc.perform(get("/ui/settings")).andExpect(status().isOk());
        // 모델 속성은 UiSettingsController에서 추가 — 렌더 성공이면 배선 OK
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*DiscoverSourceUiControllerTest*" 2>&1 | tail -5`
Expected: COMPILE FAILURE 또는 404

- [ ] **Step 3: 구현**

`src/main/java/com/celfit/crawler/crawling/adapter/in/web/DiscoverSourceUiController.java`:
```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DiscoverSourceUiController {

    private final DiscoverSourceSetting setting;

    public DiscoverSourceUiController(DiscoverSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/discover-source")
    public String update(@RequestParam String source) {
        setting.update(DiscoverSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}
```

`UiSettingsController.java` — 생성자에 `DiscoverSourceSetting discoverSourceSetting` 추가(필드·대입 포함), `page()`에 한 줄 추가:
```java
        model.addAttribute("discoverSource", discoverSourceSetting.current().name());
```

`settings.html` — `<h1>런타임 설정</h1>` 바로 다음, 댓글 카드 **앞에** 삽입 (병행 상세 세션과의 충돌 최소화를 위해 위치 명시):
```html
<section class="card">
    <h2>발굴 수집 방식</h2>
    <form method="post" th:action="@{/ui/discover-source}">
        <label class="check"><input type="radio" name="source" value="HIKER"
            th:checked="${discoverSource == 'HIKER'}"/> HikerAPI 해시태그 인기 (기본)</label>
        <label class="check"><input type="radio" name="source" value="ACTOR"
            th:checked="${discoverSource == 'ACTOR'}"/> 액터 (Apify)</label>
        <button type="submit" class="primary">저장</button>
    </form>
    <p class="hint">현재: <b th:text="${discoverSource}">HIKER</b> · 기본값: HIKER · HikerAPI는 페이지(32개)당 $0.001</p>
</section>
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*DiscoverSourceUiControllerTest*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 전체 테스트 + 커밋**

Run: `./gradlew test 2>&1 | tail -5` → BUILD SUCCESSFUL

```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/in/web/DiscoverSourceUiController.java \
        src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java \
        src/main/resources/templates/settings.html \
        src/test/java/com/celfit/crawler/crawling/adapter/in/web/DiscoverSourceUiControllerTest.java
git commit -m "feat: 발굴 소스 선택 UI (HIKER 기본)"
```

---

### Task 7: 수동 스모크 (사용자 실행)

**Files:** 없음 (수동 검증)

- [ ] **Step 1: 실행 전제**
- IntelliJ Run Configuration에 `HIKER_API_KEY` 실키 설정 후 Stop→Run.

- [ ] **Step 2: HIKER 발굴 스모크**
- `/ui/settings`에서 발굴 소스가 HIKER(기본)인지 확인 → DISCOVER 수동 실행.
- 대시보드 최근 실행에 `hiker-hashtag-top` 라벨 SUCCEEDED + 건수 ≥ resultsLimit 근처인지 확인.
- DB 확인:
```sql
SELECT payload->>'shortCode', payload->>'productType', payload->>'timestamp',
       payload->>'ownerUsername', (payload->'_rawMedia') IS NOT NULL AS has_raw
FROM raw_discovery_post ORDER BY id DESC LIMIT 5;
```
- content에 PENDING 신규 행 생성 확인 (REELS·FEED 혼합 여부 포함).

- [ ] **Step 3: ACTOR 복귀 스모크**
- UI에서 ACTOR로 전환 → DISCOVER 실행 → `apify~instagram-hashtag-scraper` 라벨로 정상 동작 → 다시 HIKER로 복귀.

- [ ] **Step 4: 관찰 기록**
- 키워드별 수집량·중복률(재발굴 배지)·FAILED 키워드(해시태그 미존재) 여부를 확인하고 다음 세션에서 조정.
