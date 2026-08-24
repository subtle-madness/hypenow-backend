package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.llm.BrandMentionJudge;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandHashtagRepository.HashtagPostInsert;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 해시태그 스윕 파이프라인(스펙 2026-08-11 §3·§4) — BrandCollectServiceTest 관용구
 * (fake HikerHttp 람다 + 인메모리 스텁 서브클래스, DB 없음)로 조기 종료·자사/직접태그/멘션
 * 필터·LLM 판정·윈도우 컷·태그별 독립 열거를 검증한다.
 */
class BrandHashtagCollectServiceTest {

	private static final long NOW = Instant.now().getEpochSecond();
	private static final int WINDOW_DAYS = 90;
	private static final long RECENT = NOW - 5L * 86400;              // 윈도우(90일) 안
	private static final long OUT_OF_WINDOW = NOW - 95L * 86400;      // 윈도우(90일) 밖

	private final BrandHashtagRepository repo = new InMemoryHashtagRepo();
	private final StubBrandRepository brands = new StubBrandRepository();
	private final FakeJudge judge = new FakeJudge();

	private final Map<String, List<String>> pagesByTag = new HashMap<>();
	private final Map<String, Integer> pageIndexByTag = new HashMap<>();
	private final List<String> calls = new ArrayList<>();

	private final BrandRow brand = new BrandRow(1L, "cclime_official", "111", BrandStatus.ACTIVE, null, 12, true);

	// ── 스텁 대역 ───────────────────────────────────────────────────────────

	private static final class InMemoryHashtagRepo extends BrandHashtagRepository {
		final Map<Long, List<String>> tags = new HashMap<>();
		final Set<String> stored = new LinkedHashSet<>();
		final List<HashtagPostInsert> inserted = new ArrayList<>();
		/** key: "brandId:shortCode" → 그 게시물이 매칭된 태그 전체(순서 보존, 중복 없음). */
		final Map<String, LinkedHashSet<String>> matchedTags = new HashMap<>();

		InMemoryHashtagRepo() {
			super(null);
		}

		@Override
		public List<String> findTags(long brandId) {
			return tags.getOrDefault(brandId, List.of());
		}

		@Override
		public Set<String> existingCodes(long brandId, List<String> codes) {
			return codes.stream().filter(c -> stored.contains(brandId + ":" + c))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		@Override
		public void insertPost(HashtagPostInsert post) {
			inserted.add(post);
			stored.add(post.brandId() + ":" + post.shortCode());
		}

		@Override
		public void recordTagMatch(long brandId, String shortCode, String tag) {
			matchedTags.computeIfAbsent(brandId + ":" + shortCode, k -> new LinkedHashSet<>()).add(tag);
		}

		@Override
		public void recordTagMatches(long brandId, java.util.Collection<String> shortCodes, String tag) {
			for (String shortCode : shortCodes) {
				recordTagMatch(brandId, shortCode, tag);
			}
		}

		HashtagPostInsert insertedByCode(String code) {
			return inserted.stream().filter(p -> p.shortCode().equals(code)).findFirst().orElseThrow();
		}

		Set<String> matchedTagsOf(long brandId, String shortCode) {
			return matchedTags.getOrDefault(brandId + ":" + shortCode, new LinkedHashSet<>());
		}
	}

	private static final class StubBrandRepository extends BrandRepository {
		String biography;

		StubBrandRepository() {
			super(null);
		}

		@Override
		public String findBiography(long brandId) {
			return biography;
		}
	}

	/** 전송 람다는 무해값 — judge()를 오버라이드해 실제 HTTP 경로를 타지 않는다. */
	private static final class FakeJudge extends BrandMentionJudge {
		final List<String> captionsSeen = new ArrayList<>();
		final List<String> biographiesSeen = new ArrayList<>();
		Verdict nextVerdict = Verdict.IRRELEVANT;
		boolean fail;

		FakeJudge() {
			super((path, body) -> "{}", true, "unused-model");
		}

		@Override
		public Verdict judge(String brandUsername, String brandBiography, List<String> brandTags,
				String authorUsername, String caption) {
			captionsSeen.add(caption);
			biographiesSeen.add(brandBiography);
			if (fail) {
				throw new IllegalStateException("판정 실패(테스트)");
			}
			return nextVerdict;
		}
	}

	// ── fake HikerHttp — 태그별 페이지 큐 라우팅 ──────────────────────────────

	private HikerClient client() {
		return new HikerClient(path -> {
			calls.add(path);
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

	private BrandHashtagCollectService service(int maxPages) {
		return new BrandHashtagCollectService(client(), new BrandCallContext(), repo, judge, brands, WINDOW_DAYS, maxPages);
	}

	private long tagCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/hashtag/medias/recent")).count();
	}

	// ── JSON 픽스처 빌더(HikerClientHashtagTest 관용구 복제) ──────────────────

	private static String sectionsBody(String nextPageId, String... medias) {
		String items = String.join(",", medias);
		String cursor = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"sections":[{"layout_content":{"medias":[%s]}}],
				 "more_available":%s},"next_page_id":%s}"""
				.formatted(items, nextPageId != null, cursor);
	}

	private static String media(String code, long takenAt, String username, String taggedUser, String caption) {
		String usertags = taggedUser == null ? "{\"in\":[]}"
				: "{\"in\":[{\"user\":{\"username\":\"" + taggedUser + "\"}}]}";
		return """
				{"media":{"code":"%s","taken_at":%d,"media_type":1,
				 "caption":{"text":"%s"},
				 "user":{"username":"%s","pk":"111","full_name":"작가","profile_pic_url":"https://p"},
				 "like_count":10,"comment_count":2,"usertags":%s}}"""
				.formatted(code, takenAt, caption, username, usertags);
	}

	// ── 케이스 1: 자사 게시자(계정명 정확 일치) → SELF/RULE, 판정기 미호출 ──────
	// (2026-08-17 — 제외 문자열 기능 폐기로 판정 재료가 "게시자 username == 브랜드 계정명"
	//  정확 일치(대소문자 무시)로 좁혀졌다. 근사 매치는 케이스 1b가 확인한다.)

	@Test
	void 자사_게시자는_SELF_RULE로_저장되고_판정기는_호출되지_않는다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("AAA", RECENT, brand.username(), null, "그냥 캡션"))));

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("AAA");
		assertThat(saved.verdict()).isEqualTo("SELF");
		assertThat(saved.verdictSource()).isEqualTo("RULE");
		assertThat(judge.captionsSeen).isEmpty();
	}

	@Test
	void 계정명을_포함할_뿐_정확히_일치하지_않는_게시자는_SELF가_아니다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("STAFF1", RECENT, "cclime_official_staff", null, "무관 캡션"))));
		judge.nextVerdict = BrandMentionJudge.Verdict.IRRELEVANT;

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("STAFF1");
		assertThat(saved.verdict()).isNotEqualTo("SELF");
	}

	// ── 케이스 2: 등록 계정 유저태그 → DIRECT_TAGGED/RULE, 판정기 미호출 ────────

	@Test
	void 계정_유저태그는_DIRECT_TAGGED_RULE로_저장되고_판정기는_호출되지_않는다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("BBB", RECENT, "poster1", "cclime_official", "무관한 캡션"))));

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("BBB");
		assertThat(saved.verdict()).isEqualTo("DIRECT_TAGGED");
		assertThat(saved.verdictSource()).isEqualTo("RULE");
		assertThat(judge.captionsSeen).isEmpty();
	}

	// ── 케이스 3: 캡션 멘션만 → RELEVANT/MENTION, 판정기 미호출 ────────────────

	@Test
	void 캡션_멘션은_RELEVANT_MENTION으로_저장되고_판정기는_호출되지_않는다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("CCC", RECENT, "poster2", null, "@cclime_official 다녀왔어요"))));

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("CCC");
		assertThat(saved.verdict()).isEqualTo("RELEVANT");
		assertThat(saved.verdictSource()).isEqualTo("MENTION");
		assertThat(judge.captionsSeen).isEmpty();
	}

	// ── 케이스 4: 부분일치 멘션(@brand+접미사)은 불일치 → LLM 경로 ──────────────

	@Test
	void 브랜드명_뒤에_문자가_이어붙은_멘션은_불일치로_LLM_경로로_간다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("DDD", RECENT, "poster3", null, "@cclime_officialkr 방문 후기"))));
		judge.nextVerdict = BrandMentionJudge.Verdict.RELEVANT;

		service(4).sweep(brand);

		assertThat(judge.captionsSeen).containsExactly("@cclime_officialkr 방문 후기");
		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("DDD");
		assertThat(saved.verdict()).isEqualTo("RELEVANT");
		assertThat(saved.verdictSource()).isEqualTo("LLM");
	}

	// ── 케이스 5: 나머지는 LLM 판정(IRRELEVANT) ────────────────────────────────

	@Test
	void 규칙_불일치_게시물은_LLM_판정_결과로_저장된다() {
		setTags("cclime");
		brands.biography = "뷰티 편집숍";
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("EEE", RECENT, "poster4", null, "동명 도자기 브랜드 이야기"))));
		judge.nextVerdict = BrandMentionJudge.Verdict.IRRELEVANT;

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("EEE");
		assertThat(saved.verdict()).isEqualTo("IRRELEVANT");
		assertThat(saved.verdictSource()).isEqualTo("LLM");
		assertThat(judge.biographiesSeen).containsExactly("뷰티 편집숍");
	}

	// ── 케이스 6: 윈도우 밖 게시물 미저장 ───────────────────────────────────────

	@Test
	void 윈도우_밖_게시물은_저장되지_않고_판정기도_호출되지_않는다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("OLD", OUT_OF_WINDOW, "poster5", null, "오래된 글"))));

		service(4).sweep(brand);

		assertThat(((InMemoryHashtagRepo) repo).inserted).isEmpty();
		assertThat(judge.captionsSeen).isEmpty();
	}

	// ── 케이스 7: 기존 게시물 있는 페이지는 신규만 처리 후 중단 ─────────────────

	@Test
	void 페이지에_기존_게시물이_있으면_신규만_처리하고_다음_페이지는_요청하지_않는다() {
		setTags("cclime");
		((InMemoryHashtagRepo) repo).stored.add(brand.id() + ":OLD1");
		pagesByTag.put("cclime", List.of(
				sectionsBody("p2",
						media("NEW1", RECENT, "poster6", null, "새 글"),
						media("OLD1", RECENT, "poster7", null, "이미 저장된 글")),
				sectionsBody(null, media("NEW2", RECENT, "poster8", null, "도달하면 안 되는 페이지"))));

		service(4).sweep(brand);

		assertThat(((InMemoryHashtagRepo) repo).inserted).extracting(HashtagPostInsert::shortCode)
				.containsExactly("NEW1");
		assertThat(tagCalls()).isEqualTo(1);
	}

	// ── 케이스 8: 기존 게시물 없으면 상한까지 전진 ──────────────────────────────

	@Test
	void 기존_게시물이_없으면_최대_페이지_상한까지_전진한다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(
				sectionsBody("p2", media("P1A", RECENT, "poster9", null, "1페이지")),
				sectionsBody("p3", media("P2A", RECENT, "poster10", null, "2페이지")),
				sectionsBody(null, media("P3A", RECENT, "poster11", null, "3페이지 — 상한 밖"))));

		service(2).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(((InMemoryHashtagRepo) repo).inserted).extracting(HashtagPostInsert::shortCode)
				.containsExactlyInAnyOrder("P1A", "P2A");
	}

	// ── 케이스 9: LLM 실패 게시물은 미저장 ──────────────────────────────────────

	@Test
	void LLM_판정_실패_게시물은_저장되지_않는다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("FAIL1", RECENT, "poster12", null, "무관 캡션"))));
		judge.fail = true;

		service(4).sweep(brand);

		assertThat(((InMemoryHashtagRepo) repo).inserted).isEmpty();
	}

	// ── 케이스 10: 태그 없으면 콜 0 ─────────────────────────────────────────────

	@Test
	void 태그가_없으면_해시태그_콜이_발생하지_않는다() {
		service(4).sweep(brand);

		assertThat(calls).isEmpty();
		assertThat(((InMemoryHashtagRepo) repo).inserted).isEmpty();
	}

	// ── 케이스 11: 태그마다 별도 열거, 인코딩 확인 ──────────────────────────────

	@Test
	void 태그마다_별도로_열거하고_각_태그가_인코딩되어_전달된다() {
		setTags("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("TAG1", RECENT, "poster13", null, "태그1"))));
		pagesByTag.put("끌리메", List.of(sectionsBody(null,
				media("TAG2", RECENT, "poster14", null, "태그2"))));

		service(4).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(calls).contains("/v2/hashtag/medias/recent?name=cclime");
		assertThat(calls).contains("/v2/hashtag/medias/recent?name=%EB%81%8C%EB%A6%AC%EB%A9%94");
	}

	// ── 케이스 12: SELF 판정은 계정명 대소문자를 무시하고 정확 일치한다 ────────

	@Test
	void SELF_판정은_계정명_대소문자를_무시한다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("SELF1", RECENT, "CClime_Official", null, "무관 캡션"))));   // brand.username()="cclime_official"

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("SELF1");
		assertThat(saved.verdict()).isEqualTo("SELF");
		assertThat(saved.verdictSource()).isEqualTo("RULE");
	}

	// ── 케이스 13(architect 리뷰 결함1): 태그 A가 저장한 코드를 태그 B가 만나도 B는 계속 전진한다 ──

	@Test
	void 태그_A가_저장한_코드를_태그_B가_만나도_조기_종료하지_않고_다음_페이지까지_전진한다() {
		setTags("cclime", "끌리메");   // 순서 고정 — cclime이 먼저 SHARED1을 저장한다
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("SHARED1", RECENT, "posterA", null, "태그A 글"))));
		// 태그 B의 1페이지에 태그 A가 방금 저장한 SHARED1이 섞여 있다 — existingCodes에는 잡히지만
		// insertedThisRun에도 있으므로 조기 종료 트리거가 아니어야 한다(수정 1 핵심).
		pagesByTag.put("끌리메", List.of(
				sectionsBody("p2",
						media("SHARED1", RECENT, "posterA", null, "태그A 글"),
						media("B1", RECENT, "posterB1", null, "태그B 1페이지 새 글")),
				sectionsBody(null, media("B2", RECENT, "posterB2", null, "태그B 2페이지 새 글"))));

		service(4).sweep(brand);

		// SHARED1은 한 번만 저장(재판정·중복 삽입 없음), B1·B2는 태그 B가 2페이지까지 전진해야 나온다.
		assertThat(((InMemoryHashtagRepo) repo).inserted).extracting(HashtagPostInsert::shortCode)
				.containsExactlyInAnyOrder("SHARED1", "B1", "B2");
		assertThat(calls.stream().filter(c -> c.contains("name=%EB%81%8C%EB%A6%AC%EB%A9%94")).count())
				.isEqualTo(2);   // 태그 B는 1페이지에서 끊기지 않고 2페이지까지 콜했다
	}

	// ── 케이스 16(2026-08-19 매칭 태그 전체 기록): 여러 태그가 매칭되면 전체 집합이 남는다 ──

	/**
	 * 케이스 13과 같은 픽스처(SHARED1이 cclime에 먼저 저장되고 끌리메가 나중에 같은 게시물을
	 * 다시 만남) — insertPost는 한 번만(기존 계약 불변) 일어나지만, 매칭 태그 집합에는 두 태그
	 * 모두 남아야 한다(was 사용자 스코프 필터의 재료). matched_tag(단일, 디버그용)는 최초 저장
	 * 태그만 유지하고(HashtagPostInsert.matchedTag), 전체 집합은 별도로 누적된다.
	 */
	@Test
	void 여러_태그에_매칭된_게시물은_매칭_태그_전체가_기록된다() {
		setTags("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("SHARED1", RECENT, "posterA", null, "태그A 글"))));
		pagesByTag.put("끌리메", List.of(sectionsBody(null,
				media("SHARED1", RECENT, "posterA", null, "태그A 글"))));

		service(4).sweep(brand);

		InMemoryHashtagRepo memRepo = (InMemoryHashtagRepo) repo;
		assertThat(memRepo.insertedByCode("SHARED1").matchedTag()).isEqualTo("cclime");   // 최초 저장 태그(디버그용) 불변
		assertThat(memRepo.matchedTagsOf(brand.id(), "SHARED1")).containsExactlyInAnyOrder("cclime", "끌리메");
	}

	/** 한 태그의 신규 저장 게시물도 그 태그가 매칭 집합에 즉시 기록된다(단일 태그 케이스). */
	@Test
	void 신규_저장_게시물도_저장한_태그가_매칭_집합에_기록된다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("AAA", RECENT, "poster1", null, "글"))));

		service(4).sweep(brand);

		assertThat(((InMemoryHashtagRepo) repo).matchedTagsOf(brand.id(), "AAA")).containsExactly("cclime");
	}

	// ── 케이스 14(nit 반영 확인): 저장 레코드 14필드 매핑 고정 ─────────────────

	@Test
	void 저장_레코드는_원본_필드_전체를_그대로_매핑한다() {
		setTags("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null, """
				{"media":{"code":"FULL1","taken_at":%d,"product_type":"clips",
				 "caption":{"text":"필드 검증 캡션"},
				 "user":{"username":"poster_full","pk":"111","full_name":"작가풀네임",
				 "profile_pic_url":"https://pic.example/author.jpg"},
				 "like_count":77,"comment_count":9,
				 "image_versions2":{"candidates":[{"url":"https://pic.example/thumb.jpg"}]},
				 "usertags":{"in":[]}}}""".formatted(RECENT))));
		judge.nextVerdict = BrandMentionJudge.Verdict.RELEVANT;

		service(4).sweep(brand);

		HashtagPostInsert saved = ((InMemoryHashtagRepo) repo).insertedByCode("FULL1");
		assertThat(saved.brandId()).isEqualTo(brand.id());
		assertThat(saved.matchedTag()).isEqualTo("cclime");
		assertThat(saved.shortCode()).isEqualTo("FULL1");
		assertThat(saved.authorUsername()).isEqualTo("poster_full");
		assertThat(saved.authorFullName()).isEqualTo("작가풀네임");
		assertThat(saved.authorProfilePicUrl()).isEqualTo("https://pic.example/author.jpg");
		assertThat(saved.takenAt()).isEqualTo(Instant.ofEpochSecond(RECENT).atOffset(ZoneOffset.UTC));
		assertThat(saved.caption()).isEqualTo("필드 검증 캡션");
		assertThat(saved.contentType()).isEqualTo("REELS");
		assertThat(saved.thumbnailUrl()).isEqualTo("https://pic.example/thumb.jpg");
		assertThat(saved.likes()).isEqualTo(77L);
		assertThat(saved.comments()).isEqualTo(9L);
		assertThat(saved.verdict()).isEqualTo("RELEVANT");
		assertThat(saved.verdictSource()).isEqualTo("LLM");
	}

	// ── 케이스 15(결함2 검증): authorUsername 없는 media는 그 게시물만 스킵하고 스윕은 계속된다 ──

	@Test
	void 작성자_없는_게시물은_스킵되고_나머지_게시물은_정상_저장된다() {
		setTags("cclime");
		String noAuthor = """
				{"media":{"code":"NOAUTH1","taken_at":%d,"media_type":1,
				 "caption":{"text":"작성자 없음"},"like_count":5,"comment_count":1,
				 "usertags":{"in":[]}}}""".formatted(RECENT);
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				noAuthor, media("OK1", RECENT, "posterOk", null, "정상 글"))));

		service(4).sweep(brand);

		assertThat(((InMemoryHashtagRepo) repo).inserted).extracting(HashtagPostInsert::shortCode)
				.containsExactly("OK1");
	}

	// ── 헬퍼 ────────────────────────────────────────────────────────────────

	private void setTags(String... tags) {
		((InMemoryHashtagRepo) repo).tags.put(brand.id(), List.of(tags));
	}
}
