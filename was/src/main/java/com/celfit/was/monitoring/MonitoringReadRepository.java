package com.celfit.was.monitoring;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * monitoring DB 조회(계약 §3) — 베이스 테이블 4개만, 초안 뷰는 monitoring 확정 후 반영(스펙 §5).
 * 주입되는 JdbcClient는 MonitoringConfig가 내부 생성한 읽기 전용 커넥션 — 쓰기 시도는
 * DB 권한 오류로 fail-closed. app 스키마·분석 결과와의 크로스 DB 조인 금지(조합은 was 코드).
 *
 * 주의 2가지: ① 이 리포지토리는 targetId·username을 검증 없이 그대로 조회한다 — 유저 소유
 * 스코프는 호출자(향후 컨트롤러) 책임이며, 반드시 app 매핑(CampaignRepository.findByIdAndUser)에서
 * 얻은 값만 넘길 것(임의 id 통과 = 남의 캠페인 열람). ② 명령 계층과 달리 예외를 승격하지 않고
 * DataAccessException을 원시 전파한다 — 조회 실패 처리 방식은 컨트롤러 작업 때 결정(의도된 보류).
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
				       registered_at, closed_at, last_fetched_at, fail_reason
				FROM target
				WHERE id IN (:ids)
				ORDER BY registered_at DESC
				""")
				.param("ids", targetIds)
				.query(TargetRow.class)
				.list();
	}

	public List<CandidateRow> findCandidates(long targetId) {
		return jdbc.sql("""
				SELECT id, target_id, short_code, detected_at, caption_excerpt, status
				FROM detected_candidate
				WHERE target_id = :targetId
				ORDER BY detected_at DESC
				""")
				.param("targetId", targetId)
				.query(CandidateRow.class)
				.list();
	}

	/** 워터마크 이후 신규 PENDING(계약 §3 알람 쿼리 그대로) — 이메일 크론 대비. */
	public List<PendingCandidate> findPendingCandidatesSince(Instant since) {
		return jdbc.sql("""
				SELECT c.id, c.target_id, c.short_code, c.caption_excerpt, c.detected_at, t.username
				FROM detected_candidate c JOIN target t ON t.id = c.target_id
				WHERE c.status = 'PENDING' AND c.detected_at > :since
				ORDER BY c.detected_at
				""")
				.param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
				.query(PendingCandidate.class)
				.list();
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
}
