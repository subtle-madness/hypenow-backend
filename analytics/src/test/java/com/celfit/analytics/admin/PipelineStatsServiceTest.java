package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PipelineStatsServiceTest {

	@Test
	void 잔여로_오늘예정과_완주여부() {
		// LIMIT 폐지(2026-07-23) 이후 잡은 잔여 전량을 매 실행 시도 — 오늘 예정=잔여 전체,
		// 완주까지는 잔여가 있으면 항상 "다음 1회"뿐(쿼타 이월은 RunHistory가 별도 추적).
		assertThat(PipelineStatsService.todayPlanned(24_551)).isEqualTo(24_551);
		assertThat(PipelineStatsService.daysToFull(24_551)).isEqualTo(1);
		// 잔여 0
		assertThat(PipelineStatsService.todayPlanned(0)).isZero();
		assertThat(PipelineStatsService.daysToFull(0)).isZero();
	}

	@Test
	void 트랙별_대조는_기분석_셋과의_교집합으로_4분할() {
		// 후보 5건: timely 2(a,b) + 윈도우 3(c,d,e). 기분석 셋엔 a,c,d와 후보 밖 x.
		Map<String, Boolean> candidates = new LinkedHashMap<>();
		candidates.put("a", true);
		candidates.put("b", true);
		candidates.put("c", false);
		candidates.put("d", false);
		candidates.put("e", false);
		PipelineStatsService.TrackSplit s =
				PipelineStatsService.split(candidates, Set.of("a", "c", "d", "x"));
		assertThat(s.timelyTotal()).isEqualTo(2);
		assertThat(s.timelyDone()).isEqualTo(1);
		assertThat(s.windowTotal()).isEqualTo(3);
		assertThat(s.windowDone()).isEqualTo(2);
		// 항등식: 후보 = 트랙 합, 후보 밖 기분석(x)은 어디에도 안 센다
		assertThat(s.timelyTotal() + s.windowTotal()).isEqualTo(candidates.size());
	}

	@Test
	void heavy_스냅샷은_항등식_후보는_기분석더하기미분석() {
		// v3 설계 문서 §1 실측(07-21): 후보 7,402 = timely 1,435 + 윈도우 5,967, 미분석 286.
		PipelineStatsService.Heavy h = new PipelineStatsService.Heavy(
				7_402, 1_435, 1_432, 5_967, 5_684,
				12_777, 11_072, 4_000, 1_104, 723, 700, Instant.now());
		assertThat(h.timelyPending()).isEqualTo(3);
		assertThat(h.windowPending()).isEqualTo(283);
		assertThat(h.truePending()).isEqualTo(286);
		// 항등식: 후보 = (트랙별 기분석 + 미분석)의 합
		assertThat(h.timelyDone() + h.timelyPending() + h.windowDone() + h.windowPending())
				.isEqualTo(h.candidates());
	}
}
