package com.celfit.analytics.analyze;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * raw 기준선 뷰 로더 — 통합 분석 잡과 해석 문구 갱신 잡이 공유한다.
 *
 * <p>뷰 평가가 운영 실측 분 단위(07-19, 27k 기준 4.5분)라 건당 조회를 반복하면 배치가 뷰 스캔에
 * 잠긴다. 1회 평가 후 메모리 맵 조회로 대체한다.
 * PG 타입이 numeric·bigint·smallint로 섞여 있어 전부 BigDecimal로 읽어 변환한다(기존 관용구).
 */
final class BaselineLoader {

	private BaselineLoader() {}

	/** 계정 평균(account_handle 키) — 최근창 밖 콘텐츠에 붙일 앵커. rank는 계정 단위가 아니라 null. */
	static Map<String, Baseline> byAccount(JdbcTemplate raw) {
		Map<String, Baseline> out = new LinkedHashMap<>();
		raw.query("""
				SELECT account_handle, recent_reels_avg_views, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_account_baseline""",
				rs -> {
					out.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), null, intOf(rs.getBigDecimal(3)),
							intOf(rs.getBigDecimal(4)), rs.getBigDecimal(5),
							longOf(rs.getBigDecimal(6)), longOf(rs.getBigDecimal(7)),
							intOf(rs.getBigDecimal(8)), longOf(rs.getBigDecimal(9)),
							longOf(rs.getBigDecimal(10))));
				});
		return out;
	}

	/** 콘텐츠 키 기준선(최근창 안 게시물만, rank 포함) — 있으면 계정 평균보다 우선. */
	static Map<String, Baseline> byShortCode(JdbcTemplate raw) {
		Map<String, Baseline> out = new LinkedHashMap<>();
		raw.query("""
				SELECT short_code, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline""",
				rs -> {
					out.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)),
							intOf(rs.getBigDecimal(4)), intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)),
							longOf(rs.getBigDecimal(11))));
				});
		return out;
	}

	static Long longOf(BigDecimal v) {
		return v == null ? null : v.longValue();
	}

	static Integer intOf(BigDecimal v) {
		return v == null ? null : v.intValue();
	}
}
