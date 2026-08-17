# 캡션 기반 광고 표기 판정 구현 계획

> 상태: 🟢 활성 · 계획 확정, 실행 전

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 태그 게시물(`brand_tagged_post` + `brand_post_meta`)의 캡션이 광고 표기 규정(공정위예규
제499호 Ⅴ.6)을 지켰는지 게시물 단위로 자동 판정해, was API로 verdict·위반 사유·근거 문구·시딩 계정
여부를 브랜드 고객에게 노출한다.

**Architecture:** 규칙 선처리(Tier0 메타·Tier1 고신뢰 사전) → LLM은 문구 추출만(Tier2, 판단 아님) →
코드가 환각 차단·위치 판정·최종 verdict를 결정(Tier3, 전부 LLM 없이 단위 테스트). 판정은 브랜드
enrich 체인에 인라인으로 추가하고, 전용 소형 LLM 풀(동시 3~4)로 Hiker 보강 워커와 분리한다. 노출
게이트(`enriched_at`)를 게시자 보강 완료 직후로 당겨 댓글·광고 판정은 프론트 폴링으로 나중에
채운다. 시딩 계정 등록은 판정과 분리된 조회 시 조인(재판정 불필요).

**Tech Stack:** Java 21, Spring Boot 4.1, monitoring 모듈(Flyway/JdbcTemplate) + was 모듈(JdbcClient),
Gemini(`GeminiHttp` seam 재사용), JUnit 5 + AssertJ, Testcontainers(PostgreSQL).

정본 스펙: [docs/superpowers/specs/2026-08-17-brand-ad-disclosure-design.md](../specs/2026-08-17-brand-ad-disclosure-design.md) — 이 계획과 스펙이 어긋나면 스펙이 이긴다.

---

## 사전 지식 (구현 전 숙지)

- `BrandCollectService.enrich()`(`monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java:276-305`)가 지금 정산 마킹(`markEnriched`)을 `ensureAuthors` + `collectCommentsGated` 둘 다 끝난 뒤 `finally`로 찍는다. 이 계획의 Task 10에서 `markEnriched`를 `ensureAuthors` 직후로 당기고, 댓글 수집·광고 판정은 그 뒤 별도 격리 단계로 뺀다(스펙 §8).
- `PostInfo`(`monitoring/src/main/java/com/celfit/monitoring/hiker/PostInfo.java`)가 `shortCode`·`caption`·`contentType`·`isPaidPartnership`을 이미 들고 있다 — enrich()가 받는 `List<PostInfo> posts`를 그대로 판정 입력으로 쓴다(추가 SELECT로 캡션을 다시 읽지 않는다).
- `BrandPostMetaRepository.upsert()`가 `processPage` 단계에서 이미 최신 caption을 커밋한 뒤 enrich가 호출되므로(`BrandSnapshotWriter.savePost` → `postMeta.upsert`), enrich 시점엔 DB의 caption이 이미 최신이다.
- LLM 호출 seam은 `GeminiHttp`(함수형 인터페이스) + `GeminiHttpTransport`(실전송, 3회 지수 백오프) — 그대로 재사용한다. 판정기 작성 패턴은 `BrandMentionJudge`(`monitoring/src/main/java/com/celfit/monitoring/llm/BrandMentionJudge.java`)를 참고하되, 그 클래스는 fail-closed(UNCERTAIN)로 접지만 이번 판정은 **실패 시 예외를 던져 verdict NULL을 유지**해야 한다(스펙 §5 — 판정값 오염 방지). 혼동하지 말 것.
- jsonb 쓰기는 `?::jsonb` 캐스트 + 애플리케이션에서 만든 JSON 문자열 패턴(`AlarmEventRepository.insert` 참고) — ORM 매핑 없음.
- monitoring 명령 API(등록·태그·제외문자열)의 표준 모양은 `BrandController`(GET 조회 / PUT 전체교체 / POST 추가 / DELETE 단건 / DELETE 전체)이고, was 쪽 프록시는 `MonitoringCommandClient` + `V1BrandAccountService` + `V1BrandAccountsController`다. 시딩 계정 등록 API는 이 5종 REST 모양을 그대로 따른다(스펙 §6 — "구체 경로는 구현 계획에서 확정"의 답).
- 마이그레이션은 UTC 타임스탬프 채번(`V<YYYYMMDDHHMMSS>__설명.sql`). 이 계획은 예시로 `V20260817095819__ad_disclosure_verdict.sql`을 쓰지만, **Task 1 실행 시점에 `date -u +%Y%m%d%H%M%S`를 다시 실행해 최신 값으로 채번**한다(병행 세션과의 경합 방지 — CLAUDE.md).

---

### Task 1: 마이그레이션 — brand_post_meta 판정 컬럼 + brand_seeded_account 테이블

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260817095819__ad_disclosure_verdict.sql` (실제 파일명은 실행 시점 `date -u +%Y%m%d%H%M%S` 값으로)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandPostMetaRepositoryTest.java` (신규 — 이 컬럼을 쓰는 Task 7이 검증)

- [ ] **Step 1: UTC 타임스탬프 확인**

Run: `date -u +%Y%m%d%H%M%S`

이 값을 파일명에 쓴다. 아래 예시는 `20260817095819`을 가정한다 — 실제 값으로 바꿀 것.

- [ ] **Step 2: 마이그레이션 파일 작성**

```sql
-- 캡션 기반 광고 표기 판정(2026-08-17 스펙) — expand 단계, 전부 nullable ADD/신규 테이블.
-- brand_post_meta에 판정 6컬럼 + 시딩 계정 등록 테이블(brand_seeded_account) 신설.
-- 대상은 08-06 브랜드 전용 스키마만(기존 캠페인 테이블 무접촉, CLAUDE.md 시스템 경계).

ALTER TABLE brand_post_meta
    ADD COLUMN ad_verdict          text CHECK (ad_verdict IN
                                    ('DISCLOSED', 'NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')),
    ADD COLUMN ad_verdict_source   text CHECK (ad_verdict_source IN ('RULE', 'LLM')),
    ADD COLUMN ad_violations       jsonb,        -- 위반 코드 배열, 예: ["HIDDEN_PLACEMENT"]
    ADD COLUMN ad_evidence         jsonb,        -- 근거 문구 배열 [{phrase, category, offset}]
    ADD COLUMN ad_judged_at        timestamptz,
    ADD COLUMN judged_caption_hash text;         -- 판정 시점 caption의 MD5(애플리케이션 계산) — 캡션
                                                  -- 변경 재판정 트리거(스펙 §4). NULL = 미판정.

-- 브랜드가 등록한 시딩(협업) 인플루언서 계정(스펙 §6) — 판정 결과에는 저장하지 않는다.
-- 조회 시 (brand_id, author_username) 조인으로 계산해 목록을 나중에 등록·수정해도 재판정이 필요 없다.
CREATE TABLE brand_seeded_account (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    username   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, username)
);
```

- [ ] **Step 3: 마이그레이션 가드 확인**

Run: `./deploy/scripts/check-migration-safety.sh` (레포에 존재하면 — 없으면 스킵하고 CI가 대신 검사한다)

이 마이그레이션은 순수 ADD/CREATE라 `-- allow-destructive` 주석이 필요 없다(expand-contract 위반 없음).

- [ ] **Step 4: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/V*__ad_disclosure_verdict.sql
git commit -m "feat(monitoring): 광고 표기 판정 컬럼 + 시딩 계정 테이블 마이그레이션"
```

---

### Task 2: Tier1 고신뢰 사전 매칭 — AdDisclosurePatterns

순수 함수, LLM·DB 무관. 스펙 §5 Tier1의 패턴 목록을 그대로 코드화한다.

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosurePatterns.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosurePatternsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdDisclosurePatternsTest {

	@Test
	void 고신뢰_패턴을_찾는다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("오늘의 룩 #광고 잘봐주세요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
		assertThat(m.start()).isEqualTo(6);
	}

	@Test
	void 여러_패턴_중_가장_이른_위치를_고른다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("협찬받았어요 오늘의 룩 #광고");
		assertThat(m.phrase()).isEqualTo("협찬받았");
	}

	@Test
	void 소정의_수수료_원고료_광고료_패턴을_인식한다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 원고료를 지급받았습니다").phrase())
				.isEqualTo("소정의 원고료");
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 수수료를 지급받았습니다")).isNotNull();
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 광고료를 지급받았습니다")).isNotNull();
	}

	@Test
	void 저정밀_단독_광고는_사전에_없다() {
		// "광고판이 예쁘네요" 같은 오탐 방지(스펙 §5 Tier1) — 단독 "광고"는 사전 미등재
		assertThat(AdDisclosurePatterns.findFirstMatch("동네 광고판이 예쁘네요")).isNull();
	}

	@Test
	void 매칭_없으면_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("오늘의 데일리룩 공유합니다")).isNull();
	}

	@Test
	void 빈_캡션은_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("")).isNull();
		assertThat(AdDisclosurePatterns.findFirstMatch(null)).isNull();
	}

	@Test
	void 더_긴_해시태그의_접두_매칭을_토큰_경계로_차단한다() {
		// "#광고아님"은 "#광고"의 접두이지만 뒤에 문자가 이어지므로 매칭되지 않는다.
		// "내돈내산"도 부정 가드에 걸려 이중으로 null이다.
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고아님 내돈내산")).isNull();
	}

	@Test
	void 협찬받고_싶다는_모집_문맥은_사전에_없다() {
		// "협찬받고"(희망·모집)는 과거형 확정 문구가 아니라 Tier1에서 제외 — LLM(Tier2)이 처리한다.
		assertThat(AdDisclosurePatterns.findFirstMatch("협찬받고 싶어요 연락주세요")).isNull();
	}

	@Test
	void 부정_신호가_있으면_다른_고신뢰_패턴이_있어도_null() {
		// "내돈내산이지만 #광고"처럼 부정 신호가 캡션 어디든 있으면 Tier1 확정을 포기한다(판단 보류).
		assertThat(AdDisclosurePatterns.findFirstMatch("내돈내산이지만 #광고")).isNull();
	}

	@Test
	void 부정_신호_없는_정상_해시태그는_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고 오늘 후기")).isNotNull();
	}

	@Test
	void 협찬받았다는_과거형_확정_문구는_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("협찬받았어요")).isNotNull();
	}

	@Test
	void 해시태그_뒤_구두점은_토큰_경계를_해치지_않는다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고, 오늘 후기")).isNotNull();
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosurePatternsTest"`
Expected: FAIL — `AdDisclosurePatterns` 클래스 없음(컴파일 오류)

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.ad;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 Tier1 고신뢰 사전(스펙 §5) — 지침 원문 예시 중 <b>오탐 여지가 없는 패턴만</b> 등재한다.
 * 매칭되면 위치 규칙({@link AdPositionRule}) 통과 시 LLM 콜 없이 DISCLOSED를 확정한다
 * ({@link AdDisclosureJudgeService} 참조). "광고" 단독처럼 저정밀 패턴은 의도적으로 미등재
 * ("광고판이 예쁘네요" 오탐 — 스펙 §5).
 * <b>부정 신호가 보이면 Tier1은 확정하지 않는다 — 문맥 판단은 LLM 몫이다.</b>
 * ("#광고아님 내돈내산", "내돈내산이지만 #광고" 같은 캡션은 {@link #NEGATION}에 걸려 null을 반환하고
 * LLM(Tier2)로 넘어간다 — Tier1은 false DISCLOSED를 내느니 판단을 보류한다.)
 */
public final class AdDisclosurePatterns {

	private AdDisclosurePatterns() {
	}

	// 해시태그 패턴은 더 긴 해시태그의 접두만 매칭되는 사고를 막기 위해 토큰 경계를 강제한다
	// ((?![\p{L}\p{N}_]) — 다음 글자가 문자/숫자/밑줄이면 매칭 실패). 예: "#광고아님"은 "#광고"로
	// 오탐하지 않는다.
	private static final List<Pattern> HIGH_CONFIDENCE = List.of(
			Pattern.compile("#유료광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("#광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("#협찬(?![\\p{L}\\p{N}_])"),
			Pattern.compile("광고입니다"),
			Pattern.compile("유료\\s*광고"),
			Pattern.compile("대가성\\s*광고"),
			// "협찬받고"(모집·희망) 오탐 방지 — 과거형 확정 문구만("협찬받았", "협찬받은").
			// "협찬받아 작성" 류는 Tier1에서 빠지지만 LLM(Tier2)이 처리해 정확도 손실은 없다.
			Pattern.compile("협찬\\s*받(았|은)"),
			Pattern.compile("제공받아\\s*작성"),
			Pattern.compile("소정의\\s*(수수료|원고료|광고료)"));

	// 캡션 어디든 부정·자비 구매 신호가 하나라도 있으면 Tier1 확정을 포기하고 LLM(Tier2)으로 넘긴다.
	// 이건 NOT_DISCLOSED 확정이 아니라 "판단 보류"다 — Tier1이 낼 수 있는 최악의 오류(false
	// DISCLOSED)를 막기 위한 가드일 뿐, 부정 문구 자체가 미표기를 의미하지 않는다.
	private static final Pattern NEGATION =
			Pattern.compile("내돈내산|광고\\s*아니|협찬\\s*아니|광고아님|협찬아님");

	/** 매칭 문구·문자 오프셋 — 오프셋은 그래핌이 아니라 char index(호출부가 위치 판정 시 변환). */
	public record Match(String phrase, int start, int end) {
	}

	/**
	 * 캡션 전체에서 가장 이른 위치의 고신뢰 매칭 1건. 여러 패턴이 매칭돼도 등장 순서로만 고른다.
	 * 부정 신호({@link #NEGATION})가 캡션 어디든 있으면 Tier1을 포기하고 null을 반환한다.
	 */
	public static Match findFirstMatch(String caption) {
		if (caption == null || caption.isBlank()) {
			return null;
		}
		if (NEGATION.matcher(caption).find()) {
			return null;
		}
		Match best = null;
		for (Pattern pattern : HIGH_CONFIDENCE) {
			Matcher matcher = pattern.matcher(caption);
			if (matcher.find() && (best == null || matcher.start() < best.start())) {
				best = new Match(matcher.group(), matcher.start(), matcher.end());
			}
		}
		return best;
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosurePatternsTest"`
Expected: PASS (12개 — 부정 문맥 오탐 차단 수정(2026-08-17) 후 6개 추가)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosurePatterns.java \
        monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosurePatternsTest.java
git commit -m "feat(monitoring): 광고 표기 Tier1 고신뢰 사전 매칭"
```

---

### Task 3: Tier3 위치 규칙 — AdPositionRule (그래핌 오프셋 + 3구간 + 첫 해시태그 예외)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdPositionRule.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/ad/AdPositionRuleTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdPositionRuleTest {

	@Test
	void 첫_해시태그는_오프셋_무관_인정() {
		// #광고가 캡션의 첫 번째 해시태그 토큰이면 뒤에 아무리 와도(더보기 접힘권) 예외 인정(지침 다.(2)③)
		String longTail = "룩 소개".repeat(50);
		String caption = "#광고 " + longTail;
		int start = caption.indexOf("#광고");
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.FIRST_HASHTAG);
	}

	@Test
	void 첫_해시태그가_아니면_일반_위치_규칙을_따른다() {
		String caption = "오늘 룩 소개 #데일리룩 " + "#광고";
		int start = caption.lastIndexOf("#광고");
		// 짧은 캡션이라 보임 상한(125그래핌) 안 — VISIBLE
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.VISIBLE);
	}

	@Test
	void 보임_상한_안쪽은_VISIBLE() {
		// #광고가 유일한 해시태그면 첫 해시태그 예외(FIRST_HASHTAG)로 빠지므로, 앞에 다른 해시태그를
		// 둬 일반 위치 규칙(보임 상한 이내)을 실제로 태운다.
		String caption = "짧은 캡션입니다 #데일리 #광고";
		int start = caption.indexOf("#광고");
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.VISIBLE);
	}

	@Test
	void 접힘_하한_초과는_HIDDEN() {
		// 해시태그가 아닌 본문 문구("광고입니다", Tier1 사전 항목)를 쓴다 — "#광고"면 캡션의 유일한
		// 해시태그라 첫 해시태그 예외로 빠져 이 테스트가 검증하려는 일반 위치 규칙의 HIDDEN 분기를
		// 태우지 못한다.
		String filler = "가".repeat(250);   // 그래핌 250 > HIDDEN_LOWER_BOUND(220)
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.HIDDEN);
	}

	@Test
	void 세번째_줄_이후는_HIDDEN() {
		// 마찬가지로 비해시태그 문구 — "#광고"면 유일한 해시태그라 첫 해시태그 예외에 걸린다.
		String caption = "1번째 줄\n2번째 줄\n3번째 줄 광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.HIDDEN);
	}

	@Test
	void 경계_사이_회색지대는_게시자에게_유리하게_GRAY() {
		// 보임 상한(125) 초과 & 접힘 하한(220) 이하 — 확실한 위반 아님(지침 원문 "눌러야만"만 부적절).
		// 비해시태그 문구를 쓴다 — "#광고"였다면 유일한 해시태그라 첫 해시태그 예외로 빠진다.
		String filler = "가".repeat(160);
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.GRAY);
	}

	@Test
	void 그래핌_오프셋은_문자_인덱스와_다를_수_있다() {
		// 이모지·결합 문자 등 서로게이트 페어 — 여기서는 최소한 일반 한글 문자열에서 char==grapheme임을 확인
		assertThat(AdPositionRule.graphemeOffset("가나다#광고", 3)).isEqualTo(3);
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdPositionRuleTest"`
Expected: FAIL — 컴파일 오류(클래스 없음)

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.ad;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 위치 판정(스펙 §5 Tier3, 지침 다.(2)) — '더보기' 접힘은 렌더링 기준(기기·폰트·이모지 폭)이라
 * 텍스트만으로 정확 판정이 불가능하다. 근사임을 인정하고 <b>불확실성은 전부 "위반 아님" 쪽으로</b>
 * 떨어지게 설계한다(지침 원문도 "눌러야만 확인 가능한 경우"만 부적절로 규정 — 회색지대는 게시자에게 유리).
 *
 * <p>경계값(VISIBLE_UPPER_BOUND·HIDDEN_LOWER_BOUND)은 초기값이다 — IG 피드 캡션의 실측 "더보기"
 * 절단 지점(~125자)을 참고했을 뿐, 골드셋 단계(스펙 §10-2)에서 실기기 실측으로 캘리브레이션한다.
 */
public final class AdPositionRule {

	private AdPositionRule() {
	}

	/** 문구 전체가 이 그래핌 수 이내 + 첫 2줄 이내면 확실히 보임(VISIBLE). 캘리브레이션 전 초기값. */
	public static final int VISIBLE_UPPER_BOUND_GRAPHEMES = 125;
	/** 시작 오프셋이 이 그래핌 수를 넘거나 3번째 줄 이후면 확실히 접힘(HIDDEN). 캘리브레이션 전 초기값. */
	public static final int HIDDEN_LOWER_BOUND_GRAPHEMES = 220;
	private static final int VISIBLE_LINE_MAX = 2;
	private static final int HIDDEN_LINE_MIN = 3;

	private static final Pattern FIRST_HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");

	public enum Band { VISIBLE, GRAY, HIDDEN, FIRST_HASHTAG }

	/** start·end는 char index(String.indexOf 등 표준 자바 인덱스) — 그래핌 변환은 내부에서 한다. */
	public static Band evaluate(String caption, int start, int end) {
		if (isFirstHashtag(caption, start)) {
			return Band.FIRST_HASHTAG;
		}
		int startGrapheme = graphemeOffset(caption, start);
		int endGrapheme = graphemeOffset(caption, end);
		int startLine = lineOf(caption, start);
		boolean visible = endGrapheme <= VISIBLE_UPPER_BOUND_GRAPHEMES && startLine <= VISIBLE_LINE_MAX;
		if (visible) {
			return Band.VISIBLE;
		}
		boolean hidden = startGrapheme > HIDDEN_LOWER_BOUND_GRAPHEMES || startLine >= HIDDEN_LINE_MIN;
		return hidden ? Band.HIDDEN : Band.GRAY;
	}

	/** 캡션의 첫 번째 '#' 해시태그 토큰과 시작 위치가 같으면 첫 해시태그(지침 다.(2)③ — 오프셋 무관 인정). */
	private static boolean isFirstHashtag(String caption, int start) {
		Matcher matcher = FIRST_HASHTAG.matcher(caption);
		return matcher.find() && matcher.start() == start;
	}

	/** char index → 그래핌(사용자 인지 문자) 개수. BreakIterator 캐릭터 경계 — ICU4J 의존 없이 JDK만. */
	public static int graphemeOffset(String text, int charIndex) {
		BreakIterator it = BreakIterator.getCharacterInstance(Locale.KOREAN);
		it.setText(text);
		int count = 0;
		for (int boundary = it.first(); boundary != BreakIterator.DONE && boundary < charIndex;
				boundary = it.next()) {
			count++;
		}
		return count;
	}

	/** 1-base 줄 번호 — charIndex 이전의 개행 수 + 1. */
	private static int lineOf(String text, int charIndex) {
		int line = 1;
		int limit = Math.min(charIndex, text.length());
		for (int i = 0; i < limit; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdPositionRuleTest"`
Expected: PASS (7개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/ad/AdPositionRule.java \
        monitoring/src/test/java/com/celfit/monitoring/ad/AdPositionRuleTest.java
git commit -m "feat(monitoring): 광고 표기 위치 3구간 판정(그래핌 오프셋 + 첫 해시태그 예외)"
```

---

### Task 4: Tier3 조합표 — AdVerdictResult + AdVerdictCombiner

환각 차단(substring 대조) + 조합표 최종 판정. LLM·DB 무관, 순수 함수.

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdVerdictResult.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractor.java` (Disclosure·Category만 먼저 — 본체는 Task 6)
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdVerdictCombiner.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/ad/AdVerdictCombinerTest.java`

- [ ] **Step 1: 결과 레코드 작성 (placeholder 아님 — 바로 실제 타입)**

```java
package com.celfit.monitoring.ad;

import java.util.List;

/** Tier0~3 최종 판정 — verdict 4종(DISCLOSED/NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN) ·
 * source(RULE/LLM) · violations 코드 배열 · evidence 근거 문구(스펙 §4 컬럼과 1:1). */
public record AdVerdictResult(String verdict, String source, List<String> violations, List<Evidence> evidence) {

	public record Evidence(String phrase, String category, int offset) {
	}
}
```

- [ ] **Step 2: Tier2 산출 타입 선행 정의 (본체 구현은 Task 6)**

```java
package com.celfit.monitoring.ad;

import java.util.List;

/**
 * Tier2 LLM 문구 추출 seam — 본체는 Task 6에서 구현한다. 여기서는 {@link AdVerdictCombiner}가
 * 의존하는 출력 타입(Disclosure·Category)만 먼저 확정해 Tier3를 LLM 없이 테스트할 수 있게 한다.
 */
public interface AdDisclosureExtractor {

	enum Category { CLEAR, AMBIGUOUS, FOREIGN, UNCERTAIN }

	record Disclosure(String phrase, Category category) {
	}

	List<Disclosure> extract(String caption);
}
```

- [ ] **Step 3: 실패하는 테스트 작성 — 스펙 §5 조합표 그대로**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdVerdictCombinerTest {

	@Test
	void CLEAR_문구가_적절_위치면_DISCLOSED() {
		String caption = "오늘 룩 소개 #광고";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void CLEAR_있으나_전부_묻힌_위치면_INSUFFICIENT_HIDDEN_PLACEMENT() {
		// 비해시태그 문구를 쓴다 — "#광고"였다면 캡션의 유일한 해시태그라 첫 해시태그 예외로 빠져
		// DISCLOSED가 나온다(이 테스트가 검증하려는 HIDDEN 분기를 태우지 못한다).
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("광고입니다", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	@Test
	void AMBIGUOUS만_존재하면_INSUFFICIENT_AMBIGUOUS_EXPRESSION() {
		String caption = "체험단 후기입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("AMBIGUOUS_EXPRESSION");
	}

	@Test
	void AMBIGUOUS가_묻힌_위치면_묻힘_코드가_병기된다() {
		String filler = "가".repeat(250);
		String caption = filler + "체험단";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS)));
		assertThat(result.violations()).containsExactlyInAnyOrder("AMBIGUOUS_EXPRESSION", "HIDDEN_PLACEMENT");
	}

	@Test
	void FOREIGN만_존재하면_INSUFFICIENT_FOREIGN_LANGUAGE() {
		String caption = "today's look Sponsor";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("Sponsor", Category.FOREIGN)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("FOREIGN_LANGUAGE");
	}

	@Test
	void UNCERTAIN_문구뿐이면_UNCERTAIN_위반_없음() {
		String caption = "협업 관련 문의는 DM으로";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("협업", Category.UNCERTAIN)));
		assertThat(result.verdict()).isEqualTo("UNCERTAIN");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void 문구_없음_사진은_NOT_DISCLOSED() {
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", false, null, List.of());
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.violations()).containsExactly("NO_DISCLOSURE");
	}

	@Test
	void 문구_없음_릴스는_UNCERTAIN() {
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", true, null, List.of());
		assertThat(result.verdict()).isEqualTo("UNCERTAIN");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void LLM이_인용한_문구가_캡션에_없으면_환각_차단_폐기() {
		// "#광고"를 인용했지만 실제 캡션엔 없다 — substring 대조 실패, 폐기 후 문구 없음으로 처리
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.evidence()).isEmpty();
	}

	@Test
	void Tier1_매칭이_묻힌_위치여도_evidence로_넘어온다() {
		// "광고입니다"는 AdDisclosurePatterns의 Tier1 사전 항목(해시태그 아님) — "#광고"를 쓰면
		// 캡션의 유일한 해시태그라 첫 해시태그 예외로 빠져 HIDDEN을 검증할 수 없다.
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		var tier1 = new AdDisclosurePatterns.Match("광고입니다", filler.length(),
				filler.length() + "광고입니다".length());
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, tier1, List.of());
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.evidence()).extracting(AdVerdictResult.Evidence::phrase).containsExactly("광고입니다");
	}

	@Test
	void 여러_카테고리가_섞이면_CLEAR_적절_위치가_우선한다() {
		String caption = "체험단이지만 #광고 표기도 했어요";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS), new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
	}
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdVerdictCombinerTest"`
Expected: FAIL — `AdVerdictCombiner` 없음

- [ ] **Step 5: 구현**

```java
package com.celfit.monitoring.ad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Tier3 — 환각 차단 + 위치 판정 + 최종 조합(스펙 §5). LLM·DB 무관, 전부 결정적 순수 함수라
 * 골드셋 없이도 지침 원문 예시 전수 테스트가 가능하다(스펙 §10-1).
 *
 * <p>우선순위(조합표 순서 그대로): CLEAR+적절위치 → DISCLOSED. CLEAR뿐이나 전부 묻힘 →
 * INSUFFICIENT+HIDDEN_PLACEMENT. AMBIGUOUS만 → INSUFFICIENT+AMBIGUOUS_EXPRESSION(묻힘 병기).
 * FOREIGN만 → INSUFFICIENT+FOREIGN_LANGUAGE. UNCERTAIN뿐 → UNCERTAIN. 유효 문구 전무 →
 * 사진 NOT_DISCLOSED+NO_DISCLOSURE / 릴스 UNCERTAIN(Tier0과 같은 분기 — 문구가 전부 환각 폐기된
 * 경우도 여기로 떨어진다).
 */
public final class AdVerdictCombiner {

	private AdVerdictCombiner() {
	}

	private record Evaluated(String phrase, String category, AdPositionRule.Band band, int graphemeOffset,
			String source) {
	}

	/**
	 * @param tier1Match Tier1이 찾았지만(위치 부적절 등으로) 확정 못 하고 넘어온 매칭 — null 허용.
	 *                   Tier3는 이 매칭도 CLEAR/RULE 후보로 재평가한다(위치가 그새 바뀌지 않으므로
	 *                   보통 같은 band가 나오지만, 판정 로직을 한곳(AdPositionRule)에만 둔다).
	 * @param llmDisclosures Tier2 추출 결과 — 캡션에 실존하지 않는 phrase는 여기서 폐기된다(환각 차단).
	 */
	public static AdVerdictResult combine(String caption, boolean isReels, AdDisclosurePatterns.Match tier1Match,
			List<AdDisclosureExtractor.Disclosure> llmDisclosures) {
		List<Evaluated> candidates = new ArrayList<>();
		if (tier1Match != null) {
			candidates.add(evaluate(caption, tier1Match.phrase(), tier1Match.start(), tier1Match.end(),
					"CLEAR", "RULE"));
		}
		for (AdDisclosureExtractor.Disclosure d : llmDisclosures) {
			int idx = caption.indexOf(d.phrase());
			if (idx < 0) {
				continue;   // 환각 차단(스펙 §5 Tier3) — 캡션에 실존하지 않는 문구는 판정에서 배제
			}
			candidates.add(evaluate(caption, d.phrase(), idx, idx + d.phrase().length(),
					d.category().name(), "LLM"));
		}
		return decide(dedupe(candidates), isReels);
	}

	private static Evaluated evaluate(String caption, String phrase, int start, int end, String category,
			String source) {
		AdPositionRule.Band band = AdPositionRule.evaluate(caption, start, end);
		int offset = AdPositionRule.graphemeOffset(caption, start);
		return new Evaluated(phrase, category, band, offset, source);
	}

	/** 같은 (phrase, 그래핌 오프셋)이 Tier1·Tier2 양쪽에서 나오면 evidence 중복을 접는다. */
	private static List<Evaluated> dedupe(List<Evaluated> in) {
		LinkedHashMap<String, Evaluated> byKey = new LinkedHashMap<>();
		for (Evaluated e : in) {
			byKey.putIfAbsent(e.phrase() + "|" + e.graphemeOffset(), e);
		}
		return List.copyOf(byKey.values());
	}

	private static AdVerdictResult decide(List<Evaluated> candidates, boolean isReels) {
		List<AdVerdictResult.Evidence> evidence = candidates.stream()
				.map(c -> new AdVerdictResult.Evidence(c.phrase(), c.category(), c.graphemeOffset()))
				.toList();

		List<Evaluated> clear = byCategory(candidates, "CLEAR");
		Optional<Evaluated> clearAccepted = clear.stream().filter(AdVerdictCombiner::accepted).findFirst();
		if (clearAccepted.isPresent()) {
			return new AdVerdictResult("DISCLOSED", clearAccepted.get().source(), List.of(), evidence);
		}
		if (!clear.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", clear.get(0).source(), List.of("HIDDEN_PLACEMENT"), evidence);
		}

		List<Evaluated> ambiguous = byCategory(candidates, "AMBIGUOUS");
		if (!ambiguous.isEmpty()) {
			List<String> violations = new ArrayList<>();
			violations.add("AMBIGUOUS_EXPRESSION");
			if (ambiguous.stream().anyMatch(c -> c.band() == AdPositionRule.Band.HIDDEN)) {
				violations.add("HIDDEN_PLACEMENT");
			}
			return new AdVerdictResult("INSUFFICIENT", "LLM", violations, evidence);
		}

		List<Evaluated> foreign = byCategory(candidates, "FOREIGN");
		if (!foreign.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", "LLM", List.of("FOREIGN_LANGUAGE"), evidence);
		}

		boolean anyUncertain = candidates.stream().anyMatch(c -> "UNCERTAIN".equals(c.category()));
		if (anyUncertain) {
			return new AdVerdictResult("UNCERTAIN", "LLM", List.of(), evidence);
		}

		// 유효 문구 전무(전부 환각으로 폐기된 경우 포함) — Tier0과 같은 매체별 분기.
		return isReels
				? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), evidence)
				: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), evidence);
	}

	private static boolean accepted(Evaluated c) {
		return c.band() == AdPositionRule.Band.VISIBLE || c.band() == AdPositionRule.Band.GRAY
				|| c.band() == AdPositionRule.Band.FIRST_HASHTAG;
	}

	private static List<Evaluated> byCategory(List<Evaluated> in, String category) {
		return in.stream().filter(c -> category.equals(c.category())).toList();
	}
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdVerdictCombinerTest"`
Expected: PASS (12개)

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/ad/AdVerdictResult.java \
        monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractor.java \
        monitoring/src/main/java/com/celfit/monitoring/ad/AdVerdictCombiner.java \
        monitoring/src/test/java/com/celfit/monitoring/ad/AdVerdictCombinerTest.java
git commit -m "feat(monitoring): 광고 표기 Tier3 환각 차단·조합표 판정"
```

---

### Task 5: 지침 원문 예시 전수 고정 테스트 (스펙 §10-1)

LLM 없이 Tier1+Tier3만으로 스펙 §2의 적절/부적절 예시를 전부 케이스로 고정한다. 지침이 명시한
사례를 틀리면 빌드가 실패해야 한다(회귀 방지).

**Files:**
- Create: `monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureGuidelineExamplesTest.java`

- [ ] **Step 1: 테스트 작성 (이미 존재하는 구현으로 바로 통과해야 하는 회귀 고정 테스트)**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 공정위예규 제499호 Ⅴ.6 예시(스펙 §2) 전수 고정 — LLM 무관, Tier1(적절 예)·Tier3 조합표(부적절
 * 카테고리)만으로 검증한다. 지침이 명시한 사례가 틀리면 이 테스트가 빌드를 깬다.
 */
class AdDisclosureGuidelineExamplesTest {

	// ---------- 적절 예(Tier1 사전 매칭 대상, 앞부분 배치로 VISIBLE 위치) ----------

	@Test
	void 적절_예_광고() {
		assertDisclosed("오늘 소개할 제품 #광고");
	}

	@Test
	void 적절_예_협찬() {
		assertDisclosed("#협찬 받았어요 오늘의 룩");
	}

	@Test
	void 적절_예_대가성_광고() {
		assertDisclosed("대가성 광고 포함된 게시물입니다");
	}

	@Test
	void 적절_예_금전적_지원() {
		// "금전적 지원"은 Tier1 사전에 없다(오탐 여지 있는 표현) — LLM CLEAR 추출을 가정해 Tier3만 검증
		AdVerdictResult result = AdVerdictCombiner.combine("금전적 지원을 받아 작성했습니다", false, null,
				List.of(new Disclosure("금전적 지원을 받아", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
	}

	@Test
	void 적절_예_무료_상품_상품_협찬_상품_할인() {
		for (String phrase : List.of("무료 상품", "상품 협찬", "상품 할인")) {
			AdVerdictResult result = AdVerdictCombiner.combine(phrase + " 받아 작성했습니다", false, null,
					List.of(new Disclosure(phrase, Category.CLEAR)));
			assertThat(result.verdict()).as(phrase).isEqualTo("DISCLOSED");
		}
	}

	// ---------- 부적절 예(전부 AMBIGUOUS로 LLM이 분류한다고 가정 — Tier3만 검증) ----------

	@Test
	void 부적절_예_체험_후기_체험단_선물_보내주셨어요() {
		for (String phrase : List.of("체험 후기", "체험단", "선물", "에서 보내주셨어요")) {
			AdVerdictResult result = AdVerdictCombiner.combine(phrase + " 잘 썼어요", false, null,
					List.of(new Disclosure(phrase, Category.AMBIGUOUS)));
			assertThat(result.verdict()).as(phrase).isEqualTo("INSUFFICIENT");
			assertThat(result.violations()).as(phrase).contains("AMBIGUOUS_EXPRESSION");
		}
	}

	@Test
	void 부적절_예_브랜드해시태그_단순언급() {
		AdVerdictResult result = AdVerdictCombiner.combine("#쿨더마 다녀왔어요", false, null,
				List.of(new Disclosure("#쿨더마", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 부적절_예_브랜드명_계정명_콜라보표기() {
		AdVerdictResult result = AdVerdictCombiner.combine("쿨더마×나의계정 콜라보", false, null,
				List.of(new Disclosure("쿨더마×나의계정", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 부적절_예_외국어_단독_AD_PR_Sponsor_spon_sp_Collabo_앰버서더_땡스투() {
		for (String phrase : List.of("AD", "PR", "Sponsor", "spon", "sp", "Collabo", "앰버서더", "땡스 투")) {
			AdVerdictResult result = AdVerdictCombiner.combine("오늘의 룩 " + phrase, false, null,
					List.of(new Disclosure(phrase, Category.FOREIGN)));
			assertThat(result.verdict()).as(phrase).isEqualTo("INSUFFICIENT");
			assertThat(result.violations()).as(phrase).containsExactly("FOREIGN_LANGUAGE");
		}
	}

	@Test
	void 부적절_예_본문_중간_구분없이_삽입은_HIDDEN() {
		// 비해시태그 문구("광고입니다", Tier1 사전 항목) — "#광고"였다면 캡션의 유일한 해시태그라
		// 첫 해시태그 예외(지침 다.(2)③)로 빠져 DISCLOSED가 나온다. 이 테스트는 "본문 중간 삽입"
		// 부적절 예시(지침 다.(2)①)를 검증하는 것이라 해시태그 예외 경로를 밟으면 안 된다.
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("광고입니다", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	@Test
	void 부적절_예_여러_해시태그_사이에_묻힘() {
		// 첫 해시태그가 아니고, 뒤쪽(접힘 하한 밖)에 있으면 묻힘 — 첫 해시태그 예외(다.(2)③)와 구분
		String manyTags = "#a #b #c #d #e #f #g #h #i #j #k #l #m #n #o #p #q #r #s #t ".repeat(6);
		String caption = manyTags + "#광고";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	private static void assertDisclosed(String caption) {
		AdDisclosurePatterns.Match tier1 = AdDisclosurePatterns.findFirstMatch(caption);
		assertThat(tier1).as("Tier1 매칭: " + caption).isNotNull();
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, tier1, List.of());
		assertThat(result.verdict()).as(caption).isEqualTo("DISCLOSED");
	}
}
```

- [ ] **Step 2: 테스트 실행 — 통과 확인 (Task 2·4 구현이 이미 있으므로 여기선 회귀 고정만)**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosureGuidelineExamplesTest"`
Expected: PASS 전부. 실패하는 케이스가 있으면 Task 2(사전)·Task 3(위치 규칙)·Task 4(조합)로 돌아가
로직을 수정한다 — 이 테스트를 지침에 맞춰 고치지 말 것(정본은 지침 원문).

- [ ] **Step 3: 커밋**

```bash
git add monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureGuidelineExamplesTest.java
git commit -m "test(monitoring): 공정위예규 제499호 예시 전수 고정 테스트"
```

---

### Task 6: Tier2 LLM 추출기 — AdDisclosureExtractorGemini

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractor.java` (인터페이스는 Task 4에서 이미 확정 — 이 태스크는 실 구현체 추가)
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractorGemini.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureExtractorGeminiTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (BrandMentionJudgeTest 관용구 — fake GeminiHttp, DB 없음)**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdDisclosureExtractorGeminiTest {

	private static String geminiBody(String disclosuresJson) {
		return """
				{"candidates":[{"content":{"parts":[{"text":"{\\"disclosures\\":%s}"}]}}]}"""
				.formatted(disclosuresJson.replace("\"", "\\\""));
	}

	@Test
	void 문구와_카테고리를_파싱한다() {
		var extractor = new AdDisclosureExtractorGemini(
				(path, body) -> geminiBody("[{\"phrase\":\"#광고\",\"category\":\"CLEAR\"}]"), "key", "model-x");
		List<AdDisclosureExtractor.Disclosure> result = extractor.extract("오늘의 룩 #광고");
		assertThat(result).containsExactly(new AdDisclosureExtractor.Disclosure("#광고", Category.CLEAR));
	}

	@Test
	void 여러_문구를_파싱한다() {
		var extractor = new AdDisclosureExtractorGemini((path, body) -> geminiBody(
				"[{\"phrase\":\"체험단\",\"category\":\"AMBIGUOUS\"},{\"phrase\":\"Sponsor\",\"category\":\"FOREIGN\"}]"),
				"key", "m");
		assertThat(extractor.extract("c")).hasSize(2);
	}

	@Test
	void 빈_배열은_빈_리스트() {
		var extractor = new AdDisclosureExtractorGemini((path, body) -> geminiBody("[]"), "key", "m");
		assertThat(extractor.extract("광고 표기 없음")).isEmpty();
	}

	@Test
	void 요청_경로와_바디에_모델_캡션이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var extractor = new AdDisclosureExtractorGemini((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("[]");
		}, "key", "model-x");
		extractor.extract("오늘의 룩 #광고");
		assertThat(sent.get()).contains("model-x:generateContent").contains("오늘의 룩 #광고")
				.contains("responseSchema");
	}

	@Test
	void api_키가_비어있으면_예외로_실패한다() {
		// BrandMentionJudge와 달리 fail-closed(UNCERTAIN)로 접지 않는다 — verdict NULL 유지가 계약(스펙 §5)
		var extractor = new AdDisclosureExtractorGemini((p, b) -> {
			throw new AssertionError("키 없이는 호출하면 안 된다");
		}, "", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void candidates_본문이_없으면_예외() {
		var extractor = new AdDisclosureExtractorGemini((p, b) -> "{}", "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 예상_밖_category_문자열은_예외() {
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> geminiBody("[{\"phrase\":\"x\",\"category\":\"MAYBE\"}]"), "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosureExtractorGeminiTest"`
Expected: FAIL — 클래스 없음

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.ad;

import com.celfit.monitoring.llm.GeminiHttp;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 광고 표기 Tier2 — LLM은 <b>판단이 아니라 추출</b>만 한다(스펙 §5 Tier2·3 역할 분담 원리).
 * 사전·정규식이 못 잡는 표기 변형·신조어·부정 문맥(`광고 아니고 내돈내산`)의 문구를 찾아 그대로
 * 인용하고 카테고리만 분류한다 — 최종 verdict는 {@link AdVerdictCombiner}가 결정적으로 계산한다.
 *
 * <p>{@link com.celfit.monitoring.llm.BrandMentionJudge}와 달리 api-key 미설정·응답 파싱 실패를
 * fail-closed(UNCERTAIN)로 접지 않고 예외를 던진다 — 여기서 접으면 판정 컬럼에 잘못된 UNCERTAIN이
 * 영속화된다. 호출부({@link AdDisclosureJudgeService})가 예외를 잡아 verdict NULL을 유지하고
 * 다음 스윕이 재시도한다(스펙 §5).
 */
public class AdDisclosureExtractorGemini implements AdDisclosureExtractor {

	private static final String SYSTEM_INSTRUCTION = """
			너는 인스타그램 게시물 캡션에서 "경제적 이해관계(협찬·광고비 등 대가)를 받았다는 표시 문구"를
			찾아내는 추출기다. 판정은 하지 않는다 — 문구를 찾아 캡션 원문 그대로 인용하고 분류만 한다.

			분류 기준(공정거래위원회예규 제499호 「추천·보증 등에 관한 표시·광고 심사지침」 Ⅴ.6):
			- CLEAR(명확): 대가 수령 사실이 분명한 한국어 표현. 예: '#광고', '#유료광고', '#협찬',
			  '광고입니다', '유료 광고', '대가성 광고', '협찬받아 작성', '금전적 지원을 받았습니다',
			  '무료 상품을 제공받았습니다', '상품 협찬', '상품 할인을 제공받아 작성'.
			- AMBIGUOUS(모호): 대가 수령을 암시하지만 불명확하거나 소비자가 광고임을 알기 어려운 표현.
			  예: '체험 후기', '체험단', '선물', '~에서 보내주셨어요', 브랜드 해시태그 단순 언급
			  (광고·협찬 표시 없이 '#브랜드명'만), '브랜드명×계정명', 이해하기 어려운 줄임말.
			- FOREIGN(외국어 단독): 한국어 문맥 없이 외국어만으로 표기. 예: 'AD', 'PR', 'Sponsor',
			  'spon', 'sp', 'Collabo', '앰버서더', '땡스 투'. 단, 캡션 전체가 한국어 문장으로 자연스럽게
			  읽히면 FOREIGN이 아니라 CLEAR·AMBIGUOUS로 분류하라(예: "이 광고(AD)는 제가 직접...").
			- UNCERTAIN(판단불가): 표시로 보이지만 대가 수령 여부를 문맥만으로 판단하기 어려운 경우.

			부정 문맥 주의: '광고 아니고 내돈내산'처럼 광고임을 명시적으로 부정하는 문맥에서 등장한
			'광고'는 표시 문구가 아니다 — 추출하지 마라.

			phrase는 캡션에 실제로 등장하는 문자열 그대로(변형·요약·재구성 금지) 인용해야 한다.
			표시로 볼 수 있는 문구가 전혀 없으면 disclosures는 빈 배열이다.
			""";

	private final GeminiHttp http;
	private final String apiKey;
	private final String model;
	private final ObjectMapper om = new ObjectMapper();

	public AdDisclosureExtractorGemini(GeminiHttp http, String apiKey, String model) {
		this.http = http;
		this.apiKey = apiKey;
		this.model = model;
	}

	@Override
	public List<Disclosure> extract(String caption) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("Gemini api-key 미설정 — 광고 표기 판정 불가(verdict NULL 유지)");
		}
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent", requestBody(caption));
		return parse(responseBody);
	}

	private String requestBody(String caption) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
		parts.addObject().put("text", "캡션:\n" + caption);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", responseSchema());
		gen.put("maxOutputTokens", 512);
		return om.writeValueAsString(root);
	}

	private ObjectNode responseSchema() {
		ObjectNode schema = om.createObjectNode();
		schema.put("type", "object");
		ObjectNode properties = schema.putObject("properties");
		ObjectNode disclosures = properties.putObject("disclosures");
		disclosures.put("type", "array");
		ObjectNode items = disclosures.putObject("items");
		items.put("type", "object");
		ObjectNode itemProps = items.putObject("properties");
		itemProps.putObject("phrase").put("type", "string");
		ObjectNode category = itemProps.putObject("category");
		category.put("type", "string");
		ArrayNode categoryEnum = category.putArray("enum");
		for (Category c : Category.values()) {
			categoryEnum.add(c.name());
		}
		items.putArray("required").add("phrase").add("category");
		schema.putArray("required").add("disclosures");
		return schema;
	}

	private List<Disclosure> parse(String responseBody) {
		JsonNode root = om.readTree(responseBody);
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		JsonNode disclosures = om.readTree(text.asString()).path("disclosures");
		if (disclosures.isMissingNode() || !disclosures.isArray()) {
			throw new IllegalStateException("Gemini 응답에 disclosures 없음: " + abbreviate(text.asString()));
		}
		List<Disclosure> out = new ArrayList<>();
		for (JsonNode node : disclosures) {
			String phrase = node.path("phrase").asString();
			String categoryRaw = node.path("category").asString();
			Category category;
			try {
				category = Category.valueOf(categoryRaw);
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("예상 밖 category: " + categoryRaw, e);
			}
			out.add(new Disclosure(phrase, category));
		}
		return out;
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosureExtractorGeminiTest"`
Expected: PASS (7개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureExtractorGemini.java \
        monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureExtractorGeminiTest.java
git commit -m "feat(monitoring): 광고 표기 Tier2 LLM 문구 추출기(Gemini)"
```

---

### Task 7: BrandPostMetaRepository — 판정 상태 조회·기록

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandPostMetaRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandPostMetaRepositoryTest.java` (Task 1에서 자리만 예약)

- [ ] **Step 1: 실패하는 테스트 작성 (BrandHashtagRepositoryTest 관용구 — TestDb Testcontainers)**

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdVerdictResult;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandPostMetaRepositoryTest {

	JdbcTemplate db;
	BrandPostMetaRepository repo;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandPostMetaRepository(db);
		repo.upsert("AAA", "poster1", "FEED", LocalDate.of(2026, 8, 1), "캡션", null, null, null, null);
	}

	@Test
	void 판정_전_상태는_verdict_null_hash_null() {
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("AAA"));
		assertThat(state.get("AAA").adVerdict()).isNull();
		assertThat(state.get("AAA").judgedCaptionHash()).isNull();
	}

	@Test
	void 판정_결과를_기록하면_조회에_반영된다() {
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(),
				List.of(new AdVerdictResult.Evidence("#광고", "CLEAR", 3)));
		repo.updateAdVerdict("AAA", result, "hash123", Instant.parse("2026-08-17T00:00:00Z"));

		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("AAA"));
		assertThat(state.get("AAA").adVerdict()).isEqualTo("DISCLOSED");
		assertThat(state.get("AAA").judgedCaptionHash()).isEqualTo("hash123");
		assertThat(db.queryForObject("SELECT ad_verdict_source FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).isEqualTo("RULE");
		assertThat(db.queryForObject("SELECT ad_violations::text FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).isEqualTo("[]");
		assertThat(db.queryForObject("SELECT ad_evidence::text FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).contains("#광고");
	}

	@Test
	void 빈_코드_목록은_빈_맵() {
		assertThat(repo.findAdJudgmentState(List.of())).isEmpty();
	}

	@Test
	void 미존재_short_code는_상태가_null_state_자체는_존재() {
		// findAdJudgmentState는 존재하는 short_code만 맵에 담는다(호출부가 null-state를 "미존재"로 취급)
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("ZZZ"));
		assertThat(state).doesNotContainKey("ZZZ");
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인 (컴파일 오류: findAdJudgmentState·updateAdVerdict·AdJudgmentState 없음)**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandPostMetaRepositoryTest"`
Expected: FAIL

- [ ] **Step 3: 구현 — BrandPostMetaRepository에 메서드 추가**

`monitoring/src/main/java/com/celfit/monitoring/store/BrandPostMetaRepository.java` 상단 import에 추가:

```java
import com.celfit.monitoring.ad.AdVerdictResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
```

클래스 필드에 `private final ObjectMapper om = new ObjectMapper();` 추가, 클래스 끝(마지막 `}`  전)에
메서드 추가:

```java
	/** 광고 표기 판정 상태 — ad_verdict NULL 또는 judged_caption_hash 불일치가 재판정 대상(스펙 §7). */
	public record AdJudgmentState(String adVerdict, String judgedCaptionHash) {
	}

	public Map<String, AdJudgmentState> findAdJudgmentState(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(shortCodes.size(), "?"));
		Map<String, AdJudgmentState> out = new HashMap<>();
		db.query("SELECT short_code, ad_verdict, judged_caption_hash FROM brand_post_meta WHERE short_code IN ("
						+ placeholders + ")",
				rs -> {
					out.put(rs.getString("short_code"),
							new AdJudgmentState(rs.getString("ad_verdict"), rs.getString("judged_caption_hash")));
				}, shortCodes.toArray());
		return out;
	}

	/**
	 * 판정 결과 기록 — violations·evidence는 애플리케이션에서 jsonb로 직렬화한다(AlarmEventRepository
	 * {@code ?::jsonb} 관용구). captionHash는 호출부가 계산한 판정 시점 caption의 MD5(스펙 §4).
	 */
	public void updateAdVerdict(String shortCode, AdVerdictResult result, String captionHash, Instant judgedAt) {
		db.update("""
				UPDATE brand_post_meta
				SET ad_verdict = ?, ad_verdict_source = ?, ad_violations = ?::jsonb, ad_evidence = ?::jsonb,
				    ad_judged_at = ?, judged_caption_hash = ?
				WHERE short_code = ?""",
				result.verdict(), result.source(), om.writeValueAsString(result.violations()),
				om.writeValueAsString(result.evidence()), Timestamp.from(judgedAt), captionHash, shortCode);
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandPostMetaRepositoryTest"`
Expected: PASS (4개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandPostMetaRepository.java \
        monitoring/src/test/java/com/celfit/monitoring/store/BrandPostMetaRepositoryTest.java
git commit -m "feat(monitoring): brand_post_meta 광고 판정 상태 조회·기록"
```

---

### Task 8: 판정 오케스트레이터 — AdDisclosureJudgeService (Tier0 + 조율 + 후보 선정)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureJudgeService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureJudgeServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (fake extractor + in-memory repo 대역, DB 없음)**

```java
package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class AdDisclosureJudgeServiceTest {

	private static PostInfo post(String shortCode, String caption, String contentType,
			Boolean isPaidPartnership) {
		return new PostInfo(shortCode, "poster1", null, null, "uid1", contentType, caption, null,
				1700000000L, 1L, 1L, 1L, null, null, null, null, null, null, isPaidPartnership,
				true, false, false);
	}

	@Test
	void 유료협찬_라벨이면_LLM_호출_없이_DISCLOSED() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "무설명 캡션", "FEED", true)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
		assertThat(repo.written.get("AAA").source()).isEqualTo("RULE");
	}

	@Test
	void 캡션_공백_사진은_NOT_DISCLOSED_릴스는_UNCERTAIN() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("F1", "", "FEED", null), post("R1", "", "REELS", null)));

		assertThat(repo.written.get("F1").verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(repo.written.get("R1").verdict()).isEqualTo("UNCERTAIN");
		assertThat(extractor.calls).isEmpty();
	}

	@Test
	void Tier1_매칭_적절_위치면_LLM_생략하고_DISCLOSED() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "오늘 소개 #광고", "FEED", null)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
		assertThat(repo.written.get("AAA").source()).isEqualTo("RULE");
	}

	@Test
	void Tier1_미매칭이면_LLM_호출_후_조합() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.next = List.of(new Disclosure("체험단", Category.AMBIGUOUS));
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "체험단 후기입니다", "FEED", null)));

		assertThat(extractor.calls).containsExactly("체험단 후기입니다");
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 이미_같은_캡션으로_판정된_게시물은_재판정하지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		String hash = md5("변경없는 캡션");
		repo.state.put("AAA", new BrandPostMetaRepository.AdJudgmentState("UNCERTAIN", hash));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "변경없는 캡션", "FEED", null)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written).doesNotContainKey("AAA");
	}

	@Test
	void 캡션이_바뀌면_재판정한다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		repo.state.put("AAA", new BrandPostMetaRepository.AdJudgmentState("NOT_DISCLOSED", md5("옛 캡션")));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "새 캡션 #광고", "FEED", null)));

		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
	}

	@Test
	void LLM_실패는_격리되고_verdict를_쓰지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.fail = true;
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "체험단 후기", "FEED", null)));

		assertThat(repo.written).doesNotContainKey("AAA");
	}

	@Test
	void 빈_목록은_아무_일도_하지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		new AdDisclosureJudgeService(repo, extractor, Runnable::run).judgePosts(List.of());
		assertThat(repo.written).isEmpty();
	}

	private static String md5(String s) {
		try {
			var digest = MessageDigest.getInstance("MD5").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class FakeExtractor implements AdDisclosureExtractor {
		final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
		List<Disclosure> next = List.of();
		boolean fail;

		@Override
		public List<Disclosure> extract(String caption) {
			calls.add(caption);
			if (fail) {
				throw new IllegalStateException("LLM 호출 실패(테스트)");
			}
			return next;
		}
	}

	private static final class FakeRepo extends BrandPostMetaRepository {
		final Map<String, BrandPostMetaRepository.AdJudgmentState> state = new HashMap<>();
		final Map<String, AdVerdictResult> written = new ConcurrentHashMap<>();

		FakeRepo() {
			super(null);
		}

		@Override
		public Map<String, BrandPostMetaRepository.AdJudgmentState> findAdJudgmentState(
				java.util.Collection<String> shortCodes) {
			Map<String, BrandPostMetaRepository.AdJudgmentState> out = new HashMap<>();
			for (String c : shortCodes) {
				if (state.containsKey(c)) {
					out.put(c, state.get(c));
				}
			}
			return out;
		}

		@Override
		public void updateAdVerdict(String shortCode, AdVerdictResult result, String captionHash,
				java.time.Instant judgedAt) {
			written.put(shortCode, result);
		}
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosureJudgeServiceTest"`
Expected: FAIL — `AdDisclosureJudgeService` 없음 (또한 `BrandPostMetaRepository`의 두 메서드가 `final`이면
override 불가 — Task 7 구현 시 `public`으로 두고 `final` 붙이지 않을 것)

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.ad;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 광고 표기 판정 오케스트레이터(스펙 §5) — Tier0(메타 규칙) → Tier1(사전, {@link AdDisclosurePatterns})
 * → Tier2(LLM 추출, {@link AdDisclosureExtractor}) → Tier3(조합, {@link AdVerdictCombiner}) 순서로
 * 실행하고, 앞 티어에서 확정되면 뒤 티어를 생략한다(Tier1 확정 시 LLM 콜 자체가 안 나간다).
 *
 * <p>후보 선정은 {@code ad_verdict IS NULL OR judged_caption_hash <> md5(caption)}(스펙 §7) — 판정
 * 상태는 {@link BrandPostMetaRepository#findAdJudgmentState}로 배치 조회하고, 해시는 이 클래스가
 * Java {@link MessageDigest}로 계산해 기록·비교 양쪽에 <b>같은 알고리즘</b>을 쓴다(Postgres md5()를
 * 별도로 호출하지 않는다 — 언어 간 해시 불일치 리스크 제거).
 *
 * <p>LLM 콜은 전용 소형 풀(worker, 동시 3~4 — 스펙 §7)로 나간다. 게시물 단위 격리: 한 건의 LLM
 * 실패·파싱 실패가 나머지 게시물 판정에 번지지 않고, verdict는 NULL로 남아 다음 스윕이 재시도한다.
 */
public class AdDisclosureJudgeService {

	private static final Logger log = LoggerFactory.getLogger(AdDisclosureJudgeService.class);

	private final BrandPostMetaRepository metaRepo;
	private final AdDisclosureExtractor extractor;
	private final Executor worker;

	public AdDisclosureJudgeService(BrandPostMetaRepository metaRepo, AdDisclosureExtractor extractor,
			Executor worker) {
		this.metaRepo = metaRepo;
		this.extractor = extractor;
		this.worker = worker;
	}

	public void judgePosts(List<PostInfo> posts) {
		if (posts.isEmpty()) {
			return;
		}
		List<PostInfo> candidates = selectCandidates(posts);
		if (candidates.isEmpty()) {
			return;
		}
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (PostInfo p : candidates) {
			tasks.add(CompletableFuture.runAsync(() -> judgeSafely(p), worker));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
	}

	private List<PostInfo> selectCandidates(List<PostInfo> posts) {
		Set<String> codes = new LinkedHashSet<>();
		for (PostInfo p : posts) {
			codes.add(p.shortCode());
		}
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = metaRepo.findAdJudgmentState(codes);
		return posts.stream().filter(p -> needsJudgment(p, state.get(p.shortCode()))).toList();
	}

	private static boolean needsJudgment(PostInfo p, BrandPostMetaRepository.AdJudgmentState state) {
		if (state == null || state.adVerdict() == null) {
			return true;
		}
		return !md5(caption(p)).equals(state.judgedCaptionHash());
	}

	private void judgeSafely(PostInfo p) {
		try {
			AdVerdictResult result = judgeOne(p);
			metaRepo.updateAdVerdict(p.shortCode(), result, md5(caption(p)), Instant.now());
		} catch (RuntimeException e) {
			// verdict NULL 유지 — 다음 스윕(캡션 해시 재비교)이 자동 재시도한다(스펙 §5).
			log.warn("광고 표기 판정 실패(격리, 다음 스윕 재시도) — {}: {}", p.shortCode(), e.toString());
		}
	}

	/** Tier0→3 순서 실행 — package-private으로 열어 오케스트레이션만 별도 테스트할 수 있게 한다. */
	AdVerdictResult judgeOne(PostInfo p) {
		if (Boolean.TRUE.equals(p.isPaidPartnership())) {
			return new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of());
		}
		String caption = caption(p);
		boolean isReels = "REELS".equalsIgnoreCase(p.contentType());
		if (caption.isBlank()) {
			return isReels
					? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), List.of())
					: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), List.of());
		}
		AdDisclosurePatterns.Match tier1 = AdDisclosurePatterns.findFirstMatch(caption);
		if (tier1 != null) {
			AdPositionRule.Band band = AdPositionRule.evaluate(caption, tier1.start(), tier1.end());
			if (band == AdPositionRule.Band.VISIBLE || band == AdPositionRule.Band.GRAY
					|| band == AdPositionRule.Band.FIRST_HASHTAG) {
				int offset = AdPositionRule.graphemeOffset(caption, tier1.start());
				return new AdVerdictResult("DISCLOSED", "RULE", List.of(),
						List.of(new AdVerdictResult.Evidence(tier1.phrase(), "CLEAR", offset)));
			}
		}
		List<AdDisclosureExtractor.Disclosure> llm = extractor.extract(caption);
		return AdVerdictCombiner.combine(caption, isReels, tier1, llm);
	}

	private static String caption(PostInfo p) {
		return p.caption() == null ? "" : p.caption();
	}

	private static String md5(String s) {
		try {
			byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 알고리즘 부재(도달 불가)", e);
		}
	}
}
```

Task 7의 `BrandPostMetaRepository.findAdJudgmentState`·`updateAdVerdict`가 이미 `public`이고
`final`이 아니므로(Step 3에서 그렇게 작성함) 테스트의 `FakeRepo` 서브클래싱이 그대로 컴파일된다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.ad.AdDisclosureJudgeServiceTest"`
Expected: PASS (8개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/ad/AdDisclosureJudgeService.java \
        monitoring/src/test/java/com/celfit/monitoring/ad/AdDisclosureJudgeServiceTest.java
git commit -m "feat(monitoring): 광고 표기 판정 오케스트레이터(Tier0~3 조율 + 후보 선정)"
```

---

### Task 9: 배선 — AdDisclosureConfig (전용 LLM 풀 + 빈 조립)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/AdDisclosureConfig.java`
- Modify: `monitoring/src/main/resources/application.yml`

- [ ] **Step 1: 구현 (신규 설정 클래스라 선행 테스트 불필요 — 배선은 통합 테스트로 Task 10에서 검증)**

```java
package com.celfit.monitoring.config;

import com.celfit.monitoring.ad.AdDisclosureExtractorGemini;
import com.celfit.monitoring.ad.AdDisclosureJudgeService;
import com.celfit.monitoring.llm.GeminiHttpTransport;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 광고 표기 판정 배선(스펙 2026-08-17 §5·§7) — BrandHashtagConfig와 같은 조립 패턴.
 * LLM 콜은 Hiker 보강 워커 풀(brandEnrichWorkerPool)과 <b>분리된 전용 소형 풀</b>로 나간다 —
 * LLM 지연(초 단위)이 게시자·댓글 수집 처리량을 잠식하지 않게 한다(스펙 §7).
 */
@Configuration
public class AdDisclosureConfig {

	@Bean
	public AdDisclosureExtractorGemini adDisclosureExtractor(
			@Value("${monitoring.gemini.api-key:}") String apiKey,
			@Value("${monitoring.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
			@Value("${monitoring.brand.ad-disclosure.judge-model:gemini-3.1-flash-lite}") String model) {
		return new AdDisclosureExtractorGemini(new GeminiHttpTransport(apiKey, baseUrl), apiKey, model);
	}

	@Bean(name = "adDisclosureWorkerPool")
	public Executor adDisclosureWorkerPool(
			@Value("${monitoring.brand.ad-disclosure.concurrency:4}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-ad-disclosure-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	@Bean
	public AdDisclosureJudgeService adDisclosureJudgeService(BrandPostMetaRepository metaRepo,
			AdDisclosureExtractorGemini extractor, @Qualifier("adDisclosureWorkerPool") Executor worker) {
		return new AdDisclosureJudgeService(metaRepo, extractor, worker);
	}
}
```

- [ ] **Step 2: application.yml에 설정 키 문서화**

`monitoring/src/main/resources/application.yml`의 `brand:` 블록(`hashtag:` 항목 다음)에 추가:

```yaml
    ad-disclosure:
      judge-model: gemini-3.1-flash-lite   # Tier2 문구 추출 모델(스펙 §5) — analytics 전 축과 통일
      concurrency: 4    # Tier2 LLM 전용 풀(enrichWorker와 분리 — 스펙 §7). LLM 지연이 보강 처리량을 잠식하지 않게 함
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :monitoring:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/config/AdDisclosureConfig.java \
        monitoring/src/main/resources/application.yml
git commit -m "feat(monitoring): 광고 표기 판정 배선 — 전용 LLM 풀 + 빈 조립"
```

---

### Task 10: BrandCollectService — 노출 게이트 이동 + 판정 배선

노출 게이트(§8)를 게시자 보강 완료 직후로 당기고, 댓글 수집·광고 판정을 그 뒤 격리된 독립
단계로 뺀다. 기존 파일이 1003줄이라 **기존 테스트를 깨지 않고 확장**하는 데 집중한다.

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`
- Modify: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 — 게이트 타이밍 + 판정 배선 + 판정 실패 격리**

`BrandCollectServiceTest.java`에 아래 3개 테스트와 그 보조 대역(`FakeAdJudge`)을 추가한다(파일
맨 아래, 마지막 `}` 앞):

```java
	// ---------- 광고 표기 판정 배선(2026-08-17 스펙 §7·§8) ----------

	@Test
	void 게시자_보강_직후_정산되고_댓글_실패는_광고_판정을_막지_않는다() {
		commentsCountsFails = true;   // 댓글 게이트 배치 조회가 던져도
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, null);

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");     // 정산은 됐고
		assertThat(adJudge.judged).contains("AAA");       // 광고 판정도 여전히 돈다(댓글과 독립)
	}

	@Test
	void 광고_판정_실패는_격리되고_정산에_영향_없다() {
		FakeAdJudge adJudge = new FakeAdJudge();
		adJudge.fail = true;
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, null);

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");   // 판정 실패가 정산을 막지 않는다
	}

	@Test
	void 게시자_보강_자체가_실패해도_정산은_찍히고_광고_판정도_돈다() {
		failingAuthorIds.add("111");   // ensureAuthors가 예외 없이 격리되는 기존 경로 유지 확인용 대역
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, "111");

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");
		assertThat(adJudge.judged).contains("AAA");
	}

	private BrandCollectService serviceWithAdJudge(FakeAdJudge adJudge) {
		return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
				adJudge, Runnable::run, 10000, 3, 30);
	}

	/** AdDisclosureJudgeService 대역 — 실제 판정 로직 없이 호출 여부·실패 격리만 검증한다. */
	private static final class FakeAdJudge extends com.celfit.monitoring.ad.AdDisclosureJudgeService {
		final List<String> judged = Collections.synchronizedList(new ArrayList<>());
		boolean fail;

		FakeAdJudge() {
			super(null, null, Runnable::run);
		}

		@Override
		public void judgePosts(List<PostInfo> posts) {
			if (fail) {
				throw new IllegalStateException("광고 판정 실패(테스트)");
			}
			posts.forEach(p -> judged.add(p.shortCode()));
		}
	}
```

이 파일의 기존 `post(...)` 헬퍼·`failingAuthorIds`·`commentsCountsFails` 필드는 이미 존재한다
(라인 76 근방) — 새로 만들지 말고 재사용할 것. `service(int)` 헬퍼(라인 326)도 그대로 두고
`serviceWithAdJudge`를 별도로 추가한다(생성자 시그니처가 바뀌므로 기존 `service()` 호출부 전부를
같이 고쳐야 한다 — Step 3에서 처리).

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: FAIL — 컴파일 오류(생성자 시그니처 불일치, `AdDisclosureJudgeService` 미배선)

- [ ] **Step 3: BrandCollectService 수정 — 생성자에 판정 서비스 추가 + enrich() 재배열**

`monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` 수정:

import 추가:
```java
import com.celfit.monitoring.ad.AdDisclosureJudgeService;
```

필드·생성자에 `adJudge` 추가(기존 필드 나열 뒤):
```java
	private final AdDisclosureJudgeService adJudge;
```

생성자 시그니처(기존 `enrichWorker` 파라미터 앞에 삽입 — 기존 호출부와 최대한 가까운 위치로
diff를 최소화):
```java
	public BrandCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			BrandSnapshotRepository snapshots, BrandCommentRepository comments,
			TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
			AdDisclosureJudgeService adJudge,
			@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
			@Value("${monitoring.brand.max-posts-per-sweep:10000}") int maxPostsPerSweep,
			@Value("${monitoring.brand.comment-pages:3}") int commentPages,
			@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.writer = writer;
		this.snapshots = snapshots;
		this.comments = comments;
		this.taggedPosts = taggedPosts;
		this.authors = authors;
		this.adJudge = adJudge;
		this.enrichWorker = enrichWorker;
		this.maxPostsPerSweep = maxPostsPerSweep;
		this.commentPages = commentPages;
		this.authorStaleDays = authorStaleDays;
	}
```

`enrich()` 메서드(276-305) 전체를 아래로 교체:

```java
	/**
	 * enrichment 단계(2026-08-17 노출 게이트 개정 — 스펙 §8) — 정산 마킹(markEnriched)을 게시자
	 * 프로필 보강 완료 <b>직후</b>로 당긴다. 댓글 수집·광고 표기 판정은 노출 게이트 밖으로 빠져
	 * 각자 격리된 독립 단계가 되고, 프론트 폴링으로 나중에 채워진다(프로그레시브 서빙).
	 *
	 * <p>정산 마킹은 여전히 <b>게시자 보강의 성패와 무관하게 무조건</b> 찍는다(finally — 근거는
	 * 아래 finally 블록 주석, 기존 규칙 그대로). 댓글·광고 판정은 정산 이후 단계라 그 실패가
	 * enriched_at에 영향을 주면 안 되므로 각자 여기서 try/catch로 격리한다(기존에는 이 격리가
	 * markEnriched를 감싸는 finally 하나로 우연히 됐지만, 순서가 바뀌면서 명시적으로 필요해졌다).
	 */
	public void enrich(BrandRow brand, List<PostInfo> posts) {
		if (posts.isEmpty()) {
			return;
		}
		try {
			ensureAuthors(brand.id(), posts);
		} finally {
			// 정산 마킹(2026-08-17 노출 게이트 개정 — 스펙 §8) — 게시자 보강 성패와 무관하게 찍는다.
			// was 게이트(enriched_at IS NOT NULL)의 의미가 "게시자 보강 완료 = 노출 가능"으로 좁혀졌다.
			// finally인 이유는 기존과 동일(180일 초과 게시물엔 재열거 백스톱이 없다 — 아래 참조).
			taggedPosts.markEnriched(brand.id(),
					posts.stream().map(PostInfo::shortCode).toList(), Instant.now());
		}
		log.info("브랜드 태그 보강 — {} 게시자 수집·정산 완료({}건 대상)", brand.username(), posts.size());
		// 댓글·광고 판정은 노출 게이트 밖 — 각자 실패해도 위 정산에 영향 없다(프로그레시브 서빙).
		collectCommentsGatedSafely(brand.id(), posts);
		judgeAdDisclosuresSafely(posts);
	}

	/**
	 * 댓글 게이트 격리 래퍼 — 노출 게이트 개정(스펙 §8) 전에는 이 실패가 markEnriched를 감싸는
	 * finally 덕에 우연히 격리됐지만, 이제 markEnriched가 먼저 찍히므로 명시적 격리가 필요하다.
	 */
	private void collectCommentsGatedSafely(long brandId, List<PostInfo> posts) {
		try {
			collectCommentsGated(brandId, posts);
		} catch (RuntimeException e) {
			log.warn("댓글 게이트 실패(격리, 다음 스윕이 워터마크로 재시도) — {}: {}", brandId, e.toString());
		}
	}

	/**
	 * 광고 표기 판정 격리 래퍼(스펙 §7) — 판정 실패가 수집·보강에 영향 없어야 한다. 실제 격리는
	 * {@link AdDisclosureJudgeService#judgePosts}가 게시물 단위로 이미 하지만, 후보 선정 배치
	 * 조회(findAdJudgmentState) 실패 같은 상위 레벨 예외까지 방어하려면 이 래퍼가 한 번 더 필요하다
	 * (collectCommentsGatedSafely와 같은 이유).
	 */
	private void judgeAdDisclosuresSafely(List<PostInfo> posts) {
		try {
			adJudge.judgePosts(posts);
		} catch (RuntimeException e) {
			log.warn("광고 표기 판정 배치 실패(격리, 다음 스윕 재시도): {}", e.toString());
		}
	}
```

- [ ] **Step 4: 기존 생성자 호출부 전부 업데이트**

`monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java` 또는
`BrandCollectService`를 생성하는 `@Bean` 정의를 찾아(보통 `@Component`라 Spring이 자동 생성 —
빈 정의가 없으면 이 스텝은 스킵) `AdDisclosureJudgeService` 주입만 추가되면 되고 별도 수정 불필요.

Run: `grep -rn "new BrandCollectService(" monitoring/src/main`

수동 `new BrandCollectService(...)` 호출이 프로덕션 코드에 없으면(생성자 주입만 있으면) 이 스텝은
완료. 있다면 새 파라미터를 채운다.

`BrandCollectServiceTest.java`의 기존 `service(int maxPostsPerSweep)` 헬퍼(라인 326-329)도
수정한다:

```java
	private BrandCollectService service(int maxPostsPerSweep) {
		return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
				new FakeAdJudge(), Runnable::run, maxPostsPerSweep, 3, 30);
	}
```

라인 993 근방의 두 번째 `new BrandCollectService(...)` 호출(동시성 테스트)도 같은 방식으로
`new FakeAdJudge()`를 끼워 넣는다.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: PASS 전체(기존 ~40여 개 + 신규 3개)

- [ ] **Step 6: monitoring 모듈 전체 테스트로 회귀 확인**

Run: `./gradlew :monitoring:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java \
        monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 노출 게이트를 게시자 보강 직후로 이동 + 광고 표기 판정 배선"
```

---

### Task 11: 시딩 계정 저장소 — BrandSeededAccountRepository

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/BrandSeededAccountRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandSeededAccountRepositoryTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandSeededAccountRepositoryTest {

	JdbcTemplate db;
	BrandSeededAccountRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandSeededAccountRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	@Test
	void 추가와_조회() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		assertThat(repo.findUsernames(brandId)).containsExactlyInAnyOrder("influencer1", "influencer2");
	}

	@Test
	void 추가는_멱등() {
		repo.add(brandId, List.of("influencer1"));
		repo.add(brandId, List.of("influencer1"));
		assertThat(repo.findUsernames(brandId)).containsExactly("influencer1");
	}

	@Test
	void 전체_교체() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.replace(brandId, List.of("influencer2", "influencer3"));
		assertThat(repo.findUsernames(brandId)).containsExactlyInAnyOrder("influencer2", "influencer3");
	}

	@Test
	void 단건_삭제() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.delete(brandId, "influencer1");
		assertThat(repo.findUsernames(brandId)).containsExactly("influencer2");
	}

	@Test
	void 전체_삭제() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.deleteAll(brandId);
		assertThat(repo.findUsernames(brandId)).isEmpty();
	}
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandSeededAccountRepositoryTest"`
Expected: FAIL — 클래스 없음

- [ ] **Step 3: 구현**

```java
package com.celfit.monitoring.store;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시딩(협업) 계정 등록(스펙 §6) — 브랜드가 등록한 시딩 인플루언서 계정. 판정 결과와 분리 저장하고
 * 조회 시 조인으로 계산한다(목록을 나중에 등록·수정해도 재판정 불필요). tombstone 없음(하드 삭제) —
 * 이 테이블은 재등록 시 자동 유도되는 값이 없어(태그·제외 문자열과 달리 유저가 직접 관리하는
 * 목록뿐) 되살릴 대상이 없다.
 */
@Repository
public class BrandSeededAccountRepository {

	private final JdbcTemplate db;

	public BrandSeededAccountRepository(JdbcTemplate db) {
		this.db = db;
	}

	public List<String> findUsernames(long brandId) {
		return db.queryForList(
				"SELECT username FROM brand_seeded_account WHERE brand_id = ? ORDER BY created_at, username",
				String.class, brandId);
	}

	public void add(long brandId, Collection<String> usernames) {
		for (String username : usernames) {
			db.update("INSERT INTO brand_seeded_account (brand_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, username);
		}
	}

	/** 전체 교체 — 목록에 없는 기존 행은 하드 삭제한다(tombstone 불필요 — 클래스 주석 참조). */
	@Transactional
	public void replace(long brandId, List<String> usernames) {
		Set<String> next = new LinkedHashSet<>(usernames);
		for (String existing : findUsernames(brandId)) {
			if (!next.contains(existing)) {
				db.update("DELETE FROM brand_seeded_account WHERE brand_id = ? AND username = ?",
						brandId, existing);
			}
		}
		add(brandId, next);
	}

	public void delete(long brandId, String username) {
		db.update("DELETE FROM brand_seeded_account WHERE brand_id = ? AND username = ?", brandId, username);
	}

	public void deleteAll(long brandId) {
		db.update("DELETE FROM brand_seeded_account WHERE brand_id = ?", brandId);
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandSeededAccountRepositoryTest"`
Expected: PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandSeededAccountRepository.java \
        monitoring/src/test/java/com/celfit/monitoring/store/BrandSeededAccountRepositoryTest.java
git commit -m "feat(monitoring): 시딩 계정 등록 저장소"
```

---

### Task 12: 시딩 계정 등록 API — BrandController 확장

기존 태그·제외 문자열과 같은 5종 REST 모양(GET/PUT/POST/DELETE단건/DELETE전체)을 따른다.

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandControllerTest.java`에 추가(기존 `StubService`·`activeBrand` 패턴 재사용 — 파일 구조를
먼저 열어 기존 `hashtags` 필드 대역 이름을 확인할 것):

```java
	// ---------- 시딩 계정(스펙 §6) ----------

	private static final class StubSeeded extends com.celfit.monitoring.store.BrandSeededAccountRepository {
		List<String> usernames = List.of();
		List<String> replaced;
		List<String> added;
		String deleted;
		boolean deletedAll;

		StubSeeded() {
			super(null);
		}

		@Override
		public List<String> findUsernames(long brandId) {
			return usernames;
		}

		@Override
		public void replace(long brandId, List<String> usernames) {
			this.replaced = usernames;
		}

		@Override
		public void add(long brandId, java.util.Collection<String> usernames) {
			this.added = List.copyOf(usernames);
		}

		@Override
		public void delete(long brandId, String username) {
			this.deleted = username;
		}

		@Override
		public void deleteAll(long brandId) {
			this.deletedAll = true;
		}
	}

	@Test
	void 시딩_계정_조회() throws Exception {
		StubSeeded seeded = new StubSeeded();
		seeded.usernames = List.of("influencer1");
		BrandRow row = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 12);
		BrandRepository brandsStub = stubBrands(row);
		MockMvc mvc = mvc(new StubService(), brandsStub, null, seeded);

		mvc.perform(get("/api/brands/brandx/seeded-accounts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usernames[0]").value("influencer1"));
	}

	@Test
	void 시딩_계정_전체_교체() throws Exception {
		StubSeeded seeded = new StubSeeded();
		BrandRow row = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 12);
		MockMvc mvc = mvc(new StubService(), stubBrands(row), null, seeded);

		mvc.perform(put("/api/brands/brandx/seeded-accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"usernames\":[\"influencer1\",\"Influencer2\"]}"))
				.andExpect(status().isNoContent());

		assertThat(seeded.replaced).containsExactlyInAnyOrder("influencer1", "influencer2");
	}

	@Test
	void 시딩_계정_단건_삭제() throws Exception {
		StubSeeded seeded = new StubSeeded();
		BrandRow row = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 12);
		MockMvc mvc = mvc(new StubService(), stubBrands(row), null, seeded);

		mvc.perform(delete("/api/brands/brandx/seeded-accounts/influencer1"))
				.andExpect(status().isNoContent());

		assertThat(seeded.deleted).isEqualTo("influencer1");
	}

	@Test
	void 미존재_브랜드는_404() throws Exception {
		MockMvc mvc = mvc(new StubService(), stubBrands(null), null, new StubSeeded());
		mvc.perform(get("/api/brands/nope/seeded-accounts")).andExpect(status().isNotFound());
	}
```

이 테스트가 참조하는 `mvc(...)`·`stubBrands(...)` 헬퍼가 기존 파일에 이미 있는지 먼저 확인한다
(`BrandControllerTest.java`를 열어 `MockMvcBuilders.standaloneSetup` 호출부와 그 인자 구성을
그대로 따라간다 — 기존 헬퍼가 `BrandController` 생성자를 감싸고 있을 것이므로, 생성자 파라미터가
늘어나면 Step 3에서 그 헬퍼도 함께 고친다).

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.web.BrandControllerTest"`
Expected: FAIL — 컴파일 오류(엔드포인트·생성자 파라미터 없음)

- [ ] **Step 3: BrandController 수정**

생성자에 `BrandSeededAccountRepository seededAccounts` 필드 추가, 클래스 끝(마지막 `}` 전)에
엔드포인트 5종 추가:

```java
	/** 시딩 계정 목록 — GET 응답·PUT 요청 바디 공용(태그·제외 문자열과 같은 계약 모양, 스펙 §6). */
	public record SeededAccountsBody(List<String> usernames) {}
```

```java
	@GetMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> seededAccounts(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		return ResponseEntity.ok(new SeededAccountsBody(seededAccounts.findUsernames(row.get().id())));
	}

	@PutMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> replaceSeededAccounts(@PathVariable String username,
			@RequestBody SeededAccountsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.replace(row.get().id(), normalize(body.usernames()));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> addSeededAccounts(@PathVariable String username,
			@RequestBody(required = false) SeededAccountsBody body) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.add(row.get().id(), normalize(body == null ? null : body.usernames()));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{username}/seeded-accounts/{seededUsername}")
	public ResponseEntity<?> deleteSeededAccount(@PathVariable String username,
			@PathVariable String seededUsername) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		String normalized = normalizeItem(seededUsername);
		if (normalized != null) {
			seededAccounts.delete(row.get().id(), normalized);
		}
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{username}/seeded-accounts")
	public ResponseEntity<?> deleteAllSeededAccounts(@PathVariable String username) {
		Optional<BrandRow> row = activeBrand(username);
		if (row.isEmpty()) {
			return brandNotFound();
		}
		seededAccounts.deleteAll(row.get().id());
		return ResponseEntity.noContent().build();
	}
```

기존 `normalize(List<String>)`(trim·소문자·blank 제거·중복 제거, 라인 253 근방)를 그대로
재사용한다 — 시딩 계정 username도 같은 정규화 규칙이 맞다(대소문자 무시 비교가 was 조인에서도
필요하므로 저장 단계에서 소문자로 통일).

생성자 시그니처에 `BrandSeededAccountRepository seededAccounts` 파라미터 추가하고 필드 대입.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.web.BrandControllerTest"`
Expected: PASS 전체(기존 + 신규 4개)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java \
        monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java
git commit -m "feat(monitoring): 시딩 계정 등록 API(GET/PUT/POST/DELETE)"
```

---

### Task 13: was — BrandReadRepository 광고 필드 + 시딩 조회

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`

- [ ] **Step 1: BrandPostMetaRow에 필드 추가 + SQL 확장**

`findPostMeta` 메서드의 SELECT에 컬럼 3개 추가(jsonb는 `::text` 캐스트로 문자열째 읽는다 —
AlarmEventRepository 관용구):

```java
	public List<BrandPostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				       video_url, video_duration, is_paid_partnership, image_object_path,
				       ad_verdict, ad_violations::text AS ad_violations, ad_evidence::text AS ad_evidence
				FROM brand_post_meta
				WHERE short_code IN (:shortCodes)
				""")
				.param("shortCodes", shortCodes)
				.query(BrandPostMetaRow.class)
				.list();
	}
```

`BrandPostMetaRow` record에 필드 추가:

```java
	/**
	 * brand_post_meta 1행. isPaidPartnership null = 응답 키 부재(판정 unknown 근거).
	 * imageObjectPath는 monitoring 자체 썸네일 아카이브 결과 — null이면 원본 CDN URL 폴백.
	 * adVerdict null = 미판정(광고 표기 판정 스펙 §4). adViolationsJson·adEvidenceJson은
	 * jsonb를 텍스트로 읽은 원문 — 파싱은 {@link BrandPostAssembler}가 한다(null 가능).
	 */
	public record BrandPostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String videoUrl, Double videoDuration,
			Boolean isPaidPartnership, String imageObjectPath, String adVerdict, String adViolationsJson,
			String adEvidenceJson) {
	}
```

- [ ] **Step 2: 시딩 계정 조회 메서드 추가**

클래스 끝(마지막 `}` 전)에:

```java
	/**
	 * 시딩(협업) 계정 username 전체(소문자 정규화, monitoring BrandController가 저장 시 이미
	 * 소문자로 정규화하지만 방어적으로 한 번 더) — was 조인 계산 재료(스펙 §6, §9 seededAuthor).
	 */
	public List<String> findSeededUsernames(long brandId) {
		return jdbc.sql("SELECT username FROM brand_seeded_account WHERE brand_id = :brandId")
				.param("brandId", brandId)
				.query(String.class)
				.list();
	}
```

- [ ] **Step 3: 컴파일 확인 (was 모듈은 findPostMeta·BrandPostMetaRow 사용처가 BrandPostAssembler
하나뿐이라 이 시점엔 컴파일 에러가 난다 — Task 15에서 해소)**

Run: `./gradlew :was:compileJava` (실패 예상 — BrandPostAssembler가 옛 레코드 생성자를 쓰고 있어서. Task 15 완료 후 재확인)

- [ ] **Step 4: 커밋 (Task 15와 함께 묶어도 무방 — 컴파일이 깨진 채로 커밋하지 않도록 Task 15까지 마친 뒤 커밋)**

이 태스크는 커밋하지 않고 Task 15에서 함께 커밋한다(중간 상태가 컴파일 불가라 별도 커밋은
`./gradlew :was:test` CI를 항상 깨뜨린다 — 프로젝트 관례상 허용되지 않는다).

---

### Task 14: was — BrandPostResponse 필드 확장

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java`

- [ ] **Step 1: record에 필드 4종 추가 + withSponsorship 시그니처 갱신**

```java
package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.v1.monitoring.TrackingItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record BrandPostResponse(
		String id,
		String brandAccountId,
		@Schema(allowableValues = {"tagged", "direct"}) String source,
		String postUrl,
		String shortcode,
		@Schema(allowableValues = {"reels", "feed"}) String contentType,
		String takenAt,
		String caption,
		String thumbnailUrl,
		String videoUrl,
		Double videoDuration,
		String authorProfileUrl,
		String authorUsername,
		String authorFullName,
		String authorProfilePicUrl,
		boolean authorIsVerified,
		Long authorFollowers,
		@Schema(allowableValues = {"sponsored", "organic", "unknown"}) String sponsorship,
		Boolean isPaidPartnership,
		String trackingStatus,
		String trackingStartedAt,
		String trackingEndedAt,
		TrackingItemResponse.SnapshotResponse latestSnapshot,
		List<TrackingItemResponse.SnapshotResponse> snapshots,
		Long commentsTotal,
		boolean commentsHidden,
		long commentsCollectedCount,
		List<TrackingItemResponse.PostCommentResponse> recentComments,
		List<String> campaignIds,
		String createdAt,
		String updatedAt,
		// ---- 광고 표기 판정(2026-08-17 스펙 §9) ----
		@Schema(allowableValues = {"DISCLOSED", "NOT_DISCLOSED", "INSUFFICIENT", "UNCERTAIN"}) String adDisclosure,
		List<String> adViolations,
		List<AdEvidence> adEvidence,
		boolean seededAuthor) {

	/** 판정 근거 문구 1건 — monitoring ad_evidence jsonb 원소와 1:1(스펙 §4). */
	public record AdEvidence(String phrase, String category, int offset) {}

	/**
	 * 협찬 판정만 교체한 사본 — shortcode 병합에서 direct 본체를 유지한 채 tagged의
	 * {@code is_paid_partnership} 관측만 승격시키는 데 쓴다(정보 손실 방지, 스펙 §6-1).
	 * 광고 표기 필드는 tagged 전용 정보라 direct에는 애초에 값이 없으므로 이 사본에서도 유지한다.
	 */
	public BrandPostResponse withSponsorship(String sponsorship, Boolean isPaidPartnership) {
		return new BrandPostResponse(id, brandAccountId, source, postUrl, shortcode, contentType, takenAt,
				caption, thumbnailUrl, videoUrl, videoDuration, authorProfileUrl, authorUsername, authorFullName,
				authorProfilePicUrl, authorIsVerified, authorFollowers, sponsorship, isPaidPartnership,
				trackingStatus, trackingStartedAt, trackingEndedAt, latestSnapshot, snapshots, commentsTotal,
				commentsHidden, commentsCollectedCount, recentComments, campaignIds, createdAt, updatedAt,
				adDisclosure, adViolations, adEvidence, seededAuthor);
	}
}
```

- [ ] **Step 2: 컴파일은 아직 실패 — Task 15에서 생성 호출부(taggedPost·directPost)를 고친 뒤 확인**

이 태스크도 Task 15와 함께 커밋한다(Task 13과 같은 이유).

---

### Task 15: was — BrandPostAssembler 배선 (광고 필드·seededAuthor 조인·노출 토글)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (Mockito 기반, 기존 파일 관용구 그대로)**

`BrandPostAssemblerTest.java`에 추가:

```java
	// ---------- 광고 표기 판정 배선(2026-08-17 스펙 §9) ----------

	@Test
	void 광고_판정_필드가_응답에_실린다() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		org.mockito.Mockito.when(repository.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L)));
		org.mockito.Mockito.when(repository.findPostMeta(org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]")));
		org.mockito.Mockito.when(repository.findSeededUsernames(42L)).thenReturn(List.of("creator1"));

		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, true);
		var posts = assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
			assertThat(post.adEvidence()).singleElement()
					.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
			assertThat(post.seededAuthor()).isTrue();
		});
	}

	@Test
	void 노출_토글이_꺼지면_광고_필드는_전부_비노출() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		org.mockito.Mockito.when(repository.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L)));
		org.mockito.Mockito.when(repository.findPostMeta(org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[]")));

		// 토글 off — findSeededUsernames를 호출조차 하지 않는다(드라이런 중 불필요한 조회 방지)
		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, false);
		var posts = assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isNull();
			assertThat(post.adViolations()).isEmpty();
			assertThat(post.adEvidence()).isEmpty();
			assertThat(post.seededAuthor()).isFalse();
		});
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findSeededUsernames(org.mockito.ArgumentMatchers.anyLong());
	}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"`
Expected: FAIL — 컴파일 오류(생성자 4번째 인자 없음, 필드 없음)

- [ ] **Step 3: BrandPostAssembler 수정**

import 추가:
```java
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
```

필드·생성자 수정(기존 필드 뒤에 추가):
```java
	private final boolean exposeAdDisclosure;
	private static final ObjectMapper OM = new ObjectMapper();

	public BrandPostAssembler(BrandReadRepository brandReadRepository,
			BrandDirectPostRepository directPostRepository, TrackingItemAssembler trackingItemAssembler,
			@org.springframework.beans.factory.annotation.Value(
					"${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.brandReadRepository = brandReadRepository;
		this.directPostRepository = directPostRepository;
		this.trackingItemAssembler = trackingItemAssembler;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}
```

`assembleTagged()`(기존 라인 136-161) 안, `metaByCode` 계산 다음에 시딩 계정 조회 추가(토글이
꺼져 있으면 조회 자체를 생략 — 위 테스트가 검증하는 배선):

```java
		Set<String> seededUsernames = !exposeAdDisclosure ? Set.of()
				: brandReadRepository.findSeededUsernames(account.id()).stream()
						.map(u -> u.toLowerCase(Locale.ROOT))
						.collect(Collectors.toCollection(LinkedHashSet::new));
```

`taggedPost(...)` 호출부(`posts.stream().map(p -> taggedPost(...))`)에 `seededUsernames` 인자를
추가하고, `taggedPost` 정적 메서드 시그니처·본문을 수정한다:

```java
	static BrandPostResponse taggedPost(long brandId, BrandTaggedPostRow post, BrandPostMetaRow meta,
			AuthorRow author, List<BrandSnapshotRow> snapshotRows, List<BrandCommentRow> commentRows,
			OffsetDateTime lastSweptAt, boolean exposeAdDisclosure, Set<String> seededUsernames) {
		String contentType = contentTypeOf(meta == null ? null : meta.contentType());
		List<TrackingItemResponse.SnapshotResponse> snapshots =
				snapshotRows.stream().map(BrandPostAssembler::snapshotOf).toList();
		List<TrackingItemResponse.PostCommentResponse> comments = commentRows.stream()
				.map(BrandPostAssembler::commentOf).filter(Objects::nonNull).toList();
		String username = author != null ? author.username() : post.authorUsername();
		String firstSeenAt = KstTimestamps.toKstIso(post.firstSeenAt());
		boolean exposeAd = exposeAdDisclosure && meta != null;
		String adDisclosure = exposeAd ? meta.adVerdict() : null;
		List<String> adViolations = exposeAd ? parseViolations(meta.adViolationsJson()) : List.of();
		List<BrandPostResponse.AdEvidence> adEvidence = exposeAd ? parseEvidence(meta.adEvidenceJson()) : List.of();
		boolean seededAuthor = exposeAdDisclosure && username != null
				&& seededUsernames.contains(username.toLowerCase(Locale.ROOT));

		return new BrandPostResponse(
				post.shortCode(),
				String.valueOf(brandId),
				SOURCE_TAGGED,
				postUrl(contentType, post.shortCode()),
				post.shortCode(),
				contentType,
				KstTimestamps.toKstIso(post.takenAt()),
				meta == null ? null : meta.caption(),
				meta == null ? null : resolveImageUrl(meta.imageObjectPath(), meta.thumbnailUrl()),
				meta == null ? null : meta.videoUrl(),
				meta == null ? null : meta.videoDuration(),
				username == null ? null : PROFILE_URL_PREFIX + username + "/",
				username,
				author == null ? null : author.fullName(),
				author == null ? null : resolveImageUrl(author.imageObjectPath(), author.profilePicUrl()),
				author != null && Boolean.TRUE.equals(author.isVerified()),
				author == null ? null : author.followers(),
				BrandSponsorshipClassifier.classify(meta == null ? null : meta.isPaidPartnership(),
						meta == null ? null : meta.caption()),
				meta == null ? null : meta.isPaidPartnership(),
				TRACKING,
				firstSeenAt,
				null,
				latestOf(snapshots),
				snapshots,
				commentsTotal(snapshots),
				commentsHidden(snapshots),
				comments.size(),
				comments,
				List.of(),
				firstSeenAt,
				KstTimestamps.toKstIso(lastSweptAt),
				adDisclosure,
				adViolations,
				adEvidence,
				seededAuthor);
	}

	/** ad_violations jsonb 텍스트 → 코드 배열. null·빈 배열은 빈 목록. */
	private static List<String> parseViolations(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		JsonNode node = OM.readTree(json);
		List<String> out = new java.util.ArrayList<>();
		node.forEach(n -> out.add(n.asString()));
		return out;
	}

	/** ad_evidence jsonb 텍스트 → 근거 문구 배열. null·빈 배열은 빈 목록. */
	private static List<BrandPostResponse.AdEvidence> parseEvidence(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		JsonNode node = OM.readTree(json);
		List<BrandPostResponse.AdEvidence> out = new java.util.ArrayList<>();
		node.forEach(n -> out.add(new BrandPostResponse.AdEvidence(
				n.path("phrase").asString(), n.path("category").asString(), n.path("offset").asInt())));
		return out;
	}
```

`directPost(...)` 호출부에도 새 필드 4개를 채운다(direct 산지는 광고 판정 정보가 없으므로
`null, List.of(), List.of(), false`):

```java
				item.campaignId() == null ? List.of() : List.of(item.campaignId()),
				item.registeredAt(),
				KstTimestamps.toKstIso(legacyLastCollectedAt),
				null, List.of(), List.of(), false);
```

`assembleTagged()`의 `.map(p -> taggedPost(...))` 호출도 새 인자를 넘기도록 수정:

```java
		return posts.stream()
				.map(p -> taggedPost(account.id(), p, metaByCode.get(p.shortCode()), authorsByPost.get(p.shortCode()),
						snapshotsByCode.getOrDefault(p.shortCode(), List.of()),
						commentsByCode.getOrDefault(p.shortCode(), List.of()), account.lastSweptAt(),
						exposeAdDisclosure, seededUsernames))
				.toList();
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblerTest"`
Expected: PASS 전체(기존 + 신규 2개)

- [ ] **Step 5: was 모듈 컴파일 확인 (Task 13·14의 잔여 컴파일 오류 해소 확인)**

Run: `./gradlew :was:compileJava :was:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋 (Task 13·14·15 통합 커밋)**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java
git commit -m "feat(was): 브랜드 게시물 응답에 광고 표기 판정·시딩 계정 필드 배선"
```

---

### Task 16: was — MonitoringCommandClient 시딩 계정 CRUD 프록시

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (MockRestServiceServer 관용구)**

```java
	// ---------- 시딩 계정(2026-08-17 스펙 §6) ----------

	@Test
	void 시딩_계정_조회() {
		server.expect(requestTo(BASE + "/api/brands/brandx/seeded-accounts"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"usernames\":[\"influencer1\"]}", MediaType.APPLICATION_JSON));

		assertThat(client.getSeededAccounts("brandx")).containsExactly("influencer1");
		server.verify();
	}

	@Test
	void 시딩_계정_전체_교체() {
		server.expect(requestTo(BASE + "/api/brands/brandx/seeded-accounts"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.usernames[0]").value("influencer1"))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.putSeededAccounts("brandx", List.of("influencer1"));
		server.verify();
	}

	@Test
	void 시딩_계정_단건_삭제() {
		server.expect(requestTo(BASE + "/api/brands/brandx/seeded-accounts/influencer1"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.deleteSeededAccount("brandx", "influencer1");
		server.verify();
	}
```

파일 상단 import에 `MediaType`·`HttpStatus`(이미 있을 가능성 높음 — 없으면 추가)를 확인한다.

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCommandClientTest"`
Expected: FAIL — 메서드 없음

- [ ] **Step 3: 구현 — MonitoringCommandClient에 메서드 5종 추가**

클래스 끝(마지막 `private <T> T exchange` 전, 기존 해시태그 메서드들 다음)에:

```java
	/** 시딩 계정 조회(monitoring BrandController §6 프록시) — 브랜드가 등록한 협업 인플루언서 목록. */
	public List<String> getSeededAccounts(String username) {
		SeededAccountsBody body = exchange(() -> restClient.get()
				.uri("/api/brands/{username}/seeded-accounts", username)
				.retrieve().body(SeededAccountsBody.class));
		return body == null || body.usernames() == null ? List.of() : body.usernames();
	}

	/** 시딩 계정 전체 교체 — usernames는 monitoring이 정규화(trim·소문자·중복 제거) 후 저장. */
	public void putSeededAccounts(String username, List<String> usernames) {
		exchange(() -> restClient.put().uri("/api/brands/{username}/seeded-accounts", username)
				.body(new SeededAccountsBody(usernames)).retrieve().toBodilessEntity());
	}

	/** 시딩 계정 단건·다건 추가 — 무해한 no-op(빈 목록 허용). */
	public void addSeededAccounts(String username, List<String> usernames) {
		exchange(() -> restClient.post().uri("/api/brands/{username}/seeded-accounts", username)
				.body(new SeededAccountsBody(usernames)).retrieve().toBodilessEntity());
	}

	/** 시딩 계정 단건 삭제 — 없어도 204(멱등). */
	public void deleteSeededAccount(String username, String seededUsername) {
		exchange(() -> restClient.delete()
				.uri("/api/brands/{username}/seeded-accounts/{seededUsername}", username, seededUsername)
				.retrieve().toBodilessEntity());
	}

	/** 시딩 계정 전체 삭제. */
	public void deleteAllSeededAccounts(String username) {
		exchange(() -> restClient.delete().uri("/api/brands/{username}/seeded-accounts", username)
				.retrieve().toBodilessEntity());
	}
```

record 목록에 추가(기존 `HashtagTagsBody` record 근처):
```java
	/** monitoring BrandController.SeededAccountsBody와 동형 — GET 응답·PUT 요청 바디 공용. */
	record SeededAccountsBody(List<String> usernames) {
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringCommandClientTest"`
Expected: PASS 전체(기존 + 신규 3개)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java \
        was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java
git commit -m "feat(was): 시딩 계정 CRUD monitoring 프록시"
```

---

### Task 17: was — V1BrandAccountService·V1BrandAccountsController 시딩 계정 엔드포인트

기존 hashtag-tags 5종과 완전히 같은 모양 — `V1BrandAccountService`에 위임 메서드 5개,
`V1BrandAccountsController`에 엔드포인트 5개.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java`

이 두 파일은 인증·소유권 검증이 필요한 사용자 대면 API라, 기존 hashtag-tags 테스트가 있다면
그 패턴을 그대로 복제해 테스트를 추가한다 — 없다면(컨트롤러 슬라이스 테스트 부재) 이 태스크는
구현만 하고 Task 12·16의 테스트가 이미 아래로 흐르는 로직(monitoring 계약·프록시)을 커버하므로
스킵 가능. 아래 grep으로 확인한다.

- [ ] **Step 1: 기존 hashtag-tags 컨트롤러 테스트 존재 확인**

Run: `find was/src/test/java -iname "*V1BrandAccount*"`

결과가 있으면 그 파일의 `hashtagTags` 관련 테스트 메서드를 복제해 `seededAccounts`용으로
추가한다(Step 2). 결과가 없으면 Step 2를 생략하고 Step 3(구현)만 진행한다.

- [ ] **Step 2 (조건부): 컨트롤러 테스트 파일이 있으면 시딩 계정 케이스 추가**

기존 `해시태그_태그_조회` 유사 테스트를 복제해 `/seeded-accounts` 경로로 바꾼 버전을 추가한다
(구체 코드는 발견된 파일의 기존 패턴을 그대로 따를 것 — 이 계획은 파일 부재 여부를 사전에 알 수
없어 템플릿만 지정한다).

- [ ] **Step 3: V1BrandAccountService에 위임 메서드 5개 추가**

기존 `getHashtagTags`~`deleteAllHashtagTags`(라인 216-251) 바로 뒤에 추가:

```java
	/** 시딩 계정 조회(스펙 §6) — 소유권은 단건 폴링과 동일(남의 brandId는 403). */
	public List<String> getSeededAccounts(long userId, long brandId) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		return commandClient.getSeededAccounts(username);
	}

	/** 시딩 계정 전체 교체 — usernames null은 빈 목록으로 접어 monitoring에 위임. */
	public void putSeededAccounts(long userId, long brandId, List<String> usernames) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		commandClient.putSeededAccounts(username, usernames == null ? List.of() : usernames);
	}

	/** 시딩 계정 단건·다건 추가. */
	public void addSeededAccounts(long userId, long brandId, List<String> usernames) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		commandClient.addSeededAccounts(username, usernames == null ? List.of() : usernames);
	}

	/** 시딩 계정 단건 삭제. */
	public void deleteSeededAccount(long userId, long brandId, String seededUsername) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		commandClient.deleteSeededAccount(username, seededUsername);
	}

	/** 시딩 계정 전체 삭제. */
	public void deleteAllSeededAccounts(long userId, long brandId) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		commandClient.deleteAllSeededAccounts(username);
	}
```

- [ ] **Step 4: V1BrandAccountsController에 엔드포인트 5개 추가**

기존 `deleteAllHashtagTags`(라인 184-189) 바로 뒤에 추가:

```java
	/** 시딩 계정 조회(스펙 §6, monitoring BrandController 프록시) — 소유 브랜드만. */
	@GetMapping("/{accountId}/seeded-accounts")
	public ApiResponse<SeededAccountsResponse> getSeededAccounts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		List<String> usernames = service.getSeededAccounts(principal.getUserId(), parseAccountId(accountId));
		return ApiResponse.ok(new SeededAccountsResponse(usernames));
	}

	/** 시딩 계정 전체 교체 — 204. */
	@PutMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> putSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) SeededAccountsRequest body) {
		List<String> usernames = body == null ? null : body.usernames();
		service.putSeededAccounts(principal.getUserId(), parseAccountId(accountId), usernames);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 단건·다건 추가 — 204. */
	@PostMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> addSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) SeededAccountsRequest body) {
		List<String> usernames = body == null ? null : body.usernames();
		service.addSeededAccounts(principal.getUserId(), parseAccountId(accountId), usernames);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 단건 삭제(DELETE {seededUsername} 계약) — 204(없어도 멱등). */
	@DeleteMapping("/{accountId}/seeded-accounts/{seededUsername}")
	public ResponseEntity<Void> deleteSeededAccount(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @PathVariable String seededUsername) {
		service.deleteSeededAccount(principal.getUserId(), parseAccountId(accountId), seededUsername);
		return ResponseEntity.noContent().build();
	}

	/** 시딩 계정 전체 삭제 — 204. */
	@DeleteMapping("/{accountId}/seeded-accounts")
	public ResponseEntity<Void> deleteAllSeededAccounts(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId) {
		service.deleteAllSeededAccounts(principal.getUserId(), parseAccountId(accountId));
		return ResponseEntity.noContent().build();
	}
```

record 목록에 추가(기존 `HashtagTagsRequest` 근처):
```java
	/** 시딩 계정 교체 요청 본문 — usernames null은 서비스에서 빈 목록으로 접는다. */
	public record SeededAccountsRequest(List<String> usernames) {
	}

	/** 시딩 계정 조회 응답 본문. */
	public record SeededAccountsResponse(List<String> usernames) {
	}
```

- [ ] **Step 5: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: (조건부) 테스트 통과 확인**

Step 1에서 기존 컨트롤러 테스트 파일을 찾았다면:

Run: `./gradlew :was:test --tests "<찾은 테스트 클래스>"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java
git commit -m "feat(was): 시딩 계정 등록 사용자 API(브랜드 계정 표면 확장)"
```

---

### Task 18: was — application.yml 노출 토글 문서화

**Files:**
- Modify: `was/src/main/resources/application.yml`

- [ ] **Step 1: 설정 키 추가**

`monitoring:` 블록(라인 84 근방, `enabled: false` 다음)에 추가:

```yaml
  brand:
    ad-disclosure:
      expose: false   # 광고 표기 판정 was 노출 토글(스펙 §10-3) — 드라이런(기존 게시물 전량 판정 +
                       # verdict 분포 검토) 완료 전까지 false. true 전환이 곧 FE 노출 개통이다.
```

- [ ] **Step 2: 컴파일·부팅 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/resources/application.yml
git commit -m "chore(was): 광고 표기 판정 노출 토글 기본값 off로 문서화"
```

---

### Task 19: 전체 모듈 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: monitoring 모듈 전체 테스트**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: was 모듈 전체 테스트**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 마이그레이션 안전성·경합 검사(있으면)**

Run: `find . -name "check-migration-safety.sh" -exec {} \; 2>/dev/null || echo "스크립트 없음 — CI migration-guard가 대신 검사"`

- [ ] **Step 4: 최종 diff 리뷰**

Run: `git log --oneline develop..HEAD` — 이 계획의 커밋 19개(Task 1~18, 일부는 통합 커밋)가
순서대로 쌓였는지 확인.

이 태스크는 결과만 확인하고 별도 커밋을 만들지 않는다.

---

## 이 계획에 포함하지 않은 것 (별도 태스크 — 스펙 §10-2·§11)

- **골드셋 평가**: 실데이터 캡션 ~200건 수동 라벨링 + 정확도 측정(NOT_DISCLOSED 오탐률 핵심 지표).
  샘플링 SQL과 라벨 CSV 대조 러너는 이 판정 파이프라인이 배포된 뒤, 실캡션이 쌓인 시점에 별도
  세션에서 작성한다(위치 규칙 경계값 캘리브레이션도 이 단계 산출물).
- **드라이런 실행 자체**: Task 9~10 배포 후 기존 게시물 전량이 다음 스윕들에서 자연히 재판정된다
  (캡션 해시가 전부 미판정 상태이므로 최초 1회는 브랜드별 전량이 후보가 된다) — 별도 배치 스크립트
  불필요. verdict 분포 확인은 운영 SQL 조회로 충분하다(`SELECT ad_verdict, count(*) FROM
  brand_post_meta GROUP BY ad_verdict`).
- **was 노출 개통**: Task 18에서 토글을 만들었을 뿐, `true`로 바꾸는 것은 드라이런 검토 후 별도
  세션의 설정 변경(운영 `application.yml` 또는 env)이다.

---

## Self-Review 기록 (skill 체크리스트 수행 결과)

**1. 스펙 커버리지** — §1~§11 전 섹션 확인:
- §4(데이터 모델) → Task 1. §5(4-Tier 판정, 조합표, 위치 규칙, 첫 해시태그 예외) → Task 2~8.
- §6(시딩 계정) → Task 11·12·16·17. §7(실행 위치·동시성, 전용 풀) → Task 9·10.
- §8(노출 게이트 이동) → Task 10. §9(was API 4필드) → Task 13~15.
- §10(정확도 검증) → §10-1은 Task 5, §10-2·§10-3은 명시적으로 범위 밖 처리(위 절 참조).
- §11(테스트 요구) → Tier1·위치·조합표 순수 단위(Task 2~4), fake GeminiHttp(Task 6), 게이트
  통합 테스트(Task 10), 어셈블러 필드 매핑·seededAuthor 조인(Task 15) 전부 포함.
- 초안에서 §7의 "전용 소형 풀(동시 3~4)"이 Task 8의 `AdDisclosureJudgeService` 생성자에
  일반 `Executor`로만 있어 풀 크기가 어디서 강제되는지 불명확했던 것을 발견 → Task 9의
  `AdDisclosureConfig.adDisclosureWorkerPool` 빈에 `monitoring.brand.ad-disclosure.concurrency:4`
  기본값을 명시하는 것으로 고정했다(BrandBackfillConfig의 `brandEnrichWorkerPool` 패턴과 동형).

**2. 플레이스홀더 스캔** — "TBD"·"적절히 처리"·"위와 유사" 패턴 검색 결과 없음. 모든 코드
스텝이 완전한 클래스/메서드 본문이다. Task 17의 Step 2(조건부 컨트롤러 테스트)만 "발견된 파일의
패턴을 따른다"는 조건부 지시인데, 이는 파일 존재 여부 자체가 불확실해 실행 시점에 Step 1의 grep
결과로 확정되는 구조라 정당한 조건부 분기이지 placeholder가 아니다.

**3. 타입 일관성** — 초안 작성 중 아래 불일치를 발견해 고쳤다:
- `BrandPostMetaRepository`의 신규 메서드를 처음엔 `private`로 썼다가, Task 8의
  `AdDisclosureJudgeService` 테스트가 `FakeRepo extends BrandPostMetaRepository`로 오버라이드해야
  해서 `public`으로 정정(Task 7 Step 3에 반영, Task 8 Step 3 하단에 주석으로 이유 명시).
- `AdVerdictResult.Evidence`의 `category` 필드를 처음 설계에서 `AdDisclosureExtractor.Category`
  enum 타입으로 뒀다가, DB에는 문자열로 저장하고(Tier1의 "CLEAR"는 애초에 enum이 아님) was 응답도
  문자열이라 처음부터 `String`으로 통일했다(Task 4 Step 1의 `AdVerdictResult` 정의부터 일관).
- `BrandCollectService` 생성자 파라미터 순서 — 스펙 문서화 없이 임의로 끝에 붙이면 기존 두 호출부
  (`service()` 헬퍼, 동시성 테스트)의 diff가 커진다고 판단해 `authors` 다음·`enrichWorker` 앞으로
  위치를 고정하고, Task 10 Step 3·4에 두 호출부 수정을 모두 명시했다.
- was `BrandPostAssembler.taggedPost()`는 정적 메서드라 필드 접근을 못 하므로 `exposeAdDisclosure`·
  `seededUsernames`를 파라미터로 명시적으로 넘기도록 시그니처를 확정했다(인스턴스 필드 참조로
  잘못 쓰면 컴파일 에러로 즉시 드러나므로 안전하지만, 계획 단계에서 시그니처를 미리 고정해
  구현자가 헷갈리지 않게 했다).

**4. 스펙 모순 재검토(코디네이터 리뷰 반영, 2차 수정)** — `AdPositionRule`(및 스펙 §5)은
"첫 번째 해시태그는 오프셋 무관 인정"(FIRST_HASHTAG)이 위치 3구간(VISIBLE/GRAY/HIDDEN)보다
우선 평가된다. 초안의 여러 테스트가 `#광고`를 캡션의 **유일한(=필연적으로 첫 번째) 해시태그**로
써놓고 HIDDEN·GRAY를 기대해, 구현대로면 FIRST_HASHTAG로 빠져 실패하는 테스트였다. 전수
스캔(Task 2~10 전체, 캡션에 "#" 등장하는 모든 테스트를 훑음) 결과와 조치:

- **깨지는 테스트(수정 완료, 7건)** — `#광고`를 비해시태그 Tier1 문구 `"광고입니다"`(사전에 실존,
  Task 2 참조)로 교체하거나(Task 3의 `접힘_하한_초과는_HIDDEN`·`세번째_줄_이후는_HIDDEN`·
  `경계_사이_회색지대는_...GRAY`, Task 4의 `CLEAR_있으나_전부_묻힌_위치면_...`·
  `Tier1_매칭이_묻힌_위치여도_evidence로_넘어온다`, Task 5의
  `부적절_예_본문_중간_구분없이_삽입은_HIDDEN`), 앞에 다른 해시태그를 추가해 `#광고`를 두 번째로
  밀어냈다(Task 3의 `보임_상한_안쪽은_VISIBLE` — `"#데일리 #광고"`).
- **FIRST_HASHTAG를 정확히 검증하는 테스트(무변경, 정상)** — Task 3의
  `첫_해시태그는_오프셋_무관_인정`(캡션 맨 앞 `#광고 ` + 긴 꼬리, FIRST_HASHTAG 기대)는 반대
  방향(1번 지시)으로도 확인 — 실제로 유일·최선두 해시태그라 조건을 만족한다.
- **`#광고`가 첫 해시태그지만 검증 결과가 우연히 안 깨지는 테스트(무변경, 근거 남김)** — Tier4의
  `CLEAR_문구가_적절_위치면_DISCLOSED`·`여러_카테고리가_섞이면_CLEAR_적절_위치가_우선한다`,
  Task 5의 `적절_예_광고`·`적절_예_협찬`, Task 8의 `Tier1_매칭_적절_위치면_LLM_생략하고_DISCLOSED`·
  `캡션이_바뀌면_재판정한다`는 전부 "DISCLOSED"만 단언한다 — FIRST_HASHTAG·VISIBLE·GRAY는
  전부 `accepted()`(코드 조합기 기준)에 속해 결과가 같으므로 깨지지 않는다. 다만 이 테스트들이
  실제로 검증하는 것은 "일반 위치 규칙의 VISIBLE 분기"가 아니라 "첫 해시태그 예외 분기"라는
  점을 여기 기록해 둔다(고치지 않은 이유는 assertion이 이미 정확해서다 — 별도 조치 불필요).
- **의도대로 첫 해시태그가 아님을 확인한 테스트(무변경)** — Task 5의
  `부적절_예_여러_해시태그_사이에_묻힘`은 `#a #b … #t`(120개) 뒤에 `#광고`를 둬 첫 해시태그가
  아님을 이미 보장한다(첫 토큰은 `#a`) — 검증 결과 문제없음.
- **위치 로직을 안 타는 테스트(무변경)** — Task 8의 `BrandCollectServiceTest` 신규 3종은
  `AdDisclosureJudgeService`를 `FakeAdJudge`로 완전히 대체해 실제 Tier0~3 로직이 호출되지
  않으므로 이 축의 영향을 받지 않는다. Task 2(`AdDisclosurePatternsTest`)·Task 6(LLM 추출기
  테스트)도 위치 판정을 호출하지 않아 무관하다.
