package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PostShapeUnsupportedException;
import com.celfit.instagram.source.SubjectNotFoundException;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.BrandCommentRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 direct 게시물 단건 수집(2026-08-18 direct 통합 §2-2·§3-2) — BrandCollectServiceTest
 * 관용구(fake HikerHttp + 스텁 서브클래스, DB 없음)로 등록 단건 콜의 저장·보강·정산, 예외 매핑
 * (부재·셰이프 이상), 야간 스윕 2단계의 나이 티어·격리를 검증한다.
 */
class BrandDirectCollectServiceTest {

	private static final long NOW = Instant.now().getEpochSecond();
	private static final long RECENT = NOW - 5L * 86400;     // 매일 티어(0~14일) 안
	private static final long AGE_25D = NOW - 25L * 86400;   // 3일 주기 티어(15~30일) 안 — 경계에서 여유 둠
	private static final long AGE_200D = NOW - 200L * 86400; // 180일 추적 상한 초과 — 영구 제외

	private final RecordingWriter writer = new RecordingWriter();
	private final BrandCallContext callContext = new BrandCallContext();
	private final RecordingCallCounts callCounts = new RecordingCallCounts();
	private final StubSnapshots snapshots = new StubSnapshots();
	private final StubComments comments = new StubComments();
	private final InMemoryTagged tagged = new InMemoryTagged();
	private final InMemoryAuthors authors = new InMemoryAuthors();

	private final List<String> calls = new ArrayList<>();
	private final Map<String, String> postResponses = new HashMap<>();
	private final Set<String> notFoundCodes = new HashSet<>();

	private final BrandRow brand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, LocalDate.now(), 12, true);

	// ── 스텁 대역(BrandCollectServiceTest 관용구 재사용) ──────────────────────

	/** 창 커버리지 무해 스텁 — direct 경로는 열거를 타지 않아 이 메서드들에 닿지 않는다(닿으면 no-op). */
	private static final class InertBrands extends BrandRepository {
		InertBrands() {
			super(null);
		}

		@Override
		public BrandRepository.Coverage coverage(long brandId) {
			return new BrandRepository.Coverage(false, null);
		}

		@Override
		public void touchProgress(long brandId) {
			// 진행 워터마크(08-31) — 이 테스트의 관심 밖, DB 없는 fake라 no-op.
		}

		@Override
		public void updateCoverage(long brandId, boolean capped, Instant coveredUntil) {
			// no-op
		}
	}

	private static final class RecordingCallCounts extends BrandCallCountRepository {
		final Map<Long, Long> byBrand = java.util.Collections.synchronizedMap(new HashMap<>());

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

		RecordingWriter() {
			super(null, null, null);
		}

		@Override
		public void savePost(LocalDate on, PostInfo post) {
			saved.add(post);
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
		final List<String> upsertedDirect = new ArrayList<>();
		final Map<String, Instant> touched = new HashMap<>();
		final List<String> enriched = java.util.Collections.synchronizedList(new ArrayList<>());
		final List<TrackedPost> due = new ArrayList<>();
		final List<TrackedPost> unenrichedDue = new ArrayList<>();
		final List<String> unavailable = new ArrayList<>();
		Instant nthNewestHashtag;                       // 스텁 floor 응답(null = 세트 미포화)
		Instant capturedFloor;                          // unenumeratedDuePosts에 전달된 floor 캡처
		Instant capturedRecheckBefore;                  // 부재 재검증 스로틀 컷 캡처(2026-09-03)
		final List<Instant> frozenTouches = new ArrayList<>();  // touchFrozenHashtag(floor) 호출 캡처

		InMemoryTagged() {
			super(null);
		}

		@Override
		public java.util.Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n) {
			return java.util.Optional.ofNullable(nthNewestHashtag);
		}

		@Override
		public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt, Instant hashtagFloor,
				Instant unavailableRecheckBefore) {
			capturedFloor = hashtagFloor;
			capturedRecheckBefore = unavailableRecheckBefore;
			return unenumeratedDuePosts(brandId, minTakenAt);   // 필터 자체는 store DB 테스트가 고정
		}

		@Override
		public void touchFrozenHashtag(long brandId, Instant minTakenAt, Instant floorTakenAt, Instant at) {
			frozenTouches.add(floorTakenAt);
		}

		@Override
		public void upsertDirect(long brandId, PostInfo post, Instant registeredAt) {
			upsertedDirect.add(post.shortCode());
		}

		@Override
		public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
			for (String c : codes) {
				touched.put(c, at);
			}
		}

		@Override
		public void markEnriched(long brandId, Collection<String> codes, Instant at) {
			enriched.addAll(codes);
		}

		@Override
		public void markUnavailable(long brandId, String shortCode, Instant at) {
			unavailable.add(shortCode);
		}

		@Override
		public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
			Map<String, Long> out = new HashMap<>();
			for (String c : codes) {
				out.put(c, 0L);
			}
			return out;
		}

		@Override
		public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt) {
			return due.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
		}

		@Override
		public List<TrackedPost> unenrichedUnenumeratedPosts(long brandId, Instant minTakenAt) {
			return unenrichedDue.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
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

	// ── fake HikerHttp — 경로별 라우팅 ───────────────────────────────────────

	private HikerBackend client() {
		return new HikerBackend(new CountingHikerHttp(path -> {
			calls.add(path);
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
				String id = path.substring(path.indexOf("?id=") + "?id=".length());
				return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\",\"follower_count\":100,\"is_private\":false}}"
						.formatted(id, id);
			}
			if (path.startsWith("/v2/media/comments")) {
				return """
						{"response":{"comments":[{"pk":"nc1","text":"댓글","comment_like_count":1,
						"created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}]},
						"next_page_id":null}""";
			}
			throw new IllegalStateException("예상 밖 콜: " + path);
		}, callContext, callCounts, new TargetCallContext(), new TargetCallCountRepository(null)));
	}

	private BrandDirectCollectService service() {
		return serviceWithLimit(300);
	}

	private BrandDirectCollectService serviceWithLimit(int sweepLimit) {
		return serviceWithLimit(sweepLimit, 2000);
	}

	private BrandDirectCollectService serviceWithLimit(int sweepLimit, int monitoringSetSize) {
		// adDisclosureEnabled=false — 이 테스트는 direct 단건 수집 경로만 검증한다. 킬 스위치가
		// 꺼져 있으면 judgeAdDisclosuresSafely가 adJudge를 아예 호출하지 않으므로(BrandCollectService
		// 클래스 주석) null을 넘겨도 안전하다.
		// brands는 무해 스텁 — direct 경로는 열거(doSweepCore·enumerationCutoff)를 타지 않아
		// 커버리지 조회·기록 지점에 닿지 않는다(collect는 adjustLotteryMetrics 재사용 목적).
		BrandCollectService collect = new BrandCollectService(client(), client(), client(), callContext, writer, snapshots, comments,
				tagged, authors, new InertBrands(), null, Runnable::run, 2000, 10000, 3, 30, false);
		return new BrandDirectCollectService(client(), client(), callContext, writer, tagged, collect, sweepLimit,
				monitoringSetSize);
	}

	/** service()가 collect·direct 각자 별도 HikerBackend(별도 fake 인스턴스)를 갖지만 같은 calls 리스트를 공유한다. */
	private long postCalls() {
		return calls.stream().filter(c -> c.startsWith("/v2/media/info/by/code")).count();
	}

	private static String postJson(String code, long takenAt, long authorPk) {
		return """
				{"media_or_ad":{"code":"%s","taken_at":%d,"product_type":"clips","like_count":10,
				"comment_count":2,"ig_play_count":500,
				"user":{"pk":%d,"username":"author_%d","full_name":"작가","profile_pic_url":"https://p"}}}"""
				.formatted(code, takenAt, authorPk, authorPk);
	}

	private static String postJsonNoTakenAt(String code, long authorPk) {
		return """
				{"media_or_ad":{"code":"%s","product_type":"clips","like_count":10,
				"comment_count":2,"ig_play_count":500,
				"user":{"pk":%d,"username":"author_%d","full_name":"작가","profile_pic_url":"https://p"}}}"""
				.formatted(code, authorPk, authorPk);
	}

	// ── collectAndEnrich ────────────────────────────────────────────────────

	@Test
	void 수집_1건은_스냅샷_메타_링크_보강까지_전부_채운다() {
		postResponses.put("D1", postJson("D1", RECENT, 101));

		PostInfo result = service().collectAndEnrich(brand, "D1", Instant.parse("2026-08-18T03:00:00Z"));

		assertThat(result.shortCode()).isEqualTo("D1");
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("D1");
		assertThat(tagged.upsertedDirect).containsExactly("D1");
		assertThat(tagged.touched).containsKey("D1");
		assertThat(authors.upserted).containsExactly("101");     // 게시자 보강
		assertThat(comments.upserted).containsExactly("D1");     // 댓글 보강
		assertThat(tagged.enriched).containsExactly("D1");       // 정산 마킹(enriched_at 게이트)
	}

	@Test
	void 게시물_부재는_예외로_전파된다() {
		notFoundCodes.add("Ghost");

		assertThatThrownBy(() -> service().collectAndEnrich(brand, "Ghost", Instant.now()))
				.isInstanceOf(SubjectNotFoundException.class);

		assertThat(writer.saved).isEmpty();
		assertThat(tagged.upsertedDirect).isEmpty();
	}

	@Test
	void 게시일_미상_응답은_전용_예외로_던진다() {
		postResponses.put("NoDate", postJsonNoTakenAt("NoDate", 101));

		assertThatThrownBy(() -> service().collectAndEnrich(brand, "NoDate", Instant.now()))
				.isInstanceOf(PostShapeUnsupportedException.class);

		assertThat(writer.saved).isEmpty();
		assertThat(tagged.upsertedDirect).isEmpty();
	}

	// ── 동기(collectAndEnrich)·비동기(sweepUnenumerated) 소스 분리 — 사용자 대면 동기 경로
	// self 트러블 원천 차단 ───────────────────────────────────────────────────

	/**
	 * {@link BrandDirectCollectService#collectAndEnrich}(direct 게시물 동기 등록, BrandController
	 * POST .../direct-posts)는 생성자 2번째 인자(syncHiker)로, {@link
	 * BrandDirectCollectService#sweepUnenumerated}(야간 스윕 2단계, 비동기)는 1번째 인자(hiker)로
	 * fetchPost를 각자 라우팅한다 — 서로 다른 fake 백엔드로 갈아 끼워 콜이 각자의 리스트로만 들어오는지
	 * 본다(BrandCollectServiceTest의 enrichSync 라우팅 테스트와 짝).
	 */
	@Test
	void collectAndEnrich은_syncHiker로_sweepUnenumerated는_hiker로_fetchPost가_라우팅된다() {
		List<String> hikerCalls = new ArrayList<>();
		List<String> syncCalls = new ArrayList<>();
		InstagramSource hikerSource = new HikerBackend(new CountingHikerHttp(path -> {
			hikerCalls.add(path);
			return postJson("D1", RECENT, 101);
		}, callContext, callCounts, new TargetCallContext(), new TargetCallCountRepository(null)));
		InstagramSource syncSource = new HikerBackend(new CountingHikerHttp(path -> {
			syncCalls.add(path);
			return postJson("D1", RECENT, 101);
		}, callContext, callCounts, new TargetCallContext(), new TargetCallCountRepository(null)));
		BrandDirectCollectService svc = serviceWithSeparateSources(hikerSource, syncSource);

		svc.collectAndEnrich(brand, "D1", Instant.now());
		assertThat(hikerCalls).isEmpty();
		assertThat(syncCalls).isNotEmpty();

		syncCalls.clear();
		tagged.due.add(new TaggedPostRepository.TrackedPost("D2", Instant.ofEpochSecond(RECENT), null));
		svc.sweepUnenumerated(brand);
		assertThat(syncCalls).isEmpty();
		assertThat(hikerCalls).isNotEmpty();
	}

	private BrandDirectCollectService serviceWithSeparateSources(InstagramSource hiker, InstagramSource syncHiker) {
		BrandCollectService collect = new BrandCollectService(client(), client(), client(), callContext, writer, snapshots,
				comments, tagged, authors, new InertBrands(), null, Runnable::run, 2000, 10000, 3, 30, false);
		return new BrandDirectCollectService(hiker, syncHiker, callContext, writer, tagged, collect, 300, 2000);
	}

	// ── sweepUnenumerated — 격리 ───────────────────────────────────────────────────

	@Test
	void 부재_게시물은_삼키고_나머지는_계속_수집된다() {
		tagged.due.add(new TaggedPostRepository.TrackedPost("Gone", Instant.ofEpochSecond(RECENT), null));
		tagged.due.add(new TaggedPostRepository.TrackedPost("Alive", Instant.ofEpochSecond(RECENT), null));
		notFoundCodes.add("Gone");
		postResponses.put("Alive", postJson("Alive", RECENT, 102));

		service().sweepUnenumerated(brand);   // 예외가 새면 여기서 터진다

		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("Alive");
		assertThat(tagged.enriched).containsExactly("Alive");
	}

	@Test
	void _180일_초과_direct_행은_콜을_내지_않는다() {
		tagged.due.add(new TaggedPostRepository.TrackedPost("Ancient", Instant.ofEpochSecond(AGE_200D),
				Instant.ofEpochSecond(NOW - 40L * 86400)));

		service().sweepUnenumerated(brand);

		assertThat(postCalls()).isZero();
		assertThat(writer.saved).isEmpty();
	}

	@Test
	void 스윕_404_게시물은_unavailable_마킹되고_나머지는_계속_수집된다() {
		tagged.due.add(new TaggedPostRepository.TrackedPost("Gone", Instant.ofEpochSecond(RECENT),
				Instant.now().minusSeconds(86400)));
		tagged.due.add(new TaggedPostRepository.TrackedPost("Alive", Instant.ofEpochSecond(RECENT),
				Instant.now().minusSeconds(86400)));
		notFoundCodes.add("Gone");
		postResponses.put("Alive", postJson("Alive", RECENT, 105));

		service().sweepUnenumerated(brand);

		// 404 게시물: 마킹만, 저장·touch 없음(마지막 수집값 보존)
		assertThat(tagged.unavailable).containsExactly("Gone");
		assertThat(tagged.touched).doesNotContainKey("Gone");
		// 격리 유지: 나머지 게시물은 정상 수집되고 touch(관측=해제 경로)를 지난다
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("Alive");
		assertThat(tagged.touched).containsKey("Alive");
	}

	@Test
	void _14일_이내_direct_행은_매일_due다() {
		// last_crawled_at이 어제(1일 전)여도 0~14일 티어는 매일 수집 대상이다.
		tagged.due.add(new TaggedPostRepository.TrackedPost("Recent", Instant.ofEpochSecond(RECENT),
				Instant.now().minusSeconds(86400)));
		postResponses.put("Recent", postJson("Recent", RECENT, 103));

		service().sweepUnenumerated(brand);

		assertThat(postCalls()).isEqualTo(1);
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("Recent");
	}

	@Test
	void _30일령_행은_3일_주기로만_due다() {
		// 30일령, 마지막 크롤 1일 전(3일 주기 미경과) — due 아님, 콜 없음.
		tagged.due.add(new TaggedPostRepository.TrackedPost("Tier2Fresh", Instant.ofEpochSecond(AGE_25D),
				Instant.now().minusSeconds(86400)));

		service().sweepUnenumerated(brand);

		assertThat(postCalls()).isZero();

		// 마지막 크롤 4일 전(3일 주기 경과) — due, 콜 발생.
		tagged.due.clear();
		tagged.due.add(new TaggedPostRepository.TrackedPost("Tier2Due", Instant.ofEpochSecond(AGE_25D),
				Instant.now().minusSeconds(4L * 86400)));
		postResponses.put("Tier2Due", postJson("Tier2Due", AGE_25D, 104));

		service().sweepUnenumerated(brand);

		assertThat(postCalls()).isEqualTo(1);
	}

	// ── 스윕당 상한(2026-08-27 해시태그 직접 수집 설계 §5) ─────────────────────

	/**
	 * 이관분은 last_crawled_at이 NULL이라 전부 즉시 due다 — 상한이 없으면 첫 스윕이 브랜드당
	 * 수백~1,000건의 단건 콜을 한 번에 쏟아내 전역 콜 예산을 넘긴다. 잔여는 다음 스윕이 이어받는다
	 * (모수 정렬이 미보강 우선이라 이관분부터 충전된다).
	 */
	@Test
	void 스윕당_상한을_넘는_due는_잘리고_다음_스윕으로_넘어간다() {
		for (int i = 0; i < 5; i++) {
			String code = "M" + i;
			tagged.due.add(new TaggedPostRepository.TrackedPost(code, Instant.ofEpochSecond(RECENT), null));
			postResponses.put(code, postJson(code, RECENT, 200 + i));
		}

		serviceWithLimit(2).sweepUnenumerated(brand);

		assertThat(postCalls()).isEqualTo(2);
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("M0", "M1");
	}

	/**
	 * fetch는 성공했지만 게시일 미상이라 저장 불가한 행(collectOne)은 touchCrawled로 커버 처리해야
	 * 한다 — 안 그러면 unenumeratedDuePosts 정렬이 미보강 우선이라 이 행이 계속 상한 창 맨 앞을
	 * 점유해 나머지 행이 영구 굶는다(2026-08-27 리뷰 지적).
	 */
	@Test
	void taken_at_없는_게시물은_커버_처리되어_상한_창을_점유하지_않는다() {
		tagged.due.add(new TaggedPostRepository.TrackedPost("NoDate", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("NoDate", postJsonNoTakenAt("NoDate", 106));
		tagged.due.add(new TaggedPostRepository.TrackedPost("After", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("After", postJson("After", RECENT, 107));

		service().sweepUnenumerated(brand);

		assertThat(tagged.touched).containsKey("NoDate");   // 커버 처리 — 즉시-due 창에서 빠진다
		assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("After");
	}

	// ── 감시 세트 바닥 한정(2026-09-02 해시태그 감시 세트 설계 §3) ─────────────────

	/** 감시 세트가 포화면(floor 존재) 2단계는 동결 touch 후 floor 한정 모수로 돈다(설계 §3). */
	@Test
	void 스윕2단계는_세트_바닥을_동결_touch하고_같은_바닥으로_모수를_자른다() {
		Instant floor = Instant.ofEpochSecond(NOW - 7L * 86400);
		tagged.nthNewestHashtag = floor;
		tagged.due.add(new TaggedPostRepository.TrackedPost("AAA", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("AAA", postJson("AAA", RECENT, 501));

		serviceWithLimit(0).sweepUnenumerated(brand);

		assertThat(tagged.frozenTouches).containsExactly(floor);
		assertThat(tagged.capturedFloor).isEqualTo(floor);
	}

	/**
	 * 부재 재검증 스로틀(2026-09-03) — 2단계 모수 선정에 "지금 − ABSENCE_RECHECK(7일)" 컷을
	 * 넘긴다. 컷의 실제 필터링은 store DB 테스트가 고정하고, 여기서는 배선(태그 부재 검증과
	 * 같은 주기를 쓰는지)만 잡는다.
	 */
	@Test
	void 스윕2단계는_부재_재검증_컷을_ABSENCE_RECHECK_주기로_넘긴다() {
		tagged.due.add(new TaggedPostRepository.TrackedPost("AAA", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("AAA", postJson("AAA", RECENT, 501));

		Instant before = Instant.now().minus(BrandCollectService.ABSENCE_RECHECK);
		serviceWithLimit(0).sweepUnenumerated(brand);
		Instant after = Instant.now().minus(BrandCollectService.ABSENCE_RECHECK);

		assertThat(tagged.capturedRecheckBefore).isNotNull();
		assertThat(tagged.capturedRecheckBefore).isBetween(before, after);
	}

	/** 세트 미포화(floor 없음)면 동결 touch를 부르지 않고 기존 전체 모수 그대로다. */
	@Test
	void 세트_미포화면_동결_touch_없이_전체_모수로_돈다() {
		tagged.nthNewestHashtag = null;
		tagged.due.add(new TaggedPostRepository.TrackedPost("AAA", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("AAA", postJson("AAA", RECENT, 502));

		serviceWithLimit(0).sweepUnenumerated(brand);

		assertThat(tagged.frozenTouches).isEmpty();
		assertThat(tagged.capturedFloor).isNull();
	}

	// ── backfillUnenriched — 기동 즉시 백필(2026-08-28 사용자 지시) ─────────────

	/**
	 * 스윕당 상한(sweepLimit)이 걸려 있어도 기동 백필은 무시하고 전량 소진한다 — 점진 소진은
	 * 야간 스윕 전용 정책이고, 기동 백필의 목적 자체가 그 점진성을 깨는 것이다.
	 */
	@Test
	void 스윕_상한을_무시하고_미보강_전량을_소진한다() {
		for (int i = 0; i < 5; i++) {
			String code = "U" + i;
			tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost(code, Instant.ofEpochSecond(RECENT), null));
			postResponses.put(code, postJson(code, RECENT, 300 + i));
		}

		int backfilled = serviceWithLimit(2).backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(5);
		assertThat(postCalls()).isEqualTo(5);
		assertThat(tagged.enriched).containsExactlyInAnyOrder("U0", "U1", "U2", "U3", "U4");
	}

	/**
	 * BrandCrawlPolicy.due 나이 티어를 적용하지 않는다 — last_crawled_at이 방금(0초 전)이라 정상
	 * sweepUnenumerated였다면 due 아닌 행도 기동 백필은 건너뛰지 않는다.
	 */
	@Test
	void 나이_티어_due_판정과_무관하게_전량_수집한다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("JustCrawled", Instant.ofEpochSecond(RECENT),
				Instant.now()));   // 방금 크롤됨 — sweepUnenumerated였다면 due=false로 걸러졌을 행
		postResponses.put("JustCrawled", postJson("JustCrawled", RECENT, 401));

		int backfilled = service().backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(1);
		assertThat(postCalls()).isEqualTo(1);
		assertThat(tagged.enriched).containsExactly("JustCrawled");
	}

	/** 미보강 재고가 없으면 콜 없이 0을 돌려준다 — 러너가 매 기동마다 값싸게 no-op으로 지나가야 한다. */
	@Test
	void 미보강_재고가_없으면_콜_없이_0을_돌려준다() {
		int backfilled = service().backfillUnenriched(brand);

		assertThat(backfilled).isZero();
		assertThat(postCalls()).isZero();
	}

	/** 부재 게시물은 격리되고 나머지는 계속 보강된다 — sweepUnenumerated와 같은 실패 격리 규율. */
	@Test
	void 백필_중_부재_게시물은_격리되고_나머지는_계속_보강된다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Gone", Instant.ofEpochSecond(RECENT), null));
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Alive", Instant.ofEpochSecond(RECENT), null));
		notFoundCodes.add("Gone");
		postResponses.put("Alive", postJson("Alive", RECENT, 402));

		int backfilled = service().backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(2);   // 시도한 행 수(부재 포함)
		assertThat(tagged.unavailable).containsExactly("Gone");
		assertThat(tagged.enriched).containsExactly("Alive");
	}

	// ── unenumeratedBusy 동시 실행 가드(2026-08-28 리뷰 지적) ───────────────────

	/**
	 * sweepUnenumerated(야간 스윕 2단계)가 처리 중일 때 기동 백필이 같은 게시물을 겹쳐 Hiker에
	 * 이중 과금하지 않도록, 겹침이면 backfillUnenriched는 즉시 0을 반환하고 콜을 내지 않는다.
	 * 실제 스레드 경합 대신 package-private 필드로 겹침 상태를 결정적으로 주입한다.
	 */
	@Test
	void 겹침_상태에서는_backfillUnenriched가_콜_없이_0을_반환한다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Busy", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("Busy", postJson("Busy", RECENT, 403));
		BrandDirectCollectService svc = service();
		svc.unenumeratedBusy.set(true);   // sweepUnenumerated가 다른 브랜드를 처리 중이라고 가정

		int backfilled = svc.backfillUnenriched(brand);

		assertThat(backfilled).isZero();
		assertThat(postCalls()).isZero();
		assertThat(tagged.enriched).isEmpty();
	}

	/** 겹침이 없으면(플래그 false) 평소대로 동작 — 가드가 정상 경로를 막지 않는다는 회귀 방지. */
	@Test
	void 겹침이_없으면_backfillUnenriched는_평소대로_동작한다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Free", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("Free", postJson("Free", RECENT, 404));
		BrandDirectCollectService svc = service();

		int backfilled = svc.backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(1);
		assertThat(tagged.enriched).containsExactly("Free");
		assertThat(svc.unenumeratedBusy.get()).isFalse();   // 처리 후 해제됨 — 다음 호출을 막지 않는다
	}
}
