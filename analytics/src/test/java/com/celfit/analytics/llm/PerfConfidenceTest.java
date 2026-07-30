package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 성과 요약 통계 왜곡 가드 판정 경계값 (설계 2026-07-30-perf-summary-statistical-guards §3-2·§6):
 * 모수 2/3·5/6 경계, top_views_share_pct 74/75, window_span_days 90 경계(LONG_SPAN 폐지 이후
 * 90일 초과는 전부 UNAVAILABLE), 모수 게이트와 한 건 지배 플래그의 중복 배제, NULL·키 부재
 * 입력에서의 NPE 없는 보수적 등급.
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

	/**
	 * 창 길이 90/91 경계 — LONG_SPAN 폐지(3차 test 실측, 설계 §3-2) 이후 90일 초과는 전부
	 * UNAVAILABLE 하나로 통합됐다. 91일에서 예전엔 LONG_SPAN(값 유지+지시로만 통제)이었는데,
	 * 그 구간에서 지시가 새어나온 실측(0205s.y·02_10.13·119irl)에 따라 91일도 이제 trend_* 값이
	 * 제거되는 UNAVAILABLE이어야 한다.
	 */
	@Test
	void 창_길이_90은_OK_91은_추세_사용불가다() {
		Map<String, Object> m = baseSummary();
		m.put("window_span_days", 90);
		assertEquals(PerfConfidence.TrendValidity.OK, PerfConfidence.of(m).trendValidity());

		m.put("window_span_days", 91);
		assertEquals(PerfConfidence.TrendValidity.UNAVAILABLE, PerfConfidence.of(m).trendValidity());
	}

	/**
	 * 창 길이 365/366 — LONG_SPAN 폐지로 이 경계 자체가 무의미해졌다(90일 초과는 365일 이하든
	 * 초과든 전부 UNAVAILABLE). 90일 초과 구간 내부에서 동작 차이가 없다는 것 자체를 단언한다.
	 */
	@Test
	void 창_길이_365도_366도_추세_사용불가로_동일하다() {
		Map<String, Object> m = baseSummary();
		m.put("window_span_days", 365);
		assertEquals(PerfConfidence.TrendValidity.UNAVAILABLE, PerfConfidence.of(m).trendValidity());

		m.put("window_span_days", 366);
		assertEquals(PerfConfidence.TrendValidity.UNAVAILABLE, PerfConfidence.of(m).trendValidity());
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
		assertEquals(PerfConfidence.TrendValidity.UNAVAILABLE, c.trendValidity());
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
		assertEquals(PerfConfidence.TrendValidity.UNAVAILABLE, c.trendValidity());
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
	 * 배포 과도기 감지(설계 2026-07-30-perf-summary-statistical-guards §7·§3-3 재정의) — 신뢰도
	 * always-strip 7컬럼({@link PerfConfidence#CONFIDENCE_COLUMNS}, §3-3 재정의로 median_views·
	 * median_er_pct는 여기서 빠졌다)이 전부 NULL/부재면 "표본 부족"이 아니라 "뷰 미적용/미러 실패"로
	 * 판단해야 한다. 뷰의 count(*) FILTER 계열 5컬럼은 분석 이력이 있는 계정에서 정상적으로는 절대
	 * NULL이 될 수 없으므로(매치 0건이면 0을 반환), 7개 전부 NULL은 미러 갭의 신호로만 성립한다 —
	 * median 2컬럼은 정상 운영(피드 전용 계정 등)에서도 NULL일 수 있어 이 판정에서 뺐다.
	 */
	@Test
	void 신뢰도_컬럼_7개가_전부_NULL이면_데이터_미비로_판정된다() {
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

	/**
	 * 회귀 방지 — email(V46, 스펙 2026-07-30-influencer-email-from-bio)은 CONFIDENCE_COLUMNS에
	 * 섞이지 않았으므로 dataIncomplete() 판정에 전혀 관여하면 안 된다. email 유무가 판정을
	 * 바꾼다면 그 자체가 email이 판정 재료로 잘못 섞였다는 신호다(요구사항 설계 §3-3 재정의 —
	 * "판정 입력이 아니고 정상 계정에서도 흔히 NULL이라 섞으면 의미가 흐려진다").
	 */
	@Test
	void email_컬럼_유무가_dataIncomplete_판정에_영향을_주지_않는다() {
		Map<String, Object> ok = baseSummary(); // 7개 신뢰도 컬럼 정상 → 데이터 미비 아님
		Map<String, Object> okWithEmail = new HashMap<>(ok);
		okWithEmail.put("email", "person@example.com");
		assertFalse(PerfConfidence.of(ok).dataIncomplete());
		assertFalse(PerfConfidence.of(okWithEmail).dataIncomplete());

		Map<String, Object> gap = new HashMap<>();
		for (String key : PerfConfidence.CONFIDENCE_COLUMNS) {
			gap.put(key, null);
		}
		Map<String, Object> gapWithEmail = new HashMap<>(gap);
		gapWithEmail.put("email", "person@example.com"); // 미러 갭인데 email만 채워진 비정상 상태를 가정해도
		assertTrue(PerfConfidence.of(gap).dataIncomplete());
		assertTrue(PerfConfidence.of(gapWithEmail).dataIncomplete(),
				"email 값 존재가 '미러 갭 아님'의 근거가 되면 안 된다");
	}

	@Test
	void 지침_문구가_판정에_맞게_생성된다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 1);
		m.put("window_span_days", 400);

		String block = PerfConfidence.of(m).promptBlock();

		// "언급하지 마라"가 아니라 "데이터 자체가 없다"는 사실 서술이어야 한다(§1 실측 보완 — "있지만
		// 언급 마라"는 test 실측에서 안 지켜졌다).
		assertTrue(block.contains("조회수 데이터는 제공되지 않는다"), block);
		assertTrue(block.contains("성장세·추세에 대한 서술은 아예 하지 마라"), block);
	}

	@Test
	void 등급이_전부_OK면_지침_블록이_비어있다() {
		assertEquals("", PerfConfidence.of(baseSummary()).promptBlock());
	}

	/**
	 * LONG_SPAN 폐지(3차 test 실측) 이후 "완만하게 표현하라"류 톤 연화 지시는 어떤 창 길이에서도
	 * 더 이상 나오면 안 된다 — 90일 초과는 이제 값 제거(UNAVAILABLE)로만 통제하고, 지시로 절반쯤
	 * 통제하던 중간 단계 자체가 사라졌다.
	 */
	@Test
	void 지침_문구에_완만하게_계열_표현이_없다() {
		Map<String, Object> m = baseSummary();
		for (int windowSpanDays : new int[] {91, 200, 365, 366, 1000}) {
			m.put("window_span_days", windowSpanDays);
			String block = PerfConfidence.of(m).promptBlock();
			assertFalse(block.contains("완만하게"), windowSpanDays + "일: " + block);
			assertFalse(block.contains("장기간에 걸친 변화"), windowSpanDays + "일: " + block);
			assertTrue(block.contains("성장세·추세에 대한 서술은 아예 하지 마라"), windowSpanDays + "일: " + block);
		}
	}

	/**
	 * WEAK 지침은 완화 표현이 지표 서술 앞에 오고, 어느 지표가 약한지 지표명을 담아야 한다(2026-07-30
	 * test 실측 {@code 0n_neww}: "안정적인 흐름을 보입니다. 릴스 콘텐츠의 경우 표본이 적어 단정하기
	 * 어렵지만…"처럼 단정을 먼저 하고 뒤늦게 발뺌하는 순서로 나와 지침이 무력화됐다).
	 */
	@Test
	void WEAK_지침은_완화_표현이_먼저_오고_지표명을_담는다() {
		Map<String, Object> m = baseSummary();
		m.put("likes_sample_count", 4);

		String directive = PerfConfidence.of(m).directives().stream()
				.filter(d -> d.contains("좋아요"))
				.findFirst().orElseThrow();

		assertTrue(directive.startsWith("\"좋아요 표본이 적어 단정하기 어렵지만\""), directive);
		assertTrue(directive.contains("좋아요 수준을 언급하라"), directive);
	}

	/** 포맷 비교 금지 지침은 부정형 회피 서술("차이가 없다" 등)도 비교 진술로 명시해 막아야 한다
	 *  (2026-07-30 test 실측 {@code 0_tsuki2}: "반응 차이는 뚜렷하게 나타나지 않으며"로 회피). */
	@Test
	void 포맷_비교_금지_지침이_회피_서술도_금지한다() {
		Map<String, Object> m = baseSummary();
		m.put("reels_count", 2);

		String directive = PerfConfidence.of(m).directives().stream()
				.filter(d -> d.contains("포맷"))
				.findFirst().orElseThrow();

		assertTrue(directive.contains("차이가 없다"), directive);
		assertTrue(directive.contains("뚜렷하지 않다"), directive);
	}

	/**
	 * 조건부 제거 대상(§1 실측 보완) — 추세 UNAVAILABLE이면 추세 4키가, 지표가 INSUFFICIENT면 그
	 * 지표의 계정 집계 키가 excludedSummaryKeys()에 잡혀야 한다.
	 */
	@Test
	void 추세_사용불가면_추세_4키가_제거_대상에_잡힌다() {
		Map<String, Object> m = baseSummary();
		m.put("window_span_days", 400);

		List<String> excluded = PerfConfidence.of(m).excludedSummaryKeys();

		assertTrue(excluded.containsAll(
				List.of("trend_direction", "trend_change_pct", "trend_older_avg", "trend_newer_avg")), excluded.toString());
	}

	/**
	 * LONG_SPAN 폐지(3차 test 실측) 핵심 회귀 방지 — 90일 초과는 91일이든 366일이든 값을 남긴 채
	 * 지시로만 통제하던 옛 LONG_SPAN 구간(90~365일)이 이제 UNAVAILABLE과 동일하게 trend_* 4키를
	 * 제거해야 한다. 90일 정확히는 여전히 남아야 한다(경계).
	 */
	@Test
	void 창_91일과_366일_둘_다_추세_4키가_제거되고_90일은_남는다() {
		Map<String, Object> m = baseSummary();

		m.put("window_span_days", 91);
		assertTrue(PerfConfidence.of(m).excludedSummaryKeys().containsAll(
				List.of("trend_direction", "trend_change_pct", "trend_older_avg", "trend_newer_avg")),
				PerfConfidence.of(m).excludedSummaryKeys().toString());

		m.put("window_span_days", 366);
		assertTrue(PerfConfidence.of(m).excludedSummaryKeys().containsAll(
				List.of("trend_direction", "trend_change_pct", "trend_older_avg", "trend_newer_avg")),
				PerfConfidence.of(m).excludedSummaryKeys().toString());

		m.put("window_span_days", 90);
		List<String> excludedAt90 = PerfConfidence.of(m).excludedSummaryKeys();
		assertFalse(excludedAt90.contains("trend_direction"), excludedAt90.toString());
		assertFalse(excludedAt90.contains("trend_change_pct"), excludedAt90.toString());
		assertFalse(excludedAt90.contains("trend_older_avg"), excludedAt90.toString());
		assertFalse(excludedAt90.contains("trend_newer_avg"), excludedAt90.toString());
	}

	@Test
	void INSUFFICIENT_지표의_계정_집계_키가_제거_대상에_잡힌다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 2);
		m.put("likes_sample_count", 1);
		m.put("comments_sample_count", 2);

		List<String> excluded = PerfConfidence.of(m).excludedSummaryKeys();

		assertTrue(excluded.containsAll(List.of("avg_views", "views_per_follower", "avg_likes", "avg_comments")),
				excluded.toString());
	}

	/** WEAK(3~5)·OK는 톤 연화·정상 서술에 값이 필요하므로 제거 대상에 잡히면 안 된다. */
	@Test
	void WEAK와_OK는_집계_키를_제거하지_않는다() {
		Map<String, Object> m = baseSummary(); // 전부 10(OK), window_span_days=30(OK)
		assertTrue(PerfConfidence.of(m).excludedSummaryKeys().isEmpty());

		m.put("views_sample_count", 4); // WEAK
		m.put("likes_sample_count", 5); // WEAK
		m.put("comments_sample_count", 3); // WEAK
		assertTrue(PerfConfidence.of(m).excludedSummaryKeys().isEmpty(), PerfConfidence.of(m).excludedSummaryKeys().toString());
	}

	/** 가드 미적용(none())에서는 아무 키도 제거 대상이 아니다 — 기존 호출부 온전성. */
	@Test
	void none은_제거_대상_키가_없다() {
		assertTrue(PerfConfidence.none().excludedSummaryKeys().isEmpty());
		assertTrue(PerfConfidence.none().excludedPostFields().isEmpty());
	}

	@Test
	void INSUFFICIENT_지표의_게시물_필드가_제거_대상에_잡힌다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 2);

		List<String> excluded = PerfConfidence.of(m).excludedPostFields();

		assertEquals(List.of("views"), excluded);
	}

	/**
	 * always-strip 재정의(설계 §3-3) — 항상 제거할 판정 전용 입력은 7개뿐이고, median_views·
	 * median_er_pct는 여기 없어야 한다. 이 목록에 median이 들어 있으면 "수준 판정 근거를 median으로
	 * 옮긴다"는 간판 결정이 무력화된다(777minseo 사례).
	 */
	@Test
	void always_strip_목록은_7개고_median_두_컬럼은_포함되지_않는다() {
		assertEquals(7, PerfConfidence.CONFIDENCE_COLUMNS.size());
		assertFalse(PerfConfidence.CONFIDENCE_COLUMNS.contains("median_views"));
		assertFalse(PerfConfidence.CONFIDENCE_COLUMNS.contains("median_er_pct"));
		assertTrue(PerfConfidence.CONFIDENCE_COLUMNS.containsAll(List.of(
				"views_sample_count", "likes_sample_count", "comments_sample_count",
				"reels_count", "feed_count", "top_views_share_pct", "window_span_days")));
	}

	/**
	 * median을 "선택지"가 아니라 "유일한 근거"로 만든다(설계 §3-3 재정의) — median_views가
	 * 존재(non-NULL)하면 대응 평균 키(avg_views·views_per_follower)가 제거 대상에 들어가야
	 * LLM이 둘 중 avg를 골라 쓸 여지가 없어진다. median_views가 NULL(키 자체가 없는 경우 포함,
	 * 조회수 관측이 없는 계정)이면 avg 폴백 경로를 살려둬야 한다(실측 949/6,653건).
	 */
	@Test
	void median_views가_있으면_대응_avg가_제거되고_NULL이면_남는다() {
		Map<String, Object> m = baseSummary(); // views_sample_count=10(OK) — INSUFFICIENT와 무관
		m.put("median_views", 9000L);
		List<String> excludedWithMedian = PerfConfidence.of(m).excludedSummaryKeys();
		assertTrue(excludedWithMedian.containsAll(List.of("avg_views", "views_per_follower")),
				excludedWithMedian.toString());

		m.remove("median_views"); // 키 부재 = NULL과 동일 취급
		List<String> excludedWithoutMedian = PerfConfidence.of(m).excludedSummaryKeys();
		assertFalse(excludedWithoutMedian.contains("avg_views"), excludedWithoutMedian.toString());
		assertFalse(excludedWithoutMedian.contains("views_per_follower"), excludedWithoutMedian.toString());
	}

	/** median_er_pct도 같은 원칙 — 대응 평균 키는 avg_er_pct 하나뿐이다. */
	@Test
	void median_er_pct가_있으면_avg_er_pct가_제거되고_NULL이면_남는다() {
		Map<String, Object> m = baseSummary();
		m.put("median_er_pct", 2.0);
		assertTrue(PerfConfidence.of(m).excludedSummaryKeys().contains("avg_er_pct"));

		m.remove("median_er_pct");
		assertFalse(PerfConfidence.of(m).excludedSummaryKeys().contains("avg_er_pct"));
	}

	/**
	 * 규칙 합성(설계 §3-3 요구사항 (3)) — 조회수가 INSUFFICIENT(모수 ≤2)면 median_views가
	 * 존재하더라도 함께 제거돼야 한다. 모수가 부족하면 중앙값도 판정 근거가 될 수 없다 — "median
	 * 존재 시 제거" 규칙과 "INSUFFICIENT 지표는 집계 키 전부 제거" 규칙이 중복 없이 합성되는지
	 * 확인한다(둘 다 median_views를 한 번만 제거 목록에 올리면 된다).
	 */
	@Test
	void 조회수_INSUFFICIENT면_median_views가_있어도_함께_제거된다() {
		Map<String, Object> m = baseSummary();
		m.put("views_sample_count", 1); // INSUFFICIENT
		m.put("median_views", 9000L); // 모수 1인 계정은 median이 그 1건 값 그대로일 수 있다

		List<String> excluded = PerfConfidence.of(m).excludedSummaryKeys();

		assertTrue(excluded.containsAll(List.of("avg_views", "views_per_follower", "median_views")),
				excluded.toString());
	}
}
