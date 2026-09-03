package com.celfit.monitoring.service;

import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 태그된 게시물 캡션 → 해시태그 후보(2026-09-03 자동 시드 재설계 §3-2) — 순수 함수. 외부 상태·시각에
 * 의존하지 않으므로 같은 입력이면 정렬까지 항상 같은 결과다(테스트가 봉인하는 계약).
 *
 * <p>추출 규칙은 was {@code BrandCaptionHashtags}와 같다 — ASCII {@code #} + {@code [\p{L}\p{N}_]+}
 * 로 인스타 링크화와 일치시킨다(전각 ＃ 제외·점에서 끊김). 두 규칙이 갈리면 "화면에서 필터되는
 * 해시태그"와 "제안 후보"가 어긋난다.
 *
 * <p>집계 단위는 <b>등장 게시물 수</b>다 — 한 캡션에 같은 태그를 세 번 달아도 1로 센다(태그 도배가
 * 순위를 만들지 못하게 한다).
 */
public final class HashtagCandidateExtractor {

	/** was BrandCaptionHashtags.HASHTAG와 같은 정의를 유지할 것. */
	private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");
	private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");

	/** 후보 1건 — 태그(소문자)·등장 게시물 수·그 태그가 등장한 가장 최근 게시일(없으면 null). */
	public record Candidate(String tag, int postCount, Instant latestTakenAt) {
	}

	private HashtagCandidateExtractor() {
	}

	/**
	 * 정렬된 후보 목록 — 등장 게시물 수 내림차순 → 최근 게시일 내림차순(null은 뒤) → 태그 사전순.
	 *
	 * @param posts    태그된 게시물의 캡션·게시일. 캡션 null·빈 값은 무시한다.
	 * @param stoplist 제외 태그. 대소문자를 가리지 않는다 — 호출자의 소문자 사전조건에 기대지 않고
	 *                 여기서 직접 소문자로 정규화한다. 순수 숫자 태그는 stoplist와 무관하게 항상 제외한다.
	 */
	public static List<Candidate> extract(List<TaggedCaption> posts, Set<String> stoplist) {
		Set<String> normalizedStoplist = new HashSet<>();
		for (String tag : stoplist) {
			normalizedStoplist.add(tag.toLowerCase(Locale.ROOT));
		}
		Map<String, Integer> countByTag = new HashMap<>();
		Map<String, Instant> latestByTag = new HashMap<>();
		for (TaggedCaption post : posts) {
			for (String tag : tagsOf(post.caption(), normalizedStoplist)) {
				countByTag.merge(tag, 1, Integer::sum);
				if (post.takenAt() != null) {
					latestByTag.merge(tag, post.takenAt(), (a, b) -> a.isAfter(b) ? a : b);
				}
			}
		}
		List<Candidate> out = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : countByTag.entrySet()) {
			out.add(new Candidate(entry.getKey(), entry.getValue(), latestByTag.get(entry.getKey())));
		}
		Comparator<Candidate> ranking = Comparator.comparingInt((Candidate c) -> c.postCount()).reversed()
				.thenComparing(Candidate::latestTakenAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
				.thenComparing(Candidate::tag);
		out.sort(ranking);
		return List.copyOf(out);
	}

	/**
	 * 게시물 1건의 태그 집합 — 소문자 정규화 후 게시물당 중복 제거, 순수 숫자·stoplist 제외.
	 *
	 * @param stoplist 이미 소문자로 정규화된 상태로 전달돼야 한다({@link #extract} 참고).
	 */
	private static Set<String> tagsOf(String caption, Set<String> stoplist) {
		if (caption == null || caption.isEmpty()) {
			return Set.of();
		}
		Set<String> tags = new HashSet<>();
		Matcher matcher = HASHTAG.matcher(caption);
		while (matcher.find()) {
			String tag = matcher.group(1).toLowerCase(Locale.ROOT);
			if (DIGITS_ONLY.matcher(tag).matches() || stoplist.contains(tag)) {
				continue;
			}
			tags.add(tag);
		}
		return tags;
	}
}
