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

	/** 구분자 없이 붙은 두 해시태그도 정규식이 각각의 #부터 다시 매치해 둘로 갈린다. */
	@Test
	void 구분자_없이_붙은_해시태그는_둘로_나뉜다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#tag1#tag2", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag)
				.containsExactlyInAnyOrder("tag1", "tag2");
	}

	@Test
	void 공백만_있는_캡션은_빈_목록이다() {
		assertThat(HashtagCandidateExtractor.extract(List.of(post("   ", T1)), Set.of())).isEmpty();
	}

	/**
	 * was BrandCaptionHashtags와 공유하는 정규식 계약: {@code [\p{L}\p{N}_]+}는 밑줄만으로도
	 * 매치된다. "#_"는 언어적으로 의미 있는 태그는 아니지만 실사용 캡션에서 사실상 나오지 않아
	 * 별도 배제 규칙을 두지 않는다 — 현재 동작을 그대로 계약으로 봉인한다.
	 */
	@Test
	void 밑줄만_있는_태그는_그대로_통과한다() {
		var out = HashtagCandidateExtractor.extract(List.of(post("#_", T1)), Set.of());

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("_");
	}

	/** stoplist 항목은 대소문자를 가리지 않는다 — 호출자가 소문자로 넘기지 않아도 배제된다. */
	@Test
	void stoplist_항목이_대문자여도_소문자_태그를_배제한다() {
		var out = HashtagCandidateExtractor.extract(
				List.of(post("#ad #끌리메", T1)), Set.of("광고", "AD"));

		assertThat(out).extracting(HashtagCandidateExtractor.Candidate::tag).containsExactly("끌리메");
	}
}
