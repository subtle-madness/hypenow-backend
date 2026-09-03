# 브랜드 해시태그 자동 시드 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 등록 시 심는 자동 해시태그를 "계정명 문자열 절삭"에서 "그 브랜드에 태그된 게시물 캡션의 해시태그 빈도(+ 임계 미만이면 brandName 기반 AI 폴백 1개)"로 바꾸고, was에 복제돼 있던 유도 규칙을 삭제해 유도 규칙을 monitoring 단일 소유로 되돌린다.

**Architecture:** monitoring이 유일한 태그 유도 주체다. 신규 클래스 4개(`HashtagCandidateExtractor` 순수 집계 / `BrandHashtagSuggester` LLM 폴백 / `BrandHashtagSeedSettings` app_setting TTL 캐시 / `BrandHashtagSeedService` 오케스트레이션)가 각각 책임 하나를 갖고, `BrandRegistrationService`는 두 지점(신규 등록의 백필 꼬리 · replay 재등록 동기 구간)에서 `seedIfEmpty`를 부른다. LLM 전송은 광고 표기 판정과 같은 `GeminiHttp` 빈을 재사용한다(새 HTTP 클라이언트 없음). was는 유도 규칙을 잃고, 대신 조회 시 장부가 비어 있으면 기존 `ensureSeeded`(무주 태그 승계)로 monitoring 태그를 물려받는다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(monitoring / was) · JdbcTemplate(monitoring) · JdbcClient(was) · Jackson 3(`tools.jackson.*`) · Micrometer · Flyway(UTC 타임스탬프 채번) · JUnit 5 + AssertJ + Mockito · Testcontainers(PostgreSQL)

---

> 상태: 🟢 활성 · 구현 미착수 (2026-09-03)
>
> 정본 설계: [2026-09-03 브랜드 해시태그 자동 시드 재설계](../specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md)

## 사전 준비 (모든 Task 공통)

통합 테스트(Testcontainers)를 도는 Task 3·Task 7은 셸에 아래가 **반드시** export돼 있어야 한다.
없으면 컨테이너 초기화가 깨져 무관한 테스트가 무더기로 실패한다(테스트 결함으로 오진하기 쉬운 양상).

```
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```

작업 위치는 worktree `/Users/woomin/Project/hypenow-backend/.worktrees/hashtag-auto-seed`(브랜치
`docs/brand-hashtag-auto-seed-spec`)다. 모든 git 명령은 `git -C <이 경로>`로 실행한다.

## 코드 조사에서 확인된 사실 (계획의 전제)

spec 작성 시점 이후 코드가 이미 움직여 있어, spec 본문의 일부 표현은 현재 코드와 이름이 다르다.
**설계 결정은 그대로 따르되** 아래 사실에 맞춰 구현한다.

| spec 표현 | 현재 코드 실체 |
|---|---|
| monitoring `BrandHashtagTags.derive` 삭제 | 2026-08-28에 **이미 삭제됨**. 남은 건 `isValidTag`뿐이고 `BrandController:361`이 유저 입력 검증에 쓴다 — 유지. |
| monitoring `BrandHashtagTagsTest` 제거 | **이미 없음**(파일 자체가 없다). 새로 만들지 않는다. |
| `BrandRegistrationService.seedHashtagsSafely` 자리 | 그 메서드도 **이미 삭제됨**. replay 분기(`register` 168행 `triggerHashtagSweep(existing.get())`) 바로 **앞**이 그 자리다. |
| `brand_hashtag` 삽입에 `insertTags` 재사용 | 실제 메서드명은 `BrandHashtagRepository.addTags(long, Collection<String>)`(tombstone 재활성 UPSERT). 시드 시점엔 `countAll == 0`이라 충돌이 불가능해 의미는 동일하다. |
| was `BrandHashtagPostAssembler`의 장부 읽기 지점 | 그 클래스는 장부를 읽지 않는다. 실제 읽기 지점은 `BrandPostAssembler:225`·`BrandPostAssembler:504`이고 이 클래스에는 `MonitoringCommandClient`도 username도 없다 → **승계는 서비스/컨트롤러 층으로 올린다**(Task 8). |

## 파일 구조

### 생성

| 파일 | 책임 |
|---|---|
| `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_seed_settings.sql` | 설정 키 3종 시드(min-posts·stoplist·ai-enabled) |
| `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedSettings.java` | 위 3키의 app_setting TTL(5초) 캐시 읽기 |
| `monitoring/src/main/java/com/celfit/monitoring/service/HashtagCandidateExtractor.java` | 순수 함수 — 캡션 목록 → 정렬된 태그 후보 |
| `monitoring/src/main/java/com/celfit/monitoring/llm/BrandHashtagSuggester.java` | AI 폴백 — brandName → 검증된 태그 1개 |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSeedService.java` | 오케스트레이션(실행 조건·집계·임계·AI·저장·로그·지표) |
| `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedConfig.java` | 위 두 클래스의 빈 조립 |
| `monitoring/src/test/java/com/celfit/monitoring/config/BrandHashtagSeedSettingsTest.java` | 설정 캐시 단위 테스트 |
| `monitoring/src/test/java/com/celfit/monitoring/service/HashtagCandidateExtractorTest.java` | 추출·dedup·제외·정렬 단위 테스트 |
| `monitoring/src/test/java/com/celfit/monitoring/llm/BrandHashtagSuggesterTest.java` | AI 요청·파싱·검증 단위 테스트(GeminiHttp fake) |
| `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagSeedServiceTest.java` | 시드 판정 전 분기 단위 테스트 |
| `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagSeedQueryTest.java` | `countAll`·`findCaptionsForSeed` 통합(Testcontainers) 테스트 |

### 수정

| 파일 | 변경 |
|---|---|
| `monitoring/.../store/BrandHashtagRepository.java` | `countAll(long)` 추가(tombstone 포함) |
| `monitoring/.../store/TaggedPostRepository.java` | `TaggedCaption` record + `findCaptionsForSeed(long)` 추가 |
| `monitoring/.../service/BrandRegistrationService.java` | 생성자에 `BrandHashtagSeedService` 추가, `runBackfillSafely(BrandRow, String)`·`expandIfRequested(BrandRow, int, String)` 시그니처 변경, replay 분기 동기 시드 |
| `monitoring/src/test/.../service/BrandRegistrationServiceTest.java` | `StubHashtagSeed` 추가 + 신규 검증 5건 + 기존 2건 javadoc 갱신 |
| `was/.../v1/brandmonitoring/V1BrandAccountService.java` | `seedLedgerTagsSafely` 및 호출 삭제, `ensureLedgerSeededSafely` 신설, `getHashtagTags` 승계 |
| `was/.../v1/brandmonitoring/BrandCaptionHashtags.java` | 삭제되는 `BrandHashtagTags`로의 javadoc 링크 정리 |
| `was/.../v1/brandmonitoring/V1BrandPostsController.java` | 생성자에 `V1BrandAccountService` 추가, `hashtagPosts`·`hashtagPostCount`에서 승계 호출 |
| `was/src/test/.../v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java` | 등록 시딩 기대 3건 삭제, 조회 승계 기대 3건 추가 |
| `was/src/test/.../v1/brandmonitoring/V1BrandAccountsControllerTest.java` | 등록 시딩 push 기대 갱신 |
| `was/src/test/.../v1/brandmonitoring/V1BrandPostsControllerTest.java` | `V1BrandAccountService` @MockitoBean 추가 |
| `DECISIONS.md` | 맨 위에 결정 1행 |
| `docs/tracks/MON-BT-브랜드-태그-모니터링.md` | 트랙 상태 + 운영 정리·재시드 절차(SQL 포함) |

### 삭제

| 파일 | 사유 |
|---|---|
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java` | monitoring 규칙의 복제본 — 유도 규칙 was 소멸 |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java` | 위 클래스 전용 테스트 |

---

## Task 1 — 설정 키 시드와 TTL 캐시 (`BrandHashtagSeedSettings`)

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_seed_settings.sql`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedSettings.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/config/BrandHashtagSeedSettingsTest.java`

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/config/BrandHashtagSeedSettingsTest.java`를 만든다.

```java
package com.celfit.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 자동 시드 설정 TTL 캐시 — {@code IgSourceSettings}와 같은 관용구(짧은 TTL·이상값 안전측·조회
 * 실패 시 직전 캐시 유지)를 세 키(min-posts·stoplist·ai-enabled)에 대해 고정한다.
 */
class BrandHashtagSeedSettingsTest {

	/** app_setting 스텁 — 조회 횟수를 세서 TTL 캐시 적중을 관측한다. */
	private static final class StubSettings extends AppSettingRepository {
		final Map<String, String> values = new HashMap<>();
		int reads;
		boolean failing;

		StubSettings() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			if (failing) {
				throw new IllegalStateException("DB 장애 주입");
			}
			reads++;
			return Optional.ofNullable(values.get(key));
		}
	}

	/** 수동으로 흘릴 수 있는 시계 — TTL 만료를 결정적으로 재현한다. */
	private static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-09-03T00:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final StubSettings store = new StubSettings();
	private final MutableClock clock = new MutableClock();

	private BrandHashtagSeedSettings settings() {
		return new BrandHashtagSeedSettings(store, clock, Duration.ofSeconds(5));
	}

	@Test
	void 키가_없으면_기본값이다() {
		var s = settings();

		assertThat(s.minPosts()).isEqualTo(7);
		assertThat(s.aiEnabled()).isTrue();
		assertThat(s.stoplist()).contains("광고", "협찬", "sponsored");
	}

	@Test
	void 설정된_값을_읽는다() {
		store.values.put("brand.hashtag-seed.min-posts", "3");
		store.values.put("brand.hashtag-seed.ai-enabled", "false");
		store.values.put("brand.hashtag-seed.stoplist", "가,나");

		var s = settings();

		assertThat(s.minPosts()).isEqualTo(3);
		assertThat(s.aiEnabled()).isFalse();
		assertThat(s.stoplist()).containsExactlyInAnyOrder("가", "나");
	}

	@Test
	void 숫자가_아닌_min_posts는_기본값으로_접힌다() {
		store.values.put("brand.hashtag-seed.min-posts", "일곱");

		assertThat(settings().minPosts()).isEqualTo(7);
	}

	@Test
	void 영이하_min_posts는_기본값으로_접힌다() {
		store.values.put("brand.hashtag-seed.min-posts", "0");

		assertThat(settings().minPosts()).isEqualTo(7);
	}

	@Test
	void stoplist는_트림_소문자_빈토큰제거로_파싱된다() {
		store.values.put("brand.hashtag-seed.stoplist", " AD , ,협찬 ,");

		assertThat(settings().stoplist()).containsExactlyInAnyOrder("ad", "협찬");
	}

	@Test
	void 빈_stoplist는_빈_집합이다() {
		store.values.put("brand.hashtag-seed.stoplist", "  ");

		assertThat(settings().stoplist()).isEmpty();
	}

	@Test
	void TTL_안에서는_재조회하지_않는다() {
		var s = settings();
		s.minPosts();
		int afterFirst = store.reads;

		s.minPosts();
		s.aiEnabled();
		s.stoplist();

		assertThat(store.reads).isEqualTo(afterFirst);
	}

	@Test
	void TTL이_지나면_재조회한다() {
		var s = settings();
		s.minPosts();
		int afterFirst = store.reads;
		store.values.put("brand.hashtag-seed.min-posts", "3");

		clock.now = clock.now.plusSeconds(6);

		assertThat(s.minPosts()).isEqualTo(3);
		assertThat(store.reads).isGreaterThan(afterFirst);
	}

	@Test
	void 조회_실패는_직전_캐시를_유지한다() {
		store.values.put("brand.hashtag-seed.min-posts", "3");
		var s = settings();
		assertThat(s.minPosts()).isEqualTo(3);

		store.failing = true;
		clock.now = clock.now.plusSeconds(6);

		assertThat(s.minPosts()).isEqualTo(3);
	}

	@Test
	void 캐시가_없는데_조회에_실패하면_기본값이다() {
		store.failing = true;

		assertThat(settings().minPosts()).isEqualTo(7);
		assertThat(settings().aiEnabled()).isTrue();
	}
}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.config.BrandHashtagSeedSettingsTest"` 가 컴파일 실패(클래스 없음)로 끝나는 것을 확인한다.

- [ ] **최소 구현** — `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedSettings.java`를 만든다.

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 브랜드 해시태그 자동 시드 런타임 설정(2026-09-03 자동 시드 재설계 §3-5) — app_setting을 짧은
 * TTL(기본 5초)로 캐시한다. {@code IgSourceSettings}와 같은 관용구다: 키 부재·이상값은 기본값으로
 * 접고, 조회가 실패하면(DB 장애) 직전 캐시를 유지하며 캐시가 아예 없으면 기본값으로 fail-safe한다 —
 * 설정 조회 예외가 등록·백필 흐름을 깨뜨리지 않게 한다.
 *
 * <p>세 키의 기본값은 Flyway 시드({@code V…__brand_hashtag_seed_settings.sql})와 같은 값이다.
 * 여기 상수는 "마이그레이션 이전·행 삭제" 같은 예외 상태의 안전망이지 정본이 아니다 — 기준값
 * 변경은 후속 마이그레이션으로 한다(CLAUDE.md 규칙).
 */
@Component
public class BrandHashtagSeedSettings {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSeedSettings.class);
	private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

	static final String KEY_MIN_POSTS = "brand.hashtag-seed.min-posts";
	static final String KEY_STOPLIST = "brand.hashtag-seed.stoplist";
	static final String KEY_AI_ENABLED = "brand.hashtag-seed.ai-enabled";

	private static final int DEFAULT_MIN_POSTS = 7;
	private static final String DEFAULT_STOPLIST =
			"광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr";

	private final AppSettingRepository settings;
	private final Clock clock;
	private final Duration ttl;

	private volatile Snapshot cache;

	@Autowired
	public BrandHashtagSeedSettings(AppSettingRepository settings) {
		this(settings, Clock.systemUTC(), DEFAULT_TTL);
	}

	/** 테스트 전용 — clock/ttl을 결정적으로 제어한다(IgSourceSettings와 같은 구조). */
	BrandHashtagSeedSettings(AppSettingRepository settings, Clock clock, Duration ttl) {
		this.settings = settings;
		this.clock = clock;
		this.ttl = ttl;
	}

	/** 최다 태그 시드 임계(등장 게시물 수, 이 값 이상이면 시드). */
	public int minPosts() {
		return snapshot().minPosts();
	}

	/** 후보·AI 결과에서 제외할 태그(전부 소문자). */
	public Set<String> stoplist() {
		return snapshot().stoplist();
	}

	/** AI 폴백 킬 스위치 — false면 임계 미만일 때 0개로 끝낸다. */
	public boolean aiEnabled() {
		return snapshot().aiEnabled();
	}

	private synchronized Snapshot snapshot() {
		Instant now = clock.instant();
		Snapshot current = cache;
		if (current != null && now.isBefore(current.expiresAt())) {
			return current;
		}
		try {
			Snapshot fresh = load(now);
			cache = fresh;
			return fresh;
		} catch (RuntimeException e) {
			log.warn("자동 시드 설정 조회 실패 — 안전측 기본값으로 fail-safe: {}", e.toString());
			Snapshot fallback = current != null ? current.withExpiry(now.plus(ttl))
					: new Snapshot(DEFAULT_MIN_POSTS, parseStoplist(DEFAULT_STOPLIST), true, now.plus(ttl));
			cache = fallback;
			return fallback;
		}
	}

	private Snapshot load(Instant now) {
		int minPosts = settings.find(KEY_MIN_POSTS).map(BrandHashtagSeedSettings::parseMinPosts)
				.orElse(DEFAULT_MIN_POSTS);
		Set<String> stoplist = parseStoplist(settings.find(KEY_STOPLIST).orElse(DEFAULT_STOPLIST));
		boolean aiEnabled = settings.find(KEY_AI_ENABLED)
				.map(v -> "true".equalsIgnoreCase(v.trim())).orElse(true);
		return new Snapshot(minPosts, stoplist, aiEnabled, now.plus(ttl));
	}

	/** 숫자 아님·0 이하는 기본값 — 0 이하를 허용하면 후보 0건에도 시드가 나가 규칙이 무너진다. */
	private static int parseMinPosts(String raw) {
		try {
			int parsed = Integer.parseInt(raw.trim());
			if (parsed <= 0) {
				log.warn("{} 값이 0 이하다({}) — 기본값 {}로 접는다", KEY_MIN_POSTS, raw, DEFAULT_MIN_POSTS);
				return DEFAULT_MIN_POSTS;
			}
			return parsed;
		} catch (NumberFormatException e) {
			log.warn("{} 값이 숫자가 아니다({}) — 기본값 {}로 접는다", KEY_MIN_POSTS, raw, DEFAULT_MIN_POSTS);
			return DEFAULT_MIN_POSTS;
		}
	}

	/** 쉼표 구분 → 트림 → 소문자 → 빈 토큰 제거(IgSourceSettings.parseSelfPaths 동형). */
	private static Set<String> parseStoplist(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(raw.split(","))
				.map(token -> token.trim().toLowerCase(Locale.ROOT))
				.filter(token -> !token.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 캐시 스냅샷 — 3개 판정값 + 만료 시각. 성공 조회만 값을 갱신한다. */
	private record Snapshot(int minPosts, Set<String> stoplist, boolean aiEnabled, Instant expiresAt) {

		Snapshot withExpiry(Instant newExpiresAt) {
			return new Snapshot(minPosts, stoplist, aiEnabled, newExpiresAt);
		}
	}
}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.config.BrandHashtagSeedSettingsTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **마이그레이션 채번** — `date -u +%Y%m%d%H%M%S` 를 실행해 UTC 타임스탬프를 얻고(예: `20260903085356`), 그 값을 파일명에 쓴다. **반드시 UTC** — KST 채번은 미래 번호 선점으로 뒤따르는 정상 채번을 Flyway out-of-order 거부에 빠뜨린다.

- [ ] **마이그레이션 작성** — `monitoring/src/main/resources/db/migration/V<위에서 얻은 값>__brand_hashtag_seed_settings.sql`

```sql
-- 브랜드 해시태그 자동 시드 런타임 설정(2026-09-03 자동 시드 재설계 §3-5).
-- min-posts  : 태그된 게시물 캡션 집계에서 최다 태그의 "등장 게시물 수"가 이 값 이상이면 그 태그 1개를 시드.
-- stoplist   : 후보·AI 결과 양쪽에서 제외할 태그(쉼표 구분, 소문자 비교).
-- ai-enabled : 임계 미만일 때의 brandName 기반 AI 폴백 킬 스위치. 끄려면 SQL 한 줄:
--   UPDATE app_setting SET value = 'false' WHERE key = 'brand.hashtag-seed.ai-enabled';
-- (재배포 불필요 — BrandHashtagSeedSettings TTL 5초 이내 반영)
INSERT INTO app_setting (key, value) VALUES
    ('brand.hashtag-seed.min-posts', '7'),
    ('brand.hashtag-seed.stoplist', '광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr'),
    ('brand.hashtag-seed.ai-enabled', 'true')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **마이그레이션 적용 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.AppSettingRepositoryTest"` 로 Flyway 재생이 깨지지 않는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 브랜드 해시태그 자동 시드 설정 키와 TTL 캐시

app_setting 3키(min-posts·stoplist·ai-enabled)를 Flyway로 시드하고,
IgSourceSettings와 같은 5초 TTL 캐시로 읽는 BrandHashtagSeedSettings를 추가한다.
키 부재·이상값은 기본값으로 접고, 조회 실패는 직전 캐시 유지로 fail-safe한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 2 — 캡션 해시태그 후보 집계 (`HashtagCandidateExtractor`)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/HashtagCandidateExtractor.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/service/HashtagCandidateExtractorTest.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (record `TaggedCaption`만 — 쿼리는 Task 3)

> 순수 함수의 입력 타입을 저장소 record로 두는 이유: 같은 모양의 record를 service·store에 두 벌
> 만들면 매핑 보일러플레이트와 드리프트가 생긴다. monitoring의 service→store 의존은 기존 관용구다
> (`BrandRegistrationService`가 `BrandRow`를 직접 쓴다).

### Steps

- [ ] **입력 record 추가** — `TaggedPostRepository`의 `nthNewestHashtagTakenAt` 메서드 **바로 뒤**(현재 81행 다음)에 record를 넣는다. import에 `java.time.Instant`는 이미 있다.

```java
	/**
	 * 자동 시드 후보 집계 입력(2026-09-03 자동 시드 재설계 §3-2) — 태그된 게시물 1건의 캡션과 게시일.
	 * takenAt은 동률 태그의 tie-break(최근 우선)에만 쓰이므로 null이어도 집계는 성립한다.
	 */
	public record TaggedCaption(String caption, Instant takenAt) {
	}
```

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/service/HashtagCandidateExtractorTest.java`

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 태그된 게시물 캡션 → 해시태그 후보(2026-09-03 자동 시드 재설계 §3-2). 순수 함수라 결정성이
 * 계약이다 — 정렬(등장 게시물 수 desc → 최근 게시일 desc → 태그 사전순)까지 여기서 봉인한다.
 */
class HashtagCandidateExtractorTest {

	private static final Instant T1 = Instant.parse("2026-09-01T00:00:00Z");
	private static final Instant T2 = Instant.parse("2026-09-02T00:00:00Z");

	private static TaggedCaption post(String caption, Instant takenAt) {
		return new TaggedCaption(caption, takenAt);
	}

	@Test
	void 캡션에서_해시태그를_소문자로_추출한다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("오늘 #Cclime 좋아요", T1)), Set.of());

		assertThat(out).containsExactly(new HashtagCandidateExtractor.Candidate("cclime", 1, T1));
	}

	@Test
	void 한_게시물_안의_같은_태그_반복은_한_번만_센다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post("#끌리메 #끌리메 #끌리메", T1)), Set.of());

		assertThat(out).singleElement()
				.extracting(HashtagCandidateExtractor.Candidate::postCount).isEqualTo(1);
	}

	@Test
	void 대소문자만_다른_태그는_같은_후보로_합쳐진다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post("#Cclime", T1), post("#CCLIME", T2)), Set.of());

		assertThat(out).containsExactly(new HashtagCandidateExtractor.Candidate("cclime", 2, T2));
	}

	@Test
	void stoplist_태그는_후보에서_빠진다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post("#광고 #끌리메", T1)), Set.of("광고"));

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메");
	}

	@Test
	void 순수_숫자_태그는_후보에서_빠진다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#2026 #끌리메", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메");
	}

	@Test
	void 숫자가_섞인_태그는_남는다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#끌리메2026", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메2026");
	}

	@Test
	void 등장_게시물_수_내림차순으로_정렬한다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#가 #나", T1), post("#나", T1), post("#나", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("나", "가");
	}

	@Test
	void 동률이면_최근_게시일이_앞선다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#오래된", T1), post("#최근", T2)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("최근", "오래된");
	}

	@Test
	void 수와_게시일이_모두_같으면_사전순이다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#bbb #aaa", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("aaa", "bbb");
	}

	@Test
	void 게시일이_null인_후보는_뒤로_밀린다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#널", null), post("#값", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("값", "널");
	}

	@Test
	void 캡션이_null이거나_비면_무시한다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post(null, T1), post("", T1), post("#가", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("가");
	}

	@Test
	void 해시태그가_하나도_없으면_빈_목록이다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("태그 없는 캡션", T1)), Set.of());

		assertThat(out).isEmpty();
	}

	@Test
	void 입력이_비면_빈_목록이다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(), Set.of())).isEmpty();
	}

	/** 전각 ＃은 인스타에서 링크가 되지 않는다 — BrandCaptionHashtags와 같은 계약. */
	@Test
	void 전각_샵은_해시태그가_아니다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(post("＃끌리메", T1)), Set.of())).isEmpty();
	}

	/** 점은 태그를 끊는다 — #cclime.beauty는 cclime까지다. */
	@Test
	void 점에서_태그가_끊긴다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#cclime.beauty", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("cclime");
	}
}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.HashtagCandidateExtractorTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현** — `monitoring/src/main/java/com/celfit/monitoring/service/HashtagCandidateExtractor.java`

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 태그된 게시물 캡션 → 해시태그 후보(2026-09-03 자동 시드 재설계 §3-2) — 순수 함수. 외부 상태·시각에
 * 의존하지 않으므로 같은 입력이면 정렬까지 항상 같은 결과다(테스트가 봉인하는 계약).
 *
 * <p>추출 규칙은 was {@code BrandCaptionHashtags}와 같다 — ASCII {@code #} + {@code [\p{L}\p{N}_]+}
 * 로 인스타 링크화와 일치시킨다(전각 ＃ 제외·점에서 끊김). 두 규칙이 갈리면 "화면에서 필터되는
 * 해시태그"와 "시드 후보"가 어긋난다.
 *
 * <p>집계 단위는 <b>등장 게시물 수</b>다 — 한 캡션에 같은 태그를 세 번 달아도 1로 센다(태그 도배가
 * 순위를 만들지 못하게 한다).
 */
public final class HashtagCandidateExtractor {

	/** was BrandCaptionHashtags.HASHTAG와 같은 정의를 유지할 것. */
	private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");
	private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");

	/** 후보 1건 — 태그(소문자)·등장 게시물 수·그 태그가 등장한 가장 최근 게시일(없으면 null). */
	public record Candidate(String tag, int postCount, Instant latestTakenAt) {
	}

	private HashtagCandidateExtractor() {
	}

	/**
	 * 정렬된 후보 목록 — 등장 게시물 수 내림차순 → 최근 게시일 내림차순(null은 뒤) → 태그 사전순.
	 *
	 * @param posts    태그된 게시물의 캡션·게시일. 캡션 null·빈 값은 무시한다.
	 * @param stoplist 제외 태그(전부 소문자). 순수 숫자 태그는 stoplist와 무관하게 항상 제외한다.
	 */
	public static List<Candidate> extract(List<TaggedCaption> posts, Set<String> stoplist) {
		Map<String, Integer> countByTag = new HashMap<>();
		Map<String, Instant> latestByTag = new HashMap<>();
		for (TaggedCaption post : posts) {
			for (String tag : tagsOf(post.caption(), stoplist)) {
				countByTag.merge(tag, 1, Integer::sum);
				if (post.takenAt() != null) {
					latestByTag.merge(tag, post.takenAt(), (a, b) -> a.isAfter(b) ? a : b);
				}
			}
		}
		List<Candidate> out = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : countByTag.entrySet()) {
			out.add(new Candidate(entry.getKey(), entry.getValue(), latestByTag.get(entry.getKey())));
		}
		Comparator<Candidate> ranking = Comparator.comparingInt((Candidate c) -> c.postCount()).reversed()
				.thenComparing(Candidate::latestTakenAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
				.thenComparing(Candidate::tag);
		out.sort(ranking);
		return List.copyOf(out);
	}

	/** 게시물 1건의 태그 집합 — 소문자 정규화 후 게시물당 중복 제거, 순수 숫자·stoplist 제외. */
	private static Set<String> tagsOf(String caption, Set<String> stoplist) {
		if (caption == null || caption.isEmpty()) {
			return Set.of();
		}
		Set<String> tags = new HashSet<>();
		Matcher matcher = HASHTAG.matcher(caption);
		while (matcher.find()) {
			String tag = matcher.group(1).toLowerCase(Locale.ROOT);
			if (DIGITS_ONLY.matcher(tag).matches() || stoplist.contains(tag)) {
				continue;
			}
			tags.add(tag);
		}
		return tags;
	}
}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.HashtagCandidateExtractorTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 캡션 해시태그 후보 집계 순수 함수

태그된 게시물 캡션에서 해시태그를 추출해 등장 게시물 수로 집계한다.
게시물당 중복 제거·순수 숫자/stoplist 제외·결정적 정렬(수 desc → 최근
게시일 desc → 사전순)까지 단위 테스트로 봉인한다. 추출 규칙은 was
BrandCaptionHashtags와 같은 정의(ASCII # + 글자/숫자/밑줄)다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 3 — 시드 판정에 필요한 조회 2종 (`countAll` · `findCaptionsForSeed`)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandHashtagRepository.java` (`findTags` 뒤, 35행 다음에 `countAll` 추가)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (Task 2에서 넣은 `TaggedCaption` record 뒤에 쿼리 추가)
- Create: `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagSeedQueryTest.java`

> **모수 결정(spec §3-2에서 한 걸음 좁힘)** — spec은 모수를 "`brand_tagged_post`(brand_id) ⋈
> `brand_post_meta`"로만 적었지만, 쿼리에 `t.tag_detected_at IS NOT NULL` 가드를 **반드시 건다**.
> 이유는 §5 재시드 절차다: 운영 정리는 절삭 태그를 hard DELETE만 하고 그 태그로 이미 수집된
> `brand_tagged_post` 행(hashtag 성분)은 남긴다. 가드가 없으면 `#dr` 같은 무관 태그로 긁혀 온
> 게시물의 캡션이 재시드 집계에 그대로 들어가 새 규칙이 그 오염을 물려받는다. 가드는 spec §3-2의
> 정의문("태그된 게시물 = 다른 사용자가 이 브랜드 계정을 태그한 게시물")과도 일치한다.

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagSeedQueryTest.java`

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 자동 시드 판정 입력 2종(2026-09-03 자동 시드 재설계 §3-1·§3-2) — BrandHashtagRepositoryTest와
 * 같은 Testcontainers 관용구. tombstone 포함 카운트와 "tag 성분 게시물의 캡션"만 실 컨테이너
 * 왕복으로 고정한다.
 */
class BrandHashtagSeedQueryTest {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

	JdbcTemplate db;
	BrandHashtagRepository tags;
	TaggedPostRepository taggedPosts;
	BrandPostMetaRepository meta;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		tags = new BrandHashtagRepository(db);
		taggedPosts = new TaggedPostRepository(db);
		meta = new BrandPostMetaRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	private static PostInfo post(String code, Instant takenAt) {
		return new PostInfo(code, "poster1", null, null, "9001", "REELS", "캡션", null,
				takenAt.getEpochSecond(), 10L, 2L, 500L, null, null, null, null, null, null, null,
				true, false, false);
	}

	private void writeMeta(String code, String caption) {
		meta.upsert(code, "poster1", "REELS", LocalDate.of(2026, 9, 1), caption,
				"https://thumb", null, null, null);
	}

	// ---------- countAll (실행 조건) ----------

	@Test
	void 태그가_없으면_countAll은_0이다() {
		assertThat(tags.countAll(brandId)).isZero();
	}

	@Test
	void countAll은_활성_태그를_센다() {
		tags.addTags(brandId, List.of("cclime", "끌리메"));

		assertThat(tags.countAll(brandId)).isEqualTo(2);
	}

	/** tombstone도 센다 — 유저가 지운 태그가 자동 시드로 되살아나면 안 된다(08-17 계약). */
	@Test
	void countAll은_tombstone_행도_센다() {
		tags.addTags(brandId, List.of("cclime"));
		tags.deleteTag(brandId, "cclime");

		assertThat(tags.findTags(brandId)).isEmpty();
		assertThat(tags.countAll(brandId)).isEqualTo(1);
	}

	@Test
	void countAll은_다른_브랜드_태그를_세지_않는다() {
		long otherId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('other', '98') RETURNING id",
				Long.class);
		tags.addTags(otherId, List.of("남의태그"));

		assertThat(tags.countAll(brandId)).isZero();
	}

	// ---------- findCaptionsForSeed (후보 모수) ----------

	@Test
	void tag_성분_게시물의_캡션과_게시일을_돌려준다() {
		taggedPosts.insert(brandId, post("AAA", NOW.minusSeconds(86400)));
		writeMeta("AAA", "오늘 #끌리메");

		List<TaggedPostRepository.TaggedCaption> out = taggedPosts.findCaptionsForSeed(brandId);

		assertThat(out).singleElement().satisfies(row -> {
			assertThat(row.caption()).isEqualTo("오늘 #끌리메");
			assertThat(row.takenAt()).isEqualTo(NOW.minusSeconds(86400));
		});
	}

	/** hashtag-only 행은 모수에서 빠진다 — 재시드 시 구 절삭 태그로 긁힌 무관 게시물 오염 차단. */
	@Test
	void hashtag_성분만_있는_게시물은_제외된다() {
		taggedPosts.upsertHashtag(brandId, post("HHH", NOW.minusSeconds(86400)), NOW);
		writeMeta("HHH", "무관 게시물 #dr");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	/** 겹침 행(tag + hashtag)은 tag 성분이 있으므로 포함된다. */
	@Test
	void tag_성분이_있으면_hashtag_겹침_행도_포함된다() {
		taggedPosts.insert(brandId, post("BOTH", NOW.minusSeconds(86400)));
		taggedPosts.upsertHashtag(brandId, post("BOTH", NOW.minusSeconds(86400)), NOW);
		writeMeta("BOTH", "#끌리메");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).hasSize(1);
	}

	@Test
	void 메타가_없는_게시물은_제외된다() {
		taggedPosts.insert(brandId, post("NOMETA", NOW.minusSeconds(86400)));

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	@Test
	void 캡션이_비었거나_null이면_제외된다() {
		taggedPosts.insert(brandId, post("EMPTY", NOW.minusSeconds(86400)));
		writeMeta("EMPTY", "");
		taggedPosts.insert(brandId, post("NULLCAP", NOW.minusSeconds(86400)));
		writeMeta("NULLCAP", null);

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	@Test
	void 다른_브랜드의_게시물은_제외된다() {
		long otherId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('other', '98') RETURNING id",
				Long.class);
		taggedPosts.insert(otherId, post("OTHER", NOW.minusSeconds(86400)));
		writeMeta("OTHER", "#남의태그");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}
}
```

- [ ] **실패 확인** — `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 후
  `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagSeedQueryTest"` 가
  컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/2** — `BrandHashtagRepository`의 `findTags`(31~35행) 바로 뒤에 추가한다.

```java
	/**
	 * 이 브랜드의 태그 행 전체 수 — <b>tombstone(deleted_at IS NOT NULL) 포함</b>. 자동 시드 실행
	 * 조건(2026-09-03 자동 시드 재설계 §3-1)의 판정 입력이라 {@link #findTags}와 의도적으로 필터가
	 * 다르다: 유저가 지운 태그가 자동 시드로 되살아나면 안 되고(08-17 tombstone 계약), 이 조건이
	 * AI 콜을 브랜드 생애 최대 1회로 묶는 게이트이기도 하다.
	 */
	public int countAll(long brandId) {
		Integer count = db.queryForObject("SELECT count(*) FROM brand_hashtag WHERE brand_id = ?",
				Integer.class, brandId);
		return count == null ? 0 : count;
	}
```

- [ ] **최소 구현 2/2** — `TaggedPostRepository`의 `TaggedCaption` record(Task 2에서 추가) 바로 뒤에 쿼리를 추가한다.

```java
	/**
	 * 자동 시드 후보 모수(2026-09-03 자동 시드 재설계 §3-2) — 이 브랜드에 <b>태그된</b> 게시물의
	 * 캡션·게시일. 캡션은 게시물 전역 1행인 {@code brand_post_meta}에 있어 short_code로 조인한다.
	 *
	 * <p><b>{@code tag_detected_at IS NOT NULL} 가드가 핵심이다</b>: 이 모수는 "다른 사용자가 이
	 * 브랜드 계정을 태그한 게시물"이고, hashtag 성분만 있는 행(해시태그 스윕이 긁어 온 게시물)은
	 * 여기 들어오면 안 된다. 특히 구 절삭 태그(예: {@code #dr}) 정리 후 재시드할 때, 그 태그로 이미
	 * 수집돼 남아 있는 무관 게시물의 캡션이 새 규칙의 집계를 그대로 오염시킨다. 겹침 행(tag +
	 * hashtag)은 tag 성분이 있으므로 포함된다.
	 *
	 * <p>캡션 3-상태 계약(트랙 HH) 중 null(미수집)·""(확인된 무캡션)은 후보를 만들지 못하므로
	 * SQL에서 거른다 — 전송량과 집계 루프를 함께 줄인다.
	 */
	public List<TaggedCaption> findCaptionsForSeed(long brandId) {
		return db.query("""
				SELECT m.caption, t.taken_at
				FROM brand_tagged_post t
				JOIN brand_post_meta m ON m.short_code = t.short_code
				WHERE t.brand_id = ?
				  AND t.tag_detected_at IS NOT NULL
				  AND m.caption IS NOT NULL
				  AND m.caption <> ''""",
				(rs, rowNum) -> new TaggedCaption(rs.getString("caption"),
						rs.getTimestamp("taken_at") == null ? null : rs.getTimestamp("taken_at").toInstant()),
				brandId);
	}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagSeedQueryTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **회귀 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.*"` 로 기존 store 테스트가 깨지지 않았는지 확인한다.

- [ ] **커밋**

```
feat(monitoring): 자동 시드 판정 입력 조회 2종

BrandHashtagRepository.countAll — tombstone 포함 태그 행 수(시드 실행 조건).
TaggedPostRepository.findCaptionsForSeed — tag 성분 게시물의 캡션·게시일.
후자는 tag_detected_at IS NOT NULL 가드로 hashtag-only 행을 배제한다.
구 절삭 태그 정리 후 재시드에서 무관 게시물 캡션이 집계를 오염시키는 경로다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 4 — AI 폴백 (`BrandHashtagSuggester`)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/llm/BrandHashtagSuggester.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/llm/BrandHashtagSuggesterTest.java`

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/llm/BrandHashtagSuggesterTest.java`

```java
package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * brandName → 해시태그 1개(2026-09-03 자동 시드 재설계 §3-4) — AdDisclosureExtractorGeminiTest와
 * 같은 fake GeminiHttp 관용구. 저장 전 검증(무효 문자·길이·순수 숫자·stoplist)은 전부 폐기(empty)로
 * 접히고, 전송·파싱 실패만 예외로 나간다(호출측 BrandHashtagSeedService가 격리한다).
 */
class BrandHashtagSuggesterTest {

	private static String geminiBody(String innerJson) {
		String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
		return """
				{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}""".formatted(escaped);
	}

	private static BrandHashtagSuggester suggester(GeminiHttp http) {
		return new BrandHashtagSuggester(http, true, "model-x");
	}

	@Test
	void 정상_응답의_해시태그를_돌려준다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"끌리메\"}"));

		assertThat(s.suggest("끌리메", Set.of())).contains("끌리메");
	}

	@Test
	void 선행_샵과_공백을_제거하고_소문자로_정규화한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"  #CClime  \"}"));

		assertThat(s.suggest("씨씨라임", Set.of())).contains("cclime");
	}

	@Test
	void 요청_경로와_바디에_모델과_브랜드명이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var s = new BrandHashtagSuggester((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("{\"hashtag\": \"끌리메\"}");
		}, true, "model-x");

		s.suggest("끌리메", Set.of());

		assertThat(sent.get()).contains("model-x:generateContent").contains("끌리메")
				.contains("responseSchema").contains("\"temperature\":0");
	}

	@Test
	void 공백_문자가_들어간_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"끌리 메\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void 특수문자가_들어간_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"cclime!\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void 한_글자_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"끌\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void 삼십자를_넘는_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"" + "a".repeat(31) + "\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void 삼십자_결과는_통과한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"" + "a".repeat(30) + "\"}"));

		assertThat(s.suggest("끌리메", Set.of())).contains("a".repeat(30));
	}

	@Test
	void 순수_숫자_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"2026\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void stoplist_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"AD\"}"));

		assertThat(s.suggest("끌리메", Set.of("ad"))).isEmpty();
	}

	@Test
	void 빈_문자열_결과는_폐기한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"\"}"));

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void brandName이_null이거나_공백이면_호출하지_않는다() {
		var s = suggester((path, body) -> {
			throw new AssertionError("brandName 없이는 호출하면 안 된다");
		});

		assertThat(s.suggest(null, Set.of())).isEmpty();
		assertThat(s.suggest("   ", Set.of())).isEmpty();
	}

	@Test
	void LLM_미설정이면_호출하지_않는다() {
		var s = new BrandHashtagSuggester((path, body) -> {
			throw new AssertionError("미설정 상태로 호출하면 안 된다");
		}, false, "model-x");

		assertThat(s.suggest("끌리메", Set.of())).isEmpty();
	}

	@Test
	void 응답_본문이_없으면_예외다() {
		var s = suggester((path, body) -> "{\"candidates\":[]}");

		assertThatThrownBy(() -> s.suggest("끌리메", Set.of())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 본문이_JSON이_아니면_예외다() {
		var s = suggester((path, body) -> geminiBody("이건 JSON이 아니다"));

		assertThatThrownBy(() -> s.suggest("끌리메", Set.of())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void hashtag_필드가_없으면_예외다() {
		var s = suggester((path, body) -> geminiBody("{\"tag\": \"끌리메\"}"));

		assertThatThrownBy(() -> s.suggest("끌리메", Set.of())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 전송_예외는_그대로_전파한다() {
		var s = suggester((path, body) -> {
			throw new IllegalStateException("전송 실패");
		});

		assertThatThrownBy(() -> s.suggest("끌리메", Set.of())).isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.llm.BrandHashtagSuggesterTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현** — `monitoring/src/main/java/com/celfit/monitoring/llm/BrandHashtagSuggester.java`

```java
package com.celfit.monitoring.llm;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 브랜드명 → 해시태그 1개 제안(2026-09-03 자동 시드 재설계 §3-4) — 태그된 게시물 캡션 집계가
 * 임계에 못 미칠 때만 쓰는 폴백이다. 입력은 <b>brandName 하나</b>다(사용자 결정 — 계정명·표시명·
 * 바이오는 넣지 않는다).
 *
 * <p>전송은 광고 표기 판정과 같은 {@link GeminiHttp} 빈·같은 모델 설정을 재사용한다(새 HTTP
 * 클라이언트를 만들지 않는다, {@code AdDisclosureExtractorGemini}와 동형).
 *
 * <p>{@code AdDisclosureExtractorGemini}와 갈리는 지점: 미설정(enabled=false)일 때 예외를 던지지
 * 않고 조용히 빈 값을 돌려준다. 광고 판정은 결과가 컬럼에 영속화되므로 잘못된 값을 남기느니
 * 실패해야 하지만, 자동 시드는 실패해도 "태그 0개"가 정상 상태이고 로컬·미설정 환경의 매 등록마다
 * 오류 로그를 남기는 게 해롭기 때문이다.
 *
 * <p><b>검증을 통과한 값만 돌려준다</b> — 선행 {@code #} 제거 → strip → 소문자 → 글자·숫자·밑줄
 * 전체 일치 → 길이 2~30 → 순수 숫자 아님 → stoplist 아님. 하나라도 어긋나면 warn 로그 + 빈 값이다
 * (LLM 출력이 그대로 스윕 대상 태그가 되는 경로라 검증이 유일한 방어선이다).
 */
public class BrandHashtagSuggester {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSuggester.class);

	private static final String SYSTEM_INSTRUCTION = """
			너는 한국 브랜드명 하나를 받아, 소비자가 그 브랜드에 관한 인스타그램 게시물에 가장 흔히
			다는 해시태그를 정확히 1개 고르는 도구다.

			규칙:
			- 답은 JSON {"hashtag": "..."} 형태만 낸다. 설명·부연·다른 필드를 넣지 않는다.
			- '#'을 붙이지 않는다.
			- 공백·마침표·이모지·특수문자를 넣지 않는다(글자·숫자·밑줄만 허용).
			- 브랜드를 특정하지 못하는 일반어(예: 광고, 협찬, 이벤트, 뷰티)를 고르지 않는다.
			""";

	/** 허용 문자 — 글자(한글 포함)·숫자·언더스코어. BrandHashtagTags.VALID_TAG와 같은 정의. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");
	private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");
	private static final int MIN_LENGTH = 2;
	private static final int MAX_LENGTH = 30;

	private final GeminiHttp http;
	private final boolean enabled;
	private final String model;
	private final ObjectMapper om = new ObjectMapper();

	/** enabled는 {@code LlmTransportConfig.LlmEnabled}의 값 — 인증은 주입된 전송이 전담한다. */
	public BrandHashtagSuggester(GeminiHttp http, boolean enabled, String model) {
		this.http = http;
		this.enabled = enabled;
		this.model = model;
	}

	/**
	 * @param brandName own 연결의 회사명. null·공백이면 호출 없이 빈 값(경쟁사 연결).
	 * @param stoplist  제외 태그(전부 소문자).
	 * @return 검증을 통과한 태그(소문자). 미설정·검증 실패는 빈 값. 전송·파싱 실패는 예외.
	 */
	public Optional<String> suggest(String brandName, Set<String> stoplist) {
		if (!enabled) {
			log.debug("Gemini 미설정 — 브랜드명 해시태그 제안 건너뜀");
			return Optional.empty();
		}
		if (brandName == null || brandName.isBlank()) {
			return Optional.empty();
		}
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent",
				requestBody(brandName));
		return validate(parse(responseBody), stoplist);
	}

	private String requestBody(String brandName) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		root.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", "브랜드명: " + brandName);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", responseSchema());
		// 태그 1개만 담으면 되므로 광고 추출(512)보다 훨씬 작다 — 잘림은 파싱 실패로 드러난다.
		gen.put("maxOutputTokens", 64);
		return om.writeValueAsString(root);
	}

	private ObjectNode responseSchema() {
		ObjectNode schema = om.createObjectNode();
		schema.put("type", "object");
		schema.putObject("properties").putObject("hashtag").put("type", "string");
		schema.putArray("required").add("hashtag");
		return schema;
	}

	private String parse(String responseBody) {
		JsonNode root = om.readTree(responseBody);
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		String textValue = text.asString();
		JsonNode innerRoot;
		try {
			innerRoot = om.readTree(textValue);
		} catch (JacksonException e) {
			throw new IllegalStateException("응답 본문 JSON 파싱 실패: " + abbreviate(textValue), e);
		}
		JsonNode hashtag = innerRoot.path("hashtag");
		if (hashtag.isMissingNode() || hashtag.isNull()) {
			throw new IllegalStateException("Gemini 응답에 hashtag 없음: " + abbreviate(textValue));
		}
		return hashtag.asString();
	}

	private Optional<String> validate(String raw, Set<String> stoplist) {
		String tag = raw == null ? "" : raw.strip();
		if (tag.startsWith("#")) {
			tag = tag.substring(1);
		}
		tag = tag.strip().toLowerCase(Locale.ROOT);
		if (!VALID_TAG.matcher(tag).matches()) {
			log.warn("AI 제안 해시태그 폐기(무효 문자) — value={}", abbreviate(raw));
			return Optional.empty();
		}
		if (tag.length() < MIN_LENGTH || tag.length() > MAX_LENGTH) {
			log.warn("AI 제안 해시태그 폐기(길이 {}) — value={}", tag.length(), abbreviate(raw));
			return Optional.empty();
		}
		if (DIGITS_ONLY.matcher(tag).matches()) {
			log.warn("AI 제안 해시태그 폐기(순수 숫자) — value={}", abbreviate(raw));
			return Optional.empty();
		}
		if (stoplist.contains(tag)) {
			log.warn("AI 제안 해시태그 폐기(stoplist) — value={}", abbreviate(raw));
			return Optional.empty();
		}
		return Optional.of(tag);
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 100 ? s.substring(0, 100) + "…" : s;
	}
}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.llm.BrandHashtagSuggesterTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): brandName 기반 해시태그 AI 폴백

태그된 게시물 캡션 집계가 임계에 못 미칠 때 쓰는 폴백. 광고 표기 판정과
같은 GeminiHttp seam·모델 설정을 재사용하고 temperature 0으로 JSON 1필드만
받는다. 저장 전 검증(# 제거·소문자·허용 문자 전체 일치·길이 2~30·순수 숫자
아님·stoplist 아님)을 통과한 값만 돌려준다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 5 — 시드 오케스트레이션 (`BrandHashtagSeedService`) + 빈 배선

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSeedService.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedConfig.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagSeedServiceTest.java`

> **지표 태그 하나 추가(spec §3-6에서 확장)** — spec은 `path`를 freq|ai|none|skip으로만 열거했다.
> DB 예외처럼 어느 경로에서 터졌는지 알 수 없는 실패를 위해 `path=unknown`을 추가한다. 없으면
> 실패를 정상 경로 중 하나로 오계상해야 하고, 그러면 지표가 거짓말을 한다.

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagSeedServiceTest.java`

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.config.BrandHashtagSeedSettings;
import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 자동 시드 판정(2026-09-03 자동 시드 재설계 §3) — 실행 조건(태그 행 0)·임계 경계(6→AI, 7→FREQ)·
 * AI 게이트(brandName null·ai-enabled=false)·격리(모든 실패가 흐름을 막지 않는다)를 고정한다.
 */
class BrandHashtagSeedServiceTest {

	private static final long BRAND_ID = 1L;
	private static final String USERNAME = "cclime_official";
	private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	private static final class StubTags extends BrandHashtagRepository {
		int existingCount;
		boolean countFailing;
		final List<String> added = new ArrayList<>();

		StubTags() {
			super(null);
		}

		@Override
		public int countAll(long brandId) {
			if (countFailing) {
				throw new IllegalStateException("DB 장애 주입");
			}
			return existingCount;
		}

		@Override
		public void addTags(long brandId, Collection<String> tags) {
			added.addAll(tags);
		}
	}

	private static final class StubTaggedPosts extends TaggedPostRepository {
		List<TaggedCaption> captions = List.of();

		StubTaggedPosts() {
			super(null);
		}

		@Override
		public List<TaggedCaption> findCaptionsForSeed(long brandId) {
			return captions;
		}
	}

	private static final class StubAppSettings extends AppSettingRepository {
		final Map<String, String> values = new HashMap<>();

		StubAppSettings() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			return Optional.ofNullable(values.get(key));
		}
	}

	private final StubTags tags = new StubTags();
	private final StubTaggedPosts taggedPosts = new StubTaggedPosts();
	private final StubAppSettings appSettings = new StubAppSettings();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final List<String> llmCalls = new ArrayList<>();

	/** 기본은 "정상 응답 1개" — 개별 테스트가 필요하면 응답 본문을 바꾼다. */
	private String llmResponse = geminiBody("{\"hashtag\": \"에이아이태그\"}");
	private RuntimeException llmFailure;

	private static String geminiBody(String innerJson) {
		String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
		return """
				{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}""".formatted(escaped);
	}

	private BrandHashtagSeedService service() {
		var suggester = new BrandHashtagSuggester((path, body) -> {
			llmCalls.add(body);
			if (llmFailure != null) {
				throw llmFailure;
			}
			return llmResponse;
		}, true, "model-x");
		return new BrandHashtagSeedService(tags, taggedPosts, suggester,
				new BrandHashtagSeedSettings(appSettings), registry);
	}

	private static TaggedCaption post(String caption) {
		return new TaggedCaption(caption, T);
	}

	private static List<TaggedCaption> repeated(String caption, int times) {
		List<TaggedCaption> out = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			out.add(post(caption));
		}
		return out;
	}

	private double counted(String path, String result) {
		var counter = registry.find("brand.hashtag.seed").tag("path", path).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}

	// ---------- 실행 조건 ----------

	@Test
	void 태그_행이_있으면_아무것도_하지_않는다() {
		tags.existingCount = 1;
		taggedPosts.captions = repeated("#끌리메", 10);

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).isEmpty();
		assertThat(llmCalls).isEmpty();
		assertThat(counted("skip", "ok")).isEqualTo(1);
	}

	// ---------- 빈도 경로 ----------

	@Test
	void 최다_태그가_임계_이상이면_그_태그_하나를_시드한다() {
		taggedPosts.captions = repeated("#끌리메 #뷰티", 7);

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).containsExactly("끌리메");
		assertThat(llmCalls).isEmpty();
		assertThat(counted("freq", "ok")).isEqualTo(1);
	}

	@Test
	void 임계_미만이면_AI로_넘어간다() {
		taggedPosts.captions = repeated("#끌리메", 6);

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).containsExactly("에이아이태그");
		assertThat(llmCalls).hasSize(1);
		assertThat(counted("ai", "ok")).isEqualTo(1);
	}

	@Test
	void 임계는_설정으로_바뀐다() {
		appSettings.values.put("brand.hashtag-seed.min-posts", "3");
		taggedPosts.captions = repeated("#끌리메", 3);

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).containsExactly("끌리메");
	}

	@Test
	void stoplist_태그는_최다여도_시드되지_않는다() {
		appSettings.values.put("brand.hashtag-seed.stoplist", "협찬");
		taggedPosts.captions = repeated("#협찬", 20);

		service().seedIfEmpty(BRAND_ID, USERNAME, null);

		assertThat(tags.added).isEmpty();
		assertThat(counted("none", "ok")).isEqualTo(1);
	}

	@Test
	void 태그된_게시물이_없으면_AI_경로다() {
		taggedPosts.captions = List.of();

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).containsExactly("에이아이태그");
	}

	// ---------- AI 게이트 ----------

	@Test
	void brandName이_없으면_AI를_부르지_않고_0개다() {
		taggedPosts.captions = repeated("#끌리메", 2);

		service().seedIfEmpty(BRAND_ID, USERNAME, null);

		assertThat(tags.added).isEmpty();
		assertThat(llmCalls).isEmpty();
		assertThat(counted("none", "ok")).isEqualTo(1);
	}

	@Test
	void ai_enabled가_false면_AI를_부르지_않고_0개다() {
		appSettings.values.put("brand.hashtag-seed.ai-enabled", "false");
		taggedPosts.captions = repeated("#끌리메", 2);

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).isEmpty();
		assertThat(llmCalls).isEmpty();
		assertThat(counted("none", "ok")).isEqualTo(1);
	}

	@Test
	void AI_결과가_검증에_걸리면_0개다() {
		llmResponse = geminiBody("{\"hashtag\": \"끌리 메\"}");

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).isEmpty();
		assertThat(counted("ai", "invalid")).isEqualTo(1);
	}

	// ---------- 격리 ----------

	@Test
	void AI_전송_실패는_격리되고_지표에_error로_남는다() {
		llmFailure = new IllegalStateException("전송 실패");

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).isEmpty();
		assertThat(counted("ai", "error")).isEqualTo(1);
	}

	@Test
	void DB_실패도_예외를_밖으로_내지_않는다() {
		tags.countFailing = true;

		service().seedIfEmpty(BRAND_ID, USERNAME, "끌리메");

		assertThat(tags.added).isEmpty();
		assertThat(counted("unknown", "error")).isEqualTo(1);
	}
}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagSeedServiceTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/2** — `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSeedService.java`

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.config.BrandHashtagSeedSettings;
import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 자동 시드(2026-09-03 자동 시드 재설계) — 계정명 문자열 절삭(2026-08-17~08-28)을
 * 대체한다. 재료는 <b>그 브랜드에 태그된 게시물의 캡션 해시태그 빈도</b>이고, 임계에 못 미치면
 * brandName 기반 AI 제안 1개로 폴백한다.
 *
 * <p><b>실행 조건은 "그 브랜드에 brand_hashtag 행이 하나도 없을 때"뿐이다</b>(tombstone 포함) —
 * 유저가 지운 태그가 되살아나지 않게 하고, AI 콜을 브랜드 생애 최대 1회로 묶는다.
 *
 * <p>호출 지점은 {@link BrandRegistrationService} 두 곳이다: 신규 등록의 백필 완주 직후(해시태그
 * 스윕 트리거 직전)와 replay 재등록의 동기 구간. 백필이 실패하면 시드하지 않는다 — 모수(캡션)가
 * 아직 없는 상태에서 판정하면 근거 없는 AI 폴백으로 새기 때문이고, 다음 replay 재등록이 백스톱이다.
 *
 * <p><b>모든 실패는 warn 격리한다</b>({@link #seedIfEmpty}가 유일한 진입점이고 예외를 밖으로 내지
 * 않는다) — 태그 0개는 정상 상태이므로 등록·백필·스윕 흐름을 막을 이유가 없다.
 */
public class BrandHashtagSeedService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSeedService.class);

	/** 태그: path(freq|ai|none|skip|unknown) · result(ok|invalid|error). */
	static final String METRIC = "brand.hashtag.seed";

	private final BrandHashtagRepository tags;
	private final TaggedPostRepository taggedPosts;
	private final BrandHashtagSuggester suggester;
	private final BrandHashtagSeedSettings settings;
	private final MeterRegistry registry;

	public BrandHashtagSeedService(BrandHashtagRepository tags, TaggedPostRepository taggedPosts,
			BrandHashtagSuggester suggester, BrandHashtagSeedSettings settings, MeterRegistry registry) {
		this.tags = tags;
		this.taggedPosts = taggedPosts;
		this.suggester = suggester;
		this.settings = settings;
		this.registry = registry;
	}

	/**
	 * 태그 행이 하나도 없을 때만 자동 태그 1개를 심는다. 예외를 던지지 않는다.
	 *
	 * @param brandName own 연결의 회사명(등록 API 파라미터). 경쟁사 연결은 null이고, 그러면 AI
	 *                  폴백 없이 0개로 끝난다(#406 게이트).
	 */
	public void seedIfEmpty(long brandId, String username, String brandName) {
		try {
			doSeed(brandId, username, brandName);
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 자동 시드 실패(격리) — brandId={}, username={}: {}",
					brandId, username, e.toString());
			count("unknown", "error");
		}
	}

	private void doSeed(long brandId, String username, String brandName) {
		if (tags.countAll(brandId) != 0) {
			log.debug("브랜드 해시태그 자동 시드 스킵(기존 태그 행 존재) — brandId={}, username={}",
					brandId, username);
			count("skip", "ok");
			return;
		}
		Set<String> stoplist = settings.stoplist();
		List<TaggedCaption> captions = taggedPosts.findCaptionsForSeed(brandId);
		List<HashtagCandidateExtractor.Candidate> candidates =
				HashtagCandidateExtractor.extract(captions, stoplist);
		int topCount = candidates.isEmpty() ? 0 : candidates.getFirst().postCount();
		if (topCount >= settings.minPosts()) {
			String tag = candidates.getFirst().tag();
			tags.addTags(brandId, List.of(tag));
			logSeed(brandId, username, "FREQ", tag, topCount, captions.size());
			count("freq", "ok");
			return;
		}
		if (brandName == null || brandName.isBlank() || !settings.aiEnabled()) {
			logSeed(brandId, username, "NONE", "-", topCount, captions.size());
			count("none", "ok");
			return;
		}
		Optional<String> suggested;
		try {
			suggested = suggester.suggest(brandName, stoplist);
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 AI 제안 실패(격리) — brandId={}, username={}: {}",
					brandId, username, e.toString());
			count("ai", "error");
			return;
		}
		if (suggested.isEmpty()) {
			logSeed(brandId, username, "AI", "-", topCount, captions.size());
			count("ai", "invalid");
			return;
		}
		tags.addTags(brandId, List.of(suggested.get()));
		logSeed(brandId, username, "AI", suggested.get(), topCount, captions.size());
		count("ai", "ok");
	}

	/** 시드 결과 1건당 info 1줄(스펙 §3-6) — 경로·태그·최다 수·후보 게시물 수를 한 줄에 담는다. */
	private void logSeed(long brandId, String username, String path, String tag, int topCount, int posts) {
		log.info("브랜드 해시태그 자동 시드 — brandId={}, username={}, path={}, tag={}, topCount={}, posts={}",
				brandId, username, path, tag, topCount, posts);
	}

	/** 지표 기록 실패는 삼킨다(MicrometerInstagramSourceMetrics 관용구) — 관측이 본류를 깨지 않는다. */
	private void count(String path, String result) {
		try {
			Counter.builder(METRIC).tag("path", path).tag("result", result).register(registry).increment();
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 자동 시드 지표 기록 실패(무시) — {} {}: {}", path, result, e.toString());
		}
	}
}
```

- [ ] **최소 구현 2/2** — `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedConfig.java`

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.service.BrandHashtagSeedService;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 자동 시드 배선(2026-09-03 자동 시드 재설계) — {@link AdDisclosureConfig}와 같은
 * 조립 패턴이다. 전송({@link GeminiHttp})·활성 여부는 {@link LlmTransportConfig}가 조립한 공유 빈을
 * 그대로 쓴다(새 HTTP 클라이언트를 만들지 않는다).
 *
 * <p>전용 executor는 두지 않는다 — 시드는 브랜드 생애 1회의 LLM 콜 1개라, 등록 백필 꼬리(backfill
 * executor)와 replay 동기 구간에서 그대로 도는 편이 풀 하나 더 만드는 것보다 단순하다.
 */
@Configuration
public class BrandHashtagSeedConfig {

	@Bean
	public BrandHashtagSuggester brandHashtagSuggester(GeminiHttp geminiHttp,
			LlmTransportConfig.LlmEnabled llmEnabled,
			@Value("${monitoring.brand.hashtag-seed.model:gemini-3.1-flash-lite}") String model) {
		return new BrandHashtagSuggester(geminiHttp, llmEnabled.value(), model);
	}

	@Bean
	public BrandHashtagSeedService brandHashtagSeedService(BrandHashtagRepository tags,
			TaggedPostRepository taggedPosts, BrandHashtagSuggester suggester,
			BrandHashtagSeedSettings settings, MeterRegistry meterRegistry) {
		return new BrandHashtagSeedService(tags, taggedPosts, suggester, settings, meterRegistry);
	}
}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagSeedServiceTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 브랜드 해시태그 자동 시드 오케스트레이션

실행 조건(태그 행 0, tombstone 포함) → 캡션 빈도 집계 → 임계(기본 7) 이상이면
그 태그 1개 시드 → 미만이면 brandName AI 폴백 → 그래도 없으면 0개. 모든 실패는
warn 격리하고 Micrometer 카운터 brand.hashtag.seed(path·result)로 남긴다.
아직 호출부는 없다(다음 커밋에서 BrandRegistrationService에 결선).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 6 — 등록 경로 결선 (`BrandRegistrationService`)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (78~109행 필드·생성자 / 154~185행 `register` / 211~235행 `expandIfRequested` / 271~296행 `runBackfillSafely`)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java` (스텁 추가 + 419~421행 `service()` + 신규 테스트 + 748~779행 javadoc)

### Steps

- [ ] **실패 테스트 작성 1/2 (스텁)** — `BrandRegistrationServiceTest`의 `StubTaggedPosts` 클래스 **바로 뒤**(326행 근처)에 시드 스텁을 넣는다.

```java
	/** 자동 시드 호출 관측(2026-09-03 자동 시드 재설계) — 판정 자체는 BrandHashtagSeedServiceTest가 본다. */
	private static final class StubHashtagSeed extends BrandHashtagSeedService {
		record SeedCall(long brandId, String username, String brandName) {}

		final List<SeedCall> calls = new CopyOnWriteArrayList<>();
		private List<String> callOrder = new CopyOnWriteArrayList<>();

		StubHashtagSeed() {
			super(null, null, null, null, null);
		}

		/** 호출 순서 검증용 — 다른 스텁과 같은 리스트를 공유시켜 인터리빙을 관찰한다. */
		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		@Override
		public void seedIfEmpty(long brandId, String username, String brandName) {
			calls.add(new SeedCall(brandId, username, brandName));
			callOrder.add("seed");
		}
	}
```

- [ ] **실패 테스트 작성 2/2 (필드·생성 + 검증)** — 필드 선언부(`private final StubTaggedPosts taggedPosts = new StubTaggedPosts();` 뒤)에 아래를 추가하고,

```java
	private final StubHashtagSeed hashtagSeed = new StubHashtagSeed();
```

  `service()`(419~421행)의 생성자 호출을 다음으로 바꾼다.

```java
		return new BrandRegistrationService(hiker, brands, collect, callCounts,
				hashtagCollect, taggedPosts, hashtagSeed, collectionPostLimit,
				Runnable::run, enrich, hashtagSweep);
```

  그리고 파일 끝의 `username_공백은_ValidationException` 테스트 **앞**에 신규 검증을 넣는다.

```java
	// ---------- 해시태그 자동 시드(2026-09-03 자동 시드 재설계 §3-1) ----------

	/**
	 * 신규 등록: 시드는 <b>전 페이지 보강 완주 뒤·해시태그 스윕 트리거 앞</b>이다. 이 순서라야
	 * 등록 직후 스윕이 갓 심은 태그를 바로 집는다(순서가 뒤집히면 새 태그의 첫 수집이 다음 야간
	 * 스윕까지 밀린다).
	 */
	@Test
	void 백필_완주_후_해시태그_스윕_직전에_자동_시드를_1회_호출한다() {
		twoPages();
		List<String> order = new CopyOnWriteArrayList<>();
		collect.useSharedCallOrder(order);
		hashtagSeed.useSharedCallOrder(order);
		hashtagCollect.useSharedCallOrder(order);

		var result = service().register("cclime_official", "끌리메");
		awaitEnrich();
		awaitHashtagSweep();

		assertThat(hashtagSeed.calls).containsExactly(
				new StubHashtagSeed.SeedCall(result.brandId(), "cclime_official", "끌리메"));
		assertThat(order).containsSubsequence("enrich", "seed", "hashtag");
	}

	/** 백필이 예외로 끝나면 시드하지 않는다 — 모수(캡션)가 없는 상태의 판정은 근거 없는 AI 폴백이 된다. */
	@Test
	void 백필_실패면_자동_시드를_호출하지_않는다() {
		collect.failing.add("cclime_official");

		service().register("cclime_official", "끌리메");
		awaitEnrich();
		awaitHashtagSweep();

		assertThat(hashtagSeed.calls).isEmpty();
	}

	/** replay 재등록은 이미 수집된 게시물이 있으므로 백필을 기다리지 않고 동기로 시드한다. */
	@Test
	void replay_재등록은_스윕_트리거_앞에서_동기로_시드한다() {
		var service = service();
		var first = service.register("cclime_official", "끌리메");
		awaitEnrich();
		awaitHashtagSweep();
		hashtagSeed.calls.clear();
		hashtagCollect.swept.clear();

		var replayed = service.register("cclime_official", "끌리메");

		// 동기 실행 — awaitHashtagSweep 전에 이미 기록돼 있어야 한다.
		assertThat(hashtagSeed.calls).containsExactly(
				new StubHashtagSeed.SeedCall(first.brandId(), "cclime_official", "끌리메"));
		assertThat(replayed.replayed()).isTrue();
		awaitHashtagSweep();
	}

	/** 경쟁사 연결은 was가 brandName에 null을 보낸다(#406 게이트) — 그대로 시드 서비스까지 내려간다. */
	@Test
	void brandName_null은_그대로_시드에_전달된다() {
		service().register("cclime_official", null, null, "competitor");
		awaitEnrich();
		awaitHashtagSweep();

		assertThat(hashtagSeed.calls).singleElement()
				.extracting(StubHashtagSeed.SeedCall::brandName).isNull();
	}

	/** 기간 확장 재백필도 등록과 같은 꼬리를 타므로 brandName이 유실되면 안 된다. */
	@Test
	void 기간_확장_재백필도_brandName을_들고_시드한다() {
		var service = service();
		service.register("cclime_official", "끌리메", 3);
		awaitEnrich();
		awaitHashtagSweep();
		hashtagSeed.calls.clear();

		service.register("cclime_official", "끌리메", 12);
		awaitEnrich();
		awaitHashtagSweep();

		// replay 분기 동기 1회 + 확장 재백필 꼬리 1회 — 둘 다 brandName을 들고 간다.
		assertThat(hashtagSeed.calls).isNotEmpty();
		assertThat(hashtagSeed.calls).allSatisfy(call ->
				assertThat(call.brandName()).isEqualTo("끌리메"));
	}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"` 가 컴파일 실패(생성자 인자 수 불일치)로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/4 (필드·생성자)** — `BrandRegistrationService`의 `taggedPosts` 필드(83행) 바로 뒤에 필드를 넣고,

```java
	/** 해시태그 자동 시드(2026-09-03 자동 시드 재설계) — 실패를 스스로 격리하므로 호출부에 try가 없다. */
	private final BrandHashtagSeedService hashtagSeed;
```

  생성자 시그니처·대입을 다음으로 바꾼다(90~109행).

```java
	public BrandRegistrationService(@Qualifier("syncInstagramSource") InstagramSource hiker,
			BrandRepository brands,
			BrandCollectService collect, BrandCallCountRepository callCounts,
			BrandHashtagCollectService hashtagCollect,
			TaggedPostRepository taggedPosts,
			BrandHashtagSeedService hashtagSeed,
			@Value("${monitoring.brand.collection-post-limit:2000}") int collectionPostLimit,
			@Qualifier("brandBackfillExecutor") Executor backfill,
			@Qualifier("brandEnrichExecutor") Executor enrich,
			@Qualifier("brandHashtagSweepExecutor") Executor hashtagSweep) {
		this.hiker = hiker;
		this.brands = brands;
		this.collect = collect;
		this.callCounts = callCounts;
		this.hashtagCollect = hashtagCollect;
		this.taggedPosts = taggedPosts;
		this.hashtagSeed = hashtagSeed;
		this.collectionPostLimit = collectionPostLimit;
		this.backfill = backfill;
		this.enrich = enrich;
		this.hashtagSweep = hashtagSweep;
	}
```

- [ ] **최소 구현 2/4 (register)** — `register(String, String, Integer, String)`(154~185행)의 replay 분기와 신규 등록 제출을 바꾼다.

```java
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			// 시드는 스윕 트리거 앞이다 — 갓 심은 태그를 이번 스윕이 바로 집게 한다. replay는 백필이
			// 돌지 않아(hiker 콜 0) 이미 수집된 게시물이 모수이므로 동기로 끝난다.
			hashtagSeed.seedIfEmpty(existing.get().id(), normalized, brandName);
			triggerHashtagSweep(existing.get());
			expandIfRequested(existing.get(), months, brandName);
			if (ownRequest && !existing.get().hasOwnLink()) {
				brands.setHasOwnLink(normalized, true);
			}
			logRegistered(normalized, startNanos, true);
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile, months, ownRequest);
		// 등록 검증 프로필 1콜의 사후 계상 — 콜 시점엔 brand_id가 없어 컨텍스트 스코프를 못 쓴다.
		// 등록 실패(계정 부재·비공개) 콜은 귀속할 브랜드가 없어 미집계다(어드민 크롤링 비용 설계).
		callCounts.add(id, LocalDate.now(KST), 1);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		// brandName은 DB에 없고 등록 API 파라미터로만 온다 — 비동기 백필 태스크에 클로저로 넘긴다.
		backfill.execute(() -> runBackfillSafely(row, brandName));
		logRegistered(normalized, startNanos, false);
		return new Result(id, normalized, profile.followers(), false);
```

  아울러 클래스 javadoc(134~153행 `register`의 javadoc) 중 "태그 시드 자체는 2026-08-28부터
  monitoring이 하지 않는다" 문단을 다음으로 교체한다.

```java
	 * <p><b>태그 자동 시드는 2026-09-03부터 다시 monitoring 책임이다</b>(자동 시드 재설계) — 단
	 * 재료가 계정명 문자열이 아니라 태그된 게시물 캡션의 해시태그 빈도(+ brandName AI 폴백)이고,
	 * 그 브랜드에 태그 행이 하나도 없을 때만 1개를 심는다({@link BrandHashtagSeedService}).
	 * was 쪽 유도 규칙 복제본은 같은 개정에서 삭제됐다.
```

- [ ] **최소 구현 3/4 (expandIfRequested)** — 시그니처와 재제출을 바꾼다(211행·234행).

```java
	private void expandIfRequested(BrandRow existing, int months, String brandName) {
```

```java
		BrandRow row = brands.findByUsername(existing.username()).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row, brandName));
```

- [ ] **최소 구현 4/4 (runBackfillSafely)** — 시그니처와 꼬리를 바꾼다(271행·287~289행).

```java
	private void runBackfillSafely(BrandRow row, String brandName) {
```

```java
			CompletableFuture.allOf(pages.toArray(CompletableFuture[]::new)).join();
			brands.touchSwept(row.id(), LocalDate.now(KST));
			// 자동 시드는 완주 표식 뒤·스윕 트리거 앞이다: 앞에 두면 시드의 LLM 콜(초 단위)만큼
			// FE 폴링 종료가 밀리고, 뒤에 두면 갓 심은 태그를 이번 스윕이 놓친다.
			hashtagSeed.seedIfEmpty(row.id(), row.username(), brandName);
			triggerHashtagSweep(row);
```

  그리고 `runBackfillSafely`의 javadoc 끝에 한 문단을 더한다.

```java
	 * <p>완주 뒤 꼬리는 두 단계다(2026-09-03 자동 시드 재설계 §3-1): 자동 시드 → 해시태그 스윕.
	 * <b>백필이 예외로 끝나면 시드하지 않는다</b> — 캡션 모수가 없는 상태의 판정은 근거 없는 AI
	 * 폴백으로 새기 때문이고, 다음 replay 재등록이 백스톱이다(기존 격리 계약과 같은 규율).
```

- [ ] **기존 테스트 javadoc 갱신** — `등록은_태그를_시드하지_않는다`·`활성_replay_재등록도_태그를_시드하지_않는다`
  두 테스트의 이름과 javadoc이 이제 사실과 어긋난다. 두 테스트를 각각
  `등록은_태그_없이도_스윕_꼬리를_돈다`·`활성_replay_재등록도_스윕만_트리거한다`로 이름을 바꾸고,
  앞 테스트의 javadoc(748~753행)을 다음으로 교체한다(뒤 테스트에는 javadoc이 없다).

```java
	/**
	 * 스윕 자체는 태그 시드 결과에 의존하지 않는다 — 태그가 0건이어도 백필 꼬리의
	 * {@code triggerHashtagSweep}은 그대로 돈다(시드 판정은 BrandHashtagSeedServiceTest가 본다).
	 */
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **모듈 회귀 확인** — `./gradlew :monitoring:test` 가 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 등록 경로에 해시태그 자동 시드 결선

신규 등록은 백필 완주 표식 뒤·해시태그 스윕 트리거 앞에서, replay 재등록은
등록 동기 구간에서 seedIfEmpty를 부른다. brandName은 DB에 없고 등록 파라미터로만
오므로 비동기 백필 태스크에 클로저로 넘긴다(runBackfillSafely 시그니처 변경).
백필 실패 경로에서는 호출하지 않는다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 7 — was 유도 규칙 복제본 삭제

**Files:**
- Delete: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java`
- Delete: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (142행 호출·149~186행 `seedLedgerTagsSafely` 삭제, 130~141행 javadoc 정리)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtags.java` (12행 javadoc 링크)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java` (등록 시딩 기대 4건)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` (등록 push 기대가 있으면)

> 삭제와 참조 제거를 **한 커밋에** 넣는다(중간 커밋이 컴파일을 깨면 안 된다).

### Steps

- [ ] **참조 전수 확인** — 아래를 돌려 `BrandHashtagTags` 참조가 어디에 남는지 확정한다(`docs/` 제외).

```
grep -rn "BrandHashtagTags" was/src crawler/src analytics/src monitoring/src instagram-source/src common-llm/src
```

  조사 시점 기준 was 쪽 참조는 5곳이다: 삭제할 본체·테스트, `BrandCaptionHashtags:12`(javadoc),
  `V1BrandAccountService:150`(javadoc)·`:172`(호출). `V1BrandAccountsControllerTest`·
  `BrandHashtagTagsBackfillMigrationTest`·`V20260827092444__brand_hashtag_tags_backfill.sql`은
  **주석 문자열로만** 언급하므로 컴파일에 영향이 없다(마이그레이션은 이미 적용돼 있어 절대 수정 금지).
  monitoring `service.BrandHashtagTags`는 동명이인이고 유지 대상이니 건드리지 않는다.

- [ ] **실패 테스트 작성** — `V1BrandAccountServiceHashtagTagsTest`에서 등록 시딩을 검증하는 3건을 삭제하고, 대신 "등록은 시딩하지 않는다"를 고정한다. 삭제 대상은
  `신규_링크_생성은_유도_태그를_monitoring에_push하고_장부에도_시딩한다`,
  `유도_태그가_없으면_push도_시딩도_건너뛴다`,
  `장부_시딩_실패는_등록_응답에_영향이_없다` 세 개다(`멱등_재_POST는_장부를_시딩하지_않는다`는 유지).
  그 자리에 아래를 넣는다.

```java
	/**
	 * 태그 유도 규칙은 2026-09-03부터 monitoring 단일 소유다(자동 시드 재설계 §4) — was는 링크
	 * 생성 시 아무 태그도 심지 않는다. 자동 태그는 monitoring이 수집 완료 뒤에 심고, 사용자 장부는
	 * 조회 시 승계로 채워진다({@code ensureLedgerSeededSafely}).
	 */
	@Test
	void 신규_링크_생성은_태그를_시딩하지_않는다() {
		given(commandClient.registerBrand(USERNAME, null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, USERNAME, 100L, "ACTIVE"));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, 12);

		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}
```

- [ ] **실패 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"` 가 실패하는 것을 확인한다(등록이 여전히 시딩하므로 `never()` 단언이 깨진다).

- [ ] **최소 구현 1/4 (삭제)** —

```
git -C /Users/woomin/Project/hypenow-backend/.worktrees/hashtag-auto-seed rm \
  was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java \
  was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java
```

- [ ] **최소 구현 2/4 (`V1BrandAccountService`)** — 142행 `seedLedgerTagsSafely(userId, registered.brandId(), username);` 한 줄과 그 앞 주석 블록(134~141행)을 아래로 교체하고, 149~186행의 `seedLedgerTagsSafely` 메서드와 그 javadoc을 통째로 삭제한다.

```java
		// 태그 시딩은 하지 않는다(2026-09-03 자동 시드 재설계 §4) — 유도 규칙이 monitoring 단일
		// 소유로 돌아갔다. monitoring이 수집 완료 뒤에 캡션 빈도(+brandName AI 폴백)로 태그를 심고,
		// 이 사용자의 장부는 조회 시 승계({@link #ensureLedgerSeededSafely})로 채워진다.
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
```

  삭제 후 `List` import가 여전히 다른 곳에서 쓰이는지 확인하고(쓰인다 — `getHashtagTags` 등), 안
  쓰이는 import가 생기면 지운다.

- [ ] **최소 구현 3/4 (`BrandCaptionHashtags` javadoc)** — 9~13행 javadoc의 마지막 문장을 바꾼다.

```java
/**
 * 캡션 해시태그 추출(스펙 2026-08-31 §3). 규칙은 ASCII # + [\p{L}\p{N}_]+ — 인스타 링크화와
 * 일치가 계약이다(전각 ＃ 제외·이모지 갭 수용, 검증 근거는 스펙). 문자 집합은 monitoring
 * {@code HashtagCandidateExtractor.HASHTAG}·{@code BrandHashtagTags.VALID_TAG}와 같은 정의를
 * 유지할 것 — 갈리면 "화면에서 필터되는 태그"와 "monitoring이 시드·스윕하는 태그"가 어긋난다.
 */
```

- [ ] **최소 구현 4/4 (컨트롤러 테스트)** — `V1BrandAccountsControllerTest`에서 등록 응답을 검증하며
  `addHashtagTags`·`addTags` 호출을 기대하는 단언이 있으면 제거한다(조사 시점 기준 1312행 부근
  `then(hashtagTagRepository).should().addTags(7L, 100L, List.of("리즈다"));`는 **태그 추가 API**의
  단언이라 그대로 둔다 — 등록 경로 단언만 대상이다). 아래로 대상을 특정한다.

```
grep -n "register" -A 20 was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java | grep -n "addHashtagTags\|addTags"
```

- [ ] **통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(was): 해시태그 유도 규칙 복제본 삭제

was BrandHashtagTags(계정명 접두사 절삭)와 그 테스트, 링크 생성 시
seedLedgerTagsSafely 호출을 삭제한다. 유도 규칙은 2026-09-03부터 monitoring
단일 소유이고, was는 조회 시 승계로 장부를 채운다. 규칙이 두 벌이라 "바꾸면
두 곳을 같이 고쳐야 한다"던 상태가 사라진다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 8 — was 조회 시 장부 승계

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (`getHashtagTags` 270~289행, `ensureSeeded` 402~412행 뒤에 공개 래퍼 신설)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (90~109행 생성자, 212~239행 두 엔드포인트)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java`
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

> **설계 판단(spec §4의 지목 클래스 정정)** — spec은 승계 지점으로 `BrandHashtagPostAssembler`를
> 지목했지만, 그 클래스는 장부를 읽지 않는다. 실제 읽기는 `BrandPostAssembler:225`·`:504`이고
> 거기엔 `MonitoringCommandClient`도 username도 없다(게시물 목록 조립 전용 컴포넌트라 monitoring
> HTTP 클라이언트를 주입하는 건 층 위반이자 성능 위험이다). 그래서 **승계를 컨트롤러 층으로 올려**
> `V1BrandPostsController`가 조립 직전에 `V1BrandAccountService.ensureLedgerSeededSafely`를
> 부른다. 소유권 검증은 컨트롤러가 이미 `requireOwnership`으로 마쳤다.
>
> **적용 범위** — 해시태그 게시물 엔드포인트 2개(`hashtagPosts`·`hashtagPostCount`)와
> `getHashtagTags`에만 건다. 메인 목록 `GET /accounts/{id}/posts`에는 걸지 **않는다**: 그 목록은
> 수집 중 FE가 초 단위로 폴링하는 경로라, 태그가 영영 0개인 경쟁사 브랜드에서 폴링마다 monitoring
> GET 1콜이 나간다. 브랜드 상세 화면은 태그 칩(`getHashtagTags`)을 함께 부르므로 실사용에서는 그
> 호출이 장부를 채운다 — 남는 갭은 트랙 문서의 후속 항목으로 적는다.

### Steps

- [ ] **실패 테스트 작성 1/2 (서비스)** — `V1BrandAccountServiceHashtagTagsTest`에 승계 검증을 추가한다.

```java
	// ---------- 조회 시 장부 승계(2026-09-03 자동 시드 재설계 §4) ----------

	/** 장부가 비었고 monitoring에 무주 태그가 있으면 승계해서 그 태그로 응답한다. */
	@Test
	void 장부가_비면_monitoring_태그를_승계해_돌려준다() {
		// 승계 전(빈 장부) → 승계 후(끌리메) 순으로 두 번 조회된다 — 다이아몬드는 varargs 추론이
		// 흔들리므로 타입 인자를 명시한다.
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<String>(), new LinkedHashSet<>(List.of("끌리메")));
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("끌리메"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("끌리메"));
		assertThat(tags).extracting(BrandHashtagTagsResponse.TagStatus::tag).containsExactly("끌리메");
	}

	/** 장부가 차 있으면 monitoring 태그 목록 조회 자체를 하지 않는다(기존 08-19 계약 유지). */
	@Test
	void 장부가_차_있으면_monitoring_태그를_조회하지_않는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("리즈다")));

		service.getHashtagTags(USER_ID, BRAND_ID);

		then(commandClient).should(never()).getHashtagTags(anyString());
	}

	/** 남이 소유한 태그는 승계하지 않는다 — ensureSeeded의 기존 무주 판정 그대로다. */
	@Test
	void 남이_소유한_태그는_승계하지_않는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(new LinkedHashSet<>());
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("남의태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of("남의태그"));

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		assertThat(tags).isEmpty();
	}

	/** monitoring 순단이 조회를 500으로 떨구지 않는다 — 승계는 best-effort다. */
	@Test
	void 승계_실패는_조회를_깨지_않는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(new LinkedHashSet<>());
		given(commandClient.getHashtagTags(USERNAME)).willThrow(new RuntimeException("monitoring 순단"));

		assertThat(service.getHashtagTags(USER_ID, BRAND_ID)).isEmpty();
	}
```

- [ ] **실패 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"` 가 실패하는 것을 확인한다.

- [ ] **최소 구현 1/3 (`ensureLedgerSeededSafely`)** — `V1BrandAccountService`의 private `ensureSeeded`(402~412행) **바로 뒤**에 공개 래퍼를 넣는다.

```java
	/**
	 * 조회 경로 장부 승계(2026-09-03 자동 시드 재설계 §4) — <b>이 사용자의 장부가 비어 있을 때만</b>
	 * {@link #ensureSeeded}로 monitoring의 무주 태그를 물려받는다. 자동 태그를 monitoring이 심게
	 * 되면서(등록 시 was 시딩 폐지) 장부를 채울 유일한 경로가 됐다.
	 *
	 * <p>비용은 장부가 비어 있는 동안만 monitoring GET 1회/조회다. 태그가 영원히 0개인 브랜드
	 * (경쟁사)는 화면을 열 때마다 1콜이 나가는데, 내부 HTTP 1콜이라 수용한다 — 그래서 초 단위로
	 * 폴링되는 게시물 목록({@code GET /accounts/{id}/posts})에는 걸지 않는다.
	 *
	 * <p>부활 방지: 사용자가 지운 태그는 was가 monitoring에서도 지우거나(단독 소유), 남이 소유
	 * 중이면 무주가 아니라 걸러진다 — 조회 승계로 되살아나지 않는다.
	 *
	 * <p><b>소유권 검증은 하지 않는다</b> — 호출측(이미 {@code requireOwnership}을 통과한
	 * 컨트롤러·서비스)의 책임이다. 실패는 warn 격리한다: monitoring 순단 하나로 조회 화면이 깨지면
	 * 안 된다(다른 best-effort 관용구와 동형).
	 */
	public void ensureLedgerSeededSafely(long userId, long brandId, String username) {
		try {
			if (!hashtagTagRepository.findByUserAndBrand(userId, brandId).isEmpty()) {
				return;
			}
			ensureSeeded(userId, brandId, username);
		} catch (RuntimeException e) {
			log.warn("해시태그 장부 조회 승계 실패(격리) — userId={}, brandId={}, username={}",
					userId, brandId, username, e);
		}
	}
```

- [ ] **최소 구현 2/3 (`getHashtagTags`)** — 270~278행을 다음으로 바꾼다.

```java
	public List<BrandHashtagTagsResponse.TagStatus> getHashtagTags(long userId, long brandId) {
		requireOwnership(userId, brandId);
		// 장부가 비어 있으면 monitoring 자동 태그를 먼저 승계한다(2026-09-03 자동 시드 재설계 §4) —
		// 소유권 통과 후에도 브랜드 자체는 존재해야 한다(기존 계약 유지).
		String username = findAccountOrThrow(brandId).username();
		ensureLedgerSeededSafely(userId, brandId, username);
		List<String> ledgerTags = List.copyOf(hashtagTagRepository.findByUserAndBrand(userId, brandId));
		if (ledgerTags.isEmpty()) {
			return List.of();
		}
		Map<String, MonitoringCommandClient.TagRunState> runStates = fetchRunStatesSafely(username);
```

  (`String username = findAccountOrThrow(brandId).username();` 이 위로 올라갔으므로 279행의 중복
  선언을 지운다.)

- [ ] **통과 확인 (서비스)** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **실패 테스트 작성 2/2 (컨트롤러)** — `V1BrandPostsControllerTest`의 `@MockitoBean` 선언부에 아래를 추가하고,

```java
	/** 해시태그 목록 조회 시 장부 승계(2026-09-03 자동 시드 재설계 §4) — 판정은 서비스 테스트가 본다. */
	@MockitoBean
	V1BrandAccountService brandAccountService;
```

  해시태그 엔드포인트 검증을 추가한다(파일의 hashtag-posts 관련 테스트 근처).

```java
	@Test
	void 해시태그_목록_조회는_장부_승계를_먼저_시도한다() throws Exception {
		stubOwnedBrand(7L, 100L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk());

		then(brandAccountService).should().ensureLedgerSeededSafely(7L, 100L, "lizda_official");
	}

	@Test
	void 해시태그_개수_조회도_장부_승계를_먼저_시도한다() throws Exception {
		stubOwnedBrand(7L, 100L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts/count").with(user(principal())))
				.andExpect(status().isOk());

		then(brandAccountService).should().ensureLedgerSeededSafely(7L, 100L, "lizda_official");
	}
```

  > `stubOwnedBrand`와 `principal()`은 이 테스트 클래스의 기존 헬퍼다. 세 번째 인자로 넘길
  > username은 그 헬퍼가 심는 `BrandAccountRow`의 username과 정확히 같아야 한다 — 헬퍼 정의를 열어
  > 실제 값을 확인하고 리터럴을 맞춘다. `then`/`should`는 `org.mockito.BDDMockito` 정적 import다.

- [ ] **실패 확인 (컨트롤러)** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 3/3 (`V1BrandPostsController`)** — 필드·생성자에 서비스를 추가하고,

```java
	private final BrandHashtagPostAssembler hashtagPostAssembler;
	/** 해시태그 장부 승계 전용(2026-09-03 자동 시드 재설계 §4) — 조립 직전 1회. */
	private final V1BrandAccountService brandAccountService;
	private final Clock clock;

	public V1BrandPostsController(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandPostAssembler assembler, BrandIndexCache indexCache,
			V1BrandDirectPostService directPostService,
			BrandHashtagPostAssembler hashtagPostAssembler, V1BrandAccountService brandAccountService,
			Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.indexCache = indexCache;
		this.directPostService = directPostService;
		this.hashtagPostAssembler = hashtagPostAssembler;
		this.brandAccountService = brandAccountService;
		this.clock = clock;
	}
```

  두 엔드포인트에서 조립 직전에 승계를 부른다.

```java
	@GetMapping("/accounts/{accountId}/hashtag-posts")
	public ApiResponse<List<BrandHashtagPostResponse>> hashtagPosts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		// 장부가 비어 있으면 monitoring 자동 태그를 승계한다 — 격리 필터(내 태그 ∩ 매칭 태그)가
		// 빈 장부에서는 아무것도 통과시키지 못해 자동 태그로 발견된 게시물이 통째로 안 보인다.
		brandAccountService.ensureLedgerSeededSafely(principal.getUserId(), brandId, account.username());
		LocalDate windowStart = BrandPostWindows.linkWindowStart(today(), link.collectionMonths());
		return ApiResponse.ok(hashtagPostAssembler.assembleForBrand(principal.getUserId(), account,
				link.accountType(), windowStart));
	}
```

```java
	@GetMapping("/accounts/{accountId}/hashtag-posts/count")
	public ApiResponse<Map<String, Object>> hashtagPostCount(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		brandAccountService.ensureLedgerSeededSafely(principal.getUserId(), brandId, account.username());
		LocalDate windowStart = BrandPostWindows.linkWindowStart(today(), link.collectionMonths());
		return ApiResponse.ok(Map.of("count",
				hashtagPostAssembler.countForBrand(principal.getUserId(), account, windowStart)));
	}
```

- [ ] **통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 전부 통과하는 것을 확인한다.

- [ ] **모듈 회귀 확인** — `./gradlew :was:test` 가 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(was): 해시태그 장부가 비면 조회 시 monitoring 태그를 승계

등록 시 시딩이 사라지면서 장부를 채울 유일한 경로가 됐다. 태그 목록 조회와
해시태그 게시물 목록·개수 조회에서, 이 사용자의 장부가 비어 있을 때만 기존
ensureSeeded(무주 태그 승계)를 부른다. 실패는 warn 격리한다.

승계 호출은 컨트롤러 층에 둔다 - BrandPostAssembler는 monitoring 클라이언트도
username도 갖지 않는 조립 전용 컴포넌트라 여기에 HTTP 의존을 넣을 수 없다.
초 단위로 폴링되는 메인 게시물 목록에는 걸지 않는다(콜 낭비).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 9 — 결정 기록과 트랙 문서

**Files:**
- Modify: `DECISIONS.md` (표 헤더 바로 아래, 맨 위 행)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (파일 끝에 절 추가)

### Steps

- [ ] **DECISIONS.md 갱신** — `| 날짜 | 결정 | 근거/상세 |` 표의 **첫 데이터 행 앞**에 아래 한 행을 넣는다(기존 행과 같은 3열 관용구).

```
| 2026-09-03 | **브랜드 해시태그 자동 시드 재설계 - 계정명 절삭 폐기, 태그된 게시물 빈도 + brandName AI 폴백, 유도 규칙 monitoring 단일화** - 자동 태그 재료를 "계정명 소문자화 후 첫 무효 문자 앞까지 절삭"(2026-08-17 be39cbd7)에서 **그 브랜드에 태그된 게시물 캡션의 해시태그 빈도**로 바꾼다. 절삭은 점이 든 계정명에서 계정과 무관한 일반어를 만든다(`dr.piel_official` → `#dr` 전량 스윕 = 무관 게시물 대량 유입 + Hiker 콜 낭비). 점 든 계정명은 애초에 그 자체로 해시태그가 될 수 없어 문자열을 어떻게 잘라도 결과가 추측이고, 실제 소비자가 쓰는 태그는 브랜드명이다. **규칙**: 최다 태그의 등장 게시물 수 ≥ 7이면 그 태그 1개 시드(비율 조건 없음), 미만이면 brandName 하나만 보고 AI가 태그 1개 생성(brandName 없는 경쟁사는 0개), 실행은 `brand_hashtag` 행이 tombstone 포함 0일 때만(생애 1회 - 지운 태그 부활 방지 + AI 콜 상한). 시점은 신규 등록=백필 완주 직후·스윕 트리거 직전, replay 재등록=동기. 임계·stoplist·AI 킬스위치는 app_setting 런타임 토글. **동시에 2026-08-27 해시태그 직접 수집 설계 §4의 "was에 유도 규칙 복제" 결정을 폐기한다** - 같은 규칙이 monitoring·was 두 벌로 존재해 "바꾸면 두 곳을 같이 고쳐야 한다"는 상태 자체가 지시("한 곳에서만") 위반이었고, was는 이미 monitoring 태그를 GET해 장부로 옮기는 `ensureSeeded` 경로를 갖고 있어 복제가 불필요했다. was `BrandHashtagTags`·등록 시 장부 시딩을 삭제하고, 조회 시 장부가 비면 승계하는 경로로 대체. FE 계약 변화 없음(자동 태그가 "등록 즉시"가 아니라 "수집 완료 뒤" 나타나는 타이밍만 바뀜 - FE 통지 1건). 운영 정리는 이미 심긴 절삭 태그 hard DELETE + 영향 브랜드 replay 재등록 재시드(트랙 MON-BT). | [spec 2026-09-03](docs/superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md), [plan](docs/superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md) |
```

- [ ] **트랙 문서 갱신** — `docs/tracks/MON-BT-브랜드-태그-모니터링.md` **파일 끝**에 아래 절을 덧붙인다.

```markdown
## 해시태그 자동 시드 재설계(2026-09-03) — 계정명 절삭 폐기

- 상태: 구현 완료·**운영 정리 미실행**. 설계 정본은 [spec 2026-09-03](../superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md), 실행 계획은 [plan](../superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md).
- 자동 태그 재료가 계정명 문자열 절삭 → **태그된 게시물 캡션의 해시태그 빈도**로 바뀌었다. 최다 태그의 등장 게시물 수가 `brand.hashtag-seed.min-posts`(기본 7) 이상이면 그 태그 1개, 미만이면 brandName 기반 AI 폴백 1개, brandName이 없으면(경쟁사) 0개. 실행은 `brand_hashtag` 행이 **tombstone 포함 0일 때만**이다.
- 유도 규칙은 **monitoring 단일 소유**로 돌아갔다. was `BrandHashtagTags`(복제본)와 등록 시 장부 시딩은 삭제됐고, 장부는 조회 시 승계(`V1BrandAccountService.ensureLedgerSeededSafely`)로 채워진다.
- 런타임 토글(app_setting, TTL 5초): `brand.hashtag-seed.min-posts` / `brand.hashtag-seed.stoplist` / `brand.hashtag-seed.ai-enabled`. AI 폴백 킬 스위치는 SQL 한 줄이다.
  ```sql
  UPDATE app_setting SET value = 'false' WHERE key = 'brand.hashtag-seed.ai-enabled';
  ```
- 관측: 시드 1건당 info 로그 1줄(`브랜드 해시태그 자동 시드 — brandId=… path=FREQ|AI|NONE tag=… topCount=… posts=…`) + Micrometer 카운터 `brand.hashtag.seed`(태그 `path`=freq|ai|none|skip|unknown, `result`=ok|invalid|error).

### 운영 데이터 정리와 재시드 (배포 후 1회 — 미실행)

실행 전 대상 목록을 먼저 뽑아 **눈으로 확인한다**. IG 계정명은 ASCII·점·언더스코어만 허용되므로
절삭 접두사 = 첫 점 앞 구간이다.

```sql
-- 1) 대상 확인(monitoring DB)
SELECT h.brand_id, a.username, a.has_own_link, h.tag, h.created_at, h.deleted_at
FROM brand_hashtag h JOIN brand_account a ON a.id = h.brand_id
WHERE position('.' in a.username) > 0
  AND h.tag = lower(split_part(a.username, '.', 1))
ORDER BY h.brand_id;
```

- `created_at`이 그 브랜드의 등록 시각과 **다른** 행은 사용자가 직접 넣은 태그일 수 있다 — 그런
  행은 대상에서 뺀다(아래 DELETE 실행 전에 `brand_id`를 손으로 제외할 것).

```sql
-- 2) monitoring: hard DELETE (tombstone이 아니다)
-- tombstone으로 남기면 "행 0일 때만" 조건에 걸려 재시드가 영영 막힌다.
DELETE FROM brand_hashtag h
USING brand_account a
WHERE a.id = h.brand_id
  AND position('.' in a.username) > 0
  AND h.tag = lower(split_part(a.username, '.', 1));
```

```sql
-- 3) was(app 스키마): 같은 (brand_id, tag) 집합을 장부에서도 삭제
-- monitoring DB와 물리적으로 분리돼 있어 조인이 불가능하다 — 1)에서 뽑은 목록을 그대로 나열한다.
DELETE FROM app.brand_hashtag_tags
WHERE (brand_id, tag) IN ( (:brandId1, :tag1), (:brandId2, :tag2) /* … 1)의 결과 전량 */ );
```

- 그 태그로 이미 수집된 `brand_hashtag_post`·매칭 태그 행은 **남긴다** — 격리 필터가 장부 기준이라
  화면에서 사라지고, 스윕은 태그가 없으니 더 긁지 않는다. 물리 정리는 비범위.
- 시드 집계 쿼리(`findCaptionsForSeed`)는 `tag_detected_at IS NOT NULL` 가드가 있어, 남겨 둔
  hashtag-only 게시물의 캡션이 재시드 집계를 오염시키지 않는다.

```
# 4) 재시드 — 대상 브랜드마다 monitoring 내부 register를 replay로 호출한다
#    (ACTIVE 브랜드 재등록 분기 → 동기 시드 → 스윕)
curl -X POST http://<monitoring>/api/brands \
  -H 'Content-Type: application/json' \
  -d '{"username":"<계정명>","brandName":"<회사명>","accountType":"own"}'
```

- **⚠️ 경쟁사 전용 브랜드(`has_own_link = false`)는 반드시 `"accountType":"competitor"`로 호출한다.**
  기본값(own)으로 부르면 `has_own_link`가 true로 뒤집혀 광고 표기 판정 모수가 오염된다
  (`BrandRepository.insertOrReactivate`의 승격 규칙 — own 요청은 승격시키고 내리지는 않는다).
  1)의 `a.has_own_link` 컬럼으로 브랜드마다 판정할 것.
- own 브랜드의 `brandName`은 was `app.users.company_name`에서 조회해 넘긴다. 넘기지 않으면
  임계 미만일 때 AI 폴백이 돌지 않아 0개로 끝난다.
- 재시드 결과(FREQ / AI / NONE 분포)는 로그 `브랜드 해시태그 자동 시드` 줄 또는 카운터
  `brand.hashtag.seed`로 집계해 이 문서에 기록한다.

### 잔여·후속

- **FE 통지 1건** — 응답 계약은 그대로이고, 자동 태그가 "등록 즉시"가 아니라 "초기 수집 완료 뒤"에
  나타나도록 타이밍만 바뀌었다.
- **메인 게시물 목록(`GET /accounts/{id}/posts`)의 승계 미적용** — 장부 승계는 태그 목록·해시태그
  게시물 목록·개수 3개 표면에만 걸었다. 메인 목록은 수집 중 초 단위로 폴링돼, 태그가 영영 0개인
  경쟁사 브랜드에서 폴링마다 monitoring GET이 나가기 때문이다. 브랜드 상세 화면이 태그 칩
  (`getHashtagTags`)을 함께 부르므로 실사용에서는 그 호출이 장부를 채우지만, 그 가정이 깨지면
  (FE가 태그 칩 호출을 없애는 등) 자동 태그로 발견된 게시물이 메인 목록에서만 안 보일 수 있다.
- **다중 태그 시드·주기적 재시드는 비범위**(spec §7). 재시드 입구는 replay 재등록 경로뿐이다.
```

- [ ] **링크 확인** — 두 문서에서 새로 건 상대 경로가 실제 파일을 가리키는지 확인한다.

```
ls docs/superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md \
   docs/superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md
```

- [ ] **커밋**

```
docs: 해시태그 자동 시드 재설계 결정 기록과 트랙 갱신

DECISIONS.md에 계정명 절삭 폐기·08-27 §4 복제 결정 폐기 사유를 기록하고,
MON-BT 트랙에 새 시드 규칙·런타임 토글·관측과 운영 정리/재시드 절차(SQL 포함)를
적는다. 재시드 시 경쟁사 전용 브랜드는 accountType=competitor 필수라는 경고를
절차 안에 넣었다(기본값 own은 has_own_link를 뒤집어 광고 판정 모수를 오염시킨다).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## 최종 검증

- [ ] `./gradlew :monitoring:test` 전량 통과
- [ ] `./gradlew :was:test` 전량 통과
- [ ] `grep -rn "BrandHashtagTags" was/src` 결과에 `was/.../v1/brandmonitoring/BrandHashtagTags`(클래스) 참조가 남지 않는다(주석 문자열 언급은 무해)
- [ ] `git -C <worktree> status` 에 의도하지 않은 변경이 없다
- [ ] PR·push는 하지 않는다 — 사용자 승인 사항이다.
