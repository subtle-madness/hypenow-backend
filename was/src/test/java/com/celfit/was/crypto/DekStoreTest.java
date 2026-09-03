package com.celfit.was.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DEK 자동 부트스트랩(Task 11 — 계획 결함 수정) — vault 모드 첫 부팅이 {@code app.encryption_keys}
 * 행 부재로 실패하지 않도록 하는 {@link DekStore}의 조회·생성·동시성 로직을 검증한다.
 * Vault 실통신(KMS 인증·재시도)은 {@link FakeDekWrapper}로 대체 — 실 Vault 검증은 스테이징
 * 게이트(deploy/README.md §6-3 롤아웃 체크리스트)가 담당한다.
 */
class DekStoreTest extends IntegrationTest {

	@Autowired JdbcClient jdbcClient;

	@Test
	void 행이_없으면_부트스트랩_후_1행이_생기고_그_값을_언래핑해_반환한다() {
		int keyId = 101;
		DekStore store = new DekStore(jdbcClient, new FakeDekWrapper());

		DekBundle bundle = store.loadOrBootstrap(keyId);

		assertThat(bundle.aesKey()).hasSize(32);
		assertThat(bundle.hmacKey()).hasSize(32);
		Long count = jdbcClient.sql("SELECT count(*) FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId).query(Long.class).single();
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void 행이_이미_있으면_새로_생성하지_않고_기존_래핑본을_그대로_언래핑한다() {
		int keyId = 102;
		FakeDekWrapper wrapper = new FakeDekWrapper();
		byte[] existingPlain = randomDek();
		byte[] existingWrapped = wrapper.wrap(existingPlain);
		jdbcClient.sql("INSERT INTO app.encryption_keys (key_id, wrapped_dek) VALUES (:id, :w)")
				.param("id", keyId).param("w", existingWrapped).update();

		DekStore store = new DekStore(jdbcClient, wrapper);
		DekBundle bundle = store.loadOrBootstrap(keyId);

		assertThat(bundle.aesKey()).isEqualTo(Arrays.copyOfRange(existingPlain, 0, 32));
		assertThat(bundle.hmacKey()).isEqualTo(Arrays.copyOfRange(existingPlain, 32, 64));
		byte[] wrappedAfter = jdbcClient.sql("SELECT wrapped_dek FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId).query(byte[].class).single();
		assertThat(wrappedAfter).isEqualTo(existingWrapped); // 재래핑 안 됨 — 값 그대로
	}

	@Test
	void 동시_부트스트랩_시뮬레이션_먼저_커밋한_행이_ON_CONFLICT로_보존된다() {
		int keyId = 103;
		FakeDekWrapper wrapper = new FakeDekWrapper();
		// "먼저 이긴" 다른 인스턴스가 이미 등록해둔 행을 미리 심어둔다.
		byte[] winnerPlain = randomDek();
		byte[] winnerWrapped = wrapper.wrap(winnerPlain);
		jdbcClient.sql("INSERT INTO app.encryption_keys (key_id, wrapped_dek) VALUES (:id, :w)")
				.param("id", keyId).param("w", winnerWrapped).update();

		DekStore store = new DekStore(jdbcClient, wrapper);
		// 이 인스턴스가 뒤늦게 자기 몫의 부트스트랩(자체 생성 DEK 래핑 + INSERT)을 시도한다 —
		// 실제 롤링 배포에서 두 인스턴스가 동시에 loadOrBootstrap을 호출해 둘 다 "행 없음"을 보고
		// bootstrap()을 실행하는 상황의 재현. ON CONFLICT DO NOTHING이라 예외 없이 조용히 무시돼야 한다.
		store.bootstrap(keyId);

		byte[] wrappedInDb = jdbcClient.sql("SELECT wrapped_dek FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId).query(byte[].class).single();
		assertThat(wrappedInDb).isEqualTo(winnerWrapped); // 자기 생성분이 아니라 먼저 이긴 값 그대로

		DekBundle bundle = store.loadOrBootstrap(keyId);
		assertThat(bundle.aesKey()).isEqualTo(Arrays.copyOfRange(winnerPlain, 0, 32));
		assertThat(bundle.hmacKey()).isEqualTo(Arrays.copyOfRange(winnerPlain, 32, 64));
	}

	private byte[] randomDek() {
		byte[] dek = new byte[64];
		new SecureRandom().nextBytes(dek);
		return dek;
	}
}
