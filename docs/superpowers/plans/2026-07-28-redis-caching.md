# Redis 캐싱 도입 구현 계획

> 상태: 🟢 활성 · ✅ 구현 완료(2026-07-29) · 스펙: [2026-07-28-redis-caching-design.md](../specs/2026-07-28-redis-caching-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** was의 무거운 조회 4경로(랭킹 목록·발굴 목록·인플루언서/콘텐츠 AI 리포트)에 Redis 캐시(TTL 백스톱, fail-open)를 도입하고, 목록 2종에 다음 페이지 프리페치를 얹는다. 세션은 JDBC 그대로.

**Architecture:** Spring Cache 추상화(`@Cacheable` + RedisCacheManager). 목록은 "공통 페이지 묶음"(개인화 제외)을 서비스 계층으로 추출해 캐싱하고, `isSaved` 오버레이는 기존처럼 컨트롤러가 캐시 밖에서 얹는다(**이미 분리돼 있음** — `V1ContentController`가 `SavedLookup`으로 오버레이 중, 쿼리에 사용자 조인 없음). 키는 정규화된 쿼리 record의 `toString()` SHA-256. 값은 Jackson JSON(`RedisSerializer.json()`). Redis 장애는 `CacheErrorHandler`가 삼켜 DB 직행.

**Tech Stack:** Java 21 · Spring Boot 4.1 (`spring-boot-starter-data-redis`, Lettuce) · Jackson 3(`tools.jackson.*`) · Testcontainers 2.x(redis:7-alpine) · docker compose.

**작업 위치:** 공유 체크아웃 주의 — `.worktrees/redis-caching` git worktree에서 `feat/redis-caching` 브랜치로 작업(superpowers:using-git-worktrees). PR 대상은 `develop`. 이 계획 문서와 스펙 문서도 같은 브랜치에서 함께 커밋한다.

**검증 명령:** 모듈 테스트 `./gradlew :was:test` (Testcontainers — Docker/colima 필요). 단일 테스트 `./gradlew :was:test --tests "com.celfit.was.cache.CacheIntegrationTest"`.

---

## 파일 구조 (전체 조감)

| 파일 | 역할 |
|---|---|
| Create `was/.../config/CacheConfig.java` | `@EnableCaching` + RedisCacheManager(캐시 4종 TTL·prefix·JSON 직렬화) + fail-open `CacheErrorHandler` |
| Create `was/.../v1/common/CacheKeys.java` | SHA-256 hex 유틸 |
| Create `was/.../v1/common/PagePrefetcher.java` | 프리페치 실행기(바운디드 풀, 실패·포화 무시) + `hasNextPage` 판정 |
| Create `was/.../v1/content/V1ContentPageService.java` | 랭킹 페이지 묶음 `@Cacheable("content-ranking")` |
| Create `was/.../v1/influencer/V1InfluencerDiscoveryPageService.java` | 발굴 페이지 묶음 `@Cacheable("influencer-discovery")` |
| Create `was/.../v1/influencer/V1InfluencerReportService.java` | 리포트 조립 `@Cacheable("influencer-report")` |
| Create `was/.../v1/content/V1ContentReportService.java` | 리포트 조립 `@Cacheable("content-report")` |
| Modify `was/.../v1/content/V1ContentQuery.java`, `.../influencer/V1InfluencerDiscoveryQuery.java` | `cacheKey()` · `next()` 추가 |
| Modify 컨트롤러 4종 (`V1ContentController`, `V1InfluencerDiscoveryController`, `V1InfluencerReportController`, `V1ContentReportController`) | 리포지토리 직결 → 서비스 경유 + 목록 2종 프리페치 |
| Modify `was/build.gradle` · `was/src/main/resources/application.yml` | 의존성 · redis 접속/타임아웃 |
| Modify `compose.yaml` · `deploy/compose.yaml` · `deploy/compose.dev.yaml` | redis 서비스 3곳 |
| Test: `was/src/test/java/com/celfit/was/cache/` + 기존 WebMvcTest 4종 수정 | 아래 각 태스크 |

`was/src/main/java/com/celfit/was/` 는 이하 `was/...`로 축약. 컨벤션 준수: 주석·커밋 한국어, DTO는 record, 탭 들여쓰기.

---

### Task 1: 인프라 — 의존성·접속 설정·compose 3종

**Files:**
- Modify: `was/build.gradle`
- Modify: `was/src/main/resources/application.yml`
- Modify: `compose.yaml` (로컬)
- Modify: `deploy/compose.yaml` (운영)
- Modify: `deploy/compose.dev.yaml` (dev 스테이징)

- [ ] **Step 1: 의존성 추가** — `was/build.gradle`의 `implementation 'org.springframework.boot:spring-boot-starter-session-jdbc'` 줄 아래에 추가:

```gradle
	// 조회 캐시(Redis) — 세션은 JDBC 유지(2026-07-28 결정: Redis는 순수 캐시 전용, specs/2026-07-28-redis-caching-design.md)
	implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

- [ ] **Step 2: 의존성 해석 확인**

Run: `./gradlew :was:dependencies --configuration runtimeClasspath | grep -i "data-redis\|lettuce"`
Expected: `spring-boot-starter-data-redis`와 `lettuce-core`가 출력에 나타남. (Boot 4에서 스타터명이 다르면 — webmvc처럼 개편됐을 수 있음 — `./gradlew :was:dependencies` 오류 메시지 기준으로 실제 스타터명을 찾아 교체)

- [ ] **Step 3: 접속 설정** — `was/src/main/resources/application.yml`의 `spring:` 블록(예: `session:` 다음)에 추가. prod는 compose가 `REDIS_HOST`를 주입하므로 prod yml 변경 불필요:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms            # 명령 타임아웃 — 장애 시 지연 전파 상한(fail-open 전제, 스펙 §7)
      connect-timeout: 500ms
```

- [ ] **Step 4: 로컬 compose** — `compose.yaml` services에 추가:

```yaml
  # was 조회 캐시 — 데이터 휘발 OK(재시작=콜드 캐시), 볼륨 없음
  redis:
    image: 'redis:7-alpine'
    command: ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
    ports:
      - '6379:6379'
```

- [ ] **Step 5: 운영 compose** — `deploy/compose.yaml` services에 추가. **포트 미공개(내부 네트워크만)·볼륨/AOF 없음·was에 depends_on 걸지 않음**(fail-open — redis 없이도 was 기동):

```yaml
  # was 조회 캐시 전용(세션 아님) — 휘발 허용(재시작=콜드 캐시), AOF·볼륨 없음 (스펙 §3).
  # was가 REDIS_HOST=redis로 접속. depends_on 없음 — redis 불능이어도 was는 DB 직행(fail-open).
  redis:
    image: redis:7-alpine
    restart: unless-stopped
    logging: *logging
    command: ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
```

그리고 `was:` 서비스의 `environment:`에 한 줄 추가:

```yaml
      REDIS_HOST: redis
```

- [ ] **Step 6: dev 스테이징 compose** — `deploy/compose.dev.yaml` services에 추가(기존 dev-* 서비스의 `profiles`/logging 관용구를 그대로 따라):

```yaml
  dev-redis:
    image: redis:7-alpine
    profiles: ["dev"]
    restart: unless-stopped
    logging: *logging
    command: ["redis-server", "--maxmemory", "128mb", "--maxmemory-policy", "allkeys-lru"]
```

그리고 `dev-was:` 서비스 `environment:`에 `REDIS_HOST: dev-redis` 추가.

- [ ] **Step 7: 컴파일·compose 문법 확인**

Run: `./gradlew :was:compileJava && docker compose -f deploy/compose.yaml config -q && docker compose -f deploy/compose.yaml -f deploy/compose.dev.yaml --profile dev config -q`
Expected: 전부 성공(경고 무시). `docker compose config`는 .env 없이는 변수 경고가 날 수 있음 — 오류만 아니면 통과. dev 파일은 단독 검증 불가(운영 파일 위 오버레이 구조라 `-f` 겹침 필수).

- [ ] **Step 8: Commit**

```bash
git add was/build.gradle was/src/main/resources/application.yml compose.yaml deploy/compose.yaml deploy/compose.dev.yaml
git commit -m "feat(was): Redis 캐시 인프라 — 의존성·접속 설정·compose 3종 (세션은 JDBC 유지)"
```

---

### Task 2: 캐시 공통 유닛 — CacheKeys · CacheConfig · PagePrefetcher

**Files:**
- Create: `was/.../v1/common/CacheKeys.java`
- Create: `was/.../config/CacheConfig.java`
- Create: `was/.../v1/common/PagePrefetcher.java`
- Test: `was/src/test/java/com/celfit/was/v1/common/CacheKeysTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/common/PagePrefetcherTest.java`

- [ ] **Step 1: CacheKeys 실패 테스트 작성**

```java
package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheKeysTest {

	@Test
	void 같은_입력은_같은_해시_다른_입력은_다른_해시() {
		assertThat(CacheKeys.sha256("a=1,b=2")).isEqualTo(CacheKeys.sha256("a=1,b=2"));
		assertThat(CacheKeys.sha256("a=1,b=2")).isNotEqualTo(CacheKeys.sha256("a=1,b=3"));
	}

	@Test
	void hex_64자() {
		assertThat(CacheKeys.sha256("x")).hasSize(64).matches("[0-9a-f]+");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.common.CacheKeysTest"`
Expected: 컴파일 실패(CacheKeys 미존재)

- [ ] **Step 3: CacheKeys 구현** — `was/.../v1/common/CacheKeys.java`:

```java
package com.celfit.was.v1.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 캐시 키 축약 — 정규화된 쿼리 toString을 SHA-256 hex로 접는다(같은 조건 = 같은 키, 스펙 §4). */
public final class CacheKeys {

	private CacheKeys() {
	}

	public static String sha256(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원 JVM", e);
		}
	}
}
```

- [ ] **Step 4: PagePrefetcher 실패 테스트 작성** — 판정 로직과 실행·예외 무시를 검증:

```java
package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PagePrefetcherTest {

	@Test
	void 다음_페이지_판정() {
		assertThat(PagePrefetcher.hasNextPage(100, 100, 0, 250)).isTrue();   // 다음 페이지 있음
		assertThat(PagePrefetcher.hasNextPage(100, 100, 200, 250)).isFalse(); // 남은 건 < limit여도 이번이 마지막 아님? 200+100>250 → 다음 없음
		assertThat(PagePrefetcher.hasNextPage(50, 100, 0, 50)).isFalse();     // 부분 페이지 = 마지막
		assertThat(PagePrefetcher.hasNextPage(100, 100, 100, 200)).isFalse(); // 정확히 소진
	}

	@Test
	void 작업이_실행되고_예외는_삼킨다() throws Exception {
		PagePrefetcher prefetcher = new PagePrefetcher();
		CountDownLatch ran = new CountDownLatch(1);
		prefetcher.prefetch(() -> {
			ran.countDown();
			throw new IllegalStateException("boom"); // 삼켜져야 함
		});
		assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
		CountDownLatch after = new CountDownLatch(1);
		prefetcher.prefetch(after::countDown); // 이전 예외 후에도 풀 생존
		assertThat(after.await(2, TimeUnit.SECONDS)).isTrue();
	}
}
```

주석의 두 번째 판정 케이스 설명: `offset+limit(300) < total(250)`이 거짓 → 다음 페이지 없음이 맞다.

- [ ] **Step 5: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.common.PagePrefetcherTest"`
Expected: 컴파일 실패(PagePrefetcher 미존재)

- [ ] **Step 6: PagePrefetcher 구현** — `was/.../v1/common/PagePrefetcher.java`:

```java
package com.celfit.was.v1.common;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 목록 다음 페이지 선계산 실행기(스펙 §5) — 응답 반환 후 N+1 페이지를 캐시에 미리 적재한다.
 * 실패·포화는 조용히 버린다(fail-open과 동일): 프리페치는 최적화지 기능이 아니다.
 * 중복 계산 방지는 @Cacheable(sync=true)가 담당 — 여기서는 스킵 판단을 하지 않는다.
 */
@Component
public class PagePrefetcher {

	private static final Logger log = LoggerFactory.getLogger(PagePrefetcher.class);

	private final ExecutorService pool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(64), runnable -> {
				Thread t = new Thread(runnable, "page-prefetch");
				t.setDaemon(true);
				return t;
			}, new ThreadPoolExecutor.DiscardPolicy());

	/** 마지막 페이지·부분 페이지면 프리페치하지 않는다. */
	public static boolean hasNextPage(int returned, int limit, int offset, long total) {
		return returned == limit && offset + limit < total;
	}

	public void prefetch(Runnable task) {
		pool.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				log.debug("프리페치 실패(무시)", e);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}
}
```

- [ ] **Step 7: CacheConfig 구현** — `was/.../config/CacheConfig.java`. 값 직렬화는 `RedisSerializer.json()`(Boot 4의 Jackson 기반 제네릭 JSON — `@class` 타입 정보 포함이라 record 왕복 가능). **`RedisSerializer.json()`이 Spring Data Redis 4에서 제거/개명됐으면** 같은 패키지의 Jackson3 계열 제네릭 serializer(`GenericJackson3JsonRedisSerializer` 류)로 대체:

```java
package com.celfit.was.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 조회 캐시(Redis) — TTL 백스톱만, 무효화 연동 없음(분석 데이터는 새벽 미러 후 하루 불변,
 * 스펙 specs/2026-07-28-redis-caching-design.md). 장애는 전면 fail-open: errorHandler가
 * 삼키고 DB 직행 — Redis는 SPOF가 아니다. 세션은 JDBC 유지(여기 안 탄다).
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	public static final String CONTENT_RANKING = "content-ranking";
	public static final String INFLUENCER_DISCOVERY = "influencer-discovery";
	public static final String CONTENT_REPORT = "content-report";
	public static final String INFLUENCER_REPORT = "influencer-report";

	private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
				.computePrefixWith(name -> "hypenow:cache:" + name + ":")
				.serializeValuesWith(RedisSerializationContext.SerializationPair
						.fromSerializer(RedisSerializer.json()));
		// TTL 근거: 미러 KST 04:30~07:00 → 최악 stale 6h면 오전 중 자연 갱신(스펙 §4)
		return RedisCacheManager.builder(factory)
				.cacheDefaults(base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(CONTENT_RANKING, base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(INFLUENCER_DISCOVERY, base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(CONTENT_REPORT, base.entryTtl(Duration.ofHours(6)))
				.withCacheConfiguration(INFLUENCER_REPORT, base.entryTtl(Duration.ofHours(6)))
				.build();
	}

	@Override
	public CacheErrorHandler errorHandler() {
		return new CacheErrorHandler() {
			@Override
			public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
				warn("get", cache, key, e);
			}

			@Override
			public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
				warn("put", cache, key, e);
			}

			@Override
			public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
				warn("evict", cache, key, e);
			}

			@Override
			public void handleCacheClearError(RuntimeException e, Cache cache) {
				warn("clear", cache, null, e);
			}
		};
	}

	private static void warn(String op, Cache cache, Object key, RuntimeException e) {
		log.warn("캐시 {} 실패(fail-open, 무시) cache={} key={}: {}", op, cache.getName(), key,
				e.getMessage());
	}
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.common.CacheKeysTest" --tests "com.celfit.was.v1.common.PagePrefetcherTest"`
Expected: PASS

- [ ] **Step 9: 기존 통합 테스트 회귀 확인** — CacheConfig 추가로 컨텍스트가 깨지지 않는지(Redis 미기동 환경에서도 RedisCacheManager 빈 생성은 lazy 연결이라 기동에 영향 없어야 함):

Run: `./gradlew :was:test --tests "com.celfit.was.LoginWallIntegrationTest"`
Expected: PASS (Redis 없이도 컨텍스트 기동·조회 정상)

- [ ] **Step 10: Commit**

```bash
git add was/src/main/java/com/celfit/was/config/CacheConfig.java was/src/main/java/com/celfit/was/v1/common/CacheKeys.java was/src/main/java/com/celfit/was/v1/common/PagePrefetcher.java was/src/test/java/com/celfit/was/v1/common/
git commit -m "feat(was): 캐시 공통 유닛 — CacheConfig(TTL·fail-open)·CacheKeys·PagePrefetcher"
```

---

### Task 3: 랭킹 목록 — V1ContentPageService + 컨트롤러 전환 + 프리페치

**Files:**
- Modify: `was/.../v1/content/V1ContentQuery.java` (cacheKey·next 추가)
- Create: `was/.../v1/content/V1ContentPageService.java`
- Modify: `was/.../v1/content/V1ContentController.java`
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentQueryTest.java` (신규 또는 기존 파일에 추가)
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentControllerTest.java` (수정)

- [ ] **Step 1: 쿼리 record 실패 테스트** — 기존 `V1ContentQueryTest`가 있으면 테스트 메서드만 추가, 없으면 신규 파일:

```java
package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class V1ContentQueryTest {

	private static V1ContentQuery q(Integer limit, Integer offset) {
		return V1ContentQuery.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28),
				null, null, null, null, null, null, null, null, null, limit, offset);
	}

	@Test
	void 같은_조건은_같은_캐시_키_페이지가_다르면_다른_키() {
		assertThat(q(50, 0).cacheKey()).isEqualTo(q(50, 0).cacheKey());
		assertThat(q(50, 0).cacheKey()).isNotEqualTo(q(50, 50).cacheKey());
	}

	@Test
	void 기본값_명시와_생략은_같은_키() {
		// contentType=reels·sort=hype 명시 == 생략(정규화 후 동일 조건)
		V1ContentQuery explicit = V1ContentQuery.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28),
				"reels", null, null, null, null, null, null, null, "hype", 100, 0);
		assertThat(explicit.cacheKey()).isEqualTo(q(null, null).cacheKey());
	}

	@Test
	void next는_offset만_limit만큼_전진() {
		V1ContentQuery next = q(50, 0).next();
		assertThat(next.offset()).isEqualTo(50);
		assertThat(next.limit()).isEqualTo(50);
		assertThat(next.sort()).isEqualTo("hype");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentQueryTest"`
Expected: 컴파일 실패(cacheKey/next 미존재)

- [ ] **Step 3: V1ContentQuery에 메서드 추가** — record 본문(마지막 private 헬퍼들 위 아무 데나)에:

```java
	/** 캐시 키(스펙 §4) — of()가 정규화를 끝낸 컴포넌트 전체의 toString 축약. record toString은 결정적. */
	public String cacheKey() {
		return com.celfit.was.v1.common.CacheKeys.sha256(toString());
	}

	/** 다음 페이지 쿼리 — 프리페치용(스펙 §5). */
	public V1ContentQuery next() {
		return new V1ContentQuery(startInstant, endExclusive, contentType, mainCategory, midCategory,
				subCategory, follower, keyword, adType, distributorId, sort, limit, offset + limit);
	}
```

(import 정리: `import com.celfit.was.v1.common.CacheKeys;` 추가 후 본문에서는 `CacheKeys.sha256(...)`.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.content.V1ContentQueryTest"`
Expected: PASS

- [ ] **Step 5: V1ContentPageService 생성**:

```java
package com.celfit.was.v1.content;

import com.celfit.was.config.CacheConfig;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.1 목록의 공통 페이지 묶음(개인화 제외) — Redis 캐시 단위(스펙 §4). isSaved 오버레이는
 * 컨트롤러가 캐시 밖에서 얹는다 → 익명 간 캐시 완전 공유 + 저장 직후에도 항상 실시간 정확(스펙 §6).
 */
@Service
public class V1ContentPageService {

	private final V1ContentRepository repository;

	public V1ContentPageService(V1ContentRepository repository) {
		this.repository = repository;
	}

	@Cacheable(cacheNames = CacheConfig.CONTENT_RANKING, key = "#q.cacheKey()", sync = true)
	public ContentPage page(V1ContentQuery q) {
		return new ContentPage(repository.findCards(q), repository.countCards(q));
	}

	/** meta.distributors — 소형 조회라 캐시 없이 통과. */
	public List<Map<String, Object>> distributorOptions() {
		return repository.findDistributorOptions();
	}

	/** 캐시에 실리는 페이지 묶음 — rows는 개인화 없는 공통 행. */
	public record ContentPage(List<ContentCardRow> rows, long total) {
	}
}
```

- [ ] **Step 6: 컨트롤러 전환** — `V1ContentController.java` 전체를 다음으로 교체(리포지토리 직결 제거, 프리페치 추가):

```java
package com.celfit.was.v1.content;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.common.SavedLookup;
import com.celfit.was.v1.content.V1ContentPageService.ContentPage;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.1 리더보드 — 인증 Optional(SecurityConfig permitAll). 공통 페이지는 Redis 캐시(pageService),
 * 로그인 시에만 카드에 isContentsSaved를 캐시 밖에서 오버레이하고, 비로그인이면 필드 자체가 없다
 * (스펙 2절 규약). principal은 익명이면 null(AppUserDetails 미일치).
 */
@RestController
public class V1ContentController {

	private final V1ContentPageService pageService;
	private final ContentCardAssembler assembler;
	private final SavedLookup savedLookup;
	private final PagePrefetcher prefetcher;

	public V1ContentController(V1ContentPageService pageService, ContentCardAssembler assembler,
			SavedLookup savedLookup, PagePrefetcher prefetcher) {
		this.pageService = pageService;
		this.assembler = assembler;
		this.savedLookup = savedLookup;
		this.prefetcher = prefetcher;
	}

	@GetMapping("/v1/contents")
	public ApiResponse<List<ContentCard>> contents(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(required = false) String contentType,
			@RequestParam(required = false) String mainCategory,
			@RequestParam(required = false) String midCategory,
			@RequestParam(required = false) String subCategory,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String adType,
			@RequestParam(required = false) String distributorId,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		V1ContentQuery query = V1ContentQuery.of(startDate, endDate, contentType, mainCategory,
				midCategory, subCategory, follower, keyword, adType, distributorId, sort, limit,
				offset);
		ContentPage page = pageService.page(query);
		// 로그인 시에만 저장 셋을 1회 조회해 각 카드를 마킹, 비로그인이면 saved=null(필드 부재).
		Set<String> savedCodes = principal == null ? null : savedLookup.savedShortCodes(principal.getUserId());
		List<ContentCard> cards = page.rows().stream()
				.map(row -> assembler.toCard(row,
						savedCodes == null ? null : savedCodes.contains(row.shortCode())))
				.toList();
		if (PagePrefetcher.hasNextPage(page.rows().size(), query.limit(), query.offset(), page.total())) {
			prefetcher.prefetch(() -> pageService.page(query.next()));
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", page.total());
		meta.put("limit", query.limit());
		meta.put("offset", query.offset());
		meta.put("distributors", pageService.distributorOptions());
		return ApiResponse.ok(cards, meta);
	}
}
```

- [ ] **Step 7: V1ContentControllerTest 수정** — 파일을 열어 다음 치환을 적용(기존 단언은 유지):
  - `@MockitoBean V1ContentRepository repository` → `@MockitoBean V1ContentPageService pageService` + `@MockitoBean PagePrefetcher prefetcher` (import `com.celfit.was.v1.common.PagePrefetcher`, `com.celfit.was.v1.content.V1ContentPageService.ContentPage`)
  - 스텁 치환: `given(repository.findCards(any())).willReturn(rows)` + `given(repository.countCards(any())).willReturn(N)` → `given(pageService.page(any())).willReturn(new ContentPage(rows, N))`
  - `given(repository.findDistributorOptions()).willReturn(...)` → `given(pageService.distributorOptions()).willReturn(...)`
  - `@Import`에 `ContentCardAssembler`가 이미 있으면 유지(컨트롤러가 계속 사용).

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.content.*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/content/ was/src/test/java/com/celfit/was/v1/content/
git commit -m "feat(was): 랭킹 목록 공통 페이지 Redis 캐시 + 다음 페이지 프리페치 (6.1)"
```

> 구현 노트(리뷰 반영): ① 스펙 §4의 커스텀 KeyGenerator 대신 SpEL `#q.cacheKey()` 채택(더 명시적). ② 스펙 §5의 '캐시에 있으면 스킵'은 `@Cacheable(sync=true)` 위임으로 충족(Redis GET 1회 = 사실상 스킵; 단 Redis 장애 시 프리페치가 실DB 조회가 되는 부작용 있음 — 상한은 풀 max 2). ③ 캐시 값 스키마 세대를 빌드 시각 epoch로 prefix에 반영(`buildInfo()`) — 배포마다 콜드 캐시(수용). 부작용: `:was:test`가 매 빌드 재실행됨(build-info 갱신 때문, 로컬 증분 빌드 비용으로 수용).

---

### Task 4: 발굴 목록 — V1InfluencerDiscoveryPageService + 컨트롤러 전환 + 프리페치

**Files:**
- Modify: `was/.../v1/influencer/V1InfluencerDiscoveryQuery.java` (cacheKey·next 추가)
- Create: `was/.../v1/influencer/V1InfluencerDiscoveryPageService.java`
- Modify: `was/.../v1/influencer/V1InfluencerDiscoveryController.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryQueryTest.java` (기존 파일에 추가)
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryControllerTest.java` (수정)

- [ ] **Step 1: 쿼리 record 실패 테스트** — 기존 `V1InfluencerDiscoveryQueryTest`에 추가:

```java
	@Test
	void 같은_조건은_같은_캐시_키_페이지가_다르면_다른_키() {
		V1InfluencerDiscoveryQuery a = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 0);
		V1InfluencerDiscoveryQuery b = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 0);
		assertThat(a.cacheKey()).isEqualTo(b.cacheKey());
		assertThat(a.cacheKey()).isNotEqualTo(a.next().cacheKey());
	}

	@Test
	void next는_offset만_limit만큼_전진() {
		V1InfluencerDiscoveryQuery next = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 100).next();
		assertThat(next.offset()).isEqualTo(150);
		assertThat(next.limit()).isEqualTo(50);
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryQueryTest"`
Expected: 컴파일 실패

- [ ] **Step 3: V1InfluencerDiscoveryQuery에 메서드 추가** (import `com.celfit.was.v1.common.CacheKeys`):

```java
	/** 캐시 키(스펙 §4) — of()가 정규화를 끝낸 컴포넌트 전체의 toString 축약. record toString은 결정적. */
	public String cacheKey() {
		return CacheKeys.sha256(toString());
	}

	/** 다음 페이지 쿼리 — 프리페치용(스펙 §5). */
	public V1InfluencerDiscoveryQuery next() {
		return new V1InfluencerDiscoveryQuery(keywords, mainCategory, midCategory, subCategory,
				follower, activityDays, sponsored, contactOpen, sort, limit, offset + limit);
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryQueryTest"`
Expected: PASS

- [ ] **Step 5: V1InfluencerDiscoveryPageService 생성** — 발굴 목록은 개인화 필드가 없으므로(컨트롤러 주석 "응답에 개인화 필드가 없다" — 스펙 §6의 '구현 시 확인' 항목 확인 완료) **조립 완료된 카드**를 통째로 캐싱:

```java
package com.celfit.was.v1.influencer;

import com.celfit.was.config.CacheConfig;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.21 발굴 목록 페이지 묶음 — Redis 캐시 단위(스펙 §4). 응답에 개인화 필드가 없어(저장 여부는
 * 프론트가 6.9 캐시에서 파생) 본 쿼리+보강 3쿼리+조립까지 통째로 캐싱한다.
 */
@Service
public class V1InfluencerDiscoveryPageService {

	private final V1InfluencerDiscoveryRepository repository;
	private final V1InfluencerDiscoveryAssembler assembler;

	public V1InfluencerDiscoveryPageService(V1InfluencerDiscoveryRepository repository,
			V1InfluencerDiscoveryAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_DISCOVERY, key = "#q.cacheKey()", sync = true)
	public DiscoveryPage page(V1InfluencerDiscoveryQuery q) {
		List<V1InfluencerDiscoveryRepository.CardRow> rows = repository.findCards(q);
		List<String> handles = rows.stream()
				.map(V1InfluencerDiscoveryRepository.CardRow::handle).toList();
		List<InfluencerCard> cards = assembler.toCards(rows, repository.findShares(handles),
				repository.findBrands(handles), repository.findThumbs(handles));
		return new DiscoveryPage(cards, repository.countCards(q));
	}

	/** 캐시에 실리는 페이지 묶음 — 조립 완료 카드(개인화 없음). */
	public record DiscoveryPage(List<InfluencerCard> cards, long total) {
	}
}
```

- [ ] **Step 6: 컨트롤러 전환** — `V1InfluencerDiscoveryController.java` 전체 교체:

```java
package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.21 발굴 목록 — 인증 Public(SecurityConfig permitAll): 비로그인 공개 페이지고 응답에 개인화 필드가
 * 없다(카드 저장 여부는 프론트가 6.9 저장 목록 캐시에서 파생). 페이지는 Redis 캐시(pageService).
 * 쿼리 파라미터는 camelCase(6.1 관례) — 프론트 앱 URL snake_case와의 변환은 프론트 fetch 레이어 책임.
 */
@RestController
public class V1InfluencerDiscoveryController {

	private final V1InfluencerDiscoveryPageService pageService;
	private final PagePrefetcher prefetcher;

	public V1InfluencerDiscoveryController(V1InfluencerDiscoveryPageService pageService,
			PagePrefetcher prefetcher) {
		this.pageService = pageService;
		this.prefetcher = prefetcher;
	}

	@GetMapping("/v1/influencers")
	public ApiResponse<List<InfluencerCard>> influencers(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String mainCategory,
			@RequestParam(required = false) String midCategory,
			@RequestParam(required = false) String subCategory,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String activity,
			@RequestParam(required = false) String sponsored,
			@RequestParam(required = false) String contact,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(q, mainCategory,
				midCategory, subCategory, follower, activity, sponsored, contact, sort, limit,
				offset);
		DiscoveryPage page = pageService.page(query);
		if (PagePrefetcher.hasNextPage(page.cards().size(), query.limit(), query.offset(), page.total())) {
			prefetcher.prefetch(() -> pageService.page(query.next()));
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", page.total());
		meta.put("limit", query.limit());
		meta.put("offset", query.offset());
		return ApiResponse.ok(page.cards(), meta);
	}
}
```

- [ ] **Step 7: V1InfluencerDiscoveryControllerTest 수정** — 컨트롤러가 리포지토리·어셈블러를 더 안 쓰므로:
  - `@MockitoBean V1InfluencerDiscoveryRepository repository` → `@MockitoBean V1InfluencerDiscoveryPageService pageService` + `@MockitoBean PagePrefetcher prefetcher`
  - `@Import`에서 `V1InfluencerDiscoveryAssembler.class` 제거(대신 테스트 픽스처에서 직접 인스턴스화)
  - `익명_200_카드와_meta` 테스트의 스텁·검증 교체:

```java
	@Test
	void 익명_200_카드와_meta() throws Exception {
		List<InfluencerCard> cards = new V1InfluencerDiscoveryAssembler()
				.toCards(List.of(row("glow")), List.of(), List.of(), List.of());
		given(pageService.page(any())).willReturn(new DiscoveryPage(cards, 109L));

		mockMvc.perform(get("/v1/influencers?sponsored=1-2&offset=100"))
				.andExpect(status().isOk()) // 로그인 월 예외(permitAll) — 비로그인 공개 페이지
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value("glow"))
				.andExpect(jsonPath("$.data[0].handle").value("glow"))
				.andExpect(jsonPath("$.data[0].email").value((String) null)) // null 노출(부재 아님)
				.andExpect(jsonPath("$.data[0].reachMultiplier").value(12.4))
				.andExpect(jsonPath("$.data[0].collaboratedBrands").isArray())
				.andExpect(jsonPath("$.error").value((String) null))
				.andExpect(jsonPath("$.meta.total").value(109))
				.andExpect(jsonPath("$.meta.limit").value(100))
				.andExpect(jsonPath("$.meta.offset").value(100));
	}
```

  (기존 `ArgumentCaptor`로 `findThumbs` 핸들을 검증하던 단언은 삭제 — 해당 배선은 서비스 내부로 이동했고, 캐시 통합 테스트(Task 6)와 기존 리포지토리 테스트가 커버.) `잘못된_enum은_400_VALIDATION_FAILED` 테스트는 그대로 두면 통과(쿼리 검증은 컨트롤러 진입 시점 그대로).
  import 추가: `com.celfit.was.v1.common.PagePrefetcher`, `com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage`.

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.*"`
Expected: PASS (V1InfluencerDiscoveryRepositoryTest 등 기존 테스트 포함 전부)

- [ ] **Step 9: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): 발굴 목록 페이지 Redis 캐시 + 다음 페이지 프리페치 (6.21)"
```

---

### Task 5: AI 리포트 2종 — 서비스 추출 + `@Cacheable`

**Files:**
- Create: `was/.../v1/influencer/V1InfluencerReportService.java`
- Create: `was/.../v1/content/V1ContentReportService.java`
- Modify: `was/.../v1/influencer/V1InfluencerReportController.java`
- Modify: `was/.../v1/content/V1ContentReportController.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java` (수정)
- Test: `was/src/test/java/com/celfit/was/v1/content/V1ContentReportControllerTest.java` (수정)

- [ ] **Step 1: V1InfluencerReportService 생성** — 컨트롤러 본문을 그대로 이식. 404는 예외로 던져지므로 **캐시에 실리지 않는다**(부재 결과 비캐싱 — 의도된 동작):

```java
package com.celfit.was.v1.influencer;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 6.5 리포트 조립 — 단일 리소스 키 Redis 캐시(TTL 6h, 스펙 §4). 404는 예외라 캐시에 안 실린다. */
@Service
public class V1InfluencerReportService {

	private final V1InfluencerReportRepository repository;
	private final V1InfluencerReportAssembler assembler;

	public V1InfluencerReportService(V1InfluencerReportRepository repository,
			V1InfluencerReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_REPORT, key = "#influencerId", sync = true)
	public InfluencerAiReport report(String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		return assembler.toReport(summary,
				repository.findLatestCopy(influencerId).orElse(null),
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrands(influencerId));
	}
}
```

- [ ] **Step 2: V1InfluencerReportController 전환** — 전체 교체:

```java
package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.5 인플루언서 AI 리포트 — influencerId는 handle 그대로(6.4와 동일 설계). 조립·캐시는 서비스. */
@RestController
public class V1InfluencerReportController {

	private final V1InfluencerReportService service;

	public V1InfluencerReportController(V1InfluencerReportService service) {
		this.service = service;
	}

	@GetMapping("/v1/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReport> aiReport(@PathVariable String influencerId) {
		return ApiResponse.ok(service.report(influencerId));
	}
}
```

- [ ] **Step 3: V1ContentReportService 생성** — 카테고리 맥락 분기까지 통째로 이식:

```java
package com.celfit.was.v1.content;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 6.3 리포트 조립 — 단일 리소스 키 Redis 캐시(TTL 6h, 스펙 §4). 404는 예외라 캐시에 안 실린다. */
@Service
public class V1ContentReportService {

	private final V1ContentReportRepository repository;
	private final V1ContentReportAssembler assembler;

	public V1ContentReportService(V1ContentReportRepository repository,
			V1ContentReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.CONTENT_REPORT, key = "#contentId", sync = true)
	public ContentAiReport report(String contentId) {
		var report = repository.findReport(contentId)
				.orElseThrow(() -> V1ApiException.notFound("콘텐츠를 찾을 수 없습니다."));
		// 카테고리 맥락은 대분류가 있을 때만 집계한다 (미분류면 비교 모수 자체가 정의되지 않음).
		var categoryContext = report.mainCategory() == null ? null
				: repository.findCategoryContext(report.mainCategory(), report.views());
		return assembler.toReport(report,
				repository.findRecentReels(report.accountHandle()),
				categoryContext,
				repository.countByCategory(contentId),
				repository.findComments(contentId));
	}
}
```

- [ ] **Step 4: V1ContentReportController 전환** — 전체 교체:

```java
package com.celfit.was.v1.content;

import com.celfit.was.v1.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.3 콘텐츠 AI 리포트 — 인증 Optional이나 P1은 비로그인 응답과 동일(개인화 필드 없음). 조립·캐시는 서비스. */
@RestController
public class V1ContentReportController {

	private final V1ContentReportService service;

	public V1ContentReportController(V1ContentReportService service) {
		this.service = service;
	}

	@GetMapping("/v1/contents/{contentId}/ai-report")
	public ApiResponse<ContentAiReport> aiReport(@PathVariable String contentId) {
		return ApiResponse.ok(service.report(contentId));
	}
}
```

- [ ] **Step 5: 리포트 컨트롤러 테스트 2종 수정** — 각 파일에서:
  - `@MockitoBean` 리포지토리(+`@Import` 어셈블러)를 `@MockitoBean V1InfluencerReportService service`(콘텐츠 쪽은 `V1ContentReportService`)로 교체.
  - 정상 케이스: 기존에 리포지토리 스텁 → 어셈블러 실통과로 만들던 기대 리포트를, **테스트 안에서 어셈블러를 직접 인스턴스화해 같은 스텁 입력으로 조립**한 뒤 `given(service.report("<id>")).willReturn(조립결과)`로 스텁. 기존 jsonPath 단언은 그대로 유지.
  - 404 케이스: `given(service.report("nope")).willThrow(V1ApiException.notFound("인플루언서를 찾을 수 없습니다."))` (콘텐츠 쪽 메시지는 "콘텐츠를 찾을 수 없습니다.") — 기존 404 단언 유지.

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReportControllerTest" --tests "com.celfit.was.v1.content.V1ContentReportControllerTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/ was/src/test/java/com/celfit/was/v1/
git commit -m "feat(was): AI 리포트 2종 서비스 추출 + Redis 캐시 6h (6.3·6.5)"
```

---

### Task 6: 캐시 통합 테스트 — 히트·TTL·프리페치·JSON 왕복·fail-open

**Files:**
- Create: `was/src/test/java/com/celfit/was/ContentCacheSeed.java` (테스트 헬퍼)
- Create: `was/src/test/java/com/celfit/was/cache/CacheIntegrationTest.java`
- Create: `was/src/test/java/com/celfit/was/cache/CacheFailOpenIntegrationTest.java`

- [ ] **Step 1: 시드 헬퍼 작성** — 6.1 콘텐츠 경로 최소 형상(기존 `V1InfluencerDiscoveryRepositoryTest`의 "분석 DB 형상 DDL 사본" 관용구). `ContentCardRow.SELECT`가 참조하는 컬럼 전부 포함:

```java
package com.celfit.was;

import org.springframework.jdbc.core.JdbcTemplate;

/** 캐시 통합 테스트용 6.1 콘텐츠 경로 최소 형상 DDL·시드 — 분석 DB 형상 사본(필요 컬럼만). */
public final class ContentCacheSeed {

	private ContentCacheSeed() {
	}

	public static void reset(JdbcTemplate jdbc) {
		jdbc.execute("DROP TABLE IF EXISTS content_analyses");
		jdbc.execute("DROP TABLE IF EXISTS contents");
		jdbc.execute("DROP TABLE IF EXISTS accounts");
		jdbc.execute("DROP TABLE IF EXISTS image_assets");
		jdbc.execute("DROP TABLE IF EXISTS beauty_distributors");
		jdbc.execute("""
				CREATE TABLE contents (
				    short_code         text PRIMARY KEY,
				    account_handle     text,
				    caption            text,
				    thumbnail_url      text,
				    posted_at          timestamptz,
				    content_type       text,
				    video_duration     numeric,
				    original_url       text,
				    views              bigint,
				    likes              bigint,
				    comments           bigint,
				    hype_score         bigint,
				    metric_captured_at timestamptz
				)""");
		jdbc.execute("""
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    is_beauty             boolean,
				    metric_timeliness     text,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb
				)""");
		jdbc.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbc.execute("CREATE TABLE image_assets (kind text NOT NULL, key text NOT NULL, object_path text)");
		jdbc.execute("CREATE TABLE beauty_distributors (slug text PRIMARY KEY, name text)");
		jdbc.execute("INSERT INTO accounts VALUES ('glow', '글로우', null, 20000)");
		// hype 내림차순: c1(90) → c2(80)
		jdbc.execute("""
				INSERT INTO contents VALUES
				  ('c1', 'glow', '수분크림 리뷰', null, now() - interval '1 day', 'reels', null, null, 1000, 100, 10, 90, now()),
				  ('c2', 'glow', '선크림 리뷰',   null, now() - interval '2 day', 'reels', null, null,  800,  80,  8, 80, now())""");
		jdbc.execute("""
				INSERT INTO content_analyses (short_code, is_beauty, metric_timeliness)
				VALUES ('c1', true, 'timely'), ('c2', true, 'timely')""");
	}
}
```

- [ ] **Step 2: CacheIntegrationTest 작성** — Redis 컨테이너는 이 클래스 전용(공유 베이스는 건드리지 않음 — Redis 없는 기존 통합 테스트들이 fail-open의 상시 회귀 가드가 되도록 유지):

```java
package com.celfit.was.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.ContentCacheSeed;
import com.celfit.was.IntegrationTest;
import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.content.V1ContentController;
import com.celfit.was.v1.content.V1ContentPageService;
import com.celfit.was.v1.content.V1ContentQuery;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ShareRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ThumbRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** 캐시 왕복 대표 검증은 6.1 경로 — 나머지 캐시 3종은 같은 CacheConfig 메커니즘(직렬화 왕복만 별도 검증). */
class CacheIntegrationTest extends IntegrationTest {

	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	CacheManager cacheManager;

	@Autowired
	V1ContentPageService pageService;

	@Autowired
	V1ContentController controller;

	@BeforeEach
	void setUp() {
		ContentCacheSeed.reset(jdbcTemplate);
		cacheManager.getCacheNames()
				.forEach(name -> cacheManager.getCache(name).clear());
	}

	private static V1ContentQuery q(int limit, int offset) {
		return V1ContentQuery.of(LocalDate.now().minusDays(7), LocalDate.now(),
				null, null, null, null, null, null, null, null, null, limit, offset);
	}

	@Test
	void 두번째_호출은_캐시_히트라_DB_변경이_안_보인다() {
		var first = pageService.page(q(1, 0));
		assertThat(first.rows()).extracting(ContentCardRow::shortCode).containsExactly("c1");
		assertThat(first.total()).isEqualTo(2);

		jdbcTemplate.update("UPDATE contents SET caption = '변경됨' WHERE short_code = 'c1'");

		var second = pageService.page(q(1, 0));
		assertThat(second.rows().get(0).caption()).isEqualTo("수분크림 리뷰"); // 캐시 히트 증거(TTL 내 stale 허용)
	}

	@Test
	void 캐시_키에_TTL이_설정된다() throws Exception {
		V1ContentQuery query = q(1, 0);
		pageService.page(query);
		var result = REDIS.execInContainer("redis-cli", "ttl",
				"hypenow:cache:content-ranking:" + query.cacheKey());
		long ttl = Long.parseLong(result.getStdout().trim());
		assertThat(ttl).isBetween(1L, 3600L); // content-ranking 1h (스펙 §4)
	}

	@Test
	void 응답_후_다음_페이지가_프리페치된다() throws Exception {
		controller.contents(null, LocalDate.now().minusDays(7), LocalDate.now(),
				null, null, null, null, null, null, null, null, null, 1, 0);
		V1ContentQuery next = q(1, 0).next();
		Cache cache = cacheManager.getCache(CacheConfig.CONTENT_RANKING);
		awaitCached(cache, next.cacheKey());
		var cached = (V1ContentPageService.ContentPage) cache.get(next.cacheKey()).get();
		assertThat(cached.rows()).extracting(ContentCardRow::shortCode).containsExactly("c2");
	}

	@Test
	void 발굴_카드_중첩_레코드가_JSON_왕복된다() {
		InfluencerCard card = new V1InfluencerDiscoveryAssembler().toCards(
				List.of(new CardRow("glow", "글로우", null, 20000L, 19662L, 214L, 380L, "소개",
						"저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
						413200L, 10370L, 152L, 3L)),
				List.of(new ShareRow("glow", "skincare", 80)),
				List.of(new BrandRow("glow", "롬앤")),
				List.of(new ThumbRow("glow", "c1", null, "reels", "skincare", "organic",
						OffsetDateTime.now(ZoneOffset.UTC), 1000L, 100L, 10L)))
				.get(0);
		DiscoveryPage page = new DiscoveryPage(List.of(card), 1L);

		Cache cache = cacheManager.getCache(CacheConfig.INFLUENCER_DISCOVERY);
		cache.put("roundtrip", page);
		DiscoveryPage back = (DiscoveryPage) cache.get("roundtrip").get();

		assertThat(back).isEqualTo(page);
	}

	private static void awaitCached(Cache cache, String key) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (cache.get(key) != null) {
				return;
			}
			Thread.sleep(100);
		}
		Assertions.fail("프리페치 미적재: " + key);
	}
}
```

주의: Testcontainers 2.x에서 `GenericContainer` 패키지가 `org.testcontainers.containers`가 아니면(2.x 개편 — PostgreSQLContainer가 `org.testcontainers.postgresql`로 간 것처럼) IDE/컴파일 오류가 알려주는 실제 패키지로 import를 맞춘다. 별도 의존성 추가는 불필요(코어는 testcontainers-postgresql이 전이 제공).

- [ ] **Step 3: CacheFailOpenIntegrationTest 작성** — Redis가 **없어도**(닫힌 포트) 조회가 정상임을 명시 검증. `sync=true` 경로까지 fail-open이 걸리는지가 핵심:

```java
package com.celfit.was.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.ContentCacheSeed;
import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.content.V1ContentPageService;
import com.celfit.was.v1.content.V1ContentQuery;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Redis 불능(닫힌 포트) = 캐시 미스로 강등 — 조회는 DB 직행으로 정상(스펙 §7 fail-open). */
class CacheFailOpenIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void deadRedis(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.port", () -> 1); // 닫힌 포트 — 연결 즉시 거부
		registry.add("spring.data.redis.timeout", () -> "200ms");
		registry.add("spring.data.redis.connect-timeout", () -> "200ms");
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1ContentPageService pageService;

	@Test
	void 레디스_불능이어도_조회는_정상() {
		ContentCacheSeed.reset(jdbcTemplate);
		var page = pageService.page(V1ContentQuery.of(LocalDate.now().minusDays(7), LocalDate.now(),
				null, null, null, null, null, null, null, null, null, 100, 0));
		assertThat(page.total()).isEqualTo(2);
	}
}
```

**만약 이 테스트가 실패하면**(예외가 새어나옴): `sync=true` 경로는 `CacheErrorHandler`가 못 덮는 스프링 버전일 수 있다. 그 경우 4개 `@Cacheable`에서 `sync = true`를 제거하고(스탬피드 방어는 nice-to-have — 스펙 §4 "그 이상은 불필요") 이 테스트가 통과하는 것을 확인한다. PagePrefetcher 주석의 sync 언급도 같이 수정.

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.cache.*"`
Expected: PASS (5개 테스트)

- [ ] **Step 5: Commit**

```bash
git add was/src/test/java/com/celfit/was/ContentCacheSeed.java was/src/test/java/com/celfit/was/cache/
git commit -m "test(was): 캐시 통합 테스트 — 히트·TTL·프리페치·JSON 왕복·fail-open"
```

> 구현 노트: 테스트 5→11개로 확장(리뷰 누적 체크리스트 A~F — 미스==히트 동일성·OffsetDateTime 정규화 계약·리포트 왕복 3종·6h TTL·404 비캐싱·리포트 서비스 경유 히트). put→get 레이스의 원인은 SDR 4.1 기본 비동기 캐시 쓰기로 판명 → CacheConfig에 immediateWrites 채택(폴링 불필요화·put 실패 가시성·테스트 격리 동시 해결). 스펙 §8의 '개인화 오버레이 정확성'은 Task 3 컨트롤러 구조(캐시 밖 오버레이) + 기존 WebMvcTest로 커버, 'TTL 만료'는 TTL 값 검증으로 갈음.

---

### Task 7: 전체 검증 · 문서 갱신 · PR

**Files:**
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표 · §7 결정 기록)
- 커밋 포함: `docs/superpowers/specs/2026-07-28-redis-caching-design.md`, `docs/superpowers/plans/2026-07-28-redis-caching.md`

- [ ] **Step 1: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL — 실패 시 원인 수정 후 재실행(특히 컨트롤러 생성자 변경에 걸리는 기존 테스트)

- [ ] **Step 2: 전체 빌드(다른 모듈 회귀 확인)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: ARCHITECTURE.md 갱신** — §5 작업 트랙 표에 행 추가(기존 행 서식 그대로): 트랙명 "was Redis 캐싱", 상태 ✅, 내용 "조회 4경로 캐시(TTL 1h/6h)+다음 페이지 프리페치, 세션 JDBC 유지". §7 결정 기록에 항목 추가: "2026-07-28 Redis는 순수 캐시 전용(세션 이관 안 함 — 강제 로그아웃 금지 정책)·TTL 백스톱만·전면 fail-open. 스펙 docs/superpowers/specs/2026-07-28-redis-caching-design.md". 실제 서식은 파일을 열어 기존 항목에 맞춘다.

- [ ] **Step 4: 문서 커밋**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/2026-07-28-redis-caching-design.md docs/superpowers/plans/2026-07-28-redis-caching.md
git commit -m "docs: Redis 캐싱 도입 — 스펙·구현 계획·ARCHITECTURE 결정 기록"
```

- [ ] **Step 5: PR 생성** — push 후 develop 대상 PR (superpowers:finishing-a-development-branch 절차 준수, 사용자 확인 후):

```bash
git push -u origin feat/redis-caching
gh pr create --base develop --title "feat(was): 조회 Redis 캐싱 도입 — 목록·리포트 4경로 + 다음 페이지 프리페치" --body "$(cat <<'EOF'
## 요약
- Redis는 순수 캐시 전용(세션은 JDBC 유지 — 강제 로그아웃 금지 정책), 무효화 없이 TTL 백스톱만, 전면 fail-open(Redis 다운 = DB 직행)
- 캐시 4종: content-ranking·influencer-discovery(1h, 정규화 파라미터 SHA-256 키) / content-report·influencer-report(6h, id 키)
- 목록 2종은 응답 직후 다음 페이지(N+1)를 비동기 프리페치 — "다음 50개" 체감 지연 해소
- compose 3종(로컬·운영·dev)에 redis:7-alpine 추가(운영: maxmemory 256mb·allkeys-lru·AOF 없음·내부 네트워크 전용)
- 스펙: docs/superpowers/specs/2026-07-28-redis-caching-design.md

## 검증
- 캐시 통합 테스트(Testcontainers Redis): 히트·TTL·프리페치·중첩 record JSON 왕복
- fail-open 테스트: Redis 닫힌 포트에서도 조회 정상
- 기존 WebMvcTest 4종 서비스 경유로 갱신, ./gradlew test 전체 통과

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6: 배포 메모(PR 머지 후 별도 확인 사항)** — develop→main CD로 배포되면 운영에서:
  1. `docker compose ps`로 redis 컨테이너 기동 확인
  2. 랭킹/발굴 목록 두 번 호출해 두 번째 응답 시간 단축 스팟체크
  3. `redis-cli info memory | grep used_memory_human`으로 메모리 사용 관측(256mb 상한 대비)
  4. `redis-cli INFO stats`의 `evicted_keys`·`keyspace_hits/misses` 스팟체크 — evict 유의미하면 maxmemory 256mb 상향 검토(발굴 페이지가 무거워 빠듯할 수 있음)

---

## Self-Review 결과

- **스펙 커버리지**: §3 인프라=Task 1 · §4 캐시 4종·키·TTL·직렬화=Task 2/3/4/5 · §5 프리페치=Task 2(실행기)+3/4(배선), 프리웜 제외 유지 · §6 개인화 분리=기존 코드가 이미 분리(Task 3에서 캐시 경계만 확정) · §7 fail-open=Task 2+6 · §8 테스트=Task 6(+각 태스크 유닛) · §9 배포=Task 7 · §10 제외 항목 어느 태스크에도 미포함 — 갭 없음.
- **스펙 §6 '발굴 목록 개인화 유무 확인' 항목**: 확인 완료 — 응답에 개인화 필드 없음(컨트롤러 주석 명시) → 조립 결과 통째 캐싱(Task 4).
- **타입 일관성**: `ContentPage(rows, total)`·`DiscoveryPage(cards, total)`·`cacheKey()`·`next()`·`hasNextPage(returned, limit, offset, total)` 시그니처가 태스크 간 동일함을 재확인.
- **불확실 지점(구현 시 검증하도록 명시됨)**: ① Boot 4의 redis 스타터명(Task 1 Step 2) ② `RedisSerializer.json()` 존치 여부(Task 2 Step 7) ③ Testcontainers 2.x GenericContainer 패키지(Task 6 Step 2) ④ `sync=true`와 CacheErrorHandler 상호작용(Task 6 Step 3 — 실패 시 sync 제거 폴백 명시).
