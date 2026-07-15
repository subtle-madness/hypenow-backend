# 프로필 소스 셀렉터 + HikerAPI 보충 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로필(Qualify) 단계의 데이터 소스를 런타임에 선택 가능하게 만든다 — 베이스 4종(SELF/ACTOR/HIKER_MOBILE/HIKER_WEB_GQL) + 독립 HikerAPI 보충 2종(posts/related).

**Architecture:** 기존 `CommentFetcher` 토글 패턴(port + enum + setting + selector)을 `ProfileFetcher`로 복제하고, 부족한 베이스(SELF·HIKER_MOBILE)에만 적용되는 `ProfileSupplementer` 레이어를 추가한다. HikerAPI는 `ApifyHttp`를 미러링한 `HikerHttp`(x-access-key)로 호출한다. 모든 소스 응답은 `ProfileMapper`가 `raw_profile` payload 계약(top-level `username`+`followersCount`)으로 정규화한다.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Postgres(jsonb + generated columns), JDK HttpClient, tools.jackson(Jackson 3) ObjectMapper, JUnit 5.

## Global Constraints

- 패키지 루트: `com.celfit.crawler`. 소스: `src/main/java/...`, 테스트: `src/test/java/...` (동일 패키지 미러).
- `raw_profile` generated column 계약: payload에 top-level `username`(String), `followersCount`(숫자, `::bigint` 캐스팅됨) **반드시** 포함. 문자열 아닌 숫자여야 함(비숫자면 INSERT 실패).
- ObjectMapper는 **tools.jackson (Jackson 3)** 빈 주입 — `com.fasterxml.jackson` 아님. `JsonNode`, `om.readTree(...)`, 파싱 실패 시 `ApifyException` 던짐 (CommentMapper와 동일).
- 모든 크롤 실행은 `CrawlExecutor.execute(...)` 를 통해 `crawl_run`+`raw_run_item` 기록. 커스텀 크롤은 Supplier 오버로드 + `new ApifyResult(null, items)`.
- Spring 빈은 `@Component`/`@Service`. 셀렉터는 `List<ProfileFetcher>` 자동 수집.
- HikerAPI STANDARD 티어 가정. 키는 env `HIKER_API_KEY`.
- app_setting 테이블: 키·값 모두 String. enum은 `.name()` 저장, bool은 `"true"`/`"false"` 저장.

---

### Task 1: ProfileSource enum + 설정 서비스 2종

**Files:**
- Create: `src/main/java/com/celfit/crawler/settings/domain/ProfileSource.java`
- Create: `src/main/java/com/celfit/crawler/settings/application/service/ProfileSourceSetting.java`
- Create: `src/main/java/com/celfit/crawler/settings/application/service/ProfileSupplementSetting.java`
- Test: `src/test/java/com/celfit/crawler/settings/application/service/ProfileSourceSettingTest.java`
- Test: `src/test/java/com/celfit/crawler/settings/application/service/ProfileSupplementSettingTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository` (`extends JpaRepository<AppSetting,String>`), `AppSetting(String key,String value)`.
- Produces: `ProfileSource{SELF,ACTOR,HIKER_MOBILE,HIKER_WEB_GQL}`; `ProfileSourceSetting.current():ProfileSource`, `.update(ProfileSource)`, `.updateRaw(String)`; `ProfileSupplementSetting.postsEnabled():boolean`, `.relatedEnabled():boolean`, `.update(boolean posts, boolean related)`.

- [ ] **Step 1: enum 작성**

```java
package com.celfit.crawler.settings.domain;

public enum ProfileSource { SELF, ACTOR, HIKER_MOBILE, HIKER_WEB_GQL }
```

- [ ] **Step 2: ProfileSourceSetting 실패 테스트 작성** — `src/test/.../ProfileSourceSettingTest.java`

```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileSourceSettingTest {

    // AppSettingRepository의 최소 fake (findById/save만 사용)
    static AppSettingRepository fakeRepo(Map<String, String> store) {
        return new AppSettingRepository() {
            @Override public Optional<AppSetting> findById(String k) {
                return Optional.ofNullable(store.get(k)).map(v -> new AppSetting(k, v));
            }
            @Override public <S extends AppSetting> S save(S e) { store.put(e.getKey(), e.getValue()); return e; }
            // 나머지 JpaRepository 메서드는 이 테스트에서 미사용 → default 예외
            @Override public java.util.List<AppSetting> findAll() { throw new UnsupportedOperationException(); }
            @Override public java.util.List<AppSetting> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> saveAll(Iterable<S> es) { throw new UnsupportedOperationException(); }
            @Override public boolean existsById(String id) { return store.containsKey(id); }
            @Override public long count() { return store.size(); }
            @Override public void deleteById(String id) { store.remove(id); }
            @Override public void delete(AppSetting e) { store.remove(e.getKey()); }
            @Override public void deleteAllById(Iterable<? extends String> ids) { ids.forEach(store::remove); }
            @Override public void deleteAll(Iterable<? extends AppSetting> es) { es.forEach(this::delete); }
            @Override public void deleteAll() { store.clear(); }
            @Override public java.util.List<AppSetting> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
            @Override public org.springframework.data.domain.Page<AppSetting> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public void flush() {}
            @Override public <S extends AppSetting> S saveAndFlush(S e) { return save(e); }
            @Override public <S extends AppSetting> java.util.List<S> saveAllAndFlush(Iterable<S> es) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch(Iterable<AppSetting> es) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch() { store.clear(); }
            @Override public AppSetting getReferenceById(String id) { throw new UnsupportedOperationException(); }
            @Override public AppSetting getOne(String id) { throw new UnsupportedOperationException(); }
            @Override public AppSetting getById(String id) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
            @Override public <S extends AppSetting> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
            @Override public <S extends AppSetting, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        };
    }

    @Test void 기본값은_SELF() {
        var setting = new ProfileSourceSetting(fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(ProfileSource.SELF);
    }

    @Test void 저장한_값을_읽는다() {
        var setting = new ProfileSourceSetting(fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.HIKER_MOBILE);
        assertThat(setting.current()).isEqualTo(ProfileSource.HIKER_MOBILE);
    }

    @Test void 이상한_값이면_SELF로_폴백() {
        var store = new HashMap<String, String>();
        var setting = new ProfileSourceSetting(fakeRepo(store));
        setting.updateRaw("GARBAGE");
        assertThat(setting.current()).isEqualTo(ProfileSource.SELF);
    }
}
```

> Note: fake가 장황하면, 이 프로젝트에 이미 있는 테스트 헬퍼/모킹 방식이 있는지 먼저 확인하고(예: 기존 `CommentSourceSetting` 테스트가 있으면 그 fake/mock 방식을 그대로 재사용). 있으면 그 방식으로 대체하라. 없으면 위 fake 사용.

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*ProfileSourceSettingTest'`
Expected: FAIL (컴파일 에러 — `ProfileSourceSetting` 없음)

- [ ] **Step 4: ProfileSourceSetting 구현** (CommentSourceSetting 미러)

```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 수집 소스 토글. app_setting 키 profile.source, 없거나 이상하면 SELF. */
@Service
public class ProfileSourceSetting {

    static final String KEY = "profile.source";

    private final AppSettingRepository settings;

    public ProfileSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProfileSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(ProfileSource.SELF);
    }

    @Transactional
    public void update(ProfileSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private ProfileSource parse(String value) {
        try {
            return ProfileSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ProfileSource.SELF;
        }
    }
}
```

- [ ] **Step 5: ProfileSupplementSetting 테스트 작성** — 같은 fakeRepo 헬퍼 사용

```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ProfileSupplementSettingTest {
    @Test void 기본값은_둘다_false() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(s.postsEnabled()).isFalse();
        assertThat(s.relatedEnabled()).isFalse();
    }
    @Test void 개별_토글이_독립적으로_저장된다() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(true, false);
        assertThat(s.postsEnabled()).isTrue();
        assertThat(s.relatedEnabled()).isFalse();
    }
}
```

- [ ] **Step 6: 테스트 실패 확인**

Run: `./gradlew test --tests '*ProfileSupplementSettingTest'`
Expected: FAIL (`ProfileSupplementSetting` 없음)

- [ ] **Step 7: ProfileSupplementSetting 구현**

```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 보충(HikerAPI 추가 호출) on/off. 키 profile.supplement.posts / .related. */
@Service
public class ProfileSupplementSetting {

    static final String POSTS = "profile.supplement.posts";
    static final String RELATED = "profile.supplement.related";

    private final AppSettingRepository settings;

    public ProfileSupplementSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public boolean postsEnabled() { return read(POSTS); }

    @Transactional(readOnly = true)
    public boolean relatedEnabled() { return read(RELATED); }

    @Transactional
    public void update(boolean posts, boolean related) {
        settings.save(new AppSetting(POSTS, Boolean.toString(posts)));
        settings.save(new AppSetting(RELATED, Boolean.toString(related)));
    }

    private boolean read(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(Boolean::parseBoolean).orElse(false);
    }
}
```

- [ ] **Step 8: 테스트 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ProfileSourceSettingTest' --tests '*ProfileSupplementSettingTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/settings src/test/java/com/celfit/crawler/settings
git commit -m "feat: ProfileSource enum + 프로필 소스/보충 설정 서비스"
```

---

### Task 2: ProfileFetcher 포트 + ProfileMapper (정규화)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/ProfileFetcher.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ProfileMapper.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ProfileMapperTest.java`

**Interfaces:**
- Consumes: `CrawlExecutor.Execution`, `TriggerType`, `ProfileSource`, tools.jackson `ObjectMapper`.
- Produces:
  - `ProfileFetcher.fetch(List<String> usernames, TriggerType trigger):CrawlExecutor.Execution`, `.source():ProfileSource`.
  - `ProfileMapper.fromSelf(String json):Map<String,Object>` — web_profile_info(GraphQL) 단건 → `{username, followersCount, userId, ...}`.
  - `ProfileMapper.fromHikerUser(String json):Map<String,Object>` — HikerAPI v2/user/by/username 또는 gql web_profile_info 응답 → `{username, followersCount, userId, ...}`.
  - `ProfileMapper.fromActorItem(Map<String,Object> item):Map<String,Object>` — Apify 아이템 정규화(이미 username/followersCount 있음, userId 보강).
  - 각 반환 맵은 **top-level `username`(String), `followersCount`(Long), `userId`(String)** 보장.

- [ ] **Step 1: ProfileFetcher 포트 작성**

```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;

/** 계정 여러 개의 프로필 수집. 전체를 crawl_run 1건으로 감싼다. items[i]에는 최소 username·followersCount·userId 포함. */
public interface ProfileFetcher {
    CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger);
    ProfileSource source();
}
```

- [ ] **Step 2: ProfileMapper 실패 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfileMapperTest {

    ProfileMapper mapper = new ProfileMapper(new ObjectMapper());

    @Test void self_graphql_정규화() {
        String json = """
            {"data":{"user":{"username":"beauty.e.ze","id":"74851841915",
              "edge_followed_by":{"count":2369}}}}""";
        Map<String, Object> p = mapper.fromSelf(json);
        assertThat(p.get("username")).isEqualTo("beauty.e.ze");
        assertThat(p.get("followersCount")).isEqualTo(2369L);
        assertThat(p.get("userId")).isEqualTo("74851841915");
    }

    @Test void hiker_user_정규화() {
        String json = """
            {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        Map<String, Object> p = mapper.fromHikerUser(json);
        assertThat(p.get("username")).isEqualTo("tem.duck");
        assertThat(p.get("followersCount")).isEqualTo(256559L);
        assertThat(p.get("userId")).isEqualTo("74756186520");
    }

    @Test void actor_아이템_보강() {
        Map<String, Object> item = new java.util.HashMap<>(Map.of(
            "username", "tem.duck", "followersCount", 256169, "id", "74756186520"));
        Map<String, Object> p = mapper.fromActorItem(item);
        assertThat(p.get("username")).isEqualTo("tem.duck");
        assertThat(p.get("followersCount")).isEqualTo(256169L);
        assertThat(p.get("userId")).isEqualTo("74756186520");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*ProfileMapperTest'`
Expected: FAIL (`ProfileMapper` 없음)

- [ ] **Step 4: ProfileMapper 구현** (CommentMapper 스타일: tools.jackson, ApifyException)

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 소스별 프로필 응답을 raw_profile payload 계약(username·followersCount·userId)으로 정규화. */
@Component
public class ProfileMapper {

    private final ObjectMapper om;

    public ProfileMapper(ObjectMapper om) {
        this.om = om;
    }

    /** self-crawl web_profile_info(GraphQL) 단건. */
    public Map<String, Object> fromSelf(String json) {
        JsonNode user = read(json).path("data").path("user");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("username", user.path("username").asString(null));
        p.put("userId", user.path("id").asString(null));
        p.put("followersCount", user.path("edge_followed_by").path("count").asLong());
        p.put("followsCount", user.path("edge_follow").path("count").asLong());
        p.put("fullName", user.path("full_name").asString(null));
        p.put("biography", user.path("biography").asString(null));
        p.put("verified", user.path("is_verified").asBoolean(false));
        p.put("private", user.path("is_private").asBoolean(false));
        return p;
    }

    /** HikerAPI v2/user/by/username 또는 gql/web_profile_info(모바일 user 객체). */
    public Map<String, Object> fromHikerUser(String json) {
        JsonNode root = read(json);
        JsonNode user = root.has("user") ? root.path("user") : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("username", user.path("username").asString(null));
        p.put("userId", user.path("pk").asString(user.path("id").asString(null)));
        p.put("followersCount", user.path("follower_count").asLong());
        p.put("followsCount", user.path("following_count").asLong());
        p.put("fullName", user.path("full_name").asString(null));
        p.put("biography", user.path("biography").asString(null));
        p.put("verified", user.path("is_verified").asBoolean(false));
        p.put("private", user.path("is_private").asBoolean(false));
        return p;
    }

    /** Apify 프로필 액터 아이템 — 이미 username/followersCount 존재, userId 보강 + Long 정규화. */
    public Map<String, Object> fromActorItem(Map<String, Object> item) {
        Map<String, Object> p = new LinkedHashMap<>(item);
        p.put("username", item.get("username"));
        p.put("followersCount", toLong(item.get("followersCount")));
        Object uid = item.get("id");
        if (uid != null) p.put("userId", String.valueOf(uid));
        return p;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("프로필 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) return Long.parseLong(s);
        return null;
    }
}
```

> `JsonNode.asString(default)` / `asLong()` 은 tools.jackson(Jackson 3) API다. 만약 이 프로젝트의 tools.jackson 버전에서 `asString` 시그니처가 다르면(예: `asText`), 기존 `CommentMapper.java`가 쓰는 접근 메서드를 그대로 따라라(같은 ObjectMapper·버전).

- [ ] **Step 5: 테스트 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ProfileMapperTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/ProfileFetcher.java \
        src/main/java/com/celfit/crawler/crawling/application/service/ProfileMapper.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ProfileMapperTest.java
git commit -m "feat: ProfileFetcher 포트 + ProfileMapper 정규화"
```

---

### Task 3: HikerProperties + HikerHttp/JdkHikerHttp (HikerAPI HTTP 어댑터)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/hiker/HikerHttp.java`
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/hiker/JdkHikerHttp.java`
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/hiker/HikerProperties.java`

**Interfaces:**
- Consumes: `ApifyException` (재사용, 실패 표현).
- Produces: `HikerHttp.get(String path):String` (path는 `/v2/user/by/username?username=x` 형태, base-url·헤더는 구현체가 붙임); `HikerProperties(String apiKey,String baseUrl,Duration requestTimeout)`.

> HTTP 어댑터는 실제 네트워크라 단위테스트 없음(ApifyHttp도 없음). 인터페이스로 분리해 상위 fetcher 테스트에서 fake로 대체한다. 이 태스크는 컴파일만 확인.

- [ ] **Step 1: HikerProperties**

```java
package com.celfit.crawler.crawling.adapter.out.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout) {}
```

- [ ] **Step 2: HikerHttp 포트**

```java
package com.celfit.crawler.crawling.adapter.out.hiker;

/** HikerAPI HTTP 전송 격리 — 테스트에서 fake로 대체. path는 base-url 이후 부분(쿼리 포함). */
public interface HikerHttp {
    String get(String path);
}
```

- [ ] **Step 3: JdkHikerHttp 구현** (JdkApifyHttp 미러, x-access-key)

```java
package com.celfit.crawler.crawling.adapter.out.hiker;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
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
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("HIKER_API_KEY가 설정되지 않았습니다 (환경변수 필요)");
        }
        this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
        this.apiKey = props.apiKey();
        this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
    }

    @Override
    public String get(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-access-key", apiKey)
                .header("accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new ApifyException("Hiker HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new ApifyException("Hiker 요청 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Hiker 요청 중단", e);
        }
    }
}
```

- [ ] **Step 4: 컴파일 확인 + 커밋**

Run: `./gradlew compileJava`
Expected: SUCCESS

```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/out/hiker
git commit -m "feat: HikerHttp 어댑터 + HikerProperties (x-access-key)"
```

---

### Task 4: application.yml + CrawlerConfig 등록

**Files:**
- Modify: `src/main/resources/application.yml` (crawler 블록에 hiker 추가)
- Modify: `src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java:@EnableConfigurationProperties`
- Modify: `src/test/resources/application.yml` (있으면 동일 추가 — 없으면 스킵)

**Interfaces:**
- Produces: `crawler.hiker.*` 프로퍼티 바인딩 활성.

- [ ] **Step 1: application.yml에 추가** — `crawler:` 아래(`apify:` 형제로)

```yaml
  hiker:
    api-key: ${HIKER_API_KEY:}
    base-url: https://api.hikerapi.com
    request-timeout: 15s
```

- [ ] **Step 2: CrawlerConfig에 HikerProperties 등록** — import 추가 후 리스트에 `HikerProperties.class` 추가

```java
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
// ...
@EnableConfigurationProperties({ApifyProperties.class, DiscoverProperties.class,
        AggregateProperties.class, ScheduleProperties.class, DirectCommentProperties.class,
        HikerProperties.class})
```

- [ ] **Step 3: 앱 컨텍스트 로드 확인 + 커밋**

Run: `./gradlew compileJava` (그리고 가능하면 `./gradlew test --tests '*ApplicationTests'` 등 컨텍스트 로드 스모크가 있으면 실행)
Expected: SUCCESS (HIKER_API_KEY 비어도 바인딩만 되므로 로드 OK — `JdkHikerHttp` 빈은 Task 5+에서 쓰일 때 키 검증)

> 주의: `JdkHikerHttp`가 `@Component`라 키 없으면 컨텍스트 로드 시 `IllegalStateException` 날 수 있다. 만약 테스트 컨텍스트가 깨지면, 테스트 `application.yml`에 `crawler.hiker.api-key: test-key` 더미를 넣어라(APIFY_TOKEN을 테스트에서 더미로 넣는 기존 방식과 동일).

```bash
git add src/main/resources/application.yml src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java
git commit -m "feat: crawler.hiker 설정 + CrawlerConfig 등록"
```

---

### Task 5: ActorProfileFetcher (기존 Apify 경로 래핑)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ActorProfileFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ActorProfileFetcherTest.java`

**Interfaces:**
- Consumes: `CrawlExecutor` (Map 오버로드), `Actors.PROFILE`, `ActorInputs.profiles(List)`, `ProfileMapper.fromActorItem`.
- Produces: `ProfileFetcher` 구현, `source()=ACTOR`.

- [ ] **Step 1: 실패 테스트 작성** (CrawlExecutor를 mockito로 스텁)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorProfileFetcherTest {

    @Test void source는_ACTOR() {
        var f = new ActorProfileFetcher(mock(CrawlExecutor.class), new ProfileMapper(new tools.jackson.databind.ObjectMapper()));
        assertThat(f.source()).isEqualTo(ProfileSource.ACTOR);
    }

    @Test void 액터결과를_정규화해_반환() {
        CrawlExecutor exec = mock(CrawlExecutor.class);
        var raw = new CrawlExecutor.Execution(7L, List.of(
            new java.util.HashMap<>(Map.of("username","tem.duck","followersCount",256169,"id","74756186520"))));
        when(exec.execute(any(), any(), any(), any(), any(), any(Map.class))).thenReturn(raw);

        var f = new ActorProfileFetcher(exec, new ProfileMapper(new tools.jackson.databind.ObjectMapper()));
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(256169L);
        assertThat(ex.items().get(0).get("userId")).isEqualTo("74756186520");
    }
}
```

> `TriggerType.MANUAL` 값이 실제 enum에 없으면 존재하는 값(예: `SCHEDULED`)으로 교체. `when(exec.execute(...,any(Map.class)))` 오버로드 매칭이 모호하면 `org.mockito.Mockito.doReturn(raw).when(exec).execute(...)` 형태로.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ActorProfileFetcherTest'`
Expected: FAIL (`ActorProfileFetcher` 없음)

- [ ] **Step 3: 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ActorProfileFetcher implements ProfileFetcher {

    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public ActorProfileFetcher(CrawlExecutor executor, ProfileMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        CrawlExecutor.Execution raw = executor.execute(JobName.QUALIFY, trigger, null, null,
                Actors.PROFILE, ActorInputs.profiles(usernames));
        List<Map<String, Object>> mapped = raw.items().stream().map(mapper::fromActorItem).toList();
        return new CrawlExecutor.Execution(raw.runId(), mapped);
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.ACTOR;
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ActorProfileFetcherTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/ActorProfileFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ActorProfileFetcherTest.java
git commit -m "feat: ActorProfileFetcher (Apify 프로필 경로 래핑)"
```

---

### Task 6: SelfProfileFetcher (web_profile_info 자체크롤)

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcherTest.java`

**Interfaces:**
- Consumes: `InstagramWebClient` (self-crawl, `Response get(String)`), `CrawlExecutor` (Supplier 오버로드), `ProfileMapper.fromSelf`, `ApifyResult(null, items)`.
- Produces: `ProfileFetcher` 구현, `source()=SELF`.

- [ ] **Step 1: 실패 테스트 작성** (InstagramWebClient·CrawlExecutor fake)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SelfProfileFetcherTest {

    // CrawlExecutor의 Supplier 오버로드만 흉내내는 최소 스텁 (spy 대신 서브클래스)
    static CrawlExecutor passthroughExecutor() {
        return new CrawlExecutor(null, null, null, null) {
            @Override public Execution execute(com.celfit.crawler.crawling.domain.JobName job,
                    TriggerType t, Long c, String k, String actorId, Supplier<ApifyResult> work) {
                ApifyResult r = work.get();
                return new Execution(1L, r.items());
            }
        };
    }

    @Test void source는_SELF() {
        var f = new SelfProfileFetcher(url -> null, passthroughExecutor(),
                new ProfileMapper(new tools.jackson.databind.ObjectMapper()), java.time.Duration.ZERO);
        assertThat(f.source()).isEqualTo(ProfileSource.SELF);
    }

    @Test void 각_username마다_web_profile_info_호출후_정규화() {
        InstagramWebClient web = url -> new InstagramWebClient.Response(200,
            """
            {"data":{"user":{"username":"beauty.e.ze","id":"74851841915","edge_followed_by":{"count":2369}}}}""",
            Map.of());
        var f = new SelfProfileFetcher(web, passthroughExecutor(),
                new ProfileMapper(new tools.jackson.databind.ObjectMapper()), java.time.Duration.ZERO);

        var ex = f.fetch(List.of("beauty.e.ze"), TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(1);
        assertThat(ex.items().get(0).get("username")).isEqualTo("beauty.e.ze");
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(2369L);
    }
}
```

> `InstagramWebClient`가 함수형 인터페이스가 아니면(메서드 2개) 람다 대신 익명 클래스로. `CrawlExecutor` 서브클래싱이 막히면(생성자/파이널) — 대신 mockito로 Supplier 오버로드를 스텁하라: `when(exec.execute(any(),any(),any(),any(),any(),any(Supplier.class))).thenAnswer(inv -> { var w=(Supplier<ApifyResult>)inv.getArgument(5); var r=w.get(); return new CrawlExecutor.Execution(1L,r.items());});`. 실제 존재하는 `CrawlExecutor` 생성자/파이널 여부를 먼저 확인해 방식 택일.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*SelfProfileFetcherTest'`
Expected: FAIL

- [ ] **Step 3: 구현** (web_profile_info URL + x-ig-app-id는 InstagramWebClient가 헤더 처리하지 않으면 URL만; 앱-id 헤더는 self client가 이미 붙이는지 확인 후 필요시 client에 위임)

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SelfProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self";
    private static final String URL =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;
    private final Duration pageDelay;

    public SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor,
                              ProfileMapper mapper, Duration pageDelay) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        return executor.execute(JobName.QUALIFY, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            InstagramWebClient.Response res = web.get(URL + u);
            if (res.status() == 200) {
                Map<String, Object> p = mapper.fromSelf(res.body());
                if (p.get("username") != null) out.add(p);
            }
            sleep();
        }
        return out;
    }

    private void sleep() {
        if (pageDelay == null || pageDelay.isZero()) return;
        try { Thread.sleep(pageDelay.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.SELF;
    }
}
```

> `web_profile_info`는 `x-ig-app-id: 936619743392459` 헤더가 필요하다. `InstagramWebClient.get(String)`이 이 헤더를 붙이지 않으면(댓글 self-crawl은 post에서만 헤더 지정) 두 가지 중 택: (a) `JdkInstagramWebClient.get`에 기본 헤더로 x-ig-app-id 추가(안전, 로그아웃 GET 공통), (b) 포트에 헤더 인자 get 오버로드 추가. 실측상 web_profile_info는 쿠키 없이도 이 헤더만으로 200 → (a) 권장. 구현 전 `JdkInstagramWebClient.get` 확인해 x-ig-app-id 없으면 추가하고 이 태스크에 포함.
> 생성자 `pageDelay`는 Spring 주입을 위해 Task 12에서 `@Value` 또는 별도 프로퍼티로 배선 — 우선 여기선 `DirectCommentProperties.pageDelay()`를 재사용하도록 Task 12에서 조정. 지금은 Duration 파라미터로 두고 테스트는 `Duration.ZERO` 주입.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*SelfProfileFetcherTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcherTest.java \
        src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/JdkInstagramWebClient.java
git commit -m "feat: SelfProfileFetcher (web_profile_info 자체크롤)"
```

---

### Task 7: HikerMobileProfileFetcher + HikerWebGqlProfileFetcher

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerMobileProfileFetcher.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerWebGqlProfileFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerProfileFetchersTest.java`

**Interfaces:**
- Consumes: `HikerHttp.get(String path)`, `CrawlExecutor` (Supplier), `ProfileMapper.fromHikerUser`, `ApifyResult`.
- Produces: 두 `ProfileFetcher` 구현, `source()=HIKER_MOBILE` / `HIKER_WEB_GQL`. Mobile은 username별 `/v2/user/by/username?username=`; WebGql은 pk 필요 → username으로 by/username 먼저 조회해 pk 얻은 뒤 `/gql/user/web_profile_info?user_id=` (WebGql 500 시 해당 username 스킵).

- [ ] **Step 1: 실패 테스트 작성** (HikerHttp fake — path별 응답)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class HikerProfileFetchersTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    ProfileMapper mapper = new ProfileMapper(new tools.jackson.databind.ObjectMapper());

    @Test void mobile_username별_조회_정규화() {
        HikerHttp http = path -> {
            assertThat(path).contains("/v2/user/by/username");
            return """
                {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        };
        var f = new HikerMobileProfileFetcher(http, passthrough(), mapper);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_MOBILE);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(256559L);
        assertThat(ex.items().get(0).get("userId")).isEqualTo("74756186520");
    }

    @Test void webgql_500이면_해당_계정_스킵() {
        HikerHttp http = path -> {
            if (path.contains("/v2/user/by/username")) {
                return """
                    {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
            }
            throw new com.celfit.crawler.crawling.adapter.out.apify.ApifyException("Hiker HTTP 500");
        };
        var f = new HikerWebGqlProfileFetcher(http, passthrough(), mapper);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_WEB_GQL);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        assertThat(ex.items()).isEmpty();  // 500 → 스킵, 예외 전파 안 함
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*HikerProfileFetchersTest'`
Expected: FAIL

- [ ] **Step 3: HikerMobileProfileFetcher 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HikerMobileProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-mobile";

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public HikerMobileProfileFetcher(HikerHttp http, CrawlExecutor executor, ProfileMapper mapper) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        return executor.execute(JobName.QUALIFY, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
            Map<String, Object> p = mapper.fromHikerUser(http.get("/v2/user/by/username?username=" + enc));
            if (p.get("username") != null) out.add(p);
        }
        return out;
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_MOBILE;
    }
}
```

- [ ] **Step 4: HikerWebGqlProfileFetcher 구현** (username→pk→gql, 500 스킵)

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HikerWebGqlProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-webgql";
    private static final Logger log = LoggerFactory.getLogger(HikerWebGqlProfileFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public HikerWebGqlProfileFetcher(HikerHttp http, CrawlExecutor executor, ProfileMapper mapper) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        return executor.execute(JobName.QUALIFY, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            try {
                String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
                Map<String, Object> base = mapper.fromHikerUser(http.get("/v2/user/by/username?username=" + enc));
                Object uid = base.get("userId");
                if (uid == null) { out.add(base); continue; }
                // 웹 gql로 재조회(게시물·related 번들). 500 등 실패 시 모바일 base로 폴백.
                Map<String, Object> gql = mapper.fromHikerUser(http.get("/gql/user/web_profile_info?user_id=" + uid));
                out.add(gql.get("username") != null ? gql : base);
            } catch (ApifyException e) {
                log.warn("web_profile_info 실패, 계정 스킵: {} ({})", u, e.getMessage());
                // 스킵 → deferred 로직이 다음 실행에 재시도
            }
        }
        return out;
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_WEB_GQL;
    }
}
```

> WebGql 테스트는 "500이면 items 비어야" 이므로, 위 구현에서 by/username은 성공하고 gql만 500이면 `catch`가 아니라 base 폴백으로 items가 1개가 된다. 테스트 의도(500→스킵)와 맞추려면 **정책 확정 필요**: (A) gql 실패 시 모바일 base 폴백(권장, 데이터 보존) vs (B) gql 실패 시 완전 스킵. 스펙상 WebGql은 "게시물+related 번들"이 목적이므로 base 폴백이면 목적 상실 → **(B) 완전 스킵**으로 통일한다. 구현 수정: `try{ base 조회; gql 조회 }`를 하나의 try로 묶고 어느 쪽이든 실패하면 스킵(위 코드가 이미 그럼 — 단 gql 성공 전 base는 버림). 테스트가 초록이 되도록 catch에서 아무것도 add하지 않게 유지. 위 코드는 gql 실패 시 `catch`로 빠지며 base를 add하지 않으므로 **테스트 통과**. (base 폴백 라인 `out.add(base)`는 uid==null일 때만.)

- [ ] **Step 5: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*HikerProfileFetchersTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/HikerMobileProfileFetcher.java \
        src/main/java/com/celfit/crawler/crawling/application/service/HikerWebGqlProfileFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerProfileFetchersTest.java
git commit -m "feat: HikerMobile/HikerWebGql 프로필 페처 (웹gql 실패 스킵)"
```

---

### Task 8: 보충 2종 + ProfileSupplementer

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerMediasSupplement.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerSuggestedSupplement.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ProfileSupplementer.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ProfileSupplementerTest.java`

**Interfaces:**
- Consumes: `HikerHttp`, `ProfileSupplementSetting`, tools.jackson `ObjectMapper`, `ProfileSource`, `CrawlExecutor.Execution`.
- Produces:
  - `HikerMediasSupplement.enrich(Map<String,Object> item):void` — item의 `userId`로 `/v1/user/medias/chunk?user_id=` 호출, item에 `latestPosts`(List) 넣음.
  - `HikerSuggestedSupplement.enrich(Map<String,Object> item):void` — `/v2/user/suggested/profiles?user_id=&expand_suggestion=true` 호출, item에 `relatedProfiles`(List) 넣음.
  - `ProfileSupplementer.apply(Execution ex, ProfileSource source):Execution` — 부족 베이스(SELF·HIKER_MOBILE)일 때만, 설정별로 각 보충을 **독립 try/catch**로 적용.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfileSupplementerTest {

    ObjectMapper om = new ObjectMapper();

    ProfileSupplementSetting settingBoth() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(true, true); return s;
    }

    CrawlExecutor.Execution oneItem() {
        Map<String, Object> item = new HashMap<>(Map.of("username","tem.duck","followersCount",1L,"userId","999"));
        return new CrawlExecutor.Execution(1L, List.of(item));
    }

    @Test void ACTOR는_보충_안함() {
        HikerHttp http = p -> { throw new AssertionError("호출되면 안됨"); };
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.ACTOR);
        assertThat(ex.items().get(0)).doesNotContainKey("latestPosts");
    }

    @Test void SELF_둘다_보충() {
        HikerHttp http = path -> path.contains("medias")
            ? "{\"response\":{\"items\":[{\"code\":\"X\",\"play_count\":10}]}}"
            : "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.SELF);
        assertThat(ex.items().get(0)).containsKeys("latestPosts","relatedProfiles");
    }

    @Test void 한_보충_실패해도_나머지와_베이스는_보존() {
        HikerHttp http = path -> {
            if (path.contains("medias")) throw new ApifyException("Hiker HTTP 500");
            return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        };
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.HIKER_MOBILE);
        Map<String, Object> item = ex.items().get(0);
        assertThat(item).doesNotContainKey("latestPosts");     // medias 실패 → 없음
        assertThat(item).containsKey("relatedProfiles");        // related 성공
        assertThat(item.get("username")).isEqualTo("tem.duck"); // 베이스 보존
    }
}
```

> 보충 응답 파싱 경로(`response.items`, `users`)는 실제 HikerAPI 응답 형태에 맞춰라. 저장된 샘플 `~/Desktop/hiker_vs_self/` 및 세션 scratchpad의 `sug.json`(suggested), medias 응답을 참고해 실제 키로 조정(테스트 JSON도 실제형태로 교체).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ProfileSupplementerTest'`
Expected: FAIL

- [ ] **Step 3: HikerMediasSupplement 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HikerAPI /v1/user/medias/chunk → item에 latestPosts(각 code·play_count·like_count 등) 병합. */
@Component
public class HikerMediasSupplement {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerMediasSupplement(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    public void enrich(Map<String, Object> item) {
        Object uid = item.get("userId");
        if (uid == null) return;
        String body = http.get("/v1/user/medias/chunk?user_id=" + uid);
        List<Map<String, Object>> posts = new ArrayList<>();
        JsonNode arr = firstArray(read(body));
        for (JsonNode n : arr) {
            JsonNode m = n.has("media") ? n.path("media") : n;
            Map<String, Object> post = new java.util.LinkedHashMap<>();
            post.put("shortCode", m.path("code").asString(null));
            post.put("videoViewCount", m.path("play_count").asLong());
            post.put("likesCount", m.path("like_count").asLong());
            post.put("commentsCount", m.path("comment_count").asLong());
            posts.add(post);
        }
        item.put("latestPosts", posts);
    }

    private JsonNode read(String json) {
        try { return om.readTree(json); }
        catch (JacksonException e) { throw new ApifyException("medias 파싱 실패: " + e.getMessage(), e); }
    }

    // 응답 구조가 {response:{items:[...]}} 또는 {items:[...]} 또는 [...] 등 다양 → 첫 번째 배열을 찾음
    private JsonNode firstArray(JsonNode node) {
        if (node.isArray()) return node;
        for (JsonNode child : node) {
            JsonNode found = firstArray(child);
            if (found.isArray()) return found;
        }
        return om.createArrayNode();
    }
}
```

- [ ] **Step 4: HikerSuggestedSupplement 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.adapter.out.apify.ApifyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HikerAPI /v2/user/suggested/profiles?expand_suggestion=true → item에 relatedProfiles 병합. */
@Component
public class HikerSuggestedSupplement {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerSuggestedSupplement(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    public void enrich(Map<String, Object> item) {
        Object uid = item.get("userId");
        if (uid == null) return;
        String body = http.get("/v2/user/suggested/profiles?user_id=" + uid + "&expand_suggestion=true");
        List<Map<String, Object>> related = new ArrayList<>();
        collectUsers(read(body), related);
        item.put("relatedProfiles", related);
    }

    private void collectUsers(JsonNode node, List<Map<String, Object>> acc) {
        if (node.isObject() && node.has("username") && (node.has("pk") || node.has("id"))) {
            Map<String, Object> u = new java.util.LinkedHashMap<>();
            u.put("username", node.path("username").asString(null));
            u.put("full_name", node.path("full_name").asString(null));
            u.put("is_verified", node.path("is_verified").asBoolean(false));
            acc.add(u);
            return;
        }
        for (JsonNode c : node) collectUsers(c, acc);
    }

    private JsonNode read(String json) {
        try { return om.readTree(json); }
        catch (JacksonException e) { throw new ApifyException("suggested 파싱 실패: " + e.getMessage(), e); }
    }
}
```

- [ ] **Step 5: ProfileSupplementer 구현** (부족 베이스만, 독립 try/catch)

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 부족한 베이스(SELF·HIKER_MOBILE)에만 HikerAPI 보충을 각각 독립 적용. */
@Service
public class ProfileSupplementer {

    private static final Logger log = LoggerFactory.getLogger(ProfileSupplementer.class);
    private static final Set<ProfileSource> DEFICIENT = Set.of(ProfileSource.SELF, ProfileSource.HIKER_MOBILE);

    private final HikerMediasSupplement medias;
    private final HikerSuggestedSupplement suggested;
    private final ProfileSupplementSetting setting;

    public ProfileSupplementer(HikerMediasSupplement medias, HikerSuggestedSupplement suggested,
                               ProfileSupplementSetting setting) {
        this.medias = medias;
        this.suggested = suggested;
        this.setting = setting;
    }

    public CrawlExecutor.Execution apply(CrawlExecutor.Execution ex, ProfileSource source) {
        if (!DEFICIENT.contains(source)) return ex;
        boolean posts = setting.postsEnabled();
        boolean related = setting.relatedEnabled();
        if (!posts && !related) return ex;
        for (var item : ex.items()) {
            if (posts) {
                try { medias.enrich(item); }
                catch (RuntimeException e) { log.warn("posts 보충 실패 {}: {}", item.get("username"), e.getMessage()); }
            }
            if (related) {
                try { suggested.enrich(item); }
                catch (RuntimeException e) { log.warn("related 보충 실패 {}: {}", item.get("username"), e.getMessage()); }
            }
        }
        return ex;
    }
}
```

- [ ] **Step 6: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ProfileSupplementerTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/Hiker*Supplement.java \
        src/main/java/com/celfit/crawler/crawling/application/service/ProfileSupplementer.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ProfileSupplementerTest.java
git commit -m "feat: 프로필 보충 2종(posts/related) + ProfileSupplementer 독립 try/catch"
```

---

### Task 9: ProfileSourceSelector

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ProfileSourceSelector.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ProfileSourceSelectorTest.java`

**Interfaces:**
- Consumes: `List<ProfileFetcher>`, `ProfileSourceSetting`, `ProfileSupplementer`.
- Produces: `ProfileSourceSelector.fetchAndSupplement(List<String> usernames, TriggerType trigger):CrawlExecutor.Execution` — 설정 소스의 fetcher(없으면 SELF 폴백) → 보충 적용.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileSourceSelectorTest {

    static ProfileFetcher fetcher(ProfileSource src, String marker) {
        return new ProfileFetcher() {
            @Override public CrawlExecutor.Execution fetch(List<String> u, TriggerType t) {
                Map<String, Object> item = new HashMap<>(Map.of("username", marker, "followersCount", 1L, "userId", "1"));
                return new CrawlExecutor.Execution(1L, List.of(item));
            }
            @Override public ProfileSource source() { return src; }
        };
    }

    ProfileSupplementer noopSupplementer() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>())); // 둘다 false
        return new ProfileSupplementer(null, null, s); // false라 보충 진입 안 함
    }

    @Test void 설정된_소스의_페처를_고른다() {
        var setting = new ProfileSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.HIKER_MOBILE);
        var sel = new ProfileSourceSelector(
            List.of(fetcher(ProfileSource.SELF, "self"), fetcher(ProfileSource.HIKER_MOBILE, "mobile")),
            setting, noopSupplementer());
        var ex = sel.fetchAndSupplement(List.of("x"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("username")).isEqualTo("mobile");
    }

    @Test void 미등록_소스면_SELF_폴백() {
        var setting = new ProfileSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.ACTOR); // ACTOR 페처 미등록
        var sel = new ProfileSourceSelector(
            List.of(fetcher(ProfileSource.SELF, "self")), setting, noopSupplementer());
        var ex = sel.fetchAndSupplement(List.of("x"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("username")).isEqualTo("self");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ProfileSourceSelectorTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** profile.source 설정으로 베이스 페처 선택(미존재 시 SELF 폴백) 후 보충 적용. */
@Service
public class ProfileSourceSelector {

    private final Map<ProfileSource, ProfileFetcher> bySource;
    private final ProfileSourceSetting setting;
    private final ProfileSupplementer supplementer;

    public ProfileSourceSelector(List<ProfileFetcher> fetchers, ProfileSourceSetting setting,
                                 ProfileSupplementer supplementer) {
        this.bySource = fetchers.stream().collect(Collectors.toMap(ProfileFetcher::source, Function.identity()));
        this.setting = setting;
        this.supplementer = supplementer;
    }

    public CrawlExecutor.Execution fetchAndSupplement(List<String> usernames, TriggerType trigger) {
        ProfileSource src = setting.current();
        ProfileFetcher f = bySource.get(src);
        if (f == null) { f = bySource.get(ProfileSource.SELF); src = ProfileSource.SELF; }
        return supplementer.apply(f.fetch(usernames, trigger), src);
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ProfileSourceSelectorTest'`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/ProfileSourceSelector.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ProfileSourceSelectorTest.java
git commit -m "feat: ProfileSourceSelector (SELF 폴백 + 보충 적용)"
```

---

### Task 10: QualifyJob 배선 교체

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/QualifyJob.java` (생성자 + `profileMissingAccounts`)
- Modify/Create: `src/test/java/com/celfit/crawler/crawling/application/service/QualifyJobTest.java` (기존 있으면 수정)

**Interfaces:**
- Consumes: `ProfileSourceSelector.fetchAndSupplement(names, trigger)`.
- Produces: 변경된 `QualifyJob` — 프로필 수집만 셀렉터 경유. 나머지(자격 판정, followers 읽기, lastProfiledAt) 불변.

- [ ] **Step 1: 기존 QualifyJob 테스트 확인** — `find src/test -name 'QualifyJobTest.java'`. 있으면 그 스타일(가짜 executor/selector 주입) 재사용. 프로필 경로가 `Actors.PROFILE` 대신 셀렉터를 부르도록 기대 수정하는 실패 테스트를 추가/변경.

```java
// QualifyJobTest 내 추가 (기존 셋업 재사용):
@Test void 프로필수집은_ProfileSourceSelector를_사용한다() {
    // given: selector.fetchAndSupplement(...)가 username+followersCount 담긴 item 반환하도록 스텁
    // when: qualify 실행
    // then: rawProfiles에 저장되고 executor.execute(...Actors.PROFILE...)는 호출되지 않음
}
```

> 실제 QualifyJob 테스트가 없으면, 이 태스크의 테스트는 "selector가 주입되고 profileMissingAccounts가 selector를 호출한다"를 mockito로 검증하는 최소 단위테스트로 작성(ContentRepository 등은 mock, targets 1개).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*QualifyJobTest'`
Expected: FAIL

- [ ] **Step 3: QualifyJob 수정** — 생성자에 `ProfileSourceSelector` 추가, `profileMissingAccounts`의 executor 호출부 교체

생성자 필드 추가:
```java
    private final ProfileSourceSelector profileSourceSelector;

    public QualifyJob(ContentRepository contents, AccountRepository accounts,
                      CollectionRuleRepository rules, RawProfileRepository rawProfiles,
                      CrawlExecutor executor, Clock clock,
                      ProfileSourceSelector profileSourceSelector) {
        this.contents = contents;
        this.accounts = accounts;
        this.rules = rules;
        this.rawProfiles = rawProfiles;
        this.executor = executor;
        this.clock = clock;
        this.profileSourceSelector = profileSourceSelector;
    }
```

`profileMissingAccounts` 내부 — 액터 호출 블록 교체(try/catch·매칭·저장 로직 유지):
```java
        List<String> names = chunk.stream().map(Account::getUsername).toList();
        CrawlExecutor.Execution ex;
        try {
            ex = profileSourceSelector.fetchAndSupplement(names, trigger);
        } catch (ApifyException e) {
            continue;  // FAILED 기록됨 — 다음 실행 때 재시도
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
```
(더 이상 쓰지 않으면 `Actors`/`ActorInputs` import 정리. `executor` 필드는 다른 곳에서 쓰면 유지.)

- [ ] **Step 4: 통과 확인 + 전체 회귀**

Run: `./gradlew test`
Expected: PASS (전체 그린)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/QualifyJob.java \
        src/test/java/com/celfit/crawler/crawling/application/service/QualifyJobTest.java
git commit -m "feat: QualifyJob 프로필 수집을 ProfileSourceSelector로 배선"
```

---

### Task 11: UI — 소스 라디오 + 보충 체크박스

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/ProfileSourceUiController.java`
- Modify: `src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java` (model attr 추가)
- Modify: `src/main/resources/templates/settings.html` (카드 추가)

**Interfaces:**
- Consumes: `ProfileSourceSetting`, `ProfileSupplementSetting`.
- Produces: `POST /ui/profile-source` (source + posts + related 저장), 설정 페이지에 프로필 카드.

- [ ] **Step 1: ProfileSourceUiController 작성** (CommentSourceUiController 미러, 보충 포함)

```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ProfileSourceUiController {

    private final ProfileSourceSetting sourceSetting;
    private final ProfileSupplementSetting supplementSetting;

    public ProfileSourceUiController(ProfileSourceSetting sourceSetting,
                                     ProfileSupplementSetting supplementSetting) {
        this.sourceSetting = sourceSetting;
        this.supplementSetting = supplementSetting;
    }

    @PostMapping("/ui/profile-source")
    public String update(@RequestParam String source,
                         @RequestParam(defaultValue = "false") boolean posts,
                         @RequestParam(defaultValue = "false") boolean related) {
        sourceSetting.update(ProfileSource.valueOf(source.toUpperCase(Locale.ROOT)));
        supplementSetting.update(posts, related);
        return "redirect:/ui/settings";
    }
}
```

- [ ] **Step 2: UiSettingsController에 model attr 추가** — 생성자에 두 setting 주입, page()에 attr 추가

```java
model.addAttribute("profileSource", profileSourceSetting.current().name());
model.addAttribute("profilePosts", profileSupplementSetting.postsEnabled());
model.addAttribute("profileRelated", profileSupplementSetting.relatedEnabled());
```
(생성자에 `ProfileSourceSetting profileSourceSetting, ProfileSupplementSetting profileSupplementSetting` 추가.)

- [ ] **Step 3: settings.html에 카드 추가** (댓글 카드 아래)

```html
<section class="card">
    <h2>프로필 수집 방식</h2>
    <form method="post" th:action="@{/ui/profile-source}">
        <label class="check"><input type="radio" name="source" value="SELF"
            th:checked="${profileSource == 'SELF'}"/> 자체 크롤 (기본)</label>
        <label class="check"><input type="radio" name="source" value="ACTOR"
            th:checked="${profileSource == 'ACTOR'}"/> 액터 (Apify)</label>
        <label class="check"><input type="radio" name="source" value="HIKER_MOBILE"
            th:checked="${profileSource == 'HIKER_MOBILE'}"/> HikerAPI 모바일</label>
        <label class="check"><input type="radio" name="source" value="HIKER_WEB_GQL"
            th:checked="${profileSource == 'HIKER_WEB_GQL'}"/> HikerAPI 웹gql</label>
        <hr/>
        <p class="hint">보충 (SELF·HikerAPI모바일에서만 적용):</p>
        <label class="check"><input type="checkbox" name="posts" value="true"
            th:checked="${profilePosts}"/> 게시물 조회수 채우기 (HikerAPI medias)</label>
        <label class="check"><input type="checkbox" name="related" value="true"
            th:checked="${profileRelated}"/> 관련계정 채우기 (HikerAPI suggested)</label>
        <button type="submit" class="primary">저장</button>
    </form>
    <p class="hint">현재: <b th:text="${profileSource}">SELF</b> · 기본값: SELF</p>
</section>
```

- [ ] **Step 4: 컴파일 + (있으면)웹 테스트 + 커밋**

Run: `./gradlew compileJava test`
Expected: PASS

```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/in/web/ProfileSourceUiController.java \
        src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java \
        src/main/resources/templates/settings.html
git commit -m "feat: 프로필 소스/보충 선택 UI"
```

---

### Task 12: SelfProfileFetcher 배선 마무리 + 수동 스모크

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java` (Spring 주입: pageDelay 소스)
- (문서) 수동 스모크 절차

**Interfaces:**
- Produces: `SelfProfileFetcher`가 스프링 컨텍스트에서 완전 배선(생성자 주입 가능).

- [ ] **Step 1: SelfProfileFetcher 생성자 Spring 주입 확정** — `Duration pageDelay`를 `DirectCommentProperties.pageDelay()`에서 주입(기존 프로퍼티 재사용) 또는 `@Value("${crawler.hiker.request-timeout}")`가 아닌 별도. 가장 단순: 생성자에서 `DirectCommentProperties props` 받아 `props.pageDelay()` 사용.

```java
    public SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor, ProfileMapper mapper,
                              com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties props) {
        this(web, executor, mapper, props.pageDelay());
    }

    SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor, ProfileMapper mapper, Duration pageDelay) {
        this.web = web; this.executor = executor; this.mapper = mapper; this.pageDelay = pageDelay;
    }
```
(DirectCommentFetcher의 2-생성자 패턴과 동일 — 테스트는 package-private 생성자 사용.)

- [ ] **Step 2: 전체 테스트 + 앱 컨텍스트 로드 확인**

Run: `./gradlew test`
Expected: PASS (테스트 컨텍스트에 `crawler.hiker.api-key` 더미 있어야 `JdkHikerHttp` 빈 로드 — Task 4 주의 참고)

- [ ] **Step 3: 수동 스모크 (사용자 실행)** — 문서화만, 자동화 아님

절차(README 또는 커밋 메시지에 기록):
1. env `HIKER_API_KEY`, `APIFY_TOKEN`, `APIFY_PROXY_URL` 설정 후 앱 기동.
2. `/ui/settings`에서 프로필 소스를 각각 SELF / HIKER_MOBILE(+보충 on) / ACTOR로 바꿔가며 Qualify 1회 트리거.
3. `raw_profile`에 각 소스별 row 저장 + `followersCount` 채워짐 확인. 보충 on일 때 payload에 `latestPosts`/`relatedProfiles` 존재 확인.
4. 저장된 샘플 `~/Desktop/hiker_vs_self/`와 대조.

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java
git commit -m "feat: SelfProfileFetcher Spring 배선(pageDelay) + 스모크 절차"
```

---

## Self-Review

**Spec coverage:**
- 베이스 4(SELF/ACTOR/HIKER_MOBILE/HIKER_WEB_GQL) → Task 5·6·7 ✅
- 독립 보충 2(posts/related, 독립 try/catch) → Task 8 ✅
- 부족 베이스에만 보충 노출/적용 → Task 8(`DEFICIENT`) + Task 11(UI hint) ✅ (UI는 항상 체크박스 표시하되 적용은 서버가 부족베이스로 제한 — spec의 "노출" 요구는 UX 비활성화가 이상적이나, 최소구현은 서버측 제한 + hint. 필요시 JS 비활성화는 후속.)
- 기본값 SELF → Task 1(`orElse(SELF)`) ✅
- raw_profile 계약(username·followersCount) → Task 2 ProfileMapper + 각 fetcher ✅
- CommentFetcher 패턴 복제(port/enum/setting/selector/UI) → Task 1·2·9·11 ✅
- HikerHttp(x-access-key)·HikerProperties → Task 3·4 ✅
- QualifyJob 배선 → Task 10 ✅
- 웹gql 500 안전 흡수 → Task 7 + Task 10 catch ✅

**미해결/구현자 확인 필요 (플랜 내 명시):**
- tools.jackson 접근 메서드(`asString`/`asText`, `asLong`) 실제 버전 확인 → CommentMapper 방식 따르기(Task 2 note).
- HikerAPI medias/suggested 실제 응답 키 → 저장 샘플로 조정(Task 8 note).
- `InstagramWebClient.get`에 x-ig-app-id 헤더 필요(Task 6 note).
- 기존 QualifyJobTest 유무에 따라 테스트 방식(Task 10 Step 1).
- 테스트 컨텍스트용 `crawler.hiker.api-key` 더미(Task 4 note).

**Type consistency:** `ProfileFetcher.fetch(List<String>,TriggerType)`·`source():ProfileSource`, `CrawlExecutor.Execution(Long,List<Map>)`, `ApifyResult(null,items)`, `ProfileMapper.fromSelf/fromHikerUser/fromActorItem`, `ProfileSupplementer.apply(Execution,ProfileSource)`, `ProfileSourceSelector.fetchAndSupplement(List<String>,TriggerType)` — 태스크 전반 일관.
