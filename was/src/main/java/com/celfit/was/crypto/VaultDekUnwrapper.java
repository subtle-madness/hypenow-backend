package com.celfit.was.crypto;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.keymanagement.KmsCryptoClient;
import com.oracle.bmc.keymanagement.model.DecryptDataDetails;
import com.oracle.bmc.keymanagement.requests.DecryptRequest;
import java.util.Base64;

/**
 * Vault KEK로 래핑된 DEK를 언래핑(부팅 1회) — 인스턴스 프린시펄 인증, IAM은 이 KEK 1개 use만
 * 허용돼 있다(Task 0). 지수 백오프 3회 재시도 후 실패면 예외 → 기동 실패(스펙 §실패 모드).
 */
public class VaultDekUnwrapper {

	private final String kekOcid;
	private final String cryptoEndpoint;

	public VaultDekUnwrapper(String kekOcid, String cryptoEndpoint) {
		this.kekOcid = kekOcid;
		this.cryptoEndpoint = cryptoEndpoint;
	}

	public DekBundle unwrap(byte[] wrappedDek) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try (KmsCryptoClient client = KmsCryptoClient.builder()
					.endpoint(cryptoEndpoint)
					.build(InstancePrincipalsAuthenticationDetailsProvider.builder().build())) {
				String plaintextB64 = client.decrypt(DecryptRequest.builder()
						.decryptDataDetails(DecryptDataDetails.builder()
								.keyId(kekOcid)
								.ciphertext(Base64.getEncoder().encodeToString(wrappedDek))
								.build())
						.build()).getDecryptedData().getPlaintext();
				return DekBundle.fromBytes(Base64.getDecoder().decode(plaintextB64));
			} catch (RuntimeException e) {
				last = e;
				try {
					Thread.sleep(1000L * attempt * attempt);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw new IllegalStateException("Vault DEK 언래핑 실패 — 기동 중단(재시도 3회 소진)", last);
	}
}
