# DirectCommentFetcher 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 댓글 수집을 액터/자체크롤 전략으로 분리하고, 비로그인 `/api/graphql` 자체 크롤 구현체를 추가해 UI 토글로 A/B 비교할 수 있게 한다.

**Architecture:** `CommentFetcher` 포트 뒤에 `ActorCommentFetcher`(기존 액터)와 `DirectCommentFetcher`(비로그인 HTTP + Apify Proxy)를 두고, `CommentSourceSelector`가 런타임 설정(`comment.source`)으로 하나를 고른다. 두 경로 모두 `CrawlExecutor`로 `crawl_run`에 기록해 비교가 성립한다. 상세·디스커버리·프로필 액터는 손대지 않는다.

**Tech Stack:** Java 21, Spring Boot, JDK `HttpClient`, PostgreSQL/JPA, JUnit5 + AssertJ, Testcontainers.

## Global Constraints

- **비로그인 원칙**: 인스타 로그인 세션·계정 풀을 쓰지 않는다.
- **범위**: 댓글 수집만 변경. discover/qualify/detail 액터는 무변경.
- **스키마 호환**: 모든 댓글 맵은 `{postUrl, ownerUsername, text, timestamp}` 키를 채운다 (raw_comment 생성 컬럼 `writer`/`text`/`written_at` + AggregateJob `groupComments`의 `postUrl` 그룹핑 호환).
- **실패 통일**: 자체 크롤의 모든 실패(차단·추출 실패·타임아웃)는 `ApifyException`으로 던져 기존 `CrawlExecutor` FAILED 기록 + `AggregateJob.bumpAttempts` 재시도 로직을 재사용한다.
- **테스트 관용구**: 통합 테스트는 `extends IntegrationTest`, `@Transactional`, `@Import` + `@Bean @Primary` fake, AssertJ, 한글 메서드명. 순수 단위 테스트는 프레임워크 없이.
- **모듈 의존 방향**: `crawling` → `settings` (단방향). 토글 설정·enum은 `settings` 모듈에 둔다.
- **커밋**: 태스크마다 커밋. 메시지 끝에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

## File Structure

**신규 생성**
- `settings/domain/CommentSource.java` — enum `{ACTOR, DIRECT}`
- `settings/application/service/CommentSourceSetting.java` — `comment.source` app_setting 읽기/쓰기 (기본 ACTOR)
- `crawling/application/port/out/CommentFetcher.java` — 댓글 수집 포트
- `crawling/application/port/out/InstagramWebClient.java` — 프록시 HTTP 아웃포트
- `crawling/application/service/ActorCommentFetcher.java` — 액터 구현체
- `crawling/application/service/DirectCommentFetcher.java` — 비로그인 자체 크롤 구현체
- `crawling/application/service/CommentSourceSelector.java` — 설정 기반 전략 선택
- `crawling/application/service/HandshakeExtractor.java` — HTML → lsd·doc_id·media_id 순수 파서
- `crawling/application/service/CommentMapper.java` — GraphQL JSON → 댓글 맵 순수 매퍼
- `crawling/adapter/out/instagram/JdkInstagramWebClient.java` — `InstagramWebClient` 실구현(프록시)
- `crawling/adapter/out/instagram/DirectCommentProperties.java` — 프록시·타임아웃 설정
- `crawling/adapter/in/web/CommentSourceUiController.java` — 토글 POST 핸들러
- 테스트 픽스처: `src/test/resources/instagram/post-page.html`, `src/test/resources/instagram/comments-response.json`

**수정**
- `crawling/application/service/CrawlExecutor.java` — `Supplier<ApifyResult>` 오버로드 추가
- `crawling/application/service/AggregateJob.java` — 댓글 호출부를 셀렉터로 교체
- `settings/adapter/in/web/UiSettingsController.java` — 토글 상태를 모델에 추가
- `settings/…/templates/settings.html` (resources) — 토글 카드
- `src/main/resources/application.yml` — `crawler.direct-comment.*` 기본값
- `CrawlerApplication.java` 또는 config — `@ConfigurationPropertiesScan` 확인(신규 properties 등록)

---

## Task 1: CrawlExecutor에 Supplier 오버로드 추가

자체 크롤은 `ApifyRunnerPort.run(actorId, input)`을 통하지 않으므로, 임의의 수집 작업을 crawl_run으로 감쌀 수 있는 오버로드가 필요하다. 기존 시그니처는 이 오버로드에 위임한다(동작 불변).

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/CrawlExecutor.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/CrawlExecutorTest.java`

**Interfaces:**
- Produces: `Execution execute(JobName job, TriggerType trigger, Long categoryId, String keyword, String actorId, Supplier<ApifyResult> work)` — `work`가 `ApifyException`을 던지면 FAILED 기록 후 전파. `ApifyResult.runId()`는 null 허용(자체 크롤은 apify run id 없음).
- 기존 `execute(..., String actorId, Map<String,Object> input)` 유지(위임).

- [ ] **Step 1: 실패하는 테스트 작성** — `CrawlExecutorTest`에 추가:

```java
    @Test
    void supplier_오버로드도_성공하면_SUCCEEDED로_기록되고_아카이브된다() {
        var execution = executor.execute(JobName.AGGREGATE, TriggerType.MANUAL, null, null,
                "direct-comment-crawler",
                () -> new com.celfit.crawler.crawling.application.port.out.ApifyResult(
                        null, List.of(Map.of("text", "좋아요"))));

        assertThat(execution.items()).hasSize(1);
        var run = runs.findById(execution.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getApifyRunId()).isNull();
        assertThat(rawRunItems.countByCrawlRunId(execution.runId())).isEqualTo(1);
    }

    @Test
    void supplier가_예외를_던지면_FAILED로_기록된다() {
        assertThatThrownBy(() -> executor.execute(JobName.AGGREGATE, TriggerType.MANUAL, null, null,
                "direct-comment-crawler",
                () -> { throw new ApifyException("차단됨"); }))
                .isInstanceOf(ApifyException.class);
        var run = runs.findTop50ByOrderByIdDesc().get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("차단됨");
    }
```

파일 상단 import에 `java.util.function.Supplier`는 불필요(테스트는 람다만 사용). `ApifyResult` FQCN 사용.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*CrawlExecutorTest'`
Expected: FAIL — 해당 시그니처의 `execute` 없음(컴파일 에러).

- [ ] **Step 3: 최소 구현** — `CrawlExecutor.java`에 import `java.util.function.Supplier` 추가 후, 기존 `execute` 메서드를 아래 2개로 교체:

```java
    public Execution execute(JobName job, TriggerType trigger, Long categoryId,
                             String keyword, String actorId, Map<String, Object> input) {
        return execute(job, trigger, categoryId, keyword, actorId, () -> runner.run(actorId, input));
    }

    public Execution execute(JobName job, TriggerType trigger, Long categoryId,
                             String keyword, String actorId, Supplier<ApifyResult> work) {
        CrawlRun run = runs.save(new CrawlRun(job, trigger, categoryId, keyword, actorId, clock.instant()));
        try {
            ApifyResult result = work.get();
            run.finishOk(result.runId(), result.items().size(), clock.instant());
            runs.save(run);
            archive(run.getId(), result.items());
            return new Execution(run.getId(), result.items());
        } catch (ApifyException e) {
            run.finishFailed(e.getMessage(), clock.instant());
            runs.save(run);
            throw e;
        }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*CrawlExecutorTest'`
Expected: PASS (기존 3개 + 신규 2개).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/CrawlExecutor.java \
        src/test/java/com/celfit/crawler/crawling/application/service/CrawlExecutorTest.java
git commit -m "feat: CrawlExecutor에 Supplier 오버로드 — 액터 외 수집도 crawl_run으로 감쌈

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: CommentSource enum + CommentSourceSetting (settings 모듈)

토글 값을 저장/조회하는 런타임 설정. `app_setting` 테이블(key/value 문자열) 공유 — 마이그레이션 불필요.

**Files:**
- Create: `src/main/java/com/celfit/crawler/settings/domain/CommentSource.java`
- Create: `src/main/java/com/celfit/crawler/settings/application/service/CommentSourceSetting.java`
- Test: `src/test/java/com/celfit/crawler/settings/application/service/CommentSourceSettingTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository`(settings.application.port.out), `AppSetting`(settings.domain) — 생성자 `new AppSetting(String key, String value)`, `getValue()`.
- Produces: `enum CommentSource { ACTOR, DIRECT }`; `CommentSource current()`(기본 ACTOR); `void update(CommentSource)`.

- [ ] **Step 1: enum 작성**

```java
package com.celfit.crawler.settings.domain;

public enum CommentSource { ACTOR, DIRECT }
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.domain.CommentSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommentSourceSettingTest extends IntegrationTest {

    @Autowired CommentSourceSetting setting;

    @Test
    void 기본값은_ACTOR다() {
        assertThat(setting.current()).isEqualTo(CommentSource.ACTOR);
    }

    @Test
    void 업데이트하면_그_값을_돌려준다() {
        setting.update(CommentSource.DIRECT);
        assertThat(setting.current()).isEqualTo(CommentSource.DIRECT);
    }

    @Test
    void 알수없는_저장값은_ACTOR로_방어된다() {
        setting.updateRaw("garbage");
        assertThat(setting.current()).isEqualTo(CommentSource.ACTOR);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*CommentSourceSettingTest'`
Expected: FAIL — `CommentSourceSetting` 없음.

- [ ] **Step 4: 구현**

```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 댓글 수집 방식 토글. app_setting 키 comment.source, 값이 없거나 이상하면 ACTOR. */
@Service
public class CommentSourceSetting {

    static final String KEY = "comment.source";

    private final AppSettingRepository settings;

    public CommentSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public CommentSource current() {
        return settings.findById(KEY)
                .map(AppSetting::getValue)
                .map(this::parse)
                .orElse(CommentSource.ACTOR);
    }

    @Transactional
    public void update(CommentSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    /** 테스트/방어용 — 임의 문자열 저장. */
    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private CommentSource parse(String value) {
        try {
            return CommentSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return CommentSource.ACTOR;
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*CommentSourceSettingTest'`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/settings/domain/CommentSource.java \
        src/main/java/com/celfit/crawler/settings/application/service/CommentSourceSetting.java \
        src/test/java/com/celfit/crawler/settings/application/service/CommentSourceSettingTest.java
git commit -m "feat: 댓글 수집 방식 토글 설정(comment.source, 기본 ACTOR)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: CommentFetcher 포트 + ActorCommentFetcher

기존 댓글 액터 호출을 포트 구현체로 추출. 동작은 현재와 동일.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/CommentFetcher.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/ActorCommentFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ActorCommentFetcherTest.java`

**Interfaces:**
- Consumes: `CrawlExecutor.execute(map 오버로드)`; `Actors.COMMENT`; `ActorInputs.comments(List<String> postUrls, int perPost)`; `ShortCodes.postUrl(String)`; `CommentSource`(settings.domain).
- Produces: `interface CommentFetcher { CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger); CommentSource source(); }`; `ActorCommentFetcher implements CommentFetcher`, `source()==ACTOR`.

- [ ] **Step 1: 포트 작성**

```java
package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;

/** 청크(포스트 여러 개)의 댓글 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface CommentFetcher {
    CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger);
    CommentSource source();
}
```

- [ ] **Step 2: 실패하는 테스트 작성** (fake 러너로 액터 경로 검증)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(ActorCommentFetcherTest.Config.class)
@Transactional
class ActorCommentFetcherTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeApifyRunner fakeApifyRunner() { return new FakeApifyRunner(); }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired ActorCommentFetcher fetcher;

    @BeforeEach void reset() { fake.reset(); }

    @Test
    void source는_ACTOR다() {
        assertThat(fetcher.source()).isEqualTo(CommentSource.ACTOR);
    }

    @Test
    void 댓글_액터를_postUrl_리스트로_호출하고_결과를_돌려준다() {
        fake.enqueue(List.of(Map.of("postUrl", "https://www.instagram.com/p/AA/",
                "ownerUsername", "fan", "text", "좋아요", "timestamp", "2026-01-01T00:00:00.000Z")));

        var ex = fetcher.fetch(List.of("AA", "BB"), 50, TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        var call = fake.calls.get(0);
        assertThat(call.actorId()).isEqualTo(Actors.COMMENT);
        assertThat(call.input()).containsEntry("resultsLimit", 50);
        assertThat(call.input().get("directUrls").toString()).contains("/p/AA/").contains("/p/BB/");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*ActorCommentFetcherTest'`
Expected: FAIL — `ActorCommentFetcher` 없음.

- [ ] **Step 4: 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 Apify 댓글 액터 경로. */
@Component
public class ActorCommentFetcher implements CommentFetcher {

    private final CrawlExecutor executor;

    public ActorCommentFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger) {
        List<String> postUrls = shortCodes.stream().map(ShortCodes::postUrl).toList();
        return executor.execute(JobName.AGGREGATE, trigger, null, null,
                Actors.COMMENT, ActorInputs.comments(postUrls, limit));
    }

    @Override
    public CommentSource source() {
        return CommentSource.ACTOR;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*ActorCommentFetcherTest'`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/CommentFetcher.java \
        src/main/java/com/celfit/crawler/crawling/application/service/ActorCommentFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ActorCommentFetcherTest.java
git commit -m "feat: CommentFetcher 포트 + ActorCommentFetcher(기존 액터 경로 추출)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: CommentSourceSelector

설정값으로 활성 `CommentFetcher`를 고른다. DirectCommentFetcher는 아직 없으므로, 이 태스크는 등록된 `CommentFetcher` 빈들을 `source()` 기준으로 매핑한다.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/CommentSourceSelector.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/CommentSourceSelectorTest.java`

**Interfaces:**
- Consumes: `List<CommentFetcher>`(스프링이 모든 구현체 주입), `CommentSourceSetting.current()`.
- Produces: `CommentFetcher current()` — 설정의 `CommentSource`와 `source()`가 일치하는 구현체 반환. 없으면 ACTOR 구현체로 폴백.

- [ ] **Step 1: 실패하는 테스트 작성** (순수 단위 — fake fetcher 2개)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentSourceSelectorTest {

    static CommentFetcher fetcherOf(CommentSource s) {
        return new CommentFetcher() {
            public CrawlExecutor.Execution fetch(List<String> c, int l, TriggerType t) { return null; }
            public CommentSource source() { return s; }
        };
    }

    @Test
    void 설정이_DIRECT면_DIRECT_구현체를_반환한다() {
        var actor = fetcherOf(CommentSource.ACTOR);
        var direct = fetcherOf(CommentSource.DIRECT);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor, direct), setting);

        assertThat(selector.current()).isSameAs(direct);
    }

    @Test
    void 해당_구현체가_없으면_ACTOR로_폴백한다() {
        var actor = fetcherOf(CommentSource.ACTOR);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor), setting);

        assertThat(selector.current()).isSameAs(actor);
    }
}
```

주의: Mockito는 spring-boot-starter-test에 포함. import 경로 확인.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*CommentSourceSelectorTest'`
Expected: FAIL — `CommentSourceSelector` 없음.

- [ ] **Step 3: 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** comment.source 설정으로 활성 CommentFetcher 선택. 미존재 시 ACTOR 폴백. */
@Service
public class CommentSourceSelector {

    private final Map<CommentSource, CommentFetcher> bySource;
    private final CommentSourceSetting setting;

    public CommentSourceSelector(List<CommentFetcher> fetchers, CommentSourceSetting setting) {
        this.bySource = fetchers.stream()
                .collect(Collectors.toMap(CommentFetcher::source, Function.identity()));
        this.setting = setting;
    }

    public CommentFetcher current() {
        CommentFetcher f = bySource.get(setting.current());
        return f != null ? f : bySource.get(CommentSource.ACTOR);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*CommentSourceSelectorTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/CommentSourceSelector.java \
        src/test/java/com/celfit/crawler/crawling/application/service/CommentSourceSelectorTest.java
git commit -m "feat: CommentSourceSelector — 설정 기반 댓글 전략 선택(ACTOR 폴백)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: AggregateJob을 셀렉터로 배선

댓글 호출부를 셀렉터 경유로 교체. 기본값 ACTOR이므로 기존 `AggregateJobTest`는 그대로 통과해야 한다(회귀 안전판).

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/AggregateJob.java`
- Test: 기존 `AggregateJobTest`(변경 없이 통과) + 회귀 실행.

**Interfaces:**
- Consumes: `CommentSourceSelector.current()` → `CommentFetcher.fetch(List<String> shortCodes, int limit, TriggerType)`.

- [ ] **Step 1: 생성자에 셀렉터 주입** — `AggregateJob` 필드/생성자에 `CommentSourceSelector commentSource` 추가:

```java
    private final CommentSourceSelector commentSource;
```
생성자 파라미터 끝에 `CommentSourceSelector commentSource` 추가하고 `this.commentSource = commentSource;` 배선.

- [ ] **Step 2: aggregateChunk의 댓글 호출부 교체** — 아래 기존 블록:

```java
        List<String> commentUrls = chunk.stream()
                .map(c -> ShortCodes.postUrl(c.getShortCode()))
                .toList();
```
를 삭제하고, 아래 기존 호출:

```java
            var cx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                    Actors.COMMENT, ActorInputs.comments(commentUrls, settings.commentsPerPost()));
            commentRunId = cx.runId();
            commentsByCode = groupComments(cx.items());
```
를 아래로 교체:

```java
            List<String> shortCodes = chunk.stream().map(Content::getShortCode).toList();
            var cx = commentSource.current()
                    .fetch(shortCodes, settings.commentsPerPost(), trigger);
            commentRunId = cx.runId();
            commentsByCode = groupComments(cx.items());
```

사용하지 않게 된 import(`Actors`가 다른 곳에서 여전히 쓰이면 유지 — detailActor에서 `Actors.DETAIL_*` 사용하므로 `Actors` import는 유지)를 확인.

- [ ] **Step 3: 기존 회귀 테스트 실행**

Run: `./gradlew test --tests '*AggregateJobTest'`
Expected: PASS — 댓글은 여전히 액터(fake) 경유. FakeApifyRunner 호출 순서(detail→comment) 불변.

- [ ] **Step 4: 전체 테스트로 배선 무결성 확인**

Run: `./gradlew test`
Expected: PASS (전체 그린).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/AggregateJob.java
git commit -m "refactor: AggregateJob 댓글 수집을 CommentSourceSelector 경유로 — 동작 불변

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: UI 토글 카드

`/ui/settings` 상단에 방식 토글 추가.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/CommentSourceUiController.java`
- Modify: `src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java`
- Modify: settings 화면 템플릿 `settings.html` (경로는 `find src/main/resources -name settings.html`로 확인)
- Test: `src/test/java/com/celfit/crawler/crawling/adapter/in/web/CommentSourceUiControllerTest.java`

**Interfaces:**
- Consumes: `CommentSourceSetting.current()/update(CommentSource)`.
- Produces: `POST /ui/comment-source` (form param `source=ACTOR|DIRECT`) → `redirect:/ui/settings`; 모델 속성 `commentSource`(현재 값 문자열).

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성** (MockMvc, 기존 web 테스트 스타일 참고 `SettingsApiTest`)

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommentSourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CommentSourceSetting setting;

    @Test
    void 토글_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/comment-source").param("source", "DIRECT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(setting.current()).isEqualTo(CommentSource.DIRECT);
    }
}
```

주의: `IntegrationTest`가 `@AutoConfigureMockMvc`를 포함하는지 확인(`SettingsApiTest` 참고). 없으면 클래스에 `@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` 추가.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*CommentSourceUiControllerTest'`
Expected: FAIL — 컨트롤러/매핑 없음(404 또는 컴파일 에러).

- [ ] **Step 3: 컨트롤러 구현**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CommentSourceUiController {

    private final CommentSourceSetting setting;

    public CommentSourceUiController(CommentSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/comment-source")
    public String update(@RequestParam String source) {
        setting.update(CommentSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}
```

- [ ] **Step 4: 설정 화면에 현재값 노출** — `UiSettingsController`의 GET 핸들러(설정 페이지 렌더)에서 모델에 추가. 생성자에 `CommentSourceSetting` 주입 후, 모델 채우는 곳에:

```java
        model.addAttribute("commentSource", commentSourceSetting.current().name());
```

- [ ] **Step 5: 템플릿에 토글 카드 추가** — `settings.html`의 폼 위쪽에:

```html
<section class="card">
  <h2>댓글 수집 방식</h2>
  <form method="post" action="/ui/comment-source">
    <label><input type="radio" name="source" value="ACTOR"
      th:checked="${commentSource == 'ACTOR'}"> 액터 (Apify)</label>
    <label><input type="radio" name="source" value="DIRECT"
      th:checked="${commentSource == 'DIRECT'}"> 자체 크롤 (직접)</label>
    <button type="submit">저장</button>
  </form>
  <p>현재: <b th:text="${commentSource}">ACTOR</b> · 기본값: ACTOR</p>
</section>
```

- [ ] **Step 6: 테스트 통과 + UI 스모크 확인**

Run: `./gradlew test --tests '*CommentSourceUiControllerTest' --tests '*UiSmokeTest'`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/in/web/CommentSourceUiController.java \
        src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java \
        src/test/java/com/celfit/crawler/crawling/adapter/in/web/CommentSourceUiControllerTest.java \
        src/main/resources/**/settings.html
git commit -m "feat: 댓글 수집 방식 UI 토글(/ui/settings)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: 정찰 — 실제 인스타 샘플 캡처 & 픽스처 커밋

DirectCommentFetcher 파서(Task 8·9)는 **실제** 페이지 HTML·GraphQL 응답을 기준으로 TDD한다. 추측 금지. 이 태스크는 실물을 캡처해 테스트 리소스로 커밋하고, 파싱 앵커를 문서화한다.

**Files:**
- Create: `src/test/resources/instagram/post-page.html`
- Create: `src/test/resources/instagram/comments-response.json`
- Create: `src/test/resources/instagram/RECON.md`

**절차(수동, 개발자 브라우저 DevTools 사용):**

- [ ] **Step 1: 대상 shortCode 선정** — DB에서 하나 뽑기:

```bash
docker compose exec -T postgres psql -U crawler -d crawler -tAc \
  "SELECT short_code FROM content WHERE status='AGGREGATED' LIMIT 1"
```

- [ ] **Step 2: 포스트 페이지 HTML 저장** — **로그아웃** 브라우저에서 `https://www.instagram.com/p/{shortCode}/` 접속 → DevTools Network → 최초 document 응답을 `post-page.html`로 저장. (또는 `view-source` 저장.)

- [ ] **Step 3: 댓글 GraphQL 응답 저장** — DevTools Network에서 `graphql` 필터 → 댓글이 들어있는 `POST /api/graphql` 응답(Preview에 comment/username 배열이 보이는 것)을 찾아 **Response**를 `comments-response.json`으로 저장. 그 요청을 **Copy → Copy as cURL**도 임시 저장.

- [ ] **Step 4: 앵커 문서화** — `RECON.md`에 다음을 실측값으로 기록:
  - `lsd` 토큰이 HTML 어디에 있는지 (예: `"LSD",[],{"token":"XXXX"}` 또는 `<input name="lsd" value="XXXX">`)
  - 댓글 쿼리의 `doc_id` 값과 `fb_api_req_friendly_name`
  - 요청 `variables` JSON의 키(예: `media_id`, `first`, `sort_order`, `after`)
  - 응답에서 댓글 배열 JSON 경로(예: `data.xdt_api__v1__media__media_id__comments__connection.edges[].node`)
  - 각 댓글 노드에서 username/text/timestamp 필드명(예: `user.username`, `text`, `created_at`)
  - 페이지네이션 커서 경로(예: `...connection.page_info.end_cursor`, `has_next_page`)

- [ ] **Step 5: 커밋**

```bash
git add src/test/resources/instagram/
git commit -m "test: 인스타 비로그인 포스트/댓글 실측 픽스처 + 파싱 앵커(RECON.md)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

> **참고:** 이후 태스크의 정확한 필드명/경로는 `RECON.md`가 정본이다. 아래 태스크의 예시 경로가 실측과 다르면 RECON.md 값으로 맞춘다.

---

## Task 8: HandshakeExtractor (순수 파서)

`post-page.html`에서 `lsd` 토큰을 뽑고, shortCode로 `media_id`를 로컬 계산한다.
**주의(RECON.md 실측):** 댓글 쿼리 `doc_id`는 서버 HTML에 없다(별도 JS 번들). 따라서 이 추출기는 **doc_id를 다루지 않는다** — doc_id/friendly_name은 Task 11에서 설정값(`DirectCommentProperties`)으로 주입한다.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HandshakeExtractor.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HandshakeExtractorTest.java`

**Interfaces:**
- Produces: `static String lsdFrom(String html)` — HTML에서 lsd 토큰 추출, 실패 시 `ApifyException`.
- 별도: `static long mediaIdFromShortCode(String sc)` — base64 로컬 계산(결정적).

- [ ] **Step 1: media_id 로컬 계산 테스트부터** (결정적, 픽스처 불필요) — 스파이크 검증값 사용:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HandshakeExtractorTest {

    @Test
    void shortCode를_media_id로_디코딩한다() {
        // 스파이크 검증: DYtaeT4TPYu -> 3903892884139341358
        assertThat(HandshakeExtractor.mediaIdFromShortCode("DYtaeT4TPYu"))
                .isEqualTo(3903892884139341358L);
    }
}
```

- [ ] **Step 2: 실패 확인 → 구현(계산부)**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HandshakeExtractor {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    public static long mediaIdFromShortCode(String sc) {
        long n = 0;
        for (int i = 0; i < sc.length(); i++) {
            int idx = ALPHABET.indexOf(sc.charAt(i));
            if (idx < 0) throw new ApifyException("shortCode 문자 이상: " + sc);
            n = n * 64 + idx;
        }
        return n;
    }

    private HandshakeExtractor() {}
}
```

Run: `./gradlew test --tests '*HandshakeExtractorTest'` → PASS.

- [ ] **Step 3: lsd 추출 테스트 추가** — 실측 픽스처(`post-page.html`)에는 `"LSD",[],{"token":"..."}` 형태로 lsd가 존재함(RECON.md 확인):

```java
    @Test
    void 페이지_HTML에서_lsd_토큰을_추출한다() throws Exception {
        String html = new String(getClass().getResourceAsStream("/instagram/post-page.html").readAllBytes());
        String lsd = HandshakeExtractor.lsdFrom(html);
        assertThat(lsd).isNotBlank();
        assertThat(lsd).doesNotContain("\"");   // 토큰만, 따옴표 없음
    }
```

- [ ] **Step 4: 실패 확인 → `lsdFrom(html)` 구현** — 정규식은 `post-page.html` 픽스처로 통과해야 한다(테스트가 정본):

```java
    private static final Pattern LSD = Pattern.compile("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"");

    public static String lsdFrom(String html) {
        Matcher m = LSD.matcher(html);
        if (!m.find()) throw new ApifyException("lsd 토큰 추출 실패");
        return m.group(1);
    }
```

(import에 `java.util.regex.Matcher`, `java.util.regex.Pattern` 추가.)

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*HandshakeExtractorTest'`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/HandshakeExtractor.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HandshakeExtractorTest.java
git commit -m "feat: HandshakeExtractor — HTML에서 lsd, shortCode→media_id

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: CommentMapper (순수 매퍼)

GraphQL 응답 → 댓글 맵 리스트(스키마 호환). 페이지 정보(커서)도 노출.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/CommentMapper.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/CommentMapperTest.java`

**Interfaces:**
- Consumes: `comments-response.json` 픽스처, `ObjectMapper`(tools.jackson), `RECON.md`의 JSON 경로.
- Produces: `record Page(List<Map<String,Object>> comments, String endCursor, boolean hasNext)`; `Page parse(String json, String postUrl)` — 각 comment 맵은 `{postUrl, ownerUsername, text, timestamp}`.

- [ ] **Step 1: 실패하는 테스트 작성** (실측 픽스처 기준, ObjectMapper 빈 주입 위해 IntegrationTest 상속)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommentMapperTest extends IntegrationTest {

    @Autowired CommentMapper mapper;

    String fixture() throws Exception {
        return new String(getClass().getResourceAsStream("/instagram/comments-response.json").readAllBytes());
    }

    @Test
    void 응답을_스키마호환_댓글맵으로_변환한다() throws Exception {
        var page = mapper.parse(fixture(), "https://www.instagram.com/p/AA/");
        // 픽스처엔 댓글 15개, 단일 페이지(has_next_page=false, end_cursor=null)
        assertThat(page.comments()).hasSize(15);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.endCursor()).isNull();
        var first = page.comments().get(0);
        assertThat(first).containsKeys("postUrl", "ownerUsername", "text", "timestamp");
        assertThat(first.get("postUrl")).isEqualTo("https://www.instagram.com/p/AA/");
        assertThat(first.get("ownerUsername")).isEqualTo("songsariiiii");
        assertThat(first.get("text")).isEqualTo("이정도는 기본아잉교 ❤️");
        // created_at 1779661498(epoch초) → ISO-8601 UTC 문자열
        assertThat(first.get("timestamp")).isEqualTo("2026-05-24T22:24:58.000Z");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*CommentMapperTest'`
Expected: FAIL — `CommentMapper` 없음.

- [ ] **Step 3: 구현** — JSON 경로/필드명은 `RECON.md` 실측값으로 맞춘다. 골격(예시 경로, 실측 조정):

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CommentMapper {

    public record Page(List<Map<String, Object>> comments, String endCursor, boolean hasNext) {}

    private final ObjectMapper om;

    public CommentMapper(ObjectMapper om) {
        this.om = om;   // Boot이 구성한 tools.jackson ObjectMapper 빈 주입
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public Page parse(String json, String postUrl) {
        JsonNode root;
        try {
            root = om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("댓글 응답 파싱 실패: " + e.getMessage(), e);
        }
        // RECON.md 실측 경로: data.xig_polaris_media.comments_connection
        JsonNode conn = root.path("data").path("xig_polaris_media").path("comments_connection");
        JsonNode edges = conn.path("edges");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("postUrl", postUrl);
            c.put("ownerUsername", node.path("user").path("username").asString());
            c.put("text", node.path("text").asString());
            // created_at = epoch seconds → ISO-8601 UTC (액터 경로의 ISO 문자열과 형식 일치)
            c.put("timestamp", ISO.format(Instant.ofEpochSecond(node.path("created_at").asLong())));
            out.add(c);
        }
        JsonNode pi = conn.path("page_info");
        String endCursor = pi.path("end_cursor").isNull() ? null : pi.path("end_cursor").asString(null);
        return new Page(out, endCursor, pi.path("has_next_page").asBoolean(false));
    }
}
```

import에 `java.time.Instant`, `java.time.ZoneOffset`, `java.time.format.DateTimeFormatter` 추가.

> 필드 경로가 픽스처와 다르면 **테스트 실패로 드러난다** — 테스트가 정본.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*CommentMapperTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/CommentMapper.java \
        src/test/java/com/celfit/crawler/crawling/application/service/CommentMapperTest.java
git commit -m "feat: CommentMapper — GraphQL 응답을 스키마호환 댓글맵으로

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 10: InstagramWebClient 포트 + JDK 프록시 구현 + 설정

프록시 물린 HTTP GET/POST. 네트워크 실연동은 자동 테스트하지 않고, fetcher 테스트에서 fake로 대체할 수 있도록 포트로 분리.

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/InstagramWebClient.java`
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/DirectCommentProperties.java`
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/JdkInstagramWebClient.java`
- Modify: `src/main/resources/application.yml`
- Modify: `@ConfigurationPropertiesScan` 위치 확인(기존 `ApifyProperties` 등록 방식과 동일하게 `DirectCommentProperties` 등록).

**Interfaces:**
- Produces:
```java
public interface InstagramWebClient {
    /** 쿠키를 관리하며 GET. 응답 본문(문자열) 반환. */
    Response get(String url);
    /** graphql POST. form-encoded body, 헤더 맵. 응답 본문 반환. */
    Response post(String url, String formBody, Map<String,String> headers);
    record Response(int status, String body, Map<String,String> setCookies) {}
}
```
- `DirectCommentProperties(String proxyUrl, java.time.Duration requestTimeout, java.time.Duration pageDelay)`.

- [ ] **Step 1: 포트 인터페이스 작성** (위 코드).

- [ ] **Step 2: properties record 작성**

```java
package com.celfit.crawler.crawling.adapter.out.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.direct-comment")
public record DirectCommentProperties(String proxyUrl, Duration requestTimeout, Duration pageDelay) {}
```

- [ ] **Step 3: application.yml에 기본값** — `crawler:` 아래 추가:

```yaml
  direct-comment:
    proxy-url: ${APIFY_PROXY_URL:}
    request-timeout: 15s
    page-delay: 1s
```

- [ ] **Step 4: JdkInstagramWebClient 구현** — `JdkApifyHttp` 패턴 참고. 쿠키는 `java.net.CookieManager`로 세션 유지, 프록시는 `proxyUrl`이 비어있지 않으면 `ProxySelector`/인증 헤더 설정.

```java
package com.celfit.crawler.crawling.adapter.out.instagram;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JdkInstagramWebClient implements InstagramWebClient {

    private final HttpClient client = HttpClient.newBuilder().build();
    private final DirectCommentProperties props;

    public JdkInstagramWebClient(DirectCommentProperties props) {
        this.props = props;
    }

    @Override
    public Response get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .GET().build());
    }

    @Override
    public Response post(String url, String formBody, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody));
        headers.forEach(b::header);
        return send(b.build());
    }

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private Response send(HttpRequest req) {
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String cookie = res.headers().firstValue("set-cookie").orElse("");
            return new Response(res.statusCode(), res.body(), Map.of("set-cookie", cookie));
        } catch (Exception e) {
            throw new ApifyException("인스타 요청 실패: " + e.getMessage(), e);
        }
    }
}
```

> 프록시 인증(`groups-RESIDENTIAL:{password}@proxy.apify.com:8000`) 배선은 `proxyUrl` 파싱 후 `HttpClient.Builder.proxy(...)` + `Authenticator`로 확장한다. `proxyUrl` 공란이면 프록시 없이(로컬 IP) 동작 — 개발 스모크용.

- [ ] **Step 5: 컴파일·부팅 확인**

Run: `./gradlew compileJava test --tests '*SanityTest'`
Expected: PASS (부팅 시 properties 바인딩 확인).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/InstagramWebClient.java \
        src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/ \
        src/main/resources/application.yml
git commit -m "feat: InstagramWebClient 포트 + JDK 프록시 구현 + direct-comment 설정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 11: DirectCommentFetcher (조립)

포스트별로 세션 GET → `lsd` 추출 → GraphQL POST → 페이지네이션으로 limit까지 수집. 청크 전체를 `CrawlExecutor` Supplier 오버로드로 crawl_run 1건에 기록. `source()==DIRECT`.

**실측 반영:** `doc_id`·`fb_api_req_friendly_name`은 서버 HTML에 없으므로 **설정값**(`DirectCommentProperties`)으로 주입한다. `lsd`만 페이지에서 동적 추출. 요청 `variables`의 정확한 키/형식은 이 태스크의 fake 테스트로는 검증 불가하며 **Task 12 스모크에서 실측 cURL로 확정**한다(이 태스크는 오케스트레이션 로직을 실측 응답 픽스처로 검증).

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/DirectCommentProperties.java` (설정 필드 2개 추가)
- Modify: `src/main/resources/application.yml`, `src/test/resources/application.yml` (설정 키 2개 추가)
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/DirectCommentFetcher.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/DirectCommentFetcherTest.java`

**Interfaces:**
- Consumes: `InstagramWebClient`, `HandshakeExtractor.lsdFrom/mediaIdFromShortCode`, `CommentMapper.parse`, `CrawlExecutor`(Supplier 오버로드), `DirectCommentProperties.{pageDelay,commentDocId,commentFriendlyName}`.
- Produces: `DirectCommentFetcher implements CommentFetcher`, `source()==DIRECT`.

- [ ] **Step 1: DirectCommentProperties 확장** — 필드 2개 추가:

```java
package com.celfit.crawler.crawling.adapter.out.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.direct-comment")
public record DirectCommentProperties(String proxyUrl, Duration requestTimeout, Duration pageDelay,
                                      String commentDocId, String commentFriendlyName) {}
```

`src/main/resources/application.yml`의 `direct-comment` 블록에 추가:
```yaml
    comment-doc-id: ${IG_COMMENT_DOC_ID:}
    comment-friendly-name: ${IG_COMMENT_FRIENDLY_NAME:}
```
`src/test/resources/application.yml`의 `direct-comment` 블록에도 동일 2줄 추가(빈 기본값이면 바인딩 OK).

- [ ] **Step 2: 실패하는 테스트 작성** (Fake `InstagramWebClient` + 실측 픽스처로 오케스트레이션 검증)

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DirectCommentFetcherTest extends IntegrationTest {

    @Autowired CrawlExecutor executor;
    @Autowired CommentMapper mapper;   // tools.jackson ObjectMapper가 주입된 빈

    static String res(String p) throws Exception {
        return new String(DirectCommentFetcherTest.class.getResourceAsStream(p).readAllBytes());
    }

    // Fake 웹클라이언트: 페이지 HTML과 graphql 응답들을 순서대로 반환
    static class FakeWeb implements InstagramWebClient {
        String html; List<String> graphql; int i = 0; int getStatus = 200; int postStatus = 200;
        public Response get(String url) { return new Response(getStatus, html, Map.of()); }
        public Response post(String url, String body, Map<String, String> h) {
            if (postStatus >= 300) return new Response(postStatus, "blocked", Map.of());
            return new Response(200, graphql.get(Math.min(i++, graphql.size() - 1)), Map.of());
        }
    }

    DirectCommentFetcher fetcher(FakeWeb web) {
        return new DirectCommentFetcher(web, executor, mapper, Duration.ZERO, "DOC", "FRIENDLY");
    }

    @Test
    void source는_DIRECT다() {
        assertThat(fetcher(new FakeWeb()).source()).isEqualTo(CommentSource.DIRECT);
    }

    @Test
    void 단일페이지_응답의_댓글을_전부_수집한다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.graphql = List.of(res("/instagram/comments-response.json"));  // hasNext=false
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(15);
        assertThat(ex.items().get(0)).containsEntry("ownerUsername", "songsariiiii");
    }

    @Test
    void limit을_초과하면_잘라낸다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.graphql = List.of(res("/instagram/comments-response.json"));
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 5, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(5);
    }

    @Test
    void 다음페이지가_있으면_커서로_이어_수집한다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        String base = res("/instagram/comments-response.json");
        // 1페이지: has_next_page=true+커서, 2페이지: 원본(false) → 15+15=30
        String page1 = base.replace("\"end_cursor\":null,\"has_next_page\":false",
                "\"end_cursor\":\"CUR\",\"has_next_page\":true");
        web.graphql = List.of(page1, base);
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(30);
    }

    @Test
    void graphql이_차단되면_ApifyException() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.postStatus = 429;
        assertThatThrownBy(() -> fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL))
                .isInstanceOf(ApifyException.class);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests '*DirectCommentFetcherTest'`
Expected: FAIL — `DirectCommentFetcher` 없음.

- [ ] **Step 4: 구현** — 스프링 생성자(properties)와 테스트 생성자(값 직접) 둘 다 제공. `lsd`는 동적, `doc_id`/`friendly_name`은 설정값.

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DirectCommentFetcher implements CommentFetcher {

    static final String ACTOR_LABEL = "direct-comment-crawler";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final CommentMapper mapper;
    private final Duration pageDelay;
    private final String docId;
    private final String friendlyName;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor,
                                CommentMapper mapper, DirectCommentProperties props) {
        this(web, executor, mapper, props.pageDelay(), props.commentDocId(), props.commentFriendlyName());
    }

    DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor, CommentMapper mapper,
                         Duration pageDelay, String docId, String friendlyName) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
        this.docId = docId;
        this.friendlyName = friendlyName;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger) {
        return executor.execute(JobName.AGGREGATE, trigger, null, null, ACTOR_LABEL,
                () -> new ApifyResult(null, collectAll(shortCodes, limit)));
    }

    private List<Map<String, Object>> collectAll(List<String> shortCodes, int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String sc : shortCodes) {
            all.addAll(collectOne(sc, limit));
        }
        return all;
    }

    private List<Map<String, Object>> collectOne(String shortCode, int limit) {
        String postUrl = ShortCodes.postUrl(shortCode);
        var pageResp = web.get(postUrl);
        if (pageResp.status() >= 300) throw new ApifyException("포스트 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());
        long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        while (out.size() < limit) {
            var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
                    Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
            if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
            var page = mapper.parse(resp.body(), postUrl);
            out.addAll(page.comments());
            if (!page.hasNext() || page.endCursor() == null) break;
            cursor = page.endCursor();
            sleep();
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** variables 정확한 키/형식은 Task 12 스모크에서 실측 cURL로 확정. */
    private String graphqlBody(String lsd, long mediaId, String cursor) {
        StringBuilder vars = new StringBuilder("{\"media_id\":\"").append(mediaId).append("\"");
        if (cursor != null) vars.append(",\"after\":\"").append(cursor).append("\"");
        vars.append("}");
        return "lsd=" + enc(lsd)
                + "&fb_api_req_friendly_name=" + enc(friendlyName)
                + "&doc_id=" + enc(docId)
                + "&variables=" + enc(vars.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private void sleep() {
        try {
            if (!pageDelay.isZero()) Thread.sleep(pageDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("중단됨", e);
        }
    }

    @Override
    public CommentSource source() {
        return CommentSource.DIRECT;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*DirectCommentFetcherTest'`
Expected: PASS (5개 테스트).

- [ ] **Step 6: 전체 테스트**

Run: `./gradlew test`
Expected: PASS. 이제 `CommentSourceSelector`가 ACTOR/DIRECT 두 구현체를 모두 인지(스프링 컨텍스트 부팅 OK).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/DirectCommentFetcher.java \
        src/test/java/com/celfit/crawler/crawling/application/service/DirectCommentFetcherTest.java \
        src/main/java/com/celfit/crawler/crawling/adapter/out/instagram/DirectCommentProperties.java \
        src/main/resources/application.yml src/test/resources/application.yml
git commit -m "feat: DirectCommentFetcher — 비로그인 GraphQL 자체 댓글 크롤(doc_id는 설정값)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 12: 수동 스모크 & A/B 비교 (자동 테스트 아님)

실제 인스타 연동 검증 및 두 방식 비교. 문서화된 절차로 수행.

- [ ] **Step 1: 프록시 설정** — `APIFY_PROXY_URL` 환경변수에 Apify Proxy 문자열 지정(예 `http://groups-RESIDENTIAL:PASSWORD@proxy.apify.com:8000`). 미설정 시 로컬 IP로 시도(차단 위험).

- [ ] **Step 2: DIRECT로 토글 후 aggregate 실행** — 앱 실행 → `/ui/settings`에서 "자체 크롤" 선택 → `/ui/jobs`에서 aggregate 실행. 로그·DB 확인:

```bash
docker compose exec -T postgres psql -U crawler -d crawler -c \
"SELECT actor_id, status, item_count, error_message FROM crawl_run \
 WHERE actor_id='direct-comment-crawler' ORDER BY id DESC LIMIT 5;"
```
기대: `SUCCEEDED` + `item_count > 0`. 실패면 `error_message`로 원인 파악(핸드셰이크/차단), 필요 시 RECON.md·정규식·경로 수정 후 Task 8·9·11 재검.

- [ ] **Step 3: 수집 댓글 스키마 확인**

```bash
docker compose exec -T postgres psql -U crawler -d crawler -c \
"SELECT writer, left(text,20), written_at FROM raw_comment \
 WHERE crawl_run_id=(SELECT max(id) FROM crawl_run WHERE actor_id='direct-comment-crawler') LIMIT 5;"
```
기대: `writer`/`text`/`written_at` 생성 컬럼이 채워짐(스키마 호환 확인).

- [ ] **Step 4: A/B 비교** — ACTOR로 토글해 동일 절차 1회 실행 후 비교:

```bash
docker compose exec -T postgres psql -U crawler -d crawler -c \
"SELECT actor_id, count(*) runs, sum(item_count) items, \
 round(avg(extract(epoch from finished_at-started_at)),1) avg_sec, \
 count(*) filter (where status='FAILED') failed \
 FROM crawl_run WHERE job='AGGREGATE' AND actor_id IN \
 ('apify~instagram-comment-scraper','direct-comment-crawler') GROUP BY actor_id;"
```

- [ ] **Step 5: 결과 기록** — 비교표(수집량·성공률·소요시간·체감 비용)를 `docs/superpowers/specs/2026-07-09-direct-comment-fetcher-design.md` 하단 "실측 결과" 절에 추가하고 커밋.

---

## Task 13 (선택): 대시보드 비교 카드

핵심 완성 후. `StatusService`류에 방식별 crawl_run 집계를 추가하고 대시보드에 표시. 우선순위 낮음 — Task 12 비교 쿼리로 이미 목적 달성 가능하므로, 반복 비교가 잦을 때만 진행.

---

## Self-Review 메모

- **스펙 커버리지**: 전략분리(T3·T5), 토글설정(T2)·UI(T6), Direct 파서(T8·T9)·클라이언트(T10)·조립(T11), 비교(T12) — 스펙 전 항목 대응.
- **타입 일관성**: `CommentFetcher.fetch(List<String>,int,TriggerType)` / `CrawlExecutor.Execution` / `ApifyResult(String,List<Map>)` / `CommentSource{ACTOR,DIRECT}` 전 태스크 일치.
- **정찰 의존성**: T8·T9·T11의 정확한 정규식·JSON 경로·요청 본문은 T7 실측 픽스처가 정본. 예시 경로가 실측과 다르면 테스트 실패로 드러나 수정하게 설계 — 추측을 코드에 고정하지 않음.
- **알려진 미확정**: 인스타 `doc_id`/`variables`/응답 경로는 T7에서 확정. 이 불확실성은 계획 구조(정찰-우선 TDD)로 관리.
