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
 * <p><b>롤링 감시 세트(2026-09-02 감시 세트 2,000 설계 §2 — 구 편입 하드스톱 폐기)</b>: hashtag 성분
 * 행이 브랜드당 {@code postLimit}(기본 2,000)에 닿아도 <b>그 브랜드의 해시태그 열거를 중단하지
 * 않는다</b> — 세트는 "게시일 최신 postLimit개"를 유지하는 롤링 창이라, 예산이 바닥나도 각 태그의
 * 신규 유입 중 세트 바닥({@link TaggedPostRepository#nthNewestHashtagTakenAt})보다 최신인 것은
 * 계속 편입해야 한다(바닥보다 오래된 행은 다음 2단계 재수집에서 자연히 밀려난다 — 설계 §3).
 * 대신 <b>낭비 가드</b>가 태그별로 더 내려가도 소득이 없는 지점(예산 0 + 페이지 전체가 바닥
 * 이하 + 편입 0)에서 그 태그의 열거만 끊는다 — {@link #sweepTag} 참조. 이미 풀에 있는 행에
 * hashtag 성분만 얹는 <b>겹침 병기는 상한 밖</b>이다(행이 늘지 않는다 — 설계 §2-3). tagged의 2,000
 * 상한과는 별도 카운터다.
 *
 * <p><b>태그 간 우선순위(백필 예산)</b>: 예산은 태그 목록({@link BrandHashtagRepository#findTags}, 등록순
 * created_at·tag)을 순서대로 태우므로, "최신 우선"은 태그 <b>하나</b>의 recent 스트림 안에서만
 * 성립한다 — 여러 태그 사이에서는 먼저 등록된 태그가 예산을 먼저 소진하고, 뒤 태그는 롤링 편입
 * (세트 바닥보다 최신)만으로 신규를 채운다.
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
		/** 남은 신규 편입 여유(백필 예산 — 태그 간 공유). */
		int budget;
		/** 감시 세트 바닥(2026-09-02 설계 §2) — null이면 세트 미포화(예산으로만 판정). 스윕 시작
		 * 시점 스냅샷이라 이번 실행의 편입이 바닥을 밀어올리는 효과는 다음 스윕부터다 — 그 사이의
		 * 초과 편입은 한 스윕치 유입으로 유계라 수용한다. */
		final Instant floor;

		SweepState(Set<String> known, Set<String> hashtagKnown, int budget, Instant floor) {
			this.known = known;
			this.hashtagKnown = hashtagKnown;
			this.budget = budget;
			this.floor = floor;
		}

		/** 롤링 편입 판정 — 바닥이 있고 그보다 최신이면 예산 없이도 편입(설계 §2). */
		boolean admitsByFloor(PostInfo p) {
			return floor != null && p.takenAt() != null
					&& Instant.ofEpochSecond(p.takenAt()).isAfter(floor);
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
		int budget = postLimit <= 0 ? Integer.MAX_VALUE : Math.max(0, postLimit - hashtagKnown.size());
		Instant floor = postLimit <= 0 ? null
				: taggedPosts.nthNewestHashtagTakenAt(brand.id(), postLimit).orElse(null);
		SweepState state = new SweepState(new HashSet<>(taggedPosts.knownCodes(brand.id())),
				hashtagKnown, budget, floor);
		int savedTotal = 0;
		for (String tag : tagList) {
			// 롤링 편입(설계 §2 — 구 하드스톱 폐기): 예산이 없어도 각 태그의 최신 유입은 편입해야
			// 하므로 여기서 잔여 태그 열거를 끊지 않는다. 대신 sweepTag 내부의 낭비 가드가 태그별로
			// 더 내려가도 소득이 없는 지점에서 그 태그의 열거만 끊는다.
			// 태그별 실행 상태 기록(FE 요청, 2026-08-31) — collecting/done/failed 폴링 계약의 원재료.
			// 시작은 sweepTag 진입 직전, 종료는 성공·실패 양쪽 다 기록한다(status는 저장 안 하고
			// BrandHashtagRunStateResolver가 조회 시점에 계산 — 클래스 javadoc 참조).
			tags.markRunStarted(brand.id(), tag);
			// 태그 단위 격리(교환비): tags.findTags는 등록순(created_at, tag) 고정 순서다 — 여기서
			// 한 태그의 실패(Hiker 5xx·타임아웃·파싱 이상)를 안 막으면 그 태그가 매 야간 스윕마다
			// 뒤 태그 전부를 영구 굶긴다. BrandDirectCollectService.collectOne과 같은 이유의 격리이고,
			// 미처리분은 다음 스윕이 같은 순서로 재시도한다(별도 백스톱 불필요 — 열거 자체가 멱등).
			try {
				// deep=false — Task 4가 이 파라미터로 심층 재열거를 배선한다(이 태스크에선 항상 false).
				int created = sweepTag(brand, tag, cutoff, now, state, false);
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
	 * 하나라도 있으면 그 페이지의 신규만 처리하고 중단한다(단 {@code deep}이면 이 조기 종료를
	 * 건너뛴다 — Task 4의 심층 재열거 배선). 빈 페이지·커서 null도 자연 종료.
	 *
	 * @return 이번 태그가 만든 <b>신규 행</b> 수(겹침 병기는 세지 않는다 — 상한 밖)
	 */
	private int sweepTag(BrandRow brand, String tag, Instant cutoff, Instant now, SweepState state, boolean deep) {
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
			// 롤링 편입(설계 §2 — 구 하드스톱 폐기) — 선별 즉시 예산을 차감한다(스트림 limit보다
			// 반 발 이른 지점). 예산이 남아 있으면 백필로 편입하고, 소진됐으면 세트 바닥보다
			// 최신인 것만 예산 없이 편입한다(롤링 편입). 둘 다 아니면 스킵(다음 스윕으로 미룸).
			List<PostInfo> brandNew = new ArrayList<>();
			for (PostInfo p : fresh) {
				if (state.known.contains(p.shortCode())) {
					continue;
				}
				if (state.budget > 0) {
					state.budget--;          // 백필 용량 소모(선별 즉시 차감 — 구 페이지 말미 차감의 강화판)
					brandNew.add(p);
				} else if (state.admitsByFloor(p)) {
					brandNew.add(p);         // 롤링 편입 — 세트 바닥 위는 예산 없이 편입(하드스톱 폐기)
				}
			}
			List<PostInfo> toCollect = new ArrayList<>(overlap);
			toCollect.addAll(brandNew);
			collectPage(brand, tag, toCollect, now);
			created += brandNew.size();
			for (PostInfo p : toCollect) {
				state.insertedThisRun.add(p.shortCode());
				state.known.add(p.shortCode());
			}
			if (!alreadyHashtag.isEmpty()) {
				taggedPosts.recordMatchedTags(brand.id(), alreadyHashtag, tag);
			}
			// 낭비 가드(설계 §2) — 예산 0에서 이 페이지가 아무것도 편입 못 했고 fresh 전원이 바닥
			// 이하면, 더 내려가도 편입 가능성이 없다(비단조 스트림이라 "전부"일 때만 끊는다).
			if (state.budget <= 0 && brandNew.isEmpty() && !fresh.isEmpty()
					&& fresh.stream().noneMatch(state::admitsByFloor)) {
				break;
			}
			// 종료 트리거는 "이전부터 있던" 코드에만 반응한다(위 클래스 주석 — 크로스 태그 깊이 보존).
			if (!deep && alreadyHashtag.stream().anyMatch(state.hashtagKnown::contains)) {
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
