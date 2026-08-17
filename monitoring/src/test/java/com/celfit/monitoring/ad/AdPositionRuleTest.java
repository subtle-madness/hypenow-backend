package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdPositionRuleTest {

	@Test
	void 첫_해시태그는_오프셋_무관_인정() {
		// #광고가 캡션의 첫 번째 해시태그 토큰이면 뒤에 아무리 와도(더보기 접힘권) 예외 인정(지침 다.(2)③)
		String longTail = "룩 소개".repeat(50);
		String caption = "#광고 " + longTail;
		int start = caption.indexOf("#광고");
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.FIRST_HASHTAG);
	}

	@Test
	void 첫_해시태그가_아니면_일반_위치_규칙을_따른다() {
		String caption = "오늘 룩 소개 #데일리룩 " + "#광고";
		int start = caption.lastIndexOf("#광고");
		// 짧은 캡션이라 보임 상한(125그래핌) 안 — VISIBLE
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.VISIBLE);
	}

	@Test
	void 보임_상한_안쪽은_VISIBLE() {
		// #광고가 유일한 해시태그면 첫 해시태그 예외(FIRST_HASHTAG)로 빠지므로, 앞에 다른 해시태그를
		// 둬 일반 위치 규칙(보임 상한 이내)을 실제로 태운다.
		String caption = "짧은 캡션입니다 #데일리 #광고";
		int start = caption.indexOf("#광고");
		assertThat(AdPositionRule.evaluate(caption, start, start + "#광고".length()))
				.isEqualTo(AdPositionRule.Band.VISIBLE);
	}

	@Test
	void 접힘_하한_초과는_HIDDEN() {
		// 해시태그가 아닌 본문 문구("광고입니다", Tier1 사전 항목)를 쓴다 — "#광고"면 캡션의 유일한
		// 해시태그라 첫 해시태그 예외로 빠져 이 테스트가 검증하려는 일반 위치 규칙의 HIDDEN 분기를
		// 태우지 못한다.
		String filler = "가".repeat(250);   // 그래핌 250 > HIDDEN_LOWER_BOUND(220)
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.HIDDEN);
	}

	@Test
	void 세번째_줄_이후는_HIDDEN() {
		// 마찬가지로 비해시태그 문구 — "#광고"면 유일한 해시태그라 첫 해시태그 예외에 걸린다.
		String caption = "1번째 줄\n2번째 줄\n3번째 줄 광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.HIDDEN);
	}

	@Test
	void 경계_사이_회색지대는_게시자에게_유리하게_GRAY() {
		// 보임 상한(125) 초과 & 접힘 하한(220) 이하 — 확실한 위반 아님(지침 원문 "눌러야만"만 부적절).
		// 비해시태그 문구를 쓴다 — "#광고"였다면 유일한 해시태그라 첫 해시태그 예외로 빠진다.
		String filler = "가".repeat(160);
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.GRAY);
	}

	@Test
	void 그래핌_오프셋은_문자_인덱스와_다를_수_있다() {
		// 이모지·결합 문자 등 서로게이트 페어 — 여기서는 최소한 일반 한글 문자열에서 char==grapheme임을 확인
		assertThat(AdPositionRule.graphemeOffset("가나다#광고", 3)).isEqualTo(3);
	}

	@Test
	void 보임_상한_경계값_125그래핌은_등호_포함_VISIBLE() {
		// 순수 한글 filler라 grapheme == char index — 오프셋 계산을 단순하게 유지.
		// 문구("광고입니다", 5그래핌) 끝이 정확히 125그래핌이 되도록 filler 120그래핌.
		String filler = "가".repeat(120);
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		int end = start + "광고입니다".length();
		assertThat(AdPositionRule.graphemeOffset(caption, end)).isEqualTo(125);
		assertThat(AdPositionRule.evaluate(caption, start, end)).isEqualTo(AdPositionRule.Band.VISIBLE);
	}

	@Test
	void 접힘_하한_경계값_220그래핌은_strict초과가_아니라_GRAY() {
		// startGrapheme > 220이 strict 조건이므로 정확히 220은 HIDDEN이 아니라 GRAY.
		String filler = "가".repeat(220);
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.graphemeOffset(caption, start)).isEqualTo(220);
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.GRAY);
	}

	@Test
	void 접힘_하한_221그래핌은_HIDDEN() {
		String filler = "가".repeat(221);
		String caption = filler + "광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.graphemeOffset(caption, start)).isEqualTo(221);
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.HIDDEN);
	}

	@Test
	void 문구가_정확히_2번째_줄에서_시작하고_보임_상한_이내면_VISIBLE() {
		// "1번째 줄\n" 다음이 2번째 줄 시작 — startLine == VISIBLE_LINE_MAX(2) 경계 확인.
		String caption = "1번째 줄\n광고입니다";
		int start = caption.indexOf("광고입니다");
		assertThat(AdPositionRule.evaluate(caption, start, start + "광고입니다".length()))
				.isEqualTo(AdPositionRule.Band.VISIBLE);
	}
}
