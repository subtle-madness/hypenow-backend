package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 수집 상한 컷 규칙 고정(FE 요청 2026-08-28 ②) — 게시물 목록과 인플루언서 집계가 이 함수 하나를
 * 공유하므로, 여기서 고정한 순서·경계가 곧 두 표면이 같은 모수를 본다는 보증이다.
 *
 * <p>특히 <b>전순서</b>(업로드일 동률에서 shortcode 타이브레이크)를 고정한다 — 부분 순서면 상한
 * 경계의 동률 구간에서 호출마다 다른 게시물이 남아, 두 표면이 같은 상한을 써도 서로 다른 2,000개를
 * 고른다.
 */
class BrandCollectionCapTest {

	@Test
	void 상한_이하면_입력_순서_그대로_통과시키고_capped는_false다() {
		List<BrandPostAssembler.PostRef> input = List.of(
				ref("C", "2026-08-01"), ref("A", "2026-08-03"), ref("B", "2026-08-02"));

		BrandCollectionCap.Capped capped = BrandCollectionCap.apply(input);

		// 자를 게 없으면 정렬도 하지 않는다 — 컷은 모수를 줄이는 일이지 순서를 정하는 일이 아니다
		// (요청 정렬은 호출부가 따로 건다).
		assertThat(capped.refs()).isEqualTo(input);
		assertThat(capped.capped()).isFalse();
	}

	@Test
	void 정확히_상한이면_capped가_false다() {
		// "상한에 걸림"과 "마침 정확히 2,000건"을 가르는 경계 — 여기서 true면 FE가 없는 잘림을 표시한다.
		BrandCollectionCap.Capped capped = BrandCollectionCap.apply(sequentialRefs(2000));

		assertThat(capped.refs()).hasSize(2000);
		assertThat(capped.capped()).isFalse();
	}

	@Test
	void 상한을_넘으면_최신_업로드순으로_자르고_capped가_true다() {
		// 입력 순서를 뒤집어 넣어도 남는 것은 최신순 상위 2,000이다 — 컷 순서는 입력 순서와 무관하다.
		List<BrandPostAssembler.PostRef> shuffled = new ArrayList<>(sequentialRefs(2001));
		Collections.reverse(shuffled);

		BrandCollectionCap.Capped capped = BrandCollectionCap.apply(shuffled);

		assertThat(capped.refs()).hasSize(2000);
		assertThat(capped.capped()).isTrue();
		// sequentialRefs는 인덱스가 커질수록 과거다 — 가장 오래된 1건(2000)만 잘린다.
		assertThat(capped.refs()).extracting(BrandPostAssembler.PostRef::shortcode)
				.doesNotContain("P2000")
				.contains("P0", "P1999");
	}

	@Test
	void 업로드일_동률은_shortcode로_갈라_컷이_결정적이다() {
		// 2,001건 전부 같은 날 — 타이브레이크가 없으면 어느 1건이 잘릴지 호출마다 달라진다.
		List<BrandPostAssembler.PostRef> sameDay = new ArrayList<>();
		for (int i = 0; i < 2001; i++) {
			sameDay.add(ref(String.format("P%04d", i), "2026-08-05"));
		}
		Collections.reverse(sameDay);

		BrandCollectionCap.Capped first = BrandCollectionCap.apply(sameDay);
		BrandCollectionCap.Capped second = BrandCollectionCap.apply(new ArrayList<>(sameDay));

		assertThat(first.refs()).isEqualTo(second.refs());
		// shortcode 오름차순이 이기므로 마지막 코드(P2000)가 잘린다.
		assertThat(first.refs()).extracting(BrandPostAssembler.PostRef::shortcode)
				.doesNotContain("P2000")
				.contains("P0000", "P1999");
	}

	@Test
	void 업로드일_미상은_마지막으로_밀려_먼저_잘린다() {
		// 업로드일을 모르는 게시물은 "최신"을 주장할 근거가 없다 — 상한이 걸리면 이쪽부터 나간다.
		List<BrandPostAssembler.PostRef> input = new ArrayList<>(sequentialRefs(2000));
		input.add(ref("UNKNOWN", null));

		BrandCollectionCap.Capped capped = BrandCollectionCap.apply(input);

		assertThat(capped.capped()).isTrue();
		assertThat(capped.refs()).extracting(BrandPostAssembler.PostRef::shortcode)
				.doesNotContain("UNKNOWN");
	}

	/** 인덱스가 커질수록 하루씩 과거인 refs — 최신순 컷의 기대값을 인덱스로 읽을 수 있게 한다. */
	private static List<BrandPostAssembler.PostRef> sequentialRefs(int count) {
		LocalDate newest = LocalDate.parse("2026-08-05");
		List<BrandPostAssembler.PostRef> refs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			refs.add(ref("P" + i, newest.minusDays(i).toString()));
		}
		return List.copyOf(refs);
	}

	/** 컷이 보는 축(shortcode·uploadedOn)만 채운 ref — 나머지는 이 규칙과 무관하다. */
	private static BrandPostAssembler.PostRef ref(String shortcode, String uploadedOn) {
		return new BrandPostAssembler.PostRef(shortcode, BrandPostAssembler.SOURCE_TAGGED, null,
				uploadedOn == null ? null : LocalDate.parse(uploadedOn),
				null, "reels", null, "author", null, null, null, null, List.of());
	}
}
