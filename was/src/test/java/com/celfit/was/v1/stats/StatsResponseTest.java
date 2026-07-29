package com.celfit.was.v1.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsResponseTest {

	@Test
	void 분포_비율은_합계가_항상_100이다() {
		// 10/22/25/8 (합 65) → 15.38/33.85/38.46/12.31 → 최대 잔여 보정 → 15/34/39/12
		List<StatsResponse.Band> bands = StatsResponse.distribution(10, 22, 25, 8);
		assertThat(bands).extracting("range").containsExactly("500-3k", "3k-10k", "10k-30k", "30k-50k");
		assertThat(bands).extracting("pct").containsExactly(15, 34, 39, 12);
		assertThat(bands.stream().mapToInt(StatsResponse.Band::pct).sum()).isEqualTo(100);
	}

	@Test
	void 반올림이_어긋나는_분포도_합계_100을_지킨다() {
		// 1/1/1/0 → 33.33 셋 → 단순 반올림이면 99 → 보정으로 34/33/33/0 (동률 잔여는 앞 구간 우선)
		List<StatsResponse.Band> bands = StatsResponse.distribution(1, 1, 1, 0);
		assertThat(bands.stream().mapToInt(StatsResponse.Band::pct).sum()).isEqualTo(100);
		assertThat(bands).extracting("pct").containsExactly(34, 33, 33, 0);
	}

	@Test
	void 계정이_없으면_전_구간_0이다() {
		// 분모 0 방어 — 합계 100 규칙보다 "데이터 없음"이 우선(0/0/0/0)
		List<StatsResponse.Band> bands = StatsResponse.distribution(0, 0, 0, 0);
		assertThat(bands).extracting("pct").containsExactly(0, 0, 0, 0);
	}
}
