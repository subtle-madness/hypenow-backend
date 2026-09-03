package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사용자 대면 동기 경로 전용 정책 계층 — {@link FailoverInstagramSource}(자체 1순위)의 역방향이다:
 * <b>Hiker 1순위, 자체는 Hiker 장애 시 구조(rescue) 수단</b>. 목적은 두 가지를 동시에 만족시키는 것 —
 * (1) 동기(사용자 대면) 경로에서 자체크롤 트러블(느린 재시도·프록시 문제)이 단 한 번도 응답 지연에
 * 얹히지 않는다(평시엔 자체를 아예 시도하지 않는다), (2) 그럼에도 Hiker 자체가 장애일 때(2026-08-27
 * 브랜드 등록 503 사고처럼 Hiker가 5xx를 주는 케이스)는 자체가 구조 수단으로 남는다.
 *
 * <p>{@code SubjectNotFoundException}·{@code PrivateAccountException}(계정 부재·비공개 — Hiker의
 * 정상 판정)은 자체로 구조하지 않고 그대로 전파한다. 그 외 벤더 장애성 실패({@link HikerFetchException}
 * 등)만 자체 구조를 시도한다 — self도 실패하면(하드게이트 {@link UnsupportedOperationException}
 * 포함) <b>원래 Hiker 예외를 그대로 던진다</b>(self의 실패 사유가 아니라 최초 실패 사유가 호출자에게
 * 의미 있다).
 *
 * <p>자체 구조 시도는 짧아야 한다 — Hiker가 이미 시간을 쓴 뒤라, self가 통상의 다회 재시도(SelfRetry
 * 기본 8초 예산)를 또 쓰면 동기 응답 예산을 넘긴다. 호출부(HikerConfig)가 구조 전용 self 인스턴스를
 * 짧은 예산(1회 시도)으로 별도 조립해 넘긴다 — 이 클래스 자체는 self 인스턴스의 재시도 정책을 모른다.
 *
 * <p>토글(selfEnabledForPath)이 꺼져 있으면(self==null이거나 predicate가 false) 구조 시도 자체를
 * 하지 않는다 — Hiker 단독과 행동이 완전히 같다(행동 변화 0).
 */
public class HikerFirstInstagramSource implements InstagramSource {

	private static final Logger log = LoggerFactory.getLogger(HikerFirstInstagramSource.class);

	private final InstagramSource hiker;
	private final InstagramSource self;
	private final Predicate<String> selfEnabledForPath;
	private final InstagramSourceMetrics metrics;

	/** self 없이 Hiker 단독(테스트·구조 미배선 호환) — 구조 시도가 아예 없다. */
	public HikerFirstInstagramSource(InstagramSource hiker) {
		this(hiker, null, path -> false, InstagramSourceMetrics.NOOP);
	}

	public HikerFirstInstagramSource(InstagramSource hiker, InstagramSource self,
			Predicate<String> selfEnabledForPath, InstagramSourceMetrics metrics) {
		this.hiker = hiker;
		this.self = self;
		this.selfEnabledForPath = selfEnabledForPath;
		this.metrics = metrics;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return rescue("fetchProfile", () -> hiker.fetchProfile(username), () -> self.fetchProfile(username));
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return rescue("fetchAuthorProfile", () -> hiker.fetchAuthorProfile(userId),
				() -> self.fetchAuthorProfile(userId));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return rescue("fetchRecentPosts", () -> hiker.fetchRecentPosts(username, userId, pages),
				() -> self.fetchRecentPosts(username, userId, pages));
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return rescue("fetchClipCounts", () -> hiker.fetchClipCounts(userId, pages),
				() -> self.fetchClipCounts(userId, pages));
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return rescue("fetchTaggedPage", () -> hiker.fetchTaggedPage(userId, pageId),
				() -> self.fetchTaggedPage(userId, pageId));
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return rescue("fetchHashtagRecentPage", () -> hiker.fetchHashtagRecentPage(tag, pageId),
				() -> self.fetchHashtagRecentPage(tag, pageId));
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return rescue("fetchPost", () -> hiker.fetchPost(shortCode), () -> self.fetchPost(shortCode));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return rescue("fetchComments", () -> hiker.fetchComments(shortCode, postUsername, pages),
				() -> self.fetchComments(shortCode, postUsername, pages));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return rescue("fetchComments",
				() -> hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds),
				() -> self.fetchComments(shortCode, postUsername, pages, knownCommentIds));
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return rescue("resolveMediaByUrl", () -> hiker.resolveMediaByUrl(url),
				() -> self.resolveMediaByUrl(url));
	}

	/**
	 * Hiker 1순위 — 성공하면 그대로 반환(자체는 건드리지 않는다). 실패 시 {@code SubjectNotFoundException}·
	 * {@code PrivateAccountException}(계정 부재·비공개, Hiker의 정상 판정)은 즉시 전파한다. 그 외
	 * 벤더 장애성 실패만, 그리고 self가 있고 토글이 그 path를 허용할 때만 self로 구조를 1회 시도한다.
	 * self가 성공하면 그 결과를 쓰고, self도 실패하면(하드게이트 포함) <b>원래 Hiker 예외</b>를 던진다.
	 * record와 짝을 이뤄 같은 (path, backend, outcome) 태그로 소요시간도 함께 남긴다 — 구조(rescue)
	 * 케이스의 duration은 Hiker 콜 + self 콜을 합친 "호출자가 본 논리 콜 1건"의 총 소요다(의도된 설계).
	 */
	private <T> T rescue(String path, Supplier<T> hikerCall, Supplier<T> selfCall) {
		long start = System.nanoTime();
		try {
			T r = hikerCall.get();
			metrics.record(path, "hiker", "ok");
			metrics.recordDuration(path, "hiker", "ok", System.nanoTime() - start);
			return r;
		} catch (SubjectNotFoundException | PrivateAccountException businessFailure) {
			// Hiker의 결정적 판정(계정 부재·비공개) — self로 재시도해도 같은 결론이라 구조 대상이 아니다.
			throw businessFailure;
		} catch (RuntimeException hikerFailure) {
			if (self == null || !selfEnabledForPath.test(path)) {
				throw hikerFailure;
			}
			try {
				T r = selfCall.get();
				log.warn("Hiker {} 실패 — 자체크롤로 구조 성공: {}", path, hikerFailure.toString());
				metrics.record(path, "self", "rescue");
				metrics.recordDuration(path, "self", "rescue", System.nanoTime() - start);
				return r;
			} catch (RuntimeException selfFailure) {
				// self 실패 사유(하드게이트 UnsupportedOperationException 포함)는 부수 정보 — 호출자에게는
				// 최초 실패(Hiker)가 의미 있는 예외이므로 그대로 던진다.
				log.warn("Hiker {} 실패 후 자체크롤 구조도 실패 — 원 Hiker 예외 전파 (self={})",
						path, selfFailure.toString());
				metrics.record(path, "hiker", "rescue-failed");
				metrics.recordDuration(path, "hiker", "rescue-failed", System.nanoTime() - start);
				throw hikerFailure;
			}
		}
	}
}
