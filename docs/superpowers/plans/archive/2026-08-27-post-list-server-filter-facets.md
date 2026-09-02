# 게시물 목록 서버 필터·패싯 + 인플루언서 집계 API 구현 계획

> 상태: ✅ 실행 완료 (2026-08-27)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 게시물 목록의 요청당 고정비(캡션 7.7MB 전송)를 제거하고, 서버 필터 5종·패싯
카운트·influencerCount·해시태그 count·인플루언서 집계 API를 추가한다.

**Architecture:** 협찬 마커 매치를 Java 상수에서 빌드한 Postgres 정규식으로 SQL에 내려 boolean만
받는다(판정 트리·상수는 Java 유지, 소급성 보존). 슬림 인덱스(`PostRef`)에 contentType·adVerdict·
author 필드를 실어 신규 필터·패싯을 전부 Java 인메모리로 계산한다. 인플루언서 집계는 같은 슬림
인덱스 + 최신 스냅샷 경량 프로젝션 위의 순수 함수로, celfit-front `brand-influencers.ts`를 1:1
복제한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Testcontainers(PostgreSQL 2.x —
`org.testcontainers.postgresql.PostgreSQLContainer`), `@WebMvcTest`
(`org.springframework.boot.webmvc.test.autoconfigure`).

**스펙:** [docs/superpowers/specs/2026-08-27-post-list-server-filter-facets-design.md](../../specs/2026-08-27-post-list-server-filter-facets-design.md)

## Global Constraints

- 테스트는 모듈 단위: `./gradlew :was:test --tests "..."`. 전체 `./gradlew test`는 PR 직전에만.
- 통합 테스트 전 셸에 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`이 필요할 수
  있으나 **이 머신은 Docker Desktop이 정본**(08-09 확인) — DOCKER_HOST 미설정이 정답. 대량 실패
  시 도커 데몬부터 확인.
- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(was):`/`test(was):`/`docs:`.
- was는 monitoring DB를 **읽기만** 하고 app 스키마와 SQL 조인하지 않는다(조합은 Java에서).
- 스키마 변경 없음 — monitoring DB 마이그레이션을 만들지 않는다.
- 기존 API 하위 호환: `meta.counts`(필터 전 전량·flat)·파라미터 생략(전량 모드) 동작 불변.
- FE 판정과의 일치가 완료 판정이다 — 계산 규칙이 요청서 문구와 FE 코드가 다르면 **FE 코드**
  (celfit-front `src/lib/monitoring/brand-influencers.ts`, `ad-disclosure.ts`)가 정본.

## File Structure

| 파일 | 책임 |
|---|---|
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipClassifier.java` (수정) | 마커 상수(불변) + Postgres 정규식 빌더 + `classify(Boolean, boolean)` 오버로드 |
| `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (수정) | `findBrandPostIndex` 슬림 확장, `findLatestMetricsForBrand` 신설(`findLatestViewsForBrand` 흡수), `findHashtagPostCodes` 신설 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java` (수정) | `PostRef` 확장, `indexForBrand` 개편(author 폴백), `adDisclosureExposed` 게이트 노출 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostWindows.java` (신설) | 창·기간 판정 순수 함수(컨트롤러 2곳 공용) |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (수정) | 필터 5종·facets·influencerCount·hashtag-posts/count |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java` (수정) | `countForBrand`(기존 필터 체인 재사용) |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandInfluencerAggregator.java` (신설) | FE `brand-influencers.ts` 1:1 복제 순수 함수(병합·집계·정렬·필터) |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandInfluencerResponse.java` (신설) | ② 응답 record(12필드) |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandInfluencersController.java` (신설) | ② 엔드포인트 — 소유권·창·배선·페이지 |

---

### Task 1: 협찬 마커 Postgres 정규식 빌더 + classify 오버로드

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipClassifier.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipClassifierTest.java` (기존 파일에 추가)

**Interfaces:**
- Produces: `public static String postgresMarkerRegex()` — `lower(caption)`에 `~`로 적용할 ARE
  정규식. `public static String classify(Boolean isPaidPartnership, boolean captionMarker)`.
  `static boolean containsSponsorshipMarker(String caption)` (private → package-private 승격,
  동치성 테스트용).

- [ ] **Step 1: 실패하는 단위 테스트 작성** — 기존 `BrandSponsorshipClassifierTest`에 추가:

```java
@Test
void 오버로드_classify는_캡션_판정_결과와_같은_트리를_탄다() {
	// (isPaidPartnership, captionMarker) 조합 6칸 전부
	assertThat(BrandSponsorshipClassifier.classify(Boolean.TRUE, false)).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(Boolean.TRUE, true)).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(null, true)).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(Boolean.FALSE, true)).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(Boolean.FALSE, false)).isEqualTo("organic");
	assertThat(BrandSponsorshipClassifier.classify(null, false)).isEqualTo("unknown");
}

@Test
void 캡션_classify는_마커_오버로드에_위임한다() {
	// 기존 caption 경로와 오버로드 경로가 같은 답을 내는지 — 대표 케이스만(전수 대조는 Task 2 코퍼스)
	assertThat(BrandSponsorshipClassifier.classify(null, "#광고 후기"))
			.isEqualTo(BrandSponsorshipClassifier.classify(null,
					BrandSponsorshipClassifier.containsSponsorshipMarker("#광고 후기")));
}

@Test
void 정규식_빌더는_비어있지_않은_ARE를_만든다() {
	String regex = BrandSponsorshipClassifier.postgresMarkerRegex();
	assertThat(regex).contains("#(?:").contains("reklam").contains("광고");
	// Java에서도 컴파일 가능한 부분집합만 쓰는지 스모크(ARE와 100% 동형은 아님 — 실검증은 Task 2)
	assertThat(regex).doesNotContain("(?<").doesNotContain("(?=");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifierTest"`
Expected: FAIL — `postgresMarkerRegex`·오버로드 미정의 컴파일 에러.

- [ ] **Step 3: 구현**

`BrandSponsorshipClassifier`에 추가(기존 상수·메서드는 손대지 않는다. `containsSponsorshipMarker`는
`private` → package-private로만 변경):

```java
/**
 * (isPaidPartnership, 캡션 마커 매치 여부) → 판정. SQL이 마커 매치를 대신 계산한 경로
 * (슬림 인덱스, 2026-08-27 P0)용 — 판정 트리는 caption 버전과 동일하다.
 */
public static String classify(Boolean isPaidPartnership, boolean captionMarker) {
	if (Boolean.TRUE.equals(isPaidPartnership)) {
		return SPONSORED;
	}
	if (captionMarker) {
		return SPONSORED;
	}
	return Boolean.FALSE.equals(isPaidPartnership) ? ORGANIC : UNKNOWN;
}

/** Java \s(ASCII 공백)와 같은 문자만 — PG [[:space:]]는 로케일에 따라 유니코드 공백까지 넓어진다. */
private static final String ARE_SPACE = "[ \t\n\f\r]";

/**
 * 마커 상수 → Postgres ARE 정규식(2026-08-27 P0) — {@code lower(caption) ~ :regex}로 쓴다.
 * Java 정규식과의 문법 차이(룩비하인드·룩어헤드 없음)는 소비형으로 등가 변환한다:
 * {@code (?<![\p{L}\p{N}])} → {@code (^|[^[:alnum:]])}, {@code l(?=\s)} → {@code l[공백]}.
 * 동치성은 SQL 골든 코퍼스 테스트(BrandSponsorshipSqlEquivalenceTest)가 봉인한다 —
 * 마커 상수를 고치면 그 테스트가 함께 검증한다(별도 갱신 불필요, 코퍼스에 사례만 추가).
 */
public static String postgresMarkerRegex() {
	List<String> alts = new ArrayList<>();
	for (String marker : CONFIRM_SUBSTRINGS) {
		alts.add(escapeAre(marker));
	}
	// 해시태그: # 뒤 태그 토큰 전체 일치 — 뒤가 토큰 문자면 다른 태그(#adventure ≠ #ad).
	// ARE는 최장 일치라 순서 무관하지만 결정성 위해 길이 내림차순 정렬.
	String tags = CONFIRM_HASHTAGS.stream()
			.sorted(Comparator.comparingInt(String::length).reversed()
					.thenComparing(Comparator.naturalOrder()))
			.map(BrandSponsorshipClassifier::escapeAre)
			.collect(Collectors.joining("|"));
	alts.add("#(?:" + tags + ")($|[^[:alnum:]_])");
	// reklam 단어 접두 — 앞이 문자·숫자면 단어 중간(WORD_PREFIX_MARKERS 등가).
	alts.add("(^|[^[:alnum:]])reklam");
	// 캡션 선두 접두 표기(LEADING_PREFIX_DISCLOSURE 등가) — 하이픈은 클래스 마지막에 둔다(ARE).
	alts.add("^" + ARE_SPACE + "*(?:광고|협찬|ad)(?:" + ARE_SPACE + "*[ㅣ|｜/–—:,.·~-]|"
			+ ARE_SPACE + "+l" + ARE_SPACE + ")");
	return alts.stream().map(a -> "(?:" + a + ")").collect(Collectors.joining("|"));
}

/** ARE 메타문자 이스케이프 — 마커 리터럴이 정규식으로 오작동하지 않게. */
private static String escapeAre(String literal) {
	return literal.replaceAll("([\\\\.^$*+?()\\[\\]{}|])", "\\\\$1");
}
```

필요 import: `java.util.ArrayList`, `java.util.Comparator`, `java.util.stream.Collectors`.
기존 `classify(Boolean, String caption)`는 내부를 위임으로 정리한다:

```java
public static String classify(Boolean isPaidPartnership, String caption) {
	return classify(isPaidPartnership, caption != null && containsSponsorshipMarker(caption));
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifierTest"`
Expected: PASS (기존 테스트 포함 전부).

- [ ] **Step 5: Commit** — `git add`(classifier + test), `git commit -m "feat(was): 협찬 마커 Postgres 정규식 빌더 + classify(boolean) 오버로드"`

---

### Task 2: SQL↔Java 마커 판정 동치성 골든 코퍼스 테스트

**Files:**
- Create: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipSqlEquivalenceTest.java`

**Interfaces:**
- Consumes: Task 1의 `postgresMarkerRegex()`·`containsSponsorshipMarker(String)`.

- [ ] **Step 1: 테스트 작성** — `IntegrationTest`(Testcontainers Postgres 베이스, `com.celfit.was.IntegrationTest`)를
상속해 스키마 없이 `SELECT lower(:caption) ~ :regex`만 실행한다:

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 협찬 마커 판정의 SQL(Postgres ARE)↔Java 동치성 봉인(2026-08-27 P0) — 슬림 인덱스가 캡션 대신
 * SQL 매치 boolean을 받으므로, 두 구현이 갈라지면 counts·필터가 화면과 어긋난다. 마커 상수를
 * 고칠 때는 이 코퍼스에 사례를 추가하면 두 구현이 함께 검증된다.
 */
class BrandSponsorshipSqlEquivalenceTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	/** 기존 classifier 주석의 함정 사례 전부 + 경계 사례. 기대값은 명시하지 않는다 — 두 구현의 일치만 단언. */
	private static final List<String> CORPUS = List.of(
			"#광고 협찬 후기", "오늘의 데일리룩 #adventure", "광고 아님 진짜 후기", "adorable puppy",
			"Bu bir reklamdır", "가장 reklam 같은", "웃reklam", "reklam", "_reklam var",
			"광고 ㅣ 신상 리뷰", "광고 l 신상", "광고 l", "AD | brand", "ad- daily", "협찬:후기",
			"#AD!", "#ad", "x#ad y", "##ad", "#prsample 후기", "#pr!", "#pring", "#Werbung.",
			"業配 내돈내산 아님", "広告です", "广告", "유료 광고 포함", "유료광고", "광고입니다",
			"이건광고입니다", "협찬받아 작성", "제품 제공 받았어요", "제공받아 솔직 후기",
			"#sponsored", "#sponsor", "#gifted post", "#paidpartnership", "#publicidad", "#anzeige",
			" 광고 ㅣ 선두가 NBSP", "  광고 — 대시 구분자", "광고~물결", "협찬 · 가운뎃점",
			"그냥 일상 글", "", " ", "커피 #맛집 #서울카페", "sponsored by nobody",
			"광고비 없이 씀", "This is an ad for fun");   // "an ad for" — 태그 아님·선두 아님 → 비매치 기대

	@Test
	void SQL_마커_매치는_Java_판정과_전_코퍼스에서_일치한다() {
		JdbcClient jdbc = JdbcClient.create(dataSource);
		String regex = BrandSponsorshipClassifier.postgresMarkerRegex();
		for (String caption : CORPUS) {
			boolean sql = jdbc.sql("SELECT lower(:caption) ~ :regex")
					.param("caption", caption).param("regex", regex)
					.query(Boolean.class).single();
			boolean java = BrandSponsorshipClassifier.containsSponsorshipMarker(caption);
			assertThat(sql).as("캡션: %s", caption).isEqualTo(java);
		}
	}
}
```

- [ ] **Step 2: 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandSponsorshipSqlEquivalenceTest"`
Expected: PASS. **불일치가 나오면 정규식(또는 코퍼스 항목의 이해)을 고친다 — Java 쪽 상수·판정은
건드리지 않는다**(FE와 일치하는 현행 판정이 정본). `IntegrationTest`가 `@SpringBootTest` 풀
컨텍스트라면 부팅이 무거울 수 있다 — 그 경우 `PostgreSQLContainer`를 직접 띄우는 독립 테스트로
바꿔도 된다(동치성 단언만 유지).

- [ ] **Step 3: Commit** — `git commit -m "test(was): 협찬 마커 SQL↔Java 동치성 골든 코퍼스"`

---

### Task 3: BrandReadRepository — 슬림 인덱스 확장 + 최신 스냅샷 지표 프로젝션

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java` (기존 파일에 추가)

**Interfaces:**
- Produces:

```java
public record BrandPostIndexRow(String shortCode, OffsetDateTime takenAt, OffsetDateTime tagDetectedAt,
		OffsetDateTime directRegisteredAt, String rawAuthorUsername, Boolean isPaidPartnership,
		boolean captionMarker, String contentType, String adVerdict, String authorUsername,
		String authorFullName, String authorProfilePicUrl, String authorImageObjectPath,
		Long authorFollowers) {}

public List<BrandPostIndexRow> findBrandPostIndex(long brandId, OffsetDateTime cutoff,
		boolean enrichedOnly, String markerRegex)   // 시그니처에 markerRegex 추가

public record LatestMetricsRow(String shortCode, String contentType, Long views, Long likes,
		boolean likesHidden, Long comments) {}

public List<LatestMetricsRow> findLatestMetricsForBrand(long brandId, OffsetDateTime cutoff,
		boolean enrichedOnly)   // findLatestViewsForBrand를 흡수·대체(LatestViewsRow 삭제)

public List<String> findHashtagPostCodes(long brandId, OffsetDateTime cutoff, int limit)
```

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `BrandReadRepositoryTest`의 기존 시드 헬퍼
(`seedBrand` 등) 관용구를 따라 추가. 핵심 단언:

```java
@Test
void 인덱스는_캡션_대신_마커_매치와_작성자_판정_컬럼을_준다() {
	long brandId = seedBrand("brand");
	// 시드: 게시물 2건 — (1) 캡션 "#광고 후기"·REELS·ad_verdict NOT_DISCLOSED·author_profile 연결,
	// (2) 캡션 "일상"·FEED·verdict null·author_ig_user_id null(author_username만 있음)
	// ... 기존 시드 헬퍼로 brand_tagged_post + brand_post_meta + author_profile 삽입 ...
	List<BrandReadRepository.BrandPostIndexRow> rows = repository.findBrandPostIndex(
			brandId, OffsetDateTime.now().minusDays(365),
			true, BrandSponsorshipClassifier.postgresMarkerRegex());
	BrandReadRepository.BrandPostIndexRow ad = byCode(rows, "CODE1");
	assertThat(ad.captionMarker()).isTrue();
	assertThat(ad.contentType()).isEqualTo("REELS");
	assertThat(ad.adVerdict()).isEqualTo("NOT_DISCLOSED");
	assertThat(ad.authorUsername()).isEqualTo("author1");
	assertThat(ad.authorFollowers()).isEqualTo(12500L);
	BrandReadRepository.BrandPostIndexRow plain = byCode(rows, "CODE2");
	assertThat(plain.captionMarker()).isFalse();
	assertThat(plain.authorUsername()).isNull();          // 프로필 미해결 — 폴백은 어셈블러 몫
	assertThat(plain.rawAuthorUsername()).isEqualTo("noprofile");
}

@Test
void 최신_스냅샷_지표는_게시물당_1행_최신값이다() {
	// 시드: CODE1에 captured_on 2일치 스냅샷(likes 10→20, likes_hidden false, comments 1→2, views 100→200)
	List<BrandReadRepository.LatestMetricsRow> rows = repository.findLatestMetricsForBrand(
			brandId, cutoff, true);
	assertThat(rows).hasSize(1);
	assertThat(rows.get(0).likes()).isEqualTo(20L);
	assertThat(rows.get(0).views()).isEqualTo(200L);
	assertThat(rows.get(0).comments()).isEqualTo(2L);
	assertThat(rows.get(0).likesHidden()).isFalse();
}

@Test
void 해시태그_발견_shortcode_슬림_조회는_RELEVANT_창_안만_최신순으로_준다() {
	// 시드: RELEVANT 2건(창 안)·IRRELEVANT 1건·창 밖 1건
	assertThat(repository.findHashtagPostCodes(brandId, cutoff, 2000))
			.containsExactly("NEWER", "OLDER");
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 에러(레코드 필드·메서드 부재) 예상.

- [ ] **Step 3: 구현**

`findBrandPostIndex` SQL 교체(주석의 실측 근거 문단은 새 구조에 맞게 갱신 — "캡션 7.7MB 전송이
고정비의 본체, 2026-08-27 perf119 실측"):

```sql
SELECT t.short_code, t.taken_at, t.tag_detected_at, t.direct_registered_at,
       t.author_username AS raw_author_username,
       m.is_paid_partnership,
       (m.caption IS NOT NULL AND lower(m.caption) ~ :markerRegex) AS caption_marker,
       m.content_type, m.ad_verdict,
       a.username AS author_username, a.full_name AS author_full_name,
       a.profile_pic_url AS author_profile_pic_url,
       a.image_object_path AS author_image_object_path,
       a.followers AS author_followers
FROM brand_tagged_post t
LEFT JOIN brand_post_meta m ON m.short_code = t.short_code
LEFT JOIN author_profile a ON a.ig_user_id = t.author_ig_user_id
WHERE t.brand_id = :brandId
  AND ( t.taken_at >= :cutoff OR t.direct_registered_at IS NOT NULL )
```
(+ 기존 `enrichedFilter` 문자열 결합 관용구 유지, `.param("markerRegex", markerRegex)` 추가.
`caption_marker`는 좌항 `IS NOT NULL` 가드 때문에 항상 non-null boolean이다 — meta 행이 없어도
false.)

`findLatestMetricsForBrand`: 기존 `findLatestViewsForBrand` SQL에서 SELECT 목록만
`s.short_code, s.content_type, s.views, s.likes, s.likes_hidden, s.comments`로 넓히고 메서드·record를
개명한다(`LatestViewsRow` 삭제, 호출부는 Task 4에서 함께 수정 — 이 시점엔 컴파일 에러가 나는 게
정상이므로 Task 3·4는 한 커밋으로 묶어도 된다. 분리하려면 이 Task에서 기존 메서드를 잠시
남겨둔다).

`findHashtagPostCodes`: `findHashtagPosts`와 같은 WHERE/ORDER/LIMIT, SELECT는 `short_code`만.

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"`

- [ ] **Step 5: Commit** — `git commit -m "feat(was): 브랜드 인덱스 슬림 확장 — 캡션 전송 제거(SQL 마커 매치)·author 조인·최신 지표 프로젝션"`

---

### Task 4: BrandPostAssembler — PostRef 확장 + indexForBrand 개편 + 창 유틸 분리

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostWindows.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (창 유틸 위임만)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`

**Interfaces:**
- Produces:

```java
public record PostRef(String shortcode, String source, String sponsorship, LocalDate uploadedOn,
		Long latestViews, String contentType /* "reels"|"feed" */, String adVerdict,
		String authorUsername, String authorFullName, String authorProfilePicUrl,
		Long authorFollowers, String takenAtKst /* 카드 takenAt과 동일 문자열, null 가능 */) {}

public boolean adDisclosureExposed(String viewerAccountType)
		// = exposeAdDisclosure && !BrandAccountType.COMPETITOR.equals(viewerAccountType)

// BrandPostWindows (final class, 순수 정적):
static LocalDate linkWindowStart(LocalDate today, int collectionMonths)
static boolean withinUploadWindow(LocalDate uploadedOn, LocalDate from, LocalDate to)
static boolean withinLinkWindow(BrandPostAssembler.PostRef ref, LocalDate windowStart)
```

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandPostAssemblerTest` 기존 관용구(mock 리포지토리)로:
  - 인덱스 행(author 조인 해결)이 PostRef의 contentType(reels 폴드)·adVerdict·author 3필드·
    `authorProfilePicUrl`(= `resolveImageUrl(imageObjectPath, profilePicUrl)` 적용값)·takenAtKst로
    옮겨지는지.
  - **author 폴백**: `authorUsername==null && rawAuthorUsername!=null`인 행만 모아
    `findAuthorsByUsername` 1회 배치 호출 → 해결되면 그 값, 미해결이면
    `authorUsername=rawAuthorUsername`·fullName/followers/profilePic null.
  - sponsorship이 `classify(isPaidPartnership, captionMarker)`로 계산되는지(캡션 없음).
  - `withViews=true`면 `findLatestMetricsForBrand`를 조회해 REELS만 latestViews 채움(피드 null) —
    기존 `findLatestViewsForBrand` 단언을 개명·확장.
  - 레거시 폴백 ref: contentType(null→"feed" 폴드), adVerdict null,
    author 3필드=(handle, displayName, profileImageUrl), followers=item.followers,
    takenAtKst=카드 takenAt 문자열.
  - `adDisclosureExposed`: expose=true·own → true / expose=true·competitor → false /
    expose=false → false.

- [ ] **Step 2: 실패 확인** — 컴파일 에러 예상.

- [ ] **Step 3: 구현**
  - `indexForBrand`: `findBrandPostIndex(account.id(), windowCutoff(), true,
    BrandSponsorshipClassifier.postgresMarkerRegex())`로 호출. 정규식은 상수라
    `private static final String MARKER_REGEX = BrandSponsorshipClassifier.postgresMarkerRegex();`로
    1회 빌드. ref 생성:

```java
for (BrandReadRepository.BrandPostIndexRow row : poolByCode.values()) {
	refs.add(new PostRef(row.shortCode(),
			resolveSource(row.tagDetectedAt(), row.directRegisteredAt(),
					ownedShortCodes.contains(row.shortCode())),
			BrandSponsorshipClassifier.classify(row.isPaidPartnership(), row.captionMarker()),
			KstTimestamps.toKstDate(row.takenAt()),
			viewsByCode.get(row.shortCode()),
			contentTypeOf(row.contentType()),
			row.adVerdict(),
			author.username(), author.fullName(),
			resolveImageUrl(author.imageObjectPath(), author.profilePicUrl()),
			author.followers(),
			KstTimestamps.toKstIso(row.takenAt())));
}
```
  (`author`는 조인 해결값 또는 폴백 배치 결과를 접은 로컬 헬퍼 record. 폴백 배치는 미해결
  username 집합이 비면 조회 자체를 생략 — `resolveAuthors` 2차 SQL 관용구와 동일.)
  - viewsByCode는 `findLatestMetricsForBrand` 결과로 구성(기존 REELS 폴드 로직 유지).
  - 레거시 ref 생성도 새 필드 채움.
  - 창 유틸 3함수를 `BrandPostWindows`로 이동, `V1BrandPostsController`의 기존 private 3메서드는
    삭제하고 호출부를 `BrandPostWindows.…`로 교체(동작 불변).

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest" --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"`
  (컨트롤러 테스트는 기존 계약 회귀 확인 — mock이 `findBrandPostIndex` 4-인자 시그니처로 바뀌므로
  스텁 수정 필요.)

- [ ] **Step 5: Commit** — `git commit -m "feat(was): PostRef 확장·indexForBrand 슬림 개편 — 신규 필터 판정값 탑재"`

---

### Task 5: 컨트롤러 ① — 필터 5종 + meta.facets + influencerCount

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

**Interfaces:**
- Consumes: Task 4의 `PostRef`(신규 필드)·`adDisclosureExposed`·`BrandPostWindows`.
- Produces: `list()` 신규 파라미터 `contentType, follower, keyword, adRisk, authorUsername` +
  `meta.facets`·`meta.influencerCount`. 패키지 공개 상수
  `static final Set<String> AD_RISK_VERDICTS = Set.of("NOT_DISCLOSED", "INSUFFICIENT")`,
  `record FollowerBand(long min, Long max)` + `static FollowerBand parseFollower(String raw)`
  (5토큰: `0-3k`→[0,3k) `3k-10k`→[3k,10k) `10k-30k`→[10k,30k) `30k-50k`→[30k,50k)
  `50k+`→[50k,∞), 그 외 400, null/`all` → null). ②(Task 8)가 재사용한다.

- [ ] **Step 1: 실패하는 WebMvc 테스트 작성** — 기존 테스트 클래스 관용구(실 어셈블러 + mock 리포지토리)로 시나리오 추가:
  - `contentType=reels`가 feed 게시물을 거르고 `meta.total`이 줄어드는지.
  - `follower=3k-10k` 경계(2999 제외·3000 포함·9999 포함·10000 제외), followers null 제외.
  - `keyword=뷰티` — username/fullName 부분 일치(대소문자 무시), 불일치 제외.
  - `authorUsername=someone` — 그 작성자만.
  - `adRisk=true` — sponsored+NOT_DISCLOSED 포함, sponsored+DISCLOSED 제외, organic+NOT_DISCLOSED
    제외. expose 프로퍼티 false(테스트 기본)면 전부 제외 + `facets.adRisk == 0`.
    expose=true 케이스는 `@WebMvcTest` properties에 `monitoring.brand.ad-disclosure.expose=true`를
    준 중첩 테스트 클래스로.
  - `facets`: 유형=reels 필터 상태에서 `facets.sponsorship.sponsored`가 "릴스 중 협찬 수",
    `facets.contentType.feed`가 "유형 필터 해제 상태의 피드 수"인지(축 해제 규칙).
  - `influencerCount`: 필터와 무관하게 창+기간 기준 distinct 작성자 수(작성자 미상 제외).
  - **하위 호환**: 파라미터 전부 생략 시 기존 `meta.counts` 6키·전량 응답 불변(기존 테스트 유지로 커버).

- [ ] **Step 2: 실패 확인** — 신규 파라미터 무시로 필터 단언 FAIL.

- [ ] **Step 3: 구현** — `list()`에 5파라미터 추가, 필터 술어를 조립:

```java
// 축 식별 — facets가 "그 축만 해제"를 계산할 때 쓴다. 축이 아닌 필터(기간·keyword·follower·
// authorUsername)는 항상 적용된다(FE 칩 정의 — 칩은 4축뿐이다).
private enum FacetAxis { SOURCE, SPONSORSHIP, CONTENT_TYPE, AD_RISK, NONE }

private static List<BrandPostAssembler.PostRef> applyFilters(List<BrandPostAssembler.PostRef> refs,
		PostFilters f, FacetAxis released) {
	return refs.stream()
			.filter(r -> released == FacetAxis.SOURCE || f.source() == null
					|| f.source().equals(r.source()))
			.filter(r -> released == FacetAxis.SPONSORSHIP || f.sponsorship() == null
					|| f.sponsorship().equals(r.sponsorship()))
			.filter(r -> released == FacetAxis.CONTENT_TYPE || f.contentType() == null
					|| f.contentType().equals(r.contentType()))
			.filter(r -> released == FacetAxis.AD_RISK || !f.adRisk() || isAdRisk(r, f.adGateOpen()))
			.filter(r -> f.follower() == null || matchesFollower(r.authorFollowers(), f.follower()))
			.filter(r -> f.keyword() == null || matchesKeyword(r, f.keyword()))
			.filter(r -> f.authorUsername() == null
					|| f.authorUsername().equalsIgnoreCase(r.authorUsername()))
			.filter(r -> BrandPostWindows.withinUploadWindow(r.uploadedOn(), f.from(), f.to()))
			.toList();
}

/** adRisk 판정 = FE hasAdDisclosureIssue 복제 — 협찬 선행 조건 + verdict 2종 + 노출 게이트. */
private static boolean isAdRisk(BrandPostAssembler.PostRef r, boolean adGateOpen) {
	return adGateOpen && BrandSponsorshipClassifier.SPONSORED.equals(r.sponsorship())
			&& r.adVerdict() != null && AD_RISK_VERDICTS.contains(r.adVerdict());
}

private static boolean matchesKeyword(BrandPostAssembler.PostRef r, String keywordLower) {
	return (r.authorUsername() != null
					&& r.authorUsername().toLowerCase(Locale.ROOT).contains(keywordLower))
			|| (r.authorFullName() != null
					&& r.authorFullName().toLowerCase(Locale.ROOT).contains(keywordLower));
}
```

`PostFilters`는 컨트롤러 private record(정규화 값 보관 — keyword는 trim·lower 선처리, 빈 문자열은
null). `filtered = applyFilters(all, f, FacetAxis.NONE)` 후 기존 정렬·페이지·하이드레이트 불변.
`meta()`에 facets·influencerCount 추가:

```java
Map<String, Object> facets = new LinkedHashMap<>();
facets.put("contentType", axisMap(applyFilters(all, f, FacetAxis.CONTENT_TYPE),
		BrandPostAssembler.PostRef::contentType, "reels", "feed"));
facets.put("sponsorship", axisMap(applyFilters(all, f, FacetAxis.SPONSORSHIP),
		BrandPostAssembler.PostRef::sponsorship, "sponsored", "organic", "unknown"));
facets.put("source", axisMap(applyFilters(all, f, FacetAxis.SOURCE),
		BrandPostAssembler.PostRef::source, "tagged", "direct"));
facets.put("adRisk", applyFilters(all, f, FacetAxis.AD_RISK).stream()
		.filter(r -> isAdRisk(r, f.adGateOpen())).count());
// axisMap: {"all": size, 값별 count} — LinkedHashMap, 전 키 0 선초기화(FE 키 부재 방어 관용구)

long influencerCount = all.stream()
		.filter(r -> BrandPostWindows.withinUploadWindow(r.uploadedOn(), f.from(), f.to()))
		.map(BrandPostAssembler.PostRef::authorUsername)
		.filter(Objects::nonNull).distinct().count();
```

`adGateOpen`은 `assembler.adDisclosureExposed(link.accountType())`로 요청당 1회 계산해
PostFilters에 담는다. 검증: `contentType`은 `normalizeFilter(raw, "contentType", "reels", "feed")`,
`adRisk`는 `"true"/"false"/null`만 허용(그 외 400), follower는 `parseFollower`.

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"`

- [ ] **Step 5: Commit** — `git commit -m "feat(was): 게시물 목록 서버 필터 5종 + meta.facets·influencerCount"`

---

### Task 6: P2 — GET /accounts/{accountId}/hashtag-posts/count

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`,
  `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssemblerTest.java`

**Interfaces:**
- Consumes: Task 3의 `findHashtagPostCodes`.
- Produces: `public long countForBrand(long userId, long brandId)`,
  `GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts/count` →
  `{"success":true,"data":{"count":N}}`.

- [ ] **Step 1: 실패하는 테스트 작성**
  - 어셈블러: tagged-only 겹침 행 제외·내 태그 교집합·fail-open(매칭 기록 없음)·원장 미시딩
    전원 노출 — `assembleForBrand`와 같은 시드에서 `countForBrand == assembleForBrand().size()`
    동치 단언이 핵심(판정 공유 회귀 방지).
  - 컨트롤러: 200 + `data.count`, 미소유 403(기존 관용구), 문자 accountId 404.

- [ ] **Step 2: 실패 확인.**

- [ ] **Step 3: 구현** — `assembleForBrand`의 필터 체인(풀 상태 제외 → 내 태그 교집합)을
shortcode 목록 위에서 재사용하도록 사적 헬퍼로 추출(`filterByMyTags`는 `BrandHashtagPostRow`를
받으므로 shortcode 기반 오버로드로 일반화)한 뒤:

```java
/** P2(2026-08-27) — 목록과 같은 판정(tagged 겹침 제외·내 태그 교집합)을 슬림 조회로 센다. */
public long countForBrand(long userId, long brandId) {
	List<String> codes = brandReadRepository.findHashtagPostCodes(brandId,
			BrandPostAssembler.windowCutoff(), HASHTAG_POST_LIMIT);
	if (codes.isEmpty()) {
		return 0;
	}
	Map<String, BrandPoolStatusRow> poolStatus = ...  // assembleForBrand과 동일 조회
	List<String> visible = codes.stream()
			.filter(code -> !isTaggedOnly(poolStatus.get(code)))
			.toList();
	return filterCodesByMyTags(userId, brandId, visible).size();
}
```

컨트롤러: `hashtagPosts`와 같은 소유권 관용구 후
`ApiResponse.ok(Map.of("count", hashtagPostAssembler.countForBrand(principal.getUserId(), brandId)))`.

- [ ] **Step 4: 통과 확인** — 두 테스트 클래스.
- [ ] **Step 5: Commit** — `git commit -m "feat(was): 해시태그 발견 게시물 count 전용 엔드포인트(P2)"`

---

### Task 7: ② 집계 순수 함수 — BrandInfluencerAggregator (FE 1:1)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandInfluencerAggregator.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandInfluencerResponse.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandInfluencerAggregatorTest.java`

**Interfaces:**
- Consumes: `PostRef`(Task 4), `LatestMetricsRow`(Task 3), `BrandPostIndex.legacyByCode`.
- Produces:

```java
public record BrandInfluencerResponse(String username, String fullName, String profilePicUrl,
		String profileUrl, Long followers, long postCount, long sponsoredCount, long views,
		long likes, long comments, long likesKnownCount, String latestPostAt) {}

// 집계 입력 1행(게시물 단위) — 컨트롤러(Task 8)가 ref+지표를 접어 만든다
public record InfluencerPost(String shortcode, String username, String fullName,
		String profilePicUrl, Long followers, String takenAtKst, boolean sponsored,
		Long views, Long likes, boolean likesHidden, Long comments) {}

public static List<InfluencerPost> dedupeByShortcode(List<InfluencerPost> posts)  // FE mergeBrandPosts
public static List<BrandInfluencerResponse> summarize(List<InfluencerPost> posts) // FE summarize…
public static List<BrandInfluencerResponse> sort(List<BrandInfluencerResponse> list, String sortKey)
		// sortKey ∈ posts|avg_views|views|likes|engagement|followers|recent — FE sortBrandInfluencers
public static boolean matchesKeyword(BrandInfluencerResponse r, String keywordLower)
public static boolean matchesFollower(BrandInfluencerResponse r,
		V1BrandPostsController.FollowerBand band)   // followers null → false
public static boolean matchesSponsorship(BrandInfluencerResponse r, String filter)
		// "sponsored" → sponsoredCount>0, "organic" → ==0
```

- [ ] **Step 1: 실패하는 단위 테스트 작성** — celfit-front `brand-influencers.ts`의 규칙별로
(각 규칙에 FE 함수명을 주석으로 남겨 대조 근거를 고정한다):
  - `dedupeByShortcode`: 같은 shortcode 2회 → 먼저 온 것만.
  - `summarize`: 같은 username 게시물 3건(1건 likesHidden) →
    postCount 3, likes = 비숨김 2건 합(숨김 likes 미포함), likesKnownCount 2,
    views = null(피드) 0 기여, comments 합, sponsoredCount = sponsored 건수.
  - followers/fullName/profilePicUrl: **가장 최근 takenAtKst 게시물의 값**, 단 followers는 최근
    게시물 값이 null이면 이전 값 유지(FE `isNewer && post.authorFollowers !== null` 등가).
  - latestPostAt = 최근 takenAtKst.
  - `sort("posts")`: postCount 동점 시 **likes 내림차순** 2차, username 오름차순 3차.
  - `sort("likes")`: likesKnownCount=0(likesUnavailable)은 값 무관 맨 뒤.
  - `sort("engagement")`: followers null/0·postCount 0·likesKnownCount 0 → 맨 뒤. 계산식
    `(likes+comments)/(followers*postCount)*100` 대소 비교.
  - `sort("avg_views")`: `Math.round((double) views / postCount)` 내림차순.
  - `sort("recent")`: takenAtKst 문자열 내림차순(ISO 사전순 = 시간순).
  - `matchesFollower`: followers null → false, 경계 [min, max).
  - `matchesSponsorship`, `matchesKeyword`(trim·lower·부분 일치, fullName null은 불일치 아님 —
    username만 검사).

- [ ] **Step 2: 실패 확인.**

- [ ] **Step 3: 구현** — 전부 정적 순수 함수. summarize 누적은 FE 코드 구조를 그대로 옮긴다
(LinkedHashMap<String, 가변 누적기> 후 최종 record 변환). 클래스 javadoc에 "celfit-front
`src/lib/monitoring/brand-influencers.ts` 1:1 이식(2026-08-27) — 규칙 변경은 FE와 동시에"를 명시.

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandInfluencerAggregatorTest"`
- [ ] **Step 5: Commit** — `git commit -m "feat(was): 인플루언서 집계 순수 함수 — FE brand-influencers.ts 1:1 이식"`

---

### Task 8: ② 컨트롤러 — GET /v1/brand-monitoring/influencers

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandInfluencersController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandInfluencersControllerTest.java`

**Interfaces:**
- Consumes: Task 4 `indexForBrand`, Task 3 `findLatestMetricsForBrand`, Task 7 전부,
  Task 5 `parseFollower`, `BrandPostWindows`, `BrandLinkRepository.findAllActiveByUser(userId)`
  (`BrandLinkRow{brandId, accountType, collectionMonths}`), `BrandReadRepository.findAccount`.

- [ ] **Step 1: 실패하는 WebMvc 테스트 작성** — `V1BrandPostsControllerTest` 관용구
(`@WebMvcTest(controllers = V1BrandInfluencersController.class, properties = {...})` + 실 어셈블러 Import):
  - `accountIds=5,6` — 두 계정 게시물이 병합·같은 username 합산 1행·같은 shortcode 중복 1회 집계.
  - 요청 id에 내 링크가 아닌 값 포함 → 403(FORBIDDEN 메시지 기존 관용구).
  - `accountIds` 누락·빈 값·비숫자 → 400.
  - 기간·keyword·follower·sponsorship 필터, sort=likes(모름 맨 뒤), offset/limit 페이지와
    `meta {total, offset, limit}` — 생략 시 전량·`limit: null`.
  - monitoring 계정 행이 없는 brandId는 건너뛴다(warn — `PerformanceContentAssembler.loadBrandPool`
    관용구).
  - 계정별 창: collectionMonths=1인 링크의 2개월 전 게시물은 모수 제외.

- [ ] **Step 2: 실패 확인.**

- [ ] **Step 3: 구현** — 컨트롤러 골격:

```java
@RestController
@RequestMapping("/v1/brand-monitoring")
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandInfluencersController {
	// 의존: BrandLinkRepository, BrandReadRepository, BrandPostAssembler, Clock

	@GetMapping("/influencers")
	public ApiResponse<List<BrandInfluencerResponse>> list(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam String accountIds,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) Integer offset,
			@RequestParam(required = false) Integer limit) {
		// 1) 파라미터 정규화 — accountIds 쉼표 파싱(비숫자 400), sort 7종 검증(기본 posts),
		//    follower는 V1BrandPostsController.parseFollower 재사용, 날짜는 parseDate 관용구,
		//    offset/limit은 게시물 목록 normalizePage와 같은 규칙(단 상한 없이 전량 허용이 기본)
		// 2) 소유권 — findAllActiveByUser를 brandId→링크 맵으로; 요청 id 중 맵에 없는 게 있으면 403
		// 3) 요청 순서대로 계정 처리: findAccount 없으면 warn+skip;
		//    index = assembler.indexForBrand(userId, account, false);
		//    windowStart = BrandPostWindows.linkWindowStart(today, link.collectionMonths());
		//    refs = index.refs() 중 withinLinkWindow && withinUploadWindow(from,to);
		//    metrics = brandReadRepository.findLatestMetricsForBrand(account.id(), windowCutoff(), true);
		//    refs → InfluencerPost 변환(아래) 후 전 계정 리스트 연결
		// 4) dedupeByShortcode → summarize → 필터(keyword/follower/sponsorship) → sort
		// 5) meta = {total: 필터 후 개수, offset, limit(전량이면 null)} + offset/limit 슬라이스
	}
}
```

ref → `InfluencerPost` 변환(계정 루프 안 — 순수 함수로 두고 단위 테스트):

```java
/** 피드 views null 폴드는 스냅샷 행의 content_type 기준 — snapshotOf 서빙 규칙 동형. */
static BrandInfluencerAggregator.InfluencerPost toInfluencerPost(BrandPostAssembler.PostRef ref,
		BrandReadRepository.LatestMetricsRow m, BrandPostResponse legacyCard) {
	Long views;
	Long likes;
	boolean likesHidden;
	Long comments;
	if (m != null) {
		boolean isReels = "REELS".equalsIgnoreCase(m.contentType());
		views = isReels ? m.views() : null;
		likes = m.likes();
		likesHidden = m.likesHidden();
		comments = m.comments();
	} else if (legacyCard != null && legacyCard.latestSnapshot() != null) {
		var s = legacyCard.latestSnapshot();
		views = s.views();
		likes = s.likes();
		likesHidden = Boolean.TRUE.equals(s.likesHidden());
		comments = s.comments();
	} else {
		views = null;
		likes = null;
		likesHidden = false;
		comments = null;
	}
	return new BrandInfluencerAggregator.InfluencerPost(ref.shortcode(), ref.authorUsername(),
			ref.authorFullName(), ref.authorProfilePicUrl(), ref.authorFollowers(),
			ref.takenAtKst(), BrandSponsorshipClassifier.SPONSORED.equals(ref.sponsorship()),
			views, likes, likesHidden, comments);
}
```

작성자 미상(`ref.authorUsername() == null`) 게시물은 집계에서 제외한다(FE도 authorUsername 키로
Map을 만들므로 null 키가 없다 — 어댑터가 빈 문자열로 접는지 구현 중 celfit-front
`api-adapters.ts`를 열어 확인하고, 빈 문자열이면 서버도 빈 문자열 제외로 동일하게).
profileUrl은 `username == null ? null : "https://www.instagram.com/" + username + "/"`.

- [ ] **Step 4: 통과 확인** — `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandInfluencersControllerTest"`
- [ ] **Step 5: Commit** — `git commit -m "feat(was): 인플루언서 집계 API 신설 — GET /v1/brand-monitoring/influencers"`

---

### Task 9: 성능 실측 + 문서 + 마무리

**Files:**
- Modify: `DECISIONS.md` (맨 위 신규 행), `docs/tracks/` 해당 트랙 파일(있으면 상태 갱신)
- Move: `docs/superpowers/plans/2026-08-27-post-list-server-filter-facets.md` → `plans/archive/`

- [ ] **Step 1: perf119 실측(before/after)** — 로컬 `hypenow-crawler-postgres-1`의 `perf119` DB
(계정 119 실사본, brand_id=119)로 구/신 인덱스 쿼리의 왕복 시간을 psql로 비교해 수치를 기록한다:

```bash
docker exec hypenow-crawler-postgres-1 psql -U crawler -d perf119 -c '\timing' \
  -c "SELECT count(*) FROM (SELECT t.short_code, m.caption FROM brand_tagged_post t LEFT JOIN brand_post_meta m ON m.short_code=t.short_code WHERE t.brand_id=119 AND (t.taken_at >= now()-interval '365 days' OR t.direct_registered_at IS NOT NULL) AND t.enriched_at IS NOT NULL) x"
```
(구 프로젝션은 `\o /dev/null` + 전체 컬럼 SELECT로 전송 포함 시간을, 신 프로젝션은 같은 방식으로
슬림 컬럼 + `lower(caption) ~ :regex`를 상수 정규식으로 측정. 결과를 PR 본문과 FE 회신 문서에 기록.)

- [ ] **Step 2: 전체 회귀** — `./gradlew :was:test` (PR 직전 1회). 실패 시 원인 수정.

- [ ] **Step 3: 문서** — DECISIONS.md 맨 위에 결정 1행(협찬 판정 SQL 마커 이전 — 소급성 유지 방식,
facets 신규 키, FE 코드 정본 원칙). plan 문서를 `plans/archive/`로 이동(스펙은 활성 유지 — 배포
후 완료 처리).

- [ ] **Step 4: PR** — 브랜치 `feature/post-list-server-filter-facets-04b967`에서 develop 대상
PR 생성(`gh pr create`), 본문에 스펙 링크·실측표·FE 확인 요청 3건(facets 키·follower 5토큰·
profileUrl 형식) 명시.

- [ ] **Step 5: Commit** — `git commit -m "docs: 결정 기록·plan 아카이브 — 게시물 목록 서버 필터·패싯"`

---

## Self-Review 결과

- 스펙 커버리지: A(Task 1–4), B(Task 5), C(Task 5), D(Task 7–8), E(Task 6), 성능·검증(Task 2·9) — 전 섹션 매핑 확인.
- 하위 호환(counts·전량 모드)은 Task 5 Step 1의 기존 테스트 유지로 커버.
- 타입 일관성: `PostRef`(Task 4 정의)·`FollowerBand`(Task 5 정의, Task 8 재사용)·
  `LatestMetricsRow`(Task 3 정의, Task 4·8 소비) 시그니처 상호 참조 일치 확인.
- 유의: Task 3–4는 `findLatestViewsForBrand` 개명 때문에 커밋 경계에서 컴파일이 깨질 수 있다 —
  본문에 명시했듯 한 커밋으로 묶는 선택 허용.
