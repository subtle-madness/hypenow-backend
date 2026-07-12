package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 서빙 미러 3종 조회. 계약 record로 매핑하고(§4-3), 미러 부재 시 빈 값으로 저하한다(대시보드 컨벤션). */
@Repository
public class PostDetailRepository {

	private static final Logger log = LoggerFactory.getLogger(PostDetailRepository.class);

	private final JdbcClient jdbcClient;

	public PostDetailRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<Content> findContent(String shortCode) {
		return safeQuery("contents", Optional::empty, () -> jdbcClient.sql("""
				SELECT short_code, account_handle, thumbnail_url, caption, posted_at,
				       content_type, video_duration, original_url,
				       views, likes, comments, hype_score
				FROM contents
				WHERE short_code = :shortCode
				""")
				.param("shortCode", shortCode)
				.query(Content.class)
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

	public List<ContentComment> findComments(String shortCode) {
		return safeQuery("content_comments", List::of, () -> jdbcClient.sql("""
				SELECT id, short_code, author_masked, body, like_count
				FROM content_comments
				WHERE short_code = :shortCode
				ORDER BY like_count DESC NULLS LAST, id
				""")
				.param("shortCode", shortCode)
				.query(ContentComment.class)
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
