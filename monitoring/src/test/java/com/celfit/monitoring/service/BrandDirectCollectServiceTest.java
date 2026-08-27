package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PostShapeUnsupportedException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
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
		final List<String> unavailable = new ArrayList<>();

		InMemoryTagged() {
			super(null);
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

	private HikerClient client() {
		return new HikerClient(new CountingHikerHttp(path -> {
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
		// adDisclosureEnabled=false — 이 테스트는 direct 단건 수집 경로만 검증한다. 킬 스위치가
		// 꺼져 있으면 judgeAdDisclosuresSafely가 adJudge를 아예 호출하지 않으므로(BrandCollectService
		// 클래스 주석) null을 넘겨도 안전하다.
		// brands는 무해 스텁 — direct 경로는 열거(doSweepCore·enumerationCutoff)를 타지 않아
		// 커버리지 조회·기록 지점에 닿지 않는다(collect는 adjustLotteryMetrics 재사용 목적).
		BrandCollectService collect = new BrandCollectService(client(), callContext, writer, snapshots, comments,
				tagged, authors, new InertBrands(), null, Runnable::run, 2000, 10000, 3, 30, false);
		return new BrandDirectCollectService(client(), callContext, writer, tagged, collect, sweepLimit);
	}

	/** service()가 collect·direct 각자 별도 HikerClient(별도 fake 인스턴스)를 갖지만 같은 calls 리스트를 공유한다. */
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
}
