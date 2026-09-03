package com.celfit.was.crypto;

import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 *
 * <p><b>{@code @DependsOn("appFlyway")}</b>(2026-09-03 스테이징 기동 실패 수정): vault 모드는 빈
 * 생성 시점에 {@code app.encryption_keys}를 읽는데, 그 테이블은 app 스키마 Flyway
 * ({@code AppFlywayConfig#appFlyway}, {@code initMethod="migrate"})가 만든다. 두 빈 사이에 의존이
 * 없으면 순서가 임의라, 첫 승격 배포에서 {@code userRepository → fieldCipher}가 마이그레이션보다
 * 먼저 생성돼 {@code relation "app.encryption_keys" does not exist}로 죽었다(app 이력이 09-02에
 * 멈춘 채 was 크래시루프). Boot 자동 구성 Flyway라면 JdbcClient가 자동으로 초기화 뒤에 오지만
 * 이 레포는 Flyway를 직접 {@code @Bean}으로 들어 그 배선이 없다. 테스트는 local 모드라 DB를
 * 안 읽어 CI가 못 잡는다 — 그래서 {@link com.celfit.was.CryptoConfigTest}가 애너테이션 자체를 고정한다.
 */
@Configuration
public class CryptoConfig {

	/** 빈 이름은 {@code AppFlywayConfig#appFlyway} 메서드명 — 이름이 바뀌면 여기도 같이 바꾼다. */
	static final String APP_FLYWAY_BEAN = "appFlyway";

	@Bean
	@DependsOn(APP_FLYWAY_BEAN)
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
		// acceptsProfiles는 프로파일 그룹·include(예: prod를 활성화하는 상위 그룹)까지 커버한다 —
		// getActiveProfiles()의 정확 일치보다 넓은 판정이라 프로파일 구성이 바뀌어도 가드가 안전 측으로 남는다.
		boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
		if (isProd && "local".equals(mode) && !allowLocalInProd) {
			throw new IllegalStateException(
					"운영 프로파일(prod)은 crypto.mode=vault 필수 — local 더미 키로 기동하는 것을 차단한다. "
							+ "prod 프로파일이 필요한 테스트는 crypto.allow-local-in-prod=true를 명시할 것.");
		}
	}
}
