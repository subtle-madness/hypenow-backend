# 태스크 B3: 기준선 스냅샷 + VLM + 종합 텍스트 → content_analyses Implementation Plan

> 상태: ✅ 구현/실행/반영됨 (2026-07-12)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 데이터 층의 마지막 조각 — 콘텐츠 1건당 분석 결과를 `content_analyses` 한 행으로 고정 저장하는 분석 잡. 기준선 스냅샷(SQL 집계 뷰) + VLM(F-2 검증 전까지 게이트 off) + 종합 텍스트(LLM) + 댓글 진정성 판정.

**Architecture:** [스펙](../../specs/2026-07-12-analytics-data-layer-design.md) §3·§6. 집합 연산(기준선)은 raw DB 뷰에, 절차(LLM/VLM 호출→저장)는 Java에 (§4-2). 저장은 분석 시점 고정·불변 — 재실행 없음, 미분석 콘텐츠만 추가. B2에서 배운 규칙 반영: **콘텐츠 단위 실패 격리(try/catch continue)**, 포트 fake 테스트, 게이트 뒤 빈.

**참여율(ER) 정의 주의:** 노션 확정안 기준 **ER = (likes+comments)/views** (팔로워 분모 아님). 피드는 views NULL → ER NULL → 평균에서 자연 제외.

**전제:** F+B2 완료 상태에서 이어감. 운영 순서는 classify → analyze (분석 대상 조건이 이를 강제 — 아래 Task 3).

---

## File Structure

```
analytics/views/03_analysis_baseline.sql                 [신규] 콘텐츠별 기준선 뷰 (분석 잡 전용, 미러 안 함)
analytics/test/03_analysis_baseline.test.sql             [신규] 손계산 기대값
analytics/src/main/resources/db/migration/analysis/
  V3__content_analyses.sql                               [신규] 분석 결과 테이블 (1:1, 불변)
analytics/src/main/java/com/celfit/analytics/llm/
  ContentToAnalyze.java                                  [신규] 종합 입력 record
  Synthesis.java                                         [신규] LLM 텍스트+진정성 출력 record
  SynthesisPort.java                                     [신규] 종합 텍스트 포트
  AnthropicSynthesizer.java                              [신규] 어댑터 (structured outputs)
  VlmResult.java                                         [신규] VLM 출력 record
  VisionPort.java                                        [신규] VLM 포트
  AnthropicVisionAnalyzer.java                           [신규] 어댑터 (이미지 URL 입력)
  LlmConfig.java                                         [수정] 포트 빈 2종 추가
analytics/src/main/java/com/celfit/analytics/analyze/
  Baseline.java                                          [신규] 기준선 record
  ContentAnalysisJob.java                                [신규] 분석 잡
  AnalyzeRunner.java                                     [신규] 게이트 러너
analytics/src/test/java/com/celfit/analytics/analyze/
  ContentAnalysisJobTest.java                            [신규] fake 포트 + Testcontainers
analytics/src/main/resources/application.yml             [수정] analyze-on-startup=false, vlm-enabled=false
analytics/README.md, ARCHITECTURE.md                     [수정] 상태 갱신
```

---

### Task 1: 기준선 뷰 (SQL식 TDD)

**Files:**
- Create: `analytics/test/03_analysis_baseline.test.sql`
- Create: `analytics/views/03_analysis_baseline.sql`

시드 기준 손계산 (dummy_a: r1 views=11000 likes=520 comments=52 / r2 7000·300·30 / f1 NULL·2000·100, dummy_b: r3 40000·1000·80):
- dummy_a ER: r1=(520+52)/11000=0.052, r2=(300+30)/7000=0.0471…, f1 NULL → avg≈0.0496 (소수 4자리 반올림 0.0496)
- dummy_a 평균 좋아요=(520+300+2000)/3=940.0, 평균 댓글=(52+30+100)/3=60.7 (1자리)
- dummy_a 릴스: 2건, 평균 views=(11000+7000)/2=9000.0; r1의 rank_in_recent_reels=1, r2=2
- 카테고리(999) views 표본: 11000,7000,40000 (f1 NULL 제외) → sample_size=3, 평균=19333.3; r3 top_percentile=34 (cume_dist 1/3), r1=67, r2=100

- [ ] **Step 1: 03_analysis_baseline.test.sql 작성 (뷰보다 먼저)**

```sql
-- 기준선 뷰 기대값 (시드 손계산 근거는 계획 문서 참조)
DELETE FROM app_setting WHERE key = 'analytics.recent-window';

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_baseline WHERE short_code LIKE 'dummy_%') = 4,
    'baseline rows != 4';
  ASSERT (SELECT recent12_avg_engagement_rate FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 0.0496,
    'dummy_a avg ER != 0.0496';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 940,
    'dummy_a avg likes != 940';
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 3,
    'dummy_a window count != 3';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 9000,
    'dummy_a reels avg views != 9000';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 1,
    'dummy_r1 reels rank != 1';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r2') = 2,
    'dummy_r2 reels rank != 2';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_f1') IS NULL,
    'feed reels rank must be NULL';
  ASSERT (SELECT category_sample_size FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r3') = 3,
    'category sample != 3 (views NULL 제외)';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r3') = 34,
    'dummy_r3 top percentile != 34';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 67,
    'dummy_r1 top percentile != 67';
END $$;
```

- [ ] **Step 2: 하니스 실행 — 실패 확인**

- [ ] **Step 3: views/03_analysis_baseline.sql 작성**

```sql
-- 콘텐츠별 기준선 (분석 잡 전용 — 미러 안 함, 분석 시점에 content_analyses로 고정 저장).
-- ER = (likes+comments)/views (노션 확정안). views NULL(피드)은 ER NULL → 평균에서 제외.
-- 조회수 비교 모수(릴스)와 참여 지표 모수(최근 N개 전체)를 분리해 함께 기록한다.
CREATE OR REPLACE VIEW analytics.v_analysis_baseline AS
WITH windowed AS (
  SELECT *,
         round((likes + comments_count)::numeric / NULLIF(views, 0), 4) AS er
  FROM analytics.v_recent_content
),
account_agg AS (
  SELECT owner_username,
         count(*)                                            AS recent_contents_count,
         round(avg(er), 4)                                   AS recent12_avg_engagement_rate,
         round(avg(likes), 0)                                AS recent12_avg_like_count,
         round(avg(comments_count), 0)                       AS recent12_avg_comment_count,
         count(*) FILTER (WHERE lower(content_type) = 'reels' AND views IS NOT NULL) AS recent_reels_count,
         round(avg(views) FILTER (WHERE lower(content_type) = 'reels'), 0)           AS recent_reels_avg_views
  FROM windowed
  GROUP BY owner_username
),
reels_rank AS (
  SELECT content_id,
         rank() OVER (PARTITION BY owner_username ORDER BY views DESC NULLS LAST) AS rank_in_recent_reels
  FROM windowed
  WHERE lower(content_type) = 'reels' AND views IS NOT NULL
),
category_ctx AS (
  SELECT content_id,
         ceil(100 * cume_dist() OVER (PARTITION BY category_id ORDER BY views DESC))::smallint AS category_top_percentile,
         round(avg(views) OVER (PARTITION BY category_id), 0)  AS category_avg_views,
         count(*) OVER (PARTITION BY category_id)              AS category_sample_size
  FROM windowed
  WHERE views IS NOT NULL
)
SELECT
  w.short_code,
  a.recent_reels_avg_views,
  r.rank_in_recent_reels,
  a.recent_reels_count,
  a.recent_contents_count,
  a.recent12_avg_engagement_rate,
  a.recent12_avg_like_count,
  a.recent12_avg_comment_count,
  c.category_top_percentile,
  c.category_avg_views,
  c.category_sample_size
FROM windowed w
JOIN account_agg a USING (owner_username)
LEFT JOIN reels_rank r USING (content_id)
LEFT JOIN category_ctx c USING (content_id);
```

- [ ] **Step 4: 하니스 전체 ALL GREEN** (기대값이 어긋나면 손계산을 다시 검산해 테스트 쪽을 고치되, 반올림 규칙(4자리/0자리)·NULL 제외 원칙은 유지하고 보고에 명시)

- [ ] **Step 5: Commit** — `feat(analytics): 콘텐츠별 기준선 뷰 — 계정 윈도우 집계·릴스 순위·카테고리 백분위`

---

### Task 2: content_analyses DDL + LLM 포트 2종

**Files:**
- Create: `analytics/src/main/resources/db/migration/analysis/V3__content_analyses.sql`
- Create: llm 패키지의 record·포트·어댑터 6파일 (아래)
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Modify: `analytics/src/main/resources/application.yml`

- [ ] **Step 1: V3__content_analyses.sql**

```sql
-- 콘텐츠 1:1 분석 결과 (분석 시점 고정·불변 — 스펙 §2). 미러 테이블과 FK 없음(논리 참조).
-- 기준선 스냅샷은 "AI 텍스트가 참조한 수치"라 같은 행에 고정한다.
CREATE TABLE content_analyses (
    short_code                   text PRIMARY KEY,
    analyzed_at                  timestamptz NOT NULL DEFAULT now(),
    model                        text NOT NULL,

    -- LLM 텍스트
    ai_content_summary           text,
    contents_pattern             text,
    ai_comment_insight           text,

    -- 기준선 스냅샷 (비LLM — 분석 시점 고정)
    recent_reels_avg_views       bigint,
    rank_in_recent_reels         smallint,
    recent_reels_count           smallint,
    recent_contents_count        smallint,
    recent12_avg_engagement_rate numeric,
    recent12_avg_like_count      bigint,
    recent12_avg_comment_count   bigint,
    category_top_percentile      smallint,
    category_avg_views           bigint,
    category_sample_size         bigint,

    -- VLM 산출물 (F-2 검증 전 NULL 허용)
    detected_brands              jsonb,
    sponsored_signal_level       text CHECK (sponsored_signal_level IN ('high','mid','low')),
    sponsored_signal_reasons     jsonb,
    ad_disclosure                text,
    detected_product_categories  jsonb,
    vlm_attributes               jsonb,
    main_category                text,
    sub_categories               jsonb,
    ad_type                      text CHECK (ad_type IN ('organic','sponsored')),

    -- 댓글 종합 판정
    comment_authenticity_grade   text CHECK (comment_authenticity_grade IN ('high','normal','suspect')),
    comment_authenticity_note    text
);
```

- [ ] **Step 2: 종합 텍스트 포트** (`llm/` 패키지)

```java
package com.celfit.analytics.llm;

import java.util.Map;

/** 종합 텍스트 입력 — 분석 잡이 조립한 콘텐츠 1건의 전체 맥락. */
public record ContentToAnalyze(String shortCode, String accountHandle, String caption,
		String contentType, Long views, Long likes, Long comments,
		Map<String, Object> baseline, Map<String, Long> commentCategoryCounts) {
}
```

```java
package com.celfit.analytics.llm;

/** LLM 종합 산출 — 텍스트 3종 + 댓글 진정성 판정 (스펙 §3 content_analyses). */
public record Synthesis(String aiContentSummary, String contentsPattern, String aiCommentInsight,
		String commentAuthenticityGrade, String commentAuthenticityNote) {
}
```

```java
package com.celfit.analytics.llm;

/** 종합 텍스트 포트 — 테스트는 fake (실 API 금지). */
public interface SynthesisPort {

	Synthesis synthesize(ContentToAnalyze content);
}
```

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.Set;

/** 종합 텍스트 Anthropic 구현 — 기준선 수치·댓글 분포를 근거로 마케터용 요약을 생성한다. */
public final class AnthropicSynthesizer implements SynthesisPort {

	private static final Set<String> GRADES = Set.of("high", "normal", "suspect");

	private static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 주어진 수치만 근거로 삼고
			수치를 지어내지 마라. 한국어로, 각 항목 2~3문장 이내.

			- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
			  협찬 수용도를 종합한 요약
			- contentsPattern: 이 계정의 어떤 콘텐츠 패턴에서 성과가 나는지 한 줄 해석
			- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
			- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
			- commentAuthenticityNote: 판정 근거 한 줄
			""";

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicSynthesizer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public Synthesis synthesize(ContentToAnalyze content) {
		String input = """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s
				""".formatted(content.shortCode(), content.accountHandle(), content.contentType(),
				content.caption(), content.views(), content.likes(), content.comments(),
				content.baseline(), content.commentCategoryCounts());
		StructuredMessageCreateParams<Synthesis> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(Synthesis.class)
				.addUserMessage(input)
				.build();
		Synthesis s = client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("종합 응답에 본문 없음"))
				.text();
		if (!GRADES.contains(s.commentAuthenticityGrade())) {
			s = new Synthesis(s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
					"normal", s.commentAuthenticityNote());
		}
		return s;
	}
}
```

- [ ] **Step 3: VLM 포트** (게이트 off 기본 — F-2 스파이크 전)

```java
package com.celfit.analytics.llm;

import java.util.List;

/** VLM 산출물 (스펙 §3 — 전부 NULL 허용 컬럼에 대응). */
public record VlmResult(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Attribute> vlmAttributes,
		String mainCategory, List<String> subCategories, String adType) {

	public record Brand(String name, String evidence) {
	}

	public record Attribute(String label, String value) {
	}
}
```

```java
package com.celfit.analytics.llm;

/** VLM(이미지 분석) 포트 — F-2 스파이크 검증 전까지 기본 비활성. 테스트는 fake. */
public interface VisionPort {

	VlmResult analyze(String thumbnailUrl, String caption);
}
```

```java
package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.List;

/**
 * VLM Anthropic 구현 — 썸네일 1장 + 캡션 기반 (F-2 스파이크의 최소 입력안).
 * 영상 프레임 입력은 스파이크 결과에 따라 확장.
 */
public final class AnthropicVisionAnalyzer implements VisionPort {

	private static final String INSTRUCTIONS = """
			당신은 뷰티 콘텐츠의 이미지 분석가다. 썸네일과 캡션을 보고 다음을 추출하라.
			확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

			- detectedBrands: 화면·캡션에서 확인되는 브랜드 {name, evidence(근거)}
			- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
			- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
			- detectedProductCategories: 제품 카테고리 (예: 클렌징, 립)
			- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
			  콘텐츠 유형 / 무드 / 편집 스타일 순
			- mainCategory: makeup|skincare|hair|etc 중 하나, subCategories: 소분류 라벨
			- adType: organic|sponsored (캡션 표기+화면 종합 판정)
			""";

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicVisionAnalyzer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public VlmResult analyze(String thumbnailUrl, String caption) {
		StructuredMessageCreateParams<VlmResult> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(VlmResult.class)
				.addUserMessageOfBlockParams(List.of(
						ContentBlockParam.ofImage(ImageBlockParam.builder()
								.source(UrlImageSource.builder().url(thumbnailUrl).build())
								.build()),
						ContentBlockParam.ofText(TextBlockParam.builder()
								.text("캡션: " + caption).build())))
				.addUserMessage("위 썸네일과 캡션을 분석하라.")
				.build();
		return client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("VLM 응답에 본문 없음"))
				.text();
	}
}
```

- [ ] **Step 4: LlmConfig에 빈 추가** (기존 @Configuration 게이트 그대로 — analyze도 classify와 같은 클라이언트 공유. 조건을 두 프로퍼티 OR로 바꾼다)

클래스 레벨 `@ConditionalOnProperty(classify-on-startup)`를 `@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}")`로 교체한다 (클래스 레벨 하나로 — 빈 단위 @ConditionalOnBean은 같은 @Configuration 안에서 순서 의존이라 쓰지 않는다). 그리고 포트 빈 2종 추가:

```java
	@Bean
	public SynthesisPort synthesisPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicSynthesizer(client, settings);
	}

	@Bean
	public VisionPort visionPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicVisionAnalyzer(client, settings);
	}
```

주의: classify-on-startup만 켜도 Synthesis/Vision 포트 빈이 생기지만 소비자(AnalyzeRunner)가 없으면 무해. AnalyzeRunner 쪽 게이트는 analyze-on-startup이 지킨다.

- [ ] **Step 5: application.yml** — `analytics:` 블록에 추가:

```yaml
  analyze-on-startup: false    # 콘텐츠 분석 배치 — 실 API 비용. 실행: --analytics.analyze-on-startup=true
  vlm-enabled: false           # F-2 스파이크 검증 전까지 VLM 스킵 (컬럼 NULL)
```

- [ ] **Step 6: 컴파일 + 기존 테스트** — `./gradlew :analytics:build` (이미지 블록 시그니처가 다르면 컴파일 에러 기준 최소 수정, "URL 이미지 + structured outputs" 방식 유지, 보고에 명시)

- [ ] **Step 7: Commit** — `feat(analytics): content_analyses DDL + 종합·VLM 포트 (VLM 기본 off)`

---

### Task 3: 분석 잡 (TDD)

**Files:**
- Create: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/Baseline.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`
- Create: `analytics/src/main/java/com/celfit/analytics/analyze/AnalyzeRunner.java`

잡의 계약:
1. **대상**: analysis DB `contents` 중 `content_analyses`에 없는 것 **AND** (댓글이 없거나 `comment_classifications`에 분류가 있는 것) — classify 선행을 강제하되 댓글 0개 콘텐츠도 분석 가능. 상한 `analyzeBatchLimit()`.
2. 콘텐츠당: 댓글 분포 집계(analysis DB) → 기준선 SELECT(raw `v_analysis_baseline`) → VLM(`vlm-enabled`일 때만) → 종합 텍스트 → 한 행 INSERT.
3. **불변**: INSERT만 (UPDATE·재분석 없음). **실패 격리**: 콘텐츠 단위 try/catch continue (B2 리뷰 반영).
4. jsonb 직렬화: `tools.jackson.databind.ObjectMapper` (Jackson 3 — CLAUDE.md 컨벤션), INSERT에서 `?::jsonb` 캐스트.

- [x] **Step 1: 실패하는 테스트 작성** — 검증 케이스 5개:
  ① 미분석+분류완료 콘텐츠가 분석되어 저장 (기준선 수치·텍스트·분포 기반 컬럼 확인) ② 이미 분석된 콘텐츠 스킵 ③ 댓글 있는데 미분류인 콘텐츠는 대상 제외 ④ vlm-enabled=false면 VisionPort 미호출·VLM 컬럼 NULL ⑤ 한 콘텐츠 실패(포트 예외) 시 나머지는 처리

테스트 골격은 CommentClassificationJobTest 패턴 재사용: Testcontainers 1개, Flyway(V1~V3) migrate, raw 대역으로 `analytics.v_analysis_baseline`과 같은 모양의 뷰를 테스트에서 CREATE (고정 수치), fake SynthesisPort/VisionPort는 호출 기록. 완전 초기화 규칙(B2 테스트의 DROP 목록에 content_analyses 추가) 준수. 구체 코드는 이 패턴에 맞춰 구현자가 작성하되 **케이스 5개의 계약은 불변**.

- [x] **Step 2: 실행 — 실패 확인**

- [x] **Step 3: Baseline record + ContentAnalysisJob 구현**

```java
package com.celfit.analytics.analyze;

import java.math.BigDecimal;

/** raw v_analysis_baseline 1행 — 분석 시점 스냅샷 재료. */
public record Baseline(Long recentReelsAvgViews, Integer rankInRecentReels, Integer recentReelsCount,
		Integer recentContentsCount, BigDecimal recent12AvgEngagementRate,
		Long recent12AvgLikeCount, Long recent12AvgCommentCount,
		Integer categoryTopPercentile, Long categoryAvgViews, Long categorySampleSize) {
}
```

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.llm.SynthesisPort;
import com.celfit.analytics.llm.VisionPort;
import com.celfit.analytics.llm.VlmResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) — classify 선행을 강제.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final SynthesisPort synthesis;
	private final VisionPort vision; // vlmEnabled=false면 null 허용
	private final AnalyticsSettings settings;
	private final boolean vlmEnabled;
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			SynthesisPort synthesis, VisionPort vision, AnalyticsSettings settings, boolean vlmEnabled) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.synthesis = synthesis;
		this.vision = vision;
		this.settings = settings;
		this.vlmEnabled = vlmEnabled;
	}

	/** @return 분석 완료 콘텐츠 수 */
	public int run() {
		List<String> targets = analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				ORDER BY c.short_code
				LIMIT ?""", String.class, settings.analyzeBatchLimit());
		String model = settings.llmModel();
		int processed = 0;
		int failed = 0;
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model);
				processed++;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
		}
		log.info("analysis complete ({} contents, {} failed)", processed, failed);
		return processed;
	}

	private void analyzeOne(String shortCode, String model) {
		Map<String, Object> content = analysis.queryForMap("""
				SELECT account_handle, caption, content_type, thumbnail_url, views, likes, comments
				FROM contents WHERE short_code = ?""", shortCode);
		Map<String, Long> categoryCounts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					categoryCounts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
		Baseline b = raw.queryForObject("""
				SELECT recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline WHERE short_code = ?""",
				(rs, i) -> new Baseline(
						// PG 타입이 numeric(round)·bigint(rank/count)·smallint(::smallint)로 섞여 있어
						// getObject 캐스트는 CCE/PSQLException 지뢰 — 전부 BigDecimal로 읽어 변환한다
						longOf(rs.getBigDecimal(1)), intOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)),
						intOf(rs.getBigDecimal(4)), rs.getBigDecimal(5),
						longOf(rs.getBigDecimal(6)), longOf(rs.getBigDecimal(7)),
						intOf(rs.getBigDecimal(8)), longOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10))),
				shortCode);
		VlmResult vlm = (vlmEnabled && vision != null)
				? vision.analyze((String) content.get("thumbnail_url"), (String) content.get("caption"))
				: null;
		Map<String, Object> baselineForPrompt = new LinkedHashMap<>();
		baselineForPrompt.put("recent_reels_avg_views", b.recentReelsAvgViews());
		baselineForPrompt.put("rank_in_recent_reels", b.rankInRecentReels());
		baselineForPrompt.put("recent_contents_count", b.recentContentsCount());
		baselineForPrompt.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		baselineForPrompt.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		baselineForPrompt.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		baselineForPrompt.put("category_top_percentile", b.categoryTopPercentile());
		Synthesis s = synthesis.synthesize(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), (String) content.get("caption"),
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts));
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  ai_content_summary, contents_pattern, ai_comment_insight,
				  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
				  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
				  category_top_percentile, category_avg_views, category_sample_size,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, vlm_attributes, main_category, sub_categories, ad_type,
				  comment_authenticity_grade, comment_authenticity_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?)""",
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				toJson(vlm == null ? null : vlm.detectedBrands()),
				vlm == null ? null : vlm.sponsoredSignalLevel(),
				toJson(vlm == null ? null : vlm.sponsoredSignalReasons()),
				vlm == null ? null : vlm.adDisclosure(),
				toJson(vlm == null ? null : vlm.detectedProductCategories()),
				toJson(vlm == null ? null : vlm.vlmAttributes()),
				vlm == null ? null : vlm.mainCategory(),
				toJson(vlm == null ? null : vlm.subCategories()),
				vlm == null ? null : vlm.adType(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote());
	}

	private String toJson(Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
```

- [x] **Step 4: 테스트 통과 (5케이스)**

- [x] **Step 5: AnalyzeRunner** — ClassifyRunner 패턴 동일 (`analytics.analyze-on-startup=true` 게이트, VisionPort는 `@Autowired(required=false)` 성격의 ObjectProvider로 주입해 vlm-enabled=false여도 기동):

```java
package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.SynthesisPort;
import com.celfit.analytics.llm.VisionPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 분석 배치 배선 — analytics.analyze-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.analyze-on-startup", havingValue = "true")
public class AnalyzeRunner {

	@Bean
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			SynthesisPort synthesis, ObjectProvider<VisionPort> vision, AnalyticsSettings settings,
			@Value("${analytics.vlm-enabled:false}") boolean vlmEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, synthesis,
				vision.getIfAvailable(), settings, vlmEnabled);
	}

	@Bean
	public CommandLineRunner analyzeOnStartup(ContentAnalysisJob job) {
		return args -> job.run();
	}
}
```

- [x] **Step 6: 전체 빌드 + 기본 게이트 off 부트 스모크**

- [x] **Step 7: Commit** — `feat(analytics): 콘텐츠 분석 배치 — 기준선 스냅샷·종합 텍스트·VLM 게이트 (분석 시점 고정)`

---

### Task 4: 문서 갱신 + 아카이브

- [ ] **Step 1: README** — 실행 절에 analyze 명령, 키 표에 `analytics.vlm-enabled` 없음(yml 프로퍼티임을 명시 — app_setting 아님) 주의 문구, 뷰 목록에 03 추가
- [ ] **Step 2: ARCHITECTURE.md** — §5 B3 행 ✅ (다른 부분 불변, diff 확인). 스펙 문서 상태 헤더는 유지(D·E 남음)
- [ ] **Step 3: 이 계획 상태 ✅ 후 plans/archive/로 git mv**
- [ ] **Step 4: 최종 검증** — `cd analytics && ./test/run.sh && cd .. && ./gradlew test -q` (전 모듈)
- [ ] **Step 5: Commit** — `docs: B3 완료 반영 — 데이터 층 전체 개통`

---

## 완료 기준 (DoD)

- SQL 하니스 ALL GREEN (00~03), `./gradlew test` 전 모듈 그린 (실 API 0)
- 기본 게이트로 bootRun = 미러만. `--analytics.classify-on-startup=true` → 분류, `--analytics.analyze-on-startup=true` → 분석 (API 키 필요)
- 스펙 §1의 완료 상태 성립: "프론트 게시물 상세 모달이 요구하는 모든 데이터가 analysis DB에 준비" (D는 후속)

## 다루지 않는 것

- F-2 VLM 스파이크 실행·vlm-enabled 켜기 — 사용자 결정 (비용)
- was API (D·E), 인플루언서 상세(C1·C2), 서비스 데이터(G)
