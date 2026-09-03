package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.crypto.CryptoConfig;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * CryptoConfig — local 모드 빈 조립과 운영 fail-closed 가드(Task 11).
 * vault 모드의 실제 조립(DekStore·VaultDekWrapper)은 Vault 실통신이 필요해 여기선 검증하지
 * 않는다 — DekStore 자체의 부트스트랩·동시성 로직은 DekStoreTest(Testcontainers)가 담당.
 */
class CryptoConfigTest {

	private static final MockEnvironment NON_PROD = mockEnvironment("default");
	private static final MockEnvironment PROD = mockEnvironment("prod");

	private static MockEnvironment mockEnvironment(String... activeProfiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(activeProfiles);
		return env;
	}

	@Test
	void local_모드는_설정_키로_FieldCipher를_만든다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, NON_PROD, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void 알_수_없는_모드는_기동_실패() {
		assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("what", null, null, null, 1, false, NON_PROD, null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void prod_프로파일에서_local_모드는_기동_실패() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, PROD, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("vault");
	}

	/**
	 * vault 모드는 빈 생성 시점에 app.encryption_keys를 읽으므로 app Flyway가 먼저 돌아야 한다 —
	 * 2026-09-03 첫 승격 배포에서 순서가 뒤집혀 스테이징 was가 크래시루프(테스트는 local 모드라
	 * 런타임으로는 못 잡는다). 빈 이름은 AppFlywayConfig#appFlyway 메서드명과 같아야 한다.
	 */
	@Test
	void fieldCipher_빈은_app_Flyway_빈에_의존한다() throws Exception {
		var method = java.util.Arrays.stream(CryptoConfig.class.getMethods())
				.filter(m -> m.getName().equals("fieldCipher")).findFirst().orElseThrow();
		var dependsOn = method.getAnnotation(org.springframework.context.annotation.DependsOn.class);
		assertThat(dependsOn).isNotNull();
		assertThat(dependsOn.value()).containsExactly("appFlyway");
		var flywayMethod = java.util.Arrays.stream(com.celfit.was.config.AppFlywayConfig.class.getMethods())
				.filter(m -> m.getName().equals("appFlyway")).findFirst();
		assertThat(flywayMethod).as("AppFlywayConfig#appFlyway 빈 이름이 바뀌면 @DependsOn도 같이 바꿔야 한다").isPresent();
	}

	@Test
	void prod_프로파일이어도_allow_local_in_prod가_켜져있으면_local_모드가_통과한다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, true, PROD, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void prod가_아니면_local_모드가_그대로_통과한다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, NON_PROD, null);
		assertThat(cipher).isNotNull();
	}
}
