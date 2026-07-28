# 뷰티 판정 v3 — 한국어 콘텐츠 필터(FOREIGN_INFLUENCER) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) 구문으로 추적한다.

**Goal:** 뷰티 판정에 한국어 콘텐츠 여부를 추가해 한국인 뷰티 인플루언서만 시드·수집·서빙 모수에 남긴다.

**Architecture:** BeautyClass에 5번째 값 `FOREIGN_INFLUENCER`(beauty=false 파생)를 추가하고, 판정 프롬프트(v3)에 한국어 콘텐츠 기준·경계 규칙을 넣는다. 하류(analytics·was)는 파생 boolean만 읽으므로 변경 없음. 기존 CLAUDE 판정 INFLUENCER분은 일회성 스크립트로 초기화 후 재판정.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway, Thymeleaf, JUnit5+AssertJ+Mockito.

**스펙:** `docs/superpowers/specs/2026-07-28-beauty-korean-filter-design.md`

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(crawler):` 식.
- 파생 규칙 단일 원천은 `BeautyClass.beauty()/company()` — 다른 곳에서 재정의 금지.
- 프롬프트·파서 단일 원천은 `ClaudeCliBeautyJudge.buildPrompt/parse` — API·Gemini 어댑터가 재사용.
- analytics·was·contract-analysis 모듈은 건드리지 않는다.
- 테스트 실행: `./gradlew :crawler:test --tests '<클래스>'` (전체는 `./gradlew :crawler:test`).

---

### Task 1: BeautyClass에 FOREIGN_INFLUENCER 추가 + DB 제약 마이그레이션

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/domain/BeautyClass.java`
- Create: `crawler/src/main/resources/db/migration/V21__beauty_foreign_influencer.sql`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/domain/BeautyClassTest.java`

**Interfaces:**
- Produces: `BeautyClass.FOREIGN_INFLUENCER` (beauty()=false, company()=false) — 이후 모든 태스크가 사용.

**주의:** enum 값을 추가하면 `BeautyJob.applyVerdicts`의 exhaustive switch 2곳이 컴파일 에러가 난다. 이 태스크에서는 두 switch에 임시가 아닌 **최종 케이스**(Task 3의 코드와 동일)를 함께 넣어도 되지만, TDD 순서를 지키기 위해 Task 1에서는 컴파일만 통과하도록 Task 3에 명시된 케이스를 그대로 추가한다(코드 중복 없음 — Task 3은 Summary 확장과 테스트만 추가).

- [ ] **Step 1: BeautyClassTest에 실패하는 테스트 추가**

기존 `파생_boolean_규칙_인플루언서와_회사만_beauty_true()`에 두 줄 추가:

```java
assertThat(BeautyClass.FOREIGN_INFLUENCER.beauty()).isFalse();
assertThat(BeautyClass.FOREIGN_INFLUENCER.company()).isFalse();
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :crawler:compileTestJava`
Expected: FAIL — `FOREIGN_INFLUENCER` 심볼 없음.

- [ ] **Step 3: enum 값 추가**

`BeautyClass.java`의 `BEAUTY_SERVICE` 다음에:

```java
    /** 뷰티 제품 개인 크리에이터지만 한국어 콘텐츠가 아님(v3) — 한국 시장 시딩 타깃 아님. */
    FOREIGN_INFLUENCER,
```

클래스 javadoc 첫머리도 v3로 갱신:

```java
/**
 * 뷰티 판정 5분류 (v3, 2026-07-28 스펙) — 판정 목적은 뷰티 제품(스킨케어·메이크업·향수 등)
 * 한국 시장 시딩·협찬 대상 발굴. boolean(beauty/beauty_company) 파생 규칙의 단일 원천.
 * BEAUTY_SERVICE·FOREIGN_INFLUENCER는 beauty=false로 파생 — 시드·수집·서빙 모수에서 자동 제외된다.
 */
```

`beauty()`/`company()` 본문은 변경 없음.

- [ ] **Step 4: BeautyJob switch 컴파일 에러 해소**

`BeautyJob.applyVerdicts`의 두 switch에 케이스 추가(집계 필드는 Task 3에서 확장 — 여기서는 컴파일 통과 목적의 최소 형태로 `notBeauty`에 합산하지 말고 임시 변수 없이 라벨만):

집계 switch(171행 부근) — 임시로 `case FOREIGN_INFLUENCER -> { }` 를 넣지 **말고**, Task 3과의 이중 작업을 피하기 위해 Task 3의 최종 코드를 여기서 적용한다:

```java
switch (v.beautyClass()) {
    case INFLUENCER, COMPANY -> beauty++;
    case BEAUTY_SERVICE -> service++;
    case FOREIGN_INFLUENCER -> foreign++;
    case NOT_BEAUTY -> notBeauty++;
}
```

라벨 switch(177행 부근):

```java
String label = switch (v.beautyClass()) {
    case INFLUENCER -> "뷰티(인플루언서)";
    case COMPANY -> "뷰티(회사)";
    case BEAUTY_SERVICE -> "뷰티(시술·서비스)";
    case FOREIGN_INFLUENCER -> "뷰티(외국인)";
    case NOT_BEAUTY -> "비뷰티";
};
```

이에 따라 같은 파일의 `Summary`·`ChunkResult`·`run()` 집계도 함께 확장해야 컴파일된다 — Task 3의 코드를 그대로 적용:

```java
public record Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty,
                      int skippedNoProfile, int failedBatches) {}
```

```java
private record ChunkResult(int beauty, int service, int foreign, int notBeauty) {}
```

`run()`의 집계부:

```java
int beauty = 0, service = 0, foreign = 0, notBeauty = 0, failedBatches = 0;
```

```java
int done = beauty + service + foreign + notBeauty;
ChunkResult r = txTemplate.execute(status -> applyVerdicts(verdicts, byUsername, done, cards.size()));
beauty += r.beauty();
service += r.service();
foreign += r.foreign();
notBeauty += r.notBeauty();
log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 시술·서비스 {} / 외국인 {} / 비뷰티 {}",
        i, total, beauty, service, foreign, notBeauty);
```

```java
return new Summary(beauty, service, foreign, notBeauty, skipped, failedBatches);
```

`applyVerdicts` 시그니처 반환부:

```java
int beauty = 0, service = 0, foreign = 0, notBeauty = 0;
...
return new ChunkResult(beauty, service, foreign, notBeauty);
```

기존 `BeautyJobTest`의 `Summary` 생성·검증부가 컴파일 에러나면 필드 순서에 맞춰 고친다(테스트 의미 변경 없음 — `judgedForeign` 자리는 0).

- [ ] **Step 5: Flyway 마이그레이션 작성**

`V21__beauty_foreign_influencer.sql`:

```sql
-- 뷰티 판정 v3 — 한국어 콘텐츠 필터. FOREIGN_INFLUENCER(외국인 뷰티 인플루언서) 분류 추가.
-- beauty=false로 파생되어 시드·수집·서빙 모수에서 자동 제외, 세그먼트로만 보존된다.
alter table influencer drop constraint influencer_beauty_class_check;
alter table influencer add constraint influencer_beauty_class_check
    check (beauty_class in ('INFLUENCER', 'COMPANY', 'BEAUTY_SERVICE', 'FOREIGN_INFLUENCER', 'NOT_BEAUTY'));
-- 기존 INFLUENCER 판정분은 새 기준 재판정 대상 — deploy/scripts/reset-influencer-judgments-v3.sql 참조.
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.domain.BeautyClassTest' --tests 'com.celfit.crawler.crawling.application.service.BeautyJobTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(crawler): 뷰티 판정 v3 — FOREIGN_INFLUENCER 분류 추가(enum·제약·집계)"
```

---

### Task 2: 판정 프롬프트 v3 + 파서 확장

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudge.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/out/claude/ClaudeCliBeautyJudgeTest.java`

**Interfaces:**
- Consumes: `BeautyClass.FOREIGN_INFLUENCER` (Task 1)
- Produces: v3 프롬프트(`buildPrompt`)·5분류 파서(`parse`) — Claude API·Gemini 어댑터 자동 반영.

- [ ] **Step 1: 실패하는 테스트 추가/수정**

`ClaudeCliBeautyJudgeTest`에서:

(a) `코드펜스로_감싼_4분류_JSON_배열을_판정으로_파싱한다`를 5분류로 개명·확장 — 입력 배열에 한 줄 추가:

```java
{"username":"e","class":"FOREIGN_INFLUENCER","reason":"영어 뷰티 콘텐츠"}
```

기대값에 추가:

```java
new BeautyJudge.Verdict("e", BeautyClass.FOREIGN_INFLUENCER, "영어 뷰티 콘텐츠")
```

(b) `Verdict의_파생_boolean은_BeautyClass_규칙을_따른다`에 한 줄 추가:

```java
assertThat(new BeautyJudge.Verdict("a", BeautyClass.FOREIGN_INFLUENCER, null).beauty()).isFalse();
```

(c) 프롬프트 테스트 확장 — 기존 `프롬프트에_판정_목적과_카드_JSON과_4분류_출력_형식_지시가_들어간다`를 5분류로 개명하고 검증 추가:

```java
.contains("FOREIGN_INFLUENCER").contains("한국어")
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudgeTest'`
Expected: FAIL — 파서가 FOREIGN_INFLUENCER를 건너뛰고, 프롬프트에 해당 문구 없음.

- [ ] **Step 3: 프롬프트 v3 + 파서 구현**

`buildPrompt`를 다음으로 교체:

```java
public static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
    return """
            너는 뷰티 마케팅 리스트업 서비스의 분류기다. 목적: 한국 시장에서 뷰티 제품(스킨케어·\
            메이크업·향수·헤어/바디케어 제품 등)을 시딩·협찬·광고할 한국인 인플루언서와, 그런 \
            인플루언서를 필요로 하는 뷰티 제품 회사를 찾는 것.
            다음 인스타그램 계정 프로필 목록(JSON)의 각 계정을 다섯 중 하나로 분류하라:
            - INFLUENCER: 한국어 콘텐츠 중심의 뷰티 제품 개인 크리에이터. 광고·협찬 게시물만이 \
            아니라 오가닉 뷰티 콘텐츠를 올리는 개인도 포함.
            - FOREIGN_INFLUENCER: 뷰티 제품 개인 크리에이터지만 한국어 콘텐츠 중심이 아닌 계정 \
            (외국어 bio·캡션으로 해외 오디언스 대상)
            - COMPANY: 뷰티 제품을 제작·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
            - BEAUTY_SERVICE: 뷰티 영역이지만 시술·서비스 중심 — 피부과·성형외과·에스테틱·헤어샵/\
            미용실·네일샵·왁싱·속눈썹·반영구 등 시술을 파는 업체, 그리고 헤어 디자이너·네일 아티스트·\
            시술 후기 위주 계정 같은 시술·서비스 중심 개인
            - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정
            경계 규칙:
            - 시술 업체가 자체 제품도 팔면 콘텐츠 주력 기준으로 — 시술·매장 홍보 중심이면 \
            BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY.
            - 한국어 판정은 캡션이 최우선 신호다 — bio가 영어라도 캡션이 주로 한국어면 한국어 \
            콘텐츠(INFLUENCER)로 판정하라(한국 계정이 영어 bio를 쓰는 경우가 흔하다).
            - 한국어·외국어를 섞어 쓰면 주 오디언스가 한국인지 기준으로 판정하라.
            - 캡션이 빈 배열(미수집)이고 bio만으로 모호하면 이름·bio의 한국어 여부로 판정하라.
            captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
            캡션의 실제 콘텐츠 주제를 근거로 판정하라.
            출력은 JSON 배열만: [{"username":"...","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY","reason":"한 줄"}]
            입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

            """ + om.writeValueAsString(cards);
}
```

`parse`의 switch에 케이스 추가(주석의 "4분류"도 "5분류"로):

```java
case "FOREIGN_INFLUENCER" -> out.add(new Verdict(username, BeautyClass.FOREIGN_INFLUENCER, n.path("reason").asString(null)));
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudgeTest' --tests 'com.celfit.crawler.crawling.adapter.out.gemini.GeminiBeautyJudgeTest' --tests 'com.celfit.crawler.crawling.adapter.out.claude.ClaudeApiBeautyJudgeTest'`
Expected: PASS (Gemini·API 어댑터 테스트가 프롬프트 문구를 검증한다면 함께 수정)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(crawler): 뷰티 판정 프롬프트 v3 — 한국어 콘텐츠 기준·FOREIGN_INFLUENCER 파싱"
```

---

### Task 3: BeautyJob 집계 검증 테스트

**Files:**
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

**Interfaces:**
- Consumes: Task 1의 `Summary(judgedBeauty, judgedService, judgedForeign, judgedNotBeauty, skippedNoProfile, failedBatches)`

구현은 Task 1 Step 4에서 이미 끝났다 — 이 태스크는 FOREIGN_INFLUENCER 판정이 저장·집계되는지 테스트로 고정한다.

- [ ] **Step 1: 기존 집계 테스트 확장**

`판정_결과가_beauty_class와_파생_boolean으로_저장되고_Summary가_구분_집계한다`에 외국인 케이스 추가:

```java
Influencer for1 = qualified(5L, "for1");
// findByStatusAndBeautyIsNull 스텁 리스트에 for1 추가, id 1~5 프로필 스텁
when(judge.judge(any())).thenReturn(List.of(
        new BeautyJudge.Verdict("inf1", BeautyClass.INFLUENCER, "메이크업 크리에이터"),
        new BeautyJudge.Verdict("com1", BeautyClass.COMPANY, "화장품 브랜드"),
        new BeautyJudge.Verdict("svc1", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보"),
        new BeautyJudge.Verdict("for1", BeautyClass.FOREIGN_INFLUENCER, "영어 뷰티 콘텐츠"),
        new BeautyJudge.Verdict("no1", BeautyClass.NOT_BEAUTY, "여행 계정")));
```

검증 추가:

```java
assertThat(s.judgedForeign()).isEqualTo(1);     // FOREIGN_INFLUENCER
assertThat(for1.getBeautyClass()).isEqualTo(BeautyClass.FOREIGN_INFLUENCER);
assertThat(for1.getBeauty()).isFalse();
assertThat(for1.getBeautyCompany()).isFalse();
```

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.BeautyJobTest'`
Expected: PASS (Task 1에서 구현 완료 상태이므로 바로 통과 — 실패하면 Task 1 집계 코드 버그)

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test(crawler): FOREIGN_INFLUENCER 판정 저장·집계 테스트"
```

---

### Task 4: 어드민 UI — 대시보드 타일·명단 필터·배지

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/StatusService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java`
- Modify: `crawler/src/main/resources/templates/influencers.html`
- Modify: `crawler/src/main/resources/static/css/admin.css`

**Interfaces:**
- Consumes: `InfluencerRepository.countByStatusAndBeautyClass(status, beautyClass)` (기존), `BeautyClass.FOREIGN_INFLUENCER`
- Produces: `StatusSummary.beautyForeign` 필드

- [ ] **Step 1: StatusSummary에 beautyForeign 추가**

record에 `beautyService` 다음 필드 추가:

```java
long beautyForeign,
```

`summary()`의 생성자 호출에서 `beautyService` 카운트 다음 줄에:

```java
influencers.countByStatusAndBeautyClass(InfluencerStatus.QUALIFIED, BeautyClass.FOREIGN_INFLUENCER),
```

- [ ] **Step 2: 대시보드 타일 추가·NOT_BEAUTY 보정**

`UiController.statusTilesFragment`의 ③ 그룹에서 BEAUTY_SERVICE 타일 다음에 추가:

```java
new StatusTile("FOREIGN", s.beautyForeign(),
        "외국인 뷰티 인플루언서 · 한국 시장 타깃 제외"),
```

NOT_BEAUTY 타일 계산 보정(외국인 분리):

```java
new StatusTile("NOT_BEAUTY", s.beautyFalse() - s.beautyService() - s.beautyForeign(),
        "비뷰티 · 수집 제외"),
```

- [ ] **Step 3: 명단 필터 추가**

`BEAUTY_FILTERS`에서 BEAUTY_SERVICE 다음에:

```java
new BeautyFilter("FOREIGN_INFLUENCER", "외국인", "FOREIGN_INFLUENCER"),
```

주석 "뷰티 4분류 + 미판정"도 "뷰티 5분류 + 미판정"으로.

- [ ] **Step 4: influencers.html 배지·수동 판정 버튼 라벨**

v2 배지 스팬(52-55행)의 삼항 체인에 FOREIGN_INFLUENCER 추가:

```html
<span th:if="${cls != null}" class="badge"
      th:classappend="${cls == 'INFLUENCER'} ? 'BEAUTY' : (${cls == 'COMPANY'} ? 'BEAUTY_COMPANY' : (${cls == 'BEAUTY_SERVICE'} ? 'BEAUTY_SERVICE' : (${cls == 'FOREIGN_INFLUENCER'} ? 'FOREIGN_INFLUENCER' : 'NOT_BEAUTY')))"
      th:text="${cls == 'INFLUENCER'} ? '뷰티' : (${cls == 'COMPANY'} ? '뷰티 회사' : (${cls == 'BEAUTY_SERVICE'} ? '시술·서비스' : (${cls == 'FOREIGN_INFLUENCER'} ? '외국인' : '뷰티 아님')))"
      th:title="${row.influencer.beautyReason}"></span>
```

수동 판정 버튼 라벨(70행)에도:

```html
th:text="${target.name() == 'INFLUENCER'} ? '뷰티' : (${target.name() == 'COMPANY'} ? '회사' : (${target.name() == 'BEAUTY_SERVICE'} ? '시술' : (${target.name() == 'FOREIGN_INFLUENCER'} ? '외국인' : '아님')))"
```

- [ ] **Step 5: admin.css 배지 색**

`.badge.BEAUTY_SERVICE` 다음에:

```css
.badge.FOREIGN_INFLUENCER { color: var(--muted); background: color-mix(in srgb, var(--muted) 10%, transparent); }
```

- [ ] **Step 6: 크롤러 전체 테스트**

Run: `./gradlew :crawler:test`
Expected: PASS (UiSmokeTest 포함 — 실패 시 해당 테스트의 기대 문구 갱신)

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(crawler): 어드민 UI — FOREIGN_INFLUENCER 타일·필터·배지"
```

---

### Task 5: 재판정 스크립트 + 문서 갱신

**Files:**
- Create: `deploy/scripts/reset-influencer-judgments-v3.sql`
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표 · §7 결정 기록)
- Modify: `docs/superpowers/specs/2026-07-28-beauty-korean-filter-design.md` (상태 헤더 → ✅ 구현됨)

- [ ] **Step 1: 재판정 스크립트 작성**

`deploy/scripts/reset-influencer-judgments-v3.sql`:

```sql
-- 뷰티 판정 v3(한국어 콘텐츠 필터) 전환 — CLAUDE 판정 INFLUENCER분만 판정 초기화
-- (2026-07-28 스펙의 일회성 운영 작업, MANUAL 판정은 보존).
-- 초기화 후 서버 어드민에서 BEAUTY 잡을 트리거하면 새 5분류 기준으로 재판정된다
-- (배치 한도는 어드민 설정 beauty.batch-limit — 대상 수에 따라 수 회 실행).
--
-- 실행(오라클 서버, raw DB 컨테이너):
--   docker compose exec -T postgres-raw psql -U crawler -d crawler < deploy/scripts/reset-influencer-judgments-v3.sql
begin;
update influencer
   set beauty          = null,
       beauty_company  = null,
       beauty_class    = null,
       beauty_source   = null,
       beauty_reason   = null,
       beauty_judged_at = null
 where beauty_class = 'INFLUENCER'
   and beauty_source = 'CLAUDE';
commit;
```

- [ ] **Step 2: ARCHITECTURE.md 갱신**

§5 작업 트랙 표에 뷰티 판정 v3 행 추가(형식은 기존 행 관례를 따름), §7 결정 기록에 항목 추가:

```markdown
- **2026-07-28 — 뷰티 판정 v3: 한국어 콘텐츠 필터.** 서비스 목적(한국 시장 시딩)에 맞춰
  INFLUENCER 정의에 한국어 콘텐츠 조건을 넣고 FOREIGN_INFLUENCER(beauty=false) 분류를 추가.
  COMPANY는 언어 무관 유지. 하류는 파생 boolean만 읽어 변경 없음. 기존 CLAUDE 판정
  INFLUENCER분은 reset-influencer-judgments-v3.sql로 초기화 후 재판정.
  (스펙: docs/superpowers/specs/2026-07-28-beauty-korean-filter-design.md)
```

스펙 문서 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨`으로 변경.

- [ ] **Step 3: 전체 테스트**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(crawler): 뷰티 판정 v3 재판정 스크립트·문서 갱신"
```
