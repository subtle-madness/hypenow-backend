# 성과 대시보드 조건부 요청(ETag/304) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 (2026-08-13 작성, 미착수)
>
> 설계 정본: [specs/2026-08-13-performance-dashboard-etag-design.md](../specs/2026-08-13-performance-dashboard-etag-design.md)
> — **배경·측정치·기각한 대안은 그 문서에 있다. 이 문서는 실행 절차만 담는다.**
> 브랜치 `feature/data-fetch-performance-54d741` · [PR #467](https://github.com/subtle-madness/hypenow-backend/pull/467)(draft, 문서만)

**Goal:** 성과 대시보드 두 엔드포인트의 반복 요청을 조립 전에 304로 끊어, 대역폭과 서버
조립 시간(~800ms)을 함께 제거한다.

**Architecture:** 요청 진입 시 값싼 쿼리 2개로 **버전키**를 만들고 `If-None-Match`와 비교해
일치하면 304로 조기 반환한다(조립·직렬화·전송을 전부 건너뛴다). 버전키는 유지보수 규율에
의존하지 않도록 **유저 소유 행 전체를 Postgres에서 해싱**해 얻고(`row::text`), 여기에 스윕
워터마크·KST 날짜·빌드 세대를 더한다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · JdbcClient · Testcontainers(PostgreSQL) · MockMvc

## Global Constraints

- 주석·커밋 메시지는 **한국어**. 커밋 prefix는 `perf(was):` / `test(was):` 형식.
- was는 **분석 결과 읽기 전용**, 쓰기는 `app` 스키마에만. raw DB 접근 금지.
- **분석 결과와 서비스 데이터를 SQL 조인하지 않는다** — monitoring DB 조회와 app 스키마
  조회는 별개 쿼리이고, 조합은 자바에서 한다.
- 테스트는 모듈 단위: `./gradlew :was:test`. **전체 `./gradlew test`는 PR 직전에만.**
- 셸에 `DOCKER_HOST`가 없으면 Testcontainers가 무더기로 죽는다. 작업 시작 전 필수:
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
  (로컬이 Docker Desktop이면 이 export는 불필요 — 대량 실패를 보면 이것부터 의심할 것)
- **DB 마이그레이션 없음.** 이 계획은 스키마를 바꾸지 않는다(설계 §2-3에서 `updated_at`
  컬럼 추가를 명시적으로 기각했다 — 쓰기 경로를 한 곳만 놓쳐도 낡은 데이터를 조용히 서빙한다).

## File Structure

| 파일 | 책임 |
|---|---|
| `was/.../monitoring/UserDataFingerprintRepository.java` **(신규)** | app 스키마에서 유저 소유 행 전체의 md5 지문 1개를 얻는다. 쿼리 1개, 왕복 1회 |
| `was/.../monitoring/BrandReadRepository.java` (수정) | 브랜드 스윕 워터마크 조회 메서드 1개 추가 |
| `was/.../v1/perfdashboard/PerformanceDashboardVersion.java` **(신규)** | 입력 5종을 모아 ETag 문자열 1개를 만든다. monitoring 비활성 환경 내성 |
| `was/.../v1/perfdashboard/V1PerformanceDashboardController.java` (수정) | 304 조기 반환 + 캐시 헤더. 조립 호출은 그대로 |
| `was/src/test/.../UserDataFingerprintRepositoryTest.java` **(신규)** | 지문이 쓰기 6종 각각에 반응하는지(Testcontainers) |
| `was/src/test/.../PerformanceDashboardVersionTest.java` **(신규)** | 입력 5종 각각이 키를 바꾸는지(mock) |
| `was/src/test/.../V1PerformanceDashboardControllerTest.java` (수정) | 304/200 분기와 헤더 계약 |

---

### Task 1: 앱 스키마 지문 리포지토리

유저의 쓰기(등록·취소·기간변경·캠페인변경·브랜드 연결/해제)를 감지하는 단일 값을 만든다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/UserDataFingerprintRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/UserDataFingerprintRepositoryTest.java`

**Interfaces:**
- Consumes: 없음(app `JdbcClient` 기본 빈만)
- Produces: `String UserDataFingerprintRepository.fingerprint(long userId)` — 32자 md5 hex

**설계 근거(요약 — 전말은 설계 §2-3):** 컬럼을 열거하지 않고 **행 전체**(`i::text`)를 해싱한다.
컬럼을 열거하면 나중에 응답에 영향을 주는 컬럼이 추가될 때 여기를 같이 고쳐야 하고, 안 고치면
낡은 데이터를 조용히 서빙한다. 행 전체 해싱은 응답과 무관한 컬럼이 늘어도 **불필요한 200이
한 번 더 나갈 뿐**(정확성 손상 없음)이라 안전한 쪽으로 틀린다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 지문이 유저 쓰기 전 종류에 반응하는지 고정한다 — 하나라도 반응하지 않으면 낡은 응답을 서빙하게 된다. */
class UserDataFingerprintRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserDataFingerprintRepository repository;

	private long userId;

	/**
	 * 유저를 매번 새로 만든다 — monitoring_items·brand_monitorings·brand_direct_posts가 전부
	 * {@code app.users(id)}를 FK로 참조해서 임의 user_id로 INSERT하면 제약 위반이다.
	 * 새 유저는 소유 행이 0건에서 시작하므로 테스트 간 정리도 필요 없다.
	 */
	@BeforeEach
	void createUser() {
		userId = newUser();
	}

	private long newUser() {
		// email·password_hash만 기본값이 없다(나머지 NOT NULL 컬럼은 전부 DEFAULT 보유).
		return jdbc.sql("""
				INSERT INTO app.users (email, password_hash)
				VALUES ('fp-' || gen_random_uuid() || '@example.com', 'x')
				RETURNING id
				""").query(Long.class).single();
	}

	private long insertItem(long owner) {
		return jdbc.sql("""
				INSERT INTO app.monitoring_items
				    (user_id, mode, registration_key, input_value, keywords, tracking_days, registered_on)
				VALUES (:u, 'url', gen_random_uuid(), 'https://www.instagram.com/p/ABC/', '{}'::jsonb, 30, current_date)
				RETURNING id
				""").param("u", owner).query(Long.class).single();
	}

	@Test
	void 행이_없으면_고정값이고_등록하면_바뀐다() {
		String empty = repository.fingerprint(userId);
		assertThat(empty).hasSize(32);

		insertItem(userId);

		assertThat(repository.fingerprint(userId)).isNotEqualTo(empty);
	}

	@Test
	void 기간_변경이_지문을_바꾼다() {
		long itemId = insertItem(userId);
		String before = repository.fingerprint(userId);

		// updated_at 컬럼이 없어 워터마크로는 감지 불가능한 변경 — 지문 방식을 택한 이유다.
		jdbc.sql("UPDATE app.monitoring_items SET tracking_days = 90 WHERE id = :id").param("id", itemId).update();

		assertThat(repository.fingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 취소가_지문을_바꾼다() {
		long itemId = insertItem(userId);
		String before = repository.fingerprint(userId);

		jdbc.sql("UPDATE app.monitoring_items SET canceled_at = now() WHERE id = :id").param("id", itemId).update();

		assertThat(repository.fingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 브랜드_연결과_해제가_지문을_바꾼다() {
		String before = repository.fingerprint(userId);

		jdbc.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type)
				VALUES (:u, 991, 'somebrand', 'own')
				""").param("u", userId).update();
		String linked = repository.fingerprint(userId);
		assertThat(linked).isNotEqualTo(before);

		// 해제는 soft-delete라 행이 남는다 — deleted_at만 바뀌므로 행 전체 해싱이 아니면 놓친다.
		jdbc.sql("UPDATE app.brand_monitorings SET deleted_at = now() WHERE user_id = :u").param("u", userId).update();

		assertThat(repository.fingerprint(userId)).isNotEqualTo(linked);
	}

	@Test
	void 다른_유저의_쓰기는_내_지문을_바꾸지_않는다() {
		String before = repository.fingerprint(userId);

		insertItem(newUser());

		assertThat(repository.fingerprint(userId)).isEqualTo(before);
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.UserDataFingerprintRepositoryTest"
```
Expected: FAIL — `UserDataFingerprintRepository` 타입이 없어 컴파일 에러.

- [ ] **Step 3: 최소 구현**

```java
package com.celfit.was.monitoring;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 성과 대시보드 ETag의 "유저 쓰기" 입력 — 유저 소유 행 전체를 해싱한 값 하나(설계 §2-3).
 *
 * <p>컬럼을 열거하지 않고 {@code row::text}로 행 전체를 해싱한다. 열거하면 응답에 영향을 주는
 * 컬럼이 추가될 때 여기를 같이 고쳐야 하고, 안 고치면 <b>낡은 응답을 조용히 서빙</b>한다.
 * 행 전체 해싱은 무관한 컬럼이 늘어도 불필요한 200이 한 번 더 나갈 뿐이라 안전한 쪽으로 틀린다.
 *
 * <p>{@code app.monitoring_items}에 {@code updated_at}이 없어(created_at·canceled_at뿐)
 * 기간·캠페인 변경을 {@code max(updated_at)} 워터마크로는 감지할 수 없다 — 이 제약이 지문
 * 방식을 강제했다. 유저당 행이 작아(운영 실측 최대 33행) 비용은 무시할 수준이다.
 */
@Repository
public class UserDataFingerprintRepository {

	private final JdbcClient jdbcClient;

	public UserDataFingerprintRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저 소유 행 전체의 md5(32자 hex). 행이 하나도 없어도 빈 입력의 해시를 돌려준다(항상 non-null). */
	public String fingerprint(long userId) {
		return jdbcClient.sql("""
				SELECT md5(
				         coalesce((SELECT string_agg(i::text, ',' ORDER BY i.id)
				                     FROM app.monitoring_items i WHERE i.user_id = :userId), '')
				         || '|' ||
				         coalesce((SELECT string_agg(b::text, ',' ORDER BY b.id)
				                     FROM app.brand_monitorings b WHERE b.user_id = :userId), '')
				         || '|' ||
				         coalesce((SELECT string_agg(d::text, ',' ORDER BY d.short_code)
				                     FROM app.brand_direct_posts d WHERE d.user_id = :userId), '')
				       ) AS fingerprint
				""")
				.param("userId", userId)
				.query(String.class)
				.single();
	}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.monitoring.UserDataFingerprintRepositoryTest"
```
Expected: PASS (5개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/UserDataFingerprintRepository.java \
        was/src/test/java/com/celfit/was/monitoring/UserDataFingerprintRepositoryTest.java
git commit -m "perf(was): 성과 대시보드 ETag용 유저 데이터 지문 리포지토리

행 전체(row::text) 해싱으로 유저 쓰기를 감지한다. app.monitoring_items에
updated_at이 없어 기간·캠페인 변경을 워터마크로 잡을 수 없고, 컬럼 열거
방식은 컬럼 추가 시 갱신을 놓치면 낡은 응답을 조용히 서빙한다."
```

---

### Task 2: 버전키 조립 컴포넌트

입력 5종(설계 §2-1)을 모아 ETag 문자열 하나를 만든다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceDashboardVersion.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (메서드 1개 추가)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceDashboardVersionTest.java`

**Interfaces:**
- Consumes: `UserDataFingerprintRepository.fingerprint(long)` (Task 1) ·
  `MonitoringReadRepository.lastSuccessfulSweepAt()` (기존) ·
  `BrandReadRepository.maxLastSweptAt()` (이 태스크에서 추가)
- Produces: `String PerformanceDashboardVersion.etagFor(long userId)` — `W/"..."` 형식의 완성된 ETag 헤더 값

**결정 두 가지(설계 §2-5 반영):**

1. **브랜드 스윕 워터마크는 유저 스코프가 아니라 전역** `max(last_swept_at)`이다. 유저의
   brandId 목록을 먼저 알아야 하는 의존을 없애 쿼리 1개로 끝난다. 스윕은 전 브랜드가 같은
   새벽에 한 번 도므로 유저 스코프와 실질적으로 같고, 과다 무효화되어도 불필요한 200이
   한 번 더 나갈 뿐이다.
2. **이미지 아카이브 워터마크는 v1에서 넣지 않는다.** `brand_post_meta`가 22,003행이라
   `max(image_archived_at)`이 순차 스캔인데, 아카이브 잡은 스윕과 같은 체인에서 돌고
   `last_swept_at`이 매일 바뀌므로 지연 상한이 24시간이다. 그동안 서빙되는 값은 지금도
   쓰고 있는 원본 CDN URL이라 회귀가 아니다. 문제가 되면 인덱스와 함께 후속으로 넣는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.UserDataFingerprintRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 버전키 입력 5종(설계 §2-1)이 각각 키를 바꾸는지 고정한다. 하나라도 반응하지 않으면
 * 낡은 응답을 304로 계속 돌려주게 되므로, 이 테스트가 정확성의 핵심 방어선이다.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceDashboardVersionTest {

	private static final long USER = 7L;
	/** KST 2026-08-13 12:00 — 자정 경계에서 충분히 떨어진 기준 시각. */
	private static final Instant NOON_KST = Instant.parse("2026-08-13T03:00:00Z");

	@Mock
	UserDataFingerprintRepository fingerprintRepository;
	@Mock
	MonitoringReadRepository monitoringReadRepository;
	@Mock
	BrandReadRepository brandReadRepository;

	private Clock clock;

	// lenient 필수 — monitoring 비활성 테스트는 스윕 스텁을 쓰지 않아, 엄격 모드면
	// UnnecessaryStubbingException으로 죽는다(MockitoExtension 기본이 STRICT_STUBS).
	@BeforeEach
	void setUp() {
		clock = Clock.fixed(NOON_KST, ZoneId.of("Asia/Seoul"));
		lenient().when(fingerprintRepository.fingerprint(USER)).thenReturn("fp-1");
		lenient().when(monitoringReadRepository.lastSuccessfulSweepAt())
				.thenReturn(OffsetDateTime.parse("2026-08-13T02:00:00Z"));
		lenient().when(brandReadRepository.maxLastSweptAt())
				.thenReturn(OffsetDateTime.parse("2026-08-13T02:30:00Z"));
	}

	private PerformanceDashboardVersion version(Clock at) {
		return new PerformanceDashboardVersion(fingerprintRepository,
				Optional.of(monitoringReadRepository), Optional.of(brandReadRepository), at, "build-1");
	}

	@Test
	void 형식은_약한_ETag다() {
		assertThat(version(clock).etagFor(USER)).matches("^W/\"[0-9a-f]{16}\"$");
	}

	@Test
	void 같은_입력이면_같은_키다() {
		assertThat(version(clock).etagFor(USER)).isEqualTo(version(clock).etagFor(USER));
	}

	@Test
	void 유저_지문이_바뀌면_키가_바뀐다() {
		String before = version(clock).etagFor(USER);
		given(fingerprintRepository.fingerprint(USER)).willReturn("fp-2");
		assertThat(version(clock).etagFor(USER)).isNotEqualTo(before);
	}

	@Test
	void 레거시_스윕이_돌면_키가_바뀐다() {
		String before = version(clock).etagFor(USER);
		given(monitoringReadRepository.lastSuccessfulSweepAt())
				.willReturn(OffsetDateTime.parse("2026-08-14T02:00:00Z"));
		assertThat(version(clock).etagFor(USER)).isNotEqualTo(before);
	}

	@Test
	void 브랜드_스윕이_돌면_키가_바뀐다() {
		String before = version(clock).etagFor(USER);
		given(brandReadRepository.maxLastSweptAt())
				.willReturn(OffsetDateTime.parse("2026-08-14T02:30:00Z"));
		assertThat(version(clock).etagFor(USER)).isNotEqualTo(before);
	}

	@Test
	void KST_자정을_넘기면_키가_바뀐다() {
		// 데이터가 하나도 안 바뀌어도 파생값(ItemStatus.derive의 today, 365일 창 컷)이 달라진다.
		String before = version(clock).etagFor(USER);
		Clock nextDay = Clock.fixed(NOON_KST.plusSeconds(86_400), ZoneId.of("Asia/Seoul"));
		assertThat(version(nextDay).etagFor(USER)).isNotEqualTo(before);
	}

	@Test
	void 배포_세대가_바뀌면_키가_바뀐다() {
		// 응답 스키마가 바뀐 배포에서 옛 ETag가 맞으면 새 필드가 영영 안 나간다.
		String before = version(clock).etagFor(USER);
		PerformanceDashboardVersion redeployed = new PerformanceDashboardVersion(fingerprintRepository,
				Optional.of(monitoringReadRepository), Optional.of(brandReadRepository), clock, "build-2");
		assertThat(redeployed.etagFor(USER)).isNotEqualTo(before);
	}

	@Test
	void 유저가_다르면_키가_다르다() {
		given(fingerprintRepository.fingerprint(8L)).willReturn("fp-1");   // 지문이 같아도
		assertThat(version(clock).etagFor(8L)).isNotEqualTo(version(clock).etagFor(USER));
	}

	@Test
	void monitoring_비활성이면_스윕_입력_없이도_동작한다() {
		PerformanceDashboardVersion offline = new PerformanceDashboardVersion(fingerprintRepository,
				Optional.empty(), Optional.empty(), clock, "build-1");
		assertThat(offline.etagFor(USER)).matches("^W/\"[0-9a-f]{16}\"$");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceDashboardVersionTest"
```
Expected: FAIL — `PerformanceDashboardVersion`·`BrandReadRepository.maxLastSweptAt` 없음(컴파일 에러).

- [ ] **Step 3: `BrandReadRepository`에 워터마크 메서드 추가**

`was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`의 `findAccount` 바로
아래에 추가한다:

```java
	/**
	 * 브랜드 스윕 워터마크(전 브랜드 최신) — 성과 대시보드 ETag 입력(설계 §2-5 ②).
	 *
	 * <p>유저 스코프가 아닌 이유: 유저의 brandId 목록을 먼저 알아야 하는 의존이 사라져 쿼리
	 * 하나로 끝난다. 스윕은 전 브랜드가 같은 새벽에 한 번 도므로 유저 스코프와 실질적으로
	 * 같고, 과다 무효화되어도 불필요한 200이 한 번 더 나갈 뿐이다(정확성 손상 없음).
	 *
	 * <p>스윕이 한 번도 안 돈 환경에선 null이다 — 호출부가 그대로 버전키에 접는다.
	 */
	public OffsetDateTime maxLastSweptAt() {
		return jdbc.sql("SELECT max(last_swept_at) AS max_swept_at FROM brand_account")
				.query((rs, rowNum) -> rs.getObject("max_swept_at", OffsetDateTime.class))
				.optional()
				.orElse(null);
	}
```

- [ ] **Step 4: `PerformanceDashboardVersion` 구현**

```java
package com.celfit.was.v1.perfdashboard;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.UserDataFingerprintRepository;
import com.celfit.was.v1.common.KstTimestamps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 성과 대시보드 응답의 버전키 — 조립 <b>전에</b> 계산해 304 조기 반환에 쓴다(설계 §2).
 *
 * <p>응답을 바꾸는 입력 5종을 전부 담는다. 하나라도 빠지면 낡은 응답을 304로 계속 돌려주는
 * <b>침묵하는 오류</b>가 되므로, 입력을 늘릴 때는 반드시 {@code PerformanceDashboardVersionTest}에
 * 케이스를 함께 추가한다.
 *
 * <ol>
 *   <li>빌드 세대 — 응답 스키마가 바뀐 배포에서 옛 ETag가 맞으면 새 필드가 영영 안 나간다</li>
 *   <li>userId — 유저마다 응답이 다르다</li>
 *   <li>KST 날짜 — 데이터가 안 바뀌어도 파생값(상태 유도·365일 창 컷)이 자정에 달라진다</li>
 *   <li>스윕 워터마크(레거시·브랜드) — 새벽 수집 결과</li>
 *   <li>유저 지문 — 등록·취소·기간변경·캠페인변경·브랜드 연결/해제</li>
 * </ol>
 *
 * <p>monitoring 서브시스템이 꺼진 환경에선 스윕 입력이 없다({@link Optional} 빈 값) — 그래도
 * 나머지 입력만으로 정상 동작한다.
 */
@Component
public class PerformanceDashboardVersion {

	private final UserDataFingerprintRepository fingerprintRepository;
	private final Optional<MonitoringReadRepository> monitoringReadRepository;
	private final Optional<BrandReadRepository> brandReadRepository;
	private final Clock clock;
	private final String buildEpoch;

	public PerformanceDashboardVersion(UserDataFingerprintRepository fingerprintRepository,
			Optional<MonitoringReadRepository> monitoringReadRepository,
			Optional<BrandReadRepository> brandReadRepository,
			Clock clock, ObjectProvider<BuildProperties> buildProperties) {
		this(fingerprintRepository, monitoringReadRepository, brandReadRepository, clock,
				epochOf(buildProperties.getIfAvailable()));
	}

	// 테스트 심 — 빌드 세대를 직접 주입한다.
	PerformanceDashboardVersion(UserDataFingerprintRepository fingerprintRepository,
			Optional<MonitoringReadRepository> monitoringReadRepository,
			Optional<BrandReadRepository> brandReadRepository, Clock clock, String buildEpoch) {
		this.fingerprintRepository = fingerprintRepository;
		this.monitoringReadRepository = monitoringReadRepository;
		this.brandReadRepository = brandReadRepository;
		this.clock = clock;
		this.buildEpoch = buildEpoch;
	}

	/** {@code CacheConfig}의 cacheEpoch와 같은 관용구 — build.time이 없으면 "dev"로 접는다. */
	private static String epochOf(BuildProperties properties) {
		return (properties == null || properties.getTime() == null) ? "dev"
				: String.valueOf(properties.getTime().getEpochSecond());
	}

	/** {@code If-None-Match} 비교에 그대로 쓰는 완성된 ETag 헤더 값(약한 검증자). */
	public String etagFor(long userId) {
		LocalDate today = LocalDate.now(clock.withZone(KstTimestamps.KST));
		String legacySweep = String.valueOf(monitoringReadRepository
				.map(MonitoringReadRepository::lastSuccessfulSweepAt).orElse(null));
		String brandSweep = String.valueOf(brandReadRepository
				.map(BrandReadRepository::maxLastSweptAt).orElse(null));
		String raw = String.join("|", buildEpoch, String.valueOf(userId), today.toString(),
				legacySweep, brandSweep, fingerprintRepository.fingerprint(userId));
		// 약한 검증자다 — 바이트 동일성이 아니라 의미적 동등성만 주장한다(gzip 인코딩 변형 무관).
		return "W/\"" + md5Hex(raw).substring(0, 16) + "\"";
	}

	private static String md5Hex(String raw) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 미지원 JVM", e);   // 도달 불가(표준 알고리즘)
		}
	}
}
```

**주의:** `OffsetDateTime`을 `String.valueOf`로 접는 것은 의도적이다 — null 안전하고, 값이
바뀌면 문자열도 바뀌므로 해시 입력으로 충분하다. `Clock` 빈은 이미 `ClockConfig`에 있다.

- [ ] **Step 5: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.PerformanceDashboardVersionTest"
```
Expected: PASS (9개 테스트)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceDashboardVersion.java \
        was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceDashboardVersionTest.java
git commit -m "perf(was): 성과 대시보드 버전키 컴포넌트 — 입력 5종 집계

빌드 세대·userId·KST 날짜·스윕 워터마크 2종·유저 지문을 묶어 약한 ETag를
만든다. KST 날짜를 넣는 이유는 데이터가 안 바뀌어도 상태 유도와 365일 창
컷이 자정에 달라지기 때문이다. monitoring 비활성 환경 내성 포함."
```

---

### Task 3: 컨트롤러 304 조기 반환과 캐시 헤더

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
  (생성자, `contents()` `:88-135`, `comparison()` `:168-187`)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java` (추가)

**Interfaces:**
- Consumes: `PerformanceDashboardVersion.etagFor(long)` (Task 2)
- Produces: 없음(표면 계약 변경이 산출물)

**핵심 제약 — 조기 반환은 반드시 조립 앞이다.** `assembler.assembleSlim(...)`을 부른 뒤
비교하면 이 작업의 이득 절반(서버 시간 ~800ms)이 사라진다. 304 분기가 어셈블러 호출보다
위에 오는지 테스트로 고정한다(`then(assembler).should(never())`).

- [ ] **Step 1: 실패하는 테스트 작성**

`V1PerformanceDashboardControllerTest`에 `@MockitoBean PerformanceDashboardVersion version;`
필드를 추가하고, 아래 테스트를 클래스 끝에 붙인다. (기존 테스트가 깨지지 않도록
`@BeforeEach`에 `lenient().when(version.etagFor(anyLong())).thenReturn("W/\"deadbeefdeadbeef\"");`
스텁을 추가한다.)

```java
	private static final String ETAG = "W/\"deadbeefdeadbeef\"";

	@Test
	void If_None_Match가_일치하면_304이고_조립을_하지_않는다() throws Exception {
		mockMvc.perform(get(CONTENTS).with(user(principal())).header("If-None-Match", ETAG))
				.andExpect(status().isNotModified())
				.andExpect(header().string("ETag", ETAG));

		// 이 검증이 이 기능의 존재 이유다 — 조립을 했다면 대역폭만 아끼고 서버 시간은 그대로다.
		then(assembler).should(never()).assembleSlim(anyLong());
	}

	@Test
	void If_None_Match가_없으면_200이고_ETag를_붙인다() throws Exception {
		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", ETAG))
				.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void If_None_Match가_다르면_200이다() throws Exception {
		mockMvc.perform(get(CONTENTS).with(user(principal())).header("If-None-Match", "W/\"0000000000000000\""))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", ETAG));
	}

	@Test
	void 응답은_저장_가능하고_공유_캐시에는_안_남는다() throws Exception {
		// no-store면 브라우저가 사본을 못 들고 있어 304가 애초에 성립하지 않는다.
		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(header().string("Cache-Control", Matchers.containsString("private")))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-cache")))
				.andExpect(header().string("Cache-Control", Matchers.not(Matchers.containsString("no-store"))));
	}

	@Test
	void comparison도_같은_계약이다() throws Exception {
		mockMvc.perform(get("/v1/performance-dashboard/comparison").with(user(principal()))
						.header("If-None-Match", ETAG))
				.andExpect(status().isNotModified());

		then(comparisonAssembler).shouldHaveNoInteractions();
	}
```

import 추가: `static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;`

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"
```
Expected: FAIL — `PerformanceDashboardVersion` 빈 주입 불가 / 304 대신 200.

- [ ] **Step 3: 컨트롤러 수정**

생성자에 `PerformanceDashboardVersion version`을 추가하고 필드에 보관한다. 그리고 `contents()`와
`comparison()`의 반환 타입을 `ResponseEntity<...>`로 바꾼 뒤, **메서드 본문 맨 앞**(파라미터
검증보다도 앞은 아니고, 조립 호출보다는 반드시 앞)에 조기 반환을 넣는다.

`contents()`의 경우 — 기존 `normalizeFilter`·`parseDate` 검증 **뒤**, `assembler.assembleSlim`
**앞**에 배치한다(400이 304보다 우선해야 잘못된 요청이 조용히 304로 성공하지 않는다):

```java
	@GetMapping("/contents")
	public ResponseEntity<ApiResponse<List<PerformanceContentResponse>>> contents(
			WebRequest webRequest,
			@AuthenticationPrincipal AppUserDetails principal,
			/* ...기존 @RequestParam 그대로... */) {
		// ...기존 normalizeFilter·parseDate 검증 그대로...

		// 조기 반환은 조립 앞이다 — 뒤로 가면 대역폭만 아끼고 서버 시간(~800ms)은 그대로 나간다.
		// checkNotModified는 If-None-Match 파싱(다중 값·약한 비교)과 ETag 헤더 세팅을 함께 한다.
		String etag = version.etagFor(principal.getUserId());
		if (webRequest.checkNotModified(etag)) {
			return null;   // 304와 ETag 헤더는 checkNotModified가 이미 세팅했다
		}

		PerformanceContentAssembler.Assembled assembled = assembler.assembleSlim(principal.getUserId());
		// ...기존 필터·meta 조립 그대로...

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noCache().cachePrivate())
				.body(ApiResponse.ok(data, meta(data.size(), counted, assembled.lastCollectedAt())));
	}
```

`comparison()`도 같은 자리(검증 뒤, `assembler.assembleSlim` 앞)에 넣는다:

```java
	@GetMapping("/comparison")
	public ResponseEntity<ApiResponse<PerformanceComparisonResponse>> comparison(
			WebRequest webRequest,
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = normalizeFilter(campaignId);

		// 조립 앞 — /contents와 같은 버전키를 쓴다(같은 조립을 소비하므로 신선도 정의가 같다).
		String etag = version.etagFor(principal.getUserId());
		if (webRequest.checkNotModified(etag)) {
			return null;
		}

		// 슬림 조립(댓글 없음, 08-12) — 비교 집계는 스냅샷·업로드일만 소비한다.
		List<PerformanceContentResponse> filtered = assembler.assembleSlim(principal.getUserId()).contents().stream()
				.filter(c -> (sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& matchesCampaign(c, campaignFilter))
				.toList();
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noCache().cachePrivate())
				.body(ApiResponse.ok(comparisonAssembler.assemble(principal.getUserId(), filtered)));
	}
```

import 추가: `org.springframework.http.CacheControl` · `org.springframework.http.ResponseEntity` ·
`org.springframework.web.context.request.WebRequest`.

**`Cache-Control`이 실제로 나가는지 반드시 테스트로 확인할 것.** Spring Security의
`CacheControlHeadersWriter`는 응답에 이미 `Cache-Control`·`Expires`·`Pragma` 중 하나라도
있으면 물러나므로 컨트롤러 지정이 이기는 것이 정상이나, **버전에 따라 다를 수 있다.**
Step 1의 `응답은_저장_가능하고_공유_캐시에는_안_남는다` 테스트가 실패하면 컨트롤러 지정이
지고 있다는 뜻이므로, `SecurityConfig.securityFilterChain`에서 이 두 경로에만
`headers(h -> h.cacheControl(HeadersConfigurer.CacheControlConfig::disable))`를 적용하는
별도 `SecurityFilterChain`으로 전환한다(전역 비활성화는 금지 — 로그인·세션 표면까지 저장
가능해진다).

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.V1PerformanceDashboardControllerTest"
```
Expected: PASS — 기존 테스트 전부 + 신규 5개.

- [ ] **Step 5: 모듈 전체 회귀 확인**

```bash
./gradlew :was:test
```
Expected: PASS. 반환 타입을 `ResponseEntity`로 바꿨으므로 OpenAPI 문서 테스트
(`OpenApiDocsIntegrationTest`)와 다른 통합 테스트가 함께 도는지 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java \
        was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java
git commit -m "perf(was): 성과 대시보드 조건부 요청 — 조립 전 304 조기 반환

If-None-Match가 일치하면 조립·직렬화·전송을 전부 건너뛴다. 조기 반환이
조립보다 앞이라는 것이 이 기능의 핵심이라 never() 검증으로 고정했다.
캐시 헤더는 no-store를 빼고 private, no-cache로 — no-store면 브라우저가
사본을 못 들고 있어 304가 애초에 성립하지 않는다."
```

---

### Task 4: 스테이징 검증과 문서 마감

코드는 끝났고, **단위·통합 테스트로 잡히지 않는 계층**(브라우저·CORS·프록시·세션)을
스테이징에서 확인한다. 설계 §5의 8개 항목이 정본이다.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-13-performance-dashboard-etag-design.md` (상태 헤더)
- Modify: `DECISIONS.md` (해당 행의 "미구현" 표기 갱신)
- Move: `docs/superpowers/plans/2026-08-13-performance-dashboard-etag.md` → `plans/archive/`

- [ ] **Step 1: develop에 머지하고 스테이징 배포**

PR #467의 draft를 해제해 리뷰·머지한다. develop 머지 후 **develop→staging 머지**로
test 스테이징(dev-api.hypenow.io)에 배포된다(cd-test.yml).

- [ ] **Step 2: CORS 304 헤더 확인 — 최우선**

304에 CORS 헤더가 없으면 "서버는 304 정상인데 브라우저 fetch는 실패"로 나타난다.

```bash
curl -sD - -o /dev/null 'https://dev-api.hypenow.io/v1/performance-dashboard/contents?accountType=all' \
  -H 'Origin: https://www.hypenow.io' -H 'Cookie: hypenow-session=<스테이징 세션>'
# → 200에서 ETag 값을 복사한 뒤:
curl -sD - -o /dev/null 'https://dev-api.hypenow.io/v1/performance-dashboard/contents?accountType=all' \
  -H 'Origin: https://www.hypenow.io' -H 'Cookie: hypenow-session=<스테이징 세션>' \
  -H 'If-None-Match: <복사한 ETag>'
```
Expected: 두 번째가 `304`이고 응답에 `Access-Control-Allow-Origin`·
`Access-Control-Allow-Credentials`가 **둘 다** 있을 것. 없으면 조기 반환이 CORS 필터보다
앞에 있다는 뜻이므로 배치를 고친다.

- [ ] **Step 3: 브라우저 실동작 확인**

스테이징 프론트에서 대시보드를 열고 **새로고침**한다.
Expected: 두 번째 요청이 304이면서 화면이 정상 렌더된다. 콘솔에 CORS 에러가 없다.

- [ ] **Step 4: 나머지 5개 항목 확인**

설계 §5의 ②~⑧을 순서대로 확인하고 결과를 기록한다.

| 항목 | 확인 방법 | 기대 |
|---|---|---|
| ② `Vary` 정합 | 변경 전후 `Vary` 헤더 비교 | 동일 |
| ③ gzip 양쪽 | `Accept-Encoding: gzip` 있을 때와 없을 때 각각 | 양쪽 다 304 |
| ④ 304 본문 | 304 응답의 본문 길이·`Content-Length` | 본문 0, `Content-Length` 없음 |
| ⑤ 세션 슬라이딩 | 304만 반복하며 세션 타임아웃 구간 초과 | 로그인 유지 |
| ⑥ 버전키 누락 | 등록·취소·기간변경·캠페인변경·브랜드 연결·해제 각각 직후 조회 | 6종 모두 즉시 200 |
| ⑦ 자정 경계 | Task 2 단위 테스트로 대체(시각 주입) | 통과 기록 |
| ⑧ 지표 해석 | 운영 304 비율이 61% 미만이어도 결함 아님 | 기록만 |

- [ ] **Step 5: 운영 배포 후 효과 측정**

staging→main 머지 후 24시간 뒤 측정한다.

```bash
# 304 비율
ssh ubuntu@155.248.187.106 "docker exec deploy-prometheus-1 wget -qO- \
  'http://localhost:9090/api/v1/query?query=sum(increase(http_server_requests_seconds_count%7Buri%3D~%22/v1/performance-dashboard.*%22%7D%5B24h%5D))%20by%20(status)'"
```
Expected: `status="304"` 항목이 존재하고 비율이 유의미할 것(설계 §7 기준선: 반복률 61%가
상한, 브라우저 캐시 축출로 그보다 낮게 나오는 것은 정상 — §5-⑧).

- [ ] **Step 6: 문서 마감과 커밋**

설계 문서 상태 헤더를 `✅ 구현됨 (2026-MM-DD, PR #NNN)`으로 바꾸고, `DECISIONS.md`의 해당
행에서 "(설계 확정, **미구현**)"을 제거한다. 이 계획 문서를 `plans/archive/`로 옮긴다
(완료 문서가 남아 있으면 이후 세션들이 계속 읽어 컨텍스트 비용이 쌓인다 — CLAUDE.md 세션 위생).

```bash
git mv docs/superpowers/plans/2026-08-13-performance-dashboard-etag.md \
       docs/superpowers/plans/archive/
git add docs/superpowers/specs/2026-08-13-performance-dashboard-etag-design.md DECISIONS.md
git commit -m "docs: 성과 대시보드 ETag 구현 완료 반영 — 상태 헤더 갱신·계획 아카이브"
```

---

## 착수 전 확인

- [ ] `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (Testcontainers 대량 실패 방지)
- [ ] 설계 문서 [specs/2026-08-13-performance-dashboard-etag-design.md](../specs/2026-08-13-performance-dashboard-etag-design.md)를 먼저 읽을 것 — **왜 워터마크가 아니라 지문인지, 왜 페이지네이션이 아닌지**가 거기 있다
- [ ] 이 계획은 **스키마를 바꾸지 않는다** — 마이그레이션 파일을 만들 필요가 없다

## 범위 밖 (후속)

| 항목 | 비고 |
|---|---|
| 조립 캐시 | `PerformanceContentAssembler.assembleSlim` 자리. **12MB 객체 그래프 직렬화 비용을 먼저 측정**하고 Redis/Caffeine을 정한다. ETag가 먼저 들어가면 반복 요청은 조립까지 오지 않으므로 우선순위가 낮다 |
| `/brand-monitoring/.../posts` 확장 | 반복률 78%·누적 790MB로 단일 항목 최대. 같은 관용구 |
| 이미지 아카이브 워터마크 | Task 2에서 v1 제외 결정. 썸네일 경로 갱신이 최대 24시간 늦어도 무해하다는 판단 |
| FE 영속화 화이트리스트 버그 | **celfit-front 소관** — `persist.ts:37`의 `queryKey.length === 2`가 대시보드의 길이 4 키를 떨궈 6MB가 IndexedDB에 저장되지 않는다. 별도 전달 필요 |
