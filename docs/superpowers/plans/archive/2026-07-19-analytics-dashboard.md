# analytics 어드민 대시보드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민(8082 /ui)을 파이프라인 관측 대시보드로 재구축 — 퍼널(단계별 몇/몇), 잡 카드(진행률·최근/다음 실행), 실행 피드, 비용 카드 제거.

**Architecture:** 잡 → `ProgressReporter`(analyze 패키지 인터페이스) → `JobProgressRegistry`(admin) 진행률 보고. `AnalyticsJobService`가 `RunHistory`(링 버퍼)에 실행 이벤트 기록. `PipelineStatsService`가 퍼널 숫자(빠른 동기 + 무거운 비동기 캐시). 크론 파싱은 `ScheduleInfo`. 템플릿·CSS 전면 재작성(크롤러 디자인 토큰), htmx 5초 폴링 유지.

**Tech Stack:** Spring Boot 4.1 (Java 21), Thymeleaf, htmx, JdbcTemplate. 스펙: `docs/superpowers/specs/2026-07-19-analytics-dashboard-design.md`

**워크트리:** `.worktrees/deploy-analytics`, 브랜치 `feat/analytics-dashboard` (origin/develop 기반)

---

### Task 1: ProgressReporter + JobProgressRegistry

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/ProgressReporter.java`
- Create: `analytics/src/main/java/com/celfit/analytics/admin/JobProgressRegistry.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/JobProgressRegistryTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobProgressRegistryTest {

	@Test
	void 시작_보고_종료_스냅샷() {
		JobProgressRegistry registry = new JobProgressRegistry();
		registry.start(JobName.ANALYZE);
		registry.reporter(JobName.ANALYZE).report(3, 1, 450);

		JobProgressRegistry.Progress p = registry.snapshot(JobName.ANALYZE);
		assertThat(p.running()).isTrue();
		assertThat(p.processed()).isEqualTo(3);
		assertThat(p.failed()).isEqualTo(1);
		assertThat(p.total()).isEqualTo(450);
		assertThat(p.startedAt()).isNotNull();

		registry.finish(JobName.ANALYZE);
		assertThat(registry.snapshot(JobName.ANALYZE).running()).isFalse();
		// 종료 후에도 마지막 진행값은 보존 (피드 기록 전 참조용)
		assertThat(registry.snapshot(JobName.ANALYZE).processed()).isEqualTo(3);
	}

	@Test
	void 미시작_잡은_빈_스냅샷() {
		JobProgressRegistry registry = new JobProgressRegistry();
		JobProgressRegistry.Progress p = registry.snapshot(JobName.MIRROR);
		assertThat(p.running()).isFalse();
		assertThat(p.total()).isZero();
	}
}
```

- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :analytics:test --tests '*JobProgressRegistryTest*'` → 컴파일 실패(클래스 없음)

- [ ] **Step 3: 구현**

```java
// analytics/src/main/java/com/celfit/analytics/analyze/ProgressReporter.java
package com.celfit.analytics.analyze;

/**
 * 잡 진행률 보고 경계 — 잡이 admin 층에 의존하지 않게 하는 함수형 인터페이스.
 * one-shot CLI처럼 어드민이 없는 컨텍스트는 NOOP 주입.
 */
@FunctionalInterface
public interface ProgressReporter {

	ProgressReporter NOOP = (processed, failed, total) -> { };

	void report(int processed, int failed, int total);
}
```

```java
// analytics/src/main/java/com/celfit/analytics/admin/JobProgressRegistry.java
package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.ProgressReporter;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * 잡별 진행률 스냅샷 — 잡 스레드가 쓰고 UI 스레드가 읽는다.
 * 값 정합보다 가시성이 목적이라 잡별 단일 뮤터블 홀더 + synchronized 로 충분.
 */
public class JobProgressRegistry {

	public record Progress(boolean running, int processed, int failed, int total, Instant startedAt) {
		static final Progress EMPTY = new Progress(false, 0, 0, 0, null);
	}

	private final Map<JobName, Progress> byJob = new EnumMap<>(JobName.class);

	public synchronized void start(JobName job) {
		byJob.put(job, new Progress(true, 0, 0, 0, Instant.now()));
	}

	/** 잡 종료 — 마지막 진행값은 보존하고 running만 내린다 (피드 기록·최근값 참조용). */
	public synchronized void finish(JobName job) {
		Progress p = byJob.getOrDefault(job, Progress.EMPTY);
		byJob.put(job, new Progress(false, p.processed(), p.failed(), p.total(), p.startedAt()));
	}

	public ProgressReporter reporter(JobName job) {
		return (processed, failed, total) -> {
			synchronized (this) {
				Progress p = byJob.getOrDefault(job, Progress.EMPTY);
				byJob.put(job, new Progress(p.running(), processed, failed, total, p.startedAt()));
			}
		};
	}

	public synchronized Progress snapshot(JobName job) {
		return byJob.getOrDefault(job, Progress.EMPTY);
	}
}
```

- [ ] **Step 4: 테스트 통과 확인** — 같은 명령, PASS
- [ ] **Step 5: 커밋** — `git add ... && git commit -m "feat(analytics): 잡 진행률 보고 경계 — ProgressReporter + JobProgressRegistry"`

---

### Task 2: 잡 반환형 JobResult + 잡 3종 보고 배선

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/JobResult.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java` (생성자 +reporter, run() 반환형·루프)
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java` (동일)
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (잡 빈 생성부에 reporter 주입 — ObjectProvider<JobProgressRegistry>로 조회, 없으면 NOOP)
- Test: 기존 `ContentAnalysisJobTest`·`AccountAnalysisJobTest` 갱신 (reporter 인자 추가 + 보고 검증 1개)

- [ ] **Step 1: JobResult record**

```java
// analytics/src/main/java/com/celfit/analytics/analyze/JobResult.java
package com.celfit.analytics.analyze;

/** 잡 실행 결과 — 실행 피드(outcome 판정)용. carriedOver=일 한도 소진으로 잔여 이월. */
public record JobResult(int processed, int failed, boolean carriedOver) {
}
```

- [ ] **Step 2: ContentAnalysisJob 배선** — 생성자에 `ProgressReporter reporter` 추가(마지막 인자), 필드 보관. `run()` 반환형 `int` → `JobResult`. 루프 직전 `reporter.report(0, 0, targets.size())`, 루프 내 processed/failed 갱신 직후 `reporter.report(processed, failed, targets.size())`, 쿼터 브레이크 시 `carriedOver=true`. 반환 `new JobResult(processed, failed, carriedOver)`. 기존 `return processed` 호출부(AnalyzeRunner 등 one-shot 러너)는 `.processed()`로 갱신.
- [ ] **Step 3: AccountAnalysisJob 동일 배선** (같은 패턴 — total=targets.size()).
- [ ] **Step 4: JobConfig 주입** — 잡 빈 생성 시 `ObjectProvider<JobProgressRegistry> registry` 파라미터 추가:

```java
ProgressReporter analyzeReporter = registry.getIfAvailable() != null
		? registry.getIfAvailable().reporter(JobName.ANALYZE) : ProgressReporter.NOOP;
```

주의: JobConfig(config 패키지)가 admin의 JobName을 참조 — analytics는 평탄 패키지 컨벤션이라 허용. admin-enabled=false면 registry 빈이 없어 NOOP.
- [ ] **Step 5: 테스트 갱신** — 기존 잡 테스트 생성자에 `ProgressReporter.NOOP` 추가. ContentAnalysisJobTest에 보고 검증 1개:

```java
@Test
void 진행률을_보고한다() {
	List<int[]> reports = new ArrayList<>();
	ProgressReporter reporter = (p, f, t) -> reports.add(new int[]{p, f, t});
	// 기존 테스트 픽스처의 잡 생성부에 reporter 전달, 대상 1건 시드 후 run()
	// 검증: 첫 보고 (0,0,1), 마지막 보고 (1,0,1)
	assertThat(reports.getFirst()).containsExactly(0, 0, 1);
	assertThat(reports.getLast()).containsExactly(1, 0, 1);
}
```

- [ ] **Step 6: `./gradlew :analytics:test` PASS 확인 후 커밋** — `feat(analytics): 잡 3종 진행률 보고 + JobResult 반환`

---

### Task 3: RunHistory + AnalyticsJobService 기록 훅

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/RunHistory.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` (registry·history 빈 + JobService 주입)
- Test: `analytics/src/test/java/com/celfit/analytics/admin/RunHistoryTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunHistoryTest {

	@Test
	void 최신순_반환_및_상한() {
		RunHistory history = new RunHistory(3);
		for (int i = 0; i < 5; i++) {
			history.record(new RunHistory.Run(JobName.MIRROR, TriggerType.MANUAL,
					java.time.Instant.ofEpochSecond(i), java.time.Instant.ofEpochSecond(i + 1),
					RunHistory.Outcome.SUCCESS, i, 0, null));
		}
		assertThat(history.recent(10)).hasSize(3);
		assertThat(history.recent(10).getFirst().processed()).isEqualTo(4); // 최신이 앞
		assertThat(history.recent(2)).hasSize(2);
	}
}
```

- [ ] **Step 2: 실패 확인** → 컴파일 실패
- [ ] **Step 3: 구현**

```java
// analytics/src/main/java/com/celfit/analytics/admin/RunHistory.java
package com.celfit.analytics.admin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 실행 피드용 인메모리 링 버퍼 — DB 이력 테이블 안 둠(07-17 결정 유지), 재시작 시 소실 수용.
 */
public class RunHistory {

	public enum Outcome { SUCCESS, FAILED, QUOTA_CARRYOVER, ERROR }

	public record Run(JobName job, TriggerType trigger, Instant startedAt, Instant endedAt,
			Outcome outcome, int processed, int failed, String note) {
	}

	private final int capacity;
	private final Deque<Run> runs = new ArrayDeque<>();

	public RunHistory(int capacity) {
		this.capacity = capacity;
	}

	public synchronized void record(Run run) {
		runs.addFirst(run);
		while (runs.size() > capacity) {
			runs.removeLast();
		}
	}

	public synchronized List<Run> recent(int limit) {
		return runs.stream().limit(limit).toList();
	}
}
```

- [ ] **Step 4: AnalyticsJobService 훅** — `run(JobName)` 반환형을 `JobResult`로 (MIRROR는 뷰 단위 진행 보고 + `new JobResult(totalRows, 0, false)`, CLASSIFY는 기존 int 반환을 JobResult로 감쌈). trigger()의 executor 블록:

```java
executor.execute(() -> {
	Instant startedAt = Instant.now();
	registry.start(job);          // JobProgressRegistry (신규 주입)
	JobResult result = null;
	Exception error = null;
	try {
		log.info("{} 시작 (trigger={})", job, triggerType);
		result = run(job);
	} catch (Exception e) {
		error = e;
		log.error("{} 잡 실패", job, e);
	} finally {
		registry.finish(job);
		lock.release(job);
		history.record(new RunHistory.Run(job, triggerType, startedAt, Instant.now(),
				outcomeOf(result, error),
				result == null ? 0 : result.processed(),
				result == null ? 0 : result.failed(),
				error == null ? null : error.getMessage()));
	}
});
```

```java
static RunHistory.Outcome outcomeOf(JobResult result, Exception error) {
	if (error != null) return RunHistory.Outcome.ERROR;
	if (result.carriedOver()) return RunHistory.Outcome.QUOTA_CARRYOVER;
	return result.failed() > 0 ? RunHistory.Outcome.FAILED : RunHistory.Outcome.SUCCESS;
}
```

MIRROR 분기의 뷰 단위 진행 보고: 루프에서 `registry.reporter(JobName.MIRROR).report(done, 0, registry.specs().size())` — 주의: 여기 `registry`는 MirrorRegistry와 이름 충돌 → JobProgressRegistry 필드명은 `progress`로.
- [ ] **Step 5: AdminConfig 빈 추가** — `JobProgressRegistry`, `RunHistory(50)` 빈 + AnalyticsJobService 생성자 주입 확장.
- [ ] **Step 6: 전체 테스트 PASS 후 커밋** — `feat(analytics): 실행 피드 RunHistory + 잡 서비스 진행·이력 훅`

---

### Task 4: ScheduleInfo

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/ScheduleInfo.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/ScheduleInfoTest.java`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ScheduleInfoTest {

	@Test
	void 크론_다음_발화를_KST로() {
		ScheduleInfo info = new ScheduleInfo(true, "0 30 19 * * *", "-", "-", "-");
		ZonedDateTime base = ZonedDateTime.of(2026, 7, 19, 10, 0, 0, 0, ZoneId.of("UTC"));
		// UTC 19:30 = KST 04:30 익일
		assertThat(info.next(JobName.MIRROR, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
					assertThat(t.getHour()).isEqualTo(4);
					assertThat(t.getMinute()).isEqualTo(30);
				});
		assertThat(info.next(JobName.ANALYZE, base)).isEmpty(); // "-" = 비활성
	}

	@Test
	void 비활성이면_전부_빈값() {
		ScheduleInfo info = new ScheduleInfo(false, "0 30 19 * * *", "-", "-", "-");
		assertThat(info.next(JobName.MIRROR, ZonedDateTime.now())).isEmpty();
		assertThat(info.enabled()).isFalse();
	}
}
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**

```java
// analytics/src/main/java/com/celfit/analytics/admin/ScheduleInfo.java
package com.celfit.analytics.admin;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;

/** 스케줄 가시화 — ScheduleRunner와 같은 프로퍼티를 읽어 잡별 다음 발화 시각(KST)을 계산. */
public class ScheduleInfo {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final boolean enabled;
	private final Map<JobName, CronExpression> crons = new EnumMap<>(JobName.class);

	public ScheduleInfo(@Value("${analytics.schedule.enabled:false}") boolean enabled,
			@Value("${analytics.schedule.mirror-cron:-}") String mirrorCron,
			@Value("${analytics.schedule.classify-cron:-}") String classifyCron,
			@Value("${analytics.schedule.analyze-cron:-}") String analyzeCron,
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron) {
		this.enabled = enabled;
		put(JobName.MIRROR, mirrorCron);
		put(JobName.CLASSIFY, classifyCron);
		put(JobName.ANALYZE, analyzeCron);
		put(JobName.ACCOUNT_ANALYZE, accountCron);
	}

	private void put(JobName job, String cron) {
		// "-"는 스케줄러 비활성 컨벤션(@Scheduled와 동일). 파싱 실패는 미표시로 강등 — 화면은 살아야 한다.
		if (cron == null || "-".equals(cron.strip())) return;
		try {
			crons.put(job, CronExpression.parse(cron.strip()));
		} catch (IllegalArgumentException ignored) {
		}
	}

	public boolean enabled() {
		return enabled;
	}

	/** base 시점 이후 첫 발화를 KST로. 스케줄 off·크론 미지정이면 empty. */
	public Optional<ZonedDateTime> next(JobName job, ZonedDateTime base) {
		if (!enabled || !crons.containsKey(job)) return Optional.empty();
		ZonedDateTime next = crons.get(job).next(base);
		return Optional.ofNullable(next).map(t -> t.withZoneSameInstant(KST));
	}
}
```

- [ ] **Step 4: PASS 확인 후 커밋** — `feat(analytics): 스케줄 가시화 ScheduleInfo — 크론 다음 발화 KST 계산`

---

### Task 5: PipelineStatsService (+ JobCostEstimator 삭제)

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/admin/PipelineStatsService.java`
- Delete: `analytics/src/main/java/com/celfit/analytics/admin/JobCostEstimator.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminConfig.java` (빈 교체)
- Test: `analytics/src/test/java/com/celfit/analytics/admin/PipelineStatsServiceTest.java`

- [ ] **Step 1: 실패하는 테스트** (순수 계산부 — 해석 문장·일수)

```java
package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PipelineStatsServiceTest {

	@Test
	void 잔여와_배치상한으로_오늘예정과_소요일() {
		// 후보 25764, 완료 1213, 상한 450 → 잔여 24551, 오늘 450, 55일
		assertThat(PipelineStatsService.todayPlanned(25764, 1213, 450)).isEqualTo(450);
		assertThat(PipelineStatsService.daysToFull(25764, 1213, 450)).isEqualTo(55);
		// 잔여 0
		assertThat(PipelineStatsService.todayPlanned(100, 100, 450)).isZero();
		assertThat(PipelineStatsService.daysToFull(100, 100, 450)).isZero();
		// 상한 0 방어
		assertThat(PipelineStatsService.daysToFull(10, 0, 0)).isZero();
	}
}
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**

```java
// analytics/src/main/java/com/celfit/analytics/admin/PipelineStatsService.java
package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 퍼널 숫자 — 빠른 집계(단순 카운트, 매 요청)와 무거운 집계(후보 뷰 스캔 — 운영 실측 3.5분,
 * 비동기 + TTL 캐시)를 분리한다. PR #45의 비용 카드 캐시 패턴 승계 (JobCostEstimator는 본 서비스로 대체·삭제).
 */
public class PipelineStatsService {

	private static final Logger log = LoggerFactory.getLogger(PipelineStatsService.class);
	private static final java.time.Duration TTL = java.time.Duration.ofMinutes(30);

	/** candidates가 -1이면 아직 집계 전("집계 중…" 표시). */
	public record Funnel(long rawContents, long candidates, long analyzed, long served,
			long copiedAccounts, long beautyAccounts, int todayPlanned, int daysToFull,
			Instant heavyComputedAt) {
	}

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	private volatile long cachedCandidates = -1;
	private volatile Instant heavyComputedAt;
	private final AtomicBoolean computing = new AtomicBoolean();

	public PipelineStatsService(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.settings = settings;
	}

	public Funnel funnel() {
		refreshHeavyIfStale();
		long rawContents = count(raw, "SELECT count(*) FROM content");
		long analyzed = count(analysis, "SELECT count(*) FROM content_analyses");
		long served = count(analysis, "SELECT count(*) FROM contents");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		long beauty = count(analysis, "SELECT count(*) FROM accounts");
		long candidates = cachedCandidates;
		int limit = settings.analyzeBatchLimit();
		return new Funnel(rawContents, candidates, analyzed, served, copied, beauty,
				candidates < 0 ? 0 : todayPlanned(candidates, analyzed, limit),
				candidates < 0 ? 0 : daysToFull(candidates, analyzed, limit),
				heavyComputedAt);
	}

	static int todayPlanned(long candidates, long analyzed, int batchLimit) {
		long remaining = Math.max(0, candidates - analyzed);
		return (int) Math.min(remaining, batchLimit);
	}

	static int daysToFull(long candidates, long analyzed, int batchLimit) {
		long remaining = Math.max(0, candidates - analyzed);
		if (remaining == 0 || batchLimit <= 0) return 0;
		return (int) Math.ceilDiv(remaining, batchLimit);
	}

	private void refreshHeavyIfStale() {
		boolean stale = heavyComputedAt == null
				|| Instant.now().isAfter(heavyComputedAt.plus(TTL));
		if (stale && computing.compareAndSet(false, true)) {
			Thread.ofVirtual().name("pipeline-stats").start(() -> {
				try {
					cachedCandidates = count(raw, "SELECT count(*) FROM analytics.v_analysis_candidates");
					heavyComputedAt = Instant.now();
				} catch (RuntimeException e) {
					log.warn("후보 집계 실패 — 이전 캐시 유지", e);
				} finally {
					computing.set(false);
				}
			});
		}
	}

	private static long count(JdbcTemplate t, String sql) {
		Long v = t.queryForObject(sql, Long.class);
		return v == null ? 0 : v;
	}
}
```

- [ ] **Step 4: JobCostEstimator.java 삭제 + AdminConfig에서 빈 교체** (`jobCostEstimator` → `pipelineStatsService`, 시그니처 동일 인자).
- [ ] **Step 5: PASS 확인 후 커밋** — `feat(analytics): 퍼널 집계 PipelineStatsService — 비용 카드(JobCostEstimator) 대체`

---

### Task 6: 컨트롤러 개편 + 템플릿·CSS 전면 재작성

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java`
- Rewrite: `analytics/src/main/resources/templates/admin.html`
- Create: `analytics/src/main/resources/templates/fragments/board.html` (잡 카드 + 피드 — 5초 폴링 단위)
- Delete: `analytics/src/main/resources/templates/fragments/status.html`
- Rewrite: `analytics/src/main/resources/static/css/admin.css` (크롤러 디자인 토큰 이식)

- [ ] **Step 1: 컨트롤러** — 카드 뷰모델 조립을 컨트롤러 내 private으로:

```java
public record JobCard(JobName job, String label, boolean running,
		int processed, int failed, int total, Instant startedAt,
		String etaText,                       // 실행 중 선형 외삽 "~13:26" (총 0이면 null)
		RunHistory.Run lastRun,               // null 허용
		ZonedDateTime nextRunAt) {            // null = 수동 전용
}
```

- `/ui` 모델: `funnel`(PipelineStatsService), `cards`(MIRROR·ANALYZE·ACCOUNT_ANALYZE 3종 — CLASSIFY는 휴면 제외), `feed`(history.recent(20)), `scheduleEnabled`.
- `fragments/board` GET 핸들러: cards + feed만 재조립해 `fragments/board :: board` 반환.
- eta 계산: `startedAt + elapsed * total / max(processed,1)` — processed==0이면 null. KST 포맷은 템플릿에서 `#temporals`.

- [ ] **Step 2: fragments/board.html** — 잡 카드 그리드 + 실행 피드(카드 2장). 실행 중 카드: 진행 바(`style="width: ${processed*100/total}%"`), `processed/total · 실패 n · 예상 완료 eta`. 유휴 카드: lastRun(시각·outcome 배지·처리 건수) + `다음 예정` (nextRunAt KST HH:mm, 없으면 "수동 전용"). 트리거 폼 버튼(실행 중 disabled). 피드: outcome 색 점 + 시각 + 문장("콘텐츠 분석 완료 — 448건 처리 · 실패 2 (스케줄)"), 비어 있으면 "서버 시작 후 실행 없음 (이력은 메모리 보관)".

- [ ] **Step 3: admin.html** — 스펙 §2 레이아웃:

```
헤더(제목 + 자동화 배지 + "잡 3종 독립 실행" 보조 배지)
퍼널 카드(캡션 "데이터가 흐르는 단계 (잡 실행 순서 아님)")
  콘텐츠: 4단 숫자(수집→후보→분석→서빙) + 커버리지 바 + 해석 문장 + "집계 HH:MM 기준"
  계정: 카피 보유/뷰티 모수 바
<div hx-get="/ui/fragments/board" hx-trigger="load, every 5s"> (잡 카드 + 피드)
접이식 라이브 로그 <details> + 기존 fragments/logs 폴링 유지
```

후보 -1(집계 전)이면 "집계 중…" 표기. 숫자 포맷 `#numbers.formatInteger(..., 3, 'COMMA')`.

- [ ] **Step 4: admin.css** — 크롤러 admin.css의 `:root` 토큰(웜 뉴트럴 + 딥 로즈 + 다크모드 미디어쿼리, Pretendard import) 복사 후 대시보드 컴포넌트 클래스 재작성: `.hd .pill`, `.card`, `.funnel .stage/.arrow/.bar`, `.jobs 그리드(3열, 모바일 1열)`, `.job .jbar`, `.badge.run(로즈)/.idle`, `.feed .ev`(타임라인 행, outcome 색 점 `--status-good/critical/warning`), `details.logs`. 목업(layout-v3) 스타일을 프로덕션 품질로 정리.

- [ ] **Step 5: 로컬 부팅 검증** — `.claude/launch.json`의 로컬 DB로 `:analytics:bootRun` (admin-enabled 기본 true) → `/ui` 렌더·폴링·트리거(미러) 확인. 이 워크트리 기준:
  `./gradlew -p .worktrees/deploy-analytics :analytics:bootRun` (로컬 crawler-postgres-1 5433 기본 설정 그대로)
- [ ] **Step 6: 전체 테스트 + 커밋** — `feat(analytics): 어드민 대시보드 — 퍼널·잡 카드·실행 피드 (비용 카드 제거)`

---

### Task 7: 마무리 — 문서·PR

- [ ] **Step 1:** ARCHITECTURE §5 태스크 I 행에 대시보드 재설계 반영 + §7 결정 한 줄(비용 카드 폐지·관측 대시보드 전환, 스펙 링크). 계획 문서를 `plans/archive/`로 이동.
- [ ] **Step 2:** `./gradlew test` 전체 PASS.
- [ ] **Step 3:** push + PR 생성(develop 대상) — 본문에 스펙 링크·스크린샷. CI 통과 확인.
- [ ] **Step 4:** 머지 후 `deploy/scripts/deploy.sh <host> analytics` (워크트리를 머지된 develop에 고정하고 실행 — 메인 폴더 공유 레이스 주의), 서버 `/ui` 검증(응답 속도·퍼널 숫자·진행률).

## Self-Review

- 스펙 커버리지: §2 레이아웃(T6), §3-1(T1·T2), §3-2(T3), §3-3(T5), §3-4(T4), §3-5(T6), §3-6(T5), §5 에러(각 구현에 반영 — 파싱 강등·캐시 유지·베스트에포트), §6 테스트(T1~T5 단위 + T2 잡 테스트), §7 배포(T7). 누락 없음.
- 타입 일관성: `JobResult(processed, failed, carriedOver)` T2 정의 → T3 소비 일치. `JobProgressRegistry.Progress`·`reporter()` T1 정의 → T2·T3 사용 일치. `RunHistory.Run`/`Outcome` T3 정의 → T6 피드 소비 일치.
- 플레이스홀더 없음 확인.
