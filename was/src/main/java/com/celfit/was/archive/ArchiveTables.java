package com.celfit.was.archive;

import java.util.List;

/**
 * 아카이브 대상 카탈로그 — 단일 정본. 여기 없는 테이블은 ArchiveInventoryTest가 EXCLUDED에
 * 사유와 함께 등재돼 있는지 검사한다.
 *
 * <p>{@link #CATALOG}와 {@link #ACCOUNT_DELETION_ORDER}는 서로 다른 질문에 답한다 —
 * "어떤 삭제 경로로든 아카이브되는 테이블 전부"(CATALOG) vs "탈퇴가 이관하는 테이블과 순서"
 * (ACCOUNT_DELETION_ORDER). 두 목록은 서로 파생시키지 않고 각각 독립적으로 나열한다(파생시키면
 * 아래 ACCOUNT_DELETION_ORDER ⊆ CATALOG 검사가 항진명제가 돼 무의미해진다).
 *
 * <p>{@code NOTICES}·{@code NOTICE_ITEMS}가 실제로 갈라지는 사례다 — users FK가 없어 탈퇴와
 * 무관하고, 어드민 개별 삭제(NOTICE_DELETED)로만 아카이브된다: CATALOG엔 있지만
 * ACCOUNT_DELETION_ORDER엔 없다.
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

	/** 유저당 마지막 확인 시각 1행 — user_id가 PK 자체다. users CASCADE, 개별 삭제 경로는 없다. */
	public static final ArchiveTable NOTICE_SEEN = new ArchiveTable(
			"app.notice_seen", List.of("user_id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/**
	 * 유저의 브랜드 연결. deleted_at UPDATE(soft-delete)는 행이 안 사라지므로 아카이브 대상이 아니다
	 * — 여기서 다루는 건 users CASCADE로 행 자체가 지워지는 탈퇴 경로뿐(BrandLinkRepository의
	 * softDeleteLink·softDeleteAllActiveByUser는 UPDATE라 archiveWriter를 타지 않는다).
	 */
	public static final ArchiveTable BRAND_MONITORINGS = new ArchiveTable(
			"app.brand_monitorings", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/**
	 * 직접 등록 매핑. users CASCADE + monitoring_items CASCADE 이중 참조였으나, monitoring_items
	 * 쪽은 더 이상 CASCADE가 아니다(V20260811090500 — item 삭제 시 이 행이 아카이브 없이 사라지는
	 * 것을 막으려고 명시 FK로 바꿨다. ArchiveCascadeReachabilityTest가 monitoring_items의 CASCADE
	 * 자식이 0개여야 한다고 강제한다). 그 결과 두 삭제 경로 모두 이 행을 먼저 지워야 한다 —
	 * 그러지 않으면 부모(monitoring_items)를 지울 때 이 행이 남아 FK 위반이 난다:
	 * <ul>
	 *   <li>등록 롤백 — MonitoringItemRepository.delete가 item보다 먼저 이 행을 아카이브·삭제</li>
	 *   <li>탈퇴 — users CASCADE(간접적으로 monitoring_items도 함께 지운다)에 앞서
	 *       UserRepository.deleteAccount가 이 행을 saved_contents와 같은 방식으로 명시 삭제</li>
	 * </ul>
	 */
	public static final ArchiveTable BRAND_DIRECT_POSTS = new ArchiveTable(
			"app.brand_direct_posts", List.of("user_id", "short_code"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/**
	 * 어드민 작성 제품 공지. users FK가 없어 탈퇴와 무관 — 어드민 개별 삭제(NoticeRepository.delete,
	 * ArchiveReason.NOTICE_DELETED)로만 아카이브된다. userScopeWhere는 호출되지 않는다("false"로
	 * 막아둔다 — 계정 삭제 경로 자체가 없다).
	 */
	public static final ArchiveTable NOTICES = new ArchiveTable(
			"app.notices", List.of("id"), null,
			List.of(), "false");

	/** notices의 CASCADE 자식(position 순 항목). notices와 같은 이유로 계정 삭제와 무관하다. */
	public static final ArchiveTable NOTICE_ITEMS = new ArchiveTable(
			"app.notice_items", List.of("id"), null,
			List.of(), "false");

	/**
	 * 브랜드 direct 등록 요청 헤더(2026-08-18 direct 통합 §T6·§T13) — users CASCADE(직접 FK). 자식
	 * {@link #BRAND_POST_REGISTRATION_ENTRIES}와 함께 MONITORING_REGISTRATIONS·
	 * MONITORING_REGISTRATION_ENTRIES 짝과 같은 위상이다.
	 */
	public static final ArchiveTable BRAND_POST_REGISTRATIONS = new ArchiveTable(
			"app.brand_post_registrations", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/** user_id 컬럼이 없다 — registration을 거쳐야 유저에 닿는다(간접 CASCADE, MONITORING_REGISTRATION_ENTRIES 동형). */
	public static final ArchiveTable BRAND_POST_REGISTRATION_ENTRIES = new ArchiveTable(
			"app.brand_post_registration_entries", List.of("registration_id", "seq"), null,
			List.of(),
			"t.registration_id IN (SELECT id FROM app.brand_post_registrations WHERE user_id = :userId)");

	/**
	 * 브랜드 풀 게시물↔캠페인 N:M 링크(2026-08-18 direct 통합 §결정 3·§T13) — users CASCADE(직접 FK).
	 * {@code campaign_id} FK는 CASCADE가 아니다 — 캠페인 삭제 경로(CampaignRepository.delete)가 이
	 * 테이블을 명시적으로 먼저 아카이브·삭제해야 한다(순서를 어기면 FK 위반).
	 */
	public static final ArchiveTable BRAND_POST_CAMPAIGNS = new ArchiveTable(
			"app.brand_post_campaigns", List.of("brand_id", "short_code", "campaign_id"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/**
	 * 유저 스코프 해시태그 태그 원장(2026-08-19 설계, 상호작용 사용자 스코프 개정) — users
	 * CASCADE(직접 FK). BRAND_DIRECT_POSTS와 같은 위상(자식 없음, 단순 유저 소유 매핑).
	 */
	public static final ArchiveTable BRAND_HASHTAG_TAGS = new ArchiveTable(
			"app.brand_hashtag_tags", List.of("user_id", "brand_id", "tag"), "t.user_id",
			List.of(), "t.user_id = :userId");

	/**
	 * AI 어시스턴트 질문 로그(2026-08-27 브랜드 모니터링 AI 어시스턴트 PoC) — users CASCADE(직접
	 * FK). BRAND_HASHTAG_TAGS와 같은 위상(자식 없음, 순정 CASCADE 자식이라 명시 DELETE 불필요 —
	 * USERS 삭제 시 함께 사라진다).
	 *
	 * <p>question·answer는 자유 텍스트라 다른 아카이브 테이블과 달리 마스킹 없이 그대로 이관한다 —
	 * 실수가 아니라 의식적 결정이다(M7). 질문 로그 자체가 이 PoC의 산출물(사용자가 어떤 질문을
	 * 하는지가 다음 이터레이션의 입력이다)이고, 탈퇴 이관 아카이브는 접근 통제된 저장소라 원문
	 * 보존이 USER_PII 마스킹 컬럼들과 같은 노출 위험을 지지 않는다고 판단했다.
	 */
	public static final ArchiveTable AI_CHAT_LOGS = new ArchiveTable(
			"app.ai_chat_logs", List.of("id"), "t.user_id",
			List.of(), "t.user_id = :userId");

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
			USERS,
			NOTICE_SEEN,
			BRAND_MONITORINGS,
			BRAND_DIRECT_POSTS,
			NOTICES,
			NOTICE_ITEMS,
			BRAND_POST_REGISTRATIONS,
			BRAND_POST_REGISTRATION_ENTRIES,
			BRAND_POST_CAMPAIGNS,
			BRAND_HASHTAG_TAGS,
			AI_CHAT_LOGS);

	/**
	 * 탈퇴 시 이관 대상과 순서 — 자식 14개 + users. CATALOG의 부분집합이어야 한다(테스트로 강제).
	 * 이관(INSERT)은 전부 삭제(DELETE)보다 먼저 일어나므로 순서 자체가 정확성에 영향을 주진
	 * 않지만, 자식 → 부모 순으로 읽히게 둔다. NOTICES·NOTICE_ITEMS는 users FK가 없어 여기 없다
	 * (클래스 주석 참고).
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
			NOTICE_SEEN,
			BRAND_MONITORINGS,
			BRAND_DIRECT_POSTS,
			BRAND_POST_REGISTRATION_ENTRIES,
			BRAND_POST_REGISTRATIONS,
			BRAND_POST_CAMPAIGNS,
			BRAND_HASHTAG_TAGS,
			AI_CHAT_LOGS,
			USERS);

	private ArchiveTables() {
	}
}
