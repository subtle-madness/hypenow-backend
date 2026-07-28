package com.celfit.was.v1.brand;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 브랜드 → 협업 인플루언서 크로스 계정 조회. detected_brands(캡션 분류) 정본. */
@Repository
public class V1BrandRepository {

	private final JdbcClient jdbcClient;

	public V1BrandRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 브랜드명으로 협업 인플루언서 집계 — 특정 계정이 아닌 **브랜드 스코프의 크로스 계정 조회**
	 * (account_content_series × content_analyses를 핸들 무관하게 전체 스캔해 detected_brands에
	 * 해당 브랜드가 있는 광고 게시물을 계정별로 묶는다). 분석 결과끼리 조인(§4-4 허용).
	 * 브랜드/ad_type 인덱스 없이 sponsored 전량을 스캔 — 현재 규모(content_analyses ~25k행)에서는
	 * 허용 가능한 수준이나, 몇 배 이상 커지면 ad_type·detected_brands GIN 인덱스 재검토 필요.
	 * LIMIT 20은 페이지네이션 없는 표시 상한(스펙 임의값, 브랜드 칩 호버 미리보기 용도).
	 */
	public List<BrandInfluencer> findInfluencers(String brand) {
		return jdbcClient.sql("""
				SELECT s.account_handle AS influencer_id, ac.display_name AS name,
				       COALESCE('/img/' || ip.object_path, ac.profile_image_url) AS profile_image_url,
				       ac.followers, count(*) AS collab_count,
				       to_char(max(s.posted_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') AS last_collab_at
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				JOIN accounts ac ON ac.handle = s.account_handle
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = s.account_handle
				CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				WHERE an.ad_type = 'sponsored' AND b->>'name' = :brand
				GROUP BY 1, 2, ip.object_path, ac.profile_image_url, ac.followers
				ORDER BY collab_count DESC, max(s.posted_at) DESC
				LIMIT 20
				""").param("brand", brand).query(BrandInfluencer.class).list();
	}
}
