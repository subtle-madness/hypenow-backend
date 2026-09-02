package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 날조 판정 순수 함수 검증(2026-09-02, "가벼운 층" 재설계) - {@link BrandAiAgent}와 분리해 신호 조합별
 * 참/거짓과 미대조 계정명 목록을 결정론으로 고정한다.
 */
class BrandAiGroundednessGuardTest {

	@Test
	void 툴_호출_0회에_표가_있으면_미대조로_본다() {
		String answer = "| 계정 | 게시물 | 조회수 | 참여율 |\n| @yoon_yoon_ | 11 | 14520 | 1.82 |";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, null, Set.of());

		assertThat(result.ungrounded()).isTrue();
		assertThat(result.unmatchedHandles()).contains("yoon_yoon_");
	}

	@Test
	void 툴_호출_0회에_세션_브랜드가_아닌_핸들이_있으면_미대조로_본다() {
		String answer = "경쟁사 계정 @other_brand_official 이 최근 활발히 올리고 있어요.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, "my_brand",
				Set.of());

		assertThat(result.ungrounded()).isTrue();
		assertThat(result.unmatchedHandles()).containsExactly("other_brand_official");
	}

	@Test
	void 툴_호출_0회여도_세션_브랜드_핸들만_있으면_날조가_아니다() {
		String answer = "질문하신 @my_brand 계정 기준으로 안내드릴게요.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, "my_brand",
				Set.of());

		assertThat(result.ungrounded()).isFalse();
		assertThat(result.unmatchedHandles()).isEmpty();
	}

	@Test
	void 툴_호출_0회여도_표와_핸들이_없으면_날조가_아니다() {
		String answer = "이 어시스턴트는 브랜드 모니터링 데이터에 대해서만 답할 수 있어요.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, null, Set.of());

		assertThat(result.ungrounded()).isFalse();
	}

	@Test
	void 툴_1회_호출_후_표_셀_계정명이_그라운딩_집합에_있으면_날조가_아니다() {
		String answer = "| 계정 | 게시물 |\n| @yoon_yoon_ | 11 |";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 1, null,
				Set.of("yoon_yoon_"));

		assertThat(result.ungrounded()).isFalse();
		assertThat(result.unmatchedHandles()).isEmpty();
	}

	@Test
	void 툴_1회_호출해도_표_셀_계정명이_그라운딩_집합에_없으면_미대조로_본다() {
		String answer = "| 계정 | 게시물 |\n| @yoon_yoon_ | 11 |";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 1, null,
				Set.of("other_account"));

		assertThat(result.ungrounded()).isTrue();
		assertThat(result.unmatchedHandles()).containsExactly("yoon_yoon_");
	}

	@Test
	void 그라운딩_집합_대조는_대소문자를_무시한다() {
		String answer = "| 계정 | 게시물 |\n| @Yoon_Yoon_ | 11 |";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 1, null,
				Set.of("yoon_yoon_"));

		assertThat(result.ungrounded()).isFalse();
	}

	@Test
	void 표_구분선_행과_숫자_셀은_후보에서_제외한다() {
		String answer = "| 계정 | 조회수 | 참여율 |\n| --- | --- | --- |\n| @yoon_yoon_ | 14520 | 1.82 |";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 1, null,
				Set.of("yoon_yoon_"));

		// 구분선 행("---")·숫자 셀("14520"·"1.82")은 애초에 후보가 아니라서 그라운딩 집합에 없어도
		// 미대조에 안 잡힌다 - yoon_yoon_만 후보였고 그건 집합에 있다.
		assertThat(result.ungrounded()).isFalse();
	}

	/** aggregate_posts groupBy=author 결과의 그룹 키(=계정명)가 그라운딩 집합에 잡히는지 검증한다
	 * (2026-09-02 갭 보완 - 처음엔 이 경로가 빠져 인플루언서 랭킹 답변마다 불필요한 mode ANY 재시도가
	 * 돌았다). {@link BrandAiToolbox}가 groupBy=author일 때만 그룹 키를 "author" 필드로도 명시하는
	 * 페이로드 계약을 그대로 재현해 이 클래스가 그 필드를 실제로 집계하는지 확인한다. */
	@Test
	void aggregate_posts_groupBy_author_결과의_계정명이_그라운딩_집합에_포함된다() throws Exception {
		ObjectMapper om = new ObjectMapper();
		JsonNode payload = om.readTree("""
				{"groupBy":"author","groups":[
				  {"key":"yoon_yoon_","author":"yoon_yoon_","postCount":11,"followers":50000},
				  {"key":"other_account","author":"other_account","postCount":3,"followers":1000}
				]}
				""");

		Set<String> handles = new LinkedHashSet<>();
		BrandAiGroundednessGuard.collectGroundedHandles(payload, handles);

		assertThat(handles).contains("yoon_yoon_", "other_account");
	}

	/** groupBy=month 등 다른 축은 그룹 키가 계정명이 아니므로("2026-08" 같은 값) "author" 필드가 없다 -
	 * 범용 "key" 필드 하나만으로는 계정명으로 오인해 잡지 않는다는 것을 고정한다. */
	@Test
	void 다른_groupBy의_범용_key_필드는_계정명으로_보지_않는다() throws Exception {
		ObjectMapper om = new ObjectMapper();
		JsonNode payload = om.readTree("""
				{"groupBy":"month","groups":[
				  {"key":"2026-08","postCount":11}
				]}
				""");

		Set<String> handles = new LinkedHashSet<>();
		BrandAiGroundednessGuard.collectGroundedHandles(payload, handles);

		assertThat(handles).isEmpty();
	}

	/** 골드셋 chain-referent-resolution 실측 실패 재현("작성자 'laura.acds'의 프로필 정보입니다") - 계정명이
	 * 작은따옴표로만 감싸져 @핸들도 표 셀도 아니라서 기존 (a)(b) 어디에도 안 걸렸다. */
	@Test
	void 툴_호출_0회에_작은따옴표로_감싼_계정명이_있으면_미대조로_본다() {
		String answer = "작성자 'laura.acds'의 프로필 정보입니다. 팔로워 수: 10,676명";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, null, Set.of());

		assertThat(result.ungrounded()).isTrue();
		assertThat(result.unmatchedHandles()).containsExactly("laura.acds");
	}

	/** 백틱으로 감싼 shortCode(대문자 포함)는 계정명 후보 패턴(소문자·숫자·점·밑줄만)에 안 맞아 자연히
	 * 제외된다 - 게시물 shortCode를 계정명으로 오인해 불필요한 재시도가 도는 것을 막는다. */
	@Test
	void 백틱으로_감싼_대문자_섞인_shortCode는_후보가_아니다() {
		String answer = "게시물 코드는 `DcG1oOthloi` 입니다.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, null, Set.of());

		assertThat(result.ungrounded()).isFalse();
	}

	/** 툴 1회 호출 후 큰따옴표로 감싼 계정명이 그라운딩 집합에 있으면 정상 통과한다. */
	@Test
	void 툴_1회_호출_후_큰따옴표로_감싼_계정명이_그라운딩_집합에_있으면_날조가_아니다() {
		String answer = "요청하신 \"bhavikadhall\" 계정의 최근 성과입니다.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 1, null,
				Set.of("bhavikadhall"));

		assertThat(result.ungrounded()).isFalse();
		assertThat(result.unmatchedHandles()).isEmpty();
	}

	/** 따옴표로 감싼 한글·공백 섞인 일반 문구는 계정명 후보 패턴에 안 맞아 애초에 신호로 잡히지
	 * 않는다(표·핸들·따옴표 계정명 셋 다 없어 최초 신호 자체가 없다). */
	@Test
	void 따옴표로_감싼_한글_문구는_후보가_아니다() {
		String answer = "이번 게시물은 \"설명 부족\"이 아쉬운 점으로 보입니다.";

		BrandAiGroundednessGuard.Result result = BrandAiGroundednessGuard.ungrounded(answer, 0, null, Set.of());

		assertThat(result.ungrounded()).isFalse();
	}
}
