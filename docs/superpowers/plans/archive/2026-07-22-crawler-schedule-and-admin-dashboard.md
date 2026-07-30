# 크롤링 스케줄 자동화 + 크롤러 어드민 대시보드 개편 — 구현 계획

> 상태: ✅ 구현됨 — `ScheduleRunner` 자동화 배선, "잡 실행"·"수집 게시물" 탭 제거·dashboard.html 통합 반영 확인

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 크롤링 4종 잡(qualify·beauty·collect·reels)을 운영 서버에서 윈도우 반복 크론으로 자동화하고, 크롤러 어드민의 잡 실행 UI를 대시보드로 통합하며 "잡 실행"·"수집 게시물" 탭을 코드까지 제거한다.

**Architecture:** 스케줄은 코드 무변경 — crawler에 이미 있는 `ScheduleRunner`(`crawler.schedule.enabled` 게이트)를 deploy/compose.yaml env로 점화한다(analytics와 동일 패턴, UTC 크론). UI는 Thymeleaf 템플릿 재배치 — 잡 실행 폼·예상 비용·실행 로그를 dashboard.html로 옮기고, 제거 페이지의 라우트·템플릿·전용 쿼리 코드를 삭제한다.

**Tech Stack:** Spring Boot 4.1 (Java 21), Thymeleaf + htmx, JPA, Gradle 멀티모듈(`:crawler`), MockMvc(@AutoConfigureMockMvc + Testcontainers `IntegrationTest` 베이스).

**설계 문서:** [specs/2026-07-22-crawler-schedule-and-admin-dashboard-design.md](../../specs/2026-07-22-crawler-schedule-and-admin-dashboard-design.md)

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix는 `feat(crawler):`/`chore(deploy):`/`docs:` 식.
- 작업 브랜치에서 진행, develop 대상 PR로 합친다. develop·main 직접 push 금지.
- 크론 표기: compose는 **UTC**(KST=UTC+9 주석 병기 — analytics 컨벤션), crawler application.yml 기본값은 **KST**(로컬 머신 존).
- discover·similar는 수동 유지 — discover 크론은 `-`(Spring 크론 비활성 표기), similar는 ScheduleRunner에 없음(추가하지 않는다).
- `POST /ui/jobs/*`(트리거·중지) 엔드포인트는 유지 — 대시보드 스트립이 사용. GET 페이지만 제거.
- 테스트 실행: `./gradlew :crawler:test`(Docker 필요 — Testcontainers). UI 스모크는 `--tests '*UiSmokeTest*'`.

---

### Task 1: 운영 스케줄 점화 (compose env + application.yml 기본값 정렬)

**Files:**
- Modify: `deploy/compose.yaml` (crawler 서비스, 약 116~140행)
- Modify: `crawler/src/main/resources/application.yml` (schedule 블록, 약 60~66행)

**Interfaces:**
- Consumes: `crawler/.../adapter/in/scheduler/ScheduleRunner.java`의 프로퍼티 키 `crawler.schedule.{enabled,discover-cron,qualify-cron,collect-cron,reels-cron,beauty-cron}` — env 상대 표기 `CRAWLER_SCHEDULE_*`은 Spring `SystemEnvironmentPropertySource` 변환으로 자동 매핑(analytics의 `ANALYTICS_SCHEDULE_*`로 운영 검증된 경로).
- Produces: 운영 crawler 컨테이너의 데일리 자동 실행. 이후 태스크와 코드 의존 없음.

- [ ] **Step 1: compose.yaml crawler 서비스에 스케줄 env 추가**

`deploy/compose.yaml`의 crawler 서비스 environment에서 `JAVA_OPTS: "-Xmx1g"` 바로 위에 추가:

```yaml
      # 스케줄(KST=UTC+9) — 윈도우 반복 발사: 잡들이 "남은 대상만" 선정하므로(재방문 컷오프·
      # DISCOVERED/미판정 잔여) 반복이 안전하고, 일시 실패·컨테이너 재기동을 윈도우 안에서 흡수한다.
      # collect 01:00~03:30/30분 · reels 01:10~03:55/15분 · qualify 02:00~03:30/30분 · beauty 03:00·03:30
      # → analytics 미러 04:30 전에 완결(같은 날 새벽 분석·아카이브 반영). 발굴(discover·similar)은 수동.
      CRAWLER_SCHEDULE_ENABLED: "true"
      CRAWLER_SCHEDULE_COLLECT_CRON: "0 0/30 16-18 * * *"
      CRAWLER_SCHEDULE_REELS_CRON: "0 10/15 16-18 * * *"
      CRAWLER_SCHEDULE_QUALIFY_CRON: "0 0/30 17-18 * * *"
      CRAWLER_SCHEDULE_BEAUTY_CRON: "0 0,30 18 * * *"
      CRAWLER_SCHEDULE_DISCOVER_CRON: "-"
```

- [ ] **Step 2: crawler 서비스 헤더 주석의 "수동 운영" 문구 갱신**

같은 파일 crawler 서비스 상단 주석:

```yaml
  # 수집 상주 서버(8080 어드민 /ui) — raw(postgres-raw)에 쓰기. 수동 운영: 어드민 UI로 잡 트리거.
  # 외부 미노출(루프백) — 접근은 SSH 터널(ssh -L 8080:localhost:8080). 스케줄은 기본 off(수동 운영).
```

를 다음으로 교체:

```yaml
  # 수집 상주 서버(8080 어드민 /ui) — raw(postgres-raw)에 쓰기.
  # 외부 미노출(루프백) — 접근은 SSH 터널(ssh -L 8080:localhost:8080).
  # 데일리 스케줄 자동(아래 CRAWLER_SCHEDULE_*) — 발굴(discover·similar)만 어드민 UI 수동 트리거.
```

- [ ] **Step 3: application.yml 기본 크론을 같은 타임라인(KST)으로 정렬**

`crawler/src/main/resources/application.yml`의 schedule 블록:

```yaml
  schedule:
    enabled: false
    discover-cron: "0 0 6 * * *"
    qualify-cron: "0 30 6 * * *"
    beauty-cron: "0 30 7 * * *"   # qualify 유입분 4분류 판정 — qualify 이후
    collect-cron: "0 0 1 * * *"   # 데일리 스냅샷(팔로워 추이+게시물) — 새벽에 길게
    reels-cron: "0 10 1 * * *"    # 릴스 1페이지 — collect와 병렬(잡 락 분리)
```

를 다음으로 교체 (enabled=false라 동작 무변경 — 운영 타임라인의 문서 역할, KST 표기):

```yaml
  schedule:
    enabled: false                       # 운영 점화는 deploy/compose.yaml env(UTC 크론)
    discover-cron: "0 0 6 * * *"         # 발굴은 수동 운영 — 운영 env에선 "-"(비활성)
    collect-cron: "0 0/30 1-3 * * *"     # 데일리 스냅샷 01:00~03:30/30분 — 윈도우 반복(잔여만 재시도)
    reels-cron: "0 10/15 1-3 * * *"      # 릴스 1페이지 01:10~03:55/15분 — 회당 10계정 한도 보완
    qualify-cron: "0 0/30 2-3 * * *"     # 판정 02:00~03:30/30분 — DISCOVERED 잔여만
    beauty-cron: "0 0,30 3 * * *"        # 뷰티 4분류 03:00·03:30 — qualify 이후, analytics 04:30 전
```

- [ ] **Step 4: compose 문법 검증**

Run: `docker compose -f deploy/compose.yaml config --quiet`
Expected: exit 0 (미설정 env 경고는 무시 — 서버 `.env`에서 채워진다)

- [ ] **Step 5: crawler 빌드로 yml 파싱 검증**

Run: `./gradlew :crawler:compileJava :crawler:processResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add deploy/compose.yaml crawler/src/main/resources/application.yml
git commit -m "feat(crawler): 크롤링 데일리 스케줄 점화 — 윈도우 반복 크론(실패·재기동 흡수), 발굴은 수동 유지"
```

---

### Task 2: 대시보드 통합 — 잡 실행 스트립 + flash + 예상 비용·실행 로그 이동

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java:65,74` (리다이렉트 2곳)
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java:96-100` (dashboard 라우트)
- Modify: `crawler/src/main/resources/templates/dashboard.html`
- Modify: `crawler/src/main/resources/static/css/admin.css` (`.job-strip` 신설)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/UiJobControllerTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/dashboard/adapter/in/web/UiSmokeTest.java`

**Interfaces:**
- Consumes: `POST /ui/jobs/{discover|qualify|collect|beauty|similar|reels}` · `POST /ui/jobs/{JOB}/stop`(기존, 무변경), `GET /ui/fragments/logs`(기존), `JobCostEstimator.estimates()`(기존), admin.js `data-persist`(sessionStorage 키가 pathname 기반이라 /ui로 옮겨도 독립 동작).
- Produces: `GET /ui` 모델에 `costs` 추가, 트리거·중지 리다이렉트가 `redirect:/ui`. Task 3·4가 이 상태(대시보드가 잡 실행 표면)를 전제로 페이지를 제거한다.
- 주의: 이 태스크 완료 시점에 `/ui/jobs` 페이지는 **아직 살아 있다**(기존 스모크 통과 유지) — 제거는 Task 3.

- [ ] **Step 1: UiJobControllerTest에 리다이렉트 변경을 먼저 반영 (실패하는 테스트)**

`UiJobControllerTest.java`에서 기존 중지 테스트의 기대값을 바꾸고 트리거 테스트를 추가:

```java
// 기존 테스트의 assertThat(view).isEqualTo("redirect:/ui/jobs"); 를 다음으로 교체
assertThat(view).isEqualTo("redirect:/ui");
```

클래스에 테스트 추가 (import에 `com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase.TriggerResult`, `com.celfit.crawler.crawling.domain.TriggerType` 필요):

```java
@Test
void 잡_트리거는_대시보드로_리다이렉트하고_시작_메시지를_남긴다() {
    when(jobService.trigger(JobName.COLLECT, TriggerType.MANUAL)).thenReturn(TriggerResult.ACCEPTED);
    var ra = new RedirectAttributesModelMap();

    String view = controller.collect(ra);

    assertThat(view).isEqualTo("redirect:/ui");
    assertThat(ra.getFlashAttributes().get("message").toString()).contains("실행 시작");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests '*UiJobControllerTest*'`
Expected: FAIL — `expected: "redirect:/ui" but was: "redirect:/ui/jobs"`

- [ ] **Step 3: UiJobController 리다이렉트 변경**

`UiJobController.java`의 두 곳 (`stop()` 끝의 `return "redirect:/ui/jobs";`, `respond()` 끝의 `return "redirect:/ui/jobs";`)을 모두 다음으로:

```java
return "redirect:/ui";
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests '*UiJobControllerTest*'`
Expected: PASS

- [ ] **Step 5: UiSmokeTest에 대시보드 통합 검증 추가 (실패하는 테스트)**

`UiSmokeTest.java`에 추가:

```java
@Test
void 대시보드에_잡_실행_스트립이_렌더된다() throws Exception {
    mvc.perform(get("/ui")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("job-strip")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/jobs/discover")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/jobs/REELS/stop")));
}

@Test
void 대시보드에_예상_비용_카드가_렌더된다() throws Exception {
    mvc.perform(get("/ui")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("예상 비용")));
}

@Test
void 대시보드에_실행_로그_패널이_렌더된다() throws Exception {
    mvc.perform(get("/ui")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/fragments/logs")));
}
```

- [ ] **Step 6: 실패 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*'`
Expected: 새 3개 테스트 FAIL (job-strip 등 미렌더), 기존 테스트는 PASS

- [ ] **Step 7: UiController 대시보드 라우트에 costs 모델 추가**

`UiController.java`의 dashboard 메서드를:

```java
@GetMapping("/ui")
public String dashboard(Model model) {
    model.addAttribute("summary", statusService.summary());
    model.addAttribute("costs", jobCostEstimator.estimates());
    return "dashboard";
}
```

- [ ] **Step 8: dashboard.html 재구성**

`dashboard.html`의 `<head>`에 admin.js 추가 (htmx 스크립트 다음 줄):

```html
    <script src="/js/admin.js"></script>
```

`<h1>대시보드</h1>` 바로 아래(현재 작업 바 `<div hx-get="/ui/fragments/status" ...>` 위)에 flash 추가:

```html
<div class="flash" th:if="${message}" th:text="${message}"></div>
```

`</details>`(파이프라인 구조 닫힘) 바로 다음, 상태 카드 주석 위에 잡 실행 스트립 삽입:

```html
<!-- 잡 실행 스트립 — 다이어그램의 각 잡을 바로 실행/중지 (긴 설명은 title 툴팁).
     중지는 협조적 — 진행 중 단위 작업까지 마치고 종료. 스케줄 자동화 후 수동 주 용도는 discover·similar. -->
<div class="job-strip">
    <form method="post" th:action="@{/ui/jobs/discover}">
        <span class="job-strip-name">discover</span>
        <button type="submit" class="primary" title="해시태그로 인플루언서 발굴">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/DISCOVER/stop}"
                title="진행 중인 키워드까지 마치고 멈춘다">중지</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/qualify}">
        <span class="job-strip-name">qualify</span>
        <label title="범위 변경 후 전체 재판정 — 기존 판정분 포함 · 재호출 없음">
            <input type="checkbox" name="requalify" value="true" data-persist/> 전체 재판정</label>
        <button type="submit" class="primary" title="프로필 스냅샷 · 팔로워 범위 판정">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/QUALIFY/stop}"
                title="진행 중인 프로필 청크까지 마치고 멈춘다">중지</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/beauty}">
        <span class="job-strip-name">beauty</span>
        <label title="Claude 판정분 재판정 — 수동 판정 보존 · 인스타 호출 없음">
            <input type="checkbox" name="rejudge" value="true" data-persist/> 재판정</label>
        <button type="submit" class="primary" title="뷰티 계정 판정">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/BEAUTY/stop}"
                title="진행 중인 판정 배치까지 마치고 멈춘다">중지</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/collect}">
        <span class="job-strip-name">collect</span>
        <button type="submit" class="primary" title="게시물을 위한 프로필 수집 (피드 12개 내장 · 릴스 제외)">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/COLLECT/stop}"
                title="진행 중인 방문까지 마치고 멈춘다">중지</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/reels}">
        <span class="job-strip-name">reels</span>
        <button type="submit" class="primary" title="릴스 수집 (계정당 HikerAPI 1요청)">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/REELS/stop}"
                title="진행 중인 방문까지 마치고 멈춘다">중지</button>
    </form>
    <form method="post" th:action="@{/ui/jobs/similar}">
        <span class="job-strip-name">similar</span>
        <button type="submit" class="primary" title="뷰티 시드의 유사 계정 발굴">실행</button>
        <button type="submit" class="danger" th:formaction="@{/ui/jobs/SIMILAR/stop}"
                title="진행 중인 시드까지 마치고 멈춘다">중지</button>
    </form>
</div>
<h2>예상 비용</h2>
<div class="job-costs">
    <div class="card job-cost" th:each="c : ${costs}">
        <h3 th:text="${c.job()} + ' — ' + ${c.label()}"></h3>
        <p class="job-cost-targets">대상 <b th:text="${c.targets()}"></b>건</p>
        <ul class="job-cost-endpoints">
            <li th:each="ep : ${c.endpoints()}" th:text="${ep}"></li>
        </ul>
        <p class="job-cost-requests">
            예상 요청 수
            <b th:if="${c.minRequests() == c.maxRequests()}" th:text="${c.minRequests()} + '회'"></b>
            <b th:unless="${c.minRequests() == c.maxRequests()}"
               th:text="${c.minRequests()} + '~' + ${c.maxRequests()} + '회'"></b>
        </p>
        <p class="job-cost-usd">
            예상 비용
            <b th:if="${c.minCostUsd() == c.maxCostUsd()}"
               th:text="'$' + ${#numbers.formatDecimal(c.minCostUsd(), 1, 3)}"></b>
            <b th:unless="${c.minCostUsd() == c.maxCostUsd()}"
               th:text="'$' + ${#numbers.formatDecimal(c.minCostUsd(), 1, 3)} + '~$' + ${#numbers.formatDecimal(c.maxCostUsd(), 1, 3)}"></b>
        </p>
        <p class="job-cost-note" th:if="${c.note() != null}" th:text="${c.note()}"></p>
    </div>
</div>
```

`<h2>최근 실행</h2>` 바로 위에 실행 로그 삽입:

```html
<h2>실행 로그</h2>
<div hx-get="/ui/fragments/logs" hx-trigger="load, every 3s"></div>
```

- [ ] **Step 9: admin.css에 job-strip 스타일 추가**

`admin.css`의 `/* ── 잡 실행 카드 ─────────────────────── */` 블록(`.job-forms` 규칙들) 바로 위에 추가:

```css
/* ── 대시보드 잡 실행 스트립 ───────────── */
.job-strip { display: flex; gap: 0.6rem; flex-wrap: wrap; margin: 0.4rem 0 1.2rem; }
.job-strip form {
    display: flex; align-items: center; gap: 0.5rem;
    background: var(--card); border: 1px solid var(--border); border-radius: 10px;
    padding: 0.45rem 0.7rem; box-shadow: var(--shadow);
}
.job-strip .job-strip-name { font-weight: 700; font-size: 0.85rem; color: var(--accent-ink); }
.job-strip label {
    font-size: 0.75rem; color: var(--ink-secondary);
    display: flex; align-items: center; gap: 0.25rem;
}
```

- [ ] **Step 10: 통과 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*' --tests '*UiJobControllerTest*'`
Expected: PASS (기존 잡 화면 테스트 포함 전부 — /ui/jobs는 아직 살아 있다)

- [ ] **Step 11: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/UiJobController.java \
        crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java \
        crawler/src/main/resources/templates/dashboard.html \
        crawler/src/main/resources/static/css/admin.css \
        crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/UiJobControllerTest.java \
        crawler/src/test/java/com/celfit/crawler/dashboard/adapter/in/web/UiSmokeTest.java
git commit -m "feat(crawler): 대시보드에 잡 실행 스트립·예상 비용·실행 로그 통합 — 트리거 리다이렉트 /ui로"
```

---

### Task 3: "잡 실행" 페이지 제거

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java:216-220` (`jobs()` 라우트 삭제)
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java:22` (javadoc의 "/ui/jobs 표시용" → "대시보드 표시용")
- Delete: `crawler/src/main/resources/templates/jobs.html`
- Modify: `crawler/src/main/resources/templates/fragments/nav.html` (잡 실행 링크 삭제)
- Modify: `crawler/src/main/resources/static/css/admin.css` (`.job-forms` 블록 삭제)
- Test: `crawler/src/test/java/com/celfit/crawler/dashboard/adapter/in/web/UiSmokeTest.java`

**Interfaces:**
- Consumes: Task 2의 대시보드 통합(스트립·비용·로그가 이미 /ui에 있음), `UiJobController`의 POST 라우트(유지 — 삭제 금지).
- Produces: `GET /ui/jobs` → 404. nav의 "수집 운영" 그룹에는 검색 키워드·설정만 남는다.

- [ ] **Step 1: UiSmokeTest에서 잡 화면 테스트 3개를 404 가드로 교체 (실패하는 테스트)**

`잡_화면이_렌더된다`, `잡_화면에_예상_비용_카드가_렌더된다`, `잡_화면에_잡별_중지_버튼이_렌더된다` 세 테스트를 삭제하고 다음으로 대체:

```java
@Test
void 잡_실행_페이지는_제거됐다_트리거는_대시보드_스트립() throws Exception {
    mvc.perform(get("/ui/jobs")).andExpect(status().isNotFound());
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*'`
Expected: 새 테스트 FAIL (200이 옴 — 페이지가 아직 있다)

- [ ] **Step 3: 라우트·템플릿·nav·CSS 제거**

1. `UiController.java`에서 다음 메서드 삭제:

```java
@GetMapping("/ui/jobs")
public String jobs(Model model) {
    model.addAttribute("costs", jobCostEstimator.estimates());
    return "jobs";
}
```

2. `JobCostEstimator.java` javadoc의 `— /ui/jobs 표시용.`을 `— 대시보드 표시용.`으로.
3. `rm crawler/src/main/resources/templates/jobs.html`
4. `nav.html`에서 다음 줄 삭제:

```html
        <a class="item" href="/ui/jobs" th:classappend="${active == 'jobs'} ? 'active'">잡 실행</a>
```

5. `admin.css`에서 `.job-forms` 규칙 2건 삭제 (`/* ── 잡 실행 카드 ── */` 주석 포함, `.job-strip`·`.job-costs` 블록은 유지):

```css
/* ── 잡 실행 카드 ─────────────────────── */
.job-forms { display: flex; gap: 0.8rem; flex-wrap: wrap; }
.job-forms form {
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 0.9rem 1.1rem; display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap;
    box-shadow: var(--shadow);
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A crawler/src/main
git add crawler/src/test/java/com/celfit/crawler/dashboard/adapter/in/web/UiSmokeTest.java
git commit -m "feat(crawler): 잡 실행 탭 제거 — 대시보드 스트립으로 대체, POST 트리거는 유지"
```

---

### Task 4: "수집 게시물" 페이지 제거 (전용 쿼리 코드 포함)

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/adapter/in/web/UiController.java` (contents 라우트 2개 + 헬퍼 + 미사용 필드·import)
- Delete: `crawler/src/main/resources/templates/contents.html`
- Delete: `crawler/src/main/resources/templates/content-detail.html`
- Modify: `crawler/src/main/resources/templates/fragments/nav.html` (수집 게시물 링크 삭제)
- Delete: `crawler/src/main/java/com/celfit/crawler/crawling/domain/RawPostDetail.java` (LEGACY — 이 화면 전용, 다른 사용처 없음 확인됨)
- Delete: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawPostDetailRepository.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/content/application/port/out/ContentRepository.java` (`findByStatusIn` 삭제)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawCommentRepository.java` (`findTop100ByContentIdOrderByIdDesc` 삭제)
- Test: `crawler/src/test/java/com/celfit/crawler/dashboard/adapter/in/web/UiSmokeTest.java`

**Interfaces:**
- Consumes: Task 3 완료 상태의 UiController(잡 라우트 없음).
- Produces: `GET /ui/contents`·`GET /ui/contents/{id}` → 404. `raw_post_detail` **테이블은 Flyway 소유로 잔존**(엔티티·리포지토리만 삭제 — DB 무접촉). `RawProfileRepository.findTopByInfluencerIdOrderByCapturedAtDesc`는 BeautyJob이 쓰므로 **유지**.

- [ ] **Step 1: UiSmokeTest의 수집 게시물 테스트 2개를 404 가드로 교체 (실패하는 테스트)**

`수집_데이터_화면이_content_행이_있어도_렌더된다`, `콘텐츠_상세_화면이_페이지형_raw_comment_행이_있어도_렌더되고_빈_행을_나열하지_않는다` 두 테스트를 삭제하고 대체:

```java
@Test
void 수집_게시물_페이지는_제거됐다() throws Exception {
    mvc.perform(get("/ui/contents")).andExpect(status().isNotFound());
    mvc.perform(get("/ui/contents/1")).andExpect(status().isNotFound());
}
```

같이 정리: 이 두 테스트만 쓰던 `@Autowired ContentRepository contents`, `@Autowired RawCommentRepository rawComments` 필드와 import(`ContentRepository`, `Content`, `ContentOrigin`, `ContentType`, `RawCommentRepository`, `RawComment`)를 삭제한다. **`RawSource`·`Map` import와 `crawlRuns`·`influencers`·`discoveries`·`rawDiscovery` 필드는 발굴 건수 등 다른 테스트가 쓰므로 유지.**

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*'`
Expected: 새 테스트 FAIL (200이 옴)

- [ ] **Step 3: UiController에서 contents 경로 전체 제거**

`UiController.java`에서 삭제:
- `@GetMapping("/ui/contents")` `contents(...)` 메서드 전체
- `@GetMapping("/ui/contents/{id}")` `contentDetail(...)` 메서드 전체
- `CommentRow` record, `commentRows(...)`, `taggedUsers(...)`, `pretty(...)` 헬퍼
- 이제 미사용이 된 필드·생성자 파라미터·대입: `contents`(ContentRepository), `rawDetails`(RawPostDetailRepository), `rawComments`(RawCommentRepository), `rawProfiles`(RawProfileRepository), `objectMapper`(ObjectMapper)
- 이제 미사용이 된 import: `ContentRepository`, `Content`, `ContentStatus`, `RawComment`, `tools.jackson.databind.ObjectMapper`, `HttpStatus`, `PathVariable`, `ResponseStatusException`

- [ ] **Step 4: 템플릿·nav·엔티티·리포지토리 제거**

```bash
rm crawler/src/main/resources/templates/contents.html \
   crawler/src/main/resources/templates/content-detail.html \
   crawler/src/main/java/com/celfit/crawler/crawling/domain/RawPostDetail.java \
   crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/RawPostDetailRepository.java
```

`nav.html`에서 삭제:

```html
        <a class="item" href="/ui/contents" th:classappend="${active == 'contents'} ? 'active'">수집 게시물</a>
```

`ContentRepository.java`에서 삭제 (다른 `findByStatus*`는 CollectJob 등이 사용 — 유지):

```java
    Page<Content> findByStatusIn(java.util.Collection<ContentStatus> statuses, Pageable pageable);
```

`RawCommentRepository.java`에서 삭제 (본문이 비면 인터페이스는 JpaRepository 상속만 남긴다 — RawComment 저장은 CollectJob 경로가 사용하므로 리포지토리 자체는 유지):

```java
    List<RawComment> findTop100ByContentIdOrderByIdDesc(Long contentId);
```

(남은 import 중 미사용이 된 `java.util.List`도 함께 삭제)

- [ ] **Step 5: 전체 컴파일·스모크 통과 확인**

Run: `./gradlew :crawler:test --tests '*UiSmokeTest*'`
Expected: PASS (컴파일 에러 없이 — 미사용 필드·import 잔재가 있으면 여기서 드러난다)

- [ ] **Step 6: crawler 전체 테스트**

Run: `./gradlew :crawler:test`
Expected: BUILD SUCCESSFUL — RawPostDetail 삭제 여파(SchemaTest·SanityTest 등)가 없는지 전체로 확인

- [ ] **Step 7: Commit**

```bash
git add -A crawler/src
git commit -m "feat(crawler): 수집 게시물 탭 제거 — 화면·라우트·전용 쿼리·LEGACY RawPostDetail 엔티티 삭제(테이블은 잔존)"
```

---

### Task 5: 문서 갱신 (ARCHITECTURE.md · deploy/README.md)

**Files:**
- Modify: `ARCHITECTURE.md` (§3 raw 테이블 표 · §5 운영 중 문단 · §7 결정 기록 · §8 미결 표의 미러 주기 행)
- Modify: `deploy/README.md` (§4-1 근처에 crawler 스케줄 항목)

**Interfaces:**
- Consumes: Task 1~4의 최종 상태.
- Produces: 살아있는 문서 정합 — 코드 없음.

- [ ] **Step 1: ARCHITECTURE.md 갱신**

1. §3 raw 테이블 표의 `raw_post_detail` 행:

```markdown
| `raw_post_detail` | 구 시대 상세 payload — 신 파이프라인 미사용(LEGACY, 크롤러 대시보드 전용) |
```

를 다음으로:

```markdown
| `raw_post_detail` | 구 시대 상세 payload — 신 파이프라인 미사용(LEGACY). 07-22 열람 화면 제거로 접근 코드도 삭제, 테이블만 잔존 |
```

2. §5 "**운영 중**" 문단의 `crawler 파이프라인(discover→qualify→aggregate)`를 다음으로 교체:

```markdown
crawler 파이프라인(discover→qualify→beauty→collect·reels — 07-22부터 qualify·beauty·collect·reels는
새벽 윈도우 반복 크론 자동 실행, discover·similar만 어드민 수동 트리거. 어드민은 대시보드 단일
화면으로 개편: 잡 실행 스트립·예상 비용·실행 로그 통합, 잡 실행·수집 게시물 탭 제거)
```

3. §7 결정 기록 맨 위에 행 추가:

```markdown
| 2026-07-22 | **크롤링 데일리 자동화 + 크롤러 어드민 대시보드 개편** — 스케줄은 코드 무변경(기존 ScheduleRunner를 compose env로 점화). 단발 크론 대신 **윈도우 반복 발사**(collect 01:00~03:30/30분 · reels 01:10~03:55/15분 · qualify 02:00~03:30/30분 · beauty 03:00·03:30, KST): 잡이 "남은 대상만" 선정하므로 반복이 안전하고 일시 실패·컨테이너 재기동을 윈도우 안에서 흡수, analytics 미러 04:30 전 완결로 같은 날 반영(썸네일 CDN 만료 전 처리). 발굴(discover·similar)은 수동 유지, 실패 알림은 후속(컨테이너 다운은 기존 OCI 알람 담당). 어드민은 잡 실행 폼·예상 비용·실행 로그를 대시보드로 통합하고 잡 실행·수집 게시물 탭을 코드까지 제거(LEGACY RawPostDetail 엔티티·리포지토리 삭제, 테이블 잔존) | [specs/2026-07-22-crawler-schedule-and-admin-dashboard-design.md](../../specs/2026-07-22-crawler-schedule-and-admin-dashboard-design.md) |
```

4. §8 미결 표에서 `| 미러 갱신 주기 | ...` 행의 설명을 다음으로 교체:

```markdown
| 미러 갱신 주기 | 해소(07-21 analytics 스케줄 점화 04:30 + 07-22 크롤 자동화가 그 앞 새벽으로 정렬) — 잔여 이슈 없음, 행 정리 예정 |
```

- [ ] **Step 2: deploy/README.md에 crawler 스케줄 항목 추가**

`## 4-1. analytics 상주 (서버, 07-19~)` 섹션 바로 아래에 신설:

```markdown
## 4-2. crawler 스케줄 (서버, 07-22~)
- 데일리 자동(compose env, KST — 윈도우 반복: 잡이 남은 대상만 집어 실패·재기동을 흡수):
  collect 01:00~03:30/30분 → reels 01:10~03:55/15분 → qualify 02:00~03:30/30분 → beauty 03:00·03:30
  — analytics 미러 04:30 전 완결. 발굴(discover·similar)은 수동.
- 어드민 UI: `ssh -L 8080:localhost:8080 <host>` 후 http://localhost:8080/ui — 대시보드에서
  잡 수동 트리거(실행 스트립)·예상 비용·실행 로그·최근 실행 확인
- 롤백: compose의 `CRAWLER_SCHEDULE_ENABLED: "false"` 후 `docker compose up -d crawler`
```

- [ ] **Step 3: Commit**

```bash
git add ARCHITECTURE.md deploy/README.md
git commit -m "docs: 크롤링 자동화·어드민 개편 반영 — ARCHITECTURE §3·§5·§7·§8, deploy README §4-2"
```

---

### Task 6: 실앱 검증 + 마무리

**Files:** 없음 (검증만 — 이슈 발견 시 해당 태스크 파일로 돌아가 수정)

**Interfaces:**
- Consumes: Task 1~5 전부.
- Produces: 검증 근거(스크린샷·테스트 출력) — PR 본문 재료.

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew :crawler:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 레포 `verify` 스킬로 실제 앱 확인**

`verify` 스킬(레포 관리 UI 검증 레시피)을 invoke해 crawler 어드민(8080 `/ui`)을 실제 기동:
- 대시보드에 flash·파이프라인 다이어그램·잡 실행 스트립·예상 비용·상태 타일·실행 로그·최근 실행이 순서대로 렌더되는지
- 스트립에서 잡 하나(권장: similar 같은 저비용 잡은 실계정 호출이 있으니 **중지 버튼**으로 확인 — "실행 중이 아닙니다" flash가 대시보드에 뜨는지)
- nav에 잡 실행·수집 게시물 링크가 없는지, `/ui/jobs`·`/ui/contents` 직접 접근이 404인지
- 라이트/다크 모드에서 스트립 스타일 확인

- [ ] **Step 3: 브랜치 마무리**

superpowers:finishing-a-development-branch 스킬로 진행 — develop 대상 PR 생성(제목 예:
`feat(crawler): 크롤링 데일리 자동화 + 어드민 대시보드 개편`). 배포는 develop→main 머지(CD)로만.

- [ ] **Step 4: 배포 후 운영 확인 (머지·CD 후, 사용자와 함께)**

- 다음 날 아침 crawler 어드민 "최근 실행"에 SCHEDULED 트리거의 collect·reels·qualify·beauty 행 확인
- analytics 어드민(8082)에서 미러가 신규 수집분을 같은 날 반영했는지 확인
