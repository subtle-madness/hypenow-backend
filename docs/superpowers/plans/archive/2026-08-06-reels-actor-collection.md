# 릴스 액터 수집 (임시 토글) 구현 계획

> 상태: ✅ 구현/실행/반영됨 (2026-08-06, PR #352 머지 · 운영 배포)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 릴스 수집을 런타임 토글(`reels.source`)로 Apify 액터(`apify~instagram-reel-scraper`)로 임시 전환하고, 액터 수집분이 analytics 지표까지 끊김 없이 흐르게 한다.

**Architecture:** `ReelsJob.visit()` 내부 소스 분기(계정당 액터 런 1회) + `raw_media_page(APIFY_ACTOR)` 저장 + `MediaItemExtractor` 액터 형태 분기 + `v_base_reel_item` UNION ALL 확장. HIKER 경로는 무변경 — 토글만으로 양방향 즉시 복귀.

**Tech Stack:** Java 21 / Spring Boot 4.1 / Mockito 단위 테스트 / PostgreSQL SQL 하니스(analytics)

**Spec:** [docs/superpowers/specs/2026-08-06-reels-actor-collection-design.md](../../specs/2026-08-06-reels-actor-collection-design.md)

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(모듈):`/`docs:` 식.
- 테스트는 모듈 단위만: `./gradlew :crawler:test` (전체 `./gradlew test`는 PR 직전에도 이 계획에선 불필요 — crawler·analytics만 변경).
- 통합 테스트 전 셸에 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수(미설정 시 대량 실패 — 플레이키 오진 금지).
- SQL 하니스: `PG_CONTAINER=hypenow-crawler-postgres-1 analytics/test/run.sh` (이 머신의 컨테이너 이름).
- Flyway 마이그레이션 없음(뷰는 `CREATE OR REPLACE`, 설정은 코드 기본값) — expand-contract 위반 소지 없음.
- grep 시 `--exclude-dir=docs` (문서 히트가 코드를 묻는다).
- **Task 1(실측)이 확정한 필드명이 이 계획의 가정과 다르면, 계획이 아니라 실측을 따른다** — Task 5·6·7의 필드명(`shortCode`/`timestamp`/`productType`/`caption`/`likesCount`/`commentsCount`/`videoPlayCount`/`videoViewCount`/`displayUrl`/`videoDuration`/`isSponsored`)을 실측값으로 치환할 것.

---

### Task 1: 액터 payload 실측 스모크 런 (필드 확정)

**Files:**
- Create: (스크래치) `scratchpad/reel-actor-sample.json` — 세션 스크래치 디렉토리에 저장, 커밋하지 않음
- Modify: 없음 (결과는 Task 5·6·7 픽스처의 원본)

**Interfaces:**
- Produces: 액터 아이템의 실측 필드명 목록(코드·시각·타입·캡션·지표·썸네일). 이후 태스크는 이 실측을 정본으로 쓴다.

- [ ] **Step 1: 스모크 대상 계정 선정 (읽기 전용 조회)**

```bash
docker exec hypenow-crawler-postgres-1 psql -U crawler -d crawler -tAc \
  "SELECT username FROM influencer WHERE beauty = true AND status = 'QUALIFIED' AND last_reels_at IS NOT NULL ORDER BY last_reels_at DESC LIMIT 3"
```

셋 중 첫 번째 username을 쓴다(릴스가 실제로 있는 계정임이 보장됨).

- [ ] **Step 2: 액터 런 1회 실행 (Apify 크레딧 소액 소진 — 사용자가 승인한 목적 그 자체)**

로컬 `.env`에는 `APIFY_TOKEN`이 없다(08-06 확인 — 서버 배포 env에만 있음). 두 경로 중 하나:

**경로 A (기본) — 서버에서 실행, 토큰은 서버 밖으로 안 나옴:**

```bash
ssh ubuntu@155.248.187.106 'TOKEN=$(sudo docker exec $(sudo docker ps -qf name=crawler- | head -1) printenv APIFY_TOKEN); curl -s -X POST "https://api.apify.com/v2/acts/apify~instagram-reel-scraper/run-sync-get-dataset-items?token=$TOKEN" -H "Content-Type: application/json" -d "{\"username\":[\"<계정명>\"],\"resultsLimit\":2}"' > "$SCRATCHPAD/reel-actor-sample.json"
```

(`name=crawler-` 필터가 여러 개 잡히면 `sudo docker ps`로 crawler 서비스 컨테이너를 눈으로 확정. `sudo`가 불필요하면 빼고. 토큰을 stdout에 출력하는 명령은 금지.)

**경로 B (폴백) — 서버 접근이 안 되면**: 사용자에게 `APIFY_TOKEN` 셸 export를 요청하고 같은 curl을 로컬에서 실행.

- [ ] **Step 3: 필드명 확정**

```bash
python3 -c "
import json,sys
items=json.load(open(sys.argv[1]))
print(sorted(items[0].keys()))
for k in ['shortCode','timestamp','productType','caption','likesCount','commentsCount','videoPlayCount','videoViewCount','displayUrl','videoDuration','isSponsored']:
    print(k, '=', items[0].get(k, '<<없음>>'))
" "$SCRATCHPAD/reel-actor-sample.json"
```

확인 항목: ① 코드 필드명 ② 시각 필드명·형식(ISO인지 epoch인지) ③ 캡션이 평문 문자열인지 ④ 지표 필드명 ⑤ 썸네일 필드명 ⑥ `productType` 값. **가정과 다른 필드는 Task 5·6·7에서 실측명으로 치환한다.** 결과 요약을 사용자에게 보고.

- [ ] **Step 4: 커밋 없음** — 산출물은 스크래치 파일과 확정된 필드명 목록뿐.

---

### Task 2: ReelsSource enum + ReelsSourceSetting

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/settings/domain/ReelsSource.java`
- Create: `crawler/src/main/java/com/celfit/crawler/settings/application/service/ReelsSourceSetting.java`
- Test: `crawler/src/test/java/com/celfit/crawler/settings/application/service/ReelsSourceSettingTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository`, `AppSetting` (기존), 테스트 fake는 `ProfileSourceSettingTest.fakeRepo(Map)` 재사용 (public static).
- Produces: `ReelsSource {HIKER, ACTOR}` / `ReelsSourceSetting.current(): ReelsSource`, `update(ReelsSource)`, `updateRaw(String)` — Task 6·8·9가 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.domain.ReelsSource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ReelsSourceSettingTest {

    @Test void 기본값은_HIKER() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(ReelsSource.HIKER);
    }

    @Test void 저장한_값을_읽는다() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ReelsSource.ACTOR);
        assertThat(setting.current()).isEqualTo(ReelsSource.ACTOR);
    }

    @Test void 이상한_값이면_HIKER로_폴백() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.updateRaw("GARBAGE");
        assertThat(setting.current()).isEqualTo(ReelsSource.HIKER);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :crawler:compileTestJava`
Expected: FAIL — `ReelsSource`/`ReelsSourceSetting` 심볼 없음

- [ ] **Step 3: 구현**

`ReelsSource.java`:

```java
package com.celfit.crawler.settings.domain;

/** 릴스 수집 소스 — ACTOR는 임시(오결제 Apify 크레딧 소진), 장기 기본은 HIKER. */
public enum ReelsSource { HIKER, ACTOR }
```

`ReelsSourceSetting.java` (ProfileSourceSetting 동형):

```java
package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 릴스 수집 소스 토글. app_setting 키 reels.source, 없거나 이상하면 HIKER. */
@Service
public class ReelsSourceSetting {

    static final String KEY = "reels.source";

    private final AppSettingRepository settings;

    public ReelsSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ReelsSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(ReelsSource.HIKER);
    }

    @Transactional
    public void update(ReelsSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private ReelsSource parse(String value) {
        try {
            return ReelsSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ReelsSource.HIKER;
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.settings.application.service.ReelsSourceSettingTest"`
Expected: PASS 3건

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/settings/domain/ReelsSource.java crawler/src/main/java/com/celfit/crawler/settings/application/service/ReelsSourceSetting.java crawler/src/test/java/com/celfit/crawler/settings/application/service/ReelsSourceSettingTest.java
git commit -m "feat(crawler): 릴스 수집 소스 토글 reels.source (기본 HIKER)"
```

---

### Task 3: reels.actor-results-limit 설정 (ReelsProperties + SettingsService)

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/common/config/ReelsProperties.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java`
- Modify: `crawler/src/main/resources/application.yml` (58-59행 `crawler.reels` 블록)
- Test: `crawler/src/test/java/com/celfit/crawler/settings/adapter/in/web/SettingsApiTest.java` (키 노출 검증이 있으면 갱신)

**Interfaces:**
- Produces: `SettingsService.reelsActorResultsLimit(): int` (기본 6) — Task 6·9가 사용. 설정 키 `"reels.actor-results-limit"`.

- [ ] **Step 1: ReelsProperties에 필드 추가**

```java
@ConfigurationProperties("crawler.reels")
public record ReelsProperties(int batchLimit, int actorResultsLimit) {}
```

`application.yml`:

```yaml
  reels:
    batch-limit: 10   # 실행 1회당 릴스를 수확할 계정 수 — 계정당 HikerAPI 1요청(pk 없으면 스킵)
    actor-results-limit: 6  # ACTOR 소스일 때 계정당 수확 릴스 수 — Apify 결과 건수 과금이라 얕게(최신만)
```

- [ ] **Step 2: 기존 생성자 호출처 컴파일 수정**

Run: `grep -rn --exclude-dir=docs 'new ReelsProperties' crawler/src`
발견되는 호출처(테스트 포함)마다 두 번째 인자 `6`을 추가.

- [ ] **Step 3: SettingsService에 키 추가**

- 상수: `static final String REELS_ACTOR_RESULTS_LIMIT = "reels.actor-results-limit";`
- `KEYS` 리스트 끝에 `REELS_ACTOR_RESULTS_LIMIT` 추가.
- `DESCRIPTIONS`에 추가(엔트리가 10개가 되어 `Map.of` 한도에 딱 참 — 초과하게 되면 `Map.ofEntries`로 전환):
  `REELS_ACTOR_RESULTS_LIMIT, "reels: ACTOR 소스일 때 계정당 수확할 릴스 수 (Apify 결과 건수 과금)"`
- `defaultValue` switch에 `case REELS_ACTOR_RESULTS_LIMIT -> reelsProps.actorResultsLimit();`
- 접근자:

```java
@Transactional(readOnly = true)
public int reelsActorResultsLimit() {
    return effective(REELS_ACTOR_RESULTS_LIMIT);
}
```

- [ ] **Step 4: 모듈 컴파일·기존 테스트 통과 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :crawler:test --tests "com.celfit.crawler.settings.*"`
Expected: PASS (SettingsApiTest가 키 목록을 단정하면 새 키 반영해 갱신)

- [ ] **Step 5: 커밋**

```bash
git add -u crawler/
git commit -m "feat(crawler): reels.actor-results-limit 런타임 설정 (기본 6)"
```

---

### Task 4: ActorInputs.reels()

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/ActorInputs.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/port/out/ActorInputsTest.java`

**Interfaces:**
- Produces: `ActorInputs.reels(String username, int resultsLimit): Map<String, Object>` — Task 6이 사용.

- [ ] **Step 1: 실패하는 테스트 추가 (ActorInputsTest에)**

```java
@Test
void reels_입력은_username_배열과_결과_한도를_담는다() {
    Map<String, Object> input = ActorInputs.reels("alice", 6);
    assertThat(input).containsEntry("username", List.of("alice"))
            .containsEntry("resultsLimit", 6);
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :crawler:compileTestJava`
Expected: FAIL — `reels` 심볼 없음

- [ ] **Step 3: 구현 (detailUrls 아래에)**

```java
/** reel 전용 상세 액터의 계정 열거 모드 — username 배열 + 계정당 결과 한도(건수 과금). */
public static Map<String, Object> reels(String username, int resultsLimit) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("username", List.of(username));
    input.put("resultsLimit", resultsLimit);
    return input;
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.port.out.ActorInputsTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -u crawler/
git commit -m "feat(crawler): 릴스 액터 입력 헬퍼 ActorInputs.reels"
```

---

### Task 5: MediaItemExtractor APIFY_ACTOR 분기

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/MediaItemExtractorTest.java`

**Interfaces:**
- Consumes: Task 1의 실측 필드명(다르면 치환).
- Produces: `extract(payload, RawSource.APIFY_ACTOR)`·`captions(payload, RawSource.APIFY_ACTOR)`가 `{"items":[...]}` 래퍼 payload에서 동작 — Task 6이 사용.

- [ ] **Step 1: 실패하는 테스트 추가**

```java
@Test
void 액터_아이템은_shortCode와_ISO_timestamp와_평문_캡션을_추출한다() {
    Map<String, Object> payload = Map.of("items", List.of(
            Map.of("shortCode", "A1", "timestamp", "2026-08-01T12:00:00.000Z",
                    "productType", "clips", "caption", "액터 캡션"),
            // 캡션 키 자체가 없는 아이템 — 미확인(null)이어야 한다 (3-상태 계약)
            Map.of("shortCode", "A2", "timestamp", "2026-08-02T12:00:00.000Z",
                    "productType", "clips")));

    List<MediaItemExtractor.MediaItem> items =
            MediaItemExtractor.extract(payload, RawSource.APIFY_ACTOR);

    assertThat(items).hasSize(2);
    assertThat(items.get(0)).isEqualTo(new MediaItemExtractor.MediaItem(
            "A1", Instant.parse("2026-08-01T12:00:00.000Z"), ContentType.REELS, false, "액터 캡션"));
    assertThat(items.get(1).caption()).isNull();
}

@Test
void 액터_아이템은_productType이_없어도_REELS다() {
    // 릴스 전용 액터 — productType 결측이 FEED 오분류로 새지 않아야 한다
    Map<String, Object> payload = Map.of("items", List.of(
            Map.of("shortCode", "A3", "timestamp", "2026-08-01T12:00:00.000Z")));

    List<MediaItemExtractor.MediaItem> items =
            MediaItemExtractor.extract(payload, RawSource.APIFY_ACTOR);

    assertThat(items.get(0).type()).isEqualTo(ContentType.REELS);
}

@Test
void 액터_캡션이_빈_문자열이면_확인된_무캡션이다() {
    Map<String, Object> payload = Map.of("items", List.of(
            Map.of("shortCode", "A4", "timestamp", "2026-08-01T12:00:00.000Z", "caption", "")));

    assertThat(MediaItemExtractor.extract(payload, RawSource.APIFY_ACTOR).get(0).caption())
            .isEqualTo("");
}

@Test
void captions는_액터_payload의_평문_캡션을_모은다() {
    Map<String, Object> payload = Map.of("items", List.of(
            Map.of("shortCode", "A1", "timestamp", "2026-08-01T12:00:00.000Z", "caption", "캡션1"),
            Map.of("shortCode", "A2", "timestamp", "2026-08-02T12:00:00.000Z", "caption", ""),
            Map.of("shortCode", "A3", "timestamp", "2026-08-03T12:00:00.000Z")));

    assertThat(MediaItemExtractor.captions(payload, RawSource.APIFY_ACTOR))
            .containsExactly("캡션1");
}
```

(Task 1 실측에서 `timestamp`가 epoch 숫자였다면 픽스처와 기대값을 그 형식으로 바꾼다 — `takenAtOf`는 Number·ISO 문자열 둘 다 처리한다.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"`
Expected: FAIL — 액터 케이스 4건 (items()가 APIFY_ACTOR에서 빈 리스트)

- [ ] **Step 3: 구현**

① `items()` switch에 분기 추가:

```java
case APIFY_ACTOR -> payload.get("items");
```

② `extract()` 루프 안 — shortCode 후보·timestamp 폴백·타입 분기(기존 코드를 아래로 교체):

```java
String code = firstString(m.get("code"), m.get("shortcode"), m.get("shortCode"));  // SELF_GQL은 shortcode, 액터는 shortCode
Instant takenAt = takenAtOf(get(m, "taken_at"));
if (takenAt == null) takenAt = takenAtOf(m.get("taken_at_timestamp")); // SELF_GQL
if (takenAt == null) takenAt = takenAtOf(m.get("timestamp"));          // APIFY_ACTOR (ISO)
if (code == null || takenAt == null) continue;
// 액터(릴스 전용)는 productType 결측이어도 REELS — FEED 오분류 방지. 나머지 소스는 기존 규칙.
ContentType type = switch (source) {
    case APIFY_ACTOR -> "feed".equals(m.get("productType")) ? ContentType.FEED : ContentType.REELS;
    default -> "clips".equals(m.get("product_type")) ? ContentType.REELS : ContentType.FEED;
};
```

③ `captionOf()` — 평문 문자열 분기(기존 `containsKey("caption")` 블록을 교체):

```java
if (m.containsKey("caption")) {
    Object c = m.get("caption");
    if (c instanceof String s) return s;   // 액터 아이템은 평문 — 빈 문자열도 '확인된 무캡션'
    return c instanceof Map<?, ?> cm && cm.get("text") instanceof String s ? s : "";
}
```

④ `captions()` — 소스 가드 확장 + 평문 수집(주석도 갱신 — "HIKER_V2_CLIPS만 지원" 문구를 APIFY_ACTOR 포함으로):

```java
public static List<String> captions(Map<String, Object> payload, RawSource source) {
    if (source != RawSource.HIKER_V2_CLIPS && source != RawSource.APIFY_ACTOR) return List.of();
    List<String> out = new ArrayList<>();
    for (Object o : items(payload, source)) {
        if (!(o instanceof Map<?, ?> raw)) continue;
        Map<String, Object> m = unwrapMedia(raw);
        if (m.get("caption") instanceof Map<?, ?> cap
                && cap.get("text") instanceof String t && !t.isBlank()) {
            out.add(t);
        } else if (m.get("caption") instanceof String t && !t.isBlank()) {  // 액터 평문
            out.add(t);
        }
    }
    return out;
}
```

- [ ] **Step 4: 전체 추출기 테스트 통과 확인 (기존 소스 회귀 포함)**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.MediaItemExtractorTest"`
Expected: PASS 전건 — 기존 HIKER·SELF_GQL 케이스 무변경 통과가 회귀 가드

- [ ] **Step 5: 커밋**

```bash
git add -u crawler/
git commit -m "feat(crawler): MediaItemExtractor에 릴스 액터(APIFY_ACTOR) 아이템 분기"
```

---

### Task 6: ReelsJob ACTOR 분기

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ReelsJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/ReelsJobTest.java`

**Interfaces:**
- Consumes: `ReelsSourceSetting.current()` (Task 2), `SettingsService.reelsActorResultsLimit()` (Task 3), `ActorInputs.reels()` (Task 4), `MediaItemExtractor.extract(payload, APIFY_ACTOR)` (Task 5), `CrawlExecutor.execute(JobName, TriggerType, String, String, String, Map)` 액터 오버로드(기존), `Actors.DETAIL_REELS`(기존).
- Produces: `reels.source=ACTOR`일 때 계정당 액터 런 1회 → `raw_media_page(APIFY_ACTOR, {"items":[...]})` → content·캡션 upsert → `last_reels_at` 북키핑.

- [ ] **Step 1: 테스트 배선 수정 + 실패하는 테스트 추가**

`ReelsJobTest`에:

① import·필드 추가:

```java
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.settings.application.service.ReelsSourceSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
```

```java
ReelsSourceSetting reelsSource = mock(ReelsSourceSetting.class);
```

② `wireCommon()`에 기본 소스 스텁 추가:

```java
when(reelsSource.current()).thenReturn(ReelsSource.HIKER);
```

③ `job()` 팩토리의 `ReelsJob` 생성자 호출에 `reelsSource` 추가(아래 Step 3의 새 시그니처 순서대로 — `settings` 다음). 196행 테스트의 직접 생성자 호출도 동일하게 수정.

④ 새 테스트 3건:

```java
static Map<String, Object> actorItem(String code, String isoTimestamp) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("shortCode", code);
    m.put("timestamp", isoTimestamp);
    m.put("productType", "clips");
    m.put("caption", "cap " + code);
    return m;
}

@Test
void 액터_소스면_계정당_액터_런으로_수집하고_APIFY_ACTOR로_저장한다() {
    when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
    when(settings.reelsActorResultsLimit()).thenReturn(6);
    Influencer inf = beautyTarget(1L, "alice", "PK1");
    when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(inf));
    List<Map<String, Object>> items = List.of(
            actorItem("A1", "2026-07-10T00:00:00Z"), actorItem("A2", "2026-07-10T00:00:00Z"));
    when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("alice"),
            eq(Actors.DETAIL_REELS), anyMap()))
            .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), items));

    var s = job(List.of()).run(TriggerType.MANUAL);   // 액터 경로 — Hiker 페처 불필요

    assertThat(s.visited()).isEqualTo(1);
    assertThat(s.postsUpserted()).isEqualTo(2);
    assertThat(inf.getLastReelsAt()).isEqualTo(NOW);

    ArgumentCaptor<RawMediaPage> captor = ArgumentCaptor.forClass(RawMediaPage.class);
    verify(rawMediaPages).save(captor.capture());
    assertThat(captor.getValue().getSource()).isEqualTo(RawSource.APIFY_ACTOR);
    assertThat(captor.getValue().getPayload()).isEqualTo(Map.of("items", items));
    assertThat(contentStore).containsKeys("A1", "A2");
    verify(captionUpserter).upsert(any(), eq(RawSource.APIFY_ACTOR), any());
}

@Test
void 액터_소스는_pk_없어도_수집한다() {
    when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
    when(settings.reelsActorResultsLimit()).thenReturn(6);
    Influencer noPk = beautyTarget(1L, "no_pk_user", null);
    when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noPk));
    when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("no_pk_user"),
            eq(Actors.DETAIL_REELS), anyMap()))
            .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(),
                    List.of(actorItem("N1", "2026-07-10T00:00:00Z"))));

    var s = job(List.of()).run(TriggerType.MANUAL);

    assertThat(s.skippedNoPk()).isZero();   // 액터는 username 기반 — pk 스킵은 HIKER 전용
    assertThat(s.visited()).isEqualTo(1);
    assertThat(noPk.getLastReelsAt()).isEqualTo(NOW);
}

@Test
void 액터_0건_응답은_수확_완료로_마킹해_재시도_루프를_막는다() {
    when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
    when(settings.reelsActorResultsLimit()).thenReturn(6);
    Influencer noClips = beautyTarget(1L, "no_clips_user", "PK1");
    when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noClips));
    when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("no_clips_user"),
            eq(Actors.DETAIL_REELS), anyMap()))
            .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), List.of()));

    var s = job(List.of()).run(TriggerType.MANUAL);

    assertThat(s.failedVisits()).isZero();
    assertThat(s.visited()).isEqualTo(1);
    assertThat(s.postsUpserted()).isZero();
    assertThat(noClips.getLastReelsAt()).isEqualTo(NOW);
    verify(rawMediaPages, org.mockito.Mockito.never()).save(any());   // 0건은 raw 저장도 없음
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :crawler:compileTestJava`
Expected: FAIL — `ReelsJob` 생성자 인자 수 불일치

- [ ] **Step 3: ReelsJob 구현**

① import 추가: `Actors`, `ActorInputs`, `ReelsSourceSetting`, `ReelsSource`.

② 필드·생성자에 `ReelsSourceSetting reelsSource` 추가(`settings` 다음 위치):

```java
public ReelsJob(InfluencerRepository influencers, RawMediaPageRepository rawMediaPages,
                ContentUpserter contentUpserter, ContentCaptionUpserter captionUpserter,
                List<UserMediaPageFetcher> mediaFetchers,
                CrawlExecutor executor, SettingsService settings, ReelsSourceSetting reelsSource,
                Clock clock, JobProgress progress, JobStopFlag stopFlag,
                TransactionTemplate txTemplate) {
```

③ `run()` — 소스를 실행당 1회 읽고, pk 스킵을 HIKER 전용으로 가드, visit에 소스 전달:

```java
ReelsSource source = reelsSource.current();   // 실행당 1회 — 토글 변경은 다음 실행부터
...
if (source == ReelsSource.HIKER
        && (inf.getIgUserId() == null || inf.getIgUserId().isBlank())) {
    skippedNoPk++;   // 해석 요청 안 씀 — 프로필 수집이 pk를 채우면 다음 실행에서 잡힌다
    log.warn("릴스 수집 스킵(pk 없음) — 프로필 수집 선행 필요: {}", inf.getUsername());
    progress.advance(JobName.REELS, 1);
    continue;
}
try {
    upserted += txTemplate.execute(status ->
            source == ReelsSource.ACTOR ? visitActor(inf, trigger) : visit(inf, trigger));
    visited++;
}
```

④ `visitActor` 신설(기존 `visit` 아래에):

```java
/**
 * ACTOR 경로 방문 1회(트랜잭션 안) — 계정당 reel 전용 액터 런 1회. 임시 전환용(오결제 Apify
 * 크레딧 소진 — 스펙 2026-08-06). 아이템 리스트를 {"items":[...]} 래퍼로 raw_media_page에
 * 보존한다(v_base_reel_item APIFY_ACTOR 분기가 이 형태를 파싱). 0건 응답은 Hiker 404와 동일하게
 * 수확 완료로 마킹 — '릴스 없음'과 '액터 누락'을 구분할 수 없지만 다음 재방문 주기에 자연
 * 재시도되므로 임시 용도로 수용한다. username 기반이라 pk 없는 계정도 수집한다.
 */
private int visitActor(Influencer inf, TriggerType trigger) {
    CrawlExecutor.Execution ex = executor.execute(JobName.REELS, trigger,
            null, inf.getUsername(), Actors.DETAIL_REELS,
            ActorInputs.reels(inf.getUsername(), settings.reelsActorResultsLimit()));
    if (ex.items().isEmpty()) {
        inf.setLastReelsAt(clock.instant());
        influencers.save(inf);
        log.info("릴스 0건(액터) — 수확 완료로 마킹: {}", inf.getUsername());
        return 0;
    }
    Map<String, Object> payload = Map.of("items", ex.items());
    Instant capturedAt = clock.instant();
    rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), RawSource.APIFY_ACTOR,
            payload, capturedAt));
    var items = MediaItemExtractor.extract(payload, RawSource.APIFY_ACTOR);
    int upserted = contentUpserter.upsert(items, inf);
    captionUpserter.upsert(items, RawSource.APIFY_ACTOR, capturedAt);
    inf.setLastReelsAt(clock.instant());
    influencers.save(inf);
    return upserted;
}
```

⑤ 클래스 javadoc(26-32행)에 한 줄 추가: "reels.source=ACTOR면 HikerAPI 대신 계정당 Apify reel 액터 런 1회(임시 — visitActor 참조)."

- [ ] **Step 4: ReelsJob 테스트 전건 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.ReelsJobTest"`
Expected: PASS — 기존 HIKER 테스트 9건 무변경 통과(복귀 안전 증명) + 신규 3건

- [ ] **Step 5: 커밋**

```bash
git add -u crawler/
git commit -m "feat(crawler): ReelsJob 액터 경로 — reels.source=ACTOR면 계정당 reel 액터 런 1회"
```

---

### Task 7: analytics v_base_reel_item UNION 확장 + 하니스

**Files:**
- Modify: `analytics/views/00_base.sql` (74-97행 `v_base_reel_item`)
- Modify: `analytics/seed/dummy.sql` (84-93행 릴스 페이지 시드 블록 + 30-38행 content 블록)
- Modify: `analytics/test/00_base.test.sql` (38-46행 v_base_reel_item 단정)

**Interfaces:**
- Consumes: Task 6의 payload 계약 — `raw_media_page(source='APIFY_ACTOR', payload={"items":[...]})`, Task 1의 실측 필드명.
- Produces: `analytics.v_base_reel_item`이 APIFY_ACTOR 행을 동일 컬럼으로 노출 → `v_base_content_snapshot`(합성 id `(page_id*1000+ordinal)*2`)에 자동 합류.

- [ ] **Step 1: 실패하는 하니스 단정 먼저 추가**

`analytics/seed/dummy.sql` 릴스 페이지 블록(93행) 뒤에:

```sql
-- 릴스 액터(APIFY_ACTOR) 페이지 — 임시 전환 기간 수집분. crawler ReelsJob ACTOR 경로의
-- {"items":[...]} 래퍼 형태. ra1은 likesCount -1(비공개→NULL)·isSponsored 검증용.
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990002,99990000,'APIFY_ACTOR','{"items":[{"shortCode":"dummy_ra1","productType":"clips","timestamp":"2026-06-05T03:00:00.000Z","likesCount":-1,"commentsCount":30,"videoPlayCount":7000,"caption":"액터 캡션 ra1","displayUrl":"https://thumb/ra1.jpg","videoDuration":22.5,"isSponsored":true}]}'::jsonb, timestamptz '2026-06-06 12:00:00+09');
```

content 블록(38행) 끝에 행 추가(직전 행 끝 `;`를 `,`로 바꾸고):

```sql
 (99990109,'dummy_ra1','REELS','dummy_b' ,99990002, timestamptz '2026-06-05 12:00:00+09','PENDING', timestamptz '2026-06-05 12:00:00+09','ENUMERATION',0);
```

`analytics/test/00_base.test.sql` v_base_reel_item 블록: 39-40행 카운트를 7→8로 갱신(주석도 `r1x3 + rn + r3 + r4 + r5 + ra1`) 하고 단정 추가:

```sql
  -- v_base_reel_item: APIFY_ACTOR 분기 (임시 액터 전환 기간 수집분)
  ASSERT (SELECT likes FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') IS NULL,
    'v_base_reel_item ra1 likes not null (likesCount -1 → NULL)';
  ASSERT (SELECT views FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = 7000,
    'v_base_reel_item ra1 views != 7000 (videoPlayCount)';
  ASSERT (SELECT caption FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = '액터 캡션 ra1',
    'v_base_reel_item ra1 caption mismatch (평문)';
  ASSERT (SELECT comments_count FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = 30,
    'v_base_reel_item ra1 comments != 30';
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = 'https://thumb/ra1.jpg',
    'v_base_reel_item ra1 thumbnail mismatch (displayUrl)';
  ASSERT (SELECT paid_partnership FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = true,
    'v_base_reel_item ra1 paid_partnership != true (isSponsored)';
  ASSERT (SELECT video_duration FROM analytics.v_base_reel_item WHERE short_code = 'dummy_ra1') = 22.5,
    'v_base_reel_item ra1 video_duration != 22.5';
```

(Task 1 실측 필드명이 다르면 시드·단정 모두 실측명으로.)

- [ ] **Step 2: 하니스 실패 확인**

Run: `PG_CONTAINER=hypenow-crawler-postgres-1 analytics/test/run.sh test/00_base.test.sql`
Expected: FAIL — 카운트 8 불일치(아직 뷰에 액터 분기 없음 → ra1 행 미노출)

- [ ] **Step 3: 뷰 확장**

`00_base.sql`의 `v_base_reel_item`(77-97행) 끝 `;` 앞에 UNION ALL 분기 추가:

```sql
UNION ALL
-- 릴스 액터(APIFY_ACTOR) 아이템 — 임시 전환 기간(2026-08, 오결제 Apify 크레딧 소진) 수집분.
-- payload는 crawler ReelsJob ACTOR 경로가 {"items":[...]} 래퍼로 저장, 필드명은 08-06 실측.
-- likesCount -1(비공개)→NULL 정규화는 Hiker 분기와 동일 이유.
SELECT
  p.id            AS page_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  it.item->>'shortCode'                                   AS short_code,
  NULLIF((it.item->>'likesCount')::bigint, -1)            AS likes,
  (it.item->>'commentsCount')::bigint                     AS comments_count,
  COALESCE((it.item->>'videoPlayCount')::bigint,
           (it.item->>'videoViewCount')::bigint)          AS views,
  it.item->>'caption'                                     AS caption,
  it.item->>'displayUrl'                                  AS thumbnail_url,
  (it.item->>'videoDuration')::numeric                    AS video_duration,
  COALESCE((it.item->>'isSponsored')::boolean, false)     AS paid_partnership
FROM (SELECT * FROM raw_media_page
      WHERE source = 'APIFY_ACTOR'
        AND jsonb_typeof(payload->'items') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload->'items')
  WITH ORDINALITY AS it(item, ord);
```

파일 첫머리 주석(2-5행)의 "캡션·지표는 raw_media_page(HIKER_V2_CLIPS ...)" 문구에 APIFY_ACTOR를 추가.

- [ ] **Step 4: 00_base 통과 후 전체 하니스**

Run: `PG_CONTAINER=hypenow-crawler-postgres-1 analytics/test/run.sh test/00_base.test.sql`
Expected: PASS

Run: `PG_CONTAINER=hypenow-crawler-postgres-1 analytics/test/run.sh`
Expected: ALL GREEN. **다른 테스트 파일의 카운트 단정이 ra1 유입으로 실패하면**(후보: `20_landing_stats`, `01_recent_window`, `02_serving`, `03/04`) — ra1은 6월 초 날짜라 recent 창 밖이어서 대부분 무관해야 한다. 실패한 단정은 ra1이 정당하게 포함되는지 판단 후 기대값을 갱신하고 주석에 `+ra1(액터)` 사유를 남긴다. 정당하지 않게 포함되면(예: 창 계산 버그) 뷰 분기를 수정.

- [ ] **Step 5: 커밋**

```bash
git add analytics/
git commit -m "feat(analytics): v_base_reel_item에 릴스 액터(APIFY_ACTOR) 분기 — 임시 전환 기간 지표 연속"
```

---

### Task 8: 어드민 UI — 릴스 수집 방식 카드

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/ReelsSourceUiController.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/adapter/in/web/UiSettingsController.java`
- Modify: `crawler/src/main/resources/templates/settings.html` (프로필 수집 방식 카드 아래)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ReelsSourceUiControllerTest.java`

**Interfaces:**
- Consumes: `ReelsSourceSetting` (Task 2).
- Produces: `POST /ui/reels-source` + 설정 페이지 모델 속성 `reelsSource`.

- [ ] **Step 1: 실패하는 테스트 작성 (DiscoverSourceUiControllerTest 동형)**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.ReelsSourceSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class ReelsSourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReelsSourceSetting setting;

    @Test
    void 소스_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/reels-source").param("source", "ACTOR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(setting.current()).isEqualTo(ReelsSource.ACTOR);
    }

    @Test
    void 설정_페이지가_릴스_소스를_노출한다() throws Exception {
        mvc.perform(get("/ui/settings")).andExpect(status().isOk());
        // 모델 속성은 UiSettingsController에서 추가 — 렌더 성공이면 배선 OK
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.ReelsSourceUiControllerTest"`
Expected: FAIL — 404 (컨트롤러 없음)

- [ ] **Step 3: 구현**

`ReelsSourceUiController.java`:

```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.ReelsSourceSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ReelsSourceUiController {

    private final ReelsSourceSetting sourceSetting;

    public ReelsSourceUiController(ReelsSourceSetting sourceSetting) {
        this.sourceSetting = sourceSetting;
    }

    @PostMapping("/ui/reels-source")
    public String update(@RequestParam String source) {
        sourceSetting.update(ReelsSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}
```

`UiSettingsController`: 생성자에 `ReelsSourceSetting reelsSourceSetting` 추가(필드·대입 포함), `page()`에:

```java
model.addAttribute("reelsSource", reelsSourceSetting.current().name());
```

`settings.html` — 프로필 수집 방식 카드(72행 `</section>`) 뒤에:

```html
<section class="card">
    <h2>릴스 수집 방식</h2>
    <form method="post" th:action="@{/ui/reels-source}">
        <label class="check"><input type="radio" name="source" value="HIKER"
            th:checked="${reelsSource == 'HIKER'}"/> HikerAPI clips (기본)</label>
        <label class="check"><input type="radio" name="source" value="ACTOR"
            th:checked="${reelsSource == 'ACTOR'}"/> 액터 (Apify · 결과 건수 과금)</label>
        <button type="submit" class="primary">저장</button>
    </form>
    <p class="hint">현재: <b th:text="${reelsSource}">HIKER</b> · 기본값: HIKER ·
        액터는 계정당 reels.actor-results-limit개 수확 — 임시 전환용(Apify 크레딧 소진), 언제든 HIKER 복귀.</p>
</section>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.ReelsSourceUiControllerTest"`
Expected: PASS 2건

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/ReelsSourceUiController.java crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ReelsSourceUiControllerTest.java -u crawler/
git commit -m "feat(crawler): 어드민 릴스 수집 방식 카드 (HIKER/ACTOR 토글)"
```

---

### Task 9: JobCostEstimator — ACTOR 표기

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java` (142-151행 `reelsEstimate`)
- Test: `crawler/src/test/java/com/celfit/crawler/dashboard/application/JobCostEstimatorTest.java`

**Interfaces:**
- Consumes: `ReelsSourceSetting` (Task 2), `SettingsService.reelsActorResultsLimit()` (Task 3).

- [ ] **Step 1: 실패하는 테스트 추가**

기존 `reels는_대기_계정을_배치_한도로_자르고_계정당_Hiker_1요청으로_추정한다`(242행)를 본떠, 생성자 배선(테스트 셋업의 `new JobCostEstimator(...)`)에 `reelsSource` mock 추가 후(기본 스텁 `when(reelsSource.current()).thenReturn(ReelsSource.HIKER)` — 기존 테스트 무변경 통과용):

`estimates()`는 모든 잡 추정을 한꺼번에 돌리므로 기존 242행 테스트와 동일한 전체 스텁 세트가 필요하다:

```java
@Test
void reels는_ACTOR_소스면_Hiker_비용_0에_액터_과금_별도를_표기한다() {
    when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
    when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
    when(settings.resultsLimit()).thenReturn(0);
    when(settings.qualifyBatchLimit()).thenReturn(0);
    when(settings.collectBatchLimit()).thenReturn(0);
    when(settings.reelsBatchLimit()).thenReturn(10);
    when(settings.reelsActorResultsLimit()).thenReturn(6);
    when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
    when(influencers.countBackfillPending()).thenReturn(0L);
    when(influencers.countTrackDue(any())).thenReturn(0L);
    when(influencers.countReelsDue(any())).thenReturn(25L);
    when(profileSource.current()).thenReturn(ProfileSource.SELF);
    when(profileSupplement.relatedEnabled()).thenReturn(false);
    when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);

    JobCost reels = byJob(estimator.estimates()).get("reels");

    assertThat(reels.targets()).isEqualTo(10);
    assertThat(reels.minRequests()).isZero();
    assertThat(reels.minCostUsd()).isZero();
    assertThat(reels.note()).contains("Apify 액터 과금 별도");
    assertThat(reels.endpoints()).anySatisfy(e -> assertThat(e).contains("reel-scraper"));
}
```

기존 HIKER reels 테스트(242행)와 그 밖의 estimates() 경유 테스트들에는 `when(reelsSource.current()).thenReturn(ReelsSource.HIKER)` 스텁이 필요하다 — 공용 셋업이 있으면 거기에, 없으면 각 테스트에 추가(Mockito strict stubs가 불필요 스텁을 거부하면 lenient 대신 필요한 테스트에만 넣는다).

- [ ] **Step 2: 컴파일/테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.dashboard.application.JobCostEstimatorTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

`JobCostEstimator` 생성자·필드에 `ReelsSourceSetting reelsSource` 추가, `reelsEstimate()` 교체:

```java
private JobCost reelsEstimate() {
    Instant revisitBefore = RevisitCutoff.boundary(clock, settings.revisitIntervalDays());
    long due = influencers.countReelsDue(revisitBefore);
    long targets = Math.min((long) settings.reelsBatchLimit(), due);
    if (reelsSource.current() == ReelsSource.ACTOR) {
        return new JobCost("reels", "릴스 수집",
                List.of("Apify instagram-reel-scraper (계정당 런 1회 · username 기반이라 pk 불필요)"),
                targets, 0, 0, 0, 0,
                "Apify 액터 과금 별도(결과 건수당 · 계정당 " + settings.reelsActorResultsLimit() + "개)");
    }
    double cost = targets * hikerProperties.costPerRequestUsd();
    return new JobCost("reels", "릴스 수집",
            List.of("HikerAPI /v2/user/clips (계정당 정확히 1회)"),
            targets, targets, targets, cost, cost,
            "뷰티 계정만 · pk 미보유는 스킵(프로필 수집이 채우면 다음 실행) — 초과분은 다음 실행");
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.dashboard.application.JobCostEstimatorTest"`
Expected: PASS 전건(기존 HIKER 추정 테스트 무변경 통과 포함)

- [ ] **Step 5: 커밋**

```bash
git add -u crawler/
git commit -m "feat(crawler): 비용 추정에 릴스 ACTOR 소스 표기 (Hiker 0 + 액터 과금 별도)"
```

---

### Task 10: 문서·전체 검증·PR

**Files:**
- Modify: `DECISIONS.md` (표 맨 위 행 추가)

- [ ] **Step 1: DECISIONS.md 표 맨 위(헤더 다음)에 행 추가**

```markdown
| 2026-08-06 | **릴스 수집 임시 액터 전환 토글(`reels.source`)** — Hiker 크레딧 절약 + 오결제 Apify 크레딧 소진 목적. ReelsJob 소스 분기: ACTOR면 계정당 `apify~instagram-reel-scraper` 런 1회(`reels.actor-results-limit` 기본 6 — 최신만 얕게), 결과를 `{"items":[...]}` 래퍼로 `raw_media_page(APIFY_ACTOR)` 저장, `v_base_reel_item` UNION 분기로 지표 연속(하이프 스코어 단절 없음). 액터는 username 기반이라 pk 미보유 계정도 수집, 0건 응답은 Hiker 404와 동일하게 수확 완료 마킹. 기본값 HIKER(코드 폴백) — 토글만으로 양방향 즉시 복귀, HIKER 경로·기존 테스트 무변경 | [spec 2026-08-06](docs/superpowers/specs/2026-08-06-reels-actor-collection-design.md) · `ReelsJob.visitActor`·`ReelsSourceSetting`·`v_base_reel_item` |
```

- [ ] **Step 2: crawler 모듈 전체 테스트**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :crawler:test`
Expected: PASS 전건 (colima 미기동이면 먼저 `colima start --cpu 8 --memory 12`)

- [ ] **Step 3: analytics 하니스 전체**

Run: `PG_CONTAINER=hypenow-crawler-postgres-1 analytics/test/run.sh`
Expected: ALL GREEN

- [ ] **Step 4: 커밋 + push + draft PR (세션 위생 — 즉시 연다)**

```bash
git add DECISIONS.md
git commit -m "docs: 릴스 액터 전환 토글 결정 기록"
git push -u origin feature/crawler-reels-actor-collection-87de8e
```

PR 생성(develop 대상): `gh pr create` 가능하면 사용, 없으면 git credential + curl API 경로(메모리 `gh-cli-absent-pr-via-api` — python urllib은 SSL 실패라 금지):

```bash
TOKEN=$(printf 'protocol=https\nhost=github.com\n' | git credential fill | sed -n 's/^password=//p')
curl -s -X POST https://api.github.com/repos/subtle-madness/hypenow-backend/pulls \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -d '{"title":"feat(crawler,analytics): 릴스 수집 임시 액터 전환 토글(reels.source)","head":"feature/crawler-reels-actor-collection-87de8e","base":"develop","draft":true,"body":"오결제 Apify 크레딧 소진 + Hiker 크레딧 절약을 위한 임시 전환. 기본 HIKER — 토글만으로 양방향 즉시 복귀, Hiker 경로 무변경.\n\n- reels.source 토글(HIKER/ACTOR) + reels.actor-results-limit(기본 6)\n- ReelsJob ACTOR 경로: 계정당 reel 액터 런 1회, raw_media_page(APIFY_ACTOR) {\"items\":[...]} 저장\n- MediaItemExtractor·v_base_reel_item APIFY_ACTOR 분기 — 지표 연속\n- 어드민 릴스 수집 방식 카드 + 비용 추정 표기\n\n스펙: docs/superpowers/specs/2026-08-06-reels-actor-collection-design.md\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)"}'
```

- [ ] **Step 5: 운영 반영 안내 (실행 아님 — 사용자 보고 사항)**

배포는 CD로만(develop→staging→main). 머지·배포 후 어드민(`/ui` 설정)에서 "릴스 수집 방식"을 액터로 저장하면 다음 릴스 잡 실행부터 적용. Hiker 복귀도 같은 라디오로 즉시.
