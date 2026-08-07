package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCommentRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 태그 수집 본체(2026-08-06 스펙 + 매일 전량 개정) — CollectServiceTest 관용구(fake
 * HikerHttp + 스텁 서브클래스, DB 없음)로 매일 전량 105개 추종·윈도우 컷·브랜드 프로필 매일
 * 갱신·부재=0·0 캐리·게시자 stale·댓글 게이트를 검증한다.
 */
class BrandCollectServiceTest {

	private static final long NOW = Instant.now().getEpochSecond();
	private static final long RECENT = NOW - 5L * 86400;             // 윈도우(90일) 안
	private static final long RETRO_IN_WINDOW = NOW - 60L * 86400;   // 소급 태그지만 윈도우 안
	private static final long OLD_95D = NOW - 95L * 86400;           // 윈도우 밖

	private static final String BRAND_PROFILE_JSON = """
			{"user":{"pk":111,"username":"brandx","full_name":"브랜드","profile_pic_url":"https://p",
			"biography":"소개","follower_count":1234,"following_count":10,"media_count":5,
			"is_private":false}}""";

	private final RecordingWriter writer = new RecordingWriter();
	private final StubSnapshots snapshots = new StubSnapshots();
	private final StubComments comments = new StubComments();
	private final InMemoryTagged tagged = new InMemoryTagged();
	private final InMemoryAuthors authors = new InMemoryAuthors();

	private final List<String> calls = new ArrayList<>();
	private final List<String> tagPages = new ArrayList<>();
	private final Set<String> failingAuthorIds = new HashSet<>();
	private boolean tagNotFound = false;
	private boolean brandProfileFails = false;
	private int tagCall = 0;

	private final BrandRow brand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null);

	// ── 스텁 대역(CollectServiceTest NoopCommentRepository 관용구) ────────────

	private static final class RecordingWriter extends BrandSnapshotWriter {
		final List<PostInfo> saved = new ArrayList<>();
		final List<String> profileSaves = new ArrayList<>();

		RecordingWriter() {
			super(null, null, null);
		}

		@Override
		public void savePost(LocalDate on, PostInfo post) {
			saved.add(post);
		}

		@Override
		public void saveBrandProfile(long brandId, String username, LocalDate on, ProfileInfo profile) {
			profileSaves.add(username + ":" + profile.followers());
		}

		PostInfo savedByCode(String code) {
			return saved.stream().filter(p -> p.shortCode().equals(code)).findFirst().orElseThrow();
		}
	}

	private static final class StubSnapshots extends BrandSnapshotRepository {
		Set<String> repostsCarry = Set.of();
		Set<String> sharesCarry = Set.of();

		StubSnapshots() {
			super(null);
		}

		@Override
		public Set<String> codesWithRepostsZeroCarry(Collection<String> codes, LocalDate today) {
			return repostsCarry;
		}

		@Override
		public Set<String> codesWithSharesZeroCarry(Collection<String> codes, LocalDate today) {
			return sharesCarry;
		}
	}

	private static final class StubComments extends BrandCommentRepository {
		final List<String> upserted = new ArrayList<>();

		StubComments() {
			super(null);
		}

		@Override
		public Set<String> findIds(String shortCode) {
			return Set.of();
		}

		@Override
		public void upsertForPost(String shortCode, List<CommentInfo> fetched) {
			upserted.add(shortCode);
		}
	}

	private static final class InMemoryTagged extends TaggedPostRepository {
		final Set<String> known = new LinkedHashSet<>();
		final List<String> inserted = new ArrayList<>();
		final Map<String, Long> collectedCounts = new HashMap<>();

		InMemoryTagged() {
			super(null);
		}

		@Override
		public Set<String> knownCodes(long brandId) {
			return new LinkedHashSet<>(known);
		}

		@Override
		public void insert(long brandId, PostInfo post) {
			inserted.add(post.shortCode());
			known.add(post.shortCode());
		}

		@Override
		public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
			Map<String, Long> out = new HashMap<>();
			for (String c : codes) {
				out.put(c, collectedCounts.getOrDefault(c, 0L));
			}
			return out;
		}

		@Override
		public void updateCommentsCollected(long brandId, String shortCode, long count) {
			collectedCounts.put(shortCode, count);
		}
	}

	private static final class InMemoryAuthors extends AuthorProfileRepository {
		Set<String> fresh = new HashSet<>();
		// 병렬 upsert 대비 스레드 안전 리스트(직결 executor를 쓰는 기존 테스트는 영향 없음).
		final List<String> upserted = Collections.synchronizedList(new ArrayList<>());

		InMemoryAuthors() {
			super(null);
		}

		@Override
		public void upsert(AuthorInfo a) {
			upserted.add(a.igUserId());
		}

		@Override
		public Set<String> freshIgUserIds(Collection<String> igUserIds, Instant staleBefore) {
			Set<String> out = new HashSet<>(fresh);
			out.retainAll(new HashSet<>(igUserIds));
			return out;
		}
	}

	// ── fake HikerHttp — 경로별 라우팅 ───────────────────────────────────────

	private HikerClient client() {
		return new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/by/username")) {
				if (brandProfileFails) {
					throw new HikerFetchException("프로필 500");
				}
				return BRAND_PROFILE_JSON;
			}
			if (path.startsWith("/v2/user/tag/medias")) {
				if (tagNotFound) {
					throw new SubjectNotFoundException("Entries not found");
				}
				return tagPages.get(Math.min(tagCall++, tagPages.size() - 1));
			}
			if (path.startsWith("/v2/user/by/id")) {
				// 쿼리 파라미터명은 id(08-07 실측 교정 — HikerClient.fetchAuthorProfile 주석 참조)
				String id = path.substring(path.indexOf("?id=") + "?id=".length());
				if (failingAuthorIds.contains(id)) {
					throw new HikerFetchException("게시자 프로필 500");
				}
				return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\",\"follower_count\":100,\"is_private\":false}}"
						.formatted(id, id);
			}
			if (path.startsWith("/v2/media/comments")) {
				return """
						{"response":{"comments":[{"pk":"nc1","text":"새 댓글","comment_like_count":1,
						"created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}]},
						"next_page_id":null}""";
			}
			throw new IllegalStateException("예상 밖 콜: " + path);
		});
	}

	private BrandCollectService service(int windowPosts) {
		return new BrandCollectService(client(), writer, snapshots, comments, tagged, authors,
				Runnable::run, 90, windowPosts, 3, 30);
	}

	private long tagCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/user/tag/medias")).count();
	}

	private long authorCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/user/by/id")).count();
	}

	private long commentCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/media/comments")).count();
	}

	// ── JSON 빌더 ────────────────────────────────────────────────────────────

	/** 릴스 아이템 — extra에 저장·공유·리포스트/숨김 키를 끼워 넣는다(",\"save_count\":10" 형식). */
	private static String reel(String code, Long takenAt, long commentCount, long authorPk, String extra) {
		String takenAtField = takenAt == null ? "" : "\"taken_at\":" + takenAt + ",";
		return """
				{"code":"%s",%s"product_type":"clips","like_count":10,"comment_count":%d,
				"ig_play_count":1000,"play_count":1000%s,
				"user":{"pk":%d,"username":"author_%d","full_name":"작가","profile_pic_url":"https://p"}}"""
				.formatted(code, takenAtField, commentCount, extra, authorPk, authorPk);
	}

	private static String page(String nextPageId, String... items) {
		String next = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"items":[%s],"more_available":%s},"next_page_id":%s}"""
				.formatted(String.join(",", items), nextPageId != null, next);
	}

	// ── 열거 워크(매일 전량) ─────────────────────────────────────────────────

	@Test
	void 스윕은_목표_개수까지_커서를_추종한다() {
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(3).sweep(brand);   // 목표 3개 — 2페이지째에서 4개 도달, 3페이지는 부르지 않는다

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C", "D");
	}

	@Test
	void 스윕은_페이지_전체가_컷_이전이면_중단한다() {
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		// 2페이지 전체가 90일 컷 이전 — 커서가 남아도 중단(페이지 "전체" 조건 — 스펙 §5·§6).
		tagPages.add(page("p3", reel("Old1", OLD_95D, 0, 102, ""), reel("Old2", OLD_95D, 0, 103, "")));
		tagPages.add(page(null, reel("NeverFetched", RECENT, 0, 104, "")));

		service(105).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactly("A");   // 컷 이전 게시물은 적재 대상 아님
	}

	@Test
	void 페이지_중간_기지_뒤의_소급_태그_신규를_놓치지_않는다() {
		tagged.known.add("KnownA");
		// 소급 태그: 기지 게시물보다 뒤 순번에 실림 — "기지 만나면 중단"이면 놓친다(스펙 §6).
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("RetroB", RETRO_IN_WINDOW, 0, 102, "")));

		service(105).sweep(brand);

		assertThat(tagged.inserted).containsExactly("RetroB");
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactlyInAnyOrder("KnownA", "RetroB");
	}

	@Test
	void 기지_게시물_스냅샷을_갱신하고_신규만_링크한다() {
		tagged.known.add("KnownA");
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("NewB", RECENT, 0, 102, "")));

		service(105).sweep(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactlyInAnyOrder("KnownA", "NewB");
		assertThat(tagged.inserted).containsExactly("NewB");
	}

	@Test
	void 윈도우_밖_게시물은_적재하지_않는다() {
		tagPages.add(page(null, reel("Old95", OLD_95D, 0, 101, ""), reel("NoTakenAt", null, 0, 102, "")));

		service(105).sweep(brand);

		assertThat(writer.saved).isEmpty();
		assertThat(tagged.inserted).isEmpty();
	}

	@Test
	void 태그_0건_계정도_프로필_갱신은_한다() {
		tagNotFound = true;

		service(105).sweep(brand);

		assertThat(writer.profileSaves).containsExactly("brandx:1234");   // 매일 프로필 갱신(08-06 개정)
		assertThat(writer.saved).isEmpty();
		assertThat(tagged.inserted).isEmpty();
		assertThat(authorCalls()).isZero();
		assertThat(commentCalls()).isZero();
	}

	// ── 브랜드 프로필 매일 갱신 ──────────────────────────────────────────────

	@Test
	void 브랜드_프로필_갱신_실패는_열거를_막지_않는다() {
		brandProfileFails = true;
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(105).sweep(brand);   // 예외가 새면 여기서 터진다

		assertThat(writer.profileSaves).isEmpty();
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
	}

	// ── 복권 3종 적재 규칙(부재=0·0 캐리) ────────────────────────────────────

	@Test
	void 부재_0_간주는_saves_관측시에만_적용한다() {
		tagPages.add(page(null,
				reel("SaveOnly", RECENT, 0, 101, ",\"save_count\":10"),
				reel("AllMiss", RECENT, 0, 102, ""),
				reel("HiddenShares", RECENT, 0, 103, ",\"save_count\":7,\"share_count_disabled\":true")));

		service(105).sweep(brand);

		PostInfo saveOnly = writer.savedByCode("SaveOnly");
		assertThat(saveOnly.saves()).isEqualTo(10L);
		assertThat(saveOnly.shares()).isZero();     // saves 관측 근거 → 부재=0(08-05 규칙)
		assertThat(saveOnly.reposts()).isZero();

		PostInfo allMiss = writer.savedByCode("AllMiss");
		assertThat(allMiss.saves()).isNull();       // 전부 꽝 세션 — 근거 없음, null(미관측) 유지
		assertThat(allMiss.shares()).isNull();
		assertThat(allMiss.reposts()).isNull();

		PostInfo hidden = writer.savedByCode("HiddenShares");
		assertThat(hidden.shares()).isNull();       // 숨김은 0이 아니라 비공개 — null 유지
		assertThat(hidden.reposts()).isZero();
	}

	@Test
	void 잔여_null은_0_캐리_이력으로_잇는다() {
		snapshots.repostsCarry = Set.of("AllMiss");
		tagPages.add(page(null, reel("AllMiss", RECENT, 0, 101, "")));

		service(105).sweep(brand);

		PostInfo p = writer.savedByCode("AllMiss");
		assertThat(p.reposts()).isZero();   // 양수 이력 없음·전일 0 종료(스텁) → 0 캐리
		assertThat(p.shares()).isNull();    // 공유는 캐리 판정에 없음 — null 유지
	}

	// ── 게시자 프로필 ────────────────────────────────────────────────────────

	@Test
	void 게시자는_미보유이거나_30일_경과만_콜한다() {
		authors.fresh = Set.of("101");   // 101은 신선 — 콜 불필요. 102(실패)·103은 콜 대상.
		failingAuthorIds.add("102");
		tagPages.add(page(null,
				reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, ""), reel("C", RECENT, 0, 103, "")));

		service(105).sweep(brand);

		assertThat(authorCalls()).isEqualTo(2);              // 102·103만
		assertThat(authors.upserted).containsExactly("103"); // 102 실패는 격리 — 103은 계속
		assertThat(writer.saved).hasSize(3);                 // 게시자 실패가 지표 적재에 번지지 않는다
	}

	// ── core/enrichment 분리(등록 백필 단계식 ready — 2026-08-07) ────────────

	@Test
	void sweepCore는_게시자_댓글_콜_없이_적재까지만_한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		List<PostInfo> posts = service(105).sweepCore(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
		assertThat(tagged.inserted).containsExactly("A");
		assertThat(authorCalls()).isZero();     // 보강 콜은 core 밖 — ready 게이트가 여기서 끊긴다
		assertThat(commentCalls()).isZero();
		assertThat(posts).extracting(PostInfo::shortCode).containsExactly("A");
	}

	@Test
	void enrich는_core가_넘긴_게시물의_게시자와_댓글만_수집한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));
		BrandCollectService service = service(105);
		List<PostInfo> posts = service.sweepCore(brand);
		long tagCallsAfterCore = tagCalls();
		int savedAfterCore = writer.saved.size();

		service.enrich(brand, posts);

		assertThat(tagCalls()).isEqualTo(tagCallsAfterCore);   // 재열거 없음 — core 결과를 그대로 소비
		assertThat(writer.saved).hasSize(savedAfterCore);      // 지표 재적재 없음
		assertThat(authorCalls()).isEqualTo(1);
		assertThat(commentCalls()).isEqualTo(1);
		assertThat(comments.upserted).containsExactly("A");
	}

	// ── 댓글 게이트 ──────────────────────────────────────────────────────────

	@Test
	void 댓글은_comment_count가_저장값보다_클_때만_콜한다() {
		tagged.known.addAll(Set.of("SameCnt", "Grown", "ZeroCnt"));
		tagged.collectedCounts.put("SameCnt", 5L);
		tagged.collectedCounts.put("Grown", 5L);
		tagPages.add(page(null,
				reel("SameCnt", RECENT, 5, 101, ""),    // 저장값과 동일 — 콜 X
				reel("Grown", RECENT, 7, 102, ""),      // 증가 — 콜 O
				reel("ZeroCnt", RECENT, 0, 103, ""),    // 0 — 콜 X
				reel("FreshD", RECENT, 2, 104, "")));   // 신규(저장값 0) — 콜 O

		service(105).sweep(brand);

		assertThat(commentCalls()).isEqualTo(2);
		assertThat(comments.upserted).containsExactlyInAnyOrder("Grown", "FreshD");
		assertThat(tagged.collectedCounts)
				.containsEntry("Grown", 7L)
				.containsEntry("FreshD", 2L)
				.containsEntry("SameCnt", 5L);
	}

	// ── 보강 병렬화(동시 6 — 2026-08-07 스펙) ────────────────────────────────

	@Test
	void 보강_게시자_콜은_워커_풀_동시성으로_나가되_상한을_넘지_않는다() {
		tagPages.add(page(null,
				reel("A", RECENT, 0, 201, ""), reel("B", RECENT, 0, 202, ""),
				reel("C", RECENT, 0, 203, ""), reel("D", RECENT, 0, 204, ""),
				reel("E", RECENT, 0, 205, ""), reel("F", RECENT, 0, 206, "")));
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maxInFlight = new AtomicInteger();
		CyclicBarrier trio = new CyclicBarrier(3);   // 3콜이 "동시에" 모여야 통과 — 순차면 못 모인다
		HikerClient latched = new HikerClient(path -> {
			if (path.startsWith("/v2/user/by/username")) {
				return BRAND_PROFILE_JSON;
			}
			if (path.startsWith("/v2/user/tag/medias")) {
				return tagPages.get(0);
			}
			if (path.startsWith("/v2/user/by/id")) {
				maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				try {
					trio.await(5, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException("동시 3 미달 — 병렬 실행 안 됨", e);
				} finally {
					inFlight.decrementAndGet();
				}
				String id = path.substring(path.indexOf("?id=") + "?id=".length());
				return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\"}}".formatted(id, id);
			}
			throw new IllegalStateException("예상 밖 콜: " + path);
		});
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			BrandCollectService svc = new BrandCollectService(latched, writer, snapshots,
					comments, tagged, authors, pool, 90, 105, 3, 30);
			svc.enrich(brand, svc.sweepCore(brand));
		} finally {
			pool.shutdown();
		}
		assertThat(maxInFlight.get()).isEqualTo(3);   // 풀 크기까지 도달, 초과 없음
		assertThat(authors.upserted)
				.containsExactlyInAnyOrder("201", "202", "203", "204", "205", "206");
	}
}
