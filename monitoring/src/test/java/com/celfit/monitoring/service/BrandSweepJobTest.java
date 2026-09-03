package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.ad.AdDisclosureJudgeService;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.image.AuthorProfileImageArchiveJob;
import com.celfit.monitoring.image.BrandPostThumbnailArchiveJob;
import com.celfit.monitoring.image.BrandProfileImageArchiveJob;
import com.celfit.monitoring.image.HashtagPostAuthorImageArchiveJob;
import com.celfit.monitoring.image.HashtagPostThumbnailArchiveJob;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 브랜드 스윕 — 매일 전량(주기 판정 없음)·성공 시에만 last_swept_on 갱신·브랜드 단위 격리. */
class BrandSweepJobTest {

	private static final class StubArchive extends AuthorProfileImageArchiveJob {
		int runs;
		boolean failing;

		StubArchive() {
			super(null, null, null, "https://par.example/o/");
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	private static final class StubBrandArchive extends BrandProfileImageArchiveJob {
		int runs;
		boolean failing;

		StubBrandArchive() {
			super(null, null, null, "https://par.example/o/");
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	private static final class StubPostThumbArchive extends BrandPostThumbnailArchiveJob {
		int runs;
		boolean failing;

		StubPostThumbArchive() {
			super(null, null, null, "https://par.example/o/");
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	private static final class StubHashtagThumbArchive extends HashtagPostThumbnailArchiveJob {
		int runs;
		boolean failing;

		StubHashtagThumbArchive() {
			super(null, null, null, "https://par.example/o/");
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	private static final class StubHashtagAuthorArchive extends HashtagPostAuthorImageArchiveJob {
		int runs;
		boolean failing;

		StubHashtagAuthorArchive() {
			super(null, null, null, "https://par.example/o/");
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	/** 백필 스텁(2026-08-18 상한 제거 개정) — 호출 횟수·주입한 실패를 관측한다. */
	private static final class StubAdJudge extends AdDisclosureJudgeService {
		int backfillCalls;
		BackfillOutcome next = new BackfillOutcome(0, 0);
		RuntimeException failing;

		StubAdJudge() {
			super(null, null, null);
		}

		@Override
		public BackfillOutcome backfillUnjudged() {
			backfillCalls++;
			if (failing != null) {
				throw failing;
			}
			return next;
		}
	}

	private static final class StubBrands extends BrandRepository {
		List<BrandRow> active = List.of();
		final List<Long> touched = java.util.Collections.synchronizedList(new ArrayList<>());
		/** 계정 게이트 호출 관측(2026-08-18) — 야간 스윕은 markServing을 부르지 않는다는 회귀 방지. */
		final List<Long> served = new ArrayList<>();

		StubBrands() {
			super(null);
		}

		@Override
		public List<BrandRow> findActive() {
			return active;
		}

		@Override
		public void touchSwept(long brandId, LocalDate on) {
			touched.add(brandId);
		}

		@Override
		public void markServing(long brandId) {
			served.add(brandId);
		}
	}

	private static class StubCollect extends BrandCollectService {
		final List<String> swept = java.util.Collections.synchronizedList(new ArrayList<>());
		final Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

		StubCollect() {
			super(null, null, null, null, null, null, null, null, null, null, null, null,
					2000, 10000, 3, 30, true);
		}

		@Override
		public void sweep(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("스윕 실패 주입");
			}
			swept.add(brand.username());
		}
	}

	private static class StubDirectCollect extends BrandDirectCollectService {
		final List<String> swept = java.util.Collections.synchronizedList(new ArrayList<>());
		final Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();
		/** 잡이 넘긴 런 스코프 캐시 관측 — 브랜드마다 같은 인스턴스여야 중복 콜이 접힌다. */
		final Set<SweepPostCache> caches = java.util.concurrent.ConcurrentHashMap.newKeySet();

		StubDirectCollect() {
			super(null, null, null, null, null, null, null, null, 300, 2000);
		}

		@Override
		public void sweepUnenumerated(BrandRow brand, SweepPostCache cache) {
			caches.add(cache);
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("direct 스윕 실패 주입");
			}
			swept.add(brand.username());
		}
	}

	private static class StubHashtagCollect extends BrandHashtagCollectService {
		final List<String> swept = java.util.Collections.synchronizedList(new ArrayList<>());
		final Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

		StubHashtagCollect() {
			super(null, null, null, null, null, null, 0, 0);
		}

		@Override
		public void sweep(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("해시태그 스윕 실패 주입");
			}
			swept.add(brand.username());
		}
	}

	private static BrandRow brand(long id, String username) {
		return new BrandRow(id, username, String.valueOf(id), BrandStatus.ACTIVE, null, 12, true);
	}

	/**
	 * 신규 썸네일·작성자 이미지 아카이브 잡·direct 수집은 대부분의 테스트에서 관심 밖 —
	 * 성공 스텁으로 채운다(direct 스윕 자체를 검증하는 테스트는 직접 생성자를 쓴다). 광고 판정
	 * 백필도 마찬가지로 기본값(킬 스위치 on)의 성공 스텁으로 채운다.
	 */
	private static BrandSweepJob sweepJob(BrandRepository brands, BrandCollectService collect,
			BrandHashtagCollectService hashtagCollect, AuthorProfileImageArchiveJob archive,
			BrandProfileImageArchiveJob brandArchive) {
		return sweepJob(brands, collect, hashtagCollect, archive, brandArchive, new StubAdJudge(), true);
	}

	private static BrandSweepJob sweepJob(BrandRepository brands, BrandCollectService collect,
			BrandHashtagCollectService hashtagCollect, AuthorProfileImageArchiveJob archive,
			BrandProfileImageArchiveJob brandArchive, AdDisclosureJudgeService adJudge,
			boolean adDisclosureEnabled) {
		return sweepJob(brands, collect, new StubDirectCollect(), hashtagCollect, archive, brandArchive, adJudge,
				adDisclosureEnabled, 3);
	}

	/** 브랜드 루프 병렬도(N)를 직접 지정하는 조립 — 1이면 호출 스레드 직렬(킬스위치 경로). */
	private static BrandSweepJob sweepJob(BrandRepository brands, BrandCollectService collect,
			BrandDirectCollectService directCollect, BrandHashtagCollectService hashtagCollect,
			AuthorProfileImageArchiveJob archive, BrandProfileImageArchiveJob brandArchive,
			AdDisclosureJudgeService adJudge, boolean adDisclosureEnabled, int brandConcurrency) {
		return new BrandSweepJob(brands, collect, directCollect, hashtagCollect, archive, brandArchive,
				new StubPostThumbArchive(), new StubHashtagThumbArchive(), new StubHashtagAuthorArchive(),
				adJudge, sweepSettings(brandConcurrency), BRAND_WORKER, adDisclosureEnabled);
	}

	/** 브랜드 루프 워커 풀(운영 brandSweepExecutor 대역) — 데몬이라 종료 훅 불필요. */
	private static final java.util.concurrent.ExecutorService BRAND_WORKER = java.util.concurrent.Executors
			.newFixedThreadPool(3, r -> {
				Thread t = new Thread(r, "test-brand-sweep");
				t.setDaemon(true);
				return t;
			});

	/** app_setting 없이 코드 기본값(=인자)만 쓰는 병렬도 토글 스텁. */
	private static BrandSweepSettings sweepSettings(int brandConcurrency) {
		com.celfit.monitoring.store.AppSettingRepository empty =
				new com.celfit.monitoring.store.AppSettingRepository(null) {
					@Override
					public java.util.Optional<String> find(String key) {
						return java.util.Optional.empty();
					}
				};
		return new BrandSweepSettings(empty, brandConcurrency, 8, java.time.Clock.systemUTC(),
				java.time.Duration.ofSeconds(5));
	}

	@Test
	void 활성_브랜드_전부를_매일_전량_수집한다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		sweepJob(brands, collect, new StubHashtagCollect(), new StubArchive(), new StubBrandArchive()).run();

		assertThat(collect.swept).containsExactlyInAnyOrder("first", "second");
		assertThat(brands.touched).containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	void 실패_브랜드는_격리되고_last_swept_on을_갱신하지_않는다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		brands.active = List.of(brand(1, "first"), brand(2, "boom"), brand(3, "third"));

		sweepJob(brands, collect, new StubHashtagCollect(), new StubArchive(), new StubBrandArchive()).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.swept).containsExactlyInAnyOrder("first", "third");
		assertThat(brands.touched).containsExactlyInAnyOrder(1L, 3L);   // boom은 "준비 중" 유지 — 내일 백스톱
	}

	@Test
	void 스윕_완료_후_게시자_프로필_이미지_아카이브가_실행된다() {
		var brands = new StubBrands();
		var archive = new StubArchive();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), archive, new StubBrandArchive()).run();

		assertThat(archive.runs).isEqualTo(1);
	}

	@Test
	void 아카이브_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var archive = new StubArchive();
		archive.failing = true;
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), archive, new StubBrandArchive()).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	@Test
	void 스윕_완료_후_브랜드_프로필_이미지_아카이브가_실행된다() {
		var brands = new StubBrands();
		var brandArchive = new StubBrandArchive();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), brandArchive).run();

		assertThat(brandArchive.runs).isEqualTo(1);
	}

	@Test
	void 브랜드_아카이브_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var brandArchive = new StubBrandArchive();
		brandArchive.failing = true;
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), brandArchive).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	@Test
	void 게시자_아카이브가_실패해도_브랜드_아카이브는_실행된다() {
		var brands = new StubBrands();
		var archive = new StubArchive();
		archive.failing = true;
		var brandArchive = new StubBrandArchive();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), archive, brandArchive).run();

		assertThat(brandArchive.runs).isEqualTo(1);   // 두 아카이브는 각자 격리 — 한쪽 실패가 다른 쪽을 막지 않는다
	}

	/**
	 * 등록 백필의 계정 게이트(markServing, 2026-08-18 첫 페이지 게시자 보강 직후로 단축)는 등록
	 * 경로 전용이다 — 야간 스윕은 {@link BrandCollectService#sweep}(2-인자 enrich, onVisible 없음)
	 * 만 쓰므로 markServing 자체를 부를 일이 없다. 배선이 잘못 얽혀 스윕이 계정 게이트를 건드리면
	 * 여기서 잡힌다.
	 */
	@Test
	void 야간_스윕_경로는_markServing을_호출하지_않는다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive())
				.run();

		assertThat(brands.served).isEmpty();
	}

	@Test
	void 브랜드마다_해시태그_스윕을_이어_돌린다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		var hashtagCollect = new StubHashtagCollect();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		sweepJob(brands, collect, hashtagCollect, new StubArchive(), new StubBrandArchive()).run();

		assertThat(hashtagCollect.swept).containsExactlyInAnyOrder("first", "second");
	}

	// ── direct 게시물 2단계(2026-08-18 direct 통합 §3-2) ──────────────────────

	@Test
	void direct_단계가_던져도_해시태그_단계가_실행되고_touchSwept가_유지된다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		var directCollect = new StubDirectCollect();
		directCollect.failing.add("first");
		var hashtagCollect = new StubHashtagCollect();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, collect, directCollect, hashtagCollect, new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				new StubHashtagAuthorArchive(), new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();   // 예외가 새면 여기서 터진다

		assertThat(hashtagCollect.swept).containsExactly("first");   // direct 실패와 무관하게 시도됨
		assertThat(brands.touched).containsExactly(1L);              // 1단계(유저태그) 성공은 유지
	}

	@Test
	void 유저태그_단계가_던져도_direct_단계는_실행된다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		var directCollect = new StubDirectCollect();
		brands.active = List.of(brand(1, "boom"));

		new BrandSweepJob(brands, collect, directCollect, new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				new StubHashtagAuthorArchive(), new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();

		assertThat(directCollect.swept).containsExactly("boom");
		assertThat(brands.touched).isEmpty();   // 유저태그 스윕 실패라 여전히 미갱신
	}

	@Test
	void 해시태그_스윕_실패는_touchSwept를_깨지_않는다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		var hashtagCollect = new StubHashtagCollect();
		hashtagCollect.failing.add("first");
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, collect, hashtagCollect, new StubArchive(), new StubBrandArchive()).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	@Test
	void 유저태그_스윕이_실패한_브랜드도_해시태그_스윕은_시도된다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		var hashtagCollect = new StubHashtagCollect();
		brands.active = List.of(brand(1, "boom"));

		sweepJob(brands, collect, hashtagCollect, new StubArchive(), new StubBrandArchive()).run();

		assertThat(hashtagCollect.swept).containsExactly("boom");
		assertThat(brands.touched).isEmpty();   // 유저태그 스윕 실패라 여전히 미갱신 — 해시태그는 그와 무관하게 시도됨
	}

	@Test
	void 스윕이_예외로_이탈해도_아카이브는_실행된다() {
		var brands = new BrandRepository(null) {
			@Override
			public List<BrandRow> findActive() {
				throw new IllegalStateException("DB 조회 실패 주입");
			}
		};
		var archive = new StubArchive();
		var brandArchive = new StubBrandArchive();
		var postThumbArchive = new StubPostThumbArchive();
		var hashtagThumbArchive = new StubHashtagThumbArchive();
		var hashtagAuthorArchive = new StubHashtagAuthorArchive();
		var adJudge = new StubAdJudge();

		assertThatThrownBy(() -> new BrandSweepJob(brands, new StubCollect(), new StubDirectCollect(),
				new StubHashtagCollect(), archive, brandArchive, postThumbArchive, hashtagThumbArchive,
				hashtagAuthorArchive, adJudge, sweepSettings(3), BRAND_WORKER, true).run())
				.isInstanceOf(IllegalStateException.class);

		assertThat(archive.runs).isEqualTo(1);   // DailySweepJob과 동형 — finally에서 반드시 실행
		assertThat(brandArchive.runs).isEqualTo(1);
		assertThat(postThumbArchive.runs).isEqualTo(1);
		assertThat(hashtagThumbArchive.runs).isEqualTo(1);
		assertThat(hashtagAuthorArchive.runs).isEqualTo(1);
		assertThat(adJudge.backfillCalls).isEqualTo(1);   // 백필도 아카이브와 같은 finally — 브랜드 조회 실패에도 실행
	}

	@Test
	void 스윕_완료_후_게시물_썸네일_아카이브_두_잡이_실행된다() {
		var brands = new StubBrands();
		var postThumbArchive = new StubPostThumbArchive();
		var hashtagThumbArchive = new StubHashtagThumbArchive();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), postThumbArchive, hashtagThumbArchive,
				new StubHashtagAuthorArchive(), new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();

		assertThat(postThumbArchive.runs).isEqualTo(1);
		assertThat(hashtagThumbArchive.runs).isEqualTo(1);
	}

	@Test
	void 게시물_썸네일_아카이브_실패는_격리되고_해시태그_아카이브는_계속_실행된다() {
		var brands = new StubBrands();
		var postThumbArchive = new StubPostThumbArchive();
		postThumbArchive.failing = true;
		var hashtagThumbArchive = new StubHashtagThumbArchive();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), postThumbArchive, hashtagThumbArchive,
				new StubHashtagAuthorArchive(), new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
		assertThat(hashtagThumbArchive.runs).isEqualTo(1);   // 잡별 격리 — 한쪽 실패가 다른 쪽을 막지 않는다
	}

	@Test
	void 스윕_완료_후_해시태그_작성자_이미지_아카이브가_실행된다() {
		var brands = new StubBrands();
		var hashtagAuthorArchive = new StubHashtagAuthorArchive();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				hashtagAuthorArchive, new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();

		assertThat(hashtagAuthorArchive.runs).isEqualTo(1);
	}

	@Test
	void 해시태그_작성자_이미지_아카이브_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var hashtagAuthorArchive = new StubHashtagAuthorArchive();
		hashtagAuthorArchive.failing = true;
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				hashtagAuthorArchive, new StubAdJudge(), sweepSettings(3), BRAND_WORKER, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	/** 상한 제거(2026-08-18) — 킬 스위치가 켜져 있으면 매 스윕마다 무조건 1회 호출한다(더는 상한
	 * 인자가 없다). */
	@Test
	void 판정_킬_스위치가_켜져있으면_매_스윕마다_백필을_호출한다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, true).run();

		assertThat(adJudge.backfillCalls).isEqualTo(1);
	}

	@Test
	void 판정_킬_스위치가_꺼지면_백필도_스킵된다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, false).run();

		assertThat(adJudge.backfillCalls).isZero();
	}

	@Test
	void 백필_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		adJudge.failing = new IllegalStateException("백필 실패 주입");
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	// ── 브랜드 루프 병렬화(2026-09-03 야간 스윕 단축) ──────────────────────────

	/** 브랜드 단위가 실제로 겹쳐 돈다 — 직렬이면 래치가 안 차서 타임아웃으로 실패한다. */
	@Test
	void 브랜드_루프가_N병렬로_돈다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "a"), brand(2, "b"), brand(3, "c"));
		java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(3);
		java.util.concurrent.atomic.AtomicBoolean overlapped = new java.util.concurrent.atomic.AtomicBoolean();
		var collect = new StubCollect() {
			@Override
			public void sweep(BrandRow brand) {
				super.sweep(brand);
				arrived.countDown();
				try {
					if (arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
						overlapped.set(true);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};

		sweepJob(brands, collect, new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubAdJudge(), true, 3).run();

		assertThat(overlapped).isTrue();
		assertThat(brands.touched).containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	/** 킬스위치 — N=1이면 executor를 안 쓰고 호출 스레드에서 등록 순서대로(개정 전 경로). */
	@Test
	void N이_1이면_브랜드가_호출_스레드에서_순서대로_처리된다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "a"), brand(2, "b"), brand(3, "c"));
		var collect = new StubCollect();
		java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
		var hashtag = new StubHashtagCollect() {
			@Override
			public void sweep(BrandRow brand) {
				threads.add(Thread.currentThread().getName());
				super.sweep(brand);
			}
		};

		sweepJob(brands, collect, new StubDirectCollect(), hashtag, new StubArchive(), new StubBrandArchive(),
				new StubAdJudge(), true, 1).run();

		assertThat(collect.swept).containsExactly("a", "b", "c");
		assertThat(brands.touched).containsExactly(1L, 2L, 3L);
		assertThat(threads).hasSize(1);
	}

	/**
	 * 브랜드 <b>안</b>의 단계 순서는 병렬화 뒤에도 ①유저태그 ②2단계 ③해시태그 그대로여야 한다 —
	 * 2단계의 감시 세트 바닥이 "어제까지의 편입" 기준이라는 전제(2026-09-02 설계 §3)가 ③을 뒤에
	 * 두는 것에 걸려 있다.
	 */
	@Test
	void 브랜드_안의_단계_순서는_유지된다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "a"), brand(2, "b"), brand(3, "c"));
		List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		var collect = new StubCollect() {
			@Override
			public void sweep(BrandRow brand) {
				steps.add(brand.username() + ":1");
				super.sweep(brand);
			}
		};
		var direct = new StubDirectCollect() {
			@Override
			public void sweepUnenumerated(BrandRow brand, SweepPostCache cache) {
				steps.add(brand.username() + ":2");
				super.sweepUnenumerated(brand, cache);
			}
		};
		var hashtag = new StubHashtagCollect() {
			@Override
			public void sweep(BrandRow brand) {
				steps.add(brand.username() + ":3");
				super.sweep(brand);
			}
		};

		sweepJob(brands, collect, direct, hashtag, new StubArchive(), new StubBrandArchive(), new StubAdJudge(),
				true, 3).run();

		for (String username : List.of("a", "b", "c")) {
			List<String> mine = steps.stream().filter(x -> x.startsWith(username + ":")).toList();
			assertThat(mine).containsExactly(username + ":1", username + ":2", username + ":3");
		}
	}

	/** 런 스코프 캐시는 브랜드마다 같은 인스턴스여야 한다 — 아니면 브랜드 간 중복 콜이 안 접힌다. */
	@Test
	void 모든_브랜드가_같은_런_스코프_캐시를_공유한다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "a"), brand(2, "b"), brand(3, "c"));
		var direct = new StubDirectCollect();

		sweepJob(brands, new StubCollect(), direct, new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubAdJudge(), true, 3).run();

		assertThat(direct.caches).hasSize(1);
	}

	/** 병렬이어도 실패는 브랜드 단위로 격리되고 성공 브랜드만 last_swept_on이 찍힌다. */
	@Test
	void 병렬에서도_실패_브랜드만_격리되고_나머지는_완주한다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "a"), brand(2, "boom"), brand(3, "c"));
		var collect = new StubCollect();
		collect.failing.add("boom");
		var direct = new StubDirectCollect();
		direct.failing.add("c");

		sweepJob(brands, collect, direct, new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				new StubAdJudge(), true, 3).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.swept).containsExactlyInAnyOrder("a", "c");
		assertThat(direct.swept).containsExactlyInAnyOrder("a", "boom");
		assertThat(brands.touched).containsExactlyInAnyOrder(1L, 3L);
	}
}
