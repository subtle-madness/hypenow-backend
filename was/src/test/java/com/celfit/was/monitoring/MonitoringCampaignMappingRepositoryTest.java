package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringCampaignMappingRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringCampaignMappingRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		// email unique — 테스트 간 충돌 방지로 매번 랜덤
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "mon-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 등록_2단계_선저장과_확정() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);

		// 1단계 직후: pending (target_id NULL) — 소유 조회엔 아직 안 잡힘
		assertThat(repository.findByUserAndTarget(userId, 17L)).isEmpty();

		repository.confirmTarget(key, 17L);

		MonitoringCampaignMapping mapping = repository.findByUserAndTarget(userId, 17L).orElseThrow();
		assertThat(mapping.registrationKey()).isEqualTo(key);
		assertThat(mapping.targetId()).isEqualTo(17L);
	}

	@Test
	void 소유_검증은_다른_유저의_target을_거른다() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);
		repository.confirmTarget(key, 42L);

		assertThat(repository.findByUserAndTarget(userId + 999, 42L)).isEmpty();
	}

	@Test
	void 키_삭제는_pending_행을_지운다() {
		UUID key = UUID.randomUUID();
		repository.insertPending(userId, key);
		repository.deleteByKey(key);

		assertThat(repository.findByUser(userId)).isEmpty();
	}

	@Test
	void 유저_target_삭제와_목록_조회() {
		UUID key1 = UUID.randomUUID();
		UUID key2 = UUID.randomUUID();
		repository.insertPending(userId, key1);
		repository.confirmTarget(key1, 1L);
		repository.insertPending(userId, key2);
		repository.confirmTarget(key2, 2L);

		assertThat(repository.findByUser(userId)).hasSize(2);

		repository.deleteByUserAndTarget(userId, 1L);

		assertThat(repository.findByUser(userId)).hasSize(1);
		assertThat(repository.findByUserAndTarget(userId, 1L)).isEmpty();
	}

	@Test
	void target_다건_역방향_조회() {
		UUID key1 = UUID.randomUUID();
		UUID key2 = UUID.randomUUID();
		repository.insertPending(userId, key1);
		repository.confirmTarget(key1, 101L);
		repository.insertPending(userId, key2);
		repository.confirmTarget(key2, 102L);

		List<MonitoringCampaignMapping> found = repository.findByTargetIds(List.of(101L, 102L, 999L));

		assertThat(found).hasSize(2);
		assertThat(found).allSatisfy(m -> assertThat(m.userId()).isEqualTo(userId));
		assertThat(repository.findByTargetIds(List.of())).isEmpty();
	}

	@Test
	void 무효_키로_확정하면_예외() {
		assertThatThrownBy(() -> repository.confirmTarget(UUID.randomUUID(), 1L))
				.isInstanceOf(IllegalStateException.class);
	}
}
