package com.celfit.was.monitoring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_campaigns CRUD — 모니터링 비활성이어도 무해한 app 테이블 접근이라 항상 활성.
 * 주입되는 JdbcClient는 기본(analysis DB) 것 — monitoring DB 접근은 MonitoringReadRepository 몫.
 */
@Repository
public class MonitoringCampaignMappingRepository {

	private final JdbcClient jdbcClient;

	public MonitoringCampaignMappingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 등록 1단계 — target_id NULL의 pending 행 선저장. 크래시 후에도 멱등키가 남아 replay 가능. */
	public long insertPending(long userId, UUID registrationKey) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_campaigns (user_id, registration_key)
				VALUES (:userId, :key)
				RETURNING id
				""")
				.param("userId", userId)
				.param("key", registrationKey)
				.query(Long.class)
				.single();
	}

	/** 등록 2단계 — monitoring 호출 성공 후 target_id 확정. */
	public void confirmTarget(UUID registrationKey, long targetId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_campaigns SET target_id = :targetId
				WHERE registration_key = :key
				""")
				.param("targetId", targetId)
				.param("key", registrationKey)
				.update();
	}

	/** 등록 확정 실패(monitoring이 target을 안 만든 경우) 시 pending 행 정리. */
	public void deleteByKey(UUID registrationKey) {
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns WHERE registration_key = :key")
				.param("key", registrationKey)
				.update();
	}

	/** 소유 검증 — (user, target) 매핑이 있어야 명령을 위임한다. pending(target NULL)은 안 잡힌다. */
	public Optional<MonitoringCampaignMapping> findByUserAndTarget(long userId, long targetId) {
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_campaigns
				WHERE user_id = :userId AND target_id = :targetId
				""")
				.param("userId", userId)
				.param("targetId", targetId)
				.query(MonitoringCampaignMapping.class)
				.optional();
	}

	public List<MonitoringCampaignMapping> findByUser(long userId) {
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_campaigns
				WHERE user_id = :userId
				ORDER BY created_at DESC
				""")
				.param("userId", userId)
				.query(MonitoringCampaignMapping.class)
				.list();
	}

	/** 유저의 "캠페인 삭제" — cancel 명령 성공 후 호출 (순서는 서비스가 보장). */
	public void deleteByUserAndTarget(long userId, long targetId) {
		jdbcClient.sql("""
				DELETE FROM app.monitoring_campaigns
				WHERE user_id = :userId AND target_id = :targetId
				""")
				.param("userId", userId)
				.param("targetId", targetId)
				.update();
	}
}
