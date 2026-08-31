> 상태: ✅ 구현됨 · C 코드 선행분 전 8태스크 완료 · 회귀 통과(instagram-source 134 / monitoring 791, 0 failures, 토글 off=행동 변화 0) (2026-08-31)
> 범위: 마일스톤 C의 **코드 선행분만**(하드닝 3 + 런타임 토글/킬스위치 + Micrometer 메트릭 + og fetcher). **토글 여전히 기본 off = 행동 변화 0.** 실 A/B·Hiker 지연 벤치·dev/staging e2e·운영 점진 개통은 **별도 운영 단계**(이 계획 밖 — `-milestoneC.md` 개요 참조).
> 구현: branch `claude/optimistic-knuth-3212ef` 커밋 c3e4d1cc~5340419f. 운영 단계 이월: geo:kr 실A/B·og/wpi A/B·Hiker 지연 벤치·dev/staging e2e·점진 개통 + 라이브 개통 시 app_setting 매 콜 read에 짧은 TTL 캐시 검토 + og는 pk·최근12도 실을 수 있으나 이 표면 미채택(후속 최적화).
> 선행: 마일스톤 A·B 완료(커밋 8e9d5fdf~190cdc62). 설계 정본: spec §8-5·§8-6·§10.

# 인스타 수집 하이브리드 — 마일스톤 C 코드 선행분 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 자체크롤을 운영에서 안전하게 켤 수 있는 코드 기반을 마련한다 — (1) 개통 전 하드닝 3종(200 로그인벽 폴백우회·서킷 복구·403 서킷), (2) 런타임 토글/킬스위치(app_setting, 매 요청 재확인), (3) `(백엔드×표면×결과)` Micrometer 메트릭, (4) og 프로필 fetcher(og/wpi 표면 토글). **모두 토글 off 유지 → 런타임 동작 불변**, mock+실픽스처 단위 검증.

**Architecture:** 모듈(`instagram-source`)은 순수 JDK 유지 — 토글은 `BooleanSupplier`, 메트릭은 모듈 정의 순수 훅 인터페이스(monitoring이 Micrometer로 구현). monitoring에 app_setting 인프라(Flyway 테이블 + JdbcTemplate 리포 + 설정 서비스) 신설. og fetcher는 문서표면(프로필 페이지 raw HTML) 파서.

**Tech Stack:** Java 21, JUnit5+AssertJ, Jackson 3, JdbcTemplate(monitoring, JPA 없음), Micrometer(monitoring), Testcontainers(monitoring store 테스트).

---

## 검증된 핵심 사실 (착수 전 필독)

**app_setting = monitoring에 없음(신설).** crawler는 JPA(`AppSetting @Entity` + `AppSettingRepository extends JpaRepository`)로 하지만 **monitoring은 JPA 없음**(`spring-boot-starter-jdbc`만, 전 리포가 `JdbcTemplate`). → monitoring엔 JdbcTemplate 리포 신설. Flyway 디렉토리 `monitoring/src/main/resources/db/migration/`, 최신 `V20260828075917__brand_hashtag_post_migration.sql` → 다음은 `date -u +%Y%m%d%H%M%S`(> 그 번호). crawler DDL 참조: `CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL);`. 기준값 시드는 같은 마이그레이션에서 `ON CONFLICT DO NOTHING`(CLAUDE.md — 07-20 사고 후 확립).

**Micrometer(monitoring).** `TimedHikerHttp`가 `external.call` 타이머에 태그 `api`(="hiker")·`operation`·`outcome`(ok|4xx|5xx|error)로 기록. `Timer.builder(METRIC).tag(...).register(registry).record(Duration)`, 기록 실패는 try/catch 삼킴. `MeterRegistry`는 빈(actuator+prometheus), `HikerConfig.instagramSource(...)`에 이미 주입됨. yml `management.metrics.distribution`에 `external.call` 히스토그램·상한 120s 존재.

**FailoverInstagramSource(현재).** 필드 `InstagramSource self`·`InstagramSource hiker`·**`final boolean selfEnabled`**. `route(selfCall, hikerCall)`: `!selfEnabled||self==null`→hiker / self 시도 / UnsupportedOperationException→hiker / SelfCrawlException NOT_FOUND→SubjectNotFoundException, 그 외→hiker. **런타임 토글엔 boolean을 `BooleanSupplier`로 바꿔 매 콜 재확인** 필요(빈은 싱글턴, 부팅 시 1회 고정이라 지금은 재시작 없이 못 바꿈).

**모듈 build.gradle:** slf4j-api + jackson-databind만. **Micrometer 없음** → 메트릭 훅은 모듈 정의 순수 인터페이스, monitoring이 구현.

**og 프로필 = raw HTTP 파싱 가능(fixture 확보).** `https://www.instagram.com/{username}/` nav 헤더(x-ig-app-id 없음). 실측(nasa, fixture `og_profile.html`): `"follower_count":104434301`(정확)·`"following_count":91`·og:description `104M Followers, 95 Following, 4,900 Posts`(mediaCount는 여기 "4,900 Posts"에서 — JSON media_count는 null)·`"full_name":"NASA"`·`"is_verified":true`·`"is_private":false`·`"biography":"..."`·`"username":"nasa"`·`"profile_pic_url"`. ⚠️**userId=null·external_url 부재·recent-posts 부재**(edge_owner_to_timeline_media 없음) → **og는 프로필 통계 전용, 최근12·userId는 wpi가 정본**. 삭제/비공개는 302/HTML 벽 → NOT_FOUND/LOGIN_WALL.

**하드닝 3종(B 최종 리뷰):** ①**200 로그인벽 HTML이 wpi/comments의 `MAPPER.readTree`를 직격→비-SelfCrawlException으로 폴백망 우회**(classifier의 LOGIN_WALL-on-200 분기는 현재 dead — 아무도 200에 body 넘겨 호출 안 함). ②**서킷 트립 후 복구경로 없음**(guard가 recordSuccess 전 throw, reset/killAll 호출자 없음). ③**403/OTHER 서킷 미계상**.

---

## 스코핑 (이 계획 = 코드만)

- **포함**: 하드닝 3 · 런타임 토글/킬스위치(app_setting) · 메트릭 훅 · og fetcher + 표면 토글. 전부 mock+실픽스처 단위 검증, **토글 기본 off**.
- **제외(별도 운영 단계)**: geo:kr 실엔드포인트 A/B · og/wpi A/B 실측 · Hiker 지연 벤치 · dev/staging e2e · **운영 점진 개통**. 이건 실 프록시·구동 환경·사람 게이트 필요.
- **행동 변화 0 게이트**: `:monitoring:test`(783 + α) 전부 통과. 토글 off라 self 경로 미실행.

---

## File Structure

**모듈 변경/신설(`com.celfit.instagram.source[.self]`):**
- `self/SelfErrorClass.java` — `FORBIDDEN_403` 추가
- `self/SelfErrorClassifier.java` — 403→FORBIDDEN_403
- `self/WpiProfileFetcher.java`·`self/DirectCommentFetcher.java` — 200-로그인벽 분류 + 파스 예외 래핑(H1)
- `self/SurfaceCircuitBreaker.java` — 시간기반 복구(half-open) + clock 주입(H2)
- `self/SelfCrawlBackend.java` — recordIfBlock에 FORBIDDEN_403 계상(H3) + og 표면 선택
- `self/OgProfileFetcher.java` — 신설(og 프로필 파서)
- `InstagramSourceMetrics.java` — 신설(순수 메트릭 훅 인터페이스)
- `FailoverInstagramSource.java` — `BooleanSupplier selfEnabled` + 메트릭 훅

**monitoring 신설/변경:**
- `store/AppSettingRepository.java` — 신설(JdbcTemplate: 키로 값 읽기·upsert)
- `hiker/IgSourceSettings.java`(또는 config) — 신설(토글·킬스위치·표면 읽기 서비스)
- `hiker/MicrometerInstagramSourceMetrics.java` — 신설(메트릭 훅 구현)
- `db/migration/V<UTC>__app_setting.sql` — 신설(테이블 + 토글 기준값 시드)
- `config/HikerConfig.java` — 토글 supplier·메트릭·og 배선

**빌드/테스트:** 모듈 `./gradlew :instagram-source:test`; monitoring `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test`.

---

## Task 1 (H1): 200 로그인벽·파스 예외 하드닝

**Files:** Modify `self/WpiProfileFetcher.java`, `self/DirectCommentFetcher.java`; Tests 확장.

문제: 두 fetcher가 200일 때 `MAPPER.readTree(body)`를 곧장 호출 → 200 로그인벽 HTML이면 Jackson이 비-SelfCrawlException을 던져 폴백망 우회. 해결: 200이어도 `SelfErrorClassifier.ofStatus(200, body)`로 먼저 분류해 LOGIN_WALL이면 `SelfCrawlException(LOGIN_WALL)`, 그리고 `readTree`를 try/catch로 감싸 파스 실패를 `SelfCrawlException(LOGIN_WALL)`(또는 OTHER)로 변환.

- [x] **Step 1: 실패 테스트** — 각 fetcher에: (a) `SelfResponse(200, "<!DOCTYPE html>...login...")` → `SelfCrawlException(LOGIN_WALL)`; (b) `SelfResponse(200, "not json{{{")` → `SelfCrawlException`(비-Jackson). 기존 정상 픽스처 테스트는 그대로 통과.
- [x] **Step 2: 구현** — 두 fetcher의 파싱 진입부에:
```java
SelfErrorClass ec = SelfErrorClassifier.ofStatus(res.status(), res.body());
if (ec != SelfErrorClass.OK) {
    throw new SelfCrawlException(ec, "자체 <표면> 실패 status=" + res.status());
}
JsonNode root;
try {
    root = MAPPER.readTree(res.body());
} catch (RuntimeException e) {   // tools.jackson.core.JacksonException 등
    throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL, "자체 <표면> JSON 파스 실패(로그인벽 의심)", e);
}
```
(현재 `status != 200`만 분기하던 걸 `ofStatus`가 200까지 보게 바꾼다. LOGIN_WALL-on-200 분기가 이제 도달 가능해진다.)
- [x] **Step 3: 통과 + 커밋** `fix(instagram-source): 200 로그인벽·파스 예외를 SelfCrawlException으로 - 폴백망 우회 차단(개통 하드닝)`.

---

## Task 2 (H3): 403 하드블록 → 서킷 계상

**Files:** Modify `self/SelfErrorClass.java`, `self/SelfErrorClassifier.java`, `self/SelfCrawlBackend.java`; Tests.

- [x] **Step 1: 실패 테스트** — `ofStatus(403,"")` → `FORBIDDEN_403`; SelfCrawlBackend에서 403이 5회 연속이면 그 표면 서킷 트립(SelfRetry는 403 재시도 안 함).
- [x] **Step 2: 구현** —
  - `SelfErrorClass`에 `FORBIDDEN_403` 추가(javadoc: doc_id 만료·IP 하드블록, 재시도 무의미, 서킷 계상).
  - `SelfErrorClassifier.ofStatus`: `case 403 -> SelfErrorClass.FORBIDDEN_403;`.
  - `SelfRetry.recoverable`: 403 미포함(재시도 안 함) — 변경 없음(FORBIDDEN_403은 recoverable 집합 밖).
  - `SelfCrawlBackend.recordIfBlock`: block 집합에 `FORBIDDEN_403` 추가 → `case RECOVERABLE_401, RATE_LIMIT_429, TRANSPORT, LOGIN_WALL, FORBIDDEN_403 -> circuit.recordBlock(surface);`.
  - `FailoverInstagramSource.route`: FORBIDDEN_403은 NOT_FOUND 아니므로 기존 "그 외 → hiker"로 폴백(변경 없음).
- [x] **Step 3: 통과 + 커밋** `feat(instagram-source): 403 하드블록 분류 FORBIDDEN_403 - 서킷 계상(지속 차단 시 self 스킵)`.

---

## Task 3 (H2): 서킷 시간기반 복구(half-open)

**Files:** Modify `self/SurfaceCircuitBreaker.java`; Test 확장.

트립 후 재시작 전까지 죽는 문제 → 쿨다운 경과 후 half-open(프로브 1회 허용). 테스트 위해 clock 주입.

- [x] **Step 1: 실패 테스트** — fake clock(`LongSupplier`)로: 5블록 트립 → isOpen true; 쿨다운(예 60_000ms) 경과 전 계속 true; 경과 후 `isOpen`이 **한 번** false(half-open 프로브 허용) → recordSuccess면 완전 리셋 / recordBlock이면 재트립·쿨다운 재시작.
- [x] **Step 2: 구현** — 생성자에 `long cooldownMillis`와 `LongSupplier clock`(기본 생성자는 `System::currentTimeMillis`, 쿨다운 기본 60_000) 추가. 필드 `volatile long trippedAt`. `recordBlock`이 임계 도달 시 `trippedAt=clock`; `isOpen(surface)`:
```java
if (killed) return true;
if (counter(surface).get() < threshold) return false;
// 트립 상태 — 쿨다운 경과면 half-open(프로브 허용)
if (clock.getAsLong() - trippedAt(surface) >= cooldownMillis) {
    return false;   // 프로브 1회 통과(성공 시 recordSuccess가 리셋, 실패 시 recordBlock이 재트립)
}
return true;
```
표면별 `trippedAt`이 필요하므로 `ConcurrentHashMap<String, AtomicLong>`로 관리(streak과 병렬). recordSuccess는 streak=0 + trippedAt=0. 기존 `reset()`/`killAll()` 유지. **기존 2-arg 생성자(`SurfaceCircuitBreaker(int threshold)`)는 유지**(cooldown 기본·clock 기본)로 기존 호출부 무변경.
- [x] **Step 3: 통과 + 커밋** `fix(instagram-source): 서킷 시간기반 복구(half-open) - 트립 후 쿨다운 경과 시 프로브 허용`.

---

## Task 4 (T1): monitoring app_setting 인프라

**Files:** Create `monitoring/.../db/migration/V<UTC>__app_setting.sql`, `monitoring/.../store/AppSettingRepository.java`; Test.

- [x] **Step 1: Flyway 마이그레이션** — `date -u +%Y%m%d%H%M%S`로 채번(> 20260828075917). `V<UTC>__ig_source_app_setting.sql`:
```sql
CREATE TABLE app_setting (
    key   text PRIMARY KEY,
    value text NOT NULL
);
-- 자체크롤 런타임 토글 기준값(개통 전 전량 off = 행동 변화 0)
INSERT INTO app_setting (key, value) VALUES
    ('ig-source.self-enabled', 'false'),
    ('ig-source.force-hiker', 'false'),
    ('ig-source.profile-surface', 'wpi')
ON CONFLICT (key) DO NOTHING;
```
- [x] **Step 2: 실패 테스트** — `monitoring/src/test/.../store/AppSettingRepositoryTest.java`(TestDb Testcontainers 패턴): 시드된 `ig-source.self-enabled`=false 읽기, upsert 후 재읽기.
- [x] **Step 3: 구현** — `AppSettingRepository`(JdbcTemplate, monitoring 리포 관용구):
```java
package com.celfit.monitoring.store;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 런타임 설정 key-value(app_setting). monitoring은 JPA 없음 — JdbcTemplate. */
@Repository
public class AppSettingRepository {

	private final JdbcTemplate db;

	public AppSettingRepository(JdbcTemplate db) {
		this.db = db;
	}

	public Optional<String> find(String key) {
		try {
			return Optional.ofNullable(
					db.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, key));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	public void upsert(String key, String value) {
		db.update("""
				INSERT INTO app_setting (key, value) VALUES (?, ?)
				ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
				""", key, value);
	}
}
```
- [x] **Step 4: 게이트(Testcontainers)** — `export DOCKER_HOST=... && ./gradlew :monitoring:test --tests "*AppSettingRepositoryTest"`. **Step 5: 커밋** `feat(monitoring): app_setting 인프라 - Flyway 테이블+토글 시드+JdbcTemplate 리포(UTC 채번)`.

---

## Task 5 (T2): 런타임 토글/킬스위치 + Failover BooleanSupplier

**Files:** Modify `FailoverInstagramSource.java`(모듈); Create `monitoring/.../hiker/IgSourceSettings.java`; Modify `HikerConfig.java`; Tests.

- [x] **Step 1: 모듈 — FailoverInstagramSource `boolean`→`BooleanSupplier`** — 필드 `private final java.util.function.BooleanSupplier selfEnabled;`. 3-arg 생성자 시그니처를 `(InstagramSource self, InstagramSource hiker, BooleanSupplier selfEnabled)`로. 1-arg 유지: `this(null, hiker, () -> false)`. `route`의 `if (!selfEnabled || self == null)` → `if (self == null || !selfEnabled.getAsBoolean())`(매 콜 재확인). **기존 A/B 테스트가 3-arg에 `true`/`false` boolean을 넘기면** `() -> true`/`() -> false`로 바꿔야 하니, 해당 테스트(`FailoverInstagramSourcePolicyTest`)의 생성자 인자를 supplier로 갱신(단언 무변경).
- [x] **Step 2: monitoring — IgSourceSettings** — app_setting에서 매 콜 읽어 토글 판정:
```java
package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.AppSettingRepository;
import org.springframework.stereotype.Service;

/** 자체크롤 런타임 토글. self-enabled AND NOT force-hiker일 때만 자체 1순위. 매 콜 app_setting 재확인
 *  (킬스위치 즉시 반영). 키 부재/이상값은 안전측(false=Hiker). */
@Service
public class IgSourceSettings {

	private final AppSettingRepository settings;

	public IgSourceSettings(AppSettingRepository settings) {
		this.settings = settings;
	}

	/** 자체 경로를 탈지 — force-hiker가 켜져 있으면 무조건 false(킬스위치). */
	public boolean selfEnabled() {
		if (bool("ig-source.force-hiker", false)) {
			return false;
		}
		return bool("ig-source.self-enabled", false);
	}

	/** 프로필 표면 — "og" 또는 "wpi"(기본 wpi). */
	public String profileSurface() {
		return settings.find("ig-source.profile-surface").filter(v -> !v.isBlank()).orElse("wpi");
	}

	private boolean bool(String key, boolean dflt) {
		return settings.find(key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(dflt);
	}
}
```
- [x] **Step 3: HikerConfig 배선** — `IgSourceSettings igSettings`를 빈 파라미터로 주입, `new FailoverInstagramSource(self, hikerBackend, igSettings::selfEnabled)`. (표면 토글은 Task 7에서 SelfCrawlBackend에 연결.)
- [x] **Step 4: 테스트** — 모듈: BooleanSupplier가 매 콜 호출됨(supplier가 false→hiker, 도중 true로 바뀌면 다음 콜 self). monitoring: IgSourceSettings가 app_setting force-hiker=true면 self-enabled=true여도 false(킬스위치) — TestDb로.
- [x] **Step 5: 게이트 + 커밋** `feat: 자체크롤 런타임 토글·킬스위치 - app_setting 매 콜 재확인(Failover BooleanSupplier)`.

---

## Task 6 (M1): Micrometer 메트릭 훅

**Files:** Create `InstagramSourceMetrics.java`(모듈); Modify `FailoverInstagramSource.java`; Create `monitoring/.../hiker/MicrometerInstagramSourceMetrics.java`; Modify `HikerConfig.java`; Tests.

- [x] **Step 1: 모듈 메트릭 훅 인터페이스** — 순수(Micrometer 무관):
```java
package com.celfit.instagram.source;

/** 수집 결과 관측 훅 — 모듈은 Micrometer를 모르고, monitoring이 이를 구현해 external.call에 기록.
 *  outcome 예: self-ok / self-fallback / self-notfound / hiker-ok. path=논리 경로(fetchPost 등). */
@FunctionalInterface
public interface InstagramSourceMetrics {
	void record(String path, String backend, String outcome);

	/** 무기록 기본(테스트·미배선). */
	InstagramSourceMetrics NOOP = (p, b, o) -> {};
}
```
- [x] **Step 2: Failover에 훅 배선** — FailoverInstagramSource가 `InstagramSourceMetrics metrics`(기본 NOOP)를 받고, 각 메서드가 논리 경로명을 route에 넘겨 결과를 기록. `route(String path, Supplier self, Supplier hiker)`로 시그니처 확장:
```java
private <T> T route(String path, Supplier<T> selfCall, Supplier<T> hikerCall) {
	if (self == null || !selfEnabled.getAsBoolean()) {
		T r = hikerCall.get();
		metrics.record(path, "hiker", "ok");
		return r;
	}
	try {
		T r = selfCall.get();
		metrics.record(path, "self", "ok");
		return r;
	} catch (UnsupportedOperationException e) {
		T r = hikerCall.get();
		metrics.record(path, "hiker", "hardgate");
		return r;
	} catch (SelfCrawlException e) {
		if (e.errorClass() == SelfErrorClass.NOT_FOUND) {
			metrics.record(path, "self", "notfound");
			throw new SubjectNotFoundException(e.getMessage());
		}
		T r = hikerCall.get();
		metrics.record(path, "hiker", "fallback:" + e.errorClass());
		return r;
	}
}
```
각 메서드: `return route("fetchPost", () -> self.fetchPost(shortCode), () -> hiker.fetchPost(shortCode));` 식. 생성자에 metrics 추가(오버로드로 기본 NOOP 유지해 A 테스트 호환). **주의:** hikerCall/hiker가 던지는 예외(SubjectNotFoundException 등)는 그대로 전파 — metrics.record는 성공 경로에만(예외 시 미기록 or catch로 error 기록, 단 과설계 금지 — 성공/폴백만 기록).
- [x] **Step 3: monitoring 구현** — `MicrometerInstagramSourceMetrics implements InstagramSourceMetrics`, `external.call` 타이머 대신 카운터(콜 수 관측이면 Counter, 지연은 이미 TimedHikerHttp가 잡음). TimedHikerHttp 관용구(태그 유한집합·try/catch 삼킴):
```java
Counter.builder("instagram.source.route")
    .tag("path", path).tag("backend", backend).tag("outcome", outcome)
    .register(registry).increment();
```
(태그 카디널리티: path=논리 경로 유한집합, backend=self|hiker, outcome=ok|hardgate|notfound|fallback:<class>. fallback:<class>는 SelfErrorClass 유한집합이라 안전.) yml에 노출 필요 시 확인 — Counter는 prometheus에 자동 노출.
- [x] **Step 4: HikerConfig 배선** — `MeterRegistry`로 `new MicrometerInstagramSourceMetrics(meterRegistry)` 만들어 FailoverInstagramSource에 주입.
- [x] **Step 5: 테스트 + 게이트 + 커밋** `feat: 자체크롤 수집 메트릭 - (path×backend×outcome) 관측 훅(모듈 순수 인터페이스+monitoring Micrometer)`.

---

## Task 7 (O1): OgProfileFetcher + 프로필 표면 토글

**Files:** Create `self/OgProfileFetcher.java`; Modify `self/SelfCrawlBackend.java`; Modify `HikerConfig.java`; Tests. Fixture `og_profile.html`(커밋됨).

- [x] **Step 1: 실패 테스트** — fixture로 `OgProfileFetcher.fetchProfile("nasa")` → ProfileInfo: username=nasa, followers=104434301L, following=91L, mediaCount=4900L(og:description "4,900 Posts"), fullName="NASA", isVerified=true, userId=null, biography contains "Making the seemingly". 302→NOT_FOUND. (fixture 실측값으로 채운다.)
- [x] **Step 2: 구현** — `OgProfileFetcher(EmbedPostFetcher.SelfFetch fetch)`. URL `https://www.instagram.com/{enc(username)}/`, nav 헤더(Accept text/html·Sec-Fetch navigate·Upgrade-Insecure-Requests, x-ig-app-id **없음**), ProxyTier.RESIDENTIAL. 3xx→NOT_FOUND, 200이면 `ofStatus`로 LOGIN_WALL 체크(H1과 동일 방어). 파싱: `"follower_count":(\d+)`·`"following_count":(\d+)`·og:description `([\d,]+) Posts`→mediaCount·`"full_name":"([^"]*)"`·`"is_verified":(true|false)`·`"is_private"`·`"biography":"..."`(HTML/유니코드 이스케이프 해제)·`"profile_pic_url":"([^"]*)"`. userId=null, externalUrl=null(부재). ProfileInfo 10필드 순서 준수. **fixture 읽어 정규식 확정**.
- [x] **Step 3: 표면 토글** — SelfCrawlBackend.fetchProfile이 og/wpi를 고르게: 생성자에 `OgProfileFetcher og`와 `Supplier<String> profileSurface` 추가(기본 "wpi"), `fetchProfile`:
```java
@Override
public ProfileInfo fetchProfile(String username) {
	if ("og".equals(profileSurface.get())) {
		return run("og", () -> og.fetchProfile(username));
	}
	return run("wpi", () -> wpi.fetchProfile(username));
}
```
`fetchRecentPosts`는 wpi 유지(og는 recent 없음). 기존 SelfCrawlBackend 생성자 호출부(HikerConfig)는 og·profileSurface 인자 추가. profileSurface = `igSettings::profileSurface`.
- [x] **Step 4: HikerConfig 배선** — `new OgProfileFetcher(httpClient::get)` + `igSettings::profileSurface`를 SelfCrawlBackend에 추가.
- [x] **Step 5: 테스트 + 게이트 + 커밋** `feat(instagram-source): og 프로필 fetcher + 표면 토글(og/wpi) - 문서표면 통계, 기본 wpi`.

---

## Task 8: 통합 회귀 + 자기검토 + 아카이브

- [x] **Step 1: 모듈 경계** — `grep -rnE 'org\.springframework|javax\.sql|java\.sql|jakarta' instagram-source/src/main` → 0(순수 유지 — 메트릭 훅·BooleanSupplier·og 전부 순수 JDK).
- [x] **Step 2: FULL 회귀** — `export DOCKER_HOST=... && ./gradlew :instagram-source:test :monitoring:test`. monitoring 전부 통과(app_setting 시드 self-enabled=false·force-hiker=false·profile-surface=wpi → **self 경로 미실행, 행동 변화 0**). 모듈 신규 테스트 통과.
- [x] **Step 3: 토글 off 확인** — 시드값이 self off임을 마이그레이션에서 재확인. FailoverInstagramSource가 selfEnabled.getAsBoolean()=false로 hiker 위임하는지 통합 확인.
- [x] **Step 4: 자기검토** — 하드닝 3종 커버(H1 200벽·H2 서킷복구·H3 403), 토글/킬스위치/표면 app_setting 3키, 메트릭 훅, og fetcher. 플레이스홀더 스캔(og `extract*`·정규식 실구현). 타입 일관성(BooleanSupplier·route 시그니처·InstagramSourceMetrics).
- [x] **Step 5: 아카이브** — 이 문서를 `plans/archive/`로(상태 ✅). 커밋.

---

## 완료 기준(DoD)

- [x] 하드닝 3종 구현·테스트: 200 로그인벽→SelfCrawlException(폴백 정상), 서킷 시간기반 복구, 403→FORBIDDEN_403 서킷 계상.
- [x] app_setting 인프라(Flyway 테이블+시드+JdbcTemplate 리포) + IgSourceSettings(토글·킬스위치·표면, 매 콜 재확인).
- [x] FailoverInstagramSource `BooleanSupplier` + InstagramSourceMetrics 훅. monitoring이 Micrometer로 구현.
- [x] OgProfileFetcher + og/wpi 표면 토글(기본 wpi).
- [x] `:monitoring:test` 전부 통과(**시드 토글 off = 행동 변화 0**). 모듈 순수 유지.
- [x] 실 A/B·벤치·e2e·운영 개통은 **별도 운영 단계**(이 계획 밖).

**PR·배포·운영 개통·토글 ON은 이 계획 범위 밖** — push까지만, 개통은 운영 단계에서 사람 게이트.
