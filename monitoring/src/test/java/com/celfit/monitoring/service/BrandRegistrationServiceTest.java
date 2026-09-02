package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 등록/탈퇴 — 동기 구간은 프로필 1콜뿐이고 백필은 executor로 넘어간다. 백필 core는 동기
 * executor(Runnable::run)로 즉시 실행시키고, enrich는 <b>실제 단일 스레드 풀</b>로 돌린다
 * (2026-08-13 완결 배치 서빙 개정): core가 페이지 보강 태스크의 완료를 join()으로 기다리므로
 * 지연 큐로 두면 테스트가 영구 블록된다 — 실 배선과 같은 "core ≠ enrich 별도 풀"을 그대로 쓴다.
 * 백필 실패는 등록을 실패시키지 않는다(last_swept_on null 유지 → 다음 스윕 백스톱).
 */
class BrandRegistrationServiceTest {

	/** 확장 스킵 판정의 창 컷 계산이 KST 캘린더 개월이라 테스트도 같은 존을 쓴다. */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String PROFILE_JSON = """
			{"user":{"pk":111,"username":"brandx","full_name":"브랜드","profile_pic_url":"https://p",
			"biography":"소개","follower_count":1234,"following_count":10,"media_count":5,
			"is_private":false}}""";

	private static final class InMemoryBrands extends BrandRepository {
		final Map<String, BrandRow> rows = new HashMap<>();
		final List<Long> touched = new CopyOnWriteArrayList<>();
		final List<Long> served = new CopyOnWriteArrayList<>();
		/** markServing 호출마다 그 시점의 보강 완료 코드 스냅샷 — "첫 배치 보강 뒤 ready"의 관측 지점. */
		final List<List<String>> enrichedAtServingMark = new CopyOnWriteArrayList<>();
		/** touchSwept 시점의 보강 완료 코드 스냅샷 — FE 폴링 종료 조건이 미완성 목록에서 걸리는지 본다. */
		final List<List<String>> enrichedAtTouchSwept = new CopyOnWriteArrayList<>();
		final Map<Long, String> backfillErrors = new HashMap<>();
		final List<Long> expanded = new ArrayList<>();
		/** raiseWindowCapped 호출 기록(스펙 §7-2) — 창·폴백 인자까지 본다. */
		record CappedRaise(long brandId, int months, Instant coveredUntilFallback) {}

		final List<CappedRaise> cappedRaises = new ArrayList<>();
		/** 동시 확장 경합 주입 — 더 큰 창이 이미 반영돼 조건부 UPDATE가 0행을 맞는 상황(rowcount false). */
		boolean loseExpandRace = false;
		long nextId = 1;

		private final Supplier<List<String>> enrichedCodes;

		InMemoryBrands(Supplier<List<String>> enrichedCodes) {
			super(null);
			this.enrichedCodes = enrichedCodes;
		}

		@Override
		public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths,
				boolean ownRequest) {
			BrandRow existing = rows.get(username);
			long id = existing != null ? existing.id() : nextId++;
			int months = existing != null ? Math.max(existing.collectionMonths(), collectionMonths) : collectionMonths;
			rows.put(username, new BrandRow(id, username, profile.userId(), BrandStatus.ACTIVE, null, months,
					ownRequest));
			return id;
		}

		@Override
		public void setHasOwnLink(String username, boolean hasOwnLink) {
			rows.computeIfPresent(username, (u, r) -> new BrandRow(r.id(), r.username(), r.igUserId(), r.status(),
					r.lastSweptOn(), r.collectionMonths(), hasOwnLink));
		}

		/** 실 SQL 의미와 등가 — GREATEST + "collection_months < months일 때만" 갱신하고 그 여부를 돌려준다. */
		@Override
		public boolean expandWindow(long brandId, int months) {
			expanded.add(brandId);
			BrandRow row = rows.values().stream().filter(r -> r.id() == brandId).findFirst().orElseThrow();
			if (loseExpandRace || months <= row.collectionMonths()) {
				return false;
			}
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), null, months, r.hasOwnLink())
					: r);
			return true;
		}

		/** 실 SQL 의미와 등가 — 창만 GREATEST로 올리고 수집 상태(lastSweptOn)는 건드리지 않는다. */
		@Override
		public boolean raiseWindowCapped(long brandId, int months, Instant coveredUntilFallback) {
			cappedRaises.add(new CappedRaise(brandId, months, coveredUntilFallback));
			BrandRow row = rows.values().stream().filter(r -> r.id() == brandId).findFirst().orElseThrow();
			if (months <= row.collectionMonths()) {
				return false;
			}
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), r.lastSweptOn(), months,
							r.hasOwnLink())
					: r);
			return true;
		}

		@Override
		public void markBackfillError(long brandId, String message) {
			backfillErrors.put(brandId, message);
		}

		@Override
		public Optional<BrandRow> findByUsername(String username) {
			return Optional.ofNullable(rows.get(username));
		}

		@Override
		public boolean close(String username) {
			BrandRow row = rows.get(username);
			if (row == null || row.status() != BrandStatus.ACTIVE) {
				return false;
			}
			rows.put(username, new BrandRow(row.id(), row.username(), row.igUserId(),
					BrandStatus.CLOSED, row.lastSweptOn(), row.collectionMonths(), row.hasOwnLink()));
			return true;
		}

		@Override
		public void touchSwept(long brandId, LocalDate on) {
			touched.add(brandId);
			enrichedAtTouchSwept.add(enrichedCodes.get());
			// 실 UPDATE와 동일하게 행에도 반영한다 — 확장 백필이 "재조회한 행"(lastSweptOn 비워짐)으로
			// 도는지를 스텁 행이 stale인 채로는 구분할 수 없다.
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), on, r.collectionMonths(),
							r.hasOwnLink())
					: r);
		}

		@Override
		public void markServing(long brandId) {
			served.add(brandId);
			enrichedAtServingMark.add(enrichedCodes.get());
		}
	}

	private static final class StubCollect extends BrandCollectService {
		final List<String> coreSwept = new ArrayList<>();
		/** sweepCore가 실제로 받은 행 — 확장 백필이 stale 행이 아닌 재조회 행으로 도는지 판별용. */
		final List<BrandRow> coreRows = new ArrayList<>();
		final List<String> enriched = new CopyOnWriteArrayList<>();
		final Set<String> failing = new HashSet<>();
		final Set<String> enrichFailing = new HashSet<>();
		/** 열거가 넘길 페이지들 — 콜백은 페이지마다 그 페이지분만 받는다(누적 아님). 기본은 빈 1페이지. */
		List<List<PostInfo>> pages = List.of(List.of());
		boolean failAfterFirstPage = false;       // 첫 페이지 콜백 후 core 실패 주입
		/** 보강 지연 — 페이지 태스크 완료를 안 기다리는 회귀(touchSwept 선행)를 결정적으로 드러낸다. */
		Duration enrichDelay = Duration.ZERO;
		final List<List<String>> enrichedPosts = new CopyOnWriteArrayList<>();
		private List<String> callOrder = new CopyOnWriteArrayList<>();

		StubCollect() {
			super(null, null, null, null, null, null, null, null, null, null, null, null,
					2000, 10000, 3, 30, true);
		}

		/** 호출 순서 검증용 — 다른 스텁과 같은 리스트를 공유시켜 인터리빙을 관찰한다. */
		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		/** 지금까지 보강이 끝난 게시물 코드 전부 — markServing·touchSwept 시점 스냅샷용. */
		List<String> enrichedCodes() {
			return enrichedPosts.stream().flatMap(List::stream).toList();
		}

		@Override
		public List<PostInfo> sweepCore(BrandRow brand, java.util.function.Consumer<List<PostInfo>> onPageCollected) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("백필 실패 주입");
			}
			coreSwept.add(brand.username());
			coreRows.add(brand);
			// 실코드의 페이지 콜백 재현(Task 4 계약) — 페이지마다 1회, 그 페이지분만 넘긴다.
			List<PostInfo> all = new ArrayList<>();
			for (List<PostInfo> page : pages) {
				onPageCollected.accept(page);
				all.addAll(page);
				if (failAfterFirstPage) {
					throw new IllegalStateException("첫 페이지 뒤 실패 주입");
				}
			}
			return all;   // 반환은 전체 누적분(실코드와 동일) — 새 배선은 이걸 쓰지 않는다.
		}

		/** BrandRegistrationService.runBackfillSafely는 사용자 트리거 전용 진입점(사용자 트리거 비동기
		 * 도입 시점 토글, 2026-09)을 부른다 — 이 스텁은 라우팅 자체를 검증하지 않으므로 기존
		 * sweepCore 오버라이드로 그대로 위임한다(어느 InstagramSource 빈을 타는지는
		 * HikerConfig·BrandCollectService 자체 테스트가 검증한다). */
		@Override
		public List<PostInfo> sweepCoreUserTriggered(BrandRow brand,
				java.util.function.Consumer<List<PostInfo>> onPageCollected) {
			return sweepCore(brand, onPageCollected);
		}

		/**
		 * 3-인자(onVisible 훅) 버전만 오버라이드한다 — 2-인자 {@code enrich(brand, posts)}는 실
		 * {@code BrandCollectService}의 위임(onVisible=null)을 그대로 쓰므로, 가상 디스패치로 결국
		 * 이 메서드로 온다. onVisible은 <b>보강 지연(댓글·판정 대역) 전에</b> 부른다 — 실 코드에서
		 * markEnriched 직후·comments/adJudge 시작 전에 발화하는 지점을 재현한다(2026-08-18 계정
		 * 게이트 단축). enrichedPosts/enriched 적재는 지연 뒤라 "그 페이지가 아직 완주 전"인 채로
		 * onVisible이 뜨는 것을 markServing 시점 스냅샷(enrichedAtServingMark)이 관측한다.
		 */
		@Override
		public void enrich(BrandRow brand, List<PostInfo> posts, Runnable onVisible) {
			if (enrichFailing.contains(brand.username())) {
				if (onVisible != null) {
					onVisible.run();   // markEnriched와 같은 finally 보장 재현 — 하드 실패에도 발화
				}
				throw new IllegalStateException("보강 실패 주입");
			}
			if (onVisible != null) {
				onVisible.run();
			}
			sleep(enrichDelay);
			enriched.add(brand.username());
			enrichedPosts.add(posts.stream().map(PostInfo::shortCode).toList());
			callOrder.add("enrich");
		}

		/** runEnrichSafely도 사용자 트리거 전용 진입점을 부른다 — 위 sweepCoreUserTriggered와 같은
		 * 이유로 기존 enrich(3-인자) 오버라이드에 위임한다. */
		@Override
		public void enrichUserTriggered(BrandRow brand, List<PostInfo> posts, Runnable onVisible) {
			enrich(brand, posts, onVisible);
		}

		private static void sleep(Duration delay) {
			if (delay.isZero()) {
				return;
			}
			try {
				Thread.sleep(delay.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static final class StubHashtagCollect extends BrandHashtagCollectService {
		final List<String> swept = new CopyOnWriteArrayList<>();
		boolean failing;
		private List<String> callOrder = new CopyOnWriteArrayList<>();

		StubHashtagCollect() {
			super(null, null, null, null, null, null, 0, 0);
		}

		/** 호출 순서 검증용 — 다른 스텁과 같은 리스트를 공유시켜 인터리빙을 관찰한다. */
		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		@Override
		public void sweep(BrandRow brand) {
			if (failing) {
				throw new IllegalStateException("해시태그 백필 실패 주입");
			}
			swept.add(brand.username());
			callOrder.add("hashtag");
		}

		/** BrandRegistrationService.triggerHashtagSweep은 사용자 트리거 전용 진입점(2026-09 도입 시점
		 * 토글)을 부른다 — 위 StubCollect와 같은 이유로 기존 sweep 오버라이드에 위임한다. */
		@Override
		public void sweepUserTriggered(BrandRow brand) {
			sweep(brand);
		}
	}

	private static final class RecordingCallCounts extends BrandCallCountRepository {
		final Map<Long, Long> byBrand = new HashMap<>();

		RecordingCallCounts() {
			super(null);
		}

		@Override
		public void add(long brandId, LocalDate calledOn, long delta) {
			byBrand.merge(brandId, delta, Long::sum);
		}
	}

	/** 확장 스킵 판정 입력(스펙 §7-2) — limit번째 최신 태그 행의 taken_at만 스텁한다. */
	private static final class StubTaggedPosts extends TaggedPostRepository {
		/** null = 태그 행이 상한 미만(컷 안 걸림). */
		Instant nthNewest;
		/** 마지막 호출의 n — 상한이 그대로 넘어갔는지 본다. */
		Integer askedN;

		StubTaggedPosts() {
			super(null);
		}

		@Override
		public Optional<Instant> nthNewestTagTakenAt(long brandId, int n) {
			askedN = n;
			return Optional.ofNullable(nthNewest);
		}
	}

	private final List<String> hikerCalls = new ArrayList<>();
	private final StubCollect collect = new StubCollect();
	private final InMemoryBrands brands = new InMemoryBrands(() -> collect.enrichedCodes());
	private final RecordingCallCounts callCounts = new RecordingCallCounts();
	private final StubHashtagCollect hashtagCollect = new StubHashtagCollect();
	private final StubTaggedPosts taggedPosts = new StubTaggedPosts();
	/** 실 기본값(application.yml)과 같은 상한 — 개별 테스트가 필요하면 바꾼다. */
	private int collectionPostLimit = 2000;

	/** enrich executor에 제출된 태스크 — 개수만 본다(실행은 아래 풀이 실제로 한다). */
	private final List<Runnable> enrichSubmissions = new CopyOnWriteArrayList<>();
	/** hashtagSweep executor에 제출된 태스크 — 08-18 분리 후 enrich와 별개 큐임을 증명하는 용도. */
	private final List<Runnable> hashtagSweepSubmissions = new CopyOnWriteArrayList<>();
	/** 태스크 밖으로 샌 예외 — 격리 규칙(보강·해시태그 실패는 태스크 안에서 삼킨다) 위반 감시. */
	private final List<Throwable> escaped = new CopyOnWriteArrayList<>();
	private final ExecutorService enrichPool = Executors.newSingleThreadExecutor();
	private final Executor enrich = task -> {
		enrichSubmissions.add(task);
		enrichPool.execute(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				// 풀 워커에서 새는 예외는 기본 uncaught 핸들러로 조용히 사라지고 워커만 교체된다
				// (= 테스트가 그냥 통과한다) — 여기서 붙잡아 tearDown에서 단언한다. execute로 제출되는
				// 해시태그 꼬리는 CompletableFuture에 안 감겨서 이 그물 말고는 검증 수단이 없다.
				escaped.add(t);
			}
		});
	};
	/** hashtagSweep executor 스텁 — enrich와 별개 풀이라 서로 다른 큐로 제출됐는지를 구분해서 잡는다. */
	private final ExecutorService hashtagSweepPool = Executors.newSingleThreadExecutor();
	private final Executor hashtagSweep = task -> {
		hashtagSweepSubmissions.add(task);
		hashtagSweepPool.execute(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				escaped.add(t);
			}
		});
	};

	@AfterEach
	void tearDown() {
		awaitEnrich();          // 남은 태스크까지 돌린 뒤에 봐야 그물이 전 태스크를 덮는다
		awaitHashtagSweep();
		assertThat(escaped).isEmpty();
		enrichPool.shutdownNow();
		hashtagSweepPool.shutdownNow();
	}

	/** enrich 큐가 빌 때까지 — 단일 스레드 FIFO라 마커 태스크 완료 = 앞서 제출된 태스크 전부 완료. */
	private void awaitEnrich() {
		try {
			enrichPool.submit(() -> { }).get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("enrich 큐 대기 중 인터럽트", e);
		} catch (Exception e) {
			throw new IllegalStateException("enrich 큐 대기 실패", e);
		}
	}

	/** hashtagSweep 큐가 빌 때까지 — awaitEnrich와 같은 마커 패턴. */
	private void awaitHashtagSweep() {
		try {
			hashtagSweepPool.submit(() -> { }).get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("hashtagSweep 큐 대기 중 인터럽트", e);
		} catch (Exception e) {
			throw new IllegalStateException("hashtagSweep 큐 대기 실패", e);
		}
	}

	private static PostInfo post(String code) {
		return new PostInfo(code, "author", null, null, "1", "REELS", null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				false, false, false);
	}

	/** 2페이지 열거 — 페이지 단위 배선(첫 배치 ready·전 페이지 완주)을 관찰하는 기본 시나리오. */
	private void twoPages() {
		collect.pages = List.of(List.of(post("P1_A"), post("P1_B")), List.of(post("P2_A"), post("P2_B")));
	}

	private BrandRegistrationService service() {
		HikerBackend hiker = new HikerBackend(path -> {
			hikerCalls.add(path);
			return PROFILE_JSON;
		});
		return new BrandRegistrationService(hiker, brands, collect, callCounts,
				hashtagCollect, taggedPosts, collectionPostLimit,
				Runnable::run, enrich, hashtagSweep);
	}

	@Test
	void 등록은_프로필_1콜_동기_후_백필을_예약한다() {
		var result = service().register("brandx");

		assertThat(result.replayed()).isFalse();
		assertThat(result.followers()).isEqualTo(1234L);
		assertThat(hikerCalls).hasSize(1);
		assertThat(hikerCalls.getFirst()).startsWith("/v2/user/by/username");
		assertThat(collect.coreSwept).containsExactly("brandx");   // 동기 executor — 백필 즉시 실행
		assertThat(brands.touched).containsExactly(result.brandId());
		// 등록 검증 프로필 1콜의 사후 계상(어드민 크롤링 비용) — 콜 시점엔 brand_id가 없어 등록 직후 +1.
		assertThat(callCounts.byBrand).containsExactly(Map.entry(result.brandId(), 1L));
	}

	/**
	 * 첫 페이지의 <b>게시자 보강 직후</b>(댓글 수집·광고 판정 전)에 ready를 연다(2026-08-18 계정
	 * 게이트 단축 — 구 "첫 페이지 전체 보강 완료" 기준 대체). markServing은 열거 완주도, 보강 전
	 * 빈 목록도 아니고, 첫 페이지의 onVisible 훅에서 딱 1회다.
	 */
	@Test
	void 첫_페이지_게시자_보강_직후에_markServing을_1회_부른다() {
		twoPages();
		collect.enrichDelay = Duration.ofMillis(50);   // 댓글·판정 대역 — 이보다 먼저 markServing이 떠야 한다

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.served).containsExactly(result.brandId());   // 페이지가 여러 장이어도 1회
		// markServing 시점엔 아직 어느 페이지도 "완주"(댓글·판정 대역 포함) 전이어야 한다 —
		// 완주분이 비어 있다는 것 자체가 "댓글·판정을 기다리지 않았다"는 증거다.
		assertThat(brands.enrichedAtServingMark).containsExactly(List.of());
	}

	/**
	 * 완주 시각(= 응답 collectionCompletedAt, FE 폴링 종료 조건)은 모든 페이지 보강이 끝난 뒤에
	 * 찍힌다 — 열거 완주 시점에 찍으면 FE가 미완성 목록을 최종본으로 알고 폴링을 멈춘다.
	 */
	@Test
	void 모든_페이지_보강이_끝난_뒤에_touchSwept한다() {
		twoPages();
		collect.enrichDelay = Duration.ofMillis(50);

		var result = service().register("brandx");

		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(brands.enrichedAtTouchSwept).singleElement().satisfies(codes ->
				assertThat(codes).containsExactlyInAnyOrder("P1_A", "P1_B", "P2_A", "P2_B"));
	}

	@Test
	void 페이지마다_그_페이지분만_보강한다() {
		twoPages();

		service().register("brandx");
		awaitEnrich();
		awaitHashtagSweep();

		// 콜백이 페이지분만 주므로 페이지끼리 겹치지 않는다 — 중복 필터가 필요 없다.
		assertThat(collect.enrichedPosts)
				.containsExactly(List.of("P1_A", "P1_B"), List.of("P2_A", "P2_B"));
		assertThat(enrichSubmissions).hasSize(2);                      // 페이지 2건 — 08-18부터 해시태그 꼬리는 별도 큐
		assertThat(hashtagSweepSubmissions).hasSize(1);                // 해시태그 꼬리 1은 hashtagSweep 큐로
		assertThat(hashtagCollect.swept).containsExactly("brandx");    // 해시태그는 완주 뒤 꼬리
	}

	@Test
	void 태그가_없어도_markServing으로_ready를_연다() {
		// pages 기본값 = 빈 1페이지(태그 0건 브랜드) — 여기서 ready를 못 열면 collecting에 영구히 갇힌다.
		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.served).containsExactly(result.brandId());
		assertThat(brands.touched).containsExactly(result.brandId());
	}

	@Test
	void 첫_페이지_뒤_core_실패도_touchSwept_없이_backfill_error를_남긴다() {
		twoPages();
		collect.failAfterFirstPage = true;

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.served).containsExactly(result.brandId());        // 정산된 첫 페이지는 서빙 유지
		assertThat(brands.touched).isEmpty();                               // 완주 아님 — 다음 스윕 백스톱
		assertThat(brands.backfillErrors).containsKey(result.brandId());
		assertThat(collect.enrichedPosts).containsExactly(List.of("P1_A", "P1_B"));   // 2페이지는 없다
		assertThat(hashtagCollect.swept).isEmpty();                         // 완주 꼬리도 없다
	}

	@Test
	void 보강_실패는_ready를_되돌리지도_backfill_error를_남기지도_않는다() {
		// 보강 실패는 태스크 안에서 삼켜진다 — 밖으로 새면 tearDown의 escaped 단언이 잡는다.
		collect.enrichFailing.add("brandx");

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.served).containsExactly(result.brandId());
		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(brands.backfillErrors).doesNotContainKey(result.brandId());
	}

	@Test
	void core_실패면_보강을_예약하지_않는다() {
		collect.failing.add("brandx");

		service().register("brandx");

		assertThat(enrichSubmissions).isEmpty();   // 게시물 없이 보강만 도는 낭비 방지
	}

	// ---------- has_own_link 초기화·승격(2026-08-19 경쟁사 판정 제거 설계 §2) ----------

	@Test
	void 신규_등록_own은_has_own_link_true다() {
		var result = service().register("brandx", null, null, "own");

		assertThat(brands.rows.get("brandx").hasOwnLink()).isTrue();
		assertThat(result.replayed()).isFalse();
	}

	@Test
	void 신규_등록_competitor는_has_own_link_false다() {
		service().register("brandx", null, null, "competitor");

		assertThat(brands.rows.get("brandx").hasOwnLink()).isFalse();
	}

	@Test
	void accountType_생략은_own과_동치다() {
		service().register("brandx");   // accountType 미상 — null

		assertThat(brands.rows.get("brandx").hasOwnLink()).isTrue();
	}

	@Test
	void competitor로_등록된_브랜드를_own으로_재등록하면_승격된다() {
		var service = service();
		service.register("brandx", null, null, "competitor");
		assertThat(brands.rows.get("brandx").hasOwnLink()).isFalse();

		service.register("brandx", null, null, "own");

		assertThat(brands.rows.get("brandx").hasOwnLink()).isTrue();
	}

	@Test
	void competitor로_등록된_브랜드를_competitor로_재등록해도_변경없다() {
		var service = service();
		service.register("brandx", null, null, "competitor");

		service.register("brandx", null, null, "competitor");

		assertThat(brands.rows.get("brandx").hasOwnLink()).isFalse();
	}

	/** own으로 이미 승격된 브랜드는 competitor 재등록에도 절대 내려가지 않는다(승격만, 강등 없음). */
	@Test
	void own인_브랜드를_competitor로_재등록해도_내려가지_않는다() {
		var service = service();
		service.register("brandx", null, null, "own");

		service.register("brandx", null, null, "competitor");

		assertThat(brands.rows.get("brandx").hasOwnLink()).isTrue();
	}

	@Test
	void 활성_브랜드_재등록은_replay다() {
		var service = service();
		var first = service.register("brandx");
		int callsAfterFirst = hikerCalls.size();

		var replayed = service.register("brandx");

		assertThat(replayed.replayed()).isTrue();
		assertThat(replayed.brandId()).isEqualTo(first.brandId());
		assertThat(hikerCalls).hasSize(callsAfterFirst);   // Hiker 콜 0 — 멱등 replay
		assertThat(callCounts.byBrand).containsExactly(Map.entry(first.brandId(), 1L));   // 콜 집계도 그대로
	}

	@Test
	void 더_큰_창_재등록은_확장이다_프로필_콜_없이_백필만_재예약() {
		var first = service().register("brandx", null, 3);
		// 첫 백필이 완주해 lastSweptOn이 찍힌 상태 = 확장 시점의 stale 행. 이걸 그대로 백필에 넘기면
		// 옛 창(3개월)으로 돌아 확장이 조용히 무효가 된다 — 아래 coreRows 단언이 그 회귀를 잡는다.
		assertThat(brands.rows.get("brandx").lastSweptOn()).isNotNull();
		hikerCalls.clear();
		collect.coreSwept.clear();
		collect.coreRows.clear();

		var result = service().register("brandx", null, 12);

		assertThat(result.replayed()).isTrue();
		assertThat(hikerCalls).isEmpty();                            // replay — Hiker 콜 0 유지
		assertThat(brands.expanded).containsExactly(first.brandId());
		assertThat(collect.coreSwept).containsExactly("brandx");     // 동기 executor — 백필 즉시 재실행
		assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(12);
		// 확장 백필이 받은 건 expandWindow 후 재조회한 행이어야 한다 — 창 12 + lastSweptOn 비워짐.
		assertThat(collect.coreRows).singleElement().satisfies(row -> {
			assertThat(row.collectionMonths()).isEqualTo(12);
			assertThat(row.lastSweptOn()).isNull();
		});
	}

	/**
	 * 확장 스킵(스펙 §7-2) — 재백필의 컷(limit번째 최신 태그 행)이 기존 창 <b>안</b>에 떨어지면
	 * 확장 구간에는 한 건도 도달하지 못하므로 열거를 시작하지 않는다. 창·커버리지 마킹만 하고
	 * 수집 상태(lastSweptOn)는 불변이다.
	 */
	@Test
	void 컷이_기존_창_안에_떨어지면_확장은_백필_없이_창만_올린다() {
		var first = service().register("brandx", null, 3);
		collect.coreSwept.clear();
		// limit번째 최신 행이 1개월 전 = 기존 창(3개월) 안 → 재백필해도 3~12개월 구간엔 못 간다.
		Instant predictedCut = ZonedDateTime.now(KST).minusMonths(1).toInstant();
		taggedPosts.nthNewest = predictedCut;

		var result = service().register("brandx", null, 12);

		assertThat(result.replayed()).isTrue();
		assertThat(collect.coreSwept).isEmpty();       // 백필 미제출(~96콜 절약)
		assertThat(brands.expanded).isEmpty();         // 수집 상태를 리셋하는 expandWindow는 안 탄다
		assertThat(taggedPosts.askedN).isEqualTo(collectionPostLimit);   // n = 상한
		assertThat(brands.cappedRaises).singleElement().satisfies(raise -> {
			assertThat(raise.brandId()).isEqualTo(first.brandId());
			assertThat(raise.months()).isEqualTo(12);
			// 폴백 = 예측 컷 그 자체(근사 아닌 실제 도달 깊이) — §7-4 클램프 입력이기도 하다.
			assertThat(raise.coveredUntilFallback()).isEqualTo(predictedCut);
		});
		assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(12);
	}

	@Test
	void 태그_행이_상한_미만이면_확장은_기존_경로다() {
		var first = service().register("brandx", null, 3);
		collect.coreSwept.clear();
		taggedPosts.nthNewest = null;   // limit번째 행 없음 = 컷이 안 걸린다

		service().register("brandx", null, 12);

		assertThat(brands.cappedRaises).isEmpty();
		assertThat(brands.expanded).containsExactly(first.brandId());
		assertThat(collect.coreSwept).containsExactly("brandx");   // 기존 재백필 경로 그대로
	}

	/**
	 * 구 판정(생애 누적 행 수)의 오표기 회귀 가드 — 누적은 상한을 넘었어도 limit번째 행이 기존
	 * 창 <b>밖</b>이면 재백필은 컷 전에 창 컷에 먼저 닿는다(= 확장 구간에 실제로 도달한다).
	 * 여기서 스킵하면 도달 가능했던 구간이 capped 오표기 + §7-4 클램프로 영구 동결된다.
	 */
	@Test
	void 컷이_기존_창_밖이면_누적이_상한을_넘어도_확장한다() {
		var first = service().register("brandx", null, 3);
		collect.coreSwept.clear();
		// 창 밖 과거 행이 많은 브랜드(누적 2,400 / 창 안 900) — limit번째가 8개월 전이다.
		taggedPosts.nthNewest = ZonedDateTime.now(KST).minusMonths(8).toInstant();

		service().register("brandx", null, 12);

		assertThat(brands.cappedRaises).isEmpty();
		assertThat(brands.expanded).containsExactly(first.brandId());
		assertThat(collect.coreSwept).containsExactly("brandx");
	}

	@Test
	void 상한이_0_이하면_확장_스킵이_비활성이다() {
		collectionPostLimit = 0;   // 무제한 — 컷 자체가 없으니 스킵 판정도 없다
		var first = service().register("brandx", null, 3);
		collect.coreSwept.clear();
		taggedPosts.nthNewest = ZonedDateTime.now(KST).minusMonths(1).toInstant();

		service().register("brandx", null, 12);

		assertThat(taggedPosts.askedN).isNull();       // 조회조차 안 한다
		assertThat(brands.cappedRaises).isEmpty();
		assertThat(brands.expanded).containsExactly(first.brandId());
		assertThat(collect.coreSwept).containsExactly("brandx");
	}

	@Test
	void 확장이_경합에서_지면_백필을_재제출하지_않는다() {
		// 사전 게이트(in-memory)를 통과했지만 조건부 UPDATE가 0행 — 더 큰 창을 넣은 동시 요청이
		// 이미 이겼다는 뜻이고, 그쪽이 백필도 이미 제출했다. 여기서 또 제출하면 중복 열거다.
		var service = service();
		service.register("brandx", null, 3);
		collect.coreSwept.clear();
		brands.loseExpandRace = true;

		var result = service.register("brandx", null, 12);

		assertThat(result.replayed()).isTrue();
		assertThat(brands.expanded).containsExactly(result.brandId());   // 시도는 했다(사전 게이트 통과)
		assertThat(collect.coreSwept).isEmpty();                         // 재제출 없음
	}

	@Test
	void 같거나_작은_창_재등록은_순수_replay다() {
		service().register("brandx", null, 6);
		collect.coreSwept.clear();

		service().register("brandx", null, 6);
		service().register("brandx", null, 3);

		assertThat(brands.expanded).isEmpty();
		assertThat(collect.coreSwept).isEmpty();
		assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(6);   // 축소 무시
	}

	@Test
	void 값_공간_밖_collectionMonths는_거절한다() {
		assertThatThrownBy(() -> service().register("brandx", null, 2))
				.isInstanceOf(ValidationException.class);
		assertThat(hikerCalls).isEmpty();   // 검증은 Hiker 콜 도달 전
	}

	@Test
	void collectionMonths_생략은_12다() {
		service().register("brandx");
		assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(12);
	}

	/**
	 * 2026-08-28 태그 생성 권한 was 일원화 — monitoring은 더 이상 brand_hashtag에 아무것도 심지
	 * 않는다(구 계정명 태그 1종 자동 시드 제거). 유도 태그 push는 was가 링크 생성 시 일반 태그
	 * add로 담당한다({@code V1BrandAccountService#seedLedgerTagsSafely} 참조) — 여기서 검증할
	 * 수 있는 건 등록이 성공하고(brandId·followers 정상) 여전히 즉시 스윕 트리거는 유지된다는
	 * 사실뿐이다(스윕 자체가 태그 시드에 의존하지 않는다는 방증).
	 */
	@Test
	void 등록은_태그를_시드하지_않는다() {
		var result = service().register("cclime_official", "끌리메");
		awaitEnrich();
		awaitHashtagSweep();

		assertThat(result.replayed()).isFalse();
		// 태그 시드 경로 자체가 없다 — hashtagCollect.sweep은 (여기선 태그 0건이라도) 백필 꼬리로 여전히 돈다.
		assertThat(hashtagCollect.swept).containsExactly("cclime_official");
	}

	@Test
	void 활성_replay_재등록도_태그를_시드하지_않는다() {
		var service = service();
		var first = service.register("cclime_official");
		awaitEnrich();
		awaitHashtagSweep();
		hashtagCollect.swept.clear();

		var replayed = service.register("cclime_official");   // replay — 시드 없이 스윕만 트리거
		awaitHashtagSweep();

		assertThat(replayed.replayed()).isTrue();
		assertThat(replayed.brandId()).isEqualTo(first.brandId());
		assertThat(hashtagCollect.swept).containsExactly("cclime_official");
	}

	/**
	 * replay는 백필이 돌지 않아(hiker 콜 0) 예전엔 재등록 시점의 즉시 조회가 없었다 — 2026-08-17부터
	 * 태그 시드 직후 해시태그 스윕도 트리거한다.
	 */
	@Test
	void 활성_replay_재등록도_즉시_해시태그_스윕을_트리거한다() {
		var service = service();
		service.register("brandx");     // 최초 등록 — 백필 꼬리가 이미 한 번 스윕
		awaitEnrich();
		awaitHashtagSweep();
		hashtagCollect.swept.clear();

		service.register("brandx");     // replay
		awaitHashtagSweep();

		assertThat(hashtagCollect.swept).containsExactly("brandx");
	}

	/** 백필 완주(lastSweptOn 있음) 브랜드는 태그 추가 즉시 스윕이 정상 트리거된다. */
	@Test
	void 태그가_있고_백필이_완주된_브랜드는_즉시_스윕을_트리거한다() {
		var result = service().register("brandx");
		awaitEnrich();
		awaitHashtagSweep();
		hashtagCollect.swept.clear();

		assertThat(brands.rows.get("brandx").lastSweptOn()).isNotNull();   // 전제 — 완주 상태
		service().triggerHashtagSweepIfNonEmpty(brands.rows.get("brandx"), List.of("cclime"));
		awaitHashtagSweep();

		assertThat(hashtagCollect.swept).containsExactly("brandx");
	}

	@Test
	void 태그가_비어있으면_스윕을_트리거하지_않는다() {
		service().register("brandx");
		awaitEnrich();
		awaitHashtagSweep();
		hashtagCollect.swept.clear();

		service().triggerHashtagSweepIfNonEmpty(brands.rows.get("brandx"), List.of());
		awaitHashtagSweep();

		assertThat(hashtagCollect.swept).isEmpty();
	}

	/**
	 * 초기 백필 미완(lastSweptOn null) 브랜드는 태그 추가 즉시 스윕을 스킵한다(2026-08-28) — was의
	 * 신규 등록 태그 push가 백필과 동시에 도착해도 전역 콜 예산을 더 경합하지 않는다. 백필 꼬리의
	 * triggerHashtagSweep이 곧 이 태그까지 커버한다({@link BrandRegistrationService
	 * #triggerHashtagSweepIfNonEmpty} 참조).
	 */
	@Test
	void 초기_백필_미완_브랜드는_태그가_있어도_즉시_스윕을_스킵한다() {
		brands.rows.put("brandx", new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true));

		service().triggerHashtagSweepIfNonEmpty(brands.rows.get("brandx"), List.of("cclime"));
		awaitHashtagSweep();

		assertThat(hashtagCollect.swept).isEmpty();
		assertThat(hashtagSweepSubmissions).isEmpty();   // executor에 아예 제출되지 않는다
	}

	@Test
	void 백필은_enrich_후_해시태그_스윕을_돌린다() {
		List<String> order = new CopyOnWriteArrayList<>();
		collect.useSharedCallOrder(order);
		hashtagCollect.useSharedCallOrder(order);

		service().register("brandx");
		awaitEnrich();
		awaitHashtagSweep();

		assertThat(order).containsExactly("enrich", "hashtag");
		assertThat(hashtagCollect.swept).containsExactly("brandx");
	}

	@Test
	void 해시태그_백필_실패는_등록_보강을_깨지_않는다() {
		// 해시태그 꼬리는 execute 제출이라 CompletableFuture 그물이 없다 — 실패가 태스크 안에서
		// 삼켜지는지는 tearDown의 escaped 단언만이 검증한다(밖으로 새면 워커만 조용히 교체된다).
		hashtagCollect.failing = true;

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(brands.backfillErrors).doesNotContainKey(result.brandId());   // core는 이미 성공
		assertThat(collect.enriched).containsExactly("brandx");   // 보강은 정상 실행됨
	}

	@Test
	void 백필_실패는_등록을_실패시키지_않는다() {
		collect.failing.add("brandx");

		var result = service().register("brandx");

		assertThat(result.replayed()).isFalse();           // 등록 자체는 성공
		assertThat(brands.touched).isEmpty();              // 백스톱 성립 — last_swept_on 미갱신
	}

	@Test
	void 백필_실패는_backfill_error로_기록된다() {
		collect.failing.add("brandx");

		var result = service().register("brandx");

		// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 사용자에게 보일 문구라 내부 예외를 안 싣는다.
		assertThat(brands.backfillErrors.get(result.brandId()))
				.isEqualTo("초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
	}

	@Test
	void 백필_성공이면_오류를_기록하지_않는다() {
		var result = service().register("brandx");

		assertThat(brands.backfillErrors).doesNotContainKey(result.brandId());
	}

	@Test
	void 탈퇴는_상태별_결과를_구분한다() {
		var service = service();
		service.register("brandx");

		assertThat(service.deregister("brandx"))
				.isEqualTo(BrandRegistrationService.DeregisterOutcome.CLOSED);
		assertThat(service.deregister("brandx"))
				.isEqualTo(BrandRegistrationService.DeregisterOutcome.ALREADY_CLOSED);
		assertThat(service.deregister("unknown"))
				.isEqualTo(BrandRegistrationService.DeregisterOutcome.NOT_FOUND);
	}

	@Test
	void username_공백은_ValidationException() {
		assertThatThrownBy(() -> service().register("  "))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service().register(null))
				.isInstanceOf(ValidationException.class);
	}
}
