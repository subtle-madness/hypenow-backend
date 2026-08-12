package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 콜 집계 데코레이터 — "컨텍스트 안의 성공 콜만 1콜"과 "집계 실패는 수집을 안 죽인다"를
 * 브랜드·캠페인 양쪽에서 고정한다(2026-08-12 어드민 크롤링 비용 설계 + 같은 날 범위 확장).
 * 스코프의 워커 풀 전파는 BrandCollectServiceTest가 커버.
 */
class CountingHikerHttpTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final class RecordingCallCounts extends BrandCallCountRepository {
		final List<String> added = new ArrayList<>();
		boolean failing = false;

		RecordingCallCounts() {
			super(null);
		}

		@Override
		public void add(long brandId, LocalDate calledOn, long delta) {
			if (failing) {
				throw new IllegalStateException("DB 죽음 주입");
			}
			added.add(brandId + ":" + calledOn + ":" + delta);
		}
	}

	private static final class RecordingTargetCallCounts extends TargetCallCountRepository {
		final List<String> added = new ArrayList<>();
		boolean failing = false;

		RecordingTargetCallCounts() {
			super(null);
		}

		@Override
		public void add(long userId, LocalDate calledOn, long delta) {
			if (failing) {
				throw new IllegalStateException("DB 죽음 주입");
			}
			added.add(userId + ":" + calledOn + ":" + delta);
		}
	}

	private final BrandCallContext context = new BrandCallContext();
	private final RecordingCallCounts counts = new RecordingCallCounts();
	private final TargetCallContext targetContext = new TargetCallContext();
	private final RecordingTargetCallCounts targetCounts = new RecordingTargetCallCounts();

	private CountingHikerHttp counting(HikerHttp delegate) {
		return new CountingHikerHttp(delegate, context, counts, targetContext, targetCounts);
	}

	@Test
	void 컨텍스트_안의_성공_콜만_계상한다() {
		CountingHikerHttp http = counting(path -> "{}");

		http.get("/no-context");   // 두 컨텍스트 모두 밖의 콜 — 미집계
		context.runScoped(7L, () -> http.get("/in-context"));

		assertThat(counts.added).containsExactly("7:" + LocalDate.now(KST) + ":1");
		assertThat(targetCounts.added).isEmpty();
	}

	@Test
	void 캠페인_컨텍스트의_콜은_서빙_유저마다_계상한다() {
		CountingHikerHttp http = counting(path -> "{}");

		targetContext.runScoped(Set.of(11L), () -> http.get("/enumerate"));
		targetContext.runScoped(new java.util.LinkedHashSet<>(List.of(11L, 12L)),
				() -> http.get("/shared-account"));

		LocalDate today = LocalDate.now(KST);
		assertThat(targetCounts.added).containsExactly(
				"11:" + today + ":1", "11:" + today + ":1", "12:" + today + ":1");
		assertThat(counts.added).isEmpty();
	}

	@Test
	void 실패_콜은_계상하지_않고_예외를_그대로_전파한다() {
		CountingHikerHttp http = counting(path -> {
			throw new HikerFetchException("500");
		});

		assertThatThrownBy(() -> context.runScoped(7L, () -> http.get("/fail")))
				.isInstanceOf(HikerFetchException.class);
		assertThatThrownBy(() -> targetContext.runScoped(Set.of(11L), () -> http.get("/fail")))
				.isInstanceOf(HikerFetchException.class);
		assertThat(counts.added).isEmpty();
		assertThat(targetCounts.added).isEmpty();
	}

	@Test
	void 집계_실패는_삼킨다_수집이_먼저다() {
		counts.failing = true;
		targetCounts.failing = true;
		CountingHikerHttp http = counting(path -> "{\"ok\":1}");

		String brandBody = context.scoped(7L, () -> http.get("/x"));
		String targetBody = targetContext.scoped(Set.of(11L), () -> http.get("/y"));

		assertThat(brandBody).isEqualTo("{\"ok\":1}");
		assertThat(targetBody).isEqualTo("{\"ok\":1}");
	}

	@Test
	void 스코프는_이전_컨텍스트를_복원한다() {
		context.runScoped(1L, () -> {
			context.runScoped(2L, () -> assertThat(context.currentBrandId()).isEqualTo(2L));
			assertThat(context.currentBrandId()).isEqualTo(1L);
		});
		assertThat(context.currentBrandId()).isNull();
	}

	@Test
	void 캠페인_스코프도_이전_컨텍스트를_복원한다() {
		// 스윕의 중첩 스코프(계정 유저 전원 → 타깃 단건 유저) 복원 — DailySweepJob.sweepAccount 경로.
		targetContext.runScoped(Set.of(1L, 2L), () -> {
			targetContext.runScoped(Set.of(1L),
					() -> assertThat(targetContext.currentUserIds()).containsExactly(1L));
			assertThat(targetContext.currentUserIds()).containsExactlyInAnyOrder(1L, 2L);
		});
		assertThat(targetContext.currentUserIds()).isNull();
	}
}
