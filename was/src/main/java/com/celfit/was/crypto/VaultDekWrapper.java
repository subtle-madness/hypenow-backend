package com.celfit.was.crypto;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.keymanagement.KmsCryptoClient;
import com.oracle.bmc.keymanagement.model.DecryptDataDetails;
import com.oracle.bmc.keymanagement.model.EncryptDataDetails;
import com.oracle.bmc.keymanagement.requests.DecryptRequest;
import com.oracle.bmc.keymanagement.requests.EncryptRequest;
import java.util.Base64;
import java.util.function.Function;

/**
 * Vault KEK로 DEK를 래핑·언래핑(스펙 §키 계층) — 인스턴스 프린시펄 인증, IAM은 이 KEK 1개
 * use만 허용돼 있다(Task 0). unwrap은 매 부팅 1회(정상 등록된 DEK 사용), wrap은 DEK 자동
 * 부트스트랩({@link DekStore})이 최초 1행을 등록할 때만 호출된다. 둘 다 지수 백오프 3회
 * 재시도 후 실패면 예외 → 기동 중단(스펙 §실패 모드).
 */
public class VaultDekWrapper implements DekWrapper {

	private final String kekOcid;
	private final String cryptoEndpoint;

	public VaultDekWrapper(String kekOcid, String cryptoEndpoint) {
		this.kekOcid = kekOcid;
		this.cryptoEndpoint = cryptoEndpoint;
	}

	@Override
	public byte[] wrap(byte[] plain) {
		String plaintextB64 = Base64.getEncoder().encodeToString(plain);
		return withRetry("래핑", client -> {
			String ciphertextB64 = client.encrypt(EncryptRequest.builder()
					.encryptDataDetails(EncryptDataDetails.builder()
							.keyId(kekOcid)
							.plaintext(plaintextB64)
							.build())
					.build()).getEncryptedData().getCiphertext();
			return Base64.getDecoder().decode(ciphertextB64);
		});
	}

	@Override
	public DekBundle unwrap(byte[] wrappedDek) {
		byte[] plain = withRetry("언래핑", client -> {
			String plaintextB64 = client.decrypt(DecryptRequest.builder()
					.decryptDataDetails(DecryptDataDetails.builder()
							.keyId(kekOcid)
							.ciphertext(Base64.getEncoder().encodeToString(wrappedDek))
							.build())
					.build()).getDecryptedData().getPlaintext();
			return Base64.getDecoder().decode(plaintextB64);
		});
		return DekBundle.fromBytes(plain);
	}

	private <T> T withRetry(String opName, Function<KmsCryptoClient, T> action) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try (KmsCryptoClient client = KmsCryptoClient.builder()
					.endpoint(cryptoEndpoint)
					.build(InstancePrincipalsAuthenticationDetailsProvider.builder().build())) {
				return action.apply(client);
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
		throw new IllegalStateException("Vault DEK " + opName + " 실패 — 기동 중단(재시도 3회 소진)", last);
	}
}
