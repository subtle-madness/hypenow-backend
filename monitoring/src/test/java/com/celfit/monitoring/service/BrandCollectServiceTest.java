package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.ad.AdDisclosureJudgeService;
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
import com.celfit.monitoring.store.BrandRepository;
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
 * 확장 / 백필은 브랜드별 수집 창 collection_months)·편입 컷·안전 상한·last_crawled_at 갱신·브랜드 프로필 매일 갱신·부재=0·
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
	private final RecordingBrands brands = new RecordingBrands();

	private final List<String> calls = new ArrayList<>();
	private final List<String> tagPages = new ArrayList<>();
	private final Set<String> failingAuthorIds = new HashSet<>();
	// 부재 검증 단건 콜(fetchPost) 대역 — code별 응답 JSON / 404 집합(BrandDirectCollectServiceTest 관용구)
	private final Map<String, String> postResponses = new HashMap<>();
	private final Set<String> notFoundCodes = new HashSet<>();
	// 게시자별 "앞으로 몇 번 더 404를 낼지" — 산발적 404(1회) / 지속 404를 같은 대역으로 표현한다.
	private final Map<String, Integer> authorNotFoundTimes = new HashMap<>();
	private boolean tagNotFound = false;
	private boolean tagPage2Fails = false;
	private boolean brandProfileFails = false;
	private boolean commentPage2Fails = false;
	private int tagCall = 0;

	private final BrandRow brand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 12, true);
	// 완주 이력 있는 브랜드 — 티어 경로(백필 아님). 어제 완주로 두어 오늘 스윕 시나리오를 만든다.
	private final BrandRow sweptBrand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE,
			LocalDate.now().minusDays(1), 12, true);

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

	/**
	 * 창 커버리지 대역(2026-08-19 수집 상한 v2 스펙 §7-1·§7-4) — 읽기(일일 스윕의 컷 클램프 입력)와
	 * 쓰기(백필 종료부의 기록)를 한 스텁에서 관찰한다. 기본값은 미컷 단면이라 기존 테스트는 무영향.
	 */
	private static final class RecordingBrands extends BrandRepository {
		BrandRepository.Coverage coverage = new BrandRepository.Coverage(false, null);
		final List<String> coverageWrites = new ArrayList<>();
		/** 진행 워터마크 touch 관찰(08-31 백필 캐시 고착 수리) — enrich 워커에서 나가므로 동기화. */
		final List<Long> progressTouches = Collections.synchronizedList(new ArrayList<>());
		/** 정산 마킹과의 호출 순서 검증용 — 기본은 격리된 리스트. */
		private List<String> callOrder = new ArrayList<>();

		RecordingBrands() {
			super(null);
		}

		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		@Override
		public void touchProgress(long brandId) {
			progressTouches.add(brandId);
			callOrder.add("progress");
		}

		@Override
		public BrandRepository.Coverage coverage(long brandId) {
			return coverage;
		}

		@Override
		public void updateCoverage(long brandId, boolean capped, Instant coveredUntil) {
			coverageWrites.add(capped ? brandId + ":capped:" + coveredUntil : brandId + ":full");
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
		/** onVisible 훅과의 호출 순서 검증용(2026-08-18 계정 게이트 단축) — 기본은 격리된 리스트. */
		private List<String> callOrder = new ArrayList<>();

		StubComments() {
			super(null);
		}

		/** 다른 스텁·테스트 로컬 훅과 같은 리스트를 공유시켜 인터리빙을 관찰한다. */
		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
		}

		@Override
		public Set<String> findIds(String shortCode) {
			return Set.of();
		}

		@Override
		public void upsertForPost(String shortCode, List<CommentInfo> fetched) {
			upserted.add(shortCode);
			callOrder.add("comments:" + shortCode);
		}
	}

	private static final class InMemoryTagged extends TaggedPostRepository {
		final Set<String> known = new LinkedHashSet<>();
		final List<String> inserted = new ArrayList<>();
		final Map<String, Long> collectedCounts = new HashMap<>();
		final List<TaggedPostRepository.TrackedPost> tracked = new ArrayList<>();
		// direct 등록(direct_registered_at IS NOT NULL) 표식 — TrackedPost 레코드에는 direct 여부가
		// 없다(저장소가 두 SQL의 WHERE로만 가른다). 서비스가 보는 필터 의미를 그대로 미러하려면
		// 대역 쪽에 표식이 필요해 여기 둔다: trackedPosts·touchCrawledDepth 둘 다 이 집합을 배제한다.
		final Set<String> directCodes = new HashSet<>();
		final Map<String, Instant> touched = new HashMap<>();
		// 부재 검증(2026-08-25 tagged 삭제 감지) — 실 SQL(tagVerifyCandidates·markUnavailable·
		// markAbsenceChecked)의 상태 미러.
		final List<String> unavailable = new ArrayList<>();
		final Map<String, Instant> absenceChecked = new HashMap<>();
		// 정산 마킹은 enrich 스레드 1개에서 나가지만, 다른 스텁(InMemoryAuthors.upserted)과 같은
		// 이유로 스레드 안전 리스트를 쓴다 — 호출 지점이 워커로 옮겨가도 단언이 깨지지 않게.
		final List<String> enriched = Collections.synchronizedList(new ArrayList<>());
		// 첫 정산 마킹만 실패시킨다 — 보강 단계의 DB 실패(페이지 1건 실패, 뒤 페이지는 정상) 대역.
		boolean markEnrichedFailsOnce = false;
		// 댓글 게이트 첫머리의 배치 조회 실패 대역(커넥션 blip) — 건별 격리가 닿지 않는 지점이다.
		boolean commentsCountsFails = false;
		int depthCalls = 0;
		/** 진행 워터마크 touch와의 호출 순서 검증용(08-31) — 기본은 격리된 리스트. */
		private List<String> callOrder = new ArrayList<>();

		InMemoryTagged() {
			super(null);
		}

		void useSharedCallOrder(List<String> shared) {
			this.callOrder = shared;
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
			if (commentsCountsFails) {
				throw new IllegalStateException("댓글 워터마크 배치 조회 실패(DB 일시 오류)");
			}
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
			// 실 SQL의 AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL 대역.
			return tracked.stream()
					.filter(t -> !t.takenAt().isBefore(minTakenAt))
					.filter(t -> !directCodes.contains(t.shortCode()))
					.toList();
		}

		@Override
		public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
			for (String c : codes) {
				touched.put(c, at);
				// 실 SQL과 동형 — 재관측은 부재 마킹·검증 스로틀을 해제한다.
				unavailable.remove(c);
				absenceChecked.remove(c);
			}
		}

		@Override
		public List<String> tagVerifyCandidates(long brandId, Instant minTakenAt, Instant recheckBefore) {
			// 실 SQL의 tagged-only(direct 제외)·검증 하한·unavailable/스로틀 제외 대역.
			return tracked.stream()
					.filter(t -> !t.takenAt().isBefore(minTakenAt))
					.filter(t -> !directCodes.contains(t.shortCode()))
					.filter(t -> !unavailable.contains(t.shortCode()))
					.filter(t -> {
						Instant checked = absenceChecked.get(t.shortCode());
						return checked == null || checked.isBefore(recheckBefore);
					})
					.map(TaggedPostRepository.TrackedPost::shortCode)
					.toList();
		}

		@Override
		public void markUnavailable(long brandId, String shortCode, Instant at) {
			unavailable.add(shortCode);
		}

		@Override
		public void markAbsenceChecked(long brandId, String shortCode, Instant at) {
			absenceChecked.put(shortCode, at);
		}

		@Override
		public void markEnriched(long brandId, Collection<String> codes, Instant at) {
			if (markEnrichedFailsOnce) {
				markEnrichedFailsOnce = false;
				throw new IllegalStateException("정산 마킹 실패(DB 일시 오류)");
			}
			enriched.addAll(codes);
			callOrder.add("enriched");
		}

		@Override
		public void touchCrawledDepth(long brandId, Instant minTakenAt, Instant at) {
			// 실 DB의 범위 UPDATE 대역 — 추적 링크 중 컷 이후(taken_at ≥ minTakenAt) 전부를 touch.
			// direct 행은 제외한다(수집 상한 v2 §7-3) — 커버 간주 touch의 동결 면제.
			depthCalls++;
			for (TaggedPostRepository.TrackedPost t : tracked) {
				if (!t.takenAt().isBefore(minTakenAt) && !directCodes.contains(t.shortCode())) {
					touched.put(t.shortCode(), at);
				}
			}
		}
	}

	private static final class InMemoryAuthors extends AuthorProfileRepository {
		Set<String> fresh = new HashSet<>();
		// stale 판정 배치 조회 실패 대역(커넥션 blip) — 게시자별 격리(fetchAuthorWithRetry)보다
		// 앞이라 여기서 던지면 예외가 enrich 밖으로 나간다.
		boolean freshLookupFails = false;
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
			if (freshLookupFails) {
				throw new IllegalStateException("게시자 stale 배치 조회 실패(DB 일시 오류)");
			}
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
			if (path.startsWith("/v2/media/info/by/code")) {
				String code = path.substring(path.indexOf("?code=") + "?code=".length());
				if (notFoundCodes.contains(code)) {
					throw new SubjectNotFoundException("게시물 없음: " + code);
				}
				String body = postResponses.get(code);
				if (body == null) {
					throw new IllegalStateException("예상 밖 코드: " + code);
				}
				return body;
			}
			if (path.startsWith("/v2/user/by/id")) {
				// 쿼리 파라미터명은 id(08-07 실측 교정 — HikerClient.fetchAuthorProfile 주석 참조)
				String id = path.substring(path.indexOf("?id=") + "?id=".length());
				if (failingAuthorIds.contains(id)) {
					throw new HikerFetchException("게시자 프로필 500");
				}
				int notFoundLeft = authorNotFoundTimes.getOrDefault(id, 0);
				if (notFoundLeft > 0) {
					authorNotFoundTimes.put(id, notFoundLeft - 1);
					throw new SubjectNotFoundException("Hiker 404");
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
		return service(maxPostsPerSweep, 10000);   // 수집 상한 불활성 — 기존 테스트 의미 보존
	}

	private BrandCollectService service(int maxPostsPerSweep, int collectionPostLimit) {
		return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
				brands, new FakeAdJudge(), Runnable::run, maxPostsPerSweep, collectionPostLimit, 3, 30, true);
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

	/** 부재 검증 단건 콜(fetchPost) 응답 — BrandDirectCollectServiceTest.postJson과 동형. */
	private static String postJson(String code, long takenAt, long authorPk) {
		return """
				{"media_or_ad":{"code":"%s","taken_at":%d,"product_type":"clips","like_count":10,
				"comment_count":2,"ig_play_count":500,
				"user":{"pk":%d,"username":"author_%d","full_name":"작가","profile_pic_url":"https://p"}}}"""
				.formatted(code, takenAt, authorPk, authorPk);
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
	void 백필_컷은_브랜드의_collection_months를_따른다() {
		// 3개월 창 브랜드 — 95일령(minusMonths(3)=89~92일보다 항상 과거)만 실린 1페이지는
		// "페이지 전체가 컷 이전"으로 1콜 종료, 편입 컷 밖이라 적재도 0건.
		// 12개월 창 대조군(백필은_365일_전체를_연다)은 같은 배치를 2콜 끝까지 연다.
		BrandRow narrow = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 3, true);
		tagPages.add(page("p2", reel("Old95a", OLD_95D, 0, 101, ""), reel("Old95b", OLD_95D, 0, 102, "")));
		tagPages.add(page(null, reel("Old95c", OLD_95D, 0, 103, "")));

		service(2000).sweep(narrow);

		assertThat(tagCalls()).isEqualTo(1);
		assertThat(tagged.inserted).isEmpty();
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
	void 커버_스윕은_열거에서_사라진_tagged_게시물을_검증_콜로_확정한다() {
		// due 확장으로 컷이 60일까지 열리는데 열거에는 안 실린 게시물 셋 — 삭제(404)·태그 해제(생존)·
		// direct 겹침(2단계가 잡으므로 검증 제외).
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("GoneReel",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Untagged",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("DirectGone",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.directCodes.add("DirectGone");
		notFoundCodes.add("GoneReel");
		postResponses.put("Untagged", postJson("Untagged", RETRO_IN_WINDOW, 101));
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));   // 커서 소진 — 자연 종료(커버)

		service(2000).sweep(sweptBrand);

		// 404 확정 — unavailable 마킹만(스로틀 마킹 없음: unavailable 자체가 후보 제외 조건)
		assertThat(tagged.unavailable).containsExactly("GoneReel");
		// 생존(태그 해제) — 스로틀만 찍고 상태 불변
		assertThat(tagged.absenceChecked).containsKey("Untagged");
		assertThat(tagged.unavailable).doesNotContain("Untagged");
		// direct 겹침 행은 검증 콜 자체가 없다(2단계 단건 수집 소관 — 중복 과금 방지)
		assertThat(calls.stream().filter(p -> p.contains("code=DirectGone")).count()).isZero();
		// 열거에 실린 게시물은 검증 대상이 아니다
		assertThat(calls.stream().filter(p -> p.contains("code=A")).count()).isZero();
	}

	@Test
	void 미커버_스윕은_부재_검증을_하지_않는다() {
		// 안전 상한(④)으로 끊긴 실행 — 커버하지 못한 열거의 부재는 증거가 아니다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Gone5d",
				Instant.ofEpochSecond(RECENT), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(3).sweep(sweptBrand);

		assertThat(tagged.unavailable).isEmpty();
		assertThat(tagged.absenceChecked).isEmpty();
		assertThat(calls.stream().filter(p -> p.startsWith("/v2/media/info/by/code")).count()).isZero();
	}

	@Test
	void 최근_검증한_생존_게시물은_재검증_콜을_내지_않는다() {
		// 태그 해제로 살아있는 게시물 — 7일 스로틀 안이면 매일 콜을 내지 않는다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Silent",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.absenceChecked.put("Silent", Instant.now().minusSeconds(86400));   // 어제 검증됨
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(2000).sweep(sweptBrand);

		assertThat(calls.stream().filter(p -> p.startsWith("/v2/media/info/by/code")).count()).isZero();
	}

	@Test
	void 부재_검증은_스윕당_상한까지만_콜을_낸다() {
		for (int i = 0; i < 35; i++) {
			String code = "Vanished" + i;
			tagged.tracked.add(new TaggedPostRepository.TrackedPost(code,
					Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
			postResponses.put(code, postJson(code, RETRO_IN_WINDOW, 101));
		}
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(2000).sweep(sweptBrand);

		assertThat(calls.stream().filter(p -> p.startsWith("/v2/media/info/by/code")).count()).isEqualTo(30);
		assertThat(tagged.absenceChecked).hasSize(30);   // 잔여 5건은 다음 스윕이 이어받는다
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

	@Test
	void 수집_상한_도달은_자연_종료로_목표_깊이_전체를_커버한다() {
		// 수집 개수 상한(2026-08-19 스펙 §3) — 안전 밸브와 달리 의도된 종료라 커버 처리한다.
		// 열거에서 못 만난 컷 밖 깊은 due 링크까지 touch해야 한다 — 안 하면 due가 영구 잔존해
		// 매 스윕이 같은 깊이를 다시 여는 낭비 루프가 된다(§3-2: 루프 차단 = 지표 동결).
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("DeepDue60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(10000, 3).sweep(sweptBrand);   // 수집 상한 3 — 2페이지째 4건 도달, 3페이지는 안 부른다

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C", "D");
		assertThat(tagged.depthCalls).isEqualTo(1);            // 안전 밸브(depthCalls=0)와의 결정적 차이
		assertThat(tagged.touched).containsKey("DeepDue60d");  // 목표 컷(60일 due)까지 통째로 동결
	}

	@Test
	void 수집_상한_도달의_깊이_커버가_direct_행은_동결하지_않는다() {
		// §7-3 — direct 등록 게시물은 상한 밖이다. 컷 종료의 "목표 컷 전체 touch"가 direct 행까지
		// 찍으면 그 행은 2단계에서도 due가 아니게 돼(last_crawled_at이 실크롤 없이 전진) 사용자가
		// 직접 등록한 게시물이 영원히 마지막 지표로 얼어붙는다. 태그 행만 동결 대상이다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("DeepDue60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("DeepDirect60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagged.directCodes.add("DeepDirect60d");
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(10000, 3).sweep(sweptBrand);   // 수집 상한 3 — 2페이지째 도달, 커버 처리

		assertThat(tagged.depthCalls).isEqualTo(1);
		assertThat(tagged.touched).containsKey("DeepDue60d");             // 태그 행은 동결
		assertThat(tagged.touched).doesNotContainKey("DeepDirect60d");    // direct 행은 상한 밖
	}

	@Test
	void 수집_상한_도달도_백필_페이지_콜백은_전부_방출한다() {
		// 등록 백필도 같은 진입점(스펙 §3-4) — 상한 종료가 완결 배치 서빙 계약(페이지마다 1회
		// 콜백)을 깨지 않아야 markServing·backfill_completed_at 경로가 무변경으로 성립한다.
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));
		List<Integer> sizes = new ArrayList<>();

		service(10000, 3).sweepCore(brand, pageItems -> sizes.add(pageItems.size()));

		assertThat(sizes).containsExactly(2, 2);       // 중단 전 두 페이지 모두 자기 페이지분으로 방출
		assertThat(tagged.depthCalls).isEqualTo(1);    // 백필 목표 컷(수집 창 전체)도 커버 처리
	}

	@Test
	void 마지막_페이지에서_정확히_상한_도달은_자연_종료다() {
		// 상한 판정이 커서 소진 판정 뒤에 있어(기존 안전 밸브와 같은 규칙 — 스펙 §3-1) 마지막
		// 페이지에서 정확히 상한에 닿으면 상한 경로가 아니라 커서 소진 자연 종료로 끝난다.
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page(null, reel("C", RECENT, 0, 103, "")));

		service(10000, 3).sweep(brand);   // 상한 3 = 총 열거 3건 — 정확히 상한에서 커서 소진

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C");
		assertThat(tagged.depthCalls).isEqualTo(1);
	}

	@Test
	void 수집_상한_0은_무제한이다() {
		// 0 이하 = 무제한 — 0이 "1페이지 후 전체 동결"로 오독되면
		// 브랜드 전체가 티어 주기 동안 조용히 얼어붙는 풋건이라 가드한다.
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page(null, reel("C", RECENT, 0, 103, "")));

		service(10000, 0).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);   // 상한이 꺼져 있어 커서 소진까지 완주
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C");
	}

	// ── 창 커버리지 기록·컷 클램프(2026-08-19 수집 상한 v2 — 스펙 §7-1·§7-4) ──

	@Test
	void 백필이_컷으로_끝나면_커버리지를_capped로_기록한다() {
		// 3페이지·상한 3 — 2페이지째 컷. 백필(last_swept_on null) 실행이므로 종료부가 기록해야 한다.
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", OLD_20D, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(10000, 3).sweep(brand);

		assertThat(brands.coverageWrites).containsExactly("1:capped:" + Instant.ofEpochSecond(OLD_20D));
		// 실수집 깊이 = 편입분 최고령 taken_at(OLD_20D) — 목표 컷(365일)이 아니다.
	}

	@Test
	void 컷_종료인데_편입이_0건이면_capped를_내리지_않는다() {
		// 병리 케이스 — taken_at 미상뿐이라 편입 0건인 채로 상한(2)에 닿았다. (true, NULL)은
		// "컷인데 깊이 미상"이라는 FE 자기모순 쌍이고 §7-4 클램프도 값을 못 얻으므로 (false, NULL)로 접는다.
		tagPages.add(page("p2", reel("A", null, 0, 101, ""), reel("B", null, 0, 102, "")));
		tagPages.add(page(null, reel("C", RECENT, 0, 103, "")));

		service(10000, 2).sweep(brand);

		assertThat(brands.coverageWrites).containsExactly("1:full");
	}

	@Test
	void 백필이_완주하면_커버리지를_전체_커버로_기록한다() {
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(10000, 2000).sweep(brand);

		assertThat(brands.coverageWrites).containsExactly("1:full");
	}

	@Test
	void capped_브랜드의_일일_열거는_covered_until보다_깊게_열지_않는다() {
		// 60일령 due 링크가 컷을 60일로 벌리려 하지만, 커버리지가 20일이면 클램프돼 20일 컷.
		brands.coverage = new BrandRepository.Coverage(true, Instant.ofEpochSecond(OLD_20D));
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Due60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old25d", NOW - 25L * 86400, 0, 102, "")));   // 20일 컷 이전 — 중단
		tagPages.add(page(null, reel("Deep", RETRO_IN_WINDOW, 0, 103, "")));

		service(10000, 2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(2);   // 클램프 없었으면 60일 컷이라 3콜
		assertThat(brands.coverageWrites).isEmpty();   // 일일 스윕은 창 커버리지를 건드리지 않는다
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

	/**
	 * 게시자 404는 결정적 부재가 아니다(08-13 실측 2.0%, 재시도 1회로 2/2 복구 — 실존 계정 확인)
	 * — 1회 재시도해 성공하면 프로필을 저장한다. 재시도가 없으면 완결 서빙에서 그 게시물이
	 * 영구 미노출이 된다.
	 */
	@Test
	void 게시자_404는_1회_재시도한다() {
		authorNotFoundTimes.put("101", 1);   // 첫 콜만 404, 두 번째는 정상 — 산발적 404의 재현
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(2000).sweep(brand);

		assertThat(authorCalls()).isEqualTo(2);              // 최초 1 + 재시도 1
		assertThat(authors.upserted).containsExactly("101"); // 재시도로 복구
	}

	/**
	 * 재시도해도 실패하면 게시자 없이 넘어간다 — 무한 재시도로 화면을 막지 않는다.
	 * 미수집분은 게시자 stale 판정으로 다음 스윕이 백스톱한다.
	 */
	@Test
	void 게시자_404가_재시도_후에도_실패하면_건너뛴다() {
		authorNotFoundTimes.put("101", 5);   // 계속 404 — 재시도 상한을 넘겨 부르지 않는지 본다
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));

		service(2000).sweep(brand);   // 예외가 새어나가면 여기서 터진다

		assertThat(authorCalls()).isEqualTo(2);   // 최초 1 + 재시도 1, 그 이상은 안 한다
		assertThat(authors.upserted).isEmpty();
	}

	// ── 스트리밍 적재 + 페이지 배치 콜백(2026-08-13 완결 배치 서빙 스펙 §2) ───

	/**
	 * 콜백은 페이지마다 1회, <b>그 페이지분만</b> 받는다(누적 아님) — 수신자가 그 페이지를 즉시
	 * 보강·정산해 목록에 올릴 수 있게 하는 전제다. 구 계약은 "서빙 창(30일) 커버 시 1회 + 그때까지의
	 * 누적 리스트"였다.
	 */
	@Test
	void 콜백은_페이지마다_그_페이지분만_받는다() {
		// 백필 경로(365일 컷) 3페이지 — 구 계약이었다면 2페이지 경계(60일령 > 서빙 창 30일)에서
		// 누적 3건으로 1회 부르고 끝났을 배치다.
		tagPages.add(page("p2", reel("P1", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("P2a", RETRO_IN_WINDOW, 0, 102, ""),
				reel("P2b", RETRO_IN_WINDOW, 0, 103, "")));
		tagPages.add(page(null, reel("P3", OLD_95D, 0, 104, "")));
		List<List<String>> batches = new ArrayList<>();

		service(2000).sweepCore(brand,
				pageItems -> batches.add(pageItems.stream().map(PostInfo::shortCode).toList()));

		assertThat(batches).hasSize(3);
		assertThat(batches.get(0)).containsExactly("P1");
		assertThat(batches.get(1)).containsExactly("P2a", "P2b");   // 1페이지분이 섞이지 않는다
		assertThat(batches.get(2)).containsExactly("P3");
		assertThat(tagCalls()).isEqualTo(3);   // 콜백이 열거를 끊지 않는다 — 365일 컷까지 계속
		assertThat(tagged.inserted).containsExactlyInAnyOrder("P1", "P2a", "P2b", "P3");
	}

	/** 태그 0건 브랜드도 콜백을 1회 받는다 — 안 부르면 그 브랜드가 collecting에 영구히 갇힌다. */
	@Test
	void 태그가_0건이면_빈_페이지로_콜백을_1회_부른다() {
		tagNotFound = true;   // 404 → 빈 페이지(HikerClient 변환) — 처리할 페이지가 없는 경로
		List<Integer> sizes = new ArrayList<>();

		service(2000).sweepCore(brand, pageItems -> sizes.add(pageItems.size()));

		assertThat(sizes).containsExactly(0);
	}

	@Test
	void 단일_페이지_열거는_그_페이지분으로_콜백한다() {
		tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));
		List<List<String>> callbacks = new ArrayList<>();

		service(2000).sweepCore(brand,
				pageItems -> callbacks.add(pageItems.stream().map(PostInfo::shortCode).toList()));

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks.getFirst()).containsExactly("A");
	}

	@Test
	void 안전_상한_중단_전까지의_페이지는_전부_콜백된다() {
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));
		List<Integer> sizes = new ArrayList<>();

		service(3).sweepCore(brand, pageItems -> sizes.add(pageItems.size()));

		// 상한 3 → 2페이지째 처리 후 중단. 두 페이지 모두 자기 페이지분(2건씩)으로 방출된다.
		assertThat(sizes).containsExactly(2, 2);
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
	void 스윕_중간_실패해도_앞_페이지는_정산된다() {
		// 일일 스윕도 페이지 배치로 돈다(2026-08-13 완결 배치 서빙 스펙 §3) — 열거 전량 후 일괄
		// 보강을 유지하면 중간 실패 시 그 스윕에서 만난 게시물이 통째로 미정산(= 목록 미노출)으로
		// 남는다. 1페이지분은 이미 보강·정산이 끝나 있어야 한다.
		tagPage2Fails = true;
		tagPages.add(page("p2", reel("P1_A", RECENT, 0, 101, ""), reel("P1_B", RECENT, 0, 102, "")));

		assertThatThrownBy(() -> service(2000).sweep(brand))
				.isInstanceOf(HikerFetchException.class);

		assertThat(tagged.enriched).containsExactlyInAnyOrder("P1_A", "P1_B");
	}

	@Test
	void 스윕은_보강이_실패해도_남은_페이지_열거를_계속한다() {
		// 콜백(보강)이 던지면 열거 루프가 통째로 끊겨 뒤 페이지가 그날 적재조차 안 된다 — 등록
		// 경로(runEnrichSafely)와 같은 격리 규칙을 스윕에도 건다. 열거는 페이지당 Hiker 콜을 이미
		// 지불한 결과물이라 보강 실패로 버리면 교환비가 나쁘다.
		tagged.markEnrichedFailsOnce = true;
		tagPages.add(page("p2", reel("P1", RECENT, 0, 101, "")));
		tagPages.add(page(null, reel("P2", RECENT, 0, 102, "")));

		service(2000).sweep(brand);   // 보강 예외가 새면 여기서 터진다

		assertThat(tagged.inserted).containsExactly("P1", "P2");   // 2페이지도 적재됐다
		assertThat(tagged.enriched).containsExactly("P2");          // 1페이지분만 미정산 — 다음 스윕이 백스톱
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

	// ── core/enrichment 분리(등록 백필 2단계 — 08-13 개정: ready는 첫 페이지 배치의 정산) ──

	@Test
	void sweepCore는_게시자_댓글_콜_없이_적재까지만_한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		List<PostInfo> posts = service(2000).sweepCore(brand);

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
		assertThat(tagged.inserted).containsExactly("A");
		assertThat(authorCalls()).isZero();     // 보강 콜은 core 밖 — 정산(= 노출)은 enrich에서만 열린다
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

	// ── 보강 정산 마킹(2026-08-13 완결 배치 서빙 스펙 §1) ────────────────────

	/**
	 * 보강이 끝나면 그 게시물들을 정산 마킹한다 — was 목록 게이트(enriched_at IS NOT NULL)의
	 * 입력이라, 안 찍히면 게시물이 목록에 안 뜬다.
	 */
	@Test
	void 보강이_끝나면_정산_마킹한다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, ""), reel("B", RECENT, 3, 102, "")));

		service(2000).sweep(brand);

		assertThat(tagged.enriched).containsExactlyInAnyOrder("A", "B");
	}

	/**
	 * 게시자 수집이 전부 실패해도 정산한다 — enriched_at의 의미는 "보강 시도가 끝났다"이지
	 * "게시자가 찼다"가 아니다. "게시자가 있을 때만 정산"으로 구현하면 실측 404 2%·타임아웃 1%의
	 * 게시물이 목록에서 영구히 사라진다(미수집분은 stale 판정으로 다음 스윕이 백스톱).
	 */
	@Test
	void 게시자_수집이_실패해도_정산한다() {
		authorNotFoundTimes.put("101", 5);   // 지속 404 — 재시도까지 소진해도 실패
		failingAuthorIds.add("102");         // 5xx·타임아웃 대역
		tagPages.add(page(null, reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(authors.upserted).isEmpty();   // 게시자는 한 명도 못 채웠다
		assertThat(tagged.enriched).containsExactlyInAnyOrder("A", "B");
	}

	/**
	 * 댓글이 미완주(중간 페이지 실패)여도 정산한다 — 단, 워터마크는 전진시키지 않아 다음 스윕이
	 * 못 받은 페이지를 재시도한다(08-10 부분 보존 규칙 불변, 정산만 얹힌다).
	 */
	@Test
	void 댓글_미완주여도_정산하되_워터마크는_전진하지_않는다() {
		commentPage2Fails = true;
		tagPages.add(page(null, reel("Partial", RECENT, 30, 101, "")));

		service(2000).sweep(brand);

		assertThat(tagged.enriched).containsExactly("Partial");
		assertThat(tagged.collectedCounts).doesNotContainKey("Partial");   // 워터마크 미전진
	}

	/**
	 * 보강 단계 <b>첫머리의 배치 DB 조회</b>가 던져도 정산한다 — 건별 Hiker 콜은 각자 격리되지만
	 * freshIgUserIds는 그 격리 밖이라, 커넥션 blip 하나로 예외가 ensureAuthors 밖으로 새면 그
	 * 페이지 행이 enriched_at NULL로 굳는다. 180일 초과 게시물에는 재열거 백스톱이 없어
	 * (BrandCrawlPolicy.due가 무조건 false) 그 미노출이 <b>영구</b>가 된다 — 그래서 마킹은 finally다.
	 * 예외 자체는 그대로 위로 전파돼 상위 격리(enrichSafely)가 로그로 남긴다.
	 *
	 * <p>2026-08-18 수정: 댓글 수집·광고 판정은 이 하드 실패와 무관하게 여전히 돈다(이중
	 * try/finally — {@link BrandCollectService#enrich} javadoc 참조). 그래서 게시자 콜만 0건이고
	 * 댓글 콜은 정상적으로 나간다 — 예전엔(안쪽 finally 하나뿐) 댓글도 도달 불가라 0건이었다.
	 */
	@Test
	void 게시자_stale_배치_조회가_던져도_정산한다() {
		authors.freshLookupFails = true;
		tagPages.add(page(null, reel("A", RECENT, 3, 101, ""), reel("B", RECENT, 3, 102, "")));
		BrandCollectService service = service(2000);
		List<PostInfo> posts = service.sweepCore(brand);

		assertThatThrownBy(() -> service.enrich(brand, posts))
				.isInstanceOf(IllegalStateException.class);

		// 실제로 그 경로를 탔다는 근거 — 게시자 콜은 한 건도 못 나갔다(첫 배치 조회에서 끊겼다).
		assertThat(authorCalls()).isZero();
		// 댓글은 게시자 하드 실패와 무관하게 돈다(08-18 수정) — 두 건 다 신규(저장값 0)라 게이트가 열린다.
		assertThat(commentCalls()).isEqualTo(2);
		assertThat(comments.upserted).containsExactlyInAnyOrder("A", "B");
		assertThat(tagged.enriched).containsExactlyInAnyOrder("A", "B");
	}

	/** 댓글 워터마크 배치 조회가 던져도 정산한다(게시자 단계는 정상 통과 — 두 번째 배치 조회 지점). */
	@Test
	void 댓글_워터마크_배치_조회가_던져도_정산한다() {
		tagged.commentsCountsFails = true;
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		service(2000).sweep(brand);   // 상위 격리가 삼키므로 여기서 터지지 않는다

		assertThat(authors.upserted).containsExactly("101");   // 게시자 단계는 통과했다
		assertThat(commentCalls()).isZero();                   // 댓글은 배치 조회에서 끊겼다
		assertThat(tagged.enriched).containsExactly("A");
	}

	// ── 보강 병렬화(2026-08-07 스펙 — 워커 풀 크기는 설정, 08-13부터 기본 10) ────

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
					comments, tagged, authors, brands, new FakeAdJudge(), pool, 2000, 10000, 3, 30, true);
			svc.enrich(brand, svc.sweepCore(brand));
		} finally {
			pool.shutdown();
		}
		assertThat(maxInFlight.get()).isEqualTo(3);   // 풀 크기까지 도달, 초과 없음
		assertThat(authors.upserted)
				.containsExactlyInAnyOrder("201", "202", "203", "204", "205", "206");
	}

	// ---------- 광고 표기 판정 배선(2026-08-17 스펙 §7·§8) ----------

	@Test
	void 게시자_보강_직후_정산되고_댓글_실패는_광고_판정을_막지_않는다() {
		tagged.commentsCountsFails = true;   // 댓글 게이트 배치 조회가 던져도
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		// commentCount(3L) > 저장값(0, 기본) — 게이트가 열려야 배치 조회(commentsCountsFails)가
		// 실제로 호출된다(comments=null이면 p.comments() != null 필터에서 조기 반환돼 죽은 코드가 됨).
		PostInfo post = post("AAA", RECENT, null, 3L);

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");     // 정산은 됐고
		assertThat(adJudge.judged).contains("AAA");       // 광고 판정도 여전히 돈다(댓글과 독립)
	}

	@Test
	void 광고_판정_실패는_격리되고_정산에_영향_없다() {
		FakeAdJudge adJudge = new FakeAdJudge();
		adJudge.fail = true;
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, null, 0L);

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");   // 판정 실패가 정산을 막지 않는다
	}

	/**
	 * 게시자 개별 콜(5xx·타임아웃)이 실패해도 ensureAuthors 자체는 예외 없이 격리를 마친다(기존
	 * 경로) — 정산·광고 판정 모두 정상 진행. 이름에 "소프트"를 명시한다: ensureAuthors 첫머리의
	 * 배치 DB 조회가 통째로 던지는 <b>하드</b> 실패는 별도 경로라 이 테스트가 커버하지 못한다
	 * (08-18 스펙 리뷰 — 하드 경로는 아래 회귀 방어 테스트가 담당).
	 */
	@Test
	void 게시자_개별_보강_소프트_실패는_격리되고_정산과_광고_판정은_돈다() {
		failingAuthorIds.add("111");   // ensureAuthors가 예외 없이 격리되는 기존 경로 유지 확인용 대역
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, "111", 0L);

		svc.enrich(brand, List.of(post));

		assertThat(tagged.enriched).contains("AAA");
		assertThat(adJudge.judged).contains("AAA");
	}

	/**
	 * 1번 버그 수정(2026-08-18)의 회귀 방어 — ensureAuthors 첫머리의 배치 DB 조회(freshIgUserIds)가
	 * 통째로 던지는 <b>하드</b> 실패 경로. 예외 전파는 기존 규칙대로 유지하되(호출자 상위 격리가
	 * 로그로 남긴다), 그 전파 도중에도 정산·댓글 수집·광고 판정이 전부 돈다는 것을 검증한다 — 이중
	 * try/finally 구조가 없으면 안쪽 finally(markEnriched) 직후 예외가 곧장 메서드 밖으로 새서
	 * 댓글·판정 호출 자체가 도달 불가였다(08-18 프로브로 확인).
	 */
	@Test
	void 게시자_보강_하드_실패에도_댓글_수집과_광고_판정은_돈다() {
		authors.freshLookupFails = true;   // ensureAuthors 첫머리 배치 조회가 통째로 던진다
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, "111", 3L);

		assertThatThrownBy(() -> svc.enrich(brand, List.of(post)))
				.isInstanceOf(IllegalStateException.class);

		assertThat(tagged.enriched).contains("AAA");      // 정산은 하드 실패에도 찍힌다(기존 규칙)
		assertThat(comments.upserted).contains("AAA");    // 댓글 수집도 하드 실패와 무관하게 돈다
		assertThat(adJudge.judged).contains("AAA");        // 광고 판정도 하드 실패와 무관하게 돈다
	}

	/**
	 * 킬 스위치(2026-08-18) — enabled=false면 judgeAdDisclosuresSafely 진입점에서 곧바로 리턴해
	 * adJudge.judgePosts 자체가 호출되지 않는다. was 노출 토글(expose)과 독립적인 롤백 수단이라
	 * 정산·댓글 수집은 평소대로 돈다는 것도 함께 확인한다.
	 */
	@Test
	void 킬_스위치가_꺼지면_광고_판정_호출_자체가_나가지_않는다() {
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge, false);
		PostInfo post = post("AAA", RECENT, null, 0L);

		svc.enrich(brand, List.of(post));

		assertThat(adJudge.judged).isEmpty();          // adJudge 호출 자체가 없다
		assertThat(tagged.enriched).contains("AAA");    // 정산은 킬 스위치와 무관하게 그대로 돈다
	}

	/**
	 * 경쟁사 판정 스킵(2026-08-19 경쟁사 판정 제거 설계 §3) — brand.hasOwnLink()==false(활성 own
	 * 연결이 하나도 없는 브랜드)면 킬 스위치와 같은 층위에서 adJudge 호출 자체가 나가지 않는다. 정산·
	 * 댓글 수집은 무관하게 그대로 돈다(킬 스위치 테스트와 같은 회귀 방지 포인트).
	 */
	@Test
	void 경쟁사_전용_브랜드는_광고_판정_호출_자체가_나가지_않는다() {
		BrandRow competitorOnlyBrand = new BrandRow(brand.id(), brand.username(), brand.igUserId(), brand.status(),
				brand.lastSweptOn(), brand.collectionMonths(), false);
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, null, 0L);

		svc.enrich(competitorOnlyBrand, List.of(post));

		assertThat(adJudge.judged).isEmpty();          // adJudge 호출 자체가 없다
		assertThat(tagged.enriched).contains("AAA");    // 정산은 own 연결 여부와 무관하게 그대로 돈다
	}

	/** own 연결이 있는 브랜드는 기존대로 판정이 돈다 — 회귀 방지(대조군). */
	@Test
	void own_연결이_있는_브랜드는_광고_판정이_돈다() {
		FakeAdJudge adJudge = new FakeAdJudge();
		BrandCollectService svc = serviceWithAdJudge(adJudge);
		PostInfo post = post("AAA", RECENT, null, 0L);

		svc.enrich(brand, List.of(post));   // brand.hasOwnLink() == true(픽스처 기본값)

		assertThat(adJudge.judged).contains("AAA");
	}

	// ---------- 계정 게이트 훅(onVisible, 2026-08-18 markServing 단축 — 스펙 §8 후속) ----------

	/**
	 * 등록 백필의 계정 게이트(BrandRegistrationService.markServing)가 게시물 게이트(markEnriched)와
	 * 같은 지점에서 열려야 한다는 요구의 핵심 근거 — onVisible은 댓글 수집 리포지토리 호출보다
	 * 먼저 발화한다. 종전 배선(enrich 전체 반환 후 markServing)이면 이 순서가 뒤집혀 댓글·판정까지
	 * 기다려야 계정이 ready였다(이번 개정의 버그 리포트 원인).
	 */
	@Test
	void onVisible은_댓글_수집_리포지토리_호출_전에_발생한다() {
		List<String> order = new ArrayList<>();
		comments.useSharedCallOrder(order);
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));   // commentCount 3 > 저장값 0 — 게이트 오픈
		BrandCollectService svc = service(2000);
		List<PostInfo> posts = svc.sweepCore(brand);

		svc.enrich(brand, posts, () -> order.add("onVisible"));

		assertThat(order).containsExactly("onVisible", "comments:A");
	}

	/**
	 * ensureAuthors 첫머리의 배치 DB 조회가 통째로 던지는 하드 실패에도 onVisible은 markEnriched와
	 * 같은 finally 보장을 받는다 — 첫 페이지 게시자 보강 실패가 계정 ready를 영구히 막으면 안 된다.
	 */
	@Test
	void onVisible은_게시자_보강_하드_실패에도_발생한다() {
		authors.freshLookupFails = true;
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));
		BrandCollectService svc = service(2000);
		List<PostInfo> posts = svc.sweepCore(brand);
		List<String> visible = new ArrayList<>();

		assertThatThrownBy(() -> svc.enrich(brand, posts, () -> visible.add("visible")))
				.isInstanceOf(IllegalStateException.class);

		assertThat(visible).containsExactly("visible");   // 하드 실패에도 훅은 정상 발화
		assertThat(tagged.enriched).contains("A");          // 정산도 기존 규칙대로 찍힌다(무변경)
	}

	// ---------- 진행 워터마크(touchProgress, 2026-08-31 등록 백필 캐시 고착 수리) ----------

	/**
	 * 페이지 보강이 끝나면 브랜드 진행 워터마크(last_swept_at)를 전진시킨다 — was 인덱스 캐시
	 * (BrandIndexCache)의 버전키가 brand_account 워터마크만 보므로, 페이지마다 안 움직이면 백필
	 * 도중 폴링이 전부 캐시에 붙어 게시물이 완주 시점에 한꺼번에 나타난다(08-31 skinfood 실측:
	 * 90초간 21건 동결 후 247건 일괄 노출).
	 */
	@Test
	void 보강이_끝나면_브랜드_워터마크를_전진시킨다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		service(2000).sweep(brand);

		assertThat(brands.progressTouches).containsExactly(1L);
	}

	/**
	 * 전진은 정산 마킹(markEnriched) 뒤여야 한다 — 앞이면 새 버전키로 재계산된 인덱스가 아직
	 * 정산 안 된 단면을 캐시해, 그 페이지가 다음 전진까지 다시 안 보인다.
	 */
	@Test
	void 워터마크_전진은_정산_마킹_후에_발생한다() {
		List<String> order = new ArrayList<>();
		tagged.useSharedCallOrder(order);
		brands.useSharedCallOrder(order);
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));

		service(2000).sweep(brand);

		assertThat(order).containsExactly("enriched", "progress");
	}

	/** 빈 배치는 데이터 변화가 없다 — 전진하면 폴링마다 헛 재계산만 유발한다. */
	@Test
	void 빈_배치에는_워터마크를_전진시키지_않는다() {
		service(2000).enrich(brand, List.of(), () -> { });

		assertThat(brands.progressTouches).isEmpty();
	}

	/**
	 * 게시자 보강 하드 실패에도 전진한다 — markEnriched와 같은 finally 보장. 정산이 찍힌 이상
	 * (게이트 통과) 워터마크가 안 움직이면 그 페이지는 완주까지 캐시 뒤에 숨는다.
	 */
	@Test
	void 게시자_보강_하드_실패에도_워터마크는_전진한다() {
		authors.freshLookupFails = true;
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));
		BrandCollectService svc = service(2000);
		List<PostInfo> posts = svc.sweepCore(brand);

		assertThatThrownBy(() -> svc.enrich(brand, posts, null))
				.isInstanceOf(IllegalStateException.class);

		assertThat(brands.progressTouches).containsExactly(1L);
	}

	/** 태그 0건(빈 배치)도 onVisible을 1회 받는다 — 못 받으면 그 브랜드가 collecting에 영구히 갇힌다. */
	@Test
	void onVisible은_빈_배치에도_발생한다() {
		BrandCollectService svc = service(2000);
		List<String> visible = new ArrayList<>();

		svc.enrich(brand, List.of(), () -> visible.add("visible"));

		assertThat(visible).containsExactly("visible");
	}

	/** 2-인자 오버로드(야간 스윕이 쓰는 경로)는 onVisible 없이도 기존과 동일하게 동작한다 — 회귀 방지. */
	@Test
	void onVisible_없는_2인자_enrich는_기존_동작_그대로다() {
		tagPages.add(page(null, reel("A", RECENT, 3, 101, "")));
		BrandCollectService svc = service(2000);
		List<PostInfo> posts = svc.sweepCore(brand);

		svc.enrich(brand, posts);   // onVisible 없음 — NPE 없이 그대로

		assertThat(tagged.enriched).contains("A");
		assertThat(comments.upserted).contains("A");
	}

	private BrandCollectService serviceWithAdJudge(FakeAdJudge adJudge) {
		return serviceWithAdJudge(adJudge, true);
	}

	private BrandCollectService serviceWithAdJudge(FakeAdJudge adJudge, boolean adDisclosureEnabled) {
		return new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
				brands, adJudge, Runnable::run, 10000, 10000, 3, 30, adDisclosureEnabled);
	}

	/**
	 * comments(commentCount)를 명시적으로 받는다 — null이면 {@code collectCommentsGated}의
	 * {@code p.comments() != null} 필터에서 조기 반환돼 댓글 게이트 경로(배치 조회·실패 주입 포함)가
	 * 아예 실행되지 않는다(08-18 스펙 리뷰 프로브로 확인 — 죽은 코드였다).
	 */
	private static PostInfo post(String shortCode, Long takenAt, String ownerUserId, long commentCount) {
		return new PostInfo(shortCode, "author", null, null, ownerUserId, "REELS", null, null,
				takenAt, null, commentCount, null, null, null, null, null, null, null, null,
				false, false, false);
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
}
