# 뷰티 판정 + 유사 계정 발굴 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** QUALIFIED 인플루언서를 로컬 Claude로 뷰티 판정(BEAUTY 잡)하고, 뷰티 시드의 유사 계정을 HikerAPI로 발굴해 DISCOVERED로 유입(SIMILAR 잡)시키며, 명단 페이지에서 뷰티 판정을 수동 오버라이드할 수 있게 한다.

**Architecture:** 기존 잡 3형제(discover/qualify/collect)와 같은 결로 잡 2개를 추가한다. BEAUTY는 저장된 `raw_profile` 텍스트 재료를 `claude -p`(headless CLI)에 배치로 넘겨 판정하고(포트 뒤 어댑터 — API 교체 가능), SIMILAR는 시드당 HikerAPI suggested profiles 1회 호출(최대 30개)로 유사 계정을 upsert한다. 판정 결과·수확 마킹은 `influencer` 컬럼 4개로 관리한다.

**Tech Stack:** Spring Boot(Java 21, virtual threads), JPA/Postgres(Flyway), Thymeleaf+htmx 관리 UI, tools.jackson(Jackson 3), JUnit5+Mockito+AssertJ, HikerAPI, Claude Code CLI.

**Spec:** `docs/superpowers/specs/2026-07-15-beauty-similar-discovery-design.md`

## Global Constraints

- 주석·커밋 메시지·테스트 메서드명은 한국어 (기존 관례)
- 커밋 말미에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- JSON은 `tools.jackson.databind.ObjectMapper` (Jackson 3 — `com.fasterxml` 아님, `asString(null)` 등 3.x API)
- 파이프라인 공용 예외는 `ApifyException` (이름과 달리 전 소스 공용 — 새 예외 타입 만들지 않음)
- 잡 실행은 `JobService` 스위치 + `JobLock` — 잡 내부 부분 실패는 잡을 멈추지 않고 Summary로 집계
- 수동 판정(`beauty_source='MANUAL'`)은 어떤 자동 경로도 덮어쓰지 않는다
- 스케줄 자동 실행 없음 — UI 수동 버튼만
- 테스트 실행: `./gradlew test --tests '<클래스명>'` (전체는 `./gradlew test`)
- 현재 브랜치 `feat/influencer-pipeline`에서 계속 작업

---

### Task 1: 기반 — V10 마이그레이션 + Influencer 필드 + JobName + 레포 쿼리

**Files:**
- Create: `src/main/resources/db/migration/V10__beauty_similar.sql`
- Modify: `src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/domain/JobName.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java`

**Interfaces:**
- Produces: `Influencer.getBeauty()/setBeauty(Boolean)`, `getBeautySource()/setBeautySource(String)`, `getBeautyReason()/setBeautyReason(String)`, `getSimilarProcessedAt()/setSimilarProcessedAt(Instant)`, 상수 `Influencer.BEAUTY_SOURCE_CLAUDE`·`Influencer.BEAUTY_SOURCE_MANUAL`; `JobName.BEAUTY`·`JobName.SIMILAR`; 레포 메서드 6개(아래 코드 그대로)

- [ ] **Step 1: 마이그레이션 작성**

`src/main/resources/db/migration/V10__beauty_similar.sql`:

```sql
-- 뷰티 판정(BEAUTY 잡·수동 오버라이드) + 유사 계정 발굴(SIMILAR 잡) 지원
ALTER TABLE influencer
    ADD COLUMN beauty               boolean,
    ADD COLUMN beauty_source        text,
    ADD COLUMN beauty_reason        text,
    ADD COLUMN similar_processed_at timestamptz;
```

- [ ] **Step 2: Influencer 엔티티에 필드 추가**

`Influencer.java`의 `firstCollectedAt` 필드 위쪽(기존 필드들 뒤)에 추가 (클래스에 이미 `@Getter @Setter`가 있어 접근자는 자동):

```java
    /** 뷰티 판정 주체 값 — CLAUDE(BEAUTY 잡)·MANUAL(명단 수동). MANUAL은 재판정에서도 보존. */
    public static final String BEAUTY_SOURCE_CLAUDE = "CLAUDE";
    public static final String BEAUTY_SOURCE_MANUAL = "MANUAL";

    /** 뷰티 계정 여부 — NULL이면 미판정. SIMILAR 잡의 시드 자격 조건(beauty=true). */
    private Boolean beauty;

    @Column(name = "beauty_source")
    private String beautySource;

    /** 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "beauty_reason")
    private String beautyReason;

    /** SIMILAR 잡이 이 시드의 유사 계정 수확을 마친(또는 수확 불가로 확정한) 시각. NULL이면 시드 후보. */
    @Column(name = "similar_processed_at")
    private Instant similarProcessedAt;
```

- [ ] **Step 3: JobName에 BEAUTY·SIMILAR 추가**

```java
public enum JobName {
    DISCOVER, QUALIFY, COLLECT, BEAUTY, SIMILAR,
    /** 구 파이프라인 실행 이력(crawl_run) 판독 전용 — 새 실행 경로 없음. */
    AGGREGATE
}
```

- [ ] **Step 4: InfluencerRepository에 쿼리 추가**

`InfluencerRepository.java` 인터페이스 끝에 추가:

```java
    /** BEAUTY 잡 대상: 판정 통과했지만 뷰티 미판정. */
    List<Influencer> findByStatusAndBeautyIsNull(InfluencerStatus status);

    /** BEAUTY 재판정(rejudge) 대상: CLAUDE 판정분만 — MANUAL은 선정 자체에서 제외된다. */
    List<Influencer> findByStatusAndBeautySource(InfluencerStatus status, String beautySource);

    /** SIMILAR 시드: 뷰티 확정 + 미수확 — id 순 Pageable로 결정적으로 소진한다. */
    List<Influencer> findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
            InfluencerStatus status, Pageable pageable);

    /** 비용 추정용. */
    long countByStatusAndBeautyIsNull(InfluencerStatus status);

    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus status);

    /** 비용 추정용: pk 미보유라 SIMILAR가 username 해석 1회를 추가로 사는 시드 수. */
    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(InfluencerStatus status);
```

- [ ] **Step 5: 컴파일 + 기존 테스트로 검증**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — 파생 쿼리 이름·마이그레이션은 Testcontainers 통합 테스트(`CollectJobIntegrationTest` 등)가 컨텍스트를 띄우며 검증한다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/db/migration/V10__beauty_similar.sql \
        src/main/java/com/celfit/crawler/crawling/domain/Influencer.java \
        src/main/java/com/celfit/crawler/crawling/domain/JobName.java \
        src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java
git commit -m "feat: 뷰티 판정·유사 발굴 기반 — influencer 컬럼 4종 + JobName + 레포 쿼리

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: ProfileExtractor에 뷰티 판정 재료 추출 추가

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/ProfileExtractor.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/ProfileExtractorTest.java`

**Interfaces:**
- Produces: `ProfileExtractor.fullName(Map,RawSource)`, `ProfileExtractor.category(Map,RawSource)`, `ProfileExtractor.biography(Map,RawSource)` — 모두 `static String`, 없거나 공백이면 null

각 소스의 실제 payload 위치 (DB 실물 확인 완료):

| 소스 | full name | category | biography |
|---|---|---|---|
| LEGACY_ENVELOPE·APIFY_ACTOR | `fullName` | `businessCategoryName` | `biography` |
| HIKER_MOBILE·DATALIKERS | `user.full_name`(래퍼 유무 모두) | `user.category` → `category_name` → `business_category_name` 폴백 | `user.biography` |
| SELF_GQL | `data.user.full_name` | `data.user.category_name` → `business_category_name` 폴백 | `data.user.biography` |

- [ ] **Step 1: 실패하는 테스트 작성**

`ProfileExtractorTest.java`에 추가 (기존 테스트 스타일 — inline Map):

```java
    @Test
    void 뷰티_판정_재료를_소스별_경로에서_추출한다() {
        // LEGACY_ENVELOPE — 최상위 평탄 구조
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("fullName", "에텔랑화장품");
        legacy.put("businessCategoryName", "Beauty, cosmetic & personal care");
        legacy.put("biography", "화장품 브랜드");
        assertThat(ProfileExtractor.fullName(legacy, RawSource.LEGACY_ENVELOPE)).isEqualTo("에텔랑화장품");
        assertThat(ProfileExtractor.category(legacy, RawSource.LEGACY_ENVELOPE))
                .isEqualTo("Beauty, cosmetic & personal care");
        assertThat(ProfileExtractor.biography(legacy, RawSource.LEGACY_ENVELOPE)).isEqualTo("화장품 브랜드");

        // HIKER_MOBILE — user 래퍼 + category, 폴백은 category_name
        Map<String, Object> hikerUser = new LinkedHashMap<>();
        hikerUser.put("full_name", "뷰티 크리에이터");
        hikerUser.put("category", "Digital creator");
        hikerUser.put("biography", "메이크업");
        Map<String, Object> hiker = Map.of("user", hikerUser);
        assertThat(ProfileExtractor.fullName(hiker, RawSource.HIKER_MOBILE)).isEqualTo("뷰티 크리에이터");
        assertThat(ProfileExtractor.category(hiker, RawSource.HIKER_MOBILE)).isEqualTo("Digital creator");
        assertThat(ProfileExtractor.biography(hiker, RawSource.HIKER_MOBILE)).isEqualTo("메이크업");

        // DATALIKERS — 평탄 유저 객체(user 래퍼 없음), category 없고 category_name만
        Map<String, Object> dl = new LinkedHashMap<>();
        dl.put("full_name", "네일샵");
        dl.put("category_name", "Nail salon");
        dl.put("biography", "네일 아트");
        assertThat(ProfileExtractor.fullName(dl, RawSource.DATALIKERS)).isEqualTo("네일샵");
        assertThat(ProfileExtractor.category(dl, RawSource.DATALIKERS)).isEqualTo("Nail salon");
        assertThat(ProfileExtractor.biography(dl, RawSource.DATALIKERS)).isEqualTo("네일 아트");

        // SELF_GQL — data.user 중첩
        Map<String, Object> gqlUser = new LinkedHashMap<>();
        gqlUser.put("full_name", "스킨케어");
        gqlUser.put("category_name", "Health/beauty");
        gqlUser.put("biography", "피부 관리");
        Map<String, Object> gql = Map.of("data", Map.of("user", gqlUser));
        assertThat(ProfileExtractor.fullName(gql, RawSource.SELF_GQL)).isEqualTo("스킨케어");
        assertThat(ProfileExtractor.category(gql, RawSource.SELF_GQL)).isEqualTo("Health/beauty");
        assertThat(ProfileExtractor.biography(gql, RawSource.SELF_GQL)).isEqualTo("피부 관리");
    }

    @Test
    void 뷰티_판정_재료가_없거나_공백이면_null() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("biography", "  ");
        assertThat(ProfileExtractor.fullName(empty, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.category(empty, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.biography(empty, RawSource.LEGACY_ENVELOPE)).isNull();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'ProfileExtractorTest'`
Expected: 컴파일 실패 — `fullName` 심볼 없음

- [ ] **Step 3: 구현**

`ProfileExtractor.java`의 `username` 메서드 뒤에 추가:

```java
    public static String fullName(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "full_name");
            case HIKER_MOBILE, DATALIKERS -> dig(user(payload), "full_name");
            default -> payload.get("fullName");
        };
        return asText(v);
    }

    public static String category(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> first(dig(payload, "data", "user", "category_name"),
                    dig(payload, "data", "user", "business_category_name"));
            case HIKER_MOBILE, DATALIKERS -> first(dig(user(payload), "category"),
                    dig(user(payload), "category_name"), dig(user(payload), "business_category_name"));
            default -> payload.get("businessCategoryName");
        };
        return asText(v);
    }

    public static String biography(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "biography");
            case HIKER_MOBILE, DATALIKERS -> dig(user(payload), "biography");
            default -> payload.get("biography");
        };
        return asText(v);
    }

    private static Object first(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }

    private static String asText(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'ProfileExtractorTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/ProfileExtractor.java \
        src/test/java/com/celfit/crawler/crawling/application/service/ProfileExtractorTest.java
git commit -m "feat: ProfileExtractor에 뷰티 판정 재료(이름·카테고리·bio) 소스별 추출

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: BeautyJudge 포트 + ClaudeCliBeautyJudge 어댑터

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java`
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java`
- Test: `src/test/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudgeTest.java`

**Interfaces:**
- Produces: `BeautyJudge` 포트 — `record ProfileCard(String username, String fullName, String category, String biography)`, `record Verdict(String username, boolean beauty, String reason)`, `List<Verdict> judge(List<ProfileCard>)` (실패 시 `ApifyException`)
- Produces: `ClaudeCliBeautyJudge.parse(ObjectMapper, String)`·`buildPrompt(ObjectMapper, List<ProfileCard>)` — package-private static (테스트용)

- [ ] **Step 1: 포트 정의**

`BeautyJudge.java`:

```java
package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

/**
 * 프로필 텍스트 재료로 뷰티 계정 여부를 판정. 현 구현은 로컬 Claude CLI —
 * 서버 배포 시 이 포트 뒤에서 Anthropic API 구현으로 교체한다.
 */
public interface BeautyJudge {

    record ProfileCard(String username, String fullName, String category, String biography) {}

    record Verdict(String username, boolean beauty, String reason) {}

    /** 실패(CLI 오류·타임아웃·파싱 불가)는 ApifyException — 호출자가 배치 단위로 격리한다. */
    List<Verdict> judge(List<ProfileCard> cards);
}
```

- [ ] **Step 2: 파싱 실패 테스트 작성**

`ClaudeCliBeautyJudgeTest.java`:

```java
package com.celfit.crawler.crawling.adapter.out.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 코드펜스로_감싼_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","beauty":true,"reason":"메이크업 계정"},
                 {"username":"b","beauty":false,"reason":"여행 계정"}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", true, "메이크업 계정"),
                new BeautyJudge.Verdict("b", false, "여행 계정"));
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"beauty\":true,\"reason\":null}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", true, null));
    }

    @Test
    void username_누락이나_beauty가_불리언이_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"beauty":true,"reason":"x"},
                 {"username":"ok","beauty":"yes"},
                 {"username":"good","beauty":false,"reason":"r"}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", false, "r"));
    }

    @Test
    void 배열이_아니거나_JSON이_아니면_ApifyException() {
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "{\"oops\":1}"))
                .isInstanceOf(ApifyException.class);
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "죄송합니다, 판정할 수 없습니다."))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 프롬프트에_카드_JSON과_출력_형식_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio")));
        assertThat(p).contains("\"username\":\"u1\"").contains("JSON 배열만");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'ClaudeCliBeautyJudgeTest'`
Expected: 컴파일 실패 — `ClaudeCliBeautyJudge` 없음

- [ ] **Step 4: 어댑터 구현**

`ClaudeCliBeautyJudge.java`:

```java
package com.celfit.crawler.crawling.adapter.out.claude;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 Claude Code CLI(headless `claude -p`)로 뷰티 판정 — 백엔드가 claude 로그인된
 * 로컬 맥에서 돈다는 전제(구독 포함, 유료 API 없음). PATH에 claude가 있어야 한다.
 */
@Component
public class ClaudeCliBeautyJudge implements BeautyJudge {

    /** 배치 1회(50명) 판정의 상한 — CLI가 응답을 못 만들면 강제 종료하고 배치 실패로 넘긴다. */
    static final int TIMEOUT_SECONDS = 120;

    private final ObjectMapper om;

    public ClaudeCliBeautyJudge(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public List<Verdict> judge(List<ProfileCard> cards) {
        return parse(om, run(buildPrompt(om, cards)));
    }

    private String run(String prompt) {
        try {
            Process p = new ProcessBuilder("claude", "-p", "--model", "haiku", "--output-format", "text")
                    .start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            // 판정 출력은 배치 50명 기준 수 KB — OS 파이프 버퍼(64KB) 안이라 waitFor 먼저 해도
            // 스트림 교착이 없다. 무한 대기 방지를 위해 타임아웃 후 읽는다.
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ApifyException("claude CLI 타임아웃(" + TIMEOUT_SECONDS + "s)");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new ApifyException("claude CLI 종료코드 " + p.exitValue() + ": " + err);
            }
            return out;
        } catch (IOException e) {
            throw new ApifyException("claude CLI 실행 실패(로컬 claude 설치·로그인 필요): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("claude CLI 대기 중단", e);
        }
    }

    static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
        return """
                다음은 인스타그램 계정 프로필 목록(JSON)이다. 각 계정이 "뷰티 계정"(화장품·메이크업·\
                스킨케어·헤어·네일·에스테틱 등 뷰티 콘텐츠 중심)인지 판정하라.
                출력은 JSON 배열만: [{"username":"...","beauty":true|false,"reason":"한 줄"}]
                입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

                """ + om.writeValueAsString(cards);
    }

    static List<Verdict> parse(ObjectMapper om, String output) {
        String json = stripFences(output);
        JsonNode root;
        try {
            root = om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("판정 응답 파싱 실패: " + e.getMessage(), e);
        }
        if (!root.isArray()) throw new ApifyException("판정 응답이 JSON 배열이 아님");
        List<Verdict> out = new ArrayList<>();
        for (JsonNode n : root) {
            String username = n.path("username").asString(null);
            if (username == null || username.isBlank() || !n.path("beauty").isBoolean()) continue;
            out.add(new Verdict(username, n.path("beauty").asBoolean(), n.path("reason").asString(null)));
        }
        return out;
    }

    /** 모델이 지시를 어기고 ```json 펜스로 감싼 경우 벗긴다. */
    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.strip();
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests 'ClaudeCliBeautyJudgeTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java \
        src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ \
        src/test/java/com/celfit/crawler/crawling/adapter/out/claude/
git commit -m "feat: BeautyJudge 포트 + 로컬 Claude CLI 어댑터 — 뷰티 판정 headless 실행·파싱

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: BeautyJob

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

**Interfaces:**
- Consumes: Task 1 레포 쿼리·Influencer 필드, Task 2 `ProfileExtractor.fullName/category/biography`, Task 3 `BeautyJudge`
- Produces: `BeautyJob.run(TriggerType, boolean rejudge)` → `record Summary(int judgedBeauty, int judgedNotBeauty, int skippedNoProfile, int failedBatches)`

- [ ] **Step 1: 실패하는 테스트 작성**

`BeautyJobTest.java` (QualifyJobTest와 같은 Mockito 스타일):

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BeautyJobTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    BeautyJudge judge = mock(BeautyJudge.class);

    BeautyJob job = new BeautyJob(influencers, rawProfiles, judge);

    static Influencer qualified(Long id, String username) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        return inf;
    }

    static RawProfile legacyProfile(Long influencerId, String fullName, String bio) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", fullName);
        payload.put("biography", bio);
        return new RawProfile(influencerId, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH);
    }

    @Test
    void 판정_결과를_beauty_필드에_저장한다() {
        Influencer a = qualified(1L, "a");
        Influencer b = qualified(2L, "b");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a, b));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L))
                .thenReturn(Optional.of(legacyProfile(2L, "여행", "여행기")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("a", true, "메이크업 중심"),
                new BeautyJudge.Verdict("b", false, "여행 계정")));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
        assertThat(s.judgedNotBeauty()).isEqualTo(1);
        assertThat(a.getBeauty()).isTrue();
        assertThat(a.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(a.getBeautyReason()).isEqualTo("메이크업 중심");
        assertThat(b.getBeauty()).isFalse();
    }

    @Test
    void raw_profile이_없으면_스킵하고_beauty는_NULL_유지() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.empty());

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.skippedNoProfile()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 배치_실패는_격리되고_해당_계정은_NULL로_남아_재시도된다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenThrow(new ApifyException("CLI 실패"));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.failedBatches()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 응답이_지어낸_username은_무시한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("ghost", true, "?")));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isZero();
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void rejudge는_CLAUDE_판정분을_다시_포함하되_MANUAL은_선정하지_않는다() {
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of());
        when(influencers.findByStatusAndBeautySource(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE)).thenReturn(List.of());

        job.run(TriggerType.MANUAL, true);

        verify(influencers).findByStatusAndBeautySource(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE);
        // MANUAL 선정 쿼리는 존재하지 않음 — findByStatusAndBeautySource(…, "MANUAL") 호출 자체가 없다
    }
}
```

참고: `RawProfile` 생성자 시그니처는 `new RawProfile(Long influencerId, Long crawlRunId, RawSource source, Map payload, Instant capturedAt)` — QualifyJob 사용례와 동일. 다르면 실제 시그니처에 맞춘다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'BeautyJobTest'`
Expected: 컴파일 실패 — `BeautyJob` 없음

- [ ] **Step 3: 구현**

`BeautyJob.java`:

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뷰티 판정 잡 — QUALIFIED 중 미판정분의 최신 raw_profile 텍스트 재료(이름·카테고리·bio)를
 * 로컬 Claude에 배치로 넘겨 beauty를 저장한다. 인스타그램 API 호출 없음(비용 $0).
 * rejudge=true면 CLAUDE 판정분도 재판정한다 — MANUAL(수동)은 선정에서 빠져 절대 덮이지 않는다.
 * beauty=true는 SIMILAR 잡의 시드 자격이 된다.
 */
@Service
public class BeautyJob {

    private static final Logger log = LoggerFactory.getLogger(BeautyJob.class);

    /** Claude 1회 호출에 넘기는 프로필 수 — 응답 길이·타임아웃(120s)과의 균형. */
    static final int JUDGE_CHUNK = 50;

    public record Summary(int judgedBeauty, int judgedNotBeauty, int skippedNoProfile, int failedBatches) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final BeautyJudge judge;

    public BeautyJob(InfluencerRepository influencers, RawProfileRepository rawProfiles, BeautyJudge judge) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.judge = judge;
    }

    @Transactional
    public Summary run(TriggerType trigger, boolean rejudge) {
        List<Influencer> targets = new ArrayList<>(
                influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED));
        if (rejudge) {
            targets.addAll(influencers.findByStatusAndBeautySource(
                    InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE));
        }

        // 판정 재료 준비 — raw_profile이 아직 없으면 판정 불가(qualify가 언젠가 채우면 재시도)
        List<BeautyJudge.ProfileCard> cards = new ArrayList<>();
        Map<String, Influencer> byUsername = new HashMap<>();
        int skipped = 0;
        for (Influencer inf : targets) {
            var rp = rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(inf.getId());
            if (rp.isEmpty()) { skipped++; continue; }
            RawProfile p = rp.get();
            cards.add(new BeautyJudge.ProfileCard(inf.getUsername(),
                    ProfileExtractor.fullName(p.getPayload(), p.getSource()),
                    ProfileExtractor.category(p.getPayload(), p.getSource()),
                    ProfileExtractor.biography(p.getPayload(), p.getSource())));
            byUsername.put(inf.getUsername(), inf);
        }

        int beauty = 0, notBeauty = 0, failedBatches = 0;
        List<List<BeautyJudge.ProfileCard>> chunks = ActorInputs.chunk(cards, JUDGE_CHUNK);
        int total = chunks.size(), i = 0;
        for (List<BeautyJudge.ProfileCard> chunk : chunks) {
            i++;
            List<BeautyJudge.Verdict> verdicts;
            try {
                verdicts = judge.judge(chunk);
            } catch (ApifyException e) {
                failedBatches++;  // 해당 배치 계정은 beauty NULL 유지 — 다음 실행 재시도
                log.warn("뷰티 판정 배치 실패 ({}/{}, {}명): {}", i, total, chunk.size(), e.getMessage());
                continue;
            }
            for (BeautyJudge.Verdict v : verdicts) {
                Influencer inf = byUsername.get(v.username());
                if (inf == null) continue;  // 응답이 지어낸 username — 무시
                inf.setBeauty(v.beauty());
                inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
                inf.setBeautyReason(v.reason());
                if (v.beauty()) beauty++; else notBeauty++;
            }
            log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 비뷰티 {}", i, total, beauty, notBeauty);
        }
        return new Summary(beauty, notBeauty, skipped, failedBatches);
    }
}
```

참고: `ActorInputs.chunk`는 제네릭 `static <T> List<List<T>> chunk(List<T>, int)` (확인 완료) — 그대로 쓴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'BeautyJobTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java \
        src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java
git commit -m "feat: BEAUTY 잡 — QUALIFIED 미판정분을 로컬 Claude로 뷰티 판정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: similar 설정 + suggested fetch 리팩터 + pk 해석기

**Files:**
- Create: `src/main/java/com/celfit/crawler/common/config/SimilarProperties.java`
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/HikerUserResolver.java`
- Modify: `src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java` (@EnableConfigurationProperties 목록)
- Modify: `src/main/resources/application.yml` (crawler.similar 블록)
- Modify: `src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/HikerSuggestedSupplement.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerUserResolverTest.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/HikerSuggestedSupplementTest.java`

**Interfaces:**
- Produces: `SettingsService.similarBatchLimit()` → int (키 `similar.batch-limit`, 기본 50)
- Produces: `HikerUserResolver.resolvePk(String username)` → String(응답에 pk 없으면 null, 전송 오류는 ApifyException)
- Produces: `HikerSuggestedSupplement.fetch(String userId)` → `record Suggested(List<Map<String,Object>> users, Object raw)` — users는 user 노드 **원형 전체**(기존 enrich의 relatedProfiles 슬림 3키 계약은 enrich 내부에서 유지)

- [ ] **Step 1: 실패하는 테스트 작성 (resolver + supplement)**

`HikerUserResolverTest.java`:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerUserResolverTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void user_래퍼가_있으면_그_안의_pk를_해석한다() {
        HikerUserResolver r = new HikerUserResolver(
                path -> "{\"user\":{\"username\":\"a\",\"pk\":12345}}", om);
        assertThat(r.resolvePk("a")).isEqualTo("12345");
    }

    @Test
    void 평탄_응답이면_최상위_pk를_해석하고_pk가_없으면_id_폴백() {
        HikerUserResolver flat = new HikerUserResolver(
                path -> "{\"username\":\"a\",\"pk\":\"77\"}", om);
        assertThat(flat.resolvePk("a")).isEqualTo("77");

        HikerUserResolver idOnly = new HikerUserResolver(
                path -> "{\"username\":\"a\",\"id\":\"88\"}", om);
        assertThat(idOnly.resolvePk("a")).isEqualTo("88");
    }

    @Test
    void pk도_id도_없으면_null() {
        HikerUserResolver r = new HikerUserResolver(path -> "{\"username\":\"a\"}", om);
        assertThat(r.resolvePk("a")).isNull();
    }
}
```

`HikerSuggestedSupplementTest.java`:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerSuggestedSupplementTest {

    static final String BODY = """
            {"response":{"suggested_users":[
              {"username":"rel1","full_name":"이름1","is_verified":false,"pk":"1","biography":"bio1"},
              {"username":"rel2","full_name":"이름2","is_verified":true,"id":"2"}
            ]}}
            """;

    ObjectMapper om = new ObjectMapper();
    HikerSuggestedSupplement sut = new HikerSuggestedSupplement(path -> BODY, om);

    @Test
    void fetch는_user_노드_원형_전체를_수집한다() {
        var s = sut.fetch("123");
        assertThat(s.users()).hasSize(2);
        assertThat(s.users().get(0))
                .containsEntry("username", "rel1")
                .containsEntry("biography", "bio1");  // 슬림 3키가 아니라 원형 그대로
        assertThat(s.raw()).isNotNull();
    }

    @Test
    void enrich는_기존_계약대로_슬림_relatedProfiles와_rawSuggested를_병합한다() {
        Map<String, Object> item = new LinkedHashMap<>();
        sut.enrich(item, "123");
        @SuppressWarnings("unchecked")
        var related = (List<Map<String, Object>>) item.get("relatedProfiles");
        assertThat(related).hasSize(2);
        assertThat(related.get(0).keySet()).containsExactly("username", "full_name", "is_verified");
        assertThat(item).containsKey("_rawSuggested");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'HikerUserResolverTest' --tests 'HikerSuggestedSupplementTest'`
Expected: 컴파일 실패 — `HikerUserResolver`·`fetch` 없음

- [ ] **Step 3: 구현**

`SimilarProperties.java`:

```java
package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.similar")
public record SimilarProperties(int batchLimit) {}
```

`CrawlerConfig.java`의 `@EnableConfigurationProperties` 목록에 `SimilarProperties.class` 추가:

```java
@EnableConfigurationProperties({ApifyProperties.class, DiscoverProperties.class,
        CollectProperties.class, ScheduleProperties.class, DirectCommentProperties.class,
        HikerProperties.class, QualifyProperties.class, DataLikersProperties.class,
        ProxyProperties.class, SimilarProperties.class})
```

`application.yml`의 `crawler.collect:` 블록 뒤에 추가:

```yaml
  similar:
    batch-limit: 50   # 실행 1회당 유사 계정을 수확할 시드(뷰티 QUALIFIED) 수
```

`SettingsService.java` — 다음 5곳 수정:

```java
    static final String SIMILAR_BATCH_LIMIT = "similar.batch-limit";
```

KEYS 목록에 `SIMILAR_BATCH_LIMIT` 추가, DESCRIPTIONS에 항목 추가:

```java
            SIMILAR_BATCH_LIMIT, "similar: 실행 1회당 유사 계정을 수확할 시드 수 (Hiker 호출량 제어)"
```

접근 메서드·기본값 분기·생성자 파라미터:

```java
    @Transactional(readOnly = true)
    public int similarBatchLimit() {
        return effective(SIMILAR_BATCH_LIMIT);
    }
```

```java
            case SIMILAR_BATCH_LIMIT -> similarProps.batchLimit();
```

(생성자에 `SimilarProperties similarProps` 추가 + 필드 할당 — 기존 `qualifyProps`와 같은 모양.)

`HikerUserResolver.java`:

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * username → 인스타 내부 pk 해석 (HikerAPI /v1/user/by/username, 유료 1요청).
 * SIMILAR 잡이 ig_user_id 미보유 시드(대부분 레거시 이관분)에 폴백으로 쓴다.
 */
@Component
public class HikerUserResolver {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerUserResolver(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    /** 응답에 pk(폴백 id)가 없으면 null. 전송 오류는 ApifyException 전파. */
    public String resolvePk(String username) {
        String body = http.get("/v1/user/by/username?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        try {
            JsonNode root = om.readTree(body);
            JsonNode user = root.path("user").isObject() ? root.path("user") : root;
            String pk = user.path("pk").asString("");
            if (pk.isBlank()) pk = user.path("id").asString("");
            return pk.isBlank() ? null : pk;
        } catch (JacksonException e) {
            throw new ApifyException("user by username 파싱 실패: " + e.getMessage(), e);
        }
    }
}
```

`HikerSuggestedSupplement.java` — `fetch` 추가, `collectUsers`를 원형 수집으로 변경, `enrich`는 fetch를 쓰도록 리팩터 (슬림 3키·`_rawSuggested` 계약 유지):

```java
    /** suggested 응답 결과 — users는 user 노드 원형 전체, raw는 응답 트리 전체. */
    public record Suggested(List<Map<String, Object>> users, Object raw) {}

    /** SIMILAR 잡·related 보충 공용 — 호출 1회로 유사 user 노드 원형을 수집한다. */
    public Suggested fetch(String userId) {
        String body = http.get("/v2/user/suggested/profiles?user_id=" + userId + "&expand_suggestion=true");
        JsonNode root = read(body);
        List<Map<String, Object>> users = new ArrayList<>();
        collectUsers(root, users);
        return new Suggested(users, om.convertValue(root, Object.class));
    }

    public void enrich(Map<String, Object> item, String userId) {
        if (userId == null) return;
        Suggested s = fetch(userId);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> u : s.users()) {
            Map<String, Object> slim = new java.util.LinkedHashMap<>();
            slim.put("username", u.get("username"));
            slim.put("full_name", u.get("full_name"));
            slim.put("is_verified", u.get("is_verified") instanceof Boolean b && b);
            related.add(slim);
        }
        item.put("relatedProfiles", related);
        item.put("_rawSuggested", s.raw());
    }

    @SuppressWarnings("unchecked")
    private void collectUsers(JsonNode node, List<Map<String, Object>> acc) {
        if (node.isObject() && node.has("username") && (node.has("pk") || node.has("id"))) {
            acc.add(om.convertValue(node, Map.class));
            return;
        }
        for (JsonNode c : node) collectUsers(c, acc);
    }
```

- [ ] **Step 4: 통과 확인 (기존 ProfileSupplementer 테스트 포함)**

Run: `./gradlew test --tests 'HikerUserResolverTest' --tests 'HikerSuggestedSupplementTest' --tests 'ProfileSupplementerTest'`
Expected: PASS — enrich 계약이 유지됐는지 ProfileSupplementerTest가 함께 확인

- [ ] **Step 5: 커밋**

```bash
git add -A src/main/java/com/celfit/crawler/common/config/ src/main/resources/application.yml \
        src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java \
        src/main/java/com/celfit/crawler/crawling/application/service/HikerSuggestedSupplement.java \
        src/main/java/com/celfit/crawler/crawling/application/service/HikerUserResolver.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerUserResolverTest.java \
        src/test/java/com/celfit/crawler/crawling/application/service/HikerSuggestedSupplementTest.java
git commit -m "feat: SIMILAR 잡 재료 — similar.batch-limit 설정 + suggested 원형 fetch + pk 해석기

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: SimilarJob

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/application/service/SimilarJob.java`
- Test: `src/test/java/com/celfit/crawler/crawling/application/service/SimilarJobTest.java`

**Interfaces:**
- Consumes: Task 1 시드 쿼리, Task 5 `HikerSuggestedSupplement.fetch`·`HikerUserResolver.resolvePk`·`settings.similarBatchLimit()`, 기존 `CrawlExecutor.execute(JobName, TriggerType, String keyword, String targetUsername, String actorId, Supplier<ApifyResult>)`
- Produces: `SimilarJob.run(TriggerType)` → `record Summary(int processedSeeds, int newInfluencers, int knownInfluencers, int ineligibleSeeds, int failedSeeds)`

- [ ] **Step 1: 실패하는 테스트 작성**

`SimilarJobTest.java`:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SimilarJobTest {

    static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerDiscoveryRepository discoveries = mock(InfluencerDiscoveryRepository.class);
    HikerSuggestedSupplement suggested = mock(HikerSuggestedSupplement.class);
    HikerUserResolver resolver = mock(HikerUserResolver.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    com.celfit.crawler.settings.application.service.SettingsService settings =
            mock(com.celfit.crawler.settings.application.service.SettingsService.class);

    SimilarJob job = new SimilarJob(influencers, discoveries, suggested, resolver, executor, settings, CLOCK);

    static Influencer seed(Long id, String username, String igUserId) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
        inf.setIgUserId(igUserId);
        return inf;
    }

    /** executor mock이 supplier를 실제로 실행하게 — pk 해석·requestCount 경로까지 단위 검증. */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void executorRunsSupplier() {
        when(settings.similarBatchLimit()).thenReturn(50);
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    ApifyResult r = ((Supplier<ApifyResult>) inv.getArgument(5)).get();
                    return new CrawlExecutor.Execution(1L, r.items());
                });
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 유사_계정을_DISCOVERED로_upsert하고_출처를_기록하고_시드를_마킹한다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenReturn(new HikerSuggestedSupplement.Suggested(
                List.of(Map.of("username", "new1", "pk", "1"),
                        Map.of("username", "known1", "pk", "2")), Map.of()));
        when(influencers.findByUsername("new1")).thenReturn(Optional.empty());
        Influencer known = seed(9L, "known1", null);
        when(influencers.findByUsername("known1")).thenReturn(Optional.of(known));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.processedSeeds()).isEqualTo(1);
        assertThat(summary.newInfluencers()).isEqualTo(1);
        assertThat(summary.knownInfluencers()).isEqualTo(1);
        assertThat(s.getSimilarProcessedAt()).isEqualTo(NOW);
        ArgumentCaptor<InfluencerDiscovery> d = ArgumentCaptor.forClass(InfluencerDiscovery.class);
        verify(discoveries, org.mockito.Mockito.times(2)).save(d.capture());
        assertThat(d.getAllValues()).allSatisfy(rec -> {
            assertThat(rec.getKeyword()).isEqualTo("유사:seed1");
            assertThat(rec.getDiscoveredPostShortCode()).isNull();
        });
    }

    @Test
    void 시드_자신과_run_내_중복은_건너뛴다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenReturn(new HikerSuggestedSupplement.Suggested(
                List.of(Map.of("username", "SEED1"),      // 자기 자신 (대소문자 무시)
                        Map.of("username", "dup"),
                        Map.of("username", "dup")), Map.of()));
        when(influencers.findByUsername("dup")).thenReturn(Optional.empty());

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.newInfluencers()).isEqualTo(1);
    }

    @Test
    void igUserId가_없으면_pk를_해석해_백필한다() {
        Influencer s = seed(1L, "seed1", null);
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(resolver.resolvePk("seed1")).thenReturn("777");
        when(suggested.fetch("777")).thenReturn(
                new HikerSuggestedSupplement.Suggested(List.of(), Map.of()));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(s.getIgUserId()).isEqualTo("777");
        assertThat(summary.processedSeeds()).isEqualTo(1);
    }

    @Test
    void pk_해석_실패_시드는_마킹하지_않고_failedSeeds로_남긴다() {
        Influencer s = seed(1L, "seed1", null);
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(resolver.resolvePk("seed1")).thenReturn(null);

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedSeeds()).isEqualTo(1);
        assertThat(s.getSimilarProcessedAt()).isNull();
        verify(suggested, never()).fetch(any());
    }

    @Test
    void chaining_불가_403은_수확_불가로_마킹해_재시도하지_않는다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenThrow(new ApifyException(
                "Hiker HTTP 403: {\"detail\":\"Not eligible for chaining.\",\"exc_type\":\"InvalidTargetUser\"}"));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.ineligibleSeeds()).isEqualTo(1);
        assertThat(summary.failedSeeds()).isZero();
        assertThat(s.getSimilarProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void 일반_오류_시드는_격리되고_다음_시드는_계속_처리된다() {
        Influencer bad = seed(1L, "bad", "1");
        Influencer good = seed(2L, "good", "2");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(bad, good));
        when(suggested.fetch("1")).thenThrow(new ApifyException("Hiker HTTP 500: 서버 오류"));
        when(suggested.fetch("2")).thenReturn(
                new HikerSuggestedSupplement.Suggested(List.of(), Map.of()));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedSeeds()).isEqualTo(1);
        assertThat(summary.processedSeeds()).isEqualTo(1);
        assertThat(bad.getSimilarProcessedAt()).isNull();
        assertThat(good.getSimilarProcessedAt()).isEqualTo(NOW);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'SimilarJobTest'`
Expected: 컴파일 실패 — `SimilarJob` 없음

- [ ] **Step 3: 구현**

`SimilarJob.java`:

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유사 계정 발굴 잡 — 뷰티 시드(QUALIFIED·beauty=true·미수확)마다 HikerAPI suggested
 * profiles(호출당 최대 30개)를 받아 DISCOVERED로 upsert한다. 응답에는 팔로워·bio가 없으므로
 * 사전 필터 없음 — 팔로워 판정은 qualify, 뷰티 판정은 beauty 잡 몫(통과하면 다시 시드가 된다).
 * 발굴 출처는 influencer_discovery("유사:{시드}") 텍스트 스냅샷으로 남긴다.
 */
@Service
public class SimilarJob {

    private static final Logger log = LoggerFactory.getLogger(SimilarJob.class);

    static final String LABEL = "hiker-suggested-profiles";
    static final String KEYWORD_PREFIX = "유사:";
    /** HikerAPI가 추천 체이닝을 막아둔 계정의 응답 표식 — 재시도 무의미, 수확 불가로 마킹. */
    static final String INELIGIBLE_MARK = "Not eligible for chaining";

    public record Summary(int processedSeeds, int newInfluencers, int knownInfluencers,
                          int ineligibleSeeds, int failedSeeds) {}

    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final HikerSuggestedSupplement suggested;
    private final HikerUserResolver resolver;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;

    public SimilarJob(InfluencerRepository influencers, InfluencerDiscoveryRepository discoveries,
                      HikerSuggestedSupplement suggested, HikerUserResolver resolver,
                      CrawlExecutor executor, SettingsService settings, Clock clock) {
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.suggested = suggested;
        this.resolver = resolver;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        List<Influencer> seeds = influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED,
                PageRequest.of(0, settings.similarBatchLimit(), Sort.by("id")));
        int processed = 0, newInf = 0, known = 0, ineligible = 0, failed = 0;
        int total = seeds.size(), i = 0;
        for (Influencer seed : seeds) {
            i++;
            CrawlExecutor.Execution ex;
            try {
                ex = executor.execute(JobName.SIMILAR, trigger, KEYWORD_PREFIX + seed.getUsername(),
                        seed.getUsername(), LABEL, () -> fetchForSeed(seed));
            } catch (ApifyException e) {
                if (e.getMessage() != null && e.getMessage().contains(INELIGIBLE_MARK)) {
                    seed.setSimilarProcessedAt(clock.instant());  // 수확 불가 확정 — 재시도 안 함
                    ineligible++;
                    log.info("유사 발굴 ({}/{}) {} — chaining 불가, 수확 불가로 마킹", i, total, seed.getUsername());
                } else {
                    failed++;  // crawl_run FAILED 기록됨 — 마킹 없이 다음 실행 재시도
                    log.warn("유사 발굴 ({}/{}) {} — 실패: {}", i, total, seed.getUsername(), e.getMessage());
                }
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> item : ex.items()) {
                String username = item.get("username") instanceof String s && !s.isBlank() ? s : null;
                if (username == null || username.equalsIgnoreCase(seed.getUsername())
                        || !seen.add(username.toLowerCase())) continue;
                var existing = influencers.findByUsername(username);
                Influencer inf = existing.orElseGet(() -> influencers.save(new Influencer(username)));
                if (existing.isPresent()) known++; else newInf++;
                // 신규·기존 모두 출처 기록(append-only) — discover의 관례와 동일
                discoveries.save(new InfluencerDiscovery(
                        inf.getId(), KEYWORD_PREFIX + seed.getUsername(), null, clock.instant()));
            }
            seed.setSimilarProcessedAt(clock.instant());
            processed++;
            log.info("유사 발굴 ({}/{}) {} — 이번 시드 {}건, 신규 누계 {}", i, total,
                    seed.getUsername(), seen.size(), newInf);
        }
        return new Summary(processed, newInf, known, ineligible, failed);
    }

    /**
     * 시드의 pk 확보(없으면 유료 1요청으로 해석해 ig_user_id 백필) 후 suggested 호출.
     * requestCount에 실제 유료 요청 수(1 또는 2)를 기록해 비용 추적을 정확히 한다.
     */
    private ApifyResult fetchForSeed(Influencer seed) {
        int requests = 0;
        String pk = seed.getIgUserId();
        if (pk == null || pk.isBlank()) {
            requests++;
            pk = resolver.resolvePk(seed.getUsername());
            if (pk == null) throw new ApifyException("pk 해석 실패: " + seed.getUsername());
            seed.setIgUserId(pk);  // 백필 — collect·재실행에서 재해석 없음
        }
        requests++;
        return new ApifyResult(null, requests, suggested.fetch(pk).users());
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'SimilarJobTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/SimilarJob.java \
        src/test/java/com/celfit/crawler/crawling/application/service/SimilarJobTest.java
git commit -m "feat: SIMILAR 잡 — 뷰티 시드의 유사 계정을 DISCOVERED로 발굴 (시드당 Hiker 1~2요청)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 배선 — JobService·UI 버튼·상태 바

**Files:**
- Modify: `src/main/java/com/celfit/crawler/crawling/application/service/JobService.java`
- Modify: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java`
- Modify: `src/main/resources/templates/jobs.html`
- Modify: `src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java` (statusFragment)

**Interfaces:**
- Consumes: Task 4 `BeautyJob.run(TriggerType, boolean)`, Task 6 `SimilarJob.run(TriggerType)`
- Produces: `POST /ui/jobs/beauty?rejudge=`, `POST /ui/jobs/similar`

- [ ] **Step 1: JobService에 분기 추가**

생성자에 `BeautyJob beautyJob, SimilarJob similarJob` 파라미터·필드 추가 후 스위치에 케이스 추가:

```java
                    case BEAUTY -> {
                        // requalify 플래그를 뷰티 재판정(rejudge)으로 재사용 — MANUAL은 잡이 보존
                        var s = beautyJob.run(triggerType, requalify);
                        if (s.failedBatches() > 0) log.warn("beauty 완료(배치 부분 실패): {}", s);
                        else log.info("beauty 완료: {}", s);
                    }
                    case SIMILAR -> {
                        var s = similarJob.run(triggerType);
                        if (s.failedSeeds() > 0) log.warn("similar 완료(시드 부분 실패): {}", s);
                        else log.info("similar 완료: {}", s);
                    }
```

- [ ] **Step 2: UiJobController에 엔드포인트 추가**

```java
    @PostMapping("/beauty")
    public String beauty(@RequestParam(defaultValue = "false") boolean rejudge, RedirectAttributes ra) {
        return respond(JobName.BEAUTY, jobService.trigger(JobName.BEAUTY, TriggerType.MANUAL, rejudge), ra);
    }

    @PostMapping("/similar")
    public String similar(RedirectAttributes ra) {
        return respond(JobName.SIMILAR, jobService.trigger(JobName.SIMILAR, TriggerType.MANUAL), ra);
    }
```

- [ ] **Step 3: jobs.html에 버튼 추가**

`③ collect` form 뒤에:

```html
    <form method="post" th:action="@{/ui/jobs/beauty}">
        <label><input type="checkbox" name="rejudge" value="true" data-persist/> Claude 판정분 재판정 (수동 판정 보존 · 인스타 호출 없음)</label>
        <button type="submit" class="primary">④ beauty — 뷰티 계정 판정 (로컬 Claude · $0)</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/similar}">
        <button type="submit" class="primary">⑤ similar — 뷰티 시드의 유사 계정 발굴</button>
    </form>
```

- [ ] **Step 4: 상태 바에 두 잡 추가**

`UiController.statusFragment`의 jobs 목록:

```java
        model.addAttribute("jobs", java.util.List.of(
                jobStatus(JobName.DISCOVER, "발굴"),
                jobStatus(JobName.QUALIFY, "판정"),
                jobStatus(JobName.BEAUTY, "뷰티판정"),
                jobStatus(JobName.SIMILAR, "유사발굴"),
                jobStatus(JobName.COLLECT, "수집")));
```

- [ ] **Step 5: 전체 테스트 + 커밋**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/celfit/crawler/crawling/application/service/JobService.java \
        src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java \
        src/main/resources/templates/jobs.html \
        src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java
git commit -m "feat: BEAUTY·SIMILAR 잡 배선 — JobService 분기 + UI 실행 버튼 + 상태 바

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: JobCostEstimator에 beauty·similar 카드 추가

**Files:**
- Modify: `src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java`
- Test: `src/test/java/com/celfit/crawler/dashboard/application/JobCostEstimatorTest.java`

**Interfaces:**
- Consumes: Task 1 카운트 쿼리 3종, Task 5 `settings.similarBatchLimit()`

- [ ] **Step 1: 실패하는 테스트 작성**

`JobCostEstimatorTest.java`에 추가 (기존 테스트의 mock 구성 방식 그대로 — 클래스 상단의 mock 필드·estimator 생성 코드를 재사용):

```java
    @Test
    void beauty는_유료_요청_0건_비용_0달러() {
        when(influencers.countByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(516L);

        var beauty = estimator.estimates().stream()
                .filter(c -> c.job().equals("beauty")).findFirst().orElseThrow();

        assertThat(beauty.targets()).isEqualTo(516);
        assertThat(beauty.maxRequests()).isZero();
        assertThat(beauty.maxCostUsd()).isZero();
    }

    @Test
    void similar는_배치_상한만큼_시드당_1회_pk_미보유만_최대_2회로_추정한다() {
        when(settings.similarBatchLimit()).thenReturn(50);
        when(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED)).thenReturn(200L);
        when(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED)).thenReturn(30L);
        when(hikerProperties.costPerRequestUsd()).thenReturn(0.001);

        var similar = estimator.estimates().stream()
                .filter(c -> c.job().equals("similar")).findFirst().orElseThrow();

        assertThat(similar.targets()).isEqualTo(50);   // min(배치 50, 대기 200)
        assertThat(similar.minRequests()).isEqualTo(50);   // 전원 pk 보유 가정
        assertThat(similar.maxRequests()).isEqualTo(80);   // pk 미보유 30명이 배치에 다 들면 +30
        assertThat(similar.minCostUsd()).isEqualTo(0.050);
        assertThat(similar.maxCostUsd()).isEqualTo(0.080);
    }
```

(확인 완료: 기존 테스트는 `influencers`·`settings` mock 필드와 `byJob(estimator.estimates())` 조회 헬퍼를 쓴다 — 새 테스트도 `byJob` 헬퍼를 그대로 써도 좋다. 카드 조회가 job 문자열 키 기반이라 카드 2장이 늘어도 기존 테스트는 깨지지 않는다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'JobCostEstimatorTest'`
Expected: FAIL — beauty/similar 카드 없음

- [ ] **Step 3: 구현**

`JobCostEstimator.java` — `estimates()`를 파이프라인 순서로 교체하고 메서드 2개 추가:

```java
    public List<JobCost> estimates() {
        return List.of(discoverEstimate(), qualifyEstimate(), beautyEstimate(),
                similarEstimate(), collectEstimate());
    }

    private JobCost beautyEstimate() {
        long targets = influencers.countByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED);
        return new JobCost("beauty", "뷰티 계정 판정 (로컬 Claude)",
                List.of("로컬 claude CLI — 구독 포함, 유료 API 없음"),
                targets, 0, 0, 0, 0, "판정 재료는 저장된 raw_profile — 인스타그램 호출 없음");
    }

    private JobCost similarEstimate() {
        long due = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus.QUALIFIED);
        long targets = Math.min((long) settings.similarBatchLimit(), due);
        long noPk = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED);
        // 배치가 pk 미보유 시드를 몇 명 집을지는 id 순서에 달렸으므로 min(전원 보유)~max(미보유 우선)로 추정
        long min = targets;
        long max = targets + Math.min(targets, noPk);
        return new JobCost("similar", "뷰티 시드의 유사 계정 발굴",
                List.of("HikerAPI /v2/user/suggested/profiles (시드당 1회 · 최대 30계정)",
                        "HikerAPI /v1/user/by/username (pk 미보유 시드만 +1회)"),
                targets, min, max,
                min * hikerProperties.costPerRequestUsd(), max * hikerProperties.costPerRequestUsd(),
                "실측: 호출당 30개 고정 · 기존 DB 대비 신규율 ~85%");
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'JobCostEstimatorTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java \
        src/test/java/com/celfit/crawler/dashboard/application/JobCostEstimatorTest.java
git commit -m "feat: 잡 비용 추정에 beauty($0)·similar(시드당 1~2요청) 카드 추가

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: 명단 페이지 뷰티 컬럼 + 수동 오버라이드

**Files:**
- Create: `src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java`
- Modify: `src/main/resources/templates/influencers.html`
- Test: `src/test/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `Influencer.setBeauty/setBeautySource/setBeautyReason`, `BEAUTY_SOURCE_MANUAL`
- Produces: `POST /ui/influencers/{id}/beauty` (params: `beauty`, `page`, `status` — 필터·페이지 유지 리다이렉트)

- [ ] **Step 1: 실패하는 테스트 작성**

`InfluencerBeautyControllerTest.java` (POJO 단위 테스트 — 기존 컨트롤러 테스트 관례가 다르면 그쪽을 따른다):

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InfluencerBeautyControllerTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerBeautyController controller = new InfluencerBeautyController(influencers);

    @Test
    void 수동_판정은_beauty와_MANUAL_출처를_기록한다() {
        Influencer inf = new Influencer("a");
        inf.setBeauty(true);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        String view = controller.override(1L, false, 2, null, new RedirectAttributesModelMap());

        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getBeautyReason()).isEqualTo("수동 판정");
        assertThat(view).isEqualTo("redirect:/ui/influencers");
    }

    @Test
    void 없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.override(9L, true, 0, null, new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'InfluencerBeautyControllerTest'`
Expected: 컴파일 실패 — 컨트롤러 없음

- [ ] **Step 3: 컨트롤러 구현**

`InfluencerBeautyController.java`:

```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 명단 페이지 뷰티 수동 오버라이드 — MANUAL 출처는 BEAUTY 잡 재판정에서도 보존된다. */
@Controller
public class InfluencerBeautyController {

    private final InfluencerRepository influencers;

    public InfluencerBeautyController(InfluencerRepository influencers) {
        this.influencers = influencers;
    }

    @PostMapping("/ui/influencers/{id}/beauty")
    public String override(@PathVariable Long id, @RequestParam boolean beauty,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(required = false) List<InfluencerStatus> status,
                           RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.setBeauty(beauty);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_MANUAL);
        inf.setBeautyReason("수동 판정");
        influencers.save(inf);
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        return "redirect:/ui/influencers";
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'InfluencerBeautyControllerTest'`
Expected: PASS

- [ ] **Step 5: 명단 템플릿에 뷰티 컬럼 추가**

`influencers.html` 테이블 헤더의 `<th>팔로워</th>` 뒤에 `<th>뷰티</th>` 추가, 행의 팔로워 `<td>` 뒤에 셀 추가:

```html
        <td>
            <span th:if="${row.influencer.beauty != null}" class="badge"
                  th:classappend="${row.influencer.beauty} ? 'QUALIFIED' : 'EXCLUDED'"
                  th:text="${row.influencer.beauty} ? '뷰티' : '뷰티 아님'"
                  th:title="${row.influencer.beautyReason}"></span>
            <span th:if="${row.influencer.beauty == null}">—</span>
            <form method="post" th:action="@{|/ui/influencers/${row.influencer.id}/beauty|}"
                  style="display:inline">
                <input type="hidden" name="page" th:value="${page.number}"/>
                <input type="hidden" th:each="s : ${status}" name="status" th:value="${s}"/>
                <button type="submit" name="beauty" value="true"
                        th:unless="${row.influencer.beauty == true}">뷰티</button>
                <button type="submit" name="beauty" value="false"
                        th:unless="${row.influencer.beauty == false}">뷰티 아님</button>
            </form>
        </td>
```

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java \
        src/test/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyControllerTest.java \
        src/main/resources/templates/influencers.html
git commit -m "feat: 명단 페이지 뷰티 컬럼 + 수동 오버라이드 (MANUAL — 재판정에서 보존)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: 최종 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 실패 0

- [ ] **Step 2: 실앱 검증 (레포 스킬 `verify` 사용)**

`.claude/skills/verify/SKILL.md` 레시피대로 부팅하되, BEAUTY 잡의 실동작을 보려면 dummy 키 대신 **HIKER_API_KEY 없이도 되는 확인**을 한다:

1. 앱 부팅 후 `/ui/jobs`에 ④ beauty·⑤ similar 버튼과 비용 카드 5장이 뜨는지
2. `/ui/influencers`에 뷰티 컬럼·수동 버튼이 뜨고, 버튼 클릭 시 뱃지가 바뀌는지 (Postgres 실DB — `beauty_source='MANUAL'` 확인: `docker exec hypenow-crawler-postgres-1 psql -U crawler -d crawler -c "SELECT username, beauty, beauty_source FROM influencer WHERE beauty_source='MANUAL' LIMIT 5;"`)
3. beauty 잡 실행(로컬 claude 필요 — dummy 키와 무관): 실행 로그에 "뷰티 판정 배치 (1/…) 완료"가 흐르는지, 이후 `SELECT beauty, count(*) FROM influencer GROUP BY beauty;`로 판정 분포 확인
4. similar 잡은 실 HIKER_API_KEY가 있을 때만 실행 (비용 발생 — 사용자 확인 후)

- [ ] **Step 3: 마무리**

superpowers:finishing-a-development-branch 스킬로 통합 방안(머지/PR) 결정.
