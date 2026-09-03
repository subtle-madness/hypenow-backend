package com.celfit.was.crypto;

/**
 * KEK로 DEK를 래핑·언래핑하는 협력자(스펙 §키 계층) — 운영은 {@link VaultDekWrapper}(OCI Vault
 * KMS), 테스트는 Vault 실통신 없이 라운드트립 가능한 가짜 구현을 쓴다. {@link DekStore}가
 * 부트스트랩·조회 시 이 인터페이스로만 통신해, 운영/테스트가 같은 조립 로직을 공유한다.
 */
public interface DekWrapper {

	/** 평문 DEK 번들(64바이트)을 KEK로 래핑 — DEK 자동 부트스트랩(최초 등록)에서만 호출된다. */
	byte[] wrap(byte[] plain);

	/** 래핑된 DEK를 KEK로 언래핑해 사용 가능한 번들로 복원. */
	DekBundle unwrap(byte[] wrapped);
}
