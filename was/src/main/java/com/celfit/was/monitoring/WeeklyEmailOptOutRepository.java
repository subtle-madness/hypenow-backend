package com.celfit.was.monitoring;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 주간 리포트 메일 수신 토글(2026-08-27 주간 개편 §5) — 저장은 기존 app.monitoring_email_opt_outs의
 * event_type='WEEKLY_DIGEST' 행이다. <b>행 없음 = 수신(기본 on)</b>, 행 있음 = 수신 거부.
 *
 * <p>별도 테이블을 만들지 않은 이유: 이 테이블은 이미 아카이브 카탈로그·탈퇴 이관 순서에
 * 배선돼 있어(ArchiveTables.MONITORING_EMAIL_OPT_OUTS) 새 테이블이었다면 필요했을 배선이
 * 통째로 불필요하다. 구 4종 어휘 행은 이번 릴리스에서 읽지도 쓰지도 않는다 — 롤링 창의
 * 구버전 was가 아직 그 행을 쓰기 때문에 남겨 두고, contract 단계에서 정리한다.
 */
@Repository
public class WeeklyEmailOptOutRepository {

	/** 주간 토글 전용 event_type 값 — 마이그레이션 V20260827135725가 CHECK에 추가했다. */
	private static final String WEEKLY_DIGEST = "WEEKLY_DIGEST";

	private final JdbcClient jdbcClient;

	public WeeklyEmailOptOutRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** true면 주간 리포트 메일을 보내지 않는다. 행이 없으면(기본) false. */
	public boolean isOptedOut(long userId) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM app.monitoring_email_opt_outs
				    WHERE user_id = :userId AND event_type = :eventType
				)
				""")
				.param("userId", userId)
				.param("eventType", WEEKLY_DIGEST)
				.query(Boolean.class)
				.single());
	}

	/** 멱등 — 이미 거부 상태면 그대로 둔다. */
	public void optOut(long userId) {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:userId, :eventType)
				ON CONFLICT DO NOTHING
				""")
				.param("userId", userId)
				.param("eventType", WEEKLY_DIGEST)
				.update();
	}

	/** 멱등 — 행이 없어도(이미 수신) 에러 없이 통과. */
	public void optIn(long userId) {
		jdbcClient.sql("""
				DELETE FROM app.monitoring_email_opt_outs
				WHERE user_id = :userId AND event_type = :eventType
				""")
				.param("userId", userId)
				.param("eventType", WEEKLY_DIGEST)
				.update();
	}
}
