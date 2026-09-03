# 브랜드 해시태그 자동 시드 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자동 해시태그의 재료를 "계정명 문자열 절삭"에서 "그 브랜드에 태그된 게시물 캡션의 해시태그 빈도(+ IG 표시명 기반 AI, + 계정명 정리 안전장치)"로 바꾸고, 계산은 monitoring 내부 조회 API 1개로, 쓰기는 was 한 곳으로 유지한다.

**Architecture:** **monitoring은 계산만 하고 DB에 쓰지 않는다** — `GET /api/brands/{username}/hashtag-suggestion`이 `{path, tag, topCount, candidatePosts}`를 돌려주고 `tag`는 절대 비지 않는다(FREQ → AI → FALLBACK 3단). **was가 유일한 작성자다**(08-28 결정 유지) — 브랜드당 시드 기록 1행(`app.brand_hashtag_seed`)과 링크별 반영 표식(`brand_monitorings.hashtag_seeded_at`)으로 계산을 브랜드당 1회, 장부 삽입을 사용자당 1회로 묶고, 훅(`ensureAutoSeeded`)을 초기 백필 완료 뒤 조회 표면(단건 폴링 · 태그 목록 · 해시태그 게시물 목록/개수)에 건다. 유도 규칙(계정명 절삭)은 어디에도 남지 않는다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(monitoring / was) · JdbcTemplate(monitoring) · JdbcClient(was) · Jackson 3(`tools.jackson.*`) · Micrometer · Flyway(UTC 타임스탬프 채번, monitoring·was app 각자 버전 공간) · JUnit 5 + AssertJ + Mockito · Testcontainers(PostgreSQL)

---

> 상태: 🟢 활성 · 구현 중 (2026-09-03)
>
> 정본 설계: [2026-09-03 브랜드 해시태그 자동 시드 재설계(2차 개정)](../specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md)

## 사전 준비 (모든 Task 공통)

Testcontainers를 도는 Task 3·Task 7은 셸에 아래가 **반드시** export돼 있어야 한다. 없으면 컨테이너
초기화가 깨져 무관한 테스트가 무더기로 실패한다(테스트 결함으로 오진하기 쉬운 양상).

```
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```

작업 위치는 worktree `/Users/woomin/Project/hypenow-backend/.worktrees/hashtag-auto-seed`(브랜치
`docs/brand-hashtag-auto-seed-spec`)다. 모든 git 명령은 `git -C <이 경로>`로 실행한다.

## 코드 조사에서 확인된 사실 (계획의 전제)

| 항목 | 실체 |
|---|---|
| monitoring `BrandHashtagTags.derive` | 2026-08-28에 **이미 삭제됨**. 남은 `isValidTag`는 `BrandController:361`이 유저 입력 검증에 쓴다 — 유지. |
| monitoring `BrandRegistrationService` | 이 개정에서 **건드리지 않는다**(등록 경로 결선 없음). `seedHashtagsSafely`도 이미 없다. |
| `brand_account.full_name` | `BrandRow`에 **없다**(id·username·igUserId·status·lastSweptOn·collectionMonths·hasOwnLink). 컬럼은 존재하며 `BrandRepository.insertOrReactivate`·`refreshProfile`이 쓴다 → 전용 조회 `findFullName(brandId)`를 새로 판다(BrandRow 확장은 사용처가 넓어 비용이 크다). |
| monitoring 404 관용구 | `BrandController.activeBrand(username)` + `brandNotFound()`(`{code:"BRAND_NOT_FOUND"}` 바디). 빈 바디 404는 was가 503으로 오승격한다(08-11 실측) — 반드시 이 헬퍼를 쓴다. |
| was `BrandAccountRow.backfillCompletedAt` | 존재(`BrandReadRepository.BrandAccountRow` 6번째 컴포넌트). |
| was `BrandLinkRow` | record, 8 컴포넌트. 프로덕션 생성은 `BrandLinkRepository`의 `query(BrandLinkRow.class)` 매핑뿐이고, **`new BrandLinkRow(...)` 직접 호출은 전부 테스트(12파일 24곳)**다. |
| was app 마이그레이션 | `was/src/main/resources/db/migration/app/`, 최신 `V20260902125204__ai_chat_logs_feedback.sql`. |

## 파일 구조

### 생성 (monitoring)

| 파일 | 책임 |
|---|---|
| `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_seed_settings.sql` | 설정 3키 시드 |
| `monitoring/.../config/BrandHashtagSeedSettings.java` | app_setting TTL(5초) 캐시 |
| `monitoring/.../service/HashtagCandidateExtractor.java` | 순수 함수 — 캡션 목록 → 정렬된 후보 |
| `monitoring/.../llm/BrandHashtagSuggester.java` | AI — (표시명, 계정명) → 정리된 태그 |
| `monitoring/.../service/BrandHashtagSuggestionService.java` | FREQ→AI→FALLBACK 3단 계산·응답 조립·로그·지표 |
| `monitoring/.../config/BrandHashtagSuggestionConfig.java` | 위 두 클래스 빈 조립 |
| 테스트 5개 | `config/BrandHashtagSeedSettingsTest` · `service/HashtagCandidateExtractorTest` · `llm/BrandHashtagSuggesterTest` · `service/BrandHashtagSuggestionServiceTest` · `store/BrandHashtagSeedQueryTest` |

### 생성 (was)

| 파일 | 책임 |
|---|---|
| `was/src/main/resources/db/migration/app/V<UTC>__brand_hashtag_seed.sql` | 시드 기록 테이블 + 링크 표식 컬럼 |
| `was/.../monitoring/BrandHashtagSeedRepository.java` | `app.brand_hashtag_seed` 접점(find/insertIgnore) |
| `was/src/test/.../monitoring/BrandHashtagSeedRepositoryTest.java` | 위 통합 테스트 |
| `was/src/test/.../v1/brandmonitoring/V1BrandAccountServiceAutoSeedTest.java` | `ensureAutoSeeded` 분기 전량 |

### 수정

| 파일 | 변경 |
|---|---|
| `monitoring/.../store/TaggedPostRepository.java` | `TaggedCaption` record + `findCaptionsForSeed(long)` |
| `monitoring/.../store/BrandRepository.java` | `findFullName(long)` |
| `monitoring/.../web/BrandController.java` | 생성자에 `BrandHashtagSuggestionService` 추가 + `GET /{username}/hashtag-suggestion` |
| `monitoring/src/test/.../web/BrandControllerTest.java` | 스텁 추가 + 404·200 검증 |
| `was/.../monitoring/BrandLinkRow.java` | `hashtagSeededAt` 컴포넌트 추가 |
| `was/.../monitoring/BrandLinkRepository.java` | `SELECT_COLUMNS`에 `hashtag_seeded_at` + `markHashtagSeeded(long)` |
| `was/.../monitoring/MonitoringCommandClient.java` | `getHashtagSuggestion(String)` + `HashtagSuggestionBody` record |
| `was/.../v1/brandmonitoring/V1BrandAccountService.java` | `seedLedgerTagsSafely` 삭제, `ensureAutoSeeded` 신설, `get`·`getHashtagTags` 훅 |
| `was/.../v1/brandmonitoring/V1BrandPostsController.java` | 생성자에 `V1BrandAccountService` + 해시태그 목록·개수에 훅 |
| `was/.../v1/brandmonitoring/BrandCaptionHashtags.java` | 삭제되는 `BrandHashtagTags` javadoc 링크 정리 |
| was 테스트 12파일 | `new BrandLinkRow(...)` 24곳에 인자 1개 추가 |
| `DECISIONS.md` · `docs/tracks/MON-BT-브랜드-태그-모니터링.md` | 결정 기록 + 트랙 갱신 |

### 삭제

| 파일 | 사유 |
|---|---|
| `was/.../v1/brandmonitoring/BrandHashtagTags.java` | 계정명 절삭 유도 규칙 |
| `was/src/test/.../v1/brandmonitoring/BrandHashtagTagsTest.java` | 위 클래스 전용 테스트 |

---

## Task 1 — monitoring 설정 키 시드와 TTL 캐시

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_seed_settings.sql`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedSettings.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/config/BrandHashtagSeedSettingsTest.java`

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/config/BrandHashtagSeedSettingsTest.java`

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
 * 해시태그 제안 설정 TTL 캐시 — {@code IgSourceSettings}와 같은 관용구(짧은 TTL·이상값 안전측·
 * 조회 실패 시 직전 캐시 유지)를 세 키(min-posts·stoplist·ai-enabled)에 대해 고정한다.
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

- [ ] **최소 구현** — `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSeedSettings.java`

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
 * 브랜드 해시태그 제안 런타임 설정(2026-09-03 자동 시드 재설계 §3-5) — app_setting을 짧은 TTL
 * (기본 5초)로 캐시한다. {@code IgSourceSettings}와 같은 관용구다: 키 부재·이상값은 기본값으로 접고,
 * 조회가 실패하면(DB 장애) 직전 캐시를 유지하며 캐시가 아예 없으면 기본값으로 fail-safe한다 —
 * 설정 조회 예외가 제안 API를 500으로 떨구지 않게 한다.
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

	/** FREQ 임계(등장 게시물 수, 이 값 이상이면 그 태그를 쓴다). */
	public int minPosts() {
		return snapshot().minPosts();
	}

	/** FREQ 후보·AI 결과에서 제외할 태그(전부 소문자). */
	public Set<String> stoplist() {
		return snapshot().stoplist();
	}

	/** AI 경로 킬 스위치 — false면 FREQ 실패 시 곧장 FALLBACK이다. */
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
			log.warn("해시태그 제안 설정 조회 실패 — 안전측 기본값으로 fail-safe: {}", e.toString());
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

	/** 숫자 아님·0 이하는 기본값 — 0 이하를 허용하면 후보 0건에도 FREQ가 나가 규칙이 무너진다. */
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

- [ ] **마이그레이션 채번** — `date -u +%Y%m%d%H%M%S` 를 실행해 UTC 타임스탬프를 얻고(예: `20260903091500`) 그 값을 파일명에 쓴다. **반드시 UTC** — KST 채번은 미래 번호 선점으로 뒤따르는 정상 채번을 Flyway out-of-order 거부에 빠뜨린다.

- [ ] **마이그레이션 작성** — `monitoring/src/main/resources/db/migration/V<위에서 얻은 값>__brand_hashtag_seed_settings.sql`

```sql
-- 브랜드 해시태그 제안 런타임 설정(2026-09-03 자동 시드 재설계 §3-5).
-- min-posts  : 태그된 게시물 캡션 집계에서 최다 태그의 "등장 게시물 수"가 이 값 이상이면 path=FREQ.
-- stoplist   : FREQ 후보·AI 결과 양쪽에서 제외할 태그(쉼표 구분, 소문자 비교).
-- ai-enabled : 2순위 AI 경로 킬 스위치. 끄면 FREQ 미달이 곧장 FALLBACK(계정명 정리)으로 간다:
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
feat(monitoring): 해시태그 제안 설정 키와 TTL 캐시

app_setting 3키(min-posts·stoplist·ai-enabled)를 Flyway로 시드하고,
IgSourceSettings와 같은 5초 TTL 캐시로 읽는 BrandHashtagSeedSettings를 추가한다.
키 부재·이상값은 기본값으로 접고, 조회 실패는 직전 캐시 유지로 fail-safe한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 2 — 캡션 해시태그 후보 집계 (`HashtagCandidateExtractor`)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (`nthNewestHashtagTakenAt` 뒤, 81행 다음에 record만 — 쿼리는 Task 3)
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/HashtagCandidateExtractor.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/service/HashtagCandidateExtractorTest.java`

> 순수 함수의 입력 타입을 저장소 record로 두는 이유: 같은 모양의 record를 service·store에 두 벌
> 만들면 매핑 보일러플레이트와 드리프트가 생긴다. monitoring의 service→store 의존은 기존 관용구다
> (`BrandRegistrationService`가 `BrandRow`를 직접 쓴다).

### Steps

- [ ] **입력 record 추가** — `TaggedPostRepository`의 `nthNewestHashtagTakenAt` 바로 뒤에 넣는다. `java.time.Instant` import는 이미 있다.

```java
	/**
	 * 해시태그 제안 후보 집계 입력(2026-09-03 자동 시드 재설계 §3-2) — 태그된 게시물 1건의 캡션과
	 * 게시일. takenAt은 동률 태그의 tie-break(최근 우선)에만 쓰이므로 null이어도 집계는 성립한다.
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
		var out = HashtagCandidateExtractor.extract(List.of(post("#끌리메 #끌리메 #끌리메", T1)), Set.of());

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
		var out = HashtagCandidateExtractor.extract(List.of(post("#광고 #끌리메", T1)), Set.of("광고"));

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
		assertThat(HashtagCandidateExtractor.extract(List.of(post("태그 없는 캡션", T1)), Set.of())).isEmpty();
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
 * 해시태그"와 "제안 후보"가 어긋난다.
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

## Task 3 — 제안 계산에 필요한 조회 2종

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (Task 2에서 넣은 `TaggedCaption` record 뒤)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java` (`findById` 뒤, 168행 근처)
- Create: `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagSeedQueryTest.java`

> **`tag_detected_at IS NOT NULL` 가드가 핵심이다**(spec §3-2에 채택됨). §5 운영 정리는 절삭 태그를
> hard DELETE만 하고 그 태그로 이미 수집된 `brand_tagged_post` 행(hashtag 성분)은 남긴다. 가드가
> 없으면 `#dr` 같은 무관 태그로 긁혀 온 게시물의 캡션이 집계에 섞여 새 규칙이 오염을 물려받는다.

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
 * 해시태그 제안 계산 입력 2종(2026-09-03 자동 시드 재설계 §3-2·§3-3) — BrandHashtagRepositoryTest와
 * 같은 Testcontainers 관용구. "tag 성분 게시물의 캡션"과 IG 표시명 조회를 실 컨테이너 왕복으로 고정한다.
 */
class BrandHashtagSeedQueryTest {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

	JdbcTemplate db;
	TaggedPostRepository taggedPosts;
	BrandPostMetaRepository meta;
	BrandRepository brands;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		taggedPosts = new TaggedPostRepository(db);
		meta = new BrandPostMetaRepository(db);
		brands = new BrandRepository(db);
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

	// ---------- findCaptionsForSeed (FREQ 모수) ----------

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

	/** hashtag-only 행은 모수에서 빠진다 — 구 절삭 태그로 긁힌 무관 게시물 오염 차단(§3-2). */
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

	// ---------- findFullName (AI 입력) ----------

	@Test
	void 표시명이_있으면_돌려준다() {
		db.update("UPDATE brand_account SET full_name = ? WHERE id = ?", "닥터피엘 Dr.PIEL", brandId);

		assertThat(brands.findFullName(brandId)).contains("닥터피엘 Dr.PIEL");
	}

	@Test
	void 표시명이_null이면_empty다() {
		assertThat(brands.findFullName(brandId)).isEmpty();
	}

	@Test
	void 표시명이_공백뿐이면_empty다() {
		db.update("UPDATE brand_account SET full_name = ? WHERE id = ?", "   ", brandId);

		assertThat(brands.findFullName(brandId)).isEmpty();
	}

	@Test
	void 없는_브랜드는_empty다() {
		assertThat(brands.findFullName(-1L)).isEmpty();
	}
}
```

- [ ] **실패 확인** — `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 후 `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagSeedQueryTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/2** — `TaggedPostRepository`의 `TaggedCaption` record 바로 뒤에 쿼리를 추가한다.

```java
	/**
	 * 해시태그 제안 FREQ 모수(2026-09-03 자동 시드 재설계 §3-2) — 이 브랜드에 <b>태그된</b> 게시물의
	 * 캡션·게시일. 캡션은 게시물 전역 1행인 {@code brand_post_meta}에 있어 short_code로 조인한다.
	 *
	 * <p><b>{@code tag_detected_at IS NOT NULL} 가드가 핵심이다</b>: 이 모수는 "다른 사용자가 이
	 * 브랜드 계정을 태그한 게시물"이고, hashtag 성분만 있는 행(해시태그 스윕이 긁어 온 게시물)은
	 * 여기 들어오면 안 된다. 특히 구 절삭 태그(예: {@code #dr}) 정리 뒤에도 그 태그로 수집된 무관
	 * 게시물 행은 남는데, 그 캡션이 집계에 섞이면 새 규칙이 오염을 그대로 물려받는다. 겹침 행
	 * (tag + hashtag)은 tag 성분이 있으므로 포함된다.
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

- [ ] **최소 구현 2/2** — `BrandRepository`의 `findById`(162행 근처) 바로 뒤에 추가한다.

```java
	/**
	 * IG 표시명(full_name) — 해시태그 제안 AI 입력(2026-09-03 자동 시드 재설계 §3-3). 등록 시
	 * 프로필 1콜로 저장되고 매일 스윕이 {@link #refreshProfile}로 갱신한다.
	 *
	 * <p>{@link BrandRow}에 싣지 않고 전용 조회로 두는 이유: BrandRow는 스윕·등록의 뜨거운 경로가
	 * 전부 물고 다니는 단면이라 이 한 필드를 위해 넓히면 비용이 크고, 표시명은 제안 계산에서만
	 * 쓰인다(브랜드당 생애 1회).
	 *
	 * <p>미수집(null)·공백은 empty — 호출측이 "표시명 없음"으로 다루고 계정명만으로 진행한다.
	 */
	public Optional<String> findFullName(long brandId) {
		List<String> rows = db.query("SELECT full_name FROM brand_account WHERE id = ?",
				(rs, rowNum) -> rs.getString("full_name"), brandId);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(rows.getFirst()).filter(value -> !value.isBlank());
	}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagSeedQueryTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **회귀 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.*"` 로 기존 store 테스트가 깨지지 않았는지 확인한다.

- [ ] **커밋**

```
feat(monitoring): 해시태그 제안 계산 입력 조회 2종

TaggedPostRepository.findCaptionsForSeed — tag 성분 게시물의 캡션·게시일.
tag_detected_at IS NOT NULL 가드로 hashtag-only 행을 배제한다(구 절삭 태그로
긁힌 무관 게시물이 집계를 오염시키는 경로).
BrandRepository.findFullName — AI 입력용 IG 표시명(BrandRow는 넓히지 않는다).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 4 — AI 제안 (`BrandHashtagSuggester`)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/llm/BrandHashtagSuggester.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/llm/BrandHashtagSuggesterTest.java`

> **입력은 IG 표시명(`full_name`) + 계정명뿐이다.** 회사명(`app.users.company_name`)·바이오는 쓰지
> 않는다(spec §3-3). 출력은 **버리지 않고 정리한다** — 허용 외 문자를 제거하고 30자로 자른 뒤에도
> 남는 값이 있으면 그대로 쓴다. stoplist·순수 숫자만 "빈 값"으로 접어 상위가 FALLBACK으로 내린다.
>
> 서명은 spec의 두 입력에 `stoplist`를 더한 3-arg다 — §3-3 정리 규칙 전체(stoplist 판정 포함)를 한
> 클래스에 두기 위해서다. 쪼개면 "AI 출력 정리"가 두 파일에 흩어져 테스트가 갈린다.

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
 * (IG 표시명, 계정명) → 해시태그 1개(2026-09-03 자동 시드 재설계 §3-3) — AdDisclosureExtractorGeminiTest와
 * 같은 fake GeminiHttp 관용구. 출력은 <b>버리지 않고 정리</b>한다(허용 외 문자 제거·30자 절단).
 * stoplist·순수 숫자만 빈 값으로 접히고, 전송·파싱 실패는 예외로 나간다(상위가 FALLBACK으로 내린다).
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
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"닥터피엘\"}"));

		assertThat(s.suggest("닥터피엘 Dr.PIEL", "dr.piel_official", Set.of())).contains("닥터피엘");
	}

	@Test
	void 선행_샵과_공백을_제거하고_소문자로_정규화한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"  #CClime  \"}"));

		assertThat(s.suggest("씨씨라임", "cclime_official", Set.of())).contains("cclime");
	}

	/** 허용 외 문자는 제거한다(버리지 않는다) — "닥터 피엘!" → "닥터피엘". */
	@Test
	void 허용_외_문자는_제거하고_남은_값을_쓴다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"닥터 피엘!\"}"));

		assertThat(s.suggest("닥터피엘", "dr.piel_official", Set.of())).contains("닥터피엘");
	}

	@Test
	void 점과_언더스코어_중_언더스코어만_남는다() {
		// 점은 허용 문자가 아니라 제거되고, 언더스코어는 유효 태그 문자라 남는다.
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"dr.piel_official\"}"));

		assertThat(s.suggest("", "dr.piel_official", Set.of())).contains("drpiel_official");
	}

	@Test
	void 삼십자를_넘으면_절단한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"" + "a".repeat(40) + "\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).contains("a".repeat(30));
	}

	@Test
	void 정리_결과가_비면_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"!!! ???\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).isEmpty();
	}

	@Test
	void 순수_숫자_결과는_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"2026\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).isEmpty();
	}

	@Test
	void stoplist_결과는_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"AD\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of("ad"))).isEmpty();
	}

	@Test
	void 요청에_모델_표시명_계정명이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var s = new BrandHashtagSuggester((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("{\"hashtag\": \"닥터피엘\"}");
		}, true, "model-x");

		s.suggest("닥터피엘 Dr.PIEL", "dr.piel_official", Set.of());

		assertThat(sent.get()).contains("model-x:generateContent")
				.contains("닥터피엘 Dr.PIEL").contains("dr.piel_official")
				.contains("responseSchema").contains("\"temperature\":0");
	}

	/** 표시명이 비어도 계정명만으로 호출한다 — 프롬프트가 "표시명 없음" 분기를 담당한다. */
	@Test
	void 표시명이_null이어도_계정명으로_호출한다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var s = new BrandHashtagSuggester((path, body) -> {
			sent.set(body);
			return geminiBody("{\"hashtag\": \"drpiel\"}");
		}, true, "model-x");

		assertThat(s.suggest(null, "dr.piel_official", Set.of())).contains("drpiel");
		assertThat(sent.get()).contains("dr.piel_official");
	}

	@Test
	void 계정명이_없으면_호출하지_않는다() {
		var s = suggester((path, body) -> {
			throw new AssertionError("계정명 없이는 호출하면 안 된다");
		});

		assertThat(s.suggest("표시명", null, Set.of())).isEmpty();
		assertThat(s.suggest("표시명", "  ", Set.of())).isEmpty();
	}

	@Test
	void LLM_미설정이면_호출하지_않는다() {
		var s = new BrandHashtagSuggester((path, body) -> {
			throw new AssertionError("미설정 상태로 호출하면 안 된다");
		}, false, "model-x");

		assertThat(s.suggest("표시명", "brand", Set.of())).isEmpty();
	}

	@Test
	void 응답_본문이_없으면_예외다() {
		var s = suggester((path, body) -> "{\"candidates\":[]}");

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 본문이_JSON이_아니면_예외다() {
		var s = suggester((path, body) -> geminiBody("이건 JSON이 아니다"));

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void hashtag_필드가_없으면_예외다() {
		var s = suggester((path, body) -> geminiBody("{\"tag\": \"닥터피엘\"}"));

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 전송_예외는_그대로_전파한다() {
		var s = suggester((path, body) -> {
			throw new IllegalStateException("전송 실패");
		});

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
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
 * IG 표시명 → 브랜드 상호 해시태그 1개(2026-09-03 자동 시드 재설계 §3-3) — 태그된 게시물 캡션
 * 집계(FREQ)가 임계에 못 미칠 때 쓰는 2순위다.
 *
 * <p>입력은 <b>표시명(full_name)과 계정명뿐</b>이다. 회사명(was {@code users.company_name})·
 * 바이오는 넣지 않는다 — 회사명은 등록자가 자기 소속을 적은 값이라 경쟁사 브랜드에 남의 이름을
 * 붙일 수 있고, 바이오는 잡음이 많다.
 *
 * <p>전송은 광고 표기 판정과 같은 {@link GeminiHttp} 빈·같은 모델 설정을 재사용한다(새 HTTP
 * 클라이언트를 만들지 않는다, {@code AdDisclosureExtractorGemini}와 동형).
 *
 * <p><b>출력은 버리지 않고 정리한다</b> — 선행 {@code #} 제거 → strip → 소문자 → 허용 외 문자
 * ({@code [\p{L}\p{N}_]} 밖) <b>제거</b> → 30자 초과 절단. 그 결과가 비었거나 순수 숫자거나
 * stoplist면 빈 값을 돌려주고, 상위({@code BrandHashtagSuggestionService})가 FALLBACK으로 내린다.
 * "AI가 조금 틀린 형태로 답했다"는 이유로 브랜드를 계정명 안전장치까지 떨어뜨리지 않기 위함이다.
 *
 * <p>{@code AdDisclosureExtractorGemini}와 갈리는 지점: 미설정(enabled=false)일 때 예외를 던지지
 * 않고 조용히 빈 값을 돌려준다. 광고 판정은 결과가 컬럼에 영속화되므로 잘못된 값을 남기느니
 * 실패해야 하지만, 여기는 빈 값이 곧 FALLBACK이라 정상 경로다.
 */
public class BrandHashtagSuggester {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSuggester.class);

	private static final String SYSTEM_INSTRUCTION = """
			너는 인스타그램 브랜드 계정의 표시명과 계정명을 받아, 소비자가 그 브랜드를 게시물에
			언급할 때 가장 흔히 쓸 해시태그를 정확히 1개 고르는 도구다.

			규칙:
			- 표시명에 브랜드 상호가 있으면 그 상호를 쓴다. 상호가 한글이면 한글로 쓴다.
			- 표시명이 비어 있거나 상호가 없으면(영문 약자·수식어뿐), 계정명에서 '_official',
			  '.official', '_kr', '_korea' 같은 접미사와 장식을 떼고 남는 브랜드 핵심을 쓴다.
			  점·언더스코어를 살릴지 뺄지는 해시태그로 자연스러운 쪽으로 네가 판단한다.
			- 답은 JSON {"hashtag": "..."} 형태만 낸다. 설명·부연·다른 필드를 넣지 않는다.
			- '#'을 붙이지 않는다. 공백·특수문자·이모지를 넣지 않는다.

			예시:
			- 표시명 "닥터피엘 Dr.PIEL", 계정명 "dr.piel_official" → {"hashtag": "닥터피엘"}
			- 표시명 "", 계정명 "dr.piel_official" → {"hashtag": "drpiel"}
			- 표시명 "", 계정명 "cclime_official" → {"hashtag": "cclime"}
			""";

	/** 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 이 밖은 제거 대상이다. */
	private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N}_]");
	private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");
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
	 * @param fullName IG 표시명(`brand_account.full_name`). null·공백이면 계정명만으로 진행한다.
	 * @param username IG 계정명. null·공백이면 호출 없이 빈 값(도달 불가 — 방어).
	 * @param stoplist 제외 태그(전부 소문자).
	 * @return 정리된 태그(소문자). 미설정·정리 결과 무효는 빈 값. 전송·파싱 실패는 예외.
	 */
	public Optional<String> suggest(String fullName, String username, Set<String> stoplist) {
		if (!enabled) {
			log.debug("Gemini 미설정 — 표시명 해시태그 제안 건너뜀");
			return Optional.empty();
		}
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent",
				requestBody(fullName, username));
		return clean(parse(responseBody), stoplist);
	}

	private String requestBody(String fullName, String username) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		root.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text",
						"표시명: " + (fullName == null ? "" : fullName) + "\n계정명: " + username);
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

	/** §3-3 출력 정리 — 제거·절단으로 살려내고, 살릴 수 없을 때만 빈 값이다. */
	private Optional<String> clean(String raw, Set<String> stoplist) {
		String tag = raw == null ? "" : raw.strip();
		if (tag.startsWith("#")) {
			tag = tag.substring(1);
		}
		tag = NOT_ALLOWED.matcher(tag.strip().toLowerCase(Locale.ROOT)).replaceAll("");
		if (tag.length() > MAX_LENGTH) {
			tag = tag.substring(0, MAX_LENGTH);
		}
		if (tag.isEmpty()) {
			log.warn("AI 제안 해시태그 정리 결과 없음 — value={}", abbreviate(raw));
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
feat(monitoring): IG 표시명 기반 해시태그 AI 제안

표시명(full_name)과 계정명만으로 브랜드 상호 해시태그 1개를 받는다. 회사명·
바이오는 입력하지 않는다. 광고 표기 판정과 같은 GeminiHttp seam·모델 설정을
재사용하고 temperature 0으로 JSON 1필드만 받는다.
출력은 버리지 않고 정리한다(# 제거·소문자·허용 외 문자 제거·30자 절단) —
결과가 비거나 순수 숫자거나 stoplist일 때만 빈 값으로 접어 상위가 FALLBACK한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 5 — 3단 계산 (`BrandHashtagSuggestionService`) + 빈 배선

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSuggestionService.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSuggestionConfig.java`
- Create: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagSuggestionServiceTest.java`

> **DB에 쓰지 않는다.** `brand_hashtag`를 읽지도 쓰지도 않는다 — "이미 태그가 있는 브랜드인가"는
> was가 `commandClient.getHashtagTags`로 판정한다(§4-2). 여기는 순수 계산 + 응답 조립뿐이다.
>
> **`tag`는 절대 비지 않는다**가 계약이다. FREQ 실패든 AI 실패든 마지막엔 계정명 정리값이 남는다.

### Steps

- [ ] **실패 테스트 작성** — `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagSuggestionServiceTest.java`

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.config.BrandHashtagSeedSettings;
import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 해시태그 제안 3단 계산(2026-09-03 자동 시드 재설계 §3) — FREQ → AI → FALLBACK. 임계 경계·
 * ai-enabled 킬 스위치·각 단계 실패의 하향 수렴을 고정하고, 무엇보다 <b>응답 tag가 절대 비지
 * 않는다</b>는 계약을 봉인한다.
 */
class BrandHashtagSuggestionServiceTest {

	private static final long BRAND_ID = 1L;
	private static final String USERNAME = "dr.piel_official";
	private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	private static final class StubTaggedPosts extends TaggedPostRepository {
		List<TaggedCaption> captions = List.of();
		boolean failing;

		StubTaggedPosts() {
			super(null);
		}

		@Override
		public List<TaggedCaption> findCaptionsForSeed(long brandId) {
			if (failing) {
				throw new IllegalStateException("DB 장애 주입");
			}
			return captions;
		}
	}

	private static final class StubBrands extends BrandRepository {
		String fullName;

		StubBrands() {
			super(null);
		}

		@Override
		public Optional<String> findFullName(long brandId) {
			return Optional.ofNullable(fullName);
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

	private final StubTaggedPosts taggedPosts = new StubTaggedPosts();
	private final StubBrands brands = new StubBrands();
	private final StubAppSettings appSettings = new StubAppSettings();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final List<String> llmCalls = new ArrayList<>();

	private String llmResponse = geminiBody("{\"hashtag\": \"닥터피엘\"}");
	private RuntimeException llmFailure;

	private static String geminiBody(String innerJson) {
		String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
		return """
				{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}""".formatted(escaped);
	}

	private BrandHashtagSuggestionService service() {
		var suggester = new BrandHashtagSuggester((path, body) -> {
			llmCalls.add(body);
			if (llmFailure != null) {
				throw llmFailure;
			}
			return llmResponse;
		}, true, "model-x");
		return new BrandHashtagSuggestionService(taggedPosts, brands, suggester,
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
		var counter = registry.find("brand.hashtag.suggest")
				.tag("path", path).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}

	// ---------- FREQ ----------

	@Test
	void 최다_태그가_임계_이상이면_FREQ다() {
		taggedPosts.captions = repeated("#닥피 #뷰티", 7);

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("FREQ");
		assertThat(out.tag()).isEqualTo("닥피");
		assertThat(out.topCount()).isEqualTo(7);
		assertThat(out.candidatePosts()).isEqualTo(7);
		assertThat(llmCalls).isEmpty();
		assertThat(counted("freq", "ok")).isEqualTo(1);
	}

	@Test
	void 임계_미만이면_AI로_내려간다() {
		taggedPosts.captions = repeated("#닥피", 6);
		brands.fullName = "닥터피엘 Dr.PIEL";

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("AI");
		assertThat(out.tag()).isEqualTo("닥터피엘");
		// topCount·candidatePosts는 AI로 내려가도 관측값을 그대로 싣는다(운영 판단 재료).
		assertThat(out.topCount()).isEqualTo(6);
		assertThat(out.candidatePosts()).isEqualTo(6);
		assertThat(counted("ai", "ok")).isEqualTo(1);
	}

	@Test
	void 임계는_설정으로_바뀐다() {
		appSettings.values.put("brand.hashtag-seed.min-posts", "3");
		taggedPosts.captions = repeated("#닥피", 3);

		assertThat(service().suggest(BRAND_ID, USERNAME).path()).isEqualTo("FREQ");
	}

	@Test
	void stoplist_태그는_최다여도_FREQ가_되지_않는다() {
		appSettings.values.put("brand.hashtag-seed.stoplist", "협찬");
		taggedPosts.captions = repeated("#협찬", 20);
		brands.fullName = "닥터피엘";

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("AI");
		assertThat(out.topCount()).isZero();
	}

	@Test
	void 태그된_게시물이_없으면_AI_경로다() {
		taggedPosts.captions = List.of();
		brands.fullName = "닥터피엘";

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("AI");
		assertThat(out.candidatePosts()).isZero();
	}

	// ---------- FALLBACK ----------

	@Test
	void ai_enabled가_false면_AI를_부르지_않고_FALLBACK이다() {
		appSettings.values.put("brand.hashtag-seed.ai-enabled", "false");
		taggedPosts.captions = repeated("#닥피", 2);

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo("drpielofficial");
		assertThat(llmCalls).isEmpty();
		assertThat(counted("fallback", "ok")).isEqualTo(1);
	}

	@Test
	void AI_정리_결과가_비면_FALLBACK이다() {
		llmResponse = geminiBody("{\"hashtag\": \"!!!\"}");
		brands.fullName = "닥터피엘";

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo("drpielofficial");
		assertThat(counted("fallback", "ok")).isEqualTo(1);
	}

	@Test
	void AI_전송_실패는_FALLBACK으로_수렴하고_지표에_error로_남는다() {
		llmFailure = new IllegalStateException("전송 실패");

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo("drpielofficial");
		assertThat(counted("fallback", "error")).isEqualTo(1);
	}

	@Test
	void 빈도_집계_DB_실패도_응답을_막지_않는다() {
		taggedPosts.failing = true;
		brands.fullName = "닥터피엘";

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("AI");
		assertThat(out.tag()).isEqualTo("닥터피엘");
		assertThat(out.topCount()).isZero();
		assertThat(counted("ai", "error")).isEqualTo(1);
	}

	@Test
	void 계정명_정리는_점과_언더스코어를_뺀_소문자다() {
		assertThat(BrandHashtagSuggestionService.fallbackTag("dr.piel_official")).isEqualTo("drpielofficial");
		assertThat(BrandHashtagSuggestionService.fallbackTag("CClime_Official")).isEqualTo("cclimeofficial");
		assertThat(BrandHashtagSuggestionService.fallbackTag("끌리메")).isEqualTo("끌리메");
	}

	/** 계정명이 언더스코어뿐인 극단 케이스 — 언더스코어는 유효 태그 문자라 그것만 남긴다. */
	@Test
	void 계정명이_언더스코어뿐이면_언더스코어를_남긴다() {
		assertThat(BrandHashtagSuggestionService.fallbackTag("___")).isEqualTo("___");
	}

	/** 어떤 입력에서도 응답 tag는 비지 않는다(§3-1 계약). */
	@Test
	void 모든_경로에서_tag는_비지_않는다() {
		llmFailure = new IllegalStateException("전송 실패");
		taggedPosts.failing = true;

		var out = service().suggest(BRAND_ID, "a");

		assertThat(out.tag()).isNotBlank();
	}
}
```

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagSuggestionServiceTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/2** — `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSuggestionService.java`

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.config.BrandHashtagSeedSettings;
import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 제안 계산(2026-09-03 자동 시드 재설계 §3) — 계정명 문자열 절삭(2026-08-17~)을
 * 대체한다. <b>DB에 쓰지 않는다</b>: 저장은 was가 전담한다(08-28 "태그 생성 권한 was 일원화" 유지).
 * {@code brand_hashtag}를 읽지도 않는다 — "이미 태그가 있는 브랜드인가"는 was가 태그 GET으로
 * 판정한다(§4-2).
 *
 * <p>3단으로 내려간다:
 * <ol>
 *   <li><b>FREQ</b> — 그 브랜드에 태그된 게시물 캡션의 해시태그 빈도. 최다 태그의 등장 게시물 수가
 *       {@code min-posts}(기본 7) 이상일 때.</li>
 *   <li><b>AI</b> — IG 표시명 + 계정명으로 상호 해시태그 1개({@link BrandHashtagSuggester}).</li>
 *   <li><b>FALLBACK</b> — 계정명에서 점·언더스코어를 뺀 소문자({@link #fallbackTag}).</li>
 * </ol>
 *
 * <p><b>{@code tag}는 절대 비지 않는다</b>(§3-1 계약). 각 단계의 예외는 격리하고 아래 단계로
 * 내려가며, 마지막 FALLBACK은 계정명만 있으면 항상 값을 만든다. 상태를 저장하지 않고 AI가
 * temperature 0이라 같은 입력엔 같은 답을 낸다.
 */
public class BrandHashtagSuggestionService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSuggestionService.class);

	/** 태그: path(freq|ai|fallback) · result(ok|error). */
	static final String METRIC = "brand.hashtag.suggest";

	/** 글자·숫자만 남긴다 — 점과 언더스코어가 함께 사라진다(§3-4의 "점·언더스코어 제거"). */
	private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
	/** 위 결과가 비었을 때의 2차 정리 — 언더스코어는 유효 태그 문자라 살린다. */
	private static final Pattern NOT_TAG_CHAR = Pattern.compile("[^\\p{L}\\p{N}_]");

	/** 제안 1건(§3-1 응답 본문). tag는 항상 비어 있지 않다. */
	public record Suggestion(String path, String tag, int topCount, int candidatePosts) {
	}

	private final TaggedPostRepository taggedPosts;
	private final BrandRepository brands;
	private final BrandHashtagSuggester suggester;
	private final BrandHashtagSeedSettings settings;
	private final MeterRegistry registry;

	public BrandHashtagSuggestionService(TaggedPostRepository taggedPosts, BrandRepository brands,
			BrandHashtagSuggester suggester, BrandHashtagSeedSettings settings, MeterRegistry registry) {
		this.taggedPosts = taggedPosts;
		this.brands = brands;
		this.suggester = suggester;
		this.settings = settings;
		this.registry = registry;
	}

	/**
	 * 이 브랜드에 심을 해시태그 1개를 계산한다. 예외를 던지지 않는다.
	 *
	 * @param brandId  monitoring {@code brand_account.id}
	 * @param username IG 계정명 — FALLBACK의 재료라 반드시 있어야 한다.
	 */
	public Suggestion suggest(long brandId, String username) {
		Set<String> stoplist = settings.stoplist();
		int topCount = 0;
		int candidatePosts = 0;
		String freqTag = null;
		boolean degraded = false;
		try {
			List<TaggedCaption> captions = taggedPosts.findCaptionsForSeed(brandId);
			candidatePosts = captions.size();
			List<HashtagCandidateExtractor.Candidate> candidates =
					HashtagCandidateExtractor.extract(captions, stoplist);
			if (!candidates.isEmpty()) {
				topCount = candidates.getFirst().postCount();
				if (topCount >= settings.minPosts()) {
					freqTag = candidates.getFirst().tag();
				}
			}
		} catch (RuntimeException e) {
			log.warn("해시태그 제안 빈도 집계 실패(격리, AI로 내려간다) — username={}: {}", username, e.toString());
			degraded = true;
		}
		if (freqTag != null) {
			return respond("FREQ", freqTag, topCount, candidatePosts, username, degraded);
		}
		if (settings.aiEnabled()) {
			try {
				String fullName = brands.findFullName(brandId).orElse(null);
				Optional<String> aiTag = suggester.suggest(fullName, username, stoplist);
				if (aiTag.isPresent()) {
					return respond("AI", aiTag.get(), topCount, candidatePosts, username, degraded);
				}
			} catch (RuntimeException e) {
				log.warn("해시태그 제안 AI 실패(격리, FALLBACK으로 내려간다) — username={}: {}",
						username, e.toString());
				degraded = true;
			}
		}
		return respond("FALLBACK", fallbackTag(username), topCount, candidatePosts, username, degraded);
	}

	/**
	 * 최종 안전장치(§3-4) — 계정명에서 점·언더스코어를 빼고 소문자화한다
	 * ({@code dr.piel_official} → {@code drpielofficial}).
	 *
	 * <p>계정명이 점·언더스코어뿐인 극단 케이스에서는 언더스코어를 살려 값을 만들고(언더스코어는
	 * 유효 태그 문자다), 그래도 비면 브랜드 식별자로 만든다 — IG 계정명 규칙상 도달 불가지만
	 * "tag는 절대 비지 않는다"는 계약을 코드에서 닫아 둔다.
	 */
	static String fallbackTag(String username) {
		String lower = username.toLowerCase(Locale.ROOT);
		String stripped = NOT_LETTER_OR_DIGIT.matcher(lower).replaceAll("");
		if (!stripped.isEmpty()) {
			return stripped;
		}
		String withUnderscore = NOT_TAG_CHAR.matcher(lower).replaceAll("");
		return withUnderscore.isEmpty() ? "brand" : withUnderscore;
	}

	/** 응답 1건 = 로그 1줄 + 카운터 1증가(§3-6). degraded는 "일부 계산이 예외로 실패했다"는 표식이다. */
	private Suggestion respond(String path, String tag, int topCount, int candidatePosts,
			String username, boolean degraded) {
		log.info("브랜드 해시태그 제안 — username={}, path={}, tag={}, topCount={}, candidatePosts={}",
				username, path, tag, topCount, candidatePosts);
		count(path.toLowerCase(Locale.ROOT), degraded ? "error" : "ok");
		return new Suggestion(path, tag, topCount, candidatePosts);
	}

	/** 지표 기록 실패는 삼킨다(MicrometerInstagramSourceMetrics 관용구) — 관측이 본류를 깨지 않는다. */
	private void count(String path, String result) {
		try {
			Counter.builder(METRIC).tag("path", path).tag("result", result).register(registry).increment();
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 제안 지표 기록 실패(무시) — {} {}: {}", path, result, e.toString());
		}
	}
}
```

- [ ] **최소 구현 2/2** — `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagSuggestionConfig.java`

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.service.BrandHashtagSuggestionService;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 제안 배선(2026-09-03 자동 시드 재설계) — {@link AdDisclosureConfig}와 같은 조립
 * 패턴이다. 전송({@link GeminiHttp})·활성 여부는 {@link LlmTransportConfig}가 조립한 공유 빈을
 * 그대로 쓴다(새 HTTP 클라이언트를 만들지 않는다).
 *
 * <p>전용 executor는 두지 않는다 — 제안은 was의 내부 GET 1건 안에서 동기로 끝나는 브랜드 생애
 * 1회짜리 계산이고, 그 호출부(was 훅)가 이미 best-effort로 격리돼 있다.
 */
@Configuration
public class BrandHashtagSuggestionConfig {

	@Bean
	public BrandHashtagSuggester brandHashtagSuggester(GeminiHttp geminiHttp,
			LlmTransportConfig.LlmEnabled llmEnabled,
			@Value("${monitoring.brand.hashtag-seed.model:gemini-3.1-flash-lite}") String model) {
		return new BrandHashtagSuggester(geminiHttp, llmEnabled.value(), model);
	}

	@Bean
	public BrandHashtagSuggestionService brandHashtagSuggestionService(TaggedPostRepository taggedPosts,
			BrandRepository brands, BrandHashtagSuggester suggester,
			BrandHashtagSeedSettings settings, MeterRegistry meterRegistry) {
		return new BrandHashtagSuggestionService(taggedPosts, brands, suggester, settings, meterRegistry);
	}
}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagSuggestionServiceTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 해시태그 제안 3단 계산

FREQ(태그된 게시물 캡션 빈도, 임계 기본 7) → AI(IG 표시명+계정명) →
FALLBACK(계정명에서 점·언더스코어 제거)으로 내려가며, 어느 단계가 예외로
죽어도 응답 tag는 절대 비지 않는다. DB 쓰기는 없고 brand_hashtag를 읽지도
않는다(태그 존재 판정은 was 몫). 관측은 info 로그 1줄 + Micrometer
brand.hashtag.suggest(path·result).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 6 — monitoring 제안 엔드포인트

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java` (106~121행 필드·생성자, `hashtagTags` GET 뒤에 엔드포인트 추가)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java` (스텁 + 생성자 + 검증)

### Steps

- [ ] **실패 테스트 작성** — `BrandControllerTest`의 `StubLegacyHistoryCopier` 선언 뒤에 스텁을 넣고,

```java
	/** 제안 계산 스텁(2026-09-03 자동 시드 재설계) — 판정은 BrandHashtagSuggestionServiceTest가 본다. */
	private static final class StubSuggestion extends BrandHashtagSuggestionService {
		Suggestion result = new Suggestion("FREQ", "닥피", 12, 40);
		Long receivedBrandId;
		String receivedUsername;

		StubSuggestion() {
			super(null, null, null, null, null);
		}

		@Override
		public Suggestion suggest(long brandId, String username) {
			receivedBrandId = brandId;
			receivedUsername = username;
			return result;
		}
	}
```

  필드 선언부(`legacyHistoryCopier` 뒤)에 `private final StubSuggestion suggestion = new StubSuggestion();`
  를 추가하고, `setUp()`의 생성자 호출을 다음으로 바꾼다.

```java
		mvc = MockMvcBuilders.standaloneSetup(new BrandController(service, brands, hashtags, taggedPosts,
						directCollect, legacyHistoryCopier, suggestion))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
```

  그리고 검증을 추가한다(import에 `com.celfit.monitoring.service.BrandHashtagSuggestionService` 필요).

```java
	// ---------- 해시태그 제안(2026-09-03 자동 시드 재설계 §3-1) ----------

	@Test
	void 해시태그_제안은_계산_결과를_그대로_돌려준다() throws Exception {
		brands.rows.put("brandx", new BrandRow(7L, "brandx", "1", BrandStatus.ACTIVE, null, 12, true));
		suggestion.result = new BrandHashtagSuggestionService.Suggestion("AI", "닥터피엘", 3, 40);

		mvc.perform(get("/api/brands/brandx/hashtag-suggestion"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.path").value("AI"))
				.andExpect(jsonPath("$.tag").value("닥터피엘"))
				.andExpect(jsonPath("$.topCount").value(3))
				.andExpect(jsonPath("$.candidatePosts").value(40));

		assertThat(suggestion.receivedBrandId).isEqualTo(7L);
		assertThat(suggestion.receivedUsername).isEqualTo("brandx");
	}

	@Test
	void 미등록_브랜드의_제안은_404다() throws Exception {
		mvc.perform(get("/api/brands/nobody/hashtag-suggestion"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void 비ACTIVE_브랜드의_제안은_404다() throws Exception {
		brands.rows.put("closed", new BrandRow(8L, "closed", "1", BrandStatus.CLOSED, null, 12, true));

		mvc.perform(get("/api/brands/closed/hashtag-suggestion"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}
```

  > `brands`는 이 테스트 클래스의 기존 `BrandRepository` 스텁이다. 브랜드 행을 심는 실제 헬퍼
  > 이름·형태(위 예시의 `brands.rows.put(...)`)는 파일을 열어 확인하고 그 관용구에 맞춘다 —
  > 다른 404 테스트(`탈퇴는_미등록이면_404다` 등)가 쓰는 방식을 그대로 재사용하면 된다.

- [ ] **실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.web.BrandControllerTest"` 가 컴파일 실패(생성자 인자 수 불일치)로 끝나는 것을 확인한다.

- [ ] **최소 구현** — `BrandController`의 필드·생성자에 서비스를 추가하고,

```java
	private final BrandLegacyHistoryCopier legacyHistoryCopier;
	/** 해시태그 제안 계산(2026-09-03 자동 시드 재설계 §3) — 쓰기 없음, 순수 계산. */
	private final BrandHashtagSuggestionService suggestions;

	public BrandController(BrandRegistrationService service, BrandRepository brands,
			BrandHashtagRepository hashtags, TaggedPostRepository taggedPosts,
			BrandDirectCollectService directCollect, BrandLegacyHistoryCopier legacyHistoryCopier,
			BrandHashtagSuggestionService suggestions) {
		this.service = service;
		this.brands = brands;
		this.hashtags = hashtags;
		this.taggedPosts = taggedPosts;
		this.directCollect = directCollect;
		this.legacyHistoryCopier = legacyHistoryCopier;
		this.suggestions = suggestions;
	}
```

  `hashtagTags` GET(활성 태그 조회) 바로 뒤에 엔드포인트를 넣는다.
  import에 `com.celfit.monitoring.service.BrandHashtagSuggestionService`를 추가한다.

```java
	/**
	 * 해시태그 자동 시드 제안(2026-09-03 자동 시드 재설계 §3-1) — 이 브랜드에 심을 태그 1개를
	 * <b>계산해서 돌려주기만</b> 한다. monitoring은 {@code brand_hashtag}에 아무것도 쓰지 않는다
	 * (08-28 "태그 생성 권한 was 일원화" 유지) — 저장·중복 방지·사용자 장부 반영은 전부 was 몫이다.
	 *
	 * <p>{@code tag}는 항상 비어 있지 않다(FREQ → AI → FALLBACK 3단). 상태를 저장하지 않고 AI가
	 * temperature 0이라 같은 입력엔 같은 답이 나온다. <b>백필 완료 여부는 검사하지 않는다</b> —
	 * 호출 시점 게이트는 was 책임이다(§4-2). 브랜드 미존재·비ACTIVE는 404(태그 GET과 동형).
	 */
	@GetMapping("/{username}/hashtag-suggestion")
	public ResponseEntity<?> hashtagSuggestion(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(suggestions.suggest(row.get().id(), row.get().username()));
	}
```

- [ ] **통과 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.web.BrandControllerTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **모듈 회귀 확인** — `./gradlew :monitoring:test` 가 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(monitoring): 해시태그 제안 조회 API

GET /api/brands/{username}/hashtag-suggestion — {path, tag, topCount,
candidatePosts}를 돌려준다. 계산만 하고 DB에는 쓰지 않는다(태그 생성 권한
was 일원화 유지). 브랜드 미존재·비ACTIVE는 태그 GET과 동형 404(코드 바디
포함 — 빈 바디 404는 was가 503으로 오승격한다).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 7 — was 저장 계층 (시드 기록 + 링크 표식)

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<UTC>__brand_hashtag_seed.sql`
- Create: `was/src/main/java/com/celfit/was/monitoring/BrandHashtagSeedRepository.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java` (record 컴포넌트 추가)
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java` (`SELECT_COLUMNS` 20~21행, `markHashtagSeeded` 추가)
- Create: `was/src/test/java/com/celfit/was/monitoring/BrandHashtagSeedRepositoryTest.java`
- Modify: was 테스트 12파일의 `new BrandLinkRow(...)` 24곳

> **record 확장은 한 커밋에 끝낸다.** 프로덕션 생성은 `BrandLinkRepository`의 `query(BrandLinkRow.class)`
> 매핑뿐이라 `SELECT_COLUMNS`에 컬럼 하나만 더하면 되고, 나머지는 전부 테스트 픽스처다.
> 컴파일러가 24곳을 전부 짚어 준다 — 놓칠 수 없다.

### Steps

- [ ] **마이그레이션 채번** — `date -u +%Y%m%d%H%M%S` 로 UTC 타임스탬프를 얻는다. **monitoring과 별개 버전 공간이므로 Task 1과 같은 값이어도 무방하지만, 지금 시각으로 새로 뽑는다.**

- [ ] **마이그레이션 작성** — `was/src/main/resources/db/migration/app/V<위 값>__brand_hashtag_seed.sql`

```sql
-- 브랜드 해시태그 자동 시드 기록(2026-09-03 자동 시드 재설계 §4-1).
-- 계산은 브랜드당 1회(이 테이블), 사용자 장부 삽입은 사용자당 1회(brand_monitorings.hashtag_seeded_at).
-- path = FREQ|AI|FALLBACK|SKIP. SKIP은 "이미 사용자 관리 태그가 있어 자동 태그를 얹지 않았다"이고
-- 그때만 tag가 NULL이다. brand_id는 monitoring brand_account.id 논리 참조(크로스 DB FK 없음).
-- 전부 additive — 롤링 중 구 코드는 이 테이블·컬럼을 모른 채 그대로 돈다.
CREATE TABLE app.brand_hashtag_seed (
    brand_id  bigint PRIMARY KEY,
    path      text NOT NULL,
    tag       text,
    seeded_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app.brand_monitorings ADD COLUMN hashtag_seeded_at timestamptz;
```

- [ ] **실패 테스트 작성** — `was/src/test/java/com/celfit/was/monitoring/BrandHashtagSeedRepositoryTest.java`

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app.brand_hashtag_seed 접점(2026-09-03 자동 시드 재설계 §4-1) — BrandHashtagTagRepositoryTest와
 * 같은 통합 관용구. 브랜드당 1행 계약(동시 호출도 1행)과 SKIP(tag NULL) 저장을 실 왕복으로 고정한다.
 */
class BrandHashtagSeedRepositoryTest extends IntegrationTest {

	@Autowired
	BrandHashtagSeedRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없다.
	long brandId;

	@BeforeEach
	void 브랜드_식별자() {
		brandId = System.nanoTime();
	}

	@Test
	void 없으면_empty다() {
		assertThat(repository.find(brandId)).isEmpty();
	}

	@Test
	void 삽입_후_조회된다() {
		repository.insertIgnore(brandId, "FREQ", "닥피");

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.brandId()).isEqualTo(brandId);
			assertThat(row.path()).isEqualTo("FREQ");
			assertThat(row.tag()).isEqualTo("닥피");
			assertThat(row.seededAt()).isNotNull();
		});
	}

	@Test
	void SKIP은_tag가_null이다() {
		repository.insertIgnore(brandId, "SKIP", null);

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.path()).isEqualTo("SKIP");
			assertThat(row.tag()).isNull();
		});
	}

	/** 동시 호출 경합 — 두 번째 삽입은 조용히 무시되고 첫 값이 남는다(브랜드당 1회 계약). */
	@Test
	void 재삽입은_무시되고_첫_값이_남는다() {
		repository.insertIgnore(brandId, "FREQ", "첫값");
		repository.insertIgnore(brandId, "AI", "둘째값");

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.path()).isEqualTo("FREQ");
			assertThat(row.tag()).isEqualTo("첫값");
		});
	}

	@Test
	void 다른_브랜드는_영향이_없다() {
		repository.insertIgnore(brandId, "FREQ", "내태그");

		assertThat(repository.find(brandId + 1)).isEmpty();
	}

	// ---------- 링크 표식(brand_monitorings.hashtag_seeded_at) ----------

	@Autowired
	BrandLinkRepository linkRepository;

	@Test
	void markHashtagSeeded는_링크_행에_시각을_찍는다() {
		long userId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "seed-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		long linkId = linkRepository.insertLink(userId, brandId, "brandx", "own", 12);
		assertThat(linkRepository.findActiveByUserAndBrand(userId, brandId))
				.hasValueSatisfying(link -> assertThat(link.hashtagSeededAt()).isNull());

		linkRepository.markHashtagSeeded(linkId);

		assertThat(linkRepository.findActiveByUserAndBrand(userId, brandId))
				.hasValueSatisfying(link -> assertThat(link.hashtagSeededAt()).isNotNull());
	}
}
```

- [ ] **실패 확인** — `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 후 `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandHashtagSeedRepositoryTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/4 (`BrandHashtagSeedRepository`)** — `was/src/main/java/com/celfit/was/monitoring/BrandHashtagSeedRepository.java`

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 브랜드 해시태그 자동 시드 기록(2026-09-03 자동 시드 재설계 §4-1) — app.brand_hashtag_seed.
 * <b>브랜드당 1행</b>이 계약이다: monitoring 제안 계산(AI 콜 포함)을 브랜드 생애 1회로 묶고,
 * 두 번째 사용자가 같은 브랜드에 링크하면 계산 없이 이 행의 태그를 자기 장부에 복사한다.
 *
 * <p>{@code brand_id}는 monitoring {@code brand_account.id} 논리 참조다(크로스 DB FK 없음 —
 * {@code BrandLinkRepository}와 같은 관용구).
 */
@Repository
public class BrandHashtagSeedRepository {

	/** 시드 1행. path가 SKIP이면 tag는 null이다(이미 사용자 태그가 있어 아무것도 심지 않은 브랜드). */
	public record SeedRow(long brandId, String path, String tag, OffsetDateTime seededAt) {
	}

	private final JdbcClient jdbcClient;

	public BrandHashtagSeedRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<SeedRow> find(long brandId) {
		return jdbcClient.sql("""
				SELECT brand_id, path, tag, seeded_at FROM app.brand_hashtag_seed WHERE brand_id = :brandId
				""")
				.param("brandId", brandId)
				.query(SeedRow.class)
				.optional();
	}

	/**
	 * 시드 기록 — 이미 있으면 조용히 무시한다(ON CONFLICT DO NOTHING). 두 조회가 동시에 같은
	 * 브랜드를 계산해도 먼저 커밋한 쪽이 이기고, 진 쪽은 호출부가 재조회해 그 값을 쓴다
	 * (호출부 {@code V1BrandAccountService.ensureAutoSeeded}의 "INSERT 후 재조회" 관용구).
	 *
	 * @param tag SKIP이면 null.
	 */
	public void insertIgnore(long brandId, String path, String tag) {
		jdbcClient.sql("""
				INSERT INTO app.brand_hashtag_seed (brand_id, path, tag)
				VALUES (:brandId, :path, :tag)
				ON CONFLICT (brand_id) DO NOTHING
				""")
				.param("brandId", brandId)
				.param("path", path)
				.param("tag", tag)
				.update();
	}
}
```

- [ ] **최소 구현 2/4 (`BrandLinkRow`)** — record에 컴포넌트를 추가하고 javadoc에 문단을 더한다.

```java
/**
 * ... (기존 javadoc 유지) ...
 *
 * <p>{@code hashtagSeededAt}(2026-09-03 자동 시드 재설계 §4-1) — 이 <b>링크</b>에 자동 태그가
 * 반영된 시각. NULL이면 아직 미반영이라 다음 조회에서 훅({@code ensureAutoSeeded})이 돈다.
 * 브랜드 단위 계산 기록({@code app.brand_hashtag_seed})과 짝이다 — 계산은 브랜드당 1회,
 * 장부 삽입은 사용자당 1회. 이 값이 찍혀 있으면 사용자가 자동 태그를 지운 뒤 다시 조회해도
 * 되살아나지 않는다.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username, String accountType,
		int collectionMonths, OffsetDateTime createdAt, OffsetDateTime deletedAt,
		OffsetDateTime hashtagSeededAt) {
}
```

- [ ] **최소 구현 3/4 (`BrandLinkRepository`)** — `SELECT_COLUMNS`(20~21행)에 컬럼을 더하고,

```java
	private static final String SELECT_COLUMNS =
			"id, user_id, brand_id, username, account_type, collection_months, created_at, deleted_at, "
					+ "hashtag_seeded_at";
```

  `insertLink`(99행) 뒤에 표식 갱신을 추가한다.

```java
	/**
	 * 자동 태그 반영 표식(2026-09-03 자동 시드 재설계 §4-2) — 이 링크에 자동 태그를 장부에 넣었거나
	 * 넣을 것이 없다고 판정한 시점을 찍는다. 이미 찍혀 있으면 갱신하지 않는다(IS NULL 가드) —
	 * 최초 반영 시각이 밀리면 "언제부터 사용자에게 보였나"를 잃는다.
	 */
	public void markHashtagSeeded(long linkId) {
		jdbcClient.sql("""
				UPDATE app.brand_monitorings SET hashtag_seeded_at = now()
				WHERE id = :id AND hashtag_seeded_at IS NULL
				""")
				.param("id", linkId)
				.update();
	}
```

- [ ] **최소 구현 4/4 (테스트 픽스처 24곳)** — `./gradlew :was:compileTestJava` 를 돌려 나오는
  `new BrandLinkRow(...)` 호출마다 **마지막 인자 뒤에 `, null`을 덧붙인다**(= 아직 시드 안 됨).
  대상 파일 체크리스트(조사 시점 24곳):

```
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java   (1곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java          (1곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandInfluencersControllerTest.java       (1곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceWithdrawalTest.java    (1곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java             (2곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandDirectPostServiceTest.java           (1곳)
was/src/test/java/com/celfit/was/v1/brandmonitoring/ai/V1BrandAiMessagesControllerTest.java     (1곳)
was/src/test/java/com/celfit/was/v1/admin/AdminCrawlingUsageServiceTest.java                    (2곳)
was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java       (1곳)
was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java         (11곳)
was/src/test/java/com/celfit/was/v1/perfdashboard/DashboardVersionTest.java                     (1곳)
was/src/test/java/com/celfit/was/v2/monitoring/V2CampaignContentServiceTest.java                (1곳)
```

  > 여러 줄에 걸친 호출이 있어 일괄 sed는 안전하지 않다 — 컴파일 오류가 가리키는 위치마다 손으로
  > 고치고, 마지막에 `grep -rn "new BrandLinkRow(" was/src/test | wc -l` 로 24를 확인한다.

- [ ] **통과 확인** — `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandHashtagSeedRepositoryTest"` 가 전부 통과하는 것을 확인한다.

- [ ] **회귀 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` 와 `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 통과하는 것을 확인한다(픽스처 24곳의 여파 확인).

- [ ] **커밋**

```
feat(was): 브랜드 해시태그 자동 시드 저장 계층

app.brand_hashtag_seed(브랜드당 1행: path·tag·seeded_at)와
brand_monitorings.hashtag_seeded_at(링크별 반영 표식)을 additive로 추가한다.
계산은 브랜드당 1회, 장부 삽입은 사용자당 1회로 묶는 짝이다.
BrandLinkRow에 hashtagSeededAt이 붙어 테스트 픽스처 24곳이 함께 갱신됐다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 8 — was → monitoring 제안 조회 클라이언트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java` (`getHashtagRunStates` 뒤에 메서드 + 하단 record 블록에 body record)
- Test: 기존 `was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java`(있으면) 또는 Task 10의 서비스 테스트가 mock으로 덮는다.

### Steps

- [ ] **클라이언트 테스트 존재 확인** — 아래로 `MonitoringCommandClient`의 기존 테스트가 있는지 본다.

```
ls was/src/test/java/com/celfit/was/monitoring/ | grep -i commandclient
```

  있으면 `getHashtagRunStates`를 검증하는 테스트와 같은 관용구로 `getHashtagSuggestion` 테스트를
  하나 추가한다(MockRestServiceServer 또는 그 파일이 쓰는 방식 그대로). 없으면 이 Task는 구현만
  하고, 계약 검증은 Task 10의 서비스 테스트(mock 반환값)가 담당한다.

- [ ] **구현** — `getHashtagRunStates` 바로 뒤에 메서드를 추가한다.

```java
	/**
	 * 해시태그 자동 시드 제안 조회(2026-09-03 자동 시드 재설계 §3-1, monitoring BrandController
	 * hashtag-suggestion) — monitoring이 계산만 해서 돌려주는 값이다(그쪽은 아무것도 쓰지 않는다).
	 * {@code tag}는 항상 비어 있지 않다(FREQ → AI → FALLBACK 3단).
	 *
	 * <p>404(BRAND_NOT_FOUND)는 다른 브랜드 조회 경로와 동형으로 MonitoringApiException으로 승격된다 —
	 * 호출부({@code V1BrandAccountService.ensureAutoSeeded})가 best-effort로 격리한다.
	 */
	public HashtagSuggestionBody getHashtagSuggestion(String username) {
		return exchange(() -> restClient.get()
				.uri("/api/brands/{username}/hashtag-suggestion", username)
				.retrieve().body(HashtagSuggestionBody.class));
	}
```

- [ ] **응답 record 추가** — 이 파일 하단의 record 블록(`HashtagTagsBody`·`TagRunState` 등이 모인 곳)에 넣는다.

```java
	/**
	 * 제안 응답(§3-1) — path는 FREQ|AI|FALLBACK, tag는 항상 비어 있지 않다.
	 * topCount·candidatePosts는 운영 판단 재료(FALLBACK 비율이 높으면 AI 경로가 죽은 것이다)라
	 * 저장하지 않고 로그·검토용으로만 쓴다. Integer인 이유는 필드 누락 응답에서 NPE가 아니라
	 * null로 들어오게 하기 위함이다.
	 */
	public record HashtagSuggestionBody(String path, String tag, Integer topCount, Integer candidatePosts) {
	}
```

- [ ] **컴파일 확인** — `./gradlew :was:compileJava` 가 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(was): monitoring 해시태그 제안 조회 클라이언트

GET /api/brands/{username}/hashtag-suggestion를 부르는
MonitoringCommandClient.getHashtagSuggestion 추가. 응답 record는
{path, tag, topCount, candidatePosts}이고 404는 기존 브랜드 조회 경로와
동형으로 MonitoringApiException으로 승격된다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 9 — was 계정명 절삭 유도 규칙 삭제

**Files:**
- Delete: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java`
- Delete: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (142행 호출 + 149~186행 `seedLedgerTagsSafely` 삭제)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCaptionHashtags.java` (9~13행 javadoc)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java` (링크 생성 시딩 기대 3건)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` (등록 경로에 시딩 기대가 있으면)

> 삭제와 참조 제거를 **한 커밋에** 넣는다(중간 커밋이 컴파일을 깨면 안 된다).

### Steps

- [ ] **참조 전수 확인** — `docs/`를 제외하고 돌린다.

```
grep -rn "BrandHashtagTags" was/src monitoring/src crawler/src analytics/src instagram-source/src common-llm/src
```

  조사 시점 기준 was 쪽 컴파일 대상 참조는 4곳이다: 삭제할 본체·테스트,
  `BrandCaptionHashtags:12`(javadoc `{@link}`), `V1BrandAccountService:150`(javadoc)·`:172`(호출).
  `V1BrandAccountsControllerTest`·`BrandHashtagTagsBackfillMigrationTest`·
  `V20260827092444__brand_hashtag_tags_backfill.sql`은 **주석 문자열로만** 언급하므로 컴파일에
  영향이 없다(마이그레이션은 이미 적용돼 있어 **절대 수정 금지**).
  monitoring `service.BrandHashtagTags`는 동명이인이고 `isValidTag`가 살아 있어 건드리지 않는다.

- [ ] **실패 테스트 작성** — `V1BrandAccountServiceHashtagTagsTest`에서 링크 생성 시딩을 검증하는
  3건(`신규_링크_생성은_유도_태그를_monitoring에_push하고_장부에도_시딩한다`,
  `유도_태그가_없으면_push도_시딩도_건너뛴다`, `장부_시딩_실패는_등록_응답에_영향이_없다`)을 삭제하고
  — `멱등_재_POST는_장부를_시딩하지_않는다`는 유지 — 그 자리에 아래를 넣는다.

```java
	/**
	 * 링크 생성은 더 이상 태그를 심지 않는다(2026-09-03 자동 시드 재설계 §4-3) — 계정명 절삭 유도
	 * 규칙이 삭제됐다. 자동 태그는 초기 백필 완료 뒤 첫 조회에서 훅({@code ensureAutoSeeded})이
	 * monitoring 제안을 받아 심는다.
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

- [ ] **최소 구현 1/4 (삭제)**

```
git -C /Users/woomin/Project/hypenow-backend/.worktrees/hashtag-auto-seed rm \
  was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java \
  was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java
```

- [ ] **최소 구현 2/4 (`V1BrandAccountService`)** — 134~142행의 주석 블록과 `seedLedgerTagsSafely(userId, registered.brandId(), username);` 호출을 아래로 교체하고, 이어지는 `seedLedgerTagsSafely` 메서드(javadoc 포함, 149~186행)를 통째로 삭제한다.

```java
		// 태그 시딩은 링크 생성 시점에 하지 않는다(2026-09-03 자동 시드 재설계 §4-3) — 계정명 절삭
		// 유도 규칙이 삭제됐고, 이 시점엔 판단 재료(태그된 게시물 캡션)가 아직 하나도 없다. 자동
		// 태그는 초기 백필 완료 뒤 첫 조회에서 ensureAutoSeeded가 monitoring 제안을 받아 심는다.
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
```

  삭제 후 쓰이지 않게 된 import가 생기면 지운다(`List`는 `getHashtagTags` 등에서 계속 쓰이므로 남는다).

- [ ] **최소 구현 3/4 (`BrandCaptionHashtags` javadoc)** — 9~13행을 바꾼다.

```java
/**
 * 캡션 해시태그 추출(스펙 2026-08-31 §3). 규칙은 ASCII # + [\p{L}\p{N}_]+ — 인스타 링크화와
 * 일치가 계약이다(전각 ＃ 제외·이모지 갭 수용, 검증 근거는 스펙). 문자 집합은 monitoring
 * {@code HashtagCandidateExtractor}·{@code BrandHashtagTags.isValidTag}와 같은 정의를 유지할 것 —
 * 갈리면 "화면에서 필터되는 태그"와 "monitoring이 제안·스윕하는 태그"가 어긋난다.
 */
```

- [ ] **최소 구현 4/4 (컨트롤러 테스트)** — `V1BrandAccountsControllerTest`의 **등록(POST) 경로** 테스트에 `addHashtagTags`·`addTags` 기대가 있으면 제거한다. 태그 추가 API 테스트의 단언(1312행 부근 `then(hashtagTagRepository).should().addTags(7L, 100L, List.of("리즈다"));`)은 **그대로 둔다**. 대상 특정:

```
grep -n "registerBrand\|addHashtagTags\|addTags" was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java
```

- [ ] **통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 전부 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(was): 계정명 절삭 유도 규칙 삭제

was BrandHashtagTags(계정명 첫 점 앞까지 절삭)와 그 테스트, 링크 생성 시
seedLedgerTagsSafely 호출을 삭제한다. 점이 든 계정명에서 계정과 무관한
일반어(dr.piel_official → dr)를 만들어 해시태그 스윕이 무관 게시물을 대량
수집하던 규칙이다. 자동 태그는 초기 백필 완료 뒤 훅이 심는다(다음 커밋).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 10 — was 자동 시드 훅 (`ensureAutoSeeded`) + 호출 지점 3곳

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (필드·생성자, `get` 228~231행, `getHashtagTags` 270~278행, 새 메서드)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (90~109행 생성자, 212~239행 두 엔드포인트)
- Create: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceAutoSeedTest.java`
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java` (생성자 인자 추가)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceWithdrawalTest.java` (생성자 인자 추가)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` (@MockitoBean 추가)
- Modify: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java` (@MockitoBean 추가 + 훅 검증)

> **호출 지점을 컨트롤러 층에 두는 이유** — spec §4-2의 3번은 "해시태그 게시물 목록 조회"인데,
> 그 조립(`BrandHashtagPostAssembler` → `BrandPostAssembler`)에는 `MonitoringCommandClient`도
> username도 없다(조립 전용 컴포넌트라 HTTP 의존을 넣는 건 층 위반이자 성능 위험이다).
> `V1BrandPostsController`가 조립 직전에 서비스의 훅을 부른다. **메인 목록
> `GET /accounts/{id}/posts`에는 걸지 않는다**(수집 중 초 단위 폴링 경로 — spec §4-2·§7).
>
> 개수 엔드포인트(`hashtag-posts/count`)에도 함께 건다 — 탭 뱃지가 목록 없이 먼저 렌더될 수 있고,
> 그때 장부가 비면 0으로 보여 사용자가 목록을 열지 않는다. 훅은 링크 표식이 찍힌 뒤 첫 분기에서
> 곧장 반환하므로 반복 비용이 없다.

### Steps

- [ ] **실패 테스트 작성** — `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceAutoSeedTest.java`

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.BrandHashtagSeedRepository;
import com.celfit.was.monitoring.BrandHashtagSeedRepository.SeedRow;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringCommandClient.HashtagSuggestionBody;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 자동 시드 훅(2026-09-03 자동 시드 재설계 §4-2) — was가 유일한 작성자다. 분기 전부를 고정한다:
 * 링크 이미 반영됨 / 백필 미완 / 이미 사용자 태그 있음(SKIP) / 신규 계산 / 기존 시드 복사 /
 * push 실패 격리 / 동시 호출 경합. 훅은 어떤 실패에서도 예외를 밖으로 내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class V1BrandAccountServiceAutoSeedTest {

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 100L;
	private static final long LINK_ID = 1L;
	private static final String USERNAME = "dr.piel_official";
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-03T00:00:00Z");

	@Mock
	BrandLinkRepository linkRepository;
	@Mock
	MonitoringCommandClient commandClient;
	@Mock
	BrandReadRepository brandReadRepository;
	@Mock
	UserRepository userRepository;
	@Mock
	BrandHashtagTagRepository hashtagTagRepository;
	@Mock
	BrandHashtagSeedRepository seedRepository;

	V1BrandAccountService service;

	@BeforeEach
	void setUp() {
		service = new V1BrandAccountService(linkRepository, new BrandLinkTransaction(linkRepository),
				commandClient, brandReadRepository, new BrandAccountAssembler(3), userRepository,
				hashtagTagRepository, seedRepository);
	}

	private static BrandLinkRow link(OffsetDateTime hashtagSeededAt) {
		return new BrandLinkRow(LINK_ID, USER_ID, BRAND_ID, USERNAME, BrandAccountType.OWN, 12,
				NOW, null, hashtagSeededAt);
	}

	/** 이 테스트가 실제로 읽는 필드만 의미 있게 채운다(username·backfillCompletedAt). */
	private static BrandAccountRow account(OffsetDateTime backfillCompletedAt) {
		return new BrandAccountRow(BRAND_ID, USERNAME, LocalDate.of(2026, 9, 2), NOW, NOW,
				backfillCompletedAt, null, 100L, 10L, 5L, "소개", "닥터피엘 Dr.PIEL",
				"https://p", false, null, "ACTIVE", null, 12, NOW, false, null);
	}

	private void stubLink(OffsetDateTime hashtagSeededAt) {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(Optional.of(link(hashtagSeededAt)));
	}

	private void stubAccount(OffsetDateTime backfillCompletedAt) {
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account(backfillCompletedAt)));
	}

	// ---------- 게이트 ----------

	@Test
	void 링크가_이미_반영됐으면_아무것도_하지_않는다() {
		stubLink(NOW);

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).find(anyLong());
		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}

	@Test
	void 미소유_브랜드는_아무것도_하지_않는다() {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.empty());

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).find(anyLong());
	}

	@Test
	void 초기_백필이_미완이면_계산하지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(null);

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	// ---------- 신규 계산 ----------

	@Test
	void 태그가_없으면_제안을_받아_기록하고_push하고_장부에_넣고_표식을_찍는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "AI", "닥터피엘", NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("AI", "닥터피엘", 3, 40));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should().insertIgnore(BRAND_ID, "AI", "닥터피엘");
		then(commandClient).should().addHashtagTags(USERNAME, List.of("닥터피엘"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥터피엘"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	/** 이미 사용자 관리 태그가 있는 브랜드 — 자동 태그를 얹지 않고 SKIP만 기록한다. */
	@Test
	void monitoring에_태그가_있으면_SKIP을_기록하고_장부는_건드리지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "SKIP", null, NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("사용자태그"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should().insertIgnore(BRAND_ID, "SKIP", null);
		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		// 이 링크에 대한 판정은 끝났다 — 다음 조회가 같은 결론을 다시 계산하면 안 된다.
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	// ---------- 기존 시드 재사용 ----------

	@Test
	void 시드가_이미_있으면_계산_없이_복사한다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "FREQ", "닥피", NOW)));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(commandClient).should(never()).getHashtagTags(anyString());
		then(brandReadRepository).should(never()).findAccount(anyLong());
		then(commandClient).should().addHashtagTags(USERNAME, List.of("닥피"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥피"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	@Test
	void SKIP_시드는_장부_삽입_없이_표식만_찍는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "SKIP", null, NOW)));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	/** 동시 호출 경합 — 내 INSERT가 지면 재조회로 이긴 쪽의 값을 쓴다(계산 결과가 아니라). */
	@Test
	void 동시_호출은_먼저_커밋된_시드를_따른다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "FREQ", "먼저값", NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("AI", "내값", 1, 5));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("먼저값"));
	}

	// ---------- 격리 ----------

	/** monitoring push가 실패해도 장부는 진행한다 — 여기서 멈추면 그 사용자 장부가 영구히 빈다. */
	@Test
	void push_실패는_장부_삽입과_표식을_막지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "FREQ", "닥피", NOW)));
		willThrow(new RuntimeException("monitoring 순단"))
				.given(commandClient).addHashtagTags(USERNAME, List.of("닥피"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥피"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	@Test
	void 제안_조회_실패는_예외를_밖으로_내지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME)).willThrow(new RuntimeException("monitoring 순단"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).insertIgnore(anyLong(), anyString(), anyString());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	@Test
	void 링크_조회_실패도_예외를_밖으로_내지_않는다() {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID))
				.willThrow(new RuntimeException("DB 장애"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	/** 제안 tag가 비어 오는 건 monitoring 계약 위반이지만, 방어적으로 심지 않는다. */
	@Test
	void 제안_tag가_비면_아무것도_심지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("FALLBACK", "  ", 0, 0));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).insertIgnore(anyLong(), anyString(), anyString());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}
}
```

- [ ] **실패 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceAutoSeedTest"` 가 컴파일 실패로 끝나는 것을 확인한다.

- [ ] **최소 구현 1/4 (필드·생성자)** — `V1BrandAccountService`의 `hashtagTagRepository` 필드 뒤에 추가하고 생성자를 넓힌다.

```java
	private final BrandHashtagTagRepository hashtagTagRepository;
	/** 브랜드 단위 자동 시드 기록(2026-09-03 §4-1) — 계산을 브랜드당 1회로 묶는다. */
	private final BrandHashtagSeedRepository seedRepository;

	public V1BrandAccountService(BrandLinkRepository linkRepository, BrandLinkTransaction linkTransaction,
			MonitoringCommandClient commandClient, BrandReadRepository brandReadRepository,
			BrandAccountAssembler assembler, UserRepository userRepository,
			BrandHashtagTagRepository hashtagTagRepository, BrandHashtagSeedRepository seedRepository) {
		this.linkRepository = linkRepository;
		this.linkTransaction = linkTransaction;
		this.commandClient = commandClient;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.userRepository = userRepository;
		this.hashtagTagRepository = hashtagTagRepository;
		this.seedRepository = seedRepository;
	}
```

  import에 `com.celfit.was.monitoring.BrandHashtagSeedRepository`를 추가한다.

- [ ] **최소 구현 2/4 (`ensureAutoSeeded`)** — `getHashtagTags` 뒤(또는 파일의 private 헬퍼 구역 앞)에 넣는다.

```java
	/**
	 * 브랜드 해시태그 자동 시드 훅(2026-09-03 자동 시드 재설계 §4-2) — <b>was가 유일한 작성자다</b>
	 * (08-28 "태그 생성 권한 was 일원화" 유지). monitoring은 계산만 해 주고 아무것도 쓰지 않는다.
	 *
	 * <p>두 단계 멱등: 계산은 브랜드당 1회({@code app.brand_hashtag_seed} 1행), 장부 삽입은
	 * 사용자당 1회({@code brand_monitorings.hashtag_seeded_at}). 두 번째 사용자가 같은 브랜드에
	 * 링크하면 계산 없이 시드 행의 태그를 자기 장부에 복사한다. 사용자가 자동 태그를 지운 뒤 다시
	 * 조회해도 표식이 찍혀 있어 되살아나지 않는다.
	 *
	 * <p>호출은 초기 백필이 끝난 뒤여야 의미가 있다 — 캡션이 하나도 없으면 FREQ가 성립하지 못하고
	 * 곧장 AI·FALLBACK으로 떨어져 그 결과가 브랜드 생애 유일한 시드로 굳는다. {@code
	 * backfill_completed_at}이 null이면 아무것도 하지 않고 다음 조회로 미룬다.
	 *
	 * <p>monitoring push는 <b>먼저</b> 시도하되 실패해도 장부는 진행한다(구 {@code
	 * seedLedgerTagsSafely}와 같은 순서·격리) — 여기서 멈추면 표식이 안 찍혀 매 조회마다 재시도하는
	 * 것 같지만, 실제로는 그 사용자에게 태그가 영영 안 보이는 상태가 길어진다. 장부만 채워진
	 * 상태는 다음 사용자의 push나 수동 추가로 자연 복구된다.
	 *
	 * <p><b>전체가 best-effort다</b> — 어떤 예외도 밖으로 내지 않는다. 호출 지점 3곳이 전부 사용자
	 * 대면 조회라, 자동 시드 실패가 화면을 깨뜨리면 안 된다. 소유권 검증은 이 메서드 안의 활성
	 * 링크 조회가 겸한다(남의 brandId면 링크가 없어 조용히 반환).
	 */
	public void ensureAutoSeeded(long userId, long brandId) {
		try {
			doEnsureAutoSeeded(userId, brandId);
		} catch (RuntimeException e) {
			log.warn("해시태그 자동 시드 실패(격리) — userId={}, brandId={}", userId, brandId, e);
		}
	}

	private void doEnsureAutoSeeded(long userId, long brandId) {
		Optional<BrandLinkRow> link = linkRepository.findActiveByUserAndBrand(userId, brandId);
		if (link.isEmpty() || link.get().hashtagSeededAt() != null) {
			return;
		}
		String username = link.get().username();
		Optional<BrandHashtagSeedRepository.SeedRow> seed = seedRepository.find(brandId);
		if (seed.isEmpty()) {
			seed = computeSeed(brandId, username);
			if (seed.isEmpty()) {
				return;   // 백필 미완·계산 실패 — 표식을 찍지 않고 다음 조회로 미룬다.
			}
		}
		String tag = seed.get().tag();
		if (tag != null && !tag.isBlank()) {
			try {
				commandClient.addHashtagTags(username, List.of(tag));
			} catch (RuntimeException e) {
				log.warn("해시태그 자동 시드 monitoring push 실패(격리, 장부는 진행) — userId={}, brandId={}",
						userId, brandId, e);
			}
			hashtagTagRepository.addTags(userId, brandId, List.of(tag));
		}
		linkRepository.markHashtagSeeded(link.get().id());
	}

	/**
	 * 브랜드 단위 계산 1회 — 백필 미완이면 empty(다음 조회로 미룸). 이미 사용자 관리 태그가 있는
	 * 브랜드는 자동 태그를 얹지 않고 SKIP만 기록한다(그 브랜드는 사용자가 이미 태그를 관리 중이다).
	 *
	 * <p>INSERT는 {@code ON CONFLICT DO NOTHING} 뒤 <b>재조회</b>한다 — 동시 호출 둘이 각자 계산해도
	 * 먼저 커밋한 값이 정본이 되고, 진 쪽은 자기 계산 결과가 아니라 그 값을 심는다(두 사용자의
	 * 장부가 갈리지 않는다).
	 */
	private Optional<BrandHashtagSeedRepository.SeedRow> computeSeed(long brandId, String username) {
		BrandAccountRow account = findAccountOrThrow(brandId);
		if (account.backfillCompletedAt() == null) {
			return Optional.empty();
		}
		if (!commandClient.getHashtagTags(username).isEmpty()) {
			seedRepository.insertIgnore(brandId, "SKIP", null);
			return seedRepository.find(brandId);
		}
		MonitoringCommandClient.HashtagSuggestionBody suggestion =
				commandClient.getHashtagSuggestion(username);
		if (suggestion == null || suggestion.tag() == null || suggestion.tag().isBlank()) {
			// monitoring 계약(tag는 비지 않는다) 위반 — 심지 않고 다음 조회로 미룬다.
			log.warn("해시태그 제안 응답에 태그가 없다(계약 위반) — brandId={}, username={}", brandId, username);
			return Optional.empty();
		}
		log.info("해시태그 자동 시드 계산 — brandId={}, username={}, path={}, tag={}, topCount={}, candidatePosts={}",
				brandId, username, suggestion.path(), suggestion.tag(),
				suggestion.topCount(), suggestion.candidatePosts());
		seedRepository.insertIgnore(brandId, suggestion.path(), suggestion.tag());
		return seedRepository.find(brandId);
	}
```

  import에 `com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow`가 없으면 추가한다
  (`findAccountOrThrow`의 반환 타입 — 이미 쓰이고 있으면 그대로).

- [ ] **최소 구현 3/4 (호출 지점 2곳 — 서비스)** — `get`(228~231행)과 `getHashtagTags`(270~278행)를 바꾼다.

```java
	/** 단건 폴링(§5-2) — 소유권은 활성 연결로 검증(남의 brandId는 403). 타입도 그 연결에서 읽는다.
	 *
	 * <p>수집이 끝난 브랜드면 자동 시드 훅을 태운다(2026-09-03 자동 시드 재설계 §4-2 호출 지점 1) —
	 * FE가 등록 직후 이 API를 폴링하므로, 백필 완료 폴링이 그대로 시드 시점이 된다. 미완일 때
	 * 부르지 않는 이유는 훅 안에서도 같은 판정을 하지만 폴링 왕복마다 링크·시드 조회를 태울
	 * 이유가 없어서다. */
	public BrandAccountResponse get(long userId, long brandId) {
		BrandLinkRow link = requireOwnership(userId, brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		if (account.backfillCompletedAt() != null) {
			ensureAutoSeeded(userId, brandId);
		}
		return assembler.toResponse(account, link.accountType(), link.collectionMonths());
	}
```

```java
	public List<BrandHashtagTagsResponse.TagStatus> getHashtagTags(long userId, long brandId) {
		requireOwnership(userId, brandId);
		// 자동 시드 훅(2026-09-03 §4-2 호출 지점 2) — 장부를 읽기 전에 태운다.
		ensureAutoSeeded(userId, brandId);
		List<String> ledgerTags = List.copyOf(hashtagTagRepository.findByUserAndBrand(userId, brandId));
		if (ledgerTags.isEmpty()) {
			findAccountOrThrow(brandId);   // 소유권 통과 후에도 브랜드 자체는 존재해야 한다(기존 계약 유지)
			return List.of();
		}
		String username = findAccountOrThrow(brandId).username();
		Map<String, MonitoringCommandClient.TagRunState> runStates = fetchRunStatesSafely(username);
```

  (이하 기존 본문 유지.)

- [ ] **최소 구현 4/4 (호출 지점 3 — 컨트롤러)** — `V1BrandPostsController`의 필드·생성자에 서비스를 추가하고,

```java
	private final BrandHashtagPostAssembler hashtagPostAssembler;
	/** 해시태그 자동 시드 훅 전용(2026-09-03 §4-2 호출 지점 3) — 조립 직전 1회. */
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

  두 해시태그 엔드포인트에서 조립 직전에 훅을 부른다.

```java
	@GetMapping("/accounts/{accountId}/hashtag-posts")
	public ApiResponse<List<BrandHashtagPostResponse>> hashtagPosts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		// 자동 시드 훅(2026-09-03 §4-2 호출 지점 3) — 격리 필터(내 장부 태그 ∩ 게시물 매칭 태그)가
		// 빈 장부에서는 아무것도 통과시키지 못해, 자동 태그로 발견된 게시물이 통째로 안 보인다.
		// 조립 층(BrandPostAssembler)에는 monitoring 클라이언트도 username도 없어 여기서 부른다.
		brandAccountService.ensureAutoSeeded(principal.getUserId(), brandId);
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
		// 탭 뱃지가 목록보다 먼저 렌더될 수 있다 — 여기서 0이 나오면 사용자가 목록을 열지 않는다.
		brandAccountService.ensureAutoSeeded(principal.getUserId(), brandId);
		LocalDate windowStart = BrandPostWindows.linkWindowStart(today(), link.collectionMonths());
		return ApiResponse.ok(Map.of("count",
				hashtagPostAssembler.countForBrand(principal.getUserId(), account, windowStart)));
	}
```

- [ ] **기존 테스트 배선 갱신** —
  1. `V1BrandAccountServiceHashtagTagsTest`: `@Mock BrandHashtagSeedRepository seedRepository;`를 더하고 `setUp()`의 생성자에 마지막 인자로 넘긴다. `getHashtagTags` 테스트들은 훅이 링크 조회에서 곧장 반환하도록 `link()` 픽스처의 `hashtagSeededAt`을 **non-null**로 채운다(그 테스트들의 관심사는 훅이 아니다).
  2. `V1BrandAccountServiceWithdrawalTest`: 생성자에 `Mockito.mock(BrandHashtagSeedRepository.class)` 또는 기존 mock 관용구대로 인자를 더한다.
  3. `V1BrandAccountsControllerTest`: `@MockitoBean BrandHashtagSeedRepository seedRepository;`를 더한다(`@Import`에 실 서비스가 붙어 있어 빈이 필요하다). 태그 조회 테스트는 `link(...)` 헬퍼의 `hashtagSeededAt`을 non-null로 채워 훅을 무력화한다.
  4. `V1BrandPostsControllerTest`: `@MockitoBean V1BrandAccountService brandAccountService;`를 더한다.

- [ ] **컨트롤러 훅 검증 추가** — `V1BrandPostsControllerTest`에 아래를 넣는다(`then`/`should`는 `org.mockito.BDDMockito` 정적 import).

```java
	@Test
	void 해시태그_목록_조회는_자동_시드_훅을_먼저_태운다() throws Exception {
		stubOwnedBrand(7L, 100L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk());

		then(brandAccountService).should().ensureAutoSeeded(7L, 100L);
	}

	@Test
	void 해시태그_개수_조회도_자동_시드_훅을_태운다() throws Exception {
		stubOwnedBrand(7L, 100L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts/count").with(user(principal())))
				.andExpect(status().isOk());

		then(brandAccountService).should().ensureAutoSeeded(7L, 100L);
	}

	/** 메인 목록은 수집 중 초 단위 폴링 경로라 훅을 걸지 않는다(§4-2·§7). */
	@Test
	void 메인_게시물_목록은_자동_시드_훅을_태우지_않는다() throws Exception {
		stubOwnedBrand(7L, 100L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk());

		then(brandAccountService).should(never()).ensureAutoSeeded(anyLong(), anyLong());
	}
```

  > `stubOwnedBrand`·`principal()`은 이 테스트 클래스의 기존 헬퍼다. 헬퍼가 심는 brandId·userId가
  > 위 리터럴과 같은지 파일을 열어 확인하고 맞춘다.

- [ ] **통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 가 전부 통과하는 것을 확인한다.

- [ ] **모듈 회귀 확인** — `./gradlew :was:test` 가 통과하는 것을 확인한다.

- [ ] **커밋**

```
feat(was): 초기 백필 완료 뒤 해시태그 자동 시드 훅

ensureAutoSeeded — 링크 표식이 없고 백필이 끝난 브랜드에서, monitoring 제안을
받아 brand_hashtag_seed에 브랜드당 1행 기록하고 그 태그를 monitoring에 push한
뒤 사용자 장부에 넣고 링크에 표식을 찍는다. 이미 사용자 태그가 있는 브랜드는
SKIP만 기록한다. 두 번째 사용자는 계산 없이 시드 행을 복사한다.

호출 지점은 단건 폴링(수집 완료일 때만)·태그 목록 조회·해시태그 게시물 목록과
개수 3곳이다. 메인 게시물 목록에는 걸지 않는다(초 단위 폴링 경로).
전체 best-effort — 어떤 실패도 응답을 막지 않는다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 11 — 결정 기록과 트랙 문서

**Files:**
- Modify: `DECISIONS.md` (표 헤더 바로 아래, 맨 위 행)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (파일 끝에 절 추가)

### Steps

- [ ] **DECISIONS.md 갱신** — `| 날짜 | 결정 | 근거/상세 |` 표의 **첫 데이터 행 앞**에 아래 한 행을 넣는다(기존 행과 같은 3열·같은 " - " 구분 관용구).

```
| 2026-09-03 | **브랜드 해시태그 자동 시드 재설계 - 계정명 절삭 폐기, monitoring은 계산만 / was가 유일한 작성자** - 자동 태그 재료를 "계정명 소문자화 후 첫 무효 문자 앞까지 절삭"(2026-08-17 be39cbd7, 08-27 §4로 was에 이식)에서 **3단 계산**으로 바꾼다: ① 그 브랜드에 태그된 게시물 캡션의 해시태그 빈도(최다 태그의 등장 게시물 수 ≥ 7) ② 미달이면 AI가 **IG 표시명(brand_account.full_name)** + 계정명에서 브랜드 상호를 뽑아 1개 ③ 그래도 없으면 계정명에서 점·언더스코어를 뺀 값. **결과는 브랜드당 항상 1개**이고 경쟁사도 같다. 절삭은 점이 든 계정명에서 계정과 무관한 일반어를 만들었다(`dr.piel_official` → `#dr` 전량 스윕 = 무관 게시물 대량 유입 + Hiker 콜 낭비) - 점 든 계정명은 그 자체로 해시태그가 될 수 없어 문자열을 어떻게 잘라도 결과가 추측이고, 실제 소비자가 쓰는 태그는 `#닥터피엘` 같은 상호다. AI 입력에서 **회사명(app.users.company_name)·바이오는 제외** - 회사명은 등록자 소속이라 경쟁사 브랜드에 남의 이름을 붙인다. **역할 분담: monitoring은 계산만 하고 DB에 쓰지 않는다**(내부 조회 API `GET /api/brands/{username}/hashtag-suggestion` 1개, 응답 `{path, tag, topCount, candidatePosts}`) - **08-28 "태그 생성 권한 was 일원화" 결정을 그대로 유지**한다. 1차 초안이던 A안(monitoring이 직접 `brand_hashtag`에 심고 was도 자기 장부에 쓰는 구조)은 **같은 태그를 두 모듈이 각자 쓰게 만들어** 폐기했다(사용자 지적) - 쓰기 주체가 갈리면 "누가 심었나"가 불명확해지고 08-28에 정리한 권한 경계가 도로 무너진다. **시점은 링크 생성이 아니라 초기 백필 완료 뒤**(그때야 캡션 모수가 존재한다) - was 단건 폴링(수집 완료 시)·태그 목록 조회·해시태그 게시물 목록/개수 조회에 같은 훅(`ensureAutoSeeded`). 중복·부활 방지는 브랜드당 시드 기록 1행(`app.brand_hashtag_seed`, 계산 1회) + 링크별 `hashtag_seeded_at`(장부 삽입 사용자당 1회) - 사용자가 지운 자동 태그는 되살아나지 않고, 이미 사용자 관리 태그가 있는 브랜드는 SKIP만 기록한다. 임계·stoplist·AI 킬스위치는 monitoring app_setting 런타임 토글. FE 계약 변화 없음(자동 태그가 "등록 즉시"가 아니라 "수집 완료 뒤 첫 조회"에 나타나는 타이밍만 바뀜 - FE 통지 1건). 운영 정리는 이미 심긴 절삭 태그를 monitoring·was 양쪽에서 삭제하는 것뿐이고, **재시드는 훅이 자동으로 한다**(스크립트·replay 호출 불요). | [spec 2026-09-03](docs/superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md), [plan](docs/superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md) |
```

- [ ] **트랙 문서 갱신** — `docs/tracks/MON-BT-브랜드-태그-모니터링.md` **파일 끝**에 아래 절을 덧붙인다.

```markdown
## 해시태그 자동 시드 재설계(2026-09-03) — 계정명 절삭 폐기

- 상태: 구현 완료·**운영 정리 미실행**. 설계 정본은 [spec 2026-09-03](../superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md), 실행 계획은 [plan](../superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md).
- 자동 태그가 계정명 절삭 → **3단 계산**으로 바뀌었다. ① FREQ: 태그된 게시물 캡션의 해시태그 빈도(최다 태그의 등장 게시물 수 ≥ `brand.hashtag-seed.min-posts`, 기본 7) ② AI: IG 표시명(`brand_account.full_name`) + 계정명으로 상호 해시태그 1개 ③ FALLBACK: 계정명에서 점·언더스코어를 뺀 소문자. **결과는 브랜드당 항상 1개**(경쟁사 포함).
- **역할 분담**: monitoring은 `GET /api/brands/{username}/hashtag-suggestion`으로 **계산만** 하고 DB에 쓰지 않는다. 저장·push·장부 반영은 was `V1BrandAccountService.ensureAutoSeeded`가 전담한다(08-28 태그 생성 권한 was 일원화 유지).
- **시점**: 링크 생성이 아니라 **초기 백필 완료 뒤 첫 조회**. 훅은 단건 폴링 `GET /accounts/{id}`(수집 완료일 때만)·`GET /accounts/{id}/hashtag-tags`·`GET /accounts/{id}/hashtag-posts`·`.../hashtag-posts/count` 4개 표면에 걸려 있다. 메인 목록 `GET /accounts/{id}/posts`에는 없다(수집 중 초 단위 폴링 경로).
- **멱등 장치 두 겹**: `app.brand_hashtag_seed`(brand_id PK — 계산·AI 콜은 브랜드 생애 1회) + `app.brand_monitorings.hashtag_seeded_at`(장부 삽입은 사용자당 1회). 사용자가 지운 자동 태그는 표식 때문에 되살아나지 않는다. 이미 사용자 관리 태그가 있는 브랜드는 `path='SKIP'`(tag NULL)만 기록하고 아무것도 심지 않는다.
- 런타임 토글(monitoring app_setting, TTL 5초): `brand.hashtag-seed.min-posts` / `brand.hashtag-seed.stoplist` / `brand.hashtag-seed.ai-enabled`. AI 킬 스위치는 SQL 한 줄이고 끄면 FREQ 미달이 곧장 FALLBACK으로 간다.
  ```sql
  UPDATE app_setting SET value = 'false' WHERE key = 'brand.hashtag-seed.ai-enabled';
  ```
- 관측: monitoring 응답 1건당 info 로그 1줄(`브랜드 해시태그 제안 — username=… path=FREQ|AI|FALLBACK tag=… topCount=… candidatePosts=…`) + Micrometer 카운터 `brand.hashtag.suggest`(태그 `path`=freq|ai|fallback, `result`=ok|error). was는 계산 시점에 `해시태그 자동 시드 계산` info 1줄을 남긴다. **FALLBACK 비율이 높으면 AI 경로가 죽은 것**이다.

### 운영 데이터 정리 (배포 후 1회 — 미실행)

실행 전 대상 목록을 먼저 뽑아 **눈으로 확인한다**. IG 계정명은 ASCII·점·언더스코어만 허용되므로
절삭 접두사 = 첫 점 앞 구간이다.

```sql
-- 1) 대상 확인(monitoring DB)
SELECT h.brand_id, a.username, h.tag, h.created_at, h.deleted_at
FROM brand_hashtag h JOIN brand_account a ON a.id = h.brand_id
WHERE position('.' in a.username) > 0
  AND h.tag = lower(split_part(a.username, '.', 1))
ORDER BY h.brand_id;
```

- `created_at`이 그 브랜드의 링크 생성 시각과 **다른** 행은 사용자가 직접 넣은 태그일 수 있다 —
  그런 행은 대상에서 뺀다(아래 DELETE 실행 전에 손으로 제외할 것).

```sql
-- 2) monitoring: hard DELETE (tombstone이 아니다 — 사용자가 의도한 태그가 아니다)
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
  화면에서 사라지고, 스윕은 태그가 없으니 더 긁지 않는다. 물리 정리는 비범위. FREQ 집계 쿼리
  (`findCaptionsForSeed`)에 `tag_detected_at IS NOT NULL` 가드가 있어 남겨 둔 게시물의 캡션이
  집계를 오염시키지 않는다.
- **재시드 스크립트는 없다.** 정리로 태그가 0개가 된 브랜드는 사용자의 다음 조회에서 훅이 계산·시드한다.
  기존 링크는 전부 `hashtag_seeded_at IS NULL`로 시작하므로 배포 직후부터 자연히 돈다.

### 배포 다음 날 검토 (필수)

AI 결과 품질의 자동 검증은 없다(spec §7) — 사람이 한 번 본다.

```sql
-- path 분포(was app DB)
SELECT path, count(*) FROM app.brand_hashtag_seed GROUP BY path ORDER BY count(*) DESC;

-- AI·FALLBACK 태그 전수(계정명과 나란히 보려면 brand_id로 monitoring brand_account를 대조한다)
SELECT brand_id, path, tag, seeded_at FROM app.brand_hashtag_seed
WHERE path IN ('AI', 'FALLBACK') ORDER BY seeded_at DESC;
```

- **FALLBACK 비율이 높으면 AI 경로가 죽은 것**이다(Gemini 자격증명·쿼터·`ai-enabled` 확인).
- 명백히 잘못된 태그는 monitoring 태그 관리 API로 지운다 — `brand_hashtag_seed` 행이 남아 있어
  자동으로 되살아나지 않는다.
- 검토 결과(분포 수치·수정한 브랜드)를 이 절에 기록한다.

### 잔여·후속

- **FE 통지 1건** — 응답 계약은 그대로이고, 자동 태그가 "등록 즉시"가 아니라 "초기 수집 완료 뒤 첫
  조회"에 나타나도록 타이밍만 바뀌었다.
- **메인 게시물 목록(`GET /accounts/{id}/posts`)에는 훅이 없다**(spec §7) — FE가 태그 목록·해시태그
  탭 호출을 없애면 자동 태그 반영이 늦어질 수 있다. 그 경우 폴링 비용을 감수하고 훅을 추가할지
  재검토한다.
- **다중 태그 시드(국문·영문 병행)·주기적 재시드는 비범위**. 브랜드당 1개·1회 고정이고, 추가는
  사용자가 태그 관리 UI로 한다.
- 수집된 `#dr` 게시물의 물리 정리.
```

- [ ] **링크 확인**

```
ls docs/superpowers/specs/2026-09-03-brand-hashtag-auto-seed-redesign-design.md \
   docs/superpowers/plans/2026-09-03-brand-hashtag-auto-seed-redesign.md
```

- [ ] **커밋**

```
docs: 해시태그 자동 시드 재설계 결정 기록과 트랙 갱신

DECISIONS.md에 계정명 절삭 폐기 사유와 1차 A안(monitoring 직접 쓰기) 폐기
사유를 기록한다. 08-28 태그 생성 권한 was 일원화는 유지되는 결정이라 그렇게
명시했다. MON-BT 트랙에는 3단 계산·역할 분담·멱등 두 겹·런타임 토글·관측과
운영 정리 SQL, 배포 다음 날 path 분포 검토 절차를 적는다. 재시드 스크립트는
없다(훅이 자동으로 한다).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## 최종 검증

- [ ] `./gradlew :monitoring:test` 전량 통과
- [ ] `./gradlew :was:test` 전량 통과
- [ ] `grep -rn "BrandHashtagTags" was/src` 결과에 `was/.../v1/brandmonitoring/BrandHashtagTags` **클래스** 참조가 남지 않는다(주석 문자열 언급·monitoring 동명 클래스는 무해)
- [ ] `grep -rn "new BrandLinkRow(" was/src/test | wc -l` 이 24이고 전부 9개 인자다
- [ ] monitoring에 `brand_hashtag` **쓰기**가 새로 생기지 않았다 — `grep -rn "addTags\|replaceTags\|insertPost" monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagSuggestionService.java` 가 0건
- [ ] `git -C <worktree> status` 에 의도하지 않은 변경이 없다
- [ ] PR·push는 하지 않는다 — 사용자 승인 사항이다.
