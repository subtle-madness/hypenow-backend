package com.celfit.was.monitoring;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.brand_post_registrations·app.brand_post_registration_entries CRUD(2026-08-18 direct 통합
 * §2-2-1) — 브랜드 direct 등록 전용 저장소. 레거시 {@link RegistrationRepository}와 같은 위상이지만
 * 레거시 아이템 테이블(app.monitoring_items)과 조인하지 않는다 — 이 등록의 산지는 오직
 * monitoring 브랜드 풀(brand_tagged_post)뿐이라 CTE로 item 상태를 끌어올 필요가 없다
 * ({@link #settleStalePendingEntries} 참조, 레거시 {@code settleStaleEntries}의 CTE 조인과 대조).
 */
@Repository
public class BrandPostRegistrationRepository {

	private static final String STALE_REASON = "24시간 넘게 처리되지 않아 실패로 확정했어요.";

	private final JdbcClient jdbcClient;

	public BrandPostRegistrationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 등록 요청 1행 선저장 — requested_at DEFAULT now(). RETURNING id·requested_at(응답 직렬화용 정확한 값). */
	public InsertedRegistration insert(long userId, long brandId, Long campaignId) {
		return jdbcClient.sql("""
				INSERT INTO app.brand_post_registrations (user_id, brand_id, campaign_id)
				VALUES (:userId, :brandId, :campaignId)
				RETURNING id, requested_at
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("campaignId", campaignId)
				.query(InsertedRegistration.class)
				.single();
	}

	/**
	 * entry 1건 삽입 — {@code settled_at}은 result가 pending이면 NULL, 아니면 now()로 접수 시점에
	 * 함께 확정한다(1차 판정으로 확정되는 duplicate·failed 입력이 여기 해당 — 실행기를 거치지 않는다).
	 */
	public void insertEntry(long registrationId, int seq, String input, String shortCode, String result,
			String reasonCode, String reason) {
		jdbcClient.sql("""
				INSERT INTO app.brand_post_registration_entries
				    (registration_id, seq, input, short_code, result, reason_code, reason, settled_at)
				VALUES (:registrationId, :seq, :input, :shortCode, :result, :reasonCode, :reason,
				        CASE WHEN :result = 'pending' THEN NULL ELSE now() END)
				""")
				.param("registrationId", registrationId)
				.param("seq", seq)
				.param("input", input)
				.param("shortCode", shortCode)
				.param("result", result)
				.param("reasonCode", reasonCode)
				.param("reason", reason)
				.update();
	}

	/** 실행기의 entry 확정(§T8 entry 처리 ①~④) — shortCode는 share 해소로 새로 알아낸 값을 함께 반영한다. */
	public void updateEntryResult(long registrationId, int seq, String shortCode, String result,
			String reasonCode, String reason) {
		jdbcClient.sql("""
				UPDATE app.brand_post_registration_entries
				SET short_code = :shortCode, result = :result, reason_code = :reasonCode, reason = :reason,
				    settled_at = now()
				WHERE registration_id = :registrationId AND seq = :seq
				""")
				.param("shortCode", shortCode)
				.param("result", result)
				.param("reasonCode", reasonCode)
				.param("reason", reason)
				.param("registrationId", registrationId)
				.param("seq", seq)
				.update();
	}

	/** 미정산(pending) 엔트리가 0건이면 완료 처리 — 이미 완료면 no-op(레거시 markCompletedIfAllSettled와 동형). */
	public void markCompletedIfAllSettled(long registrationId) {
		jdbcClient.sql("""
				UPDATE app.brand_post_registrations
				SET completed_at = now()
				WHERE id = :registrationId
				  AND completed_at IS NULL
				  AND NOT EXISTS (
				      SELECT 1 FROM app.brand_post_registration_entries
				      WHERE registration_id = :registrationId AND result = 'pending'
				  )
				""")
				.param("registrationId", registrationId)
				.update();
	}

	/** 단건 조회(entries 포함, seq ASC) — 유저 스코프는 호출부가 건다(실행기는 스코프 없이 부른다). */
	public Optional<BrandPostRegistrationRow> findById(long registrationId) {
		Optional<Header> header = jdbcClient.sql("""
				SELECT id, user_id, brand_id, campaign_id, requested_at, completed_at
				FROM app.brand_post_registrations WHERE id = :id
				""")
				.param("id", registrationId)
				.query(Header.class)
				.optional();
		if (header.isEmpty()) {
			return Optional.empty();
		}
		Header h = header.get();
		List<BrandPostRegistrationEntryRow> entries = jdbcClient.sql("""
				SELECT registration_id, seq, input, short_code, result, reason_code, reason, settled_at
				FROM app.brand_post_registration_entries
				WHERE registration_id = :id
				ORDER BY seq ASC
				""")
				.param("id", registrationId)
				.query(BrandPostRegistrationEntryRow.class)
				.list();
		return Optional.of(new BrandPostRegistrationRow(h.id(), h.userId(), h.brandId(), h.campaignId(),
				h.requestedAt(), h.completedAt(), entries));
	}

	/**
	 * 크래시 복구 대상(§T8 5분 stale 복구) — pending entry가 남은 registration id(중복 제거).
	 * 접수 커밋 직후 프로세스가 죽어 {@code afterCommit} 트리거를 못 받은 등록을 다시 집어간다.
	 */
	public List<Long> findRegistrationIdsWithPendingOlderThan(Duration age) {
		return jdbcClient.sql("""
				SELECT DISTINCT r.id
				FROM app.brand_post_registrations r
				JOIN app.brand_post_registration_entries e ON e.registration_id = r.id
				WHERE e.result = 'pending' AND r.requested_at < now() - make_interval(secs => :seconds)
				ORDER BY r.id
				""")
				.param("seconds", (double) age.toMillis() / 1000.0)
				.query(Long.class)
				.list();
	}

	/**
	 * 나이 기반 entry 확정(§T8 24시간 stale 정산, 설계 R7 — 레거시 {@code settleStaleEntries} 동형).
	 * item 상태를 참조할 필요가 없다 — 이 등록의 pending은 "아직 monitoring 명령을 못 보냈다"는 뜻
	 * 하나뿐이라(레거시처럼 target 확정 여부로 갈리는 중간 상태가 없다), 나이 초과면 무조건 failed다.
	 *
	 * @return 영향받은 registration_id 목록(중복 제거) — 호출부가 각각 markCompletedIfAllSettled를 돈다
	 */
	public List<Long> settleStalePendingEntries(Duration age) {
		List<Long> registrationIds = jdbcClient.sql("""
				UPDATE app.brand_post_registration_entries e
				SET result = 'failed', reason_code = 'internal_error', reason = :reason, settled_at = now()
				FROM app.brand_post_registrations r
				WHERE e.registration_id = r.id AND e.result = 'pending'
				  AND r.requested_at < now() - make_interval(secs => :seconds)
				RETURNING e.registration_id
				""")
				.param("reason", STALE_REASON)
				.param("seconds", (double) age.toMillis() / 1000.0)
				.query(Long.class)
				.list();
		return registrationIds.stream().distinct().toList();
	}

	/**
	 * 취소 시 pending entry 정산(§2-4 취소, 2026-08-18 스테이징 실측 경합 수정) — 응답 유실로
	 * pending에 머문 entry는 실제로는 등록이 완료된 상태다. 취소 시점에 success로 정산해 stale
	 * 복구가 취소된 게시물을 재등록하는 경합을 막는다(2026-08-18 스테이징 실측). short_code가
	 * null인 pending(파싱·share 미해소)은 대상이 아니다 — 아직 무엇을 취소할지조차 확정되지
	 * 않았으므로 이 취소와 무관하다.
	 *
	 * <p>userId로 좁히는 이유(등록자 한정 취소, 08-19 개정): 동시 등록 레이스로 다른 유저의 같은
	 * (brand, shortcode) 등록이 pending으로 남아 있을 수 있다 — 그 상태에서 내가 취소하면, 브랜드
	 * 스코프였던 구버전은 남의 아직 진행 중인 등록 entry까지 "성공"으로 조작해버렸다. 이제는 취소하는
	 * 유저 본인의 registration만 정산 대상이다.
	 *
	 * @return 영향받은 registration_id 목록(중복 제거) — 호출부가 각각 markCompletedIfAllSettled를 돈다
	 */
	public List<Long> settlePendingAsSuccessForCancel(long brandId, String shortCode, long userId) {
		List<Long> registrationIds = jdbcClient.sql("""
				UPDATE app.brand_post_registration_entries e
				SET result = 'success', reason_code = NULL, reason = NULL, settled_at = now()
				FROM app.brand_post_registrations r
				WHERE e.registration_id = r.id AND e.result = 'pending' AND e.short_code = :shortCode
				  AND r.brand_id = :brandId AND r.user_id = :userId
				RETURNING e.registration_id
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("userId", userId)
				.query(Long.class)
				.list();
		return registrationIds.stream().distinct().toList();
	}

	/**
	 * 등록자 한정 취소(요구사항, 08-19)의 보조 판정 — 취소-복구 경합(2026-08-18 스테이징 실측)과
	 * 겹치는 지점: 등록 명령의 HTTP 응답이 유실되면 monitoring은 실제로 등록을 완료했는데
	 * {@code app.brand_direct_posts} 원장은 아직 안 쓰였고(executor의 upsertDirect는 응답을 받아야
	 * 돈다) entry는 pending으로 남는다. 이 창에서 사용자가 취소를 시도하면 원장 존재만으로는 등록자
	 * 판정이 안 되므로, "이 유저가 이 (brand, shortcode)에 pending 등록을 넣어 뒀는가"도 등록자
	 * 증거로 인정한다({@link com.celfit.was.v1.brandmonitoring.V1BrandDirectPostService#
	 * ensureOwnRegistration} 참조).
	 */
	public boolean hasPendingEntry(long brandId, String shortCode, long userId) {
		Boolean exists = jdbcClient.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM app.brand_post_registration_entries e
				    JOIN app.brand_post_registrations r ON r.id = e.registration_id
				    WHERE r.brand_id = :brandId AND r.user_id = :userId AND e.short_code = :shortCode
				      AND e.result = 'pending'
				)
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("userId", userId)
				.query(Boolean.class)
				.single();
		return Boolean.TRUE.equals(exists);
	}

	/** insert() RETURNING 전용 — id·requested_at만 있으면 응답 직렬화에 충분하다(entries는 빈 채로 시작). */
	public record InsertedRegistration(long id, OffsetDateTime requestedAt) {
	}

	/** entries 없이 헤더만 매핑하기 위한 내부 전용 행. */
	private record Header(long id, long userId, long brandId, Long campaignId, OffsetDateTime requestedAt,
			OffsetDateTime completedAt) {
	}
}
