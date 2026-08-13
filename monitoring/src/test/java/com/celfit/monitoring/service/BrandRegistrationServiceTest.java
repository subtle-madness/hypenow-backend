package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
		/** 동시 확장 경합 주입 — 더 큰 창이 이미 반영돼 조건부 UPDATE가 0행을 맞는 상황(rowcount false). */
		boolean loseExpandRace = false;
		long nextId = 1;

		private final Supplier<List<String>> enrichedCodes;

		InMemoryBrands(Supplier<List<String>> enrichedCodes) {
			super(null);
			this.enrichedCodes = enrichedCodes;
		}

		@Override
		public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths) {
			BrandRow existing = rows.get(username);
			long id = existing != null ? existing.id() : nextId++;
			int months = existing != null ? Math.max(existing.collectionMonths(), collectionMonths) : collectionMonths;
			rows.put(username, new BrandRow(id, username, profile.userId(), BrandStatus.ACTIVE, null, months));
			return id;
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
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), null, months) : r);
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
					BrandStatus.CLOSED, row.lastSweptOn(), row.collectionMonths()));
			return true;
		}

		@Override
		public void touchSwept(long brandId, LocalDate on) {
			touched.add(brandId);
			enrichedAtTouchSwept.add(enrichedCodes.get());
			// 실 UPDATE와 동일하게 행에도 반영한다 — 확장 백필이 "재조회한 행"(lastSweptOn 비워짐)으로
			// 도는지를 스텁 행이 stale인 채로는 구분할 수 없다.
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), on, r.collectionMonths()) : r);
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
			super(null, null, null, null, null, null, null, null, 2000, 3, 30);
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

		@Override
		public void enrich(BrandRow brand, List<PostInfo> posts) {
			if (enrichFailing.contains(brand.username())) {
				throw new IllegalStateException("보강 실패 주입");
			}
			sleep(enrichDelay);
			enriched.add(brand.username());
			enrichedPosts.add(posts.stream().map(PostInfo::shortCode).toList());
			callOrder.add("enrich");
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

	/** insertTags는 ON CONFLICT DO NOTHING이라 재현은 LinkedHashSet 유니온으로 — 재등록 순서 검증용. */
	private static final class StubHashtags extends BrandHashtagRepository {
		final Map<Long, LinkedHashSet<String>> tags = new HashMap<>();
		final Map<Long, String> exclusions = new HashMap<>();
		boolean failing;

		StubHashtags() {
			super(null);
		}

		@Override
		public void insertTags(long brandId, Collection<String> newTags) {
			if (failing) {
				throw new IllegalStateException("해시태그 시드 실패 주입");
			}
			tags.computeIfAbsent(brandId, k -> new LinkedHashSet<>()).addAll(newTags);
		}

		@Override
		public void insertDefaultExclusion(long brandId, String term) {
			exclusions.putIfAbsent(brandId, term);
		}
	}

	private static final class StubHashtagCollect extends BrandHashtagCollectService {
		final List<String> swept = new CopyOnWriteArrayList<>();
		boolean failing;
		private List<String> callOrder = new CopyOnWriteArrayList<>();

		StubHashtagCollect() {
			super(null, null, null, null, null, 0, 0);
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

	private final List<String> hikerCalls = new ArrayList<>();
	private final StubCollect collect = new StubCollect();
	private final InMemoryBrands brands = new InMemoryBrands(() -> collect.enrichedCodes());
	private final RecordingCallCounts callCounts = new RecordingCallCounts();
	private final StubHashtags hashtags = new StubHashtags();
	private final StubHashtagCollect hashtagCollect = new StubHashtagCollect();

	/** enrich executor에 제출된 태스크 — 개수만 본다(실행은 아래 풀이 실제로 한다). */
	private final List<Runnable> enrichSubmissions = new CopyOnWriteArrayList<>();
	private final ExecutorService enrichPool = Executors.newSingleThreadExecutor();
	private final Executor enrich = task -> {
		enrichSubmissions.add(task);
		enrichPool.execute(task);
	};

	@AfterEach
	void tearDown() {
		enrichPool.shutdownNow();
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
		HikerClient hiker = new HikerClient(path -> {
			hikerCalls.add(path);
			return PROFILE_JSON;
		});
		return new BrandRegistrationService(hiker, brands, collect, callCounts,
				hashtags, hashtagCollect, Runnable::run, enrich);
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
	 * 첫 페이지 배치의 보강이 끝나는 시점에 ready를 연다(2026-08-13 스펙 §2) — markServing은
	 * 열거 완주도, 보강 전 빈 목록도 아니고 첫 배치 완결 뒤 딱 1회다.
	 */
	@Test
	void 첫_페이지_배치_보강_후에_markServing을_1회_부른다() {
		twoPages();
		collect.enrichDelay = Duration.ofMillis(50);

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.served).containsExactly(result.brandId());   // 페이지가 여러 장이어도 1회
		// markServing 시점에 첫 페이지분 보강이 이미 끝나 있어야 한다.
		assertThat(brands.enrichedAtServingMark).containsExactly(List.of("P1_A", "P1_B"));
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

		// 콜백이 페이지분만 주므로 페이지끼리 겹치지 않는다 — 중복 필터가 필요 없다.
		assertThat(collect.enrichedPosts)
				.containsExactly(List.of("P1_A", "P1_B"), List.of("P2_A", "P2_B"));
		assertThat(enrichSubmissions).hasSize(3);                    // 페이지 2 + 해시태그 꼬리 1
		assertThat(hashtagCollect.swept).containsExactly("brandx");   // 해시태그는 완주 뒤 꼬리
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
		collect.enrichFailing.add("brandx");   // 보강 실패는 태스크 안에서 삼켜진다 — 밖으로 새면 실패

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

	@Test
	void 등록은_태그_3종과_기본_제외_문자열을_시드한다() {
		var result = service().register("cclime_official", "끌리메");

		assertThat(hashtags.tags.get(result.brandId()))
				.containsExactly("끌리메", "cclime", "cclime_official");
		assertThat(hashtags.exclusions.get(result.brandId())).isEqualTo("cclime");
	}

	@Test
	void 활성_replay_재등록도_태그를_유니온한다() {
		var service = service();
		var first = service.register("cclime_official", null);   // 대행사 선등록 — 브랜드명 미상
		assertThat(hashtags.tags.get(first.brandId())).containsExactly("cclime", "cclime_official");

		var replayed = service.register("cclime_official", "끌리메");   // 뒤늦게 brand 유형 유저가 연결

		assertThat(replayed.replayed()).isTrue();
		assertThat(hashtags.tags.get(first.brandId()))
				.containsExactly("cclime", "cclime_official", "끌리메");   // 유니온 — 기존 순서 보존 + 신규 추가
	}

	@Test
	void 백필은_enrich_후_해시태그_스윕을_돌린다() {
		List<String> order = new CopyOnWriteArrayList<>();
		collect.useSharedCallOrder(order);
		hashtagCollect.useSharedCallOrder(order);

		service().register("brandx");
		awaitEnrich();

		assertThat(order).containsExactly("enrich", "hashtag");
		assertThat(hashtagCollect.swept).containsExactly("brandx");
	}

	@Test
	void 해시태그_백필_실패는_등록_보강을_깨지_않는다() {
		hashtagCollect.failing = true;   // 해시태그 백필 실패는 태스크 안에서 삼켜진다 — 밖으로 새면 실패

		var result = service().register("brandx");
		awaitEnrich();

		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(brands.backfillErrors).doesNotContainKey(result.brandId());   // core는 이미 성공
		assertThat(collect.enriched).containsExactly("brandx");   // 보강은 정상 실행됨
	}

	@Test
	void 해시태그_시드_실패는_등록과_백필_예약을_깨지_않는다() {
		hashtags.failing = true;

		var result = service().register("brandx");   // seedHashtagsSafely가 던져도 여기서 새면 안 된다

		assertThat(result.replayed()).isFalse();                  // 등록 자체는 성공
		assertThat(collect.coreSwept).containsExactly("brandx");   // backfill.execute가 정상 호출·실행됨
		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(hashtags.tags).doesNotContainKey(result.brandId());   // 시드 자체는 실패해 미기록
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
