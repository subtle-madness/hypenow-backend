package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.MatchedTagRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 구 해시태그 전용 목록의 <b>리라우팅</b> 조립(2026-08-27 해시태그 직접 수집 설계 §3) —
 * {@code GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts}의 응답 셰이프
 * ({@link BrandHashtagPostResponse})는 그대로 두고, 데이터 산지만 구 감지 테이블
 * ({@code brand_hashtag_post})에서 <b>통합 풀</b>로 옮긴다. FE가 새 통합 목록으로 전환하기 전에도
 * 화면이 낡지 않게 하는 전환기 장치이고, <b>다음 릴리스에 이 클래스와 엔드포인트를 함께 제거</b>한다.
 *
 * <p>구현은 {@link BrandPostAssembler#assembleBrandPosts} 결과의 {@code source=hashtag} 부분집합을
 * 구 셰이프로 옮기는 것뿐이다 — 사용자 격리·정산 게이트·정렬을 사본으로 다시 구현하지 않으므로
 * 본 목록과 이 탭이 갈릴 수 없다(구 구조는 그 판정을 각자 갖고 있어 실제로 갈렸다).
 *
 * <p><b>구 규칙과의 차이(의도됨)</b>:
 * <ul>
 *   <li>tagged·direct 성분이 붙은 겹침 행은 이 탭에서 빠진다 — 그런 행은 이제 본 목록에 제대로
 *       실린다(구 규칙도 tagged 겹침은 제외였다).</li>
 *   <li>{@code brandPostId}는 항상 shortcode다 — 해시태그 게시물이 전부 성과 측정 풀 소속이 됐다.</li>
 *   <li>{@code likes}·{@code comments}는 최신 스냅샷 값이다(구 "발견 시점 관측값"보다 신선하다).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandHashtagPostAssembler {

	/**
	 * 서빙 상한 — 본 목록의 {@code POST_LIMIT}과 같은 값. 편입 상한(브랜드당 1,000)이 이미 모수를
	 * 제한하지만, 폭주 방어 상한은 표시 표면마다 두는 것이 이 저장소의 관용구다.
	 */
	static final int HASHTAG_POST_LIMIT = 2000;

	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";

	private final BrandPostAssembler brandPostAssembler;
	private final BrandReadRepository brandReadRepository;

	public BrandHashtagPostAssembler(BrandPostAssembler brandPostAssembler,
			BrandReadRepository brandReadRepository) {
		this.brandPostAssembler = brandPostAssembler;
		this.brandReadRepository = brandReadRepository;
	}

	/**
	 * 브랜드 1계정의 해시태그 게시물(구 셰이프) — 최신순, 본 목록과 <b>같은</b> 격리·정산·창 판정을
	 * 거친 결과다.
	 *
	 * @param windowStart 조회자의 링크 표시 창 하한(본 목록과 같은 컷) — 두 화면의 모수가 어긋나면
	 *                    "탭에는 있는데 목록에는 없는" 게시물이 생긴다.
	 */
	public List<BrandHashtagPostResponse> assembleForBrand(long userId, BrandAccountRow account,
			String viewerAccountType, LocalDate windowStart) {
		List<BrandPostResponse> hashtagPosts = brandPostAssembler
				.assembleBrandPosts(userId, account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY,
						false, viewerAccountType)
				.stream()
				.filter(p -> BrandPostAssembler.SOURCE_HASHTAG.equals(p.source()))
				.filter(p -> withinWindow(p, windowStart))
				.limit(HASHTAG_POST_LIMIT)
				.toList();
		if (hashtagPosts.isEmpty()) {
			return List.of();
		}
		Set<String> codes = hashtagPosts.stream().map(BrandPostResponse::shortcode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		// 매칭 태그가 여럿이면 "#태그로 발견" 배지에 하나만 실린다 — 구 matched_tag(단일 컬럼)와 같은
		// 계약이라 첫 값을 쓴다. 이 필드는 엔드포인트와 함께 다음 릴리스에 사라진다.
		Map<String, String> matchedTagByCode = brandReadRepository.findMatchedTags(account.id(), codes).stream()
				.collect(Collectors.toMap(MatchedTagRow::shortCode, MatchedTagRow::tag, (a, b) -> a));
		return hashtagPosts.stream()
				.map(p -> toResponse(p, matchedTagByCode.get(p.shortcode())))
				.toList();
	}

	/** 업로드일 기준 창 판정 — 본 목록의 {@code withinUploadWindow}와 같은 규칙(업로드일 미상은 제외). */
	private static boolean withinWindow(BrandPostResponse post, LocalDate windowStart) {
		LocalDate uploadedOn = BrandPostAssembler.uploadedOn(post);
		return uploadedOn != null && !uploadedOn.isBefore(windowStart);
	}

	/**
	 * 통합 풀 응답 → 구 슬림 셰이프. {@code postUrl}은 콘텐츠 타입과 무관하게 항상 {@code /p/}다
	 * (Instagram이 reels도 {@code /p/}를 {@code /reel/}로 리다이렉트한다 — 구 계약 유지).
	 */
	static BrandHashtagPostResponse toResponse(BrandPostResponse post, String matchedTag) {
		TrackingItemResponse.SnapshotResponse latest = post.latestSnapshot();
		return new BrandHashtagPostResponse(
				post.shortcode(),
				PROFILE_URL_PREFIX + "p/" + post.shortcode() + "/",
				matchedTag,
				post.takenAt(),
				post.caption(),
				post.contentType(),
				post.thumbnailUrl(),
				post.authorUsername(),
				post.authorFullName(),
				post.authorProfilePicUrl(),
				post.authorProfileUrl(),
				latest == null ? null : latest.likes(),
				latest == null ? null : latest.comments(),
				post.sponsorship(),
				post.createdAt(),
				// 해시태그 게시물은 이제 전부 성과 측정 풀 소속이다 — 배지는 항상 켜진다.
				post.shortcode());
	}
}
