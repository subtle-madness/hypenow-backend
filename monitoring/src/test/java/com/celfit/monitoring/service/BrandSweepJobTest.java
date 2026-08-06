package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 브랜드 스윕 — 3일 트래킹 주기 판정·성공 시에만 last_tracked_on 갱신·브랜드 단위 격리. */
class BrandSweepJobTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

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
		public void touchTracked(long brandId, LocalDate on) {
			touched.add(brandId);
		}
	}

	private static final class StubCollect extends BrandCollectService {
		final List<String> detected = new ArrayList<>();
		final List<String> tracked = new ArrayList<>();
		final Set<String> failing = new HashSet<>();

		StubCollect() {
			super(null, null, null, null, null, null, 90, 105, 3, 30);
		}

		@Override
		public void detect(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("감지 실패 주입");
			}
			detected.add(brand.username());
		}

		@Override
		public void track(BrandRow brand) {
			if (failing.contains(brand.username())) {
				throw new IllegalStateException("트래킹 실패 주입");
			}
			tracked.add(brand.username());
		}
	}

	private static BrandRow brand(long id, String username, LocalDate lastTrackedOn) {
		return new BrandRow(id, username, String.valueOf(id), BrandStatus.ACTIVE, lastTrackedOn);
	}

	@Test
	void 트래킹_도래_판정_3일_주기() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		brands.active = List.of(
				brand(1, "never_tracked", null),              // 백필 미완 — 트래킹 백스톱
				brand(2, "two_days_ago", TODAY.minusDays(2)), // 주기 미도래 — 감지
				brand(3, "three_days_ago", TODAY.minusDays(3)), // 도래 — 트래킹
				brand(4, "today", TODAY));                    // 오늘 트래킹함 — 감지

		new BrandSweepJob(brands, collect, 3).run();

		assertThat(collect.tracked).containsExactly("never_tracked", "three_days_ago");
		assertThat(collect.detected).containsExactly("two_days_ago", "today");
	}

	@Test
	void 트래킹_성공시에만_last_tracked_on을_갱신한다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("fail_track");
		brands.active = List.of(brand(1, "fail_track", null), brand(2, "ok_track", null));

		new BrandSweepJob(brands, collect, 3).run();

		assertThat(brands.touched).containsExactly(2L);   // 실패 브랜드는 미갱신 — 내일 다시 트래킹
		assertThat(collect.tracked).containsExactly("ok_track");
	}

	@Test
	void 브랜드_실패는_격리된다() {
		var brands = new StubBrands();
		var collect = new StubCollect();
		collect.failing.add("boom");
		brands.active = List.of(
				brand(1, "first", TODAY), brand(2, "boom", TODAY), brand(3, "third", TODAY));

		new BrandSweepJob(brands, collect, 3).run();   // 예외가 새면 여기서 터진다

		assertThat(collect.detected).containsExactly("first", "third");
	}
}
