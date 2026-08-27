package com.celfit.was.monitoring;

import java.util.Collection;
import java.util.List;
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
	 * 이 유저가 이 게시물에 건 캠페인 링크만 삭제(취소 API 전용, §2-4, 등록자 한정 취소 08-19 개정) —
	 * 원장 삭제와 짝, 취소 후 재등록 시 새로 붙는다. user_id로 좁히는 이유: 동시 등록 레이스로 같은
	 * (brand, shortcode)에 다른 유저의 캠페인 링크가 공존할 수 있다({@link #upsert} — user_id는 PK가
	 * 아니라 (brand_id, short_code, campaign_id)당 여러 유저 행이 있을 수 있다) — 브랜드+shortcode
	 * 전체 삭제였던 구버전은 내 취소가 남의 캠페인 링크까지 지우는 사고였다.
	 */
	public void deleteByBrandAndShortCodeAndUser(long brandId, String shortCode, long userId) {
		jdbcClient.sql("""
				DELETE FROM app.brand_post_campaigns
				WHERE brand_id = :brandId AND short_code = :shortCode AND user_id = :userId
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("userId", userId)
				.update();
	}

	/**
	 * 브랜드 풀 게시물 묶음의 캠페인 배치 조회(2026-08-18 direct 통합 §T10) — {@code BrandPostAssembler}가
	 * {@code campaignIds}를 채우는 데 쓴다. shortcode당 다건일 수 있다(N:M) — 호출부가 shortcode로 그룹핑.
	 */
	public List<Link> findByBrandAndShortCodes(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbcClient.sql("""
				SELECT short_code, campaign_id FROM app.brand_post_campaigns
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				ORDER BY short_code, campaign_id
				""")
				.param("brandId", brandId)
				.param("shortCodes", shortCodes)
				.query(Link.class)
				.list();
	}

	/** {@link #findByBrandAndShortCodes} 1행 — shortcode 1건에 캠페인 1건. */
	public record Link(String shortCode, long campaignId) {
	}

	/**
	 * 유저가 캠페인에 연결한 브랜드 풀 게시물 shortcode(seededAuthor 캠페인 도출 재료, 2026-08-18
	 * 캠페인 도출 개정 — direct 통합 이후분). brand_id 무관 유저 스코프(캠페인은 브랜드가 아니라 유저
	 * 단위 개념 — {@link BrandPostAssembler#assembleBrandPosts} 주석 참조)다. tagged·direct 공통 —
	 * 이 테이블이 두 산지 모두를 커버하므로({@code upsert} 호출부가 direct 등록 시 campaignId 파라미터
	 * 하나뿐이라도, 트랙 결정상 tagged에도 부착 여지가 열려 있다) {@code BrandDirectPostRepository.
	 * findCampaignLinkedShortCodes}(레거시 monitoring_items 경유, 이관 전 매핑 전용)와 상호 배타적으로
	 * 겹치지 않는다 — 신규 등록·이관 완료분은 여기, 이관 전 매핑만 그쪽.
	 */
	public List<String> findShortCodesByUser(long userId) {
		return jdbcClient.sql("""
				SELECT DISTINCT short_code FROM app.brand_post_campaigns WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(String.class)
				.list();
	}
}
