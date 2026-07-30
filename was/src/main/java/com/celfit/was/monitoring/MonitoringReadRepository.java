package com.celfit.was.monitoring;

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

	/** 추적 게시물 표시 메타(계약 §3 post_meta, v2.2) — caption·uploadedAt·thumbnailUrl의 유일한 산지. */
	public List<PostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url
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
				SELECT short_code, captured_on, content_type, likes, comments, views, saves, shares, reposts
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

	/** 계정 표시 메타(계약 §3 profile_meta, v1.1) — displayName·profileImageUrl·lastUploadedAt의 산지. */
	public List<ProfileMetaRow> findProfileMeta(Collection<String> usernames) {
		if (usernames.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT username, display_name, profile_image_url, last_uploaded_at
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
				SELECT captured_on, content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = (SELECT tracked_short_code FROM target WHERE id = :targetId)
				ORDER BY captured_on
				""")
				.param("targetId", targetId)
				.query(PostSnapshotRow.class)
				.list();
	}

	/**
	 * KST 달력일 기준 알람 이벤트(갭 문서 A-1-2, 다이제스트 크론의 유일한 입력).
	 * `(occurred_at AT TIME ZONE 'Asia/Seoul')::date`로 걸러 워터마크 없이 날짜 재계산이 가능하게 한다
	 * (V1InfluencerDiscoveryRepository의 KST date 캐스팅 관용구와 동일).
	 */
	public List<AlarmEventRow> findAlarmEventsOn(LocalDate kstDate) {
		return jdbc.sql("""
				SELECT id, target_id, user_id, event_type, occurred_at
				FROM alarm_event
				WHERE (occurred_at AT TIME ZONE 'Asia/Seoul')::date = :kstDate
				ORDER BY user_id, occurred_at
				""")
				.param("kstDate", kstDate)
				.query(AlarmEventRow.class)
				.list();
	}
}
