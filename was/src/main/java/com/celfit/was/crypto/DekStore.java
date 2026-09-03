package com.celfit.was.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DEK 조회·자동 부트스트랩(스펙 §키 계층) — vault 모드 첫 부팅은 {@code app.encryption_keys}에
 * 행이 없다(테이블은 같은 부팅의 Flyway가 막 만들고, 행은 아무도 넣어두지 않았다). 이 클래스가
 * 그 간극을 메운다: 행이 없으면 (a) 새 DEK를 메모리에만 생성 → (b) KEK로 래핑 →
 * (c) {@code ON CONFLICT DO NOTHING}으로 등록 시도 → (d) **반드시 다시 SELECT**해서 그 행을
 * 언래핑해 사용한다. 롤링 배포로 두 인스턴스가 동시에 부트스트랩해도 먼저 커밋한 쪽의 래핑본이
 * (c)에서 이기고, 진 쪽도 (d) 재조회로 같은 값을 읽어 두 인스턴스가 항상 같은 DEK로 수렴한다.
 * 이 클래스가 생성한 평문 DEK는 재조회 결과와 별개로 즉시 폐기 — 신뢰하는 값은 언제나
 * SELECT→unwrap 경로뿐이다.
 *
 * <p><b>키 유실 가드(fail-closed)</b>: "행 없음"은 진짜 첫 부팅뿐 아니라 구 백업 복원·잘못된
 * DB 지정·행 실수 삭제에서도 똑같은 모양이다. 그 DB에 이미 {@code *_enc} 암호문이 있는데
 * 이 상태로 부트스트랩하면 같은 {@code key_id}로 전혀 다른 새 DEK가 조용히 등록되고,
 * 암호문의 {@code v1:<key_id>:...} 접두사 검사는 통과하지만 GCM 태그 검증에서 기존 암호문
 * 전량이 복호화 실패로 죽는다 — 부트스트랩 시점이 아니라 한참 뒤 읽기 경로에서 늦게
 * 발견되는 사고(fail-open)가 된다. 그래서 부트스트랩 진입 전에 암호문 존재 여부를 확인해,
 * 있으면 부트스트랩을 거부하고 기동을 중단한다({@link #loadOrBootstrap}).
 */
public class DekStore {

	private static final Logger log = LoggerFactory.getLogger(DekStore.class);
	private static final int DEK_BYTES = 64;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final DekWrapper wrapper;

	public DekStore(JdbcClient jdbc, DekWrapper wrapper) {
		this.jdbc = jdbc;
		this.wrapper = wrapper;
	}

	/**
	 * key_id 행이 있으면 언래핑해 반환, 없으면 부트스트랩 후 재조회해 반환.
	 * 행이 없는데 암호문이 이미 존재하면(키 유실 의심) 부트스트랩을 거부하고 예외를 던진다.
	 */
	public DekBundle loadOrBootstrap(int keyId) {
		byte[] wrapped = findWrappedDek(keyId);
		if (wrapped == null) {
			guardAgainstKeyLoss();
			bootstrap(keyId);
			wrapped = findWrappedDek(keyId);
			if (wrapped == null) {
				throw new IllegalStateException(
						"DEK 부트스트랩 직후에도 key_id=" + keyId + " 행을 찾을 수 없다 — DB 연결 또는 트랜잭션 가시성 문제");
			}
		}
		return wrapper.unwrap(wrapped);
	}

	/**
	 * encryption_keys 행이 없는데 암호화 컬럼에 이미 값이 있으면 키 유실이 의심된다 — 첫 부팅과
	 * "행 없음" 모양이 같아 구분이 안 되므로, 4개 테이블 전부를 확인해 하나라도 걸리면 즉시
	 * 기동을 중단한다(자동 부트스트랩으로 새 DEK를 생성해 조용히 뜨는 fail-open 방지).
	 */
	private void guardAgainstKeyLoss() {
		Boolean ciphertextExists = jdbc.sql("""
				SELECT EXISTS(SELECT 1 FROM app.users WHERE email_enc IS NOT NULL)
					OR EXISTS(SELECT 1 FROM app.inquiries WHERE email_enc IS NOT NULL)
					OR EXISTS(SELECT 1 FROM app.password_resets WHERE email_enc IS NOT NULL)
					OR EXISTS(SELECT 1 FROM app.signup_events WHERE email_enc IS NOT NULL)""")
				.query(Boolean.class)
				.single();
		if (Boolean.TRUE.equals(ciphertextExists)) {
			throw new IllegalStateException(
					"encryption_keys 행은 없는데 암호문이 존재 — 키 유실 의심, 자동 부트스트랩 거부. "
							+ "백업의 encryption_keys 복원 필요");
		}
	}

	// 패키지 가시성 — 동시 부트스트랩(ON CONFLICT DO NOTHING) 경로를 DekStoreTest가 직접 재현한다.
	void bootstrap(int keyId) {
		byte[] plainDek = new byte[DEK_BYTES];
		RANDOM.nextBytes(plainDek);
		try {
			byte[] wrappedDek = wrapper.wrap(plainDek);
			jdbc.sql("""
					INSERT INTO app.encryption_keys (key_id, wrapped_dek) VALUES (:id, :wrapped)
					ON CONFLICT (key_id) DO NOTHING""")
					.param("id", keyId)
					.param("wrapped", wrappedDek)
					.update();
			// 키 바이트·base64는 절대 로그에 남기지 않는다 — 등록 시도 사실과 key_id만 기록.
			log.info("DEK 부트스트랩 — key_id={} 신규 래핑본 등록 시도(동시 부트스트랩 시 먼저 커밋한 쪽이 채택됨)", keyId);
		} finally {
			Arrays.fill(plainDek, (byte) 0);
		}
	}

	private byte[] findWrappedDek(int keyId) {
		return jdbc.sql("SELECT wrapped_dek FROM app.encryption_keys WHERE key_id = :id")
				.param("id", keyId)
				.query(byte[].class)
				.optional()
				.orElse(null);
	}
}
