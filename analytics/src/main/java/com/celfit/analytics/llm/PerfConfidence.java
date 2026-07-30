package com.celfit.analytics.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 성과 요약 통계 왜곡 가드 — 결정론적 등급 판정
 * (설계 2026-07-30-perf-summary-statistical-guards §2·§3-2).
 *
 * <p>{@code account_summaries} 스냅샷(Map, snake_case 컬럼명)에서 지표별 표본 신뢰도·추세
 * 유효성·한 건 지배·포맷 비교 가능성을 판정한다. 임계값은 §2 실측 분위수에 붙이되 SQL이 아니라
 * 여기 상수로 관리한다 — 운영 뷰 적용은 수동 런북이라 임계값 조정마다 운영 DDL이 붙는 것을 피하려는
 * 설계 의도(§3 서문).
 *
 * <p><b>NULL 안전</b>: median·share·span 계열 컬럼은 관측 없는 계정에서 NULL이고, 새 컬럼이 아직
 * 미러되지 않은 과도기에는 맵에 키 자체가 없을 수 있다. 판정 불가는 전부 "그 지표에 대한 서술을
 * 억제하는" 방향의 보수적 등급으로 접는다({@link #gradeOf}는 null을 INSUFFICIENT로, {@link #trendOf}는
 * null을 TOO_LONG으로, 포맷 비교는 null을 비교 불가로 처리) — 절대 NPE를 내지 않는다.
 *
 * <p><b>배포 과도기 감지({@link #dataIncomplete()})</b>: 위 등급 접힘과는 별개로, 9개 신 컬럼이
 * <b>전부</b> NULL/부재면 "표본이 진짜 부족한 계정"이 아니라 "뷰 선적용 없이 마이그레이션이 먼저
 * 배포돼 미러가 새 컬럼을 채우지 못한 상태"로 본다. 이 둘은 구분된다 — {@code views_sample_count}·
 * {@code likes_sample_count}·{@code comments_sample_count}·{@code reels_count}·{@code feed_count}는
 * 뷰(10_account_detail.sql)에서 {@code count(*) FILTER (...)}로 계산되는데, 이 집계 함수는 매치되는
 * 행이 0건이어도 SQL NULL이 아니라 정수 0을 반환한다 — 계정에 분석 이력이 하나라도 있으면
 * (GROUP BY 자체가 성립하는 조건) 이 5개 컬럼은 절대 NULL일 수 없다. 예를 들어 피드 전용 계정은
 * {@code views_sample_count=0}·{@code reels_count=0}·{@code feed_count=12}처럼 값이 채워지지,
 * NULL이 되지 않는다. 따라서 9개 전부 NULL은 정상 운영에서 발생 불가능하고, 미러가 이 컬럼들에
 * 아무것도 쓰지 못한 배포 과도기에서만 성립한다 — 판정 근거는 설계
 * 2026-07-30-perf-summary-statistical-guards-design.md 배포 순서 절 참조.
 *
 * <p>{@link #none()}(가드 미적용 기본값)과 {@link #dataIncomplete()}는 서로 다른 상태다 — {@code none()}은
 * "이 호출부는 신뢰도 판정 자체를 적용하지 않는다"는 뜻이고, {@code dataIncomplete()}는 "판정을
 * 적용했는데 입력 자체가 배포 과도기라 신뢰할 수 없다"는 뜻이다. 후자는 카피 생성을 아예 건너뛰어야
 * 한다는 신호로 쓰인다({@code AccountAnalysisJob}·{@code ClaudeBurstRunner} 참조).
 */
public final class PerfConfidence {

	/** 지표별 실질 모수 등급 (§3-2 표) — ≤2 INSUFFICIENT, 3~5 WEAK, ≥6 OK. */
	public enum Grade { OK, WEAK, INSUFFICIENT }

	/** 성장세 유효성 — 건수가 아니라 시간 축(window_span_days) 기준. */
	public enum TrendValidity { OK, LONG_SPAN, TOO_LONG }

	private static final int INSUFFICIENT_MAX = 2;
	private static final int WEAK_MAX = 5;
	private static final int DOMINANCE_MIN_SAMPLE = 3;
	private static final int DOMINANCE_SHARE_PCT = 75;
	private static final int LONG_SPAN_MAX_DAYS = 90;
	private static final int TOO_LONG_DAYS = 365;
	private static final int FORMAT_COMPARABLE_MIN = 3;

	/**
	 * 판정 재료 9컬럼(설계 §3-1) — 이 목록이 정본이다. {@code AccountAdCanon}이 LLM 프롬프트
	 * 사본에서 같은 컬럼을 제거할 때도 이 목록을 재사용해, 판정용 컬럼 목록이 두 곳에서 따로
	 * 놀다 어긋나는 드리프트를 막는다.
	 */
	public static final List<String> CONFIDENCE_COLUMNS = List.of(
			"views_sample_count", "likes_sample_count", "comments_sample_count",
			"reels_count", "feed_count", "median_views", "median_er_pct",
			"top_views_share_pct", "window_span_days");

	private final Grade viewsGrade;
	private final Grade likesGrade;
	private final Grade commentsGrade;
	private final TrendValidity trendValidity;
	private final boolean singlePostDominance;
	private final boolean formatComparable;
	private final boolean dataIncomplete;

	private PerfConfidence(Grade viewsGrade, Grade likesGrade, Grade commentsGrade,
			TrendValidity trendValidity, boolean singlePostDominance, boolean formatComparable,
			boolean dataIncomplete) {
		this.viewsGrade = viewsGrade;
		this.likesGrade = likesGrade;
		this.commentsGrade = commentsGrade;
		this.trendValidity = trendValidity;
		this.singlePostDominance = singlePostDominance;
		this.formatComparable = formatComparable;
		this.dataIncomplete = dataIncomplete;
	}

	/** account_summaries 스냅샷(SELECT * 결과)에서 등급을 계산한다. */
	public static PerfConfidence of(Map<String, Object> summary) {
		Long viewsN = asLong(summary, "views_sample_count");
		Long likesN = asLong(summary, "likes_sample_count");
		Long commentsN = asLong(summary, "comments_sample_count");
		Grade views = gradeOf(viewsN);
		Grade likes = gradeOf(likesN);
		Grade comments = gradeOf(commentsN);
		TrendValidity trend = trendOf(asLong(summary, "window_span_days"));
		boolean dominance = dominanceOf(viewsN, asInt(summary, "top_views_share_pct"));
		boolean comparable = comparableOf(asLong(summary, "reels_count"), asLong(summary, "feed_count"));
		boolean dataIncomplete = CONFIDENCE_COLUMNS.stream().allMatch(k -> summary.get(k) == null);
		return new PerfConfidence(views, likes, comments, trend, dominance, comparable, dataIncomplete);
	}

	/** 신뢰도 제약 없음 — 판정을 아예 건너뛰는 경로(버스트 export 등 구 호출부) 호환용 기본값. */
	public static PerfConfidence none() {
		return new PerfConfidence(Grade.OK, Grade.OK, Grade.OK, TrendValidity.OK, false, true, false);
	}

	public Grade viewsGrade() {
		return viewsGrade;
	}

	public Grade likesGrade() {
		return likesGrade;
	}

	public Grade commentsGrade() {
		return commentsGrade;
	}

	public TrendValidity trendValidity() {
		return trendValidity;
	}

	public boolean singlePostDominance() {
		return singlePostDominance;
	}

	public boolean formatComparable() {
		return formatComparable;
	}

	/**
	 * 배포 과도기 감지 — 9개 신뢰도 재료 컬럼이 전부 NULL/부재면 true(클래스 javadoc 참조).
	 * 호출부는 이 값이 true면 카피 생성을 건너뛰어야 한다 — 저품질 문구를 최신 버전으로 찍어
	 * 영구 고정하는 것보다 아무것도 안 쓰는 게 안전하다(뷰 적용 후 자연 재대상됨).
	 */
	public boolean dataIncomplete() {
		return dataIncomplete;
	}

	private static Grade gradeOf(Long sampleCount) {
		if (sampleCount == null || sampleCount <= INSUFFICIENT_MAX) {
			return Grade.INSUFFICIENT;
		}
		if (sampleCount <= WEAK_MAX) {
			return Grade.WEAK;
		}
		return Grade.OK;
	}

	private static TrendValidity trendOf(Long windowSpanDays) {
		if (windowSpanDays == null || windowSpanDays > TOO_LONG_DAYS) {
			return TrendValidity.TOO_LONG;
		}
		if (windowSpanDays > LONG_SPAN_MAX_DAYS) {
			return TrendValidity.LONG_SPAN;
		}
		return TrendValidity.OK;
	}

	/**
	 * 한 건 지배 — 조회수 실질 모수가 3건 이상일 때만 판정한다. 모수 1~2건은 점유율이 자동
	 * 100%라 모수 게이트(gradeOf)와 뜻이 겹치므로 이 플래그는 세우지 않는다(§3-2).
	 * top_views_share_pct가 NULL(과도기 미러 지연 등)이면 근거 없이 "1건이 끌어올린 구조"라는
	 * 구체적 서술을 지시하지 않도록 false로 접는다 — 증거 없는 주장을 만들지 않는다는 원칙.
	 */
	private static boolean dominanceOf(Long viewsSampleCount, Integer topViewsSharePct) {
		if (viewsSampleCount == null || viewsSampleCount < DOMINANCE_MIN_SAMPLE) {
			return false;
		}
		return topViewsSharePct != null && topViewsSharePct >= DOMINANCE_SHARE_PCT;
	}

	private static boolean comparableOf(Long reelsCount, Long feedCount) {
		if (reelsCount == null || feedCount == null) {
			return false;
		}
		return reelsCount >= FORMAT_COMPARABLE_MIN && feedCount >= FORMAT_COMPARABLE_MIN;
	}

	private static Long asLong(Map<String, Object> summary, String key) {
		return summary.get(key) instanceof Number n ? n.longValue() : null;
	}

	private static Integer asInt(Map<String, Object> summary, String key) {
		return summary.get(key) instanceof Number n ? n.intValue() : null;
	}

	/** 위 등급을 한국어 프롬프트 지침 문장으로 변환한다 — 각 지침은 독립적이라 여러 개가 동시에 뜰 수 있다. */
	public List<String> directives() {
		List<String> out = new ArrayList<>();
		addMetricDirective(out, viewsGrade, "조회수");
		addMetricDirective(out, likesGrade, "좋아요");
		addMetricDirective(out, commentsGrade, "댓글");
		if (singlePostDominance) {
			out.add("조회수가 특정 1건에 쏠려 있다 — 평균적 성과로 서술하지 말고 대표작 1건이 "
					+ "끌어올린 구조라는 관점으로 써라.");
		}
		if (trendValidity == TrendValidity.TOO_LONG) {
			out.add("게시물 간 기간이 1년을 넘는다 — 성장세·추세에 대한 서술은 아예 하지 마라.");
		} else if (trendValidity == TrendValidity.LONG_SPAN) {
			out.add("게시물 간 기간이 길다(3개월~1년) — 최근 추세로 단정하지 말고 \"장기간에 걸친 변화\"처럼 완만하게 표현하라.");
		}
		if (!formatComparable) {
			out.add("릴스 또는 피드 게시물이 3건 미만이다 — 포맷(릴스/피드)별 반응 차이는 언급하지 마라.");
		}
		return out;
	}

	private static void addMetricDirective(List<String> out, Grade grade, String label) {
		switch (grade) {
			case INSUFFICIENT -> out.add(label + " 표본이 2건 이하다 — " + label + " 수준에 대한 서술은 아예 하지 마라.");
			case WEAK -> out.add(label + " 표본이 적다(3~5건) — " + label
					+ " 수준을 언급하되 \"표본이 적어 단정하기 어렵지만\"처럼 톤을 낮춰라.");
			case OK -> {
			}
		}
	}

	/** 프롬프트에 그대로 삽입할 블록 — 해당 지침이 없으면 빈 문자열(섹션 자체를 만들지 않는다). */
	public String promptBlock() {
		List<String> d = directives();
		if (d.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("신뢰도 지침 — 아래에 해당하면 반드시 지켜라:\n");
		for (String line : d) {
			sb.append("  - ").append(line).append('\n');
		}
		return sb.toString().stripTrailing();
	}
}
