package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * monitoring DB의 브랜드 태그 모니터링 테이블 조회(2026-08-07 스펙 §3-2) — brand_account·
 * brand_tagged_post·brand_post_meta·brand_post_snapshot·brand_post_comment·author_profile만 읽는다.
 * 브랜드 파이프라인은 캠페인 테이블(post_meta·post_snapshot 등)과 한 줄도 겹치지 않는 전용
 * 스키마라(08-06 개정), 레거시 테이블·뷰와 조인하지 않는다 — 조합이 필요하면 was 코드에서.
 *
 * <p>주입되는 JdbcClient는 {@link MonitoringConfig}가 내부 생성한 읽기 전용 커넥션이다
 * ({@link MonitoringReadRepository}와 같은 풀). app 스키마·분석 결과와의 크로스 DB 조인 금지.
 *
 * <p>주의: 이 리포지토리는 brandId를 검증 없이 조회한다 — 소유권 스코프는 호출자 책임이며,
 * 반드시 app 매핑(app.brand_monitorings.brand_id)에서 얻은 값만 넘길 것(임의 id 통과 = 남의
 * 브랜드 열람). 명령 계층과 달리 예외를 승격하지 않고 DataAccessException을 원시 전파한다.
 *
 * <p>배치 메서드(findPostMeta·findSnapshots·findComments·findAuthors·findAuthorsByUsername)는
 * "윈도우 안 게시물 전체를 한 SQL 왕복으로" 가져오기 위한 것 — 게시물 수만큼 반복 호출하면 N+1이
 * 된다. 전부 빈 컬렉션을 선처리한다({@code IN ()} 은 SQL 오류).
 */
public class BrandReadRepository {

	private final JdbcClient jdbc;

	public BrandReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 브랜드 계정 1행 — followers·biography 등은 매일 스윕이 갱신하는 최신 관측값(추이는
	 * brand_profile_snapshot). backfillCompletedAt null = 최초 백필 미완("수집 준비 중" 판별),
	 * backfillError는 그 실패 사유(스윕 성공 시 monitoring이 클리어).
	 */
	public Optional<BrandAccountRow> findAccount(long brandId) {
		return jdbc.sql("""
				SELECT id, username, last_swept_on, last_swept_at, registered_at, backfill_completed_at,
				       backfill_error, followers, following, media_count, biography, full_name,
				       profile_pic_url, is_verified, external_url, status, image_object_path,
				       collection_months,
				       COALESCE(collection_started_at, registered_at) AS collection_started_at
				FROM brand_account
				WHERE id = :brandId
				""")
				.param("brandId", brandId)
				.query(BrandAccountRow.class)
				.optional();
	}

	/**
	 * 브랜드 풀(tagged ∪ direct) 게시물 조회(2026-08-18 direct 통합 §3-3, T10 — 옛
	 * {@code findTaggedPostsInWindow}/{@code findEnrichedTaggedPostsInWindow} 통합) — cutoff(365일
	 * 컷) 이후 taken_at인 행 <b>또는</b> direct 등록 행(창 예외, direct는 등록 시점이 아무리 오래돼도
	 * 표시된다)을 최신순으로 돌려준다. 개수 상한은 정책 v1(08-09)에서 폐지 — 모수는 수집 편입 컷
	 * (365일)이 이미 제한하고, 상한을 두면 12개월치가 많은 브랜드의 오래된 게시물이 소리 없이 잘린다.
	 *
	 * @param enrichedOnly true면 <b>보강 정산분만</b>(enriched_at IS NOT NULL) — 게시자 프로필·댓글이
	 *        붙기 전의 반쯤 빈 카드를 FE에 내보내지 않는다는 표시 표면 계약이다(2026-08-13 완결 배치
	 *        서빙 스펙 §5). 게시물 목록·상세와 그 {@code meta.counts}가 이 경로를 쓴다
	 *        ({@code BrandPostAssembler}의 {@code ENRICHED_ONLY}).
	 *        <p>false(전량)는 "있는데 없다고 답하면 안 되는" 판정 전용 — 캠페인 콘텐츠 존재 판정
	 *        (없으면 NOT_FOUND로 떨어진다), 직접 등록 중복 판정(놓치면 direct로 이중 등록된다), 성과
	 *        대시보드 지표 집계(미정산분도 스냅샷 지표는 이미 있어서 빼면 과소 계상)에 쓴다. 정산
	 *        여부는 <b>표시</b> 판정이지 존재 판정이 아니다(2026-08-13 완결 배치 서빙 리뷰 결정).
	 *
	 *        <p>정산은 "보강 <b>시도</b>가 끝났다"는 뜻이지 "필드가 다 찼다"가 아니다 — 게시자 조회가
	 *        404·타임아웃으로 소진되면 그 필드가 빈 채로 정산된다(실측 404 2%·타임아웃 1%). FE의 빈
	 *        필드 방어는 계속 필요하다.
	 */
	public List<BrandTaggedPostRow> findBrandPostsInWindow(long brandId, OffsetDateTime cutoff,
			boolean enrichedOnly) {
		String enrichedFilter = enrichedOnly ? " AND enriched_at IS NOT NULL" : "";
		return jdbc.sql("""
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at
				FROM brand_tagged_post
				WHERE brand_id = :brandId
				  AND ( taken_at >= :cutoff OR direct_registered_at IS NOT NULL )
				""" + enrichedFilter + """

				ORDER BY taken_at DESC
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.query(BrandTaggedPostRow.class)
				.list();
	}

	/**
	 * 실수집 깊이 — 열거(tagged)로 편입된 게시물의 최고령 taken_at(성과 대시보드 covered 판정,
	 * 2026-08-19). 수집 개수 상한(collection-post-limit)이 백필·스윕 열거를 최신 게시물 컷에서 끊으므로
	 * "이 시각보다 깊은 기간은 열거가 닿았다고 보장할 수 없다"의 경계값이다. direct-only 행
	 * (tag_detected_at IS NULL)은 창 예외라 아무리 오래된 게시물도 등록될 수 있어 포함하면 열거
	 * 깊이를 과대평가한다 — 제외. tagged 게시물이 하나도 없으면 empty(컷 자체가 불가능한 상태).
	 */
	public Optional<OffsetDateTime> findOldestEnumeratedTakenAt(long brandId) {
		return jdbc.sql("""
				SELECT taken_at FROM brand_tagged_post
				WHERE brand_id = :brandId AND tag_detected_at IS NOT NULL
				ORDER BY taken_at
				LIMIT 1
				""")
				.param("brandId", brandId)
				.query(OffsetDateTime.class)
				.optional();
	}

	/** 태그 게시물 표시 메타(게시물 전역 최신 1행) — caption·thumbnailUrl·영상/유료협찬 필드의 산지. */
	public List<BrandPostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				       video_url, video_duration, is_paid_partnership, image_object_path,
				       ad_verdict, ad_violations::text AS ad_violations_json, ad_evidence::text AS ad_evidence_json
				FROM brand_post_meta
				WHERE short_code IN (:shortCodes)
				""")
				.param("shortCodes", shortCodes)
				.query(BrandPostMetaRow.class)
				.list();
	}

	/**
	 * 게시물별 일 단위 지표 시계열 전량(오름차순) — 브랜드 스냅샷은 윈도우 이탈 후에도 영구
	 * 보존이라 상한(워터마크)을 두지 않는다. views는 DDL 주석대로 이미 화면 합산값(IG 몫 + FB 몫)이라
	 * fb_plays를 따로 읽지 않는다.
	 */
	public List<BrandSnapshotRow> findSnapshots(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, captured_on, content_type, likes, likes_hidden, comments, views,
				       saves, shares, shares_hidden, reposts
				FROM brand_post_snapshot
				WHERE short_code IN (:shortCodes)
				ORDER BY short_code, captured_on
				""")
				.param("shortCodes", shortCodes)
				.query(BrandSnapshotRow.class)
				.list();
	}

	/**
	 * 게시물별 최신 댓글 상한 {@code perPostLimit}건(레거시 findComments와 같은 윈도우 관용구).
	 *
	 * <p>상한을 SQL 단계에서 자르는 이유: 브랜드 댓글은 매 스윕이 최대 45건씩 누적 합집합으로 쌓고
	 * 행 삭제가 없어(08-06 스키마) 인기 게시물은 365일 윈도우 동안 수천 행까지 간다 — 상한 없이
	 * 가져오면 "윈도우 전 게시물 × 전량"이 한 번에 힙에 올라온다. 표시 상한을 호출부가 자르는
	 * 방식으로는 그 비용을 못 줄인다.
	 */
	public List<BrandCommentRow> findComments(Collection<String> shortCodes, int perPostLimit) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text
				FROM (
				    SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text,
				           row_number() OVER (PARTITION BY short_code ORDER BY commented_at DESC) AS rn
				    FROM brand_post_comment
				    WHERE short_code IN (:shortCodes)
				) ranked
				WHERE rn <= :perPostLimit
				ORDER BY short_code, commented_at DESC
				""")
				.param("shortCodes", shortCodes)
				.param("perPostLimit", perPostLimit)
				.query(BrandCommentRow.class)
				.list();
	}

	/** 게시자(인플루언서) 프로필 — 기본 경로. author_profile의 PK가 ig_user_id라 중복이 없다. */
	public List<AuthorRow> findAuthors(Collection<String> igUserIds) {
		if (igUserIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT ig_user_id, username, full_name, followers, profile_pic_url, is_verified,
				       image_object_path
				FROM author_profile
				WHERE ig_user_id IN (:igUserIds)
				""")
				.param("igUserIds", igUserIds)
				.query(AuthorRow.class)
				.list();
	}

	/**
	 * 게시자 프로필 폴백 경로 — brand_tagged_post.author_ig_user_id가 null(열거 셰이프에 따라 발생)일
	 * 때 username으로 찾는다. username은 author_profile의 유니크 키가 아니라(PK는 ig_user_id) 계정
	 * 이름 변경 이력 등으로 같은 이름이 여러 행일 수 있어, DISTINCT ON으로 계정당 최신
	 * (fetched_at DESC) 1행만 돌려준다 — 호출부의 username→프로필 맵 구성이 중복으로 깨지지 않게.
	 *
	 * <p>ig_user_id DESC 타이브레이크가 붙는 이유: fetched_at은 같은 스윕 트랜잭션에서 적재된 행들이
	 * 동일값이 될 수 있어(Postgres now()는 트랜잭션 내 불변) 동점 시 어느 행이 남는지가 비결정적이다.
	 */
	public List<AuthorRow> findAuthorsByUsername(Collection<String> usernames) {
		if (usernames.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (username)
				       ig_user_id, username, full_name, followers, profile_pic_url, is_verified,
				       image_object_path
				FROM author_profile
				WHERE username IN (:usernames)
				ORDER BY username, fetched_at DESC, ig_user_id DESC
				""")
				.param("usernames", usernames)
				.query(AuthorRow.class)
				.list();
	}

	/**
	 * 브랜드 해시태그 발견 게시물(스펙 2026-08-11 §5) — RELEVANT(관련 판정)만, cutoff 이후 taken_at
	 * 최신순 상한 limit건. tagged와 달리 상한을 SQL 단계에서 자른다 — 해시태그 발견분은 태그 게시물과
	 * 달리 등록 시점 검증이 없어 폭주 가능성이 tagged보다 크다(스펙 §5, 서빙 상한은 호출부 정책).
	 */
	public List<BrandHashtagPostRow> findHashtagPosts(long brandId, OffsetDateTime cutoff, int limit) {
		return jdbc.sql("""
				SELECT short_code, matched_tag, author_username, author_full_name,
				       author_profile_pic_url, taken_at, caption, content_type, thumbnail_url,
				       likes, comments, first_seen_at, image_object_path, author_image_object_path
				FROM brand_hashtag_post
				WHERE brand_id = :brandId AND verdict = 'RELEVANT' AND taken_at >= :cutoff
				ORDER BY taken_at DESC
				LIMIT :limit
				""")
				.param("brandId", brandId).param("cutoff", cutoff).param("limit", limit)
				.query(BrandHashtagPostRow.class).list();
	}

	/**
	 * 해시태그 발견 게시물의 매칭 태그 전체(2026-08-19, was 사용자 스코프 필터 지원) —
	 * {@code brand_hashtag_post_matched_tags}(모니터링 스윕이 게시물당 매칭된 활성 태그 전부를
	 * 기록하는 M:N 테이블, {@code matched_tag} 단일 컬럼과 별개). 조회자 본인의 태그 원장
	 * ({@code app.brand_hashtag_tags})과의 교집합 판정은 was 코드({@code BrandHashtagPostAssembler})가
	 * 한다 — 여기서는 monitoring DB만 읽는다(시스템 경계, app 스키마와 SQL 조인 금지).
	 */
	public List<MatchedTagRow> findMatchedTags(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, tag FROM brand_hashtag_post_matched_tags
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(MatchedTagRow.class).list();
	}

	/**
	 * 브랜드 풀 상태(2026-08-18 direct 통합 §2-3·§2-5·§T9) — 중복 판정(direct 등록)·발견 목록 판정
	 * (hashtag-posts)·취소 판정 공용 배치 조회. {@code tag_detected_at}·{@code direct_registered_at}
	 * 원시값 대신 boolean으로 파생해 호출부의 null 분기를 없앤다. 윈도우(365일 컷) 제한이 없는 순수
	 * 존재 판정이다 — {@link #findExistingTaggedShortCodes}와 같은 위상.
	 */
	public List<BrandPoolStatusRow> findBrandPoolStatus(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code,
				       tag_detected_at IS NOT NULL      AS tag_detected,
				       direct_registered_at IS NOT NULL AS direct_registered,
				       taken_at
				  FROM brand_tagged_post
				 WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(BrandPoolStatusRow.class)
				.list();
	}

	/**
	 * 후보 shortcode 중 그 브랜드의 tagged 게시물로 실재하는 것들(2026-08-17 승격 상태 필드 §스펙) —
	 * 윈도우(365일 컷) 제한이 없는 순수 존재 판정이다. 표시용 전량 조립({@link #findTaggedPostsInWindow}·
	 * {@link #findPostMeta} 등)을 태우지 않는 이유: 해시태그 발견 목록 조립에 매 요청 무거운 태그
	 * 게시물 전량 조립을 끼워 넣지 않기 위해서다(성능 — 존재 판정만 필요, 표시 필드는 불필요).
	 */
	public Set<String> findExistingTaggedShortCodes(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return Set.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return new LinkedHashSet<>(jdbc.sql("""
				SELECT short_code FROM brand_tagged_post WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(String.class).list());
	}

	/**
	 * 브랜드별 Hiker 콜 일별 집계(brand_call_count — 2026-08-12 어드민 크롤링 비용 설계) 전량 조회.
	 * 기간 필터·유저 귀속(연결 기간 판정)은 was 코드가 한다 — 링크 기간은 app 스키마 소관이라
	 * 크로스 DB 조인이 불가능하고, 행 수도 브랜드당 하루 1행이라 전량이 부담이 아니다.
	 */
	public List<BrandCallDailyRow> findDailyCallCounts(Collection<Long> brandIds) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT brand_id, called_on, calls
				FROM brand_call_count
				WHERE brand_id IN (:brandIds)
				""")
				.param("brandIds", brandIds)
				.query(BrandCallDailyRow.class)
				.list();
	}

	/**
	 * 전 브랜드 날짜별 콜 합(설계 2026-08-13 §3-4) — 어드민 전역 크롤링 비용 API의 브랜드 몫.
	 * 유저별 카드({@link #findDailyCallCounts})와 달리 연결 기간으로 자르지 않는다: 공유 브랜드는
	 * 유저마다 계상되므로 유저별 값을 더하면 실제로 나간 돈보다 커진다. 전사 합계는 브랜드 축에서
	 * 직접 합산해야 정확하다.
	 *
	 * <p>sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수다(record 컴포넌트가 long).
	 */
	public List<DailyCallSum> sumDailyCallCounts() {
		return jdbc.sql("""
				SELECT called_on, sum(calls)::bigint AS calls
				FROM brand_call_count
				GROUP BY called_on
				""")
				.query(DailyCallSum.class)
				.list();
	}

	/**
	 * brand_account 1행(was 계약 소비 컬럼만) — 08-07 확장 필드 포함. imageObjectPath는 monitoring
	 * 자체 프로필 이미지 아카이브 결과(V20260811023454) — null이면 아직 아카이브 전이라 서빙 측이
	 * 원본 CDN URL로 폴백한다.
	 *
	 * <p>collectionMonths는 자산 레벨 수집 창(공유 유저 간 max — 스펙 2026-08-12), collectionStartedAt은
	 * 확장 시 갱신되는 폴링 앵커(기존 행은 registered_at 폴백).
	 */
	public record BrandAccountRow(long id, String username, LocalDate lastSweptOn, OffsetDateTime lastSweptAt,
			OffsetDateTime registeredAt, OffsetDateTime backfillCompletedAt, String backfillError,
			Long followers, Long following, Long mediaCount, String biography, String fullName,
			String profilePicUrl, Boolean isVerified, String externalUrl, String status,
			String imageObjectPath, int collectionMonths, OffsetDateTime collectionStartedAt) {
	}

	/**
	 * brand_tagged_post 1행 — 게시물 지표·메타는 여기 없다(게시물 전역 테이블에서 배치 조회).
	 *
	 * <p>2026-08-18 direct 통합(T10)으로 3필드 추가: {@code lastCrawledAt}(브랜드 스윕·direct 단건
	 * 수집이 이 행을 마지막으로 건드린 시각 — {@code updatedAt} 산정에 쓴다), {@code tagDetectedAt}
	 * (태그 열거가 이 링크를 처음 만난 시각, null이면 direct-only), {@code directRegisteredAt}
	 * (직접 등록 시각, null이면 tagged-only — 둘 다 있으면 겹침 행). {@code source} 파생과
	 * {@code trackingStartedAt} 산정의 원천이다({@code BrandPostAssembler.brandPost}).
	 */
	public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
			OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount,
			OffsetDateTime lastCrawledAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt) {
	}

	/**
	 * brand_post_meta 1행. isPaidPartnership null = 응답 키 부재(판정 unknown 근거).
	 * imageObjectPath는 monitoring 자체 썸네일 아카이브 결과 — null이면 원본 CDN URL 폴백.
	 * adVerdict null = 미판정(광고 표기 판정 스펙 §4). adViolationsJson·adEvidenceJson은
	 * jsonb를 텍스트로 읽은 원문 — 파싱은 {@link BrandPostAssembler}가 한다(null 가능).
	 */
	public record BrandPostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String videoUrl, Double videoDuration,
			Boolean isPaidPartnership, String imageObjectPath, String adVerdict, String adViolationsJson,
			String adEvidenceJson) {
	}

	/** brand_post_snapshot 1행 — 컬럼 구성은 레거시 post_snapshot과 동형(캐리포워드 규칙 이식 전제). */
	public record BrandSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long likes,
			boolean likesHidden, Long comments, Long views, Long saves, Long shares, boolean sharesHidden,
			Long reposts) {
	}

	/** brand_post_comment 1행. */
	public record BrandCommentRow(String shortCode, String id, String author, String body, long likeCount,
			OffsetDateTime commentedAt, String ownerReplyText) {
	}

	/**
	 * author_profile 1행(was 계약 소비 컬럼만). imageObjectPath는 monitoring 자체 프로필 이미지
	 * 아카이브 결과(V20260807150500) — null이면 원본 CDN URL 폴백.
	 */
	public record AuthorRow(String igUserId, String username, String fullName, Long followers,
			String profilePicUrl, Boolean isVerified, String imageObjectPath) {
	}

	/**
	 * brand_hashtag_post 1행(RELEVANT만) — 프로필 보강·스냅샷이 없어(스펙 §5 보류) author 필드는
	 * 열거 관측값 그대로고 followers·isVerified는 아예 없다. likes·comments도 열거 시점 관측값이다.
	 * imageObjectPath는 monitoring 자체 썸네일 아카이브 결과 — null이면 원본 CDN URL 폴백.
	 * authorImageObjectPath는 작성자 프로필 사진 아카이브 결과(2026-08-17 신설,
	 * V20260817142317__hashtag_post_author_image_archive.sql) — null이면 원본 CDN URL 폴백.
	 */
	public record BrandHashtagPostRow(String shortCode, String matchedTag, String authorUsername,
			String authorFullName, String authorProfilePicUrl, OffsetDateTime takenAt, String caption,
			String contentType, String thumbnailUrl, Long likes, Long comments,
			OffsetDateTime firstSeenAt, String imageObjectPath, String authorImageObjectPath) {
	}

	/** brand_hashtag_post_matched_tags 1행({@link #findMatchedTags}) — shortcode 1건에 매칭 태그 1건. */
	public record MatchedTagRow(String shortCode, String tag) {
	}

	/** brand_call_count 1행 — calledOn은 KST 달력일(집계 경계 계산도 KST — 쓰는 쪽과 정합). */
	public record BrandCallDailyRow(long brandId, LocalDate calledOn, long calls) {
	}

	/**
	 * 브랜드 풀 상태 1행({@link #findBrandPoolStatus}) — taken_at은 direct-only 신규 행에도 항상 값이
	 * 있다(brand_tagged_post.taken_at NOT NULL, direct 등록도 단건 콜의 게시일로 채운다).
	 */
	public record BrandPoolStatusRow(String shortCode, boolean tagDetected, boolean directRegistered,
			OffsetDateTime takenAt) {
	}

	// 시딩(협업) username 조회(findSeededUsernames, brand_seeded_account 유래)는 2026-08-18 캠페인 도출
	// 개정으로 제거됐다 — seededAuthor는 이제 was BrandPostAssembler.resolveSeededUsernames가 기존
	// 캠페인 관리 데이터(MonitoringItemRepository·BrandDirectPostRepository·BrandPostCampaignRepository)
	// 에서 산출한다. brand_seeded_account 테이블·마이그레이션 자체는 expand-contract상 존치.
}
