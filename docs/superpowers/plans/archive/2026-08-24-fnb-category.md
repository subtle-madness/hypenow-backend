# F&B 카테고리 추가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 스펙: [2026-08-23-fnb-category-design.md](../specs/2026-08-23-fnb-category-design.md)

**Goal:** 뷰티 단일 카테고리 판정을 2축(뷰티+F&B)으로 확장 — 판정·백필·명단까지 구축하고, 수집·시드 편입은 `fnb.pipeline-enabled` 토글(기본 off) 뒤에 둔다.

**Architecture:** `influencer`에 F&B 축 컬럼을 병렬 추가(beauty 세트와 대칭), LLM 판정은 기존 1콜에 두 축을 동시 판정. 다운스트림 게이트(수집·시드·비용 추정)는 JPQL에 `:includeFnb` 파라미터를 추가해 토글로 분기. 서빙 모수(분석 뷰 01/02/20·was)는 무변경.

**Tech Stack:** Java 21, Spring Boot 4.1(JPA/Flyway), Jackson 3(`tools.jackson.*`), Testcontainers(PostgreSQL), Thymeleaf 어드민, SQL 하니스(`analytics/test/run.sh`).

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(crawler):` / `docs:`.
- 테스트는 모듈 단위: `./gradlew :crawler:test` (전체 `./gradlew test`는 PR 직전에만).
- 로컬 도커는 Docker Desktop — `DOCKER_HOST` **설정하지 않는 것이 정답**(08-09 확인. CLAUDE.md의 colima 문단은 구 환경 기준).
- 신규 Flyway 마이그레이션은 **UTC 타임스탬프 채번**: `V$(date -u +%Y%m%d%H%M%S)__<설명>.sql`. 기존 V1~V22 rename 절대 금지.
- 스키마 변경은 expand-contract — 이번 작업은 expand(ADD COLUMN)만. DROP·RENAME 없음.
- `app_setting` 기준값은 마이그레이션 시드(`ON CONFLICT (key) DO NOTHING`, V16 관용구).
- grep 시 `--exclude-dir=docs` (문서 히트가 코드를 묻는다).
- 기존 `BeautyClass`·beauty 축 저장값·`JobName.BEAUTY`·포트 이름(`BeautyJudge`)·잡 이름은 **rename 금지** — 운영 DB 값·크론 키·어드민이 물려 있다.
- 서빙 모수(분석 뷰 01/02/20의 `beauty ∧ ¬beauty_company`)·미러·was는 건드리지 않는다.

---

### Task 1: 마이그레이션 + CategoryClass + Influencer F&B 필드

**Files:**
- Create: `crawler/src/main/resources/db/migration/V<UTC타임스탬프>__influencer_fnb.sql`
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/domain/CategoryClass.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/Influencer.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/domain/CategoryClassTest.java` (신규)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java` (메서드 추가)

**Interfaces:**
- Produces: `CategoryClass` enum (`INFLUENCER, COMPANY, SERVICE, FOREIGN_INFLUENCER, NONE` + `boolean inCategory()`, `boolean company()`), `Influencer.classifyFnb(CategoryClass cls, String source, String reason, String basis)`, getter/setter `getFnb()/getFnbClass()/setFnbJudgedAt(Instant)/setFnbCaptionCount(Short)` 등(Lombok `@Getter @Setter`가 자동 생성), DB 컬럼 `fnb, fnb_company, fnb_class, fnb_source, fnb_reason, fnb_basis, fnb_judged_at, fnb_caption_count`, app_setting 키 `fnb.pipeline-enabled`='false'.

- [ ] **Step 1: 실패하는 단위 테스트 작성** — `CategoryClassTest.java`

```java
package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassTest {

    @Test
    void 파생_boolean은_INFLUENCER와_COMPANY만_카테고리_소속이다() {
        assertThat(CategoryClass.INFLUENCER.inCategory()).isTrue();
        assertThat(CategoryClass.COMPANY.inCategory()).isTrue();
        assertThat(CategoryClass.SERVICE.inCategory()).isFalse();
        assertThat(CategoryClass.FOREIGN_INFLUENCER.inCategory()).isFalse();
        assertThat(CategoryClass.NONE.inCategory()).isFalse();
    }

    @Test
    void company는_COMPANY만_true다() {
        assertThat(CategoryClass.COMPANY.company()).isTrue();
        assertThat(CategoryClass.INFLUENCER.company()).isFalse();
        assertThat(CategoryClass.SERVICE.company()).isFalse();
    }
}
```

(단언 스타일은 `BeautyClassTest` 참고 — assertj가 아니라 JUnit `assertTrue`면 그 스타일을 따른다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.domain.CategoryClassTest"`
Expected: 컴파일 실패 (`CategoryClass` 없음)

- [ ] **Step 3: enum 구현** — `CategoryClass.java`

```java
package com.celfit.crawler.crawling.domain;

/**
 * 카테고리 공용 5분류 — F&B 축(fnb_class)의 저장값이자 파생 boolean(fnb/fnb_company) 규칙의
 * 단일 원천 (스펙 2026-08-23 §1). 뷰티 축은 역사적 이름(BeautyClass — BEAUTY_SERVICE·NOT_BEAUTY)이
 * 운영 DB에 박혀 있어 그대로 두고, 새 카테고리 축부터 이 중립 이름을 쓴다.
 */
public enum CategoryClass {
    /** 해당 카테고리 제품 콘텐츠 중심 한국어 개인 크리에이터 — 시딩·협찬 타깃. */
    INFLUENCER,
    /** 해당 카테고리 제품 제작·판매 회사(브랜드·쇼핑몰, 언어 무관) — 컨택 타깃. */
    COMPANY,
    /** 매장·서비스 공식 계정(F&B: 식당·카페·베이커리 등 업장 자체) — 타깃 아님. */
    SERVICE,
    /** 개인 크리에이터지만 한국어 콘텐츠가 아님 — 한국 시장 시딩 타깃 아님. */
    FOREIGN_INFLUENCER,
    /** 해당 카테고리 콘텐츠 중심이 아닌 계정. */
    NONE;

    public boolean inCategory() {
        return this == INFLUENCER || this == COMPANY;
    }

    public boolean company() {
        return this == COMPANY;
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.domain.CategoryClassTest"`
Expected: PASS

- [ ] **Step 5: 마이그레이션 파일 작성** — 파일명은 실행 시점에 채번:

```bash
echo "V$(date -u +%Y%m%d%H%M%S)__influencer_fnb.sql"
```

내용:

```sql
-- 인플루언서 F&B 축 판정 컬럼 (스펙 2026-08-23 §1) — expand만, 파괴 없음.
-- beauty 축 컬럼 세트와 대칭. NULL = F&B 축 미판정(백필 대상).
ALTER TABLE influencer
  ADD COLUMN fnb boolean,
  ADD COLUMN fnb_company boolean,
  ADD COLUMN fnb_class text,
  ADD COLUMN fnb_source text,
  ADD COLUMN fnb_reason text,
  ADD COLUMN fnb_basis text,
  ADD COLUMN fnb_judged_at timestamptz,
  ADD COLUMN fnb_caption_count smallint;

-- F&B 수집·시드 파이프라인 게이트 — 기본 off (스펙 §4). 판정 모수·비용 확인 후 수동 UPDATE로 on.
-- ON CONFLICT DO NOTHING: 런타임 오버라이드 보존 (V16 관용구).
INSERT INTO app_setting(key, value) VALUES ('fnb.pipeline-enabled', 'false')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 6: Influencer 엔티티 확장** — `similarProcessedAt` 필드 선언 앞에 삽입:

```java
    /** F&B 계정 여부 — NULL이면 미판정(백필 대상). 수집·시드 편입은 fnb.pipeline-enabled 토글이 게이트. */
    private Boolean fnb;

    /** F&B 회사(식품·음료 브랜드·쇼핑몰) 여부 — fnb=true의 하위 구분. 토글 on이어도 수집 제외. */
    @Column(name = "fnb_company")
    private Boolean fnbCompany;

    /** F&B 5분류 원본 — boolean은 이 값의 파생. NULL이면 F&B 축 미판정. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fnb_class")
    private CategoryClass fnbClass;

    @Column(name = "fnb_source")
    private String fnbSource;

    /** F&B 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "fnb_reason")
    private String fnbReason;

    /** F&B 축 판정 시각. */
    @Column(name = "fnb_judged_at")
    private Instant fnbJudgedAt;

    /** F&B 판정에 실제로 넣은 캡션 건수 — 추후 F&B rejudge 도입 시 재료. */
    @Column(name = "fnb_caption_count")
    private Short fnbCaptionCount;

    /** F&B 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY. */
    @Column(name = "fnb_basis")
    private String fnbBasis;
```

`classify()` 메서드 아래에 추가:

```java
    /** F&B 축 판정 적용 — 파생 boolean을 fnb_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classifyFnb(CategoryClass cls, String source, String reason, String basis) {
        this.fnbClass = cls;
        this.fnb = cls.inCategory();
        this.fnbCompany = cls.company();
        this.fnbSource = source;
        this.fnbReason = reason;
        this.fnbBasis = basis;
    }
```

- [ ] **Step 7: 통합 테스트 추가** — `BeautySelectionIntegrationTest`에 메서드 추가 (마이그레이션+매핑 라운드트립 검증):

```java
    @Test
    void fnb_판정이_저장되고_재조회된다() {
        Influencer inf = influencers.save(new Influencer("fnb_roundtrip"));
        inf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "레시피 계정", "CAPTION");
        inf.setFnbJudgedAt(Instant.parse("2026-08-24T00:00:00Z"));
        inf.setFnbCaptionCount((short) 5);
        influencers.save(inf);

        Influencer found = influencers.findByUsername("fnb_roundtrip").orElseThrow();
        assertThat(found.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(found.getFnb()).isTrue();
        assertThat(found.getFnbCompany()).isFalse();
        assertThat(found.getFnbReason()).isEqualTo("레시피 계정");
        assertThat(found.getFnbBasis()).isEqualTo("CAPTION");
        assertThat(found.getFnbCaptionCount()).isEqualTo((short) 5);
    }
```

(import·단언 스타일은 파일 내 기존 테스트를 따른다. cleanup()이 지우는 대상에 이 계정이 포함되는지 확인 — 기존 패턴대로 username prefix 정리 로직이 있으면 맞춘다.)

- [ ] **Step 8: 통합 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: PASS (Flyway가 새 마이그레이션 적용 + 매핑 정합)

- [ ] **Step 9: 커밋**

```bash
git add -A && git commit -m "feat(crawler): influencer F&B 축 컬럼·CategoryClass 추가 (스펙 2026-08-23 §1)"
```

---

### Task 2: 판정 2축화 — Verdict·프롬프트·파서·어댑터

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/BeautyJudge.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java` (buildPrompt·parse)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeApiBeautyJudge.java` (MAX_TOKENS)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/gemini/GeminiBeautyJudge.java` (RESPONSE_SCHEMA·maxOutputTokens)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java` (2축 적용 최소 적응)
- Test: `ClaudeCliBeautyJudgeTest.java`(형식 전환), `BeautyJobTest.java`(Verdict 생성부 기계적 수정), `GeminiBeautyJudgeTest.java`·`ClaudeApiBeautyJudgeTest.java`(스키마·토큰 단언 갱신)

**Interfaces:**
- Consumes: Task 1의 `CategoryClass`, `Influencer.classifyFnb(...)`.
- Produces: `BeautyJudge.Verdict(String username, BeautyClass beautyClass, String reason, String basis, CategoryClass fnbClass, String fnbReason, String fnbBasis)` — 축별 class는 무효/누락 시 null(양축 null이면 파싱에서 항목 제외). `Verdict.beauty()/company()`는 beautyClass null이면 false.

- [ ] **Step 1: 파서 테스트를 새 형식으로 재작성** — `ClaudeCliBeautyJudgeTest`의 JSON 픽스처를 전부 2축 형식으로 바꾸고 신규 케이스 추가. 핵심 신규 테스트:

```java
    @Test
    void 이축_JSON을_판정으로_파싱한다() {
        String out = """
                [{"username":"kim","beauty":{"reason":"뷰티 리뷰","basis":"CAPTION","class":"INFLUENCER"},
                  "fnb":{"reason":"레시피 다수","basis":"CAPTION","class":"INFLUENCER"}}]""";
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(v.get(0).fnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(v.get(0).fnbReason()).isEqualTo("레시피 다수");
        assertThat(v.get(0).fnbBasis()).isEqualTo("CAPTION");
    }

    @Test
    void 한_축이_무효여도_다른_축_판정은_살린다() {
        String out = """
                [{"username":"kim","beauty":{"reason":"r","basis":"BIO","class":"뭔가이상한값"},
                  "fnb":{"reason":"r2","basis":"BIO","class":"NONE"}}]""";
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).beautyClass()).isNull();
        assertThat(v.get(0).fnbClass()).isEqualTo(CategoryClass.NONE);
    }

    @Test
    void 양축_모두_무효면_항목을_건너뛴다() {
        String out = """
                [{"username":"kim","beauty":{"class":"X"},"fnb":{"class":"Y"}}]""";
        assertThat(ClaudeCliBeautyJudge.parse(om, out)).isEmpty();
    }

    @Test
    void 프롬프트에_두_축_분류와_출력_형식이_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("u", "이름", "cat", "bio", List.of())));
        assertThat(p).contains("beauty");
        assertThat(p).contains("fnb");
        assertThat(p).contains("BEAUTY_SERVICE");   // 뷰티 축 어휘
        assertThat(p).contains("\"SERVICE\"");       // F&B 축 어휘 (매장)
        assertThat(p).contains("NONE");
        assertThat(p).contains("두 축은 독립");
    }
```

기존 테스트(`코드펜스로_감싼_5분류_JSON…`, `basis를_파싱한다` 등)는 픽스처만 2축 형식으로 치환하고 단언 의도는 유지한다. `Verdict의_파생_boolean은_BeautyClass_규칙을_따른다`에는 `beautyClass=null → beauty()=false` 케이스를 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudgeTest"`
Expected: 컴파일 실패(Verdict 시그니처) 또는 FAIL

- [ ] **Step 3: Verdict 확장** — `BeautyJudge.java`:

```java
    /**
     * 2축 판정 결과 — beauty(뷰티 제품)·fnb(식품/음료 제품) 축을 독립 판정한다(스펙 2026-08-23 §2).
     * 축별 class는 모델 응답이 무효·누락이면 null — 호출자는 null 아닌 축만 적용한다(해당 축은
     * 미판정으로 남아 다음 실행 재시도). 파생 boolean은 각 enum 규칙에 위임.
     */
    record Verdict(String username, com.celfit.crawler.crawling.domain.BeautyClass beautyClass,
                   String reason, String basis,
                   com.celfit.crawler.crawling.domain.CategoryClass fnbClass,
                   String fnbReason, String fnbBasis) {
        public boolean beauty() {
            return beautyClass != null && beautyClass.beauty();
        }

        public boolean company() {
            return beautyClass != null && beautyClass.company();
        }
    }
```

- [ ] **Step 4: 프롬프트 교체** — `ClaudeCliBeautyJudge.buildPrompt`의 text block 전문을 다음으로 교체:

```java
    public static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
        return """
                너는 인플루언서 마케팅 리스트업 서비스의 분류기다. 각 인스타그램 계정을 두 카테고리 \
                축에서 독립적으로 분류한다: beauty(뷰티 제품 — 스킨케어·메이크업·향수·헤어/바디케어 \
                제품 등), fnb(식품/음료 제품 — 가공식품·음료·건강기능식품·식재료 등). 한 계정이 두 축 \
                모두에 해당할 수 있다(예: 뷰티 리뷰와 레시피를 함께 올리는 계정).
                목적: 한국 시장에서 각 카테고리 제품을 시딩·협찬·광고할 한국인 인플루언서와, 그런 \
                인플루언서를 필요로 하는 제품 회사를 찾는 것.

                [beauty 축 분류]
                - INFLUENCER: 게시물 캡션·bio를 한국어로 쓰는 뷰티 제품 개인 크리에이터. 광고·협찬 \
                게시물만이 아니라 오가닉 뷰티 콘텐츠를 올리는 개인도 포함.
                - FOREIGN_INFLUENCER: 뷰티 제품 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
                - COMPANY: 뷰티 제품을 제작·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
                - BEAUTY_SERVICE: 뷰티 영역이지만 시술·서비스 중심 — 피부과·성형외과·에스테틱·헤어샵/\
                미용실·네일샵·왁싱·속눈썹·반영구 등 시술을 파는 업체, 그리고 헤어 디자이너·네일 아티스트·\
                시술 후기 위주 계정 같은 시술·서비스 중심 개인
                - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정

                [fnb 축 분류]
                - INFLUENCER: 캡션·bio를 한국어로 쓰는 F&B 개인 크리에이터 — 요리/레시피, 식품·음료 \
                제품 리뷰, 맛집·카페 탐방 개인 계정 모두 포함(제품 시딩이 가능한 개인).
                - FOREIGN_INFLUENCER: F&B 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
                - COMPANY: 식품·음료 제품을 제조·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
                - SERVICE: 매장·서비스 공식 계정 — 식당·카페·베이커리·술집 등 업장 자체의 계정. \
                개인이 매장을 탐방·리뷰하는 계정은 SERVICE가 아니라 INFLUENCER다.
                - NONE: F&B 콘텐츠 중심이 아닌 계정

                경계 규칙:
                - 두 축은 독립이다 — 한 축의 판정이 다른 축에 영향을 주지 않는다. 어느 쪽도 아니면 \
                beauty=NOT_BEAUTY, fnb=NONE이다.
                - 시술 업체가 자체 제품도 팔면 콘텐츠 주력 기준으로 — 시술·매장 홍보 중심이면 \
                BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY. F&B 매장이 자체 제품(밀키트·원두·소스 등)을 \
                온라인 판매해도 같은 기준 — 매장 홍보 중심이면 SERVICE, 제품 판매 중심이면 COMPANY.
                - 한국어 판정은 캡션이 최우선 신호다 — bio가 영어라도 캡션이 주로 한국어면 한국어 \
                콘텐츠(INFLUENCER)로 판정하라(한국 계정이 영어 bio를 쓰는 경우가 흔하다). 반대도 \
                같다 — bio에 한국어가 섞여 있어도 캡션이 주로 외국어면 FOREIGN_INFLUENCER다.
                - 한국어·외국어를 섞어 쓰면 주 오디언스가 한국인지 기준으로 판정하라.
                - 캡션이 빈 배열(미수집)이고 bio만으로 모호하면 이름·bio의 한국어 여부로 판정하라.
                - 판정 기준은 계정이 글을 쓰는 언어이지, 다루는 제품·주제의 국적이 아니다. 한국 \
                브랜드 제품을 리뷰해도, 한국에 거주해도, bio·캡션을 일본어·중국어·영어 등으로 \
                쓰면 FOREIGN_INFLUENCER다(한국 시장 시딩 대상이 아니므로). 예: "韓国コスメ"를 일본어로 \
                리뷰하는 일본 계정 → beauty축 FOREIGN_INFLUENCER.
                - bio·이름이 히라가나·가타카나·한자(중국어)·태국어·키릴 문자 등으로 된 문장이면 강한 \
                외국어 신호다. 단, ヽ( ´ー｀)ノ·ﾟ·・ 같은 장식용 카오모지 문자는 한국 계정도 흔히 \
                쓰므로 신호가 아니다 — 낱글자 장식인지 문장을 이루는지로 구분하라.
                - category는 계정주가 자율 선택한 미검증 자기신고 필드다 — bio·캡션의 실제 내용과 \
                상충하면 실제 내용을 우선하라.
                captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
                캡션의 실제 콘텐츠 주제를 근거로 판정하라.
                basis는 각 축 판정의 주근거다 — 캡션의 콘텐츠 주제를 근거로 했으면 CAPTION, bio·이름을 \
                근거로 했으면 BIO, 캡션도 bio도 근거가 되지 못해 category만 보고 판단했으면 CATEGORY_ONLY.
                각 축에서 reason(근거)을 먼저 쓰고, 그 근거와 일관된 class를 마지막에 쓰라.
                출력은 JSON 배열만: [{"username":"...",\
                "beauty":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY"},\
                "fnb":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|SERVICE|NONE"}}]
                입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

                """ + om.writeValueAsString(cards);
    }
```

- [ ] **Step 5: 파서 교체** — `ClaudeCliBeautyJudge.parse`와 헬퍼:

```java
    public static List<Verdict> parse(ObjectMapper om, String output) {
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
            if (username == null || username.isBlank()) continue;
            JsonNode b = n.path("beauty");
            JsonNode f = n.path("fnb");
            BeautyClass beautyClass = parseBeautyClass(b.path("class").asString(null));
            CategoryClass fnbClass = parseCategoryClass(f.path("class").asString(null));
            // 양축 모두 무효(모델 일탈)면 건너뛴다 — 해당 계정 두 축 다 미판정 유지, 다음 실행 재시도.
            // 한 축만 무효면 그 축만 null — 유효한 축의 판정을 버릴 이유가 없다.
            if (beautyClass == null && fnbClass == null) continue;
            out.add(new Verdict(username, beautyClass, b.path("reason").asString(null),
                    normalizeBasis(b.path("basis").asString(null)),
                    fnbClass, f.path("reason").asString(null),
                    normalizeBasis(f.path("basis").asString(null))));
        }
        return out;
    }

    private static BeautyClass parseBeautyClass(String cls) {
        if (cls == null) return null;
        return switch (cls) {
            case "INFLUENCER" -> BeautyClass.INFLUENCER;
            case "FOREIGN_INFLUENCER" -> BeautyClass.FOREIGN_INFLUENCER;
            case "COMPANY" -> BeautyClass.COMPANY;
            case "BEAUTY_SERVICE" -> BeautyClass.BEAUTY_SERVICE;
            case "NOT_BEAUTY" -> BeautyClass.NOT_BEAUTY;
            default -> null;
        };
    }

    private static CategoryClass parseCategoryClass(String cls) {
        if (cls == null) return null;
        return switch (cls) {
            case "INFLUENCER" -> CategoryClass.INFLUENCER;
            case "FOREIGN_INFLUENCER" -> CategoryClass.FOREIGN_INFLUENCER;
            case "COMPANY" -> CategoryClass.COMPANY;
            case "SERVICE" -> CategoryClass.SERVICE;
            case "NONE" -> CategoryClass.NONE;
            default -> null;  // 5분류 외 값(모델 일탈) — 해당 축 미판정 유지
        };
    }
```

(import에 `com.celfit.crawler.crawling.domain.CategoryClass` 추가.)

- [ ] **Step 6: 어댑터 상수 갱신**

`ClaudeApiBeautyJudge`: `MAX_TOKENS = 8192L` → `16384L` (출력이 계정당 2축 ~2배 — 스펙 §2).
`GeminiBeautyJudge`: `maxOutputTokens` 8192 → 16384, `RESPONSE_SCHEMA` 교체:

```java
    static final String RESPONSE_SCHEMA = """
            {"type":"array","items":{"type":"object","properties":{
              "username":{"type":"string"},
              "beauty":{"type":"object","properties":{
                "reason":{"type":"string"},
                "basis":{"type":"string","enum":["CAPTION","BIO","CATEGORY_ONLY"]},
                "class":{"type":"string","enum":["INFLUENCER","FOREIGN_INFLUENCER","COMPANY","BEAUTY_SERVICE","NOT_BEAUTY"]}},
               "required":["reason","basis","class"]},
              "fnb":{"type":"object","properties":{
                "reason":{"type":"string"},
                "basis":{"type":"string","enum":["CAPTION","BIO","CATEGORY_ONLY"]},
                "class":{"type":"string","enum":["INFLUENCER","FOREIGN_INFLUENCER","COMPANY","SERVICE","NONE"]}},
               "required":["reason","basis","class"]}},
             "required":["username","beauty","fnb"]}}""";
```

- [ ] **Step 7: BeautyJob 최소 적응(컴파일·NPE 가드)** — `applyVerdicts` 루프 본문에서 beauty 축 적용을 null 가드로 감싸고 fnb 축 적용을 추가한다(백필 마스크는 Task 3):

```java
            short capCount = captionCounts.getOrDefault(v.username(), 0).shortValue();
            if (v.beautyClass() != null) {
                inf.classify(v.beautyClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.reason(), v.basis());
                inf.setBeautyJudgedAt(clock.instant());
                inf.setBeautyCaptionCount(capCount);
                switch (v.beautyClass()) {
                    case INFLUENCER, COMPANY -> beauty++;
                    case BEAUTY_SERVICE -> service++;
                    case FOREIGN_INFLUENCER -> foreign++;
                    case NOT_BEAUTY -> notBeauty++;
                }
            }
            if (v.fnbClass() != null) {
                inf.classifyFnb(v.fnbClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.fnbReason(), v.fnbBasis());
                inf.setFnbJudgedAt(clock.instant());
                inf.setFnbCaptionCount(capCount);
            }
            influencers.save(inf);
```

(기존 `switch`·라벨 로그는 beauty 축 블록 안으로 이동. done 카운터·로그 라인은 유지하되 beautyClass null이면 라벨을 "뷰티축 무응답"으로.) `BeautyJobTest`의 `new BeautyJudge.Verdict(u, cls, reason, basis)` 생성부는 전부 `new BeautyJudge.Verdict(u, cls, reason, basis, null, null, null)`로 기계적 치환해 기존 동작 단언을 유지한다.

- [ ] **Step 8: 관련 테스트 전체 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.out.claude.*" --tests "com.celfit.crawler.crawling.adapter.out.gemini.*" --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: PASS (Gemini·ClaudeApi 테스트가 구 스키마·구 토큰을 단언하면 새 값으로 갱신)

- [ ] **Step 9: 커밋**

```bash
git add -A && git commit -m "feat(crawler): 판정 프롬프트·파서 2축화(뷰티+F&B) — 1콜 동시 판정 (스펙 §2)"
```

---

### Task 3: BeautyJob 백필 선정 + fnbOnly 마스크 + Summary 확장

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobService.java` (Summary 로그 — 시그니처 변화 없으면 무수정)
- Test: `BeautyJobTest.java`, `BeautySelectionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2의 `Verdict`(축별 null 허용), Task 1의 `classifyFnb`.
- Produces: `InfluencerRepository.findFnbBackfillTargets(InfluencerStatus status, Pageable pageable)`, `BeautyJob.Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty, int fnbApplied, int fnbPositive, int skippedNoProfile, int failedBatches)`.

- [ ] **Step 1: 실패하는 테스트 작성** — `BeautyJobTest`에 추가 (파일 상단의 기존 mock 셋업 관용구 재사용):

```java
    @Test
    void 백필_대상은_F앤B_축만_적용하고_뷰티_판정을_보존한다() {
        Influencer inf = influencer("kept", 1L);
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
        // 신규(미판정) 없음, 백필만 선정되는 상황
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of());
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        stubProfile(inf);  // 기존 헬퍼 관용구 — raw_profile 재료 스텁
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("kept",
                BeautyClass.NOT_BEAUTY, "모델이 딴소리", "BIO",
                CategoryClass.INFLUENCER, "레시피 계정", "CAPTION")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        // 뷰티 축은 그대로 (MANUAL INFLUENCER 보존 — 모델의 NOT_BEAUTY 무시)
        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        // F&B 축만 적용
        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getFnb()).isTrue();
        assertThat(s.fnbApplied()).isEqualTo(1);
        assertThat(s.fnbPositive()).isEqualTo(1);
        assertThat(s.judgedBeauty()).isZero();
    }

    @Test
    void 신규_판정은_두_축을_모두_적용한다() {
        Influencer inf = influencer("fresh", 2L);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        stubProfile(inf);
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("fresh",
                BeautyClass.NOT_BEAUTY, "뷰티 아님", "CAPTION",
                CategoryClass.INFLUENCER, "요리 계정", "CAPTION")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.NOT_BEAUTY);
        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(s.judgedNotBeauty()).isEqualTo(1);
        assertThat(s.fnbApplied()).isEqualTo(1);
    }

    @Test
    void 백필은_신규가_한도를_다_채우면_호출되지_않는다() {
        // beauty.batch-limit 스텁이 반환하는 한도만큼 신규(미판정)를 채운다
        when(settings.beautyBatchLimit()).thenReturn(1);
        Influencer fresh = influencer("only_new", 3L);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(fresh));
        stubProfile(fresh);
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("only_new",
                BeautyClass.NOT_BEAUTY, "r", "BIO", CategoryClass.NONE, "r", "BIO")));

        job.run(TriggerType.MANUAL, false);

        verify(influencers, never()).findFnbBackfillTargets(any(), any());
    }
```

(mock 셋업 헬퍼 이름은 파일 내 기존 것을 그대로 쓴다 — `influencer(...)`·`stubProfile(...)`이 없으면 그 파일에서 인플루언서 생성·raw_profile 스텁에 쓰는 기존 관용구(예: `when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(...))`)를 인라인으로 쓴다. `settings.beautyBatchLimit()` 스텁도 기존 셋업에 이미 있으면 값만 조정.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: 컴파일 실패 (`findFnbBackfillTargets`·`fnbApplied` 없음)

- [ ] **Step 3: 선정 쿼리 추가** — `InfluencerRepository`의 `findByStatusAndBeautyIsNull` 아래:

```java
    /**
     * F&B 백필 대상: 뷰티 축은 판정 완료(MANUAL 포함 — 백필은 뷰티 판정을 덮지 않으므로 안전)지만
     * F&B 축이 미판정인 계정 — 카테고리 확장(스펙 2026-08-23 §3)의 기존 판정분 전체 재판정 경로.
     * id 순 Pageable로 결정적으로 소진한다.
     */
    @Query("select i from Influencer i where i.status = :status and i.beauty is not null "
            + "and i.fnb is null order by i.id")
    List<Influencer> findFnbBackfillTargets(@Param("status") InfluencerStatus status, Pageable pageable);
```

- [ ] **Step 4: BeautyJob 선정·적용·Summary 수정**

`run()` 선정부 — 신규 선정 직후, 기존 rejudge 블록 **앞**에 삽입(백필이 rejudge보다 우선 — 초기 백로그 소화가 이번 확장의 목적이고, rejudge는 백필 완료 후 자연 재개된다):

```java
        // F&B 백필 — 뷰티 축은 판정 완료, F&B 축만 채운다(스펙 §3). 뷰티 판정(MANUAL 포함)은 덮지 않는다.
        Set<String> fnbOnly = new HashSet<>();
        if (targets.size() < limit) {
            List<Influencer> backfill = influencers.findFnbBackfillTargets(
                    InfluencerStatus.QUALIFIED, PageRequest.of(0, limit - targets.size()));
            backfill.forEach(i -> fnbOnly.add(i.getUsername()));
            targets.addAll(backfill);
        }
```

`Summary` 교체:

```java
    public record Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty,
                          int fnbApplied, int fnbPositive, int skippedNoProfile, int failedBatches) {}
```

`applyVerdicts` — 시그니처에 `Set<String> fnbOnly` 추가, Task 2에서 만든 2축 적용부를 마스크로 완성:

```java
            boolean applyBeauty = !fnbOnly.contains(v.username()) && v.beautyClass() != null;
            if (applyBeauty) { /* Task 2의 beauty 축 블록 그대로 */ }
            if (v.fnbClass() != null) {
                inf.classifyFnb(v.fnbClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.fnbReason(), v.fnbBasis());
                inf.setFnbJudgedAt(clock.instant());
                inf.setFnbCaptionCount(capCount);
                fnbApplied++;
                if (v.fnbClass().inCategory()) fnbPositive++;
            }
```

`ChunkResult`에 `fnbApplied`·`fnbPositive` 추가, 배치 로그에 `F&B 적용 {} (인플루언서·회사 {})` 추가. 계정별 로그 라벨에 fnb 축 적용 시 ` / F&B(레시피·제품)` 식 표기 대신 실제 분류 라벨을 붙인다:

```java
            String fnbLabel = v.fnbClass() == null ? "" : " / " + switch (v.fnbClass()) {
                case INFLUENCER -> "F&B(인플루언서)";
                case COMPANY -> "F&B(회사)";
                case SERVICE -> "F&B(매장·서비스)";
                case FOREIGN_INFLUENCER -> "F&B(외국인)";
                case NONE -> "비F&B";
            };
```

클래스 상단 주석도 갱신: "판정 잡 — 두 카테고리 축(뷰티·F&B)을 1콜로 판정한다".

- [ ] **Step 5: 통합 선정 테스트 추가** — `BeautySelectionIntegrationTest`:

```java
    @Test
    void 백필_선정은_뷰티_판정_완료이고_fnb_미판정인_계정만_고른다() {
        Influencer judged = influencers.save(qualified("bf_judged"));      // beauty 판정됨, fnb NULL → 대상
        judged.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(judged);
        Influencer unjudged = influencers.save(qualified("bf_unjudged")); // beauty NULL → 신규 경로 몫, 제외
        Influencer done = influencers.save(qualified("bf_done"));         // 둘 다 판정 → 제외
        done.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        done.classifyFnb(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(done);

        var picked = influencers.findFnbBackfillTargets(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, 10));
        assertThat(picked).extracting(Influencer::getUsername).containsExactly("bf_judged");
    }
```

(`qualified(...)` 헬퍼가 없으면 파일 내 기존 인플루언서 생성 관용구 — `new Influencer(name)` + `setStatus(QUALIFIED)` — 를 따른다.)

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest" --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: PASS. `JobService`의 Summary 로그(`log.warn("beauty 완료(배치 부분 실패): {}", s)`)는 record toString이라 무수정 컴파일 확인.

- [ ] **Step 7: 커밋**

```bash
git add -A && git commit -m "feat(crawler): F&B 백필 선정·축 선택 적용 — 뷰티 판정 보존 (스펙 §3)"
```

---

### Task 4: 수집·시드 토글 게이트 (`fnb.pipeline-enabled`)

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` (8개 쿼리에 `:includeFnb`)
- Modify: 콜사이트 — `CollectJob.java`, `ReelsJob.java`, `SimilarJob.java`, `dashboard/application/StatusService.java`, `dashboard/application/JobCostEstimator.java`
- Test: `BeautySelectionIntegrationTest.java`(게이트 쿼리), 기존 잡 단위 테스트(mock 시그니처 갱신)

**Interfaces:**
- Consumes: Task 1의 컬럼·시드 키.
- Produces: `SettingsService.fnbPipelineEnabled()` → boolean, 아래 8개 메서드에 `boolean includeFnb` 파라미터 추가(시그니처 변경 — 콜사이트는 컴파일 에러로 전수 드러남): `countBackfillPending`, `countTrackDue`, `findCollectTargets`, `findReelsTargets`, `countReelsDue`, `findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull`, `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull`, `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull`.

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `BeautySelectionIntegrationTest`:

```java
    @Test
    void 수집_선정은_토글_on일_때만_F앤B_인플루언서를_포함하고_회사는_항상_제외한다() {
        Influencer fnbInf = influencers.save(qualified("gate_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbInf);
        Influencer fnbCo = influencers.save(qualified("gate_fnb_co"));
        fnbCo.classifyFnb(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbCo.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbCo);

        Instant future = Instant.now().plusSeconds(3600);
        var off = influencers.findCollectTargets(future, false, PageRequest.of(0, 100));
        assertThat(off).extracting(Influencer::getUsername)
                .doesNotContain("gate_fnb", "gate_fnb_co");

        var on = influencers.findCollectTargets(future, true, PageRequest.of(0, 100));
        assertThat(on).extracting(Influencer::getUsername).contains("gate_fnb");
        assertThat(on).extracting(Influencer::getUsername).doesNotContain("gate_fnb_co");
    }

    @Test
    void 시드_선정도_토글을_따른다() {
        Influencer fnbInf = influencers.save(qualified("seed_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbInf);

        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, PageRequest.of(0, 100)))
                .extracting(Influencer::getUsername).doesNotContain("seed_fnb");
        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, true, PageRequest.of(0, 100)))
                .extracting(Influencer::getUsername).contains("seed_fnb");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest"`
Expected: 컴파일 실패 (새 시그니처 없음)

- [ ] **Step 3: 토글 리더 추가** — `SettingsService`:

```java
    /** F&B 파이프라인 게이트 키 — 수집(collect·reels)·유사발굴 시드·비용 추정의 F&B 편입 여부. */
    static final String FNB_PIPELINE_ENABLED = "fnb.pipeline-enabled";

    /**
     * F&B 판정 통과 계정의 수집·시드 편입 여부(기본 false — 스펙 2026-08-23 §4).
     * 숫자 설정(KEYS·UI 목록)과 달리 boolean 런타임 토글 — on은 운영 수동 UPDATE.
     */
    @Transactional(readOnly = true)
    public boolean fnbPipelineEnabled() {
        return settings.findById(FNB_PIPELINE_ENABLED)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElse(false);
    }
```

- [ ] **Step 4: 쿼리 8개에 `:includeFnb` 술어 추가** — 패턴: 기존 `i.beauty = true and (i.beautyCompany is null or i.beautyCompany = false)`를 괄호로 감싸 `or (:includeFnb = true and i.fnb = true and (i.fnbCompany is null or i.fnbCompany = false))`를 붙인다. 예시(`findCollectTargets` — 나머지 7개도 동일 치환):

```java
    /**
     * 수집 대상: 판정 통과 + 카테고리 확정 + (백필 안 된 것 우선) + 재방문 주기(revisitBefore)가
     * 지난 것만. 기본 모수는 뷰티 인플루언서(회사 제외)이고, includeFnb=true(fnb.pipeline-enabled
     * 토글)면 F&B 인플루언서(회사 제외)도 포함한다 — 스펙 2026-08-23 §4.
     */
    @Query("select i from Influencer i where i.status = 'QUALIFIED' and ("
            + "(i.beauty = true and (i.beautyCompany is null or i.beautyCompany = false)) "
            + "or (:includeFnb = true and i.fnb = true and (i.fnbCompany is null or i.fnbCompany = false))) "
            + "and (i.firstCollectedAt is null or i.lastCollectedAt < :revisitBefore) "
            + "order by case when i.firstCollectedAt is null then 0 else 1 end, i.lastCollectedAt asc nulls first")
    List<Influencer> findCollectTargets(@Param("revisitBefore") Instant revisitBefore,
                                        @Param("includeFnb") boolean includeFnb, Pageable pageable);
```

동일 치환 대상 전체 (모두 `@Param("includeFnb") boolean includeFnb` 추가):
- `countBackfillPending()` → `countBackfillPending(boolean includeFnb)`
- `countTrackDue(Instant, boolean)`
- `findCollectTargets(Instant, boolean, Pageable)` (위)
- `findReelsTargets(Instant, boolean, Pageable)`
- `countReelsDue(Instant, boolean)`
- `findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus, boolean, Pageable)`
- `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus, boolean)`
- `countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(InfluencerStatus, boolean)`

`countBeautyInfluencers`·`countBeautyCompanies`·명단 필터 쿼리는 **뷰티 축 전용이므로 무변경**.

- [ ] **Step 5: 콜사이트 갱신** — `./gradlew :crawler:compileJava`의 컴파일 에러를 따라 전수 수정. 각 콜사이트는 `settings.fnbPipelineEnabled()`를 전달한다 (CollectJob·ReelsJob·SimilarJob·StatusService·JobCostEstimator 모두 이미 `SettingsService`를 주입받고 있다 — 없다면 생성자 주입 추가). 예: `CollectJob` 124행 `influencers.findCollectTargets(revisitBefore, settings.fnbPipelineEnabled(), PageRequest.of(0, limit))`. 대시보드 수집 대기열 타일·예상 비용 카드가 토글 on 시 F&B 모수를 자동 반영하게 된다는 주석을 StatusService·JobCostEstimator 해당 지점에 한 줄 남긴다.

- [ ] **Step 6: 단위 테스트 mock 시그니처 갱신** — `CollectJobIntegrationTest`·`ReelsJobTest`·`SimilarJobTest`·`ScheduleRunnerTest`·`JobCostEstimatorTest`·`UiSmokeTest` 등에서 위 8개 메서드를 스텁하는 곳에 `anyBoolean()`/`eq(false)` 인자를 추가(동작 단언 불변).

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :crawler:test`
Expected: PASS (crawler 모듈 전체 — 시그니처 변경 여파 전수 검증)

- [ ] **Step 8: 커밋**

```bash
git add -A && git commit -m "feat(crawler): F&B 수집·시드 게이트 — fnb.pipeline-enabled 토글, 기본 off (스펙 §4)"
```

---

### Task 5: 어드민 — 명단 F&B 필터·오버라이드·대시보드 타일

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/InfluencerBeautyController.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java` (명단·카운트 쿼리 5개 추가)
- Modify: `crawler/src/main/resources/templates/influencers.html`
- Test: `InfluencerBeautyControllerTest.java`, `UiSmokeTest.java`

**Interfaces:**
- Consumes: Task 1의 `CategoryClass`·`classifyFnb`.
- Produces: `GET /ui/influencers?fnb=INFLUENCER|COMPANY|SERVICE|FOREIGN_INFLUENCER|NONE|UNJUDGED`(뷰티 필터와 동시 선택 시 뷰티 우선), `POST /ui/influencers/{id}/fnb?fnbClass=...`, `StatusSummary.fnbInfluencer()/fnbUnjudged()`.

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성** — `InfluencerBeautyControllerTest`에 기존 오버라이드 테스트 관용구를 본떠 추가:

```java
    @Test
    void fnb_수동_오버라이드는_MANUAL_출처로_저장된다() throws Exception {
        // 기존 뷰티 오버라이드 테스트의 셋업 관용구 그대로, 엔드포인트만 /fnb
        mvc.perform(post("/ui/influencers/" + inf.getId() + "/fnb")
                        .param("fnbClass", "SERVICE"))
                .andExpect(status().is3xxRedirection());
        Influencer saved = influencers.findById(inf.getId()).orElseThrow();
        assertThat(saved.getFnbClass()).isEqualTo(CategoryClass.SERVICE);
        assertThat(saved.getFnb()).isFalse();
        assertThat(saved.getFnbSource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.InfluencerBeautyControllerTest"`
Expected: FAIL (404 — 엔드포인트 없음)

- [ ] **Step 3: 오버라이드 엔드포인트 추가** — `InfluencerBeautyController`:

```java
    /** F&B 축 수동 오버라이드 — MANUAL 출처는 백필 선정(fnb IS NULL)에서 자연 제외돼 보존된다. */
    @PostMapping("/ui/influencers/{id}/fnb")
    public String overrideFnb(@PathVariable Long id, @RequestParam CategoryClass fnbClass,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(required = false) List<InfluencerStatus> status,
                              @RequestParam(required = false) List<String> beauty,
                              @RequestParam(required = false) List<String> fnb,
                              RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classifyFnb(fnbClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정", null);
        influencers.save(inf);
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        if (beauty != null && !beauty.isEmpty()) ra.addAttribute("beauty", beauty);
        if (fnb != null && !fnb.isEmpty()) ra.addAttribute("fnb", fnb);
        return "redirect:/ui/influencers";
    }
```

기존 `override(...)`에도 `@RequestParam(required = false) List<String> fnb` 파라미터와 `if (fnb != null && !fnb.isEmpty()) ra.addAttribute("fnb", fnb);` 보존 라인을 추가한다.

- [ ] **Step 4: 명단 쿼리·필터 추가**

`InfluencerRepository`에 (기존 beautyClass 3종 관용구와 대칭):

```java
    /** 명단 F&B 필터: 선택한 분류(fnb_class)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndFnbClassIn(
            java.util.Collection<InfluencerStatus> statuses,
            java.util.Collection<CategoryClass> classes, Pageable pageable);

    /** 명단 F&B 필터: F&B 미판정(백필 잔여)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndFnbClassIsNull(
            java.util.Collection<InfluencerStatus> statuses, Pageable pageable);

    /** 명단 F&B 필터: 선택 분류 + 미판정을 함께 체크한 경우. */
    @Query("select i from Influencer i where i.status in :statuses "
            + "and (i.fnbClass in :classes or i.fnbClass is null)")
    org.springframework.data.domain.Page<Influencer> findByStatusInAndFnbClassInOrNull(
            @Param("statuses") java.util.Collection<InfluencerStatus> statuses,
            @Param("classes") java.util.Collection<CategoryClass> classes, Pageable pageable);

    /** 대시보드 F&B 판정 그룹용: F&B 인플루언서(회사 제외) 수. */
    @Query("select count(i) from Influencer i where i.status = :status and i.fnb = true "
            + "and (i.fnbCompany is null or i.fnbCompany = false)")
    long countFnbInfluencers(@Param("status") InfluencerStatus status);

    /** 대시보드 F&B 판정 그룹용: F&B 축 미판정(백필 잔여) 수 — 백필 진행률 지표. */
    long countByStatusAndFnbIsNull(InfluencerStatus status);
```

`UiController` — `BEAUTY_FILTERS` 아래에:

```java
    /** F&B 5분류 + 미판정 — 배지 CSS는 뷰티 것 재사용(색 의미 동일: 타깃/회사/서비스/외국인/아님). */
    private static final java.util.List<BeautyFilter> FNB_FILTERS = java.util.List.of(
            new BeautyFilter("INFLUENCER", "F&B", "BEAUTY"),
            new BeautyFilter("COMPANY", "F&B 회사", "BEAUTY_COMPANY"),
            new BeautyFilter("SERVICE", "매장·서비스", "BEAUTY_SERVICE"),
            new BeautyFilter("FOREIGN_INFLUENCER", "외국인", "FOREIGN_INFLUENCER"),
            new BeautyFilter("NONE", "F&B 아님", "NOT_BEAUTY"),
            new BeautyFilter("UNJUDGED", "미판정", "UNJUDGED"));
```

`influencers(...)` 메서드 — 파라미터에 `@RequestParam(required = false) java.util.List<String> fnb` 추가, beautySelected 계산부 아래에:

```java
        var fnbKeys = FNB_FILTERS.stream().map(BeautyFilter::key).toList();
        var fnbSelected = fnb == null ? java.util.List.<String>of()
                                      : fnb.stream().filter(fnbKeys::contains).toList();
        boolean fnbUnjudged = fnbSelected.contains("UNJUDGED");
        var fnbClasses = fnbSelected.stream().filter(k -> !"UNJUDGED".equals(k))
                .map(com.celfit.crawler.crawling.domain.CategoryClass::valueOf).toList();
```

디스패치를 재구성 (뷰티 필터가 있으면 뷰티 우선 — 두 필터 동시 조합은 지원하지 않는다, 단순성 우선):

```java
        if (company) {
            result = influencers.findByStatusInAndBeautyTrueAndBeautyCompanyTrue(effective, pageable);
        } else if (!classes.isEmpty() || unjudged) {
            if (classes.isEmpty()) result = influencers.findByStatusInAndBeautyClassIsNull(effective, pageable);
            else if (!unjudged) result = influencers.findByStatusInAndBeautyClassIn(effective, classes, pageable);
            else result = influencers.findByStatusInAndBeautyClassInOrNull(effective, classes, pageable);
        } else if (!fnbClasses.isEmpty() || fnbUnjudged) {
            if (fnbClasses.isEmpty()) result = influencers.findByStatusInAndFnbClassIsNull(effective, pageable);
            else if (!fnbUnjudged) result = influencers.findByStatusInAndFnbClassIn(effective, fnbClasses, pageable);
            else result = influencers.findByStatusInAndFnbClassInOrNull(effective, fnbClasses, pageable);
        } else {
            result = influencers.findByStatusIn(effective, pageable);
        }
```

model 속성 추가:

```java
        model.addAttribute("fnb", fnbSelected);
        model.addAttribute("fnbFilters", FNB_FILTERS);
        model.addAttribute("fnbClasses", com.celfit.crawler.crawling.domain.CategoryClass.values());
```

- [ ] **Step 5: 대시보드 타일** — `StatusService.StatusSummary` record의 `beautyUnjudged` 다음에 `long fnbInfluencer, long fnbUnjudged,` 필드 추가, `summary()` 생성부의 `countByStatusAndBeautyIsNull(...)` 인자 다음에:

```java
                influencers.countFnbInfluencers(InfluencerStatus.QUALIFIED),
                influencers.countByStatusAndFnbIsNull(InfluencerStatus.QUALIFIED),
```

`UiController.statusTilesFragment`의 `influencerGroups`에서 ③ 그룹 뒤에 그룹 추가:

```java
                new StatusTileGroup("③-2 F&B 판정 — beauty 잡의 F&B 축 (QUALIFIED 내)", java.util.List.of(
                        new StatusTile("BEAUTY", s.fnbInfluencer(),
                                "F&B 인플루언서 · 수집 편입은 fnb.pipeline-enabled 토글(기본 off)"),
                        new StatusTile("UNJUDGED", s.fnbUnjudged(),
                                "F&B 미판정 · 백필 잔여"))),
```

- [ ] **Step 6: 명단 템플릿** — `influencers.html`:

(a) 뷰티 필터 체크박스 블록(30~33행 관용구) 바로 아래에 F&B 필터 그룹 추가:

```html
    <label class="check" th:unless="${companyView}" th:each="ff : ${fnbFilters}">
        <input type="checkbox" name="fnb" th:value="${ff.key()}"
               th:checked="${fnb != null && fnb.contains(ff.key())}"/>
        <span th:text="${'F&B: ' + ff.label()}"></span>
    </label>
```

(폼 submit 시 beauty·fnb가 같은 폼이면 그대로 두고, 마크업 구조는 기존 필터 폼을 따른다.)

(b) 뷰티 배지 `<td>`(50~68행) 다음에 F&B 열 추가 — `<th>`도 짝 맞춰 추가:

```html
        <td th:with="fcls=${row.influencer.fnbClass != null ? row.influencer.fnbClass.name() : null}">
            <span th:if="${fcls != null}" class="badge"
                  th:classappend="${fcls == 'INFLUENCER' ? 'BEAUTY' : (fcls == 'COMPANY' ? 'BEAUTY_COMPANY'
                      : (fcls == 'SERVICE' ? 'BEAUTY_SERVICE'
                      : (fcls == 'FOREIGN_INFLUENCER' ? 'FOREIGN_INFLUENCER' : 'NOT_BEAUTY')))}"
                  th:text="${fcls == 'INFLUENCER' ? 'F&B' : (fcls == 'COMPANY' ? 'F&B 회사'
                      : (fcls == 'SERVICE' ? '매장·서비스'
                      : (fcls == 'FOREIGN_INFLUENCER' ? 'F&B 외국인' : 'F&B 아님')))}"
                  th:title="${row.influencer.fnbReason}"></span>
            <span th:if="${fcls == null}">—</span>
            <form method="post" th:action="@{|/ui/influencers/${row.influencer.id}/fnb|}"
                  style="display:inline" th:each="target : ${fnbClasses}"
                  th:if="${target.name() != fcls}">
                <input type="hidden" name="page" th:value="${page.number}"/>
                <input type="hidden" th:each="s : ${status}" name="status" th:value="${s}"/>
                <input type="hidden" th:each="b : ${beauty}" name="beauty" th:value="${b}"/>
                <input type="hidden" th:each="f : ${fnb}" name="fnb" th:value="${f}"/>
                <input type="hidden" name="fnbClass" th:value="${target.name()}"/>
                <!-- 버튼 마크업은 옆 뷰티 오버라이드 폼(62~68행 부근)의 것을 그대로 복제 -->
            </form>
        </td>
```

(오버라이드 폼 내부 버튼·툴팁 마크업은 뷰티 열의 실제 마크업을 그대로 미러 — hidden input 구성만 위처럼 fnb 추가.)

(c) 페이지네이션 링크(80·83행)에 `fnb=${fnb}` 파라미터 추가. 뷰티 오버라이드 폼의 hidden에도 `fnb` 보존 라인 추가.

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.adapter.in.web.InfluencerBeautyControllerTest" --tests "com.celfit.crawler.dashboard.adapter.in.web.UiSmokeTest"`
Expected: PASS (UiSmokeTest가 명단·대시보드 렌더를 커버 — 템플릿 오류는 여기서 터진다)

- [ ] **Step 8: (선택) 어드민 UI 실물 확인** — 레포 `verify` 스킬 레시피로 `/ui/influencers`·대시보드에서 F&B 필터·배지·타일 렌더 확인.

- [ ] **Step 9: 커밋**

```bash
git add -A && git commit -m "feat(crawler): 어드민 명단 F&B 필터·수동 오버라이드·대시보드 F&B 타일 (스펙 §5)"
```

---

### Task 6: 분석 뷰 — v_base_influencer에 fnb 노출 + 하니스

**Files:**
- Modify: `analytics/views/00_base.sql` (v_base_influencer만)
- Modify: `analytics/seed/dummy.sql` (fnb 시드 1줄)
- Modify: `analytics/test/00_base.test.sql` (단언 2개)

**Interfaces:**
- Produces: `analytics.v_base_influencer.fnb / fnb_company / fnb_judged_at` 컬럼. 상위 뷰(01/02/20)·미러는 무변경 — 소비자 없음(스펙 §6).

- [ ] **Step 1: 뷰 컬럼 추가** — `00_base.sql`의 `v_base_influencer`를 다음으로 교체 (기존 컬럼 순서 유지, 끝에 추가 — CREATE OR REPLACE 제약):

```sql
-- influencer 노출 — 서빙 모수(뷰티 인플루언서) 필터 재료. 필터 자체는 상위 뷰(01·02·20) 몫.
-- fnb 축(2026-08-24)은 노출만 — 현재 소비자 없음, 추후 카테고리 서빙 개편 재료(스펙 2026-08-23 §6).
CREATE OR REPLACE VIEW analytics.v_base_influencer AS
SELECT
  id AS influencer_id,
  username,
  status,
  followers,
  beauty,
  beauty_company,
  beauty_judged_at,
  fnb,
  fnb_company,
  fnb_judged_at
FROM influencer;
```

- [ ] **Step 2: 시드·단언 추가**

`seed/dummy.sql` — influencer INSERT 이후에 한 줄 추가:

```sql
-- F&B 축 시드: dummy_a는 뷰티+F&B 겸임(복수 카테고리), 나머지는 F&B 미판정(NULL)
UPDATE influencer SET fnb = true, fnb_company = false, fnb_class = 'INFLUENCER'
WHERE username = 'dummy_a';
```

`test/00_base.test.sql` — v_base_influencer 단언 블록에 추가:

```sql
  ASSERT (SELECT fnb FROM analytics.v_base_influencer WHERE username = 'dummy_a') = true,
    'v_base_influencer dummy_a fnb != true';
  ASSERT (SELECT fnb FROM analytics.v_base_influencer WHERE username = 'dummy_co') IS NULL,
    'v_base_influencer dummy_co fnb not null (미판정)';
```

- [ ] **Step 3: 하니스 실행**

전제: 대상 DB에 Task 1 마이그레이션이 적용돼 있어야 한다(fnb 컬럼). 로컬 실데이터 컨테이너에는 crawler를 한 번 기동해 Flyway를 적용하거나(`./gradlew :crawler:bootRun` 후 즉시 종료), 테스트 전용이면 프레시 DB에 마이그레이션 적용 후 실행(CI `sql-harness` 잡과 동일 구조).

```bash
analytics/test/run.sh test/00_base.test.sql
```

Expected: `PASS: test/00_base.test.sql` (다른 테스트 회귀 확인은 `analytics/test/run.sh` 전체)

- [ ] **Step 4: 커밋**

```bash
git add -A && git commit -m "feat(analytics): v_base_influencer에 fnb 축 노출 — 소비자 없음, 서빙 무변경 (스펙 §6)"
```

---

### Task 7: 문서·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위 항목 추가)
- Create: `docs/tracks/<다음 미사용 문자>-fnb-카테고리.md` (`ls docs/tracks/`로 다음 문자 확인 — JJ까지 사용 중이면 KK)
- Modify: `ARCHITECTURE.md` §3 raw DB 표의 `influencer` 행 — beauty 판정 설명에 `+ F&B 축(fnb_class 5분류, 2026-08-24)` 추가
- Modify: `docs/superpowers/specs/2026-08-23-fnb-category-design.md` 상태 헤더 → `> 상태: ✅ 구현됨`
- Move: 이 계획 문서 → `docs/superpowers/plans/archive/` (PR을 여는 커밋에서)

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 추가**

```markdown
## 2026-08-24 — F&B 카테고리 추가: 판정 2축화, 수집은 토글 뒤

- **뭘**: 판정을 뷰티+F&B 2축으로 확장. influencer에 fnb_* 병렬 컬럼, LLM 1콜 2축 동시 판정,
  기존 판정분은 F&B 축만 백필(뷰티 판정·MANUAL 보존). 수집·시드·비용 추정의 F&B 편입은
  app_setting `fnb.pipeline-enabled`(기본 false)로 게이트. 서빙 모수는 뷰티 유지.
- **왜**: F&B(식품/음료 제품 시딩 + 요리·레시피) 타깃 확장. 복수 카테고리 허용(계정이 두 축
  모두 가능)이라 boolean 축 병렬이 자연 모델. 카테고리 3개 이상이 되면 influencer_category
  테이블로 이관 검토(스펙 §1 대안 기각 사유 참조).
- 설계: docs/superpowers/specs/2026-08-23-fnb-category-design.md
```

- [ ] **Step 2: 트랙 파일 생성** — `docs/tracks/` 관례(기존 파일 형식 참조)에 맞춰 트랙 요약·상태(✅ 구현 완료 / ⬜ 토글 on은 백필 완료 후 별도 결정)·후속(F&B rejudge, 카테고리 서빙 개편) 기록.

- [ ] **Step 3: 스펙 상태 헤더 갱신 + 계획 문서 아카이브 이동**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-08-24-fnb-category.md docs/superpowers/plans/archive/
```

- [ ] **Step 4: PR 직전 전체 검증**

```bash
./gradlew test
```

Expected: 4모듈 전체 PASS. (analytics 하니스는 CI `sql-harness` 잡이 프레시 DB로 재검증.)

- [ ] **Step 5: 커밋 + PR**

```bash
git add -A && git commit -m "docs: F&B 카테고리 결정 기록·트랙 파일·스펙 상태 갱신"
git push -u origin HEAD
gh pr create --base develop --title "feat(crawler): F&B 카테고리 추가 — 판정 2축화, 수집은 토글 뒤" --body "$(cat <<'EOF'
## 요약
- 판정을 뷰티+F&B 2축으로 확장 (influencer fnb_* 컬럼, LLM 1콜 2축 판정)
- 기존 판정분 전체 F&B 백필 — 뷰티 판정(MANUAL 포함) 보존
- 수집·시드·비용 추정 F&B 편입은 `fnb.pipeline-enabled` 토글(기본 off)
- 어드민 명단 F&B 필터·오버라이드·대시보드 타일, v_base_influencer fnb 노출
- 서빙 모수(분석 뷰 01/02/20·was) 무변경

설계: docs/superpowers/specs/2026-08-23-fnb-category-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## 배포 후 운영 메모 (코드 아님)

- 머지·배포 직후부터 새벽 beauty 크론이 신규 2축 판정 + 백필을 자동 시작한다(배치 한도 `beauty.batch-limit` 내). 백필을 당기려면 어드민에서 beauty 수동 트리거.
- 백필 진행률은 대시보드 "③-2 F&B 판정" 타일의 미판정 수로 확인.
- 수집 편입은 F&B 모수·Hiker 비용 확인 후: `UPDATE app_setting SET value='true' WHERE key='fnb.pipeline-enabled';` (재기동 불필요 — 잡이 실행 시점마다 읽는다.)
