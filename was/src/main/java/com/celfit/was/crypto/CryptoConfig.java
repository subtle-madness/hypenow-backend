package com.celfit.was.crypto;

import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FieldCipher 빈 조립(스펙 §키 계층) — crypto.mode:
 *   local: 설정의 고정 키(테스트·로컬 개발 — Vault 무의존)
 *   vault: app.encryption_keys의 래핑된 DEK를 부팅 시 1회 Vault decrypt로 언래핑(운영·스테이징)
 * 언래핑 실패는 기동 실패 — 암호화 무결성이 가용성보다 우선(스펙 §실패 모드).
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
			ObjectProvider<JdbcClient> jdbcClient) {
		return switch (mode) {
			case "local" -> new FieldCipher(DekBundle.fromBytes(Base64.getDecoder().decode(localKeyBase64)), keyId);
			case "vault" -> new FieldCipher(
					new VaultDekUnwrapper(kekOcid, cryptoEndpoint).unwrap(loadWrappedDek(jdbcClient.getObject(), keyId)),
					keyId);
			default -> throw new IllegalStateException("crypto.mode는 local|vault: " + mode);
		};
	}

	private byte[] loadWrappedDek(JdbcClient jdbc, int keyId) {
		return jdbc.sql("SELECT wrapped_dek FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId)
				.query(byte[].class)
				.single();
	}
}
