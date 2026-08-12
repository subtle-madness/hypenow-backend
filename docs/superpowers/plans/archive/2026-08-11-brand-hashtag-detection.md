# 브랜드 해시태그 감지 구현 계획

> 상태: ✅ 실행됨 (2026-08-11 subagent-driven 실행 완료 — 태스크 13/13, 리뷰 사이클 반영분은 커밋 이력 참조)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 해시태그로만 브랜드를 언급한 게시물을 레이더에 추가한다 — 스펙 [2026-08-11-brand-hashtag-detection-design.md](../specs/archive/2026-08-11-brand-hashtag-detection-design.md)의 구현.

**Architecture:** monitoring 모듈에 해시태그 스윕 파이프라인(신규 3테이블 + `BrandHashtagCollectService` + Gemini 판정)을 기존 브랜드 스윕·등록 백필에 편승시키고, was는 등록 시 brandName 전달 + 피드 병합(`source: "hashtag"`) + 제외 문자열 프록시만 얹는다. 기존 유저태그 파이프라인·캠페인 테이블은 0줄 변경.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcTemplate(monitoring)·JdbcClient(was) / Flyway / Gemini REST(순수 JDK HttpClient — analytics `GeminiHttpApi` 관용구 이식) / 테스트는 monitoring 순수 단위(fake `HikerHttp` 람다 + 인메모리 스텁) · was `@WebMvcTest` 슬라이스 + Testcontainers 통합.

**전제(실행 전 확인):**
- 브랜치: `feat/brand-hashtag-detection` (worktree 권장 — `superpowers:using-git-worktrees`). PR은 develop 대상.
- 테스트 실행 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (미설정 시 Testcontainers 대량 실패 — CLAUDE.md 함정).
- 모듈 단위 실행: `./gradlew :monitoring:test`, `./gradlew :was:test`. 전체는 PR 직전에만.

---

## 파일 구조 총괄

**monitoring (생성):**
- `db/migration/V20260811150000__brand_hashtag_detection.sql` — 신규 3테이블
- `service/BrandHashtagTags.java` — 태그 셋·루트 유도(순수 정적 유틸)
- `service/BrandHashtagCollectService.java` — 스윕·필터·판정 파이프라인
- `store/BrandHashtagRepository.java` — 태그·제외 문자열·판정 게시물 저장
- `llm/GeminiHttp.java` + `llm/GeminiHttpTransport.java` + `llm/BrandMentionJudge.java` — LLM 판정
- `config/BrandHashtagConfig.java` — 빈 배선

**monitoring (수정):**
- `hiker/HikerClient.java` — `fetchHashtagRecentPage` + usertags 추출
- `service/BrandRegistrationService.java` — 태그 시드 + 백필 훅
- `service/BrandSweepJob.java` — 일일 스윕 합류
- `web/BrandController.java` — 등록 요청 `brandName` + 제외 문자열 GET/PUT
- `resources/application.yml` — 설정 키

**was (수정 — app 스키마 마이그레이션 없음):**
- `monitoring/MonitoringCommandClient.java` — registerBrand에 brandName, 제외 문자열 프록시 2종
- `monitoring/BrandReadRepository.java` — RELEVANT 게시물 조회
- `v1/brandmonitoring/V1BrandAccountService.java` — company_name 전달
- `v1/brandmonitoring/BrandPostAssembler.java`·`BrandPostResponse.java`·게시물 목록 서비스 — hashtag 소스 병합
- `v1/brandmonitoring/V1BrandAccountsController.java` — 제외 문자열 엔드포인트

**배포·문서:**
- `deploy/compose.yaml` — monitoring 컨테이너에 `GEMINI_API_KEY` env
- `docs/contracts/monitoring-was-contract.md` — hashtag 소스·제외 문자열 계약 추가

---

### Task 1: monitoring 마이그레이션 — 신규 3테이블

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260811150000__brand_hashtag_detection.sql`

- [ ] **Step 1: 마이그레이션 작성**

채번은 UTC 타임스탬프(작성 시각으로 갱신 — 아래는 예시가 아니라 실제 파일명 규칙 `V<YYYYMMDDHHMMSS>__`). 기존 정수 번호 rename 금지.

```sql
-- 브랜드 해시태그 감지(2026-08-11 스펙) — expand 단계 신규 3테이블.
-- 해시태그-only 언급 게시물의 발견·판정만 저장한다(보강·스냅샷 없음 — 스펙 §5 보류).
-- 기존 브랜드 7테이블·캠페인 테이블 비접촉(08-06 격리 원칙).

-- 브랜드별 스윕 대상 해시태그 — 등록 시 자동 유도 3종(#브랜드명·#루트·#전체계정명) 유니온 삽입.
CREATE TABLE brand_hashtag (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, tag)
);

-- 자사 계열 제외 문자열 — 게시자 username에 포함되면 SELF 판정. 기본값은 계정명 루트,
-- 유저가 was 경유로 관리(전체 교체 PUT).
CREATE TABLE brand_hashtag_exclusion (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    term       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, term)
);

-- 필터 도달 게시물 전량 저장(SELF·DIRECT_TAGGED·무관·불확실 포함) — 이 테이블이 조기 종료의
-- "기존 게시물" 판정 재료이자 dedup 키이자 재판정 재료다. 윈도우(90일) 밖은 미저장.
-- 피드 노출은 verdict='RELEVANT'만(스펙 §5).
CREATE TABLE brand_hashtag_post (
    brand_id               bigint      NOT NULL REFERENCES brand_account (id),
    short_code             text        NOT NULL,
    matched_tag            text        NOT NULL,   -- 발견 경로 태그(운영 디버그용)
    author_username        text        NOT NULL,
    author_full_name       text,
    author_profile_pic_url text,
    taken_at               timestamptz NOT NULL,
    caption                text        NOT NULL DEFAULT '',
    content_type           text,                   -- REELS / FEED (열거 관측)
    thumbnail_url          text,
    likes                  bigint,
    comments               bigint,
    verdict                text        NOT NULL CHECK (verdict IN
                               ('RELEVANT', 'UNCERTAIN', 'IRRELEVANT', 'SELF', 'DIRECT_TAGGED')),
    verdict_source         text        NOT NULL CHECK (verdict_source IN ('RULE', 'MENTION', 'LLM')),
    first_seen_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code)
);

-- was 피드 조회(RELEVANT만, 최신순) 전용 부분 인덱스.
CREATE INDEX brand_hashtag_post_feed_idx
    ON brand_hashtag_post (brand_id, taken_at DESC) WHERE verdict = 'RELEVANT';
```

was_reader SELECT 권한은 기존 `ALTER DEFAULT PRIVILEGES`(V2)가 자동 적용 — 별도 GRANT 불필요(V20260806150000 주석 참조).

- [ ] **Step 2: 컨텍스트 로드로 마이그레이션 적용 확인**

Run: `./gradlew :monitoring:test --tests "*ApplicationTest*" 2>/dev/null || ./gradlew :monitoring:test --tests "*ContextLoad*"` (컨텍스트 로드 테스트가 없으면 Task 4의 리포지토리 통합 테스트가 검증을 겸한다 — 이 경우 스킵하고 다음 단계로)
Expected: Flyway 적용 성공, BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add monitoring/src/main/resources/db/migration/V20260811150000__brand_hashtag_detection.sql
git commit -m "feat(monitoring): 브랜드 해시태그 감지 신규 3테이블 — 태그·제외 문자열·판정 게시물"
```

---

### Task 2: 태그 셋 유도 유틸 `BrandHashtagTags`

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagTags.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagTagsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrandHashtagTagsTest {

	@Test
	void 루트는_상용_접미사를_반복_제거한다() {
		assertThat(BrandHashtagTags.root("cclime_official")).isEqualTo("cclime");
		assertThat(BrandHashtagTags.root("brand.official")).isEqualTo("brand");
		assertThat(BrandHashtagTags.root("brand_official_kr")).isEqualTo("brand");
		assertThat(BrandHashtagTags.root("olens")).isEqualTo("olens");
	}

	@Test
	void 루트가_3자_미만이_되는_제거는_하지_않는다() {
		assertThat(BrandHashtagTags.root("ab_official")).isEqualTo("ab_official");
	}

	@Test
	void 태그셋은_브랜드명_루트_전체계정명_3종이다() {
		assertThat(BrandHashtagTags.derive("끌리메", "cclime_official"))
				.containsExactly("끌리메", "cclime", "cclime_official");
	}

	@Test
	void 브랜드명이_없으면_계정명_유도_2종만이다() {
		assertThat(BrandHashtagTags.derive(null, "cclime_official"))
				.containsExactly("cclime", "cclime_official");
		assertThat(BrandHashtagTags.derive("  ", "cclime_official"))
				.containsExactly("cclime", "cclime_official");
	}

	@Test
	void 브랜드명의_공백과_해시는_제거한다() {
		assertThat(BrandHashtagTags.derive("끌리메 뷰티", "cclime_official"))
				.contains("끌리메뷰티");
		assertThat(BrandHashtagTags.derive("#끌리메", "cclime_official")).contains("끌리메");
	}

	@Test
	void 해시태그_불가_문자를_포함한_후보는_버린다() {
		// IG 해시태그는 점(.)에서 끊긴다 — 점 포함 계정명의 전체계정명 태그는 성립 불가
		assertThat(BrandHashtagTags.derive(null, "cclime.beauty"))
				.containsExactly("cclime");
	}

	@Test
	void 중복_유도는_한_번만_남는다() {
		assertThat(BrandHashtagTags.derive("olens", "olens")).containsExactly("olens");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagTagsTest"`
Expected: 컴파일 실패 (`BrandHashtagTags` 미존재)

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 해시태그 감지 태그 셋 유도(스펙 2026-08-11 §2) — 등록 데이터에서 전자동 3종:
 * #브랜드명(company_name, brand 유형만 — 판별은 was 소관, 여기엔 null로 옴) ·
 * #계정명 루트(상용 접미사 제거) · #전체 계정명. 유저 입력·시드 분석 없음.
 */
public final class BrandHashtagTags {

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");
	private static final List<String> SUFFIXES = List.of("official", "kr", "korea", "global");
	private static final int MIN_ROOT_LENGTH = 3;

	private BrandHashtagTags() {
	}

	/** 계정명 루트 — cclime_official → cclime. 접미사는 반복 제거하되 3자 미만이 되면 중단. */
	public static String root(String username) {
		String u = username.toLowerCase(Locale.ROOT).strip();
		boolean stripped = true;
		while (stripped) {
			stripped = false;
			for (String s : SUFFIXES) {
				for (String sep : List.of("_", ".")) {
					String suffix = sep + s;
					if (u.endsWith(suffix) && u.length() - suffix.length() >= MIN_ROOT_LENGTH) {
						u = u.substring(0, u.length() - suffix.length());
						stripped = true;
					}
				}
			}
		}
		return u;
	}

	/** 태그 셋 3종(순서 보존·중복 제거·해시태그 불가 문자 필터). brandName null·공백이면 2종. */
	public static LinkedHashSet<String> derive(String brandName, String username) {
		LinkedHashSet<String> tags = new LinkedHashSet<>();
		if (brandName != null && !brandName.isBlank()) {
			tags.add(brandName.strip().replaceAll("[\\s#]+", ""));
		}
		String u = username.toLowerCase(Locale.ROOT).strip();
		tags.add(root(u));
		tags.add(u);
		tags.removeIf(t -> t.isBlank() || !VALID_TAG.matcher(t).matches());
		return tags;
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagTagsTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagTags.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagTagsTest.java
git commit -m "feat(monitoring): 해시태그 태그 셋 유도 유틸 — 브랜드명·루트·전체계정명 3종"
```

---

### Task 3: `HikerClient.fetchHashtagRecentPage`

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java` (fetchTaggedPage L207–237 아래에 추가)
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientHashtagTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

기존 관용구 그대로: `HikerClient`는 `HikerHttp` 람다로 조립한다(`BrandCollectServiceTest` 참조). v2 해시태그 recent 응답은 `{response:{sections:[{layout_content:{medias:[{media:{...}}]}}], next_page_id}}` 셰이프이고, `usertags.in[].user.username`이 실린다(2026-08-11 PoC 실측 — 원본 `poc-clime/06-recent-끌리메-p1.json`).

```java
package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HikerClientHashtagTest {

	private final List<String> calls = new ArrayList<>();

	private HikerClient client(String body) {
		return new HikerClient(path -> {
			calls.add(path);
			return body;
		});
	}

	private static String sectionsBody(String nextPageId, String... medias) {
		String items = String.join(",", medias);
		String cursor = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"sections":[{"layout_content":{"medias":[%s]}}],
				 "next_page_id":%s,"more_available":%s}}"""
				.formatted(items, cursor, nextPageId != null);
	}

	private static String media(String code, long takenAt, String username, String taggedUser) {
		String usertags = taggedUser == null ? "{\"in\":[]}"
				: "{\"in\":[{\"user\":{\"username\":\"" + taggedUser + "\"}}]}";
		return """
				{"media":{"code":"%s","taken_at":%d,"media_type":1,
				 "caption":{"text":"캡션 #끌리메"},
				 "user":{"username":"%s","pk":"111"},
				 "like_count":10,"comment_count":2,"usertags":%s}}"""
				.formatted(code, takenAt, username, usertags);
	}

	@Test
	void 섹션_셰이프에서_게시물과_커서를_파싱한다() {
		HikerClient.HashtagPage page = client(sectionsBody("p2",
				media("AAA", 1786000000L, "poster1", null),
				media("BBB", 1786000001L, "poster2", "cclime_official")))
				.fetchHashtagRecentPage("끌리메", null);

		assertThat(calls).containsExactly("/v2/hashtag/medias/recent?name=%EB%81%8C%EB%A6%AC%EB%A9%94");
		assertThat(page.posts()).hasSize(2);
		assertThat(page.posts().get(0).post().shortCode()).isEqualTo("AAA");
		assertThat(page.posts().get(0).taggedUsernames()).isEmpty();
		assertThat(page.posts().get(1).taggedUsernames()).containsExactly("cclime_official");
		assertThat(page.nextPageId()).isEqualTo("p2");
	}

	@Test
	void 커서를_넘기면_page_id_파라미터가_붙는다() {
		client(sectionsBody(null)).fetchHashtagRecentPage("cclime", "p2");
		assertThat(calls).containsExactly("/v2/hashtag/medias/recent?name=cclime&page_id=p2");
	}

	@Test
	void 마지막_페이지는_커서가_null이다() {
		HikerClient.HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", null)))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void 태그_404는_빈_페이지로_접는다() {
		HikerClient client = new HikerClient(path -> {
			throw new SubjectNotFoundException("Entries not found");
		});
		HikerClient.HashtagPage page = client.fetchHashtagRecentPage("없는태그", null);
		assertThat(page.posts()).isEmpty();
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void usertags의_username은_소문자로_정규화한다() {
		HikerClient.HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", "CClime_Official")))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.posts().get(0).taggedUsernames()).containsExactly("cclime_official");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientHashtagTest"`
Expected: 컴파일 실패 (`HashtagPage` 미존재)

- [ ] **Step 3: 구현 — `HikerClient`에 추가**

`fetchTaggedPage` 바로 아래에 추가. 기존 private 유틸(`root`/`toPost`/`pageParam`/`nextPageId`/`moreAvailable`/`enc`)을 그대로 재사용한다.

```java
	/** 해시태그 recent 스트림 게시물 + 사진 태그된 계정 목록(소문자 정규화). */
	public record HashtagPost(PostInfo post, List<String> taggedUsernames) {}

	public record HashtagPage(List<HashtagPost> posts, String nextPageId) {}

	/**
	 * 해시태그 recent 열거 1페이지(스펙 2026-08-11 §3) — 섹션 셰이프
	 * {response:{sections:[{layout_content:{medias:[{media}]}}]}}를 우선 파싱하고,
	 * 평탄 items 셰이프는 폴백. usertags는 직접태그 제외 판정 재료(추가 콜 없음 — PoC 실측 §9).
	 */
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		String body;
		try {
			body = http.get("/v2/hashtag/medias/recent?name=" + enc(tag) + pageParam(pageId));
		} catch (SubjectNotFoundException e) {
			log.info("해시태그 열거 404 — tag {} page_id {}, 게시물 없음/커서 종료로 간주", tag, pageId);
			return new HashtagPage(List.of(), null);
		}
		JsonNode root = root(body);
		List<HashtagPost> posts = new ArrayList<>();
		for (JsonNode item : hashtagItems(root)) {
			posts.add(new HashtagPost(toPost(item, null, body, Map.of(), true), taggedUsernames(item)));
		}
		String cursor = moreAvailable(root) ? nextPageId(root) : null;
		return new HashtagPage(posts, cursor);
	}

	private static List<JsonNode> hashtagItems(JsonNode root) {
		JsonNode res = root.has("response") ? root.path("response") : root;
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode section : res.path("sections")) {
			for (JsonNode media : section.path("layout_content").path("medias")) {
				JsonNode m = media.path("media");
				if (!m.isMissingNode() && !m.isNull()) {
					out.add(m);
				}
			}
		}
		if (out.isEmpty()) {
			for (JsonNode item : res.path("items")) {
				out.add(item.has("media") ? item.path("media") : item);
			}
		}
		return out;
	}

	private static List<String> taggedUsernames(JsonNode media) {
		List<String> out = new ArrayList<>();
		for (JsonNode in : media.path("usertags").path("in")) {
			String username = in.path("user").path("username").asString(null);
			if (username != null && !username.isBlank()) {
				out.add(username.toLowerCase(java.util.Locale.ROOT));
			}
		}
		return out;
	}
```

주의: `toPost(item, null, body, Map.of(), true)`의 시그니처 인자 순서는 기존 `fetchTaggedPage` L221의 호출과 동일하게 맞출 것(파일에서 확인 후 그대로 복제).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientHashtagTest"`
Expected: PASS (5 tests). 섹션 파싱이 실패하면 실제 PoC 원본(`/private/tmp/claude-501/.../poc-clime/06-recent-끌리메-p1.json`)의 중첩 구조를 확인해 `hashtagItems`를 보정한다 — 테스트 fixture가 아니라 파서를 실셰이프에 맞추는 방향으로.

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java \
        monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientHashtagTest.java
git commit -m "feat(monitoring): Hiker 해시태그 recent 열거 — 섹션 셰이프 파싱 + usertags 추출"
```

---

### Task 4: `BrandHashtagRepository` + 통합 테스트

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/BrandHashtagRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagRepositoryTest.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

기존 monitoring 통합 테스트가 쓰는 Testcontainers 베이스가 있으면 그걸 상속/복제한다(`grep -rl "PostgreSQLContainer" monitoring/src/test`). 없으면 아래 표준형(Testcontainers 2.x — `org.testcontainers.postgresql.PostgreSQLContainer`):

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class BrandHashtagRepositoryTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	JdbcTemplate jdbc;
	BrandHashtagRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		repo = new BrandHashtagRepository(jdbc);
		jdbc.update("DELETE FROM brand_hashtag_post");
		jdbc.update("DELETE FROM brand_hashtag_exclusion");
		jdbc.update("DELETE FROM brand_hashtag");
		jdbc.update("DELETE FROM brand_account");
		jdbc.update("INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99')");
		brandId = jdbc.queryForObject("SELECT id FROM brand_account WHERE username = 'cclime_official'", Long.class);
	}

	@Test
	void 태그_삽입은_멱등이다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("끌리메", "cclime")));
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "cclime_official")));
		assertThat(repo.findTags(brandId)).containsExactly("끌리메", "cclime", "cclime_official");
	}

	@Test
	void 제외_문자열은_기본값_삽입_후_전체_교체가_가능하다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.insertDefaultExclusion(brandId, "cclime"); // 멱등
		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime");
		repo.replaceExclusionTerms(brandId, List.of("cclime", "cclimebeauty"));
		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime", "cclimebeauty");
		repo.replaceExclusionTerms(brandId, List.of());
		assertThat(repo.findExclusionTerms(brandId)).isEmpty();
	}

	@Test
	void 게시물_저장과_기존_코드_조회가_동작한다() {
		repo.insertPost(brandId, "끌리메", "AAA", "poster1", "포스터", "https://pic",
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), "캡션", "REELS", "https://thumb",
				10L, 2L, "RELEVANT", "LLM");
		// 같은 (brand, code) 재삽입은 무시(ON CONFLICT DO NOTHING)
		repo.insertPost(brandId, "cclime", "AAA", "poster1", null, null,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), "다른캡션", null, null,
				null, null, "IRRELEVANT", "LLM");
		Set<String> existing = repo.existingCodes(brandId, List.of("AAA", "BBB"));
		assertThat(existing).containsExactly("AAA");
		assertThat(jdbc.queryForObject(
				"SELECT verdict FROM brand_hashtag_post WHERE brand_id = ? AND short_code = 'AAA'",
				String.class, brandId)).isEqualTo("RELEVANT");
	}

	@Test
	void 빈_코드_목록은_빈_집합을_돌려준다() {
		assertThat(repo.existingCodes(brandId, List.of())).isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagRepositoryTest"`
Expected: 컴파일 실패 (`BrandHashtagRepository` 미존재)

- [ ] **Step 3: 구현**

기존 `BrandSnapshotRepository` 관용구(순수 JdbcTemplate, 리포지토리 무트랜잭션)를 따른다:

```java
package com.celfit.monitoring.store;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해시태그 감지 저장(스펙 2026-08-11) — 태그·제외 문자열·판정 게시물 3테이블.
 * 게시물은 필터 도달 전량(SELF·DIRECT_TAGGED 포함) 저장 — 조기 종료·dedup·재판정 재료.
 */
@Repository
public class BrandHashtagRepository {

	private final JdbcTemplate db;

	public BrandHashtagRepository(JdbcTemplate db) {
		this.db = db;
	}

	public List<String> findTags(long brandId) {
		return db.queryForList("SELECT tag FROM brand_hashtag WHERE brand_id = ? ORDER BY created_at, tag",
				String.class, brandId);
	}

	public void insertTags(long brandId, Collection<String> tags) {
		for (String tag : tags) {
			db.update("INSERT INTO brand_hashtag (brand_id, tag) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, tag);
		}
	}

	public List<String> findExclusionTerms(long brandId) {
		return db.queryForList(
				"SELECT term FROM brand_hashtag_exclusion WHERE brand_id = ? ORDER BY created_at, term",
				String.class, brandId);
	}

	public void insertDefaultExclusion(long brandId, String term) {
		db.update("INSERT INTO brand_hashtag_exclusion (brand_id, term) VALUES (?, ?) ON CONFLICT DO NOTHING",
				brandId, term);
	}

	/** 전체 교체(PUT 계약) — 삭제 후 재삽입을 한 트랜잭션으로. */
	@Transactional
	public void replaceExclusionTerms(long brandId, List<String> terms) {
		db.update("DELETE FROM brand_hashtag_exclusion WHERE brand_id = ?", brandId);
		for (String term : terms) {
			db.update("INSERT INTO brand_hashtag_exclusion (brand_id, term) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, term);
		}
	}

	/** 페이지 단위 기존 코드 조회 — 조기 종료·스킵 판정 재료. 빈 입력은 선처리(IN ()은 SQL 오류). */
	public Set<String> existingCodes(long brandId, List<String> codes) {
		if (codes.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
		Object[] args = new Object[codes.size() + 1];
		args[0] = brandId;
		for (int i = 0; i < codes.size(); i++) {
			args[i + 1] = codes.get(i);
		}
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_hashtag_post WHERE brand_id = ? AND short_code IN ("
						+ placeholders + ")",
				String.class, args));
	}

	public void insertPost(long brandId, String matchedTag, String shortCode, String authorUsername,
			String authorFullName, String authorProfilePicUrl, OffsetDateTime takenAt, String caption,
			String contentType, String thumbnailUrl, Long likes, Long comments,
			String verdict, String verdictSource) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username,
				    author_full_name, author_profile_pic_url, taken_at, caption, content_type,
				    thumbnail_url, likes, comments, verdict, verdict_source)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (brand_id, short_code) DO NOTHING""",
				brandId, shortCode, matchedTag, authorUsername, authorFullName, authorProfilePicUrl,
				takenAt, caption != null ? caption : "", contentType, thumbnailUrl, likes, comments,
				verdict, verdictSource);
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagRepositoryTest"`
Expected: PASS (4 tests) — Task 1 마이그레이션 적용 검증 겸함

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandHashtagRepository.java \
        monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagRepositoryTest.java
git commit -m "feat(monitoring): 해시태그 감지 리포지토리 — 태그·제외 문자열·판정 게시물"
```

---

### Task 5: Gemini 판정기 `BrandMentionJudge`

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/llm/GeminiHttp.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/llm/GeminiHttpTransport.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/llm/BrandMentionJudge.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/llm/BrandMentionJudgeTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

전송은 `HikerHttp`와 같은 함수형 seam으로 분리해 테스트에서 람다로 대체한다:

```java
package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BrandMentionJudgeTest {

	private static String geminiBody(String verdict) {
		return """
				{"candidates":[{"content":{"parts":[{"text":"{\\"verdict\\":\\"%s\\"}"}]}}]}"""
				.formatted(verdict);
	}

	@Test
	void 관련_판정을_파싱한다() {
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> geminiBody("RELEVANT"), "key", "model-x");
		assertThat(judge.judge("cclime_official", List.of("끌리메", "cclime"), "poster1", "끌리메 후기"))
				.isEqualTo(BrandMentionJudge.Verdict.RELEVANT);
	}

	@Test
	void 무관과_불확실도_파싱한다() {
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("IRRELEVANT"), "key", "m")
				.judge("u", List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.IRRELEVANT);
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("UNCERTAIN"), "key", "m")
				.judge("u", List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}

	@Test
	void 요청_바디에_브랜드_컨텍스트와_캡션이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("RELEVANT");
		}, "key", "model-x");
		judge.judge("cclime_official", List.of("끌리메", "cclime"), "poster1", "끌리메 다녀왔어요");
		assertThat(sent.get()).contains("model-x:generateContent");
		assertThat(sent.get()).contains("cclime_official").contains("끌리메 다녀왔어요");
	}

	@Test
	void 예상_밖_판정_문자열은_예외다() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> geminiBody("MAYBE"), "key", "m");
		assertThatThrownBy(() -> judge.judge("u", List.of("t"), "p", "c"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void api_키가_비어있으면_불확실로_접는다() {
		// fail-closed: 키 미설정 환경(로컬 등)에서 스윕이 죽지 않고, 판정 불가분은 비노출(UNCERTAIN)
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> {
			throw new AssertionError("키 없이는 호출하면 안 된다");
		}, "", "m");
		assertThat(judge.judge("u", List.of("t"), "p", "c"))
				.isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.llm.BrandMentionJudgeTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`GeminiHttp.java`:

```java
package com.celfit.monitoring.llm;

/** Gemini 전송 seam — HikerHttp와 같은 패턴. 테스트는 람다, 운영은 GeminiHttpTransport. */
@FunctionalInterface
public interface GeminiHttp {
	String post(String path, String jsonBody);
}
```

`GeminiHttpTransport.java` — analytics `GeminiHttpApi.send` 관용구(순수 JDK HttpClient, `x-goog-api-key`, 429/5xx 지수 백오프)를 축소 이식:

```java
package com.celfit.monitoring.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Gemini generateContent 전송 — analytics GeminiHttpApi 관용구 축소판(배치·페이싱 없음). */
public class GeminiHttpTransport implements GeminiHttp {

	private static final int MAX_ATTEMPTS = 3;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String baseUrl;
	private final String apiKey;

	public GeminiHttpTransport(String baseUrl, String apiKey) {
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
	}

	@Override
	public String post(String path, String jsonBody) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(30))
						.header("Content-Type", "application/json")
						.header("x-goog-api-key", apiKey)
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() == 200) {
					return res.body();
				}
				last = new IllegalStateException("Gemini HTTP " + res.statusCode());
				if (res.statusCode() != 429 && res.statusCode() < 500) {
					throw last;   // 4xx(429 제외)는 재시도 무의미
				}
			} catch (java.io.IOException e) {
				last = new IllegalStateException("Gemini 전송 실패: " + e.getMessage(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Gemini 전송 중단", e);
			}
			try {
				Thread.sleep(Duration.ofSeconds(1L << attempt).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Gemini 재시도 대기 중단", e);
			}
		}
		throw last;
	}
}
```

`BrandMentionJudge.java`:

```java
package com.celfit.monitoring.llm;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 해시태그 발견 게시물의 브랜드 관련성 판정(스펙 2026-08-11 §4-6) — 핵심 역할은 이름 충돌 방어
 * (동명 타업종, 예: 도자기 "끌리메"). 캡션 기준 RELEVANT/UNCERTAIN/IRRELEVANT.
 * api-key 미설정이면 호출 없이 UNCERTAIN(비노출)로 접는다 — fail-closed.
 */
public class BrandMentionJudge {

	public enum Verdict { RELEVANT, UNCERTAIN, IRRELEVANT }

	private static final Logger log = LoggerFactory.getLogger(BrandMentionJudge.class);
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{"verdict":{"type":"string",
			 "enum":["RELEVANT","UNCERTAIN","IRRELEVANT"]}},"required":["verdict"]}""";

	private final GeminiHttp http;
	private final String apiKey;
	private final String model;

	public BrandMentionJudge(GeminiHttp http, String apiKey, String model) {
		this.http = http;
		this.apiKey = apiKey;
		this.model = model;
	}

	public Verdict judge(String brandUsername, List<String> brandTags, String authorUsername, String caption) {
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("Gemini api-key 미설정 — 해시태그 판정을 UNCERTAIN(비노출)으로 접는다");
			return Verdict.UNCERTAIN;
		}
		String system = """
				인스타그램 게시물이 특정 브랜드를 실제로 언급하는지 판정한다. 같은 이름의 다른 업종
				브랜드·제품(이름 충돌), 우연히 태그만 단 무관 게시물은 IRRELEVANT. 캡션이 없거나
				정보가 부족해 판단이 어려우면 UNCERTAIN. 브랜드 방문 후기·제품 리뷰·협찬 콘텐츠·
				브랜드 소개 등 실제 언급이면 RELEVANT.""";
		String user = """
				브랜드 인스타그램 계정: @%s
				브랜드 해시태그: %s
				게시자: @%s
				캡션:
				%s""".formatted(brandUsername, String.join(", ", brandTags), authorUsername,
				caption == null || caption.isBlank() ? "(캡션 없음)" : caption);
		String body = MAPPER.createObjectNode()
				.<tools.jackson.databind.node.ObjectNode>set("system_instruction",
						MAPPER.createObjectNode().set("parts",
								MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("text", system))))
				.<tools.jackson.databind.node.ObjectNode>set("contents",
						MAPPER.createArrayNode().add(MAPPER.createObjectNode().set("parts",
								MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("text", user)))))
				.<tools.jackson.databind.node.ObjectNode>set("generationConfig",
						MAPPER.createObjectNode()
								.put("responseMimeType", "application/json")
								.set("responseSchema", MAPPER.readTree(RESPONSE_SCHEMA)))
				.toString();
		String response = http.post("/v1beta/models/" + model + ":generateContent", body);
		JsonNode text = MAPPER.readTree(response)
				.path("candidates").path(0).path("content").path(0).path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			// 셰이프 폴백: content가 배열이 아닌 객체인 응답
			text = MAPPER.readTree(response)
					.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		}
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음");
		}
		String verdict = MAPPER.readTree(text.asString()).path("verdict").asString(null);
		try {
			return Verdict.valueOf(verdict);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IllegalStateException("예상 밖 판정: " + verdict);
		}
	}
}
```

주의: Jackson 3(`tools.jackson.*`)이다 — `com.fasterxml` 임포트 금지. analytics `GeminiHttpApi.generateJson`의 candidates 파싱 경로(`content.parts[0].text`)를 확인해 본문 파싱을 그 경로에 맞추고 위의 이중 폴백은 제거해도 된다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.llm.BrandMentionJudgeTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/llm/ \
        monitoring/src/test/java/com/celfit/monitoring/llm/
git commit -m "feat(monitoring): Gemini 브랜드 관련성 판정기 — 이름 충돌 방어, 키 미설정은 fail-closed"
```

---

### Task 6: `BrandHashtagCollectService` — 스윕·필터·판정 파이프라인

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java`
- Modify: `monitoring/src/main/resources/application.yml` (`monitoring.brand` 블록)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandCollectServiceTest` 관용구: fake `HikerHttp` 람다 + 인메모리 스텁 리포지토리 서브클래스 + 스텁 판정기. `BrandRow` 생성은 기존 테스트의 픽스처 방식을 그대로 복제한다(파일에서 확인).

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.llm.BrandMentionJudge;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrandHashtagCollectServiceTest {

	private static final long RECENT = Instant.now().minusSeconds(3600).getEpochSecond();
	private static final long ANCIENT = Instant.now().minusSeconds(200L * 24 * 3600).getEpochSecond();

	private final List<String> calls = new ArrayList<>();
	private final List<String> tagPages = new ArrayList<>();
	private int pageCall = 0;

	/** 인메모리 스텁 — 실 리포지토리의 시그니처만 상속(super는 null JdbcTemplate). */
	static class StubRepo extends com.celfit.monitoring.store.BrandHashtagRepository {
		List<String> tags = new ArrayList<>();
		List<String> terms = new ArrayList<>();
		Map<String, String> verdictByCode = new HashMap<>();   // code → verdict
		StubRepo() { super(null); }
		@Override public List<String> findTags(long brandId) { return tags; }
		@Override public List<String> findExclusionTerms(long brandId) { return terms; }
		@Override public Set<String> existingCodes(long brandId, List<String> codes) {
			Set<String> out = new HashSet<>(verdictByCode.keySet());
			out.retainAll(new HashSet<>(codes));
			return out;
		}
		@Override public void insertPost(long brandId, String matchedTag, String shortCode,
				String authorUsername, String authorFullName, String authorProfilePicUrl,
				OffsetDateTime takenAt, String caption, String contentType, String thumbnailUrl,
				Long likes, Long comments, String verdict, String verdictSource) {
			verdictByCode.putIfAbsent(shortCode, verdict + "/" + verdictSource);
		}
	}

	/** 판정 스텁 — LLM 콜 없이 고정 판정. judged에 호출 캡션 기록. */
	static class StubJudge extends BrandMentionJudge {
		Verdict fixed = Verdict.RELEVANT;
		boolean fail = false;
		List<String> judged = new ArrayList<>();
		StubJudge() { super((p, b) -> null, "key", "m"); }
		@Override public Verdict judge(String brandUsername, List<String> brandTags,
				String authorUsername, String caption) {
			if (fail) throw new IllegalStateException("LLM 실패");
			judged.add(caption);
			return fixed;
		}
	}

	private final StubRepo repo = new StubRepo();
	private final StubJudge judge = new StubJudge();

	private HikerClient hiker() {
		return new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/hashtag/medias/recent")) {
				return tagPages.get(Math.min(pageCall++, tagPages.size() - 1));
			}
			throw new IllegalStateException("예상 밖 콜: " + path);
		});
	}

	private BrandHashtagCollectService service(int maxPages) {
		return new BrandHashtagCollectService(hiker(), repo, judge, 90, maxPages);
	}

	// BrandRow 픽스처 — BrandCollectServiceTest의 brand 픽스처 생성 방식을 그대로 복제할 것
	private final BrandRow brand = /* BrandCollectServiceTest와 동일하게 생성: id=1L, username="cclime_official", igUserId="99" */;

	private static String page(String nextPageId, String... medias) {
		String cursor = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"sections":[{"layout_content":{"medias":[%s]}}],
				 "next_page_id":%s,"more_available":%s}}"""
				.formatted(String.join(",", medias), cursor, nextPageId != null);
	}

	private static String media(String code, long takenAt, String poster, String caption, String taggedUser) {
		String usertags = taggedUser == null ? "{\"in\":[]}"
				: "{\"in\":[{\"user\":{\"username\":\"" + taggedUser + "\"}}]}";
		return """
				{"media":{"code":"%s","taken_at":%d,"media_type":1,
				 "caption":{"text":"%s"},"user":{"username":"%s","pk":"111"},
				 "like_count":10,"comment_count":2,"usertags":%s}}"""
				.formatted(code, takenAt, caption, poster, usertags);
	}

	@Test
	void 자사_게시자는_SELF로_저장하고_판정기를_부르지_않는다() {
		repo.tags.add("끌리메");
		repo.terms.add("cclime");
		tagPages.add(page(null, media("AAA", RECENT, "cclime_daegu", "지점 홍보", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).containsEntry("AAA", "SELF/RULE");
		assertThat(judge.judged).isEmpty();
	}

	@Test
	void 등록_계정_유저태그는_DIRECT_TAGGED로_저장한다() {
		repo.tags.add("끌리메");
		tagPages.add(page(null, media("AAA", RECENT, "customer1", "후기", "cclime_official")));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).containsEntry("AAA", "DIRECT_TAGGED/RULE");
		assertThat(judge.judged).isEmpty();
	}

	@Test
	void 캡션_멘션만_있으면_판정_없이_RELEVANT_확정이다() {
		repo.tags.add("끌리메");
		tagPages.add(page(null, media("AAA", RECENT, "customer1", "@cclime_official 다녀왔어요", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).containsEntry("AAA", "RELEVANT/MENTION");
		assertThat(judge.judged).isEmpty();
	}

	@Test
	void 멘션은_전체_단어_일치만_인정한다() {
		repo.tags.add("끌리메");
		// @cclime_officialkr은 다른 계정 — 접두 일치로 오인하면 안 된다
		tagPages.add(page(null, media("AAA", RECENT, "customer1", "@cclime_officialkr 좋아요", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode.get("AAA")).startsWith("RELEVANT/LLM");
	}

	@Test
	void 나머지는_LLM_판정을_저장한다() {
		repo.tags.add("끌리메");
		judge.fixed = BrandMentionJudge.Verdict.IRRELEVANT;
		tagPages.add(page(null, media("AAA", RECENT, "hanbok_shop", "한복 원단 #끌리메", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).containsEntry("AAA", "IRRELEVANT/LLM");
	}

	@Test
	void 윈도우_밖_게시물은_저장하지_않는다() {
		repo.tags.add("끌리메");
		tagPages.add(page(null, media("OLD", ANCIENT, "customer1", "옛 후기", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).isEmpty();
	}

	@Test
	void 페이지에_기존_게시물이_있으면_다음_페이지로_가지_않는다() {
		repo.tags.add("끌리메");
		repo.verdictByCode.put("KNOWN", "RELEVANT/LLM");
		tagPages.add(page("p2", media("NEW1", RECENT, "customer1", "신규", null),
				media("KNOWN", RECENT, "customer2", "기존", null)));
		tagPages.add(page(null, media("NEVER", RECENT, "customer3", "도달 불가", null)));
		service(4).sweep(brand);
		assertThat(calls).hasSize(1);                        // 페이지네이션 중단
		assertThat(repo.verdictByCode).containsKey("NEW1");  // 페이지 내 신규는 처리
		assertThat(repo.verdictByCode).doesNotContainKey("NEVER");
	}

	@Test
	void 기존_게시물이_없으면_상한까지_전진한다() {
		repo.tags.add("끌리메");
		tagPages.add(page("p2", media("A", RECENT, "c1", "1", null)));
		tagPages.add(page("p3", media("B", RECENT, "c2", "2", null)));
		tagPages.add(page("p4", media("C", RECENT, "c3", "3", null)));
		tagPages.add(page("p5", media("D", RECENT, "c4", "4", null)));
		service(2).sweep(brand);
		assertThat(calls).hasSize(2);   // 상한 2페이지
	}

	@Test
	void LLM_실패_게시물은_미저장으로_남겨_다음_스윕이_재시도한다() {
		repo.tags.add("끌리메");
		judge.fail = true;
		tagPages.add(page(null, media("AAA", RECENT, "customer1", "애매한 캡션", null)));
		service(4).sweep(brand);
		assertThat(repo.verdictByCode).isEmpty();
	}

	@Test
	void 태그가_없으면_콜_자체가_없다() {
		service(4).sweep(brand);
		assertThat(calls).isEmpty();
	}

	@Test
	void 태그마다_별도로_열거한다() {
		repo.tags.addAll(List.of("끌리메", "cclime"));
		tagPages.add(page(null, media("AAA", RECENT, "c1", "1", null)));
		service(4).sweep(brand);
		assertThat(calls).hasSize(2);
		assertThat(calls.get(0)).contains("name=%EB%81%8C%EB%A6%AC%EB%A9%94");
		assertThat(calls.get(1)).contains("name=cclime");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.llm.BrandMentionJudge;
import com.celfit.monitoring.store.BrandHashtagRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 해시태그 감지 스윕(스펙 2026-08-11 §3·§4) — 브랜드별 태그마다 recent 열거(조기 종료, 상한
 * maxPages), 필터 순서 고정: 윈도우 → 자사 제외 → 중복 → 직접태그 → 멘션 확정 → LLM 판정.
 * 판정 게시물은 전량 저장(조기 종료·dedup 재료), 윈도우 밖만 미저장. 보강 없음(보류).
 */
public class BrandHashtagCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagCollectService.class);

	private final HikerClient hiker;
	private final BrandHashtagRepository repo;
	private final BrandMentionJudge judge;
	private final int windowDays;
	private final int maxPages;

	public BrandHashtagCollectService(HikerClient hiker, BrandHashtagRepository repo,
			BrandMentionJudge judge, int windowDays, int maxPages) {
		this.hiker = hiker;
		this.repo = repo;
		this.judge = judge;
		this.windowDays = windowDays;
		this.maxPages = maxPages;
	}

	public void sweep(BrandRow brand) {
		List<String> tags = repo.findTags(brand.id());
		if (tags.isEmpty()) {
			return;
		}
		List<String> terms = repo.findExclusionTerms(brand.id());
		Instant cutoff = Instant.now().minus(Duration.ofDays(windowDays));
		int stored = 0;
		for (String tag : tags) {
			stored += sweepTag(brand, tag, tags, terms, cutoff);
		}
		log.info("브랜드 해시태그 스윕 완료 — {} 태그 {}종, 신규 저장 {}건", brand.username(), tags.size(), stored);
	}

	private int sweepTag(BrandRow brand, String tag, List<String> allTags, List<String> terms,
			Instant cutoff) {
		int stored = 0;
		String cursor = null;
		for (int page = 0; page < maxPages; page++) {
			HikerClient.HashtagPage p = hiker.fetchHashtagRecentPage(tag, cursor);
			if (p.posts().isEmpty()) {
				break;
			}
			Set<String> known = repo.existingCodes(brand.id(),
					p.posts().stream().map(hp -> hp.post().shortCode()).filter(c -> c != null).toList());
			for (HikerClient.HashtagPost hp : p.posts()) {
				if (hp.post().shortCode() == null || known.contains(hp.post().shortCode())) {
					continue;
				}
				if (processNew(brand, tag, allTags, hp, terms, cutoff)) {
					stored++;
				}
			}
			// 조기 종료(사용자 확정 08-11): 페이지에 기존 게시물이 하나라도 있으면 페이지네이션 중단.
			// 페이지 내 신규는 위에서 이미 전부 처리했다 — 콜은 페이지 단위라 중간 중단은 무의미.
			if (!known.isEmpty() || p.nextPageId() == null) {
				break;
			}
			cursor = p.nextPageId();
		}
		return stored;
	}

	/** 필터·판정 후 저장. 저장했으면 true, 윈도우 밖·LLM 실패 스킵이면 false. */
	private boolean processNew(BrandRow brand, String tag, List<String> allTags,
			HikerClient.HashtagPost hp, List<String> terms, Instant cutoff) {
		var post = hp.post();
		if (post.takenAt() == null || Instant.ofEpochSecond(post.takenAt()).isBefore(cutoff)) {
			return false;   // 윈도우 밖 — 영원히 대상 아님, 미저장(페이지 컷은 taken_at 비단조라 안 씀)
		}
		String author = post.username() != null ? post.username() : "";
		String verdict;
		String source;
		if (matchesExclusion(author, terms)) {
			verdict = "SELF";
			source = "RULE";
		} else if (hp.taggedUsernames().contains(brand.username().toLowerCase(Locale.ROOT))) {
			verdict = "DIRECT_TAGGED";   // 기존 유저태그 스윕의 영역 — 여기선 기록만, 비노출
			source = "RULE";
		} else if (captionMentions(post.caption(), brand.username())) {
			verdict = "RELEVANT";        // 멘션-only — 유저태그 스윕이 못 잡는 케이스, 판정 스킵
			source = "MENTION";
		} else {
			BrandMentionJudge.Verdict v;
			try {
				v = judge.judge(brand.username(), allTags, author, post.caption());
			} catch (RuntimeException e) {
				log.warn("해시태그 판정 실패(게시물 스킵 — 다음 스윕 재시도) {} {}: {}",
						brand.username(), post.shortCode(), e.toString());
				return false;
			}
			verdict = v.name();
			source = "LLM";
		}
		repo.insertPost(brand.id(), tag, post.shortCode(), author, post.ownerFullName(),
				post.ownerProfilePicUrl(),
				Instant.ofEpochSecond(post.takenAt()).atOffset(ZoneOffset.UTC),
				post.caption(), post.contentType(), post.thumbnailUrl(),
				post.likes(), post.comments(), verdict, source);
		return true;
	}

	private static boolean matchesExclusion(String author, List<String> terms) {
		String a = author.toLowerCase(Locale.ROOT);
		return terms.stream().anyMatch(t -> !t.isBlank() && a.contains(t.toLowerCase(Locale.ROOT)));
	}

	/** 캡션 @멘션 — 전체 단어 일치만(@cclime_officialkr을 @cclime_official로 오인 금지). */
	static boolean captionMentions(String caption, String brandUsername) {
		if (caption == null || caption.isBlank()) {
			return false;
		}
		return Pattern.compile("@" + Pattern.quote(brandUsername) + "(?![\\w.])",
				Pattern.CASE_INSENSITIVE).matcher(caption).find();
	}
}
```

`BrandHashtagConfig.java` — 빈 배선(`@Service` 대신 명시 구성으로 `@Value` 주입을 모은다):

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.llm.BrandMentionJudge;
import com.celfit.monitoring.llm.GeminiHttpTransport;
import com.celfit.monitoring.service.BrandHashtagCollectService;
import com.celfit.monitoring.store.BrandHashtagRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BrandHashtagConfig {

	@Bean
	public BrandMentionJudge brandMentionJudge(
			@Value("${monitoring.gemini.api-key:}") String apiKey,
			@Value("${monitoring.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
			@Value("${monitoring.brand.hashtag.judge-model:gemini-2.5-flash}") String model) {
		return new BrandMentionJudge(new GeminiHttpTransport(baseUrl, apiKey), apiKey, model);
	}

	@Bean
	public BrandHashtagCollectService brandHashtagCollectService(HikerClient hiker,
			BrandHashtagRepository repo, BrandMentionJudge judge,
			@Value("${monitoring.brand.window-days:90}") int windowDays,
			@Value("${monitoring.brand.hashtag.max-pages:4}") int maxPages) {
		return new BrandHashtagCollectService(hiker, repo, judge, windowDays, maxPages);
	}
}
```

주의: 판정 모델 기본값(`gemini-2.5-flash`)은 실행 시점에 analytics `application.yml`이 실제로 쓰는 모델 id를 확인해 동일 계열로 맞출 것(`grep -rn "gemini" analytics/src/main/resources/application.yml`) — 존재하지 않는 모델 id는 운영에서 첫 판정부터 실패한다.

`application.yml` — `monitoring.brand` 블록에 추가:

```yaml
  brand:
    schedule:
      sweep-cron: "-"
    window-days: 90
    window-posts: 105
    comment-pages: 3
    author-stale-days: 30
    enrich-concurrency: 6
    hashtag:
      max-pages: 4            # 스윕 페이지 상한 — 첫 백필은 상한까지(소급 2~3개월), 평시 조기 종료로 1페이지 수렴
      judge-model: gemini-2.5-flash
  gemini:
    api-key: ${GEMINI_API_KEY:}
    base-url: https://generativelanguage.googleapis.com
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java \
        monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java \
        monitoring/src/main/resources/application.yml \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java
git commit -m "feat(monitoring): 해시태그 스윕 파이프라인 — 조기 종료·자사/직접태그/멘션 필터·LLM 판정"
```

---

### Task 7: 등록 편승 — 태그 시드 + 백필 훅

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (register L70–84, runBackfillSafely L91–113)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java` (register L32–38 + 요청 record)
- Test: 기존 `BrandRegistrationService` 테스트 + `BrandControllerTest` 확장

- [ ] **Step 1: 실패하는 테스트 추가**

기존 `BrandRegistrationService` 테스트 클래스(있으면 그 파일에, 없으면 `BrandControllerTest`에)에 추가. 스텁은 Task 6의 `StubRepo`·`StubJudge` 재사용:

```java
	@Test
	void 등록은_태그_셋과_기본_제외_문자열을_시드한다() {
		service.register("cclime_official", "끌리메");
		assertThat(hashtagRepo.insertedTags).containsExactly("끌리메", "cclime", "cclime_official");
		assertThat(hashtagRepo.defaultExclusions).containsExactly("cclime");
	}

	@Test
	void 활성_브랜드_재등록도_태그를_유니온한다() {
		// 대행사가 먼저 등록(브랜드명 없음) → 이후 brand 유저 연결 시 브랜드명 태그가 추가되는 경로
		service.register("cclime_official", null);
		service.register("cclime_official", "끌리메");
		assertThat(hashtagRepo.insertedTags).contains("끌리메");
	}

	@Test
	void 백필은_유저태그_보강_후_해시태그_스윕을_돌린다() {
		service.register("cclime_official", "끌리메");
		// 백필·보강 executor를 동기 실행 스텁(Runnable::run)으로 주입했다는 전제 —
		// 기존 테스트의 executor 주입 방식을 그대로 따른다
		assertThat(hashtagSweptBrands).containsExactly("cclime_official");
	}

	@Test
	void 해시태그_백필_실패는_등록·보강을_깨지_않는다() {
		hashtagCollectFails = true;
		service.register("cclime_official", "끌리메");
		// 예외 전파 없음 + backfill_error 미기록(코어는 성공)
		assertThat(brands.backfillErrors).isEmpty();
	}
```

컨트롤러 테스트(`BrandControllerTest` 기존 관용구 — standalone MockMvc):

```java
	@Test
	void 등록_요청은_brandName을_서비스로_전달한다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", 1234L, false);
		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\",\"brandName\":\"끌리메\"}"))
				.andExpect(status().isCreated());
		assertThat(service.lastBrandName).isEqualTo("끌리메");
	}

	@Test
	void brandName_없는_기존_요청도_그대로_동작한다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", 1234L, false);
		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\"}"))
				.andExpect(status().isCreated());
		assertThat(service.lastBrandName).isNull();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "*BrandRegistration*" --tests "*BrandControllerTest*"`
Expected: 컴파일 실패 (`register(String, String)` 미존재)

- [ ] **Step 3: 구현**

`BrandRegistrationService` — 시그니처 확장(기존 단일 인자 호출부가 있으면 `register(username, null)` 위임 오버로드 유지) + 시드 + 백필 훅:

```java
	public Result register(String username, String brandName) {
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			// replay에도 태그 유니온 — 뒤늦게 연결한 brand 유저의 브랜드명 태그를 흡수(스펙 §2)
			seedHashtags(existing.get().id(), normalized, brandName);
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		seedHashtags(id, normalized, brandName);
		backfill.execute(() -> runBackfillSafely(row));
		return new Result(id, normalized, profile.followers(), false);
	}

	private void seedHashtags(long brandId, String username, String brandName) {
		hashtags.insertTags(brandId, BrandHashtagTags.derive(brandName, username));
		hashtags.insertDefaultExclusion(brandId, BrandHashtagTags.root(username));
	}

	private void runBackfillSafely(BrandRow row) {
		try {
			List<PostInfo> posts = collect.sweepCore(row);
			brands.touchSwept(row.id(), LocalDate.now(KST));
			enrich.execute(() -> {
				runEnrichSafely(row, posts);
				runHashtagBackfillSafely(row);   // 보강 뒤 — ready(~30초)에 영향 없음
			});
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
			brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
		}
	}

	private void runHashtagBackfillSafely(BrandRow row) {
		try {
			hashtagCollect.sweep(row);
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		}
	}
```

생성자에 `BrandHashtagRepository hashtags`, `BrandHashtagCollectService hashtagCollect` 주입 추가.

`BrandController` — 요청 record에 `brandName` 추가(하위 호환 — 필드 부재 시 null):

```java
	public record BrandRegisterRequest(String username, String brandName) {}

	@PostMapping
	public ResponseEntity<BrandRegisterResponse> register(@RequestBody BrandRegisterRequest req) {
		BrandRegistrationService.Result result = service.register(req.username(), req.brandName());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new BrandRegisterResponse(result.brandId(), result.username(),
						result.followers(), "ACTIVE"));
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "*BrandRegistration*" --tests "*BrandControllerTest*"`
Expected: PASS (기존 + 신규 전부)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java \
        monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java \
        monitoring/src/test/java/com/celfit/monitoring/
git commit -m "feat(monitoring): 등록 시 해시태그 시드 + 백필 큐 편승 — replay에도 태그 유니온"
```

---

### Task 8: 일일 스윕 합류 — `BrandSweepJob`

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java` (runSweep)
- Test: 기존 `BrandSweepJob` 테스트 확장(없으면 `BrandCollectServiceTest` 옆에 신설)

- [ ] **Step 1: 실패하는 테스트 추가**

```java
	@Test
	void 일일_스윕은_브랜드마다_해시태그_스윕을_이어_돌린다() {
		// 스텁: collect.sweep 성공 후 hashtagCollect.sweep 호출 순서 검증
		job.run();
		assertThat(hashtagSweptBrands).containsExactly("brand1", "brand2");
	}

	@Test
	void 해시태그_스윕_실패는_브랜드_스윕_성공을_깨지_않는다() {
		hashtagCollectFails = true;
		job.run();
		assertThat(touchSweptBrands).containsExactly("brand1", "brand2");   // touchSwept 유지
	}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`runSweep` 루프 확장 — 유저태그 스윕과 별도 격리(해시태그 실패가 `touchSwept`·failures 카운트에 영향 없음):

```java
	private void runSweep() {
		LocalDate today = LocalDate.now(KST);
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				collect.sweep(b);
				brands.touchSwept(b.id(), today);
			} catch (RuntimeException e) {
				failures++;
				log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
			try {
				hashtagCollect.sweep(b);
			} catch (RuntimeException e) {
				log.warn("브랜드 해시태그 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}
```

생성자에 `BrandHashtagCollectService hashtagCollect` 주입 추가.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "*BrandSweepJob*"`
Expected: PASS

- [ ] **Step 5: monitoring 모듈 전체 테스트**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :monitoring:test`
Expected: BUILD SUCCESSFUL — 기존 테스트 무손상 확인

- [ ] **Step 6: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java \
        monitoring/src/test/java/com/celfit/monitoring/
git commit -m "feat(monitoring): 일일 브랜드 스윕에 해시태그 단계 합류 — 실패 상호 격리"
```

---

### Task 9: monitoring 제외 문자열 API

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java`
- Test: `BrandControllerTest` 확장

- [ ] **Step 1: 실패하는 테스트 추가**

```java
	@Test
	void 제외_문자열_조회는_현재_목록을_돌려준다() throws Exception {
		hashtagRepo.terms = List.of("cclime");
		mvc.perform(get("/api/brands/cclime_official/hashtag-exclusions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.terms[0]").value("cclime"));
	}

	@Test
	void 제외_문자열_교체는_정규화_후_전체_교체한다() throws Exception {
		mvc.perform(put("/api/brands/cclime_official/hashtag-exclusions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[\" CClime \",\"cclime\",\"\"]}"))
				.andExpect(status().isNoContent());
		assertThat(hashtagRepo.replacedTerms).containsExactly("cclime");   // trim·소문자·중복·공백 제거
	}

	@Test
	void 미등록_브랜드의_제외_문자열은_404다() throws Exception {
		mvc.perform(get("/api/brands/unknown/hashtag-exclusions"))
				.andExpect(status().isNotFound());
	}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`BrandController`에 추가(브랜드 해석은 기존 `DELETE /api/brands/{username}`의 not-found 관용구를 따른다):

```java
	public record HashtagExclusionsBody(List<String> terms) {}

	@GetMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<HashtagExclusionsBody> exclusions(@PathVariable String username) {
		var brand = brands.findByUsername(username.strip());
		if (brand.isEmpty() || brand.get().status() != BrandStatus.ACTIVE) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(new HashtagExclusionsBody(hashtags.findExclusionTerms(brand.get().id())));
	}

	@PutMapping("/{username}/hashtag-exclusions")
	public ResponseEntity<Void> replaceExclusions(@PathVariable String username,
			@RequestBody HashtagExclusionsBody body) {
		var brand = brands.findByUsername(username.strip());
		if (brand.isEmpty() || brand.get().status() != BrandStatus.ACTIVE) {
			return ResponseEntity.notFound().build();
		}
		List<String> normalized = (body.terms() == null ? List.<String>of() : body.terms()).stream()
				.filter(t -> t != null && !t.isBlank())
				.map(t -> t.strip().toLowerCase(java.util.Locale.ROOT))
				.distinct()
				.toList();
		hashtags.replaceExclusionTerms(brand.get().id(), normalized);
		return ResponseEntity.noContent().build();
	}
```

컨트롤러 생성자에 `BrandRepository brands`(이미 있으면 재사용)·`BrandHashtagRepository hashtags` 주입.

- [ ] **Step 4: 통과 확인 → Step 5: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java \
        monitoring/src/test/java/com/celfit/monitoring/web/
git commit -m "feat(monitoring): 브랜드 제외 문자열 조회·전체 교체 API"
```

---

### Task 10: was — 등록 시 brandName 전달

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java` (registerBrand + BrandRegisterRequest record)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (register)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` 확장

- [ ] **Step 1: 실패하는 테스트 추가**

기존 관용구(@WebMvcTest + @MockitoBean). `UserRepository`를 @MockitoBean으로 추가하고 프로필 스텁:

```java
	@Test
	void brand_유형_유저의_등록은_company_name을_brandName으로_전달한다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("brand", "끌리메")));
		given(commandClient.registerBrand("lizda_official", "끌리메"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", "끌리메");
	}

	@Test
	void 비brand_유형은_brandName_없이_전달한다() throws Exception {
		// 대행사 등: company_name이 대행사명이라 브랜드명 태그로 부적합(스펙 §2)
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("agency", "대행사명")));
		given(commandClient.registerBrand("lizda_official", null))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null);
	}
```

`profileOf(userType, companyName)` 헬퍼는 `UserProfile` record의 실제 필드 순서에 맞춰 작성(불명 필드는 null/기본값).

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`MonitoringCommandClient`:

```java
	public BrandRegisterResult registerBrand(String username, String brandName) {
		return exchange(() -> restClient.post().uri("/api/brands")
				.body(new BrandRegisterRequest(username, brandName)).retrieve().body(BrandRegisterResult.class));
	}

	record BrandRegisterRequest(String username, String brandName) {
	}
```

`V1BrandAccountService.register` — `UserRepository` 주입 추가, 호출부 수정:

```java
	public BrandAccountResponse register(long userId, String rawUsername) {
		String username = BrandUsername.normalize(rawUsername);
		BrandUsername.validate(username);
		Optional<Long> alreadyLinked = linkTransaction.precheck(userId, username);
		if (alreadyLinked.isPresent()) {
			return assembler.toResponse(findAccountOrThrow(alreadyLinked.get()));
		}
		String brandName = brandNameOf(userId);
		BrandRegisterResult registered = translate(() -> commandClient.registerBrand(username, brandName));
		// ... 이하 기존과 동일
	}

	/** 스펙 2026-08-11 §2 — company_name은 brand 유형일 때만 브랜드명(타 유형은 대행사명 등). */
	private String brandNameOf(long userId) {
		return userRepository.findProfileById(userId)
				.filter(p -> "brand".equals(p.userType()))
				.map(UserProfile::companyName)
				.filter(name -> name != null && !name.isBlank())
				.orElse(null);
	}
```

`UserProfile`의 접근자 이름(`userType()`/`companyName()`)은 record 정의를 확인해 맞출 것.

- [ ] **Step 4: 통과 확인 → Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/
git commit -m "feat(was): 브랜드 등록에 company_name 전달 — brand 유형만, 해시태그 브랜드명 태그 재료"
```

---

### Task 11: was — 피드 병합 (`source: "hashtag"`)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java` (source @Schema에 "hashtag" 추가)
- Modify: 게시물 목록 서비스(`V1BrandPostsController`가 위임하는 서비스 — 파일에서 확인)
- Test: 게시물 목록 기존 테스트 클래스 확장

- [ ] **Step 1: `BrandReadRepository.findHashtagPosts` — 실패하는 테스트부터**

목록 테스트에 hashtag 소스 케이스 추가(기존 @WebMvcTest 관용구, `BrandReadRepository`는 @MockitoBean):

```java
	@Test
	void 해시태그_발견_게시물이_source_hashtag로_병합된다() throws Exception {
		given(brandReadRepository.findHashtagPosts(eq(100L), any(), anyInt()))
				.willReturn(List.of(new BrandReadRepository.BrandHashtagPostRow(
						"HHH", "끌리메", "customer1", "고객1", "https://pic",
						OffsetDateTime.parse("2026-08-01T00:00:00Z"), "끌리메 후기 #끌리메",
						"REELS", "https://thumb", 10L, 2L,
						OffsetDateTime.parse("2026-08-02T00:00:00Z"))));
		// ... 기존 direct·tagged 스텁 그대로 ...
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.shortcode=='HHH')].source").value("hashtag"))
				.andExpect(jsonPath("$.data[?(@.shortcode=='HHH')].id").value("bh_HHH"));
	}

	@Test
	void 같은_shortcode의_기존_소스가_해시태그보다_우선한다() throws Exception {
		// tagged와 hashtag 양쪽에 있는 게시물은 tagged 본체 유지(스펙 §4-4 — 배지는 출처)
		// tagged 스텁과 hashtag 스텁에 같은 shortcode 배치 후 source가 "tagged"인지 확인
	}

	@Test
	void source_필터_hashtag는_해시태그_발견만_남긴다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=hashtag")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].source").value(everyItem(is("hashtag"))));
	}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`BrandReadRepository`:

```java
	/** 해시태그 발견 게시물 — RELEVANT(관련 판정)만, 최신순 상한 limit건(스펙 2026-08-11 §5). */
	public List<BrandHashtagPostRow> findHashtagPosts(long brandId, OffsetDateTime cutoff, int limit) {
		return jdbc.sql("""
				SELECT short_code, matched_tag, author_username, author_full_name,
				       author_profile_pic_url, taken_at, caption, content_type, thumbnail_url,
				       likes, comments, first_seen_at
				FROM brand_hashtag_post
				WHERE brand_id = :brandId AND verdict = 'RELEVANT' AND taken_at >= :cutoff
				ORDER BY taken_at DESC
				LIMIT :limit
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.param("limit", limit)
				.query(BrandHashtagPostRow.class)
				.list();
	}

	public record BrandHashtagPostRow(String shortCode, String matchedTag, String authorUsername,
			String authorFullName, String authorProfilePicUrl, OffsetDateTime takenAt, String caption,
			String contentType, String thumbnailUrl, Long likes, Long comments,
			OffsetDateTime firstSeenAt) {
	}
```

`BrandPostAssembler` — 해시태그 응답 조립 + 병합. **tagged-only 조립 메서드(`bt_` 합성 id를 만드는 그 메서드)를 열어 필드 기본값을 그대로 미러**하되 아래만 다르게:

| 필드 | 값 |
|---|---|
| id | `"bh_" + shortCode` (tagged-only `bt_` 관용구 동형) |
| source | `"hashtag"` |
| contentType | 열거 관측 소문자 변환(tagged와 같은 어휘 규칙 — "reels"/"feed") |
| sponsorship / isPaidPartnership | `BrandSponsorshipClassifier.classify(null, caption)` / null (열거에 paid 관측 없음) |
| latestSnapshot / snapshots | null / 빈 리스트 (보강 보류 — 스냅샷 없음) |
| commentsTotal | 열거 관측 `comments` |
| commentsCollectedCount / recentComments | 0 / 빈 리스트 |
| author* | row의 author 필드(팔로워는 null — 프로필 보강 없음) |

병합 정적 메서드(기존 `mergeByShortcode` 아래에 추가 — 동일 정렬 comparator 재사용):

```java
	/** 해시태그 발견분 합류 — 기존(direct·tagged) 우선, 신규 shortcode만 추가 후 재정렬(스펙 §4-4). */
	static List<BrandPostResponse> mergeHashtag(List<BrandPostResponse> existing,
			List<BrandPostResponse> hashtag) {
		Map<String, BrandPostResponse> byCode = new LinkedHashMap<>();
		existing.forEach(p -> byCode.put(p.shortcode(), p));
		hashtag.forEach(p -> byCode.putIfAbsent(p.shortcode(), p));
		return byCode.values().stream()
				.sorted(Comparator.comparing(BrandPostAssembler::uploadedOn,
								Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(BrandPostResponse::shortcode))
				.toList();
	}
```

목록 서비스: 기존 direct·tagged 조회·병합 뒤에 `findHashtagPosts(brandId, cutoff, limit)` 결과를 조립해 `mergeHashtag`로 합류(cutoff·limit은 tagged 조회가 쓰는 값과 동일하게). `source` 쿼리 파라미터 필터에 `"hashtag"` 어휘 추가(필터링 지점은 서비스의 기존 source 필터 로직 — 응답 `source` 필드 기준이면 자동 포함되는지 확인만). `BrandPostResponse.source`의 `@Schema(allowableValues = {"tagged", "direct", "hashtag"})` 갱신.

- [ ] **Step 4: 통과 확인**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "*BrandPost*" --tests "*brandmonitoring*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/ \
        was/src/test/java/com/celfit/was/
git commit -m "feat(was): 브랜드 피드에 해시태그 발견 게시물 병합 — source hashtag, 기존 소스 우선"
```

---

### Task 12: was — 제외 문자열 프록시

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java` + `V1BrandAccountService.java`
- Test: `V1BrandAccountsControllerTest` 확장

- [ ] **Step 1: 실패하는 테스트 추가**

```java
	@Test
	void 제외_문자열_조회는_소유_브랜드만_허용한다() throws Exception {
		given(linkRepository.isActivelyLinked(7L, 100L)).willReturn(true);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(commandClient.getHashtagExclusions("lizda_official")).willReturn(List.of("lizda"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.terms[0]").value("lizda"));
	}

	@Test
	void 미소유_브랜드의_제외_문자열은_403이다() throws Exception {
		given(linkRepository.isActivelyLinked(7L, 100L)).willReturn(false);
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())))
				.andExpect(status().isForbidden());
	}

	@Test
	void 제외_문자열_교체는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.isActivelyLinked(7L, 100L)).willReturn(true);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(put("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[\"lizda\",\"lizda_sub\"]}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().putHashtagExclusions("lizda_official", List.of("lizda", "lizda_sub"));
	}
```

`isActivelyLinked`가 `BrandLinkRepository`에 없으면 기존 소유 검증 메서드(목록 엔드포인트가 쓰는 것 — 파일에서 확인)를 같은 이름으로 재사용하고 테스트를 그 시그니처에 맞춘다. 403 관용구도 기존 소유권 위반 응답과 동일하게.

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`MonitoringCommandClient`:

```java
	public record HashtagExclusionsBody(List<String> terms) {
	}

	public List<String> getHashtagExclusions(String username) {
		HashtagExclusionsBody body = exchange(() -> restClient.get()
				.uri("/api/brands/{username}/hashtag-exclusions", username)
				.retrieve().body(HashtagExclusionsBody.class));
		return body == null || body.terms() == null ? List.of() : body.terms();
	}

	public void putHashtagExclusions(String username, List<String> terms) {
		exchange(() -> restClient.put().uri("/api/brands/{username}/hashtag-exclusions", username)
				.body(new HashtagExclusionsBody(terms)).retrieve().toBodilessEntity());
	}
```

컨트롤러·서비스: 기존 계정 상세 엔드포인트의 소유 검증 관용구(accountId → 링크 확인 → 403)를 그대로 따라 GET/PUT 2개 추가. username은 `findAccountOrThrow(brandId).username()`에서 얻는다.

- [ ] **Step 4: 통과 확인 → Step 5: was 모듈 전체 테스트 → Step 6: Commit**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test`
Expected: BUILD SUCCESSFUL

```bash
git add was/src/main/java/com/celfit/was/ was/src/test/java/com/celfit/was/
git commit -m "feat(was): 해시태그 제외 문자열 조회·교체 프록시 — 소유 브랜드만"
```

---

### Task 13: 배포 env·계약 문서·마무리

**Files:**
- Modify: `deploy/compose.yaml` (monitoring 서비스 environment)
- Modify: `docs/contracts/monitoring-was-contract.md`
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md`

- [ ] **Step 1: compose에 GEMINI_API_KEY 전달**

`deploy/compose.yaml`의 monitoring 서비스 `environment` 블록에 추가(기존 `HIKER_API_KEY` 줄 옆):

```yaml
      GEMINI_API_KEY: ${GEMINI_API_KEY}
```

`.env.example`에는 `GEMINI_API_KEY`가 이미 있다(analytics용) — 신규 키 등록 불필요. 운영 `~/deploy/.env`에도 이미 존재.

- [ ] **Step 2: 계약 문서 갱신**

`docs/contracts/monitoring-was-contract.md`에 버전 행 추가(기존 v2.x 서식 준수):
- 게시물 목록 `source`에 `"hashtag"` 추가 — 스냅샷·댓글·팔로워 없음(보강 보류), id 합성 `bh_`+shortcode, sponsorship은 캡션 키워드만.
- 신규 `GET/PUT /v1/brand-monitoring/accounts/{accountId}/hashtag-exclusions` — terms 전체 교체, 소유 브랜드만.
- FE 공유 필요 항목으로 "해시태그 발견 배지·제외 문자열 관리 UI" 명시.

- [ ] **Step 3: 트랙 문서 갱신**

`docs/tracks/MON-BT-브랜드-태그-모니터링.md`의 해시태그 항목을 "구현 완료(PR #, 배포 대기)"로 갱신.

- [ ] **Step 4: 전체 테스트 (PR 직전 1회)**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit + PR**

```bash
git add deploy/compose.yaml docs/contracts/monitoring-was-contract.md docs/tracks/
git commit -m "docs: 해시태그 감지 계약 v-bump·배포 env — monitoring GEMINI_API_KEY"
```

PR 본문에 포함할 운영 체크리스트:
- [ ] 배포 후 첫 등록 1건으로 백필 확인(`brand_hashtag_post` 적재, verdict 분포)
- [ ] 다음 새벽 스윕에서 조기 종료 로그 확인("신규 저장 0건" + 태그당 1콜 수렴) — 스펙 §9의 일 단위 미실증 항목
- [ ] Gemini 판정 실패율 로그 확인(모델 id 유효성)
- [ ] 기존 브랜드(구 등록분)는 태그가 없어 해시태그 스윕이 조용히 스킵됨 — 소급 시드가 필요하면 별도 백필 스크립트(replay POST /api/brands with brandName)로

---

## Self-Review 결과

- **스펙 커버리지**: §2 태그 셋(T2·T7·T10) · §3 조기 종료·상한·백필(T3·T6·T7) · §4 필터 순서 전부(T6) + 제외 문자열 관리(T4·T9·T12) · §5 저장·RELEVANT만 노출·보강 없음(T1·T6·T11) · §6 시작 시점(T7) · §8 계약 문서화(T13). 갭 없음.
- **의도적 미구현**: 기존 등록 브랜드 소급 시드는 T13 운영 체크리스트로 위임(수동 replay — 현 등록 규모가 작아 스크립트 불요할 수 있음).
- **타입 일관성**: `BrandHashtagRepository.insertPost` 14인자 시그니처가 T4·T6에서 동일, `HashtagPage`/`HashtagPost`가 T3·T6에서 동일, `registerBrand(String, String)`이 T10 테스트·구현에서 동일함을 확인.
- **알려진 불확실 지점(실행 중 확인 필요로 명시함)**: ① `toPost` 인자 순서(T3) ② Gemini 응답 candidates 경로·모델 id(T5·T6) ③ `BrandRow`·`UserProfile` record 필드(T6·T10) ④ was 목록 서비스의 소유 검증·source 필터 메서드명(T11·T12) — 각 태스크에 확인 지시 포함.
