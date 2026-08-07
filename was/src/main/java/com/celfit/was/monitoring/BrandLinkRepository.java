package com.celfit.was.monitoring;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 브랜드 연결 저장 계층(2026-08-07 스펙 §3-1) — app.brand_monitorings와 users.instgram_account_name
 * 접점. 항상 활성(app 기본 DataSource, monitoring 서브시스템 비활성이어도 무해).
 *
 * <p>활성 연결은 부분 유니크 인덱스(brand_monitorings_active_user_uidx)로 유저당 1개가 DB에서
 * 강제된다 — 서비스가 FOR UPDATE로 먼저 막지만, 동시 요청의 최후 보루는 여기서 터지는
 * DuplicateKeyException이다. 해제는 soft-delete라 재등록 경로가 열려 있다(§5.4).
 */
@Repository
public class BrandLinkRepository {

	private static final String SELECT_COLUMNS = "id, user_id, brand_id, username, created_at, deleted_at";

	private final JdbcClient jdbcClient;

	public BrandLinkRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저의 활성 연결 — 없으면 empty(미등록 또는 해제됨). */
	public Optional<BrandLinkRow> findActiveByUser(long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.brand_monitorings
				WHERE user_id = :userId AND deleted_at IS NULL
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.query(BrandLinkRow.class)
				.optional();
	}

	/** 브랜드 스코프 API의 소유권 검증용 — empty면 남의 브랜드(403). */
	public Optional<BrandLinkRow> findActiveByUserAndBrand(long userId, long brandId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.brand_monitorings
				WHERE user_id = :userId AND brand_id = :brandId AND deleted_at IS NULL
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.param("brandId", brandId)
				.query(BrandLinkRow.class)
				.optional();
	}

	/**
	 * 등록 트랜잭션의 불변 검증용 — 유저 행을 FOR UPDATE로 잠그고 저장된 브랜드 계정명을 읽는다
	 * (동시 등록 두 건이 같은 유저를 서로 다른 계정으로 채우는 경합 차단).
	 *
	 * <p>반환 null은 "아직 미저장"이다. 행 자체가 없는 것은 인증 전제상 도달 불가라 조용한 null로
	 * 뭉개지 않고 예외로 드러낸다 — 그래서 값 null과 행 부재를 구분해야 하고, optional() 대신
	 * list()로 받는다(optional()은 컬럼 값 null도 empty로 접어버려 둘을 구분할 수 없다).
	 */
	public String instagramAccountNameForUpdate(long userId) {
		List<String> rows = jdbcClient
				.sql("SELECT instgram_account_name FROM app.users WHERE id = :userId FOR UPDATE")
				.param("userId", userId)
				.query((rs, rowNum) -> rs.getString("instgram_account_name"))
				.list();
		if (rows.isEmpty()) {
			throw new IllegalStateException("브랜드 계정 조회 실패 — users 행 없음: id=" + userId);
		}
		return rows.get(0);
	}

	/** 브랜드 계정명 최초 저장(불변 계약 — 이미 값이 있으면 서비스가 여기까지 오지 않는다). */
	public void saveInstagramAccountName(long userId, String username) {
		jdbcClient.sql("UPDATE app.users SET instgram_account_name = :username WHERE id = :userId")
				.param("username", username)
				.param("userId", userId)
				.update();
	}

	/** 활성 연결 생성. RETURNING id. 이미 활성 연결이 있으면 DuplicateKeyException. */
	public long insertLink(long userId, long brandId, String username) {
		return jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username)
				VALUES (:userId, :brandId, :username)
				RETURNING id
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("username", username)
				.query(Long.class)
				.single();
	}

	/** 연결 해제(soft-delete). 이미 해제됐거나 없으면 false — 호출부의 멱등 판정 지점. */
	public boolean softDeleteActiveLink(long userId) {
		return jdbcClient.sql("""
				UPDATE app.brand_monitorings SET deleted_at = now()
				WHERE user_id = :userId AND deleted_at IS NULL
				""")
				.param("userId", userId)
				.update() > 0;
	}

	/** 브랜드에 남은 활성 연결 수 — 0이면 monitoring 쪽 브랜드 탈퇴까지 진행한다(§5.4). */
	public int countActiveByBrand(long brandId) {
		return jdbcClient.sql("""
				SELECT count(*) FROM app.brand_monitorings
				WHERE brand_id = :brandId AND deleted_at IS NULL
				""")
				.param("brandId", brandId)
				.query(Integer.class)
				.single();
	}
}
