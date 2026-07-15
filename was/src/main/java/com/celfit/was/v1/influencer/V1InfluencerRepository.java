package com.celfit.was.v1.influencer;

import com.celfit.was.v1.content.ContentCardRow;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class V1InfluencerRepository {

	private final JdbcClient jdbcClient;

	public V1InfluencerRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** accounts ⋈ account_summaries — 프로필 1행. */
	public Optional<ProfileRow> findProfile(String handle) {
		return jdbcClient.sql("""
				SELECT a.handle, a.display_name, a.profile_image_url, a.followers, a.external_link,
				       s.posts_count, s.follows_count, s.biography
				FROM accounts a
				LEFT JOIN account_summaries s ON s.handle = a.handle
				WHERE a.handle = :h
				""").param("h", handle).query(ProfileRow.class).optional();
	}

	/** 최근 12개 Content 카드 — 분석 완료 콘텐츠만(카드 필드가 분석 산출이므로), 게시일 내림차순. */
	public List<ContentCardRow> findRecentCards(String handle) {
		return jdbcClient.sql(ContentCardRow.SELECT + """

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				WHERE c.account_handle = :h
				ORDER BY c.posted_at DESC, c.short_code
				LIMIT 12
				""").param("h", handle).query(ContentCardRow.class).list();
	}

	public record ProfileRow(String handle, String displayName, String profileImageUrl,
			Long followers, String externalLink, Long postsCount, Long followsCount, String biography) {
	}
}
