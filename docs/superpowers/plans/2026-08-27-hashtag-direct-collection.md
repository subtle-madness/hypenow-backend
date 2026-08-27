# 해시태그 직접 수집 전환 Implementation Plan

> 상태: 🟢 활성 · 2026-08-27 작성, 실행 전

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 해시태그로 발견한 게시물을 별도 "감지" 테이블 대신 tagged/direct와 같은 수집 풀(`brand_tagged_post`)에 직접 편입해, 스냅샷·댓글·게시자 보강과 주기 재수집을 그대로 받고 `/posts` 통합 목록에 `source=hashtag`로 합류시킨다.

**Architecture:** `brand_tagged_post`에 세 번째 nullable 타임스탬프 `hashtag_detected_at`을 더해(2026-08-18 `direct_registered_at` 패턴 동형) 한 행이 tagged·direct·hashtag 성분과 겹침을 동시에 표현하고, 매칭 태그는 신설 `brand_post_matched_tag`(M:N)가 기억한다. monitoring 일일 스윕의 ③단계는 LLM 관련성 판정을 버리고 "열거 → 기간·본인 컷 → 편입(브랜드당 1000) → `BrandCollectService.enrich` 재사용"으로 바뀌며, tagged 열거가 도달할 수 없는 행(direct·hashtag 성분)의 주기 재수집은 기존 direct 2단계를 일반화해 공용으로 쓴다. was는 `resolveSource`를 direct > tagged > hashtag로 3원화하고, hashtag-only 행만 "조회자 장부 태그 ∩ 게시물 매칭 태그 ≠ ∅"로 격리한다(fail-open 폐기).

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(monitoring, was) · JdbcTemplate(monitoring) / JdbcClient(was) · Flyway(monitoring `db/migration`, was `db/migration/app`) · JUnit 5 + Mockito + AssertJ · Testcontainers 2.x(PostgreSQL)

---

## 전제·주의 (실행 전에 반드시 읽을 것)

- **선행 조건:** `docs/superpowers/plans/archive/2026-08-27-hashtag-tag-ledger-fix.md`(계획 1 — 구현 완료·archive)가 **먼저 머지·배포**돼 있어야 한다. 이 계획의 격리 필터는 사용자 태그 장부가 비어 있지 않다는 것을 전제하며, fail-open을 폐기한다.
- **정본 스펙:** `docs/superpowers/specs/2026-08-27-hashtag-direct-collection-design.md`. 설계 결정은 재논의하지 않는다.
- **테스트 실행 전 매 셸에서 아래를 export한다.** 미설정 시 Testcontainers가 colima 소켓을 못 찾아 통합 테스트가 무더기로 실패한다(테스트 결함으로 오진하기 쉬운 실패 양상).
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
- **테스트는 모듈 단위로만** — `./gradlew :monitoring:test`, `./gradlew :was:test`. 전체 `./gradlew test`는 PR 직전에만.
- **DROP은 이 계획의 범위 밖이다(expand-contract).** `brand_hashtag_post`·`brand_hashtag_post_matched_tags`는 이번 릴리스에서 **읽기·쓰기만 중단**한다. 그 테이블을 향하는 리포지토리 메서드(`BrandHashtagRepository.insertPost`/`existingCodes`/`recordTagMatch(s)`, `BrandReadRepository.findHashtagPosts`, 이미지 아카이브 잡 2종)와 그 테스트는 **호출부가 사라져도 그대로 둔다** — 다음 릴리스에서 테이블 DROP과 함께 제거한다. 새 DROP·RENAME·타입 변경 태스크를 추가하지 말 것.
- **신규 Flyway 마이그레이션은 실행 시점에 UTC로 채번**한다(`date -u +%Y%m%d%H%M%S`). KST 채번 금지. monitoring과 was(app)는 독립 버전 공간이다.
- 커밋 메시지는 한국어, prefix는 `feat(monitoring):`/`feat(was):`/`fix(...)`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `monitoring/src/main/resources/db/migration/V<UTC>__brand_tagged_post_hashtag_source.sql` | (신규) `hashtag_detected_at` 컬럼 + `brand_post_matched_tag` 테이블·인덱스. |
| `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` | (수정) hashtag upsert·코드 집합·매칭 태그 기록 추가 + 열거 커버 가드 3종에 hashtag 제외 + `directDuePosts` → `unenumeratedDuePosts`. |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` | (수정) `collectionCutoff`를 package-private static으로 승격(해시태그 수집이 같은 컷을 쓴다). |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java` | (전면 재작성) 감지 → 수집. LLM 판정 제거, `brand_tagged_post` 편입 + `enrich` 재사용. |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java` | (수정) `sweepDirect` → `sweepUnenumerated`(direct ∪ hashtag), 미보강 우선 + 스윕당 상한. |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java` | (수정) 2단계 호출명·로그 문구. |
| `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java` | (수정) judge 빈 제거, 새 수집 서비스 배선. |
| `monitoring/src/main/java/com/celfit/monitoring/llm/BrandMentionJudge.java` | (삭제) 관련성 판정 파이프라인 폐기. |
| `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_post_migration.sql` | (신규) 구 감지 데이터 이관(SELF 제외·브랜드당 최신 1000). |
| `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` | (수정) `hashtagDetectedAt` 컬럼·행, `findMatchedTags` 산지 교체. |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` | (수정) source 3원화 + 격리 필터 3분기(fail-open 폐기). |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` | (수정) `source=hashtag` 필터·`counts.hashtag`, 구 엔드포인트 리라우팅 배선. |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java` | (전면 재작성) 통합 풀의 `source=hashtag` 부분집합을 구 셰이프로 매핑. |

---

## Task 1: 스키마 — hashtag 성분 컬럼과 매칭 태그 테이블

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_tagged_post_hashtag_source.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java` (신규)

> **채번:** `date -u +%Y%m%d%H%M%S`로 뽑아 `V<그 값>__brand_tagged_post_hashtag_source.sql`로 만든다.

- [ ] **Step 1: 실패 테스트 작성**

`monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java`:

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 성분 저장(2026-08-27 해시태그 직접 수집 설계 §1·§2) — BrandHashtagRepositoryTest와 같은
 * Testcontainers 관용구. 겹침 병기(tagged/direct 행에 hashtag_detected_at만 얹기)·매칭 태그 누적·
 * 열거 커버 가드(hashtag-only 행 제외)를 실 컨테이너 왕복으로 고정한다.
 */
class TaggedPostHashtagSourceTest {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	JdbcTemplate db;
	TaggedPostRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new TaggedPostRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	/** PostInfo 22필드 픽스처 — 이 테스트가 쓰는 값만 채우고 나머지는 null/기본이다. */
	private static PostInfo post(String code, String author, Instant takenAt) {
		return new PostInfo(code, author, null, null, "9001", "REELS", "캡션", null,
				takenAt.getEpochSecond(), 10L, 2L, 500L, null, null, null, null, null, null, null,
				true, false, false);
	}

	@Test
	void hashtag_편입은_hashtag_detected_at만_채운다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NULL AND direct_registered_at IS NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				Boolean.class, brandId)).isTrue();
	}

	/** 겹침 병기 — 이미 tagged로 있던 행에는 hashtag_detected_at만 얹고 tag_detected_at은 보존한다. */
	@Test
	void 기존_tagged_행에는_hashtag_성분만_병기된다() {
		repo.insert(brandId, post("BOTH", "poster1", NOW.minusSeconds(86400)));

		repo.upsertHashtag(brandId, post("BOTH", "poster1", NOW.minusSeconds(86400)), NOW);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NOT NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'BOTH'",
				Boolean.class, brandId)).isTrue();
		assertThat(db.queryForObject("SELECT count(*) FROM brand_tagged_post WHERE brand_id = ?",
				Integer.class, brandId)).isEqualTo(1);
	}

	/** 최초 병기 시각은 재수집으로 밀리지 않는다(COALESCE) — direct_registered_at과 같은 규칙. */
	@Test
	void 재편입은_최초_hashtag_시각을_밀지_않는다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW.plusSeconds(86400));

		assertThat(db.queryForObject(
				"SELECT hashtag_detected_at FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				java.sql.Timestamp.class, brandId).toInstant()).isEqualTo(NOW);
	}

	@Test
	void hashtagCodes는_hashtag_성분이_있는_코드만_돌려준다() {
		repo.insert(brandId, post("TAGONLY", "poster1", NOW.minusSeconds(86400)));
		repo.upsertHashtag(brandId, post("HHH", "poster2", NOW.minusSeconds(86400)), NOW);

		assertThat(repo.hashtagCodes(brandId)).containsExactly("HHH");
	}

	/** 같은 게시물이 다른 태그로 재발견되면 매칭 태그가 누적된다(멱등 upsert). */
	@Test
	void 매칭_태그는_누적되고_재기록은_멱등이다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);

		repo.recordMatchedTag(brandId, "HHH", "끌리메");
		repo.recordMatchedTag(brandId, "HHH", "끌리메");
		repo.recordMatchedTags(brandId, List.of("HHH"), "cclime");

		assertThat(Set.copyOf(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'HHH'",
				String.class, brandId))).containsExactlyInAnyOrder("끌리메", "cclime");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest"
```
Expected: 컴파일 실패 — `cannot find symbol: method upsertHashtag(...)`

- [ ] **Step 3: 마이그레이션 파일 생성**

```bash
echo "monitoring/src/main/resources/db/migration/V$(date -u +%Y%m%d%H%M%S)__brand_tagged_post_hashtag_source.sql"
```

그 경로에 아래 내용을 그대로 쓴다.

```sql
-- 해시태그 직접 수집 전환(2026-08-27 설계 §1) — expand 단계, nullable ADD + 신규 테이블만.
--
-- 접근 A: 해시태그 발견 게시물을 별도 테이블이 아니라 brand_tagged_post 풀에 흡수한다.
-- tag_detected_at / direct_registered_at / hashtag_detected_at 세 개의 nullable 타임스탬프 조합이
-- 한 행으로 세 성분과 그 겹침을 표현한다(V20260818040742 direct 합류와 동형).
--
-- DEFAULT를 두지 않는다 — direct 합류 때는 구버전 파드의 insert가 tag_detected_at을 모르는 문제가
-- 있어 DEFAULT now()가 필요했지만, 여기서는 반대다: 롤링 창의 구버전 파드가 만드는 행은 tagged나
-- direct 산지이고 hashtag 성분이 없는 것이 정답이라 NULL이 그대로 옳다. 기존 행 백필도 없다.
ALTER TABLE brand_tagged_post ADD COLUMN hashtag_detected_at timestamptz;

-- 편입 상한(브랜드당 1000) 카운트와 2단계 재수집 모수 조회용 부분 인덱스.
CREATE INDEX brand_tagged_post_hashtag_idx
    ON brand_tagged_post (brand_id) WHERE hashtag_detected_at IS NOT NULL;

-- "이 게시물이 어떤 해시태그로 잡혔나" — was 사용자 격리 필터(내 장부 태그 ∩ 매칭 태그)의 재료.
-- 스윕이 같은 게시물을 다른 태그로 재발견하면 행이 누적된다(멱등 upsert).
-- 구 brand_hashtag_post_matched_tags와 같은 모양이지만 FK 대상이 통합 풀로 바뀌었다 — 구 테이블은
-- 이번 릴리스에서 읽기·쓰기만 중단하고 DROP은 다음 릴리스다(expand-contract).
CREATE TABLE brand_post_matched_tag (
    brand_id   bigint      NOT NULL,
    short_code text        NOT NULL,
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, tag),
    FOREIGN KEY (brand_id, short_code) REFERENCES brand_tagged_post (brand_id, short_code) ON DELETE CASCADE
);

-- was 격리 필터는 (brand_id, short_code IN (...))로 읽지만, 태그 축 조회(운영 점검·태그별 편입량)도
-- 흔해 구 matched_tags와 같은 보조 인덱스를 둔다.
CREATE INDEX brand_post_matched_tag_tag_idx ON brand_post_matched_tag (brand_id, tag);
```

- [ ] **Step 4: 리포지토리 메서드 추가**

`monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`의 `upsertDirect` 바로 뒤(= `clearDirect` 앞)에 아래를 삽입한다.

old_string:
```java
	/** 취소(겹침 행) — direct 표식만 해제, tagged 행은 그대로 남는다(설계 §2-4). 행이 없어도 무해. */
```
new_string:
```java
	/**
	 * 해시태그 편입 upsert(2026-08-27 해시태그 직접 수집 설계 §2-3) — 해시태그 recent 열거로 얻은
	 * 게시물을 hashtag 표식과 함께 통합 풀에 링크한다. {@link #upsertDirect}와 같은 규칙이다:
	 * tag_detected_at은 명시적 NULL로 둬 DEFAULT now()를 무력화하고(이 행이 태그 열거 산지가
	 * 아니라는 표시 — 나중에 태그 열거가 만나면 {@link #insert}가 COALESCE로 채운다), 이미 있던
	 * 행(tagged·direct)에는 hashtag_detected_at만 얹는다. COALESCE라 재발견·재수집으로 최초 편입
	 * 시각이 밀리지 않는다.
	 */
	public void upsertHashtag(long brandId, PostInfo post, Instant detectedAt) {
		db.update("""
				INSERT INTO brand_tagged_post
				    (brand_id, short_code, author_username, author_ig_user_id, taken_at,
				     tag_detected_at, hashtag_detected_at)
				VALUES (?, ?, ?, ?, ?, NULL, ?)
				ON CONFLICT (brand_id, short_code) DO UPDATE SET
				    hashtag_detected_at = COALESCE(brand_tagged_post.hashtag_detected_at, EXCLUDED.hashtag_detected_at),
				    author_ig_user_id   = COALESCE(brand_tagged_post.author_ig_user_id, EXCLUDED.author_ig_user_id)""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())), Timestamp.from(detectedAt));
	}

	/**
	 * 이 브랜드에서 hashtag 성분이 이미 있는 코드 전체 — 해시태그 스윕의 dedup·조기 종료 판정과
	 * 편입 상한 잔량 계산(크기)의 공용 입력이다(구 {@code BrandHashtagRepository.existingCodes}의
	 * 통합 풀판). 스윕 1회당 1번만 읽고 페이지마다 메모리로 교차한다 — 페이지당 IN 쿼리보다 싸다.
	 *
	 * <p>기준이 "브랜드 풀에 있는 코드"가 아니라 "hashtag 성분이 있는 코드"인 것이 핵심이다:
	 * 전자로 하면 tagged 열거가 이미 확보한 게시물이 전부 조기 종료 신호가 돼, 해시태그 스트림
	 * 깊은 곳의 hashtag-only 게시물에 영영 도달하지 못한다.
	 */
	public Set<String> hashtagCodes(long brandId) {
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL",
				String.class, brandId));
	}

	/**
	 * 매칭 태그 기록(2026-08-27 설계 §1) — "이 (brand, shortcode)가 이 태그의 열거 스트림에도
	 * 나타났다". FK가 brand_tagged_post를 향하므로 호출부는 편입 직후(또는 이미 있는 행)에만
	 * 부른다. 멱등(ON CONFLICT DO NOTHING).
	 */
	public void recordMatchedTag(long brandId, String shortCode, String tag) {
		db.update("""
				INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
				VALUES (?, ?, ?)
				ON CONFLICT DO NOTHING""",
				brandId, shortCode, tag);
	}

	/** {@link #recordMatchedTag} 배치판 — 페이지 내 "이미 hashtag 성분이 있는" 코드 묶음 전용. */
	public void recordMatchedTags(long brandId, Collection<String> shortCodes, String tag) {
		for (String shortCode : shortCodes) {
			recordMatchedTag(brandId, shortCode, tag);
		}
	}

	/** 취소(겹침 행) — direct 표식만 해제, tagged 행은 그대로 남는다(설계 §2-4). 행이 없어도 무해. */
```

- [ ] **Step 5: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest"
```
Expected: PASS (5 tests)

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/ \
        monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java \
        monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java
git commit -m "feat(monitoring): brand_tagged_post에 hashtag 성분·매칭 태그 테이블 추가"
```

---

## Task 2: 열거 커버 가드에 hashtag 성분 반영

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (`trackedPosts`, `directDuePosts`→`unenumeratedDuePosts`, `touchCrawledDepth`, `tagVerifyCandidates`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java`

> **왜:** hashtag-only 행은 tagged 열거(`/v2/user/tag/medias`)에 절대 실리지 않는다. direct-only 행에 이미 적용된 것과 **같은 이유**로, 이 행들이 ①열거 깊이 판정(`trackedPosts`)에 들어가면 도달 불가 깊이까지 매일 열거를 벌리는 요청량 누수가 영구화되고 ②커버 간주 touch(`touchCrawledDepth`)를 받으면 2단계 단건 수집의 due가 실크롤 없이 꺼져 조용히 얼어붙으며 ③부재 검증(`tagVerifyCandidates`) 후보가 되면 2단계가 이미 잡는 404를 중복 과금한다.

- [ ] **Step 1: 실패 테스트 추가**

`TaggedPostHashtagSourceTest.java`의 마지막 `}` 앞에 아래를 삽입한다.

old_string:
```java
		assertThat(Set.copyOf(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'HHH'",
				String.class, brandId))).containsExactlyInAnyOrder("끌리메", "cclime");
	}
}
```
new_string:
```java
		assertThat(Set.copyOf(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'HHH'",
				String.class, brandId))).containsExactlyInAnyOrder("끌리메", "cclime");
	}

	// ── 열거 커버 가드(설계 §2-5) ────────────────────────────────────────────

	/** hashtag 성분 행은 tagged 열거가 도달할 수 없다 — 열거 깊이 판정 모수에서 빠져야 한다. */
	@Test
	void trackedPosts는_hashtag_성분_행을_제외한다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.upsertHashtag(brandId, post("HASHONLY", "poster2", takenAt), NOW);
		repo.insert(brandId, post("BOTH", "poster3", takenAt));
		repo.upsertHashtag(brandId, post("BOTH", "poster3", takenAt), NOW);

		assertThat(repo.trackedPosts(brandId, takenAt.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("TAGONLY");
	}

	/** 커버 간주 touch도 마찬가지 — 여기 걸리면 2단계 단건 수집의 due가 실크롤 없이 꺼진다. */
	@Test
	void touchCrawledDepth는_hashtag_성분_행을_건드리지_않는다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.upsertHashtag(brandId, post("HASHONLY", "poster2", takenAt), NOW);

		repo.touchCrawledDepth(brandId, takenAt.minusSeconds(1), NOW);

		assertThat(db.queryForObject(
				"SELECT last_crawled_at IS NULL FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HASHONLY'",
				Boolean.class, brandId)).isTrue();
	}

	/** 부재 검증은 tagged-only 전용 — hashtag 성분 행의 404는 2단계 단건 수집이 이미 잡는다. */
	@Test
	void tagVerifyCandidates는_hashtag_성분_행을_제외한다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.insert(brandId, post("BOTH", "poster3", takenAt));
		repo.upsertHashtag(brandId, post("BOTH", "poster3", takenAt), NOW);

		assertThat(repo.tagVerifyCandidates(brandId, takenAt.minusSeconds(1), NOW))
				.containsExactly("TAGONLY");
	}

	/** 2단계 모수는 direct ∪ hashtag — tagged-only만 빠진다. 미보강 행이 먼저 온다(이관분 우선 충전). */
	@Test
	void unenumeratedDuePosts는_direct와_hashtag를_미보강_우선으로_돌려준다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.upsertDirect(brandId, post("DIRECT", "poster2", takenAt), NOW);
		repo.upsertHashtag(brandId, post("HASHTAG", "poster3", takenAt), NOW);
		repo.markEnriched(brandId, List.of("DIRECT"), NOW);   // 보강 완료 — 뒤로 밀린다

		assertThat(repo.unenumeratedDuePosts(brandId, takenAt.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("HASHTAG", "DIRECT");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest"
```
Expected: 컴파일 실패 — `cannot find symbol: method unenumeratedDuePosts(...)`

- [ ] **Step 3: `trackedPosts` 가드 추가**

old_string:
```java
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL""",
```
new_string:
```java
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL""",
```

- [ ] **Step 4: `directDuePosts`를 `unenumeratedDuePosts`로 일반화**

old_string:
```java
	public List<TrackedPost> directDuePosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND direct_registered_at IS NOT NULL AND taken_at >= ?""",
```
new_string:
```java
	public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ?
				  AND (direct_registered_at IS NOT NULL OR hashtag_detected_at IS NOT NULL)
				  AND taken_at >= ?
				ORDER BY (enriched_at IS NULL) DESC, taken_at DESC""",
```

같은 메서드의 javadoc도 새 의미로 바꾼다.

old_string:
```java
	/**
	 * direct 2단계 스윕의 모수 — <b>브랜드 창 안의 direct 등록 행 전부</b>다(2026-08-19 수집 상한 v2
	 * §7-3). 겹침 행(태그·direct 둘 다)도 포함한다: direct 게시물은 2,000 상한 밖이라, 1단계 열거가
	 * 상한에 걸려 도달하지 못한 겹침 행은 여기서 단건 콜로 살려야 사용자가 직접 등록한 게시물이
	 * 동결되지 않는다. 구 필터({@code tag_detected_at IS NULL})는 그 구제 경로를 막았다.
```
new_string:
```java
	/**
	 * 2단계 스윕의 모수 — <b>브랜드 창 안에서 tagged 열거가 커버하지 못하는 행 전부</b>다
	 * (2026-08-19 수집 상한 v2 §7-3 + <b>2026-08-27 해시태그 직접 수집 설계 §2-5 일반화</b>):
	 * direct 등록 행과 hashtag 편입 행. 겹침 행(태그와 함께 있는 행)도 포함한다: 이 둘은 태그
	 * 열거의 2,000 상한 밖이라, 1단계가 상한에 걸려 도달하지 못한 겹침 행은 여기서 단건 콜로 살려야
	 * 동결되지 않는다. 구 필터({@code tag_detected_at IS NULL})는 그 구제 경로를 막았다.
	 *
	 * <p><b>정렬은 미보강(enriched_at IS NULL) 우선</b>(설계 §5) — 구 감지 데이터 이관분은 게시자·
	 * 댓글·스냅샷이 통째로 비어 있고 was 표시 게이트가 정산분만 서빙하므로, 나이 기반 due 순서에
	 * 맡기면 오래된 이관분의 첫 보강이 한없이 밀린다. 호출부가 스윕당 건수 상한으로 자르므로
	 * 이 정렬이 곧 "누구부터 충전하나"의 정본이다.
```

- [ ] **Step 5: `touchCrawledDepth`·`tagVerifyCandidates` 가드 추가**

old_string:
```java
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL""",
```
new_string:
```java
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL""",
```

old_string:
```java
		return db.queryForList("""
				SELECT short_code FROM brand_tagged_post
				WHERE brand_id = ? AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND taken_at >= ? AND unavailable_at IS NULL
```
new_string:
```java
		return db.queryForList("""
				SELECT short_code FROM brand_tagged_post
				WHERE brand_id = ? AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL
				  AND taken_at >= ? AND unavailable_at IS NULL
```

- [ ] **Step 6: 호출부 이름 맞추기(컴파일 복구)**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java`:

old_string:
```java
		List<TaggedPostRepository.TrackedPost> due = taggedPosts
				.directDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE)).stream()
```
new_string:
```java
		List<TaggedPostRepository.TrackedPost> due = taggedPosts
				.unenumeratedDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE)).stream()
```

`monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`:

old_string:
```java
		@Override
		public List<TrackedPost> directDuePosts(long brandId, Instant minTakenAt) {
			return due.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
		}
```
new_string:
```java
		@Override
		public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt) {
			return due.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
		}
```

- [ ] **Step 7: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest" \
                           --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"
```
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java \
        monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java \
        monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
git commit -m "feat(monitoring): 열거 커버 가드에 hashtag 성분 반영 - 2단계 모수를 direct∪hashtag로 일반화"
```

---

## Task 3: 해시태그 스윕을 감지에서 수집으로 전환

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java:359-361`(`collectionCutoff` 가시성)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java` (전면 재작성)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java` (전면 재작성)

- [ ] **Step 1: 실패 테스트 작성 — 파일 전체를 아래로 교체**

`monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java`:

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCommentRepository;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 해시태그 <b>수집</b>(2026-08-27 해시태그 직접 수집 설계 §2) — 구 "감지 + LLM 관련성 판정"
 * 파이프라인을 대체한 뒤의 계약을 고정한다. BrandCollectServiceTest 관용구(fake HikerHttp 람다 +
 * 인메모리 스텁 서브클래스, DB 없음)를 그대로 쓴다.
 *
 * <p>고정하는 것: 브랜드 본인 게시물 규칙 제외 · 브랜드 수집 창(collectionMonths) 사후 컷 ·
 * 통합 풀 편입과 보강 정산 · 겹침 병기(상한 밖) · 브랜드당 편입 상한 · 조기 종료(이전부터 있던
 * 코드에만 반응) · 매칭 태그 누적.
 */
class BrandHashtagCollectServiceTest {

	private static final long NOW = Instant.now().getEpochSecond();
	private static final long RECENT = NOW - 5L * 86400;            // 12개월 창 안
	private static final long OUT_OF_WINDOW = NOW - 400L * 86400;   // 12개월 창 밖

	private final StubTags tags = new StubTags();
	private final InMemoryTagged tagged = new InMemoryTagged();
	private final RecordingWriter writer = new RecordingWriter();
	private final StubSnapshots snapshots = new StubSnapshots();
	private final StubComments comments = new StubComments();
	private final InMemoryAuthors authors = new InMemoryAuthors();
	private final BrandCallContext callContext = new BrandCallContext();

	private final Map<String, List<String>> pagesByTag = new HashMap<>();
	private final Map<String, Integer> pageIndexByTag = new HashMap<>();
	private final List<String> calls = new ArrayList<>();

	private final BrandRow brand =
			new BrandRow(1L, "cclime_official", "111", BrandStatus.ACTIVE, LocalDate.now(), 12, true);

	// ── 스텁 대역 ───────────────────────────────────────────────────────────

	private static final class StubTags extends BrandHashtagRepository {
		List<String> tags = List.of();

		StubTags() {
			super(null);
		}

		@Override
		public List<String> findTags(long brandId) {
			return tags;
		}
	}

	/** 통합 풀 인메모리 대역 — brand_tagged_post의 세 성분 중 이 테스트가 보는 것만 흉내낸다. */
	private static final class InMemoryTagged extends TaggedPostRepository {
		final Set<String> known = new LinkedHashSet<>();          // 풀에 행이 있는 코드
		final Set<String> hashtag = new LinkedHashSet<>();        // hashtag 성분이 있는 코드
		final List<String> upsertedHashtag = new ArrayList<>();   // 이번 실행의 upsertHashtag 호출 순서
		final Map<String, LinkedHashSet<String>> matchedTags = new HashMap<>();
		final List<String> touched = new ArrayList<>();
		final List<String> enriched = new ArrayList<>();

		InMemoryTagged() {
			super(null);
		}

		@Override
		public Set<String> knownCodes(long brandId) {
			return new HashSet<>(known);
		}

		@Override
		public Set<String> hashtagCodes(long brandId) {
			return new HashSet<>(hashtag);
		}

		@Override
		public void upsertHashtag(long brandId, PostInfo post, Instant detectedAt) {
			upsertedHashtag.add(post.shortCode());
			known.add(post.shortCode());
			hashtag.add(post.shortCode());
		}

		@Override
		public void recordMatchedTag(long brandId, String shortCode, String tag) {
			matchedTags.computeIfAbsent(shortCode, k -> new LinkedHashSet<>()).add(tag);
		}

		@Override
		public void recordMatchedTags(long brandId, Collection<String> shortCodes, String tag) {
			for (String shortCode : shortCodes) {
				recordMatchedTag(brandId, shortCode, tag);
			}
		}

		@Override
		public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
			touched.addAll(codes);
		}

		@Override
		public void markEnriched(long brandId, Collection<String> codes, Instant at) {
			enriched.addAll(codes);
		}

		@Override
		public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
			// 워터마크를 높게 둬 댓글 콜 자체가 나가지 않게 한다 — 이 테스트의 관심사가 아니다.
			Map<String, Long> out = new HashMap<>();
			for (String c : codes) {
				out.put(c, 999L);
			}
			return out;
		}

		Set<String> matchedTagsOf(String shortCode) {
			return matchedTags.getOrDefault(shortCode, new LinkedHashSet<>());
		}
	}

	private static final class RecordingWriter extends BrandSnapshotWriter {
		final List<String> saved = new ArrayList<>();

		RecordingWriter() {
			super(null, null, null);
		}

		@Override
		public void savePost(LocalDate on, PostInfo post) {
			saved.add(post.shortCode());
		}
	}

	private static final class StubSnapshots extends BrandSnapshotRepository {
		StubSnapshots() {
			super(null);
		}

		@Override
		public Set<String> codesWithRepostsZeroCarry(Collection<String> codes, LocalDate today) {
			return Set.of();
		}

		@Override
		public Set<String> codesWithSharesZeroCarry(Collection<String> codes, LocalDate today) {
			return Set.of();
		}
	}

	private static final class StubComments extends BrandCommentRepository {
		StubComments() {
			super(null);
		}

		@Override
		public Set<String> findIds(String shortCode) {
			return Set.of();
		}
	}

	private static final class InMemoryAuthors extends AuthorProfileRepository {
		final List<String> upserted = java.util.Collections.synchronizedList(new ArrayList<>());

		InMemoryAuthors() {
			super(null);
		}

		@Override
		public void upsert(AuthorInfo a) {
			upserted.add(a.igUserId());
		}

		@Override
		public Set<String> freshIgUserIds(Collection<String> igUserIds, Instant staleBefore) {
			return Set.of();   // 전부 미보유 취급 — 게시자 콜이 항상 나가게
		}
	}

	/** 창 커버리지 무해 스텁 — 해시태그 경로는 tagged 열거를 타지 않아 여기 닿지 않는다. */
	private static final class InertBrands extends BrandRepository {
		InertBrands() {
			super(null);
		}

		@Override
		public BrandRepository.Coverage coverage(long brandId) {
			return new BrandRepository.Coverage(false, null);
		}

		@Override
		public void updateCoverage(long brandId, boolean capped, Instant coveredUntil) {
			// no-op
		}
	}

	// ── fake HikerHttp — 태그별 페이지 큐 + 게시자 프로필 ─────────────────────

	private HikerClient client() {
		return new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/by/id")) {
				String id = path.substring(path.indexOf("?id=") + "?id=".length());
				return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\",\"follower_count\":100,\"is_private\":false}}"
						.formatted(id, id);
			}
			if (!path.startsWith("/v2/hashtag/medias/recent")) {
				throw new IllegalStateException("예상 밖 콜: " + path);
			}
			String tag = URLDecoder.decode(tagParam(path), StandardCharsets.UTF_8);
			if (!pagesByTag.containsKey(tag)) {
				throw new IllegalStateException("등록 안 된 태그 콜: " + tag);
			}
			List<String> pages = pagesByTag.get(tag);
			int idx = pageIndexByTag.merge(tag, 1, Integer::sum) - 1;
			return pages.get(Math.min(idx, pages.size() - 1));
		});
	}

	private static String tagParam(String path) {
		String query = path.substring(path.indexOf('?') + 1);
		for (String kv : query.split("&")) {
			if (kv.startsWith("name=")) {
				return kv.substring("name=".length());
			}
		}
		throw new IllegalStateException("name 파라미터 없음: " + path);
	}

	private BrandHashtagCollectService service(int maxPages, int postLimit) {
		// adDisclosureEnabled=false — 광고 판정은 이 테스트의 관심사가 아니고, 꺼져 있으면
		// judgeAdDisclosuresSafely가 adJudge를 아예 부르지 않아 null을 넘겨도 안전하다.
		BrandCollectService collect = new BrandCollectService(client(), callContext, writer, snapshots, comments,
				tagged, authors, new InertBrands(), null, Runnable::run, 10000, 2000, 3, 30, false);
		return new BrandHashtagCollectService(client(), callContext, tags, tagged, writer, collect,
				maxPages, postLimit);
	}

	private long tagCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/hashtag/medias/recent")).count();
	}

	// ── JSON 픽스처 빌더(구 테스트 관용구 유지) ──────────────────────────────

	private static String sectionsBody(String nextPageId, String... medias) {
		String items = String.join(",", medias);
		String cursor = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"sections":[{"layout_content":{"medias":[%s]}}],
				 "more_available":%s},"next_page_id":%s}"""
				.formatted(items, nextPageId != null, cursor);
	}

	private static String media(String code, long takenAt, String username) {
		return """
				{"media":{"code":"%s","taken_at":%d,"media_type":1,
				 "caption":{"text":"캡션"},
				 "user":{"username":"%s","pk":"9001","full_name":"작가","profile_pic_url":"https://p"},
				 "like_count":10,"comment_count":2,"usertags":{"in":[]}}}"""
				.formatted(code, takenAt, username);
	}

	// ── 규칙 컷 ─────────────────────────────────────────────────────────────

	@Test
	void 브랜드_본인_게시물은_편입하지_않는다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("SELF1", RECENT, "CClime_Official"))));

		service(4, 1000).sweep(brand);

		assertThat(tagged.upsertedHashtag).isEmpty();
		assertThat(writer.saved).isEmpty();
	}

	@Test
	void 브랜드_수집_창_밖_게시물은_편입하지_않는다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("OLD1", OUT_OF_WINDOW, "poster1"))));

		service(4, 1000).sweep(brand);

		assertThat(tagged.upsertedHashtag).isEmpty();
	}

	// ── 편입·보강 ───────────────────────────────────────────────────────────

	@Test
	void 신규_게시물은_스냅샷_링크_매칭태그_보강까지_전부_채운다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("AAA", RECENT, "poster1"))));

		service(4, 1000).sweep(brand);

		assertThat(writer.saved).containsExactly("AAA");
		assertThat(tagged.upsertedHashtag).containsExactly("AAA");
		assertThat(tagged.touched).containsExactly("AAA");
		assertThat(tagged.enriched).containsExactly("AAA");     // 정산 마킹(was 노출 게이트)
		assertThat(authors.upserted).containsExactly("9001");   // 게시자 보강
		assertThat(tagged.matchedTagsOf("AAA")).containsExactly("cclime");
	}

	/** 태그 A가 저장한 게시물이 태그 B의 스트림에도 실리면 매칭 태그가 누적된다. */
	@Test
	void 같은_게시물이_다른_태그로_재발견되면_매칭_태그가_누적된다() {
		tags.tags = List.of("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("AAA", RECENT, "poster1"))));
		pagesByTag.put("끌리메", List.of(sectionsBody(null, media("AAA", RECENT, "poster1"))));

		service(4, 1000).sweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("AAA");   // 편입은 1회
		assertThat(tagged.matchedTagsOf("AAA")).containsExactlyInAnyOrder("cclime", "끌리메");
	}

	// ── 상한 ────────────────────────────────────────────────────────────────

	@Test
	void 편입_상한에_도달하면_신규_편입을_멈춘다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("N1", RECENT, "poster1"), media("N2", RECENT, "poster2"),
				media("N3", RECENT, "poster3"))));

		service(4, 2).sweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("N1", "N2");
	}

	/** 이미 풀에 있는 행(tagged·direct)에 hashtag 성분만 얹는 병기는 행이 늘지 않아 상한 밖이다. */
	@Test
	void 겹침_병기는_편입_상한을_소모하지_않는다() {
		tags.tags = List.of("cclime");
		tagged.known.add("OVERLAP");   // tagged로 이미 확보한 게시물(hashtag 성분은 없음)
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("OVERLAP", RECENT, "poster0"), media("N1", RECENT, "poster1"),
				media("N2", RECENT, "poster2"))));

		service(4, 1).sweep(brand);

		// 상한 1이라 신규는 N1 하나뿐이지만, 겹침 OVERLAP은 상한과 무관하게 병기된다.
		assertThat(tagged.upsertedHashtag).containsExactlyInAnyOrder("OVERLAP", "N1");
	}

	// ── 조기 종료 ───────────────────────────────────────────────────────────

	@Test
	void 이전부터_있던_코드를_만나면_그_태그_열거를_중단한다() {
		tags.tags = List.of("cclime");
		tagged.known.add("PRIOR");
		tagged.hashtag.add("PRIOR");   // 이전 스윕이 hashtag로 편입해 둔 게시물
		pagesByTag.put("cclime", List.of(
				sectionsBody("p2", media("PRIOR", RECENT, "poster0"), media("N1", RECENT, "poster1")),
				sectionsBody(null, media("N2", RECENT, "poster2"))));

		service(4, 1000).sweep(brand);

		assertThat(tagCalls()).isEqualTo(1);                       // 2페이지를 요청하지 않는다
		assertThat(tagged.upsertedHashtag).containsExactly("N1");  // 그 페이지의 신규는 처리한다
		assertThat(tagged.matchedTagsOf("PRIOR")).containsExactly("cclime");
	}

	/**
	 * 크로스 태그 백필 깊이 보존 — 이번 실행에서 다른 태그가 방금 편입한 코드는 종료 신호가 아니다.
	 * (신호로 보면 태그 B의 열거 깊이가 태그 순서에 좌우된다.)
	 */
	@Test
	void 이번_실행에서_방금_편입한_코드는_종료_신호가_아니다() {
		tags.tags = List.of("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("SHARED", RECENT, "poster0"))));
		pagesByTag.put("끌리메", List.of(
				sectionsBody("p2", media("SHARED", RECENT, "poster0")),
				sectionsBody(null, media("DEEP", RECENT, "poster9"))));

		service(4, 1000).sweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("SHARED", "DEEP");
	}

	@Test
	void 태그가_없으면_콜을_내지_않는다() {
		tags.tags = List.of();

		service(4, 1000).sweep(brand);

		assertThat(calls).isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"
```
Expected: 컴파일 실패 — `constructor BrandHashtagCollectService ... cannot be applied to given types`

- [ ] **Step 3: `collectionCutoff` 가시성 승격**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`:

old_string:
```java
	/**
	 * 브랜드별 수집 창 컷 — KST 캘린더 개월(요청서 "게시물 taken_at 기준 최근 N개월").
	 * 열거 깊이(백필)와 편입 필터가 같은 컷을 쓴다 — 창 밖 소급 태그가 편입되지 않게.
	 */
	private static Instant collectionCutoff(BrandRow brand, Instant now) {
```
new_string:
```java
	/**
	 * 브랜드별 수집 창 컷 — KST 캘린더 개월(요청서 "게시물 taken_at 기준 최근 N개월").
	 * 열거 깊이(백필)와 편입 필터가 같은 컷을 쓴다 — 창 밖 소급 태그가 편입되지 않게.
	 *
	 * <p>package-private인 이유(2026-08-27): 해시태그 수집({@link BrandHashtagCollectService})도
	 * 같은 컷을 써야 한다(설계 §2-2 — 구 windowDays=90 고정 폐기). 사본을 두면 반드시 갈린다.
	 */
	static Instant collectionCutoff(BrandRow brand, Instant now) {
```

- [ ] **Step 4: `BrandHashtagCollectService` 전면 재작성**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java` 파일 전체를 아래로 교체한다.

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 <b>수집</b>(2026-08-27 해시태그 직접 수집 설계 §2) — 태그별 recent 열거로 찾은
 * 게시물을 tagged/direct와 같은 풀({@code brand_tagged_post})에 직접 편입한다.
 *
 * <p><b>구 "감지" 구조는 폐기됐다.</b> 예전에는 별도 테이블({@code brand_hashtag_post})에 발견 시점
 * 관측값을 1회 저장하고 LLM 관련성 판정(SELF·DIRECT_TAGGED·MENTION·LLM)으로 노출을 걸렀을 뿐,
 * 스냅샷·댓글·게시자 보강도 주기 재수집도 없었다. 이제 편입 게이트는 <b>규칙 하나</b>(게시자
 * username이 브랜드 계정명과 정확히 일치하면 제외)뿐이고, 편입된 게시물은
 * {@link BrandCollectService#enrich}를 그대로 타 tagged와 동일한 보강·정산·재수집을 받는다.
 * 동명이인·무관 게시물 노이즈는 제품 결정(전부 편입)에 따라 수용한다 — 재도입이 필요하면 노출
 * 단계 필터로 돌아온다(매칭 태그·게시자 메타는 보존된다).
 *
 * <p><b>열거 종료</b>: recent 스트림은 IG 랭킹 혼합이라 taken_at이 단조가 아니다 — 기간으로는 종료를
 * 판정할 수 없어(창 밖 게시물이 중간에 섞인다) 기간은 <b>사후 필터</b>로만 쓰고, 종료는 "이전 스윕부터
 * 있던 게시물 도달"(dedup)로 판정한다. dedup 기준 집합이 "풀에 있는 코드"가 아니라 <b>"hashtag 성분이
 * 있는 코드"</b>인 것이 중요하다: 전자로 하면 tagged 열거가 이미 확보한 게시물이 전부 종료 신호가 돼
 * 스트림 깊은 곳의 hashtag-only 게시물에 영영 못 간다.
 *
 * <p><b>크로스 태그 종료 분리</b>(구 구조에서 이어받은 규칙): 태그 A가 이번 실행에서 편입한 코드가
 * 태그 B의 스트림에 실려도 B의 종료 신호로 보지 않는다 — 안 그러면 B의 열거 깊이가 태그 순서에
 * 좌우된다. {@code insertedThisRun}이 그 구분을 들고 있다.
 *
 * <p><b>편입 상한</b>: hashtag 성분 행이 브랜드당 {@code postLimit}(기본 1,000)에 닿으면 신규 편입을
 * 멈춘다(최신 우선 — recent 스트림 순서가 곧 우선순위다). 이미 풀에 있는 행에 hashtag 성분만 얹는
 * <b>겹침 병기는 상한 밖</b>이다(행이 늘지 않는다 — 설계 §2-3). tagged의 2,000 상한과는 별도 카운터다.
 *
 * <p>plain class + {@code BrandHashtagConfig}에서 배선(구 구조에서 이어받은 배치).
 */
public class BrandHashtagCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandHashtagRepository tags;
	private final TaggedPostRepository taggedPosts;
	private final BrandSnapshotWriter writer;
	private final BrandCollectService collect;
	private final int maxPages;
	private final int postLimit;

	public BrandHashtagCollectService(HikerClient hiker, BrandCallContext callContext,
			BrandHashtagRepository tags, TaggedPostRepository taggedPosts, BrandSnapshotWriter writer,
			BrandCollectService collect, int maxPages, int postLimit) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.tags = tags;
		this.taggedPosts = taggedPosts;
		this.writer = writer;
		this.collect = collect;
		this.maxPages = maxPages;
		this.postLimit = postLimit;
	}

	/** 스윕 1회분 상태 — 태그 루프가 공유한다(종료 판정·상한 잔량이 태그 간에 이어져야 한다). */
	private static final class SweepState {
		/** 브랜드 풀에 행이 있는 코드 — 겹침(병기, 상한 밖)과 신규(상한 적용)를 가른다. */
		final Set<String> known;
		/** hashtag 성분이 이미 있던 코드(스윕 시작 시점 스냅샷) — 조기 종료의 유일한 신호원. */
		final Set<String> hashtagKnown;
		/** 이번 실행에서 편입한 코드 — 종료 신호에서 제외(크로스 태그 깊이 보존). */
		final Set<String> insertedThisRun = new HashSet<>();
		/** 남은 신규 편입 여유. */
		int budget;

		SweepState(Set<String> known, Set<String> hashtagKnown, int budget) {
			this.known = known;
			this.hashtagKnown = hashtagKnown;
			this.budget = budget;
		}
	}

	/** 브랜드 1개분 해시태그 수집 — 태그가 없으면 콜 0으로 즉시 반환한다. */
	public void sweep(BrandRow brand) {
		// 콜 집계 스코프(어드민 크롤링 비용) — 열거·보강 콜 전부 이 브랜드 몫으로 계상된다.
		callContext.runScoped(brand.id(), () -> doSweep(brand));
	}

	private void doSweep(BrandRow brand) {
		List<String> tagList = tags.findTags(brand.id());
		if (tagList.isEmpty()) {
			return;
		}
		Instant now = Instant.now();
		Instant cutoff = BrandCollectService.collectionCutoff(brand, now);
		Set<String> hashtagKnown = taggedPosts.hashtagCodes(brand.id());
		SweepState state = new SweepState(new HashSet<>(taggedPosts.knownCodes(brand.id())), hashtagKnown,
				postLimit <= 0 ? Integer.MAX_VALUE : Math.max(0, postLimit - hashtagKnown.size()));
		int savedTotal = 0;
		for (String tag : tagList) {
			if (state.budget <= 0) {
				log.info("브랜드 해시태그 편입 상한({}) 도달 — {} 잔여 태그 열거 중단", postLimit, brand.username());
				break;
			}
			savedTotal += sweepTag(brand, tag, cutoff, now, state);
		}
		log.info("브랜드 해시태그 수집 완료 — {} 태그 {}개, 신규 편입 {}건, 잔여 편입 여유 {}건",
				brand.username(), tagList.size(), savedTotal, state.budget);
	}

	/**
	 * 태그 1개분 recent 열거 — maxPages까지 순회하되, 페이지에 "이전부터 있던" hashtag 성분 게시물이
	 * 하나라도 있으면 그 페이지의 신규만 처리하고 중단한다. 빈 페이지·커서 null도 자연 종료.
	 *
	 * @return 이번 태그가 만든 <b>신규 행</b> 수(겹침 병기는 세지 않는다 — 상한 밖)
	 */
	private int sweepTag(BrandRow brand, String tag, Instant cutoff, Instant now, SweepState state) {
		int created = 0;
		String cursor = null;
		for (int page = 0; page < maxPages; page++) {
			HikerClient.HashtagPage result = hiker.fetchHashtagRecentPage(tag, cursor);
			if (result.posts().isEmpty()) {
				break;
			}
			List<PostInfo> pagePosts = distinctByShortCode(result.posts().stream()
					.map(HikerClient.HashtagPost::post).toList());
			// 이 페이지에서 이미 hashtag 성분이 있는 코드 — 행이 있으니 매칭 태그는 남길 수 있다(FK 만족).
			Set<String> alreadyHashtag = new LinkedHashSet<>();
			for (PostInfo p : pagePosts) {
				if (state.hashtagKnown.contains(p.shortCode()) || state.insertedThisRun.contains(p.shortCode())) {
					alreadyHashtag.add(p.shortCode());
				}
			}
			List<PostInfo> fresh = pagePosts.stream()
					.filter(p -> eligible(brand, p, cutoff))
					.filter(p -> !alreadyHashtag.contains(p.shortCode()))
					.toList();
			List<PostInfo> overlap = fresh.stream().filter(p -> state.known.contains(p.shortCode())).toList();
			List<PostInfo> brandNew = fresh.stream().filter(p -> !state.known.contains(p.shortCode()))
					.limit(Math.max(0, state.budget - created))
					.toList();
			List<PostInfo> toCollect = new ArrayList<>(overlap);
			toCollect.addAll(brandNew);
			collectPage(brand, tag, toCollect, now);
			created += brandNew.size();
			for (PostInfo p : toCollect) {
				state.insertedThisRun.add(p.shortCode());
				state.known.add(p.shortCode());
			}
			if (!alreadyHashtag.isEmpty()) {
				taggedPosts.recordMatchedTags(brand.id(), alreadyHashtag, tag);
			}
			// 종료 트리거는 "이전부터 있던" 코드에만 반응한다(위 클래스 주석 — 크로스 태그 깊이 보존).
			if (alreadyHashtag.stream().anyMatch(state.hashtagKnown::contains)) {
				break;
			}
			cursor = result.nextPageId();
			if (cursor == null) {
				break;
			}
		}
		state.budget -= created;
		return created;
	}

	/**
	 * 편입 자격 — 결손 필드·수집 창 밖·브랜드 본인 게시물을 거른다(설계 §2-2).
	 * 기간은 <b>사후 필터</b>다: recent 스트림이 taken_at 비단조라 열거 종료 판정에는 쓸 수 없다.
	 * 본인 제외는 게시자 username과 브랜드 계정명의 <b>정확 일치</b>(대소문자 무시)다 — "브랜드명을
	 * 포함한 스태프 부계정" 같은 근사 매치는 제외 대상이 아니다(구 SELF 규칙과 같은 정의).
	 */
	private static boolean eligible(BrandRow brand, PostInfo post, Instant cutoff) {
		if (post.takenAt() == null || post.username() == null || post.username().isBlank()) {
			return false;
		}
		if (Instant.ofEpochSecond(post.takenAt()).isBefore(cutoff)) {
			return false;
		}
		return !post.username().equalsIgnoreCase(brand.username());
	}

	/**
	 * 페이지분 편입 — 복권 지표 보정 → 스냅샷·메타 적재 → 통합 풀 링크(hashtag 성분) → 매칭 태그 →
	 * 마지막 수집 시각 → 보강. 전부 upsert/멱등이라 재실행 안전하다.
	 *
	 * <p>보강 실패는 격리한다({@code BrandCollectService.enrichSafely}와 같은 규칙) — 열거분은 이미
	 * Hiker 콜을 지불하고 얻은 결과물이라, 보강 실패로 그날 열거를 통째로 버리면 손해가 크다.
	 * 미보강분은 야간 스윕 2단계(미보강 우선 배치)가 백스톱한다.
	 */
	private void collectPage(BrandRow brand, String tag, List<PostInfo> posts, Instant now) {
		if (posts.isEmpty()) {
			return;
		}
		List<PostInfo> adjusted = collect.adjustLotteryMetrics(posts);
		LocalDate today = LocalDate.now(KST);
		for (PostInfo p : adjusted) {
			writer.savePost(today, p);
			taggedPosts.upsertHashtag(brand.id(), p, now);
			taggedPosts.recordMatchedTag(brand.id(), p.shortCode(), tag);
		}
		taggedPosts.touchCrawled(brand.id(), adjusted.stream().map(PostInfo::shortCode).toList(), now);
		try {
			collect.enrich(brand, adjusted);
		} catch (RuntimeException e) {
			log.warn("해시태그 보강 실패(격리, 열거 계속) — {} 다음 스윕이 백스톱: {}",
					brand.username(), e.toString());
		}
	}

	/** 페이지 내 동일 shortCode 중복 제거(첫 등장 유지) — code 결손 아이템은 여기서 통째로 버린다. */
	private static List<PostInfo> distinctByShortCode(List<PostInfo> posts) {
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		for (PostInfo p : posts) {
			if (p.shortCode() != null && !p.shortCode().isBlank()) {
				byCode.putIfAbsent(p.shortCode(), p);
			}
		}
		return new ArrayList<>(byCode.values());
	}
}
```

- [ ] **Step 5: 통과 확인(배선은 다음 태스크라 아직 컴파일이 깨져 있다)**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:compileJava
```
Expected: FAIL — `BrandHashtagConfig.java`에서 `constructor BrandHashtagCollectService ... cannot be applied`
(Task 4에서 배선을 고친 뒤 테스트가 초록이 된다. 이 태스크의 커밋은 Task 4와 함께 한다 — 중간 상태로 커밋하면 빌드가 깨진 커밋이 남는다.)

---

## Task 4: LLM 관련성 판정 파이프라인 제거와 배선 교체

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/llm/BrandMentionJudge.java`
- Delete: `monitoring/src/test/java/com/celfit/monitoring/llm/BrandMentionJudgeTest.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/llm/VertexGeminiHttp.java:8`, `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractorGemini.java:17`(끊긴 `{@link}` 정리)

- [ ] **Step 1: 배선 교체**

`monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java` 파일 전체를 아래로 교체한다.

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.service.BrandCollectService;
import com.celfit.monitoring.service.BrandHashtagCollectService;
import com.celfit.monitoring.service.BrandSnapshotWriter;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 수집 배선(2026-08-27 해시태그 직접 수집 설계 §2) — 구 감지 구조의 LLM 관련성
 * 판정({@code BrandMentionJudge}) 빈은 파이프라인과 함께 제거됐다. 남은 설정 값은 두 개다:
 *
 * <ul>
 *   <li>{@code max-pages} — 태그당 recent 열거 최대 페이지(구 구조에서 그대로).</li>
 *   <li>{@code post-limit} — 브랜드당 hashtag 성분 행 상한(설계 §0, 기본 1,000). tagged의
 *       {@code collection-post-limit}(2,000)과 <b>별도 카운터</b>다. 0 이하는 무제한
 *       (backfill-max-per-run·collection-post-limit 관용 일치).</li>
 * </ul>
 *
 * <p>구 {@code window-days}(90일 고정)는 폐기됐다 — 기간 컷은 이제 브랜드의 collectionMonths를
 * 그대로 쓴다({@code BrandCollectService.collectionCutoff}).
 */
@Configuration
public class BrandHashtagConfig {

	@Bean
	public BrandHashtagCollectService brandHashtagCollectService(HikerClient hiker,
			BrandCallContext callContext, BrandHashtagRepository tags, TaggedPostRepository taggedPosts,
			BrandSnapshotWriter writer, BrandCollectService collect,
			@Value("${monitoring.brand.hashtag.max-pages:4}") int maxPages,
			@Value("${monitoring.brand.hashtag.post-limit:1000}") int postLimit) {
		return new BrandHashtagCollectService(hiker, callContext, tags, taggedPosts, writer, collect,
				maxPages, postLimit);
	}
}
```

- [ ] **Step 2: 판정기 삭제와 끊긴 javadoc 링크 정리**

```bash
git rm monitoring/src/main/java/com/celfit/monitoring/llm/BrandMentionJudge.java \
       monitoring/src/test/java/com/celfit/monitoring/llm/BrandMentionJudgeTest.java
```

`monitoring/src/main/java/com/celfit/monitoring/llm/VertexGeminiHttp.java`:

old_string:
```java
 * {@link GeminiHttp} seam의 Vertex 구현(2026-08-18 전환) — 호출부({@link BrandMentionJudge}·
```
new_string:
```java
 * {@link GeminiHttp} seam의 Vertex 구현(2026-08-18 전환) — 호출부(
```

`monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractorGemini.java`:

old_string:
```java
 * <p>{@link com.celfit.monitoring.llm.BrandMentionJudge}와 달리 api-key 미설정·응답 파싱 실패를
```
new_string:
```java
 * <p>구 해시태그 관련성 판정기(2026-08-27 폐기)와 달리 api-key 미설정·응답 파싱 실패를
```

- [ ] **Step 3: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"
```
Expected: PASS (9 tests)

- [ ] **Step 4: 커밋(Task 3 + Task 4를 한 커밋으로)**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java \
        monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java \
        monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java \
        monitoring/src/main/java/com/celfit/monitoring/llm/VertexGeminiHttp.java \
        monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractorGemini.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java
git commit -m "feat(monitoring): 해시태그 스윕을 감지에서 직접 수집으로 전환 - LLM 관련성 판정 폐기"
```

---

## Task 5: 2단계 재수집 일반화 — 미보강 우선 + 스윕당 상한

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java:33-117`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java:27,100-133`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java:183`

> **왜 상한이 필요한가(설계 §5·§6):** 이관분은 `last_crawled_at`이 NULL이라 180일 안이면 전부 즉시 due다 — 상한이 없으면 이관 직후 첫 스윕이 브랜드당 최대 1,000건의 단건 콜 + 보강 콜을 한 번에 쏟아낸다("전역 동시 콜 14" 예산을 넘긴다). 상한 안에서 **미보강 행이 먼저** 오도록 정렬(Task 2)했으므로, 이관분이 매일 상한만큼 점진 충전된다.

- [ ] **Step 1: 실패 테스트 추가**

`BrandDirectCollectServiceTest.java`의 마지막 `}` 앞에 아래를 삽입한다.

old_string:
```java
		service().sweepDirect(brand);

		assertThat(postCalls()).isEqualTo(1);
	}
}
```
new_string:
```java
		service().sweepDirect(brand);

		assertThat(postCalls()).isEqualTo(1);
	}

	// ── 스윕당 상한(2026-08-27 해시태그 직접 수집 설계 §5) ─────────────────────

	/**
	 * 이관분은 last_crawled_at이 NULL이라 전부 즉시 due다 — 상한이 없으면 첫 스윕이 브랜드당
	 * 수백~1,000건의 단건 콜을 한 번에 쏟아내 전역 콜 예산을 넘긴다. 잔여는 다음 스윕이 이어받는다
	 * (모수 정렬이 미보강 우선이라 이관분부터 충전된다).
	 */
	@Test
	void 스윕당_상한을_넘는_due는_잘리고_다음_스윕으로_넘어간다() {
		for (int i = 0; i < 5; i++) {
			String code = "M" + i;
			tagged.due.add(new TaggedPostRepository.TrackedPost(code, Instant.ofEpochSecond(RECENT), null));
			postResponses.put(code, postJson(code, RECENT, 200 + i));
		}

		serviceWithLimit(2).sweepUnenumerated(brand);

		assertThat(postCalls()).isEqualTo(2);
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("M0", "M1");
	}
}
```

- [ ] **Step 2: 테스트 헬퍼·호출명 일괄 교체**

같은 파일에서 서비스 팩토리에 상한 인자를 넣고, 호출부 6곳의 메서드명을 바꾼다.

old_string:
```java
	private BrandDirectCollectService service() {
```
new_string:
```java
	private BrandDirectCollectService service() {
		return serviceWithLimit(300);
	}

	private BrandDirectCollectService serviceWithLimit(int sweepLimit) {
```

old_string:
```java
		return new BrandDirectCollectService(client(), callContext, writer, tagged, collect);
	}
```
new_string:
```java
		return new BrandDirectCollectService(client(), callContext, writer, tagged, collect, sweepLimit);
	}
```

그다음 남은 `sweepDirect` 호출 6곳과 섹션 주석을 한 번에 바꾼다(macOS sed는 `-i ''` 필수).

```bash
sed -i '' 's/service()\.sweepDirect(brand)/service().sweepUnenumerated(brand)/g; s/── sweepDirect — 격리/── sweepUnenumerated — 격리/' \
  monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
grep -c "sweepDirect" monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
```
Expected: 위 grep이 `0`

`monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java:183`의 스텁 오버라이드도 이름을 맞춘다.

old_string:
```java
		public void sweepDirect(BrandRow brand) {
```
new_string:
```java
		public void sweepUnenumerated(BrandRow brand) {
```

- [ ] **Step 3: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"
```
Expected: 컴파일 실패 — `constructor BrandDirectCollectService ... cannot be applied to given types` / `cannot find symbol: method sweepUnenumerated`

- [ ] **Step 4: 서비스 구현**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java`의 필드·생성자에 상한을 더한다.

old_string:
```java
	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandSnapshotWriter writer;
	private final TaggedPostRepository taggedPosts;
	private final BrandCollectService collect;

	public BrandDirectCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			TaggedPostRepository taggedPosts, BrandCollectService collect) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.taggedPosts = taggedPosts;
		this.collect = collect;
	}
```
new_string:
```java
	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandSnapshotWriter writer;
	private final TaggedPostRepository taggedPosts;
	private final BrandCollectService collect;
	/** 스윕당 브랜드당 단건 수집 상한(설계 §5) — 0 이하는 무제한. */
	private final int sweepLimit;

	public BrandDirectCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			TaggedPostRepository taggedPosts, BrandCollectService collect,
			@Value("${monitoring.brand.unenumerated-sweep-limit:300}") int sweepLimit) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.taggedPosts = taggedPosts;
		this.collect = collect;
		this.sweepLimit = sweepLimit;
	}
```

`@Value` import를 추가한다.

old_string:
```java
import org.springframework.stereotype.Service;
```
new_string:
```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
```

sweep 진입점과 본체를 교체한다.

old_string:
```java
	public void sweepDirect(BrandRow brand) {
		callContext.scoped(brand.id(), () -> {
			doSweepDirect(brand);
			return null;
		});
	}

	private void doSweepDirect(BrandRow brand) {
		Instant now = Instant.now();
		List<TaggedPostRepository.TrackedPost> due = taggedPosts
				.unenumeratedDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE)).stream()
				.filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now))
				.toList();
		List<PostInfo> batch = new ArrayList<>();
```
new_string:
```java
	public void sweepUnenumerated(BrandRow brand) {
		callContext.scoped(brand.id(), () -> {
			doSweepUnenumerated(brand);
			return null;
		});
	}

	private void doSweepUnenumerated(BrandRow brand) {
		Instant now = Instant.now();
		List<TaggedPostRepository.TrackedPost> dueAll = taggedPosts
				.unenumeratedDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE)).stream()
				.filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now))
				.toList();
		// 스윕당 상한(2026-08-27 설계 §5) — 구 감지 데이터 이관분은 last_crawled_at이 NULL이라 180일
		// 안이면 전부 즉시 due다. 상한이 없으면 이관 직후 첫 스윕이 브랜드당 최대 1,000건의 단건 콜 +
		// 보강 콜을 한 번에 쏟아내 "전역 동시 콜 14" 예산을 넘긴다. 모수 정렬이 미보강 우선이라
		// (unenumeratedDuePosts) 잘리는 쪽은 항상 이미 보강된 행이고, 잔여는 다음 스윕이 이어받는다.
		List<TaggedPostRepository.TrackedPost> due = sweepLimit > 0 && dueAll.size() > sweepLimit
				? dueAll.subList(0, sweepLimit) : dueAll;
		if (due.size() < dueAll.size()) {
			log.info("2단계 단건 수집 상한({}) 컷 — 브랜드 {} due {}건 중 {}건만 수집, 잔여는 다음 스윕",
					sweepLimit, brand.username(), dueAll.size(), due.size());
		}
		List<PostInfo> batch = new ArrayList<>();
```

메서드 javadoc의 제목도 새 의미로 바꾼다.

old_string:
```java
	 * 야간 스윕 2단계(설계 §3-2) — {@code directDuePosts} 중 {@link BrandCrawlPolicy#due}인 것만
```
new_string:
```java
	 * 야간 스윕 2단계(설계 §3-2, <b>2026-08-27 hashtag 일반화</b>) — {@code unenumeratedDuePosts}
	 * (tagged 열거가 도달할 수 없는 direct·hashtag 성분 행) 중 {@link BrandCrawlPolicy#due}인 것만
```

- [ ] **Step 5: 스윕 잡 배선**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java`:

old_string:
```java
				directCollect.sweepDirect(b);
```
new_string:
```java
				directCollect.sweepUnenumerated(b);
```

old_string:
```java
 * 단건 수집({@link BrandDirectCollectService#sweepDirect}, 2단계)이, 그 다음에 해시태그 발견
 * 스윕(3단계)이 각자 격리된 채로 돈다.
```
new_string:
```java
 * 단건 수집({@link BrandDirectCollectService#sweepUnenumerated}, 2단계)이, 그 다음에 해시태그
 * 수집(3단계)이 각자 격리된 채로 돈다. 2026-08-27 해시태그 직접 수집 전환으로 2단계 모수는
 * direct ∪ hashtag(열거가 도달할 수 없는 행 전부)로 넓어졌고, 3단계는 "감지"가 아니라 "수집"이다.
```

- [ ] **Step 6: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.*"
```
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java \
        monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java
git commit -m "feat(monitoring): 2단계 재수집을 direct∪hashtag로 일반화 - 미보강 우선·스윕당 상한"
```

---

## Task 6: 구 감지 데이터 이관 마이그레이션

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_hashtag_post_migration.sql`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagPostMigrationTest.java` (신규)

> **채번:** `date -u +%Y%m%d%H%M%S`. Task 1의 스키마 마이그레이션보다 **뒤 번호**여야 한다(같은 실행 세션에서 뒤에 만들면 자연히 그렇게 된다).

- [ ] **Step 1: 실패 테스트 작성**

`monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagPostMigrationTest.java`:

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 구 해시태그 감지 데이터 이관(2026-08-27 해시태그 직접 수집 설계 §5) 검증 — 컨테이너 기동 시점의
 * DB는 비어 있어 마이그레이션이 no-op으로 지나가므로, <b>마이그레이션 파일 원문을 classpath에서
 * 읽어 다시 실행</b>해 검증한다(테스트가 SQL 사본을 들고 있으면 파일과 조용히 갈린다).
 * 파일명은 UTC 채번이라 글롭으로 찾는다.
 */
class BrandHashtagPostMigrationTest {

	private static final OffsetDateTime SEEN = OffsetDateTime.parse("2026-08-20T00:00:00Z");

	JdbcTemplate db;
	long brandId;

	private static String migrationSql() throws IOException {
		Resource[] found = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/V*__brand_hashtag_post_migration.sql");
		assertThat(found).hasSize(1);
		return found[0].getContentAsString(StandardCharsets.UTF_8);
	}

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	private void insertHashtagPost(String code, String verdict, OffsetDateTime takenAt) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                verdict, verdict_source, first_seen_at)
				VALUES (?, ?, 'cclime', ?, ?, ?, 'RULE', ?)""",
				brandId, code, "poster_" + code, takenAt, verdict, SEEN);
	}

	private Set<String> migratedCodes() {
		return Set.copyOf(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL",
				String.class, brandId));
	}

	/** verdict 무관 전량 이관 — 구 LLM 판정은 폐기됐으므로 IRRELEVANT도 새 풀에 들어간다. */
	@Test
	void verdict와_무관하게_이관한다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		insertHashtagPost("IRR", "IRRELEVANT", OffsetDateTime.parse("2026-08-18T00:00:00Z"));
		insertHashtagPost("UNC", "UNCERTAIN", OffsetDateTime.parse("2026-08-17T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(migratedCodes()).containsExactlyInAnyOrder("REL", "IRR", "UNC");
	}

	/** SELF(브랜드 본인)만 제외 — 새 수집 규칙의 본인 제외와 정합. */
	@Test
	void SELF_판정분은_이관하지_않는다() throws IOException {
		insertHashtagPost("SELF1", "SELF", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(migratedCodes()).isEmpty();
	}

	/** 이미 tagged로 있던 행(겹침)은 hashtag 성분만 얹고 tag_detected_at을 보존한다. */
	@Test
	void 겹침_행은_hashtag_성분만_병기된다() throws IOException {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at, tag_detected_at)
				VALUES (?, 'BOTH', 'poster_BOTH', ?, ?)""",
				brandId, OffsetDateTime.parse("2026-08-19T00:00:00Z"), SEEN);
		insertHashtagPost("BOTH", "DIRECT_TAGGED", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NOT NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'BOTH'",
				Boolean.class, brandId)).isTrue();
	}

	/** 브랜드당 최신순 1000 상한 — 넘치는 오래된 분은 이관하지 않는다. */
	@Test
	void 브랜드당_최신_1000건까지만_이관한다() throws IOException {
		for (int i = 0; i < 1005; i++) {
			insertHashtagPost("C" + i, "RELEVANT",
					OffsetDateTime.parse("2026-08-20T00:00:00Z").minusMinutes(i));
		}

		db.execute(migrationSql());

		assertThat(migratedCodes()).hasSize(1000).contains("C0", "C999").doesNotContain("C1000", "C1004");
	}

	/** 매칭 태그도 함께 옮긴다 — 이게 없으면 이관분이 was 격리 필터를 통과하지 못한다. */
	@Test
	void 매칭_태그를_새_테이블로_옮긴다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		db.update("INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag) VALUES (?, 'REL', '끌리메')",
				brandId);

		db.execute(migrationSql());

		assertThat(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'REL'",
				String.class, brandId)).containsExactlyInAnyOrder("cclime", "끌리메");
	}

	/** 이관분은 미보강(enriched_at NULL)이라 was 표시 게이트를 아직 통과하지 않는다(스윕이 충전한다). */
	@Test
	void 이관분은_미보강_상태로_들어온다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(db.queryForObject(
				"SELECT enriched_at IS NULL AND last_crawled_at IS NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'REL'",
				Boolean.class, brandId)).isTrue();
	}

	/** 재실행 안전 — 롤포워드·수동 재적용에서 중복 키로 죽지 않는다. */
	@Test
	void 두_번_실행해도_멱등이다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());
		db.execute(migrationSql());

		assertThat(migratedCodes()).containsExactly("REL");
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_matched_tag WHERE brand_id = ?", Integer.class, brandId))
				.isEqualTo(1);
	}

	/** 매칭 태그는 이관된 행에만 붙는다 — 상한·SELF로 빠진 행의 태그를 옮기면 FK가 터진다. */
	@Test
	void 이관되지_않은_행의_매칭_태그는_옮기지_않는다() throws IOException {
		insertHashtagPost("SELF1", "SELF", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		db.update("INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag) VALUES (?, 'SELF1', '끌리메')",
				brandId);

		db.execute(migrationSql());

		assertThat(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'SELF1'",
				String.class, brandId)).isEmpty();
	}

	@Test
	void 이관_대상이_없으면_아무것도_하지_않는다() throws IOException {
		db.execute(migrationSql());

		assertThat(migratedCodes()).isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagPostMigrationTest"
```
Expected: FAIL — 전 케이스가 `migrationSql()`의 `Expected size: 1 but was: 0`

- [ ] **Step 3: 마이그레이션 파일 생성**

```bash
echo "monitoring/src/main/resources/db/migration/V$(date -u +%Y%m%d%H%M%S)__brand_hashtag_post_migration.sql"
```

그 경로에 아래 내용을 그대로 쓴다.

```sql
-- 구 해시태그 감지 데이터 이관(2026-08-27 해시태그 직접 수집 설계 §5).
--
-- brand_hashtag_post(감지 전용 테이블)의 게시물을 통합 풀 brand_tagged_post로 승격한다.
-- 구 LLM 관련성 판정(verdict)은 폐기됐으므로 IRRELEVANT·UNCERTAIN도 전량 이관한다. 단
-- SELF(브랜드 본인 게시물)만 제외한다 — 새 수집 규칙의 "본인 게시물 제외"와 정합해야 한다.
--
-- 브랜드당 최신순 1,000건 상한(설계 §0의 편입 상한과 같은 수) — 순위는 taken_at DESC이고,
-- 동시각 타이브레이크로 short_code를 둬 재실행 결과가 흔들리지 않게 한다(멱등의 전제).
--
-- author_ig_user_id는 구 테이블에 없다 → NULL. 야간 스윕 2단계의 단건 수집이 채운다.
-- enriched_at·last_crawled_at은 채우지 않는다: 이관분은 게시자·댓글·스냅샷이 비어 있으므로
-- was 표시 게이트(enriched_at IS NOT NULL)를 통과하면 안 되고, last_crawled_at NULL이 곧
-- "즉시 due"라 2단계 미보강 우선 배치가 상한 안에서 점진 충전한다.
--
-- 겹침(이미 tagged·direct로 있던 행)은 hashtag_detected_at만 얹는다 — COALESCE라 재실행해도
-- 최초 시각이 밀리지 않는다.
INSERT INTO brand_tagged_post
    (brand_id, short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
     tag_detected_at, hashtag_detected_at)
SELECT r.brand_id, r.short_code, r.author_username, NULL, r.taken_at, r.first_seen_at,
       NULL, r.first_seen_at
FROM (
    SELECT hp.brand_id, hp.short_code, hp.author_username, hp.taken_at, hp.first_seen_at,
           row_number() OVER (PARTITION BY hp.brand_id
                              ORDER BY hp.taken_at DESC, hp.short_code) AS rn
    FROM brand_hashtag_post hp
    WHERE hp.verdict <> 'SELF'
) r
WHERE r.rn <= 1000
ON CONFLICT (brand_id, short_code) DO UPDATE SET
    hashtag_detected_at = COALESCE(brand_tagged_post.hashtag_detected_at, EXCLUDED.hashtag_detected_at);

-- 매칭 태그 이관 — 새 테이블의 FK가 brand_tagged_post를 향하므로, 위에서 실제로 이관된
-- (또는 원래 풀에 있던) 행에만 붙인다. 상한·SELF로 빠진 행의 태그를 옮기면 FK가 터진다.
-- 조인 조건에 hashtag_detected_at IS NOT NULL을 둬 "구 감지 유래 행"으로 좁힌다.
INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
SELECT m.brand_id, m.short_code, m.tag
FROM brand_hashtag_post_matched_tags m
JOIN brand_tagged_post t ON t.brand_id = m.brand_id AND t.short_code = m.short_code
WHERE t.hashtag_detected_at IS NOT NULL
ON CONFLICT DO NOTHING;

-- 발견 경로 태그(brand_hashtag_post.matched_tag, NOT NULL 단일 컬럼)도 함께 옮긴다. V20260819054457이
-- 이 값을 matched_tags로 백필했고 이후 스윕도 계속 기록했으므로 대개 위 문장에 이미 포함되지만,
-- 두 벌 사이에 구멍이 있으면 그 게시물은 매칭 태그가 0건이 돼 was 격리 필터(fail-open 폐기)에서
-- 영영 숨는다. 멱등이라 중복은 무해하니 안전 쪽으로 한 번 더 긁는다.
INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
SELECT hp.brand_id, hp.short_code, hp.matched_tag
FROM brand_hashtag_post hp
JOIN brand_tagged_post t ON t.brand_id = hp.brand_id AND t.short_code = hp.short_code
WHERE t.hashtag_detected_at IS NOT NULL
ON CONFLICT DO NOTHING;
```

- [ ] **Step 4: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandHashtagPostMigrationTest"
```
Expected: PASS (9 tests)

- [ ] **Step 5: monitoring 모듈 전량 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/ \
        monitoring/src/test/java/com/celfit/monitoring/store/BrandHashtagPostMigrationTest.java
git commit -m "feat(monitoring): 구 해시태그 감지 데이터를 통합 풀로 이관 - SELF 제외·브랜드당 1000"
```

---

## Task 7: was 조회 계층 — hashtag 성분과 매칭 태그 산지 교체

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java:77-95`(`findBrandPostsInWindow`), `:232-242`(`findMatchedTags`), `:354-358`(`BrandTaggedPostRow`)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`, `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java` (픽스처 인자 추가)

- [ ] **Step 1: 행 record에 hashtagDetectedAt 추가**

`BrandReadRepository.java`:

old_string:
```java
	public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
			OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount,
			OffsetDateTime lastCrawledAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt,
			OffsetDateTime unavailableAt) {
	}
```
new_string:
```java
	public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
			OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount,
			OffsetDateTime lastCrawledAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt,
			OffsetDateTime unavailableAt, OffsetDateTime hashtagDetectedAt) {
	}
```

같은 record의 javadoc 끝에 새 필드 설명을 붙인다.

old_string:
```java
	 * <p>{@code unavailableAt}(야간 스윕 단건 콜이 404를 받은 시각, null이면 정상 — 값이 있으면
	 * trackingStatus가 hidden으로 내려간다, 2026-08-25 설계).
	 */
```
new_string:
```java
	 * <p>{@code unavailableAt}(야간 스윕 단건 콜이 404를 받은 시각, null이면 정상 — 값이 있으면
	 * trackingStatus가 hidden으로 내려간다, 2026-08-25 설계).
	 *
	 * <p>{@code hashtagDetectedAt}(2026-08-27 해시태그 직접 수집 설계 §1) — 해시태그 열거가 이
	 * 게시물을 처음 편입한 시각. 세 타임스탬프가 모두 null이 아닐 수 있고(3성분 겹침), tag·direct가
	 * 둘 다 null이면 hashtag-only 행이다. {@code source} 3원화와 사용자 격리 필터의 입력이다
	 * ({@code BrandPostAssembler.resolveSource}·{@code filterVisibleToUser}).
	 */
```

- [ ] **Step 2: SELECT에 컬럼 추가**

old_string:
```java
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at,
				       unavailable_at
				FROM brand_tagged_post
```
new_string:
```java
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at,
				       unavailable_at, hashtag_detected_at
				FROM brand_tagged_post
```

- [ ] **Step 3: `findMatchedTags` 산지 교체**

old_string:
```java
	/**
	 * 해시태그 발견 게시물의 매칭 태그 전체(2026-08-19, was 사용자 스코프 필터 지원) —
	 * {@code brand_hashtag_post_matched_tags}(모니터링 스윕이 게시물당 매칭된 활성 태그 전부를
	 * 기록하는 M:N 테이블, {@code matched_tag} 단일 컬럼과 별개). 조회자 본인의 태그 원장
	 * ({@code app.brand_hashtag_tags})과의 교집합 판정은 was 코드({@code BrandHashtagPostAssembler})가
	 * 한다 — 여기서는 monitoring DB만 읽는다(시스템 경계, app 스키마와 SQL 조인 금지).
	 */
	public List<MatchedTagRow> findMatchedTags(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, tag FROM brand_hashtag_post_matched_tags
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
```
new_string:
```java
	/**
	 * 통합 풀 게시물의 매칭 태그 전체(2026-08-19 신설 → <b>2026-08-27 산지 교체</b>) —
	 * {@code brand_post_matched_tag}(스윕이 게시물당 매칭된 활성 태그 전부를 기록하는 M:N 테이블).
	 * 구 산지 {@code brand_hashtag_post_matched_tags}는 감지 구조 폐기와 함께 읽기를 중단했다
	 * (테이블 DROP은 다음 릴리스 — expand-contract).
	 *
	 * <p>조회자 본인의 태그 원장({@code app.brand_hashtag_tags})과의 교집합 판정은 was 코드
	 * ({@code BrandPostAssembler.filterVisibleToUser})가 한다 — 여기서는 monitoring DB만 읽는다
	 * (시스템 경계, app 스키마와 SQL 조인 금지).
	 */
	public List<MatchedTagRow> findMatchedTags(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, tag FROM brand_post_matched_tag
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
```

- [ ] **Step 4: 픽스처 인자 추가(컴파일 복구)**

`BrandPostAssemblerTest.java`에서 6번 반복되는 동일 라인은 sed로 한 번에 고친다(macOS sed는 `-i ''` 필수).

```bash
sed -i '' 's/SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null)));/SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));/g' \
  was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java
```

나머지 5곳은 Edit으로 고친다.

old_string:
```java
						7L, rowCrawledLater, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null),
```
new_string:
```java
						7L, rowCrawledLater, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
```

old_string:
```java
						7L, rowCrawledEarlier, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null),
```
new_string:
```java
						7L, rowCrawledEarlier, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
```

old_string:
```java
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), 0L, null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null),
```
new_string:
```java
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), 0L, null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
```

old_string:
```java
				7L, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"),
				OffsetDateTime.parse("2026-08-20T18:00:00Z"));
```
new_string:
```java
				7L, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"),
				OffsetDateTime.parse("2026-08-20T18:00:00Z"), null);
```

old_string:
```java
				directRegisteredAt == null ? null : OffsetDateTime.parse(directRegisteredAt), null);
```
new_string:
```java
				directRegisteredAt == null ? null : OffsetDateTime.parse(directRegisteredAt), null, null);
```

`V1BrandPostsControllerTest.java`의 픽스처 3곳:

old_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				firstSeenAt, firstSeenAt, null, null);
	}
```
new_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				firstSeenAt, firstSeenAt, null, null, null);
	}
```

old_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 0L,
				registeredAt, null, registeredAt, null);
	}
```
new_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 0L,
				registeredAt, null, registeredAt, null, null);
	}
```

old_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				registeredAt, firstSeenAt, registeredAt, null);
	}
```
new_string:
```java
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				registeredAt, firstSeenAt, registeredAt, null, null);
	}
```

- [ ] **Step 5: 통과 확인(기존 계약 무변)**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest" \
                    --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest" \
                    --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"
```
Expected: PASS — 이 태스크는 컬럼·산지만 넓힌다(동작 변경 없음)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java
git commit -m "feat(was): 브랜드 풀 조회에 hashtag 성분 추가·매칭 태그 산지를 통합 테이블로 교체"
```

---

## Task 8: source 3원화와 사용자 격리 필터

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java:56-103`(상수·생성자), `:170-237`(조립 배선·필터), `:426-440`(`resolveSource`)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`

- [ ] **Step 1: 실패 테스트 추가**

`BrandPostAssemblerTest.java`의 `// ---------- 노출 필터(등록자 전용, 08-19) ----------` 섹션 앞에 아래를 삽입한다.

old_string:
```java
	// ---------- 노출 필터(등록자 전용, 08-19) ----------
```
new_string:
```java
	// ---------- source 3원화·해시태그 격리(2026-08-27 설계 §3) ----------

	/** hashtag-only 행은 조회자의 장부 태그와 게시물 매칭 태그의 교집합이 있을 때만 보인다. */
	@Test
	void 내_태그와_겹치는_해시태그_게시물만_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("MINE"), hashtagRow("THEIRS")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MINE", "끌리메"),
				new BrandReadRepository.MatchedTagRow("THEIRS", "남의태그")));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.shortcode()).isEqualTo("MINE");
			assertThat(post.source()).isEqualTo("hashtag");
		});
	}

	/**
	 * fail-open 폐기(설계 §3) — 매칭 기록이 없는 행은 숨긴다. 구 감지 목록은 "매칭 기록이 없으면
	 * 전원 노출"이었지만, 태그 장부 백필 이후 모든 사용자에게 최소 태그가 있으므로 그 완화가
	 * 필요 없어졌고, 남겨 두면 남의 태그로 잡힌 게시물이 전원에게 새어 나간다.
	 */
	@Test
	void 매칭_기록이_없는_해시태그_게시물은_숨긴다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("ORPHAN")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of());

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).isEmpty();
	}

	/** 장부가 비어 있으면 아무것도 안 보인다 — 구 fail-open(전원 노출)의 회귀 방지. */
	@Test
	void 장부가_비면_해시태그_게시물은_보이지_않는다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("MINE")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of());
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MINE", "끌리메")));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).isEmpty();
	}

	/**
	 * tagged 성분이 있으면 브랜드 공유(기존 규칙) — 격리 필터도 태그 장부 조회도 타지 않는다.
	 * source 우선순위는 direct(등록자 관점) > tagged > hashtag다.
	 */
	@Test
	void tagged_성분이_있으면_해시태그_겹침이어도_tagged로_전원_노출된다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		var row = new BrandReadRepository.BrandTaggedPostRow("BOTH", "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				7L, null, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null,
				OffsetDateTime.parse("2026-08-06T03:00:00Z"));
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(List.of(row));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.source()).isEqualTo("tagged"));
		verify(hashtagTagRepository, never()).findByUserAndBrand(anyLong(), anyLong());
	}

	/** hashtag 성분이 함께 있으면, 남이 등록한 direct 행도 내 태그로 보인다 — 관점은 hashtag다. */
	@Test
	void 남이_등록한_direct에_hashtag_성분이_있으면_hashtag로_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var row = new BrandReadRepository.BrandTaggedPostRow("MIX", "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				7L, null, null, OffsetDateTime.parse("2026-08-07T02:00:00Z"), null,
				OffsetDateTime.parse("2026-08-06T03:00:00Z"));
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(List.of(row));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of());   // 내가 등록한 게 아니다
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MIX", "끌리메")));

		var assembler = new BrandPostAssembler(repository, mock(BrandPostCampaignRepository.class),
				directRepository, mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class),
				hashtagTagRepository, false);
		var posts = assembler.assembleBrandPosts(7L, accountRow(), false,
				BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.source()).isEqualTo("hashtag"));
	}

	/** hashtag-only 픽스처 — tag_detected_at·direct_registered_at 없이 hashtag_detected_at만 채워진 행. */
	private static BrandReadRepository.BrandTaggedPostRow hashtagRow(String code) {
		return new BrandReadRepository.BrandTaggedPostRow(code, "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				0L, null, null, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"));
	}

	private static BrandPostAssembler newAssemblerWithTags(BrandReadRepository repository,
			com.celfit.was.monitoring.BrandHashtagTagRepository hashtagTagRepository) {
		return new BrandPostAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), hashtagTagRepository, false);
	}

	// ---------- 노출 필터(등록자 전용, 08-19) ----------
```

- [ ] **Step 2: 기존 헬퍼 시그니처 확장**

old_string:
```java
	private static BrandPostAssembler newAssembler(BrandReadRepository repository,
			BrandPostCampaignRepository campaignRepository, BrandDirectPostRepository directRepository,
			TrackingItemAssembler trackingAssembler, MonitoringItemRepository itemRepository,
			boolean exposeAdDisclosure) {
		return new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, exposeAdDisclosure);
	}
```
new_string:
```java
	private static BrandPostAssembler newAssembler(BrandReadRepository repository,
			BrandPostCampaignRepository campaignRepository, BrandDirectPostRepository directRepository,
			TrackingItemAssembler trackingAssembler, MonitoringItemRepository itemRepository,
			boolean exposeAdDisclosure) {
		return new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class),
				exposeAdDisclosure);
	}
```

`was/src/test/java/com/celfit/was/v1/perfdashboard/PerfDiagnosisHarnessTest.java`에도 실 조립 사이트가 하나 더 있다(성과 대시보드 지연 측정 하네스).

old_string:
```java
		BrandPostAssembler brandPostAssembler = new BrandPostAssembler(brandReadRepository,
				postCampaignRepository, directPostRepository, trackingItemAssembler, itemRepository, false);
```
new_string:
```java
		BrandPostAssembler brandPostAssembler = new BrandPostAssembler(brandReadRepository,
				postCampaignRepository, directPostRepository, trackingItemAssembler, itemRepository,
				new BrandHashtagTagRepository(app), false);
```

같은 파일에 import를 추가한다.

old_string:
```java
import com.celfit.was.monitoring.BrandDirectPostRepository;
```
new_string:
```java
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
```

- [ ] **Step 3: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"
```
Expected: 컴파일 실패 — `constructor BrandPostAssembler ... cannot be applied to given types`

- [ ] **Step 4: 상수·생성자 확장**

`BrandPostAssembler.java`:

old_string:
```java
	static final String SOURCE_TAGGED = "tagged";
	static final String SOURCE_DIRECT = "direct";
```
new_string:
```java
	static final String SOURCE_TAGGED = "tagged";
	static final String SOURCE_DIRECT = "direct";
	/** 해시태그 열거로만 편입된 행(2026-08-27 설계 §3) — 사용자 격리 필터가 걸리는 유일한 source. */
	static final String SOURCE_HASHTAG = "hashtag";
```

old_string:
```java
	private final MonitoringItemRepository monitoringItemRepository;
	/** 광고 표기 판정 노출 게이트(스펙 §10-2 드라이런 후 개통) — 기본 false, was 문서화(Task 18)에서 개통 절차 안내. */
	private final boolean exposeAdDisclosure;
	private static final ObjectMapper OM = new ObjectMapper();

	public BrandPostAssembler(BrandReadRepository brandReadRepository,
			BrandPostCampaignRepository postCampaignRepository, BrandDirectPostRepository directPostRepository,
			TrackingItemAssembler trackingItemAssembler, MonitoringItemRepository monitoringItemRepository,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.brandReadRepository = brandReadRepository;
		this.postCampaignRepository = postCampaignRepository;
		this.directPostRepository = directPostRepository;
		this.trackingItemAssembler = trackingItemAssembler;
		this.monitoringItemRepository = monitoringItemRepository;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}
```
new_string:
```java
	private final MonitoringItemRepository monitoringItemRepository;
	/** 해시태그 격리 필터(2026-08-27 설계 §3) — 조회자 본인의 태그 원장. */
	private final BrandHashtagTagRepository hashtagTagRepository;
	/** 광고 표기 판정 노출 게이트(스펙 §10-2 드라이런 후 개통) — 기본 false, was 문서화(Task 18)에서 개통 절차 안내. */
	private final boolean exposeAdDisclosure;
	private static final ObjectMapper OM = new ObjectMapper();

	public BrandPostAssembler(BrandReadRepository brandReadRepository,
			BrandPostCampaignRepository postCampaignRepository, BrandDirectPostRepository directPostRepository,
			TrackingItemAssembler trackingItemAssembler, MonitoringItemRepository monitoringItemRepository,
			BrandHashtagTagRepository hashtagTagRepository,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.brandReadRepository = brandReadRepository;
		this.postCampaignRepository = postCampaignRepository;
		this.directPostRepository = directPostRepository;
		this.trackingItemAssembler = trackingItemAssembler;
		this.monitoringItemRepository = monitoringItemRepository;
		this.hashtagTagRepository = hashtagTagRepository;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}
```

import를 추가한다.

old_string:
```java
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
```
new_string:
```java
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
```

old_string:
```java
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
```
new_string:
```java
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
```

old_string:
```java
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
```
new_string:
```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
```

- [ ] **Step 5: 격리 필터 3분기로 교체**

old_string:
```java
		// 노출 필터(등록자 전용 노출 요구사항, 08-19) — direct-only(tag_detected_at IS NULL)는 등록한
		// 유저에게만 보인다. 원장 조회는 direct 등록 행이 하나도 없으면 생략한다(불필요한 조회 방지 —
		// exposeAdDisclosure 토글과 같은 관용구).
		boolean hasDirectRegistration = allPosts.stream().anyMatch(p -> p.directRegisteredAt() != null);
		Set<String> ownedShortCodes = hasDirectRegistration ? directPostRepository.shortCodesByUser(userId)
				: Set.of();
		List<BrandTaggedPostRow> posts = filterVisibleToUser(allPosts, ownedShortCodes);
		if (posts.isEmpty()) {
			return List.of();
		}
```
new_string:
```java
		// 노출 필터(등록자 전용 노출 요구사항 08-19 + 해시태그 격리 2026-08-27 설계 §3) —
		// direct-only는 등록한 유저에게만, hashtag-only는 "내 장부 태그 ∩ 게시물 매칭 태그 ≠ ∅"일
		// 때만 보인다. 두 보조 조회 모두 해당 성분의 행이 하나도 없으면 생략한다(불필요한 조회 방지 —
		// exposeAdDisclosure 토글과 같은 관용구).
		boolean hasDirectRegistration = allPosts.stream().anyMatch(p -> p.directRegisteredAt() != null);
		Set<String> ownedShortCodes = hasDirectRegistration ? directPostRepository.shortCodesByUser(userId)
				: Set.of();
		// 격리 대상은 tagged 성분이 없는 hashtag 행뿐이다 — tagged가 붙은 행은 브랜드 공유(기존 규칙).
		Set<String> isolatedCodes = allPosts.stream()
				.filter(p -> p.tagDetectedAt() == null && p.hashtagDetectedAt() != null)
				.map(BrandTaggedPostRow::shortCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> myTags = isolatedCodes.isEmpty() ? Set.of()
				: hashtagTagRepository.findByUserAndBrand(userId, account.id());
		Map<String, Set<String>> matchedTagsByCode = isolatedCodes.isEmpty() ? Map.of()
				: brandReadRepository.findMatchedTags(account.id(), isolatedCodes).stream()
						.collect(Collectors.groupingBy(MatchedTagRow::shortCode,
								Collectors.mapping(MatchedTagRow::tag, Collectors.toSet())));
		List<BrandTaggedPostRow> posts =
				filterVisibleToUser(allPosts, ownedShortCodes, myTags, matchedTagsByCode);
		if (posts.isEmpty()) {
			return List.of();
		}
```

old_string:
```java
	/**
	 * 노출 필터(요구사항 §2, 08-19) — 해시태그로 감지된 게시물({@code tag_detected_at IS NOT NULL})은
	 * 전원 노출, 직접 등록 전용 게시물({@code tag_detected_at IS NULL && direct_registered_at IS NOT
	 * NULL})은 등록한 유저에게만 노출한다. 등록자 원장은 {@code app.brand_direct_posts}
	 * ({@link BrandDirectPostRepository#shortCodesByUser}) — 2026-08-18 direct 통합 이후 신규 등록·
	 * 이관 전 레거시 등록을 모두 아우르는 유저 귀속 원장이다. monitoring DB({@code brand_tagged_post})와
	 * SQL 조인하지 않고 이 자바 코드에서 조합한다(시스템 경계 — was는 monitoring·app 스키마를 조인하지 않는다).
	 */
	private static List<BrandTaggedPostRow> filterVisibleToUser(List<BrandTaggedPostRow> posts,
			Set<String> ownedShortCodes) {
		return posts.stream()
				.filter(p -> p.tagDetectedAt() != null || ownedShortCodes.contains(p.shortCode()))
				.toList();
	}
```
new_string:
```java
	/**
	 * 노출 필터(요구사항 §2 08-19 + 해시태그 격리 2026-08-27 설계 §3) — 세 갈래다:
	 *
	 * <ol>
	 *   <li><b>tagged 성분이 있는 행</b>({@code tag_detected_at IS NOT NULL}) — 전원 노출. 브랜드에
	 *       연결된 사용자가 공유하는 자산이다(기존 규칙 불변).</li>
	 *   <li><b>내가 등록한 direct 행</b> — 등록자 원장 {@code app.brand_direct_posts}
	 *       ({@link BrandDirectPostRepository#shortCodesByUser})에 있으면 노출.</li>
	 *   <li><b>hashtag-only 행</b> — 조회자의 장부 태그({@code app.brand_hashtag_tags})와 게시물의
	 *       매칭 태그({@code brand_post_matched_tag})의 <b>교집합이 있을 때만</b> 노출.</li>
	 * </ol>
	 *
	 * <p><b>fail-open은 폐기됐다</b>(구 감지 목록은 "매칭 기록이 없거나 장부가 비면 전원 노출"이었다) —
	 * 태그 장부 시딩·백필(2026-08-27 설계 §4)로 모든 사용자에게 최소 자동 태그가 생겨 완화가 필요
	 * 없어졌고, 남겨 두면 남의 태그로 잡힌 게시물이 브랜드 전원에게 새어 나간다.
	 *
	 * <p>monitoring DB와 app DB는 물리적으로 분리돼 SQL 조인이 불가능하다 — 조합은 이 자바 코드에서
	 * 한다(시스템 경계).
	 */
	private static List<BrandTaggedPostRow> filterVisibleToUser(List<BrandTaggedPostRow> posts,
			Set<String> ownedShortCodes, Set<String> myTags, Map<String, Set<String>> matchedTagsByCode) {
		return posts.stream()
				.filter(p -> visibleToUser(p, ownedShortCodes, myTags, matchedTagsByCode))
				.toList();
	}

	private static boolean visibleToUser(BrandTaggedPostRow post, Set<String> ownedShortCodes,
			Set<String> myTags, Map<String, Set<String>> matchedTagsByCode) {
		if (post.tagDetectedAt() != null) {
			return true;
		}
		if (ownedShortCodes.contains(post.shortCode())) {
			return true;
		}
		if (post.hashtagDetectedAt() == null) {
			return false;   // 남이 등록한 direct-only
		}
		Set<String> matched = matchedTagsByCode.get(post.shortCode());
		return matched != null && !Collections.disjoint(matched, myTags);
	}
```

- [ ] **Step 6: `resolveSource` 3원화**

old_string:
```java
	/**
	 * source 파생(등록자 전용 노출 요구사항, 08-19) — tagged-only는 항상 "tagged". direct-only
	 * ({@code tag_detected_at IS NULL})는 항상 "direct"다: {@link #filterVisibleToUser}를 통과한
	 * 시점에 이미 이 유저가 등록자임이 보장된다. 겹침 행(둘 다 값이 있음, 전원 노출 대상)만 조회자
	 * 관점으로 갈린다 — 등록자에겐 "direct", 그 외 유저에겐 "tagged"(요구사항 §3).
	 */
	private static String resolveSource(BrandTaggedPostRow post, boolean registeredByUser) {
		if (post.directRegisteredAt() == null) {
			return SOURCE_TAGGED;
		}
		if (post.tagDetectedAt() == null) {
			return SOURCE_DIRECT;
		}
		return registeredByUser ? SOURCE_DIRECT : SOURCE_TAGGED;
	}
```
new_string:
```java
	/**
	 * source 파생(등록자 전용 노출 요구사항 08-19 + 3원화 2026-08-27 설계 §3) — 우선순위는
	 * <b>direct(등록자 관점) &gt; tagged &gt; hashtag</b>다.
	 *
	 * <ol>
	 *   <li>이 유저가 직접 등록한 행이면 "direct" — 겹침이어도 등록자 관점이 이긴다.</li>
	 *   <li>tagged 성분이 있으면 "tagged" — 남이 등록한 direct 성분은 이 유저에게 direct가 아니다.</li>
	 *   <li>남은 direct 성분(남이 등록)은 hashtag 성분이 함께 있을 때 "hashtag"다 — 이 행이 이
	 *       유저에게 보이는 이유가 그것이기 때문이다. hashtag 성분마저 없으면
	 *       {@link #filterVisibleToUser}가 이미 걸렀으므로 도달 불가지만, 안전값으로 "direct"를 둔다.</li>
	 *   <li>그 외(성분이 hashtag뿐)는 "hashtag".</li>
	 * </ol>
	 */
	private static String resolveSource(BrandTaggedPostRow post, boolean registeredByUser) {
		if (post.directRegisteredAt() != null && registeredByUser) {
			return SOURCE_DIRECT;
		}
		if (post.tagDetectedAt() != null) {
			return SOURCE_TAGGED;
		}
		if (post.directRegisteredAt() != null) {
			return post.hashtagDetectedAt() != null ? SOURCE_HASHTAG : SOURCE_DIRECT;
		}
		return SOURCE_HASHTAG;
	}
```

- [ ] **Step 7: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"
```
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java
git commit -m "feat(was): 브랜드 게시물 source 3원화와 해시태그 사용자 격리 - fail-open 폐기"
```

---

## Task 9: `/posts` 통합 목록에 hashtag 합류

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java:33-49`(javadoc), `:101-104`(source 화이트리스트), `:223-244`(counts)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java:26`(`@Schema`)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

- [ ] **Step 1: 구 계약을 고정하던 테스트 2건 제거**

`V1BrandPostsControllerTest.java`에서 아래 두 테스트를 통째로 지운다(해시태그가 통합 목록에 합류하는 것이 새 계약이라 이 둘은 정반대를 고정한다).

old_string:
```java
	/**
	 * 회귀 케이스(2026-08-12 별도 탭 결정) — 해시태그 발견분은 더 이상 §6-1 목록에 섞이지 않는다.
	 * repository가 tagged·hashtag 둘 다 갖고 있어도 posts 응답은 tagged만 보이고, source 화이트리스트·
	 * meta.counts에서도 hashtag 어휘가 완전히 빠져야 한다(예전엔 여기 있었다 — Task 11 되돌림).
	 */
	@Test
	void 게시물_목록_API는_해시태그_발견분을_더이상_병합하지_않는다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenHashtag(hashtagRow("HHH", "2026-08-05T01:00:00Z"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				.andExpect(jsonPath("$.meta.counts", Matchers.not(Matchers.hasKey("hashtag"))));

		then(brandReadRepository).should(never()).findHashtagPosts(anyLong(), any(), anyInt());
	}

	@Test
	void source_필터에_hashtag를_주면_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=hashtag").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}
```
new_string:
```java
	/**
	 * 해시태그 게시물은 통합 목록에 {@code source=hashtag}로 합류한다(2026-08-27 설계 §3, 08-12
	 * 별도 탭 결정 폐기). counts에 hashtag 키가 생기고 source 화이트리스트도 그 값을 받는다.
	 */
	@Test
	void 게시물_목록은_해시태그_게시물을_source_hashtag로_합류시킨다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"),
				hashtagOnlyRow("HHH", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenMyTagMatch("HHH");

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[?(@.shortcode=='HHH')].source")
						.value(Matchers.contains("hashtag")))
				.andExpect(jsonPath("$.meta.counts.hashtag").value(1))
				.andExpect(jsonPath("$.meta.counts.tagged").value(1));
	}

	@Test
	void source_필터는_hashtag만_남긴다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"),
				hashtagOnlyRow("HHH", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenMyTagMatch("HHH");

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=hashtag").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("HHH"))
				// counts는 필터 전 전량 기준이라 흔들리지 않는다.
				.andExpect(jsonPath("$.meta.counts.tagged").value(1));
	}

	/** 내 장부 태그와 겹치지 않는 해시태그 게시물은 목록에도 counts에도 없다(격리, fail-open 폐기). */
	@Test
	void 내_태그와_겹치지_않는_해시태그_게시물은_목록에_없다() throws Exception {
		givenTagged(hashtagOnlyRow("HHH", "2026-08-05T01:00:00Z"));
		given(hashtagTagRepository.findByUserAndBrand(7L, 100L)).willReturn(Set.of("내태그"));
		given(brandReadRepository.findMatchedTags(eq(100L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("HHH", "남의태그")));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.counts.hashtag").value(0));
	}
```

- [ ] **Step 2: 픽스처 헬퍼 2개 추가**

같은 파일의 `taggedRow` 헬퍼 바로 뒤에 삽입한다.

old_string:
```java
	/** direct 등록 행 — direct_registered_at만 채워지고 tag_detected_at은 null(direct-only, source=direct). */
```
new_string:
```java
	/** hashtag-only 행 — hashtag_detected_at만 채워진다(source=hashtag, 사용자 격리 대상). */
	private static BrandTaggedPostRow hashtagOnlyRow(String code, String takenAt) {
		OffsetDateTime firstSeenAt = OffsetDateTime.parse("2026-08-06T02:00:00Z");
		return new BrandTaggedPostRow(code, "hashtag_influencer", "9002", OffsetDateTime.parse(takenAt),
				firstSeenAt, 0L, firstSeenAt, null, null, null, firstSeenAt);
	}

	/** 조회자(7L)의 장부 태그와 게시물 매칭 태그가 겹치도록 스텁 — 해시태그 격리 통과 조건. */
	private void givenMyTagMatch(String... shortCodes) {
		given(hashtagTagRepository.findByUserAndBrand(7L, 100L)).willReturn(Set.of("끌리메"));
		given(brandReadRepository.findMatchedTags(eq(100L), any())).willReturn(
				java.util.Arrays.stream(shortCodes)
						.map(code -> new BrandReadRepository.MatchedTagRow(code, "끌리메"))
						.toList());
	}

	/** direct 등록 행 — direct_registered_at만 채워지고 tag_detected_at은 null(direct-only, source=direct). */
```

- [ ] **Step 3: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"
```
Expected: FAIL — `source_필터는_hashtag만_남긴다`가 400(VALIDATION_FAILED), `게시물_목록은...`이 `meta.counts.hashtag` 없음

- [ ] **Step 4: 컨트롤러 구현**

`V1BrandPostsController.java`:

old_string:
```java
		String sourceFilter = normalizeFilter(source, "source", BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT);
```
new_string:
```java
		String sourceFilter = normalizeFilter(source, "source", BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT, BrandPostAssembler.SOURCE_HASHTAG);
```

old_string:
```java
		counts.put(BrandPostAssembler.SOURCE_DIRECT, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_DIRECT));
```
new_string:
```java
		counts.put(BrandPostAssembler.SOURCE_DIRECT, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_DIRECT));
		// 해시태그 합류(2026-08-27 설계 §3) — 08-12 별도 탭 결정으로 빠졌던 키가 통합과 함께 돌아왔다.
		counts.put(BrandPostAssembler.SOURCE_HASHTAG, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_HASHTAG));
```

클래스 javadoc의 "병합하지 않는다" 문단을 새 계약으로 바꾼다.

old_string:
```java
 * <p>해시태그 발견 게시물은 §6-1 목록에 <b>병합하지 않는다</b>(2026-08-12 결정 — 별도 탭) — 스냅샷·
 * 댓글·팔로워 보강이 없는 별개 성격의 데이터라 같은 필터·정렬·counts 계약에 억지로 끼워 맞추면
 * null 필드가 늘어난다. {@link #hashtagPosts} 참조.
 */
```
new_string:
```java
 * <p>해시태그 게시물은 2026-08-27 직접 수집 전환으로 <b>이 목록에 {@code source=hashtag}로 합류</b>한다
 * (08-12 "별도 탭" 결정 폐기) — 이제 tagged·direct와 같은 풀에서 같은 보강·스냅샷·재수집을 받으므로
 * "null 필드가 늘어난다"는 분리 근거가 사라졌다. 단 hashtag-only 행은 <b>조회자의 장부 태그와
 * 겹칠 때만</b> 보인다({@code BrandPostAssembler.filterVisibleToUser}). 구 전용 API
 * ({@link #hashtagPosts})는 전환 기간 동안 같은 풀에서 구 셰이프로 서빙된다.
 */
```

`BrandPostResponse.java`:

old_string:
```java
		@Schema(allowableValues = {"tagged", "direct"}) String source,
```
new_string:
```java
		@Schema(allowableValues = {"tagged", "direct", "hashtag"}) String source,
```

- [ ] **Step 5: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"
```
Expected: 위 3건은 PASS. 해시태그 전용 API 섹션(`해시태그_발견_게시물_*`)은 아직 구 조립을 보고 있어 실패할 수 있다 — Task 10에서 정리한다.

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java
git commit -m "feat(was): /posts 통합 목록에 source=hashtag 합류와 counts.hashtag 추가"
```

---

## Task 10: 구 `/hashtag-posts` 엔드포인트 리라우팅

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java` (전면 재작성)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java:128-140`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssemblerTest.java` (전면 재작성)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java` (구 섹션 정리)

> **계약:** 응답 셰이프(`BrandHashtagPostResponse`)는 유지하되 데이터 산지가 통합 풀로 바뀐다. **`source=hashtag`인 행만** 내려간다 — tagged·direct 성분이 붙은 겹침 행은 이제 `/posts` 본 목록에 제대로 실리므로 이 탭에서 빠지는 것이 맞다(구 규칙도 tagged 겹침은 제외였다). `brandPostId`는 항상 shortcode다: 해시태그 게시물이 이제 전부 성과 측정 풀 소속이기 때문이다. `likes`·`comments`는 최신 스냅샷 값이라 구 "발견 시점 관측값"보다 신선하다. FE 전환 확인 후 다음 릴리스에 이 엔드포인트를 제거한다.

- [ ] **Step 1: 실패 테스트 작성 — 파일 전체를 아래로 교체**

`was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssemblerTest.java`:

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 구 해시태그 전용 목록의 <b>리라우팅</b> 조립(2026-08-27 해시태그 직접 수집 설계 §3) — 응답 셰이프는
 * 그대로 두고 데이터 산지만 통합 풀로 옮겼다. 격리·창 판정은 {@link BrandPostAssembler}가 이미
 * 끝낸 결과를 그대로 쓰므로, 여기서 고정하는 것은 <b>셰이프 매핑과 source=hashtag 부분집합 선택</b>뿐이다.
 */
class BrandHashtagPostAssemblerTest {

	private static BrandPostResponse post(String code, String source, Long likes, Long comments) {
		TrackingItemResponse.SnapshotResponse latest = likes == null ? null
				: new TrackingItemResponse.SnapshotResponse("2026-08-07", 500L, likes, false, comments,
						null, null, false, null);
		return new BrandPostResponse(code, "100", source,
				"https://www.instagram.com/reel/" + code + "/", code, "reels",
				"2026-08-06T10:00:00+09:00", "캡션 원문", "https://cdn/thumb.jpg", null, null,
				"https://www.instagram.com/hashtag_influencer/", "hashtag_influencer", "해시태그 인플루언서",
				"https://cdn/author.jpg", false, 1000L, "unknown", null, "tracking",
				"2026-08-06T11:00:00+09:00", null, latest, latest == null ? List.of() : List.of(latest),
				comments, false, 0L, List.of(), List.of(), "2026-08-06T11:00:00+09:00",
				"2026-08-08T03:00:00+09:00", null, List.of(), List.of(), false);
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(100L, "lizda_official", LocalDate.of(2026, 8, 8),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
				"https://cdn/pic.jpg", true, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}

	@Test
	void source_hashtag_행만_구_셰이프로_내려준다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("TAG1", "tagged", 10L, 2L), post("HHH", "hashtag", 20L, 3L)));
		given(repository.findMatchedTags(eq(100L), any()))
				.willReturn(List.of(new MatchedTagRow("HHH", "끌리메")));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8));

		assertThat(result).singleElement().satisfies(row -> {
			assertThat(row.shortcode()).isEqualTo("HHH");
			assertThat(row.postUrl()).isEqualTo("https://www.instagram.com/p/HHH/");
			assertThat(row.matchedTag()).isEqualTo("끌리메");
			assertThat(row.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
			assertThat(row.caption()).isEqualTo("캡션 원문");
			assertThat(row.contentType()).isEqualTo("reels");
			assertThat(row.thumbnailUrl()).isEqualTo("https://cdn/thumb.jpg");
			assertThat(row.authorUsername()).isEqualTo("hashtag_influencer");
			assertThat(row.authorFullName()).isEqualTo("해시태그 인플루언서");
			assertThat(row.authorProfilePicUrl()).isEqualTo("https://cdn/author.jpg");
			assertThat(row.authorProfileUrl()).isEqualTo("https://www.instagram.com/hashtag_influencer/");
			assertThat(row.likes()).isEqualTo(20L);
			assertThat(row.comments()).isEqualTo(3L);
			assertThat(row.sponsorship()).isEqualTo("unknown");
			assertThat(row.firstSeenAt()).isEqualTo("2026-08-06T11:00:00+09:00");
			// 해시태그 게시물이 이제 전부 성과 측정 풀 소속이라 배지는 항상 켜진다.
			assertThat(row.brandPostId()).isEqualTo("HHH");
		});
	}

	/** 조회자의 신청 기간(링크 창) 밖 게시물은 본 목록과 마찬가지로 빠진다 — 두 화면이 어긋나면 안 된다. */
	@Test
	void 링크_창_밖_게시물은_빠진다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("HHH", "hashtag", 20L, 3L)));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2026, 8, 7));   // 창 시작이 게시물 업로드일(08-06)보다 뒤

		assertThat(result).isEmpty();
	}

	/** 스냅샷이 아직 없으면 지표는 null이다(구 셰이프도 nullable) — 조회 자체는 성공해야 한다. */
	@Test
	void 스냅샷이_없으면_지표는_null이다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("HHH", "hashtag", null, null)));
		given(repository.findMatchedTags(eq(100L), any())).willReturn(List.of());

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);
		var result = assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8));

		assertThat(result).singleElement().satisfies(row -> {
			assertThat(row.likes()).isNull();
			assertThat(row.comments()).isNull();
			assertThat(row.matchedTag()).isNull();   // 매칭 기록이 없으면 배지 문구도 없다
		});
	}

	@Test
	void 해시태그_행이_없으면_빈_목록이고_매칭_태그를_조회하지_않는다() {
		var brandPostAssembler = mock(BrandPostAssembler.class);
		var repository = mock(BrandReadRepository.class);
		given(brandPostAssembler.assembleBrandPosts(eq(7L), any(), eq(false),
				eq(BrandPostAssembler.BrandPostScope.ENRICHED_ONLY), eq(false), eq(BrandAccountType.OWN)))
				.willReturn(List.of(post("TAG1", "tagged", 10L, 2L)));

		var assembler = new BrandHashtagPostAssembler(brandPostAssembler, repository);

		assertThat(assembler.assembleForBrand(7L, account(), BrandAccountType.OWN,
				LocalDate.of(2025, 8, 8))).isEmpty();
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findMatchedTags(org.mockito.ArgumentMatchers.anyLong(), any());
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandHashtagPostAssemblerTest"
```
Expected: 컴파일 실패 — `constructor BrandHashtagPostAssembler ... cannot be applied to given types`

- [ ] **Step 3: 조립기 전면 재작성**

`was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java` 파일 전체를 아래로 교체한다.

```java
package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 구 해시태그 전용 목록의 <b>리라우팅</b> 조립(2026-08-27 해시태그 직접 수집 설계 §3) —
 * {@code GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts}의 응답 셰이프
 * ({@link BrandHashtagPostResponse})는 그대로 두고, 데이터 산지만 구 감지 테이블
 * ({@code brand_hashtag_post})에서 <b>통합 풀</b>로 옮긴다. FE가 새 통합 목록으로 전환하기 전에도
 * 화면이 낡지 않게 하는 전환기 장치이고, <b>다음 릴리스에 이 클래스와 엔드포인트를 함께 제거</b>한다.
 *
 * <p>구현은 {@link BrandPostAssembler#assembleBrandPosts} 결과의 {@code source=hashtag} 부분집합을
 * 구 셰이프로 옮기는 것뿐이다 — 사용자 격리·정산 게이트·정렬을 사본으로 다시 구현하지 않으므로
 * 본 목록과 이 탭이 갈릴 수 없다(구 구조는 그 판정을 각자 갖고 있어 실제로 갈렸다).
 *
 * <p><b>구 규칙과의 차이(의도됨)</b>:
 * <ul>
 *   <li>tagged·direct 성분이 붙은 겹침 행은 이 탭에서 빠진다 — 그런 행은 이제 본 목록에 제대로
 *       실린다(구 규칙도 tagged 겹침은 제외였다).</li>
 *   <li>{@code brandPostId}는 항상 shortcode다 — 해시태그 게시물이 전부 성과 측정 풀 소속이 됐다.</li>
 *   <li>{@code likes}·{@code comments}는 최신 스냅샷 값이다(구 "발견 시점 관측값"보다 신선하다).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandHashtagPostAssembler {

	/**
	 * 서빙 상한 — 본 목록의 {@code POST_LIMIT}과 같은 값. 편입 상한(브랜드당 1,000)이 이미 모수를
	 * 제한하지만, 폭주 방어 상한은 표시 표면마다 두는 것이 이 저장소의 관용구다.
	 */
	static final int HASHTAG_POST_LIMIT = 2000;

	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";

	private final BrandPostAssembler brandPostAssembler;
	private final BrandReadRepository brandReadRepository;

	public BrandHashtagPostAssembler(BrandPostAssembler brandPostAssembler,
			BrandReadRepository brandReadRepository) {
		this.brandPostAssembler = brandPostAssembler;
		this.brandReadRepository = brandReadRepository;
	}

	/**
	 * 브랜드 1계정의 해시태그 게시물(구 셰이프) — 최신순, 본 목록과 <b>같은</b> 격리·정산·창 판정을
	 * 거친 결과다.
	 *
	 * @param windowStart 조회자의 링크 표시 창 하한(본 목록과 같은 컷) — 두 화면의 모수가 어긋나면
	 *                    "탭에는 있는데 목록에는 없는" 게시물이 생긴다.
	 */
	public List<BrandHashtagPostResponse> assembleForBrand(long userId, BrandAccountRow account,
			String viewerAccountType, LocalDate windowStart) {
		List<BrandPostResponse> hashtagPosts = brandPostAssembler
				.assembleBrandPosts(userId, account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY,
						false, viewerAccountType)
				.stream()
				.filter(p -> BrandPostAssembler.SOURCE_HASHTAG.equals(p.source()))
				.filter(p -> withinWindow(p, windowStart))
				.limit(HASHTAG_POST_LIMIT)
				.toList();
		if (hashtagPosts.isEmpty()) {
			return List.of();
		}
		Set<String> codes = hashtagPosts.stream().map(BrandPostResponse::shortcode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		// 매칭 태그가 여럿이면 "#태그로 발견" 배지에 하나만 실린다 — 구 matched_tag(단일 컬럼)와 같은
		// 계약이라 첫 값을 쓴다. 이 필드는 엔드포인트와 함께 다음 릴리스에 사라진다.
		Map<String, String> matchedTagByCode = brandReadRepository.findMatchedTags(account.id(), codes).stream()
				.collect(Collectors.toMap(MatchedTagRow::shortCode, MatchedTagRow::tag, (a, b) -> a));
		return hashtagPosts.stream()
				.map(p -> toResponse(p, matchedTagByCode.get(p.shortcode())))
				.toList();
	}

	/** 업로드일 기준 창 판정 — 본 목록의 {@code withinUploadWindow}와 같은 규칙(업로드일 미상은 제외). */
	private static boolean withinWindow(BrandPostResponse post, LocalDate windowStart) {
		LocalDate uploadedOn = BrandPostAssembler.uploadedOn(post);
		return uploadedOn != null && !uploadedOn.isBefore(windowStart);
	}

	/**
	 * 통합 풀 응답 → 구 슬림 셰이프. {@code postUrl}은 콘텐츠 타입과 무관하게 항상 {@code /p/}다
	 * (Instagram이 reels도 {@code /p/}를 {@code /reel/}로 리다이렉트한다 — 구 계약 유지).
	 */
	static BrandHashtagPostResponse toResponse(BrandPostResponse post, String matchedTag) {
		TrackingItemResponse.SnapshotResponse latest = post.latestSnapshot();
		return new BrandHashtagPostResponse(
				post.shortcode(),
				PROFILE_URL_PREFIX + "p/" + post.shortcode() + "/",
				matchedTag,
				post.takenAt(),
				post.caption(),
				post.contentType(),
				post.thumbnailUrl(),
				post.authorUsername(),
				post.authorFullName(),
				post.authorProfilePicUrl(),
				post.authorProfileUrl(),
				latest == null ? null : latest.likes(),
				latest == null ? null : latest.comments(),
				post.sponsorship(),
				post.createdAt(),
				// 해시태그 게시물은 이제 전부 성과 측정 풀 소속이다 — 배지는 항상 켜진다.
				post.shortcode());
	}
}
```

- [ ] **Step 4: 컨트롤러 배선 교체**

`V1BrandPostsController.java`:

old_string:
```java
	/**
	 * 해시태그 발견 게시물 전용 표면(스펙 §8, 별도 탭 결정 2026-08-12) — {@link #list}(tagged·direct)와
	 * 완전히 분리된 API다. 병합·필터·정렬·counts가 없다 — {@link BrandHashtagPostAssembler}가 최신순
	 * 전량(상한은 그쪽 정책)을 그대로 내려준다. 소유 검증은 목록과 같은 관용구(403·404).
	 */
	@GetMapping("/accounts/{accountId}/hashtag-posts")
	public ApiResponse<List<BrandHashtagPostResponse>> hashtagPosts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		requireOwnership(principal.getUserId(), brandId);
		findAccountOrThrow(brandId);
		return ApiResponse.ok(hashtagPostAssembler.assembleForBrand(principal.getUserId(), brandId));
	}
```
new_string:
```java
	/**
	 * 구 해시태그 전용 표면(스펙 §8) — <b>2026-08-27 직접 수집 전환 이후 리라우팅</b>이다: 응답
	 * 셰이프는 그대로 두고 데이터는 {@link #list}와 같은 통합 풀에서 온다
	 * ({@link BrandHashtagPostAssembler}). FE가 통합 목록으로 전환하기 전에도 화면이 낡지 않게 하는
	 * 전환기 장치이고, <b>다음 릴리스에 제거</b>한다. 소유 검증은 목록과 같은 관용구(403·404)이고,
	 * 서빙 창도 목록과 같은 링크 창을 쓴다(두 화면의 모수가 어긋나면 안 된다).
	 */
	@GetMapping("/accounts/{accountId}/hashtag-posts")
	public ApiResponse<List<BrandHashtagPostResponse>> hashtagPosts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		LocalDate windowStart = linkWindowStart(today(), link.collectionMonths());
		return ApiResponse.ok(hashtagPostAssembler.assembleForBrand(principal.getUserId(), account,
				link.accountType(), windowStart));
	}
```

- [ ] **Step 5: 컨트롤러 슬라이스 테스트의 구 섹션 정리**

`V1BrandPostsControllerTest.java`에서 아래 **4개 `@Test` 메서드를 통째로 삭제**한다(선행 javadoc 주석 포함). 전부 구 감지 산지(`findHashtagPosts`·`findBrandPoolStatus` 스텁)를 전제한다.

- `해시태그_발견_게시물_목록은_열거_필드를_그대로_내려준다`
- `해시태그_발견_게시물_협찬은_캡션_확정_키워드로만_판정한다`
- `남이_수집한_발견_게시물은_내게_미수집으로_보인다`
- `내가_수집한_발견_게시물은_brandPostId가_채워진다`

이어서 남은 3건의 스텁·단언을 새 산지로 맞춘다.

old_string:
```java
		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/hashtag-posts").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findHashtagPosts(anyLong(), any(), anyInt());
	}
```
new_string:
```java
		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/hashtag-posts").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findMatchedTags(anyLong(), any());
	}
```

그리고 삭제한 4건을 대신할 리라우팅 계약 테스트 1건을 `해시태그_발견_게시물이_없으면_빈_배열이다` 앞에 넣는다.

old_string:
```java
	@Test
	void 해시태그_발견_게시물이_없으면_빈_배열이다() throws Exception {
```
new_string:
```java
	/**
	 * 리라우팅(2026-08-27 설계 §3) — 구 엔드포인트가 통합 풀의 {@code source=hashtag} 행을 구
	 * 셰이프로 내려준다. 격리(내 태그 매칭)도 본 목록과 같은 판정을 그대로 탄다.
	 */
	@Test
	void 해시태그_목록은_통합_풀에서_구_셰이프로_서빙된다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"),
				hashtagOnlyRow("HHH", "2026-08-05T01:00:00Z"));
		givenMyTagMatch("HHH");

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("HHH"))
				.andExpect(jsonPath("$.data[0].postUrl").value("https://www.instagram.com/p/HHH/"))
				.andExpect(jsonPath("$.data[0].matchedTag").value("끌리메"))
				.andExpect(jsonPath("$.data[0].brandPostId").value("HHH"));
	}

	@Test
	void 해시태그_발견_게시물이_없으면_빈_배열이다() throws Exception {
```

마지막으로 쓰이지 않게 된 픽스처 헬퍼를 지운다.

old_string:
```java
	private void givenHashtag(BrandHashtagPostRow... rows) {
		given(brandReadRepository.findHashtagPosts(anyLong(), any(), anyInt())).willReturn(List.of(rows));
	}

```
new_string:
```java
```

old_string:
```java
	private static BrandHashtagPostRow hashtagRow(String code, String takenAt) {
		return hashtagRow(code, takenAt, "해시태그 캡션");
	}

	private static BrandHashtagPostRow hashtagRow(String code, String takenAt, String caption) {
		return new BrandHashtagPostRow(code, "#브랜드명", "hashtag_influencer", "해시태그 인플루언서",
				"https://cdn/hashtag-author.jpg", OffsetDateTime.parse(takenAt), caption, "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null);
	}

```
new_string:
```java
```

남은 미사용 import(`BrandHashtagPostRow`)를 지운다.

old_string:
```java
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
```
new_string:
```java
```

- [ ] **Step 6: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"
```
Expected: PASS. 남은 미사용 import·헬퍼 경고가 컴파일 오류로 나오면(`anyInt` 등) 그 import만 함께 지운다.

- [ ] **Step 7: was 모듈 전량 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssemblerTest.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java
git commit -m "feat(was): 구 hashtag-posts 엔드포인트를 통합 풀 리라우팅으로 전환"
```

---

## 완료 후

- [ ] **Step 1: 전체 테스트(PR 직전 1회)**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 마이그레이션 채번 검증**

```bash
ls monitoring/src/main/resources/db/migration/ | tail -5
date -u +%Y%m%d%H%M%S
```
Expected: 새로 만든 두 파일의 번호가 기존 최대보다 크고, 현재 UTC 시각(+1h 이내)을 넘지 않는다. 넘으면 CI `migration-guard`(미래 채번 차단)에 걸린다 — 채번을 다시 해 rename한다.

- [ ] **Step 3: 브랜치 push (PR은 사용자 승인 후에만 연다)**

```bash
git push -u origin HEAD
```

- [ ] **Step 4: 문서 아카이브(PR을 여는 커밋에 동봉)**

```bash
git mv docs/superpowers/plans/2026-08-27-hashtag-direct-collection.md \
       docs/superpowers/plans/archive/2026-08-27-hashtag-direct-collection.md
git mv docs/superpowers/specs/2026-08-27-hashtag-direct-collection-design.md \
       docs/superpowers/specs/archive/2026-08-27-hashtag-direct-collection-design.md
```
상태 헤더를 각각 `✅ 구현됨`으로 갱신하고, `grep -rn "2026-08-27-hashtag-direct-collection" docs/ --include=*.md`로 옛 경로 참조를 찾아 함께 고친다(아카이빙이 링크를 조용히 깨뜨린 사고 전력 있음). `DECISIONS.md` 맨 위에 이번 전환 결정을, 해당 `docs/tracks/` 트랙 문서에 상태를 갱신한다.

- [ ] **Step 5: 배포 후 확인 항목(운영 반영 시)**

1. 이관 결과 — 브랜드별 hashtag 성분 행 수가 상한을 넘지 않는지.
   ```sql
   SELECT brand_id, count(*) FROM brand_tagged_post WHERE hashtag_detected_at IS NOT NULL
   GROUP BY brand_id ORDER BY 2 DESC LIMIT 10;
   ```
2. 이관분 충전 진행 — 미보강 잔량이 매일 줄어드는지(스윕당 상한 300 × 브랜드 수).
   ```sql
   SELECT count(*) FROM brand_tagged_post WHERE hashtag_detected_at IS NOT NULL AND enriched_at IS NULL;
   ```
3. 첫 스윕 콜량 — 그라파나 브랜드 콜 패널에서 이관 직후 피크가 예산(전역 동시 콜 14) 안인지.
4. **FE 통지 필요**: `/posts`에 `source=hashtag` 합류 · `meta.counts.hashtag` 신설 · `source` 필터에 `hashtag` 허용 · 구 `/hashtag-posts`는 리라우팅 후 **다음 릴리스에 제거**(전환 요청).

---

## 자체 검토 — 스펙 대조표

| 스펙 항목 | 대응 태스크 |
|---|---|
| §0 기존 스윕·감지 구조 완전 폐기(LLM 판정 포함) | Task 3(서비스 재작성) · Task 4(판정기·빈 삭제) |
| §0 편입 게이트 없음 + 브랜드 본인 계정 규칙 제외 | Task 3(`eligible`) |
| §0 수집 기간 = collectionMonths(90일 고정 폐기) | Task 3(`BrandCollectService.collectionCutoff` 공용화) |
| §0 상한 브랜드당 1000(tagged 2000과 별도 카운터) | Task 3(`SweepState.budget`) · Task 4(`post-limit:1000`) |
| §0 FE 노출 = `/posts`에 source=hashtag 합류 | Task 8 · Task 9 |
| §0 기존 감지 데이터 이관 | Task 6 |
| §0 사용자 격리(내 태그 매칭만) | Task 8 |
| §0 구 `/hashtag-posts` 리라우팅 | Task 10 |
| §1 `hashtag_detected_at` nullable 컬럼 | Task 1 |
| §1 `brand_post_matched_tag` 신설(멱등 누적) | Task 1 |
| §1 `brand_hashtag` 유지 | (변경 없음 — Task 3이 `findTags`만 계속 읽는다) |
| §1 구 테이블 읽기·쓰기 중단, DROP은 다음 릴리스 | Task 3·4·7(호출 제거) + 전제·주의(DROP 금지 명시) |
| §2-1 열거: dedup 도달 시 중단 | Task 3(`alreadyHashtag` + `hashtagKnown` 종료 트리거) |
| §2-2 필터: collectionCutoff 사후 컷 + 본인 제외 | Task 3(`eligible`) |
| §2-3 편입: upsert·겹침 병기·매칭 태그·상한 | Task 1(리포지토리) · Task 3(서비스) |
| §2-4 보강: `BrandCollectService.enrich` 재사용 | Task 3(`collectPage`) |
| §2-5 재수집: sweepDirect 일반화 + 같은 나이 티어 | Task 2(모수 쿼리) · Task 5(서비스·잡) |
| §2 즉시 스윕 트리거 유지 + 전용 executor 유지 | (변경 없음 — `BrandRegistrationService.triggerHashtagSweep*`이 같은 `sweep(BrandRow)`를 부른다) |
| §2 LLM verdict 파이프라인 제거 | Task 4 |
| §3 source 3원화(direct > tagged > hashtag) | Task 8(`resolveSource`) |
| §3 `meta.counts.hashtag` · `source=hashtag` 필터 | Task 9 |
| §3 사용자 격리(교집합), fail-open 폐기 | Task 8(`visibleToUser`) |
| §3 서빙 창 = 링크 창(`linkWindowStart` 재사용) | (기존 `withinLinkWindow`가 direct만 면제 — hashtag는 자동 적용) · Task 10(구 엔드포인트도 같은 창) |
| §3 구 엔드포인트 리라우팅 | Task 10 |
| §4 태그 장부 갭 수정(전제 조건) | **계획 1**(별도 문서) |
| §5 이관: upsert·SELF 제외·1000 상한·매칭 태그 | Task 6 |
| §5 이관분 미보강 우선 보강 배치(스윕당 상한) | Task 2(정렬) · Task 5(상한) |
| §6 스로틀(스윕당 보강 상한) | Task 5 |
| §6 FE 계약 변경 통지 | 완료 후 Step 5-4 |
| §6 구 감지 테이블 DROP은 다음 릴리스 | 전제·주의 |
| §7 테스트: monitoring 기간 컷·1000 상한·본인 제외·겹침 병기·매칭 태그 누적·이관분 우선 보강 | Task 1·2·3·5 |
| §7 테스트: was resolveSource·격리·counts·리라우팅 등가성 | Task 8·9·10 |
| §7 테스트: 장부 갭 | **계획 1** |
| §7 테스트: SQL 이관(겹침·상한·SELF 제외) | Task 6 |

**태스크 수:** 10개(계획 2) + 4개(계획 1). **실행 순서:** 계획 1 전량 → 계획 2 Task 1 → 10.
