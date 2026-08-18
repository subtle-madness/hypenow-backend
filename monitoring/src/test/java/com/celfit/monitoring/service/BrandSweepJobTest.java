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
			super(null, null, null, "https://par.example/o/", 1000);
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
			super(null, null, null, "https://par.example/o/", 1000);
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
			super(null, null, null, "https://par.example/o/", 1000);
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
			super(null, null, null, "https://par.example/o/", 1000);
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
			super(null, null, null, "https://par.example/o/", 1000);
		}

		@Override
		public void run() {
			runs++;
			if (failing) {
				throw new IllegalStateException("아카이브 실패 주입");
			}
		}
	}

	/** 백필 스텁 — 호출 횟수·마지막 limit·주입한 실패를 관측한다. */
	private static final class StubAdJudge extends AdDisclosureJudgeService {
		int backfillCalls;
		int lastLimit = -1;
		BackfillOutcome next = new BackfillOutcome(0, 0);
		RuntimeException failing;

		StubAdJudge() {
			super(null, null, null);
		}

		@Override
		public BackfillOutcome backfillUnjudged(int limit) {
			backfillCalls++;
			lastLimit = limit;
			if (failing != null) {
				throw failing;
			}
			return next;
		}
	}

	private static final class StubBrands extends BrandRepository {
		List<BrandRow> active = List.of();
		final List<Long> touched = new ArrayList<>();

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
	}

	private static final class StubCollect extends BrandCollectService {
		final List<String> swept = new ArrayList<>();
		final Set<String> failing = new HashSet<>();

		StubCollect() {
			super(null, null, null, null, null, null, null, null, null, 2000, 3, 30, true);
		}

		@Override
		public void sweep(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("스윕 실패 주입");
			}
			swept.add(brand.username());
		}
	}

	private static final class StubHashtagCollect extends BrandHashtagCollectService {
		final List<String> swept = new ArrayList<>();
		final Set<String> failing = new HashSet<>();

		StubHashtagCollect() {
			super(null, null, null, null, null, 0, 0);
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
		return new BrandRow(id, username, String.valueOf(id), BrandStatus.ACTIVE, null, 12);
	}

	/** 신규 썸네일·작성자 이미지 아카이브 잡들은 대부분의 테스트에서 관심 밖 — 성공 스텁으로 채운다.
	 * 광고 판정 백필도 마찬가지로 기본값(상한 1000, 킬 스위치 on)의 성공 스텁으로 채운다. */
	private static BrandSweepJob sweepJob(BrandRepository brands, BrandCollectService collect,
			BrandHashtagCollectService hashtagCollect, AuthorProfileImageArchiveJob archive,
			BrandProfileImageArchiveJob brandArchive) {
		return sweepJob(brands, collect, hashtagCollect, archive, brandArchive, new StubAdJudge(), 1000, true);
	}

	private static BrandSweepJob sweepJob(BrandRepository brands, BrandCollectService collect,
			BrandHashtagCollectService hashtagCollect, AuthorProfileImageArchiveJob archive,
			BrandProfileImageArchiveJob brandArchive, AdDisclosureJudgeService adJudge, int backfillPerNight,
			boolean adDisclosureEnabled) {
		return new BrandSweepJob(brands, collect, hashtagCollect, archive, brandArchive,
				new StubPostThumbArchive(), new StubHashtagThumbArchive(), new StubHashtagAuthorArchive(),
				adJudge, backfillPerNight, adDisclosureEnabled);
	}

	@Test
	void 활성_브랜드_전부를_매일_전량_수집한다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		sweepJob(brands, collect, new StubHashtagCollect(), new StubArchive(), new StubBrandArchive()).run();

		assertThat(collect.swept).containsExactly("first", "second");
		assertThat(brands.touched).containsExactly(1L, 2L);
	}

	@Test
	void 실패_브랜드는_격리되고_last_swept_on을_갱신하지_않는다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		brands.active = List.of(brand(1, "first"), brand(2, "boom"), brand(3, "third"));

		sweepJob(brands, collect, new StubHashtagCollect(), new StubArchive(), new StubBrandArchive()).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.swept).containsExactly("first", "third");
		assertThat(brands.touched).containsExactly(1L, 3L);   // boom은 "준비 중" 유지 — 내일 백스톱
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

	@Test
	void 브랜드마다_해시태그_스윕을_이어_돌린다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		var hashtagCollect = new StubHashtagCollect();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		sweepJob(brands, collect, hashtagCollect, new StubArchive(), new StubBrandArchive()).run();

		assertThat(hashtagCollect.swept).containsExactly("first", "second");
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

		assertThatThrownBy(() -> new BrandSweepJob(brands, new StubCollect(), new StubHashtagCollect(),
				archive, brandArchive, postThumbArchive, hashtagThumbArchive, hashtagAuthorArchive, adJudge,
				1000, true).run())
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

		new BrandSweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), postThumbArchive, hashtagThumbArchive,
				new StubHashtagAuthorArchive(), new StubAdJudge(), 1000, true).run();

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

		new BrandSweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), postThumbArchive, hashtagThumbArchive,
				new StubHashtagAuthorArchive(), new StubAdJudge(), 1000, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
		assertThat(hashtagThumbArchive.runs).isEqualTo(1);   // 잡별 격리 — 한쪽 실패가 다른 쪽을 막지 않는다
	}

	@Test
	void 스윕_완료_후_해시태그_작성자_이미지_아카이브가_실행된다() {
		var brands = new StubBrands();
		var hashtagAuthorArchive = new StubHashtagAuthorArchive();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				hashtagAuthorArchive, new StubAdJudge(), 1000, true).run();

		assertThat(hashtagAuthorArchive.runs).isEqualTo(1);
	}

	@Test
	void 해시태그_작성자_이미지_아카이브_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var hashtagAuthorArchive = new StubHashtagAuthorArchive();
		hashtagAuthorArchive.failing = true;
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(),
				new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
				hashtagAuthorArchive, new StubAdJudge(), 1000, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}

	@Test
	void 백필_상한만큼_findUnjudged를_호출한다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, 500, true).run();

		assertThat(adJudge.backfillCalls).isEqualTo(1);
		assertThat(adJudge.lastLimit).isEqualTo(500);
	}

	@Test
	void 백필_상한이_0이면_비활성() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, 0, true).run();

		assertThat(adJudge.backfillCalls).isZero();
	}

	@Test
	void 판정_킬_스위치가_꺼지면_백필도_스킵된다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, 1000, false).run();

		assertThat(adJudge.backfillCalls).isZero();
	}

	@Test
	void 백필_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var adJudge = new StubAdJudge();
		adJudge.failing = new IllegalStateException("백필 실패 주입");
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive(),
				adJudge, 1000, true).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
	}
}
