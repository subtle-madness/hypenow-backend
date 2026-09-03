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
	void 트랙별_대조는_파트B_완료만_기분석으로_센다() {
		// 후보 5건: timely 2(a,b) + 윈도우 3(c,d,e). 행 보유 {a,c,d,x}, 그중 pending(A만) {c}.
		// 파트 A 행은 랭킹에 못 뜨므로 '기분석'이 아니라 '사실만'으로 따로 센다.
		Map<String, Boolean> candidates = new LinkedHashMap<>();
		candidates.put("a", true);
		candidates.put("b", true);
		candidates.put("c", false);
		candidates.put("d", false);
		candidates.put("e", false);

		PipelineStatsService.TrackSplit s = PipelineStatsService.split(
				candidates, Set.of("a", "c", "d", "x"), Set.of("c"));

		assertThat(s.timelyTotal()).isEqualTo(2);
		assertThat(s.timelyDone()).isEqualTo(1);
		assertThat(s.timelyFactsOnly()).isZero();
		assertThat(s.windowTotal()).isEqualTo(3);
		assertThat(s.windowDone()).isEqualTo(1);   // d만 (c는 사실만)
		assertThat(s.windowFactsOnly()).isEqualTo(1);
		// 항등식: 후보 = 트랙 합, 후보 밖 행 보유(x)는 어디에도 안 센다
		assertThat(s.timelyTotal() + s.windowTotal()).isEqualTo(candidates.size());
	}

	@Test
	void 아카이브_커버리지는_잡의_대상_선정과_같은_규칙으로_대조() {
		// 썸네일: 대상 {a,b,c} ∩ 기록 {a,z} = 1건 아카이브 (대상 밖 기록 z는 안 센다 — 잡과 동일).
		// 프로필: 파일명 일치(p1)만 최신, 불일치(p2)·파싱 불가(p3)는 갱신 대기(잡의 '변경 취급').
		PipelineStatsService.ArchiveCoverage c = PipelineStatsService.archiveCoverage(
				Set.of("a", "b", "c"), Set.of("a", "z"),
				Map.of("p1", "https://cdn.example/t51/111_n.jpg?sig=1",
						"p2", "https://cdn.example/t51/222_n.jpg",
						"p3", "not a url"),
				Map.of("p1", "111_n.jpg", "p2", "999_n.jpg"));
		assertThat(c.thumbTargets()).isEqualTo(3);
		assertThat(c.thumbArchived()).isEqualTo(1);
		assertThat(c.thumbPending()).isEqualTo(2);
		assertThat(c.profileTargets()).isEqualTo(3);
		assertThat(c.profileFresh()).isEqualTo(1);
		assertThat(c.profilePending()).isEqualTo(2);
		// 카드 한 줄용 합산
		assertThat(c.targets()).isEqualTo(6);
		assertThat(c.archived()).isEqualTo(2);
		assertThat(c.pending()).isEqualTo(4);
	}

	@Test
	void heavy_스냅샷은_항등식_후보는_기분석더하기미분석() {
		// v3 설계 문서 §1 실측(07-21) + 09-03 파트 A 축.
		PipelineStatsService.Heavy h = new PipelineStatsService.Heavy(
				7_402,
				1_435, 1_432, 2,
				5_967, 5_684, 100,
				9_000, 8_800,
				12_777, 11_072,
				4_000, 1_104,
				723, 700,
				new PipelineStatsService.ArchiveCoverage(107_886, 27_686, 5_699, 5_694),
				Instant.now());
		assertThat(h.timelyPending()).isEqualTo(3);
		assertThat(h.windowPending()).isEqualTo(283);
		assertThat(h.truePending()).isEqualTo(286);
		assertThat(h.factsOnlyTotal()).isEqualTo(102);
		assertThat(h.factPending()).isEqualTo(200);
		// 항등식: 후보 = (트랙별 파트 B 완료 + 미완)의 합. '사실만'은 미완에 포함된다 -
		// 파트 A만으로는 랭킹에 못 뜨므로 여전히 해야 할 일이다.
		assertThat(h.timelyDone() + h.timelyPending() + h.windowDone() + h.windowPending())
				.isEqualTo(h.candidates());
	}
}
