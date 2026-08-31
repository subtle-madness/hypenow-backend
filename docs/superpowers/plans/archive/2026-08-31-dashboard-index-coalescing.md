# 성과 대시보드 동시 조립 합류(single-flight) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 유저·같은 버전키의 대시보드 인덱스 조립이 동시에 N번 도는 것을 1번으로 접어, 병렬 버스트에서 26초까지 늘어지는 꼬리를 조립 1회분(8초대)으로 되돌린다.

**Architecture:** `PerformanceContentAssembler`는 그대로 두고, 그 앞에 `DashboardIndexCoalescer`를 세운다. 진행 중인 조립을 `ConcurrentHashMap<Key, CompletableFuture<DashboardIndex>>`에 표식으로 올려 두고 리더 1명만 조립하며, 같은 키로 들어온 요청은 그 future에 합류한다. **완료 즉시 맵에서 제거하므로 캐시가 아니다**(힙 증가 0). 합류 키에 쓰는 버전키는 `conditional()`이 이미 계산해 놓은 값을 body로 넘겨 요청당 1회만 계산한다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · JUnit 5 + Mockito(`MockitoExtension`) + AssertJ · `@WebMvcTest`(Spring Boot 4는 `org.springframework.boot.webmvc.test.autoconfigure`)

**설계 문서:** [2026-08-31-dashboard-index-coalescing-design.md](../specs/2026-08-31-dashboard-index-coalescing-design.md)

## Global Constraints

- 주석·로그·커밋 메시지는 **한국어**. 커밋 prefix는 `feat(was):` / `docs:` 식(CLAUDE.md 컨벤션).
- 브랜치는 이미 `feature/performance-dashboard-slow-requests-cc3a3f`(worktree). **develop 대상 PR**로 합친다.
- **보관 금지**가 이 작업의 계약이다 — 조립 결과를 요청 종료 후까지 들고 있는 코드를 넣지 않는다(캐시는 별도 설계에서 다룬다).
- 응답 바디·HTTP 계약은 **한 글자도 바뀌지 않는다**. FE 변경 없음.
- 이 PR 범위 밖(설계 §7): 인덱스 캐시, `findAccounts` 배치, 화면 범위 스코핑, 콜드 8초 절감.
- 단위 테스트는 도커 없이 돈다. 전체 `:was:test`만 Testcontainers가 필요하다(이 머신은 Docker Desktop이라 `DOCKER_HOST` 설정 불필요).

## File Structure

| 파일 | 책임 |
|---|---|
| `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescer.java` (신규) | 진행 중인 인덱스 조립에 합류시킨다. 보관하지 않는다. |
| `was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescerTest.java` (신규) | 합류·미합류·비보관·예외 전파 계약 고정 |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` (수정) | `conditional`이 버전키를 body로 넘기고, 4라우트가 합류기를 경유 |
| `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java` (수정) | 인덱스 스텁을 합류기로 재지정 + "요청당 `compute` 1회" 추가 |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` (수정) | `DashboardIndex`를 완전 불변으로(공유 안전성) |
| `DECISIONS.md` · 설계 문서 상태 헤더 · 본 계획서 아카이브 이동 | 문서 정합 |

---

### Task 1: `DashboardIndexCoalescer` — 진행 중인 조립에 합류

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescer.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescerTest.java`

**Interfaces:**
- Consumes: `PerformanceContentAssembler.index(long userId)` → `PerformanceContentAssembler.DashboardIndex` (기존, 시그니처 변경 없음)
- Produces: `DashboardIndexCoalescer.index(String version, long userId)` → `DashboardIndex` — Task 2의 컨트롤러가 이걸 부른다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescerTest.java`:

```java
package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 동시 조립 합류 계약(2026-08-31 설계 §2) — <b>보관하지 않는다</b>는 것까지 포함해 고정한다.
 * 조립 mock은 호출마다 <b>새 인스턴스</b>를 돌려주므로, 합류가 실패하면 반환 인스턴스가 갈려서
 * 테스트가 즉시 깨진다(횟수 단정과 인스턴스 단정이 서로를 보강한다).
 */
@ExtendWith(MockitoExtension.class)
class DashboardIndexCoalescerTest {

	private static final String VERSION = "0123456789abcdef0123456789abcdef";
	private static final String OTHER_VERSION = "ffffffffffffffffffffffffffffffff";
	private static final long USER_ID = 7L;
	/** 대기 상한 — 합류 실패 시 무한 대기로 굳지 않게 모든 대기에 건다. */
	private static final long TIMEOUT_SECONDS = 5;

	@Mock
	PerformanceContentAssembler assembler;

	DashboardIndexCoalescer coalescer;

	@BeforeEach
	void 합류기() {
		coalescer = new DashboardIndexCoalescer(assembler);
	}

	@Test
	void 보관하지_않는다_순차_호출은_매번_다시_조립한다() {
		given(assembler.index(USER_ID)).willAnswer(invocation -> emptyIndex());

		DashboardIndex first = coalescer.index(VERSION, USER_ID);
		DashboardIndex second = coalescer.index(VERSION, USER_ID);

		assertThat(second).isNotSameAs(first);
		then(assembler).should(times(2)).index(USER_ID);
	}

	@Test
	void 동시_진입은_조립_1회로_접히고_전원_같은_인스턴스를_받는다() throws Exception {
		CountDownLatch 리더진입 = new CountDownLatch(1);
		CountDownLatch 리더해제 = new CountDownLatch(1);
		AtomicInteger 조립횟수 = new AtomicInteger();
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			조립횟수.incrementAndGet();
			리더진입.countDown();
			assertThat(리더해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});

		List<Thread> 합류자스레드 = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(7);
		try {
			Future<DashboardIndex> 리더 = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			assertThat(리더진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

			List<Future<DashboardIndex>> 합류자 = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				합류자.add(pool.submit(() -> {
					합류자스레드.add(Thread.currentThread());
					return coalescer.index(VERSION, USER_ID);
				}));
			}
			awaitParked(합류자스레드, 6);   // 6명이 전부 대기에 들어간 뒤에 리더를 푼다
			리더해제.countDown();

			DashboardIndex expected = 리더.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			for (Future<DashboardIndex> f : 합류자) {
				assertThat(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isSameAs(expected);
			}
			assertThat(조립횟수).hasValue(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 버전이_다르면_합류하지_않고_각자_조립한다() throws Exception {
		CountDownLatch 둘다진입 = new CountDownLatch(2);
		CountDownLatch 해제 = new CountDownLatch(1);
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			둘다진입.countDown();
			assertThat(해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DashboardIndex> a = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			Future<DashboardIndex> b = pool.submit(() -> coalescer.index(OTHER_VERSION, USER_ID));

			// 둘 다 조립에 들어왔다는 것 자체가 "합류하지 않았다"의 증거다.
			assertThat(둘다진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			해제.countDown();

			assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isNotSameAs(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			then(assembler).should(times(2)).index(USER_ID);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 리더가_실패해도_보관되지_않아_다음_호출은_새로_조립한다() {
		given(assembler.index(USER_ID))
				.willThrow(new IllegalStateException("조립 실패"))
				.willAnswer(invocation -> emptyIndex());

		assertThatThrownBy(() -> coalescer.index(VERSION, USER_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("조립 실패");
		assertThat(coalescer.index(VERSION, USER_ID)).isNotNull();
		then(assembler).should(times(2)).index(USER_ID);
	}

	@Test
	void 리더의_예외는_합류자에게_포장_없이_전파된다() throws Exception {
		CountDownLatch 리더진입 = new CountDownLatch(1);
		CountDownLatch 리더해제 = new CountDownLatch(1);
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			리더진입.countDown();
			assertThat(리더해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			throw new IllegalStateException("조립 실패");
		});

		List<Thread> 합류자스레드 = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DashboardIndex> 리더 = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			assertThat(리더진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			Future<DashboardIndex> 합류자 = pool.submit(() -> {
				합류자스레드.add(Thread.currentThread());
				return coalescer.index(VERSION, USER_ID);
			});
			awaitParked(합류자스레드, 1);
			리더해제.countDown();

			// CompletionException이 벗겨지지 않으면 cause가 그것이 되어 이 단정이 깨진다.
			assertThatThrownBy(() -> 합류자.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> 리더.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(IllegalStateException.class);
		} finally {
			pool.shutdownNow();
		}
	}

	/** 합류자 n명이 전부 대기 상태로 들어갈 때까지 — join()에 진입했다는 신호다. */
	private static void awaitParked(List<Thread> threads, int n) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			synchronized (threads) {
				if (threads.size() == n && threads.stream().allMatch(t -> {
					Thread.State state = t.getState();
					return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
				})) {
					return;
				}
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("합류자 " + n + "명이 대기 상태로 들어가지 않았다");
	}

	/** 조립 결과 자리를 채우는 빈 인덱스 — 호출마다 새 인스턴스여야 한다(합류 판별의 근거). */
	private static DashboardIndex emptyIndex() {
		return new DashboardIndex(USER_ID, List.of(), null, Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
	}
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.DashboardIndexCoalescerTest"
```

Expected: 컴파일 실패 — `DashboardIndexCoalescer` 심볼 없음.

- [ ] **Step 3: 최소 구현**

`was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescer.java`:

```java
package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardIndex;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 대시보드 인덱스 <b>동시 조립 합류</b>(2026-08-31 — growth 26.5초 응급 처치).
 *
 * <p><b>무엇이 느렸나</b>: 대시보드 4표면은 요청마다 {@link PerformanceContentAssembler#index}로 유저의
 * 브랜드 풀 전량을 다시 만든다(운영 실측 2026-08-31, 브랜드 6개·창 안 15,177행: <b>8.4초</b>). 그런데
 * FE는 개요 화면에서 계정마다 growth를 1건씩 <b>병렬로</b> 쏘고, {@code accountIds}는 인덱스를 다 만든
 * 뒤 메모리에서 거는 필터라 N개 요청이 각자 N브랜드 전량을 만든다. 2코어 호스트에서 7건이 겹치자
 * 8.4초가 <b>26.5초</b>가 됐다(request_id mbx0prz2).
 *
 * <p><b>그래서 중복만 없앤다</b>: 같은 (유저, 버전키)의 조립이 이미 진행 중이면 그 결과에 합류한다.
 * 리더 1명만 조립하고 나머지는 같은 인스턴스를 받는다 — 인덱스는 순수 파생값이라 어느 쪽이 만들든
 * 결과가 같다.
 *
 * <p><b>보관하지 않는다 — 캐시가 아니다.</b> 맵에 올리는 것은 값이 아니라 "진행 중"이라는 표식이고
 * {@code finally}가 완료 즉시 제거한다. 보관은 웜 요청을 수십 ms로 만들지만 콜드 8.4초를 못 고치고
 * 유저당 ~13MB를 문다. 무엇보다 후속 구조 개편(화면 범위 스코핑 + DB 집계 — 화면은 724행이면 되는데
 * 15,177행을 만든다)이 오면 조립 자체가 100ms대가 되어 보관할 이유가 사라진다(설계 §2-2·§7-1).
 *
 * <p><b>키에 버전이 들어가는 이유</b>: 자정 경계·스윕 순간에는 요청마다 {@link DashboardVersion} 값이
 * 갈릴 수 있다. 버전이 다른 요청끼리 합류하면 ETag(신)와 바디(구)가 어긋난다 — 키를 유저로만 잡으면
 * 생기는 사고라 버전을 같이 넣는다.
 *
 * <p><b>리더 실패는 합류자에게도 전파한다</b>({@link #join}). 합류자가 각자 재시도하면 DB가 힘든 바로
 * 그 순간에 N중 재조립이 터진다 — 같이 실패하고 클라이언트가 재시도하는 편이 낫다. 실패한 키는
 * 맵에 남지 않으므로 다음 요청은 새로 조립한다.
 */
@Component
public class DashboardIndexCoalescer {

	private final PerformanceContentAssembler assembler;

	/** 진행 중인 조립만 담는다 — 완료 즉시 제거하므로 상주량은 "지금 조립 중인 키" 수다. */
	private final Map<Key, CompletableFuture<DashboardIndex>> inFlight = new ConcurrentHashMap<>();

	public DashboardIndexCoalescer(PerformanceContentAssembler assembler) {
		this.assembler = assembler;
	}

	/**
	 * 인덱스 — 같은 키의 조립이 진행 중이면 그 결과에 합류하고, 아니면 내가 리더로 조립한다.
	 *
	 * @param version 이 요청의 버전키({@link DashboardVersion#compute}) — 요청당 1회 계산한 값을 넘긴다
	 */
	public DashboardIndex index(String version, long userId) {
		Key key = new Key(userId, version);
		CompletableFuture<DashboardIndex> mine = new CompletableFuture<>();
		CompletableFuture<DashboardIndex> leader = inFlight.putIfAbsent(key, mine);
		if (leader != null) {
			return join(leader);
		}
		try {
			// 조립은 맵 밖에서 한다 — 8초짜리를 락 안에서 돌리면 다른 유저의 진입까지 줄을 선다.
			DashboardIndex value = assembler.index(userId);
			mine.complete(value);
			return value;
		} catch (Throwable t) {
			// 합류자가 영원히 매달리지 않게 반드시 완료시킨다(정상·실패 어느 쪽이든).
			mine.completeExceptionally(t);
			throw t;
		} finally {
			inFlight.remove(key, mine);
		}
	}

	/** 합류자 대기 — {@link CompletionException} 포장을 벗겨 리더가 던진 예외를 그대로 던진다. */
	private static DashboardIndex join(CompletableFuture<DashboardIndex> leader) {
		try {
			return leader.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}

	/** 합류 단위 — 유저 + 버전키. 버전이 다르면 다른 조립이다(위 javadoc). */
	private record Key(long userId, String version) {
	}
}
```

- [ ] **Step 4: 통과를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.DashboardIndexCoalescerTest"
```

Expected: 5개 테스트 PASS. 실패하면 **재시도하지 말고** 원인을 본다 — `동시_진입` 테스트가 깨지면
`awaitParked`가 잡은 스레드 상태를 로그로 찍어 합류자가 실제로 `join()`에 들어갔는지부터 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescer.java was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardIndexCoalescerTest.java
git commit -m "feat(was): 대시보드 인덱스 동시 조립 합류 — 리더 1명만 조립하고 나머지는 합류(보관 없음)"
```

---

### Task 2: 컨트롤러 배선 — 버전키를 요청당 1회 계산해 합류 키로 넘긴다

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` (22행 import, 146~152행 생성자, 159행 `conditional`, 258·260행 contents, 339·343행 comparison, 394·395행 influencers, 490·491행 growth)
- Modify: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java`

**Interfaces:**
- Consumes: `DashboardIndexCoalescer.index(String version, long userId)` (Task 1)
- Produces: 없음(HTTP 표면은 그대로) — 응답 바디·헤더·상태코드가 바뀌지 않는 것이 이 태스크의 계약이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다 — "요청당 버전키 1회"**

`V1PerformanceDashboardControllerTest`에 추가한다(기존 테스트 스타일 그대로, 한국어 메서드명):

```java
	@Test
	void 한_요청에서_버전키는_한_번만_계산된다() throws Exception {
		DashboardIndex empty = new DashboardIndex(7L, List.of(), null, Set.of(),
				Map.of(), Map.of(), Map.of(), Map.of());
		given(coalescer.index(VERSION, 7L)).willReturn(empty);
		given(assembler.hydratePage(eq(empty), anyList())).willReturn(List.of());

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk());

		// 조건부 판정과 합류 키가 같은 값을 써야 한다 — 두 번 계산하면 자정 경계에서 갈린다.
		then(dashboardVersion).should(times(1)).compute(7L);
		then(coalescer).should().index(VERSION, 7L);
	}
```

`import static org.mockito.Mockito.times;`가 없으면 추가한다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"
```

Expected: 컴파일 실패 — `coalescer` 심볼 없음.

- [ ] **Step 3: 컨트롤러를 고친다**

1) import 교체(22행): `import java.util.function.Supplier;` → `import java.util.function.Function;`
   (`Supplier`는 이 파일에서 `conditional` 외에 쓰이지 않는다 — 확인 후 제거)

2) 필드·생성자에 합류기를 추가한다:

```java
	private final PerformanceContentAssembler assembler;
	private final DashboardIndexCoalescer coalescer;
	private final PerformanceComparisonAssembler comparisonAssembler;
	private final DashboardVersion dashboardVersion;

	public V1PerformanceDashboardController(PerformanceContentAssembler assembler,
			DashboardIndexCoalescer coalescer, PerformanceComparisonAssembler comparisonAssembler,
			DashboardVersion dashboardVersion) {
		this.assembler = assembler;
		this.coalescer = coalescer;
		this.comparisonAssembler = comparisonAssembler;
		this.dashboardVersion = dashboardVersion;
	}
```

3) `conditional`(159행)을 버전키를 넘기는 형태로 바꾼다. javadoc에 한 문단을 덧붙인다:

```java
	 * <p>계산한 버전키는 {@code body}로 넘긴다(2026-08-31) — {@link DashboardIndexCoalescer}의 합류
	 * 키가 이 값이라, body 안에서 다시 계산하면 <b>같은 요청 안에서 키가 갈릴 수 있다</b>(자정 경계·
	 * 동시 스윕). 그러면 ETag(신)와 바디(구)가 어긋난다.
	 */
	private <T> ResponseEntity<T> conditional(long userId, String ifNoneMatch, Function<String, T> body) {
		String version = dashboardVersion.compute(userId);
		String etag = DashboardVersion.etagOf(version);
		if (DashboardVersion.matches(ifNoneMatch, etag)) {
			return cached(HttpStatus.NOT_MODIFIED, etag).build();
		}
		return cached(HttpStatus.OK, etag).body(body.apply(version));
	}
```

4) 4개 라우트의 람다를 `version ->`로 바꾸고 인덱스 획득을 합류기로 돌린다:

- contents(258·260행):
```java
		return conditional(principal.getUserId(), ifNoneMatch, version -> {
			// 인덱스 패스(경량) — 여기부터 페이지 슬라이스까지 전부 ref 위에서 끝낸다.
			PerformanceContentAssembler.DashboardIndex index = coalescer.index(version, principal.getUserId());
```
- comparison(339·343행):
```java
		return conditional(principal.getUserId(), ifNoneMatch, version -> {
			...
			List<PerformanceContentAssembler.DashboardRef> filtered =
					coalescer.index(version, principal.getUserId()).refs().stream()
```
- influencers(394·395행):
```java
		return conditional(principal.getUserId(), ifNoneMatch, version -> {
			PerformanceContentAssembler.DashboardIndex index = coalescer.index(version, principal.getUserId());
```
- growth(490·491행):
```java
		return conditional(principal.getUserId(), ifNoneMatch, version -> {
			PerformanceContentAssembler.DashboardIndex index = coalescer.index(version, principal.getUserId());
```

`assembler`는 남는다 — `hydratePage`(contents)와 `assemble`(단건)이 계속 쓴다.

- [ ] **Step 4: 컨트롤러 테스트를 합류기로 재지정한다**

1) `@MockitoBean` 선언에 추가:

```java
	@MockitoBean
	DashboardIndexCoalescer coalescer;
```

2) 인덱스 스텁·검증을 기계적으로 옮긴다(모두 같은 파일 안):

| 종전 | 변경 |
|---|---|
| `lenient().when(assembler.index(7L))` | `lenient().when(coalescer.index(VERSION, 7L))` |
| `given(assembler.index(7L))` | `given(coalescer.index(VERSION, 7L))` |
| `then(assembler).should(never()).index(anyLong())` | `then(coalescer).should(never()).index(any(), anyLong())` |
| `then(assembler).should().index(7L)` | `then(coalescer).should().index(VERSION, 7L)` |

`assembler.assemble(...)`·`assembler.hydratePage(...)` 스텁은 **그대로 둔다**(컨트롤러가 계속 직접 부른다).

3) 400으로 끝나는 테스트들은 버전키 계산에 도달하지 않으므로(`then(dashboardVersion).should(never()).compute(anyLong())`) 그대로 통과해야 한다 — 손대지 않는다.

- [ ] **Step 5: 통과를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"
```

Expected: 전부 PASS. 특히 304 경로 테스트가 그대로 통과해야 한다(조건부 미스 전에는 합류기에 진입하지 않는다).

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java
git commit -m "feat(was): 성과 대시보드 4표면이 합류기 경유 — 버전키는 요청당 1회 계산해 합류 키로 전달"
```

---

### Task 3: `DashboardIndex` 공유 안전성 하드닝

인덱스 인스턴스 하나를 합류자 여러 스레드가 동시에 읽는다. 소비 쪽은 이미 읽기 전용이지만(2026-08-31 감사: `hydratePage`·컨트롤러 4곳), 생성 시 방어 복사가 빠진 두 곳을 막아 **구조적으로** 안전하게 만든다.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` (244행 `new DashboardIndex(...)`, 356행 `new DashboardIndex.BrandHydration(...)`, 909행 레코드 javadoc)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Consumes: 없음 / Produces: `DashboardIndex`의 모든 컬렉션 필드가 불변이라는 성질(Task 1 합류기가 이 성질에 의존한다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`PerformanceContentAssemblerTest`에 추가한다(기존 테스트의 스텁 헬퍼를 그대로 쓰고, 인덱스를 만든 뒤 컬렉션 변형을 시도한다):

```java
	@Test
	void 인덱스의_컬렉션은_전부_불변이다_합류자_공유_전제() {
		// 이 테스트가 쓰는 스텁 구성은 같은 클래스의 index() 테스트들과 동일하다.
		PerformanceContentAssembler.DashboardIndex index = assembler.index(USER_ID);

		assertThatThrownBy(() -> index.campaignsById().put(999L, null))
				.isInstanceOf(UnsupportedOperationException.class);
		index.brandsById().values().forEach(brand ->
				assertThatThrownBy(() -> brand.ownedShortCodes().add("XXX"))
						.isInstanceOf(UnsupportedOperationException.class));
	}
```

주의: 이 클래스의 기존 테스트가 `index()`를 부를 때 쓰는 스텁 세팅(브랜드 계정·링크·인덱스 행)을 그대로 재사용한다 — 브랜드가 하나도 없는 세팅이면 `brandsById()`가 비어 두 번째 단정이 공회전하므로, **브랜드 1개와 `ownedShortCodes`가 실린 세팅**을 쓰는 기존 테스트를 골라 그 준비 코드를 복제한다.

- [ ] **Step 2: 실패를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"
```

Expected: FAIL — `campaignsById`가 `HashMap`, `ownedShortCodes`가 리포지토리 반환 `Set`이라 변형이 성공한다.

- [ ] **Step 3: 방어 복사를 넣는다**

244행 `new DashboardIndex(...)`의 마지막 인자:

```java
		return new DashboardIndex(userId, List.copyOf(refs),
				lastCollectedAt(legacy.lastCollectedAt(), pool.lastSweptAt()), competitorIds,
				Map.copyOf(legacyCards), Map.copyOf(brandByCode), pool.brandsById(),
				Map.copyOf(campaignsById));
```

356행 `BrandHydration` 생성:

```java
			brandsById.put(brandAccountId,
					new DashboardIndex.BrandHydration(account, link.accountType(), Set.copyOf(ownedShortCodes)));
```

909행 `DashboardIndex` 레코드 javadoc에 한 줄 덧붙인다:

```java
 * <p>인스턴스는 {@link DashboardIndexCoalescer}가 여러 요청 스레드에 <b>같은 것을 나눠 준다</b> —
 * 모든 컬렉션 필드는 생성 시점에 불변으로 굳힌다(2026-08-31).
```

- [ ] **Step 4: 통과를 확인한다**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceContentAssemblerTest"
```

Expected: PASS. `Map.copyOf`는 null 값을 거부하므로, 여기서 NPE가 나면 `campaignsById`에 null 값이 들어오는 경로가 있다는 뜻이다 — 그때는 복사를 되돌리지 말고 그 경로를 먼저 확인한다(`CampaignRepository.findByUser`는 null 행을 돌려주지 않는다).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java
git commit -m "feat(was): DashboardIndex 컬렉션 전량 불변화 — 합류자 스레드 공유 전제"
```

---

### Task 4: 전체 검증 · 문서 정합 · PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 새 행)
- Modify: `docs/superpowers/specs/2026-08-31-dashboard-index-coalescing-design.md` (상태 헤더)
- Move: `docs/superpowers/plans/2026-08-31-dashboard-index-coalescing.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 모듈 전체 테스트**

```bash
./gradlew :was:test
```

Expected: 전부 PASS. 대량 실패가 나면 테스트 결함으로 오진하지 말고 도커부터 확인한다(CLAUDE.md 함정 — Testcontainers).

- [ ] **Step 2: 설계 문서 상태 헤더를 갱신한다**

`docs/superpowers/specs/2026-08-31-dashboard-index-coalescing-design.md` 2행:

```markdown
> 상태: ✅ 구현됨 (2026-08-31 작성 · §2~§5 구현, §7은 후속)
```

- [ ] **Step 3: `DECISIONS.md` 맨 위에 행을 추가한다**

기존 표 형식(`| 날짜 | 결정 | 근거 |`)에 맞춰, **왜 캐시가 아니라 합류인지**가 남게 쓴다:

```markdown
| 2026-08-31 | **성과 대시보드 26초 꼬리는 자기 경합이다 — 보관 없는 동시 조립 합류(single-flight)로 접는다** | 운영 growth 26.5초(mbx0prz2) 단계 분해: 앱 CPU는 123ms이고 26.4초가 리포지토리 대기다. 경합 전 같은 엔드포인트가 8.4초(`findBrandPostIndex` 6.2초/6콜 + `findLatestSnapshotsForBrand` 1.4초/6콜)이므로 **8.4초는 고정비, 나머지 18초는 7중 자기 경합**이다(값싼 `lastSuccessfulSweepAt`이 689ms로 부푼 것이 증거). FE가 계정마다 growth를 병렬로 쏘는데 `accountIds`가 조립 **후** 메모리 필터라 N요청이 각자 N브랜드를 만든다. ETag는 URL이 갈려 못 막는다. **캐시(보관)는 비채택** — 콜드 8.4초를 못 고치고 유저당 ~13MB를 무는데, 후속 구조 개편(화면은 7일 724행이면 되는데 180일 15,177행을 만든다 — 범위 스코핑 + DB 집계)이 오면 보관할 이유가 사라진다. FE의 계정별 N요청 수정도 같은 중복을 없애는 처방이라 8.4초를 못 줄인다. |
```

- [ ] **Step 4: 계획서를 아카이브로 옮기고 커밋**

```bash
git mv docs/superpowers/plans/2026-08-31-dashboard-index-coalescing.md docs/superpowers/plans/archive/
git add DECISIONS.md docs/superpowers/specs/2026-08-31-dashboard-index-coalescing-design.md
git commit -m "docs: 동시 조립 합류 구현 반영 — DECISIONS 행 추가, 스펙 상태 갱신, 계획서 아카이브"
```

- [ ] **Step 5: PR을 연다**

```bash
git push -u origin feature/performance-dashboard-slow-requests-cc3a3f
```

```bash
gh pr create --base develop --title "feat(was): 성과 대시보드 동시 조립 합류 — 26초 꼬리 응급 처치" --body "$(cat <<'EOF'
## 무엇을 고치나

운영 `GET /v1/performance-dashboard/growth`가 26.5초(request_id `mbx0prz2`, 08-31 00:32 UTC). 단계 분해상 앱 CPU는 123ms이고 26.4초가 리포지토리 대기다.

- 조립 1회 고정비 **8.4초** (브랜드 6개·창 안 15,177행)
- 나머지 **18초는 7중 자기 경합** — FE가 계정마다 growth를 병렬로 쏘는데 `accountIds`가 조립 후 메모리 필터라 N요청이 각자 N브랜드를 만든다

## 어떻게

`DashboardIndexCoalescer` — 같은 (유저, 버전키) 조립이 진행 중이면 합류한다. 리더 1명만 조립.

- **보관하지 않는다**(캐시 아님, 힙 증가 0) — 맵에 올리는 건 "진행 중" 표식이고 완료 즉시 제거
- 합류 키의 버전은 `conditional()`이 이미 계산한 값을 body로 넘겨 요청당 1회만 계산
- `DashboardIndex`를 완전 불변으로(스레드 공유 전제)

응답 바디·HTTP 계약 변경 없음. **FE 변경 없음.**

## 검증

배포 후 Loki `요청 단계 요약`에서 `/v1/performance-dashboard/*`를 본다 — 동시 버스트가 조립 1회분(8초대)으로 수렴하고 20초대 꼬리가 사라지는지, 합류자의 `repo_ms`가 0에 가까운지(합류의 직접 증거).

## 범위 밖

인덱스 캐시, `findAccounts` 배치, **화면 범위 스코핑 + DB 집계**(8.4초 고정비의 본체 — 화면은 7일 724행이면 되는데 180일 15,177행을 만든다). 설계 §7에 근거와 함께 적어 뒀다.

설계: `docs/superpowers/specs/2026-08-31-dashboard-index-coalescing-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**스펙 커버리지**

| 스펙 | 태스크 |
|---|---|
| §2-1 합류기 계약 | Task 1 |
| §2-2 보관하지 않음 | Task 1 (`보관하지_않는다_순차_호출은_매번_다시_조립한다`) |
| §2-3 리더 실패 전파 | Task 1 (테스트 4·5) |
| §3 버전키 요청당 1회 | Task 2 |
| §4 불변 하드닝 | Task 3 |
| §5 테스트 | Task 1·2·3 각 Step |
| §6 배포 후 검증 | Task 4 PR 본문 |
| §7 안 하는 것 | Global Constraints |

**타입 일관성**: `index(String version, long userId)`가 Task 1 정의 → Task 2 소비 → Task 2 테스트 검증까지 같은 시그니처. `DashboardIndex` 생성자 인자 순서(userId, refs, lastCollectedAt, competitorBrandAccountIds, legacyCards, brandByCode, brandsById, campaignsById)는 Task 1 테스트 헬퍼와 Task 2 테스트가 동일하게 쓴다.

**남은 판단 지점**(구현자가 코드를 보고 정할 것): Task 3 Step 1의 테스트는 `PerformanceContentAssemblerTest`의 기존 스텁 세팅에 얹는다 — 브랜드·`ownedShortCodes`가 실린 기존 테스트를 골라 준비 코드를 복제해야 두 번째 단정이 실제로 돈다.
