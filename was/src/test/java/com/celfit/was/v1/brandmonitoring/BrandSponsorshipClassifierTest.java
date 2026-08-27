package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 협찬 판정(FE §4.4) 순수 함수 단위 테스트. */
class BrandSponsorshipClassifierTest {

	@Test
	void 판정_규칙_4단계() {
		assertThat(BrandSponsorshipClassifier.classify(true, null)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(null, "오늘의 #협찬 후기")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "#광고 아님… 이 아니라 광고")).isEqualTo("sponsored"); // 키워드가 플래그 false보다 우선
		assertThat(BrandSponsorshipClassifier.classify(false, "그냥 일상")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(null, "그냥 일상")).isEqualTo("unknown");
		assertThat(BrandSponsorshipClassifier.classify(null, null)).isEqualTo("unknown");
	}

	@Test
	void 한국어_확장_마커_제품제공() {
		assertThat(BrandSponsorshipClassifier.classify(false, "#제품제공 후기입니다")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "브랜드에서 제품을 제공받아 작성했어요")).isEqualTo("sponsored");
	}

	@Test
	void 영문_해시태그_마커는_정확한_태그_토큰만() {
		// 운영 실측 — 해외 인플루언서는 #ad류 표기만 쓴다(DZ_SPNbzTKE 등)
		assertThat(BrandSponsorshipClassifier.classify(false, "50% deal ⭐️ #ad \n#cclime")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "so good #sponsored")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "thanks for the #gifted set")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "#PR box unboxing")).isEqualTo("sponsored"); // 대소문자 무시
		// substring이면 #adventure가 #ad에 걸린다 — 태그 전체 일치만 인정
		assertThat(BrandSponsorshipClassifier.classify(false, "our #adventure was #adorable")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(false, "#prospin review")).isEqualTo("organic");
	}

	@Test
	void 캡션_선두_광고_접두_표기() {
		// 운영 실측(DbU1UKMR7Nk) — 구분자가 파이프가 아니라 한글 모음 ㅣ(U+3163)
		assertThat(BrandSponsorshipClassifier.classify(false, "광고 ㅣ 카톡선물 뭐 보낼지 고민될 때 저장해두기…"))
				.isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "광고 | 신제품 리뷰")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "광고｜공백 없는 전각 구분자")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "협찬 - 한 달 사용 후기")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "  광고 ㅣ 선행 공백")).isEqualTo("sponsored");
		// 운영 실측(DbSa8zcBJCn) — 구분자가 소문자 라틴 l(U+006C)인 파이프 대용 표기
		assertThat(BrandSponsorshipClassifier.classify(false, "ad l 아침 루틴이 하루 전체를 좌우 한다는 걸 깨달았습니다"))
				.isEqualTo("sponsored");
		// 운영 실측(DRRVIvTkiDB) — 영문 선두 AD 표기
		assertThat(BrandSponsorshipClassifier.classify(false, "AD | Now you can experience CLIME's aesthetic care"))
				.isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "광고 l 소문자 엘 구분자")).isEqualTo("sponsored");
	}

	@Test
	void 선두_접두_표기는_구분자가_없거나_캡션_중간이면_불인정() {
		// 구분자 없이 단어가 이어지면 확정 표기가 아니다 — 오탐이 나면 오가닉 성과가 협찬으로 집계된다
		assertThat(BrandSponsorshipClassifier.classify(false, "광고 아님 그냥 내돈내산 후기")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(false, "광고비 한 푼 안 받고 쓴 후기")).isEqualTo("organic");
		// 선두 앵커만 인정 — 캡션 중간의 "광고 ㅣ"는 문맥을 알 수 없다
		assertThat(BrandSponsorshipClassifier.classify(false, "요즘 광고 ㅣ 표기 많이 보이네요")).isEqualTo("organic");
		// 선두 ad도 구분자 없이 단어가 이어지면 불인정 — adorable/ad lib이 ad에 걸리면 안 된다
		assertThat(BrandSponsorshipClassifier.classify(false, "adorable moments today")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(false, "ad lib workout session")).isEqualTo("organic");
	}

	@Test
	void 외국어_광고_표기() {
		assertThat(BrandSponsorshipClassifier.classify(false, "yeni rutinim 🤍 *reklam")).isEqualTo("sponsored"); // 터키어(DZuy0obyijZ 실측)
		assertThat(BrandSponsorshipClassifier.classify(false, "bu video reklamdır")).isEqualTo("sponsored"); // 교착어 접미사
		assertThat(BrandSponsorshipClassifier.classify(false, "この動画は広告です")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "業配影片分享")).isEqualTo("sponsored");
	}

	@Test
	void 오버로드_classify는_캡션_판정_결과와_같은_트리를_탄다() {
		// (isPaidPartnership, captionMarker) 조합 6칸 전부
		assertThat(BrandSponsorshipClassifier.classify(Boolean.TRUE, false)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(Boolean.TRUE, true)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(null, true)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(Boolean.FALSE, true)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(Boolean.FALSE, false)).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(null, false)).isEqualTo("unknown");
	}

	@Test
	void 캡션_classify는_마커_오버로드에_위임한다() {
		// 기존 caption 경로와 오버로드 경로가 같은 답을 내는지 — 대표 케이스만(전수 대조는 SQL 골든 코퍼스)
		assertThat(BrandSponsorshipClassifier.classify(null, "#광고 후기"))
				.isEqualTo(BrandSponsorshipClassifier.classify(null,
						BrandSponsorshipClassifier.containsSponsorshipMarker("#광고 후기")));
	}

	@Test
	void SQL_골든_코퍼스의_경계_사례는_Java_기대값이_고정이다() {
		// SQL 동치성 테스트(BrandSponsorshipSqlEquivalenceTest)는 두 구현의 "일치"만 단언해서
		// 둘이 함께 틀리면 통과한다. 코퍼스 주석이 기대값을 적어 둔 경계 사례는 여기서 절대값으로 못박는다.
		// "an ad for" — 해시태그도 아니고 선두도 아니다
		assertThat(BrandSponsorshipClassifier.containsSponsorshipMarker("This is an ad for fun")).isFalse();
		assertThat(BrandSponsorshipClassifier.classify(null, "This is an ad for fun")).isEqualTo("unknown");
		// 태그 토큰 불일치 — "#ad한글"은 #ad가 아니다([:alnum:] 유니코드 인식 경계)
		assertThat(BrandSponsorshipClassifier.containsSponsorshipMarker("#ad한글")).isFalse();
		assertThat(BrandSponsorshipClassifier.classify(null, "#ad한글")).isEqualTo("unknown");
		// reklam 단어 중간 — 앞이 한글(문자)이라 단어 접두가 아니다
		assertThat(BrandSponsorshipClassifier.containsSponsorshipMarker("한글reklam")).isFalse();
		assertThat(BrandSponsorshipClassifier.classify(null, "한글reklam")).isEqualTo("unknown");
		// 대조군 — "#광고" 부분 문자열에 걸려 매치가 기대값(비매치 3건과 같은 코퍼스 줄)
		assertThat(BrandSponsorshipClassifier.containsSponsorshipMarker("#광고태그중간ad")).isTrue();
		assertThat(BrandSponsorshipClassifier.classify(null, "#광고태그중간ad")).isEqualTo("sponsored");
	}

	@Test
	void 정규식_빌더는_비어있지_않은_ARE를_만든다() {
		String regex = BrandSponsorshipClassifier.postgresMarkerRegex();
		assertThat(regex).contains("#(?:").contains("reklam").contains("광고");
		// Java에서도 컴파일 가능한 부분집합만 쓰는지 스모크(ARE와 100% 동형은 아님 — 실검증은 SQL 코퍼스)
		assertThat(regex).doesNotContain("(?<").doesNotContain("(?=");
	}
}
