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
		boolean failing;

		StubBrands() {
			super(null);
		}

		@Override
		public Optional<String> findFullName(long brandId) {
			if (failing) {
				throw new IllegalStateException("DB 장애 주입");
			}
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

	/** 표시명 조회(DB) 실패도 격리되어 FALLBACK으로 수렴하고 지표에 error로 남는다. */
	@Test
	void 표시명_조회_DB_실패도_FALLBACK으로_수렴하고_지표에_error로_남는다() {
		brands.failing = true;

		var out = service().suggest(BRAND_ID, USERNAME);

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo("drpielofficial");
		assertThat(llmCalls).isEmpty();
		assertThat(counted("fallback", "error")).isEqualTo(1);
	}

	/** 계정명이 null·공백이면(호출부 결함 방어) 계산을 시도조차 하지 않고 곧장 FALLBACK이다. */
	@Test
	void 계정명이_비어있으면_계산_없이_바로_FALLBACK이다() {
		var out = service().suggest(BRAND_ID, null);

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo(BrandHashtagSuggestionService.UNREACHABLE_FALLBACK_TAG);
		assertThat(out.topCount()).isZero();
		assertThat(out.candidatePosts()).isZero();
		assertThat(llmCalls).isEmpty();
		assertThat(counted("fallback", "error")).isEqualTo(1);
	}

	/** 공백 계정명도 null과 동일하게 취급한다. */
	@Test
	void 계정명이_공백뿐이면_계산_없이_바로_FALLBACK이다() {
		var out = service().suggest(BRAND_ID, "   ");

		assertThat(out.path()).isEqualTo("FALLBACK");
		assertThat(out.tag()).isEqualTo(BrandHashtagSuggestionService.UNREACHABLE_FALLBACK_TAG);
		assertThat(llmCalls).isEmpty();
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

	/**
	 * 계정명이 언더스코어조차 없는 순수 특수문자뿐이면(예: "."), 2차 정리도 아무것도 못 건져
	 * {@link BrandHashtagSuggestionService#UNREACHABLE_FALLBACK_TAG}까지 내려간다 — 실제 IG
	 * 계정명 규칙상 도달 불가지만, 코드는 이 마지막 계단까지 비지 않음을 봉인해 둔다.
	 */
	@Test
	void 계정명에_글자_숫자_언더스코어가_전혀_없으면_최종_방어값이다() {
		String tag = BrandHashtagSuggestionService.fallbackTag(".");

		assertThat(tag).isNotBlank();
		assertThat(tag).isEqualTo(BrandHashtagSuggestionService.UNREACHABLE_FALLBACK_TAG);
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
