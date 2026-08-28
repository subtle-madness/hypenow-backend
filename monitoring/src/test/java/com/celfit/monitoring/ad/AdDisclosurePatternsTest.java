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
	void 부정_신호와_스팬이_겹치지_않으면_다른_고신뢰_패턴이_매칭된다() {
		// 08-28 스팬 겹침 축소 이전엔 "내돈내산이지만 #광고"가 캡션 어디든 있는 부정 신호에 걸려
		// 무조건 null이었다. "내돈내산"과 "#광고"는 스팬이 겹치지 않으므로(부정어가 "#광고"를
		// 직접 수식하지 않음) 이제는 매칭을 살린다.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("내돈내산이지만 #광고");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
	}

	@Test
	void 부정_문구_뒤_무관한_문장에_실존하는_표기는_매칭된다() {
		// 08-28 운영 오탐 실측(_arinzip short_code Db7xIiTiSPy): "#광고 아린이가 반년넘게 꾸준히
		// 내돈내산해서…"는 캡션 맨 앞에 "#광고"가 실존하는데도 캡션 뒤쪽 "내돈내산"에 걸려 Tier1이
		// 통째로 포기해 최종 NOT_DISCLOSED로 오귀속됐다. "#광고"와 "내돈내산" 스팬이 겹치지 않으므로
		// 이제는 "#광고"가 매칭된다.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns
				.findFirstMatch("#광고 아린이가 반년넘게 꾸준히 내돈내산해서 후기 남겨요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
	}

	@Test
	void 협찬_인데_내돈내산각_같은_캡션도_매칭된다() {
		// 08-28 운영 오탐 실측(_bbohouse short_code DbPMV2Jmd05, "(광고) 지만 내돈내산" 계열)과 같은
		// 패턴 — "#협찬"과 "내돈내산"이 스팬으로 안 겹치므로 "#협찬"이 매칭된다.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("#협찬 인데 내돈내산각");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#협찬");
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
	void 협찬_조사_결합_부정문_스팬은_제외되지만_안겹치는_광고_해시태그는_매칭된다() {
		// "협찬이 아니라"의 NEGATION 스팬은 "협찬" 주변에만 걸리고, 문장 끝의 "#광고"와는 겹치지
		// 않는다 — 08-28 스팬 겹침 축소 이전엔 부정 가드가 캡션 전체를 포기시켜 null이었지만, 이제는
		// "#광고" 자체가 실존하는 표기이므로 매칭을 살린다.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("이건 협찬이 아니라 그냥 산거예요 #광고");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
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

	@Test
	void 제품제공_해시태그는_매칭된다() {
		// 08-19 운영 실측: LLM이 "#제품제공"을 AMBIGUOUS로 오분류해 표기 미흡 오탐 727건 —
		// 지침 Ⅴ.6 명확 표기이므로 Tier1로 승격(핫픽스).
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("#제품제공 @brand 후기입니다");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#제품제공");
	}

	@Test
	void 제품제공_해시태그도_토큰_경계로_접두_매칭을_차단한다() {
		// "#제품제공이벤트"(팔로워 증정 공지)는 "#제품제공"으로 오탐하지 않는다.
		assertThat(AdDisclosurePatterns.findFirstMatch("#제품제공이벤트 참여하세요")).isNull();
	}

	@Test
	void 제품을_제공받았다는_수령형_문구는_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("제품을 제공받아 작성한 후기입니다")).isNotNull();
		assertThat(AdDisclosurePatterns.findFirstMatch("제품 제공받았어요")).isNotNull();
		assertThat(AdDisclosurePatterns.findFirstMatch("제품제공받고 쓰는 후기")).isNotNull();
	}

	@Test
	void 상품제공_해시태그는_매칭된다() {
		// #제품제공(08-19 핫픽스 #514)과 동일 구조 — 프롬프트 CLEAR 예시와 사전의 비대칭 정합.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("#상품제공 후기 남겨요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#상품제공");
	}

	@Test
	void 상품제공_해시태그도_토큰_경계로_접두_매칭을_차단한다() {
		// "#상품제공이벤트"(증정 공지)는 "#상품제공"으로 오탐하지 않는다.
		assertThat(AdDisclosurePatterns.findFirstMatch("#상품제공이벤트 참여하세요")).isNull();
	}

	@Test
	void 텍스트_단독_제품제공은_사전에_없다() {
		// "제품제공 이벤트"(증정 공지)류 오탐 여지 — 수령형(받아/받았/받은/받고)이 아니면 LLM(Tier2) 몫.
		assertThat(AdDisclosurePatterns.findFirstMatch("팔로워 대상 제품제공 이벤트를 엽니다")).isNull();
	}

	@Test
	void 괄호형_광고_표기는_매칭된다() {
		// 08-28 운영 오탐 실측(_bbohouse "(광고) 지만 내돈내산") — 괄호로 감싼 표기는 고정밀이라 등재.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("(광고) 지만 내돈내산 하려던 제품이에요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("(광고)");
	}

	@Test
	void 대괄호형_협찬_표기도_매칭된다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("[협찬] 오늘의 룩 소개합니다");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("[협찬]");
	}

	@Test
	void 괄호_안이라도_저정밀_단독_광고는_아니고_정확히_광고여야_매칭된다() {
		// 괄호형은 "(광고)"·"[광고]"만 고정밀로 인정 — "(광고주)"처럼 괄호 안에 다른 글자가 더 있으면
		// 사전 패턴([\(\[]\s*광고\s*[\)\]])이 매칭하지 않는다.
		assertThat(AdDisclosurePatterns.findFirstMatch("(광고주) 협업 문의는 DM으로")).isNull();
	}

	@Test
	void 전각_해시_협찬은_매칭된다() {
		// 08-28 운영 오탐 실측(dodami_0607 short_code DYs1rKgEgRv): "＃협찬 | #아워팜"처럼 전각
		// 해시(U+FF03)로 시작하는 캡션이 반각 "#"만 매칭하던 사전·LLM 둘 다 놓쳐 INSUFFICIENT+
		// FOREIGN_LANGUAGE로 오귀속됐다.
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("＃협찬 | #아워팜 제품이에요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("＃협찬");
	}

	@Test
	void 전각_해시_광고도_매칭된다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("＃광고 오늘의 룩")).isNotNull();
	}
}
