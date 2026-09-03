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

	/**
	 * accounts ⋈ account_summaries — 프로필 1행. 아카이브된 프로필 이미지는 /img/ 상대경로로 폴백.
	 * email은 account_summaries.email(analytics V46, 소개글 정규식 파싱 — 스펙
	 * 2026-07-30-influencer-email-from-bio-design.md) — 발굴 목록(6.21)·유사 카드(6.23)가 읽는 컬럼과
	 * 같은 소스다(2026-09-03 FE 피드백 #1: 상세만 항상 null이던 결함 수리).
	 */
	public Optional<ProfileRow> findProfile(String handle) {
		return jdbcClient.sql("""
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers, a.external_link,
				       s.posts_count, s.follows_count, s.biography, s.email
				FROM accounts a
				LEFT JOIN account_summaries s ON s.handle = a.handle
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE a.handle = :h
				""").param("h", handle).query(ProfileRow.class).optional();
	}

	/**
	 * 최근 12개 Content 카드 — 게시일 내림차순, 분석 미완 게시물도 포함(LEFT JOIN).
	 * 목록(6.1)은 분석 완료만 노출(INNER)이지만, 인플루언서 상세는 "실제 최신 12개"를 보여준다.
	 * 미분석 게시물은 카드의 분석 필드(main_category·ad_type·brands 등)가 null/빈배열이 된다.
	 * 게시물 단위 뷰티 판정(is_beauty)은 걸지 않는다 — 성과 지표(account_summaries·시계열)의
	 * 모수인 "실제 최신 12개"와 화면 카드가 1:1로 맞아야 하고(v2 리포트 차트 contentIds가
	 * 이 카드와 short_code 조인), 비뷰티 게시물 제외는 랭킹(6.1)만의 정책이다(07-28 결정).
	 */
	public List<ContentCardRow> findRecentCards(String handle) {
		return jdbcClient.sql(ContentCardRow.SELECT + """

				FROM contents c
				LEFT JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				""" + ContentCardRow.IMAGE_JOINS + """
				WHERE c.account_handle = :h
				ORDER BY c.posted_at DESC, c.short_code
				LIMIT 12
				""").param("h", handle).query(ContentCardRow.class).list();
	}

	public record ProfileRow(String handle, String displayName, String profileImageUrl,
			Long followers, String externalLink, Long postsCount, Long followsCount, String biography,
			String email) {
	}
}
