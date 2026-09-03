package com.celfit.was.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

	/**
	 * {@code guardAgainstKeyLoss()}는 4개 테이블 전체를 대상으로 하는 전역 검사라, 컨테이너가
	 * JVM 전체에서 공유되는 이 스위트에서는 다른 클래스(이중 쓰기 테스트 등)가 이미 써둔
	 * {@code email_enc}가 "키 유실 아님" 시나리오(부트스트랩 성공 케이스)를 오염시킨다.
	 * 행을 지우면 FK 캐스케이드 부작용이 커서(계정 탈퇴 CASCADE 자식 9종 등,
	 * {@code AdminCrawlingCostSummaryIntegrationTest}가 같은 이유로 겪은 문제) 대신
	 * {@code email_enc}만 널로 되돌린다 — 다른 테스트는 이미 자기 검증을 마친 뒤라 영향 없다.
	 */
	@BeforeEach
	void clearCrossTestCiphertextPollution() {
		jdbcClient.sql("UPDATE app.users SET email_enc = NULL WHERE email_enc IS NOT NULL").update();
		jdbcClient.sql("UPDATE app.inquiries SET email_enc = NULL WHERE email_enc IS NOT NULL").update();
		jdbcClient.sql("UPDATE app.password_resets SET email_enc = NULL WHERE email_enc IS NOT NULL").update();
		jdbcClient.sql("UPDATE app.signup_events SET email_enc = NULL WHERE email_enc IS NOT NULL").update();
	}

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

	@Test
	void 행이_없어도_암호문이_이미_존재하면_키_유실로_보고_부트스트랩을_거부한다() {
		int keyId = 104;
		// encryption_keys 행은 없지만(=키 유실 모양) users에 이미 email_enc가 채워진 행이 있다 —
		// 이중 쓰기 중이던 DB를 잘못 지정했거나 encryption_keys 백업 복원을 빠뜨린 상황의 재현.
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name, email_enc)
				VALUES (:email, 'h', '레거시', 'v1:1:dummy-iv:dummy-ciphertext')""")
				.param("email", "keyloss-" + UUID.randomUUID() + "@ex.com")
				.update();

		DekStore store = new DekStore(jdbcClient, new FakeDekWrapper());

		assertThatThrownBy(() -> store.loadOrBootstrap(keyId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("키 유실");

		// 부트스트랩이 실행되지 않았어야 한다 — 행은 여전히 없어야 한다(새 DEK가 조용히 등록되지 않음).
		Long count = jdbcClient.sql("SELECT count(*) FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId).query(Long.class).single();
		assertThat(count).isEqualTo(0L);
	}

	/**
	 * Minor 5(리뷰 라운드 1) — bootstrap()을 직접 호출하는 대신, {@code loadOrBootstrap()} 공개
	 * API 경로(find→null→bootstrap→재조회) 전체가 경합에서도 먼저 커밋한 값으로 수렴하는지
	 * 검증한다. wrap() 훅에서 "이 인스턴스가 INSERT를 실행하기 직전" 순간에 경쟁 인스턴스가
	 * 이미 커밋한 것처럼 행을 심어, find 시점엔 없었지만 insert 시점엔 이미 있는 진짜 레이스를
	 * 결정적으로(스레드 없이) 재현한다.
	 */
	@Test
	void 동시_부트스트랩_시뮬레이션_loadOrBootstrap_전체_경로도_먼저_커밋한_값으로_수렴한다() {
		int keyId = 105;
		FakeDekWrapper baseWrapper = new FakeDekWrapper();
		byte[] rivalPlain = randomDek();
		byte[] rivalWrapped = baseWrapper.wrap(rivalPlain);

		DekWrapper racingWrapper = new DekWrapper() {
			@Override
			public byte[] wrap(byte[] plain) {
				// 이 인스턴스가 자기 몫을 래핑하는 바로 그 순간, "경쟁 인스턴스"가 먼저 커밋한다.
				jdbcClient.sql("INSERT INTO app.encryption_keys (key_id, wrapped_dek) VALUES (:id, :w)")
						.param("id", keyId).param("w", rivalWrapped).update();
				return baseWrapper.wrap(plain); // 이 인스턴스 자신의 래핑본 — ON CONFLICT로 패배해야 한다
			}

			@Override
			public DekBundle unwrap(byte[] wrapped) {
				return baseWrapper.unwrap(wrapped);
			}
		};

		DekStore store = new DekStore(jdbcClient, racingWrapper);
		DekBundle bundle = store.loadOrBootstrap(keyId);

		assertThat(bundle.aesKey()).isEqualTo(Arrays.copyOfRange(rivalPlain, 0, 32));
		assertThat(bundle.hmacKey()).isEqualTo(Arrays.copyOfRange(rivalPlain, 32, 64));
		Long count = jdbcClient.sql("SELECT count(*) FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId).query(Long.class).single();
		assertThat(count).isEqualTo(1L);
	}

	private byte[] randomDek() {
		byte[] dek = new byte[64];
		new SecureRandom().nextBytes(dek);
		return dek;
	}
}
