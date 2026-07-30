package com.celfit.was.monitoring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_items CRUD — 모니터링 비활성이어도 무해한 app 테이블 접근이라 항상 활성.
 * 주입되는 JdbcClient는 기본(analysis DB) 것 — monitoring DB 접근은 MonitoringReadRepository 몫.
 * V15로 테이블이 v3 추적 행(monitoring_items)으로 재구성됨 — 이 리포지토리는 과도기 어댑터로,
 * 아래 insertPending은 임시 기본값을 채워 넣는다(후속 태스크에서 v3 시그니처로 교체 예정).
 */
@Repository
public class MonitoringCampaignMappingRepository {

	private final JdbcClient jdbcClient;

	public MonitoringCampaignMappingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 등록 1단계 — target_id NULL의 pending 행 선저장. replay는 이 키로 monitoring 호출을
	 * 반복하는 것(서비스 계층 몫)이지 이 메서드 재호출이 아니다 — 같은 키 재INSERT는 UNIQUE 위반.
	 * 크래시로 남은 pending 행의 키 재사용은 프론트 API 작업에서 닫는다.
	 * mode·input_value·tracking_days·registered_on은 v3 컬럼이라 NOT NULL — 이 메서드는 구v2
	 * 시그니처(userId, key)만 받으므로 임시 기본값(mode='url', input_value='', tracking_days=1,
	 * registered_on=CURRENT_DATE)을 채운다. 후속 태스크에서 v3 시그니처로 교체 예정.
	 * ⚠️ 배포 순서 경고: Task 2(v3 시그니처 교체) 전에 monitoring.enabled=true로 배포하면
	 * account 모드 등록도 이 임시값(mode='url' 등)으로 저장된다 — 교체 전 활성화 금지.
	 */
	public long insertPending(long userId, UUID registrationKey) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_items
					(user_id, registration_key, mode, input_value, tracking_days, registered_on)
				VALUES (:userId, :key, 'url', '', 1, CURRENT_DATE)
				RETURNING id
				""")
				.param("userId", userId)
				.param("key", registrationKey)
				.query(Long.class)
				.single();
	}

	/** 등록 2단계 — monitoring 호출 성공 후 target_id 확정. 매핑이 없으면(키 무효·정리됨) 예외. */
	public void confirmTarget(UUID registrationKey, long targetId) {
		int updated = jdbcClient.sql("""
				UPDATE app.monitoring_items SET target_id = :targetId
				WHERE registration_key = :key
				""")
				.param("targetId", targetId)
				.param("key", registrationKey)
				.update();
		if (updated != 1) {
			throw new IllegalStateException("등록 확정 실패 — registration_key 매핑 없음: " + registrationKey);
		}
	}

	/** 등록 확정 실패(monitoring이 target을 안 만든 경우) 시 pending 행 정리. */
	public void deleteByKey(UUID registrationKey) {
		jdbcClient.sql("DELETE FROM app.monitoring_items WHERE registration_key = :key")
				.param("key", registrationKey)
				.update();
	}

	/** 소유 검증 — (user, target) 매핑이 있어야 명령을 위임한다. pending(target NULL)은 안 잡힌다. */
	public Optional<MonitoringCampaignMapping> findByUserAndTarget(long userId, long targetId) {
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_items
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
				FROM app.monitoring_items
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
				DELETE FROM app.monitoring_items
				WHERE user_id = :userId AND target_id = :targetId
				""")
				.param("userId", userId)
				.param("targetId", targetId)
				.update();
	}
}
