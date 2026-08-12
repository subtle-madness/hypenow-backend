package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
				       profile_pic_url, is_verified, external_url, status
				FROM brand_account
				WHERE id = :brandId
				""")
				.param("brandId", brandId)
				.query(BrandAccountRow.class)
				.optional();
	}

	/**
	 * 브랜드 윈도우 안 태그 게시물 — cutoff(365일 컷) 이후 taken_at만, 최신순 전량. 컷은 호출부가
	 * 정한다(윈도우 정책은 상위 계층 계약). 개수 상한은 정책 v1(08-09)에서 폐지 — 모수는 수집 편입
	 * 컷(365일)이 이미 제한하고, 상한을 두면 12개월치가 많은 브랜드의 오래된 게시물이 소리 없이 잘린다.
	 */
	public List<BrandTaggedPostRow> findTaggedPostsInWindow(long brandId, OffsetDateTime cutoff) {
		return jdbc.sql("""
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count
				FROM brand_tagged_post
				WHERE brand_id = :brandId AND taken_at >= :cutoff
				ORDER BY taken_at DESC
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.query(BrandTaggedPostRow.class)
				.list();
	}

	/** 태그 게시물 표시 메타(게시물 전역 최신 1행) — caption·thumbnailUrl·영상/유료협찬 필드의 산지. */
	public List<BrandPostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				       video_url, video_duration, is_paid_partnership
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
				SELECT ig_user_id, username, full_name, followers, profile_pic_url, is_verified
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
				       ig_user_id, username, full_name, followers, profile_pic_url, is_verified
				FROM author_profile
				WHERE username IN (:usernames)
				ORDER BY username, fetched_at DESC, ig_user_id DESC
				""")
				.param("usernames", usernames)
				.query(AuthorRow.class)
				.list();
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

	/** brand_account 1행(was 계약 소비 컬럼만) — 08-07 확장 필드 포함. */
	public record BrandAccountRow(long id, String username, LocalDate lastSweptOn, OffsetDateTime lastSweptAt,
			OffsetDateTime registeredAt, OffsetDateTime backfillCompletedAt, String backfillError,
			Long followers, Long following, Long mediaCount, String biography, String fullName,
			String profilePicUrl, Boolean isVerified, String externalUrl, String status) {
	}

	/** brand_tagged_post 1행 — 게시물 지표·메타는 여기 없다(게시물 전역 테이블에서 배치 조회). */
	public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
			OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount) {
	}

	/** brand_post_meta 1행. isPaidPartnership null = 응답 키 부재(판정 unknown 근거). */
	public record BrandPostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String videoUrl, Double videoDuration,
			Boolean isPaidPartnership) {
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

	/** author_profile 1행(was 계약 소비 컬럼만). */
	public record AuthorRow(String igUserId, String username, String fullName, Long followers,
			String profilePicUrl, Boolean isVerified) {
	}

	/** brand_call_count 1행 — calledOn은 KST 달력일(집계 경계 계산도 KST — 쓰는 쪽과 정합). */
	public record BrandCallDailyRow(long brandId, LocalDate calledOn, long calls) {
	}
}
