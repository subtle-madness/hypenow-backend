# 상세 소스 셀렉터 (Detail Source Selector) Implementation Plan

> 상태: 🗄 대체됨 — 미구현 상태에서 07-14 인플루언서 파이프라인 전환이 aggregate 단계 자체를 폐기하며 소멸(DetailSourceSelector·DetailFetcher 등 미작성). 스펙: [specs/2026-07-11-detail-source-selector-design.md](../../specs/archive/2026-07-11-detail-source-selector-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `AggregateJob`의 상세 fetch를 타입별 런타임 선택 가능한 다중 소스(릴스=HikerAPI / 피드=self-crawl / ACTOR 선택지)로 바꾼다.

**Architecture:** 기존 댓글·프로필 소스 셀렉터 패턴 복제. `DetailFetcher` 포트 + 3구현(Actor/HikerReel/SelfFeed) + `DetailSourceSelector.forType(type)`가 `detail.<type>.source` 설정으로 fetcher 선택(ACTOR 폴백). `AggregateJob.aggregateChunk`의 상세 호출 한 줄만 셀렉터 경유로 교체.

**Tech Stack:** Java 21, Spring Boot 4, JPA/PostgreSQL, tools.jackson(Jackson 3, `tools.jackson.*`), JUnit5/AssertJ, Testcontainers.

## Global Constraints

- 작업 폴더/브랜치: `/Users/dongju/project/current/soma/hypenow/hypenow-detail` 의 `feat/detail-source-selector`. **모든 git·gradle 명령은 이 폴더에서 실행** (셸 기본 폴더가 `hypenow-crawler`로 리셋되므로 매번 `cd` 확인 — 그 폴더는 다른 세션의 발굴 워크트리).
- 패키지 루트: `com.celfit.crawler`.
- ObjectMapper는 **tools.jackson**(`tools.jackson.databind.ObjectMapper`), `com.fasterxml` 아님. JSON 파싱 실패는 `com.celfit.crawler.crawling.application.port.out.ApifyException`.
- `raw_post_detail` payload 하드계약(generated column): `shortCode`(=short_code)·`caption`·`likesCount`(=likes)·`commentsCount`(=comments_count)·`videoPlayCount`(=video_play_count). **`shortCode`는 필수**(인덱싱+컬럼). 광고판정(`AdSignals.adMarked`)이 읽는 `caption`·`isPaidPartnership`도 매퍼가 채운다. 원본은 `_rawDetail`로 통째.
- `DetailSource` enum 값: `ACTOR`, `HIKER`, `SELF`.
- 설정 키: `detail.reels.source`(기본 `HIKER`), `detail.feed.source`(기본 `SELF`). 파싱 실패/미설정 시 타입 기본값.
- crawl_run 라벨: HIKER=`detail-hiker-reels`, SELF=`detail-self-feed`, ACTOR=기존 Apify 액터 id(`apify~instagram-reel-scraper`/`apify~instagram-post-scraper` — Map 오버로드가 actorId를 runner에 넘기므로 라벨 커스터마이즈 불가, 현행 유지).
- per-item skip: HIKER/SELF는 shortCode 하나 실패 시 나머지 진행(청크 안 죽음). 청크 전체 예외는 `AggregateJob` 기존 catch가 재시도 처리.

---

## File Structure

**신규 (src/main/java/com/celfit/crawler/):**
- `settings/domain/DetailSource.java` — enum
- `settings/application/service/DetailSourceSetting.java` — 타입별 소스 설정
- `crawling/application/port/out/DetailFetcher.java` — 포트
- `crawling/application/service/DetailMapper.java` — 소스별 정규화 + _rawDetail
- `crawling/application/service/ActorDetailFetcher.java` — ACTOR(양타입)
- `crawling/application/service/HikerReelDetailFetcher.java` — HIKER(릴스)
- `crawling/application/service/SelfFeedDetailFetcher.java` — SELF(피드)
- `crawling/application/service/DetailSourceSelector.java` — forType 라우팅
- `crawling/adapter/out/instagram/DirectDetailProperties.java` — self 피드 doc_id/friendlyName/pageDelay
- `crawling/adapter/in/web/DetailSourceUiController.java` — POST /ui/detail-source

**수정:**
- `crawling/application/service/AggregateJob.java` — 상세 fetch를 셀렉터 경유로(한 줄)
- `crawling/adapter/in/web/UiSettingsController.java` — 상세 소스 모델 속성
- `src/main/resources/templates/settings.html` — 상세 카드(프로필 카드 뒤)
- `src/main/resources/application.yml` — `crawler.direct-detail`
- `common/config/CrawlerConfig.java` — `@EnableConfigurationProperties`에 `DirectDetailProperties` 추가
- `src/test/resources/application.yml` — 더미 `crawler.direct-detail`

---

## Task 1: DetailSource enum + DetailSourceSetting

**Files:**
- Create: `src/main/java/com/celfit/crawler/settings/domain/DetailSource.java`
- Create: `src/main/java/com/celfit/crawler/settings/application/service/DetailSourceSetting.java`
- Test: `src/test/java/com/celfit/crawler/settings/application/service/DetailSourceSettingTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository`(findById/save), `AppSetting`(생성자 `(String key, String value)`, `getValue()`), `com.celfit.crawler.content.domain.ContentType`(REELS/FEED).
- Produces: `DetailSource {ACTOR, HIKER, SELF}`; `DetailSourceSetting.sourceFor(ContentType) -> DetailSource`; `update(DetailSource reels, DetailSource feed)`.

- [ ] **Step 1: Write the failing test**

`DetailSourceSettingTest.java`:
```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DetailSourceSettingTest {

    /** app_setting을 흉내내는 인메모리 fake. */
    static AppSettingRepository fakeRepo(Map<String, String> store) {
        return new AppSettingRepository() {
            @Override public Optional<AppSetting> findById(String key) {
                return Optional.ofNullable(store.get(key)).map(v -> new AppSetting(key, v));
            }
            @Override public AppSetting save(AppSetting s) { store.put(s.getKey(), s.getValue()); return s; }
        };
    }

    @Test void 기본값_릴스HIKER_피드SELF() {
        var s = new DetailSourceSetting(fakeRepo(new HashMap<>()));
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.HIKER);
        assertThat(s.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.SELF);
    }

    @Test void update_후_타입별로_읽힌다() {
        Map<String, String> store = new HashMap<>();
        var s = new DetailSourceSetting(fakeRepo(store));
        s.update(DetailSource.ACTOR, DetailSource.ACTOR);
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.ACTOR);
        assertThat(s.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.ACTOR);
    }

    @Test void 이상값이면_타입_기본값_폴백() {
        Map<String, String> store = new HashMap<>();
        store.put("detail.reels.source", "GARBAGE");
        var s = new DetailSourceSetting(fakeRepo(store));
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.HIKER);
    }
}
```

> 참고: `AppSettingRepository`가 `JpaRepository` 확장이라 인터페이스 익명구현이 안 되면, 기존 `ProfileSourceSettingTest`의 fake 방식을 그대로 따른다(그 파일의 `fakeRepo` 시그니처 확인 후 동일 패턴 사용).

- [ ] **Step 2: Run test — 실패 확인**

`cd /Users/dongju/project/current/soma/hypenow/hypenow-detail && ./gradlew test --tests "*DetailSourceSettingTest*"`
Expected: 컴파일 실패(DetailSource/DetailSourceSetting 없음).

- [ ] **Step 3: enum 작성**

`DetailSource.java`:
```java
package com.celfit.crawler.settings.domain;

public enum DetailSource { ACTOR, HIKER, SELF }
```

- [ ] **Step 4: DetailSourceSetting 작성**

`DetailSourceSetting.java`:
```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상세 수집 소스 토글. 타입별 키(detail.reels.source/detail.feed.source), 기본 릴스=HIKER·피드=SELF. */
@Service
public class DetailSourceSetting {

    static final String REELS_KEY = "detail.reels.source";
    static final String FEED_KEY = "detail.feed.source";

    private final AppSettingRepository settings;

    public DetailSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DetailSource sourceFor(ContentType type) {
        String key = type == ContentType.REELS ? REELS_KEY : FEED_KEY;
        DetailSource dflt = type == ContentType.REELS ? DetailSource.HIKER : DetailSource.SELF;
        return settings.findById(key).map(AppSetting::getValue).map(v -> parse(v, dflt)).orElse(dflt);
    }

    @Transactional
    public void update(DetailSource reels, DetailSource feed) {
        settings.save(new AppSetting(REELS_KEY, reels.name()));
        settings.save(new AppSetting(FEED_KEY, feed.name()));
    }

    private DetailSource parse(String value, DetailSource dflt) {
        try {
            return DetailSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
```

- [ ] **Step 5: Run test — 통과 확인** → PASS

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/celfit/crawler/settings/domain/DetailSource.java \
        src/main/java/com/celfit/crawler/settings/application/service/DetailSourceSetting.java \
        src/test/java/com/celfit/crawler/settings/application/service/DetailSourceSettingTest.java
git commit -m "feat: DetailSource enum + 타입별 소스 설정"
```

---

## Task 2: DetailFetcher 포트 + DetailMapper

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/DetailFetcher.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/DetailMapper.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/DetailMapperTest.java`

**Interfaces:**
- Consumes: tools.jackson `ObjectMapper`; `ApifyException`; `ContentType`; `TriggerType`; `CrawlExecutor.Execution`.
- Produces: `DetailFetcher.fetch(List<String> shortCodes, ContentType type, TriggerType trigger) -> CrawlExecutor.Execution`; `DetailFetcher.source() -> DetailSource`; `DetailFetcher.supports(ContentType) -> boolean`. `DetailMapper.fromHikerMedia(String) / fromSelfGraphql(String) / fromActorItem(Map) -> Map<String,Object>`.

- [ ] **Step 1: Write the failing test**

`DetailMapperTest.java`:
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DetailMapperTest {

    DetailMapper mapper = new DetailMapper(new ObjectMapper());

    @Test void hiker_릴스_미디어_정규화() {
        String json = """
            {"code":"DKdNETtTTz_","caption_text":"광고 아님","like_count":19594,
             "comment_count":42,"play_count":88123,"is_paid_partnership":true}""";
        Map<String, Object> d = mapper.fromHikerMedia(json);
        assertThat(d.get("shortCode")).isEqualTo("DKdNETtTTz_");
        assertThat(d.get("caption")).isEqualTo("광고 아님");
        assertThat(d.get("likesCount")).isEqualTo(19594L);
        assertThat(d.get("commentsCount")).isEqualTo(42L);
        assertThat(d.get("videoPlayCount")).isEqualTo(88123L);
        assertThat(d.get("isPaidPartnership")).isEqualTo(true);
        assertThat(d).containsKey("_rawDetail");
    }

    @Test void self_피드_graphql_정규화() {
        // 실제 응답은 {data:{xdt_shortcode_media:{...}}} 래핑
        String json = """
            {"data":{"xdt_shortcode_media":{
              "shortcode":"DShi4OoEsoD",
              "edge_media_to_caption":{"edges":[{"node":{"text":"#협찬 캡션"}}]},
              "edge_media_preview_like":{"count":720},
              "edge_media_to_comment":{"count":15},
              "is_paid_partnership":false}}}""";
        Map<String, Object> d = mapper.fromSelfGraphql(json);
        assertThat(d.get("shortCode")).isEqualTo("DShi4OoEsoD");
        assertThat(d.get("caption")).isEqualTo("#협찬 캡션");
        assertThat(d.get("likesCount")).isEqualTo(720L);
        assertThat(d.get("commentsCount")).isEqualTo(15L);
        assertThat(d.get("videoPlayCount")).isNull();   // 피드=조회수 없음
        assertThat(d).containsKey("_rawDetail");
    }

    @Test void actor_아이템은_그대로_통과() {
        Map<String, Object> item = new java.util.HashMap<>(Map.of(
            "shortCode", "ABC", "caption", "x", "likesCount", 5, "commentsCount", 1));
        assertThat(mapper.fromActorItem(item)).isSameAs(item);
    }
}
```

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: 포트 작성**

`DetailFetcher.java`:
```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;

/** 청크(shortCode 여러 개)의 상세 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface DetailFetcher {
    CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger);
    DetailSource source();
    boolean supports(ContentType type);
}
```

- [ ] **Step 4: DetailMapper 작성**

`DetailMapper.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 소스별 상세 응답을 raw_post_detail payload 계약으로 정규화 + 원본 통째(_rawDetail). */
@Component
public class DetailMapper {

    private final ObjectMapper om;

    public DetailMapper(ObjectMapper om) {
        this.om = om;
    }

    /** HikerAPI /v2/media/info/by/code 미디어 객체. */
    public Map<String, Object> fromHikerMedia(String json) {
        JsonNode root = read(json);
        JsonNode m = root.has("media") ? root.path("media") : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("shortCode", m.path("code").asString(null));
        p.put("caption", m.path("caption_text").asString(m.path("caption").path("text").asString(null)));
        p.put("likesCount", m.path("like_count").asLong());
        p.put("commentsCount", m.path("comment_count").asLong());
        p.put("videoPlayCount", m.path("play_count").asLong());
        p.put("isPaidPartnership", m.path("is_paid_partnership").asBoolean(false));
        p.put("_rawDetail", raw(root));
        return p;
    }

    /** self-crawl GraphQL 포스트 쿼리(data.xdt_shortcode_media). */
    public Map<String, Object> fromSelfGraphql(String json) {
        JsonNode root = read(json);
        JsonNode media = root.has("data") ? root.path("data").path("xdt_shortcode_media") : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("shortCode", media.path("shortcode").asString(null));
        p.put("caption", media.path("edge_media_to_caption").path("edges").path(0)
                .path("node").path("text").asString(null));
        p.put("likesCount", media.path("edge_media_preview_like").path("count").asLong());
        p.put("commentsCount", media.path("edge_media_to_comment").path("count").asLong());
        p.put("videoPlayCount", null);   // 피드=조회수 없음
        p.put("isPaidPartnership", media.path("is_paid_partnership").asBoolean(false));
        p.put("_rawDetail", raw(root));
        return p;
    }

    /** Apify 상세 액터 아이템 — 이미 하드계약 키(shortCode/caption/likesCount/…) 보유, 그대로 통과. */
    public Map<String, Object> fromActorItem(Map<String, Object> item) {
        return item;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("상세 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private Object raw(JsonNode node) {
        return om.convertValue(node, Object.class);
    }
}
```

- [ ] **Step 5: Run test — 통과 확인** → PASS

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/DetailFetcher.java \
        src/main/java/com/celfit/crawler/crawling/application/service/DetailMapper.java \
        src/test/java/com/celfit/crawler/crawling/application/service/DetailMapperTest.java
git commit -m "feat: DetailFetcher 포트 + DetailMapper(소스별 정규화 + _rawDetail)"
```

---

## Task 3: HikerReelDetailFetcher (HIKER / 릴스)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerReelDetailFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerReelDetailFetcherTest.java`

**Interfaces:**
- Consumes: `HikerHttp.get(String path)`; `CrawlExecutor.execute(JobName, TriggerType, Long, String, String label, Supplier<ApifyResult>)`; `DetailMapper.fromHikerMedia`; `ApifyResult(String, List)`; `DetailFetcher`.
- Produces: `HikerReelDetailFetcher implements DetailFetcher` (source=HIKER, supports REELS). 라벨 `detail-hiker-reels`. per-item skip.

- [ ] **Step 1: Write the failing test**

`HikerReelDetailFetcherTest.java` (CrawlExecutor 없이 collect 로직만 검증하려면 실제 CrawlExecutor가 필요 → 여기선 fetch를 fake HikerHttp + 실제 매퍼로 돌리되, CrawlExecutor는 실물 대신 아이템만 확인하도록 `execute` 결과의 items를 검사. CrawlExecutor는 순수 로직이라 fake runner/repo가 번거로우므로, **collect 결과를 직접 노출하는 package-private 헬퍼**를 테스트한다):
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerReelDetailFetcherTest {

    ObjectMapper om = new ObjectMapper();
    DetailMapper mapper = new DetailMapper(om);

    @Test void 각_shortCode마다_media_info_호출하고_정규화() {
        HikerHttp http = path -> "{\"code\":\"" + path.substring(path.indexOf("code=") + 5)
                + "\",\"like_count\":10,\"comment_count\":2,\"play_count\":100}";
        var f = new HikerReelDetailFetcher(http, null, mapper);
        List<Map<String, Object>> out = f.collect(List.of("AA", "BB"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsEntry("shortCode", "AA").containsEntry("videoPlayCount", 100L);
    }

    @Test void 한_shortCode_실패해도_나머지_보존() {
        HikerHttp http = path -> {
            if (path.contains("code=BAD")) throw new ApifyException("Hiker HTTP 404");
            return "{\"code\":\"OK\",\"like_count\":1}";
        };
        var f = new HikerReelDetailFetcher(http, null, mapper);
        List<Map<String, Object>> out = f.collect(List.of("BAD", "OK"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "OK");
    }
}
```

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: 구현 작성**

`HikerReelDetailFetcher.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** HikerAPI 릴스 상세 — shortCode별 /v2/media/info/by/code 단건 호출(per-item skip). */
@Component
public class HikerReelDetailFetcher implements DetailFetcher {

    static final String LABEL = "detail-hiker-reels";
    private static final Logger log = LoggerFactory.getLogger(HikerReelDetailFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final DetailMapper mapper;

    public HikerReelDetailFetcher(HikerHttp http, CrawlExecutor executor, DetailMapper mapper) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        return executor.execute(JobName.AGGREGATE, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(shortCodes)));
    }

    List<Map<String, Object>> collect(List<String> shortCodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sc : shortCodes) {
            try {
                Map<String, Object> d = mapper.fromHikerMedia(http.get("/v2/media/info/by/code?code=" + sc));
                if (d.get("shortCode") != null) out.add(d);
            } catch (ApifyException e) {
                log.warn("릴스 상세 실패, 스킵: {} ({})", sc, e.getMessage());
            }
        }
        return out;
    }

    @Override public DetailSource source() { return DetailSource.HIKER; }

    @Override public boolean supports(ContentType type) { return type == ContentType.REELS; }
}
```

- [ ] **Step 4: Run test — 통과 확인** → PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/HikerReelDetailFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerReelDetailFetcherTest.java
git commit -m "feat: HikerReelDetailFetcher (릴스 상세 = HikerAPI media/info, per-item skip)"
```

---

## Task 4: SelfFeedDetailFetcher (SELF / 피드) + 설정

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/DirectDetailProperties.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/SelfFeedDetailFetcher.java`
- Modify: `src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java` (`@EnableConfigurationProperties`에 `DirectDetailProperties.class` 추가)
- Modify: `src/main/resources/application.yml` (`crawler.direct-detail`)
- Modify: `src/test/resources/application.yml` (더미 `crawler.direct-detail`)
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/SelfFeedDetailFetcherTest.java`

**Interfaces:**
- Consumes: `InstagramWebClient`(`Response get(String)`, `Response post(String url, String body, Map<String,String> headers)`, `Response.status()/.body()`); `HandshakeExtractor.lsdFrom(String html)`; `DetailMapper.fromSelfGraphql`; `ShortCodes.postUrl(String)`; `CrawlExecutor`; `ApifyResult`; tools.jackson `ObjectMapper`; `DirectDetailProperties`.
- Produces: `SelfFeedDetailFetcher implements DetailFetcher` (source=SELF, supports FEED). 라벨 `detail-self-feed`. 청크당 부트스트랩 페이지 GET 1회로 lsd 확보 후 shortCode별 GraphQL POST(doc_id). per-item skip.

> **doc_id 주의:** self 피드 GraphQL 포스트 쿼리 `doc_id`(및 `variables` 정확한 키)는 환경변수 `IG_POST_DOC_ID`로 주입한다(댓글의 `IG_COMMENT_DOC_ID`와 동일 방식). 구현은 `variables={"shortcode": <code>}`를 기본 가정으로 하고, **정확한 값은 Task 9 수동 스모크에서 실측 cURL로 확정**한다(댓글 `DirectCommentFetcher`도 동일하게 스모크에서 확정). 미설정이면 fetch가 실패해 청크가 재시도로 흡수된다.

- [ ] **Step 1: Write the failing test**

`SelfFeedDetailFetcherTest.java` (fake InstagramWebClient로 부트스트랩+POST 흐름과 per-item skip 검증):
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfFeedDetailFetcherTest {

    ObjectMapper om = new ObjectMapper();
    DetailMapper mapper = new DetailMapper(om);

    /** get은 부트스트랩 페이지(lsd 포함 HTML), post는 shortcode별 graphql. */
    InstagramWebClient fakeWeb(java.util.function.Function<String, InstagramWebClient.Response> post) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                return new Response(200, "<script>\"LSD\",[],{\"token\":\"lsd-abc\"}</script>", Map.of());
            }
            @Override public Response post(String url, String body, Map<String, String> headers) {
                return post.apply(body);
            }
        };
    }

    @Test void 부트스트랩_후_shortCode별_graphql_정규화() {
        var web = fakeWeb(body -> new InstagramWebClient.Response(200,
                "{\"data\":{\"xdt_shortcode_media\":{\"shortcode\":\"SC1\",\"edge_media_preview_like\":{\"count\":9}}}}",
                Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        List<Map<String, Object>> out = f.collect(List.of("SC1"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "SC1").containsEntry("likesCount", 9L);
    }

    @Test void 한_shortCode_500이어도_나머지_보존() {
        var web = fakeWeb(body -> body.contains("BAD")
                ? new InstagramWebClient.Response(500, "", Map.of())
                : new InstagramWebClient.Response(200,
                    "{\"data\":{\"xdt_shortcode_media\":{\"shortcode\":\"OK\"}}}", Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        List<Map<String, Object>> out = f.collect(List.of("BAD", "OK"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "OK");
    }
}
```

> `HandshakeExtractor.lsdFrom`의 실제 파싱 앵커는 기존 구현을 따른다. 위 fake HTML이 `lsdFrom`에서 토큰을 못 뽑으면, 기존 `HandshakeExtractor`/`DirectCommentFetcher` 테스트의 픽스처 HTML 형태를 그대로 복사해 사용한다(실측 픽스처는 `src/test/resources` 또는 기존 테스트에 존재).

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: DirectDetailProperties 작성**

`DirectDetailProperties.java`:
```java
package com.celfit.crawler.crawling.adapter.out.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.direct-detail")
public record DirectDetailProperties(String postDocId, String postFriendlyName, Duration pageDelay) {}
```

- [ ] **Step 4: SelfFeedDetailFetcher 작성**

`SelfFeedDetailFetcher.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.adapter.out.instagram.DirectDetailProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 비로그인 GraphQL 자체 피드 상세. 청크당 lsd 1회 부트스트랩 후 shortCode별 POST(per-item skip). */
@Component
public class SelfFeedDetailFetcher implements DetailFetcher {

    static final String LABEL = "detail-self-feed";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";
    private static final Logger log = LoggerFactory.getLogger(SelfFeedDetailFetcher.class);

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final DetailMapper mapper;
    private final Duration pageDelay;
    private final String docId;
    private final String friendlyName;
    private final ObjectMapper om;

    @Autowired
    public SelfFeedDetailFetcher(InstagramWebClient web, CrawlExecutor executor, DetailMapper mapper,
                                 DirectDetailProperties props, ObjectMapper om) {
        this(web, executor, mapper, props.pageDelay(), props.postDocId(), props.postFriendlyName(), om);
    }

    SelfFeedDetailFetcher(InstagramWebClient web, CrawlExecutor executor, DetailMapper mapper,
                          Duration pageDelay, String docId, String friendlyName, ObjectMapper om) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
        this.docId = docId;
        this.friendlyName = friendlyName;
        this.om = om;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        return executor.execute(JobName.AGGREGATE, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(shortCodes)));
    }

    List<Map<String, Object>> collect(List<String> shortCodes) {
        if (shortCodes.isEmpty()) return List.of();
        var pageResp = web.get(ShortCodes.postUrl(shortCodes.get(0)));   // 부트스트랩: lsd 1회
        if (pageResp.status() >= 300) throw new ApifyException("부트스트랩 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String sc : shortCodes) {
            try {
                var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, sc),
                        Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
                if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
                Map<String, Object> d = mapper.fromSelfGraphql(resp.body());
                if (d.get("shortCode") != null) out.add(d);
            } catch (ApifyException e) {
                log.warn("피드 상세 실패, 스킵: {} ({})", sc, e.getMessage());
            }
            sleep();
        }
        return out;
    }

    private String graphqlBody(String lsd, String shortCode) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("shortcode", shortCode);
        String varsJson;
        try {
            varsJson = om.writeValueAsString(vars);
        } catch (JacksonException e) {
            throw new ApifyException("variables 직렬화 실패", e);
        }
        return "lsd=" + enc(lsd)
                + "&fb_api_req_friendly_name=" + enc(friendlyName)
                + "&doc_id=" + enc(docId)
                + "&variables=" + enc(varsJson);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private void sleep() {
        try {
            if (pageDelay != null && !pageDelay.isZero()) Thread.sleep(pageDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("중단됨", e);
        }
    }

    @Override public DetailSource source() { return DetailSource.SELF; }

    @Override public boolean supports(ContentType type) { return type == ContentType.FEED; }
}
```

- [ ] **Step 5: CrawlerConfig 등록**

`CrawlerConfig.java`의 `@EnableConfigurationProperties({...})`에 `DirectDetailProperties.class` 추가하고, 상단에 import 추가:
```java
import com.celfit.crawler.crawling.adapter.out.instagram.DirectDetailProperties;
```
```java
@EnableConfigurationProperties({ApifyProperties.class, DiscoverProperties.class,
        AggregateProperties.class, ScheduleProperties.class, DirectCommentProperties.class,
        HikerProperties.class, DirectDetailProperties.class})
```

- [ ] **Step 6: application.yml 추가**

`src/main/resources/application.yml`의 `crawler:` 아래(예: `direct-comment:` 블록 뒤)에:
```yaml
  direct-detail:
    post-doc-id: ${IG_POST_DOC_ID:}
    post-friendly-name: ${IG_POST_FRIENDLY_NAME:PolarisPostActionLoadPostQueryQuery}
    page-delay: 1s
```

- [ ] **Step 7: 테스트 yml 더미 추가**

`src/test/resources/application.yml`의 `crawler:` 아래:
```yaml
  direct-detail:
    post-doc-id: test-doc
    post-friendly-name: TestPostQuery
    page-delay: 1ms
```

- [ ] **Step 8: Run test — 통과 확인**

`./gradlew test --tests "*SelfFeedDetailFetcherTest*"` → PASS. (부트스트랩 HTML이 `lsdFrom`에서 실패하면 기존 `HandshakeExtractor` 테스트 픽스처로 교체.)

- [ ] **Step 9: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/DirectDetailProperties.java \
        src/main/java/com/celfit/crawler/crawling/application/service/SelfFeedDetailFetcher.java \
        src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java \
        src/main/resources/application.yml src/test/resources/application.yml \
        src/test/java/com/celfit/crawler/crawling/application/service/SelfFeedDetailFetcherTest.java
git commit -m "feat: SelfFeedDetailFetcher (피드 상세 self-crawl GraphQL, doc_id 설정)"
```

---

## Task 5: ActorDetailFetcher (ACTOR / 양타입)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ActorDetailFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ActorDetailFetcherTest.java`

**Interfaces:**
- Consumes: `Actors.DETAIL_REELS`/`DETAIL_FEED`; `ActorInputs.detailUrls(List<String>)`; `ShortCodes.reelUrl(String)`/`postUrl(String)`; `CrawlExecutor.execute(JobName, TriggerType, Long, String, String actorId, Map input)`; `DetailFetcher`.
- Produces: `ActorDetailFetcher implements DetailFetcher` (source=ACTOR, supports 양타입). 타입별 액터+URL 선택.

- [ ] **Step 1: Write the failing test**

`ActorDetailFetcherTest.java` (타입별 URL/액터 선택을 검증. `CrawlExecutor`를 실물로 쓰기 번거로우므로, URL/액터 선택 로직을 package-private 헬퍼로 노출해 검증):
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.Actors;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActorDetailFetcherTest {

    ActorDetailFetcher f = new ActorDetailFetcher(null);

    @Test void 릴스는_reel액터_reelUrl() {
        assertThat(f.actorFor(ContentType.REELS)).isEqualTo(Actors.DETAIL_REELS);
        assertThat(f.urlsFor(List.of("ABC"), ContentType.REELS).get(0)).contains("/reel/ABC");
    }

    @Test void 피드는_post액터_postUrl() {
        assertThat(f.actorFor(ContentType.FEED)).isEqualTo(Actors.DETAIL_FEED);
        assertThat(f.urlsFor(List.of("ABC"), ContentType.FEED).get(0)).contains("/p/ABC");
    }

    @Test void 양타입_지원_source_ACTOR() {
        assertThat(f.supports(ContentType.REELS)).isTrue();
        assertThat(f.supports(ContentType.FEED)).isTrue();
        assertThat(f.source().name()).isEqualTo("ACTOR");
    }
}
```

> `ShortCodes.reelUrl`/`postUrl`이 만드는 실제 경로(`/reel/<sc>/` vs `/p/<sc>/`)는 기존 구현을 따른다. 위 `contains` 앵커가 다르면 실제 반환값에 맞춰 조정.

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: 구현 작성**

`ActorDetailFetcher.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 Apify 상세 액터 래핑 — 타입별 액터/URL 선택. crawl_run 라벨은 액터 id(현행 유지). */
@Component
public class ActorDetailFetcher implements DetailFetcher {

    private final CrawlExecutor executor;

    public ActorDetailFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        String actor = actorFor(type);
        return executor.execute(JobName.AGGREGATE, trigger, null, null, actor,
                ActorInputs.detailUrls(urlsFor(shortCodes, type)));
    }

    String actorFor(ContentType type) {
        return type == ContentType.REELS ? Actors.DETAIL_REELS : Actors.DETAIL_FEED;
    }

    List<String> urlsFor(List<String> shortCodes, ContentType type) {
        return shortCodes.stream()
                .map(sc -> type == ContentType.REELS ? ShortCodes.reelUrl(sc) : ShortCodes.postUrl(sc))
                .toList();
    }

    @Override public DetailSource source() { return DetailSource.ACTOR; }

    @Override public boolean supports(ContentType type) { return true; }
}
```

- [ ] **Step 4: Run test — 통과 확인** → PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/ActorDetailFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ActorDetailFetcherTest.java
git commit -m "feat: ActorDetailFetcher (기존 Apify 상세 액터 래핑, 타입별 선택)"
```

---

## Task 6: DetailSourceSelector

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/DetailSourceSelector.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/DetailSourceSelectorTest.java`

**Interfaces:**
- Consumes: `List<DetailFetcher>`(source()/supports()/fetch()); `DetailSourceSetting.sourceFor(ContentType)`; `ContentType`.
- Produces: `DetailSourceSelector.forType(ContentType) -> DetailFetcher` (설정 소스가 그 타입 미지원 시 ACTOR 폴백).

- [ ] **Step 1: Write the failing test**

`DetailSourceSelectorTest.java`:
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.application.service.DetailSourceSettingTest;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailSourceSelectorTest {

    /** source/supports만 다른 스텁 fetcher. */
    static DetailFetcher stub(DetailSource src, ContentType supported) {
        return new DetailFetcher() {
            @Override public CrawlExecutor.Execution fetch(List<String> s, ContentType t, TriggerType tr) { return null; }
            @Override public DetailSource source() { return src; }
            @Override public boolean supports(ContentType t) { return t == supported || src == DetailSource.ACTOR; }
        };
    }

    DetailSourceSetting settingWith(String reels, String feed) {
        var store = new HashMap<String, String>();
        if (reels != null) store.put("detail.reels.source", reels);
        if (feed != null) store.put("detail.feed.source", feed);
        return new DetailSourceSetting(DetailSourceSettingTest.fakeRepo(store));
    }

    @Test void 기본_릴스는_HIKER_피드는_SELF_선택() {
        var hiker = stub(DetailSource.HIKER, ContentType.REELS);
        var self = stub(DetailSource.SELF, ContentType.FEED);
        var actor = stub(DetailSource.ACTOR, ContentType.REELS);
        var sel = new DetailSourceSelector(List.of(hiker, self, actor), settingWith(null, null));
        assertThat(sel.forType(ContentType.REELS)).isSameAs(hiker);
        assertThat(sel.forType(ContentType.FEED)).isSameAs(self);
    }

    @Test void 설정이_ACTOR면_ACTOR_선택() {
        var hiker = stub(DetailSource.HIKER, ContentType.REELS);
        var self = stub(DetailSource.SELF, ContentType.FEED);
        var actor = stub(DetailSource.ACTOR, ContentType.REELS);
        var sel = new DetailSourceSelector(List.of(hiker, self, actor), settingWith("ACTOR", "ACTOR"));
        assertThat(sel.forType(ContentType.REELS)).isSameAs(actor);
        assertThat(sel.forType(ContentType.FEED)).isSameAs(actor);
    }
}
```

> `DetailSourceSettingTest.fakeRepo`를 재사용하므로 Task 1의 테스트에서 `fakeRepo`를 **package-private static**으로 노출해 둔다(이미 위 코드가 static). 접근이 안 되면 셀렉터 테스트에 동일 fake를 복제.

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: 구현 작성**

`DetailSourceSelector.java`:
```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** detail.<type>.source 설정으로 타입별 상세 fetcher 선택. 미지원/미존재 시 ACTOR 폴백. */
@Service
public class DetailSourceSelector {

    private final List<DetailFetcher> fetchers;
    private final DetailSourceSetting setting;

    public DetailSourceSelector(List<DetailFetcher> fetchers, DetailSourceSetting setting) {
        this.fetchers = fetchers;
        this.setting = setting;
    }

    public DetailFetcher forType(ContentType type) {
        DetailSource src = setting.sourceFor(type);
        return pick(type, src).orElseGet(() -> pick(type, DetailSource.ACTOR).orElseThrow(
                () -> new IllegalStateException("상세 fetcher 없음: " + type)));
    }

    private Optional<DetailFetcher> pick(ContentType type, DetailSource src) {
        return fetchers.stream().filter(f -> f.source() == src && f.supports(type)).findFirst();
    }
}
```

- [ ] **Step 4: Run test — 통과 확인** → PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/DetailSourceSelector.java \
        src/test/java/com/celfit/crawler/crawling/application/service/DetailSourceSelectorTest.java
git commit -m "feat: DetailSourceSelector (타입별 소스 선택 + ACTOR 폴백)"
```

---

## Task 7: AggregateJob 배선 교체

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/AggregateJob.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/AggregateJobDetailRoutingTest.java`

**Interfaces:**
- Consumes: `DetailSourceSelector.forType(ContentType).fetch(List<String> shortCodes, ContentType, TriggerType)`.
- Produces: `AggregateJob`가 상세 fetch를 셀렉터 경유로. 나머지 동작 불변.

**변경 내용 (aggregateChunk):**
- 생성자에 `DetailSourceSelector detailSource` 주입(필드 추가).
- `aggregateChunk`에서 `List<String> detailUrls`·`String detailActor` 지역변수와 `executor.execute(...detailActor, ActorInputs.detailUrls(...))` 제거.
- 상단에서 `List<String> shortCodes = chunk.stream().map(Content::getShortCode).toList();`를 한 번 계산해 상세·댓글 양쪽에 재사용.
- 상세 호출을 다음으로 교체:
```java
CrawlExecutor.Execution dx = detailSource.forType(type).fetch(shortCodes, type, trigger);
```
- `import ...Actors;`·`ActorInputs.detailUrls` 참조가 이 파일에서 사라지면 해당 import 제거(단 `ActorInputs.chunk`는 `run()`에서 계속 사용하므로 `ActorInputs` import 유지, `Actors` import는 다른 참조 없으면 제거). `ShortCodes` import는 더 이상 상세 URL 생성에 안 쓰이면 제거(파일 내 다른 사용 확인).

- [ ] **Step 1: Write the failing test**

`AggregateJobDetailRoutingTest.java` (셀렉터가 타입별로 호출되는지 — `AggregateJob` 통합은 무거우므로, 여기선 `DetailSourceSelector.forType`이 REELS/FEED에 대해 각각 올바른 fetcher를 주는지 이미 Task 6에서 검증됨. 이 태스크는 **AggregateJob이 `executor.execute(...detailActor...)`를 더 이상 직접 호출하지 않고 `detailSource.forType(...).fetch(...)`를 호출**하도록 배선됐는지 컴파일+기존 AggregateJob 테스트 그린으로 확인). 기존 AggregateJob 테스트가 있으면 그 테스트가 `DetailSourceSelector`를 목으로 받도록 갱신한다. 없으면 최소 배선 스모크:
```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.junit.jupiter.api.Test;

class AggregateJobDetailRoutingTest {

    @Test void aggregateChunk가_타입별_셀렉터를_경유한다() {
        // AggregateJob 협력자 목 구성은 기존 AggregateJob 테스트의 세팅을 재사용.
        // 핵심 검증: detailSource.forType(REELS) 가 호출되고 그 fetcher.fetch가 호출된다.
        DetailSourceSelector detailSource = mock(DetailSourceSelector.class);
        DetailFetcher fetcher = mock(DetailFetcher.class);
        when(detailSource.forType(any())).thenReturn(fetcher);
        when(fetcher.fetch(any(), any(), any()))
                .thenReturn(new CrawlExecutor.Execution(1L, java.util.List.of()));
        // ... (기존 AggregateJob 생성자 나머지 협력자 목 주입) ...
        // AggregateJob job = new AggregateJob(contents, rawDetails, rawComments, executor,
        //         settings, clock, commentSource, progress, detailSource);
        // job.run(TriggerType.MANUAL);
        // verify(detailSource, atLeastOnce()).forType(ContentType.REELS 또는 FEED);
        assertThat(detailSource).isNotNull();  // 배선 컴파일 확인용 자리표시 — 아래 verify로 교체
    }
}
```

> **구현자 노트:** 이 파일의 정확한 테스트는 **기존 `AggregateJob` 테스트 파일**(있으면)을 열어 협력자 목 세팅을 그대로 가져와 `DetailSourceSelector` 목을 추가하고, `run()` 후 `verify(detailSource).forType(...)`·`verify(fetcher).fetch(...)`로 교체한다. 기존 AggregateJob 테스트가 없다면, 이 태스크는 **컴파일 + 전체 스위트 그린**으로 배선을 검증하고 위 자리표시 대신 `forType/fetch` 호출을 검증하는 최소 목 테스트를 완성한다.

- [ ] **Step 2: Run test/컴파일 — 실패 확인** (AggregateJob 생성자 시그니처 불일치)

- [ ] **Step 3: AggregateJob 수정**

생성자에 `DetailSourceSelector detailSource` 필드·파라미터 추가:
```java
private final DetailSourceSelector detailSource;

public AggregateJob(ContentRepository contents, RawPostDetailRepository rawDetails,
                    RawCommentRepository rawComments, CrawlExecutor executor,
                    SettingsService settings, Clock clock, CommentSourceSelector commentSource,
                    JobProgress progress, DetailSourceSelector detailSource) {
    // ... 기존 대입 ...
    this.detailSource = detailSource;
}
```
`aggregateChunk` 앞부분 교체:
```java
private ChunkResult aggregateChunk(List<Content> chunk, ContentType type, TriggerType trigger) {
    List<String> shortCodes = chunk.stream().map(Content::getShortCode).toList();

    Map<String, Map<String, Object>> detailByCode;
    Map<String, List<Map<String, Object>>> commentsByCode;
    Long detailRunId;
    Long commentRunId;
    try {
        CrawlExecutor.Execution dx = detailSource.forType(type).fetch(shortCodes, type, trigger);
        detailRunId = dx.runId();
        detailByCode = indexDetails(dx.items());
        if (detailByCode.isEmpty() && !chunk.isEmpty()) {
            int f = bumpAttempts(chunk);
            return new ChunkResult(0, 0, chunk.size() - f, f);
        }
        var cx = commentSource.current().fetch(shortCodes, settings.commentsPerPost(), trigger);
        commentRunId = cx.runId();
        commentsByCode = groupComments(cx.items());
    } catch (ApifyException e) {
        int f = bumpAttempts(chunk);
        return new ChunkResult(0, 0, chunk.size() - f, f);
    }
    // ... 이하(raw 저장·adMark·AGGREGATED) 불변 ...
}
```
`ActorInputs`(chunk용)은 유지, `Actors`·`ShortCodes` import는 파일 내 다른 참조 없으면 제거. `import com.celfit.crawler.crawling.application.service.DetailSourceSelector;`는 동일 패키지라 불필요.

- [ ] **Step 4: Run — 전체 스위트 그린 확인**

`./gradlew test` → BUILD SUCCESSFUL (배선 후 기존 AggregateJob 테스트/신규 라우팅 테스트 통과).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/AggregateJob.java \
        src/test/java/com/celfit/crawler/crawling/application/service/AggregateJobDetailRoutingTest.java
git commit -m "feat: AggregateJob 상세 수집을 DetailSourceSelector 경유로 — 동작 불변"
```

---

## Task 8: UI 카드 + 컨트롤러

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/DetailSourceUiController.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiSettingsController.java`
- Modify: `src/main/resources/templates/settings.html`
- Test: `src/test/java/com/celfit/crawler/crawling/adapter/in/web/DetailSourceUiControllerTest.java`

**Interfaces:**
- Consumes: `DetailSourceSetting.update(DetailSource reels, DetailSource feed)`, `sourceFor(ContentType)`.
- Produces: `POST /ui/detail-source` (params `reels`, `feed`) → 설정 저장 후 redirect. settings.html에 상세 카드.

- [ ] **Step 1: Write the failing test**

`DetailSourceUiControllerTest.java`:
```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.application.service.DetailSourceSettingTest;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class DetailSourceUiControllerTest {

    @Test void 폼_저장이_타입별_소스를_반영() {
        var store = new HashMap<String, String>();
        var setting = new DetailSourceSetting(DetailSourceSettingTest.fakeRepo(store));
        var ctrl = new DetailSourceUiController(setting);
        String view = ctrl.update("actor", "actor");
        assertThat(view).isEqualTo("redirect:/ui/settings");
        assertThat(setting.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.ACTOR);
        assertThat(setting.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.ACTOR);
    }
}
```

- [ ] **Step 2: Run test — 실패 확인** → 컴파일 실패

- [ ] **Step 3: DetailSourceUiController 작성**

`DetailSourceUiController.java`:
```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DetailSourceUiController {

    private final DetailSourceSetting setting;

    public DetailSourceUiController(DetailSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/detail-source")
    public String update(@RequestParam String reels, @RequestParam String feed) {
        setting.update(DetailSource.valueOf(reels.toUpperCase(Locale.ROOT)),
                DetailSource.valueOf(feed.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}
```

- [ ] **Step 4: UiSettingsController에 모델 속성 추가**

생성자에 `DetailSourceSetting detailSourceSetting` 주입(필드·파라미터 추가), `page(Model)`에:
```java
model.addAttribute("detailReelsSource", detailSourceSetting.sourceFor(ContentType.REELS).name());
model.addAttribute("detailFeedSource", detailSourceSetting.sourceFor(ContentType.FEED).name());
```
상단 import: `import com.celfit.crawler.content.domain.ContentType;`, `import com.celfit.crawler.settings.application.service.DetailSourceSetting;`.

- [ ] **Step 5: settings.html 상세 카드 추가**

프로필 카드 `</section>`(현재 파일 라인 ~45) **바로 뒤, "빈칸으로 저장하면…" 힌트 앞**에:
```html
<section class="card">
    <h2>상세 수집 방식</h2>
    <form method="post" th:action="@{/ui/detail-source}">
        <p class="hint">릴스:</p>
        <label class="check"><input type="radio" name="reels" value="HIKER"
            th:checked="${detailReelsSource == 'HIKER'}"/> HikerAPI (기본·실조회수)</label>
        <label class="check"><input type="radio" name="reels" value="ACTOR"
            th:checked="${detailReelsSource == 'ACTOR'}"/> 액터 (Apify)</label>
        <hr/>
        <p class="hint">피드:</p>
        <label class="check"><input type="radio" name="feed" value="SELF"
            th:checked="${detailFeedSource == 'SELF'}"/> 자체 크롤 (기본·프록시)</label>
        <label class="check"><input type="radio" name="feed" value="ACTOR"
            th:checked="${detailFeedSource == 'ACTOR'}"/> 액터 (Apify)</label>
        <button type="submit" class="primary">저장</button>
    </form>
    <p class="hint">현재: 릴스 <b th:text="${detailReelsSource}">HIKER</b> · 피드 <b th:text="${detailFeedSource}">SELF</b></p>
</section>
```

- [ ] **Step 6: Run test — 통과 확인** → PASS. 이어 전체 스위트 그린: `./gradlew test`.

- [ ] **Step 7: Commit**
```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/in/web/DetailSourceUiController.java \
        src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiSettingsController.java \
        src/main/resources/templates/settings.html \
        src/test/java/com/celfit/crawler/crawling/adapter/in/web/DetailSourceUiControllerTest.java
git commit -m "feat: 상세 소스 선택 UI 카드 + 컨트롤러"
```

---

## Task 9: 수동 스모크 · doc_id 확보 (사용자)

**Files:** 없음(수동). `~/Desktop/hiker_vs_self/detail_*` 샘플 대조.

- [ ] **Step 1: self 피드 doc_id 실측**
  - 로그인 없이 브라우저/`curl`로 포스트 페이지를 열어 GraphQL 포스트 쿼리의 `doc_id`·`fb_api_req_friendly_name`·`variables` 키를 네트워크 탭에서 확인(댓글 doc_id 확보와 동일 절차).
  - IntelliJ Run Config 환경변수에 `IG_POST_DOC_ID`(및 필요 시 `IG_POST_FRIENDLY_NAME`) 설정. 프록시(`APIFY_PROXY_URL`)·HikerAPI 키(`HIKER_API_KEY`)도 설정.
  - `variables`가 `{"shortcode": …}`가 아니면 `SelfFeedDetailFetcher.graphqlBody`의 vars 키를 실측에 맞게 수정하고 테스트 갱신.

- [ ] **Step 2: 앱 실행(이 폴더 hypenow-detail 기준) 후 릴스1·피드1 집계**
  - `/ui/settings`에서 릴스=HIKER·피드=SELF 확인, QUALIFIED·due 콘텐츠로 AGGREGATE 트리거.
  - `raw_post_detail`에 릴스는 `video_play_count` 실값, 피드는 caption·likes·comments 채워졌는지 확인. `detail-hiker-reels`/`detail-self-feed` crawl_run 행 확인.

- [ ] **Step 3: 소스 토글·ACTOR 폴백 확인**
  - 릴스를 ACTOR로 바꿔 저장 → 다음 집계에서 `apify~instagram-reel-scraper` 행이 뜨는지.

- [ ] **Step 4: `~/Desktop/hiker_vs_self` 대조**
  - 저장된 `detail_REELS_HIKERAPI.json`·`detail_FEED_SELFCRAWL.json`과 payload 구조/조회수 일치 확인.

---

## Self-Review

**1. Spec coverage:**
- 소스 모델(타입별 토글·기본값·enum) → Task 1. ✓
- DetailFetcher 포트·DetailMapper(하드계약 키·_rawDetail·isPaidPartnership) → Task 2. ✓
- HikerReel/SelfFeed/Actor 3구현 → Task 3/4/5. ✓
- DetailSourceSelector.forType+폴백 → Task 6. ✓
- AggregateJob 배선(한 줄 교체) → Task 7. ✓
- UI 카드(프로필 카드 뒤)·컨트롤러 → Task 8. ✓
- 신규 설정 `crawler.direct-detail`(post-doc-id) → Task 4. ✓
- crawl_run 기록·라벨 → Global Constraints + Task 3/4/5 라벨. ✓
- 에러(per-item skip·청크 재시도·키/doc_id 미설정·튜플 방어) → Task 3/4 + Global Constraints. ✓
- 수동 스모크·doc_id 확보 → Task 9. ✓

**2. Placeholder scan:** 코드 스텝은 전부 실제 코드. Task 4 doc_id·Task 9는 "구현 정찰서 실측"이 명시적 non-goal/스모크 항목(플레이스홀더 아님, 코드는 configurable로 완성). Task 7 테스트는 "기존 AggregateJob 테스트 세팅 재사용" 지시 — 구현자가 기존 테스트를 열어 목 구성. Task 1의 `fakeRepo`는 기존 `ProfileSourceSettingTest` 패턴 확인 지시.

**3. Type consistency:** `DetailFetcher.fetch(List<String>, ContentType, TriggerType)`·`source()`·`supports()` 전 태스크 일관. `DetailMapper.fromHikerMedia/fromSelfGraphql/fromActorItem` 일관. `DetailSourceSetting.sourceFor(ContentType)`·`update(DetailSource,DetailSource)` 일관. 라벨 상수 `detail-hiker-reels`·`detail-self-feed` 일관. `_rawDetail` 키 일관.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-11-detail-source-selector.md`. 두 실행 옵션:

1. **Subagent-Driven (추천)** — 태스크마다 새 서브에이전트 + 태스크별 리뷰 + 최종 전체 리뷰
2. **Inline** — 이 세션에서 직접 순차 실행

어느 방식으로 갈까요?
