package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 <b>수집</b>(2026-08-27 해시태그 직접 수집 설계 §2) — 태그별 recent 열거로 찾은
 * 게시물을 tagged/direct와 같은 풀({@code brand_tagged_post})에 직접 편입한다.
 *
 * <p><b>구 "감지" 구조는 폐기됐다.</b> 예전에는 별도 테이블({@code brand_hashtag_post})에 발견 시점
 * 관측값을 1회 저장하고 LLM 관련성 판정(SELF·DIRECT_TAGGED·MENTION·LLM)으로 노출을 걸렀을 뿐,
 * 스냅샷·댓글·게시자 보강도 주기 재수집도 없었다. 이제 편입 게이트는 <b>규칙 하나</b>(게시자
 * username이 브랜드 계정명과 정확히 일치하면 제외)뿐이고, 편입된 게시물은
 * {@link BrandCollectService#enrich}를 그대로 타 tagged와 동일한 보강·정산·재수집을 받는다.
 * 동명이인·무관 게시물 노이즈는 제품 결정(전부 편입)에 따라 수용한다 — 재도입이 필요하면 노출
 * 단계 필터로 돌아온다(매칭 태그·게시자 메타는 보존된다).
 *
 * <p><b>열거 종료</b>: recent 스트림은 IG 랭킹 혼합이라 taken_at이 단조가 아니다 — 기간으로는 종료를
 * 판정할 수 없어(창 밖 게시물이 중간에 섞인다) 기간은 <b>사후 필터</b>로만 쓰고, 종료는 "이전 스윕부터
 * 있던 게시물 도달"(dedup)로 판정한다. dedup 기준 집합이 "풀에 있는 코드"가 아니라 <b>"hashtag 성분이
 * 있는 코드"</b>인 것이 중요하다: 전자로 하면 tagged 열거가 이미 확보한 게시물이 전부 종료 신호가 돼
 * 스트림 깊은 곳의 hashtag-only 게시물에 영영 못 간다.
 *
 * <p><b>크로스 태그 종료 분리</b>(구 구조에서 이어받은 규칙): 태그 A가 이번 실행에서 편입한 코드가
 * 태그 B의 스트림에 실려도 B의 종료 신호로 보지 않는다 — 안 그러면 B의 열거 깊이가 태그 순서에
 * 좌우된다. {@code insertedThisRun}이 그 구분을 들고 있다.
 *
 * <p><b>편입 상한</b>: hashtag 성분 행이 브랜드당 {@code postLimit}(기본 1,000)에 닿으면 <b>그 브랜드의
 * 해시태그 열거 자체를 중단</b>한다(콜 예산 보호 — {@link #doSweep}이 예산 소진 후의 태그는 아예
 * 열거하지 않는다). 이미 풀에 있는 행에 hashtag 성분만 얹는 <b>겹침 병기는 상한 밖</b>이다(행이
 * 늘지 않는다 — 설계 §2-3) — 단 이 면제는 <b>태그가 실제로 열거되는 동안만</b> 유효하다: 상한
 * 도달로 어느 태그가 아예 열거되지 않으면 그 태그의 겹침도 병기되지 않는다. tagged의 2,000
 * 상한과는 별도 카운터다.
 *
 * <p><b>태그 간 우선순위</b>: 예산은 태그 목록({@link BrandHashtagRepository#findTags}, 등록순
 * created_at·tag)을 순서대로 태우므로, "최신 우선"은 태그 <b>하나</b>의 recent 스트림 안에서만
 * 성립한다 — 여러 태그 사이에서는 먼저 등록된 태그가 예산을 먼저 소진하고, 뒤 태그는 남은 예산이
 * 없으면 이번 스윕에서 아예 열거되지 않는다.
 *
 * <p>plain class + {@code BrandHashtagConfig}에서 배선(구 구조에서 이어받은 배치).
 */
public class BrandHashtagCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagCollectService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandHashtagRepository tags;
	private final TaggedPostRepository taggedPosts;
	private final BrandSnapshotWriter writer;
	private final BrandCollectService collect;
	private final int maxPages;
	private final int postLimit;

	public BrandHashtagCollectService(HikerClient hiker, BrandCallContext callContext,
			BrandHashtagRepository tags, TaggedPostRepository taggedPosts, BrandSnapshotWriter writer,
			BrandCollectService collect, int maxPages, int postLimit) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.tags = tags;
		this.taggedPosts = taggedPosts;
		this.writer = writer;
		this.collect = collect;
		this.maxPages = maxPages;
		this.postLimit = postLimit;
	}

	/** 스윕 1회분 상태 — 태그 루프가 공유한다(종료 판정·상한 잔량이 태그 간에 이어져야 한다). */
	private static final class SweepState {
		/** 브랜드 풀에 행이 있는 코드 — 겹침(병기, 상한 밖)과 신규(상한 적용)를 가른다. */
		final Set<String> known;
		/** hashtag 성분이 이미 있던 코드(스윕 시작 시점 스냅샷) — 조기 종료의 유일한 신호원. */
		final Set<String> hashtagKnown;
		/** 이번 실행에서 편입한 코드 — 종료 신호에서 제외(크로스 태그 깊이 보존). */
		final Set<String> insertedThisRun = new HashSet<>();
		/** 남은 신규 편입 여유. */
		int budget;

		SweepState(Set<String> known, Set<String> hashtagKnown, int budget) {
			this.known = known;
			this.hashtagKnown = hashtagKnown;
			this.budget = budget;
		}
	}

	/** 브랜드 1개분 해시태그 수집 — 태그가 없으면 콜 0으로 즉시 반환한다. */
	public void sweep(BrandRow brand) {
		// 콜 집계 스코프(어드민 크롤링 비용) — 열거·보강 콜 전부 이 브랜드 몫으로 계상된다.
		callContext.runScoped(brand.id(), () -> doSweep(brand));
	}

	private void doSweep(BrandRow brand) {
		List<String> tagList = tags.findTags(brand.id());
		if (tagList.isEmpty()) {
			return;
		}
		Instant now = Instant.now();
		Instant cutoff = BrandCollectService.collectionCutoff(brand, now);
		Set<String> hashtagKnown = taggedPosts.hashtagCodes(brand.id());
		SweepState state = new SweepState(new HashSet<>(taggedPosts.knownCodes(brand.id())), hashtagKnown,
				postLimit <= 0 ? Integer.MAX_VALUE : Math.max(0, postLimit - hashtagKnown.size()));
		int savedTotal = 0;
		for (String tag : tagList) {
			if (state.budget <= 0) {
				log.info("브랜드 해시태그 편입 상한({}) 도달 — {} 잔여 태그 열거 중단", postLimit, brand.username());
				break;
			}
			// 태그별 실행 상태 기록(FE 요청, 2026-08-31) — collecting/done/failed 폴링 계약의 원재료.
			// 시작은 sweepTag 진입 직전, 종료는 성공·실패 양쪽 다 기록한다(status는 저장 안 하고
			// BrandHashtagRunStateResolver가 조회 시점에 계산 — 클래스 javadoc 참조).
			tags.markRunStarted(brand.id(), tag);
			// 태그 단위 격리(교환비): tags.findTags는 등록순(created_at, tag) 고정 순서다 — 여기서
			// 한 태그의 실패(Hiker 5xx·타임아웃·파싱 이상)를 안 막으면 그 태그가 매 야간 스윕마다
			// 뒤 태그 전부를 영구 굶긴다. BrandDirectCollectService.collectOne과 같은 이유의 격리이고,
			// 미처리분은 다음 스윕이 같은 순서로 재시도한다(별도 백스톱 불필요 — 열거 자체가 멱등).
			try {
				int created = sweepTag(brand, tag, cutoff, now, state);
				savedTotal += created;
				tags.markRunFinished(brand.id(), tag, created, false);
			} catch (RuntimeException e) {
				log.warn("해시태그 태그 열거 실패(격리, 다음 태그 계속) — {} 태그 {}: {}",
						brand.username(), tag, e.toString());
				// found_count는 0으로 통일(위 markRunFinished javadoc) — Hiker 404는 여기 도달하지
				// 않는다(fetchHashtagRecentPage가 이미 빈 HashtagPage로 흡수해 정상 0건 종료).
				tags.markRunFinished(brand.id(), tag, 0, true);
			}
		}
		log.info("브랜드 해시태그 수집 완료 — {} 태그 {}개, 신규 편입 {}건, 잔여 편입 여유 {}건",
				brand.username(), tagList.size(), savedTotal, state.budget);
	}

	/**
	 * 태그 1개분 recent 열거 — maxPages까지 순회하되, 페이지에 "이전부터 있던" hashtag 성분 게시물이
	 * 하나라도 있으면 그 페이지의 신규만 처리하고 중단한다. 빈 페이지·커서 null도 자연 종료.
	 *
	 * @return 이번 태그가 만든 <b>신규 행</b> 수(겹침 병기는 세지 않는다 — 상한 밖)
	 */
	private int sweepTag(BrandRow brand, String tag, Instant cutoff, Instant now, SweepState state) {
		int created = 0;
		String cursor = null;
		for (int page = 0; page < maxPages; page++) {
			HikerClient.HashtagPage result = hiker.fetchHashtagRecentPage(tag, cursor);
			if (result.posts().isEmpty()) {
				break;
			}
			List<PostInfo> pagePosts = distinctByShortCode(result.posts().stream()
					.map(HikerClient.HashtagPost::post).toList());
			// 이 페이지에서 이미 hashtag 성분이 있는 코드 — 행이 있으니 매칭 태그는 남길 수 있다(FK 만족).
			Set<String> alreadyHashtag = new LinkedHashSet<>();
			for (PostInfo p : pagePosts) {
				if (state.hashtagKnown.contains(p.shortCode()) || state.insertedThisRun.contains(p.shortCode())) {
					alreadyHashtag.add(p.shortCode());
				}
			}
			List<PostInfo> fresh = pagePosts.stream()
					.filter(p -> eligible(brand, p, cutoff))
					.filter(p -> !alreadyHashtag.contains(p.shortCode()))
					.toList();
			List<PostInfo> overlap = fresh.stream().filter(p -> state.known.contains(p.shortCode())).toList();
			List<PostInfo> brandNew = fresh.stream().filter(p -> !state.known.contains(p.shortCode()))
					.limit(Math.max(0, state.budget))
					.toList();
			List<PostInfo> toCollect = new ArrayList<>(overlap);
			toCollect.addAll(brandNew);
			collectPage(brand, tag, toCollect, now);
			created += brandNew.size();
			// 페이지 단위 즉시 차감(2026-08-27 재리뷰 반영) — 태그 루프 전체를 돈 뒤 한 번에 빼면,
			// 도중 예외(격리된 태그 실패)로 이 태그가 여기서 끊길 때 이미 커밋된 페이지분이 예산에서
			// 안 빠져 다음 태그가 그만큼 초과 편입한다. 페이지마다 바로 빼면 어느 페이지에서 끊기든
			// state.budget이 그 순간까지의 실편입을 정확히 반영한다.
			state.budget -= brandNew.size();
			for (PostInfo p : toCollect) {
				state.insertedThisRun.add(p.shortCode());
				state.known.add(p.shortCode());
			}
			if (!alreadyHashtag.isEmpty()) {
				taggedPosts.recordMatchedTags(brand.id(), alreadyHashtag, tag);
			}
			// 종료 트리거는 "이전부터 있던" 코드에만 반응한다(위 클래스 주석 — 크로스 태그 깊이 보존).
			if (alreadyHashtag.stream().anyMatch(state.hashtagKnown::contains)) {
				break;
			}
			cursor = result.nextPageId();
			if (cursor == null) {
				break;
			}
		}
		return created;
	}

	/**
	 * 편입 자격 — 결손 필드·수집 창 밖·브랜드 본인 게시물을 거른다(설계 §2-2).
	 * 기간은 <b>사후 필터</b>다: recent 스트림이 taken_at 비단조라 열거 종료 판정에는 쓸 수 없다.
	 * 본인 제외는 게시자 username과 브랜드 계정명의 <b>정확 일치</b>(대소문자 무시)다 — "브랜드명을
	 * 포함한 스태프 부계정" 같은 근사 매치는 제외 대상이 아니다(구 SELF 규칙과 같은 정의).
	 */
	private static boolean eligible(BrandRow brand, PostInfo post, Instant cutoff) {
		if (post.takenAt() == null || post.username() == null || post.username().isBlank()) {
			return false;
		}
		if (Instant.ofEpochSecond(post.takenAt()).isBefore(cutoff)) {
			return false;
		}
		return !post.username().equalsIgnoreCase(brand.username());
	}

	/**
	 * 페이지분 편입 — 복권 지표 보정 → 스냅샷·메타 적재 → 통합 풀 링크(hashtag 성분) → 매칭 태그 →
	 * 마지막 수집 시각 → 보강. 전부 upsert/멱등이라 재실행 안전하다.
	 *
	 * <p>보강 실패는 격리한다({@code BrandCollectService.enrichSafely}와 같은 규칙) — 열거분은 이미
	 * Hiker 콜을 지불하고 얻은 결과물이라, 보강 실패로 그날 열거를 통째로 버리면 손해가 크다.
	 * 미보강분은 야간 스윕 2단계(미보강 우선 배치)가 백스톱한다.
	 */
	private void collectPage(BrandRow brand, String tag, List<PostInfo> posts, Instant now) {
		if (posts.isEmpty()) {
			return;
		}
		List<PostInfo> adjusted = collect.adjustLotteryMetrics(posts);
		LocalDate today = LocalDate.now(KST);
		for (PostInfo p : adjusted) {
			writer.savePost(today, p);
			taggedPosts.upsertHashtag(brand.id(), p, now);
			taggedPosts.recordMatchedTag(brand.id(), p.shortCode(), tag);
		}
		taggedPosts.touchCrawled(brand.id(), adjusted.stream().map(PostInfo::shortCode).toList(), now);
		try {
			collect.enrich(brand, adjusted);
		} catch (RuntimeException e) {
			log.warn("해시태그 보강 실패(격리, 열거 계속) — {} 다음 스윕이 백스톱: {}",
					brand.username(), e.toString());
		}
	}

	/** 페이지 내 동일 shortCode 중복 제거(첫 등장 유지) — code 결손 아이템은 여기서 통째로 버린다. */
	private static List<PostInfo> distinctByShortCode(List<PostInfo> posts) {
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		for (PostInfo p : posts) {
			if (p.shortCode() != null && !p.shortCode().isBlank()) {
				byCode.putIfAbsent(p.shortCode(), p);
			}
		}
		return new ArrayList<>(byCode.values());
	}
}
