# 유사 인플루언서 유사도 v2 — 1단계 구현 계획

> 상태: ✅ 구현/실행/반영됨
>
> 추기: 실행 후 #149 최종본과의 표면 충돌로 v2 이식 — 스펙 §7 참조. 계획 본문의 v1 파일 경로는 이식 전 기준.
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/influencers/{id}/similar`의 유사도를 혼합 점수(traits Jaccard 0.6 + 카테고리 믹스 히스토그램 교집합 0.4)로 바꾸고, 컷 0.30 미달 제외·최대 10명·matchPct 필드 제거를 적용한다.

**Architecture:** was 단독 변경(analytics 무접촉). `V1InfluencerReportRepository.findSimilar`의 SQL 한 곳에 점수식·컷·LIMIT을 넣고, 응답 DTO `SimilarInfluencer`에서 matchPct를 제거한다. 점수는 내부 정렬용으로만 쓰고 응답에 싣지 않는다. 근거 스펙: `docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md`(2단계 어휘 통제는 보류 — 이 계획은 1단계만).

**Tech Stack:** Java 21 · Spring Boot 4.1 · JdbcClient · Testcontainers 2.x(`org.testcontainers.postgresql.PostgreSQLContainer`) · PostgreSQL 17

**컷 0.30 실측 근거(2026-07-28 운영 dry-run, 샘플 30계정 × 동일 카테고리 전 후보):**
후보쌍 점수 p05=0.240 / p50=0.400 / p95=0.486, 기준 계정별 10위 점수 최솟값=0.400.
→ 컷 0.30은 정상 패널(10명)을 전혀 줄이지 않으면서, 믹스 교집합 0.75 미만 + 태그 무겹침인
후보만 얇은 풀에서 걸러낸다. '미분류' 피어 그룹(카테고리 믹스 없음)은 믹스 성분 0이라
태그 Jaccard ≥ 0.5가 아니면 빈 패널이 된다 — 의도된 동작(스펙 ⑤).

---

## 사전 조건

- [x] **브랜치 전략(07-28 변경)**: #149가 아직 열려 있고 계속 바뀔 수 있어, 머지를 기다리지 않고
  `origin/feat/influencer-report-redesign`에서 딴 스택 브랜치 `feat/similar-similarity-v2-phase1`에서
  작업한다(스펙·계획 문서 커밋 체리픽 포함 — 완료). PR은 `feat/influencer-report-redesign` 대상 —
  #149 머지 시 GitHub이 develop으로 자동 재타깃한다. #149가 바뀌면 이 브랜치를 리베이스.

---

### Task 1: 리포지토리 테스트 신설 (레드)

`findSimilar`는 지금까지 리포지토리 테스트가 없었다(컨트롤러 테스트만 mock으로 존재).
점수식·컷·LIMIT이 전부 SQL로 들어가므로 Testcontainers 통합 테스트를 새로 만든다.
`V1ContentReportRepositoryTest`와 같은 패턴: `IntegrationTest` 상속, `@BeforeEach`에서
테이블 DROP/CREATE(운영에선 뷰인 것도 테스트에선 같은 이름의 테이블로 시드).

**Files:**
- Create: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportRepositoryTest.java`

- [ ] **Step 1: 테스트 파일 작성**

주의: 이 시점에는 `SimilarRow`가 아직 6필드(overlapN·unionN 포함)라 **컴파일이 깨진다** —
그게 이 태스크의 레드다. 아래 코드는 Task 2에서 만들 4필드 시그니처를 전제한다.

```java
package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 유사도 v2 1단계 — 혼합 점수(Jaccard 0.6 + 믹스 교집합 0.4)·컷 0.30·LIMIT 10 검증.
 *  스펙: docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md §3. */
class V1InfluencerReportRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1InfluencerReportRepository repository;

	@BeforeEach
	void setUpTables() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_peer_stats");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_category_stats");
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
		// 운영에선 account_peer_stats·account_category_stats가 뷰지만(V39·V35),
		// findSimilar가 읽는 컬럼만 같은 이름의 테이블로 시드한다(콘텐츠 리포트 테스트와 같은 관용구).
		jdbcTemplate.execute("""
				CREATE TABLE account_peer_stats (
				    handle        text PRIMARY KEY,
				    peer_category text NOT NULL
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_analyses (
				    handle      text NOT NULL,
				    tagline     text,
				    traits      jsonb,
				    analyzed_at timestamptz NOT NULL DEFAULT now()
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_category_stats (
				    account_handle text NOT NULL,
				    main_group     text NOT NULL,
				    content_count  bigint NOT NULL
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text
				)""");
	}

	/** 계정 1명 시드 — 피어 그룹·프로필·최신 분석·카테고리 믹스를 한 번에. */
	private void seedAccount(String handle, String peerCategory, long followers,
			String traitsJson, String... mixPairs) {
		jdbcTemplate.update("INSERT INTO account_peer_stats VALUES (?, ?)", handle, peerCategory);
		jdbcTemplate.update("INSERT INTO accounts VALUES (?, ?, ?, ?)",
				handle, handle + "님", "https://cdn/" + handle + ".jpg", followers);
		jdbcTemplate.update(
				"INSERT INTO account_analyses (handle, tagline, traits) VALUES (?, ?, ?::jsonb)",
				handle, handle + " 태그라인", traitsJson);
		// mixPairs = [main_group, content_count, ...] 짝수 나열
		for (int i = 0; i < mixPairs.length; i += 2) {
			jdbcTemplate.update("INSERT INTO account_category_stats VALUES (?, ?, ?)",
					handle, mixPairs[i], Long.parseLong(mixPairs[i + 1]));
		}
	}

	@Test
	void 태그_겹침이_높을수록_상위() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\",\"성분 분석\",\"릴스 중심\"]",
				"메이크업", "10");
		// full: Jaccard 1.0, 믹스 1.0 → 0.6+0.4 = 1.0
		seedAccount("full", "메이크업", 20_000, "[\"정보형 리뷰\",\"성분 분석\",\"릴스 중심\"]",
				"메이크업", "10");
		// partial: 교집합 1/합집합 5 = 0.2 → 0.12+0.4 = 0.52
		seedAccount("partial", "메이크업", 20_000,
				"[\"정보형 리뷰\",\"감성 콘텐츠\",\"일상 브이로그\"]", "메이크업", "10");

		List<V1InfluencerReportRepository.SimilarRow> rows = repository.findSimilar("me");

		assertThat(rows).extracting(V1InfluencerReportRepository.SimilarRow::influencerId)
				.containsExactly("full", "partial");
	}

	@Test
	void 믹스만_같아도_컷은_넘고_태그_겹침보다는_아래() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		// tagged: Jaccard 1.0 → 1.0 / mixonly: Jaccard 0, 믹스 1.0 → 0.40 (컷 0.30 통과)
		seedAccount("tagged", "메이크업", 99_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		seedAccount("mixonly", "메이크업", 11_000, "[\"감성 콘텐츠\"]", "메이크업", "10");

		List<V1InfluencerReportRepository.SimilarRow> rows = repository.findSimilar("me");

		assertThat(rows).extracting(V1InfluencerReportRepository.SimilarRow::influencerId)
				.containsExactly("tagged", "mixonly");
	}

	@Test
	void 컷_미달이면_제외된다() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		// 태그 무겹침 + 믹스 교집합 0.5(반은 스킨케어) → 0.4×0.5 = 0.20 < 0.30
		seedAccount("faroff", "메이크업", 10_000, "[\"감성 콘텐츠\"]",
				"메이크업", "5", "스킨케어", "5");

		assertThat(repository.findSimilar("me")).isEmpty();
	}

	@Test
	void 최대_10명까지만_반환한다() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		for (int i = 0; i < 12; i++) {
			seedAccount("cand" + i, "메이크업", 10_000 + i, "[\"감성 콘텐츠\"]", "메이크업", "10");
		}

		assertThat(repository.findSimilar("me")).hasSize(10);
	}

	@Test
	void 동점이면_팔로워_근접_우선() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		// 둘 다 점수 0.40 동점 — 팔로워 차 5,000 < 50,000
		seedAccount("near", "메이크업", 15_000, "[\"감성 콘텐츠\"]", "메이크업", "10");
		seedAccount("far", "메이크업", 60_000, "[\"일상 브이로그\"]", "메이크업", "10");

		List<V1InfluencerReportRepository.SimilarRow> rows = repository.findSimilar("me");

		assertThat(rows).extracting(V1InfluencerReportRepository.SimilarRow::influencerId)
				.containsExactly("near", "far");
	}

	@Test
	void 믹스_결측_후보는_태그_성분만으로_판정() {
		seedAccount("me", "메이크업", 10_000, "[\"정보형 리뷰\"]", "메이크업", "10");
		// 카테고리 믹스 행 없음(varargs 생략) → 믹스 성분 0.
		// nomixTagged: 0.6×1.0 = 0.60 ≥ 컷 / nomixBare: 0 → 제외
		seedAccount("nomixTagged", "메이크업", 12_000, "[\"정보형 리뷰\"]");
		seedAccount("nomixBare", "메이크업", 12_000, "[\"감성 콘텐츠\"]");

		assertThat(repository.findSimilar("me"))
				.extracting(V1InfluencerReportRepository.SimilarRow::influencerId)
				.containsExactly("nomixTagged");
	}

	@Test
	void 기준_계정이_없으면_빈_목록() {
		assertThat(repository.findSimilar("ghost")).isEmpty();
	}
}
```

- [ ] **Step 2: 레드 확인**

```bash
./gradlew :was:compileTestJava 2>&1 | tail -5
```

Expected: **컴파일 실패** — `SimilarRow` 생성자·접근자 불일치(현재 6필드). 이것이 레드다.
(커밋은 Task 2에서 구현과 함께 — 컴파일 깨진 상태로 커밋하지 않는다.)

---

### Task 2: 점수식 SQL·DTO·컨트롤러 구현 (그린)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java` — `findSimilar` SQL 교체, `SimilarRow` 4필드로 축소
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/SimilarInfluencer.java` — matchPct 제거
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportController.java` — `similar()` 매핑 단순화
- Modify: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java` — matchPct 관련 단언 제거·테스트 통합

- [ ] **Step 1: `findSimilar` SQL·`SimilarRow` 교체**

`V1InfluencerReportRepository`의 기존 `findSimilar` 메서드와 `SimilarRow` record,
그리고 record 위 주석(`/** overlap·union 모두 중복 제거(DISTINCT) — matchPct ≤ 100 보장. */`)을
아래로 통째로 교체한다:

```java
	/** 유사 인플루언서 — 혼합 점수 = 0.6×traits Jaccard + 0.4×카테고리 믹스 히스토그램 교집합.
	 *  같은 피어 카테고리 내에서 컷 0.30 미달 제외, 점수 내림차순·팔로워 근접순 상위 10.
	 *  점수는 정렬·컷 전용이라 반환하지 않는다(화면 비노출 — 유사도 v2 스펙 ③).
	 *  가중치·컷은 SQL 상수 — 내부값이라 app_setting 채널 없음. 컷 0.30 근거는
	 *  2026-07-28 운영 dry-run(10위 점수 최솟값 0.400, 후보쌍 p05 0.240). */
	public List<SimilarRow> findSimilar(String handle) {
		return jdbcClient.sql("""
				WITH me AS (
				  SELECT p.peer_category, ac.followers, la.traits
				  FROM account_peer_stats p
				  JOIN accounts ac ON ac.handle = p.handle
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = p.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE p.handle = :h
				),
				shares AS (
				  SELECT account_handle, main_group,
				         content_count::numeric / sum(content_count)
				           OVER (PARTITION BY account_handle) AS share
				  FROM account_category_stats
				),
				scored AS (
				  SELECT c.handle, ac.display_name, ac.profile_image_url, ac.followers,
				         la.tagline, me.followers AS my_followers,
				         0.6 * COALESCE(
				           (SELECT count(DISTINCT t.value) FROM jsonb_array_elements_text(la.traits) t
				             WHERE t.value IN (SELECT value FROM jsonb_array_elements_text(me.traits)))::numeric
				           / NULLIF((SELECT count(DISTINCT value) FROM (
				               SELECT value FROM jsonb_array_elements_text(la.traits)
				               UNION ALL SELECT value FROM jsonb_array_elements_text(me.traits)) u), 0), 0)
				         + 0.4 * COALESCE(
				           (SELECT sum(LEAST(sa.share, sb.share))
				            FROM shares sa
				            JOIN shares sb ON sb.main_group = sa.main_group
				                          AND sb.account_handle = c.handle
				            WHERE sa.account_handle = :h), 0) AS score
				  FROM account_peer_stats c
				  JOIN me ON c.peer_category = me.peer_category
				  JOIN accounts ac ON ac.handle = c.handle
				  JOIN LATERAL (SELECT tagline, traits FROM account_analyses
				                WHERE handle = c.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE c.handle <> :h
				)
				SELECT s.handle AS influencer_id, s.display_name AS name,
				       COALESCE('/img/' || ip.object_path, s.profile_image_url) AS profile_image_url,
				       s.tagline
				FROM scored s
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = s.handle
				WHERE s.score >= 0.30
				ORDER BY s.score DESC, abs(s.followers - s.my_followers) ASC
				LIMIT 10
				""").param("h", handle).query(SimilarRow.class).list();
	}

	public record SimilarRow(String influencerId, String name, String profileImageUrl,
			String tagline) {
	}
```

- [ ] **Step 2: `SimilarInfluencer`에서 matchPct 제거**

파일 전체를 아래로 교체:

```java
package com.celfit.was.v1.influencer;

/** 유사 인플루언서 1행 — 혼합 점수(traits Jaccard 0.6 + 카테고리 믹스 0.4) 정렬(07-28 유사도 v2).
 *  점수·유사도 %는 화면 비노출 결정에 따라 응답에 싣지 않는다(구 matchPct 제거). */
public record SimilarInfluencer(String influencerId, String name, String profileImageUrl,
		String tagline) {
}
```

- [ ] **Step 3: 컨트롤러 `similar()` 매핑 단순화**

`V1InfluencerReportController`의 `similar` 메서드와 그 위 주석을 아래로 교체
(matchPct 계산·overlapN/unionN 참조 제거):

```java
	/** 유사 인플루언서 — 혼합 점수(traits Jaccard 0.6 + 카테고리 믹스 0.4) 정렬,
	 *  컷 0.30·최대 10명(07-28 유사도 v2). 점수는 SQL 내부 정렬용 — 응답 비노출.
	 *  기준 계정이 없거나(피어/카피 미존재) 후보가 없으면 빈 목록 — aiReport의 404와 달리
	 *  이 표면은 200+[](패널 데이터라 부재=빈 패널, findSimilar 참조). */
	@GetMapping("/v1/influencers/{influencerId}/similar")
	public ApiResponse<List<SimilarInfluencer>> similar(@PathVariable String influencerId) {
		return ApiResponse.ok(repository.findSimilar(influencerId).stream()
				.map(r -> new SimilarInfluencer(r.influencerId(), r.name(), r.profileImageUrl(),
						r.tagline()))
				.toList());
	}
```

- [ ] **Step 4: 컨트롤러 테스트 갱신**

`V1InfluencerReportControllerTest`에서 `유사_인플루언서_목록`과
`유사_인플루언서_교집합_없으면_matchPct_null` 두 테스트를 아래 **한 개**로 교체한다
(`유사_인플루언서_없으면_200에_빈_배열`은 그대로 둔다):

```java
	@Test
	void 유사_인플루언서_목록() throws Exception {
		given(repository.findSimilar("haeun.log")).willReturn(List.of(
				new V1InfluencerReportRepository.SimilarRow("minji.beauty", "민지", "/img/p.jpg",
						"저자극 스킨케어 성분 리뷰")));
		mockMvc.perform(get("/v1/influencers/haeun.log/similar").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("minji.beauty"))
				.andExpect(jsonPath("$.data[0].tagline").value("저자극 스킨케어 성분 리뷰"))
				// 유사도 v2(07-28): 점수·% 화면 비노출 — 구 matchPct 필드 부재 확인.
				.andExpect(jsonPath("$.data[0].matchPct").doesNotExist());
	}
```

- [ ] **Step 5: 그린 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.*" 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — 리포지토리 테스트 7건 + 컨트롤러 테스트 전건 통과.
(Testcontainers 기동 실패 시: colima 환경이면 `DOCKER_HOST` 확인 — CLAUDE.md 함정 참조.)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): 유사 인플루언서 유사도 v2 1단계 — 혼합 점수·컷 0.30·최대 10명·matchPct 제거"
```

---

### Task 3: 전체 테스트·문서 갱신·PR

**Files:**
- Modify: `ARCHITECTURE.md` — §5 작업 트랙 표·§7 결정 기록에 유사도 v2 1단계 반영
- Modify: `docs/superpowers/plans/2026-07-28-similar-influencer-similarity-v2-phase1.md` — 상태 헤더를 `✅ 구현/실행/반영됨`으로 바꾸고 `plans/archive/`로 이동

- [ ] **Step 1: 전체 테스트**

```bash
./gradlew test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. 실패 시 원인 수정 전에는 다음 단계로 가지 않는다.

- [ ] **Step 2: ARCHITECTURE.md 갱신**

§5 작업 트랙 표에 유사도 v2 1단계 행(또는 기존 리포트 개편 행에 추가)을 넣고,
§7 결정 기록에 한 줄 추가:

```markdown
- **2026-07-28 유사 인플루언서 유사도 v2 1단계**: traits 자유 서술의 Jaccard가 난수화
  (고유값 5,847/28,387, 67%가 1회 등장)되어 혼합 점수(Jaccard 0.6 + 카테고리 믹스
  히스토그램 교집합 0.4)·컷 0.30(운영 dry-run 실측)·최대 10명으로 교체, 유사도 %는
  화면 비노출(matchPct 필드 삭제). 어휘 통제(2단계)는 보류 — 카테고리·태그라인 변경
  반영 분석 후 재결정. 스펙: docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md
```

- [ ] **Step 3: 계획 문서 아카이브 + 커밋**

```bash
git mv docs/superpowers/plans/2026-07-28-similar-influencer-similarity-v2-phase1.md docs/superpowers/plans/archive/
git add ARCHITECTURE.md
git commit -m "docs: ARCHITECTURE §5·§7 유사도 v2 1단계 기록 + 계획 아카이브"
```

(이동 후 파일 첫머리 상태 헤더를 `> 상태: ✅ 구현/실행/반영됨`으로 수정하는 것 포함.)

- [ ] **Step 4: push + PR 생성 (base = feat/influencer-report-redesign, #149 스택)**

```bash
git push -u origin feat/similar-similarity-v2-phase1
gh pr create --base feat/influencer-report-redesign --title "feat(was): 유사 인플루언서 유사도 v2 1단계 — 혼합 점수·컷·10명·% 제거" --body "$(cat <<'EOF'
## 요약
- `GET /v1/influencers/{id}/similar` 유사도를 혼합 점수로 교체: `0.6×traits Jaccard + 0.4×카테고리 믹스 히스토그램 교집합`(account_category_stats V35, was는 읽기만)
- 컷 0.30 미달 제외(운영 dry-run 실측: 10위 점수 최솟값 0.400 — 정상 패널 축소 없음), 최대 6→10명
- 유사도 % 화면 비노출 확정에 따라 응답 `matchPct` 필드 삭제(프론트 미착수라 소비자 없음)
- 어휘 통제(2단계)는 보류 — 스펙 `docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md` §5

## 테스트
- 신규 `V1InfluencerReportRepositoryTest`(Testcontainers): 점수 정렬·컷·LIMIT 10·팔로워 타이브레이크·믹스 결측·빈 목록 7건
- 컨트롤러 테스트 matchPct 부재 단언으로 갱신

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL 출력. #149 스택 PR — #149 머지 시 develop 대상으로 자동 재타깃.
배포 순서 제약 없음(was 단독) — 머지는 사용자 결정.
