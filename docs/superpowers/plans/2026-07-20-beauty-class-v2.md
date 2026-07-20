# 뷰티 판정 v2 — 4분류(beauty_class) 구현 계획

> 상태: 🟢 활성 · ✅ 구현됨(재판정 운영 작업 대기)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 뷰티 판정을 3분류에서 4분류(INFLUENCER/COMPANY/BEAUTY_SERVICE/NOT_BEAUTY)로 바꿔 피부과·성형외과·에스테틱·헤어샵 등 시술·서비스 계정을 별도 세그먼트로 분리하고, 판정 LLM을 Gemini에서 Anthropic SDK(구독)로 전환한다.

**Architecture:** 4분류 원본은 새 컬럼 `influencer.beauty_class`에 저장하고, 기존 boolean(`beauty`/`beauty_company`)은 파생값으로 계속 채운다. BEAUTY_SERVICE는 beauty=false로 파생되어 SIMILAR 시드·수집·분석 뷰·was가 무변경으로 자동 제외한다. 파생 규칙의 단일 원천은 `BeautyClass` enum.

**Tech Stack:** Java 21, Spring Boot 4.1, JPA/Flyway, Thymeleaf 어드민, Jackson 3(`tools.jackson.*`), Anthropic Java SDK.

**스펙:** `docs/superpowers/specs/2026-07-20-beauty-class-v2-design.md`

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(crawler):`/`docs:` 식.
- Jackson 3 (`tools.jackson.*`) — `com.fasterxml` 금지.
- 테스트 실행은 `./gradlew :crawler:test` (전체는 `./gradlew test`).
- raw DB 스키마 변경은 crawler Flyway로만 (현재 최신 V15 → 이번에 V16).
- 분석 뷰(00_base/02_serving/20_landing_stats)·was·리포지토리의 boolean 기반 선정 쿼리는 **건드리지 않는다** (파생 규칙 덕에 무변경).
- 로컬에서 크롤 잡 실행 금지(수집 주체는 오라클 서버) — 재판정은 서버 어드민에서 트리거.

---

### Task 1: BeautyClass enum + Influencer.beauty_class + V16 마이그레이션

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/domain/BeautyClass.java`
- Create: `crawler/src/main/resources/db/migration/V16__beauty_class.sql`
- Create: `crawler/src/test/java/com/celfit/crawler/crawling/domain/BeautyClassTest.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`

**Interfaces:**
- Produces: `enum BeautyClass { INFLUENCER, COMPANY, BEAUTY_SERVICE, NOT_BEAUTY }` + `boolean beauty()` / `boolean company()`
- Produces: `Influencer.getBeautyClass()`, `Influencer.classify(BeautyClass cls, String source, String reason)` — beauty_class와 파생 boolean·source·reason을 한 번에 세팅(judgedAt은 미변경 — 호출자 몫)

- [ ] **Step 1: 실패하는 테스트 작성**

`crawler/src/test/java/com/celfit/crawler/crawling/domain/BeautyClassTest.java`:

```java
package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BeautyClassTest {

    @Test
    void 파생_boolean_규칙_인플루언서와_회사만_beauty_true() {
        assertThat(BeautyClass.INFLUENCER.beauty()).isTrue();
        assertThat(BeautyClass.INFLUENCER.company()).isFalse();
        assertThat(BeautyClass.COMPANY.beauty()).isTrue();
        assertThat(BeautyClass.COMPANY.company()).isTrue();
        assertThat(BeautyClass.BEAUTY_SERVICE.beauty()).isFalse();
        assertThat(BeautyClass.BEAUTY_SERVICE.company()).isFalse();
        assertThat(BeautyClass.NOT_BEAUTY.beauty()).isFalse();
        assertThat(BeautyClass.NOT_BEAUTY.company()).isFalse();
    }

    @Test
    void classify는_beauty_class와_파생값을_함께_세팅하고_judgedAt은_건드리지_않는다() {
        Influencer inf = new Influencer("a");
        inf.classify(BeautyClass.BEAUTY_SERVICE, Influencer.BEAUTY_SOURCE_CLAUDE, "피부과 시술 홍보");

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautyCompany()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(inf.getBeautyReason()).isEqualTo("피부과 시술 홍보");
        assertThat(inf.getBeautyJudgedAt()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests BeautyClassTest`
Expected: 컴파일 실패 (`BeautyClass` 없음)

- [ ] **Step 3: 구현**

`crawler/src/main/java/com/celfit/crawler/crawling/domain/BeautyClass.java`:

```java
package com.celfit.crawler.crawling.domain;

/**
 * 뷰티 판정 4분류 (v2, 2026-07-20 스펙) — 판정 목적은 뷰티 제품(스킨케어·메이크업·향수 등)
 * 시딩·협찬 대상 발굴. boolean(beauty/beauty_company) 파생 규칙의 단일 원천.
 * BEAUTY_SERVICE는 beauty=false로 파생 — 시드·수집·서빙 모수에서 자동 제외된다.
 */
public enum BeautyClass {
    /** 뷰티 제품 콘텐츠 중심 개인 크리에이터 — 시딩·협찬 타깃. */
    INFLUENCER,
    /** 뷰티 제품 제작·판매 회사(브랜드·쇼핑몰) — 컨택 타깃. */
    COMPANY,
    /** 뷰티 영역이지만 시술·서비스 중심(병원·에스테틱·헤어·네일 업체와 그 영역 개인) — 타깃 아님. */
    BEAUTY_SERVICE,
    /** 뷰티 콘텐츠 중심이 아닌 계정. */
    NOT_BEAUTY;

    public boolean beauty() {
        return this == INFLUENCER || this == COMPANY;
    }

    public boolean company() {
        return this == COMPANY;
    }
}
```

`Influencer.java` — `beautyCompany` 필드 선언 아래에 추가:

```java
    /** 4분류 원본(v2) — boolean은 이 값의 파생. NULL이면 미판정(구 3분류 시대 판정분 포함). */
    @Enumerated(EnumType.STRING)
    @Column(name = "beauty_class")
    private BeautyClass beautyClass;
```

`Influencer.java` — 클래스 하단(생성자 아래)에 추가:

```java
    /** 판정 결과 일괄 적용 — 파생 boolean을 beauty_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classify(BeautyClass cls, String source, String reason) {
        this.beautyClass = cls;
        this.beauty = cls.beauty();
        this.beautyCompany = cls.company();
        this.beautySource = source;
        this.beautyReason = reason;
    }
```

`crawler/src/main/resources/db/migration/V16__beauty_class.sql`:

```sql
-- 뷰티 판정 v2 — 4분류 원본(beauty_class). boolean(beauty/beauty_company)은 파생값으로 유지된다.
-- BEAUTY_SERVICE(시술·서비스: 병원·에스테틱·헤어·네일 업체와 그 영역 개인)는 beauty=false로 파생.
alter table influencer add column beauty_class text
    constraint influencer_beauty_class_check
    check (beauty_class in ('INFLUENCER', 'COMPANY', 'BEAUTY_SERVICE', 'NOT_BEAUTY'));
-- 기존 판정분 백필 없음 — 전환 직후 전체 초기화(MANUAL 포함) 후 새 기준으로 재판정한다
-- (deploy/scripts/reset-beauty-judgments.sql).
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests BeautyClassTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/domain/BeautyClass.java \
        crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java \
        crawler/src/main/resources/db/migration/V16__beauty_class.sql \
        crawler/src/test/java/com/celfit/crawler/crawling/domain/BeautyClassTest.java
git commit -m "feat(crawler): 뷰티 판정 4분류 BeautyClass + beauty_class 컬럼(V16)"
```

---

### Task 2: Verdict 4분류 전환 + 프롬프트·파서 개정

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java` (buildPrompt·parse)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java` (RESPONSE_SCHEMA만)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudgeTest.java`

**Interfaces:**
- Consumes: Task 1의 `BeautyClass`
- Produces: `record Verdict(String username, BeautyClass beautyClass, String reason)` + 편의 위임 `boolean beauty()` / `boolean company()` (BeautyJob·기존 로그 코드가 사용)

**주의:** Verdict 시그니처 변경으로 이 태스크 완료 전까지 `BeautyJob`(Task 3에서 수정)과 다른 어댑터 테스트가 컴파일 깨질 수 있다 — 이 태스크 안에서 `new Verdict(u, true/false, true/false, r)` 생성 지점을 전부 4분류 생성자로 치환한다. 치환 규칙: `(u, true, false, r)`→`(u, BeautyClass.INFLUENCER, r)`, `(u, true, true, r)`→`(u, BeautyClass.COMPANY, r)`, `(u, false, false, r)`→`(u, BeautyClass.NOT_BEAUTY, r)`. 대상 파일: `ClaudeCliBeautyJudgeTest`, `ClaudeApiBeautyJudgeTest`, `GeminiBeautyJudgeTest`, `BeautyJobTest`, `BeautySelectionIntegrationTest` 중 Verdict를 만드는 곳 전부(`grep -rn "new BeautyJudge.Verdict\|new Verdict" crawler/src`로 확인). BeautyJob 본문은 `v.beauty()`/`v.company()` 위임 메서드 덕에 이 태스크에서는 컴파일이 유지된다(저장 로직 개정은 Task 3).

- [ ] **Step 1: 실패하는 테스트 작성 — ClaudeCliBeautyJudgeTest를 4분류로 개정**

기존 테스트 파일을 아래 내용으로 교체:

```java
package com.celfit.crawler.crawling.adapter.out.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.domain.BeautyClass;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 코드펜스로_감싼_4분류_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","class":"INFLUENCER","reason":"메이크업 크리에이터"},
                 {"username":"b","class":"COMPANY","reason":"화장품 브랜드 공식몰"},
                 {"username":"c","class":"BEAUTY_SERVICE","reason":"피부과 시술 홍보 계정"},
                 {"username":"d","class":"NOT_BEAUTY","reason":"여행 계정"}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업 크리에이터"),
                new BeautyJudge.Verdict("b", BeautyClass.COMPANY, "화장품 브랜드 공식몰"),
                new BeautyJudge.Verdict("c", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보 계정"),
                new BeautyJudge.Verdict("d", BeautyClass.NOT_BEAUTY, "여행 계정"));
    }

    @Test
    void Verdict의_파생_boolean은_BeautyClass_규칙을_따른다() {
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, null).beauty()).isTrue();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.COMPANY, null).company()).isTrue();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.BEAUTY_SERVICE, null).beauty()).isFalse();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.NOT_BEAUTY, null).beauty()).isFalse();
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"class\":\"INFLUENCER\",\"reason\":null}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, null));
    }

    @Test
    void username_누락이나_class가_4분류가_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"class":"INFLUENCER","reason":"x"},
                 {"username":"ok","class":"BEAUTY"},
                 {"username":"legacy","beauty":true},
                 {"username":"good","class":"NOT_BEAUTY","reason":"r"}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", BeautyClass.NOT_BEAUTY, "r"));
    }

    @Test
    void 배열이_아니거나_JSON이_아니면_ApifyException() {
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "{\"oops\":1}"))
                .isInstanceOf(ApifyException.class);
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "죄송합니다, 판정할 수 없습니다."))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 프롬프트에_판정_목적과_카드_JSON과_4분류_출력_형식_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio", List.of("입술 보습 꿀템"))));
        assertThat(p).contains("\"username\":\"u1\"").contains("입술 보습 꿀템").contains("JSON 배열만")
                .contains("INFLUENCER").contains("COMPANY").contains("BEAUTY_SERVICE").contains("NOT_BEAUTY")
                .contains("시딩·협찬").contains("피부과").contains("captions는 최근 게시물 캡션");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests ClaudeCliBeautyJudgeTest`
Expected: 컴파일 실패 (Verdict 시그니처 불일치)

- [ ] **Step 3: 구현**

`BeautyJudge.java`의 Verdict 주석·정의 교체:

```java
    /**
     * 4분류 판정 결과(v2) — 파생 boolean(beauty/company)은 BeautyClass 규칙을 위임한다.
     * BEAUTY_SERVICE(시술·서비스)는 beauty=false — 리스트업 세그먼트로만 남고 수집·유사발굴 제외.
     */
    record Verdict(String username, com.celfit.crawler.crawling.domain.BeautyClass beautyClass, String reason) {
        public boolean beauty() {
            return beautyClass.beauty();
        }

        public boolean company() {
            return beautyClass.company();
        }
    }
```

`ClaudeCliBeautyJudge.buildPrompt` 교체:

```java
    public static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
        return """
                너는 뷰티 마케팅 리스트업 서비스의 분류기다. 목적: 뷰티 제품(스킨케어·메이크업·향수·\
                헤어/바디케어 제품 등)을 시딩·협찬·광고할 인플루언서와, 그런 인플루언서를 필요로 하는 \
                뷰티 제품 회사를 찾는 것.
                다음 인스타그램 계정 프로필 목록(JSON)의 각 계정을 넷 중 하나로 분류하라:
                - INFLUENCER: 뷰티 제품 콘텐츠 중심의 개인 크리에이터. 광고·협찬 게시물만이 아니라 \
                오가닉 뷰티 콘텐츠를 올리는 개인도 포함.
                - COMPANY: 뷰티 제품을 제작·판매하는 회사(브랜드·쇼핑몰) 공식 계정
                - BEAUTY_SERVICE: 뷰티 영역이지만 시술·서비스 중심 — 피부과·성형외과·에스테틱·헤어샵/\
                미용실·네일샵·왁싱·속눈썹·반영구 등 시술을 파는 업체, 그리고 헤어 디자이너·네일 아티스트·\
                시술 후기 위주 계정 같은 시술·서비스 중심 개인
                - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정
                경계 규칙: 시술 업체가 자체 제품도 팔면 콘텐츠 주력 기준으로 — 시술·매장 홍보 중심이면 \
                BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY.
                captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
                캡션의 실제 콘텐츠 주제를 근거로 판정하라.
                출력은 JSON 배열만: [{"username":"...","class":"INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY","reason":"한 줄"}]
                입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

                """ + om.writeValueAsString(cards);
    }
```

`ClaudeCliBeautyJudge.parse`의 switch 교체 (import에 `com.celfit.crawler.crawling.domain.BeautyClass` 추가):

```java
            // 4분류 외 값(모델 일탈)은 건너뛴다 — 해당 계정은 미판정 유지, 다음 실행 재시도
            switch (cls) {
                case "INFLUENCER" -> out.add(new Verdict(username, BeautyClass.INFLUENCER, n.path("reason").asString(null)));
                case "COMPANY" -> out.add(new Verdict(username, BeautyClass.COMPANY, n.path("reason").asString(null)));
                case "BEAUTY_SERVICE" -> out.add(new Verdict(username, BeautyClass.BEAUTY_SERVICE, n.path("reason").asString(null)));
                case "NOT_BEAUTY" -> out.add(new Verdict(username, BeautyClass.NOT_BEAUTY, n.path("reason").asString(null)));
                default -> { }
            }
```

`GeminiBeautyJudge.RESPONSE_SCHEMA` 교체 (4분류 enum 명시):

```java
    static final String RESPONSE_SCHEMA = """
            {"type":"array","items":{"type":"object","properties":{
              "username":{"type":"string"},
              "class":{"type":"string","enum":["INFLUENCER","COMPANY","BEAUTY_SERVICE","NOT_BEAUTY"]},
              "reason":{"type":"string"}},
             "required":["username","class","reason"]}}""";
```

다른 테스트의 Verdict 생성 지점 치환 — `grep -rn "new BeautyJudge.Verdict\|new Verdict(" crawler/src`로 찾아 위 치환 규칙대로 수정한다 (`ClaudeApiBeautyJudgeTest`·`GeminiBeautyJudgeTest`·`BeautyJobTest`·`BeautySelectionIntegrationTest` 예상).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests ClaudeCliBeautyJudgeTest --tests GeminiBeautyJudgeTest --tests ClaudeApiBeautyJudgeTest`
Expected: PASS (컴파일 포함 전체 확인은 Step 5 직전 `./gradlew :crawler:test`)

- [ ] **Step 5: crawler 전체 테스트 후 커밋**

Run: `./gradlew :crawler:test`
Expected: PASS (BeautyJob 저장 로직은 아직 boolean 파생 위임으로 기존 동작 유지)

```bash
git add -A crawler/src
git commit -m "feat(crawler): 뷰티 판정 프롬프트·파서 4분류 개정 — 시술·서비스(BEAUTY_SERVICE) 분리"
```

---

### Task 3: BeautyJob 저장 로직 — beauty_class 저장 + Summary 구분

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

**Interfaces:**
- Consumes: `Influencer.classify(BeautyClass, String, String)` (Task 1), `Verdict.beautyClass()` (Task 2)
- Produces: `record Summary(int judgedBeauty, int judgedService, int judgedNotBeauty, int skippedNoProfile, int failedBatches)` — judgedBeauty는 INFLUENCER+COMPANY, judgedService는 BEAUTY_SERVICE, judgedNotBeauty는 NOT_BEAUTY만. (JobService는 `failedBatches()`와 toString만 사용 — 시그니처 변경 안전)

- [ ] **Step 1: 실패하는 테스트 추가**

`BeautyJobTest`에 아래 테스트 추가 (mock 기반 — 파일의 `qualified()`/`legacyProfile()` 헬퍼 재사용. import에 `com.celfit.crawler.crawling.domain.BeautyClass` 추가. 기존 테스트들의 `new BeautyJudge.Verdict(u, true/false, ...)` 생성 지점은 Task 2에서 이미 치환됨. Summary는 이 파일에서 접근자로만 쓰이므로 컴포넌트 추가는 기존 어서션을 깨지 않는다):

```java
    @Test
    void 판정_결과가_beauty_class와_파생_boolean으로_저장되고_Summary가_구분_집계한다() {
        Influencer inf1 = qualified(1L, "inf1");
        Influencer com1 = qualified(2L, "com1");
        Influencer svc1 = qualified(3L, "svc1");
        Influencer no1 = qualified(4L, "no1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf1, com1, svc1, no1));
        for (long id = 1; id <= 4; id++) {
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(id))
                    .thenReturn(Optional.of(legacyProfile(id, "이름", "bio")));
        }
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("inf1", BeautyClass.INFLUENCER, "메이크업 크리에이터"),
                new BeautyJudge.Verdict("com1", BeautyClass.COMPANY, "화장품 브랜드"),
                new BeautyJudge.Verdict("svc1", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보"),
                new BeautyJudge.Verdict("no1", BeautyClass.NOT_BEAUTY, "여행 계정")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(2);      // INFLUENCER + COMPANY
        assertThat(s.judgedService()).isEqualTo(1);     // BEAUTY_SERVICE
        assertThat(s.judgedNotBeauty()).isEqualTo(1);   // NOT_BEAUTY

        // BEAUTY_SERVICE — beauty_class 원본 저장 + beauty=false 파생(수집·시드 자동 제외)
        assertThat(svc1.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(svc1.getBeauty()).isFalse();
        assertThat(svc1.getBeautyCompany()).isFalse();
        assertThat(svc1.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(svc1.getBeautyJudgedAt()).isEqualTo(NOW);
        // 인플루언서·회사도 파생 boolean이 기존 규칙과 동일
        assertThat(inf1.getBeauty()).isTrue();
        assertThat(inf1.getBeautyCompany()).isFalse();
        assertThat(com1.getBeauty()).isTrue();
        assertThat(com1.getBeautyCompany()).isTrue();
        assertThat(no1.getBeauty()).isFalse();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests BeautyJobTest`
Expected: FAIL (Summary에 judgedService 없음 / beauty_class 미저장)

- [ ] **Step 3: 구현**

`BeautyJob.java` 수정:

Summary 교체:

```java
    public record Summary(int judgedBeauty, int judgedService, int judgedNotBeauty,
                          int skippedNoProfile, int failedBatches) {}
```

`run()`의 집계 변수·ChunkResult·반환 교체:

```java
    private record ChunkResult(int beauty, int service, int notBeauty) {}
```

```java
        int beauty = 0, service = 0, notBeauty = 0, failedBatches = 0;
```

루프 안 적용부:

```java
            int done = beauty + service + notBeauty;
            ChunkResult r = txTemplate.execute(status -> applyVerdicts(verdicts, byUsername, done, cards.size()));
            beauty += r.beauty();
            service += r.service();
            notBeauty += r.notBeauty();
            log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 시술·서비스 {} / 비뷰티 {}",
                    i, total, beauty, service, notBeauty);
```

반환: `return new Summary(beauty, service, notBeauty, skipped, failedBatches);`

`applyVerdicts` 교체 (import `com.celfit.crawler.crawling.domain.BeautyClass` 추가):

```java
    private ChunkResult applyVerdicts(List<BeautyJudge.Verdict> verdicts, Map<String, Influencer> byUsername,
                                      int done, int totalCards) {
        int beauty = 0, service = 0, notBeauty = 0;
        for (BeautyJudge.Verdict v : verdicts) {
            Influencer inf = byUsername.get(v.username());
            if (inf == null) continue;  // 응답이 지어낸 username — 무시
            inf.classify(v.beautyClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.reason());
            inf.setBeautyJudgedAt(clock.instant());  // rejudge의 '오래된 판정 우선' 기준
            influencers.save(inf);
            switch (v.beautyClass()) {
                case INFLUENCER, COMPANY -> beauty++;
                case BEAUTY_SERVICE -> service++;
                case NOT_BEAUTY -> notBeauty++;
            }
            done++;
            String label = switch (v.beautyClass()) {
                case INFLUENCER -> "뷰티(인플루언서)";
                case COMPANY -> "뷰티(회사)";
                case BEAUTY_SERVICE -> "뷰티(시술·서비스)";
                case NOT_BEAUTY -> "비뷰티";
            };
            log.info("뷰티 판정 ({}/{}) {} — {} ({})", done, totalCards, v.username(), label, v.reason());
        }
        return new ChunkResult(beauty, service, notBeauty);
    }
```

클래스 javadoc의 "3분류" 언급을 "4분류(BeautyClass)"로 갱신.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests BeautyJobTest --tests BeautySelectionIntegrationTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java \
        crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java
git commit -m "feat(crawler): BeautyJob이 beauty_class 저장 + Summary 시술·서비스 구분 집계"
```

---

### Task 4: 수동 오버라이드 4분류 (컨트롤러 + 명단 UI)

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java` (`influencers()`에 4분류 목록 모델 추가)
- Modify: `crawler/src/main/resources/templates/influencers.html`
- Modify: `crawler/src/main/resources/static/css/admin.css`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyControllerTest.java`

**Interfaces:**
- Consumes: `Influencer.classify(...)`, `BeautyClass`
- Produces: `POST /ui/influencers/{id}/beauty` 파라미터가 `beauty`/`company` boolean → `beautyClass=INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY` 하나로 바뀐다 (Spring이 문자열→enum 자동 바인딩)

- [ ] **Step 1: 실패하는 테스트 작성 — 파일 교체**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InfluencerBeautyControllerTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerBeautyController controller = new InfluencerBeautyController(influencers);

    @Test
    void 수동_판정은_beauty_class와_파생값과_MANUAL_출처를_기록한다() {
        Influencer inf = new Influencer("a");
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "이전 판정");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        String view = controller.override(1L, BeautyClass.BEAUTY_SERVICE, 2, null, new RedirectAttributesModelMap());

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautyCompany()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getBeautyReason()).isEqualTo("수동 판정");
        assertThat(view).isEqualTo("redirect:/ui/influencers");
    }

    @Test
    void 수동으로_뷰티_회사로_판정할_수_있다() {
        Influencer inf = new Influencer("brand");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        controller.override(1L, BeautyClass.COMPANY, 0, null, new RedirectAttributesModelMap());

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.COMPANY);
        assertThat(inf.getBeauty()).isTrue();
        assertThat(inf.getBeautyCompany()).isTrue();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
    }

    @Test
    void 없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.override(9L, BeautyClass.INFLUENCER, 0, null,
                new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests InfluencerBeautyControllerTest`
Expected: 컴파일 실패 (override 시그니처 불일치)

- [ ] **Step 3: 구현**

`InfluencerBeautyController.override` 교체 (클래스 javadoc "3분류"→"4분류" 갱신, import `BeautyClass` 추가):

```java
    @PostMapping("/ui/influencers/{id}/beauty")
    public String override(@PathVariable Long id, @RequestParam BeautyClass beautyClass,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(required = false) List<InfluencerStatus> status,
                           RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classify(beautyClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정");
        influencers.save(inf);
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        return "redirect:/ui/influencers";
    }
```

`UiController.influencers()` — `model.addAttribute("statuses", JUDGED_STATUSES);` 옆에 추가 (import `com.celfit.crawler.crawling.domain.BeautyClass`):

```java
        // 수동 오버라이드 버튼용 4분류 목록 — 템플릿 하드코딩 대신 enum 단일 원천
        model.addAttribute("beautyClasses", BeautyClass.values());
```

`influencers.html`의 뷰티 `<td>`(40–66행) 교체:

```html
        <td th:with="cls=${row.influencer.beautyClass != null ? row.influencer.beautyClass.name() : null}">
            <!-- v2 판정(beauty_class 있음) — 4분류 배지 -->
            <span th:if="${cls != null}" class="badge"
                  th:classappend="${cls == 'INFLUENCER'} ? 'BEAUTY' : ${cls == 'COMPANY'} ? 'BEAUTY_COMPANY' : ${cls == 'BEAUTY_SERVICE'} ? 'BEAUTY_SERVICE' : 'NOT_BEAUTY'"
                  th:text="${cls == 'INFLUENCER'} ? '뷰티' : ${cls == 'COMPANY'} ? '뷰티 회사' : ${cls == 'BEAUTY_SERVICE'} ? '시술·서비스' : '뷰티 아님'"
                  th:title="${row.influencer.beautyReason}"></span>
            <!-- 구 3분류 시대 판정분(재판정 전 과도기) — boolean 기반 표시 -->
            <span th:if="${cls == null and row.influencer.beauty != null}" class="badge"
                  th:classappend="${row.influencer.beauty} ? (${row.influencer.beautyCompany} ? 'BEAUTY_COMPANY' : 'BEAUTY') : 'NOT_BEAUTY'"
                  th:text="${row.influencer.beauty} ? (${row.influencer.beautyCompany} ? '뷰티 회사(구)' : '뷰티(구)') : '뷰티 아님(구)'"
                  th:title="${row.influencer.beautyReason}"></span>
            <span th:if="${cls == null and row.influencer.beauty == null}">—</span>
            <form method="post" th:action="@{|/ui/influencers/${row.influencer.id}/beauty|}"
                  style="display:inline" th:each="target : ${beautyClasses}"
                  th:unless="${cls == target.name()}">
                <input type="hidden" name="page" th:value="${page.number}"/>
                <input type="hidden" th:each="s : ${status}" name="status" th:value="${s}"/>
                <input type="hidden" name="beautyClass" th:value="${target.name()}"/>
                <button type="submit"
                        th:text="${target.name() == 'INFLUENCER'} ? '뷰티' : ${target.name() == 'COMPANY'} ? '회사' : ${target.name() == 'BEAUTY_SERVICE'} ? '시술' : '아님'"></button>
            </form>
        </td>
```

`admin.css` — `.badge.NOT_BEAUTY` 규칙 아래에 추가:

```css
.badge.BEAUTY_SERVICE { color: var(--cat-feed); background: color-mix(in srgb, var(--cat-feed) 10%, transparent); }
```

- [ ] **Step 4: 통과 확인 (UI 스모크 포함)**

Run: `./gradlew :crawler:test --tests InfluencerBeautyControllerTest --tests UiSmokeTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java \
        crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java \
        crawler/src/main/resources/templates/influencers.html \
        crawler/src/main/resources/static/css/admin.css \
        crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyControllerTest.java
git commit -m "feat(crawler): 명단 수동 오버라이드·배지 4분류 확장"
```

---

### Task 5: 대시보드 BEAUTY_SERVICE 타일

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java`

**Interfaces:**
- Produces: `InfluencerRepository.countByStatusAndBeautyClass(InfluencerStatus status, BeautyClass beautyClass)` (Spring Data 파생 쿼리)
- Produces: `StatusSummary`에 `long beautyService` 컴포넌트 추가 (`beautyCompany` 다음 위치)

- [ ] **Step 1: 구현** (집계 조립 코드 — 기존에 StatusService 단위 테스트가 없고 UiSmokeTest가 렌더링을 커버한다)

`InfluencerRepository`에 추가 (import `BeautyClass`):

```java
    /** 뷰티 판정 4분류 집계 — 대시보드 타일용(BEAUTY_SERVICE 등). */
    long countByStatusAndBeautyClass(InfluencerStatus status, BeautyClass beautyClass);
```

`StatusService.StatusSummary` — `beautyCompany` 다음에 `long beautyService,` 추가. `summary()`의 생성자 호출에 맞춰 추가:

```java
                influencers.countBeautyCompanies(InfluencerStatus.QUALIFIED),
                influencers.countByStatusAndBeautyClass(InfluencerStatus.QUALIFIED, BeautyClass.BEAUTY_SERVICE),
                influencers.countByStatusAndBeauty(InfluencerStatus.QUALIFIED, false),
```

`UiController.statusTilesFragment`의 ③ 그룹 교체 — BEAUTY_SERVICE 타일 추가, NOT_BEAUTY는 서비스 제외분(`beautyFalse - beautyService`; 구 판정분(beauty_class NULL)은 NOT_BEAUTY 쪽에 잡힌다 — 전체 재판정 후에는 정확히 일치):

```java
                new StatusTileGroup("③ 뷰티 판정 — beauty가 가른 결과 (QUALIFIED 내)", java.util.List.of(
                        new StatusTile("BEAUTY", s.beautyInfluencer(),
                                "뷰티 인플루언서 · 수집·유사발굴 대상"),
                        new StatusTile("BEAUTY_COMPANY", s.beautyCompany(),
                                "뷰티 회사 · 리스트업 전용(수집 제외)"),
                        new StatusTile("BEAUTY_SERVICE", s.beautyService(),
                                "시술·서비스(병원·에스테틱·헤어·네일 등) · 타깃 제외"),
                        new StatusTile("NOT_BEAUTY", s.beautyFalse() - s.beautyService(),
                                "비뷰티 · 수집 제외"),
                        new StatusTile("UNJUDGED", s.beautyUnjudged(),
                                "미판정 · 뷰티판정 대기"))),
```

- [ ] **Step 2: 테스트 확인**

Run: `./gradlew :crawler:test --tests UiSmokeTest --tests "com.celfit.crawler.dashboard.*"`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java \
        crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java \
        crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java
git commit -m "feat(crawler): 대시보드에 시술·서비스(BEAUTY_SERVICE) 타일 추가"
```

---

### Task 6: judge 기본값 claude-api 전환 + 전체 재판정 스크립트 + 문서

**Files:**
- Modify: `crawler/src/main/resources/application.yml` (crawler.beauty.judge)
- Create: `deploy/scripts/reset-beauty-judgments.sql`
- Modify: `ARCHITECTURE.md` (§3 influencer 행·§7 결정 기록)
- Modify: `docs/superpowers/plans/2026-07-20-beauty-class-v2.md` (상태 헤더)

**Interfaces:**
- Consumes: 기존 `ClaudeApiBeautyJudge`(`crawler.beauty.judge=claude-api`, `ANTHROPIC_AUTH_TOKEN` 구독 우선) — 코드 변경 없음

- [ ] **Step 1: application.yml 기본값 전환**

`crawler.beauty.judge` 행 교체:

```yaml
    judge: claude-api # 판정 구현 — claude-api(기본, Anthropic SDK: ANTHROPIC_AUTH_TOKEN 구독 우선·ANTHROPIC_API_KEY 폴백, 모델 crawler.beauty.claude-model 기본 claude-haiku-4-5) | gemini(롤백: GEMINI_API_KEY) | claude-cli(로컬 Claude CLI 롤백)
```

- [ ] **Step 2: 재판정 초기화 SQL 작성**

`deploy/scripts/reset-beauty-judgments.sql`:

```sql
-- 뷰티 판정 v2 전환 — 판정분 전체 초기화(MANUAL 포함, 2026-07-20 스펙 §4-5의 일회성 운영 작업).
-- 초기화 후 서버 어드민에서 BEAUTY 잡을 트리거하면 새 4분류 기준으로 재판정된다
-- (배치 한도는 어드민 설정 beauty.batch-limit로 조절).
--
-- 실행(오라클 서버, raw DB 컨테이너):
--   docker compose exec -T postgres-raw psql -U crawler -d crawler < deploy/scripts/reset-beauty-judgments.sql
begin;
update influencer
   set beauty          = null,
       beauty_company  = null,
       beauty_class    = null,
       beauty_source   = null,
       beauty_reason   = null,
       beauty_judged_at = null
 where beauty is not null or beauty_source is not null;
commit;
```

- [ ] **Step 3: 문서 갱신**

- `ARCHITECTURE.md` §3 raw DB 표의 `influencer` 행: "뷰티 판정 beauty/beauty_company/beauty_judged_at" → "뷰티 판정 4분류 beauty_class(+파생 beauty/beauty_company)/beauty_judged_at".
- `ARCHITECTURE.md` §7 결정 기록에 한 줄 추가:
  `- 2026-07-20 뷰티 판정 v2 — 4분류(beauty_class: INFLUENCER/COMPANY/BEAUTY_SERVICE/NOT_BEAUTY)로 시술·서비스 분리, boolean은 파생 유지(다운스트림 무변경). judge 기본 claude-api(구독) 전환. 스펙: docs/superpowers/specs/2026-07-20-beauty-class-v2-design.md`
- 이 계획 문서의 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨(재판정 운영 작업 대기)`로 갱신.

- [ ] **Step 4: 전체 테스트**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/resources/application.yml deploy/scripts/reset-beauty-judgments.sql \
        ARCHITECTURE.md docs/superpowers/plans/2026-07-20-beauty-class-v2.md
git commit -m "feat(crawler): 뷰티 판정 기본 claude-api(구독) 전환 + 전체 재판정 초기화 SQL"
```

---

## 배포·운영 절차 (구현 완료 후, 사람이 하는 일)

0. (선택) 로컬에서 `ANTHROPIC_AUTH_TOKEN` export 후 `ClaudeApiBeautyJudgeSmokeTest`를 수동 실행해 새 프롬프트의 실판정 품질(피부과·헤어샵 표본이 BEAUTY_SERVICE로 나오는지) 확인 — 스펙 §6.
1. develop 대상 PR 생성·머지 → 서버 배포(deploy/scripts/deploy.sh 경로).
2. 서버 환경변수 `ANTHROPIC_AUTH_TOKEN` 존재 확인 (로컬 맥에서 `claude setup-token` 발급 — API 키 폴백으로 새지 않게 authToken 필수). crawler 기동 로그에서 `Claude 판정 인증 모드: 구독(OAuth)` 확인.
3. `reset-beauty-judgments.sql` 실행 (위 주석의 docker compose exec 명령).
4. 서버 crawler 어드민(`/ui`)에서 BEAUTY 잡 트리거 — batch-limit 단위로 반복 실행하며 대시보드 ③ 그룹에서 4분류 분포 확인.
5. 명단 페이지에서 피부과·에스테틱 등이 `시술·서비스` 배지로 빠지는지 표본 확인. 오분류는 수동 오버라이드(4버튼)로 정정.
