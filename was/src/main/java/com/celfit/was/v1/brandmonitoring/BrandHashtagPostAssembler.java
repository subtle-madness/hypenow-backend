package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPoolStatusRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 해시태그 발견 게시물 전용 조립(스펙 §8, 별도 탭 결정 2026-08-12) — {@code brand_hashtag_post}
 * (RELEVANT만)를 {@link BrandHashtagPostResponse} 슬림 셰이프로 옮긴다.
 *
 * <p>{@link BrandPostAssembler}(브랜드 풀)와는 완전히 분리된 표면이다 — 병합·필터·정렬·
 * counts를 공유하지 않는다(2026-08-12 결정: 처음엔 §6-1 목록에 {@code source: "hashtag"}로
 * 합류시켰으나, 스냅샷·댓글·팔로워 보강이 없는 별개 성격의 데이터를 같은 계약에 끼워 맞추면
 * null 필드만 늘어난다는 FE 판단으로 전용 API로 분리했다). 컷 정책({@link BrandPostAssembler#windowCutoff})만
 * 그대로 재사용한다 — 같은 브랜드 화면 시간창 기준을 유지하기 위해서다.
 *
 * <p>조립 규칙({@link #toResponse})은 순수 정적 함수라 DB 없이 단위 테스트한다. 인스턴스 메서드는
 * 배치 조회 배선만 담당한다 — 발견 게시물 자체는 보강이 없어 단일 SQL 왕복이지만, brandPostId
 * 판정(2026-08-17)에 브랜드 풀 상태만 보는 경량 조회 1종이 더해졌다.
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

	/**
	 * 브랜드 1계정의 해시태그 발견 게시물(RELEVANT만) — 최신순, 병합·필터·정렬 없이 전량이되,
	 * <b>tagged 겹침 행은 제외</b>한다(2026-08-18 정정, direct 통합 §2-5로 판정 산지만 이동 — 규칙의
	 * 의미는 불변). 이 화면은 "태그 안 된 게시물"인데 이미 tagged로 측정 중인 shortcode가 뜨는 건
	 * 화면 정의와 모순이다 — 사진 태그(캡션에 안 보임)와 해시태그를 동시에 단 게시물이 실제로 이
	 * 겹침을 만든다(캡션 멘션 자체는 수집 시점 DIRECT_TAGGED 판정으로 이미 걸러진다). 단,
	 * <b>direct 매핑이 살아 있는 행은 tagged 여부와 무관하게 유지</b>한다(승격분 dim 잔존 계약 —
	 * direct가 우선).
	 *
	 * <p>제외 문자열 기능은 전면 폐기됐다(2026-08-17 협의 확정 — "감지는 감지 해시태그만으로
	 * 수행하고 제외는 적용하지 않는다") — monitoring 관리 API 5종·was 프록시 API 4종이 모두
	 * 제거됐고 조회 시점 필터도 함께 없앤다. 남겨두면 과거 시드(브랜드 계정명 루트 등)가 유저가
	 * 조회·삭제할 수 없는 "유령 필터"로 영구 동작해 계정명을 포함한 정상 작성자(예: 스태프
	 * 부계정)의 발견 게시물이 이유 없이 숨는다. verdict(SELF 등) 기반 필터링은 수집 시점 판정이라
	 * 이 변경과 별개다.
	 *
	 * <p>{@code brandPostId}(2026-08-17 승격 상태 필드)는 direct 등록에만 채워진다 — tagged 겹침
	 * 행은 이미 목록에서 빠지므로 tagged로만 채워지는 경로가 없다. <b>2026-08-18 direct 통합 이후
	 * 판정 산지가 바뀌었다</b>: {@code app.brand_direct_posts}(유저 스코프 매핑) + tagged 존재 판정
	 * 2회 조회 대신, monitoring 브랜드 풀 상태({@code BrandReadRepository.findBrandPoolStatus})
	 * 1회 조회로 both를 판정한다(제외: {@code tag_detected AND NOT direct_registered}; brandPostId:
	 * {@code direct_registered ? shortCode : null}) — 브랜드 풀이 direct 등록을 유저 스코프가 아니라
	 * 브랜드 스코프로 승격했으므로(설계 §1-1) 판정도 자연히 브랜드 스코프가 된다. 그래서 이 메서드는
	 * 더 이상 userId를 받지 않는다 — 같은 브랜드에 연결된 누구에게나 같은 발견 목록이 보인다.
	 */
	public List<BrandHashtagPostResponse> assembleForBrand(long brandId) {
		List<BrandHashtagPostRow> rows = brandReadRepository.findHashtagPosts(brandId,
				BrandPostAssembler.windowCutoff(), HASHTAG_POST_LIMIT);
		if (rows.isEmpty()) {
			return List.of();
		}

		Set<String> shortCodes = rows.stream().map(BrandHashtagPostRow::shortCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, BrandPoolStatusRow> poolStatus = brandReadRepository.findBrandPoolStatus(brandId, shortCodes)
				.stream().collect(Collectors.toMap(BrandPoolStatusRow::shortCode, Function.identity(), (a, b) -> a));

		return rows.stream()
				.filter(row -> !isTaggedOnly(poolStatus.get(row.shortCode())))
				.map(row -> toResponse(row, brandPostIdOf(poolStatus.get(row.shortCode()), row.shortCode())))
				.toList();
	}

	/** 제외 조건 — tag_detected AND NOT direct_registered(2026-08-18 direct 통합 §2-5). */
	private static boolean isTaggedOnly(BrandPoolStatusRow status) {
		return status != null && status.tagDetected() && !status.directRegistered();
	}

	/** direct 등록이면 shortcode를, 아니면 null을 돌려준다(tagged-only 행은 이미 제외됐다). */
	private static String brandPostIdOf(BrandPoolStatusRow status, String shortCode) {
		return status != null && status.directRegistered() ? shortCode : null;
	}

	/** {@code brandPostId} 없는 호출부(단위 테스트 등)를 위한 편의 오버로드 — null로 접는다. */
	static BrandHashtagPostResponse toResponse(BrandHashtagPostRow row) {
		return toResponse(row, null);
	}

	/**
	 * 해시태그 발견 1건 조립 — 열거 시점 관측값을 그대로 옮긴다(보강 없음, 스펙 §5 보류).
	 * 협찬 판정은 유료협찬 관측 자체가 없어(열거 응답에 그 필드가 없다) 캡션 키워드만으로 한다.
	 */
	static BrandHashtagPostResponse toResponse(BrandHashtagPostRow row, String brandPostId) {
		String contentType = contentTypeOf(row.contentType());
		return new BrandHashtagPostResponse(
				row.shortCode(),
				// 콘텐츠 타입과 무관하게 /p/ 고정 — Instagram이 reels도 /p/를 /reel/로 리다이렉트한다.
				PROFILE_URL_PREFIX + "p/" + row.shortCode() + "/",
				row.matchedTag(),
				KstTimestamps.toKstIso(row.takenAt()),
				row.caption(),
				contentType,
				// 아카이브 사본(/img/ Vercel rewrite) 우선, 미아카이브는 원본 CDN URL 폴백 —
				// 원본은 인스타 서명 URL이라 며칠~2주면 만료된다(BrandPostAssembler.resolveImageUrl 동형).
				BrandPostAssembler.resolveImageUrl(row.imageObjectPath(), row.thumbnailUrl()),
				row.authorUsername(),
				row.authorFullName(),
				// 게시자 프로필 사진도 이제 아카이브 사본 우선 서빙(2026-08-17 작성자 이미지 아카이브 잡 신설).
				BrandPostAssembler.resolveImageUrl(row.authorImageObjectPath(), row.authorProfilePicUrl()),
				row.authorUsername() == null ? null : PROFILE_URL_PREFIX + row.authorUsername() + "/",
				row.likes(),
				row.comments(),
				BrandSponsorshipClassifier.classify(null, row.caption()),
				KstTimestamps.toKstIso(row.firstSeenAt()),
				brandPostId);
	}

	private static String contentTypeOf(String raw) {
		return CONTENT_TYPE_REELS.equalsIgnoreCase(raw) ? REELS : FEED;
	}
}
