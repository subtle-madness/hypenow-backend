package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
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
 * 브랜드 태그 수집 본체(2026-08-06 스펙 + 2026-08-09 크롤링 정책 v1) — CollectServiceTest
 * 관용구(fake HikerHttp + 스텁 서브클래스, DB 없음)로 티어 기반 열거 깊이(14일 최소 / due
 * 확장 / 백필 365일)·편입 컷·안전 상한·last_crawled_at 갱신·브랜드 프로필 매일 갱신·부재=0·
 * 0 캐리·게시자 stale·댓글 게이트를 검증한다.
 */
class BrandCollectServiceTest {

	private static final long NOW = Instant.now().getEpochSecond();
	private static final long RECENT = NOW - 5L * 86400;             // 매일 티어(0~14일) 안
	private static final long OLD_20D = NOW - 20L * 86400;           // 14일 컷 밖, 추적(180일) 안
	private static final long RETRO_IN_WINDOW = NOW - 60L * 86400;   // 소급 태그(7일 주기 티어)
	private static final long OLD_70D = NOW - 70L * 86400;           // 60일 컷 이전 판정용
	private static final long OLD_95D = NOW - 95L * 86400;           // 구 90일 윈도우 밖·365일 안(백필 편입)
	private static final long OLD_200D = NOW - 200L * 86400;         // 추적 종료 구간(180~365일)
	private static final long OLD_400D = NOW - 400L * 86400;         // 편입 컷(365일) 밖

	private static final String BRAND_PROFILE_JSON = """
			{"user":{"pk":111,"username":"brandx","full_name":"브랜드","profile_pic_url":"https://p",
			"biography":"소개","follower_count":1234,"following_count":10,"media_count":5,
			"is_private":false}}""";

	private final RecordingWriter writer = new RecordingWriter();
	private final BrandCallContext callContext = new BrandCallContext();
	private final RecordingCallCounts callCounts = new RecordingCallCounts();
	private final StubSnapshots snapshots = new StubSnapshots();
	private final StubComments comments = new StubComments();
	private final InMemoryTagged tagged = new InMemoryTagged();
	private final InMemoryAuthors authors = new InMemoryAuthors();

	private final List<String> calls = new ArrayList<>();
	private final List<String> tagPages = new ArrayList<>();
	private final Set<String> failingAuthorIds = new HashSet<>();
	private boolean tagNotFound = false;
	private boolean tagPage2Fails = false;
	private boolean brandProfileFails = false;
	private boolean commentPage2Fails = false;
	private int tagCall = 0;

	private final BrandRow brand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null);
	// 완주 이력 있는 브랜드 — 티어 경로(백필 아님). 어제 완주로 두어 오늘 스윕 시나리오를 만든다.
	private final BrandRow sweptBrand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE,
			LocalDate.now().minusDays(1));

	// ── 스텁 대역(CollectServiceTest NoopCommentRepository 관용구) ────────────

	private static final class RecordingCallCounts extends BrandCallCountRepository {
		// 병렬 enrich 워커가 동시에 add하므로 스레드 안전하게 누적한다.
		final Map<Long, Long> byBrand = Collections.synchronizedMap(new HashMap<>());

		RecordingCallCounts() {
			super(null);
		}

		@Override
		public void add(long brandId, LocalDate calledOn, long delta) {
			byBrand.merge(brandId, delta, Long::sum);
		}
	}

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
		final List<TaggedPostRepository.TrackedPost> tracked = new ArrayList<>();
		final Map<String, Instant> touched = new HashMap<>();
		int depthCalls = 0;

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

		@Override
		public List<TaggedPostRepository.TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
			return tracked.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
		}

		@Override
		public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
			for (String c : codes) {
				touched.put(c, at);
			}
		}

		@Override
		public void touchCrawledDepth(long brandId, Instant minTakenAt, Instant at) {
			// 실 DB의 범위 UPDATE 대역 — 추적 링크 중 컷 이후(taken_at ≥ minTakenAt) 전부를 touch.
			depthCalls++;
			for (TaggedPostRepository.TrackedPost t : tracked) {
				if (!t.takenAt().isBefore(minTakenAt)) {
					touched.put(t.shortCode(), at);
				}
			}
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
		// 운영 조립(HikerConfig)과 동형으로 콜 집계 데코레이터를 끼운다 — 스코프 전파까지 함께 검증.
		return new HikerClient(new CountingHikerHttp(path -> {
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
				if (tagPage2Fails && tagCall >= 1) {
					throw new HikerFetchException("열거 2페이지 500");
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
				if (commentPage2Fails) {
					if (path.contains("page_id=")) {
						throw new HikerFetchException("Hiker HTTP 500: 순간 과부하");
					}
					return """
							{"response":{"comments":[{"pk":"nc1","text":"새 댓글","comment_like_count":1,
							"created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}]},
							"next_page_id":"cmt-cursor-2"}""";
				}
				return """
						{"response":{"comments":[{"pk":"nc1","text":"새 댓글","comment_like_count":1,
						"created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}]},
						"next_page_id":null}""";
			}
			throw new IllegalStateException("예상 밖 콜: " + path);
		}, callContext, callCounts, new TargetCallContext(), new TargetCallCountRepository(null)));
	}

	private BrandCollectService service(int maxPostsPerSweep) {
		return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
				Runnable::run, 365, 30, maxPostsPerSweep, 3, 30);
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
	void 안전_상한_도달_시_열거를_중단한다() {   // 구 "스윕은_목표_개수까지_커서를_추종한다" 개칭
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(3).sweep(brand);   // 상한 3 — 2페이지째에서 4개 도달, 3페이지는 부르지 않는다

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C", "D");
	}

	@Test
	void 스윕은_페이지_전체가_컷_이전이면_중단한다() {
		// due 없는 티어 경로 — 컷은 최소 깊이 14일. 2페이지 전체가 컷 이전이라 중단하되,
		// 이미 실려 온 20일령 게시물은 365일 편입 컷 안이므로 적재는 된다(공짜 데이터 — 스펙 §4).
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, ""), reel("Old2", OLD_20D, 0, 103, "")));
		tagPages.add(page(null, reel("NeverFetched", RECENT, 0, 104, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old1", "Old2");
	}

	@Test
	void 페이지_중간_기지_뒤의_소급_태그_신규를_놓치지_않는다() {
		tagged.known.add("KnownA");
		// 소급 태그: 기지 게시물보다 뒤 순번에 실림 — "기지 만나면 중단"이면 놓친다(스펙 §6).
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("RetroB", RETRO_IN_WINDOW, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(tagged.inserted).containsExactly("RetroB");
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactlyInAnyOrder("KnownA", "RetroB");
	}

	@Test
	void 기지_게시물_스냅샷을_갱신하고_신규만_링크한다() {
		tagged.known.add("KnownA");
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("NewB", RECENT, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactlyInAnyOrder("KnownA", "NewB");
		assertThat(tagged.inserted).containsExactly("NewB");
	}

	// ── 콜 집계(어드민 크롤링 비용 — 2026-08-12 설계) ────────────────────────

	@Test
	void 스윕의_성공_콜_전부가_브랜드에_계상된다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		service(2000).sweep(brand);

		// 프로필 1 + 태그 열거 1 + 게시자 1(워커 스레드) + 댓글 1(워커 스레드) — 성공 콜 수와 1:1.
		// 워커 콜까지 잡히는 것이 곧 runScoped 재전파 검증이다(ThreadLocal은 풀을 못 넘는다).
		assertThat(calls).hasSize(4);
		assertThat(callCounts.byBrand).containsExactly(Map.entry(1L, 4L));
	}

	@Test
	void 실패_콜은_계상되지_않는다() {
		tagNotFound = true;

		service(2000).sweep(brand);

		// 프로필 1콜만 성공 — 태그 404는 예외로 빠져나가 집계되지 않는다(성공 콜만 과금 정합).
		assertThat(callCounts.byBrand).containsExactly(Map.entry(1L, 1L));
	}

	@Test
	void 윈도우_밖_게시물은_적재하지_않는다() {
		// 편입 컷은 365일(정책 §2 최대 12개월) — 그 밖과 taken_at 미상만 제외된다.
		tagPages.add(page(null, reel("Old400", OLD_400D, 0, 101, ""), reel("NoTakenAt", null, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(writer.saved).isEmpty();
		assertThat(tagged.inserted).isEmpty();
	}

	@Test
	void 태그_0건_계정도_프로필_갱신은_한다() {
		tagNotFound = true;

		service(2000).sweep(brand);

		assertThat(writer.profileSaves).containsExactly("brandx:1234");   // 매일 프로필 갱신(08-06 개정)
		assertThat(writer.saved).isEmpty();
		assertThat(tagged.inserted).isEmpty();
		assertThat(authorCalls()).isZero();
		assertThat(commentCalls()).isZero();
	}

	// ── 티어 깊이 결정(정책 v1 — 2026-08-09 스펙 §4) ─────────────────────────

	@Test
	void due_없으면_최소_14일_깊이만_연다() {
		// 20일령 링크가 있지만 어제 크롤됨(3일 주기 미경과) — 컷은 14일 유지
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Fresh20d",
				Instant.ofEpochSecond(OLD_20D), Instant.ofEpochSecond(NOW - 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, "")));
		tagPages.add(page(null, reel("NeverFetched", RECENT, 0, 103, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(2);   // 2페이지 전체가 14일 컷 이전 — 중단
	}

	@Test
	void due_게시물의_taken_at까지_깊이를_늘린다() {
		// 60일령, 마지막 크롤 10일 전(≥ 7일 주기) — due. 컷이 60일로 내려간다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Due60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, "")));    // 컷(60일) 이후 — 계속
		tagPages.add(page(null, reel("Deep", OLD_70D, 0, 103, "")));    // 전체가 컷 이전 — 중단

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(3);   // due 없던 위 테스트(2콜)와 대조 — 깊이가 늘었다
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old1", "Deep");
	}

	@Test
	void 백필은_365일_전체를_연다() {
		// last_swept_on null(등록 직후·백필 실패 백스톱·재가입) — due 판정 없이 등록 윈도우 전체.
		// 1페이지를 전부 95일령으로 채워 커서를 살려 둔다: 티어 경로(14일 컷)였다면 "페이지 전체가
		// 컷 이전"으로 1콜에서 끊길 배치다 → 2콜이면 컷이 365일로 열렸다는 판별이 된다.
		tagPages.add(page("p2", reel("Old95a", OLD_95D, 0, 101, ""), reel("Old95b", OLD_95D, 0, 102, "")));
		tagPages.add(page(null, reel("Old95c", OLD_95D, 0, 103, "")));

		service(2000).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);
		// 95일령 전부 편입(구 90일 윈도우 밖) — 2페이지까지 갔다는 증거이기도 하다.
		assertThat(tagged.inserted).containsExactlyInAnyOrder("Old95a", "Old95b", "Old95c");
	}

	@Test
	void 티어_경로는_같은_배치를_1페이지에서_끊는다() {
		// 위 백필 테스트의 대조군 — 같은 페이지 배치를 완주 이력 있는 브랜드(14일 컷)로 돌린다.
		tagPages.add(page("p2", reel("Old95a", OLD_95D, 0, 101, ""), reel("Old95b", OLD_95D, 0, 102, "")));
		tagPages.add(page(null, reel("Old95c", OLD_95D, 0, 103, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(1);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("Old95a", "Old95b");
	}

	@Test
	void 늦은_발견은_나이에_맞게_편입한다() {
		// 180~365일: 링크+스냅샷 1회(이후 due 판정이 영구 제외 — BrandCrawlPolicyTest가 고정).
		// 365일 초과: 무시(정책 §2 최대 12개월).
		tagPages.add(page(null, reel("A", RECENT, 0, 101, ""),
				reel("Retro200", OLD_200D, 0, 102, ""), reel("Ancient400", OLD_400D, 0, 103, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Retro200");
		assertThat(writer.saved).extracting(PostInfo::shortCode)
				.containsExactlyInAnyOrder("A", "Retro200");
	}

	@Test
	void 만난_게시물은_last_crawled_at을_갱신한다() {
		tagged.known.add("KnownA");
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("NewB", RECENT, 0, 102, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagged.touched).containsKeys("KnownA", "NewB");
	}

	@Test
	void 자연_종료면_열거에서_사라진_추적_게시물도_커버로_갱신한다() {
		// 삭제·태그 제거·비공개 전환으로 열거에 더 안 실리는 due 링크 — "만난 게시물"만 touch하면
		// 영영 갱신되지 않아 due가 영구 true로 굳고, 매 스윕이 그 taken_at까지 깊이를 다시 연다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Gone60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));   // 커서 소진 — 자연 종료

		service(2000).sweep(sweptBrand);

		assertThat(tagged.depthCalls).isEqualTo(1);
		assertThat(tagged.touched).containsKeys("A", "Gone60d");
	}

	@Test
	void 안전_상한_중단은_깊이를_갱신하지_않는다() {
		// 훑다 만 스윕은 깊이를 커버하지 못했다 — 여기서 touch하면 자가 치유가 깨진다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Gone5d",
				Instant.ofEpochSecond(RECENT), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(3).sweep(sweptBrand);

		assertThat(tagged.depthCalls).isZero();
		assertThat(tagged.touched).containsKeys("A", "B", "C", "D");   // 만난 게시물은 그대로 touch
		assertThat(tagged.touched).doesNotContainKey("Gone5d");
	}

	// ── 브랜드 프로필 매일 갱신 ──────────────────────────────────────────────

	@Test
	void 브랜드_프로필_갱신_실패는_열거를_막지_않는다() {
		brandProfileFails = true;
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(2000).sweep(brand);   // 예외가 새면 여기서 터진다

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

		service(2000).sweep(brand);

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

		service(2000).sweep(brand);

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

		service(2000).sweep(brand);

		assertThat(authorCalls()).isEqualTo(2);              // 102·103만
		assertThat(authors.upserted).containsExactly("103"); // 102 실패는 격리 — 103은 계속
		assertThat(writer.saved).hasSize(3);                 // 게시자 실패가 지표 적재에 번지지 않는다
	}

	// ── 스트리밍 적재 + 서빙 콜백(2026-08-12 스펙 §2) ────────────────────────

	@Test
	void 서빙_창_커버_시점에_콜백을_1회_호출하고_열거는_계속한다() {
		// 백필 경로(365일 컷). 2페이지 전체가 60일령 > 서빙 창(30일) — 여기서 콜백이 떠야 한다.
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old60a", RETRO_IN_WINDOW, 0, 102, ""),
				reel("Old60b", RETRO_IN_WINDOW, 0, 103, "")));
		tagPages.add(page(null, reel("Old95", OLD_95D, 0, 104, "")));
		List<List<String>> callbacks = new ArrayList<>();

		service(2000).sweepCore(brand,
				early -> callbacks.add(early.stream().map(PostInfo::shortCode).toList()));

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks.getFirst()).containsExactly("A", "Old60a", "Old60b");   // 경계 페이지까지 누적분
		assertThat(tagCalls()).isEqualTo(3);   // 콜백 후에도 365일 컷까지 계속 — 조기 종료 아님
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old60a", "Old60b", "Old95");
	}

	@Test
	void 서빙_창보다_게시물이_얕으면_열거_종료_시점에_콜백한다() {
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));   // 전부 최근 — 경계 미도달
		List<List<String>> callbacks = new ArrayList<>();

		service(2000).sweepCore(brand,
				early -> callbacks.add(early.stream().map(PostInfo::shortCode).toList()));

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks.getFirst()).containsExactly("A");
	}

	@Test
	void 안전_상한_중단도_종료_시점에_콜백한다() {
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));
		List<Integer> sizes = new ArrayList<>();

		service(3).sweepCore(brand, early -> sizes.add(early.size()));

		assertThat(sizes).containsExactly(4);   // 상한 3 → 2페이지째 중단, 그때까지 적재분 4건
	}

	@Test
	void 열거_중간_실패에도_앞_페이지_적재는_보존된다() {
		// 스트리밍의 핵심 — 구 일괄 processCore였다면 전량 유실됐을 배치다.
		tagPage2Fails = true;
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));

		assertThatThrownBy(() -> service(2000).sweepCore(brand))
				.isInstanceOf(HikerFetchException.class);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
		assertThat(tagged.inserted).containsExactly("A");
	}

	@Test
	void 페이지_간_중복_코드는_한_번만_처리한다() {
		// 커서 드리프트로 같은 게시물이 두 페이지에 실려도 적재·링크는 1회(구 putIfAbsent 의미 보존).
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page(null, reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A", "B");
		assertThat(tagged.inserted).containsExactly("A", "B");
	}

	// ── core/enrichment 분리(등록 백필 단계식 ready — 2026-08-07) ────────────

	@Test
	void sweepCore는_게시자_댓글_콜_없이_적재까지만_한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		List<PostInfo> posts = service(2000).sweepCore(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
		assertThat(tagged.inserted).containsExactly("A");
		assertThat(authorCalls()).isZero();     // 보강 콜은 core 밖 — ready 게이트가 여기서 끊긴다
		assertThat(commentCalls()).isZero();
		assertThat(posts).extracting(PostInfo::shortCode).containsExactly("A");
	}

	@Test
	void enrich는_core가_넘긴_게시물의_게시자와_댓글만_수집한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));
		BrandCollectService service = service(2000);
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

		service(2000).sweep(brand);

		assertThat(commentCalls()).isEqualTo(2);
		assertThat(comments.upserted).containsExactlyInAnyOrder("Grown", "FreshD");
		assertThat(tagged.collectedCounts)
				.containsEntry("Grown", 7L)
				.containsEntry("FreshD", 2L)
				.containsEntry("SameCnt", 5L);
	}

	/**
	 * 댓글 부분 보존(08-10) — 중간 페이지 실패 시 받은 페이지분은 upsert하되 워터마크는 올리지
	 * 않는다. 워터마크가 그대로면 다음 스윕 게이트(comment_count > 저장값)가 다시 열리고,
	 * 기지 댓글 페이지 중단이 재수집 비용을 막는다. (운영 실측 08-10: 브랜드 17 첫 백필에서
	 * 중간 실패 24게시물이 받은 댓글까지 전량 폐기 — 이 테스트가 그 재발 방지다.)
	 */
	@Test
	void 댓글_중간_페이지_실패는_받은_만큼_저장하고_워터마크는_유지한다() {
		commentPage2Fails = true;
		tagPages.add(page(null, reel("Partial", RECENT, 30, 101, "")));

		service(2000).sweep(brand);

		assertThat(comments.upserted).containsExactly("Partial");        // 1페이지분은 저장
		assertThat(tagged.collectedCounts.get("Partial")).isNull();      // 워터마크 유지 → 다음 스윕 재시도
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
			BrandCollectService svc = new BrandCollectService(latched, callContext, writer, snapshots,
					comments, tagged, authors, pool, 365, 30, 2000, 3, 30);
			svc.enrich(brand, svc.sweepCore(brand));
		} finally {
			pool.shutdown();
		}
		assertThat(maxInFlight.get()).isEqualTo(3);   // 풀 크기까지 도달, 초과 없음
		assertThat(authors.upserted)
				.containsExactlyInAnyOrder("201", "202", "203", "204", "205", "206");
	}
}
