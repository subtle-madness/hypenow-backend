package com.celfit.was.v1.brandmonitoring.ai;

import java.util.Map;

/**
 * 프리셋 질문 폴백(FE 변경요청서 2026-08-28 §3.1) - presetId별로 시스템 프롬프트 뒤에 덧붙이는 추가
 * 지시문. FE가 프리셋 버튼을 누르면 text(질문 문구)와 함께 presetId를 실어 보내는데, 이 지시문은
 * 모델이 그 질문 의도에 맞는 툴을 바로 골라 쓰도록 힌트를 준다.
 *
 * <p>미등록 presetId는 오류가 아니라 무시한다(자유 질의로 폴백, {@link #instructionFor}가 빈 문자열을
 * 돌려준다) - FE가 신규 프리셋을 추가했는데 백엔드가 미처 못 따라가도 요청 자체는 죽지 않는다.
 */
final class BrandAiPresets {

	private static final Map<String, String> INSTRUCTIONS = Map.of(
			"efficient_influencers", """

					[프리셋] 사용자가 "효율 좋은 인플루언서" 프리셋을 선택했습니다. list_posts로 게시물별 게시자·조회수를
					모아 게시자별로 묶고, get_author로 팔로워 수를 확인해 팔로워 대비 조회수(도달 배수)가 높은 게시자를
					상위로 안내하세요. 팔로워 수가 없거나 0인 게시자는 배수를 계산하지 말고 그 사실을 밝히세요.
					""",
			"top_posts", """

					[프리셋] 사용자가 "인기 게시물" 프리셋을 선택했습니다. aggregate_posts나 list_posts(sort=performance_desc)로
					조회수 기준 상위 게시물을 찾아 5열 이하 표로 정리해 답하세요.
					""",
			"sponsored_vs_organic", """

					[프리셋] 사용자가 "협찬 vs 오가닉 비교" 프리셋을 선택했습니다. aggregate_posts로 조회 범위 안 게시물의
					성과를 확인하고, list_posts의 sponsorship 필드로 광고 표기 여부를 나눠 비교하세요. 참여율은 4번 규칙의
					산식을 그대로 씁니다.
					""",
			"tagged_posts_analysis", """

					[프리셋] 사용자가 "태그된 게시물 분석" 프리셋을 선택했습니다. list_posts·search_posts로 브랜드에 태그된
					게시물의 최근 흐름과 특징(주제·언급 빈도 등)을 정리해 답하세요.
					""",
			"paid_amplify", """

					[프리셋] 사용자가 "유료 증폭 후보" 프리셋을 선택했습니다. list_posts(sort=performance_desc)나
					aggregate_posts로 이미 성과가 좋은 오가닉 게시물을 찾아 유료 증폭(부스팅) 후보로 제안하세요. 실제 광고
					집행 여부는 데이터에 없으니 추천 근거는 실측 지표(조회수·참여율 등)로만 듭니다.
					""");

	private BrandAiPresets() {
	}

	/** 등록된 presetId면 지시문, 아니면(null·미등록) 빈 문자열 - 호출부가 항상 시스템 프롬프트에 그대로 이어붙일 수 있게 한다. */
	static String instructionFor(String presetId) {
		if (presetId == null) {
			return "";
		}
		return INSTRUCTIONS.getOrDefault(presetId, "");
	}
}
