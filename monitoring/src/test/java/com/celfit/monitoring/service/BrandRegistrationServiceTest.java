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
import org.junit.jupiter.api.Test;

/**
 * 브랜드 등록/탈퇴 — 동기 구간은 프로필 1콜뿐이고 백필은 executor로 넘어간다(테스트는 동기
 * executor(Runnable::run)로 즉시 실행시켜 검증). 백필 실패는 등록을 실패시키지 않는다
 * (last_tracked_on null 유지 → 다음 스윕 백스톱).
 */
class BrandRegistrationServiceTest {

	private static final String PROFILE_JSON = """
			{"user":{"pk":111,"username":"brandx","full_name":"브랜드","profile_pic_url":"https://p",
			"biography":"소개","follower_count":1234,"following_count":10,"media_count":5,
			"is_private":false}}""";

	private static final class InMemoryBrands extends BrandRepository {
		final Map<String, BrandRow> rows = new HashMap<>();
		final List<Long> touched = new ArrayList<>();
		final List<Long> served = new ArrayList<>();
		final Map<Long, String> backfillErrors = new HashMap<>();
		final List<Long> expanded = new ArrayList<>();
		long nextId = 1;

		InMemoryBrands() {
			super(null);
		}

		@Override
		public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths) {
			BrandRow existing = rows.get(username);
			long id = existing != null ? existing.id() : nextId++;
			int months = existing != null ? Math.max(existing.collectionMonths(), collectionMonths) : collectionMonths;
			rows.put(username, new BrandRow(id, username, profile.userId(), BrandStatus.ACTIVE, null, months));
			return id;
		}

		@Override
		public void expandWindow(long brandId, int months) {
			expanded.add(brandId);
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), null, months) : r);
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
			// 실 UPDATE와 동일하게 행에도 반영한다 — 확장 백필이 "재조회한 행"(lastSweptOn 비워짐)으로
			// 도는지를 스텁 행이 stale인 채로는 구분할 수 없다.
			rows.replaceAll((u, r) -> r.id() == brandId
					? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), on, r.collectionMonths()) : r);
		}

		@Override
		public void markServing(long brandId) {
			served.add(brandId);
		}
	}

	private static final class StubCollect extends BrandCollectService {
		final List<String> coreSwept = new ArrayList<>();
		/** sweepCore가 실제로 받은 행 — 확장 백필이 stale 행이 아닌 재조회 행으로 도는지 판별용. */
		final List<BrandRow> coreRows = new ArrayList<>();
		final List<String> enriched = new ArrayList<>();
		final Set<String> failing = new HashSet<>();
		final Set<String> enrichFailing = new HashSet<>();
		List<PostInfo> earlyBatch = List.of();   // 서빙 콜백에 넘길 누적분(기본: 없음)
		List<PostInfo> fullResult = List.of();   // 완주 반환분
		boolean failAfterServing = false;        // 콜백 후 실패 시나리오 주입
		final List<List<String>> enrichedPosts = new ArrayList<>();
		private List<String> callOrder = new ArrayList<>();

		StubCollect() {
			super(null, null, null, null, null, null, null, null, 30, 2000, 3, 30);
		}

		/** 호출 순서 검증용 — 다른 스텁과 같은 리스트를 공유시켜 인터리빙을 관찰한다. */
		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		@Override
		public List<PostInfo> sweepCore(BrandRow brand, java.util.function.Consumer<List<PostInfo>> onServingCovered) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("백필 실패 주입");
			}
			coreSwept.add(brand.username());
			coreRows.add(brand);
			onServingCovered.accept(earlyBatch);   // 실코드의 "정확히 1회" 계약 재현
			if (failAfterServing) {
				throw new IllegalStateException("서빙 후 실패 주입");
			}
			return fullResult;
		}

		@Override
		public void enrich(BrandRow brand, List<PostInfo> posts) {
			if (enrichFailing.contains(brand.username())) {
				throw new IllegalStateException("보강 실패 주입");
			}
			enriched.add(brand.username());
			enrichedPosts.add(posts.stream().map(PostInfo::shortCode).toList());
			callOrder.add("enrich");
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
		final List<String> swept = new ArrayList<>();
		boolean failing;
		private List<String> callOrder = new ArrayList<>();

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
	private final List<Runnable> enrichQueue = new ArrayList<>();
	private final InMemoryBrands brands = new InMemoryBrands();
	private final StubCollect collect = new StubCollect();
	private final RecordingCallCounts callCounts = new RecordingCallCounts();
	private final StubHashtags hashtags = new StubHashtags();
	private final StubHashtagCollect hashtagCollect = new StubHashtagCollect();

	private static PostInfo post(String code) {
		return new PostInfo(code, "author", null, null, "1", "REELS", null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				false, false, false);
	}

	private BrandRegistrationService service() {
		HikerClient hiker = new HikerClient(path -> {
			hikerCalls.add(path);
			return PROFILE_JSON;
		});
		return new BrandRegistrationService(hiker, brands, collect, callCounts,
				hashtags, hashtagCollect, Runnable::run, enrichQueue::add);
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

	@Test
	void core_완료_즉시_ready를_찍고_보강은_전용_큐로_넘긴다() {
		var result = service().register("brandx");

		// core만 끝난 시점 — 보강(게시자·댓글)이 실행되기 전인데 ready(touchSwept)는 이미 찍혀 있다.
		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(collect.enriched).isEmpty();
		assertThat(enrichQueue).hasSize(1);

		enrichQueue.getFirst().run();
		assertThat(collect.enriched).containsExactly("brandx");
	}

	@Test
	void 서빙_콜백은_markServing과_선행_보강을_수행하고_잔여만_재보강한다() {
		collect.earlyBatch = List.of(post("A"), post("B"));
		collect.fullResult = List.of(post("A"), post("B"), post("C"));

		var result = service().register("brandx");

		assertThat(brands.served).containsExactly(result.brandId());     // 조기 ready
		assertThat(brands.touched).containsExactly(result.brandId());    // 완주 touchSwept도 그대로
		assertThat(enrichQueue).hasSize(2);                              // 선행 + 잔여
		enrichQueue.forEach(Runnable::run);
		assertThat(collect.enrichedPosts)
				.containsExactly(List.of("A", "B"), List.of("C"));       // 잔여는 선행분 제외
		assertThat(hashtagCollect.swept).containsExactly("brandx");      // 해시태그는 잔여 태스크 꼬리
	}

	@Test
	void 선행분이_비면_선행_보강_태스크를_만들지_않는다() {
		// earlyBatch 기본값 List.of() — 태그가 얕은 브랜드의 헛 태스크 방지.
		var result = service().register("brandx");

		assertThat(brands.served).containsExactly(result.brandId());   // 서빙 마크는 목록이 비어도 정당
		assertThat(enrichQueue).hasSize(1);                            // 잔여(완주) 태스크만
	}

	@Test
	void 서빙_후_core_실패도_touchSwept_없이_backfill_error를_남긴다() {
		collect.failAfterServing = true;
		collect.earlyBatch = List.of(post("A"));

		var result = service().register("brandx");

		assertThat(brands.served).containsExactly(result.brandId());   // 이미 연 서빙은 유지(부분 데이터)
		assertThat(brands.touched).isEmpty();                          // 완주 아님 — 다음 스윕 백스톱
		assertThat(brands.backfillErrors).containsKey(result.brandId());
		assertThat(enrichQueue).hasSize(1);                            // 선행 보강만 제출됨(잔여 태스크 없음)
	}

	@Test
	void 보강_실패는_ready를_되돌리지도_backfill_error를_남기지도_않는다() {
		collect.enrichFailing.add("brandx");

		var result = service().register("brandx");
		enrichQueue.forEach(Runnable::run);   // 보강 실패는 태스크 안에서 삼켜진다 — 여기로 새면 실패

		assertThat(brands.touched).containsExactly(result.brandId());
		assertThat(brands.backfillErrors).doesNotContainKey(result.brandId());
	}

	@Test
	void core_실패면_보강을_예약하지_않는다() {
		collect.failing.add("brandx");

		service().register("brandx");

		assertThat(enrichQueue).isEmpty();   // 게시물 없이 보강만 도는 낭비 방지
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
		List<String> order = new ArrayList<>();
		collect.useSharedCallOrder(order);
		hashtagCollect.useSharedCallOrder(order);

		service().register("brandx");
		enrichQueue.getFirst().run();

		assertThat(order).containsExactly("enrich", "hashtag");
		assertThat(hashtagCollect.swept).containsExactly("brandx");
	}

	@Test
	void 해시태그_백필_실패는_등록_보강을_깨지_않는다() {
		hashtagCollect.failing = true;

		var result = service().register("brandx");
		enrichQueue.forEach(Runnable::run);   // 해시태그 백필 실패는 태스크 안에서 삼켜진다 — 여기로 새면 실패

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
		assertThat(brands.touched).isEmpty();              // 백스톱 성립 — last_tracked_on 미갱신
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
