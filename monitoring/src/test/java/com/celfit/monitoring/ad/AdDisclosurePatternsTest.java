package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdDisclosurePatternsTest {

	@Test
	void 고신뢰_패턴을_찾는다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("오늘의 룩 #광고 잘봐주세요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
		assertThat(m.start()).isEqualTo(6);
	}

	@Test
	void 여러_패턴_중_가장_이른_위치를_고른다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("협찬받았어요 오늘의 룩 #광고");
		assertThat(m.phrase()).isEqualTo("협찬받았");
	}

	@Test
	void 소정의_수수료_원고료_광고료_패턴을_인식한다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 원고료를 지급받았습니다").phrase())
				.isEqualTo("소정의 원고료");
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 수수료를 지급받았습니다")).isNotNull();
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 광고료를 지급받았습니다")).isNotNull();
	}

	@Test
	void 저정밀_단독_광고는_사전에_없다() {
		// "광고판이 예쁘네요" 같은 오탐 방지(스펙 §5 Tier1) — 단독 "광고"는 사전 미등재
		assertThat(AdDisclosurePatterns.findFirstMatch("동네 광고판이 예쁘네요")).isNull();
	}

	@Test
	void 매칭_없으면_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("오늘의 데일리룩 공유합니다")).isNull();
	}

	@Test
	void 빈_캡션은_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("")).isNull();
		assertThat(AdDisclosurePatterns.findFirstMatch(null)).isNull();
	}

	@Test
	void 더_긴_해시태그의_접두_매칭을_토큰_경계로_차단한다() {
		// "#광고아님"은 "#광고"의 접두이지만 뒤에 문자가 이어지므로 매칭되지 않는다.
		// "내돈내산"도 부정 가드에 걸려 이중으로 null이다.
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고아님 내돈내산")).isNull();
	}

	@Test
	void 협찬받고_싶다는_모집_문맥은_사전에_없다() {
		// "협찬받고"(희망·모집)는 과거형 확정 문구가 아니라 Tier1에서 제외 — LLM(Tier2)이 처리한다.
		assertThat(AdDisclosurePatterns.findFirstMatch("협찬받고 싶어요 연락주세요")).isNull();
	}

	@Test
	void 부정_신호가_있으면_다른_고신뢰_패턴이_있어도_null() {
		// "내돈내산이지만 #광고"처럼 부정 신호가 캡션 어디든 있으면 Tier1 확정을 포기한다(판단 보류).
		assertThat(AdDisclosurePatterns.findFirstMatch("내돈내산이지만 #광고")).isNull();
	}

	@Test
	void 부정_신호_없는_정상_해시태그는_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고 오늘 후기")).isNotNull();
	}

	@Test
	void 협찬받았다는_과거형_확정_문구는_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("협찬받았어요")).isNotNull();
	}

	@Test
	void 해시태그_뒤_구두점은_토큰_경계를_해치지_않는다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고, 오늘 후기")).isNotNull();
	}

	@Test
	void 광고_아님_띄어쓰기_변형도_부정_가드에_걸린다() {
		// "아니"(2음절)만 잡던 기존 정규식은 "아님"(아+님)을 못 잡아 "#광고 아님"이 그대로
		// DISCLOSED로 오탐했다 — (광고|협찬)\s*(아니|아님)로 교체해 두 변형·띄어쓰기를 모두 커버.
		assertThat(AdDisclosurePatterns.findFirstMatch("#광고 아님 사비로 구매했습니다")).isNull();
	}

	@Test
	void 협찬_아님_띄어쓰기_변형도_부정_가드에_걸린다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("#협찬 아님 그냥 샀어요")).isNull();
	}

	@Test
	void 협찬_조사_결합_부정문은_부정_가드에_걸린다() {
		// "협찬이 아니라"처럼 명사와 "아니/아님" 사이에 조사(이/가/은/는)가 끼어드는 경우 —
		// (광고|협찬)\s*(아니|아님)만으로는 못 잡던 구멍(조사 결합 부정문). #광고가 뒤에 있어도
		// 부정 가드가 먼저 걸려 Tier1 확정을 포기한다.
		assertThat(AdDisclosurePatterns.findFirstMatch("이건 협찬이 아니라 그냥 산거예요 #광고")).isNull();
	}

	@Test
	void 광고_조사_결합_부정문은_부정_가드에_걸린다() {
		// "광고가 아니에요"도 동일하게 조사 결합 부정문 구멍이었다.
		assertThat(AdDisclosurePatterns.findFirstMatch("광고가 아니에요 내맘대로 후기")).isNull();
	}

	@Test
	void 이모지_뒤_오프셋은_char_index_기준이다() {
		// Javadoc 캐비어트("오프셋은 그래핌이 아니라 char index")의 회귀 방지 —
		// 서로게이트 페어인 이모지가 앞에 있어도 start/end는 String.indexOf와 같은 UTF-16 char index다.
		String caption = "오늘의 룩 😀😀 #광고 후기";
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch(caption);
		assertThat(m).isNotNull();
		assertThat(m.start()).isEqualTo(caption.indexOf("#광고"));
		assertThat(caption.substring(m.start(), m.end())).isEqualTo("#광고");
	}
}
