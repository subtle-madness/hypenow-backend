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
 * <p>2026-09-02 노출 상한 폐지 설계 §4 — 신선도 통제가 수집 쪽 롤링 세트로 이동했다.
 * 구 {@code HASHTAG_POST_LIMIT = 2000} 컷도 폐지되고, 창 안 전량을 반환한다.
 * {@link BrandCollectionCap}과 함께 폐지.
 *
 * <p>구현은 {@link BrandPostAssembler#assembleBrandPosts} 결과의 {@code source=hashtag} 부분집합을
 * 구 셰이프로 옮기는 것뿐이다 — 사용자 격리·정산 게이트·정렬을 사본으로 다시 구현하지 않으므로
 * 본 목록과 이 탭이 갈릴 수 없다(구 구조는 그 판정을 각자 갖고 있어 실제로 갈렸다). 개수만 필요한
 * 호출({@link #countForBrand}, P2 — 2026-08-27 develop 도입)은 같은 이유로 전량 하이드레이트를
 * 태우지 않는 경량 인덱스 산지({@link BrandPostAssembler#indexForBrand})를 공유한다.
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
		// 노출 상한 폐지(2026-09-02 노출 상한 폐지 설계 §4) — 신선도 통제가 수집 쪽 롤링 세트로 옮겨가
		// 창 안 전량을 반환한다.
		List<BrandPostResponse> hashtagPosts = brandPostAssembler
				.assembleBrandPosts(userId, account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY,
						false, viewerAccountType)
				.stream()
				.filter(p -> BrandPostAssembler.SOURCE_HASHTAG.equals(p.source()))
				.filter(p -> withinWindow(p, windowStart))
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

	/**
	 * 해시태그 발견 게시물 개수만(P2, 2026-08-27 develop 도입 → <b>해시태그 직접 수집 전환 이후
	 * 재구현</b>) — FE 탭 뱃지처럼 목록 본문이 필요 없는 호출용이다(전량 하이드레이트·전송을 태우지
	 * 않는 슬림 경로). {@link #assembleForBrand}와 <b>같은 판정 산지</b>를 탄다: 노출 필터(등록자 전용
	 * 노출 + 해시태그 격리)는 {@link BrandPostAssembler#indexForBrand}가 이미 끝낸
	 * {@link BrandPostAssembler.PostRef} 목록 위에서 source=hashtag·링크 창만 걸러 센다 — 판정을
	 * 복제하면 뱃지 숫자와 목록 길이가 조용히 갈라진다.
	 *
	 * <p>인덱스는 스냅샷·표시 메타·게시자 배치 조회가 전혀 없는 경량 패스라(리포지토리 주석 참조),
	 * 목록 조회처럼 매번 풀 카드를 조립하지 않는다 — 배지(brandPostId) 파생도 셀 때는 쓸 데가 없어
	 * 등록자 원장을 따로 조회하지 않는다({@code indexForBrand}가 이미 노출 필터에 쓴 값을 재사용한다).
	 *
	 * @param windowStart {@link #assembleForBrand}와 같은 링크 표시 창 하한 — 어긋나면 뱃지 숫자와
	 *                    탭 목록 길이가 갈라진다.
	 */
	public long countForBrand(long userId, BrandAccountRow account, LocalDate windowStart) {
		BrandPostAssembler.BrandPostIndex index = brandPostAssembler.indexForBrand(userId, account, false);
		return index.refs().stream()
				.filter(ref -> BrandPostAssembler.SOURCE_HASHTAG.equals(ref.source()))
				.filter(ref -> BrandPostWindows.withinLinkWindow(ref, windowStart))
				.count();
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
				post.shortcode(),
				post.influencerId());
	}
}
