# 개인정보 봉투 암호화(트랙 A) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** app 스키마 개인정보 컬럼(users·inquiries·password_resets·signup_events)을 봉투 암호화(AES-256-GCM DEK + OCI Vault KEK)로 보호하고, 세션 principal에서 이메일을 제거하며, signup_events 90일 보존 배치를 넣는다.

**Architecture:** DEK(+HMAC 블라인드 인덱스 키)는 Vault KEK로 래핑된 채 `app.encryption_keys`에 저장, 앱 부팅 시 1회 언래핑해 메모리에만 보관. 컬럼 암호문은 `v1:<key_id>:<iv>:<ct>` 형식, 등가 검색은 `*_bidx`(HMAC). 전환은 expand(이중 쓰기)→백필→읽기 전환→contract(다음 릴리스) 순.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, OCI Java SDK(keymanagement), Testcontainers(PostgreSQL)

**Spec:** [docs/superpowers/specs/2026-09-03-pii-envelope-encryption-design.md](../specs/2026-09-03-pii-envelope-encryption-design.md)

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(was):`/`docs:` 식.
- 마이그레이션 채번은 **UTC 타임스탬프**: `date -u +%Y%m%d%H%M%S` → `V<STAMP>__<설명>.sql`. 경로는 `was/src/main/resources/db/migration/app/`.
- 스키마 변경은 expand-contract — 이 계획의 마이그레이션은 전부 expand(추가만). contract(평문 DROP)는 **다음 릴리스 별도 PR**(Task 12는 문서만).
- 테스트는 모듈 단위: `./gradlew :was:test --tests "..."`. 통합 테스트는 Testcontainers — 셸에 `DOCKER_HOST` 필요 시 환경 확인(맥 로컬은 Docker Desktop, 미설정이 정답 — 메모리 참조).
- DTO는 record(+정적 `from()`), 조회는 JdbcClient. Jackson은 `tools.jackson.*`.
- **평문 DEK·HMAC 키를 로그·예외 메시지·테스트 스냅샷에 절대 남기지 않는다.**
- 이중 쓰기 기간(이 계획 전체)에는 평문 컬럼이 여전히 정본 — 읽기 전환(Task 8~9)은 백필(Task 7) 후에만.

---

### Task 0: Vault KEK 생성 + IAM 정책 (인프라 — 코드와 독립, 로컬 맥에서 실행)

**Files:** 없음 (OCI 리소스만. 결과 OCID를 Task 6 롤아웃과 운영 설정에 사용)

**Produces:** KEK OCID(이하 `<KEK_OCID>`), crypto endpoint(이하 `<CRYPTO_EP>`) — Task 2의 운영 설정값, Task 6의 래핑 명령에 사용.

- [ ] **Step 1: KEK 생성** (기존 `hypenow-vault` 재사용, AES 대칭·SOFTWARE 보호 = 무료)

```bash
TENANCY=$(awk -F= '/\[HYPENOW\]/{f=1;next} /^\[/{f=0} f && /^tenancy/{print $2; exit}' ~/.oci/config | tr -d ' ')
MGMT=https://ezvjprllaacng-management.kms.ap-tokyo-1.oraclecloud.com
oci --profile HYPENOW kms management key create --compartment-id "$TENANCY" \
  --display-name hypenow-pii-kek \
  --key-shape '{"algorithm":"AES","length":32}' --protection-mode SOFTWARE \
  --endpoint "$MGMT" --query 'data.id' --raw-output
```

- [ ] **Step 2: crypto endpoint 확인**

```bash
oci --profile HYPENOW kms management vault get \
  --vault-id ocid1.vault.oc1.ap-tokyo-1.ezvjprllaacng.abxhiljrdlobsvksbyvx5srgucpcmb2ynfqir4xzaj2mlsvb57k72y7by3jq \
  --query 'data."crypto-endpoint"' --raw-output
```

- [ ] **Step 3: IAM 정책 — 서버 dynamic group에 이 키 1개 `use`만 허용**

```bash
oci --profile HYPENOW iam policy create --compartment-id "$TENANCY" \
  --name hypenow-pii-kek-use \
  --description "was 인스턴스가 PII KEK로 DEK 언래핑만 가능(키 1개 한정) — 백업 시크릿은 계속 접근 불가" \
  --statements "[\"Allow dynamic-group hypenow-instances to use keys in tenancy where target.key.id = '<KEK_OCID>'\"]"
```

- [ ] **Step 4: 검증** — `oci --profile HYPENOW iam policy list --compartment-id "$TENANCY"`로 정책에 시크릿 권한이 **없는지**(keys use만) 확인. `<KEK_OCID>`·`<CRYPTO_EP>`를 기록해 둔다.

---

### Task 1: FieldCipher 코어 — 암호화·복호화·블라인드 인덱스 (local 모드)

**Files:**
- Create: `was/src/main/java/com/celfit/was/crypto/DekBundle.java`
- Create: `was/src/main/java/com/celfit/was/crypto/FieldCipher.java`
- Test: `was/src/test/java/com/celfit/was/FieldCipherTest.java`

**Interfaces:**
- Produces: `record DekBundle(byte[] aesKey, byte[] hmacKey)` (각 32바이트, `DekBundle.fromBytes(byte[] 64바이트)` 정적 팩토리), `FieldCipher.encrypt(String plaintext) → String`(null 입력 null 반환), `FieldCipher.decrypt(String token) → String`(null 입력 null, 형식·태그 오류는 `IllegalStateException`), `FieldCipher.blindIndex(String normalized) → String`(null 입력 null). 생성자 `new FieldCipher(DekBundle bundle, int keyId)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.crypto.DekBundle;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** FieldCipher 단위 검증 — 라운드트립·IV 랜덤성·bidx 결정성·형식·오류 처리(스펙 §암호문·블라인드 인덱스). */
class FieldCipherTest {

	// 테스트 전용 고정 키(64바이트 = AES 32 + HMAC 32) — 운영 키와 무관
	private static final byte[] TEST_KEY = new byte[64];
	static {
		for (int i = 0; i < 64; i++) {
			TEST_KEY[i] = (byte) i;
		}
	}

	private final FieldCipher cipher = new FieldCipher(DekBundle.fromBytes(TEST_KEY), 1);

	@Test
	void 라운드트립_원문복원() {
		String token = cipher.encrypt("user@example.com");
		assertThat(cipher.decrypt(token)).isEqualTo("user@example.com");
	}

	@Test
	void 같은_평문도_IV가_랜덤이라_암호문이_다르다() {
		assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
	}

	@Test
	void 암호문은_버전_키ID_접두사를_가진다() {
		assertThat(cipher.encrypt("x")).startsWith("v1:1:");
	}

	@Test
	void 블라인드_인덱스는_결정적이고_역산불가_형식() {
		String a = cipher.blindIndex("user@example.com");
		assertThat(a).isEqualTo(cipher.blindIndex("user@example.com"));
		assertThat(a).isNotEqualTo(cipher.blindIndex("other@example.com"));
		assertThat(Base64.getUrlDecoder().decode(a)).hasSize(32); // SHA-256 출력
	}

	@Test
	void null은_그대로_통과() {
		assertThat(cipher.encrypt(null)).isNull();
		assertThat(cipher.decrypt(null)).isNull();
		assertThat(cipher.blindIndex(null)).isNull();
	}

	@Test
	void 손상된_암호문은_조용히_null이_아니라_예외() {
		String token = cipher.encrypt("x");
		String tampered = token.substring(0, token.length() - 4) + "AAAA";
		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt("garbage")).isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :was:test --tests "com.celfit.was.FieldCipherTest"` → 컴파일 실패(클래스 없음) 기대

- [ ] **Step 3: 구현**

```java
package com.celfit.was.crypto;

/** DEK 번들 — AES 데이터 키 + HMAC 블라인드 인덱스 키(용도 분리, 스펙 §키 계층). 래핑 해제 결과로만 생성된다. */
public record DekBundle(byte[] aesKey, byte[] hmacKey) {

	public static DekBundle fromBytes(byte[] raw) {
		if (raw == null || raw.length != 64) {
			throw new IllegalArgumentException("DEK 번들은 64바이트(AES 32 + HMAC 32)여야 한다");
		}
		byte[] aes = new byte[32];
		byte[] hmac = new byte[32];
		System.arraycopy(raw, 0, aes, 0, 32);
		System.arraycopy(raw, 32, hmac, 0, 32);
		return new DekBundle(aes, hmac);
	}
}
```

```java
package com.celfit.was.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 필드 암호화 단일 정본(스펙 §암호문·블라인드 인덱스) — AES-256-GCM + HMAC-SHA256 블라인드 인덱스.
 * 암호문 형식 v1:<key_id>:<b64(iv 12B)>:<b64(ct+tag)> — 키 로테이션 시 신구 공존용 접두사.
 * 복호화 실패는 예외(조용한 데이터 소실 방지). 평문 키는 이 객체(메모리) 밖으로 내보내지 않는다.
 */
public class FieldCipher {

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec aesKey;
	private final SecretKeySpec hmacKey;
	private final String prefix;
	private final int keyId;

	public FieldCipher(DekBundle bundle, int keyId) {
		this.aesKey = new SecretKeySpec(bundle.aesKey(), "AES");
		this.hmacKey = new SecretKeySpec(bundle.hmacKey(), "HmacSHA256");
		this.keyId = keyId;
		this.prefix = "v1:" + keyId + ":";
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return prefix + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("필드 암호화 실패", e);
		}
	}

	public String decrypt(String token) {
		if (token == null) {
			return null;
		}
		String[] parts = token.split(":", 4);
		if (parts.length != 4 || !parts[0].equals("v1") || !parts[1].equals(String.valueOf(keyId))) {
			throw new IllegalStateException("알 수 없는 암호문 형식(접두사 불일치)");
		}
		try {
			byte[] iv = Base64.getDecoder().decode(parts[2]);
			byte[] ct = Base64.getDecoder().decode(parts[3]);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("필드 복호화 실패 — 손상된 암호문 또는 키 불일치", e);
		}
	}

	/** 등가 검색·UNIQUE용 지문 — 호출부가 정규화(이메일 lower 등)를 마친 값을 넘긴다(스펙: 정규화 규칙 재사용). */
	public String blindIndex(String normalized) {
		if (normalized == null) {
			return null;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(hmacKey);
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("블라인드 인덱스 생성 실패", e);
		}
	}
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.FieldCipherTest"` → 전부 PASS

- [ ] **Step 5: 커밋** — `git add was/src/main/java/com/celfit/was/crypto/ was/src/test/java/com/celfit/was/FieldCipherTest.java && git commit -m "feat(was): FieldCipher — AES-256-GCM 필드 암호화 + HMAC 블라인드 인덱스"` (+ Co-Authored-By 푸터)

---

### Task 2: DEK 프로바이더 — local/vault 모드와 부팅 언래핑

**Files:**
- Create: `was/src/main/java/com/celfit/was/crypto/CryptoConfig.java`
- Create: `was/src/main/java/com/celfit/was/crypto/VaultDekUnwrapper.java`
- Modify: `was/build.gradle` (OCI SDK 의존성)
- Modify: `was/src/main/resources/application.yml`, `was/src/test/resources/application-test.yml` (있는 파일명 기준 — 테스트 프로파일 설정 위치는 기존 관례 확인 후 동일 위치)
- Test: `was/src/test/java/com/celfit/was/CryptoConfigTest.java`

**Interfaces:**
- Consumes: Task 1의 `FieldCipher`, `DekBundle`
- Produces: 스프링 빈 `FieldCipher` (모든 리포지토리가 주입받는 단일 인스턴스). 설정 키: `crypto.mode`(local|vault), `crypto.local-key-base64`(local 전용), `crypto.kek-ocid`·`crypto.crypto-endpoint`(vault 전용), `crypto.key-id`(기본 1).

- [ ] **Step 1: 실패하는 테스트 작성** — local 모드 빈 생성 검증

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.crypto.CryptoConfig;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** CryptoConfig local 모드 — Vault 없이 설정 키만으로 FieldCipher 빈이 만들어진다(테스트·로컬 개발 경로). */
class CryptoConfigTest {

	@Test
	void local_모드는_설정_키로_FieldCipher를_만든다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void 알_수_없는_모드는_기동_실패() {
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("what", null, null, null, 1, null))
				.isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :was:test --tests "com.celfit.was.CryptoConfigTest"` → 컴파일 실패 기대

- [ ] **Step 3: 의존성 + 구현.** `was/build.gradle` dependencies에 추가(버전은 Maven Central의 최신 3.x 확인 후 고정 — 아래는 확인 시점 예시, 반드시 실존 버전으로):

```groovy
	// OCI Vault KMS — PII DEK 언래핑(트랙 A). local 모드에선 로드만 되고 미사용.
	implementation 'com.oracle.oci.sdk:oci-java-sdk-keymanagement:3.46.1'
	implementation 'com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3:3.46.1'
```

```java
package com.celfit.was.crypto;

import java.util.Base64;
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
			org.springframework.beans.factory.ObjectProvider<JdbcClient> jdbcClient) {
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
```

```java
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
```

설정: 운영 compose env에 `CRYPTO_MODE=vault`, `CRYPTO_KEK_OCID=<KEK_OCID>`, `CRYPTO_CRYPTO_ENDPOINT=<CRYPTO_EP>` (배포 PR에서 compose.yaml·.env 갱신 — Task 11). 테스트 프로파일: `crypto.mode: local`, `crypto.local-key-base64: <base64 64바이트 아무 값>` — 기존 테스트 설정 파일이 있는 위치를 따른다(없으면 `@DynamicPropertySource` 쓰는 기존 통합 테스트 관례 확인 후 동일 방식).

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.CryptoConfigTest"` → PASS. `./gradlew :was:compileJava`로 SDK 의존성 해석 확인.

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): crypto.mode local/vault 분기 — 부팅 시 Vault DEK 언래핑"`

---

### Task 3: expand 마이그레이션 — 암호화 컬럼·encryption_keys 테이블

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<STAMP>__pii_envelope_expand.sql` (STAMP는 `date -u +%Y%m%d%H%M%S`)

**Interfaces:**
- Produces: `app.encryption_keys(key_id, wrapped_dek, created_at)`, 각 대상 테이블의 `*_enc`·`*_bidx` 컬럼(전부 NULL 허용 — 이중 쓰기 전 기존 행 때문). 이후 Task가 이 컬럼명에 의존: `users.email_enc/email_bidx/name_enc/nickname_enc/phone_number_enc`, `inquiries.name_enc/email_enc/organization_enc/message_enc`, `password_resets.email_enc/email_bidx`, `signup_events.email_enc/email_bidx/ip_enc`.

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 개인정보 봉투 암호화 expand 단계(트랙 A 스펙 2026-09-03) — 암호문(*_enc)·블라인드 인덱스(*_bidx)
-- 컬럼 추가와 래핑된 DEK 저장소. 전부 NULL 허용: 기존 행은 백필 커맨드(앱 레벨)가 채운다.
-- UNIQUE 인덱스는 백필 완료 후 후속 마이그레이션에서(부분 백필 상태 충돌 방지 — 스펙 §전환 1).
-- 평문 컬럼 DROP은 contract 단계(다음 릴리스) — expand-contract 규칙.

CREATE TABLE app.encryption_keys (
    key_id      smallint PRIMARY KEY,
    wrapped_dek bytea NOT NULL,          -- Vault KEK로 래핑된 DEK 번들(AES 32B + HMAC 32B) — 평문 키는 저장 금지
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app.users
    ADD COLUMN email_enc        text,
    ADD COLUMN email_bidx       text,    -- HMAC(lower(email)) — 로그인 조회·UNIQUE(백필 후 생성)
    ADD COLUMN name_enc         text,
    ADD COLUMN nickname_enc     text,
    ADD COLUMN phone_number_enc text;

ALTER TABLE app.inquiries
    ADD COLUMN name_enc         text,
    ADD COLUMN email_enc        text,
    ADD COLUMN organization_enc text,
    ADD COLUMN message_enc      text;

ALTER TABLE app.password_resets
    ADD COLUMN email_enc  text,
    ADD COLUMN email_bidx text;          -- 조회 키 대체(백필 후 UNIQUE) — PK 교체는 contract에서

ALTER TABLE app.signup_events
    ADD COLUMN email_enc  text,
    ADD COLUMN email_bidx text,          -- 어뷰징 추적(email, created_at) 조회 대체
    ADD COLUMN ip_enc     text;
```

- [ ] **Step 2: 마이그레이션 적용 확인** — 아무 통합 테스트 1개 실행(Flyway가 컨테이너에 전체 재생): `./gradlew :was:test --tests "com.celfit.was.PasswordResetRepositoryTest"` → PASS(스키마 오류 없음)

- [ ] **Step 3: 채번 가드 확인** — `bash check-migration-safety.sh` (레포 루트 기준 실제 경로 확인) 또는 CI에 위임. 미래 채번(UTC+1h 초과) 아님을 확인.

- [ ] **Step 4: 커밋** — `git commit -m "feat(was): 개인정보 암호화 expand 마이그레이션 — *_enc·*_bidx 컬럼 + encryption_keys"`

---

### Task 4: users 이중 쓰기 — 가입·프로필 수정 경로

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/me/...` 중 PATCH 호출부가 아니라 **리포지토리 내부에서** 처리(호출부 무변경)
- Test: `was/src/test/java/com/celfit/was/UserRepositoryDualWriteTest.java`

**Interfaces:**
- Consumes: `FieldCipher`(빈 주입), `UserRepository.normalizeEmail(String)`
- Produces: `insert`·`insertProfile`·`patchProfile`·이메일 변경 경로가 평문과 암호문을 **항상 동시에** 쓴다. 시그니처 무변경(호출부 영향 없음).

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.NewUser;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * users 이중 쓰기(스펙 §전환 1) — 가입·패치가 평문과 *_enc/*_bidx를 항상 함께 채우는지.
 * 베이스 클래스·컨테이너 설정은 기존 통합 테스트(AccountDeletionServiceIntegrationTest 등) 관례를 그대로 따른다.
 */
class UserRepositoryDualWriteTest extends /* 기존 통합 테스트 베이스 */ {

	@Autowired UserRepository userRepository;
	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	@Test
	void 가입은_평문과_암호문을_함께_쓴다() {
		var profile = userRepository.insertProfile(new NewUser(/* 기존 테스트의 NewUser 픽스처 재사용,
				email="Dual@Ex.com", name="김철수", nickname="철수", phoneNumber="01012345678" */), "hash");
		Map<String, Object> row = jdbcClient.sql(
				"SELECT email, email_enc, email_bidx, name_enc, nickname_enc, phone_number_enc FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();
		assertThat(row.get("email")).isEqualTo("dual@ex.com");                 // 평문 유지(이중 쓰기 기간 정본)
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("dual@ex.com");
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex("dual@ex.com"));
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("김철수");
		assertThat(fieldCipher.decrypt((String) row.get("nickname_enc"))).isEqualTo("철수");
		assertThat(fieldCipher.decrypt((String) row.get("phone_number_enc"))).isEqualTo("01012345678");
	}

	@Test
	void 프로필_패치도_암호문을_함께_갱신한다() {
		var profile = userRepository.insertProfile(/* 픽스처 */, "hash");
		userRepository.patchProfile(profile.id(), Map.of("name", "박영희", "phone_number", "01099998888"));
		Map<String, Object> row = jdbcClient.sql(
				"SELECT name, name_enc, phone_number_enc FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("박영희");
		assertThat(fieldCipher.decrypt((String) row.get("phone_number_enc"))).isEqualTo("01099998888");
	}
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :was:test --tests "com.celfit.was.UserRepositoryDualWriteTest"` → FAIL(enc 컬럼 null)

- [ ] **Step 3: 구현** — `UserRepository`에 `FieldCipher` 주입(생성자 파라미터 추가). `insert`/`insertProfile` INSERT 컬럼 목록에 `email_enc, email_bidx, name_enc, nickname_enc, phone_number_enc` 추가, 값은 `fieldCipher.encrypt(...)`·`fieldCipher.blindIndex(normalizeEmail(email))`. `patchProfile`은 PATCHABLE_COLUMNS 순회 시 `name`/`nickname`/`phone_number` 컬럼이 오면 대응하는 `<col>_enc = :<col>_enc` SET을 함께 추가하고 param에 암호문 바인딩(화이트리스트 불변 — enc 컬럼은 코드가 파생하는 것이라 외부 입력이 못 건드린다). `updatePasswordHash` 등 비대상 메서드는 무변경.

- [ ] **Step 4: 통과 확인** — Step 1 테스트 + 기존 회귀: `./gradlew :was:test --tests "com.celfit.was.AdminSignupIntegrationTest" --tests "com.celfit.was.UserRepositoryDualWriteTest"` → PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): users 이중 쓰기 — 가입·패치가 암호문·블라인드 인덱스 동시 기록"`

---

### Task 5: inquiries·password_resets·signup_events 이중 쓰기

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/inquiry/InquiryRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/account/PasswordResetRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/account/SignupEventRecorder.java`
- Test: `was/src/test/java/com/celfit/was/PiiDualWriteTest.java`

**Interfaces:**
- Consumes: `FieldCipher`, `UserRepository.normalizeEmail`
- Produces: 세 리포지토리의 INSERT/UPSERT가 `*_enc`(+ email은 `*_bidx`)를 함께 기록. 시그니처 무변경.

- [ ] **Step 1: 실패하는 테스트 작성** — Task 4와 같은 베이스, 세 테이블 각각 "쓰기 후 enc/bidx 채워짐 + 복호화 일치" 검증. signup_events는 `SignupEventRecorder.record("A@b.com", "ok", "203.0.113.9", Map.of())` 후 `email_enc` 복호화 = `A@b.com`(recorder는 정규화 없이 원문 보존 — 기존 의미론 유지), `email_bidx` = `blindIndex(normalizeEmail("A@b.com"))`(조회는 정규화 키), `ip_enc` 복호화 = `203.0.113.9`. password_resets는 기존 `PasswordResetRepositoryTest`의 upsert 픽스처를 재사용해 `email_enc`·`email_bidx` 검증. inquiries는 접수 API 리포지토리 메서드로 4컬럼 enc 검증.

- [ ] **Step 2: 실패 확인** — `./gradlew :was:test --tests "com.celfit.was.PiiDualWriteTest"` → FAIL

- [ ] **Step 3: 구현** — 각 INSERT/UPSERT 컬럼·값 추가(Task 4와 같은 패턴). password_resets의 upsert(`ON CONFLICT (email)`)는 SET 절에도 enc/bidx 갱신 추가.

- [ ] **Step 4: 통과 확인** — Step 1 테스트 + `--tests "com.celfit.was.PasswordResetRepositoryTest"` 회귀 → PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): inquiries·password_resets·signup_events 이중 쓰기"`

---

### Task 6: 백필 커맨드 + bidx UNIQUE 마이그레이션 준비

**Files:**
- Create: `was/src/main/java/com/celfit/was/crypto/PiiBackfillRunner.java`
- Create: `was/src/main/resources/db/migration/app/V<STAMP2>__pii_bidx_unique.sql` (**Task 7 롤아웃에서 백필 후 배포되는 PR에 포함** — STAMP2는 그 시점 재채번)
- Test: `was/src/test/java/com/celfit/was/PiiBackfillRunnerTest.java`

**Interfaces:**
- Consumes: `FieldCipher`
- Produces: `--crypto.backfill=true` 기동 시 4개 테이블의 `*_enc IS NULL` 행을 배치 암호화(멱등 — 이미 채워진 행 무시), 완료 로그에 테이블별 처리 건수. 종료 후 앱은 정상 기동 계속(one-shot 아님 — 재실행 무해).

- [ ] **Step 1: 실패하는 테스트 작성** — 평문만 있는 행을 SQL로 직접 시드(이중 쓰기 우회: `INSERT INTO app.users (email, password_hash, name) VALUES ('legacy@ex.com','h','레거시')`) → `runner.backfillAll()` 호출 → enc/bidx 채워짐 + 복호화 일치 + 재호출 시 추가 변경 0건(멱등) 검증. 4개 테이블 동일 패턴.

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현**

```java
package com.celfit.was.crypto;

import com.celfit.was.auth.UserRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 암호화 백필(스펙 §전환 2) — *_enc IS NULL 행만 채우는 멱등 커맨드.
 * 기동 플래그 --crypto.backfill=true일 때만 실행(운영 롤아웃 §Task 7 러너 절차 참조).
 * 클로즈베타 규모(users 104명)라 전량 단순 루프 — 수천 행 초과 시 배치 분할로 재작업.
 */
@Component
@ConditionalOnProperty(name = "crypto.backfill", havingValue = "true")
public class PiiBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PiiBackfillRunner.class);

	private final JdbcClient jdbc;
	private final FieldCipher cipher;

	public PiiBackfillRunner(JdbcClient jdbc, FieldCipher cipher) {
		this.jdbc = jdbc;
		this.cipher = cipher;
	}

	@Override
	public void run(ApplicationArguments args) {
		backfillAll();
	}

	@Transactional
	public void backfillAll() {
		int users = backfillUsers();
		int inquiries = backfillInquiries();
		int resets = backfillPasswordResets();
		int events = backfillSignupEvents();
		log.info("PII 백필 완료 — users={}, inquiries={}, password_resets={}, signup_events={}",
				users, inquiries, resets, events);
	}

	private int backfillUsers() {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, email, name, nickname, phone_number FROM app.users WHERE email_enc IS NULL""")
				.query().listOfRows();
		for (Map<String, Object> r : rows) {
			String email = (String) r.get("email");
			jdbc.sql("""
					UPDATE app.users SET email_enc = :ee, email_bidx = :eb, name_enc = :ne,
					       nickname_enc = :ke, phone_number_enc = :pe WHERE id = :id""")
					.param("ee", cipher.encrypt(email))
					.param("eb", cipher.blindIndex(UserRepository.normalizeEmail(email)))
					.param("ne", cipher.encrypt((String) r.get("name")))
					.param("ke", cipher.encrypt((String) r.get("nickname")))
					.param("pe", cipher.encrypt((String) r.get("phone_number")))
					.param("id", r.get("id"))
					.update();
		}
		return rows.size();
	}

	// backfillInquiries / backfillPasswordResets / backfillSignupEvents — 동일 패턴:
	// SELECT (평문 컬럼) WHERE email_enc IS NULL → 행별 UPDATE로 enc(+bidx) 기록.
	// inquiries는 name/email/organization/message 4컬럼, password_resets는 email(+bidx),
	// signup_events는 email(+bidx는 normalizeEmail 적용)·ip. 각 메서드 반환값은 처리 행 수.
	// (구현 시 위 backfillUsers처럼 전체 SQL을 그대로 작성한다 — 요약 금지)
}
```

bidx UNIQUE 마이그레이션(백필 후 배포 PR에 포함):

```sql
-- 백필 완료 후에만 적용(트랙 A 스펙 §전환) — 부분 백필 상태에서 NULL 다중은 UNIQUE 허용이라 안전하지만,
-- 정합 보증을 위해 백필 → 본 마이그레이션 → 읽기 전환 순서를 지킨다(롤아웃 Task 7).
CREATE UNIQUE INDEX users_email_bidx_key ON app.users (email_bidx);
CREATE UNIQUE INDEX password_resets_email_bidx_key ON app.password_resets (email_bidx);
CREATE INDEX signup_events_bidx_ix ON app.signup_events (email_bidx, created_at);
```

- [ ] **Step 4: 통과 확인** — 백필 테스트 + 멱등 재실행 테스트 PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): PII 백필 커맨드(멱등) + bidx UNIQUE 마이그레이션"`

---

### Task 7: [롤아웃 게이트 — 코드 아님] 스테이징·운영 백필 절차

**Files:** 없음 (runbook — PR 본문·트랙 문서에 기록)

이 지점에서 **PR 1(Task 1~6, 9, 10)을 머지·배포**하고, 읽기 전환(Task 8)은 백필 완료 후의 **PR 2**로 나눈다.

- [x] **Step 1: DEK 생성·등록 — 자동 부트스트랩으로 대체(Task 11, 계획 결함 수정)**
  최초 작성 시점엔 로컬에서 `openssl rand`로 DEK를 만들어 수동 등록하는 절차였으나, 이는
  vault 모드 첫 부팅이 `app.encryption_keys` 행 부재로 죽는다는 결함이 있었다(테이블은 같은
  부팅의 Flyway가 막 만들고, 행은 아무도 넣어두지 않는다). `CryptoConfig`/`DekStore`가 첫
  부팅에서 행이 없으면 스스로 생성·래핑·`INSERT … ON CONFLICT DO NOTHING`·재조회까지
  전부 수행한다 — 수동 단계는 없다. 이 Step은 이제 **첫 vault 기동 후 행 존재 확인만**:
  `SELECT key_id, created_at FROM app.encryption_keys;`(deploy/README.md §6-3).

- [ ] **Step 2: 스테이징(develop→staging)에서** `CRYPTO_MODE=vault` 기동 확인(언래핑 성공 로그) → 이중 쓰기 검증(신규 가입 1건 → enc 컬럼 확인)
- [ ] **Step 3: 스테이징 백필** — `--crypto.backfill=true`로 1회 기동, 처리 건수 로그 확인
- [ ] **Step 4: 운영 배포(staging→main) 후 운영 백필** — 같은 절차. `SELECT count(*) FROM app.users WHERE email_enc IS NULL` = 0 확인
- [ ] **Step 5: PR 2(읽기 전환 + bidx UNIQUE 마이그레이션) 진행 승인**

---

### Task 8: 읽기 전환 — 조회를 암호문·bidx 기준으로

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java` (findByEmail·findProfileByEmail·findById·findProfileById·insert/insertProfile RETURNING)
- Modify: `was/src/main/java/com/celfit/was/v1/account/PasswordResetRepository.java` (email → email_bidx 조회)
- Modify: `was/src/main/java/com/celfit/was/v1/admin/AdminUserRepository.java` (메모리 필터)
- Modify: `was/src/main/java/com/celfit/was/v1/inquiry/InquiryRepository.java` (목록 복호화)
- Test: `was/src/test/java/com/celfit/was/PiiReadSwitchTest.java` + 기존 통합 테스트 전체 회귀

**Interfaces:**
- Consumes: Task 3 컬럼, Task 6 UNIQUE 인덱스, `FieldCipher`
- Produces: 모든 이메일 조회가 `email_bidx = blindIndex(normalizeEmail(q))`, 모든 표시용 값이 `decrypt(*_enc)`. **레코드(AppUser·UserProfile·AdminUserRow 등)와 컨트롤러 시그니처는 무변경** — 리포지토리가 SELECT에서 enc 컬럼을 읽어 복호화한 값으로 레코드를 조립한다(JdbcClient 자동 매핑 → 명시적 RowMapper 전환이 필요한 메서드는 전환).

- [ ] **Step 1: 실패하는 테스트 작성** — 핵심 시나리오: ① 평문 컬럼을 일부러 구식 값으로 오염(`UPDATE app.users SET email='stale@x.com' WHERE id=:id`)시킨 뒤 `findByEmail("real@ex.com")`이 **bidx 기준으로** 올바른 행을 찾고 레코드의 email이 **복호화 값**(`real@ex.com`)인지 — 읽기가 평문 컬럼에 더는 의존하지 않음을 직접 증명. ② 로그인 전 과정(AppUserDetailsService.loadUserByUsername) 왕복. ③ AdminUserRepository.findPage("철수") 메모리 필터가 암호화된 name에서 부분일치 매칭. ④ password_resets 흐름(요청→confirm→reset)이 bidx 조회로 완주. ⑤ 중복 이메일 가입이 email_bidx UNIQUE 위반으로 DuplicateKeyException(기존 예외 계약 유지).

- [ ] **Step 2: 실패 확인** — FAIL(아직 평문 조회)

- [ ] **Step 3: 구현.** 요지: `findByEmail`은 `WHERE email_bidx = :bidx`, SELECT 목록을 `id, email_enc, password_hash, role, created_at`로 바꾸고 RowMapper에서 `cipher.decrypt(rs.getString("email_enc"))`를 AppUser.email에 채운다(레코드 형태 불변). `PROFILE_COLUMNS` 계열은 enc 컬럼 병행 SELECT 후 복호화 조립. AdminUserRepository.findPage는 검색어가 있으면 `SELECT` 전체(WHERE 없이) 후 복호화·`contains` 필터·수동 페이지네이션(클로즈베타 규모 전제 — 스펙 한계 명시), 검색어 없으면 기존 페이지 쿼리 유지 + 복호화 조립. signup_events 어뷰징 조회 경로가 있으면 email_bidx 기준으로.

- [ ] **Step 4: 전체 회귀** — `./gradlew :was:test` (PR 직전 전체 실행 규칙) → PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): PII 읽기 전환 — bidx 조회 + 복호화 조립(레코드·API 불변)"`

---

### Task 9: 세션 principal 교체 — 이메일 제거(재로그인 없음)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/AppUserDetails.java`
- Modify: `was/src/main/java/com/celfit/was/auth/UserResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/account/V1MeController.java`
- Test: `was/src/test/java/com/celfit/was/AppUserDetailsPrincipalTest.java`

**Interfaces:**
- Consumes: `AppUserDetails.getUserId()`(기존), `UserRepository.findById`
- Produces: `getUsername()` = `String.valueOf(userId)`, 직렬화 형상(필드 선언·serialVersionUID) 불변 — email 필드는 남기되 생성자에서 null 저장. SessionService 호출부는 `principal.getUsername()` 그대로(값만 userId 문자열로 바뀜 — principal_name 매칭 의미론 유지).

- [ ] **Step 1: 실패하는 테스트 작성** — ① `getUsername()`이 userId 문자열 반환. ② **직렬화 호환 고정 픽스처**: 구버전 형상(email 채워진 AppUserDetails)을 ObjectOutputStream으로 직렬화한 byte[]를 테스트 리소스로 고정해두고, 새 클래스로 역직렬화 성공 + `getUserId()` 보존 검증(전원 재로그인 없음의 직접 증거 — 픽스처는 이 태스크 시작 시점(변경 전 클래스)에 생성해 커밋). ③ 새로 만든 인스턴스 직렬화 바이트에 이메일 문자열 미포함(`new String(bytes, ISO_8859_1)`에 "@" 미검출 수준의 실용 검증).

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현** — `AppUserDetails`: 생성자에서 `this.email = null;`(필드 선언·serialVersionUID 유지, 클래스 주석에 "email 필드는 직렬화 호환용 잔존 — 값은 항상 null(트랙 A)" 갱신), `getUsername()`은 `String.valueOf(userId)`. `UserResponse.from(principal)`은 이메일이 필요하므로 시그니처를 `from(AppUserDetails principal, String email)`로 바꾸고 호출부(로그인 응답 조립부)가 `userRepository.findById(principal.getUserId())`의 복호화 이메일을 전달 — 호출부 전수는 `grep -rn "UserResponse.from" was/src/main/java`로 확인 후 일괄 수정. `V1MeController`의 `sessionService.*(principal.getUsername(), ...)` 4곳은 무수정(값 의미만 변경 — 신규 세션은 userId로 매칭). 전환기 한계(기존 email-principal 세션은 세션 목록·강제 로그아웃에서 안 보임, 만료로 자연 소거)를 컨트롤러 주석으로 기록.

- [ ] **Step 4: 통과 확인** — Step 1 테스트 + 로그인 통합 테스트(`V1AuthControllerTest`) 회귀 → PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): 세션 principal 이메일→userId — 형상 유지로 재로그인 없이 전환"`

---

### Task 10: signup_events 90일 보존 스케줄러

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/account/SignupEventRetentionScheduler.java`
- Test: `was/src/test/java/com/celfit/was/SignupEventRetentionTest.java`

**Interfaces:**
- Consumes: `JdbcClient`, `Clock`(기존 ClockConfig 빈)
- Produces: 매일 UTC 03:40(감사 로그 03:30과 겹침 회피) `created_at < now()-90d` 삭제. 설정 키 `signup-events.retention.cron`으로 오버라이드 가능.

- [ ] **Step 1: 실패하는 테스트 작성** — 91일 전·89일 전 행 시드(created_at 직접 지정 INSERT) → `scheduler.deleteExpired()` 호출 → 91일 전 행만 삭제 검증.

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현** — `AdminAuditLogRetentionScheduler` 관용구 복제(단일 책임 @Scheduled 컴포넌트, RETENTION_DAYS=90, cron `${signup-events.retention.cron:0 40 3 * * *}` zone UTC, 삭제 건수 info 로그, 실패는 error 로그 후 다음 주기 재시도):

```java
package com.celfit.was.v1.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 가입 시도 이벤트 90일 보존 배치(트랙 A 스펙 §signup_events) — 비회원 이메일 포함 로그라
 * 목적(디버깅·어뷰징 추적) 소멸분은 파기한다(암호화와 별개의 법적 파기 의무).
 * AdminAuditLogRetentionScheduler 관용구.
 */
@Component
public class SignupEventRetentionScheduler {

	private static final Logger log = LoggerFactory.getLogger(SignupEventRetentionScheduler.class);
	private static final int RETENTION_DAYS = 90;

	private final JdbcClient jdbcClient;
	private final Clock clock;

	public SignupEventRetentionScheduler(JdbcClient jdbcClient, Clock clock) {
		this.jdbcClient = jdbcClient;
		this.clock = clock;
	}

	@Scheduled(cron = "${signup-events.retention.cron:0 40 3 * * *}", zone = "UTC")
	public void deleteExpired() {
		OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
		try {
			int deleted = jdbcClient.sql("DELETE FROM app.signup_events WHERE created_at < :cutoff")
					.param("cutoff", cutoff)
					.update();
			log.info("가입 이벤트 보존 삭제 — cutoff={}, 삭제 건수={}", cutoff, deleted);
		} catch (RuntimeException e) {
			log.error("가입 이벤트 보존 삭제 실패 — cutoff={}", cutoff, e);
		}
	}
}
```

- [ ] **Step 4: 통과 확인** — PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(was): signup_events 90일 보존 배치 — 비회원 이메일 파기"`

---

### Task 11: 배포 설정 — compose env + 문서·트랙 정리 (PR 1 마무리)

**Files:**
- Modify: `deploy/compose.yaml`·`deploy/compose.test.yaml` (was 서비스 env: `CRYPTO_MODE`, `CRYPTO_KEK_OCID`, `CRYPTO_CRYPTO_ENDPOINT` — 값은 `.env` 참조로)
- Modify: `deploy/README.md` (§6-2 뒤에 §6-3 "PII 봉투 암호화 키 운영" — KEK OCID·DEK 등록 절차·로테이션 개요, Task 0·7의 명령 기록)
- Create: `docs/tracks/` 신규 트랙 파일(트랙 문자 규칙은 기존 파일 확인 후 채번) — 상태·PR 링크·롤아웃 체크리스트(Task 7)
- Modify: `DECISIONS.md` 맨 위 결정 1행

- [ ] **Step 1: compose·README·트랙 문서 작성** (서버 `.env`에 실값 추가는 배포 시 수동 — README에 명시)
- [ ] **Step 2: 전체 테스트** — `./gradlew :was:test` → PASS 확인 후
- [ ] **Step 3: 커밋 + PR 1 오픈** — base develop, 본문에 스펙 링크·롤아웃 게이트(Task 7이 PR 2의 선행 조건임을 명시). plan 문서는 **PR 2 머지 시** `plans/archive/`로 이동(세션 위생 규칙 — 실행 완료 시점 기준).

---

### Task 12: [다음 릴리스 — PR 3, 지금 실행 금지] contract — 평문 컬럼 DROP

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<STAMP3>__pii_contract_drop_plaintext.sql`

읽기 전환(PR 2)이 운영에 배포되고 **한 릴리스 간격**이 지난 뒤에만:

```sql
-- allow-destructive: 트랙 A contract 단계 — 읽기 전환(PR 2) 배포 후 릴리스 간격 확보, 참조 코드 전무 확인
-- 롤링 창 유실 백필: 이중 쓰기가 PR 1부터 계속이라 enc 미기록 행은 구조상 없다 — 최종 검증만 수행
-- no-backfill: 이중 쓰기 기간 전체에 enc 동시 기록 — DROP 대상 평문을 참조할 백필이 남아있지 않음
UPDATE app.users SET email_enc = email_enc WHERE email_enc IS NULL;  -- 방어적 검증(0행이어야 정상)

ALTER TABLE app.users DROP COLUMN email, DROP COLUMN name, DROP COLUMN nickname, DROP COLUMN phone_number;
ALTER TABLE app.inquiries DROP COLUMN name, DROP COLUMN email, DROP COLUMN organization, DROP COLUMN message;
ALTER TABLE app.signup_events DROP COLUMN email, DROP COLUMN ip;
-- password_resets: PK 교체를 여기서 — email_bidx를 PK로 승격 후 email DROP
ALTER TABLE app.password_resets DROP CONSTRAINT password_resets_pkey;
ALTER TABLE app.password_resets ALTER COLUMN email_bidx SET NOT NULL;
ALTER TABLE app.password_resets ADD PRIMARY KEY (email_bidx);
ALTER TABLE app.password_resets DROP COLUMN email;
```

주의: users.email에 걸린 기존 UNIQUE 제약·인덱스는 컬럼 DROP과 함께 소멸. **가드 v2 짝 검사**(DROP 파일의 보정 UPDATE 동봉) 형식을 지키되, 실제 백필 불요 사유를 위 주석으로 명시 — 가드 통과 형식은 [deploy/README.md §5-1] 기준으로 작성 시점 재확인. 이 마이그레이션 후 이중 쓰기 코드 제거(평문 컬럼 참조 삭제) 커밋을 같은 PR에 동승.

---

## Self-Review 결과

- 스펙 커버리지: 키 계층(T0·T2·T7), 암호문·bidx(T1), 대상 컬럼 전부(T3~T6·T8), 세션(T9), 보존(T10), expand-contract(T3·T6·T12), 어드민 메모리 필터(T8), 로컬 모드·실패 모드(T2), 테스트 전략(각 태스크) — 갭 없음.
- 아카이브 상호작용: `deleteAccount`의 ArchiveWriter가 행을 통째 이관하므로 이중 쓰기 기간엔 평문+암호문이 함께 아카이브된다 — contract 후 신규 아카이브는 enc만 남는다. 기존 아카이브의 평문 정리는 트랙 후속 항목으로 트랙 문서(T11)에 기록.
- 타입 일관성: FieldCipher 시그니처(T1)를 T4~T8이 동일 사용, DekBundle 64바이트 규약을 T2·T7(openssl rand 64)이 공유 — 일치.
