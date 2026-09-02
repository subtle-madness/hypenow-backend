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
	private final Set<String> failingTags = new HashSet<>();
	private final Map<String, Integer> failAfterCallByTag = new HashMap<>();
	private final List<String> calls = new ArrayList<>();

	private final BrandRow brand =
			new BrandRow(1L, "cclime_official", "111", BrandStatus.ACTIVE, LocalDate.now(), 12, true);

	// ── 스텁 대역 ───────────────────────────────────────────────────────────

	private static final class StubTags extends BrandHashtagRepository {
		List<String> tags = List.of();
		/** markRunStarted/markRunFinished 호출 인자 캡처(태그별 실행 상태 기록, 2026-08-31). */
		final List<String> runStarted = new ArrayList<>();
		final List<String> runFinished = new ArrayList<>();
		final Map<String, Integer> runFoundCounts = new HashMap<>();
		final Map<String, Boolean> runFailed = new HashMap<>();

		StubTags() {
			super(null);
		}

		@Override
		public List<String> findTags(long brandId) {
			return tags;
		}

		@Override
		public void markRunStarted(long brandId, String tag) {
			runStarted.add(tag);
		}

		@Override
		public void markRunFinished(long brandId, String tag, int foundCount, boolean failed) {
			runFinished.add(tag);
			runFoundCounts.put(tag, foundCount);
			runFailed.put(tag, failed);
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
			if (failingTags.contains(tag)) {
				throw new RuntimeException("의도된 태그 열거 실패: " + tag);
			}
			int callIndex = pageIndexByTag.merge(tag, 1, Integer::sum);   // 이 태그의 1-based 호출 번호
			Integer failAt = failAfterCallByTag.get(tag);
			if (failAt != null && callIndex == failAt) {
				throw new RuntimeException("의도된 페이지 실패: " + tag + " 호출 " + callIndex);
			}
			if (!pagesByTag.containsKey(tag)) {
				throw new IllegalStateException("등록 안 된 태그 콜: " + tag);
			}
			List<String> pages = pagesByTag.get(tag);
			int idx = callIndex - 1;
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
				 "user":{"username":"%s","pk":9001,"full_name":"작가","profile_pic_url":"https://p"},
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

	/**
	 * 이미 풀에 있는 행(tagged·direct)에 hashtag 성분만 얹는 병기는 행이 늘지 않아 상한 밖이다.
	 * 겹침 2건 + 신규 2건을 같은 페이지에 섞어, 겹침 쪽에 남은 예산(brandNew의 {@code limit})이
	 * 잘못 전이되지 않는지까지 고정한다(겹침 리스트에 예산 limit을 걸면 이 테스트가 깨진다).
	 */
	@Test
	void 겹침_병기는_편입_상한을_소모하지_않는다() {
		tags.tags = List.of("cclime");
		tagged.known.add("OVERLAP1");   // tagged로 이미 확보한 게시물(hashtag 성분은 없음)
		tagged.known.add("OVERLAP2");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("OVERLAP1", RECENT, "poster0"), media("OVERLAP2", RECENT, "poster0b"),
				media("N1", RECENT, "poster1"), media("N2", RECENT, "poster2"))));

		service(4, 1).sweep(brand);

		// 상한 1이라 신규는 N1 하나뿐이지만, 겹침 OVERLAP1·OVERLAP2는 둘 다 상한과 무관하게 병기된다.
		assertThat(tagged.upsertedHashtag).containsExactlyInAnyOrder("OVERLAP1", "OVERLAP2", "N1");
		assertThat(tagged.matchedTagsOf("OVERLAP1")).containsExactly("cclime");
		assertThat(tagged.matchedTagsOf("OVERLAP2")).containsExactly("cclime");
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

	// ── 태그 단위 격리 ──────────────────────────────────────────────────────

	/**
	 * 태그 목록은 등록순 고정 순서라(tags.findTags), 한 태그의 Hiker 실패(5xx·타임아웃·파싱 이상)를
	 * doSweep이 격리하지 않으면 그 태그가 매 스윕마다 뒤 태그 전부를 영구 굶긴다.
	 */
	@Test
	void 한_태그의_실패는_다음_태그를_굶기지_않는다() {
		tags.tags = List.of("실패", "cclime");
		failingTags.add("실패");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("N1", RECENT, "poster1"))));

		service(4, 1000).sweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("N1");
	}

	/**
	 * 재리뷰(2026-08-27) 반영 — 예산 차감이 태그 루프 종료 후 일괄이면, 태그 도중 예외로 그 지점까지
	 * 이미 커밋된 페이지분이 예산에서 안 빠져 다음 태그가 초과 편입한다. cclime은 1페이지에서 2건을
	 * 편입한 뒤 2페이지 요청에서 실패(격리)한다 — 상한 3 중 남은 예산은 1이어야 하고, 끌리메는
	 * 딱 1건만 편입해야 총합이 상한 3을 넘지 않는다.
	 */
	@Test
	void 태그_중도_실패해도_이미_편입된_페이지는_예산에서_차감된다() {
		tags.tags = List.of("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(
				sectionsBody("p2", media("A1", RECENT, "poster1"), media("A2", RECENT, "poster2"))));
		failAfterCallByTag.put("cclime", 2);   // 1페이지는 정상, 2페이지 요청에서 예외
		pagesByTag.put("끌리메", List.of(sectionsBody(null,
				media("B1", RECENT, "poster3"), media("B2", RECENT, "poster4"), media("B3", RECENT, "poster5"))));

		service(4, 3).sweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("A1", "A2", "B1");
	}

	// ── 태그별 실행 상태 기록(FE 요청, 2026-08-31) ────────────────────────────

	@Test
	void 정상_종료된_태그는_신규_편입_건수와_함께_성공으로_기록된다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("N1", RECENT, "poster1"), media("N2", RECENT, "poster2"))));

		service(4, 1000).sweep(brand);

		assertThat(tags.runStarted).containsExactly("cclime");
		assertThat(tags.runFinished).containsExactly("cclime");
		assertThat(tags.runFoundCounts).containsEntry("cclime", 2);
		assertThat(tags.runFailed).containsEntry("cclime", false);
	}

	/** Hiker 404(게시물 0건)는 fetchHashtagRecentPage가 이미 빈 페이지로 흡수한다 — 정상 종료다. */
	@Test
	void 게시물_0건_태그도_실패가_아니라_성공_0건으로_기록된다() {
		tags.tags = List.of("cclime");
		pagesByTag.put("cclime", List.of(sectionsBody(null)));

		service(4, 1000).sweep(brand);

		assertThat(tags.runFinished).containsExactly("cclime");
		assertThat(tags.runFoundCounts).containsEntry("cclime", 0);
		assertThat(tags.runFailed).containsEntry("cclime", false);
	}

	@Test
	void 예외로_격리된_태그는_실패로_기록되고_건수는_0이다() {
		tags.tags = List.of("실패");
		failingTags.add("실패");

		service(4, 1000).sweep(brand);

		assertThat(tags.runStarted).containsExactly("실패");
		assertThat(tags.runFinished).containsExactly("실패");
		assertThat(tags.runFoundCounts).containsEntry("실패", 0);
		assertThat(tags.runFailed).containsEntry("실패", true);
	}

	@Test
	void 태그별_실행_기록은_서로_독립적이다() {
		tags.tags = List.of("실패", "cclime");
		failingTags.add("실패");
		pagesByTag.put("cclime", List.of(sectionsBody(null, media("N1", RECENT, "poster1"))));

		service(4, 1000).sweep(brand);

		assertThat(tags.runStarted).containsExactly("실패", "cclime");
		assertThat(tags.runFailed).containsEntry("실패", true).containsEntry("cclime", false);
		assertThat(tags.runFoundCounts).containsEntry("실패", 0).containsEntry("cclime", 1);
	}

	/** 편입 상한으로 아예 열거되지 않은 태그는 시작·종료 기록 자체가 없다(열거 자체가 없었으므로). */
	@Test
	void 편입_상한으로_열거되지_않은_태그는_실행_기록이_없다() {
		tags.tags = List.of("cclime", "끌리메");
		pagesByTag.put("cclime", List.of(sectionsBody(null,
				media("N1", RECENT, "poster1"), media("N2", RECENT, "poster2"))));

		service(4, 2).sweep(brand);

		assertThat(tags.runStarted).containsExactly("cclime");
		assertThat(tags.runFinished).containsExactly("cclime");
	}
}
