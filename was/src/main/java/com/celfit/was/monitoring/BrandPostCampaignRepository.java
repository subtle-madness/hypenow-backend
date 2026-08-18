package com.celfit.was.monitoring;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.brand_post_campaigns CRUD(2026-08-18 direct 통합 §결정 3) — 브랜드 풀 게시물(tagged·direct
 * 공통)↔캠페인 N:M 링크. 캠페인은 서비스 데이터라 monitoring이 아니라 여기 둔다(시스템 경계) —
 * monitoring brand_tagged_post에는 campaign_id가 없다.
 *
 * <p>이번 릴리스에서 행을 만드는 경로는 <b>직접 등록 시 campaignId 파라미터</b>와 <b>이관 잡</b>
 * 둘뿐이다(부착·해제 API는 범위 밖 — 설계 §5). {@code campaign_id} FK에는 CASCADE를 걸지 않는다 —
 * {@code ArchiveCascadeReachabilityTest}가 app.monitoring_campaigns의 CASCADE 자식이 0개일 것을
 * 강제하므로, 캠페인 삭제 경로({@code CampaignRepository.delete})가 이 테이블을 명시적으로
 * 아카이브·삭제해야 한다(T13 — 이 트랙의 범위 밖, 카탈로그 등재는 후속).
 */
@Repository
public class BrandPostCampaignRepository {

	private final JdbcClient jdbcClient;

	public BrandPostCampaignRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 링크 생성 — 이미 있으면 무시(PK가 brand_id·short_code·campaign_id 3중이라 같은 조합 재요청은 멱등). */
	public void upsert(long brandId, String shortCode, long campaignId, long userId) {
		jdbcClient.sql("""
				INSERT INTO app.brand_post_campaigns (brand_id, short_code, campaign_id, user_id)
				VALUES (:brandId, :shortCode, :campaignId, :userId)
				ON CONFLICT (brand_id, short_code, campaign_id) DO NOTHING
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("campaignId", campaignId)
				.param("userId", userId)
				.update();
	}

	/**
	 * 게시물의 모든 캠페인 링크 삭제(취소 API 전용, §2-4) — 브랜드 풀에서 direct 표식이 빠지면
	 * 그 게시물의 캠페인 소속도 함께 정리한다(원장 삭제와 짝, 취소 후 재등록 시 새로 붙는다).
	 */
	public void deleteByBrandAndShortCode(long brandId, String shortCode) {
		jdbcClient.sql("""
				DELETE FROM app.brand_post_campaigns WHERE brand_id = :brandId AND short_code = :shortCode
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.update();
	}
}
