package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.TaggedPostRepository.TaggedCaption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 태그된 게시물 캡션 → 해시태그 후보(2026-09-03 자동 시드 재설계 §3-2). 순수 함수라 결정성이
 * 계약이다 — 정렬(등장 게시물 수 desc → 최근 게시일 desc → 태그 사전순)까지 여기서 봉인한다.
 */
class HashtagCandidateExtractorTest {

	private static final Instant T1 = Instant.parse("2026-09-01T00:00:00Z");
	private static final Instant T2 = Instant.parse("2026-09-02T00:00:00Z");

	private static TaggedCaption post(String caption, Instant takenAt) {
		return new TaggedCaption(caption, takenAt);
	}

	@Test
	void 캡션에서_해시태그를_소문자로_추출한다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("오늘 #Cclime 좋아요", T1)), Set.of());

		assertThat(out).containsExactly(new HashtagCandidateExtractor.Candidate("cclime", 1, T1));
	}

	@Test
	void 한_게시물_안의_같은_태그_반복은_한_번만_센다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#끌리메 #끌리메 #끌리메", T1)), Set.of());

		assertThat(out).singleElement()
				.extracting(HashtagCandidateExtractor.Candidate::postCount).isEqualTo(1);
	}

	@Test
	void 대소문자만_다른_태그는_같은_후보로_합쳐진다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post("#Cclime", T1), post("#CCLIME", T2)), Set.of());

		assertThat(out).containsExactly(new HashtagCandidateExtractor.Candidate("cclime", 2, T2));
	}

	@Test
	void stoplist_태그는_후보에서_빠진다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#광고 #끌리메", T1)), Set.of("광고"));

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메");
	}

	@Test
	void 순수_숫자_태그는_후보에서_빠진다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#2026 #끌리메", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메");
	}

	@Test
	void 숫자가_섞인_태그는_남는다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#끌리메2026", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메2026");
	}

	@Test
	void 등장_게시물_수_내림차순으로_정렬한다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#가 #나", T1), post("#나", T1), post("#나", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("나", "가");
	}

	@Test
	void 동률이면_최근_게시일이_앞선다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#오래된", T1), post("#최근", T2)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("최근", "오래된");
	}

	@Test
	void 수와_게시일이_모두_같으면_사전순이다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#bbb #aaa", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("aaa", "bbb");
	}

	@Test
	void 게시일이_null인_후보는_뒤로_밀린다() {
		var out = HashtagCandidateExtractor.extract(List.of(
				post("#널", null), post("#값", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("값", "널");
	}

	@Test
	void 캡션이_null이거나_비면_무시한다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post(null, T1), post("", T1), post("#가", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("가");
	}

	@Test
	void 해시태그가_하나도_없으면_빈_목록이다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(post("태그 없는 캡션", T1)), Set.of())).isEmpty();
	}

	@Test
	void 입력이_비면_빈_목록이다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(), Set.of())).isEmpty();
	}

	/** 전각 ＃은 인스타에서 링크가 되지 않는다 — BrandCaptionHashtags와 같은 계약. */
	@Test
	void 전각_샵은_해시태그가_아니다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(post("＃끌리메", T1)), Set.of())).isEmpty();
	}

	/** 점은 태그를 끊는다 — #cclime.beauty는 cclime까지다. */
	@Test
	void 점에서_태그가_끊긴다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#cclime.beauty", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("cclime");
	}
}
