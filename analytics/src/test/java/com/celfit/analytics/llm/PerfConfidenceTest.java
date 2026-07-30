package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 성과 요약 통계 왜곡 가드 판정 경계값 (설계 2026-07-30-perf-summary-statistical-guards §3-2·§6):
 * 모수 2/3·5/6 경계, top_views_share_pct 74/75, window_span_days 90/365 경계, 모수 게이트와 한 건
 * 지배 플래그의 중복 배제, NULL·키 부재 입력에서의 NPE 없는 보수적 등급.
 */
class PerfConfidenceTest {

	/** 모든 게이트가 "걸리지 않는" 기본값 — 개별 테스트는 필요한 키만 덮어쓴다. */
	static Map<String, Object> baseSummary() {
		Map<String, Object> m = new HashMap<>();
		m.put("views_sample_count", 10);
		m.put("likes_sample_count", 10);
		m.put("comments_sample_count", 10);
		m.put("reels_count", 10);
		m.put("feed_count", 10);
		m.put("top_views_share_pct", 10);
		m.put("window_span_days", 30);
		return m;
	}

	@Test
	void 모수_2는_INSUFFICIENT_3은_WEAK다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 2);
		assertEquals(PerfConfidence.Grade.INSUFFICIENT, PerfConfidence.of(m).viewsGrade());

		m.put("views_sample_count", 3);
		assertEquals(PerfConfidence.Grade.WEAK, PerfConfidence.of(m).viewsGrade());
	}

	@Test
	void 모수_5는_WEAK_6은_OK다() {
		Map<String, Object> m = baseSummary();
		m.put("likes_sample_count", 5);
		assertEquals(PerfConfidence.Grade.WEAK, PerfConfidence.of(m).likesGrade());

		m.put("likes_sample_count", 6);
		assertEquals(PerfConfidence.Grade.OK, PerfConfidence.of(m).likesGrade());
	}

	@Test
	void 댓글_모수도_조회수와_동일한_경계를_따른다() {
		Map<String, Object> m = baseSummary();
		m.put("comments_sample_count", 2);
		assertEquals(PerfConfidence.Grade.INSUFFICIENT, PerfConfidence.of(m).commentsGrade());

		m.put("comments_sample_count", 6);
		assertEquals(PerfConfidence.Grade.OK, PerfConfidence.of(m).commentsGrade());
	}

	@Test
	void 점유율_74는_지배아님_75는_지배다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 3); // 모수 게이트(3+) 통과
		m.put("top_views_share_pct", 74);
		assertFalse(PerfConfidence.of(m).singlePostDominance());

		m.put("top_views_share_pct", 75);
		assertTrue(PerfConfidence.of(m).singlePostDominance());
	}

	/** 모수 1~2건은 점유율이 자동 100%라 모수 게이트와 뜻이 겹친다 — 이 플래그는 세우지 않는다(§3-2). */
	@Test
	void 모수_2에_점유율_100이어도_한_건_지배_플래그는_서지_않는다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 2);
		m.put("top_views_share_pct", 100);

		assertFalse(PerfConfidence.of(m).singlePostDominance());
	}

	@Test
	void 창_길이_90은_OK_91은_LONG_SPAN이다() {
		Map<String, Object> m = baseSummary();
		m.put("window_span_days", 90);
		assertEquals(PerfConfidence.TrendValidity.OK, PerfConfidence.of(m).trendValidity());

		m.put("window_span_days", 91);
		assertEquals(PerfConfidence.TrendValidity.LONG_SPAN, PerfConfidence.of(m).trendValidity());
	}

	@Test
	void 창_길이_365는_LONG_SPAN_366은_TOO_LONG이다() {
		Map<String, Object> m = baseSummary();
		m.put("window_span_days", 365);
		assertEquals(PerfConfidence.TrendValidity.LONG_SPAN, PerfConfidence.of(m).trendValidity());

		m.put("window_span_days", 366);
		assertEquals(PerfConfidence.TrendValidity.TOO_LONG, PerfConfidence.of(m).trendValidity());
	}

	@Test
	void 포맷_비교는_릴스_피드_각각_3건_미만이면_불가다() {
		Map<String, Object> m = baseSummary();
		m.put("reels_count", 2);
		assertFalse(PerfConfidence.of(m).formatComparable());

		m.put("reels_count", 3);
		m.put("feed_count", 2);
		assertFalse(PerfConfidence.of(m).formatComparable());

		m.put("feed_count", 3);
		assertTrue(PerfConfidence.of(m).formatComparable());
	}

	/** 관측 없는 계정(빈 맵)에서도 NPE 없이 가장 보수적인(서술 억제) 등급으로 접혀야 한다. */
	@Test
	void 빈_맵_입력에서도_NPE_없이_보수적_등급이_나온다() {
		PerfConfidence c = PerfConfidence.of(Map.of());

		assertEquals(PerfConfidence.Grade.INSUFFICIENT, c.viewsGrade());
		assertEquals(PerfConfidence.Grade.INSUFFICIENT, c.likesGrade());
		assertEquals(PerfConfidence.Grade.INSUFFICIENT, c.commentsGrade());
		assertEquals(PerfConfidence.TrendValidity.TOO_LONG, c.trendValidity());
		assertFalse(c.singlePostDominance());
		assertFalse(c.formatComparable());
	}

	/** 새 컬럼이 아직 미러되지 않은 과도기 — 키는 있지만 값이 NULL인 경우도 동일하게 보수적이어야 한다. */
	@Test
	void NULL_값_입력에서도_NPE_없이_보수적_등급이_나온다() {
		Map<String, Object> m = new HashMap<>();
		m.put("views_sample_count", null);
		m.put("likes_sample_count", null);
		m.put("comments_sample_count", null);
		m.put("reels_count", null);
		m.put("feed_count", null);
		m.put("top_views_share_pct", null);
		m.put("window_span_days", null);

		PerfConfidence c = PerfConfidence.of(m);

		assertEquals(PerfConfidence.Grade.INSUFFICIENT, c.viewsGrade());
		assertEquals(PerfConfidence.TrendValidity.TOO_LONG, c.trendValidity());
		assertFalse(c.singlePostDominance());
		assertFalse(c.formatComparable());
	}

	@Test
	void none은_모든_지표가_OK고_지침_블록이_비어있다() {
		PerfConfidence c = PerfConfidence.none();

		assertEquals(PerfConfidence.Grade.OK, c.viewsGrade());
		assertEquals(PerfConfidence.Grade.OK, c.likesGrade());
		assertEquals(PerfConfidence.Grade.OK, c.commentsGrade());
		assertEquals(PerfConfidence.TrendValidity.OK, c.trendValidity());
		assertTrue(c.formatComparable());
		assertFalse(c.singlePostDominance());
		assertEquals("", c.promptBlock());
		// none()은 "판정 자체를 적용 안 함"이지 dataIncomplete(배포 과도기)와는 다른 상태다.
		assertFalse(c.dataIncomplete());
	}

	/**
	 * 배포 과도기 감지(설계 2026-07-30-perf-summary-statistical-guards §7) — 신뢰도 재료 9컬럼이
	 * 전부 NULL/부재면 "표본 부족"이 아니라 "뷰 미적용/미러 실패"로 판단해야 한다. 뷰의 count(*)
	 * FILTER 계열 5컬럼은 분석 이력이 있는 계정에서 정상적으로는 절대 NULL이 될 수 없으므로
	 * (매치 0건이면 0을 반환), 9개 전부 NULL은 미러 갭의 신호로만 성립한다.
	 */
	@Test
	void 신뢰도_컬럼_9개가_전부_NULL이면_데이터_미비로_판정된다() {
		Map<String, Object> m = new HashMap<>();
		for (String key : PerfConfidence.CONFIDENCE_COLUMNS) {
			m.put(key, null);
		}

		assertTrue(PerfConfidence.of(m).dataIncomplete());
	}

	@Test
	void 컬럼이_아예_없어도_전부_NULL과_동일하게_데이터_미비로_판정된다() {
		assertTrue(PerfConfidence.of(Map.of()).dataIncomplete());
	}

	/**
	 * 피드 전용 계정(조회수 관측이 원천적으로 없음)은 데이터 미비가 아니라 정상 판정을 받아야
	 * 한다 — views_sample_count=0·reels_count=0·feed_count=12처럼 값이 채워지지 NULL이 되지
	 * 않기 때문이다. 이 케이스를 데이터 미비로 오판하면 정상 계정이 영구 스킵된다(설계 §7).
	 */
	@Test
	void 피드_전용_계정은_일부만_NULL이라_데이터_미비가_아니다() {
		Map<String, Object> m = new HashMap<>();
		m.put("views_sample_count", 0);
		m.put("likes_sample_count", 12);
		m.put("comments_sample_count", 12);
		m.put("reels_count", 0);
		m.put("feed_count", 12);
		m.put("median_views", null); // 조회수 관측 없음 — 정상적으로 NULL
		m.put("median_er_pct", 1.8); // 좋아요·댓글은 있어 계산 가능
		m.put("top_views_share_pct", null); // 조회수 합이 없어 정상적으로 NULL
		m.put("window_span_days", 45);

		assertFalse(PerfConfidence.of(m).dataIncomplete());
	}

	@Test
	void 지침_문구가_판정에_맞게_생성된다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 1);
		m.put("window_span_days", 400);

		String block = PerfConfidence.of(m).promptBlock();

		assertTrue(block.contains("조회수 표본이 2건 이하다"), block);
		assertTrue(block.contains("성장세·추세에 대한 서술은 아예 하지 마라"), block);
	}

	@Test
	void 등급이_전부_OK면_지침_블록이_비어있다() {
		assertEquals("", PerfConfidence.of(baseSummary()).promptBlock());
	}
}
