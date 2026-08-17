package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.llm.BrandMentionJudge;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandHashtagRepository.HashtagPostInsert;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 해시태그 스윕 본체(스펙 2026-08-11 §3·§4) — 태그별 recent 열거를 조기 종료 규칙으로
 * 순회하고, 신규 게시물을 규칙(SELF·DIRECT_TAGGED·MENTION) → LLM 판정 순서로 걸러 저장한다.
 * 기존 브랜드 태그 트래킹(user/tag/medias 경로, BrandCollectService)과는 완전히 분리된 경로 —
 * 저장 테이블도 다르고(brand_hashtag_post), 여기서 DIRECT_TAGGED로 접히는 건은 그쪽이 이미
 * 다루는 영역이라 기록만 하고 비노출로 둔다(중복 노출 방지).
 *
 * <p>조기 종료: 페이지에 이미 저장된 게시물(existingCodes)이 하나라도 있으면 그 페이지의 신규만
 * 처리하고 다음 페이지는 요청하지 않는다 — 해시태그 recent 스트림은 IG 랭킹 혼합이라 taken_at이
 * 단조가 아니므로, 게시물 단위가 아니라 페이지 단위로 판단한다(브랜드 태그 트래킹의 taken_at 컷
 * 조기 종료와 다른 규칙 — 여기는 dedup 도달로 종료).
 *
 * <p><b>크로스 태그·크로스 페이지 종료 분리(architect 리뷰 반영)</b>: {@code existingCodes}는
 * "이전 스윕이 저장한 분"과 "이번 스윕이 방금 저장한 분"을 구분하지 못한다 — 태그 A가 저장한
 * 코드가 태그 B의 recent 스트림에도 실리면(브랜드 관련 게시물은 여러 태그를 동시에 다는 경우가
 * 흔하다), 태그 B가 자기 페이지에서 그 코드를 existing으로 보고 첫 페이지에서 즉시 끊긴다 —
 * 태그 B의 백필 깊이 약속(§3, maxPages까지 전진)이 태그 순서에 좌우되는 버그였다. 그래서
 * {@link #sweep}이 스코프에 든 {@code insertedThisRun}에 이번 실행에서 저장한 코드를 누적하고,
 * <b>재처리 스킵 판정(freshPosts 필터)은 existing 그대로</b> 쓰되(dedup 의미는 안 바뀐다 — 이미
 * DB에 있는 코드는 어느 태그가 만났든 재판정하지 않는다), <b>페이지네이션 종료 트리거만
 * {@code existing - insertedThisRun}이 비어있지 않을 때</b>로 좁힌다 — "이번 실행에서 내가(또는
 * 다른 태그가) 방금 저장한 코드"는 종료 신호로 보지 않고, "이전부터 있던 코드"만 종료 신호로 본다.
 *
 * <p>plain class + {@code BrandHashtagConfig}에서 배선(BrandCollectService처럼 @Service를 쓰지
 * 않는 이유는 없지만, 태스크 계획이 설정 클래스 분리를 명시해 그대로 따른다).
 */
public class BrandHashtagCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagCollectService.class);

	private final HikerClient hiker;
	private final BrandCallContext callContext;
	private final BrandHashtagRepository repo;
	private final BrandMentionJudge judge;
	private final BrandRepository brands;
	private final int windowDays;
	private final int maxPages;

	public BrandHashtagCollectService(HikerClient hiker, BrandCallContext callContext,
			BrandHashtagRepository repo, BrandMentionJudge judge, BrandRepository brands,
			int windowDays, int maxPages) {
		this.hiker = hiker;
		this.callContext = callContext;
		this.repo = repo;
		this.judge = judge;
		this.brands = brands;
		this.windowDays = windowDays;
		this.maxPages = maxPages;
	}

	/**
	 * 브랜드 1개분 해시태그 스윕 — 태그가 없으면 콜 0으로 즉시 반환한다(스펙 §3-1).
	 * 브랜드 소개(biography)·멘션 정규식은 태그 전체가 공유하는 판정 컨텍스트라 태그 루프 진입 전
	 * 1회씩만 준비한다(biography는 BrandRepository 조회 1회, HTTP 콜 아님 — 멘션 Pattern.compile도
	 * 게시물마다가 아니라 여기서 1회만).
	 */
	public void sweep(BrandRow brand) {
		// 콜 집계 스코프(어드민 크롤링 비용) — 해시태그 recent 열거 콜도 이 브랜드 몫으로 계상된다.
		callContext.runScoped(brand.id(), () -> doSweep(brand));
	}

	private void doSweep(BrandRow brand) {
		List<String> tags = repo.findTags(brand.id());
		if (tags.isEmpty()) {
			return;
		}
		String biography = brands.findBiography(brand.id());
		Instant windowCutoff = Instant.now().minus(Duration.ofDays(windowDays));
		Pattern mentionPattern = mentionPattern(brand.username());
		Set<String> insertedThisRun = new HashSet<>();

		int savedTotal = 0;
		for (String tag : tags) {
			savedTotal += sweepTag(brand, tag, tags, biography, windowCutoff, mentionPattern, insertedThisRun);
		}
		log.info("브랜드 해시태그 스윕 완료 — {} 태그 {}개, 신규 저장 {}건",
				brand.username(), tags.size(), savedTotal);
	}

	/**
	 * 태그 1개분 recent 열거 — maxPages까지 순회하되, 페이지에 "이전부터 있던" 게시물이 하나라도
	 * 있으면 그 페이지의 신규만 처리하고 중단한다. 빈 페이지·커서 null도 자연 종료.
	 */
	private int sweepTag(BrandRow brand, String tag, List<String> allTags, String biography,
			Instant windowCutoff, Pattern mentionPattern, Set<String> insertedThisRun) {
		int saved = 0;
		String cursor = null;
		for (int page = 0; page < maxPages; page++) {
			HikerClient.HashtagPage result = hiker.fetchHashtagRecentPage(tag, cursor);
			if (result.posts().isEmpty()) {
				break;
			}
			List<String> codes = result.posts().stream().map(hp -> hp.post().shortCode()).toList();
			Set<String> existing = repo.existingCodes(brand.id(), codes);
			// 재처리 스킵 판정은 existing 그대로(dedup 의미 불변) — 동일 shortCode 중복은
			// freshPosts 구성 시 별도로 접는다(judge 이중 호출·saved 이중 계상 방지).
			List<HikerClient.HashtagPost> freshPosts = distinctByShortCode(result.posts().stream()
					.filter(hp -> !existing.contains(hp.post().shortCode())).toList());
			saved += processNew(brand, tag, freshPosts, allTags, biography, windowCutoff,
					mentionPattern, insertedThisRun);
			// 조기 종료 트리거는 "이전부터 있던" 코드에만 반응한다 — 이번 실행에서 다른 태그가
			// 방금 저장한 코드는 종료 신호가 아니다(크로스 태그 백필 깊이 보존).
			boolean hasPriorExisting = existing.stream().anyMatch(c -> !insertedThisRun.contains(c));
			if (hasPriorExisting) {
				break;
			}
			cursor = result.nextPageId();
			if (cursor == null) {
				break;
			}
		}
		return saved;
	}

	/** 페이지 내 동일 shortCode 중복 제거 — 응답 순서 유지, 첫 등장분만 남긴다. */
	private static List<HikerClient.HashtagPost> distinctByShortCode(List<HikerClient.HashtagPost> posts) {
		Map<String, HikerClient.HashtagPost> byCode = new LinkedHashMap<>();
		for (HikerClient.HashtagPost hp : posts) {
			byCode.putIfAbsent(hp.post().shortCode(), hp);
		}
		return new ArrayList<>(byCode.values());
	}

	/**
	 * 신규 게시물 처리 순서 고정(스펙 §4): shortCode/takenAt/authorUsername 결손 스킵 → 윈도우 컷 →
	 * 자사(SELF/RULE) → 직접태그(DIRECT_TAGGED/RULE) → 캡션 멘션(RELEVANT/MENTION) → LLM 판정.
	 * authorUsername 결손은 shortCode·takenAt과 같은 지점에서 걸러 LLM 콜도 나가지 않게 한다 —
	 * author_username은 brand_hashtag_post의 NOT NULL 컬럼이라, 여기서 안 거르면 insertPost가
	 * 제약 위반으로 던지고 브랜드 스윕 전체가 죽는다(한 게시물의 결손이 나머지를 물귀신처럼 끌고
	 * 감 — 격리 원칙 위반). LLM 호출이 던지면 그 게시물은 미저장 스킵(다음 스윕이 existingCodes에
	 * 없으니 자연 재시도).
	 *
	 * <p>SELF 판정(2026-08-17 — 제외 문자열 기능 폐기): 예전엔 유저가 관리하는 제외 문자열 목록에
	 * 게시자 username이 <b>포함</b>되면(substring) SELF였지만, 그 목록 자체가 폐지되며 판정 재료를
	 * 잃었다. 브랜드 본인 게시물을 발견 풀에 계속 들여보낼 수는 없으므로, 게시자 username이 브랜드
	 * 계정명과 <b>정확히 일치</b>(대소문자 무시)하는 경우만 SELF로 남긴다 — "브랜드명을 포함한
	 * 스태프 부계정" 같은 근사 매치는 더 이상 SELF가 아니라 일반 판정 경로(직접태그·멘션·LLM)를 탄다.
	 */
	private int processNew(BrandRow brand, String tag, List<HikerClient.HashtagPost> posts,
			List<String> allTags, String biography, Instant windowCutoff,
			Pattern mentionPattern, Set<String> insertedThisRun) {
		String brandUsernameLower = brand.username().toLowerCase(Locale.ROOT);
		int saved = 0;
		for (HikerClient.HashtagPost hp : posts) {
			PostInfo post = hp.post();
			String shortCode = post.shortCode();
			Long takenAtEpoch = post.takenAt();
			String authorUsername = post.username();
			// HikerClient의 asString()은 code 키 부재 시 null이 아니라 ""를 반환한다 — isBlank()도 함께 본다.
			if (shortCode == null || shortCode.isBlank() || takenAtEpoch == null
					|| authorUsername == null || authorUsername.isBlank()) {
				continue;
			}
			OffsetDateTime takenAt = Instant.ofEpochSecond(takenAtEpoch).atOffset(ZoneOffset.UTC);
			if (takenAt.toInstant().isBefore(windowCutoff)) {
				continue;
			}
			String verdict;
			String source;
			if (authorUsername.equalsIgnoreCase(brand.username())) {
				verdict = "SELF";
				source = "RULE";
			} else if (hp.taggedUsernames().contains(brandUsernameLower)) {
				verdict = "DIRECT_TAGGED";
				source = "RULE";
			} else if (mentionsBrand(post.caption(), mentionPattern)) {
				verdict = "RELEVANT";
				source = "MENTION";
			} else {
				try {
					BrandMentionJudge.Verdict v = judge.judge(brand.username(), biography, allTags,
							authorUsername, post.caption());
					verdict = v.name();
					source = "LLM";
				} catch (RuntimeException e) {
					log.warn("해시태그 판정 실패(미저장, 다음 스윕 재시도) — 게시물 {}: {}",
							shortCode, e.toString());
					continue;
				}
			}
			repo.insertPost(new HashtagPostInsert(brand.id(), tag, shortCode, authorUsername,
					post.ownerFullName(), post.ownerProfilePicUrl(), takenAt, post.caption(),
					post.contentType(), post.thumbnailUrl(), post.likes(), post.comments(),
					verdict, source));
			insertedThisRun.add(shortCode);
			saved++;
		}
		return saved;
	}

	/**
	 * 캡션 @멘션 전체 단어 일치 패턴 — "@brandusername" 뒤에 [\w.]가 이어지면 불일치로 본다
	 * (예: 브랜드 cclime_official 캡션의 @cclime_officialkr은 오인 금지). 대소문자 무시.
	 * 스윕 1회당 1번만 컴파일한다(게시물마다 compile 금지 — sweep()에서 만들어 내려보낸다).
	 */
	private static Pattern mentionPattern(String brandUsername) {
		return Pattern.compile("(?i)@" + Pattern.quote(brandUsername) + "(?![\\w.])");
	}

	private static boolean mentionsBrand(String caption, Pattern mentionPattern) {
		return caption != null && !caption.isBlank() && mentionPattern.matcher(caption).find();
	}
}
