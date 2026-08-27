package com.celfit.was.v1.perfdashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 성과 대시보드 버전키의 <b>유저 소유 가변 행 지문</b>(2026-08-13 ETag 설계 §2-3) — app DataSource.
 * 각 메서드는 md5 hex 32자 문자열 하나를 돌려준다(행 0건이어도 {@code md5('')}라 항상 비어 있지 않다).
 *
 * <p><b>왜 워터마크가 아니라 행 지문인가</b>: {@code app.monitoring_items}에 {@code updated_at}이 없고
 * 기간·캠페인·타깃 변경 UPDATE가 어떤 타임스탬프도 갱신하지 않는다(설계 §2-2). 컬럼을 추가하고 모든
 * 쓰기 경로에서 그걸 세우는 대안은 <b>한 곳만 빠뜨려도 낡은 데이터를 조용히 서빙</b>하므로, 정확성을
 * 규율이 아니라 데이터 자체에서 얻는 쪽을 골랐다. 유저당 행이 수십 건이라(§2-3 실측) 비용은 무시할
 * 수준이다.
 *
 * <p><b>SQL 관용구 3가지 규율</b>(전 쿼리 공통):
 * <ol>
 *   <li><b>{@code ROW(...)::text}로 행을 직렬화한다.</b> 설계 §2-3 예시의 {@code a || ':' || b}
 *       수동 연결과 다른 점인데, {@code source_url}처럼 구분자({@code :}·{@code ,})를 값에 품는 컬럼이
 *       있어 수동 연결은 서로 다른 상태가 같은 문자열이 될 여지가 남는다. {@code record_out}은 특수
 *       문자를 따옴표로 감싸고 NULL과 빈 문자열도 구분하므로 그 여지가 통째로 사라진다.</li>
 *   <li><b>{@code timestamptz}는 {@code extract(epoch from ...)}로 넣는다.</b> {@code ::text}는 세션
 *       {@code TimeZone}에 따라 표현이 달라져, 커넥션 설정이 갈리면 데이터가 같은데도 ETag가 영영
 *       안 맞는 조용한 성능 사고가 된다.</li>
 *   <li><b>{@code ORDER BY}를 반드시 건다.</b> {@code string_agg}는 입력 순서를 그대로 쓰므로 정렬이
 *       없으면 같은 데이터가 매번 다른 md5를 낸다. 텍스트 키 정렬은 DB 로케일 의존을 끊으려고
 *       {@code COLLATE "C"}다.</li>
 * </ol>
 *
 * <p><b>해싱 컬럼 ↔ 응답 영향 컬럼 대응표</b>는 각 메서드 javadoc에 있다 — 응답에 나가는 컬럼이
 * 늘면 여기도 같이 늘어나야 한다(설계 §2-3 규율, 스테이징 검증 §5-⑥이 최종 게이트).
 */
@Repository
public class DashboardVersionRepository {

	private final JdbcClient jdbcClient;

	public DashboardVersionRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 레거시 추적 아이템 지문 — {@code app.monitoring_items}, 유저 스코프
	 * ({@code MonitoringItemRepository.findAllByUser}와 같은 범위).
	 *
	 * <table>
	 *   <caption>해싱 컬럼 ↔ 응답 영향 지점</caption>
	 *   <tr><th>컬럼</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code id}</td><td>{@code item.id}(contentKey) · 행 존재 자체(등록·롤백 삭제)</td></tr>
	 *   <tr><td>{@code mode}</td><td>{@code item.mode} · 상태 유도 분기(url/account) · handle 산지</td></tr>
	 *   <tr><td>{@code target_id}</td><td>상태 유도(pending 판정) · 스냅샷·게시물 산지 연결</td></tr>
	 *   <tr><td>{@code campaign_id}</td><td>{@code item.campaignId}·{@code campaignName} · 캠페인 필터</td></tr>
	 *   <tr><td>{@code input_value}</td><td>account 모드의 {@code item.handle} · 시딩 계정 도출</td></tr>
	 *   <tr><td>{@code source_url}</td><td>{@code item.sourceUrl} · url 모드 게시물 URL 폴백</td></tr>
	 *   <tr><td>{@code keywords}</td><td>{@code item.keywords}</td></tr>
	 *   <tr><td>{@code tracking_days}</td><td>{@code item.trackingDays} · 기간 만료 상태 유도</td></tr>
	 *   <tr><td>{@code registered_on}</td><td>{@code item.registeredAt} · 기간 만료 상태 유도 기준일</td></tr>
	 *   <tr><td>{@code canceled_at}</td><td>상태 유도 1순위(취소)</td></tr>
	 *   <tr><td>{@code canceled_from}</td><td>취소 상태의 종결 어휘(not_uploaded/ended)</td></tr>
	 * </table>
	 *
	 * <p>제외: {@code user_id}(스코프 자신) · {@code registration_key}·{@code created_at}(응답에 나가지
	 * 않고 UPDATE 경로도 없다).
	 */
	public String monitoringItemsFingerprint(long userId) {
		return fingerprint("""
				SELECT md5(coalesce(string_agg(
				         ROW(i.id, i.mode, i.target_id, i.campaign_id, i.input_value, i.source_url,
				             i.keywords::text, i.tracking_days, to_char(i.registered_on, 'YYYYMMDD'),
				             extract(epoch from i.canceled_at), i.canceled_from)::text,
				         ',' ORDER BY i.id), ''))
				FROM app.monitoring_items i
				WHERE i.user_id = :userId
				""", userId);
	}

	/**
	 * 브랜드 연결 지문 — {@code app.brand_monitorings}, 유저 스코프.
	 *
	 * <table>
	 *   <caption>해싱 컬럼 ↔ 응답 영향 지점</caption>
	 *   <tr><th>컬럼</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code id}</td><td>행 존재(연결·재연결) · 동률 정렬의 최종 타이브레이크</td></tr>
	 *   <tr><td>{@code brand_id}</td><td>브랜드 풀 조회 대상 자체 · {@code content.brandAccountId} 귀속</td></tr>
	 *   <tr><td>{@code account_type}</td><td>경쟁사 집합(기본 범위·statusCounts 모수) · own-first 귀속 순서</td></tr>
	 *   <tr><td>{@code collection_months}</td><td>유저별 표시 기간 — 브랜드 조립의 창 입력</td></tr>
	 *   <tr><td>{@code created_at}</td><td>연결 순서({@code ORDER BY created_at, id})가 같은 shortcode의
	 *       귀속 승자를 정한다</td></tr>
	 *   <tr><td>{@code deleted_at}</td><td>해제 = 활성 목록에서 제외(브랜드 풀 통째로 빠진다)</td></tr>
	 * </table>
	 *
	 * <p>제외: {@code username}(연결 행의 값은 응답에 안 나간다 — 표시 계정명은 monitoring
	 * {@code brand_account.username}에서 온다).
	 */
	public String brandLinksFingerprint(long userId) {
		return fingerprint("""
				SELECT md5(coalesce(string_agg(
				         ROW(l.id, l.brand_id, l.account_type, l.collection_months,
				             extract(epoch from l.created_at), extract(epoch from l.deleted_at))::text,
				         ',' ORDER BY l.id), ''))
				FROM app.brand_monitorings l
				WHERE l.user_id = :userId
				""", userId);
	}

	/**
	 * 직접 등록 원장 지문 — {@code app.brand_direct_posts}, 유저 스코프
	 * ({@code BrandDirectPostRepository.shortCodesByUser}와 같은 범위).
	 *
	 * <table>
	 *   <caption>해싱 컬럼 ↔ 응답 영향 지점</caption>
	 *   <tr><th>컬럼</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code brand_id}</td><td>등록 브랜드 귀속</td></tr>
	 *   <tr><td>{@code short_code}</td><td>{@code ownedShortCodes} — 노출 필터(등록자 전용 노출)와
	 *       {@code source} 판정(direct/tagged)의 입력</td></tr>
	 *   <tr><td>{@code monitoring_item_id}</td><td>레거시 아이템 연결 — 겹침 병합 대상 여부</td></tr>
	 *   <tr><td>{@code migrated_at}</td><td>이관 표식 — 레거시 폴백 조립 경로 진입 여부</td></tr>
	 * </table>
	 *
	 * <p>제외: {@code user_id}(스코프 자신) · {@code created_at}(응답에 나가지 않는다).
	 */
	public String directPostsFingerprint(long userId) {
		return fingerprint("""
				SELECT md5(coalesce(string_agg(
				         ROW(d.brand_id, d.short_code, d.monitoring_item_id,
				             extract(epoch from d.migrated_at))::text,
				         ',' ORDER BY d.brand_id, d.short_code COLLATE "C"), ''))
				FROM app.brand_direct_posts d
				WHERE d.user_id = :userId
				""", userId);
	}

	/**
	 * 캠페인 지문 — {@code app.monitoring_campaigns}, 유저 스코프
	 * ({@code CampaignRepository.findByUser}와 같은 범위).
	 *
	 * <table>
	 *   <caption>해싱 컬럼 ↔ 응답 영향 지점</caption>
	 *   <tr><th>컬럼</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code id}</td><td>행 존재(생성·삭제) — 삭제는 아이템의 {@code campaign_id}를 NULL로
	 *       떨구고 {@code campaignName} 해석도 못 하게 만든다</td></tr>
	 *   <tr><td>{@code name}</td><td>{@code item.campaignName}</td></tr>
	 * </table>
	 *
	 * <p>제외: {@code description}·{@code start_date}·{@code end_date}·{@code brand}·{@code budget}·
	 * {@code seeding_count}·{@code created_at} — 캠페인 관리 표면(6.25) 전용이고 대시보드 응답에는
	 * 나가지 않는다. 대시보드가 이 중 하나를 싣게 되면 <b>여기에 같이 추가해야 한다</b>.
	 */
	public String campaignsFingerprint(long userId) {
		return fingerprint("""
				SELECT md5(coalesce(string_agg(
				         ROW(c.id, c.name)::text,
				         ',' ORDER BY c.id), ''))
				FROM app.monitoring_campaigns c
				WHERE c.user_id = :userId
				""", userId);
	}

	/**
	 * 게시물↔캠페인 부착 지문 — {@code app.brand_post_campaigns}.
	 *
	 * <p><b>스코프가 유저 하나가 아니다.</b> 조립이 이 테이블을 두 갈래로 읽는데 범위가 서로 다르다:
	 * <ul>
	 *   <li>{@code BrandPostCampaignRepository.findByBrandAndShortCodes}는 <b>브랜드 스코프</b>다 —
	 *       내 브랜드에 붙은 <b>다른 유저의</b> 부착도 내 카드의 {@code campaignId}(=campaignIds의 head)를
	 *       바꾼다. 유저 스코프로만 지문을 뜨면 이 변경을 통째로 놓쳐 낡은 카드를 서빙하게 된다.</li>
	 *   <li>{@code findShortCodesByUser}는 <b>유저 스코프</b>다(시딩 계정 도출) — 내가 지금 연결하고
	 *       있지 않은 브랜드의 내 부착 행도 여기 걸린다.</li>
	 * </ul>
	 * 그래서 두 범위의 <b>합집합</b>을 뜬다. UNION으로 쓴 이유는 {@code OR}가 PK 선두 컬럼
	 * ({@code brand_id}) 인덱스와 {@code brand_post_campaigns_user_idx}를 동시에 못 쓰게 만들어서다.
	 *
	 * <table>
	 *   <caption>해싱 컬럼 ↔ 응답 영향 지점</caption>
	 *   <tr><th>컬럼</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code brand_id}·{@code short_code}·{@code campaign_id}</td>
	 *       <td>{@code campaignIds}(head가 {@code item.campaignId}·{@code campaignName}) · 캠페인 필터</td></tr>
	 *   <tr><td>{@code user_id}</td><td>시딩 계정 도출 스코프 — 같은 (brand, code, campaign)에 유저별
	 *       행이 공존할 수 있어 식별에 포함한다</td></tr>
	 * </table>
	 */
	public String postCampaignLinksFingerprint(long userId) {
		return fingerprint("""
				SELECT md5(coalesce(string_agg(
				         ROW(t.brand_id, t.short_code, t.campaign_id, t.user_id)::text,
				         ',' ORDER BY t.brand_id, t.short_code COLLATE "C", t.campaign_id, t.user_id), ''))
				FROM (
				  SELECT p.brand_id, p.short_code, p.campaign_id, p.user_id
				  FROM app.brand_post_campaigns p
				  WHERE p.user_id = :userId
				  UNION
				  SELECT p.brand_id, p.short_code, p.campaign_id, p.user_id
				  FROM app.brand_post_campaigns p
				  WHERE p.brand_id IN (SELECT b.brand_id FROM app.brand_monitorings b
				                       WHERE b.user_id = :userId AND b.deleted_at IS NULL)
				) t
				""", userId);
	}

	/** 모든 지문 쿼리의 공통 실행부 — 결과는 항상 1행 1열({@code md5('')}이라도 비어 있지 않다). */
	private String fingerprint(String sql, long userId) {
		return jdbcClient.sql(sql).param("userId", userId).query(String.class).single();
	}
}
