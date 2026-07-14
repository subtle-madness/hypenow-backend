# 태스크 V — 매칭 어휘 계약 구현 계획

> 상태: 🟢 활성
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매칭 어휘(제품 속성·톤 + 캠페인 목표)를 `contract-analysis`에 enum으로 확정한다 — 브리프 VLM(was)·콘텐츠 VLM(analytics)·폼 선택지·가중치 프리셋 키가 전부 이 어휘만 쓰도록 하는 단일 원천.

**Architecture:** 기준 spec [2026-07-13-campaign-recommendation-design.md](../specs/2026-07-13-campaign-recommendation-design.md) §3.
카테고리 어휘는 crawler 분류 계층 재사용이므로 **여기서 정의하지 않는다** — 신설은 세 enum뿐.
각 enum은 `code`(소문자 snake — DB/JSON/프롬프트 어휘)와 `label`(한국어 표시명)을 들고,
`fromCode()`가 어휘 밖 값을 `Optional.empty()`로 돌려 방어(B3 `AnthropicVisionAnalyzer`의
null 교체 패턴을 계약 층으로 끌어올림). 새 패키지 `com.celfit.contract.matching` —
기존 `contract.analysis`(분석 결과 record)와 성격이 달라 분리.

**Tech Stack:** Java 21 순수 JDK (main은 무의존 유지 — 모듈 규칙), JUnit Jupiter는 **test 스코프만** 추가(공개 계약 표면에 안 샌다).

**작업 위치:** 베이스 브랜치 `docs/campaign-recommendation-pivot`(스펙이 있는 스택 최상단), 새 브랜치 `feat/task-v-matching-vocabulary`, 워크트리 `.worktrees/task-v`. PR 대상은 `docs/campaign-recommendation-pivot`(스택 유지).

---

## 어휘 확정안 (이 계획의 심장 — 계획 리뷰가 곧 어휘 확정)

선정 기준: **제품 이미지와 인플루언서 콘텐츠 양쪽에서 판별 가능한 축만** (spec §10).
예: "가성비"는 이미지로 판별 불가 → 제외. 값은 시작 셋이며, 추가는 enum 상수 +1(테스트가
형식을 강제). **개정 시 `content_attribute_tags` 재분석 필요**(어휘 버전 컬럼 — P2 소관).

**CampaignGoal (3)** — spec §1에서 이미 확정: `awareness` 인지도 / `conversion` 전환 / `credibility` 신뢰·리뷰

**ProductAttribute (10)** — 제품 효능·포지셔닝:

| code | label | 이미지에서 | 콘텐츠에서 |
|---|---|---|---|
| `moisturizing` | 보습 | 패키지 문구·제형 | 보습 리뷰·수분광 연출 |
| `soothing` | 진정 | 시카·알로에 등 성분 표기 | 트러블 진정 후기 |
| `anti_aging` | 안티에이징 | 링클·펩타이드 표기 | 탄력·주름 콘텐츠 |
| `brightening` | 미백·광채 | 비타민C·글로우 표기 | 광채 연출·톤업 후기 |
| `sun_care` | 자외선 차단 | SPF 표기 | 선케어 루틴 |
| `cleansing` | 클렌징·세정 | 제형·용기 | 세안 루틴 |
| `coverage` | 커버·베이스 | 파운데이션·쿠션 유형 | 베이스 메이크업 시연 |
| `color_makeup` | 색조 | 립·아이 팔레트 유형 | 발색·스와치 |
| `low_irritation` | 저자극·민감성 | 무향·더마 표기 | 민감성 피부 후기 |
| `vegan_clean` | 비건·클린뷰티 | 비건 인증 마크 | 클린뷰티 지향 소개 |

**ContentTone (7)** — 비주얼 톤·무드:

| code | label |
|---|---|
| `clean_minimal` | 깔끔·미니멀 |
| `natural_daily` | 내추럴·데일리 |
| `luxury` | 럭셔리·고급 |
| `cute_kitsch` | 귀여움·키치 |
| `trendy_hip` | 트렌디·힙 |
| `glam` | 글램·화려 |
| `expert_informative` | 전문가·정보형 |

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `contract-analysis/build.gradle` (수정) | test 스코프 JUnit 추가 |
| `contract-analysis/src/main/java/com/celfit/contract/matching/CampaignGoal.java` (생성) | 캠페인 목표 어휘 |
| `contract-analysis/src/main/java/com/celfit/contract/matching/ProductAttribute.java` (생성) | 제품 속성 어휘 |
| `contract-analysis/src/main/java/com/celfit/contract/matching/ContentTone.java` (생성) | 톤·무드 어휘 |
| `contract-analysis/src/test/java/com/celfit/contract/matching/MatchingVocabularyTest.java` (생성) | 세 enum 공통 계약 검증 |

---

### Task 1: 테스트 인프라 + CampaignGoal

**Files:**
- Modify: `contract-analysis/build.gradle`
- Create: `contract-analysis/src/test/java/com/celfit/contract/matching/MatchingVocabularyTest.java`
- Create: `contract-analysis/src/main/java/com/celfit/contract/matching/CampaignGoal.java`

- [ ] **Step 1: build.gradle에 test 의존성 추가**

```groovy
// 계약 모듈: 분석 결과의 record·enum + 매칭 어휘를 담는다 (ARCHITECTURE.md §5-4).
// 순수 JDK — main에는 Spring/JPA 등 어떤 의존성도 추가하지 않는다 (test 스코프는 예외).
plugins {
	id 'java-library'
}

dependencies {
	// 이 모듈엔 dependency-management(BOM)가 없다 — 버전 명시 필수
	testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

(파일 전체 교체 — 기존 내용은 plugins 블록과 주석뿐)

- [ ] **Step 2: 실패하는 테스트 작성**

`contract-analysis/src/test/java/com/celfit/contract/matching/MatchingVocabularyTest.java`:

```java
package com.celfit.contract.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 매칭 어휘 공통 계약: 코드 형식·유일성·왕복 변환·어휘 밖 방어. */
class MatchingVocabularyTest {

	@Test
	void 캠페인_목표_코드는_왕복_변환된다() {
		for (CampaignGoal goal : CampaignGoal.values()) {
			assertEquals(Optional.of(goal), CampaignGoal.fromCode(goal.code()));
		}
	}

	@Test
	void 캠페인_목표는_3종이다() {
		assertEquals(3, CampaignGoal.values().length);
		assertEquals(Optional.of(CampaignGoal.AWARENESS), CampaignGoal.fromCode("awareness"));
		assertEquals(Optional.of(CampaignGoal.CONVERSION), CampaignGoal.fromCode("conversion"));
		assertEquals(Optional.of(CampaignGoal.CREDIBILITY), CampaignGoal.fromCode("credibility"));
	}

	@Test
	void 어휘_밖_코드는_empty로_방어한다() {
		assertTrue(CampaignGoal.fromCode("branding").isEmpty());
		assertTrue(CampaignGoal.fromCode("").isEmpty());
		assertTrue(CampaignGoal.fromCode(null).isEmpty());
	}

	@Test
	void 캠페인_목표_코드는_형식을_지키고_중복이_없다() {
		assertCodesWellFormed(java.util.Arrays.stream(CampaignGoal.values())
				.map(CampaignGoal::code).toList());
	}

	/** 코드 형식: 소문자 snake_case (DB·JSON·프롬프트에 그대로 박히는 문자열) + 유일성. */
	static void assertCodesWellFormed(java.util.List<String> codes) {
		Set<String> seen = new HashSet<>();
		for (String code : codes) {
			assertTrue(code.matches("^[a-z]+(_[a-z]+)*$"), "코드 형식 위반: " + code);
			assertTrue(seen.add(code), "코드 중복: " + code);
		}
	}
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :contract-analysis:test`
Expected: **컴파일 실패** — `CampaignGoal` 심볼 없음

- [ ] **Step 4: CampaignGoal 구현**

`contract-analysis/src/main/java/com/celfit/contract/matching/CampaignGoal.java`:

```java
package com.celfit.contract.matching;

import java.util.Optional;

/**
 * 캠페인 목표 — 가중치 프리셋 선택 키이자 브리프 폼 선택지 어휘.
 * 목표 1개 = 프리셋 1행 (기준 spec 2026-07-13-campaign-recommendation-design.md §1·§5).
 */
public enum CampaignGoal {
	AWARENESS("awareness", "인지도"),
	CONVERSION("conversion", "전환"),
	CREDIBILITY("credibility", "신뢰·리뷰");

	private final String code;
	private final String label;

	CampaignGoal(String code, String label) {
		this.code = code;
		this.label = label;
	}

	/** DB·JSON·프롬프트에 박히는 안정 문자열. */
	public String code() {
		return code;
	}

	/** 한국어 표시명 (프론트 전달용 — 해석은 여기서 확정, was는 전달만). */
	public String label() {
		return label;
	}

	/** 어휘 밖 값 방어 — 모르는 코드는 empty (LLM 출력·외부 입력 검증용). */
	public static Optional<CampaignGoal> fromCode(String code) {
		for (CampaignGoal value : values()) {
			if (value.code.equals(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :contract-analysis:test`
Expected: BUILD SUCCESSFUL, 테스트 4개 통과

- [ ] **Step 6: Commit**

```bash
git add contract-analysis/build.gradle contract-analysis/src
git commit -m "feat(contract): 매칭 어휘 — 캠페인 목표 3종 enum + 계약 테스트 인프라"
```

---

### Task 2: ProductAttribute

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/matching/ProductAttribute.java`
- Modify: `contract-analysis/src/test/java/com/celfit/contract/matching/MatchingVocabularyTest.java`

- [ ] **Step 1: 실패하는 테스트 추가** (MatchingVocabularyTest에 아래 메서드 추가)

```java
	@Test
	void 제품_속성_코드는_왕복_변환되고_형식을_지킨다() {
		for (ProductAttribute attr : ProductAttribute.values()) {
			assertEquals(Optional.of(attr), ProductAttribute.fromCode(attr.code()));
		}
		assertCodesWellFormed(java.util.Arrays.stream(ProductAttribute.values())
				.map(ProductAttribute::code).toList());
	}

	@Test
	void 제품_속성_어휘_밖_코드는_empty로_방어한다() {
		assertTrue(ProductAttribute.fromCode("cost_effective").isEmpty());
		assertTrue(ProductAttribute.fromCode(null).isEmpty());
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :contract-analysis:test`
Expected: 컴파일 실패 — `ProductAttribute` 심볼 없음

- [ ] **Step 3: ProductAttribute 구현**

`contract-analysis/src/main/java/com/celfit/contract/matching/ProductAttribute.java`:

```java
package com.celfit.contract.matching;

import java.util.Optional;

/**
 * 제품 속성 어휘 — 브리프 이미지 VLM(was)과 콘텐츠 속성 VLM(analytics)이
 * 공유하는 소프트 스코어 축. 수록 기준: 제품 이미지와 인플루언서 콘텐츠
 * 양쪽에서 판별 가능한 속성만 (기준 spec §3·§10).
 * 어휘 개정 시 content_attribute_tags 재분석 필요 — 어휘 버전은 P2가 관리.
 */
public enum ProductAttribute {
	MOISTURIZING("moisturizing", "보습"),
	SOOTHING("soothing", "진정"),
	ANTI_AGING("anti_aging", "안티에이징"),
	BRIGHTENING("brightening", "미백·광채"),
	SUN_CARE("sun_care", "자외선 차단"),
	CLEANSING("cleansing", "클렌징·세정"),
	COVERAGE("coverage", "커버·베이스"),
	COLOR_MAKEUP("color_makeup", "색조"),
	LOW_IRRITATION("low_irritation", "저자극·민감성"),
	VEGAN_CLEAN("vegan_clean", "비건·클린뷰티");

	private final String code;
	private final String label;

	ProductAttribute(String code, String label) {
		this.code = code;
		this.label = label;
	}

	/** DB·JSON·프롬프트에 박히는 안정 문자열. */
	public String code() {
		return code;
	}

	/** 한국어 표시명 (프론트 전달용). */
	public String label() {
		return label;
	}

	/** 어휘 밖 값 방어 — 모르는 코드는 empty (LLM 출력 검증용). */
	public static Optional<ProductAttribute> fromCode(String code) {
		for (ProductAttribute value : values()) {
			if (value.code.equals(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :contract-analysis:test`
Expected: BUILD SUCCESSFUL, 테스트 6개 통과

- [ ] **Step 5: Commit**

```bash
git add contract-analysis/src
git commit -m "feat(contract): 매칭 어휘 — 제품 속성 10종 (이미지·콘텐츠 양쪽 판별 가능 축만)"
```

---

### Task 3: ContentTone

**Files:**
- Create: `contract-analysis/src/main/java/com/celfit/contract/matching/ContentTone.java`
- Modify: `contract-analysis/src/test/java/com/celfit/contract/matching/MatchingVocabularyTest.java`

- [ ] **Step 1: 실패하는 테스트 추가** (MatchingVocabularyTest에 아래 메서드 추가)

```java
	@Test
	void 톤_코드는_왕복_변환되고_형식을_지킨다() {
		for (ContentTone tone : ContentTone.values()) {
			assertEquals(Optional.of(tone), ContentTone.fromCode(tone.code()));
		}
		assertCodesWellFormed(java.util.Arrays.stream(ContentTone.values())
				.map(ContentTone::code).toList());
	}

	@Test
	void 톤_어휘_밖_코드는_empty로_방어한다() {
		assertTrue(ContentTone.fromCode("vintage").isEmpty());
		assertTrue(ContentTone.fromCode(null).isEmpty());
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :contract-analysis:test`
Expected: 컴파일 실패 — `ContentTone` 심볼 없음

- [ ] **Step 3: ContentTone 구현**

`contract-analysis/src/main/java/com/celfit/contract/matching/ContentTone.java`:

```java
package com.celfit.contract.matching;

import java.util.Optional;

/**
 * 비주얼 톤·무드 어휘 — 제품 이미지의 브랜드 무드와 인플루언서 콘텐츠의
 * 연출 톤을 같은 언어로 비교하기 위한 소프트 스코어 축 (기준 spec §3·§10).
 */
public enum ContentTone {
	CLEAN_MINIMAL("clean_minimal", "깔끔·미니멀"),
	NATURAL_DAILY("natural_daily", "내추럴·데일리"),
	LUXURY("luxury", "럭셔리·고급"),
	CUTE_KITSCH("cute_kitsch", "귀여움·키치"),
	TRENDY_HIP("trendy_hip", "트렌디·힙"),
	GLAM("glam", "글램·화려"),
	EXPERT_INFORMATIVE("expert_informative", "전문가·정보형");

	private final String code;
	private final String label;

	ContentTone(String code, String label) {
		this.code = code;
		this.label = label;
	}

	/** DB·JSON·프롬프트에 박히는 안정 문자열. */
	public String code() {
		return code;
	}

	/** 한국어 표시명 (프론트 전달용). */
	public String label() {
		return label;
	}

	/** 어휘 밖 값 방어 — 모르는 코드는 empty (LLM 출력 검증용). */
	public static Optional<ContentTone> fromCode(String code) {
		for (ContentTone value : values()) {
			if (value.code.equals(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :contract-analysis:test`
Expected: BUILD SUCCESSFUL, 테스트 8개 통과

- [ ] **Step 5: 전체 빌드 확인** (다른 모듈이 안 깨졌는지)

Run: `./gradlew build -x test && ./gradlew :contract-analysis:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add contract-analysis/src
git commit -m "feat(contract): 매칭 어휘 — 비주얼 톤 7종"
```

---

### Task 4: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§6 작업 트랙 V 행, §8 결정 기록)

- [ ] **Step 1: 작업 트랙 V 상태를 ✅로**

§6 캠페인 추천 작업 트랙 표에서 V 행의 `⬜`를 `✅`로 바꾼다.

- [ ] **Step 2: 결정 기록 추가** (§8 표 맨 위에)

```markdown
| 2026-07-14 | 매칭 어휘 확정 — `contract.matching` 패키지: CampaignGoal 3종·ProductAttribute 10종·ContentTone 7종 (code+label, fromCode 어휘 밖 empty 방어). 수록 기준: 이미지·콘텐츠 양쪽 판별 가능 축만 | [plans/2026-07-14-task-v-matching-vocabulary.md](docs/superpowers/plans/2026-07-14-task-v-matching-vocabulary.md) |
```

- [ ] **Step 3: Commit**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 태스크 V 완료 반영 — 매칭 어휘 확정"
```

---

## 완료 기준

- `./gradlew :contract-analysis:test` 통과 (테스트 8개)
- `./gradlew build -x test` 전체 모듈 컴파일 성공
- main 소스에 JDK 외 의존성 없음 (build.gradle diff로 확인 — test 스코프만 추가)
- ARCHITECTURE §6 V ✅, §8 결정 기록 1줄
- 실행 완료 시 이 계획을 `plans/archive/`로 이동 (finishing 단계)
