# 홈/리빙 카테고리 축 추가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 판정 3축화 — 뷰티·F&B에 홈/리빙 축을 F&B 병렬 복제 패턴으로 추가한다
([스펙](../../specs/2026-08-27-home-living-category-design.md)).

**Architecture:** `influencer` 테이블에 `home_living_*` 병렬 컬럼 8개(expand만), 판정 프롬프트
1콜 3축 동시 판정, 백필 경로 추가, 수집·시드는 `home-living.pipeline-enabled` 토글(기본 off),
어드민 타일·필터·오버라이드 대칭 확장. F&B 재판정 안정화(스펙 2026-08-27)의 정착 가드를
홈/리빙 축에 처음부터 적용.

**Tech Stack:** Java 21 · Spring Boot 4.1 · JPA/JPQL · Flyway · Thymeleaf · JUnit5+Mockito

## Global Constraints

- 마이그레이션은 **UTC 타임스탬프 채번**: `date -u +%Y%m%d%H%M%S`로 생성 (CLAUDE.md).
- 스키마는 **expand만** — DROP·RENAME·타입변경 금지 (expand-contract).
- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(crawler):`/`docs:` 등.
- 테스트는 모듈 단위: `./gradlew :crawler:test` (전체 `./gradlew test`는 PR 직전만).
- 이 머신은 Docker Desktop — `DOCKER_HOST` 미설정이 정답 (메모리 08-09).
- 기존 뷰티·F&B 저장값·enum 이름은 절대 건드리지 않는다.
- `BeautyJob`·`JobName.BEAUTY` 이름 유지 — 크론 키·어드민이 물려 있다.
- Java 명명: 필드 `homeLiving*`, DB 컬럼 `home_living_*`, 쿼리 파라미터 `homeLiving`,
  토글 키 `home-living.pipeline-enabled`, 프롬프트 JSON 키 `home_living`.

---

### Task 1: 마이그레이션 + Influencer 엔티티 홈/리빙 축

**Files:**
- Create: `crawler/src/main/resources/db/migration/V<UTC타임스탬프>__influencer_home_living.sql`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/domain/CategoryClassTest.java` (있으면 확장, 없으면 생성)

**Interfaces:**
- Produces: `Influencer.getHomeLivingClass()/getHomeLivingSource()/getHomeLivingCaptionCount()/getHomeLiving()/getHomeLivingCompany()` (Lombok Getter/Setter),
  `Influencer.classifyHomeLiving(CategoryClass cls, String source, String reason, String basis)`,
  `Influencer.setHomeLivingJudgedAt(Instant)`, `Influencer.setHomeLivingCaptionCount(Short)`
- Consumes: 기존 `CategoryClass` enum (변경 없음)

- [ ] **Step 1: 실패하는 테스트 작성** — `classifyHomeLiving` 파생 boolean 규칙 (classifyFnb와 대칭)

`crawler/src/test/java/com/celfit/crawler/crawling/domain/CategoryClassTest.java`에 추가
(파일이 없으면 클래스 신규 생성, 있으면 테스트 메서드만 추가):

```java
package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassTest {

    @Test
    void classifyHomeLiving은_파생_boolean을_class와_일치시킨다() {
        Influencer inf = new Influencer("acc");
        inf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "집꾸미기", "CAPTION");
        assertThat(inf.getHomeLiving()).isTrue();
        assertThat(inf.getHomeLivingCompany()).isFalse();
        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getHomeLivingSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(inf.getHomeLivingReason()).isEqualTo("집꾸미기");
        assertThat(inf.getHomeLivingBasis()).isEqualTo("CAPTION");

        inf.classifyHomeLiving(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_MANUAL, "가구 브랜드", null);
        assertThat(inf.getHomeLiving()).isTrue();
        assertThat(inf.getHomeLivingCompany()).isTrue();

        inf.classifyHomeLiving(CategoryClass.SERVICE, Influencer.BEAUTY_SOURCE_CLAUDE, "인테리어 시공", "BIO");
        assertThat(inf.getHomeLiving()).isFalse();
        assertThat(inf.getHomeLivingCompany()).isFalse();

        inf.classifyHomeLiving(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "일상 계정", "CAPTION");
        assertThat(inf.getHomeLiving()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.domain.CategoryClassTest"`
Expected: 컴파일 실패 — `classifyHomeLiving` 미정의

- [ ] **Step 3: Influencer 엔티티 확장**

`Influencer.java`의 fnb 필드 블록(`fnbBasis` 필드) 바로 뒤에 추가:

```java
    /** 홈/리빙 계정 여부 — NULL이면 미판정(백필 대상). 수집·시드 편입은 home-living.pipeline-enabled 토글이 게이트. */
    @Column(name = "home_living")
    private Boolean homeLiving;

    /** 홈/리빙 회사(가구·리빙 브랜드·쇼핑몰) 여부 — home_living=true의 하위 구분. 토글 on이어도 수집 제외. */
    @Column(name = "home_living_company")
    private Boolean homeLivingCompany;

    /** 홈/리빙 5분류 원본 — boolean은 이 값의 파생. NULL이면 홈/리빙 축 미판정. */
    @Enumerated(EnumType.STRING)
    @Column(name = "home_living_class")
    private CategoryClass homeLivingClass;

    @Column(name = "home_living_source")
    private String homeLivingSource;

    /** 홈/리빙 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "home_living_reason")
    private String homeLivingReason;

    /** 홈/리빙 축 판정 시각. */
    @Column(name = "home_living_judged_at")
    private Instant homeLivingJudgedAt;

    /** 홈/리빙 판정에 실제로 넣은 캡션 건수 — 정착 규칙(캡션 0건 → 1회 업그레이드)의 재료. */
    @Column(name = "home_living_caption_count")
    private Short homeLivingCaptionCount;

    /** 홈/리빙 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY. */
    @Column(name = "home_living_basis")
    private String homeLivingBasis;
```

`classifyFnb` 메서드 뒤에 추가:

```java
    /** 홈/리빙 축 판정 적용 — 파생 boolean을 home_living_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classifyHomeLiving(CategoryClass cls, String source, String reason, String basis) {
        this.homeLivingClass = cls;
        this.homeLiving = cls.inCategory();
        this.homeLivingCompany = cls.company();
        this.homeLivingSource = source;
        this.homeLivingReason = reason;
        this.homeLivingBasis = basis;
    }
```

- [ ] **Step 4: 마이그레이션 파일 생성**

번호 채번: `date -u +%Y%m%d%H%M%S` 실행 결과를 파일명에 사용.
`crawler/src/main/resources/db/migration/V<채번>__influencer_home_living.sql`:

```sql
-- 인플루언서 홈/리빙 축 판정 컬럼 (스펙 2026-08-27) — expand만, 파괴 없음.
-- fnb 축 컬럼 세트(V20260824082708)와 대칭. NULL = 홈/리빙 축 미판정(백필 대상).
ALTER TABLE influencer
  ADD COLUMN home_living boolean,
  ADD COLUMN home_living_company boolean,
  ADD COLUMN home_living_class text,
  ADD COLUMN home_living_source text,
  ADD COLUMN home_living_reason text,
  ADD COLUMN home_living_basis text,
  ADD COLUMN home_living_judged_at timestamptz,
  ADD COLUMN home_living_caption_count smallint;

-- 값 방어는 fnb 축 관용구 그대로 — home_living_class는 CategoryClass 5분류,
-- home_living_basis는 LLM이 밝힌 판정 주근거.
ALTER TABLE influencer ADD CONSTRAINT influencer_home_living_class_check
    CHECK (home_living_class IN ('INFLUENCER', 'COMPANY', 'SERVICE', 'FOREIGN_INFLUENCER', 'NONE'));
ALTER TABLE influencer ADD CONSTRAINT influencer_home_living_basis_check
    CHECK (home_living_basis IN ('CAPTION', 'BIO', 'CATEGORY_ONLY'));

-- 홈/리빙 수집·시드 파이프라인 게이트 — 기본 off (스펙 §4). 모수·비용 확인 후 수동 UPDATE로 on.
INSERT INTO app_setting(key, value) VALUES ('home-living.pipeline-enabled', 'false')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 5: 테스트 통과 확인 (마이그레이션 적용 포함)**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.domain.CategoryClassTest" --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: PASS (통합 테스트가 Testcontainers에 Flyway 전체 재생 — 마이그레이션 문법·제약 검증)

- [ ] **Step 6: Commit**

```bash
git add crawler/src/main/resources/db/migration crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java crawler/src/test/java/com/celfit/crawler/crawling/domain/CategoryClassTest.java
git commit -m "feat(crawler): influencer 홈/리빙 축 컬럼·엔티티 추가 (판정 3축화 §1)"
```

---

### Task 2: Verdict 3축 확장 + 프롬프트·파서

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeApiBeautyJudge.java` (MAX_TOKENS만)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java` (maxOutputTokens만)
- Modify: 기존 `new BeautyJudge.Verdict(...)` 7-인자 호출부 전부 (테스트 포함 — 컴파일러가 찾아준다)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudgeTest.java`

**Interfaces:**
- Produces: `BeautyJudge.Verdict(String username, BeautyClass beautyClass, String reason, String basis, CategoryClass fnbClass, String fnbReason, String fnbBasis, CategoryClass homeLivingClass, String homeLivingReason, String homeLivingBasis)` — 10-인자 record.
  Task 3~6이 `v.homeLivingClass()`/`v.homeLivingReason()`/`v.homeLivingBasis()`를 소비한다.
- Consumes: 없음 (Task 1과 독립 — 병행 가능)

- [ ] **Step 1: 실패하는 파서 테스트 작성**

`ClaudeCliBeautyJudgeTest.java`에 추가 (기존 테스트의 om 필드·패턴 재사용 — 파일 상단을 읽고 기존 헬퍼에 맞출 것):

```java
    @Test
    void 삼축_JSON을_파싱한다() {
        String out = """
                [{"username":"a",
                  "beauty":{"reason":"뷰티 리뷰","basis":"CAPTION","class":"INFLUENCER"},
                  "fnb":{"reason":"레시피","basis":"CAPTION","class":"INFLUENCER"},
                  "home_living":{"reason":"집꾸미기 콘텐츠","basis":"CAPTION","class":"INFLUENCER"}}]
                """;
        var verdicts = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.getFirst().homeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(verdicts.getFirst().homeLivingReason()).isEqualTo("집꾸미기 콘텐츠");
        assertThat(verdicts.getFirst().homeLivingBasis()).isEqualTo("CAPTION");
    }

    @Test
    void 홈리빙_축이_누락되면_그_축만_null이다() {
        String out = """
                [{"username":"a",
                  "beauty":{"reason":"뷰티","basis":"BIO","class":"INFLUENCER"},
                  "fnb":{"reason":"아님","basis":"BIO","class":"NONE"}}]
                """;
        var verdicts = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.getFirst().beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(verdicts.getFirst().homeLivingClass()).isNull();
    }

    @Test
    void 세_축_모두_무효면_건너뛴다() {
        String out = """
                [{"username":"a",
                  "beauty":{"class":"?"},"fnb":{"class":"?"},"home_living":{"class":"?"}}]
                """;
        assertThat(ClaudeCliBeautyJudge.parse(om, out)).isEmpty();
    }

    @Test
    void 프롬프트에_홈리빙_축_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("a", "이름", "카테고리", "bio", List.of())));
        assertThat(p).contains("home_living");
        assertThat(p).contains("집꾸미기");
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudgeTest"`
Expected: 컴파일 실패 — `homeLivingClass()` 미정의

- [ ] **Step 3: Verdict record 확장**

`BeautyJudge.java`의 Verdict를 다음으로 교체 (10-인자, javadoc의 "2축"을 "3축"으로):

```java
    /**
     * 3축 판정 결과 — beauty(뷰티 제품)·fnb(식품/음료 제품)·homeLiving(홈/리빙) 축을 독립
     * 판정한다(스펙 2026-08-23 §2, 2026-08-27 홈/리빙 §2). 축별 class는 모델 응답이 무효·누락이면
     * null — 호출자는 null 아닌 축만 적용한다(해당 축은 미판정으로 남아 다음 실행 재시도).
     * 파생 boolean은 각 enum 규칙에 위임.
     */
    record Verdict(String username, com.celfit.crawler.crawling.domain.BeautyClass beautyClass,
                   String reason, String basis,
                   com.celfit.crawler.crawling.domain.CategoryClass fnbClass,
                   String fnbReason, String fnbBasis,
                   com.celfit.crawler.crawling.domain.CategoryClass homeLivingClass,
                   String homeLivingReason, String homeLivingBasis) {
        public boolean beauty() {
            return beautyClass != null && beautyClass.beauty();
        }

        public boolean company() {
            return beautyClass != null && beautyClass.company();
        }
    }
```

- [ ] **Step 4: 기존 7-인자 호출부 일괄 수정**

`./gradlew :crawler:compileJava :crawler:compileTestJava`를 돌려 컴파일 에러 지점을 전부 찾아,
각 `new BeautyJudge.Verdict(u, bc, r, b, fc, fr, fb)` 호출 끝에 `, null, null, null`을 붙인다
(파서 프로덕션 코드는 Step 5에서 실값으로 바뀌므로 테스트 호출부만 남는다).
의미상 홈/리빙 판정이 필요한 테스트는 Task 3에서 실값을 넣는다 — 여기서는 컴파일 복구만.

- [ ] **Step 5: 프롬프트·파서 구현**

`ClaudeCliBeautyJudge.buildPrompt`:
1. 도입부 문장을 3축으로 교체 — `축에서 독립적으로 분류한다:` 뒤 나열에 홈/리빙 추가:

```
너는 인플루언서 마케팅 리스트업 서비스의 분류기다. 각 인스타그램 계정을 세 카테고리 \
축에서 독립적으로 분류한다: beauty(뷰티 제품 — 스킨케어·메이크업·향수·헤어/바디케어 \
제품 등), fnb(식품/음료 제품 — 가공식품·음료·건강기능식품·식재료 등), home_living(홈/리빙 — \
가구·인테리어 소품·주방/생활용품·홈데코 등). 한 계정이 여러 축에 해당할 수 있다(예: 뷰티 \
리뷰와 레시피를 함께 올리는 계정).
```

2. `[fnb 축 분류]` 블록 뒤에 삽입:

```
[home_living 축 분류]
- INFLUENCER: 캡션·bio를 한국어로 쓰는 홈/리빙 개인 크리에이터 — 두 부류 모두 포함한다. \
(1) 리빙 제품(가구·인테리어 소품·주방/생활용품·홈데코) 리뷰·공동구매·추천 계정, \
(2) 집꾸미기·홈스타일링·살림·정리수납·홈카페 콘텐츠 중심 계정(제품 리뷰가 주업이 아니어도 \
집·공간·살림이 콘텐츠의 중심이면 포함 — 예: 오늘의집류 집 기록 계정).
- FOREIGN_INFLUENCER: 홈/리빙 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
- COMPANY: 가구·리빙 제품을 제조·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
- SERVICE: 서비스 업체 공식 계정 — 인테리어 시공·리모델링·이사·입주청소·정리수납 대행·\
부동산 등, 그리고 시공 사례·견적 홍보 위주의 서비스 중심 개인
- NONE: 홈/리빙 콘텐츠 중심이 아닌 계정. 집이 배경으로만 등장하는 일상·가족·육아 계정은 \
홈/리빙이 아니다 — 콘텐츠의 주제가 집·공간·살림·리빙 제품인지로 판정하라.
```

3. 경계 규칙의 `어느 쪽도 아니면 beauty=NOT_BEAUTY, fnb=NONE이다.`를
   `어느 쪽도 아니면 beauty=NOT_BEAUTY, fnb=NONE, home_living=NONE이다.`로 교체.
   `두 축은 독립이다`를 `세 축은 독립이다`로 교체.
4. 출력 지시의 JSON 예시에 홈/리빙 추가:

```
출력은 JSON 배열만: [{"username":"...",\
"beauty":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY"},\
"fnb":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|SERVICE|NONE"},\
"home_living":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|SERVICE|NONE"}}]
```

`ClaudeCliBeautyJudge.parse`의 루프 본문을 교체:

```java
            String username = n.path("username").asString(null);
            if (username == null || username.isBlank()) continue;
            JsonNode b = n.path("beauty");
            JsonNode f = n.path("fnb");
            JsonNode h = n.path("home_living");
            BeautyClass beautyClass = parseBeautyClass(b.path("class").asString(null));
            CategoryClass fnbClass = parseCategoryClass(f.path("class").asString(null));
            CategoryClass homeLivingClass = parseCategoryClass(h.path("class").asString(null));
            // 세 축 모두 무효(모델 일탈)면 건너뛴다 — 해당 계정 전 축 미판정 유지, 다음 실행 재시도.
            // 일부 축만 무효면 그 축만 null — 유효한 축의 판정을 버릴 이유가 없다.
            if (beautyClass == null && fnbClass == null && homeLivingClass == null) continue;
            out.add(new Verdict(username, beautyClass, b.path("reason").asString(null),
                    normalizeBasis(b.path("basis").asString(null)),
                    fnbClass, f.path("reason").asString(null),
                    normalizeBasis(f.path("basis").asString(null)),
                    homeLivingClass, h.path("reason").asString(null),
                    normalizeBasis(h.path("basis").asString(null))));
```

토큰 한도 상향 (출력이 계정당 ~1.5배 — 스펙 §2):
- `ClaudeApiBeautyJudge.MAX_TOKENS`: `16384L` → `24576L`
- `GeminiBeautyJudge`: `gen.put("maxOutputTokens", 16384)` → `24576`

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.out.claude.*" --tests "com.celfit.crawler.crawling.adapter.out.gemini.*"`
Expected: PASS (기존 파서 테스트 + 신규 3축 테스트)

- [ ] **Step 7: Commit**

```bash
git add -A crawler/src/main/java/com/celfit/crawler/crawling/adapter/out crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java crawler/src/test
git commit -m "feat(crawler): 판정 프롬프트·파서 3축 확장 — home_living 축 추가 (§2)"
```

---

### Task 3: BeautyJob 3축 적용 — 백필·정착 가드·Summary

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 `classifyHomeLiving`/`homeLiving*` 게터·세터, Task 2의 10-인자 Verdict
- Produces: `BeautyJob.Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty, int fnbApplied, int fnbPositive, int homeLivingApplied, int homeLivingPositive, int skippedNoProfile, int failedBatches)`,
  `InfluencerRepository.findHomeLivingBackfillTargets(InfluencerStatus, Pageable)` — Task 5가 `countHomeLivingBackfillRemaining` 대칭 카운트를 만든다.

- [ ] **Step 1: 실패하는 테스트 작성** — `BeautyJobTest.java`에 추가.
  기존 `new BeautyJudge.Verdict(...)` 호출은 Task 2에서 `, null, null, null`이 붙어 있다 —
  아래 신규 테스트는 홈/리빙 실값을 쓴다.

```java
    @Test
    void 신규_판정은_세_축을_모두_적용한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict(
                "a", BeautyClass.NOT_BEAUTY, "뷰티 아님", "BIO",
                CategoryClass.NONE, "F&B 아님", "BIO",
                CategoryClass.INFLUENCER, "집꾸미기 계정", "BIO")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        assertThat(a.getBeautyClass()).isEqualTo(BeautyClass.NOT_BEAUTY);
        assertThat(a.getFnbClass()).isEqualTo(CategoryClass.NONE);
        assertThat(a.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(a.getHomeLiving()).isTrue();
        assertThat(a.getHomeLivingCompany()).isFalse();
        assertThat(a.getHomeLivingSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(a.getHomeLivingJudgedAt()).isEqualTo(NOW);
        assertThat(a.getHomeLivingCaptionCount()).isEqualTo((short) 0);
        assertThat(s.homeLivingApplied()).isEqualTo(1);
        assertThat(s.homeLivingPositive()).isEqualTo(1);
        verify(influencers, times(1)).save(a);
    }

    @Test
    void 홈리빙_백필은_홈리빙_축만_적용하고_뷰티_판정을_보존한다() {
        Influencer inf = qualified(1L, "kept");
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
        inf.classifyFnb(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "F&B 아님", "CAPTION");
        inf.setFnbCaptionCount((short) 3);   // 캡션 기반 F&B 판정 — 정착 가드로 보존돼야 한다
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findHomeLivingBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("kept",
                BeautyClass.NOT_BEAUTY, "모델이 딴소리", "BIO",
                CategoryClass.INFLUENCER, "모델이 딴소리2", "BIO",
                CategoryClass.INFLUENCER, "살림 계정", "BIO")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        // 뷰티 축 보존 (백필 마스크) — MANUAL INFLUENCER 그대로
        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        // F&B 축 보존 (정착 가드 — 캡션 기반 기존 판정은 재적용 금지)
        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.NONE);
        // 홈/리빙 축만 적용
        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(s.homeLivingApplied()).isEqualTo(1);
        assertThat(s.judgedBeauty()).isZero();
        assertThat(s.fnbApplied()).isZero();
    }

    @Test
    void 홈리빙_백필은_FnB_백필이_쓴_만큼만_남은_한도로_호출된다() {
        when(settings.beautyBatchLimit()).thenReturn(10);
        List<Influencer> newcomers = List.of(qualified(1L, "n1"), qualified(2L, "n2"));
        List<Influencer> fnbBackfill = List.of(qualified(3L, "f1"), qualified(4L, "f2"), qualified(5L, "f3"));
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(newcomers);
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(fnbBackfill);
        when(influencers.findHomeLivingBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                .thenReturn(Optional.empty());   // 재료 없음 — 카드 생성은 생략, 선정 호출만 검증

        job.run(TriggerType.MANUAL, false);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(influencers).findHomeLivingBackfillTargets(eq(InfluencerStatus.QUALIFIED), cap.capture());
        // 한도 10 - 신규 2 - F&B 백필 3 = 5
        assertThat(cap.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void 캡션_기반_홈리빙_판정은_재판정에서_덮이지_않는다() {
        Influencer inf = qualified(1L, "settled");
        inf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "비뷰티", "CAPTION");
        inf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "집꾸미기", "CAPTION");
        inf.setHomeLivingCaptionCount((short) 5);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findHomeLivingBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
                eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(), any(Pageable.class)))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("settled",
                BeautyClass.NOT_BEAUTY, "여전히 비뷰티", "BIO",
                null, null, null,
                CategoryClass.NONE, "이번엔 아니라 함", "BIO")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, true);   // rejudge 경로

        // 캡션 기반(count=5>0) 홈/리빙 판정은 자동 재적용 금지 — 노이즈 뒤집힘 차단(정착 규칙)
        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(s.homeLivingApplied()).isZero();
    }

    @Test
    void 캡션0_홈리빙_판정은_캡션이_생기면_업그레이드_재판정된다() {
        Influencer inf = qualified(1L, "upgrade");
        inf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "비뷰티", "BIO");
        inf.classifyHomeLiving(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "근거 부족", "BIO");
        inf.setHomeLivingCaptionCount((short) 0);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findHomeLivingBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
                eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(), any(Pageable.class)))
                .thenReturn(List.of(inf));
        // 이번 실행엔 릴스 캡션이 있다 — 업그레이드 조건(0건 → N건) 성립
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("biography", "bio");
        payload.put("latestPosts", List.of(Map.of("caption", "선반 조립 브이로그")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("upgrade",
                BeautyClass.NOT_BEAUTY, "비뷰티", "CAPTION",
                null, null, null,
                CategoryClass.INFLUENCER, "실측 캡션이 리빙", "CAPTION")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, true);   // rejudge 경로

        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getHomeLivingCaptionCount()).isEqualTo((short) 1);
        assertThat(s.homeLivingApplied()).isEqualTo(1);
    }

    @Test
    void 수동_홈리빙_판정은_어느_경로로도_덮이지_않는다() {
        Influencer inf = qualified(1L, "manual_hl");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf));
        inf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("manual_hl",
                BeautyClass.NOT_BEAUTY, "비뷰티", "BIO",
                CategoryClass.NONE, "F&B 아님", "BIO",
                CategoryClass.NONE, "모델이 뒤집으려 함", "BIO")));

        job.run(TriggerType.MANUAL, false);

        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getHomeLivingSource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
    }
```

`BeautySelectionIntegrationTest.java`에 백필 선정 쿼리 통합 테스트 추가 (기존 F&B 백필
선정 테스트 패턴을 파일에서 확인해 동일 헬퍼 사용 — 시나리오만 규정한다):

```java
    @Test
    void 홈리빙_백필은_뷰티_판정_완료이면서_홈리빙_미판정인_계정만_id순으로_선정한다() {
        // given: (1) beauty 판정됨 + home_living NULL → 선정
        //        (2) beauty NULL(신규 대기) → 미선정
        //        (3) beauty 판정됨 + home_living 판정됨 → 미선정
        // then: findHomeLivingBackfillTargets가 (1)만 id 순으로 반환
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: 컴파일 실패 — `findHomeLivingBackfillTargets`·`homeLivingApplied` 미정의

- [ ] **Step 3: InfluencerRepository 백필 쿼리 추가**

`findFnbBackfillTargets` 뒤에:

```java
    /**
     * 홈/리빙 백필 대상: 뷰티 축은 판정 완료지만 홈/리빙 축이 미판정인 계정 — 카테고리 확장
     * (스펙 2026-08-27 §3)의 기존 판정분 전체 재판정 경로. F&B 백필과 달리 fnb 축 상태는 묻지
     * 않는다 — fnb도 미판정이면 F&B 백필 경로가 먼저 집고, 그때 홈/리빙 축도 첫 판정으로 같이
     * 적용된다(선정 순서: 신규 → F&B 백필 → 홈/리빙 백필). id 순 Pageable로 결정적으로 소진한다.
     */
    @Query("select i from Influencer i where i.status = :status and i.beauty is not null "
            + "and i.homeLiving is null order by i.id")
    List<Influencer> findHomeLivingBackfillTargets(@Param("status") InfluencerStatus status, Pageable pageable);
```

- [ ] **Step 4: BeautyJob 구현**

1. `Summary` 교체:
```java
    public record Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty,
                          int fnbApplied, int fnbPositive, int homeLivingApplied, int homeLivingPositive,
                          int skippedNoProfile, int failedBatches) {}
```
2. `run()` 선정부 — `fnbOnly`를 `backfillOnly`로 rename(의미: 백필 경로 선정 = 뷰티 축 미적용)
   하고 F&B 백필 블록 뒤에 홈/리빙 백필 추가:
```java
        Set<String> backfillOnly = new HashSet<>();
        if (targets.size() < limit) {
            List<Influencer> backfill = influencers.findFnbBackfillTargets(
                    InfluencerStatus.QUALIFIED, PageRequest.of(0, limit - targets.size()));
            backfill.forEach(i -> backfillOnly.add(i.getUsername()));
            targets.addAll(backfill);
        }
        // 홈/리빙 백필 — 뷰티·F&B 판정 완료분의 홈/리빙 축을 채운다(스펙 2026-08-27 §3).
        // F&B 백필 뒤 순서 — F&B 백필 선정분은 홈/리빙 축도 첫 판정으로 같이 적용되므로 중복 선정은
        // byUsername 중복 방어가 거른다.
        if (targets.size() < limit) {
            List<Influencer> hlBackfill = influencers.findHomeLivingBackfillTargets(
                    InfluencerStatus.QUALIFIED, PageRequest.of(0, limit - targets.size()));
            hlBackfill.forEach(i -> backfillOnly.add(i.getUsername()));
            targets.addAll(hlBackfill);
        }
```
3. `run()` 카운터에 `homeLivingApplied`, `homeLivingPositive` 추가(applyVerdicts 지역 카운터도
   같은 이름), `logResponseGaps(chunk, verdicts, backfillOnly)`,
   `ChunkResult`에 `homeLivingApplied`/`homeLivingPositive` 필드 추가, 배치 로그 문자열에
   ` / 홈리빙 적용 {} (인플루언서·회사 {})` 추가.
4. `applyVerdicts` — F&B 가드 블록 뒤에 홈/리빙 정착 가드(스펙 §3, F&B 가드와 대칭):
```java
            // 홈/리빙 축 정착 규칙(스펙 2026-08-27 §3) — F&B 가드와 대칭: MANUAL 보호,
            // 첫 판정 또는 캡션 0건 → N건 업그레이드만 적용. 토글 on 이후의 매 주기
            // 재판정 뒤집힘(F&B 08-26 실측)을 예방적으로 차단한다.
            Short prevHlCap = inf.getHomeLivingCaptionCount();
            boolean hlFirstJudgment = inf.getHomeLivingClass() == null;
            boolean hlCaptionUpgrade = prevHlCap != null && prevHlCap == 0 && capCount > 0;
            boolean applyHomeLiving = v.homeLivingClass() != null
                    && !Influencer.BEAUTY_SOURCE_MANUAL.equals(inf.getHomeLivingSource())
                    && (hlFirstJudgment || hlCaptionUpgrade);
```
   스킵 조건을 `if (!applyBeauty && !applyFnb && !applyHomeLiving) continue;`로,
   적용 블록을 F&B 적용 블록 뒤에:
```java
            if (applyHomeLiving) {
                inf.classifyHomeLiving(v.homeLivingClass(), Influencer.BEAUTY_SOURCE_CLAUDE,
                        v.homeLivingReason(), v.homeLivingBasis());
                inf.setHomeLivingJudgedAt(clock.instant());
                inf.setHomeLivingCaptionCount(capCount);
                homeLivingApplied++;
                if (v.homeLivingClass().inCategory()) homeLivingPositive++;
            }
            String hlLabel = !applyHomeLiving ? "" : " / " + switch (v.homeLivingClass()) {
                case INFLUENCER -> "홈리빙(인플루언서)";
                case COMPANY -> "홈리빙(회사)";
                case SERVICE -> "홈리빙(서비스)";
                case FOREIGN_INFLUENCER -> "홈리빙(외국인)";
                case NONE -> "비홈리빙";
            };
```
   계정별 로그를 `log.info("뷰티 판정 ({}/{}) {} — {}{}{} ({})", done, totalCards, v.username(), beautyLabel, fnbLabel, hlLabel, reason);`로.
   `reason` 선정도 3축 폴백: `String reason = applyBeauty ? v.reason() : (applyFnb ? v.fnbReason() : v.homeLivingReason());`
5. `logResponseGaps` — `fnbGaps` 옆에 `hlGaps`(`v.homeLivingClass() == null`) 추가, 경고 문구에
   ` / 홈리빙축 {}건` 추가. 파라미터 이름 `fnbOnly` → `backfillOnly`.
6. 클래스 javadoc의 "두 카테고리 축(뷰티·F&B)"을 "세 카테고리 축(뷰티·F&B·홈/리빙)"으로,
   선정 경로 설명에 홈/리빙 백필 추가.
7. `Summary` 소비처 컴파일 에러 수정: `UiJobController`/`JobService`/`ScheduleRunner` 등에서
   Summary를 로그 문자열로 만들면 홈/리빙 카운트 추가 (`./gradlew :crawler:compileJava`가 찾아준다).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest" --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: PASS (기존 F&B 케이스 + 신규 홈/리빙 케이스 전부)

- [ ] **Step 6: Commit**

```bash
git add -A crawler/src
git commit -m "feat(crawler): BeautyJob 홈/리빙 축 적용 — 백필·정착 가드·Summary (§3)"
```

---

### Task 4: 수집·시드 게이트 — 토글 + 선정 쿼리 3축화

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` (includeFnb 쿼리 8개)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/CollectJob.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ReelsJob.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/SimilarJob.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java` (호출부 시그니처만 — 타일은 Task 5)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java`

**Interfaces:**
- Produces: `SettingsService.homeLivingPipelineEnabled(): boolean`,
  기존 8개 쿼리 시그니처에 `@Param("includeHomeLiving") boolean includeHomeLiving` 추가:
  `countBackfillPending`, `countTrackDue`, `findCollectTargets`, `findReelsTargets`,
  `countReelsDue`, `findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull`,
  `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull`,
  `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull`
- Consumes: Task 1의 `homeLiving`/`homeLivingCompany` 엔티티 필드

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`BeautySelectionIntegrationTest.java`에 추가 (기존 F&B 토글 게이트 테스트를 파일에서 찾아
같은 시드 헬퍼로 작성 — 시나리오):

```java
    @Test
    void 홈리빙_토글_off면_수집_선정에_홈리빙_계정이_없고_on이면_편입된다() {
        // given: beauty=false·fnb=false·home_living=true(회사 아님) QUALIFIED 계정 1명
        // when: findCollectTargets(revisitBefore, includeFnb=false, includeHomeLiving=false)
        // then: 미선정
        // when: findCollectTargets(revisitBefore, includeFnb=false, includeHomeLiving=true)
        // then: 선정. home_living_company=true인 계정은 토글 on이어도 미선정.
        // findReelsTargets·SIMILAR 시드 선정도 동일 시드로 동일 검증.
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: 컴파일 실패 — 3-인자 `findCollectTargets` 미정의

- [ ] **Step 3: 구현**

1. `SettingsService` — `FNB_PIPELINE_ENABLED` 아래 키 추가:
```java
    /** 홈/리빙 파이프라인 게이트 키 — 수집(collect·reels)·유사발굴 시드·비용 추정의 홈/리빙 편입 여부. */
    static final String HOME_LIVING_PIPELINE_ENABLED = "home-living.pipeline-enabled";
```
   `fnbPipelineEnabled()` 아래 메서드 추가:
```java
    /**
     * 홈/리빙 판정 통과 계정의 수집·시드 편입 여부(기본 false — 스펙 2026-08-27 §4).
     * 숫자 설정(KEYS·UI 목록)과 달리 boolean 런타임 토글 — on은 운영 수동 UPDATE.
     */
    @Transactional(readOnly = true)
    public boolean homeLivingPipelineEnabled() {
        return settings.findById(HOME_LIVING_PIPELINE_ENABLED)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElse(false);
    }
```
2. `InfluencerRepository` — 위 8개 쿼리의 JPQL 술어에서
   `or (:includeFnb = true and i.fnb = true and (i.fnbCompany is null or i.fnbCompany = false))` 뒤에
   한 줄씩 추가(8곳 전부 동일):
```java
            + "or (:includeHomeLiving = true and i.homeLiving = true and (i.homeLivingCompany is null or i.homeLivingCompany = false))) "
```
   (기존 fnb 줄 끝의 `))) `를 `)) `로 조정해 괄호 짝을 유지 — OR 그룹을 닫는 세 번째 괄호는
   홈/리빙 줄 끝으로 이동한다. 8곳 수정 후 통합 테스트가 JPQL 문법을 검증한다.)
   각 메서드에 `@Param("includeHomeLiving") boolean includeHomeLiving` 파라미터 추가, javadoc에
   `includeHomeLiving=true(home-living.pipeline-enabled 토글)면 홈/리빙 인플루언서(회사 제외)도 포함 — 스펙 2026-08-27 §4.` 문구 추가.
3. 호출부 — `settings.fnbPipelineEnabled()`를 넘기는 자리마다
   `settings.homeLivingPipelineEnabled()`를 추가 인자로:
   - `CollectJob.java:125` 부근 `findCollectTargets(revisitBefore, settings.fnbPipelineEnabled(), settings.homeLivingPipelineEnabled(), ...)`
   - `ReelsJob.java:89` 부근 동일
   - `SimilarJob.java:77` 부근 동일
   - `JobCostEstimator` — `includeFnb` 지역변수 옆에 `boolean includeHomeLiving = settings.homeLivingPipelineEnabled();` 추가 후 3곳 호출 확장
   - `StatusService.summary()` — `countBackfillPending`/`countTrackDue`/`countReelsDue` 호출 확장
   `./gradlew :crawler:compileJava`로 잔여 호출부를 모두 잡는다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest" --tests "com.celfit.crawler.crawling.application.service.CollectJob*" --tests "com.celfit.crawler.crawling.application.service.SimilarJob*" --tests "com.celfit.crawler.crawling.application.service.ReelsJob*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A crawler/src
git commit -m "feat(crawler): 수집·시드 선정 3축 게이트 — home-living.pipeline-enabled 토글 (§4)"
```

---

### Task 5: 대시보드 타일 — ③-4 홈/리빙 판정 + ③-3 3축 유니온 개편

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` (카운트 쿼리)
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java` (statusTilesFragment)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/port/out/CollectableDedupCountIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 엔티티 필드, Task 3의 백필 모수 정의
- Produces: `StatusSummary` 필드 개편 —
  추가: `homeLivingInfluencer, homeLivingCompany, homeLivingService, homeLivingForeign, homeLivingNone, homeLivingUnjudged` (6개, fnb 6개 뒤),
  유니온 그룹 교체: `beautyOnlyCollectable, fnbOnlyCollectable, bothCollectable` →
  `beautyOnlyCollectable, fnbOnlyCollectable, homeLivingOnlyCollectable, anyCollectable` (겹침 = any − 단독 3합).

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`CollectableDedupCountIntegrationTest.java`를 3축으로 확장 (기존 테스트 파일의 시드 헬퍼 사용):

```java
    @Test
    void 삼축_단독_카운트는_미판정을_비대상으로_센다() {
        // given: (1) beauty=true만(fnb·home_living NULL) → beautyOnly에 포함(핵심: NULL 함정)
        //        (2) home_living=true만(beauty=false, fnb NULL) → homeLivingOnly에 포함
        //        (3) beauty=true ∧ home_living=true → 어느 단독에도 미포함, any에는 포함
        // then: countBeautyOnlyCollectable=1, countHomeLivingOnlyCollectable=1,
        //       countAnyCollectable=3 → 겹침(any − 단독합) = 1
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.port.out.CollectableDedupCountIntegrationTest"`
Expected: 컴파일 실패 — `countHomeLivingOnlyCollectable`·`countAnyCollectable` 미정의

- [ ] **Step 3: 레포 카운트 쿼리 구현**

`InfluencerRepository`:
1. 홈/리빙 판정 그룹용 (F&B 것과 대칭):
```java
    /** 홈/리빙 판정 5분류 집계 — 대시보드 타일용(뷰티 축 countByStatusAndBeautyClass와 대칭). */
    long countByStatusAndHomeLivingClass(InfluencerStatus status, CategoryClass homeLivingClass);

    /** 대시보드 홈/리빙 판정 그룹용: 홈/리빙 인플루언서(회사 제외) 수. */
    @Query("select count(i) from Influencer i where i.status = :status and i.homeLiving = true "
            + "and (i.homeLivingCompany is null or i.homeLivingCompany = false)")
    long countHomeLivingInfluencers(@Param("status") InfluencerStatus status);

    /**
     * 대시보드 홈/리빙 판정 그룹용: 백필 잔여 수 — 백필 선정(findHomeLivingBackfillTargets)과
     * 같은 모수(뷰티 축 판정 완료 ∧ 홈/리빙 축 미판정)를 센다.
     */
    @Query("select count(i) from Influencer i where i.status = :status "
            + "and i.beauty is not null and i.homeLiving is null")
    long countHomeLivingBackfillRemaining(@Param("status") InfluencerStatus status);
```
2. 3축 유니온 — 기존 두 단독 쿼리에 홈/리빙 부정절 추가, 신규 2개, `countBothCollectable` 삭제:
```java
    /**
     * 대시보드 중복 제거 그룹용: 뷰티 수집 대상이면서 F&B·홈/리빙 수집 대상은 아닌 수.
     * 부정절은 명시 분해형 — NOT(x = true AND …)로 쓰면 미판정(NULL)이 NULL 평가로 빠져
     * 단독 카운트가 축소된다(설계 2026-08-25 §구현 주의점).
     */
    @Query("select count(i) from Influencer i where i.status = :status "
            + "and i.beauty = true and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and (i.fnb is null or i.fnb = false or i.fnbCompany = true) "
            + "and (i.homeLiving is null or i.homeLiving = false or i.homeLivingCompany = true)")
    long countBeautyOnlyCollectable(@Param("status") InfluencerStatus status);

    /** 대시보드 중복 제거 그룹용: F&B 수집 대상이면서 뷰티·홈/리빙 수집 대상은 아닌 수 — 위와 대칭. */
    @Query("select count(i) from Influencer i where i.status = :status "
            + "and i.fnb = true and (i.fnbCompany is null or i.fnbCompany = false) "
            + "and (i.beauty is null or i.beauty = false or i.beautyCompany = true) "
            + "and (i.homeLiving is null or i.homeLiving = false or i.homeLivingCompany = true)")
    long countFnbOnlyCollectable(@Param("status") InfluencerStatus status);

    /** 대시보드 중복 제거 그룹용: 홈/리빙 수집 대상이면서 뷰티·F&B 수집 대상은 아닌 수 — 위와 대칭. */
    @Query("select count(i) from Influencer i where i.status = :status "
            + "and i.homeLiving = true and (i.homeLivingCompany is null or i.homeLivingCompany = false) "
            + "and (i.beauty is null or i.beauty = false or i.beautyCompany = true) "
            + "and (i.fnb is null or i.fnb = false or i.fnbCompany = true)")
    long countHomeLivingOnlyCollectable(@Param("status") InfluencerStatus status);

    /**
     * 대시보드 중복 제거 그룹용: 세 축 중 하나라도 수집 대상인 수(유니온) — 겹침 타일은
     * 별도 "2축 이상" 쿼리 대신 (유니온 − 단독 3합)으로 계산한다(2^3 조합 열거 회피).
     */
    @Query("select count(i) from Influencer i where i.status = :status and ("
            + "(i.beauty = true and (i.beautyCompany is null or i.beautyCompany = false)) "
            + "or (i.fnb = true and (i.fnbCompany is null or i.fnbCompany = false)) "
            + "or (i.homeLiving = true and (i.homeLivingCompany is null or i.homeLivingCompany = false)))")
    long countAnyCollectable(@Param("status") InfluencerStatus status);
```
   `countBothCollectable`는 삭제 (사용처는 StatusService뿐 — 같은 커밋에서 함께 제거).

- [ ] **Step 4: StatusService·UiController 타일 구현**

`StatusSummary` record에 Interfaces대로 필드 추가·교체, `summary()`에서:
```java
                // 홈/리빙 축 타일 — F&B 타일과 대칭(토글 무관, 백필 진행률 확인용).
                influencers.countHomeLivingInfluencers(InfluencerStatus.QUALIFIED),
                influencers.countByStatusAndHomeLivingClass(InfluencerStatus.QUALIFIED, CategoryClass.COMPANY),
                influencers.countByStatusAndHomeLivingClass(InfluencerStatus.QUALIFIED, CategoryClass.SERVICE),
                influencers.countByStatusAndHomeLivingClass(InfluencerStatus.QUALIFIED, CategoryClass.FOREIGN_INFLUENCER),
                influencers.countByStatusAndHomeLivingClass(InfluencerStatus.QUALIFIED, CategoryClass.NONE),
                influencers.countHomeLivingBackfillRemaining(InfluencerStatus.QUALIFIED),
                // 중복 제거 그룹 — 3축 단독 + 유니온(겹침은 UiController에서 유니온 − 단독합)
                influencers.countBeautyOnlyCollectable(InfluencerStatus.QUALIFIED),
                influencers.countFnbOnlyCollectable(InfluencerStatus.QUALIFIED),
                influencers.countHomeLivingOnlyCollectable(InfluencerStatus.QUALIFIED),
                influencers.countAnyCollectable(InfluencerStatus.QUALIFIED),
```

`UiController.statusTilesFragment` — ③-2 그룹 뒤에 ③-4 그룹 추가, ③-3 그룹 교체:
```java
                new StatusTileGroup("③-4 홈/리빙 판정 — beauty 잡의 홈/리빙 축 (QUALIFIED 내)", java.util.List.of(
                        new StatusTile("BEAUTY", "홈/리빙", s.homeLivingInfluencer(),
                                "홈/리빙 인플루언서 · 수집 편입은 home-living.pipeline-enabled 토글(기본 off)"),
                        new StatusTile("BEAUTY_COMPANY", "홈/리빙 회사", s.homeLivingCompany(),
                                "가구·리빙 브랜드 · 리스트업 전용(수집 제외)"),
                        new StatusTile("BEAUTY_SERVICE", "서비스", s.homeLivingService(),
                                "서비스(인테리어 시공·이사·청소 등 업체) · 타깃 제외"),
                        new StatusTile("FOREIGN", "외국인", s.homeLivingForeign(),
                                "외국인 홈/리빙 인플루언서 · 한국 시장 타깃 제외"),
                        new StatusTile("NOT_BEAUTY", "홈/리빙 아님", s.homeLivingNone(),
                                "홈/리빙 아님 · 수집 제외"),
                        new StatusTile("UNJUDGED", "미판정", s.homeLivingUnjudged(),
                                "홈/리빙 미판정 · 백필 잔여"))),
                // 세 축의 수집 대상을 겹침 없이 나눠 센다 — 합계가 실제 방문 계정 총수(유니온).
                // 겹침은 유니온 − 단독 3합(2축 이상 동시 해당 전체 — 2^3 조합 세분화는 타일 낭비).
                new StatusTileGroup("③-3 수집 모수 — 뷰티 ∪ F&B ∪ 홈/리빙 (중복 제거)", java.util.List.of(
                        new StatusTile("BEAUTY", "뷰티만", s.beautyOnlyCollectable(),
                                "뷰티 수집 대상 · 다른 축 아님(미판정 포함)"),
                        new StatusTile("BEAUTY", "F&B만", s.fnbOnlyCollectable(),
                                "F&B 수집 대상 · 다른 축 아님"),
                        new StatusTile("BEAUTY", "홈·리빙만", s.homeLivingOnlyCollectable(),
                                "홈/리빙 수집 대상 · 다른 축 아님"),
                        new StatusTile("BEAUTY_SERVICE", "겹침", s.anyCollectable()
                                - s.beautyOnlyCollectable() - s.fnbOnlyCollectable() - s.homeLivingOnlyCollectable(),
                                "2축 이상 동시 수집 대상 · 중복 방문 없음(계정당 1회)"),
                        new StatusTile("QUALIFIED", "합계", s.anyCollectable(),
                                "유니온 · 실제 방문하게 될 계정 총수"))),
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.port.out.CollectableDedupCountIntegrationTest" --tests "com.celfit.crawler.dashboard.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A crawler/src
git commit -m "feat(crawler): 대시보드 홈/리빙 판정 타일·3축 유니온 개편 (§5)"
```

---

### Task 6: 명단 필터·수동 오버라이드

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` (명단 필터 쿼리 3종)
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java` (influencers 메서드)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java`
- Modify: `crawler/src/main/resources/templates/influencers.html`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `classifyHomeLiving`
- Produces: POST `/ui/influencers/{id}/homeLiving` (RequestParam `homeLivingClass`),
  GET `/ui/influencers` 쿼리 파라미터 `homeLiving`(다중값)

- [ ] **Step 1: 실패하는 테스트 작성** — `InfluencerBeautyControllerTest.java`에 추가
  (기존 F&B 오버라이드 테스트를 파일에서 확인해 동일 패턴 — MockMvc든 단위든 기존 스타일 유지):

```java
    @Test
    void 홈리빙_오버라이드는_MANUAL로_저장되고_필터를_보존한다() {
        // given: 저장된 인플루언서
        // when: POST /ui/influencers/{id}/homeLiving?homeLivingClass=INFLUENCER&homeLiving=UNJUDGED
        // then: homeLivingClass=INFLUENCER, homeLivingSource=MANUAL, homeLivingReason="수동 판정",
        //       redirect에 homeLiving 파라미터 보존
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.InfluencerBeautyControllerTest"`
Expected: FAIL (404 또는 컴파일 실패)

- [ ] **Step 3: 구현**

1. `InfluencerRepository` 명단 필터 3종 (F&B 것과 대칭):
```java
    /** 명단 홈/리빙 필터: 선택한 분류(home_living_class)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndHomeLivingClassIn(
            java.util.Collection<InfluencerStatus> statuses,
            java.util.Collection<CategoryClass> classes, Pageable pageable);

    /** 명단 홈/리빙 필터: 홈/리빙 미판정(백필 잔여)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndHomeLivingClassIsNull(
            java.util.Collection<InfluencerStatus> statuses, Pageable pageable);

    /** 명단 홈/리빙 필터: 선택 분류 + 미판정을 함께 체크한 경우. */
    @Query("select i from Influencer i where i.status in :statuses "
            + "and (i.homeLivingClass in :classes or i.homeLivingClass is null)")
    org.springframework.data.domain.Page<Influencer> findByStatusInAndHomeLivingClassInOrNull(
            @Param("statuses") java.util.Collection<InfluencerStatus> statuses,
            @Param("classes") java.util.Collection<CategoryClass> classes, Pageable pageable);
```
2. `UiController` — `FNB_FILTERS` 아래:
```java
    /** 홈/리빙 5분류 + 미판정 — 배지 CSS는 뷰티 것 재사용(색 의미 동일). */
    private static final java.util.List<BeautyFilter> HOME_LIVING_FILTERS = java.util.List.of(
            new BeautyFilter("INFLUENCER", "홈/리빙", "BEAUTY"),
            new BeautyFilter("COMPANY", "홈/리빙 회사", "BEAUTY_COMPANY"),
            new BeautyFilter("SERVICE", "서비스", "BEAUTY_SERVICE"),
            new BeautyFilter("FOREIGN_INFLUENCER", "외국인", "FOREIGN_INFLUENCER"),
            new BeautyFilter("NONE", "홈/리빙 아님", "NOT_BEAUTY"),
            new BeautyFilter("UNJUDGED", "미판정", "UNJUDGED"));
```
   `influencers()` 메서드 — 파라미터 `@RequestParam(required = false) java.util.List<String> homeLiving` 추가,
   fnb 파싱 블록 뒤에 동일 파싱(homeLivingSelected/homeLivingUnjudged/homeLivingClasses),
   분기 체인 마지막 `else` 앞에 (우선순위: 뷰티 > F&B > 홈/리빙 — 축 교차 조합 미지원):
```java
        } else if (!homeLivingClasses.isEmpty() || homeLivingUnjudged) {
            if (homeLivingClasses.isEmpty()) result = influencers.findByStatusInAndHomeLivingClassIsNull(effective, pageable);
            else if (!homeLivingUnjudged) result = influencers.findByStatusInAndHomeLivingClassIn(effective, homeLivingClasses, pageable);
            else result = influencers.findByStatusInAndHomeLivingClassInOrNull(effective, homeLivingClasses, pageable);
```
   모델 속성 추가: `homeLiving`(선택값)·`homeLivingFilters`·`homeLivingClasses`(=CategoryClass.values()).
3. `InfluencerBeautyController` — `overrideFnb` 뒤에 대칭 메서드 (기존 두 메서드에도
   `homeLiving` 필터 보존 파라미터 추가):
```java
    /** 홈/리빙 축 수동 오버라이드 — 적용 시점 가드(home_living_source='MANUAL')가 자동 판정을 막는다. */
    @PostMapping("/ui/influencers/{id}/homeLiving")
    public String overrideHomeLiving(@PathVariable Long id, @RequestParam CategoryClass homeLivingClass,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(required = false) List<InfluencerStatus> status,
                                     @RequestParam(required = false) List<String> beauty,
                                     @RequestParam(required = false) List<String> fnb,
                                     @RequestParam(required = false) List<String> homeLiving,
                                     RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classifyHomeLiving(homeLivingClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정", null);
        influencers.save(inf);
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        if (beauty != null && !beauty.isEmpty()) ra.addAttribute("beauty", beauty);
        if (fnb != null && !fnb.isEmpty()) ra.addAttribute("fnb", fnb);
        if (homeLiving != null && !homeLiving.isEmpty()) ra.addAttribute("homeLiving", homeLiving);
        return "redirect:/ui/influencers";
    }
```
4. `influencers.html` —
   - 필터 폼: F&B 필터 블록 뒤에 동일 구조로 홈/리빙 필터 블록
     (`th:each="hf : ${homeLivingFilters}"`, `name="homeLiving"`, 배지 텍스트 `${'홈리빙: ' + hf.label()}`).
   - 테이블 헤더: `<th>F&amp;B</th>` 뒤 `<th>홈/리빙</th>`.
   - F&B 셀 뒤에 홈/리빙 셀 — F&B 셀 블록을 복제해 `fnbClass→homeLivingClass`,
     action `@{|/ui/influencers/${row.influencer.id}/homeLiving|}`, hidden `homeLivingClass`,
     배지 텍스트 `홈/리빙`/`홈/리빙 회사`/`서비스`/`홈/리빙 외국인`/`홈/리빙 아님`,
     버튼 텍스트 `홈리빙`/`회사`/`서비스`/`외국인`/`아님`, 툴팁 `homeLivingReason`.
   - 모든 오버라이드 폼(뷰티·F&B·홈/리빙)과 페이저 링크에 `homeLiving` hidden/파라미터 보존 추가.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.InfluencerBeautyControllerTest"`
Expected: PASS

- [ ] **Step 5: `/verify` 스킬로 UI 확인** — 대시보드 ③-4·③-3 타일과 명단 필터·오버라이드
  버튼이 렌더되는지 실제 앱으로 확인 (프로젝트 `verify` 스킬의 레시피대로).

- [ ] **Step 6: Commit**

```bash
git add -A crawler/src
git commit -m "feat(crawler): 명단 홈/리빙 필터·수동 오버라이드 (§5)"
```

---

### Task 7: 분석 뷰 노출 + 문서·전체 검증·PR

**Files:**
- Modify: `analytics/views/00_base.sql`
- Modify: `analytics/test/00_base.test.sql`, `analytics/seed/dummy.sql`
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/specs/2026-08-27-home-living-category-design.md` (상태 헤더 → ✅ 구현됨)
- Move: 이 plan 문서 → `docs/superpowers/plans/archive/`

**Interfaces:**
- Consumes: Task 1의 `home_living` 컬럼

- [ ] **Step 1: 뷰·시드·하니스 테스트 수정**

`analytics/views/00_base.sql`의 `v_base_influencer` SELECT에 `fnb_judged_at` 뒤 추가:
```sql
  home_living,
  home_living_company,
  home_living_judged_at
```
주석의 "fnb 축(2026-08-24)은 노출만"에 "홈/리빙 축(2026-08-27)도 동일" 취지 추가.

`analytics/seed/dummy.sql` — 기존 fnb UPDATE(34행) 옆에 대칭 시드:
```sql
UPDATE influencer SET home_living = true, home_living_company = false, home_living_class = 'INFLUENCER'
WHERE username = 'dummy_a';
```

`analytics/test/00_base.test.sql` — fnb ASSERT 옆에:
```sql
  ASSERT (SELECT home_living FROM analytics.v_base_influencer WHERE username = 'dummy_a') = true,
    'v_base_influencer dummy_a home_living != true';
  ASSERT (SELECT home_living FROM analytics.v_base_influencer WHERE username = 'dummy_co') IS NULL,
    'v_base_influencer dummy_co home_living not null (미판정)';
```

- [ ] **Step 2: SQL 하니스 실행**

Run: `analytics/test/run.sh test/00_base.test.sql` (실데이터 postgres 컨테이너 필요 —
`docker start crawler-postgres-1` 후. 새 컬럼이 로컬 DB에 없으면 crawler 부팅으로 Flyway 적용
후 실행. 로컬에서 컨테이너 사정이 안 되면 CI `sql-harness` 잡에 맡기고 이 스텝은 PR CI로 검증)
Expected: PASS

- [ ] **Step 3: 문서 갱신**

- `DECISIONS.md` 맨 위에 항목 추가: 판정 3축화(홈/리빙) — 병렬 컬럼 유지 결정(범용 테이블
  이관은 4번째 카테고리·카테고리 서빙 개편 때), 정착 가드 처음부터 적용, 링크는 스펙 문서.
- 스펙 문서 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨 (2026-08-27)`로.
- 이 plan 문서를 `docs/superpowers/plans/archive/`로 이동.

- [ ] **Step 4: crawler 모듈 전체 테스트**

Run: `./gradlew :crawler:test`
Expected: PASS (전체 회귀)

- [ ] **Step 5: 전체 테스트 (PR 직전 1회)**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit + PR**

```bash
git add -A
git commit -m "feat(analytics): v_base_influencer 홈/리빙 노출 + docs: 3축화 결정 기록"
git push -u origin feature/influencer-data-scraping-7cf793
gh pr create --base develop --title "feat: 홈/리빙 카테고리 축 추가 — 판정 3축화" --body "..."
```
PR 본문: 스펙 링크, 변경 요약(마이그레이션 1건 expand·토글 off 기본), 배포 후 새벽 크론이
백필 자동 시작함을 명시. 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
