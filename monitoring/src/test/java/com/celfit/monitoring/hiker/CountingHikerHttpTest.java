package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.store.BrandCallCountRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 콜 집계 데코레이터 — "브랜드 컨텍스트 안의 성공 콜만 1콜"과 "집계 실패는 수집을 안 죽인다"를
 * 고정한다(2026-08-12 어드민 크롤링 비용 설계). 스코프의 워커 풀 전파는 BrandCollectServiceTest가 커버.
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

	private final BrandCallContext context = new BrandCallContext();
	private final RecordingCallCounts counts = new RecordingCallCounts();

	@Test
	void 컨텍스트_안의_성공_콜만_계상한다() {
		CountingHikerHttp http = new CountingHikerHttp(path -> "{}", context, counts);

		http.get("/no-context");   // 브랜드 밖 콜(캠페인 등) — 미집계
		context.runScoped(7L, () -> http.get("/in-context"));

		assertThat(counts.added).containsExactly("7:" + LocalDate.now(KST) + ":1");
	}

	@Test
	void 실패_콜은_계상하지_않고_예외를_그대로_전파한다() {
		CountingHikerHttp http = new CountingHikerHttp(path -> {
			throw new HikerFetchException("500");
		}, context, counts);

		assertThatThrownBy(() -> context.runScoped(7L, () -> http.get("/fail")))
				.isInstanceOf(HikerFetchException.class);
		assertThat(counts.added).isEmpty();
	}

	@Test
	void 집계_실패는_삼킨다_수집이_먼저다() {
		counts.failing = true;
		CountingHikerHttp http = new CountingHikerHttp(path -> "{\"ok\":1}", context, counts);

		String body = context.scoped(7L, () -> http.get("/x"));

		assertThat(body).isEqualTo("{\"ok\":1}");
	}

	@Test
	void 스코프는_이전_컨텍스트를_복원한다() {
		context.runScoped(1L, () -> {
			context.runScoped(2L, () -> assertThat(context.currentBrandId()).isEqualTo(2L));
			assertThat(context.currentBrandId()).isEqualTo(1L);
		});
		assertThat(context.currentBrandId()).isNull();
	}
}
