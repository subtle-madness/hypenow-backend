package com.celfit.monitoring.service;

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
import java.util.List;
import java.util.Locale;
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
 * <p>plain class + {@code BrandHashtagConfig}에서 배선(BrandCollectService처럼 @Service를 쓰지
 * 않는 이유는 없지만, 태스크 계획이 설정 클래스 분리를 명시해 그대로 따른다).
 */
public class BrandHashtagCollectService {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagCollectService.class);

	private final HikerClient hiker;
	private final BrandHashtagRepository repo;
	private final BrandMentionJudge judge;
	private final BrandRepository brands;
	private final int windowDays;
	private final int maxPages;

	public BrandHashtagCollectService(HikerClient hiker, BrandHashtagRepository repo,
			BrandMentionJudge judge, BrandRepository brands, int windowDays, int maxPages) {
		this.hiker = hiker;
		this.repo = repo;
		this.judge = judge;
		this.brands = brands;
		this.windowDays = windowDays;
		this.maxPages = maxPages;
	}

	/**
	 * 브랜드 1개분 해시태그 스윕 — 태그가 없으면 콜 0으로 즉시 반환한다(스펙 §3-1).
	 * 제외 문자열·브랜드 소개(biography)는 태그 전체가 공유하는 판정 컨텍스트라 태그 루프
	 * 진입 전 1회씩만 뽑는다(biography는 BrandRepository 조회 1회, HTTP 콜 아님).
	 */
	public void sweep(BrandRow brand) {
		List<String> tags = repo.findTags(brand.id());
		if (tags.isEmpty()) {
			return;
		}
		List<String> exclusions = repo.findExclusionTerms(brand.id());
		String biography = brands.findBiography(brand.id());
		Instant windowCutoff = Instant.now().minus(Duration.ofDays(windowDays));

		int savedTotal = 0;
		for (String tag : tags) {
			savedTotal += sweepTag(brand, tag, tags, exclusions, biography, windowCutoff);
		}
		log.info("브랜드 해시태그 스윕 완료 — {} 태그 {}개, 신규 저장 {}건",
				brand.username(), tags.size(), savedTotal);
	}

	/**
	 * 태그 1개분 recent 열거 — maxPages까지 순회하되, 페이지에 기존 게시물이 하나라도 있으면
	 * 그 페이지의 신규만 처리하고 중단한다. 빈 페이지·커서 null도 자연 종료.
	 */
	private int sweepTag(BrandRow brand, String tag, List<String> allTags, List<String> exclusions,
			String biography, Instant windowCutoff) {
		int saved = 0;
		String cursor = null;
		for (int page = 0; page < maxPages; page++) {
			HikerClient.HashtagPage result = hiker.fetchHashtagRecentPage(tag, cursor);
			if (result.posts().isEmpty()) {
				break;
			}
			List<String> codes = result.posts().stream().map(hp -> hp.post().shortCode()).toList();
			Set<String> existing = repo.existingCodes(brand.id(), codes);
			List<HikerClient.HashtagPost> freshPosts = result.posts().stream()
					.filter(hp -> !existing.contains(hp.post().shortCode())).toList();
			saved += processNew(brand, tag, freshPosts, allTags, exclusions, biography, windowCutoff);
			if (!existing.isEmpty()) {
				// 조기 종료 — 페이지 내 신규는 이미 처리했다, 다음 페이지는 요청하지 않는다.
				break;
			}
			cursor = result.nextPageId();
			if (cursor == null) {
				break;
			}
		}
		return saved;
	}

	/**
	 * 신규 게시물 처리 순서 고정(스펙 §4): 윈도우 컷 → 자사(SELF/RULE) → 직접태그(DIRECT_TAGGED/
	 * RULE) → 캡션 멘션(RELEVANT/MENTION) → LLM 판정. LLM 호출이 던지면 그 게시물은 미저장
	 * 스킵한다(다음 스윕이 existingCodes에 없으니 자연 재시도).
	 */
	private int processNew(BrandRow brand, String tag, List<HikerClient.HashtagPost> posts,
			List<String> allTags, List<String> exclusions, String biography, Instant windowCutoff) {
		String brandUsernameLower = brand.username().toLowerCase(Locale.ROOT);
		int saved = 0;
		for (HikerClient.HashtagPost hp : posts) {
			PostInfo post = hp.post();
			String shortCode = post.shortCode();
			Long takenAtEpoch = post.takenAt();
			if (shortCode == null || takenAtEpoch == null) {
				continue;
			}
			OffsetDateTime takenAt = Instant.ofEpochSecond(takenAtEpoch).atOffset(ZoneOffset.UTC);
			if (takenAt.toInstant().isBefore(windowCutoff)) {
				continue;
			}
			String authorUsername = post.username();
			String verdict;
			String source;
			if (authorUsername != null && matchesExclusion(authorUsername, exclusions)) {
				verdict = "SELF";
				source = "RULE";
			} else if (hp.taggedUsernames().contains(brandUsernameLower)) {
				verdict = "DIRECT_TAGGED";
				source = "RULE";
			} else if (mentionsBrand(post.caption(), brand.username())) {
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
			saved++;
		}
		return saved;
	}

	/** 게시자 username에 제외 문자열이 포함되면 자사 계열(SELF) — 대소문자 무시. */
	private static boolean matchesExclusion(String authorUsername, List<String> exclusions) {
		String lower = authorUsername.toLowerCase(Locale.ROOT);
		return exclusions.stream().anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
	}

	/**
	 * 캡션 @멘션 전체 단어 일치 — "@brandusername" 뒤에 [\w.]가 이어지면 불일치로 본다
	 * (예: 브랜드 cclime_official 캡션의 @cclime_officialkr은 오인 금지). 대소문자 무시.
	 */
	private static boolean mentionsBrand(String caption, String brandUsername) {
		if (caption == null || caption.isBlank()) {
			return false;
		}
		Pattern mention = Pattern.compile("(?i)@" + Pattern.quote(brandUsername) + "(?![\\w.])");
		return mention.matcher(caption).find();
	}
}
