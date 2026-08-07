package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
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
			super(null, null, null, null, null, null, 90, 105, 3, 30);
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

		new BrandSweepJob(brands, collect).run();

		assertThat(collect.swept).containsExactly("first", "second");
		assertThat(brands.touched).containsExactly(1L, 2L);
	}

	@Test
	void 실패_브랜드는_격리되고_last_swept_on을_갱신하지_않는다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		brands.active = List.of(brand(1, "first"), brand(2, "boom"), brand(3, "third"));

		new BrandSweepJob(brands, collect).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.swept).containsExactly("first", "third");
		assertThat(brands.touched).containsExactly(1L, 3L);   // boom은 "준비 중" 유지 — 내일 백스톱
	}
}
