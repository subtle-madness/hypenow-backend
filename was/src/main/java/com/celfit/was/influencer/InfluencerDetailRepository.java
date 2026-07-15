package com.celfit.was.influencer;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 인플루언서 상세 미러 3종(account_summaries·account_category_stats·account_content_series) + accounts 조회.
 * 전부 계약 record로 매핑한다(§4-3, 컴포넌트 순서 = SELECT 컬럼 순서 = V10 DDL). 부재 시 빈 값으로
 * 저하한다(PostDetailRepository와 동일 관용구 — 패키지 분리로 의도적 중복, C1 스펙 §4).
 */
@Repository
public class InfluencerDetailRepository {

	private static final Logger log = LoggerFactory.getLogger(InfluencerDetailRepository.class);

	private final JdbcClient jdbcClient;

	public InfluencerDetailRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
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
				SELECT handle, display_name, profile_image_url, followers
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

	private <T> T safeQuery(String table, Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("{} 조회 실패, 빈 값으로 대체합니다: {}", table, e.getMessage());
			return fallback.get();
		}
	}
}
