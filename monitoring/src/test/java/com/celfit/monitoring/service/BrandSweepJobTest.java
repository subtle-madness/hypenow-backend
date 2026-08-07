package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.image.AuthorProfileImageArchiveJob;
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
			super(null, null, null, null, null, null, null, 90, 105, 3, 30);
		}

		@Override
		public void sweep(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("스윕 실패 주입");
			}
			swept.add(brand.username());
		}
	}

	private static BrandRow brand(long id, String username) {
		return new BrandRow(id, username, String.valueOf(id), BrandStatus.ACTIVE, null);
	}

	@Test
	void 활성_브랜드_전부를_매일_전량_수집한다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));

		new BrandSweepJob(brands, collect, new StubArchive()).run();

		assertThat(collect.swept).containsExactly("first", "second");
		assertThat(brands.touched).containsExactly(1L, 2L);
	}

	@Test
	void 실패_브랜드는_격리되고_last_swept_on을_갱신하지_않는다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		brands.active = List.of(brand(1, "first"), brand(2, "boom"), brand(3, "third"));

		new BrandSweepJob(brands, collect, new StubArchive()).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.swept).containsExactly("first", "third");
		assertThat(brands.touched).containsExactly(1L, 3L);   // boom은 "준비 중" 유지 — 내일 백스톱
	}

	@Test
	void 스윕_완료_후_게시자_프로필_이미지_아카이브가_실행된다() {
		var brands = new StubBrands();
		var archive = new StubArchive();
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), archive).run();

		assertThat(archive.runs).isEqualTo(1);
	}

	@Test
	void 아카이브_실패는_격리되어_스윕_결과에_영향을_주지_않는다() {
		var brands = new StubBrands();
		var archive = new StubArchive();
		archive.failing = true;
		brands.active = List.of(brand(1, "first"));

		new BrandSweepJob(brands, new StubCollect(), archive).run();   // 예외가 새면 여기서 터진다

		assertThat(brands.touched).containsExactly(1L);
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

		assertThatThrownBy(() -> new BrandSweepJob(brands, new StubCollect(), archive).run())
				.isInstanceOf(IllegalStateException.class);

		assertThat(archive.runs).isEqualTo(1);   // DailySweepJob과 동형 — finally에서 반드시 실행
	}
}
