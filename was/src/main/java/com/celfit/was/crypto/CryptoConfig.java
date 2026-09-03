package com.celfit.was.crypto;

import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FieldCipher 빈 조립(스펙 §키 계층) — crypto.mode:
 *   local: 설정의 고정 키(테스트·로컬 개발 — Vault 무의존)
 *   vault: app.encryption_keys의 래핑된 DEK를 부팅 시 1회 조회하거나(없으면 자동 부트스트랩)
 *          Vault decrypt로 언래핑(운영·스테이징) — {@link DekStore} 참조.
 * 언래핑 실패는 기동 실패 — 암호화 무결성이 가용성보다 우선(스펙 §실패 모드).
 *
 * <p>운영 fail-closed 가드: 활성 프로파일에 {@code prod}가 있는데 mode가 local이면 기동을
 * 막는다 — 더미 키로 뜨는 사고를 컴파일이 아니라 런타임에서라도 차단한다. 테스트가 prod
 * 프로파일을 켜야 하는 경우(예: {@code ProdForwardedHeadersTest})는 Vault를 실제로 붙일 수
 * 없으니 {@code crypto.allow-local-in-prod=true}를 명시적으로 켜서 우회 — 운영 compose에는
 * 이 플래그가 없으므로 실제 배포 경로에서는 가드가 항상 살아 있다.
 */
@Configuration
public class CryptoConfig {

	@Bean
	public FieldCipher fieldCipher(
			@Value("${crypto.mode:local}") String mode,
			@Value("${crypto.local-key-base64:}") String localKeyBase64,
			@Value("${crypto.kek-ocid:}") String kekOcid,
			@Value("${crypto.crypto-endpoint:}") String cryptoEndpoint,
			@Value("${crypto.key-id:1}") int keyId,
			@Value("${crypto.allow-local-in-prod:false}") boolean allowLocalInProd,
			Environment environment,
			ObjectProvider<JdbcClient> jdbcClient) {
		guardAgainstLocalModeInProd(mode, allowLocalInProd, environment);
		return switch (mode) {
			case "local" -> new FieldCipher(DekBundle.fromBytes(Base64.getDecoder().decode(localKeyBase64)), keyId);
			case "vault" -> new FieldCipher(
					new DekStore(jdbcClient.getObject(), new VaultDekWrapper(kekOcid, cryptoEndpoint))
							.loadOrBootstrap(keyId),
					keyId);
			default -> throw new IllegalStateException("crypto.mode는 local|vault: " + mode);
		};
	}

	private void guardAgainstLocalModeInProd(String mode, boolean allowLocalInProd, Environment environment) {
		boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
		if (isProd && "local".equals(mode) && !allowLocalInProd) {
			throw new IllegalStateException(
					"운영 프로파일(prod)은 crypto.mode=vault 필수 — local 더미 키로 기동하는 것을 차단한다. "
							+ "prod 프로파일이 필요한 테스트는 crypto.allow-local-in-prod=true를 명시할 것.");
		}
	}
}
