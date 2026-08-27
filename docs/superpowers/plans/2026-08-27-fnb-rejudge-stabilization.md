# F&B 재판정 루프 안정화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** F&B 판정이 재판정 루프에서 반복해서 덮이는 것을 막는다 — 캡션 기반 판정은 정착(수동 교정만), 비뷰티 재판정은 계정당 월 1회로 제한.

**Architecture:** 두 지점만 고친다. (1) `BeautyJob.applyVerdicts`의 F&B 축 적용 가드 — 기존 판정이 캡션 기반이면 재적용 금지, 캡션 0건 판정만 캡션이 생겼을 때 1회 업그레이드. (2) `InfluencerRepository.findRejudgeTargets`에 쿨다운 컷오프 파라미터 추가 — 판정 후 N일(기본 30, `beauty.rejudge-cooldown-days`) 이내는 재선정 제외. 스키마 변경·마이그레이션 없음.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA(JPQL), Mockito 단위 테스트 + Testcontainers 통합 테스트.

**스펙:** [docs/superpowers/specs/2026-08-27-fnb-rejudge-stabilization-design.md](../specs/2026-08-27-fnb-rejudge-stabilization-design.md)

## Global Constraints

- 브랜치: `feature/fnb-user-decline-fc1753`(현 worktree). PR 대상은 `develop`.
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(crawler):`/`test(crawler):`/`docs:`.
- 테스트는 모듈 단위: `./gradlew :crawler:test`. 이 머신은 Docker Desktop이므로 `DOCKER_HOST`를 설정하지 않는다(설정하면 오히려 깨짐 — colima 아님).
- 설정 기본값은 yml `@ConfigurationProperties` + `app_setting` 오버라이드 패턴(기존 `beauty.batch-limit`과 동일). Flyway 마이그레이션 금지.
- `SettingsService.DESCRIPTIONS`는 현재 `Map.of` 10쌍 — **10쌍이 상한이라 키를 추가하려면 `Map.ofEntries(Map.entry(...), ...)`로 바꿔야 한다**(안 바꾸면 컴파일 에러).
- F&B MANUAL 가드(`fnb_source=MANUAL`이면 미적용)는 그대로 보존한다.

---

### Task 1: 비뷰티 재판정 쿨다운

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/common/config/BeautyProperties.java`
- Modify: `crawler/src/main/resources/application.yml` (crawler.beauty 블록, 55~57행 부근)
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/port/out/InfluencerRepository.java:90-96` (`findRejudgeTargets`)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java:109-115` (호출부)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautySelectionIntegrationTest.java`
- Test(기계적 갱신): `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java` (findRejudgeTargets 스텁 7곳: 271·667·697·704·710·725·741행)

**Interfaces:**
- Produces: `InfluencerRepository.findRejudgeTargets(InfluencerStatus status, String beautySource, Instant cooldownBefore, Pageable pageable)` — cooldownBefore **이전**에 판정된(또는 판정 시각 미기록) 비뷰티만 반환.
- Produces: `SettingsService.beautyRejudgeCooldownDays()` → int (기본 30, app_setting `beauty.rejudge-cooldown-days` 오버라이드).
- Task 2가 이 시그니처로 스텁을 작성한다.

- [ ] **Step 1: 통합 테스트 작성 (실패 확인용)**

`BeautySelectionIntegrationTest`에 추가 — 기존 헬퍼 `notBeauty(name, judgedAt)`·`profile(...)`·`runId()` 재사용:

```java
@Test
void 재판정_선정은_쿨다운_이내_판정을_제외한다() {
    Long run = runId();
    // 컷오프: 이 시각 이전 판정만 재판정 대상
    Instant cooldownBefore = JUDGED.plusSeconds(60);

    // 대상: 판정이 컷오프보다 오래됐고 판정 후 새 스냅샷이 있다
    Influencer old = notBeauty("cool-old", JUDGED);
    profile(old, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

    // 제외: 새 스냅샷은 있지만 판정이 컷오프 이후(쿨다운 이내)
    Influencer recent = notBeauty("cool-recent", JUDGED.plusSeconds(120));
    profile(recent, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

    List<Influencer> out = influencers.findRejudgeTargets(
            InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
            cooldownBefore, PageRequest.of(0, 100));

    assertThat(out.stream().map(Influencer::getUsername)
            .filter(u -> u.startsWith(PREFIX)))
            .containsExactly(PREFIX + "cool-old");
}
```

기존 두 테스트(130행 `재판정_선정은_판정_후_재료가_갱신된_비뷰티만_고른다`, 150행 `재판정_선정은_판정이_오래된_계정부터_시각_미기록이_가장_먼저다`)의 `findRejudgeTargets` 호출에 셋째 인자 `Instant.now()`를 추가한다(JUDGED가 2026-07-15라 항상 컷오프 이전 — 기존 의미 불변).

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :crawler:compileTestJava`
Expected: FAIL — `findRejudgeTargets` 3인자 시그니처 없음.

- [ ] **Step 3: 구현**

`BeautyProperties.java` — 컴포넌트 추가:

```java
@ConfigurationProperties("crawler.beauty")
public record BeautyProperties(int batchLimit, int rejudgeCooldownDays) {}
```

`application.yml`의 `crawler.beauty:` 블록에 추가(batch-limit 다음 줄):

```yaml
    rejudge-cooldown-days: 30  # 비뷰티 재판정 쿨다운(일) — 수집으로 프로필이 매 주기 갱신되는 F&B 계정이 매일 재선정되는 것을 차단(스펙 2026-08-27)
```

`SettingsService.java` — 4곳:

```java
// 키 상수(BEAUTY_BATCH_LIMIT 아래)
static final String BEAUTY_REJUDGE_COOLDOWN_DAYS = "beauty.rejudge-cooldown-days";
```

`KEYS` 리스트에 `BEAUTY_BATCH_LIMIT` 다음으로 `BEAUTY_REJUDGE_COOLDOWN_DAYS` 추가.

`DESCRIPTIONS`는 `Map.of` 10쌍 상한에 걸리므로 `Map.ofEntries`로 전환(기존 10개 항목을 `Map.entry(k, v)`로 감싸고) 추가:

```java
Map.entry(BEAUTY_REJUDGE_COOLDOWN_DAYS,
        "beauty: 비뷰티 재판정 쿨다운(일) — 판정 후 이 기간 안엔 프로필이 갱신돼도 재선정 안 함"),
```

접근자(`beautyBatchLimit()` 아래)와 `defaultValue` switch 케이스:

```java
@Transactional(readOnly = true)
public int beautyRejudgeCooldownDays() {
    return effective(BEAUTY_REJUDGE_COOLDOWN_DAYS);
}
```

```java
case BEAUTY_REJUDGE_COOLDOWN_DAYS -> beautyProps.rejudgeCooldownDays();
```

`InfluencerRepository.findRejudgeTargets` — 쿨다운 절 추가(주석도 갱신):

```java
/**
 * BEAUTY 재판정(rejudge) 대상: CLAUDE가 비뷰티로 판정했지만 판정 후 프로필 재료가 갱신된
 * (새 raw_profile 스냅샷이 생긴) 계정만 — 재료가 그대로면 같은 판정만 반복하므로 배치 낭비다.
 * cooldownBefore 이후(쿨다운 이내) 판정분은 제외한다 — F&B 파이프라인 편입 계정은 수집
 * 재방문마다 프로필이 갱신돼, 이 절이 없으면 매일 전원이 재선정된다(스펙 2026-08-27).
 * MANUAL은 선정 자체에서 제외되고, 뷰티 판정분은 재검하지 않는다(캡션이 뷰티→비뷰티로
 * 뒤집는 사례는 관측되지 않음 — 2026-07-16 실험). 오래된 판정 우선(시각 미기록 = 가장 오래됨).
 */
@Query("select i from Influencer i where i.status = :status and i.beautySource = :beautySource "
        + "and i.beauty = false "
        + "and (i.beautyJudgedAt is null or i.beautyJudgedAt < :cooldownBefore) "
        + "and (i.beautyJudgedAt is null or i.beautyJudgedAt < "
        + "(select max(rp.capturedAt) from RawProfile rp where rp.influencerId = i.id)) "
        + "order by i.beautyJudgedAt asc nulls first, i.id")
List<Influencer> findRejudgeTargets(@Param("status") InfluencerStatus status,
                                    @Param("beautySource") String beautySource,
                                    @Param("cooldownBefore") java.time.Instant cooldownBefore,
                                    Pageable pageable);
```

`BeautyJob.java` 호출부(109~115행) — 컷오프 계산 추가:

```java
if (rejudge && targets.size() < limit) {
    // 재료(raw_profile)가 판정 후 갱신된 비뷰티만 — 재료가 그대로면 같은 판정만 반복한다.
    // 쿨다운(beauty.rejudge-cooldown-days) 이내 판정분은 제외 — F&B 수집 계정의 매일 재선정 차단.
    // 오래된 판정 우선(쿼리 정렬) — 실패 배치가 옛 판정 시각으로 남아 먼저 재시도된다
    java.time.Instant cooldownBefore = clock.instant()
            .minus(java.time.Duration.ofDays(settings.beautyRejudgeCooldownDays()));
    targets.addAll(influencers.findRejudgeTargets(
            InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
            cooldownBefore, PageRequest.of(0, limit - targets.size())));
}
```

`BeautyJobTest` 기계적 갱신:
- `wireSavePassthrough()`에 `when(settings.beautyRejudgeCooldownDays()).thenReturn(30);` 추가.
- `findRejudgeTargets` 스텁·verify 7곳(271·667·697·704·710·725·741행)에 `Pageable` 인자 앞에 `any(Instant.class)` 추가. 단 704행 테스트(`rejudge는_재료_갱신된_비뷰티_선정_쿼리를_CLAUDE_판정분으로만_호출한다`)는 verify에서 컷오프 값을 정확히 검증한다:

```java
verify(influencers).findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
        eq(Influencer.BEAUTY_SOURCE_CLAUDE),
        eq(NOW.minus(java.time.Duration.ofDays(30))), any(Pageable.class));
```

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautySelectionIntegrationTest" --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: PASS (신규 쿨다운 테스트 포함 전부).

- [ ] **Step 5: Commit**

```bash
git add -A crawler/src docs
git commit -m "feat(crawler): 비뷰티 재판정에 쿨다운 도입 — beauty.rejudge-cooldown-days(기본 30일)

F&B 파이프라인 편입 계정은 수집 재방문마다 프로필이 갱신돼 매일 전원이
재판정 선정되던 것을 계정당 월 1회로 제한(스펙 2026-08-27)."
```

---

### Task 2: F&B 축 정착 가드

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/BeautyJob.java` (`applyVerdicts`의 `applyFnb` 계산, 277~283행 부근)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/BeautyJobTest.java`

**Interfaces:**
- Consumes: Task 1의 4인자 `findRejudgeTargets` 시그니처(스텁에 `any(Instant.class)` 필요).
- Produces: 없음(내부 가드) — 동작 계약은 "F&B 축은 첫 판정(fnb_class NULL) 또는 캡션 업그레이드(기존 fnb_caption_count=0 ∧ 이번 캡션>0)일 때만 적용".

- [ ] **Step 1: 단위 테스트 3개 작성 (실패 확인용)**

`BeautyJobTest`에 추가 — 기존 헬퍼 `qualified(id, username)`·`legacyProfile(...)` 재사용. 공통 스텁은 258행 `rejudge_경로에서도_수동_FnB_판정은_덮이지_않는다`와 동일 패턴(신규·백필 선정은 빈 리스트, rejudge 선정만 대상 반환):

```java
@Test
void 캡션_기반_FnB_판정은_재판정에서_덮이지_않는다() {
    // 정착 규칙(스펙 2026-08-27 §1) — 캡션 실측으로 판정된 F&B는 자동 재적용 금지, 이후는 수동만.
    Influencer inf = qualified(1L, "settled");
    inf.setBeauty(false);
    inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
    inf.classifyFnb(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "취미 계정", "CAPTION");
    Instant prevJudgedAt = Instant.parse("2026-08-25T00:00:00Z");
    inf.setFnbJudgedAt(prevJudgedAt);
    inf.setFnbCaptionCount((short) 5);
    when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
            eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(inf));
    when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
            .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
    when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
            .thenReturn(Optional.empty());
    when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("settled",
            BeautyClass.NOT_BEAUTY, "여전히 비뷰티", "BIO",
            CategoryClass.INFLUENCER, "모델 노이즈로 뒤집힘", "BIO")));

    BeautyJob.Summary s = job.run(TriggerType.MANUAL, true);

    // 뷰티 축은 재판정 결과가 적용된다(rejudge의 목적)
    assertThat(inf.getBeautyJudgedAt()).isEqualTo(NOW);
    // F&B 축은 정착 — class·judgedAt·캡션 수 어느 것도 안 바뀐다
    assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.NONE);
    assertThat(inf.getFnbJudgedAt()).isEqualTo(prevJudgedAt);
    assertThat(inf.getFnbCaptionCount()).isEqualTo((short) 5);
    assertThat(s.fnbApplied()).isZero();
}

@Test
void 캡션0_FnB_판정은_캡션이_생기면_업그레이드_재판정된다() {
    // 캡션 없이(프로필 텍스트만) 판정된 것은 캡션이 쌓였을 때 1회 실측 재판정한다.
    Influencer inf = qualified(2L, "upgrade");
    inf.setBeauty(false);
    inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
    inf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "얇은 근거", "BIO");
    inf.setFnbJudgedAt(Instant.parse("2026-08-25T00:00:00Z"));
    inf.setFnbCaptionCount((short) 0);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fullName", "이름");
    payload.put("biography", "bio");
    payload.put("latestPosts", List.of(Map.of("caption", "클라이밍"), Map.of("caption", "등산")));
    when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
            eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(inf));
    when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L)).thenReturn(Optional.of(
            new RawProfile(2L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
    when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("upgrade",
            BeautyClass.NOT_BEAUTY, "비뷰티", "CAPTION",
            CategoryClass.NONE, "캡션이 취미 위주", "CAPTION")));

    BeautyJob.Summary s = job.run(TriggerType.MANUAL, true);

    assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.NONE);
    assertThat(inf.getFnbJudgedAt()).isEqualTo(NOW);
    assertThat(inf.getFnbCaptionCount()).isEqualTo((short) 2);
    assertThat(s.fnbApplied()).isEqualTo(1);
}

@Test
void 캡션0_FnB_판정은_이번에도_캡션이_없으면_재적용하지_않는다() {
    // 같은 품질(캡션 0건)의 판정으로 덮는 것은 노이즈 반복일 뿐이다 — 업그레이드만 허용.
    Influencer inf = qualified(3L, "still-zero");
    inf.setBeauty(false);
    inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
    inf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "얇은 근거", "BIO");
    Instant prevJudgedAt = Instant.parse("2026-08-25T00:00:00Z");
    inf.setFnbJudgedAt(prevJudgedAt);
    inf.setFnbCaptionCount((short) 0);
    when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
            .thenReturn(List.of());
    when(influencers.findRejudgeTargets(eq(InfluencerStatus.QUALIFIED),
            eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(inf));
    when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(3L))
            .thenReturn(Optional.of(legacyProfile(3L, "이름", "bio")));  // 캡션 없음
    when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
            .thenReturn(Optional.empty());
    when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("still-zero",
            BeautyClass.NOT_BEAUTY, "비뷰티", "BIO",
            CategoryClass.NONE, "근거 없음", "BIO")));

    BeautyJob.Summary s = job.run(TriggerType.MANUAL, true);

    assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
    assertThat(inf.getFnbJudgedAt()).isEqualTo(prevJudgedAt);
    assertThat(s.fnbApplied()).isZero();
}
```

첫 판정(fnb_class NULL)이 캡션 0건이어도 적용되는 것은 기존 테스트가 이미 회귀 방어한다
(`신규_판정은_두_축을_모두_적용한다`, `백필_대상은_F앤B_축만_적용하고_뷰티_판정을_보존한다` 등 — 가드를 잘못 조이면 이들이 깨진다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: 신규 3개 중 `캡션_기반_...`·`캡션0_..._재적용하지_않는다` FAIL(현재는 F&B가 덮임), `업그레이드` PASS(현재도 적용됨 — 회귀 방어용).

- [ ] **Step 3: 가드 구현**

`BeautyJob.applyVerdicts`의 `applyFnb` 계산(277~280행)을 교체:

```java
// F&B 축의 MANUAL(수동 교정)은 적용 시점에 막는다 — fnbOnly 마스크는 뷰티 축만 보호하므로,
// rejudge·신규 경로로 같은 계정이 다시 잡히면 수동 F&B 판정이 CLAUDE로 조용히 덮인다.
// 정착 규칙(스펙 2026-08-27 §1): 캡션 기반 판정은 자동 재적용 금지(이후는 수동만),
// 캡션 0건 판정은 캡션이 생겼을 때만 1회 업그레이드 — count 미기록(NULL)은 정착으로 취급.
Short prevFnbCap = inf.getFnbCaptionCount();
boolean fnbFirstJudgment = inf.getFnbClass() == null;
boolean fnbCaptionUpgrade = prevFnbCap != null && prevFnbCap == 0 && capCount > 0;
boolean applyFnb = v.fnbClass() != null
        && !Influencer.BEAUTY_SOURCE_MANUAL.equals(inf.getFnbSource())
        && (fnbFirstJudgment || fnbCaptionUpgrade);
```

(주의: `capCount` 선언(273행 부근)이 이 블록보다 앞이어야 한다 — 현재도 앞에 있음.)

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :crawler:test --tests "com.celfit.crawler.crawling.application.service.BeautyJobTest"`
Expected: PASS 전부(기존 테스트 포함 — 특히 백필·신규·MANUAL 계열 회귀 없음).

- [ ] **Step 5: Commit**

```bash
git add crawler/src
git commit -m "feat(crawler): F&B 판정 정착 규칙 — 캡션 기반 판정은 자동 재적용 금지, 캡션0→캡션N 업그레이드만 허용

재판정 루프가 정착된 F&B 판정을 모델 노이즈로 반복해서 덮던 것을 차단(08-26 순감 883 실측, 스펙 2026-08-27)."
```

---

### Task 3: 모듈 검증·문서 갱신·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Move: `docs/superpowers/plans/2026-08-27-fnb-rejudge-stabilization.md` → `docs/superpowers/plans/archive/`
- (스펙은 `docs/superpowers/specs/`에 유지 — 트랙 완결 시점에만 archive 이동)

**Interfaces:** 없음.

- [ ] **Step 1: crawler 모듈 전체 테스트**

Run: `./gradlew :crawler:test`
Expected: BUILD SUCCESSFUL. (Docker Desktop 기동 상태 확인 — Testcontainers 필요. `DOCKER_HOST`는 설정하지 않는다.)

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 추가**

```markdown
## 2026-08-27 — F&B 판정 정착 규칙 + 비뷰티 재판정 쿨다운

F&B 파이프라인 편입(08-25)으로 F&B 계정이 "비뷰티인데 프로필이 매 재방문 갱신되는" 계정이
되면서 재판정 루프에 매일 전원 재진입, 정착된 판정이 모델 노이즈로 반복해서 덮였다
(08-26 재판정 5,024명 중 INFLUENCER 순감 883 실측). 두 가지로 안정화:
캡션 기반 F&B 판정은 자동 재적용 금지(이후 수동 교정만, positive/negative 대칭 —
비대칭이면 노이즈 편입이 고착되는 상향 래칫), 캡션 0건 판정만 캡션이 생겼을 때 1회
업그레이드. 비뷰티 재판정 선정에 쿨다운 `beauty.rejudge-cooldown-days`(기본 30일) 추가 —
비뷰티→뷰티 뒤집힘 기회는 월 1회 유지. beauty 크론 주기는 유지(신규 판정은 수집 게이트,
할 일 없으면 대상 0명). 스펙: docs/superpowers/specs/2026-08-27-fnb-rejudge-stabilization-design.md
```

(기존 맨 위 항목 형식과 다르면 그 형식을 따른다 — 날짜 헤더·본문 단락 구성만 유지.)

- [ ] **Step 3: plan 문서 아카이브 이동 + 스펙 상태 갱신**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-08-27-fnb-rejudge-stabilization.md docs/superpowers/plans/archive/
```

스펙 문서 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨`으로 갱신.

- [ ] **Step 4: Commit + PR**

```bash
git add -A docs DECISIONS.md
git commit -m "docs: F&B 재판정 안정화 결정 기록 + plan 아카이브"
git push -u origin feature/fnb-user-decline-fc1753
gh pr create --base develop --title "feat(crawler): F&B 재판정 루프 안정화 — 정착 규칙 + 쿨다운" --body "$(cat <<'EOF'
## 요약
- F&B 축 정착 규칙: 캡션 기반 판정은 자동 재적용 금지(수동 교정만), 캡션 0건 판정만 캡션이 생겼을 때 1회 업그레이드 재판정
- 비뷰티 재판정 쿨다운 `beauty.rejudge-cooldown-days`(기본 30일) — F&B 수집 계정이 매일 전원 재선정되던 것을 계정당 월 1회로 제한
- 배경: 08-26 재판정 런(5,024명)에서 F&B INFLUENCER 순감 883(대시보드 ③-2 7,390→6,507) — 교정 자체는 정당했으나 정착 판정까지 반복해서 덮이는 구조를 차단

## 구현
- `BeautyJob.applyVerdicts` F&B 적용 가드: 첫 판정(fnb_class NULL) 또는 업그레이드(기존 caption 0 ∧ 이번 캡션>0)만 적용, MANUAL 가드 유지
- `findRejudgeTargets`에 cooldownBefore 파라미터, 기본값은 yml(`BeautyProperties.rejudgeCooldownDays`) + app_setting 오버라이드
- 스키마 변경·마이그레이션 없음
- 설계: docs/superpowers/specs/2026-08-27-fnb-rejudge-stabilization-design.md

## 검증
- 단위: BeautyJobTest 신규 3건(정착·업그레이드·0건 반복 차단) + 기존 회귀(백필·신규·MANUAL)
- 통합: BeautySelectionIntegrationTest 쿨다운 선정 1건
- `./gradlew :crawler:test` 전체 통과

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
