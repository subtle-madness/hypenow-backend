package com.celfit.was.monitoring;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 브랜드 연결 저장 계층(2026-08-07 스펙 §3-1, 08-07 다계정 개정) — app.brand_monitorings 접점.
 * 항상 활성(app 기본 DataSource, monitoring 서브시스템 비활성이어도 무해).
 *
 * <p>활성 연결은 부분 유니크 인덱스(brand_monitorings_active_user_brand_uidx)로 유저·브랜드당 1개가
 * DB에서 강제된다 — 같은 (유저, 브랜드) 동시 등록의 최후 보루는 여기서 터지는 DuplicateKeyException
 * 이고, 호출부는 이를 멱등 성공으로 접는다. 유저별 계정 수 한도는 인덱스로 표현할 수 없어
 * {@link #lockUser} 직렬화 위에서 앱이 센다. 해제는 soft-delete라 재연결 경로가 열려 있다(§5.4).
 */
@Repository
public class BrandLinkRepository {

	private static final String SELECT_COLUMNS =
			"id, user_id, brand_id, username, account_type, created_at, deleted_at";

	private final JdbcClient jdbcClient;

	public BrandLinkRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저의 활성 연결 전체 — 연결 순서(생성 오름차순)가 목록 표시 순서다. */
	public List<BrandLinkRow> findAllActiveByUser(long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.brand_monitorings
				WHERE user_id = :userId AND deleted_at IS NULL
				ORDER BY created_at, id
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.query(BrandLinkRow.class)
				.list();
	}

	/**
	 * 유저의 연결 전체(해제분 포함) — 어드민 크롤링 사용량의 기간 귀속 입력(2026-08-12 설계).
	 * 해제된 연결도 "연결돼 있던 기간의 콜"은 그 유저 몫이라 deleted_at 필터를 걸지 않는다.
	 */
	public List<BrandLinkRow> findAllByUser(long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.brand_monitorings
				WHERE user_id = :userId
				ORDER BY created_at, id
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.query(BrandLinkRow.class)
				.list();
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
	 * 유저 행 FOR UPDATE 잠금 — 계정 수 한도(카운트→INSERT)의 유저 단위 직렬화 뮤텍스.
	 * 한도는 유니크 인덱스로 표현할 수 없어, 동시 등록 두 건이 각자 한도 미달을 보고 초과 삽입하는
	 * 경합을 이 잠금으로 막는다. 행 부재는 인증 전제상 도달 불가라 조용히 넘기지 않고 예외로 드러낸다.
	 */
	public void lockUser(long userId) {
		List<Long> rows = jdbcClient.sql("SELECT id FROM app.users WHERE id = :userId FOR UPDATE")
				.param("userId", userId)
				.query(Long.class)
				.list();
		if (rows.isEmpty()) {
			throw new IllegalStateException("브랜드 연결 잠금 실패 — users 행 없음: id=" + userId);
		}
	}

	/** 활성 연결 생성. RETURNING id. 같은 (유저, 브랜드) 활성 연결이 있으면 DuplicateKeyException. */
	public long insertLink(long userId, long brandId, String username, String accountType) {
		return jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type)
				VALUES (:userId, :brandId, :username, :accountType)
				RETURNING id
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("username", username)
				.param("accountType", accountType)
				.query(Long.class)
				.single();
	}

	/**
	 * 활성 연결의 타입 변경(08-12) — 재수집이 아니라 관계 속성만 바꾼다.
	 *
	 * <p>false는 <b>활성 연결이 없다</b>는 뜻 하나뿐이다(호출부의 소유권·멱등 판정 지점).
	 * 이미 그 타입인 행에 호출해도 Postgres는 갱신 행으로 세므로 true다 — 즉 반환값은
	 * "값이 실제로 달라졌나"가 아니라 "대상 행이 있었나"의 신호다. 값 비교 조건을 WHERE에 넣지
	 * 않은 것은 의도적이다: 넣으면 false가 "행 없음"과 "이미 같은 값"을 뭉개 소유권 판정이 깨진다.
	 */
	public boolean updateAccountType(long userId, long brandId, String accountType) {
		return jdbcClient.sql("""
				UPDATE app.brand_monitorings SET account_type = :accountType
				WHERE user_id = :userId AND brand_id = :brandId AND deleted_at IS NULL
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("accountType", accountType)
				.update() > 0;
	}

	/** 연결 1건 해제(soft-delete). 이미 해제됐거나 없으면 false — 호출부의 멱등 판정 지점. */
	public boolean softDeleteLink(long userId, long brandId) {
		return jdbcClient.sql("""
				UPDATE app.brand_monitorings SET deleted_at = now()
				WHERE user_id = :userId AND brand_id = :brandId AND deleted_at IS NULL
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.update() > 0;
	}

	/** 유저의 활성 연결 전체 해제(회원 탈퇴 훅) — 해제한 행 수를 돌려준다. */
	public int softDeleteAllActiveByUser(long userId) {
		return jdbcClient.sql("""
				UPDATE app.brand_monitorings SET deleted_at = now()
				WHERE user_id = :userId AND deleted_at IS NULL
				""")
				.param("userId", userId)
				.update();
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
