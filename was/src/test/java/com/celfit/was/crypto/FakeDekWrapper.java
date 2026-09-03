package com.celfit.was.crypto;

/**
 * 테스트 전용 DekWrapper — Vault 실통신 없이 wrap/unwrap 라운드트립을 검증하기 위한 가짜 구현.
 * 고정 바이트로 XOR해 가역 변환만 제공한다(운영 안전성과 무관 — 절대 프로덕션 코드에서 사용 금지).
 * Vault 실통신(인증·재시도·실제 KMS 암복호화)은 이 테스트로 검증할 수 없다 — 스테이징 게이트
 * (deploy/README.md §6-3 롤아웃 체크리스트)가 그 부분을 담당한다.
 */
public class FakeDekWrapper implements DekWrapper {

	private static final byte MASK = (byte) 0xA5;

	@Override
	public byte[] wrap(byte[] plain) {
		return xor(plain);
	}

	@Override
	public DekBundle unwrap(byte[] wrapped) {
		return DekBundle.fromBytes(xor(wrapped));
	}

	private byte[] xor(byte[] in) {
		byte[] out = new byte[in.length];
		for (int i = 0; i < in.length; i++) {
			out[i] = (byte) (in[i] ^ MASK);
		}
		return out;
	}
}
