package com.celfit.was.archive;

import java.util.List;

/**
 * 아카이브 대상 카탈로그 — 단일 정본. 여기 없는 테이블은 ArchiveInventoryTest가 EXCLUDED에
 * 사유와 함께 등재돼 있는지 검사한다.
 *
 * <p>{@link #CATALOG}와 {@link #ACCOUNT_DELETION_ORDER}는 서로 다른 질문에 답한다 —
 * "어떤 삭제 경로로든 아카이브되는 테이블 전부"(CATALOG) vs "탈퇴가 이관하는 테이블과 순서"
 * (ACCOUNT_DELETION_ORDER). 지금은 두 목록의 원소가 우연히 같지만(9개 전부 탈퇴 캐스케이드에도
 * 걸린다), SAVED_REMOVED·CAMPAIGN_DELETED·REGISTRATION_ROLLBACK처럼 탈퇴와 무관한 개별 삭제
 * 경로 전용 테이블이 생기면 갈라진다 — 그런 테이블은 CATALOG엔 있지만 ACCOUNT_DELETION_ORDER엔
 * 없어야 맞다. 두 목록은 서로 파생시키지 않고 각각 독립적으로 나열한다(파생시키면 아래
 * ACCOUNT_DELETION_ORDER ⊆ CATALOG 검사가 항진명제가 돼 무의미해진다).
 */
public final class ArchiveTables {

	/** 직접 식별 컬럼 7종 — 자연인을 특정한다. company_name 등 속성 컬럼은 자산이라 보존한다.
	 *  package-private — ArchiveWriterTest가 이 목록과 자체 하드코드 기대값(EXPECTED_USER_PII)이
	 *  일치하는지 교차 검증한다. 테스트가 이 상수를 그대로 참조해서 payload를 검사하면 이 상수
	 *  자체의 오타를 못 잡는다(실측 확인 — nickname을 nicknameX로 바꿔도 자기 자신과 비교해 통과했다),
	 *  그래서 소스를 분리했다. */
	static final List<String> USER_PII = List.of(
			"email", "password_hash", "name", "nickname",
			"phone_country_code", "phone_number", "profile_image_url");

	public static final ArchiveTable SAVED_CONTENTS = new ArchiveTable(
			"app.saved_contents", List.of("user_id", "short_code"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable SAVED_INFLUENCERS = new ArchiveTable(
			"app.saved_influencers", List.of("user_id", "handle"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_CAMPAIGNS = new ArchiveTable(
			"app.monitoring_campaigns", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_EMAIL_OPT_OUTS = new ArchiveTable(
			"app.monitoring_email_opt_outs", List.of("user_id", "event_type"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_ITEMS = new ArchiveTable(
			"app.monitoring_items", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_REGISTRATIONS = new ArchiveTable(
			"app.monitoring_registrations", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	public static final ArchiveTable MONITORING_DIGESTS = new ArchiveTable(
			"app.monitoring_digests", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/** user_id 컬럼이 없다 — registration을 거쳐야 유저에 닿는다(간접 CASCADE). */
	public static final ArchiveTable MONITORING_REGISTRATION_ENTRIES = new ArchiveTable(
			"app.monitoring_registration_entries", List.of("registration_id", "seq"), null,
			List.of(),
			"t.registration_id IN (SELECT id FROM app.monitoring_registrations WHERE user_id = :userId)");

	public static final ArchiveTable USERS = new ArchiveTable(
			"app.users", List.of("id"), "t.id",
			USER_PII, "t.id = :userId");

	/**
	 * 아카이브 카탈로그 전체 — 어떤 삭제 경로(탈퇴 이관, 저장 해제, 캠페인 삭제, 등록 롤백 등)로든
	 * 아카이브되는 테이블 전부. ArchiveInventoryTest의 "분류됨" 판정 기준이 이 목록이다 — 여기 없는
	 * app 테이블은 EXCLUDED에 사유와 함께 있어야 한다. ACCOUNT_DELETION_ORDER와 달리 순서·CASCADE
	 * 가정이 없다(단순 소속 목록).
	 */
	public static final List<ArchiveTable> CATALOG = List.of(
			SAVED_CONTENTS,
			SAVED_INFLUENCERS,
			MONITORING_CAMPAIGNS,
			MONITORING_EMAIL_OPT_OUTS,
			MONITORING_ITEMS,
			MONITORING_REGISTRATIONS,
			MONITORING_DIGESTS,
			MONITORING_REGISTRATION_ENTRIES,
			USERS);

	/**
	 * 탈퇴 시 이관 대상과 순서 — 자식 8개 + users. CATALOG의 부분집합이어야 한다(테스트로 강제).
	 * 이관(INSERT)은 전부 삭제(DELETE)보다 먼저 일어나므로 순서 자체가 정확성에 영향을 주진
	 * 않지만, 자식 → 부모 순으로 읽히게 둔다.
	 */
	public static final List<ArchiveTable> ACCOUNT_DELETION_ORDER = List.of(
			SAVED_CONTENTS,
			SAVED_INFLUENCERS,
			MONITORING_REGISTRATION_ENTRIES,
			MONITORING_REGISTRATIONS,
			MONITORING_ITEMS,
			MONITORING_DIGESTS,
			MONITORING_EMAIL_OPT_OUTS,
			MONITORING_CAMPAIGNS,
			USERS);

	private ArchiveTables() {
	}
}
