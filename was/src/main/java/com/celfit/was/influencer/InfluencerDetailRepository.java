package com.celfit.was.influencer;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountAnalysis;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 인플루언서 상세 미러 3종(account_summaries·account_category_stats·account_content_series) + accounts
 * + 계정 LLM 카피 이력(account_analyses 최신 1행) 조회.
 * 전부 계약 record로 매핑한다(§4-3, 컴포넌트 순서 = SELECT 컬럼 순서 = V10 DDL). 부재 시 빈 값으로
 * 저하한다(PostDetailRepository와 동일 관용구 — 패키지 분리로 의도적 중복, C1 스펙 §4).
 */
@Repository
public class InfluencerDetailRepository {

	private static final Logger log = LoggerFactory.getLogger(InfluencerDetailRepository.class);
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public InfluencerDetailRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	public Optional<AccountSummary> findSummary(String handle) {
		return safeQuery("account_summaries", Optional::empty, () -> jdbcClient.sql("""
				SELECT handle, followers, follows_count, posts_count, biography,
				       analyzed_count, views_count, metric, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments,
				       trend_direction, trend_change_pct, trend_older_avg, trend_newer_avg,
				       sponsored_count, organic_avg, ad_avg, ad_drop_pct,
				       comparison_organic_count, comparison_ad_count, last_ad_posted_at,
				       last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :handle
				""")
				.param("handle", handle)
				.query(AccountSummary.class)
				.optional());
	}

	public Optional<Account> findAccount(String handle) {
		return safeQuery("accounts", Optional::empty, () -> jdbcClient.sql("""
				SELECT handle, display_name, profile_image_url, followers, external_link
				FROM accounts
				WHERE handle = :handle
				""")
				.param("handle", handle)
				.query(Account.class)
				.optional());
	}

	public List<AccountCategoryStat> findCategoryStats(String handle) {
		return safeQuery("account_category_stats", List::of, () -> jdbcClient.sql("""
				SELECT account_handle, main_group, content_count
				FROM account_category_stats
				WHERE account_handle = :handle
				ORDER BY content_count DESC, main_group
				""")
				.param("handle", handle)
				.query(AccountCategoryStat.class)
				.list());
	}

	public List<AccountContentPoint> findSeries(String handle) {
		return safeQuery("account_content_series", List::of, () -> jdbcClient.sql("""
				SELECT short_code, account_handle, posted_at, content_type, views, likes, comments, sponsored
				FROM account_content_series
				WHERE account_handle = :handle
				ORDER BY posted_at, short_code
				""")
				.param("handle", handle)
				.query(AccountContentPoint.class)
				.list());
	}

	/**
	 * 계정 LLM 카피 — 이력 테이블(account_analyses)에서 계정별 최신 1행(analyzed_at DESC)만 유효.
	 * traits(jsonb)는 record가 List<String>이라 자동 매핑이 안 돼 명시 RowMapper로 파싱한다
	 * (직렬화는 소비자 매핑 계층 몫 — C2 스펙 §3).
	 */
	public Optional<AccountAnalysis> findLatestAnalysis(String handle) {
		return safeQuery("account_analyses", Optional::empty, () -> jdbcClient.sql("""
				SELECT handle, analyzed_at, model, input_last_posted_at, input_analyzed_count,
				       tagline, summary, trend_note, chart_note, traits::text AS traits,
				       ad_headline, pace_note
				FROM account_analyses
				WHERE handle = :handle
				ORDER BY analyzed_at DESC
				LIMIT 1
				""")
				.param("handle", handle)
				.query((rs, rowNum) -> new AccountAnalysis(
						rs.getString("handle"),
						rs.getObject("analyzed_at", OffsetDateTime.class),
						rs.getString("model"),
						rs.getObject("input_last_posted_at", OffsetDateTime.class),
						rs.getObject("input_analyzed_count", Long.class),
						rs.getString("tagline"),
						rs.getString("summary"),
						rs.getString("trend_note"),
						rs.getString("chart_note"),
						parseTraits(rs.getString("traits")),
						rs.getString("ad_headline"),
						rs.getString("pace_note")))
				.optional());
	}

	private List<String> parseTraits(String json) {
		return json == null ? null : objectMapper.readValue(json, STRING_LIST);
	}

	private <T> T safeQuery(String table, Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("{} 조회 실패, 빈 값으로 대체합니다: {}", table, e.getMessage());
			return fallback.get();
		}
	}
}
