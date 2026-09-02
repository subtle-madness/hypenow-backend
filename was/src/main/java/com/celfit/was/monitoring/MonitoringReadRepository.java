package com.celfit.was.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * monitoring DB 조회(계약 §3, v2.2) — 베이스 테이블 직접 SELECT, 초안 뷰는 조인하지 않는다(계약
 * "두 뷰를 조인하지 말 것" 주의사항 및 스펙 §5). 주입되는 JdbcClient는 MonitoringConfig가 내부
 * 생성한 읽기 전용 커넥션 — 쓰기 시도는 DB 권한 오류로 fail-closed. app 스키마·분석 결과와의
 * 크로스 DB 조인 금지(조합은 was 코드).
 *
 * 주의 2가지: ① 이 리포지토리는 targetId·username·shortCode를 검증 없이 그대로 조회한다 — 유저
 * 소유 스코프는 호출자(6.26 어셈블러 등) 책임이며, 반드시 app 매핑(app.monitoring_items.target_id)에서
 * 얻은 값만 넘길 것(임의 id 통과 = 남의 캠페인 열람). ② 명령 계층과 달리 예외를 승격하지 않고
 * DataAccessException을 원시 전파한다 — 조회 실패 처리 방식은 컨트롤러 작업 때 결정(의도된 보류).
 *
 * <p>배치 메서드(findPostMeta·findSnapshots·findComments·findProfileMeta·findLatestProfileSnapshots)는
 * 전부 "유저의 목록 전체를 한 SQL 왕복으로" 조회하기 위한 것 — 6.26 어셈블러가 항목 수만큼 반복
 * 호출하면 N+1이 된다. 단건 재조립(6.29·6.30)도 같은 메서드를 원소 1개짜리 컬렉션으로 재사용한다.
 */
public class MonitoringReadRepository {

	private final JdbcClient jdbc;

	public MonitoringReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<TargetRow> findTargets(Collection<Long> targetIds) {
		if (targetIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT id, type, username, short_code, keyword_rule::text AS keyword_rule, status,
				       tracked_short_code, tracked_since, registration_key, expires_at,
				       registered_at, closed_at, last_fetched_at, fail_reason,
				       user_id, tracked_hidden_at, fetch_failing, matched_keywords::text AS matched_keywords
				FROM target
				WHERE id IN (:ids)
				ORDER BY registered_at DESC
				""")
				.param("ids", targetIds)
				.query(TargetRow.class)
				.list();
	}

	/**
	 * target + 표시 부속(post_meta·profile_meta·최신 팔로워·last sweep) 통합 조회(2026-08-27 풀 대기
	 * 수리) — 6.26 목록 조립의 monitoring 왕복을 7회→3회로 줄이는 핵심. findTargets·lastSuccessfulSweepAt·
	 * findPostMeta·findProfileMeta·findLatestProfileSnapshots 5개 왕복을 한 SQL로 접는다(스냅샷·댓글은
	 * 행 수가 많아 별도 왕복 유지). 개별 메서드들은 단건 재조립(6.29·6.30)·어드민 경로가 계속 쓴다.
	 *
	 * <p>last_completed_at은 비상관 스칼라(CROSS JOIN)라 전 행 동일 — 호출부는 아무 행에서나 읽으면
	 * 되고, 결과가 0행이면 {@link #lastSuccessfulSweepAt()}로 폴백해야 한다(meta.lastCollectedAt은
	 * 추적 행이 없어도 응답 계약에 있다).
	 */
	public List<TargetDetailRow> findTargetDetails(Collection<Long> targetIds) {
		if (targetIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT t.id, t.type, t.username, t.short_code, t.keyword_rule::text AS keyword_rule, t.status,
				       t.tracked_short_code, t.tracked_since, t.registration_key, t.expires_at,
				       t.registered_at, t.closed_at, t.last_fetched_at, t.fail_reason,
				       t.user_id, t.tracked_hidden_at, t.fetch_failing, t.matched_keywords::text AS matched_keywords,
				       pm.short_code AS pm_short_code, pm.username AS pm_username,
				       pm.content_type AS pm_content_type, pm.uploaded_at AS pm_uploaded_at,
				       pm.caption AS pm_caption, pm.thumbnail_url AS pm_thumbnail_url,
				       pm.image_object_path AS pm_image_object_path,
				       pf.username AS pf_username, pf.display_name AS pf_display_name,
				       pf.profile_image_url AS pf_profile_image_url, pf.last_uploaded_at AS pf_last_uploaded_at,
				       pf.image_object_path AS pf_image_object_path,
				       ps.followers,
				       sw.last_completed_at
				FROM target t
				LEFT JOIN post_meta pm ON pm.short_code = t.tracked_short_code
				LEFT JOIN profile_meta pf ON pf.username = t.username
				LEFT JOIN LATERAL (
				    SELECT followers FROM profile_snapshot
				    WHERE username = t.username
				    ORDER BY captured_on DESC
				    LIMIT 1
				) ps ON true
				CROSS JOIN (SELECT max(completed_at) AS last_completed_at FROM sweep_run WHERE ok = true) sw
				WHERE t.id IN (:ids)
				ORDER BY t.registered_at DESC
				""")
				.param("ids", targetIds)
				.query(TargetDetailRow.class)
				.list();
	}

	/**
	 * 추적 게시물 표시 메타(계약 §3 post_meta, v2.2) — caption·uploadedAt·thumbnailUrl의 유일한 산지.
	 * image_object_path(트랙 KK 확장)는 monitoring 자체 썸네일 아카이브 결과 — null이면 아직 아카이브 전.
	 */
	public List<PostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url, image_object_path
				FROM post_meta
				WHERE short_code IN (:shortCodes)
				""")
				.param("shortCodes", shortCodes)
				.query(PostMetaRow.class)
				.list();
	}

	/**
	 * 여러 게시물의 스냅샷을 한 왕복으로(계약 §3 post_snapshot) — 상한(maxCapturedOn)은 호출부가
	 * meta.lastCollectedAt의 KST 날짜를 넘겨 워터마크 이후 스냅샷을 배제한다(6.26 계약). 하한(등록일·
	 * tracked_since)은 item별로 다를 수 있어 여기서 걸지 않고 호출부가 결과를 필터링한다.
	 */
	public List<TrackedSnapshotRow> findSnapshots(Collection<String> shortCodes, LocalDate maxCapturedOn) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, captured_on, content_type, likes, likes_hidden, comments, views, saves, shares, shares_hidden, reposts
				FROM post_snapshot
				WHERE short_code IN (:shortCodes) AND captured_on <= :maxCapturedOn
				ORDER BY short_code, captured_on
				""")
				.param("shortCodes", shortCodes)
				.param("maxCapturedOn", maxCapturedOn)
				.query(TrackedSnapshotRow.class)
				.list();
	}

	/**
	 * 게시물별 최신 댓글 상한 {@code limit}건(계약 §3 post_comment) — commented_at DESC 윈도우로
	 * 게시물마다 상위 N건만 골라 한 왕복에 담는다(최신순 정렬은 계약, was가 재정렬).
	 */
	public List<PostCommentRow> findComments(Collection<String> shortCodes, int limit) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text
				FROM (
				    SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text,
				           row_number() OVER (PARTITION BY short_code ORDER BY commented_at DESC) AS rn
				    FROM post_comment
				    WHERE short_code IN (:shortCodes)
				) ranked
				WHERE rn <= :limit
				ORDER BY short_code, commented_at DESC
				""")
				.param("shortCodes", shortCodes)
				.param("limit", limit)
				.query(PostCommentRow.class)
				.list();
	}

	/**
	 * 계정 표시 메타(계약 §3 profile_meta, v1.1) — displayName·profileImageUrl·lastUploadedAt의 산지.
	 * image_object_path(설계 스펙 §3-1)는 monitoring 자체 아카이브 결과 — null이면 아직 아카이브 전.
	 */
	public List<ProfileMetaRow> findProfileMeta(Collection<String> usernames) {
		if (usernames.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT username, display_name, profile_image_url, last_uploaded_at, image_object_path
				FROM profile_meta
				WHERE username IN (:usernames)
				""")
				.param("usernames", usernames)
				.query(ProfileMetaRow.class)
				.list();
	}

	/** 계정별 최신 팔로워 스냅샷(6.26 어셈블러의 followers 산지) — 계정당 1행(captured_on DESC). */
	public List<ProfileSnapshotBatchRow> findLatestProfileSnapshots(Collection<String> usernames) {
		if (usernames.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT username, captured_on, followers, media_count
				FROM (
				    SELECT username, captured_on, followers, media_count,
				           row_number() OVER (PARTITION BY username ORDER BY captured_on DESC) AS rn
				    FROM profile_snapshot
				    WHERE username IN (:usernames)
				) ranked
				WHERE rn = 1
				""")
				.param("usernames", usernames)
				.query(ProfileSnapshotBatchRow.class)
				.list();
	}

	/**
	 * 게시물별 최신 스냅샷 1건(계약 §3 post_snapshot) — 어드민 모니터링 상태 유도(설계 2026-08-01 §4
	 * AdminMonitoringHealthService, collect_stalled 판정용)가 쓴다. {@link #findSnapshots}와 달리
	 * 워터마크(maxCapturedOn) 없이 순수 최신값만 필요해 별도 메서드를 둔다.
	 */
	public List<TrackedSnapshotRow> findLatestSnapshots(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, captured_on, content_type, likes, likes_hidden, comments, views, saves, shares, shares_hidden, reposts
				FROM (
				    SELECT short_code, captured_on, content_type, likes, likes_hidden, comments, views, saves, shares, shares_hidden, reposts,
				           row_number() OVER (PARTITION BY short_code ORDER BY captured_on DESC) AS rn
				    FROM post_snapshot
				    WHERE short_code IN (:shortCodes)
				) ranked
				WHERE rn = 1
				""")
				.param("shortCodes", shortCodes)
				.query(TrackedSnapshotRow.class)
				.list();
	}

	/**
	 * 마지막으로 성공한 배치 완료 시각(계약 §3 sweep_run, 6.26 meta.lastCollectedAt) — 성공한 실행이
	 * 없으면 null. 집계 함수라 행은 항상 1개지만 값 자체가 null일 수 있어(JdbcClient의 단일 컬럼
	 * 매핑은 null 값을 예외로 다뤄) RowMapper로 직접 ResultSet에서 읽는다.
	 */
	public OffsetDateTime lastSuccessfulSweepAt() {
		List<OffsetDateTime> rows = jdbc.sql("SELECT max(completed_at) AS last_completed_at FROM sweep_run WHERE ok = true")
				.query((rs, rowNum) -> rs.getObject("last_completed_at", OffsetDateTime.class))
				.list();
		return rows.isEmpty() ? null : rows.get(0);
	}

	public List<ProfileSnapshotRow> profileTimeseries(String username) {
		return jdbc.sql("""
				SELECT captured_on, followers, following, media_count
				FROM profile_snapshot
				WHERE username = :username
				ORDER BY captured_on
				""")
				.param("username", username)
				.query(ProfileSnapshotRow.class)
				.list();
	}

	/** 추적 게시물 추이(계약 §3 예시의 tracked_short_code 서브쿼리 그대로). */
	public List<PostSnapshotRow> postTimeseries(long targetId) {
		return jdbc.sql("""
				SELECT captured_on, content_type, likes, likes_hidden, comments, views, saves, shares, shares_hidden, reposts
				FROM post_snapshot
				WHERE short_code = (SELECT tracked_short_code FROM target WHERE id = :targetId)
				ORDER BY captured_on
				""")
				.param("targetId", targetId)
				.query(PostSnapshotRow.class)
				.list();
	}

	/**
	 * KST 날짜 구간([fromKstDate, toKstDateInclusive])의 알람 이벤트(갭 문서 A-1-2) — 주간
	 * 다이제스트 크론({@link com.celfit.was.monitoring.WeeklyDigestJob})의 유일한 alarm_event
	 * 입력이다. 워터마크 없이 구간 전체를 매번 다시 읽으므로 몇 번을 재실행해도 안전하다(호출부가
	 * 같은 주간 창을 넘기는 한 같은 결과를 재현 — WeeklyDigestJob 클래스 Javadoc "명시적 창" 절
	 * 참조). 하한은 fromKstDate의 KST 자정(포함), 상한은 toKstDateInclusive **다음 날**의 KST
	 * 자정(배타) — 고정 Clock 테스트에서 미래 날짜 이벤트가 섞이지 않도록 KST 날짜 경계 배제
	 * 시맨틱을 그대로 보존한다.
	 */
	public List<AlarmEventRow> findAlarmEventsBetween(LocalDate fromKstDate, LocalDate toKstDateInclusive) {
		OffsetDateTime from = fromKstDate.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
		OffsetDateTime to = toKstDateInclusive.plusDays(1).atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
		return jdbc.sql("""
				SELECT id, target_id, user_id, event_type, occurred_at
				FROM alarm_event
				WHERE occurred_at >= :from AND occurred_at < :to
				ORDER BY user_id, occurred_at
				""")
				.param("from", from)
				.param("to", to)
				.query(AlarmEventRow.class)
				.list();
	}

	/**
	 * 유저의 캠페인·콘텐츠 모니터링 콜 일별 집계 전량(2026-08-12 어드민 크롤링 비용 범위 확장) —
	 * 귀속은 monitoring이 콜 시점에 끝냈으므로(target_call_count가 유저 키) 브랜드와 달리 기간
	 * 계산이 없다. 행 수가 유저당 하루 1행이라 전량 조회가 싸다.
	 */
	public List<UserCallDailyRow> findDailyCallCounts(long userId) {
		return jdbc.sql("""
				SELECT called_on, calls
				FROM target_call_count
				WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(UserCallDailyRow.class)
				.list();
	}

	/**
	 * 전 유저 날짜별 콜 합(설계 2026-08-13 §3-4) — 어드민 전역 크롤링 비용 API의 캠페인·콘텐츠 몫.
	 * 한 콜이 여러 유저의 캠페인을 서빙하면 target_call_count에 유저마다 +1로 기록돼 있어, 이
	 * 합계 역시 그만큼 상한 쪽으로 치우친다(브랜드 공유와 같은 관점 — 계약 문서에 명시).
	 *
	 * <p>sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수다(record 컴포넌트가 long).
	 */
	public List<DailyCallSum> sumDailyCallCounts() {
		return jdbc.sql("""
				SELECT called_on, sum(calls)::bigint AS calls
				FROM target_call_count
				GROUP BY called_on
				""")
				.query(DailyCallSum.class)
				.list();
	}

	/** target_call_count 1행 — calledOn은 KST 달력일(집계 경계 계산도 KST — 쓰는 쪽과 정합). */
	public record UserCallDailyRow(LocalDate calledOn, long calls) {
	}
}
