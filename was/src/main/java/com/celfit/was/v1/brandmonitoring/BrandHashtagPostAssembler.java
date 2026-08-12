package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 해시태그 발견 게시물 전용 조립(스펙 §8, 별도 탭 결정 2026-08-12) — {@code brand_hashtag_post}
 * (RELEVANT만)를 {@link BrandHashtagPostResponse} 슬림 셰이프로 옮긴다.
 *
 * <p>{@link BrandPostAssembler}(tagged·direct)와는 완전히 분리된 표면이다 — 병합·필터·정렬·
 * counts를 공유하지 않는다(2026-08-12 결정: 처음엔 §6-1 목록에 {@code source: "hashtag"}로
 * 합류시켰으나, 스냅샷·댓글·팔로워 보강이 없는 별개 성격의 데이터를 같은 계약에 끼워 맞추면
 * null 필드만 늘어난다는 FE 판단으로 전용 API로 분리했다). 컷 정책({@link BrandPostAssembler#windowCutoff})만
 * 그대로 재사용한다 — 같은 브랜드 화면 시간창 기준을 유지하기 위해서다.
 *
 * <p>조립 규칙({@link #toResponse})은 순수 정적 함수라 DB 없이 단위 테스트한다. 인스턴스 메서드는
 * 배치 조회 배선만 담당한다(사실 배치랄 것도 없다 — 보강이 없어 단일 SQL 왕복으로 끝난다).
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandHashtagPostAssembler {

	/**
	 * 해시태그 발견분 서빙 상한 — SQL 단계에서 자른다(tagged와 달리 등록 시점 검증이 없어 폭주
	 * 가능성이 더 크다, 스펙 §5). 브랜드 게시물 목록의 폭주 방어 상한(POST_LIMIT)과 같은 값.
	 */
	static final int HASHTAG_POST_LIMIT = 2000;

	private static final String CONTENT_TYPE_REELS = "REELS";
	private static final String REELS = "reels";
	private static final String FEED = "feed";
	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";

	private final BrandReadRepository brandReadRepository;

	public BrandHashtagPostAssembler(BrandReadRepository brandReadRepository) {
		this.brandReadRepository = brandReadRepository;
	}

	/** 브랜드 1계정의 해시태그 발견 게시물 전량(RELEVANT만) — 최신순, 병합·필터 없이 그대로. */
	public List<BrandHashtagPostResponse> assembleForBrand(long brandId) {
		List<BrandHashtagPostRow> rows = brandReadRepository.findHashtagPosts(brandId,
				BrandPostAssembler.windowCutoff(), HASHTAG_POST_LIMIT);
		return rows.stream().map(BrandHashtagPostAssembler::toResponse).toList();
	}

	/**
	 * 해시태그 발견 1건 조립 — 열거 시점 관측값을 그대로 옮긴다(보강 없음, 스펙 §5 보류).
	 * 협찬 판정은 유료협찬 관측 자체가 없어(열거 응답에 그 필드가 없다) 캡션 키워드만으로 한다.
	 */
	static BrandHashtagPostResponse toResponse(BrandHashtagPostRow row) {
		String contentType = contentTypeOf(row.contentType());
		return new BrandHashtagPostResponse(
				row.shortCode(),
				// 콘텐츠 타입과 무관하게 /p/ 고정 — Instagram이 reels도 /p/를 /reel/로 리다이렉트한다.
				PROFILE_URL_PREFIX + "p/" + row.shortCode() + "/",
				row.matchedTag(),
				KstTimestamps.toKstIso(row.takenAt()),
				row.caption(),
				contentType,
				sanitizeImageUrl(row.thumbnailUrl()),
				row.authorUsername(),
				row.authorFullName(),
				sanitizeImageUrl(row.authorProfilePicUrl()),
				row.authorUsername() == null ? null : PROFILE_URL_PREFIX + row.authorUsername() + "/",
				row.likes(),
				row.comments(),
				BrandSponsorshipClassifier.classify(null, row.caption()),
				KstTimestamps.toKstIso(row.firstSeenAt()));
	}

	private static String contentTypeOf(String raw) {
		return CONTENT_TYPE_REELS.equalsIgnoreCase(raw) ? REELS : FEED;
	}

	/** 저장 측이 걸러도 이미 박힌 값이 있을 수 있어 서빙에서 한 번 더 방어한다(BrandPostAssembler 동형). */
	private static String sanitizeImageUrl(String url) {
		if (url == null) {
			return null;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://") ? url : null;
	}
}
