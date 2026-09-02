package com.celfit.instagram.source;

import com.celfit.instagram.source.self.SelfCrawlException;
import com.celfit.instagram.source.self.SelfErrorClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수집 정책 계층 — 자체크롤 1순위 + Hiker 폴백 + 에러 taxonomy 라우팅(스펙 §4·§8). selfEnabledForPath는
 * 경로별(path) 판정을 매 콜 재확인한다(app_setting 런타임 토글·킬스위치·부분 개통이 재시작 없이
 * 반영 — 운영 점진 개통 시 "프로필만 빼고 켜기" 같은 표면 단위 제어를 지원한다). 결과는
 * InstagramSourceMetrics로 관측한다. path에 대해 false를 주면 그 경로만 전량 Hiker(행동 변화 0).
 */
public class FailoverInstagramSource implements InstagramSource {

	private static final Logger log = LoggerFactory.getLogger(FailoverInstagramSource.class);

	private final InstagramSource self;
	private final InstagramSource hiker;
	private final Predicate<String> selfEnabledForPath;
	private final InstagramSourceMetrics metrics;

	/** 마일스톤 A 호환 — 자체 없이 Hiker 단독. */
	public FailoverInstagramSource(InstagramSource hiker) {
		this(null, hiker, path -> false, InstagramSourceMetrics.NOOP);
	}

	/** 전역 토글 호환 — path와 무관하게 동일 판정(경로별 제어가 필요 없는 호출부·기존 테스트용). */
	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker, BooleanSupplier selfEnabled) {
		this(self, hiker, path -> selfEnabled.getAsBoolean(), InstagramSourceMetrics.NOOP);
	}

	/** 전역 토글 호환 — path와 무관하게 동일 판정(경로별 제어가 필요 없는 호출부·기존 테스트용). */
	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker, BooleanSupplier selfEnabled,
			InstagramSourceMetrics metrics) {
		this(self, hiker, path -> selfEnabled.getAsBoolean(), metrics);
	}

	/** 경로별(표면별) 자체크롤 토글 — path(=route()가 넘기는 논리 경로명, metric path 태그와 동일)별로
	 * 판정한다. 운영 점진 개통 수단(예: IgSourceSettings::selfEnabledForPath). */
	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker,
			Predicate<String> selfEnabledForPath) {
		this(self, hiker, selfEnabledForPath, InstagramSourceMetrics.NOOP);
	}

	/** 경로별(표면별) 자체크롤 토글 + 관측 훅. */
	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker,
			Predicate<String> selfEnabledForPath, InstagramSourceMetrics metrics) {
		this.self = self;
		this.hiker = hiker;
		this.selfEnabledForPath = selfEnabledForPath;
		this.metrics = metrics;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return route("fetchProfile", () -> self.fetchProfile(username), () -> hiker.fetchProfile(username));
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return route("fetchAuthorProfile", () -> self.fetchAuthorProfile(userId),
				() -> hiker.fetchAuthorProfile(userId));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return route("fetchRecentPosts", () -> self.fetchRecentPosts(username, userId, pages),
				() -> hiker.fetchRecentPosts(username, userId, pages));
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return route("fetchClipCounts", () -> self.fetchClipCounts(userId, pages),
				() -> hiker.fetchClipCounts(userId, pages));
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return route("fetchTaggedPage", () -> self.fetchTaggedPage(userId, pageId),
				() -> hiker.fetchTaggedPage(userId, pageId));
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return route("fetchHashtagRecentPage", () -> self.fetchHashtagRecentPage(tag, pageId),
				() -> hiker.fetchHashtagRecentPage(tag, pageId));
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return route("fetchPost", () -> self.fetchPost(shortCode), () -> hiker.fetchPost(shortCode));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return route("fetchComments", () -> self.fetchComments(shortCode, postUsername, pages),
				() -> hiker.fetchComments(shortCode, postUsername, pages));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return route("fetchComments", () -> self.fetchComments(shortCode, postUsername, pages, knownCommentIds),
				() -> hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds));
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return route("resolveMediaByUrl", () -> self.resolveMediaByUrl(url), () -> hiker.resolveMediaByUrl(url));
	}

	/**
	 * 자체 1순위 → 실패 시 Hiker(매 콜 selfEnabled 재확인). NOT_FOUND는 부재로 종료(폴백 안 함,
	 * SubjectNotFoundException 변환), 그 외 자체 실패·미지원은 Hiker 폴백. 성공/폴백 결과를 관측한다
	 * (Hiker 자체 예외는 그대로 전파, 미기록).
	 */
	private <T> T route(String path, Supplier<T> selfCall, Supplier<T> hikerCall) {
		if (self == null || !selfEnabledForPath.test(path)) {
			T r = hikerCall.get();
			metrics.record(path, "hiker", "ok");
			return r;
		}
		try {
			T r = selfCall.get();
			// 댓글 미완주(CommentsFetch.complete=false)는 라우팅 자체는 성공이라 폴백하지 않지만,
			// 커버리지 저하가 "ok"에 묻히면 관측이 안 되므로 별도 outcome으로 구분한다(F9, 서킷·폴백
			// 동작은 그대로 — 관측만 바뀐다).
			String outcome = (r instanceof CommentsFetch cf && !cf.complete()) ? "partial" : "ok";
			metrics.record(path, "self", outcome);
			return r;
		} catch (UnsupportedOperationException e) {
			T r = hikerCall.get();
			metrics.record(path, "hiker", "hardgate");
			return r;
		} catch (SelfCrawlException e) {
			if (e.errorClass() == SelfErrorClass.NOT_FOUND) {
				metrics.record(path, "self", "notfound");
				throw new SubjectNotFoundException(e.getMessage());
			}
			T r = hikerCall.get();
			metrics.record(path, "hiker", "fallback:" + e.errorClass());
			return r;
		} catch (RuntimeException e) {
			// SelfCrawlException·UnsupportedOperationException으로 분류되지 못하고 self 호출에서
			// 그대로 샌 예외(예: 미처 못 잡은 설정 오류) — 분류 불가라 서킷 계상은 하지 않지만,
			// 폴백망 누수(F2)는 막아야 하므로 Hiker로는 태운다. 원인 추적용으로 태그를 구분한다.
			log.warn("자체크롤 {} 예상 못한 런타임 예외 — hiker로 폴백: {}", path, e.getMessage(), e);
			T r = hikerCall.get();
			metrics.record(path, "hiker", "fallback:UNEXPECTED");
			return r;
		}
	}
}
